/*
 * Vaultix — app:ui:common
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * M3 Expressive（2026）**波浪进度指示器**的唯一封装点。
 *
 * 为什么封装而不是在各处直接调用：
 *   1. `Circular/LinearWavyProgressIndicator` 带 `@ExperimentalMaterial3ExpressiveApi`，
 *      调用方都要写 `@OptIn`。集中到本文件后，`@OptIn` 只出现两次，.alpha 改签名时
 *      也只需改这里一处。
 *   2. **回退成本可控**：若真机验收认为波浪不合适，把这两个函数的实现换回
 *      `CircularProgressIndicator` / `LinearProgressIndicator` 即可，9 个调用点一行不用动。
 *
 * ⚠️ 尺寸是**写死**的（解 `material3-1.5.0-alpha16` 源码实测，不是推测）：
 *   - `CircularWavyProgressIndicator` 内部 `.size(48.dp)`（`CircularProgressIndicatorTokens.WaveSize`）；
 *   - `LinearWavyProgressIndicator` 内部 `.size(width = 240.dp, height = 10.dp)`
 *     （`LinearContainerWidth = 240.dp` / `LinearProgressIndicatorTokens.WaveHeight`）。
 *   ⇒ **传 `Modifier.fillMaxWidth()` 或 `Modifier.size(18.dp)` 都不会改变最终尺寸**，
 *     modifier 只作用于外层的 Box/Spacer，内部那次 `.size()` 覆盖了宽度约束。
 *
 *   老组件其实同样是写死的（`LinearIndicatorWidth = 240.dp`、`CircularIndicatorDiameter = 40.dp`），
 *   所以两处尺寸差异只有：
 *   - Linear：宽 240dp **不变**，高 4dp → 10dp（波浪需要垂直空间，不可避免）；
 *   - Circular：40dp → 48dp（+8dp）。
 *
 * 因此**以下场景不能用本封装**（仍用原组件）：
 *   - 18dp / 20dp 的内联小转圈（按钮里、文案旁）—— 换成波浪会被撑到 48dp，破坏布局；
 *   - 需要**精确读数**的确定态进度 —— 密码强度条（`ItemFormDialog`，靠 `weight(1f)` 撑宽，
 *     换波浪会变成固定 240dp）与 TOTP 倒计时（`UnifiedProgressBar`）；
 *   - 任何需要自定义尺寸的场合。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.vaultix.ui.common

import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * 圆形**波浪**等待指示器（不确定态），替代老的无参 `CircularProgressIndicator()`。
 *
 * 尺寸固定 48dp，调用方传的 [modifier] 只能影响定位（如 `align`），不能改大小。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun VaultixWavyProgress(modifier: Modifier = Modifier) {
    CircularWavyProgressIndicator(modifier = modifier)
}

/**
 * 线性**波浪**进度条（不确定态），替代老的 `LinearProgressIndicator(modifier = Modifier.fillMaxWidth())`。
 *
 * 宽度固定 240dp（与老组件一致，所以 `fillMaxWidth()` 传不传都一样），高度 10dp。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun VaultixWavyProgressBar(modifier: Modifier = Modifier) {
    LinearWavyProgressIndicator(modifier = modifier)
}
