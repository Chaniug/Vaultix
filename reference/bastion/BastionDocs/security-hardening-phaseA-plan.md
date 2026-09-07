# 阶段 A：P0 安全类修复计划（待确认）

> 审查范围：`Bastion/app/src/main/java`
> 分支策略：所有改动在 `dev` 分支，每项单独 commit + push，提交后观察 GitHub Actions。
> 状态：计划已拟定，**未开始写业务代码**，等待确认。

---

## 0. 审查依据（均已逐行核实，非误报）

| # | 问题 | 位置 | 核实结论 |
|---|------|------|----------|
| A1 | 加密密钥/IV 硬编码且长度 17 字节（AES 须 16/24/32，IV 须 16）→ 实际抛 `InvalidKeyException` | `util/ImageManager.kt:52-53,492-509` | 确定性 bug：证件图片加密路径从来没成功过 |
| A2 | `disablePasswordVerification` 可关闭启动主密码校验，无 `BuildConfig.DEBUG` 门控，DataStore 持久化 | `DeveloperSettingsScreen.kt:278`、`MainAppLockPolicy.kt:34`、`SettingsManager.kt:533` | 正式包可达，危险 |
| A3 | 会话单例跨进程共享状态缺 `@Volatile`/同步，read-modify-write 无锁 | `SessionManager.kt:45,49,198-208`、`SecondarySessionManager.kt:25-26` | 对比 `SecurityManager.kt:47-51` 已用 `@Volatile`，属遗漏 |
| A4 | 生物识别密钥失效后静默回退到 `setUserAuthenticationRequired(false)` 的 compat 密钥重包装 MDK | `SecurityManager.kt:721,810-825` | 经调用链核实：**仍需通过 BiometricPrompt UI，非完全绕过**；但 MDK 失去 Keystore 生物识别绑定，数据静息保护降级（防御纵深削弱） |

---

## A1. ImageManager 硬编码密钥/IV（确定性 bug，必修）

**现状**
- `ImageManager.kt:52-53` 硬编码 `ENCRYPTION_KEY = "BastionSecureKey1".toByteArray()`（17 字节）与 `IV = "BastionSecureIV16".toByteArray()`（17 字节）。
- `ImageManager.kt:492-509` 的 `encrypt()/decrypt()` 用 `AES/CBC/PKCS5Padding` + 上述密钥/IV。

**问题**
- 17 字节既非合法 AES 密钥长度（16/24/32）也非合法 IV 长度（16），`SecretKeySpec`/`IvParameterSpec` 在 `cipher.init()` 时直接抛 `InvalidKeyException` / `InvalidAlgorithmParameterException`。
- 即便能跑，密钥以明文硬编码在 APK 中，反编译即等同明文。
- 结论：证件图片加密路径实际是坏的；且因密钥非法从未成功写入过 `.enc` 文件，**无存量数据需要迁移**。

**修复方案**
- 删除 `ENCRYPTION_KEY` / `IV` 常量与手写 `encrypt()/decrypt()`。
- 改用 Jetpack Security 的 `EncryptedFile` + `MasterKey`（AES256_GCM）。项目已依赖 `androidx.security:security-crypto`（`SecurityManager` 的 `EncryptedSharedPreferences` 即来自该库），无需新增依赖。
- 新增封装：
  - `suspend fun saveImage(...)`：压缩 Bitmap → `EncryptedFile.Builder(file, context, masterKey, EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB).build().openFileOutput().write(bytes)`。
  - `suspend fun loadImage(...)`：`EncryptedFile.Builder(...).build().openFileInput().readBytes()` → 解码 Bitmap。
  - `decrypt()` 旧调用点（如 `loadImage` 行 255）改为读取 `EncryptedFile`。
- 错误处理：读取旧 `.enc`（若极个别设备曾绕过异常写出）失败时，UI 层提示"图片不可用"，不做静默崩溃。

**改动文件**：`util/ImageManager.kt`
**验证**：单元/插桩测试 + 手动保存/加载图片 + GitHub Actions。

---

## A2. disablePasswordVerification 无 DEBUG 门控（危险，必修）

**现状**
- `DeveloperSettingsScreen.kt:278` 直接 toggle → `viewModel.updateDisablePasswordVerification(enabled)`。
- `SettingsManager.kt:533` 持久化到 DataStore；`MainAppLockPolicy.kt:34` 在 `disablePasswordVerification == true` 时返回 `bypassEnabled = true`，跳过启动主密码校验。
- 全局**无任何 `BuildConfig.DEBUG` 判断**，release 构建同样可达、可生效。

**问题**
- 正式包中，能进入开发者选项的人即可关闭主密码校验，启动后直接绕过密码/生物识别，等同任意访问全部保险库数据。

