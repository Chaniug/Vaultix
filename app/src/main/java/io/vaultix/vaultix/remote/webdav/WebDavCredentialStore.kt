/*
 * Vaultix — app / WebDAV 凭据
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * **WebDAV 凭据的读写（唯一入口）**。
 *
 * ## 为什么必须封成一个类，而不是让 UI 自己 `putString`
 *
 * 落盘格式是「`用户名` + `\n` + `密码`」这种**没有任何自描述**的裸串：
 * 写错一个分隔符，读侧就解析不出来，而且**不会报任何错** ——
 * 表现是"配置成功了，但每次连接都说找不到凭据"。
 * ⇒ 序列化与反序列化必须在同一处（[save] / [read] 相隔二十行，改一个能立刻看见另一个）。
 *
 * ## 为什么用 `\n` 分隔（不用 `:`）
 *
 * WebDAV 用户名**可以**含 `:`（域账号 `DOMAIN\user`、`user:sub` 都常见），
 * 拿 `:` 当分隔符会在那种账号上**静默截断密码**。
 * 而 `\n` 不可能是 HTTP Basic 用户名的一部分（header 里出现裸换行就是请求走私，
 * 合法用户名不可能含它）。
 *
 * ## ⚠️ 这里同时也是 key 前缀的唯一真源
 *
 * 前缀 `webdav_credential::` **不能改名**：改名等于让所有已配置的库
 * 在下次解锁时说"找不到凭据"。读侧（DI 里的 `WebDavCredentialLookup`）
 * 已经改为委托到本类，因此不存在第二份前缀字面量。
 *
 * ## ⚠️ credentialId 为什么是**算出来的**而不是随机 UUID
 *
 * 随机 UUID 每配一次就多一份凭据，而 `SecureCredentialStore` 里那些再也无人引用的
 * 旧条目**没法清理**（不知道键）。用「服务器 + 用户名」的哈希当 id 则天然幂等：
 * 同一台 NAS 同一个账号重配 = 覆盖同一格，不留垃圾；换个账号才有新格子。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.vaultix.remote.webdav

import io.vaultix.data.repository.kdbx.WebDavCredentials
import io.vaultix.datastore.SecureCredentialStore
import java.security.MessageDigest
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** WebDAV 账号密码的落盘读写（格式见文件头）。 */
@Singleton
class WebDavCredentialStore @Inject constructor(
    private val credentials: SecureCredentialStore,
) {

    /**
     * 由「服务器地址 + 用户名」推出稳定的 credentialId。
     *
     * ## 为什么不能含 `:`
     *
     * credentialId 会被嵌进 origin（`webdav:<credentialId>:<url>`），而
     * [io.vaultix.data.repository.kdbx.WebDavVaultOrigin.parse] 按**第一个** `:` 切分
     * ⇒ id 里一旦有 `:`，origin 就再也解析不回来（表现为"这个库打不开"）。
     * 十六进制串天然不含 `:`，这里顺带把这个约束焊死在实现里。
     *
     * ## 为什么要归一化服务器地址
     *
     * 用户这次输 `https://nas.local/dav`、下次输 `https://nas.local/dav/`
     * （或大小写不同）时应该命**同一格**凭据，否则每次改配置都会留下孤儿密文。
     * 归一化只做"明显等价"的处理（去首尾空白、去尾斜杠、主机部分大小写），
     * **不**做路径重写 —— 路径不同就是不同目录，那不是同一个配置。
     */
    fun credentialIdFor(serverUrl: String, username: String): String {
        val normalizedServer = serverUrl.trim().trimEnd('/').lowercase(Locale.ROOT)
        val normalizedUser = username.trim()
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$normalizedServer\n$normalizedUser".toByteArray(Charsets.UTF_8))
        // 只取前 8 字节：它只是本机的一张索引，不是密码学凭据。
        // 碰撞概率对本场景（单机几十个库）可忽略，而短 id 让 origin 可读得多。
        return digest.take(CREDENTIAL_ID_BYTES).joinToString("") { "%02x".format(it) }
    }

    /** 写入（覆盖）某账号的凭据。**必须在 [testConnection] 之前调用**（见 AddCloudVaultViewModel）。 */
    fun save(credentialId: String, username: String, password: String) {
        credentials.putString(storageKey(credentialId), "$username$SEPARATOR$password")
    }

    /**
     * 读回凭据。
     *
     * @return null = 没配过 / 格式坏掉 / Keystore 换了密钥解不开。
     *   调用方一律按"请重新填写"处理 —— 具体是哪种原因对用户没有区别。
     */
    fun read(credentialId: String): WebDavCredentials? {
        val raw = credentials.getString(storageKey(credentialId)) ?: return null
        val separator = raw.indexOf(SEPARATOR)
        // `<= 0`：没有分隔符，或分隔符在开头（用户名是空的 ⇒ 必然错）。
        if (separator <= 0) return null
        return WebDavCredentials(
            username = raw.substring(0, separator),
            password = raw.substring(separator + 1),
        )
    }

    /**
     * 删除凭据。
     *
     * ★ 它的用途是**回滚**：配置流程里凭据必须先于连接自检落盘
     * （来源对象构造时就要按 id 取凭据），因此"连不上"时必须把刚写的那格擦掉，
     * 否则用户每试一次错密码就留下一条永远用不到的密文。
     */
    fun remove(credentialId: String) {
        credentials.remove(storageKey(credentialId))
    }

    private companion object {
        /** ⚠️ **不可改名** —— 改名等于所有已配置的库下次都说"找不到凭据"。 */
        const val KEY_PREFIX = "webdav_credential::"
        const val SEPARATOR = '\n'
        const val CREDENTIAL_ID_BYTES = 8

        fun storageKey(credentialId: String): String = "$KEY_PREFIX$credentialId"
    }
}
