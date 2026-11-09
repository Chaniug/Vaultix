# 下一步任务清单 · 归档 1/6

> **历史归档，勿通读。** 覆盖 2026-11-05 ~ 2026-10-22 的已完成记录。
> 入口与当前待办见 [`next-steps.md`](./next-steps.md)。
> 拆出于 2026-11-10：原文件 3592 行 / 280 KB，远超 §7.1 的 300 行上限，
> 且真正的待办被埋在 96% 位置。归档按时间倒序，**只在需要追溯时检索**。

---

## ✅ UI 细节轮（三页真机截图）：权限页 / 锁与安全 / 连接 Bitwarden（2026-11-05）

用户给三张真机截图，逐条收口「设置字符没对齐 + 权限没开时顶部有乱码」、
「锁与安全只需开关、自动锁定『从不』排最后且风格不统一」、「连接 Bitwarden 页面简陋」。
**代码已改、7 个静态门禁全绿**；detekt 曾连红两次，已修复并复验转绿（见下方 11-06 段落）。

| # | 现象 | 真正原因 |
|---|---|---|
| A | 权限未开时状态卡显示 `有 %1$d 项待允许` | 带占位符的字符串**漏传实参**，实参反倒给了不需要参数的副标题 |
| B | 四行右侧状态不对齐 | `SettingsRow.trailing` 垂直居中于两行文字 + `TextButton` 内边距 vs 裸 `Text` |
| C | 「从不」压在最底、说明裸堆、小屏被裁 | 档位照抄 Bitwarden 顺序；`AlertDialog.text` 槽**不滚动** |
| D | chip 折行 / 12dp 圆角 / 次级底主按钮 | 平分屏宽却用四字标签；容器没走统一规格；唯一终点动作没有强调级别 |

**四条判据**：① 带 `%1$d` 的字符串要成对核对参数（静态检查查不出漏传）；
② 「未就绪」≠「待办」，硬件决定的项需要**第三态**而非合并计数；
③ `AlertDialog.text` 自身不滚动；④ 「只需要开关」该砍的是**整行点击**，不是安全链条
（输 PIN / KDBX 主密码 / 系统指纹是封装房间信封的必需步骤，#243）。

详见 [`.ai/SESSION-2026-11-05.md`](../../.ai/SESSION-2026-11-05.md)。

**同日第二批：✅ 全部做完（用户「顺便把下一批次都做完」，去 GitHub 下载 preview 验收）**
1. ✅ `SelectionActionBar` 批量删除加二次确认（一处覆盖四个列表，文案带条数与可恢复性）；
2. ✅ 通行密钥详情框删除改独立 error 按钮 + 确认；验证码编辑框与 ⋮ 菜单两条入口按
   `entry.bound` 分支文案（清配置 / 删整条）并各自加确认；
3. ✅ 全屏表单新增 `rememberDiscardGate`（判脏与保存共用 `snapshot()`），
   回收站自动清理档位改「暂存 + 保存才提交」，hint 写明永久删除不可恢复；
4. ✅ `finish()` 全成功时跳过结果页；失败 / 跳过 / `scopeOnly` / `lockOnly` 仍弹报告；
   PIN 输入 / KDBX 主密码 / 系统指纹认证保留（#243）。
   同族补齐：关闭 PIN 确认、确认导入、防截屏关闭态后果文案。
   详见 [`.ai/SESSION-2026-11-05.md`](../../.ai/SESSION-2026-11-05.md) §6。

**同日第三批：✅ 纯视觉 BUG 13 处已修（用户「还有肉眼可见的 BUG 吗」）**
五类 `AlertDialog.text` 槽补 `verticalScroll`、`AddVaultTypeDialog` 同色叠 alpha 改
`surfaceContainerLow`、徽标圆角统一 `VaultixCorner.Small`、Divider 缩进 / 省略号对齐、
rail 标签统一 1 行 + Ellipsis、认证框补进度圈、失败结果框「完成」→「关闭」、
三处只读框「返回 / 完成」→「关闭」。详见
[`.ai/SESSION-2026-11-05.md`](../../.ai/SESSION-2026-11-05.md) §7.1–7.2。

**🔴 再下一批（未做，需动 ViewModel 契约，按收益排序）**：
1. `VaultListViewModel/Screen` 库列表首帧首启品牌页（全 App 落地页，老用户每次冷启动都撞见）；
2. `ItemsViewModel/Screen` 密码 Tab 首帧「还没有保存的密码」；
3. `CardWalletViewModel` 新建/删除零反馈 + `_saving` 无 `finally`（下次打开表单永久转圈）；
4. `SettingsViewModel` 非可空偏好导致首帧假「开」/ 单选框勾错项
   （`autoViewGateOnNever` 一项已于 11-06 修，见 `SESSION-2026-11-06.md` §3）；
5. `Trash` / `CardWallet` 空态塌缩、`Autofill` 磁贴结果回调空实现、
   `MainActivity` 冷启动主题闪、`SiteIcon` 深色死白圆盘。
   完整证据见 §7.3。

**更往后**：删除类动作的结果反馈（Snackbar + busy 防连点）、
滑动删除确认框补条目名、审计里余下的 P2 文案统一。

**🕐 2026-11-06（四批全部完成，CI 全绿）**：状态核对 / detekt 修复 / 液态玻璃光学层 /
设置页开关与弹窗规格，单测 1637 例 0 失败，preview `v0.11.1-dev-7c1428d`。
玻璃点击动效按用户要求**搁置**（11-07 部分解除，见下方第六批）。
**第五批**：添加库弹窗紧凑化 + 弹窗按钮可换行 + 网盘页宽度归一 + 39 处按钮补边框，
详见 [`.ai/SESSION-2026-11-06.md`](../../.ai/SESSION-2026-11-06.md) §6。
逐批根因与判据见同一篇。

**同日发版**：`v0.11.2` 已发 **Latest**（含人工版发布说明、
`checksums-sha256.txt`），流程与判据见
[`.ai/SESSION-2026-11-06.md`](../../.ai/SESSION-2026-11-06.md) §7。

