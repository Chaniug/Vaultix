package io.vaultix.vaultix.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.vaultix.common.PasswordStrength
import io.vaultix.common.SshFingerprint
import io.vaultix.common.UriFormat
import io.vaultix.model.CustomFieldType
import io.vaultix.model.VaultCustomField
import io.vaultix.model.VaultFolder
import io.vaultix.model.VaultItem
import io.vaultix.model.VaultItemType
import io.vaultix.model.VaultLinkedId
import io.vaultix.model.VaultReprompt
import io.vaultix.vaultix.R
import io.vaultix.vaultix.ui.qr.QrScannerContent
import io.vaultix.vaultix.ui.theme.Spacing

/**
 * 条目表单对话框（新建 / 编辑共用，Docs/08 S10）。
 *
 * 按条目类型决定可编辑字段，**除安全笔记外都可编辑**：
 * - Login：用户名 / 密码（含实时强度条）/ 网址（多值）/ TOTP 密钥；
 * - Card：持卡人 / 发卡行 / 卡号 / 有效期月·年 / 安全码（对齐 Bitwarden card 载荷）；
 * - Identity：全量 17 字段（对齐 Bitwarden identity 载荷，覆盖 Bastion 兼容缺陷）；
 * - SshKey：私钥 / 公钥 / 指纹（指纹由公钥自动推导、可手改，见 [applyPublicKeyChange]）；
 * - SecureNote：仅名称 + 备注（该类型本就没有专属字段）。
 *
 * [typeEditable]：仅**新建**时开放类型选择。编辑态不允许改类型——改类型会让
 * 原类型载荷失去意义，且写路径有类型守恒守卫。
 *
 * 密码等敏感值只在对话框存续期间存在于内存；关闭/保存后由调用方保证不再持有副本。
 * 编辑场景用 [initial] 预填；切换目标条目时（key 变化）状态自动重置。
 * 保存回传的是 [initial] 的 copy，未在本表单暴露的段（自定义字段 / 通行密钥等）原样保留。
 *
 * 纯逻辑（快照组装 / 字段列表）见 [FormValues]，本文件只负责 UI。
 */
