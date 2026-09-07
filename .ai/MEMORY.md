# Vaultix 项目长期笔记

## 产品定位（2026-09-07 用户拍板）
- **Bitwarden 优先的客户端**，对标 **Keyguard 路线**（区别于 Monica / Bastion 的"本地优先·聚合"）
- 支持 2 种库：Bitwarden 云端（主）+ KDBX 本地（次）
- 用户理由：Bitwarden 是目前主流
- 注意：不采纳 Bastion 的"私有本地库"——用开放标准的 KDBX 承担本地角色，避免用户数据锁定

## 架构约定
- 领域模型以 **Bitwarden `Cipher` 为规范模型（canonical）**，KDBX 为**降级映射端**
- 保真度三级：**无损** / **约定承载**（落 `Vaultix.*` 自定义字段）/ **有损**
  - 强制往返测试：`Bitwarden → VaultItem → KDBX → VaultItem → Bitwarden`
  - 有损字段必须在 `VaultCapabilities` 登记，UI 提示，**禁止静默丢弃**
- 对齐 Bitwarden **官方客户端实际行为**（非仅文档）：
  - KDF salt 为 UTF-8 编码字符串
  - `encKeyValidation` 明文为 UUID（非 `"Bitwarden"` 字符串）
  - Vaultwarden 忽略 `sinceRevisionDate`（增量同步会空转）

## 里程碑（2026-09 已调换 M1/M2）
| 期 | 内容 | 状态 |
|---|---|---|
| M0 | 基础骨架 + core:crypto | ✅ 已完成 |
| **M1** | **Bitwarden 同步** | 下一步 |
| **M2** | **KDBX 引擎** | 后置 |
| M3 | 平台集成（Autofill / 安全中心） | |
| M4/M5 | 发布准备 / 1.0 | |

## 技术栈（2026-09-07 升级并验证出 APK）
Gradle 9.5.1 / AGP 9.3.2 / Kotlin 2.4.10 / KSP 2.3.11 / Hilt 2.60.1 / compileSdk 37 / JDK 17

⚠️ **硬约束**
- Hilt 与 Kotlin 强绑定：Kotlin 2.4.x → Hilt **2.60.1**；AGP 9 要求 Hilt ≥ 2.59
- AGP 9 禁止 apply `kotlin-android`（内置 Kotlin 会冲突）
- version catalog 别名避免 `kotlin-` 前缀（撞内置 `kotlin {}` DSL），KGP 用别名 `kgp`
- 类型安全项目访问器需 `enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")`
- 本机 SDK 在 `C:\AndroidSDK`，**无 android-36**，compileSdk 必须 ≥ 37

## 代码来源与协议
- Vaultix = **GPL-3.0**（2026-09-07 从 MIT 切换）
- 参考/搬运源 Bastion（GPL-3.0，Copyright 2025 JoyinJoester）
- 搬运文件**必须**带溯源声明；Vaultix 原创部分需标注（如 `AesGcm.kt`）
- Keyguard 为"源码仅个人使用授权"，**禁止复用代码**，仅可参考 UI 交互

## 首次启动流程（方向，未落地）
主推连接 Bitwarden → 次选创建本地 KDBX → 兜底"稍后在设置里连接"
- 需区分**库凭据**（Bitwarden 账户密码 / KDBX 主密码）与**应用锁**（PIN / 生物识别）
- 建议 M2 后两入口并列，契合"聚合"能力

## 目标平台与发布（2026-09-07 确认）
- **Android 17 = API 37**：minSdk 26 / targetSdk 37 / compileSdk 37
- 只出 arm64-v8a；**本地只小编译不出包，GitHub Actions 负责出包**
  （main → debug preview Release；rele 分支或 v* tag → 签名 release APK）
- CI 脚本 `.github/scripts/ensure-android-sdk.sh` 必须装 `platforms;android-37`
  （原为 36，与 compileSdk=37 冲突会导致 CI 失败；已修，并保持 LF 换行）
- 本机 SDK `C:\AndroidSDK` 有 35 / 37 / 37.0，**无 android-36**，故 compileSdk 必须 ≥ 37

## 协作文件夹（均已入库，便于 AI 接力）
- `Docs/progress/`：environment（环境+构建策略）/ current-status（进度快照）/
  next-steps（下一步清单）/ decisions（决策记录）
- `.ai/`：MEMORY.md（长期约定）/ ISSUES.md（踩坑：现象-根因-解法）/ SESSION-*.md（会话日志）
- 两者内容同步自 `.workbuddy/memory/`

## 签名与发布（2026-09-07 配置完成）
- 4 个 `SIGNING_*` Secret 已通过 `gh secret set` 上传到 GitHub（keytool 生成，RSA 2048，有效期 10000 天）
- jks 本体：`D:\vaultix-release.jks`（**放在项目外**，且 .gitignore 已忽略 *.jks，绝不会误提交）
- alias：`vaultix`
- ⚠️ 密码**未记入本文件**（避免明文泄露）；用户需自行备份「jks + 密码」，二者缺一即无法再发布更新
- debug(preview) 与 release 复用同一套密钥 → 同一设备可互相覆盖安装（前提：applicationId 相同、debug 不加 applicationIdSuffix）

