# Vaultix 项目长期笔记

> **当前迭代状态**：`Docs/progress/next-steps.md`（待办清单）与 `Docs/progress/current-status.md`
> （进度快照）为准；逐轮流水见 `.ai/SESSION-2026-09-08.md` 与 `.ai/SESSION-2026-09-09.md`
> （M2-a 自动填充 / 三缺陷修复 / 快捷入口）；踩坑索引见 `.ai/ISSUES.md`。

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
- **构建/编译 daemon 堆各 4g（`gradle.properties` 的 `org.gradle.jvmargs` 与
  `kotlin.daemon.jvmargs`），禁止回退 2g**：Vaultix 含多个 Compose 模块 +
  Bitwarden 全载荷映射，Kotlin 编译器在分析大型 composable / 生成大段字节码
  （Compose 重启组）时易 OOM，表现为「编译器过大无法编译」。CI `ubuntu-latest`
  16GB 与本地沙箱均足够；日后模块继续膨胀优先上调此项而非加 `-Xms`

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
| 5 | `:app:lintDebug`、`:app:testDebugUnitTest` 多 flavor 歧义 | 改显式 variant（现为 lintFullDebug / testFullDebugUnitTest；offline 自 2026-09-09 起不参与构建） |
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

## 解密链路与 Mapper 完成（2026-09-07）
- `CipherMapper`：toDomain（密文→明文，需账号对称密钥）/ toRequest（明文→密文）
- 解密失败**降级为空串**而非抛异常：个别损坏条目不应让整个列表加载失败
- `unpackAccountKey`：stretchMasterKey → 解包 → **stretched.clear()**（不留在内存）
- **修复 CredentialKeys 真实 Bug**：生成脚本的 `$` 转义问题导致 key 中不含实际 server 值
  （所有库的凭据共用一个 key，多库会互相覆盖）；改为常量前缀 + 字符串拼接
  * 教训：用生成脚本写含 `$` 的代码时，优先用拼接而非模板插值；写完必须校验输出

## M1 数据链路全景（已通）
登录 → MasterKey → 解包账号对称密钥 → 解密条目字段 → VaultItem（可显示）
剩余：UI 层（登录页 / 列表页）、WorkManager 周期同步、Detekt 门禁

## P0 最小闭环完成（2026-09-08）
- **UI 最小闭环已通**：库列表 → 连接 Bitwarden 表单 → 解锁 → 条目列表 → 新建条目
  （scope 口径经用户确认：不做搜索/筛选/多选/详情页）
- **关键语义：`VaultEntity.id = 规范化服务器 URL`**（trimEnd('/')）——与认证层
  token 按 server 键控一致；代价是**同一服务器仅支持一个账号**（decisions 已记录）
- Room v2：vaults 增 `account`（邮箱）列，Migration 1→2（DatabaseModule 已挂）
- `VaultSessionManager`（data:repository）：内存持 `SymmetricCryptoKey`、
  lock 清零幂等、unlock 覆盖先清旧密钥；**5 个 JVM 单测**（含清零断言）
- domain 首个真实接口：`VaultRepository` / `ItemRepository`（+UnlockResult/SyncReport/
  VaultSaveOutcome）；data:repository 首个真实实现
- **新建条目链路**：本地 uuid 密文行 + pending_ops(CREATE) → `flushPending`
  轻量推送（Bastion 教训：不等整库下载）→ 服务端新 id 时**本地行重映射**
  （删临时行 + 请求密文重建，`CipherRequest.toStoredCipherDto`；请求密文即服务端密文）
- `CipherMapper` 补 password 双向字段（VaultItem 现 6 字段）
- 解锁 == 联网重新登录（M1 无离线解锁）；2FA 登录不支持但**错误分类**可区分
  （401 / 400 two_factor）并给可执行提示
- UI 文案在 `strings.xml`；sync 提示文案暂由 data 层中文直供（简化，已记录）
- app → domain/data:repository 依赖已装配；offline flavor 用 `AppFlavor.supportsBitwarden`
  隐藏入口，两 flavor 编译均绿
- M1 剩余：条目编辑/删除、自动锁定（AppLifecycleObserver）、移除库、文案收编

## P0 全部落地（2026-09-08 第二轮，详见 SESSION-2026-09-08.md「第二轮」）
- 条目详情（S9 最小版）+ 编辑（S10 最小版）+ 软删除（回收站语义）
- `VaultixClipboard`（app）：敏感复制 + 自动清除，**借鉴 Bastion ClipboardUtils
  （GPL 溯源已标注）**：触发即忘延迟清空、清空前校验内容未被改写、API33+ IS_SENSITIVE；
  时长接 `clipboardClearMs`（默认 30s）
- `AutoLockController`（app）：ProcessLifecycleOwner 切后台计时（elapsedRealtime）、
  回前台超时（`autoLockTimeoutMs` 默认 5 分钟）即 `lockAll()` + 锁定代次事件强制回根路由；
  注册在 VaultixApplication（Hilt 字段注入）
- ItemRepository 新增：`observeItem` / `updateItem`（沿用原 id）/ `softDeleteItem`
  （本地标记 deletedDate + SOFT_DELETE 入队）；单测 11 个全绿
- 复用小结（写 UI 前先翻 Bastion）：Bastion 详情/编辑页 3371/4732 行巨型文件
  **不可整搬**（Docs/16 反例），只借鉴其小型工具与语义（ClipboardUtils、SessionManager
  计时规则）；搬运必须 GPL 溯源声明

## Detekt 门禁上线（2026-09-08 第三轮）
- **`dev.detekt` 2.0.0-alpha.6**（官方兼容表精确对齐 Kotlin 2.4.10/AGP 9.3/Gradle 9.5；
  1.23.x 仅到 Kotlin 2.0 不可用；2.0 稳定后需升级）
- 根 build.gradle.kts `subprojects` 统一 apply（android-application/library 插件回调），
  config: `config/detekt/detekt.yml` + `buildUponDefaultConfig`，阈值=Docs/16 硬上限
  （LongMethod≤150 / LargeClass≤1200 / 参数≤8 / 单类≤40 函数）
