# 03-通行密钥与凭据提供商 · 第 3 篇（#37 ~ #50）

> 主篇索引：[`03-通行密钥与凭据提供商.md`](./03-通行密钥与凭据提供商.md)（含计数规则与重号警告）
> 本篇 4 章 · 16.1KB · 覆盖 #37 ~ #50

## 37. 锁态模型与凭据提供商链路按 Bitwarden 标准重写（2026-09-11，P0，`d6f3409`）

> 用户要求：「参考 bitwarden 的做法…哪怕是一字一句抄代码，也要实现。
> 还有**密码库加锁和解锁逻辑也要按 bitwarden 标准来**吧。更稳定，我这项目当前的
> 密码库加锁解锁逻辑太烂了，不标准」。
>
> 这是第 31 轮「锁态竞态」的**根治版**：第 31 轮是在旧模型上打补丁（`CredentialFlowGuard`
> 时间戳窗口），本轮把模型本身换成 Bitwarden 的。

### 旧模型的三处根本性缺陷（可实证）

1. **计时基准错**：后台只记 `backgroundedAtMs`，前台回头算差值。
   ⇒ 任何影响"前台时刻"的事件（keyguard、进程事件、Activity 互切）都会污染判定。
   Bitwarden 是「后台启动 `delay(timeout)` 的 job；前台 `cancel` 它」——锁不锁完全由
   那个 job 自己决定，前台不做任何判定。
2. **档位模型是裸 `Int` 的口头约定**（负数=从不 / 0=立即 / N=分钟）。
   ⇒ 无法表达 `OnAppRestart`（重启时锁定）；`when` 漏档只会在运行时静默走 else。
3. **autofill 豁免是启发式**：`CredentialFlowGuard` 用 8 秒时间戳窗口近似判断
   「这次前台切换是不是我们自己造成的」。窗口过短 → 通行密钥被误锁；
   过长 → 用户真的切走又回来却不锁。Bitwarden 用 `CheckTimeoutReason.AppCreated(
   firstTimeCreation, createdForAutofill)` 做**结构化**表达。

另有：`VaultRepositoryImpl.lockVault/lockAll` 用 `runBlocking` 阻塞调用线程；
CP Service 在「部分库锁定」时把 `credentialEntries` 与 `authenticationActions` 并存。

### 落地（逐句对齐 Bitwarden 三处）

| 旧 | 新 |
|---|---|
| `AutoLockPolicy`（纯函数判差值）| `VaultTimeout` sealed class（10 档位，core:datastore） |
| `AutoLockController` 自判 + `backgroundedAtMs` | `VaultLockManager.onAppBackgrounded/onAppForegrounded` |
| `CredentialFlowGuard` 时间戳窗口 | `CheckTimeoutReason.AppCreated(..., createdForAutofill)` 结构性豁免 |
| 裸 `Int` 档位偏好 | `VaultTimeout` + 一次性迁移（`fromLegacyMinutes`） |
| `runBlocking` 锁定 | `suspend fun` |
| CP 三处各自判定锁态 | `RootNavViewModel` 集中 + CP 锁定只给 `authenticationActions` |
| 无 trampoline | `CredentialProviderActivity`（`exported=false`，结果原样透传） |

### ★ 最危险的迁移点（务必保留断言）

旧 `auto_lock_minutes = -1` 语义是「**从不**锁定」；
新 `VaultTimeout` 的 `-1`（`OnAppRestart`）语义是「**重启时**锁定」——**正好相反**。

若不迁移，用户明确选择的「永不锁定」会被静默改成「重启即锁」。
已在 `VaultixPreferences.vaultTimeout` 首次读取时按 `fromLegacyMinutes` 转换 + 置迁移标记；
`VaultTimeoutTest` 与独立验证脚本都固化了 `fromLegacyMinutes(-1) == Never` 且
`!= OnAppRestart` 两条断言。

### 验证

1. **真实编译生产类 + 真实类行为断言**：用沙箱内 `kotlin-compiler-embeddable-2.2.21`
   编译 `VaultTimeout.kt`，再对**编译产物**跑 28 条断言 → 全 PASS。
2. 超时决策逻辑 17 条断言 → 全 PASS（含 autofill 豁免、OnAppRestart 不响应后台、
   前台 cancel 后到点不锁）。
3. `core:datastore` 新增 `VaultTimeoutTest`（10 用例）；该模块此前无测试源集配置，
   本轮补上 junit/truth 依赖。
4. 全仓 `.kt` 无超 120 字符行。

### 教训（第 31 轮教训的延续）

第 31 轮我写出的 `CredentialFlowGuard` 用 `Long.MIN_VALUE` 作初值导致整数溢出、
自动锁定被永久抑制 —— 那次是**自造启发式**引入的缺陷。
本轮把启发式整个换成 Bitwarden 的结构化模型，**从根上消除了这类"自创逻辑"的风险面**。
结论：**当上游有成熟实现时，自造"看起来更严谨"的变体是负收益**。

