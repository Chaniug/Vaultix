/*
 * Vaultix — app:ui:addvault
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * 本地 KDBX 库：**打开已有** / **新建空白** 两态在同一页。
 *
 * 打开态：SAF `OpenDocument` 选文件 → 主密码（+ 可选 keyfile）→ 解锁入库。
 * 新建态：库名 + 主密码（两次）→ SAF `CreateDocument` 选保存位置 → 建库。
 *
 * ⚠️ 两态用**不同的 SAF 契约**（`OpenDocument` vs `CreateDocument`），
 * 且取持久权限的**标志位不同**：打开只要读（库文件我们不改），
 * 新建要**读写**（那是我们自己的库）。见 [persistReadPermission] / [persistWritePermission]。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.vaultix.ui.addvault

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.vaultix.vaultix.R
import io.vaultix.vaultix.ui.error.unlockErrorText
import io.vaultix.vaultix.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * MIME 过滤：KDBX 没有注册 MIME 类型，故按通配 MIME（星号斜杠星号）打开并靠引擎判格式
 * （见 `KdbxFormat`，签名 + 版本双重识别）。
 *
 * ⚠️ 注释里不要写「星号 + 斜杠」的字面序列：Kotlin 块注释**支持嵌套**，
 * 那个序列会被解析成注释结束符，导致文件后半段全部变成语法错误
 * （`.ai/ISSUES.md` #9 的同一个坑，当年排查了很久）。
 */
private const val ANY_MIME = "*/*"

/** 新建时的默认文件名（系统面板里预填；用户可改）。 */
private const val DEFAULT_KDBX_FILE_NAME = "vault.kdbx"

