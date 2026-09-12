# Vaultix 项目长期笔记

> **接力顺序（先读这四份，再动手）**：
> 本文件（约定 / 硬约束 / 决策）→ `.ai/ISSUES.md`（坑：现象-根因-解法，最新 #65）→
> `Docs/progress/next-steps.md`（待办，最新在顶部）→ `Docs/progress/current-status.md`（进度快照）
> → `.ai/SESSION-YYYY-MM-DD.md`（逐轮流水，需要细节才翻）。
>
> 最后更新：2026-09-13（第四十九轮交付后）。**本文件已压缩重写**：逐轮叙事折叠进
> 第 10 节「历史轮次索引」，只留「不知道就会写错、且错了不报错」的内容。

---

## 1. 产品定位（2026-09-07 用户拍板）
- **Bitwarden 优先的客户端**，对标 **Keyguard 路线**（区别于 Monica / Bastion 的「本地优先·聚合」）
- 支持 **2 种库**：Bitwarden 云端（主）+ **KDBX 本地**（次）
- 不采纳 Bastion 的「私有本地库」—— 用开放标准 KDBX 承担本地角色，避免用户数据锁定

## 2. 架构约定
- 领域模型以 **Bitwarden `Cipher` 为规范模型（canonical）**，KDBX 为**降级映射端**
- 保真度三级：**无损 / 约定承载**（落 `Vaultix.*` 自定义字段）/ **有损**
  - 强制往返测试：`Bitwarden → VaultItem → KDBX → VaultItem → Bitwarden`
  - 有损字段必须在 `VaultCapabilities` 登记 + UI 提示，**禁止静默丢弃**
- 对齐 Bitwarden **官方客户端实际行为**（非仅文档）：KDF salt 为 UTF-8 字符串；
  `encKeyValidation` 明文为 UUID（非 `"Bitwarden"`）；Vaultwarden 忽略 `sinceRevisionDate`
- **DTO 有字段 ≠ 数据不丢**：新增/核对字段必须逐处确认 `toDomain` 读、`toRequest` 写、
  `toUpdateRequest` 写，并补单测（本项目最贵教训之一）

## 3. 里程碑
| 期 | 内容 | 状态 |
|---|---|---|
| M0 | 基础骨架 + `core:crypto` | ✅ DONE |
| M1 | **Bitwarden 同步** | ✅ 基本收官（等 824c432 三项最终确认） |
| M2 | KDBX 引擎 + 集成 | 🔄 **阶段 A（只读）完成**；阶段 B（写回）未做 |
| M2-a | 自动填充服务（提前启动） | 🔄 主体已落地；继续按真机反馈打磨 |
| M3 | 平台集成（Autofill / 安全中心） | ⬜ autofill 已并入 M2-a；安全中心待做 |
| M4 / M5 | 发布准备 / 1.0 | ⬜ TODO |

## 4. 技术栈与硬约束
Gradle 9.5.1 / AGP 9.3.2 / Kotlin 2.4.10 / KSP 2.3.11 / Hilt 2.60.1 / compileSdk 37 / JDK 17

- **Hilt 与 Kotlin 强绑定**：Kotlin 2.4.x → Hilt **2.60.1**；AGP 9 要求 Hilt ≥ 2.59
- AGP 9 **禁止** apply `kotlin-android`（内置 Kotlin 冲突）
- version catalog 别名避免 `kotlin-` 前缀（撞内置 `kotlin {}` DSL）；KGP 用别名 `kgp`
- 类型安全项目访问器需 `enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")`
- 本机 SDK 在 `C:\AndroidSDK`，**无 android-36** ⇒ compileSdk **必须 ≥ 37**
- **daemon 堆各 4g**（`org.gradle.jvmargs` 与 `kotlin.daemon.jvmargs`），**禁止回退 2g**
  （含多 Compose 模块 + Bitwarden 全载荷映射，分析大型 composable 时易 OOM）
- **detekt `2.0.0-alpha.6`**（精确对齐 Kotlin 2.4.10/AGP 9.3/Gradle 9.5，1.23.x 不可用）
  - 阈值 = `Docs/16` 硬上限：`LongMethod ≤ 150` / `LargeClass ≤ 1200` / 参数 ≤ 8 / 单文件 ≤ 60 函数
  - **行长上限 120**，按 Kotlin `String.length`（UTF-16）算 —— CJK 按**字符**，别用字节数
  - **`ComplexCondition` 阈值 3**（比默认 4 严）
  - Compose `@Composable` PascalCase、命名参数 MagicNumber 已豁免
- **`offline` flavor 暂停参与构建**（2026-09-09 拍板）：CI 收敛为 `assembleFullDebug` /
  `lintFullDebug` / `assembleFullRelease`，本地门禁也只跑 full。flavor 定义与 `AppFlavor`
  分支代码保留在仓库，恢复点写在 workflow 注释里

## 5. 协议、代码来源与参考源
- Vaultix = **GPL-3.0**（2026-09-07 从 MIT 切换）；搬运文件**必须**带溯源声明
- **Bastion**（GPL-3.0，Copyright 2025 JoyinJoester）= 主要搬运源，双方均 GPL ⇒ 合法
- **Keyguard = "All Rights Reserved" / 仅个人使用授权** ⇒ **禁止复用代码**，仅可参考 UI 交互
- 参考源位置：
  | 项目 | 路径 | 说明 |
  |---|---|---|
  | Bastion | `reference/bastion/`（**仓内 vendored @369ed56**） | 只读参考，不参与构建/detekt |
  | Bitwarden Android | `D:\Vaultix-refs\bitwarden-android`（仓外，`--depth 1`） | **功能层真源** |
  | Keyguard | `D:\Vaultix-refs\keyguard-app`（仓外） | **仅 UI 交互参考**，含 `androidLibAutofill/` |
