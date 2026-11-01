# 04-锁与解锁 · 第 2 篇（#88 ~ #188）

> 主篇索引：[`04-锁与解锁.md`](./04-锁与解锁.md)（含计数规则与重号警告）
> 本篇 6 章 · 16.3KB · 覆盖 #88 ~ #188

## 88. debug 包 → release 包**原地覆盖**后，快速解锁（指纹）失效，需重新启用才恢复（2026-09-13，第五十二轮）

> ★ **专项计划：[`Docs/progress/quick-unlock-plan.md`](../../Docs/progress/quick-unlock-plan.md)**
> —— 含复现步骤、埋点清单、观测→根因判据表、以及可先行的 UX 改进。本条目只记结论与已排除项。

> ### ⚡ 勘误 + 结案（2026-09-13 22:45）—— 下面那条"结论"**是错的**，勿再据此排查
>
> **错在哪**：原文断言「**状态（包裹密钥 / KEK）在"原地更新"过程中变成了不可解密**。
> 这是真 bug，会命中每一个升级的用户。」该断言**从未被证实**，本次实测**直接证伪**：
>
> | 实测 | 结果 | 排除了什么 |
> |---|---|---|
> | `local_unlock_enabled_https://pwd.vv1234.cn`（DataStore `.pb` 值字节 `12 02 08 01`） | **true** | 不是"开关被关" |
> | `shared_prefs/vaultix_secure.xml` 含 `local_unlock_key::https://pwd.vv1234.cn` | **payload 在** | **`disableLocalUnlock` / `clearBrokenLocalUnlock` 从未执行**（二者都会删该 payload） |
> | `bw_access::` / `bw_refresh::` / `bw_protected_key::` | 均在 | 与"凭据库密钥"无关，不用重登 |
>
> **决定性反证**：用户原话「**必须清掉后台打开，才能看到指纹解锁**」——
> 若 KEK 真是 `MISSING`/`INVALIDATED`，**重启进程不可能恢复**。⇒ 只能是 **UI 状态被固化成错值**。
>
> **真实根因：`UnlockViewModel` 的 `vaultId` 竞态。**
> "选库"（协程 A，`:140`）与"订阅可用性"（协程 C，`:169`）**并发**收集同一个 `observeVaults()`；
> C 若先处理同一次发射，其 `.map` 读到的 `var vaultId` 仍是空串 ⇒ `return@collectLatest` 跳过，
> 而 `localUnlockAvailable` 的**唯一写入点**（`:179`）正在其后 ⇒ 状态**永久停在初值 `false`**
> ⇒ **指纹入口不渲染、生物识别也不自动弹**（`eligible` 里含同一项）。
> 重建 ViewModel（手动返回 / 清后台重开）才有一次重掷机会 —— 与全部用户观感吻合，
> 也解释了"系统 keystore 日志一条都没有"（压根没读密钥）。
>
> **修复**：`UnlockViewModel` 新增私有 `resolveTargetVaultId(vaults)`，订阅侧改为
> `.map { resolveTargetVaultId(it) }.filter { it.isNotBlank() }.distinctUntilChanged()`，
> **不再读跨协程 `var vaultId`** ⇒ 竞态从根上消失（语义不变：路由带参只认该库；无参才自动选库，
> 且先看查看层锁再看"第一个未锁定"）。
> 门禁：新增 `UnlockViewModelTest`（3 例）**修前精确红 → 修后绿**；全模块 detekt ✓ /
> `:app:compileFullDebugKotlin` ✓ / `:app:assembleFullDebug` ✓ / app 全量单测 ✓。
> 真机已覆盖安装（证书 SHA-256 一致、`firstInstallTime` 未变 ⇒ 数据未丢）。
>
> ⚠️ **仍未排除的次要可能**：`keyAvailable == false`（KEK 别名真丢/失效，只读探测不抛异常）。
> **判据**：修复后锁定密码库，若解锁页**仍**无指纹入口 ⇒ 才转向 KEK 那条线。
> 本次现场证据（`artifacts/rec_manual.mp4` / `f60.png` / `screen_cur.png`）见专项计划 §1.2~§1.6。
>
> ### ✅ 该"次要可能"随后被实测排除 + 真机验收通过（2026-09-13 22:53）
> - `keystore2` 日志：`add_auth_token(authType=0x2)` → `createOperation/update/`**`finish`**（带 `HardwareAuthToken`）；
>   `acceptCrypto` **118 → 135** ⇒ **KEK 健康、`unwrap` 成功** ⇒ `keyAvailable == false` 不成立。
> - 真机 dump 到 `window-name="biometrics_dialog"`（`解锁 Vaultix` / `请按压屏内指纹感应区验证指纹`）
>   ⇒ 自动弹窗**真的发起** ⇒ `localUnlockAvailable == true`。
> - 用户确认：解锁页**有指纹入口**且**实际能解锁**。
> ⇒ **#88 结案。**
>
> ✅ **本条曾写「修复仅在本地，尚未提交/推送」，2026-10-04 核实为过期，已删除。**
> 证据：修复提交 `e724757 fix(unlock): 修掉 vaultId 竞态导致指纹入口消失（#88）`
> 早已入库；`resolveTargetVaultId` 现仍在 `UnlockViewModel.kt:377`，
> 且被 `UnlockViewModelTest`（3 例）覆盖，后续还有两次提交在其上继续演进
> （`2c2d2fb` 应用内 PIN、`ae80aae` 房子化批次 1）。⇒ 交付链路上并无缺口。

