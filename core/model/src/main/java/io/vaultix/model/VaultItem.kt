package io.vaultix.model

import kotlinx.serialization.Serializable

/**
 * 统一领域模型：VaultItem（依据 Docs/02 统一领域模型）。
 * 同时承载 Bitwarden 与 KDBX 两种库格式的抽象，字段映射由各数据源 Mapper 完成。
 *
 * ⚠️ 明文承载：仅存在于已解锁的内存中，禁止落盘、禁止进日志（Docs/09）。
 * [password] 于 2026-09-08 补入（M1 UI 新建条目需要）；[uris]/[totp]/[fido2Credentials]
 * 于本轮补入（对齐 Bitwarden 登录条目字段，修复「验证码/通行密钥读不到」）；
 * [customFields] 于本轮补入（修复「密码条里的自定义条目不显示」）。
 * 补字段必须同步补 Mapper（CipherMapper 与未来的 KDBX Mapper），见 MEMORY「保真度三级」约定。
 */
@Serializable
data class VaultItem(
    val id: String,
    val title: String,
    val username: String = "",
    val password: String = "",
    val notes: String = "",
    val type: VaultItemType = VaultItemType.Login,
    /** 登录条目的匹配网址（Bitwarden login.uris，可多值）。 */
    val uris: List<VaultUri> = emptyList(),
    /**
     * TOTP 密钥原文（解密后的明文串）。可能是 `otpauth://totp/...?secret=...`，
     * 也可能是裸 base32 密钥——由 [io.vaultix.common.OtpUriParser] 判别。
     * 空表示无 TOTP（Bitwarden login.totp 缺失/解密失败）。
     */
    val totp: String? = null,
    /** 通行密钥（WebAuthn 凭证），只读——由服务器/浏览器管理，客户端不创建。 */
    val fido2Credentials: List<VaultFido2Credential> = emptyList(),
    /**
     * 银行卡字段（type=Card）。非卡类条目恒为 null。
     * 字段对齐 Bitwarden card 载荷；只读展示（M2 编辑暂不支持）。
     */
    val card: VaultCard? = null,
    /**
     * SSH 密钥字段（type=SshKey）。非 SSH 条目恒为 null。
     * 字段对齐 Bitwarden sshKey 载荷；私钥/公钥/指纹只读展示。
     */
    val sshKey: VaultSshKey? = null,
    /**
     * 身份信息字段（type=Identity）。非身份条目恒为 null。
     * 字段对齐 Bitwarden identity 载荷（17 字段全量）；只读展示（M1 编辑暂不支持）。
     *
     * ⚠️ 补字段必须同步补 Mapper（CipherMapper.mapIdentity / mapIdentityRequest）
     * 与 ItemDetailScreen.IdentitySection，见 MEMORY「保真度三级」约定——否则身份条目
     * 在 Vaultix 里会默默丢字段（这正是 Bastion 兼容性差的根因之一）。
     */
    val identity: VaultIdentity? = null,
    /**
     * 自定义字段（Bitwarden cipher.fields，可多值，顺序即服务端顺序）。
     * 类型对齐 Bitwarden：Text / Hidden / Boolean / Linked（见 [CustomFieldType]）。
     * 只读展示（M1 编辑暂不支持；写回时由 CipherMapper 直接复用服务端原密文，不丢字段）。
     */
    val customFields: List<VaultCustomField> = emptyList(),
    /**
     * 所属文件夹（Bitwarden cipher.folderId）。null = 未归类（「无文件夹」）。
     *
     * ⚠️ 2026-09-08 补入：此前领域模型缺此字段，导致**拉取时文件夹信息直接丢弃**、
     * 新建条目也永远落在根目录（上传不带 folderId）。
     */
    val folderId: String? = null,
    /** 是否收藏（Bitwarden cipher.favorite）。 */
    val favorite: Boolean = false,
    /**
     * 主密码二次验证（Bitwarden cipher.reprompt）：
     * [VaultReprompt.Password] 时查看/自动填充前需再验一次主密码。
     */
    val reprompt: VaultReprompt = VaultReprompt.None,
    /**
     * 安全笔记载荷（type=SecureNote）。非安全笔记条目恒为 null。
     * Bitwarden 目前只有通用子类型（0），建模为其载体以便未来扩展。
     */
    val secureNote: VaultSecureNote? = null,
)

/**
 * 主密码二次验证开关（对齐 Bitwarden cipher.reprompt 0/1）。
 *
 * 用枚举而非裸 Int：调用点不会出现 `reprompt = 1` 这类魔法数字，
 * 与服务端 int 的互转集中在 CipherMapper。
 */
