# 06 · Android 平台集成

## 1. 自动填充（Autofill Framework）

### 1.1 服务声明

```xml
<service android:name=".autofill.VaultixAutofillService"
    android:permission="android.permission.BIND_AUTOFILL_SERVICE"
    android:exported="true">
    <intent-filter><action android:name="android.service.autofill.AutofillService"/></intent-filter>
    <meta-data android:name="android.autofill" android:resource="@xml/autofill_service"/>
</service>
```

`res/xml/autofill_service.xml`：声明可保存的类型（username/password/credit card）与兼容性白名单（对不支持的浏览器/应用提供"兼容模式"）。

### 1.2 填充流程

```
onFillRequest(request, cancellationSignal, callback)
 1. 收集 AssistStructure → 提取：
    · 包名、Web 域名（从 WebView/浏览器 URL 节点或 WebDomain）
    · 各字段 hints（username/password/newPassword/otp/creditCard...）
 2. 校验关联关系（见 1.3）：包名 ↔ Digital Asset Links
 3. 会话状态：
    · 已解锁 → 按 URI 匹配条目 → 构建 Dataset
    · 未解锁 → 构建"解锁"Dataset，点击后拉起解锁 Activity，成功后回调填充
 4. 返回 FillResponse：
    · 头部：匹配的条目（最多 3–5 个）
    · 用户名 Dataset / 密码 Dataset / TOTP Dataset / 信用卡 Dataset
    · 末位固定："在 Vaultix 中搜索"（deeplink: vaultix://search?q=<host>）
```

### 1.3 关联校验（安全红线）

| 场景 | 校验方式 | 不通过时 |
|---|---|---|
| 原生 App | `Digital Asset Links`：`https://<host>/.well-known/assetlinks.json` 中包含该 App 的签名指纹 | 仍可填充但**必须**二次确认（展示"即将填充到：<包名>"），并记录到"未验证关联"提示 |
| 浏览器 | 读取 `WebDomain` / 地址栏 URL 节点 | 域名为空 → 拒绝填充密码，只允许"打开 Vaultix 搜索" |
| 无法判断（降级） | — | **不得**自动填充密码；只允许展示搜索入口 |

> 关联校验结果（域名 → 包名）需缓存，有效期跟随 `assetlinks.json` 的 TTL，并支持用户手动"记住此关联"。

### 1.4 保存流程

```
onSaveRequest：用户提交登录表单后触发
 → 展示保存确认（可设为"自动保存"）
 → 无匹配条目：新建（标题取 App 名/域名）
 → 有匹配：提示"更新密码？"（同时写入 passwordHistory / KDBX History）
```

### 1.5 特殊场景

| 场景 | 处理 |
|---|---|
| 多步骤登录（先用户名后密码） | 用 `clientState` 传递已选条目 id，保证两步填同一条 |
| 分区填充（`partition`） | 用户名与密码分屏时分别响应 |
| TOTP 字段 | 识别 `smsOTPCredential` / `AUTOFILL_HINT_...OTP` 或相邻 hint；填充当前 30 s 码 |
| 表单无 hint | 走启发式（inputType 为 `TYPE_TEXT_VARIATION_PASSWORD` 等） |
| 用户拒绝 | 记录"此 App 不再询问"（存 DataStore，可重置） |

## 2. Credential Manager（作为 Provider）

Android 14（API 34）起，密码管理器可注册为 **Credential Provider**，在系统统一的选择器中提供凭据（含 Passkey）。

```xml
<service android:name=".autofill.VaultixCredentialProviderService"
    android:exported="true"
    android:permission="android.permission.BIND_CREDENTIAL_PROVIDER_SERVICE">
    <intent-filter>
        <action android:name="android.service.credentials.CredentialProviderService"/>
    </intent-filter>
    <meta-data android:name="android.credentials.provider" android:resource="@xml/provider"/>
</service>
```

实现要点：

| 回调 | 实现 |
|---|---|
| `onGetCredential` | 处理 `GetPasswordOption` / `GetPublicKeyCredentialOption`；返回条目列表，用户点选后填充 |
| `onCreateCredential` | 处理 `CreatePasswordRequest`（保存新密码）与 `CreatePublicKeyCredentialRequest`（Passkey 注册） |
| `onClearCredentialState` | 清除会话状态 |

- 与 Autofill 并存：API 34+ 优先走 Credential Manager，其余走 Autofill 服务。
- 系统设置入口：需引导用户在 **设置 → 密码与账号 / 凭据管理器** 中把 Vaultix 选为 provider（首次进入时提供一键跳转）。
- Passkey（V1.5）：本地生成 EC P-256 密钥对，私钥用 Keystore 包裹；存储为 KDBX 附件（约定字段）或 Bitwarden 的 FIDO2 credential 字段。

## 3. 生物识别与解锁

