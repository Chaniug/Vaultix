# 01-构建与环境 · 第 5 篇（#128 ~ #134）

> 主篇索引：[`01-构建与环境.md`](./01-构建与环境.md)（含计数规则与重号警告）
> 本篇 4 章 · 16.0KB · 覆盖 #128 ~ #134

## 128. 🔴 改完 `package.xml` 编译仍报 `Failed to find target with hash string 'android-37.0'` —— **Gradle daemon 里的 SDK loader 缓存没失效**（2026-09-29 沙箱搭建）

- **现象**：手工补好 `/opt/android-sdk/platforms/android-37.0/package.xml`
  （`<type-details xmlns:ns3="...">` 里用 `ns3:platformDetailsType`，带
  `<api-level>37.0</api-level>` + `<extension-level>22</extension-level>` +
  `<base-extension>true</base-extension>` + `<layoutlib api="15"/>`）之后，
  `:app:compileFullDebugKotlin` **照样**报 `Failed to find target with hash string 'android-37.0'`，
  且连着重跑几次都是同一行错。
- **★ 根因不在 sdklib**：写了 `/tmp/sdklibx/Probe.java` 直连
  `AndroidSdkHandler.getInstance(AndroidLocationsSingleton.INSTANCE, Paths.get("/opt/android-sdk"))`
  打印各平台，输出是
  `android-37.0 | hash=android-37.0 | apiStringWithExt=37.0 | codename=[null] | isPreview=false | isBaseExt=true | ext=22`，
  且 `getTargetFromHashString("android-37.0")` 返回非 null 的 `PlatformTarget`。
  ⇒ **sdklib 侧完全正常；`找不到`是 daemon 进程里上次失败的缓存结果没被丢弃。**
- **证据链（取证用的三条，别跳过）**
  1. `javap` 反编译 `AndroidVersion.getPlatformHashString()`：非 36 分支返回
     `"android-" + getApiStringWithExtension()`；
  2. `DetailsTypes$ApiDetailsType.getAndroidVersion()` 走
     `getApiLevel(): Int` + `getApiMinorLevel(): Int`；
  3. `getApiLevelInt(String)` 支持 `"37.0"` 这种含 `.` 的解析。
- **修法（缺一不可）**
  ```
  gradle --stop
  rm -rf <project>/.gradle ~/.gradle/daemon
  gradle :app:compileFullDebugKotlin     # → BUILD SUCCESSFUL
  ```
- **教训（与 #14「daemon 大任务批崩」同族，但机理不同）**：
  #14 是 daemon **崩**，本条是 daemon **活得好好地拿着旧结论**。
  ⇒ **凡是"改了 SDK / 环境变量 / 仓库镜像这类 daemon 外部输入"之后门禁仍报同一行错，
  第一怀疑对象就是 daemon 缓存，不是被改的那个东西。**
  先花 10 分钟写个探针证明"被调用的库本身没问题"（本条的 `Probe.java`），
  再花 30 秒重启 daemon —— 顺序反了就会在库源码里挖一晚上。
- **附：本轮沙箱另外两处环境修复**（都是 DNS 劫持到 `198.18.0.x` 的连带）
  - `github.com` 等被劫持 ⇒ git 读写走 `ghfast.top` 代理前缀
    （`https://<user>:<pat>@ghfast.top/https://github.com/…`）；
  - Maven/Google 仓库走腾讯镜像，改写在 `~/.gradle/init.d/99-mirrors.gradle`
    的 `settingsEvaluated` 里（写在 `init.gradle` 顶层会因语法损坏拖垮整个 Gradle）。

## 129. ✅ 单测**全量跑时**随机挂 11 条（`Could not initialize class VaultTimeout`）—— 测试顺序敏感的静态初始化竞态（2026-09-29 发现；**已修** `2f6c7fb`：急切 `val` → `by lazy`）

**现象**（本会话实测）：