/**
 * 本地 KDBX 库（打开 / 新建两态）。
 *
 * ⚠️ SAF 持久授权：三个 picker 全部走 `OpenDocument` / `CreateDocument`
 * （而不是 `GetContent`），并在回调里 `takePersistableUriPermission` ——
 * KDBX 库每次解锁都要重读文件，没有持久授权就会出现「今天能解锁、明天说读不到文件」。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddKdbxScreen(
    onBack: () -> Unit,
    onAdded: () -> Unit,
    viewModel: AddKdbxViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    // 文案先取好：LaunchedEffect 里不能直接 stringResource
    val addedText = stringResource(R.string.add_kdbx_added)
    val updatedText = stringResource(R.string.add_kdbx_updated)
    val createdText = stringResource(R.string.add_kdbx_create_saved)

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is AddKdbxViewModel.Event.VaultAdded -> {
                    // ⚠️ 成功路径**必须有反馈**（`.ai/ISSUES.md` #94）：重复添加同一文件时
                    // 列表零变化（upsert 覆盖同一行），不给提示用户只会读成「毫无反应」。
                    snackbarHostState.showSnackbar(
                        if (event.isUpdate) updatedText else addedText,
                    )
                    onAdded()
                }

                AddKdbxViewModel.Event.VaultCreated -> {
                    // 新库同样是**已解锁**的（`Kdbx.createVault` 顺手登记了会话），
                    // 所以这里直接回列表，用户点进去就能用。
                    snackbarHostState.showSnackbar(createdText)
                    onAdded()
                }
            }
        }
    }

    val databasePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let { persistReadPermission(context, it) }
        viewModel.onDatabasePicked(context, uri)
    }
    val keyFilePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let { persistReadPermission(context, it) }
        viewModel.onKeyFilePicked(context, uri)
    }

    // ★ `CreateDocument`：用户在系统面板里**输入新文件名**，返回一个**不存在**的新文件。
    //   与 `OpenDocument`（选一个已存在的）是两个不同的契约，不能互换 ——
    //   用 `OpenDocument` 建不出文件，用 `CreateDocument` 打不开已有文件。
    val createTargetPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(ANY_MIME),
    ) { uri ->
        // ⚠️ 新建要**读写**权限：这是我们要长期持有的库，只给读会让"改一条"
        //   在下次启动后失败。打开态只要读（那个文件不归我们写）。
        val persisted = uri?.let { persistWritePermission(context, it) }
        // keyfile 的字节在这里读好再交给 ViewModel —— ViewModel 不持有 Context（见其 KDoc）。
        val keyFileUri = state.keyFileUri
        scope.launch {
            val keyFileBytes = withContext(Dispatchers.IO) { readBytesOrNull(context, keyFileUri) }
            viewModel.createVault(uri = uri, persistedUri = persisted, keyFileBytes = keyFileBytes)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.add_kdbx_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        // ⚠️ 版式纪律（2026-10-01 第 67 轮重做）：
        //  ① **不再垂直居中**（原先 `Arrangement.Center`）。打开态内容少 ⇒ 居中会把
        //     整块表单吊在屏幕中间、上下各留一大片；而新建态内容多 ⇒ 又变成贴顶。
        //     同一页两个模式各一套位置 = 「一屏里出现两套规格」（8.4 里列的第一号廉价感来源）。
        //     现在统一**从顶往下排**，用 `Spacing.lg` 做内容与顶栏的呼吸。
        //  ② 左右内边距从 `Spacing.xl`(24dp) 收到 `Spacing.lg`(16dp)：与全 App
        //     其余页面同源。24dp 只有这里用，是"局部规格"。
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(padding)
                .padding(horizontal = Spacing.lg, vertical = Spacing.lg),
        ) {
            // ⚠️ 副标题只说**别人不知道的那件事**：文件留在本机。
            //   原先那段是「打开已有的 KeePass 数据库，或新建一个空白库。文件留在本机，Vaultix 不上传。」
            //   —— 前半句与**正下方的模式切换器完全重复**（切换器上就写着"打开已有 / 新建空白"），
            //   而下方的按钮也已说明后续动作。8.4：「一个元素只该说一件事」，
            //   重复的信息不仅占地方，还会让首屏最重的一块文字压在顶栏下方，观感发闷。
            Text(
                text = stringResource(R.string.add_kdbx_privacy_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Spacing.lg))

            ModeSwitcher(
                mode = state.mode,
                enabled = !state.submitting,
                onModeChange = viewModel::onModeChange,
            )
            Spacer(Modifier.height(Spacing.xl))

            if (state.mode == AddKdbxViewModel.Mode.Open) {
                OpenSection(
                    state = state,
                    viewModel = viewModel,
                    onPickDatabase = { databasePicker.launch(arrayOf(ANY_MIME)) },
                    onPickKeyFile = { keyFilePicker.launch(arrayOf(ANY_MIME)) },
                )
            } else {
                CreateSection(
                    state = state,
                    viewModel = viewModel,
                    onPickKeyFile = { keyFilePicker.launch(arrayOf(ANY_MIME)) },
                    onSubmit = {
                        // ⚠️ 先校验、**后**弹面板：面板一旦确认，系统就真的建了一个空文件，
                        //   表单填错时它就成了磁盘上的孤儿（见 ViewModel 的文件头说明）。
                        if (viewModel.requestSaveLocation()) {
                            createTargetPicker.launch(DEFAULT_KDBX_FILE_NAME)
                        }
                    },
                )
            }

            // ⚠️ `forKdbx = true`：KDBX 没有邮箱，凭据错必须说"主密码不正确"
            //    （默认文案是给 Bitwarden 的"邮箱或主密码不正确"，见 ErrorText 的 KDoc）。
            unlockErrorText(state.error, forKdbx = true)?.let { message ->
                Spacer(Modifier.height(Spacing.sm))
                Row(verticalAlignment = Alignment.Top) {
                    Icon(
                        imageVector = Icons.Filled.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(start = Spacing.sm),
                    )
                }
            }

            // ⚠️ 提交中**只在按钮里**显示进度（转圈），**不再**在下方另画一条波浪进度条。
            //   原因：两者说的都是"正在处理"，同一件事报两遍（8.4「一个元素只该说一件事」），
            //   且 `ISSUES #87` 的上下文里用户明确不喜欢堆叠动效。
            //   下面的文案只负责说**在做什么**（"正在解密数据库…"），不重复表达"忙"。
            //   ⚠️ 左对齐（不居中）：本页整体是左对齐版式，居中的孤行会另起一套对齐基准。
            if (state.submitting) {
                Spacer(Modifier.height(Spacing.md))
                Text(
                    text = stringResource(
                        if (state.mode == AddKdbxViewModel.Mode.Open) {
                            R.string.add_kdbx_working
                        } else {
                            R.string.add_kdbx_create_working
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(Spacing.xl))
        }
    }
}

/**
 * 「打开已有 / 新建空白」两段式切换。
 *
 * ## 2026-10-01（第 67 轮）修掉的两个观感问题
 *
 * ① 🔴 **选中项曾被 `enabled = false`**（原写法 `enabled && mode != entry`）
 *    —— 本意是"别重复点已选项"，但 `enabled = false` 的语义是**不可用**，
 *    M3 会把文字降到 `alpha 0.38` 且**去掉选中态的容器色**。结果是：
 *    选中的那一半看起来是**灰掉的坏按钮**，没选中的那一半反而更"亮"、更像能点。
 *    ⇒ 用户看到的是"选中态反了"。
 *    修法：**永远 `enabled`**，重复点已选项由 `onModeChange` 幂等吞掉
 *    （ViewModel 里切同值会清错误并重设状态，无副作用）。
 *    「已选中」是**状态**，只该由选中容器色表达，不该借用"禁用"这个另一套语义。
 *
 * ② 原本没有强调"当前在选哪种"，切换后除了半段变色没有任何提示
 *    ⇒ 现在 label 里带一个**类型图标**（打开 = 文件夹，新建 = 加号），
 *    图标与文字共同编码，深色/浅色与动态取色下都比"半段底色差"更易读。
 *    ⚠️ 类型图标放在 **label 行内**，而**不是**塞进 `icon` 槽 —— 因为 `icon` 槽的
 *    默认值是 `SegmentedButtonDefaults.Icon(selected)`（选中时才淡入一个 check 勾），
 *    那是 M3 表达"选中"的标准手段；占掉它等于**丢掉组件自带的选中反馈**。
 *    两者各司其职：`icon` 槽说"选没选中"，label 行内图标说"这是什么选项"。
 *    ⚠️ 该默认动效（`fadeIn + scaleIn`，`MotionSchemeKeyTokens.FastSpatial`）
 *    是**组件自带**的 M3 规范动效，不是我们手加的 —— 与 `ISSUES #87`
 *    「不要花里胡哨的动画」不冲突（那条针对的是页面转场与自造动效）。
 *
 * ⚠️ 这里用的是 **stable 的 `SegmentedButton`**（`SingleChoiceSegmentedButtonRow`），
 * **不是** M3E 的 alpha `ButtonGroup` —— 见 `8.8` §6①「交互框架等 beta/stable 再换」。
 * 这一条刻意守住：模式切换是页面的骨架控件，不该押在会改名的 alpha API 上。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModeSwitcher(
    mode: AddKdbxViewModel.Mode,
    enabled: Boolean,
    onModeChange: (AddKdbxViewModel.Mode) -> Unit,
) {
    val modes = AddKdbxViewModel.Mode.entries
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        modes.forEachIndexed { index, entry ->
            SegmentedButton(
                selected = mode == entry,
                onClick = { onModeChange(entry) },
                // ⚠️ 只受"整体是否可交互"控制，**不**因"已选中"而禁用（见 KDoc ①）。
                enabled = enabled,
                shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                // icon 槽留给默认的「选中勾」（不传 = 用默认值），见 KDoc ②。
                label = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                    ) {
                        Icon(
                            imageVector = if (entry == AddKdbxViewModel.Mode.Open) {
                                Icons.Filled.FolderOpen
                            } else {
                                Icons.Filled.Add
                            },
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            stringResource(
                                if (entry == AddKdbxViewModel.Mode.Open) {
                                    R.string.add_kdbx_mode_open
                                } else {
                                    R.string.add_kdbx_mode_create
                                },
                            ),
                        )
                    }
                },
            )
        }
    }
}

/** 打开态的表单：选文件 → （可选）keyfile → 主密码 → 打开。 */
@Composable
private fun OpenSection(
    state: AddKdbxViewModel.UiState,
    viewModel: AddKdbxViewModel,
    onPickDatabase: () -> Unit,
    onPickKeyFile: () -> Unit,
) {
    PickedFileRow(
        icon = Icons.Filled.Description,
        label = stringResource(R.string.add_kdbx_file),
        value = state.fileName,
        pickLabel = stringResource(R.string.add_kdbx_pick_file),
        onPick = onPickDatabase,
    )
    Spacer(Modifier.height(Spacing.md))

    // keyfile 是**可选**项，与主密码那种必填字段不该同间距
    // （8.4：间距本身携带"这两件事有多相关"的信息）。
    KeyFileRow(state = state, viewModel = viewModel, onPickKeyFile = onPickKeyFile)
    Spacer(Modifier.height(Spacing.md))

    PasswordField(state = state, viewModel = viewModel)
    Spacer(Modifier.height(Spacing.xl))

    SubmitButton(
        label = stringResource(R.string.add_kdbx_submit),
        enabled = state.canSubmitOpen,
        submitting = state.submitting,
        onSubmit = viewModel::submit,
    )
    Spacer(Modifier.height(Spacing.xxl))
}

