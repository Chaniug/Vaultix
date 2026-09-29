# AI 协作记忆

> 供 **AI 接力**使用。开工前必读，收工后必更新。

## ⚠️ 唯一真源在这里（2026-09-13 起）

**`.ai/` 是记忆的唯一定稿处，已入库、并被代码注释直接引用。**
`.workbuddy/memory/MEMORY.md` 只是**入口指针 + 3 条保命规则**，**不再镜像正文** ——
此前约定「两边保持同步」，结果是同一主题两处各有一份、必然漂移（AI 会看错位置）。
**要改内容，只改这里。**

## 🕐 最新状态（**2026-09-29 收工 · 快速解锁「房子化」批次 1-4 + 批次 5.0**·交接点，接力请先看这几行）

> ### ★ 本轮（2026-09-29 收工）—— 批次 5.0：软锁 + 前台门禁 + 解锁提速（定稿 §6.2）
> **触发**：用户真机反馈 —— ①「设置**从不**，锁屏/清后台后 Vaultix 直接就是开着的，
> 有风险」（对照 Bitwarden 会锁）；②「指纹解锁进密码库要等好几秒，应秒解秒进」。
> 用户拍板**方案 A**：对齐 Bitwarden —— 离开 App 就真锁，回来靠恢复信封免交互自动开。
> 1. ★★★ **核心洞察：软锁 vs 硬锁 = 信封的留与删**：
>    **软锁**（新 `AutoUnlockRepository.softLock`）= 离开 App 触发，清密钥 + **留**信封
>    ⇒ 回来自动开；**硬锁**（既有 `lockVault`/`lockAll`）= 点锁定，清密钥 + **删**信封
>    ⇒ 回来过门锁。旧 bug 根因 = `Never -> return@launch`（**任何原因都不锁**，密钥常驻内存）。
> 2. ★★★ **前台门禁是安全底线，不是优化**：软锁把 `houseKeyInMemory` 翻 false，而
>    `AutoRestoreTrigger` 正 observe 它 —— **没门禁会立刻把钥匙读回内存、等于没锁**。
>    修法：`AutoLockController.isForeground` 作 `combine` 第三源，**非前台一律不恢复**。
> 3. ★★ **指纹提速 = 消除 N 次串行 Keystore 往返**：旧 `candidateVaultIds()` 每库调
>    `fingerprintQuickUnlockAvailable(id).first()`（内含一次 Keystore 往返）⇒ 改「一次读
>    范围快照（`preferences.quickUnlockScope()`，零 Keystore）+ 内存求交」。
> 4. ★ **先开核心库再异步补开其余**：`completeLocalUnlock` 拆两段（`LocalUnlockFanout.openRest`
>    + `Result.lockOpened`）—— 其余库是附加收益，不该挡住用户点的那一个。
> 5. **测试纪律（本批又踩）**：非 suspend 成员（`lock()` / `isUnlocked`）只能 `every`
>    （`coEvery` 静默失效，运行时才报 `no answer found`）；含 `withContext(Dispatchers.IO)`
>    的实现**必须轮询终态**（`advanceUntilIdle()` 管不到真实线程池）。
> 6. **回归测试做变异验证**：`VaultLockManagerImplNeverTest` 临时还原旧实现确认变红。
>
> 门禁：detekt / `:app:compileFullDebugKotlin` / 单测 **398 全过 0 failed**
> （`data:repository` 113 + `app` 285）+ 孤儿串未超基线。
> ⚠️ **当前位置：`main` 已推送；`rele` 未动。**
> **下一轮起点 = 批次 5（真机验收清单 1-11，需真手指）** —— ⭐ 新增第 10 条
> （Never 档离场软锁 + 回来自动开）、第 11 条（多库指纹秒进）。
> **接力入口（自包含）：[`Docs/progress/quick-unlock-house-rework.md`](../Docs/progress/quick-unlock-house-rework.md)**（批次 5.0 专节）
> · 定稿实施记录 **§6.2**。