- **现象**（用户报）：「新包（CI release 0.2.0）覆盖本地的 debug 包后，生物验证好像失效了；
  我重新登录、删掉生物验证、再重新开启，才能继续指纹解锁。」
- **已排除的原因**（实测，不是猜）：
  - **不是卸载重装**：`firstInstallTime=2026-09-12 13:46:46` / `lastUpdateTime=2026-09-13 21:39:03`
    ⇒ 真·原地更新，**应用数据与 Keystore 都保留**；
  - **不是签名不一致**：把 CI 发布的 APK 与手机上已安装的 APK 都取出来比对证书，
    两者 SHA-256 **完全相同**（`7e6c8c07…`）⇒ 包名与签名身份都没变；
  - **不是 release 包功能坏了**：重新启用后**指纹立刻可用** ⇒ 代码路径正常。
- **⇒ 结论：状态（包裹密钥 / KEK）在"原地更新"过程中变成了不可解密。这是真 bug，会命中每一个升级的用户。**

- **待验证的候选根因**（都需要 debug 包 + logcat 才能定性）：
  1. `SecureCredentialStore` 的承载方式在更新后失效（若是 `EncryptedSharedPreferences` 一类，
     其 master key 的生命周期需要单独确认）；
  2. KEK 被判定为"永久失效"（`KeyPermanentlyInvalidatedException`）—— 但更新**不应**导致；
     需确认 OEM（荣耀）是否在 update 时重建 Keystore 条目；
  3. 包裹 payload 与 KEK 不匹配（`unwrap` 抛 `AEADBadTagException`）
     ⇒ 被 `isLocalUnlockUnrecoverable()` 判为不可恢复 ⇒ 清理注册（**这条会给用户正确的提示**，
     但用户描述是自己手动删的，需确认到底是谁清的）。
- **复现方法（可主动做）**：当前 release 0.2.0 已装、快速解锁已重新启用且可用。
  此时**用本地 debug 包（同一把密钥，可原地覆盖）再装一次**，观察快速解锁是否再次失效 ——
  若能稳定复现，即可在下次冷启动时抓 logcat（关注 `LocalUnlockKeyStore` / `completeLocalUnlock`
  / `isLocalUnlockUnrecoverable` 相关分支）。
