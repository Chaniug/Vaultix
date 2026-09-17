/*
 * Vaultix — app / KDBX 网盘同步装配
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * **app 侧把「data:repository 不知道的那两块」补上**：
 *
 * | 缺的是什么 | 为什么 data 层给不了 | 在这里怎么补 |
 * |---|---|---|
 * | OneDrive 来源 | 需要 MSAL（`OneDriveAuthManager` 就在 app） | `@Provides KdbxFileSource.Factory` |
 * | WebDAV 凭据 | 账号密码存 `SecureCredentialStore`，键格式是 app 约定 | `@Provides WebDavCredentialLookup` |
 * | 会话替换 | 免密重开要 app 侧的快解锁凭据信封 | `@Provides KdbxSessionReplacer` |
 *
 * ⚠️ 这里**不**提供 `OkHttpClient` —— 那是 `data:bitwarden` 的 `NetworkModule` 已经在
 * 管的单例（带 Cloudflare 兼容指纹 + Bearer 预挂拦截器）。自己再造一个会多一份连接池，
 * 且两个客户端的超时/重试行为开始漂移。
 *
 * ## ⚠️ OneDrive 工厂是「注册」而不是「注入」
 *
 * `registerFactory` 必须在**同步真正被调用之前**跑过一次。装配方式是注入
 * [KdbxCloudSyncCoordinator] 到一个 `@Singleton` 的初始化器，在它的 `init` 里注册。
 * 用 `init` 而不是"等第一次同步时再注册"：后者的失败模式是"第一次同步说没有来源、
 * 第二次却好了"，属于最难查的那种时序 bug。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.vaultix.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.vaultix.data.kdbx.KdbxFileSource
import io.vaultix.data.repository.kdbx.KdbxCloudSyncCoordinator
import io.vaultix.data.repository.kdbx.KdbxSessionReplacer
import io.vaultix.data.repository.kdbx.WebDavCredentialLookup
import io.vaultix.data.repository.kdbx.WebDavCredentials
import io.vaultix.datastore.SecureCredentialStore
import io.vaultix.vaultix.remote.onedrive.OneDriveKdbxFileSource
import javax.inject.Inject
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object KdbxCloudSyncAppModule {

    /**
     * OneDrive 来源工厂 —— 接到协调器的注册口上。
     *
     * 返回 `(origin) -> KdbxFileSource?`，非 OneDrive 的 origin 返回 null（协调器据此
     * 继续走别的实现）。**不在这里判断 origin 前缀** —— 前缀知识只有
     * `OneDriveVaultOrigin` 一处真值源，重复写一遍迟早漂移。
     */
    @Provides
    @Singleton
    fun provideOneDriveKdbxSourceFactory(
        factory: OneDriveKdbxFileSource.Factory,
    ): OneDriveSourceFactory = OneDriveSourceFactory(factory::create)

    /**
     * WebDAV 凭据查询。
     *
     * ⚠️ 键格式（`webdav_credential::<credentialId>`）与 [WebDavCredentials] 的
     * 序列化格式**必须**是这里说了算：账号密码以 `账号\n密码` 明文形态交给
     * [SecureCredentialStore]，由后者用 Keystore 里的不可导出密钥做 AES-GCM 包装。
     * 换句话说，**明文只在内存里存在一瞬间**，落盘的是密文。
     *
     * ⚠️ 用 `\n` 而不是 `:` 分隔：WebDAV 用户名**可以**含 `:`（域账号 `DOMAIN\user`
     * 或 `user:sub` 都有），拿 `:` 当分隔符会在那种账号上静默截断密码。
     * 而 `\n` 不可能是 HTTP Basic 用户名的一部分（header 里出现裸换行就是请求走私，
     * 合法的用户名不可能含它）。
     */
    @Provides
    @Singleton
    fun provideWebDavCredentialLookup(
        credentials: SecureCredentialStore,
    ): WebDavCredentialLookup = WebDavCredentialLookup { credentialId ->
        val raw = credentials.getString(webDavCredentialKey(credentialId)) ?: return@WebDavCredentialLookup null
        val separator = raw.indexOf('\n')
        if (separator <= 0) return@WebDavCredentialLookup null
        WebDavCredentials(
            username = raw.substring(0, separator),
            password = raw.substring(separator + 1),
        )
    }

    /**
     * 会话替换 —— **免密**版。
     *
     * ## 为什么要免密（为什么不能用「请重新解锁」凑）
     *
     * 「远端更新了，本地拉下来」是**自动同步**要做的事。如果每次都要用户重新输主密码，
     * 自动同步就退化成"手动 + 输密码"，等于没做。免密的前提是 app 侧的快解锁凭据
     * （`LocalUnlockEnrollment` / `PinUnlockStore` 的信封）能解出主密码。
     *
     * ## ⚠️ 这里**只**尝试免密，失败就如实报告
     *
     * 解不出（用户没开快速解锁、或信封被系统认证锁住）时**不能**编一个"成功"返回 ——
     * 那会让状态显示"已用远端覆盖"，而会话里还是旧内容，用户下次保存就把旧内容推回去，
     * **静默覆盖远端的新版本**。宁可让用户多点一次解锁，也不能丢数据。
     *
     * 真正的解密动作委托给 [KdbxSessionReplacer] 的下一个实现（见下方 TODO 标注的
     * `UnlockCredentialSessionReplacer`）—— 本批先不接，理由写在那里。
     */
    @Provides
    @Singleton
    fun provideKdbxSessionReplacer(): KdbxSessionReplacer = KdbxSessionReplacer { _, _ ->
        Result.failure(
            IllegalStateException("云端已更新。请先锁定并重新解锁该密码库，再执行同步。"),
        )
    }

    internal fun webDavCredentialKey(credentialId: String): String = "webdav_credential::$credentialId"
}

/**
 * 让 OneDrive 工厂有**类型**（而不是两个 `(String) -> KdbxFileSource?` 直接撞一起）。
 *
 * ⚠️ 有必要：协调器的注册口与"别的网盘"的工厂签名完全相同。都不加类型的话，
 * Hilt 分不清谁是谁，接线时错把 A 塞给 B 的位置**编译期发现不了** ——
 * 那种错会表现为"配置了 OneDrive 却总说没有来源"。包一层就有类型可依。
 */
class OneDriveSourceFactory(
    private val factory: (String) -> KdbxFileSource?,
) {
    fun create(origin: String): KdbxFileSource? = factory(origin)
}

/**
 * 启动时把 OneDrive 工厂注册进协调器。
 *
 * ## 为什么需要一个 `@Singleton` 的初始化器
 *
 * 协调器上的 [KdbxCloudSyncCoordinator.registerFactory] 是一个**副作用**，
 * Hilt 不会因为"有人 @Inject 了协调器"就替我们执行它。所以必须有一个东西
 * 在应用启动时把协调器拿到手并调用一次 —— 那就是这个类。
 *
 * ⚠️ 它必须在进程启动的早期被实例化。做法是在 [io.vaultix.vaultix.VaultixApplication]
 * 里 `@Inject` 一个字段（见该文件的 `kdbxCloudSyncInitializer`）——
 * **不要**改成"第一次同步时懒注册"：那会让第一次同步报"没有云端来源"、
 * 第二次却正常，是最难查的时序 bug。
 */
@Singleton
class KdbxCloudSyncInitializer @Inject constructor(
    coordinator: KdbxCloudSyncCoordinator,
    oneDriveFactory: OneDriveSourceFactory,
) {
    init {
        coordinator.registerFactory(oneDriveFactory::create)
    }
}
