# 01-构建与环境 · 第 9 篇（#174 ~ #176）

> 主篇索引：[`01-构建与环境.md`](./01-构建与环境.md)（含计数规则与重号警告）
> 本篇 3 章 · 15.2KB · 覆盖 #174 ~ #176

## 174. 🔴 CI 单测那一条红的真相：`Dispatchers.Main` 在 JVM 单测里是一具**会被卸下**的替身（2026-10-18）

**位置**：`app/.../ui/unlock/UnlockViewModel.kt` · `UnlockViewModelTest` / `StaleRoomResealTest`
**修于**：2026-10-18（commit `214ea18`，CI run `39085733395` 硬门禁转绿）

拿到 PAT 之后 #173 里那个"到底红了哪一例"终于有了答案 —— 而且不是 #172 的锅。

### 红的是什么

```
Run unit tests (hard gate)
375 tests completed, 1 failed

UnlockViewModelTest > candidateVaultsExcludeTheTargetItself FAILED
    java.lang.IllegalStateException: Dispatchers.Main was accessed when the
      platform dispatcher was absent and the test dispatcher was unset
        at TestMainDispatcher.isDispatchNeeded(TestMainDispatcher.kt:32)
        at DispatchedContinuationKt.resumeCancellableWithInternal(...)
        at DispatchedCoroutine.afterResume(Builders.common.kt:588)
        at LimitedDispatcher$Worker.run(LimitedDispatcher.kt:124)
    Caused by: Module with the Main dispatcher had failed to initialize
```

### 机理（与"环境不一样"无关，是**竞态**）

1. VM 里 `viewModelScope.launch { … withContext(Dispatchers.IO) { … } … }`：
   IO 段跑在**真实线程池**，返回时**续体要回 `Dispatchers.Main`**。
2. JVM 单测的 classpath 上**没有 `kotlinx-coroutines-android`**
   （`app/build.gradle.kts` 只给了 `testImplementation(libs.kotlinx.coroutines.test)`），
   于是 `Dispatchers.Main` 是 `MissingMainCoroutineDispatcher`；
   `runTest` 会临时装一个 `TestMainDispatcher` 顶替它。
3. 但 `@After { Dispatchers.resetMain() }` 一执行，替身就**卸下来了**。
   真实线程池那条续体若恰好在此之后 resume ⇒ `isDispatchNeeded` 撞上"没有主线程调度器" ⇒ 炸。
4. 同文件的 `candidateVaultsExcludeVaultsOutsideTheQuickUnlockScope` /
   `candidateVaultsExcludeAlreadyUnlockedVaults` 走**同一段代码却没红**
   ⇒ 是**时序竞态**，不是必现（所以它不是"新引入"，是既有隐藏 flake）。

> ⚠️ 别被"`runTest` 自带 `TestMainDispatcher`"骗了：它只保证**虚拟调度器上**的
> 任务没问题；**真线程 resume 回主线程**这条路它挡不住，因为那要看替身**当时**还在不在。

### 解法：把作用域与后台调度器变成可注入的（生产语义零变化）

`UnlockViewModel` 加两个 `internal var`（生产恒为 `null`）：

| 覆写口 | 生产默认值 |
|---|---|
| `testingScope` | `viewModelScope` |
| `testingIoDispatcher` | `Dispatchers.IO` |

协程挂载点统一收敛到 `scope` / `io` 两个 getter。测试注入
`TestScope` + `StandardTestDispatcher(testScheduler)`，等待就从"轮询真实终态 + 5 s 超时"
变成**确定性的虚拟时间**。

### 🔴 四条"更标准"的路全部走不通（都实测过）

| 方案 | 编译器 / Dagger 的反应 |
|---|---|
| 构造参数默认值里读 `viewModelScope` / `this` | `Cannot access '<this>' before the instance has been initialized`（默认值在**实例初始化前**求值；与基类是否 `ViewModel` **无关**，普通 class 同样复现） |
| `viewModelScope` 做成 `@Provides` 注入（`Lazy` / `Provider` / `dagger.Lazy` 三种形状） | `Injection of an @HiltViewModel class is prohibited`（Hilt 的 `ViewModelValidationPlugin` 硬校验） |
| `scopeOverride: CoroutineScope? = null` 当构造参数 | `CoroutineScope cannot be provided`（**可空 + 带默认值也照样要求绑定**） |
| **构造后赋值的 `var` 字段** | ✅ |

### 测试侧的两个坑（都会让"确定性等待"变成错觉）

