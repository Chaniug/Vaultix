# 问题与坑整理（AI 接力必读）

> 每个坑都写清：现象 → 根因 → 解法。避免重复踩。

## 1. Git Bash 与原生 git 的路径映射错位（2026-09-07）

- **现象**：Bash 中 `git clone /d/Vaultix` 报"目录非空"（实际为空）；`git clone /tmp/xxx` 退出码 0 但目录不存在
- **根因**：MSYS 与原生 Windows git 对 `/d/`、`/tmp` 解析不一致，`/tmp/xxx` 被落到 `D:\tmp\xxx`
- **解法**：涉及 git/Gradle 的目录操作一律用 **PowerShell + 原生 `盘符:\路径`**

## 2. AGP 9 迁移五连坑（2026-09-07）

| # | 坑 | 解法 |
|---|---|---|
| 1 | 不能 apply `kotlin-android` | AGP 9 内置 Kotlin，apply 会报 `Cannot add extension with name 'kotlin'` |
| 2 | `kotlinOptions` 废弃 | 改用顶层 `kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_17 } }`（枚举，非 `JavaVersion`） |
| 3 | catalog 别名不能叫 `kotlin-jvm` | 与内置 `kotlin {}` DSL 撞名；改用 `kgp`，仅根工程 `apply false` |
| 4 | 类型安全项目访问器未启用 | `settings.gradle.kts` 加 `enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")` |
| 5 | Hilt 与 AGP/Kotlin 双绑定 | AGP 9 要求 ≥2.59；Kotlin 2.4.x 要求 **2.60.1**（metadata 版本不匹配会报 `Provided Metadata instance has version 2.4.0`） |

⚠️ Bastion 注释称"升 Kotlin 2.4 被 KSP 阻塞"**已过时**，真正在卡的是 Hilt。

## 3. Argon2 黄金向量错误（2026-09-07）

- **现象**：`KdfTest` 两个 Argon2 参考向量测试失败
- **根因**：**测试向量是臆造的，实现是对的**
- **解法**：用 Python `argon2-cffi` 独立校准。校准方法：
  `hash_secret_raw(secret=pwd, salt=sha256(email), time_cost=t, memory_cost=m*1024, parallelism=p, hash_len=32, type=ID)`
- **教训**：密码学黄金值必须用权威实现生成，不能手写

## 4. 误用 `New-Item -Force` 覆盖已有文件（2026-09-07）

- **现象**：3 个已存在的测试文件被清空为 0 字节
- **教训**：创建文件前**必须先确认是否已存在**；切勿对可能已存在的文件用 `-Force`

## 5. 本机 SDK 没有 android-36（2026-09-07）

- `C:\AndroidSDK` 只有 35 / 37 / 37.0，故 `compileSdk` 必须 ≥ 37
- `D:\AndroidSDK` 是空壳，不可用

## 6. PowerShell 生成含 `$` 的代码时的转义陷阱（2026-09-07）

- **现象**：用 here-string 生成 Kotlin 代码时，`"bw_access::$server"` 被写成
  `"bw_access::server"`（`$` 被插值吞掉）——**所有库的凭据共用同一个 key，
  多库场景下 token 会互相覆盖**。这是真实上线级 Bug。
- **试过的错误转法**：`"`${'$'}server"` 产出 `$` 消失；单引号 here-string 里 `` `$ `` 保留反引号
- **解法**：代码里改用**常量前缀 + 字符串拼接**（`PREFIX + server`），完全避开 `$`
- **教训**：①生成脚本写完必须**校验输出文件内容**，不能只看脚本本身；
  ②需要插值变量时优先拼接而非模板；③写含 `$` 的代码用单引号 here-string `@'...'@`

## 7. 覆盖安装前提（记录，避免日后误改）

debug(preview) 与 release 可互相覆盖安装的**硬性前提**：
- 两者 applicationId 相同（debug **不加** applicationIdSuffix）
- 两者签名相同（已统一走 SIGNING_* Secret，jks 在 `D:\vaultix-release.jks`，密码用户自留）

