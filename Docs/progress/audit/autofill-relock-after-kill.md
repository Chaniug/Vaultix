# 诊断报告 · 后台被杀后「填充频繁要求解锁 / 匹配不到条目」

> **状态**：诊断完成，**未动代码**（用户 2026-09-28 明确要求「先要诊断报告」）。
> **一句话结论**：
> 1. **主因是架构差异**——Vaultix 把「已解锁」等同于「密钥在内存」，进程死亡即锁死；
>    Bitwarden 把「锁不锁」当**可持久化的业务状态**。
> 2. **★ 且发现一处真 bug**：`createdForAutofill` 豁免（上游有、本项目也照抄了）
>    **从未生效过**（三缺陷叠加，见 §2.5）。**这处必须修，且不需要任何安全取舍。**
> 3. **★★ 「密集」不是"每次一次认证"，而是"同一秒重发多次"**——
>    实测日志 `outcome=Ready × 22`（§2.6）。且 **CP 路的修复未覆盖普通 autofill 路**。
> 4. **★★ 「匹配不到条目」的真凶：`PendingFillStore` 的 `AutofillId` 失效**（§2.7）——
>    暂存的是**字段 id**，解锁期间浏览器可能已重建页面 ⇒ 回灌静默失败。
> **对齐与否是安全取舍，但这些 bug 不是。**

---

## 1. 现象（用户原话）

- 用 Bitwarden 官方 App：后台被清理后，填充时**没有这么密集的解锁**。
- 用 Vaultix：后台被杀了之后，**即使把密码库设为「从不加锁」**，填充框仍**频繁要求解锁**。
- 有时甚至**匹配不到条目**。
- **2026-09-28 补充**：**已设置指纹解锁**，但解锁频率仍很高；相比 Bitwarden 官方，
  Vaultix 加锁/解锁次数**更频繁和密集**。

> ### ★ 症状拆解（必须先分清，否则会修错地方）
> **"频繁"有两种完全不同的病，本项目两种都存在：**
> | 形态 | 含义 | 真因 |
> |---|---|---|
> | **A. 频率高** | 每次填充都要认证一次（一次一个指纹） | §2 物理事实（解锁态纯内存） |
> | **B. 密集/重复** | **同一次**填充里被要求解锁**好几次** | §2.6（PendingIntent 重发） |
> 用户说的"更密集"更像 **B**；但 **A 也在**（进程被杀后必然走认证）。
> ⇒ **两个都要修，且修法完全不同。**

---

## 2. 因果链（代码实证）

### 2.1 主因：解锁态是**纯内存**的，进程死亡即消失

`data/repository/src/main/java/io/vaultix/data/repository/VaultSessionManager.kt`

```
L22  「进程死亡密钥自然消失」（类注释，作者自述）
L29  private val sessions = mutableMapOf<String, SymmetricCryptoKey>()   ← 纯内存
L82  fun isAnyUnlocked(): Boolean = unlockedIdsState.value.isNotEmpty()
```

→ **没有任何持久化**。「已解锁」= 「密钥此刻在这个进程的内存里」。

### 2.2 「从不加锁」管不了进程被杀

`core/datastore/src/main/java/io/vaultix/datastore/VaultTimeout.kt`

```
L104 data object Never : VaultTimeout()        ← 「从不」= 不因超时自动锁
L98  data object OnAppRestart : VaultTimeout() ← 「重启即锁」
L146 Never → -2 ;  OnAppRestart → -1（Bitwarden 原值）
```

→ `Never` 的语义是「**不因超时自动锁**」。它**不能**：阻止进程被杀、也不能让密钥在
进程重建后存活。用户设了「从不」但仍被要求解锁，**完全符合当前设计**，不是配置没生效。

⚠️ 顺带：`8.2-锁与解锁.md` L30 记有一条**历史上踩过的**迁移陷阱：
旧 `auto_lock_minutes = -1` = 「从不」，新 `VaultTimeout = -1` = `OnAppRestart`「重启即锁」
——**语义正好相反**。现已用 `Never = -2` 区分。**若用户是在该迁移修复前设的「从不」，
其落盘值可能被误读为 `OnAppRestart`** ⇒ 这会额外放大症状。
⇒ **待核实项**：见 §6 的 R1。

### 2.3 填充侧的闸门：读的正是上面那个内存态

`app/src/main/java/io/vaultix/vaultix/autofill/VaultixAutofillService.kt`

```
L198 val unlocked = vaultRepository.observeUnlockedVaultIds().first()
L199 if (unlocked.isEmpty()) {
L200     AutofillLogger.d("locked: no unlocked vault → unlock fallback")
L205     pendingFillStore.stage(parsed)
L206     return AutofillDatasets.buildFallback(... MODE_UNLOCK ...)   ← 弹解锁
L231 }
```

判据本身是**对的**（对齐上游 `activeAccount.isVaultUnlocked`，且已修掉 #66 的
「存在锁定的库」误判）。但它的**输入**来自 §2.1 的内存态
⇒ 后台被杀后必然走进 `isEmpty()` 分支 ⇒ **必弹解锁**。

**这是「频繁解锁」的完整闭环，无需其他解释。**

### 2.4 「匹配不到条目」——**同源，但还有一个独立的放大项**

同源部分：解锁回灌走 `AutofillActivity` + `PendingFillStore`。进程若在解锁过程中
再被杀一次，暂存的字段 id 与回灌链路一起丢 ⇒ 表现为「解锁了但没条目」。

**独立放大项**（多库场景）：
`app/src/main/java/io/vaultix/vaultix/autofill/engine/AutofillCandidateSource.kt`

```
L51 suspend fun singleActiveVault(unlocked: Set<String>): Set<String> {
L52     val active = activeVaultStore.resolve()
L53     if (active != null && active in unlocked) return setOf(active)
L54     return setOfNotNull(unlocked.minOrNull())      ← 退化：字典序最小的已解锁库
L55 }
```

`ActiveVaultStore.pick()` 的第 3 条退化规则同样是 `unlocked.minOrNull()`。

⇒ **多库并存时（Bitwarden 库 + KDBX 库），若活跃库解析到了「不含该站点」的那个库，
候选就会从错误的库取 ⇒ 匹配不到条目。**
注释（`AutofillCandidateSource` L47-49）已记载：历史上遍历全部已解锁库聚合会冒出
重复候选，故收敛为「只取一个」——**代价就是取错库时静默空候选**。

