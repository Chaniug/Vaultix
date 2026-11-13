# Vaultix 项目长期笔记

> **接力顺序（先读这四份，再动手）**：
> 本文件（约定 / 硬约束 / 决策）→ `.ai/ISSUES.md`（坑：现象-根因-解法，最新 **#288**）→
> `Docs/progress/next-steps.md`（待办唯一真源；历史按时间段归档，见文末索引）→ `Docs/progress/current-status.md`（进度快照）
> → `.ai/SESSION-YYYY-MM-DD.md`（逐轮流水，需要细节才翻）。
>
> 最后更新：2026-11-13（**#288 坚果云 WebDAV 403「登录不上去」**（错误分类 / 连接自检 /
> 凭据隔离）：真机「测试连接 → 账号或密码不正确（HTTP 403）」。匿名 / 假凭据差分实测
> 12/12 全 401 ⇒ **403 是认证已通过后的服务端拒绝**，官方 FAQ 归因为微信二次验证 /
> 访问频率 / 应用密码被撤销 / 目录权限；旧实现把 401/403 合并成「密码错」，
> 用户只会无限重输。修法：`testConnection()` 主探 `PROPFIND Depth: 0`（与列目录同源、
> 405/501 才降级 HEAD、404 不再假报成功）、401/403 分流且在 **`errorMessage()` 共同出口**
> 也分流、表单补「应用密码 / 关二次验证」提示、新增 `webDavProductionClient()` 让
> **两个生产构造点**都用清掉 Bitwarden 拦截器的专用 client。
> ⚠️ 长期教训：**401 与 403 是两类用户动作，合并成一句话等于把用户锁死在错误解法上**；
> **自检与使用必须走同一个协议方法**（HEAD 探通不代表 PROPFIND 能列目录）；
> **错误分类要放在共同出口**，只在写入路径改会造出「同一状态码两套解释」；
> **上一轮静态排查的「最严重项」不一定是下一轮真机反馈的根因**（本轮已证伪 Bearer
> 覆盖是 403 的直接原因，但 P0 本身仍真实存在，顺手修掉）；
> **Kotlin 块注释可嵌套**，KDoc 里写出块注释起始符会把后面的代码整段吞进注释。
> 详情见 `issues/06-界面与交互-part26.md`；
> 上一轮 **#287 改完 KDBX 条目立刻返回等于没保存**（协程取消 / 写回持久性）：
> 用户补充「点保存后停 5 秒才生效、大部分人点完就返回」⇒ **判据第三次反转** —— 不是 #286
> 的同步闸门，而是写回协程跑在 `viewModelScope` 里被页面返回取消在 `Kdbx.verifyWrite()`
> 的 KDF 1~5 s 窗口内：`mutateLocked` 已换内存会话（本地立刻可见），脏缓存 /
> `markLocalEdited` / `enqueue` 全没跑 ⇒ #286 的双真源判据两个都干净 ⇒ 拉云端旧版覆盖。
> 修法四道防线（`serialWrite` 收口 `NonCancellable`、脏缓存提到自检前、`cacheRestore`
> 失败恢复、UI 层 `withContext(NonCancellable)`）。⚠️ 长期教训：
> **「先保证写回跑完，再谈同步闸门别覆盖」是串联不是并联** —— 上一轮的完成口径会被
> 下一轮真机反馈反转，越是「局部全绿」越要回查**调用链更早的那一段**；
> **数据落盘是原子动作，不该挂在页面生命周期上**（`deleteItem` 11-08 已中过同一坑，
> 防线要放共享咽喉而非逐个 ViewModel 打补丁）；**取消不是失败**（`CancellationException`
> 只重抛，取消态下跑回滚链会半途而废）；**`launch(NonCancellable)` 已 deprecated**，
> 统一 `launch { withContext(NonCancellable) { } }`；**`functionBody()` 要从签名起点截**
> （表达式体 `= withContext(...) { }` 的第一个 `{` 属于 lambda，从那里截会漏判）；
> **行为测试必须做反证**（撤掉 `NonCancellable` 后用例必须变红）。
> 详情见 `issues/06-界面与交互-part25.md`；
> 上一轮 **#286 OneDrive KDBX 改动被刷新覆盖**（数据完整性 / 同步状态机）：
> `hasUnsyncedLocalEdits()` 双真源唯一判据 + 下载前后两道刷新闸门 + `readRemoteCurrent`
> 回写前现查脏标记 + 冷启动 / 解锁补传，含「启动扫一次在锁态空转」的第二轮补正；
> 核心纪律 **DB 状态是展示快照、缓存脏标记才是事实**，详见 `issues/06-界面与交互-part24.md`；
> 上一轮 **#285 六组 UI / 主题 / KDBX 反馈**：网盘账号卡精修、域名分组
> 整体化、详情页长标题单行、「+」剪影卡改 M3 fixed accent roles、主题默认色与动态取色
> 修复、KDBX 顶栏统一显示 `KDBX`，六组全改生产代码。反编译取证两个主题根因：静态色板
> 一个 fixed role 都没传（关动态取色回退基线紫 / 开则壁纸色，两档跳变）+ `dynamicColor`
> 默认 `true` 绕过品牌浅色板；补齐 12 个 fixed 角色、三处默认值统一 `false`、
> `supportsDynamicColor()` 单一真源让低版本开关禁用 + 说明。**反转 #282 分组头成块**
> 与 **#282/#283 剪影卡 `xxxContainer` + alpha 分档**两套判据。
> ⚠️ 三条长期教训：**判据会随用户反馈反转，改生产代码前先确认旧门禁没钉住旧写法**、
> **色板「按规范取名」不等于颜色合规（fixed 色值也要量化验饱和度 / 对比度）**、
> **判「有没有某个参数」要走签名 helper 而不是 `functionBody()`**。
> 详情见 `issues/06-界面与交互-part23.md`；
> 上一轮 **#284 取消按钮规格统一 + KDBX 通行密钥排查**：用户指出设置页多处
> 「取消」大小 / 形态与正常按钮不一致 ⇒ 解包 material3 1.5.0-alpha16 反编译取证
> （`AlertDialogFlowRow` 就是 `FlowRow`、Dialog 全家零 `IntrinsicSize`），**推翻 #273 / #283
> 的 intrinsic 根因**：`width(IntrinsicSize.Min)` 把宽度钉死成最小固有宽度，#283 的
> `widthIn(min=MinWidth)` 只是把小方块托到 64dp，而 `DialogShell` 家族是原生 wrap-content
> ⇒ 两套规格并排。删固定宽度、只留宽度下限与显式 `contentPadding`，**反转 #283 规则 4**
> （禁止 `IntrinsicSize` / `width` / `height`），#282「取消必须有边框」不动；7 处旧注释订正。
> 另：KDBX **能**存通行密钥（codec → writer 加密字段 → mapper 读回、仓储已放行），
> 管理页结构完整，但 `toCredential()` 五项必填而 `SavePasskeyDialog` 不产出 `userHandle` /
> 允许 `userName` 留空 ⇒ **写入成功、重开库静默丢失**；修法 `WebAuthn.randomUserHandle()`
> 仅在没有真实 `user.id` 时兜底 + 补齐元数据 + 删诱导复用半成品的死代码，三层测试闭环。
> ⚠️ 两条长期教训：**观感问题也要先取证再改宽度**、**源码门禁要扫构造块而非整文件**。
> 详情见 `issues/06-界面与交互-part22.md`；
> 上一轮 **#283 深色模式光照重构 + 三组 M3 观感整改**：按用户要求先取证
> Material Dark theme / Apple Liquid Glass / 玻璃拟态三路深色规范，再把「白高光 + 黑内阴影
> + 8dp 黑外影全开满」收敛为 `glassLighting()` **一处定义的三档**（收敛**强度**不改色相，
> #281 边缘厚度与 #282 去细边线一个不动）；取消按钮补 M3 `MinWidth` 与显式 padding
> （22 个文件 60+ 调用点全走共用组件）；**反转 #282 分组计数胶囊判据**为低调纯文字。
> ⚠️ 两条长期教训：**门禁代码自己也要过门禁**（detekt 复杂度撞两次，靠拆小函数解决）、
> **常量类型变更必须全链路同步**（`RimLineColor: Color → RIM_LINE_ALPHA: Float` 曾留下
> 编译不过的半成品）。详情见 `issues/06-界面与交互-part21.md`；
> 上一轮 **#282 第五批界面 / 设置 / 条目 UI 整改**：五组反馈全改生产代码，
> **反转三处旧判据** —— ① 用户最新反馈覆盖 #266「裸云 = 地点」的**形状**判据，KDBX 云端库统一
> `CloudDone`，但本地 KDBX 不画云 / KDBX 不产出条目级同步态 / 三态枚举与读屏文案**一个不动**
> ⇒ **形状统一 ≠ 语义合并**；② 剪切板「取消」要边框，与 #275 规则 1 冲突 ⇒ **反转门禁**为
> 「单选项对话框退出按钮必须有边框」，不是偷偷改代码让旧规则继续绿（**判据不是誓言**，
> 反转要写明，别把旧结论留在 KDoc 里当现行判据）；③ #281 填充下界 `0.45→0.42` 是调参留痕，
> 边缘厚度判据（模糊 / rim / 内阴影）不动。底栏去细边线用「**中心更透、边缘更立**」：
> 删两档共有的 1dp `style.stroke` 整圈描边，边界交给 3dp 内阴影 + 镜面 rim，不回退 #281。
> 条目列表 M3 质感**只在绘制 / 布局阶段**动（分组头成块 + 计数 tonal 胶囊、副标题恒定一行且
> 类型徽标对齐详情页 `TypeBadge(NEUTRAL)`、行尾四枚点缀整组**一个**胶囊 + `hasRowTrailing()`
> 早退），零新增 State / 动画 / 层级；**标题补 `maxLines = 1` 是唯一显示行为变更**（密码页
> 原先折行、其余三页早已单行），单列第 5 条门禁钉住 —— 文档别把「本轮新增」写成「早就如此」。
> ⚠️ 两个坑：`replace_all` 删 Spacer 会连前导 `\n` 一起吞，
> 让相邻 composable 挤到同一行；`check_orphan_state.py` / `check_orphan_strings.py` 用 argparse，
> **不吃位置参数**，批量循环统一传根会 `rc=2`（是用错命令不是代码红）。
> 详见 `issues/06-界面与交互-part20.md`）；2026-11-11（**底栏材质分档 #281**：常态磨砂 + 液态玻璃质感回提；判据「玻璃感来自
> 对比不是透明度」、`DockMaterial` 两档共用采样链路、源层不再乘开关、API<31 仍降级实色，
> 玻璃单测 38 条，详见 `issues/06-界面与交互-part19.md`）；2026-11-11（**液态玻璃第六轮 #280**：修 `uGlow` 高斯量纲、表面流光 / 通透厚度搬回绘制层
> 覆盖 31+ 全档、SDF 梯度折射 + 按压透镜、真跟手与非对称时序；玻璃单测 31 条，详见
> `issues/06-界面与交互-part18.md`）；2026-11-11（**「不需要修」判定方案**：52 条分级登记 + 三问判定法 + 触发转修条件 +
> 自动门禁；订正日志门面 P0→P1、分支≠buildType；登记表见
> `Docs/progress/audit/evidence/no-fix-entries-2026-11-11.md`）；2026-11-11（**项目整体排查**：43 个坐标中 29 项已最新；`composeBom` / `material3` / `markdownRenderer` 三项禁单独升；「依赖解析结果 > 声明」实证；报告见 `Docs/progress/audit/project-audit-2026-11-11.md`）；2026-11-02（**v0.11.0 发版**：JSON→KDBX 做到逐字段无损 #219/#223/#224；发布流水线嵌套 `cd` 回归 #226 —— 报错文案与真实原因无关，查这类红要从第一条 `##[error]` 往上读）；2026-11-01（**#223 键名自造 ⇒ 与 KeePassXC 互不认：`card_*` / `identity_*` / `KP2A_URL_n` 改为照抄生态键名，旧 `VPX_*` 只读兼容**；此前 **#221 预览包 0 附件：`files:` 被粘进 `body:`，YAML 合法但键不存在**；此前 **#220 测试接缝赋值时机修复：CI 那条偶发红其实是「注入从未生效」**；此前 **JSON→KDBX 无损转换补齐 #219：卡/身份/SSH/密码历史/文件夹 读写两侧成对接上**；更早 **预览包身份 #218（含 218.4：APK v2 签名块解析器 + 自检 + 变异测试）**；更早 **两层锁 #214 = 「从不」档界面门禁 `AutoViewGate` + 新档位 `OnSystemLock`**；更早 开关双重取反 ⇒ 恒等映射 #213；命中框 52×32→52×48 #212；锁语义澄清 + 开发者「保持不锁」#210 / 通行密钥 type 串 #211；§9 顶部才是真源）。**本文件已压缩重写**：逐轮叙事折叠进
> 第 9 节「当前状态」与第 10 节「历史轮次索引」，只留「不知道就会写错、且错了不报错」的内容。
>
> ⚠️ **2026-09-26 起 §9 顶部才是"最近几轮"的唯一真源**；§10 的索引止于 2026-09-13，
> 之后不再逐条续写（避免两处漂移）。


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
- **多库锁模型（2026-09-29 定稿）**：一栋房子，一扇门，N 个房间；任何「锁」只动自己那一层
  —— 门锁/房钥匙**全局**、房间/档位**每库**（D3 施工中）、多 KDBX 仍受 #60 内存约束。
  **动锁/解锁/信封相关代码前必读** [`decisions/多库锁模型-定稿.md`](./decisions/多库锁模型-定稿.md)

