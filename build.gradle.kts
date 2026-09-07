// 根工程只声明插件，具体配置在各模块中完成。
// 参考文档：Docs/11-工程规范与构建体系.md
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    // ⚠️ AGP 9 起内置 Kotlin，禁止声明或应用 kotlin-android
    //    （会与内置 kotlin 扩展冲突：Cannot add extension with name 'kotlin'）。
    // kotlin-jvm 仅把 KGP 2.3.21 带上 classpath，覆盖 AGP 内置的 2.2.10，
    // 实现多模块统一 Kotlin 版本；任何模块都不得 apply 它。
    alias(libs.plugins.kgp) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ksp) apply false
    // 覆盖率：仅 core:crypto 应用（M0 只有该模块有单元测试与覆盖率门禁）
    alias(libs.plugins.kover) apply false
    // Detekt（P1 质量门禁）：由下方 subprojects 统一为所有 Android 模块开启
    alias(libs.plugins.detekt) apply false
}

// ---- Detekt 统一门禁（P1）----
// 对所有 Android 模块（app / core:* / data:* / domain）应用 detekt：
// 阈值见 config/detekt/detekt.yml（对齐 Docs/16 硬上限）。
// 2.0 插件按 source set / compilation 自动注册类型解析任务（含 AGP 内置 Kotlin）。
import dev.detekt.gradle.extensions.DetektExtension

subprojects {
    plugins.withId("com.android.application") { enableVaultixDetekt() }
    plugins.withId("com.android.library") { enableVaultixDetekt() }
}

fun Project.enableVaultixDetekt() {
    pluginManager.apply("dev.detekt")
    extensions.configure<DetektExtension>("detekt") {
        config.setFrom(rootProject.file("config/detekt/detekt.yml"))
        // 在 detekt 内置默认配置之上叠加我们的阈值（未提及的规则保持默认）
        buildUponDefaultConfig.set(true)
    }
}
