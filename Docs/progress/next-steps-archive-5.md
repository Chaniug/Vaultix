# 下一步任务清单 · 归档 5/6

> **历史归档，勿通读。** 覆盖 2026-09-12 ~ 2026-09-11 的已完成记录。
> 入口与当前待办见 [`next-steps.md`](./next-steps.md)。
> 拆出于 2026-11-10：原文件 3592 行 / 280 KB，远超 §7.1 的 300 行上限，
> 且真正的待办被埋在 96% 位置。归档按时间倒序，**只在需要追溯时检索**。

---

> ## 【已归档】第三十七轮（2026-09-12）：阶段 2 收尾 —— **活跃库真源收敛 + 门禁治理**
>
> 承接上一轮「修复，然后把 bastion 的 ui 都准备开始搬过来」，本轮把迁移文档里
> 「★ 全局活跃库真源」那一格**整格做掉**，并顺手清掉三处 detekt 超标。
>
> **① 活跃库收敛（7 处消费点，全部只读 `ActiveVaultStore`）**
> | 文件 | 改动 |
> |---|---|
> | `session/ActiveVaultStore.kt` | 新增 **`resolve()`**：挂起、每次真实重算（不依赖异步首帧），取值规则抽成 `pick()` 供流与重算共用 |
> | `autofill/VaultixAutofillService.kt` | `collectCandidates` 入参从「全部 unlocked」改为 `singleActiveVault(unlocked)`；日志补 `active=` |
> | `passkey/VaultixCredentialProviderService.kt` | 同上（`sources` 传 `passwordEntries` / `resolvePasskeys` / `collectStoredRpIds`） |
> | `passkey/PasskeyCreateActivity.kt` | 回写目标只剩活跃库（下拉仍显示库名，**不可选到别的库**） |
> | `autofill/save/AutofillSaveViewModel.kt` | `resolveTarget` 从「任取一个已解锁库」改为活跃库 |
> | `autofill/shortcut/ManualFillViewModel.kt` | 从「combine 聚合所有库」改为只订阅 `activeVaultId` |
> | `settings/SettingsViewModel.kt` | `passkeyCount` 改为只数活跃库（与填充侧口径一致） |
>
> **② Tab 数据改为流驱动（切库后自动跟随）**
> `ItemsViewModel` / `TotpCodesViewModel` 的 `vaultId` 原先是**构造期一次性取值**：
> 设置页切库后主界面内容不会变，等于「切换」只改了填充目标、UI 却撒谎。
> 改为 `vaultIdState: StateFlow<String>`（路由参数固定 / 否则跟随活跃库），
> 条目、文件夹、同步状态、库名全部 `flatMapLatest` / `combine` 跟随。
>
> **③ 设置页「库管理」入口**：`VaultSection` + `ActiveVaultDialog`，只列已解锁库；
> 新增 `group_vaults` / `settings_active_vault` / `settings_active_vault_none` /
> `settings_active_vault_locked_hint` 四条字符串。
>
> **④ detekt 门禁治理（不豁免、改结构）**
> - `CardBrandLibraryLogo.kt`：`libraryLogo()` 538 行超 `LongMethod ≤150`
>   → 10 个品牌各提为 `private val *_LOGO`，`when` 缩到 13 行（`else -> null`）；
> - `VaultixApp.kt`：主函数 168 行 → 导航图拆成 `vaultEntryGraph` / `settingsGraph` / `itemsGraph`；
> - `SettingsScreen.kt`：主函数 169 行 → 拆出 `SecuritySection` / `OthersSection`。
> 全量复扫：27 个改动文件 **0 处超 150 行 / 0 处文件超 1200 行 / 0 行超 120 宽**。
>
> **⑤ 新增 `ActiveVaultStoreTest`（5 例）**：沿用存档库 / 存档库已锁退化 / 多库无存档取
> 字典序最小 id（结论稳定）/ 全锁返回 null / `select` 同步可见 + 异步落盘。
>
> ⚠️ **沙箱限制（仍未跑构建）**：GitHub / Maven Central / Google Maven 不可达
> （fake-ip `198.18.0.x`）+ 无 Android SDK → 本轮同样**未经 `compileFullDebugKotlin`
> + `detekt` + 单测门禁验证**，改以人工核对 + 脚本快检（括号平衡 / 行宽 / 函数长度）替代。
> **请在本地先跑一次三件套再进阶段 3。**
>
> ---