## 3. 里程碑
| 期 | 内容 | 状态 |
|---|---|---|
| M0 | 基础骨架 + `core:crypto` | ✅ DONE |
| M1 | **Bitwarden 同步** | ✅ 基本收官（等 824c432 三项最终确认） |
| M2 | KDBX 引擎 + 集成 | ✅ **阶段 A（只读）+ 阶段 B（写回）均已可用**：整库写回（`Kdbx.save`/`saveVia`，网盘同步真调用）+ **条目级编辑全部放开**（`KdbxItemRepository` 五动作、`ItemRepositoryImpl` 已分流；2026-10-13 通行密钥也放开）。2026-10-25 补上**写回基线**（#185）——「令牌对得上 ≠ 内容来源对得上」那条静默覆盖路径已两道防线封住；2026-10-26 把**内容指纹补成全字段**（#190）——否则那道防线在「改网址/改 TOTP/换文件夹」上恒失效。剩余：真机 W6 互操作验收 |
| M2-a | 自动填充服务（提前启动） | 🔄 主体已落地；继续按真机反馈打磨 |
| M3 | 平台集成（Autofill / 安全中心） | ⬜ autofill 已并入 M2-a；安全中心待做 |
| M4 / M5 | 发布准备 / 1.0 | ⬜ TODO |

