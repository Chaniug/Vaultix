package io.vaultix.domain

import io.vaultix.model.VaultFolder
import io.vaultix.model.VaultItem

/**
 * **Bitwarden 加密 JSON 导出 / 导入**（对齐 Bitwarden 官方 `bitwarden-exporters`）。
 *
 * 为什么单列一个仓储接口而不是塞进 [VaultRepository]：
 * - 导出 / 导入是**一次性、重计算、带独立密码**的批处理动作，与库生命周期 / 同步
 *   完全正交（`VaultRepository` 是「库状态机 + 同步」，塞进去只会让它继续膨胀）；
 * - 该能力只在设置页使用，UI 侧只要一个窄接口，不需要看到条目 / 文件夹的读写细节。
 *
 * 实现位于 data:repository（内部委托 data:bitwarden 的导出器 / 导入器 + 条目 / 文件夹仓储）。
 * 纯 Kotlin，无 Android 依赖，便于 ViewModel 注入 fake 单测。
 *
 * ⚠️ 安全契约（对齐 Docs/09）：导出 / 导入涉及**全库明文**与用户密码，
 * 明文只在内存流转、用完即弃；本接口不接收也不返回任何持久化路径
 * （落盘 / 读盘由 app 层经 SAF 完成，data 层不碰文件系统）。
 */
interface VaultExportRepository {

    /**
     * 导出整个库为 **Bitwarden 密码保护加密导出** JSON。
     *
     * @param vaultId 待导出的库（需已解锁；未解锁时抛 [VaultNotUnlockedException]）。
     * @param exportPassword 本次导出的独立密码（与账号主密码无关）。
     * @return 可直接落盘的加密导出 JSON 字符串（UTF-8）。
     */
    suspend fun exportEncryptedJson(vaultId: String, exportPassword: String): String

    /**
     * 解析 **Bitwarden 加密导出文件**（不落库，仅返回解密后的内容供预览）。
     *
     * @param fileContent 文件全文（UTF-8 解码后的字符串）。
     * @param exportPassword 导出时设置的密码。
     * @return 解密成功后的条目 / 文件夹列表（已映射为领域模型）。
     * @throws VaultImportException 分类失败原因（格式非法 / 密码错误 / KDF 不支持）。
     */
    fun parseEncryptedJson(fileContent: String, exportPassword: String): ImportedVault

    /**
     * 把 [parseEncryptedJson] 的结果**写入目标库**。
     *
     * 语义：**增量导入**——逐条 [ItemRepository.createItem] 新建（不覆盖同 id 条目，
     * 由目标库重新分配 id）。
     *
     * ## 文件夹（2026-10-28 修正）
     *
     * 此前只按名字匹配已有文件夹、缺失的跳过（注释理由是「Vaultix 不支持客户端新建
     * 文件夹」）。该理由在 #199（2026-10-27）之后已不成立：`FolderRepository`
     * 三个写方法齐全。现在**先建缺失文件夹、再写条目**，并用**源端 id → 目标 id**
     * 映射条目归属；不再按名字二次查找（同名文件夹会互相顶掉）。
     *
     * ⚠️ 文件夹创建失败会**在写任何条目之前**中止：宁可整次导入失败，也不要把
     * 条目静默倒进「无文件夹」——那是不可逆的组织信息丢失。
     *
     * ## 跳过与失败都要计数
     *
     * - 已存在（[ImportDuplicatePolicy] 判定）；
     * - **文件内部重复**（同一份导出里本来就有两条一样的）；
     * - 当前库不支持的类型（KDBX 只收 Login / SecureNote）；
     * - 逐条写入失败（不静默吞掉）。
     *
     * @return 结构化结果；UI 必须把「成功 / 各类跳过 / 失败」分别告诉用户。
     */
    suspend fun applyImportedVault(vaultId: String, imported: ImportedVault): ImportResult

