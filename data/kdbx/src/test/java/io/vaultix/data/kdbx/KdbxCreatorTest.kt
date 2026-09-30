/*
 * Vaultix — data:kdbx（单测）
 * Copyright (C) 2026 Vaultix contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 */
package io.vaultix.data.kdbx

import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.database.Credentials
import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.decode
import app.keemobile.kotpass.database.encode
import app.keemobile.kotpass.models.Meta
import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayOutputStream
import java.util.UUID
import org.junit.Test

/**
 * **新建 KDBX 库**（阶段 B · 批次 W0）的验收。
 *
 * ## 本文件守的两条"用户硬要求"
 *
 * 1. 「库的标准要匹配最新的 kdbx 格式，只做最新兼容，4.1 以上，**不向下兼容 3.0 等**」
 *    ⇒ [新建的库确实是 KDBX 4_1] + [三点一的老库被明确拒绝] + [拒绝文案指向 4x 而不是 3x]
 * 2. 「库要能被 DX、XC 之类的 kdbx 密码管理器打开」
 *    ⇒ [新建的库用的是通用套件]（AES-256 / GZip / KDBX 4.1；cipher UUID 逐字节钉死）
 *
 * ⚠️ 这两条都**不是**"跑通就算"：第 2 条里最容易翻车的是**加密套件选错**
 * （选了 ChaCha20 的话自己读得回来、XC/DX 却打不开），而单测跑在 JVM 上、
 * 用同一份 kotpass 编解码 —— **自己一定能读回自己**。
 * ⇒ 所以套件参数不能只靠"往返成功"来验，必须**直接断言文件头里那个 cipher UUID**。
 *   真正的互操作验收在真机 + 桌面（施工单 §6.0），单测只负责把"选型跑偏"挡在本地。
 */
class KdbxCreatorTest {

    // ---------------------------------------------------------------- 新建：基本可用性

    @Test
    fun `新建的库能被它自己的密码打开`() {
        val created = KdbxCreator.createEmpty(name = "我的库", password = PASSWORD)

        val opened = KdbxOpener.open(created.bytes, PASSWORD, keyFileBytes = null)

        assertThat(opened.isSuccess).isTrue()
        // ★ 建完库要顺手登记会话，用的必须是**这一组**凭据。
        //   这里直接拿 `created.credentials` 去解码 —— 它成立，"登记会话时凭据一致"
        //   这个不变量才成立（分开算两次凭据正是"自己建的库自己打不开"的来源）。
        assertThat(decode(created.bytes, created.credentials).content.group.name).isEqualTo("我的库")
        assertThat(created.credentialLabel).isEqualTo("created/password-only")
    }

    @Test
    fun `主密码不对时打不开新建的库`() {
        val created = KdbxCreator.createEmpty(name = "我的库", password = PASSWORD)

        val opened = KdbxOpener.open(created.bytes, "definitely-not-the-password", keyFileBytes = null)

        assertThat(opened.isFailure).isTrue()
        assertThat(errorKindOf(opened)).isInstanceOf(KdbxOpenError.InvalidCredentials::class.java)
    }

    @Test
    fun `用 keyfile 建的库必须带同一份 keyfile 才打得开`() {
        val keyFile = ByteArray(32) { index -> (index + 1).toByte() }
        val created = KdbxCreator.createEmpty(name = "带 keyfile", password = PASSWORD, keyFileBytes = keyFile)

        // 只给主密码 → 开不了（keyfile 是密码的一部分，不是可选装饰）。
        assertThat(KdbxOpener.open(created.bytes, PASSWORD, keyFileBytes = null).isFailure).isTrue()
        // 密码 + 同一份 keyfile → 开得了。
        assertThat(KdbxOpener.open(created.bytes, PASSWORD, keyFileBytes = keyFile).isSuccess).isTrue()
    }

    // ---------------------------------------------------------------- 新建：格式与套件

    @Test
    fun `校验与解锁都不会改动调用方的 keyfile 字节`() {
        // ★★ 这条守的是一个**真实踩到过**的线上缺陷，不是假想：
        //   `LocalUnlockEnrollment` 组装快速解锁信封的写法是
        //       readBytes(keyFileUri) → Kdbx.verify(..., keyFileBytes) → 用**同一份数组**组信封
        //   而上游 `EncryptedValue.fromBinary` 会**原地 XOR 改写**传进去的数组
        //   （`parseKeyfile(32 字节)` 更是"原样返回同一引用"）⇒
        //   信封里存下的是**废数据** ⇒ 「带 keyfile 的库启用快速解锁后，指纹再也开不了它」。
        //   修法是 `buildCredentialCandidates` 在边界上 copy（唯一那个交 keyfile 给 kotpass 的地方）。
        val keyFile = ByteArray(32) { index -> (index + 1).toByte() }
        val created = KdbxCreator.createEmpty(name = "我的库", password = PASSWORD, keyFileBytes = keyFile)
        val afterCreate = keyFile.copyOf()

        // 解锁（走 `KdbxOpener.open` → `buildCredentialCandidates`）：数组必须原样保留。
        // （`Kdbx.verify` 就是同一个 `open` 的 `isSuccess`，所以这一条同时覆盖两条入口。）
        KdbxOpener.open(created.bytes, PASSWORD, keyFileBytes = keyFile)
        assertThat(keyFile).isEqualTo(afterCreate)

        // 而且**同一份字节连用两次**必须都成功 —— 这才是信封那条真实用法的形状。
        assertThat(KdbxOpener.open(created.bytes, PASSWORD, keyFileBytes = keyFile).isSuccess).isTrue()
        assertThat(KdbxOpener.open(created.bytes, PASSWORD, keyFileBytes = keyFile).isSuccess).isTrue()
    }

