/*
 * Vaultix — data:bitwarden
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * 溯源声明（GPL-3.0 合规）
 * 「附件下载必须用独立 client」的做法与 Bastion 项目（GPL-3.0，Copyright 2025
 * JoyinJoester）一致；本文件为独立实现。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.data.bitwarden.di

import javax.inject.Qualifier

/**
 * 标记**不挂鉴权**的 OkHttp client（附件等第三方签名 URL 下载专用）。
 *
 * ## 为什么必须单独一个 client
 *
 * 附件的下载地址是服务器签发的**第三方对象存储直链**（Azure Blob / AWS S3）。
 * 默认 client 的 [io.vaultix.data.bitwarden.network.BitwardenAuthInterceptor]
 * 会对所有请求预挂 Bearer —— 那意味着**访问令牌会被发到 amazonaws.com 这类
 * 第三方域名**。令牌一旦进了第三方服务器的日志，等价于账号凭据泄漏。
 *
 * 该 client 保留 Cloudflare 兼容的请求头指纹（部分自托管站点的直链也过 CF），
 * 但**不挂** authInterceptor / Authenticator。
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AttachmentHttpClient