/** 新建态的表单：库名 → 主密码（两次）→ （可选）keyfile → 选位置并创建。 */
@Composable
private fun CreateSection(
    state: AddKdbxViewModel.UiState,
    viewModel: AddKdbxViewModel,
    onPickKeyFile: () -> Unit,
    onSubmit: () -> Unit,
) {
    OutlinedTextField(
        value = state.vaultName,
        onValueChange = viewModel::onVaultNameChange,
        label = { Text(stringResource(R.string.add_kdbx_create_name)) },
        placeholder = { Text(stringResource(R.string.add_kdbx_create_name_hint)) },
        supportingText = { Text(stringResource(R.string.add_kdbx_create_name_helper)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(Spacing.md))

    PasswordField(
        state = state,
        viewModel = viewModel,
        labelRes = R.string.add_kdbx_create_password,
        supportingRes = R.string.add_kdbx_create_password_helper,
    )
    // ⚠️ 两次密码之间用 `sm`(8dp) 而非 `md`(12dp)：它俩是**同一个字段的两半**，
    //    不是两个字段。间距差异正是在说这件事（原先两处都是 md，看不出关系）。
    Spacer(Modifier.height(Spacing.sm))

    // ⚠️ 二次输入：KDBX 的主密码**没有找回途径**，这是唯一能在"设"的这一刻挡住笔误的地方。
    RepeatPasswordField(state = state, viewModel = viewModel)
    Spacer(Modifier.height(Spacing.md))

    KeyFileRow(state = state, viewModel = viewModel, onPickKeyFile = onPickKeyFile)
    Spacer(Modifier.height(Spacing.xl))

    SubmitButton(
        label = stringResource(R.string.add_kdbx_create_submit),
        enabled = state.canSubmitCreate,
        submitting = state.submitting,
        onSubmit = onSubmit,
    )
    Spacer(Modifier.height(Spacing.xxl))
}

/**
 * 提交按钮（抽出来避免主函数越 detekt 的行数门禁）。
 *
 * ⚠️ 2026-10-01（第 67 轮）两处改动：
 * ① `FilledTonalButton` → **`Button`**。整页只有这一个主操作，tonal 的低强调度让它
 *    和旁边的 `OutlinedButton`（"选择"）分不出主次；页面级的"打开/创建"该用主色。
 * ② 高 **48dp → 56dp**（M3 的 large button 规格）。48dp 在满宽按钮上偏扁，
 *    且与下方 `Spacing.xxl` 的收尾留白比例不协调。
 *
 * `submitting` 时按钮内嵌 20dp 小转圈而**不是**波浪进度条：
 * 见 `8.8` §4 P1-1 实测表 —— 18/20dp 内联小转圈换成波浪会被撑到 **48dp**，
 * 把按钮与文案挤开，所以这一类**本就不该换**。
 */
@Composable
private fun SubmitButton(
    label: String,
    enabled: Boolean,
    submitting: Boolean,
    onSubmit: () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    Button(
        onClick = {
            focusManager.clearFocus()
            onSubmit()
        },
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
    ) {
        if (submitting) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        } else {
            Text(label, style = MaterialTheme.typography.titleSmall)
        }
    }
}

/**
 * 主密码输入（KDBX 无邮箱/服务器，只有这一个必填字段）。
 *
 * @param labelRes 默认是打开态的「数据库主密码」；新建态传「设定主密码」——
 *   见 ViewModel 文件头关于**两态文案不能共用**的说明。
 * @param supportingRes 辅助说明（新建态用它声明"没有找回途径"）。
 */
@Composable
private fun PasswordField(
    state: AddKdbxViewModel.UiState,
    viewModel: AddKdbxViewModel,
    labelRes: Int = R.string.add_kdbx_password,
    supportingRes: Int? = null,
) {
    OutlinedTextField(
        value = state.password,
        onValueChange = viewModel::onPasswordChange,
        label = { Text(stringResource(labelRes)) },
        supportingText = if (supportingRes != null) {
            { Text(stringResource(supportingRes)) }
        } else {
            null
        },
        singleLine = true,
        visualTransformation = passwordTransformation(state.passwordVisible),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            imeAction = if (supportingRes != null) ImeAction.Next else ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { viewModel.submit() }),
        trailingIcon = {
            PasswordVisibilityToggle(
                visible = state.passwordVisible,
                onToggle = { viewModel.onPasswordVisibleChange(!state.passwordVisible) },
            )
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

/** 新建态的二次密码输入。 */
@Composable
private fun RepeatPasswordField(
    state: AddKdbxViewModel.UiState,
    viewModel: AddKdbxViewModel,
) {
    val focusManager = LocalFocusManager.current
    OutlinedTextField(
        value = state.passwordRepeat,
        onValueChange = viewModel::onPasswordRepeatChange,
        label = { Text(stringResource(R.string.add_kdbx_create_password_repeat)) },
        singleLine = true,
        visualTransformation = passwordTransformation(state.passwordVisible),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = {
            focusManager.clearFocus()
        }),
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun passwordTransformation(visible: Boolean): VisualTransformation =
    if (visible) VisualTransformation.None else PasswordVisualTransformation()

/** 明文/密文切换按钮（两处密码框共用，保证图标与无障碍文案一致）。 */
@Composable
private fun PasswordVisibilityToggle(visible: Boolean, onToggle: () -> Unit) {
    IconButton(onClick = onToggle) {
        Icon(
            imageVector = if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
            contentDescription = stringResource(
                if (visible) R.string.add_vault_password_hidden else R.string.add_vault_password_visible,
            ),
        )
    }
}

/**
 * keyfile 一行：默认收起为「添加密钥文件」，选了之后展开为一行可清除的记录。
 *
 * 密钥文件是**可选**的：一上来就摆一个输入框会让无 keyfile 的绝大多数用户
 * 以为必须提供（KeePass 的 keyfile 是少数派用法）。
 */
@Composable
private fun KeyFileRow(
    state: AddKdbxViewModel.UiState,
    viewModel: AddKdbxViewModel,
    onPickKeyFile: () -> Unit,
) {
    if (state.keyFileUri == null) {
        TextButton(onClick = onPickKeyFile) {
            Icon(Icons.Filled.Key, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(Spacing.sm))
            Text(stringResource(R.string.add_kdbx_add_keyfile))
        }
    } else {
        PickedFileRow(
            icon = Icons.Filled.Key,
            label = stringResource(R.string.add_kdbx_keyfile),
            value = state.keyFileName,
            pickLabel = stringResource(R.string.add_kdbx_pick_file),
            onPick = onPickKeyFile,
            onClear = viewModel::clearKeyFile,
        )
    }
}

/**
 * 「已选文件」一行：图标 + 说明 + 文件名（+ 可选清除）。
 *
 * ⚠️ 2026-10-01（第 67 轮）**从"裸 Row"改成容器**。原状是直接铺在页面背景上的一行
 * （图标 + 上下两行字 + 右侧按钮），四周没有边界 —— 而页面下方全是
 * `OutlinedTextField`（**自带描边容器**）⇒ **一屏里出现两套规格**：
 * 上半段是"悬浮的字"，下半段是"有框的字段"，观感廉价的首要来源。
 *
 * 用 `Card` 而不是手绘 `clip + background + border`：设置页的
 * `SettingsGroupCard` 已是同一形态（`Card` + `surfaceContainerLow` + 描边），
 * **复用组件语义而不是重写一遍几何**，M3 换 token 时也自动跟随。
 * ⚠️ `surfaceContainerLow` 比条目卡片的 `surfaceContainerLowest` **高一档** ——
 * 8.4「嵌套容器色阶必须换档，禁止同色叠 alpha」。
 */
@Composable
private fun PickedFileRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    pickLabel: String,
    onPick: () -> Unit,
    onClear: (() -> Unit)? = null,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Spacing.md),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md, vertical = Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
            Column(modifier = Modifier.weight(1f).padding(start = Spacing.md)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = value.ifBlank { pickLabel },
                    style = MaterialTheme.typography.bodyMedium,
                    // 未选文件时是"待办提示"，用弱色；选好后是**事实**，用正常前景色
                    // （原先两者同色，看不出"到底选没选"）。
                    color = if (value.isBlank()) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (onClear != null) {
                IconButton(onClick = onClear) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(R.string.add_kdbx_clear_keyfile),
                    )
                }
            }
            OutlinedButton(onClick = onPick) {
                Text(stringResource(R.string.add_kdbx_browse))
            }
        }
    }
}

