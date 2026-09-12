/*
 * Vaultix — data:kdbx
 * Copyright (C) 2026 Vaultix contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 */
package io.vaultix.data.kdbx

import app.keemobile.kotpass.cryptography.format.BaseCiphers
import app.keemobile.kotpass.cryptography.format.CipherProvider
import app.keemobile.kotpass.cryptography.format.TwofishCipher

/**
 * KDBX 可用的密码套件：kotpass 基础套件（AES-256-CBC / ChaCha20）+ **Twofish**。
 *
 * Twofish 只有 KDBX 3.1 的老库会用（KeePass 2.x 早期的可选算法），
 * 不注册它会让「版本识别通过、解密却失败」，用户只会看到「密码错误」。
 */
internal val KDBX_CIPHER_PROVIDERS: List<CipherProvider> =
    BaseCiphers.entries + TwofishCipher
