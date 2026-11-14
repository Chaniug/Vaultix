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

## 🕐 最新状态（2026-11-14 · #291 两条安全红线修复）

> **触发**：用户「软件签名的问题，我一直都把签名放到 github 上的 secret 里面的，
> 所以签名没有什么疑问，至于安全问题，你决定如何修吧」
> ⇒ **签名链路完全不动**（`SIGNING_*` Secrets / `release.yml` / attestation），
> 安全侧由助手拍板范围并施工。

**拍板范围**：只修「有真实损害、能本地验证、可加门禁防回归」的两项。

- ✅ **S2** release 下绕过 `VaultixLog` 的裸 `Log`：实收敛 **8 处**（6 处真违规 +
  `AutofillLogger` 2 处半合规），`reseal $vaultId` 改 `redact(vaultId)` 脱敏；
  全仓生产代码只剩 `VaultixApplication.kt` 门面 sink 一处直接用平台 `Log`。
- ✅ **S3** 生产 3 处 `!!`：全改「取局部快照 + 空值有去向」，**不用** `requireNotNull`
  （那只是把 NPE 换成 `IllegalArgumentException`，对真机用户仍是点一下就崩）。
- ✅ 新增 `check_production_safety.py` + 6 组自测，接 `ci-debug.yml` 两步；
  `check_ui_hardcoded_text.py` 补 `_log_lambda_mask()` 修 trailing lambda 误报。
- ❌ **不夹带**：S6 崩溃可取证（要「建」不是「修」，D07 脱敏边界待用户拍板）、
  CP 双能力分歧（`decisions.md:66` 等真机 A/B）、厂商白名单引导、
  **S1 依赖解析门禁（仍是 P1 第一优先，不混进本提交）**、签名链路。

⚠️ **长期判据**：`BuildConfig.DEBUG` 由 **buildType** 决定、不由分支决定；R8 只裁
`if (BuildConfig.DEBUG) { … }` 整块，源码里一句裸 `Log.d(...)` 在 release 照输出
⇒「main 与 rele 源码一致」**不等于**产物行为一致。另一坑是「**同一判据存两份**」：
Kotlin `nonUiCallSites` 与 Python `_ALLOW_HINTS` 各存一份日志白名单，日志改成
trailing lambda 后语句形状变了就误报，两处都要补（#160 同源老毛病）。

接力正文：[`01-构建与环境-part14.md`](./issues/01-构建与环境-part14.md)；
P1 两项已在 `next-steps.md` 勾选，**真机验收清单**见 part14 §291.9。

**✅ CI / 出包结论（已看到真实 run 结果后回填，非预填）**：

- 首轮 `99b738cb`：`AI docs consistency` 43557701739 **success**；
  `Android CI (Debug)` 43557701747 **failure** —— 唯一违规是 detekt
  `KdbxSyncOrchestrator.sync` 圈复杂度 **15 > 14**（为消掉 `!!` 加的 `?: return`
  多带一个分支，撞上原本就贴边的上限）。
  ⇒ 抽 `resolveSyncSource()` + 私有 `sealed SyncSourceResolution` 修红，见 part14 §291.10。
- 修红 `fcb77f9d`：`AI docs consistency` 43559918223 **success**（约 12s）；
  `Android CI (Debug)` 43559918391 **success**（05:06:44Z → 05:11:11Z，约 4m27s；
  `Lint, test diagnostics, and debug build` 与 `Sandbox unit tests` 两个 job 全绿）。
- preview Release **`Vaultix v0.13.1-dev-fcb77f9 · Preview`** 已出
  （tag `preview`、`prerelease=true`），资产
  `Vaultix-v0.13.1-dev-fcb77f9-preview.apk`（35.8 MB）：
  `https://github.com/Chaniug/Vaultix/releases/tag/preview`
- ⚠️ **本次只推 `main`、没有推 `rele`** ⇒ 不触发正式发版，`v0.13.1` 正式包不受影响；
  #291 仍是**待真机验收**，代码推完不等于修好了。

## 🕐 发布状态（2026-11-14 · v0.13.1 正式发布 + 上游改动搁置）

> **一句话**：用户拍板「发一个 release；上游改动目前意义不大，先搁置，以后每月排查一次
> 重大更新即可」。本轮发 **v0.13.1 稳定版**（含 #288 / #289 / #290 三个真机修复），
> 纯发布 + 文档，无新增生产代码。

**发版口径（重要，别再踩）**：
- 发版前先 `fetch --tags` —— 本地 `origin/rele` 缓存曾停在 `8a046657`（v0.12.0），
  误判「落后 70 个提交」；真实是 `2af7286c`（v0.13.0 发布点），只落后 main 7 个提交。
- **`v0.13.0` 已是 11-12 的 Latest**，`VERSION` 却仍写 `0.13.0`。
  `release.yml` 有「删同版本旧 Release/tag 再重建」的幂等步骤，直接发布会**覆盖已发布版本**
  ⇒ 发版前必须 bump `VERSION`，本轮改 `0.13.0 → 0.13.1`。
- 发布说明写在 `Docs/release-notes/v0.13.1.md`；`release.yml` 的 changelog 步骤**优先读手写版**，
  没有才回退提交列表。别把内部账（文档回填 / CI 修红）贴给用户。
- 4 个 `SIGNING_*` Secrets 均已配置；正式包是混淆 + 固定密钥签名，可与 preview 覆盖安装。
- 路径：用户明确授权 **合入 `rele`**（main 快进 → push `rele` → `release.yml` 读 VERSION 打 tag
  `v0.13.1`）。⚠️ 项目硬约束仍是「**不要擅自合入 rele**」，每次都要用户点头。

**上游参考候选 → 已拍板搁置**：2026-11 的 Bitwarden / KeePassDX / KP2A 调研结论
（决策页 `vaultix-upstream-targeted-2026-11.html` 在工作区、不入库，`next-steps.md` 有登记表）
用户判定「目前对我意义不大」，**全部不排期**，仅保留 `Docs/progress/next-steps.md`
的「上游参考候选（未排期）」表作存档。

**月度节奏（文档约定，不建定时任务）**：**每月排查一次上游重大更新**，
只筛 major / 安全 / 互操作性硬 bug，出简报、不动代码；到点由用户提醒，
不建 GitHub Actions 定时 workflow、不建自动化任务。见 `next-steps.md` 同节。

**✅ 发布结论（已看到真实 run 结果后回填，非预填）**：
`Android Stable Release` run `43542269744` → `completed/success`
（2026-11-14 01:43:18Z → 01:51:18Z，约 8 分钟；单 job `Build & Publish Stable Release` success）。
- `Vaultix v0.13.1` 已发布并置为 **Latest**（`v0.13.0` 已让位），`draft=false` /
  `prerelease=false`；tag `v0.13.1` → `dd2e845b`；`origin/rele` 与 `main` 齐平（0/0），
  `VERSION = 0.13.1`。
- 资产 `Vaultix-v0.13.1.apk`（6.6 MB，已混淆 + 资源压缩）+ `checksums-sha256.txt`；
  APK SHA-256 `8f1835985b8e01736fea74fa8be16e8d15c023fb39a502130cca04db17b96509`；
  签名 `CN=Vaultix, O=Vaultix` SHA-256
  `7e6c8c073d0c1a3b8fd8acf6e5d070acd73fdaf2986ca717de27183f395c1fd8`；attestation 已生成
  （可 `gh attestation verify *.apk --repo Chaniug/Vaultix` 离线核验）。
- 发布页正文**读的是手写版** `Docs/release-notes/v0.13.1.md`（未回退提交列表），
  `## 校验和 (SHA-256)` / `## 来源证明` 两节由流水线写入；地址
  `https://github.com/Chaniug/Vaultix/releases/tag/v0.13.1`。
- ⚠️ 正式包是**混淆 + 固定密钥签名的独立构建**，preview 已验 #290 主路径；
  装机侧建议覆盖安装后再复验 #288 / #289 / #290 三条主路径一次。

## 🕐 上一轮状态（2026-11-13 · #290 点「注销」网盘账号闪退）

> ✅ **2026-11-14 真机验收通过**：用户回报「最新版已经不闪退了」，`v0.13.0-dev-5553bda`
> 上注销网盘账号不再闪退，#290 主路径闭环。细分提示文案 / logcat 项未逐项回报，
> 见 [part28 待真机验收](./issues/06-界面与交互-part28.md)。

> **一句话**：真机「网盘账号无法顺利从 vaultix 注销，点击注销按钮，发现闪退」。日志
> `versionName=0.13.0-dev-731cdae`（正是 #289 preview）两次 FATAL
> `Resources$NotFoundException: String resource ID #0x0`，栈在 `NoticeSnackbar` →
> `stringResource(0)`，经 `Recomposer.performRecompose` / `Choreographer` ⇒
> **Compose 重组期异常**，不是协程异常，`launchGuarded` 兜不住。
> 接力正文：[`#290`](./issues/06-界面与交互-part28.md)。

1. **取证**：`confirmRemoval()` 成功分支写 `notice = SETTINGS_CLOUD_REMOVED_WEBDAV`，
   而 `CloudAccountsViewModel` 的 11 条提示常量是 `const val ... = R.string.*`。
   AGP 编译期 `compile_symbol_list/.../R.txt` 与 `compile_r_class_jar/.../R.jar`
   里资源 id 全是 `0x0` 占位（`public static int`，非 final）；运行时
   `runtime_symbol_list` / `compile_and_runtime_r_class_jar` 才是 `0x7f0f01xx`
   （`cloud_msg_removed_webdav = 2131689781`）。`const val` 要编译期常量
   ⇒ Kotlin 把 **0 内联进每个调用点**，`javap -constants CloudAccountsViewModel.class`
   显示 11 条 `SETTINGS_CLOUD_* = 0`。`strings.xml` 与 `resources.arsc` 里文案都在
   ⇒ 不是资源被裁。全仓宽松扫描确认只有这一个文件有同款写法。
2. **四处修法**：① 11 条改普通 `val`（非编译期常量，`<clinit>` 生成
   `getstatic R$string.* → putstatic`，`javap -c` 已验证；运行时读非 final 真值）；
   ② 抽 `internal safeStringRes(id)` 认 `Resources.ID_NULL`，`NoticeSnackbar` 与
   `CloudError.Res` 两处变量式 `stringResource` 都过它；③ `NoticeSnackbar` 对无效 id
   也 `onConsumed()`，避免 0 永远挂在 `UiState`；④ `CloudError.Res` 降级
   `error_unknown_no_detail`，抽 `@Composable cloudErrorText()` 把页面圈复杂度
   从 16 压回 detekt 上限 14。
3. **⚠️ 订正 #289**：`probeWebDav()` 成功分支同样写
   `notice = SETTINGS_CLOUD_PROBE_OK`（也是 0），且在 `refresh()` **之前** ⇒
   #289「连上就闪退」极可能就是这个 0。#289 的 `launchGuarded` **仍必要**
   （无全局兜底时裸 `viewModelScope.launch` 未捕获异常确实杀进程、重 IO 确实压主线程），
   但它兜的是**协程内部**异常；重组期异常不在其作用域。⇒ 两次闪退同源，
   #289 修了真实健壮性缺口，但没修掉当时那次闪退。

**验证**：`detekt` + 全模块 `test` + `:app:assembleFullDebug`
`BUILD SUCCESSFUL in 1m 52s`（354 tasks：37 executed / 10 from cache / 307 up-to-date）；
**1860 例 0 失败**（186 份 XML，比 #289 的 1851 例多 9 例、多 2 份）；
**33 项 Python 静态 / 文档门禁全绿**（17 `check_*` + 13 `selftest_*` + 编码 /
changelog 自检 / 图标几何）。新建 `CloudAccountsResourceIdRulesTest`（4 条源码门禁）
+ `CloudAccountsResourceIdBehaviorTest`（5 例行为，含 R 值环境探针）。
**反证**：把 1 条 `val` 改回 `const val` ⇒ 3 例立刻变红；撤掉 `safeStringRes`
守卫 ⇒ 2 例立刻变红；源码均已恢复。

**本轮 6 条坑**：编译期 R 类不是真值（`0x0` 占位）；`stringResource(0)` 是**重组期**
异常，任何协程异常边界都兜不住；变量式资源解析必须先验 `Resources.ID_NULL`；
`val` 与 `const val` 差一条 `getstatic`，改完要 `javap -c` 看 `<clinit>`；
detekt `CyclomaticComplexMethod`（页面塞 `when` + `?.let` + `?:` 从 14 涨到 16）；
测试注释里写 `R.string.y` 示例会被 `selftest_cross_file_private.py` 判成悬空引用。

