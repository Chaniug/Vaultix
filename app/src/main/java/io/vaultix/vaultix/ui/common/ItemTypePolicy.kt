/*
 * Vaultix — app:ui:common
 * Copyright (C) 2026 Vaultix contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * 条目类型的**库能力裁剪**（A3 · 2026-10-13）。
 *
 * ## 修的是什么
 *
 * 新建表单的类型筹码一行摆开 5 种（登录 / 银行卡 / 身份 / SSH / 安全笔记），
 * **不问当前库是哪种**。而 KDBX 侧的现实是：
 * - 读：`KdbxItemMapper.toVaultItem` 只产 `Login` / `SecureNote` 两种
 *   （推导规则 = 无用户名无密码有备注 ⇒ 安全笔记）；
 * - 写：`KdbxItemWriter` 对 Card / Identity / SshKey 的专属字段**静默丢弃**
 *   （没有对应字段区，也不报错）。
 *
 * ⇒ 在 KDBX 库里存一张"银行卡"，用户得到的是一个**类型是卡、字段全空**的残骸：
 * 看起来保存成功，详情页一片空白。比"不给建"更坏 —— 它浪费了用户的一次输入，
 * 还让他以为数据存上了。
 *
 * ⇒ 按库类型裁剪可选集。Bitwarden 侧全量五种（云端五类都有对应载荷）。
 *
 * ## 为什么是纯函数文件
 *
 * 裁剪逻辑要被 `ItemFormDialog`（UI）与卡包 Tab（新建入口显隐）共用，
 * 还要被单测钉住 ⇒ 放 `ui/common` 的纯逻辑区（同 [FormValues] / [ItemFilter] 的定位），
 * 不碰 Compose 运行时，JVM 单测直接跑。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.vaultix.ui.common

import io.vaultix.model.VaultItemType
import io.vaultix.model.VaultKind

/**
 * 该库**可选**的条目类型（顺序即表单里的展示顺序，也是"当前类型不在表内"时的兜底顺序）。
 *
 * @param kind 当前库类型；null = 尚未解析出来（库摘要还在加载）。
 *   按**不缩小**处理（返回全量）—— 与"未知类型不映射到 Login"同向：
 *   宁可多给一个选项，也不在信息不足时替用户做缩小决定。
 */
fun selectableItemTypes(kind: VaultKind?): List<VaultItemType> = when (kind) {
    // ★ 11-07：KDBX 侧的类型闸门已解除。
    //    A3 当初裁掉 Card/Identity/SSH 是因为 mapper 只映射登录字段；但 11-01 之后
    //    `KdbxItemWriter.applyPayloads` 已把这三类载荷写成 `card_*` / `identity_*` /
    //    `VPX_SSH_*` 自定义字段（其中 `card_*` 正是 KeePassXC 的 Bitwarden 导入器
    //    用的生态标准键名），`KdbxItemMapper.toVaultCard/Identity/SshKey` 双向都认，
    //    卡号与 CVV 还按 `EntryValue.Encrypted` 保护。
    //    ⇒ 再裁就是在数据层已经保真的前提下，白白不让用户建卡。
    //    KeePassXC 没有原生「银行卡」类型，这些字段会显示在条目的「高级」页 ——
    //    形式不同但数据不丢，符合用户「手机上打通即可，电脑上不管形式」的要求。
    VaultKind.KDBX, VaultKind.BITWARDEN, null -> VaultItemType.entries.toList()
}

/**
 * 该库是否支持**银行卡**条目（卡包 Tab 的新建入口据此显隐）。
 *
 * 由 [selectableItemTypes] 派生而不是另写一个布尔 —— 两个判断必须同源，
 * 否则会出现"表单里能选卡、卡包却不让建"这种自相矛盾的门（各自漂移的产物）。
 */
fun supportsCardEntries(kind: VaultKind?): Boolean =
    VaultItemType.Card in selectableItemTypes(kind)
