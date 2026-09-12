# 下一步任务清单

> ## 【最新】第四十三轮（2026-09-12）：Edge 账号框 / 填充图标 / 指纹解锁 / 填充辅助开关 + 阶段 3 动效
>
> 用户一次报四件事并要求「搬运 bastion 的外观和动效的改动连通这些修复一起做，一个提交推送」。
> 全部落地于同一提交。逐条见下（**待真机验收**）。
>
> **① Edge 浏览器「账号框不出候选也填不进去」（P0，根因有真机实证）**
> - 直接证据来自上一轮留存的真机日志 `build/adb-capture/device-log.txt`：
>   ```
>   fillRequest pkg=com.microsoft.emmx webDomain=github.com fields=221
>     hints={UNKNOWN=215, PASSWORD=5, USERNAME=2} user=true pass=true
>   ```
>   —— 一个页面解析出 **221 个「字段」**，其中 5 个被判 PASSWORD（GitHub 登录页只有 1 个密码框）。
> - 根因：浏览器 WebView 会把**整棵 DOM** 建成带 `autofillId` 的节点，`<label>Password</label>`、
>   「Username or email address」这类**展示节点**被文本启发式判成 PASSWORD / USERNAME →
>   占掉真账号框的语义位 → `promoteUsernameField` 的「没有可见 USERNAME 才升格」被假 USERNAME 挡住
>   → 真账号框永远 UNKNOWN → `hasUsernameField=false` → Dataset 里只有密码值
>   ⇒ **点密码框能弹（只填密码），点账号框什么都不弹也填不进**（与 QQ 修复前同型）。
> - 修复（两处，均对齐上游 Bitwarden `ViewNodeExtensions.toAutofillView`）：
>   1. **节点准入闸** `isEditableNode()`（新文件 `EditableNodePolicy.kt`）：有 `htmlInfo` 时
>      只认 `tag == "input"`；无 htmlInfo 时认 className 的 EditText 家族；两者都判不出才放行；
>      带标准 autofillHints 一律放行。非输入控件**直接丢弃**（上游同款闸门）。
>   2. **语义信号不再包含 `node.text`**（新函数 `BrowserUrlBars.formSignalOf`）：上游启发式从不读
>      `node.text`，只看 `idEntry` / `hint` / `htmlInfo` —— `text` 是内容/标签文字，正是误判之源。
>      `text` 仍作为字段**值**解析（保存流程用），两件事分开。
>      HTML 属性键同时补齐上游的 `autocomplete` / `label` / `hint` / `autofill`
>      （站点用 `<input autocomplete="username">` 声明语义时直接判成账号框，不再依赖邻近升格）。
> - **新增诊断**：`fillRequest` 日志追加 `seq=USERNAME,UNKNOWN(hid),PASSWORD,…`（字段序列 + 可见性）。
>   账号框填不进去时，「真账号框是 UNKNOWN」与「被假 USERNAME 占位」只看计数分不清，看序列一眼可定。
> - 单测：`EditableNodePolicyTest`（7 例，锁「web 展示节点不得进字段表」）。
>
> **② 填充下拉的图标（用户：太丑、别人家都是小而精致的）**
> - 上一版是 **40dp 彩色启动图标**（`R.mipmap.ic_launcher`）；现对齐 Bitwarden
>   `autofill_remote_view.xml` 的真实规格：**20dp 单色语义图标** + 12/6dp 内边距 + 48dp 最小高度。
> - 新增 4 个矢量：`ic_autofill_login`（地球）/ `ic_autofill_card`（卡片）/
>   `ic_autofill_identity`（人像）/ `ic_autofill_vaultix`（盾牌+钥匙孔，整表认证行专用，不上色）。
> - 上色方式对齐上游 `Context.iconTint`：RemoteViews `setColorFilter`，亮色 `#44474E` / 暗色 `#C4C6CF`。
> - 类别经 `AutofillIntents.EXTRA_CATEGORY` 一路带到认证回灌，保证二次验证后图标不跳变。
>
> **③ 指纹解锁「覆盖安装 + 重启后不生效」（三个独立缺陷叠加）**
> - **A. 解锁页会卡死在 `submitting`**（最能解释「按钮在、点了没反应」）：
>   `onError` 只处理「用户取消」，而 `ERROR_TIMEOUT` / `ERROR_CANCELED` / `ERROR_HW_UNAVAILABLE` /
>   `ERROR_LOCKOUT` 都不在白名单里 → `submitting` 永远为真 → 指纹按钮与主密码按钮双双置灰、
>   转圈不散、后续点击被守卫直接 return ⇒ **整页死锁只能杀进程**。
>   修复：任何错误都复位；仅「用户/系统取消」保持安静，其余显示原因；
>   `ERROR_CANCELED` 归入「取消」。
> - **B. 自动弹窗时机**：新增的「进解锁页自动弹一次」若在 Activity 尚未 RESUMED 时发起，
>   重启后（生物识别 HAL 未就绪）很容易被系统以非取消错误结束 → 正好踩中 A。
>   修复：改为 `lifecycle.withResumed { … }` 之后再发起。
> - **C. 一次瞬时失败会「自毁」注册**：`isLocalUnlockUnrecoverable` 把
>   `UserNotAuthenticatedException`（语义 = **本次未认证**）当成「密钥已废」，
>   触发 `clearBrokenLocalUnlock` **真删用户的快速解锁注册**；
>   且 `LocalUnlockKeyStore.obtainKey()` 在别名缺失时会**静默新建 KEK**，
>   把「钥匙丢了」掩盖成「认证成功 + 解密失败」→ 照样走到自毁分支。
>   修复：①`UserNotAuthenticatedException` 移出「不可恢复」（对齐 `Docs/03` 的
>   「应拉起 BiometricPrompt 重认证」）；②`LocalUnlockKeyStore` 拆
>   `loadKey()`（只读，绝不新建）与 `obtainOrCreateKey()`（仅启用路径可新建，
>   且先删失效别名再建）；③新增三态 `kekStatus`（LOADABLE / MISSING / INVALIDATED）——
>   `containsAlias` **不能**当健康检查（平台在密钥永久失效时让它静默返回 false）。
> - **D. CI 层「假覆盖安装」**：`ci-debug.yml` 原先只在 **push** 时注入固定密钥 ⇒
>   手动 `workflow_dispatch` 出的包是 runner 现生成的一次性 debug key 签的，
>   **盖不上 preview 包**（只能卸载重装 ⇒ 数据 + Keystore 一起没，表现恰是「指纹解锁被清除」）。
>   已改为 `event_name != 'pull_request'` 即解码，构建步骤按磁盘上是否存在 `release.jks` 决定是否签名。
>
> **④ 填充辅助（Fill Assist）独立开关（用户：上游有独立按钮，我这个 APP 上没有）**
> - 新增偏好 `fill_assist_enabled`（默认开）+ `VaultixPreferences.fillAssistEnabled` /
>   `setFillAssistEnabled`；设置 → 自动填充 → 填充行为新增一行开关（图标 `AutoFixHigh`）。
> - 文案直接取上游官方中文 `values-zh-rCN`：**启用填充辅助** /
>   「填充辅助通过使用站点特定的规则，提高在受支持站点上的自动填充的准确性」。
> - 关闭后**完全不读规则表**（识别退回纯启发式，行为等于搬运 Fill Assist 之前），
>   且不再做 6 小时节流的规则刷新（省掉无意义联网与磁盘写）。
> - 说明：上游是 **feature flag + 设置项**双重门控（且 bitwarden.com 上 flag 为 false），
>   Vaultix 只用设置项 —— 自建 Vaultwarden 通常不返回该 flag，照搬会让功能永远关着。
>
> **⑤ 阶段 3 观感（Bastion 外观与动效）**
> - **Tab 切换转场**：新增 `ui/shell/TabTransitions.kt`（移植 Bastion `NavTransitions` 的 tab 部分：
>   进入 = fadeIn + 从 1/16 屏高上移，220ms；退出 = 纯 fadeOut，120ms；缓动
>   `CubicBezierEasing(0.6, 0, 0.4, 1)`），并在 `MainShellScreen` 用
>   `AnimatedContent + SizeTransform(clip = false)` 接线（对齐 Bastion
>   `AuthenticatorPasskeyAnimatedContent` 的 `contentKey` 用法）。
> - **Tab 状态保留**：`rememberSaveableStateHolder()` + `SaveableStateProvider(tab.name)` ——
>   切走再切回不再回到列表顶部、搜索词不丢（对齐 Bastion 的 `cardWalletSaveableStateHolder`）。
> - **验证码倒计时平滑进度**：移植 Bastion `rememberTotpSmoothProgress`
>   （秒级数据源 + 绘制层 1s 线性动画，周期翻转时 `snap()` 防倒卷）。
> - 未做（阶段 3 剩余）：主界面卡片样式细化、FAB 行为按需取用、设置页分组结构复核。
>
> **门禁**：`:app:compileFullDebugKotlin` + `:app:testFullDebugUnitTest` +
> `:data:repository:testDebugUnitTest` + `detekt` **全绿**（本地真跑，非推测）。
> **待真机验收**：①Edge 账号框能弹能填；②搜索框仍不乱弹（回归重点）；
> ③填充下拉图标小而克制；④覆盖安装 + 重启后指纹解锁可用；
> ⑤设置页出现「启用填充辅助」开关；⑥Tab 切换有淡入上移过渡且切回不丢滚动位置。
>

