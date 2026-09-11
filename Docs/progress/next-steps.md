# 下一步任务清单

> 更新于 2026-09-11（第三十轮）。**【最新】通行密钥「Authentication failed」根因已定位并修复：
> 浏览器流程回传了自造的 clientDataJSON —— 系统只给 32 字节 `clientDataHash`（无明文），
> 而 RP 校验用的是**网页交给它的那份浏览器 JSON**，所以 provider 回传的必须是**占位符**
> （官方明文要求）；签名仍只用系统给的哈希。
> ⚠️ Bastion / Keyguard 两家在这点上都是**反例**，不可照抄；Bitwarden 交给 SDK 的做法才对。**
>
> 上一轮（第二十九轮）**通行密钥「找不到候选 / 列表为空」根因已定位并修复：
> 未 trim + rpId 未归一化 + allowCredentials 无回退（全在 discovery 链）。
> 「解密残留填充」经真实 JCE 实测后被降级为纵深防御，非根因。**
> 同时**撤回**第二十八轮的两个错误结论（见下方「⚠️ 结论更正」）。
> 审计报告 `Docs/progress/audit/bitwarden-alignment.md`；对齐评估
> `Docs/progress/bastion-parity-assessment.md`（**注意已过时**）。
> 状态：`TODO` / `DOING` / `DONE` / `BLOCKED`

## ⚠️ 结论更正（2026-09-11，第二十九轮）

### 更正一：「已注册的通行密钥需要重新注册」——**错误，已撤回**

第二十八轮曾断言「RP 记录的是注册时的 BE/BS 语义，改代码救不了旧凭证，必须重新注册」。
**这个结论是错的。** 查证 WebAuthn 规范与多家 RP 文档后确认：

> **BE（Backup Eligible）与 BS（Backup State）是「注册期存档字段」。**
> RP 在**断言（登录）阶段不做 BE/BS 校验**。RP 的 login 校验清单只有：
> 解码 clientDataJSON → 校验 `type==="webauthn.get"` / challenge / origin →
> 按 credentialId 取公钥 → `SHA-256(rpId)` 匹配 rpIdHash → UP 已置（按策略再查 UV）→
> 验签（`authData ‖ SHA-256(clientDataJSON)`）→ `new signCount > stored signCount`。

因此 **BE/BS 缺失根本不可能导致「登录验签失败」**，也**不存在「旧凭证必须重注册」**。
用户的质疑是对的：「通行密钥为什么要新建呢，这个不是存好的就不动的吗」。

**保留 BE/BS 置位的理由变更为：语义正确性**（Vaultix 私钥随库同步，确实是可备份凭证），
**不是**为了修某个登录 bug。相关 KDoc / 注释 / 测试说明均已改写。

### 更正二：「signCount 改读库」——**错误，已回退**

第二十八轮把断言侧的 `counter` 从硬编码 0 改成「读库里的值原样发送」。**这是引入新 bug。**

规范对计数器的校验是**严格大于**（`new > stored`）。Bitwarden 官方客户端每签一次会**递增并
写回服务端**，同步下来的 `counter` 就是**非零**（`CipherMapper` 第 444 行实证）。原样发送且
不递增 ⇒ **第二次登录发出的值与上次相同** ⇒ `new > stored` 不成立 ⇒ RP 判为**重放**并拒绝。

**已回退为恒 0**（对齐 Bastion `PasskeyAuthActivity` 的 `newSignCount = 0L`）：0 表示
「本 authenticator 不实现计数器」，规范 §6.1.1 明确允许，RP 据此跳过单调性校验。
Bitwarden / 1Password / iCloud Keychain 这类**同步型 passkey 全部走这条路**。

## 第二十九轮 2026-09-11 · 通行密钥「列表为空」根因

> 用户报：BE/BS 那次改动之后，**通行密钥又找不到了，候选列表为空、没有任何候选**。
> 要求对照 Keyguard / Bastion / Bitwarden 三份实现仔细排查后修复。

### 已确认（实测排除法）

- [x] **不是 `8afac3d` 引入的**：`git show 8afac3d --name-only` 只有 3 个文件
      （`PasskeyGetActivity` / `WebAuthn` / `WebAuthnTest`），**完全没碰发现（discovery）逻辑**。
      `VaultixCredentialProviderService` 最后一次变更是更早的 `fec4032`。
      「候选为空」与那次改动**无因果关系**。

### 根因（对照三家实现确认；①为纵深防御，②③④是实际根因）

- [x] **① 解密残留填充（⚠️ 经实测后降级为「纵深防御」，非根因）**：
      曾推断 Bitwarden 服务端用 ISO10126 填充、Vaultix 用 `PKCS5Padding` 解密导致残留。
      **用真实 JCE 实测后该推断不成立**：
      - 标准 SunJCE 的 `PKCS5Padding` 对 ISO10126 密文**直接抛 `BadPaddingException`**，
        不会静默泄漏字节；
      - 反向验证：ISO10126 末字节同样是填充长度，用宽松 PKCS5 解析 **2000/2000 完全正确**；
      - 且 `decrypt` 本就以 `PKCS5Padding` + `doFinal` 正确解填充。
      ⇒ **不是根因**。但保留 `VaultixCrypto.removePkcs7PaddingIfStrict` 作为**纵深防御**
      （仅在严格 PKCS#7 成立时剥离，避免误伤），并记录 `trim()` 对 `0x01..0x10`
      的兜底作用（已验证 16/16 可去除）。
      **教训：一次 JCE 实验就能否掉的假设，不要写进根因。**
- [x] **② 未 trim（实际根因）**：服务端多处字段带前导/尾随空白，官方客户端读出来一律
      `trim()`；Vaultix 的 `CipherMapper.mapFido2` **一个字段都没 trim**。
      而 `rpId` 是按**精确字符串**比对的 —— 不 trim 直接导致全量失配。
      修复：`mapFido2` 全字段 `.trim()`（对齐官方语义；`counter`/`discoverable` 走
      `toLongOrNull`/`toBooleanStrictOrNull`，不 trim 会**静默回落默认值**）。
      同时 `decryptToString` 也加 `trim()` 作为全局兜底。
- [x] **③ rpId 未归一化（实际根因）**：此前是 `cred.rpId.equals(rpId, ignoreCase = true)`，
      只能处理大小写；**末尾根点 `.` / Unicode 域名（punycode）** 一律失配。
      对照 Bastion `PasskeyRpIdNormalizer`：`trim` → `trimEnd('.')` → `lowercase(Locale.ROOT)`
      → `IDN.toASCII(..., USE_STD3_ASCII_RULES)`，且 **`isEquivalent` 两侧都归一化**。
      修复：Vaultix 新增 `normalizeRpId` / `isSameRpId`，语义与 Bastion 逐条对齐。
- [x] **④ allowCredentials 严格过滤无回退（实际根因）**：RP 下发的 `allowCredentials` 是
      **提示**而非授权门。用户若在**其它设备/客户端**注册过该 RP 的 passkey，本地
      credentialId 与列表对不上 —— 严格过滤会把**唯一可用候选也删掉**。
      对照 Bastion `BastionCredentialProviderService.resolvePasskeys`：
      `if (filtered.isEmpty() && allowed.isNotEmpty()) resolve(..., strictAllowCredentials = false)`。
      修复：Vaultix 照抄该回退 —— 严格匹配为空时回退到「只按 rpId」并打日志。

### 对照结论（Keyguard / Bitwarden 的共识）

三家在**锁定态**的处理完全一致，Vaultix **已对齐**，不是本次根因（已核实）：

| 维度 | Bitwarden | Keyguard | Vaultix |
|---|---|---|---|
| 全库锁定 | 只返回 `authenticationActions`（unlock），**不带** credentialEntries，直接 return | `MasterSession.Empty` → 只返回 unlock 的 `AuthenticationAction` | ✅ 同（`unlocked.isEmpty()` 分支） |
| 通道互斥 | if/return 互斥 | 同 | ✅ 同（锁定即 return） |
| 部分锁定 | —（单账号） | — | ✅ 凭据 + unlock 动作并存 |
| 补偿重试 | **无** | **无**（仅 UI 侧 800ms 最小处理时长） | 无（对照后确认**不需要**） |