| 跑法 | 结果 |
|---|---|
| `:app:testFullDebugUnitTest --rerun-tasks`（第 1 次） | 287 tests, **11 failed** |
| `:app:testFullDebugUnitTest --rerun-tasks`（第 2 次，同代码） | **BUILD SUCCESSFUL** |
| 只跑那两个类（`--tests "*NeverTest*" --tests "*AutoRestoreTriggerTest*"`） | **BUILD SUCCESSFUL** |

11 条失败的 message **全部**是：

```
java.lang.NoClassDefFoundError: Could not initialize class io.vaultix.datastore.VaultTimeout$Never
java.lang.ExceptionInInitializerError
```

且**只集中在两个测试类**：`VaultLockManagerImplNeverTest`（6 条）、
`AutoRestoreTriggerTest`（5 条）—— 其余 276 条全绿。

**根因（诊断，未最终证实）**：`core/datastore/.../VaultTimeout.kt:171-174` 的

```kotlin
private val KNOWN_BY_MINUTES: Map<Int, VaultTimeout> = listOf(
    Immediately, OneMinute, FiveMinutes, ...
).mapNotNull { timeout -> timeout.vaultTimeoutInMinutes?.let { it to timeout } }.toMap()
```

是 **`VaultTimeout` companion 的静态属性**，它在 `<clinit>` 期间引用各 data object 实例；
而这些实例的 `type` getter 又引用**嵌套的 `Type` 枚举**（`Type` 属于 `VaultTimeout` 自身）。
⇒ 构成**环形静态初始化**：谁的类加载先触发、触发到哪一步，决定成败。
两个失败类都在**字段/setUp 阶段**（而非方法体内）就引用 `VaultTimeout.Never`，
因而更容易踩中。

**为什么这是真隐患（不是环境噪声）**：同一份代码、同一个命令，两次结果不同
⇒ **flaky**。它会在 CI 上随机变红，且报错信息（`NoClassDefFoundError`）
**完全指向错误的方向**（会让人以为 datastore 模块坏了）。

**⚠️ 定位纪律（别被误导）**：
- 看到测试里的 `NoClassDefFoundError` / `ExceptionInInitializerError`，
  **先怀疑"类初始化竞态"，不要先怀疑被测代码**；
- **区分办法**：单独跑该测试类。**单独过、全量挂 ⇒ 顺序敏感**。

**可能的修法（未实施，供接力者评估）**：
把 `KNOWN_BY_MINUTES` 从急切 `val` 改成 `by lazy`（或改成 `get()`），
打破静态初始化期对子类实例的引用。⚠️ 改前先确认没有别处依赖它的初始化时机。

**本会话为何没修**：本轮任务范围是「亮屏补偿 + 删死字段 + 清理」，
`VaultTimeout.kt` **未被本轮改动**（`git status` 干净），修它属于扩大范围 ⇒ 已向用户报告待决。

---

### ✅ 已修（2026-09-29 收尾轮）—— 修法就是 `by lazy`，但**结论要改两处**

**触发**：收尾轮要**重做变异验证**（上轮因本坑污染而未通过：三轮注入都"变红"，
但红的理由全是本坑，**不是断言失败**）。结果一上手就发现 —— **基线直接红**：
只跑 `VaultLockManagerImplNeverTest` 一个类，**6 条全挂**。

⇒ ★★ **就地修正上表第 3 行的结论**：当时"只跑那两个类 BUILD SUCCESSFUL"是**顺序侥幸**，
**不是"单独跑就没事"**。本坑**不是"仅全量跑才触发"**，而是**取决于类加载顺序**，
而顺序又受编译产物与用例执行次序影响 ⇒ **单跑同样会踩**。
（这也正是本坑最毒的地方：它会伪装成"环境噪声"，让人放弃深究。）

**根因链（本次拿到最底层，环形初始化被证实）**：