@Composable
fun ItemFormDialog(
    title: String,
    initial: VaultItem,
    folders: List<VaultFolder> = emptyList(),
    saving: Boolean = false,
    typeEditable: Boolean = false,
    onDismiss: () -> Unit,
    onSave: (VaultItem) -> Unit,
) {
    var type by rememberSaveable(initial) { mutableStateOf(initial.type) }
    var name by rememberSaveable(initial) { mutableStateOf(initial.title) }
    var username by rememberSaveable(initial) { mutableStateOf(initial.username) }
    var password by rememberSaveable(initial) { mutableStateOf(initial.password) }
    var notes by rememberSaveable(initial) { mutableStateOf(initial.notes) }
    var totp by rememberSaveable(initial) { mutableStateOf(initial.totp.orEmpty()) }
    val uris = rememberSaveable(initial, saver = STRING_LIST_SAVER) {
        mutableStateListOf<String>().apply { addAll(initial.uris.map { it.uri }) }
    }
    val cardValues = rememberSaveable(initial, saver = STRING_LIST_SAVER) {
        mutableStateListOf<String>().apply { addAll(cardValuesOf(initial.card)) }
    }
    val identityValues = rememberSaveable(initial, saver = STRING_LIST_SAVER) {
        mutableStateListOf<String>().apply { addAll(identityValuesOf(initial.identity)) }
    }
    // SSH 三项（私钥 / 公钥 / 指纹）。指纹原本为空而公钥可解析时，**初始化即补上**：
    // 导入来的条目常缺指纹，补它是纯收益。而已存有指纹时绝不覆盖，避免静默重写数据。
    val sshValues = rememberSaveable(initial, saver = STRING_LIST_SAVER) {
        mutableStateListOf<String>().apply {
            addAll(sshValuesOf(initial.sshKey))
            if (get(SSH_FINGERPRINT_INDEX).isBlank()) {
                this[SSH_FINGERPRINT_INDEX] = SshFingerprint.of(get(SSH_PUBLIC_KEY_INDEX)).orEmpty()
            }
        }
    }
    val customFields = rememberSaveable(initial, saver = CUSTOM_FIELD_LIST_SAVER) {
        mutableStateListOf<VaultCustomField>().apply { addAll(initial.customFields) }
    }
    var favorite by rememberSaveable(initial) { mutableStateOf(initial.favorite) }
    var reprompt by rememberSaveable(initial) { mutableStateOf(initial.reprompt) }
    var folderId by rememberSaveable(initial) { mutableStateOf(initial.folderId) }
    var showNameError by rememberSaveable { mutableStateOf(false) }
    var scanning by rememberSaveable { mutableStateOf(false) }
    var showAppPicker by rememberSaveable { mutableStateOf(false) }

    // ⚠️ 2026-09-13 第二轮用户反馈：「编辑条目不是全屏显示，看起来不舒服。」
    // ⇒ 由 `AlertDialog`（居中卡片、宽度被平台限制在 ~280dp）改成**整页编辑**
    // （[FullScreenDialogShell]：顶部标题行 + 可滚动正文 + 底部固定操作条）。
    // 原 `text` 槽里的那段滚动 Column 也一并收进壳里，正文因此少了一层缩进。
    FullScreenDialogShell(
        title = title,
        onDismiss = { if (!saving) onDismiss() },
        confirmEnabled = !saving,
        confirmLabel = stringResource(R.string.action_save),
        onConfirm = {
            if (name.isBlank()) {
                showNameError = true
            } else {
                onSave(
                    buildSnapshot(
                        initial = initial,
                        type = type,
                        values = FormValues(
                            name = name,
                            username = username,
                            password = password,
                            notes = notes,
                            totp = totp,
                            uris = uris.toList(),
                            cardValues = cardValues.toList(),
                            identityValues = identityValues.toList(),
                        ),
                        meta = ItemMeta(
                            folderId = folderId,
                            favorite = favorite,
                            reprompt = reprompt,
                            customFields = customFields.toList(),
                        ),
                        ssh = sshValues.toList(),
                    ),
                )
            }
        },
    ) {
        FormHeader(
            folders = folders,
            folderId = folderId,
            onFolderSelect = { folderId = it },
            typeEditable = typeEditable,
            type = type,
            onTypeSelect = { type = it },
        )
        NameField(
            name = name,
            onNameChange = { name = it; showNameError = false },
            favorite = favorite,
            onFavoriteChange = { favorite = it },
            showError = showNameError,
        )
        FormDivider()
        when (type) {
            VaultItemType.Login -> LoginFields(
                username = username,
                onUsernameChange = { username = it },
                password = password,
                onPasswordChange = { password = it },
                uris = uris,
                totp = totp,
                onTotpChange = { totp = it },
                onScanTotp = { scanning = true },
                onPickApp = { showAppPicker = true },
            )
            VaultItemType.Card -> LabeledFields(CARD_LABELS, cardValues)
            VaultItemType.Identity -> LabeledFields(IDENTITY_LABELS, identityValues)
            VaultItemType.SshKey -> SshKeyFields(sshValues)
            // 安全笔记只有名称 + 备注（该类型本就没有专属字段可填）
            VaultItemType.SecureNote -> Unit
        }
        ItemFormTail(
            customFields = customFields,
            notes = notes,
            onNotesChange = { notes = it },
            reprompt = reprompt,
            onRepromptChange = { reprompt = it },
        )
    }

    QrScannerHost(
        scanning = scanning,
        onResult = { text ->
            totp = text
            scanning = false
        },
        onBack = { scanning = false },
    )
    AppPickerHost(
        show = showAppPicker,
        uris = uris,
        onDismiss = { showAppPicker = false },
    )
}

