# 通行密钥 UV 豁免（`isUserPreVerified`）—— 决策定稿（2026-09-18）

> **这份文档把「解锁时的生物识别能否充当 WebAuthn 的 User Verification」定死。**
> 起因：用户报「通行密钥登录指纹弹了两次」，经代码排查定位到
> `isUserPreVerified` 机制**四处断链、没有任何一处读它**（见
> `.ai/issues/03-通行密钥与凭据提供商.md` #50.1、`SESSION-2026-09-18.md` §11）。
>
> **交接状态**：安全边界已定稿 ✅ / 代码已实施 ✅（静态门禁全绿）/ 真机验收 ⏳
>
> 实施落点见 §4 的「实施行」列；变更文件 6 个（详见 §4.3）。
>
> ⚠️ **本决策会放宽一处安全检查的触发条件**。动手前**必须逐条读完 §3 的不变量**，
> 任何一条不满足就**不要实施**。这不是性能优化，是安全边界的移动。

---

## §0 一句话总结

> **允许「同一次凭据流程内刚完成的设备验证」充当 WebAuthn UV，从而省掉第二次弹窗。**
> 判定条件是**流程内的、非持久的、一次性消费的**标记；
> `sign()` 的 `isUserVerified` 断言**保留不动**，只允许被合法地提前置真。

---

## §1 问题陈述（为什么会有第二次弹窗）

用户场景（设备：荣耀 BKQ-AN00 / Android 17）：

```
① 库锁定 → CP 走 authenticationActions → AutofillActivity 弹指纹（解锁）
② 解锁成功 → RESULT_OK + 候选列表回灌
③ 用户点通行密钥候选 → PasskeyGetActivity
④ verifyUser() 无条件弹指纹        ← 冗余！第 ① 步刚验证过
⑤ sign() 强断言 isUserVerified
```

第 ④ 步的验证与第 ① 步是**同一次用户意图、同一个流程**。

**机制本来就在，只是没接上**：`CredentialProviderIntentUtils.isUserPreVerified()`
的 KDoc 原文写着「第二条来源是本 App 自己的解锁流程（用户在解锁时刚做过生物识别，
**无需再弹一次**）」。但：

| 环节 | 状态 |
|---|---|
| `CredentialProviderEntryBuilder.publicKeyEntry()` 的候选 Intent | ❌ 没带 `EXTRA_KEY_UV_PERFORMED_DURING_UNLOCK` |
| `PasskeyGetActivity.verifyUser()` | ❌ 0 处引用该标记 |
| `PasskeyCreateActivity.verifyUser()` | ❌ 0 处引用该标记 |
| `AutofillActivity` 解锁成功后 | ❌ 没有任何地方把它置为 `true` |

---

## §2 ★ 规范依据：WebAuthn UV 到底要求什么

**W3C WebAuthn Level 2/3** 对 UV 的要求是：

> UV 标志位表示「认证器对用户做了用户验证（user verification）」。
> 它约束的是**本次断言的签名之前，是否已确认用户在场并被验证**——
> **不约束"用哪个 API 完成验证"**。

⇒ 因此以下推论成立：

1. **平台 `BiometricPrompt` 的成功回调，在语义上属于 user verification**
   （设备用户在场 + 生物特征匹配）；
2. **同一次流程内、紧邻的验证**，只要未被撤销（会话未锁、流程未中断），
   可以作为本次断言的 UV 依据 —— **Bitwarden 官方客户端就是这么做的**
   （解锁后置 `isUserPreVerified = true` ⇒ 断言不再弹窗）；
3. **但不满足上述条件时，必须重新验证**（见 §3）。

⚠️ 这三条是本决策的**全部**授权范围。任何超出（例如"今天验证过就一直算"）
都**不被允许**。

---

## §3 ★★ 安全不变量（**逐条必须满足，否则不要实施**）

实施代码时，下面每一条都要能在代码里指出**对应的实现行**。缺任何一条 ⇒ 停止。