    @Test
    fun `诊断_新建不会改动调用方传进来的 keyfile 字节`() {
        // ★ 这条不是"顺手加的"：先前的 keyfile 用例就是**因为它红了**才查出问题。
        //   kotpass 0.13 的 changelog 里有一条 "Avoid mutating input key passed to
        //   AesKdf::transformKey" —— 说明这条链路上**确实出现过**把调用方的密钥缓冲
        //   改掉的行为。而 `parseKeyfile(32 字节) = 原样返回同一个数组引用`（上游源码），
        //   也就是说**我们传进去的数组会被 kotpass 直接持有**。
        //   ⇒ 一旦它在某处被清零，调用方手上那份 keyfile 就悄悄变成全 0：
        //     本次操作"看起来成功"，但**同一份字节再用就开不了库了**。
        //   本用例把"字节未被改动"这个前提钉住，避免以后升级 kotpass 时静默踩到。
        val keyFile = ByteArray(32) { index -> (index + 1).toByte() }
        val before = keyFile.copyOf()

        KdbxCreator.createEmpty(name = "我的库", password = PASSWORD, keyFileBytes = keyFile)

        assertThat(keyFile).isEqualTo(before)
    }

    @Test
    fun `新建的库确实是 KDBX 4_1`() {
        val created = KdbxCreator.createEmpty(name = "我的库", password = PASSWORD)

        val format = inspectKdbxFormat(created.bytes)

        assertThat(format).isInstanceOf(KdbxFormat.Supported::class.java)
        assertThat((format as KdbxFormat.Supported).version).isEqualTo(EXPECTED_VERSION)
    }

    @Test
    fun `新建的库用的是通用加密套件（AES-256 与 GZip 与 Argon2）`() {
        // ★ 这条是「能被 XC/DX 打开」的**本地代理判据**。
        //   往返成功说明不了套件对不对（自己写的自己当然读得回），
        //   唯一能在单测里钉住的是**文件头里的实测值**。
        val created = KdbxCreator.createEmpty(name = "我的库", password = PASSWORD)
        val decoded = decode(created.bytes, CREDENTIALS) as KeePassDatabase.Ver4x

        val header = decoded.header
        // ① cipher：必须是 AES（UUID 见 KeePass 规范；ChaCha20 在老版 XC/DX 上会打不开）。
        assertThat(header.cipherId).isEqualTo(AES_256_CIPHER_UUID)
        // ② 压缩：GZip（与 KeePassXC 默认一致；不压缩也合法，但保持一致少一个变量）。
        assertThat(header.compression.name).isEqualTo("GZip")
        // ③ 版本：4.1（连 minor 一起钉 —— "4.x" 不够，用户要的是 4.1）。
        assertThat(header.version.major.toInt()).isEqualTo(4)
        assertThat(header.version.minor.toInt()).isEqualTo(1)
        // ④ KDF：Argon2（不是 AesKdf / 不是无 KDF）。
        assertThat(header.kdfParameters)
            .isInstanceOf(app.keemobile.kotpass.database.header.KdfParameters.Argon2::class.java)
    }

    @Test
    fun `新建的库有一个空的回收站组`() {
        // 删除在 KDBX 里 = 移进回收站。库一开始就带回收站，
        // 第一次删除才不用"顺手改库结构"（施工单 W1 的 softDelete 依赖这个前提）。
        val created = KdbxCreator.createEmpty(name = "我的库", password = PASSWORD)

        val decoded = decode(created.bytes, CREDENTIALS)
        val meta = decoded.content.meta

        assertThat(meta.recycleBinEnabled).isTrue()
        assertThat(meta.recycleBinUuid).isNotNull()
        val recycleBin = decoded.content.group.groups.single { it.uuid == meta.recycleBinUuid }
        assertThat(recycleBin.name).isEqualTo(EXPECTED_RECYCLE_BIN_NAME)
        assertThat(recycleBin.entries).isEmpty()
    }

