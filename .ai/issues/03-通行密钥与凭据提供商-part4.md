# 03-通行密钥与凭据提供商 · 第 4 篇（#50 ~ #109）

> 主篇索引：[`03-通行密钥与凭据提供商.md`](./03-通行密钥与凭据提供商.md)（含计数规则与重号警告）
> 本篇 3 章 · 15.7KB · 覆盖 #50 ~ #109

## 50.1 【更正 50】真根因不是「判据口径」，是 `isUserPreVerified` **只写不读**（2026-09-18 晚）

> 用户补了一句「**属于 bitwarden 的**」⇒ #50 里「两处判据口径不一致」的推导**被证伪**：
> 通行密钥属 Bitwarden 库，而真机日志里 Bitwarden 库是 `first=… → Success`
> （**已解锁**），「点进去判锁定」的环**根本不成立**。**上面的 #50 结论作废**，
> 保留仅为"一次走错的推理"的样本。

### 真根因：UV 豁免标志三处断链，**没有任何一处读它**

`isUserPreVerified` 这套机制**存在、注释也完全正确**，但链路断了：

| 环节 | 位置 | 状态 |
|---|---|---|
| 语义实现 | `CredentialProviderIntentUtils.isUserPreVerified()` `:60-79` | ✅ 正确 |
| 契约 | 同上 KDoc：「本 App 自己的解锁流程（用户在解锁时刚做过生物识别，**无需再弹一次**）」 | ✅ 意图明确 |
| 写入管理器 | `CredentialProviderRequestManager.setGetCredentialRequest(…, preVerified)` | ✅ |
| trampoline 透传 | `CredentialProviderActivity:135` | ⚠️ 只传给了 MainActivity |
| **候选条目的 Intent** | `CredentialProviderEntryBuilder.publicKeyEntry():362-377` | ❌ **没带这个 extra** |
| **`PasskeyGetActivity`** | `verifyUser()` | ❌ **0 处引用** |
| **`PasskeyCreateActivity`** | `verifyUser()` | ❌ **0 处引用** |
| **解锁成功后置位** | `AutofillActivity` 解锁成功分支 | ❌ **没有任何地方置为 true** |

⇒ 四处缺一不可地断了 ⇒ `verifyUser()` **无条件弹窗**，这就是第二次指纹。

### 为什么正好解释「两次」

```
① CP 列候选时库锁定 → authenticationActions → AutofillActivity 弹指纹（解锁）
② 解锁成功 → RESULT_OK + 候选列表
③ 用户点候选 → PasskeyGetActivity
④ verifyUser() 无条件弹指纹   ← 即使第 ① 步刚在同一流程里做过生物识别
```

### 修法（三处 + 一处关键）

| # | 位置 | 改动 |
|---|---|---|
| 1 | `CredentialProviderEntryBuilder.publicKeyEntry()` | Intent 补上 UV 已完成的 extra |
| 2 | `PasskeyGetActivity` | `isUserPreVerified` ⇒ 跳过弹窗，**合法地**把 `isUserVerified` 置真后 `sign()` |
| 3 | `PasskeyCreateActivity` | 同上（注册侧） |
| ⚠️ | **`AutofillActivity` 解锁成功且为 CP 流程时** | 必须把「刚做过生物识别」写进管理器——否则 1~3 传下去的值**恒为 false**，弹窗照旧 |

### 安全边界（动手前必须定死）

「解锁用的生物识别」充当「WebAuthn UV」要满足：
1. **同一次流程**（不能被上一次会话的解锁顶用）⇒ 标记需**流程/时效绑定**，不做进程级持久标志；
2. 语义可对齐（都属 user verification）；
3. **`sign()` 的断言不能删、不能放宽** —— 只允许"合法地提前置真"。

⚠️ 这三条先写成 `.ai/decisions/` 决策记录再动手；**不要**凭"看起来能省一次"就跳过。

### 定案依据（用户答复取代 logcat）

用户答「**是的**」——确认第二次弹窗出现在**点候选之后**。这与断链假设**唯一吻合**：
断链存在 ⇒ 标记恒 `false` ⇒ 必弹第二次，且时机必为"点候选后"。故免去 logcat 亦定案。

### 实施状态：✅ 已修（2026-09-18）

6 个文件落地，另有两处刻意取舍（**保留确认卡片** —— 它是"用谁的凭据登录哪个站点"的
授权，与"设备在场"是两件事；`passwordEntry()` 一并挂标记）。落点明细见
`.ai/decisions/通行密钥UV豁免-定稿.md` §4.3。

