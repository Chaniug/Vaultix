# Vaultix 技术文档集

> Android 开源密码管理器 · 同时支持 **Bitwarden 云端库** 与 **KeePass KDBX 本地库** · Material Design 3 界面风格

| 项目 | 说明 |
|---|---|
| 项目代号 | Vaultix |
| 目标平台 | Android 8.0（API 26）及以上，Target SDK 36（Android 16） |
| 技术栈 | Kotlin + Jetpack Compose + Material 3 + Coroutines/Flow + Room + Ktor/OkHttp + Hilt |
| 支持库类型 | ① Bitwarden / Vaultwarden 新版服务端（在线同步，仅新版 API）② KDBX 4.x 本地文件（写入恒 4.1） |
| 开源协议 | 建议 GPL-3.0-or-later（与 Bitwarden 客户端一致）；依赖 License 见 [11-工程规范](./Docs/11-工程规范与构建体系.md) |
| 文档版本 | v1.0（2026-09） |

## 文档导航

| 编号 | 文档 | 内容 | 读者 |
|---|---|---|---|
| 00 | [项目总览](./Docs/00-项目总览.md) | 产品定位、功能范围、术语表、非目标 | 所有人 |
| 01 | [系统架构设计](./Docs/01-系统架构设计.md) | 分层架构、模块划分、依赖规则、关键时序 | 架构师 / 开发 |
| 02 | [统一领域模型](./Docs/02-统一领域模型.md) | Bitwarden 与 KDBX 的抽象与字段映射 | 开发 |
| 03 | [密码学与密钥管理](./Docs/03-密码学与密钥管理.md) | 两套 KDF、EncString、KDBX 密钥派生链、本地密钥存储 | 开发 / 安全审计 |
| 04 | [Bitwarden 同步引擎](./Docs/04-Bitwarden同步引擎.md) | prelogin/token/sync、增量策略、冲突与离线 | 开发 |
| 05 | [KDBX 存储引擎](./Docs/05-KDBX存储引擎.md) | KDBX 4.x 读写流程、保存策略、附件 | 开发 |
| 06 | [Android 平台集成](./Docs/06-Android平台集成.md) | Autofill、Credential Manager、生物识别、Keystore、后台任务 | 开发 |
| 07 | [Material 3 设计系统](./Docs/07-Material3设计系统.md) | 色彩/字体/形状/动效/组件规范 | 设计 / 开发 |
| 08 | [界面设计规格](./Docs/08-界面设计规格.md) | 逐屏布局、状态、空态、平板与大屏适配 | 设计 / 开发 |
| 09 | [安全与隐私设计](./Docs/09-安全与隐私设计.md) | 威胁模型、内存与剪贴板、防截屏、日志 | 安全 / 开发 |
| 10 | [状态管理与数据流](./Docs/10-状态管理与数据流.md) | MVI、UseCase、仓库边界、错误模型 | 开发 |
| 11 | [工程规范与构建体系](./Docs/11-工程规范与构建体系.md) | Gradle 模块化、DI、命名、依赖清单、License | 开发 |
| 12 | [测试与发布](./Docs/12-测试与发布.md) | 测试金字塔、合规用例、CI、上架与 F-Droid | QA / 开发 |
| 13 | [路线图与里程碑](./Docs/13-路线图与里程碑.md) | 分期目标与验收标准 | 所有人 |
| 14 | [附录：代码骨架](./Docs/14-附录代码骨架.md) | 关键接口与类的代码框架 | 开发 |
| 15 | [竞品界面参考与差异化](./Docs/15-竞品界面参考与差异化.md) | 对 Keyguard / Monica / Bastion 的参考点与差异 | 设计 / 产品 |

## 分支策略与自动构建

参照 Bastion 项目的 CI 模式搭建，构建在 **GitHub Actions** 上完成。

| 分支 | 角色 | 自动构建 |
|---|---|---|
| `main` | **默认开发分支**（受保护，需 CI 通过） | push/PR → 编译 **debug APK** → 发到 `preview` Release（prerelease，供真机快速验证） |
| `rele` | **稳定发布分支**（合入 main 验证无误后的快照） | push/打 `v*` tag → 编译 **签名 release APK**（混淆+压缩）→ 发到 GitHub Release（Latest）+ SHA-256 |

