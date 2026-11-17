/*
 * Vaultix — app:test · settings
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * 自定义自动锁定分钟数 → 显示单位 的**分支顺序**门禁（#300，2026-11-17）。
 *
 * ## 钉死的是什么
 *
 * 1440 = 24 × 60：**任何整天的分钟数必然也是整小时**。
 * 所以「判天」必须写在「判小时」之前；一旦顺序写反，`auto_lock_day_fmt` 就成了死资源
 * —— 用户填 1440（1 天）看到「24 小时」、2880（2 天）看到「48 小时」，不崩、不报错，
 * 只是永远显示不出「天」。这类 bug `detekt` 查不到、编译也不报，只有穷举边界才露馅。
 *
 * ## 为什么抽纯函数测，而不是测 Composable
 *
 * `vaultTimeoutLabel` 是 `@Composable`，JVM 单测里起不来；而"该显示什么单位"是
 * 一个**纯整数判定**。把判定权交给 [customTimeoutUnit]，测试直接钉纯函数即可，
 * 无需 Compose test rule，也就不依赖 SDK / instrumentation。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.vaultix.ui.settings

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CustomTimeoutUnitTest {

    @Test
    fun `整天的分钟数必须判为天而不是小时`() {
        // 这是本轮 bug 的精确复现：旧顺序下 1440 会命中「小时」分支 ⇒ 24 小时。
        assertThat(customTimeoutUnit(AutoLockPresets.MINUTES_PER_DAY))
            .isEqualTo(AutoLockUnit.DAY)
        assertThat(customTimeoutUnit(AutoLockPresets.MINUTES_PER_DAY * 2))
            .isEqualTo(AutoLockUnit.DAY)
    }

    @Test
    fun `整小时但非整天的分钟数判为小时`() {
        assertThat(customTimeoutUnit(AutoLockPresets.MINUTES_PER_HOUR))
            .isEqualTo(AutoLockUnit.HOUR)
        assertThat(customTimeoutUnit(AutoLockPresets.MINUTES_PER_HOUR * 23))
            .isEqualTo(AutoLockUnit.HOUR) // 23h 不是整天，不能被「天」吞掉
    }

    @Test
    fun `非整小时的分钟数判为分钟`() {
        assertThat(customTimeoutUnit(1)).isEqualTo(AutoLockUnit.MINUTE)
        assertThat(customTimeoutUnit(90)).isEqualTo(AutoLockUnit.MINUTE)
        assertThat(customTimeoutUnit(AutoLockPresets.MINUTES_PER_DAY + 1))
            .isEqualTo(AutoLockUnit.MINUTE)
    }

    @Test
    fun `自定义上限内所有整天值都判为天`() {
        // 上限 100000 分钟 ≈ 69 天。遍历到 69 天，确认「天」分支在上限内全程可达。
        val maxDays = AutoLockPresets.MAX_CUSTOM_MINUTES / AutoLockPresets.MINUTES_PER_DAY
        (1..maxDays).forEach { days ->
            assertThat(customTimeoutUnit(days * AutoLockPresets.MINUTES_PER_DAY))
                .isEqualTo(AutoLockUnit.DAY)
        }
    }
}