## 4. 技术栈与硬约束
Gradle 9.8.2 / AGP 9.5.0 / Kotlin 2.4.21 / KSP 2.3.13 / Hilt 2.60.1 / compileSdk 37 / JDK 17

> ⚠️ **2026-11-11 按 `libs.versions.toml` + wrapper 订正**：旧口径
> `9.5.1 / 9.3.2 / 2.4.10 / 2.3.11` 曾长期留在本节，是整体排查报告 §1.4 版本漂移的**第 5 处**
> （在 AI 必读文档里，危害比版本目录注释更大）。
> **版本类事实只信版本目录与 wrapper，不信记忆正文**；正文只记约束与 why。

- **Hilt 与 Kotlin 强绑定**：Kotlin 2.4.x → Hilt **2.60.1**；AGP 9 要求 Hilt ≥ 2.59
- AGP 9 **禁止** apply `kotlin-android`（内置 Kotlin 冲突）
- version catalog 别名避免 `kotlin-` 前缀（撞内置 `kotlin {}` DSL）；KGP 用别名 `kgp`
- 类型安全项目访问器需 `enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")`
- 本机 SDK 在 `C:\AndroidSDK`，**无 android-36** ⇒ compileSdk **必须 ≥ 37**
- **daemon 堆各 4g**（`org.gradle.jvmargs` 与 `kotlin.daemon.jvmargs`），**禁止回退 2g**
  （含多 Compose 模块 + Bitwarden 全载荷映射，分析大型 composable 时易 OOM）