| # | 不变量 | 为什么 | 怎么满足 |
|---|---|---|---|
| **I1** | 标记必须是**流程内的、一次性的**，消费后立即失效 | 防止"上一次会话的解锁"顶用 ⇒ 等于长期免验证 | 放在 `CredentialProviderRequestManager`（它已有 `clear()`）；**读取后立即清除**，不做持久化 |
| **I2** | 标记只能由**真实的用户验证成功**置位 | 防止"没验证也能签发" | 唯一写入点是 `BiometricPrompt.AuthenticationCallback.onAuthenticationSuccess` 的**成功分支**；**不得**由"库已解锁"这类状态反推 |
| **I3** | 标记必须**绑定到同一流程**（同一次凭据请求） | 防止跨请求复用 | 置位与消费都在同一次 `CredentialProviderRequestManager` 生命周期内；`clear()` 在 `finish()` 时调用 |
| **I4** | **`sign()` 的 `isUserVerified` 断言保留**，不得删、不得放宽 | 它是最后一道防线 | 只把"置真"的**来源**合法化，不改断言本身 |
| **I5** | 库若在签名前**重新上锁**，标记必须失效 | 解锁是"拿到私钥"的前提，锁了就得重来 | 消费标记时**同时校验库仍解锁**（`isVaultUnlocked`）；不满足则回落正常弹窗 |
| **I6** | 用户**主动取消**验证 ⇒ 标记不得置位 | 取消不是验证成功 | `onAuthenticationError` / `onAuthenticationFailed` 分支**不置位** |
| **I7** | 不得为省一次弹窗而降低 `allowedAuthenticators` 强度 | UV 强度不能降 | 维持 `BIOMETRIC_STRONG or DEVICE_CREDENTIAL`（API 30+）不变 |

### §3.1 反例（**明确禁止的做法**）

| ❌ 禁止 | 为什么 |
|---|---|
| 把标记做成 `DataStore` / SharedPreferences 持久化 | 违反 I1 —— 会退化成"验证过一次就永远免验证" |
| 用「库当前已解锁」**反推** UV 已完成 | 违反 I2 —— 解锁可能来自主密码、来自快速解锁，且可能是很久以前 |
| 在 `verifyUser()` 里直接把 `isUserVerified = true` 再调 `sign()` | 违反 I4 的精神 —— 那不是"传递验证结论"，是"绕过验证" |
| 删掉 `sign()` 里的断言"因为它总是 true" | 违反 I4 —— 断言是防御性设计，删了就再没有兜底 |
| 无条件跳过（不看标记、不看库状态） | 违反 I5 |

---

## §4 实施设计（四处改动，缺一不可）

| # | 位置 | 改动 | 对应不变量 |
|---|---|---|---|
| 1 | `AutofillActivity.unlockAllAndFinish()` 解锁成功分支 | 若 `credentialFlow` 且解封成功 ⇒ 置 `CredentialProviderRequestManager` 的 UV 标记 | I2 / I6 |
| 2 | `CredentialProviderEntryBuilder.publicKeyEntry()`（及 `passwordEntry`） | 建候选 Intent 时带上该标记 | I3 |
| 3 | `PasskeyGetActivity` | `onCreate` 读标记 ⇒ 若为真**且库仍解锁** ⇒ 跳过弹窗、`isUserVerified = true`、直接 `sign()`；**读后立即清除** | I1 / I5 |
| 4 | `PasskeyCreateActivity` | 同上（注册侧） | I1 / I5 |

### §4.3 实施落点（2026-09-18 已落地）

| # | 文件 | 实施内容 |
|---|---|---|
| 1 | `passkey/CredentialProviderRequestManager.kt` | 新增 `markUserPreVerified()`（唯一写入点）与 `consumeUserPreVerified()`（读后即清零）；`isUserPreVerified` 保持 `private set`，KDoc 明示「读取请走 consume」 |
| 2 | `passkey/CredentialProviderIntentUtils.kt` | 新增 `Intent.consumeUserPreVerified()`：系统 `biometricPromptResult` 优先，否则回落单例并消费 |
| 3 | `passkey/CredentialProviderEntryBuilder.kt` | 新增私有扩展 `Intent.markUvPerformedIfNeeded()`，**仅在确为真时**加 extra；`passwordEntry()` 与 `publicKeyEntry()` 两处候选 Intent 均挂上 |
| 4 | `autofill/AutofillActivity.kt` | `credentialFlow && result.first == UnlockResult.Success` 分支内 `markUserPreVerified()`；失败分支明确**不置位** |
| 5 | `passkey/PasskeyGetActivity.kt` | 新增字段 `preVerifiedByUnlock`；`onCreate` 在库态校验后 `consumeUserPreVerified()`；新增 `onConfirmClicked()` —— 预验证为真则置 `isUserVerified=true` 直接 `sign()`，否则 `verifyUser()`。**确认卡片保留** |
| 6 | `passkey/PasskeyCreateActivity.kt` | 与 5 同构（注册侧），预验证为真则直接 `createPasskey()` |

> **两个刻意的设计取舍**（与 §4 表格的无损实现有偏差，故单列）：
>
> 1. **保留确认卡片**。原表写「跳过弹窗、直接 sign()」，实施时改为「**只跳过生物识别**、
>    卡片照旧」。理由：卡片是用户对「用**谁**的凭据登录**哪个站点**」的**授权**，
>    与「设备用户在场」是两件事；解锁那次验证只解决后者。省掉卡片会变成「点候选即签名」。
> 2. **`passwordEntry()` 也挂标记**。原表只列 `publicKeyEntry()`。实施时两处都挂：
>    标记只影响读取侧的判定，多挂不改变密码候选的行为（`PasskeyGetActivity` 只服务通行密钥），
>    但为将来密码侧复用留了正确语义。