- **影响**：**不阻断发布**（可两下操作自愈、数据不丢），但**每个升级的用户都会遇到** ⇒
  应与性能专项并列，列为下一轮的高优先项。
- **⚠️ 排查前提**：release 包 `run-as` 被拒（`package not debuggable`）—— 读应用数据必须装 debug 包；
  性能专项里"读缓存内容"的那部分同理（这一点已记入 `Docs/progress/perf-plan.md` 的前提）。

## 131. 多库「锁」的作用域不一致：四把锁四个答案 + 单库动作带全局副作用（2026-09-29，P0-结构）

- **现象**（用户）：两个库互相解锁/加锁逻辑混乱 —— 手动锁 A，B 的冷启动恢复也没了；
  开 A 再开 B，只有 B 会被计时锁，A 永不自动锁。
- **根因**：四种「锁」作用域互不一致，且存在跨层/跨库副作用：
  1. `lockVault(id)`（`VaultRepositoryImpl.kt:326-341`）锁单库却**删全局 `house_lock_auto`**（`:340`）
     —— 删的本意对（不删则冷启动把刚锁的库悄悄开回来，「锁定」被静默撤销），错在**粒度**
     （auto 信封包的是**全局房钥匙**）与**缺状态**（没有「用户主动锁」的每库持久记录）；
  2. `lockOtherKdbxVaults(keep)`（`:231`）只覆盖 KDBX、不含 Bitwarden 云库，
     且挂在「进入条目页」这个 UI 时机；
  3. 超时定时器只认 `activeVaultId`（`VaultLockManagerImpl.kt:195-198`）；
     `CheckTimeoutReason.UserChanged`（`:271`）**全项目零调用方**（死代码）——
     而 Bitwarden 有 `handleUserSwitch → checkForVaultTimeout(previous, UserChanged)`（`:560-571`）。
- **解法**：[`多库锁模型-定稿`](../decisions/多库锁模型-定稿.md) D1（每库「用户主动锁」标记，
  不删全局信封）+ D2（每库各自计时）+ D3（档位每库一份）。
- **判据**：修完「锁 A」不得改变 B 的任何状态；每个已解锁库各有自己的定时器。

## 132. KDBX 快速解锁比 Bitwarden 慢 100 倍：房间信封包「主密码」而非「派生密钥」（2026-09-29，实测）

- **现象**（用户）：切到 KDBX 库指纹解锁要等好几秒；Bitwarden 库秒开。
- **实测**（同 App、同轮、同指纹弹窗）：BW（`pwd.vv1234.cn`）解锁 **33 ms**；
  KDBX（OneDrive `valkjin.kdbx`）**3.58 s**。指纹认证本身 ~1 s（含按手指），慢在认证之后。
- **根因**：房间信封 payload 不对称 —— BW 侧包**派生 64B full key**
  （`LocalUnlockEnrollment.kt:63-64`，取出即用，零 KDF）；KDBX 侧包**主密码 + keyfile**
  （`KdbxUnlockPayload` 线格式）⇒ 每次解锁都要重跑 Argon2id + 重解密整库。
  Bitwarden 官方同思路（`storeUserAutoUnlockKey(getUserEncryptionKey)` + `DecryptedKey`）。
- **顺带解释**：登记 KDBX 时多输一次主密码是**密码学必然**（解锁后内存只有库明文，
  密码本体不保留）；信封改包派生密钥后「已解锁时登记」即可免输入 —— 一箭双雕。
- **解法**：定稿批次 B —— KDBX 信封改包 KDF 后 masterKey（文件头 HMAC 校验开库，
  校验失败回退主密码重跑并重包裹）。

## 133. 「一次认证开多库」的补开被导航取消 ⇒ 用户被迫按第二次指纹（2026-09-29，实测）

