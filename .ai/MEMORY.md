# Vaultix 项目长期笔记

> **接力顺序（先读这四份，再动手）**：
> 本文件（约定 / 硬约束 / 决策）→ `.ai/ISSUES.md`（坑：现象-根因-解法，最新 **#213**）→
> `Docs/progress/next-steps.md`（待办，最新在顶部）→ `Docs/progress/current-status.md`（进度快照）
> → `.ai/SESSION-YYYY-MM-DD.md`（逐轮流水，需要细节才翻）。
>
> 最后更新：2026-10-30（**两层锁 #214 = 「从不」档界面门禁 `AutoViewGate` + 新档位 `OnSystemLock`**；更早 开关双重取反 ⇒ 恒等映射 #213；命中框 52×32→52×48 #212；锁语义澄清 + 开发者「保持不锁」#210 / 通行密钥 type 串 #211；§9 顶部才是真源）。**本文件已压缩重写**：逐轮叙事折叠进
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
| [8.4 UI·观感](./conventions/8.4-UI·观感.md) | UI·观感（含**三张尺寸表分工** + **中文文案哨兵**） | 14 |
| [8.5 通行密钥](./conventions/8.5-通行密钥.md) | 通行密钥 | 12 |
| [8.6 工程质量](./conventions/8.6-工程质量.md) | 工程质量 | 12 |
| [8.7 环境](./conventions/8.7-环境.md) | 环境（含**沙箱内真编译 + 真跑单测**，`--all` 可批量巡检全仓，无 SDK 也能跑） | 10 |
| [8.8 M3Expressive](./conventions/8.8-M3Expressive-采纳范围与顺序.md) | M3E（2026）采纳范围与顺序 | — |
| [8.9 文档分篇](./conventions/8.9-文档分篇.md) | 文档分篇（防超长上下文） | 5 |
| [8.10 架构与目录地图](./conventions/8.10-架构与目录地图.md) | **东西在哪 + 该信哪份文档**（接力地图） | — |

## 9. 当前状态与下一批（接力起手式）

> ⚡ **2026-10-31（最新）：一次指纹够所有库 —— 认证粒度对齐全局（#215）**
>
> 用户：「进入 app 时候验证指纹怎么需要两次，两个密码库？两个密码库不应该是同一个指纹吗，
> 指纹检验一次就够了啊」。**用户判断正确，这是 bug**，而且是 #214 的直接后续。
>
> | 环节 | 行为 | 后果 |
> |---|---|---|
> | `AutoViewGate` 熄屏 | 给**每个**已解锁的「从不」库各打一个 `automatic` 标记 | 两库 ⇒ 两个标记 |
> | `RootNavViewModel` | 「**任一**库带查看锁 ⇒ 收回解锁页」 | 剩一个标记就整个 UI 停在解锁页 |
> | `completeLocalUnlock(forViewLock)` | 原来只调 `clearViewLock(vaultId)` | 只清当前库 ⇒ **切库再验一次** |
>
> ⇒ **验几次 = 用户有几个库**。Bitwarden 没这问题是因为它的锁定态是**账号级**：
> 门禁与认证天然同粒度。#214 把门禁细化到库、却没同步细化认证 —— 错位就出来了。
>
> ✅ 修法 = 把**认证**粒度对齐到全局（**不是**把门禁粗化）：新增 `clearAutoViewLocks()`，
> 做 `viewLockedIds -= autoViewLockedIds` **子集相减**；UI 一行 `clearViewLock(vaultId)` →
> `clearAutoViewLocks()`。手动查看锁**一个不动**（用户对单库的显式指令不该被一次身份确认撤销）。
>
> ⚠️ 刻意弃用的两条：① `clearAllViewLocks()` —— 会连带抹掉手动锁（安全倒退）；
> ② UI 里 `for (id in ids) clearViewLock(id)` —— 仍是逐库，且漏掉之后才出现的库。
>
> 测试**两层各钉一遍**：`ViewUnlockScopeTest`（新）钉「UI 到底调了哪个方法」
> （`clearAutoViewLocks` 1 次、`clearViewLock` 0 次、`clearAllViewLocks` 0 次、`viewLock` 0 次），
> `VaultSessionManagerTest` 钉集合代数（自动跨库全清 + 手动原样保留）。
> ⚠️ **只测数据层会假绿**：bug 现场就在 UI 那一行，回归门禁必须钉在调用点。
>
> 判据：① **认证粒度必须与门禁粒度对齐**（一全局一按库 ⇒ 立刻退化成每库一次）；
> ② **「存储方式」不许升格成「语义」**；③ 要扩大清除范围时，正确解通常是
> **新增一个更精确的方法**，而不是放宽已有的那个。
>
> 📄 详见 `.ai/issues/04-锁与解锁.md` **#215**
>
> ✅ CI `a877736` **一次过**（`build_failed=false` / 1422 例 0 失败）。
> 加接口成员时先 `grep -rn "clearViewLock"` 把接口 / Impl / 调用点 / 测试一次看全 —— #214 连红两次都栽在"我以为它在那个类里"。
>
> ⚡ **2026-10-30：对齐 Bitwarden —— 「从不」档要的是两层锁（#214）**
>
> 用户：「开启了从不锁定，熄屏或清后台后 Bitwarden **还是要指纹**，但**填充不受影响**，
> 只是进入 App 时要验证，其他的不变」。⇒ 用户要的不是「更狠的锁」，是**两层**。
>
> | 层 | 熄屏/清后台后 | 填充 | 谁设置 |
> |---|---|---|---|
> | 密钥层 `sessions` | **保持**（不要求主密码） | 照常用 | 只有真锁才清 |
> | 界面层 `viewLockedIds` | **置位**（只画指纹按钮） | 自动门禁→**看穿** | `AutoViewGate` |
>
> 原 `Never` 的两个洞：① `AutoRestoreTrigger` 用**免认证信封**恢复（清后台静默开回来）；
> ② `VaultixApplication`/`AutoLockController` 对 `Never` 直接 return ⇒ 没人挡界面。
>
> ✅ 修法：复用已有的 `viewLock`（#61，只挡界面不动密钥），新增 `AutoViewGate`，**只在两个时刻**
> 自动置位（`ACTION_SCREEN_OFF` / 信封恢复且 `opened>0`）；**普通切后台不触发**（否则 `Never`
> 退化成 `Immediately`）。自动标记存 `autoViewLockedIdsState` **子集** ⇒ 填充看穿自动、
> 仍尊重手动（`anyManualViewLocked`）。五个前置条件缺一即跳过，其中**「指纹可用」是硬要求**
> （没有指纹时门禁 = 每次回来输主密码，比不做更糟）。读失败 **fail-open**（挡界面属可用性，
> 与 `devRelaxScreenLock` 的 fail-closed 相反，KDoc 写了理由）。
>
> 同时补齐 Bitwarden 档位 **`VaultTimeout.OnSystemLock`**（`-3`）：严格 `isDeviceLocked()`
> （不用 `isKeyguardLocked`，否则「仅滑动」设备上 = 熄屏即锁）+ 300ms 复查 + `screenOffToken` 取消。
> ⚠️ `-3` 与「负数 = 旧键 Never」冲突：`fromLegacyMinutes(-3)` 仍 `Never`，`assertNotEquals` 钉死。
>
> 判据：① **「锁」至少两层，先问锁哪一层**；② 门禁先问「能力不可用时退化长什么样」；
> ③ **自动置位的锁态必须是可区分子集**（共用标志 ⇒ 下游只能二选一，而「一律放行」是安全倒退）；
> ④ fail-open/closed 按「代价更大的一侧」定，每个都写理由。
>
> 📄 详见 `.ai/issues/04-锁与解锁.md` **#214** · `.ai/SESSION-2026-10-30.md` §8
>
> ⚡ **2026-10-30（上一轮）：开关"永远点不动"的真因 —— 两个负号相消（#213）**
>
> #212 修完命中框后用户复测：「还是无法点击，**按钮始终是打开状态**」。
> 后半句是关键：不是"没反应"，是**状态永远不变**。
>
> | 步 | 值 |
> |---|---|
> | 偏好（第一版是**反义**：`true` = 仍需验证） | `true` |
> | 开关 `checked` | `true`（ON） |
> | 点一下 → `toggleable` 回调 `!checked` | 收到 `false` |
> | 调用点又取反 `setXxx(!false)` | 写入 `true` |
> | 回流 | 仍是 ON ⇒ **`f(!x)=x` 恒等映射** |
>
> ⚠️ **Compose 的 `Switch` / `Checkbox` / `toggleable` 回调传入的是"新值"（`!checked`），不是"用户意图"。**
> 调用点再取反一次 = 把状态机接成恒等映射：写成功了、但写的是当前值。单测/detekt 全看不见。
>
> ✅ 修法：**语义翻正**（`true` = 保持不锁，默认 `false`）+ **换键 `dev_relax_screen_lock_v2`**
> （旧键存的是反义，复用会让老机器的 `true` 静默变成"不锁"）+ UI `onCheckedChange = viewModel::setXxx`
> **不取反** + `VaultLockManagerImpl` 用 `if (!…)`、读失败默认 `false`（不放松）。
>
> 判据：① 布尔控件回调 = 新值；② **bool 偏好的名字必须与真值同向**，不同向就一定有人补 `!`；
> ③ **改语义必换键**（旧数据被反向误读，方向通常是"放松安全"）；
> ④ "点了永远不动"先怀疑 `f(!x)=x`，再怀疑命中框 —— 两层可以同时存在。
>

> ⚡ **2026-10-30（更早）：M3 `Switch` 没有 48dp 触摸目标（#212）**
>
> 用户一句话：「保持不锁的按钮无法点击」。**数据流没问题**（写偏好 → DataStore → 回流，
> 3 条单测全绿），问题在**命中框**：
>
> | 层 | 事实 |
> |---|---|
> | 行 | `SettingsRow` 这行**不传 `onClick`**（改锁的开关不该"点哪都生效"）⇒ 行内唯一热区是行尾开关 |
> | 开关 | M3 `Switch` 画 52×32dp，`toggleable` 的**命中框就是这 52×32dp**；Material 要 ≥48dp，它自己不补 |
>
> ⚠️ **最贵的教训：给 `Switch` 包 `Modifier.minimumInteractiveComponentSize()` 完全无效。**
> 那个 modifier 只是**提供** `LocalMinimumInteractiveComponentSize`；`Switch` 实现里**没读**它
> （读它的是 `Checkbox`/`RadioButton`/`Slider`）。第一版就这么写的 —— 看着对、CI 绿、真机照样点不中。
> **要看有没有效果只有一个办法：找到组件源码里有没有读那个 Local。**
>
> ✅ 修法：`SettingsComponents.SwitchWithTouchTarget` = `Box(52dp × heightIn(min=48dp))` 包住
> `Switch`（仍居中画 52×32dp）⇒ **视觉零变化，命中框 52×32 → 52×48**。
> `SettingsSwitch` 内置（13 个调用点一次修好）+ `CapabilityToggle` 三分支共用
> + `DisplayOptionsSheet` 矮行 `Spacing.sm`→`Spacing.md`（内容 22dp ⇒ 恰好 48dp）。
> 高度自适应用 `BoxWithConstraints`，行高不够时按行高封顶 + `Log.w` **一次**（路标，不是报错）。
>
> ⚠️ 别用 `Box.clickable` 扩热区：内外都收到点击 ⇒ 一次点击切两次、状态弹回原样，
> 症状同样是"没反应"；热区**上限就是 48dp**（`InteractiveComponentSize.kt` 注释：
> 更大的可点区域会抢相邻组件的手势）。
> 判据：**"点了没反应"先量命中框，别先怀疑数据流。**
>