**🕐 2026-11-07（第六批）**：条目表单 16 个输入框补语义徽章、`UriListEditor` 补回
`FormFieldLabel` 字号、详情页字段层级与复制图标主色、底栏补按压反馈（玻璃态可见）。
金标联盟 SharedView Kit 只做适配评估、**未接**。详见
[`.ai/SESSION-2026-11-07.md`](../../.ai/SESSION-2026-11-07.md)。
11-06 的「玻璃动效搁置」本日**部分解除**（仅按压反馈，不含 lens / 弹性 / 转场）。

**🕐 2026-11-07 下午两批**：① KDBX 同步死锁 —— 显示「已同步」却保存不了，
因基线内存态冷启动丢失、被旧闸门读成「远端变过」，且同步捷径不重建基线；
修法与「不覆盖另一台设备改动」的安全底线见
[`.ai/SESSION-2026-11-07.md`](../../.ai/SESSION-2026-11-07.md) §7。
② UI 第七批：动作条对齐并分列、tertiary 局部点缀、TOTP 页规格统一 + 预览卡，
保留全屏壳的滚动隐身定稿，详见同篇 §8。

---

## ✅ KDBX 库加固轮（第四批）：一个「少算」、一个「少走」（2026-11-03）· #235–#236

用户继续点同一范围（kdbx 的填充 / 数据结构 / 增删改查 / 安全 / OneDrive 同步）。
这一批与前几批相反：不是「多做了」，而是**少算了一点**和**少走了一步** ——
两者都不报错、不崩溃，只在最贵的时刻把前面的谨慎一次抹掉。

| # | 缺陷 | 一句话的后果 |
|---|---|---|
| 235 | `contentFingerprint` 只喂 `folders`（拼好的路径），没喂 `groupPaths` 的拓扑 | 路径集合相同、树不同 ⇒ 判「内容没变」⇒ 写回基线**放行一次可能覆盖远端改动的写入** |
| 236 | 分片上传的重试粒度只有单片，用尽 3 次就整次作废 | 几十 MiB 的库断在 90% 就白传全部；用户感受是「同步好慢、老是失败」 |

**235 的取舍**：根组 uuid 是 `Ver4x.create` **随机生成**的，算进指纹会让「同一份内容建两次库」
指纹不同 ⇒ 每次保存都判「内容过期」⇒ **用户一条都存不进去**。所以 `KdbxMappedContent`
新增 `rootGroupUuid`、指纹里跳过它；`null`（不知道根）时不跳过任何节点 —— 宁可假阳性。
**边界写清楚**：同父组下调整顺序捕获不了（`groupPaths` 没有 index），交给 #185 的 `baselineAligned`。

**236 的三条铁律**：① 永不「跳跃」（服务端说超前 ⇒ 夹回确认位重发，否则文件中间永久缺一段）；
② 「没给字段」与「给了空数组」分开判；③ `0-` 的含义取决于**本地确认到哪**（本地为 0 ⇒ 原地重发；
本地大于 0 ⇒ 会话被清，Restart）。顺带修掉三个真缺陷：**202 却不推进导致同一片无限 PUT（实测 OOM）**、
**每片多一次 GET**、**412 被吞进续传会静默覆盖别人改动**。

**验证**：`:data:kdbx` 236 例、`:data:repository` 258 例、`:app` full 552 / offline 518 例，0 失败；
`detekt` 与 13 个脚本门禁全绿。变异验证：分组树不参与 / 不跳过根组 / `0-` 一律 Restart /
去掉夹回 / 412 归入 Retry —— 各自精确报红后还原。
⚠️ 两处第一版用例本身是错的（「子组上移一层」与「第一片失败」），见 part9 的诚实记录。

---

## ✅ KDBX 库加固轮（第三批）：本地 SAF 每次 stat 整份读（2026-11-02）· #234

| # | 缺陷 | 一句话的后果 |
|---|---|---|
| 234 | `SafKdbxFileSource.stat()` 为算内容 SHA-256 **每次都整份读** | 解锁、每次后台校验、每次同步都多读一遍；2 MiB 库打开看一眼要读两三遍 |

**修法（先证明，再省）**：记住上一次整份读的摘要**与那次读的时间区间**；下次只查元数据，
"大小未变 + 修改时间早于那段区间起点" ⇒ 那段区间里文件没被动过 ⇒ 摘要就是现在这一版。
证据另加两根支柱：**库变更序号**（本进程每次成功写 `++`）与 **#185 的写侧内容指纹**。

**★ 与 #232 对照**：同样用时间戳，那边只能当诊断（比的是**服务端时钟** vs 本机时钟），
这边能当结论（**同一个时钟源**的两次读数）。⇒ 差别不是时间戳，是**谁的时钟**。

**⚠️ 踩到的坑**：第一版判据写成"落在读区间**内**才可疑"，被 `修改时间晚于读区间 必须重读` 当场抓红 ——
区间**之后**的改动同样让摘要过期。改成 `modified >= window.startedAt`。
另外发现 `fileSourceFor` 每次新建 SAF 实例 ⇒ 序号恒为 0，保护**静默失效**，改为按 origin 复用。

**验证**：`SafCachePolicyTest` 14 例；四组变异（退回 `covers()` / 去掉大小判据 / 忽略序号 /
mtime 缺失按 0）全部精确报红后还原。`:data:repository` 258 例、`:app` 533 例、`detekt`、全部脚本门禁绿。

**仍待真机验收**：`VaultixKdbxSaf` 日志里应出现「stat 快速路径：元数据证明内容未变」
（重复打开同一个库时），且本地库第二次解锁明显更快。

---

## ✅ KDBX 库加固轮（第二批）：两条「保障本身成了代价」（2026-11-02）· #232–#233

上一批的结局是"写反了"，这一批是**没写错、但无条件用了最重的手段**。

| # | 缺陷 | 一句话的后果 |
|---|---|---|
| 232 | `OneDriveKdbxFileSource.write` 的读回校验**无条件**整份下载 | 改一条密码 ⇒ 上 2MiB 再下 2MiB；弱网上每次保存多等几秒 |
| 233 | 上传成功后只更新 `vaults.remoteVersionToken`，缓存里的令牌永久留旧值 | 每次解锁都判"远端已变"、白下一次、挂**假的**「远端有更新」角标 |