> 更新于 2026-09-12（第三十六轮）。**① 修复 preview「全新安装永久转圈」；② 开工 Bastion UI 搬迁（阶段 1 收尾 + 阶段 2 骨架）。**
>
> 用户：「最新的 github 预览版有问题，我卸载老版本后，新版本进不去，一直转圈加载」→
> 「修复，然后把 bastion 的 ui 都准备开始搬过来」。
>
> ## ① 启动死锁（P0，preview 版阻塞性）
>
> **根因链（4 跳，缺一不可）**：
> 1. `RootNavViewModel` 把「**没有任何库**」判成 `VaultLocked`（旧判定只有两态）；
> 2. `VaultixApp` 用 `remember { resolveStartDestination(state) }` **固化首帧结论**，
>    而首帧 Flow 初始值恒为 `VaultLocked` → `startDestination = UnlockEntryRoute`；
> 3. `UnlockViewModel` 无库可解析 → `vaultId` 恒空 → `state.vault == null`；
> 4. `UnlockScreen` 在 `vault == null` 时 `CircularProgressIndicator()` + `return@Column`
>    → **全屏只有一个转圈，且没有任何出口**。
>
> 卸载旧版 = 库数据清空 = 全新安装 → 必然命中。用户看到的「一直转圈」就是第 4 跳。
>
> **修法（4 处，逐跳掐断）**：
> - `RootNavState` 补两态：`Splash`（首帧未到，**初始值为它**）与 `Onboarding`（一个库都没有）；
>   「无库 ≠ 锁定」从此在类型层面成立。
> - `VaultixApp`：`startDestination` 固定为 `SplashRoute`（只承担「未知」语义），落点改由
>   `LaunchedEffect(rootNavState)` 状态驱动 + `navigateToRoot()` 换栈；`routedOnce` 保证
>   「已解锁」只落一次（否则解锁后会被换栈踹回列表）。⚠️ 已去掉 `lockEpoch` 分支——
>   全锁时状态必回 `VaultLocked`，由状态分支统一收敛，比「代次计数」更可靠。
> - `UnlockViewModel.UiState` 增 `noVaultToUnlock`：`vault == null` 分不清「首帧未到」与
>   「确认没库」，现在后者必为真，`UnlockScreen` 立刻走 `onNoVault` 逃生（**不再有转圈死角**）。
> - 新增 `RootNavViewModelTest`（4 例）锁住「无库 → Onboarding / 有库全锁 → 解锁 /
>   已解锁 → 主图 / 会话真值优先」；顺带补 `testImplementation(kotlinx-coroutines-test)`。
>
> ## ② Bastion UI 搬迁（依据 `Docs/progress/main-shell-migration.md` 方案 A4）
>
> **阶段 1 收尾**：搬 `CardBrandIcon.kt`（242 行）+ `CardBrandLibraryLogo.kt`（212 行）
> → `ui/cardwallet/`。枚举覆盖度已复核（两端 `CardBrand` 均 18 项、逐项同名），
> `when(this)` 分支零改动；`runCatchingObserved` → 标准 `runCatching`（不引 Bastion 日志体系）。
> ⚠️ **Detekt 行宽**：素材 `pathData` 最长 4720 字符 → 按「数字↔命令字母 / 负号」边界折行
> （规避 `1e-3` 指数负号），**不豁免门禁**，最长行降到 112。
>
> **阶段 2 骨架（已接线，待真机验收）**：
> - 新增 `session/ActiveVaultStore`（Hilt 单例 + `StateFlow<String?>`）：**单一活跃库**真源，
>   持久化复用既有的 `VaultixPreferences.defaultVaultId`（对齐 Bastion `KEY_ACTIVE_VAULT_ID`）；
>   不搬 `UnifiedCategoryFilterSelection`（多后端产物，见迁移文档 §0.3）。
> - 新增 `MainShellRoute`（**不带 vaultId**）+ `ui/shell/MainShellScreen`：4 Tab
>   （密码 / 验证码 / 卡包 / 设置）+ 中央「+」，复用上一轮已搬好的
>   `AdaptiveMainScaffold` / `VaultixBottomDock`；宽窄屏按 600dp 分界。
> - 「+」按 `VaultixNavItem.addTarget` 分发（`addRequest` 计数触发，设置 Tab 回退新建密码，
>   对齐 Bastion `else -> handlePasswordAddOpen()`）。
> - `ItemsViewModel` / `TotpCodesViewModel`：`vaultId` 改为「路由参数 ?: 活跃库」，
>   并在带参数进入时**反向登记**活跃库（保证 autofill 后续读到同一个库）。
> - 三个既有页加 `embedded` 模式（隐藏返回键与 FAB）+ `addRequest`；新建卡包 Tab
>   （内容源 = 活跃库内 `Cipher type=3`，列表打码保留末四位）。
> - 解锁 / 点已解锁库 → 直接进 `MainShellRoute`（对齐 Bastion「解锁即进主界面」）。
>
> ### 本轮待办（下一轮接着做）
> - [ ] **真机验收**：全新安装 → 应直达库列表（不再转圈）；解锁 → 直接进主界面 Tab。
> - [ ] 阶段 2 剩余：autofill / CP / `PasskeyCreateActivity` 的候选来源改为**只取活跃库**
>       （见迁移文档 §阶段 2「★ 全局活跃库真源」，这是「条目不重复」目标的关键一刀）。
> - [ ] 设置页补「库管理」入口（切换活跃库）。
> - [ ] 阶段 3 观感：Tab 切换转场动画、卡面样式细调。
>
> ### ⚠️ 沙箱限制（本轮未跑构建）
> 沙箱内 GitHub / Maven Central / Google Maven 均不可达（域名被解析到 fake-ip `198.18.0.x`），
> 且无 Android SDK（SDK 下载源不通）→ **本轮改动未经 `compileFullDebugKotlin` +
> `detekt` + 单测门禁验证**。已改为：人工逐处核对符号/导入 + 括号平衡快检 + 行宽扫描
> （全部 ≤120）。**请在本地先跑一次三件套再进阶段 3。**
>
> 参考：仓库经 `https://gh-proxy.com/https://github.com/...` 镜像克隆成功（main @ `526a6f6`）。

