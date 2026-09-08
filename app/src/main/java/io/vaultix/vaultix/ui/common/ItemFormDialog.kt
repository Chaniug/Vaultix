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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.vaultix.common.PasswordStrength
import io.vaultix.model.CustomFieldType
import io.vaultix.model.VaultCustomField
import io.vaultix.model.VaultItem
import io.vaultix.model.VaultItemType
import io.vaultix.model.VaultLinkedId
import io.vaultix.vaultix.R

/**
 * 条目表单对话框（新建 / 编辑共用，Docs/08 S10）。
 *
 * 按条目类型决定可编辑字段，**身份与银行卡已可编辑**：
 * - Login：用户名 / 密码（含实时强度条）/ 网址（多值）/ TOTP 密钥；
 * - Card：持卡人 / 发卡行 / 卡号 / 有效期月·年 / 安全码（对齐 Bitwarden card 载荷）；
 * - Identity：全量 17 字段（对齐 Bitwarden identity 载荷，覆盖 Bastion 兼容缺陷）；
 * - SecureNote / SshKey：仅名称 + 备注（类型专属段保留服务端原值）。
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
    val customFields = rememberSaveable(initial, saver = CUSTOM_FIELD_LIST_SAVER) {
        mutableStateListOf<VaultCustomField>().apply { addAll(initial.customFields) }
    }
    var showNameError by rememberSaveable { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(text = title, style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(modifier = Modifier.imePadding()) {
                if (typeEditable) {
                    SectionLabel(text = stringResource(R.string.item_field_type))
                    Spacer(Modifier.height(8.dp))
                    TypePicker(selected = type, onSelect = { type = it })
                    Spacer(Modifier.height(8.dp))
                }
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
                SectionLabel(text = stringResource(R.string.section_custom_fields))
                Spacer(Modifier.height(8.dp))
                CustomFieldsEditor(fields = customFields)
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
                                meta = ItemMeta(customFields = customFields.toList()),
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

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** 新建条目的类型选择：一行可选筹码，选中态即当前类型。 */
@Composable
private fun TypePicker(
    selected: VaultItemType,
    onSelect: (VaultItemType) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
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
            Spacer(Modifier.height(8.dp))
        }
        TextButton(onClick = { fields.add(VaultCustomField()) }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
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
        Spacer(Modifier.height(4.dp))
        FieldValueInput(
            field = field,
            revealHidden = revealHidden,
            onRevealChange = { revealHidden = it },
            onFieldChange = onFieldChange,
        )
        Spacer(Modifier.height(4.dp))
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
