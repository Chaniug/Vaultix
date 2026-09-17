/*
 * Vaultix — data:repository
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * **「origin 字符串 ⇒ 文件来源对象」的唯一判据表。**
 *
 * ## 为什么单独一个接口（而不是让解锁路径依赖整个 [KdbxCloudSyncCoordinator]）
 *
 * 需要它的有三处，全部在**解锁 / 校验**路径上：
 * `VaultRepositoryImpl` · `LocalUnlockEnrollment` · `PinEnrollment`。
 * 它们只想知道"这个 origin 该用哪个来源读"，并不需要同步状态机、冲突处理那一整套。
 * 抽成接口后：
 *  1. 依赖面从"整个协调器"缩到"一个函数"；
 *  2. 将来若同步链反向依赖了仓储（免密会话替换就会 —— 它要拿 app 侧的快解锁凭据），
 *     也不会把这几个解锁类一起拖进 Hilt 的构造环。
 *
 * ## ★ 判据只能有一份
 *
 * 本接口的实现**就是** [KdbxCloudSyncCoordinator]（它 `: KdbxFileSourceResolver`）。
 * ⚠️ **不要在任何地方再写一份 `when (origin.startsWith("webdav:"))`** ——
 * 两份判据迟早不一致，且失效方式**没有任何报错**：表现是"同步找得到来源、
 * 解锁却说找不到"（或反过来），用户看到的是"这个库点了没反应"。
 * 2026-09-17 那一轮就是因为读、写走了两套来源体系，导致网盘库
 * **加不进来也解锁不了**（方案 §16.1）。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.data.repository.kdbx

import io.vaultix.data.kdbx.KdbxFileSource

/**
 * 把持久化的 `VaultEntity.origin` 解析成可读写的文件来源。
 *
 * 判别表（实现在 [KdbxCloudSyncCoordinator.fileSourceFor]）：
 * `content://…` ⇒ SAF · `webdav:<credId>:<url>` ⇒ WebDAV · 其余 ⇒ app 注册的工厂（OneDrive）。
 */
fun interface KdbxFileSourceResolver {

    /**
     * @return null = 这个 origin 没有可用的来源（UI 据此提示"该库还没有可用的文件来源"，
     *   而**不是**胡乱拼一个路径去读 —— 读不到会被误报成"密码错误"）。
     */
    fun fileSourceFor(origin: String): KdbxFileSource?

    /**
     * 按 [uri] 读一个**附属文件**的字节（目前只用于 KDBX 的 keyfile）。
     *
     * ## 为什么放在这里而不是让调用方自己 `Uri.parse`
     *
     * keyfile 在 SAF 场景下同样是一个 `content://`，解析规则与库文件**完全一样**。
     * 让调用方另写一遍解析，就是上面说的"第二份判据"。
     * 附带好处：将来 keyfile 也可以放在网盘上（拿到的就是同一套来源）。
     *
     * ## 为什么用 suspend
     *
     * [KdbxFileSource.read] 是挂起的（网盘来源要走网络）。keyfile 现在只从本地 SAF 读，
     * 但把这里做成挂起意味着**将来不必改签名**。
     *
     * @return null = 没配 keyfile（[uri] 为 null / 空白），**或**读不到。
     *   ⚠️ 调用方**不能**把这两种情况混为一谈：KDBX 的 keyfile 不是可选装饰，
     *   少它一个字节就是开不了库。所以"有 URI 但读不到"必须走
     *   "这组凭据验不过"那条路（`Kdbx.verify` 拿不到字节时自然验不过），
     *   而不是静默降级成"仅主密码"—— 降级的结果是信封里躺一组**永远解不开**的凭据，
     *   用户只会看到"密码不对"，真因（keyfile 读不到）无从得知。
     */
    suspend fun readBytes(uri: String?): ByteArray? {
        val target = uri?.takeIf { it.isNotBlank() } ?: return null
        val source = fileSourceFor(target) ?: return null
        // 读不到（授权失效 / 文件被删）：如实返回 null，让上面的取舍在调用方生效。
        return runCatching { source.read() }.getOrNull()
    }
}
