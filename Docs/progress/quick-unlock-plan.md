# 专项计划 · 升级后快速解锁失效（#88）

> 建档：2026-09-13（第五十二轮）· 坑的原始记录：`.ai/issues/04-锁与解锁.md` **#88**
> 配套：性能专项 [`perf-plan.md`](./perf-plan.md)（两者都需要 debug 包 + ADB，建议同一会话连着做）
> 本文档是**执行计划**：每条都给出「验证步骤 → 命令 → 判据 → 修法方向」，可照做。

---

## 1. 现象与已排除项（**都是实测，不是推断**）

**现象**（用户报，2026-09-13）：
> CI release `0.2.0` 覆盖本地 debug 包后，「生物验证好像失效了」；
> 重新登录 → 删掉快速解锁 → 重新开启，才能继续指纹解锁。

**已排除的三项**：

| 候选原因 | 实测证据 | 结论 |
|---|---|---|
| 卸载重装 ⇒ 数据被清 | `firstInstallTime=2026-09-12 13:46:46`、`lastUpdateTime=2026-09-13 21:39:03` | ❌ 排除了，是**真·原地更新** |
| 签名不一致 ⇒ 被迫重装 | CI release APK 与手机上已安装 APK，`apksigner verify --print-certs` 的 **SHA-256 完全相同**（`7e6c8c07…`） | ❌ 排除了 |
| release 包（R8）把功能压坏 | 重新启用后**指纹立刻可用** | ❌ 排除了，代码路径正常 |

⇒ **剩下唯一解释：那对「包裹密钥 / KEK」在原地更新过程中变成了不可解密。**
**影响面：每一个升级的用户都会遇到**（不阻断发布 —— 可两下操作自愈、数据不丢）。

### 1.1 现场复现（2026-09-13 22:03，release `0.2.0` 上 —— **不施任何操作就看到了**）

在真机上跑性能测量（反复 `force-stop` + 冷启动）时顺带发现：**解锁页的指纹图标已消失**。

- **截图证据**：`.workbuddy/artifacts/lock_now.png` —— 只有 头像「B」/ `Bitwarden` /
  `valkjin@outlook.com` / 主密码框 / **灰掉的**「解锁」按钮；**密码框与解锁按钮之间没有那个
  64dp 指纹图标**（第五十一轮刚交付的）。
- **系统侧无异常**（`dumpsys fingerprint`）：指纹**已录 2 枚**（`count: 2`），
  且**加密认证成功过**（`acceptCrypto: 118`、`authEndedFor(… wasSuccessful=true)`）
  ⇒ **不是手机的问题、不是没录指纹**。
- **系统日志侧无异常**（`logcat -c` 后冷启动）：**没有任何** keystore /
  `KeyPermanentlyInvalidated` / `AEADBadTag` / `UnrecoverableKey` 记录
  ⇒ **不是"解密时抛异常"**。

**⇒ 由代码可推的唯一判定式**（`data/repository/.../VaultRepositoryImpl.kt` 第 371~378 行）：

```kotlin
localUnlockAvailable = isLocalUnlockEnabled(vaultId) && localUnlockKeyStore.keyAvailable
```

图标隐藏 ⇒ 必为二者之一为假；而**两者都不产生异常日志**，与实测完全吻合：

| 分支 | 含义 | 现场怎么验 |
|---|---|---|
| `isLocalUnlockEnabled == false` | 偏好开关被关。注意 **`clearBrokenLocalUnlock`（第 452 行）会同时清 payload + 关开关** | 用户若记得"开过"，即说明是**走过「不可恢复」分支被静默清掉**的 |
| `keyAvailable == false` | KEK 别名不存在 / 已永久失效。`containsAlias()` 返回 false **不抛异常** | 设置页「快速解锁」显示为**关**（Settings 读的就是 `localUnlockAvailable`，见 `SettingsViewModel:85`）|

