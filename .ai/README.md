# AI 协作记忆

> 供 **AI 接力**使用。开工前必读，收工后必更新。

> 🗺 **找"东西在哪 / 该信哪份文档"** → [`conventions/8.10-架构与目录地图.md`](./conventions/8.10-架构与目录地图.md)
> （模块依赖图 · 三层钥匙图 · 模块/目录/包地图 · **`Docs/` 是设计期蓝图、已滞后于实现**）

## ⚠️ 唯一真源在这里（2026-09-13 起）

**`.ai/` 是记忆的唯一定稿处，已入库、并被代码注释直接引用。**
`.workbuddy/memory/MEMORY.md` 只是**入口指针 + 3 条保命规则**，**不再镜像正文** ——
此前约定「两边保持同步」，结果是同一主题两处各有一份、必然漂移（AI 会看错位置）。
**要改内容，只改这里。**
## 🔧 沙箱能力（2026-11-10 打通 · 别再"写错一个字赔一轮 CI"）

> 详见 [`.ai/tools/SANDBOX-CAPABILITIES.md`](./tools/SANDBOX-CAPABILITIES.md)。

- **能**：真编译 + 真跑单测（core / domain / data 六个模块 **940 条用例 ≈ 3–4 分钟**）、
  类型错误 / 未解析引用 / 混编 Java、全套 `check_*.py` 静态门禁。
- **不能**：APK、lint/detekt、`app` 的 Compose 签名（单一 source set，没插件整包编不过）。
- **首次**：`bash .ai/tools/typecheck_kotlin.sh --fetch-compiler`（约 58MB）。
- ⚠️ 这套工具**此前在沙箱里从未真正跑起来**（它找 gradle 缓存里的编译器，而沙箱不跑 gradle）。
  本轮修了四处才通：stdlib 回退、`.java` 进闭包、`javac` 补字节码、批量模式同款。
- 已接进 CI：`ci-debug.yml` 的 `sandbox-tests` job 用**同一份脚本**，与 `android-ci` 并行
  ⇒ 本地绿 ⇒ CI 不会红在同一件事上。

## 🕐 最新状态（2026-11-17 · #301.1 点 ⋮ 直接闪退，已修）

> **一句话**：用户「点三个点的按钮会闪退。直接闪退的」——确认是**条目页顶栏 ⋮**，
> 且「**一点就崩、菜单根本没出现**」⇒ 崩在 Popup 首次组合 + 测量阶段。
> **根因是两个各自正确的东西撞在一起**，不是功能 bug。

- **因果链**：
  1. M3 `DropdownMenuPopup` 内部是 `Column(modifier.width(IntrinsicSize.Max))`
     （`AndroidMenu.android.kt:165`）⇒ 开菜单时向子树**索取 intrinsic 宽度**；
  2. 我在 #301 把组 1 来源行换成了 `MiddleEllipsizedText`，它内部是
     **`BoxWithConstraints` = `SubcomposeLayout`，不支持 intrinsic 测量** ⇒ 直接抛
     `IllegalStateException: Intrinsic measurements are not currently supported by SubcomposeLayout`。
- **为什么改版前不崩**：老 `MenuVaultCard` 的来源行用的是**朴素 `Text` + Ellipsis**，
  菜单里从来没调用过 `MiddleEllipsizedText`（`ItemsScreen.kt` 里那个调用点在**列表**的
  `VaultStatusRow`，不在菜单）。⚠️ **我把组件从 A 场景搬到 B 场景，却没检查 B 的约束模型。**
- **修法**：新增 `MiddleEllipsizedTextInIntrinsicScope`，用 `Modifier.layout` 在**布局阶段**
  回读 `constraints.maxWidth`（不参与 subcompose），保留中间省略。
  不退回朴素 `Text`（会失去中间省略，长路径尾部文件名被吃掉）；
  不用固定 280dp 硬算（会把字号 / 无障碍大字体钉死在推算里）。
- **门禁**：新增第 8 条「菜单内不允许出现不支持 intrinsic 测量的 subcompose 组件」
  （`BoxWithConstraints` / `SubcomposeLayout` / `LazyColumn` / `LazyRow` /
  `rememberTextMeasurer`），并正向钉住必须用 intrinsic-safe 变体。
- **变异反证**：真的把 `BoxWithConstraints` 写回去 ⇒ 首轮是 `compileFullDebugKotlin FAILED`
  （漏 import）= **假阳性**，补齐后重跑才拿到真断言红。
  ⚠️ **"红的是断言不是编译"本轮踩了两次**（#301 变异① 与本次）。
- **验证**：`app` **740 例 0 失败**（739 + 1）、detekt 通过、**25 门禁 + 20 自测**全绿。
- **CI 实证**：`7ea33a81` → `Android CI (Debug)` **44030045946** 两 job 全绿；preview **`Vaultix-7ea33a8.apk`**（35.87 MB）。
- **待真机确认**：⋮ 点开不闪退 / 来源行长路径仍**中间省略** / 系统字号调到最大仍不溢出。

## 🕐 上一轮（2026-11-17 · #301 条目页 ⋮ 菜单改版）

> **一句话**：用户「这个部分的 UI 该如何设计才好看呢？信息直观，功能完善，目前感觉这个看起来
> 很老套的样子」+ 截图（条目页顶栏 ⋮ 展开的下拉）。**功能一个没增、一个没删**，
> 只把「一整块 `DropdownMenu` + 两条分隔线」换成「**三块 M3 Expressive 独立卡片**」。

- **改前为什么老套**：单个 `DropdownMenu`、一整片同色底板、4dp 圆角、组与组只靠 1dp 灰线区分、
  组标签是「前往」这行 11sp 灰字。问题不在配色，而在**结构与项目语言不一致** —— 本项目卡片是
  16dp 圆角 + 8dp 间隙 + 1dp `outlineVariant` 描边（`EntryCard` / 设置卡都这样）。
