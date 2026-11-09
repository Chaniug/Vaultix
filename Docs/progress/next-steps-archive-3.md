# 下一步任务清单 · 归档 3/6

> **历史归档，勿通读。** 覆盖 2026-09-29 ~ 2026-09-15 的已完成记录。
> 入口与当前待办见 [`next-steps.md`](./next-steps.md)。
> 拆出于 2026-11-10：原文件 3592 行 / 280 KB，远超 §7.1 的 300 行上限，
> 且真正的待办被埋在 96% 位置。归档按时间倒序，**只在需要追溯时检索**。

---

> ## 🏠 【2026-09-29 夜】快速解锁「房子化」批次 2 收工 —— 动作表重排 + 重登记向导 + 扇出门禁（✅ 已被批次 3 接续）
>
> **接力文档（下一会话从这里进，自包含）**：[`house-rework-batch2-handoff.md`](house-rework-batch2-handoff.md)
> **施工工作单**：[`quick-unlock-house-rework.md`](quick-unlock-house-rework.md)（批次 1-2 ✅，3-5 未动）
> **本轮**：① 动作表重排（勾库 = 纯软件封装不碰门锁；开锁各一次 wrap；**已装门锁不重开**；
> `pendingRooms()` 收敛单维度；顺序约束加第二层「房钥匙还得在内存」，拦在问主密码之前）
> ② 重登记向导（新建 `LegacyQuickUnlockCleanup`，**只在本次建成房间之后**才清旧信封，
> 失败则回滚新开的门锁）③ 新建 `LocalUnlockFanoutTest`（8 用例，钉 `completeFingerprintUnlock`
> **恰 1 次且不随库数增长**）+ `LegacyQuickUnlockCleanupTest`（8 用例）。
> 门禁：detekt / `:app:compileFullDebugKotlin` / 单测 **318 全过 0 failed**（三关分开单跑）。
> `main` 已推送 `61ea1a3`；`rele` 未动。
> ✅ **上面的「下一步 = 批次 3」已于同日深夜完成**（含遗留 #1 的解法），见最顶部那个状态块
> ⇒ **接力从最顶部读起，别再从这一条开工。**
> ⚠️ 真机全链路未验（批次 5 清单 1-7；尤其「指纹一次开多库」「杀后台必须重新解锁」）。

> ## 🏠 【2026-09-29 白天】快速解锁「房子化」批次 1 收工 —— 钥匙层核心落地，门禁三关全绿（✅ 已被批次 2 接续）
>
> **接力文档（钥匙层模型速查，已被批次 2 接续）**：[`house-rework-batch1-handoff.md`](house-rework-batch1-handoff.md)
> **施工工作单**：[`quick-unlock-house-rework.md`](quick-unlock-house-rework.md)（批次 1-2 ✅，3-5 未动）
> **本轮**：两级钥匙层（门锁×2 包 1 把仅内存房钥匙 + 每库纯软件房间信封）全部落地；
> **H1/H2 结构性消灭**；契约 17→16 方法；扇出重写为「1 次 Keystore + N 次纯软件」；
> 新增 `HouseKeyStoreTest` 6 用例。门禁：detekt / compileFullDebugKotlin / 单测全绿（三关分开单跑）。
> **批次 0 判别实验取消**（结构性修复已同时消灭 H1/H2，判别失去意义）。
> ✅ **上面的「下一步 = 批次 2」三条已于同日夜间全部完成**（`61ea1a3`），见最顶部那个状态块
> ⇒ **接力从最顶部读起，别再从这一条开工。**
> ⚠️ 真机全链路未验（批次 5 清单 1-7；尤其「指纹一次开多库」「杀后台必须重新解锁」）。

> ## 🏠 【2026-09-28 深夜】快速解锁「房子化」已定稿 —— 批次 0 实验待做（未动代码）
>
> **定稿**：[`.ai/decisions/快速解锁房子化-两级钥匙层级-定稿.md`](../../.ai/decisions/快速解锁房子化-两级钥匙层级-定稿.md)
> **施工工作单**：[`quick-unlock-house-rework.md`](quick-unlock-house-rework.md)（批次 0 起，自包含）
> **用户拍板**（2026-09-28 23:48）：「房子化吧」+ 追加「设置里面的快速解锁的设置也需要删掉多余的
> 内容」⇒ 定稿 §5.1 删除清单五类：每库指纹/PIN 标记、Partial 态、勾库两步登记、
> 每库 enabled/pinEnabled 双键真源、逐库成败分类。
> **方案**：两级钥匙层级——门锁（指纹/PIN 各一信封）只包 1 把随机软件「房子钥匙」
> （**绝不落盘** = 硬约束 #1，保住「杀后台=自然锁定」，刻意不学 Keyguard 持久会话），
> 每库凭据改纯软件封装。H1/H2 从结构上消失；能力级 UI、生效范围、逐库问密码、
> 先校验后包裹全部保留。两篇旧定稿已加修订横幅（历史保留）。
> **下一步 = 工作单批次 0（P0 一分钟实验，需真手指）**：H1 → 插入止血批次 0.5；H2 → 直接批次 1。
> 另：新增约定 [`conventions/8.9-文档分篇.md`](../../.ai/conventions/8.9-文档分篇.md)
> （用户拍板：md 过长必须分篇，防 AI 接力读超长上下文；正文 ≈300 行/20KB 上限）。