> 更新于 2026-09-11（第三十五轮）。**【最新】真机 adb 联调打通 + 抓出「文档地雷」：**
> **CP 密码能力的方向已被 `f815ab2` 反转，但四份文档仍写着「双能力供应」。**
>
> ⚠️⚠️ **先读本条再读下面的历史区块**：`credential_provider.xml` **故意不声明**
> `TYPE_PASSWORD_CREDENTIAL`（`f815ab2`，2026-09-11 00:03；理由见 `decisions.md` 末行）。
> 但以下三处**仍按反转之前的口径写着「双能力」**，属**已过时表述**，勿据此改回：
> ① 本文件第二十四轮 ③「密码凭据供应（8ba40d9）」整段；
> ② 本文件「★ 最高优先」区 ① 的 capability 行与 ③ 的叙述；
> ③ `.ai/MEMORY.md` 第 555 行。
> ⇒ **接力者若照这些段落「修」回 `TYPE_PASSWORD_CREDENTIAL`，会再次弄坏 Edge 密码填充。**
>
> **未决分歧（勿单方面拍板）**：Bitwarden 官方 `res/xml/provider.xml` **是声明双能力的**
> （`TYPE_PASSWORD_CREDENTIAL` + `TYPE_PUBLIC_KEY_CREDENTIAL`）。故 `f815ab2` 所依据的
> 「CP 密码分支任一环节失败即返回空」更像是**该分支自身的缺陷**——删能力是**绕过**而非根治，
> 与本项目「功能正确性一律以 Bitwarden 为准」的裁决规则存在张力。
> **待真机 A/B 复现后（Edge 密码填充一次 + 通行密钥一次）再定方向。**
>
> ### 本轮真机状态快照（HONOR BKQ-AN00 / Android 17 / `0.1.0-dev-24af692`）
>
> | 项 | 实测值 | 含义 |
> |---|---|---|
> | `autofill_service` / `credential_service` | 均指向 Vaultix | 两轨注册与启用**都正确** |
> | CP manifest action | `android.service.credentials.CredentialProviderService` | 旧「漏 `service.` 段」坑**未复发** |
> | 库 | 1 个 `https://pwd.vv1234.cn`（BITWARDEN） | 账号 valkjin@outlook.com |
> | 条目 | 218（217 Login + 1 Card）；`pending_ops=0` | 同步干净 |
> | 快速解锁 | `local_unlock_enabled_<vaultId>`=**true** + payload 已落盘 | **内因已解除**（不再是「开关默认关」） |
> | 自动锁定 | 旧键 `auto_lock_minutes`=**-1**，新键 `vault_timeout` 与迁移标记**均不存在** | 迁移**未触发** → 按 legacy 读 = **`Never`（从不）**。⚠️ 同一 -1 在新键语义下是 `OnAppRestart`「重启即锁」，**结论相反——必须看键名而非数值** |
> | `VaultixAutofill` 日志 | 缓冲区**零条** | 修复后**从未跑过一次**填充/通行密钥流程 |
>
> ### 本轮新增工具与方法
>
> - **`scripts/adb-log.sh`**（新增）：`connect` / `dump` / `live` 三子命令；自动探测 adb
>   （PATH → `local.properties` 的 `sdk.dir` → 常见路径）、**mDNS 自愈定位设备**、强制 `-s`。
>   解决三个固定摩擦：① adb 不在 PATH；② 无线调试 IP/端口会漂（实测手抄的 `192.168.1.144`
>   连不上，mDNS 里的真实地址是 `192.168.1.114`，**只有端口一致**）；③ 显式 connect 与 mDNS
>   会把同一设备注册成两条 → 报 `more than one device/emulator`。
>   **无线调试的权威地址 = `adb mdns services` 里 `_adb-tls-connect._tcp` 那行的末列。**
> - **日志事实**：全 App **只有一个 tag `VaultixAutofill`**（`AutofillLogger`，`BuildConfig.DEBUG`
>   门禁，dev 包有效），消息体带 `CP ` / `PK ` 前缀区分两条链路。**`dumpsys credential` 无实现**
>   （只回服务列表头）→ **CP 侧只能靠 logcat**。
> - **读应用私有状态的三个可靠手法**（无需 root，靠 `run-as`）：
>   ① `shared_prefs/vaultix_secure.xml` = 快速解锁 payload / device_id / `bw_*` 凭据；
>   ② DataStore 偏好 = `files/datastore/vaultix_settings.preferences_pb`（**不是** `vaultix.preferences_pb`），
>      protobuf，`od` 手解即可读开关与 `auto_lock_minutes`；
>   ③ Room 库 = `databases/vaultix.db` **+ `-wal` + `-shm` 三个一起拉**，本地打开前**删掉 `-shm`**
>      让 SQLite 重建（否则 WAL 不重放、只看到 `android_metadata`）。
>      ⚠️ **`fido2` 在 `encryptedPayload` 密文内 → 通行密钥条数无法从库直读**。
> - 本机注记：`git -C /d/...` 在 Git Bash 下报 No such file，MSYS 路径要写 `D:/...`。