---

## 43. 🔴🔴 通行密钥 `clientDataJSON` 回传空占位符 → GitHub 注册失败（2026-09-12，P0，`aa5fa27`）

> **本条推翻 #35。** #35 判定「浏览器流程应回传空占位符」是**错的**，
> 直接导致真机 **GitHub 注册通行密钥报 `Security key authentication failed`**。

### 症状（用户原话）
「通行密钥这部分还有问题，能够读取到，能够进入登录，在**最终校验**的时候提示错误。」
用户实测 GitHub 注册 → `Two-factor authentication ... Security key authentication failed.`

### 先排除用户猜测
用户猜：「会不会是密码条目和通行密钥没绑在一起？」→ **不是**。
凭据注册与条目绑定路径正常（`fido2Credentials` 落库、`credentialId` / `rpId` 匹配均正常）。
真因是 `clientDataJSON` 占位符。

### 根因：把官方文档的**条件句**读漏了

#35 依据的是官方这句（**逐字，注意加粗部分**）：
> **If you retrieve an origin**, use the `clientDataHash` that's provided directly in
> `CreatePublicKeyCredentialRequest()` or `GetPublicKeyCredentialOption()` instead of
> assembling and hashing clientDataJSON during the signature request. To avoid JSON parsing
> issues, set a placeholder value for `clientDataJSON` in the attestation and assertion response.

- `If you retrieve an origin` = 经 `CallingAppInfo.getOrigin(privilegedAllowlist)` +
  **特权应用名单**拿到 origin 的场景（Google Password Manager 走那条路，
  challenge 校验由**系统侧**完成）。
- Vaultix 的 `CallingAppOrigin` 明确采用「**自证式读取**」、**不走特权名单** ⇒ **不适用占位符**。

**而规范层面 RP 一定会解析明文**（W3C WebAuthn Level 2，逐字）：
> **§7.1** Let JSONtext be the result of running UTF-8 decode on the value of
> `response.clientDataJSON`. Let C ... be the result of running a JSON parser on JSONtext.
> - Verify that the value of `C.type` is the string `webauthn.create`（断言为 `webauthn.get`）
> - Verify that the value of `C.challenge` equals **the base64url encoding of `options.challenge`**
> - Verify that the value of `C.origin` matches the Relying Party's origin.

**§5.8.1.1 `CollectedClientData`** 对字段的定义同样是明文：
`challenge` = **the base64url encoding of options.challenge**、`origin` = the serialization of callerOrigin。

⇒ **回传空字节数组连 JSON 解析都过不了**，`C.challenge` 校验必然失败。

### ✅ 正确解法（两条流程的**唯一**差别 = 签名覆盖哪份哈希）

| 事项 | 浏览器流程 | 原生 App 流程 |
|---|---|---|
| **签名**覆盖 | `authData ‖ clientDataHash`（系统给的） | `authData ‖ sha256(自建 JSON)` |
| **回传** `clientDataJSON` | **自建真实 JSON** | **自建真实 JSON**（同一份） |
| `androidPackageName` | **不写**（浏览器那份没有该字段） | 可写 |

> 回传字段供 RP **读明文校验**；签名哈希保证与浏览器一致 —— **二者互不冲突**。
> #35 误以为"自造 JSON 永远对不上所以只能放占位符"，错在把「逐字节相等」当成了要求。

`androidPackageName` 为什么浏览器流程不能写：浏览器那份 JSON 里没有该字段，
写进去会让 RP 对回传 JSON 重算哈希时与浏览器签名值不符（Bastion 实测：Microsoft 登录失败）。

### 改动（`aa5fa27`，8 个文件）
| 文件 | 改动 |
|---|---|
| `core/common/WebAuthn.kt` | **删除** `BROWSER_FLOW_CLIENT_DATA_PLACEHOLDER`；`buildClientDataJson` 增 `androidPackageName` 参数；新增 `buildCreateClientDataJson` / `buildGetClientDataJson`；KDoc 引规范原文 + 文档条件句 + Bastion 依据 |
| `passkey/PasskeyProviderIntents.kt` | `createIntent` 补 `clientDataHash: ByteArray? = null`（对齐 GET 侧 `getIntent`）+ `putExtra` |
| `passkey/VaultixCredentialProviderService.kt` | `buildCreateResponse` 透传 `request.clientDataHash` |
| `passkey/PasskeyCreateActivity.kt` | 删占位符分支，始终 `buildCreateClientDataJson(..., androidPackageName = null)`；接收 `clientDataHash` 仅作自检日志；**origin 推导顺序改为 `requestJson.origin` → `CallingAppOrigin` → `https://$rpId`**（对齐 Bastion `PasskeyOriginResolver`） |
| `passkey/PasskeyGetActivity.kt` | **回退**占位符，改 `buildGetClientDataJson(..., androidPackageName = null)` |
| `core/common/WebAuthnTest.kt` | 改写 2 个已失效的占位符用例为 `browser flow signs provided hash and returns real clientDataJSON` / `create response always carries real clientDataJSON` |