> Keyguard `PasskeyTargetCheck` 同样是**严格匹配无回退**；Bitwarden 的
> `filterAllowedCredentialsIfNecessary` 在列表为空时不过滤。Vaultix 采用
> 「Bastion 式回退」，比两家都更宽容，但方向是「宁可多列，不可漏列」，正确。

### 测试

- [x] `WebAuthnTest`：新增 **signCount 恒 0 回归锁**（断言流程末 4 字节必须 `{0,0,0,0}`，
      大端解析同 0）。
- [x] `VaultixCryptoTest`：新增 4 条回归测试 —— ISO10126 残留填充剥离 / 严格 PKCS#7 剥离 /
      **非严格填充必须原样返回**（防误伤）/ `decryptToString` 必须 trim。

⏳ **真机待验证**：装新包 → 在 Edge 触发通行密钥登录 → **候选列表应出现条目**；
若仍为空，`logcat` 抓 `VaultixAutofill` tag 的
`GET resolve rpId=... total=... unusable=... rpIdMiss=... allowedMiss=... matched=...`
逐级计数（本轮已埋点），四个计数一出来即可判定卡在哪一级。

## 第三十轮 2026-09-11 · 通行密钥「Authentication failed」根因

> 用户报：候选列表已能出现（第二十九轮的 discovery 修复生效），但站点仍报
> **Authentication failed** —— 这是断言（登录）阶段的**验签失败**。

### 根因：provider 与 RP 看到的 `clientDataJSON` 不是同一份

- 系统只把 **32 字节 `clientDataHash`**（浏览器那份 clientDataJSON 的 SHA-256）交给 provider，
  **不给明文**；
- 网页交给 RP 的是**浏览器自己拼的那份** JSON，RP 用它重新哈希后与签名里的哈希比对。

旧实现（`WebAuthn.buildClientDataJsonForBrowser`）试图「逐字节复刻浏览器的 clientDataJSON」
再回传，还按哈希在 `[无 crossOrigin, 有 crossOrigin]` 两个候选里**反选** —— 这条路不可能
稳定成功：字段集与字段顺序由浏览器版本决定（Chromium 可能带 `tokenBinding` 等），
provider 无从保证命中；一旦不命中就 `?: candidates.first()` 回退到自造变体 ⇒ RP 哈希必然
对不上 ⇒ 验签失败。

**官方逐字口径**（`developer.android.com/identity/sign-in/credential-provider`）：
> use the `clientDataHash` that's provided directly in `CreatePublicKeyCredentialRequest()`
> or `GetPublicKeyCredentialOption()` instead of assembling and hashing clientDataJSON during
> the signature request. **To avoid JSON parsing issues, set a placeholder value for
> `clientDataJSON` in the attestation and assertion response.**

### 修复（`90d5e6d`）

- [x] `WebAuthn.BROWSER_FLOW_CLIENT_DATA_PLACEHOLDER`（空字节数组）作为浏览器流程的
      clientDataJSON；**删除** `buildClientDataJsonForBrowser`（错路产物）；
      `clientDataJsonMatchesHash` 保留作诊断并注明浏览器流程下 `false` 是**预期**。
- [x] `PasskeyGetActivity.sign()`：有 `clientDataHash` → 签名覆盖 `authData ‖ clientDataHash`、
      回传占位符；无 → 自己拼 JSON、自己哈希、自己签、回传同一份 JSON。
- [x] `PasskeyCreateActivity.createPasskey()`：浏览器流程同样回传占位符。
- [x] 纠正三处已证伪的 KDoc（`buildClientDataJson` / `buildClientDataJsonForBrowser` /
      两个 Activity 的 `browserFlow`）。

### 三家对照（关键：两家是反例）

| 实现 | 浏览器流程如何处理 clientDataJSON |
|---|---|
| **Bitwarden ✅** | 从不在 Android 侧重建，交给 SDK（`ClientData.DefaultWithCustomHash(hash)` / `DefaultWithExtraData(androidPackageName)`）；`Fido2PublicKeyCredential.clientDataJson` 可空 |
| Keyguard ❌ | 重建（`PasskeyProviderGetRequest.kt:119-159`） |
| Bastion ❌ | 重建（`PasskeyAuthActivity.createClientDataJson`） |

> **Bastion 是本项目主要参考对象，但这一处不能跟。参考项目的"多数"不等于正确。**

### 测试（沙箱无 Android SDK，用 Gradle 自带 kotlin-compiler-embeddable 2.2.21 在真实源码上跑）

- [x] 真实 `WebAuthnTest`：**10/10 通过**，其中新增 3 条回归锁：
      `browser flow signs provided hash and returns placeholder clientDataJSON` /
      `native flow returns the very clientDataJSON it signed over` /
      `create response carries placeholder in browser flow and real json otherwise`。
- [x] 独立验证程序 20 项断言全绿，含**反证旧路**：浏览器多带一个字段（`tokenBinding`）⇒
      两个自造候选 `match=false`，证明旧逻辑只会回退到错的那份。
- [x] detekt 阈值自检：4 个改动文件超 120 字符行长 = 0；`sign()` 111 行 < 150；
      `WebAuthn` fun 数 29 < 60。

⏳ **真机待验证**：装新包 → Edge 触发通行密钥登录 → **应当登录成功**。
若仍失败，`logcat` 抓 `VaultixAutofill`：
`PK assertion ready sigLen=... authDataLen=... cdjLen=0 browserFlow=true`（`cdjLen=0` 即占位符生效）。

## 已完成（第二十八轮 2026-09-11 · `8afac3d`）

> ⚠️ 本轮的两个结论已被上方「结论更正」修正，此处保留作历史记录。

- [x] 断言/注册的 BE/BS 置位（`0x05→0x1D` / `0x45→0x5D`）——**保留**（语义正确性）。
- [x] 响应 JSON 补 `clientExtensionResults:{}` + `authenticatorAttachment:"platform"` ——**保留**。
- [x] 实测排除三个疑似项（200 组随机 P-256 密钥重建签名 100% 验签通过等）——**结论有效**。
- [x] ~~signCount 改读库~~ → **已回退为恒 0**（见「更正二」）。
- [x] ~~「旧凭证必须重新注册」~~ → **已撤回**（见「更正一」）。

**CI 与产物（第二十八轮已验证）**：run `34549342318`（commit `8afac3d`）**23/23 步全绿**
（checkout / JDK17 / Gradle / detekt / 编码门禁 / keystore 解码 / Build Debug APK /
单测 / 发布 preview Release；`lint` 按配置 skipped）。产物 `app-full-debug.apk`
**31,118,791 字节**，`sha256=f2d134e9ca7a09c9a935a76ab8e39562728346733019259cd4eeb3e405feac76`。

> **沙箱环境备注（长期）**：Release 资产现已走 `release-assets.githubusercontent.com`，
> 沙箱对该域名的 fake-IP 解析（`198.18.0.22`）会返回 404/401。已在 `/etc/hosts` 固定
> `185.199.108.133`（实测可用；`198.18.x.x` 是 fake-IP 不可用），
> `github.com → 140.82.112.3`、`api.github.com → 140.82.113.6`、
> `raw.githubusercontent.com → 140.82.113.3`。
> 另注意：`api.github.com` 对本环境拿到的 `ghu_` 型 OAuth token 一律返回 **401**
> （token 只能用于 git 传输层，不能调 REST API）。因此查 CI 状态改为读网页端
> `/Chaniug/Vaultix/actions/runs/<id>/job_groups_batch` 与 job 详情页的
> `<check-step data-name/data-conclusion>` 属性。

## 已完成（第二十七轮 2026-09-10 · 设置页对齐 Bastion，`be8a5bf`）

> 逐项比对 Bastion `AutofillSettingsV2Screen`(1149) / `PasskeySettingsScreen`(666) /
> `SettingsScreen`(1925) 后补齐。

- [x] **三态状态卡**：未启用 `errorContainer` / 需注意 `tertiaryContainer` /
      正常 `primaryContainer`。「需注意」= 密码能填但通行密钥没开。
- [x] **`AutofillStatusChecker`**：`hasEnabledAutofillServices()` 判「有没有」+
      读 `Settings.Secure:autofill_service` 判「是不是我」；读不到时按「已启用」。