```
java.lang.ExceptionInInitializerError: Exception java.lang.NullPointerException
⇒ NoClassDefFoundError: Could not initialize class io.vaultix.datastore.VaultTimeout
⇒ NoClassDefFoundError: Could not initialize class io.vaultix.datastore.VaultTimeout$Never
```

最底层是 **`NullPointerException`**（上一轮只看到 `NoClassDefFoundError`，
所以停在"疑似"）—— 即 `VaultTimeout.<clinit>` 执行到 `KNOWN_BY_MINUTES` 的构建时，
某个 data object 尚未完成初始化。

**修法（已落地）**：`KNOWN_BY_MINUTES` 由急切 `val` 改成 **`by lazy { ... }`**
（`core/datastore/.../VaultTimeout.kt`，含防回归 KDoc：写明"别改回急切 val"）。
语义不变（首次访问构建一次 + 缓存，默认线程安全）——
推迟到首次 `fromStorageValue` 时才构建，此时所有类均已初始化完毕。

**验证（3 条证据，缺一不可）**：
1. 修复后单跑 `VaultLockManagerImplNeverTest` + `AutoRestoreTriggerTest` ⇒ **BUILD SUCCESSFUL**；
2. 修复后 **app 全量单测** ⇒ **BUILD SUCCESSFUL**（不再出现 11 failed）；
3. 修复前报告里 11 条**全部**是 `NoClassDefFoundError`；修复后同类报告 **0 条**。

⚠️ **回归红线**：**别把 `by lazy` 改回急切 `val`** —— 会立刻复现本坑。

---

## 130. 🔴 测试用**虚拟 `delay`** 去等 `Dispatchers.Default` 上的协程 ⇒ 断言"没发生"的用例**随机假绿**（2026-09-29 变异验证暴露，**已修**）

**现象**：对 `VaultLockManagerImpl` 注入"切后台就锁库"这个变异后，
`VaultLockManagerImplNeverTest` 的 6 条里**只有 2 条变红**，
而**本该红**的 `Never档_切后台_绝不锁定` **是绿的**。

按本项目变异纪律 ——「**不变红 ⇒ 测试什么都没验证**」—— 说明这 6 条里**有假绿**。

**根因**：`VaultLockManagerImpl`（以及 `AutoRestoreTrigger`）的 scope 是**硬编码**的：

```kotlin
private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
```

⇒ `scope.launch { ... }` 跑在 **Default 真实线程池**，**`Dispatchers.setMain(...)` 对它完全无效**。

而 `runTest` 只虚拟化**测试调度器**上的 `delay`。于是测试里的 `delay(50)`：
**虚拟时间瞬间推进（真实耗时≈0）、却根本没等真实线程** ⇒ 断言可能先于协程执行。
⇒ 断言"**没发生**锁定"的用例，成败**纯看 Default 线程碰巧跑完没有**。

**铁证（失败栈）**：注入后变红的两条，栈里是

```
VaultLockManagerImpl$setVaultToLocked$1.invokeSuspend (VaultLockManagerImpl.kt:421)
kotlinx.coroutines.scheduling.CoroutineScheduler$Worker.run
```

`CoroutineScheduler` ⇒ **真实线程池**，不是测试调度器。

**修法（已落地，两个测试文件）**：**改用真实时间等待**：

```kotlin
/** 切到 Default（跳出测试调度器）再 delay ⇒ 此时 delay 走真实时间。 */
private suspend fun settle(ms: Long = 500L) = withContext(Dispatchers.Default) { delay(ms) }
```

同时把 `withTimeoutOrNull(5_000L) { while (!pred()) delay(1) }` 这类**虚拟时间轮询**
改成真实时间轮询（`awaitUntil` / `awaitCondition`，超时即 `error`，避免静默假绿）。
⚠️ `AutoRestoreTriggerTest` 的旧 `awaitCondition` 更糟：它在虚拟时钟下**一瞬**
耗尽 5000ms 预算 ⇒ 退化成"只检查一次 predicate"。