门禁全绿：四个 `.ai/tools` 脚本（仅 3 处已知跨模块误报）+ `detekt` +
`compileFullDebugKotlin` / `compileOfflineDebugKotlin` + 单测。

⚠️ **仍未结**：真机验收（决策文档 §5.2 八条）—— 尤其 I5「解锁后手动锁库须重弹」、
I1/I3「连续两次登录第二次仍弹」。

### 上游对照

**Bastion 也没有这个豁免**（`PasskeyAuthActivity:319/367/379` 无条件 `requestBiometricAuth`，
全项目无 `isUserPreVerified` / `biometricPromptResult`）⇒ 它同样会弹两次。
**即 Vaultix 的机制比 Bastion 更完整**（已经写好了 flag，只差接上）。

详见 `SESSION-2026-09-18.md` §11。

---

## 108. 🔴 注册"能保存、但网页端校验不出"根因：`attestationObject` 的 CBOR 键名写成了 `attestationStatement`（2026-09-20）

**现象**（用户原话）：

> 「app 好像无法正常通过网页保存通行密钥。这是怎么回事」
> 追问后补充：**「能弹出，能保存，但是网页端校验不出来已保存的」**

⚠️ 这个症状组合（**流程不报错、客户端自认为成功、服务端拒绝**）是最容易误判的一类 ——
它不像"崩了"、"没弹"那样有明确断点，而是一条**结构错误**在客户端被静默接受、
只在服务端解析时才暴露。

### 根因：`buildNoneAttestationObject` 里键名写错

`core/common/.../WebAuthn.kt` 的 `buildNoneAttestationObject` 手工拼 CBOR：

```kotlin
// ❌ 修正前
buf.write(CBOR_MAP_3)
writeText(buf, "fmt");    writeText(buf, "none")
writeText(buf, "attestationStatement"); buf.write(CBOR_EMPTY_MAP)   // ← 键名错
writeText(buf, "authData"); writeByteString(buf, authData)
```

W3C WebAuthn L2 §6.5.4 与 CTAP2 canonical CBOR 明确规定：`attestationObject` 的顶层是
**3 个固定键**、键名**逐字固定**：

| 键名 | 类型 | 说明 |
|---|---|---|
| `fmt` | `tstr` | attestation statement format identifier |
| `attStmt` | `map` | **⚠️ 不是 `attestationStatement`**；`fmt = "none"` 时也**必须存在**，值为**空 map** |
| `authData` | `bytes` | authenticator data |

⇒ 我写成 `attestationStatement`（20 字节文本），Relying Party 按规范读 `attStmt` 时**取不到键**
⇒ 解析后 attestation statement 缺失 ⇒ 注册校验失败。而客户端**全程无异常**
（它只是"往一个 map 里放了几个键"，从不校验键名）⇒ 就是"能保存、但校验不出"。

### 字节级证据（修正前 / 修正后）

用纯 Python 独立复现（不依赖 Kotlin）打印出真实字节：

```
修正前： … 0x74 "attestationStatement"  → 74 61 74 74 65 73 74 61 74 69 6f 6e ...
                └ text(20)
修正后： 0x67 "attStmt" 0xA0             → 67 61 74 74 53 74 6D 74 A0
                └ text(7)      └ map(0) = 空 map，必须存在
```

### 修法

带 KDoc 附规范依据地改键名（`WebAuthn.kt`），并**顺手加了一条防御性 `require`**：

```kotlin
require(bytes.size < CBOR_SHORT_MAX) {
    "writeText 只支持 < $CBOR_SHORT_MAX 字节的文本，收到 ${bytes.size}: $s"
}
```

> 理由：`writeText` 只写**短头**（`0x60|len`，len < 24）。若日后有人往这里塞长键名，
> **静默截断/写坏长度头**是比键名写错更难查的一类 bug —— 让它在**编码当场**炸掉。
> 已核对现有 4 处调用参数（3 / 4 / 7 / 8 字节）全部 < 24。

### ⚠️ 为什么**不是**缺 `transports` / `publicKey` / `publicKeyAlgorithm`

排查时对着上游（`reference/bastion/.../PasskeyCreateActivity.kt` 的 `createAttestationObject`
L1185-1224，用的是**正确键名** `attStmt`）逐字段比对，确认这几项**不是**本次成因：

| 候选 | 判定 | 理由 |
|---|---|---|
| `transports` | ❌ 无因果 | 它只影响 RP 后续**认证**时的传输提示，与**本次注册校验**无关 |
| `publicKey` / `publicKeyAlgorithm`（credProps 之外） | ❌ 信息重复 | authenticatorData 里已有凭据公钥；这不是 CS 的必填键 |
| `flags` / BE-BS | ❌ 已在 #32/#33 排除 | 见 #33「BE/BS 与验签失败无因果」 |