    @Test
    fun `新建的库内容为空且根组用库名`() {
        val created = KdbxCreator.createEmpty(name = "我的库", password = PASSWORD)

        val decoded = decode(created.bytes, CREDENTIALS)

        assertThat(decoded.content.group.entries).isEmpty()
        assertThat(decoded.content.group.name).isEqualTo("我的库")
        // 同一份库在两个工具里不该叫两个名字：根组名与 Meta.name 必须一致。
        assertThat(decoded.content.meta.name).isEqualTo("我的库")
        assertThat(decoded.content.meta.generator).isEqualTo(KdbxCreator.GENERATOR)
    }

    @Test
    fun `库名为空时退回默认根组名（KDBX 不允许空组名）`() {
        val created = KdbxCreator.createEmpty(name = "   ", password = PASSWORD)

        val decoded = decode(created.bytes, CREDENTIALS)

        assertThat(decoded.content.group.name).isEqualTo(KdbxCreator.DEFAULT_ROOT_NAME)
    }

    // ---------------------------------------------------------------- 只认 4.x（用户拍板的判决）

    @Test
    fun `三点一的老库被明确拒绝`() {
        // ★ 回归门禁：2026-09-30 之前**能读** 3.1，本轮按用户要求主动收窄。
        //   造样本用 kotpass 自己的 Ver3x（真 3.1 文件头），不是手搓字节。
        val threeOne = encodeLegacyThreeOne()

        // 先确认样本确实是 3.1 —— 否则这条测试会在"样本根本没造对"的情况下假绿。
        assertThat(inspectKdbxFormat(threeOne)).isEqualTo(KdbxFormat.UnsupportedVersion("3.1"))

        val opened = KdbxOpener.open(threeOne, PASSWORD, keyFileBytes = null)

        assertThat(opened.isFailure).isTrue()
        val error = errorKindOf(opened)
        assertThat(error).isInstanceOf(KdbxOpenError.UnsupportedVersion::class.java)
        assertThat((error as KdbxOpenError.UnsupportedVersion).version).isEqualTo("3.1")
    }

    @Test
    fun `拒绝文案指向 4x 而不是被拒的 3x`() {
        // ★ 这条守的是一个**已经发生过**的文案漂移：拒绝 3.1 的那天，
        //   另一处提示还写着「请用 KeePass 另存为 **3.1** / 4.x」——
        //   等于把用户引向一个刚被拒的格式。文案现在只有一份（挂在错误类型上）。
        val guidance = KdbxOpenError.UnsupportedVersion("3.1").guidance

        assertThat(guidance).contains("4.x")
        assertThat(guidance).contains("KeePassXC")
        // 「另存为 3.1」这类反向指引必须不存在。
        assertThat(guidance).doesNotContain("另存为 3.1")
        assertThat(guidance).doesNotContain("3.1 / 4.x")
    }

    // ---------------------------------------------------------------- helpers

    private fun errorKindOf(result: Result<*>): KdbxOpenError? =
        (result.exceptionOrNull() as? KdbxFailure)?.error

    private fun decode(bytes: ByteArray, credentials: Credentials): KeePassDatabase =
        java.io.ByteArrayInputStream(bytes).use { input ->
            KeePassDatabase.decode(
                inputStream = input,
                credentials = credentials,
                cipherProviders = KDBX_CIPHER_PROVIDERS,
            )
        }

    /**
     * 造一个**真的 KDBX 3.1** 字节串（用 kotpass 的 `Ver3x`，不是手搓文件头）。
     *
     * ⚠️ 必须走编码而不是"只改版本号字节"：真实 3.1 与 4.1 的差异不只是文件头，
     * 只改头会造出一个**不存在的中间态**，测出来的结论也就没有意义。
     */
    private fun encodeLegacyThreeOne(): ByteArray {
        val legacy = KeePassDatabase.Ver3x.create(
            rootName = "Legacy",
            meta = Meta(name = "Legacy"),
            credentials = CREDENTIALS,
        )
        val out = ByteArrayOutputStream()
        legacy.encode(outputStream = out, cipherProviders = KDBX_CIPHER_PROVIDERS)
        return out.toByteArray()
    }

    private companion object {
        const val PASSWORD = "master-pw-123"

        /** 期望的版本与根组名 —— 与 [KdbxFormat.SUPPORTED_MAJOR] / 用户要求同源。 */
        const val EXPECTED_VERSION = "4.1"

        /** kotpass `Group.createRecycleBin(Defaults.RecycleBinName)` 写死这个名字。 */
        const val EXPECTED_RECYCLE_BIN_NAME = "Recycle Bin"

        /**
         * AES-256 的 cipher UUID（KDBX 规范固定值 `31C1F2E6-BF71-4350-BE58-05216AFC5AFF`）。
         *
         * ⚠️ 硬编码是**故意**的：它是对**其它密码管理器**的接口，不是我们的内部约定。
         * 从 kotpass 常量读过来会让这条断言在"套件被换掉"时跟着变，那正好失去意义。
         */
        val AES_256_CIPHER_UUID: UUID = UUID.fromString("31c1f2e6-bf71-4350-be58-05216afc5aff")

        val CREDENTIALS: Credentials = Credentials.from(EncryptedValue.fromString(PASSWORD))
    }
}