/**
 * 表单尾段：自定义字段 → 备注 → 附加选项（主密码二次验证）。
 *
 * 抽成独立 composable 是为了不让 [ItemFormDialog] 越过 detekt `LongMethod ≤150` 门禁；
 * 这三段在新建与编辑两种场景下完全一致，本来就该是一个可复用单元。
 */
@Composable
private fun ItemFormTail(
    customFields: SnapshotStateList<VaultCustomField>,
    notes: String,
    onNotesChange: (String) -> Unit,
    reprompt: VaultReprompt,
    onRepromptChange: (VaultReprompt) -> Unit,
) {
    FormDivider()
    SectionLabel(
        text = stringResource(R.string.section_custom_fields),
        icon = Icons.Filled.Tune,
    )
    Spacer(Modifier.height(Spacing.sm))
    CustomFieldsEditor(fields = customFields)
    Spacer(Modifier.height(Spacing.sm))
    OutlinedTextField(
        value = notes,
        onValueChange = onNotesChange,
        label = { Text(stringResource(R.string.item_field_notes)) },
        minLines = 2,
        modifier = Modifier.fillMaxWidth(),
    )
    // 主密码二次验证（对齐 Bitwarden「附加选项」里的开关）
    FormDivider()
    RepromptToggle(reprompt = reprompt, onRepromptChange = onRepromptChange)
}

/**
 * 扫码用**全屏 Dialog 内嵌相机**，而不是跳转到独立页面：
 * 这样结果可以直接回填 totp 字段，不会因为导航离开而丢失已填的其他内容。
 */
@Composable
private fun QrScannerHost(scanning: Boolean, onResult: (String) -> Unit, onBack: () -> Unit) {
    if (!scanning) return
    Dialog(
        onDismissRequest = onBack,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        QrScannerContent(onResult = onResult, onBack = onBack)
    }
}

/**
 * 关联手机 App：从已安装应用里选，写入 `androidapp://<package>`
 * （Bitwarden 官方 URI 形态，服务端与其它客户端都能识别，自动填充按包名匹配）。
 */
@Composable
private fun AppPickerHost(show: Boolean, uris: SnapshotStateList<String>, onDismiss: () -> Unit) {
    if (!show) return
    AppPickerDialog(
        onDismiss = onDismiss,
        onPick = { packageName ->
            val uri = UriFormat.androidAppUri(packageName)
            if (uri !in uris) uris.add(uri)
            onDismiss()
        },
    )
}

/**
 * 分区小标题。
 *
 * 2026-09-13 观感：给每个分区配一枚 **16dp 主色图标**，标题字色也从
 * `onSurfaceVariant` 提到 `primary`。此前所有分区都是同一句灰字，二十来个输入框
 * 连成一片，眼睛没有落点；有了图标后「文件夹 / 类型 / 登录信息 / 自定义字段 / 备注」
 * 各自成块，扫视即可定位（对齐 Bastion「编辑页分区带头图」的做法，但只在
 * 分区标题一级加，不把每个输入框都包进卡片 —— 那样会把表单撑得更长）。
 */
@Composable
private fun SectionLabel(text: String, icon: ImageVector) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(Spacing.lg),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/**
 * 表单分组之间的分隔线。
 *
 * 为什么加它：此前整张表单只有 `Spacer(8.dp)` 的**等距堆叠** —— 名称、凭据、自定义字段、
 * 备注、二次验证全是一样大的间距，读起来像"一长串输入框"，分不出结构（用户反馈
 * 「新建密码条目的页面也丑」）。一条极淡的分隔线就能把分组"读"出来，
 * 且不像卡片那样需要猜底色。
 */
@Composable
private fun FormDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(top = 18.dp, bottom = 14.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
    )
}

/**
 * 表单头部：文件夹（库里有文件夹才显示）+ 类型选择（仅新建显示）。
 * 抽出的目的：控制主函数长度与圈复杂度；顺序对齐 Bitwarden 官方
 * （文件夹在最上，其次类型、名称）。
 */