- **复用判断口诀**：搬「思想」和「无依赖的核」，**不搬**「缠成一团的业务实现」。
  可搬 ✅ 安全策略 / 算法 / 流程模式（空库保护、失败分类、KDF、401 刷新）；
  不可搬 ❌ 强耦合其模型与存储的巨型实现（`BitwardenSyncService.kt` 2594 行、
  `AddEditPasswordScreen` 3500 行）、UI 层（缠 SettingsManager）
- 对齐字段**优先查 Bitwarden 官方**（clients / SDK），不要只看 Bastion
- 遇协议层争议 → **先拉规范原文逐字比对**（用户原话：「应该是有标准的才对」）

## 6. 目标平台、发布与签名
- **Android 17 = API 37**：minSdk 26 / targetSdk 37 / compileSdk 37；只出 `arm64-v8a`
- **本地只小编译不出包，GitHub Actions 负责出包**：main → debug preview；
  `rele` 分支或 `v*` tag → 签名 release APK
- CI 脚本 `.github/scripts/ensure-android-sdk.sh` 必须装 `platforms;android-37`；
  runner 上目录名可能是 `android-37.0` ⇒ **校验用前缀匹配 `android-37*`**（保持 LF 换行）
- 签名：4 个 `SIGNING_*` Secret 已上传；jks 本体 `D:\vaultix-release.jks`（**项目外**）、
  alias `vaultix`；密码在 `D:\vaultix-signing-passwords.txt`（项目外）。
  ⚠️ **jks + 密码缺一即无法再发布更新**，需用户自行备份
- **jks 是 PKCS12 ⇒ 私钥密码 = store 密码**（`SIGNING_KEY_PASSWORD = STORE_PASSWORD`，不可拆开）
- GitHub Secrets **不会自动变环境变量** ⇒ workflow 必须用 step `env:` 显式注入
- **判定签名只信 CI 日志的 notice 行**：`##[notice]固定密钥解码并校验通过`（成功）/
  `##[warning]…一次性`（失败）。两个分支的 echo 都会打印，**不要信脚本回显**
- **CI 已修的 7 类坑（勿回退）**：① SDK 装 `android-37`；② 校验改前缀匹配；
  ③ 取 APK 改用 `find`（多 flavor 展开会 too many arguments）；④ workflow `paths` 必须含
  `.sh` / `.toml` / `.github/scripts/**`；⑤ 歧义任务改显式 variant（`lintFullDebug` /
  `testFullDebugUnitTest`）；⑥ lint 步骤加 `--no-configuration-cache`；
  ⑦ androidTest 补 Compose BOM。另：单测步骤必须**显式列出有真用例的模块**（如 `:core:crypto`）
- `ci-debug.yml` 的固定密钥解码**不按事件名 gating**（`event_name != 'pull_request'`），
  改按磁盘上是否有 `release.jks` 注入 —— 否则手动触发的包含一次性 debug key，
  **盖不上 preview 包**（用户只能卸载重装，数据 + Keystore 全丢，伪装成「指纹解锁被清除」）
- **CI 的 non-blocking 步骤**（`Run unit tests (non-blocking)`）失败**不会**让 run 变红，
  但会留 annotation ⇒ **沉默的债**，需定期巡检

## 7. 协作文件夹（均已入库，便于 AI 接力）
- `Docs/progress/`：`environment` / `current-status` / `next-steps` / `decisions` /
  `main-shell-migration` / `audit/`
- `.ai/`：`MEMORY.md`（本文件副本）/ `ISSUES.md`（踩坑，**含推翻链，接力必读**）/
  `SESSION-*.md`（会话日志）/ `README.md`（索引）

---

## 8. 长期约定速查（写代码前必读）

### 8.1 自动填充（autofill / CP）
- **节点准入闸（铁律）**：只有**可编辑控件**才进字段表 —— 有 `htmlInfo` 时 `tag == "input"`；
  无 htmlInfo 时看 className 的 EditText 家族；两者都判不出才放行（保原生 App）；
  带标准 autofillHints 一律放行。依据上游 `ViewNodeExtensions.toAutofillView`
- **语义信号绝不含 `node.text`** —— 浏览器整棵 DOM 都是可填节点，`<label>Password</label>`
  会挤掉真账号框。`text` 只作为字段**值**
- **误弹检测**：上游模型是「**分类结果即证据**」（节点要么归 Login/Card、要么 `Unused` 剔除，
  **没有"信号强度"这一层**）。`IGNORED_RAW_HINTS = [search, find, recipient, edit]`（+ 中文）；
  用户名关键词 = `[email, phone, username]`（★**没有 `login`**）
- ⚠️ **纪律（顺序不能反）**：撤 `strength` 门槛的**前提**是分类层已有否定词。
  以后放宽/新增关键词，必须同步评估这层闸还挡不挡得住
- **用户名升格两条硬约束**：判据用「没有**可见**的 USERNAME」；升格后 `strength → MEDIUM`
- **浏览器三条腿**（缺一条就部分浏览器静默失效）：① 域名三级兜底
  （`webDomain` → `BrowserUrlBars` → 结构文本 BFS）；② 字段四路信号
  （autofillHints 含 Chromium `webUsername`/`webPassword` → htmlInfo → inputType → idEntry）；
  ③ 包名闸门（浏览器无域名时**禁止**退化包名匹配）
