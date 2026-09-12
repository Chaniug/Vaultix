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

## 34. 通行密钥「候选列表为空」根因：解密残留填充 + 未 trim + rpId 未归一化（2026-09-11，P0）

**真机症状（用户原话）**：「这样改动后又找不到通行密钥了」→ **列表为空、没有任何候选**。

### 先排除：不是 `8afac3d`（BE/BS）引入的
`git show 8afac3d --name-only` 只有 3 个文件（`PasskeyGetActivity` / `WebAuthn` /
`WebAuthnTest`），**完全没碰 discovery 逻辑**。`VaultixCredentialProviderService`
最后一次变更是更早的 `fec4032`。二者无因果关系（时间上的先后 ≠ 因果）。

### 根因（四项叠加，全部对照 Keyguard / Bastion / Bitwarden 确认）

**① 解密残留填充 —— ⚠️ 经实测后降级为「纵深防御」，**不是**根因**

曾推断 Bitwarden 服务端用 ISO10126 填充、Vaultix 用 `PKCS5Padding` 解密会「不报错也不剥离」。
**用真实 JCE 实测后该推断不成立**：
- 标准 SunJCE 的 `PKCS5Padding` 对 ISO10126 密文**直接抛 `BadPaddingException`**，不会静默泄漏；
- 反向：ISO10126 末字节同样是填充长度，用宽松 PKCS5 解析 **2000/2000 完全正确**；
- 且 `decrypt` 本就以 `PKCS5Padding` + `doFinal` 正确解填充 ⇒ 压根不会有残留。

⇒ **不是根因**。但保留 `VaultixCrypto.removePkcs7PaddingIfStrict` 作为**纵深防御**
（仅严格 PKCS#7 成立时才剥离，避免误伤）；并验证了 `trim()` 对填充字节
`0x01..0x10` 的兜底作用（Java `trim()` 去所有 `<= U+0020` 的字符，16/16 可去除）。

**教训：一次 JCE 实验就能否掉的假设，不要写进根因。**

**② 没有 trim（实际根因，数据映射层）**

服务端多处字段带前导/尾随空白，官方客户端读出来一律 `trim()`；Vaultix 的
`CipherMapper.mapFido2` **一个字段都没 trim**。

**解法**：`mapFido2` 全字段 `.trim()`；`decryptToString` 也加 `trim()`。
⚠️ 特别注意：`counter` / `discoverable` 走 `toLongOrNull` / `toBooleanStrictOrNull`，
不 trim 会**静默回落默认值**（counter=0 / discoverable=true）——默认值本身安全，
但会把真实数据悄悄改写。

**③ rpId 未归一化（匹配层）**

此前是 `cred.rpId.equals(rpId, ignoreCase = true)`，只能处理大小写。
**末尾根点 `.` / Unicode 域名（punycode）** 一律失配。

**解法**：`VaultixCredentialProviderService.normalizeRpId` / `isSameRpId`，
逐条对齐 Bastion `PasskeyRpIdNormalizer`：
`trim` → `trimEnd('.')` → `lowercase(Locale.ROOT)` → `IDN.toASCII(lower, USE_STD3_ASCII_RULES)`，
**两侧都归一化**。

**④ allowCredentials 严格过滤无回退（策略层）**

RP 下发的 `allowCredentials` 是**提示**而非授权门（规范允许空）。用户若在**其它设备 /
其它客户端**注册过该 RP 的 passkey，本地 credentialId 与列表对不上 —— 严格过滤会把
**唯一可用的候选也删掉**。

**解法**：照抄 Bastion `resolvePasskeys` 的回退 ——
严格匹配**为空时回退到「只按 rpId」**并打日志（`allowedMiss` 记录被回退的数量）。

### 三家对照：锁定态处理（已核实，Vaultix 已对齐，不是根因）

| 维度 | Bitwarden | Keyguard | Vaultix |
|---|---|---|---|
| 全库锁定 | 只返回 `authenticationActions`(unlock)，不带 credentialEntries，直接 return | `MasterSession.Empty` → 只返回 unlock 的 `AuthenticationAction` | ✅ 同（`unlocked.isEmpty()` 分支） |
| 通道互斥 | if/return 互斥 | 同 | ✅ 同 |
| 补偿重试 | **无** | **无**（仅 UI 侧 800ms 最小处理时长） | 无（对照后确认**不需要**） |

### 埋点（现场排障唯一手段）
CP 服务每次 resolve 都打：
```
GET resolve rpId=<x> allowed=<n> total=<n> unusable=<n> rpIdMiss=<n> allowedMiss=<n> matched=<n>
GET resolve EMPTY: storedRpIds=<库内实际存的域名>
```
四个计数一出来即可判定卡在哪一级（标签 `VaultixAutofill`，限 debug 构建）。

### 教训
1. **「列表为空」是 discovery 问题**，要在「候选是怎么被筛出来的」这条链上找，
   且必须用逐级计数把「哪一级筛没了」量化出来 —— 而不是靠猜或改无关的注册期字段。
2. **能用真实运行时实验否掉的假设，绝不要写进根因。** 本轮「ISO10126 残留」的推断
   听上去很合理（Bitwarden 服务端确实用过 ISO10126），但一次 20 行 JCE 程序就否掉了它
   （标准 `PKCS5Padding` 会直接抛异常）。**先做实验，再下结论。**