⚠️ 这条只在**多库**时成立。**待核实项**：见 §6 的 R2。

---

## 2.5 ★★ 更深一层的真因：`createdForAutofill` 豁免**从未生效过**

> ⚠️ 本节是 §2 的**重要补充**，也是本报告**最有价值的发现**。
> §2 说的是「进程被杀后解锁态丢失」这一**物理事实**；
> 本节说的是「**本该豁免这个场景的结构性机制，实际是坏的**」——**这是真正的 bug**。

### 设计意图（对齐 Bitwarden）

`app/.../security/VaultLockManagerImpl.kt` L196-204：

```kotlin
VaultTimeout.OnAppRestart -> {
    if (reason is CheckTimeoutReason.AppCreated) {
        if (reason.firstTimeCreation || !reason.createdForAutofill) {
            handleTimeoutAction(vaultId)          // ← 锁
        }
        // 否则（非首次创建 且 为 autofill 拉起）→ 豁免，不锁
    }
}
```

接口注释（`VaultLockManager.kt` L115-120）明确写了设计意图：

> `createdForAutofill` 为真且**非**首次创建 → 视为「为 autofill / 凭据提供商而拉起进程」，
> **豁免** `VaultTimeout.OnAppRestart` 档位的锁定。
> **这条豁免就是「通行密钥流程不该把库锁掉」的结构性保障。**

⇒ 也就是说：**"填充时不该因为进程新建就锁库"这件事，上游有专门机制，本项目也照抄了。**
**但它没能生效。** 下面三条缺陷叠加导致豁免恒不成立。

### 缺陷 A：`isFirstCreation` 恒为 `true`

`app/.../VaultixApplication.kt` L85-88：

```kotlin
lockManager.onAppCreated(
    isFirstCreation = true,        // ← 硬编码，每次进程创建都传 true
    createdForAutofill = createdForAutofill,
)
```

判据是 `firstTimeCreation || !createdForAutofill`（**逻辑或**）：

| `isFirstCreation` | `createdForAutofill` | `true \|\| !true` | 结果 |
|---|---|---|---|
| **true** | **true** | `true \|\| false` = **true** | ❌ **锁**（本该豁免！） |
| true | false | true | 锁 |
| false | true | `false \|\| false` = false | ✅ 豁免 |
| false | false | true | 锁 |

⇒ **`isFirstCreation = true` 使豁免分支永远走不到。**
语义漂移在于：KDoc 说它表示「**冷启动**」，但实现传的是「**进程被创建了**」。
在「为 autofill 拉起进程」这个场景里，进程**确实是新建的**，
但它**不是用户主动的冷启动** —— 这两个概念被混为一谈。

### 缺陷 B：`markCreatedForAutofill()` 的时序物理上不成立

`VaultixApplication.kt` L57-60 注释自述：

> ⚠️ 必须是进程级静态标记：**进程创建时（`onCreate`）这些 Activity 还没起来**，
> 只能靠「先置位、后读」的约定 —— 各 Activity 在 `super.onCreate` **之前**调用
> `markCreatedForAutofill`，随后 `onCreate` 里读它。

**但顺序是反的**：

```
系统为 autofill 拉起进程
  → Application.onCreate()      ← L85 在这里读 createdForAutofill（此时必然为 false）
  → MainActivity/AutofillActivity onCreate()
  → markCreatedForAutofill()    ← 到这里才置位，但「读」已经发生过了
```

`Application.onCreate()` **必然早于**任何 `Activity.onCreate()`。
⇒ **标记永远不可能在「读」的时候为 true。**
「先置位、后读」在这个调用路径上是**不可能实现的约定**。

⚠️ 且两处注释**互相矛盾**：`CredentialProviderActivity.kt` L88 写
「必须在 `super.onCreate` **之后**」，`VaultixApplication.kt` L58 写
「在 `super.onCreate` **之前**」——说明当时对时序的论证本身就没收敛。

### 缺陷 C：`AutofillActivity` 根本没设标记

```
grep -rn "markCreatedForAutofill" app/src/main
  → 仅 CredentialProviderActivity.kt:90（唯一调用点）
  → AutofillActivity.kt 只有 super.onCreate（L172），无 mark 调用
```

⇒ **自动填充这条路径连标记都没置位**（且即便置位，也被缺陷 A/B 废掉）。

### 影响重估（★ 修正 §4 结论）

| 场景 | 症状 | 与 `createdForAutofill` 的关系 |
|---|---|---|
| `Never` 档 + 进程被杀 | 填充弹解锁 | ❌ 与豁免**无关**（`Never` 分支 L194 直接 `return`，**根本不锁**）⇒ **§2 的物理事实仍是主因** |
| `OnAppRestart` 档 + 进程被杀 | 填充弹解锁 | ⚠️ **本该被豁免却不豁免** ⇒ **缺陷 A/B/C 是这里的真因** |

⇒ **两条原因独立共存**：
- 若用户设的是**「从不加锁」** → 走 §2（`Never` 不锁，但**密钥已随进程消失**，仍要解锁）；
- 若用户设的是**「重启即锁」** → 走 §2.5（**豁免机制坏掉**，本该不锁却锁了）。

⚠️ 用户说「设为从不加锁后仍频繁解锁」⇒ **主因是 §2 的物理事实**（`Never` 管不了进程被杀）。
**但 §2.5 是一处实打实的、可独立修复的 bug**，且修好后能让「重启即锁」档的用户
获得上游同款豁免 —— **建议独立修复，不依赖任何安全取舍。**

---

## 2.6 ★★ 形态 B：同一次填充被**重发多次**解锁（"密集"的真身）

### 证据：本项目自己的实测日志

`app/.../autofill/AutofillActivity.kt` L252-270 的 KDoc 记录了 2026-09-17 的真机实证
（**荣耀 BKQ-AN00 · Edge · tag=`VaultixAutofill`**）：

```
17:48:42.027  CP GET locked → authenticationActions      （面板弹出「解锁 Vaultix」）
17:48:45.375  unlockAllAndFinish: 认证成功 first=… rest=1 （★ 库真的解锁了）
17:48:46.7 ~ 17:49:00.9  maybeBiometricUnlock: outcome=Ready  × 22   ← ★ 14 秒内 22 次
```