- **锁态只有一个口径：活跃库是否解锁**（对齐 Bitwarden `CredentialProviderProcessorImpl`
  的 `activeAccount.isVaultUnlocked`）。⚠️ **禁止**拿「全库快照里存在锁定的库」当判据 ——
  多库并存（Bitwarden + KDBX，或 KDBX 被「切库即锁旧库」锁掉）时它**恒为真** ⇒
  候选被永久清空，且**解锁后判据不变** ⇒ 无限解锁环（`ISSUES.md` #66）。
  四类消费点（CP 列候选 / passkey 断言 / 密码回灌 / 自动填充）必须读**同一个真源**。
- **CP 只声明 `TYPE_PUBLIC_KEY_CREDENTIAL`**（`f815ab2` 反转了「双能力」）。
  ⚠️ 声明 `TYPE_PASSWORD_CREDENTIAL` 会让 Chromium 系把密码请求全路由到 CP、绕过 Autofill，
  而 CP 密码分支一失败就**两条路全废**。密码填充回归 `VaultixAutofillService.onFillRequest`。
  ⚠️ **未决分歧（勿单方面改回）**：Bitwarden 官方 `provider.xml` **是双能力**的，
  故「删能力」更像绕过 CP 密码分支自身缺陷 ⇒ 待真机 A/B 复现后再定
- **保存流程三段缺一不可**：FillResponse 挂 `SaveInfo`（账号+密码框为 requiredIds，
  **无匹配 fallback 分支也要挂**）→ 框架回调 `onSaveRequest` → 拉起 `AutofillSaveActivity` 落库；
  保存必须在**解锁会话**内完成，锁定态不落盘不排队
- **TOTP 无条件复制**（对齐 `AutofillCompletionManagerImpl`）：填充成功后**每次都复制**，
  仅受开关门控；挂 dataset 级 `setAuthentication`（API 26+），一律走 `VaultixClipboard`
- **FillResponse 最多 10 条 dataset**（Binder 大小限制，超了整包被丢弃）
- **Fill Assist**：规则来自服务端 `/api/config` 的 `environment.fillAssistRules`，
  `manifest.json` → `forms.v1.json`（schema 主版本必须 `1`），本地缓存 6h；
  **默认开但有独立 UI 开关**（设置 → 自动填充 → 填充行为）。**不**照搬上游 feature flag
  （自建 Vaultwarden 不返回，会永远关着）
- **填充下拉图标规格**：**20dp 单色语义矢量** + `setColorFilter`（亮 `#44474E` / 暗 `#C4C6CF`），
  行内边距 12/6、最小高度 48dp；品牌行不上色。**禁止**再用彩色启动图标
- **`onFillRequest` 不是挂起函数** ⇒ 读偏好流（`prefs.xxx.first()`）必须在 `scope.launch {}` 内
- **键盘内联建议（`InlinePresentation`）不做**：国产输入法基本未接入该 API（仅 Gboard/SwiftKey）
- 保底三件套（不依赖输入法与无障碍）：磁贴 / 手动填充 / 智能复制通知

### 8.2 锁与解锁 —— **两层语义，代码里是两条独立路径（勿合并）**

| | 真锁（加密门禁） | 查看锁（界面门禁） |
|---|---|---|
| 入口 | 超时到期 / 冷启动 / 退出数据库 / `lockVault` | 主页锁按钮 → `viewLock` |
| 密钥 | **清零** | **原样留着** |
| 恢复 | 主密码（+2FA）+ 联网 | **一次生物识别**，离线 |
| 载体 | `VaultSessionManager` | `VaultSessionManager.viewLockedIds` |
| 根导航 | `RootNavState.VaultLocked(null)` | `RootNavState.VaultLocked(vaultId)` |

三条不变量（破坏必然出「点了没反应」或「解锁完又要验证」）：
1. `viewLocked` 在 `RootNavViewModel` 判定里**必须排在「已解锁」之前**（查看锁的库在 `unlockedIds` 里）
2. 解锁页自动选库**先看 `isViewLocked`**，再看 `!unlocked`
3. 真锁路径（`lock / lockAll`）要**一并清查看锁标记**，否则标记残留把用户死锁在
   「只需认证、但密钥已不在内存」的页面

成功分支靠**显式参数**区分（`completeLocalUnlock(cipher, forViewLock)`），
**不要**读调用时刻的状态（认证对话框期间可能被别的流改写）。

其他：
- **锁态模型按 Bitwarden 重写**：`VaultTimeout` sealed class（10 档位）+ 后台
  `launch { delay(t); lock() }` / 前台 `cancel` job；CP 侧由 `RootNavViewModel` 集中判据，
  锁定只给 `authenticationActions`；`CredentialProviderActivity` 作 trampoline（`exported=false`）
- ⚠️ **迁移陷阱**：旧 `auto_lock_minutes = -1` = 「**从不**」；新 `VaultTimeout` `-1` =
  `OnAppRestart`「**重启即锁**」—— 语义正好相反，已做一次性迁移 + 标记
- **Android 16+ `Settings.Secure` 对第三方 App 受限** ⇒ 凡读系统设置判状态的检测，
  取向一律「**读不到 = 已启用**」（⚠️ `adb shell settings get` 有值 ≠ App 内读得到）
- **快速解锁四铁律**：① `submitting` 必须由「成功 / 失败 / **任何**弹窗错误」三路复位
  （只处理「用户取消」会整页死锁）；② `UserNotAuthenticatedException` **不是**「密钥已废」，
  不得据此清用户注册；③ **解密路径绝不新建 KEK**（`loadKey()` 只读，只有启用路径
  `obtainOrCreateKey()`）；④ 判 KEK 健康用 `getKey`（三态 `kekStatus`），
  **不要用 `containsAlias`**（失效时静默返回 false）
- 快速解锁模型：账号对称密钥 64B 用 Keystore user-auth KEK（AES-GCM）包裹落盘
  （`local_unlock_key::<vaultId>`），锁库只清内存；指纹增删自动 invalidate KEK → 回退主密码