> ## 【已归档】第三十九轮（2026-09-12）：通行密钥 `clientDataJSON` 占位符错误回退（标准合规）
>
> 用户实测 **GitHub 注册通行密钥**报 `Security key authentication failed`，并要求
> 「**按照标准改对，不要失误，不要自己乱加。应该是有标准的才对。**」
> —— 本轮**逐字拉 W3C 规范原文核对**后，确认第三十轮引入的「占位符」方案本身是错的，予以回退。
>
> **① 根因：浏览器流程回传空 `clientDataJSON`，违反规范 §5.8.1.1 / §7.1 / §7.2**
>
> 第三十轮（`90d5e6d`）依据 Android 官方文档那句
> `To avoid JSON parsing issues, set a placeholder value for clientDataJSON`
> 把浏览器流程的 `clientDataJSON` 改成 `ByteArray(0)`。**该句有前置条件**：
> > **If you retrieve an origin**, use the `clientDataHash` ...
>
> 指经 `CallingAppInfo.getOrigin(privilegedAllowlist)` + **特权应用名单**拿到 origin 的场景
> （Google Password Manager 走那条路，challenge 校验由系统侧完成）。
> Vaultix 的 `CallingAppOrigin` 明确走「**自证式读取**」、**不用特权名单** ⇒ 不适用占位符。
>
> 而规范层面 RP **一定**解析 `clientDataJSON` 明文逐项校验：
> > **§7.1** Let JSONtext be the result of running UTF-8 decode on `response.clientDataJSON`.
> > Let C be the result of running a JSON parser on JSONtext.
> > - Verify that `C.type` is `webauthn.create`.
> > - Verify that `C.challenge` equals **the base64url encoding of `options.challenge`**.
> > - Verify that `C.origin` matches the Relying Party's origin.
>
> **§5.8.1.1 `CollectedClientData`** 对字段的定义同样是明文：
> `challenge` = **the base64url encoding of options.challenge**、`origin` = the serialization of callerOrigin。
> ⇒ **空字节数组连 JSON 解析都过不了**，`C.challenge` 校验必然失败。
>
> **② 修法：回传自建真实 JSON，两条流程唯一差别是「签名覆盖哪份哈希」**
> | 文件 | 改动 |
> |---|---|
> | `core/common/WebAuthn.kt` | **删除** `BROWSER_FLOW_CLIENT_DATA_PLACEHOLDER`；`buildClientDataJson` 增 `androidPackageName` 参数；新增语义化封装 **`buildCreateClientDataJson`** / **`buildGetClientDataJson`**；重写 KDoc 引规范 §5.8.1.1/§7.1/§7.2 原文 + 官方文档条件句 + Bastion 依据 |
> | `passkey/PasskeyProviderIntents.kt` | `createIntent` 补 **`clientDataHash: ByteArray? = null`**（对齐 GET 侧的 `getIntent`）+ `putExtra(EXTRA_CLIENT_DATA_HASH)` |
> | `passkey/VaultixCredentialProviderService.kt` | `buildCreateResponse` 透传 `(request.callingRequest as? CreatePublicKeyCredentialRequest)?.clientDataHash` |
> | `passkey/PasskeyCreateActivity.kt` | 删占位符分支，始终 `buildCreateClientDataJson(..., androidPackageName = null)`；接收 `clientDataHash` 只做自检日志；**origin 推导顺序改为 `requestJson.origin` → `CallingAppOrigin` → `https://$rpId`**（对齐 Bastion `PasskeyOriginResolver`） |
> | `passkey/PasskeyGetActivity.kt` | **回退**占位符改动，改 `buildGetClientDataJson(..., androidPackageName = null)`；签名仍用 `clientDataHash ?: sha256(自建 JSON)` |
> | `core/common/.../WebAuthnTest.kt` | 改写 2 个已失效的占位符用例为 **`browser flow signs provided hash and returns real clientDataJSON`** / **`create response always carries real clientDataJSON`**，新增断言「非空 + challenge 为 base64url 形态 + type/origin 正确 + 浏览器流程不含 `androidPackageName`」 |
>
> **③ 关键区分（本轮核心结论，勿再混淆）**
> | 事项 | 浏览器流程 | 原生 App 流程 |
> |---|---|---|
> | **签名**覆盖 | `authData ‖ clientDataHash`（系统给的） | `authData ‖ sha256(自建 JSON)` |
> | **回传** `clientDataJSON` | **自建真实 JSON** | **自建真实 JSON**（同一份） |
> | `androidPackageName` | **不写**（浏览器那份没有该字段，写了会让 RP 重算哈希与浏览器不符 —— Bastion 实测 Microsoft 登录失败） | 可写 |
>
> **④ 验证（沙箱真跑）**
> - `:core:common:testDebugUnitTest` → **95 用例 0 失败**（含改写后的 3 条 clientDataJSON 回归锁）
> - `:app:compileFullDebugKotlin` → **BUILD SUCCESSFUL**
> - 改动文件超 120 字符行长 = 0（detekt `MaxLineLength`）
>
> **待真机验收**：GitHub 注册通行密钥（此前报 `Security key authentication failed`）+ 登录，
> 两条都应通过；`logcat` 抓 `PK create clientDataJson ... jsonMatchesBrowserHash=` ，
> 该值为 `false` 属**预期**（系统那份哈希来自浏览器），不再是故障信号。
>

> ## 【已归档】第三十八轮（2026-09-12）：通行密钥「登录最后一步校验失败」根因修复
>
> 用户反馈：「通行密钥能够读取到，能够进入登录，在**最终校验**的时候提示错误。」
> 定位到一条**一字节都不对**的硬伤，已修复并加回归锁。
>
> **① 根因：`rawId` 被二次 Base64 解码（`PasskeyGetActivity.kt:446`）**
>
> 注册侧（`PasskeyCreateActivity`）三处口径一致、正确：
> `credentialId = WebAuthn.base64Url(key.credentialId)` 落库；`authData` 里放**原始 32 字节**；
> `rawId = base64Url(原始 32 字节)`。
>
> 但登录侧把**已经是 b64url 文本**的 `cred.credentialId` **又解码了一次**，再编码回去：
> ```kotlin
> // 旧实现（已证伪）
> val idBytes = decodeBase64UrlOrStandard(cred.credentialId) ?: cred.credentialId.toByteArray(UTF_8)
> buildGetResponseJson(credentialId = idBytes, ...)     // 内部又 base64Url()
> ```
> 规范要求 `rawId` **逐字节等于** RP 在 `allowCredentials` 里持有的 ID，而该路径有两处失配：
> - **标准 Base64（含 `+` `/`）存储的 ID 被规范化成 b64url（`-` `_`）** → 文本不同。
>   实测：`2z85N/AMiSf…qf/i5FU=` 被回传成 `2z85N_AMiSf…qf_i5FU`，任何做字符串比对的 RP 直接拒。
> - 解码抛异常时被 `?:` 兜底成 `text.toByteArray(UTF_8)` → 把 Base64 文本本身当 ID 发出去。
>
> 这解释了全部三个现象：候选能列出（`idMatches` **两边都解码**，自洽 → 麻痹了测试）、
> 能进登录页（只用 rpId/元数据）、**最后一步校验报错**（签名/authData 全对，唯独 rawId 错）。
>
> **② `userHandle` 是同一陷阱的第二实例**
> 注册侧 `userHandle = json.optJSONObject("user")?.optString("id")` **原样落库**（未解码），
> 登录侧却又 `decode` → `base64Url` 编码回去。同样改为原样回传。
>
> **③ 修法：以「存储态文本」为准**
> | 文件 | 改动 |
> |---|---|
> | `core/common/WebAuthn.kt` | 新增 **`rawIdFromStored()`**（合法 Base64 则**原样返回**；脏数据退化为 UTF-8 重编码，不崩）+ **`buildGetResponseJsonFromStoredId()`**（`id`/`rawId`/`userHandle` 全走原样文本）；给旧 `buildGetResponseJson` 加「优先用新函数」的 KDoc 警示 |
> | `passkey/PasskeyGetActivity.kt` | 改用 `buildGetResponseJsonFromStoredId`；删掉 `idBytes` 与 `userHandle` 的两次多余 decode；补 `rawId=` / `storedIsBase64=` 现场诊断日志 |
> | `core/common/.../WebAuthnTest.kt` | 新增 2 例回归锁（`stored credential id is echoed verbatim as rawId`、`rawIdFromStored tolerates blank input`） |
>
> **④ 验证方式（沙箱内真跑，非推测）**
> 本轮把 `core/common/WebAuthn.kt` 连同验证器用 `kotlinc` 单独编译成 jar（绕过 Gradle/Android），
> **14/14 项检查通过**；并跑通真实 Gradle：
> - `:core:common:testDebugUnitTest` → **12/12 通过**（含新增 2 例）
> - `testFullDebugUnitTest` 全量 → **141 用例 0 失败**
> - `detekt` → 通过（修掉 1 处 `MaxLineLength`）
> - `:app:compileFullDebugKotlin` → **BUILD SUCCESSFUL**
>
> ⚠️ 唯一未能在沙箱完成的是 `assembleFullDebug` 的 **native 符号剥离**（缺 NDK，`stripFullDebugDebugSymbols`
> 报 MD5 读取失败）——属沙箱工具链缺口，与本次改动无关；该步在 CI 上正常。
>
> **⑤ 顺手修复沙箱构建环境（长期收益）**
> - `/root/.gradle/init.gradle` 原本**语法错误**（`mavelCentral()` 拼写错 + url 未加引号），
>   导致所有 Gradle 调用初始化即失败 → 重写为 `beforeSettings` 注入 Aliyun/腾讯镜像
>   （pluginManagement + dependencyResolutionManagement 两处都要，项目设了 `FAIL_ON_PROJECT_REPOS`）。
> - 安装 Android SDK：`platforms/android-37.0` + `build-tools/37.0.0`（**注意 37 的目录名带扩展版本号，
>   在线 manifest 里根本没有 `platforms;android-37`**，只能按 `platform-37.0_r01.zip` 直取）。
> - hosts 补 `dl.google.com → 113.108.239.161`（此前被污染到 fake-ip `198.18.0.13`）。
>
> **待真机验收**：用 Google/Edge 打开某站点 passkey 登录，确认最终校验不再报错。
>

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