- **detekt `2.0.0-alpha.6`**（1.23.x 不可用）。⚠️ 官方兼容表写 Kotlin 2.4.10 / AGP 9.3.1 /
  Gradle 9.6.1，**已过期**：本项目 2.4.21 / 9.5.0 / 9.8.2 下 CI `detekt` 绿。
  升 Gradle 9.9.1 前先单独试 `detekt --configuration-cache`，别整条 CI 一起推
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
  | Bitwarden Android | `D:\Vaultix-refs\bitwarden-android`（仓外，浅克隆） | **功能层真源** |
  | Keyguard | `D:\Vaultix-refs\keyguard-app`（仓外，浅克隆） | **仅 UI 交互参考**，含 `androidLibAutofill/` |
  | kotpass | `D:\Vaultix-refs\kotpass`（仓外，浅克隆） | KDBX 引擎上游源码；**依赖版本以 `libs.versions.toml` 为准** |
- 查参考实现**先读本地**，不必联网；刷新方式与当前 HEAD 见
  [`conventions/8.10`](conventions/8.10-架构与目录地图.md) §6.1 与 `issues/01` §42。
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

> ⚠️ **2026-09-13 起「大文件」统一改为「索引 + 分篇」**：入口保持稳定路径，正文按主题分篇、
> **按需只开一篇**（别全读 —— 全读会把真正需要的上下文挤掉）。仓库里 115 处
> 「见 `.ai/ISSUES.md` #NN」的引用仍然有效：先看索引的「编号 → 分篇」表，再跳分篇。