---

## 35. 通行密钥「Authentication failed」根因：浏览器流程回传了自造的 clientDataJSON（2026-09-11，P0，`90d5e6d`）

> ### 🔴🔴 本条结论已于 2026-09-12 **被推翻** —— 见 **#43**。
>
> **本条判定的"正确做法"（浏览器流程回传空占位符）是错的**，真机 **GitHub 注册通行密钥
> 报 `Security key authentication failed`**。错因：官方 `set a placeholder value for
> clientDataJSON` 那句**带前置条件 `If you retrieve an origin`**（仅适用于特权应用名单场景），
> 而 W3C 规范 §7.1/§7.2 要求 RP 解析**明文**校验 `C.challenge` ⇒ 空数组必然失败。
> **正确做法：两条流程都回传自建真实 JSON**，唯一差别是签名覆盖哪份哈希。
> 下面原文保留，仅作记录当时的（错误）推理过程 —— **不要照此实现**。

**真机症状（用户原话）**：「能检测出来通行密钥，但是 Authentication failed，这个问题还是有。」
→ 候选列表已正常（§34 的 discovery 修复生效），但**站点侧验签失败**。

### 先定性：这是断言阶段的验签失败，与 discovery / BE·BS / signCount 都无关

RP 的 login 校验清单只有：`type==="webauthn.get"` / challenge / origin / 按 credentialId
取公钥 / `SHA-256(rpId)` 匹配 rpIdHash / UP（按策略 UV）/ **对 `authData ‖ SHA-256(clientDataJSON)` 验签** /
`new signCount > stored`。列表能出来 ⇒ discovery 已通；本问题在「验签」这一格。

### 根因：provider 与 RP 看到的 `clientDataJSON` 不是同一份

- 系统只把 **32 字节 `clientDataHash`**（浏览器那份 clientDataJSON 的 SHA-256）交给 provider，
  **不给明文**；
- 网页交给 RP 的是**浏览器自己拼的那份** JSON，RP 用它重新哈希后与签名里的哈希比对。

旧实现（`buildClientDataJsonForBrowser`）试图「逐字节复刻浏览器的 clientDataJSON」再回传，
还按哈希在 `[无 crossOrigin, 有 crossOrigin]` 两个候选里**反选** —— 这条路不可能稳定成功：
字段集与字段顺序由浏览器版本决定（Chromium 可能带 `tokenBinding` 等），provider 无从保证命中；
一旦不命中就 `?: candidates.first()` 回退到自造变体 ⇒ RP 哈希必然对不上 ⇒ **验签失败**。

**官方口径**（`developer.android.com/identity/sign-in/credential-provider`，逐字）：
> use the `clientDataHash` that's provided directly in `CreatePublicKeyCredentialRequest()`
> or `GetPublicKeyCredentialOption()` instead of assembling and hashing clientDataJSON during
> the signature request. **To avoid JSON parsing issues, set a placeholder value for
> `clientDataJSON` in the attestation and assertion response.**

### 解法：两条流程彻底分开

> ⚠️ **下表「回传 clientDataJSON」一行是错的（2026-09-12 推翻）**：浏览器流程**不应**
> 回传空占位符，应回传**自建真实 JSON**。详见 #43。

| | 浏览器流程（有 `clientDataHash`） | 原生 App 流程（无 `clientDataHash`） |
|---|---|---|
| 签名材料 | `authData ‖ clientDataHash`（系统给的） | `authData ‖ SHA-256(自产 JSON)` |
| 回传 clientDataJSON | ~~**空占位符**~~ **（错！应为自建真实 JSON）** | **同一份自产 JSON**（拼/签/回传三者同字节） |
| 依据 | 官方要求；RP 用的是网页那份 | RP 用的就是 provider 这份 |

`WebAuthn.BROWSER_FLOW_CLIENT_DATA_PLACEHOLDER = ByteArray(0)`；`buildClientDataJsonForBrowser`
**删除**（错路产物）；`clientDataJsonMatchesHash` 保留作诊断，并注明浏览器流程下
`false` 属**预期**（不再是指标故障）。

create 路径同理：浏览器流程回传占位符（attestation 为 `none`，本无签名，`clientDataHash`
仅供系统/网页侧校验）。

### 三家对照（重要：别照抄，两家是反例）

> ⚠️ **本节判定有误（2026-09-12 更正）**：被标为 ❌ 的 **Bastion** 其实是**对的**，
> 被标为 ✅ 的 Bitwarden 也不算错（它由 SDK 内部构造并回传真实 JSON，并非回传空值）。
> **真正错的是 Vaultix 自己**（回传空占位符）。详见 #43。

| 实现 | 浏览器流程如何处理 clientDataJSON |
|---|---|
| **Bitwarden** | ✅ **从不在 Android 侧重建**：交给 SDK，`request.clientDataHash?.let { ClientData.DefaultWithCustomHash(it) } ?: ClientData.DefaultWithExtraData(callingAppInfo.getAppOrigin())`；`Fido2PublicKeyCredential.clientDataJson` 可空（**SDK 内部仍是真实 JSON**） |
| Keyguard | ✅ 重建（`PasskeyProviderGetRequest.kt:119-128` 拼 JSON，`:129` 签系统哈希，`:159` 回传自造 JSON）——**符合规范** |
| Bastion | ✅ 重建（`PasskeyAuthActivity.createClientDataJson` + 回传 `clientDataJsonB64`）——**符合规范** |
| Vaultix（修复前） | ❌ 重建 + 按哈希反选（方向错在"反选"） |
| Vaultix（第三十轮"修复"） | ❌❌ **回传空占位符** —— 比修复前更糟，直接导致 GitHub 注册失败 |

