# 当前进度快照

> 最后更新：2026-09-07

## 里程碑进度

| 期 | 内容 | 状态 |
|---|---|---|
| M0 | 基础骨架 + `core:crypto` | ✅ DONE |
| M1 | **Bitwarden 同步** | 🚧 DOING（身份端点骨架已编译通过） |
| M2 | KDBX 引擎 | ⬜ TODO |
| M3 | 平台集成（Autofill / 安全中心） | ⬜ TODO |
| M4 / M5 | 发布准备 / 1.0 | ⬜ TODO |

> 路线图于 2026-09-07 调整：M1/M2 **已对调**，改为 Bitwarden 优先。

## 模块状态

| 模块 | 状态 | 说明 |
|---|---|---|
| `app` | ✅ | 空壳首页 + flavor（full / offline） |
| `core:common` | ✅ | safeCall 等 |
| `core:model` | ✅ | VaultItem / VaultItemType |
| `core:crypto` | ✅ | 10 个文件，行覆盖 91.4% |
| `core:ui` | ✅ | VaultixTheme |
| `data:bitwarden` | 🚧 | 身份+库 API、DTO、网络层、认证链路（401 自动刷新）已完成；同步编排待做 |
| `data:repository` | 🚧 | 仅 KDoc 占位 |
| `domain` | 🚧 | 仅 KDoc 占位 |
| `core:database` | ✅ | Room：vaults / ciphers / folders / pending_ops，只存密文 |
| core:datastore | ✅ | DataStore 设置 + Keystore 安全凭据 |\n| data:kdbx | ⬜ | M2 |
| `feature/*`（11 个） | ⬜ | **决定暂不拆分** |

## 质量指标

| 指标 | 当前值 | 目标 | 状态 |
|---|---|---|---|
| `core:crypto` 行覆盖 | 91.4%（417/456） | ≥ 80% | ✅ |
| `core:crypto` 用例数 | 172 通过 | — | ✅ |
| 构建 | `:app:assembleFullDebug` 通过 | 通过 | ✅ |

## 技术栈（已升级并验证）

Gradle 9.5.1 / AGP 9.3.2 / Kotlin 2.4.10 / KSP 2.3.11 / Hilt 2.60.1 / compileSdk 37 / JDK 17
