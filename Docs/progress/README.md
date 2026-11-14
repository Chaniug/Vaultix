# 项目进度 · 文档说明

> 本文件夹是**施工进度、决策与待办的记录处**，不是「当前状态」的唯一真源。
> **接力第一站**是 [`.ai/README.md`](../../.ai/README.md) 顶部「🕐 最新状态」（倒序、唯一真源）；
> **待办唯一真源**是 [`next-steps.md`](./next-steps.md) 顶部。
> 「东西在哪 / 该信哪份文档」见
> [`.ai/conventions/8.10-架构与目录地图.md`](../../.ai/conventions/8.10-架构与目录地图.md)。

## 文件说明

| 文件 | 性质 | 更新时机 |
|---|---|---|
| `README.md` | 本说明 + 导航 | 真源结构变化时 |
| `next-steps.md` | **待办唯一真源**（顶部当前状态表 + 待真机验收 + 归档索引） | 每次调整计划 / 每轮收工 |
| `decisions.md` | 关键决策记录 | 每次拍板 |
| `environment.md` | 构建环境与 CI 分工（仓库相关版本以 `gradle-wrapper.properties` / `libs.versions.toml` 为准） | 环境或 CI 变更时 |
| `current-status.md` | **已冻结的过程留痕**（最后更新 2026-11-01） | ❌ 不再更新，需要现状看 `.ai/README.md` |
| `*-plan.md` / `*-handoff.md` / `audit/` | 按日期冻结的施工工作单与审计报告 | ❌ 不回写成「当前」 |
| `next-steps-archive-*.md` | 已完成待办的历史归档 | 归档时追加，不改正文 |
| `../release-notes/v*.md` | **按版本冻结**的发布说明，Release 页正文直接读它 | 发版时新建，不改写已发布版本 |

## 状态标记约定

`TODO` → `DOING` → `DONE`，受阻为 `BLOCKED`（需写明阻塞原因）。
待真机验收项用 `- [ ]` / `- [x]`，判据写在清单里，**用户真机反馈才算闭环** ——
静态门禁 / CI 全绿不能替代（#290 / #291 都踩过这条）。

## AI 接力指引（2026-11-14 校正）

1. **先读 [`.ai/README.md`](../../.ai/README.md) 顶部「🕐 最新状态」** —— 当前版本、上一轮改了什么、
   真机验收欠账、发布结论都在这里；这是唯一真源，倒序排列。
2. 再读 [`.ai/MEMORY.md`](../../.ai/MEMORY.md)（长期约定 / 硬约束）与
   [`next-steps.md`](./next-steps.md)（待办）；遇到坑查
   [`.ai/ISSUES.md`](../../.ai/ISSUES.md) 索引，再跳对应 `issues/0x-*.md` 分篇。
3. 动手前按改动领域加载定稿：决策看 `.ai/decisions/`，长期约定看 `.ai/conventions/8.x`，
   具体清单见 `.ai/README.md`「接力流程」。
4. 收工后更新 `.ai/README.md` 顶部状态块与 `next-steps.md`；
   **不要再更新 `current-status.md`**（已冻结），也别改 `.ai/SESSION-*` 历史流水。

> ⚠️ 旧版本文件曾要求「先读 `.ai/SESSION-*`」「完成任务必须更新 `current-status.md`」，
> 那是 2026-09 的结构。**会话记录已归档、进度快照已冻结**，照旧指引会读到两周前的状态。