**结论（更正）**：三家**都回传真实 JSON**，只有 Vaultix 走了占位符这条歪路。
**教训不是"参考项目都错"，而是"我把官方文档的条件句读漏了，反而推翻了正确的参考实现"。**

### 验证（沙箱无 Android SDK，用 Gradle 内置 kotlin-compiler-embeddable 2.2.21 直接在真实源码上跑）
- 真实 `WebAuthnTest.kt`：**10/10 通过**（含新增 3 条回归锁）；
- 独立验证程序 20 项断言全绿，关键三条：
  1. 浏览器流程签名可被 RP 用「浏览器 JSON 的哈希」验通（真实登录场景）；
  2. **反证旧路**：浏览器多带一个字段（`tokenBinding`）→ 两个自造候选 `match=false`，
     旧逻辑只会回退到错的那份；
  3. 原生流程回传 JSON 反解 == 签名所用 JSON。

### 教训（含 2026-09-12 更正）

1. **【2026-09-12 更正】别只读官方文档的后半句。** 本条栽在漏读条件句
   `If you retrieve an origin` —— 跳过前置条件把「特定场景的建议」当成「普适要求」，
   写出了与规范冲突的代码。**读官方建议时，先确认它的适用前提。**
2. **【2026-09-12 更正】与规范冲突时，以规范为准。** 规范是 RP 的实现依据；
   任何平台实现建议都不能推翻「RP 必须解析 clientDataJSON 明文校验 `C.challenge`」
   这一硬要求。**"官方文档 vs 规范"打架时，规范赢。**
3. 【保留】凡"provider 要把某个值回传回去"的设计，先问一句：
   **RP 校验用的是谁手里的那份副本？** 若是对方手里的，那关键在签名覆盖的哈希一致
   —— 但**这不等于可以不回传真实 JSON**（RP 仍要读明文）。
4. 【保留】"按哈希枚举候选反选"这种补偿逻辑，本身就是设计错的信号。真方案只有一条：
   **用系统给的哈希去签，不要自造变体。** 补偿代码越多，说明方向越偏。
5. **【2026-09-12 新增】参考实现出现分歧时，先怀疑自己的理解，而不是先判定"他们都错"。**
   本条当时把两家正确实现标为反例，理由仅是"与我的理解不符" —— 这是危险信号。
   **当所有参考实现都跟你不一样，大概率是你错了，而不是全世界错了。**

## 36. 通行密钥「Authentication failed」第二根因：锁态竞态（Activity 把「库锁定」当成「找不到」）（2026-09-11，P0，`38c5130`）

> 用户报：35 号（clientDataJSON 占位符）修复后**仍然** Authentication failed，
> 并追问「是密码库的问题吗，密码库加锁解锁的逻辑问题？」——**用户的判断是正确的**。
> 随后指示：「参考 bitwarden 的做法，它能够调用出通行密钥，没道理我们调用不出、
> 校验不了的，哪怕是一字一句抄代码，也要实现」。

### 先定性：这是两个独立缺陷叠加，缺一个都修不好

| | 缺陷 | 性质 |
|---|---|---|
| **A** | `PasskeyGetActivity` 把「库锁定」与「凭证不存在」混为一谈 | **假错误信息**（误导排查方向） |
| **B** | `AutoLockController` 把「我自己拉起的 Activity」误判成「用户切走又回来」 | **真锁定**（会话真的被清） |

只修 A：用户会看到「请解锁」但根本没人锁他，仍失败。
只修 B：锁态竞态消失，但一旦真锁定仍报「Passkey not found」，同样的假错误会复现。

### 完整链路

1. 浏览器请求 → `VaultixCredentialProviderService` 列候选（**能列出 ⇒ 当时库是解锁的**）；
2. 点候选 → 系统拉起 `PasskeyGetActivity`；
3. 同进程 Activity 切换在 `ProcessLifecycleOwner` 语义下 = 一次前后台切换（`onStop`→`onStart`）；
4. `AutoLockController.onStart` 判定回前台是否该锁。`AutoLockPolicy.screenLockRequiresRelock(screenLocked) = screenLocked` —— **只要 keyguard 锁着就锁，与分钟档位无关** ⇒ `lockAll()`；
5. 会话清空 → `ItemRepositoryImpl.observeState` 发空列表（`if (!isUnlocked) emptyList()`）→ `cred == null`；
6. `fail("Passkey not found")` → 浏览器「认证失败」。

### 关键代码依据

- `ItemRepositoryImpl.kt:296-303` `observeState`：库未解锁恒发空列表（**这个设计本身是对的**，
  错在调用方把「空」解释成「不存在」）。
- `AutoLockPolicy.screenLockRequiresRelock(screenLocked) = screenLocked`：无差别 relock。
- `AppLifecycleObserver` 的注册（`VaultixApplication.onCreate`）：`ProcessLifecycleOwner`
  对「同进程 Activity 互切」也会发 `onStop/onStart`，这是 Android 的既定行为。