## 第三十轮 2026-09-11 · 通行密钥「Authentication failed」根因

> 用户报：候选列表已能出现（第二十九轮的 discovery 修复生效），但站点仍报
> **Authentication failed** —— 这是断言（登录）阶段的**验签失败**。

### 根因：provider 与 RP 看到的 `clientDataJSON` 不是同一份

- 系统只把 **32 字节 `clientDataHash`**（浏览器那份 clientDataJSON 的 SHA-256）交给 provider，
  **不给明文**；
- 网页交给 RP 的是**浏览器自己拼的那份** JSON，RP 用它重新哈希后与签名里的哈希比对。

旧实现（`WebAuthn.buildClientDataJsonForBrowser`）试图「逐字节复刻浏览器的 clientDataJSON」
再回传，还按哈希在 `[无 crossOrigin, 有 crossOrigin]` 两个候选里**反选** —— 这条路不可能
稳定成功：字段集与字段顺序由浏览器版本决定（Chromium 可能带 `tokenBinding` 等），
provider 无从保证命中；一旦不命中就 `?: candidates.first()` 回退到自造变体 ⇒ RP 哈希必然
对不上 ⇒ 验签失败。

**官方逐字口径**（`developer.android.com/identity/sign-in/credential-provider`）：
> use the `clientDataHash` that's provided directly in `CreatePublicKeyCredentialRequest()`
> or `GetPublicKeyCredentialOption()` instead of assembling and hashing clientDataJSON during
> the signature request. **To avoid JSON parsing issues, set a placeholder value for
> `clientDataJSON` in the attestation and assertion response.**

### 修复（`90d5e6d`）

- [x] `WebAuthn.BROWSER_FLOW_CLIENT_DATA_PLACEHOLDER`（空字节数组）作为浏览器流程的
      clientDataJSON；**删除** `buildClientDataJsonForBrowser`（错路产物）；
      `clientDataJsonMatchesHash` 保留作诊断并注明浏览器流程下 `false` 是**预期**。
- [x] `PasskeyGetActivity.sign()`：有 `clientDataHash` → 签名覆盖 `authData ‖ clientDataHash`、
      回传占位符；无 → 自己拼 JSON、自己哈希、自己签、回传同一份 JSON。
- [x] `PasskeyCreateActivity.createPasskey()`：浏览器流程同样回传占位符。
- [x] 纠正三处已证伪的 KDoc（`buildClientDataJson` / `buildClientDataJsonForBrowser` /
      两个 Activity 的 `browserFlow`）。

### 三家对照（关键：两家是反例）

| 实现 | 浏览器流程如何处理 clientDataJSON |
|---|---|
| **Bitwarden ✅** | 从不在 Android 侧重建，交给 SDK（`ClientData.DefaultWithCustomHash(hash)` / `DefaultWithExtraData(androidPackageName)`）；`Fido2PublicKeyCredential.clientDataJson` 可空 |
| Keyguard ❌ | 重建（`PasskeyProviderGetRequest.kt:119-159`） |
| Bastion ❌ | 重建（`PasskeyAuthActivity.createClientDataJson`） |

> **Bastion 是本项目主要参考对象，但这一处不能跟。参考项目的"多数"不等于正确。**

### 测试（沙箱无 Android SDK，用 Gradle 自带 kotlin-compiler-embeddable 2.2.21 在真实源码上跑）

- [x] 真实 `WebAuthnTest`：**10/10 通过**，其中新增 3 条回归锁：
      `browser flow signs provided hash and returns placeholder clientDataJSON` /
      `native flow returns the very clientDataJSON it signed over` /
      `create response carries placeholder in browser flow and real json otherwise`。
- [x] 独立验证程序 20 项断言全绿，含**反证旧路**：浏览器多带一个字段（`tokenBinding`）⇒
      两个自造候选 `match=false`，证明旧逻辑只会回退到错的那份。
- [x] detekt 阈值自检：4 个改动文件超 120 字符行长 = 0；`sign()` 111 行 < 150；
      `WebAuthn` fun 数 29 < 60。

⏳ **真机待验证**：装新包 → Edge 触发通行密钥登录 → **应当登录成功**。
若仍失败，`logcat` 抓 `VaultixAutofill`：
`PK assertion ready sigLen=... authDataLen=... cdjLen=0 browserFlow=true`（`cdjLen=0` 即占位符生效）。

## 已完成（第二十八轮 2026-09-11 · `8afac3d`）

> ⚠️ 本轮的两个结论已被上方「结论更正」修正，此处保留作历史记录。

- [x] 断言/注册的 BE/BS 置位（`0x05→0x1D` / `0x45→0x5D`）——**保留**（语义正确性）。
- [x] 响应 JSON 补 `clientExtensionResults:{}` + `authenticatorAttachment:"platform"` ——**保留**。
- [x] 实测排除三个疑似项（200 组随机 P-256 密钥重建签名 100% 验签通过等）——**结论有效**。
- [x] ~~signCount 改读库~~ → **已回退为恒 0**（见「更正二」）。
- [x] ~~「旧凭证必须重新注册」~~ → **已撤回**（见「更正一」）。

**CI 与产物（第二十八轮已验证）**：run `34549342318`（commit `8afac3d`）**23/23 步全绿**
（checkout / JDK17 / Gradle / detekt / 编码门禁 / keystore 解码 / Build Debug APK /
单测 / 发布 preview Release；`lint` 按配置 skipped）。产物 `app-full-debug.apk`
**31,118,791 字节**，`sha256=f2d134e9ca7a09c9a935a76ab8e39562728346733019259cd4eeb3e405feac76`。

> **沙箱环境备注（长期）**：Release 资产现已走 `release-assets.githubusercontent.com`，
> 沙箱对该域名的 fake-IP 解析（`198.18.0.22`）会返回 404/401。已在 `/etc/hosts` 固定
> `185.199.108.133`（实测可用；`198.18.x.x` 是 fake-IP 不可用），
> `github.com → 140.82.112.3`、`api.github.com → 140.82.113.6`、
> `raw.githubusercontent.com → 140.82.113.3`。
> 另注意：`api.github.com` 对本环境拿到的 `ghu_` 型 OAuth token 一律返回 **401**
> （token 只能用于 git 传输层，不能调 REST API）。因此查 CI 状态改为读网页端
> `/Chaniug/Vaultix/actions/runs/<id>/job_groups_batch` 与 job 详情页的
> `<check-step data-name/data-conclusion>` 属性。

## 已完成（第二十七轮 2026-09-10 · 设置页对齐 Bastion，`be8a5bf`）

> 逐项比对 Bastion `AutofillSettingsV2Screen`(1149) / `PasskeySettingsScreen`(666) /
> `SettingsScreen`(1925) 后补齐。

- [x] **三态状态卡**：未启用 `errorContainer` / 需注意 `tertiaryContainer` /
      正常 `primaryContainer`。「需注意」= 密码能填但通行密钥没开。
- [x] **`AutofillStatusChecker`**：`hasEnabledAutofillServices()` 判「有没有」+
      读 `Settings.Secure:autofill_service` 判「是不是我」；读不到时按「已启用」。
- [x] **通行密钥分组**：「已解锁库中：N 个」+ 凭据提供商状态 + 特性说明。
- [x] **填充行为组（真开关）**：严格匹配 / 允许子域名匹配 → `MatchConfig` 真参数。
- [x] 首页「数据」→「数据管理」+「其他 · 权限管理」；CP 副标题文案修正。


## 已完成（第二十七轮 2026-09-10 · 设置页对齐 Bastion）

> 上一轮 `fec4032`（`isUsablePasskey` 放宽为元数据检查）后，用户要求「设置页面对齐 bastion」。
> 逐项比对 Bastion `AutofillSettingsV2Screen`(1149) / `PasskeySettingsScreen`(666) /
> `SettingsScreen`(1925) 后补齐，提交 `be8a5bf`。

- [x] **三态状态卡**（移植 Bastion `AutofillSettingsV2Screen` 状态卡）：
      未启用 `errorContainer` / 需注意 `tertiaryContainer` / 正常 `primaryContainer`。
      「需注意」= 密码能填但通行密钥没开（Chromium 最常见的半残状态）。
      顶栏手动刷新 + `ON_RESUME` 自动刷新（系统设置开启后返回立即可见）。
- [x] **`AutofillStatusChecker`**（新增）：两步判定——
      ① `AutofillManager.hasEnabledAutofillServices()` 判「系统有没有启用任何服务」
      （单独用不够：选的是谁分不出来）；② 读 `Settings.Secure:autofill_service`
      按 `VaultixAutofillService` 类名子串匹配判「是不是我」。
      **读不到值时按「已启用」处理**：① 已确认有服务，把「ROM 隐藏设置项」误报成
      「未启用」会误导用户反复去系统设置确认。
- [x] **通行密钥分组**（设置 → 自动填充 → 通行密钥）：凭据提供商状态行 +
      「已解锁库中：N 个」+ 特性说明；Android 14 以下整组退化为版本说明。
      数量是排查「Edge 看不到通行密钥」的关键读数：**显示 0 即定位到同步/解析问题**。
- [x] **填充行为组（真开关，非装饰）**：`严格匹配 → MatchConfig.exactDomainOnly`、
      `允许子域名匹配 → MatchConfig.allowBaseDomainMatch`，在
      `VaultixAutofillService.buildResponse` 生效。浏览器填不出来时关掉严格匹配
      是成本最低的排查第一步。新增 `VaultixPreferences` 两个键（默认 true / false，
      与 Bitwarden 一致）。
