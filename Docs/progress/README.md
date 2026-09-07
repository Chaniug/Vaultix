# 项目进度

> 本文件夹是**开发进度与协作的单一事实来源（SSoT）**，用于对齐"做到哪一步、下一步该干什么"。
> 格式固定，便于人工查找与 **AI 接力**。

## 文件说明

| 文件 | 用途 | 更新时机 |
|---|---|---|
| `README.md` | 本说明 + 快速导航 | 结构变化时 |
| `environment.md` | 环境配置与构建策略 | 环境或 CI 变更时 |
| `current-status.md` | 当前进度快照 | 每完成一个任务 |
| `next-steps.md` | 下一步任务清单 | 每次调整计划 |
| `decisions.md` | 关键决策记录 | 每次拍板 |

## 状态标记约定

`TODO` → `DOING` → `DONE`，受阻为 `BLOCKED`（需写明阻塞原因）。

## AI 接力指引

1. 先读 `.ai/MEMORY.md`（项目长期约定）与 `.ai/SESSION-*.md`（近期会话）
2. 再读本文件夹的 `current-status.md` 与 `next-steps.md`
3. 完成任务后**必须**更新 `current-status.md` 与 `next-steps.md`
4. 遇到坑写入 `.ai/ISSUES.md`
