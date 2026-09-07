# Passkey Origin 校验：对齐 Bitwarden 方案（2026-09）

## 0. 信息来源

Bitwarden 安卓端已迁至独立仓库 **`bitwarden/android`**（`bitwarden/clients` 不含安卓代码）。
本方案基于**本地克隆的真实源码**（镜像 `ghfast.top` 克隆成功，57M / 2555 个 kt 文件），非二手摘要。

## 1. Bitwarden 真实实现

### 1.1 签名指纹
`app/src/main/kotlin/com/x8bit/bitwarden/data/platform/util/CallingAppInfoExtensions.kt`
```kotlin
fun CallingAppInfo.getAppSigningSignatureFingerprint(): ByteArray? {
    if (signingInfo.hasMultipleSigners()) return null          // 多签名者 → 拒绝
    val signature = signingInfo.apkContentsSigners.first()
    return MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
}
// origin 格式：Base64(hash, URL_SAFE or NO_WRAP or NO_PADDING)
fun CallingAppInfo.getAppOrigin(): String =
    "android:apk-key-hash:${Base64.encodeToString(getAppSigningSignatureFingerprint(), ENCODING_FLAGS)}"
// 列表/assetlinks 格式：hex 大写，":" 分隔（如 F0:FD:6C:...）
fun CallingAppInfo.getSignatureFingerprintAsHexString(): String? =
    getAppSigningSignatureFingerprint()?.joinToString(":") { b -> b.toHexString(HexFormat.UpperCase) }
```

### 1.2 主流程（两条互斥分支）
`data/credentials/manager/OriginManagerImpl.kt`
```kotlin
override suspend fun validateOrigin(relyingPartyId, callingAppInfo) =
    if (callingAppInfo.isOriginPopulated()) validatePrivilegedAppOrigin(...)   // 有 origin
    else validateCallingApplicationAssetLinks(...)                             // 无 origin
```

**分支 A：有 origin → 特权 App 三级校验**（`validatePrivilegedAppOrigin`）
依次 Google 列表（`isVerifiedSource=true`）→ 社区列表（`false`）→ 用户信任列表（`true`），
每级 `takeUnless { it is PrivilegedAppNotAllowed }`（命中即返回，未命中进下一级）。
`validatePrivilegedApp()` 逻辑：
- 包名不在 allowList → `PrivilegedAppNotAllowed`
- 包名在、签名不匹配 → `PrivilegedAppSignatureNotFound`
- 命中且 `isVerifiedSource` → origin 简化为 `https://{rpId}`（信任其声称的 rpId）
- 命中但非 verified → 用 allowList 中记录的 origin

**分支 B：无 origin → Digital Asset Links 网络校验**（`validateCallingApplicationAssetLinks`）
```
source.web.site = https://{rpId}
target.android_app.package_name = 调用方包名
target.android_app.certificate.sha256_fingerprint = hex 指纹
relation = delegate_permission/common.handle_all_urls
```
- `linked == true` → `Success(null)`
- `linked == false` → `PasskeyNotSupportedForApp`
- 请求失败 → `AssetLinkNotFound`

### 1.3 失败语义（全部 fail-closed）
`data/credentials/model/ValidateOriginResult.kt`：
`AssetLinkNotFound` / `PrivilegedAppNotAllowed` / `PrivilegedAppSignatureNotFound` /
`PasskeyNotSupportedForApp` / `Unknown` —— **没有任何"用自己签名兜底"的路径。**

### 1.4 特权列表数据
- `app/src/main/assets/fido2_privileged_google.json`（28 KB）
- `app/src/main/assets/fido2_privileged_community.json`（3.4 KB）
- 用户信任列表：Room 持久化（`PrivilegedAppRepository` / `PrivilegedAppDao`）+ 设置页 UI

格式（一个包名可含多个签名条目，对应不同构建变体）：
```json
{ "apps": [ { "type": "android", "info": {
    "package_name": "com.android.chrome",
    "signatures": [
      { "build": "release",   "cert_fingerprint_sha256": "F0:FD:6C:..." },
      { "build": "userdebug", "cert_fingerprint_sha256": "19:75:B2:..." }
    ] } } ] }
```

## 2. bastion 现状

