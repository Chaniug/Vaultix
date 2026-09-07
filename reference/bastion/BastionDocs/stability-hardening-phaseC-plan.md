# 阶段 C：P1/P2 稳定性修复计划（待确认）

> 审查范围：`Bastion/app/src/main/java`
> 分支策略：所有改动在 `dev` 分支，每项单独 commit，提交后观察 GitHub Actions。
> 审查方法：Explore agent 中等深度静态审查 + 实际文件核实（非误报）。
> 状态：计划已拟定，含 P1×1 + P2×9，**未开始写业务代码**，等待确认范围。

---

## 0. 审查结论摘要

代码库在**资源管理**（`.use` / `try-finally` 覆盖完整）与**协程生命周期**（无 `GlobalScope`，Service 的 `serviceScope` 均在 `onDestroy` cancel，`CancellationException` 普遍正确 re-throw）上整体规范。
真正值得修的问题集中在两类：

1. **主线程阻塞式 IO/DB**（维度 4）：Activity / BroadcastReceiver / 自动填充热路径中直接用 `runBlocking` 读 DataStore 或 Room，无超时保护。配置慢/数据量大时卡顿甚至 ANR。
2. **共享可变状态缺 `@Volatile`**（维度 3）：单例对象中跨线程读写的 `var` 字段未加 `@Volatile`，存在内存可见性隐患（偶发漏记日志 / 状态错乱）。

> 参考基线：`ui/base/BaseBastionActivity.kt:52` 已用 `runBlocking { withTimeout(200) { settingsFlow.first() } }` 作为标准防护写法，Phase C 统一对齐该模式。

---

## 1. P1 — 主线程全表查询（ANR 风险，必修）

### C1. `PasskeyAuthActivity.onCreate` 主线程 `runBlocking` 全表加载

| 项 | 内容 |
|---|------|
| 位置 | `passkey/PasskeyAuthActivity.kt:193-211`（`onCreate` 内） |
| 现状 | `onCreate` 主线程中 `runBlocking { ... getAllPasskeysSync() ... }`；`getAllPasskeysSync()` 是 **Room 全表加载**（见 `PasskeyDao.kt`）。 |
| 问题 | Credential Provider 弹窗启动即主线程同步等磁盘全表查询；Passkey 数量增长后必然卡顿，**严重时 ANR**。且 onCreate 后续逻辑（finish / setContent）强依赖 `passkey` 是否为 null，改造需引入加载态。 |
| 修复方案 | 将 `onCreate` 的加载拆分为：① 先以默认/占位态 `setContent`（显示加载中）；② `lifecycleScope.launch(Dispatchers.IO)` 异步查库，完成后 `withContext(Main)` 更新 state；③ 根据结果决定展示 UI 或 `finish()` + 异常 Intent。保留现有 `getPasskeyByRecordId` → `getPasskeyById` → `getAllPasskeysSync` 三级回退逻辑，仅改为异步。 |
| 影响文件 | `passkey/PasskeyAuthActivity.kt` |
| 验证 | 手动：录入多（如 200+）条 Passkey，从系统凭据选择器触发认证，观察无 ANR、首次加载有 loading；GitHub Actions 跑测试。 |

---

## 2. P2 — 主线程 `runBlocking` 读 DataStore/DB（统一加超时保护）

### C2. 主线程 DataStore 读取缺 `withTimeout` 保护

以下位置均在主线程/热路径用 `runBlocking { settingsFlow.first() }` 读 DataStore，**无超时**（`BaseBastionActivity` 有，这里遗漏）：

| # | 位置 | 场景 | 风险 |
|---|------|------|------|
| C2.1 | `autofill_ng/AutofillPickerActivityV2.kt:402` | 验证前同步读取 autoLockMinutes | 卡顿 |
| C2.2 | `autofill_ng/builder/FilledDataBuilderNg.kt:36` | 自动填充**响应构建热路径**（binder/主线程） | 浮层卡顿/ANR |
| C2.3 | `autofill_ng/AccountFillPolicy.kt:37` | 同热路径读 separateUsernameAccountEnabled | 浮层卡顿/ANR |
| C2.4 | `receiver/LauncherEntryRepairReceiver.kt:23` | `BroadcastReceiver.onReceive` 主线程 | 升级后短暂卡顿（建议 `goAsync()`） |