> 更新于 2026-09-11（第三十四轮）。**【最新】修复 CI（detekt 门禁 + 被掩盖的编译错误），CI 转绿。**
>
> 用户：「拉取 github 最新的改动到本地。有 github 上的报错需要修复」。
>
> **根因两条**：① detekt 门禁违规（`MagicNumber` 命中 3 文件 + `resolvePasskeys` 圈复杂度 41）；
> ② 上一提交 `d6f3409` 引入的 3 处 `Int?` **编译错误**被「detekt 先于 compile 失败」**掩盖**。
>
> **修法**：①去魔法数字（用档位自身 minutes / 提 `BYTE_MASK` 常量）；②圈复杂度问题——
> detekt **2.0.0-alpha.6** 会把**同文件**被调私有函数的复杂度**累加**进调用方，故把 helper 拆到
> **独立文件** `passkey/PasskeyResolution.kt`；③3 处空安全（`mapNotNull` / `requireNotNull` /
> 客户端侧 `CreateCredentialRequest`）。
>
> **结果**：commit `24af692` → CI run `34590428399` **success**（含 detekt + Build Debug APK）。
>
> 另：拉取 Bitwarden / Keyguard 源码到**仓库外** `D:\Vaultix-refs\`（不纳入 git）。
>
> 更新于 2026-09-11（第三十三轮）。搜索框按 Bitwarden 标准重写，消除「输入框乱跳」。
>
> 用户反馈：「当前的 vaultix 一直在输入框，搜索框乱跳，有没有办法。按照 bitwarden 的方式修吧」。
>
> **根因**：三个带搜索的界面存在**三种互不一致的坏写法**——
> ① `ItemsScreen` 把 `OutlinedTextField` 挂在 `LargeTopAppBar` **外层**的 `Column` 里（最严重：
> 大标题栏滚动时高度在变，输入框跟着反复垫高）；② `PasskeysScreen` / ③ `TotpCodesScreen`
> 把输入框塞进 `LargeTopAppBar` 的 `title` 槽，而大标题栏**本身就是可变高度**，展开/收起时
> 输入框抖动、焦点漂移。
>
> **修复（对齐 Bitwarden 的 `BitwardenSearchTopAppBar`）**：新增
> `ui/common/VaultixSearchTopAppBar` —— 固定高度 `TopAppBar`（**绝不用大标题栏**）、
> 搜索态输入框**整体占据 `title` 槽**（与标题二选一、不并存）、`FocusRequester` +
> `LaunchedEffect` **主动请求焦点**、`ImeAction.Done`、有输入时清除按钮带动画。
> 三个屏幕统一改为「`searchActive` 时**整体替换**顶栏」，不再叠加；`searchActive` 统一
> 改 `rememberSaveable`。
>
> **验证**：新建组件用 Compose API 桩真实编译 **0 error**（API 参数名逐字对齐 Bastion
> 实测用法）；四文件按 Kotlin UTF-16 语义无超 120 字符行；import 无残留未使用。
>
> 同轮附带：**已配好 SSH over 443 通道并把本地 10 个提交推送到 GitHub**（见下）。

> 更新于 2026-09-11（第三十二轮）。锁态模型按 Bitwarden 标准重写完成。
>
> 用户要求：「参考 bitwarden 的做法…哪怕是一字一句抄代码，也要实现。
> 还有**密码库加锁和解锁逻辑也要按 bitwarden 标准来**吧。更稳定，我这项目当前的
> 密码库加锁解锁逻辑太烂了，不标准」。已确认三个方向并全部落地：
> ①完整抄定时器模型；②统一透明 trampoline + 集中路由；③根导航驱动的解锁路由。
>
> **核心变化**：锁态从「回前台算时间差」换成 Bitwarden 的「后台启动 `delay()` 定时器 job、
> 前台 `cancel` 它」。新的 `VaultLockManager` + `VaultTimeout` sealed class 取代旧的
> `AutoLockController` 自判 + 裸 `Int` 档位 + `CredentialFlowGuard` 时间戳窗口。
> 仓储层的 `runBlocking` 也一并去掉（改挂起式）。
>
> ⚠️ **存量迁移**：旧 `auto_lock_minutes` 的 `-1` 是「从不」，新模型 `-1`（OnAppRestart）
> 是「重启即锁」——**语义正好相反**，已在 `VaultixPreferences` 做一次性迁移。
>
> 第三十一轮**通行密钥「Authentication failed」的第二个根因（锁态竞态）已修复 ——
> 用户的判断是对的，问题确实在密码库的加锁/解锁逻辑上。**
> `PasskeyGetActivity` 把「库锁定」与「凭证不存在」混为一谈（`ItemRepositoryImpl.observeState`
> 在库未解锁时恒发空列表），而 `AutoLockController.onStart` 又把「系统拉起我方 Activity」
> 造成的 ProcessLifecycle 前后台切换误判成「用户切走又回来」→ `lockAll()`。本轮的三处
> 对齐（锁态单独成一路 / 凭据流程豁免 / 验证簿记）已在第三十二轮被更彻底的模型替换。
>
> 第三十轮（再上一轮）**通行密钥「Authentication failed」的第一个根因已修复：
> 浏览器流程回传了自造的 clientDataJSON** —— 系统只给 32 字节 `clientDataHash`（无明文），
> 而 RP 校验用的是**网页交给它的那份浏览器 JSON**，所以 provider 回传的必须是**占位符**
> （官方明文要求）；签名仍只用系统给的哈希。

## 第三十四轮 2026-09-11 · 修复 CI：detekt 门禁 + 被掩盖的编译错误

**commit `24af692`；CI run `34590428399` = success。**

### 根因 1：detekt 门禁

- `MagicNumber`：`VaultTimeout`（5 处档位）+ `VaultixCrypto`（`and 0xff`）+ `SettingsScreen`（4 处档位）。
- `CyclomaticComplexMethod`：`resolvePasskeys` complexity **41**（阈值 14）。

### ★ detekt 2.0.0-alpha.6 非标准行为（务必记住）

`CyclomaticComplexMethod` 会把**同文件**被调私有函数的复杂度**累加**进调用方
（同一方法拆同文件 helper：19 → 41）；`ignoreNestingFunctions` 默认 **false**。
**实证方法**：把函数体 stub 成 `return emptyList()` → 违规消失 ⇒ 复杂度来自「调用」。
**规避**：helper 拆到**独立文件**（detekt 逐文件分析、不跨文件累加）——
新建 `app/.../passkey/PasskeyResolution.kt`。

### 根因 2：编译错误被 CI 步骤顺序掩盖

workflow 顺序 = **detekt → compile**；detekt 一红即中断 ⇒ 编译步骤从不执行。

| 文件 | 修法 |
|---|---|
| `core/datastore/.../VaultTimeout.kt` | `mapNotNull { t -> t.vaultTimeoutInMinutes?.let { it to t } }.toMap()` |
| `app/.../ui/settings/SettingsScreen.kt` | `requireNotNull(timeout.vaultTimeoutInMinutes)` |
| `app/.../passkey/CredentialProviderIntentUtils.kt` | 返回类型改客户端侧 `CreateCredentialRequest`（与 `BeginCreateCredentialRequest` 互不相关，javap credentials 1.6.0 实证） |

### 验证

本地全绿：`detekt`（10 模块）/ `:app:compileFullDebugKotlin` / `:app:assembleFullDebug` /
`:core:datastore:testDebugUnitTest`。

### 另：参考源码本地副本（不纳入 git / 不同步 GitHub）

`D:\Vaultix-refs\bitwarden-android` @`74c0e04`、`D:\Vaultix-refs\keyguard-app` @`f95c865`（`--depth 1`）。
> ⚠️ 以上为**当时快照**；2026-10-28 已刷新（bitwarden `1e624ad` / keyguard `693c2073`），
> 现况以 [`.ai/conventions/8.10`](../../.ai/conventions/8.10-架构与目录地图.md) §6.1 为准。

## 第三十三轮 2026-09-11 · 搜索框按 Bitwarden 标准重写 + 打通 GitHub 推送

### 一、搜索框「乱跳」（用户反馈）

用户原话：「当前的 vaultix 一直在输入框，搜索框乱跳，有没有办法。按照 bitwarden 的方式修吧」。

**三种坏写法（三处互不一致）**：

| 屏幕 | 旧写法 | 症状 |
| --- | --- | --- |
| `ItemsScreen` | `OutlinedTextField` 挂在 `LargeTopAppBar` **外层** `Column` | 最严重：大标题栏滚动变高，输入框被反复垫高 |
| `PasskeysScreen` | 输入框塞进 `LargeTopAppBar` 的 `title` 槽 | 大标题栏展开/收起时输入框抖 |
| `TotpCodesScreen` | 同上（且清除按钮语义写成「取消」） | 同上 |

**修法（照抄 Bitwarden `BitwardenSearchTopAppBar`）**：

1. 新建 `app/src/main/java/io/vaultix/vaultix/ui/common/SearchTopAppBar.kt`
   （`VaultixSearchTopAppBar`）：
   - 固定高度 `TopAppBar`，**明确不用 `LargeTopAppBar`**；
   - 搜索态输入框**整体占据 `title` 槽**（与标题二选一）；
   - `FocusRequester` + `LaunchedEffect(Unit) { requestFocus() }` 主动聚焦；
   - `ImeAction.Done`；有输入时显示清除按钮（`scaleIn+fadeIn` / `scaleOut+fadeOut`）；
   - 左侧关闭按钮 = 退出搜索并清空。
2. 三个屏幕统一改成 `if (searchActive) VaultixSearchTopAppBar(...) else LargeTopAppBar(...)`
   —— **整体替换，不叠加**。
3. `searchActive` 从 `remember` 改为 `rememberSaveable`（旋转 / 进程恢复不丢）。
4. 删除三个屏幕各自的旧 `SearchField` 私有组件及随之失效的 import。

**验证（无 Android SDK / Compose 依赖下的手段）**：

- 自建 Compose API 桩（`/tmp/vs/stub/`），用 `kotlin-compiler-embeddable` **真实编译**
  新建的 `SearchTopAppBar.kt` → **0 error**；`Modifier`/`Icons.Filled` 等桩写法与真实
  Compose 对齐（`interface Modifier { companion object : Modifier }`）。
- 关键 API 参数名**逐字比对 Bastion 实测用法**：
  `TopAppBarDefaults.topAppBarColors(containerColor=...)`、
  `TextFieldDefaults.colors(focusedContainerColor/unfocusedContainerColor/focusedIndicatorColor/unfocusedIndicatorColor=...)`。
- 四文件按 **Kotlin `String.length`（UTF-16）语义**无超 120 字符行（`awk` 按字节算会误报 CJK 注释）。
- import 残留检查：无未使用 import（`getValue`/`setValue` 为 `by` 委托操作符，须保留）。

**提交**：`a3093e4`。

### 二、GitHub 推送（此前一直未推上去）

**问题链**：

1. HTTPS 推送报 `could not read Username` —— 沙箱的非交互环境取不到凭证，
   而 `git-credential-helper` 对 github.com 返回空。
2. `github.com` 被解析到 `198.18.0.x`（保留测试网段，网关劫持），HTTPS 直连 TLS 被断。
3. SSH 22 端口超时。

**解决**：

1. 用阿里 DoH（`https://223.5.5.5/resolve`，沙箱内可达）查到 GitHub **真实 IP**：
   `github.com=20.205.243.166`、`ssh.github.com=20.205.243.160`、
   `api.github.com=20.205.243.168`、`codeload.github.com=20.205.243.165`、
   `raw.githubusercontent.com=185.199.110.133`、`objects.githubusercontent.com=185.199.111.133`。
