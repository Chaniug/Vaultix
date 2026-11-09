/*
 * Vaultix — data:repository
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * `blocksCreate` 的边界测试（纯函数，不需要 Android 运行时）。
 *
 * 背景：2026-11-10 用户真机「新建本地 KDBX 库 100% 报已存在同名文件」——
 * `CreateDocument` 会先建一个 0 字节占位，而旧判据把"存在"当冲突。
 * 三格边界逐一钉住，防止将来有人把判据改回"存在即冲突"。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.data.repository.kdbx

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SafCreatePolicyTest {

    /**
     * 正常新建路径：`CreateDocument` 刚建好的 0 字节占位 —— **必须放行**。
     *
     * 同一条判据同时覆盖"上次失败留下的残骸"：两者的字节完全一样（都是空），
     * 差别只在"怎么来的"，而判据只看内容 —— 它确实没有内容可毁，
     * 原地重建比让用户先去文件管理器删一次要好。
     */
    @Test
    fun `empty file does not block creation`() {
        assertThat(blocksCreate(ByteArray(0))).isFalse()
    }

    /** 读不到（文件不存在 / 授权失效）：不该由这一步拦，交给真正的写去报错。 */
    @Test
    fun `unreadable target does not block creation`() {
        assertThat(blocksCreate(null)).isFalse()
    }

    /** 同名但已有内容：这才是 `failIfExists` 要保护的东西，**必须拦**。 */
    @Test
    fun `existing content blocks creation`() {
        assertThat(blocksCreate(byteArrayOf(0x03, 0xD9.toByte(), 0xA2.toByte()))).isTrue()
    }

    /** 哪怕只有 1 个字节也算"有内容"——判据是 `isNotEmpty`，不是"像不像 KDBX"。 */
    @Test
    fun `single byte counts as content`() {
        assertThat(blocksCreate(byteArrayOf(0x00))).isTrue()
    }
}