**提交 / CI**：`5553bda4 fix(settings): #290 注销网盘账号不再因 stringResource(0) 闪退`
已推送 `main`；`Android CI (Debug)` **success**（17:29:58 → 17:34:34，约 4m36s，
`Sandbox unit tests` 与 `Lint, test diagnostics, and debug build` 两个 job 全绿，
`Run unit tests (hard gate)`、`Publish Debug APK to preview Release` 逐步通过）；
preview Release 自动更新为 `Vaultix v0.13.0-dev-5553bda · Preview`
（`Vaultix-v0.13.0-dev-5553bda-preview.apk`，37,575,798 B ≈ 35.8MB）。
文档回填为第二个提交；`AI docs consistency` 由该文档提交触发。

**待真机验收**：注销 WebDAV / OneDrive 账号不闪退且弹对应成功提示、账号从清单消失；
测试连接成功（#289 主路径）不闪退并弹「连接成功，账号已保存」；重复点注销 /
立刻返回不闪退；校验失败红字正常（`CloudError.Res` 路径）；logcat 不再出现
`Invalid resource ID 0x00000000`。

---

## 上一轮（2026-11-13 · #289 连上 WebDAV 后闪退、重进账号已添加）— 归因已被 #290 收窄

> ⚠️ **订正**：本段把闪退定性为「成功之后的协程未捕获异常」，但 #290 的真机日志 +
> 字节码证明两次闪退同源，真因是 `const val R.string.* = 0` 在 Compose 重组期
> `stringResource(0)` 崩溃。`launchGuarded` 仍必要但不是这次闪退的根因。
> 以下保留原文作过程留痕，结论以 #290 段为准。

> **一句话**：真机「连接上 webdav 后，会闪退出来。重新进入后会发现网盘账号已经添加进去了」。
> 「账号已添加」= **凭据已落盘** ⇒ 崩溃点必然在**成功之后**；工程里没有全局
> `CoroutineExceptionHandler`（#98 实测），而两个 ViewModel 全是裸
> `viewModelScope.launch` ⇒ 清单读取链路里任一处抛出都会**杀进程**。定性为
> **协程异常边界 / 主线程 IO**，不是连接失败。接力正文：[`#289`](./issues/06-界面与交互-part27.md)。

1. **取证**：`probeWebDav()` 成功后依次走 `_state.update`（probe OK）→ `refresh()`
   → `CloudAccountInventory.list()`（Room Flow `.first()` + Keystore 解密 + MSAL
   `listCachedSessions()` + Compose 重组）。#242 早就记过「`launch` 的异常在 ViewModel
   scope 里表现为静默终止」，但当时只在 `VaultListViewModel` 补了兜底，这两个
   ViewModel 一直裸着。⇒ **不猜具体异常**：成功之后有五六处可抛，逐个 try/catch
   追不完，要补的是**边界本身**。另查出凭据写入 / 自检 / 清单读取全压在默认主线程。
2. **四处修法**：① 两个 ViewModel 共 10 个动作全部收口到 `launchGuarded(label, block)`
   （`runCatching { block() }.onFailure { ... }`，`CancellationException` / `Error`
   原样重抛，其余写 `VaultixLog.e(TAG, error)`、把 `busy` / `loading` / `PROBING`
   拉回终态、错误进 `CloudError.Detail` / `UnlockUiError.Detail`，每个文件只留定义里
   **一处**裸 launch）；② 重 IO 显式 `withContext(Dispatchers.IO)`（设置页凭据写入 +
   自检 + 回滚 + `refresh()`、`inventory.list()`；添加页 `cloudAccounts.list()`）；
   ③ `probeWebDav()` 内部两处 `runCatching` 也区分取消，取消被吞成「自检失败」会
   **误删刚写好的凭据**，回滚本身也包住不许盖掉原始原因；④ 添加页
   `pickAccount` / `openDirectory` / `goUp` / `submit` 同步收口，`return@launch`
   → `return@launchGuarded`。
3. **⚠️ 取舍**：刻意**不**在 `launchGuarded` 统一入口改 dispatcher ——
   `signInOneDrive()` 走 MSAL 必须主线程；重 IO 由各调用点自己 `withContext`。
   **判据第二次反转**：#288 修的是「连不上」，本轮真机把焦点推到同一条主路径的
   **成功之后**。

**验证**：`detekt` + `:app:assembleFullDebug` + 九模块全量单测
`BUILD SUCCESSFUL in 3m 19s`（315 tasks：24 executed / 33 from cache / 258 up-to-date），
**1851 例 0 失败**（184 份 XML，比 #288 的 1843 例多 8 例、多 2 份 XML）；
**全套 Python 静态 / 文档门禁全绿**。新建 `CloudAccountsCrashGuardTest`（4 例行为）
+ `WebDavLaunchGuardRulesTest`（4 条源码门禁）。**反证**：临时撤掉 `launchGuarded`
的异常兜底，2 例行为用例立刻变红，源码已恢复。全量验证两次被门禁拦住并修好：
detekt `TooGenericExceptionCaught` ⇒ 改 `runCatching`；`SettingsStyleRulesTest`
中文硬编码 ⇒ 10 个日志 label 统一改 ASCII。

**本轮 6 条坑**：无全局 `CoroutineExceptionHandler` 时裸 `viewModelScope.launch`
未捕获异常 = 进程退出（「已保存」只证明写盘成功）；detekt `TooGenericExceptionCaught`
逼宽边界改 `runCatching`；设置页门禁把**中文日志 label** 也判成 UI 硬编码中文；
`runCatching` 会吞 `CancellationException` 必须显式重抛；统一入口别顺手切 dispatcher
（MSAL 必须主线程）；沙箱恢复后 `ANDROID_HOME` / `local.properties` 丢失 ⇒ Gradle 报
`SDK location not found`，补 `sdk.dir=/opt/android-sdk` 即可，不是代码问题。

**提交 / CI**：`731cdaee fix(webdav): #289 连接成功后的未捕获异常不再闪退` 已推送 `main`；
`Android CI (Debug)` **success**（11:08:27 → 11:25:21，约 17 分钟；本轮 CI 无缓存、
`Lint, test diagnostics, and debug build` 与 `Sandbox unit tests` 两个 job 全绿，
其中 22 个前置门禁步骤与 `Run unit tests (hard gate)`、`Publish Debug APK to preview Release`
逐步通过）；`Sandbox unit tests` 单独 3m10s success。
preview Release 自动更新为 `Vaultix v0.13.0-dev-731cdae · Preview`
（`Vaultix-v0.13.0-dev-731cdae-preview.apk`，37,575,798 B ≈ 37.6MB）。
文档回填为第二个提交；`AI docs consistency` 由该文档提交触发。

**待真机验收**：应用密码 + 关二次验证后点测试连接应成功且**不闪退**、账号出现在清单；
成功后立刻返回 / 停留都不闪退；401 / 403 / 404 / 超时显示对应红字而非闪退；
添加页选账号 / 进目录 / 返回 / 提交任一处故障显示错误态；注销 / OneDrive 登录 /
换号 / 健康探测同样不闪退；异常时 logcat 能搜到 `CloudAccounts` / `AddCloudVault` 的 ERROR。

## 🕐 上一轮状态（2026-11-13 · #288 坚果云 WebDAV 403「登录不上去」）

> **一句话**：真机截图「测试连接 → WebDAV 账号或密码不正确（HTTP 403）」。
> 匿名 / 假凭据差分实测 12/12 全 401 ⇒ 用户拿到的 403 是**认证已通过后的服务端拒绝**，
> 旧实现把 401/403 合并成「密码错」，用户只会无限重输；且自检用 HEAD 探目录，
> 与 `listChildren()` 不同源。接力正文：[`#288`](./issues/06-界面与交互-part26.md)。

1. **取证**：沙箱对 `dav.jianguoyun.com` 匿名 / 假 Basic × 4 方法 × 3 UA 全部回
   `401 + WWW-Authenticate: Basic realm="nutstore"`；坚果云官方 FAQ 把 403 归因为
   微信二次验证 / 访问频率 / 应用密码被撤销 / 目录权限。**证伪上一轮 P0**：
   `accessTokenForHost()` 对陌生 host `return null`，坚果云域名不会命中 Bearer 覆盖。
2. **四处修法**：① `testConnection()` 主探 `PROPFIND Depth: 0`，`405/501` 才降级 HEAD，
   与 `listChildren()` 同源、404 不再假报成功，两条路径共用 `probeFailureOf()` 判据表；
   ② 401/403 分流到 `credentialsMessage` / `forbiddenMessage` 两处真源，
   **`errorMessage()` 也分流**（stat/read/list/强写的共同出口），403 明确「不是密码错」
   + 四条可排除原因；③ 密码框下加「应用密码 / 关二次验证」提示，填之前就说清；
   ④ 新增 `webDavProductionClient(shared)`（`newBuilder()` 共享连接池、清
   interceptors / networkInterceptors / Authenticator），`CloudAccountConnector` 与
   `KdbxCloudSyncCoordinator` **两个构造点**都过它，顺手修掉上一轮 P0 并订正三处过时 KDoc。
3. **⚠️ 取舍**：P0 这次不是真因，但同机 Vaultwarden + WebDAV（同域名不同端口也会命中，
   `hostOf()` 用 `URL(server).host` 不含端口）会覆盖 Basic 并把 token 发到 NAS，仍属必修。

**验证**：`detekt` + `:app:assembleFullDebug` + 九模块全量单测
`BUILD SUCCESSFUL in 1m 6s`，**1843 例 0 失败**（182 份 XML，比 #287 多 16 例：
新增 12 例 + 远端 12 个提交带来的 4 例）；**33 项 Python 门禁全绿**。新建
`WebDavConnectionTest`(7 例行为) + `WebDavConnectionRulesTest`(5 条源码门禁)。
**反证**：临时撤掉 `interceptors().clear()`，
`专用client必须清掉共享拦截器且保留Basic` 立刻变红（`7 tests completed, 1 failed`），
源码已恢复。

**本轮 6 条坑**：403≠401（合并文案会把用户动作锁死在重输密码）；
自检与使用必须同源；错误分类要放**共同出口** `errorMessage()`；
**Kotlin 块注释可嵌套**（KDoc 里写块注释起始符会吞掉后面整段代码）；
`forEach { }` 要 `}` 闭合（写成 `)` 会吞掉后面所有成员函数）；
detekt `ThrowsCount` 上限 2 ⇒ 改 `String?` 判据表 + 单一 throw，`MaxLineLength` 也要拆表格行。

**提交 / CI**：`f5635822 fix(webdav): #288 坚果云 403 不再谎报密码错 + 自检改 PROPFIND + 专用 client` 已推送 `main`；`AI docs consistency` success（10s）、`Android CI (Debug)` success（4m23s，`Lint, test diagnostics, and debug build` 与 `Sandbox unit tests` 两个 job 全绿）；preview Release 自动更新为 `Vaultix v0.13.0-dev-f563582 · Preview`（`Vaultix-v0.13.0-dev-f563582-preview.apk`，37,575,802 B ≈ 37.6MB）。

**待真机验收**：生成应用密码 + 关二次验证后测试连接应成功；仍失败时红字必须列 403
四条原因；账号页 OK 后添加页能列目录；错密码报 401 且文案不同；不存在子目录提示
「找不到该目录」；同机 Vaultwarden + WebDAV 时 NAS 日志无 Bearer / `Bitwarden-Client-*`；
自建 NAS 的 PROPFIND / HEAD 两种响应都要能过。

## 🕐 上一轮状态（2026-11-12 · #287 改完 KDBX 条目立刻返回等于没保存）

> **一句话**：用户补充时序「点保存后停 5 秒看到『已保存到本地 · 正在同步到云端』
> 才生效，但大部分人点完就返回」——**判据第三次反转**：#286 修的同步闸门没坏，
> 坏的是更前面的一段：写回协程跑在 `viewModelScope` 里，页面返回把它取消在
> `Kdbx.verifyWrite()` 的 KDF 几秒窗口内。属**协程取消 / 写回持久性**缺陷。
> 接力正文：[`#287`](./issues/06-界面与交互-part25.md)。

1. **根因**：`Kdbx.mutateLocked` 先把新会话换进内存（所以改完立刻能看到改动），
   `persist()` 旧顺序却是**先** `Kdbx.verifyWrite()`（KDF 往返自检，真机 1~5 s）
   **再**写带 `pendingLocalEdits` 的脏缓存。用户在这几秒返回 ⇒ `onCleared()` 取消
   `viewModelScope` ⇒ `fileCache.save` / `cloudSync.markLocalEdited` /
   `autoUploader.enqueue` 全没跑 ⇒ DB 仍是 `IN_SYNC`、缓存没有脏标记。此时 #286 的
   双真源判据（DB 状态 + 缓存脏标记）**两个都干净**，点同步就放行拉远端旧字节
   `replaceSession()` ⇒ 改动彻底消失。停留 5 秒只是让协程跑完、提示出现、上传排队。
   ⇒ **先保证「写回必须跑完」，再谈「同步闸门别覆盖」**，两道防线串联不是并联。