> ## 🔍 【2026-09-28 晚】Bitwarden / KDBX 连接与同步排查 —— **1 个 P0 真 bug + 3 个休眠设计隐患，未动代码**
>
> **报告**：[`Docs/progress/audit/bitwarden-kdbx-sync-audit.md`](audit/bitwarden-kdbx-sync-audit.md)（自包含）
> **用户诉求**：「app 与 bitwarden 服务连接的时候，或 kdbx 连接的时候，是否还有 bug，比如同步的问题，排查一下」。
>
> **结论速览**：Bitwarden 侧历史修复（#8/#22/#91/#92/#119）**全部在位**；KDBX #106（写路径）已修、
> 网盘同步（OneDrive/WebDAV，09-17 落地）实现齐整。新发现：
> - 🔴 **P0 真 bug**：OneDrive KDBX 库的指纹快解在填充扇出中 **100% 失败**（真机 4 次实证，
>   `opened=0`；失败在 ~50ms 的本地密码学层，报 `本地解锁凭据不可用`）。
>   根因两个候选（报告 §3.2）：**H1** 多库登记「一个 cipher 连续 wrap」产出错位 payload /
>   KEK 换代后未重包；**H2** auth-per-use 语义下 rest 库的新解密 cipher **未被本次生物认证授权**。
>   ⚠️ 放大因素：KDBX 侧 unwrap 失败「勿删登记」**永不自愈**（Bitwarden 侧同场景会清登记）。
>   **一分钟决定性实验**（报告 §3.4）：把 OneDrive 库切为活跃库→锁定→指纹解锁——
>   仍失败 = H1（payload 坏）；成功 = H2（扇出授权缺口）。
> - 🔴 **P1 休眠设计隐患**（R-6~R-9「KDBX 单条编辑」的**硬前置**，开放编辑前必须拍板）：
>   ① NeedsReload 路径**提前推进 token 基线**（`KdbxSyncOrchestrator.kt:148`）——开放编辑后
>   时序「NeedsReload→旧会话上编辑→再同步」会把远端新改动**静默覆盖**；
>   ② `notifyLocalChangedDuringUpload` 三层实现、**app 层零调用**；
>   ③ `localChangedSinceLastSync` 两处口径不一（库列表页硬编码 true / 设置页用 syncStatus），
>   硬编码 true 会把「远端更新」伪报成「冲突」，误选「用本地覆盖远端」= **真数据丢失入口**。
> - P2：`collectionIds`/`archivedDate` 未建模（#119 尾巴）——组织库激活前需一次服务端行为确认。

> ## 🔍 【2026-09-28】后台被杀后「填充频繁要求解锁 / 匹配不到条目」——**诊断完成，未动代码**
>
> **报告**：[`Docs/progress/audit/autofill-relock-after-kill.md`](audit/autofill-relock-after-kill.md)（自包含）
> **用户诉求**：Bitwarden 官方 App 后台被清后填充不密集解锁；Vaultix 即使设「从不加锁」仍频繁解锁，
> 有时匹配不到条目。**问是否应对齐 Bitwarden**。
> **用户拍板**：先要诊断报告，不动代码。
>
> **结论（一句话）**：不是 bug，是**架构差异**。Vaultix 把「已解锁」等同于「密钥在内存」
> （`VaultSessionManager.sessions` 纯内存 Map，L29），进程死亡即锁死 ⇒
> `VaultixAutofillService.kt:198` 的 `unlocked.isEmpty()` 必然成立 ⇒ 必弹解锁。
> **「从不加锁」= `VaultTimeout.Never`，语义只是「不因超时自动锁」，管不了进程被杀。**
>
> **★ 一处自我更正（重要）**：最初假设「Bitwarden 用独立 `:autofill` 进程」——**已证伪**。
> 实测 Bitwarden 官方 `AutofillService` 注册在**主 manifest**、无独立进程
> （DeepWiki §6.2）；本仓库 `grep android:process` 亦为空。**此路不通，勿据此改造。**
> 真实差异在 **`isLocked` 的判据来源**：上游走 `VaultTimeoutService`（可持久化+按时间重算），
> 本项目走内存 Map。且 **Bitwarden 自己也承认 Force stop 后填充会停**（官方 troubleshooting 页）——
> 它靠电池优化白名单降频，**不是架构免疫**。
>
> **四方案（均未实施，待拍板）**：A 持久化解锁态（⚠️ 与 `8.2` 真锁=密钥清零正面冲突，
> **不推荐**）· **A′ 修 `createdForAutofill` 豁免（⭐ 强烈推荐：纯 bug 修复、零安全取舍）** ·
> **B 只收敛体验：进程重建后走快速解锁信封「一次指纹」继续填充（推荐）** ·
> **C 修「匹配不到条目」：`singleActiveVault` 取到的库无匹配时降级兜底（推荐，无安全影响，可独立做）** ·
> D 电池优化白名单引导 + 文案澄清（建议附带）。
> **★ 关键论证**：A 的实质是「B + 一层无收益的解锁态持久化标记」⇒ **B 才是最小改动**。
>
> ### ★★★ 第二轮排查（2026-09-28，用户补充「**已设指纹解锁**，仍很频繁，且更密集」）
> **症状被拆成两种不同的病 —— 之前只查了一半：**
> | 形态 | 含义 | 真因 | 状态 |
> |---|---|---|---|
> | **A. 频率高** | 每次填充都要认证一次 | §2 物理事实（解锁态纯内存） | 已定位 |
> | **B. 密集/重复** | **同一次**填充被要求解锁**好几次** | **`PendingIntent` 重发** | ★ **新发现** |
>
> **★ 形态 B 的实证（项目自己的日志）**：`AutofillActivity.kt:252-270` KDoc 记有
> 荣耀 BKQ-AN00 · Edge 实测 —— `maybeBiometricUnlock: outcome=Ready × 22`（14 秒内 22 次）。
> **成因**：系统按 PendingIntent 的 `resultCode` 判"认证动作完成没"，非 `RESULT_OK` ⇒ **重发**。
> **⚠️ 修复只覆盖了 CP 路**（`if (credentialFlow) … else …`，L427）；
> **普通 autofill 路缺口在 `deliverPendingFill` L574**：
> `val unlockedNow = if (credentialFlow) isAnyVaultUnlocked() else false` ← **普通路恒 false**
> ⇒ 走到 L584 `finish()`（= `CANCELED`）⇒ **重发**。
> **逻辑悖论**：走到「无有效暂存」恰恰说明用户**刚完成外部解锁**，却被当作"没解锁"。
> ✅ **修法通路已确认**：Autofill 框架的正向结果 = `RESULT_OK` + `EXTRA_AUTHENTICATION_RESULT`
> （`reference/bastion/.../AutofillAuthenticationActivity.kt:249-258` 实证；
> **本仓 `VaultixAutofillService.kt:593-601` 已在用同一套**）⇒ **不需要 Credential Manager API**。
>
> **★ 形态「匹配不到条目」的真凶 = 暂存的 `AutofillId` 失效**：
> `PendingFillStore` 存的是**字段 id**（类注释自述；`TTL_MS=120s`）。解锁期间浏览器若**重建页面**，
> 回灌时 id 已失效 ⇒ `AutofillActivity.kt:576`「无有效暂存 → 收工」**静默失败**
> ⇒ 系统重列候选、判据未变 ⇒ 用户看到「没有匹配项」。
> **且会被形态 B 的"重发"放大**（重发期间页面更可能重建）⇒ **两条症状互相喂养**。
> **补充**：锁定时**根本不进入匹配**（`VaultixAutofillService.kt:199-231` 直接返回解锁 fallback）
> ⇒ 用户看到的"没有匹配"**不是匹配失败，是压根没匹配过** —— 极具误导性，建议文案区分。
>
> ⇒ **新增方案**：**B′（修 `else false`，⭐ 最高优先，纯 bug 修复，直接命中"密集"）** ·
> **C′（① 锁定态文案区分"未解锁"vs"无匹配" ② 回灌失败给可见反馈 ③ 暂存能否不依赖 `AutofillId`）**。
> ⇒ **新增取证 R5–R7**（R6 尤其重要：**先问清用户走的是普通填充还是通行密钥** ——
> 本项目有过「拿按键返回复现手势返回」连错三轮的教训）。

