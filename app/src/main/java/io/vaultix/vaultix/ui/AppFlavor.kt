package io.vaultix.vaultix.ui

import io.vaultix.vaultix.BuildConfig

/**
 * 分发差异开关（flavor：full / offline）。
 *
 * M1 只有 Bitwarden 云库实现：offline 分发（仅 KDBX，M2 才支持）不展示
 * 「连接 Bitwarden」入口。UI 通过这里判断，编译期两侧代码都保留。
 */
object AppFlavor {
    val supportsBitwarden: Boolean = BuildConfig.FLAVOR == "full"
}
