/*
 * Vaultix — domain
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * **新建 KDBX 库**的领域入口（M2 阶段 B · 批次 W0）。
 *
 * ## 为什么不并进 `VaultRepository`
 *
 * 与 [KdbxSyncRepository] 同一条理由：`VaultRepositoryImpl` **正好 40 个函数**
 * （detekt `TooManyFunctions` 硬上限）。往那儿加一个方法 = 门禁立刻变红，
 * 而"把上限调高"是最差的处理（那个数是从 Docs/16 来的架构约定，不是随手定的）。
 *
 * ## 与本文件同目录那几个接口的分工
 *
 * | 接口 | 管什么 |
 * |---|---|
 * | `VaultRepository` | 库的**生命周期**：添加已有库 / 解锁 / 锁定 / 移除 |
 * | `KdbxSyncRepository` | 库的**内容与远端**之间的差异怎么收敛 |
 * | **本接口** | 库的**从无到有**：造一个空白库并让它进入列表 |
 *
 * ## ★ 为什么"新建"不能复用 `addKdbxVault`（哪怕签名看起来能凑）
 *
 * `addKdbxVault` 的语义是「**打开一个已存在的文件**并把它登记进列表」，所以它：
 * ① 先 `unlock`（用给定密码去**验证**一个既有的库）—— 新建时文件还不存在，无从验证；
 * ② 落库的 `origin` **就是** `sourceUri` —— 新建时 `sourceUri` 是 SAF 刚给的新文件
 *    URI，而它是否等于最终路径要**看 provider**（见 [NewKdbxVaultOutcome] 的说明）。
 *
 * 硬凑的结果是把"设定一个新密码"偷偷变成"验证一个旧密码"—— 失败时的表现是
 * 「新建库 → 提示密码错误」，而用户刚输的是**他自己设的**新密码。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.domain

/**
 * 新建一个空白 KDBX 库并登记进库列表。
 *
 * 实现见 `data:repository` 的 `KdbxCreateRepositoryImpl`。
 */
interface KdbxCreateRepository {

    /**
     * 造一个空白 KDBX 库、写到 [targetUri] 指向的文件，并把它登记为可用库。
     *
     * ## 成功之后库是**已解锁**的
     *
     * 这是刻意的，不是副作用（见 `data:kdbx` 的 `Kdbx.createVault` 的 KDoc）：
     * 刚建好的库明文就在手上，再让用户输一次刚设的密码纯属白付一次 Argon2；
     * 更要紧的是——**登记会话用的凭据必须与写进文件的那一组完全相同**，
     * 分两步做就多出一个"写的凭据 ≠ 读的凭据"的失败面，而那正是
     * 「自己建的库自己打不开」这种最伤人的 bug。
     *
     * ## 落库的 `displayName`
     *
     * 与 [VaultRepository.addKdbxVault] 一致取用户输入；空白时实现方用兜底名。
     * ⚠️ `displayName` 同时是**根组名**（写进文件、XC/DX 里也显示它），实现方
     * 不应在这里再做一次裁剪 —— 由 `data:kdbx` 负责（它才知道组名的合法边界）。
     *
     * @param targetUri 目标文件的 URI（SAF `content://`，来自
     *   `ActivityResultContracts.CreateDocument`）。⚠️ 调用方**必须先取得持久
     *   读写授权**，否则重启后这个库读不到 —— 那种失败发生在"第二天"，与此刻的
     *   代码路径相隔十万八千里，是排查起来最贵的一类。
     * @param displayName 库名（同时用作根组名）。
     * @param masterPassword 新库的主密码。⚠️ 这是**设定**不是验证 —— 实现方
     *   **不得**拿它去试图解锁任何既有库。
     * @param keyFileBytes 可选的 keyfile 原始字节（null = 该库不用密钥文件）。
     *   调用方读完即弃，实现方交给 `data:kdbx` 后不保留。
     */
    suspend fun createVault(
        targetUri: String,
        displayName: String,
        masterPassword: String,
        keyFileBytes: ByteArray? = null,
    ): NewKdbxVaultOutcome
}

/**
 * 新建 KDBX 库的结果。
 *
 * ## 为什么不复用 [KdbxAddOutcome]
 *
 * 那个类型有两个分支（`Added` / `Updated`）是为**重复打开同一文件**设计的
 * （`id` 就是文件 URI ⇒ 重复添加会覆盖同一行，列表零变化，必须向用户解释）。
 * 新建**不可能**是 `Updated`：目标文件刚刚由系统面板创建、此前在列表里不存在。
 * 复用它等于给调用方留一个永远进不去的分支，而"永远不进"的分支迟早会被误当成
 * "这条路真的不会发生"的证明。
 */
sealed interface NewKdbxVaultOutcome {

    /**
     * 库已创建、已落盘、已登记进列表（且已解锁）。
     *
     * @param vaultId 登记用的 id。⚠️ **只能是这个值**，调用方不要自己拼
     *   （比如拿请求时的 URI 去推）—— 见 [origin] 的说明。
     * @param origin 实际落库的 `VaultEntity.origin`。
     *   ★ 为什么它可能**不等于**调用方传进来的 `targetUri`：SAF 的部分文档提供方
     *   （Downloads 之类）会返回一个**临时** URI，最终路径另有一个。
     *   调用方若拿请求时的 URI 去推 vaultId，就会出现「库在列表里，却怎么点都打不开」
     *   —— 而且只在**那些 provider 上**复现。⇒ 一律以本字段为准。
     */
    data class Created(val vaultId: String, val origin: String) : NewKdbxVaultOutcome

    /**
     * 创建失败。
     *
     * @param result 失败原因，复用 [UnlockResult] 这一族（KDBX 侧的失败分类只有它一份，
     *   另造一套会让 UI 的 `when` 分支两处漂移）。
     *   常见：目标不可写、keyfile 读不到、格式参数被拒。
     */
    data class Failed(val result: UnlockResult) : NewKdbxVaultOutcome
}