> ⚡ **2026-10-30（上一轮）：锁语义澄清 + 开发者「保持不锁」（#210）+ 通行密钥 type 串（#211）**
>
> 用户四问（日志内容 / 日志性能 / 两版指纹差异 / 要不要开发者设置），前提是"两个环境都设了
> 自动锁定 = **从不**"。**核心澄清**：
>
> | 想达成的效果 | 该用哪一档 |
> |---|---|
> | 清后台 / 熄屏回来**要**指纹 | **`OnAppRestart`（重启 App 时）** —— 不是「从不」 |
> | 什么都不锁（进程死了也免交互恢复） | 「从不」(`Never`) —— 任何触发源都 `return`，信封免认证恢复 |
>
> ⇒ 「从不」下出现"一个包锁一个不锁"，按代码不可能（同一条 `VaultTimeoutPreferences`）。
> 先看**设置→锁与安全那行的副标题**（实际档位）+ 解锁页形态（只有指纹按钮 = 查看层锁）。
> **别为了"让两版一致"去改 `Never` 的语义**（会推翻 2026-09-29 二次定稿）。
>
> ✅ 按用户要求落地 **debug-only「保持不锁」**：~~`dev_relax_screen_lock`（默认 `true`=要验证）~~
> **⚠️ 该语义已于同日晚翻正并换键 `dev_relax_screen_lock_v2`（默认 `false`=要验证），见上一条 #213**；
> 只在 `OnAppRestart` 分支生效，读偏好失败按 `true`（锁）；UI 与日志同组（`DeveloperSection`），
> 整组仍 `BuildConfig.DEBUG` 才渲染。**没把「自动锁定」整行搬进去** —— 它是一等设置，
> 两份入口必然漂移。
>
> ⚠️ **这轮最贵的三条（都付了 CI 时间：run `41033038640` ❌ → `5dded0c` ✅）**：
> ① **`@set:JvmName` 只改 JVM 方法名**，Kotlin 成员解析永远用属性名 ⇒ `prefs.setXxx = …` 编译不过；
> ② **`javap` 的 `public` ≠ Kotlin 的 `public`** —— `InvalidStateError.Companion` 那个常量是 `internal`；
> ③ **relaxed mock 的 `Boolean` 恒 `false`** ⇒ 新开关语义"`true`=锁"会让正向用例**静默变绿**，
> 三处构造必须显式 stub。
>
> 📌 **日志两个问题的结论**：release **零开销**（`VaultixLog.enabled = BuildConfig.DEBUG`，
> 关着时连 lambda 都不执行；`AutofillLogger` 同理）；debug 每次只是一条 `Log.d`（系统缓冲，
> 不写文件）；采集只在用户点开日志对话框时跑一次 `logcat -d -t 1200`，且不在主线程。
> 逐轮流水见 [SESSION-2026-10-30.md](./SESSION-2026-10-30.md)。

> ⚡ **2026-10-29：查漏补齐 · 四项（#206–#209）+ v0.10.3 已发**
>
> 起点是「继续完成计划的开发任务、修复漏洞、查漏补齐」。**先核实清单再动手** ——
> `next-steps` / `current-status` 里那七类"尚未落地"有一半已经过期（如 `UpdateChecker`
> 走 ghproxy：镜像只作用于下载页且**默认关闭**，真缺的是"验"不是"关"）。
> ⇒ **审计清单是"当时的结论"，动手前必须 grep 一遍现状**。
>
> | # | 一句话 | 判据 |
> |---|---|---|
> | #206 | 开发者日志**屏幕显示没脱敏**（脱敏只挂在导出出口）+ `E/F` 兜底把白名单架空 | **脱敏要保护的是"看的人"**；**兜底只能比主判据更严，不能更宽** |
> | #207 | 镜像下载后无处可验 → 更新结果带官方校验和直链（**不走镜像**） | **校验物来源必须与被测物独立**（同源校验 = 没校验） |
> | #208 | 注册请求里的 `excludeCredentials` 从不读 ⇒ 同站点能注册两把密钥 | 规范里写死的语义，不实现就是洞；**解析失败落增益侧，命中必须拦** |
> | #209 | 改密码后 `passwordRevisionDate` 不更新、密码历史**永不增长** | **「沿用原密文」是手段（别整条重写），不是目的（保持原样）**—— 它被错误套到了恰恰该变的字段上 |
>
> ⚠️ 两条沙箱代价：① androidx.credentials **1.6.0 没有 `CreateCredentialInvalidStateException`**
> （下 AAR 数过）⇒ 用 `CustomException(type="InvalidStateError")`；
> ② **`const val` 不能写在普通 class 里**（顶层 / object / companion 才行）。
> ⇒ **依赖里的类名要数一遍、新声明的位置要按 Kotlin 规则过一遍**，别拿 CI 当编译器。
>
> 📦 **v0.10.3 已发布**（`d631c3d`，run `40687433714`）；#204 的"校验和区块印的是命令原文"
> 在真实发版路径上验过 —— 现在是真哈希。**#204 闭环。**
> 逐轮流水见 [SESSION-2026-10-29.md](./SESSION-2026-10-29.md)。

> ⚡ **2026-10-28：液态玻璃三连修 —— 整页变白（#200）→ 底栏消失（#205）+ 发版 v0.10.1 / v0.10.2**
>
> 🔴 **#205（最新，未发版）**：开液态玻璃后**整条底栏消失**。`LiquidGlassLayer` 的两个子节点
> 都写 `matchParentSize()` —— 它的语义是「跟随父尺寸、**不参与**父的尺寸计算」⇒ 父 `Box`
> 高度停在 `constraints.minHeight` = **0**（`Box.kt` 源码已核对）⇒ 子节点被按 0 高测量
> ⇒ `clip(shape)` 裁光。宽度不塌是因为外层给了 `fillMaxWidth()`，**只有高度没人管**。
> 修法 = 层 B 改 `fillMaxWidth()`，成为唯一参与父测量的节点。
> 判据：**`matchParentSize` 只能"盖在别人身上"，不能"撑起父容器"** ——
> 每写一个都要能指出"谁在决定父尺寸"，指不出来就是 0。
> ⚠️ 它从第二轮就存在，被"整页变白"盖了整整两轮：**屏幕全白时看不出底栏也没了**。
> ⇒ **主诉症状缓解 ≠ 修完**，必须重新完整过一遍验收清单。
>
> 📦 **两个稳定版都已发出**：`v0.10.1`（修 #200 白屏）→ `v0.10.2`（带 #202 导入去重、
> 导出/导入往返保真度、#203 修复）。两版**同一张签名证书**（`7e6c8c07…`，APK v2 块实测），
> 可覆盖升级；versionCode `10001 → 10002`。
>
> 🔴 **发版链路的新洞（#204）**：Release 说明的「校验和」区块印的是 `$(cat …)` **命令原文** ——
> `with.body:` 是 **YAML 字符串不是 shell**，`$( )` 只在 `run:` 里展开。
> 判据：**`run:` 是 shell，`with:` 是模板**；`with:` 里出现 `$(` / 反引号 / `&&` / `||` 都是写错地方，
> 而这类错误**不会让构建失败**，只能"打开产物看一眼"发现。
> ⇒ **凡是"发布产物才有的性质"（签名、versionCode、包内资源、说明文字），CI 绿都推不出来，
> 必须直接验产物**（本轮的验法：下载 APK 算 sha256 + 自写 APK v2 签名块解析器比证书）。
>
> 用户：「开启液态玻璃后，整个页面变白」→ 修 → 发 release → 继续开发。
>
> 🔴 **根因一句话**：#198.1 为了修"图标被一起糊掉"，把源改成 `record {}` 之后**没有
> `drawLayer` 画回屏幕**，KDoc 还自称"与 Haze 的有意分歧"。分歧不成立 —— 浮层只盖住底栏，
> 源录的是整屏 Tab 内容 ⇒ 其余区域**没有任何绘制命令** ⇒ 露窗口底色（浅色主题 = 白）。
> **"没人画"在 Compose 里不报错，就是露底色。**
>
> ✅ 修法三处：① `record` 后 `drawLayer(contentLayer)` 回屏；② 模糊挂**独立 `effectLayer`**
> （一块 layer 只有一个 `renderEffect`）；③ `translate(offset)` 用本身不取反。
> 详见 `.ai/issues/06` §200。
>
> 🚀 **v0.10.1 已发布**（`app-full-release.apk` 7.2 MB）。发版路上两个坑都补了洞：
> - **#201**：`ci-debug.yml` 的 `Run lint` 带 `if: event_name != 'push'` ⇒ push `main` **根本不跑 lint**，
>   而 release 的 `lintVital` 对 fatal **直接中断** ⇒ "CI 绿"推不出"能发版"。已删非法 `cache` 备份域，
>   并补「domain 白名单」单测把 lint 才知道的约束搬进毫秒级检查。
> - **#202**：重复导入 = 每个条目两份（改一份、自动填充撞另一份）。已加 `ImportDuplicatePolicy`
>   （type+title+username）+ 写前预演「新建 N / 已存在 M」，预演没出来时「确认导入」点不动。
>
> 🐛 顺带修 **#203**：`init { scope.launch }` 让测试接缝晚一步生效 ⇒ CI 偶发
> `Dispatchers.Main was accessed`（本地绿）。抽 `startObservers()` 显式启动。
>
> ⚙️ **SSH 通道已启用并固化**（见 `.ai/issues/01` #39 与 §9 下的"环境配方"）：
> 私钥 `~/.ssh/github_chani`（ED25519，指纹 `SHA256:r99kZ2…IqA`）+ `~/.ssh/config` 走
> `ssh.github.com:443`，`origin` 已从 token URL 改为 `git@github.com:Chaniug/Vaultix.git`。
>
> 门禁：CI run 40565728584 全绿（detekt / assembleFullDebug / 9 模块单测 / `e:` 0）。
> ⚠️ 待真机验收：玻璃 6 条（`issues/06` #200.6）、导入去重。

