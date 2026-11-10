/*
 * Vaultix — app:ui · common
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * 一句话：**「关联应用」是给人看的，不该显示 `com.tdx.AndroidNew` 这种机器标识**。
 *
 * ## 名字从哪来（优先级，2026-11-10 定案）
 *
 * 1. 条目自定义字段里的应用名 —— KDBX 从 KeePassDX / Bastion 迁过来的条目常带
 *    `appName` / `bastion_app_name` / `App Name`，里面就是用户写的「通达信」。
 *    **零权限、零 IO、离线可用**，且不依赖目标 App 是否装在这台机器上。
 * 2. `PackageManager` 的 application label —— 字段没写、但应用装着时能拿到中文名。
 *    ⚠️ Android 11+ 包可见性：读不到会抛 `NameNotFoundException`，必须 `runCatching`。
 * 3. 包名 —— 前两条都不成立时的诚实回退（**仍然显示**，只是降级成副标题）。
 *
 * ⚠️ 为什么不能只做第 2 条：详情页 [AppRow] 的旧注释写的正是「读另一个 App 的
 *    label 代价过高，包名本身就足够定位」。但真机截图证明这条结论不对 ——
 *    用户看不懂包名，而且「已安装 · 自动填充会匹配到此应用」那种解释性副标题
 *    越写越长，也没把「这是哪个 App」说清楚。**有中文名就该用中文名**。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.vaultix.ui.common

import android.content.Context
import io.vaultix.model.VaultCustomField
import java.util.Locale

/**
 * 「关联应用」显示名解析。
 *
 * 纯 Kotlin 部分（[fromCustomFields]）不碰 Android，可直接在 JVM 单测里钉住别名表；
 * [installedLabel] 才需要 [Context]。
 */
object AppLabel {

    /**
     * 应用名字段的别名（**归一化后**比较）。
     *
     * [normalize] 会去掉下划线/连字符/空格并小写，所以这里只需写一种形态：
     * `appName` / `App Name` / `APP_NAME` / `BastionAppName` / `bastion_app_name` 全覆盖。
     */
    private val NAME_KEYS = setOf(
        "appname",
        "bastionappname",
        "applicationname",
        "androidappname",
    )

    /** 包名字段的别名；用于「多条应用 URI 时别把 A 的名字安到 B 头上」。 */
    private val PACKAGE_KEYS = setOf(
        "apppackagename",
        "bastionapppackage",
        "bastionapppackagename",
        "androidapppackagename",
        "packagename",
        "kp2aapp",
    )

    /**
     * 从自定义字段里找「这个包名对应的应用名」。找不到返回 `null`。
     *
     * ## 为什么要看包名字段
     *
     * 一个条目可能绑了多个 App，而自定义字段是**扁平的键值对**：若字段里明明写着
     * `bastion_app_package = com.other.app`，却把 `appName = 微信` 显示到当前这条
     * `com.tdx.AndroidNew` 上，就是**把身份安错了**。⇒ 有包名字段时，
     * 必须其中一条等于 [packageName] 才认；没有包名字段（最常见：只有一个应用、
     * 字段里只有 `appName`）则直接用。
     *
     * ⚠️ 值长得像包名的一律不认：有的导入器会把包名误写进 `appName`。
     */
    fun fromCustomFields(fields: List<VaultCustomField>, packageName: String): String? {
        val packageFields = fields.filter { normalize(it.name) in PACKAGE_KEYS }
        if (packageFields.isNotEmpty() && packageFields.none { it.value.trim() == packageName }) {
            return null
        }
        return fields.firstOrNull { field ->
            normalize(field.name) in NAME_KEYS &&
                field.value.isNotBlank() &&
                !looksLikePackage(field.value)
        }?.value?.trim()
    }

    /**
     * 已安装应用的显示名；读不到（未安装 / 包不可见 / label 就是包名）返回 `null`。
     *
     * ⚠️ 刻意同步调用：只在 Composition 里按 `remember(packageName, …)` 跑一次，
     *    不为此开协程 —— 列表里每行都 launch 一次比一次 `getApplicationInfo` 更贵。
     */
    fun installedLabel(context: Context, packageName: String): String? = runCatching {
        val pm = context.packageManager
        val info = pm.getApplicationInfo(packageName, 0)
        val label = pm.getApplicationLabel(info).toString().trim()
        label.takeIf { it.isNotBlank() && it != packageName && !looksLikePackage(label) }
    }.getOrNull()

    /** 键名归一：小写 + 去掉 `_` / `-` / 空格（不动中文，本项目暂无中文键名）。 */
    private fun normalize(key: String): String =
        key.lowercase(Locale.ROOT).replace("_", "").replace("-", "").replace(" ", "")

    /** 形如 `com.foo.bar` 的值不是给人看的名字。 */
    private fun looksLikePackage(value: String): Boolean {
        val v = value.trim()
        return v.contains('.') && !v.contains(' ') && v.all { it.isLetterOrDigit() || it == '.' || it == '_' }
    }
}
