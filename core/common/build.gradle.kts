plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "io.vaultix.common"
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
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.datetime)
    // ★ SSH 密钥对生成需要 Ed25519：Android JCA 直到 API 33 才提供它，而 minSdk = 26
    //   ⇒ 走 Bouncy Castle（纯 Java，与 API 级别无关）。core:crypto 已依赖同一库。
    implementation(libs.bcprov.jdk18on)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
}