@Serializable
enum class VaultReprompt {
    /** 0 不要求 */
    None,

    /** 1 每次查看 / 自动填充前都要求输入主密码 */
    Password,
}

/**
 * 安全笔记载荷（Bitwarden secureNote 段）。
 *
 * [type] 为子类型号：目前 Bitwarden 只有 0（通用笔记）。建模成 data class
 * 而非直接用 Int，是为了将来服务端新增子类型时不用改领域模型签名。
 */
@Serializable
data class VaultSecureNote(
    val type: Int = 0,
)

enum class VaultItemType { Login, SecureNote, Card, Identity, SshKey }

/** 登录条目的一个匹配网址（对齐 Bitwarden login.uris[i]）。 */
@Serializable
data class VaultUri(
    val uri: String,
    /** 匹配规则；null = 服务端未指定 / 未知值，按 Bitwarden 默认「基域匹配」。 */
    val match: UriMatch? = null,
)

/** 网址匹配规则（对齐 Bitwarden login.uris[i].match 0-4）。 */
enum class UriMatch {
    /** 0 基域匹配（默认） */
    Domain,
    /** 1 主机匹配（含子域） */
    Host,
    /** 2 前缀匹配 */
    StartsWith,
    /** 3 精确匹配 */
    Exact,
    /** 4 正则匹配 */
    RegularExpression,
    /** 5 永不匹配（排除该 URI，对齐 Bitwarden UriMatchType.Never） */
    Never,
}

/**
 * 通行密钥（WebAuthn 凭证）的领域模型（解密后明文）。
 *
 * ⚠️ 明文承载：仅存在于已解锁的内存中，禁止落盘、禁止进日志（Docs/09）。
 *
 * 存储形态（对齐 Bitwarden login.fido2Credentials）：通行密钥**永远绑定在某个
 * 登录条目（密码条目）上**，不存在独立的通行密钥条目。因此 [VaultItem.fido2Credentials]
 * 仅对 [VaultItemType.Login] 有意义。
 *
 * 字段说明：
 * - [keyType]/[keyCurve]/[keyValue]：密钥材料（ES256/P-256 等；[keyValue] 为私钥/公钥材料，
 *   真机由平台 WebAuthn 生成，Vaultix 仅在保存流程中承载用户提供的材料）；
 * - [counter]：签名计数器（防重放，默认 0）；
 * - [discoverable]：是否为可发现凭证（resident key）。
 *
 * 注：本模型同时用于「只读展示」与「保存流程」——保存流程会补全上述密钥/计数/可发现性字段，
 * 写回时由 [io.vaultix.data.bitwarden.mapper.CipherMapper] 逐字段加密（creationDate 不加密，
 * 保持 Bitwarden 期望的可解析 DateTime 形态）。
 */
@Serializable
data class VaultFido2Credential(
    val credentialId: String = "",
    val rpId: String = "",
    val rpName: String = "",
    val userName: String = "",
    val userDisplayName: String = "",
    val userHandle: String? = null,
    val keyAlgorithm: String? = null,
    val creationDate: String? = null,
    /** 密钥类型（Bitwarden: "public-key"）。 */
    val keyType: String? = null,
    /** 曲线（Bitwarden: "P-256"）。 */
    val keyCurve: String? = null,
    /** 密钥材料（私钥/公钥 PEM 或原始字节，保存流程承载）。 */
    val keyValue: String? = null,
    /** 签名计数器（默认 0）。 */
    val counter: Long = 0,
    /** 是否为可发现凭证（默认 true）。 */
    val discoverable: Boolean = true,
)

/** 银行卡条目字段（Bitwarden card 载荷，解密后明文；只读展示）。 */
@Serializable
data class VaultCard(
    /** 持卡人姓名（cardholderName）。 */
    val cardholderName: String = "",
    /** 发卡行/品牌（brand），如 Visa / Mastercard。 */
    val brand: String = "",
    /** 卡号（number，密文解密后）。 */
    val number: String = "",
    /** 有效期月份（expMonth，1-12）。 */
    val expMonth: String = "",
    /** 有效期年份（expYear，2 或 4 位）。 */
    val expYear: String = "",
    /** 安全码（code，即 CVV/CVC）。 */
    val code: String = "",
)