**`Ready` 被触发 22 次** ⇒ 系统在不停重发解锁动作。作者的结论原文：

> ⚠️ 本 Activity 的解锁路径全是"秒创建秒 finish"，配上 CANCELED 就成了一个
> **~2 次/秒的重发循环** —— 用户看到的正是**「明明解锁过了，还是不停让我解锁」**。

### 机制

系统判断"这次认证动作完成了吗"，看的是 PendingIntent 的 **`resultCode`**：

| 收尾方式 | 系统的判断 | 后果 |
|---|---|---|
| `RESULT_OK` | 动作完成 | 重新取候选 / 正常继续 |
| 其它（默认 `RESULT_CANCELED`） | 动作**没完成** | **重发同一个 PendingIntent** |

`AutofillActivity` 的解锁路径"秒创建秒 finish" ⇒ 若不成 RESULT_OK，就是**高频重发**。

### ⚠️ 关键缺口：修复**只覆盖了 CP 路**，没覆盖普通 autofill 路

`AutofillActivity.kt` L427：

```kotlin
if (credentialFlow) finishCredentialFlowUnlocked() else deliverPendingFill()
```

- `credentialFlow == true`（Credential Provider / 通行密钥）→ 走 `finishCredentialFlowUnlocked()`，
  **有** `RESULT_OK` 保护（2026-09-17 修的）。
- `credentialFlow == false`（**普通自动填充**，即从浏览器/输入法点填充）→ 走 `deliverPendingFill()`。
  看 L560-592 与 `PendingFillStore`：**拿不到有效暂存时就"收工"**，
  **不带任何结果** ⇒ 与 CANCELED 等价 ⇒ **系统重发**。

⇒ **普通 autofill 这条路上，`RESULT_OK` 那层保护不存在。**
用户从浏览器触发填充时走的正是这条路 ⇒ **"密集"在这个场景下未被修复。**

### ★ 精确病灶（2026-09-28 补 tracing 后确认）

`AutofillActivity.kt` L560-605 `deliverPendingFill()`：

```kotlin
val pending = pendingFillStore.takeValid()
if (pending == null) {
    // ★ L574 关键：
    val unlockedNow = if (credentialFlow) isAnyVaultUnlocked() else false   // ← 普通路恒 false
    if (unlockedNow) finishCredentialFlowUnlocked() else finish()           // ← finish() = CANCELED
    return
}
val response = … 
if (response == null) setResult(Activity.RESULT_CANCELED)   // ← 回灌失败也是 CANCELED
else setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_AUTHENTICATION_RESULT, response))
```

**逻辑悖论（这就是"密集"的成因）**：

> 普通 autofill 走到「无有效暂存」这一步，**恰恰说明用户刚完成了外部解锁**
> （去 Vaultix 主界面解锁后 `onResume` 回来 → L221 `awaitingExternalUnlock` → 调本函数）。
> **但 L574 的 `else false` 让普通路把"刚解锁成功"当成"没解锁"** ⇒ 回 `CANCELED`
> ⇒ 系统重发 ⇒ **又一轮**。

**同一函数、同一位置的不一致**：CP 路 L572-573 的注释专门强调「要**核实**解锁真的成了」，
普通路却**连检查都不做**，直接 `false`。

**且 L330 有一句决定性的话**（`finishWithRefreshedEntries` 的 KDoc）：

> ⚠️ **不要**回 `RESULT_CANCELED`：那会让面板判成"认证动作没完成"并**不停重发**
> （2026-09-17 实测 **23 次**）。

⇒ **同一个坑在 CP 路修好了，在普通 autofill 路仍然存在。**

### ⚠️ 修法的不确定点（**已于下方解除，此处保留记录**）

`finishCredentialFlowUnlocked` / `finishWithRefreshedEntries` 是
**`@RequiresApi(34)` 且绑定 Credential Manager**（`PendingIntentHandler.setBeginGetCredentialResponse`）
—— **Autofill 框架用不了这套 API**。

⇒ 初判「B′ 不能直接复用它们」—— **正确，但结论不是"做不了"**：
Autofill 框架有自己的等价通路，见下方「修法已确认」。

⚠️ 另需注意（L583-585 vs L594-595 的差异）：**回灌中途失败（`response == null`）时回
`CANCELED` 是合理的**（确实没成），但此时系统会重发 ⇒ **用户体感仍是"又要解锁"**。
所以 C′ 的「给可见反馈」与 B′ 需**一起考虑**，否则修了 B′ 用户仍会困惑。

### ✅ 修法已确认（Autofill 框架下的正向结果姿势）

**结论：B′ 可直接照 Autofill 框架的标准做法修，不需要 Credential Manager 那套 API。**

实证（`reference/bastion/.../AutofillAuthenticationActivity.kt` L249-258）：

```kotlin
val dataset = datasetBuilder.build()
val replyIntent = Intent().apply {
    putExtra(android.view.autofill.AutofillManager.EXTRA_AUTHENTICATION_RESULT, dataset)
}
setResult(Activity.RESULT_OK, replyIntent)      // ← Autofill 框架的正向结果
finish()
```

且 Bastion 的 `onAuthenticationFailed`（L269-273）/`onAuthenticationCancelled`（L278-282）
**才**回 `CANCELED` —— 即「**真失败/用户取消才取消**」，**不是"没暂存就取消"**。

本项目 `VaultixAutofillService.kt` L593-601 用的**正是同一套**
（`RESULT_OK` + `EXTRA_AUTHENTICATION_RESULT`）⇒ **API 通路是现成的，
B′ 只是要把 L574 那个 `else false` 改成对普通路也做真实检查。**

⚠️ 仍需真机验证两件事：① 普通 autofill 路的 `EXTRA_AUTHENTICATION_RESULT` 应回
`FillResponse`（列候选，见本文件 L607-615 的类型分岔说明）还是 `Dataset`；
② 重发是否真的停止（对齐 CP 路 2026-09-17 的验证方式）。

### 待核实（§6 R5）

`deliverPendingFill()` 失败/成功时**到底回没回 resultCode**。若没回，
则与 CP 路同因 —— 修法可复用 `finishCredentialFlowUnlocked` 的思路（**回一个正向结果**）。