2. 写入 `/etc/hosts` **并同步 `~/.user_hosts`**（`/etc/hosts` 改动 workspace 重启会被还原，
   项目指南第 2 条要求长期使用）。
3. 配置 `~/.ssh/config`：`github.com` → `HostName ssh.github.com` + `Port 443`（**SSH over 443**，
   绕开被墙的 22 端口）。
4. 安装九哥提供的私钥（指纹 `SHA256:r99kZ2srjaVvBVmUeBKb6S6svJxK/IIDGUZ7+oEXIqA`，
   与公钥 `github-chani` 逐字节一致）→ `ssh -T git@github.com` 返回
   `Hi Chaniug! You've successfully authenticated`。
5. `git remote set-url origin git@github.com:Chaniug/Vaultix.git` → **推送成功**：
   `ae3a236..797cac1`（第三十二轮 9 个提交）+ `797cac1..a3093e4`（本轮）。

**【重要·沙箱重启后恢复步骤】**：若 `/etc/hosts` 被还原且推送失败，重跑上述第 1–3 步
（真实 IP 可能漂移，用 DoH 重查即可）。

## 第三十二轮 2026-09-11 · 锁态模型按 Bitwarden 标准重写

### 为什么旧的「不标准」（可实证，非主观）

| 维度 | Bitwarden 标准 | 旧 Vaultix | 后果 |
|---|---|---|---|
| 超时触发 | 后台 `launch { delay(timeout); lock() }`，前台 **cancel 该 job** | 后台记 `backgroundedAtMs`，前台回头算差值 | 计时基准是"前台时刻"，keyguard/进程事件都会干扰 |
| 超时模型 | `VaultTimeout` sealed class（10 个档位） | 裸 `Int`（负数=从不、0=立即） | 语义靠口头约定；`OnAppRestart` 档位压根不存在 |
| 超时原因 | `AppBackgrounded` / `AppCreated(firstTimeCreation, createdForAutofill)` / `UserChanged` | 无 | 无法表达"这次前台切换是 autofill 造成的" |
| autofill 豁免 | `createdForAutofill=true` **结构性豁免** | `CredentialFlowGuard` 8 秒时间戳窗口（启发式） | 窗口过短/过长都会误判 |
| 锁定执行 | LockManager 挂起调度 | `VaultRepositoryImpl` 用 **`runBlocking`** 阻塞调用线程 | UI 线程被阻塞做密钥清零 |
| CP 锁定出路 | 返回 `authenticationAction`，系统重新发起请求 | Activity 内自己 `startActivity` 再 `cancel()` | 两段式流程被打断成一段 |
| CP 路由 | `RootNavViewModel` 集中决定 | 判定散在 Service 的 `lockedCount` + Activity 的 `sessions.isUnlocked` | 多处判定，任一处不一致就出竞态 |

### 落地清单

**锁态**
- 新增 `core/datastore/.../VaultTimeout.kt`（纯 JVM 模型，对齐 Bitwarden `VaultTimeout`）：
  `Immediately` / 1 / 5 / 15 / 30 / 60 / 240 分钟 / `OnAppRestart` / `Never` / `Custom`，
  含 `toStorageValue` / `fromStorageValue` / **`fromLegacyMinutes`（迁移）**。