1. **只换 `scope` 不够**：`withContext(Dispatchers.IO)` 仍会跳真实线程池，虚拟时钟推不动。
   实测：只换 scope 时有 **6 条**用例报"没有收到 `PromptForUnlock` 事件"。
2. **`advanceUntilIdle()` 不总是对的**：它会把 `Channel` 的缓冲刷新一并快进，
   出现"事件已发出、`prompts` 仍为空"（实测 `submitting=true prompts=[]`）。
   这一页用 `runCurrent()` / `advanceTimeBy(1)` 就够 —— 链路上没有任何 `delay`。

### 变异验证（4 组，各自精确命中）

| 变异 | 红的用例 |
|---|---|
| `scope` 忽略覆写 | 只红 `overrideScopeIsActuallyUsedByTheViewModel` |
| `io` 忽略覆写 | 只红 `promptCarriesTheFilteredCandidateVaults` |
| 事件不带候选库（`rest = emptyList()`） | 只红 `promptCarriesTheFilteredCandidateVaults` |
| 完整还原修复前实现 | 红 `promptCarries…` + `StaleRoomResealTest` 两条 |

> 第一条特别值得留意：**没有它，前三组变异只有一条用例在挡**。结构前提
> （"注入真的生效"）本身也需要一条门禁，否则"确定性"只是错觉。

---

## 175. 🔴 `init { scope.launch { … collect … } }` 是单测随机假红的总闸：构造期派发 + 无界 collect（2026-10-18）

### 现象

`VaultLockManagerImplNeverTest` 改成虚拟时钟后（见 #130 同族），出现一波**极难读**的红：

- 全量跑时偶发 **1–2 条**红，**同一次构建里红哪条还会变**；
- 追查过程中一度出现**两条互斥断言同时红**：`非Never档_切后台_不立即锁`（"不该锁"）报
  *should not be called*，而同一次构建里 `非Never档_切后台满5分钟_锁定`（"该锁"）报
  *was not called* —— 同一个定时器不可能既锁了又没锁；
- 单跑常常全绿，`--tests` 过滤器的结果不稳定，**不能作为隔离证据**。

### 根因（两层，第二层才是真凶）

被测类原来这样写：

```kotlin
init {
    scope.launch { vaultRepository.observeVaults().collect { … } }   // ← 真凶
    registerScreenOnReceiver()
}
```

1. **构造期派发**：`launch` 发生在**构造期间**，而测试的调度器覆写口
   （`testingIoDispatcher`，见 #174 同款写法）**只能在构造之后赋值** ⇒ 这一发
   已经派到 `Dispatchers.Default` 上，此后 `runTest` 的虚拟时钟**永远推不动它**。
2. **无界 `collect`**：这条协程**永不结束**。`runTest` 判断"还有没有待办"时会被它搅乱，
   于是 `advanceUntilIdle()` / `advanceTimeBy()` 的行为变得**依赖进程当时的线程状态**
   —— 这才是"红哪条每次都不一样"的原因。

> 判据：**看到"两条互斥断言同时红"，先找无界协程，不要调测试时序。**
> 互斥同时成立 = 不是时序偏一点，是有别的东西在抢调度。

### 解法：把"启动"从 `init` 里拿出来，交给调用方

| 类 | 新增 | 生产调用点 |
|---|---|---|
| `VaultLockManagerImpl` | `startObservingVaults()`（幂等，`observingJob` 守卫） + 接口默认空实现 | `VaultixApplication.onCreate` |
| `AutoLockController` | `startCollecting()`（幂等，`collectingJob` 守卫） | `VaultixApplication.onCreate` |

测试侧顺序变成**固定两步**：`先 testingIoDispatcher = StandardTestDispatcher(testScheduler)` →
`再 startXxx()` → `advanceUntilIdle()`。收集协程从此直接落在虚拟时钟上。

⚠️ 接口里加默认空实现，是为了不逼着 `mockk<VaultLockManager>` 的测试替身实现它。

### 🔴 试过但**不通**的两条路（别重试）

| 方案 | 实测结果 |
|---|---|
| `scope` 写成 `by lazy` / getter（不改 `init`） | 无效：`init` 那次 `launch` **在构造期**就取走了 `scope`，之后改 getter 救不回来 |
| 自定义 `CoroutineDispatcher` 包装，`dispatch` 时现查真调度器 | **`delay` 永远醒不过来**：`timer coroutine started` 出现 5 次、`timer fired` **0 次**。虚拟时钟靠"上下文里的 `CoroutineDispatcher` **就是** `TestDispatcher`"来恢复，套一层就认不出 |

### 虚拟时钟的三条规则（同一天连踩四轮才定）

