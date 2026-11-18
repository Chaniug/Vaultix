/*
 * Vaultix — app:autofill · match 单测
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 */
package io.vaultix.vaultix.autofill.match

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EquivalentDomainsTest {

    @Test
    fun `same domain is equivalent`() {
        assertThat(EquivalentDomains.isEquivalent("google.com", "google.com")).isTrue()
    }

    @Test
    fun `members of same group are equivalent`() {
        assertThat(EquivalentDomains.isEquivalent("google.com", "youtube.com")).isTrue()
    }

    @Test
    fun `subdomain of a group member is equivalent`() {
        // 组里存的是裸域，登录页通常是 www. / accounts. 子域 —— 必须先归一到基域再查表。
        assertThat(EquivalentDomains.isEquivalent("www.google.com", "youtube.com")).isTrue()
        assertThat(EquivalentDomains.isEquivalent("accounts.google.com", "youtube.com")).isTrue()
    }

    @Test
    fun `same base domain is not reported as equivalent group`() {
        // 同基域要走 baseDomainMatch（100 分），不能冒充等价域（139 分），否则排序失真。
        assertThat(EquivalentDomains.isEquivalent("www.google.com", "google.com")).isFalse()
    }

    @Test
    fun `different groups are not equivalent`() {
        assertThat(EquivalentDomains.isEquivalent("google.com", "microsoft.com")).isFalse()
    }

    @Test
    fun `unknown domains are not equivalent`() {
        assertThat(EquivalentDomains.isEquivalent("foo.example", "bar.example")).isFalse()
    }
}
