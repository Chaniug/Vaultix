# 04-锁与解锁 · 第 5 篇（#215 ~ #222）

> 主篇索引：[`04-锁与解锁.md`](./04-锁与解锁.md)（含计数规则与重号警告）
> 本篇 2 章 · 12.2KB · 覆盖 #215 ~ #222

## 215. ✅ 「进 App 要验两次指纹」——认证是全局的，门禁标记却是按库的（2026-10-31，#214 的直接后续）

### 现象与用户原话

> 「进入 app 时候验证指纹怎么需要两次，两个密码库？两个密码库不应该是同一个指纹吗，
> 指纹检验一次就够了啊」

用户有两个密码库。熄屏后重新进 App：验证一次指纹 → 进第一个库 → **切到第二个库又弹一次指纹**。
用户的判断完全正确，这不是"设计如此"，是 bug。

### 真因：认证粒度 ≠ 门禁粒度

#214 加的两层锁，第一层（密钥）是**全局**的（房子化后只有一把门锁 `HouseKeyStore`），
第二层（界面门禁）的标记却是**按库**存的（`viewLockedIdsState: Set<String>`）。
三处一串联就必然出这个症状：

| 环节 | 行为 | 后果 |
|---|---|---|
| `AutoViewGate.gateUnlockedVaults()` | 熄屏时给**每个**已解锁的「从不」库各打一个 `automatic = true` 标记 | 两个库 ⇒ 两个标记 |
| `RootNavViewModel` | 判据是「**任一**库带查看锁 ⇒ 收回解锁页」 | 只要有**一个**标记剩着，整个 UI 就停在解锁页 |
| `UnlockViewModel.completeLocalUnlock(forViewLock = true)` | 只调 `clearViewLock(vaultId)` | **只清了当前库** ⇒ 另一个标记还在 ⇒ 再验一次 |

所以"验几次"= 用户有几个库。库越多越离谱，这不是能用"多点一次"糊过去的量级问题。

> **为什么 Bitwarden 没有这个 bug**：它的锁定态是**账号级**的（一个账号一次认证），
> 「认证粒度」与「门禁粒度」天然一致。#214 把门禁细化到库、却没同步细化认证 —— 错位就出现了。

### 修法：把认证粒度对齐到全局（不是把门禁粗化）

新增 `VaultSessionManager.clearAutoViewLocks()`，**只从 `viewLockedIds` 里减去 `autoViewLockedIds` 子集**：

```kotlin
suspend fun clearAutoViewLocks() {
    mutex.withLock {
        viewLockedIdsState.value = viewLockedIdsState.value - autoViewLockedIdsState.value
        autoViewLockedIdsState.value = emptySet()
    }
}
```

UI 侧只改一行：`clearViewLock(vaultId)` → `clearAutoViewLocks()`。
`VaultSessionRepository` 加同名接口成员（零逻辑直通实现）。

**"一次认证够全部库"的理由**：验的是"你是不是本人"，这件事与"哪个库"无关。
按库的标记只是 UI 的**存储方式**，不该被升格成"每个库各验一次"的**语义**。

### 刻意没选的两个修法（都会被写回来，所以在这里钉死）

| 候选 | 为什么不选 |
|---|---|
| `clearAllViewLocks()` | 会把手动查看锁一起抹掉 —— 用户锁了 A，验个指纹 A 也开了，**安全语义倒退** |
| UI 里 `for (id in ids) clearViewLock(id)` | 绕了一圈还是"逐库清"，且**漏掉之后才出现的库**；把粒度错位固化进 UI 层 |

子集相减是**唯一同时满足两个方向**的写法：自动标记跨库全清、手动锁一个不动。

### 测试：两层各钉一遍（缺一层会假绿）

| 层 | 文件 | 钉的是什么 |
|---|---|---|
| 数据层 | `VaultSessionManagerTest.清自动门禁_跨库一次清干净_手动锁原样保留` | 集合代数：`bw` 自动 → 放行；`kdbx` 自动 + 手动 → 仍锁住；`anyManualViewLocked() == true` |
| UI 层 | `ViewUnlockScopeTest`（新，2 条） | **UI 到底调了哪个方法**：`clearAutoViewLocks()` 恰好 1 次、`clearViewLock` 0 次、`clearAllViewLocks` 0 次；查看锁分支不碰密钥（`viewLock` 0 次） |

