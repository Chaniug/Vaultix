/*
 * Vaultix — app:security
 * Copyright (C) 2026 Vaultix contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * 备份面契约的静态断言（2026-10-26，`.ai/ISSUES.md` #186）。
 *
 * ## 为什么用"解析 XML 文件"而不是 Robolectric 跑 ApplicationInfo
 *
 * 这条缺陷的**全部**内容就是"几个 XML 属性写成了什么"，没有运行期行为可言。
 * 用 Robolectric 起一个 ApplicationInfo 要引入整套 runtimes 且慢；直接解析
 * AndroidManifest.xml 与两份规则文件是毫秒级、零依赖、零 flaky，
 * 且报错信息能精确指到"哪个 domain 没排除"。
 *
 * 单测拿到的是**源** manifest（app/src/main/AndroidManifest.xml），不是 AGP 合并产物——
 * 这恰好是我们要钉的东西：allowBackup 只可能被我们自己写错，不存在被依赖库合并覆盖的情形
 * （那需要 tools:replace，本项目没有）。
 *
 * ## 三条断言分别防什么
 *
 * 1. allowBackup 不是 true —— 防"有人改回 true 图省事"。这是主目标：一旦改回，
 *    全部门锁信封 / WebDAV 凭据会随用户云备份离开设备。
 * 2. 排除规则必须同时覆盖 cloud-backup 与 device-transfer —— 防"只排一半"。
 *    密码管理器最典型的翻车方式：换机时信封被搬过去，看起来能用，实际解不开。
 * 3. sharedpref 的路径不能带 .xml 后缀 —— 这是 Android 备份规则的经典坑：
 *    写成 vaultix_secure.xml 会静默不匹配（不报错、不警告，就是不生效）。
 */
package io.vaultix.vaultix.security

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupPolicyConfigTest {

    /** 源码根（单测工作目录是模块根 app/）。 */
    private val appDir: File = File("src/main")

    private val manifest: String by lazy { File(appDir, "AndroidManifest.xml").readText() }

    /** 取 application 起始标签的完整文本（属性都在这一段）。 */
    private fun applicationTag(): String {
        val start = manifest.indexOf("<application")
        assertTrue("AndroidManifest.xml 里找不到 application 标签", start >= 0)
        val end = manifest.indexOf('>', start)
        assertTrue("application 起始标签未闭合", end > start)
        return manifest.substring(start, end + 1)
    }

    private fun xmlRuleFile(name: String): String = File(appDir, "res/xml/$name").readText()

    /** 取某个标签包起来的整段文本。 */
    private fun section(xml: String, tagName: String): String {
        val open = xml.indexOf("<$tagName>")
        val close = xml.indexOf("</$tagName>")
        assertTrue("data_extraction_rules.xml 缺少 $tagName 段", open >= 0 && close > open)
        return xml.substring(open, close)
    }

    @Test
    fun `系统备份必须处于关闭状态`() {
        val tag = applicationTag()
        assertTrue(
            "#186：android:allowBackup 必须是 false。当前 application 标签为：\n" +
                "$tag\n" +
                "理由：vaultix_secure.xml 里的门锁信封由 Keystore 密钥包裹，而 Keystore 不参与备份，"
                + "被备份出去只会得到一份永远解不开的密文，并让用户误以为门锁还在。",
            tag.contains("android:allowBackup=\"false\""),
        )
    }

    @Test
    fun `必须同时声明两套排除规则资源`() {
        val tag = applicationTag()
        assertTrue(
            "缺少 android:fullBackupContent（Android 11 及以下的排除规则入口）",
            tag.contains("android:fullBackupContent=\"@xml/backup_rules\""),
        )
        assertTrue(
            "缺少 android:dataExtractionRules（Android 12+ 的排除规则入口）",
            tag.contains("android:dataExtractionRules=\"@xml/data_extraction_rules\""),
        )
    }

    @Test
    fun `敏感目录在云备份与换机迁移两条通路上都被排除`() {
        val rules = xmlRuleFile("data_extraction_rules.xml")
        val cloudBackup = section(rules, "cloud-backup")
        val deviceTransfer = section(rules, "device-transfer")

        SENSITIVE_RULES.forEach { (domain, path) ->
            assertTrue(
                "cloud-backup 缺少排除：domain=$domain path=$path",
                cloudBackup.contains("<exclude domain=\"$domain\" path=\"$path\""),
            )
            assertTrue(
                "device-transfer 缺少排除：domain=$domain path=$path —— "
                    + "只排 cloud-backup 等于没排：换机时信封照样被搬过去",
                deviceTransfer.contains("<exclude domain=\"$domain\" path=\"$path\""),
            )
        }
    }

    @Test
    fun `Android11 及以下的备份规则同步排除同一批路径`() {
        val legacy = xmlRuleFile("backup_rules.xml")
        SENSITIVE_RULES.forEach { (domain, path) ->
            assertTrue(
                "backup_rules.xml 缺少排除：domain=$domain path=$path",
                legacy.contains("<exclude domain=\"$domain\" path=\"$path\""),
            )
        }
    }

    @Test
    fun `sharedpref 路径不带 xml 后缀`() {
        val all = xmlRuleFile("backup_rules.xml") + xmlRuleFile("data_extraction_rules.xml")
        assertFalse(
            "sharedpref 域名的 path 不能带 .xml 后缀 —— Android 会静默不匹配（规则形同虚设）",
            all.contains("path=\"vaultix_secure.xml\""),
        )
        // 反向确认：不带后缀的那条确实存在（防止"两条都没写"也算通过）。
        assertTrue(
            "缺少 sharedpref/vaultix_secure 排除",
            all.contains("path=\"vaultix_secure\""),
        )
    }

    private companion object {
        /**
         * 必须排除的 (domain, path) 全表。
         *
         * 新增任何"落在应用私有目录里的秘密"都要加进这张表，并同步两份 XML。
         * 目录与理由的对应关系见 backup_rules.xml 顶部的表格。
         */
        val SENSITIVE_RULES = listOf(
            "sharedpref" to "vaultix_secure",
            "database" to "vaultix.db",
            "file" to ".",
            "cache" to ".",
        )
    }
}