- **三组分别回答一个问题**：① 库身份（**我在哪**）② 切换密码库 / 验证码 / 回收站 / 文件夹管理
  （**去别处**）③ `[●] [⟳] ←weight→ [🔒]`（**对这个库做什么**）。
- **四处取证（都不是"感觉这样好"）**：
  1. 外层必须 `DropdownMenuPopup` —— **`DropdownMenu` 自带整块底**，在里面套 `DropdownMenuGroup`
     会被那块底重新粘成一片。这个坑**编译器与 detekt 都看不出，只能读框架源码**。
  2. 三组统一 `MenuDefaults.groupShapes(VaultixCorner.Panel, …)` 16dp 四角全圆，**不用**
     `groupShape(i, n)` —— 那是「一整块被切开」语义（首 16/下 8，配 `GroupSpacing = 2.dp`），
     density 3.47 下渲染成亚像素线，读起来仍是一整片。
  3. 间隙 `Spacing.sm`(8dp)，不用 `MenuDefaults.GroupSpacing`(2dp)；8dp 是本项目标尺
     「相关元素之间」那一档。
  4. **1dp `outlineVariant` 描边是"能不能分出三块"的关键** —— 截图逼出来的：组色
     `surfaceContainer #F1F0EF` 与底 `surface #FDFCFB` **亮度只差约 4%**，无描边时
     浅色下三块重新糊成一片，等于白改。
- **信息直观（加的东西）**：组 1 补 **40dp 库类型徽标**（Bitwarden / KDBX 图标，`primaryContainer`
  圆牌）；`VaultCardInfo` 新增 **必填** `kind`（无默认值 —— 有默认值就会有人不传，退化成
  「少一枚徽标」而不是编译错误）。
- **信息直观（删的东西）**：组 2 的「前往」小标题。改成独立圆块后**这个信息由形态本身给出**，
  再写一遍等于用文字解释一个一眼可见的结构 —— 那行 11sp 灰字正是"老套"的来源之一。
- **刻意不动**：组 3 `MenuSyncLockRow` 内容**一字未改**（2026-11-08~09 改四轮才定稿，
  「删元素能解决拥挤，但会制造空旷」）；三组共用 `MENU_PANEL_WIDTH = 280.dp`
  （2026-10-04 真机对照过切库变形问题）。
- **门禁**：`ItemsMoreMenuGroupingTest` **7 条**（Popup 形态 / 三组包进 `MenuGroup` / 正好 2 个 gap /
  必须带 `outlineVariant` 描边 / 共用固定宽 / 用 `MenuDefaults.itemShape` / **四个入口与两个动作
  一个都不能丢**）。**四条变异反证全红，还原复绿**。
  ⚠️ 变异① 首轮是**编译红（假阳性）** —— `DropdownMenu` 的 import 已删，补回 import 才拿到真断言红。
- **验证**：`app` **739 例 0 失败**（732 + 7，87 份 XML）、detekt 通过、
  **25 个门禁 + 20 个自测**全绿。
- **CI 实证（2026-11-17）**：`67993348` → `Android CI (Debug)` **44006173605** 两 job 全绿 /
  `AI docs` **44006173648** 全绿（文档提交 `69045c28`）；preview
  **`Vaultix-69045c2.apk`**（37,608,514 B ≈ 35.87 MB）。
- **待真机确认**：浅色下三块能否**一眼**分出（描边够不够）/ 深色下 8dp 间隙是否过疏 /
  40dp 徽标是否喧宾夺主 / 多库时「切换密码库」是否仍显眼 / 库未知（`vaultCard == null`）
  退化成的两组形态是否正常。
- **视觉对比稿**：`/workspace/preview-vault-menu.html`（仓库外，不进提交）—— 双机并排
  「改前 vs 改后」+ 浅/深色切换 + 紧凑/完整切换。

## 🕐 更早（2026-11-17 · #300 自动锁定审查 + 验证码 ⋮ 对齐）

> **一句话**：两组用户反馈——① 「自动锁定有没有 bug」⇒ 逐环节对照 Bitwarden 后判定
> **核心健全、一行没改**（并写明四处"看着像 bug 其实不是"的理由，防止接力者被诱导重改）；
> 只修了一个确凿死分支：自定义时长「天」永远不可达。② 「验证码 ⋮ 偏左」⇒ 反编译取证
> **M3 IconButton 容器恒 48dp**（2026-10-19 注释按 36dp 算错），图标视觉右移 6dp，
> **48dp 热区原样保留**。

- **自动锁定审查结论（不动核心）**：`VaultTimeout` 10 档（含 `OnSystemLock` / `OnAppRestart`）、
  `checkForVaultTimeoutInternal` 四族穷尽分流、每库 `timerJobMap` 回前台全撤、熄屏后按
  `elapsedRealtime()` 单调时钟重算并钳 `[0, duration]`、`isDeviceLocked` + 300ms 单次复检 +
  可作废 token、「从不」档密钥常驻 + 界面层 `viewLock` 双层、门禁开关与 `AutoViewGate` **同源**
  （都是 `fingerprintLockAvailable()` 投影）—— 逐条对上上游 `VaultLockManagerImpl`。
- ⚠️ **刻意不改的四点（别再改）**：`checkForVaultTimeout(UserChanged)` 生产零调用（那是对齐上游
  **账号切换**语义，Vaultix 单用户多库没有这个场景）；`onAppCreated` 只处理 `activeVaultId`
  （刚起进程时非活动库本就锁着）；改档位不重算当前会话定时器（上游同样等下次切后台）；
  `devRelaxScreenLock` 只拦 `OnAppRestart`（放开会让单测与产品语义分叉）。