⚠️ 只写数据层用例的话，**把 UI 改回 `clearViewLock(vaultId)` 依然全绿** —— 这个 bug 的现场就在
UI 这一行，回归门禁必须钉在调用点上。

### CI 结论（`a877736` ✅ 一次过）

| 项 | 结果 |
|---|---|
| 编译 | ✅ `build_failed=false`（debug APK 出包并签名） |
| 单测 | ✅ **1422 例，失败 0 例**（含本轮新增 3 例） |
| detekt / 七个 `check_*.py` | ✅ 全过 |

这一轮**一次就绿**，与 #214 连红两次形成对照。差别不在运气：
本轮唯一的新增依赖是 `VaultSessionRepository.clearAutoViewLocks()`，加接口成员时**同一 grep 就把实现类和所有实现点收齐了**（`grep -rn "clearViewLock" ` 一次列出接口、Impl、调用点、测试），没有出现 #214 那种"我以为它在那个类里"。
⇒ 判据：**加接口成员时先 `grep` 现有同类成员，把所有实现点一次看全**；
这个动作 30 秒，能省掉一整轮 CI。

### 判据

1. **认证的粒度必须与门禁的粒度对齐**。要么门禁全局（#214 之前），要么认证全局（本轮）。
   一个全局、一个按库 ⇒ 立刻退化成"每个库各验一次"。
2. **"存储方式"不许升格成"语义"**。按库存标记是为了知道"锁哪几个库"，
   不是为了让用户按库出认证。
3. **收窄清除范围的功能，必须成对思考"扩大清除范围"的风险**。`clearAllViewLocks()` 顺手可取，
   但它会连带手动锁 —— 所以正确解是**新增一个更精确的方法**，而不是放宽已有的那个。
4. **子集标志的第二次回报**。#214 引入 `autoViewLockedIdsState` 时只是为了让自动填充
   能"看穿自动门禁、仍尊重手动锁"；本轮它又正好是"只清自动、不动手动"的现成工具。
   ⇒ 把一个"看起来多余"的区分做出来，往往在下一次改动里就还回来。

### 发版：v0.10.4（2026-10-31）

`#214` + `#215` 一起进 **v0.10.4**。流程沿用历史惯例：`main` bump `VERSION` → `rele` 快进 → push `rele`。

| 项 | 结果 |
|---|---|
| 触发 | push `rele`（`ddcb113`）→ `release.yml`「Build & Publish Stable Release」`124521010885` |
| 产物 | `app-full-release.apk` **7.24 MB** + `checksums-sha256.txt` |
| Release | `v0.10.4`，`draft=false` / `prerelease=false` / `make_latest=true` |
| SHA-256 | `55c768d3…3952f175` |

⚠️ **没走 tag 触发**：`release.yml` 里「Remove existing release/tag for this version」那一步
在 `tag` 事件下会先删掉刚推上去的 tag（幂等重跑用的逻辑，但对 tag 事件是自伤）。走 `rele` 分支没有这个风险。

### 顺手修的流程问题：Release 说明对用户不友好

自动生成的 changelog 是**提交列表**，里面有「修 CI 红」「补充文档」这类内部账。
v0.10.4 第一次发布就是这样，用户点开 Release 看到的第一条是
「AutoViewGate 从 VaultTimeoutPreferences 读产品开关（f7ac3d2 CI 红）」—— 看不懂，也没意义。

而**改不掉**：`PATCH /releases/{id}` 需要 token，沙箱里只有 SSH key（`ssh -T` 认得出身份，
但推不了 REST）。⇒ 事后修改这条路是死的。

修法（向前兼容，不碰 v0.10.4 已有的 body）：
- 新增 `Docs/release-notes/v<版本>.md`：**人工撰写**的更新内容，正文直接是内容
  （不写标题、不写元说明 —— 多一层剥离逻辑只会多一处出错的地方）；
- `release.yml` 的「Generate changelog」优先读它，**文件不存在时回退提交列表**
  （否则某次忘了写文件就等于发布失败，这条路不能断）。

判据：**自动生成的对外文案要留人工覆盖口，且覆盖必须可回退**。
纯自动 = 内部噪音外泄；纯人工 = 忘写就发不出去。

---

