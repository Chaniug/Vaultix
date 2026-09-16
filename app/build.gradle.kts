import com.android.build.api.dsl.ApplicationExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    // 注意：AGP 9 起内置 Kotlin，不再 apply kotlin-android（会与内置扩展冲突）
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

/**
 * 版本名的**单一来源**（versionName 与 versionCode 都由它派生，杜绝两处各写一遍）：
 * ① CI 注入的 `-PversionName`（如 `0.3.0-dev-abc1234`）；② 否则读仓库根 `VERSION` 文件。
 */
val vaultixVersionName: String = providers.gradleProperty("versionName").getOrElse(
    rootProject.file("VERSION").takeIf { it.exists() }?.readText()?.trim()?.ifEmpty { null }
        ?: "0.1.0",
)

/**
 * 由版本名推导 `versionCode`：`X.Y.Z[-任意后缀]` → `X*1_000_000 + Y*1_000 + Z`。
 *
 * ⚠️ 此前是硬编码 `1`，带来两个问题（2026-09-14 用户报告「版本显示里有个 (1)」）：
 *   ① 设置页在版本号后拼 `($versionCode)`，于是**永远显示「(1)」** —— 它长得像浏览器
 *      给重复下载加的后缀，用户很自然地以为两者有关，其实毫无关系（那个后缀是下载器加的）；
 *   ② `versionCode` 是 Android 判断新旧、决定能否覆盖安装的**硬性依据**（同码可覆盖、
 *      低码被拒），恒为 1 等于这层信息完全失效，也是将来上架的前提条件。
 *
 * `X*1_000_000` 给 minor / patch 各留三位，<1000 都不会串位。
 * 解析不出 `X.Y` 时**回退 1 而不抛异常**：版本号写法出错不该让整个构建起不来。
 */
fun versionCodeOf(name: String): Int {
    val parts = name.substringBefore('-').trim().split('.')
    val major = parts.getOrNull(0)?.toIntOrNull() ?: return 1
    val minor = parts.getOrNull(1)?.toIntOrNull() ?: return 1
    val patch = parts.getOrNull(2)?.toIntOrNull() ?: 0
    if (major < 0 || minor < 0 || patch < 0) return 1
    return major * 1_000_000 + minor * 1_000 + patch
}

android {
    namespace = "io.vaultix.vaultix"
    // 本机 SDK 已安装 android-37（无 android-36），且与 Bastion 对齐
    compileSdk = 37

    defaultConfig {
        applicationId = "io.vaultix.vaultix"
        minSdk = 26
        targetSdk = 37
        versionCode = versionCodeOf(vaultixVersionName)
        // 版本号来源优先级：
        //   ① CI 注入的 -PversionName（如 0.3.0-dev-abc1234），用于发布产物；
        //   ② 否则读仓库根的 VERSION 文件（真源），保证「本地构建 / 设置页显示 / VERSION
        //      文件」三处同源——此前本地默认硬编码 0.1.0，与 VERSION 的 0.3.0 长期不一致，
        //      本地装机后设置页会显示过期版本号，排查问题时极易误判手上装的是哪一版。
        //      ⚠️ 2026-09-14：CI 的 debug 包也曾硬编码 0.1.0-dev-<sha>，等于在这条路上
        //      又破了一次同源（见 ci-debug.yml，已改为读 VERSION）。
        versionName = vaultixVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }

        // 仅构建 arm64-v8a 安装包：Vaultix 只面向现代 64 位设备（Android 17 / API 36 为主），
        // 不再产出 armeabi-v7a / x86 / x86_64，既缩小体积又缩短构建时间。
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            // 注意：不要加 applicationIdSuffix，保证 debug 与 release 可互相覆盖安装
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 签名由 CI 通过 -Pandroid.injected.signing.* 全局注入；本地 release 需自行配置
        }
    }

    // 分发维度：full（含 Bitwarden 网络同步）/ offline（仅 KDBX 本地）
    flavorDimensions += "distribution"
    productFlavors {
        create("full") {
            dimension = "distribution"
            // 默认即带 INTERNET 权限（见 src/full/AndroidManifest.xml）
        }
        create("offline") {
            dimension = "distribution"
            // 仅 KDBX 本地库：不申请 INTERNET 权限（见构建体系文档）
            manifestPlaceholders["internetPermission"] = ""
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // Compose（版本由 Compose BOM 统一管理）
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // 依赖注入
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    // 存储 / 加密 / 异步
    implementation(libs.androidx.datastore)
    implementation(libs.androidx.security.crypto)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.collections.immutable)
    implementation(libs.kotlinx.serialization.json)
    // 填充辅助规则表的拉取（公开 GitHub 资产，与 Bitwarden 同源）。
    implementation(libs.okhttp)
    implementation(libs.kotlinx.datetime)

    // OneDrive / Microsoft Graph —— KDBX 网盘同步的鉴权层（MSAL public client + PKCE）。
    implementation(libs.msal)

    // 平台能力
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.credentials)
    implementation(libs.coil.compose)

    // 扫码（TOTP 相机扫码）：CameraX 取流 + ZXing 解 QR
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.zxing.core)

    // ---- 项目模块：UI 只依赖 domain 接口 + data:repository 实现 ----
    implementation(projects.core.model)
    implementation(projects.core.common)
    implementation(projects.core.ui)
    implementation(projects.core.datastore)
    implementation(projects.domain)
    implementation(projects.data.repository)

    // 测试
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.turbine)
    testImplementation(libs.mockk)
    // ViewModel / Flow 单测：Main 调度器替换（runTest + UnconfinedTestDispatcher）
    testImplementation(libs.kotlinx.coroutines.test)
    // Compose BOM 必须同时声明给 androidTest 配置：ui-test-junit4 等依赖本身不带版本号
    // （在 catalog 中无 version），仅 implementation(platform(bom)) 不会传递给 androidTest，
    // 否则解析时版本为空（表现为 "Could not find androidx.compose.ui:ui-test-junit4:."）。
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.hilt.android.testing)
    kspAndroidTest(libs.hilt.compiler)
}

// AGP 9 内置 Kotlin：编译器选项改在顶层 kotlin {} 块配置（kotlinOptions 已废弃）。
// Kotlin 2.2+ 起 jvmTarget 必须用 JvmTarget 枚举，不再接受 Gradle 的 JavaVersion。
kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}