> ### ★★ 补充发现（2026-09-28，用户追问「bitwarden 的做法好吗」后深挖）
> **`createdForAutofill` 豁免从未生效过 —— 这是一处真 bug，不是架构差异。**
> - 上游机制**本项目已抄**：`VaultLockManagerImpl.kt:196-204` 的 `OnAppRestart` 分支含
>   `if (firstTimeCreation || !createdForAutofill)` ⇒ 为 autofill 拉起的进程**本应豁免锁定**
>   （`VaultLockManager.kt:115-122` 注释称其为「通行密钥流程不该把库锁掉的结构性保障」）。
> - **缺陷 A**：`VaultixApplication.kt:85-88` 硬编码 `isFirstCreation = true` ⇒
>   `true || !x` 恒真 ⇒ **豁免分支永远走不到**（语义漂移：KDoc「冷启动」vs 实现「进程被创建」）。
> - **缺陷 B**：`markCreatedForAutofill()` 时序**物理不可能** —— `Application.onCreate()`
>   必然早于 `Activity.onCreate()`，而标记在 Activity 里置位 ⇒ **读永远早于写**。
>   且两处注释互相矛盾（`VaultixApplication.kt:58` 说 super.onCreate **之前**，
>   `CredentialProviderActivity.kt:88` 说**之后**）。
> - **缺陷 C**：`AutofillActivity` **根本没调用** `markCreatedForAutofill`
>   （全仓唯一调用点是 `CredentialProviderActivity.kt:90`）。
> - ⇒ **影响重估**：`Never` 档 → 走 §2 物理事实（主因）；`OnAppRestart` 档 → 走 §2.5
>   （**本该豁免却不豁免**）。**A′ 是纯 bug 修复、零安全取舍，建议提为最高优先。**
> ⚠️ 修法要点（`autofill-relock-after-kill.md` §7.1）：**单纯改传参无解**（用户冷启动与
> 系统拉起 autofill 在 `Application.onCreate` 时刻**都表现为"进程刚创建"**）⇒ **必须换判据来源**；
> ⚠️ 修前必须做**闭环证明**（还原成出 bug 版本看是否恰好报那几处 —— #124 有「连坏两版假绿」教训）。

>
> ⚠️ **改动前必须先做的取证（R1–R4）**：R1「从不加锁」落盘值是否被旧迁移误读为 `OnAppRestart`
> （`Never=-2` vs `OnAppRestart=-1`，见 `8.2` L30）· R2「匹配不到条目」是否**只在多库**时出现 ·
> R3 弹的是主密码框还是指纹（决定 B 能否直接生效）· R4「被杀」是手动划掉还是系统自动杀。
>
> ⚠️ **另需澄清的边界**：「锁态单一口径」（`8.1` L39-46 / #66）说的是**判据不要自相矛盾**；
> 本报告的「解锁态不持久」说的是**该判据的输入无法跨进程存活**。
> **两者不冲突 ⇒ 别混为一谈后推翻 #66 的修复。**
>
> ## ✅ 【2026-09-26】设置页缺陷修复（「先修坏的」）—— 六件事全部落地（**真机验收未做**）
>
> 用户问四件事：Bastion 设置还有什么可搬 / 设置哪些能归拢 / **开关是否异常** / 文案是否模糊。
> 拍板 = **先修「坏」的，再谈搬运**；KDBX 定位 = **开放单条编辑**。
>
> **已改**（6 个提交，`da60613` → `f160ace`）：
> 1. `da60613` **副标题截断 2 行** + 删冗余 `showSubtitle` ⇒ `SettingsRow` 8 参 → 7 参
>    （白捡一处 detekt 违例）
> 2. `06bd5a4` **开关「不猜状态」收口**：8 个流 `StateFlow<Boolean>` → **`Boolean?`**、
>    初值 `null`；`SettingsSwitch` 移到 `SettingsComponents.kt` 改 `internal`（唯一实现）；
>    7 处裸 `Switch` 收口；删 `passkeyCount` 死代码
> 3. `561bf11` **快解三态开关**：`checked = x is On` 压扁 `Partial` ⇒ 拆成
>    **「继续」按钮 / `Switch`** 两种控件；新增探针 `check_state_flattening.py`（9 例自测）
> 4. `d98b4f0` `setting_autofill_save_prompt` 文案改清楚；新增探针
>    `check_orphan_strings.py`（834 条里 **126 条零引用**，基线 `--gate` 只减不增）
> 5. `1493f9e` README 补登探针
> 6. `f160ace` 记账 #120–#126 + **七篇计数校正**（7 篇里 6 篇数字是错的）
>
> **验证**：detekt 净 **18 → 17**（无新增）· Kotlin 类型检查 ✅ ·
> 两个新探针各自做**变异测试 + 闭环证明**（把代码还原成出 bug 的版本，看是否恰好报那几行）✅
> ⚠️ **沙箱无 Android SDK，未跑 Gradle 全量编译与单测。**
>
> ⚠️ **待真机验收（优先这两个）**：
> ① 设置页所有开关的**首帧**不再闪 `true`（此前 7 个裸 `Switch` 会）；
> ② 快速解锁 **Partial 态**（「已开启但只覆盖一部分应用」）现在显示为**「继续」按钮**，
> 不再是"关着的开关"。
>
> ⚠️ **本轮未做**：① Bastion 备份体系搬运（下轮）；② 126 条孤儿串的逐条判读
> （**不可自动化**：会毁掉刻意留档的对照文案，且无编译错误会拦住）；
> ③ 修 #125（`check_import_packages.py` 误判 `Icons.Filled.Build`，
> **别顺手豁免 `Build`** —— 会把真的 `android.os.Build` 误用一起放行）。
>
> ★ **下一轮起点 = 开放 KDBX 单条编辑（R-6…R-9）** —— 完整交接见
> **`.ai/SESSION-2026-09-26.md` §8**（前置事实 / 铁律 / 阻塞项 / 逐提交验证方式）。
> 细节 ⇒ **`ISSUES.md` #120–#126**、`.ai/SESSION-2026-09-26.md`。

