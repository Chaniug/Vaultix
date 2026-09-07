package com.bastion.app.utils

import app.keemobile.kotpass.errors.CryptoError
import app.keemobile.kotpass.errors.FormatError
import java.io.FileNotFoundException
import java.io.IOException
import java.util.Locale

enum class KeePassErrorCode {
    LEGACY_KDB_UNSUPPORTED,
    FORMAT_UNSUPPORTED,
    /** KDBX 版本高于本应用支持的版本（如未来的 KDBX 4.2 / 5.x）。 */
    FORMAT_VERSION_TOO_NEW,
    /** 文件不是 KDBX（选错文件、或文件已损坏/被截断）。 */
    FORMAT_NOT_KDBX,
    /** 文件头合法但内容解压/解密失败（文件损坏）。 */
    FORMAT_CORRUPTED,
    /** 使用了硬件密钥（YubiKey 等）Challenge-Response 加密的库，当前不支持。 */
    CHALLENGE_RESPONSE_UNSUPPORTED,
    INVALID_CREDENTIAL,
    URI_PERMISSION_DENIED,
    KDF_MEMORY_INSUFFICIENT,
    IO_READ_WRITE_FAILED,
    /** 更新/删除时按 Title+UserName+URL 兜底匹配命中多条（外来 KeePass 条目无 UUID 标记）。 */
    AMBIGUOUS_ENTRY_MATCH
}

class KeePassOperationException(
    val code: KeePassErrorCode,
    override val message: String,
    cause: Throwable? = null
) : Exception(message, cause)

fun Throwable.toKeePassOperationException(): KeePassOperationException {
    if (this is KeePassOperationException) return this

    val root = rootCause()
    val lowerMessage = (root.message ?: message ?: "").lowercase(Locale.ROOT)

    fun wrap(code: KeePassErrorCode, userMessage: String): KeePassOperationException {
        return KeePassOperationException(code = code, message = userMessage, cause = this)
    }

    if (root is SecurityException || lowerMessage.contains("permission denied") || lowerMessage.contains("eacces")) {
        return wrap(
            code = KeePassErrorCode.URI_PERMISSION_DENIED,
            userMessage = "文件权限不足，请重新授予数据库或密钥文件访问权限"
        )
    }

    if (root is OutOfMemoryError ||
        (lowerMessage.contains("argon2") && lowerMessage.contains("memory")) ||
        lowerMessage.contains("outofmemory")
    ) {
        return wrap(
            code = KeePassErrorCode.KDF_MEMORY_INSUFFICIENT,
            userMessage = "KDF 内存参数过高，设备内存不足，请降低内存占用或并行度"
        )
    }

    if (root is CryptoError.InvalidKey ||
        lowerMessage.contains("wrong key used for decryption") ||
        lowerMessage.contains("invalid credentials")
    ) {
        return wrap(
            code = KeePassErrorCode.INVALID_CREDENTIAL,
            userMessage = "数据库密码或密钥文件不正确"
        )
    }

    if (lowerMessage.contains("legacy kdb") ||
        lowerMessage.contains(".kdb (v1") ||
        lowerMessage.contains("keepass 1.x")
    ) {
        return wrap(
            code = KeePassErrorCode.LEGACY_KDB_UNSUPPORTED,
            userMessage = "检测到旧版 .kdb（KeePass 1.x）数据库，当前仅支持 .kdbx。请先在 KeePassDX/KeePassXC 中另存为 .kdbx 后再导入。"
        )
    }

    // Challenge-Response（YubiKey 等硬件密钥）加密的库目前无法打开。
    // 必须放在 INVALID_CREDENTIAL 之前判定，否则会被误报为"密码错误"，
    // 导致用户反复尝试密码而始终无法定位真正原因。
    if (lowerMessage.contains("challenge-response") ||
        lowerMessage.contains("challenge response") ||
        lowerMessage.contains("yubikey")
    ) {
        return wrap(
            code = KeePassErrorCode.CHALLENGE_RESPONSE_UNSUPPORTED,
            userMessage = "该数据库使用硬件密钥（YubiKey 等）的 Challenge-Response 加密，当前版本暂不支持。请在 KeePassXC/KeePass2 中改用「密码 + 密钥文件」方式重新保存后再导入。"
        )
    }

    // 以下把原先塌缩为同一句"格式不支持或文件已损坏"的多种 FormatError 拆开，
    // 让用户能区分「版本太新」「选错文件」与「文件损坏」三类可自助处理的情况。
    if (root is FormatError.UnsupportedVersion ||
        lowerMessage.contains("unsupported version") ||
        lowerMessage.contains("unsupported cipher id") ||
        lowerMessage.contains("unsupported header field") ||
        lowerMessage.contains("unsupported kdf")
    ) {
        return wrap(
            code = KeePassErrorCode.FORMAT_VERSION_TOO_NEW,
            userMessage = "数据库使用了当前版本尚不支持的加密格式或 KDBX 版本。请更新 Bastion，或在 KeePassXC/KeePass2 中另存为 KDBX 3.1 / 4.x 标准格式后重试。"
        )
    }

    if (root is FormatError.UnknownFormat ||
        root is FormatError.InvalidHeader ||
        lowerMessage.contains("unknown format") ||
        lowerMessage.contains("invalid header") ||
        lowerMessage.contains("bad magic")
    ) {
        return wrap(
            code = KeePassErrorCode.FORMAT_NOT_KDBX,
            userMessage = "该文件不是有效的 KDBX 数据库（可能选错了文件，或文件在传输中损坏）。请确认选择的是 .kdbx 文件，并重新从来源获取完整文件。"
        )
    }

    if (root is FormatError.InvalidContent ||
        root is FormatError.InvalidXml ||
        root is FormatError.FailedCompression ||
        lowerMessage.contains("failed compression") ||
        lowerMessage.contains("invalid content")
    ) {
        return wrap(
            code = KeePassErrorCode.FORMAT_CORRUPTED,
            userMessage = "数据库文件已损坏（内容解析失败）。请改用备份副本，或在 KeePassXC/KeePass2 中尝试修复后重新导入。"
        )
    }

    if (root is FileNotFoundException || root is IOException) {
        return wrap(
            code = KeePassErrorCode.IO_READ_WRITE_FAILED,
            userMessage = "读取或写入 KeePass 文件失败"
        )
    }

    return wrap(
        code = KeePassErrorCode.IO_READ_WRITE_FAILED,
        userMessage = root.message?.takeIf { it.isNotBlank() } ?: "KeePass 操作失败"
    )
}

fun Throwable.rootCause(): Throwable {
    var current: Throwable = this
    while (current.cause != null && current.cause !== current) {
        current = current.cause!!
    }
    return current
}