⚠️ 两条都指向同一个结论：当前是**静默消失**，用户只能自己摸索 —— 正是 §6 要修的那处 UX。

⚠️ 且它**阻塞性能专项**：库解不开就测不了「已解锁进列表」的 PSS 与分动作 Janky
（见 `perf-plan.md` §0.5 第 3 条：冷启动/内存都必须与比较对象处于同一解锁状态）。

### 1.2 第二次现场（2026-09-13 22:21，**同一次会话内的前后对比**）

用户原话：「**我清掉后台之后，页面就没有指纹解锁了**」。实测抓到的两屏，构成了最干净的对照：

| 时间 | 状态 | 启动 | 指纹入口 |
|---|---|---|---|
| 22:19 | 进程冷启动 → **直接进密码列表**（库是解锁态，`dumpsys` 无新认证） | `TotalTime 205ms` / `LaunchState COLD` | 不适用 |
| 22:21 | **解锁页**（库被锁上，疑似超时） | — | ❌ **不存在** |

22:21 的解锁页实测（`uiautomator dump`，证据 `artifacts/ui_unlock_before_pw.xml` + `screen_cur.png`）：

```
可见文案：B | Bitwarden | valkjin@outlook.com | 主密码 | 解锁
content-desc：显示密码
指纹图标（contentDescription="使用生物识别 / 设备 PIN 解锁"）：0 个
```

⇒ 按第五十一轮的设计，这一页应是「密码框 → 44dp 指纹图标 → 解锁按钮」三件；**现在中间那件整件不在**。
⇒ 且 `localUnlockAvailable = enabled && keyAvailable` 为假的**两个分支都不会抛异常**
（见 §1.1 判定表），与"系统日志一条都没有"完全一致。

⚠️ **这比"指纹弹得慢"严重一档**：不是显示延迟，而是**被判定为不可用**（图标不渲染）。

### 1.3 手操作 + 录屏复现（2026-09-13 22:25，**用户手操作、AI 录屏**，证据 `artifacts/rec_manual.mp4`）

用户原话：「我手动点击的是不是和adb调用的不一样？」——**问得对，且实测确实不一样**（见 §1.5）。
于是改由**用户按自己的习惯操作**、AI 只负责录屏（`screenrecord` 脱离终端运行），逐帧回放：

| 时刻 | 画面 |
|---|---|
| t≈20s | 最近任务里**清后台**（"清理加速"页） |
| t≈24~36s | 桌面 |
| **t≈40s** | **从桌面点图标打开 Vaultix → 解锁页** |
| t≈40~80s | 一直是该解锁页，**指纹入口始终不存在** |

关键帧 `artifacts/f60.png`：`B` → `Bitwarden` → `valkjin@outlook.com` → `主密码` 框 →
**一段空白** → `解锁` 按钮。

✅ **该空白的高度与 44dp 图标一档相当 ⇒ `Spacer` 占位约定是生效的，
"没出现"的是 `Icon` 本身**（即 `visible == false`）。
⛔ **勘误**：本节早先写过"布局相对早先 dump 上移 518px ⇒ 占位可能没生效" —— **推论错误，撤回**。
（两次 dump 的 y 值差 518 是**页面状态不同**造成的，不能作为"占位失效"的证据。）

### 1.4 ★ 根因（高置信假设，2026-09-13 22:30）：**`UnlockViewModel` 的 `vaultId` 竞态**，
**不是密钥问题**

**推翻此前所有"密钥"方向的推理的判据**（用户原话：「**像这种情况又必须清掉后台打开，才能看到指纹解锁**」）：
`keyAvailable` 只有 `MISSING` / `INVALIDATED`（钥匙真没了 / 被永久失效）时才为 false
（`LocalUnlockKeyStore.kt:115-119`）—— **这两种都救不回来，重启进程不可能恢复**。
⇒ 「重开就有」只能是**UI 状态被固化成错值**。

**机制**（`app/.../ui/unlock/UnlockViewModel.kt`）：

