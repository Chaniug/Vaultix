# docs 导航 · 接力入口

> **本文件是给接力的 AI / 开发者看的入口**。GitHub 打开 `docs/` 目录会自动渲染它。
>
> ⚠️ 下面「当前状态」一节会过时 —— 接力时请以 `git log` 和 GitHub Actions 的实时结果为准，
> 本文只提供「从哪看起」的地图和踩过的坑。
>
> 最后更新：2026-09-06

---

## 一、项目一句话

**bastion** —— Android 开源的 Bitwarden 兼容密码管理器，同时支持 KeePass（.kdbx）、
Passkey / Credential Manager、TOTP 验证器、自动填充。Jetpack Compose + Room + Kotlin，
单模块 `Bastion/app`，minSdk 26 / targetSdk 35 / compileSdk 37。

仓库：`git@github.com:Chaniug/bastion.git`（推荐 SSH，比 HTTPS 稳）

---

## 二、🔥 接力先看这里（当前状态快照）

### 分支

| 分支 | 提交 | 说明 |
|---|---|---|
| `dev` | `fb8dc12` | **默认开发分支**，所有开发/修复/测试都在这里 |
| `main` | `28eb875` | 落后 dev **2 个提交**，**尚未合并**（等 dev 真机验证通过后再合） |

dev 领先 main 的 2 个提交：

```
fb8dc12 revert(设置): 撤销版本与更新对话框自动检查，改回手动触发
b344151 feat(设置): 版本与更新对话框信息增强（版本卡片 / 发布日期 / APK 大小 / 更新日志卡片）
```

> 按仓库准则：**dev 验证没问题后才合 main**。合并前请确认这 2 个提交都已真机验证。

### CI

最近一次 `Android CI debug`（run `34025520525`，对应 `fb8dc12`）：

- ✅ 全绿，约 5.7 分钟
- 单测：`total=680 failed=0 baseline=0 verdict=PASS`
- `build_failed=false`

⚠️ **注意**：CI 里单测是 **non-blocking**，绿 ≠ 测试真跑过。查真实数字：

```bash
gh -R Chaniug/bastion run view <run-id> --log | grep -aoE "total=[0-9]+ failed=[0-9]+.*verdict=[A-Z]+"
```

### 发布物

| 类型 | 版本 |
|---|---|
| 预览版（debug，可装真机） | `Development Preview (build.202609060949)` |
| 稳定版 | `Bastion v1.0.1601`（2026-09-06 发布，含手动同步预热后台化等修复） |

### 进行中的工作

1. **待办清单 13 项**：见 [`性能优化待办-2026-09.md`](./性能优化待办-2026-09.md)（按「影响 × 确定性」排序）
2. **待拍板（未动）**：passkey origin 校验对齐 Bitwarden 的 3 个决策，
   见 [`passkey-origin校验-对齐bitwarden-方案-2026-09.md`](./passkey-origin校验-对齐bitwarden-方案-2026-09.md)
3. **待确认**：`BitwardenSyncWorker.schedulePeriodicSync()` 和
   `AutoBackupManager.scheduleAutoBackup()` 定义了但**全项目零调用点**
   —— 需确认是刻意未启用还是功能漏接（见排查报告第六节）

---

## 三、文档地图

### 🚀 接力优先读这 3 篇

| 文档 | 用途 |
|---|---|
| [`性能优化待办-2026-09.md`](./性能优化待办-2026-09.md) | **待办总表 13 项**，每项附位置、根因、判据。想知道「接下来做什么」看这个 |
| [`性能排查报告-2026-09.md`](./性能排查报告-2026-09.md) | 内存/耗电/卡顿全量排查，含**11 项「已确认健康、不要再动」**——避免重复劳动 |
| [`架构与路线图.md`](./架构与路线图.md) | 整体架构 |

### 性能专题（按时间线）

