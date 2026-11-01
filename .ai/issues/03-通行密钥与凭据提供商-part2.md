# 03-通行密钥与凭据提供商 · 第 2 篇（#34 ~ #36）

> 主篇索引：[`03-通行密钥与凭据提供商.md`](./03-通行密钥与凭据提供商.md)（含计数规则与重号警告）
> 本篇 3 章 · 16.2KB · 覆盖 #34 ~ #36

## 34. 通行密钥「候选列表为空」根因：解密残留填充 + 未 trim + rpId 未归一化（2026-09-11，P0）

**真机症状（用户原话）**：「这样改动后又找不到通行密钥了」→ **列表为空、没有任何候选**。

### 先排除：不是 `8afac3d`（BE/BS）引入的
`git show 8afac3d --name-only` 只有 3 个文件（`PasskeyGetActivity` / `WebAuthn` /
`WebAuthnTest`），**完全没碰 discovery 逻辑**。`VaultixCredentialProviderService`
最后一次变更是更早的 `fec4032`。二者无因果关系（时间上的先后 ≠ 因果）。

### 根因（四项叠加，全部对照 Keyguard / Bastion / Bitwarden 确认）

**① 解密残留填充 —— ⚠️ 经实测后降级为「纵深防御」，**不是**根因**

曾推断 Bitwarden 服务端用 ISO10126 填充、Vaultix 用 `PKCS5Padding` 解密会「不报错也不剥离」。
**用真实 JCE 实测后该推断不成立**：
- 标准 SunJCE 的 `PKCS5Padding` 对 ISO10126 密文**直接抛 `BadPaddingException`**，不会静默泄漏；
- 反向：ISO10126 末字节同样是填充长度，用宽松 PKCS5 解析 **2000/2000 完全正确**；
- 且 `decrypt` 本就以 `PKCS5Padding` + `doFinal` 正确解填充 ⇒ 压根不会有残留。

⇒ **不是根因**。但保留 `VaultixCrypto.removePkcs7PaddingIfStrict` 作为**纵深防御**
（仅严格 PKCS#7 成立时才剥离，避免误伤）；并验证了 `trim()` 对填充字节
`0x01..0x10` 的兜底作用（Java `trim()` 去所有 `<= U+0020` 的字符，16/16 可去除）。

**教训：一次 JCE 实验就能否掉的假设，不要写进根因。**

**② 没有 trim（实际根因，数据映射层）**

服务端多处字段带前导/尾随空白，官方客户端读出来一律 `trim()`；Vaultix 的
`CipherMapper.mapFido2` **一个字段都没 trim**。

**解法**：`mapFido2` 全字段 `.trim()`；`decryptToString` 也加 `trim()`。
⚠️ 特别注意：`counter` / `discoverable` 走 `toLongOrNull` / `toBooleanStrictOrNull`，
不 trim 会**静默回落默认值**（counter=0 / discoverable=true）——默认值本身安全，
但会把真实数据悄悄改写。

**③ rpId 未归一化（匹配层）**

此前是 `cred.rpId.equals(rpId, ignoreCase = true)`，只能处理大小写。
**末尾根点 `.` / Unicode 域名（punycode）** 一律失配。

**解法**：`VaultixCredentialProviderService.normalizeRpId` / `isSameRpId`，
逐条对齐 Bastion `PasskeyRpIdNormalizer`：
`trim` → `trimEnd('.')` → `lowercase(Locale.ROOT)` → `IDN.toASCII(lower, USE_STD3_ASCII_RULES)`，
**两侧都归一化**。

**④ allowCredentials 严格过滤无回退（策略层）**

RP 下发的 `allowCredentials` 是**提示**而非授权门（规范允许空）。用户若在**其它设备 /
其它客户端**注册过该 RP 的 passkey，本地 credentialId 与列表对不上 —— 严格过滤会把
**唯一可用的候选也删掉**。

**解法**：照抄 Bastion `resolvePasskeys` 的回退 ——
严格匹配**为空时回退到「只按 rpId」**并打日志（`allowedMiss` 记录被回退的数量）。

### 三家对照：锁定态处理（已核实，Vaultix 已对齐，不是根因）

| 维度 | Bitwarden | Keyguard | Vaultix |
|---|---|---|---|
| 全库锁定 | 只返回 `authenticationActions`(unlock)，不带 credentialEntries，直接 return | `MasterSession.Empty` → 只返回 unlock 的 `AuthenticationAction` | ✅ 同（`unlocked.isEmpty()` 分支） |
| 通道互斥 | if/return 互斥 | 同 | ✅ 同 |
| 补偿重试 | **无** | **无**（仅 UI 侧 800ms 最小处理时长） | 无（对照后确认**不需要**） |