> ## 🚧 【2026-09-22】Edge「偶尔不弹条目 / 偶尔提示无匹配」——按 Bitwarden 官方标准修 P0（**待真机验收**）
>
> 用户反馈：Edge 里**偶尔**不弹出密码条目，**偶尔**弹出但提示「密码库中无匹配条目」。
> 「偶尔」是关键判据 ⇒ 不是静态判定写错，而是**两次捕获的输入信号不同**。
>
> **参考 Bitwarden 官方源码核对后**（`ViewNodeExtensions.kt` / `ViewStructureUtils.kt` /
> `HtmlInfoExtensions.kt`）定位出**两条结构性 P0**（分类层本就对齐，不是问题所在）：
> 1. **容器重定向缺失**：语义挂在 `autofillType == NONE` 的容器上（`<form autocomplete=username>`、
>    包 `<input>` 的 `<div>`）时，框架回填会**丢弃整条 dataset** ——
>    所以表象是「整个条目都不出现」。上游 `findFirstAutofillableChild` 就是干这个的。
> 2. **逐字段站点用全局单值**：`webDomains.firstOrNull()` 把页面域名当成整页唯一值，
>    而框架只在**跨域 iframe 根节点**带 `webDomain` ⇒ iframe 内的框被当成主文档的框，
>    填充时站点校验不过 → 静默不填。上游是 `website = this.website ?: parentWebsite`。
>
> **已改**（3 文件）：
> - **新增** `autofill/parser/FillTargetResolver.kt`：重定向 + 继承的**纯函数**策略。
>   ⚠️ 抽出来是因为 `ViewNode` 是 `@SystemApi` 抽象类、JVM 单测构造不出实例；
>   逻辑留在解析器里就永远不可测。
> - 改 `autofill/parser/AssistStructureParser.kt`：接上重定向（语义落点 / 值 / 可见性
>   全部改用下钻后的节点）；`traverse` 增 `parentWebDomain` 参数做自顶向下继承。
> - **新增** `autofill/parser/FillTargetResolverTest.kt`：16 条用例。
>
> **验证**：16/16 通过；另做**变异测试**（`if (canReceiveValue)` → `if (false)`）
> ⇒ 7 条失败，确认断言真能挡回归而非恒真。
> 四道本地脚本：3 道 OK；`check_import_packages` 报的
> `SettingsScreen.kt` 图标导入告警经 `git stash` 比对确认是**既有问题、与本轮无关**。
>
> ⚠️ **本轮未做**（留给后续）：① 真机验收（Edge 复现路径）；② P1「统一解析侧与填充侧
> 的 `sameSite` 语义」；③ KVM 里无 Android SDK，故**整套 Gradle 编译与 `gw test` 未跑**，
> 只对新增纯函数文件做了独立 kotlinc 编译 + JUnit 实跑。
>
> 细节 ⇒ **`ISSUES.md` #113 / #114**、`conventions/8.1-自动填充.md`。

> ## ✅ 【2026-09-20 第三轮】胶囊阴影掉帧 + 强调色语义 + README 对齐 与 两处文档失真更正
>
> 提交 `475ac2a`（胶囊阴影）· `738d003`（强调色 + README + 文档更正）。
> 门禁：4 个本地脚本 OK · detekt 0 findings · 双变体编译 ✅ ·
> `gw test` **928 / 0 / 0**（918 → 928，`AvatarHueTest` 5 项 ×2 flavor）。
>
> **用户三条反馈的结论**：
> 1. **胶囊阴影闪动/掉帧** —— 两个成因叠加：①底色用**动画后**的值、阴影用**原始**
>    值 ⇒ 翻转不同步；②`shadowElevation` 一变就重建 `graphicsLayer` 阴影层。
>    改法：阴影**恒定** `1.dp`。（只统一判据不够 —— 那只是同步变，仍要重建。）
> 2. **「配色略单一」** —— 排查后是**强调色缺语义**，不是缺颜色（`primary` 已有 48 处）。
>    实质问题是那个 40dp 首字母头像**全是灰的**，而兜底才是常态。
>    改为按标题哈希取 12 档色相；明度 **0.32**（实测：0.45 时青黄区白字对比度仅
>    **2.57**，0.32 时 12 档全部 ≥ **4.75**）。另把能力图标色收进 `capabilityTint`。
> 3. **README** —— 版本 0.1.0 → **0.5.0**（落后五个小版本）、补网盘/卡包/权限页/
>    UV 豁免、验证码补全 5 种类型、里程碑按实际状态重写、新增「质量门」小节。
>
> **顺带更正的两处文档失真**（都是"文档说一套、代码做一套"）：
> - **身份字段数 17 → 18**，跨 16 个文件。已对官方 Bitwarden `Identity` 核实（18 个，
>   第 18 个是 `license_number`）⇒ **代码对、注释错**。
> - **`Docs/07` §2.2–2.3** 把"5 套主题 + 对比度"写成已有能力，实际只有
>   `ThemeMode{SYSTEM,LIGHT,DARK}` + `dynamicColor` + `oledPureBlack`。
>   未实现的移入"计划"并标注。
>
> 细节 ⇒ **`.ai/SESSION-2026-09-20.md` §6**。
>
> **⏳ 真机验收（5 条）**见 §6.6：阴影无闪动、头像各色且恒定、跨页同色、
> 深色/纯黑下可读、切主题颜色不变。
>
> ---