2. **四道防线**：① 八个写动作共用的 `serialWrite` 改
   `withContext(Dispatchers.IO + NonCancellable) { writeGate.withLock { block() } }`
   （⚠️ `withContext` 在 `withLock` **外面**，等锁期间也不许取消；一处收口，不靠每个
   ViewModel 各自记得）；② `persist` 把脏缓存**提到几秒自检之前**先落，本地 IO 毫秒级，
   把盲窗压到最小；③ 新增 `cacheRestore` 快照，自检 / 落盘失败时恢复缓存
   （原本无缓存则 `fileCache.remove`）+ 回滚会话，`CancellationException` **只**重抛、
   不记 ERROR、不跑回滚链；④ `ItemDetailViewModel.updateItem()` / `deleteItem()` 统一
   `viewModelScope.launch { withContext(NonCancellable) { … } }`，让 `saving=false`
   与成功提示也能收口。`deleteItem` 早在 11-08 就用 `launch(NonCancellable)` 修过同一个
   坑，编辑没跟上 ⇒ 这次把防线放到**共享咽喉**。
3. **⚠️ 取舍**：点完保存立刻锁库 / 切后台时写回仍会跑完（几秒）。这是对的 —— 取消一次
   半途的落盘只会制造「会话 / 缓存 / DB 状态」三份互相矛盾的真相。

**验证**：`detekt` + `:app:assembleFullDebug` + 九模块全量单测 `BUILD SUCCESSFUL in 19s`
（大量 UP-TO-DATE，输入未变即结果有效），**1827 例 0 失败**（179 份 XML，比 #286
多 6 例）；**33 项 Python 门禁全绿**。新建 `KdbxWriteDurabilityRulesTest`(5 条源码门禁)，
`KdbxItemRepositoryTest` +1 条行为测试；相关三测试类 20 例 0 失败。
**反证**：临时撤掉 `serialWrite` 的 `NonCancellable`，行为用例立刻变红
（`9 tests completed, 1 failed`），证明测的正是「返回即取消」这条真机链路，源码已恢复。

**本轮 6 条坑**：`launch(NonCancellable)` 已 deprecated（破坏结构化并发）⇒ 统一
`launch { withContext(NonCancellable) { } }`；`functionBody()` 必须从**签名起点**截，
不能从第一个 `{` 截（`serialWrite` 是表达式体，第一个 `{` 属于 `withContext` lambda，
从那里截会漏判要检查的 `withContext` 本身）；取消不是失败（取消态下每个挂起点立刻再抛，
回滚链跑不完）；detekt `ThrowsCount` 上限 2 ⇒ 合并两个 catch 用
`if (error !is CancellationException)` 区分；行为测试用 `runBlocking` 真实时间而非
`runTest`（虚拟时间会在切 `Dispatchers.IO` 后立刻推进到超时）；行为测试必须做反证。

**提交 / CI**：`7fc7cbc5 fix(sync): #287 改完 KDBX 条目立刻返回不再丢改动`
已推送 `main`；`AI docs consistency` success（12s）、`Android CI (Debug)` success
（4m22s，`Lint, test diagnostics, and debug build` 与 `Sandbox unit tests` 两个 job 全绿）；
preview Release 自动更新为 `Vaultix v0.12.0-dev-7fc7cbc · Preview`
（`app-full-debug.apk`，37,558,886 B ≈ 37.6MB）。
CI 结论与 Release 版本按 #286 `68665533` + `5748f5a7` 的两提交惯例补记。

**待真机验收**：改完点保存**立刻返回**再点同步（主路径）；连改 3 笔立刻返回；
保存后立刻杀进程 / 立刻开飞行模式；冲突对话框「用云端覆盖本地」仍不自锁；
另七个写动作（删除 / 新建 / 建文件夹等）立刻返回也不丢。

## 🕐 上一轮状态（2026-11-12 · #286 OneDrive KDBX 改动被刷新覆盖）

> **一句话**：用户报「onedrive 上的 kdbx 改完条目，同步中途返回 / 断网后刷新，
> 直接被云端旧内容覆盖，等于修改并未成功」——定性为**数据完整性 / 同步状态机**
> 缺陷（不是 UI 观感），两个叠加根因都修完并闭环。
> 接力正文：[`#286`](./issues/06-界面与交互-part24.md)。

1. **刷新覆盖**：`persist` 把改动写成带 `pendingLocalEdits` 的脏缓存并标
   `PENDING_UPLOAD`，自动上传一跑就把 DB 状态洗成 `SYNCING`（失败则 `FAILED`）；
   而闸门读 `impliesLocalChanges`，它**不认** `SYNCING` / `FAILED` ⇒ 误判「本地没改」
   → 拉远端旧字节换会话 → `markResolved` 记已同步 → `fileCache.save` 清掉脏标记
   ⇒ 改动永久消失。修法：`hasUnsyncedLocalEdits()` = **DB 状态 或 缓存脏标记**的
   唯一判据；`resolveUsingRemote()` 下载**前 + 后**两道闸门都走它；
   `localChangedSinceLastSync()` 委托协调器；`readRemoteCurrent()` 回写缓存前现查
   脏标记（`dirtyNow` / `loadFailed`，读失败也 fail-closed）。
2. **返回 / 断网等于没同步**：`enqueue` 只在 `persist` 那一刻调用。新增
   `resumePendingUploadsNow()` 扫全表 + `startUnlockObserver()` 订阅
   `Kdbx.sessionRevision()` 按 `newlyUnlockedIds` 差集补传；`Application.onCreate()`
   **先订阅再扫存量**（无窗口）。
3. **★ 本轮补正（第二轮）**：第一版只做「启动扫一次」是**空转** ——
   `Kdbx.saveVia()` 要求 `KdbxSessionStore` 有会话，冷启动锁态必然 `NotUnlocked`，
   会写假 `FAILED` 并退避 3 次；而全仓解锁路径都**没有**同步钩子。⇒ 补传只处理
   **已解锁**的库（`sessionReady = Kdbx::isUnlocked`，可注入便于单测），锁着的库
   等解锁事件精准补传。⚠️「改动不丢」由第 1 条保证，补传只负责自动收敛，两件事别混。

**验证**：`detekt` + `:app:assembleFullDebug` + 九模块全量单测
`BUILD SUCCESSFUL in 41s`，**1821 例 0 失败**（178 份 XML，比 #285 多 15 例）；
**23 项 Python 门禁全绿**。新建 `KdbxPendingEditRulesTest`(6)，
`KdbxCloudSyncCoordinatorTest` +4 / `CachedKdbxFileSourceTest` +1 /
`KdbxAutoUploaderTest` +4；相关四测试类 47 例 0 失败。

**提交 / CI**：`68665533` 已推送 `main`，`AI docs consistency` success（9s）、
`Android CI (Debug)` success（4m51s，两个 job 全绿）；preview Release 自动更新为
`Vaultix v0.12.0-dev-6866553 · Preview`（`app-full-debug.apk`，37.6MB）。

**待真机验收**：上传中途返回 / 断网后刷新必须弹冲突而非变回旧内容；杀进程冷启动、
解锁后改动自动补传（不必再点同步）；下载那 1–10 s 内再编辑，会话与缓存都不被盖。

**沙箱环境坑（本轮复现）**：`./gradlew` 前须 `source /opt/vaultix-android-env.sh`，
否则 `SDK location not found`；长验证走前台并 `cmd > log 2>&1; rc=$?; tail; exit $rc`
（后台任务会被沙箱休眠中断、管道会吞退出码）；zsh 不做变量单词分割，
门禁命令别用变量拼整串。

## 🕐 上一轮状态（2026-11-12 · #285 六组 UI / 主题 / KDBX 反馈）

> **一句话**：用户带 4 张真机截图提了六组观感 / 主题问题，**六组全改了生产代码**。
> 接力正文：[`#285`](./issues/06-界面与交互-part23.md)。

1. **网盘账号卡精修**：从「设置页一组多行」的 `SettingsGroupCard` + `SettingsRow`
   （72dp 行 + 20dp 内距 + 一整行按钮 ⇒ 又高又空的深灰板）改为**一个账号一张紧凑卡**；
   「已连接」从卡片外收成行尾徽标；主操作 48dp 满宽胶囊与 40dp outlined「注销」
   统一为两枚同规格 M3 按钮（主次靠填充不靠尺寸）；全宽按钮与卡片同款圆角。
2. **域名分组整体化**：**反转 #282「分组头成块」** —— 带底 + 圆角的头与同形状条目卡
   并排，读成插进列表的分割条。改无背景 section header + 列表 `spacedBy(0)` 由 item
   自给三档间距（**组间 20 > 组内 4 > 头与首条 2dp**），整体感交给 Gestalt 接近性。
3. **详情页长标题**：`TopBarTitle` 加 `singleLine`，详情页传 `true` 只走「缩字号 →
   省略号」，不再把 `accounts.spotify.com` 折成孤字行；库名顶栏仍允许两行。
4. **「+」剪影卡配色**：**作废 #282/#283 的 `xxxContainer` 交叉 + 两层 alpha 分档**
   （用户说的「奇怪颜色」正是这套做法本身，container 随主题翻转 ⇒ 深浅各调一遍仍浑浊），
   改 **M3 fixed accent roles**：卡底画纯 `xxxFixed` / `xxxFixedDim`，文案 / 线条用
   `onXxxFixed` / `onXxxFixedVariant`，删光 7 个 alpha 常量与 `isDarkSurface` 判档。
   5 型靠「3 色相 × Fixed / FixedDim 两档」区分，不硬造第 4 个色相。
5. **主题 / 动态取色**：反编译取证两个根因 —— 静态色板**一个 fixed role 都没传**
   （关闭动态取色时回退 M3 基线紫，开则壁纸派生色，两档跳变）+ `dynamicColor` 默认
   `true` 让首次启动绕过品牌浅色板。补齐 12 个 fixed 命名参数（色值 Python 实测
   饱和度 22%~35.5%、前景对比 6~12:1）、三处默认值统一 `false`、新增
   `supportsDynamicColor()` 单一真源让低版本开关**禁用 + 说明**、OLED 纯黑压整套
   9 档 surface 家族。
6. **KDBX 顶栏标题**：`VaultOriginLabel.vaultTopBarTitle()` 单一真源，KDBX 一律显示
   `KDBX`；只改顶栏，列表状态行仍带文件名。

**验证**：detekt + `:app:assembleFullDebug` + 十模块全量单测 `BUILD SUCCESSFUL in 44s`，
**1806 例 0 失败**（177 份 XML，比 #284 多 15 例）；**33 项 Python 门禁全绿**。
新建 `ThemeDefaultRulesTest`(4) / `TopBarTitleRulesTest`(3) / `CloudAccountCardStyleTest`(3)，
`AddActionSheetStyleTest` 三条规则**整体反转**、`ItemsRowStyleTest` 反转规则 3 + 新增规则 6、
`VaultixBrandColorTest` 补 fixed 家族 4 类。

**本轮三处 detekt / 门禁坑**：`functionBody()` 不含参数签名（#283 同款，补
`functionSignature()`）；裸 `Color(0xFF…)` 函数参数触发 `MagicNumber`（抽属性常量）；
规则 3 反转后圈复杂度 15 > 14（拆 helper）。⚠️ **管道吞退出码**：`gradlew | tail`
让 shell 显示 exit 0 而实际 `EXIT=1`，判 rc 一律 `cmd > log 2>&1; rc=$?; exit $rc`。

**提交 / CI**：`e12fb13a feat(ui): #285 六组 UI / 主题 / KDBX 整改` 已推送 `main`；
`AI docs consistency` success（9s）、`Android CI (Debug)` success（5m11s，
`Lint, test diagnostics, and debug build` + `Sandbox unit tests` 两个 job 全绿）。
本轮本地按项目约束只跑 detekt / 编译 / 九模块单测（`BUILD SUCCESSFUL in 2m 29s`），
出包与 lint 交给 CI；preview Release 由 CI 自动更新为
`Vaultix v0.12.0-dev-e12fb13 · Preview`（资产 `app-full-debug.apk`）。
⚠️ 复核时踩到沙箱恢复后 `ANDROID_HOME` / `local.properties` 丢失（`SDK location not found`），
先 `source /opt/vaultix-android-env.sh` 即可；后台任务在沙箱休眠时会被中断，
长验证一律前台跑并 `cmd > log 2>&1; rc=$?; exit $rc`。

## 🕐 上一轮状态（2026-11-12 · #284 取消按钮规格统一 + KDBX 通行密钥排查）