| 文档 | 内容 |
|---|---|
| [`性能排查报告-2026-09.md`](./性能排查报告-2026-09.md) | 2026-09-06 全量排查（Compose 报告 + Lint 基线 + 模式扫描） |
| [`性能优化待办-2026-09.md`](./性能优化待办-2026-09.md) | 13 项待办总表 |
| [`省电与内存优化计划-2026-09.md`](./省电与内存优化计划-2026-09.md) | 真机实测（荣耀 BKQ-AN00 / API 37）：`canAuthenticate` 每秒 81 次、`[anon]` 133MB 定性、`scudo` 增长 |
| [`vault冷启动加载-修复记录.md`](./vault冷启动加载-修复记录.md) | 冷启动加载优化，含 §六「同步预热后台化」修复记录 |
| [`vault冷启动加载-对标调研.md`](./vault冷启动加载-对标调研.md) | 冷启动对标官方 Bitwarden |
| [`解锁后加载耗时-排查记录.md`](./解锁后加载耗时-排查记录.md) | 解锁耗时排查 |
| [`解锁后加载耗时-对标落地设计.md`](./解锁后加载耗时-对标落地设计.md) | 解锁耗时落地设计 |
| [`passwordlistcontent-拆分计划-2026-09.md`](./archive/passwordlistcontent-拆分计划-2026-09.md) | ART JIT 方法指令拆分（**已收官，已归档**），§10.5 有 2 个待测动画项 |
| [`解锁后加载耗时-排查记录.md`](./archive/解锁后加载耗时-排查记录.md) · [`对标落地设计.md`](./archive/解锁后加载耗时-对标落地设计.md) | 解锁后加载耗时（**已收官，已归档**） |

### 外部对标与专项设计

| 文档 | 内容 |
|---|---|
| [`借鉴分析-2026-09.md`](./借鉴分析-2026-09.md) | Bitwarden / Keyguard / Monica 三项目改动分析，逐项对照 bastion 现状 |
| [`passkey-origin校验-对齐bitwarden-方案-2026-09.md`](./passkey-origin校验-对齐bitwarden-方案-2026-09.md) | 基于本地克隆的 `bitwarden/android` 真实源码，3 个决策待拍板 |
| [`passkey备份完整性-设计计划-2026-09.md`](./passkey备份完整性-设计计划-2026-09.md) | passkey 备份完整性设计（推荐选项 A） |
| [`上游对标与优化计划-2026-09.md`](./上游对标与优化计划-2026-09.md) | 上游对标 |
| [`bitwarden同步与密码库生态.md`](./bitwarden同步与密码库生态.md) | 同步与多密码库生态 |
| [`自动填充与浏览器兼容.md`](./自动填充与浏览器兼容.md) | 自动填充兼容 |

### 工程与流程

| 文档 | 内容 |
|---|---|
| [`分支合并与发版流程.md`](./分支合并与发版流程.md) | **发版流程**，动 main 前必读 |
| [`local-dev-编译环境搭建.md`](./local-dev-编译环境搭建.md) | **本地构建环境**（下一个 AI 大概率要重搭，先看这个） |
| [`GITHUB_NETWORK_NOTE.md`](./GITHUB_NETWORK_NOTE.md) | GitHub 网络/IP 坑，推送失败先看这个 |
| [`lint债务清理计划.md`](./lint债务清理计划.md) | Lint 债务（37KB，最大的一篇） |
| [`compose-material3-api-migration-plan-2026-09.md`](./compose-material3-api-migration-plan-2026-09.md) | Material3 API 迁移 |
| [`dependency-upgrade-plan-2026-08.md`](./dependency-upgrade-plan-2026-08.md) | 依赖升级 |
| [`项目文档-提交与重点Bug修复总结.md`](./项目文档-提交与重点Bug修复总结.md) | 历史提交与重点 Bug |
| [`android端优化与杂项.md`](./android端优化与杂项.md) | 杂项优化 |

---

## 四、仓库基本准则（摘要，必读）

1. 改动遵循最新开发规范；
2. **`dev` 是默认开发/修复/测试分支**，dev 验证没问题后再合 `main`；
3. **排错主要看 GitHub Actions 日志**；
4. 提交后观察 CI，报错及时修复并自动总结；
5. **重点改动或不明白的部分，先提计划等确认再改**；
6. 未完成的重点计划落盘 `/docs`，方便其他 agent 接力；
7. GitHub API 的 IP 与 github.com 不同 —— 推送失败检查直连 IP / 代理 / hosts，**真实 IP + SSH 可解决**，拉取慢优先用 CDN 镜像；
8. 预览版装真机测试：**荣耀 BKQ-AN00 / Android 17 / API 37**。

---

## 五、本地环境坑（血泪，先看再动手）