```kotlin
var vaultId: String = savedStateHandle.get<String>(ARG_VAULT_ID).orEmpty()   // 根导航进入 = ""（:112）
val localUnlockAvailable: Boolean = false                                    // state 初值（:71）

// 协程 A（:140 先启动）：异步补 vaultId
viewModelScope.launch { observeVaults().collect { vaults -> if (vaultId.isBlank()) vaultId = … } }

// 协程 C（:169）：读 var vaultId
viewModelScope.launch {
    observeVaults()
        .map { vaults -> vaults.firstOrNull { it.id == vaultId }?.id.orEmpty() }   // ← 竞态点
        .distinctUntilChanged()
        .collectLatest { id ->
            if (id.isBlank()) return@collectLatest        // ← 空则跳过
            localUnlockAvailable(id).collect { available ->                      // ←（:178）
                _state.update { it.copy(localUnlockAvailable = available) }       // ← 全项目唯一写入点
            }
        }
}
```

**A 与 C 并发收集同一 `observeVaults()`（Room 冷流 ⇒ 各自一次查询）**。C 的
`.map` 若在该次发射中**先于** A 写入 `vaultId` 求值，则得到空串 →
`return@collectLatest` 跳过 → 而 **`localUnlockAvailable` 的唯一写入点是 `:179`**，
Room 该表无变更也不会再发射 ⇒ **状态永久停在 `false`**。

**⇒ 与全部实测吻合**：

| 现象 | 解释 |
|---|---|
| 解锁页**没有指纹图标** | `state.localUnlockAvailable` 恒 false（`:206` 的 `quickUnlockVisible`） |
| **自动弹指纹也不触发** | `eligible = (viewLocked \|\| localUnlockAvailable) && …`，同样恒 false |
| **不 100% 出现 / 时好时坏** | 纯竞态（两个查询的返回顺序） |
| **「手动返回才出现」** | 重建 ViewModel = 重掷一次 |
| **「清掉后台重开才看到」** | 同上（新进程 = 新 ViewModel） |
| **系统 keystore 日志一条都没有** | 根本没读密钥，压根不是密钥问题 |
| §1.3 录像里"清后台重开**仍然**没有" | 该次骰子掷到了坏的一面（竞态特性） |

**修法方向（形状）**：让"选库"与"订阅可用性"**用同一次发射**，消灭跨协程读 `var` 的竞态 ——
例如把选库逻辑内联进同一条流，或用 `MutableStateFlow<String>`（由 A 赋值）驱动
`.filter { it.isNotBlank() }.flatMapLatest { id -> localUnlockAvailable(id) }`，
使 `vaultId` 补上时**必然**产生一次新发射。

**验证方式（不需要真机）**：给 `UnlockViewModel` 写单测，用一个"首次发射时 `vaultId` 尚未补上"
的假仓储，断言 `state.localUnlockAvailable` 最终为 `true` —— **当前实现必然失败（红）**，
修好后通过（绿）。这比装 debug 包更快，且能防回归。


### 1.5 「手操作」vs「adb 调用」的实测差异（复现对齐必读）

系统日志里两种启动记录的**原始形式**：

```
adb  am start -n：  START u0 {flg=0x10000000 xflg=0x5 cmp=io.vaultix.vaultix/.MainActivity}
桌面点图标：        START u0 {act=android.intent.action.MAIN cat=[android.intent.category.LAUNCHER]
                              flg=0x10000000 xflg=0x4 cmp=io.vaultix.vaultix/.MainActivity}
```

⇒ 三处差异必须对齐，否则复现的不是同一个东西：
1. **intent**：桌面带 `act=MAIN cat=[LAUNCHER]`，`am start -n` 只给组件名；本机（荣耀）连 `xflg` 都不同（0x5 vs 0x4）。
2. **触摸语义**：`input tap` 是 0 时长合成点击，能触发 `onClick`，但复现不了长按/拖拽阈值与跟手。
3. **返回**：`input keyevent 4` 无预测式返回动画（要手势才触发系统动画）。
⇒ ⚠️ 但**本次 A/B 实测**：两种 intent（带/不带 `MAIN+LAUNCHER`）**都落在同一个页面**，
说明 intent 差异**不是**本现象主因；主因仍指向"库的锁定状态/时序"。

