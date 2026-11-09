/*
 * Vaultix — data:repository
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * **「只创建」遇到既有文件时该不该拒写** —— `SafKdbxFileSource.write(failIfExists)` 的判据。
 *
 * ## 🔴 这个文件修的是什么（2026-11-10 用户真机，新建本地库 100% 失败）
 *
 * 用户流程：设置 → 新建本地 KDBX → 填库名与主密码 → 「创建并选择保存位置」→
 * 系统面板确认文件名 → 界面报「该位置已存在同名文件，已取消创建以免覆盖」。
 * 再回头打开刚才那个位置的文件，得到「该文件不是 KDBX 数据库」。
 *
 * 两条报错**都不算错**，但合起来描述的是一个不存在的用户错误：那个文件是
 * **系统在上一步刚替我们建好的 0 字节占位**，不是"别人的库"。
 *
 * ```
 *   CreateDocument  →  返回 URI（文件此刻已存在，0 字节）
 *          ↓
 *   createVault(failIfExists = true)
 *          ↓
 *   旧判据 existing != null  →  冲突  ← ❌ 把自己刚建的占位当成了别人的文件
 * ```
 *
 * ## 为什么判据必须是「有内容」而不是「存在」
 *
 * `failIfExists` 要保护的是**用户已有库的内容**。0 字节没有内容可毁：
 *
 * | 场景 | 旧判据 | 新判据 | 哪个对 |
 * |---|---|---|---|
 * | `CreateDocument` 刚建好的占位（**正常路径**） | 冲突 ❌ | 放行 ✅ | 新 |
 * | 上次新建失败留下的 0 字节残骸 | 冲突 ❌ | 放行 ✅（原地重建，用户少一步） | 新 |
 * | 同名但已有内容的库 | 冲突 ✅ | 冲突 ✅ | 一致 |
 *
 * ⚠️ 网盘侧没有这个坑：`PUT` 到一个不存在的路径不会先建空文件，所以只有本地
 * 新建会 100% 复现 —— 这也是它长期没被别处路径暴露出来的原因。
 *
 * ## 为什么是一个独立文件 + 纯函数
 *
 * 与 [SafCachePolicy] 同一取向：`SafKdbxFileSource` 依赖 `ContentResolver`，
 * 本模块的单测跑不了 Android 运行时。把**判据**放在纯函数里，测试就能覆盖
 * 三种边界（null / 空 / 有内容），而不必起 Robolectric。
 * ⚠️ 判据只有这一份：`SafKdbxFileSource` 必须调用本函数，别再在那里写一遍
 * `existing != null && existing.isNotEmpty()` —— 两处判据迟早会分家。
 * ---------------------------------------------------------------------------
 */

package io.vaultix.data.repository.kdbx

/**
 * 「只创建」写入前，读到的既有内容是否应当**阻止**这次写入。
 *
 * @param existing 目标文件当前内容的读取结果：
 *   - `null` = 读不到（文件不存在 / 授权失效）⇒ 不阻止（交给后续的写去报真实错误）；
 *   - 空数组 = 文件存在但**没有任何内容** ⇒ 不阻止（见文件头）；
 *   - 非空 = 文件有内容 ⇒ **阻止**，绝不覆盖。
 * @return `true` = 应当抛冲突，拒写。
 *
 * ⚠️ 返回 `false` 不等于"写入一定成功"：它只回答"这一步该不该拦"。
 *   真正的写失败（磁盘满 / 授权被撤 / provider 拒绝）仍由 `openOutputStream`
 *   那一层抛出，那是另一件事。
 */
internal fun blocksCreate(existing: ByteArray?): Boolean = existing != null && existing.isNotEmpty()
