package io.vaultix.vaultix.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.vaultix.common.WebAuthn
import io.vaultix.model.PasskeyKeyAlgorithm
import io.vaultix.model.VaultFido2Credential
import io.vaultix.model.VaultItem
import io.vaultix.vaultix.R
import io.vaultix.vaultix.ui.theme.Spacing
import java.time.Instant

/**
 * 保存（绑定）一个通行密钥到所选密码条目。
 *
 * 对齐 Bitwarden / Keyguard：通行密钥必须绑定到登录条目，不存在独立通行密钥条目。
 * 调用方负责在 onSave 中把凭证追加进目标登录条目的 fido2 列表。
 *
 * ## 两态对话框（2026-11-09 重构，修用户反馈的两个 bug）
 *
 * 表单态 ⇄ 选择态在**同一个 `AlertDialog` 内**切换，选择态不是弹层。
 * 原因见 [PasskeyLoginPicker] 的 KDoc —— 上一版把搜索框塞进 `ExposedDropdownMenu`，
 * 既打不了字，列表还会溢出对话框。
 *
 * ## 2026-11-12 #284：手工录入的凭证必须是**完整**的
 *
 * `KdbxPasskeyCodec.toCredential` 把 `rpId / credentialId / privateKey / userName /
 * userHandle` 五项列为必填，缺一个就返回 `null`（宁可当普通条目，也不给半截凭证）。
 * 但本对话框此前**只校验了 `rpId / credentialId / keyValue`**，构造出的
 * `VaultFido2Credential` 既没有 `userHandle`（模型默认 `null`），也允许 `userName`
 * 留空 ⇒ 写进 KDBX 后读不回来：内存会话里当下还看得到，**重开库就静默消失**。
 * Bitwarden 库不受影响（读侧不要求这两个字段），所以这个坑只打在 KDBX 上。
 *
 * ⇒ 现在 `userName` 必填校验；`userHandle` 选填、留空时由
 *    [WebAuthn.randomUserHandle] 生成合法值；并补齐 `keyAlgorithm` / `keyType` /
 *    `keyCurve` / `creationDate` 等元数据，与系统注册流程
 *    `PasskeyCreateActivity` 的产出同形。`SavePasskeyDialogRulesTest` 扫源码钉住。
 */
@Composable
fun SavePasskeyDialog(
    candidates: List<VaultItem>,
    onDismiss: () -> Unit,
    onSave: (loginId: String, credential: VaultFido2Credential) -> Unit,
) {
    var selectedLogin by remember { mutableStateOf(candidates.firstOrNull()) }
    var pickingLogin by remember { mutableStateOf(false) }
    var errorRes by remember { mutableStateOf<Int?>(null) }
    val draft = remember { PasskeyDraft() }

    // 选择态按返回键 / 点外部 → 退回表单，而不是丢掉已经填好的字段。
    AlertDialog(
        onDismissRequest = { if (pickingLogin) pickingLogin = false else onDismiss() },
        title = {
            Text(
                stringResource(
                    if (pickingLogin) R.string.passkey_select_login else R.string.passkeys_add_title,
                ),
            )
        },
        text = {
            if (pickingLogin) {
                PasskeyLoginPicker(
                    candidates = candidates,
                    selectedId = selectedLogin?.id,
                    onSelect = { login ->
                        selectedLogin = login
                        pickingLogin = false
                    },
                )
            } else {
                SavePasskeyForm(
                    draft = draft,
                    selected = selectedLogin,
                    canPick = candidates.isNotEmpty(),
                    errorRes = errorRes,
                    onPickLogin = { pickingLogin = true },
                )
            }
        },
        confirmButton = {
            if (pickingLogin) {
                DialogTextButton(
                    text = stringResource(R.string.action_cancel),
                    onClick = { pickingLogin = false },
                )
            } else {
                DialogTextButton(
                    text = stringResource(R.string.action_save),
                    onClick = {
                        val login = selectedLogin
                        if (login == null || draft.rpId.isBlank() || draft.credentialId.isBlank()) {
                            errorRes = R.string.totp_invalid_secret
                            return@DialogTextButton
                        }
                        // ⚠️ **私钥材料必填**（P0，2026-09-11）：没有 keyValue 的通行密钥「看得到、
                        // 点不动」—— 候选能列出来，签名时 parseEcPrivateKey 返回 null，浏览器侧报
                        // `Cannot parse passkey key`。真机上那条唯一缺 keyValue 的凭据正是这样来的。
                        // 参考实现里 keyValue 一律来自真实密钥对（Keyguard：
                        // `PasskeyBase64.encodeToString(keyMaterial.privateKeyPkcs8)`），
                        // **没有任何实现允许手工留空**。
                        if (draft.keyValue.isBlank()) {
                            errorRes = R.string.passkey_error_key_value_required
                            return@DialogTextButton
                        }
                        // ⚠️ #284：`userName` / `userHandle` 是 KDBX 读侧的两个必填字段
                        //   （`KdbxPasskeyCodec.toCredential` 缺一即返回 null）。前者空了
                        //   直接拦下 —— 别让用户"保存成功、重开库却什么都没有"；
                        //   后者选填，留空生成合法值，见 [WebAuthn.randomUserHandle]。
                        if (draft.userName.isBlank()) {
                            errorRes = R.string.passkey_error_user_name_required
                            return@DialogTextButton
                        }
                        onSave(
                            login.id,
                            VaultFido2Credential(
                                credentialId = draft.credentialId.trim(),
                                rpId = draft.rpId.trim(),
                                rpName = draft.rpName.takeIf { it.isNotBlank() } ?: draft.rpId.trim(),
                                userName = draft.userName.trim(),
                                userDisplayName = draft.userDisplayName.takeIf { it.isNotBlank() }
                                    ?: draft.userName.trim(),
                                userHandle = draft.userHandle.trim()
                                    .ifBlank { WebAuthn.randomUserHandle() },
                                keyValue = draft.keyValue.trim(),
                                // 与系统注册流程 PasskeyCreateActivity 同形：手工录入的
                                // 凭证也必须带齐元数据，否则详情页 / 断言各读各的默认值。
                                keyAlgorithm = PasskeyKeyAlgorithm.DEFAULT,
                                keyType = "public-key",
                                keyCurve = "P-256",
                                creationDate = Instant.now().toString(),
                                counter = 0,
                                discoverable = true,
                            ),
                        )
                    },
                )
            }
        },
        dismissButton = {
            // 选择态的「取消」已经在 confirmButton 上（右下角单按钮），这里留空。
            if (!pickingLogin) {
                DialogTextButton(
                    text = stringResource(R.string.action_cancel),
                    onClick = onDismiss,
                )
            }
        },
    )
}

