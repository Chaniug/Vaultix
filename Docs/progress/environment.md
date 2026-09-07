# 环境配置与构建策略

## 1. 本机环境

| 项 | 值 | 备注 |
|---|---|---|
| Android SDK | `C:\AndroidSDK` | platforms: android-35 / 37 / 37.0；build-tools 最高 37.0.0 |
| JDK | 17（`C:\Program Files\Microsoft\jdk-17.0.20.8-hotspot`） | AGP 9.3 最低要求即 17，无需 21 |
| Gradle | 9.5.1（wrapper 管理） | 已缓存于 `~/.gradle/wrapper/dists` |
| `local.properties` | `sdk.dir=C\:/AndroidSDK` | **已加入 .gitignore，不入库** |

⚠️ 本机 SDK **没有 android-36**，因此 `compileSdk` 必须 ≥ 37。

⚠️ `D:\AndroidSDK` 是空壳（无 platforms / build-tools），不可用。

## 2. 构建策略：本地只小编译，出包交给 GitHub

| 场景 | 做什么 | 不做什么 |
|---|---|---|
| **本地开发** | 小编译验证、跑单测 | **不出 APK** |
| **GitHub Actions** | 出包并发布 | — |

**理由**：完整出包首次需 4 分钟以上，且本地产物易与 CI 产物混淆、签名也不一致。
把出包收敛到 CI 可保证**产物可复现、签名统一**。

### CI 分工

| 分支 | 触发 | 产物 |
|---|---|---|
| `main` | push / PR | debug APK → `preview` Release（prerelease） |
| `rele` 或 `v*` tag | push / tag | 签名 release APK（R8 混淆压缩 + SHA-256） |

发布前需在仓库 Secrets 配齐 `SIGNING_KEYSTORE_BASE64`、`SIGNING_STORE_PASSWORD`、
`SIGNING_KEY_ALIAS`、`SIGNING_KEY_PASSWORD`（详见 `README.md`）。

## 3. ABI 策略：只出 arm64-v8a

`app/build.gradle.kts` 中：

```kotlin
ndk { abiFilters += listOf("arm64-v8a") }
```

- 面向现代 64 位设备（Android 17 / API 37 为主）
- **不产出** `armeabi-v7a` / `x86` / `x86_64`
- 收益：显著缩小体积 + 缩短构建时间

## 4. 常用命令

```bash
# 本地小编译（日常推荐）
./gradlew :core:crypto:compileDebugKotlin
./gradlew :app:compileFullDebugKotlin

# 单测 + 覆盖率门禁（会自动触发测试）
./gradlew :core:crypto:koverVerifyDebug

# 出包（仅 CI 使用，本地一般不跑）
./gradlew :app:assembleFullDebug
./gradlew :app:assembleRelease
```

> Windows 下用 `gradlew.bat`；路径建议用 PowerShell 的原生 `盘符:\路径`，
> 不要依赖 Git Bash 的 `/d/`、`/tmp`（存在 MSYS 路径映射错位问题，见 `.ai/ISSUES.md`）。