- [x] **设置首页**：「数据」→「数据管理」；新增「其他 · 权限管理」
      （`Settings.ACTION_APPLICATION_DETAILS_SETTINGS`）。
- [x] **`credential_provider.xml` 副标题修正**：此前误用标题文案
      `setting_credential_provider`，新增专用 `setting_credential_provider_subtitle`。
- [x] 清理 `SettingsScreen.kt` 遗留未用 import（`StatusBarManager`/`ComponentName`/
      `graphics.drawable.Icon`/`Build`/`ContextCompat`/`AutofillTileService`，
      磁贴逻辑早年已迁到二级页）。

### 明确不对齐 Bastion 的三项（有意为之）
1. CP 文案：Bastion 写「通行密钥**和密码**设置」，与其 `credential_provider_config.xml`
   「CP 不处理密码」的注释自相矛盾 → Vaultix 保持「凭据提供商（通行密钥）」。
2. 跳系统设置：Vaultix 用 `CredentialManager.createSettingsPendingIntent()`（直达自家
   provider 开关），不下沉为 Bastion 的 `ACTION_SETTINGS`。
3. 不搬「黑名单 / 屏蔽字段 / 智能标题 / 通知时长 / 密码建议 / 影子校验 / 校验诊断」
   ——Vaultix 无对应能力，搬过来是点不动的假开关。

⏳ **真机待验证（装 `dev-be8a5bf` 的包）**：
① 设置 → 自动填充 → 顶部状态卡颜色是否与实际启用状态一致（**这是本次的核心读数**）；
② 「通行密钥 → 已保存的通行密钥」显示的数量 —— **若为 0，则 Edge 看不到通行密钥
是因为库里根本没解析出 fido2，而不是 CP 通道问题**；
③ 通行密钥仍不弹时，把「严格匹配」关掉再试密码填充；
④ 权限管理能否跳到系统应用信息页。


## 已完成（第二十六轮 2026-09-10 · getOrigin 语义纠正 → CI 全绿）

> 第二十五轮推 `de6ad9e` 后 CI 连续两次失败：`34495082792` 挂 detekt（`RomCompat.kt`
> 常量返回 + 超长行，已由 `3e23740` 清理）；`34495532104` detekt 已过但 **Build Debug APK
> 编译失败**：`CallingAppOrigin.kt:51:44 No value passed for parameter 'privilegedAllowlist'`。
> 本轮修掉这一条，CI run `34496366032` **全绿**。

- [x] **反编译真源定语义**：下载 `maven.google.com` 的 `credentials-1.6.0.aar`（634 KB），
      `javap -c -p` 逐条读 `CallingAppInfo` / `PrivilegedApp` / `SignatureVerifier` /
      `RequestValidationUtil`，得到 `getOrigin(allowList)` 的**确定分支**（不再靠猜签名）：
      `!isValidJSON → IllegalArgumentException`；`origin==null → return null`；
      `包名命中 && intersect(调用方指纹, 名单指纹) 非空 → return origin`；其余 → `IllegalStateException`。
- [x] **纠正关键误判**：allowList **不是可选占位参数，而是签名背书名单**；`signatures` 为
      **必填**且元素是**对象**（取 `cert_fingerprint_sha256`）。→ 空名单 / `[]` / `["FP"]`
      三种取巧写法**全部必抛异常**，必须填调用方**自己的真实签名指纹**。
- [x] **改为「自证式读取」**：新增 `ALLOW_LIST_TEMPLATE`（合法 JSON 结构）+
      `signingFingerprintOrNull()`（`SHA-256(apkContentsSigners[0])`，多签名者→null，
      对齐 Bitwarden `getSignatureFingerprintAsHexString`）；`originOrNull()` 用调用方
      自己的指纹拼「只含它自己」的名单读 origin —— 只做**来源过滤**，不做特权应用身份背书。
- [x] **⑦ 预留正解路径**：`trustedOriginOrNull(callingAppInfo, allowList)` 保留给将来接入
      「用户信任的应用名单」（即 Bitwarden `OriginManagerImpl` 三级回退的用户名单一级）。
- [x] 头部根因注释按反编译事实重写（此前那段「传放行任意包名的名单」的描述是错的，已删除）。
- [x] **CI run `34496366032` 全绿**：detekt ✓ / 编码门禁 ✓ / 签名解码 ✓ / Build Debug APK ✓ /
      单测（非阻塞）✓；预览包 `app-full-debug.apk`（`dev-d082e63`，31.09 MB）已发布。

⏳ **真机待验证（装 `dev-d082e63` 的包）**：
① Edge 聚焦登录框 → 应弹密码条目（**核心闭环**；`logcat -s VaultixAutofill` 的
`caller=com.microsoft.emmx origin=https://...` 中 origin 应不再是 `-`）；
② 同一站点有 passkey 时应一并出现；③ 点候选完成填充 / 通行密钥断言；
④ 系统设置 → 密码和账号 → Vaultix → 齿轮 → 应落在自动填充/凭据设置页（非库列表）；
⑤ 多库且部分锁定时应同时看到候选与「解锁 Vaultix」。

## 已完成（第二十五轮 2026-09-10 · CP「已启用但不弹」根因修复 + Bitwarden 逐行对齐）

> 用户真机状态：**系统里凭据提供商已启用，但 Edge 里密码与通行密钥都不弹**。
> 不抓日志、直接按 Bitwarden 官方实现逐行比对 → 找到多条代码级缺陷，全部修复。

- [x] **★ 总根因之二：`androidx.credentials` 1.3.0 → 1.6.0**（对齐 Bitwarden 1.6.0）
      —— 1.5.0 才引入「凭据选择二级 UI 体验」（聚焦输入框时向 Credential Manager 下发请求
      + 下拉/键盘建议聚合），Chromium（Chrome/Edge）在 Android 14+ 呈现凭据条目**正依赖该机制**；
      停在 1.3.0 → 系统不在聚焦时派发请求 → 「已启用但毫无反应」。
      原先锁 1.3.0 的理由（「1.6.0 把 callingAppInfo.origin 收紧」）已由 1.6.0 的官方替代
      `isOriginPopulated()` + `getOrigin()` 消解。
- [x] **调用来源读取兼容层**：新增 `CallingAppOrigin`（`isOriginPopulated()` + `getOrigin()`
      安全包装，全 runCatching），替换 `PasskeyGetActivity` / `PasskeyCreateActivity` 里
      对 `callingAppInfo.origin` 的直读（1.6.0 起该属性为 internal）。
- [x] **entry 能力补全**：`PasswordCredentialEntry` / `PublicKeyCredentialEntry` 构造补
      `setAutoSelectAllowed`（仅单候选时允许自动选中）+ 按需 `setBiometricPromptData`；
      新增 `RomCompat`（HyperOS/MagicOS 判定）—— **魔改 ROM 上不挂**，否则系统可能
      渲染阶段丢弃整个 entry（仍是「什么都不弹」，比不挂更糟）。
- [x] **取消监听**：`onBeginGetCredentialRequest` / `onBeginCreateCredentialRequest` 补
      `cancellationSignal.setOnCancelListener`（对齐 Bitwarden `processGetCredentialRequest`）。
- [x] **密码条目按来源过滤**：复用 `BitwardenLikeAutofillMatcher`（与老 autofill 同规则），
      浏览器场景只用 origin 不用包名；来源不可得时不过滤（宁可多列不可漏列）。
- [x] **部分库锁定的解锁引导**：多库场景下若有库未解锁，凭据通道与认证动作通道**并存**
      （用户可就地解锁其余库，无需先清空候选）。
- [x] **⑥ provider settingsActivity**：新建 `CredentialProviderSettingsActivity`
      （承载 `AutofillSettingsScreen`），`credential_provider.xml` settingsActivity
      由 `MainActivity` 改指此处（系统凭据管理器的「管理」入口语义）。

⏳ **真机待验证（装含本轮修复的包）**：
① Edge 聚焦登录框 → 应弹密码条目（**核心闭环**）；② 同一站点有 passkey 时应一并出现；
③ 点候选完成填充 / 通行密钥断言；④ 系统设置 → 密码和账号 → Vaultix → 齿轮 → 应落在
自动填充/凭据设置页（非库列表）；⑤ 多库且部分锁定时应同时看到候选与「解锁 Vaultix」。

## 已完成（第二十四轮 2026-09-10 · Credential Provider 集成闭环 + autofill 打磨）

> 跨度 dba5ae1→f474654，均编译 + detekt + 单测全绿，已推 main 并出 CI preview 包。

- [x] **★ 总根因修复（f474654）**：manifest `<service>` intent-filter action 误写
      `android.credentials.CredentialProviderService`（漏 `service.` 段）→ 系统从未发现
      Vaultix 是 Provider → 无启用项 / `credential_service` 恒空 / Edge 永不弹 / passkey
      查不到。修为 `android.service.credentials.CredentialProviderService`。
      **教训：CP 注册逐字符对照官方模板，勿凭记忆**
- [x] **设置页「凭据提供商」状态行（e92d215）**：读 Secure `credential_service`，ON_RESUME
      刷新；跳转改用 `CredentialManager.createSettingsPendingIntent()` 直达启用界面
      （老 `REQUEST_SET_AUTOFILL_SERVICE` 与凭据提供商是两个独立设置项，部分设备无反应）