- `Docs/progress/`：`environment` / `current-status` / `next-steps`（**待办唯一真源**）/
  `decisions` / `main-shell-migration` / **`perf-plan.md`（性能专项）** / `audit/`
- `.ai/`：
  - `MEMORY.md` —— 本文件（接力起手式：定位/架构/约会速查索引入口）
  - **`conventions/`** —— §8「长期约定速查」的正文分篇（`8.1-自动填充` … `8.10-架构与目录地图`）
  - **`decisions/`** —— **逻辑定稿**（用户已拍板的方向，**别重新论证**）：
    `快速解锁房子化-两级钥匙层级-定稿.md`（快速解锁现行真源，2026-09-28 起修订前两篇）、
    `设置页信息架构-定稿.md`、`通行密钥UV豁免-定稿.md`
  - `ISSUES.md` —— **索引**（编号 → 分篇）
  - **`issues/`** —— 坑的正文分篇（`01-构建与环境` … `07-数据与同步`，**含推翻链，接力必读**）
  - `SESSION-*.md` —— 会话日志（逐轮流水，append-only）
  - `README.md` —— 索引
  - **`HANDOFF-*.md` —— 接力入口（新会话先读这个）**：
    `HANDOFF-2026-10-02.md`（双库审计批次 A 收口 · **剩余任务四类清单 + 三条纪律**）

---

## 8. 长期约定速查（写代码前必读）

> ⚠️ 2026-09-13 起本节的正文**按主题拆到 `conventions/` 分篇** —— **只读你这次要动的那一篇**，
> 不要全读（原本节约 250 行，全读会挤掉真正需要的上下文）。

| 分篇 | 主题 | 条目 |
|---|---|---|
| [8.1 自动填充](./conventions/8.1-自动填充.md) | 自动填充 | 16 |
| [8.2 锁与解锁](./conventions/8.2-锁与解锁.md) | 锁与解锁 | 6 |
| [8.3 M2KDBX](./conventions/8.3-M2KDBX.md) | M2KDBX | 12 |
| [8.4 UI·观感](./conventions/8.4-UI·观感.md) | UI·观感（含**三张尺寸表分工** + **中文文案哨兵** + **对话框动作槽的 intrinsic 坑 / 右侧固定宽度 = 左侧固定损失**） | 15 |
| [8.5 通行密钥](./conventions/8.5-通行密钥.md) | 通行密钥 | 12 |
| [8.6 工程质量](./conventions/8.6-工程质量.md) | 工程质量 | 12 |
| [8.7 环境](./conventions/8.7-环境.md) | 环境（含**沙箱内真编译 + 真跑单测**，`--all` 可批量巡检全仓，无 SDK 也能跑） | 10 |
| [8.8 M3Expressive](./conventions/8.8-M3Expressive-采纳范围与顺序.md) | M3E（2026）采纳范围与顺序 | — |
| [8.9 文档分篇](./conventions/8.9-文档分篇.md) | 文档分篇（防超长上下文） | 5 |
| [8.10 架构与目录地图](./conventions/8.10-架构与目录地图.md) | **东西在哪 + 该信哪份文档**（接力地图） | — |
| [8.11 七类不报错的错](./conventions/8.11-七类不报错的错.md) | **门禁全绿但内容错**的七种形态 + 各自该配的检查 | 7 |


