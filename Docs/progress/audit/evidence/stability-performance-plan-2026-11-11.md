# 附录 C · 稳定性 / 性能提升施工单（2026-11-11）

> 主报告：[`../project-audit-2026-11-11.md`](../project-audit-2026-11-11.md) §4–§6。
> **性质：待施工的方案，不是已完成的改动。** 本轮只做排查与报告，未改任何生产代码。
> 每条都给「改动点 → 实现 → 验收 → 变异验证 → 回滚」，可直接拆成独立提交。

---

## S1 · 依赖解析结果纳入 CI 门禁（最高价值）

**为什么排第一**：#239 的崩溃在编译、单测、detekt、15 个静态门禁下**全部通过**，
只有真机一进 `OutlinedTextField` 才炸。根因是 `bom` 只是约束、不是命令，
任何传递依赖都能顶掉它 ⇒ **必须检查解析结果，而不是声明**。

### 改动点

| 文件 | 动作 |
|---|---|
| `.ai/tools/check_compose_alignment.py` | 新建 |
| `.ai/tools/tests/selftest_compose_alignment.py` | 新建（正反用例） |
| `.github/workflows/ci-debug.yml` | 在 `Run detekt` 之后加一步 |

### 实现

1. CI 步骤先产出解析快照：
   ```bash
   ./gradlew :app:dependencies \
     --configuration fullDebugRuntimeClasspath \
     --no-configuration-cache > "$RUNNER_TEMP/compose-deps.txt"
   ```
2. 脚本解析 `androidx.compose.foundation:foundation -> X`、
   `androidx.compose.material3:material3 -> Y`、`ui` / `runtime` 的选中版本；
   兼容 `+---` / `\---` 树形前缀与 `(n)` 重复标记。
3. 断言三条：
   - foundation 与 ui / runtime 的 **minor 相同**；
   - foundation 的 minor 与 `material3` 编译基线（当前 `1.11.x`）**一致**；
   - 出现 `By conflict resolution` / `By constraint` 指向 `org.jetbrains.compose.*` 时，
     打印来源链并**失败**（这正是 #239 的加害路径）。
4. 自检脚本喂两份 fixture：`good.txt`（当前解析）应通过、`bad.txt`（1.12.1 + 冲突仲裁）应失败。
   ⚠️ 项目 `.ai/conventions/8.6` 记过教训：**启发式探针必须配正反用例**，否则「它说 OK」与「真没问题」分不清。

### 验收 / 变异验证

- 临时把 `composeBom` 改到 `2026.09.00`（**不改 material3**），CI 必须红在这一步 ⇒ 证明门禁有效；
- 改回 `2026.06.01` 必须绿；
- `markdownRenderer` 改到 `0.46.0` 同样必须红（验证「Android 变体 POM 的传递依赖」也能被抓到）。

### 约束与回滚

- 沙箱无 Android SDK / Gradle ⇒ 该脚本**只能放 CI**，不要尝试加进本地 15 个 `check_*.py` 的批量运行；
- 回滚 = 删 CI 那一步，不影响任何产物。

---

## S2 · 日志门面收敛 + release 零 DEBUG

### 改动点（6 处）

| 文件 | 行 | 现状 |
|---|---|---|
| `app/.../ui/settings/QuickUnlockController.kt` | 867 | `.onFailure { Log.d(TAG, "清除待重装标记失败：${it.message}") }` |
| `app/.../ui/unlock/UnlockViewModel.kt` | 926, 958, 960, 962 | `Log.d(TAG, "reseal $vaultId → ...")` 等 |
| `app/.../ui/settings/SettingsComponents.kt` | 460 | `Log.w(TAG, ...)`，Compose 组合阶段 |

### 实现

- 改成 `VaultixLog.d(TAG) { "..." }` / `.w(TAG) { "..." }` 的 **lambda 形式**：
  `VaultixLog.install(enabled = BuildConfig.DEBUG)` 在 release 下拼串前就返回，零开销。
- `resel $vaultId` 这类日志把**库标识**写进 logcat；按 `VaultixLog` KDoc 的红线，
  应改为不携带可识别库信息的表述（如只记结果枚举）。
- `AutofillLogger.kt:28,38` 已有 `BuildConfig.DEBUG` 保护，可保留，但建议也统一到门面；
  `VaultixApplication.kt:112-114` 是门面实现，**不动**。
