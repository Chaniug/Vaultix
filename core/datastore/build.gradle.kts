plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "io.vaultix.datastore"
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
    implementation(libs.androidx.datastore)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // ---- 单元测试（src/test）----
    // ⚠️ 2026-10-10：`libs.androidx.datastore` 在下面**重复声明了两次**，这不是笔误。
    //   - 上面的 `implementation` 供**生产**编译（`VaultTimeoutPreferences` 的构造参数）；
    //   - 这里的 `testImplementation` 供**单测**编译：本模块的测试源集**编译期看不见**
    //     `implementation` 依赖（Gradle 的 java-library 语义），缺了它
    //     `:core:datastore:testDebugUnitTest` 会直接**编译失败**而不是测试失败。
    //   实测取证（2026-10-10，Kotlin 2.2.21 + datastore 1.2.1，脱离 Gradle 复刻两条
    //   classpath）：① 带上 datastore ⇒ 18 例全过；② 不带 ⇒ `unresolved reference:
    //   'androidx'` 等 20+ 条编译错误，第一个错在 `VaultTimeoutPreferences.kt:11`。
    //   本模块此前从未在 CI 跑过（步骤带 `continue-on-error`），所以这个缺口一直没暴露。
    testImplementation(libs.androidx.datastore)
    // kotlinx.coroutines.flow.{Flow, flowOf, catch}：测试源集同样看不见
    // `implementation(libs.kotlinx.coroutines.android)`（那是个 AAR，且同样是
    // implementation 作用域）。用 `-jvm` 变体即可 —— 测试只用 Flow 的组合子，
    // 不碰 Main dispatcher。
    testImplementation(libs.kotlinx.coroutines.core)
    // VaultTimeout 档位模型的纯 JVM 单测（含旧档位迁移映射断言）
    testImplementation(libs.junit)
    testImplementation(libs.truth)
}