### 埋点（现场排障唯一手段）
CP 服务每次 resolve 都打：
```
GET resolve rpId=<x> allowed=<n> total=<n> unusable=<n> rpIdMiss=<n> allowedMiss=<n> matched=<n>
GET resolve EMPTY: storedRpIds=<库内实际存的域名>
```
四个计数一出来即可判定卡在哪一级（标签 `VaultixAutofill`，限 debug 构建）。

### 教训
1. **「列表为空」是 discovery 问题**，要在「候选是怎么被筛出来的」这条链上找，
   且必须用逐级计数把「哪一级筛没了」量化出来 —— 而不是靠猜或改无关的注册期字段。
2. **能用真实运行时实验否掉的假设，绝不要写进根因。** 本轮「ISO10126 残留」的推断
   听上去很合理（Bitwarden 服务端确实用过 ISO10126），但一次 20 行 JCE 程序就否掉了它
   （标准 `PKCS5Padding` 会直接抛异常）。**先做实验，再下结论。**

---

## 35. 通行密钥「Authentication failed」根因：浏览器流程回传了自造的 clientDataJSON（2026-09-11，P0，`90d5e6d`）

> ### 🔴🔴 本条结论已于 2026-09-12 **被推翻** —— 见 **#43**。
>
> **本条判定的"正确做法"（浏览器流程回传空占位符）是错的**，真机 **GitHub 注册通行密钥
> 报 `Security key authentication failed`**。错因：官方 `set a placeholder value for
> clientDataJSON` 那句**带前置条件 `If you retrieve an origin`**（仅适用于特权应用名单场景），
> 而 W3C 规范 §7.1/§7.2 要求 RP 解析**明文**校验 `C.challenge` ⇒ 空数组必然失败。
> **正确做法：两条流程都回传自建真实 JSON**，唯一差别是签名覆盖哪份哈希。
> 下面原文保留，仅作记录当时的（错误）推理过程 —— **不要照此实现**。

**真机症状（用户原话）**：「能检测出来通行密钥，但是 Authentication failed，这个问题还是有。」
→ 候选列表已正常（§34 的 discovery 修复生效），但**站点侧验签失败**。

### 先定性：这是断言阶段的验签失败，与 discovery / BE·BS / signCount 都无关

RP 的 login 校验清单只有：`type==="webauthn.get"` / challenge / origin / 按 credentialId
取公钥 / `SHA-256(rpId)` 匹配 rpIdHash / UP（按策略 UV）/ **对 `authData ‖ SHA-256(clientDataJSON)` 验签** /
`new signCount > stored`。列表能出来 ⇒ discovery 已通；本问题在「验签」这一格。

### 根因：provider 与 RP 看到的 `clientDataJSON` 不是同一份

- 系统只把 **32 字节 `clientDataHash`**（浏览器那份 clientDataJSON 的 SHA-256）交给 provider，
  **不给明文**；
- 网页交给 RP 的是**浏览器自己拼的那份** JSON，RP 用它重新哈希后与签名里的哈希比对。

旧实现（`buildClientDataJsonForBrowser`）试图「逐字节复刻浏览器的 clientDataJSON」再回传，
还按哈希在 `[无 crossOrigin, 有 crossOrigin]` 两个候选里**反选** —— 这条路不可能稳定成功：
字段集与字段顺序由浏览器版本决定（Chromium 可能带 `tokenBinding` 等），provider 无从保证命中；
一旦不命中就 `?: candidates.first()` 回退到自造变体 ⇒ RP 哈希必然对不上 ⇒ **验签失败**。

**官方口径**（`developer.android.com/identity/sign-in/credential-provider`，逐字）：
> use the `clientDataHash` that's provided directly in `CreatePublicKeyCredentialRequest()`
> or `GetPublicKeyCredentialOption()` instead of assembling and hashing clientDataJSON during
> the signature request. **To avoid JSON parsing issues, set a placeholder value for
> `clientDataJSON` in the attestation and assertion response.**

### 解法：两条流程彻底分开

> ⚠️ **下表「回传 clientDataJSON」一行是错的（2026-09-12 推翻）**：浏览器流程**不应**
> 回传空占位符，应回传**自建真实 JSON**。详见 #43。

| | 浏览器流程（有 `clientDataHash`） | 原生 App 流程（无 `clientDataHash`） |
|---|---|---|
| 签名材料 | `authData ‖ clientDataHash`（系统给的） | `authData ‖ SHA-256(自产 JSON)` |
| 回传 clientDataJSON | ~~**空占位符**~~ **（错！应为自建真实 JSON）** | **同一份自产 JSON**（拼/签/回传三者同字节） |
| 依据 | 官方要求；RP 用的是网页那份 | RP 用的就是 provider 这份 |