- **敏感存储未用** `androidx.security.crypto`（1.1.0 已整体废弃），改用
  **Android Keystore + AES-256-GCM**（密钥不可导出、每值随机 IV）

### 8.3 M2 KDBX
- **引擎选型定案**：直接用 **`app.keemobile:kotpass:0.13.0`**（Maven Central，**MIT**，
  纯 Kotlin/JVM，唯一传递依赖 okio）。**不搬 Keyguard**：其本体 `All Rights Reserved`；
  其 `util/kdbx/` 只是 vendored kotpass 且被改过（换成 Keyguard 自己的 crypto）；GPL 兼容性有问题
- **KDBX 与 Bitwarden 是两套会话模型**（第 4 批全部复杂度来源）：

  | | Bitwarden | KDBX |
  |---|---|---|
  | 内存会话 | 一把对称密钥（`VaultSessionManager`） | **整库明文**（`data:kdbx` 会话） |
  | 条目存储 | Room `ciphers`（密文） | **只在内存**（不写 ciphers 表） |
  | 解锁 | 联网 + 可能 2FA | 离线（文件 + 主密码 + 可选 keyfile） |

- **门面** `io.vaultix.data.kdbx.Kdbx`（`unlock / contentOf / isUnlocked / unlockedIds /
  lock / lockAll`）+ `KdbxSource`（`(uri) -> ByteArray?`，由 `data:repository` 用 `ContentResolver` 实现）。
  `KdbxSession / KdbxOpener / KdbxSessionStore` **永远保持 `internal`** —— 它们握着 kotpass 的
  `KeePassDatabase`（明文整库），绝不能进跨模块签名（一次 `println` 就可能把明文写进日志）
- **读路径分流**在 `ItemRepositoryImpl.observeItems / observeTrash / observeItem`，UI 与
  自动填充侧零改动。⚠️ 库种类是**挂起**查询（`vaultDao.get`）⇒ 必须放 `flatMapLatest`，
  不能写在方法体里
- **会话变化通知** = `KdbxSessionFlow`（**只带一个代次计数**）。任何改变会话集合的动作
  （解锁 / 锁定 / 移除 / 退出数据库 / 切库）都要 `bump()`，**漏一处就「解锁了但列表还是空」**。
  给明文会话挂 `MutableStateFlow` 是**反模式**
- **切库即锁旧库**在 `ItemsViewModel.init`（`lockOtherKdbxVaults`）：KDBX 那把「密钥」是整库明文，
  多库同时解锁会让「同时只能进一个库」的内存约束失效
- **keyfile 只存 URI**（`VaultixPreferences.kdbxKeyFileUri`），内容现读；
  UI 必须 `takePersistableUriPermission`（否则「今天能解锁、明天说读不到文件」）
- **失败必须分类**：`SourceUnavailable`（读不到文件）≠ `InvalidCredentials`（密码错）——
  一律报「密码错误」会让人反复重输正确的密码
- **两个 codec**（`KdbxTotpCodec` / `KdbxPasskeyCodec`）都提供 `isXxxFieldName`，
  `customFieldsOf` 用它排除专属字段 —— 否则详情页会把**私钥 PEM / TOTP 密钥**当
  「隐藏自定义字段」展示。最易错两点：位置式 `TOTP Settings` 按**出现顺序**填；
  `TimeOtp-Secret-Hex|Base64` 必须**真解码再转 base32**（当 base32 解析会**静默算错码**）
- **阶段 B（写回）未做，铁律预告**：**插件字段（`KPEX_*`）与不认识的自定义字段一律原样保留**
  （丢一个 = 用户通行密钥失效）；写回走**整体重建 + 原子替换**（先写临时文件再 rename）+
  `.kdbx.bak` 备份；往返测试**逐字段相等**。目标格式 **KDBX 4.1**
- 写回预留钩子：`Kdbx` 门面补 `save(vaultId, ...)`；`KdbxSessionStore` 已持 `KeePassDatabase`
  （`encode` 可用）；`KdbxMappedContent.groupPaths`（uuid → 路径）就是为写回预留的
- 未做：KDBX 快速解锁（KEK 包裹，免每次输主密码）；KDBX 回收站映射
  （现在只 `recycleBinCount` 计数，`ItemRepositoryImpl.observeTrash` 对 KDBX 明确返回空）

### 8.4 UI / 观感
- **站点图标**：端点 `<服务器>/icons/<域名>/icon.png`（同 Vaultwarden）。三条取舍：
  **剥掉服务器路径**（反代下端点在站点根）、**只收 https**（cleartext 会被静默拦）、
  **保留 `www.`**。⚠️ **域名必须过白名单**（仅字母/数字/`.`/`-`）—— 它会被拼进 URL 路径，
  不设防就能改写请求目标。纯字符串实现放 `core:common/SiteIconUrl`（零 Android/零网络）
- **顶栏**：动作收敛为 **🔍 + ⋮**（低频动作进 overflow）；
  **筛选条必须浮在内容之上**（与顶栏同层 + `matchParentSize()` 透明遮罩）——
  塞进可滚动 `Column` 会随列表滚走而箭头还指着「已展开」；
  **筛选状态刻意不持久化**（落盘会让用户下次对着空列表发呆）；有筛选时标题必须拼
  「**库名 · 筛选名**」（只显示库名，用户会以为数据丢了）