/**
 * 手工录入通行密钥时的表单草稿。
 *
 * 收进一个 state holder，而不是在 [SavePasskeyDialog] 里平铺 6 个 `var`：
 * 后者要把 6 个值 + 6 个 setter 传给表单函数（12 个参数），
 * 而它们本来就是同一份数据的 12 个面 —— 参数越多，漏传一个就越难发现
 * （漏传不等于编译失败：会给一个默认空串，表现为"填了但保存后是空的"）。
 */
private class PasskeyDraft {
    var rpId by mutableStateOf("")
    var rpName by mutableStateOf("")
    var userName by mutableStateOf("")
    var userDisplayName by mutableStateOf("")
    var credentialId by mutableStateOf("")
    var keyValue by mutableStateOf("")
    /** 选填；留空时由 `WebAuthn.randomUserHandle()` 生成（KDBX 读侧必填，见 #284）。 */
    var userHandle by mutableStateOf("")
}

/** 表单态：选择条目 + 6 个字段 + 错误提示。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SavePasskeyForm(
    draft: PasskeyDraft,
    selected: VaultItem?,
    canPick: Boolean,
    errorRes: Int?,
    onPickLogin: () -> Unit,
) {
    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
        // 先算成两个局部值再拼装：`selected` 可空，把 `?.` 串在 lambda 里
        // 会让"这一行到底显示什么"需要读三遍才能确认。
        val headline = selected?.let { it.title.ifBlank { it.username } }
            ?: stringResource(R.string.passkey_select_login)
        val supporting = selected?.let { item ->
            item.username.takeIf { name -> name.isNotBlank() && name != item.title }
        }
        if (canPick) {
            // 「选择器」用 ListItem 而不是只读 TextField（2026-11-09）：
            // M3 里 out-lined text field 的语义是"这里可以打字"，只读字段会让用户
            // 先点一下输入框、发现光标闪却打不出字，再去找箭头 —— 三步才明白。
            // ListItem + 展开箭头是「点它挑一个」的通用造型。
            OutlinedCard(onClick = onPickLogin, modifier = Modifier.fillMaxWidth()) {
                ListItem(
                    headlineContent = {
                        Text(
                            text = headline,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    supportingContent = if (supporting != null) {
                        {
                            Text(
                                text = supporting,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    } else {
                        null
                    },
                    trailingContent = {
                        Icon(
                            imageVector = Icons.Filled.ArrowDropDown,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )
            }
            Spacer(Modifier.height(Spacing.sm))
        } else {
            Text(
                stringResource(R.string.passkey_select_login),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedTextField(
            value = draft.rpId,
            onValueChange = { draft.rpId = it },
            label = { Text(stringResource(R.string.passkey_field_rp_id)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(Spacing.sm))
        OutlinedTextField(
            value = draft.rpName,
            onValueChange = { draft.rpName = it },
            label = { Text(stringResource(R.string.passkey_field_rp_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(Spacing.sm))
        OutlinedTextField(
            value = draft.userName,
            onValueChange = { draft.userName = it },
            label = { Text(stringResource(R.string.passkey_field_user)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(Spacing.sm))
        OutlinedTextField(
            value = draft.userDisplayName,
            onValueChange = { draft.userDisplayName = it },
            label = { Text(stringResource(R.string.passkey_user)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(Spacing.sm))
        OutlinedTextField(
            value = draft.credentialId,
            onValueChange = { draft.credentialId = it },
            label = { Text(stringResource(R.string.passkey_field_credential_id)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(Spacing.sm))
        // #284：userHandle 是 KDBX 读侧必填项。做成选填输入框 —— 有真实值就用真实值
        //（导入第三方库的凭证时 RP 的 user.id 不能丢），留空则由保存逻辑生成合法兜底值。
        OutlinedTextField(
            value = draft.userHandle,
            onValueChange = { draft.userHandle = it },
            label = { Text(stringResource(R.string.passkey_field_user_handle_optional)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(Spacing.sm))
        OutlinedTextField(
            value = draft.keyValue,
            onValueChange = { draft.keyValue = it },
            label = { Text(stringResource(R.string.passkey_field_key_value)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        errorRes?.let {
            Text(
                stringResource(it),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/**
 * 通行密钥的「选择密码条目」页（2026-11-09 重写）。
 *
 * ## 上一版为什么坏了两件事
 *
 * 上一版把搜索框放进 `ExposedDropdownMenu` 的菜单体里，并靠 `ExposedDropdownMenuBox`
 * 展开。真机上两个 bug：
 *
 * 1. **打不了字**。弹出菜单是**独立窗口**，它的焦点归菜单自己管；输入框虽然是菜单的
 *    子项、也能显示光标，但 IME 的连接建立在外层 anchor 上。⇒ 手指点进搜索框，
 *    键盘不弹、敲字无反应。
 * 2. **列表溢出**。`ExposedDropdownMenu` 的可用高度是按**它的宿主窗口**边界算的。
 *    宿主是 `AlertDialog`（又一个独立窗口），这层嵌套让边界计算失真 ⇒ 菜单一路画到
 *    屏幕外，盖住对话框自己的下半部分（用户截图正是这样）。
 *
 * 两个 bug 同源：**在 Dialog 里再开一个 Popup**。所以修法不是调参数，是换载体。
 *
 * ## 现在的做法：Dialog 内的一页，不是弹层
 *
 * 搜索框与列表都是对话框内容的一部分（`AlertDialog` 的 `text` slot），
 * 与 [io.vaultix.vaultix.ui.totp.LoginPickerDialog] 同一形状 —— 那处从 2026-09 起
 * 一直正常，就是因为它没有用 Popup。列表用 `LazyColumn` + `heightIn(max)`：
 * 高度有界、内部可滚，条目再多也不会顶出对话框。
 *
 * ⚠️ 别改回 `ExposedDropdownMenu` 「顺手一点」。它适合**短选项**（5 项以内、不用打字），
 *    "几十个条目 + 搜索"这个组合在 Dialog 里就是上面两个 bug。
 *
 * ⚠️ 搜索匹配 `title` **+** `username`：很多条目标题是「GitHub」而账号是邮箱，
 *    用户凭记忆输入的是任一个，只按标题筛会漏（与注册流程 `PasskeyCreateSheet` 一致）。
 */
