// 启用类型安全项目访问器：否则子模块中的 projects.core.model / projects.domain
// 会被误解析为 TaskContainer.projects（一个 ProjectReportTask）而编译失败。
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("""com\.android.*""")
                includeGroupByRegex("""com\.google.*""")
                includeGroupByRegex("""androidx.*""")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // ★ OneDrive 鉴权（MSAL）**必须**要这个 feed（2026-09-17）。
        //
        // 依赖链：msal:8.4.2 → com.microsoft.identity:common:24.6.0 →
        //         com.microsoft.device.display:display-mask:0.3.0
        // 而 display-mask **只在微软 Surface Duo SDK 的公共 feed 上**，
        // Google Maven 与 Maven Central 都没有 ⇒ 不加这一行，构建会在
        // `:app:processFullDebugNavigationResources` 报
        // "Could not find com.microsoft.device.display:display-mask:0.3.0"。
        // 上游 Bastion 用的是同一个 feed（其 settings.gradle 的 DuoSDK-Public）。
        //
        // ⚠️ 这是一个**外部单点**：该 feed 下线会直接让构建挂掉。
        //    真要脱钩只能 exclude 掉 display-mask —— 但 MSAL 的 common 确实引用了它
        //    （双屏 / Surface Duo 适配），排除后折叠设备上可能 NoClassDefFoundError。
        //    现阶段选择"与上游保持一致"，而不是为了少一个仓库承担运行时风险。
        maven {
            url = uri(
                "https://pkgs.dev.azure.com/MicrosoftDeviceSDK/DuoSDK-Public/_packaging/Duo-SDK-Feed/maven/v1",
            )
        }
    }
}

rootProject.name = "Vaultix"

include(":app")

// core 层：通用能力 / 领域模型 / UI 主题 / 密码学
include(":core:common")
include(":core:crypto")
include(":core:model")
include(":core:ui")

// domain 与 data 层
include(":domain")
include(":data:repository")
include(":data:bitwarden")

// 注意：新增模块必须在此登记，否则目录不参与构建（孤儿目录）。
// 后续 core:crypto / core:database / data:kdbx / data:bitwarden / feature:* 亦然。
include(":core:database")
include(":core:datastore")

// M2：KDBX 本地库引擎（kotpass，MIT）
include(":data:kdbx")
