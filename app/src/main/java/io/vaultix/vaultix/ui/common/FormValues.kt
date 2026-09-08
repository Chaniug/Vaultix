package io.vaultix.vaultix.ui.common

import io.vaultix.model.VaultCard
import io.vaultix.model.VaultIdentity
import io.vaultix.model.VaultItem
import io.vaultix.model.VaultItemType
import io.vaultix.model.VaultUri

/**
 * 条目表单的**纯逻辑**部分（不含 Compose，便于单测）。
 *
 * 与 UI 的契约：**「标签列表」与「值列表」一一对应**——
 * [cardValuesOf] / [identityValuesOf] 产出的列表长度与顺序，必须与
 * ItemFormDialog 中的 CARD_LABELS / IDENTITY_LABELS 完全一致（否则按索引取
 * 值会越界）。字段顺序即 Bitwarden canonical 顺序。
 *
 * 组装对象时用 [Iterator.nextOrEmpty] 顺序读取，避免 `getOrNull(3)` 这类
 * 下标字面量（会被 Detekt MagicNumber 拦下，且一旦顺序调整极易错位）。
 */

/** 表单当前值（保存时一次性收集；收敛参数个数以符合 ≤8 门禁）。 */
class FormValues(
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
 *
 * @param type 表单当前选中的类型。新建时允许切换类型，因此不能直接用
 *             `initial.type`——否则用户选了「银行卡」仍会存成登录条目。
 */
fun buildSnapshot(
    initial: VaultItem,
    type: VaultItemType,
    values: FormValues,
): VaultItem {
    val base = initial.copy(
        type = type,
        title = values.name.trim(),
        notes = values.notes.trim(),
    )
    return when (type) {
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

/** 银行卡字段 → 表单值（顺序同 ItemFormDialog 的 CARD_LABELS）。 */
fun cardValuesOf(card: VaultCard?): List<String> = listOf(
    card?.cardholderName.orEmpty(),
    card?.brand.orEmpty(),
    card?.number.orEmpty(),
    card?.expMonth.orEmpty(),
    card?.expYear.orEmpty(),
    card?.code.orEmpty(),
)

/** 身份字段 → 表单值（顺序同 ItemFormDialog 的 IDENTITY_LABELS，Bitwarden canonical 17 字段）。 */
fun identityValuesOf(identity: VaultIdentity?): List<String> = listOf(
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
fun buildCard(values: List<String>): VaultCard {
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
fun buildIdentity(values: List<String>): VaultIdentity {
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