```kotlin
BiometricPrompt.PromptInfo.Builder()
    .setTitle("解锁 Vaultix")
    .setSubtitle("使用生物识别或设备密码")
    .setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
    .build()
```

| 要点 | 说明 |
|---|---|
| 门禁方式 | 推荐 **Keystore 密钥门禁**（`setUserAuthenticationRequired`）而非仅 UI 校验，防 root 绕过 |
| 免认证窗口 | 支持 `-1`（每次都认证）/ `0`（每次使用密钥都认证）/ `N` 秒时间窗；默认 0 |
| 失败处理 | 连续失败 5 次 → 锁定 30 s 并要求主密码 |
| 指纹变更 | `setInvalidatedByBiometricEnrollment(true)`，捕获 `KeyPermanentlyInvalidatedException` 清除凭据 |
| 降级 | 无生物识别硬件 → 设备 PIN/图案；仍无 → 只能用主密码 |

## 4. 防泄漏

| 措施 | 实现 |
|---|---|
| 防截屏/录屏/投屏 | 所有含敏感内容的 Activity 设置 `FLAG_SECURE`（在 `onCreate` 内、`setContent` 之前） |
| Recent 卡片脱敏 | Android 13+：`setRecentsScreenshotEnabled(false)`；低版本用 `FLAG_SECURE` 全量开启并做生命周期兜底 |
| 防浮层劫持 | 声明 `HIDE_OVERLAY_WINDOWS`（Android 12+）；敏感页面检测到悬浮窗权限被授予时给出警告 |
| 剪贴板 | 复制后 30/60 s 自动清除（可配置）；Android 13+ 系统也会自动清理，但仍自行实现；复制时禁用"复制成功"Toast 泄露内容 |
| 键盘 | 主密码/敏感输入建议"仅系统键盘"，提供设置项提示用户关闭第三方键盘的云输入 |
| 备份 | 数据库置于 `noBackupFilesDir`；`android:allowBackup="false"` 或明确 `dataExtractionRules` 排除密钥；KDBX 文件不在应用私有目录（用户自控） |
| 内存 | `SecureBytes` 清零；避免在 `Bundle`/`SavedStateHandle` 中保存明文（跨进程传递密文 + 会话内密钥引用） |

## 5. 锁定策略

| 触发 | 默认 | 可配置 |
|---|---|---|
| 应用切后台 | 立即锁定（或 15 s 宽限） | 1 分钟 / 5 分钟 / 15 分钟 / 永不 |
| 设备锁屏 | 立即 | 同左 |
| 手动 | 通知栏"锁定"磁贴 + 设置内按钮 | — |
| 超时 | 15 分钟无操作 | 1/5/15/30 分钟 / 永不 |
| 重启 | 必须主密码解锁 | — |

- 锁定用 `ProcessLifecycleOwner` + 前台服务状态共同判定；避免误判（如系统权限弹窗导致短暂 onPause）。
- 锁定后 Autofill/Credential Manager 会话立即失效。

## 6. 后台任务

| 任务 | 方案 |
|---|---|
| Bitwarden 周期同步 | `WorkManager` 周期任务（默认 6 h，要求非计量网络），或依赖服务端推送（V1 不接 FCM，改用轮询） |
| KDBX 自动保存 | 前台 `CoroutineWorker`（短任务），锁定前同步阻塞保存 |
| 图标缓存清理、日志清理 | 周期 `WorkManager`，约束充电+空闲 |
| 剪贴板清除倒计时 | 前台服务 + 通知（Android 12+ 后台启动限制，需在前台上下文中启动） |

## 7. 快捷入口

| 能力 | 实现 |
|---|---|
| 应用快捷方式 | `ShortcutManager`：搜索 / 新建条目 / 生成密码 / 锁定 |
| 快捷设置磁贴 | `TileService`：一键锁定 / 打开生成器 |
| 文本选择菜单 | 注册 `Intent.ACTION_PROCESS_TEXT`，支持"保存到 Vaultix"/"用 Vaultix 填充" |
| 分享目标 | `ACTION_SEND` 接收文本 → 新建安全笔记 |
| 应用内搜索（AppSearch） | V1.5，可选接入系统级搜索 |

## 8. 版本兼容矩阵

| 能力 | 最低 API |
|---|---|
| Autofill Framework | 26（8.0） |
| BiometricPrompt（Jetpack） | 23（回退 `FingerprintManager` 已弃用，minSdk 26 无需考虑） |
| `setUserAuthenticationParameters` | 30 |
| StrongBox | 28（失败回落 TEE） |
| Credential Manager Provider | 34 |
| `setRecentsScreenshotEnabled` | 33 |
| `HIDE_OVERLAY_WINDOWS` | 31 |
| 动态取色（Material You） | 31（低版本提供静态主题 + 壁纸取色回退） |
| 通知权限 `POST_NOTIFICATIONS` | 33（运行时申请） |
