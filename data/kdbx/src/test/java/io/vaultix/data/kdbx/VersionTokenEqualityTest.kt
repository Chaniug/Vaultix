/*
 * Vaultix — data:kdbx (test)
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 */
package io.vaultix.data.kdbx

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `isSameVersionToken` 的门禁（#244，2026-11-04）。
 *
 * ## 它守的是什么
 *
 * 这个函数回答「远端内容变了没有」。答错的**两个方向**代价完全不对称：
 *
 * | 答错 | 后果 |
 * |---|---|
 * | 说"变了"（假阳性） | 多下载一次 / 多弹一次冲突 —— 用户烦，但数据安全 |
 * | 说"没变"（假阴性） | 别人的改动永远拉不下来，而本机一改就把它**覆盖**掉 |
 *
 * ⇒ 本文件的用例按"**只在确实同一版时才说 true**"来写：
 * 格式迁移（裸值 ↔ 带 `ctag:` 前缀）必须认成同一版，**不同字段**（cTag vs eTag）
 * 一律不认。
 */
class VersionTokenEqualityTest {

    @Test
    fun `完全相同的令牌是同一版`() {
        assertTrue(isSameVersionToken("abc", "abc"))
        assertTrue(isSameVersionToken("\"abc\"", "abc"))
        assertTrue(isSameVersionToken("W/\"abc\"", "abc"))
    }

    @Test
    fun `两边都没有令牌视为同一状态`() {
        // 首次同步前：基线为空、远端也给不出令牌。此时不该谎报"远端变了"。
        assertTrue(isSameVersionToken(null, null))
        assertTrue(isSameVersionToken(null, ""))
        assertTrue(isSameVersionToken("  ", null))
    }

    @Test
    fun `只有一边有令牌就是不同版`() {
        // 空 ≠ 某个值：那通常意味着远端刚被创建（或令牌丢了），必须当成"变了"去对账。
        assertFalse(isSameVersionToken(null, "abc"))
        assertFalse(isSameVersionToken("abc", null))
    }

    @Test
    fun `裸值到带前缀的迁移认成同一版`() {
        // ★ 升级当天的关键一条：老库里存的是裸 eTag，新代码给的是 `ctag:...`。
        //   不认这条，每个老库第一次同步都要弹一次冲突。
        assertTrue(isSameVersionToken("abc", "ctag:abc"))
        assertTrue(isSameVersionToken("ctag:abc", "abc"))
        assertTrue(isSameVersionToken("abc", "etag:abc"))
    }

    @Test
    fun `ctag与etag是两个不同字段_绝不混为一谈`() {
        // 把两者当成同一版 = 凭空宣布"内容没变"，真正的内容改动会被漏掉。
        assertFalse(isSameVersionToken("ctag:abc", "etag:abc"))
        assertFalse(isSameVersionToken("etag:abc", "ctag:abc"))
    }

    @Test
    fun `前缀不同且值不同_当然不同版`() {
        assertFalse(isSameVersionToken("ctag:abc", "etag:def"))
        assertFalse(isSameVersionToken("abc", "def"))
    }

    @Test
    fun `带前缀的值仍会被剥掉引号再比`() {
        // Graph 给的值自带双引号，迁移期里旧数据可能存的是剥过引号的形态。
        assertTrue(isSameVersionToken("abc", "ctag:\"abc\""))
    }
}