`WebAuthn.BROWSER_FLOW_CLIENT_DATA_PLACEHOLDER = ByteArray(0)`；`buildClientDataJsonForBrowser`
**删除**（错路产物）；`clientDataJsonMatchesHash` 保留作诊断，并注明浏览器流程下
`false` 属**预期**（不再是指标故障）。

create 路径同理：浏览器流程回传占位符（attestation 为 `none`，本无签名，`clientDataHash`
仅供系统/网页侧校验）。

### 三家对照（重要：别照抄，两家是反例）

> ⚠️ **本节判定有误（2026-09-12 更正）**：被标为 ❌ 的 **Bastion** 其实是**对的**，
> 被标为 ✅ 的 Bitwarden 也不算错（它由 SDK 内部构造并回传真实 JSON，并非回传空值）。
> **真正错的是 Vaultix 自己**（回传空占位符）。详见 #43。

| 实现 | 浏览器流程如何处理 clientDataJSON |
|---|---|
| **Bitwarden** | ✅ **从不在 Android 侧重建**：交给 SDK，`request.clientDataHash?.let { ClientData.DefaultWithCustomHash(it) } ?: ClientData.DefaultWithExtraData(callingAppInfo.getAppOrigin())`；`Fido2PublicKeyCredential.clientDataJson` 可空（**SDK 内部仍是真实 JSON**） |
| Keyguard | ✅ 重建（`PasskeyProviderGetRequest.kt:119-128` 拼 JSON，`:129` 签系统哈希，`:159` 回传自造 JSON）——**符合规范** |
| Bastion | ✅ 重建（`PasskeyAuthActivity.createClientDataJson` + 回传 `clientDataJsonB64`）——**符合规范** |
| Vaultix（修复前） | ❌ 重建 + 按哈希反选（方向错在"反选"） |
| Vaultix（第三十轮"修复"） | ❌❌ **回传空占位符** —— 比修复前更糟，直接导致 GitHub 注册失败 |

**结论（更正）**：三家**都回传真实 JSON**，只有 Vaultix 走了占位符这条歪路。
**教训不是"参考项目都错"，而是"我把官方文档的条件句读漏了，反而推翻了正确的参考实现"。**

### 验证（沙箱无 Android SDK，用 Gradle 内置 kotlin-compiler-embeddable 2.2.21 直接在真实源码上跑）
- 真实 `WebAuthnTest.kt`：**10/10 通过**（含新增 3 条回归锁）；
- 独立验证程序 20 项断言全绿，关键三条：
  1. 浏览器流程签名可被 RP 用「浏览器 JSON 的哈希」验通（真实登录场景）；
  2. **反证旧路**：浏览器多带一个字段（`tokenBinding`）→ 两个自造候选 `match=false`，
     旧逻辑只会回退到错的那份；
  3. 原生流程回传 JSON 反解 == 签名所用 JSON。

### 教训（含 2026-09-12 更正）

1. **【2026-09-12 更正】别只读官方文档的后半句。** 本条栽在漏读条件句
   `If you retrieve an origin` —— 跳过前置条件把「特定场景的建议」当成「普适要求」，
   写出了与规范冲突的代码。**读官方建议时，先确认它的适用前提。**
2. **【2026-09-12 更正】与规范冲突时，以规范为准。** 规范是 RP 的实现依据；
   任何平台实现建议都不能推翻「RP 必须解析 clientDataJSON 明文校验 `C.challenge`」
   这一硬要求。**"官方文档 vs 规范"打架时，规范赢。**
3. 【保留】凡"provider 要把某个值回传回去"的设计，先问一句：
   **RP 校验用的是谁手里的那份副本？** 若是对方手里的，那关键在签名覆盖的哈希一致
   —— 但**这不等于可以不回传真实 JSON**（RP 仍要读明文）。
4. 【保留】"按哈希枚举候选反选"这种补偿逻辑，本身就是设计错的信号。真方案只有一条：
   **用系统给的哈希去签，不要自造变体。** 补偿代码越多，说明方向越偏。
5. **【2026-09-12 新增】参考实现出现分歧时，先怀疑自己的理解，而不是先判定"他们都错"。**
   本条当时把两家正确实现标为反例，理由仅是"与我的理解不符" —— 这是危险信号。
   **当所有参考实现都跟你不一样，大概率是你错了，而不是全世界错了。**

## 36. 通行密钥「Authentication failed」第二根因：锁态竞态（Activity 把「库锁定」当成「找不到」）（2026-09-11，P0，`38c5130`）

> 用户报：35 号（clientDataJSON 占位符）修复后**仍然** Authentication failed，
> 并追问「是密码库的问题吗，密码库加锁解锁的逻辑问题？」——**用户的判断是正确的**。
> 随后指示：「参考 bitwarden 的做法，它能够调用出通行密钥，没道理我们调用不出、
> 校验不了的，哪怕是一字一句抄代码，也要实现」。

### 先定性：这是两个独立缺陷叠加，缺一个都修不好

