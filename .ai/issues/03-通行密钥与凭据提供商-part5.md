# 03-通行密钥与凭据提供商 · 第 5 篇（#163 ~ #211）

> 主篇索引：[`03-通行密钥与凭据提供商.md`](./03-通行密钥与凭据提供商.md)（含计数规则与重号警告）
> 本篇 7 章 · 19.6KB · 覆盖 #163 ~ #211

## 163. 🔴 Firefox Android：通行密钥「取不到」—— 浏览器**根本没向 Vaultix 发请求**（2026-10-04，非本仓库缺陷）

- **现象**（用户接 #162 修完 Firefox 密码填充后紧接着报）：「firefox 可以显示密码条目和填充了，
  但是通行密钥好像取不到。」

- **判读（靠日志零条埋点定性，不是靠猜）**：`vaultix_logs_20261004_121252.txt` 全文
  `CP ` 埋点 **0 条**、`CredentialManager` / `CredentialProvider` 字样 **0 处** ——
  而该日志覆盖了用户在 Firefox 上的完整操作时段（12:09–12:12，含多次输入框聚焦）。
  ⇒ **系统从未向 Vaultix 发起过一次通行密钥请求**。
  ⚠️ 这一步是全条的关键：**「我们筛不出候选」与「系统压根没来问」是两个完全不同的病因**，
  而它们在用户侧的现象**完全一样**（都是"取不到"）——
  唯一能区分的就是「入口埋点有没有被触发」。这正是 8.5 铁律
  「**「列表为空」必须逐级量化埋点**」在入口那一层的应用。