**下一步**：本现象**不再需要换 debug 包**去查"开关/KEK"了（§1.4 已把方向定到竞态）。
改为：① 用单测复现该竞态（红）→ ② 修 `UnlockViewModel` 订阅形状 → ③ 单测转绿 →
④ 再上真机按 §7 验收（重点复看"锁库后解锁页是否稳定出现指纹入口"）。

### 1.6 ★ 已修复并上真机（2026-09-13 22:45）

**修复前的三条硬证据（这次终于能读私有状态了 —— debug 包 `run-as` 可用）**：

| 证据 | 实测 | 排除了什么 |
|---|---|---|
| `local_unlock_enabled_https://pwd.vv1234.cn` | DataStore `.pb` 偏移 310 后字节 = `12 02 08 01` ⇒ **true** | 排除"用户没开/开关被关" |
| `shared_prefs/vaultix_secure.xml` 键名含 `local_unlock_key::https://pwd.vv1234.cn` | **payload 在** | **排除 `disableLocalUnlock` 与 `clearBrokenLocalUnlock`**（二者都会删掉该 payload） |
| `bw_access::…` / `bw_refresh::…` / `bw_protected_key::…` | 均在 | 确认无需重登 Bitwarden |

⇒ 图标消失只剩两种可能：`keyAvailable == false`，或 **上述 UI 状态竞态**。
⚠️ 而"清掉后台重开就能看到"**必须**用竞态解释（KEK 若 `MISSING`/`INVALIDATED` 则重启救不回来）
⇒ 竞态是运营中的那个原因。**若修完仍看不到指纹，说明还有第二个问题** —— 这是可证伪的判据。

**改动**（`app/.../ui/unlock/UnlockViewModel.kt`）：
新增私有 `resolveTargetVaultId(vaults)`，**从一次发射自己解析目标库**；
订阅可用性那条流改为 `.map { resolveTargetVaultId(it) }.filter { it.isNotBlank() }.distinctUntilChanged()`，
**不再读跨协程共享的 `var vaultId`** ⇒ 竞态从根上消失。
（语义保持不变：路由带参时只认该库、不在列表里则视为"无可解锁目标"；无参进入才自动选库，
且**先看查看层锁**再看"第一个未锁定"。）

**门禁（本地全绿）**：
- 新增 `app/src/test/.../ui/unlock/UnlockViewModelTest.kt`（3 例）：
  **修前** `fingerprintEntryAppearsEvenIfFirstCollectorGetsTheVaultListLate` **FAILED**（红，精确复现竞态）；
  **修后** `BUILD SUCCESSFUL`（绿）。另两例为反向门禁（正常顺序 / 未启用时必须保持隐藏）。
- 全模块 `detekt` ✓ → `:app:compileFullDebugKotlin` ✓ → `:app:assembleFullDebug` ✓ →
  `:app:testFullDebugUnitTest` 全量 ✓。

**真机包**：`app-full-debug.apk` → `zipalign -p -f 4` → 发布密钥重签 →
证书 SHA-256 **`7e6c8c07…`（与现装包完全一致）** → `adb install -r -d`。
**`firstInstallTime` 未变**（2026-09-12 13:46:46）⇒ 确认覆盖安装、**数据未丢**。
⚠️ 副作用：变体由 release 换成 **debug**（`versionName=0.1.0`），换回 release 可从 GitHub 重装。

**待真机验收（用户操作）**：锁定密码库 → 解锁页**必须稳定出现指纹入口**（可重复锁/解锁多次）。
若仍不出现 ⇒ 转向 `keyAvailable`（KEK 别名）那条线。