**修复方案（双保险）**
1. **UI 层**：`DeveloperSettingsScreen.kt` 该开关用 `if (BuildConfig.DEBUG) { ... }` 包裹——release 构建不显示、不可切换。
2. **决策层**：`MainAppLockPolicy.resolveAccessState` 与 `AuthComponents.kt:185,247` 使用处加 `&& BuildConfig.DEBUG` 守卫，即便 DataStore 被手动置位，release 下也强制忽略。
3. `AppSettings.disablePasswordVerification` 保留为只读布尔，但增加注释标明"仅 DEBUG 生效"。

**改动文件**：`ui/screens/DeveloperSettingsScreen.kt`、`security/lock/MainAppLockPolicy.kt`、`ui/components/AuthComponents.kt`
**验证**：分别打 debug/release 对比；GitHub Actions 跑测试。

---

## A3. 会话单例跨进程共享状态缺同步（必修）

**现状**
- `SessionManager.kt:45` `unlockTimestamp: Long`、`SessionManager.kt:49` `autoLockMinutes: Int` 为普通 `var`；`canSkipVerification()`（:198-208）对 `_isUnlocked/unlockTimestamp/autoLockMinutes` 做无锁 read-modify-write。
- `SecondarySessionManager.kt:25-26` 同样。
- 该单例被主进程 Activity（`onResume`/`onUserInteraction`）与 `:autofill`、`:accessibility` 进程的 Binder/IO 线程并发读写；`SharedPreferences` 已用 `MODE_MULTI_PROCESS`，但内存字段未同步。
- 对比 `SecurityManager.kt:47-51` 正确用了 `@Volatile`，此处是遗漏。

**改动**
- `unlockTimestamp`、`autoLockMinutes` 加 `@Volatile`。
- `canSkipVerification()` / `refreshSession()` 中对复合状态的读写加 `synchronized(lock)`（或封装为原子更新）。
- `SecondarySessionManager` 同样处理。

**改动文件**：`security/SessionManager.kt`、`security/SecondarySessionManager.kt`
**验证**：手动多进程场景（主进程解锁后自动填充进程能否免二次解锁、超时判定正确）+ GitHub Actions。

---

## A4. compat 密钥回退导致 MDK 生物识别绑定降级（需设计决策）

**现状**
- `SecurityManager.kt:721` `getOrGenerateCompatSecureKey()` 使用 `setUserAuthenticationRequired(false)`。
- `persistKeystoreWrappedMdk()`（:810-825）在 `UserNotAuthenticatedException` / `KeyPermanentlyInvalidatedException` / `UnrecoverableKeyException` 三种情况下回退调用 `persistCompatKeystoreWrappedMdk(mdk)` 重包装主数据密钥。

**核实结论（重要，修正初判）**
- `unlockVaultWithBiometric()`（:218）由 BiometricPrompt 之后的后校验调用（`AuthComponents.kt:72-99`、`AutofillUnlockActivity.kt:87`、`AutofillCipherCallbackActivity.kt:157`、`PasswordListTopSection.kt:657`），compat 回退后**仍需先通过生物识别弹窗**，因此**不是完全绕过**。
- 但回退后 MDK 改由免认证 compat 密钥包装，失去 Keystore 生物识别绑定——数据静息（at-rest）保护降级，属于防御纵深削弱，建议处理。

**修复方案（二选一，待你确认取向）**
- **选项 A（最小侵入，推荐）**：保留 compat 作为"最后兜底"，但仅在密码解锁且 MDK 确因密钥失效无法以 AUTH 密钥重包装时启用；补强 `SecurityDiagLogger` 记录降级事件便于审计；确保在下一次密码解锁时尝试用新鲜 AUTH 密钥重新包装 MDK 以恢复绑定。
- **选项 B（更严格）**：移除 compat 回退，密钥失效时强制要求重新输入主密码，以 AUTH 密钥重新派生并包装 MDK；代价是密钥失效场景下用户必须记得主密码（无法纯生物识别恢复）。

**改动文件**：`security/SecurityManager.kt`
**验证**：单元测试 + 手动（录入新指纹后验证解锁流程）+ GitHub Actions。

---

## 提交与验证策略
- 全部在 `dev` 分支；A1/A2/A3 为确定性修复，A4 视取向再定。
- 每项单独 commit + push，提交后**观察 GitHub Actions 状态**，报错即自动修复并总结。
- 如需回归保护，扩展现有 `*RegressionGuardTest`（如 `BiometricUnlockRegressionGuardTest.kt`）覆盖新行为。

## 待确认
1. **A4 取选项 A 还是 B？**
2. **是否现在开始按上述落地（dev 分支）？**