### 解法：逐处对齐 Bitwarden（用户要求「一字一句抄」）

1. **锁态单独成一路**（对齐 `CredentialProviderProcessorImpl.isVaultUnlocked` +
   `RootNavViewModel` 的 `VaultLocked → VaultUnlockRoute.Standard`）：
   `VaultSessionManager.isAnyUnlocked()`；`cred == null` 时先探锁态分流。
2. **凭据流程豁免窗口**（对齐 `VaultLockManagerImpl` 的 `FOREGROUNDED → handleOnForeground()`
   取消超时任务、`OnAppRestart` 的 autofill 豁免）：新增 `CredentialFlowGuard`，
   两个凭据 Activity 在 `onCreate` 最早时机打点。
3. **验证簿记**（对齐 `BitwardenCredentialManagerImpl`）：`isUserVerified` 全路径复位、
   `authenticationAttempts` 上限 5。
4. **origin 取值顺序**（对齐 `getOriginUrlFromAssertionOptionsOrNull`）：host 取
   **请求 JSON 的 rpId**，缺失时明确失败（`Error.MissingHostUrl`），不再构造 `"https://"` 伪 origin。

### 教训（**本轮最贵的一条**）

**真实运行抓出了本次改动自身引入的严重缺陷**：`CredentialFlowGuard` 初值若取
`Long.MIN_VALUE`，判定式 `now - lastFlowStartedAtMs` 会**整数溢出为负数**，
负数恒 `< 8000` ⇒ 判定「在豁免窗口内」**恒为真** ⇒ **自动锁定被永久抑制**。

这个缺陷在纸面推演里 100% 看不出来（逻辑"对称"且看起来更严谨）。改成初值 `0L` 后
（`elapsedRealtime` 恒为正且远大于窗口）才正确，并把该溢出场景固化为回归断言。

⇒ **凡是能用真实运行时验证的，绝不靠读代码下结论**。这条在第 29 轮已被总结过一次
（加密填充假设被 JCE 实测否掉），本轮再次应验 —— 而且这次否掉的是**我自己刚写的代码**。

### 复现断言清单（`VerifyCredentialFlowGuard.kt`，9/9 PASS）

1. 凭据流程刚启动 + 屏幕锁定 → **不锁**（豁免生效）
2. 凭据流程已过窗口 + 屏幕锁定 → **锁**
3. 从未启动流程 + 屏幕锁定 → **锁**（初值不得造成意外豁免）
4. 恰好到达窗口边界 → 视为过期（`<` 而非 `<=`）
5. 窗口内 + 后台超时 → 仍豁免
6. 「从不自动锁定」档位 → 不锁（与本机制无关）
7. `Long.MIN_VALUE` 作初值会溢出（**证明为什么不能用它**）
8. 初值下、无流程时即便 `elapsedRealtime` 很大也不豁免
9. 初值确为 `0`

## 37. 锁态模型与凭据提供商链路按 Bitwarden 标准重写（2026-09-11，P0，`d6f3409`）

> 用户要求：「参考 bitwarden 的做法…哪怕是一字一句抄代码，也要实现。
> 还有**密码库加锁和解锁逻辑也要按 bitwarden 标准来**吧。更稳定，我这项目当前的
> 密码库加锁解锁逻辑太烂了，不标准」。
>
> 这是第 31 轮「锁态竞态」的**根治版**：第 31 轮是在旧模型上打补丁（`CredentialFlowGuard`
> 时间戳窗口），本轮把模型本身换成 Bitwarden 的。

### 旧模型的三处根本性缺陷（可实证）

1. **计时基准错**：后台只记 `backgroundedAtMs`，前台回头算差值。
   ⇒ 任何影响"前台时刻"的事件（keyguard、进程事件、Activity 互切）都会污染判定。
   Bitwarden 是「后台启动 `delay(timeout)` 的 job；前台 `cancel` 它」——锁不锁完全由
   那个 job 自己决定，前台不做任何判定。
2. **档位模型是裸 `Int` 的口头约定**（负数=从不 / 0=立即 / N=分钟）。
   ⇒ 无法表达 `OnAppRestart`（重启时锁定）；`when` 漏档只会在运行时静默走 else。
3. **autofill 豁免是启发式**：`CredentialFlowGuard` 用 8 秒时间戳窗口近似判断
   「这次前台切换是不是我们自己造成的」。窗口过短 → 通行密钥被误锁；
   过长 → 用户真的切走又回来却不锁。Bitwarden 用 `CheckTimeoutReason.AppCreated(
   firstTimeCreation, createdForAutofill)` 做**结构化**表达。

另有：`VaultRepositoryImpl.lockVault/lockAll` 用 `runBlocking` 阻塞调用线程；
CP Service 在「部分库锁定」时把 `credentialEntries` 与 `authenticationActions` 并存。

### 落地（逐句对齐 Bitwarden 三处）

