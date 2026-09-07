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