- [x] **CP 集成 ①–④（98edb37 + 8ba40d9）**：系统注册 + 通行密钥查询/创建 + inline 候选；
      ~~双能力声明（`TYPE_PUBLIC_KEY_CREDENTIAL` + `TYPE_PASSWORD_CREDENTIAL`）~~ 🔴 **已被
      `f815ab2` 反转：现只声明通行密钥，见文件顶部第三十五轮**；
      `buildGetResponse` / `PasswordGetActivity` 回灌密码凭据；锁定态按选项类型生成 Entry
- [x] **老路认证回灌时序（f474654 + 64ade7a）**：`MODE_COPY_TOTP` 认证宿主 `setResult`
      从 onCreate 推迟到 onResume（onCreate 同步会丢认证结果 → 「验证码复制成功但密码没填」）；
      解锁桥解锁后自动 finish 回原 App，不再停在 Vaultix 主界面
- [x] **认证宿主闪回修复（dba5ae1 + e52779e）**：宿主 Activity 独立 `taskAffinity`，
      NEW_TASK 不再复用后台 MainActivity 栈（修「Via 点密码条目闪回 Vaultix」，用户已确认
      不闪）；认证回灌去 NEW_TASK/singleTask，引导型意图由调用点显式加 NEW_TASK
- [x] **MODE_UNLOCK 原地生物解锁（3c7c0b8）**：库锁定且已启用本地快速解锁 → 弹
      BiometricPrompt 免开主界面，认证通过即解封全部库并 finish 回原 App；无本地解锁
      回退引导卡片走主密码
- [x] **搜索框乱弹抑制（b9a1d6e）**：字段分类引入 `SignalStrength`（HIGH/MEDIUM/LOW），
      无密码框时弱信号 USERNAME 不再触发登录候选（对齐 Bastion `AutofillDetectionPolicy`）
- [x] **文档收口（20680df）**：.ai 接力记忆同步（CP 总根因 + 真机验证清单 + 新坑索引）

⏳ **真机待验证（用户装含 f474654 的包，第一优先）**：
① 设置页点「凭据提供商」→ 弹系统启用 Vaultix 界面；② 启用后 Edge/Chrome 登录弹
密码条目 + passkey（**核心闭环验证点**）；③ 开 autoCopyTotp 在 Via 填充 → 密码应能填进
（onResume 时序修复）；④ 搜索框不应再乱弹密码条目。

## 已完成（第二十三轮 2026-09-09 · 磁贴/应用列表修复 + TOTP 链路 + 通行密钥保真）

> 用户拍板：**无障碍兜底不做**（磁贴已覆盖大部分场景）。

- [x] **磁贴不显示**：补 `onTileAdded` / `onStartListening`（写 label/icon/state）+
      `requestListeningState`；设置页那行改为可点击——API 33+ `requestAddTileService`
      弹系统添加框，低版本给手动添加说明（快捷设置排列归系统，应用无开关权）
- [x] **添加 App 读不全**：Manifest 补 `<queries>`（MAIN+LAUNCHER），
      解决 Android 11+ 包可见性过滤；不申请 `QUERY_ALL_PACKAGES`
- [x] **验证码识别**：`HintClassifier` 补 Bastion `isOtpHint` 词表（otp/2fa/验证码/一次性…）
- [x] **纯 2FA 页面**：`FillPlanner` 新增「只有验证码框」分支（此前一条建议都不出）
- [x] **填充后自动复制验证码**：`FillSuggestion.totpSecret` + `MODE_COPY_TOTP`
      （dataset 级 `setAuthentication`，API 26+）+ `VaultixClipboard`；开关 `autoCopyTotp`
- [x] **手动填充**：通知加「复制验证码」动作；验证码总览页改用 `VaultixClipboard`
      （此前 `LocalClipboardManager` 无 IS_SENSITIVE、不自动清除）
- [x] **上限保护**：FillResponse 最多 10 条 dataset（Binder 限制）
- [x] **通行密钥 P0**：`mapFido2` 补齐 13 字段（修复编辑条目清空服务端密钥材料的
      不可逆破坏）+ `creationDate` 用 `decryptOrPlain`；新增 2 例回归测试
- [x] full flavor 编译 + detekt 0 违规 + 单测全绿（data:bitwarden 亦全绿）

## 已完成（第二十二轮 2026-09-09 · C 自动填充保存流程 `onSaveRequest`）

- [x] **`SaveInfo` 接线**（根因）：FillResponse 挂 `AutofillSaveInfo`（账号+密码框为
      requiredIds）；**无匹配 fallback 分支同样挂**——没匹配项才是最需要保存的场景
- [x] **`AutofillSaveMatcher`**（纯函数 + 9 例单测）：目标 URI 归一（网页 `https://host` /
      App `androidapp://<pkg>`）、「同基域 + 同账号」→ 更新判定、默认名称（域名去 www /
      应用名 / 包名）
- [x] **`AutofillSaveActivity/ViewModel`**：透明卡片确认页，新建 / 更新二合一；
      更新时把新网址并入条目 uris（Bitwarden 同款，防同账号多域名重复条目）；
      库未解锁只引导解锁，**不暂存明文**
- [x] 开关 `VaultixPreferences.autofillSavePrompt`（默认开）+ 设置页「保存提示」行
- [x] full flavor 编译 + detekt 0 违规 + 单测全绿

## 已完成（第二十一轮 2026-09-09 · 快捷入口三件套：磁贴 + 手动填充 + 智能复制接力）

- [x] **键盘内联建议降级（用户拍板）**：`InlinePresentation` 依赖输入法实现 Android 11+
      IME inline suggestions API，国产输入法（搜狗/百度/讯飞/QQ/微信）基本未接入 → 不做
- [x] **Quick Settings 磁贴**（`AutofillTileService`，BIND_QUICK_SETTINGS_TILE + ACTIVE_TILE）
- [x] **手动填充界面**（`ManualFillActivity/ViewModel`）：跨库聚合已解锁登录条目 + 搜索 +
      未解锁引导；选条目即复制密码并自动回原 App
- [x] **智能复制接力**（`SmartCopyNotifier/Receiver`）：复制密码后通知栏留「复制用户名」
      （60s 超时、VISIBILITY_SECRET、复用 `VaultixClipboard` 自动清除与 IS_SENSITIVE）
- [x] 设置页自动填充组加「快速填充磁贴」说明行（可发现性）
- [x] 三件套**全程不依赖无障碍权限**（用户判断：多数场景无需无障碍即可解决）
- [x] full flavor 编译 + 全模块 detekt 0 违规 + 全项目单测 0 失败
      （offline flavor 自本轮起暂停参与构建，见「只构建/发布 full」决策）

## 已完成（第十八轮 2026-09-09 · 编辑密码查看修复 + 批次④ 设置页对齐）

- [x] **修复「编辑条目看不到密码」**：编辑表单密码框此前只有随机生成按钮（无明文
      查看开关），密码始终掩码圆点——唯一能明文看到的反而是骰子生成的随机密码；
      补 Bitwarden 编辑页同款眼睛 toggle（新建/编辑均可用，`153da7a`）
- [x] **主题模式三态**（对齐 Bastion themeMode）：VaultixPreferences.themeMode
      （system/light/dark）+ ThemeMode 枚举 + VaultixTheme 接线（MainActivity 收集，
      实时切换）；设置页「外观」组新增单选对话框
- [x] **OLED 纯黑**（对齐 Bastion oledPureBlackEnabled）：深色模式 surface/background
      纯黑（对动态取色与固定色板同样生效）；设置页开关
- [x] **回收站自动清理档位收编设置页**：TrashAutoDeleteDialog 提升到 ui.common
      （回收站顶栏与设置页共用，同一偏好键即改即生效）；新「数据」分组
- [x] **偏好默认值真值源**：core:datastore 新 `VaultixPreferencesDefaults`
      （TRASH_AUTO_DELETE_DAYS=30 / THEME_MODE=system），UI 层 stateIn 初始值不再硬编码
- [x] **对照结论（Bastion SettingsScreen 8 组 vs Vaultix M1）**：已落地=安全（自动锁定/
      剪贴板/防截屏/快速解锁/立即锁定）+ 外观（主题/OLED/动态色）+ 数据（回收站档位）+
      关于；**推迟 M2**=自动填充服务全套（Vaultix M1 未做系统填充）、导入导出/备份、
      开发者日志、界面布局定制、WebDAV/OneDrive 同步、清理全部数据
- [x] 双 flavor 编译 + 全模块 detekt 0 违规 + 全项目单测 0 失败；提交推送 `origin/main`

## 已完成（第十七轮 2026-09-09 · Bastion 对齐·批次③ 回收站自动清理）

- [x] **清理策略纯函数**（core:common 新文件 TrashCleanupPolicy.kt）：对齐 Bastion
      TrashViewModel 语义（cutoff = now - days、剩余天数 = max(0, days - 整除天数)、
      0 = 不自动清空；Vaultix 不提供 Bastion 的 -1 禁用档）；ISO-8601 容错解析
      （本地毫秒 / 服务端微秒-纳秒均可），不可解析恒不清理（宁多留不误删）
- [x] **数据链路**：domain 新 `TrashEntry(item, deletedDate)`，`observeTrash` 改返回
      TrashEntry（本地软删只改列不重写密文，删除时间必须从行级取，DTO 内不可靠）；
      CipherDao 加 `getTrashByVault` 一次性快照
- [x] **到期清理**：`ItemRepository.cleanupExpiredTrash(vaultId, days)`——过期行走
      permanentDeleteItem 同口径（DELETE 入队 → 删本地行 → flush），离线联网补推保证
      服务端同步删除；失败静默 0 不打断 UI；days<=0 零副作用
- [x] **设置**：VaultixPreferences `trashAutoDeleteDays`（默认 30 天）；回收站顶栏
      Settings 图标 → 档位对话框（从不 / 7 / 30 / 90 天，即选即存）