## 222. 🔴 新增库「不会自动关联快速解锁」—— 三条根因叠加（2026-11-01）

### 现象与用户原话

> 「我添加一个新的 bitwarden 密码库，会发现它无法自动关联上快速解锁，
> 需要我先关掉快速解锁，然后再重新关联上所有的。这样的操作很麻烦」

关键线索就在**这个 workaround 本身**：关掉再打开百分百能修好。
说明写入路径（启用时）能算出所有库的钥匙，**新增库这条路径没有触发同一次计算**。

### 真因一：新增库从不建「房间信封」

房子化之后，「库在快速解锁范围内」的**真源**是**每库一份的房间信封**
（`HouseKeyStore.sealRoom`），`QUICK_UNLOCK_SCOPE` 只是 UI 的响应式镜像。

而 `addBitwardenVault` / `addBitwardenVaultWithTwoFactor` 的收尾只有
`doUnlock`（登录）+ `registerVaultRow`（写 `vaults` 行）—— **一次都没调
`sealRoomsForVaults`**。新库没有信封，自然不在范围内。

「关掉再打开」为什么灵：`startEnrollment` 里那句
`val targets = snapshot.rows.map { it.vaultId }` 拿的是**当前所有库**，
一次全量重算把新库补了进去。不是开关有魔法，是它重新收集了一次名单。

### 真因二：横幅判定只问「第一个已解锁的库」

```kotlin
if (dismissed) null else vaults.firstOrNull { it.id in unlocked }
```

它挑的是**第一个已解锁的库**，再只问这一个 `fingerprintQuickUnlockAvailable`。
若那个库早已启用（`available == true`）⇒ 返回 `null` ⇒ 排在后面的**新库永远查不到**。
用户看到的就是"没有提示"。

⚠️ 这是「聚合判据取错对象」的典型：要回答"是否**存在**未启用的库"，
却只抽查了**一个**元素。抽样条件（已解锁）与被判定的属性（是否启用）不是同一件事。

### 真因三：`QUICK_UNLOCK_PROMPT_DISMISSED` 是全局永久静音

一次「以后再说」⇒ 未来**所有**新增库都不再提示。没有过期、没有按库维度。

### 修法（三处，一次收口）

| 优先级 | 改什么 | 落点 |
|---|---|---|
| P0 | 新库自动建房间信封 | `LocalUnlockEnrollment.autoEnrollNewVault`，由 `registerVaultRow` 调用 |
| P1 | 候选改为「任一未启用的已解锁库」 | `VaultListViewModel.quickUnlockSuggest` |
| P2 | 新库重置 dismissed | `registerVaultRow` 里 `setQuickUnlockPromptDismissed(false)` |

**P0 为什么能全自动**：调用点是「刚 add 成功」—— 该库会话密钥已在内存
（`sessions.keyOf` 非空），房间信封包的正是它；房钥匙若也在内存
（`houseKeyStore.isUnlocked`），`sealRoom` 是纯软件 AES-GCM，**无任何系统认证**。
⇒ 不问密码、不按指纹。

**P0 的天花板（必须写清）**：冷启动后还没解锁任何库就新增库时，房钥匙不在内存
（`isUnlocked == false`），物理上封不了。此时**静默跳过**，由 P1 修好后的横幅兜底
（用户点一下、按一次指纹）。不在 data 层弹认证 —— 那是 UI 的职责。

**P0 的三道守卫**（各自对应一种"不该动"的真实状态）：
1. 房钥匙不在内存 ⇒ 没钥匙可封；
2. 一把门锁都没装 ⇒ 快速解锁从未启用，不能借"新增库"擅自开启；
3. 范围为**空集** ⇒ 空集在该键里有确定含义（"一个库都不要"），
   用户手动取消全部勾选后不该被悄悄改回来。

**为什么挂在 `registerVaultRow`**：`VaultRepositoryImpl` 的函数数已卡在
detekt `TooManyFunctions`（≤40）上限，加不了私有函数；而它本来就是两条添加路径
**唯一**的共同收尾，天然是「新库已就位」这个时刻的落点（后加第 N 条路径也不会漏）。

**P1 为什么不能写成一次性读取**：`flatMapLatest` 里若用 `.first()` 逐个问，
就丢掉了响应性 —— 库启用后横幅不会自动消失。必须继续用 Flow 组合
（`combine(candidates.map { ... })`），测试里用 `MutableStateFlow` 钉住这一条。

