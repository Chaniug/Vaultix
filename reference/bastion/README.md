# Bastion 参考快照（vendored reference）

> **只读参考，不参与构建**：本目录不属任何 Gradle 模块——不会被编译、不打包进
> APK、不参与 detekt/lint。用途 = 让 Vaultix 上的接力 AI 无需访问本机
> `D:\Bastion` 也能随时取用 Bastion 的行为知识、测试向量与文档。

## 快照信息

| 项 | 值 |
|---|---|
| 来源仓库 | `github.com/Chaniug/bastion`（本地 `D:\Bastion\bastion`，dev 分支） |
| 快照 commit | `369ed56`（dev head，2026-09-06；2026-09-08 起 Bastion 冻结不再演进） |
| 许可 | **GPL-3.0**（`LICENSE` 为原仓库副本；Vaultix 同为 GPL-3.0） |
| 内容 | 1012 文件 / ≈13 MB（main 源码 664 + 单测 155 + repo docs 24 + BastionDocs md 151 + workflows 7 + values 文案） |
| 引用导航 | `Docs/18-Bastion参考地图.md`（参考索引/别搬清单/对拍流程）；`.ai/MEMORY.md` |

## 路径映射（对照 Docs/18 §2 的前缀约定）

| 原仓库路径 | 本目录 | 说明 |
|---|---|---|
| `app/src/main/java/com/bastion/app/` | `app/src/main/java/com/bastion/app/` | 全部主源码（Docs/18 中前缀 `app/` 即指此处） |
| `app/src/test/java/…` | `app/src/test/java/…` | 单测 ≈680 条：**对拍断言场景来源**（Docs/18 §5） |
| `app/src/main/res/values*/` | `app/src/main/res/values*/` | 仅文案/样式 values（drawable/mipmap/layout 未搬） |
| 仓库根 `docs/` | `docs/` | 内部文档（README、架构路线图、同步生态、自动填充、passkey 方案、性能档案…） |
| `.github/workflows/` | `workflows/` | **仅参考用**：Vaultix 自己的 CI 在仓库根 `.github/workflows/`，勿把本目录 yml 移入启用（路径/Secrets 均不同） |
| 仓库根 `README.md` | `upstream-README.md` | 原 README 副本 |
| 仓库根 `LICENSE` | `LICENSE` | 原 LICENSE 副本（GPL-3.0） |
| `BastionDocs/` | `BastionDocs/` | 用户文档站，**仅 *.md**（151 篇；站点源码/图片未搬） |

## 刻意未搬（与 Docs/18 §3 别搬清单一致）

- `mdbx/`（已移除自研引擎历史）、`desktop/`（CMP 桌面端，Vaultix 无桌面目标）、
  `image/` `pages/` `scripts/`、Bastion app `assets/`（1079 个 webp 动图）
- `BastionDocs` 非 md 文件（package.json / mjs / 站点资源）
- res 中 drawable/layout/mipmap/xml（UI 视觉规范以 Vaultix Docs/07、Docs/08 为准）

## 使用规则（MUST）

1. **只搬三类资产**：行为知识、测试向量与保真矩阵、无依赖的核心算法（Docs/18 §1）；
   **不做文件级搬迁**——强耦合 Bastion 模型/存储的文件必须按 Vaultix 架构重写。
2. 把本目录内容写进 Vaultix 产品代码前，按 Docs/18 §6 溯源模板在文件头注明
   来源（`Ported from Bastion (GPL-3.0), Copyright 2025 JoyinJoester` + 用途）。
3. 对拍验收：以本目录 `app/src/test/` 场景意图 + `docs/` 修复记录为断言来源，
   在 Vaultix 单测固化（GuardTest 不迁移）。
4. 大段研读后把结论回写 `Docs/18-Bastion参考地图.md`（§7 维护规则），避免重复通读。

## 更新策略

Bastion 已冻结（2026-09-08 用户拍板）：本快照**不会随上游更新**。若发现需对齐的
修复，走 Vaultix 侧人工决策后单独同步。更新本目录时应同步更新上表 commit 值。