## 8. 「登录易失效」三根因（2026-09-08，真机驱动，d689a37 修复）

- **现象**：app 高频提示「登录已失效，请重新登录」；重启后快速解锁尤其必现
- **根因（3 条叠加）**：
  1. 网络层从不预挂 `Authorization`——每个请求先 401 → OkHttp Authenticator 刷新 → 重试；每次调用都轮换 token，任何一次刷新失败即误报失效；
  2. `host→server` 映射只在**登录**时登记且仅存内存——进程重启后走快速解锁（不重登）则映射为空，刷新必失败（即使 access token 仍有效）；
  3. 刷新失败不分类——403（CF/WAF）/429/5xx/网络抖动全被当凭据失效（Bastion 明示过此误伤）
- **解法**：① 请求拦截器按 host 预挂 Bearer，expiresIn 落盘、到期前 60s 预刷新；② 解锁路径（含本地快速解锁）`registerServer`；③ 刷新结果三分：400/401=Invalid（重登）/ 其余=Transient（保留登录态，sync 401 按 `refreshFailureOf(server)` 归类）
- **防回退**：任何「请求不带 Bearer 直接发」或「把非 401 失败当登出」的改动都是回归

## 9. KDoc 里写字面 `/**` → 注释嵌套直到 EOF（2026-09-08，排查最久的一个）

- **现象**：KSP 报一连串误导性错误——`ModuleProcessingStep ... 'BitwardenAuthInterceptor' could not be resolved`，且对**所有** @Provides 方法重复（连无参方法也是）；clean/重启 daemon/清缓存均无效
- **根因**：新文件 KDoc 写了「仅 `/api/**` 数据端点预挂」——Kotlin 块注释**支持嵌套**，`/**` 即 `/*` 开启一层嵌套注释，文件里再无第二个 `*/` 闭合 → 注释一直未闭合到 EOF → 整个文件解析失败 → 所有引用它的符号不可解析
- **解法**：注释文案避开 `/*` 序列（如改述「路径以 /api/ 开头」）
- **教训**：KSP 报「类无法解析」先怀疑**被引用文件本身有没有解析错误**（尤其是注释/编码），不要只在引用侧找原因

## 10. KSP2 对 @Provides 签名里的具体新类解析失败（2026-09-08）

- **现象**：`@Provides fun x(...): MyNewClass`（同模块新类）→ KSP「could not be resolved」；改为返回接口类型（`okhttp3.Interceptor`）后在函数体内构造具体类 → 通过
- **解法**：@Provides 签名一律用接口/已有类型；具体类只出现在函数体
- **备注**：疑似 KSP2 增量/新文件符号注册缺陷；2.0 稳定后复测可移除该限制

## 11. mockk：普通函数用 `every`，suspend 用 `coEvery`（2026-09-08）

- **现象**：`coEvery { repo.logout(any()) }` 配普通（非挂起）函数 → 运行时报 `no answer found for ... logout(...)`，编译不报错
- **解法**：非 suspend 函数 stub 用 `io.mockk.every`；仅 suspend 用 `coEvery`
- **关联**：DAO 是 suspend（Room）用 coEvery；领域普通方法（lockVault/logout）用 every

## 12. 测试 fixture 用占位字符串掩盖真实路径（2026-09-08）

- **现象**：引入「更新=合并上传」后 `updateItem_preservesIdAndRevision` 失败——`IllegalStateException: 本地密文损坏`
- **根因**：既有 fixture 的 `encryptedPayload = "stale"`（非 JSON）——以前 update 不读 payload，现在要解码合并
- **解法**：fixture 重建为真实链路产物（`mapper.toRequest(...).toStoredCipherDto(...)` 再 encode）
- **教训**：测试数据要贴近真实格式；存储层语义变更后旧占位 fixture 是最先暴露的假阳性