> ⚡ **2026-10-27：液态玻璃底栏返工（#198.1）· 全仓库 134 套 / 1328 例 / 0 失败**
>
> 用户真机截图：「液态玻璃的底栏不对啊，按钮都看不清，参考开源项目是如何实现的？」
> —— 整条胶囊连同 4 个 tab 图标、文字、中央「+」全糊成灰雾。
>
> 🔴 **一句话根因**：`graphicsLayer { renderEffect = 模糊 }` 模糊的是**该图层的输出，
> 包含它全部子内容**。第一版把它挂在包住 `DockRow` 的 Box 上 ⇒ 图标自己也被糊。
> **上一版 KDoc 里写的"必须挂最外层 Box 才对"是错的**，真机截图直接证伪。
>
> ✅ **正确架构 = 源 → 消费 两段分离**（对齐 chrisbanes/Haze 的 `hazeSource`/`hazeEffect`，
> 但**不引新依赖**，用 Compose 自带的 `rememberGraphicsLayer` + `record` + `drawLayer` 自实现）：
> - **源**：`Modifier.liquidGlassSource` 挂在 Tab 内容区 Box 上，
>   `drawWithContent { layer.record { drawContent() } }`（内容只进 layer，**不画到屏幕**）；
> - **消费**：`Modifier.liquidGlassBackdrop` 挂在浮层的"背景层"，
>   `canvas.translate(-offset); layer.renderEffect = blur; drawLayer(layer)`；
> - **结构必须分两层兄弟节点**：层 A 只画糊影（唯一带 `renderEffect`），层 B 画
>   填充/高光/描边 + 图标。写进一个 Box 的 modifier 链就复现 bug。
>
> ⚠️ 三条连带判据：
> ① **源的子树里不能有不透明铺底**（`Surface(color=…)`）—— 录到的会是那块纯色，
>   糊它等于没做；本项目 Tab 页透明、**顶栏是不透明 `Surface`** ⇒ 源不能含顶栏。
> ② 源录的是**整块 Tab 区**（比胶囊高得多），层 A 必须 `clip(shape)`，否则糊影溢到胶囊外。
> ③ 填充数值改为**直接对齐 Haze `HazeMaterials` thin 档**（0.60 / 0.65，**深色更实**）——
>   第一版的 0.62 / 0.48 是拍脑袋且**方向反了**（Haze 五档全部深色更实）。
>   降级档也不再"半透明 + 0.18 补偿"，改为**全不透明**：半透明底栏浮在列表上会透出条目。
>
> 门禁：compile（双变体）✅ · detekt ✅ · test **134 套 / 1328 例 / 0 失败** ✅。
> `LiquidGlassTest` 8 → **10 例**（2 例被真机证伪后改写方向）。详见 `.ai/issues/06` §198.1。
>
> ⚡ **2026-10-27：文件夹 CRUD 落地（#199）· 全仓库 133 套 / 1328 例 / 0 失败**
>
> 用户：「继续开发，完善设置，以及日常的功能」。挑的是功能盘点里**唯一"看得见、动不了"**的一块：
> `FolderRepository` 只有读、`FolderDao` 只有整体替换 ⇒ 用户看得见文件夹却建不了改不了删不了
> （Bitwarden 官方与 KeePassDX 都能建分组）。
>
> | 决策点 | 用户选定 |
> |---|---|
> | 写失败怎么办 | **直连 API，失败当场报错** |
> | KDBX 分组一并支持？ | **是** |
> | 入口放哪 | **条目页 ⋮ 菜单「前往」组** |
>
> 🔴 **最值钱的一条**：Bitwarden 侧**不能**照搬条目的「本地先行 + `pending_ops`」。
> `pruneRemovedRows` 的判定是"服务端没返回的 folder 行 = 已删" ⇒ **刚建未推的文件夹
> 会被下一次同步静默删除**。⇒ 成功才写本地，失败一个字不改（代价：离线不能建文件夹）。
> 反证用例 `新建失败_本地零写入` 专门锁这条：没有它，"先写本地再调远端"也能全绿。
>
> ⚠️ 另外三条判据：① 名字禁 `/` `\`（KDBX 的 `VaultFolder.name` 是**完整路径**）；
> ② 两种库的删除文案**必须分开**（Bitwarden 只脱标签 / KDBX 递归删子树）；
> ③ 删除代价「数法只能有一处」（`deletionCostOf`），UI 侧 KDBX 那支**刻意不显示条目数**
> （子组没算进去会偏小，给偏小的数字比不给更危险）。
>
> 坑记录：`Suspension functions…` 我误判成"位置错"，实为 `every` 应为 `coEvery`
> —— **读报错要看列号指向哪个符号**；detekt `LongMethod` 数的是**有效行**，
> 且**不要用删注释去凑门禁**（抽出一组完整接线才是既有先例的做法）。
> 详见 `.ai/SESSION-2026-10-27.md`。
>
> ⚡ **2026-10-26：与开源项目对标审计 → 七组修复（#186–#192）**
>
> 用户：「与别的开源优秀项目进行对比，看看还有哪些功能缺失，或者不完善，有明显bug。修复这些漏洞」。
> 先做四维度**只读审计**（备份与自动填充 / 锁与设置面 / 通行密钥与 KDBX / 安全加固与 CI），
> 基准是 Bitwarden 官方 Android、Keyguard、KeePassDX、Keepass2Android、Monica、Bastion 快照，
> **再逐条落地**。本轮落地七组，全部编译 + detekt + 单测通过。
>
> | 编号 | 一句话 | 关键判据 |
> |---|---|---|
> | #186 | `allowBackup` 默认 true ⇒ 锁库信封被云备份搬走，Keystore 不跟着走 | **加密材料与解密密钥分开存时，备份策略按后者决定** |
> | #187 | 自动填充保存链四处断裂（锁定态无 `saveInfo`、字段选错、撞号、明文进 Intent） | **requestCode 是身份**；**凭据不进 `Intent`** |
> | #188 | 锁库不清剪贴板 + 网址被当敏感内容 30 秒后清空 | **定时清理必须配一个立即清理的触发点** |
> | #189 | 熄屏不保证 `onStop` ⇒ 自动锁定不启动 | **「用户看不见了」有熄屏/切后台/分屏三条通路** |
> | #190 | 409 被当弃单（同步）+ 内容指纹只摘 5 字段（KDBX） | **4xx ≠ 可以扔**；**指纹漏字段 = 那道防线在该字段上恒失效且静默** |
> | #191 | 搜索不覆盖备注与自定义字段 | **界面上展示的字段都该可搜索**，例外要反向锁定 |
> | #192 | 空分组被当「远端删了」⇒ 能清空本地文件夹 | **「远端没有」≠「远端删了」** |
>
> 🔴 **本轮最值钱的一条（#190 的第二半）**：#185 那道写回基线防线靠内容指纹判断"我手里这份
> 是不是远端那一版"，而指纹最初只摘 `id/title/username/password/notes`。**改网址、改 TOTP、
> 改卡片、改自定义字段、改收藏、换文件夹之后保存，指纹不变** ⇒ 基线判"没动过" ⇒ 放行 ⇒
> **基于旧版本覆盖远端**。而且**不报错、不降级、零日志** —— 比"没有防线"更难发现，
> 因为审查时看着它是有的。修法 = 全字段 + 字段分隔符（消除可构造碰撞）+ Map 按 hash 排序
> （顺序不稳 = 假阳性）+ `ABSENT/PRESENT` 区分 null 与空串，并用**表驱动**
> `FIELD_MUTATORS`（21 字段逐个断言必变）把它钉住。
>
> ⚠️ **三个本轮实测踩到的坑**：
> ① detekt `CyclomaticComplexMethod` 降不下来时，**"提前 return"也算分支**（`flushPending` 16>14，
> 最终靠拆出 `handleOperationFailure()` 才过）；
> ② 单测**假红**要学会怀疑夹具：`BitwardenSyncPendingOpFailureTest` 的 404 用例本该弃单却留在队列，
> 真相是 `payload="{}"` 缺 `CipherRequest.type`，请求还没发就抛了 `MissingFieldException`
> 被归成"网络类"；`VaultixLog` 默认 sink 丢弃日志，定位只能靠临时 `System.err` 再读测试 XML；
> ③ `onScreenOff()` 之后直接推进虚拟时间是假红 —— 定时器在协程里挂上，要先 `settle(m)`。
>
> **门禁**：compile ✅ · detekt ✅ · test **131 套 / 1301 例 / 0 失败** ✅（`--max-workers=1 --no-daemon`）。
> （2026-10-27 更正：历史上记的 174/1706、180/1744 均**口径有误**（通配符跨目录重复计数），实测 2026-10-27 为 131/1301。 → #196/#197 + 环境守卫 → #198 液态玻璃 +1 套 / +8 例（1300 → 1301）。）
>
> **同日夜补齐第二批（#193–#195 + TOTP 票据）**：
> `#193` `release.yml` 写死 `gradle-version: 8.11.1`（`ci-debug.yml` 2026-10-02 就修过，这条路漏网）；
> `#194` 附件解密后的明文留在 `cacheDir` 锁库不清 ⇒ 新增 `SensitiveCache` 统一归口；
> `#195` `keyAlgorithm` 同一把密钥两个名字（`ECDSA` vs `ES256`）+ 四处 `FLAG_MUTABLE` 改 IMMUTABLE；
> 顺带把 TOTP 密钥从 `PendingIntent` extras 挪进 `TotpTicketStore`（60 秒可重读，**不能做成一次性**
> —— `AutofillActivity` 可能被重投递，第二次拿 null 等于"点了没反应"）。
>
> 🔴 **这三条共享同一个根因模型**：**同一条规则写在两处、只改了一处，看起来就"已修"了**
> （#193 ↔ `ci-debug` 已修 / #195 ↔ `AutofillIntents.pending` 早就 IMMUTABLE / #120 的原型）。
> 判据：**修法不是"记得两边都改"，是让事实只有一个来源**。
>
> **审计里还没落地的（下一批，按优先级）**：
> ① ~~通行密钥候选不做 Origin 校验~~（**已修** #196/#197：创建侧 origin↔rpId 从属校验
> + 读 `authenticatorSelection.residentKey`；`backupEligible/backedUp` 强制 true 属**有意为之**，
> 见 `WebAuthn.buildAuthenticatorData` 的 KDoc，不修）；
> ② PIN 熔断无冷却 + 忘记 PIN 绕过；「从不」档 KEK 不绑认证；
> ③ 自动拉取零接线（`SyncTrigger.PAGE_ENTER/APP_RESUME/PERIODIC` 全仓零调用）；
> ④ 导出丢密码历史/时间戳、`passwordRevisionDate` 从不更新、CSV 导入、文件夹 CRUD、
> passphrase UI、安全中心；
> ⑤ `UpdateChecker` 走 `ghproxy.net` 且不校验 SHA-256；`FillAssistRepository` 默认联网拉规则；
> `DeveloperLogsDialog` 的 E/F 兜底不脱敏；
> ⑥ `KdbxRoundTrip.verify` 只比字段名不比值；`KdbxFidelity.NON_DEFAULT_CIPHER` 从不产生。

