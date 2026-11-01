# 03-通行密钥与凭据提供商 · 第 1 篇（#24 ~ #33）

> 主篇索引：[`03-通行密钥与凭据提供商.md`](./03-通行密钥与凭据提供商.md)（含计数规则与重号警告）
> 本篇 8 章 · 13.4KB · 覆盖 #24 ~ #33

## 24. Credential Provider manifest action 漏 `service.` 段 → 系统从不发现 Provider（2026-09-10，总根因 P0）

- **现象**：系统设置无「启用 Vaultix 凭据提供商」项、`credential_service` 恒空、
  Chrome/Edge 永不弹密码与 passkey、passkey 查不到（数据都在，纯系统层缺失）
- **根因**：intent-filter action 误写 `android.credentials.CredentialProviderService`
  （漏 `service.` 段）；正确 = `android.service.credentials.CredentialProviderService`
- **解法**：逐字符对照官方模板修正 action；同时注意权限
  `android.permission.BIND_CREDENTIAL_PROVIDER_SERVICE`、meta-data 名
  `android.credentials.provider`、provider.xml 根标签 `<credential-provider>`
- **判据**：真机 dumpsys 该 provider 应出现在系统凭据服务列表；
  **教训：此类系统级注册任何字段都不得凭记忆书写**

## 26. CP 已启用但 Edge/Chrome 仍「什么都不弹」——androidx.credentials 版本停太旧（2026-09-10，总根因之二 P0）

- **现象**：用户确认系统里 Vaultix 凭据提供商**已启用**（设置 → 密码和账号可见且打勾），
  但 Edge/Chrome 聚焦登录框时**密码条目与通行密钥都不出现**；老路径（Via 等 WebView）
  正常。系统侧无任何报错，应用侧 `onBeginGetCredentialRequest` 从不被调用。
- **根因**：`androidx.credentials` 被锁在 **1.3.0**，而**1.5.0 才引入「凭据选择二级 UI 体验」**
  —— 应用可在登录时刻把 `GetCredentialRequest` 与具体输入框关联，用户聚焦该框时系统才
  向 Credential Manager 下发请求，候选以键盘上方/下拉建议形式聚合展示。Chromium
  （Chrome/Edge）在 Android 14+ 呈现凭据条目**正依赖该机制**。停在 1.3.0 → 系统不会
  在聚焦时派发请求 → 表现为「已启用但毫无反应」。
  - 附带缺失：`CredentialEntry.setBiometricPromptData`（1.5.0 引入，`@RequiresApi(35)`），
    Android 15+ 系统渲染条目所需（Bitwarden 1.6.0 必挂）。
  - **锁 1.3.0 的原始理由是错的**：当时记「1.6.0 把 `CallingAppInfo.origin` 收紧为
    internal，读它会编译失败」。1.6.0 实际提供了官方替代读法
    `CallingAppInfo.isOriginPopulated()` + `getOrigin()` —— 不该为绕一个 API 变更而退到
    有功能缺陷的版本。
- **解法**：
  1. `libs.versions.toml` `credential` 1.3.0 → **1.6.0**（对齐 Bitwarden）；
  2. 新增 `CallingAppOrigin`（`isOriginPopulated()` + `getOrigin()` 安全包装），
     替换 `PasskeyGetActivity` / `PasskeyCreateActivity` 里对 `callingAppInfo.origin` 的直读；
  3. entry 构造补 `setAutoSelectAllowed` + 按需 `setBiometricPromptData`；
  4. 两个 process 方法补 `cancellationSignal.setOnCancelListener`（对齐 Bitwarden）。
- **厂商 ROM 陷阱**：`setBiometricPromptData` **小米 HyperOS 已知不兼容**（Bitwarden 原文），
  荣耀 MagicOS 同族魔改风险相同 —— 挂上可能导致系统在渲染阶段丢弃整个 entry，
  表现**仍是「什么都不弹」**（比不挂更糟）。故新增 `RomCompat` 判定，非
  HyperOS/MagicOS 且 API ≥ 35 才挂。