- **让位必须做在滚动内容里**（`LazyColumn.contentPadding` / 可滚动的 `Spacer`），
  **绝不**做外层容器 `padding`：做在外面，内容就永远到不了那片区域 ⇒ 顶栏透明了也不沉浸、
  筛选条压住首条、搜索态「Scaffold 让位 + 外层 padding」双重留白成黑横幅（`ISSUES.md` #67）。
  搜索态还必须自己 `BackHandler` —— 主界面是**根路由**（栈里没有上一层），不拦就退桌面。
- **底栏是叠层悬浮**（不是 `Scaffold(bottomBar)`）：内容铺到屏幕底，各页用 `bottomInset`
  （`rememberBottomDockInset` = 胶囊 86dp + 系统手势条）自己留位。
- **隐藏手势必须有可见反馈**：长按滑删的 armed 态要给 32% 红底 **+ 让出 16dp 缝**
  （只调 alpha 看不见 —— 删除底被不透明卡片完全盖住）。功能存在 ≠ 用户知道它存在（#68）。
- **沉浸式顶栏三个前置条件缺一不可**：Box 叠加 + 列表顶部让位 + 外层 insets 归零
  （少一个就退化成「顶栏变短但内容仍被压着」）
- **Tab 动效规格**（Bastion 移植）：进入 `fadeIn + slideInVertically(1/16 屏高)` 220ms，
  退出 `fadeOut` 120ms，缓动 `CubicBezierEasing(0.6f, 0f, 0.4f, 1f)`；
  配 `AnimatedContent(contentKey = tab)` + `SaveableStateHolder`（**切 Tab 不丢滚动/搜索词**）；
  验证码倒计时用 `rememberTotpSmoothProgress`（秒级数据 + 绘制层 1s 线性动画，翻转 `snap()`）
- **条目卡片**（`ui/common/EntryCard.kt`，照 Bastion `PasswordEntryCard`）：M3 默认 Card +
  **12dp 圆角** + **16dp 内边距** + 标题 SemiBold + 6dp 行距；三个列表（密码/验证码/卡包）统一。
  ⚠️ 「外框」是**组件级卡片样式**，与「壳」（导航/容器/转场）无关
- **搜索输入框绝不能放进 `LargeTopAppBar`**（高度随滚动变化 ⇒ 输入框被反复垫高、焦点漂移）；
  Bitwarden 做法 = 固定高度 `TopAppBar` + 搜索态**整体占据 `title` 槽**（与标题二选一，
  调用方写 `if (searchActive) 搜索顶栏 else 普通顶栏`，**整体替换不叠加**）；
  `searchActive` 用 `rememberSaveable`
- **改点击语义必须补回被挤掉的入口**：整行点击从「编辑」改成「复制」后，编辑入口若不显式补
  一个，条目就再也改不了
- 详情页**不做动态验证码**（降功耗），只提示「含验证码 / 已绑定 N 个通行密钥」
- 同一功能在项目里出现 3 种不同写法 = 高危信号，先统一再修

### 8.5 通行密钥（WebAuthn / CP）关键判据
- **注册与断言两条流程都回传自建的 `clientDataJSON` 真实 JSON**，唯一差别是
  **签名覆盖哪份哈希**（浏览器用系统给的 `clientDataHash`，原生流程用 `sha256(自建 JSON)`）；
  **浏览器流程不写** `androidPackageName`（浏览器那份 JSON 没有该字段）。
  ⚠️ **绝不回传空占位符** —— 官方文档 `set a placeholder value` 那句带前置条件
  `If you retrieve an origin`（仅特权应用名单场景适用），而 W3C L2 §7.1/§7.2 要求 RP
  **解析明文**校验 `C.type` / `C.challenge` / `C.origin` ⇒ 空数组连 JSON 解析都过不了
- **`rawId` / `userHandle` 是不透明字节串**：库里 `credentialId` 有两种形态 ——
  Vaultix 自建 = `base64Url(32字节)`（43 字符），**Bitwarden 同步 = UUID 文本**（36 字符）。
  ⚠️ UUID 文本字符集 `0-9a-f-` 全落在 base64url 字母表内、长度 36 = 4×9 ⇒「能不能 base64 解码」
  会**误判成 base64**。正解：**先 `UUID.fromString → 16 字节 → base64Url`**，再 fallback
- **`allowCredentials` 是提示不是授权门** ⇒ 严格匹配为空要**回退到「只按 rpId」**（宁可多列不可漏列）
- **rpId 两侧都归一化**：`trim → trimEnd('.') → lowercase(ROOT) → IDN.toASCII(USE_STD3_ASCII_RULES)`
- 官方客户端读字段一律 `trim()`；`counter`/`discoverable` 不 trim 会**静默回落默认值**
- **`signCount` 恒 0**（0 = 不实现计数器，规范允许）；递增必然跨设备分叉
- 响应 JSON 必带 `clientExtensionResults:{}` + `authenticatorAttachment: platform`
- **origin 取值顺序**：`requestJson.origin` → `CallingAppOrigin` → `https://$rpId`
- 全库锁定时**只返回 `authenticationActions`(unlock)**、不带 credentialEntries
- **铁律：验签失败不要在注册期字段（BE/BS）上找原因**；**「列表为空」必须逐级量化埋点**
  （`total/unusable/rpIdMiss/allowedMiss/matched`）
- **先证伪「自己失败了」**：用系统侧日志证明"我们没失败"，再压到字段级
- `CallingAppInfo.getOrigin(allowList)` 的 allowList 是**签名背书名单**（非可选占位）⇒
  正解 = **自证式读取**（拿调用方自己 `signingInfo` 算 SHA-256 指纹，拼只含它自己的名单）。
  ⚠️ **读第三方 API 不能只看方法签名猜语义，要反编译看实现**