- [x] **清理时机 + UI**：进入回收站即执行到期清理（Bastion cleanupExpiredItemsNow
      同位），实删 snackbar 报数；行内「N 天后自动清理」倒计时（剩 0 天红色
      「即将自动清理」；档位 0 不显示）
- [x] **单测**：TrashCleanupPolicyTest 9 例（cutoff 边界 / 倒计时整除 / 0-负档 no-op /
      三种 ISO 格式解析）；ItemRepositoryImplTest 增 4 例（只删过期 / 0-负档零副作用 /
      DAO 异常静默 / observeTrash 带 deletedDate）；双 flavor 编译 + 全模块 detekt
      0 违规 + 全项目单测 0 失败；提交推送 `origin/main`

## 已完成（第十四轮 2026-09-08 · Bastion 对齐·批次② 验证码五类型 + 批量导入）

- [x] **OtpType 五类型引擎**（core:common Totp.kt）：`OtpType(TOTP/HOTP/STEAM/YANDEX/MOTP)`；
      迁入 Bastion 三生成函数——HOTP（RFC 4226，counter 驱动）、Yandex（委托标准 TOTP）、
      mOTP（MD5(epoch/10+secret+pin) hex 取数字前 6 位补 0）；新增统一入口
      `TotpGenerator.generate(config)`（TotpCodesScreen 与 ItemDetailScreen 详情页共用）
- [x] **五类型 URI 解析与生成**：`otpauth://hotp(counter)` / `otpauth://yaotp(pin)` /
      `motp://issuer:account?secret=&pin=` / `encoder=steam` 识别（Bastion 口径）；
      `OtpUriParser.buildUri(config)` 五类型统一出口；**编码修复**：自研 RFC 3986
      uriEncode/uriDecode（对齐 android.net.Uri 语义，`+` 不转空格）——修复 Steam 等
      Base64 密钥含 `+ / =` 时被 URLDecoder 误解码的隐患，label 亦按 %XX 解码
- [x] **otpauth-migration:// 批量导入**（core:common 新文件 OtpImportParser.kt，纯 Kotlin）：
      手工 protobuf wire format（ProtoReader）+ OtpParameters 7 字段（secret/name/issuer/
      algorithm/digits/type/counter）+ Base64 容错（URL-safe `- _`、form 空格、缺 padding）
      + base32Encode；`OtpScanResult{Single/Multiple/UnsupportedPhoneFactor/InvalidFormat}`
      四分支分发（含 phonefactor:// 显式不支持、裸密钥单条）；剥离 GA name 的
      `Issuer:` 前缀（带/不带空格两种形态）
- [x] **编辑器类型选择**（TotpEditDialog）：类型下拉（五类型）+ 条件字段——HOTP 显
      counter、mOTP 显 PIN（固定 10s/6 位口径在 buildTotpConfig 收敛）、Steam 隐藏参数、
      mOTP 密钥字段标签区分；TotpEntry 贯穿 type/counter/pin（steam 改计算属性）
- [x] **列表行为**：HOTP 行显示「计数器 N」不显示倒计时/进度条；mOTP 按 10s 步长滚动
- [x] **导入入口**：验证码界面顶栏「导入」→ 粘贴对话框；单条预填编辑确认，批量直接
      创建 + snackbar 报数，phonefactor/无效内容提示原因（ImportDialogWithOutcome）
- [x] **单测**：HOTP RFC 4226 附录 D 十向量 + 8 位同源校验；mOTP 规格断言；五类型
      buildUri↔parse roundtrip（含 Steam Base64 `+/=` 编解码无损）；migration 手工构造
      protobuf roundtrip（TOTP+HOTP 双条/大 counter varint/SHA256+8 位/URL-safe 容错/
      各分发分支）；core:common 60 例全绿（全项目 339 例 0 失败）
- [x] 双 flavor 编译 + 全模块 detekt 0 违规（拆 LongMethod/复杂度超限函数、表驱动字段
      解析、MagicNumber 常量化）；提交推送 `origin/main`

## 已完成（第十三轮 2026-09-08 · TOTP 统一界面 + 通行密钥只读列表 + 入口 hub）

- [x] **TOTP 统一界面（对齐 Bitwarden 总览 + Bastion 验证器视图）**：从所有登录条目的
      `login.totp` 归一化展示，区分「已绑定（随密码条目）/ 独立（空密码登录条目，Bitwarden
      官方兼容形态）」两类；实时滚动刷新 + 进度条 + 点按复制；搜索发行方/账号；编辑/删除；
      独立项可「绑定到密码条目」合并后删除
- [x] **Steam 验证码**：Totp 引擎 Base64 密钥 + HMAC-SHA1 + 26 字符专属字母表 + 固定 5 位码；
      otpauth 解析按 issuer/label/algorithm 识别 steam；core:common 单测 5 例覆盖
- [x] **通行密钥只读列表（PasskeysScreen + SavePasskeyDialog）**：拉平所有登录条目的
      fido2Credentials 展示；**只读（仅查看/删除）** 不可自建；保存（绑定）时强制挂到所选
      登录条目，对齐 Bitwarden 无独立通行密钥条目形态（CipherMapper 已支持 fido2 逐字段加密写回）
- [x] **入口 hub**：ItemsScreen 顶栏新增 TOTP 入口；VaultixApp 新增 TotpCodes/Passkeys 路由；
      验证码界面顶栏「通行密钥」按钮进入 PasskeysScreen
- [x] **搜索 + 删除**：TOTP 界面、通行密钥界面均含搜索与删除；密码条目入口沿用既有能力
- [x] 修复编译与 detekt 0 违规门禁（超长行拆分、MagicNumber 常量化、UnusedParameter、
      updateItem 补 vaultId、补 passkey_field_user 字符串与必要 import）；双 flavor 编译 +
      全模块 detekt（0 违规）+ 受影响模块单测（core:common / data:bitwarden 全绿）
- [x] 提交并推送 `origin/main`（5e652ce）

## 已完成（第十轮 2026-09-08 · Bitwarden 对齐审计 + 批 1）

- [x] **对齐审计**（Docs/progress/audit/bitwarden-alignment.md）：Vaultix bitwarden 侧
      vs Bastion reference 全量差距表 + 批次划分（M1-1..7 做 / M2-1..3 推迟 / 不做）
- [x] **编辑丢数据修复（M1-1/2）**：DTO 全载荷承载（card/identity/sshKey/secureNote/
      uri/totp/fido2 对齐 Bastion 字段集）；更新 = 合并上传（toUpdateRequest 只
      重加密可编辑明文，未编辑密文段原样并入）
- [x] **类型保真（M1-3）**：type5=SshKey 显式建模；写路径类型守恒守卫拒写未知类型
      （防 type 漂移）；条目列表/详情类型徽标；非 Login 编辑只开放名称/备注并提示
- [x] **同步收敛与毒丸处理（M1-5/6）**：全量成功后 prune 服务端已移除行（排除
      pending ops）；flush 4xx（401/408/429 除外）弃单
- [x] 单测新增（mapper 载荷保真 4 例 + repository 类型守卫 1 例）；双 flavor 编译 +
      Hilt + 各模块单测 + detekt 全绿
- [x] **回收站视图（S19，批 1 后追加）**：domain/data 新增 observeTrash /
      restoreItem / permanentDeleteItem（本地先行 + RESTORE/DELETE 入队补推，
      4xx 弃单）；条目页顶栏回收站入口 → TrashScreen（恢复 / 永久删除二次确认 /
      空态 / 类型徽标）；observeItem 对已删行保持 null 语义；repository 3 例新单测
- [x] 文档同步（MEMORY / decisions / SESSION / audit 报告）

## 已完成（第十二轮 2026-09-08 · WIP 推送 + 编译膨胀审计 + 编译器健康）

- [x] **WIP 推送（76979ac）**：指纹按钮归位 / 条目字段补全(uri·totp·fido2) /
      TOTP 引擎 / 卡包·SSH 读取打通，共 13 文件，已推 `origin/main`
- [x] **编译膨胀审计（排查"编译器过大无法编译"）**：扫描 674 个函数 + 全模块 detekt
      —— 代码已在质量门禁内（最大源函数 ≤153 行、无超 JVM 方法上限、配置缓存 CI 已开）；
      **唯一真实风险 = Gradle/Kotlin daemon 仅 2GB 堆**（ISSUES #14 同根因）
- [x] **构建堆修复（`gradle.properties`）**：`org.gradle.jvmargs` 2g→4g；新增
      `kotlin.daemon.jvmargs=-Xmx4g -XX:ReservedCodeCacheSize=512m`（Kotlin 编译
      daemon 独立 JVM，大模块生成字节码防 OOM）；注释锁死，防后人误改回 2g
- [x] **CI 单测盲区补位（`ci-debug.yml`）**：原单测只跑 core:crypto / app:testFull /
      data:repository，新加的 `:core:common`（TOTP/强度 16 例）、`:data:bitwarden`
      （CipherMapper 保真 28 例）从未在 CI 执行 → 纳入门禁（项目反复警告的"改了却
      验证不到"盲区）
- [x] 验证：双 flavor 编译 + 全模块 detekt（0 违规）+ 受影响模块单测（core:common 16 /
      data:bitwarden 28 全绿）

## 已完成（第九轮 2026-09-08 · 同步编排全链路接线 + 强度条 + Bastion 快照）

- [x] **Bastion 参考快照 vendored**：`reference/bastion/`（@369ed56，1012 文件/≈13 MB：
      主源码 664 + 单测 155 + repo docs + BastionDocs md + workflows 参考；只读、不参与
      构建/detekt）；接力 AI 无需访问 D:\Bastion；Docs/18 §2 与 .ai/MEMORY 已更新指针