- [x] **通行密钥分组**：「已解锁库中：N 个」+ 凭据提供商状态 + 特性说明。
- [x] **填充行为组（真开关）**：严格匹配 / 允许子域名匹配 → `MatchConfig` 真参数。
- [x] 首页「数据」→「数据管理」+「其他 · 权限管理」；CP 副标题文案修正。


## 已完成（第二十七轮 2026-09-10 · 设置页对齐 Bastion）

> 上一轮 `fec4032`（`isUsablePasskey` 放宽为元数据检查）后，用户要求「设置页面对齐 bastion」。
> 逐项比对 Bastion `AutofillSettingsV2Screen`(1149) / `PasskeySettingsScreen`(666) /
> `SettingsScreen`(1925) 后补齐，提交 `be8a5bf`。

- [x] **三态状态卡**（移植 Bastion `AutofillSettingsV2Screen` 状态卡）：
      未启用 `errorContainer` / 需注意 `tertiaryContainer` / 正常 `primaryContainer`。
      「需注意」= 密码能填但通行密钥没开（Chromium 最常见的半残状态）。
      顶栏手动刷新 + `ON_RESUME` 自动刷新（系统设置开启后返回立即可见）。
- [x] **`AutofillStatusChecker`**（新增）：两步判定——
      ① `AutofillManager.hasEnabledAutofillServices()` 判「系统有没有启用任何服务」
      （单独用不够：选的是谁分不出来）；② 读 `Settings.Secure:autofill_service`
      按 `VaultixAutofillService` 类名子串匹配判「是不是我」。
      **读不到值时按「已启用」处理**：① 已确认有服务，把「ROM 隐藏设置项」误报成
      「未启用」会误导用户反复去系统设置确认。
- [x] **通行密钥分组**（设置 → 自动填充 → 通行密钥）：凭据提供商状态行 +
      「已解锁库中：N 个」+ 特性说明；Android 14 以下整组退化为版本说明。
      数量是排查「Edge 看不到通行密钥」的关键读数：**显示 0 即定位到同步/解析问题**。
- [x] **填充行为组（真开关，非装饰）**：`严格匹配 → MatchConfig.exactDomainOnly`、
      `允许子域名匹配 → MatchConfig.allowBaseDomainMatch`，在
      `VaultixAutofillService.buildResponse` 生效。浏览器填不出来时关掉严格匹配
      是成本最低的排查第一步。新增 `VaultixPreferences` 两个键（默认 true / false，
      与 Bitwarden 一致）。
- [x] **设置首页**：「数据」→「数据管理」；新增「其他 · 权限管理」
      （`Settings.ACTION_APPLICATION_DETAILS_SETTINGS`）。
- [x] **`credential_provider.xml` 副标题修正**：此前误用标题文案
      `setting_credential_provider`，新增专用 `setting_credential_provider_subtitle`。
- [x] 清理 `SettingsScreen.kt` 遗留未用 import（`StatusBarManager`/`ComponentName`/
      `graphics.drawable.Icon`/`Build`/`ContextCompat`/`AutofillTileService`，
      磁贴逻辑早年已迁到二级页）。

### 明确不对齐 Bastion 的三项（有意为之）
1. CP 文案：Bastion 写「通行密钥**和密码**设置」，与其 `credential_provider_config.xml`
   「CP 不处理密码」的注释自相矛盾 → Vaultix 保持「凭据提供商（通行密钥）」。
2. 跳系统设置：Vaultix 用 `CredentialManager.createSettingsPendingIntent()`（直达自家
   provider 开关），不下沉为 Bastion 的 `ACTION_SETTINGS`。
3. 不搬「黑名单 / 屏蔽字段 / 智能标题 / 通知时长 / 密码建议 / 影子校验 / 校验诊断」
   ——Vaultix 无对应能力，搬过来是点不动的假开关。

⏳ **真机待验证（装 `dev-be8a5bf` 的包）**：
① 设置 → 自动填充 → 顶部状态卡颜色是否与实际启用状态一致（**这是本次的核心读数**）；
② 「通行密钥 → 已保存的通行密钥」显示的数量 —— **若为 0，则 Edge 看不到通行密钥
是因为库里根本没解析出 fido2，而不是 CP 通道问题**；
③ 通行密钥仍不弹时，把「严格匹配」关掉再试密码填充；
④ 权限管理能否跳到系统应用信息页。


## 已完成（第二十六轮 2026-09-10 · getOrigin 语义纠正 → CI 全绿）

> 第二十五轮推 `de6ad9e` 后 CI 连续两次失败：`34495082792` 挂 detekt（`RomCompat.kt`
> 常量返回 + 超长行，已由 `3e23740` 清理）；`34495532104` detekt 已过但 **Build Debug APK
> 编译失败**：`CallingAppOrigin.kt:51:44 No value passed for parameter 'privilegedAllowlist'`。
> 本轮修掉这一条，CI run `34496366032` **全绿**。

- [x] **反编译真源定语义**：下载 `maven.google.com` 的 `credentials-1.6.0.aar`（634 KB），
      `javap -c -p` 逐条读 `CallingAppInfo` / `PrivilegedApp` / `SignatureVerifier` /
      `RequestValidationUtil`，得到 `getOrigin(allowList)` 的**确定分支**（不再靠猜签名）：
      `!isValidJSON → IllegalArgumentException`；`origin==null → return null`；
      `包名命中 && intersect(调用方指纹, 名单指纹) 非空 → return origin`；其余 → `IllegalStateException`。
- [x] **纠正关键误判**：allowList **不是可选占位参数，而是签名背书名单**；`signatures` 为
      **必填**且元素是**对象**（取 `cert_fingerprint_sha256`）。→ 空名单 / `[]` / `["FP"]`
      三种取巧写法**全部必抛异常**，必须填调用方**自己的真实签名指纹**。
- [x] **改为「自证式读取」**：新增 `ALLOW_LIST_TEMPLATE`（合法 JSON 结构）+
      `signingFingerprintOrNull()`（`SHA-256(apkContentsSigners[0])`，多签名者→null，
      对齐 Bitwarden `getSignatureFingerprintAsHexString`）；`originOrNull()` 用调用方
      自己的指纹拼「只含它自己」的名单读 origin —— 只做**来源过滤**，不做特权应用身份背书。
- [x] **⑦ 预留正解路径**：`trustedOriginOrNull(callingAppInfo, allowList)` 保留给将来接入
      「用户信任的应用名单」（即 Bitwarden `OriginManagerImpl` 三级回退的用户名单一级）。
- [x] 头部根因注释按反编译事实重写（此前那段「传放行任意包名的名单」的描述是错的，已删除）。
- [x] **CI run `34496366032` 全绿**：detekt ✓ / 编码门禁 ✓ / 签名解码 ✓ / Build Debug APK ✓ /
      单测（非阻塞）✓；预览包 `app-full-debug.apk`（`dev-d082e63`，31.09 MB）已发布。

⏳ **真机待验证（装 `dev-d082e63` 的包）**：
① Edge 聚焦登录框 → 应弹密码条目（**核心闭环**；`logcat -s VaultixAutofill` 的
`caller=com.microsoft.emmx origin=https://...` 中 origin 应不再是 `-`）；
② 同一站点有 passkey 时应一并出现；③ 点候选完成填充 / 通行密钥断言；
④ 系统设置 → 密码和账号 → Vaultix → 齿轮 → 应落在自动填充/凭据设置页（非库列表）；
⑤ 多库且部分锁定时应同时看到候选与「解锁 Vaultix」。

## 已完成（第二十五轮 2026-09-10 · CP「已启用但不弹」根因修复 + Bitwarden 逐行对齐）

> 用户真机状态：**系统里凭据提供商已启用，但 Edge 里密码与通行密钥都不弹**。
> 不抓日志、直接按 Bitwarden 官方实现逐行比对 → 找到多条代码级缺陷，全部修复。

- [x] **★ 总根因之二：`androidx.credentials` 1.3.0 → 1.6.0**（对齐 Bitwarden 1.6.0）
      —— 1.5.0 才引入「凭据选择二级 UI 体验」（聚焦输入框时向 Credential Manager 下发请求
      + 下拉/键盘建议聚合），Chromium（Chrome/Edge）在 Android 14+ 呈现凭据条目**正依赖该机制**；
      停在 1.3.0 → 系统不在聚焦时派发请求 → 「已启用但毫无反应」。
      原先锁 1.3.0 的理由（「1.6.0 把 callingAppInfo.origin 收紧」）已由 1.6.0 的官方替代
      `isOriginPopulated()` + `getOrigin()` 消解。