- 新建 `.ai/tools/check_direct_log.py`：扫 `app/src/main` 的 `import android.util.Log`，
  白名单 = `VaultixApplication.kt` + `AutofillLogger.kt`，其余命中即失败。

### 验收

- release 包跑一遍解锁 / 快速解锁 / 设置页，`adb logcat` 中 `Vaultix` TAG **无 DEBUG 行**；
- `check_direct_log.py` 加进推送前门禁列表；
- 变异验证：临时在被白名单外的文件加一行 `Log.d(...)`，门禁必须红。

### 回滚

单文件 revert；无数据迁移、无状态变更。

---

## S3 · 生产 `!!` 清零

| 文件 | 行 | 建议 |
|---|---|---|
| `PasskeyCreateActivity.kt` | 509 | `selectedLoginId ?: return showUserFacingError(...)`，避免 NPE 打断凭据创建 |
| `PasskeyGetActivity.kt` | 495 | `clientDataHash ?: return ...`，签名前置校验前置到更早的分支 |
| `KdbxSyncOrchestrator.kt` | 198 | `fileSourceFactory(origin) ?: return SyncResult.UnsupportedOrigin(...)` |

各补 1 条空值路径单测。门禁：扩展 `check_compile_smells.py` 或新增一条
「`/src/main/` 中 `!!` 计数必须为 0」（测试目录豁免）。

---

## S4 · 分片上传退避改可取消

### 现状（先别重写）

`OneDriveGraphClient.kt` 的三处 `Thread.sleep(CHUNK_RETRY_DELAY_MS)`（`:429 / :604 / :794`，
常量 `:1130 = 500L`）位于 `upload()` 的 `withContext(Dispatchers.IO)` 调用链内：
`chunkedUpload` 是 `suspend`，`putChunkWithRetry` / `resumeStep` 是普通 `fun`。
⇒ **不阻塞主线程**，问题是占住 IO 线程且不可取消。

### 实现

1. `putChunkWithRetry` / `resumeStep` / `judgeResume` 调用链改为 `suspend`（只在 IO 链内传播，不扩散到 API 层）；
2. `Thread.sleep(ms)` → `delay(ms)`（`kotlinx.coroutines.delay`）；
3. 每个重试循环入口加 `currentCoroutineContext().ensureActive()`，让退出同步 / 切库能立刻中断上传；
4. **必须保留**：`MAX_STALLS` 防死循环（#236 的 OOM 修复）、412/401/403 不重试语义、
   条件写 `If-Match` / `If-None-Match` 行为。

### 验收

- `OneDriveGraphClientChunkRetryTest`、`OneDriveGraphClientWriteTest` 全绿（它们用 `runBlocking`
  验证真实请求形态，改成 `delay` 后仍应通过）；
- 新增「协程取消后不再发起下一次重试」用例；
- 真机：大文件上传中切到后台 / 切库，确认无残留请求、无 ANR。

### 回滚

单文件 revert；`delay` 与 `Thread.sleep` 行为等价（都是等待 500ms），无数据语义变化。

---

## S5 · 覆盖率从 0 到有基线

1. 在 `app`、`core/*`、`data/*` 的 `build.gradle.kts` 应用 `alias(libs.plugins.kover)`
   （当前只有根 `build.gradle.kts:16` 的 `apply false`）；
2. 根工程加 `kover { reports { ... } }` 聚合；
3. CI 加 `./gradlew koverXmlReportFullDebug --no-configuration-cache`，产物上传 artifact；
4. `koverVerify` 阈值**先取首次实测值**（例如「不低于当前值」），下一季度再上调；
   ⚠️ 不要一步设到规范要求的 80%，那会让门禁立刻失效、然后被长期忽略。

---

## P1 · Baseline Profile

1. 新增 `:baselineprofile` 模块（或 `androidx.baselineprofile` 插件），依赖
   `androidx.benchmark:benchmark-macro-junit4`；
2. `BaselineProfileGenerator` 覆盖 4 条旅程：
   **冷启动 → 解锁 → 条目列表滚动 → 条目详情**；
   再加一条 **autofill 冷启动**（`VaultixApplication.createdForAutofill` 已有标记可复用）；
3. 生成 `app/src/main/baseline-prof.txt` 并入库（当前全仓 `find -iname "*baseline*prof*"` 零结果）；
4. 验收：`StartupTimingMetric.timeToInitialDisplayMs`，中端机 P50 目标降 10%+；
   同时看 `FrameTimingMetric` 的列表滚动 `frameOverrunMs`；