/** SSH 密钥条目字段（Bitwarden sshKey 载荷，解密后明文；只读展示）。 */
@Serializable
data class VaultSshKey(
    /** 私钥（privateKey，PEM 文本）。 */
    val privateKey: String = "",
    /** 公钥（publicKey，如 ssh-ed25519 AAAA...）。 */
    val publicKey: String = "",
    /** 指纹（keyFingerprint）。 */
    val keyFingerprint: String = "",
)

/**
 * 身份信息条目字段（Bitwarden identity 载荷，解密后明文；只读展示）。
 *
 * ⚠️ 明文承载：仅存在于已解锁的内存中，禁止落盘、禁止进日志（Docs/09）。
 *
 * 字段语义对齐 Bitwarden `CipherIdentityData`（官网/服务端字段集，非 Bastion 子集）：
 * 全量 17 字段，覆盖姓名 / 地址 / 联系方式 / 证件号。Bastion 对身份条目兼容性差
 * （仅映射了 title/name 等少量字段，其余如 passportNumber/licenseNumber/ssn 静默丢弃），
 * Vaultix 以 Bitwarden 全字段为规范（canonical），逐字段解密降级空串，绝不丢字段。
 * 溯源：GPL-3.0，字段集参照 Bitwarden 开源 `CipherIdentityData`。
 */
@Serializable
data class VaultIdentity(
    /** 称谓（title，如 Mr/Mrs/Dr）。 */
    val title: String = "",
    /** 名（firstName）。 */
    val firstName: String = "",
    /** 中间名（middleName）。 */
    val middleName: String = "",
    /** 姓（lastName）。 */
    val lastName: String = "",
    /** 地址行 1（address1）。 */
    val address1: String = "",
    /** 地址行 2（address2）。 */
    val address2: String = "",
    /** 地址行 3（address3）。 */
    val address3: String = "",
    /** 城市（city）。 */
    val city: String = "",
    /** 省/州（state）。 */
    val state: String = "",
    /** 邮编（postalCode）。 */
    val postalCode: String = "",
    /** 国家（country）。 */
    val country: String = "",
    /** 公司（company）。 */
    val company: String = "",
    /** 邮箱（email）。 */
    val email: String = "",
    /** 电话（phone）。 */
    val phone: String = "",
    /** 身份证号（ssn）。 */
    val ssn: String = "",
    /** 用户名（username，身份下的登录名）。 */
    val username: String = "",
    /** 护照号（passportNumber）。 */
    val passportNumber: String = "",
    /** 驾照号（licenseNumber）。 */
    val licenseNumber: String = "",
)

/**
 * 自定义字段（Bitwarden cipher.fields，解密后明文；只读展示）。
 *
 * ⚠️ 明文承载：仅存在于已解锁的内存中，禁止落盘、禁止进日志（Docs/09）。
 *
 * 字段语义对齐 Bitwarden cipher.fields：
 * - [name]：字段名（解密后的明文）；
 * - [value]：字段值（解密后的明文；[CustomFieldType.Boolean] 时为 `"true"`/`"false"`，
 *   [CustomFieldType.Linked] 时为 [linkedId] 的引用，需经 [CustomFieldType] 映射展示）；
 * - [type]：字段类型，决定展示形态（隐藏/布尔/链接）；
 * - [linkedId]：仅 [CustomFieldType.Linked] 有效，指向标准字段（如用户名/密码/网址）的编号。
 *
 * 注：Bastion 的等价概念为 `data/CustomField.kt`（title/value/isProtected），
 * 但 Vaultix 以 Bitwarden 的 `type/linkedId` 四态模型为规范（canonical），与 KDBX 映射时
 * 再降级为「名称+值+是否敏感」三态。溯源：GPL-3.0，Bastion 同理。
 */
@Serializable
data class VaultCustomField(
    /** 字段名（解密后明文）。 */
    val name: String = "",
    /** 字段值（解密后明文）。 */
    val value: String = "",
    /** 字段类型，决定展示形态。默认 Text。 */
    val type: CustomFieldType = CustomFieldType.Text,
    /** 链接字段指向的标准字段编号（仅 Linked 类型有效）。 */
    val linkedId: Int? = null,
)

/** 自定义字段类型（对齐 Bitwarden cipher.fields[i].type）。 */
@Serializable
enum class CustomFieldType {
    /** 0 普通文本。 */
    Text,

    /** 1 隐藏（如密保答案/API Key），展示时默认掩码，仅可复制。 */
    Hidden,

    /** 2 布尔（解密后值为 `"true"`/`"false"`）。 */
    Boolean,

    /** 3 链接标准字段（value 为 linkedId 引用，展示为对应标准字段名）。 */
    Linked,
}