- [x] **调用来源读取兼容层**：新增 `CallingAppOrigin`（`isOriginPopulated()` + `getOrigin()`
      安全包装，全 runCatching），替换 `PasskeyGetActivity` / `PasskeyCreateActivity` 里
      对 `callingAppInfo.origin` 的直读（1.6.0 起该属性为 internal）。
- [x] **entry 能力补全**：`PasswordCredentialEntry` / `PublicKeyCredentialEntry` 构造补
      `setAutoSelectAllowed`（仅单候选时允许自动选中）+ 按需 `setBiometricPromptData`；
      新增 `RomCompat`（HyperOS/MagicOS 判定）—— **魔改 ROM 上不挂**，否则系统可能
      渲染阶段丢弃整个 entry（仍是「什么都不弹」，比不挂更糟）。
- [x] **取消监听**：`onBeginGetCredentialRequest` / `onBeginCreateCredentialRequest` 补
      `cancellationSignal.setOnCancelListener`（对齐 Bitwarden `processGetCredentialRequest`）。
- [x] **密码条目按来源过滤**：复用 `BitwardenLikeAutofillMatcher`（与老 autofill 同规则），
      浏览器场景只用 origin 不用包名；来源不可得时不过滤（宁可多列不可漏列）。
- [x] **部分库锁定的解锁引导**：多库场景下若有库未解锁，凭据通道与认证动作通道**并存**
      （用户可就地解锁其余库，无需先清空候选）。
- [x] **⑥ provider settingsActivity**：新建 `CredentialProviderSettingsActivity`
      （承载 `AutofillSettingsScreen`），`credential_provider.xml` settingsActivity
      由 `MainActivity` 改指此处（系统凭据管理器的「管理」入口语义）。

⏳ **真机待验证（装含本轮修复的包）**：
① Edge 聚焦登录框 → 应弹密码条目（**核心闭环**）；② 同一站点有 passkey 时应一并出现；
③ 点候选完成填充 / 通行密钥断言；④ 系统设置 → 密码和账号 → Vaultix → 齿轮 → 应落在
自动填充/凭据设置页（非库列表）；⑤ 多库且部分锁定时应同时看到候选与「解锁 Vaultix」。

## 已完成（第二十四轮 2026-09-10 · Credential Provider 集成闭环 + autofill 打磨）

> 跨度 dba5ae1→f474654，均编译 + detekt + 单测全绿，已推 main 并出 CI preview 包。

- [x] **★ 总根因修复（f474654）**：manifest `<service>` intent-filter action 误写
      `android.credentials.CredentialProviderService`（漏 `service.` 段）→ 系统从未发现
      Vaultix 是 Provider → 无启用项 / `credential_service` 恒空 / Edge 永不弹 / passkey
      查不到。修为 `android.service.credentials.CredentialProviderService`。
      **教训：CP 注册逐字符对照官方模板，勿凭记忆**
- [x] **设置页「凭据提供商」状态行（e92d215）**：读 Secure `credential_service`，ON_RESUME
      刷新；跳转改用 `CredentialManager.createSettingsPendingIntent()` 直达启用界面
      （老 `REQUEST_SET_AUTOFILL_SERVICE` 与凭据提供商是两个独立设置项，部分设备无反应）
- [x] **CP 集成 ①–④（98edb37 + 8ba40d9）**：系统注册 + 通行密钥查询/创建 + inline 候选；
      双能力声明（`TYPE_PUBLIC_KEY_CREDENTIAL` + `TYPE_PASSWORD_CREDENTIAL`）；
      `buildGetResponse` / `PasswordGetActivity` 回灌密码凭据；锁定态按选项类型生成 Entry
- [x] **老路认证回灌时序（f474654 + 64ade7a）**：`MODE_COPY_TOTP` 认证宿主 `setResult`
      从 onCreate 推迟到 onResume（onCreate 同步会丢认证结果 → 「验证码复制成功但密码没填」）；
      解锁桥解锁后自动 finish 回原 App，不再停在 Vaultix 主界面
- [x] **认证宿主闪回修复（dba5ae1 + e52779e）**：宿主 Activity 独立 `taskAffinity`，
      NEW_TASK 不再复用后台 MainActivity 栈（修「Via 点密码条目闪回 Vaultix」，用户已确认
      不闪）；认证回灌去 NEW_TASK/singleTask，引导型意图由调用点显式加 NEW_TASK
- [x] **MODE_UNLOCK 原地生物解锁（3c7c0b8）**：库锁定且已启用本地快速解锁 → 弹
      BiometricPrompt 免开主界面，认证通过即解封全部库并 finish 回原 App；无本地解锁
      回退引导卡片走主密码
- [x] **搜索框乱弹抑制（b9a1d6e）**：字段分类引入 `SignalStrength`（HIGH/MEDIUM/LOW），
      无密码框时弱信号 USERNAME 不再触发登录候选（对齐 Bastion `AutofillDetectionPolicy`）
- [x] **文档收口（20680df）**：.ai 接力记忆同步（CP 总根因 + 真机验证清单 + 新坑索引）

⏳ **真机待验证（用户装含 f474654 的包，第一优先）**：
① 设置页点「凭据提供商」→ 弹系统启用 Vaultix 界面；② 启用后 Edge/Chrome 登录弹
密码条目 + passkey（**核心闭环验证点**）；③ 开 autoCopyTotp 在 Via 填充 → 密码应能填进
（onResume 时序修复）；④ 搜索框不应再乱弹密码条目。

## 已完成（第二十三轮 2026-09-09 · 磁贴/应用列表修复 + TOTP 链路 + 通行密钥保真）

> 用户拍板：**无障碍兜底不做**（磁贴已覆盖大部分场景）。

- [x] **磁贴不显示**：补 `onTileAdded` / `onStartListening`（写 label/icon/state）+
      `requestListeningState`；设置页那行改为可点击——API 33+ `requestAddTileService`
      弹系统添加框，低版本给手动添加说明（快捷设置排列归系统，应用无开关权）
- [x] **添加 App 读不全**：Manifest 补 `<queries>`（MAIN+LAUNCHER），
      解决 Android 11+ 包可见性过滤；不申请 `QUERY_ALL_PACKAGES`
- [x] **验证码识别**：`HintClassifier` 补 Bastion `isOtpHint` 词表（otp/2fa/验证码/一次性…）
- [x] **纯 2FA 页面**：`FillPlanner` 新增「只有验证码框」分支（此前一条建议都不出）
- [x] **填充后自动复制验证码**：`FillSuggestion.totpSecret` + `MODE_COPY_TOTP`
      （dataset 级 `setAuthentication`，API 26+）+ `VaultixClipboard`；开关 `autoCopyTotp`
- [x] **手动填充**：通知加「复制验证码」动作；验证码总览页改用 `VaultixClipboard`
      （此前 `LocalClipboardManager` 无 IS_SENSITIVE、不自动清除）
- [x] **上限保护**：FillResponse 最多 10 条 dataset（Binder 限制）
- [x] **通行密钥 P0**：`mapFido2` 补齐 13 字段（修复编辑条目清空服务端密钥材料的
      不可逆破坏）+ `creationDate` 用 `decryptOrPlain`；新增 2 例回归测试
- [x] full flavor 编译 + detekt 0 违规 + 单测全绿（data:bitwarden 亦全绿）

## 已完成（第二十二轮 2026-09-09 · C 自动填充保存流程 `onSaveRequest`）

- [x] **`SaveInfo` 接线**（根因）：FillResponse 挂 `AutofillSaveInfo`（账号+密码框为
      requiredIds）；**无匹配 fallback 分支同样挂**——没匹配项才是最需要保存的场景
- [x] **`AutofillSaveMatcher`**（纯函数 + 9 例单测）：目标 URI 归一（网页 `https://host` /
      App `androidapp://<pkg>`）、「同基域 + 同账号」→ 更新判定、默认名称（域名去 www /
      应用名 / 包名）
- [x] **`AutofillSaveActivity/ViewModel`**：透明卡片确认页，新建 / 更新二合一；
      更新时把新网址并入条目 uris（Bitwarden 同款，防同账号多域名重复条目）；
      库未解锁只引导解锁，**不暂存明文**