### 1.7 真机首验（2026-09-13 22:48）—— **次要可能已被排除：KEK 是健康的**

用户：「我指纹解锁了」。取证（`adb`）三路对齐：

| 证据 | 实测 | 说明 |
|---|---|---|
| `dumpsys fingerprint` → `acceptCrypto` | **118（22:04）→ 135（22:48）** | 增长的是**密钥绑定**的认证（`acceptCrypto`），不是普通屏锁认证 |
| `logcat`（`pid=2252`，即 Vaultix） | `jit_compiled: java.security.KeyStore.getKey(...)` | 应用**真的调了 `getKey`**（`kekStatus`/`loadKey` 路径） |
| `keystore2` | `add_auth_token(challenge=…, authType=0x2)` → `createOperation()` → `update()` → **`finish()`**（带 `HardwareAuthToken`） | **一次受硬件认证令牌保护的密码学操作成功完成** ⇒ `unwrap` 成功 |

⇒ **`keyAvailable` 为真、payload 可解、指纹解锁走通完整加密链路**。
⇒ 至此 §1.4 预留的"次要可能 `keyAvailable == false`（KEK 别名丢失/失效）"**被实测排除**；
唯一剩下的解释就是那个 `vaultId` 竞态 —— 与单测红→绿互证。

⏳ **仍待验的是"稳定性"**（原症状是"时有时无"）：
需要拿到**真锁**态（`主密码` 与 `使用生物识别` 同时出现的那一页）。注意判别：
- **真锁页** = 有 `主密码` **且**有指纹入口（44dp 图标，无文字）；
- **查看层锁页** = **无** `主密码`，只有一个带文字"使用生物识别 / 设备 PIN 解锁"的按钮 ——
  这条分支**不依赖** `quickUnlockVisible`，**不能**用来验本条修复。
⚠️ 应用内**没有**"立即锁定"入口（`setting_lock_now` 是未引用的残留字符串），
真锁只能由**超时 / 冷启动**触发 —— 而实测它**与时间相关**
（22:19 冷启动直进列表，22:21 同一会话已变解锁页，仅隔 2 分钟）。

### 1.8 ✅ 真机验收通过（2026-09-13 22:53，用户确认）

用户原话：「我只是看到解锁页，但是没有入手解锁 / **实际上是可以解锁的**」。

四条独立证据构成闭环：

| # | 证据 | 层次 |
|---|---|---|
| 1 | 单测 `fingerprintEntryAppearsEvenIfFirstCollectorGetsTheVaultListLate`：**修前精确红 → 修后绿** | 机制（确定性、可回归） |
| 2 | 真机 dump 到 `window-name="biometrics_dialog"`，文案 `解锁 Vaultix` / `请按压屏内指纹感应区验证指纹` ⇒ **自动弹窗真的发起**（`eligible = (viewLocked \|\| localUnlockAvailable) && …`） | 状态 ⇒ `localUnlockAvailable == true` |
| 3 | `keystore2`：`add_auth_token(authType=0x2)` → `createOperation/update/finish`（带 `HardwareAuthToken`）；`acceptCrypto` 118→135 | 密码 ⇒ **KEK 健康、`unwrap` 成功** |
| 4 | 用户点按指纹后进入列表（并用眼睛看到了指纹入口） | 端到端 |

⇒ **#88 本项结案。** ⚠️ 但**修复目前只在本地**：尚未提交/推送 ⇒ **CI 的 release 包还没有这个修复**。

**⚠️ 本轮踩到的探针坑（别再犯）**：用 `adb shell grep` 在**设备端**过滤 `uiautomator` 的 xml **没有生效**
（`window-name` 与计数全为空 ⇒ 连拍 6 个样本全 0），差点被误读成"页面上没有指纹入口"。
**正确做法：把 dump `pull` 到本地再 grep** —— 本文件其余结论都是这样取的。





## 2. 关键结构（先看清才能查）

两个**独立**的 Keystore 密钥，别混：