- 豁免：Compose `@Composable` PascalCase（ignoreAnnotated）、命名参数 MagicNumber；
  crypto「有意捕获」用 **带理由的 @Suppress**（勿删）
- 本地命令 `gradlew detekt`（含测试源）；CI push/PR 门禁 + `config/**` 触发路径
- 清理动作范例：ItemDetailScreen 拆分 DetailBodyContent 降圈复杂度 17→≤14、
  ItemRepositoryImpl 解密 flowOn 注入 @CryptoDispatcher（InjectDispatcher 规则）、
  RepositoryModule abstract class→interface、网络超时 30s 常量、`delay(2_000)` 常量

## 自动锁定升级（2026-09-08 第四轮，参考 Bastion）
- **档位（分钟）**：`VaultixPreferences.autoLockMinutes`（int key auto_lock_minutes，
  默认 5）：0=切后台立即锁 / >0=离开 N 分钟锁 / <0=从不；**-2 重启后锁定不需要**
  （密钥只在内存，重启天然锁）
- 判定纯函数 `AutoLockPolicy`（app/security）+ 4 单测；`AutoLockController` v2：
  onStop 立即档/计时，onStart keyguard 仍锁或超时 → lockAll + lockEvents 回根
- **Hilt 断环范例（重要）**：OkHttpClient↔Retrofit.Builder 构造期环曾潜伏数轮，
  直到 ViewModel 注入点展开全链才暴露；解法 = `BitwardenAuthenticator` 注入
  `Provider<TokenRefresher>`，401 回调时才 get()（懒断环；eager 会死锁）。
  设计网络栈时注意 client→auth→client 自环
- 待办：档位 UI（Bastion 选项 0/1/5/10/15/30/60/300/1440/-1 与
  getAutoLockDisplayName 文案可参考）→ 设置页最小版任务

## 2FA 登录 + 签名修复（2026-09-08 第五轮，真机驱动）
- **2FA（经典 OAuth 扩展，Bastion 同款）**：password grant 400 → 解析
  `TwoFactorProviders`（字符串/数值数组、Pascal/camel 键均兼容）→ UI 验证码
  步骤 → 原 grant 追加 twoFactorToken/Provider/Remember 重发；码错再收挑战 →
  TwoFactorInvalid（专属文案）。AddVault/Unlock 共用 TwoFactorStep；
  provider 0=TOTP / 1=Email（服务端自动发码，无需 email/send 端点）
- **真机抓包关键**：Vaultwarden ≥1.33 prelogin 返回 **camelCase**（kdf…），
  官方仍 PascalCase → DTO 双形态 + resolvedXxx（PreLoginResponseTest 回归）
- **CF 后自托管**：请求头组与 Bastion 逐值一致（桌面 Chrome 131 UA +
  Sec-Ch-Ua 三件套 + Keyguard-Client + Accept-Language + Bitwarden-Client-Name
  desktop/2025.1.0），NetworkModule 文件头带 GPL 溯源
- **签名事故（重要教训）**：CI「validateSigning 通过」≠ 固定密钥——此前
  SIGNING_KEYSTORE_BASE64 解码失败走一次性密钥 fallback，每个包签名都不同、
  永远无法覆盖安装（validateSigning 对 fallback 也通过，早期笔记误判）。
  已重生成 D:\vaultix-release.jks 并重传 secrets；判断标准必须是 CI 日志
  「固定密钥解码并校验通过」notice
- 新 jks 密码：**D:\vaultix-signing-passwords.txt**（项目外；用户需自行备份
  jks+密码，缺一则无法再发布）；旧 jks 备份为 vaultix-release-old-*.jks
- 迁移提示：一次性签名 → 固定签名需**最后一次卸载重装**，此后包可正常覆盖
- **签名两大根因（终于修好，63be53c CI 全绿）**：
  1) GitHub Secrets 不会自动变环境变量——workflow 里 `${SIGNING_STORE_PASSWORD}`
     恒空回落 android → decode 必失败。必须 step env 显式注入（两个 workflow 已修）。
  2) jks 为 **PKCS12：私钥密码 = store 密码**（keytool 忽略不同 keypass）。
     此前把独立随机 KEY_PASSWORD 传 GitHub → AGP "Failed to read key"。
     现 SIGNING_KEY_PASSWORD = STORE_PASSWORD（同值），不可再拆开。
- **判定签名不要信日志脚本回显**（两个分支的 echo 都会被打印）：
  只看 `##[notice]固定密钥解码并校验通过`（成功）或 `##[warning]…一次性`（失败）行

## 本地快速解锁（2026-09-08 完成，Bastion 同款模型）
- 登录/主密码解锁后：账号对称密钥 64B 用 **Keystore user-auth KEK**（AES-GCM）
  包裹落盘（SecureCredentialStore key `local_unlock_key::<vaultId>`），锁库只清内存；
  再次解锁 = BiometricPrompt（API30+ 生物识别或设备 PIN，26-29 仅强生物识别）
  → 本地解封 → 免主密码/免 2FA/离线。指纹增删自动 invalidate KEK → 回退主密码
- 代码位：`core:datastore/LocalUnlockKeyStore`；domain VaultRepository 新增
  localUnlockAvailable/enrollLocalUnlock/prepareLocalUnlock/prepareLocalEnroll/
  completeLocalUnlock/disableLocalUnlock；VaultixPreferences per-vault 开关 +
  横幅 dismissed 标记
- UI：MainActivity→FragmentActivity（BiometricPrompt 宿主，fragment-ktx 依赖）；
  `BiometricPrompter`（DEVICE_CREDENTIAL 组合规则：30+ 无负按钮/低版本需负按钮）；
  UnlockScreen「生物识别/设备 PIN 解锁」按钮（fallback 主密码）；
  VaultList 登录后一次性启用横幅（拒绝后不再打扰，设置页可关）
- 登录设备登记：connect/token 必须把 device-type/device-identifier/device-name
  放 **HTTP Header**（仅 body 字段服务器不认）；deviceType 0=Android（曾误用 1=iOS）