- **根因（Mozilla 官方，Bugzilla 原文）**：
  - [Bugzilla 1862132「Support Credential Manager JetPack API on Android 14+」](https://bugzilla.mozilla.org/show_bug.cgi?id=1862132)
    （`m_kato`，**RESOLVED FIXED**，milestone 128）—— Firefox 128 起才接上 Android 14 的
    Credential Manager 第三方 CP 路由。**Android 13 及以下仍只走 Google Password Manager。**
  - **同 bug 下 m_kato 的原文（决定性）**：
    > `Current implementation is that we use Credential manager when
    > "Discoverable Credential" is required.`
    ⇒ **Firefox 只在请求声明了 residentKey / discoverable credential 时才走 Credential Manager**
    （从而才会向第三方 CP 询问）；默认的 `preferred` mediation 走它自己那套
    「**只支持 Google Password Manager**」的旧对话框 ⇒ **第三方密码管理器一个都看不到**。
  - 相关：Bugzilla [1907531](https://bugzilla.mozilla.org/show_bug.cgi?id=1907531) 记录了同样的形态
    （Proton Pass / 1Password 全部复现，作者指出 Firefox 稳定版/beta/nightly 均如此），
    已被 [2054935](https://bugzilla.mozilla.org/show_bug.cgi?id=2054935) 标为它的重复。
  - ⇒ **站点侧若用 `mediation: "conditional"` 或不带 residentKey 的普通 `navigator.credentials.get()`，
    在 Firefox 上就拿不到第三方 CP 的条目。**
- ⚠️⚠️ **2026-10-04 订正：上面那句「只在 required 时才走 CM」不能读成「Firefox 完全不支持第三方 CP」。**
  Mozilla 官方支持论坛 [Firefox Passkeys not working #1533712](https://support.mozilla.org/en-US/questions/1533712)
  里，同一位提问者（Bitwarden + Firefox Android）自己追记：
  > 「passkeys stored in Bitwarden **can be used to login in** Firefox Android
  > **if they are generated somewhere else**」
  且该帖主题是**注册（create）**坏了（生成后"isn't stored anywhere"，Firefox 只给
  "This Device" 一个选项），**而非认证（get）坏了**。
  ⇒ **更准确的区分**：
  | 路径 | Firefox Android 上的状态 |
  |---|---|
  | **认证（get / 登录）** | **可以是通的** —— 通行密钥在别处（Bitwarden / Google 等）注册过就能用 |
  | **注册（create / 生成）** | **对第三方 PM 明确坏** —— 只给「This Device」，存进 GPM，第三方收不到 |
  | 是否走 Credential Manager | 取决于请求的 **mediation / residentKey**，见上 |
  ⇒ 下一位接力者**别把这条读成「Firefox 上通行密钥一律不可用」**：
  用户若报"Firefox 上登不上"，先问清是**注册**还是**认证**，
  再看新加的 `CP GET` / `CP CREATE` 两条入口埋点谁被触发 —— 这次的日志是**两条都没触发**。
- **⚠️ 定性：这不是 Vaultix 的缺陷，代码侧无从修**。已核实 Vaultix 侧前置条件**全部齐备**：
  `credential_provider.xml` 已声明 `TYPE_PUBLIC_KEY_CREDENTIAL`（且刻意不声明 password，见 #26）·
  `CredentialProviderEntryBuilder` 的逐级埋点（`total/unusable/rpIdMiss/allowedMiss/matched`）齐备·
  `rpId` 双侧归一（`trim→trimEnd('.')→lowercase→IDN.toASCII`）·
  `allowCredentials` 严格匹配为空时**回退到只按 rpId**（宁可多列不可漏列）·
  服务与解锁页**共用同一份**构建逻辑（不复制，故不会漂移）。
  ⇒ **不要为了"修"这个去改匹配逻辑** —— 那会把本来正确的代码改坏。
- **用户侧绕行（按代价从低到高）**：
  1. **在 Firefox `about:config` 开**
     `security.webauthn.webauthn_enable_android_fido2.residentkey = true`
     （m_kato 在 1862132 与 1893366 两处都点名这个 pref；多个用户验证有效，
     但 1893366 里有用户报告开了仍复现 ⇒ **不保证，且改的是浏览器实验性配置**）。
  2. **同一站点改用 Chrome 登录**（Android 14+ 上第三方 CP 路由可靠）—— 最省事的一条。
  3. 站点若是自家产品，让后端在 `create()` 时请求 `residentKey: "required"` +
     认证时用 `mediation: "conditional"` ⇒ 命中 Firefox 唯一会走 CM 的那条路。
- **本轮实际改动（唯一一处）**：补 `onBeginCreateCredentialRequest` 的**入口埋点**
  （此前 GET 侧每条路径都有 `CP ` 日志，CREATE 侧只有 cancel 打了）——
  既然本条的病因是"系统没来问"，**CREATE 侧连一个判据都没有**，
  下次再遇到"Firefox 上注册通行密钥也没反应"时同样无法区分。
  口径与 GET 侧对齐：调用方包名 + origin（只打域名）+ 请求类型；并补上 `ok` / `failed` 两条。

---

## 190. 内容指纹补全时顺带覆盖 `fido2Credentials` 的 11 个字段（2026-10-26）

- **背景**：05 篇 #190 把 `KdbxEncoder.contentFingerprint` 从 5 个字段扩为全字段。
  与本篇相关的是 `fido2Credentials` —— 它此前**完全不在摘要里**。
- **后果**：换一把通行密钥（`credentialId` / `keyValue` / `userHandle` / `rpId` 等任一变化）
  后保存，指纹不变 ⇒ #185 的写回基线判为"内容没动过" ⇒ **基于旧版本覆盖远端**。
  通行密钥的私钥被旧值盖掉之后，那个站点就再也登不进去了，且**没有任何提示**。
- **修法**：`fido2Credentials` 的 11 个字段（含 `rpId` / `userHandle` / `counter` /
  `discoverable` / `keyAlgorithm` / `keyCurve` 等）全部进摘要，嵌套结构用 `toString()`
  取全字段，外层用 `FIELD_SEPARATOR` 分隔。
- **验证**：`KdbxFingerprintCoverageTest.FIELD_MUTATORS` 里对 `fido2Credentials`
  有一条独立的 mutator，改一次断言指纹必变。
- **判据**：**凡是"密文/私钥类"字段，一旦被漏在指纹外，那道防线在该字段上恒失效**——
  而且它的失效形态不是报错，是"悄悄写回旧值"。


---

## 195. 🔴 同一把密钥两个名字 + 四处 `FLAG_MUTABLE`（2026-10-26，对比审计）

### 195.1 `keyAlgorithm`：`"ECDSA"` vs `"ES256"`

- **现象**：用户本机新建的通行密钥显示 `ECDSA`，从 KeePassDX 导入的显示 `ES256`，
  而它们**是同一把曲线上的同一类密钥**。
- **根因**：这个字符串在三个地方被写入，各写各的：

  | 写入点 | 原值 |
  |---|---|
  | `PasskeyCreateActivity` | `"ECDSA"` |
  | `KdbxPasskeyCodec.fromCredential` | `"ES256"` |
  | `CipherMapper`（写回默认值） | `"ECDSA"` |

- **为什么这是 bug 而不只是"叫法不同"**：任何按算法名做判断的地方
  （例如将来支持 Ed25519 时"这把是不是 ES256"）都会在其中**一半数据上判错**，而且不报错。
- **修法**：`core:model` 新增 `PasskeyKeyAlgorithm`（`ES256` / `DEFAULT`），三处全部改引用。
  选 `ES256` 而不是 `ECDSA`：`ES256` 是 WebAuthn/COSE 里那个**具体算法**的名字
  （ECDSA + P-256 + SHA-256），而 `ECDSA` 只是一个**族名**（还能是 ES384/ES512）；
  本项目实际也只支持这一把，写 `ES256` 与能力相符。
- **兼容性**：Bitwarden 对该字段不做枚举校验（它是加密的自定义字段，服务端看不到明文），
  存 `ES256` 不会被拒；官方写 `ECDSA` 也无妨（双方都只是"原样存取"）。
  ⇒ **不需要为兼容而保留旧值**。
- **⚠️ 不要加"两种都认"的归一化**：那会让"到底该写哪个"永远悬着，新一代数据继续分裂。

### 195.2 四处 `PendingIntent.FLAG_MUTABLE`

- **根因**：`CredentialProviderEntryBuilder` 三处（解锁动作 / 密码候选 / 通行密钥候选）
  + `VaultixCredentialProviderService`（创建入口）都用 `FLAG_MUTABLE`，
  而 `AutofillIntents.pending` 从一开始就是 `FLAG_IMMUTABLE`（注释还写着"必须 IMMUTABLE"）。
  → 又一次 **同一条规则只执行了一半**。
- **为什么 MUTABLE 在这里没有理由**：MUTABLE 的语义是"持有者可以改 extras"。
  这些 Intent 全是本应用构造好的**显式 Intent**，不需要系统往里填任何东西
  （填值靠 API 33+ 的 `CredentialEntry` 本身，不靠改 extras）。
  解锁那一条更明确：Bitwarden 用 MUTABLE 是因为要宿主回填认证结果，
  而本应用**不需要** —— 解锁完成后系统会**重新调用** `onBeginGetCredentialRequest`。
- **风险**：extras 里带 `requestJson`（含 challenge / allowCredentials）、
  `vaultId` / `itemId` / `credentialId`。可写 = 能让签名请求指向另一把密钥。
- **修法**：四处统一改 `FLAG_IMMUTABLE`，并在两个文件头各留一段说明（含与 Bitwarden 的
  有意分歧及理由），避免下一个人"对齐上游"又改回去。

## 196. 🔴 创建通行密钥时**完全不校验** origin 与 rpId 的从属关系（2026-10-26，对比审计）

### 196.1 现象与根因

`PasskeyCreateActivity.onCreate` 推导 origin 的写法是：

```kotlin
origin = runCatching { JSONObject(requestJson).optString("origin").takeIf { it.isNotBlank() } }.getOrNull()
    ?: CallingAppOrigin.originOrNull(providerReq?.callingAppInfo)
    ?: "https://$rpId"          // ← 凭空构造
```

三个问题叠在一起：

1. **优先级与 GET 侧相反**。GET 侧（`PasskeyGetActivity`）是
   `originFromCaller ?: originFromRequest`，创建侧却是请求优先。同一件事两处两个口径。
2. **最后一级 `"https://$rpId"` 是凭空构造**。rpId 为空时得到 `"https://"`；
   rpId 非空时得到一个"看起来合法"的 origin。于是**任何没带 origin 的请求都会被自动判成合法**。
3. **完全没有 origin vs rpId 的从属校验**。也就是说，任何能构造
   `CreatePublicKeyCredentialRequest` 的调用方，都能让本应用给 `rpId = bank.example`
   生成一把密钥，而 origin 随便写。

### 196.2 修法

- 新建 `PasskeyOriginPolicy`（纯函数，可单测）：`origin` 的主机必须**等于** `rpId`，
  或 `rpId` 是它的**可注册后缀**；再做一层"rpId 不能宽于 origin 的可注册域"
  （挡住 `co.uk` / `github.io` 这类公共后缀当 rpId）。
- `PasskeyCreateActivity`：推导顺序改为 `CallingAppOrigin` 优先、请求 JSON 次之，
  **删掉凭空构造的兜底**；推导完调 `PasskeyOriginPolicy.check`，`Rejected` 直接
  `fail(CreateCredentialUnknownException("Origin does not match relying party"))`。
  **用户可见文案不含域名**（`reason` 只进日志）—— 这条提示会显示在别的 App 里。
- 新建 `PasskeyOriginPolicyTest`（8 用例，表驱动）。

### 196.3 ⚠️ 不能用 `UriMatcher.sameSite`

`sameSite` 的语义是 **Cookie 口径的"同一站点"**（可注册域相等），于是
`a.example.com` 与 `b.example.com` 会**互相通过**。而在 WebAuthn 里，
`rpId = a.example.com` 的密钥**不能**用于 `b.example.com`。

> **"同一站点"是浏览器 Cookie 口径，"rpId 从属"才是 WebAuthn 口径，两者不能混用。**

这条边界专门写了一个测试用例守着（`兄弟子域必须拒绝_这是与 Cookie 同站点口径的分界`）。

### 196.4 顺带核实的**不是漏洞**的两处

- `PasskeyResolution.isSameRpId`：`normalizeRpId(a) == normalizeRpId(b)`，**精确匹配**，
  符合规范（rpId 比较本来就是字符串相等，不是域后缀包含）。
- `CallingAppOrigin`：已是"自证式读取"（不用特权名单，靠 App 自己的 allowlist 模板自证），
  本次未改。

---

## 197. 🔴 `authenticatorSelection.residentKey` 从不读 → `discoverable` 恒 true（2026-10-26，对比审计）

### 197.1 现象

创建通行密钥时 `VaultFido2Credential.discoverable` 硬编码 `true`。
后果不是崩溃，而是**语义造假**：库里所有通行密钥都自称"可发现凭证（resident key）"，于是

- 界面上的"可发现凭证"筛选与统计全错；
- 将来做"仅展示可发现凭证"的条件发现流程时，会把 RP 明确声明
  `residentKey: "discouraged"` 的凭证也列出来；
- 与 Bitwarden 服务端对账时字段不一致（那边写的是 RP 实际要的值）。

### 197.2 取值口径（WebAuthn L2 §5.4.4 / §5.4.6）

| 请求里写的 | 本实现记录 |
|---|---|
| `residentKey = "required"` | `true` |
| `residentKey = "preferred"` | `true`（"能可发现最好"，而本实现确实可发现 ⇒ 满足偏好） |
| `residentKey = "discouraged"` | `false`（唯一记 false 的取值） |
| 只给旧字段 `requireResidentKey` | 按它判断 |
| 两者都缺 / 显式 null | `true` |

**缺省记 true 是一处有意偏离**：L2 把 `residentKey` 的默认值定成 `discouraged`。
但本应用的物理形态（私钥在库里、用户点一条就填）本来就是可发现的，
记 false 会让条件发现流程在常见站点上失效。Bitwarden Android 同样不因缺省而记 false。

### 197.3 修法

新建 `PasskeyAuthenticatorSelection.isDiscoverable(requestJson)`，`PasskeyCreateActivity`
用它替代硬编码 `true`；新建 `PasskeyAuthenticatorSelectionTest`（9 用例）。

### 197.4 🔴 附带挖出的**测试基建坑**：单测里的 `org.json` 是空壳

写这条测试时 3 个用例怎么改都红，而逻辑明明对。根因不在业务代码：

> **AGP 的 `mockable-android.jar` 把 `org.json.JSONObject` 的每个方法都替换成"返回默认值"**：
> `has()` → `ireturn 0`、`getJSONObject()` → `aconst_null`、`isNull()` → `ireturn 0`，
> **连 `JSONObject(String)` 构造器都是空实现**（`aload_0; invokespecial Object.<init>; return`）。

⇒ 任何"解析 JSON"的代码在 JVM 单测里**根本跑不到真实逻辑**，只走"解析失败"分支。
更危险的是反向：一个恒返回默认值的实现，会让**应该失败的用例通过**。

- **定位手法**（可复用）：`javap -c -p -classpath <android.jar> org.json.JSONObject`
  → 看到 `has` 的方法体只有 `iconst_0; ireturn` 就是空壳。
- **修法**：`testRuntimeOnly(libs.org.json)`（`org.json:json:20240303`）。
  测试 fork 的 classpath 里，测试运行时依赖排在 `mockable-android.jar` **之前**，真实实现胜出。
  不影响 app 编译与运行时。
- **守卫**：`UnitTestEnvironmentTest` 断言"单测里的 org.json 是真实实现"。
  哪天依赖被删 / AGP 改策略，它先红，而不是某个业务断言莫名失败。

### 197.5 排查过程中的一次**误判**（值得记）

一开始判断成"Android 与 `org.json:json` 的嵌套对象行为不一致"，于是写了一层
"取到子对象 → `toString()` → 重新解析"。那层绕行**本身引入了新 bug**：
显式 `null` 被序列化成字面量 `"null"`，不再被 `isNull` 识别。

> **教训：在被测环境可疑时，"绕过环境"的写法会把环境问题变成业务问题。**
> 正确顺序是先把环境修对（这一节），再用**最直接的读法**（`optJSONObject` + `isNull`）写业务代码。

---

## 208. 注册请求里的 `excludeCredentials` **从不读**（2026-10-29）

**现象**：全仓 `grep excludeCredentials` 零命中（2026-10-26 审计时已记在"尚未落地"里）。
⇒ 同一站点点两次「创建通行密钥」，库里就有两把、RP 侧也是两把，下次登录列出两个
一模一样的候选，**用户分不出该选哪个**；"换机后重新注册"也拿不到 RP 期望的"已存在"提示。

**规范口径（WebAuthn L2 §5.4.4 / §6.3）**：RP 注册时可用 `excludeCredentials` 声明
「这个账号已经有过哪些凭证」；认证器若已持有其中任何一个，**不得**再建新的，
应中止并返回 `InvalidStateError`。

**修法**：新增 `PasskeyExcludePolicy`（与 #196 `PasskeyOriginPolicy` / #197
`PasskeyAuthenticatorSelection` 同族的 `internal object`）：
- `excludedIds(requestJson)`：解析出 id 集合；**解析失败 = 空集合**（增益语义，
  一条解析失败就让注册失败，代价与收益不成比例）；
- `firstConflict(...)`：**只在本次 rpId 下**按 `credentialId` 比（不比 `transports` /
  `type` —— 那些是筛选提示，不是身份）；
- `PasskeyCreateActivity.createPasskey()` 在**启动协程之前**判定（要 `setResult`+`finish`，
  必须主线程），命中则 `CreateCredentialCustomException("InvalidStateError", …)`。

⚠️ **两个实测约束**：
1. androidx.credentials 1.6.0 **没有** `CreateCredentialInvalidStateException`
   （下载 AAR 数过：只有 Unknown / Cancelled / Interrupted / NoCreateOption /
   Unsupported / ProviderConfiguration / Custom）⇒ 用 **CustomException** 带
   `type = "InvalidStateError"`，让 RP 至少拿得到语义；
2. 比对集合取 `loginCandidates`（活跃库的登录条目）—— 通行密钥只挂在登录条目上，
   本流程写入目标也只有那里 ⇒ 它就是"已有的全部"，不必全库扫。

> **判据**：① **规范里写死的语义，不实现就是洞**（哪怕它不是安全缺口，后果直接落在体感上）；
> ② **"解析不出来"要落在增益侧**（不阻断主流程），但 **"命中"必须拦**；
> ③ 判定集合要能说清"为什么这就是全部"。、#208

---

## 211. `excludeCredentials` 命中后的 type 串写成了裸字面量 ⇒ RP 收不到规范的 `InvalidStateError`（2026-10-30）

- **背景**：上一轮（#208）实现了「RP 要求排除的凭证已存在 ⇒ 中止创建」，
  但 `fail(...)` 传的是手写的 `"InvalidStateError"`。
- **为什么是错的**：`CreateCredentialCustomException` 的 `type` 会被 GMS 侧**按字符串分派**
  转成回给 RP 的 `DOMException`。androidx 认的取值是命名空间化的常量
  `androidx.credentials.TYPE_INVALID_STATE_ERROR`；裸串 `"InvalidStateError"` 落进
  "未知类型"分支 ⇒ 浏览器里 `navigator.credentials.create()` 被 reject 的原因
  不是 `InvalidStateError`，站点也就没法给"这个账号已经注册过"的提示。
  表现是"注册失败但说不清为什么"，比不拦更难受。
- **修法**：直接用库里的公开常量
  `InvalidStateError.TYPE_CREATE_PUBLIC_KEY_CREDENTIAL_INVALID_STATE_ERROR`
  （值 = `"androidx.credentials.TYPE_INVALID_STATE_ERROR"`）。
- **取证**：`credentials-1.6.0.aar` → `classes.jar`，`javap -constants` 读出常量名与取值。
  同一次核对也再次确认：库里**没有** `CreateCredentialInvalidStateException`
  （`CreateCredential*` 九件套是 Cancellation / Custom / Interrupted / NoCreateOption /
  ProviderConfiguration / Unknown / Unsupported + 基类 + Companion），
  所以"custom type"确实是唯一路径 —— 但 type **必须**用常量，不能自己造。
- **判据**：**跨进程协议字段永远用对方的常量，不写字面量**。字面量在本地测试里恒绿
  （异常构造成功、`type` 也有值），只有在真机 + 真 RP 上才暴露 ⇒ 这类错误
  既不会被单测发现，也读不出根因，只能靠"翻一遍对方库里的常量表"提前避免。