**验证**：修好后重跑变异 A ⇒ **3 条**（切后台 / 进程创建 / 亮屏）全部
**`AssertionError`** 变红，且 `AssertionError 计数 == 失败用例数`（3 == 3）
⇒ 那条"切后台"用例的灵敏度**被找回来了**。

**⚠️ 两条纪律**：
1. **别把裸 `delay(...)` 写回这两个测试**：只要被测实现硬编码 `Dispatchers.Default`，
   虚拟 `delay` 就等不到它。要么真实时间等待，要么把 scope 做成可注入。
2. **断言"没发生"的用例，可信度只能来自「变异验证变红」**，
   **不能**来自"我等了一会儿"——等待只能证明"这段时间内没发生"。

**同族隐患（未清，供接力）**：其他模块凡有
`CoroutineScope(... + Dispatchers.Default)` 硬编码 + 测试用 `runTest` + 虚拟 `delay`
断言"没发生"，同样会假绿。排查法：
`grep -rn "Dispatchers.Default" --include=*.kt` 与测试文件里的 `delay(` 交叉比对。

### 130.1 🔴 同族第二例：**起点组合就会触发被测行为** ⇒ 等再久也假不了绿（2026-10-02，**已修** `aacd21c`）

`AutoRestoreTriggerTest` 里两条 flaky
（`档位离开Never_删信封且不恢复`、`非Never档_即使有信封也不恢复`）。
⚠️ 与 #130 **同文件、同类症状（时红时绿）**，但**根因不同** —— 记在一起是为了说明
「同一种症状可以有两个完全不同的根因，修错方向就白修」。

- **根因**：`AutoRestoreTrigger` 在 **`init` 里就 `scope.launch` 启动收集**。
  而 `Fixture()` 初始值是 `(Never, keyInMemory = false)`，配上 `hasEnvelope = true`
  ⇒ **恰好就是 `reconcile` 分支 3「应恢复」的组合** ⇒ 收集协程一跑起来就调 `restore()`。
  测试随后断言 `coVerify(exactly = 0) { restore() }` ⇒ 与测试线程竞速。
- **⚠️ 关键区分**：这一例**不是"等得不够"**（#130 的病因）。
  #130 修的是"用虚拟 delay 等真实线程"；而这里**等待再久也抹不掉已经发生的调用** ——
  `restore()` 在断言之前就已经进了 mock 的调用记录。
  ⇒ **看到 flaky 先分清是"没等到"还是"等到了一个不该发生的结果"**，两者修法正交。
- **修法**：**把起点挪出「恢复」组合** —— 构造前先置 `keyInMemory = true`
  （起点命中分支 2「幂等写信封」），此后**只翻档位、不动钥匙**
  ⇒ 路径上任何时刻都不可能出现 `(Never, 无钥匙, 有信封)`。
- **⇒ 可迁移的判据**（写测试时适用）：
  1. **被测对象若在构造时就开始工作**，夹具的**初始值本身就是一个会被求值的输入**，
     不是"还没开始"的中立态。断言"从未发生 X"前，先确认初始组合不会触发 X。
  2. **只翻一个输入源**。`combine` 对**中间态也会求值** ——
     想测「A 与 B 同时成立」，必须让路径上不出现别的组合
     （原写法先翻钥匙再翻档位，中间态正是恢复组合）。
- **为什么此前只识别出一条**：`非Never档_即使有信封也不恢复` 有**完全相同的竞态**，
  但因为它用 `settle()` 且现象上更偶发，一直被当成"已通过"的一条。
  ⇒ **修 flaky 时要顺手把同夹具的兄弟用例过一遍** —— 它们共享同一份初始值，
  往往一起带病。