**232 的判据只剩一条**：远端记录的**大小** ≠ 写入字节数 ⇒ 判冲突（连下载都省）。
时间戳降级为诊断线索（`suspiciousTimestamp`），**永不参与结论** ——
第一版拿它判死，导致**每一次正常保存**都报假冲突。
⚠️ 反向绝不成立：大小相符仍必须下载逐字节比（`readBackPlan` 没有"证明一致"这一档）。

**233 的修法**：`CachedKdbxFileSource.recordUploaded(bytes, newVersionToken)` 把
`vaults.remoteVersionToken` 与缓存 `versionToken` 两个真相一起刷新；容器（`FreshnessHolder`）
在协调器里按 origin 共享 —— 否则收敛写在一个用完即扔的实例上，UI 永远 `UNVERIFIED`。

**验证**：新增 `OneDriveReadBackPolicyTest`（11 例）、`CachedKdbxFileSourceTest` +3 例、
`KdbxSyncOrchestratorTest` +2 例、`KdbxCloudSyncCoordinatorTest` +1 例、
`OneDriveKdbxFileSourceWriteTest` +1 例；五组变异验证均精确报红。
`:data:kdbx` / `:data:repository`（244 例）/ `:app`（533 例）/ `detekt` / 全部门禁脚本全绿。

**⚠️ 两条新教训**（详见 [`.ai/issues/05-KDBX本地库-part7.md`](../../.ai/issues/05-KDBX本地库-part7.md)）：
1. 元数据只能证明"不一致"，**永远证明不了"一致"**；且别用服务端时钟跟本机时钟比大小。
2. 同一事实存两份，必须有**收敛机制**；否则漂移的表现不是报错，是一直挂着的假角标。
3. 变异后仍然全绿，先怀疑"没编译"，再怀疑测试。

---

## ✅ KDBX 库加固轮：四条「结局写反」的缺陷（2026-11-02）· #228–#231

用户点名范围：**kdbx 库的功能 / 安全性 / 密码 / onedrive 同步**。
四条都不是"功能没写"，而是**某个分支的结局写反了** —— 也正因如此，看代码通顺、能编译、测试也绿。

| # | 缺陷 | 一句话的后果 |
|---|---|---|
| 228 | `WebDavKdbxFileSource.verifyReadBack` 用 `runCatching().isSuccess` 把三态压成两态 | 写后**读不回来被当成写成功**（NAS 回 503 / 断网时），用户以为存好了 |
| 229 | `createVault` / `addKdbxVault` 都不写 `vaults.syncStatus` | 新建网盘库**没有「立即同步」入口**，且首次改动被 `impliesLocalChanges=false` 漏推 |
| 230 | `markLocalEdited` 只拦 `CONFLICT`，`markRemoteChanged` 拦三个 | 同一语义两条路径两种行为；`PENDING_UPLOAD_WITH_LOCAL_CHANGES` 被降级 |
| 231 | `AndroidManifest` 里没有 `.kdbx` 的 `VIEW` 过滤器，`<queries>` 也没有 | 文件管理器里点 `xxx.kdbx` ⇒「没有可打开此文件的应用」 |

**验证**：`WebDavKdbxFileSourceReadBackTest`（6 例，含变异验证：回退修复精确红 2 例）、
`KdbxCreateRepositoryTest` +3 例、`KdbxSyncOrchestratorTest` +4 例（含反方向用例）。
`:data:kdbx:test` / `:data:repository:testDebugUnitTest` / `:app:testFullDebugUnitTest` / `detekt` / 全部门禁脚本全绿。

**⚠️ 三条通用教训**（写法细节见 [`.ai/issues/05-KDBX本地库-part6.md`](../../.ai/issues/05-KDBX本地库-part6.md)）：
1. `runCatching` + `isSuccess` 能把"失败"整段吞掉；决定"要不要记成功"的 `Result` 必须三个分支都写。
2. 可空列上的 `null` 往往**有语义**。补值时用整行 `upsert` 又会引入"漏抄一列 = 静默清空一列"。
3. 「写了」≠「用得到」：功能齐备但没有系统入口，用户的起点等于零。

**仍待真机验收**：① 在「文件」里点一个 `.kdbx`，候选列表应出现 Vaultix；
② 选中后应进入添加页且文件名已预填；③ 解锁后列表里有「立即同步」；
④ 断网后保存一次，应看到冲突/失败提示而不是「已同步」。

---

## ✅ #213（真因）：开关被接成恒等映射 —— 两个负号相消（2026-10-30 晚，第二次回归）

用户第二次复测：「还是无法点击，**按钮始终是打开状态**」。命中框（#212）确实也是问题，
但**状态永远不变**说明还有第二层：偏好是反义（`true` = 仍需验证），UI 又取反一次，
而 `Switch` 回调传进来的**已经是新值**（`!checked`）⇒ 写入 `!(!checked)` = 当前值。

| 层 | 修前 | 修后 |
|---|---|---|
| 偏好语义 | `true` = 仍需验证（与键名相反） | `true` = 保持不锁，默认 `false` |
| 键名 | `dev_relax_screen_lock` | `dev_relax_screen_lock_v2`（旧键存反义，复用会静默变"不锁"） |
| UI | `onCheckedChange = { setXxx(!it) }` | `onCheckedChange = viewModel::setXxx`（不取反） |
| 锁管理 | `if (devRelaxScreenLockEnabled()) lock` | `if (!devRelaxScreenLockEnabled()) lock`，读失败默认 `false`（= 锁） |

**新增 3 条偏好层用例**：默认值 / 写口往返 / 旧键必须被忽略。

⚠️ 判据：Compose 的 `Switch`/`Checkbox`/`toggleable` 回调传的是**新值**；
在调用点再取反 = `f(!x)=x`，写成功了但写的还是旧值，单测与 detekt 全看不见。

**待用户真机复测**（这是第二次，务必确认开关能停在"关"的位置）。

---

## ✅ #212：触摸目标只有 32dp（2026-10-30 晚，第一次回归）



用户：「保持不锁的按钮无法点击」。**不是数据流问题**，是命中框。

