package io.vaultix.vaultix.ui.common

import io.vaultix.common.CardBrand
import io.vaultix.common.CardBrandDetector
import io.vaultix.common.SshFingerprint
import io.vaultix.model.VaultCard
import io.vaultix.model.VaultCustomField
import io.vaultix.model.VaultIdentity
import io.vaultix.model.VaultItem
import io.vaultix.model.VaultItemType
import io.vaultix.model.VaultReprompt
import io.vaultix.model.VaultSshKey
import io.vaultix.model.VaultUri

/**
 * 条目表单的**纯逻辑**部分（不含 Compose，便于单测）。
 *
 * 与 UI 的契约：**「标签列表」与「值列表」一一对应**——
 * [cardValuesOf] / [identityValuesOf] / [sshValuesOf] 产出的列表长度与顺序，
 * 必须与 ItemFormDialog 中的 CARD_LABELS / IDENTITY_LABELS / SSH 下标常量
 * 完全一致（否则按索引取值会越界）。字段顺序即 Bitwarden canonical 顺序。
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
 * 条目的**通用元数据段**（与各类型无关）：文件夹 / 收藏 / 主密码二次验证 / 自定义字段。
 *
 * 单独成类而非塞进 [FormValues]：后者已经有 8 个参数（门禁上限），
 * 再加就会触发 Detekt LongParameterList。
 */
data class ItemMeta(
    val folderId: String? = null,
    val favorite: Boolean = false,
    val reprompt: VaultReprompt = VaultReprompt.None,
    val customFields: List<VaultCustomField> = emptyList(),
)

/**
 * 构造保存快照：只覆盖**本类型可编辑**的段，其余沿用 [initial]
 * （自定义字段 / 通行密钥等不会因编辑而丢失）。
 *
 * @param type 表单当前选中的类型。新建时允许切换类型，因此不能直接用
 *             `initial.type`——否则用户选了「银行卡」仍会存成登录条目。
 * @param ssh SSH 段的三项值（私钥 / 公钥 / 指纹），顺序见 [sshValuesOf]。
 *            ⚠️ 单独成参而**不塞进 [FormValues]**：后者已经有 8 个参数，
 *            正卡在 detekt `allowedConstructorParameters` 的硬上限上
 *            （同 `ItemMeta` 当年被拆出去的理由，见其 KDoc）。
 */