> ## ✅ 【2026-09-18 下半场】**验证码临期换码 + 权限引导页**（门禁全绿，**真机验收未做**）
>
> 提交 `a4c8686`（12 文件，+1135 / −35）。门禁：detekt **0 findings** ·
> `:app:compileFullDebugKotlin` + `:app:compileOfflineDebugKotlin` ✅ ·
> `gw test` **892 用例 0 失败 0 错误**（866 → 892，+26 = 13 个权限用例 × 2 flavor）。
>
> **做了两件用户当天口头提的事**：
> 1. **验证码页**（`ui/totp/TotpCodesScreen.kt`）：当前码剩余 ≤5 秒时点按复制的是
>    **下一个**码（对齐 Bastion `codeToCopy`）；行内新增小字 `Next` 预览。
>    阈值与警示色**共用 `TOTP_HOT_WARNING_SECONDS`**，不新开常量。
> 2. **权限引导页**（**新增** `ui/permissions/`）：设置页「关于 → 权限管理」由
>    「直接跳系统页」改为「先进应用内页」，三态状态卡 + 分项用途说明 + 行尾动作。
>    **只列真正用得到的四项**（相机 / 通知 / 指纹 / 网络）—— NFC 等三项是传递依赖，
>    故意不列。
>
> **顺手修的**：扫码页权限死胡同（被「拒绝且不再询问」后按钮点了没反应，用户卡死）。
> **顺手证伪的**：一次"修 `sha256Hex` 跨作用域"的误判 —— 原代码没问题，已完整还原，
> 并纠正了 `.ai/SESSION-2026-09-18.md` §5.1 里那个**错误的理由**。
>
> 细节 ⇒ **`.ai/SESSION-2026-09-18.md` §8** 与
> **`.ai/decisions/设置页信息架构-定稿.md` §12**。
>
> **⏳ 新增的三个小尾巴**：
> - `SystemSettingsIntents.openAutofillSettings` 目前**无人调用**（为自动填充设置去重预备）；
> - 临期换码的**真机秒边界行为**未验收；
> - 权限页 **Android 13+ 通知分支**单测覆盖不到（测试环境 `SDK_INT` 读到桩值 0）。
>
> ---
>
> ## ✅ 【2026-09-18】**设置页施工单 §1~§4 全部落地**（门禁全绿，**真机验收未做**）
>
> 门禁：detekt **0 findings** · `:app:compileFullDebugKotlin` ✅ · `gw test` **866 用例 0 失败**。
> 细节与两处改判的证据 ⇒ **`settings-rework.md` §6** 与 **`.ai/SESSION-2026-09-18.md`**。
>
> **⏳ 现在真正待做的两件事**：
> 1. **真机验收 §1~§4**（各节验收清单 + 装机后比 SHA-256）；
> 2. **原生化评估的阶段 0（三个真机测量）** —— 见 `native-rewrite-eval.md` §4。
>    ⚠️ **没拿到那三个数之前，不要启动任何 Rust/Go 工作。**
>
> ---
>
> ## ✅ 【2026-09-17 傍晚】**R1–R4 已全部完成**（下面那条 2026-09-17 下午的「待开工」块已作废，保留仅作背景）
>
> 门禁全绿：detekt（全模块）· `:app:compileFullDebugKotlin` ·
> `:data:kdbx:testDebugUnitTest` **47 项 0 失败**（★ 此前从未运行过）·
> `:data:repository:testDebugUnitTest`。逐条细节 ⇒ **`.ai/SESSION-2026-09-17.md` §11**。
>
> **⏳ 现在真正待做的两件事**：
> 1. **真机验收**（R1–R3 只过了编译与静态检查）：WebDAV 加库 → 解锁 → 改条目 →
>    KeePassXC 能开（字段没丢）· OneDrive 真机登录链路 · 断网中断不损坏 ·
>    以及 09-17 凌晨那 5 组 UI。**再加：`git commit`（工作区 16 个文件尚未提交）。**
> 2. 🔴 **通行密钥「反复要求解锁」**（用户新报）：Bitwarden 库 +「从不加锁」+ 清后台后
>    网页登录 ⇒ 指纹过了又要解锁约 3 次，刷新页面后正常。
>    ⚠️ **先取证再动代码**（`AutofillLogger` 只在 debug 构建输出）——
>    两个待查点与 logcat 命令见 `.ai/SESSION-2026-09-17.md` §11.6。
>
> ---
>
> ## ▶ 【2026-09-17 下午】~~待开工：KDBX 网盘同步的剩余工作~~（**已完成，见上**）
>
> **⚠️ 这一条覆盖下面全部。** 用户指令：「写好文档进度吧，我重开一个会话一次性完成」。
>
> ★★ **工作单在 [`cloud-sync-plan.md`](./cloud-sync-plan.md) 的 §16**（自包含，
> 零上下文会话可直接照做）。**开工前必读 §16.1。**
>
> ### 先记住这一句：**代码是完整的，但用户一个网盘库都加不进来、也打不开**
>
> 根因不是 UI 没做，而是**读、写两条路径用了两个不同的接口**：
>
> | 动作 | 走哪个接口 | 支持网盘？ |
> |---|---|---|
> | **写回**（`Kdbx.saveVia`） | 新的 `KdbxFileSource` | ✅ |
> | **读 / 解锁 / 添加 / 凭据校验**（`Kdbx.unlock` / `Kdbx.verify`） | **旧的 `KdbxSource`** | ❌ |
>
> 旧的 `KdbxSource` 唯一实现是 `VaultRepositoryImpl` 里一个 `ContentResolver` lambda
> —— **只认 SAF `content://`**。⇒ 把 `"webdav:…"` 丢给 `Uri.parse` 必然读不到。
> **所以 UI 只是"看不见"，读路径未迁移才是"点了也没用"。**
>
> ### 剩余 4 项（详见 §16）
>
> | # | 事项 | 说明 |
> |---|---|---|
> | **R1** ★ 必做先做 | 把读路径迁到 `KdbxFileSource` | **5 个调用点**（`VaultRepositoryImpl` ×3 · `LocalUnlockEnrollment` · `PinEnrollment`）。不迁则 R2/R3 做完也白做 |
> | **R2** | WebDAV **凭据写入** + 配置 UI | ★ 读侧接线是完整的、**写侧一行都没有** ⇒ `credentials()` 恒 null ⇒ **WebDAV 当前 100% 走不通**。且顺序必须是「先存凭据 → 再 testConnection」 |
> | **R3** | OneDrive 配置 UI | 鉴权/Graph/文件源都已就绪，主要是接 UI（`signIn` **必须传真实 Activity**） |
> | **R4** | `KdbxWritePathTest.kt`（9 用例）**从没跑过** | 沙箱无 JUnit/Truth；另复核 CI 的 test 模块清单是否含 `data:kdbx` |
>
> ⚠️ **R5（免解锁「用远端覆盖本地」）放到第二期** —— 现状是**有意不做假成功**，
> 不是没做完。理由见 §16.6。
>
> ⚠️ **施工纪律在 §16.8**（12 条，逐条都是付过代价的：门禁含 `test`、
> detekt 两种口径、前置项红≠后面都过、`VaultRepositoryImpl` 顶格 40 函数……）。