- [x] **同步编排器（Bastion 语义移植，data:repository）**：触发分类/静默语义/
      90s·180s 节流/运行中合并回放/指数退避×5/per-vault 状态流/解锁门卫；
      单测覆盖（虚拟时间）；Hilt 双构造（@Inject 两绑定参数 + internal 五参测试构造）
- [x] **同步 UI 接线**：条目页提示条（运行中细进度条/警告常驻/手动成功短暂提示）
      由 orchestrator per-vault 状态派生；进页 PAGE_ENTER、手动刷新 MANUAL force；
      AutoLockController.onStart 回前台对已解锁库逐库 APP_RESUME；
      库列表卡片行内同步状态（同步中/同步失败；静默成功不打扰）
- [x] **密码强度条**：新建/编辑表单密码框下五档评分条（core:common PasswordStrength
      移植 Bastion，仅提示非门槛）
- [x] 双 flavor 编译 + Hilt 组件（full/offline）+ 各模块单测 + detekt 全绿

## 已完成（第八轮 2026-09-08 · Bastion 冻结决策与参考地图）

- [x] **决策落地（decisions.md）**：Bastion 冻结 = reference implementation，Vaultix = 唯一演进线；只搬三类资产、不做文件级搬迁
- [x] **新增 `Docs/18-Bastion参考地图.md`**：分里程碑参考索引（M1 同步核对 / M2 data:kdbx / M3 Autofill / 1.x）、别读别搬清单、对拍流程、GPL 溯源规范；README 导航与阅读路径已更新
- [x] 长期记忆同步（.ai/MEMORY.md、.workbuddy/memory/MEMORY.md、SESSION-2026-09-08.md）
- [x] M2 提示：`data:kdbx` 启动前通读 Docs/18 §4.3（Bastion KDBX 资产密度最高的参考站）

## 已完成（第六-七轮 2026-09-08 · 真机联调 + 签名修复 + 快速解锁 + 兼容）

- [x] **签名彻底修复（63be53c CI 全绿）**：Secrets env 注入 + PKCS12 keypass=storepass
      （详见 decisions/MEMORY）；preview 只发 **full 单包**（bbf843c）
- [x] **prelogin/同步兼容**：Vaultwarden camelCase 双形态；CF 后自托管请求头组
      （Bastion 同值：Chrome UA/Sec-Ch-Ua/Keyguard-Client/Bitwarden-Client-*）
- [x] **2FA 登录**（经典 OAuth 扩展）+ 多方式选择（TOTP/邮箱/Duo/YubiKey/org-Duo；
      YubiKey 44 位动态码输入）
- [x] **type 0 遗留密文默认放行**（官方/Bastion 对齐；修复「未命名」条目）
- [x] **设备登记 Header**（connect/token 带 device-type/identifier/name；type 0=Android）
- [x] **本地快速解锁（免主密码/免 2FA）**：Keystore user-auth KEK 包裹 + 生物识别/
      设备 PIN 解封；解锁页按钮 + 登录后启用横幅 + 设置页管理
- [x] 设置页（自动锁档位/剪贴板清除/防截屏/动态取色/立即锁定/快速解锁管理/关于）
- [x] 双 flavor 编译 + 单测 + detekt 全绿

## 已完成（第十五~十六段 2026-09-08 晚 · M1 字段对齐闭合 + Bastion 对齐第一批）

- [x] **领域补齐**：folderId/favorite/reprompt/secureNote 进 VaultItem，三处 Mapper 连通
      （拉取/新建/编辑；此前拉取即丢、编辑沿用 stored 等于不可修改）；4 例单测
- [x] **新建条目可选类型**（登录/银行卡/身份/安全笔记/SSH；此前 FAB 固定 Login）
- [x] **linkedId 修正为 Bitwarden 官方分段编码**（100/300/400 段；原 1/2/3/4 全错）
      + VaultLinkedId 枚举 + core:model 5 例锁定 + CI 补 :core:model
- [x] **自定义字段 4 类型编辑器**（fields 按表单意图写回；Boolean/Hidden/Linked/Text）
- [x] **收藏 + 主密码二次验证 UI**（标题行星标 / 附加选项开关）
- [x] **TOTP 相机扫码**（CameraX+ZXing；全屏 Dialog 内嵌，扫码回填不丢表单输入）
- [x] **文件夹选择**（FolderRepository 只读数据流 + FolderPicker，库无文件夹时隐藏）
- [x] **修复验证器「取消=删除」数据丢失 bug**（文案/动作错位；移除 error 色 + 取消并排）
- [x] **随机密码生成**（Bastion PasswordGenerator 生成核迁入，去 zxcvbn/Context；
      生成对话框：长度/字符集/排除选项；密码框 🎰 入口）+ 7 例单测
- [x] **修复表单「验证码下方区域不可见」**（AlertDialog 加 verticalScroll）
- [x] 提交并推送（e6b05d6…f0f5df6，CI 全绿）；真机 adb 已连（荣耀，logcat 无崩溃）

## ⚠️ 策略变更（2026-09-08 晚拍板）：Bastion 代码与 UI 分批整体搬入

保持 Vaultix 架构（多模块 / Bitwarden canonical / CipherDto 密文存储），
把 Bastion 设置/密码条目/验证码/通行密钥/卡包的代码与 UI 先搬过来再改。
**数据模型与 Room 明文表不搬**；包名/模型/DI/偏好键必替换；GPL 溯源必保留。
已完成第一批（密码生成器 + 表单滚动），后续批次见下。

## ★ 最高优先（2026-09-09 定）：Credential Provider 集成（Edge 填充 + 通行密钥都靠它）

背景：真机 dumpsys 实证——Android 14+/17 上 Chromium（Chrome/Edge）取凭据
**优先走 Credential Manager**；Bitwarden 注册了 `CredentialProviderService`
（`BIND_CREDENTIAL_PROVIDER_SERVICE`），Vaultix **没有** → Edge 连 fillRequest 都不发、
网站登录时通行密钥也不出现（passkey 数据都在，应用内 hub 已就绪，只是系统层没注册）。

> **🔴 总根因修复（2026-09-10 定）**：manifest `<service>` 的 intent-filter action
> **误写为 `android.credentials.CredentialProviderService`（漏 `service.` 段）**，正确为
> `android.service.credentials.CredentialProviderService` → 系统从未发现 Vaultix 是
> Provider → 系统设置无启用项、`credential_service` 恒空、Edge/Chrome 永不弹、
> passkey 查不到。此前所有「用户未启用」诊断（dumpsys 空 + 重装被清）均被此掩盖。
> 教训：Credential Provider 注册必须逐字符对照官方模板，勿凭记忆。
> 随附修复：①设置页「凭据提供商」行改用 `CredentialManager.createSettingsPendingIntent()`
> 直达启用界面（老 `REQUEST_SET_AUTOFILL_SERVICE` 与凭据提供商是两个独立设置项且部分设备无反应）；
> ②老 autofill MODE_COPY_TOTP 认证回灌从 onCreate 推迟到 onResume（onCreate 同步
> setResult+finish 认证结果会丢 → 「验证码复制成功但密码没填」）。

- [ ] **⑥（待做）provider settingsActivity**：`credential_provider.xml` 补
      `android:settingsActivity`（系统凭据管理器「密码和账号→Vaultix」里出现管理入口，
      对应 Keyguard/Bastion 的通行密钥专属管理页）；Activity 需处理未解锁态。
- [ ] **⑦（待做）privileged apps allowlist**：passkey origin 校验接入
      `CallingAppInfo.getOrigin()` + 白名单（可参考 Google gpm-passkeys-privileged-apps 列表）。

- [x] **① CredentialProviderService 注册**（DSP 主入口）：manifest `<service>`
      `android.service.credentials.CredentialProviderService` + `@xml/credential_provider`
      ~~capability（`TYPE_PUBLIC_KEY_CREDENTIAL` / `TYPE_PASSWORD_CREDENTIAL`）~~
      🔴 **能力声明已于 `f815ab2` 反转：现仅 `TYPE_PUBLIC_KEY_CREDENTIAL`**（见文件顶部第三十五轮）
- [x] **② 通行密钥查询/创建**：BeginCreateCredential / BeginGetCredential 回调 →
      校验 origin（与条目 URI 匹配）→ 无锁提示解锁（PendingIntent 复用
      AutofillActivity 解锁链）→ 列出匹配 passkey（PasskeysViewModel 数据源）
- [x] **③ 密码凭据供应（8ba40d9，推翻原「passkey-only」假设）**：真机实证——启用
      provider 后 Credential Manager 接管登录字段并同时问「密码+通行密钥」；只声明
      public-key → 密码框为空、老 AutofillService 在凭据字段被绕过（即「启用 provider
      后 Edge 密码弹框消失」的根因）。现改为双能力供应：`credential_provider.xml` 声明
      `TYPE_PASSWORD_CREDENTIAL`；`buildGetResponse` 处理 `BeginGetPasswordOption` →
      已解锁库 Login 条目列 `PasswordCredentialEntry`；新增 `PasswordGetActivity` 透明确认后
      回灌 `PasswordCredential`（与 Bitwarden Android 14+ 行为一致）；锁定态「解锁 Vaultix」
      入口按选项具体类型生成对应 Entry

      > 🔴🔴 **本段叙述已被 `f815ab2`（2026-09-11）反转 —— 别再照它改回去。**
      > 现实是：声明 `TYPE_PASSWORD_CREDENTIAL` 会让 Chromium 系把密码请求
      > **全部路由到 CP 通道**并绕过 Autofill 框架；而 Vaultix 的 CP 密码分支
      > 任一环节失败即返回空 → Edge 密码框什么都不弹，**两条路全废**。
      > 故已删除该能力、只留通行密钥；密码填充回归 `VaultixAutofillService.onFillRequest`；
      > `pwOptions` 保留但标 `@Suppress("unused")`。
      > 完整理由与**未决分歧**（Bitwarden 官方其实声明了双能力）见 `decisions.md` 末行。