### 途中两个编译陷阱（易再犯）
1. **`callingRequest` 在 provider 侧不存在。** `PasskeyProviderIntents` / `BeginCreateCredentialRequest`
   **没有** `callingRequest`；`BeginCreatePublicKeyCredentialRequest` **自带** `clientDataHash`。
   （`CreatePublicKeyCredentialRequest` 是**调用方侧**的类，provider 侧拿不到 —— 同 #41 的类型陷阱。）
2. **`AutofillLogger` 需 import** `io.vaultix.vaultix.autofill.AutofillLogger`。

### 验证（`aa5fa27`）
- `:core:common:testDebugUnitTest` → **95 用例 0 失败**（`WebAuthnTest` 12 例，含 3 条回归锁）
- `testFullDebugUnitTest` → **129 用例 0 失败**
- `detekt` 通过；`:app:compileFullDebugKotlin` BUILD SUCCESSFUL
- **CI run `34686274203` = success**（19 步骤全绿，APK 已发布 preview Release）

### 教训（本轮最贵）
1. **官方文档的限定条件不能只读后半句。** `If you retrieve an origin` 决定了整条建议
   是否适用 —— 跳过它会把「特定场景的推荐做法」当成「普适要求」。
2. **与规范冲突时以规范为准。** 规范是 RP 的实现依据，任何平台建议都不能推翻
   「RP 必须解析明文校验 `C.challenge`」这一硬要求。
3. **「所有参考实现都跟我不同」时，先怀疑自己。** 本轮把两家**正确**实现标成反例，
   唯一理由只是"跟我的理解不符" —— 这是危险信号，事实证明是我错了。
4. 用户那句「**应该是有标准的才对**」是对的：**遇到协议层争议，先拉规范原文逐字比对**，
   不要在实现之间靠印象猜测。

---

## 49. `rawId` 形态判别的 **UUID 陷阱**（2026-09-12，P0，`8fd64f5`）

**一句话**：库里 `credentialId` 有两种形态 —— Vaultix 自建 = `base64Url(32字节)`（43 字符）；
**Bitwarden 同步 = UUID 文本**（36 字符）。而 **UUID 文本的字符集 `0-9a-f-` 恰好全落在 base64url
字母表内、长度 36 又是 4 的倍数** ⇒ 「能不能 base64 解码」这个判据会**把它误判成 base64**，
于是**原样发出 GUID 文本** ⇒ RP 解出 27 字节 ≠ 其持有的 16 字节 ⇒ 断言被判未知凭证
（症状：候选能列出、能选、生物识别通过，**最后一步报错**）。

**正确顺序**（对齐 Keyguard `PasskeyCredentialId.encode` / Bastion `toWebAuthnId`）：
`UUID.fromString → 16 字节 → base64Url`（22 字符）**优先**，再 fallback「合法 base64 原样 / 否则 UTF-8 重编码」。

**★ 教训**：凡"启发式判形态"，都要显式考虑**输入恰好满足另一种判据**的情况；
并给形态判别留**可诊断的日志**（本项目新增 `describeStoredIdForm`：`blank/uuid/base64/text`）。
详见 `SESSION-2026-09-12.md` 第四十轮。

---


## 50. 「通行密钥登录指纹弹了两次」—— 两次分属两个请求，其中一次去不掉（2026-09-18，**纯代码排查**）

**现象**（用户原话）：「我用通行密钥登录好像指纹解锁了两次，一次是解锁密码库，
一次是登录验证。好像这种间流程只要一次指纹解锁就够了吧」。
用户当时无电脑 ⇒ 读不到 logcat ⇒ **本节结论全部来自源码**。

**结论：两次弹窗分属两个不同的 Credential Manager 请求**，不是同一个 prompt 弹两遍。

| # | 弹窗 | 由谁弹 | 标题（用户据此分辨） | 能否去掉 |
|---|---|---|---|---|
| ① | 解锁密码库 | `AutofillActivity.maybeBiometricUnlock()` | **「解锁 Vaultix」** | ✅ 才是可优化的一次 |
| ② | 登录验证 | `PasskeyGetActivity.verifyUser()` | **「使用通行密钥登录」** | ❌ 协议要求 |

