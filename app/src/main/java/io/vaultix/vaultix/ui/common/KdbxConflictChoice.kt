/*
 * Vaultix — app / UI 通用
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * **网盘冲突的三个处理选项**。
 *
 * ## 为什么单独一个文件（而不是塞进 `KdbxConflictDialog.kt`）
 *
 * 两个原因，任一条都够：
 * 1. detekt `MatchingDeclarationName`：一个文件里若只有**一个**顶层声明，文件名
 *    必须与它同名。把枚举与 Composable 放一起时，"文件里的单个顶层声明"就成了
 *    `KdbxConflictChoice`（枚举在前），报错。
 * 2. 更实质的：**ViewModel 依赖这个枚举，但不依赖那个 Composable**。
 *    拆开后 `VaultListViewModel` 只 import 这个文件 —— UI 层怎么画对话框
 *    （换主题、改文案、换组件）不会牵连到 ViewModel 的编译单元。
 *
 * ## ★ 没有第四个选项
 *
 * **刻意不提供「合并」**：三方合并是第二期（方案 §8 方案 A）。现在给一个
 * "两边都保留"的按钮，用户会以为点了就都保住了 —— 而实现不出来时只能
 * 假装成功或悄悄丢一边，那比**不给这个按钮**恶劣得多。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.vaultix.ui.common

/**
 * 冲突处理选项。
 *
 * ⚠️ 三个选项的**代价并不对称地"都很小"**：前两个各自会**永久丢弃**一边的改动。
 * 所以对话框里每个选项都必须写明后果（见 `KdbxConflictDialog`），
 * 且**不给任何一个标红** —— 两个覆盖的代价是对称的，把其中一个标成"危险"
 * 会诱导用户去点另一个，而那个一样会丢数据。
 */
enum class KdbxConflictChoice {
    /** 用本地覆盖远端 —— 远端那份改动会丢。 */
    KeepLocalUpload,

    /** 用远端覆盖本地 —— 本地那份改动会丢。 */
    KeepRemoteDownload,

    /** 稍后再决定（不做任何 IO，状态停在冲突）。 */
    DecideLater,
}
