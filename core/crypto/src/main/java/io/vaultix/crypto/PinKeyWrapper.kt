/*
 * Vaultix — core:crypto
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * 本文件为 Vaultix 新增（Bastion 无对应实现）：应用内 PIN 的密钥包裹层。
 */
package io.vaultix.crypto

import javax.crypto.AEADBadTagException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PIN 解包结果（**三态**，供 UI 区分「PIN 输错」与「数据坏掉」）。
 *
 * 为什么要分三态而不是「成功 / 失败」：这两种失败对用户的含义完全不同 ——
 * 输错要提示重试并计数，数据坏则要如实告知并提供重设路径。混成一个 `null`
 * 会让 UI 无法给出正确动作（项目纪律：**「空」有多态，塌进单一分支即成假状态**）。
 */
sealed interface PinUnwrapResult {

    /** 解包成功。⚠️ [payload] 是明文密钥材料，调用方**用完必须清零**。 */
    class Opened(val payload: ByteArray) : PinUnwrapResult

    /** PIN 不正确（GCM 标签校验失败）。 */
    data object WrongPin : PinUnwrapResult

    /** 信封本身不合法（前缀不对 / base64 坏 / 被截断）。**不是** PIN 错。 */
    data class Malformed(val detail: String) : PinUnwrapResult
}

/**
 * 应用内 PIN 的密钥包裹层。
 *
 * ## 为什么需要单独一层
 *
 * 本地快速解锁既有的 KEK（`LocalUnlockKeyStore`）是**系统认证**保护的：
 * 每次使用都要过 `BiometricPrompt`（指纹 / 设备凭据）。应用内 PIN 在
 * Vaultix 自己的界面上输入，**无法满足**那个约束，因此它必须有自己的包裹路径。
 *
 * ## ★ 安全模型：安全性**不来自** PIN 的位数
 *
 * 6 位 PIN 的熵只有 10^6。单靠它派生密钥，包裹物一旦被拖离设备，就能在电脑上
 * 以百万次/秒的速度爆破。本层能成立，是因为它**与硬件外层叠加**：
 * 落盘时还会被 `SecureCredentialStore` 那把「不要求用户认证、但不可导出」的
 * Android Keystore 密钥再封一次。
 *
 * ⇒ **破译者必须同时持有这台设备**（光有落盘文件没用）；
 * ⇒ 再叠上 Argon2id 的**内存硬**特性（每次猜测都要付出 64MiB 级代价）与
 *    失败次数封锁，设备上的在线爆破同样不可行。
 *
 * 换句话说：PIN 换的是一把**更顺手、但不更弱**的门锁 —— 它没有降低库密钥强度，
 * 只是把「每次都要按指纹」换成「输 6 位数字」。
 *
 * ## 信封格式
 *
 * ```
 * PV1:<salt_b64>:<GcmSealed.encode()>      例如
 * PV1:<16字节盐>:VG1.<nonce>|<密文+标签>
 * ```
 * - 分隔符用 `:` 是**刻意**的：base64 标准字母表与内层 `VG1.` 信封都不含 `:`，
 *   因此按 `:` 切 3 段无歧义（内层自带 `.` 与 `|`，若也拿它们当外部分隔符必踩坑）。
 * - `PV1` 是**独立于** `VG1` 的版本前缀：Argon2id 参数不写进信封，由前缀固定。
 *   ⚠️ 将来若调整参数，**必须新增 `PV2` 并保留 `PV1` 的读取分支**，
 *   否则老用户的 PIN 会原地失效。
 *
 * ## 与主密码的关系
 *
 * PIN **只**用于解开这份已包好的密钥，**不参与派生库密钥**（Docs/09）。
 * 因此忘记 PIN 永远不等于丢数据：用主密码解锁后重设即可。
 */