@Composable
private fun FormHeader(
    folders: List<VaultFolder>,
    folderId: String?,
    onFolderSelect: (String?) -> Unit,
    typeEditable: Boolean,
    type: VaultItemType,
    onTypeSelect: (VaultItemType) -> Unit,
) {
    if (folders.isNotEmpty()) {
        SectionLabel(text = stringResource(R.string.item_folder), icon = Icons.Filled.Folder)
        Spacer(Modifier.height(Spacing.sm))
        FolderPicker(folders = folders, selectedId = folderId, onSelect = onFolderSelect)
        Spacer(Modifier.height(Spacing.sm))
    }
    if (typeEditable) {
        SectionLabel(text = stringResource(R.string.item_field_type), icon = Icons.Filled.Category)
        Spacer(Modifier.height(Spacing.sm))
        TypePicker(selected = type, onSelect = onTypeSelect)
        Spacer(Modifier.height(Spacing.sm))
    }
}

/** 名称输入 + 收藏星标（对齐 Bitwarden：收藏在标题行右侧）。 */
@Composable
private fun NameField(
    name: String,
    onNameChange: (String) -> Unit,
    favorite: Boolean,
    onFavoriteChange: (Boolean) -> Unit,
    showError: Boolean,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = name,
            onValueChange = onNameChange,
            label = { Text(stringResource(R.string.item_field_name)) },
            singleLine = true,
            isError = showError,
            supportingText = if (showError) {
                { Text(stringResource(R.string.item_name_required)) }
            } else {
                null
            },
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = { onFavoriteChange(!favorite) }) {
            Icon(
                imageVector = if (favorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                contentDescription = stringResource(R.string.item_favorite),
                tint = if (favorite) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

/** 主密码二次验证开关（对齐 Bitwarden「附加选项」）。 */
@Composable
private fun RepromptToggle(
    reprompt: VaultReprompt,
    onRepromptChange: (VaultReprompt) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.item_reprompt),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(R.string.item_reprompt_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = reprompt == VaultReprompt.Password,
            onCheckedChange = {
                onRepromptChange(if (it) VaultReprompt.Password else VaultReprompt.None)
            },
        )
    }
}

/**
 * 文件夹选择（对齐 Bitwarden：名称上方）。
 *
 * 只在 [folders] 非空时由调用方渲染——未解锁 / 库还没有文件夹时隐藏，
 * 不显示一个「永远无选项」的下拉。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FolderPicker(
    folders: List<VaultFolder>,
    selectedId: String?,
    onSelect: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = folders.firstOrNull { it.id == selectedId }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected?.name ?: stringResource(R.string.item_folder_none),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.item_folder)) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.item_folder_none)) },
                onClick = {
                    onSelect(null)
                    expanded = false
                },
            )
            folders.forEach { folder ->
                DropdownMenuItem(
                    text = { Text(folder.name) },
                    onClick = {
                        onSelect(folder.id)
                        expanded = false
                    },
                )
            }
        }
    }
}

/** 新建条目的类型选择：一行可选筹码，选中态即当前类型。 */
@Composable
private fun TypePicker(
    selected: VaultItemType,
    onSelect: (VaultItemType) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        modifier = Modifier.fillMaxWidth(),
    ) {
        VaultItemType.entries.forEach { type ->
            FilterChip(
                selected = type == selected,
                onClick = { onSelect(type) },
                label = { Text(stringResource(itemTypeLabelRes(type))) },
            )
        }
    }
}

