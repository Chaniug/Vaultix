/*
 * Vaultix — core:common
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 */
package io.vaultix.common

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class UriFormatTest {

    @Test
    fun websiteHttp_isClassifiedWithHost() {
        val kind = UriFormat.classify("https://example.com/login")
        assertThat(kind).isInstanceOf(UriKind.Website::class.java)
        assertThat((kind as UriKind.Website).host).isEqualTo("example.com")
    }

    @Test
    fun bareDomain_isClassifiedAsWebsite() {
        val kind = UriFormat.classify("example.org")
        assertThat(kind).isInstanceOf(UriKind.Website::class.java)
        assertThat((kind as UriKind.Website).host).isEqualTo("example.org")
    }

    @Test
    fun wwwPrefix_isStripped() {
        val kind = UriFormat.classify("https://www.beta.io/path")
        assertThat((kind as UriKind.Website).host).isEqualTo("beta.io")
    }

    @Test
    fun androidAppScheme_isClassifiedWithPackage() {
        val kind = UriFormat.classify("androidapp://com.example.app")
        assertThat(kind).isInstanceOf(UriKind.AndroidApp::class.java)
        assertThat((kind as UriKind.AndroidApp).packageName).isEqualTo("com.example.app")
    }

    @Test
    fun androidAppScheme_withTrailingPath_isClassifiedWithPackage() {
        val kind = UriFormat.classify("androidapp://com.example.app/")
        assertThat((kind as UriKind.AndroidApp).packageName).isEqualTo("com.example.app")
    }

    @Test
    fun androidAppDashScheme_isStillRecognized() {
        val kind = UriFormat.classify("android-app://com.other.pkg")
        assertThat(kind).isInstanceOf(UriKind.AndroidApp::class.java)
        assertThat((kind as UriKind.AndroidApp).packageName).isEqualTo("com.other.pkg")
    }

    @Test
    fun iosAppScheme_isOther() {
        val kind = UriFormat.classify("iosapp://com.example.app")
        assertThat(kind).isInstanceOf(UriKind.Other::class.java)
    }

    @Test
    fun empty_isOther() {
        assertThat(UriFormat.classify("   ")).isInstanceOf(UriKind.Other::class.java)
    }
}