/**
 * 取得**持久读权限**（打开已有文件）。
 *
 * 失败时静默（部分文档提供方不支持持久授权）：那会让下次解锁时读不到文件并如实报
 * 「请重新选择文件」，比在这里弹一个用户看不懂的错误要好。
 */
private fun persistReadPermission(context: Context, uri: Uri) {
    runCatching {
        context.contentResolver.takePersistableUriPermission(
            uri,
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
    }
}

/**
 * 取得**持久读写权限**（新建的库归我们管，只给读会让"改一条"在重启后失败）。
 *
 * @return 授权后的 URI；失败返回 null。
 *   ★ 为什么返回 URI 而不是像 [persistReadPermission] 那样吞掉失败：
 *   新建路径上"授权失败"与"用户取消"必须**分开**，前者建了库也活不过一次重启。
 *   返回 null 让 ViewModel 能给出明确的错误。
 */
private fun persistWritePermission(context: Context, uri: Uri): Uri? = runCatching {
    context.contentResolver.takePersistableUriPermission(
        uri,
        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
            android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
    )
    uri
}.getOrNull()

/** 读一个 `content://` 的全部字节（keyfile）；读不到返回 null。 */
private fun readBytesOrNull(context: Context, uri: String?): ByteArray? {
    val target = uri?.takeIf { it.isNotBlank() } ?: return null
    return runCatching {
        context.contentResolver.openInputStream(Uri.parse(target))?.use { it.readBytes() }
    }.getOrNull()
}