## CI 修复记录（2026-09-07，共 7 处，现已全绿）
| # | 问题 | 修复 |
|---|---|---|
| 1 | ensure-android-sdk.sh 装 android-36，但 compileSdk=37 | 改为 android-37 |
| 2 | runner 上 platform 目录为 android-37.0/37.1/37.2，**无** android-37 | 校验改前缀匹配 `android-37*`（本机两者都有，本地不暴露） |
| 3 | `cd app/build/outputs/apk/*/debug` 多 flavor 展开报 too many arguments | 改为从 apk 根目录 find |
| 4 | workflow `paths` 缺 .sh / .toml / .github/scripts/** | 补齐（否则改构建脚本不触发 CI，形成盲区） |
| 5 | `:app:lintDebug`、`:app:testDebugUnitTest` 多 flavor 歧义 | 改 lintFullDebug+lintOfflineDebug、testFullDebugUnitTest |
| 6 | Lint 与 Gradle 配置缓存不兼容（ConfigurationCacheError） | lint 步骤加 `--no-configuration-cache` |
| 7 | androidTest 缺 Compose BOM，`ui-test-junit4` 版本为空 | 补 `androidTestImplementation(platform(bom))` |
| 8 | 单测步骤只跑 `:app`（当时无用例，形同虚设） | 追加 `:core:crypto:testDebugUnitTest` |

**验证结果**：push 触发（1m59s）与手动触发含 lint（3m46s）均为 success；
日志确认签名走真实 keystore（validateSigningFullDebug/OfflineDebug 通过），"一次性密钥"提示已消失。

## Bitwarden 认证链路完成（2026-09-07）
- `BitwardenAuthRepository`：prelogin → 派生 MasterKey → masterPasswordHash → connect/token
  - 盐 = `email.trim().lowercase()`（官方客户端**实际行为**，非文档表述）
  - Kdf=0→PBKDF2，Kdf=1→Argon2id（缺 memory/parallelism 时用 Bitwarden 默认值 64/4）
  - refresh 用 `Mutex` 串行化：并发 401 只刷新一次
- `BitwardenTokenRefresher`（替换 DI 中的空实现）两个**易踩的点**：
  1. **OkHttp `Authenticator.authenticate` 是同步回调**，不是 suspend → 必须
     `runBlocking(Dispatchers.IO)` 桥接（Bastion 的 `refreshForHost` 同样如此）
  2. OkHttp 只给得到 **host**，而 token 按 server 存储 → 需维护 host→server 映射
- `AuthSession` 持有 MasterKey 供上层解包对称密钥，`dispose()` 清零

## 从 Bastion 文档中学到并已规避的坑
1. **Vaultwarden 忽略 `sinceRevisionDate`** → 增量同步空转；必须先 `GET accounts/revision-date` 预检
2. **死连接挂死**：反代（nginx/Cloudflare）静默关闭空闲连接，复用即挂 →
   `retryOnConnectionFailure(true)` + `pingInterval(30s)`，超时收紧 30s（原 60s 像卡死）
3. **401 必须反应式恢复**：按 host 刷新后重试一次，`priorResponse != null` 即停（防死循环）
4. **上传/下载解耦**：新建条目走轻量 `POST /ciphers`，不等整库下载

## 选型纠正
- 敏感存储**未用** `androidx.security.crypto` 的 `EncryptedSharedPreferences`/`MasterKey`
  （1.1.0 已整体废弃），改为 **Android Keystore + AES-256-GCM**：密钥不可导出、
  每值 IV 随机。已移除 security-crypto 依赖。

## 同步编排完成（2026-09-07）—— 复用了 Bastion 的轮子
- `BitwardenSyncService`：推送 dirty → 预检 revision → 全量拉取 → 安全校验 → 落库
- **直接复用的 Bastion 设计（GPL-3.0，已标注溯源）**：
  - `EmptyVaultProtection`（Bastion 注明源自 Keyguard 安全策略）：
    * 服务端返回 0 条但本地有数据 → **阻止同步**（防服务端故障清空用户数据，不可逆）
    * 数据量骤减 > 50% → 同样拦截并提示
    * 按 Vaultix 的 String 型 vaultId 改写，去掉 Log 以符合项目日志规范
  - 失败分类（参考 Bastion `SyncExecutionOutcome`）：Success / Skipped / Blocked /
    RetryableError / FatalError，让上层能给出**可执行**提示而非笼统"同步失败"
- **刻意不搬**：节流、优先级队列、被动自动同步（服务高频自动同步，M1 用不上，避免过度设计）

## 复用 Bastion 代码的判断标准（经验，供后续参考）
| 可复用 ✅ | 不可直接搬 ❌ |
|---|---|
| 与业务模型解耦的**安全策略 / 算法 / 流程模式** | 强耦合 Bastion 模型与存储的具体实现 |
| 例：空库保护、失败分类、KDF 流程、401 刷新思路 | 例：`BitwardenSyncService.kt`(2594 行) 依赖其 Room/SecureItem/SettingsManager |
| 例：`BitwardenCrypto.kt` 的加密内核（M0 已搬） | 例：UI 层（缠了大量 Bastion 的 SettingsManager） |

判断口诀：**搬"思想"和"无依赖的核"，不搬"缠成一团的业务实现"**。