## 2FA 多方式（2026-09-08）
- TwoFactorStep 列出服务器下发全部「可输码」provider（TOTP/邮箱/Duo/YubiKey/org-Duo），
  枚举官方值 2=Duo、3=YubiKey、4=U2F、7=WebAuthn（U2F/WebAuthn 浏览器专用不展示）
- YubiKey OTP 44 位字母数字输入（触控生成），数字类仍 6 位

## Bastion 冻结为 reference（2026-09-08 用户拍板）
- ~~Bastion 代码与 GitHub 均不再动~~；Vaultix = 唯一演进线

## ⚠️ 策略变更（2026-09-08 晚，用户重新拍板，推翻「不做文件级搬迁」）
**用户决定**：保持 Vaultix 架构（多模块 / Bitwarden canonical 模型 / CipherDto 密文存储），
把 Bastion 的**设置、密码条目、验证码条目、通行密钥、卡包**的代码与 UI **先整体搬过来再改**
——用户认为逐项对齐效率太低，Bastion 实现更完善。

**执行边界（搬迁时必须替换的部分，勿把 Bastion 数据层一并搬入）**：
- 包名 `com.bastion.app.*` → `io.vaultix.*`；GPL-3.0 溯源声明必须保留（双方均 GPL，搬代码合法）
- **数据模型不搬**：Bastion 的 PasswordEntry / SecureItem / 自有 Room 表 → Vaultix 的
  VaultItem / VaultRepository / CipherDto 密文链（否则 M1 保真成果全部作废、回到明文 Room）
- SettingsManager / DataStore 键 → Vaultix 的 VaultixPreferences；DI → Vaultix 的 Hilt 图
- 参考源 = `reference/bastion/`（vendored @369ed56）

**搬迁顺序（用户五模块）**：① 密码条目（Add/Edit 表单 + 生成器，进行中）
② 验证码条目（TotpGenerator 五类型 + 编辑 UI）③ 设置 ④ 通行密钥 ⑤ 卡包。
回收站自动清理随密码条目批次一起。

**本轮已落地（第一批）**：
- core:common `PasswordGenerator.kt`：从 Bastion 搬生成核（SecureRandom + Keyguard
  最小字符数算法 + 排除相似/歧义 + 洗牌 + PIN + 内置词表 passphrase），
  **去除 zxcvbn / Context / Bastion logging 依赖**（强度分析沿用 Vaultix PasswordStrength）
- `ItemFormDialog`：内容区加 `verticalScroll` —— 修复「验证码下方区域不可见」
  （AlertDialog 不滚动，身份 17 字段/自定义字段加入后内容超高被裁剪）
- 密码字段加「生成」按钮 + 生成选项对话框（长度/大小写/数字/符号/排除相似/排除歧义），后续"搬代码"= 在 Vaultix 架构上重写
- 正确姿势：只搬三类资产（行为知识 / 测试向量与保真矩阵 / 无依赖的核），**不做文件级搬迁**（Bastion 主源码 ≈ 664 文件 / 25.8 万行、单模块）
- 参考索引、别搬清单与对拍流程：`Docs/18-Bastion参考地图.md`；决策：`Docs/progress/decisions.md`
- **仓库内快照 `reference/bastion/`**（vendored @369ed56，≈13 MB：主源码 664+单测 155+
  仓库 docs+BastionDocs md+workflows 参考）：接力 AI **无需访问 D:\Bastion**，本目录
  只读参考、不参与构建/detekt；路径与 Docs/18 前缀一致；详见其 README.md
- 高频参考（快照内：`reference/bastion/docs/`；本地 clone `D:\Bastion\bastion`，dev 分支；`app/` = `Bastion/app/src/main/java/com/bastion/app/`）：
  - M2 KDBX：`app/utils/KeePassKdbxService.kt`、`app/keepass/KeePassFieldRegistry.kt`、`app/data/LocalKeePassDatabase.kt`
  - M1 核对：`app/bitwarden/service/BitwardenSyncService.kt`、`app/bitwarden/api/BitwardenApiFactory.kt`
  - Bastion 内部文档：仓库根 `docs/`（bitwarden同步与密码库生态.md 等）；文档可能滞后代码，以 dev 代码为最终事实

## 同步编排 UI 接线完成（2026-09-08 第九轮）
- `BitwardenSyncOrchestrator` **Hilt 双构造**：@Inject 两绑定参数（VaultRepository/
  VaultSessionManager）公开构造委托 internal 五参完整构造（scope/config/now 仅供
  测试虚拟时间注入）——经验：@Inject 构造只能含可绑定参数，测试注入口走 internal
  次构造；Kotlin 次构造参数不能带 val，属性须提到类体统一赋值
- 触发接线：条目页 init=PAGE_ENTER（90s 节流）、手动刷新=MANUAL force、回前台=
  AutoLockController.onStart 对已解锁库逐库 APP_RESUME（180s 节流、库锁门卫在
  编排器内）；**UI 不再直调 syncVault**
- 提示语义（Bastion 静默同步）：自动同步成功不打扰；手动成功短暂提示；最近错误
  （errorAt>successAt）常驻提示可重试；库列表行内仅显示「同步中/同步失败」，静默
  成功不显示
- 新建/编辑密码框带**强度条**（PasswordStrength 0–100，弱→非常强五档文案与颜色
  递进；空密码不渲染；仅提示非强制门槛）

## Bitwarden 对齐审计 + 批 1 落地（2026-09-08 第十轮，审计报告 Docs/progress/audit/bitwarden-alignment.md）
- 背景：用户指出 Vaultix bitwarden 侧不如 Bastion 完整 → 全量差距审计（两枚子代理
  超时无产出 → 主代理基于实测收敛报告），批 1 = **数据安全 + 类型保真**
- **DTO 全载荷承载**：card/identity/secureNote/sshKey + login 的 uri/totp/fido2/
  passwordRevisionDate 补入 CipherDto/CipherRequest（字段集对齐 Bastion
  BitwardenApi.kt，wire 为小写 camelCase）
