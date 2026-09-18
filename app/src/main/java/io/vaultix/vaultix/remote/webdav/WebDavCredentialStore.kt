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

/**
 * 一个**已保存过凭据**的 WebDAV 账号（不含密码）。
 *
 * @param serverUrl 用户当初填的服务器地址（原样保留，仅用于展示与重算 id）。
 * @param username 账号。
 * @param credentialId 由上面两者算出（见 [WebDavCredentialStore.credentialIdFor]）。
 */
data class ConfiguredWebDavAccount(
    val serverUrl: String,
    val username: String,
    val credentialId: String,
)

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
        val normalizedServer = clean(serverUrl).trim().trimEnd('/').lowercase(Locale.ROOT)
        val normalizedUser = clean(username).trim()
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$normalizedServer\n$normalizedUser".toByteArray(Charsets.UTF_8))
        // 只取前 8 字节：它只是本机的一张索引，不是密码学凭据。
        // 碰撞概率对本场景（单机几十个库）可忽略，而短 id 让 origin 可读得多。
        return digest.take(CREDENTIAL_ID_BYTES).joinToString("") { "%02x".format(it) }
    }

    /**
     * 写入（覆盖）某账号的凭据。**必须在自检之前调用**
     * （来源对象构造时就要按 id 取凭据，见 `CloudAccountConnector`）。
     *
     * ⚠️ [serverUrl] 只用于**登记索引**（[listConfigured]），不参与加解密。
     * 调用方**必须**传它 —— 否则这个账号配完之后在「网盘账号」里列不出来。
     */
    fun save(serverUrl: String, username: String, password: String): String {
        val credentialId = credentialIdFor(serverUrl, username)
        credentials.putString(storageKey(credentialId), "$username$SEPARATOR$password")
        indexAdd(ConfiguredWebDavAccount(serverUrl, username, credentialId))
        return credentialId
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
        indexRemove(credentialId)
    }

    // ------------------------------------------------------------------ 索引

    /**
     * ★ **已保存过凭据的全部 WebDAV 账号**（不含密码）。
     *
     * ## 为什么需要它
     *
     * credentialId 是「服务器 + 用户名」的**哈希**，从 id **反推不出**地址 ⇒
     * 「我配过哪些账号」这个问题原本**答不出来**。
     * 后果：`CloudAccountInventory` 只能从**库的 origin 反推**账号 ⇒
     * 「配了凭据、但还没建库」的账号**列不出来** ⇒
     * 一旦把配置入口搬到「网盘账号」页，用户配完会以为"没配上"。
     *
     * ## ⚠️ 为什么这不是「第二份真相」
     *
     * 项目反复被咬的是**两处独立维护的真相必然漂移**（见
     * `.ai/decisions/设置页信息架构-定稿.md`）。本索引不是那种东西：
     *
     * - 它**不存密码**，只是「哪些 id 存在」的一份目录；
     * - 它与凭据在**同一处、同一次调用**里维护（[save] / [remove] 各自行末尾一行），
     *   不存"删了凭据却忘了删索引"的可能；
     * - 即使索引丢失/损坏，后果也**只是列不出来**，凭据本身照常可用（库照常解锁）。
     *
     * ⇒ 这是"让现有真相**可枚举**"，不是"另建一份真相"。
     */
    fun listConfigured(): List<ConfiguredWebDavAccount> {
        val raw = credentials.getString(INDEX_KEY) ?: return emptyList()
        return raw.lineSequence()
            .mapNotNull { line ->
                val parts = line.split(INDEX_FIELD_SEPARATOR)
                // 字段数不对 / 有空字段 ⇒ 这一行坏了。**跳过而不是让整个清单废掉**。
                if (parts.size != INDEX_FIELDS || parts.any { it.isBlank() }) return@mapNotNull null
                val (serverUrl, username) = parts
                ConfiguredWebDavAccount(
                    serverUrl = serverUrl,
                    username = username,
                    credentialId = credentialIdFor(serverUrl, username),
                )
            }
            .toList()
    }

    /** 登记一行（已存在则按 credentialId 去重，保证幂等）。 */
    private fun indexAdd(account: ConfiguredWebDavAccount) {
        val rest = listConfigured().filter { it.credentialId != account.credentialId }
        indexWrite(rest + account)
    }

    private fun indexRemove(credentialId: String) {
        indexWrite(listConfigured().filter { it.credentialId != credentialId })
    }

    private fun indexWrite(accounts: List<ConfiguredWebDavAccount>) {
        if (accounts.isEmpty()) {
            credentials.remove(INDEX_KEY)
            return
        }
        credentials.putString(
            INDEX_KEY,
            accounts.joinToString(separator = "\n") {
                clean(it.serverUrl) + INDEX_FIELD_SEPARATOR + clean(it.username)
            },
        )
    }

    /**
     * 压掉会**打断索引文本**的控制字符：`\n` 是记录分隔符、`\t` 是字段分隔符，
     * 字段里出现它们会让这一行解析失败 ⇒ 该账号从清单里静默消失
     * （凭据本身还在、库照常能用，只是列不出来）。粘贴进来的地址带换行不是不可能。
     *
     * ★ 它必须**同时**用于 [credentialIdFor]：否则 `save` 按原始串算出的 id 与
     * `listConfigured` 按清洗后串重算出的 id 会**不相等**（去重失效 + 列出的 id 指向空格子）。
     * 放进 id 推导里，两边就恒等。
     *
     * ★ 只影响**索引与 id**：凭据本体仍按原样落盘（真实账号里本就不会有这些字符）。
     */
    private fun clean(value: String): String =
        value.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ')

    private companion object {
        /** ⚠️ **不可改名** —— 改名等于所有已配置的库下次都说"找不到凭据"。 */
        const val KEY_PREFIX = "webdav_credential::"
        const val SEPARATOR = '\n'
        const val CREDENTIAL_ID_BYTES = 8

        /** 索引的键（与凭据同库，但不是一个 credential 键，不会被 `[storageKey]` 撞上）。 */
        const val INDEX_KEY = "webdav_account_index"
        /** ⚠️ 用 tab 而不是 `\n`：记录内字段分隔符不能与记录分隔符相同。 */
        const val INDEX_FIELD_SEPARATOR = '\t'
        const val INDEX_FIELDS = 2

        fun storageKey(credentialId: String): String = "$KEY_PREFIX$credentialId"
    }
}
