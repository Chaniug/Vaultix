package com.bastion.app.sync

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「同步/登出不得误清本地数据」守卫测试（P2 上游采纳项）。
 *
 * 背景：Monica 修复过「同步后通行密钥丢失」，Keyguard 修复过
 * 「404 被当作终态失败」——共同教训是同步侧对"服务器异常/空响应"
 * 必须保守，本地未上传的数据绝不能被顺手清掉。
 *
 * 防止后续改动回退以下关键行为：
 * 1. 全量同步空响应清理必须保留本地未上传（cipher_id 为空）的记录；
 * 2. passkey 的全量删除（deleteAllByBitwardenVaultId）仅限用户显式路径
 *    （登出 logout / 强制清缓存 FULL_FORCE），同步流程不可达；
 * 3. clearVaultLocalReferences 只能由这两个用户显式入口触发；
 * 4. 密码 / SecureItem / passkey 的 synced 清理 SQL 必须保留 IS NOT NULL 保护。
 */
class VaultLocalDataGuardTest {

    private val syncServiceSource = projectFile(
        "app/src/main/java/com/bastion/app/bitwarden/service/BitwardenSyncService.kt"
    ).readText()
    private val repositorySource = projectFile(
        "app/src/main/java/com/bastion/app/bitwarden/repository/BitwardenRepository.kt"
    ).readText()
    private val passkeyDaoSource = projectFile(
        "app/src/main/java/com/bastion/app/data/PasskeyDao.kt"
    ).readText()
    private val passwordEntryDaoSource = projectFile(
        "app/src/main/java/com/bastion/app/data/PasswordEntryDao.kt"
    ).readText()
    private val secureItemDaoSource = projectFile(
        "app/src/main/java/com/bastion/app/data/SecureItemDao.kt"
    ).readText()

    @Test
    fun fullSyncEmptyServerResponseMustKeepLocalUnuploadedRecords() {
        val cleanupBody = syncServiceSource
            .substringAfter("if (activeServerCipherIds.isEmpty()) {")
            .substringBefore("} else {")

        assertTrue(
            "全量同步空响应清理必须使用带保护的删除：密码 deleteAllSyncedBitwardenEntries、SecureItem deleteAllSyncedBitwardenEntries、passkey deleteAllSyncedBitwardenPasskeys。",
            cleanupBody.contains("passwordEntryDao.deleteAllSyncedBitwardenEntries(") &&
                cleanupBody.contains("secureItemDao.deleteAllSyncedBitwardenEntries(") &&
                cleanupBody.contains("passkeyDao.deleteAllSyncedBitwardenPasskeys(")
        )
        assertTrue(
            "同步流程内禁止出现无保护的 passkeyDao.deleteAllByBitwardenVaultId（会把本地未上传的通行密钥一并清掉）。",
            !syncServiceSource.contains("passkeyDao.deleteAllByBitwardenVaultId(")
        )
    }

    @Test
    fun syncedCleanupQueriesMustKeepLocalOnlyRecordsProtected() {
        assertTrue(
            "PasskeyDao.deleteAllSyncedBitwardenPasskeys 必须带 bitwarden_cipher_id IS NOT NULL 过滤（保留本地新建未上传的记录）。",
            passkeyDaoSource.contains("suspend fun deleteAllSyncedBitwardenPasskeys(") &&
                Regex(
                    "DELETE\\s+FROM\\s+passkeys\\s+WHERE\\s+bitwarden_vault_id\\s*=\\s*:vaultId\\s+AND\\s+bitwarden_cipher_id\\s+IS\\s+NOT\\s+NULL",
                    RegexOption.IGNORE_CASE
                ).containsMatchIn(passkeyDaoSource)
        )
        // 密码与 SecureItem 的 synced 清理保护不可回退。
        val passwordSyncedQuery = passwordEntryDaoSource
            .substringBefore("suspend fun deleteAllSyncedBitwardenEntries")
            .takeLast(600)
        assertTrue(
            "PasswordEntryDao.deleteAllSyncedBitwardenEntries 必须保留 bitwarden_cipher_id IS NOT NULL 与非墓碑过滤。",
            Regex(
                "DELETE\\s+FROM\\s+password_entries[\\s\\S]*?bitwarden_cipher_id\\s+IS\\s+NOT\\s+NULL[\\s\\S]*?isDeleted\\s*=\\s*0",
                RegexOption.IGNORE_CASE
            ).containsMatchIn(passwordSyncedQuery)
        )
        assertTrue(
            "SecureItemDao 必须保留带保护的 synced 清理方法（全删方法仅供登出/强制清缓存使用）。",
            secureItemDaoSource.contains("deleteAllSyncedBitwardenEntries")
        )
    }

    @Test
    fun clearVaultLocalReferencesMustOnlyBeReachableFromExplicitUserActions() {
        // clearVaultLocalReferences 是 private，调用点只允许：
        // 1) logout（用户显式登出）；2) clearVaultCache 的 FULL_FORCE 分支（用户显式操作）。
        val definitionMarker = "private suspend fun clearVaultLocalReferences("
        val callSites = Regex("clearVaultLocalReferences\\(")
            .findAll(repositorySource)
            .map { it.range.first }
            .filter { index ->
                // 排除定义本身
                !repositorySource.startsWith(definitionMarker, maxOf(0, index - "private suspend fun ".length))
            }
            .toList()

        assertTrue(
            "clearVaultLocalReferences 必须存在调用（登出/清缓存）。",
            callSites.isNotEmpty()
        )
        callSites.forEach { index ->
            val context = repositorySource.substring(
                maxOf(0, index - 600),
                minOf(repositorySource.length, index + 100)
            )
            val isLogoutCall = context.contains("suspend fun logout(")
            val isFullForceCall = context.contains("CacheClearMode.FULL_FORCE")
            assertTrue(
                "clearVaultLocalReferences 只能由用户显式路径调用（logout / FULL_FORCE 清缓存），同步失败等路径绝不可达。违规上下文：${context.takeLast(120)}",
                isLogoutCall || isFullForceCall
            )
        }
    }

    @Test
    fun passkeyFullDeleteMustNotBeInvokedOutsideLogoutOrCacheClear() {
        // deleteAllByBitwardenVaultId（全删）在业务层的合法调用点仅限
        // BitwardenRepository.clearVaultLocalReferences（登出/FULL_FORCE 事务内）。
        val businessCallers = listOf(
            "app/src/main/java/com/bastion/app/bitwarden/repository/BitwardenRepository.kt",
            "app/src/main/java/com/bastion/app/bitwarden/service/BitwardenSyncService.kt"
        ).map { projectFile(it).readText() }

        val syncService = businessCallers[1]
        assertTrue(
            "同步服务（BitwardenSyncService）不得调用 passkeyDao.deleteAllByBitwardenVaultId。",
            !syncService.contains("passkeyDao.deleteAllByBitwardenVaultId(")
        )
        val repository = businessCallers[0]
        val count = Regex("passkeyDao\\.deleteAllByBitwardenVaultId\\(")
            .findAll(repository)
            .count()
        assertTrue(
            "BitwardenRepository 中 passkey 全删只应出现在 clearVaultLocalReferences（1 处）。",
            count == 1
        )
    }

    private fun projectFile(relativePath: String): File {
        var dir = File(requireNotNull(System.getProperty("user.dir"))).canonicalFile
        while (
            dir.parentFile != null &&
            !File(dir, "settings.gradle").exists() &&
            !File(dir, "settings.gradle.kts").exists()
        ) {
            dir = dir.parentFile
        }
        return File(dir, relativePath)
    }
}
