package com.bastion.app.bitwarden.sync

fun VaultSyncStatus?.isUserVisibleSyncInProgress(): Boolean {
    val current = this ?: return false
    // 静默同步（自动触发，非手动）不打扰用户：本地离线数据已秒开渲染，
    // 同步在后台进行，顶部/卡片不显示"同步中"转圈；仅手动同步显示进度
    return (current.isRunning || current.queuedReason != null) && !current.isSilent
}