fun buildSnapshot(
    initial: VaultItem,
    type: VaultItemType,
    values: FormValues,
    meta: ItemMeta = ItemMeta(),
    ssh: List<String> = emptyList(),
): VaultItem {
    val base = initial.copy(
        type = type,
        title = values.name.trim(),
        notes = values.notes.trim(),
        folderId = meta.folderId,
        favorite = meta.favorite,
        reprompt = meta.reprompt,
        customFields = meta.customFields,
    )
    return when (type) {
        VaultItemType.Login -> base.copy(
            username = values.username.trim(),
            password = values.password,
            // ⚠️ 必须保留每个位置的 match 规则：表单只编辑 URL 文本，不编辑匹配规则。
            // 若这里直接 `VaultUri(it)`（match 恒 null），则用户只要编辑保存过一次，
            // 导入来的 Exact / RegularExpression / Never 规则就被**静默清成默认基域匹配**
            // ——对自动填充是实打实的行为改变（例如 Never 的站点反而会被填充）。
            uris = values.uris.filter { it.isNotBlank() }.mapIndexed { index, url ->
                VaultUri(url, initial.uris.getOrNull(index)?.match)
            },
            totp = values.totp.trim().takeIf { it.isNotBlank() },
        )
        VaultItemType.Card -> base.copy(card = buildCard(values.cardValues))
        VaultItemType.Identity -> base.copy(identity = buildIdentity(values.identityValues))
        VaultItemType.SshKey -> base.copy(sshKey = buildSshKey(ssh))
        // 安全笔记只有名称 + 备注（其类型专属段不存在，无需覆盖）
        VaultItemType.SecureNote -> base
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

/** 表单值 → 银行卡（按 [cardValuesOf] 的顺序读取）。品牌为空时从卡号推导（对齐 Bastion）。 */
fun buildCard(values: List<String>): VaultCard {
    val it = values.iterator()
    val cardholderName = it.nextOrEmpty()
    val brand = it.nextOrEmpty()
    val number = it.nextOrEmpty()
    val expMonth = it.nextOrEmpty()
    val expYear = it.nextOrEmpty()
    val code = it.nextOrEmpty()
    val resolvedBrand = if (brand.isBlank()) {
        val detected = CardBrandDetector.detect(number)
        if (detected != CardBrand.UNKNOWN) detected.displayName else ""
    } else {
        brand
    }
    return VaultCard(
        cardholderName = cardholderName,
        brand = resolvedBrand,
        number = number,
        expMonth = expMonth,
        expYear = expYear,
        code = code,
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

/**
 * SSH 表单值的固定下标（[sshValuesOf] / [buildSshKey] / 表单输入三处共用）。
 *
 * 提成常量而不用字面量：三处必须一致，散落的 `values[1]` 一旦错位就是
 * 「公钥填进了指纹框」这种极难查的问题（Detekt MagicNumber 亦会拦下标字面量）。
 */
const val SSH_PRIVATE_KEY_INDEX = 0

/** 公钥下标。 */
const val SSH_PUBLIC_KEY_INDEX = 1

/** 指纹下标。 */
const val SSH_FINGERPRINT_INDEX = 2

/**
 * SSH 字段 → 表单值（顺序 = 上面的下标常量）。
 *
 * 指纹缺失时**不在这里补**：推导只发生在表单初始化与公钥变更两处
 * （见 [applyPublicKeyChange]），保持「值从哪来」有单一来源。
 */
fun sshValuesOf(ssh: VaultSshKey?): List<String> = listOf(
    ssh?.privateKey.orEmpty(),
    ssh?.publicKey.orEmpty(),
    ssh?.keyFingerprint.orEmpty(),
)

/**
 * 表单值 → SSH 密钥（按 [sshValuesOf] 的顺序读取）。
 *
 * 三项都 `trim()`：从终端复制的密钥几乎总带首尾空白/换行，留着会让详情页
 * 多出空行、复制按钮带出无关空白，还可能让公钥的算法名核对失败。
 * 其余类型（[buildCard] / [buildIdentity]）不 trim，是因为它们的输入不会带空白。
 */
fun buildSshKey(values: List<String>): VaultSshKey {
    val it = values.iterator()
    return VaultSshKey(
        privateKey = it.nextOrEmpty().trim(),
        publicKey = it.nextOrEmpty().trim(),
        keyFingerprint = it.nextOrEmpty().trim(),
    )
}

/**
 * 公钥变更时更新 [values]：若指纹处于「跟随公钥」状态则一并重算。
 *
 * 判据是**比较值、而非记一个 `fingerprintEdited` 标志位**：当下指纹为空、
 * 或正好等于旧公钥的推导值，就说明它是自动来的，可以覆盖；若用户手改成
 * 别的值，则原样保留。用比较法的额外好处是**可自愈** —— 用户把手改的值
 * 又改回推导值（或清空）时会自动重回跟随状态，不会永久卡在「手动」上。
 *
 * 推导失败（公钥还没填完 / 格式不对）时写**空串**而非保留旧值：宁可暂时空着，
 * 也不能让界面上挂着一个与当前公钥不符的指纹 —— 那是会骗人的错信息。
 *
 * ⚠️ 已知边界：若旧指纹是**遗留的 MD5 格式**（`MD5:aa:bb:...`，非本实现产出），
 * 它永不等于 SHA256 推导值 ⇒ 判为「用户自定义」而保留 ⇒ 改公钥后它会陈旧。
 * 取舍是「尊重用户填过的值」优先；现代数据里 MD5 指纹已罕见。
 */
fun applyPublicKeyChange(values: MutableList<String>, newPublicKey: String) {
    val current = values.getOrElse(SSH_FINGERPRINT_INDEX) { "" }
    val previousPublicKey = values.getOrElse(SSH_PUBLIC_KEY_INDEX) { "" }
    val wasFollowing = current.isBlank() ||
        current == SshFingerprint.of(previousPublicKey).orEmpty()
    values.setOrNull(SSH_PUBLIC_KEY_INDEX, newPublicKey)
    if (wasFollowing) {
        values.setOrNull(SSH_FINGERPRINT_INDEX, SshFingerprint.of(newPublicKey).orEmpty())
    }
}

/** 越界即忽略：走 [MutableList.set] 在列表短于预期时会抛 IndexOutOfBounds。 */
private fun MutableList<String>.setOrNull(index: Int, value: String) {
    if (index in indices) this[index] = value
}

private fun Iterator<String>.nextOrEmpty(): String = if (hasNext()) next() else ""