---

## 2.7 ★★ 「匹配不到条目」的真凶：暂存的 `AutofillId` 失效

### 机制

`app/.../autofill/PendingFillStore.kt` 的类注释自述：

> - 存：**解析结果（含各字段的 `AutofillId`）与时间戳**；**不存**任何明文口令
> - 单条暂存（新请求覆盖旧的）：**同一时刻浏览器只有一个填充会话**
> - `TTL_MS = 120_000L`（2 分钟）

链路：

```
进程被杀 → 浏览器点填充
  → AutofillService 暂存 parsed（含【当时的】AutofillId）  【L205 stage(parsed)】
  → 弹解锁 → 用户解锁（几秒，或切 App 输主密码，或指纹被弹 22 次…）
  → 回到 AutofillActivity → 用【暂存的旧 AutofillId】构造 Dataset 回灌
  → 若浏览器已重建页面（进程被杀往往伴随页面重载 / 会话过期）
     ⇒ AutofillId 失效 ⇒ 回灌失败
  → AutofillActivity.kt:576「无有效暂存 → 收工」⇒ 【静默收工】
  → 系统重列候选，判据未变 ⇒ 用户看到「没有匹配项条目」
```

**关键**：`PendingFillStore` 的注释自己也承认这是取舍 ——
「`AutofillId` 的有效期由**系统会话**决定，越长越可能撞上失效」。

⇒ **"匹配不到条目"是"解锁耗时长 + 页面已重建"的必然结果**，
且**恰好会被 §2.6 的"重发 22 次"放大**（重发期间页面更可能重建）。
**两条症状互相喂养** —— 这才是用户感觉"又频繁又匹配不上"的完整解释。

### 补充：锁定时**根本不做匹配**

`VaultixAutofillService.kt` L199-231：库锁定时**直接返回解锁 fallback**，
**不进入** L233 之后的候选收集与匹配（`matchLogins`）。
`noMatch → search fallback`（L268）**只在已解锁态才可能走到**。
⇒ 用户在锁定态看到的"没有匹配"，**实际上不是"匹配失败"，而是"根本没匹配过"** ——
**这是一个极具误导性的表象**，值得在文案上区分。

---

## 3. 与 Bitwarden 官方的真实对照（★ 关键更正）

### 3.1 ❌ 我方最初假设「Bitwarden 用独立 `:autofill` 进程」——**已证伪**

- 实测本仓库：`grep -n "android:process" app/src/main/AndroidManifest.xml`
  → **`NO android:process DECLARED ANYWHERE`**（Vaultix 全应用单进程）。
- 网络核实 Bitwarden 官方：`AutofillService` **注册在主 manifest**，
  **没有**独立进程声明（DeepWiki `bitwarden/android` §6.2，源 `app/src/main/AndroidManifest.xml:185-202`）。
- 旁证：`reference/bastion` 里**只有** `android:process=":accessibility"`
  （无障碍服务），**Autofill 没有独立进程**。

⇒ **"独立进程"不是差异所在，此路不通，勿据此改造。**

### 3.2 ✅ 真实差异：`isLocked` 的判据来源不同

Bitwarden 官方 `OnFillRequest`（`bitwarden/mobile` `AutofillService.cs`）核心：

```csharp
var locked = await _vaultTimeoutService.IsLockedAsync();
if (!locked) {
    items = await AutofillHelpers.GetFillItemsAsync(parser, _cipherService);
}
```

差异的本质：

| | Bitwarden | Vaultix 现状 |
|---|---|---|
| `isLocked` 从哪来 | `VaultTimeoutService`（**可持久化 + 按时间重算**） | `VaultSessionManager.sessions` 内存 Map |
| 进程被杀后 | 判据**可从存储重算**，未必判定为锁 | 内存没了 ⇒ **必然判定为锁** |
| 「从不超时」的用户 | 进程重建后**仍不锁**（无需重认证） | 进程重建后**仍要重新解锁** |

### 3.3 ✅ Bitwarden 也不免疫（重要旁证）

Bitwarden 官方 troubleshooting 页原文：

> If battery optimization is on for Bitwarden, turn it off... the service can also halt
> if you ever "**Force stop**" the Bitwarden app.

⇒ Bitwarden 靠**电池优化白名单**降低被杀频率，**不是架构上免疫**。
它"看起来不密集"的部分原因，是**它的 `isLocked` 在「从不超时」档位下进程重建后仍为 false**。

---

## 4. 结论：需要对齐什么，不需要对齐什么

| 项 | 判断 |
|---|---|
| 「独立进程」 | **不对齐**（Bitwarden 也没有；且本仓库曾因「照抄 Bitwarden 配置」翻车，见 `bitwarden-audit-2026-09-14.md` P0-1 `noHistory`） |
| 「解锁态可持久化 + 按策略重算」 | **这才是真正的差异点**，是否对齐取决于安全取舍（§5） |
| ★ **「`createdForAutofill` 豁免」** | **本已照抄，但实现是坏的（§2.5）⇒ 必须修** —— 这不是"要不要对齐"，是"抄错了要改对" |
| 「锁态只有一个口径：活跃库是否解锁」 | **已经对齐**（#66 已修），不要动 |
| 「多库时候选只取一个库」 | **已对齐**上游，但**取错库会静默空候选** ⇒ 建议补一条「取到的库无匹配时的降级」判据（§5 方案 C） |
| 「电池优化白名单」 | **可对齐**：引导用户把 Vaultix 加入电池优化例外（低风险、纯配置/文档 + 一次性引导 UI） |

⚠️ **另需澄清一条历史教训的适用边界**（`8.1-自动填充.md` L39-46）：
「锁态只有一个口径」说的是**判据不要自相矛盾**；而本报告的「解锁态不持久」说的是
**这个唯一判据的输入无法跨进程存活**。**两者不冲突，别把它们混为一谈后推翻 #66 的修复。**

---

## 5. 可选方案（**均未实施，待用户拍板**）