> **一句话**：用户两组反馈（设置页「取消」大小 / 形态与正常按钮不一致 + KDBX 能否存通行密钥 /
> 管理部分是否正常）。**都改了生产代码**：① 反编译取证推翻 #273/#283 的 intrinsic 修法；
> ② 排查结论「KDBX **能**存」，但发现手工录入会产出半截凭证、写入后读回静默丢失。
> 接力正文：[`#284`](./issues/06-界面与交互-part22.md)。

1. **判据反转（#283 规则 4 作废）**：解包 `material3-android:1.5.0-alpha16` 反编译确认
   `AlertDialogFlowRow` 就是纯 `FlowRow`、Dialog 全家零 `IntrinsicSize`；`git show d8559668`
   证明依赖版本与 #273 时相同 ⇒ #273「`indentChar` + `IntrinsicSize.Max` 测成 0 宽」的
   根因本就是猜的。`width(IntrinsicSize.Min)` 会把宽度**固定**成 `minIntrinsicWidth`，
   #283 的 `widthIn(min=MinWidth)` 只是把小方块托到 64dp ⇒ 63 处动作槽调用（21 个使用文件）
   全是固定方块，
   而「其他正常按钮」（`DialogShell` 家族用 `FlowRow` + 原生 M3 按钮）是 wrap-content
   ⇒ 两套规格并排。修法：删 intrinsic / 固定宽度，**只留** `widthIn(min = MinWidth)` 下限 +
   显式 `contentPadding`；#282 的「取消必须有边框」**不反转**。
   `DialogStyleRulesTest` 规则 4 反转为「禁止固定宽度」，`SettingsStyleRulesTest` 规则 2
   保留但 KDoc 勘误（有效理由是「规格单点真源」，不是竖排）；7 处生产旧注释同步订正。
2. **KDBX 能存通行密钥**：`KdbxPasskeyCodec` ↔ `KdbxItemWriter.applyPasskeys()`
   （私钥 PEM / `credentialId` / `userHandle` 强制 `EntryValue.Encrypted`）↔
   `KdbxItemMapper` 读回；`ItemRepositoryImpl.updateFido2Credentials()` 自 2026-10-13 起
   对 KDBX 放行。管理页（`PasskeysViewModel` / `PasskeysScreen`）列表 / 搜索 / 删除 / 详情
   均完整；通行密钥只绑 `VaultItemType.Login`，没有独立条目。
3. **真缺陷：写入侧半截凭证 ⇒ 存了读不回来**：`toCredential()` 要求
   `rpId / credentialId / keyValue / userName / userHandle` 五项非空，缺一返回 `null`；
   而 `SavePasskeyDialog` 只校验前三项、不产出 `userHandle` 且允许 `userName` 留空，
   `PasskeyCreateActivity` 在 RP 不给 `user.id` 时同样留空 ⇒ KDBX 里写入成功、重开库静默消失
   （最容易被误判成「KDBX 不支持」）。修法：`WebAuthn.randomUserHandle()`
   （32 字节 → base64url 无填充）只在**没有真实值时兜底**；手工录入 `userName` 必填、
   `userHandle` 选填留空自动补、补齐 `keyAlgorithm` / `keyType` / `keyCurve` /
   `creationDate` / `counter` / `discoverable`；删 `PasskeysViewModel` 里会诱导复用半成品的死代码。
4. **三层测试闭环**：`WebAuthnTest` +1（合法性）、`KdbxPasskeyWritebackTest` +2
   （正向往返 + **故意钉住失败形态**）、新建 `SavePasskeyDialogRulesTest` 3 条源码门禁
   （构造块带齐必填字段 / `userName` 空白校验 / 系统注册路径的兜底表达式）。
   ⚠️ 源码门禁用 `balancedCallEnd()` 只扫 `VaultFido2Credential(...)` 构造块 + `stripComments()`，
   别整文件 `contains` 被注释骗过。
5. **本轮环境坑**：`:core:common:test --tests "…"` 不是合法用法（该模块用模块级任务）；
   zsh 下 `PIPESTATUS` 为空（打印 `EXIT=` 缺值），判 rc 用 `cmd > log 2>&1; rc=$?`。

> 验证：`:app:compileFullDebugKotlin` 绿（仅既有 `hiltViewModel` deprecation 警告）、
> `detekt` / `:app:assembleFullDebug` 绿、CI 同款全量单测 **1791 例 0 失败**
> （`DialogStyleRulesTest` 4 / `SavePasskeyDialogRulesTest` 3 / `SettingsStyleRulesTest` 6 /
> `KdbxPasskeyWritebackTest` 11 / `WebAuthnTest` 13 均确实执行）、
> 16 个 `check_*.py` + 13 条 `selftest_*.py` + 编码 / 图标几何 / detekt 圈复杂度探针
> 共 **32 项全绿**。**未验证（真机）**：所有「取消」在 2.0x 字号 / 窄屏下是否与同对话框
> 其他按钮一致、KDBX 手工录入通行密钥关库重开是否还在 —— 见 `Docs/progress/next-steps.md`。

## 🕐 最新状态（2026-11-12 · #283 深色模式光照重构 + 三组 M3 观感整改）

> **一句话**：用户 3 组反馈（取消按钮 M3 规格 / 分组计数太显眼 / 深色剪影卡与底栏）。
> **全改了生产代码**，并按用户要求先做「优秀项目怎么处理深色玻璃」的规范取证。
> 接力正文：[`#283`](./issues/06-界面与交互-part21.md)。

1. **取消按钮**：#273 的 `width(IntrinsicSize.Min)` 只治了「逐字竖排」，没治「被压成贴字
   小胶囊」。⇒ 在它**之前**补 `widthIn(min = ButtonDefaults.MinWidth)`（顺序反了会被
   `fixedWidth` 钳掉），两个组件再显式声明 M3 `contentPadding`。全项目 Grep 确认
   **22 个文件 60+ 处** `AlertDialog` 动作槽**全部**走共用组件、零裸按钮漏网 ⇒ 改一处
   即全量生效。`DialogShell` 走 `FlowRow`、用原生 M3 按钮（自带 MinWidth），其 16dp
   padding 是 11-06 真机「三按钮被裁」后刻意收的一档，**不动**。
2. ⚠️ **判据反转**：#282 把站点计数做成 `tertiaryContainer` + `CircleShape` 胶囊，用户说
   「给人一种要点击的感觉」—— 高对比圆点在 M3 里就是 chip / badge，会造第二个假目标。
   ⇒ 退回 `onSurfaceVariant` 纯文字（无底 / 无形状 / 无 padding），`weight(1f)` 右对齐保留；
   `ItemsRowStyleTest` 规则 3 同步反转、写明旧判据作废。
3. **深色剪影卡**：`xxxContainer` 底配 `onSurface` 前景（M3 应配 `onXxxContainer`）+ 两层
   半透明把三个色系洗成浑浊灰 + `onSurfaceVariant × 0.34` 线条对比不足 ⇒ `GlyphPalette`
   补 `onTint` / `onWash`，深色叠色 `0.68→0.94` / `0.62→0.92`、线条 `0.34→0.62`，
   判深浅复用 `isDarkSurface`（按实际底色，不用 `isSystemInDarkTheme()`）。
   **浅色档 4 个数值一个没动**。
4. **深色底栏「外围脏」= 三者全开满**：白高光 0.48/0.62 + 黑内阴影 + 8dp 黑外影，且
   三种装饰是 `Frosted` / `Liquid` **两档共用**（所以用户说常态化与液态玻璃都脏）。
   取证 Material Dark theme（elevation 靠表面色阶 / 别用浅色 glow 冒充暗影 / 自定义
   `shadow(8dp)` 是反模式）、Apple Liquid Glass（specular 从 0.1–0.2 起、阴影 mild、
   全开满 ⇒ muddy gray）、玻璃拟态（面板白 5%–12%）⇒ 新增 `glassLighting()` **一处定义**
   三档：`sheenScale 0.35` / `shadeScale 0.45` / `shadowElevation 8→2dp`；8 处白高光走
   `style.sheenAt()`、3 处黑暗部走 `style.shadeAt()`，`sheen` 色相仍恒为白（收敛**强度**
   不改色相），#281 边缘厚度与 #282 去细边线**一个不动**。
5. ⚠️ **门禁代码自己也要过门禁**：detekt `CyclomaticComplexMethod ≤14` 本轮撞两次
   （生产 `liquidGlassStyle` 15、测试规则 12 的 `buildList` 18），都靠拆小函数解决；
   另两个坑：`functionBody()` 从 `{` 起、**不含参数列表**（新门禁自己写错，已补
   `functionSignature()`）；`… | tail` 会**吞掉 Gradle 退出码**（一律 `set -o pipefail`）。

> 验证：`:app:compileFullDebugKotlin` / `detekt` / `:app:assembleFullDebug` 绿、CI 同款全量
> 单测 **1785 例 0 失败**（`LiquidGlassDockRulesTest` 12 / `DialogStyleRulesTest` 4 /
> `ItemsRowStyleTest` 5 / `LiquidGlassTest` 26 / 新建 `AddActionSheetStyleTest` 3 均确实执行）、
> 17 项静态门禁 + 13 项自测全绿。**未验证（真机）**：深色两档底栏外围是否还有脏晕、
> 玻璃边缘有没有被削薄、深色剪影卡文案对比度、2.0x 字号下取消按钮对齐
> —— 见 `Docs/progress/next-steps.md`。

## 🕐 最新状态（2026-11-12 · #282 第五批界面 / 设置 / 条目 UI 整改）

> **一句话**：用户一次给 5 组反馈（底栏细边线 + 透明度、云图标、剪影配色、设置三处、
> 条目 M3 质感）。**五组全改了生产代码**，并**反转三处旧判据**。
> 接力正文：[`#282`](./issues/06-界面与交互-part20.md)。

1. **「中心更透、边缘更立」**：不是回退 #281 的质感回提。删掉两档共有的一圈 1dp
   `style.stroke` 细描边（用户说的「很小很小的外框边线」），边界交给 3dp 内阴影 + 镜面 rim；
   Liquid 填充 `0.50/0.62 → 0.44/0.56`、`INNER_CLARITY 0.10→0.13`、
   `INNER_VIGNETTE 0.09→0.06`、`SHADER_CLARITY 0.055→0.075`，**模糊与阴影不动**。
   `LiquidGlassDockRulesTest` 新增规则 11：降级 `return` 之后不许再出现 `style.stroke`。
2. ⚠️ **形状统一 ≠ 语义合并**：KDBX 云端库云图标改用 `CloudDone`（用户最新反馈覆盖
   #266 的「裸云 = 地点」形状判据），但**本地 KDBX 仍不画云**、KDBX **仍不产出条目级
   同步态**、三态枚举与三条读屏文案一个不动（`CloudSyncBadgeTest` 钉住）。
3. **判据不是誓言**：剪切板「取消」要边框，与 #275 定的规则 1 冲突 ⇒ **反转门禁**为
   「单选项对话框退出按钮必须有边框」，而不是偷偷改代码让旧规则继续绿；
   `DialogActionButtons.kt` KDoc 同步反转留痕。同理 #281 的填充下界 `0.45 → 0.42`
   是调参留痕、不是架构底线，边缘厚度判据（模糊 / rim / 内阴影）不动。
4. **低开销 M3 质感**：条目列表只在绘制 / 布局阶段动 —— 分组头成块 + 计数 tonal 胶囊、
   副标题恒定一行且类型徽标**对齐详情页已有的 `TypeBadge(NEUTRAL)`**、行尾四枚点缀
   整组装进**一个** `surfaceContainerHigh` 胶囊（零新增 State / 动画 / 层级），
   无点缀靠 `hasRowTrailing()` 早退。不改字号字重、不动 `SiteIcon` 缓存链、
   不动 `EntryCard` 描边、不动 `ItemsGrouping.kt`。⚠️ 其中**标题补 `maxLines = 1` 是本轮唯一的
   显示行为变更**（密码页长标题原先折行，其余三个列表共用的 `EntryCard` 早已单行）⇒ 单列
   第 5 条门禁钉住；规则 1 KDoc 曾误写成「标题早就如此」，已订正。新增 `ItemsRowStyleTest`
   5 条文本门禁。
5. ⚠️ **删除 Spacer 时别吞换行**：`replace_all` 连前导 `\n` 一起吃，会让相邻 composable
   挤到同一行；已逐处恢复并静态确认「同行两语句残留 = 0」。
6. ⚠️ **两条孤儿门禁不吃位置参数**（`check_orphan_state.py` / `check_orphan_strings.py`
   用 argparse），批量循环统一传根会 `rc=2` —— 那是**用错命令**不是代码红。
