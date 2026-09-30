/*
 * Vaultix — app（单测）
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 */
package io.vaultix.vaultix.ui.common

import com.google.common.truth.Truth.assertThat
import io.vaultix.data.repository.kdbx.WebDavVaultOrigin
import io.vaultix.vaultix.remote.onedrive.OneDriveVaultOrigin
import org.junit.Test

/**
 * 「这个库在哪」的解析口径（2026-09-30 用户要求）。
 *
 * 用户要求原话：「bitwarden 就显示用户的域名就可以了，**不要 https，不要完整的地址**；
 * kdbx 的话显示本地的路径，或者 onedrive 的路径」。
 *
 * ⇒ 三条断言各守一条：**去掉 scheme**、**去掉路径**（BW）、**按来源分派**（KDBX）。
 * 这些都是纯字符串逻辑，放 JVM 单测里钉住比真机看截图可靠得多。
 */
class VaultOriginLabelTest {

    // ---------------------------------------------------------------- Bitwarden：只给域名

    @Test
    fun `bitwarden 去掉 scheme 与路径`() {
        assertThat(serverHostOf("https://vault.bitwarden.com")).isEqualTo("vault.bitwarden.com")
        assertThat(serverHostOf("https://vault.bitwarden.com/")).isEqualTo("vault.bitwarden.com")
        // 自建常见的子路径接口地址 —— 路径必须被切掉，只留主机。
        assertThat(serverHostOf("https://vault.example.com/api/")).isEqualTo("vault.example.com")
    }

    @Test
    fun `bitwarden 保留端口（两台自建服务器靠它区分）`() {
        // ⚠️ 抹掉端口会让 `nas.local:8443` 与 `nas.local` 看起来同名，
        //    而用户正是靠这一行确认"连的是哪台"。
        assertThat(serverHostOf("https://nas.local:8443/dav")).isEqualTo("nas.local:8443")
    }

    @Test
    fun `bitwarden 没有 scheme 时原样返回主机`() {
        // 库表里的地址是用户输入后规范化过的，不代表一定有 `://` —— 没有时不能把整串吃掉。
        assertThat(serverHostOf("vault.example.com")).isEqualTo("vault.example.com")
    }

    // ---------------------------------------------------------------- KDBX：按来源分派

    @Test
    fun `本地 SAF 归为本地文件（绝不展示 content 前缀）`() {
        val safOrigin = "content://com.android.externalstorage.documents/document/primary%3ADownload%2Fx.kdbx"

        assertThat(kdbxSourceTargetOf(safOrigin)).isEqualTo(KdbxSourceTarget.LocalFile)
    }

    @Test
    fun `onedrive 的 origin 解析出网盘路径`() {
        // 走上游的 build（URL-encode）再解析：这条同时验证了"编码往返"这件事。
        val origin = OneDriveVaultOrigin.build("acc-1", "/Vaultix/我的库.kdbx")

        assertThat(kdbxSourceTargetOf(origin))
            .isEqualTo(KdbxSourceTarget.OneDrive("Vaultix/我的库.kdbx"))
    }

    @Test
    fun `webdav 的 origin 解析出文件 URL`() {
        val url = "https://nas.local:5006/vaultix-dav/valkjin.kdbx"
        val origin = WebDavVaultOrigin.build("cred-1", url)

        assertThat(kdbxSourceTargetOf(origin)).isEqualTo(KdbxSourceTarget.WebDav(url))
    }

    @Test
    fun `前缀像网盘但解析不了时退化为本地文件名（不谎报来源）`() {
        // 坏配置（缺 accountId / 路径为空）会让 parse 返回 null。此时退化成本地文件，
        // 而 LocalFile 的文案用的是**文件名**——所以不会出现"把网盘库说成本地路径"。
        assertThat(kdbxSourceTargetOf("onedrive:")).isEqualTo(KdbxSourceTarget.LocalFile)
        assertThat(kdbxSourceTargetOf("webdav:cred:")).isEqualTo(KdbxSourceTarget.LocalFile)
    }
}