- **判据**：升级后聚焦 Edge 登录框应能看到条目；`logcat -s VaultixAutofill` 应出现
  `GET options pk=.. pw=.. caller=com.microsoft.emmx origin=https://...`。
  **教训：系统集成能力依赖的 androidx 版本不能凭「能编译」就锁死，要对照官方 release notes 确认功能引入版本。**

## 27. CP 密码条目不做来源过滤 → 列全库无关站点（2026-09-10）

- **现象**：Edge 密码框可能列出几十条与当前网站无关的登录条目；且浏览器的
  「只展示相关凭据」预期被打破（部分版本会因此判定无有效候选）。
- **根因**：`passwordEntries` 只遍历已解锁库的**全部** Login 条目，零过滤；
  而通行密钥分支有 rpId 匹配 —— 两条分支语义不对称。Bitwarden 侧为
  `filterCiphersForMatches(matchUri = ...)`（按调用来源过滤）。
- **解法**：复用 Vaultix 既有 `BitwardenLikeAutofillMatcher`（eTLD+1 / 等价域 /
  androidapp:// 同一套规则），保证 CP 通道与老 autofill 通道语义一致；
  浏览器场景**只用 origin 不用包名**（包名是浏览器自己，拿去匹配条目 URI 必落空）。
  origin/包名都取不到时**不过滤**（宁可多列，不可漏列）。
- **判据**：CP 通道与 autofill 通道对同一条目应给出相同的匹配结论。

## 28. provider.xml settingsActivity 指向 MainActivity（2026-09-10）

- **现象**：系统凭据管理器「密码和账号 → Vaultix → 齿轮」点进来落在 app 主壳（库列表页），
  看不到任何凭据管理入口。
- **根因**：`android:settingsActivity` 指向 `MainActivity`（带底部导航的主界面）。
  系统语义要求它是**能管理本 Provider 凭据的界面**。
- **解法**：新建 `CredentialProviderSettingsActivity`（承载 `AutofillSettingsScreen`，
  含自动填充开关 + 凭据提供商状态 + 快速填充磁贴），manifest 注册为 `exported=true`
  （系统设置应用需跨进程启动），`settingsActivity` 改指此处。
  对齐 Keyguard / Bastion / Bitwarden 的「独立凭据管理页」做法。

## 29. `CallingAppInfo.getOrigin()` 在 1.6.0 需要 `privilegedAllowlist` 且必须签名命中（2026-09-10）

- **现象**：升级到 `androidx.credentials` 1.6.0 后 CI 编译失败：
  `CallingAppOrigin.kt:51:44 No value passed for parameter 'privilegedAllowlist'`。
- **根因（反编译 credentials-1.6.0.aar 确认，此前判断有误）**：
  `getOrigin(privilegedAllowlist: String)` **不是**可选的展示参数，而是**签名背书名单**：
  ```
  if (!isValidJSON(allowList))  throw IllegalArgumentException   // 空串/非 JSON
  if (origin == null)           return null                      // 系统未填，不校验
  apps = PrivilegedApp.extractPrivilegedApps(JSONObject(allowList))
  if (isAppPrivileged(apps))    return origin                    // 包名 + 签名指纹都命中
  else                          throw IllegalStateException      // 命中不了
  ```
  且 `PrivilegedApp.createFromJSONObject` 对 `signatures` 用 `getJSONArray`（必填），
  元素是**对象**（取 `cert_fingerprint_sha256`）；`isAppPrivileged` 单签名者走
  `verifySignatureFingerprints` = `intersect(调用方指纹, 名单指纹)` 非空。
  → **「空名单 / 空签名数组 / 裸字符串数组」三种取巧写法全部会抛 `IllegalStateException`**，
    必须把调用方**自己的真实签名指纹**填进名单。