| 密钥 | 别名常量所在 | 保护方式 | 用途 |
|---|---|---|---|
| **凭据库密钥** | `SecureCredentialStore.KEY_ALIAS` | Keystore AES-256-GCM，**不要求用户认证** | 加密 `SharedPreferences` 里的**值**（Bitwarden token、`local_unlock_key::<vaultId>` 包裹串） |
| **KEK（快速解锁）** | `LocalUnlockKeyStore.KEY_ALIAS` | `setUserAuthenticationRequired(true)` + 逐次认证 | 包裹账号对称密钥（64B fullKey） |

⇒ **排查的第一个分叉点**：如果**只有快速解锁**坏（Bitwarden token 还在、不用重新登 Bitwarden）⇒ 问题在 **KEK**；
如果**连 token 也读不出来**（要重新登 Bitwarden）⇒ 问题在**凭据库密钥**（影响面更大）。
**⚠️ 用户原话「我重新登录」有歧义 —— 必须问清是"输入主密码解锁"还是"重新登录 Bitwarden 账号"。这一条直接决定往哪边查。**

## 3. 复现（确定性，当前就能做）

⚠️ **勘误（2026-09-13 22:21 实测）**：本行原文写「release 0.2.0 已装、快速解锁已重新启用且可用」——
**已不成立**：现场抓到解锁页**没有指纹入口**（见 §1.2）。当前状态 = **快速解锁不可用**，
这正是要查的问题本身，不是实验台的既有条件。
⇒ 现在可直接当实验台用的是：**能稳定拿到"解锁页无指纹入口"这个现象**（库锁上即可）。

```bash
# 1) 记录"好"的状态（先取证，别急着装）
adb shell dumpsys package io.vaultix.vaultix | grep -E "firstInstallTime|lastUpdateTime"
#    记下当时的 kekStatus 与 keyAvailable（见 §4 埋点）

# 2) 用本地 debug 包原地覆盖（**同一把密钥 ⇒ 数据不丢**）
BT=/c/AndroidSDK/build-tools/36.0.0; ADB=/c/AndroidSDK/platform-tools/adb.exe
cp app/build/outputs/apk/full/debug/app-full-debug.apk raw.apk
"$BT/zipalign.exe" -p -f 4 raw.apk aligned.apk
"$BT/apksigner.bat" sign --ks D:/vaultix-release.jks --ks-key-alias vaultix \
  --ks-pass pass:<store> --key-pass pass:<store> --out signed.apk aligned.apk
$ADB install -r -d signed.apk

# 3) 立刻冷启动并抓日志（关键窗口）
$ADB shell am force-stop io.vaultix.vaultix
$ADB logcat -c
$ADB logcat -s VaultixUnlock:V LocalUnlockKeyStore:V AndroidRuntime:E > /tmp/unlock.log &
$ADB shell am start -n io.vaultix.vaultix/.MainActivity
```

**判据**：若快速解锁**再次失效** ⇒ **可稳定复现**，按 §5 对照定位；
若**没失效** ⇒ 说明触发条件另有其它（例如当时跨的是"一次性密钥签的 preview 包"），
需要在用户真机历史上回溯当时的安装来源（`dumpsys package` 的 `installerPackageName` 是 `com.microsoft.emmx`，说明是从 Edge 浏览器下载安装的 —— **这条线索要追**）。

## 4. 需要加的埋点（当前这条链路日志不足）

在 `LocalUnlockKeyStore` / `VaultRepositoryImpl.completeLocalUnlock` 加**常驻**日志（tag 建议 `VaultixUnlock`）：