> ### （上一轮）—— 快速解锁「房子化」批次 4：失效矩阵（定稿 §6）
> 1. ★★ **rearm 的真实形态是「延迟重装」，不是「静默重包」**：硬约束 #2
>    （auth-per-use 一次授权只保一次 `doFinal`）⇒ 重写门锁信封**必须**再弹一次认证。
>    故 =「失效时只打标记（`house_lock_fingerprint_rearm_pending`）→ 下次认证时补写」。
>    用户拍板「可以接受重新安装」。
> 2. ★★ **开门 / 关门唯一正确判据 = `HouseKeyStore.isUnlocked`**（不是「信封存不存在」——
>    重录指纹后两者分叉，误用后者会把 rearm 走成降级、**连带清掉房间信封**）。
> 3. **降级绝不静默**：`UnlockRecoveryRepositoryImpl.degradeFingerprintLock()` → 禁锁 +
>    三选一明确文案 + 回主密码；无指纹信封时 no-op。
> 4. **StaleCredentials**：`RoomResealRepositoryImpl.resealRoom(vaultId, newMasterPassword)`
>    只重包**该房间软件信封**，门锁不动；`UnlockViewModel` 在 `StaleCredentials` 分支调用。
> 5. **PIN 熔断全局 5 次**：批次 1 已达成（N 信封 → 1 门锁信封 ⇒ 计数天然全局）。
> 6. 新增失效三态分类 `LocalUnlockFailureKind { Recoverable, Rearmable, Unavailable }`。
>
> **接力入口（自包含）：[`Docs/progress/house-rework-batch4-handoff.md`](../Docs/progress/house-rework-batch4-handoff.md)**
> · 定稿实施记录 **§6.1**。

---

> ### ★ 上一轮（2026-09-29 深夜续）—— 真机验收反馈修复：「从不锁定」对齐 Bitwarden + 解锁方式三行精简
> 1. ★★ **硬约束 #1 修订（定稿级，见 `.ai/decisions/快速解锁房子化-两级钥匙层级-定稿.md` 顶部横幅）**：
>    「房钥匙绝不落盘」→「**绝不以明文落盘**」。对标 Bitwarden `userAutoUnlockKey`
>    （`reference/bitwarden/` 稀疏克隆核证：keystoreEncryptedPreferences 载体、
>    进程重启无交互自动恢复）。落地为第三把锁 `AutoUnlockKeyStore`（免认证 Keystore 密钥）。
> 2. **信封生命周期协调器 `AutoRestoreTrigger`**（挂「解锁成功事件」防 lockVault 死角）：
>    Never 且钥匙在内存→幂等写；无钥匙有信封→自动恢复；主动锁库→删（真锁）；
>    档位改离 Never→删。域接口独立 `AutoUnlockRepository`（VaultRepositoryImpl 顶格 40 函数）。
> 3. **autofill 双管齐下**：`buildResponse` 1s 恢复等待窗口（对齐 Bitwarden 500ms 等 UNLOCKING）；
>    `ItemRepositoryImpl.observeItems` 改 **Eagerly 共享缓存**（原冷流每次 `.first()` 全量重解密 = 卡顿主源）。
> 4. **设置页**：删第三行「管理解锁方式」；行点击 = 进向导（`manageBiometric`/`managePin`），
>    开关 = On 关 / Off 进向导（关闭是低频破坏性动作，防误触）。
> 5. **新增 `HouseKeyStoreTest` auto 信封 3 用例**（生命周期 / 损坏自愈 / 门锁全删连带清）。
>
> ⚠️ **上一轮位置：`main` 已推送（含该轮提交）；`rele` 未动。**
> （本轮批次 4 已在其上继续推进，见最顶部状态块。）
> ~~下一轮起点 = 批次 4（失效矩阵）~~（✅ 已完成）。

---