- **为什么 Bitwarden 没这个问题**：它读 assets 里的 Google / 社区名单 + 用户信任名单
  做**三级签名背书**（`OriginManagerImpl`），Vaultix 当前不做身份背书，故不内置名单。
- **解法（自证式读取）**：
  1. `ALLOW_LIST_TEMPLATE`：`signatures` 为对象数组的合法 JSON 结构；
  2. `signingFingerprintOrNull()`：按 Bitwarden `getSignatureFingerprintAsHexString()` 口径
     算调用方 APK 签名 SHA-256（多签名者返回 null），拼只含它自己的名单；
  3. 只用于**读取系统已填好的 origin** 以做按来源过滤，不做任何特权应用身份认定；
     `trustedOriginOrNull(allowList)` 预留给将来接入用户信任名单。
- **判据**：Edge 聚焦登录框后 `logcat -s VaultixAutofill` 应出现
  `caller=com.microsoft.emmx origin=https://...`（origin 不再是 `-`）。
- **教训：`getOrigin()` 的 allowList 语义是「签名校验」不是「参数占位」，读 API 必须反编译看实现，不能只看签名。**

## 30. CI 门禁卡在 `getOrigin` 参数（本轮修复已闭环，2026-09-10）

- run `34495532104` 因 #29 的编译错误失败；本轮 `d082e63` 修复后 run `34496366032` **全绿**
  （detekt ✓ / 编码门禁 ✓ / 签名解码 ✓ / Build Debug APK ✓ / 单测非阻塞 ✓），
  预览包已发布：`app-full-debug.apk`（dev-d082e63，31.0 MB）。

## 32. 通行密钥「验签失败」根因：authenticatorData 缺 BE/BS 标志位（2026-09-11，`8afac3d`）

**真机症状（用户原话）**：Edge 里用通行密钥登录，**指纹验证通过了**，但**所有网站**都报
「验证失败 / 无法验证」。三个特征合起来指向一件事——**签名数据的密码学校验不通过**，
而不是候选展示、生物识别或 credentialId 匹配的问题。

### 根因
`WebAuthn.buildAuthenticatorData` 只设了 UP(0x01) + UV(0x04)，**缺 BE(0x08) / BS(0x10)**。

Vaultix 的 passkey 私钥存在库中并随 Bitwarden 服务端同步 —— 语义上是
**可备份凭证（backup eligible）**，必须声明：
- `BE`（Backup Eligibility, 0x08）= 本凭证**可**被备份
- `BS`（Backup State, 0x10）= 本凭证**当前处于**备份状态

这是 Bitwarden / 1Password / iCloud Keychain 这类可同步通行密钥的标准声明。缺失时
RP 的校验库会因 BE/BS 语义不符而拒绝整条断言。

**关键判据：注册与断言的 BE/BS 基线必须一致。** 对齐 Bastion
（`PasskeyAuthActivity` / `PasskeyCreateActivity` 两边基线都是 `0x1D`，注册额外加 AT）：

| 流程 | 修复前 | 修复后 |
|---|---|---|
| 断言 | `0x05`（UP+UV） | `0x1D`（UP+UV+BE+BS） |
| 注册 | `0x45`（UP+UV+AT） | `0x5D`（UP+UV+BE+BS+AT） |

### 连带修掉的两处偏差
1. **signCount 硬编码 0 → 读库**（对齐 Keyguard `PasskeyProviderGetRequest`）：
   库里非零值**原样发送但不递增**。递增必然跨设备分叉（A 签 6、B 设备恢复后仍签 5，
   RP 看到计数回退直接拒签 → 表现为「用了若干次后突然失效」）；0 表示「不实现计数器」，
   规范允许 RP 跳过单调性校验。Bastion 选择强行写 0，Keyguard 选择保留非零值 ——
   取 Keyguard 口径（两者对 RP 都合规，保留原值更尊重既有数据）。