### §4.1 关键实现细节

- **标记载体**：复用 `CredentialProviderRequestManager` 的既有 `isUserPreVerified` 字段
  （它已有 `set…(preVerified)` 与 `clear()`），**但新增一个"消费"语义**：
  `consumeUserPreVerified(): Boolean`（读完即置 false）。**不要**新增持久化载体。
- **候选 Intent 的 extra key**：复用既有的 `EXTRA_KEY_UV_PERFORMED_DURING_UNLOCK`
  （`CredentialProviderIntentUtils` 里已有常量与 `isUserPreVerified()` 读取函数）。
- **为什么要走 Intent 而不只靠单例**：候选点击是**系统经 PendingIntent 拉起**的，
  中间可能经过进程重建；单例状态此时可能已丢。Intent extra 是随 PendingIntent 走的，
  更可靠。**两者都带**（单例作快路径，extra 作兜底）。
- **库仍解锁的校验**：用**仓储口径** `vaultRepository.isVaultUnlocked(vaultId)`
  （与 `PasskeyGetActivity` 既有判据同源，含 KDBX 会话）。

### §4.2 与既有断言的关系（**不要改**）

`PasskeyGetActivity.sign()` 里这段**保持原样**：

```kotlin
// 照抄 Bitwarden 的调用契约：签名只在「设备验证已完成」之后发生
if (!isUserVerified) {
    AutofillLogger.d("PK aborted: sign() called while not user-verified")
    fail(GetCredentialUnknownException("User not verified"))
    return
}
```

我们只是新增一条**合法的置真路径**，断言本身是兜底，**留着**。

---

## §5 验收清单

### §5.1 静态可验（本次已核）

- [x] `sign()` 的断言**仍在**（`PasskeyGetActivity.kt:425` `if (!isUserVerified)` 未被删/放宽）
- [x] 写入点**唯一**：全仓库 `markUserPreVerified()` 仅 `AutofillActivity.kt:507` 一处调用
- [x] 读取侧**全部走消费语义**：`PasskeyGetActivity` / `PasskeyCreateActivity` 均调 `consumeUserPreVerified()`，无任何直接读 `isUserPreVerified` 后又用它放行的路径
- [x] 标记载体**非持久化**：仅在 `CredentialProviderRequestManager` 内存单例，无 DataStore/SharedPreferences
- [x] `allowedAuthenticators` 强度**未降**（I7）
- [x] 四道门禁全绿：四个 `.ai/tools` 脚本（仅 3 处已知跨模块误报）+ `detekt` + `compileFullDebugKotlin` + 单测

### §5.2 真机待验收（**必须在设备上逐条走**）

- [ ] 库锁定 + 通行密钥登录：**只弹一次**指纹（点候选后不再弹）
- [ ] 库**已解锁** + 通行密钥登录：正常弹一次（UV），签名成功
- [ ] 用户在该次指纹上**取消** ⇒ 必须**回落弹窗**（不能静默放过）—— 验 I6
- [ ] 解锁后**先手动锁库**再点候选 ⇒ 必须重新弹窗 —— 验 I5
- [ ] 连续两次通行密钥登录（不同站点）⇒ **第二次仍要弹窗**（标记已消费）—— 验 I1 / I3
- [ ] 注册侧（`PasskeyCreateActivity`）同上
- [ ] 日志可见 `preVerified=true/false` 与实际弹窗行为**一致**
- [ ] 确认卡片仍在（且卡片上的站点/账号信息正确）

---

## §6 未决 / 风险

1. **真机验收未做**：本决策与实施均在无真机条件下完成，§5 清单**必须**在设备上逐条走。
2. **与 `setBiometricPromptData` 的关系**：那条路（系统在候选列表内验证）能给
   **更强的**"同流程"保证，但 Vaultix 因 **无可用 cipher + 魔改 ROM 风险**不挂
   （见 `CredentialProviderEntryBuilder.kt` 末尾注释）。本决策是**在那条路不可用时的替代**。
3. **KDBX 库**：其"解锁"是整库明文进内存，与 Bitwarden 的对称密钥模型不同，
   但 UV 语义一致，不影响本决策。
4. **若将来支持 `setBiometricPromptData`**：应优先走系统那条（`biometricPromptResult`
   由系统直接给出，无需自己维护标记），本决策的标记可退化为兜底。

---

## §7 一句话交接

> **标记是"流程内一次性"的，不是"验证过就免税"。**
> 读它的地方必须**同时校验库仍解锁**，且**读完即清**。
> `sign()` 的断言是兜底，**永远留着**。
