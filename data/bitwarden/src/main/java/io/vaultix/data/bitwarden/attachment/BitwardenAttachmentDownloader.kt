/*
 * Vaultix — data:bitwarden
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * 溯源声明（GPL-3.0 合规）
 * 下载→验MAC→解密的链路划分参考 Bastion 项目（GPL-3.0，Copyright 2025
 * JoyinJoester）的附件下载实现；解密细节独立实现（先验 MAC、再解密，
 * 与参考实现的流式方案不同，理由见 [BitwardenAttachmentCrypto] 的 KDoc）。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.data.bitwarden.attachment

import io.vaultix.crypto.SymmetricCryptoKey
import io.vaultix.crypto.VaultixCrypto
import io.vaultix.data.bitwarden.di.AttachmentHttpClient
import io.vaultix.data.bitwarden.di.BitwardenApiFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bitwarden 附件**下载 + 解密**编排器（只读方向，2026-10-21）。
 *
 * 链路：`GET /ciphers/{id}/attachment/{attachmentId}` 拿签名 URL →
 * 用**无鉴权** client 下载密文 → 解包附件密钥 → 验 MAC → 解密写 [sink]。
 *
 * ## ⚠️ 为什么下载不能走默认 OkHttp client
 *
 * 签名 URL 指向第三方对象存储（Azure Blob / AWS S3）。默认 client 的
 * [io.vaultix.data.bitwarden.network.BitwardenAuthInterceptor] 会给所有请求
 * 预挂 Bearer —— 那等于把**访问令牌送进第三方服务器的访问日志**。
 * 本类注入的是 [@AttachmentHttpClient]（无鉴权，只保留浏览器指纹头）。
 *
 * ## 为什么"密钥解包失败"和"MAC 不匹配"都要在整条链路里可区分
 *
 * - 解包失败（EncString 解不开）= 密钥体系对不上，重试无意义；
 * - MAC 不匹配（[AttachmentAuthException]）= 密文被篡改 / 附件不完整，重试也无意义；
 * - 网络 / HTTP 非 2xx = 环境问题，重试有意义。
 * 三者共用一个 `Exception` 会让 UI 的"重试"提示变成误导。
 */
@Singleton
class BitwardenAttachmentDownloader @Inject constructor(
    private val apiFactory: BitwardenApiFactory,
    @AttachmentHttpClient private val httpClient: OkHttpClient,
    private val crypto: VaultixCrypto,
) {

    /**
     * 下载并解密一个附件，明文写入 [sink]（不关闭它 —— 关闭归调用方）。
     *
     * @param server 库的服务器地址（origin）。
     * @param wrappedKey 附件独立密钥的 EncString（`attachments[i].key`）；null = 老附件
     *   直接用账号密钥当附件密钥（官方语义，2025 年前的数据没有 per-attachment key）。
     * @param candidateKeys 解包 wrappedKey 的候选密钥，按**条目密钥 → 账号密钥**的
     *   顺序传入（与字段解密的回退顺序同构）；wrappedKey 为 null 时取第一个。
     * @return 写出的明文字节数。
     */
    suspend fun downloadTo(
        server: String,
        cipherId: String,
        attachmentId: String,
        wrappedKey: String?,
        candidateKeys: List<SymmetricCryptoKey>,
        sink: OutputStream,
    ): Result<Long> = withContext(Dispatchers.IO) {
        runCatching {
            // ① 拿签名 URL。url 为空 = 服务器不支持该端点（过老的 Vaultwarden）。
            val url = apiFactory.vault(server)
                .attachmentDownload(cipherId, attachmentId)
                .url ?: error("服务器未返回附件下载地址")

            // ② 下载**密文**（整份进内存：先验 MAC 的前提；附件上限 100MB 是
            //    移动端官方上限，密文体积与明文同级，可接受 —— 理由同 KDoc）。
            val encrypted = httpClient.newCall(Request.Builder().url(url).build())
                .execute()
                .use { response ->
                    require(response.isSuccessful) { "附件下载失败：HTTP ${response.code}" }
                    val body = response.body ?: error("附件下载失败：响应体为空")
                    body.bytes()
                }

            // ③ 解出附件密钥：有 wrappedKey 逐个候选解包（全失败 = 密钥体系对不上）；
            //    没有 = 候选密钥本身就是附件密钥（老数据）。
            val attachmentKeys: List<SymmetricCryptoKey> =
                if (wrappedKey.isNullOrBlank()) {
                    candidateKeys.take(1)
                } else {
                    candidateKeys.mapNotNull { key ->
                        runCatching { crypto.decryptSymmetricKey(wrappedKey, key) }.getOrNull()
                    }
                }
            require(attachmentKeys.isNotEmpty()) { "附件密钥解包失败，密钥不匹配" }

            // ④ 解密（内部先验 MAC，失败抛 [AttachmentAuthException]，一个字节不写 sink）。
            //    格式异常（AttachmentFormatException）与密钥无关，直接向上抛，不换钥匙重试。
            var lastError: Exception? = null
            for (key in attachmentKeys) {
                try {
                    return@runCatching BitwardenAttachmentCrypto.decrypt(encrypted, key, sink)
                } catch (e: AttachmentAuthException) {
                    lastError = e
                }
            }
            throw lastError ?: AttachmentAuthException()
        }
    }
}