- **现象**（日志）：`fanout rest=onedrive:… → Unavailable(Job was cancelled)` +
  `VaultixUnlockVm: 其余库补开失败（不影响已进入的库）：Job was cancelled`
  （`UnlockViewModel.kt:626-632`）。
- **根因**：`openRest` 跑在与页面生命周期绑定的 scope（viewModelScope）——
  首库打开即放行导航，导航销毁 ViewModel ⇒ 补开 job 一并取消。
- **解法**：定稿 D5 —— 补开挪到应用级长生命周期 scope（或关键段 `NonCancellable`）。
- **判据**：一次指纹后已登记房间全部打开（`fanout rest done opened=N failed=0`，无 cancelled）。

## 161. 🔴 「PIN 忘记后可在设置里重设」——**文案承诺了功能，入口不存在**（2026-10-04，判读孤儿串时发现）

- **怎么发现的**：为#126 判读 118 条零引用字符串，`quick_unlock_action_change_pin`
  （文案就是「修改 PIN」）**零引用**。但零引用本身不稀奇（多数是残留），
  这次是因为它**恰好指向一个真实缺失的交互**才值得追下去。

- **确认的三条证据**（都不是"看代码觉得没有"，是逐条查出来的）：
  1. `strings.xml:636` 写着 `<string name="quick_unlock_action_change_pin">修改 PIN</string>`
     —— **文案早就写好了**，零引用。
  2. `strings.xml:688` 的 `pin_section_hint` 对用户承诺：
     >「PIN 用于平时快速解锁。**忘记它不影响数据——用主密码解锁后在设置里重设即可。**」
  3. 设置页 PIN 行（`QuickUnlockDialogs.kt:167-178`）整行 `onClick = onTogglePin`、
     尾部 `CapabilityToggle(onToggle = onTogglePin)` —— **两处都是 `togglePin`**。
     PIN 已启用时点进去走的是**关闭流程**，全仓库 `grep -i "change_pin|改PIN|重设 PIN"`
     在 `app/src/main` 下**零命中**：没有任何路径能进入「改」。

- **后果**：用户按提示去设置里「重设」，**找不到任何入口**。
  唯一出路是关掉 PIN 再重开（要输一遍旧 PIN）⇒ 提示不仅无效，还**引导用户绕远路**。
  这是本项目最在意的那类问题（见 8.4「假成功/假入口」一节）：
  **承诺了做不到的事，比不承诺更糟**。

- **★ 为什么这比"孤儿串残留"严重**：孤儿串多留一条只是冗余；
  而这里是一条**面向用户的错误指引** —— 它教用户去找一个不存在的功能。

- **修法（建议，尚未实施）**：对齐 Bitwarden 的做法 ——
  `togglePin` 在 PIN **已启用**时应打开「改 PIN」流程（需先验旧 PIN 或主密码），
  而不是直接走关闭。可复用 `PinEntryDialog` 的三态：
  `SetNew`（首次）/ `Change`（已启用，要先验旧）/ `TurnOff`（关闭）。
  ⚠️ 关键约束：**不能**让「关闭」变得比「改」更难点，否则用户会为了改 PIN 先关一遍。
  另外 `pin_change` / `pin_confirm_label` / `pin_mismatch` / `pin_too_short`
  这几条零引用串**正好是这个流程缺的文案** ⇒ 补入口时一并接上，
  一次性消掉 `pin_*` 21 条孤儿串里的一批。

- **门禁（若实施）**：`QuickUnlockDialogs` 已有 PIN 三态的 Compose 层，
  缺的是**编排**（哪个状态进哪个流程）⇒ 应补`QuickUnlockController` 侧的状态单测，
  断言「PIN=On 时点该行 ⇒ 进 Change 流程且要求验旧 PIN」，
  断言「PIN=Off 时点该行 ⇒ 进 SetNew 流程」。**修前精确红、修后绿**才算数。

### ✅ 已修（2026-10-14 · `47ca162`，B5）