| 层 | 修前 | 修后 |
|---|---|---|
| 行 | `SettingsRow` 不传 `onClick` ⇒ 行内唯一热区是行尾开关 | 不变（刻意如此：改锁的开关不该"点哪都生效"） |
| 开关 | 命中框 = 52×32dp（M3 `Switch` 不自带 48dp 目标） | 占 52×**48**dp 格子，`Switch` 仍居中画 52×32 ⇒ 视觉零变化 |
| 覆盖 | 只有 `SettingsSwitch` 的 13 个调用点 | 外加 `CapabilityToggle` 三分支、`DisplayOptionsSheet` 矮行加了行高 |

⚠️ **别再用 `Modifier.minimumInteractiveComponentSize()` 给 `Switch` 扩热区** ——
它只"提供" `LocalMinimumInteractiveComponentSize`，`Switch` 不读，写了等于没写。
⚠️ 也别用 `Box.clickable` 扩：内外都收到点击 ⇒ 切两次弹回原样，症状同样是"没反应"。

**待用户真机复测**：设置 → 开发者 → 「保持不锁」，按在开关边缘也应生效。

---

## ✅ 锁语义澄清 + 开发者「保持不锁」· 日志两个问题（2026-10-30）· #210 / #211

用户一次问了四件事：日志内容要不要优化、日志会不会拖性能、正式版与预览版的指纹差异
对不对、以及**预览版要不要加一个开发者设置**把「日志 / 上锁 / 完全不锁」收在一处。

### 回答（结论先给）

| 问题 | 结论 |
|---|---|
| 日志内容要不要优化 | **上一轮已经优化过**（#206 抽了 `LogScrubber`：采集即脱敏、白名单兜底收窄）。本轮只补一处语义化。真正还会"刷屏"的是**别的 tag**的系统噪声 —— 那是 `shouldKeep` 的过滤范围问题，不是脱敏问题 |
| 日志会不会拖性能 | **release 恒零开销**：`VaultixLog.enabled = BuildConfig.DEBUG` ⇒ 关着时连字符串 lambda 都不会执行；`AutofillLogger.d` 同理。debug 下每次调用是一次 `Log.d`（系统缓冲，无文件写、无同步 IO）。**采集**（打开日志对话框）才跑一次 `logcat -d -t 1200`，而且只在用户点开/刷新时跑一次，不在主线程 |
| 两版指纹差异对不对 | 「清后台 / 熄屏回来要验证」= **`OnAppRestart`（重启 App 时）** 档，**不是「从不」**。「从不」在任何触发源下都不锁，进程死亡后由信封**免交互**恢复 ⇒ 两个包若都是「从不」，按代码不可能一个锁一个不锁，先去设置页看那行**副标题**（实际档位）与解锁页形态 |
| 要不要加开发者设置 | **要，已加**：#210 —— 与「查看与导出日志」同组，整组仍 `BuildConfig.DEBUG` 才渲染 |

### 落地

| 层 | 改动 |
|---|---|
| 偏好 | `VaultixPreferences.DEV_RELAX_SCREEN_LOCK`（默认 `true` = 照档位执行） |
| 消费 | `VaultLockManagerImpl` 的 `OnAppRestart` 分支：开关关掉 ⇒ 该档退化成 `Never`。**只**拦这一档 |
| UI | `SettingsScreen.DeveloperSection` 第二行「保持不锁（开发用）」，副标题随开关变化，直说"正式版没有这一项" |
| 通行密钥 | #211：`excludeCredentials` 命中时的 type 串改成 CredentialManager 的规范值（原先是裸写的类名） |

**⚠️ 这一轮最贵的三条教训（都付了 CI 时间）**：
1. **`@set:JvmName` 只改 JVM 方法名** —— Kotlin 的成员解析永远用**属性名**，
   `prefs.setXxx = …` 照样 `Unresolved reference`。
2. **`javap` 的 `public` ≠ Kotlin 的 `public`** —— `InvalidStateError.Companion` 那个常量
   在 Kotlin 侧是 `internal`，编译期就拒（不是运行时）。
3. **relaxed mock 的 `Boolean` 恒 `false`** —— 新开关语义是"`true` = 锁"，于是
   `OnAppRestart` 的正向用例会**静默变绿**（锁根本没发生）。三处构造都要显式 stub。

**门禁**：detekt（2.0.0-alpha.6 CLI 逐文件，7 个改动文件 0 issue）· 新增 3 例单测 ·
CI：第 1 轮 `41033038640` ❌（两处 `e:`，见上 1、2 条）→ `5dded0c` **✅ success**。

**待真机验收**：
① 设置 → 开发者 → 关掉「保持不锁」；把自动锁定设为「重启 App 时」，清后台再进 ⇒ **不应**再要指纹；
② 再打开该开关 ⇒ 恢复要指纹（与正式版一致）；
③ 同一站点重复创建通行密钥 ⇒ 浏览器控制台应看到 `InvalidStateError`（而不是笼统失败）。

---

## ✅ 查漏补齐四项（2026-10-29）· #206–#209

用户：「继续完成计划的开发任务，修复漏洞。查漏补齐」。做法：**先把 `current-status` 里
挂着的那七类「已知未做」逐个 grep 核实**（清单会过期，照着做会"修"一个已经修好的），
再挑出四项小而实的落地。完整流水见 `.ai/SESSION-2026-10-29.md`。

| # | 问题 | 修法 |
|---|---|---|
| #206 | 开发者日志**屏幕显示没有脱敏**（脱敏只挂在导出出口），且 `E/F` 兜底（`\|\| level in "EF"`）把白名单**架空** | 抽 `LogScrubber`：**采集即脱敏**（显示与导出都干净）；兜底收窄为「**且**含 `vaultix`」 |
| #207 | 镜像开关打开时下载页指向 `ghproxy.net`，App 内**没有任何**官方校验和入口 | `UpdateCheckResult.checksumsUrl`（挑 `checksums-sha256.txt`）+ 对话框「核对校验和」；**该地址不走镜像** |
| #208 | 注册请求里的 `excludeCredentials` **全仓零命中** ⇒ 同站点能注册出两把密钥 | `PasskeyExcludePolicy`（解析失败=空集合；命中=拦）；`CreateCredentialCustomException("InvalidStateError")` |
| #209 | 改密码后 `passwordRevisionDate` 不更新、密码历史**永不增长**（两段都只透传） | `passwordRevisionPlan()`：一次解密算两笔账（新 revision + 旧密码进历史，沿用原密文不重加密）；上限 20 |

