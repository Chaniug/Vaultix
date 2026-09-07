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
- **Bastion 代码与 GitHub 均不再动**（仍有人用，保持现网版本）；Vaultix = 唯一演进线，后续"搬代码"= 在 Vaultix 架构上重写
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