> ⚡ **2026-10-25 晚：写回基线 —— 令牌对得上 ≠ 内容来源对得上（#185）**
>
> 修的是上一轮盘点里"只挑一件"的那件：**远端改了、本机没拉、用户接着编辑 ⇒ 远端被静默覆盖**。
>
> - 🔴 **根因不是"令牌被提前推进"，而是主写路径带的就是旧令牌**。
>   `saveVia(expectedVersion = 缓存里的 v1)` 天生不满足"内容与令牌配套"这条乐观并发前提 ——
>   服务端见令牌对得上就放行。**版本号只保证"没人抢先"，不保证"我这内容是那一版的"**。
> - **两道防线**：① `KdbxSyncOrchestrator` 该分支不推进 token，改做**内容对账**
>   （相同才降级 `IN_SYNC`，不同则保持 `REMOTE_CHANGED` 且一个字节都不写）；
>   ② 新增 `KdbxBaseStore` 内容指纹基线，`mutateLocked` **改动之前**取指纹比对，
>   结论随 `KdbxPendingWrite.baselineAligned` 上浮，`KdbxItemRepository.persist` 落字节前拦。
> - 🔴 **四个实测踩到的坑**（详见 `.ai/issues/07` #185）：
>   ① 不能拿 `encode` 字节哈希当指纹（每次重生成 IV ⇒ 必然不同 ⇒ "所有库永远算变过"）；
>   ② 不能为比指纹再 `encode`（一次 = 一次 KDF）；
>   ③ 不能把 `updatedAt` 算进指纹（远端只是重编码 ⇒ 被判"内容变了"，防线变噪音）；
>   ④ **`saveVia` 里不能现查 `session.content`** —— 那时它已是**改完之后**的内容，
>   基线记的是改之前那一版 ⇒ **所有正常保存全线误拦**（本轮实测就是这个后果，14 条用例红了 4 条）。
> - **语义边界（三条都要记住）**：基线没记录 ⇒ **放行**（交给令牌兜底，别制造假冲突）；
>   闸门**只对云端来源**生效（本地 SAF 库没有"远端那一版"，判定它等于拦掉所有本地保存）；
>   同步编排器那条写路径传 `null`（不表态）—— 它推的是"会话里的全部改动"，正解是条件写。
> - **门禁**：compile ✅ · detekt ✅ · test **162 套 / 1620 例 / 0 失败** ✅（`--max-workers=1`）· CI `40189657418` ✅。

> ⚡ **2026-10-24 晚：首次启动 3 页引导（#184）**
>
> 用户问「首次启动的引导该如何设置呢」。查证发现：`Docs/08` 的 S2「引导（3 页）」**从未实现**，
> 全新安装看到的是品牌空态页（`EmptyVaultState`）。用户拍板：补 3 页 + 插画用**代码画的矢量图**。
>
> - 🔴 **准入 = 「无库」且「没看过」，不是「第一次装 App」**。两个条件各自都不够：
>   只看"没看过"⇒ 旧版本升级上来的老用户（键不存在、读作 false）被拦一次；
>   只看"无库"⇒ 删光库的老用户反复被引导。`RootNavState.Onboarding` 的语义是
>   "没有库可解锁"，**不能**直接当引导条件。
> - **落点与写入**：`RootNavViewModel.showOnboarding`（初值 `false` = 不拦）→ `VaultixApp`
>   仅在 `!routedOnce` 时落 `OnboardingRoute`。两条出口（看完 / 跳过）都写 `onboarding_seen`；
>   第 3 页「去开启自动填充」额外写 `fill_setup_prompted`，且**不结束引导**。
> - **偏好层用独立类 `OnboardingPreferences`**：`VaultixPreferences` 已 35 个函数（上限 40），
>   再堆 4 个就顶死。房内纪律：顶到上限是"该抽类"的信号。
> - **插画**：`Canvas` + 主题色，无位图、无外链素材。所有几何比例具名（detekt `MagicNumber` 是硬门禁，
>   且这些数字彼此耦合，散在算式里没人能一次改对）。
> - 🔴 **插画不能与文案唱反调**：第 2 页说"两个库地位一样"，两块石头就必须等大 ——
>   画成云 + 文件，云的视觉重量天然压过文件。
> - 🔴 **测试桩教训（本轮真踩）**：手写 `MemoryStore` 时 `data` 返回构造时的 `flowOf(initial)`，
>   4 条用例全挂而**产品代码是对的** —— `DataStore.edit{}` 内部**也读 `data`**。
>   ⇒ 能用官方实现（`PreferenceDataStoreFactory` + `TemporaryFolder`）就别手写桩。
> - **门禁**：compile ✅ · detekt ✅ · test 161 套 / 1605 例 / 0 失败 ✅ · Python 检查全 ✅。

> ⚡ **2026-10-24 晚：权限页行尾不齐 + 状态卡曲率打架（#183）**
>
> 用户：「权限管理部分设置里面，好像还没有对齐」。截图里「去设置」（`TextButton`）与
> 「已允许」（裸 `Text`）**左边缘错开** —— 按钮自带 `ButtonHorizontalPadding` 24dp。
>
> - **修法**：抽 `TrailingSlot`（`widthIn(min = 112.dp)` + `heightIn(min = TouchTarget)`
>   + `CenterEnd`），**状态与按钮一律装进同一个框**；`TrailingLabel` 统一 `labelLarge`。
>   ⚠️ 宽度必须**写死**，`wrapContent` 会让每行各贴各的；宽度不够就换行 ⇒ 行高 72 → 96dp。
> - **附带**：两页状态卡（`PermissionsScreen` / `AutofillSettingsScreen`）的圆角
>   28dp → `VaultixCorner.Panel`(16dp)。**必须两页一起改** —— 只改一页等于把问题挪个位置。
> - 🔴 **通用教训：一屏里出现两套规格（8.4）最常见的两种形态是"缩进不同"和"曲率不同"**，
>   两者都不会报错、也不容易在对数里发现，只有把两张卡/两行并排看才露馅。
> - **门禁**：compile ✅ · detekt ✅ · test ✅（160 套 / 1591 例 / 0 失败）· Python 检查 11 个全 ✅。

> ⚡ **2026-10-24 晚：筛选面板「回到顶部」被否决 ⇒ 改为默认关闭的选项（#182.1）**
>
> 用户真机复测后的原话：「现在往下滑动的时候，发现点击筛选，会直接返回最上面。这样设定不友好」。
>
> - 🔴 **判据：「画面连续」≠「位置连续」。** #182 用 `graphicsLayer` 反向补偿做到了
>   **可见内容一像素不跳**，却没解决"位置被换掉了"—— 用户在读第 30 条，点筛选是想在
>   **这个上下文里**换一维，不是从头再看。
> - **改法**：`rememberListEdgeHolder` 加 `jumpToTop` 门控 → 偏好
>   `VaultixPreferences.itemsFilterJumpToTop`（**默认 false**）。关闭时**完全不碰滚动位**
>   （连"切 chip 回第 0 项"也不做）。开关在「条目显示」浮层最下面：`展开筛选时回到列表顶部`。
> - ⚠️ 代价如实记：关掉后往下滑再展开，顶栏之下仍是空白（#182 描述的那个"难看"）。
>   **取舍交给用户** —— 两边诉求都真实，不该再替他猜。
> - 🔴 **通用教训：位置类改动（滚动 / 分页 / 折叠）默认必须"不打扰"**，
>   要打扰就得是用户显式打开的行为。修一个现象若需要动用户的浏览上下文，先问这是不是他要的。
> - **门禁**：compile ✅ · detekt ✅ · test ✅ · Python 检查 ✅。

> ⚡ **2026-10-24：筛选面板「贴着条目」+「滚动后展开难看」已修（#182）**
>
> 用户两张真机截图 + 两句话：chip 行与下方条目"隔得很近"；往下滑之后点筛选"显示效果
> 很不好看"。两条**同源**：面板与列表之间**没有一条边界被明确画出来**。
>
> - **① 间距**：chip 行下内边距 8dp → **12dp**，并另加 [QUICK_FILTER_LIST_GAP] 8dp
>   呼吸缝（放在**让位侧**，与面板高度解耦）；`QUICK_FILTER_ROW_HEIGHT` 从字面量 48dp
>   改成**算式**（内边距 + `FILTER_CHIP_HEIGHT`），以后改内边距不会漏改让位常数。
> - **② 滚动后展开**：面板展开 **且** 列表已滚离顶部时，把列表滚到
>   `first = 第 0 项 / offset = QUICK_FILTER_TOP_ANCHOR(56dp)`，外壳 `padding(top)` 下移
>   同一值、列表各项 `graphicsLayer(translationY)` **反向补偿** ⇒ 可见内容**一像素都不跳**，
>   面板下面接的是列表的**头**而不是空处。列表在顶部时 `reveal = 0`，老路径逐字节不变。
> - 🔴 **顺序硬性：先让位、再滚。** `scrollToItem(0, anchor)` 上限是 `contentPadding.top`，
>   而让位要下一帧才生效 —— 先滚会被夹到 0（滚了等于没滚）。实现用两帧 `withFrameNanos`。
> - 🔴 **本文件 8.4「让位只走 contentPadding」有了一个例外**：面板展开这种"自上而下绘制"的
>   浮层，需要外壳也参与让位，否则顶栏之下是空的。例外条件写死在
>   [itemsShellTopInset] 的 KDoc 里，别当成通用做法扩散。
> - **门禁**：compile ✅ · detekt ✅ · `gradle test` **160 套 / 1591 例 / 0 失败** ✅ ·
>   10 个 Python 检查 ✅ · `check_issues_index` 一致 ✅。
> - **待真机验收**：① 顶部展开时 chip 与首条之间应明显有缝；② 下滑几屏后点库名，
>   面板下方立刻能看到列表顶端；③ 面板展开期间连点 chip，顶栏标题不缩。

> ⚡ **2026-10-22：详情页闪过 1 帧「条目不存在或已被删除」已修（#181）**
>
> 用户真机反馈：列表滑动后点进条目，会有**不到 1 帧**的缺失提示闪一下。交接见
> **`.ai/SESSION-2026-10-22.md`**。
>
> - **根因**：`ItemDetailViewModel.UiState.item` 初始 `null`，被 UI 用来表达**两件事**
>   （"还没解出来" vs "确认不存在"）⇒ 订阅建立前的那一两帧被渲染成"确认不存在"。
> - **修法（两层）**：① `UiState` 新增 `itemLoaded`（收到首次发射即真、**永不回落**）；
>   ② `DetailBodyContent` 加 `loading`，`loading && item == null` 画转圈，`item == null` 才画提示。
> - 🔴 **判据：「未知」必须是一种显式状态，不能借用「空值」表达**。`null` 兼表"没有"与
>   "不知道"时，UI 必然在其中一种情况下撒谎。
> - ⚠️ **绕路记录**：最初判定是 `observeItem` 里 `vaultDao.observeAll()` 卡住
>   `flatMapLatest` 上游，加了 `.onStart { emit(null) }` —— **探针实测证伪**（撤掉后
>   `.first()` 仍拿到条目）。该行作为前置订阅的廉价优化保留，但**不是修复点**。
>   下次遇到"疑似链路顺序问题"，先写探针跑一次再改代码。
> - **门禁**：`:app:compileFullDebugKotlin` ✅ · `detekt` ✅ · `gradle test`
>   **套数=160 / tests=1591 / failures=0 / errors=0 / skipped=0**（XML 时间戳在当次窗口内）✅ ·
>   10 个 Python 静态检查全 OK ✅。
> - **待真机验收**：① 点进条目不再看到该提示；② `itemLoaded` 置真后流重发（同步刷写）
>   不闪回"不存在"。状态层属 Compose 状态机，本地无 UI 测试装置，只能真机看。

