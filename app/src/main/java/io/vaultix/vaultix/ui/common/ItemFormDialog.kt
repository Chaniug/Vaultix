package io.vaultix.vaultix.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.vaultix.common.PasswordStrength
import io.vaultix.model.VaultCard
import io.vaultix.model.VaultIdentity
import io.vaultix.model.VaultItem
import io.vaultix.model.VaultItemType
import io.vaultix.model.VaultUri
import io.vaultix.vaultix.R

/**
 * 条目表单对话框（新建 / 编辑共用，Docs/08 S10）。
 *
 * 按 [VaultItem.type] 决定可编辑字段，**身份与银行卡已可编辑**：
 * - Login：用户名 / 密码（含实时强度条）/ 网址（多值）/ TOTP 密钥；
 * - Card：持卡人 / 发卡行 / 卡号 / 有效期月·年 / 安全码（对齐 Bitwarden card 载荷）；
 * - Identity：全量 17 字段（对齐 Bitwarden identity 载荷，覆盖 Bastion 兼容缺陷）；
 * - SecureNote / SshKey：仅名称 + 备注（类型专属段保留服务端原值，不做整条重写）。
 *
 * 密码等敏感值只在对话框存续期间存在于内存；关闭/保存后由调用方保证不再持有副本。
 * 编辑场景用 [initial] 预填；切换目标条目时（key 变化）状态自动重置。
 * 保存回传的是 [initial] 的 copy，未在本表单暴露的段（自定义字段 / 通行密钥 /
 * SSH 密钥等）原样保留，由 data 层合并上传。
 */