> 约定流程：`main` 上开发并验证 → 确认无误后合入 `rele` → 由 `rele` 自动产出稳定 APK。
> 也可直接推送 `vX.Y.Z` 标签触发 `release.yml`。版本号见仓库根 `VERSION` 文件（rele 合入时读取）。

**Secret 配置**（仓库 `Settings → Secrets and variables → Actions → New repository secret`）：

| Secret 名 | 含义 | 生成方式 |
|---|---|---|
| `SIGNING_KEYSTORE_BASE64` | 发布密钥库（`.jks`）的 **Base64 单行** 文本 | `base64 -w0 vaultix-release.jks` |
| `SIGNING_STORE_PASSWORD` | 密钥库口令（store password） | keytool 生成时你填的 `-storepass` |
| `SIGNING_KEY_ALIAS` | 密钥别名 | keytool 生成时你填的 `-alias`（**必须与实际一致**，否则签名失败） |
| `SIGNING_KEY_PASSWORD` | 密钥口令（key password） | keytool 生成时你填的 `-keypass` |

> 未配置 `SIGNING_KEYSTORE_BASE64` 时，流水线会生成**一次性**密钥仅用于验证（每次构建不同、不可正式分发，且 debug/release 无法互相覆盖）。**正式发布前务必配置以上 4 个 Secret。**

**本地生成密钥库并提取 Base64（复制即用）：**

```bash
# 1) 生成发布密钥库（alias 自定，下面用 vaultix，请记牢）
#    ⚠️ Java 默认 PKCS12 格式要求 store 密码与 key 密码【相同】，
#       务必两处填同一个密码，否则 keytool 会忽略 keypass，导致后续 CI 签名失败。
keytool -genkeypair -v -keystore vaultix-release.jks \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -alias vaultix \
  -storepass '改成你的同一个密码' \
  -keypass  '改成你的同一个密码' \
  -dname "CN=Vaultix, O=Vaultix, C=CN"

# 2) 导出为单行 Base64（macOS 用 base64 vaultix-release.jks 即可，去掉 -w0）
base64 -w0 vaultix-release.jks

# 3) 按上表把输出串与密码分别填进 4 个 Secret：
#    SIGNING_KEYSTORE_BASE64 <- 第 2 步整串
#    SIGNING_KEY_ALIAS       <- vaultix
#    SIGNING_STORE_PASSWORD  <- 你的 store 密码
#    SIGNING_KEY_PASSWORD    <- 你的 key 密码
```

**release 与 debug 互相覆盖安装（同一设备升级安装）的前提：**

- 两者 `applicationId` 必须相同（写 `app/build.gradle` 时 **debug 不要加 `applicationIdSuffix ".debug"`**）。
- 两者签名证书必须相同。本仓库的 `ci-debug.yml` 与 `release.yml` **已统一复用同一套 `SIGNING_*` Secret**，因此从 `preview` Release 装的 debug 包，可直接被 `rele` 发布的 release 包覆盖安装。
- 例外：本地 Android Studio 直接 Run 的 debug 包默认用 `~/.android/debug.keystore`（Android 自动生成），与 CI 包签名不同，无法直接覆盖，需先卸载再装，或在本地也把 debug `signingConfig` 指向同一个 `vaultix-release.jks`。

详见 [`.github/workflows/ci-debug.yml`](./.github/workflows/ci-debug.yml) 与 [`.github/workflows/release.yml`](./.github/workflows/release.yml)。

## 阅读路径建议

- **新贡献者**：00 → 01 → 02 → 10 → 11
- **设计同学**：07 → 08 → 15
- **做安全审计**：03 → 09 → 06
- **接同步 / KDBX**：02 → 04 / 05 → 14

## 文档约定

- `MUST` / `SHOULD` / `MAY` 采用 RFC 2119 语义；`MUST NOT` 表示硬性禁止。
- 代码骨架仅示意，非最终实现；以模块内源码为准。
- 涉及密码学的参数（迭代数、内存量、随机数长度）均以开源实现与 KeePass / Bitwarden 官方规范为准，升级时需重新核对。