## 13. Dagger 依赖环新形态：拦截器引入认证仓库（2026-09-08）

- **现象**：给 OkHttp 加「预挂 Bearer 拦截器」后 Hilt 报
  `Retrofit.Builder → BitwardenApiFactory → ... → OkHttpClient` 构造期环
- **根因**：拦截器构造即拉 `AuthRepository`，而 AuthRepository → ApiFactory → Builder → OkHttpClient
- **解法**：拦截器注入 `Provider<AccessTokenProvider>` 请求时再 `get()`（与 BitwardenAuthenticator 同款断环；**eager 会死锁**）
- **判据**：网络栈出现 client→auth→client 自环一律 Provider 懒断环

## 14. Gradle daemon 大任务批崩（2026-09-08）

- **现象**：一次跑 6 模块单测+detekt，daemon 直接消失（heap 2GiB 不够）「daemon disappeared unexpectedly」
- **解法**：拆批执行（编译一批 / 单测一批 / detekt 单独）；单测失败先从 XML 报告定位再重跑

## 15. 「永不锁定」档位被新规则静默覆盖（2026-09-09）

- **现象**：自动锁定设为「从不 / 永久开启」后，息屏再亮屏仍然被锁
- **根因**：① 策略函数 `neverAutoLock` 写了但控制器没调用，`onStart` 无条件执行
  「keyguard 仍锁即锁」；② 档位缓存在 `@Volatile` 字段（初值默认 5 分钟），
  冷启动首帧 DataStore 首值还没到达 → 按默认档误判
- **解法**：`AutoLockPolicy.shouldLockOnResume(minutes, screenLocked, timedOut)` 让 never
  短路；控制器每次判定现取 `prefs.autoLockMinutes.first()`，删掉缓存字段
- **判据**：① 用户显式档位必须在**策略层**最高优先级并写单测；② 生命周期观察者里
  不要缓存偏好字段（DataStore / Flow 首值异步到达，首帧会用到默认值）

## 16. 浏览器自动填充「静默失效」的三处缺口（2026-09-09）

- **现象**：Chrome 能填、Edge（`com.microsoft.emmx`）填不了（或只有密码能填）
- **根因**：① Edge / 三星 / Opera 等 WebView **不总上报** `ViewNode.webDomain`；
  ② Chromium 下发的是 `webUsername` / `webPassword` hint，映射表只有 `username` / `password`；
  ③ 浏览器字段语义常常只存在于 `htmlInfo` 属性与资源 id 里
- **解法**：地址栏 `URL_BARS` 兜底（**包名 + idEntry 双重匹配**，只判 idEntry 会误命中
  同名资源）+ 结构文本 BFS 扫描；hint 补别名并遍历**全部** hint；文本信号加
  `idEntry` + `htmlInfo`；只有密码框时把上方最近文本框升格为用户名
- **坑**：地址栏节点必须**排除在可填充字段之外**——其文本常含 "login"，会被文本启发式
  判成用户名字段，填充时把账号写进地址栏

## 17. Detekt 门禁常见三连（2026-09-09）

- `MatchingDeclarationName`：文件里只有**一个**顶层类/对象声明时，文件名须与其同名
  → 把 data class 拆到独立文件（如 `AppInfo.kt` 与 `AppPickerDialog.kt` 分离）
- 给表单 / 页面新增 UI 后 `LongMethod` / `CyclomaticComplexMethod` 超阈值 → 把
  `if (showX) { Dialog { ... } }` 抽成 `XHost(...)` 私有 composable（比事后重构便宜得多）
- `MagicNumber`：`fillMaxWidth(0.95f)` 这类比例值也要常量化（Compose 的 `.dp` 数字不受影响）
- `Unresolved reference 'Bolt'`：`Icons.Filled.*` 并非全量 Material 图标都可用，
  换用项目里已验证存在的图标（如 `ContentCopy`）——编译前先确认图标存在

## 19. 保存流程「永远不触发」：FillResponse 没挂 SaveInfo（2026-09-09）