- [x] **④ inline suggestions**（API 30+）+ `AutofillInlinePlaceholderActivity`
      （Bastion 15 行 no-op）→ Via 等老路径把候选显示在键盘上方
- [ ] **⑤ 字段角色推断**：迁 Bastion `AutofillFieldRolePolicy`/`AutofillFieldPromotionPolicy`
      → 解决用户名框 UNKNOWN（只有密码框有条目）
- [ ] 依赖：`androidx.credentials:credentials`（catalog 已有，确认 app 引用）+ Play services 依赖评估
- [ ] 参考：Bitwarden 设备上 dumpsys 的 service 结构；Bastion autofill_ng 相关实现

**状态（2026-09-10 收口）**：①②③④ 已实现并推送（98edb37 / 8ba40d9）；**总根因已修
（f474654）**——此前一切「系统未启用 / 被清」诊断均被 action 误写掩盖。⑤ 部分落地
（b9a1d6e 信号强度分级抑制误弹），完整迁 `AutofillFieldRolePolicy` /
`AutofillFieldPromotionPolicy` 仍待做。⑥⑦ 见上（provider settingsActivity /
privileged allowlist）。
轻量解锁链：`AutofillActivity` 含 `MODE_COPY_TOTP` / `MODE_REPROMPT` / `MODE_UNLOCK`
回灌路径；「Via 点填充闪回 Vaultix 不回填」已由 dba5ae1 + e52779e + 3c7c0b8 修复
（用户已确认不闪）。永不加锁仍会锁（进程被杀清内存密钥）→ 参照 Bastion 生物解密管理：
登录后自动 enroll 本地解锁 + 回前台自动弹生物验证（B，待做）。
⏳ 待真机验证启用与填充闭环，清单见第二十四轮区块。

诊断全过程与三层根因详见 `.ai/MEMORY.md`「★ Edge/Chrome 填充失效根因」。

## Bastion 对齐批次（剩余）

- [x] **批次② 验证码条目对齐**（第十四轮完成）：OtpType 五类型（TOTP/HOTP/Steam/Yandex/MOTP）
      引擎；编辑器类型选择与 HOTP counter / mOTP pin 字段；**otpauth-migration:// 批量导入**
      （纯 Kotlin protobuf 解析，顶栏导入入口，单条预填/批量直建）
- [x] **批次③ 回收站对齐**（第十七轮完成）：自动清理策略（autoDeleteDays DataStore 设置 +
      进入即到期清理走 DELETE 入队保证服务端同步删 + 行内剩余天数倒计时 + 顶栏档位设置）
- [x] **批次④ 设置页**（第十八轮完成，M1 范围）：主题模式三态 + OLED 纯黑 +
      回收站档位收编（新数据组）；自动填充/导入导出/开发者/布局定制等 Bastion
      大件对照结论为 M2 推迟（见第十八轮区块）
- [x] **批次⑤ 通行密钥**（第十九轮完成，M1 范围）：列表/详情/删除/绑定保存已有；
      本轮补**详情创建时间展示 + 凭据 ID 复制**；Bastion 重载体（KeePass 绑定/同步/
      凭证发现）因 Vaultix 无 KeePass 层**不照搬**，详见 bastion-parity-assessment.md
- [x] **批次⑥ 卡包**（第十九轮完成，M1 范围）：搬运 Bastion `CardBrandDetector`
      （GPL 溯源保留，core:common 纯算法）→ 详情识别品牌 + 卡号分组、表单保存自动
      回填品牌；Bastion 专属卡面可视化/账单地址/文档卡为 M2 可选

## P0 · M1 收尾（剩余）

- [ ] **真机回归（待用户，装 f0f5df6 preview）**：字段对齐验收（新建选类型→保存→
      官方端对拍 folder/favorite/reprompt/自定义字段 4 类型是否真写回）；
      **linkedId 修复验收**（Linked 字段显示所指字段名与值）；TOTP 扫码；**验证器
      取消不再删除**；随机密码生成；**先去回收站恢复此前被误删的验证码**；
      表单可滚动到底（验证码下方区域可见）；**回收站自动清理验收（批次③）**：
      顶栏档位改 7 天后新建删除条目（倒计时显示）、改档位「从不」倒计时消失；
      **设置页验收（批次④）**：主题模式切深色/浅色即时生效、OLED 纯黑深色下背景变纯黑；
      **编辑密码查看验收**：编辑已有登录条目点眼睛图标可明文核对原密码；
      **通行密钥验收（批次⑤）**：详情展示创建时间、凭据 ID 可复制；
      **卡片品牌验收（批次⑥）**：卡号自动识别品牌（如 Visa/Mastercard）并卡号分组显示；
      **快捷入口验收（第二十一轮）**：下拉快捷设置添加「快速填充」磁贴 → 点磁贴选条目
      → 密码已复制（粘贴到登录框）→ 点通知复制用户名；
      **永不锁定验收**：设置自动锁定为「从不」，息屏再亮屏 / 锁屏解锁后仍保持解锁；
      **自动填充验收**：Chrome / Edge / 三星浏览器分别触发填充（Edge 为重点）；
      **关联 App 验收**：条目编辑「关联应用」→ 选中 App → 详情显示「应用」+ 包名 + 可启动；
      **保存提示验收（第二十二轮）**：全新站点/App 登录 → 弹出保存卡片 → 库里出现新条目
      （带网址或 `androidapp://`）；已存账号改密码登录 → 弹「更新密码」→ 条目密码被更新；
      **磁贴验收（第二十三轮）**：设置 → 自动填充 → 「快速填充磁贴」点击后是否弹出添加框 /
      手动添加后磁贴是否正常显示（不再空白）；
      **关联应用列表验收**：条目编辑「关联应用」→ 列表是否完整（Android 11+ 需 `<queries>`）；
      **验证码验收**：带 TOTP 的条目填充后是否自动复制验证码；纯验证码页面是否能填；
      验证码总览页复制后是否按设置时长自动清除；
      **通行密钥验收**：详情是否显示创建时间（此前恒为「—」）
- [ ] **M2 规划**：见 `Docs/progress/bastion-parity-assessment.md`（差距约 40% 可比覆盖；
      最大两块缺口 = 系统自动填充服务 + 数据导入导出，均架构级工作量）
- [ ] 回归通过 → M1 close-out（文档归档 + 下一里程碑规划）

## P2 · 自动化（WorkManager 周期同步归位）

- [ ] **WorkManager 周期同步 + 网络约束（推迟理由见 decisions「周期同步 M1 判推迟」）**：
      进程存活时 APP_RESUME/PAGE_ENTER 已覆盖「打开即最新」；进程被杀后会话密钥在
      内存（重启必锁），周期任务无可同步内容 → 收益≈0。编排器 PERIODIC 触发已预留，
      P2 与本地 KDBX 队列推送一并设计时再启用

## P1 · 质量基础设施（剩余）

- [ ] Baseline Profile；Gradle 配置缓存；ViewModel 单测；:app 单测用例

## P2 · 自动化

- [ ] WorkManager 周期同步 + 网络约束（`Docs/17` §3.3：禁止常驻轮询）

## P3 · M2（KDBX）

- [ ] `data:kdbx` 引擎（kotpass），按 `Docs/02` §3.4 做往返保真度测试

## 已知未决（接力者注意）

- 快速解锁 payload 在 SecureCredentialStore（key `local_unlock_key::<vaultId>`），
  开关在 DataStore；删除 KEK 仅由系统指纹变更触发（单库 disable 只删 payload+开关，
  多库时 KEK 共享保留——未来多库需「全库清空」入口）
- 同一服务器仅一个账号（vaultId=server）；设备 id 存 SecureCredentialStore（卸载即换，
  服务器端会累积旧设备记录，属正常）
- Detekt 用 2.0.0-alpha.6；2.0 稳定后升级重新生成默认配置核对

## 已完成（第三轮 2026-09-08 · 质量门禁）

- [x] **Detekt 门禁（P1）**：`dev.detekt` 2.0.0-alpha.6（官方兼容表对齐
      Kotlin 2.4.10 / AGP 9.3 / Gradle 9.5）；根工程统一为全部 Android 模块开启，
      阈值在 `config/detekt/detekt.yml`（对齐 Docs/16 硬上限：
      LongMethod ≤150 行 / LargeClass ≤1200 / 参数 ≤8 / 单类函数 ≤40），
      Compose 函数命名与命名参数数字做政策级豁免
- [x] 存量违规清零（crypto 有意捕获用 @Suppress+理由注释、网络超时常量化、
      ItemDetailScreen 拆分降圈复杂度、RepositoryModule→interface、
      ItemRepositoryImpl 解密调度器注入化）
- [x] CI：push/PR 均执行 `gradlew detekt`（--no-configuration-cache）；
      `config/**` 纳入触发路径；单测补充 :data:repository
- [x] `./gradlew detekt` 全模块（main+test）绿

## 已完成（第二轮 2026-09-08 · 详情/编辑/删除 + 自动锁定）

- [x] 条目详情页（S9 最小版）+ 敏感复制（剪贴板自动清除，Bastion 思路 +
      GPL 溯源）+ 编辑（S10 最小版，updateItem 沿用原 id）+ 软删除（回收站语义，
      SOFT_DELETE 入队 + 轻量推送）
- [x] 自动锁定（AutoLockController：切后台计时 elapsedRealtime、超时 lockAll +
      回根导航）
- [x] data:repository 新增 6 个写路径/解密流单测（共 11 个）；双 flavor 编译通过

