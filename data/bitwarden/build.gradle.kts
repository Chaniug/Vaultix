plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "io.vaultix.data.bitwarden"
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
    // 依赖 core 层（不得反向依赖 domain / app）
    implementation(projects.core.model)
    implementation(projects.core.common)
    implementation(projects.core.crypto)
    implementation(projects.core.datastore)
    implementation(projects.core.database)

    // 网络层
    implementation(libs.retrofit.core)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging.interceptor)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    // 依赖注入
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // 测试（DTO 容错、解析双形态）
    testImplementation(libs.junit)
    testImplementation(libs.truth)
}
