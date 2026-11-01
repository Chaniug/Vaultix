# 04-锁与解锁 · 第 3 篇（#189 ~ #212）

> 主篇索引：[`04-锁与解锁.md`](./04-锁与解锁.md)（含计数规则与重号警告）
> 本篇 5 章 · 13.3KB · 覆盖 #189 ~ #212

## 189. 🟡 熄屏时 App 未必切后台 ⇒ 自动锁定计时器不启动（2026-10-26，对比审计）

- **现象**：设成"离开 5 分钟锁定"，把手机熄屏放一夜，第二天点亮发现**还开着**。
- **根因**：自动锁定只监听 `ACTION_SCREEN_ON`（用于记"回来"）和 `onStop`（用于记"离开"），
  但**熄屏不保证触发 `onStop`**：播放媒体、画中画、`showWhenLocked` 等场景下 Activity
  仍是 resumed；同时 Doze 会让主线程定时器整体停摆。
- **修法**：广播接收器同时注册 `ACTION_SCREEN_ON` / `ACTION_SCREEN_OFF`，按 `intent.action`
  分流；新增 `VaultLockManager.onScreenOff()`，复用 `onAppBackgrounded()` 那套分流
  （`Never` / `OnAppRestart` 档位照旧不锁）。
- **⚠️ 测试坑**：`onScreenOff()` 之后直接推进虚拟时间会**假红** —— 定时器是在协程里挂上的，
  只 `runCurrent()` 一次跳不够。需要 `settle(m)`（反复 `runCurrent()` 到定时器计数稳定）
  再推进。本轮就这么踩了一次。
- **判据**：**"离开"的判据不能只用 `onStop`**。凡是"用户看不见了"这类语义，
  至少要考虑 熄屏 / 切后台 / 分屏 三条独立通路。
---

## 194. 🟡 附件解密后的明文留在 `cacheDir` 里，锁库不清（2026-10-26，对比审计）

- **现象**（用户看不见）：下载一个附件后，`cache/attachments/<itemId>/<id>_<name>` 里躺着
  **解密好的明文**。用户锁库、离开设备、甚至删掉那条密码 —— 文件还在。
- **根因**：`ItemDetailViewModel.downloadAttachment` 落在 `cacheDir` 下就算完事。
  `cacheDir` 只对本应用可读，所以**不是"没风险"，而是"风险窗口可控却没人关窗"**：
  系统只在**存储空间不足时**才回收缓存 —— 一台 512G 的手机可能几个月都不回收一次。
- **修法**：新增 `SensitiveCache`（`util/`）作为**敏感落盘缓存的唯一归口**：
  1. 所有敏感缓存挂在**同一个根** `cache/vaultix-sensitive/` 下
     （`attachments/`、`thumbs/`），`clearAll()` 只需删一次根目录
     ⇒ **将来谁再往根下加第三种敏感缓存，自动被清，不需要回来改 `clearAll`**
     —— "清除的覆盖面不能靠记得"；
  2. `setVaultToLocked` 里 `runCatching { sensitiveCache.clearAll() }`；
  3. `deleteItem` 成功后 `invalidateFor(itemId)`（删条目前清没意义：删可能失败，
     KDBX 写回冲突等，失败时条目还在、缓存还得用）；
  4. `invalidateFor` 按 `CACHE_KINDS` 表逐项清 ⇒ **新增一种缓存必须在这个表里登记**
     （挂在根下只保证 `clearAll` 覆盖，"删单条"要登记）。
- **⚠️ 与 `VaultixClipboard.clearNow()` 的关键区别**：剪贴板是全系统共享的一块地，
  清之前必须确认"是我放的"；`cacheDir` 下这几个子目录**只有本应用会写**，
  所以这里**无条件清** —— 少一层判断，就少一次"判断错了于是没清"。
- **⚠️ 别误伤 `cache/dev_logs/`**：那是用户主动导出的排查日志，与库内容无关，
  保留策略是"只留最近若干个"。把它一起删掉 = 用户刚导出的日志在锁库那一刻消失。
  ⇒ 单测里有一条**反向锁定**（`clearAll_不碰导出日志目录`）。