@Composable
fun ItemFormDialog(
    title: String,
    initial: VaultItem,
    saving: Boolean = false,
    onDismiss: () -> Unit,
    onSave: (VaultItem) -> Unit,
) {
    val type = initial.type
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
    var showNameError by rememberSaveable { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(text = title, style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(modifier = Modifier.imePadding()) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; showNameError = false },
                    label = { Text(stringResource(R.string.item_field_name)) },
                    singleLine = true,
                    isError = showNameError,
                    supportingText = if (showNameError) {
                        { Text(stringResource(R.string.item_name_required)) }
                    } else {
                        null
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (type == VaultItemType.SshKey) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(
                            R.string.item_edit_type_fields_readonly,
                            stringResource(itemTypeLabelRes(type)),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(8.dp))
                when (type) {
                    VaultItemType.Login -> LoginFields(
                        username = username,
                        onUsernameChange = { username = it },
                        password = password,
                        onPasswordChange = { password = it },
                        uris = uris,
                        totp = totp,
                        onTotpChange = { totp = it },
                    )
                    VaultItemType.Card -> LabeledFields(CARD_LABELS, cardValues)
                    VaultItemType.Identity -> LabeledFields(IDENTITY_LABELS, identityValues)
                    // 安全笔记只有名称 + 备注；SSH 密钥段保持只读（上方已提示）
                    VaultItemType.SecureNote, VaultItemType.SshKey -> Unit
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text(stringResource(R.string.item_field_notes)) },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = !saving,
                onClick = {
                    if (name.isBlank()) {
                        showNameError = true
                    } else {
                        onSave(
                            buildSnapshot(
                                initial,
                                FormValues(
                                    name = name,
                                    username = username,
                                    password = password,
                                    notes = notes,
                                    totp = totp,
                                    uris = uris.toList(),
                                    cardValues = cardValues.toList(),
                                    identityValues = identityValues.toList(),
                                ),
                            ),
                        )
                    }
                },
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !saving) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

/** 表单当前值（保存时一次性收集；收敛参数个数以符合 ≤8 门禁）。 */
private class FormValues(
    val name: String,
    val username: String,
    val password: String,
    val notes: String,
    val totp: String,
    val uris: List<String>,
    val cardValues: List<String>,
    val identityValues: List<String>,
)

/**
 * 构造保存快照：只覆盖**本类型可编辑**的段，其余沿用 [initial]
 * （自定义字段 / 通行密钥 / SSH 段等不会因编辑而丢失）。
 */
private fun buildSnapshot(initial: VaultItem, values: FormValues): VaultItem {
    val base = initial.copy(title = values.name.trim(), notes = values.notes.trim())
    return when (initial.type) {
        VaultItemType.Login -> base.copy(
            username = values.username.trim(),
            password = values.password,
            uris = values.uris.filter { it.isNotBlank() }.map { VaultUri(it) },
            totp = values.totp.trim().takeIf { it.isNotBlank() },
        )
        VaultItemType.Card -> base.copy(card = buildCard(values.cardValues))
        VaultItemType.Identity -> base.copy(identity = buildIdentity(values.identityValues))
        // 安全笔记只有名称 + 备注；SSH 密钥段保持只读
        VaultItemType.SecureNote, VaultItemType.SshKey -> base
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
) {
    OutlinedTextField(
        value = username,
        onValueChange = onUsernameChange,
        label = { Text(stringResource(R.string.item_field_username)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = password,
        onValueChange = onPasswordChange,
        label = { Text(stringResource(R.string.item_field_password)) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )
    PasswordStrengthHint(password = password)
    Spacer(Modifier.height(8.dp))
    UriListEditor(
        uris = uris,
        onAdd = { uris.add("") },
        onRemove = { uris.removeAt(it) },
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = totp,
        onValueChange = onTotpChange,
        label = { Text(stringResource(R.string.section_totp)) },
        placeholder = { Text(stringResource(R.string.item_totp_hint)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
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
        Spacer(Modifier.height(8.dp))
    }
}

/** 银行卡字段 → 表单值（顺序同 [CARD_LABELS]）。 */
private fun cardValuesOf(card: VaultCard?): List<String> = listOf(
    card?.cardholderName.orEmpty(),
    card?.brand.orEmpty(),
    card?.number.orEmpty(),
    card?.expMonth.orEmpty(),
    card?.expYear.orEmpty(),
    card?.code.orEmpty(),
)

/** 身份字段 → 表单值（顺序同 [IDENTITY_LABELS]，Bitwarden canonical 17 字段）。 */
private fun identityValuesOf(identity: VaultIdentity?): List<String> = listOf(
    identity?.title.orEmpty(),
    identity?.firstName.orEmpty(),
    identity?.middleName.orEmpty(),
    identity?.lastName.orEmpty(),
    identity?.address1.orEmpty(),
    identity?.address2.orEmpty(),
    identity?.address3.orEmpty(),
    identity?.city.orEmpty(),
    identity?.state.orEmpty(),
    identity?.postalCode.orEmpty(),
    identity?.country.orEmpty(),
    identity?.company.orEmpty(),
    identity?.email.orEmpty(),
    identity?.phone.orEmpty(),
    identity?.ssn.orEmpty(),
    identity?.username.orEmpty(),
    identity?.passportNumber.orEmpty(),
    identity?.licenseNumber.orEmpty(),
)

/** 表单值 → 银行卡（按 [cardValuesOf] 的顺序读取）。 */
private fun buildCard(values: List<String>): VaultCard {
    val it = values.iterator()
    return VaultCard(
        cardholderName = it.nextOrEmpty(),
        brand = it.nextOrEmpty(),
        number = it.nextOrEmpty(),
        expMonth = it.nextOrEmpty(),
        expYear = it.nextOrEmpty(),
        code = it.nextOrEmpty(),
    )
}

/** 表单值 → 身份信息（按 [identityValuesOf] 的顺序读取）。 */
private fun buildIdentity(values: List<String>): VaultIdentity {
    val it = values.iterator()
    return VaultIdentity(
        title = it.nextOrEmpty(),
        firstName = it.nextOrEmpty(),
        middleName = it.nextOrEmpty(),
        lastName = it.nextOrEmpty(),
        address1 = it.nextOrEmpty(),
        address2 = it.nextOrEmpty(),
        address3 = it.nextOrEmpty(),
        city = it.nextOrEmpty(),
        state = it.nextOrEmpty(),
        postalCode = it.nextOrEmpty(),
        country = it.nextOrEmpty(),
        company = it.nextOrEmpty(),
        email = it.nextOrEmpty(),
        phone = it.nextOrEmpty(),
        ssn = it.nextOrEmpty(),
        username = it.nextOrEmpty(),
        passportNumber = it.nextOrEmpty(),
        licenseNumber = it.nextOrEmpty(),
    )
}

private fun Iterator<String>.nextOrEmpty(): String = if (hasNext()) next() else ""

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
        Spacer(Modifier.width(8.dp))
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
                    label = { Text(stringResource(R.string.item_field_uri)) },
                    singleLine = true,
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 4.dp),
                )
                IconButton(onClick = { onRemove(index) }) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = stringResource(R.string.item_remove_uri),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        TextButton(onClick = onAdd, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.item_add_uri))
        }
    }
}

/** 字符串可变列表的保存器（进程重建后恢复表单输入）。 */
private val STRING_LIST_SAVER = listSaver<SnapshotStateList<String>, String>(
    save = { it.toList() },
    restore = { mutableStateListOf<String>().apply { addAll(it) } },
)

/** 银行卡可编辑字段标签（顺序 = [cardValuesOf] / [buildCard]）。 */
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