> ## ✅ 【2026-09-17 白天】KDBX 接入 OneDrive / WebDAV —— 批次 0~6 全部完成
>
> `910b571`（特性，36 文件 / +4813 行）→ 5 轮 CI 修复 → `96a82fa`（CI 纳入 `data:kdbx` 单测）。
> **155 条沙箱断言 0 失败**，CI 在 `bb5ebb5` 全绿，preview APK `app-full-debug.apk` = 35,883,835 字节。
> 按 [`cloud-sync-plan.md`](./cloud-sync-plan.md) 施工；**§14 的 Q3 采纳了「只做方案 B（拒写）」**。
> 明细与教训 → `.ai/SESSION-2026-09-17.md` **§10**（含 5 轮 CI 各红一层的复盘 + 5 个坑）。

> ## ✅ 【2026-09-17 凌晨】5 组 UI 入库 + **KDBX 写入口止血（坑 #106）** + OneDrive 鉴权骨架
>
> **⚠️ 这一条覆盖下面全部**。用户输入只有两段：
> 「我项目里面的 kdbx 的同步功能还没接上。比如 onedrive，以及 webdav」
> 「ai 目录等内容的记忆也要记录同步到最新的」。详见 **`.ai/SESSION-2026-09-17.md`**。
>
> | 项 | 结果 |
> |---|---|
> | **5 组 UI（承接 09-16 诉求）** | ✅ `5017029` 填充行收窄 · `8654e62` 卡片圆角 12→16dp + 统一描边 · `be8b15e` 表单改 4 张分组卡 · `5b4b41c` 全屏外壳改叠加布局（真沉浸） · `5b9ff76` 多选栏摘「全选」+ 修被悬浮 Dock 遮挡 |
> | **KDBX 写入口止血（坑 #106）** | ✅ `8fc1125` —— **不是用户报的**，是排查网盘同步前置条件时发现的 |
> | **补齐单测编译断链** | ✅ `4d3e639` —— 预先存在、被本轮改动暴露（见下方 🔴） |
> | **OneDrive 鉴权骨架** | ✅ `37e963b` MSAL 8.4.2 + `res/raw` 配置 + manifest 回调 Activity + 鉴权类 + Graph 只读客户端 |
> | **门禁** | ✅ detekt · `:app:compileFullDebugKotlin` · 单测 **656 项（去重）0 失败** |
> | **推送** | ✅ `d17772b → 37e963b` |
>
> ---
>
> ### 🔴 坑 #106：读路径分流了 KDBX，写路径没有 ⇒ 两个方向都在骗人
>
> | 环节 | 修前现状 |
> |---|---|
> | 读 | `observeItems` 按 `kind` 分流：KDBX 走 `Kdbx.contentOf`（**内存，不在 Room**） |
> | 写 | `createItem` **无任何 kind 判断** ⇒ 写出 **Room 孤儿行**，读侧永远看不到（**静默丢失**） |
> | 写 | `updateItem` 先查 `cipherDao.get(id)`；KDBX 条目 id 是 `itemIdOf(uuid)` ⇒ 报**「条目不存在」**（与真因无关） |
>
> 修法：`domain.ReadOnlyVaultException` + `ItemRepositoryImpl.requireWritable` 守在 **8 个写方法**的
> `runCatching` 首行 + UI 收起写入口（详情页编辑/删除、条目页 FAB 与底部栏「+」）。
> ⚠️ `cleanupExpiredTrash` 也要拦（残留孤儿行会让它给 KDBX 库入队**永不推送的毒丸**）；
> ⚠️ `removeFido2Credential` 要单独拦（否则先走 `loadItem` 报出与真因无关的错）。
>
> > **判据（写进纪律）**：**读路径按 `kind` 分流了，写路径就必须同时分流。**
> > 否则「读 A 存储、写 B 存储」必然产生幽灵数据，**且两个方向都骗人**。
>
> ---
>
> ### 🔴 门禁教训：「任务 UP-TO-DATE」≠「那段代码是好的」
>
> 本轮跑 `test` 才炸出 `data:repository` 的单测**编译**断链（`ItemRepositoryImplTest` 缺
> `atomicWriteDao`；两个 `VaultRepository*Test` 缺三个 enrollment 参数）。
> **全是更早的提交遗留**，只因那些提交**只跑了 `:app:` 编译、没跑 `test`**，而
> `compileDebugUnitTestKotlin` 一直 **UP-TO-DATE** ⇒ 从未被重新编译。
>
> > ★ **门禁必须包含 `test`，不能只跑 `compile`。**
> > 一个任务只要输入没变过就永远不检查；改了某模块的**生产代码**后，
> > 它的**测试编译**才会失效，那时才暴露积压的断链。
>
> ---
>
> ### ▶ 下一步（按优先级）
>
> ★★ **KDBX 网盘同步（OneDrive / WebDAV）的完整方案已写好：
> [`cloud-sync-plan.md`](./cloud-sync-plan.md)** —— 含前置可行性核实、文件源接缝、
> 冲突策略三方案、5 个批次的任务拆解、9 条验收标准、**7 个待拍板问题**。
> **要动网盘，先读这一篇，别从零设计。**
>
> 1. **设置页加「连接 OneDrive」入口 + 登录按钮** —— 交互式登录需要 Activity 才能拉起，
>    必须真有按钮才能真机跑通「登录 → 拿 token → 列出网盘 `.kdbx` 文件」。
>    （外部风险已摸清：Azure 配置实测复核通过、微软 feed、manifest、依赖全部就位。）
> 2. ★ **KDBX 阶段 B（写回）** —— 网盘同步的**地基**，也是三处文档共同的「最大未完成块」。
>    **可行性已核实**：kotpass 0.13.0 有 `encode(...)`，且项目已有的
>    `KDBX_CIPHER_PROVIDERS` 正是它要的参数 ⇒ **不需新增依赖**。
>    先在本地 SAF 打稳（原子替换 + `.kdbx.bak` + 往返测试 + `KPEX_*` 保真），再考虑接网盘。
> 3. **先做 WebDAV 还是 OneDrive？** —— 方案文档建议**先 WebDAV**
>    （不需要 OAuth 注册、用户自己的 NAS 直连、无 Azure feed 那种外部单点）；
>    OneDrive 鉴权已完成，不会浪费。
> 4. KDBX 回收站映射（现在只计数，不进回收站页）。
>
> ⚠️ **开工前需用户拍板**（见方案文档 §14）：冲突策略（三方合并 / 拒写报冲突 / 分期）、
> `KdbxFileSource` 与 MSAL 的模块归属、自动同步的触发时机。

