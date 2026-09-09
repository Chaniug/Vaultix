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

class PublicSuffixListTest {

    @Test
    fun `single label returns as is`() {
        assertThat(PublicSuffixList.baseDomain("localhost")).isEqualTo("localhost")
    }

    @Test
    fun `two labels returns as is`() {
        assertThat(PublicSuffixList.baseDomain("example.com")).isEqualTo("example.com")
    }

    @Test
    fun `subdomain keeps registrable domain`() {
        assertThat(PublicSuffixList.baseDomain("sub.example.com")).isEqualTo("example.com")
    }

    @Test
    fun `deep subdomain keeps registrable domain`() {
        assertThat(PublicSuffixList.baseDomain("a.b.example.com")).isEqualTo("example.com")
    }

    @Test
    fun `multi level suffix co uk`() {
        assertThat(PublicSuffixList.baseDomain("sub.example.co.uk")).isEqualTo("example.co.uk")
    }

    @Test
    fun `multi level suffix com cn`() {
        assertThat(PublicSuffixList.baseDomain("example.com.cn")).isEqualTo("example.com.cn")
    }

    @Test
    fun `strips www prefix`() {
        assertThat(PublicSuffixList.baseDomain("www.example.com")).isEqualTo("example.com")
    }

    @Test
    fun `lowercases host`() {
        assertThat(PublicSuffixList.baseDomain("Example.COM")).isEqualTo("example.com")
    }
}