> ### ★ 又一批（2026-09-29 深夜）—— 批次 3：设置页简化「删五类」，顺带补上批次 2 遗留 #1
> 1. **删 `Partial` 三态**：开关收敛为**二值**（`On`/`Off`），判据只有「**门锁装没装**」。
>    「范围内 N 个库配好了几个」这个量**不再决定任何 UI** —— 它正是 #93「谎报状态的开关」
>    的成因，房子化后在结构上不存在了（定稿 §5.1）。
> 2. **删每库「指纹 / PIN」角标**：向导的范围列表变**纯复选框**（房子化后门锁是全局的，
>    「这个库配了指纹没配 PIN」这种区分已经不存在）。
> 3. **结果页**：成功/跳过改**计数**（「已纳入 N / 跳过 M」），**失败仍逐条列**
>    （失败必须可行动 —— 只知道"有 2 个失败了"，用户唯一出路是全部重来）。
> 4. **副标题**改定稿 §5.1 文案；PIN 那句的两个数字由 `PIN_MIN_LENGTH` / `PIN_MAX_ATTEMPTS`
>    **传入而非写死**（文案数字与阈值分叉 = 一句不成立的承诺）。
> 5. ★★ **批次 2 遗留 #1 已解决**：把 `lockState` / `locksToOpen` / `roomSealingBlocker` /
>    `assemble` 挪成**文件级 `internal` 纯函数** ⇒ `QuickUnlockControllerTest` 7 条改写为
>    **17 条**，动作表判定终于有行为级证据。**代价要说清**：钉住的是**判定逻辑**，
>    不是整条编排流程（后者仍要靠批次 5 真机验收）。
>
> ⚠️ **上一轮当前位置：`main` 已推送（批次 2 `61ea1a3` + 批次 3 提交）；`rele` 未动。**
> （本轮批次 4 已在其上继续推进，见最顶部状态块。）
> **接力入口（自包含）：[`Docs/progress/house-rework-batch3-handoff.md`](../Docs/progress/house-rework-batch3-handoff.md)**

---

> ### ★ 本轮（2026-09-29 夜）做了什么 —— 快速解锁「房子化」批次 2 落地（动作表 + 重登记 + 扇出门禁）

> ### ★ 本轮（2026-09-29 夜）做了什么 —— 快速解锁「房子化」批次 2 落地（动作表 + 重登记 + 扇出门禁）
> 1. **动作表重排（`QuickUnlockController`，定稿 §5）**：勾库 = **纯软件封装**
>    （`sealRoomsForVaults`，**不碰指纹不碰门锁**）；开锁 = 各**一次** wrap；
>    **已装着的门锁不重开**（`Session.locksToOpen` 与 `methods` 分开 —— 否则每次进向导
>    都要再按一次指纹）；`pendingRooms()` 收敛为「房间信封存在性」**单维度**
>    （房间是共享的，与选了哪种方式无关）。
> 2. **顺序约束加第二层**：房钥匙还得**在内存**（绝不落盘 ⇒ 重启即失），
>    新增 `LocalUnlockEnrollment.isHouseKeyReady`，**拦在问主密码之前**（输完一轮 KDBX
>    密码才说"不行"是编排层的反面教材）。
> 3. **重登记向导（旧信封清理）**：新建 `LegacyQuickUnlockCleanup`（三个旧信封前缀 +
>    两个旧 DataStore 前缀，**只枚举键名不解密**）；控制器**只在本次至少建成一个房间信封
>    之后**才清、失败则回滚新开的门锁 ⇒ 「同批生效或整体回退」的"不半新半旧"落点。
>    设置页顶部新增「快速解锁已升级，需重新登记一次」提示行。
> 4. **新建 `LocalUnlockFanoutTest`（8 用例）**：钉的是**扇出形状** ——
>    `completeFingerprintUnlock` **恰好 1 次且不随库数增长**（旧形状 N 库 = N 次 Keystore = H2）。
>
> ⚠️ **当前位置：`main` 已推送（`61ea1a3`）；`rele` 未动。**
> **下一轮起点 = 批次 3（设置页简化：删五类 / 副标题 / `QuickUnlockControllerTest` 改写）**，
> 之后批次 4（失效矩阵）→ 批次 5（**真机验收，必做**）。
> **接力入口（自包含）：[`Docs/progress/house-rework-batch2-handoff.md`](../Docs/progress/house-rework-batch2-handoff.md)**

---

