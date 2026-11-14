# Docs/ 文档地图（2026-11-14 整理）

> 这份文件回答「**本项目有哪些文档、该信哪一份**」。
> 唯一真源索引见 [`.ai/conventions/8.10-架构与目录地图.md`](../.ai/conventions/8.10-架构与目录地图.md) §1；
> 当前状态与下一批见 [`.ai/README.md`](../.ai/README.md) 顶部「🕐 最新状态」；
> 待办见 [`progress/next-steps.md`](./progress/next-steps.md)。

---

## 1. 先分清楚四类，别拿蓝图描述现状

| 类别 | 位置 | 性质 | 能否回答「现在怎样」 |
|---|---|---|---|
| **活跃真源** | [`.ai/README.md`](../.ai/README.md) 顶部、[`.ai/MEMORY.md`](../.ai/MEMORY.md)、[`.ai/ISSUES.md`](../.ai/ISSUES.md) + `issues/`、[`.ai/conventions/8.x`](../.ai/conventions/8.10-架构与目录地图.md)、[`progress/next-steps.md`](./progress/next-steps.md) | 倒序更新、接力第一站 | ✅ **只有这一类能** |
| **设计期蓝图** | 本目录 `00`~`18`（2026-09-07） | 当时的规划与验收目标 | ❌ 只能读「当初想怎么做」，见 §2 |
| **施工记录 / 决策** | [`progress/`](./progress/README.md)：`decisions.md`、`*-plan.md`、`*-handoff.md`、`audit/`、各 `archive-*` | 按日期冻结的过程留痕 | ⚠️ 只看结论与真源指针；过程会过时 |
| **版本冻结** | [`release-notes/v*.md`](./release-notes/v0.13.2.md) | 发布页正文直接读它 | ❌ **不改写已发布版本** |
| **第三方只读** | `reference/bastion/`（仓库根下） | 上游 Bastion（GPL-3.0） | ❌ 不是本项目 |

另：根 [`README.md`](../README.md) 与 [`SECURITY.md`](../SECURITY.md) 面向用户，属于活跃文档，
但其中的版本号以 `VERSION` / Releases 页为准，不另立真源。

---

## 2. `00`~`18` 是设计期蓝图：已核实的偏差

> 这批文档**没有作废**：概念、术语、密码学约束、验收目标仍有参考价值。
> 但下面这些条目与当前实现不一致，照抄会写错。**未列入的行也不是「已核实一致」**，
> 只是尚未逐条复核 —— 一律按「设计期口径」读。