> ## ✅ 【2026-09-16】主密码解锁误触发重新登录 + 条目详情/新建页 M3 + 设置页搬运合并
>
> 用户睡前下单、睡醒验收（见 `.ai/SESSION-2026-09-16.md`）：
> `bfb0179`（解锁误触发重新登录 / 索要服务端 2FA）·
> `f5ec95c`（条目详情 / 新建编辑页 M3 2026）·
> `a56da7c`（设置页：从 Bastion 搬运 + 已有项合并精简 + M3）。
> 同轮另有工具链深挖（记忆分层 / 文档分篇 / CI 与 APK 分块下载验证）交
> `2d1adae` / `70651e7` / `f622f51`（+ 文档 `aa133c9`）；CI `run 35084153580` success。

> ## ✅ 【2026-09-15 第六十六轮·五续】通行密钥漏订阅已真修 + 三个弹窗按 M3 重做 + 堵上 `@OptIn` 的缝
>
> **⚠️ 这一条覆盖上一条（四续）**：四续说「三续的代码没问题」，但**用户真机验收驳回了** ——
> 通行密钥页仍然「必须点一下搜索才出现」。**是我错了**，本轮才找到真病根。
> 详见 `.ai/SESSION-2026-09-15.md` **§11** 与坑 #103。
>
> | 项 | 结果 |
> |---|---|
> | **通行密钥「必须点搜索才显示」** | ✅ 真根因 = **漏订阅**（详见下方），修后冷启动首帧即渲染 |
> | **三个弹窗按 M3 重做** | ✅ 当前密码库 / 快速解锁 PIN / 添加密码库，共用新 `ui/common/DialogShell.kt` |
> | **`@OptIn` 缺注解导致 CI 挂** | ✅ 修 `fc1fa6c` ⇒ CI run `34938509113` = **`conclusion: success`** |
> | **新增守护工具** | ✅ `.ai/tools/check_experimental_optin.py`（含 `--selftest`，5/5 通过） |
> | **APK** | ✅ `preview` Release 的 `app-full-debug.apk`（**32.2 MB**，对应 commit `fc1fa6c`） |
>
> ---
>
> ### 🔴 通行密钥那条 bug 的真根因（接力必读，别再修错方向）
>
> **症状**：打开通行密钥页看不到条目；点一下搜索就全出来了。
> **上一轮我误判成** #84「分支塌陷」，只调了空态分支顺序 —— **没碰病根，被用户驳回**。
>
> **真根因**：`PasskeysScreen` 调的是 `viewModel.passkeyRows()`，函数内部读 `_state.value`。
> **普通函数调用不会订阅 Flow、也不参与 Compose 重组** ⇒ 首帧算出空列表后
> **再无任何机制触发重算**，页面钉死在空态。点搜索翻转 `searchActive` ⇒ 逼出一次重组 ⇒ 条目才出现。
>
> **正确写法**（对齐验证码页 `rememberTotpEntries`，那边早修过同一个坑）：
>
> ```kotlin
> @Composable
> private fun rememberPasskeyRows(state: PasskeysViewModel.UiState): List<PasskeyRow> =
>     remember(state.items, state.query) {
>         state.items.toPasskeyRows().filter { it.matches(state.query) }
>     }
> ```
>
> 纯逻辑抽成 `List<VaultItem>.toPasskeyRows()` + `PasskeyRow.matches(query)`（**不读 `_state`**）。
>
> > **可复用判据**：界面要用的任何派生数据，都必须从**「已收集的 state 快照」**算出来。
> > **排查启发式**：用户描述里出现「**要点一下某个无关按钮才出现**」⇒ 八成就是漏订阅。
> > **症状的形状本身就是诊断** —— 别用症状的相似性去套旧结论。
>
> ---
>
> ### 🔴 第二处盲区：实验性 API 的 `@OptIn`（与 #101 同类）
>
> `BasicAlertDialog` / `ModalBottomSheet` / `TopAppBar` / `ExposedDropdownMenuBox` 等
> 都是 `@Experimental*`，**每个调用点都要手写 `@OptIn`**。
> **本工程没有任何工程级 opt-in 编译参数**（`app/build.gradle.kts` 的
> `kotlin { compilerOptions }` 里只有 `jvmTarget`）⇒ **没有兜底，只能靠编译器**。
>
> **三个本地工具全看不见它**：detekt 不做符号解析、不看注解；
> `check_signature_types.py` 只看签名里的类型名；`check_import_packages.py`
> 看的包路径**完全正确**（`BasicAlertDialog` 确实在 `material3`）。
>
> 新增 `check_experimental_optin.py` 堵上。**改完 UI 后跑一下**（`--changed` 只扫本次改动）：
>
> ```bash
> python3 .ai/tools/check_experimental_optin.py --selftest   # 先验工具自己
> python3 .ai/tools/check_experimental_optin.py --changed    # 再扫改动
> ```
>
> ⚠️ 它的判据是**注解作用域**（自己的注解 / 调用链上有一层带了 `@OptIn`，迭代到不动点），
> **不是文本搜索** —— 头一版用文本搜索在干净仓库上误报了 17 处，已废弃。
> **已声明的漏报边界**：跨文件的「调用了某个已 opt-in 的 composable」判不了。
>
> ---
>
> ### ⏭️ 本轮之后的待办
>
> 1. **装机验收**（用户）：通行密钥冷启动即显示（**不许再需要点搜索**）+
>    三个弹窗（当前密码库 / 添加密码库 / 快速解锁）的新观感。
> 2. **把 `check_experimental_optin.py` 接进 CI**（目前只在本地跑，可省一轮 CI）。
> 3. 其余旧待办顺延：`detail_totp_hidden` 指路语、M3E P1-2/P1-3、
>    P2 expressive AppBar、应用内 PIN 解锁 debt、Gradle 8.11.1 vs wrapper 9.5.1 版本错配排查。
>
> ---

