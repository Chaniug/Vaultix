plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "io.vaultix.data.repository"
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
    implementation(projects.core.common)
    implementation(projects.core.crypto)
    implementation(projects.core.database)
    implementation(projects.core.datastore)
    implementation(projects.domain)
    implementation(projects.data.bitwarden)
    // KDBX 本地库引擎（M2）：门面 `io.vaultix.data.kdbx.Kdbx`
    implementation(projects.data.kdbx)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    // HttpException 分类（401 / 400 two_factor）需要 retrofit 类型
    implementation(libs.retrofit.core)
    // ★ WebDAV 条件写（`If-Match` / `If-None-Match: *`）必须手写 OkHttp：
    //   现成的 sardine-android 0.8 不支持条件请求头，用它等于放弃 TOCTOU 防护。
    implementation(libs.okhttp)

    // ⚠️ `compileOnly` **不是笔误**：本模块只**声明** okio 版本（见下），产物里不落 jar。
    //   运行时的 okio 由 app 侧（okhttp / coil 传递而来）唯一提供一份。
    compileOnly(libs.okio)

    // 测试
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.turbine)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    // 单测里真的要构造 OkHttpClient + 跑请求（WebDAV 条件写那些用例），
    // 只有 `compileOnly(libs.okio)` 不够。
    testImplementation(libs.okhttp)
    testImplementation(libs.okio)
}