| 尝试 | 结果 |
|---|---|
| 一律 `advanceTimeBy(N)` + `runCurrent()` | 单跑全绿，**全量偶发 1–2 条红** |
| 一律 `advanceUntilIdle()` | **稳定 3 条红** —— 它把定时器一路推到到点，"不该锁"的断言被迫变红 |
| 只 `runCurrent()` 一次去建定时器 | **时好时坏** —— `runCurrent()` 只跑"当前时刻已排队"的任务，而这条路径是 **2–3 跳** |
| 自定义调度器包装 | `delay` 永不恢复（见上表） |

⇒ 定型为：

| 目的 | 写法 |
|---|---|
| **建好定时器但不推时间** | `settle(m)` —— 反复 `runCurrent()` 直到**定时器计数稳定** |
| 断言"到点前**不**会锁" | `settle(m)` → `advanceTimeBy(N - 1)` → `runCurrent()`（**不能**再 `advanceUntilIdle()`，它会补齐剩下那 1ms） |
| 断言"**到点会**锁" | `settle(m)` → `advanceTimeBy(N)` → `advanceUntilIdle()`（让锁定那一跳跑完） |

`settle` 的收敛信号来自新增的 `internal fun timerCountForTest(): Int`
（只读、零副作用，生产零调用）。**判据是"计数稳定"，不是"跑了几轮"** ——
所以实现将来加一跳，测试不会坏。收敛下限设 3 轮：补偿路径会先 `1 → 0`（cancel）
再 `0 → 1`（重启），只看"连续两次相同"会误判收敛。

### 变异验证（6 组，各自精确命中）

| 变异 | 红的用例 | 条数 |
|---|---|---|
| 非 Never 档切后台改成立即锁 | 不立即锁 / 补偿 / 回前台 / 亮屏不提前锁 / lockAll 撤销 / 多库撤销 | 6 |
| `onAppForegrounded` 不撤定时器 | 回前台仍不锁 / lockAll 撤销 / 多库撤销 | 3 |
| `lockAll` 退回"只锁活动库" | `lockAll_锁全部已解锁库` / `lockAll_撤销定时器…` | 2 |
| `Never` 分支塞回 `handleTimeoutAction` | 三条 `Never档_*` | 3 |
| `onScreenOn` 取消后不重启 | `非Never档_亮屏补偿后_时间从零点走满仍会锁` | 1 |
| `lockAllNow` 退回 `lockVault(…)` | `lockAllNow_锁全部库_不退化成锁活动库` | 1 |

> 前三组"红一片"是对的（它们动的是公共路径），后两组是**单点门禁**。
> 判据：**变异必须红，且红的范围要能用一句话解释**。解释不了 = 测试之间有隐藏耦合。

### 顺带修掉的两件事

1. `VaultLockManagerImplNeverTest` 里有个 `private val dispatcher = StandardTestDispatcher()`
   **从未被使用**，且它不与 `runTest` 的 `testScheduler` 共享 —— 纯干扰项，已删。
2. `AutoLockController` 此前**一条测试都没有**，现已补 6 条（`AutoLockControllerTest`），
   其中 `lockAllNow_锁全部库_不退化成锁活动库` 是 2026-10-18 那个"只锁活动库"缺陷的门禁。
## 176. 🔴 `StandardTestDispatcher` 跑测试任务时**不恢复拦截器**：跨 `withContext(io)` 的续体照样踩 `Dispatchers.Main`（2026-10-18）

### 现象

#174 把 `UnlockViewModel` 的 `scope` / `io` 都换成虚拟调度器之后，本地全绿、CI 也绿过一次；
但下一个 run（`39139391458`，sha `d646127`）的硬门禁 `Run unit tests (hard gate)` **又红了**，
红的仍是同一句：

```
java.lang.IllegalStateException: Dispatchers.Main was accessed when the platform
dispatcher was absent and the test dispatcher was unset.
```

这次换了一条用例：`UnlockViewModelTest > promptCarriesTheFilteredCandidateVaults`。

⚠️ **这就是 #173 / #174 的续集**：只要"本地绿、CI 红"还在出现，就不能算修完。
#174 修的是一条**病因相同但落点不同**的路径。

### 根因

堆栈的关键三行：

```
TestMainDispatcher.isDispatchNeeded(TestMainDispatcher.kt:32)
DispatchedContinuationKt.safeIsDispatchNeeded(DispatchedContinuation.kt:262)
DispatchedCoroutine.afterResume(Builders.common.kt:588)
    ← kotlinx.coroutines.scheduling.CoroutineScheduler$Worker.run
```

