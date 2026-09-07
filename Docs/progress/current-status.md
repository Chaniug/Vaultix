# 当前进度快照

> 最后更新：2026-09-08

## 里程碑进度

| 期 | 内容 | 状态 |
|---|---|---|
| M0 | 基础骨架 + `core:crypto` | ✅ DONE |
| M1 | **Bitwarden 同步** | 🚧 DOING（数据链路 + UI 最小闭环已通；剩编辑/删除/自动锁定） |
| M2 | KDBX 引擎 | ⬜ TODO |
| M3 | 平台集成（Autofill / 安全中心） | ⬜ TODO |
| M4 / M5 | 发布准备 / 1.0 | ⬜ TODO |

## 模块状态

| 模块 | 状态 | 说明 |
|---|---|---|
| `app` | 🚧→✅ 可用 | 4 屏最小闭环：库列表 / 连接 Bitwarden / 解锁 / 条目列表 + 新建条目 |
| `core:common` | ✅ | safeCall 等 |
| `core:model` | ✅ | VaultItem（6 字段，+password）/ VaultKind / VaultSummary |
| `core:crypto` | ✅ | 10 个文件，行覆盖 91.4% |
| `core:database` | ✅ | Room v2（vaults.account 列，Migration 1→2）；vaults/ciphers/folders/pending_ops |
| `core:datastore` | ✅ | DataStore 设置 + Keystore 安全凭据 |
| `core:ui` | ✅ | VaultixTheme |
| `data:bitwarden` | ✅ | 数据链路全通 + 新建推送换 id 重映射 |
| `data:repository` | ✅ | **首个真实实现**：VaultRepositoryImpl / ItemRepositoryImpl（+5 会话单测） |
| `domain` | 🚧 | **首个真实接口**：VaultRepository / ItemRepository + 结果类型 |
| `data:kdbx` | ⬜ | M2 |
| `feature/*` | ⬜ | 暂不拆分（按包名组织） |

## 质量指标

| 指标 | 当前值 | 目标 | 状态 |
|---|---|---|---|
| `core:crypto` 行覆盖 | 91.4%（417/456） | ≥ 80% | ✅ |
| `core:crypto` 用例数 | 172 通过 | — | ✅ |
| `data:repository` 会话单测 | 5 通过 | — | ✅ |
| 构建 | `:app:compile{Full,Offline}DebugKotlin` 通过 | 通过 | ✅ |

## 技术栈（已升级并验证）

Gradle 9.5.1 / AGP 9.3.2 / Kotlin 2.4.10 / KSP 2.3.11 / Hilt 2.60.1 / compileSdk 37 / JDK 17