- **验证**：`SensitiveCacheTest`（6 条，用**真实 TemporaryFolder**，不 mock 文件系统 ——
  本类的全部价值就是"真的删掉了"）+ `VaultLockManagerImplNeverTest` 两条
  （`自动锁定后清明文附件缓存` / `Never档_绝不清明文附件缓存`）。
- **判据**：**"临时文件"不等于"安全文件"**。凡是解密后落盘的明文，都要回答
  "它什么时候消失" —— 如果答案是"系统缺空间的时候"，那就等于没有答案。

---

## 203. 🔴 `init { scope.launch }` 让测试接缝"晚一步生效" ⇒ CI 偶发 `Dispatchers.Main was accessed`（2026-10-28）

- **现象**：CI run `40563967972` 报
  `UnlockViewModelTest > fingerprintEntryStaysHiddenWhenLocalUnlockIsNotEnabledForTheVault FAILED`
  → `IllegalStateException at TestMainDispatcherJvm.kt:45` /
  `MainDispatchers.kt:111` / `HandlerDispatcher.kt:51`。**本地同版本绿**，且此前同一文件
  跑过很多次都绿 ⇒ 偶发，不是必现。
- **根因**：`UnlockViewModel` 的三个测试接缝（`testingScope` / `testingIoDispatcher` /
  `testingBlockableIoDispatcher`）只能**构造之后**赋值（Hilt 不许把 `viewModelScope`
  注入构造，#174 已记四种失败尝试）。而观察协程是在 `init { … }` 里 `scope.launch` 起的 ——
  **`init` 在构造期间就跑**，那一刻 `testingScope` 还是 null ⇒ 落在 `viewModelScope`
  （= `Dispatchers.Main.immediate`）。
  单测 `@Before setMain(dispatcher)` 换掉的只是**当时**的 Main；`@After resetMain()` 把
  它卸掉时，仍挂在旧 Main 上的任务一执行就炸。**构造期间与赋值之后用的不是同一个调度器。**
- **修法**（两步，缺一不可）：
  1. 观察协程的启动从 `init` 里抽成 `internal fun startObservers()`，带
     `AtomicBoolean` 幂等守卫；`init` 只在 `testingScope == null`（= 生产）时自启。
  2. 两个测试工厂（`UnlockViewModelTest` / `StaleRoomResealTest`）在**接缝全设完之后**
     调一次 `startObservers()`。
- **判据**：**接缝的生效范围必须覆盖"用到它的第一个协程"**。`init` 天然不满足这一点 ——
  凡是"构造后赋值才生效"的测试接缝，都不能让真实协程在 `init` 里起。
- **为什么这次不是"再加一次 setMain"就能解决**：问题不在 Main 被卸掉，而在**有两个 Main**
  先后存在。把 `setMain` 调两次只会让第二个也变成"旧的"。

---

## 210. 「从不」档 ≠ 用户以为的「清后台/锁屏后要验证」——开发者设置收纳「保持不锁」（2026-10-30）

### 现象（用户原话）

> 「release 版本在后台清掉 app，再次打开 app 的时候需要指纹解锁，而预览版是不需要的，
> 这样对吗。我感觉 app 清掉后台或者锁屏后，指纹解锁是正确的方案。当然了开发的时候
> 避免 app 频繁上锁，保持不锁状态也有好处，只是开发预览版是否要加一个开发者设置，
> 在里面把日志、上锁的和完全不锁的功能都放到里面。
> 上述的两个环境都是在 app 设置里面锁与安全里面设置了自动锁定时间为**从不**的状态下。」

### 先把两个事实分开（这是本条最容易看错的地方）