### 测试

- `LocalUnlockEnrollmentAutoEnrollTest`（7 例）：三道守卫各一、成功路径
  （含**范围并集**与**零认证**断言）、无密钥、封装抛异常、KDBX 不猜。
- `VaultListViewModelQuickUnlockSuggestTest`（5 例）：**前一个库已启用仍发现新库**
  （旧实现必红）、全部启用才不提示、未解锁不参与、dismissed 生效、启用后自动消失。

### 判据

1. **"关掉再打开就好了"要先怀疑"新对象没被纳入那次全量计算"**，而不是开关的读写逻辑
   —— workaround 之所以稳定有效，通常因为它重跑了正确的那个集合。
2. **问"是否存在满足条件的元素"时，不许只抽查第一个** ——
   抽样条件与被判定属性不是同一件事时，必然漏。
3. **"以后再说"是对象级的意愿，不是永久全局静音** —— 新对象出现时应重新征询一次。
4. **自动化的天花板要写进 KDoc**：做不了的那一半，必须有一条能兜住的引导路径。

## 241. 🔴 新加的 KDBX 库永远不进快速解锁 —— #222 只修了 Bitwarden 这一条路径（2026-11-03 晚，用户报告）

- **现象**：新添加库后（尤其 OneDrive 上的 KDBX），
  ① 库不纳入快速解锁，**每次都要输 KDBX 主密码**；
  ② 在设置里配快速解锁、输完 KDBX 密码点「继续」，**好像点不动**。
- **根因一（主因）**：`autoEnrollNewVault`（#222 的成果）全仓库**只有一个调用点** ——
  `VaultRepositoryImpl.registerVaultRow`，而那是 **Bitwarden 专用收尾**
  （写的是 `VaultKind.BITWARDEN`）。KDBX 的两条添加路径都没有挂：

  | 路径 | 是否自动纳入 |
  |---|---|
  | Bitwarden（`registerVaultRow`） | ✅ |
  | KDBX 打开文件 / 网盘 KDBX（`addKdbxVault`） | ❌ |
  | KDBX 新建库（`KdbxCreateRepositoryImpl`） | ❌ |

  而解锁侧 `UnlockViewModel.candidateVaultIds` 的判据是
  `vaults.filter { it.id != target && !it.unlocked && it.id in scope }` ——
  **只看 `quickUnlockScope`**。新库不在 scope 里 ⇒ 快速解锁结构性地碰不到它。
  ⇒ #222 修的是"Bitwarden 新库不自动关联"，**同类问题在 KDBX 上一直躺着**。

- **根因二（结构性的，比少调一行更值得记）**：旧签名是
  `autoEnrollNewVault(vaultId: String)`，内部写死 `prepareForVaults(listOf(id)) { _ -> null }`。
  而 `prepareOne` 对 KDBX 走 `prepareKdbx(...)`，**第一件事就是拿主密码去 `Kdbx.verify`** ——
  密码恒为 `null` ⇒ `Skipped` ⇒ 永远封不成。
  ⇒ **这个签名从设计上就排除了所有 KDBX 库**，不是"漏调用"，是"调了也没用"。
  更糟的是当时有一条单测把这个缺陷**写成了预期行为**（`不是Bitwarden库_不猜也不封`），
  等于给 bug 上了一把锁。

- **修法**：
  1. 签名加一个密码源：
     `autoEnrollNewVault(vaultId, masterPasswordOf: suspend (String) -> String? = { null })`；
     Bitwarden 走会话密钥、不需要它（默认值保持不变，旧调用点零改动）。
  2. 两条 KDBX 路径在**添加成功后**调用，并把**本次添加用的那个主密码**交进去
     （它就在函数参数里，不需要再问用户）。
  3. `submitPassword` 的两处裸 `?: return` 改成带错误文案的返回
     （`quick_unlock_session_expired` / `quick_unlock_no_pending_vault`）。