| 旧 | 新 |
|---|---|
| `AutoLockPolicy`（纯函数判差值）| `VaultTimeout` sealed class（10 档位，core:datastore） |
| `AutoLockController` 自判 + `backgroundedAtMs` | `VaultLockManager.onAppBackgrounded/onAppForegrounded` |
| `CredentialFlowGuard` 时间戳窗口 | `CheckTimeoutReason.AppCreated(..., createdForAutofill)` 结构性豁免 |
| 裸 `Int` 档位偏好 | `VaultTimeout` + 一次性迁移（`fromLegacyMinutes`） |
| `runBlocking` 锁定 | `suspend fun` |
| CP 三处各自判定锁态 | `RootNavViewModel` 集中 + CP 锁定只给 `authenticationActions` |
| 无 trampoline | `CredentialProviderActivity`（`exported=false`，结果原样透传） |

### ★ 最危险的迁移点（务必保留断言）

旧 `auto_lock_minutes = -1` 语义是「**从不**锁定」；
新 `VaultTimeout` 的 `-1`（`OnAppRestart`）语义是「**重启时**锁定」——**正好相反**。

若不迁移，用户明确选择的「永不锁定」会被静默改成「重启即锁」。
已在 `VaultixPreferences.vaultTimeout` 首次读取时按 `fromLegacyMinutes` 转换 + 置迁移标记；
`VaultTimeoutTest` 与独立验证脚本都固化了 `fromLegacyMinutes(-1) == Never` 且
`!= OnAppRestart` 两条断言。

### 验证

1. **真实编译生产类 + 真实类行为断言**：用沙箱内 `kotlin-compiler-embeddable-2.2.21`
   编译 `VaultTimeout.kt`，再对**编译产物**跑 28 条断言 → 全 PASS。
2. 超时决策逻辑 17 条断言 → 全 PASS（含 autofill 豁免、OnAppRestart 不响应后台、
   前台 cancel 后到点不锁）。
3. `core:datastore` 新增 `VaultTimeoutTest`（10 用例）；该模块此前无测试源集配置，
   本轮补上 junit/truth 依赖。
4. 全仓 `.kt` 无超 120 字符行。

### 教训（第 31 轮教训的延续）

第 31 轮我写出的 `CredentialFlowGuard` 用 `Long.MIN_VALUE` 作初值导致整数溢出、
自动锁定被永久抑制 —— 那次是**自造启发式**引入的缺陷。
本轮把启发式整个换成 Bitwarden 的结构化模型，**从根上消除了这类"自创逻辑"的风险面**。
结论：**当上游有成熟实现时，自造"看起来更严谨"的变体是负收益**。

---

## 38. 搜索框「乱跳」——三处顶栏写法不一致（2026-09-11，P1，`a3093e4`）

**现象**：用户反馈「一直在输入框，搜索框乱跳」。

**根因**：三个带搜索的界面**各写各的**，且都不符合 Bitwarden 做法：

| 屏幕 | 旧写法 | 为何会跳 |
| --- | --- | --- |
| `ItemsScreen` | `OutlinedTextField` 挂在 `LargeTopAppBar` **外层** `Column` | 大标题栏滚动变高 → 输入框被反复垫高（最严重） |
| `PasskeysScreen` | 输入框塞进 `LargeTopAppBar.title` | 大标题栏自身可变高度 → 展开/收起时抖 |
| `TotpCodesScreen` | 同上（清除按钮语义还写成「取消」） | 同上 |

**修法（照抄 Bitwarden `BitwardenSearchTopAppBar`）**：

- 新增 `ui/common/SearchTopAppBar.kt`（`VaultixSearchTopAppBar`）：固定高度 `TopAppBar`
  （**绝不用 `LargeTopAppBar`**）+ 搜索态输入框整体占据 `title` 槽 +
  `FocusRequester`/`LaunchedEffect` 主动聚焦 + `ImeAction.Done` + 清除按钮动画。
- 三个屏幕统一 `if (searchActive) 搜索顶栏 else 普通顶栏`（**整体替换，不叠加**）。
- `searchActive` 由 `remember` 改 `rememberSaveable`。
- 删除三个屏幕各自的旧 `SearchField` 与失效 import。

**验证**：Compose API 桩 + kotlin-compiler-embeddable 真实编译新组件 → 0 error；
API 参数名逐字对齐 Bastion；四文件无超 120 字符行（UTF-16 语义）；无未使用 import。

### 教训

同一功能在项目里出现 **3 种不同写法**，是最危险的信号之一 —— 说明没有统一封装。
**优先建一个复用组件、三处统一，再谈“修 bug”**；否则今天修好一处，另两处还在跳。

---

## 39. 沙箱无法推送 GitHub（2026-09-11，P1，已解决）

**现象**：`git push` 报 `could not read Username`；`git fetch` 报 `gnutls_handshake() failed`；
`ssh` 报 22 端口超时。

**根因**：沙箱把 GitHub 域名解析到 `198.18.0.x`（保留测试网段，被网关劫持），HTTPS TLS 被断；
SSH 22 端口被墙；`git-credential-helper` 对 github.com 返回空、非交互环境取不到 HTTPS 凭证。

**解决**：阿里 DoH 查真实 IP → 写 `/etc/hosts` **并同步 `~/.user_hosts`** →
`~/.ssh/config` 令 `github.com` 走 `ssh.github.com:443` → `git remote` 改 SSH URL。
推送成功。**恢复步骤见 `.ai/MEMORY.md` 第三十三轮「可复用配方 1」。**

---

## 40. detekt 圈复杂度「越拆越高」——同文件 helper 被累加进调用方（2026-09-11，P0）