| 坑 | 现象 | 解法 |
|---|---|---|
| 系统 Gradle 与 AGP 不兼容 | 构建直接失败 | 用 `/tmp/gradle951/gradle-9.5.1/bin/gradle` |
| Kotlin `InternalError: unsafe memory access` | 编译崩溃 | 加 `-Dkotlin.compiler.jvm.fast.jar.fs.mode=disabled` |
| 本地 JDK 20 vs CI JDK 21 | 约 53 条 `ClassFormatError` / `NoClassDefFoundError` | **本地噪音，CI 不受影响**，别为它改代码 |
| 杀 daemon | — | `ps aux \| grep -E "GradleDaemon\|KotlinCompileDaemon" \| awk '{print $2}' \| xargs -r kill`<br>⚠️ **不可用 `pkill -f gradle`，会自杀** |
| **release 构建本地失败** | 卡在 `packageReleaseResources`：<br>`Failed to create MD5 hash` | Gradle 9.5.1 + AGP 的本地问题，CI 正常。要 Compose 报告改用 debug |
| **Compose 报告增量编译为空** | CSV 只有表头 | 必须全量重编：<br>`gradle :app:cleanCompileDebugKotlin :app:compileDebugKotlin` |
| `gh api` 不支持 `-R` | `unknown shorthand flag: 'R'` | `gh api` 用完整路径 `repos/Chaniug/bastion/...`；<br>`gh run list` 才支持 `-R` |
| zsh 的 glob | `--include=*.kt` 报 `no matches found` | 加引号：`--include="*.kt"` |
| `git push` 报 up-to-date 但 fetch 显示旧值 | 本地跟踪引用未刷新 | 用 `git ls-remote origin <branch>` **直问服务器**，<br>再用 `git update-ref refs/remotes/origin/<b> <sha>` 修正 |

### 常用命令

```bash
# 编译 + 守卫测试（改同步/生物识别相关逻辑必跑）
cd Bastion
GRADLE=/tmp/gradle951/gradle-9.5.1/bin/gradle
$GRADLE :app:compileDebugKotlin -Dkotlin.compiler.jvm.fast.jar.fs.mode=disabled --no-daemon
$GRADLE :app:testDebugUnitTest --tests "*BiometricUnlockRegressionGuardTest*" \
  -Dkotlin.compiler.jvm.fast.jar.fs.mode=disabled --no-daemon

# 生成 Compose 编译器报告（排查卡顿用）
rm -rf app/build/compose_compiler app/build/compose_metrics
$GRADLE :app:cleanCompileDebugKotlin :app:compileDebugKotlin \
  -Dkotlin.compiler.jvm.fast.jar.fs.mode=disabled --no-daemon
# 报告在 app/build/compose_compiler/*.csv 和 compose_metrics/*/app-module.json
```

---

## 六、验证方式（改完怎么算通过）

按改动类型分层：

1. **编译**：`:app:compileDebugKotlin` 通过，无新增警告；
2. **守卫测试**：项目大量使用**源码文本守卫断言**（断言源码必须/不得包含某字符串），
   改了对应文件必须跑对应 GuardTest，例如：
   - `BiometricUnlockRegressionGuardTest`（同步预热、生物识别解锁）
   - `MultiPasswordSaveRegressionGuardTest`（改了 `PasswordListContent`）
   - `TotpSwipeSelectionRegressionGuardTest`（改了验证器滑动）
3. **CI**：dev 绿。**注意单测 non-blocking，必须挖 `total= failed=` 真实数字**；
4. **真机**（荣耀 BKQ-AN00 / API 37）：装预览版，按各文档的「验收」小节复测。
   滚动卡顿的判据见排查报告第八节（`ScrollPerf` 三条件）。

---

## 七、排查手法沉淀（避免重走弯路）

- **判断泄漏靠曲线不靠快照**：`冷启动 T0 / 静置 T1 / 长跑后` 三点对比，单点数字无意义。
- **Compose 报告要看 `restartable`，不能只看 `skippable`**：
  `skippable=0` 的 93 个里 `restartable=0`（是格式化/取色辅助函数，不参与重组）——不是热点。
  真正指标是 **`restartable=1 且 skippable=0`**，本项目为 **0**（StrongSkipping 已生效，重组很干净）。
- **`grep ... | head -N` 的截断会漏掉命中导致误判**：找齐调用点要去掉 head 全量列出
  （历史教训：`TotpListContent` 这处耗电点曾被截断漏掉）。
- **看到 `while(true)` 先判断有界还是常驻**：本项目 13 处里只有 TOTP 心跳是常驻，
  其余是流拷贝（`break`）或手势追踪（`awaitPointerEvent`）。
- **改性能前先找对称性**：同一功能的两条代码路径写法不一致，往往是历史遗留 bug 高发区
  （如「静默同步已异步、手动同步还是同步」）。
- **先算清「返回值到底有没有人用」**：很多「不敢改」的性能改动，卡点是误以为返回值被依赖。
  算清口径（子集/全集关系）常能发现零语义损失。
- **别急着下结论，先读注释**：`TotpTicker.kt`、`BitwardenOfflineSecretCache` 等处的
  注释写了优化前因后果，读了才知道哪些坑已填。
- **ART JIT 的唯一权威信号**是 logcat 的 `Method exceeds compiler instruction limit`，
  **不是文件行数**（Kotlin 局部函数编译为独立合成方法，不计入主函数指令数）。