@Composable
private fun PasskeyLoginPicker(
    candidates: List<VaultItem>,
    selectedId: String?,
    onSelect: (VaultItem) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val visible = remember(candidates, query) {
        val q = query.trim()
        if (q.isEmpty()) {
            candidates
        } else {
            candidates.filter {
                it.title.contains(q, ignoreCase = true) || it.username.contains(q, ignoreCase = true)
            }
        }
    }
    // 打开即聚焦：这一页除了"搜一个、点一个"没别的事可做。
    // 与 [io.vaultix.vaultix.ui.folders.FolderNameDialog] 同一条纪律。
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Column {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text(stringResource(R.string.passkey_search_login)) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = if (query.isEmpty()) {
                null
            } else {
                {
                    // 有内容才给清除：空框上的 ✕ 是个点了没反应的按钮。
                    IconButton(onClick = { query = "" }) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(R.string.action_clear),
                        )
                    }
                }
            },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester),
        )
        Spacer(Modifier.height(Spacing.sm))
        if (visible.isEmpty()) {
            // 空结果也要说话：只剩一个搜索框时用户会以为卡住了。
            Text(
                text = stringResource(R.string.passkey_search_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = Spacing.md),
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp)) {
                items(visible, key = { it.id }) { login ->
                    ListItem(
                        // 单选：读屏念「单选按钮」，且不带一个永远不变的 `selected` 状态
                        // （同 AddCloudVaultScreen 的既有做法）。
                        modifier = Modifier.clickable(role = Role.RadioButton) { onSelect(login) },
                        headlineContent = {
                            Text(
                                text = login.title.ifBlank { login.username },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        supportingContent = if (login.username.isNotBlank() && login.username != login.title) {
                            {
                                Text(
                                    text = login.username,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        } else {
                            null
                        },
                        trailingContent = if (login.id == selectedId) {
                            {
                                Icon(
                                    imageVector = Icons.Filled.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        } else {
                            null
                        },
                    )
                }
            }
        }
    }
}