- [x] 开关 `VaultixPreferences.autofillSavePrompt`（默认开）+ 设置页「保存提示」行
- [x] full flavor 编译 + detekt 0 违规 + 单测全绿

## 已完成（第二十一轮 2026-09-09 · 快捷入口三件套：磁贴 + 手动填充 + 智能复制接力）

- [x] **键盘内联建议降级（用户拍板）**：`InlinePresentation` 依赖输入法实现 Android 11+
      IME inline suggestions API，国产输入法（搜狗/百度/讯飞/QQ/微信）基本未接入 → 不做
- [x] **Quick Settings 磁贴**（`AutofillTileService`，BIND_QUICK_SETTINGS_TILE + ACTIVE_TILE）
- [x] **手动填充界面**（`ManualFillActivity/ViewModel`）：跨库聚合已解锁登录条目 + 搜索 +
      未解锁引导；选条目即复制密码并自动回原 App
- [x] **智能复制接力**（`SmartCopyNotifier/Receiver`）：复制密码后通知栏留「复制用户名」
      （60s 超时、VISIBILITY_SECRET、复用 `VaultixClipboard` 自动清除与 IS_SENSITIVE）
- [x] 设置页自动填充组加「快速填充磁贴」说明行（可发现性）
- [x] 三件套**全程不依赖无障碍权限**（用户判断：多数场景无需无障碍即可解决）
- [x] full flavor 编译 + 全模块 detekt 0 违规 + 全项目单测 0 失败
      （offline flavor 自本轮起暂停参与构建，见「只构建/发布 full」决策）

## 已完成（第十八轮 2026-09-09 · 编辑密码查看修复 + 批次④ 设置页对齐）

- [x] **修复「编辑条目看不到密码」**：编辑表单密码框此前只有随机生成按钮（无明文
      查看开关），密码始终掩码圆点——唯一能明文看到的反而是骰子生成的随机密码；
      补 Bitwarden 编辑页同款眼睛 toggle（新建/编辑均可用，`153da7a`）
- [x] **主题模式三态**（对齐 Bastion themeMode）：VaultixPreferences.themeMode
      （system/light/dark）+ ThemeMode 枚举 + VaultixTheme 接线（MainActivity 收集，
      实时切换）；设置页「外观」组新增单选对话框
- [x] **OLED 纯黑**（对齐 Bastion oledPureBlackEnabled）：深色模式 surface/background
      纯黑（对动态取色与固定色板同样生效）；设置页开关
- [x] **回收站自动清理档位收编设置页**：TrashAutoDeleteDialog 提升到 ui.common
      （回收站顶栏与设置页共用，同一偏好键即改即生效）；新「数据」分组
- [x] **偏好默认值真值源**：core:datastore 新 `VaultixPreferencesDefaults`
      （TRASH_AUTO_DELETE_DAYS=30 / THEME_MODE=system），UI 层 stateIn 初始值不再硬编码
- [x] **对照结论（Bastion SettingsScreen 8 组 vs Vaultix M1）**：已落地=安全（自动锁定/
      剪贴板/防截屏/快速解锁/立即锁定）+ 外观（主题/OLED/动态色）+ 数据（回收站档位）+
      关于；**推迟 M2**=自动填充服务全套（Vaultix M1 未做系统填充）、导入导出/备份、
      开发者日志、界面布局定制、WebDAV/OneDrive 同步、清理全部数据
- [x] 双 flavor 编译 + 全模块 detekt 0 违规 + 全项目单测 0 失败；提交推送 `origin/main`

## 已完成（第十七轮 2026-09-09 · Bastion 对齐·批次③ 回收站自动清理）

- [x] **清理策略纯函数**（core:common 新文件 TrashCleanupPolicy.kt）：对齐 Bastion
      TrashViewModel 语义（cutoff = now - days、剩余天数 = max(0, days - 整除天数)、
      0 = 不自动清空；Vaultix 不提供 Bastion 的 -1 禁用档）；ISO-8601 容错解析
      （本地毫秒 / 服务端微秒-纳秒均可），不可解析恒不清理（宁多留不误删）
- [x] **数据链路**：domain 新 `TrashEntry(item, deletedDate)`，`observeTrash` 改返回
      TrashEntry（本地软删只改列不重写密文，删除时间必须从行级取，DTO 内不可靠）；
      CipherDao 加 `getTrashByVault` 一次性快照
- [x] **到期清理**：`ItemRepository.cleanupExpiredTrash(vaultId, days)`——过期行走
      permanentDeleteItem 同口径（DELETE 入队 → 删本地行 → flush），离线联网补推保证
      服务端同步删除；失败静默 0 不打断 UI；days<=0 零副作用
- [x] **设置**：VaultixPreferences `trashAutoDeleteDays`（默认 30 天）；回收站顶栏
      Settings 图标 → 档位对话框（从不 / 7 / 30 / 90 天，即选即存）
- [x] **清理时机 + UI**：进入回收站即执行到期清理（Bastion cleanupExpiredItemsNow
      同位），实删 snackbar 报数；行内「N 天后自动清理」倒计时（剩 0 天红色
      「即将自动清理」；档位 0 不显示）
- [x] **单测**：TrashCleanupPolicyTest 9 例（cutoff 边界 / 倒计时整除 / 0-负档 no-op /
      三种 ISO 格式解析）；ItemRepositoryImplTest 增 4 例（只删过期 / 0-负档零副作用 /
      DAO 异常静默 / observeTrash 带 deletedDate）；双 flavor 编译 + 全模块 detekt
      0 违规 + 全项目单测 0 失败；提交推送 `origin/main`

## 已完成（第十四轮 2026-09-08 · Bastion 对齐·批次② 验证码五类型 + 批量导入）

- [x] **OtpType 五类型引擎**（core:common Totp.kt）：`OtpType(TOTP/HOTP/STEAM/YANDEX/MOTP)`；
      迁入 Bastion 三生成函数——HOTP（RFC 4226，counter 驱动）、Yandex（委托标准 TOTP）、
      mOTP（MD5(epoch/10+secret+pin) hex 取数字前 6 位补 0）；新增统一入口
      `TotpGenerator.generate(config)`（TotpCodesScreen 与 ItemDetailScreen 详情页共用）
- [x] **五类型 URI 解析与生成**：`otpauth://hotp(counter)` / `otpauth://yaotp(pin)` /
      `motp://issuer:account?secret=&pin=` / `encoder=steam` 识别（Bastion 口径）；
      `OtpUriParser.buildUri(config)` 五类型统一出口；**编码修复**：自研 RFC 3986
      uriEncode/uriDecode（对齐 android.net.Uri 语义，`+` 不转空格）——修复 Steam 等
      Base64 密钥含 `+ / =` 时被 URLDecoder 误解码的隐患，label 亦按 %XX 解码
- [x] **otpauth-migration:// 批量导入**（core:common 新文件 OtpImportParser.kt，纯 Kotlin）：
      手工 protobuf wire format（ProtoReader）+ OtpParameters 7 字段（secret/name/issuer/
      algorithm/digits/type/counter）+ Base64 容错（URL-safe `- _`、form 空格、缺 padding）
      + base32Encode；`OtpScanResult{Single/Multiple/UnsupportedPhoneFactor/InvalidFormat}`
      四分支分发（含 phonefactor:// 显式不支持、裸密钥单条）；剥离 GA name 的
      `Issuer:` 前缀（带/不带空格两种形态）
- [x] **编辑器类型选择**（TotpEditDialog）：类型下拉（五类型）+ 条件字段——HOTP 显
      counter、mOTP 显 PIN（固定 10s/6 位口径在 buildTotpConfig 收敛）、Steam 隐藏参数、
      mOTP 密钥字段标签区分；TotpEntry 贯穿 type/counter/pin（steam 改计算属性）
- [x] **列表行为**：HOTP 行显示「计数器 N」不显示倒计时/进度条；mOTP 按 10s 步长滚动
- [x] **导入入口**：验证码界面顶栏「导入」→ 粘贴对话框；单条预填编辑确认，批量直接
      创建 + snackbar 报数，phonefactor/无效内容提示原因（ImportDialogWithOutcome）
