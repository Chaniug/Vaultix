/*
 * Vaultix — data:kdbx
 * Copyright (C) 2026 Vaultix contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 */

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "io.vaultix.data.kdbx"
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
    implementation(projects.core.datastore)
    implementation(projects.core.database)

    // KDBX 读写引擎（MIT，见 gradle/libs.versions.toml 的说明）
    implementation(libs.kotpass)

    implementation(libs.kotlinx.coroutines.android)

    // 依赖注入
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // 测试：KDBX 读写往返 / 字段映射（纯 JVM，不需要 Android 运行时）
    testImplementation(libs.junit)
    testImplementation(libs.truth)
}