> ## ✅ 【2026-09-15 第六十六轮·四续】CI 编译失败已修 + 补上 `import` 盲区守护工具
>
> **⚠️ 这一条是上一条（三续）的前置**：三续的代码首次推送后 **CI 编译没过**，
> 现已修复并**全绿**，APK 可下载装机。详见 `.ai/ISSUES.md` **#101**。
>
> | 项 | 结果 |
> |---|---|
> | **CI 编译失败修复** | ✅ `PasskeysScreen.kt` 的 `WindowInsets` 导入包写错（`material3` → `foundation.layout`），修 `e3f6f05` |
> | **CI 复验** | ✅ run `34930096397` → **success**（`Build Debug APK` ✓ / `Publish … preview Release` ✓ / detekt ✓） |
> | **新增守护工具** | ✅ `.ai/tools/check_import_packages.py` —— 校验 `import` 包路径 |
>
> **可下载 APK**：`preview` Release 资产的 `app-full-debug.apk`（30.7MB），
> tag 为 `dev-e3f6f056…`（与该 commit 对应）；证书与本机一致 ⇒ **覆盖安装不丢数据**。
>
> ---
>
> ### ⚠️ 本轮暴露的工具盲区（接力必读）
>
> **detekt + `check_signature_types.py` 都查不出"符号名写对、包名写错"**：
> - detekt **不做符号解析**，只跑静态规则；
> - `check_signature_types.py` **只读函数签名里的类型名，从不读 `import` 语句**。
>
> ⇒ **`import` 行此前是一条无人看守的缝。** 新增的
> `check_import_packages.py` 用「**独占包一致性**」判据堵上它：
> 某符号若全仓库只从一个包导入过（样本 ≥2），偏离即报错。
> **改完 import 后跑一下**（`--changed` 只扫本次改动）：
>
> ```bash
> python3 .ai/tools/check_import_packages.py --changed
> ```
>
> **回归验证**：注入原错误会报 `L47: WindowInsets 导入自 material3，约定为 foundation.layout`，
> **行号与 CI 报的 `47:35` 吻合** ⇒ 当时若有它，可省掉一整轮 CI。
>
> ## ✅ 【2026-09-15 第六十六轮·三续】通行密钥对齐 + PIN 键盘下移 + 密码库管理合并 —— **已完成，待本机验收**
>
> 用户报的两件 UI 事 + 一条架构诉求（问句，按推荐方案已做）。
> 详见 `.ai/SESSION-2026-09-15.md` **§9**。
>
> | 项 | 结果 |
> |---|---|
> | **通行密钥页对齐三列表** | ✅ 换 `EntryCard` + `SiteIconByHost` + 沉浸顶栏 + `Spacing.lg` + `bottomInset` + 左滑删除 + 长按多选 |
> | **解锁页 PIN 键盘下移** | ✅ 键盘底从 **57%~82%** → **88%~92%**（六档屏高验算，零溢出） |
> | **设置页「密码库管理」合并** | ✅ 新建 `VaultManagementScreen.kt`；首页「密码库」组只剩 1 行入口 |
>
> **装机验收（本轮）**：
> 1. **通行密钥页**（验证码页右上「通行密钥」入口）：卡片应为 **M3 卡片底 + 站点图标**，
>    观感与密码条目页 / 验证码页**一致**；左右留白与另两页相同；
>    顶栏**大标题能随滚动收起、内容从它下面穿过**（沉浸）；
>    **左滑可删**、**长按进多选**（底部出现批量操作条）。
> 2. **解锁页 PIN**：点「用 PIN 解锁」后，**数字键盘应贴在屏幕下 1/3**
>    （拇指能自然够到），不再停在中部偏上；「改用主密码」仍在键盘正下方；
>    主密码模式的**居中观感应完全不变**；小屏上内容仍可滚动、不溢出。
> 3. **设置页**：首页「密码库」组**只有 1 行**「密码库管理」（副标题 = 当前库名）；
>    点进去有 3 行：当前密码库 / 添加密码库 / 快速解锁；
>    该页的选库对话框行为与之前**完全一致**（已解锁库→切过去；**未解锁库→去解锁页**；
>    「设为默认」仍可对未解锁库用）；
>    「解锁与隐私」组里**不再有**「快速解锁」，只剩自动锁定 / 防截屏 / 剪贴板清除。
>
> ⚠️ 本轮**零行为变化**：只是把已有入口搬家，没新增 / 删除任何设置项，没改任何默认值。
>
