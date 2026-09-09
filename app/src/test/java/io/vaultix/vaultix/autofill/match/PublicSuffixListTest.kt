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

    @Test
    fun `full psl wildcard compute amazonaws com`() {
        // *.compute.amazonaws.com 是通配公共后缀，故 myapp.compute.amazonaws.com 整体即基域。
        assertThat(PublicSuffixList.baseDomain("myapp.compute.amazonaws.com"))
            .isEqualTo("myapp.compute.amazonaws.com")
    }

    @Test
    fun `full psl bare compute amazonaws com is not a suffix`() {
        // PSL 中 compute.amazonaws.com 本身不是公共后缀（仅 *.compute.amazonaws.com 是），
        // 故公共后缀回退为 com，基域为 amazonaws.com（PSL 规范的 eTLD+1 语义）。
        assertThat(PublicSuffixList.baseDomain("compute.amazonaws.com"))
            .isEqualTo("amazonaws.com")
    }

    @Test
    fun `implicit suffix for unknown tld`() {
        assertThat(PublicSuffixList.baseDomain("example.unknowntld")).isEqualTo("example.unknowntld")
    }
}