### 2.1 已有（`passkey/PasskeyOriginResolver.kt`）
- `getCallingAppSigningHash()`：`apkContentsSigners` → `CertificateFactory` 生成 X509 → `SHA-256(cert.encoded)` → base64url
- 解析链：requestJson.origin → `CallingAppInfo.origin` → `apk-key-hash`(调用方签名) → `https://{rpId}` → **SELF_SIGNING_FALLBACK** → `https://invalid.local`
- `passkey/PasskeyRequestValidator.kt` 已有校验：`request_origin_host_not_allowed_for_rp`（该条 `strictBlock=true`）、
  `calling_origin_host_not_allowed_for_rp`、`request_and_calling_origin_host_mismatch`、`origin_fallback_used`

### 2.2 差距对照

| 维度 | Bitwarden | bastion |
|---|---|---|
| 多签名者 | `hasMultipleSigners()` → 拒绝 | 取 `signatures[0]`，不拒绝 |
| 有 origin 时 | 特权三级列表 + 签名匹配 | 直接用 origin，仅做 host↔rpId 匹配 |
| 无 origin 时 | assetlinks 网络校验，失败即拒 | 回退链，末端用 **bastion 自己签名**兜底 |
| 失败处理 | fail-closed | shadow（记日志），仅 rpId 不匹配才阻断 |
| 特权列表 | Google+社区+用户（含 UI） | 无 |

### 2.3 最值得改的一处
`PasskeyOriginResolver.kt:84-88` 的 `SELF_SIGNING_FALLBACK`：调用方既无 origin 又无 rpId 时，
用 **bastion 自身的签名哈希**当 origin。等于任意 App 可借 bastion 身份生成 origin。
Bitwarden 在同样场景返回 `PasskeyNotSupportedForApp` / `AssetLinkNotFound`，绝不用自己签名。

## 3. 对齐方案（分阶段，风险递增）

### 阶段 1：低风险，直击安全弱点（建议先做）
1. **移除 `SELF_SIGNING_FALLBACK` 的"自家签名"行为**
   无 origin 且无 rpId → 直接 `INVALID_FALLBACK`，不再套 bastion 自身签名。
   保留 `https://{rpId}` 回退（Bitwarden 无 origin 时同样把 rpId 用作 assetlinks 的 source）。
2. **对齐多签名处理**：`hasMultipleSigners()` → 返回 null（拒绝）并记录。
3. **统一哈希算法**：`getAppSigningHash` 与 `getCallingAppSigningHash` 走同一路径
   （建议统一为 `SHA-256(signature.toByteArray())`，与 Bitwarden 完全一致；
   Android 上 `Signature.toByteArray()` 即证书 DER 编码，与 `cert.encoded` 等价）。
> 本阶段不引入网络依赖、不阻断正常流程。

### 阶段 2：核心机制对齐（中等风险，需决策）
4. **特权 App 列表校验**（有 origin 分支）：内置主流浏览器包名 + 签名指纹，
   `validatePrivilegedApp()` 语义对齐（包名命中 → 遍历该包所有 signatures 比对 → 命中才信任）。
5. **Digital Asset Links 校验**（无 origin 分支）：对齐 `OriginManagerImpl` 分支 B。
   **建议先"影子模式"**：校验 + 记录结果，不阻断；观察一段时间再决定是否 fail-closed。

### 阶段 3：可选
6. 用户信任特权 App 的管理 UI（对齐 Bitwarden 设置页）。

## 4. 需拍板的风险决策点

| # | 决策 | 风险 |
|---|---|---|
| A | 是否引入 Digital Asset Links 网络校验？ | 需网络；离线/被墙时原生 App passkey 会失败（建议先影子模式） |
| B | 特权列表来源：借鉴 Bitwarden JSON（28KB，需评估 license + 后续维护）还是自建精简版（仅主流浏览器）？ | 列表不全 → 浏览器 passkey 失败 |
| C | 校验失败是否阻断（fail-closed）？ | bastion 现为 shadow 模式；直接改阻断可能误伤（建议分阶段观察后决定） |

## 5. 验证方式

- 单元测试：`PasskeyOriginResolver` 多签名 / 无 origin / 无 rpId 各分支的 origin 与 source 断言
- 真机（荣耀 安卓17）：github passkey 登录（浏览器有 origin 分支）、原生 App passkey（无 origin 分支）
- 日志：`PasskeyRequestValidator.logShadow` 观察 `origin_fallback_used` 是否归零