| # | 文档 | 蓝图写的 | 当前事实 |
|---|---|---|---|
| 01 | [系统架构设计](./01-系统架构设计.md) | `feature:*` 模块、`VaultSource` 策略模式、「UI → ViewModel → Domain → Data」分层 | **都不存在**。实际是 `settings.gradle.kts` 里的 **10 个 Gradle 模块**，数据源差异由 `data:repository` + `domain` 接口收敛 |
| 01 / 04 / 13 | 架构 / 同步引擎 / 路线图 | WorkManager 周期同步 | **C01 已判推迟**：用户真机反馈「自动同步太频繁」后移除进页 / 回前台自动拉取，见 `progress/decisions.md`；`check_no_fix_register.py` 会拦截把它重新列成待办 |
| 01 / 11 | 架构 / 工程规范 | 「若本项目选 Apache-2.0」的 License 假设 | 2026-09 起已定 **GPL-3.0-or-later**；KDBX 实际用 MIT 的 `kotpass`，未复用 KeePassDX 代码（两处表格已就地订正） |
| 11 | [工程规范与构建体系](./11-工程规范与构建体系.md) | 依赖记在 `docs/dependencies.md` | **该文件不存在**。唯一真源是 `gradle/libs.versions.toml`（AGP 9.5.0 / Kotlin 2.4.21），Gradle 版本看 `gradle-wrapper.properties`（9.8.2） |
| 12 | [测试与发布](./12-测试与发布.md) §6 | `release/x.y.z` 分支、Google Play 分阶段、F-Droid 自动构建 | **均未落地**。现行是 `main` bump `VERSION` → 用户授权后快进 `rele` → `release.yml` 打 `vX.Y.Z` → GitHub Release（Latest）+ checksums + attestation |
| 00 / 13 | [项目总览](./00-项目总览.md) §3.1、[路线图](./13-路线图与里程碑.md) M3 | 安全中心 13 类告警、5 套主题、可自定义 Dock | 安全中心**未做**（根 README 状态表仍是 ⬜）；主题是 M3 + 动态取色 + OLED 纯黑；M3/M4/M5 是**规划**，不是当前进度 |
| 07 / 08 | 设计系统 / 界面规格 | 早期配色与逐屏布局 | #282~#285 多轮 UI 重做后已明显不符；#285 作废了 `xxxContainer` 交叉与 alpha 分档，改 M3 fixed accent roles |
| 09 / 17 | 安全 / 稳定性 | 日志与全局崩溃兜底 | #291 才把 release 下裸 `Log` 收敛到 `VaultixLog`；#289 实测**工程没有全局 `CoroutineExceptionHandler`**，崩溃可取证（S6）仍是待决项 —— 蓝图里的「兜底」是设计要求，不是现状 |
| 全 19 篇 | — | 2026-09-07 定稿 | 最新稳定版 **v0.13.2**（2026-11-14）；当前状态一律看 `.ai/README.md` 顶部 |

---

## 3. 该信哪份（速查）

| 想找 | 去哪 |
|---|---|
| 当前版本 / 上一轮改了什么 / 发布结论 | [`.ai/README.md`](../.ai/README.md) 顶部「🕐 最新状态」 |
| 长期约定、硬约束、发版纪律 | [`.ai/MEMORY.md`](../.ai/MEMORY.md) |
| 用户拍板的定稿（别重新论证） | `.ai/decisions/` |
| 已知坑（现象 → 根因 → 解法） | [`.ai/ISSUES.md`](../.ai/ISSUES.md) 索引 → `.ai/issues/0x-*.md` 分篇 |
| 模块 / 目录 / 依赖地图 | [`.ai/conventions/8.10-架构与目录地图.md`](../.ai/conventions/8.10-架构与目录地图.md) |
| 发布产物命名形状 | [`.ai/conventions/8.12-发布产物命名.md`](../.ai/conventions/8.12-发布产物命名.md) |
| 待办 / 待真机验收 | [`progress/next-steps.md`](./progress/next-steps.md) |
| 已冻结的逐轮进度留痕 | [`progress/current-status.md`](./progress/current-status.md)（**只读**） |
| 已发布版本的变更说明 | [`release-notes/`](./release-notes/v0.13.2.md)（按版本冻结） |

---

## 4. 维护约定

- **发版时**只新建 `release-notes/vX.Y.Z.md`，并同步 `VERSION`、`.ai/README.md` 顶部、
  `next-steps.md` 顶部状态表三处；**不回写**已发布的说明。
- **版本号、门禁数量、真源指针这类易变事实**，尽量用「以某文件 / 某页面为准」的指针表达，
  不在多份文档里各写一份 —— 本次整理修掉的根 README `0.13.0` / `8 个脚本`、
  `next-steps.md` 的 `v0.12.0` 都是这么漂掉的。
- **历史存档不改写**：`.ai/SESSION-*`、`progress/current-status.md`、
  `progress/next-steps-archive-*`、`progress/audit/`、按日期的审计报告只加标注、不改正文。
- 新增长期约定写 `.ai/conventions/8.x` 并更新 8.10 索引；新增坑写 `.ai/issues/0x-*`
  并同步 `.ai/ISSUES.md` 与 `check_issues_index.py` 的计数。