> ⚡ **2026-10-20（下半场）：KDBX 写入/同步链三条静默丢数据修完（#178–#180）**
>
> 完整交接见 **`.ai/SESSION-2026-10-20-b.md`**。三条的共同形态：**失败不报错、用户以为成功**。
>
> - **#178 `Kdbx.mutate` 读-改-写不原子** ⇒ 并发编辑静默丢一笔、**两次都返回成功**。
>   修法：每库一把 `Mutex`（`writeLocks` / `lockOf`），`mutate` 与 `replaceSession` 共用。
>   🔴 **判据：`ConcurrentHashMap` 不等于线程安全** —— 它只保护单次操作，
>   "get → 改 → put" 的三步合起来照样能被插进来。
> - **#179 `writeLocks` 只增不减**：`lock` 只 `close` 会话不丢锁 ⇒ 删掉/锁掉的库还握着活锁。
>   修法：`lock` / `lockAll` 一并 `remove` / `clear`，且**顺序必须是"先 close 会话、再丢锁"**。
> - **#180 「用远端覆盖 / 拉取」的下载窗口**：`read` 要几秒，窗口内的编辑被
>   `replaceSession` 整体抹掉并记成 `IN_SYNC`（**再也不会重试**）。
>   修法：`read()` **之前**再判一次 `localChangedSinceLastSync`，有改动就返回 `Conflict` 不下载。
>   ⚠️ 两个"不要"：不要用 `markDeferred`（只在已 CONFLICT 时生效，等于没做）；
>   不要把本地改动改写成 `CONFLICT`（伪报"两边都改了"，把用户推向真丢数据的选项）。
>
> **验证**：三条都做了变异验证（撤掉修复 → 对应用例转红），#179 的反证写在同一用例内。
> **门禁**：编译 ✅ · `detekt --rerun-tasks` ✅ ·
> `gradle test --rerun-tasks` **套数=84 / 用例=776 / 失败=0 / 错误=0 / 跳过=0** ✅ ·
> 9 个 Python 静态检查全 OK ✅。
> 🔴 **又踩到"假绿"**：第一次 `gradle test` 超时转后台，回来看到的 42 套/388 例是
> **08:21 的旧报告**。纪律：**看数字前先 `stat` 报告时间戳**。
>
> **查过但不是 bug（别重复查）**：`persist` 不传 `expectedVersion`（四象限表里本地 SAF 有意如此）·
> `update` 在锁外取 `before`（`applyDomainFields` 用锁内 `existing` 打底，`before` 只判删除）·
> KDBX 无 SSH 映射（A3 入口硬闸整体拒收）· `Kdbx.save(File)` 零调用（能力已迁到 `verifyWrite`）。

> ⚡ **2026-10-21：UI 审计剩余 5 项收口（§2.2 / §2.7 / §3.4 / §3.7 + §2.5 判定不做）**
>
> 完整交接见 **`.ai/SESSION-2026-10-20.md`**。四项落地、一项明确不做：
>
> - **§2.2 + §3.7 合并成一轮**（同一类：语义常量层）：新增第四张表 `ui/theme/UiSize.kt`
>   （`InlineIcon`18 / `SecondaryIcon`20 / `MicroIcon`16 / `InlineProgress`18 /
>   `InlineProgressStroke`2 / `TouchTarget`48 / `HairGap`2 / `TightGap`6 /
>   `RowVerticalPadding`10），**80 处字面量逐行精确替换**（27 文件）、
>   清掉 9 个孤儿私有常量、补 27 个 import。
>   🔴 **判据：`Spacing` 管"两者之间"、`UiSize` 管"自己多大"。18 与 20 并存不是重复，
>   判据是"它和谁并排" —— 18dp 在 `TextButton` 图标槽内（不改按钮高度），20dp 独立。**
> - **§3.4 中文硬编码 117 → 0**：新增哨兵 `.ai/tools/check_ui_hardcoded_text.py`
>   （查**反方向** —— Kotlin 里有没有本该进资源的中文；`check_orphan_strings.py` 只管
>   "资源没人用"）。四类 `Res/Detail` 类型封同一判据（见 §8.4 新纪律 5/6）。
>   🔴 **教训**：`LocalUnlockFanout` 因"拿不到 `Context`"镜像了一份中文字面量、
>   注释写"靠 review 守" ⇒ 那是靠人守的双源。改成产出 `LocalUnlockMessage` 枚举。
> - **§2.7 文档纠偏**（`Docs/08 §4`）：原四档表读起来像已实现，实际只有一个 600dp
>   阈值两档。已拆成「4.1 已实现（照实描述 + 代码位置）」/「4.2 计划（均未实现）」，
>   三档化 / 双栏 / 铰链避让登记为待做（`.ai/issues/06` #177）。**没有改任何布局。**
> - **§2.5 顶栏四套收敛：不做**。审计自己写"需真机看折叠手感"，本地改数值等于凭感觉 ——
>   与 §2.7 不擅自实现三档是同一个理由。
>
> **门禁**：编译 ✅ · `detekt --rerun-tasks` ✅（**修了 3 个真违例**：
> `CloudAccountsScreen` 复杂度 16→14 抽出 `NoticeSnackbar`、`actionResultText` 15→14
> 抽出 `syncNoticeText`、`LocalUnlockFanoutTest` 补末尾换行）·
> `testFullDebugUnitTest` **套数=42 / `tests=388` / `skipped=0` / `failures=0` / `errors=0`**
> （**三个数都对上**，且 `185 actionable tasks: 185 executed`，XML 时间戳全在当次窗口内）✅ ·
> 9 个 Python 静态检查全 OK ✅（顺手修了 `check_compile_smells` 把 KDoc 里的
> `R.string.<name>` 示例当悬空引用、`check_issues_index` 缺 #177 两处）·
> CJK 行宽：超 120 的 11 处**全是 KDoc 表格行**，detekt `MaxLineLength` 实跑未报（既有状态）。
>
> 🔴 **本轮踩坑（都记进 §8.4 / §8.6 / `issues/01`）**：
> ① 基线特征串是对**引号内内容**匹配，不是整行 —— 写成 `X =` 永不命中且不报错；
> ② 给 ViewModel 注入 `Context` 会连带炸两处直接构造它的单测（补 `textContext()` 假 Context）；
> ③ 把 `when` 内联进已有大 `when` 的参数会顶爆圈复杂度 —— detekt 当场抓住；
> ④ 🔴 **`failures=0` 不等于全过**：daemon 中途崩溃只生成 39/42 套 XML，而那份报告是
> `tests=367 failures=0 errors=0` —— **零失败**。⇒ **测试门禁必须核三个数：套数 42 /
> tests 388 / failures 0**，少核「套数」就会把"没跑到"读成"全过"。详见 §8.6 末节。
>
> **下一轮起点**：审计项全部收口。剩下的是**真机项**（§2.5 顶栏折叠手感、
> OneDrive `If-None-Match: *` 的 201/412、W6 KeePassXC 互操作）与
> **响应式三档化 / Expanded 双栏 / 铰链避让**（需要折叠屏 + 平板）。

> ⚡ **2026-10-19：UI 审计 §4 的 5 个施工项全部落地（P0 → P2）**
>
> 来源：`Docs/progress/audit/ui-design-audit-2026-10-19.md`。完整交接见
> **`.ai/SESSION-2026-10-19.md`**（七个判据 + 4 个踩坑全在里面）。要点：
>
> - **#31 Preview 基建先做**（P2 级别但排最前）：`ui/preview/` 两个文件 11 条 `@Preview`，
>   覆盖 3 个规格中心 × 字体档（1.0/1.3/2.0x）× 明暗。**字体档必须覆写
>   `LocalDensity.fontScale`，设 `textSize` 是假的** —— 只有前者才走到 `sp→px` 那条路，
>   才能验证让位补偿。
> - **#32 P0 真 bug**：库列表让位写死 `96.dp`，而 4 键手势机真实需 ≈118dp
>   （86 占位 + 16 字体增量 + ≈32 系统内边距）⇒ 首个落地页最后一条点不到。
>   已函数化为 `rememberBottomDockInset()`。**判据：让位 = 占位 + 字体增量 + 系统内边距，
>   三项缺一都会在某种设备上翻车，不许写字面量。**
>   同批补无障碍语义 5 处（`Role.Tab`+`selected` / `Role.RadioButton` / `stateDescription`）。
>   **判据：`role` 决定"读屏叫它什么"，`selected` 决定"说没说它选中"，`clickable` 两个都不给，
>   自绘组件必须自己写。**
> - **#33 P1**：主 CTA 9 种写法 → `PrimaryActionButton` 1 个组件。踩坑：
>   **detekt `MatchingDeclarationName` 要求一个文件只能有一个非 `private` 顶层声明，
>   挪文件不解决**（这轮因为"组件 + 它的枚举"连踩两次：`PrimaryActionEmphasis` → 改 `Boolean`；
>   `VaultixShapes` + `VaultixCorner` → 拆两个文件）。另：`IconButton` 补触摸目标用
>   **`minimumInteractiveComponentSize()`，优于手写 `heightIn(min)`**（后者会拉大水波纹）。
> - **#34 P2**：空态 5 套 → `EmptyState` 1 个；圆角 6 种 → `VaultixCorner` 三档
>   （**按尺寸分档不按组件分档**，一句话判定 = 看它有多高；20dp 归 `Panel`，28dp 只留给对话框）。
>   顺手修掉**幽灵圆角**：`VaultixShapes` 原来只覆盖 `extraSmall` ⇒ 6 处不传 `shape` 的 `Card`
>   吃 M3 基线 12dp。**判据：`Shapes(...)` 是部分覆盖不是全量替换。**
>
> **门禁**：编译 ✅ · `detekt --rerun-tasks` **10 个模块任务行真执行** ✅ ·
> `testFullDebugUnitTest` **42 套 / 388 例 0 失败**（数 `test-results/*.xml` 得，非看 ✓）✅ ·
> 9 个 Python 静态检查全 OK ✅ · CJK 行宽 28 文件全 ≤120 ✅。
>
> **CI**：push sha `953c96c` 同时触发**两条** run —— `39288071755` 三个任务行**真执行**、
> `39288075378` 三者全 `FROM-CACHE`（吃前者缓存）。**两条都绿，但只有一条是真验的。**
> 🔴 **判据**：单看某条 run 的 `> Task … FROM-CACHE` 不足以判"没验"，
> 它可能只是并发的另一条 run 刚验过、缓存命中。要判的是
> **「同一 sha 下有没有任意一条 run 是真执行的」** —— 必须 `gh run view --log` 逐条看完。
> CI 产物 8 模块 **1168 例 0 失败**（app 388 与本机一致，XML 时间戳落在真执行那条 run 区间内）。
>
> **下一轮起点**：审计 §4 里 P0/P1/P2 已清空，剩的是 P3（§2.2 `Spacing` 语义化 +
> §3.7 图标尺寸，建议**合并成一轮**先定常量层形态再动手；§2.5 顶栏收敛需真机；
> §2.7 是文档超期；§3.4 中文硬编码需单独排期）。另有两个老的真机项：
> OneDrive `If-None-Match: *` 的 201/412、W6 KeePassXC 互操作。

