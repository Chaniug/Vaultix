package com.bastion.app.sync

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 绑定型 passkey 合并改造的源码守卫测试。
 *
 * 防止后续改动回退关键行为：
 * 1. 绑定型 passkey 必须走「合并进密码 cipher」（PUT），不能创建独立 cipher；
 * 2. 绑定型 passkey 删除绝不能 queueCipherDelete（会误删整个密码 cipher）；
 * 3. 下载侧必须回填 boundPasswordId；
 * 4. 同步流程必须执行历史独立 passkey cipher 迁移。
 */
class PasskeySyncMergeGuardTest {

    private val uploadProcessorSource = projectFile(
        "app/src/main/java/com/bastion/app/bitwarden/service/CipherUploadProcessor.kt"
    ).readText()
    private val syncProcessorSource = projectFile(
        "app/src/main/java/com/bastion/app/bitwarden/service/CipherSyncProcessor.kt"
    ).readText()
    private val repositorySource = projectFile(
        "app/src/main/java/com/bastion/app/bitwarden/repository/BitwardenRepository.kt"
    ).readText()
    private val passkeyListScreenSource = projectFile(
        "app/src/main/java/com/bastion/app/ui/screens/PasskeyListScreen.kt"
    ).readText()
    private val codecSource = projectFile(
        "app/src/main/java/com/bastion/app/bitwarden/mapper/Fido2CredentialCodec.kt"
    ).readText()
    private val syncServiceSource = projectFile(
        "app/src/main/java/com/bastion/app/bitwarden/service/BitwardenSyncService.kt"
    ).readText()
    private val exportSource = projectFile(
        "app/src/main/java/com/bastion/app/bitwarden/export/BitwardenJsonExport.kt"
    ).readText()
    private val mergeServiceSource = projectFile(
        "app/src/main/java/com/bastion/app/bitwarden/service/BitwardenHistoricalPasskeyMergeService.kt"
    ).readText()

    @Test
    fun boundPasskeyUploadMustMergeIntoPasswordCipherInsteadOfCreatingStandaloneCipher() {
        val pendingBody = uploadProcessorSource
            .substringAfter("suspend fun uploadPendingPasskeys(")
            .substringBefore("private fun normalizePasskeyForUpload(")

        assertTrue(
            "绑定型 passkey 且密码已有 cipherId 时必须走 mergePasskeyIntoPasswordCipher（PUT 密码 cipher），否则服务器会继续出现独立 [Passkey] 条目。",
            pendingBody.contains("mergePasskeyIntoPasswordCipher(") &&
                pendingBody.contains("boundPassword.bitwardenCipherId")
        )
        assertTrue(
            "未绑定/密码未同步的 passkey 必须保留独立 uploadPasskey 创建路径。",
            pendingBody.contains("uploadPasskey(vault, passkey, accessToken, symmetricKey)")
        )
    }

    @Test
    fun mergeMustFetchBaselineBeforePutToAvoidOverwritingServerPasskeys() {
        assertTrue(
            "合并必须基于服务器 baseline（禁止盲 PUT 清空服务器上其他 passkey）。",
            uploadProcessorSource.contains("fetchCipherForFieldMerge(vaultApi, accessToken, passwordCipherId)")
        )
        assertTrue(
            "合并必须按 credentialId 去重并保留其余既有 credential。",
            uploadProcessorSource.contains("mergeByCredentialId(")
        )
    }

    @Test
    fun boundPasskeyDeleteMustNotQueueCipherDelete() {
        val deleteBody = passkeyListScreenSource
            .substringAfter("suspend fun deletePasskeyWithBinding(")
            .substringBefore("val performDeleteTargets")

        assertTrue(
            "绑定型 passkey 删除必须走 DELETE_PENDING（由同步从密码 cipher 的 fido2Credentials 移除），绝不能 queueCipherDelete 整个密码 cipher。",
            deleteBody.contains("SYNC_STATUS_DELETE_PENDING") &&
                deleteBody.contains("isBoundToSyncedPassword") &&
                deleteBody.contains("!boundPassword.bitwardenCipherId.isNullOrBlank()")
        )
    }

