plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    // 覆盖率：M0 起 core:crypto 为唯一带单元测试与覆盖率门禁的模块
    alias(libs.plugins.kover)
}

android {
    namespace = "io.vaultix.crypto"
    compileSdk = 37
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Argon2id 黄金向量用例跑 Bitwarden 默认参数（m=64MiB, p=4），纯 JVM 上由 BouncyCastle
// 回退执行，需要一次性分配 64MiB 级别的连续内存；Gradle 默认 512m 测试堆过紧，
// 给到 1g 避免与环境相关的偶发 OOM。
tasks.withType<Test>().configureEach {
    maxHeapSize = "1024m"
}

// AGP 9 内置 Kotlin：改用顶层 kotlin {} 配置编译器（kotlinOptions 已废弃）
kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    // 纯 JVM 可用：模块内禁止出现 android.* 依赖，保证单元测试能脱离 Robolectric 运行。
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.argon2kt)
    implementation(libs.bcprov.jdk18on)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // ---- 单元测试（src/test，纯 JVM：模块内无 android.* 依赖，不需要 Robolectric）----
    // 仅引入 junit + truth：
    //   - mockk：本模块无接口/协作对象可替身（VaultixCrypto 只依赖 CoroutineDispatcher，
    //     测试直接注入真实 dispatcher），引入 mockk 只会增加无用依赖；
    //   - turbine：模块内无 Flow，无需断言流序列。
    //   （二者仍在版本目录中定义，供后续 data/domain 层使用。）
    testImplementation(libs.junit)
    testImplementation(libs.truth)
}

// ===== 覆盖率（Kover 0.9.x）=====
// 门禁：debug 变体、模块级（APPLICATION 分组）、行覆盖 ≥ 80% —— Docs/12 与 M0 验收标准。
// 任务：koverHtmlReportDebug / koverXmlReportDebug / koverVerifyDebug（verify 会自动触发测试）。
kover {
    reports {
        filters {
            excludes {
                // 排除 Hilt/KSP 生成物：它们是编译期产物而非业务逻辑，
                // 纳入统计只会稀释覆盖率口径，掩盖真实业务代码的覆盖缺口。
                classes(
                    "*_Factory",
                    "*_HiltModules*",
                    "*_MembersInjector",
                    "*.Hilt_*",
                    "hilt_aggregated_deps.*",
                )
            }
        }

        variant("debug") {
            verify {
                rule("core:crypto 行覆盖率 >= 80%") {
                    // CoverageUnit.LINE + AggregationType.COVERED_PERCENTAGE 为默认值，
                    // 显式写清楚门禁口径，避免后续读者误读为指令覆盖率。
                    minBound(
                        80,
                        kotlinx.kover.gradle.plugin.dsl.CoverageUnit.LINE,
                        kotlinx.kover.gradle.plugin.dsl.AggregationType.COVERED_PERCENTAGE,
                    )
                }
            }
        }
    }
}