> ⚡ **2026-10-18（第三轮）：#176 已修 —— 虚拟时钟管"时间"，不管"线程"**
>
> #175 修完、跑绿、推上去（`d646127`），**CI 又红了**（run `39139391458`），红的是
> `UnlockViewModelTest > promptCarriesTheFilteredCandidateVaults`，报的还是
> `Dispatchers.Main was accessed …`，而**本地 `--rerun-tasks` 全绿**。
>
> **根因**：#174 把 `UnlockViewModel.io` 换成 `StandardTestDispatcher(testScheduler)`，
> 只解决了"虚拟时钟推得动"，**没解决"续体跳不跳线程"**。
> `StandardTestDispatcher.dispatch` 只是把任务排进 `TestCoroutineScheduler`，
> 它跑任务时**不恢复** `ContinuationInterceptor` ⇒ 跨 `withContext(io)` 的续体恢复时，
> 上下文里既没有拦截器也没有 `Dispatchers.Main` ⇒ `TestMainDispatcher.isDispatchNeeded`
> 炸（堆栈 `DispatchedCoroutine.afterResume` ← `Dispatchers.IO` 的真线程 worker）。
>
> **解法**：加**第二个**覆写口，语义是**拦截**而不是替换 ——
> `testingBlockableIoDispatcher`（生产恒 null）+ `private val ioContext: CoroutineContext`，
> 8 处 `withContext(io)` 改 `withContext(ioContext)`。测试里两个**都要设**：
> ```kotlin
> it.testingIoDispatcher = StandardTestDispatcher(testScheduler)          // 让时钟推得动
> it.testingBlockableIoDispatcher = StandardTestDispatcher(testScheduler) // 让续体不跳线程
> ```
> 为什么 `StandardTestDispatcher` 能内联：它**不是** `TestDispatcher` 的子类，
> `runTest` 收编共享调度器时只认 `TestDispatcher` ⇒ 这个实例不被收编 ⇒ 退化成
> "可捕获的惰性调度器"，在已持锁线程上 `isDispatchNeeded == false` ⇒ 当场内联，
> 且 `withContext` 后拦截器仍是它自己 ⇒ 续体恢复时不碰 `Dispatchers.Main`。
>
> 🔴 **别用 `Dispatchers.Unconfined`** 顶替：它不可捕获，真被 `delay` 排上来会抛
> `UnsupportedOperationException`，只是把"偶发"换个地方偶发。
>
> **门禁**：`ui.unlock.*` 29 例 × 连跑 5 次 = 5/5 绿；全量 **388 例 0 失败**；
> detekt `--rerun-tasks` ✅；8 个静态检查 ✅；索引校验 ✅；
> **CI run `39141070281` success**（sha `d32657c`，硬门禁日志含 `> Task :app:testFullDebugUnitTest`）。
>
> **判据（写死）**：虚拟时钟管的是**时间**，不是**线程**。凡是拿"注入调度器"去消灭
> `Dispatchers.Main` 竞态的，都要额外确认被测代码里有没有 `withContext(<别的调度器>)`;
> 有的话，那个调度器必须在测试里被**拦截**掉。**本地绿不算证据，看 CI 硬门禁。**

> ⚡ **2026-10-18（第二轮）：#175 已修 —— 单测"随机假红"的总闸**。
> 起因：`VaultLockManagerImplNeverTest` 改成虚拟时钟后**偶发 1–2 条红、每次红的不同**，
> 甚至出现**两条互斥断言同时红**（`非Never档_切后台_不立即锁` 报 "should not be called"，
> 同一次构建里 `非Never档_切后台满5分钟_锁定` 报 "was not called"）。
> - **根因（在实现里，不在测试里）**：`VaultLockManagerImpl` / `AutoLockController`
>   原来都在 **`init { scope.launch { … collect … } }`** 里自启收集协程。两层后果：
>   ① **构造期派发** —— `launch` 发生在构造期间，`testingIoDispatcher` 只能在构造后赋值
>   ⇒ 这一发已派到 `Dispatchers.Default`，虚拟时钟**永久推不动它**；
>   ② **无界 `collect`** —— 永不结束，`runTest` 的 idle 判据被它搅乱 ⇒ 红哪条每次都变。
> - **解法**：启动从 `init` 移出 ⇒ `startObservingVaults()` / `startCollecting()`（均幂等），
>   生产在 `VaultixApplication.onCreate` 显式调；测试顺序固定为"先注入调度器 → 再 start"。
> - 🔴 **两条不通的路（别重试）**：`scope` 改 getter（`init` 构造期就取走了）；
>   自定义 `CoroutineDispatcher` 包装做"延迟解析"（`delay` **永不恢复**：
>   `timer started` 5 次 / `fired` **0 次** —— 虚拟时钟要求上下文里的 dispatcher **就是**
>   `TestDispatcher` 本身）。
> - **虚拟时钟三条规则**（同一天连踩四轮才定）：`settle(m)`（反复 `runCurrent()` 直到
>   **定时器计数稳定**）建钟不推钟 → 断言"不该锁"用 `advanceTimeBy(N-1)` + `runCurrent()`
>   （**不能**跟 `advanceUntilIdle`，它会补齐那 1ms）→ 断言"会锁"用 `advanceTimeBy(N)` + `advanceUntilIdle()`。
>   ⚠️ **`advanceUntilIdle` 不能用来表达"时间过去了多少"**，它把**所有** `delay` 推到尽头。
> - 新增 `internal fun timerCountForTest()` 作为 `settle` 的收敛信号（判据是"计数稳定"，
>   不是"跑了几轮"）。顺带删掉测试里一个**从未使用**的 `StandardTestDispatcher` 字段。
> - **`AutoLockController` 此前零测试**，现补 6 条（`AutoLockControllerTest`）。
> - 门禁：`:app:testFullDebugUnitTest --rerun-tasks` 真实执行 **388 例 0 失败**
>   （原 377 + `AutoLockControllerTest` 6 + `VaultLockManagerImplNeverTest` 8→13 的净增 5）；
>   `security.*` 整包 **19 例 × 连跑 6 次全绿**；detekt ✅（`--rerun-tasks` 真跑，非 UP-TO-DATE）；
>   8 个 Python 静态检查全 OK；
>   **变异验证 6 组全部精确命中**（红的条数 6/3/2/3/1/1，每组都能一句话解释范围）。
> - 另一个产品缺陷（同轮早先）已修：`AutoLockController.lockAllNow()` 原来转发
>   `lockVaultForCurrentUser`（**只锁活动库**），与"清零**全部**库"的文案不符 ⇒
>   改转发 `VaultLockManager.lockAll()`。⚠️ 该入口目前是**生产零调用**的死链（见其 KDoc）。
> **下一步仍是两个真机项**（OneDrive `If-None-Match: *` 的 201/412、W6 KeePassXC 互操作）。

> ⚡ **2026-10-18：#174 已修，CI 硬门禁转绿**（commit `214ea18` / run `39085733395`，
> `> Task :app:testFullDebugUnitTest` 真实执行，非 `FROM-CACHE`）。
> 上一轮 #173 记的"CI 单测步红"终于坐实到具体一例：
> `UnlockViewModelTest > candidateVaultsExcludeTheTargetItself`，报
> `Dispatchers.Main was accessed when the platform dispatcher was absent`。
> - **机理**：`viewModelScope.launch { withContext(Dispatchers.IO) {…} }` 的**续体回主线程**
>   是真线程 resume，而 `@After { Dispatchers.resetMain() }` 已把替身卸下 ⇒ **竞态**。
>   ⚠️ `runTest` 自带的 `TestMainDispatcher` **只护虚拟调度器**，护不住真线程那条路。
> - **解法**：`UnlockViewModel` 加两个 `internal var`（生产恒 null）——
>   `testingScope`（默认 `viewModelScope`）、`testingIoDispatcher`（默认 `Dispatchers.IO`）；
>   协程挂载点收敛到 `scope` / `io` 两个 getter。
> - 🔴 **四条"更标准"的路全不通**（都实测）：构造参数默认值里读 `this` ⇒ `Cannot access '<this>'`；
>   注入 `@HiltViewModel`（`Lazy`/`Provider`/`dagger.Lazy`）⇒ `prohibited`；
>   可空构造参数 ⇒ `CoroutineScope cannot be provided`；**构造后赋值的 `var` 字段** ⇒ ✅。
> - 🔴 **测试侧两坑**：① 只换 scope 不够（IO 段仍在真线程，实测 6 例报"没收到事件"）；
>   ② `advanceUntilIdle()` 会把 `Channel` 缓冲快进掉 ⇒ "事件已发出、`prompts` 仍空"
>   （改用 `runCurrent()` / `advanceTimeBy(1)`）。
> - 本机 **377 例 0 失败**；变异验证 4 组各自精确命中（含"结构门禁"
>   `overrideScopeIsActuallyUsedByTheViewModel`，用 Job 同一性钉"注入真的生效"）。
> **下一步仍是两个真机项**（OneDrive `If-None-Match: *` 的 201/412、W6 KeePassXC 互操作）。

> ⚡ **2026-10-17：#172 已修**（OneDrive 无基线写入 = 无条件覆盖；分片上传
> uploadSession 请求体一直是**非法 JSON**）。全量门禁 **1155 例 0 失败**
> （CI 同款 8 任务 + `--rerun-tasks`），变异验证 6 组全中。
> - **接口**：`KdbxFileSource.write` 加**独立参数** `failIfExists`。
>   ⚠️ 不要"用 `expectedVersion == null` 推断只创建"——null 是"没有基线"，
>   failIfExists 是"存在就是错"，**两件事**。`force` 同为独立参数，同构。
> - **来源**：SAF 在 `openOutputStream("wt")` **之前**拦；WebDAV 强制丢基线发
>   `If-None-Match: *`；OneDrive 简单上传发 `*`、分片上传用会话体
>   `conflictBehavior: "fail"` 且**不带 `ifMatch`**、并补**写后读回**。
> - **第二颗雷**：`createUploadSession` 手拼 `"@microsoft.graph.conflictBehavior":fail`
>   （值漏引号）⇒ **> 2MiB 的库在 OneDrive 上从来没存进去过**（小库走简单上传不经过）。
>   改 DTO + `Json(explicitNulls = false)`。**判据：手拼结构化文本（JSON/XML/SQL）
>   是可控缺陷源，能交给序列化器就交出去。**
> **下一步 = 真机验 `If-None-Match: *` 的 201/412**（Graph 官方文档只字未提该头语义）
> + W6 真机互操作验收。两者都**需真机，本环境不可执行**。
>
> 🔴 **同日第二课（#173）：本地"1155 例 0 失败"是假的，CI 单测步是红的。**
> 原因：沙箱 `~/.gradle` 的 **build-cache 命中也算"成功"**，`FROM-CACHE` 会跳过执行
> 却把**旧 test-results** 恢复出来 ⇒ 我上一轮立的"XML mtime 晚于启动时刻"判据失效。
> **判据改成：任务行必须是 `> Task :x:test…`，不能是 `FROM-CACHE` / `UP-TO-DATE`。**
> 用 A/B 实验隔离（移走改动⇒CI 绿；注入 `fail()`⇒CI 红）确认 **CI 那一行是真门禁**。
> ⚠️ 别用 `gh cache delete` 清 CI 缓存来"消毒"——那会把一次隔离实验变成两次未知。

