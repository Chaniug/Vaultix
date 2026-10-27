plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "io.vaultix.domain"
    compileSdk = 37
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// AGP 9 内置 Kotlin：改用顶层 kotlin {} 配置编译器（kotlinOptions 已废弃）
kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    implementation(projects.core.model)
    implementation(libs.kotlinx.coroutines.android)

    // 本模块此前没有测试目录；FolderNamePolicy（纯函数）是第一个 —— 校验规则放在
    // domain 是因为仓储与 UI 都要用它，放在任一侧都会让另一侧重复实现一遍。
    testImplementation(libs.junit)
    testImplementation(libs.truth)
}
