package com.bastion.app.util

import app.keemobile.kotpass.errors.CryptoError
import app.keemobile.kotpass.errors.FormatError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import com.bastion.app.utils.KeePassErrorCode
import com.bastion.app.utils.KeePassOperationException
import com.bastion.app.utils.toKeePassOperationException
import java.io.IOException

class KeePassErrorTest {

    @Test
    fun invalidKey_mapsToInvalidCredential() {
        val ex = CryptoError.InvalidKey("Wrong key used for decryption.")
            .toKeePassOperationException()
        assertEquals(KeePassErrorCode.INVALID_CREDENTIAL, ex.code)
    }

    // 原先 UnsupportedVersion 与选错文件、文件损坏一并塌缩为 FORMAT_UNSUPPORTED，
    // 用户只看到一句"格式不支持或文件已损坏"，无法自助处理。
    // 现已拆分为三类独立错误码，此处断言同步更新为 FORMAT_VERSION_TOO_NEW。
    @Test
    fun unsupportedVersion_mapsToFormatVersionTooNew() {
        val ex = FormatError.UnsupportedVersion("File version is not supported.")
            .toKeePassOperationException()
        assertEquals(KeePassErrorCode.FORMAT_VERSION_TOO_NEW, ex.code)
    }

    @Test
    fun unknownFormat_mapsToFormatNotKdbx() {
        val ex = FormatError.UnknownFormat("Not a KDBX file.")
            .toKeePassOperationException()
        assertEquals(KeePassErrorCode.FORMAT_NOT_KDBX, ex.code)
    }

    @Test
    fun invalidContent_mapsToFormatCorrupted() {
        val ex = FormatError.InvalidContent("Failed to parse content.")
            .toKeePassOperationException()
        assertEquals(KeePassErrorCode.FORMAT_CORRUPTED, ex.code)
    }

    @Test
    fun securityException_mapsToPermissionDenied() {
        val ex = SecurityException("Permission denied")
            .toKeePassOperationException()
        assertEquals(KeePassErrorCode.URI_PERMISSION_DENIED, ex.code)
    }

    @Test
    fun outOfMemory_mapsToKdfMemoryInsufficient() {
        val ex = OutOfMemoryError("Argon2 memory")
            .toKeePassOperationException()
        assertEquals(KeePassErrorCode.KDF_MEMORY_INSUFFICIENT, ex.code)
    }

    @Test
    fun ioException_mapsToReadWriteFailed() {
        val ex = IOException("Disk error")
            .toKeePassOperationException()
        assertEquals(KeePassErrorCode.IO_READ_WRITE_FAILED, ex.code)
    }

    @Test
    fun legacyKdbMessage_mapsToLegacyUnsupported() {
        val ex = IllegalStateException("legacy kdb file is not supported")
            .toKeePassOperationException()
        assertEquals(KeePassErrorCode.LEGACY_KDB_UNSUPPORTED, ex.code)
    }

    @Test
    fun mappedException_keepsOriginalInstance() {
        val original = KeePassOperationException(
            KeePassErrorCode.INVALID_CREDENTIAL,
            "数据库密码或密钥文件不正确"
        )
        val mapped = original.toKeePassOperationException()
        assertSame(original, mapped)
    }
}