文案实证（`res/values/strings.xml`）：
```
credential_unlock_title    = 解锁 Vaultix          ← ①（CP 认证动作）
credential_unlock_subtitle = 解锁后选择凭据
passkey_auth_title         = 使用通行密钥登录       ← ②（WebAuthn UV）
passkey_auth_subtitle      = 验证以使用 %1$s 的通行密钥
```
⇒ **下次复现看副标题即可分辨**，不必等 logcat。

### ② 为什么去不掉

`PasskeyGetActivity.kt:358-370` 的 `sign()` 首行是防御性断言：
```kotlin
// 照抄 Bitwarden 的调用契约：签名只在「设备验证已完成」之后发生
if (!isUserVerified) {
    AutofillLogger.d("PK aborted: sign() called while not user-verified")
    fail(GetCredentialUnknownException("User not verified"))
    return
}
```
`isUserVerified` **只能**由 `onAuthenticationSucceeded`（`:315-319`）置真，全文件无第二处写点；
而签名要用的私钥取自已解密的内存仓储（`:219-220` `itemRepository.observeItem(...)` → `cred.keyValue`）
⇒ **解锁库是拿到私钥的前提**。链路顺序是硬约束：

```
解锁库（①） → 内存里有 keyValue → verifyUser()（②） → sign()
```

### ① 为什么会弹

1. `CredentialProviderEntryBuilder.buildGetResponse():133` 查 `observeUnlockedVaultIds()`
2. `:136-163` 空 ⇒ `GET locked → authenticationActions` + 暂存请求 + 只回 `unlockAction()`
3. `unlockAction():221-238` 建 `MODE_UNLOCK` 的 `PendingIntent`
4. `AutofillActivity:199` → `maybeBiometricUnlock()`
5. `:429-441` `outcome is Prompt` ⇒ `BiometricPrompter.authenticate(...)` ← **①在这里**
6. `unlockAllAndFinish():473-505` → `finishCredentialFlowUnlocked()`
7. `finishWithRefreshedEntries():332-352` 重建候选 + `RESULT_OK`

★ ①② 之间隔着**一次系统请求往返**（认证动作 ≠ 候选点击）⇒ 不可能是同一个 prompt 实例。

### 想「只弹一次」的唯一正路

让库在这条链路上**始终不锁** ⇒ 命合 `VaultTimeout.OnAppRestart` 的**结构性豁免**
（`VaultLockManagerImpl:196-204`）。豁免需**两个条件同时成立**：
1. 档位恰为 `OnAppRestart`——更严档位走 `else` 分支，`firstTimeCreation` 时照样锁（`:207-213`）；
2. `CredentialProviderActivity.onCreate:90` 的 `markCreatedForAutofill()` 早于
   `VaultixApplication.onCreate` 的判定（顺序要求写在 `VaultixApplication.kt:59-67` 的 KDoc）。

⚠️ `markCreatedForAutofill` **全项目只有 `CredentialProviderActivity` 一处调用**
（`autofill/` 下没有）—— AutofillService 路径不打这个标记。

### 为什么不能改成「在候选列表里直接验证」（`setBiometricPromptData`）

见 `CredentialProviderEntryBuilder.kt` 末尾的两条理由（**2026-09-18 补全了被截断的第 2 条**）：
魔改 ROM 会丢弃 entry + **无可用 cipher**（库密钥只在内存，拿不出
`setUserAuthenticationRequired` 的 Keystore 密钥作 CryptoObject）。

### 排除的假设（含依据）

| 假设 | 结论 | 依据 |
|---|---|---|
| ① 是「查看层锁」误报 | ❌ | `AutofillActivity:449` 只看 `observeUnlockedVaultIds().isNotEmpty()`；`VaultRepositoryImpl:143-144` 该 Flow **不含 viewLock**（查看锁只加内存标记，`VaultSessionManager:118-124`） |
| 「列得出来点进去说锁定」 | ❌ | `:244` 与列候选**同一真源**（`isVaultUnlocked` / `observeUnlockedVaultIds`，`VaultRepositoryImpl:254-255`） |
| 与 §11.6 的「3 次解锁环」同条 | ⚠️ **待确认次数** | 那条（面板重发 PendingIntent）已在 `e0b14c3` 修掉；若现在仍弹 3 次 ⇒ 是**另一个**未收录的 bug，需 logcat |
| Bastion 是不是也这样 | 参考 | 其 `PasskeyAuthActivity:400` 同样不挂 `BiometricPromptData`，且全项目**没有** `createdForAutofill` / `OnAppRestart` 豁免 ⇒ 它本来就可能多弹 |

### 下一步（需要用户配合，一行信息即可）

复现前看一眼**设置里的自动锁定档位**：
- 「从不加锁」却仍弹 ① ⇒ 豁免没生效，**真 bug**（§11.6 残留），需 logcat；
- 「立即 / 5 分钟」等更严档位 ⇒ **设计内行为**，① 是超时的正常代价。

详见 `SESSION-2026-09-18.md` §9。


---