- **现象**：`onSaveRequest` 写了却从不被调用；登录新网站后不提示保存
- **根因**：框架**只在 FillResponse 里带了 `SaveInfo`** 时才在用户提交表单后回调保存；
  只实现 `onSaveRequest` 等于没做
- **解法**：`AutofillSaveInfo.build(parsed)` 挂到**每个** FillResponse 上——
  包括「无匹配项」的 fallback 分支（**没匹配恰恰是最需要保存的场景**：用户首次登录该站点）
- **判据**：Android 自动填充保存链路三段缺一不可——
  ① FillResponse 挂 SaveInfo → ② 框架回调 onSaveRequest → ③ 自己拉起确认界面落库

## 23. 「点填充却跳到 Vaultix 主页」：中转 Activity 先渲染了 UI（2026-09-09）

- **现象**：浏览器里能看到条目，点选后跳到 Vaultix 库列表页，密码没填进去
- **根因**：为「填充后自动复制 TOTP」挂了 dataset 认证，回调 Activity 的 `onCreate`
  **先 `setContent` 渲染提示卡片、再回填 Dataset**——用户正好点中卡片上的
  「打开 Vaultix 解锁」按钮
- **解法**：命中 `MODE_COPY_TOTP` 时**先判断、直接回填 + finish，绝不 setContent**
- **判据**：自动填充的「中转」Activity 必须无感（透明、无布局、无按钮）；
  任何需要经过 Activity 的填充副作用，都要先分流再决定是否渲染 UI

## 20. Quick Settings 磁贴「加了不显示 / 空白」（2026-09-09）

- **现象**：声明了 TileService，但快捷设置里找不到，或加进去是空白方块
- **根因**：① 只实现 `onClick`，没有 `onStartListening`（系统拿不到 label/icon/state，
  国产 ROM 尤其明显）；② 没调 `requestListeningState`，系统不保证回调 onClick
- **另注**：磁贴**必须由用户手动添加**，系统没有给应用「开关磁贴」的 API——
  设置页只能做「引导添加」（API 33+ `StatusBarManager.requestAddTileService`），不是开关
- **解法**：`onTileAdded` / `onStartListening` 里写 `tile.label/icon/state` + `updateTile()`，
  并 `requestListeningState(this, ComponentName(this, X::class.java))`

## 21. Android 11+ 查不全已安装应用（「关联应用」列表很短）（2026-09-09）

- **现象**：`queryIntentActivities(ACTION_MAIN + CATEGORY_LAUNCHER)` 只返回很少几个 App
- **根因**：**包可见性限制**（API 30+）——不在「自动可见」白名单的查询需要 `<queries>` 声明
- **解法**：Manifest 加 `<queries><intent><action MAIN/><category LAUNCHER/></intent></queries>`，
  **不要**申请 `QUERY_ALL_PACKAGES`（应用商店审核风险）

## 22. Mapper 读写字段不对称 → 静默数据破坏（2026-09-09，通行密钥 P0）

- **现象**：编辑任一条登录条目后，服务端该条通行密钥的密钥材料被清空、计数器归零
- **根因**：`mapFido2` 只读 8 个字段，`mapFido2Request` 却写 13 个 → 没读到的字段用默认值
  覆盖上传（`encryptOpt("")` 返回 null = 服务端字段被置空）
- **解法**：读侧补齐全部字段（照 Bastion `Fido2CredentialCodec` 13 字段）；
  对「写明文、读密文」的 `creationDate` 用 `decryptOrPlain` 兼容
- **判据**：**Mapper 读 N 字段就必须能写回 N 字段**，且要有「往返后逐字段相等」的回归测试；
  读不到就写默认值的写法一定会造成不可逆破坏

## 18. Kotlin 默认参数不能调用 suspend 函数（2026-09-09）

- **现象**：`suspend fun f(clearMs: Long = prefs.preference.first())` 编译失败
  （默认参数表达式不是挂起上下文）