- [x] **单测**：HOTP RFC 4226 附录 D 十向量 + 8 位同源校验；mOTP 规格断言；五类型
      buildUri↔parse roundtrip（含 Steam Base64 `+/=` 编解码无损）；migration 手工构造
      protobuf roundtrip（TOTP+HOTP 双条/大 counter varint/SHA256+8 位/URL-safe 容错/
      各分发分支）；core:common 60 例全绿（全项目 339 例 0 失败）
- [x] 双 flavor 编译 + 全模块 detekt 0 违规（拆 LongMethod/复杂度超限函数、表驱动字段
      解析、MagicNumber 常量化）；提交推送 `origin/main`

## 已完成（第十三轮 2026-09-08 · TOTP 统一界面 + 通行密钥只读列表 + 入口 hub）

- [x] **TOTP 统一界面（对齐 Bitwarden 总览 + Bastion 验证器视图）**：从所有登录条目的
      `login.totp` 归一化展示，区分「已绑定（随密码条目）/ 独立（空密码登录条目，Bitwarden
      官方兼容形态）」两类；实时滚动刷新 + 进度条 + 点按复制；搜索发行方/账号；编辑/删除；
      独立项可「绑定到密码条目」合并后删除
- [x] **Steam 验证码**：Totp 引擎 Base64 密钥 + HMAC-SHA1 + 26 字符专属字母表 + 固定 5 位码；
      otpauth 解析按 issuer/label/algorithm 识别 steam；core:common 单测 5 例覆盖
- [x] **通行密钥只读列表（PasskeysScreen + SavePasskeyDialog）**：拉平所有登录条目的
      fido2Credentials 展示；**只读（仅查看/删除）** 不可自建；保存（绑定）时强制挂到所选
      登录条目，对齐 Bitwarden 无独立通行密钥条目形态（CipherMapper 已支持 fido2 逐字段加密写回）
- [x] **入口 hub**：ItemsScreen 顶栏新增 TOTP 入口；VaultixApp 新增 TotpCodes/Passkeys 路由；
      验证码界面顶栏「通行密钥」按钮进入 PasskeysScreen
- [x] **搜索 + 删除**：TOTP 界面、通行密钥界面均含搜索与删除；密码条目入口沿用既有能力
- [x] 修复编译与 detekt 0 违规门禁（超长行拆分、MagicNumber 常量化、UnusedParameter、
      updateItem 补 vaultId、补 passkey_field_user 字符串与必要 import）；双 flavor 编译 +
      全模块 detekt（0 违规）+ 受影响模块单测（core:common / data:bitwarden 全绿）
- [x] 提交并推送 `origin/main`（5e652ce）

## 已完成（第十轮 2026-09-08 · Bitwarden 对齐审计 + 批 1）

- [x] **对齐审计**（Docs/progress/audit/bitwarden-alignment.md）：Vaultix bitwarden 侧
      vs Bastion reference 全量差距表 + 批次划分（M1-1..7 做 / M2-1..3 推迟 / 不做）
- [x] **编辑丢数据修复（M1-1/2）**：DTO 全载荷承载（card/identity/sshKey/secureNote/
      uri/totp/fido2 对齐 Bastion 字段集）；更新 = 合并上传（toUpdateRequest 只
      重加密可编辑明文，未编辑密文段原样并入）
- [x] **类型保真（M1-3）**：type5=SshKey 显式建模；写路径类型守恒守卫拒写未知类型
      （防 type 漂移）；条目列表/详情类型徽标；非 Login 编辑只开放名称/备注并提示
- [x] **同步收敛与毒丸处理（M1-5/6）**：全量成功后 prune 服务端已移除行（排除
      pending ops）；flush 4xx（401/408/429 除外）弃单
- [x] 单测新增（mapper 载荷保真 4 例 + repository 类型守卫 1 例）；双 flavor 编译 +
      Hilt + 各模块单测 + detekt 全绿
- [x] **回收站视图（S19，批 1 后追加）**：domain/data 新增 observeTrash /
      restoreItem / permanentDeleteItem（本地先行 + RESTORE/DELETE 入队补推，
      4xx 弃单）；条目页顶栏回收站入口 → TrashScreen（恢复 / 永久删除二次确认 /
      空态 / 类型徽标）；observeItem 对已删行保持 null 语义；repository 3 例新单测
- [x] 文档同步（MEMORY / decisions / SESSION / audit 报告）

## 已完成（第十二轮 2026-09-08 · WIP 推送 + 编译膨胀审计 + 编译器健康）

- [x] **WIP 推送（76979ac）**：指纹按钮归位 / 条目字段补全(uri·totp·fido2) /
      TOTP 引擎 / 卡包·SSH 读取打通，共 13 文件，已推 `origin/main`
- [x] **编译膨胀审计（排查"编译器过大无法编译"）**：扫描 674 个函数 + 全模块 detekt
      —— 代码已在质量门禁内（最大源函数 ≤153 行、无超 JVM 方法上限、配置缓存 CI 已开）；
      **唯一真实风险 = Gradle/Kotlin daemon 仅 2GB 堆**（ISSUES #14 同根因）
- [x] **构建堆修复（`gradle.properties`）**：`org.gradle.jvmargs` 2g→4g；新增
      `kotlin.daemon.jvmargs=-Xmx4g -XX:ReservedCodeCacheSize=512m`（Kotlin 编译
      daemon 独立 JVM，大模块生成字节码防 OOM）；注释锁死，防后人误改回 2g
- [x] **CI 单测盲区补位（`ci-debug.yml`）**：原单测只跑 core:crypto / app:testFull /
      data:repository，新加的 `:core:common`（TOTP/强度 16 例）、`:data:bitwarden`
      （CipherMapper 保真 28 例）从未在 CI 执行 → 纳入门禁（项目反复警告的"改了却
      验证不到"盲区）
- [x] 验证：双 flavor 编译 + 全模块 detekt（0 违规）+ 受影响模块单测（core:common 16 /
      data:bitwarden 28 全绿）

## 已完成（第九轮 2026-09-08 · 同步编排全链路接线 + 强度条 + Bastion 快照）

- [x] **Bastion 参考快照 vendored**：`reference/bastion/`（@369ed56，1012 文件/≈13 MB：
      主源码 664 + 单测 155 + repo docs + BastionDocs md + workflows 参考；只读、不参与
      构建/detekt）；接力 AI 无需访问 D:\Bastion；Docs/18 §2 与 .ai/MEMORY 已更新指针
- [x] **同步编排器（Bastion 语义移植，data:repository）**：触发分类/静默语义/
      90s·180s 节流/运行中合并回放/指数退避×5/per-vault 状态流/解锁门卫；
      单测覆盖（虚拟时间）；Hilt 双构造（@Inject 两绑定参数 + internal 五参测试构造）
- [x] **同步 UI 接线**：条目页提示条（运行中细进度条/警告常驻/手动成功短暂提示）
      由 orchestrator per-vault 状态派生；进页 PAGE_ENTER、手动刷新 MANUAL force；
      AutoLockController.onStart 回前台对已解锁库逐库 APP_RESUME；
      库列表卡片行内同步状态（同步中/同步失败；静默成功不打扰）
- [x] **密码强度条**：新建/编辑表单密码框下五档评分条（core:common PasswordStrength
      移植 Bastion，仅提示非门槛）
- [x] 双 flavor 编译 + Hilt 组件（full/offline）+ 各模块单测 + detekt 全绿

## 已完成（第八轮 2026-09-08 · Bastion 冻结决策与参考地图）

- [x] **决策落地（decisions.md）**：Bastion 冻结 = reference implementation，Vaultix = 唯一演进线；只搬三类资产、不做文件级搬迁
- [x] **新增 `Docs/18-Bastion参考地图.md`**：分里程碑参考索引（M1 同步核对 / M2 data:kdbx / M3 Autofill / 1.x）、别读别搬清单、对拍流程、GPL 溯源规范；README 导航与阅读路径已更新
- [x] 长期记忆同步（.ai/MEMORY.md、.workbuddy/memory/MEMORY.md、SESSION-2026-09-08.md）
- [x] M2 提示：`data:kdbx` 启动前通读 Docs/18 §4.3（Bastion KDBX 资产密度最高的参考站）

## 已完成（第六-七轮 2026-09-08 · 真机联调 + 签名修复 + 快速解锁 + 兼容）