5. 注意：**profile 必须随主要 UI 重构更新**，否则过期 profile 反而锁死旧代码路径；
   建议在 CI nightly 做一次「profile 是否过期」提示，不做硬门禁。

---

## P2 · 冷启动初始化分层

### 现状（`VaultixApplication.onCreate()` 串行 7 件事）

```
1 screenSecurityGuard.install(this)
2 VaultixLog.install(enabled = BuildConfig.DEBUG) { ... }
3 installKdbxDerivedKeyDiskCache(File(filesDir, "kdf_derived"))
4 ProcessLifecycleOwner.get().lifecycle.addObserver(autoLockController)
5 autoLockController.startCollecting()
6 lockManager.startObservingVaults()
7 lockManager.onAppCreated(isFirstCreation = true, createdForAutofill = ...)
```

### 分层约束（**顺序不能乱**）

| 层 | 项 | 约束 |
|---|---|---|
| 必须第一 | 1 | 源码注释已写明：晚一步就留下「能被截到敏感内容」的窗口 |
| 必须早于任何解锁 | 3 | autofill fanout 在进程启动后几毫秒就 decode，晚一步实跑 30s Argon2（#247） |
| 可延迟 / 可分流 | 2、4、5、6 | 诊断装配只在 DEBUG 有意义；锁态 / 库列表观察可按 `createdForAutofill` 分流 |

### 实现

- 用 AndroidX App Startup 的 `Initializer` 把 2/4/5/6 拆开；
- `createdForAutofill = true` 时只装配 autofill 必需子集，跳过 UI 相关的观察者；
- ⚠️ **绝不能**把 `startCollecting()` / `startObservingVaults()` 退回构造期自启 ——
  源码注释明确这是为了让单测能注入测试调度器 / 虚拟时钟，改回去会重演时序竞态；
- 验收：Perfetto / `Traceview` 冷启动 trace 看 `onCreate` 耗时；
  `AutoLockControllerTest` 等时序敏感用例必须仍绿。

---

## P3 / P4 / P5 概要

| 编号 | 第一步（可直接执行） | 判停条件 |
|---|---|---|
| P3 配置缓存 | `./gradlew detekt --configuration-cache` 单跑一次 | 可用 ⇒ 开 `org.gradle.configuration-cache=true` 并去掉 CI 的 `--no-configuration-cache`；不可用 ⇒ detekt 拆独立 job，其余任务启用 |
| P4 Compose | 统计全仓 `derivedStateOf`（当前 **2 个文件**）与 `LazyColumn key`（**10 处**），逐个审计筛选 / 排序 / 分组（#278）变化是否引发整屏重组 | 用 Layout Inspector 重组计数对比；补「切换分组前后条目总数不变」断言（#278 已证明 key 冲突会静默丢条目） |
| P5 PSL | 确认 `PublicSuffixData` 的分块拼接发生在 `object` 初始化还是首次匹配 | 若是类初始化 ⇒ 改 `lazy {}`；目标：autofill 冷启动不触发 PSL 拼接 |

---

## 阶段排期与通用纪律

| 阶段 | 内容 | 前置 |
|---|---|---|
| 0 | S2 + S3 + 版本注释漂移 + `.ai/ISSUES.md` 数字 | 无 |
| 1 | **S1**（护栏） | 无 |
| 2 | §1.1 六项低风险升级 | **必须在 1 之后**（升级前先有护栏） |
| 3 | P1 / S5 / P3 | 阶段 2 CI 绿 |
| 4 | S4 / P2 / P4 / P5 / §1.3 三项依赖专项 | 真机反馈驱动，各自独立 |

**纪律**：

1. **§1.3 的三项依赖（composeBom / material3 / markdownRenderer）绝不塞进任何一次「顺手升级」**，
   必须作为一个含业务代码改造的独立专项；
2. 每次只动一个变量，升级提交**不要夹带重构**（#239 的教训：一次 Dependabot 改动 + 一个 UI 库
   传递依赖，排查时要靠 `dependencyInsight` 才能拆开）；
3. 新增门禁一律配**正反用例**（`.ai/conventions/8.6` 的「门禁可以自证伪」教训）；
4. 全量门禁结论以 **CI 为准**（`.ai/conventions/8.6.0`：本地不做全量 test / assemble / 全模块 detekt）；
5. 判断 CI 结果看 `gh run view <id> --json conclusion`，**不要看管道退出码**。