- **更新 = 合并上传**（toUpdateRequest(item, stored, key)）：未编辑密文段原样并入，
  修复三大真实数据丢失：编辑登录丢 uri/totp；编辑卡/身份/SSH 毁载荷；type5→Login
  漂移（现 mapType 显式 5=SshKey，未知类型由 ItemRepositoryImpl 类型守恒守卫拒写）
- **同步收敛**：全量成功后 prune 本地行（排除 pending ops）；flush 遇 4xx
  （401/408/429 除外）弃单防毒丸；BitwardenSyncService 头注释已更新（编排职责在
  data:repository orchestrator）
- UI：条目列表/详情类型徽标；非 Login 编辑隐藏登录字段并提示「专属字段只读」
- **回收站视图（同批追加）**：observeTrash（deletedDate 非空流）+ restoreItem
  （本地清 deletedDate → RESTORE 入队）+ permanentDeleteItem（DELETE 入队 → 本地
  删行）；条目页顶栏 Delete 图标进 TrashScreen；observeItem 对已删行保持 null
- **登录失效修复（真机驱动，Bastion 对齐）**：请求拦截器预挂 Bearer + expiresIn
  落盘 + 过期前 60s 预刷新（accessTokenForHost）；refresh 结果三分——400/401=
  Invalid（重登），403/429/5xx/网络=Transient（**绝不误报失效**，CF 场景关键）；
  sync 401 按 refreshFailureOf(server) 归类；解锁路径 registerServer（重启+快速
  解锁不失效）；拦截器/Authenticator 均 Provider 懒解析断 Dagger 环