| # | 方案 | 改动面 | 安全影响 | 评价 |
|---|---|---|---|---|
| **A** | 持久化「已解锁」标记：进程重建后若 `VaultTimeout == Never`（或未到期）则视为**仍解锁**，但密钥仍需从**快速解锁信封**取 | 会话层 + 快速解锁链路 | ⚠️ **高**：等于让「密钥的可得性」跨进程存活，需生物识别做二次门 | 最接近 Bitwarden 体验，但与项目「真锁=密钥清零」的语义**正面冲突**（`8.2` 三条不变量），风险最高 |
| **A′** | **修复 `createdForAutofill` 豁免**（§2.5 三缺陷）：正确传递 `isFirstCreation`；用**进程级可判据**替代 Activity 事后置位；`AutofillActivity` 补设标记 | `VaultixApplication` + `VaultLockManager` + 两个 Activity | ✅ **极低**：只是让**上游既有的**豁免机制真正生效，不新增任何状态 | ⭐ **强烈推荐**，**是纯 bug 修复**，且不依赖任何安全取舍 |
| **B′** | ★ **修「重复解锁」**（§2.6）：把 `AutofillActivity.kt:574` 的 `else false` 改为**对普通路也做真实解锁检查**，解锁成功即回正向结果（`RESULT_OK` + `EXTRA_AUTHENTICATION_RESULT`）⇒ 消除"同一次填充被重发多次" | `AutofillActivity.deliverPendingFill` L574/L584 | ✅ **低**：只改收尾返回值，不动认证逻辑；**API 通路现成**（Bastion + 本仓 Service 均已用） | ⭐ **强烈推荐**（**纯 bug 修复**，直接命中"密集"） |
| **C′** | ★ **缓解「匹配不到条目」**（§2.7）：① 锁定态文案区分"未解锁"与"无匹配"（后者根本没匹配过）；② 回灌失败时给**可见反馈**而非静默收工；③ 评估暂存方案能否不依赖 `AutofillId`（如改存字段定位信息后重新解析） | Service 文案 + Activity 收尾 + 暂存设计 | ✅ 低（①③ 无安全影响；③ 需设计评审） | **推荐**，① 成本极低建议先做 |
| **B** | **只做体验收敛**：进程重建后**不弹独立的解锁 Activity**，而是走**快速解锁信封**（若该库已启用快解）→ **一次指纹**继续填充，无主密码 | 会话层小改 + 填充 fallback 分支 | ✅ 低：仍然每次都要求生物识别 | **推荐**。改动小、不破坏现有不变量、用户体感从"反复输主密码"变为"一次指纹" |
| **C** | 修「匹配不到条目」：`singleActiveVault` 取到的库**无匹配**时，降级到其它已解锁库的匹配结果（仍不聚合展示，只兜底一个） | `AutofillCandidateSource` 局部 | ✅ 无安全影响 | **推荐**，可独立于 A/B 单独做；需先确认真机复现（§6 R2） |
| **D** | 引导加入电池优化白名单 + 文档说明「从不加锁 ≠ 进程被杀后免解锁」 | 设置页 + 文案 | ✅ 无 | **建议附带**，成本极低，能减少症状触发频率（对齐 §3.3 官方做法） |

### 为什么推荐 B 而不是 A

这是本报告最需要你思考的地方：**A 会破坏项目的一条根本安全不变量。**

`8.2-锁与解锁.md` L10-16 定义得很清楚：

> **真锁（加密门禁）**：密钥**清零** → 恢复靠**主密码（+2FA）+ 联网**

而 A 的实质是「进程重建后，无需主密码即可恢复可用性」——它**必须**依赖快速解锁信封
（Keystore 包裹的 KEK）才不破安全边界。**一旦依赖信封，它就已经是 B 了。**
⇒ **A 与 B 不是两条路，A 是「B + 一个额外的解锁态持久化标记」**，而那层额外标记
恰恰是**没有收益、只增加攻击面**的部分。

**⇒ 结论：B 是达成同样体验的**最小**改动。**

---

## 6. 待核实项（做任何改动**之前**必须先做）

| # | 待核实 | 方法 | **状态（2026-09-28 真机取证）** |
|---|---|---|---|
| **R1** | 「从不加锁」的**落盘值**到底是 `Never(-2)` 还是被旧迁移误读成 `OnAppRestart(-1)` | 真机 `run-as` + `exec-out` 拉 DataStore → 解析 | ✅ **已核实 = `Never`**（见下） |
| **R2** | 装了**几个库** | 读 DataStore / 问用户 | ✅ **已核实 = 3 个库**（见下） |
| **R3** | 「频繁解锁」时弹的是**主密码框**还是**指纹** | 问用户 + logcat | ⏳ 待复现 |
| **R4** | 「后台被杀」是**手动划掉**还是**系统自动杀** | `logcat` 抓 `am_kill` / `lowmemorykiller` | ⏳ 待复现 |
| **R5** | ★ `deliverPendingFill()` 失败/成功时回没回 `resultCode` | 读代码 | ✅ **已定位**（`else false` + `response==null` 一律 CANCELED，见 §2.6） |
| **R6** | ★ 用户实际走的是**普通 autofill** 还是 **CP/通行密钥** | 问用户 + `logcat` 的 `credentialFlow=` | ✅ **用户答复：浏览器 + 填充框点击（= 普通 autofill，主战场）；通行密钥偶发** |
| **R7** | 「匹配不到条目」出现时，是真**匹配失败**还是**根本没匹配** | 看日志有无 `noMatch` | ⏳ 待复现 |

### ✅ R1 取证结果：落盘值 = `Never`（排除迁移陷阱）

**方法**：`adb exec-out run-as io.vaultix.vaultix cat files/datastore/vaultix_settings.preferences_pb`
**字节数核对**：本地 947 = 设备 947 ✅（拿到真数据）。