> ### ★ 上一轮（2026-09-29 白天）—— 快速解锁「房子化」批次 1 落地（钥匙层核心）
> 1. **门禁三关实测全绿**（detekt / `:app:compileFullDebugKotlin` / 单测，含新建
>    `HouseKeyStoreTest` 6 用例）：两把全局门锁（指纹 KEK / PIN Argon2id 各一信封）包同一把
>    随机 256-bit 房钥匙（**仅内存、绝不落盘**，硬约束 #1）+ 每库纯软件房间信封
>    （AES-GCM，AAD 绑 vaultId 防错位）。**H1（一把 cipher 连包 N 库）与 H2（rest 库现取
>    新 cipher 无人授权）已结构性消灭**；新建 `HouseKeyStore.kt`（~470 行，两级钥匙层唯一协调者）。
> 2. **契约重排 + 扇出重写**：`VaultRepository` 17→16 方法（新增 `RoomUnlockOutcome` 三态）；
>    `LocalUnlockFanout` 重写为「**1 次** `completeFingerprintUnlock`（解锁路径唯一 Keystore 操作）+
>    **N 次** `unlockVaultFromRoom`（纯软件）」——首库特殊化随 H2 一起消失。
>    ⚠️ `QuickUnlockController` **只做编译级适配**（行为可用、不背叛定稿；完整重排 = 批次 2 第一件事）。
> 3. **批次 0（P0 判别实验）取消**——批次 1 的结构性修复同时消灭 H1/H2，判别失去意义；
>    真机全链路验收（批次 5 清单）仍必做，尤其「指纹一次开多库」「杀后台后必须重新解锁」。
>
> ⚠️ **当前位置：`main` 已推送（`ae80aae`）；`rele` 未动。**
> ✅ **上面列的「下一轮起点 = 批次 2」三条已于同日夜间全部完成**（`61ea1a3`），
> 见最顶那个状态块 ⇒ **接力请从最顶读起，别再从这一条开工**。
> 本块保留作**钥匙层模型的速查**：模型速查 / 存储键表 / 16 方法契约 / 15 项刀序 /
> 5 条遗留 —— 全在
> [`Docs/progress/house-rework-batch1-handoff.md`](../Docs/progress/house-rework-batch1-handoff.md)。

---

> ### ★ 本轮（2026-09-26）做了什么 —— 四句话
> 1. **设置页「先修坏的」六件事全部落地**（用户拍板：先修坏的，再谈搬运）：
>    ① 开关**不再猜状态** —— 8 个流 `StateFlow<Boolean>` → **`Boolean?`**、初值 `null`
>    （`initialValue = true` 把「偏好没读出来」伪装成「明确开着」）；在同一页上
>    **3 个开关用占位、7 个用裸 `Switch`**，两种行为并存 ⇒ 收口到唯一的 `SettingsSwitch`（#120）；
>    ② **快解三态开关失联** —— `checked = x is S.On` 把 `Partial`（「开了一部分」）
>    渲染成**关着的开关**，类型/detekt/穷尽性**全都查不出**（#121）；
>    ③ 副标题截断为 2 行 · ④ 删 `passkeyCount` 死代码 · ⑤ 一条文案改清楚 · ⑥ 新探针两个。
> 2. **★ 记一条勘误（影响每个"再加一个参数"的决定）**：detekt `LongParameterList`
>    的阈值是**触发点**，**参数数 ≥ 8 就红**。项目里 `FormValues.kt:42`、
>    `TotpCodesScreen.kt:672` 两处注释写的「8 个刚好」**是错的**（#123）。
>    ⟹ 副作用：`ItemRepositoryImpl` 已 10 参 ⇒ **KDBX 写入必须抽独立协作者类**，不能加构造参数。
> 3. **★ 新增两个探针，且两个都"坏过"**：
>    `check_state_flattening.py`（#124）**连坏两版都是假绿**（只扫当前文件的 sealed 声明 ⇒
>    真 bug 在另一个文件里；正则贪婪吃到倒数第二段 `…CapabilityState` ⇒ 仍报 0）。
>    `check_orphan_strings.py`（#126）834 条字符串里 **126 条零引用**，
>    **是报告器不是删除器**（按「被搬走的留，被取代的删」，这个数不可能归零）。
> 4. **★ 账目大体检：7 篇里 6 篇计数是错的**，且此前**只有 06 篇有题注行**，其余六篇压根没有
>    ⟹「看数字判断进度」在多数分篇上根本不成立。已全部校正 + 补题注 + 加三重互校脚本。
>
> ⚠️ **当前位置：`main` 已推送（`f160ace`）；`rele` 未动**。
> **下一轮起点 = `R-6 … R-9`：开放 KDBX 单条编辑**（用户已拍板；方案见 `SESSION-2026-09-26.md` §8）。

- **工作区干净；今天的提交已全部推送**（`main` → 见当日末尾；**`rele` 未动**）。
  今日链条：副标题截断 → 开关收口「不猜状态」 → 快解三态开关 + 探针 → 一条文案 + 孤儿串探针 →
  记账（#120–#126 + 七篇计数校正）→ **收工复核抓出自己引入的 `UnusedPrivateProperty`（#127，已修）**。