- [x] **签名彻底修复（63be53c CI 全绿）**：Secrets env 注入 + PKCS12 keypass=storepass
      （详见 decisions/MEMORY）；preview 只发 **full 单包**（bbf843c）
- [x] **prelogin/同步兼容**：Vaultwarden camelCase 双形态；CF 后自托管请求头组
      （Bastion 同值：Chrome UA/Sec-Ch-Ua/Keyguard-Client/Bitwarden-Client-*）
- [x] **2FA 登录**（经典 OAuth 扩展）+ 多方式选择（TOTP/邮箱/Duo/YubiKey/org-Duo；
      YubiKey 44 位动态码输入）
- [x] **type 0 遗留密文默认放行**（官方/Bastion 对齐；修复「未命名」条目）
- [x] **设备登记 Header**（connect/token 带 device-type/identifier/name；type 0=Android）
- [x] **本地快速解锁（免主密码/免 2FA）**：Keystore user-auth KEK 包裹 + 生物识别/
      设备 PIN 解封；解锁页按钮 + 登录后启用横幅 + 设置页管理
- [x] 设置页（自动锁档位/剪贴板清除/防截屏/动态取色/立即锁定/快速解锁管理/关于）
- [x] 双 flavor 编译 + 单测 + detekt 全绿

## 已完成（第十五~十六段 2026-09-08 晚 · M1 字段对齐闭合 + Bastion 对齐第一批）

- [x] **领域补齐**：folderId/favorite/reprompt/secureNote 进 VaultItem，三处 Mapper 连通
      （拉取/新建/编辑；此前拉取即丢、编辑沿用 stored 等于不可修改）；4 例单测
- [x] **新建条目可选类型**（登录/银行卡/身份/安全笔记/SSH；此前 FAB 固定 Login）
- [x] **linkedId 修正为 Bitwarden 官方分段编码**（100/300/400 段；原 1/2/3/4 全错）
      + VaultLinkedId 枚举 + core:model 5 例锁定 + CI 补 :core:model
- [x] **自定义字段 4 类型编辑器**（fields 按表单意图写回；Boolean/Hidden/Linked/Text）
- [x] **收藏 + 主密码二次验证 UI**（标题行星标 / 附加选项开关）
- [x] **TOTP 相机扫码**（CameraX+ZXing；全屏 Dialog 内嵌，扫码回填不丢表单输入）
- [x] **文件夹选择**（FolderRepository 只读数据流 + FolderPicker，库无文件夹时隐藏）
- [x] **修复验证器「取消=删除」数据丢失 bug**（文案/动作错位；移除 error 色 + 取消并排）
- [x] **随机密码生成**（Bastion PasswordGenerator 生成核迁入，去 zxcvbn/Context；
      生成对话框：长度/字符集/排除选项；密码框 🎰 入口）+ 7 例单测
- [x] **修复表单「验证码下方区域不可见」**（AlertDialog 加 verticalScroll）
- [x] 提交并推送（e6b05d6…f0f5df6，CI 全绿）；真机 adb 已连（荣耀，logcat 无崩溃）

## ⚠️ 策略变更（2026-09-08 晚拍板）：Bastion 代码与 UI 分批整体搬入

保持 Vaultix 架构（多模块 / Bitwarden canonical / CipherDto 密文存储），
把 Bastion 设置/密码条目/验证码/通行密钥/卡包的代码与 UI 先搬过来再改。
**数据模型与 Room 明文表不搬**；包名/模型/DI/偏好键必替换；GPL 溯源必保留。
已完成第一批（密码生成器 + 表单滚动），后续批次见下。

## ★ 最高优先（2026-09-09 定）：Credential Provider 集成（Edge 填充 + 通行密钥都靠它）

背景：真机 dumpsys 实证——Android 14+/17 上 Chromium（Chrome/Edge）取凭据
**优先走 Credential Manager**；Bitwarden 注册了 `CredentialProviderService`
（`BIND_CREDENTIAL_PROVIDER_SERVICE`），Vaultix **没有** → Edge 连 fillRequest 都不发、
网站登录时通行密钥也不出现（passkey 数据都在，应用内 hub 已就绪，只是系统层没注册）。

> **🔴 总根因修复（2026-09-10 定）**：manifest `<service>` 的 intent-filter action
> **误写为 `android.credentials.CredentialProviderService`（漏 `service.` 段）**，正确为
> `android.service.credentials.CredentialProviderService` → 系统从未发现 Vaultix 是
> Provider → 系统设置无启用项、`credential_service` 恒空、Edge/Chrome 永不弹、
> passkey 查不到。此前所有「用户未启用」诊断（dumpsys 空 + 重装被清）均被此掩盖。
> 教训：Credential Provider 注册必须逐字符对照官方模板，勿凭记忆。
> 随附修复：①设置页「凭据提供商」行改用 `CredentialManager.createSettingsPendingIntent()`
> 直达启用界面（老 `REQUEST_SET_AUTOFILL_SERVICE` 与凭据提供商是两个独立设置项且部分设备无反应）；
> ②老 autofill MODE_COPY_TOTP 认证回灌从 onCreate 推迟到 onResume（onCreate 同步
> setResult+finish 认证结果会丢 → 「验证码复制成功但密码没填」）。

- [ ] **⑥（待做）provider settingsActivity**：`credential_provider.xml` 补
      `android:settingsActivity`（系统凭据管理器「密码和账号→Vaultix」里出现管理入口，
      对应 Keyguard/Bastion 的通行密钥专属管理页）；Activity 需处理未解锁态。
- [ ] **⑦（待做）privileged apps allowlist**：passkey origin 校验接入
      `CallingAppInfo.getOrigin()` + 白名单（可参考 Google gpm-passkeys-privileged-apps 列表）。

- [x] **① CredentialProviderService 注册**（DSP 主入口）：manifest `<service>`
      `android.service.credentials.CredentialProviderService` + `@xml/credential_provider`
      capability（`TYPE_PUBLIC_KEY_CREDENTIAL` / `TYPE_PASSWORD_CREDENTIAL`）
- [x] **② 通行密钥查询/创建**：BeginCreateCredential / BeginGetCredential 回调 →
      校验 origin（与条目 URI 匹配）→ 无锁提示解锁（PendingIntent 复用
      AutofillActivity 解锁链）→ 列出匹配 passkey（PasskeysViewModel 数据源）
- [x] **③ 密码凭据供应（8ba40d9，推翻原「passkey-only」假设）**：真机实证——启用
      provider 后 Credential Manager 接管登录字段并同时问「密码+通行密钥」；只声明
      public-key → 密码框为空、老 AutofillService 在凭据字段被绕过（即「启用 provider
      后 Edge 密码弹框消失」的根因）。现改为双能力供应：`credential_provider.xml` 声明
      `TYPE_PASSWORD_CREDENTIAL`；`buildGetResponse` 处理 `BeginGetPasswordOption` →
      已解锁库 Login 条目列 `PasswordCredentialEntry`；新增 `PasswordGetActivity` 透明确认后
      回灌 `PasswordCredential`（与 Bitwarden Android 14+ 行为一致）；锁定态「解锁 Vaultix」
      入口按选项具体类型生成对应 Entry
- [x] **④ inline suggestions**（API 30+）+ `AutofillInlinePlaceholderActivity`
      （Bastion 15 行 no-op）→ Via 等老路径把候选显示在键盘上方
- [ ] **⑤ 字段角色推断**：迁 Bastion `AutofillFieldRolePolicy`/`AutofillFieldPromotionPolicy`
      → 解决用户名框 UNKNOWN（只有密码框有条目）
- [ ] 依赖：`androidx.credentials:credentials`（catalog 已有，确认 app 引用）+ Play services 依赖评估
- [ ] 参考：Bitwarden 设备上 dumpsys 的 service 结构；Bastion autofill_ng 相关实现

**状态（2026-09-10 收口）**：①②③④ 已实现并推送（98edb37 / 8ba40d9）；**总根因已修
（f474654）**——此前一切「系统未启用 / 被清」诊断均被 action 误写掩盖。⑤ 部分落地
（b9a1d6e 信号强度分级抑制误弹），完整迁 `AutofillFieldRolePolicy` /
`AutofillFieldPromotionPolicy` 仍待做。⑥⑦ 见上（provider settingsActivity /
privileged allowlist）。
轻量解锁链：`AutofillActivity` 含 `MODE_COPY_TOTP` / `MODE_REPROMPT` / `MODE_UNLOCK`
回灌路径；「Via 点填充闪回 Vaultix 不回填」已由 dba5ae1 + e52779e + 3c7c0b8 修复
（用户已确认不闪）。永不加锁仍会锁（进程被杀清内存密钥）→ 参照 Bastion 生物解密管理：
登录后自动 enroll 本地解锁 + 回前台自动弹生物验证（B，待做）。
⏳ 待真机验证启用与填充闭环，清单见第二十四轮区块。

