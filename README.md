<div align="center">

# 🔐 Vaultix

**Android 开源密码管理器 —— 同时支持 Bitwarden 云端库与 KeePass KDBX 本地库**

[![CI](https://github.com/Chaniug/Vaultix/actions/workflows/ci-debug.yml/badge.svg)](https://github.com/Chaniug/Vaultix/actions/workflows/ci-debug.yml)
[![Release](https://img.shields.io/github/v/release/Chaniug/Vaultix?include_prereleases&label=release)](https://github.com/Chaniug/Vaultix/releases)
[![License](https://img.shields.io/badge/License-GPL--3.0--or--later-blue.svg)](./LICENSE)
[![Platform](https://img.shields.io/badge/Android-8.0%2B%20(API%2026)-3DDC84?logo=android&logoColor=white)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.4-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Compose](https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4?logo=jetpackcompose&logoColor=white)](https://developer.android.com/jetpack/compose)

</div>

---

## 📖 这是什么

Vaultix 是一个 Android 密码管理器。与多数同类不同的是，它**同时**把两套生态当作一等公民：

| | 说明 |
|---|---|
| ☁️ **Bitwarden / Vaultwarden** | 在线同步，走官方新版 API（prelogin → token → sync）。支持自建 Vaultwarden |
| 🔒 **KeePass KDBX** | 本地文件，KDBX 4.x（写入恒 4.1）。不联网、无账号、无 2FA |

两套库共用同一套界面、同一个自动填充引擎、同一份条目模型 —— 你可以在一个 App 里同时放着云端库和一个本地 .kdbx 文件。

> 名字来自 **Vault**（保险库）+ **-ix**（技术感后缀）。

## ✨ 功能

<details open>
<summary><b>凭据管理</b></summary>

- 条目类型：登录 / 安全笔记 / 支付卡 / 身份（身份支持 17 个字段）
- 分组文件夹、收藏、回收站、批量多选删除
- 自定义字段（文本 / 隐藏 / 布尔）、附加选项（主密码二次验证）
- 站点图标自动抓取与本地缓存

</details>

<details open>
<summary><b>自动填充与通行密钥</b></summary>

- **AutofillService**：登录框下拉直填，支持用户名/密码/验证码
- **Credential Provider**（Android 14+）：通行密钥（passkey）创建与断言
- **解锁即回填**：库锁定时点填充 → 生物识别解锁 → 条目列表直接出现，无需返回重进
- **保存新凭据**：登录页保存提示三段式（SaveInfo → onSaveRequest → 确认页）
- 填充后自动复制 TOTP（受开关门控）

</details>

<details open>
<summary><b>安全</b></summary>

- 两套 KDF 全支持：**PBKDF2**（SHA-256/512）与 **Argon2id**
- 本地快速解锁：账号密钥由 **Android Keystore + AES-256-GCM** 包裹，逐次生物认证
- 两层锁语义：**真锁**（密钥清零 + 主密码 + 联网）与**查看锁**（密钥留在内存 + 一次生物识别 + 离线）
- 防截屏开关、敏感字段内存清零、TOTP 不落盘

</details>

<details open>
<summary><b>界面</b></summary>

- Material Design 3 + 动态取色 + OLED 纯黑
- 沉浸式顶栏、悬浮底栏、跟随滚动的收起动画
- 全屏编辑页（长表单不再蜷在弹窗里）
- 平板 / 大屏自适应

</details>

## 🧱 技术栈

| 层 | 选型 |
|---|---|
| 语言 | Kotlin 2.4 |
| UI | Jetpack Compose + Material 3 |
| 异步 | Coroutines / Flow |
| 本地存储 | Room（条目缓存）+ DataStore（偏好）+ Android Keystore（密钥） |
| 网络 | Retrofit / OkHttp |
| DI | Hilt |
| KDBX | [`app.keemobile:kotpass`](https://github.com/keemobile/kotpass)（MIT） |
| 构建 | Gradle 9 + AGP 9 · compileSdk 37 · minSdk 26 · 只出 `arm64-v8a` |
| 质量门 | detekt（含复杂度/规模阈值，见 [`config/detekt`](./config/detekt/detekt.yml)） |

## 🚀 构建

前置：**JDK 17**、**Android SDK（需 platforms;android-37）**、`local.properties` 指向 SDK。

```bash
git clone git@github.com:Chaniug/Vaultix.git
cd Vaultix

# 编译 debug 包（会先跑 detekt 质量门）
./gradlew detekt :app:assembleFullDebug

# 产物
# app/build/outputs/apk/full/debug/app-full-debug.apk
```

> **只用命令行也行**：本项目不依赖 Android Studio，`sdkmanager` 装齐 SDK 后
> 用 Gradle Wrapper 即可出包。环境配置细节见
> [Docs/progress/environment.md](./Docs/progress/environment.md)。

## 🏗 架构一览

```
app/                 # UI 层：Compose 界面、导航、ViewModel、自动填充/通行密钥宿主
 ├ core/             # 跨领域基础设施（datastore: 偏好与 Keystore 封装 / ui: 设计系统 …）
 ├ data/             # 数据层：repository 实现（Bitwarden 同步、KDBX 会话、条目读写）
 └ domain/           # 领域层：纯 Kotlin 接口与模型，不依赖 Android
```

三条硬约束：

1. **`domain` 不依赖 Android** —— 两套库的差异在 `data` 层收敛成统一模型；
2. **锁态只有一个真源**（活跃库是否解锁），四类消费点（CP 候选 / passkey 断言 / 密码回灌 / 自动填充）读同一处；
3. **KDBX 会话是整库明文（仅内存）**，不进数据库、不落盘、不进日志。

详见 [Docs/01-系统架构设计.md](./Docs/01-系统架构设计.md)。

## 📚 文档

<details>
<summary><b>19 篇技术文档（点开）</b></summary>

| 编号 | 文档 | 内容 |
|---|---|---|
| 00 | [项目总览](./Docs/00-项目总览.md) | 产品定位、功能范围、术语表、非目标 |
| 01 | [系统架构设计](./Docs/01-系统架构设计.md) | 分层架构、模块划分、依赖规则、关键时序 |
| 02 | [统一领域模型](./Docs/02-统一领域模型.md) | Bitwarden 与 KDBX 的抽象与字段映射 |
| 03 | [密码学与密钥管理](./Docs/03-密码学与密钥管理.md) | 两套 KDF、EncString、KDBX 派生链、本地密钥存储 |
| 04 | [Bitwarden 同步引擎](./Docs/04-Bitwarden同步引擎.md) | prelogin/token/sync、增量策略、冲突与离线 |
| 05 | [KDBX 存储引擎](./Docs/05-KDBX存储引擎.md) | KDBX 4.x 读写流程、保存策略、附件 |
| 06 | [Android 平台集成](./Docs/06-Android平台集成.md) | Autofill、Credential Manager、生物识别、Keystore |
| 07 | [Material 3 设计系统](./Docs/07-Material3设计系统.md) | 色彩 / 字体 / 形状 / 动效 / 组件规范 |
| 08 | [界面设计规格](./Docs/08-界面设计规格.md) | 逐屏布局、状态、空态、大屏适配 |
| 09 | [安全与隐私设计](./Docs/09-安全与隐私设计.md) | 威胁模型、内存与剪贴板、防截屏、日志 |
| 10 | [状态管理与数据流](./Docs/10-状态管理与数据流.md) | MVI、UseCase、仓库边界、错误模型 |
| 11 | [工程规范与构建体系](./Docs/11-工程规范与构建体系.md) | 模块化、DI、命名、依赖清单、License |
| 12 | [测试与发布](./Docs/12-测试与发布.md) | 测试金字塔、合规用例、CI、上架 |
| 13 | [路线图与里程碑](./Docs/13-路线图与里程碑.md) | 分期目标与验收标准 |
| 14 | [附录：代码骨架](./Docs/14-附录代码骨架.md) | 关键接口与类的代码框架 |
| 15 | [竞品界面参考](./Docs/15-竞品界面参考与差异化.md) | 对 Keyguard / Monica / Bastion 的参考点 |
| 16 | [性能与交互规范](./Docs/16-性能与交互规范.md) | 代码规模上限、列表/解密性能、沉浸交互 |
| 17 | [稳定性与防错规范](./Docs/17-稳定性与防错规范.md) | 序列化容错、协程取消、崩溃兜底 |
| 18 | [Bastion 参考地图](./Docs/18-Bastion参考地图.md) | Bastion 代码/文档 → Vaultix 行为的参考索引 |

进度与决策记录见 [Docs/progress/](./Docs/progress/README.md)。

</details>

## 🌿 分支策略与自动构建

| 分支 | 角色 | 自动构建 |
|---|---|---|
| `main` | **默认开发分支**（受保护，需 CI 通过） | push / PR → **debug APK** → 发到 `preview` Release（预发布，供真机快速验证） |
| `rele` | **稳定发布分支** | push / 打 `v*` tag → **签名 release APK**（混淆 + 压缩）→ GitHub Release + SHA-256 |

> 约定：`main` 上开发验证 → 合入 `rele` → 自动产出稳定包。也可直接推 `vX.Y.Z` 标签。
> 版本号见仓库根 [`VERSION`](./VERSION)。

**覆盖安装（debug ↔ release 互换）的前提**：`applicationId` 相同 + 签名证书相同。
本仓库的 `ci-debug.yml` 与 `release.yml` 已共用同一套 `SIGNING_*` Secret，因此两个渠道的包可以互相覆盖安装、数据不丢。

<details>
<summary><b>Secret 配置与本地生成密钥库（点开）</b></summary>

仓库 `Settings → Secrets and variables → Actions`：

| Secret | 含义 |
|---|---|
| `SIGNING_KEYSTORE_BASE64` | 发布密钥库（`.jks`）的 Base64 单行文本 |
| `SIGNING_STORE_PASSWORD` | 密钥库口令 |
| `SIGNING_KEY_ALIAS` | 密钥别名 |
| `SIGNING_KEY_PASSWORD` | 密钥口令 |

```bash
# 1) 生成发布密钥库（⚠️ Java 默认 PKCS12 要求 store 密码与 key 密码【相同】）
keytool -genkeypair -v -keystore vaultix-release.jks \
  -keyalg RSA -keysize 2048 -validity 10000 -alias vaultix \
  -storepass '同一个密码' -keypass '同一个密码' \
  -dname "CN=Vaultix, O=Vaultix, C=CN"

# 2) 导出单行 Base64（macOS 去掉 -w0）
base64 -w0 vaultix-release.jks
```

未配置 `SIGNING_KEYSTORE_BASE64` 时流水线会生成**一次性**密钥（仅用于验证，不可正式分发）。

</details>

## 🗺 状态

当前版本 **0.1.0**（早期开发中，API 与数据格式仍可能变动）。

- ✅ M1 基础框架 / M2-a Bitwarden 同步引擎 / M2-b KDBX 只读 / M3 自动填充与通行密钥 —— 主链路已通
- 🚧 进行中：性能专项优化、PIN 快速解锁、KDBX 写入（阶段 B）
- 📋 计划：性能优化见 [Docs/progress/perf-plan.md](./Docs/progress/perf-plan.md)

里程碑与验收标准见 [Docs/13-路线图与里程碑.md](./Docs/13-路线图与里程碑.md)。

## 🤝 参与

Issue / PR 都欢迎。提 PR 前请确保：

```bash
./gradlew detekt :app:assembleFullDebug   # 质量门 + 编译必须全绿
```

代码风格、复杂度阈值、模块依赖规则见 [Docs/11-工程规范与构建体系.md](./Docs/11-工程规范与构建体系.md)
与 [`config/detekt/detekt.yml`](./config/detekt/detekt.yml)。

## 📄 许可与溯源

**GPL-3.0-or-later**，与 Bitwarden 客户端保持一致（2026-09 由 MIT 切换），
以便合规参考/复用同为 GPL-3.0 的实现。依赖清单与 License 详见
[Docs/11-工程规范与构建体系.md](./Docs/11-工程规范与构建体系.md)。

**溯源声明**

- 行为与部分不变量参考 **[Bastion](https://github.com/JoyinJoester/Bastion)**（GPL-3.0）实现，
  已按 GPL-3.0 要求保留来源与协议；参考地图见 [Docs/18](./Docs/18-Bastion参考地图.md)。
- **[Keyguard](https://github.com/aczid/keyguard)** 为 **All Rights Reserved**，
  本项目**不参考、不复用其任何代码**，仅在交互层面做过独立观察。
- KDBX 读写使用 [`app.keemobile:kotpass`](https://github.com/keemobile/kotpass)（MIT）。