- ★★ **当前最该记住的五条纪律**（都付过代价）：
  1. **探针的闭环证明 = 「把代码还原成出 bug 的版本，看它是否恰好报那几行」**，
     **不是「自测全绿」** —— 自测只证明"探针能处理我编的简化输入"（#124 连坏两版）；
  2. **当注释不得不解释「点这个按钮是 A 不是 B」时，错的是控件，不是注释** ——
     正解是换控件（按钮/开关二选一），不是给开关写更多解释（#122）；
  3. **"同一原则改一半"比没改更危险** —— 它让「已修」看起来成立（#120：同页 3 占位 vs 7 裸开关）；
  4. **类型改造最容易死在"中途某一层收窄"上，而收窄处编译不报** ——
     `Boolean?` 传进 `Boolean` 参数才报，反过来是隐式的（`FillBehaviorSection` 泄漏点）；
  5. ⭐ **"违例数没涨"不等于"没新增违例"** —— 净数把**抵消**（消 1 增 1）和**真的没动**
     显示成一模一样（#127 实付代价）。判据必须是**逐条集合差**，且**掐掉行号**再比。
- ⚠️ **真机验收未做**：本轮全部改动只过了 detekt / 类型检查 / 变异测试。
  优先验「设置页所有开关的**首帧**不再闪 `true`」与「快速解锁 Partial 态显示为**「继续」按钮**」。
- ⚠️ **沙箱里跑 detekt 的口径**（别被数字吓到）：项目自带的 `config/detekt/detekt.yml` 已经是
  **旧属性名**（`threshold` / `functionThreshold`），可直接喂给沙箱里的 detekt 1.23.8。
  但两边**默认规则集不同**（1.23.8 会多报一堆 `MagicNumber`）⇒
  **本地这套只用来做「相对基线的差值」，不能用来判"是否绿"**；CI 的 `./gradlew detekt`
  （2.0.0-alpha.6）才是真门禁。见 `SESSION-2026-09-26.md` §6。


- ★★ **本轮最大的方法论收获**：`.ai/issues/03` **#108 曾把 `publicKeyAlgorithm` 判为"不需要"** ——
  它只写了"为什么不是"，**没写"在哪个解析层不是"**（浏览器层 vs RP 层），于是后人当成全局结论用。
  ⇒ **写排除结论必须绑定到具体的解析者/层。**
- ⚠️ **GitHub Actions 缓存已顶到上限**（10.5 GB / 上限 10 GB；`gradle-transforms-v2` 25 条吃 6.0 GiB）
  ⇒ 会触发 LRU 淘汰、偶发**冷启动变慢**。治本开关 `gradle-home-cache-cleanup: true` **尚未开启**。
- ★★ **发布机制（2026-09-17 实测确认，务必记住）**：**push 到 `rele` 分支 = 直接触发正式发布**。
  `.github/workflows/release.yml` 的触发条件是 `branches: [rele]` / `tags: [v*]` / 手动：
  读根 `VERSION` → 打 `v*` tag → **混淆构建** `:app:assembleFullRelease` →
  发布**非 prerelease 的 Latest Release**（附 APK + `checksums-sha256.txt`），
  并按 `keep_releases`（默认 10）清理旧 Release。
  ⇒ ⚠️ **不要为了"让分支保持同步"顺手 push `rele`** —— 那等于发版。
  ⇒ 本次收工：main 已**快进**合入 rele ⇒ **v0.3.0 已发布成功**
  （`Vaultix v0.3.0 (Stable)` · tag `v0.3.0` · **Latest** · 固定密钥签名 ⇒ 可覆盖安装）。
  `main` 上的 `ci-debug.yml` 只把 debug APK 发到 `preview`（prerelease），不参与正式发布。
- ★★ **下一步**：**真机验收 §1 ~ §4**（代码已全部完成，验收清单在
  `Docs/progress/settings-rework.md` 各节末尾；装机后必比 SHA-256）。
  ~~按 `settings-rework.md` 的 §2 → §3 → §4 施工~~ **（四项均已完成 2026-09-18，含两处改判）**。
  设计依据仍是 `decisions/设置页信息架构-定稿.md` **§11.10 / §11.11 / §11.12**。