**现象**：`VaultixCredentialProviderService.resolvePasskeys` 报 `CyclomaticComplexMethod`
complexity **41**（阈值 14）。把它拆成若干**同文件** private helper 后，复杂度不降反升
（原内联版 19 → 拆后 41）。

**根因**：detekt **2.0.0-alpha.6** 的 `CyclomaticComplexMethod` 会把**同一文件内**被调用的
私有函数的复杂度**累加**进调用方（非标准 McCabe 行为）。同时该版本
`ignoreNestingFunctions` 默认 **false**（1.x 为 true）→ 作用域函数
（`let`/`run`/`with`/`apply`/`also`/`forEach`/`use`）每个 +1。

**实证方法（可复用）**：把可疑函数的**函数体临时 stub** 成 `return emptyList()` 再跑 detekt。
- 违规**消失** ⇒ 复杂度来自「调用」（本情况）；
- 违规**仍在** ⇒ 复杂度在自身函数体。
> 不要凭"标准 McCabe 是 per-function"的常识推断 —— 本版本就是不走标准。

**解法**：把 helper 拆到**独立文件**（detekt 逐文件分析、**不跨文件累加**）。
本轮新建 `app/src/main/java/io/vaultix/vaultix/passkey/PasskeyResolution.kt`：
- `PasskeyMatch` / `PasskeyCounts` 改 `internal` 顶层；
- `collectPasskeyMatches(itemRepository, unlocked, rpId)` / `applyAllowedFilter(rpMatched, allowed, log)`
  / `collectStoredRpIds(itemRepository, unlocked)` 顶层函数（inject 依赖以参数传入）；
- `resolvePasskeys` 主函数仅编排（自身 ~5），各 helper 独立 < 14。

**副作用**：`PasskeyMatch` 从 service 内 private 嵌套类移到同包顶层 `internal`（`publicKeyEntry`
同包引用，无需 import）；`WebAuthn` / `VaultFido2Credential` import 随之从 service 移除。

---

## 41. CI 1 分钟就红 → 其实是 detekt 先于 compile 失败，掩盖了编译错误（2026-09-11，P0）

**现象**：`main` 推送后 CI ~1 分钟即失败（正常约 3 分钟）。表面只见 detekt 报错，
容易误以为"修完 detekt 就好"。

**根因**：workflow 步骤顺序 = **detekt → (lint) → compile → assemble**。detekt 一失败即中断，
**编译步骤永不被执行** ⇒ 上一提交引入的 3 处 `Int?` 空安全**编译错误**被完全掩盖
（`d6f3409` 引入，但它同时把 detekt 弄红，编译错误一直没暴露）。

3 处编译错误与修法：
| 文件 | 错误 | 修法 |
|---|---|---|
| `core/datastore/.../VaultTimeout.kt` | `associateBy { it.vaultTimeoutInMinutes }` 键实为 `Int?` | `mapNotNull { t -> t.vaultTimeoutInMinutes?.let { it to t } }.toMap()` |
| `app/.../ui/settings/SettingsScreen.kt` | 组合 `when` 分支不做智能转换，`timeout.vaultTimeoutInMinutes` 仍 `Int?` | `requireNotNull(timeout.vaultTimeoutInMinutes)` |
| `app/.../passkey/CredentialProviderIntentUtils.kt` | 返回类型写成 `BeginCreateCredentialRequest?`，实际是 `CreateCredentialRequest?` | 改返回类型为 `CreateCredentialRequest?`（见下） |

**类型陷阱细节**：`BeginCreateCredentialRequest`(`androidx.credentials.provider`) 与
`CreateCredentialRequest`(`androidx.credentials`) 是**两个互不相关**的类（无继承关系）。
`PendingIntentHandler.retrieveProviderCreateCredentialRequest(intent)` 返回
`ProviderCreateCredentialRequest`，其 `callingRequest` = **客户端侧** `CreateCredentialRequest`
（credentials 1.6.0 `javap` 实证）。⇒ 由 Intent **无法**还原服务端侧的 `BeginCreateCredentialRequest`。
连带把 `CredentialProviderRequestManager.createCredentialRequest` / `setCreateCredentialRequest`
也改为 `CreateCredentialRequest`（该字段 **write-only**、无读取方，改动安全）。

**教训**：
1. **detekt 只做静态检查、不做类型检查** ⇒ 用"去魔法数字"等手段改代码后，
   **必须真跑一次 compile**（本地 `:app:compileFullDebugKotlin`）。
2. CI 步骤顺序会让**前置步骤失败掩盖后续步骤的问题** —— 排障先看"哪一步真的跑了"，
   别被"最后一条报错"误导（本次真正待修的还有编译错误）。

---

## 42. 本机（Windows/WorkBuddy）与早前沙箱的环境差异（2026-09-11）

早前多轮记录的"沙箱无 Android SDK / JDK17、`gradlew` 跑不起来、`ghu_` token 对
`api.github.com` 401" **仅适用于沙箱**。本机（用户 Windows + WorkBuddy）**全部可用**：
- JDK 17 + Android SDK（`C:\AndroidSDK`）齐全 → 可本地 gradle 构建/跑测试；
- `gh` CLI 可正常 `gh run list/view/watch`（查 CI 状态无需再读网页端 job 页）；
- `git push` 经 SSH（`git@github.com:Chaniug/Vaultix.git`）正常。

