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
 * | WebDAV 凭据 | 账号密码存 `SecureCredentialStore`（读写格式见 [WebDavCredentialStore]） | `@Provides WebDavCredentialLookup` |
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
import io.vaultix.data.kdbx.Kdbx
import io.vaultix.data.kdbx.KdbxFileSource
import io.vaultix.data.repository.kdbx.KdbxCloudSyncCoordinator
import io.vaultix.data.repository.kdbx.KdbxSessionReplacer
import io.vaultix.data.repository.kdbx.WebDavCredentialLookup
import io.vaultix.vaultix.remote.onedrive.OneDriveKdbxFileSource
import io.vaultix.vaultix.remote.webdav.WebDavCredentialStore
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
     * ★ 2026-09-17：读侧的"键格式 + `\n` 解析"**整体搬进了 [WebDavCredentialStore]**，
     * 这里只剩一次转发。原因：配置 UI 要**写**同一份凭据，而写侧若自己拼一次
     * `"$user\n$pass"`，两处格式就有漂移空间 —— 而那个错**没有任何报错**，
     * 表现是"配置成功了但每次连接都说找不到凭据"。
     * ⇒ 序列化与反序列化必须同处一地（见该类文件头）。
     *
     * ⚠️ 注意这里**拿不到** `credentialId` 之外的任何东西：origin 里只有它，
     * 账号密码一律现取（用户可能刚改过密码）。
     */
    @Provides
    @Singleton
    fun provideWebDavCredentialLookup(
        store: WebDavCredentialStore,
    ): WebDavCredentialLookup = WebDavCredentialLookup { credentialId ->
        store.read(credentialId)
    }

    /**
     * 会话替换 —— **免密**版（2026-10-01 接通）。
     *
     * ## 为什么必须免密
     *
     * 「远端更新了，本地拉下来」是同步要做的事。若每次都要用户重新输主密码，
     * 同步就退化成「手动 + 输密码」，等于没做。
     *
     * ## 免密的依据：凭据就在会话里
     *
     * 已解锁的会话**本来就存着**打开这个库用的那组凭据（`KdbxSession.credentials`，
     * 写回时 `KdbxRoundTrip` 也正用它重编码整库）⇒ 拿同一组凭据去解远端字节即可，
     * **不需要**快速解锁信封，也**不需要**把主密码以明文交回这一层。
     *
     * ## ⚠️ 这里此前是一个**恒定失败**的占位
     *
     * 旧实现无条件返回「云端已更新，请先锁定并重新解锁」—— 而用户照做、重新解锁，
     * 再点一次**还是这一句**（失败是恒定的，与解锁与否无关）。
     * 后果：「用远端覆盖本地」与「拉取远端更新」两条路都是**死路**，
     * 冲突永远解不掉、另一台设备的改动本机永远拉不下来。
     *
     * ⇒ 现在委托给 [Kdbx.replaceSession] —— 那是唯一一份实现，
     *    「拉字节 → 替换会话 → 才记状态」的顺序由协调器保证。
     */
    @Provides
    @Singleton
    fun provideKdbxSessionReplacer(): KdbxSessionReplacer = KdbxSessionReplacer { vaultId, bytes ->
        Kdbx.replaceSession(vaultId = vaultId, remoteBytes = bytes)
    }
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