- **真 bug（已修）**：`vaultTimeoutLabel` 的 `Custom` 分支把 `MINUTES_PER_HOUR` 判断写在
  `MINUTES_PER_DAY` 之前，而 1440 = 24 × 60 ⇒ **任何整天的分钟数必然也是整小时**，
  `auto_lock_day_fmt` 是死资源：填 1440 显示「24 小时」、2880 显示「48 小时」，不崩不报错。
  修法：抽纯函数 `customTimeoutUnit()`（**天在小时前**），`vaultTimeoutLabel` 改调它；
  `CustomTimeoutUnitTest` 4 例（整天 / 23h 不被天吞 / 分钟 / 上限 69 天全遍历）。
- **验证码 ⋮（已修）**：2026-10-19 把 `size(36.dp)` 换成 `minimumInteractiveComponentSize()` 时，
  注释按「容器 36dp ⇒ 左右各留 8dp」推算"贴右距离不变"——**前提是错的**。反编译
  `material3-android:1.5.0-alpha16` 的 `IconButtonKt`，字节码 `StateLayerSize = 48`
  ⇒ 20dp 图标居中左右各留 **14dp**，视觉右缘比原设计多缩 **6dp**，正是用户说的"偏左了一点"。
  修法：热区 `minimumInteractiveComponentSize()` **保留**、图标 `offset(x = 6.dp)` 推回右缘 8dp；
  **只 offset 图标不 offset 按钮** ⇒ 点击手感不变、图标仍在 48dp 容器内不被裁。
  `TotpRowMenuAlignmentTest` 4 条源码门禁（**先剥 `//` 注释**，否则 KDoc 里的「36dp」会误命中）。
- **变异反证**：① 小时判断调回前面 ⇒ 2 例红；② 去掉 offset ⇒ 1 例红；③ 热区退回 `size(36.dp)`
  ⇒ 2 例红。全部字节级还原后复绿。
- **验证**：`app` **732 例全绿**（724 + 4 + 4）、detekt 通过、**23 个门禁 + 20 个自测**全绿、
  Markdown 链接 **276 个文件**全部有效。
- **CI 实证（2026-11-17）**：`449da722` → `Android CI (Debug)` 43996846249（两 job 全绿）/ `AI docs` 43996846308 全绿；preview **`Vaultix-449da72.apk`**（35.85 MB）。
- **待真机确认（两条，肉眼即可）**：设置 → 锁与安全 → 自动锁定 → 自定义填 **1440**，应显示
  「**1 天**」（此前是「24 小时」）；验证码列表卡片右上角 ⋮ 的图标右缘应与其他列表行尾元素贴右一致。

## 🕐 更早（2026-11-16 · v0.13.6 正式发布，当日收工）

> **一句话**：今日两件大事全部闭环并发版 —— ① #294~#298 Bitwarden 同步修复链
> （`encryptedFor` 真机验收通过）；② #299 浏览器通行密钥 origin 透传（Via 真机验收通过）
> + 真锁页视觉统一。**v0.13.6** 已发 Latest，tag `v0.13.6` → `36a78647`，
> main / rele / tag 三者一致。只推 main + 一次快进 rele，绝不二次推 rele。

## 🕐 更早（2026-11-16 · #299 浏览器通行密钥 origin 透传修复（已发版））

> **一句话**：Via 浏览器（WebView）上注册通行密钥，弹窗点保存即报
> "Origin does not match relying party"。根因：CP 层用真实 `callingAppInfo` 读到了 origin，
> 但 `createIntent()` 没透传；中转 Activity 用 `retrieveProviderCreateCredentialRequest()`
> 取回的 `callingAppInfo` 在浏览器流程下丢了 origin（自证名单读不出），两条来源都空 ⇒ 校验拒绝。

- **真机日志（18:16，三连失败）**：
  `CP CREATE begin caller=mark.via origin=https://pwd.vv1234.cn/`（CP 读到）
  → `PKC origin caller=- request=- verdict=Rejected` → `PKC rejected: origin 无法解析出主机（origin=）`。
- **集思广益（三个参考项目）**：
  - bitwarden：`OriginManagerImpl` 读真实 assets 资产链接名单，`validatePrivilegedApp` 校验包名+签名指纹；
  - keyguard：`AndroidCallingAppOriginResolver`，特权名单命中取 origin，否则用调用方签名 hash；
  - Monica：`PasskeyOriginResolver` 五级降级链，末级 `https://{rpId}` 兜底（真实 assets 名单 73 个浏览器）。
  ⚠️ 排除"Via 不在名单"假设：CP 层用**同一个** `CallingAppOrigin.originOrNull()` 读到了 origin，
  证明自证名单对 Via 有效。差异是 CP 与 Activity 拿到的 callingAppInfo 不是同一对象。
- **不采纳 Monica 的 rpId 兜底**：Vaultix CREATE 侧当年**主动删掉**过 `?: "https://$rpId"`
  （`PasskeyCreateActivity:199` 注释：凭空构造会让校验形同虚设）。本次透传的是系统给的真实 origin，
  `PasskeyOriginPolicy.check()` 严格校验**不动**——既修浏览器又不降安全门槛。
- **KDBX 结论（用户问）**：**同样受影响、同样被修复**。通行密钥注册共用 CP → `PasskeyCreateActivity`
  → origin 校验，分流到 Bitwarden/KDBX 发生在**写库时**（`isKdbx()`），在校验之后。
  故 origin 校验失败与目标库无关；一次修复两边都好。GET 侧（使用密钥）走 rpId 匹配，本就不受影响。