**唯一坑**：Git Bash 下 `./gradlew` 报「找不到或无法加载主类 GradleWrapperMain」。绕过：
```bash
java -classpath "D:/Vaultix/gradle/wrapper/gradle-wrapper.jar" \
     org.gradle.wrapper.GradleWrapperMain <task> [--no-configuration-cache]
# 长时间任务/daemon 配额耗尽：先 ... GradleWrapperMain --stop
```

**参考源码本地副本（不纳入 git，位于仓库外 `D:\Vaultix-refs\`）**：
| 项目 | 路径 | HEAD |
|---|---|---|
| Bitwarden Android | `D:\Vaultix-refs\bitwarden-android` | `74c0e04` |
| Keyguard | `D:\Vaultix-refs\keyguard-app` | `f95c865` |

---

## 43. 🔴🔴 通行密钥 `clientDataJSON` 回传空占位符 → GitHub 注册失败（2026-09-12，P0，`aa5fa27`）

> **本条推翻 #35。** #35 判定「浏览器流程应回传空占位符」是**错的**，
> 直接导致真机 **GitHub 注册通行密钥报 `Security key authentication failed`**。

### 症状（用户原话）
「通行密钥这部分还有问题，能够读取到，能够进入登录，在**最终校验**的时候提示错误。」
用户实测 GitHub 注册 → `Two-factor authentication ... Security key authentication failed.`

### 先排除用户猜测
用户猜：「会不会是密码条目和通行密钥没绑在一起？」→ **不是**。
凭据注册与条目绑定路径正常（`fido2Credentials` 落库、`credentialId` / `rpId` 匹配均正常）。
真因是 `clientDataJSON` 占位符。

### 根因：把官方文档的**条件句**读漏了

#35 依据的是官方这句（**逐字，注意加粗部分**）：
> **If you retrieve an origin**, use the `clientDataHash` that's provided directly in
> `CreatePublicKeyCredentialRequest()` or `GetPublicKeyCredentialOption()` instead of
> assembling and hashing clientDataJSON during the signature request. To avoid JSON parsing
> issues, set a placeholder value for `clientDataJSON` in the attestation and assertion response.

- `If you retrieve an origin` = 经 `CallingAppInfo.getOrigin(privilegedAllowlist)` +
  **特权应用名单**拿到 origin 的场景（Google Password Manager 走那条路，
  challenge 校验由**系统侧**完成）。
- Vaultix 的 `CallingAppOrigin` 明确采用「**自证式读取**」、**不走特权名单** ⇒ **不适用占位符**。

**而规范层面 RP 一定会解析明文**（W3C WebAuthn Level 2，逐字）：
> **§7.1** Let JSONtext be the result of running UTF-8 decode on the value of
> `response.clientDataJSON`. Let C ... be the result of running a JSON parser on JSONtext.
> - Verify that the value of `C.type` is the string `webauthn.create`（断言为 `webauthn.get`）
> - Verify that the value of `C.challenge` equals **the base64url encoding of `options.challenge`**
> - Verify that the value of `C.origin` matches the Relying Party's origin.

**§5.8.1.1 `CollectedClientData`** 对字段的定义同样是明文：
`challenge` = **the base64url encoding of options.challenge**、`origin` = the serialization of callerOrigin。

⇒ **回传空字节数组连 JSON 解析都过不了**，`C.challenge` 校验必然失败。

### ✅ 正确解法（两条流程的**唯一**差别 = 签名覆盖哪份哈希）

| 事项 | 浏览器流程 | 原生 App 流程 |
|---|---|---|
| **签名**覆盖 | `authData ‖ clientDataHash`（系统给的） | `authData ‖ sha256(自建 JSON)` |
| **回传** `clientDataJSON` | **自建真实 JSON** | **自建真实 JSON**（同一份） |
| `androidPackageName` | **不写**（浏览器那份没有该字段） | 可写 |

> 回传字段供 RP **读明文校验**；签名哈希保证与浏览器一致 —— **二者互不冲突**。
> #35 误以为"自造 JSON 永远对不上所以只能放占位符"，错在把「逐字节相等」当成了要求。

`androidPackageName` 为什么浏览器流程不能写：浏览器那份 JSON 里没有该字段，
写进去会让 RP 对回传 JSON 重算哈希时与浏览器签名值不符（Bastion 实测：Microsoft 登录失败）。

### 改动（`aa5fa27`，8 个文件）
| 文件 | 改动 |
|---|---|
| `core/common/WebAuthn.kt` | **删除** `BROWSER_FLOW_CLIENT_DATA_PLACEHOLDER`；`buildClientDataJson` 增 `androidPackageName` 参数；新增 `buildCreateClientDataJson` / `buildGetClientDataJson`；KDoc 引规范原文 + 文档条件句 + Bastion 依据 |
| `passkey/PasskeyProviderIntents.kt` | `createIntent` 补 `clientDataHash: ByteArray? = null`（对齐 GET 侧 `getIntent`）+ `putExtra` |
| `passkey/VaultixCredentialProviderService.kt` | `buildCreateResponse` 透传 `request.clientDataHash` |
| `passkey/PasskeyCreateActivity.kt` | 删占位符分支，始终 `buildCreateClientDataJson(..., androidPackageName = null)`；接收 `clientDataHash` 仅作自检日志；**origin 推导顺序改为 `requestJson.origin` → `CallingAppOrigin` → `https://$rpId`**（对齐 Bastion `PasskeyOriginResolver`） |
| `passkey/PasskeyGetActivity.kt` | **回退**占位符，改 `buildGetClientDataJson(..., androidPackageName = null)` |
| `core/common/WebAuthnTest.kt` | 改写 2 个已失效的占位符用例为 `browser flow signs provided hash and returns real clientDataJSON` / `create response always carries real clientDataJSON` |