- ⏳ **可选第二轮**：`Docs/progress/docs-slimming.md` —— 文档与记忆瘦身（三项，宜一次做完）。
- ★★ **今天的共同病根**（三种形态，全踩过）——见 `SESSION-2026-09-17.md` §13.1：
  | 形态 | 例子 |
  |---|---|
  | **沉默的分支** | 通行密钥 `Ready` 分支；同步取消分支不清 `isRunning` |
  | **假空态** | 读路径解密失败 → 空列表（用户以为密码丢了） |
  | **假未登录** | 长寿命状态存在短寿命页面里 ⇒ "返回就没" |
  ⇒ 三条约定：**要么改状态要么留日志** · **「空」有三态** · **状态的生命周期不能短于页面的**。
- ✅ **已真机验证修复**：通行密钥（一次指纹直达）· Bitwarden 空列表（改主密码解锁恢复）·
  OneDrive 登录态（"**清掉后台也没丢**"，§11.9）。被排除的假设也记在 §11.9，别重走。
- ✅ **设置页四项全部落地**（2026-09-18）—— ~~① 解锁方式合并精简~~ ~~② 库页改「行 + ⋮」~~
  ~~③ SSH「生成密钥对」~~ ~~④ 添加页表单~~ **全部已完成，均待真机验收**。
  规格 / 落点 / 纪律 / 验收清单 / **两处改判的证据**全在
  [`Docs/progress/settings-rework.md`](../Docs/progress/settings-rework.md)（自包含；**本文件不复述**）。
  ⚠️ ③ 的「私钥导出格式」已定：**OpenSSH 新格式**（不是 PKCS#8，理由见该文档 §3 的实测表）。
- ⚠️ **未结**：B 决策文档（模块边界）· 跨格式中转站/灾备三层的成文（`SESSION §13.4`）·
  `decodeTrashRows` 同款"静默跳过" · R1–R3 三条真机验收（WebDAV 改条目→KeePassXC 能开 / OneDrive 写回 / 断网不损坏）。
- ⚠️ **诊断手法（今天验证有效）**：`run-as` + **`exec-out`** 拉 `databases/vaultix.db`
  （**必须含 `-wal`/`-shm`**）→ 本地 sqlite3；MSAL 账户在
  `shared_prefs/com.microsoft.identity.client.account_credential_cache.xml`。
  ⚠️ Room 列名是**驼峰** · `adb pull` 要 **Windows 路径** · 大日志用 `exec-out tail -c 2500000` ·
  **python 是 Windows 版、不认 `/d/...`** · 锁屏(`isKeyguardShowing=true`)+Dozing 时 `install` 会被拒。
- ⚠️ **门禁必须含 `test`**（`SESSION §6.1`）· 装机后**必须比 SHA-256 核证**（install 输出可能是空白的假成功）。

## 目录结构（索引 + 分篇，**按需只开一篇**）