/** 登录条目专属字段：用户名 / 密码（含强度条）/ 网址 / TOTP 密钥。 */
@Composable
private fun LoginFields(
    username: String,
    onUsernameChange: (String) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    uris: SnapshotStateList<String>,
    totp: String,
    onTotpChange: (String) -> Unit,
    onScanTotp: () -> Unit,
    onPickApp: () -> Unit,
) {
    OutlinedTextField(
        value = username,
        onValueChange = onUsernameChange,
        label = { Text(stringResource(R.string.item_field_username)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(Spacing.sm))
    var showGenerator by remember { mutableStateOf(false) }
    // 明文查看开关：编辑已有条目时必须能确认原密码（否则只见掩码圆点，
    // 唯一能明文看到的反而是骰子生成的随机密码——Bitwarden 编辑页同款眼睛按钮）
    var revealPassword by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = password,
        onValueChange = onPasswordChange,
        label = { Text(stringResource(R.string.item_field_password)) },
        singleLine = true,
        visualTransformation = if (revealPassword) {
            VisualTransformation.None
        } else {
            PasswordVisualTransformation()
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            Row {
                IconButton(onClick = { revealPassword = !revealPassword }) {
                    Icon(
                        imageVector = if (revealPassword) {
                            Icons.Filled.VisibilityOff
                        } else {
                            Icons.Filled.Visibility
                        },
                        contentDescription = stringResource(R.string.item_reveal_value),
                    )
                }
                // 随机密码生成（Bastion 同款能力）：弹出选项对话框后回填
                IconButton(onClick = { showGenerator = true }) {
                    Icon(
                        Icons.Filled.Casino,
                        contentDescription = stringResource(R.string.item_generate_password),
                    )
                }
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
    if (showGenerator) {
        PasswordGeneratorDialog(
            onUse = {
                onPasswordChange(it)
                showGenerator = false
            },
            onDismiss = { showGenerator = false },
        )
    }
    PasswordStrengthHint(password = password)
    Spacer(Modifier.height(Spacing.sm))
    UriListEditor(
        uris = uris,
        onAdd = { uris.add("") },
        onRemove = { uris.removeAt(it) },
        onPickApp = onPickApp,
    )
    Spacer(Modifier.height(Spacing.sm))
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = totp,
            onValueChange = onTotpChange,
            label = { Text(stringResource(R.string.section_totp)) },
            placeholder = { Text(stringResource(R.string.item_totp_hint)) },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onScanTotp) {
            Icon(
                Icons.Filled.QrCodeScanner,
                contentDescription = stringResource(R.string.item_scan_totp),
            )
        }
    }
}

/**
 * 按 [labels] 顺序渲染一组单行输入框，值就地写入 [values]（索引一一对应）。
 * 银行卡与身份字段共用：避免为 17 个字段各声明一个状态变量。
 */
@Composable
private fun LabeledFields(labels: List<Int>, values: SnapshotStateList<String>) {
    labels.forEachIndexed { index, labelRes ->
        OutlinedTextField(
            value = values[index],
            onValueChange = { values[index] = it },
            label = { Text(stringResource(labelRes)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(Spacing.sm))
    }
}

/**
 * SSH 密钥输入（S22）：私钥 / 公钥多行，指纹单行。
 *
 * 三项都用**等宽**：密钥是机器文本，等宽便于逐字符核对（与详情页一致）。
 * 用 `LocalTextStyle.current.copy(...)` 而非自建 `TextStyle` —— 前者恰好等于
 * 输入框的默认样式只换字体，因此不可能把主题文字色弄丢。
 *
 * 私钥**不遮蔽**，与详情页 `SshKeySection`（明文 + 复制）保持一致；表单里遮蔽
 * 反而让用户没法确认自己粘对了没有 —— 而「确认贴对了」正是本表单要解决的问题。
 *
 * 指纹由公钥驱动：公钥的 onValueChange 走 [applyPublicKeyChange]，
 * 「自动 / 手动」的判定逻辑都在那边（纯函数，已单测）。
 */
@Composable
private fun SshKeyFields(values: SnapshotStateList<String>) {
    val monospace = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace)
    OutlinedTextField(
        value = values[SSH_PRIVATE_KEY_INDEX],
        onValueChange = { values[SSH_PRIVATE_KEY_INDEX] = it },
        label = { Text(stringResource(R.string.ssh_private_key)) },
        minLines = SSH_KEY_MIN_LINES,
        maxLines = SSH_KEY_MAX_LINES,
        textStyle = monospace,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(Spacing.sm))
    OutlinedTextField(
        value = values[SSH_PUBLIC_KEY_INDEX],
        onValueChange = { applyPublicKeyChange(values, it) },
        label = { Text(stringResource(R.string.ssh_public_key)) },
        minLines = SSH_KEY_MIN_LINES,
        maxLines = SSH_KEY_MAX_LINES,
        textStyle = monospace,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(Spacing.sm))
    OutlinedTextField(
        value = values[SSH_FINGERPRINT_INDEX],
        onValueChange = { values[SSH_FINGERPRINT_INDEX] = it },
        label = { Text(stringResource(R.string.ssh_fingerprint)) },
        supportingText = { Text(stringResource(R.string.ssh_fingerprint_hint)) },
        singleLine = true,
        textStyle = monospace,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(Spacing.sm))
}

/** SSH 密钥输入框的行数区间：私钥可能很长，给足可视空间又不至于撑爆整页。 */
private const val SSH_KEY_MIN_LINES = 3
private const val SSH_KEY_MAX_LINES = 8

/**
 * 密码强度条（Docs/08 S10）：分值 0–100 → 五档（弱/一般/良好/强/非常强），
 * 仅做提示不做门槛；空密码不渲染（保持编辑空表单轻量）。
 * 颜色递进：弱=error，一般=tertiary，良好及以上=primary。
 */
@Composable
private fun PasswordStrengthHint(password: String) {
    if (password.isEmpty()) return
    val score = PasswordStrength.score(password)
    val level = PasswordStrength.levelOf(score)
    val color = when (level) {
        PasswordStrength.Level.WEAK -> MaterialTheme.colorScheme.error
        PasswordStrength.Level.FAIR -> MaterialTheme.colorScheme.tertiary
        PasswordStrength.Level.GOOD,
        PasswordStrength.Level.STRONG,
        PasswordStrength.Level.VERY_STRONG,
        -> MaterialTheme.colorScheme.primary
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        LinearProgressIndicator(
            progress = { score / 100f },
            color = color,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier
                .weight(1f)
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp)),
        )
        Spacer(Modifier.width(Spacing.sm))
        Text(
            text = stringResource(strengthLabelRes(level)),
            style = MaterialTheme.typography.labelMedium,
            color = color,
        )
    }
}

private fun strengthLabelRes(level: PasswordStrength.Level): Int = when (level) {
    PasswordStrength.Level.WEAK -> R.string.password_strength_weak
    PasswordStrength.Level.FAIR -> R.string.password_strength_fair
    PasswordStrength.Level.GOOD -> R.string.password_strength_good
    PasswordStrength.Level.STRONG -> R.string.password_strength_strong
    PasswordStrength.Level.VERY_STRONG -> R.string.password_strength_very_strong
}

/**
 * 多网址编辑（对齐 Bitwarden login.uris 可多值）：逐条输入，可增删。
 * [uris] 为就地可变列表，编辑直接回写索引以保持 UI 同步。
 */
@Composable
private fun UriListEditor(
    uris: SnapshotStateList<String>,
    onAdd: () -> Unit,
    onRemove: (Int) -> Unit,
    onPickApp: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        uris.forEachIndexed { index, uri ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedTextField(
                    value = uri,
                    onValueChange = { uris[index] = it },
                    label = {
                        Text(
                            stringResource(
                                if (uri.startsWith(UriFormat.ANDROID_APP_SCHEME, ignoreCase = true)) {
                                    R.string.item_field_app_package
                                } else {
                                    R.string.item_field_uri
                                },
                            ),
                        )
                    },
                    singleLine = true,
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = Spacing.xs),
                )
                IconButton(onClick = { onRemove(index) }) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = stringResource(R.string.item_remove_uri),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Spacer(Modifier.height(Spacing.sm))
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onAdd, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(Spacing.sm))
                Text(stringResource(R.string.item_add_uri))
            }
            // 关联手机 App：等价于 Bitwarden 在网址里填 androidapp://包名，
            // 但用户不该手敲包名——这里给一个应用列表直接选。
            TextButton(onClick = onPickApp, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Android, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(Spacing.sm))
                Text(stringResource(R.string.item_add_app))
            }
        }
    }
}