- **★ 用户决策（2026-11-03）**：选择**静默纳入**，不弹确认框。原话：
  「因为我认为指纹解锁和PIN码是 vaultix 的最高优先级的」。
  即：新 KDBX 库添加成功后，若房钥匙在内存且门锁已装，**直接用刚输入的主密码封房间信封**，
  不问用户。⚠️ 这个决定的实质是"允许在用户未逐库确认的情况下把主密码加密存盘"，
  与 Bitwarden 的既有行为一致；三道守卫（房钥匙不在内存 / 一把门锁都没装 / 范围为空集）
  仍然生效，**快速解锁从没启用过的人不会被悄悄开启**。

- **判据**：
  1. **"某个能力加到了 A 类对象上，B 类对象是否也有"必须逐个路径数**。
     #222 当时只盯着 Bitwarden，因为报障的人用的是 Bitwarden ——
     **报障渠道决定了视野**，这是最容易留下的盲区。
  2. **形参里少一个参数，等于在类型层面排除一整类对象**。`{ _ -> null }` 这种
     "占位默认值"看着无害，实际是"这个分支永远走不通"的声明。
     写它的时候要问：**谁会走到这个默认值上？**
  3. **单测把这个缺陷写成断言时，bug 就被固化了**。`不是Bitwarden库_不猜也不封`
     的字面意思是"KDBX 不该被封" —— 而真实需求恰恰相反。
     **测试名里的"不"字要能对应到一条产品决策，否则它锁住的是历史而非契约。**

- **验证**：`:data:repository` 265 例、`:app` 565 例，0 失败；detekt 全模块通过；
  9 个脚本门禁 + 文档门禁全绿。
- **真机验收**：添加一个 OneDrive KDBX → 回库列表看它是否显示「已启用快速解锁」
  → 杀进程重开 → 指纹应能直接打开它（不再要主密码）。
  ⚠️ 前提：添加时**至少一把门锁已装**、且范围非空集 —— 否则按设计会静默跳过。

---

## 243. 🔴 冷启动解锁后仍然「该库未纳入快速解锁」—— 纳入发生在**认证之前**，顺序天生是反的（2026-11-04，真机第三轮）

**现象**（用户截图 + 原话「还是存在问题」）：解锁页正常显示 `valk.kdbx`、指纹图标、
「轻触指纹快速解锁」；按下去却弹「出错了：**该库未纳入快速解锁**」。

**根因：三段时序，而旧实现只在第 ① 段之后尝试纳入**

```
① 主密码解开 valk.kdbx → autoEnrollNewVault
                         └─ houseKeyStore.isUnlocked == false ⇒ 静默 false
② 用户按指纹 → 门锁开了、房钥匙进内存
                         └─ 但**没有任何人再来封这个库的房间**
③ 解锁页：valk 不在 quickUnlockScope
   ⇒ fingerprintQuickUnlockAvailable(valk) == false
   ⇒ 自动弹认证不触发 / 入口不可用
   ⇒ 用户只能继续用主密码，或按到「未纳入」那句话
```

要害在于：**用户按下的那次认证，恰恰就是"房钥匙该进内存"的那一次**，
而纳入却要求"房钥匙已经在内存"。两件事在时序上互斥 —— 谁先谁后都不可能同时满足。

**修法（四处，缺一条都不闭环）**

1. **入口可见性改判据**：`quickUnlockEntryAvailable()` = 装过任意一把门锁
   （不再要求"该库在范围内"）。理由：房间信封可以在**按下指纹那一刻**补建；
   拿它当入口判据会让"还没封房间"的库**永远没有入口**，于是永远补不上（自锁）。
   旧判据 `fingerprintQuickUnlockAvailable(id)`（= 在范围内）保留，语义已收窄为
   「能不能**直接**开」，库列表横幅仍用它。
2. **补建接缝**：`LocalUnlockFanout.RoomEnroller`。门锁刚开、首库 `Unavailable`
   ⇒ 补建 ⇒ **原地重试一次**（信封里就是刚才验过的凭据，必然能开）。
   不重试的话用户会看到"已纳入却仍然开不了"，下一次还得输密码。
3. **主密码路径**：`unlockKdbxInternal` 成功后调 `enrollJustAuthenticated`（**带**房钥匙
   守卫）。房钥匙不在就如实跳过 —— 否则 KDBX 备料要白跑一次 `Kdbx.verify`
   （真机 64 MiB / 46 轮）再在 `sealRoom` 抛异常。