- 新增 `app/.../security/VaultLockManager.kt` + `VaultLockManagerImpl.kt`（逐句对齐
  `VaultLockManagerImpl.kt`）：`handleOnBackground` / `handleOnForeground`（**只 cancel job**）/
  `checkForVaultTimeout` / `handleTimeoutActionWithDelay` / `CheckTimeoutReason` 三类。
- `AutoLockController` 降级为薄适配器；**删除** `AutoLockPolicy.kt`、`CredentialFlowGuard.kt`。
- `VaultixApplication` 新增 `onAppCreated` + `markCreatedForAutofill` 接线。
- `VaultRepository.lockVault/lockAll` 改 `suspend`，**去掉 `runBlocking`**。

**凭据提供商**
- 新增 `CredentialProviderActivity`（透明 trampoline，`exported=false`）、
  `CredentialProviderRequestManager`（凭据不经 Intent）、`CredentialProviderIntentUtils`。
- `VaultixCredentialProviderService`：**有任一库锁定时只返回 `authenticationActions`**，
  不再与 `credentialEntries` 并存（对齐 Bitwarden「锁定态与可填充候选互斥」）。
- `PasskeyGetActivity`：锁定时不再自起解锁页，改为回灌取消交由系统走两段式。

**解锁路由**
- 新增 `RootNavViewModel` + `RootNavState`；`VaultixApp` 起始路由按锁态决定；
  新增 `UnlockEntryRoute`（无参，自动选首个锁定库）。保留主密码 + 2FA + 本地快速解锁。

**设置**
- 档位改 `VaultTimeout`（新增「重启 App 时」）；`SettingsScreen` 的 `when` 变穷尽分支。

### 验证（**真实运行**，非纸面推演）

1. **真实编译生产类**：用沙箱内 `kotlin-compiler-embeddable-2.2.21` 编译
   `core/datastore/.../VaultTimeout.kt`，再用**编译出的真实类**跑 28 条行为断言 → **全 PASS**。
   固化了最危险的两点：`fromLegacyMinutes(-1) == Never` 且 `!= OnAppRestart`；`Custom(0)` 抛异常。
2. **超时决策逻辑** 17 条断言 → **全 PASS**。重点：
   - `OnAppRestart` + `AppCreated(false, createdForAutofill=true)` → **不锁**（豁免生效）
   - `OnAppRestart` + `AppBackgrounded` → **不锁**（本档位不响应后台）
   - 前台 `cancel` 定时器后到点**不锁**
3. 新增 `core/datastore` 单测 `VaultTimeoutTest`（10 用例），并补该模块的 junit/truth 依赖。
4. 全仓 `.kt` 无超 120 字符行（detekt 门禁）。

### 待真机回归

- [ ] 解锁 → 切后台 5 分钟 → 回前台应锁
- [ ] 解锁 → 息屏即回（5 分钟档）→ **不应立刻锁**（旧实现在此会锁）
- [ ] 设置「重启 App 时」→ 杀进程重开应锁；**从浏览器点通行密钥拉起则不应锁**（豁免）
- [ ] 网页点通行密钥 → 候选正常 → 确认卡片 + 生物识别 → 登录成功
- [ ] 锁定时点候选 → 应出现「解锁 Vaultix」动作 → 点它解锁后自动重列候选
- [ ] 首次安装（无库）启动 → 应进库列表引导添加，**不能卡在解锁页**
- [ ] 存量用户升级：原先选「从不」的**必须仍是「从不」**（迁移正确性）

> ⚠️ Bastion / Keyguard 两家在这点上都是**反例**，不可照抄；Bitwarden 交给 SDK 的做法才对。**
>
> 再上一轮（第二十九轮）**通行密钥「找不到候选 / 列表为空」根因已定位并修复：
> 未 trim + rpId 未归一化 + allowCredentials 无回退（全在 discovery 链）。
> 「解密残留填充」经真实 JCE 实测后被降级为纵深防御，非根因。**
> 同时**撤回**第二十八轮的两个错误结论（见下方「⚠️ 结论更正」）。
> 审计报告 `Docs/progress/audit/bitwarden-alignment.md`；对齐评估
> `Docs/progress/bastion-parity-assessment.md`（**注意已过时**）。
> 状态：`TODO` / `DOING` / `DONE` / `BLOCKED`

## 第三十一轮 2026-09-11 · 通行密钥「Authentication failed」第二根因（锁态竞态）

> 用户报：clientDataJSON 占位符修复后**仍然** Authentication failed，并追问
> 「是密码库的问题吗，密码库加锁解锁的逻辑问题？」——**判断正确**。
> 随后指示「参考 bitwarden 的做法…哪怕是一字一句抄代码，也要实现」。

### 根因（**锁态竞态**：两处判定不一致）

现场链路（逐步）：

1. 浏览器发起 passkey 请求 → `VaultixCredentialProviderService` 列出候选
   —— **能列出说明当时库是解锁的**；
2. 用户点候选 → 系统拉起 `PasskeyGetActivity`；
3. 该 Activity 属于本 App。`ProcessLifecycleOwner` 因此走 `onStop` → `onStart`
   （同进程内 Activity 切换在 ProcessLifecycle 语义下等同于一次前后台切换）；
4. `AutoLockController.onStart` 判定「回前台是否该锁」。`AutoLockPolicy
   .screenLockRequiresRelock(screenLocked) = screenLocked` —— **只要屏幕处于
   keyguard 就锁，与分钟档位无关** → `lockAll()`；
5. 会话被清 → `ItemRepositoryImpl.observeState` 发空列表（`if (!isUnlocked)
   emptyList()`）→ Activity 读到 `cred == null` →
   `fail(GetCredentialUnknownException("Passkey not found"))`；
6. 浏览器收到**认证失败**，且错误信息把人误导到「库里没有这条密钥」。