| 事实 | 结论 |
|---|---|
| 「从不」档下 `VaultLockManagerImpl` 会不会锁 | **不会**，任何触发源（`AppBackgrounded` / `AppCreated` / `UserChanged`）都 `return@launch` |
| 「从不」档下进程死亡后要不要验证 | **不要**：`AutoRestoreTrigger` 存在信封 ⇒ 免交互恢复（现成的 `Never档_进程创建_不锁定` 用例钉着） |
| 用户想要的「清后台 / 熄屏回来要指纹」是哪一档 | **`OnAppRestart`（重启 App 时）**，不是「从不」 |
| `Immediately`（立即） | 也会锁，但它连「切后台一秒又回来」都锁，太吵 |

⇒ 所以「正式版要指纹、预览版不要」若**两个包都是「从不」**，按代码不可能出现
（两条路径读的是同一个 `VaultTimeoutPreferences`）。**可能的解释只有两类**，且都不需要改锁模型：

| 解释 | 怎么自查（30 秒） |
|---|---|
| ① 两份实际档位不同（预览包覆盖安装后偏好没跟过来，或某一包停在「重启 App 时」/「立即」） | 打开「设置 → 锁与安全」，看**自动锁定**那一行的副标题——它就是当前生效档位，别凭记忆 |
| ② 正式版那次是**手动锁**（主页锁按钮 = 查看层锁）或**指纹门锁待重装**导致的"看起来要验证" | 看解锁页是「只有指纹按钮」（= 查看层锁）还是「主密码 + 指纹」（= 真锁） |

⚠️ **不要**为了"让两版一致"去改 `Never` 的语义：那会推翻 2026-09-29 的二次定稿
（`VaultLockManagerImpl.checkForVaultTimeoutInternal` 的 KDoc「为什么推翻」），
并且让「从不」变成和 Bitwarden 不一致。

### 落地：开发者设置里加一个「保持不锁」

用户明确要的是**开发便利**，不是新产品档位 ⇒ 做成 **debug-only 开关**：

| 层 | 位置 | 做法 |
|---|---|---|
| 偏好 | `VaultixPreferences.DEV_RELAX_SCREEN_LOCK` | `true`（默认）= 照档位执行；`false` = `OnAppRestart` 退化成「不锁」 |
| 消费 | `VaultLockManagerImpl` `OnAppRestart` 分支 | `if (devRelaxScreenLockEnabled()) handleTimeoutAction(vaultId)`，**只**拦这一档 |
| UI | `SettingsScreen.DeveloperSection` | 与「查看与导出日志」同组，整组仍在 `if (BuildConfig.DEBUG)` 内 |

- **⚠️ 读失败按 `true`（锁）**：这是安全判定，宁可多锁一次。与项目里那些
  `runCatching{}.getOrDefault(false)` 的"图省事"兜底**方向相反**，别照抄。
- **⚠️ 不把「自动锁定」整行搬进开发者组**：它已是「锁与安全」页的一等设置
  （2026-09-30 晚用户拍板的位置），搬进 debug-only 组会出现两份入口，而 debug 包
  恰恰是天天在用的那个 ⇒ 两份必然漂移。开发者组里只放这个**辅助开关**，副标题
  直说"正式版没有这一项"。
- **⚠️ `VaultixPreferences` 已顶格 40 个函数**（detekt `TooManyFunctions`）：写口做成
  带 `@set:JvmName("setDevRelaxScreenLock")` 的 write-only 属性，Kotlin 调用方写法
  （`prefs.setDevRelaxScreenLock = false`）与普通 setter 一致，但不占第 41 个名额。
- **⚠️ relaxed mock 的 `Boolean` 恒 `false`** ⇒ 等于"开发者开关关掉了" ⇒ `OnAppRestart`
  用例会**静默变绿**（锁根本没发生）。测试里三处构造都要显式 stub 成 `true`。
- **验证**：`VaultLockManagerImplNeverTest` 新增 3 条 ——
  `OnAppRestart档_进程创建_立即锁`（正向 + 守"生产默认没被改"）、
  `OnAppRestart档_开发者保持不锁开关关掉后_进程创建不锁`、
  `开发者开关关掉后_五分钟档_照旧到点锁`（反向边界：不许把别的档一起关掉）。