## 9. 当前状态与下一批（接力起手式）

> ⚠️ 2026-11-01 起本节**只留指针**（8.9 §2：入口文件只留指针 + 一句话摘要）。
>
> - **当前状态 / 下一批**：`.ai/README.md` 顶部「🕐 最新状态」（**唯一真源**，倒序）；
> - **逐轮细节**：`.ai/SESSION-YYYY-MM-DD.md`（按天一篇）；
> - **判据沉淀**：[8.11 七类「不报错的错」](./conventions/8.11-七类不报错的错.md)；
> - **项目整体排查（2026-11-11）**：[`Docs/progress/audit/project-audit-2026-11-11.md`](../Docs/progress/audit/project-audit-2026-11-11.md)
>   —— 依赖矩阵 / 规范符合性 / 稳定性与性能方案；**只出报告，未改生产代码**；
> - **坚果云 WebDAV 403 登录不上去（2026-11-13）**：[`#288` 接力记录](./issues/06-界面与交互-part26.md)
>   —— 403 不是密码错（认证已过、服务端拒绝），自检改 `PROPFIND Depth: 0`、
>   401/403 分流、表单补应用密码提示、WebDAV 专用 client 隔离 Bitwarden 拦截器
> - **改完 KDBX 条目立刻返回等于没保存（2026-11-12）**：[`#287` 接力记录](./issues/06-界面与交互-part25.md)
>   —— **不是 #286 的同步闸门，是更前面的写回持久性**；`viewModelScope` 被页面返回取消
>   在 `Kdbx.verifyWrite()` 的 KDF 几秒窗口里，会话已换而脏标记没落 ⇒ 双真源两个都干净。
>   核心纪律：**先保证写回跑完，再谈同步闸门别覆盖**（串联不是并联）；
>   **落盘是原子动作，防线放在共享咽喉 `serialWrite`，别逐个 ViewModel 补**；
>   **取消不是失败**（取消态跑回滚链必半途而废）；
>   `launch(NonCancellable)` 已 deprecated ⇒ 用 `withContext(NonCancellable)`；
>   源码门禁判表达式体函数要从**签名起点**配平括号；
> - **OneDrive KDBX 改动被刷新覆盖（2026-11-12）**：[`#286` 接力记录](./issues/06-界面与交互-part24.md)
>   —— **不是 UI 观感，是数据完整性**；两个叠加根因：① 刷新闸门只读 DB 状态，
>   而自动上传把 `PENDING_UPLOAD` 洗成 `SYNCING`/`FAILED`，`impliesLocalChanges` 不认
>   ⇒ 拉远端旧字节换会话并清掉脏缓存；② `enqueue` 只在保存那一刻调用，无补传入口。
>   核心纪律：**DB 状态是展示用的瞬时快照，缓存里的 `pendingLocalEdits` 才是
>   「本地改动还在不在」的事实**（与 11-07 `hasWork`/`sync` 同一条，判据只能有一份）；
>   **「改动不丢」与「自动收敛」是两件事** —— 闸门保前者、补传管后者；
>   **局部验证全绿 ≠ 链路闭环** —— `saveVia` 要内存会话，冷启动锁态的「启动扫一次」
>   全是空转，最后靠订阅解锁事件才接上；「下载前判一次」也覆盖不了下载那 1–10 s 的窗口，
>   旧 KDoc 自称覆盖是错的，要看语句顺序而不是注释。
> - **第五批界面 / 设置 / 条目 UI 整改（2026-11-12）**：[`#282` 接力记录](./issues/06-界面与交互-part20.md)
>   —— 五组用户反馈（底栏细边线 + 通透度、云图标、剪影配色、设置三处、条目 M3 质感）
>   **全改生产代码 + 反转三处旧判据**；核心两条：**形状统一 ≠ 语义合并**（云图标改形状、
>   三态枚举与读屏文案不动）、**判据不是誓言**（用户反馈覆盖旧结论要反转门禁并写明，
>   不是偷偷改代码让旧规则继续绿，更不是把旧结论留在 KDoc 里当现行判据）；
> - **深色模式光照重构（2026-11-12）**：[`#283` 接力记录](./issues/06-界面与交互-part21.md)
>   —— 用户要求「仔细看看优秀的项目是如何处理的」⇒ 先取证 Material Dark theme /
>   Apple Liquid Glass / 玻璃拟态三路规范，再收敛「白高光 + 黑内阴影 + 8dp 黑外影全开满」；
>   **深色下 elevation 靠表面色阶、不靠自定义 drop shadow**；玻璃受光边收敛**强度**而非色相
>   （改灰会丢方向感）；同类装饰若 `Frosted` / `Liquid` 两档共用，深色问题必然同时命中两档。
>   底栏「中心更透、边缘更立」、条目列表「只在绘制 / 布局阶段动、零新增 State」；
> - **底栏材质分档（2026-11-11 中午）**：[`#281` 接力记录](./issues/06-界面与交互-part19.md)
>   —— 「不开液态玻璃时平常状态也要通透磨砂」+ 液态玻璃质感回提；**判据：玻璃感来自
>   「透出内容」与「玻璃自身」的对比，不是越透越好**（11-09 起一路调淡调没了自我）；
>   `glassSourceActive` 只看系统能力、不乘开关，否则磨砂糊到空背景；**改了生产代码**；
> - **液态玻璃第六轮（2026-11-11）**：[`#280` 接力记录](./issues/06-界面与交互-part18.md)
>   —— 「通透 / 流光 / 折射」五处没接通的判据：高斯量纲 `w²`、表面层不能只放 API 33+、
>   SDF 梯度法线、跟手与零重组兼得、**改了生产代码**；
> - **⚠️ 排查 / 整改开工前必读**：[「不需要修」登记表（52 条）](../Docs/progress/audit/evidence/no-fix-entries-2026-11-11.md)
>   与[判定方案](../Docs/progress/audit/no-fix-register-2026-11-11.md) —— 产出的待修项先与登记 ID 比对，
>   命中即引用登记，不要重写理由、更不要重新列成待办（有 `check_no_fix_register.py` 门禁）；
> - **历史轮次状态块**（2026-09 ~ 2026-11，原 §9 正文）：
>   [`archive/MEMORY-9-历史状态-2026-09至11.md`](./archive/MEMORY-9-历史状态-2026-09至11.md)
>   —— **是历史，不要当现状读。**

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
| 身份全字段 | `VaultIdentity` **18** 字段 + overlay 写回（**不改 overlay「可编辑」就是 UI 假象**） | — |
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
| P0 解锁判据 | 「**存在**锁定的库」≠「**当前**库锁定」⇒ 候选全灭 + **无限解锁环** | `bc09995` |
| 布局三症 | 让位写在外层容器 padding ⇒ 筛选条压首条 / 上下不沉浸 / 搜索黑横幅 + 返回退桌面 | `bc09995` |
| 滑删反馈 | 手势**早就接了**、只是没有可见反馈 ⇒ 用户以为没做 | `bc09995` |
| KDBX 入口 | 添加库入口挂在**不可达路由**后面 ⇒ 集成交付了却用不到 | `bc09995` |
| 观感 | 全局输入框圆角 / 类型彩色徽标 / 详情图标头部 / 验证码字体 / 填充下拉实底 | `bc09995` |
| 记忆归档 | ISSUES #66~#69 + 四份接力文件同步 | `678be0d` |