7. ⚠️ **本地全量验证的三个环境坑，别记成代码红**：
   - 后台 shell 不继承 `ANDROID_HOME` ⇒ `lintFullDebug` / `assembleFullDebug` 在**任务图阶段**
     就报 `SDK location not found`；同一条命令里 `export ANDROID_HOME=/opt/android-sdk` 即可
     （SDK 实际在 `/opt/android-sdk/platforms/android-37.0`，目录名带 `.0`）；
   - `lintFullDebug` 分析全项目时撞沙箱 **8 GiB cgroup** 上限：`memory.events` 显示
     `oom_kill=1`、`memory.peak≈8GiB`，表现为 `Gradle build daemon disappeared unexpectedly`
     —— **不是 lint 报错**，是 JVM 被容器杀掉。清场（`./gradlew --stop` + 停 Kotlin daemon）
     再跑，lint 能真正跑完；
   - 清场后 lint 报 **42 errors / 268 warnings / 3 hints**，但**全是既有问题、不是 #282 回归**：
     `ItemsScreen.kt` 那 9 条 `LocalContextGetResourceValueCall` 落在 986–1025 行（#278 分组
     逻辑），本轮 diff 只碰 141 / 870 / 2149+ 行；`LiquidGlassOptics.kt` 的 2 条 `NewApi`
     本轮根本没改。⚠️ CI 的 `Run lint` 是 `if: event != 'push'`（push 链路跳过）+
     `continue-on-error: true`（PR 只报告不阻断），workflow 注释明写「取证完成前不要删这个
     `if`、不要放宽 lint 规则」⇒ **别把既有 lint 红算成本轮回归，也别顺手去动那条门禁**；
   - `pkill -f KotlinCompileDaemon` 会连**执行它的 shell 自己**一起杀（模式串出现在命令行里），
     要用 `jps -l` 取 PID 精确 kill。
   另：文档里写死的「17 个 `check_*.py` / 14 条 `selftest_*.py`」实测是 **16 / 13**，
   已改为不写死的表述（数量以 `.ai/tools/` 实测为准）。

> 验证：`:app:compileFullDebugKotlin` 1m5s 绿、`detekt` 绿、`:app:assembleFullDebug` 34s 绿
> （APK 已产出）、CI 同款全量单测 **1778 例 0 失败**（`ItemsRowStyleTest` 5 /
> `LiquidGlassDockRulesTest` 11 / `DialogStyleRulesTest` 3 均确实执行）、全套静态门禁 +
> 全部自测 + CI 文档门禁 6 步 + 编码 + 图标几何 12 项全绿。`lintFullDebug` 本地红在
> **42 条既有问题**（见第 7 条），与本轮无关且 CI 不阻断。
> **未验证（真机）**：去细边线后边界、更透后的图标对比度、行尾胶囊拥挤度、动态取色下
> 剪影配色、PIN 按钮在 2.0x 字号下是否齐平 —— 见 `Docs/progress/next-steps.md`。

## 🕐 最新状态（2026-11-11 中午 · 底栏材质分档：常态磨砂 + 液态玻璃质感回提）

> **一句话**：用户要「不开液态玻璃时平常状态也通透磨砂」，并判 #280 后玻璃仍「薄、效果不强」。
> 复核查到 11-09 起一路**调淡**的历史 —— 玻璃是被调得没有「自己」了，不是不够透。
> 本轮**改了生产代码**：材质分档 + 质感回提（[`#281` 接力记录](./issues/06-界面与交互-part19.md)）。

1. **玻璃感 = 对比，不是透明度**：透出的内容与玻璃自身要拉开层次；只加透明度会越改越薄。
   加大模糊（透出色块而非可辨文字）才有余量回提填充，再补内阴影 / rim / 阴影做厚度。
2. **「有没有模糊」≠「哪种材质」**：开关关掉不等于停用整条采样链路。
   `DockMaterial{Frosted,Liquid}` 共用 `LiquidGlassLayer`，磨砂只跳过 AGSL 与动态光学。
3. ⚠️ **源层别乘开关**：`glassSourceActive` 只看 `isLiquidGlassBlurSupported()`；
   乘 `liquidGlassDock` 会让常态磨砂糊到空背景（真机表现为「底栏变灰一块」）。
4. **降级不可省**：半透明 + 无模糊 = 列表文字穿栏。API < 31 仍走实色 `Surface`；
   `liquidGlassStyle(blurAvailable = false)` 两档都返回 `alpha = 1f`。
5. **观感不要靠猜**：连续两轮「不达标」却给不出具体档位 ⇒ 这轮把模糊 / 填充 /
   内阴影 / rim 四个量做成交互预览滑杆（`/workspace/vaultix-dock-material-preview.html`），
   下一轮按数值固化。HTML 的 `backdrop-filter` 只近似 `RenderEffect`，真机观感仍待采集。
6. ⚠️ **detekt `LongParameterList ≤ 8`**：`glassRenderEffect` 已有 9 个实参，
   再加布尔就撞门禁 ⇒ 拆成 `frostedRenderEffect` / 液态玻璃两个入口，编译期就分档。

> 验证：detekt + 玻璃单测 **38 条**全绿（新增 6 数值规格 + 规则 10）；
> 真机磨砂观感 / 滚动帧率代价 / 液态玻璃是否仍薄**未验证**，见 `Docs/progress/next-steps.md`。

## 🕐 最新状态（2026-11-11 上午 · 液态玻璃第六轮：通透 / 流光 / 折射）

> **一句话**：用户说「通透没做好、点击的流光和折射细节没完善」。复核后**不是调参问题，
> 是五处没接通** —— 其中 `uGlow` 把「半径」当方差用，光晕实际只有 `√w ≈ 12px`。
> 本轮改了生产代码（4 个 kt + 2 个测试），不是只出报告。