| 路径 | 是什么 | 规模 |
|---|---|---|
| `README.md` | 本文件：接力入口 | — |
| `MEMORY.md` | 项目长期笔记（定位 / 架构 / 里程碑 / 技术栈硬约束 / 协议与来源 / 发布签名） | 44KB |
| `MEMORY.md §8` | **索引**：写代码前的长期约定 → 正文在 ↓ | — |
| `conventions/` | 约定正文 8 篇：`8.1-自动填充` `8.2-锁与解锁` `8.3-M2KDBX` `8.4-UI·观感` `8.5-通行密钥` `8.6-工程质量` `8.7-环境` `8.8-M3Expressive-采纳范围与顺序` | 各 2~4KB |
| `ISSUES.md` | **索引**：坑的「编号 → 分篇」总表（最新 **#126**）。⚠️ 「条数」列**校正过两次**（2026-09-21 / 09-26），每次都发现多篇漂移 —— **只信它、不信正文会误判进度**（见 #126 附近的记账注） | 13KB |
| `issues/` | 坑的正文 7 篇：`01-构建与环境` … `07-数据与同步` | 各 4~43KB |
| `decisions/` | **逻辑定稿**（用户拍板的方向，非根因、非实现）：`快速解锁房子化-两级钥匙层级-定稿.md`（**快速解锁现行真源**，2026-09-28 起修订前两篇）、`库选择与快速解锁-逻辑定稿.md`、`快速解锁能力级重构-定稿.md`、`设置页信息架构-定稿.md`、`通行密钥UV豁免-定稿.md` | — |
| `SESSION-YYYY-MM-DD.md` | 逐轮工作日志（append-only） | — |
| `tools/` | **本地自检脚本**（不需 Android SDK，`python3` 直接跑）。覆盖「编译器才能发现、detekt 查不到」的缝：<br>`check_compile_smells.py` = 图标导入 / 顶层常量顺序 / `R.string` 悬空引用 / **重复声明**（第四个检查，见 #103；带 `--detekt-probe` 两级探针）；<br>`check_signature_types.py` = 签名里的类型名是否存在；<br>`check_import_packages.py` = `import` 的包路径对不对（#101 / #104）；<br>`check_experimental_optin.py` = 实验性 API 有没有 `@OptIn`（#101.2，带 `--selftest`）；<br>`check_orphan_strings.py` = `strings.xml` 里**零引用**的字符串（#126；⚠️ 是**报告器不是删除器** —— 「被搬走的留，被取代的删」需人判读；`--gate` 用基线做**只减不增**约束）；<br>`check_state_flattening.py` = **多态状态（sealed ≥3 分支）有没有被压成二值开关**（#124；`Switch(checked = x is S.On)` 会把 `Partial` 与 `Off` 合并 —— `is On` 是合法 Kotlin，类型检查与 detekt **双双查不出**）；<br>`fetch_release_asset.sh` = **分块断点续传下载 release 资产**（沙箱整文件下载不可靠，见 #105；自带 `zipfile` 完整性校验） |
| `tools/tests/` | **门禁自己的自测**（改探针前必跑）：<br>`selftest_duplicate_declarations.py` = 7 个正反用例；<br>`selftest_import_packages.py` = **端到端**（真把 import 改错、跑脚本、看退出码、还原）；<br>`selftest_cross_file_private.py` = **端到端**（真把 `internal` 改回 `private`、验证报出两个调用方、还原）；<br>`selftest_orphan_strings.py` = 7 个用例（含**前缀撞名** `a_head`/`a_header` 的经典误判，以及"零引用被误删只在运行时才炸"的各种引用形态）；<br>`selftest_state_flattening.py` = 9 个正反用例。⚠️ 含**全限定名**形态（`Outer.Cap.On`）—— 少了它，探针会在真实代码上静默失效（见该文件开头） |

> ⚠️ **各道脚本的扫描范围都是 `app/ core/ data/ domain/`**（排除 `reference/` 对照源码与 `build/`）。
> 2026-09-16 之前只扫 `app/src`，**同一个盲区造成两次 CI 红**
> （domain 的重复声明、data 的错 import 都扫不到）。**改门禁时先确认它扫的是不是全部 —— 看报的数字。** — |

> **为什么这么拆**：原先 `.ai/ISSUES.md` 139KB、`.ai/MEMORY.md` 43KB ——
> 全读会把真正需要的上下文挤掉，定位只能靠 grep。现在入口稳定、**正文按需读一篇**。
> 仓库里 115 处「见 `.ai/ISSUES.md` #NN」的引用依然有效：先查索引表，再跳分篇。

## 接力流程

1. 读 `MEMORY.md` —— 拿到项目定位、架构、硬约束与**当前状态（§9）**
2. **要动「快速解锁」→ 先读 `decisions/快速解锁房子化-两级钥匙层级-定稿.md`**
   （2026-09-28 起的现行真源，**别重新论证**；施工进度 = `Docs/progress/quick-unlock-house-rework.md`，
   **批次 1-4 已收工（含批次 3.5 真机反馈修复），下一会话从
   `Docs/progress/house-rework-batch4-handoff.md` 进；失效矩阵决策见定稿 §6.1**）
   「库选择 / 默认库」→ 仍读 `decisions/库选择与快速解锁-逻辑定稿.md`（其快速解锁章节已被房子化定稿修订）
2b. **要动「设置页」→ 先读 `decisions/设置页信息架构-定稿.md`**
   （7 组 → 5 组的目标结构 + 全量文案改动表，**别重新设计分组**；§6 是开工步骤）
2c. **要动「Material 3 / 观感 / 动效」→ 先读 `conventions/8.8-M3Expressive-采纳范围与顺序.md`**
   （哪些该借、哪些明令不借、按什么顺序做；含 2026 的设计口径与本项目实测）
2d. **要动「KDBX 网盘同步（OneDrive / WebDAV）」→ 先读
   [`Docs/progress/cloud-sync-plan.md`](../Docs/progress/cloud-sync-plan.md)**
   （★ 前置是 KDBX 阶段 B 写回，勿跳过；冲突策略有三个方案待拍板，**别自行决定**）