**两个独立缺陷叠加**：
- **缺陷 A**：Activity 把「库锁定」与「凭证不存在」混为一谈（假错误信息）；
- **缺陷 B**：自动锁定把「我自己拉起的 Activity」误判成「用户切走又回来」（真锁定）。

### 修复（逐处对齐 Bitwarden）

| Vaultix 缺陷 | Bitwarden 对应设计 | 本次落地 |
|---|---|---|
| A. 锁定误报「找不到」 | `CredentialProviderProcessorImpl` 的 `isVaultUnlocked` 门 + `RootNavViewModel` 把 `VaultLocked` 映射到**解锁界面**（绝不是错误页） | `VaultSessionManager.isAnyUnlocked()`；`PasskeyGetActivity` 在 `cred == null` 时**先探锁态**：锁定 → `unlockAndFinish()` 走解锁引导；已解锁但凭证不在 → 才是真的「找不到」 |
| B. 自己拉起的 Activity 触发误锁 | `VaultLockManagerImpl`：`FOREGROUNDED → handleOnForeground()` **取消**超时任务；另有 `OnAppRestart` 的 autofill 豁免 | 新增 `CredentialFlowGuard`（记最近一次凭据流程启动时刻）；两个凭据 Activity 在 `onCreate` **最早时机**打点；`AutoLockController.onStart` 在豁免窗口（8s）内跳过锁定判定 |
| C. 无验证簿记 | `isUserVerified` + `authenticationAttempts`（`MAX_AUTHENTICATION_ATTEMPTS = 5`） | 同名同语义落地；签名前断言 `isUserVerified`；所有终结路径复位为 false；用尽尝试即失败，不再弹窗 |
| D. origin 取值顺序错、且构造伪 origin | `getOriginUrlFromAssertionOptionsOrNull`（**host 来自请求 JSON 的 rpId**）；缺失 → `Error.MissingHostUrl` | 改为「调用方 origin → 请求 rpId」顺序；原生流程（无 `clientDataHash`）拿不到 host 时**明确失败**，不发出注定被拒的断言 |

### 验证（**真实运行**，非纸面推演）

用沙箱内 `kotlin-compiler-embeddable-2.2.21.jar` 编译并执行
`VerifyCredentialFlowGuard.kt`（内联 `CredentialFlowGuard` 与 `AutoLockPolicy`
的逐字逻辑副本 + `AutoLockController.onStart` 的判定整合）—— **9/9 PASS**。

> **该验证抓出了一个本次改动自身引入的严重缺陷**：`CredentialFlowGuard` 初值若取
> `Long.MIN_VALUE`，判定式 `now - lastFlowStartedAtMs` 会**整数溢出为负数**，
> 负数恒 `< 8000` ⇒ 判定「在豁免窗口内」**恒为真** ⇒ **自动锁定被永久抑制**。
> 已改为初值 `0L`（`elapsedRealtime` 恒为正且远大于窗口，不溢出），
> 并把该溢出场景固化为回归断言。
>
> 教训：**能真实跑起来的验证，一定要先跑再看输出** —— 这条推论在纸面上完全看不出问题。

### 未决 / 待真机确认

- [ ] 真机回归（用户装 Edge 侧 CI 产物）：候选列出 → 点选 → 应出现确认卡片与生物识别 →
      Edge 正常登录。**重点观察日志中 `vaultUnlocked` / `anyUnlocked` 两项**：
      - `credFound=false vaultUnlocked=false` → 锁态竞态（本次修复目标，应已消失）；
      - `credFound=false vaultUnlocked=true` → 才是库里真的没这条凭证；
      - `credFound=true` 但仍失败 → 回到签名链路（第三十轮已处理的 clientDataJSON）。
- [ ] 锁定引导连通性：`unlockAndFinish()` 走 `AutofillIntents.MODE_UNLOCK`。
      该路径在 autofill 场景已被验证；凭据提供商场景下的 PendingIntent 可启动性
      需真机确认（理论上 `FLAG_ACTIVITY_NEW_TASK` 已补）。
- [ ] 豁免窗口 8s 的体感：若用户反馈「切走再立刻回来没被锁」，把
      `CredentialFlowGuard.EXEMPTION_WINDOW_MS` 调小即可（该常量已集中）。


## ⚠️ 结论更正（2026-09-11，第二十九轮）

### 更正一：「已注册的通行密钥需要重新注册」——**错误，已撤回**

第二十八轮曾断言「RP 记录的是注册时的 BE/BS 语义，改代码救不了旧凭证，必须重新注册」。
**这个结论是错的。** 查证 WebAuthn 规范与多家 RP 文档后确认：

> **BE（Backup Eligible）与 BS（Backup State）是「注册期存档字段」。**
> RP 在**断言（登录）阶段不做 BE/BS 校验**。RP 的 login 校验清单只有：
> 解码 clientDataJSON → 校验 `type==="webauthn.get"` / challenge / origin →
> 按 credentialId 取公钥 → `SHA-256(rpId)` 匹配 rpIdHash → UP 已置（按策略再查 UV）→
> 验签（`authData ‖ SHA-256(clientDataJSON)`）→ `new signCount > stored signCount`。

因此 **BE/BS 缺失根本不可能导致「登录验签失败」**，也**不存在「旧凭证必须重注册」**。
用户的质疑是对的：「通行密钥为什么要新建呢，这个不是存好的就不动的吗」。

**保留 BE/BS 置位的理由变更为：语义正确性**（Vaultix 私钥随库同步，确实是可备份凭证），
**不是**为了修某个登录 bug。相关 KDoc / 注释 / 测试说明均已改写。

### 更正二：「signCount 改读库」——**错误，已回退**

第二十八轮把断言侧的 `counter` 从硬编码 0 改成「读库里的值原样发送」。**这是引入新 bug。**

规范对计数器的校验是**严格大于**（`new > stored`）。Bitwarden 官方客户端每签一次会**递增并
写回服务端**，同步下来的 `counter` 就是**非零**（`CipherMapper` 第 444 行实证）。原样发送且
不递增 ⇒ **第二次登录发出的值与上次相同** ⇒ `new > stored` 不成立 ⇒ RP 判为**重放**并拒绝。

