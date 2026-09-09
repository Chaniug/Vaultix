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