- **工程经验**：KDoc 里禁止字面 `/**`（如 /api/**）——块注释嵌套直到 EOF 不闭合，
  KSP 报误导性连锁错；KSP2 对 @Provides 签名里的具体新类解析有 bug → 签名用接口
- 未做（推迟）：非 Login 专属字段展示/编辑（M2-1）、附件/历史（M2-2）、文件夹管理
  UI（M2-3）——见审计报告 §2 表

## 同步触发策略收敛（2026-09-08 用户真机反馈）
- 用户实测「基本正常」但自动同步太频繁 → **移除 PAGE_ENTER/APP_RESUME 自动拉取**；
  自动同步只随本地修改的 flush 推送；拉取 = 手动（顶栏刷新图标 + 下拉刷新 PullToRefreshBox）
- ItemsViewModel 不再 init 自动同步，暴露 isSyncing 驱动下拉指示器；AutoLockController
  回归纯锁定职责（移除 orchestrator 注入）

## 真机回归修复（2026-09-08 晚，2925396）
用户装 preview 后报告三处问题，已对齐 Bastion 修复并推送：
- **验证码界面不显示标题/账号**：`TotpCodesViewModel.toTotpEntry` 对齐 Bastion
  `TotpDataResolver.fromAuthenticatorKey`——otpauth 解析出的 issuer/account 为空时
  回退到条目名（cipher name）/用户名（username）。Bitwarden 的 `login.totp` 常以裸
  base32 密钥存储（无 issuer/account），此前只显示密钥前缀、账号空白。`TotpRow`
  改用 `entry.title` 显示。
- **密码条自定义字段不显示**：`VaultItem` 新增 `customFields: List<VaultCustomField>`
  + `CustomFieldType`(Text/Hidden/Boolean/Linked，对齐 Bitwarden type 0-3)；
  `CipherMapper.toDomain` 解密并映射 `fields`（含 linkedId）；详情页新增
  `CustomFieldsSection`（Hidden 掩码可点开、Boolean→是/否、Linked→标准字段名）。
  写路径 `toUpdateRequest` 仍复用服务端原密文 `stored.fields`，不丢字段。
- **条目 URI 兼容格式（androidapp:// 等）**：新增 `core:common/UriFormat.classify`
  （移植 Bastion `BitwardenLikeAutofillMatcherNg.normalizePackageName`），归一为
  Website/AndroidApp(packageName)/Other；详情 URI 行显示「应用」标签+包名，已安装
  则可启动应用。单测 9 例覆盖。
- **经验**：Bastion 冻结后取代码走 `reference/bastion/`（vendored 快照），勿碰 `D:\Bastion` 现网；
  涉及「标题/账号回退」「隐藏字段」「URI 包识别」这类交互细节，Bastion 是权威参考。

## 身份条目全字段支持 + 通行密钥绑定校验（2026-09-08，本轮）
- **身份条目（type=4）全字段打通**：`VaultItem` 新增 `VaultIdentity`（17 字段，对齐 Bitwarden
  `CipherIdentityData`：title/firstName/middleName/lastName/address1-3/city/state/postalCode/
  country/company/email/phone/ssn/username/passportNumber/licenseNumber）；`CipherMapper.toDomain`
  解密映射 `dto.identity`、`toRequest` 加密写回 `item.identity`（仅 type=Identity 时写 identity 段，
  防类型漂移）；`ItemDetailScreen` 新增 `IdentitySection`（只读，可复制）。此前 identity 完全未进
  领域模型，详情页静默丢字段——这正是 Bastion 兼容性差的根因之一。
- **SSH 密钥/银行卡**：`BitwardenDto` 字段集已对齐 Bitwarden（sshKey: privateKey/publicKey/
  keyFingerprint；card: cardholderName/brand/number/expMonth/expYear/code），读映射完整，无需改动。
- **通行密钥绑定缺陷核对（用户点名的 Bastion 缺陷）**：Bastion 的 `PasskeyEntry.boundPasswordId`
  恒为 null，通行密钥作为独立 Login 密文（名 `X [Passkey]`）存在，与所属密码条目并无真实关联
  （「假绑定」）。**Vaultix 不存在此缺陷**——`PasskeysViewModel.savePasskey` /
  `ItemRepositoryImpl.updateFido2Credentials` 把凭证写入所属登录条目的 `login.fido2Credentials`
  （`CipherMapper.toUpdateRequest` 逐字段重加密）；`PasskeyMapper.fromCipherResponse` 的 `boundPasswordId
  = null` 仅为 Bastion 单方的引用字段，Vaultix 不采用「独立通行密钥条目」模型。新增单测
  `updateFido2Credentials_writesIntoLoginCipher_notSeparatePasskeyCipher` 锁定该行为，防回归。
- **Detekt**：本轮改动零新增违例；`core:model` 通过。现存 Detekt 违例（app 18 / data:bitwarden 7 /
  data:repository 3 / core:common 1）均来自 M1 relay UI 提交（4401bf2），非本轮引入，待专项清理。

## 专项清理 + 身份/银行卡可编辑 + 主页搜索（2026-09-08 本轮）
**Detekt 门禁 31 处 → 0**（上一轮记录的存量违例已全部清零，双 flavor + 全模块）。
- 机械类：`?: ""`→`orEmpty()`、`listOfNotNull`→`listOf`、`!!`→局部 val、未用变量/属性删除、
  `require(x != null)`→`requireNotNull(x)`（保留「库未解锁」前置语义，勿直接删变量）、
  `sealed class`→`sealed interface`、去掉 `unpackAccountKey` 冗余 `suspend`。
- `InjectDispatcher` 4 处用 `@Suppress` + 理由：2 处是 OkHttp `Authenticator` **同步回调**，
  必须 `runBlocking(Dispatchers.IO)` 桥接（框架线程边界，注入无测试收益）；AutoLockController
  因项目只有 `@CryptoDispatcher`（语义 = KDF/CPU 密集）一个限定符，复用会混淆语义，
  待引入通用 `@DefaultDispatcher` 再改注入。
- 长参数/圈复杂度**靠重构不靠抑制**：`DetailBodyContent` 的 5 个复制回调收敛为 `DetailActions`；
  `toUpdateRequest` 拆 `overlayLogin/Card/Identity/SshKey`；`ItemFormDialog` 拆 `buildSnapshot`。
- 教训：重构后**必重跑 detekt**——本轮三次新增（ItemFormDialog 圈复杂度 18、ItemsScreen 超
  150 行、toUpdateRequest 圈复杂度 16）都是自己引入的，编译/测试全绿也不会报。

**身份 / 银行卡可编辑（用户要求）**
- `ItemFormDialog` 重写为按 `type` 决定字段的通用表单：Login / Card(6) / Identity(17 全量) /
  SecureNote·SshKey（仅名称+备注）。签名收敛为
  `ItemFormDialog(title, initial: VaultItem, saving, onDismiss, onSave)`，`onSave` 回传完整快照；
  `ItemDetailViewModel.updateItem(item: VaultItem)` 同步改为快照入参（原 6 参数版本废弃）。
- 实现要点：用「标签列表 + 值列表」双表驱动 + `Iterator.nextOrEmpty()` 组装对象——既避开
  17 个状态变量，也避开 `getOrNull(3)` 这类会被 MagicNumber 查的字面量下标。
- ⚠️ **关键**：`toUpdateRequest` 原本 `card = stored.card` / `identity = stored.identity`
  （只回传服务端原密文）→ 改为 overlay：本类型按表单明文重加密，非本类型或段缺失才沿用原密文。
  **不改这里「可编辑」就是 UI 假象，保存后服务端数据不变。** 已加 3 个回归测试
  （`updateWritesEditedCardFields` / `updateWritesEditedIdentityFieldsAndClearsBlanks` /
  `updateOfLoginDoesNotClobberUnrelatedStoredSegments`）。
- 未编辑段（secureNote / fields / passwordRevisionDate）始终沿用原密文，防丢载荷。

**主页搜索**：`ItemsScreen` 顶栏加搜索图标 → 展开 `SearchField`；`ItemsViewModel` 加 `query` +
`visibleItems`（标题/用户名/网址，忽略大小写）；空结果区分「库里没有条目」与「没有匹配」。

## 中文注释乱码修复 + 源文件编码门禁（2026-09-08 本轮）
**事故**：`BitwardenAuthRepository.kt` 整文件中文注释被写坏（UTF-8 被按 GBK 误读后再次
存盘，且三字节序列尾字节被 `0x3F` 替换 = **有损**），139 处 U+FFFD。编译与测试都不检查
注释 → 静默入库（`c5a051f`），直到人工阅读才发现。

- **定位**：用 `git cat-file blob <rev>:<path>` 逐提交取**原始字节**比对。
  ⚠️ 不要用 `git show <rev>:<path> > file` —— git-bash 重定向会转码，读数不可信。
  结果：`d689a37` 完好、`c5a051f` 损坏 → 是 `git add -A` 把工作区损坏副本提交进去了。
  修复 = `git checkout d689a37 -- <path>` 再重放本轮改动（用 Python 二进制替换写入，
  确保输出一定是 UTF-8）。
- **门禁**：`.github/scripts/check-encoding.py`，CI push/PR 执行。
  判据必须是「GBK 编码→UTF-8 解码后**全部落在 CJK 区**」；朴素的
  「encode gbk + decode utf-8 成功即乱码」会大量误报——「为」(CE AA→U+03AA)、
  「状态」「值」「只」等正常中文都会被误判（正常中文 GBK 次字节多 >0xBF，
  按 UTF-8 解读落在 U+0080-U+07FF，不在 CJK 区）。
- **教训**：`git add -A` 会把工作区任何损坏一并提交；提交前值得跑一次编码检查。

**暴露缺口（待办）**：条目**编辑**已支持登录/银行卡/身份，但**新建**入口
（ItemsScreen FAB）固定传 `VaultItem(type = Login)` → **目前无法新建身份/卡片条目**，
只能从 Bitwarden 同步过来。补一个类型选择器即可。

## 对齐 Bitwarden 官方 App：补齐 folder/favorite/reprompt/secureNote（2026-09-08 本轮）
用户对着官方 Android 客户端截图提出「字段要补齐，不然拉取和上传都会有问题」。

**根因（重要教训，务必记住）**：`folderId / favorite / reprompt / secureNote`
在 `BitwardenDto` 里**全都有**，但 `VaultItem` 领域模型**没建模** →
`CipherMapper.toDomain` 解析时**直接丢弃**。
> **DTO 有字段 ≠ 数据不丢。** 以后新增/核对 DTO 字段，必须逐字段确认三处：
> `toDomain` 读了、`toRequest` 写了、`toUpdateRequest` 写了——并且补单测。
> 本轮另一个同类坑：`toUpdateRequest` 对这四项固定沿用 `stored` 旧值，
> 等于用户在 Vaultix 里**改了也不上传**。

**已做**（e6b05d6）：VaultItem 加 4 字段 + `VaultReprompt` 枚举（替代裸 Int，
避免 `reprompt = 1`）+ `VaultSecureNote`；Mapper 三处连通；SecureNote 缺省补
子类型 0（服务端对 type=2 期望有 secureNote 段）。单测 4 例，含专门防
「退化成沿用 stored」的用例。

**新建类型选择器**（af41c9e）：ItemFormDialog 加 `typeEditable`（仅新建态，
FlowRow+FilterChip）；`buildSnapshot` 改用**当前 type**（否则选「银行卡」仍存成
登录条目）；`createItem` 改收完整快照（与 `updateItem` 对称）。

## Bastion 可搬性评估 + 扫码库选型（2026-09-08）
- **自定义字段**：Bastion 只有 **3 态**（title/value/isProtected），**Vaultix 已是
  4 态**（Bitwarden canonical）——Bastion 是子集，且 `CustomField.kt` 是绑
  `PasswordEntry` 的 Room Entity。**不搬，自己写。**
- **folder/favorite**：Bastion 用自己的 `bitwardenFolderId` 中间字段绕（它的领域
  模型与 DTO 隔了一层）；Vaultix 是 DTO↔VaultItem 直连，**DTO 里本来就有，不用看
  Bastion**。
- **AddEditPasswordScreen**（3500+ 行）：强耦合 Bastion 的 SecureItem/SettingsManager
  和它独有的「预设字段」系统（Vaultix 无此概念）。**不搬。**
- 唯一值得借鉴：Bastion 扫码屏的**生命周期/错误恢复设计**（AtomicBoolean 防重复
  触发、scanGeneration 重建 session），但实现绑 ZXing。

**扫码库决定：CameraX + ZXing core（自己轻量封装）**
依据 42matters Google Play SDK 数据：ZXing 集成率 60-69%（第一）、ML Kit 38-47%（第二）。
选 ZXing 理由：① 轻量（~500KB vs ML Kit unbundled +2-3MB）；② **国内可用**
（ML Kit bundled 依赖 GMS，国内必然踩坑）；③ QR 是 ISO 固定标准，解码内核成熟
（相机层用现代 CameraX，不算过时）。

## 剩余 P0 全部完成（2026-09-08 本轮）
- **自定义字段 4 类型编辑器**：Mapper 的 `fields` 从「沿用 stored」改为**按表单意图**
  写回；UI 支持文本/隐藏/布尔/链接（Boolean=开关存 "true"/"false"、Hidden=掩码可显隐、
  Linked=下拉选官方 linkedId）。⚠️ 改行为时两个旧用例失败（它们断言旧的「沿用
  stored」），已更新为验证「UI 原样带回 → 明文往返不变」。
- **收藏 + 主密码二次验证 UI**：数据层上一轮已就绪，本轮接上（标题行星标 + 底部开关）。
- **TOTP 相机扫码**（CameraX + ZXing）+ **单测 20 例**（app 15 + core:model 5）。

**Detekt 两个技巧**：① `catch (ignored: X)` 可放行 SwallowedException /
TooGenericExceptionCaught（变量名匹配 allowedExceptionNameRegex）；
② enum 位置参数算 MagicNumber，改**命名参数** `LoginUsername(code = 100)` 即放行。

## linkedId 官方分段编码（真 bug，用户「去 Bitwarden 官方找」后抓到）
Vaultix 原把 linkedId 当顺序编号（1/2/3/4），**官方是分段编码**：
登录 100 段、银行卡 300 段、身份 400 段（100=Username…305=Number…418=FullName）。
→ `linkedId=100` 匹配不到，Linked 字段退化成「未知关联字段」。
已新增 `VaultLinkedId` 枚举 + `CustomFieldLabels.kt`（用 Map，27 个 when 会撑爆复杂度）。
> **以后对齐字段优先查 Bitwarden 官方**（bitwarden/clients / 官方 SDK），
> 不要只看 Bastion——Bastion 自定义字段只有 3 态，且 folder/favorite 绕了自己的中间字段。

## TOTP 扫码实现要点
用**全屏 Dialog 内嵌相机**而非跳独立页（结果直接回填 totp，**不丢已填内容**；
导航方案会因 AppNavGraph 是 internal + 表单状态被重置而不可行）。
`AtomicBoolean` 保证只回调一次；只取 Y 平面拼 NV21（ZXing 只读 Y）。

**仍未做**：文件夹下拉——`folders` 表有，但 domain/repository **没有读取接口**，
需先新增 `observeFolders`（要解密 encryptedName）。

## 文件夹选择完成（2026-09-08 本轮，UI 三开关最后一块）
`FolderDao.observeByVault` 数据库层早就有，缺的只是上层接口：
- `VaultFolder(id, name)` 领域模型；domain 新增 `FolderRepository`（**只读**——
  M1 不做文件夹增删改，维护由网页端/官方端管理，避免双端冲突）；
- `FolderRepositoryImpl` 与 `observeItems` 同链路形态（密文快照 × 解锁状态 →
  解密，crypto 调度器注入；未解锁返回空列表而非抛异常，UI 平滑降级）；
- `ItemFormDialog` 名称上方加 `FolderPicker`：**仅当库里有文件夹才显示**
  （避免「永远无选项」的下拉），首项「无文件夹」= folderId 置空；
- 坑：`VaultSessionManager.keyOf` 是 **suspend**，`Flow.map` 的 lambda 是 suspend
  上下文可直接调，但抽出的私有函数要记得标 `suspend`；
- 门禁：主函数又超（153 行/复杂度 15）→ 拆出 `FormHeader`
  （文件夹+类型，顺序对齐官方：文件夹 → 类型 → 名称）。

## ★ 新 bug：点候选「退到 Vaultix 不回填」（2026-09-09 用户报告，待修）

现象：登录页出现带验证码的条目 → TOTP 能复制到剪贴板，但**点击候选后密码没填入，
反而跳进 Vaultix 主界面**。

诊断：
- `VaultixAutofillService.onFillRequest` 锁定分支（L147-165）：所有库锁定时只返回一个
  **MODE_UNLOCK 解锁卡片**（不返回任何真实 dataset），点击 → AutofillActivity →
  `AutofillPromptScreen.onOpenVault` → `startActivity(MainActivity)`（L118-124）→ **跳主界面**
- 缺陷本质：锁定状态没有「轻量解锁→自动回填」闭环，把用户丢进 Vaultix 手动操作；
  用户心智是「点一下密码就填进去」（Bastion/Bitwarden 的解锁是 fill 流程内的认证）
- 相关前科：MODE_COPY_TOTP 曾有同类表现，dc7ac19 已修（中转 Activity 不再渲染 UI）

**修复方向（与 Credential Provider 一并做，避免返工）**：
锁定响应改为 → 点击弹出**轻量解锁**（生物识别/主密码，无需进 MainActivity）→
解锁成功 → 重发 fill 或直接回灌原候选 dataset → 自动回原 App；
此解锁链将被 Credential Provider 的 BeginGetCredential 复用（同一条链）。

## ★ Edge/Chrome 填充失效根因（2026-09-09 真机 dumpsys 实证）

现象：Bitwarden / Bastion 能在 Edge 填充，Vaultix 连「密码条目按钮」都不出现。
**结论：Vaultix 缺 Credential Provider**——不是无障碍、也不是 Chromium 白名单。

设备 Android 17。三方注册服务对比（`dumpsys package`）：
- Bitwarden：`AutofillService` ✅ + **`CredentialProviderService` ✅（BIND_CREDENTIAL_PROVIDER_SERVICE）**
- Vaultix：`AutofillService` ✅ 但 **Credential Provider ❌**

**原理**：Android 14+ 起 Chromium（Chrome/Edge）取凭据**优先走 Credential Manager**，
不再主要依赖老 Autofill Framework。Edge 找不到 Vaultix 的 provider → 不发起请求
（现场实证：Edge 前台有焦点但 fillRequest 为 0）。Via 等轻量浏览器仍走老 Autofill
→ 我们能响应（datasets=2）。

**三个待修（按优先级）**：
1. **实现 CredentialProviderService**（Credential Manager 集成）← 让 Edge/Chrome 能用
2. inline suggestions（API 30+）+ `AutofillInlinePlaceholderActivity`（Bastion 15 行 no-op）
   ← 老路径下 Chromium 不显示下拉数据集，需内联候选
3. 字段角色推断（Bastion `AutofillFieldRolePolicy`/`AutofillFieldPromotionPolicy`）
   ← 现象：QQ/Via 上只有密码框有条目、用户名框没有（用户名框无 autocomplete hint
   被判 UNKNOWN，FillPlanner 只在 hasUsernameField 为真时才绑 username）
4. 无障碍兜底（Bastion `BastionAccessibilityService`）降为可选

## M1 字段对齐至此闭合
官方「添加登录」界面的字段（名称/文件夹/收藏/用户名/密码/验证器密钥/网址/
备注/主密码二次验证/自定义字段 4 类型）已**全部可编辑并正确往返服务端**；
「检查数据泄露」（HIBP）与附件/密码历史为 P2 后置。

## M2-a 系统自动填充落地（2026-09-09，详见 .ai/SESSION-2026-09-09.md）
链路：`AssistStructure` → `AssistStructureParser` → `BitwardenLikeAutofillMatcher` →
`FillPlanner` → `FillResponse`（Dataset 列表）。与 MainActivity 同进程，直接读已解锁库明文；
库全锁时走 `AutofillActivity` 认证回灌（解锁 / 搜索 / reprompt 三模式）。

- 匹配对齐 Bitwarden `filterCiphersForMatches`：逐 URI 自带 `UriMatch` 规则（Domain 默认
  = eTLD+1）/ 等价域 / `androidapp://` 包名 / Never 排除；PSL 用完整 Mozilla 公共后缀表
  （~10325 条，非 20 条 stub）。
- **浏览器三条腿（对齐 Bitwarden `AutofillParserImpl`，缺一条就部分浏览器静默失效）**：
  1. **域名三级兜底**：`ViewNode.webDomain` → `BrowserUrlBars`（浏览器包名 + 地址栏资源 id
     双重匹配，30+ 浏览器）→ 结构文本 BFS 扫描（只认末段为字母的 host）；
     兜底域名写 `ParsedStructure.fallbackWebDomain`，**只用于匹配、不用于拒绝判定**。
  2. **字段识别四路信号**：autofillHints（含 Chromium 的 `webUsername`/`webPassword`，且要
     遍历**全部** hint）→ htmlInfo 属性 → inputType → idEntry；只有密码框时把它**上方最近**
     文本框升格为用户名（邮箱框优先）。
  3. **包名闸门** `AutofillRequestContextPolicy`：浏览器无域名时**禁止**退化包名匹配
     （否则把浏览器自己当条目身份）+ Bitwarden 同款 blocked packages（android/设置/自身/一加锁）。
- 地址栏节点**不能**进可填充字段（文本含 "login" 会被启发式判成用户名框 → 把账号填进地址栏）。

## ⚠️ 只构建/发布 full 分发（2026-09-09 用户拍板）
- `offline` flavor（仅 KDBX、无 INTERNET 权限）**暂停参与构建**：CI 收敛为
  `assembleFullDebug` / `lintFullDebug` / `assembleFullRelease`；本地门禁也只跑 full。
- 理由：`data:kdbx` 属 P3 待办，offline 包目前是空壳（无本地库引擎可用）；
  双 variant 编译让 CI 时间与缓存空间翻倍。
- **flavor 定义与 `AppFlavor` 分支代码保留在仓库**，待 Bitwarden 收尾后决定是否做纯本地版。
  恢复点写在 workflow 注释里：加回 `compileOfflineDebugKotlin` / `lintOfflineDebug` /
  `assembleOfflineRelease`。
- 决策记录：`Docs/progress/decisions.md`（2026-09-09 行）。

## 用户反馈三缺陷修复（2026-09-09，6afaaa4）
- ① **「永不锁定」仍锁**：`AutoLockPolicy.neverAutoLock` 写了却没接线，`onStart` 无条件执行
  「屏幕锁定即锁」；叠加档位缓存在 `@Volatile` 字段（初值 5 分钟），冷启动首帧 DataStore
  未到 → 按默认档误锁。解法：`shouldLockOnResume(minutes, screenLocked, timedOut)` 让 never
  最高优先级短路 + 每次判定现取 `prefs.autoLockMinutes.first()`（不缓存字段）。
  > 教训：任何新增规则（息屏重验证…）都可能悄悄覆盖用户显式档位——档位语义必须在
  > **策略层**收口并写单测；生命周期观察者里不要缓存偏好字段（首值是异步到达的）。
- ② **Edge 填充失效**：Edge（`com.microsoft.emmx`）等 WebView 不总上报 `webDomain`
  + 缺 `webUsername/webPassword` hint 映射 → 见上「浏览器三条腿」。
- ③ **条目不能关联 App**：`UriFormat.ANDROID_APP_SCHEME/androidAppUri`（单一真值源）
  + `AppInfo/AppPickerDialog`（LAUNCHER intent 枚举，**不申请 QUERY_ALL_PACKAGES**），
  表单「关联应用」写入 `androidapp://<pkg>`（Bitwarden 官方形态，服务端与其它端都认）。

## TOTP 链路对齐（2026-09-09，第二十三轮）
- **填充后自动复制验证码**（对齐 Bitwarden `isAutoCopyTotpDisabled=false`）：条目有 TOTP、
  页面没有验证码框时，挂 **dataset 级 `setAuthentication`**（API 26+，Bastion 同款取舍，
  不用 API 33 的 `FillEventHistory`）→ 回调 Activity 回填后经 `VaultixClipboard` 复制。
  开关 `autoCopyTotp`（默认开）。
- 字段识别补 Bastion `isOtpHint` 词表；`FillPlanner` 支持「纯 2FA 页面」（只有验证码框）
  单独出建议；FillResponse **最多 10 条 dataset**（Binder 大小限制，超了整包被丢弃）。
- 复制验证码一律走 `VaultixClipboard`（IS_SENSITIVE + 自动清除）：修了验证码总览页
  直接用 `LocalClipboardManager` 的漏洞；手动填充通知新增「复制验证码」动作。

## 通行密钥：能力边界（2026-09-09 核对）
- **现状**：可列表/详情/复制凭据 ID/删除/绑定登录条目/同步服务端（绑定模型正确，
  优于 Bastion 的「假绑定」）；**不能**真正用于 FIDO2 登录——没有 `CredentialProviderService`、
  没有 `androidx.credentials` 接线、没有密钥生成与 attestation（版本号在 version catalog 里
  备好但没引用）。`Docs/06` 有完整设计，属 M2-b 立项项。
- **P0 已修**：`CipherMapper.mapFido2` 只读 8 字段却写 13 字段 → 编辑条目会清空服务端
  `keyValue`/`counter`/`discoverable`（不可逆）；`creationDate` 明文写密文读 → 永远显示「—」，
  现用 `decryptOrPlain` 兼容。

## 自动填充保存流程完成（2026-09-09，C 项）
- **三段缺一不可**：① FillResponse 挂 `SaveInfo`（`AutofillSaveInfo.build`，账号+密码框为
  requiredIds；**无匹配 fallback 分支也要挂**）→ ② 框架回调 `onSaveRequest`
  → ③ 拉起 `AutofillSaveActivity` 确认后落库。此前只差第一段，等于整个链路没生效。
- `AutofillSaveMatcher`（纯函数）：`targetUri`（网页 `https://host` / App `androidapp://pkg`）、
  `findExisting`（同基域 + 同账号 → 提示更新）、`defaultTitle`（域名去 www → 应用名 → 包名）。
- 更新条目时把本次来源网址**并入 uris**（Bitwarden 同款：同账号多域名不重复建条目）。
- 保存必须在**解锁会话**内完成——密钥只在内存，锁定态下不落盘、不排队，只引导解锁。
- 开关 `VaultixPreferences.autofillSavePrompt`（默认开）+ 设置页「保存提示」。

## 快捷入口三件套 + 内联建议降级（2026-09-09，00f4235）
- **键盘内联建议（`InlinePresentation`）不做 / 低优先级**：依赖输入法实现 Android 11+ 的
  IME inline suggestions API，国产输入法（搜狗/百度/讯飞/QQ/微信）基本未接入，
  仅 Gboard / SwiftKey 支持。用户拍板：多数场景不依赖无障碍也能解决。
- **三件套**（全程不依赖输入法与无障碍）：`AutofillTileService`（Quick Settings 磁贴，
  `ACTIVE_TILE` 否则部分 ROM 显示「未激活」）+ `ManualFillActivity/ViewModel`（跨库聚合已
  解锁条目 + 搜索，选中即复制密码并自动回原 App）+ `SmartCopyNotifier/Receiver`
  （复制密码 → 通知接力复制用户名，60s 超时、VISIBILITY_SECRET、复用 `VaultixClipboard`）。
- **参考事实（Bastion 调研）**：其无障碍服务**不做悬浮层**（WindowManager/addView 0 命中，
  无 SYSTEM_ALERT_WINDOW），只在 WebView 场景静默注入（`ACTION_SET_SELECTION` +
  `ACTION_PASTE` 主路径、`ACTION_SET_TEXT` 兜底，幂等防双填 + 临时剪贴板还原 + 包名闸门）；
  磁贴 / 通知 / 智能复制才是真正的保底，且**都不需要无障碍权限**。
- 待办顺序：C 保存流程 `onSaveRequest`（现为空实现）→ B 无障碍注入兜底（最后）→ P0 真机回归。

