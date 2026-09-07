package com.bastion.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * 获取 **Activity 级**共享的 ViewModel，而非 `viewModel()` 默认的 NavBackStackEntry 级。
 *
 * 为什么需要它：Navigation Compose 会为每个 destination 提供独立的 `ViewModelStoreOwner`。
 * 因此在子页面里直接写 `viewModel()`，拿不到 Activity 级那个实例，而是**新建一个** ——
 * 表现为同一个 ViewModel 被创建多次，每个实例各跑一遍 `init` 里的加载逻辑，且各持一份
 * 互不相干的状态副本。
 *
 * 实测（2026-09-06）：`BitwardenViewModel` 冷启动存在两个实例，各跑一次 `loadVaults()`
 * （476ms + 432ms），且两套 `_vaults` / `_unlockState` / `syncStatus` 可能不一致。
 *
 * 这只是**过渡方案**。等 `SimpleMainScreen` 拆分完成、改为「单一创建点 + 逐层显式传参」
 * 之后，本函数即可退役——那时依赖更明确，也不依赖隐式 owner。在此之前它是唯一决策点，
 * 切换策略只改这一处。
 */
@Composable
inline fun <reified VM : ViewModel> activityViewModel(): VM {
    val owner = LocalContext.current as? ViewModelStoreOwner
        ?: error("activityViewModel() 必须在 Activity 上下文中使用（当前 context 不是 ViewModelStoreOwner）")
    return viewModel(viewModelStoreOwner = owner)
}
