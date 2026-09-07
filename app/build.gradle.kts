import com.android.build.api.dsl.ApplicationExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    // 注意：AGP 9 起内置 Kotlin，不再 apply kotlin-android（会与内置扩展冲突）
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "io.vaultix.vaultix"
    // 本机 SDK 已安装 android-37（无 android-36），且与 Bastion 对齐
    compileSdk = 37

    defaultConfig {
        applicationId = "io.vaultix.vaultix"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        // CI 通过 -PversionName 注入版本号（如 0.1.0-dev-abc1234），本地默认 0.1.0
        versionName = providers.gradleProperty("versionName").getOrElse("0.1.0")
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
    implementation(libs.kotlinx.datetime)

    // 平台能力
    implementation(libs.androidx.biometric)
    implementation(libs.coil.compose)

    // 测试
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.turbine)
    testImplementation(libs.mockk)
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