**已回退为恒 0**（对齐 Bastion `PasskeyAuthActivity` 的 `newSignCount = 0L`）：0 表示
「本 authenticator 不实现计数器」，规范 §6.1.1 明确允许，RP 据此跳过单调性校验。
Bitwarden / 1Password / iCloud Keychain 这类**同步型 passkey 全部走这条路**。

## 第二十九轮 2026-09-11 · 通行密钥「列表为空」根因

> 用户报：BE/BS 那次改动之后，**通行密钥又找不到了，候选列表为空、没有任何候选**。
> 要求对照 Keyguard / Bastion / Bitwarden 三份实现仔细排查后修复。

### 已确认（实测排除法）

- [x] **不是 `8afac3d` 引入的**：`git show 8afac3d --name-only` 只有 3 个文件
      （`PasskeyGetActivity` / `WebAuthn` / `WebAuthnTest`），**完全没碰发现（discovery）逻辑**。
      `VaultixCredentialProviderService` 最后一次变更是更早的 `fec4032`。
      「候选为空」与那次改动**无因果关系**。

### 根因（对照三家实现确认；①为纵深防御，②③④是实际根因）

- [x] **① 解密残留填充（⚠️ 经实测后降级为「纵深防御」，非根因）**：
      曾推断 Bitwarden 服务端用 ISO10126 填充、Vaultix 用 `PKCS5Padding` 解密导致残留。
      **用真实 JCE 实测后该推断不成立**：
      - 标准 SunJCE 的 `PKCS5Padding` 对 ISO10126 密文**直接抛 `BadPaddingException`**，
        不会静默泄漏字节；
      - 反向验证：ISO10126 末字节同样是填充长度，用宽松 PKCS5 解析 **2000/2000 完全正确**；
      - 且 `decrypt` 本就以 `PKCS5Padding` + `doFinal` 正确解填充。
      ⇒ **不是根因**。但保留 `VaultixCrypto.removePkcs7PaddingIfStrict` 作为**纵深防御**
      （仅在严格 PKCS#7 成立时剥离，避免误伤），并记录 `trim()` 对 `0x01..0x10`
      的兜底作用（已验证 16/16 可去除）。
      **教训：一次 JCE 实验就能否掉的假设，不要写进根因。**
- [x] **② 未 trim（实际根因）**：服务端多处字段带前导/尾随空白，官方客户端读出来一律
      `trim()`；Vaultix 的 `CipherMapper.mapFido2` **一个字段都没 trim**。
      而 `rpId` 是按**精确字符串**比对的 —— 不 trim 直接导致全量失配。
      修复：`mapFido2` 全字段 `.trim()`（对齐官方语义；`counter`/`discoverable` 走
      `toLongOrNull`/`toBooleanStrictOrNull`，不 trim 会**静默回落默认值**）。
      同时 `decryptToString` 也加 `trim()` 作为全局兜底。
- [x] **③ rpId 未归一化（实际根因）**：此前是 `cred.rpId.equals(rpId, ignoreCase = true)`，
      只能处理大小写；**末尾根点 `.` / Unicode 域名（punycode）** 一律失配。
      对照 Bastion `PasskeyRpIdNormalizer`：`trim` → `trimEnd('.')` → `lowercase(Locale.ROOT)`
      → `IDN.toASCII(..., USE_STD3_ASCII_RULES)`，且 **`isEquivalent` 两侧都归一化**。
      修复：Vaultix 新增 `normalizeRpId` / `isSameRpId`，语义与 Bastion 逐条对齐。
- [x] **④ allowCredentials 严格过滤无回退（实际根因）**：RP 下发的 `allowCredentials` 是
      **提示**而非授权门。用户若在**其它设备/客户端**注册过该 RP 的 passkey，本地
      credentialId 与列表对不上 —— 严格过滤会把**唯一可用候选也删掉**。
      对照 Bastion `BastionCredentialProviderService.resolvePasskeys`：
      `if (filtered.isEmpty() && allowed.isNotEmpty()) resolve(..., strictAllowCredentials = false)`。
      修复：Vaultix 照抄该回退 —— 严格匹配为空时回退到「只按 rpId」并打日志。

### 对照结论（Keyguard / Bitwarden 的共识）

三家在**锁定态**的处理完全一致，Vaultix **已对齐**，不是本次根因（已核实）：

| 维度 | Bitwarden | Keyguard | Vaultix |
|---|---|---|---|
| 全库锁定 | 只返回 `authenticationActions`（unlock），**不带** credentialEntries，直接 return | `MasterSession.Empty` → 只返回 unlock 的 `AuthenticationAction` | ✅ 同（`unlocked.isEmpty()` 分支） |
| 通道互斥 | if/return 互斥 | 同 | ✅ 同（锁定即 return） |
| 部分锁定 | —（单账号） | — | ✅ 凭据 + unlock 动作并存 |
| 补偿重试 | **无** | **无**（仅 UI 侧 800ms 最小处理时长） | 无（对照后确认**不需要**） |

> Keyguard `PasskeyTargetCheck` 同样是**严格匹配无回退**；Bitwarden 的
> `filterAllowedCredentialsIfNecessary` 在列表为空时不过滤。Vaultix 采用
> 「Bastion 式回退」，比两家都更宽容，但方向是「宁可多列，不可漏列」，正确。

### 测试

- [x] `WebAuthnTest`：新增 **signCount 恒 0 回归锁**（断言流程末 4 字节必须 `{0,0,0,0}`，
      大端解析同 0）。
- [x] `VaultixCryptoTest`：新增 4 条回归测试 —— ISO10126 残留填充剥离 / 严格 PKCS#7 剥离 /
      **非严格填充必须原样返回**（防误伤）/ `decryptToString` 必须 trim。

⏳ **真机待验证**：装新包 → 在 Edge 触发通行密钥登录 → **候选列表应出现条目**；
若仍为空，`logcat` 抓 `VaultixAutofill` tag 的
`GET resolve rpId=... total=... unusable=... rpIdMiss=... allowedMiss=... matched=...`
逐级计数（本轮已埋点），四个计数一出来即可判定卡在哪一级。