- **验证**：CI run `37034357094` —— `:app:testFullDebugUnitTest` **真执行**（无 FROM-CACHE 后缀），
  detekt / APK / 单测三步均 `success`，`e:` 0。
  ⚠️ 但flaky 是概率性的，**一次通过不能证明消除**；这里的信心来自**根因分析**
  （起点组合已不可能触发恢复），不是来自一次绿。

### 130.2 🟡 **同族全量排查：10 处进程级 `CoroutineScope`，7 处已定性**（2026-10-03，结论无需改代码）

#130/#130.1 修的是**症状**。既然"进程级 scope + 同步断言"这个模式在仓库里出现 10 次，
就该问一句：**还有几处带病？** 全量逐个核对（判据只有一条：
**测试里有没有"断言某事从未发生"，且该断言依赖尚未完成的异步收集？**）：

| 处 | 结论 | 依据 |
|---|---|---|
| `AutoRestoreTrigger` | 🔴 **曾带病，本轮已修** | 见 #130.1（两处 flaky） |
| `VaultLockManagerImpl` | 🔴 **曾带病，已修** | 见 #130 |
| `ActiveVaultStore` | ✅ 安全 | `init` 只写 `_activeVaultId`（内存），落盘在 `select()` 里；测试用 `coVerify(timeout = …)` 等到落盘 |
| `ItemRepositoryImpl` | ✅ 安全 | 10 处"没发生"断言都在**同步 suspend 调用之后**，不依赖 `shareIn` 的时序 |
| `UnlockViewModel` | ✅ 不适用 | 0 处"没发生"断言 |
| `BitwardenSyncOrchestrator` | ✅ 不适用 | 同上 |
| `HouseKeyStore` | ✅ 不属此类 | 用 `withContext(Dispatchers.IO)`，受 `runTest` 控制 ⇒ 本来就能被测到 |
| `AutoLockController` | ⚪ 无测试 | — |
| `VaultixAutofillService` | ⚪ 无测试 | — |
| `AutofillActivity` | ⚪ 无测试 | — |
| `ScreenSecurityGuard` | 🔴 **零测试 ⇒ 隐患不可见**，本轮补了 5 条用例 | 见 #159 |

- ⇒ **剩下的隐患不在代码里，在"没有测试"里**：3 处无测试的进程级 scope，
  现在无法判定。这与 #159 同型——**没写测试不等于没问题，只等于问题看不见。**
- **可迁移的判据**（比"这个类有没有 flaky"更通用）：
  **看到测试里出现 `verify(exactly = 0)` / `assertFalse(...曾发生...)`，
  就去问"它等的那件事，有没有一个我能抓住的完成信号？"**
  抓不到信号 ⇒ 换个夹具设计（让被测方报信，见 `ScreenSecurityGuardTest`
  的 `Channel` 方案），**不要靠 `sleep` 或 `timeout` 轮询硬扛**。

## 134. 真机取证三坑：screenrecord 被 kill 丢 moov；logcat -b all 洪水；adb 在线 ≠ 没 suspend（2026-09-29）

- **screenrecord**：用 `kill`（SIGTERM）提前停 ⇒ mp4 缺 moov atom
  （`ffprobe` 报 `moov atom not found`），实测 12.8 MB 数据整段作废。
  ⇒ **必须让 `--time-limit` 自然到点再 `pull`**；要短窗就把 limit 设短。
  同一时刻只能有一个 screenrecord 实例（新起前先确认旧的已结束）。
- **logcat**：`-b all` 在荣耀上实测 **3 秒涨 219 MB**（写盘 IO 会污染测量本身）
  ⇒ 用默认缓冲 + `-v threadtime`（带 pid/tid，便于按进程过滤）。
- **suspend 判定**：host 侧 `adb devices` 一直 `device` **不能**证明设备没深度睡眠
  （无线 adb 保活）；判 suspend 看 `dumpsys power` 的 `mWakefulness` / suspend 统计。
- （用户级记忆已同步；本条为项目接力侧存档。）