2. **响应 JSON 补齐两家共有字段**：
   - `clientExtensionResults:{}` —— 部分 RP 解析器**直接读该键**，缺失即解析失败；
   - `authenticatorAttachment:"platform"` —— 软件密钥 + 系统生物识别，属平台内置
     （Bitwarden 填 `cross-platform`、Bastion 填 `platform`，取 Bastion 口径）。

### 已排除的疑似项（实测无问题，未改动）
- **密钥编解码往返**：200 组随机 P-256 密钥走
  `base64Url(PKCS8)` → `decodeBase64UrlOrStandard` → `parseEcPrivateKey` 重建后签名，
  **100% 被原公钥验签通过**（DER 分支命中 200/200）；
- **PKCS8 分支顺序**：PKCS8 长 67 字节，`normalizeScalar` 正确返回 null 交给 DER 分支，
  不会被误当 32 字节裸标量；
- **clientDataHash 反选逻辑**：能正确命中 Chromium 含 `crossOrigin` 的原文
  （已验证：无 crossOrigin 候选不命中、含 crossOrigin 候选命中）。

### ⚠️ 升级注意
**修复前注册的 passkey 需要重新注册**。旧凭证在 RP 侧是按「无 BE/BS」语义登记的，
改了 flags 后旧凭证的断言仍会因基线不符而失败（RP 记的是注册时的语义）。
用户需在被拒的网站上删除旧通行密钥后重新创建。

### 教训
**「指纹过了但网站说验证失败」= 数据问题，不是交互问题。** 拿到这个组合（指纹通过 +
全站失败 + 验签失败）时应立刻停止排查候选展示 / CP 通道 / 生物识别，直接查
`authenticatorData` / `clientDataJSON` / `signature` 三者。此前的多轮修复都在改
「能不能弹出来」，而这一步早就通了 —— 错在把「功能不通」笼统当成一个问题。

---

## 33. **【更正 32】BE/BS 与「验签失败」无因果，且「必须重新注册」是错的**（2026-09-11，第二十九轮）

上一节（#32）的**结论有两处错误，本节正式更正**。用户质疑「通行密钥为什么要新建呢，
这个不是存好的就不动的吗」——**用户是对的**。

### 更正一：BE/BS 不是登录失败的原因
查证 WebAuthn 规范 / 多家 RP 文档后确认：

> **BE（Backup Eligible）、BS（Backup State）是「注册期存档字段」。**
> RP 在**断言（登录）阶段不做 BE/BS 校验**。login 校验清单只有：
> `type==="webauthn.get"` / challenge / origin / 按 credentialId 取公钥 /
> `SHA-256(rpId)` 匹配 rpIdHash / UP（按策略 UV）/ 验签 / **`new signCount > stored`**。

⇒ 「登录验签失败」**不可能**由 BE/BS 缺失引起；同理，也**不存在「旧凭证必须重注册」**。
（BE/BS 置位仍然保留，但理由改为**语义正确性**：私钥随库同步，确实是可备份凭证。）

### 更正二：signCount 必须恒 0，不能「读库原样发送」
#32 把断言 `counter` 从硬编码 0 改成读库。**这引入了新 bug。**
规范校验是**严格大于**（`new > stored`）；Bitwarden 官方端每签一次会**递增写回服务端**，
同步下来的 `counter` 就是非零（`CipherMapper:444` 实证）。原样发送且不递增 ⇒
第二次登录值与上次相同 ⇒ `new > stored` 不成立 ⇒ RP 判**重放**拒签。
**已回退为恒 0**（对齐 Bastion `newSignCount = 0L`，规范 §6.1.1 允许，同步型 passkey 标准做法）。

### 教训
**验签失败时不要在「注册期字段」上找原因。** 断言阶段的数据只有
`authenticatorData`（rpIdHash/flags/signCount）/ `clientDataJSON` / `signature`，
以及 RP 侧存的**公钥**与**上一次的 signCount**。BE/BS 只影响「RP 将来怎么展示这条凭证」。

---