## 212. 🔴 #210 的开关「点了没反应」—— M3 `Switch` 没有 48dp 触摸目标（2026-10-30，真机回归）

### 现象（用户原话）

> 「保持不锁的按钮无法点击」（附 1256px 宽 ≈ 393dp 手机截图）

### 根因（不是数据流，是命中框）

| 层 | 事实 |
|---|---|
| 数据 | **没问题**：`writeDevRelaxScreenLock` → DataStore → `devRelaxScreenLock` 回流，#210 的 3 条单测全绿 |
| 行 | `SettingsRow` 这一行**没传 `onClick`**（设计如此：改锁的开关不该"点哪都生效"），所以行内**唯一**热区就是行尾那个开关 |
| 开关 | M3 `Switch` 画出来 **52×32dp**，而它的 `toggleable` **命中框就是这 52×32dp**。Material 要求 ≥48dp，`Switch` **自己不补** |

⚠️ **最容易被"想当然"骗过去的一步**：给 `Switch` 包一层
`Modifier.minimumInteractiveComponentSize()` **没有任何效果** —— 这个 modifier 只是
**提供** `LocalMinimumInteractiveComponentSize`，而 `Switch` 的实现里**没读**它
（读它的是 `Checkbox` / `RadioButton` / `Slider`，它们用"`toggleable` + 0 尺寸子节点 +
`minimumInteractiveComponentSize`"这一手把热区做到 48dp）。
第一版修改正是这么写的 —— 看着对、CI 也绿、真机照样点不中。

⇒ 32dp 高意味着拇指上下各差 8dp 就落空，"按在开关上"变成"按在开关边上"。
这跟 `.ai/ISSUES.md` #121 是同一族教训：**编译、静态检查、单测全都查不出，只在真机上表现为"点了没反应"**。

### 修法：让开关自己占一个 52×48dp 的格子

| 层 | 做法 |
|---|---|
| 组件 | `SettingsComponents.SwitchWithTouchTarget`：`Box(width = 52dp, heightIn(min = 48dp))` 包住 `Switch`，`Switch` 仍按 52×32dp 居中画 ⇒ **视觉零变化，命中框 52×32 → 52×48** |
| 覆盖范围 | `SettingsSwitch`（**内置**，一次修好 13 个调用点）＋ `QuickUnlockDialogs.CapabilityToggle` 的"未知 / 开 / 关"三分支（热区必须一致，否则"数据到了之后忽然变得好点了"本身就是 bug） |
| 高度自适应 | `BoxWithConstraints`：行高够就 48dp；不够就按行高封顶并 `Log.w` 一次（`DisplayOptionsSheet` 那种矮行）。**告警是给下一个人看的路标**，不是运行期报错 |
| 矮行 | `DisplayOptionsSheet` 两个开关行 `Spacing.sm` → `Spacing.md`，内容高 22dp ⇒ 恰好装下 48dp（观感只多 8dp 留白） |

- **⚠️ 不许把外壳做成"可点的放大区"**（`Box.clickable { 再调一次 onCheckedChange }`）：
  点击会依次落到内层 `Switch` 和外层 `Box` 上，**一次点击切换两次** ⇒ 状态弹回原样，
  症状同样是"点了没反应"。放大热区**只能靠让 `Switch` 占更大的布局格子**。
- **⚠️ 热区上限就是 48dp**：M3 `InteractiveComponentSize.kt` 的注释明说，比这更大的
  可点区域会抢到相邻组件本该收到的手势 —— 所以不做"整行可点"。
- **⚠️ 顺手删掉的一句错误注释**：`QuickUnlockDialogs.CapabilityToggle` 的 KDoc 原本写
  「[Switch] 自带最小触摸尺寸」—— 它**没有**。这种"看起来权威的错误注释"会让下一次
  改动直接跳过这一层，已就地改正并留了勘误标记。