4. **幂等短路**：`autoEnrollUnlocked` 见 `hasRoom` 直接返回 true。这条**不是优化**：
   方法现在会在每次主密码解锁后被调用，没有短路就是每输一次密码多烧一遍 Argon2。

**顺带修掉的第二道死锁**：`自动纳入` 的「范围为空集」守卫把**未确认过**（语义 = 默认
全部库都在范围里）也当成"用户明确一个都不要"⇒ **只有单个 KDBX 库的用户永远纳不进来**
（他没有别的库可纳入，范围就永远是空集）。改为同时判 `isQuickUnlockScopeConfirmed()`。

> **判据**：① **"先认证再授权"的流程里，任何依赖"已授权"的副作用都必须挂在认证
> **之后**的那个钩子上** —— 挂在之前，条件恒假；② **入口本身不能以"能力已就绪"为前提**，
> 否则用户永远无法第一次让它就绪（鸡生蛋）；正确形状是"入口常开，能力按需补建"；
> ③ **"空集/空值"必须连同"确认标记"一起读**，一个值两种含义时只取其中一个含义，
> 必然有一类用户被永久挡住。

- **验证**：`:data:repository` 276 例、`:app` 569 例、`:data:kdbx` 247 例，0 失败；
  detekt 全模块通过；9 个脚本门禁 + 文档门禁全绿。
- **真机验收**：冷启动 → 输 `valk.kdbx` 主密码 → **不再需要按第二次**；直接退出再进，
  指纹应能一次打开它。若第一次进解锁页就按指纹，也应在同一次按压内完成
  「补建 + 重试」，而不是弹「未纳入」。

---

## 245. 🟡 锁屏回来「正在派生密钥并解锁…（约需 1–3 秒）」转个不停，指纹弹不出来（2026-11-04，真机）

**现象**（用户原话）：「手机锁屏后，解锁屏幕后，bitwarden 库上锁，说派生密钥，弹不出指纹解锁，
很慢，在那里转。」截图：查看锁页面，「使用生物识别 / 设备 PIN 解锁」按钮置灰，
下方转圈 + 那行「正在派生密钥并解锁…（约需 1–3 秒）」。

**三件事叠在一起，每一件单独都不致命**

| # | 问题 | 后果 |
|---|---|---|
| ① | 查看锁页面复用了「**正在派生密钥并解锁**」这句文案 | 这一页**根本不做派生**（密钥已在内存，只等一次生物识别）⇒ 用户以为卡在密码学运算上，越等越怀疑 |
| ② | 自动弹认证在**首帧就发起**（`lifecycleOwner.withResumed` 之后立刻弹） | cipher 要现备（一次 Keystore 往返）、宿主刚 RESUMED ⇒ 弹窗比用户预期晚，这段等待全落在"转圈"上 |
| ③ | 自动门禁的标记**不会因为用户取消而消失** | 用户取消后仍被钉在"只有生物识别按钮"的一页上，只能反复点 |

**修法**

1. `UiState.viewLockAuto`：区分「系统自动打的门禁」与「用户手动按的锁」。
   `viewLocked` 与 `viewLockAuto` **在同一次状态更新里**写入（分开写会出现一帧
   "锁着但不知来源"的中间态，而自动弹认证正好读它）。
2. 自动弹认证的新判据：`(viewLocked && !viewLockAuto) || localUnlockAvailable`。
   —— **系统自动门禁不再抢在用户前面弹**：那一页本来就只有一个主按钮，
   "回来确认一次身份"由用户主动按下即可，抢弹只剩代价（见 ②）。
3. 自动门禁被**用户取消**（非系统侧终止）⇒ 清 `clearAutoViewLocks()` 并放行回库列表。
   手动查看锁、系统侧终止，两者都**不动**（一个是指令，一个不是选择）。
4. 忙碌文案换成 `unlock_view_lock_verifying`（「正在等待身份验证…」），如实描述。

> **判据**：① **文案必须描述真实在做的事** —— 写"派生密钥"而实际在等指纹，
> 是把用户送去错误的方向排查（他会去怀疑 KDF 参数，而问题在时序与文案）；
> ② **"自动"与"手动"是两种意图，不能共用一个标记**：一个该尊重"用户没选择"，
> 一个该尊重"用户已选择"；③ 会唤起系统弹窗的自动动作，在"只有一个按钮"的页面上
> 几乎没有收益 —— **让用户按下那个按钮，比抢在他前面弹更快**。