**⚠️ 四条最值得记住的判据**：
1. **审计清单是"当时的结论"，不是"现在的现状"** —— 本轮就抓到一条已经修好的
   （`UpdateChecker` 走 ghproxy：镜像只作用于下载页、且**默认关闭**）。动手前 grep 一遍。
2. **脱敏要保护的是"看的人"，不只是"分享出去的那份"**；**兜底只能比主判据更严**，
   比主判据更宽的兜底等于没有判据（#206 的 `\|\| level in "EF"`）。
3. **校验物的来源必须与被测物独立**（#207）—— 拿镜像给的校验和验镜像给的 APK 是没验。
4. **「沿用服务端原密文」是手段（别整条重写），不是目的（保持原样）**（#209）——
   这条防丢失的纪律被错误套到了恰恰应该变化的字段上，症状是"不报错但结果是假的"。

**⚠️ 两个沙箱代价（各值一整轮 CI，已自查拦下）**：
① androidx.credentials **1.6.0 没有** `CreateCredentialInvalidStateException`
（下载 AAR 数过：只有 Unknown / Cancelled / Interrupted / NoCreateOption / Unsupported /
ProviderConfiguration / Custom）⇒ **"我记得有这个类"不算数，依赖里的类名要数一遍**；
② **`const val` 不能写在普通 class 里**（顶层 / object / companion 才行）。

**门禁**：本地 8 项脚本全过（索引 = 题注 = 正文）· 新增 **27 例**单测 · CI run **40908552787** ✅（10 个模块 detekt + 9 个模块单测 + `assembleFullDebug`，`e:` 0，**一轮过**）。

**待真机验收**：
① 开发者日志里含 `password=` 的行应显示 `<redacted>`，系统噪声不再刷屏；
② 检查更新 → 有更新时出现「核对校验和」且能打开官方 txt；
③ 同一站点重复创建通行密钥应被拒（不是又建一把）；
④ 改一次密码 → 网页端 / 其它客户端应看到密码历史多一条、`passwordRevisionDate` 变新。

---

## ✅ 文件夹 CRUD（2026-10-27）· #199

用户：「继续开发，完善设置，以及日常的功能」。挑的是功能盘点里**唯一"看得见、动不了"**的一块：
`FolderRepository` 只有读方法、`FolderDao` 只有整体替换 ⇒ 用户能**看到**文件夹（分组视图、
表单下拉框都在消费它），但**建不了、改不了、删不了**。Bitwarden 官方客户端与 KeePassDX 都能建分组。

| 决策点 | 用户选定 |
|---|---|
| 写失败怎么办 | **直连 API，失败当场报错** |
| KDBX 分组一并支持？ | **是** |
| 入口放哪 | **条目页 ⋮ 菜单「前往」组** |