    /**
     * 写入前的**预演**：算出哪些条目会被跳过（已存在）、哪些会被新建。
     *
     * 为什么不把"去重"塞进 [applyImportedVault] 内部：
     * 导入是**不可撤销的批量写**（会往库里塞进几十上百条），用户必须在写之前就看到
     * "会新建 N 条 / 已存在 M 条"，并由自己决定 M 条是跳过还是照样导入。
     * 一个"悄悄帮我跳过"的导入器，用户只会以为**文件少了条**。
     *
     * @return 新建数、重复数、重复样例标题（最多 [IMPORT_CONFLICT_SAMPLE] 条，
     *   仅供文案展示 —— 全量清单不进 UI 状态）。
     */
    suspend fun previewImport(vaultId: String, imported: ImportedVault): ImportPlan
}

/** 导入预演结果（[VaultExportRepository.previewImport]）。 */
data class ImportPlan(
    /** 会被新建的条目数（已扣除「当前库不支持的类型」）。 */
    val newItemCount: Int,
    /** 因"库里已存在"被跳过的条目数（[ImportDuplicatePolicy] 判定）。 */
    val duplicateCount: Int,
    /** 重复条目的样例标题（去重后，最多 [IMPORT_CONFLICT_SAMPLE] 个）。 */
    val duplicateTitles: List<String>,
    /**
     * 因**文件内部重复**被跳过的条目数（2026-10-28 新增）。
     *
     * 去重若只拿"现有库"当基准，同一份文件里两条一样的会被原样复制；
     * 这一项让预览与写入共用同一份判定，避免"预演说新建 2、实际写 1"。
     */
    val duplicateInFileCount: Int = 0,
    /** 目标库不支持、会被跳过的条目数（KDBX 只收 Login / SecureNote）。 */
    val unsupportedCount: Int = 0,
    /** 目标库缺失、导入时会新建的文件夹数（按名字去重后的口径）。 */
    val newFolderCount: Int = 0,
) {
    companion object {
        /** 提示文案里最多展示几个重复条目名（再多就"等 N 条"）。 */
        const val IMPORT_CONFLICT_SAMPLE = 3

        /** 文件里没有任何可导入条目时的空计划。 */
        val EMPTY = ImportPlan(newItemCount = 0, duplicateCount = 0, duplicateTitles = emptyList())
    }
}

/**
 * 一次导入的完整结果（2026-10-28）。
 *
 * 为什么不是 `Int`：只报「写了几条」等于把**没写进去的**全藏起来。用户在 KDBX 目标上
 * 导入一份含卡/身份/SSH 的备份时，会看到「已导入 3 条」而不知道另外 7 条为什么不见。
 */
data class ImportResult(
    /** 实际写入的条目数。 */
    val written: Int,
    /** 库里已存在而跳过的条目数。 */
    val duplicateExisting: Int,
    /** 文件内部重复而跳过的条目数。 */
    val duplicateInFile: Int,
    /** 目标库不支持而跳过的条目数。 */
    val unsupported: Int,
    /** 写入失败的条目数（不静默吞掉）。 */
    val failed: Int,
) {
    /** 所有没有写进去的条目总数。 */
    val skipped: Int get() = duplicateExisting + duplicateInFile + unsupported + failed
}

/** 解密并映射后的导出内容（预览 + 导入的输入）。 */
data class ImportedVault(
    /** 文件内的文件夹（源端 id 与名字）。 */
    val folders: List<VaultFolder>,
    /** 文件内的条目（已映射为领域模型；`folderId` 仍是源端 id）。 */
    val items: List<VaultItem>,
)

/** 目标库未解锁：导出 / 导入都需要内存密钥，未解锁时无从解密。 */
class VaultNotUnlockedException(vaultId: String) :
    IllegalStateException("Vault is not unlocked: $vaultId")

/**
 * 导入失败原因分类（UI 据此给出可执行提示，而非笼统「导入失败」）。
 *
 * 与 data 层的异常一一对应；domain 不引 data 类型，故在此重声明。
 */
sealed class VaultImportException(message: String) : Exception(message) {

    /** 文件不是合法 JSON，或不是加密导出信封（缺必需字段）。 */
    class MalformedFile : VaultImportException("File is not a valid Bitwarden encrypted export")

    /** 导出密码错误。 */
    class WrongPassword : VaultImportException("Incorrect export password")

    /** 文件内声明的 KDF 不受支持。 */
    class UnsupportedKdf : VaultImportException("Unsupported KDF type in export file")
}