/**
 * 自定义字段编辑器（对齐 Bitwarden 官方「添加自定义字段」）。
 *
 * 每行可改名称 / 值 / 类型，可删除；底部可新增。值的形态随 [CustomFieldType] 变化：
 * - [CustomFieldType.Boolean]：开关（存 `"true"` / `"false"`，与服务端一致）；
 * - [CustomFieldType.Hidden]：默认掩码，可点开查看；
 * - [CustomFieldType.Linked]：下拉选择所指标准字段（官方 100/300/400 分段编码）；
 * - [CustomFieldType.Text]：普通单行文本。
 */
@Composable
private fun CustomFieldsEditor(fields: SnapshotStateList<VaultCustomField>) {
    Column(modifier = Modifier.fillMaxWidth()) {
        fields.forEachIndexed { index, field ->
            CustomFieldRow(
                field = field,
                onFieldChange = { fields[index] = it },
                onRemove = { fields.removeAt(index) },
            )
            Spacer(Modifier.height(Spacing.sm))
        }
        TextButton(onClick = { fields.add(VaultCustomField()) }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(Spacing.sm))
            Text(stringResource(R.string.item_add_field))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomFieldRow(
    field: VaultCustomField,
    onFieldChange: (VaultCustomField) -> Unit,
    onRemove: () -> Unit,
) {
    var typeExpanded by remember { mutableStateOf(false) }
    var revealHidden by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = field.name,
                onValueChange = { onFieldChange(field.copy(name = it)) },
                label = { Text(stringResource(R.string.item_field_name)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onRemove) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.item_remove_field),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Spacer(Modifier.height(Spacing.xs))
        FieldValueInput(
            field = field,
            revealHidden = revealHidden,
            onRevealChange = { revealHidden = it },
            onFieldChange = onFieldChange,
        )
        Spacer(Modifier.height(Spacing.xs))
        ExposedDropdownMenuBox(expanded = typeExpanded, onExpandedChange = { typeExpanded = it }) {
            OutlinedTextField(
                value = stringResource(CUSTOM_FIELD_TYPE_LABELS.getValue(field.type)),
                onValueChange = {},
                readOnly = true,
                label = { Text(stringResource(R.string.item_field_type)) },
                modifier = Modifier.menuAnchor().fillMaxWidth(),
            )
            ExposedDropdownMenu(expanded = typeExpanded, onDismissRequest = { typeExpanded = false }) {
                CustomFieldType.entries.forEach { type ->
                    DropdownMenuItem(
                        text = { Text(stringResource(CUSTOM_FIELD_TYPE_LABELS.getValue(type))) },
                        onClick = {
                            onFieldChange(field.copy(type = type))
                            typeExpanded = false
                        },
                    )
                }
            }
        }
    }
}

/** 单个自定义字段的「值」输入：形态由字段类型决定。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FieldValueInput(
    field: VaultCustomField,
    revealHidden: Boolean,
    onRevealChange: (Boolean) -> Unit,
    onFieldChange: (VaultCustomField) -> Unit,
) {
    when (field.type) {
        CustomFieldType.Boolean -> Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = stringResource(R.string.item_field_value), modifier = Modifier.weight(1f))
            Switch(
                checked = field.value.equals("true", ignoreCase = true),
                onCheckedChange = { onFieldChange(field.copy(value = it.toString())) },
            )
        }

        CustomFieldType.Linked -> LinkedValueInput(field = field, onFieldChange = onFieldChange)

        CustomFieldType.Hidden -> Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = field.value,
                onValueChange = { onFieldChange(field.copy(value = it)) },
                label = { Text(stringResource(R.string.item_field_value)) },
                singleLine = true,
                visualTransformation = if (revealHidden) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { onRevealChange(!revealHidden) }) {
                Icon(
                    imageVector = if (revealHidden) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = stringResource(R.string.item_reveal_value),
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        CustomFieldType.Text -> OutlinedTextField(
            value = field.value,
            onValueChange = { onFieldChange(field.copy(value = it)) },
            label = { Text(stringResource(R.string.item_field_value)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 链接型字段：选择指向的标准字段（编号用官方分段编码）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LinkedValueInput(
    field: VaultCustomField,
    onFieldChange: (VaultCustomField) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = VaultLinkedId.fromCode(field.linkedId)
    val fallback = R.string.item_field_value
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = stringResource(selected?.let { LINKED_FIELD_LABELS[it] } ?: fallback),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.item_field_value)) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            VaultLinkedId.entries.forEach { id ->
                DropdownMenuItem(
                    text = { Text(stringResource(LINKED_FIELD_LABELS[id] ?: fallback)) },
                    onClick = {
                        // 链接型字段本身不存值，只存指向（value 清空）
                        onFieldChange(field.copy(linkedId = id.code, value = ""))
                        expanded = false
                    },
                )
            }
        }
    }
}

/** 字符串可变列表的保存器（进程重建后恢复表单输入）。 */
private val STRING_LIST_SAVER = listSaver<SnapshotStateList<String>, String>(
    save = { it.toList() },
    restore = { mutableStateListOf<String>().apply { addAll(it) } },
)

/** 银行卡可编辑字段标签（顺序 = [cardValuesOf] / [buildCard]，必须与之一一对应）。 */
private val CARD_LABELS = listOf(
    R.string.card_cardholder,
    R.string.card_brand,
    R.string.card_number,
    R.string.card_exp_month,
    R.string.card_exp_year,
    R.string.card_cvv,
)

/** 身份可编辑字段标签（顺序 = [identityValuesOf] / [buildIdentity]，Bitwarden canonical）。 */
private val IDENTITY_LABELS = listOf(
    R.string.identity_title,
    R.string.identity_first_name,
    R.string.identity_middle_name,
    R.string.identity_last_name,
    R.string.identity_address1,
    R.string.identity_address2,
    R.string.identity_address3,
    R.string.identity_city,
    R.string.identity_state,
    R.string.identity_postal_code,
    R.string.identity_country,
    R.string.identity_company,
    R.string.identity_email,
    R.string.identity_phone,
    R.string.identity_ssn,
    R.string.identity_username,
    R.string.identity_passport,
    R.string.identity_license,
)

/**
 * 每个自定义字段序列化为 4 个字符串：name / value / type 序号 / linkedId（-1 = 无）。
 * 用字符串扁平表而非自定义 Saver：与 [STRING_LIST_SAVER] 保持同一手法，避免
 * 为 data class 逐一写 Bundle 读写。
 */
private const val CUSTOM_FIELD_VALUE_COUNT = 4

/** 自定义字段列表的保存器（进程重建后恢复用户已编辑的字段）。 */
private val CUSTOM_FIELD_LIST_SAVER = listSaver<SnapshotStateList<VaultCustomField>, String>(
    save = { list ->
        list.flatMap { f ->
            listOf(f.name, f.value, f.type.ordinal.toString(), (f.linkedId ?: -1).toString())
        }
    },
    restore = { values ->
        mutableStateListOf<VaultCustomField>().apply {
            addAll(values.chunked(CUSTOM_FIELD_VALUE_COUNT).map(::customFieldOf))
        }
    },
)

private fun customFieldOf(parts: List<String>): VaultCustomField {
    val it = parts.iterator()
    return VaultCustomField(
        name = it.nextOrEmpty(),
        value = it.nextOrEmpty(),
        type = CustomFieldType.entries.getOrNull(it.nextOrEmpty().toIntOrNull() ?: 0)
            ?: CustomFieldType.Text,
        linkedId = it.nextOrEmpty().toIntOrNull()?.takeIf { v -> v >= 0 },
    )
}

private fun Iterator<String>.nextOrEmpty(): String = if (hasNext()) next() else ""