    @Test
    fun downloadMustBackfillBoundPasswordId() {
        val syncPasskeyBody = syncProcessorSource
            .substringAfter("private suspend fun syncPasskeyCipher(")
            .substringBefore("// ========== 辅助方法")

        assertTrue(
            "下载侧必须按 cipherId 查密码条目并回填 passkey.boundPasswordId。",
            syncPasskeyBody.contains("getByBitwardenCipherIdInVault(vault.id, cipher.id)") &&
                syncPasskeyBody.contains("boundPasswordId")
        )
    }

    @Test
    fun syncFlowMustRunHistoricalStandalonePasskeyMigration() {
        val syncBody = repositorySource
            .substringAfter("suspend fun sync(")
            .substringBefore("suspend fun getVaultCacheRiskSummary(")

        // 注意：不能用 indexOf("uploadLocalEntries")，注释里可能出现该字样；
        // 用调用点 "syncService.uploadLocalEntries" 定位实际上传位置。
        val mergeIndex = syncBody.indexOf("mergeHistoricalStandalonePasskeys(")
        val uploadIndex = syncBody.indexOf("syncService.uploadLocalEntries")

        assertTrue(
            "同步流程必须在 uploadLocalEntries 之前执行历史独立 passkey cipher 迁移。",
            mergeIndex >= 0 &&
                uploadIndex >= 0 &&
                syncBody.contains("processPendingOperations") &&
                mergeIndex < uploadIndex
        )
    }

    @Test
    fun fido2CredentialCodecMustExistWithCoreCapabilities() {
        assertTrue(
            "公共编解码器必须包含解密、加密、去重三个核心能力。",
            codecSource.contains("fun decodeFido2Credentials(") &&
                codecSource.contains("fun decryptCredentialsToPlainApiData(") &&
                codecSource.contains("fun encryptCredential(") &&
                codecSource.contains("fun mergeByCredentialId(")
        )
    }

    @Test
    fun uploadAndExportMustNotWriteLegacyPasskeyBindingsField() {
        assertTrue(
            "passkey 绑定关系已由官方 fido2Credentials 承载，上传侧不得再写入 bastion_passkey_bindings 私有自定义字段。",
            !syncServiceSource.contains("addField(\"bastion_passkey_bindings\"") &&
                !syncServiceSource.contains("add(\"bastion_passkey_bindings\"")
        )
        assertTrue(
            "JSON 导出同样不应写入 bastion_passkey_bindings（避免服务器端冗余脏数据）。",
            !exportSource.contains("add(\"bastion_passkey_bindings\"")
        )
    }

    @Test
    fun downloadMustRebuildPasskeyBindingsFromLocalPasskeys() {
        val syncLoginBody = syncProcessorSource
            .substringAfter("private suspend fun syncLoginCipher(")
            .substringBefore("private suspend fun syncPasswordCipher(")

        assertTrue(
            "下载侧必须在 passkey 同步后重建密码条目的 passkey_bindings 列（以本地 passkeys 表为准）。",
            syncLoginBody.contains("rebuildPasswordPasskeyBindings(") &&
                syncProcessorSource.contains("fun rebuildPasswordPasskeyBindings(") &&
                syncProcessorSource.contains("PasskeyBindingCodec.encodeList(")
        )
    }

    @Test
    fun syncMustCleanupLegacyPasskeyBindingsField() {
        assertTrue(
            "同步时必须清理历史冗余自定义字段 bastion_passkey_bindings（从服务器 cipher 的 fields 剔除后 PUT）。",
            mergeServiceSource.contains("cleanupLegacyPasskeyBindingsField(") &&
                mergeServiceSource.contains("isLegacyPasskeyBindingsField(") &&
                mergeServiceSource.contains("CipherUpdateRequest(")
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