### 2026-09-13（第五十轮 · 睡前 5 bug + 1 美化）
| 轮次 | 结论 | commit |
|---|---|---|
| 切页闪 | cross-fade **进出不同步** ⇒ 中间帧双向半透明，背景漏光 25%（深色 = 闪光） | `23c3629` |
| 倒计时条 | 进度条未随滚动折叠；对齐 Bastion `lerp(44.dp, 0.dp, frac)` + `tween(200)` | `23c3629` |
| 卡片按钮 / 多选 | 主操作 = 点卡片，低频 = MoreVert 菜单，批量 = 长按选择（Bastion 规格） | `23c3629` |
| 填充下拉 | RemoteViews 有硬边界（阴影/水波纹/网络全不支持）⇒ 图标 App 侧合成 Bitmap | `23c3629` |
| 解锁慢 | `viewModelScope.launch` **默认主线程**，Keystore / 密钥派生必须显式切 IO + 预热 cipher | `23c3629` |
| 美化 | 表单分区图标化 + 条目行尾收藏星标 | `23c3629` |

### 2026-09-13（第五十一轮 · 用户复盘后 6 条反馈）
| 轮次 | 结论 | commit |
|---|---|---|
| 验证码页沉浸 | 让位又写回了**滚动容器外的 padding** ⇒ 内容永远画不到顶栏区域（**#67 同根因残留**） | `d062792` |
| 手势分层 | 长按只进选择、**红底与位移只由手指驱动**；删除底改**常驻** + alpha，去条件式增删节点 | `d062792` |
| 填充下拉 M3 | RemoteViews 只能对齐**尺寸与静态配色**；M3 `ListItem` 规格：56dp / 40dp icon container / 16dp / 16·14sp；`drawable-night/` 给深色底板 | `d062792` |
| 指纹图标 | 长文案按钮 → **64dp 大号指纹图标**（primary 色）夹在密码框与灰色解锁按钮之间 | `d062792` |
| 云同步图标 | `PendingOp` 取 `op IN ('CREATE','UPDATE')` 作判据（**判定集合必须 = 展示集合**）；Flow 自动翻转；KDBX 恒空 | `d062792` |
| 下拉空隙 | 指示器默认贴容器顶 ⇒ 与让位后的首条之间是纯背景；自定义 indicator 加 `padding(top = topInset)` | `d062792` |