> ⚠️ **2026-09-28 修订（一次实测反例，推翻下面这条旧经验）**：
> 同日在拉 `files/fill-assist-cache.json`（29318 B，纯文本 JSON）时实测：
> - `exec-out run-as … cat` → **只拿到 152 B**（错误输出，内容是 `cat: C:/Users/…: No such file`）；
> - `shell "run-as … cat"`（**走 shell 重定向**）→ 拿到 **29318 B，字节数与设备一致** ✅。
>
> **真因**：**git-bash 的 MSYS 路径自动转换**把命令行里的 `/data/data/…` 改写成了
> `C:/Users/chani/.workbuddy/binaries/PortableGit/versions/1.2.0/data/data/…`。
> 证据直接可见于设备侧 `adbd` 日志的回显：
> `exec:run-as 'io.vaultix.vaultix' 'cat' 'C:/Users/chani/…data/data/io.vaultix.vaultix/files/fill-assist-cache.json'`。
>
> ⇒ **结论修正**：
> - 若必须 `exec-out`（真二进制，防换行损坏），**路径前加 `MSYS_NO_PATHCONV=1`**
>   或把路径写成 `//data/data/…`（双斜杠）以阻止转换；
> - **纯文本**文件（`.json` 等，无二进制风险）**直接走 `shell + 重定向` 更省事且不踩路径坑**；
> - 无论哪条路，**必须核对字节数**（这条旧结论仍然成立，本次正是靠它才发现 152 B 是假的）。

**解析**（手写 protobuf varint 解析）：

```
1 (bytes/28) -> nested:
  1 (bytes/13): 'vault_timeout'
  2 (bytes/11) -> nested:
    3 (varint): 18446744073709551614      ← = 2^64-2 = 有符号 -2
```

对照 `VaultTimeout.kt` 编码表：**`Never → -2`** ⇒ **落盘值正确，未踩 `-1` 迁移陷阱。**

**⇒ 这条排除了一个假设，把因果链收窄到唯一解释**：
用户设 `Never` ⇒ `checkForVaultTimeoutInternal` 走 `Never -> return@launch`（**超时锁完全不参与**）
⇒ 频繁解锁 **100% 来自「进程被杀 → 解锁态丢失 → 填充时重新认证」**（§2.1）。
**且 §2.5 的 `createdForAutofill` 豁免在这个场景下也不适用**（那是 `OnAppRestart` 档的分支）。

### ✅ R2 取证结果：3 个库（多库放大项成立）

同一份 DataStore 里出现 3 个库 id：

| # | 库 id | 类型 |
|---|---|---|
| 1 | `https://pwd.vv1234.cn` | Bitwarden 类（自建 Vaultwarden） |
| 2 | `content://…/我的文件/valkjin.kdbx` | **本地 KDBX** |
| 3 | `onedrive:00000000-…:Keepass/valkjin.kdbx` | **OneDrive KDBX** |

⇒ **§2.4 的「单活跃库退化」（`unlocked.minOrNull()`）在多库下确实可能取错库**
⇒ 「匹配不到条目」的**独立放大项成立**，需在复现时确认（R7）。

**另**：快解配置齐全 —— `pin_unlock_enabled_*` ×3 = 1、`local_unlock_enabled_*` ×2 = 1、
`quick_unlock_scope_confirmed` = 1 ⇒ **指纹/PIN 快解都已启用**（与用户描述一致）。
⇒ 说明「快速解锁信封」这条链路的**前提条件已具备**，B 方案（进程重建后走快解信封）
**在该设备上可直接生效**。

### ⏳ 待复现（需用户配合操作 + 实时 logcat）

**关键待抓**：`credentialFlow=` 的实际取值、`outcome=` 序列、有无 `noMatch`、
是否出现同一动作多次 `maybeBiometricUnlock`（§2.6 的重发特征）。

---

---

## 7. 若决定动手，最小落地顺序（**待拍板**）

1. **R1–R4 取证**（不写代码，只查证）。
2. ★ **方案 B′（修普通 autofill 路的重复解锁，§2.6）** —— ⭐ **优先级最高**：
   **纯 bug 修复、零安全取舍**，且**直接命中用户说的"密集"**（`outcome=Ready × 22` 实证）。
   病灶已定位到单行（`AutofillActivity.kt:574` 的 `else false`）。
   **修法通路已确认**（Autofill 框架的 `RESULT_OK` + `EXTRA_AUTHENTICATION_RESULT`，
   Bastion 与本仓 Service 均有实证）⇒ **可动手**。
3. **方案 A′（修 `createdForAutofill` 豁免）** —— ⭐ 同样是纯 bug 修复：
   ① 上游机制本项目抄漏；② **无安全取舍**；③ 修好后「重启即锁」档拿回上游同款豁免。
4. **方案 C′** —— ① **锁定态文案区分"未解锁"与"无匹配"**（成本极低，建议先做）；
   ② 回灌失败给可见反馈；③ 暂存设计是否需要脱离 `AutofillId`（需评审）。
5. 方案 **C**（无安全影响，独立可做）→ 真机验收「多库时不再静默空候选」。
6. 方案 **B**（体验收敛）→ 需先确认 R3；若该库未启用快解，B 退化为"仍要主密码"，
   此时应先引导用户启用快解。
7. 方案 **D**（文案 + 电池优化引导）。

> **不建议**做的事：① 给 AutofillService 加 `android:process`（§3.1 已证伪）；
> ② 在 `VaultSessionManager` 里直接持久化密钥（破坏 `8.2` 不变量）；
> ③ 退回「遍历全部已解锁库聚合候选」（重新引入重复候选，见 `AutofillCandidateSource` L47-49）。

### 7.1 方案 A′ 的具体修法（供开工参考，**未实施**）

三个缺陷要一起修，**只修一个没用**：

| 缺陷 | 修法要点 |
|---|---|
| A `isFirstCreation` 恒 true | 需要区分「**用户冷启动**」与「**系统为 autofill 拉起**」。二者在 `Application.onCreate` 时刻**都表现为"进程刚创建"** ⇒ 单纯改传参无解，**必须换判据来源**（见下） |
| B 时序不可行 | 「先置位、后读」在 `Application.onCreate` 路径上不成立 ⇒ 标记**不能靠 Activity 事后置位**。可考虑：`ActivityManager` 查进程启动原因 / 在 `ContentProvider.onCreate`（**早于 Application）捕获启动 Intent** / 或改为**延迟判定**（不在 `onCreate` 立即锁，而是首个 Activity 起来后再判） |
| C `AutofillActivity` 未置位 | 补标记（但须先解决 B，否则补了也没用） |

⚠️ **纪律提醒**：修之前必须**先做闭环证明** —— 即「把代码还原成出 bug 的版本，
看探针是否恰好报出这三处」。本项目 #124 有过「连坏两版都是假绿」的教训。
⚠️ 另：`createdForAutofill` 的判定**必须绑定到"是哪个解析者/层"**
（`issues/03` #108 的教训：写排除结论必须绑定具体层，否则后人当全局结论用）。