- **解法**：默认参数只能是非挂起表达式 → 在函数体内读取偏好，或由调用方显式传值

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

## 25. 认证 Activity onCreate 同步 setResult+finish → 认证结果丢失（2026-09-10）

- **现象**：老 autofill dataset 认证（填充后复制验证码 / 二次验证）：验证码复制成功
  （Activity 副作用照跑）但**密码没填进**（返回的 dataset 被系统丢弃）
- **根因**：认证 Activity 在 onCreate 里同步投递结果并 finish，早于系统完成
  「认证会话接管」；且此前宿主 launchMode=singleTask + intent 带 NEW_TASK
  （实例复用走 onNewIntent、跨 task 均丢 result）
- **解法**：投递推迟到 **onResume**（一次性 guard）；launchMode 改 standard、
  认证意图不带 NEW_TASK（引导型解锁/搜索意图才在调用点补 NEW_TASK）
- **判据**：认证 Activity 必须走完生命周期再返回结果（对齐 Bitwarden/Bastion 认证宿主）

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

## 31. 设置页与 Bastion 的信息架构差距（本轮修复，2026-09-10，be8a5bf）

对 Bastion `AutofillSettingsV2Screen`(1149 行) / `PasskeySettingsScreen`(666 行) /
`SettingsScreen`(1925 行) 逐项比对后补齐：

- **没有状态卡**：用户分不清「没启用」和「启用了但填不出来」→ 新增三态卡
  （未启用 errorContainer / 需注意 tertiaryContainer / 正常 primaryContainer）。
  「需注意」= 密码能填但通行密钥没开（Chromium 半残状态，最常见）。
- **`AutofillStatusChecker` 的两步判定**：`AutofillManager.hasEnabledAutofillServices()`
  只回答「系统有没有启用任何服务」，选的是谁分不出来 → 必须再读
  `Settings.Secure:autofill_service` 按自身类名子串匹配。
  **读不到值（部分 ROM 对第三方 App 隐藏）时按「已启用」处理**——第一步已确认有服务，
  把「读不到」报成「未启用」会误导用户反复去系统设置确认。
- **没有通行密钥入口**：设置页看不到「我到底存了几个通行密钥」，排查 Edge 通行密钥
  问题全靠猜 → 新增分组显示「已解锁库中：N 个」。显示 0 即定位到同步/解析问题。
  口径必须写清「已解锁库中」：锁定库密文读不出来，硬统计得 0 会误导成「没存过」。
- **填充行为无开关**：`MatchConfig` 的两个域匹配参数此前是硬编码默认值 → 提升为用户
  开关（严格匹配 / 允许子域名匹配），在 `VaultixAutofillService.buildResponse` 生效。
  **必须是真开关**：装饰性开关比没有开关更糟。
- **`credential_provider.xml` 的 `settingsSubtitle` 误用标题文案**
  （`@string/setting_credential_provider` = 「凭据提供商（通行密钥）」），
  新增 `setting_credential_provider_subtitle` 专用副标题。

### 明确**不**对齐 Bastion 的三项（有意为之）

1. Bastion CP 文案写「通行密钥**和密码**设置」——与其 `credential_provider_config.xml`
   里「CP 不处理密码」的注释自相矛盾。Vaultix 保持「凭据提供商（通行密钥）」。
2. Bastion 用 `Intent("android.settings.CREDENTIAL_PROVIDER_SETTINGS")` 跳系统设置，
   Vaultix 用 `CredentialManager.createSettingsPendingIntent()`（能直达自家 provider
   的启用开关），不下沉为通用 `ACTION_SETTINGS`。
3. 不搬 Bastion 的「黑名单 / 屏蔽字段 / 智能标题 / 通知时长 / 密码建议 / 影子校验 /
   校验诊断」——Vaultix 无对应能力，搬过来只会是点不动的假开关。
   同理不搬 `PasskeySettingsScreen` 的影子校验/严格校验开关。

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