诊断全过程与三层根因详见 `.ai/MEMORY.md`「★ Edge/Chrome 填充失效根因」。

## Bastion 对齐批次（剩余）

- [x] **批次② 验证码条目对齐**（第十四轮完成）：OtpType 五类型（TOTP/HOTP/Steam/Yandex/MOTP）
      引擎；编辑器类型选择与 HOTP counter / mOTP pin 字段；**otpauth-migration:// 批量导入**
      （纯 Kotlin protobuf 解析，顶栏导入入口，单条预填/批量直建）
- [x] **批次③ 回收站对齐**（第十七轮完成）：自动清理策略（autoDeleteDays DataStore 设置 +
      进入即到期清理走 DELETE 入队保证服务端同步删 + 行内剩余天数倒计时 + 顶栏档位设置）
- [x] **批次④ 设置页**（第十八轮完成，M1 范围）：主题模式三态 + OLED 纯黑 +
      回收站档位收编（新数据组）；自动填充/导入导出/开发者/布局定制等 Bastion
      大件对照结论为 M2 推迟（见第十八轮区块）
- [x] **批次⑤ 通行密钥**（第十九轮完成，M1 范围）：列表/详情/删除/绑定保存已有；
      本轮补**详情创建时间展示 + 凭据 ID 复制**；Bastion 重载体（KeePass 绑定/同步/
      凭证发现）因 Vaultix 无 KeePass 层**不照搬**，详见 bastion-parity-assessment.md
- [x] **批次⑥ 卡包**（第十九轮完成，M1 范围）：搬运 Bastion `CardBrandDetector`
      （GPL 溯源保留，core:common 纯算法）→ 详情识别品牌 + 卡号分组、表单保存自动
      回填品牌；Bastion 专属卡面可视化/账单地址/文档卡为 M2 可选

## P0 · M1 收尾（剩余）

- [ ] **真机回归（待用户，装 f0f5df6 preview）**：字段对齐验收（新建选类型→保存→
      官方端对拍 folder/favorite/reprompt/自定义字段 4 类型是否真写回）；
      **linkedId 修复验收**（Linked 字段显示所指字段名与值）；TOTP 扫码；**验证器
      取消不再删除**；随机密码生成；**先去回收站恢复此前被误删的验证码**；
      表单可滚动到底（验证码下方区域可见）；**回收站自动清理验收（批次③）**：
      顶栏档位改 7 天后新建删除条目（倒计时显示）、改档位「从不」倒计时消失；
      **设置页验收（批次④）**：主题模式切深色/浅色即时生效、OLED 纯黑深色下背景变纯黑；
      **编辑密码查看验收**：编辑已有登录条目点眼睛图标可明文核对原密码；
      **通行密钥验收（批次⑤）**：详情展示创建时间、凭据 ID 可复制；
      **卡片品牌验收（批次⑥）**：卡号自动识别品牌（如 Visa/Mastercard）并卡号分组显示；
      **快捷入口验收（第二十一轮）**：下拉快捷设置添加「快速填充」磁贴 → 点磁贴选条目
      → 密码已复制（粘贴到登录框）→ 点通知复制用户名；
      **永不锁定验收**：设置自动锁定为「从不」，息屏再亮屏 / 锁屏解锁后仍保持解锁；
      **自动填充验收**：Chrome / Edge / 三星浏览器分别触发填充（Edge 为重点）；
      **关联 App 验收**：条目编辑「关联应用」→ 选中 App → 详情显示「应用」+ 包名 + 可启动；
      **保存提示验收（第二十二轮）**：全新站点/App 登录 → 弹出保存卡片 → 库里出现新条目
      （带网址或 `androidapp://`）；已存账号改密码登录 → 弹「更新密码」→ 条目密码被更新；
      **磁贴验收（第二十三轮）**：设置 → 自动填充 → 「快速填充磁贴」点击后是否弹出添加框 /
      手动添加后磁贴是否正常显示（不再空白）；
      **关联应用列表验收**：条目编辑「关联应用」→ 列表是否完整（Android 11+ 需 `<queries>`）；
      **验证码验收**：带 TOTP 的条目填充后是否自动复制验证码；纯验证码页面是否能填；
      验证码总览页复制后是否按设置时长自动清除；
      **通行密钥验收**：详情是否显示创建时间（此前恒为「—」）
- [ ] **M2 规划**：见 `Docs/progress/bastion-parity-assessment.md`（差距约 40% 可比覆盖；
      最大两块缺口 = 系统自动填充服务 + 数据导入导出，均架构级工作量）
- [ ] 回归通过 → M1 close-out（文档归档 + 下一里程碑规划）

## P2 · 自动化（WorkManager 周期同步归位）

- [ ] **WorkManager 周期同步 + 网络约束（推迟理由见 decisions「周期同步 M1 判推迟」）**：
      进程存活时 APP_RESUME/PAGE_ENTER 已覆盖「打开即最新」；进程被杀后会话密钥在
      内存（重启必锁），周期任务无可同步内容 → 收益≈0。编排器 PERIODIC 触发已预留，
      P2 与本地 KDBX 队列推送一并设计时再启用

## P1 · 质量基础设施（剩余）

- [ ] Baseline Profile；Gradle 配置缓存；ViewModel 单测；:app 单测用例

## P2 · 自动化

- [ ] WorkManager 周期同步 + 网络约束（`Docs/17` §3.3：禁止常驻轮询）

## P3 · M2（KDBX）

- [ ] `data:kdbx` 引擎（kotpass），按 `Docs/02` §3.4 做往返保真度测试

## 已知未决（接力者注意）

- 快速解锁 payload 在 SecureCredentialStore（key `local_unlock_key::<vaultId>`），
  开关在 DataStore；删除 KEK 仅由系统指纹变更触发（单库 disable 只删 payload+开关，
  多库时 KEK 共享保留——未来多库需「全库清空」入口）
- 同一服务器仅一个账号（vaultId=server）；设备 id 存 SecureCredentialStore（卸载即换，
  服务器端会累积旧设备记录，属正常）
- Detekt 用 2.0.0-alpha.6；2.0 稳定后升级重新生成默认配置核对

## 已完成（第三轮 2026-09-08 · 质量门禁）

- [x] **Detekt 门禁（P1）**：`dev.detekt` 2.0.0-alpha.6（官方兼容表对齐
      Kotlin 2.4.10 / AGP 9.3 / Gradle 9.5）；根工程统一为全部 Android 模块开启，
      阈值在 `config/detekt/detekt.yml`（对齐 Docs/16 硬上限：
      LongMethod ≤150 行 / LargeClass ≤1200 / 参数 ≤8 / 单类函数 ≤40），
      Compose 函数命名与命名参数数字做政策级豁免
- [x] 存量违规清零（crypto 有意捕获用 @Suppress+理由注释、网络超时常量化、
      ItemDetailScreen 拆分降圈复杂度、RepositoryModule→interface、
      ItemRepositoryImpl 解密调度器注入化）
- [x] CI：push/PR 均执行 `gradlew detekt`（--no-configuration-cache）；
      `config/**` 纳入触发路径；单测补充 :data:repository
- [x] `./gradlew detekt` 全模块（main+test）绿

## 已完成（第二轮 2026-09-08 · 详情/编辑/删除 + 自动锁定）

- [x] 条目详情页（S9 最小版）+ 敏感复制（剪贴板自动清除，Bastion 思路 +
      GPL 溯源）+ 编辑（S10 最小版，updateItem 沿用原 id）+ 软删除（回收站语义，
      SOFT_DELETE 入队 + 轻量推送）
- [x] 自动锁定（AutoLockController：切后台计时 elapsedRealtime、超时 lockAll +
      回根导航）
- [x] data:repository 新增 6 个写路径/解密流单测（共 11 个）；双 flavor 编译通过