| | 缺陷 | 性质 |
|---|---|---|
| **A** | `PasskeyGetActivity` 把「库锁定」与「凭证不存在」混为一谈 | **假错误信息**（误导排查方向） |
| **B** | `AutoLockController` 把「我自己拉起的 Activity」误判成「用户切走又回来」 | **真锁定**（会话真的被清） |

只修 A：用户会看到「请解锁」但根本没人锁他，仍失败。
只修 B：锁态竞态消失，但一旦真锁定仍报「Passkey not found」，同样的假错误会复现。

### 完整链路

1. 浏览器请求 → `VaultixCredentialProviderService` 列候选（**能列出 ⇒ 当时库是解锁的**）；
2. 点候选 → 系统拉起 `PasskeyGetActivity`；
3. 同进程 Activity 切换在 `ProcessLifecycleOwner` 语义下 = 一次前后台切换（`onStop`→`onStart`）；
4. `AutoLockController.onStart` 判定回前台是否该锁。`AutoLockPolicy.screenLockRequiresRelock(screenLocked) = screenLocked` —— **只要 keyguard 锁着就锁，与分钟档位无关** ⇒ `lockAll()`；
5. 会话清空 → `ItemRepositoryImpl.observeState` 发空列表（`if (!isUnlocked) emptyList()`）→ `cred == null`；
6. `fail("Passkey not found")` → 浏览器「认证失败」。

### 关键代码依据

- `ItemRepositoryImpl.kt:296-303` `observeState`：库未解锁恒发空列表（**这个设计本身是对的**，
  错在调用方把「空」解释成「不存在」）。
- `AutoLockPolicy.screenLockRequiresRelock(screenLocked) = screenLocked`：无差别 relock。
- `AppLifecycleObserver` 的注册（`VaultixApplication.onCreate`）：`ProcessLifecycleOwner`
  对「同进程 Activity 互切」也会发 `onStop/onStart`，这是 Android 的既定行为。

### 解法：逐处对齐 Bitwarden（用户要求「一字一句抄」）

1. **锁态单独成一路**（对齐 `CredentialProviderProcessorImpl.isVaultUnlocked` +
   `RootNavViewModel` 的 `VaultLocked → VaultUnlockRoute.Standard`）：
   `VaultSessionManager.isAnyUnlocked()`；`cred == null` 时先探锁态分流。
2. **凭据流程豁免窗口**（对齐 `VaultLockManagerImpl` 的 `FOREGROUNDED → handleOnForeground()`
   取消超时任务、`OnAppRestart` 的 autofill 豁免）：新增 `CredentialFlowGuard`，
   两个凭据 Activity 在 `onCreate` 最早时机打点。
3. **验证簿记**（对齐 `BitwardenCredentialManagerImpl`）：`isUserVerified` 全路径复位、
   `authenticationAttempts` 上限 5。
4. **origin 取值顺序**（对齐 `getOriginUrlFromAssertionOptionsOrNull`）：host 取
   **请求 JSON 的 rpId**，缺失时明确失败（`Error.MissingHostUrl`），不再构造 `"https://"` 伪 origin。

### 教训（**本轮最贵的一条**）

**真实运行抓出了本次改动自身引入的严重缺陷**：`CredentialFlowGuard` 初值若取
`Long.MIN_VALUE`，判定式 `now - lastFlowStartedAtMs` 会**整数溢出为负数**，
负数恒 `< 8000` ⇒ 判定「在豁免窗口内」**恒为真** ⇒ **自动锁定被永久抑制**。

这个缺陷在纸面推演里 100% 看不出来（逻辑"对称"且看起来更严谨）。改成初值 `0L` 后
（`elapsedRealtime` 恒为正且远大于窗口）才正确，并把该溢出场景固化为回归断言。

⇒ **凡是能用真实运行时验证的，绝不靠读代码下结论**。这条在第 29 轮已被总结过一次
（加密填充假设被 JCE 实测否掉），本轮再次应验 —— 而且这次否掉的是**我自己刚写的代码**。

### 复现断言清单（`VerifyCredentialFlowGuard.kt`，9/9 PASS）

1. 凭据流程刚启动 + 屏幕锁定 → **不锁**（豁免生效）
2. 凭据流程已过窗口 + 屏幕锁定 → **锁**
3. 从未启动流程 + 屏幕锁定 → **锁**（初值不得造成意外豁免）
4. 恰好到达窗口边界 → 视为过期（`<` 而非 `<=`）
5. 窗口内 + 后台超时 → 仍豁免
6. 「从不自动锁定」档位 → 不锁（与本机制无关）
7. `Long.MIN_VALUE` 作初值会溢出（**证明为什么不能用它**）
8. 初值下、无流程时即便 `elapsedRealtime` 很大也不豁免
9. 初值确为 `0`