⇒ 按 `8.4`「**改观感先做减法，再做加法**」的同源纪律：**一次只修一个已证实的根因**，
不把"猜测相关"的改动混进同一提交（否则真机复测通过也说不清是哪一处起的作用）。

### ★ 单测把错误行为**锁死**了（比 bug 本身更值得记的教训）

`core/common/src/test/.../WebAuthnTest.kt` 里原本写着：

```kotlin
assertThat(text).contains("attestationStatement")   // ❌ 直接把错误键名锁为"期望值"
```

⇒ 测试**永远绿**，且**反向保护了 bug** —— 谁改对键名反而会让测试红。
这解释了为什么这个 bug 能活到用户真机反馈。

**修复方式：把"协议结构断言"从字符串包含升到字节级 + 反向锁**：

```kotlin
assertThat(text).contains("attStmt")
assertThat(text).doesNotContain("attestationStatement")   // ★ 反向锁：必须有
assertThat(attObj[1].toInt() and 0xff).isEqualTo(0x63)    // text(3) 头
assertThat(String(attObj, 2, 3, Charsets.UTF_8)).isEqualTo("fmt")
assertThat(attObj[10].toInt() and 0xff).isEqualTo(0x67)   // text(7) 头
assertThat(String(attObj, 11, 7, Charsets.UTF_8)).isEqualTo("attStmt")
assertThat(attObj[18].toInt() and 0xff).isEqualTo(0xa0)   // ★ 空 map 必须存在
assertThat(attObj[28].toInt() and 0xff).isEqualTo(0x58)   // bytes(1 字节长度) 头
assertThat(attObj[29].toInt() and 0xff).isEqualTo(authData.size)
assertThat(attObj.copyOfRange(30, 30 + authData.size)).isEqualTo(authData)
```

**并已证明这条回归锁真的有效**：临时把键名改回 `attestationStatement` ⇒ 测试**正确地 FAIL**
（报 `missing attStmt`）；恢复后 PASS。**"补了测试"不等于"测试有保护力"** —— 必须做这个反证。

### ⇒ 可复用判据（写协议编解码时自问）

> **凡是"按规范手工拼/解析二进制或半结构化数据"的地方，
> 断言必须落到 ① 键名的 `bytes` 级、② 键集相等、③ 长度头正确 ——
> 绝不能用"包含某子串"这种松断言。**
>
> 松断言在**正常路径**上也能绿，所以它给出的不是保护而是**虚假安全感**；
> 更糟的是它会**把错误实现固化为"期望值"**（本轮就是这么发生的）。

### 顺带更正一处**陈旧 KDoc**（`PasskeyCreateActivity.kt`）

类头 KDoc 残留着已被 #43 推翻的旧结论（说浏览器流程回传占位符），改为：

> 浏览器流程（系统给了 `clientDataHash`）回传的 `clientDataJSON` **仍是自建的真实 JSON**。
> 官方那句"set a placeholder value for clientDataJSON"带前置条件 `If you retrieve an origin`
> （仅经**特权应用名单**拿到 origin 的场景适用），本模块走「自证式读取」不适用；
> 而 W3C WebAuthn L2 §7.1 要求 RP 解析 clientDataJSON **明文**校验 `C.challenge`，
> 空占位符必然失败。详见 #43（该条已推翻更早的 #35）。

### 状态与验收

✅ 代码已修 + 单测已升级（含反证）。**真机已验收通过（2026-09-21，见 #109 末尾）**：
- [x] Chrome / Edge 打开真实站点 → 注册通行密钥 → **服务端不再"校验不出"**
- [x] 注册后**立即在同一站点登录**一次，确认凭据真的可用
- [x] 回归：已注册的旧凭据登录不受影响

> ⚠️ 2026-09-21 补正：本条的"真机验收未做"曾被读成"注册链路已通"。
> 实际上注册在**浏览器侧仍然整份被拒收**，直到 #109 补掉缺失的 JSON 字段才真正打通：
> **#108 修的是 RP（服务端）解析 `attestationObject`；#109 修的是浏览器（Chromium）解析
> CredMan 响应** —— 两层解析者不同，缺一不可（详见下条开头那节）。

---

## 109. 🔴 注册响应被浏览器**整份拒收**：create 响应的 `response` 缺 `publicKeyAlgorithm`（2026-09-21）

**现象**（用户原话）：

> 「这一步会问我保存到哪一个密码条目。我选中后又无法正常在网页上看到。」

即：**能弹出、能选中、能保存，但网页侧拿不到凭据**。

### ★★ 为什么会被 #108 带偏（这条比 bug 本身更值得记）