**修复方案（统一）**：对齐 `BaseBastionActivity` 模式，改为
```kotlin
runBlocking { withTimeout(200) { settingsManager.settingsFlow.first() } }
```
`LauncherEntryRepairReceiver` 额外改用 `goAsync()` + 协程（BroadcastReceiver 主线程不宜长阻塞）。

### C3. Passkey 其余主线程 `runBlocking`（读设置/写库）

| # | 位置 | 场景 |
|---|------|------|
| C3.1 | `passkey/PasskeyAuthActivity.kt:364` | `requestPasskeyUserVerification` 读设置 |
| C3.2 | `passkey/PasskeyAuthActivity.kt:541,557` | `authenticateWithPasskey` 生物识别回调内写库 |
| C3.3 | `passkey/PasskeyCreateActivity.kt:572,785,848,956` | 创建流程多处读库/读设置 |

> 注：C3 改动量大于 C2，涉及把主线程 `runBlocking` 改为 `lifecycleScope.launch` + 回调编排。**建议作为第二阶段，确认后再做**。

### C4. `WebDavHelper.kt:3687` IO 线程 `runBlocking`

| 项 | 内容 |
|---|------|
| 位置 | `utils/WebDavHelper.kt:3687` |
| 现状 | `kotlinx.coroutines.runBlocking { keepassDao.insertDatabase(newKeePassDb) }` 处于已在 IO 上执行的恢复流程内。 |
| 问题 | 在 IO worker 上 `runBlocking` 阻塞该线程，破坏结构化并发（非主线程 ANR，但浪费线程/降低吞吐）。 |
| 修复方案 | 将外层函数改为 `suspend`，直接 `keepassDao.insertDatabase(...)` 调用，去掉 `runBlocking`。 |
| 影响文件 | `utils/WebDavHelper.kt` |

---

## 3. P2 — 并发可见性 `@Volatile` 遗漏（低风险明确修复）

### C5. `OperationLogger` 共享字段缺 `@Volatile`

| 项 | 内容 |
|---|------|
| 位置 | `utils/OperationLogger.kt:28-31`（`database` / `deviceId` / `deviceName` / `securityManager`），对比 32-33 行 `lastSnapshotCleanupAt` 已 `@Volatile` |
| 现状 | `init()`（主线程）写这 4 个字段；`log()` 在 `Dispatchers.IO` 协程（34 行 `scope`）中读（179、256 行）。 |
| 问题 | 内存可见性不一致：IO 线程理论可能读到 `database=null`，偶发漏记日志或走空分支。 |
| 修复方案 | 给 `database` / `deviceId` / `deviceName` / `securityManager` 加 `@Volatile`（对齐同文件已有写法）。 |
| 影响文件 | `utils/OperationLogger.kt` |

### C6. `ClipboardUtils` / `PasswordBatchTransferProgressTracker` 共享 var 无同步

| # | 位置 | 问题 |
|---|------|------|
| C6.1 | `utils/ClipboardUtils.kt:40` | `companion` 内 `clearClipboardJob: Job?` 的 `cancel()`+赋值无锁；若 `copyToClipboard` 被非主线程调用存在竞态（旧清除任务未取消）。改为 `@Volatile` 或 `AtomicReference<Job?>`。 |
| C6.2 | `ui/password/PasswordBatchTransferProgressTracker.kt:44,47` | `nextOperationId += 1` 与 `clearJob` 在 `Dispatchers.Default` 批处理协程无同步并发调用，operationId 可能重复/丢失。改用 `AtomicLong` / `AtomicReference`。 |
| C6.3 | `ui/password/PasswordBatchDeleteProgressTracker.kt` | 同 C6.2 模式（需确认同结构）。 |

---

## 4. 提交与验证策略

- 全部在 `dev` 分支；按 C1 → C2 → C4 → C5 → C6 → C3 顺序（P1 与低风险项优先，C3 较大改动置后）。
- 每项单独 commit + push，提交后**观察 GitHub Actions 状态**，报错即自动修复并总结。
- 验证：单元/插桩测试 + 手动（Passkey 多条目触发认证无 ANR；自动填充浮层无卡顿；剪贴板自动清除时序正确）+ GitHub Actions。

## 5. 待确认

1. **Phase C 整体范围**：是否按 C1→C2→C4→C5→C6→C3 全做？还是先只做 P1（C1）+ 低风险明确项（C2/C4/C5/C6），把 C3（Passkey 系列 runBlocking 协程化）留待单独确认？
2. **C1 改造方式**：引加载态（推荐）还是其他？
3. **是否现在开始落地（dev 分支）？**