@Singleton
class PinKeyWrapper @Inject constructor(
    private val crypto: VaultixCrypto,
) {

    /**
     * Argon2id 参数。生产取 [PinArgon2Params.PRODUCTION]；
     * 单测用 [useParams] 调小 —— 每条 PIN 用例都真跑 64MiB 会把测试拖到几十秒，
     * 而参数本身并不是被测对象。
     */
    private var params: PinArgon2Params = PinArgon2Params.PRODUCTION

    /** 仅供单测：切换 Argon2id 参数。 */
    internal fun useParams(overridden: PinArgon2Params) {
        params = overridden
    }

    /**
     * 用 [pin] 包裹 [payload]，返回可持久化的信封字符串。
     *
     * 每次调用都生成**新的随机盐**：同一 PIN 两次包裹必须产出不同密文
     * （否则「两个库用了同一个 PIN」会被落盘数据一眼看出）。
     * 也正因如此，**修改 PIN 必须重新包裹**，不能只改盐。
     */
    fun wrap(pin: String, payload: ByteArray): String {
        val salt = CryptoRandom.nextBytes(SALT_BYTES)
        val key = deriveKey(pin, salt)
        return try {
            val sealed = aesGcmEncrypt(key, payload, AAD)
            buildString {
                append(ENVELOPE_PREFIX)
                append(FIELD_SEPARATOR)
                append(salt.encodeStandardBase64())
                append(FIELD_SEPARATOR)
                append(sealed.encode())
            }
        } finally {
            key.zero()
            salt.fill(0)
        }
    }

    /**
     * 用 [pin] 解包 [wrapped]。
     *
     * 信封解析失败与 PIN 错误**分开返回**：前者说明落盘数据坏了（该走重设），
     * 后者才是「再输一次」（该计数）。把两者混成同一个失败会让 UI 做错动作。
     *
     * @suppress SwallowedException：此处**故意**吞掉 `AEADBadTagException` ——
     * 它本身就是「PIN 不对」的信号，不是需要上报的故障；异常对象里也没有额外
     * 诊断信息（GCM 标签不过时平台不区分「密钥错」与「密文被改」，这是有意的）。
     * 若把它链出去，只会诱导上层把「用户打错一位数字」当成崩溃来对待。
     */
    @Suppress("SwallowedException")
    fun unwrap(pin: String, wrapped: String): PinUnwrapResult {
        val parts = wrapped.split(FIELD_SEPARATOR, limit = FIELD_COUNT)
        if (parts.size != FIELD_COUNT || parts[FIRST_FIELD] != ENVELOPE_PREFIX) {
            return PinUnwrapResult.Malformed("unsupported PIN envelope prefix")
        }
        val salt = runCatching { decodeStandardBase64(parts[SALT_FIELD], "pin salt") }.getOrNull()
            ?: return PinUnwrapResult.Malformed("PIN salt is not valid base64")
        val sealed = runCatching { GcmSealed.decode(parts[CIPHERTEXT_FIELD]) }.getOrNull()
            ?: return PinUnwrapResult.Malformed("PIN ciphertext envelope is malformed")

        val key = deriveKey(pin, salt)
        return try {
            PinUnwrapResult.Opened(aesGcmDecrypt(key, sealed, AAD))
        } catch (error: AEADBadTagException) {
            // GCM 标签不过 = 密钥不对（PIN 输错）或密文被改。两者对用户都是「再试一次」。
            PinUnwrapResult.WrongPin
        } finally {
            key.zero()
            salt.fill(0)
            sealed.wipe()
        }
    }

    private fun deriveKey(pin: String, salt: ByteArray): SecureBytes =
        crypto.deriveKeyArgon2(
            passphrase = pin,
            salt = salt,
            iterations = params.iterations,
            memoryMb = params.memoryMb,
            parallelism = params.parallelism,
        )

    private companion object {
        /** 信封前缀：Vaultix PIN v1（参数固定为 [PinArgon2Params.PRODUCTION]）。 */
        const val ENVELOPE_PREFIX = "PV1"

        /** 字段分隔符；`:` 不在 base64 / `VG1` 信封的字母表里，故无歧义。 */
        const val FIELD_SEPARATOR = ':'

        const val FIELD_COUNT = 3
        const val FIRST_FIELD = 0
        const val SALT_FIELD = 1
        const val CIPHERTEXT_FIELD = 2

        /** Argon2id 盐长度（16 字节 = 128 位，远超「同盐碰撞」所需的随机性）。 */
        const val SALT_BYTES = 16

        /**
         * 附加认证数据：把信封与用途绑死。
         * 少了它，同一把 PIN 衍生的密钥在其它协议里可能被复用（跨协议混淆）。
         */
        val AAD: ByteArray = "vaultix-pin-v1".toByteArray(Charsets.UTF_8)
    }
}

/**
 * PIN 派生用的 Argon2id 参数。
 *
 * 单独成类而不是散落字面量：生产参数与测试参数必须一眼可辨。
 * ⚠️ 这些参数**不写进信封**，由 [PinKeyWrapper] 的 `PV1` 前缀固定（见其 KDoc）。
 */
internal data class PinArgon2Params(
    val iterations: Int,
    val memoryMb: Int,
    val parallelism: Int,
) {
    companion object {
        /** 生产参数：直接取 [VaultixCrypto] 的 Argon2id 默认值，**不与主密码档位降级**。 */
        val PRODUCTION = PinArgon2Params(
            iterations = VaultixCrypto.DEFAULT_ARGON2_ITERATIONS,
            memoryMb = VaultixCrypto.DEFAULT_ARGON2_MEMORY_MB,
            parallelism = VaultixCrypto.DEFAULT_ARGON2_PARALLELISM,
        )
    }
}