- **验证**：`:app` 572 例（`ViewUnlockScopeTest` 新增 3 条：自动取消放行 / 手动取消保持 /
  系统侧终止保持），`:data:repository` 276 例，0 失败；detekt 通过。
- **真机验收**：熄屏 → 亮屏解锁 → 进 Vaultix：**不应**自动弹指纹（页面等用户按），
  按下后应在一秒内出现系统指纹框；取消后应回到库列表，而不是停在认证页。

---

## 246. 🟡 KDBX「解锁很慢很慢」，输入密码或指纹之后都要等（2026-11-04，真机）

**现象**（用户原话）：「onedrive 上的 kdbx，解锁速度很慢很慢，我输入了密码，或者指纹解锁，
依然很慢很慢。」

**已知的等待来源（按量级排）**

| 来源 | 量级 | 现状 |
|---|---|---|
| KDBX KDF（Argon2d，用户实测 **64 MiB / 46 轮**） | **百毫秒 ~ 数秒**，每次开库一次 | 无法避免（这是密码库的加密参数）。但**次数**可以省 |
| 远端整库下载（OneDrive） | 视文件大小与网络 | 有 `CachedKdbxFileSource`，但**解锁路径**是否命中缓存未验证 |
| 每次主密码解锁后多跑一遍 KDF（#243 引入的风险） | 一次完整 KDF | 已修：`hasRoom` 短路 |
| **但短路排在 `hasAnyLock()` + `quickUnlockScope().first()` 之后** | 一次 Keystore + 一次 DataStore | ★ 本轮修：把 `hasRoom` 提到**最前**，绝大多数情况下这三笔开销全免 |

> **判据**：「解锁慢」先数**KDF 跑了几次**，而不是先去调参数。这个项目里 KDF 出现在三个
> 地方（主解锁 / 房间信封备料 / 每次改动后的保存），任何一处多做一次，用户感知就是"翻倍"。

### 第二批：网络往返（2026-11-04 续）

KDF 次数压下去之后，剩下的慢点只剩**网络**。静态排查锁定两处白付的开销：

| 位置 | 问题 | 修法 |
|---|---|---|
| `OneDriveGraphClient.stat` | 请求 `/me/drive/root:/path` **不挂 `$select`** ⇒ Graph 返回整个 driveItem（几十个 facet、权限、缩略图…），服务端现拼。而 `stat()` 只读 5 个字段，且 2026-10-05 实测它就是瓶颈（`read=2837ms` 而缓存命中、`open` 只用 334ms） | 新增 `buildStatRelativeUrl`，`%24select=cTag,eTag,size,lastModifiedDateTime,folder` |
| `KdbxCloudSyncCoordinator.withRemoteCache` | **每次解析都新建**装饰器 ⇒ 每个实例各一个 `freshness` StateFlow。UI 订的是"解析那一刻"那个实例的流，别处（上传成功 / 后台校验）更新后**永不再发值** ⇒ 角标一直不变（#233 的 `FreshnessHolder` 只共享了"值"，没共享"流"） | 按 origin 复用装饰器实例（与 `safSources` 同款做法） |

⚠️ **`$select` 的 `$` 必须写成 `%24`**：OkHttp 的 `HttpUrl` 会把 query 参数名里的裸 `$`
**静默丢掉**，实际发出的是 `?cTag,eTag,…`（无参数名的怪查询，Graph 400）。而
`url.contains("select=")` 这种断言恰好还能匹配到它 —— 是典型的"永远绿的假测试"。
⇒ 用例断言的是 `%24select=`，含变异验证记录。

- **验证**：`:data:repository` 277 例（新增 `同一个远端库两次解析来源必须拿到同一个实例`）、
  `:app` OneDrive 48 例（新增 `stat 必须只请求版本令牌那几个字段` / `下载与上传 URL 不得带 select`），0 失败。
- **仍待真机日志（不猜）**：`adb logcat -s VaultixKdbxCache VaultixKdbx VaultixUnlock` 记录
  ① 点下到开库的耗时；② `read=` 与 `open(KDF+解析)=` 各占多少；③ 是否每次都重新下载整库
  （日志里"无缓存（首次解锁 / 已被清理）⇒ 下载"这行出现几次）。
