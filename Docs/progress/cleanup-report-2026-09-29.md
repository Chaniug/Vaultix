# 项目清理报告（2026-09-29）

> 用户指令：「顺手也清理一下这个项目中无用过期的文件产物，日志等内容」。
>
> ⚠️ **本文件只是清单，不含任何删除动作。** 请勾选后我再执行。
> 立场：**先扫描、出报告、等确认**——`git clean` / `rm -rf` 这类命令在这台机器上
> 曾误伤过（见 `.workbuddy/memory` 的 adb 章节），不擅自执行。

---

## 一、可以直接删（构建产物，可 100% 由源码再生）

| # | 路径 | 体积 | 说明 | 删了会怎样 |
|---|---|---|---|---|
| 1 | `build/` | **2.0G** | 根项目构建输出 | 下次构建重生成 |
| 2 | `app/build/` | **593M** | app 模块构建输出（含 34 个 APK 历史） | 下次构建重生成 |
| 3 | `core/*/build/` 合计 | **~55M** | 7 个子模块构建输出 | 同上 |
| 4 | `data/*/build/` 合计 | **~73M** | 3 个子模块构建输出 | 同上 |
| 5 | `domain/build/` | **5.4M** | 同上 | 同上 |
| 6 | `.gradle/` | **67M** | Gradle 项目级缓存 | 首次构建变慢（会重建） |
| 7 | `.kotlin/` | 未跟踪 | Kotlin 增量编译会话缓存（**新出现，未 gitignore**） | 同上 |

> **合计约 2.9G**。⚠️ 建议保留 `.gradle/`（删了下次构建要重下依赖），其余可删。

---

## 二、可以删（过期过程产物）

| # | 路径 | 体积 | 日期 | 说明 |
|---|---|---|---|---|
| 8 | `.workbuddy/autofill-capture.log` | **75M** | 09-09 | 20 天前的一次性抓取日志 |
| 9 | `.workbuddy/autofill-edge.log` | **55M** | 09-09 | 同上 |
| 10 | `.workbuddy/artifacts/` | **32M** | 09-13/14 | 一批截图/录像/UI dump（`rec_manual.mp4` 19M、多个 `ui_*.xml`、`evidence_*.png`） |

> ⚠️ **`.workbuddy/artifacts/perf-report-2026-09-13.md` 是唯一有长期价值的文件**——
> 那是性能排查的**结论报告**，建议**先取出保留**（可移入 `Docs/`），再删目录。
> ⚠️ `.workbuddy/memory/`（460K）**绝对不动**——那是持久记忆。

---

## 三、**不要删**（有意留存）

| 路径 | 理由 |
|---|---|
| `.workbuddy/memory/` | 持久工作记忆（项目约定明确） |
| `Docs/progress/audit/evidence/2026-09-28-*.log` | **取证记录**（12K），排查结论的证据链 |
| `.ai/` | 记忆真源 |
| `Docs/progress/*.md` | 工作单/进度 |
| `reference/` | 参考文档（78 个 md） |
| `keystore.properties` / `local.properties` | 本地配置，**含签名信息** |

---

## 四、设备侧日志（需连 adb 才有意义）

设备上曾录制的 logcat：

| 路径 | 体积 | 状态 |
|---|---|---|
| `/sdcard/vx-live.log` | 377M | 已停写（09-29 17:14） |
| `/sdcard/vxbr.log` | 4G | 已停写（09-29 17:27） |
| `/sdcard/heartbeat.log` | 22M | —— |

> ⚠️ 本次 `adb devices` **无设备连接**（无线端口轮换/未开）。等你需要时再连；
> 若已无分析价值，可在设备上直接 `rm`（**注意：那 4G 会占满存储**）。

---

## 五、建议的 .gitignore 补充

`.kotlin/` 当前**未被忽略**（`git status` 显示为未跟踪）。建议加一行：

```
.kotlin/
```

---

## 六、执行方式（等你勾选）

- 若你回「**1-10 全删**」→ 我执行 `rm -rf` 上述第 1-10 项（**先备份 perf-report**）。
- 若只要清构建产物 → 「**只删 1-7**」。
- 若还要清设备日志 → 需先重连设备。

⚠️ 无论哪种，**`.workbuddy/memory/` 与取证日志都不在删除范围**。

---

## 七、✅ 执行结果（2026-09-29 21:3x 已完成）

**用户指令**：「你按照优先级完成1234吧」。
**执行范围**：第 1-5、7、8、9、10 项 —— **保留第 6 项 `.gradle/`**（按本报告第一节的建议：
删了下次构建要重下依赖）。

**前置保护**：唯一有长期价值的文件已先救出 ——
`perf-report-2026-09-13.md` → `Docs/progress/perf-report-2026-09-13.md`
（**6713 字节，逐字节校验一致**），并已 `git add` 纳入版本管理。

| 项 | 路径 | 结果 |
|---|---|---|
| 1-5 | `build/` · `app/build/` · `core/*/build/` · `data/*/build/` · `domain/build/` | ✅ 已删 |
| 7 | `.kotlin/` | ✅ 已删（`.gitignore` 已含该行，见第四节建议） |
| 8-9 | `.workbuddy/autofill-capture.log`(75M) · `.workbuddy/autofill-edge.log`(55M) | ✅ 已删 |
| 10 | `.workbuddy/artifacts/`(32M，含 19M `rec_manual.mp4`) | ✅ 已删（报告已救出） |
| 6 | `.gradle/`(67M) | ⏸ **有意保留** |

**保留项核证（删后复查，均在）**：
- ✅ `.workbuddy/memory/`（持久记忆）
- ✅ `Docs/progress/audit/evidence/2026-09-28-bprime-verify-VaultixAutofill.log`（取证链）

**未执行（有意留待）**：第四节**设备侧日志**（`/sdcard/vx-live.log` 377M ·
`/sdcard/vxbr.log` **4G** · `/sdcard/heartbeat.log` 22M）——
本次 `adb devices` **无设备连接**，需重连后再处置。

**合计释放约 2.9G**（D 盘可用 315G）。

⚠️ **副作用已知**：下次构建需重建 `build/` 输出（已保留 `.gradle/`，无需重下依赖）。