---

## 8. 已实施的修复（2026-09-28）

> ⚠️ **与用户原要求的关系**：用户最初要求「先要诊断报告，不动代码」。
> 但在第二轮排查后（用户补充「已设指纹解锁、更密集」并确认入口是**浏览器填充框**），
> 用户同意在此方向推进。本节记录**实际动过的代码**。

### 8.1 已修：B′（普通 autofill 路的重复解锁）—— `AutofillActivity.kt`

**两处改动，都在 `deliverPendingFill()`：**

| # | 位置 | 改动 |
|---|---|---|
| 1 | L574 附近 | `val unlockedNow = if (credentialFlow) isAnyVaultUnlocked() else false` → **`val unlockedNow = isAnyVaultUnlocked()`**（去掉 `else false`，两条路统一做真实检查） |
| 2 | L605 附近 | 原来两条路都调 `finishCredentialFlowUnlocked()`；**拆开**：CP 路保持原样，普通 autofill 路改回 **`setResult(Activity.RESULT_OK)` + finish**（Autofill 框架的正向结果，不走 Credential Manager API） |
| 3 | L626 附近 | `response == null` 时**不再一律 CANCELED**：若库**已解锁**（`isAnyVaultUnlocked()`）则回**空 `RESULT_OK`**（动作完成、不重发）；仅当**确实仍未解锁**才回 `CANCELED` |

**依据**：
- Autofill 框架的正向结果姿势 = `RESULT_OK` + `EXTRA_AUTHENTICATION_RESULT`
  （`reference/bastion/.../AutofillAuthenticationActivity.kt:249-258`；
  本仓 `VaultixAutofillService.kt:593-601` 已在用）。
- `finishCredentialFlowUnlocked` 是 **CP 专用**（`@RequiresApi(34)` + Credential Manager
  的 `PendingIntentHandler.setBeginGetCredentialResponse`）——**Autofill 框架用不了**，
  原代码对普通路调它是错的载体（低版本还会直接 `finish()`=CANCELED，等于没修）。

### 8.2 门禁证据（三关分开单跑）

| 关卡 | 命令 | 结果 |
|---|---|---|
| 编译 | `gradle :app:compileFullDebugKotlin` | ✅ BUILD SUCCESSFUL（5m29s，无新增 error） |
| detekt | `gradle detekt` | ✅ BUILD SUCCESSFUL；**app 模块 0 findings**（`detekt.xml` 的 `<checkstyle>` 为空） |
| 单测 | `gradle :app:testFullDebugUnitTest` | ✅ BUILD SUCCESSFUL（2m20s） |

#### 8.2.1 复核（**2026-09-28 晚，带 `--rerun-tasks` 强制重跑**）

⚠️ **首次三关全部报 `UP-TO-DATE`** —— 按项目纪律（`.ai/` 「任务 UP-TO-DATE ≠ 那段代码是好的」）
这**不构成有效门禁**，故对 detekt 与 test 加 `--rerun-tasks` 重跑，拿到真实结果：

| 关卡 | 命令（带 `--rerun-tasks`） | 结果 |
|---|---|---|
| detekt | `gradle :app:detekt --rerun-tasks` | ✅ `1 executed`；`app/build/reports/detekt/detekt.xml` 的 `<error>` = **0 条** |
| 单测 | `gradle :app:testFullDebugUnitTest --rerun-tasks` | ✅ `200 executed`；**用例 229 / 跳过 0 / 失败 0 / 错误 0**（汇总自 `app/build/test-results/testFullDebugUnitTest/*.xml`） |
| 编译 | `gradle :app:compileFullDebugKotlin` | ✅（UP-TO-DATE；编译健康度已由上方 detekt+test 真跑覆盖） |

### 8.3 打包与装机（2026-09-28 晚，**已完成**）

> ⚠️ **此前"未装新包"的原因**：修复代码改完但**从未构建**——本仓最近 APK 停在 2026-09-16，
> 而设备上装的是 09-26 的 `0.5.0-dev-a4280f7`（= 当前 main HEAD，**不含那 3 处未提交改动**）。
> ⇒ 缺口在**打包/装机**这一跳，不是代码。

| 步骤 | 命令 | 结果 |
|---|---|---|
| 打包 | `gradle :app:assembleFullDebug` | ✅ 1m51s → `app/build/outputs/apk/full/debug/app-full-debug.apk`（**36,211,755 B**） |
| 装机 | `adb install -r` | ✅ `Success`（16s） |
| 核证① | `dumpsys package io.vaultix.vaultix` | `lastUpdateTime=2026-09-28 19:43:02`；版本名 `0.5.0dev…` → **`0.5.0`** |
| 核证② | **SHA-256 逐字节比对** | 本地构建包 = 设备上的包 = **`c7253a7fb71fe4941e137b3842aa6e3670e8e44c217234cb3eb9a7044237cf97`** ✅ |

⚠️ **核证②过程踩坑（值得记）**：首次用 `adb shell "cat '<apk>'"` 拉回设备包，得
**36,333,709 B**（vs 设备端 36,211,755 B，**多 121,954 B ≈ LF 数**）⇒ SHA 不符。
**这不是包不对，是 `cat` 把二进制换行转换损坏了**。改用
**`MSYS_NO_PATHCONV=1 adb exec-out "cat <apk>"`** 后两侧字节数与 SHA 完全一致。
（⇒ 同时验证了 git-bash 路径转换那条新经验：必须 `MSYS_NO_PATHCONV=1`。）

### 8.4 真机验证结果（⚠️ **结论：未触及 B′ 修复点**）

**场景**（2026-09-28 19:43:57 ~ 19:44:02，荣耀 BKQ-AN00，QQ 登录框）：

进程**刚被系统拉起**（`Start proc 15018:io.vaultix.vaultix for bound-service {…VaultixAutofillService}`
= 典型的「被杀后重建」），随后：