#108 里有一节「⚠️ 为什么**不是**缺 `transports` / `publicKey` / `publicKeyAlgorithm`」，
结论是"信息重复、不是必填键"。**那个结论在 RP 层是对的，在浏览器层是错的** ——
因为两端是**不同的解析者、不同的失败点**：

| 层 | 谁在解析 | 缺字段的后果 | 对应条目 |
|---|---|---|---|
| **浏览器** | Chromium 的 `fido2credentialrequest_native_android.cc`，把 CredMan 响应 JSON 转 Mojo | **整份响应被丢弃**（根本到不了 RP） | **本条 #109** |
| **RP（服务端）** | 解析 `attestationObject`（CBOR 结构） | 注册校验失败 | #108 |

⇒ **教训：写"为什么不是 X"时，必须同时写清"在哪个解析层不是"。**
只写结论不写层次，后人会把它当全局结论用（这次就绕了整整一轮）。

### 真机证据（决定性）

```
CredentialManager: Final credential received from: …VaultixCredentialProviderService   ← 我方全对
CredentialManager: finishing session with propagateCancellation false                  ← 会话正常收尾
E chromium: [ERROR:…/fido2credentialrequest_native_android.cc:59]
    MojoClassFromJSON failed to convert JSON: field missing or invalid: publicKeyAlgorithm
E cr_ChromiumWebauthn: [CredManHelper] Failed to convert response from CredMan to Mojo object
```

判据顺序按 `8.5`「**先证伪『自己失败了』**」：系统侧那两句证明**我们没失败** ⇒
问题只能出在"交给对方的形态"上 ⇒ 直接压到字段级，不必再猜密码学。

### 根因

`WebAuthn.buildCreateResponseJson` 的 `response` 里**只有**
`clientDataJSON` + `attestationObject`；而 Chromium 解析
`AuthenticatorAttestationResponse` 时**必读 `publicKeyAlgorithm`**，缺了即整份拒收。

### 修法

补齐四个字段（对齐 `reference/bastion/.../PasskeyCreateActivity.kt` 的响应构造，
一份跑通过的生产实现 —— 不要凭记忆定字段名与类型）：

| 字段 | 形式 | 备注 |
|---|---|---|
| `publicKeyAlgorithm` | **int**（ES256 = `-7`） | **Chromium 必需**；用既有常量 `WebAuthn.COSE_ALG_ES256` |
| `publicKey` | DER **SubjectPublicKeyInfo** 的 base64url | 来源 `GeneratedKey.publicKeySpki`（= `PublicKey.encoded`），本次新增该字段 |
| `authenticatorData` | base64url | 与 `attestationObject` 内那份**同源** |
| `transports` | `["internal","hybrid"]` | 与 Bitwarden / Bastion 同口径 |

★ **三个新入参刻意不给默认值**：少任何一个都是在**调用方看不见的另一侧**失败；
让编译器强迫每个调用点显式提供，是防这类"静默不完整"的唯一可靠手段。

### 顺带（同轮一起改的体验问题，别再当"阻断"查）

用户：「选取密码条目的时候还需要我自己滑动查找，有点费力」+「Bitwarden 会自动找到匹配的密码条目」。

`PasskeyCreateActivity.loadLogins` 原为 `items.filter { it.type == Login }`
（**全库平铺**、不排序、不预选）⇒ 改为按 `rpId` 同站匹配**置顶 + 默认预选**，
选择器由只读下拉改为**可搜索下拉**。

⚠️ `UriMatcher.sameSite` 语义是**宽容**的（任一侧 null/空白即返回 `true`，服务填充场景）
⇒ 匹配前**必须先挡空值**，否则没填网址的条目全被算作"匹配"，排序与预选立刻失效。

### 验收（✅ 已完成，2026-09-21 真机）

| 判据 | 修复前 `dev-4edf866` | 修复后 `dev-924dd85` |
|---|---|---|
| `MojoClassFromJSON failed … publicKeyAlgorithm` | **2 次** | **0 次** |
| `Failed to convert response from CredMan` | **2 次** | **0 次** |
| `Final credential received from …VaultixCredentialProviderService` | 在 | 仍在 |

用户确认：**注册成功**（Edge + `console.cloud.tencent.com`）。

### ⇒ 可复用判据

> **"客户端自认为成功、对端拒绝"时，先找对端自己的日志，再看它挑剔的是哪个字段。**
> 只看我方日志会得到"一切正常"的错误结论；而"响应少字段"这类问题的失败点
> **天然在调用方看不见的另一侧**（此处是浏览器），所以要用 `dumpsys` / logcat 把对端的话捞出来。


---