> **踩坑全表**（现象 → 根因 → 解法，编号 #1~**#126**，索引见 `.ai/ISSUES.md`）：
> ⚠️ **条数别信记忆** —— 2026-09-26 体检发现**7 篇里 6 篇的计数都是错的**（见 §9 本轮第 7 条）。
> ⚠️ 其中 **#35 ↔ #43 ↔ #39 是一条「推翻链」**（passkey clientDataJSON 的错判与更正），
> 接力时必须先看懂，不要只看旧条目就动手。

> ⚠️ **本索引止于 2026-09-13（第五十一轮）** —— 之后不再逐条续写，避免与 §9 两处漂移。
> **§9 才是"当前状态 + 最近几轮"的真源**，逐日细节在 `.ai/SESSION-YYYY-MM-DD.md`。
> 2026-09-14 起的轮次请直接看 §9 顶部与对应的 SESSION 文件；最近几轮是：
> - `SESSION-2026-10-19.md` —— **UI 设计审计 §4 五个施工项 P0→P2 全部落地**
>   （Preview 基建 / 底部让位真 bug / 主 CTA 收敛 / 空态与圆角收敛），
>   388 例 0 失败；新增判据 7 条（见 §9 顶部）。
> - `SESSION-2026-10-16.md` —— **A4 安全笔记类型往返翻转 + A6 标准字段内存保护丢失 +
>   W5 JSON⇄KDBX 双向转换落地**（`3fc8649` / `d50463f` / `6ae9bcd`，
>   CI `38894211052` + `38898302875` + `38927742868`；1142 例 0 失败；
>   新增坑 #169 / #170 / #171；A5 时间戳提案查证后驳回）。
> - `SESSION-2026-10-14.md` —— **KDBX × Bitwarden 边界审计五条修完**
>   （B6 通行密钥写回 / A1 收藏 / A2 文件夹 / A3 类型裁剪+硬闸 / B5 忘记 PIN #161），
>   CI `38495406982` 实证 1096 例 0 失败；新增坑 #164–#168。
> - `SESSION-2026-10-12.md` —— **CI 单测硬门禁修复**（`f470bc4`，两处测试缺陷，
>   未动产品代码；教训：一条测试可能因前半段异常从未执行到后半段断言）。
> - `SESSION-2026-10-05.md` 至 `SESSION-2026-10-07.md` —— **JSON⇄KDBX 无损互转 W1–W4**
>   （`KdbxFieldKeys` 单一真源 / `KdbxPasskeyCodec` 10 键 / TOTP 往返钉死 /
>   `VPX_` 工具区多 URL 降级）。**W5 转换入口已于 2026-10-16 落地**（见 §9 顶部与
>   next-steps），W6 真机互操作待真机。
> - `SESSION-2026-09-26.md` —— **设置页缺陷修复（开关「不猜状态」#120、快解三态开关 #121）
>   + 两个新探针（都坏过 #124/#126）+ detekt 阈值勘误 #123 + 七篇计数校正**。
>   **下一轮起点 = 开放 KDBX 单条编辑（R-6…R-9）**，该文件 §7 是完整交接。