> ⚡ **2026-10-16：W5 JSON⇄KDBX 双向转换落地**（`6ae9bcd`，CI run `38927742868` success，
> 全量门禁 **1142 例 0 失败**）。施工单 `Docs/progress/json-kdbx-lossless-conversion.md`
> 的 W1–W5 全段闭合：
> - **转换器本体** `KdbxConversionEngine`（`data/kdbx`，`build`/`read` 双向；
>   写完即 `KdbxRoundTrip.verify` 自检，不过 ⇒ `WriteCheckFailed`）；
>   **无会话转换**（不登记 `KdbxSessionStore`，纯函数式进/出字节）。
> - **入口 UI**：设置 → 导入/导出 → 页首「JSON ⇄ KDBX 转换」行 → `ConversionScreen`
>   （SAF 两读两写 + 预览计数卡：条目/分组/TOTP/通行密钥/降级/跳过）。
>   密码纪律延续：预览后即弃文件密码、转换成功后即弃输出密码。
> - 关键设计：**`VPX_BW_ID`/`VPX_BW_TYPE` 旁路字段只写不读回领域模型**
>   （Bitwarden 的 id/type 在 KDBX 里无标准位，只由转换器直接消费）；
>   Bitwarden folder id 必须合法 UUID ⇒ `remapFolderIds` 换新 UUID；
>   KDBX 无 Card/Identity/SshKey 承载 ⇒ **载荷守卫 + `typeDegradedCount` 告知**
>   （降级必须可见，不静默）。
> - `ConversionViewModelTest` 11 例（含变异验证：抽掉「预览后清空文件密码」
>   精确只红 1 例）。
> **下一步 = W6 真机互操作验收**（KeePassXC / KeePassDX 打开 Vaultix 写的库）——
> **需真机，本环境不可执行，是唯一遗留项**。

> ⚡ **2026-10-16：A4 + A6 已修，A5 提案驳回**（`3fc8649` A4 / `d50463f` A6，
> CI `38894211052` + `38898302875`，全量门禁 **1108 例 0 失败**）。
> **A4**：KDBX 无类型概念 ⇒ 读侧纯启发式 ⇒ **空笔记的安全笔记**（表单备注可空）
> 三字段全空 ⇒ 往返后**静默翻成登录**。修复沿用 A1 的 `VPX_` 工具区模式
> （`VPX_SECURE_NOTE`），读侧**三态**（marker 优先 / 缺失回落 / 显式假值信任标记）
> ——「没有标记」≠「标记为非笔记」，压布尔会让第三方库笔记一夜全变登录
> （#121 同款事故，本篇第二次撞墙）。
> **A6**：Title/UserName/Notes 无条件写 `Plain` ⇒ 第三方库里受保护
> （`Protected="True"`）的用户名/备注**落盘即丢保护属性**。修法
> `protectedAware` 泛化原 `passwordValue`，四字段一律走它。
> **A5（时间戳）查证后驳回**：kotpass 0.13.0 的 `Entry` 默认
> `TimeData.create()`、`Group.modifyEntry` 内部刷 lastAccess/lastMod、
> `moveEntry` 刷 locationChanged —— **「代码里没写」≠「没发生」**。
> 新增坑 **#169 / #170**。下一批仍是 W5（转换器本体 + 入口 UI）。

> ⚡ **2026-10-14：KDBX × Bitwarden 边界审计五条全部修完**（`57fc416`…`2c5f3eb` 六个提交，
> CI run `38495406982` success，报告实证 **1096 例 0 失败**）。修的四条产品边界 +
> 一个早已立案的 #161，全部来自 `Docs/progress/kdbx-bitwarden-boundary-audit-2026-10-12.md`：
> **B6** 通行密钥写回（`ItemRepositoryImpl` 双会话校验 + KDBX 感知 `loadItem`）·
> **A1** 收藏读写（`VPX_FAVORITE` 工具区，读宽松真值 / 写未变不动）·
> **A2** 文件夹可见（`observeFolders` 按库分流 + `KdbxSessionFlow` 触发器）·
> **A3** 表单裁剪 + 卡包门控 + **仓储硬闸**（导入路径绕开表单，`requireSupportedType`）·
> **B5** 「忘记 PIN？」（#161，不验任何东西清信封，PIN 不是根凭据）。
> 新增坑 **#164–#167 + #168**（ISSUES 索引已核对一致）。共同形态判据：
> **「底层能力写好」≠「链路通」** —— 五条里有三条是中间层断（writer 有没人调 /
> mapper 有没人读 / 表单裁了没人拦导入）。下一批 = W5（转换器本体 + 入口 UI，仍待做）。

> ⚡ **2026-10-12：CI 单测硬门禁转绿**（`f470bc4`，run `38235068372` success）。修的是一处**测试缺陷**
> 而非产品 bug：`ItemRepositoryImplTest` 两例 —— `restoreItem_clearsDeletedDate` 漏 stub（`MockKException`）、
> `allSixWritePathsGoThroughAtomicDao` 的 ④ 恢复对象不在回收站（踩 `ItemRepositoryImpl.kt:365` 守卫）+
> 其末尾 `deleteByIds` 计数应为 2（该用例此前从未跑完 ④ 之后）。报告实证 **1073 例 0 失败**。
> ⚠️ 教训：**一条测试可能因前半段异常而从未执行到后半段断言**（详见 `SESSION-2026-10-12.md`）。
> `HANDOFF-2026-10-05` 的剩余工作不变。

> ⚡ **2026-10-07：JSON⇄KDBX 无损互转 W1 + W2 + W3 + W4 已落地**（`101fe0c` / `569c398`
> / `4ea40f5`+`15ff84b` / `d141538`）。W3 结论 = **TOTP 往返本来就通**；
> W4 新增 `KdbxToolFields`（`VPX_` 工具区单一真源）+ `KdbxToolFieldsRoundTripTest`27 例。
> W4 之前多 URL 是**静默丢数据**（取 `uris.firstOrNull()`，改标题就抹掉其余网址）。
> ~~**下一步 = W5**（转换入口 UI + 转换器接口本体），W6 真机互操作待真机。~~
> ✅ **W5 已于 2026-10-16 落地**（见 §9 顶部），W6 真机互操作待真机。
> 🔴 **CI 判定纪律（见 SESSION-2026-10-05/07 §九、§十一）**：单测步骤标了 **non-blocking**，
> 所以「`gh run` 显示 ✓」**不代表单测通过** —— 必须确认
> `:data:kdbx:testDebugUnitTest` 那一行是真实执行（非 FROM-CACHE）且无 FAILED。
> **本轮已因此被"假绿"骗过两次**（`37609359341` 编译失败、`37610581524` 6 例断言失败，
> 两次顶层都是 ✓）。取失败详情用 `gh api .../artifacts` 里的 `debug-unit-test-reports`
> （比翻日志快，能直接看到 expected/actual）。
> 🔴 **沙箱无 Android SDK 的三条铁律**：① **符号存在性要实测**（下载 jar 用 `javap` 看，
> 别凭印象写API）；② **没有证据不改解析语义** —— 缺的不是胆子而是证据；
> ③ **语法糖能不能用看 Kotlin 的运算符约定，不看 JVM 有没有那个方法** ——
> `EntryFields` 有 `put`（javap 可见）但 `apply { this[k]=v }` 照样编译失败。
> 🔴 **往返型缺陷只能靠"写→读"完整往返抓出来**（见 SESSION §十一 bug 1）：
> 写侧断言"字段写进去了"、读侧断言"值读回来了"，两条都成立才叫往返 ——
> 只验一侧的缺陷（自己写自己读、格式不对称）**两侧各自都对、接起来不对**。