- **已落地修复**：`PasskeyProviderIntents.EXTRA_ORIGIN` + createIntent 透传；CP 层读真实 origin 传入；
  Activity 三来源 `provider > caller > request`（日志加 `provider=` 字段）；
  新增 `check_passkey_origin_handoff.py` + 7 组变异自测（含表达式体函数与注释假红的坑）+ CI 两步。
- ⚠️ 待 CI 编译 + 单测；真机复验判据：Via 注册点保存不再报 "Origin does not match relying party"，
  `PKC origin provider=https://pwd.vv1234.cn/ ... verdict=Ok`，通行密钥落进所选库。
  **v0.13.5 已发布，本修复尚未发版**（待验收后决定是否 v0.13.6）。
- **✅ CI 绿**：两 run [43849875873](https://github.com/Chaniug/Vaultix/actions/runs/43849875873)
  / 43849875951 success（commit `d9403903`），新门禁输出「✅ passkey origin 透传契约检查通过（#299）」+
  变异自测 7/7；preview `v0.13.5-dev-d940390`，资产 `Vaultix-d940390.apk`（37.6 MB）。
  待用户装此包真机复验后再决定是否发 v0.13.6；只推 main，绝不推 rele。

## 🕐 更早（2026-11-16 · v0.13.5 正式发布：Bitwarden 同步修复闭环）

> **一句话**：#294~#298 五批 Bitwarden 同步修复经两轮真机验收通过，已发 **v0.13.5 正式版**
> （`Android Stable Release` run `43846571223` success，tag `v0.13.5` → `ab39130f`，
> 已置 Latest，APK `Vaultix-v0.13.5.apk` 6,977,746 B / SHA-256 `e2ecfe4a…d48076`。
> ⚠️ 只推 main + 快进 rele 触发发版；发完回填只推 main，**绝不二次推 rele**。

## 🕐 更早（2026-11-16 · #298 请求体缺 encryptedFor ⇒ 1.37+ 服务端 422）

> **一句话**：真机改网址同步，服务端始终不变——根因锁定为**请求体缺 `encryptedFor`**，
> 而 Vaultwarden 1.37+（web-v2025.6.0）在 `Json<CipherData>` 守卫里把它设为必填，
> 缺即在 handler 之前 **422**。官方 bitwarden-android / keyguard / Monica 三客户端都发。

- **真机取证链（四层闭环，首次全落地）**：
  ① 日志 `flushPending op=UPDATE id=d19f28e7… code=422 definitive=false`，body 是 Rocket 默认 HTML；
  ② 从设备 `pending_ops.payload` 直读被拒请求体原文（`createdAt` 与日志相差 12ms，确证是本次）；
  ③ 三参考项目对照都发 `encryptedFor` = JWT `sub`；
  ④ Vaultix `CipherRequest` 全模块零出现 ⇒ 必然 422。
  ⚠️ 推翻了两个早期错误假设：不是通行密钥（217 条里带 fido2 的 ~20 条）、不是 `creationDate` 微秒精度。
- **为什么 #294~#297 没找到**：本地起 1.30/1.33 全 200（旧版无此必填字段），静态对照的也是旧版服务端模型；
  变化发生在 1.37。这不是排查失误，是服务端协议静默升级。
- **已落地修复**（本次，`58e3440b` 之后）：
  ① `CipherRequest` 加 `@SerialName("encryptedFor")`，默认 null（存量兼容）；
  ② 统一发送出口 `executeOperation` 在 CREATE/UPDATE 发送前注入 `encryptedFor` = access token 的 `sub`（UUID 校验），
     **存量 op 也救活**（不必清队列）；取不到 token 时降级跳过 + WARN，不阻断同步；
  ③ 新增 `BitwardenSyncEncryptedForTest` 4 例（UPDATE/CREATE 注入、token 缺失降级、非 UUID 降级）；
  ④ 新增 `check_encrypted_for.py` + 7 组变异自测，接 `ci-debug.yml` 两步。
- **对"同步快"的说明**：Bitwarden 协议只有 `GET /sync` 全量端点（Monica 亦然），无增量拉取。
  修好本 bug 后就地消掉了"每次推 422 + 全量拉取"的无用功，revision 未变时直接 Skipped——
  这才是体感上最大的提速；真有改动时的全量下载是协议限制，短期不动。
- ⚠️ **待 CI 真编译 + 单测**（本机无法编译）；只 push `main`，绝不推 `rele`。
  装机复现判据：日志出现 `flushPending op=UPDATE … code=200`（不再是 422），服务端修改时间推进。
- **✅ CI 真实结论（2026-11-16 17:13，commit `5a3b6a41`）**：两 run 全 success ——
  `Android CI (Debug)` [43837076839](https://github.com/Chaniug/Vaultix/actions/runs/43837076839)
  （detekt `BUILD SUCCESSFUL in 18s`、新门禁输出「✅ encryptedFor 契约检查通过（#298）」、
  4 例新测试与全量单测通过）、`AI docs consistency` 43837076961。
  preview `v0.13.4-dev-5a3b6a4 · Preview`，资产 `Vaultix-5a3b6a4.apk`（37.6 MB）。
  ⚠️ 只推 `main` 未推 `rele`，正式 v0.13.4 不受影响。
- **⚠️ 首轮真机验收修红（用户装 `5a3b6a4` 复现仍 422）**：日志出现本轮新增的
  `encryptedFor 未注入（无法从 access token 解析 user id）` ⇒ 修复代码跑了，但取值传错参数：
  `userIdFromToken` 把 `row.origin`（`https://pwd.…`，完整 baseUrl）传给 `accessTokenForHost`，
  而它的 key 是 `URL(server).host`（纯 host）⇒ 查不到 ⇒ 降级跳过 ⇒ 依旧 422。
  `BitwardenAuthInterceptor` 用 `url.host` 才是同口径；单测当时也 mock 成完整 URL，跟着一起错。
  修法：先 `URL(server).host` 再查；测试改 mock key + verify 钉住；门禁加变异用例（8/8 自测）。
  **✅ 修复后 CI 全绿**：两 run [43840280935](https://github.com/Chaniug/Vaultix/actions/runs/43840280935)
  / 43840280850 success，preview `v0.13.4-dev-8a0bc21`，资产 `Vaultix-8a0bc21.apk`（37.6 MB）。
  ⇒ **✅ 第二轮真机验收通过（2026-11-16 17:57–17:58，用户装 `8a0bc21`）**：
  ① 存量 3 条 op（含上午积压的旧 UPDATE）拿到 encryptedFor 后越过 422，服务端回
     `400 "client copy is out of date"`（10/28 过期基线，#294 语义）⇒ 判确定性失败弃单，
     队列清空 `剩 0 条`——**存量被正确清理**（这批本就是该被服务端当前版本覆盖的旧改动）；
  ② 随后全量 `sync ok: cipher 217`,本地对齐服务端；
  ③ 用户**本次新改的网址**：`flushPending 待推送 1 条 → 完成：1 条全部推送成功，队列已清空`，
     `本地写回完成并已推送 … 无待推送残留`——**闭环**。
  ④ 全程无 `encryptedFor 未注入`、无 422；再点同步 `队列为空 → revision 未变 → Skipped`（正常）。
  **#298 真机验收闭环，可发正式版。**

## 🕐 更早（2026-11-16 · #297 同步看不见 ⇒ 无法定位）

> **一句话**：云端还是没变、点同步还是"成功"，但这次的日志比上一轮**更少** ——
> 只剩一行 `sync skipped: revision 未变`，**连 `flushPending` 都没有**。
> 于是分不清「改动没进队列」与「进了队列被清掉」，两种故障修法完全不同。

- **证据**：`vaultix_logs_20261116_155316.txt`（`0.13.4-dev-d881fb3`，**已含 #296**）。
  15:51:54 点同步；15:51:54.315 只有 `sync skipped: revision 未变`。
  对比上一份 `..._142908.txt` 里明明有 `flushPending ... code=422`。
- **为什么这轮没能直接给出服务端字段**：唯一根因是**可观测性缺口**。
  `flushPending` 在队列为空时是**静默 `return Result.success(Unit)`** ——
  没日志、没痕迹，而 `executeSync` 随后照常报 `Skipped`（上层当成功）。
  写路径 `flushAfterLocalWrite` 成功时同样**一条日志都没有**。
- **静态排查（又一次）**：`BitwardenJson` 是 `explicitNulls=false`、未开
  `encodeDefaults` ⇒ null 字段被省略；`buildUpdateRequest` 里"改 URI"路径上的
  `type` / `match` / `reprompt` 都是有界 Int，`uri` / `name` 是 EncString 或 null
  ⇒ **请求体值层面也不存在能凭空造出 422 的非法值**（与本地起 1.30/1.33 实测全 200 一致）。
- **已落地的处置**：① `flushPending` 空队列**必须留 WARN**（"队列为空 + 不会上传"两层语义）；
  ② 开头报「待推送 N 条（按 op 类型）」、收尾报「全部成功 / 剩 M 条留在队列」；
  ③ 写路径统一出口 `flushAfterLocalWrite` 成功与失败**都留痕**，且共用 `VaultixSync` tag
  ⇒ 用户一次 `adb logcat -s VaultixSync` 就能看到「入了什么队 + 推了什么」；
  ④ 日志摘要只聚合 op 类型，**不得带条目 id**（`VaultixLog` 铁律）。
- **验证**：新增 `BitwardenSyncFlushObservabilityTest` 3 例
  （空队列留痕 / 成功报条数且无 id / 失败说明改动仍在队列），
  `data/bitwarden` **124 例全绿**（121+3）；行为反证：把空队列改回静默 ⇒ 用例立刻红，
  字节级还原复绿。新增 `check_sync_observability.py` + **14 条变异自测**，
  接 `ci-debug.yml` 两步（`android-ci` 42 → 44 步）。
  ⚠️ 写门禁时**自测抓出 3 个真实假绿**（KDoc 里同词、按"下一个 fun"截取把注释算进函数体、
  判断常量名用了 sanitize 后的文本），均已修并有对应用例。
- **同批追加（写路径闸门卡死）**：顺着"A 判据"（队列为空）往回查，在三个写入口发现
  `saving` 这个**防连点闸门会卡死**：复位写成 `repo.xxx()` 的**下一句**，而仓储
  `ItemRepositoryImpl` 用的是 `error(...)`（**抛异常**）而不是 `Result.failure(...)`
  ⇒ 一次"库未解锁 / 条目不存在"就能让 `saving` 永久 `true`，之后**每一次点保存都被
  开头的 `if (saving) return` 静默吞掉**：界面像保存了，实际一行没落库、一条 op 没入队，
  点同步自然只能得到"无改动"——与用户描述完全吻合。
  处置：`ItemDetailViewModel.updateItem` / `ItemsViewModel.createItem` /
  `AutofillSaveViewModel.saveNew`·`updateExisting` 四处复位移入 `finally`；
  `createItem` 另加 `catch (e: CancellationException) { throw e }` →
  `catch (e: Throwable) { Result.failure(e) }` 收口（原本逃逸会**炸 App**）。
  三个入口共用 `VaultixSync` tag ⇒ 保存链路在日志里是连续的一串。
- **验证（闸门）**：`ItemsViewModelSavingGateTest` 3 例（复位 / 第二次仍能打到仓储 / 闸门本身仍生效），
  三条变异反证全红（复位移回下一句 ✅、catch 收窄 ✅、删闸门 ✅）。
  `app` **724 例全绿**、`data:bitwarden` **124**、`data:repository` **352**，detekt 通过；
  **23 个门禁 + 20 个自测**全绿（顺带把 README / next-steps 里写错的「24 个」改为 23）。
- **CI 实证（2026-11-16）**：`58e3440b` → `Android CI (Debug)` 43829870101 / `AI docs` 43829870031 全绿；
  `c1f3cc54` → `Android CI (Debug)` 43834201528（`Lint/test diagnostics/debug build` +
  `Sandbox unit tests` 两 job）/ `AI docs` 43834201510 全绿。preview 资产 **`Vaultix-c1f3cc5.apk`**
  （35.85 MB，`v0.13.4-dev-c1f3cc5 · Preview`）。
- **下一步（需要用户配合）**：装新包 `Vaultix-c1f3cc5.apk` 复现一次，回传日志。这次一定能看到
  `flushPending 队列为空（无待推送 op）⇒ 本次同步只做拉取，不会上传任何改动`
  或 `flushPending 开始：待推送 N 条（UPDATE=1）` + `flushPending op=UPDATE ... code=... body=...`；
  保存那一刻还会先出现 `写路径收口：updateItem 已返回（成功）· <id>` 或 `写路径中断：…`。
  这几句直接判定故障在**写路径**还是**推送路径**，不必再猜。

> 上一轮（#296 HTTP 422 静默弃单）已按 8.9 §6 迁出：
> [archive/README-状态块-2026-11-16-296.md](./archive/README-状态块-2026-11-16-296.md)。
> 再上一轮（#295 编辑条目用错钥匙）见 [archive/README-状态块-2026-11-16-295.md](./archive/README-状态块-2026-11-16-295.md)。

---
## 🕐 更早（2026-11-15 · v0.13.4 正式发布）

> **一句话**：真机取证 → 修复 → 正式发版闭环，连发 v0.13.3 / v0.13.4。

- **通行密钥**：单行 PEM 私钥解析修复（按 BEGIN/END 标记剥离，不再依赖换行）+ 诊断串带
  输入形态指纹；A 库私钥自始完好，**旧库无需迁移、无需重注册**。#293 凭据 ID 保真一并进正式版。
- **判据**：`TYPE_NO_CREDENTIAL` 先查系统「首选凭据服务」开关（覆盖安装会重置），
  不是 App 解析失败。见 [8.5 通行密钥](./conventions/8.5-通行密钥.md)。
- **省电 / UI**：TOTP 页后台挂起每秒时钟；查看层锁页重排为**密码库主体**
  （放大库徽标、文案砍到一句、主色 CTA）。两者均在 v0.13.4。
- **发布**：v0.13.4 已置 Latest（run 43705947147，签名 7e6c8c07…）；
  说明见 [v0.13.4 发布说明](../Docs/release-notes/v0.13.4.md)。
- **验证**：CI 1866 例单测 0 失败；TOTP 生命周期与 dev 包全链路真机复验通过。

---
## 🕐 更早状态（2026-11-01 ~ 2026-11-09）

> 已按日期拆成 4 篇（2026-11-09 二次拆分，原文 481 行 / 37KB 越 §7.1 上限）：
> [`STATUS-ARCHIVE.md`](./STATUS-ARCHIVE.md)（11-09）·
> [`STATUS-ARCHIVE-2.md`](./STATUS-ARCHIVE-2.md)（11-06 ~ 11-08）·
> [`STATUS-ARCHIVE-3.md`](./STATUS-ARCHIVE-3.md)（11-04 ~ 11-05）·
> [`STATUS-ARCHIVE-4.md`](./STATUS-ARCHIVE-4.md)（11-01 ~ 11-03）。
> 本节只留指针 —— README 是**入口**，只该承载"最新 + 上一轮"。

---


## 目录结构（索引 + 分篇，**按需只开一篇**）

| 路径 | 是什么 | 规模 |
|---|---|---|
| `README.md` | 本文件：接力入口（只承载**最新 + 上一轮**状态） | 172 行 / 15.3KB |
| `STATUS-ARCHIVE.md` 等 4 篇 | **状态归档**（倒序，新块在最上面）：`-`（11-09）· `-2`（11-06~11-08）· `-3`（11-04~11-05）· `-4`（11-01~11-03）。2026-11-09 二次拆分：单篇曾 481 行 / 37KB 越 8.9 §7.1 上限 | 各 8~11KB |
| `MEMORY.md` | 项目长期笔记（定位 / 架构 / 里程碑 / 技术栈硬约束 / 协议与来源 / 发布签名） | **23KB**（2026-11-01 从 109KB 瘦身：§9 历史状态已归档） |
| `MEMORY.md §8` | **索引**：写代码前的长期约定 → 正文在 ↓ | — |
| `conventions/` | 约定正文 11 篇：`8.1-自动填充` `8.2-锁与解锁` `8.3-M2KDBX` `8.4-UI·观感` `8.5-通行密钥` `8.6-工程质量` `8.7-环境` `8.8-M3Expressive-采纳范围与顺序` `8.9-文档分篇` **`8.10-架构与目录地图`** `8.11-七类不报错的错` | 各 2~24KB；**8.4 / 8.6 已拆 `-partN`**（主篇保留速查清单） |
| `ISSUES.md` | **索引**：坑的「分类 → 主篇索引」（最新 **#256**；#255 自动填充篇，#249 / #254 / #256 界面篇）。⚠️ 逐条摘要**与正文重复**，已按 8.9 §7.4 移出 —— 查某条：点分类 → 主篇「章节明细」 | **8.4KB**（原 84KB） |
| `issues/` | 坑的**正文**：7 个分类 = 7 个索引 + **41 个分篇**（`-partN`）。`## NN.` 锚点原样保留，代码注释里的 `#NN` 仍能 grep 到 | 每篇 ≤22KB |
| `decisions/` | **逻辑定稿**（用户拍板的方向，非根因、非实现）：`快速解锁房子化-两级钥匙层级-定稿.md`（**快速解锁现行真源**，2026-09-28 起修订前两篇）、`库选择与快速解锁-逻辑定稿.md`、`快速解锁能力级重构-定稿.md`、`设置页信息架构-定稿.md`、`通行密钥UV豁免-定稿.md`、`条目页-M3-视觉方案.md`（**P0 已实施，P1/P2 待做**） | — |
| `SESSION-YYYY-MM-DD.md` | 逐轮工作日志（append-only）。**2026-09 的 17 篇已按时间归档**到 `archive/sessions-2026-09/`（不拆内容 —— 历史日志没人整读，拆开只会让文件数翻倍） | 21 篇 ≈320KB |
| `archive/` | **归档区**（只挪不删，均为历史，**不要当现状读**）：`MEMORY-9-历史状态-2026-09至11.md`、`README-状态块-2026-09至10.md`、`sessions-2026-09/` | ≈1MB |
| `tools/` | **本地自检脚本**（不需 Android SDK，`python3` 直接跑）。覆盖「编译器才能发现、detekt 查不到」的缝：<br>`check_compile_smells.py` = 图标导入 / 顶层常量顺序 / `R.string` 悬空引用 / **重复声明**（第四个检查，见 #103；带 `--detekt-probe` 两级探针）；<br>`check_signature_types.py` = 签名里的类型名是否存在；<br>`check_import_packages.py` = `import` 的包路径对不对（#101 / #104）；<br>`check_experimental_optin.py` = 实验性 API 有没有 `@OptIn`（#101.2，带 `--selftest`）；<br>`check_issues_index.py` = **`ISSUES.md` 索引与分篇正文的一致**（#160：曾漂四次；查编号集合 / 题注数字 / 总表条数列三处相等，配套 `tests/selftest_issues_index.py` **6 例正反用例**）；<br>`check_orphan_strings.py` = `strings.xml` 里**零引用**的字符串（#126；⚠️ 是**报告器不是删除器** —— 「被搬走的留，被取代的删」需人判读；`--gate` 用基线做**只减不增**约束）；<br>`check_state_flattening.py` = **多态状态（sealed ≥3 分支）有没有被压成二值开关**（#124；`Switch(checked = x is S.On)` 会把 `Partial` 与 `Off` 合并 —— `is On` 是合法 Kotlin，类型检查与 detekt **双双查不出**）；<br>`check_orphan_state.py` = **Compose 状态变量是不是「只写不读」**（2026-09-30 真机实录：`var showX by remember…` + `onClick = { showX = true }` 都写了、**漏了 `if (showX) { XDialog() }`** ⇒ 点那行毫无反应；⚠️ 这一形状**五道门禁全盲**：Kotlin 对 `by` 委托的**局部**变量不报 unused、detekt 不查局部委托、孤儿串看不见。判据=「标识符只出现在声明与赋值左侧」；**零容忍**（无基线）。配套自测 `tests/selftest_orphan_state.py`）；<br>`fetch_release_asset.sh` = **分块断点续传下载 release 资产**（沙箱整文件下载不可靠，见 #105；自带 `zipfile` 完整性校验） |
| `tools/tests/` | **门禁自己的自测**（改探针前必跑）：<br>`selftest_duplicate_declarations.py` = 7 个正反用例；<br>`selftest_import_packages.py` = **端到端**（真把 import 改错、跑脚本、看退出码、还原）；<br>`selftest_cross_file_private.py` = **端到端**（真把 `internal` 改回 `private`、验证报出两个调用方、还原）；<br>`selftest_orphan_strings.py` = 7 个用例（含**前缀撞名** `a_head`/`a_header` 的经典误判，以及"零引用被误删只在运行时才炸"的各种引用形态）；<br>`selftest_state_flattening.py` = 9 个正反用例。⚠️ 含**全限定名**形态（`Outer.Cap.On`）—— 少了它，探针会在真实代码上静默失效（见该文件开头） |

> ⚠️ **各道脚本的扫描范围都是 `app/ core/ data/ domain/`**（排除 `reference/` 对照源码与 `build/`）。
> 2026-09-16 之前只扫 `app/src`，**同一个盲区造成两次 CI 红**
> （domain 的重复声明、data 的错 import 都扫不到）。**改门禁时先确认它扫的是不是全部 —— 看报的数字。** — |

> **为什么这么拆**：原先 `.ai/ISSUES.md` 139KB、`.ai/MEMORY.md` 43KB ——
> 全读会把真正需要的上下文挤掉，定位只能靠 grep。现在入口稳定、**正文按需读一篇**。
> 仓库里 115 处「见 `.ai/ISSUES.md` #NN」的引用依然有效：先查索引表，再跳分篇。

## 接力流程

1. **先读本文件顶部「🕐 最新状态」** —— 当前版本 / 上一轮 / 真机验收欠账 / 发布结论的
   **唯一真源**（倒序）。`MEMORY.md` §9 也把「当前状态」指回这里，
   **别从中间的历史状态块起手**（2026-11-14 校正：旧版这里写「读 MEMORY.md 拿当前状态」，
   与 `MEMORY.md` 自己的声明矛盾）。
1b. 再读 `MEMORY.md` —— 项目定位、架构、硬约束与长期约定（**不当当前状态读**）；
   待办看 `Docs/progress/next-steps.md` 顶部，`current-status.md` 已冻结存档。
2. **要动「快速解锁」→ 先读 `decisions/快速解锁房子化-两级钥匙层级-定稿.md`**
   （2026-09-28 起的现行真源，**别重新论证**；施工进度 = `Docs/progress/quick-unlock-house-rework.md`，
   **批次 1-4 已收工（含批次 3.5 真机反馈修复），下一会话从
   `Docs/progress/house-rework-batch4-handoff.md` 进；失效矩阵决策见定稿 §6.1**）
   「库选择 / 默认库」→ 仍读 `decisions/库选择与快速解锁-逻辑定稿.md`（其快速解锁章节已被房子化定稿修订）
2b. **要动「设置页」→ 先读 `decisions/设置页信息架构-定稿.md`**
   （7 组 → 5 组的目标结构 + 全量文案改动表，**别重新设计分组**；§6 是开工步骤）
2c. **要动「Material 3 / 观感 / 动效」→ 先读 `conventions/8.8-M3Expressive-采纳范围与顺序.md`**
   （哪些该借、哪些明令不借、按什么顺序做；含 2026 的设计口径与本项目实测）
2d. **要动「KDBX 网盘同步（OneDrive / WebDAV）」→ 先读
   [`Docs/progress/cloud-sync-plan.md`](../Docs/progress/cloud-sync-plan.md)**
   （★ 前置是 KDBX 阶段 B 写回，勿跳过；冲突策略有三个方案待拍板，**别自行决定**）
2e. **要动「性能」→ 读 [`Docs/progress/perf-plan.md`](../Docs/progress/perf-plan.md)**
2f. **要做「原生化 / Rust / Go 重写」→ 先读
   [`Docs/progress/native-rewrite-eval.md`](../Docs/progress/native-rewrite-eval.md)**
   （已给结论：**现在别动**；唯一量化热点是 KDF；要引就引 Rust 不要 Go；**先量真机**）

3. **按本次要动的模块**，只开 `conventions/` 里对应的一篇（例：动填充 → `8.1-自动填充`）
4. **按本次要碰的模块**，只开 `issues/` 里对应的一篇（例：动填充 → `02-自动填充`）
5. 读 `Docs/progress/next-steps.md`（**待办唯一真源**；已完成的历史已按时间段归档，
   见文件末尾的归档索引，**勿通读**）与 `current-status.md`
6. 完成后**更新上述文件**：新坑 → 写进对应 `issues/*.md` 并在 `ISSUES.md` 索引表补一行；
   新约定 → 写进对应 `conventions/*.md`。**别把正文堆回索引。**

## 两类记录的分工（2026-09-13 明确，**别混**）

| | `.ai/SESSION-YYYY-MM-DD.md`（本目录，入库） | `.workbuddy/memory/YYYY-MM-DD.md`（本地，未入库） |
|---|---|---|
| 定位 | **面向接力者的纪要** | **工作流水**（详细过程） |
| 写什么 | 这轮改了什么 · 为什么 · 怎么验证的 · 结论 | 命令、中间尝试、失败路径、逐条取证过程 |
| 长度 | 精简 | 可以很长 |
| 谁看 | 任何接手的人（含新克隆仓库的人） | 只有本机 AI |

**收工规则（单向，从 2026-09-13 起）**：
1. 把当天本地流水里**对接力有用的部分**整理成一段，**追加**进 `.ai/SESSION-YYYY-MM-DD.md`；
2. 然后把**本地当天的日志删掉**（它只是过程草稿，次日不再保留）。

> ⚠️ **历史未回溯合并**：本地流水现存 **2026-09-08 / 09-09 / 09-10 / 09-11 / 09-14 /
> 09-15 / 09-16 / 09-17** 八天（约 320KB），与 `.ai/SESSION-<同日>.md` **内容并不相同、
> 各有一方独有**（不是副本，是两套独立写下的流水）⇒ **不能盲删**。
> **查结论以 `.ai/SESSION-*` 为准，查过程细节参考本地流水。**
> ⇒ 清理步骤见 **[`Docs/progress/docs-slimming.md`](../Docs/progress/docs-slimming.md) §2.2**：
> 先逐份比对、把独有内容**追加**进 `.ai/SESSION-*`，确认无遗漏后再删；**一次只处理一天**。

## ⚠️ 读记忆的正确姿势

**被推翻的旧结论不会删除，而是就地标注 —— 看到 `~~删除线~~` / `🔴 已被推翻` /
`~~xx~~` 标记时，务必读完整标注再决定要不要照做。**

真实案例：`issues/` 里 #35 曾判定「通行密钥浏览器流程应回传**空占位符**」，
该结论于 2026-09-12 被 **#43** 推翻（回传空占位符会让 GitHub 注册直接失败）。
**只读 #35 不读 #43，就会重新引入同一个 bug。**

两条纪律：

| 纪律 | 说明 |
|---|---|
| **旧结论就地加勘误指针，不删原文** | 保留错误推演过程有价值（能看出当时怎么想歪的），但**必须在标题处**给出推翻标记与正确去向 |
| **推翻类记录必须写「错在哪」** | 否则接力者只知结论变了、不知**为什么**变，容易又绕回去 |

## 另有一条工作纪律（2026-09-13 补）

**遇「用户看得到、我复现不了」的问题，先取实证再动代码**，且**先对齐复现路径**
（哪个手势 / 哪个入口 / 哪台设备）。
本轮为此付出代价：「手势返回画面缩小」改了三次转场都没中，因为一直在用**按键返回**复现
**手势**返回的问题；「填充反复解锁」白改一轮，因为只抓了自己的日志 tag，
看不到系统框架侧说「认证结果被丢弃」。详见 `issues/06-界面与交互.md` #81 与
`issues/02-自动填充.md` #82。