| 交付 | 位置 |
|---|---|
| 接力记录（五个根因 / 四段施工 / 硬边界 / 验证） | [`06-界面与交互-part18.md` #280](./issues/06-界面与交互-part18.md) |
| 交互预览（跟手拖动 / 31-32 vs 33+ / 光晕半径 `√w` vs `w`） | `/workspace/vaultix-glass-optics-preview.html`（仓库外） |
| 源码 | `ui/glass/LiquidGlass.kt` · `LiquidGlassOptics.kt` · `LiquidGlassSource.kt` · `ui/shell/VaultixBottomDock.kt` |

### 判据（新增，长期有效）

1. **高斯量纲**：`exp(-d²/w)` 里 w 是**方差**；传半径就必须写 `exp(-d²/w²)`。
   AGSL 运行时编译、CI 覆盖不到 ⇒ `LiquidGlassDockRulesTest` 规则 8 扫源码钉住 `uGlow.w * uGlow.w`。
2. **表面反射不能只放 shader**：#277/#278 把光晕放在 API 33+ 的层 A，31-32 点按毫无反应、
   33+ 又被层 B 的实色填充盖一层。`drawOpticalBody` / `drawSurfaceSweep` 画在层 B ⇒ **31+ 全档可见**。
3. **折射法线取 SDF 梯度**，不是 `normalize(coord-center)`（药丸长边中段会斜）；
   且只在 `band > 0` 的边缘带算，别给中心像素平白加 4 次 sqrt。
4. **「跟手」与「零重组」可以兼得**：移动期持续上报但量化 0.02 才写一次 state；
   亮 120 / 灭 360 靠按方向切 `animationSpec`，不把动画值提到组合期读。
5. ⚠️ **管道 `| tail` 会吞掉 Gradle 的失败退出码**：detekt 实际报 2 处 `MagicNumber`
   却一度被记成通过。验证一律 `> log 2>&1; rc=$?` 取真实退出码。

> detekt 全绿、玻璃单测 **31 条**全绿（规则 9 + 数值 18 + 源坐标 4）、
> 全量单测 **1765 例 0 失败**、`assembleFullDebug` 出包成功、
> 17 个静态门禁 + 15 条自测 + 沙箱 typecheck（8 组 966 例）全绿；
> 真机 AGSL 运行时编译 / 31-32 实机档位 / 按压帧率**未验证**，改善幅度待采集。
>
> ⚠️ 顺带扶正一个**长期假红的本地自测** `selftest_cross_file_private.py`：
> `PinEnrollment.kt` 早在 `ae80aaec` 就被删掉，硬编码的「2 个调用方」期望恒不成立；
> 它**不在 CI 清单里**，所以只跑 CI 那几条自测时永远看不见。
> ⇒ 期望改为从源码现算 + 「至少 1 个」下限断言，不是把 2 改成 1（下次重构又会红）。
> **判据：探针自己红了也要有人看，否则「门禁绿」不可信。**

## 🕐 最新状态（2026-11-11 · 「不需要修」清单与判定方案）

> **一句话**：用户问「还有哪些是不需要修的，仔细看看，做出完善的方案」。
> 交付的不是又一份清单，而是**判据**：52 条分级登记 + 三问判定法 + 触发转修条件 + 自动门禁，
> 让下一轮排查不再把同样的误报、同样的刻意冻结重新列成待办。

| 交付 | 位置 |
|---|---|
| 判定方案（三问 / 五类 / 触发条件 / 复核机制 / 订正） | [`Docs/progress/audit/no-fix-register-2026-11-11.md`](../Docs/progress/audit/no-fix-register-2026-11-11.md) |
| **登记表唯一真源**（52 条：锚点 / 理由 / 转修条件 / 禁止重新表述） | [`audit/evidence/no-fix-entries-2026-11-11.md`](../Docs/progress/audit/evidence/no-fix-entries-2026-11-11.md) |
| 门禁 + 12 条自测 | `.ai/tools/check_no_fix_register.py` · `.ai/tools/tests/selftest_no_fix_register.py`（已接 `ci-docs.yml`） |

### 判据（新增，长期有效）

1. **「凭什么不修」比「不修清单」值钱**：三问 = ① 有无真实损害 ② 仓库有无知情记录
   ③ 什么情况触发转修。Q2 必须**沿时间线确认决策没被后续推翻** —— 差点把已被
   `decisions.md:47` 推翻的「不做 Bastion 文件级搬迁」当成现行判据。
2. **「不修」不是「永不修」，也不是「不用看」**：每条都带触发条件；D 类防回退、
   A 类防 Dependabot 顺手升，靠门禁的「锚点消失即报」自动兜住。
3. **门禁首次运行就抓到真矛盾**：`next-steps.md` 仍把已判推迟的 WorkManager 周期同步
   挂在 `- [ ]` 上 ⇒ 已改为「已判推迟（不是待办）」并写明触发条件。
4. **分支 ≠ buildType**：release 由主线合并、源码一致属实，但 `BuildConfig.DEBUG` 由构建类型
   决定；R8 裁得掉 `if (BuildConfig.DEBUG)` 块，裁不掉裸 `Log.d/w` ⇒ 日志门面 **P0 → P1**，
   生产 `!!` 排到它前面。**这条纠正了上一轮把 release 风险说过头的地方。**
5. **裸 catch 18 处撤下整改清单**：10 处函数级已 `@Suppress` 登记理由，8 处有明确降级行为
   且 detekt `swallow_catch = 0`；先前「catch 上方 6 行」的检索口径是错的
   （注解通常挂在函数上，离 catch 十几到三十行）。

## 🕐 最新状态（2026-11-11 凌晨 · 项目整体排查：只出报告，未改生产代码）

> **一句话**：用户要「依赖升级 / 安卓规范 / 优化项 + 稳定性与性能方案」整体排查。
> 结论：**依赖大半已经最新**（43 个坐标中 29 项 = 最新稳定版），真问题不是「版本旧」，
> 而是 `composeBom` / `material3` / `markdownRenderer` **三项绝对不能单独升**（#239 真机闪退链）；
> 最高价值动作不是升级，是**把「依赖解析结果」纳入 CI 门禁**。

| 交付 | 位置 |
|---|---|
| 主报告（结论 + 六块方案） | [`Docs/progress/audit/project-audit-2026-11-11.md`](../Docs/progress/audit/project-audit-2026-11-11.md) |
| 依赖矩阵 / POM 证据 / 扫描明细 / 施工单 | 主报告末尾「附录索引」三篇 `audit/evidence/` |

### 判据（新增，长期有效）

1. **依赖对齐是「解析结果」的属性，不是「声明」的属性**（#239 的推广）。本轮实证：
   `markdown-renderer 0.46.0` 带入的 `org.jetbrains.compose.foundation:foundation-android:1.12.1`
   **只写在 Android 变体 POM 里**，KMP 根 `.module` 与 `-m3.module` 都搜不到
   ⇒ 静态只看根 POM / Module Metadata 会**漏判**，必须看 runtime classpath。
2. **规范扫描必须做适用性裁剪**：539 个 `.kt` 扫出的 159 条「注释缺空格」几乎全是
   `file://` / `content://` 里的 `//` 误判；52 条「硬编码密钥」= 51 条 RFC 测试向量 + 1 条字段名常量。
   团队规范 80/800 行与项目 detekt 门禁 150/1200 行口径不同，且 detekt 只数代码行
   ⇒ **启发式数字只能定位，不能当违规数**，更不要为了对齐去批量拆方法。
3. **真违规只有两类**（2026-11-11 订正）：生产 **3 处** `!!`（P1，会 NPE）、
   release 下绕过 `VaultixLog` 的 **6 处** `Log.d/w`（**由 P0 降 P1**）；
   **18 处裸 catch 已撤下**（见上方新状态块第 5 条）。另有三项质量基建缺失
   （Baseline Profile / Kover 未接 CI / 配置缓存未启用）属**「要建」不是「要修」**。

> 本轮**未改任何生产代码**；顺手修 `.ai/ISSUES.md` 06 篇条数 `71 → 73`（与题注、正文一致）。
> 未跑真机 / `assemble` / `dependencyInsight`（沙箱无 SDK），所有性能改善幅度均为**待采集**。
> 施工优先级：先做「依赖解析门禁」再谈升级（见报告附录 C · S1）。

## 🕐 最新状态（2026-11-10 深夜 · #278 按站点分组 + #279 触摸光晕）

> **一句话**：两件事一起交付 —— ① 密码条目新增「按站点」分组（用户确认只要显示层归纳，
> 不增加开销、不破坏数据与 UI）；② 液态玻璃照 FlClash 精修：克隆其子模块 `prism_ui`
> 调研后，确认透镜带 / 色散 #277 已有对应物，**只补「跟随手指的触摸光晕」**。

| # | 用户要的 | 处理 |
|---|---|---|
| [#278](./issues/06-界面与交互-part18.md) | 同域名 / 同软件的条目归一组，更清晰 | `ItemsGroupMode.Site("site")`：主 URI 归一化（`androidapp://` 包名优先 → 网站取 `PublicSuffixList.baseDomain` eTLD+1），**≥2 条才成组**；单条站点与无 URI 归尾部「未分组」；组标题经 `AppLabel` 翻中文（调用方注入 lambda）。数据 / 同步 / 自动填充零改动 |
| [#279](./issues/06-界面与交互-part18.md) | FlClash 那种触摸时光照反射 | shader 新增 `uGlow` uniform：光心 = 手指精确位置（`DockRow` 行级 `pointerInput` 观察**不消费事件**），强度 `TOUCH_GLOW_PEAK × intensity²`，screen 叠加在 rim 之后；`GlassPress` 增 `focusY` |

### 判据

1. **「整理归纳」只做展示层**：不改数据、不改自动填充、不破坏现有 UI 结构 —— 分组是
   `groupItems` 的第 5 个枚举分支，列表结构 / 折叠语义全部复用。
2. **无站点键的条目必须仍出现在列表里**：`siteKeyOf` 返回 null 时收集进兜底组，
   直接 `return@forEach` 会把条目整组丢掉（静态门禁查不出「列表少了几条」）。
3. **光晕只进 uniform 与渐变中心**：位置可以跟手指，尺寸一个像素都不许动
   （`LiquidGlassDockRulesTest` 第 7 条规则钉住三处接线）。

> 实证：CI 同款 9 模块单测全绿（新增 `ItemsGroupingSiteTest` 7 条、glass 新增 2 条测试与
> 1 条规则门禁）+ CI 口径 `detekt` 全绿 + 15 个静态门禁 `PASS=15 FAIL=0`。

## 🕐 最新状态（2026-11-10 深夜 · 液态玻璃底栏第四轮：边界沿轮廓 / 补饱和度 / 按压联动）

> **一句话**：用户「点击效果和玻璃效果都没做完善，注意边界和大小」—— 三个症状三种根因，
> 第四点是**约束**：前两处的改法全程不许改变底栏的边界与大小。承接 #198/#200/#205 架构，不重开链路。

| # | 用户看到 | 根因 / 处理 |
|---|---|---|
| [#277](./issues/06-界面与交互-part17.md) | 边界断在圆角、像「上下有边、左右没边」 | 高光 / 描边用 `drawRect` 画，被 `.clip(Pill)` 裁掉两端半圆 ⇒ 改 `Shape.createOutline` + `drawOutline` **沿轮廓**画五层（填充 / 高光带 / 镜面 rim / 焦点高光 / 内描边），圆角连续；降级档只画填充 + 内描边 |
| [#277](./issues/06-界面与交互-part17.md) | 玻璃后方糊成一片灰雾 | 只 `blur` 会把彩色条目洗成中性灰 ⇒ 模糊后串 `setSaturation(1.38)` 的 ColorFilter 链（Haze vibrancy / LiquidGlassBar 1.5× 同款），**31-32 也拿到**；AGSL 源码一行不改（运行时编译、CI 覆盖不到） |
| [#277](./issues/06-界面与交互-part17.md) | 点击时玻璃毫无反应 | 按压只发生在被按 item 内部 ⇒ 折算成 `GlassPress(intensity, focusX)` 两个**纯绘制参数**：镜面增亮 / AGSL 折射变深 / 焦点光晕落在被按 tab 上沿 + 松手 `ContextClick` 触觉 |
| [#277](./issues/06-界面与交互-part17.md) | 「注意底栏的边界和大小」 | `GlassPress` 不碰任何 `size/height/width/padding`；两态共用 `DockRow`/`VaultixCorner.Pill`，玻璃层不写死高度（#205 硬边界）。收 `State<GlassPress>` 只在**绘制阶段**读 ⇒ spring 期间零重组 |

### 三条判据

1. **沿轮廓 vs 矩形**：`clip(Shape)` 会裁掉矩形装饰在圆角处的部分 —— 边界要连续，几何就必须
   用同一个 `Outline` 画，不能先画矩形再靠 clip 补。
2. **饱和度是「玻璃能不能透出内容」的关键，不是装饰**：`createChainEffect(outer, inner)` 的
   outer 作用于 inner 结果，饱和度链别写反；链创建失败要原样返回 blur。
3. **观感状态不许参与测量**：动画值在组合期读会每帧重组整条底栏 ⇒ 收 `State`、绘制阶段读；
   按压只调 alpha / shader uniform / 渐变中心，尺寸一个像素都不动。

> 实证：CI 同款 9 模块单测 **1751 例 / 0 失败**（上轮 1741 + 10）+ detekt + 15 个静态门禁全绿；
> 两次变异验证（`drawOutline`→`drawRect`、`SATURATION`→`1f`）均精确变红后恢复。
> 静态对照预览 `/workspace/vaultix-glass-dock-preview.html`（仓库外）。
> ⚠️ 8.4-part3「iOS 26 透镜折射 ❌ 不做」否的是**可拖动放大镜 + PixelCopy 逐帧回读**；
> 本项目既有 `OpticsShader` 用 `createRuntimeShaderEffect` 直接采样 RenderNode，做的是固定边缘折射，不冲突。

## 🕐 最新状态（2026-11-10 夜 · 卡包第二轮：颜色按银行走 + 名片重排）

> **一句话**：#275 只做到「形状像」，这轮补上**颜色有语义**、**名片有层次**。
> 原来按 `item.id.hashCode()` 从 5 组渐变里挑 —— 同银行两卡可以异色、异银行反而撞色。

| # | 用户看到 | 根因 / 处理 |
|---|---|---|
| [#276](./issues/06-界面与交互-part16.md) | 不同银行该有不同颜色 | 数据里**没有发卡行字段**（`VaultCard.brand` 存的是卡组织）⇒ 新增 `core/common/BankCardPalette` 四级判据：14 家关键词表显式占用 18 组色相 / 表外「XX银行」按名稳定 hash / 卡组织 / id 兜底；卡面副标题写「招商银行 · Visa」让颜色自解释 |
| [#276](./issues/06-界面与交互-part16.md) | 个人信息名片要好看一点 | `2.45f → 2.05f`，重排**机构抬头 → 大姓名 → 细分隔线 → 横排联系方式**，加渐变抬头竖条 + 同心圆水印；底色仍走 M3 容器色、跟随主题 |

### 三条判据

1. **颜色这类「标识」必须由业务实体决定，不能由条目 id 决定**。id 只配当最后一级兜底 ——
   它稳定，但不携带任何用户能理解的语义。
2. **关键词表按长度降序匹配**：`boc` / `bocom`、`cmb` / `cmbc` 是两组真实存在的银行缩写，
   短词先命中会把交通染成中行、民生染成招商。变异验证：删掉 `.sortedByDescending` ⇒
   恰好 `cmbc` 与 `bocom` 那 2 条测试变红。
3. **纯领域逻辑放到能单测的模块**：银行名关键词是数据、不是 UI 文案，放 `ui/` 会撞
   `check_ui_hardcoded_text.py`（白名单只减不增，不该为数据开口子）；颜色用 ARGB `Long`
   传递，UI 层才包成 `Color`。

> 实证：CI 同款 9 模块单测 **1741 例 / 0 失败**（上轮 1730 + 11）+ detekt（含
> `:core:common`）+ 15 个静态门禁全绿。
> ⚠️ 本地只跑 `testFullDebugUnitTest` 会少算其它模块（得到 1590），汇报前要按 CI 口径重跑。
> 另：`matchParentSize` 是 `BoxScope` 扩展，抽成独立 Composable 要带 receiver；本地
> `ANDROID_HOME` 需显式设 `/opt/android-sdk`（别写进 `local.properties`）。

## 🕐 最新状态（2026-11-10 深夜 · 一轮 4 点观感整改：按钮强调 / 中文应用名 / 图标徽标 / 卡包造型）

> **一句话**：用户凭观感点名的「主题模式关闭按钮不对」，根因是**强调级别用反**；
> 顺着「顺带查一下」查出 7 处同类 + 1 处槽位反了 + 1 处弹窗固定高度；
> 另三点分别是「名字本来就有，只是没人读」「状态不必写字」「两类语义别共用一张卡」。

| # | 用户看到 | 根因 / 处理 |
|---|---|---|
| [#275](./issues/06-界面与交互-part16.md) | 主题模式「关闭」按钮不对 | M3 动作区 `TextButton < OutlinedButton < Button`，收起动作却用了带框的中等强调 ⇒ 全仓 7 处改 `DialogTextButton`；`PasskeysScreen` 的「删除 / 关闭」槽位纠正；`LoginPickerDialog` 固定 `height(240.dp)` 改 `heightIn(max)` |
| [#275](./issues/06-界面与交互-part16.md) | 关联应用显示 `com.tdx.AndroidNew` | 库里本来就有 `appName=通达信`，只是没人读 ⇒ 新增 `AppLabel`：自定义字段 → `PackageManager` label → 包名 三级兜底；有包名字段时**必须与当前包名一致**才用名字（防多应用串名） |
| [#275](./issues/06-界面与交互-part16.md) | 「已绑定」占标题行 | 文字 chip ⇒ `26.dp` 圆形 `Link` / `Timer` 图标徽标；保留 `contentDescription`、不挂 `IconButton`（会抢 ⋮ 菜单触摸区） |
| [#275](./issues/06-界面与交互-part16.md) | 银行卡 / 个人信息都只是列表行 | ⇒ 银行卡 `aspectRatio(1.7f)` 深色实体卡（渐变按 id 稳定挑选、不跟随主题）、个人信息 `2.45f` 浅色名片；**语义不同不共用 `EntryCard`** |

### 三条判据

1. **控件的视觉权重必须匹配语义权重**：`confirmButton` 是 M3 该重载的**必需参数**（只给
   `dismissButton` 会解析到 `content` 重载、编译失败）；想降观感改 `TextButton`，别挪槽位。
   真正无动作区要 `BasicAlertDialog`，不是留一个空的 `dismissButton = { }`。
2. **语义不同就不共用容器 / 造型**（8.4 已有判据的反向应用）：卡与名片、应用与网址同理。
3. **省像素不能省语义**：图标替文字必留 `contentDescription`；纯状态徽标不挂点击语义。
   另：`remember` 按**调用位置**分组，循环里逐条 `remember` 会互相覆盖 —— 列表要一次性算好。

> 实证：`f94efeb1`；全量 **1730** 条单测 0 失败（+9：`AppLabelTest` 6 + `DialogStyleRulesTest` 3）
> + detekt + 15 个静态门禁 + `Android CI (Debug)` run `42819017944` 双 job 绿。
> 变异验证：把主题模式按钮临时改回 `DialogOutlinedActionButton` ⇒ 对应门禁恰好变红。
> 顺带校正了 06 主篇「分篇索引」表里 part15 的滞后值（原写 #271 / 1 条 / 5.1KB，
> 实为 #271~#273 / 2 条 / 10.4KB）—— 又是「同一事实存两份」。

## 🕐 最新状态（2026-11-10 深夜 · OneDrive 库「不能同步」+ 绿灯消失）

> **一句话**：两个症状是**两个互不相干的缺陷**，都只打网盘库 —— 本地库全程正常，
> 所以它们能一直藏着。

| # | 症状 | 真根因 |
|---|---|---|
| [#274](./issues/07-数据与同步-part3.md) | 点同步 →「这是本地文件，没有需要同步的云端副本」 | `KdbxFileSource.isRemote` 是接口**默认属性**（写死 `false`，保守设计）；`CachedKdbxFileSource` 逐个 `override fun`、**没有 `by delegate`** ⇒ 继承了默认值 ⇒ 每个套了缓存的来源都自称本地。而 `fileSourceFor` 对**所有**远端来源都套这一层 |
| [#274](./issues/07-数据与同步-part3.md) | ⋮ 菜单里没有绿灯、卡片没有「已同步」那行 | 同一道闸（`localOnlyGate`）顺手 `markStatus(markLocalOnly())` ⇒ `syncStatus = LOCAL_ONLY`，而 `LOCAL_ONLY` 在 `SyncStatusDot.of()` / `SyncBadge.of()` 里都是 `null` ⇒ **两者都不显示** |
| [#274](./issues/07-数据与同步-part3.md) | 修好代码后绿灯还是不亮（存量数据） | `addKdbxVault` 的 `@Upsert` 是 **UPDATE 全列**，构造实体时漏带 `syncStatus`/`remoteVersionToken`/`lastSyncedAt` ⇒ 用户**再打开一次同一个库**就把它们抹成 `null`；而补初始值那段判的是覆盖**前**的快照 ⇒ 这一行再也活不过来 |

### 修法

1. `CachedKdbxFileSource` 加 `override val isRemote: Boolean get() = delegate.isRemote`
   —— 不能写死 `true`（缓存包住本地文件时要说谎），也不能什么都不写（＝这次事故）。
2. `addKdbxVault` 把三列从 `existing` 带回来。
3. 启动时 `repairVaultsMarkedLocalOnly()`：origin 解析得出网盘来源、状态却是
   `LOCAL_ONLY`/`null` ⇒ 补成 **`PENDING_UPLOAD`**（不是 `IN_SYNC`）。

### 判据

1. **装饰器必须转发每一个语义属性。** 接口默认值是为"忘了写"兜底的，但它兜不住装饰器 ——
   装饰器的"忘了写"等于**主动断言反面**。加属性时先问：这个装饰器要不要转发？
2. **`@Upsert` 不是部分更新。** 它先 INSERT、冲突则 UPDATE **全列**。每加一列都要问
   一次"这行已存在时它会被写成什么"；写成 `null` 就是一次静默数据丢失。
3. **修完根因还要修存量。** 用户看到的绿灯消失不会因为代码改对而自己亮回来，
   而他没有任何理由再去点一次同步。
4. **补状态只能补"事实"，不能补"结论"。** `PENDING_UPLOAD`（有云端但没对过账）✓；
   `IN_SYNC`（对过账、一致）✗ —— 凭空写会让下次同步走"两边都没变"的捷径、永久盖住差异。

> 实证：`aba3a58a`；本地全量 **1721** 条单测 0 失败（新增 2 条）+ detekt + 15 个静态门禁全绿。
> 已做变异验证：临时删掉 `override val isRemote` ⇒ 恰好新增那 2 条红、其余 22 条绿。

## 🕐 最新状态（2026-11-10 深夜 · 两处「宽度算错」导致的竖排）

> **一句话**：两个症状（对话框「取消」竖着摞、权限页「通知」竖排）**同一个病因** ——
> 不是字号、不是字重，是**宽度算错了**。

| # | 症状 | 真根因 |
|---|---|---|
| [#273](./issues/06-界面与交互-part15.md) | 对话框的「取消 / 关闭 / 保存」两个字上下摞着 | M3 `AlertDialog` 给动作槽传了 `indentChar`("horizontal")，这个缩进同时参与 `Text` 换行**和** `width(IntrinsicSize.Max)` 的测得宽度；按钮是 `Row`、不实现 intrinsic ⇒ 测得 0 ⇒ 被 `widthIn(min=58dp)` 兜住 ⇒ 两个字各占 29dp 逐字换行 |
| [#273](./issues/06-界面与交互-part15.md) | 权限管理页「通知」竖排，别的不竖 | `TrailingSlot` 写死 112dp，而一行放不下时 Compose **先压缩 `weight(1f)` 的标题列**、不是撑宽卡片 ⇒ 2 字标题只剩 1 字宽（8 字标题的「指纹 / 设备密码」早已折成两行，看不出是被挤的） |

### 判据

1. **"为了对齐而牺牲可读性"永远是错的。** 112dp 行尾换来的只是"右边线齐"——
   一件灰度上看不出来的事，代价是一个 2 字标题被挤成竖排。行尾按内容取宽、
   靠 `Alignment.End` 对齐，齐的效果不变，标题凭空多出 70dp。
2. **软约束先让路，硬约束不让。** 行里放不下时，被压缩的永远是 `weight(1f)` 的那一列。
   所以"给右边固定宽度"等于"给左边固定损失"——两个字的标题最先倒霉。
3. **M3 的槽位会往下传排版属性，裸控件接不住。** `AlertDialog` 的四个槽位都带
   `indentChar`，裸 `Button` 吃下它就把自己撑歪。要么走封装、要么什么都不放。
4. **"引用不存在的资源"不会崩。** `stringResource` → `getString` 只返回
   `"ResString(id=0x…)"`，界面上就是一段怪字样，没人会报 crash ⇒ 只能静态查。

> 实证：`d8559668`；本地全量 **1719** 条单测 0 失败（新增 6 条静态门禁）+ detekt + 15 个静态门禁全绿。

## 🕐 最新状态（2026-11-10 夜 · 设置页按「同类放一块」重排 + 本地库上云引导页）

> **一句话**：设置页不是"分组太少"，是**四处分错**；本地库上云不是"功能没写"，
> 是**不该替用户搬**。

| # | 变化 | 真问题 |
|---|---|---|
| [#271](./issues/06-界面与交互-part15.md) | 自动填充移出「外观」进「密码库与安全」；组名改「外观与自定义」；「版本」→「版本与更新」；开发者两行并入「关于」卡 | 自动填充回答"库怎么用出去"，被放在回答"界面长什么样"的组里；检查更新藏在"版本"标题下；页尾两个 2 行组把扫读切碎 |
| [#272](./issues/05-KDBX本地库-part12.md) | 本地 KDBX 的 ⋮ 新增「放到网盘…」→ 三步引导页（**只讲方法，不代搬文件**） | 用户想多设备共用本地库，但"搬库"要迁 origin / 库 id / 加密信封 / 指纹与 PIN，失败丢的是解锁凭据；用户自己在网盘客户端上传只多输一次主密码，且不迁移任何东西 |

### 判据

1. **分组按"用户的问题"分，不按"控件类型"分。** 主题/纯黑回答"长什么样"；
   自动填充回答"怎么用出去"；权限管理回答"能拿我什么"。三者混在一组，组名就只能起个兼职的。
2. **"同一份偏好的第二个入口"不算零散。** 条目显示在条目页也有一处、回收站清理在回收站页也有一处 ——
   同一个 DataStore 键、同一个弹层，那是就地快捷方式。删掉它只会多两次跳转。
3. **有能力 ≠ 该替用户做。** 上传 `.kdbx` 的 API 早就有；不做一键迁移是因为**代价不在上传那一步**，
   而在库身份与解锁凭据的迁移。给方法比给按钮更安全。
4. **可见性按能力裁剪，不按版本裁剪。** 「放到网盘」只出现在本地 KDBX 的 ⋮ 里
   （`canMoveToCloud()`，5 条单测钉住），网盘库与 Bitwarden 永远看不到。

> 实证：`e0e73a96`，CI run `42776479259` 两个 job 全 success；本地全量 **1713** 条单测 0 失败 + detekt + 15 个静态门禁全绿。

## 🕐 最新状态（2026-11-10 深夜 · 本地 KDBX「点同步变已同步」= 云图标第三轮）

> **一句话**：三轮都在改"图标"，只有这一轮找到了真因 ——
> **`syncStatus` 那一列被拿来回答了"这个库在不在云上"。**
> 那一列能写歪，"库在哪"是事实，只有 `origin` 知道。

| # | 现象 | 真根因 |
|---|---|---|
| [#270](./issues/05-KDBX本地库-part11.md) | 本地 KDBX 新建条目 → 点同步 → 显示「已同步」，卡片和 OneDrive 库长得一样 | 本地 SAF 来源**就是那个文件**：能 stat、能 read ⇒ 编排器照常跑了三态判定 ⇒ 自己跟自己比必然一致 ⇒ 写 `IN_SYNC`。再往上，卡片按 `syncStatus != null` 选"网盘库"徽章 |

### 判据

1. **能写的数据不许回答"事实"类问题。** 「库在哪」问 `origin`；「对上没对上」问 `syncStatus`。
   混用一次，数据一写歪整个界面就跟着歪。
2. **数据层修 + 展示层再修，不是重复劳动。** 数据层保住明天，展示层保住今天
   （已经落库的坏值不会自己变回去）。
3. **没有"另一端"的对象，不该有关于"另一端"的任何一句话。** 本地文件的「已同步」
   不是措辞问题，是一句**没有真值**的断言。
4. **mock 不会跑接口的默认实现。** 给 `KdbxFileSource` 加 `isRemote`（默认 `false`）后，
   10 条既有用例全红在 `no answer found for ... isRemote()` —— 与 `readRemoteCurrent` 同一个坑。

> 实证：`52a1765a`，CI run `42770711485` 两个 job 全 success；
> 本地全量 1708 条单测 + detekt + 15 个静态门禁全绿。

## 🕐 2026-11-10 夜 · KDBX 改「跟随锁定」+ 回来自动弹指纹

> **一句话**：两处都是**把上一轮的结论推翻**，而且推翻的理由值得记住 ——
> 上一轮的两条结论都是**针对症状的过拟合**，不是错在判断，是错在止步。

| # | 变化 | 为什么推翻前一次 |
|---|---|---|
| [#268](./issues/04-锁与解锁-part9.md) | 切库**不再锁**，改「跟随锁定」：份数上限 3，锁任一 KDBX 则整组失效 | 60 秒宽限期把约束押在**一个猜出来的时间常数**上：取小没用（用户看五分钟）、取大等于不锁 |
| [#269](./issues/04-锁与解锁-part9.md) | 熄屏/清后台回来**自动弹指纹**（对齐 Bitwarden） | #245 的"不抢着弹"是在绕 `prewarmCipher` 缺失这个**真 bug**；它修好后，抑制只剩"多一次点击" |

### 判据

1. **一条策略如果需要"魔法数字"才能成立，先问这个数字在替谁做决定** ——
   答案是"替用户的意图"时，这条策略迟早要重写。（60 秒 → 跟随锁定）
2. **为绕开一个 bug 而加的抑制逻辑，必须写下"它绕的是什么"** ——
   否则 bug 修好后，它会以"这是经过真机验证的决定"的名义永远留下。（#245 → #269）
3. **`Kdbx.lock()` 丢掉的只是对象图，不是密钥** —— 派生密钥本来就常驻（#247），
   所以"多留一份"的额外暴露面远小于直觉，而省掉的是网盘库**整库下载 + 解析**。
   这条是「跟随锁定」敢做的全部依据。

## 🕐 2026-11-10 傍晚 · 云图标「删过头」+ 生效范围文案

> **一句话**：两条反馈的共同形状是**一个东西被要求回答两个问题**。
> 图标要同时说「库在哪」和「这条推了吗」，数字要同时说「配置结果」和「暂时缺口」。
> 拆成两个语义就都好了 —— 这比"再调一次判据"可靠。

| # | 现象 | 真根因 |
|---|---|---|
| [#266](./issues/06-界面与交互-part14.md) | OneDrive 上的 KDBX **没有**云图标，和本地库长得一样 | 上一轮 #263 修成了「KDBX 一律不画」= **过度修正**。判据从「这个库有没有云」退化成了「这个库是不是 KDBX」 |
| [#267](./issues/06-界面与交互-part14.md) | 「已对 3 个库生效」看起来像还有库漏了 | N<总数 不代表被排除，只表示"那几个库还没被主密码解锁过一次"（会自动收敛）。而旧文案把暂时状态说成了配置结果 |

### 判据

1. **一个图标只能回答一个问题**（语义层判据，#282 仍成立）—— 地点（库在云上吗）由
   `VaultSummary.origin` 决定，状态（这条推了吗）由 Bitwarden `pending_ops` 决定。
   两次修复各朝一个方向错（多画 / 少画）时，别找"哪个分支写错了"，
   先问「这个位置该由几个问题共同决定」。
   ⚠️ **形状层已于 #282 反转**：用户反馈两朵不同的云并排出现反而像「没同步」，
   故「已上云」的两种答案统一用**带勾云**（`CloudDone`），原「地点=裸云 / 状态=带勾云」
   作废。但**语义不合并**：本地 KDBX 仍不画云、KDBX 仍不产出条目级同步态，
   `CLOUD_VAULT` 与 `SYNCED` 仍是两个枚举值（`CloudSyncBadgeTest` 钉住）。
   详见 `.ai/issues/06-界面与交互-part20.md`。
2. **给用户看的数字必须可判断** —— 孤立分子（3）不可判断；`3/5` 可判断；「所有」也可判断。

## 🕐 2026-11-10 下午 · #265 补齐「进后台立即结束宽限期」

> **一句话**：上一轮给切库加了 60 秒宽限期，但**只在前台生效**这件事当时没落地 ——
> 选项原文写的是「超过 60 秒、**或 App 进后台/熄屏**，立即锁」。本轮补上。

- `KdbxGraceLocks.flushNow()`：把排期中的库**当场锁掉**（顺序执行、逐个兜错）。
- `AutoLockController.onStop` 接线：`flushPendingKdbxLocks()` 在 `onAppBackgrounded()` **之前**调，
  两处才不对同一个库各锁一次。
- **判据不变，只是把"宽限期属于前台"这句话补进了时序**：
  「同时 1 份明文」→ 「前台最多 2 份、后台立刻回到 1 份、60 秒后必然回到 1 份」。
- ⚠️ 这一轮又撞了一次 **detekt `TooManyFunctions` 40 上限**：新增一个接口方法就必须从
  `VaultRepositoryImpl` 挪走一个纯函数（这次搬的是 `classifyLoginError`）。
  **下次要加方法，先找纯函数，别调阈值。**

## 🕐 2026-11-10 上午 · 真机反馈四连：文件名 / 云图标 / 来源 / 切库

> **一句话**：四条里有**三条是「同一个事实存了两份口径」**，一条是「安全约束的时机错了」。
> 共同点：编译、单测、资源检查**全绿**，只有真机在特定组合下才看得见。

| # | 现象 | 真根因 |
|---|---|---|
| [#262](./issues/06-界面与交互-part12.md) | 新建本地库，文件永远叫 `vaultix.kdbx` | `createTargetPicker.launch(DEFAULT_KDBX_FILE_NAME)` 传的是**常量**；用户填的库名只进了根组名，从未参与文件名 |
| [#263](./issues/06-界面与交互-part13.md) | 本地 KDBX 的条目行尾挂着「已同步」云 | 三条**各自正确**的代码串成假话：`observeSyncStates` 返 `emptyMap()`（对）+ 读侧 `?: true`（对 Bitwarden）+ 行尾**无条件**绘制 |
| [#264](./issues/06-界面与交互-part13.md) | OneDrive 上的库显示成「本地文件」 | 来源判据存在**两份**：`VaultOriginLabel` 一份（对，密码页在用），`VaultListScreen.vaultSubtitle()` 自己又写一份，KDBX 分支**无条件**「本地文件」 |
| [#265](./issues/04-锁与解锁-part7.md) | 两个 KDBX 库互相锁定 + OneDrive 库指纹解锁很慢 | **互锁是 `#60` 的既有设计**（整库明文不能多份），不是 bug；但「立刻锁」让每次切回重付 `stat` 往返 + Argon2 + 解析 ⇒ 改 **60 秒宽限期**（`KdbxGraceLocks`） |

### 四条判据（都能直接判下一处同类）

1. **文件名是用户在文件管理器里看到的第二个身份** —— 凡由用户命名的东西，落盘名必须跟着它走；
   还要按 Windows/OneDrive 最严规则清洗（非法字符、末尾点、**按码点**截断，别把 emoji 切半）。
2. **「没有这回事」必须有一个独立的值，不能借用「一切正常」** ——
   `true` = 有云且最新，`null` = 没有云这个概念。合并成一个布尔，本地库就必然被画上云。
   `ItemRow.synced` 已从 `Boolean` 收窄为 `Boolean?`，让"这个库没有云"**类型上无法忽略**。
3. **凡是「某个值该显示成什么」的映射，全项目只能有一份实现** ——
   出现第二份时两份**都不会报错**，只是一个页面说 A、另一个说 B。
   已加门禁 `check_kdbx_origin_single_source.py`（做过反向验证：注入旧写法会红）。
4. **安全约束可以从「数量维度」换到「时间维度」，但不能悄悄消失** ——
   「同时 1 份明文」→「最多 2 份，且第 2 份 60 秒后必然消失」，上限仍是常数、终点仍确定。
   ⚠️ 别改回「立刻锁」（正是 #265 修掉的），也别改成「不锁」（约束消失）。

## 🕐 2026-11-10 清晨 · 用户报的四个真机 bug

> **一句话**：四条里三条是**判据判错了对象**，只有一条是"功能没写"。
> 这类 bug 的共同点是**不崩、不报错**（或报错但报的是另一个错），只能靠真机撞出来。

| # | 现象 | 真根因 |
|---|---|---|
| [#260](./issues/05-KDBX本地库-part10.md) | 新建本地 KDBX 库 100% 报「已存在同名文件」 | `CreateDocument` **先建了 0 字节占位**，`failIfExists` 却把"存在"当冲突 ⇒ 自己刚建的占位把自己挡住了 |
| [#260](./issues/05-KDBX本地库-part10.md) | 打开刚建的文件报「不是 KDBX 数据库」 | 0 字节被归到 `NotKdbx`：一句**没有下一步**的结论。新增 `KdbxFormat.Empty` / `KdbxOpenError.EmptyFile` |
| [#261](./issues/06-界面与交互-part12.md) | 新建**银行卡**按返回必弹「放弃修改？」，新建登录不弹 | `buildSnapshot` 对 Card/Identity/SshKey **无条件造对象**（`null → 空对象` ⇒ 判脏恒真）；判脏基线还错误地取了 `initial` |
| [#261](./issues/06-界面与交互-part12.md) | 新建面板两行副标题多余、删后卡片悬空 | 高度是按"两行内容"定的常量；删文案必须同步重算（76/84 → 72/72） |

### 三条判据（都能直接判下一处同类）

1. **`CreateDocument` 给的是"已经创建好的空文件"，不是"可以创建的位置"** ——
   接在它后面的 `failIfExists` 判据必须是"**有没有字节**"。
   判据抽成纯函数 `blocksCreate()`（`SafCreatePolicy.kt`），四格边界有测试。
2. **判脏基线 = 「打开表单时的首帧快照」，不是入参** ——
   `remember(initial) { snapshot() }` 一行同时免疫"空段补全"与"自动推导指纹"两类误报。
   且**新建直通、编辑才拦**（`initial.id.isNotBlank()`）。
3. **删一行文案，卡片的常量高度必须跟着重算** ——
   高度常量的注释里写清"由哪几项相加"，否则删文案永远是只有真机才看得出的半成品。

> ⚠️ 提醒下一位：`SafKdbxFileSource` 的 `generation` 序号、`SafCreatePolicy` 的
> "空即放行"，两者都是**为具体 bug 立的判据**，改之前先读 `05-part10` 的根因链。

## 🕐 2026-11-10 白天 · UI 精装修 + v0.12.0 已发布

> **一句话**：从「图标」一路做到「启动帧」，七块改动共用同一条方法 ——
> **一个系统、多尺度复用**。反着做（每处各造一个新装饰）会同时得到「单调」和「廉价」。

### 七块改动（全部已出包）

| 块 | 关键决定 |
|---|---|
| App 图标 | 徽章盾 + 金 V → **折纸 V**（受光面 + 同系淡蓝背光面 + 内角墨阱）；配色对齐 `#3B5BC4`；单色层改实心剪影、删 `evenOdd` |
| 底栏点击 | `tween(90)` → **spring** 吸入/回弹；选中 pill 圆角连续形变（颜色仍**瞬切**）；图标 one-shot 弹跳；加号只压缩回弹（**否决旋转**：终态不变、中途变叉号） |
| 液态玻璃 | 填充 0.38/0.52、模糊 16dp、**默认开**；`FALLBACK_ALPHA` 仍 `1f`（API ≤30 实色，8.4 part3 的可用性定案没破） |
| 「+」面板 | 主卡（按 `addTarget` 默认分发）+ 2 列字段剪影网格 + 生成器通栏卡；**所有 Tab 恒定 4 格** |
| 卡包 | `filter { it.type == Card }` 放宽到 `Card \|\| Identity` —— Bitwarden `TYPE_IDENTITY` 与 KDBX `toVaultIdentity` 双端生效 |
| 库列表 | 库身份卡（徽章 + 色调底 + 右侧出血水印）+ 概览行 + Extended FAB；切库 **E 数据流**（240ms 前置） |
| 启动帧 | 白底 + 转圈 → **单色 V 剪影 + 180ms 淡入** |

### 判据：装饰与廉价的分界不是"多少"，是"是否同源"

- 库列表水印（84dp / alpha 0.07）与徽章（48dp / 实色）**同一个 `VaultIdentity`**；
- 面板主卡水印（scale 1.45 / alpha 0.13）与网格卡剪影**同一个 `TypeGlyph`**；
- 启动标记的 V 与桌面图标**同一份 pathData**（`tools/icon/` 的 check 钉住）。

⚠️ 加装饰前先问：**它复用的是已有的哪个形状 / 色阶？** 答不出来 = 要新增一套语言，
那就先停手（8.4 的「一屏两套规格」就是从这一步开始的）。

### 六条坑（新窗口别再踩）

1. ★ **`Box` 按最大子元素测量** ⇒ 水印画布**不能超过卡片自然高度**，否则把卡撑高。
   库列表 104→84dp（卡 ~79dp）、面板 72dp（卡 ≥76dp）都是这么定的。
2. ★ **M3 `*Container` 与 `surfaceContainerLow` 只差约 8% 明度**（88% vs 96%）
   ⇒ 10% 混合后差异不足 1%，**肉眼分不出**；要看得出色相**至少 20%**（现取 0.22）。
3. ★ **`Theme.Vaultix` 继承 `android:Theme.Material.Light` = 恒浅色**，
   深色模式窗口底色也是白的 ⇒ 冷启动闪白。修法：`windowBackground` 在
   `values` / `values-night` 分开取值，**不能用品牌蓝**（NavHost 是 fadeIn，会透出来）。
4. ★ **从未引用过资源的文件里新增 `R.xxx`，`import io.vaultix.vaultix.R` 不会自动存在**
   （`VaultixApp.kt` 栽过）。`check_compile_smells` 只核对 `R.string` 名字、不核对可达性。
5. ★ **push 断连后本地 `origin/main` 引用不刷新** ⇒ 会误判"已推送"。
   push 后必须 `git fetch` + 比 `git log --oneline -1 origin/main`。
6. ★ **顶层 `Float` 装饰常量必须 `const val`**（`MayBeConstant`）；**`Dp` 的不能**
   —— `96.dp` 是 `Int.dp` 属性访问，不是编译期常量。

### 发布

`VERSION` **0.11.3 → 0.12.0**（versionCode 11003 → 12000）。
`push rele` → `release.yml`：读 VERSION → 打 `v0.12.0` → 混淆构建 → 发 Latest。
⚠️ **发版前必须 bump VERSION**，否则 `git tag -f` 会覆盖已发布的 tag
（本地 `rele` 通常落后 main 几十个提交，用 `git push origin main:rele` 快进）。

### 仍待真机确认

① 玻璃在深色 / OLED 纯黑下「淡」是否过头；② 库卡色调底 0.22 会不会过浓；
③ 启动 V 的显眼程度；④ 面板主卡水印与网格卡是否读作同一系统；
⑤ 2.0x 字号下主卡 + 4 格 + 工具条是否一屏放得下。

### 上一轮（2026-11-09 · 应用与网址分区 + 名称区去「待填字段」外观）

> 完整块见 [`SESSION-2026-11-09.md`](./SESSION-2026-11-09.md)。

> **一句话**：两条反馈看着无关，根因同一类 —— **同一个容器 / 控件承担了两种语义**。
> 网址卡混着 `androidapp://` 应用绑定（拆两区）；无框 `TextField` 的下划线在 M3 里是
> 「未完成表单」的标准像，与 `headlineSmall` 的「这是标题」互相拆台（改 `BasicTextField`）。
> ⚠️ 判据：「用户复制完这个值能粘到哪？」答不出来就不该有复制按钮。

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

1. 读 `MEMORY.md` —— 拿到项目定位、架构、硬约束与**当前状态（§9）**
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