### 8.6 工程质量
- **detekt 两个必须知道的坑**：① `CyclomaticComplexMethod` 会把**同文件**被调私有函数的
  复杂度**累加**进调用方（实测拆同文件 helper：19 → 41，阈值 14）；本版本
  `ignoreNestingFunctions` 默认 **false** ⇒ 作用域函数（`let`/`run`/`apply`/`also`/`forEach`）
  每个 +1。**唯一规避 = 把 helper 拆到「独立文件」**（detekt 逐文件分析）。
  实证法：把可疑函数体 stub 成 `return emptyList()` 再跑。
  ② detekt **只做静态检查、不做类型检查**，且 **CI 里 detekt 先于 compile** ⇒
  **编译错误被掩盖**（现象：CI 1 分钟就红）。**改完 detekt 必须真跑一次 compile。**
- **重构后必重跑 detekt**：编译/测试全绿也不会报新违例
- **注释里的「斜杠 + 星号」**：Kotlin 块注释**支持嵌套**，注释文案里出现「星号紧跟斜杠」会
  **提前结束注释**（报一堆 `Expecting a top level declaration`，位置全在同一行 —— 看到这个
  形态先去看注释）。写完注释 5 秒自检 `grep -n '\*/\|\/\*'`。
  **KDoc 里禁止字面 `/**`**（如 `/api/**`），块注释嵌套到 EOF 不闭合，KSP 报误导性连锁错
- **源码编码门禁** `.github/scripts/check-encoding.py`（CI push/PR）：
  判据必须是「**GBK 编码 → UTF-8 解码后全部落在 CJK 区**」；
  朴素的「encode gbk + decode utf-8 成功即乱码」会**大量误报**（「为」「状态」「值」「只」等）
- ⚠️ `git add -A` 会把工作区任何损坏一并提交（曾造成 139 处 U+FFFD 的注释损坏静默入库）；
  用 `git cat-file blob <rev>:<path>` 取**原始字节**比对（**不要**用 `git show > file`，会转码）
- **类型陷阱**：`retrieveProviderCreateCredentialRequest(intent).callingRequest` 是**客户端侧**
  `androidx.credentials.CreateCredentialRequest`，与**服务端侧**
  `androidx.credentials.provider.BeginCreateCredentialRequest` **互不相关**（无继承）；
  `BeginCreatePublicKeyCredentialRequest` **自带** `clientDataHash`
- **Hilt 断环**：`OkHttpClient ↔ Retrofit.Builder` 构造期环 → 注入 `Provider<TokenRefresher>`，
  401 回调时才 `get()`（**懒断环；eager 会死锁**）
- `@Inject` 构造只能含可绑定参数；测试注入口走 `internal` 次构造
- **单测是回归标尺**：本轮基线 `333 tests, 0 failures`（`:core:common` + `:data:kdbx` +
  `:data:repository` + `:app:testFullDebugUnitTest`）+ 全模块 detekt 全绿 +
  full/offline 两个 flavor 都编译通过
- **新增入口先画一遍「从冷启动到该入口」的可达路径**：KDBX 添加入口曾挂在
  `VaultListRoute` 后面，而该路由在「已有 ≥1 个库」时**不可达**（根导航落在解锁页 / 主界面）
  ⇒ 集成交付了却用不到（`ISSUES.md` #69）。功能挂在不可达路由后面，测试通过也没意义。
- **全局形状杠杆**：`MaterialTheme.shapes.extraSmall` 是 M3 `OutlinedTextField` 的默认圆角
  （基线只有 4dp）⇒ 改这一处 = 全 App 输入框一次性变圆，比逐个字段加 `shape` 稳妥
- **detekt 行数门禁会随改动"涨"到临界**：主 composable 贴着 `LongMethod ≤150` 时，
  加几行就红。拆法：把「与主流程无耦合的一段」抽成独立 composable / 私有函数
  （本轮抽了 `rememberItemsTopInset` / `SearchBackHandler` / `TotpPageProgress`）；
  文件里**唯一的类形声明**必须与文件名同名（`MatchingDeclarationName`）——
  枚举与 composable 同文件时会被判违规，需拆文件（`BadgeTone.kt`）

### 8.7 环境
- **本机（Windows / WorkBuddy）**：JDK 17 + Android SDK（`C:\AndroidSDK`）+ `gh` CLI **全部可用**
  ⇒ 可本地 gradle 构建、可直接 `git push`、可 `gh run view` 查 CI
- Git Bash 下 `./gradlew` 报「找不到主类 GradleWrapperMain」⇒ 用
  `java -classpath "D:/Vaultix/gradle/wrapper/gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain <task>`
  或直接 `~/.gradle/wrapper/dists/gradle-9.5.1-bin/*/gradle-9.5.1/bin/gradle`；
  daemon 配额耗尽先 `--stop`
- **本地验证链（≈ CI push 门禁）**：`detekt`（全模块）→ `:app:compileFullDebugKotlin` →
  `:app:assembleFullDebug` → 相关模块单测
- ⚠️ 沙箱**缺 NDK** ⇒ `assembleFullDebug` 的 native 符号剥离会失败（与代码无关，CI 正常）
  ⇒ 替代验证用 `:app:compileFullDebugKotlin`
- 沙箱内 GitHub 推送（**仅供沙箱参考**）：GitHub 被解析到 `198.18.0.x` ⇒ 用阿里 DoH
  （`https://223.5.5.5/resolve?name=github.com&type=A`）写 hosts + `~/.user_hosts`；
  SSH over 443（`HostName ssh.github.com` / `Port 443`）；HTTPS 推送不可行
- 沙箱无 Android SDK 时，可用 Gradle 自带的 `kotlin-compiler-embeddable-2.2.21.jar`
  真实编译纯 JVM 模块的 `.kt` 并跑断言 —— **验证真正会编译进 App 的那份代码**，
  胜过「内联逻辑副本」；Compose 文件可用 API 桩（`Modifier` 桩必须写成
  `interface Modifier { companion object : Modifier }`）