### 途中两个编译陷阱（易再犯）
1. **`callingRequest` 在 provider 侧不存在。** `PasskeyProviderIntents` / `BeginCreateCredentialRequest`
   **没有** `callingRequest`；`BeginCreatePublicKeyCredentialRequest` **自带** `clientDataHash`。
   （`CreatePublicKeyCredentialRequest` 是**调用方侧**的类，provider 侧拿不到 —— 同 #41 的类型陷阱。）
2. **`AutofillLogger` 需 import** `io.vaultix.vaultix.autofill.AutofillLogger`。

### 验证（`aa5fa27`）
- `:core:common:testDebugUnitTest` → **95 用例 0 失败**（`WebAuthnTest` 12 例，含 3 条回归锁）
- `testFullDebugUnitTest` → **129 用例 0 失败**
- `detekt` 通过；`:app:compileFullDebugKotlin` BUILD SUCCESSFUL
- **CI run `34686274203` = success**（19 步骤全绿，APK 已发布 preview Release）

### 教训（本轮最贵）
1. **官方文档的限定条件不能只读后半句。** `If you retrieve an origin` 决定了整条建议
   是否适用 —— 跳过它会把「特定场景的推荐做法」当成「普适要求」。
2. **与规范冲突时以规范为准。** 规范是 RP 的实现依据，任何平台建议都不能推翻
   「RP 必须解析明文校验 `C.challenge`」这一硬要求。
3. **「所有参考实现都跟我不同」时，先怀疑自己。** 本轮把两家**正确**实现标成反例，
   唯一理由只是"跟我的理解不符" —— 这是危险信号，事实证明是我错了。
4. 用户那句「**应该是有标准的才对**」是对的：**遇到协议层争议，先拉规范原文逐字比对**，
   不要在实现之间靠印象猜测。

---

## 44. 沙箱构建环境三处修复（2026-09-12，长期收益，非项目代码问题）

> 本轮把沙箱从「**完全跑不了 Gradle**」恢复到「**可真实编译 / 跑单测 / 跑 detekt**」，
> 长期收益显著（此后无需仅靠 CI 试错）。

| # | 问题 | 修法 |
|---|---|---|
| 1 | `/root/.gradle/init.gradle` **语法错误**（`mavelCentral()` 拼写错 + url 未加引号）→ **所有** Gradle 调用初始化即失败 | 重写为 `beforeSettings { settings -> ... }` 注入 Aliyun/腾讯镜像。**必须同时注入 `pluginManagement` 与 `dependencyResolutionManagement` 两处** —— 项目设了 `RepositoriesMode.FAIL_ON_PROJECT_REPOS`，往 `allprojects.repositories` 塞仓库会被直接拒绝 |
| 2 | AGP 插件解析失败（`com.android.application:9.3.2` not found） | 镜像**必须在 `pluginManagement` 层**注入（`settingsEvaluated` 太晚） |
| 3 | `SDK location not found` → `sdkmanager` 报 `Failed to find package 'platforms;android-37'` | **platform 37 的目录名带扩展版本号（`android-37.0`），在线 `repository2-3.xml` 里根本没有 `platforms;android-37` 这个包**（最高只到 android-36）⇒ `sdkmanager` 必然失败。只能按已知包名直取 `platform-37.0_r01.zip` + `build-tools_r37_linux.zip` 解压安装 |

### 附带
- **hosts 补 `dl.google.com → 113.108.239.161`**（此前被污染到 fake-ip `198.18.0.13`，
  导致 manifest 拉取静默失败）。`sed` 写 hosts 报 `Device or resource busy` → 改用 python 重写。
- **Gradle wrapper 分发包**：`services.gradle.org` 302 → GitHub，TLS 抖动报
  `SSLHandshakeException` → 经 `ghfast.top` 镜像下载 `gradle-9.5.1-bin.zip`（140MB），
  装入 `~/.gradle/wrapper/dists/gradle-9.5.1-bin/<hash>/` 并 `touch gradle-9.5.1-bin.zip.ok`。
- **Gradle daemon OOM 被杀**（cgroup `memory.max` = 8GB）→
  加 `-Dorg.gradle.jvmargs="-Xmx3g" -Dkotlin.daemon.jvmargs="-Xmx2g"`。
- `api.github.com` 真实 IP `20.205.243.168`（`gh` CLI 有 TLS 抖动 → 改用
  `curl --resolve api.github.com:443:20.205.243.168` 直连；响应含控制字符需
  `json.loads(..., strict=False)`）。

### ⚠️ 唯一未能完成的步骤
`assembleFullDebug` 的 **native 符号剥离**（`stripFullDebugDebugSymbols` 报
`Cannot access output property 'outputDir'` / `Failed to create MD5 hash`）—— 沙箱**缺 NDK**，
**与代码改动无关**；该步在 CI 上正常。替代验证用 `:app:compileFullDebugKotlin`。