- **先修的是「修改 PIN」入口**（`startChangePin` 那一行，要验旧 PIN），
  #161 的原始诉求闭环；**但「忘记 PIN」的人仍然无路** —— 旧 PIN 正是他忘掉的东西。
- **补的正是这一条**：PIN 开关行下方新增「忘记 PIN？」行（红色文字，破坏性入口），
  点击弹二次确认框。语义 = **不验任何东西**清掉 PIN 门锁信封
  （`HouseKeyStore.disablePinLock`：删 `house_lock_pin` + `house_pin_attempts`）——
  PIN 从来不是根凭据（主密码才是），清除不丢数据、不解锁任何内容，
  之后走主密码（或指纹）进门，再把开关拨回 On 即设新 PIN。
  与 `startChangePin`（验旧 PIN）分工明确：**记得旧 PIN 走「修改」，忘了走「忘记」**。
- **实现要点**：`QuickUnlockController` 新增 `Dialog.PinForgotConfirm` + `onForgetPin`
  （一个方法兼顾「打开确认框 / 执行清除」两态 —— 该类顶着 detekt `TooManyFunctions`
  **40 上限**，只加得起一个方法）；顺带把 `newPinInvalid` 挪成文件级 internal
  （腾函数位 + 变得可单测）。另补上 2026-10-04 加「修改 PIN」行时**漏掉的
  `SettingsDivider()`**（组内行间必须显式分隔线，见 `SettingsGroupCard` 契约）。
- **测试**：`HouseKeyStoreTest` 1 例（清信封 + 尝试计数不残留 + 重设即新 PIN，
  旧 PIN 变 `WrongPin` 而非 `LockedOut`）；`QuickUnlockControllerTest` 3 例
  （`newPinInvalid`：位数与一致性都不过时报位数 / 位数够但不一致 / 放行）。
- **⚠️ 判据**：**「有重设路径」不等于「忘了凭据的人有重设路径」** ——
  所有要验旧凭据的流程，都要同时问一句「忘旧的那个人怎么办」。


---

## 188. 🟡 锁库不清剪贴板 + 网址被当敏感内容 30 秒后清空（2026-10-26，对比审计）

- **现象**：
  1. 复制一条密码后锁库（手动或自动），**剪贴板里密码还在**，任何 App 都能粘出来；
  2. 复制一条条目的**网址**，30 秒后粘出来是空的 —— 用户以为是系统问题。
- **根因**：
  1. `setVaultToLocked()` 里没有任何剪贴板动作；
  2. `ItemDetailViewModel.doCopy` 无条件按敏感内容处理（`clearMs = clipboardClearMs`），
     对网址也一样。
- **修法**：
  1. `VaultLockManagerImpl.setVaultToLocked` 里 `runCatching { clipboard.clearNow() }`；
  2. `VaultixClipboard.clearNow()`：先取消定时任务，然后**只在确认剪贴板里就是自己放的
     东西**时才清 —— 判据是 `ClipDescription` 带 `EXTRA_IS_SENSITIVE`（API 33+）
     或 `label == "Vaultix"`；**读不到就什么都不做**（绝不盲清：
     清掉用户从别处复制的验证码是同样的伤害）；
  3. `doCopy` 改为 `val sensitive = !isUri` ⇒ 网址 `clearMs = 0L`（不定时清）。
- **⚠️ 坑**：`clipboardScope` 原先是 `Dispatchers.Main.immediate`，`delay` 挂在主线程上
  —— 界面忙 / Doze 时定时器会被整体推迟，所谓"30 秒清空"可能变成几分钟。
  改为 `Dispatchers.Default`。
- **判据**：**"定时清理"必须配一个"立即清理"的触发点**。只靠定时器的话，
  锁库这一刻（正是最需要它的时候）永远等不到。
- **验证**：`VaultLockManagerImplNeverTest` 新增 `自动锁定后清空剪贴板`；
  security 包 38 例全绿。

---