| 打点位置 | 记录内容 | 为什么需要 |
|---|---|---|
| App 启动（或解锁页 init） | `kekStatus`（三态）+ `keyAvailable` | 判定"是探测判废了，还是解密真失败" |
| `localUnlockAvailable(vaultId)` 求值处 | `enabled`（偏好开关）+ `keyAvailable` + payload 是否存在 | 三者任一为假都会让指纹入口消失 |
| `completeLocalUnlock` 入口 | payload 是否拿到（`wrappedPayload != null`） | 区分"凭据库解不开"与"KEK 解不开" |
| `unwrap` 失败处 | **异常类型全链**（`AEADBadTagException` / `UnrecoverableKeyException` / `KeyPermanentlyInvalidatedException`） | 这三种对应三条完全不同的修法 |
| `clearBrokenLocalUnlock` 被调用时 | 明确一行"清了谁的注册、为什么" | 确认到底是谁清掉的（用户手动 / App 自动） |

## 5. 判据表（观测 → 根因 → 修法方向）

| 观测 | 根因 | 修法方向 |
|---|---|---|
| `kekStatus == MISSING`（别名不存在） | 更新后 Keystore 条目被清（OEM 行为？） | ① 让 `keyAvailable=false` 时给出**显式提示**而非静默隐藏入口；② 排查 OEM 是否在 update 时重建别名 |
| `kekStatus == INVALIDATED`（`KeyPermanentlyInvalidatedException`） | KEK 被永久失效 | 同上 + 确认触发条件（生物识别变更？更新？） |
| `cipher init`/`unwrap` 抛 `AEADBadTagException` | payload 与 KEK 不匹配（**被替换/不同源**） | 检查写入时机：是否在"旧 KEK 已失效 + 新 KEK 已建"的窗口里写了 payload |
| payload 读不出来（`wrappedPayload()==null`） | 凭据库密钥失效 ⇒ **影响面更大**（token 也会丢） | 与"用户是否要重新登 Bitwarden"交叉验证；查 `SecureCredentialStore` 的 KEY_ALIAS 生命周期 |
| 日志显示 `clearBrokenLocalUnlock` 被调用 | App 走了"不可恢复 ⇒ 清理注册"分支（**行为本身是对的**） | 保留行为，但**要在 UI 上告知用户**"快速解锁已失效，请重新启用"，别让用户自己发现 |

## 6. 无论根因如何，都该做的一处 UX 改进（低风险、收益确定）

现在的问题之一是**静默**：指纹入口消失后，用户只能自己摸索"删掉再开启"。

⇒ **在设置页与解锁页显式给出状态**：

- 偏好开关 = 开、但 `keyAvailable == false` ⇒ 显示一行：
  **「快速解锁不可用（密钥已被系统回收），请关闭后重新开启以恢复」** + 一个"重新启用"按钮；
- 解锁页同理给出一行提示，而不是只留主密码表单。

这条**不依赖**根因定位，可以先行落地；也是"用户不必再猜"的最小闭环。

## 7. 验收标准

1. debug ⇄ release **原地互相覆盖**后，快速解锁**仍然可用**（或至少：失效时 UI 明确告知 + 一键恢复）；
2. 冷启动日志能一眼看出 `kekStatus` / payload / 异常三者的状态（不依赖复现用户现场）；
3. 不破坏既有回归：真锁/查看锁两条路径、`LocalUnlockFailure` 的"不可恢复 ⇒ 清理"语义、
   以及 #85 的"探测宽、失败判定严"边界。

## 8. 协作与前提

- **必须用 debug 包**：release 包 `run-as` 被拒（`run-as: package not debuggable`），
  读应用数据（`shared_prefs` / `databases`）只能在 debug 包上做；两者同签一把密钥，可原地互装、数据不丢。
- **一次只改一处、改完立刻同指标复测**（避免多变量叠加导致无法归因）。
- 相关代码：`core/datastore/LocalUnlockKeyStore.kt`、`core/datastore/SecureCredentialStore.kt`、
  `core/datastore/VaultixPreferences.kt`（`localUnlockEnabled`）、
  `data/repository/VaultRepositoryImpl.kt`（`localUnlockAvailable` / `completeLocalUnlock` /
  `clearBrokenLocalUnlock`）、`data/repository/LocalUnlockFailure.kt`。