| 层 | 内容 |
|---|---|
| 领域 | `FolderNamePolicy`（名字校验唯一一处，禁 `/` `\` 与超长）+ `FolderRepository` 三个写方法 |
| KDBX | `KdbxItemWriter.createGroup/renameGroup/removeGroup/deletionCostOf` + `Kdbx` 门面 |
| 仓储 | `FolderRepositoryImpl` 用 `resolve(vaultId)` 分流到 `Remote(api)` / `Kdbx` |
| 库 | `AtomicWriteDao.upsertFolder/deleteFolder`（`@Transaction`，Room `@Upsert` 生成实现） |
| UI | `ui/folders/`：`FoldersViewModel` + `FoldersScreen` + `FolderNameDialog`；`FoldersRoute` |

**⚠️ 三条最值得记住的判据**：
1. **Bitwarden 侧不能照搬条目的「本地先行 + `pending_ops`」**：`pruneRemovedRows` 的判定是
   "服务端没返回的 folder 行 = 已删" ⇒ **刚建未推的文件夹会被下一次同步静默删除**。
   ⇒ 成功才写本地，失败一个字不改（代价：离线不能建文件夹，KDoc 已写明）。
   反证用例 `新建失败_本地零写入` 专门锁这条。
2. **两种库的删除文案必须分开**：Bitwarden 只脱标签（条目变「无文件夹」、不删）／
   KDBX 递归删整棵子树（不可撤销）。同一句话会让 KDBX 用户无预警丢数据。
3. **数法只能有一处**：`deletionCostOf` 同时供 `removeGroup` 与 UI 预览；
   UI 侧 KDBX 那支**刻意不显示条目数**（子组没算进去会偏小，给偏小的数字更危险）。

**门禁**：编译（offline + full）✅ · detekt 全模块 ✅ ·
**133 套 / 1328 例 / 0 失败** ✅（本轮 +2 套 / +27 例）。

---

## ✅ 与开源项目对标审计 → 七组修复（2026-10-26）· #186–#192

用户：「与别的开源优秀项目进行对比，看看还有哪些功能缺失，或者不完善，有明显bug。修复这些漏洞」。
做法：先跑**四维度只读审计**（备份与自动填充 / 锁与设置面 / 通行密钥与 KDBX / 安全加固与 CI），
基准是 Bitwarden 官方 Android、Keyguard、KeePassDX、Keepass2Android、Monica、Bastion 快照，
**再逐条落地**。本轮落地七组。

| 编号 | 问题 | 修法 |
|---|---|---|
| #186 | `allowBackup` 默认 true ⇒ 锁库信封被云备份搬走，Keystore 不跟着走 ⇒ 新机上是一把「看着还在、打不开」的假锁 | `allowBackup="false"` + 两份排除规则（`dataExtractionRules` 与 `fullBackupContent`），四条路径在**两条通路**上都排 |
| #187 | 自动填充保存链四处断裂：锁定态漏挂 `saveInfo`（首次锁态登录永不提示保存）／`parse(::…)` 丢实参 + 字段不跳隐藏与空值／`requestCode = id.hashCode()` 撞号（点第 2 行填第 1 条）／明文密码进 `Intent` | 补 `saveInfo`；显式传参 + `saveValueOf`；`RequestCodes.next()`（8000 起 CAS 自增）；`PendingSaveStore` 一次性票据 + 60s TTL + 「已过期」界面 |
| #188 | 锁库不清剪贴板；复制网址被当敏感内容 30 秒后清空 | `clearNow()`（只在确认是自己放的才清）+ `sensitive = !isUri`；`clipboardScope` 改 `Default` |
| #189 | 熄屏不保证 `onStop` ⇒ 自动锁定计时器不启动 | 监听 `ACTION_SCREEN_OFF` 复用 `onAppBackgrounded()` 分流 |
| #190 | ① **409 被当「确定性失败」弃单** ⇒ 远端改动永久丢失；② **内容指纹只摘 5 个字段** ⇒ #185 那道防线在「改网址/改 TOTP/改卡片/改自定义字段/换文件夹」上**恒失效且静默** | ① `HTTP_CONFLICT` 从 4xx 集合剔除，409 走 `incrementRetry`；② 指纹扩为全字段 + `FIELD_SEPARATOR` + Map 按 hash 排序 + `ABSENT/PRESENT` |
| #191 | 搜索只比标题/用户名/uri ⇒ 备注与自定义字段「看得见却搜不到」 | `matches` 补 `notes` + `customFields`；**明确不搜密码**并配反向测试 |
| #192 | `pruneRemovedRows` 把「远端没返回分组」当「远端删了」⇒ 一次不完整响应能清空本地所有文件夹 | 空分组 + 待删 ≥3 时只报警不删 |

**⚠️ 本轮三条最值得记住的判据**：
1. **指纹漏字段 = 那道防线在该字段上恒为真，而且静默**（#190 ②）。审查时看着防线是有的，
   比"没有防线"更难发现。所以要用**表驱动** `FIELD_MUTATORS`（21 字段逐个断言必变）钉住，
   并写死纪律：给 `VaultItem` 加字段必须往表里加一条。
2. **4xx ≠ 可以扔**（#190 ①）。401/408/409/429 都是「再试」类。
3. **「远端没有」≠「远端删了」**（#192），批量删除必须有数量熔断且熔断在保守侧。

**门禁**：编译 ✅ · detekt ✅ · `gradle test --offline --max-workers=1 --no-daemon`
**170 套 / 1671 例 / 0 失败** ✅（本轮 +8 套 / +51 例）· 索引一致性脚本 ✅。

**待真机验收**：
① 锁态下在浏览器登录一个站点 → 解锁后应弹出「保存到 Vaultix」；
② 密码列表点第 2 条 → 填进去的必须是第 2 条；
③ 复制密码后锁库 → 到别处粘贴应为空；复制网址 → 30 秒后仍能粘；
④ 设「离开 5 分钟锁定」→ 熄屏 6 分钟后点亮 → 应要求解锁；
⑤ 两台设备制造一次 409（同一条两端同时改）→ 改动不应该消失。

### 第二批（同日 · #193–#195）

| 编号 | 问题 | 修法 |
|---|---|---|
| #193 | `release.yml` 写死 `gradle-version: 8.11.1`（wrapper 9.5.1）—— `ci-debug.yml` 10-02 就修过，**这条路漏网** | 删 `gradle-version:`（版本只有一个来源）+ 兜底从 properties 读、解析不出 `exit 1` |
| #194 | 附件解密后的**明文**留在 `cache/attachments/` 锁库不清（系统只在缺空间时回收） | 新增 `SensitiveCache` 统一归口（同根 ⇒ 一次删净）；锁库 `clearAll()`、删条目 `invalidateFor()` |
| #195 | ① `keyAlgorithm` 两套名字（`ECDSA` vs `ES256`）② 四处 `FLAG_MUTABLE` | ① `core:model` 新增 `PasskeyKeyAlgorithm`，三处统一 `ES256` ② 全部改 `FLAG_IMMUTABLE` |
| #195 | TOTP 密钥原文进 `PendingIntent` extras（长期共享秘密） | `TotpTicketStore`：extras 只带票据，60 秒可重读 |

> 🔴 **共享根因**：**同一条规则写在两处、只改了一处，看起来就"已修"**。
> 修法不是"记得两边都改"，是**让事实只有一个来源**。

**门禁**：编译 ✅ · detekt ✅ · `gradle test` **131 套 / 1301 例 / 0 失败** ✅ · 索引一致 ✅（含第四批 #198）。

---

## ✅ 第三批：通行密钥创建侧的**作用域**与**语义**（2026-10-26 晚）· #196 / #197

| 编号 | 问题 | 修法 |
|---|---|---|
| #196 | 创建通行密钥**完全不校验** origin ↔ rpId 从属；origin 推导顺序与 GET 侧相反；末级兜底是**凭空构造**的 `"https://$rpId"` | 新建 `PasskeyOriginPolicy`（主机相等 / rpId 为可注册后缀 / rpId 不得宽于可注册域）；删掉凭空构造；`Rejected` 直接 `fail`，**文案不含域名** |
| #197 | `authenticatorSelection.residentKey` 从不读，`discoverable` 恒 `true` | 新建 `PasskeyAuthenticatorSelection`，按 `required`/`preferred`/`discouraged`/旧 `requireResidentKey`/缺省 判定 |
| 基建 | **单测里的 `org.json` 是 mockable 空壳**（连 `JSONObject(String)` 构造器都是空的）⇒ 所有"读 JSON"的代码在单测里只走失败分支，且"恒返回默认值"会让**该失败的用例通过** | `testRuntimeOnly(libs.org.json)` + `UnitTestEnvironmentTest` 守卫 |

> ⚠️ **`excludeCredentials` 仍不读** —— 它影响的是"用户体感"（同一站点重复注册不提示），
> 不属于安全缺口，排在下一批。

---

## ✅ 第四批：底栏液态玻璃（2026-10-27，用户需求）· #198

> 用户原话：「我想底栏做液态玻璃的通透效果，能做吗，开启底栏的液态玻璃效果，
> 需要在设置里面主题模式里面添加液态玻璃开关按钮。」

| 项 | 结论 |
|---|---|
| 半透明 + 上沿高光 + 内描边 | ✅ 做 |
| **实时模糊其后内容** | ✅ 做，**仅 API 31+**（`graphicsLayer { renderEffect = RenderEffect.createBlurEffect(...) }` 作用于图层输入 = 背后已合成内容） |
| 模糊背后的像素 | ❌ Compose 没有这个 API（`Modifier.blur` 模糊的是**自己**） |
| iOS 26 的**透镜折射** | ❌ **有意不做**：需 AGSL 采样源或 `PixelCopy` 逐帧回读，滚动帧率打穿 |

| 改动 | 位置 |
|---|---|
| 唯一实现点 | `ui/glass/LiquidGlass.kt`（参数全集中常量，避免各处拼参数漂移观感） |
| 外观分支 | `VaultixBottomDock(liquidGlass)` + **抽出 `DockRow`** 让两种外观共用布局 |
| 偏好 | `VaultixPreferences.liquidGlassDock`（默认 **false**） |
| 设置开关 | **主题模式**组 → OLED 纯黑之后，`BlurOn` 图标；低版本副标题明说"不支持实时模糊" |
| 穿透 | `MainActivity` → `VaultixApp` → `vaultEntryGraph` → `MainShellScreen` → `VaultixBottomDock` |

> 🔴 **三处静默失效**：① 模糊挂内层 / 先铺不透明 Surface ⇒ 模糊的是自己，表现为"开关坏了"；
> ② `fill.alpha == 1f` 把模糊结果整块盖掉；③ API 30 及以下静默降级。
> 详见 `.ai/issues/06` #198 与 `conventions/8.4-UI·观感.md`。

**新增测试**：`LiquidGlassTest` 8 例（API 门槛表驱动 26/29/30/31/33/35、开关关闭不挂模糊、
填充半透明且深色更透、填充只改 alpha 不改色相、描边深色更淡、降级补偿不越界、高光带比例）。

**待真机验收**：① API 31+ 滚动时底栏后文字被糊成色块；② API 30 及以下半透明 + 副标题提示；
③ 关闭后回到原实心底栏；④ 深色 + 动态色不显脏；⑤ 底栏三段两种外观下布局完全一致。

**审计里尚未落地的（下一批，按优先级）**：
① ~~`excludeCredentials` 不读~~ → **已修** #208（2026-10-29）；
   ~~通行密钥候选不做 Origin 校验~~ / ~~`authenticatorSelection` 不读~~ → **已修** #196/#197；
   `backupEligible/backedUp` 强制 true → **有意为之**，不改（见 `WebAuthn` KDoc）；
② PIN 熔断无冷却 + 忘记 PIN 绕过；「从不」档 KEK 不绑认证；
③ 自动拉取零接线（`SyncTrigger.PAGE_ENTER/APP_RESUME/PERIODIC` 全仓零调用）；
④ 导出丢密码历史/时间戳、`passwordRevisionDate` 从不更新、CSV 导入、文件夹 CRUD、
passphrase UI、安全中心；
⑤ `UpdateChecker` 走 `ghproxy.net` 且不校验 SHA-256；`FillAssistRepository` 默认联网拉规则；
`DeveloperLogsDialog` 的 E/F 兜底不脱敏；
⑥ `KdbxRoundTrip.verify` 只比字段名不比值；`KdbxFidelity.NON_DEFAULT_CIPHER` 从不产生。

---

## ✅ 写回基线：令牌对得上 ≠ 内容来源对得上（2026-10-25 晚）· #185

源自上一轮盘点里"如果只能挑一件就做这件"的那一项（原本在
`audit/bitwarden-kdbx-sync-audit.md` §4.1 记为"当前休眠"）。

**用户可见的后果**：另一台设备改了库（v1→v2），本机同步时报"云端有更新"，
用户没拉、直接改了一笔 ⇒ **远端 v2 被基于 v1 的内容静默覆盖**，两边都没有提示。

| 项 | 结论 |
|---|---|
| 根因 | 主写路径 `saveVia(expectedVersion = 缓存里的旧令牌)` 天生不满足"内容与令牌配套" —— 服务端见令牌对得上就**放行** |
| 防线 1（令牌层） | `KdbxSyncOrchestrator`「只有远端变」分支**绝不**提前推进基线；改做内容对账（相同才降级 `IN_SYNC`） |
| 防线 2（内容指纹） | 新增 `KdbxBaseStore`；`mutateLocked` 在**改动之前**取指纹比对，结论随 `KdbxPendingWrite.baselineAligned` 上浮 |
| 落点 | `KdbxItemRepository.persist` 见到 `false` **在落任何字节之前**抛 `StaleBaseline`（全链路贯通到 UI 文案） |
| 新状态 | `KdbxWriteFailure.StaleBaseline` → `KdbxSyncResult` / `KdbxSyncReport` → `Outcome.SyncStaleBaseline` → 两处 toast |

**⚠️ 四个坑（本轮实测踩到，详见 `.ai/issues/07` #185）**：
① 不能拿 `encode` 字节哈希当指纹（每次重生成 IV ⇒ 必然不同）；
② 不能为比指纹再 `encode`（一次 = 一次 KDF）；
③ 不能把 `updatedAt` 算进指纹（"只是重新编码"会被判成"内容变了"）；
④ **`saveVia` 里不能现查会话内容**（那时已是改后内容 ⇒ 正常保存全线误拦，实测踩到）。

**⚠️ 两个"只对某条路径"的边界**：
- 闸门**只对云端来源**生效（本地 SAF 库没有"远端那一版"，判它等于拦掉所有本地保存）；
- 同步编排器那条写路径传 `baselineAligned = null`（不表态）—— 它推的是"会话里的全部改动"，
  正解是条件写（远端真变过 ⇒ 服务端拒写转 `CONFLICT`）。

**门禁**：编译 ✅ · detekt ✅ · `gradle test --max-workers=1` **162 套 / 1620 例 / 0 失败** ✅ · CI `40189657418` ✅（提交 `1a0a7e1`）。

**待真机验收**：① 两台设备，A 改库 → B 不点同步直接编辑 → 应弹"云端有更新还没拉下来"而**不是**静默保存成功；
② B 点同步拉到 A 的改动 → 再编辑 → 能正常保存；③ 本地 SAF 库编辑不受影响（回归）。

---

## ✅ 首次启动 3 页引导（2026-10-24 晚）· #184

用户问：「首次启动的引导该如何设置呢」。查证结果：`Docs/08` §2 的 S2「引导（3 页）」**从未实现**，
全新安装看到的是品牌空态页。用户拍板：**补 3 页引导 + 插画用代码画的矢量图**。

| 项 | 结论 |
|---|---|
| 准入条件 | 「**无库**」且「**没看过**」——**不是**"第一次装 App"（两半单独用都会误拦） |
| 偏好层 | 新增 `OnboardingPreferences`（独立类，`VaultixPreferences` 已 35/40 个函数） |
| 判断位置 | `RootNavViewModel.showOnboarding`，初值 `false`（宁可晚一帧，不可错拦） |
| 落点 | `VaultixApp` 仅在 `!routedOnce`（真冷启动）时进 `OnboardingRoute` |
| 三页 | ①盾牌=这是什么 ②两块**等大**的库=云端本地地位一样 ③页面框+指纹=自动填充 |
| 出口 | 「开始使用」/「跳过」都写 `onboarding_seen`；第 3 页「去开启自动填充」另写 `fill_setup_prompted` 且**不结束引导** |
| 插画 | `Canvas` + 主题色（深浅色/动态色自动成立），几何比例全部具名常量 |

**⚠️ 两条判据**：
1. **插画不能与文案唱反调** —— 文案说"两个库地位一样"，图里画成云 + 文件，云的视觉重量就压过文件；
2. **手写测试桩前先问"官方有没有现成的"** —— 本轮手写 `MemoryStore` 因 `DataStore.edit{}`
   内部也读 `data` 而全挂，**产品代码是对的**。改用 `PreferenceDataStoreFactory` + `TemporaryFolder`。

**门禁**：编译 ✅ · detekt ✅ · `gradle test` **161 套 / 1605 例 / 0 失败** ✅ · Python 静态检查全 ✅ · 索引一致 ✅。

**待真机验收**：① 全新安装 → 三页 → 品牌页；② 「跳过」→ 直接品牌页；③ 第 3 页跳系统页后返回仍停在第三页；
④ 杀掉重开**不再出现**引导；⑤ 深色 + 2.0x 字体不裁切、插画不压字。

---

## ✅ 权限管理页右侧不齐 + 状态卡曲率（2026-10-24 晚）· #183

用户：「另外权限管理部分设置里面，好像还没有对齐」（附真机截图）。

| 项 | 结论 |
|---|---|
| 现象 ① | 「去设置」（`TextButton`）与「已允许」（裸 `Text`）**左边缘错开 24dp** |
| 现象 ② | 状态卡 28dp 圆角 vs 紧邻分组卡 16dp —— 边界齐了、**曲率没齐** |
| 改法 ① | 抽 `TrailingSlot`（`min 112dp` + `CenterEnd` + `TouchTarget` 高），状态与按钮**同一外框** |
| 改法 ② | 两页状态卡统一 `VaultixCorner.Panel`(16dp)；**`AutofillSettingsScreen` 同批改** |
| 附带 | 权限页状态卡上方补 `Spacing.md`（原本贴顶栏），两页现在都是 12 + 16 = 28dp |

**判据**：一屏两套规格（8.4）最常见的两种形态是「缩进不同」和「曲率不同」——
都不报错、也不容易在对数里发现，只有并排看才露馅。

**门禁**：编译 ✅ · detekt ✅ · `gradle test` **160 套 / 1591 例 / 0 失败** ✅ ·
Python 静态检查 11 个全 ✅ · 索引一致 ✅。

**待真机验收**：① 四行右侧左右边缘各成一条竖线；② 状态卡与分组卡圆角看不出差别；
③ 浅色 / 深色各看一次（深色下 `primaryContainer` 上的「已允许」更淡，要确认仍读得清）。

## ✅ 「点筛选会回到最上面」已改掉（2026-10-24 晚）· #182.1

用户真机复测：「现在往下滑动的时候，发现点击筛选，会直接返回最上面。这样设定不友好」。

| 项 | 结论 |
|---|---|
| 我错在哪 | 只保证「画面不跳」，没保证「位置不换」——**画面连续 ≠ 位置连续** |
| 改法 | `rememberListEdgeHolder` 加 `jumpToTop` 门控 → 偏好 `itemsFilterJumpToTop`，**默认 false** |
| 关闭时 | **完全不碰滚动位**（连"切 chip 回第 0 项"也不做） |
| 开关 | 「条目显示」浮层最下面：`展开筛选时回到列表顶部` |
| 代价 | 关掉后往下滑再展开，顶栏之下仍是空白（#182 说的"难看"）——**取舍交给用户** |

**判据**：位置类改动（滚动 / 分页 / 折叠）默认必须"不打扰"，要打扰就得是用户显式打开的。

**门禁**：编译 ✅ · detekt ✅ · `gradle test` 0 失败 ✅ · Python 检查 ✅ · 索引一致 ✅。

**待真机验收**：① 往下滑几屏点库名 —— 列表**不动**；② 切 chip —— 仍**不动**；
③ 打开上述开关后再重复 ①，才应该回到顶端并露出列表头。

---

## ✅ 筛选面板贴条目 / 滚动后展开难看（2026-10-24）· #182 修完

用户两张真机截图 + 两句话。两条**同源**：面板与列表之间没有一条被明确画出来的边界。

| 问题 | 改动 |
|---|---|
| chip 行与下方条目「隔得很近」 | 下内边距 8dp → **12dp** + 让位侧另加 `QUICK_FILTER_LIST_GAP` 8dp；`QUICK_FILTER_ROW_HEIGHT` 改**算式** |
| 往下滑后点筛选「显示效果很不好看」 | 面板展开且列表已滚离顶部时：滚到 `first=0 / offset=56dp` + 外壳下移同值 + 各项 `graphicsLayer` 反向补偿 ⇒ 内容不跳 |

🔴 **顺序硬性：先让位、再滚**（两帧 `withFrameNanos`）；否则 `scrollToItem` 的锚点被夹到 0。
🔴 **8.4「让位只走 contentPadding」的例外**：只对"自上而下绘制"的浮层成立，条件写在
`itemsShellTopInset` 的 KDoc 里，别扩散。

**门禁**：编译 ✅ · detekt ✅ · `gradle test` **160 套 / 1591 例 0 失败** ✅ · 10 个 Python 检查 ✅ ·
`check_issues_index` 一致 ✅。

**待办（真机）**：① 顶部展开时 chip 与首条之间明显有缝；② 下滑几屏后点库名，面板下方立刻
看到列表顶端；③ 面板展开期间连点 chip，顶栏标题不缩。

---
