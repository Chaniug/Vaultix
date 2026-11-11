# 下一步任务清单

> 2026-11-10 · **入口文件**：只放「当前状态 + 待办 + 归档索引」。
> 已完成的历史按时间段归档（见文末），**需要追溯时再打开，不要通读**。
> 上一轮完整记录 → [`.ai/README.md`](../../.ai/README.md)「🕐 最新状态」。
>
> ⚠️ `.ai/archive/` 里 9 月的会话记录引用了本文件的若干小节（如「★最高优先」
> 「Bastion 对齐批次」「第二十六轮清单」），那些内容现已归档到 archive-4/5/6 ——
> 按关键词在归档里检索即可，本文件不再承载。

---

## 当前状态（2026-11-10）

| 项 | 值 |
|---|---|
| 版本 | **v0.12.0**（Latest · 混淆包 6.5 MB · versionCode 12000） |
| 上一轮 | UI 精装修：图标 / 底栏玻璃 / 「+」面板 / 卡包 / 库列表切库 / 启动帧 |
| 门禁 | detekt · 编码 · 图标几何 · assembleFullDebug · 单测 · preview 全绿 |
| 整体排查 | 2026-11-11 · [报告](audit/project-audit-2026-11-11.md)：43 个依赖坐标中 **29 项已最新**、3 项禁单独升、**2 类**真违规（订正后）、3 项质量基建缺失（属「要建」） |
| 不需要修 | 2026-11-11 · [判定方案](audit/no-fix-register-2026-11-11.md) + [52 条登记](audit/evidence/no-fix-entries-2026-11-11.md)；门禁 `check_no_fix_register.py` 已接 `ci-docs.yml` |

## 待真机验收（v0.12.0）

- [ ] 玻璃在**深色 / OLED 纯黑**下「淡」是否过头（填充 0.38 / 0.52、模糊 16dp）
- [ ] 库卡**色调底 0.22** 会不会过浓；面板主卡水印 `alpha 0.13` 是否合适
- [ ] 启动 V 的**显眼程度**（已从 96dp 蓝方块改为单色剪影 + 180ms 淡入）
- [ ] 面板主卡水印与网格卡剪影是否读作**同一个系统**
- [ ] **2.0x 字号**下主卡 + 4 格 + 工具条是否一屏放得下
- [ ] **24dp 通知栏**里折纸 V 是否仍可辨认

---

## 待办

### P1 · 质量基础设施

> **2026-11-11 整体排查已核实**（[报告](audit/project-audit-2026-11-11.md) §3 / 附录 C）：
> Baseline Profile **确认缺失**（全仓零命中）、Gradle 配置缓存 **确认未启用**
> （CI 每条 gradle 命令都带 `--no-configuration-cache`）、Kover **只在根 `apply false`、未接 CI**。
> 旧注释「未逐项核对是否已完成」就此前三项**已作废**；ViewModel 单测仍待核。

> ⚠️ **2026-11-11 补**：任何整体排查 / 依赖升级 / 规范整改**开工前先读**
> [「不需要修」登记表](audit/evidence/no-fix-entries-2026-11-11.md)（52 条，判据见
> [no-fix-register](audit/no-fix-register-2026-11-11.md)）。产出的每条待修项先与登记 ID 比对，
> 命中即引用登记，不要重写理由、更不要重新列成待办（有 `check_no_fix_register.py` 门禁）。

- [ ] **依赖解析结果纳入 CI 门禁**（最高优先，先于任何升级）：施工单见报告附录 C · S1
- [ ] **生产 3 处 `!!` 清零**（P1，会 NPE 崩溃，排在日志之前）：报告 §2.2 / §4 · S3
- [ ] **release 下 6 处绕过 `VaultixLog` 的直接 `Log` 收敛**（P1，由 P0 下调：不崩溃、
      不丢数据，属日志红线与信息泄露风险；`BuildConfig.DEBUG` 由 buildType 决定，不由分支决定）：
      报告 §2.2 / §6 订正