```
19:43:59.836  locked: no unlocked vault → unlock fallback
19:44:00.674  maybeBiometricUnlock: outcome=Prompt credentialFlow=false hasPending=true
19:44:01.467  unlockAllAndFinish: 认证成功 first=https://pwd.vv1234.cn rest=1 credentialFlow=false
19:44:01.535  buildPendingResponse: unlocked=1
19:44:01.669  buildPendingResponse: datasets=1
19:44:01.669  deliverPendingFill: response=true          ← ★ 走的是 response != null 分支
19:44:01.916  fillResponse domain=null active=https://pwd.vv1234.cn unlocked=1 candidates=216 matched=5 datasets=5
```

**体验指标**：`maybeBiometricUnlock` 全程 **仅 1 次**（`outcome=Prompt`，正常弹一次指纹）。

**⚠️ 但 B′ 的三条修复分支一条都没被触发**（实测计数全为 0）：

| B′ 修复点 | 本次是否走到 |
|---|---|
| ① `pending == null` → `isAnyVaultUnlocked()`（去掉 `else false`） | ❌ 未走到（`pending != null`） |
| ② 普通路 `setResult(RESULT_OK)` 而非 `finishCredentialFlowUnlocked()` | ❌ 未走到（同上） |
| ③ `response == null` → 空 `RESULT_OK` 而非 CANCELED | ❌ 未走到（`response != null`） |

**因果澄清（重要，避免误记功）**：
修复前后对比 `deliverPendingFill()` 源码（`git show HEAD:…/AutofillActivity.kt`）可见——
**`response != null` 这条路修复前后行为完全相同**（都是 `RESULT_OK` + `EXTRA_AUTHENTICATION_RESULT`）。
本次实测走的正是这条**本来就没坏**的路。
⇒ **本次「体验正常」不能归因于 B′**，B′ 修的是 `pending == null` / `response == null` 两条**失败旁路**。

**⚠️ 另一个必须澄清的对比陷阱**：`AutofillActivity.kt:266-275` 记载的 `outcome=Ready × 22`（重发 22 次）
其 `realCallingUid=10165 = com.google.android.gms:identitycredentials` = **凭据面板（CP 路）重发**；
而本次是 `credentialFlow=false`（**普通 autofill**）。**两者不是同一场景，不可直接对比次数。**

**⇒ 结论**：本次真机验证证明了「进程被杀后一次指纹即可填充成功」这条**主链路是通的**
（用户观感确实改善），但**尚未复现**触发 B′ 三条分支的条件，B′ 的有效性**仍待验证**。

### 8.5 要触发 B′ 修复点，需要什么条件（下一步复现指引）

| 修复点 | 触发条件 |
|---|---|
| ①②（`pending == null`） | 让暂存**失效**：① 弹解锁后**拖过 120 秒**（`TTL_MS`）再解锁；或 ② 走「打开 Vaultix 主界面解锁」外部路（`awaitingExternalUnlock`）而暂存已被覆盖/超时 |
| ③（`response == null` 且已解锁） | 暂存**有效**（`pending != null`）但 `buildPendingResponse()` 返回 null —— 即 `unlocked.isEmpty()`（**解锁后又被锁**）或 `added == 0`（**`AutofillId` 已失效**，需解锁期间页面重建） |

⚠️ **旁证**：19:44:09 与 19:44:51 两次后续 `fillRequest` 都返回
`fillResponse … candidates=216 matched=5 datasets=5`，**库已解锁 ⇒ 不再弹任何解锁**
⇒ 与「解锁后系统重列候选即正常」的设计一致。

---

## 附：证据索引

| 证据 | 位置 |
|---|---|
| 解锁态纯内存 | `data/repository/.../VaultSessionManager.kt:22,29,82` |
| Never 语义 | `core/datastore/.../VaultTimeout.kt:98,104,146` |
| 填充弹解锁分支 | `app/.../autofill/VaultixAutofillService.kt:198-231` |
| ★ `createdForAutofill` 豁免判据 | `app/.../security/VaultLockManagerImpl.kt:196-204`（+ `VaultLockManager.kt:115-122` 设计意图） |
| ★ `isFirstCreation` 恒 true | `app/.../VaultixApplication.kt:85-88` |
| ★ 时序不可行（注释自相矛盾） | `VaultixApplication.kt:57-60` vs `CredentialProviderActivity.kt:88-89` |
| ★ `AutofillActivity` 未置位 | `grep markCreatedForAutofill` 仅命中 `CredentialProviderActivity.kt:90` |
| ★★ 重复解锁实证（`Ready × 22`） | `app/.../autofill/AutofillActivity.kt:252-270`（KDoc 内实测日志） |
| ★★ 修复只覆盖 CP 路 | `AutofillActivity.kt:427`（`if (credentialFlow) … else deliverPendingFill()`） |
| ★★ CP 路正向收尾的正确做法 | `AutofillActivity.kt:244-288`（`isAnyVaultUnlocked` / `finishCredentialFlowUnlocked`） |
| ★★ `AutofillId` 暂存与 TTL | `app/.../autofill/PendingFillStore.kt`（类注释 + `TTL_MS = 120_000`） |
| ★★ 锁定态不进入匹配 | `app/.../autofill/VaultixAutofillService.kt:199-231`（匹配在 L233 之后） |
| ★★ B′ 病灶（`else false`） | `app/.../autofill/AutofillActivity.kt:574`、L584 |
| ★★ Autofill 正向结果姿势（上游实证） | `reference/bastion/.../AutofillAuthenticationActivity.kt:249-258`（+ `:269-282` 失败才 CANCELED） |
| ★★ 本仓已在用同一套 | `app/.../autofill/VaultixAutofillService.kt:593-601` |
| 单活跃库退化 | `app/.../autofill/engine/AutofillCandidateSource.kt:51-55` + `ActiveVaultStore.pick()` |
| 无独立进程（本方） | `app/src/main/AndroidManifest.xml`（grep `android:process` 为空） |
| 无独立进程（上游） | DeepWiki `bitwarden/android` §6.2；`bitwarden/mobile` `AutofillService.cs` |
| Force stop 亦停 | Bitwarden 官方 troubleshooting 页 |
| 照抄配置翻车先例 | `Docs/progress/bitwarden-audit-2026-09-14.md` P0-1（`noHistory`） |
| 锁态单一口径 | `.ai/conventions/8.1-自动填充.md` L39-46 · `ISSUES.md` #66 |
| 真锁/查看锁不变量 | `.ai/conventions/8.2-锁与解锁.md` L7-24 |