`CoroutineScheduler$Worker` = **真线程池**（`Dispatchers.IO`）。也就是说：

> 测试里 `withContext(io)` **仍然真的跳了线程**，尽管 `io` 已经是
> `StandardTestDispatcher(testScheduler)`。

机理（`kotlinx-coroutines-test` 内部）：

1. `StandardTestDispatcher.dispatch` 只是把任务排进 `TestCoroutineScheduler`；
2. 调度器跑任务时**不恢复**原协程的 `ContinuationInterceptor`
   （这正是"虚拟时间"能成立的前提：任务在 scheduler 里顺序执行，不按协程上下文跳）；
3. 于是 `withContext(io)` 的**续体恢复**时，`DispatchedContinuation` 手里的上下文
   **既没有拦截器、也没有 `Dispatchers.Main`**；
4. `resumeCancellableWith` 退化成"问 `Dispatchers.Main` 要不要派发" ⇒
   `TestMainDispatcher.isDispatchNeeded` ⇒ 而 `@After resetMain()` 已经卸下替身 ⇒ 炸。

⇒ **"把调度器换成虚拟的" 只解决"时钟推不推得动"，不解决"续体跳不跳线程"。**
这两件事被 #174 混为一谈了。

### 解法

新增第二个覆写口，语义是**拦截**而不是**替换**：

```kotlin
/**
 * ★ #176：只给单测用的"后台活"调度器**拦截**口（生产恒为 null ⇒ 直接跑在 [io] 上）。
 */
internal var testingBlockableIoDispatcher: CoroutineDispatcher? = null

/** 真正执行"后台活"的调度器：测试被拦截 ⇒ 内联，生产/默认 = [io]。 */
private val ioContext: CoroutineContext
    get() = (testingBlockableIoDispatcher ?: io) as CoroutineContext
```

八处 `withContext(io) { … }` 改成 `withContext(ioContext) { … }`；测试里：

```kotlin
it.testingIoDispatcher = StandardTestDispatcher(testScheduler)          // 让时钟推得动
it.testingBlockableIoDispatcher = StandardTestDispatcher(testScheduler) // 让续体不跳线程
```

为什么传 `StandardTestDispatcher` 就能内联？因为它**不是** `TestDispatcher` 的子类 ——
`runTest` 收编共享调度器时只认 `TestDispatcher`，所以这个实例**不会被收编进虚拟时钟**，
行为退化成"可捕获的惰性调度器"：`isDispatchNeeded` 在已持有锁的线程上返回 `false` ⇒
**任务当场内联执行**，且 `withContext` 完成后 `ContinuationInterceptor` 仍是它自己
⇒ 续体恢复时拦截器还在 ⇒ **永不碰 `Dispatchers.Main`**。

### 为什么不是别的写法

| 备选 | 为什么不用 |
|---|---|
| `Dispatchers.Unconfined` | 能内联，**不可捕获**：真被 `delay` 排上来会抛 `UnsupportedOperationException`，把"偶发"换成"换个地方偶发" |
| `newSingleThreadContext("io")` | 仍是真线程 ⇒ 续体还是跳线程，病根不动 |
| 改断言 / 加 `@Ignore` | 违反"断言不放宽"；且这是**测试基建**缺陷，不该由业务断言买单 |
| 全局 `Dispatchers.setMain(...)` 不 reset | 会把别的类的假红掩盖成绿，是更坏的结果 |

### 验证

- 改前：CI `39139391458` 上 `promptCarriesTheFilteredCandidateVaults` 红（1/8 例）；
  本地 `--rerun-tasks` 一次通过 —— **本地绿不构成证据**，这次是 CI 报告先给出真相。
- 改后：`ui.unlock.*` 整包连跑 5 次（`--rerun-tasks`）全绿；全量 `388 例 0 失败`。

### 教训（写给下一个接手的人）

> **"我把调度器换成了 `StandardTestDispatcher`" ≠ "这段代码不跳线程了"。**
> 虚拟时钟管的是**时间**，不是**线程**。凡是用 `runTest` + 注入调度器去消灭
> `Dispatchers.Main` 竞态的，都要额外确认一件事：被测代码里有没有
> `withContext(<别的调度器>)` —— 有的话，那个调度器必须在测试里被**拦截**掉
> （内联执行），否则每跑一次都是一次掷骰子。

⚠️ 同类隐患还可能有：任何用 `UnconfinedTestDispatcher` 之外的普通调度器做的
"注入即可测"改造。判断标准只有一条 —— **看 CI 的硬门禁，不看本地绿。**

---