- [ ] Baseline Profile：生成 `baseline-prof.txt`，覆盖冷启动 / 解锁 / 列表 / 详情 + autofill
- [ ] Gradle 配置缓存：先验 `detekt --configuration-cache`，可用再开
- [ ] Kover 覆盖率基线接入 CI（阈值先取实测值，不一步设 80%）
- [ ] `ViewModel 单测` / `:app` 单测用例补强 —— **本轮未核对，保留待核**

### P2 · 已判推迟（不是待办，勿重新排期）

> **WorkManager 周期同步 + 网络约束 —— 已判推迟，收益≈0**
> （[`decisions.md`](decisions.md)「周期同步 M1 判推迟」；登记为 [C01](audit/evidence/no-fix-entries-2026-11-11.md)）：
> 进程存活时 APP_RESUME / PAGE_ENTER 已覆盖「打开即最新」；进程被杀后会话密钥在内存
> （重启必锁），周期任务无可同步内容。编排器 PERIODIC 已预留，
> **触发转修条件**：引入本地 KDBX 离线写队列（有可同步内容），或用户要求后台自动同步。
> `Docs/17` §3.3：禁止常驻轮询。

### 已知未决（接力者注意）

- 快速解锁 payload 在 `SecureCredentialStore`（key `local_unlock_key::<vaultId>`），
  开关在 DataStore；删除 KEK 仅由系统指纹变更触发（单库 disable 只删 payload + 开关，
  多库时 KEK 共享保留 —— 未来多库需「全库清空」入口）。
- 同一服务器仅一个账号（`vaultId = server`）；设备 id 存 `SecureCredentialStore`
  （卸载即换，服务器端会累积旧设备记录，属正常）。
- Detekt 用 `2.0.0-alpha.6`；2.0 稳定后升级需重新生成默认配置核对。

---

## 文档债（2026-11-10 盘点）

- [x] **本文件拆分**：3592 行 / 280 KB → 入口 + 6 份归档（本轮完成）
- [ ] 6 个 `SESSION-*.md` 超 300 行（最大 **768 行** = `SESSION-2026-11-03.md`）。
      ⚠️ 记忆明确「存量未回溯合并 ⇒ **不能盲删**」，拆分要逐段回溯，是独立工程。
- [ ] `Docs/` 根目录 19 个设计期文档已滞后于实现（`08-界面设计规格.md` 33 KB）——
      README 已有已知标记；若要处理，应先定「哪几份还算真源」。

---

## 历史归档（按时间倒序，**勿通读**）

| # | 时间段 | 文件 | 规模 |
|---|---|---|---|
| 1 | 2026-11-05 ~ 10-22 | [next-steps-archive-1.md](./next-steps-archive-1.md) | 574 行 |
| 2 | 2026-10-22 ~ 09-29 | [next-steps-archive-2.md](./next-steps-archive-2.md) | 574 行 |
| 3 | 2026-09-29 ~ 09-15 | [next-steps-archive-3.md](./next-steps-archive-3.md) | 567 行 |
| 4 | 2026-09-15 ~ 09-12 | [next-steps-archive-4.md](./next-steps-archive-4.md) | 612 行 |
| 5 | 2026-09-12 ~ 09-11 | [next-steps-archive-5.md](./next-steps-archive-5.md) | 569 行 |
| 6 | 2026-09-11 ~ 09-08 | [next-steps-archive-6.md](./next-steps-archive-6.md) | 565 行 |

> ⚠️ 归档每份 565–612 行，**超 §7.1 的 300 行上限**——这是刻意的：它们是
> 「按时间段完整的完成记录」，对半切会破坏叙事连续性；入口已声明「勿通读」。
> 若确实需要严格 300 行，切点都在 `##` 边界，可直接对半拆。

### 已过期的旧待办（仅留档，原文见 archive-6 尾部）

- ~~`P0 · M1 收尾（剩余）`~~ —— 真机回归清单（装 `f0f5df6` preview）。M1 早已完成。
- ~~`P3 · M2（KDBX）`~~ —— `data:kdbx` 引擎（kotpass）。KDBX 已实现。
- ~~`P2 · 自动化`~~ —— 与上方 WorkManager 那条重复，已合并。