- **能在本地跑的实验就不要靠推理排除** —— 一轮脚本胜过三轮 CI 试错

---

## 9. 当前状态与下一批（第四十九轮接力起手式）

**第四十九轮（已落地，未提交）**：用户一次报 8 件事 —— P0 解锁逻辑 + 布局三症 +
滑动删除反馈 + KDBX 入口 + 填充下拉 + 条目页观感。根因见 `ISSUES.md` **#66~#69**。
门禁（本地真跑）：`:app:compileFullDebugKotlin` + 全模块 `detekt` +
**333 tests, 0 failures**。

**★ 第一优先：真机验收（本轮 8 条对应的验收点）**
1. **Edge/GitHub 凭据面板**：不再只剩「解锁 Vaultix」；已解锁时应**直接出现候选**；
   即便弹过一次解锁，**解锁完不应再弹**（无限解锁环已修）。若仍异常，
   抓 `VaultixAutofill` tag 的 `CP GET unlocked=N` 看现场口径。
2. **上下沉浸**：滚动时条目能滑到顶栏之下；底栏胶囊周围透出内容。
3. **筛选条**：展开后第一条条目不再被 chip 压住。
4. **搜索**：不再有黑横幅；返回手势只退出搜索（不再退回桌面）。
5. **长按滑删**：长按后能**看见**红底与位移，再左滑删除（密码 / 验证码 / 分组态都要）。
6. **设置 → 密码库**：出现「添加密码库」→ 能选「打开 KDBX 文件」。
7. **观感**：输入框变圆（全局 12dp）；详情页有图标头部；列表与详情出现「验证码 / 通行密钥」
   彩色徽标；验证码数字更大、≤5s 变红；填充下拉 56dp 圆角实底。
8. **回归**：Bitwarden 登录/同步/填充、KDBX 读写、主页锁按钮（查看锁）均不受影响。

**之后的新功能候选（按建议顺序）**：
1. **KDBX 阶段 B（写回）** —— 最大的未完成块；起点 `ISSUES.md` #59 / #64，铁律见 §8.3
2. KDBX 快速解锁（KEK 包裹，免每次输主密码）—— ⚠️ 先想清「换 keyfile 后怎么办」
3. KDBX 回收站映射（`ItemRepositoryImpl.observeTrash` 里分支）
4. `main-shell-migration.md` **阶段 3 剩余**：卡片样式细化 / FAB 行为 / 设置页分组复核
5. 设置页「本轮可补」三项：SectionCard 观感统一、条目分组模式入口、显示选项、列表密度
   （**不搬**需后端能力的假开关：密码建议/智能标题/屏蔽字段/通知时长/诊断导出/HIBP/附件历史/Send；
   **架构决策不搬**：私有本地库 / WebDAV / OneDrive / Bastion 自有分类）
6. 遗留：`item.new` 无类型选择器的历史缺口已补（af41c9e）；`provider.xml` 可否补
   `settingsActivity` 已做（`CredentialProviderSettingsActivity`）；privileged allowlist 待 M3

**未决分歧（勿单方面改）**：CP 是否恢复声明 `TYPE_PASSWORD_CREDENTIAL`
（Bitwarden 官方声明双能力；我方因「CP 密码分支会绕过 Autofill」而删）⇒ **待真机 A/B 复现后再定**。

---

## 10. 历史轮次索引（细节见 `.ai/SESSION-*.md` 与 `.ai/ISSUES.md`）

> 只留「一句话结论 + 提交号」，用于快速定位；**细节不要从本节推**。

### 2026-09-07 ~ 09-08（M0 / M1 建设）
| 轮次 | 结论 | commit |
|---|---|---|
| M0 骨架 | core:crypto；行覆盖 91.4% | — |
| Bitwarden 认证 | prelogin → MasterKey → masterPasswordHash → connect/token；盐 = `email.trim().lowercase()`；Kdf 0=PBKDF2 / 1=Argon2id（缺省 64/4）；refresh 用 `Mutex` 串行化 | — |
| 同步编排 | 推送 dirty → 预检 revision → 全量拉取 → 空库保护（**服务端 0 条但本地有数据必须阻止**，防不可逆清空）→ 落库；失败五分类 | — |
| P0 最小闭环 | 库列表 → 连接 → 解锁 → 条目列表 → 新建；`VaultEntity.id = 规范化服务器 URL`（同一服务器仅支持一个账号，已记录） | — |
| 打卡 1~5 | 详情/编辑/软删除 + `VaultixClipboard` + 自动锁定 + 2FA 登录 + 本地快速解锁 | — |
| 批 1 数据安全 | DTO 全载荷 + 合并更新 + type5 建模 + 类型守恒守卫 + 全量后 prune + flush 4xx 弃单 | `b0ad421` |
| 回收站视图 | 恢复 / 永久删除（本地先行 + 队列） | `1d0f299` |
| 登录失效修复 | 预挂 Bearer + 到期前 60s 预刷新 + 刷新失败三分（400/401 失效，其余可重试）+ 解锁路径 `registerServer` | `d689a37` |
| 同步触发收敛 | 移除进页/回前台自动拉取；自动同步 = flush；拉取 = 手动 | `824c432` |
| 对齐补齐 | `folder/favorite/reprompt/secureNote` 进领域模型（**DTO 有字段 ≠ 数据不丢**这条教训的出处） | `e6b05d6` / `af41c9e` |
| 身份全字段 | `VaultIdentity` 17 字段 + overlay 写回（**不改 overlay「可编辑」就是 UI 假象**） | — |
| linkedId | 官方是**分段编码**（登录 100 / 卡 300 / 身份 400），不是顺序编号 | — |
| 扫码 | **CameraX + ZXing**（不选 ML Kit：国内依赖 GMS 必踩坑） | — |