2e. **要动「性能」→ 读 [`Docs/progress/perf-plan.md`](../Docs/progress/perf-plan.md)**
2f. **要做「原生化 / Rust / Go 重写」→ 先读
   [`Docs/progress/native-rewrite-eval.md`](../Docs/progress/native-rewrite-eval.md)**
   （已给结论：**现在别动**；唯一量化热点是 KDF；要引就引 Rust 不要 Go；**先量真机**）

3. **按本次要动的模块**，只开 `conventions/` 里对应的一篇（例：动填充 → `8.1-自动填充`）
4. **按本次要碰的模块**，只开 `issues/` 里对应的一篇（例：动填充 → `02-自动填充`）
5. 读 `Docs/progress/next-steps.md`（**待办唯一真源**，最新在顶部）与 `current-status.md`
6. 完成后**更新上述文件**：新坑 → 写进对应 `issues/*.md` 并在 `ISSUES.md` 索引表补一行；
   新约定 → 写进对应 `conventions/*.md`。**别把正文堆回索引。**

## 两类记录的分工（2026-09-13 明确，**别混**）

| | `.ai/SESSION-YYYY-MM-DD.md`（本目录，入库） | `.workbuddy/memory/YYYY-MM-DD.md`（本地，未入库） |
|---|---|---|
| 定位 | **面向接力者的纪要** | **工作流水**（详细过程） |
| 写什么 | 这轮改了什么 · 为什么 · 怎么验证的 · 结论 | 命令、中间尝试、失败路径、逐条取证过程 |
| 长度 | 精简 | 可以很长 |
| 谁看 | 任何接手的人（含新克隆仓库的人） | 只有本机 AI |

**收工规则（单向，从 2026-09-13 起）**：
1. 把当天本地流水里**对接力有用的部分**整理成一段，**追加**进 `.ai/SESSION-YYYY-MM-DD.md`；
2. 然后把**本地当天的日志删掉**（它只是过程草稿，次日不再保留）。

> ⚠️ **历史未回溯合并**：本地流水现存 **2026-09-08 / 09-09 / 09-10 / 09-11 / 09-14 /
> 09-15 / 09-16 / 09-17** 八天（约 320KB），与 `.ai/SESSION-<同日>.md` **内容并不相同、
> 各有一方独有**（不是副本，是两套独立写下的流水）⇒ **不能盲删**。
> **查结论以 `.ai/SESSION-*` 为准，查过程细节参考本地流水。**
> ⇒ 清理步骤见 **[`Docs/progress/docs-slimming.md`](../Docs/progress/docs-slimming.md) §2.2**：
> 先逐份比对、把独有内容**追加**进 `.ai/SESSION-*`，确认无遗漏后再删；**一次只处理一天**。

## ⚠️ 读记忆的正确姿势

**被推翻的旧结论不会删除，而是就地标注 —— 看到 `~~删除线~~` / `🔴 已被推翻` /
`~~xx~~` 标记时，务必读完整标注再决定要不要照做。**

真实案例：`issues/` 里 #35 曾判定「通行密钥浏览器流程应回传**空占位符**」，
该结论于 2026-09-12 被 **#43** 推翻（回传空占位符会让 GitHub 注册直接失败）。
**只读 #35 不读 #43，就会重新引入同一个 bug。**

两条纪律：

| 纪律 | 说明 |
|---|---|
| **旧结论就地加勘误指针，不删原文** | 保留错误推演过程有价值（能看出当时怎么想歪的），但**必须在标题处**给出推翻标记与正确去向 |
| **推翻类记录必须写「错在哪」** | 否则接力者只知结论变了、不知**为什么**变，容易又绕回去 |

## 另有一条工作纪律（2026-09-13 补）

**遇「用户看得到、我复现不了」的问题，先取实证再动代码**，且**先对齐复现路径**
（哪个手势 / 哪个入口 / 哪台设备）。
本轮为此付出代价：「手势返回画面缩小」改了三次转场都没中，因为一直在用**按键返回**复现
**手势**返回的问题；「填充反复解锁」白改一轮，因为只抓了自己的日志 tag，
看不到系统框架侧说「认证结果被丢弃」。详见 `issues/06-界面与交互.md` #81 与
`issues/02-自动填充.md` #82。
