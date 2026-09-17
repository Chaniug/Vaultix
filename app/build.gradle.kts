import com.android.build.api.dsl.ApplicationExtension
import java.util.Properties
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

    /**
     * ---- 签名（2026-09-17）----
     *
     * ## 要解决的问题
     *
     * 此前：`assembleFullDebug` 产出的是 **Android debug 签名**，而设备上装的是
     * **项目自有发布密钥**（`CN=Vaultix`）⇒ 每次装机都得手动 `apksigner sign` 重签一遍。
     * 更麻烦的是 CI 产物与本地产物**互不兼容**（下载下来盖不上）。
     *
     * 现在：只要拿得到发布密钥，**debug 与 release 都用它签** ⇒
     * 「CI 产物 == 本地产物 == 设备上已装的包」三者签名一致，下载即可原地覆盖安装。
     *
     * ## 密钥从哪来（按优先级，两处都拿不到就退回旧行为）
     *
     * 1. **环境变量** —— CI 用 GitHub Secrets 注入（`VAULTIX_KEYSTORE_PATH` /
     *    `VAULTIX_STORE_PASSWORD` / `VAULTIX_KEY_ALIAS`）；
     * 2. **仓库根的 `keystore.properties`** —— 本地开发用，**已 gitignore**；
     * 3. 默认路径 `D:/vaultix-release.jks`（见 `.ai/conventions/8.7-环境.md`）存在就用它。
     *
     * ⚠️ **密钥永不入库**（仓库是公开的）：三种来源都在仓库之外。
     * ⚠️ **key 密码 == store 密码**：密码文件里那个 `KEY_PASSWORD` 是错的，别照抄（8.7 有记录）。
     * ⚠️ 日志里会打印**用了哪种来源**（不打印密码），否则"这次到底签没签"只能靠猜。
     *
     * ⚠️ 这里必须 `import java.util.Properties`（见文件头）：在 Gradle Kotlin 脚本里
     * 写 `java.util.Properties()` 会被 `java`（JavaPluginExtension）**遮蔽**，
     * 报 `Unresolved reference 'util'` —— 与"属性名写错"长得一模一样的假象。
     */
    val keystoreProps = Properties().apply {
        val file = rootProject.file("keystore.properties")
        if (file.exists()) file.inputStream().use { load(it) }
    }

    fun signingValue(envKey: String, propKey: String): String? =
        System.getenv(envKey)?.takeIf { it.isNotBlank() }
            ?: keystoreProps.getProperty(propKey)?.takeIf { it.isNotBlank() }

    val releaseStore = signingValue("VAULTIX_KEYSTORE_PATH", "storeFile")
        // ⚠️ **必须把反斜杠归一化成正斜杠**：`.properties` 里 `\` 是**转义字符**，
        //    `D:\vaultix-release.jks` 会被 `Properties` 解析成 `D:vaultix-release.jks`
        //    （`\v` → `v`），于是 file 解析失败、签名悄悄退回 debug。
        //    这是"配置看着没错、效果却是没签"的典型来源，所以在代码里兜住，
        //    而不是只在文档里写"请用正斜杠"。
        ?.replace('\\', '/')
        ?.let { rootProject.file(it) }
        ?: rootProject.file("D:/vaultix-release.jks").takeIf { it.exists() }

    val releaseStorePassword = signingValue("VAULTIX_STORE_PASSWORD", "storePassword")
    val releaseKeyAlias = signingValue("VAULTIX_KEY_ALIAS", "keyAlias") ?: "vaultix"

    val hasReleaseSigning = releaseStore != null && releaseStorePassword != null

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = releaseStore
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                // ⚠️ 与 store 密码相同（见 8.7 的坑），**不要**去读那个错的 KEY_PASSWORD。
                keyPassword = releaseStorePassword
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            // 注意：不要加 applicationIdSuffix，保证 debug 与 release 可互相覆盖安装。
            // ⚠️ 有发布密钥时**刻意用它签 debug**（见上）：否则 CI 的 debug 包与设备上
            //    已装的包签名不符，下载下来根本装不上，等于白出包。
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 有发布密钥就用它；没有则保持"由 CI 通过 -Pandroid.injected.signing.* 注入"的旧路径。
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    logger.lifecycle(
        "[Vaultix 签名] " + if (hasReleaseSigning) {
            "使用项目发布密钥签名（别名 $releaseKeyAlias，来源 ${releaseStore?.path}）" +
                " ⇒ debug/release 产物与设备上已装的包**签名一致**，可直接覆盖安装。"
        } else {
            // ⚠️ 分两种情况说清楚 —— 否则"签没签"这件事只能靠猜：
            //    有密钥库但没给密码时，笼统地说"找不到密钥库"会把人引到错误的方向。
            val why = if (releaseStore == null) {
                "找不到 keystore（env VAULTIX_KEYSTORE_PATH / keystore.properties / " +
                    "D:/vaultix-release.jks 都没有）"
            } else {
                "找到了 keystore（${releaseStore.path}）但**没有密码**" +
                    "（env VAULTIX_STORE_PASSWORD 或 keystore.properties 的 storePassword）"
            }
            "未启用发布签名：$why ⇒ 退回旧行为：debug 用 Android debug 密钥签、release 不签。" +
                "⚠️ 这样的产物装不上已有发布签名的设备（需手动重签，见 .ai/conventions/8.7-环境.md）。"
        },
    )

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
    // ⚠️ `data:kdbx` **必须显式声明**（2026-09-17 踩）：
    // `data:repository` 是用 `implementation(projects.data.kdbx)` 引它的，
    // 而 `implementation` **不传递给上层消费者** ⇒ app 侧拿不到
    // `io.vaultix.data.kdbx.KdbxFileSource` 等类型，且报错极具误导性：
    //     e: KdbxCloudSyncAppModule.kt:151 Cannot access class
    //        'io.vaultix.data.kdbx.KdbxFileSource'.
    //        Check your module classpath for missing or conflicting dependencies.
    //     e: OneDriveKdbxFileSource.kt:35 Unresolved reference 'kdbx'.
    // 读起来像"依赖冲突"，实际只是少了这一行。
    // 不改成 `data:repository` 用 `api(...)`：那会把 KDBX 的全部实现
    // （含 kotpass）泄给每一个消费者的编译期 classpath，
    // 而 app 需要它只是因为 OneDrive 来源实现落在这里（见 KdbxCloudSyncAppModule）。
    implementation(projects.data.kdbx)

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
