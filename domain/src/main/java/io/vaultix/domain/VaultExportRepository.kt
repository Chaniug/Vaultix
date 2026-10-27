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
     * 由目标库重新分配 id）；文件夹按名字匹配已有文件夹，缺失的跳过
     * （Vaultix 不支持在客户端新建文件夹，见 [FolderRepository] 的说明）。
     *
     * @return 实际写入的条目数（未知类型条目会被跳过，故可能小于文件内条目数）。
     */
    suspend fun applyImportedVault(vaultId: String, imported: ImportedVault): Int

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
    /** 会被新建的条目数。 */
    val newItemCount: Int,
    /** 因"已存在"被跳过的条目数（[ImportDuplicatePolicy] 判定）。 */
    val duplicateCount: Int,
    /** 重复条目的样例标题（去重后，最多 [IMPORT_CONFLICT_SAMPLE] 个）。 */
    val duplicateTitles: List<String>,
) {
    companion object {
        /** 提示文案里最多展示几个重复条目名（再多就"等 N 条"）。 */
        const val IMPORT_CONFLICT_SAMPLE = 3

        /** 文件里没有任何可导入条目时的空计划。 */
        val EMPTY = ImportPlan(newItemCount = 0, duplicateCount = 0, duplicateTitles = emptyList())
    }
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