> ⚠️ **逐轮历史不在这里维护** —— 与本文件早期做法不同：历史只保留一处，避免两处不同步。
> 最新待办 → [`Docs/progress/next-steps.md`](../Docs/progress/next-steps.md)（最新在顶部）·
> 逐轮流水 → `.ai/SESSION-YYYY-MM-DD.md` · 坑 → `.ai/ISSUES.md`（索引，正文在 `issues/`）·
> 性能专项 → [`Docs/progress/perf-plan.md`](../Docs/progress/perf-plan.md)。
> **同一天接着报「Firefox 通行密钥取不到」⇒ 已定性为浏览器缺陷，不是本仓库问题**：
> 日志里 `CP ` 埋点 **0 条**（用户已在 Firefox 上操作过）⇒ **系统压根没来问**，
> 而非"我们筛不出候选"——两者用户侧现象**完全一样**，只能靠入口埋点区分。
> Mozilla 官方 [Bugzilla 1862132](https://bugzilla.mozilla.org/show_bug.cgi?id=1862132) 下
> m_kato 原文：「**只在 `Discoverable Credential` required 时才走 Credential Manager**」
> ⇒ Firefox 默认 mediation 走自己那套**只支持 Google Password Manager** 的旧对话框，
> 第三方 CP 一个都看不到（Proton Pass / 1Password 同样复现，见 bug 1907531）。
> ⚠️ **不要为此改匹配逻辑**（那会把对的代码改坏）：Vaultix 侧前置条件已全部核实齐备。
> 绕行：Firefox `about:config` 开 `security.webauthn.webauthn_enable_android_fido2.residentkey=true`
> （不保证）· 或同站点改用 Chrome。本轮只补了 CREATE 侧缺失的入口埋点。
> ⚠️⚠️ **别读成「Firefox 上一律不可用」**（我第一版结论就写过头了，已订正）：
> Mozilla 官方论坛同一用户追记「**在别处生成**的通行密钥**可以**在 Firefox Android 上**登录**」，
> 而那帖主题是**注册（create）**坏了（只给 "This Device"、存进 GPM）。
> ⇒ **认证(get) 可以是通的 / 注册(create) 对第三方 PM 明确坏**；
> 是否走 CM 取决于请求的 mediation / residentKey。
> 下次遇到先问清是注册还是认证，再看 `CP GET` / `CP CREATE` **哪条入口埋点**被触发
> （这次两条都没触发）。详见 `03` 篇 **#163**。

**JSON⇄KDBX 无损互转 · W2 通行密钥写回（2026-10-06 · 最新）**

> 施工单 W2（= HANDOFF 第 5 项「通行密钥写回 KDBX」）已落地 `569c398`，CI `37348461753` 全绿
> （detekt + 全仓编译 + 单测 + 编码门禁）。
> - `KdbxPasskeyCodec` 补 `RP_NAME` / `USER_DISPLAY_NAME` / `CREATION_DATE`，字段集与
>   bw2keepass 并集 = **10 键**；新增 `fromCredential(credential, index)`
> - 多凭证：第 0 条不带后缀、第 n 条带 `_n`；`isPasskeyFieldName` 改**后缀感知**
>   （否则第 2 条凭证会漏进自定义字段 ⇒ 详情页明文展示私钥 PEM）
> - 读侧 `KdbxItemMapper.toPasskeyFields` → `passkeyCredentials` 返回**列表**
>   （此前「一条登录挂 2 个通行密钥」只读得出第一条 = 静默丢凭证）
> - 写侧 `KdbxItemWriter.applyPasskeys` 走**专属通道**（通行密钥区是保留区，不能进
>   `applyCustomFields`）；私钥 PEM / credentialId / userHandle 强制 `Encrypted`
> - ⚠️ **已知不保真**：`FLAG_BE` / `FLAG_BS` 领域模型无此二位 ⇒ 写回取默认值
>   「可备份且已备份」，第三方写入的 `false` 会被改写。W6 真机需确认 XC/DX 无副作用。

**JSON⇄KDBX 无损互转 · W1 落地（2026-10-05 后续接力）**

> 施工单 `Docs/progress/json-kdbx-lossless-conversion.md` 的 W1（修现网数据毁 bug）已落地：
> 新增 `KdbxFieldKeys` 单一真源（标准五键 + `isReserved` 大小写折叠判区，合并标准/OTP/通行密钥）；
> `KdbxItemMapper.customFieldsOf` 改走 `KdbxFieldKeys.isReserved`；`KdbxItemWriter.applyCustomFields`
> 写回前对保留键 `return@forEach` 跳过（R1 铁律）—— 名为 `Title`/`title`/`Url` 的自定义字段
> **不再覆盖**库名/URL（kotpass 大小写敏感，探针实测真覆盖）。
> 白名单 `"URL"` → `"Url"`（与写侧 `BasicField.Url.key` 对齐，漏判的 URL 标准键已修）。
> 单测 `KdbxFieldKeysTest` + `KdbxReservedFieldTest` 落到 `:data:kdbx` test 源集。
> ⚠️ **沙箱无 Android SDK ⇒ 未本地编译**，靠 CI（`:data:kdbx:testDebugUnitTest`）+ 真机验；
> 静态自检 `check_compile_smells` / `check_orphan_strings --gate` 全绿。
> W2–W6 待做（通行密钥写回 / TOTP 往返 / VPX_ 多值 / 转换入口 / 真机互操作）。

**Firefox Android 匹配不到密码条目 · 已修（2026-10-04 · 最新）**

> 用户报「输入框能看到 vaultix 的提示，但密码条目无法匹配出来」。**日志一行定案**：
> `fields=3 user=true pass=true targets=2`（解析层正常）而
> `candidates=216 matched=0 datasets=0`（候选全被过滤成 0 分）⇒ 病根是**拿不到域名**。
>
> **⚠️ 本轮最值钱的不是代码，是判读教训**：
> 日志里「空串」8 次、「真 null」5 次。只盯空串那 8 次，会得出「补个 `isNotBlank()`
> 就好了」的错误结论 —— 而真 null 那 5 次 `?:` 根本不短路、兜底**已经算了确实算不出来**。
> 修掉空串，那 8 次只会退化成和另外 5 次一样的 null，**用户症状一点没变**。
> ⇒ **「分布统计」比「典型样本」更能定性**：同一份日志里两种形态的**出现次数对比**，
> 直接决定了「这是一处判据写错」还是「整条路在平台层就不通」。
>
> **两层根因（只修一层不够）**：① 空串穿透（`?:` 只对 null 短路，GeckoView 登录页上报
> 的正是空串；同仓 `inheritWebDomain` 早有防御，唯独页面级收集处漏了 ⇒ **同一件事两处
> 判据不一致**）；② **兜底全落空**（13 次 `fallback=` 全 null）。
> **外部佐证**：Bitwarden Android 在 Firefox Android 上**同样**做不了域名匹配
> （其 issue #5720 / 社区 #19444）⇒ **GeckoView 平台限制，不是我们判据写错**。
>
> **修法两刀**：新增纯函数 [WebDomainResolver]（`ViewNode` 是 `@SystemApi` 抽象类、
> 构造不出实例，逻辑不抽出来没法 JVM 单测）+ **无域名降级**（`orderWithoutDomain`：
> 拿不到域名时不再返回 0 条，改为按「收藏优先 → 名称升序」给全部候选，**对齐
> Bitwarden 的搜索兜底语义** —— 它也没让用户挡在门外）。
> ⚠️ 降级**不关闭**包名匹配闸门：浏览器里缺域名时包名是浏览器自己的，拿它匹配会弹出
> 「浏览器身份」的无关条目，那才是真的错条目。
>
> **测试有效性已反向验证**：把 `isNotBlank` 改回原样后 **6 例变红**，报错精确复现
> `effective=` 空串 ⇒ 闸门是真的，不是摆设。
> **遗留**：`AutofillCredential` 没有 `lastUsedAt` ⇒ 降级只能按收藏+名称排，
> 不能按「最近使用」（本条最大的可改进点）。详见 `06` 篇 **#162**。

**本地批量巡检 + 判读孤儿串（2026-10-04）**

> 工具 `bash .ai/tools/typecheck_kotlin.sh --all`：**无 SDK 也能批量真编译 + 真跑全仓单测**
> （本次 8 个模块 / 97 个测试文件 / 28 个包组，17 组全绿约 727 条用例）。
> ⚠️ 它的 ✅ **不能读成"全仓测试通过"** —— Compose 编译、KSP 代码生成、
> 依赖 `android.jar` 空注解的写法、需要真实 Android 运行时的桩方法，**全都查不了**（见 #145.7）。
> 本轮**没发现需要修的代码缺陷**；价值在于把"没发现"从"没查"变成"**查过且有明确边界**"。
> **纪律 6**：改动可能影响别处时跑 `--all`（按包隔离，一个包编不过不牵连其他包）。
>
> **顺带结掉三条待定项**：`removeVaultFromScope()` 零调用方 → **已删**
> （范围勾选向导早已整段删除，"取消勾选"不可能再触发，留着就是死逻辑）·
> `isForeground` 无消费者 → **已定保留**（⚠️ 它是 `val ... asStateFlow()` 的**公开 API**，
> `check_orphan_state.py` 只抓"赋值后零读取"，**抓不到它 ⇒ 门禁报 0 个 ≠ 没人用**）·
> #88「仅在本地」→ **已核实过期**（`e724757` 早已入库）。
>
> 🔴 **新缺陷 #161（本轮挖出）**：「PIN 忘记后可在设置里重设」是**界面上的承诺**，
> 但 `QuickUnlockDialogs.kt:167-178` 两处都是 `togglePin`（已启用时走关闭流程），
> 全仓库 `app/src/main` 下 `change_pin` **零命中** ⇒ **用户按指引找不到任何入口**。
> 零引用的 `quick_unlock_action_change_pin` / `pin_change` / `pin_mismatch` /
> `pin_too_short` 正好是这个流程缺的文案 ⇒ 补入口时一并接上。
> **判读孤儿串 118 条已做完**（见 `06`篇 #126「判读结论」）：本轮**一条都没删**，
> 但顺带挖出 #161。**教训**：零引用串里可能藏着"承诺了但没实现"的证据 ——
> 顺着它去看"它本该在的地方"，比删掉它值钱得多。

**双库健康度审计 批次 A（2026-10-02 · 最新）**

> 问题面："Bitwarden / KDBX 两条腿各自健康吗" ⇒ 通读审计找出 **#153–#159**（#160 是副产品）。
> 共同点：**每一个都向用户报了"成功"**。⚠️ 这七条里最贵的一句纪律：
> **表达"改动有没有真的发生"的标志位，默认必须取"没发生"**（#157 —— `applied = true` 的默认值
> 把"没做存在性校验"这条缺省路径变成了成功）。
> 另两条同源：注释写"由某某兜底"时**当场确认那个调用点存在**（#158，#147 同款的第二次发作）·
> `catch` 之后返回兜底值，要反问"这值会不会被认成有效结果"（#153 的恒定 `000000`）。
> 索引账务：发现 `ISSUES.md` **第四次漂移**（四种形态），新增 `.ai/tools/check_issues_index.py`
> 全量重数 ⇒ **七篇已校到「索引 = 题注 = 正文」**。

**（前一轮）多库锁模型定稿（2026-09-29 深夜）** —— [`.ai/decisions/多库锁模型-定稿.md`](./decisions/多库锁模型-定稿.md)

> 用户指令：「两个库互相解锁/加锁逻辑混淆，头大，先完善逻辑再动手」→ 真机取证 → 逐项拍板 → 定稿。
> **一句话模型**：一栋房子，一扇门，N 个房间；任何「锁」只动自己那一层。
> 实测铁证：BW 33ms vs KDBX 3.58s（房间信封 payload 不对称：派生 key vs 主密码）；
> 秒切已证（零认证）；「又要验证」真凶 = Honor `iAwareF` 杀后台，非锁屏钩子（Bitwarden 源码核证）。
> 决策 D1–D7（D4 暂缓）；新坑 #131–#134；**下一批 = 定稿批次 A（D1→D2→D3→D5）**，
> 随后 B（KDBX 信封提速）、C（管理页 UI）。
> 取证与拍板过程：`SESSION-2026-09-29.md`「深夜轮」。

**（前一轮）快速解锁「房子化」批次 5.1/5.2 + 收尾（2026-09-29）**

> `4fd6eed`（十八文件，已 push main）· `5a65a75`（清理 2.9G）· 变异验证干净通过（#129/#130 已修）·
> 亮屏补偿真机验证由深夜轮补完。细节：`SESSION-2026-09-29.md` 各节 · 工作单 `quick-unlock-house-rework.md`。

**（更早 · 2026-10-01）KDBX 网盘全链路收口 + `v0.8.0` 发布**：全链路三洞（#147 占位实现 /
#148 基线提前推进 / #149 `stat` 在 `read` 之后）+ 新建<｜hy_place▁holder▁no▁813｜> 入口（#150/#151）⇒ 同日发布 `v0.8.0`
（release 流水线 25/25 success）。⚠️ **观感改动一路由 CI 通过代替人眼，从未被任何人看过**。

**（更早轮次）**：见下方 §10 历史轮次索引 —— 细节一律不从本文件推，去对应 `SESSION-*.md`。

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
