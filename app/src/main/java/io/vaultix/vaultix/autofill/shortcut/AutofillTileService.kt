/*
 * Vaultix — app:autofill · shortcut
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * 快捷磁贴思路参考 Bastion `AutofillTileService`（GPL-3.0，Copyright 2025 JoyinJoester），
 * 本文件按 Vaultix 的组件与导航独立编写。
 */
package io.vaultix.vaultix.autofill.shortcut

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import io.vaultix.vaultix.R

/**
 * Quick Settings「快速填充」磁贴：点一下直接进条目搜索界面（复制密码后自动返回）。
 *
 * **不需要无障碍权限**（Bastion 文档把磁贴归到无障碍是误导，它只是起一个 Activity）。
 * 这是国产 ROM / 国产输入法环境下最稳的填充入口：不依赖系统 autofill 弹窗，
 * 也不依赖输入法的 inline suggestions 支持。
 *
 * ⚠️ 磁贴**必须由用户手动添加**到快捷设置面板（Android 13+ 可通过
 * `StatusBarManager.requestAddTileService` 弹系统确认框，见设置页入口）。
 * 且必须实现 [onStartListening] 刷新 tile 状态，否则部分 ROM（MIUI / ColorOS 等）
 * 上磁贴会显示为空白或「未激活」。
 */
class AutofillTileService : TileService() {

    /** 磁贴被用户添加：请求系统把我们加入「活跃磁贴」列表，保证 onClick 能被回调。 */
    override fun onTileAdded() {
        requestListeningState(this, ComponentName(this, AutofillTileService::class.java))
    }

    /**
     * 进入可见 / 可交互状态：立刻把标题、图标、状态写给系统。
     * 不写这一段磁贴可能只有空白方块（国产 ROM 尤其明显）。
     */
    override fun onStartListening() {
        requestListeningState(this, ComponentName(this, AutofillTileService::class.java))
        val tile = qsTile ?: return
        tile.state = Tile.STATE_INACTIVE
        tile.label = getString(R.string.tile_manual_fill)
        tile.contentDescription = getString(R.string.tile_manual_fill)
        tile.icon = Icon.createWithResource(this, R.drawable.ic_stat_lock)
        tile.updateTile()
    }

    override fun onStopListening() {
        // 无需持有资源：磁贴不可见时不查询任何数据
    }

    override fun onClick() {
        val pending = PendingIntent.getActivity(
            this,
            REQUEST_OPEN,
            fillIntent(),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(pending)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(fillIntent())
        }
    }

    private fun fillIntent() = Intent(this, ManualFillActivity::class.java)
        .setAction(Intent.ACTION_MAIN)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    private companion object {
        const val REQUEST_OPEN = 3001
    }
}
