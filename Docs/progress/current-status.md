# 当前进度快照

> 最后更新：2026-09-08（第二轮）

## 里程碑进度

| 期 | 内容 | 状态 |
|---|---|---|
| M0 | 基础骨架 + `core:crypto` | ✅ DONE |
| M1 | **Bitwarden 同步** | 🚧 DOING（数据链路 + UI 闭环 + 条目编辑/删除 + 自动锁定；剩 2FA/设置/回收站） |
| M2 | KDBX 引擎 | ⬜ TODO |
| M3 | 平台集成（Autofill / 安全中心） | ⬜ TODO |
| M4 / M5 | 发布准备 / 1.0 | ⬜ TODO |

## 模块状态

| 模块 | 状态 | 说明 |
|---|---|---|
| `app` | ✅ 可用 | 5 屏闭环：库列表 / 连接 / 解锁 / 条目列表 / 条目详情（编辑删除复制）+ 自动锁定 |
| `core:common` | ✅ | safeCall 等 |
| `core:model` | ✅ | VaultItem（6 字段）/ VaultKind / VaultSummary |
| `core:crypto` | ✅ | 行覆盖 91.4% |
| `core:database` | ✅ | Room v2；CipherDao.observe(id) 单条目流（v2 无 schema 变更） |
| `core:datastore` | ✅ | DataStore 设置 + Keystore 凭据 |
| `core:ui` | ✅ | VaultixTheme |
| `data:bitwarden` | ✅ | 同步/认证/重映射 |
| `data:repository` | ✅ | VaultRepositoryImpl / ItemRepositoryImpl（create/update/softDelete）+ 11 单测 |
| `domain` | 🚧 | VaultRepository / ItemRepository 接口 |
| `data:kdbx` | ⬜ | M2 |
| `feature/*` | ⬜ | 暂不拆分（按包名组织） |

## 质量指标

| 指标 | 当前值 | 目标 | 状态 |
|---|---|---|---|
| `core:crypto` 行覆盖 | 91.4% | ≥ 80% | ✅ |
| `core:crypto` 用例数 | 172 | — | ✅ |
| `data:repository` 单测 | 11 通过（会话 5 + 条目写路径 6） | — | ✅ |
| Detekt（全模块 main+test） | 0 违规 | 0 | ✅ |
| 构建 | `:app:compile{Full,Offline}DebugKotlin` 通过 | 通过 | ✅ |

## 质量工具

- Detekt `dev.detekt` 2.0.0-alpha.6（2026-09-08 上线，阈值 `config/detekt/detekt.yml`，
  根工程统一启用，CI push/PR 门禁）
- Kover ≥80% 行覆盖门禁（core:crypto）

## 技术栈（未变）

Gradle 9.5.1 / AGP 9.3.2 / Kotlin 2.4.10 / KSP 2.3.11 / Hilt 2.60.1 / compileSdk 37 / JDK 17

## 参考资产（2026-09-08）

- Bastion 冻结为 **reference implementation**（代码与 GitHub 均不再改动），Vaultix = 唯一演进线；决策见 `decisions.md`
- 新增 `Docs/18-Bastion参考地图.md`：按里程碑的 Bastion 参考索引 + 别搬清单 + 对拍流程（M2 `data:kdbx` 启动前必读 §4.3）