### 2026-09-09 ~ 09-10（M2-a 自动填充 / CP 集成）
| 轮次 | 结论 | commit |
|---|---|---|
| autofill 骨架 | `AssistStructureParser` + `BitwardenLikeAutofillMatcher` + `FillPlanner`；PSL 用完整 Mozilla 表 | `a06f037`/`f0a7cb4`/`ea78ee6`/`dffc490` |
| **CP 总根因** | manifest intent-filter action 误写 `android.credentials.CredentialProviderService`（**漏 `service.` 段**）⇒ 系统从未发现 Vaultix 是 Provider ⇒ 无启用项 / `credential_service` 恒空 / Edge 永不弹 | `f474654` |
| 认证回灌 | Activity 复用后台 task（`taskAffinity` 独立）+ `setResult` 改 `onResume` + 去 `NEW_TASK` + 去 `singleTask` | `dba5ae1`/`f474654`/`e52779e` |
| credentials 版本 | 1.3.0 → **1.6.0**（1.5.0 才引入「凭据选择二级 UI」，Chromium Android 14+ 依赖它） | — |
| 保存流程 | `onSaveRequest` + `AutofillSaveActivity` + `SaveInfo` | `a90e2d5` |
| 快捷入口 | 磁贴 / 手动填充 / 智能复制（**都不依赖无障碍**） | `00f4235` |
| 设置页状态卡 | 三态（未启用/需注意/正常）；`AutofillStatusChecker` 两步判定；**读不到按「已启用」** | `be8a5bf` |
| 只发 full | offline flavor 暂停参与构建 | `b64ccb6` |

### 2026-09-11（锁态模型重写 / CI 转绿）
| 轮次 | 结论 | commit |
|---|---|---|
| 锁态重写 | 按 Bitwarden 换 `VaultTimeout` sealed class + 定时器模型；**删掉自造的 `CredentialFlowGuard`**（`Long.MIN_VALUE` 溢出 ⇒ 自动锁定被永久抑制） | 第 32 轮 |
| CI 转绿 | detekt 门禁 + 修 3 处 `Int?` 编译错误（**被 detekt 掩盖**） | `24af692` |
| 搜索框 | 按 Bitwarden 重写（不进 LargeTopAppBar） | 第 33 轮 |

### 2026-09-12（通行密钥收官 / 观感批次 / KDBX）
| 轮次 | 结论 | commit |
|---|---|---|
| 启动死锁 | `remember { resolveStartDestination }` 固化首帧错误结论 + loading 无出口 ⇒ 永久转圈；补 `Splash`/`Onboarding` 态 | `22d7273` |
| 主界面骨架 | Bastion 式 Tab 容器 + `ActiveVaultStore.resolve()`；**多库是筛选维度而非导航层** | `7a99635` |
| passkey 二次 Base64 | `rawId`/`userHandle` 被二次解码（b64url ↔ 标准 Base64 失配） | `1d2d242` |
| clientDataJSON | **回退占位符**（推翻第三十轮，以 W3C 规范为准） | `aa5fa27` |
| rawId UUID 分支 | UUID 文本被误判成 base64 ⇒ 补 16 字节分支；**真机重登 GitHub 通过，正式结案** | `8fd64f5` |
| Fill Assist | 完整搬运（服务端规则 + 6h 缓存 + 独立开关） | `9fca5ab` |
| 误弹检测 | 完全对齐 Bitwarden（分类结果即证据） | `59b57e8` |
| Edge 账号框 P0 | 浏览器整棵 DOM 可填 + `node.text` 污染语义 ⇒ 节点准入闸 + 信号改 `formSignalOf` | `4cc4dc1` |
| 观感批次 | 卡片外框统一 / 分组 / 按住滑动删除 / 沉浸式顶栏 / 统一进度条 / 设置页对齐 | `310bb25`/`cb6cbad` |
| KDBX 引擎 | kotpass 读路径 + 映射 + 往返测试 10/10 | `879e7c1` |
| 锁/解锁 1b~4 | 查看层锁 / 退出数据库 / 解锁即回填 | `5682bc6` |
| 第 2/3/4 批 | 顶栏胶囊化 + 站点图标 + KDBX 集成 + codec | `e88bda2`/`93c814c`/`fcaf918` |

### 2026-09-13（第四十九轮 · 用户一次报 8 件事）
| 轮次 | 结论 | commit |
|---|---|---|
| P0 解锁判据 | 「**存在**锁定的库」≠「**当前**库锁定」⇒ 候选全灭 + **无限解锁环** | 待提交 |
| 布局三症 | 让位写在外层容器 padding ⇒ 筛选条压首条 / 上下不沉浸 / 搜索黑横幅 + 返回退桌面 | 待提交 |
| 滑删反馈 | 手势**早就接了**、只是没有可见反馈 ⇒ 用户以为没做 | 待提交 |
| KDBX 入口 | 添加库入口挂在**不可达路由**后面 ⇒ 集成交付了却用不到 | 待提交 |
| 观感 | 全局输入框圆角 / 类型彩色徽标 / 详情图标头部 / 验证码字体 / 填充下拉实底 | 待提交 |

> **踩坑全表**（现象 → 根因 → 解法，编号 #1~#69）：`.ai/ISSUES.md`。
> ⚠️ 其中 **#35 ↔ #43 ↔ #39 是一条「推翻链」**（passkey clientDataJSON 的错判与更正），
> 接力时必须先看懂，不要只看旧条目就动手。
