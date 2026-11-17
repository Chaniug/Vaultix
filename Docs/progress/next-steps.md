# 下一步任务清单

> 2026-11-14 校订 · **入口文件**：只放「当前状态 + 待办 + 归档索引」。
> 已完成的历史按时间段归档（见文末），**需要追溯时再打开，不要通读**。
> **接力第一站**是 [`.ai/README.md`](../../.ai/README.md) 顶部「🕐 最新状态」（唯一真源，倒序）；
> 本文件顶部状态表只做速查，长期约定见 [`.ai/MEMORY.md`](../../.ai/MEMORY.md)。
>
> ⚠️ `.ai/archive/` 里 9 月的会话记录引用了本文件的若干小节（如「★最高优先」
> 「Bastion 对齐批次」「第二十六轮清单」），那些内容现已归档到 archive-4/5/6 ——
> 按关键词在归档里检索即可，本文件不再承载。

---

## 当前状态（2026-11-17 · 开发中 · 最近 #301 条目页 ⋮ 菜单改版）

| 项 | 值 |
|---|---|
| 版本 | **v0.13.4**（Latest · 混淆包 6,977,746 B · 可覆盖 v0.13.3 · [发布说明](../release-notes/v0.13.4.md)） |
| 上一轮 | **v0.13.4 正式发布**：装的是 #293 后续的单行 PEM 私钥修复 + 诊断加固、TOTP 后台时钟挂起、查看层锁页重排与 R8 报告留存；v0.13.3 已带 #293 凭据 ID 保真。发布说明见 [v0.13.3](../release-notes/v0.13.3.md) / [v0.13.4](../release-notes/v0.13.4.md) |
| 门禁 | **25 个** `check_*.py` + **20 个**自测，连同 detekt / 编码 / 图标几何 / `assembleFullDebug` / 单测全绿；#291 起 CI 钉住「生产裸 `Log` / `!!`」，#292 起钉住「发布产物命名形状」，#293 起钉住「KDBX passkey credentialId 逐字符保真」，#294 起钉住「Bitwarden 推送后 revisionDate 基线必须推进」，**#295 起钉住「编辑条目必须用条目自己的钥匙加密」**，**#296 起钉住「422 不得弃单 + 失败须留脱敏响应体」**，**#297 起钉住「同步必须自证：队列为空也要留痕 + 写路径统一留痕」**，**#300 起钉住「自动锁定时长按大单位分档（天在小时前）+ 验证码 ⋮ 48dp 热区与 6dp 视觉右移不可分」**，**#301 起钉住「条目页 ⋮ 菜单必须是 `DropdownMenuPopup` + 三块带描边的 `MenuGroup`，且四个入口与两个动作一个都不许丢」** |
| Bitwarden 同步 | 2026-11-16 · **#294 推送 UPDATE 成功后基线不推进**（**改了生产代码 + 测试 + 门禁**）：真机「自建 Bitwarden 改了条目、点同步显示正常，服务端却没有改动」。`CipherMapper.toUpdateRequest()` 把本地行 `revisionDate` 塞进 `lastKnownRevisionDate`，Vaultwarden（`src/api/core/ciphers.rs`）判 `cipher.updated_at - dt > 1s` **直接 err 拒绝**；400 落在 `DEFINITIVE_HTTP_CODES` ⇒ 判「确定性失败」**弃单** ⇒ `flushAfterLocalWrite` 只返回 `Queued`、UPDATE 不抛异常 ⇒ UI 显示「已保存」。**缺口**：`flushPending` 把 UPDATE 的 `CipherResponse` 整个丢弃（只给 CREATE 的 remap 用）⇒ 本地基线永远停在编辑前 ⇒ **第二次编辑必被拒**。修法：新增 `advanceLocalRevision()`，UPDATE 成功分支调用，**列 + 载荷两处都写**（载荷那份才是 `updateItem` 真正读的），空串必须挡（空 = 服务端放弃并发检测）；CREATE / DELETE 语义与 400 弃单判据**不动**。新增 `BitwardenSyncRevisionBaselineTest` 3 例（`data/bitwarden` **111 例全绿**）、`check_bitwarden_revision_baseline.py` + **11 组变异自测**，接 `ci-debug.yml` 两步（`android-ci` 36 → 38 步）；行为反证撤掉回写 ⇒ 主契约立刻红、字节级还原复绿。⚠️ 只推 `main` **未推 `rele`**，正式 v0.13.4 不受影响。**✅ CI**：`AI docs consistency` 43808735293 success、`Android CI (Debug)` 43808735246 success（约 3m39s，两 job 全绿，新门禁两步在 CI 均 ✓、detekt `BUILD SUCCESSFUL in 12s`）、preview `v0.13.4-dev-96e8728 · Preview` 资产 `Vaultix-96e8728.apk`（35.8 MB）。⚠️ 只推 `main` **未推 `rele`**，正式 v0.13.4 不受影响。详见 [07 篇 part4](../../.ai/issues/07-数据与同步-part4.md) |
| Bitwarden 条目密钥 | 2026-11-16 · **#295 编辑条目用错钥匙加密**（**改了生产代码 + 测试 + 门禁**）：真机「在已有条目里加 / 改一条网址，保存后本地能看到，云端却没变；点同步也没被云端覆盖回来」。`CipherRequest` **没有 `key` 字段**，`toUpdateRequest()` 一律用**账号密钥**加密 `name` / `notes` / `login.uris` / `fields` —— 而按 Bitwarden 官方 `Cipher.toEncryptedNetworkCipher()` 与 Keyguard `buildCipherCodecPair`（`itemCrypto = key != null ? 条目密钥 : 全局密钥`），带 `cipher.key` 的条目**必须**用条目自己的密钥加密字段、`key` 本身用账号密钥（组织条目用组织密钥）包裹。后果链：写进服务端的密文用账号密钥加、却挂在带 `cipher.key` 的条目上 ⇒ 官方客户端 / 网页只按条目密钥解 ⇒ `MacVerificationException` ⇒ **显示旧值**；Vaultix 自己 `decryptToString` 失败后**回退账号密钥** ⇒ 本地看起来完全正常 ⇒ 把错误藏住（这也是「同步回来也没覆盖」的原因：`pendingIds` 队列已清，全量拉取的旧版被回退解码「解成功」，看起来一致）。修法：新增 `resolveItemKey()` + 拆出 `buildUpdateRequest(item, stored, accountKey, contentKey, hasItemKey)`，七个可编辑字段全改 `contentKey`，请求体补 `key = stored.key.takeIf { hasItemKey }`，`toStoredCipherDto` 顺延 `key = key ?: previous?.key`（**整体替换语义下漏带即抹掉**）；`toRequest`（新建）保持账号密钥且不带 `key`，`passwordRevisionPlan` 仍走账号密钥。测试断言朝向**反过来**：新增 `CipherMapperItemKeyWriteTest` 7 例，用 `asOtherClient()` 模拟官方客户端「只按条目密钥解」，断言**别人能解开**而不是自己能解开（`data/bitwarden` **118 例全绿** = 原 111 + 新增 7）；行为反证把 `contentKey` 变异回 `key` ⇒ 4 条主契约全红、还原复绿。新增 `check_item_key_write.py` + **11 条变异自测**，接 `ci-debug.yml` 两步（`android-ci` 38 → 40 步）。含 2 个门禁自身的坑（`sanitize()` 把 `@SerialName("key")` 抹成空串 ⇒ 假红；同文件 `@SerialName("key")` 出现 5 次 ⇒ 全文搜假绿，改括号配平切 `data class` 段）。⚠️ 只推 `main` **未推 `rele`**，正式 v0.13.4 不受影响。**✅ CI**：`AI docs consistency` 43815003889 success（12s）、`Android CI (Debug)` 43815003799 success（06:10:51Z → 06:15:54Z，约 5m03s，两 job 全绿；日志实证 `Check item-key write direction (#295)` 输出「✅ 条目密钥写方向契约通过（#295）」、11 条变异自测逐条 ✅）。⚠️ 报例数有个坑：`build/test-results/` 留着已删测试类的旧 XML，按目录累加会算多（本轮先报 121，实际 118）。| Bitwarden 422 弃单 | 2026-11-16 · **#296 HTTP 422 被当确定性失败弃单 ⇒ 改动静默丢失**（**改了生产代码 + 测试 + 门禁**）：真机 `0.13.4-dev-abeb7bc`（已含 #295）日志 `flushPending op=UPDATE ... code=422 definitive=true`，10 秒后 `sync skipped: revision 未变`。链条：422 落进 `DEFINITIVE_HTTP_CODES` ⇒ `pendingOpDao.remove` 弃单 ⇒ 队列空 + 服务端 revision 未变 ⇒ `Skipped` 冒充同步成功。对照官方 `CipherRequest` 与 Vaultwarden 1.24/1.28/1.30/1.33/main 的 `CipherData`，并在本机起 1.30.0 / 1.33.0 真实例用同构请求体实测 PUT **全部 200** ⇒ 请求体结构不是充分原因，缺的是 422 响应体。修法：① 422 与 409 同列**留队重试**，不再弃单；② `safeErrorBody()` 把错误体脱敏（EncString / JWT / 长 token → 占位符，限长 512）并入 `flushPending` 失败日志；③ `advanceLocalRevision()` 推送成功后清 `vaultixLocalRevisionDate`，让「修改于」不再停在导入时间。新增 3 例行为测试（`cleanTest` 后 **121 例全绿** = 118+3）、`check_http422_policy.py` + **10 条变异自测**，接 `ci-debug.yml` 两步（`android-ci` 40 → 42 步）；顺手修正 #294 自测两处失效锚点（11/11 复绿）。22 个门禁 / 19 个自测本地全绿，`compileFullDebugKotlin` / `compileFullDebugUnitTestKotlin` 通过。**✅ CI 真实结论**：`d881fb33` 两 run 全绿——`Android CI (Debug)` 43825961602 success（约 3m，两 job 全绿；`Check HTTP 422 failure policy (#296)` / `Self-test HTTP 422 failure policy checker` / detekt / unit tests 全 success）、`AI docs consistency` 43825961594 success；preview `Vaultix-d881fb3.apk`（`0.13.4-dev-d881fb3`，35.9 MB）已发到 preview release。首轮 `1e0498db` 的 43824448459 曾 detekt 三红（复杂度 / 魔法数 / 行宽），抽 `logFlushFailure()` + `MAX_ERROR_BODY_CHARS` + 拆长行后复绿。**待真机回传带 `body=` 的日志**再对齐具体字段。详见 [07 篇 part6](../../.ai/issues/07-数据与同步-part6.md) |
| Bitwarden 同步可观测 | 2026-11-16 · **#297 同步后日志只剩一行「revision 未变」**（**改了生产代码 + 测试 + 门禁**）：用户装 `d881fb3`（已含 #296）复现，`vaultix_logs_20261116_155316.txt` 里**只有** `15:51:54.315 VaultixSync: sync skipped: revision 未变`，**连 `flushPending` 都没有**；而 #296 之前的 `..._142908.txt` 明明有 `flushPending ... code=422`。⇒ 无法区分「改动没进队列（写路径）」「进了队列被清掉（推送路径）」，两者修法完全不同。根因：`flushPending` 队列为空时**静默 `return Result.success(Unit)`**，写路径 `flushAfterLocalWrite` 成功时**一条日志都没有**；`executeSync` 随后照常报 `Skipped`，`BitwardenSyncOrchestrator` 的 `Skipped` 分支记 `lastSuccessAt`、清零 attempt、不排重试 ⇒「同步成功」符合当前代码逻辑，只是这个"成功"不含"推了什么"。**静态排查（续 #296）**：`BitwardenJson` 是 `explicitNulls=false` 且未开 `encodeDefaults` ⇒ null 字段被省略；改 URI 路径上 `type`/`reprompt`/`match` 均走有界 Int 转换、`uri`/`name` 是 EncString 或 null ⇒ **请求体值层面也无非法值**（与本地起 1.30/1.33 全 200 一致）。修法：① 空队列留 WARN（含"队列为空"+"不会上传"两层语义）；② 开头报「待推送 N 条（按 op 类型）」、收尾 `logFlushSummary` 报「全部成功 / 剩 M 条留在队列」；③ `flushAfterLocalWrite` 成功与失败**都留痕**，`ItemRepositoryImpl` 新增 `private const val SYNC_TAG = "VaultixSync"` 与推送侧共用 ⇒ 一次 `adb logcat -s VaultixSync` 看清「入了什么队 + 推了什么」；④ 摘要只聚合 op 类型，**不得带条目 id**（`VaultixLog` 铁律）。验证：新增 `BitwardenSyncFlushObservabilityTest` 3 例（`cleanTest` 后 **124 例全绿** = 121+3），行为反证把空队列改回静默 ⇒ 用例立刻红、字节级还原复绿；新增 `check_sync_observability.py` + **14 条变异自测**，接 `ci-debug.yml` 两步（`android-ci` 42 → 44 步）；`data:repository` **352 例全绿**、detekt 通过。⚠️ **写门禁时自测抓出 3 个真实假绿**：① `sanitize()` 抹掉字符串字面量 ⇒ 文案断言假红；② KDoc 里也写着 `return Result.success(Unit)` ⇒ 朴素 `find()` 命中注释、分支体被截空；③ 按"下一个 `fun`"截函数体会把下一段 KDoc 算进来 ⇒ 删文案的变异假绿。三种都已修并各有对应用例（`_raw_method` / 行首锚定正则 / `_brace_body` 花括号配平）。**待真机回传日志**：必须出现 A「队列为空…不会上传」或 B「待推送 N 条（UPDATE=1）」，A ⇒ 查写路径，B ⇒ 紧跟着的 `body=` 就是服务端拒收原因。**同批（写路径闸门）**：四个写入口把 `saving` 复位写成调用之后的下一句，而 `ItemRepositoryImpl` 用 `error(...)` **抛**异常 ⇒ 一次失败就让 `saving` 永久 `true`、之后每次保存被 `if (saving) return` 静默吞掉（写路径压根没跑 ⇒ 队列为空 ⇒ 点同步只得「无改动」）；复位移入 `finally`，`createItem` 另补 `catch (CancellationException) { throw }` + `catch (Throwable) { Result.failure }` 收口（原本逃逸会炸 App）。新增 `ItemsViewModelSavingGateTest` 3 例（复位 / 第二次仍打到仓储 / 闸门本身仍生效），三条变异反证全红；`app` **724 例全绿**、detekt 通过。**CI 实证**：`58e3440b` → `Android CI (Debug)` 43829870101 / `AI docs` 43829870031 全绿；`c1f3cc54` → `Android CI (Debug)` 43834201528 / `AI docs` 43834201510 全绿；回填提交 `fdedc937` → `AI docs` 43835799717 绿；preview 资产 `Vaultix-c1f3cc5.apk`（35.85 MB）。详见 [07 篇 part7](../../.ai/issues/07-数据与同步-part7.md) |
| 条目页 ⋮ 闪退 | 2026-11-17 · **#301.1 点 ⋮ 直接闪退**（**改了生产代码 + 测试 + 门禁**）：用户「点三个点的按钮会闪退。直接闪退的」，确认位置=**条目页顶栏 ⋮**、时机=**一点就崩、菜单根本没出现** ⇒ 崩在 Popup 首次组合 + 测量阶段。根因是两件各自正确的东西撞在一起：① M3 `DropdownMenuPopup` 内部是 `Column(modifier.width(IntrinsicSize.Max))`（`AndroidMenu.android.kt:165`），开菜单时向子树**索取 intrinsic 宽度**；② #301 把来源行换成了 `MiddleEllipsizedText`，它内部是 `BoxWithConstraints` = **`SubcomposeLayout`，不支持 intrinsic 测量** ⇒ 抛 `IllegalStateException`。**改版前不崩的原因**：老 `MenuVaultCard` 用的是朴素 `Text` + Ellipsis，菜单里从未调用过 `MiddleEllipsizedText`（`ItemsScreen.kt` 那个调用点在**列表**的 `VaultStatusRow`）—— 典型「把组件从 A 场景搬到 B 场景却没检查 B 的约束模型」。修法：新增 `MiddleEllipsizedTextInIntrinsicScope`，用 `Modifier.layout` 在布局阶段回读 `constraints.maxWidth`（不参与 subcompose）保留中间省略；**不退回朴素 Text**（会失去中间省略、长路径尾部文件名被吃掉）、**不用固定 280dp 硬算**（会把字号 / 无障碍大字体钉死在推算里）、**不用 `onTextLayout`**（本 Compose 版本不在公开包下）。新增第 8 条门禁「菜单内不允许出现不支持 intrinsic 测量的 subcompose 组件」（`BoxWithConstraints`/`SubcomposeLayout`/`LazyColumn`/`LazyRow`/`rememberTextMeasurer` + 正向钉住 intrinsic-safe 变体）。变异反证：真把 `BoxWithConstraints` 写回去 ⇒ 首轮 `compileFullDebugKotlin FAILED`（漏 import）**假阳性**，补齐后重跑才拿到真断言红（**本轮第二次踩「红的是断言不是编译」**）。`app` **740 例 0 失败**、detekt 通过、25 门禁 + 20 自测全绿。详见 [06 篇 part29](../../.ai/issues/06-界面与交互-part29.md) 的 `301.1` 节 |
| 条目页 ⋮ 菜单 | 2026-11-17 · **#301 ⋮ 菜单改版**（**改了生产代码 + 测试**，只动形态不动功能）：用户「这个部分的 UI 该如何设计才好看呢？信息直观，功能完善，目前感觉这个看起来很老套的样子」+ 截图。改前是**单个 `DropdownMenu`**：一整片同色底板、4dp 圆角、组间只靠 1dp `HorizontalDivider`、组标签是「前往」那行 11sp 灰字。⇒ 换 **M3 Expressive 分组菜单**：外层 `DropdownMenuPopup`（⚠️ `DropdownMenu` **自带整块底**，在里面套 `DropdownMenuGroup` 会被重新粘成一片 —— 编译器与 detekt 都看不出，只能读框架源码）+ 三块 `DropdownMenuGroup` + 库身份卡 / 导航四项 / 同步锁定行。四处取证：① 三组统一 `MenuDefaults.groupShapes(VaultixCorner.Panel, …)` 16dp 四角全圆，**不用** `groupShape(i, n)`（那是「一整块被切开」语义：首 16/下 8，配 `GroupSpacing = 2.dp`，density 3.47 下渲染成亚像素线）；② 间隙用 `Spacing.sm`(8dp) 而非框架的 2dp；③ **1dp `outlineVariant` 描边是「能不能分出三块」的关键** —— 组色 `surfaceContainer #F1F0EF` 与底 `surface #FDFCFB` **亮度只差约 4%**，无描边时浅色下三块重新糊成一片（截图逼出来的实证，不是偏好）；④ 徽标**不复用**解锁页 `VaultKindBadge`（那是 `UnlockScreen` 的 private，尺寸用途都不同，抽公共组件收益不抵风险）。信息直观：组 1 补 40dp 库类型徽标（`VaultCardInfo.kind` **必填无默认值** —— 有默认值就会有人不传，退化成「少一枚徽标」）、组 2 删「前往」小标题（结构本身已表达分组）。**刻意不动**：组 3 `MenuSyncLockRow` 一字未改（2026-11-08~09 改四轮才定稿）、三组共用 `MENU_PANEL_WIDTH = 280.dp`（2026-10-04 真机对照过切库变形）。`ItemsMoreMenuGroupingTest` **7 条源码门禁**（含「四个入口与两个动作一个都不能丢」），**四条变异反证全红、还原复绿**（⚠️ 变异① 首轮是 `Unresolved reference` **编译红 = 假阳性**，补回 import 才拿到真断言红）。`app` **739 例 0 失败**（87 份 XML）、detekt 通过、**25 门禁 + 20 自测**全绿。**CI 实证**：`67993348` → `Android CI (Debug)` **44006173605** / `AI docs` **44006173648** 全绿；preview **`Vaultix-69045c2.apk`**（35.87 MB）。详见 [06 篇 part29](../../.ai/issues/06-界面与交互-part29.md) |
| 自动锁定审查 / 验证码 ⋮ | 2026-11-17 · **#300 两组用户反馈**（**改了生产代码 + 测试**）：① 「锁与安全 → 自动锁定有没有 bug / 逻辑错漏，参考 Bitwarden」⇒ **逐环节对照后判定核心健全、不动**（10 档 `VaultTimeout` 含 `OnSystemLock`/`OnAppRestart`、`checkForVaultTimeoutInternal` 四族穷尽分流、每库 `timerJobMap` 回前台全撤、亮屏按 `elapsedRealtime()` 单调时钟重算并钳 `[0, duration]`、`isDeviceLocked` + 300ms 复检 + 可作废 token、「从不」档密钥常驻 + `viewLock` 双层、门禁开关与 `AutoViewGate.quickLockReady` 同源）；⚠️ 刻意不改并写明理由的四点：`UserChanged` 生产零调用（= 上游**账号切换**语义，Vaultix 单用户多库没有）、`onAppCreated` 只处理活动库、改档位不重算当前会话、`devRelaxScreenLock` 只拦 `OnAppRestart`。**真 bug 一个**：`vaultTimeoutLabel` 自定义档先判小时再判天，而 1440 = 24×60 ⇒ `auto_lock_day_fmt` 死资源，填 1440 显示「24 小时」、2880 显示「48 小时」；抽纯函数 `customTimeoutUnit()`（天在小时前）+ `CustomTimeoutUnitTest` 4 例（整天 / 23h 不被天吞 / 分钟 / 上限 69 天全遍历）。② 「验证码条目 ⋮ 偏左一点」⇒ 反编译 `material3-android:1.5.0-alpha16` 取证 `IconButtonKt` 容器 **恒 48dp**（字节码 `StateLayerSize=48`），2026-10-19 注释按 36dp 推算「贴右距离不变」是错的：20dp 图标居中左右各留 14dp，视觉右缘比原设计多缩 **6dp**。修法：**热区 `minimumInteractiveComponentSize()` 保留**（不倒 2026-10-19 的 48dp 护墙）、图标 `offset(x = TotpRowMenuIconVisualNudge = 6.dp)` 推回右缘 8dp、只 offset 图标不 offset 按钮（点击手感不变）。`TotpRowMenuAlignmentTest` 4 条源码门禁（**先剥 `//` 注释**避免 KDoc 里「36dp」误导 —— 同 #296/#297 同款坑）。变异反证：去掉 offset ⇒ 1 红、热区退回 `size(36)` ⇒ 2 红，均还原复绿。`app` **732 例全绿**、detekt 通过、23 门禁 + 20 自测全绿。**CI 实证**：`449da722` → `Android CI (Debug)` 43996846249 / `AI docs` 43996846308 全绿；preview `Vaultix-449da72.apk`（35.85 MB）。详见 [04 篇 part10](../../.ai/issues/04-锁与解锁-part10.md) |
| KDBX 通行密钥 | 2026-11-15 · **#293 正式版 KDBX passkey 登录 GitHub / x.com 报「通行密钥有问题」**（**改了生产代码 + 测试 + 门禁**）：三条差分对照（电脑 KeePassXC 正常 / Vaultix 的 Bitwarden passkey 正常 / 只有 Vaultix 读同一 KDBX 失败）把根因收敛到 `KdbxPasskeyCodec.toCredential()` 的 `normalizeCredentialId()` —— 把 KeePass 生态的 **base64url 无填充** credentialId 转成**标准 base64**（补 `=`、换出 `+` `/`），而 `WebAuthn.rawIdFromStored()` 决策是「原样回传存储文本」（2026-09-12 真机证伪 decode→re-encode、`WebAuthnTest` ② 钉死）⇒ 断言 `id`/`rawId` 成了 RP 按 base64url 解不出的文本。修法**只动读侧**：`credentialId = credentialId` 原样、删 `normalizeCredentialId()`；**不动** `WebAuthn`、不动写方向 `fromCredential()`。订正过时溯源（`KPEX_PASSKEY_*` 由 KeePassXC 定义、KeePassDX 遵循，无 `KPXC_*` 体系）。首轮 `data/kdbx` 3 红全为「测试把缺陷当规格」或自造假红断言，逐条订正后 **274 例全绿**；新增跨模块契约测试 + `check_kdbx_passkey_id.py` + 11 组变异自测（含「KDoc 写旧函数名不误报」），接 `ci-debug.yml` 两步；两轮反证：门禁 11/11、把旧转换注入回生产代码 5 例立刻红且字节级还原复绿。**✅ CI 真实结论**：`AI docs consistency` 43676429827 success（约 10s）、`Android CI (Debug)` 43676429706 success（04:21:47Z → 04:26:29Z，约 4m42s，两 job 全绿，新接的 `Check KDBX passkey credentialId fidelity (#293)` / `Self-test …` 两步在 CI 均 ✓、门禁输出「✅ KDBX passkey credentialId 保真契约通过」）、preview `v0.13.2-dev-e0e630a · Preview` 资产 `Vaultix-e0e630a.apk`（35.8 MB，**#292 短命名约定同步生效**、正文表四项完整）。⚠️ 只推 `main` 未推 `rele`，正式 v0.13.2 不受影响；文档结论回填同样只推 `main`，不二次发版；**真机验收已于 2026-11-15 完成**，修复随 v0.13.3 / v0.13.4 发布。详见 [part6](../../.ai/issues/03-通行密钥与凭据提供商-part6.md) |
| 整体排查 | 2026-11-11 · [报告](audit/project-audit-2026-11-11.md)：43 个依赖坐标中 **29 项已最新**、3 项禁单独升、**2 类**真违规（订正后）、3 项质量基建缺失（属「要建」） |
| 不需要修 | 2026-11-11 · [判定方案](audit/no-fix-register-2026-11-11.md) + [52 条登记](audit/evidence/no-fix-entries-2026-11-11.md)；门禁 `check_no_fix_register.py` 已接 `ci-docs.yml` |
| 液态玻璃 | 2026-11-11 上午 · **#280 第六轮**（**改了生产代码**）：修 `uGlow` 高斯量纲、表面流光 / 通透厚度搬回绘制层（31+ 全档）、SDF 梯度折射 + 按压透镜、跟手与亮 120 / 灭 360；detekt + 31 条玻璃单测全绿，[接力记录](../../.ai/issues/06-界面与交互-part18.md) |
| 底栏材质 | 2026-11-11 中午 · **#281 材质分档**（**改了生产代码**）：常态改通透磨砂（0.74/0.80、30dp、无 AGSL），液态玻璃回提质感和通透感（24dp、0.50/0.62、内阴影 0.26、rim 0.62、阴影 8dp）；两档共用采样链路，源层不再乘开关；玻璃单测 38 条，[接力记录](../../.ai/issues/06-界面与交互-part19.md) |
| 第五批 UI | 2026-11-12 · **#282 五组反馈**（**全改生产代码**）：底栏去 1dp 细描边 + 液态玻璃「中心更透、边缘更立」（填充 0.44/0.56、clarity 0.13、vignette 0.06、shader 0.075，模糊与阴影不动）；KDBX 云图标统一 `CloudDone`（**形状统一 ≠ 语义合并**）；「+」剪影卡三色系交叉；设置页删 5 处 Spacer 修 PIN 按钮错位 + 精简自动锁定文案 + 三兄弟「取消」改有边框（**反转 #275 规则 1**）；条目列表分组头成块 / 计数胶囊 / 副标题恒定一行 / 类型徽标对齐详情页 / 行尾点缀整组一胶囊（**零新增 State**）；⚠️ **标题补 `maxLines = 1` 是本轮唯一显示行为变更**（密码页原先折行、其余三页早已单行），已单列第 5 条门禁钉住。门禁：**1778 例单测 0 失败**、detekt / `assembleFullDebug` 绿、全套静态门禁绿；`lintFullDebug` 本地红在 42 条**既有**问题（CI 不阻断，见 part20）、[接力记录](../../.ai/issues/06-界面与交互-part20.md) |
| 深色模式专项 | 2026-11-12 · **#283 三组反馈**（**全改生产代码**）：① 主题模式「取消」及同款按钮补 M3 `MinWidth` + 显式 `contentPadding`（#273 的 `IntrinsicSize.Min` 只治竖排没治「贴字小胶囊」），22 个文件 60+ 处动作槽全走共用组件，`DialogShell` 走 `FlowRow` 刻意不动；② 站点计数去掉 `tertiaryContainer` 胶囊（**反转 #282 判据**）退为 `onSurfaceVariant` 纯文字；③ 深色剪影卡补 `onXxxContainer` 配对 + 叠色画纯，底栏按 Material / Apple / 玻璃拟态三路取证引入 `glassLighting()` 三档（`sheenScale 0.35` / `shadeScale 0.45` / 外影 `8→2dp`），8 处白高光 + 3 处黑暗部统一走 `sheenAt` / `shadeAt`。门禁：**1785 例单测 0 失败**、detekt / `assembleFullDebug` 绿、17 项静态门禁 + 13 项自测全绿；[接力记录](../../.ai/issues/06-界面与交互-part21.md) |
| 按钮规格 / 通行密钥 | 2026-11-12 · **#284 两组反馈**（**全改生产代码**）：① 反编译取证推翻 #273/#283 的 intrinsic 根因（material3 `AlertDialogFlowRow` = 纯 `FlowRow`、Dialog 全家零 `IntrinsicSize`），删固定宽度回归原生 M3 wrap-content（**反转 #283 规则 4**、#282 边框判据不动、7 处注释订正）；② 排查结论 **KDBX 能存通行密钥**（codec → writer 加密字段 → mapper 读回、仓储已放行），管理页结构完整，但 `toCredential()` 五项必填而手工录入不产出 `userHandle` ⇒「存了读不回来」，修法 `WebAuthn.randomUserHandle()` 兜底 + `userName` 必填 + 补齐元数据 + 删死代码，三层测试闭环。门禁：**1791 例单测 0 失败**、detekt / `assembleFullDebug` 绿、16 个 `check_*.py` + 13 条自测 + 编码 / 图标几何 / 探针共 32 项全绿；[接力记录](../../.ai/issues/06-界面与交互-part22.md) |
| 六组 UI / 主题 | 2026-11-12 · **#285 六组反馈**（**全改生产代码**）：① 网盘账号卡从「一组多行」壳改为一个账号一张紧凑卡（状态徽标收进卡内、两枚同规格 M3 按钮、同款圆角）；② **反转 #282 分组头成块**，改无背景 section header + 三档间距（组间 20 > 组内 4 > 头与首条 2dp）；③ 详情页 `TopBarTitle.singleLine` 只走缩字号 / 省略号；④ **作废 #282/#283 的 `xxxContainer` 交叉 + alpha 分档**，剪影卡改 M3 fixed accent roles（浅深同值、删光 7 个 alpha 常量与深浅判档）；⑤ 反编译取证补齐 12 个 fixed roles + 三处 `dynamicColor` 默认值统一 `false` + `supportsDynamicColor()` 单一真源让低版本禁用 + OLED 纯黑压整套 9 档 surface；⑥ KDBX 顶栏统一显示 `KDBX`（`vaultTopBarTitle` 单一真源，列表状态行仍带文件名）。门禁：**1806 例单测 0 失败**、detekt / `assembleFullDebug` 绿、33 项静态门禁 / 自测全绿；[接力记录](../../.ai/issues/06-界面与交互-part23.md) |
| 网盘账号注销闪退 / 资源 ID | 2026-11-13 · **#290 点「注销」网盘账号即闪退**（**改了生产代码**）：真机日志 `0.13.0-dev-731cdae` 两次 FATAL `Resources$NotFoundException: String resource ID #0x0`，栈在 `NoticeSnackbar` → `stringResource(0)`，经 `Recomposer.performRecompose` ⇒ **Compose 重组期异常**，`launchGuarded` 兜不住。根因：`CloudAccountsViewModel` 11 条 `const val SETTINGS_CLOUD_* = R.string.*` 被 Kotlin 内联成 **0**（AGP 编译期 R 类 `0x0` 占位、运行时才 `0x7f0f01xx`；`javap -constants` 11 条全 `= 0`），`strings.xml` / `resources.arsc` 文案都在 ⇒ 不是资源被裁。修法：① 11 条改普通 `val`（`<clinit>` `getstatic R$string.* → putstatic`，`javap -c` 验证）；② 抽 `safeStringRes()` 认 `Resources.ID_NULL`，`NoticeSnackbar` 与 `CloudError.Res` 两处变量式解析都过它；③ 无效 id 也 `onConsumed()`；④ `CloudError.Res` 降级 `error_unknown_no_detail`、抽 `cloudErrorText()` 压回 detekt 圈复杂度 14。⚠️ **订正 #289**：`probeWebDav()` 成功同样下发 0 ⇒ 两次闪退同源，`launchGuarded` 仍必要但不是当时那次闪退的根因。含 6 条坑，新建 `CloudAccountsResourceIdRulesTest` 4 条 + `CloudAccountsResourceIdBehaviorTest` 5 例，反证 `const val` 回退 3 例红 / 撤守卫 2 例红；门禁：**1860 例单测 0 失败**（186 份 XML）、`detekt` + `test` + `assembleFullDebug` `BUILD SUCCESSFUL in 1m 52s`、33 项静态门禁全绿；CI：`5553bda4` → `Android CI (Debug)` success（约 4m36s，两 job 全绿）、preview Release `Vaultix v0.13.0-dev-5553bda · Preview`（`Vaultix-v0.13.0-dev-5553bda-preview.apk` 35.8MB）；[接力记录](../../.ai/issues/06-界面与交互-part28.md) |
| WebDAV 闪退 / 异常边界 | 2026-11-13 · **#289 连上 WebDAV 后闪退、重进账号已添加**（**改了生产代码**）：「账号已添加」= 凭据已落盘 ⇒ 崩溃点在**成功之后**（`_state.update` → `refresh()` → `CloudAccountInventory.list()`：Room Flow + Keystore 解密 + MSAL 会话枚举）；工程**没有全局 `CoroutineExceptionHandler`**（#98 实测）、两个 ViewModel 全是裸 `viewModelScope.launch` ⇒ 任一处抛出即**进程退出**，且凭据写入 / 自检 / 清单读取全压在默认主线程。修法：两个 ViewModel 共 10 个动作收口 `launchGuarded`（`runCatching { }.onFailure { }` + 取消 / `Error` 原样重抛 + 异常转可见错误态与 `VaultixLog.e`）、重 IO 显式 `withContext(Dispatchers.IO)`（⚠️ 不在统一入口改 dispatcher，MSAL 必须主线程）、`probeWebDav()` 内部两处 `runCatching` 也区分取消以免误删凭据、添加页四动作同步收口。含 6 条坑（无全局兜底时裸 launch = 进程退出 / detekt `TooGenericExceptionCaught` / 设置页中文日志 label 被判 UI 硬编码 ⇒ 10 个改 ASCII / `runCatching` 吞 `CancellationException` / 统一入口别切 dispatcher / 沙箱 `local.properties` 丢失报 `SDK location not found`），新建 `CloudAccountsCrashGuardTest` 4 例 + `WebDavLaunchGuardRulesTest` 4 条，反证撤兜底后 2 例变红。门禁：**1851 例单测 0 失败**（184 份 XML）、detekt / `assembleFullDebug` 绿（`BUILD SUCCESSFUL in 3m 19s`）、全套静态门禁全绿；CI：`731cdaee` → `Android CI (Debug)` success（约 17m，两 job 全绿）、`Sandbox unit tests` 3m10s，preview Release `Vaultix v0.13.0-dev-731cdae · Preview`（`Vaultix-v0.13.0-dev-731cdae-preview.apk` 37.6MB）；[接力记录](../../.ai/issues/06-界面与交互-part27.md) |
| WebDAV 连接 | 2026-11-13 · **#288 坚果云 403「登录不上去」**（**改了生产代码**）：真机「测试连接 → WebDAV 账号或密码不正确（HTTP 403）」。匿名 / 假 Basic 凭据 × 4 方法 × 3 UA 差分实测 **12/12 全 401**（`WWW-Authenticate: Basic realm="nutstore"`）⇒ 用户带凭据拿到的 403 是**认证已通过后的服务端拒绝**（官方 FAQ：微信二次验证 / 访问频率 / 应用密码被撤销 / 目录权限），旧实现把 401/403 合并成「密码错」把用户锁死在重输密码上；**证伪上一轮 P0**（`accessTokenForHost()` 对陌生 host `return null`）但顺手修掉。四处修法：① `testConnection()` 主探 `PROPFIND Depth: 0`（`405/501` 才降级 HEAD，与 `listChildren()` 同源，404 不再假报成功），两条路径共用 `probeFailureOf()` 判据表；② 401/403 分流到 `credentialsMessage` / `forbiddenMessage` 两处真源，**`errorMessage()` 也分流**（stat/read/list/强写共同出口），403 明写「不是密码错」+ 四条可排除原因；③ 密码框下加「应用密码 / 关二次验证」提示；④ 新增 `webDavProductionClient(shared)`（`newBuilder()` 共享连接池、清 interceptors / networkInterceptors / Authenticator），`CloudAccountConnector` 与 `KdbxCloudSyncCoordinator` 两个构造点都过它，并订正三处过时 KDoc。含 6 条坑（403≠401 / 自检与使用同源 / 分类放共同出口 / **Kotlin 块注释可嵌套** / `forEach {}` 要 `}` 闭合 / detekt `ThrowsCount` 与 `MaxLineLength`），新建 `WebDavConnectionTest` 7 例 + `WebDavConnectionRulesTest` 5 条，反证撤 `interceptors().clear()` 后 P0 用例变红。门禁：**1843 例单测 0 失败**（182 份 XML）、detekt / `assembleFullDebug` 绿、33 项静态门禁全绿；CI：`f5635822` → `AI docs consistency` success（10s）、`Android CI (Debug)` success（4m23s，两 job 全绿），preview Release `Vaultix v0.13.0-dev-f563582 · Preview`（`Vaultix-v0.13.0-dev-f563582-preview.apk` 37.6MB）；[接力记录](../../.ai/issues/06-界面与交互-part26.md) |
| 写回持久性 | 2026-11-12 · **#287 协程取消 / 写回持久性修复**（**改了生产代码**，判据第三次反转）：用户补充「点保存停 5 秒看到『已保存到本地 · 正在同步到云端』才生效，大部分人点完就返回」⇒ 根因**不是 #286 的同步闸门**，而是更前面：`mutateLocked` 已把新会话换进内存（本地立刻可见），`persist()` 旧顺序先跑 `Kdbx.verifyWrite()`（KDF 往返 1~5 s）再写脏缓存，用户在这几秒返回 ⇒ `viewModelScope` 被 `onCleared()` 取消 ⇒ `fileCache.save` / `markLocalEdited` / `autoUploader.enqueue` 全没跑 ⇒ DB 仍 `IN_SYNC`、缓存无脏标记 ⇒ #286 双真源两个都干净 ⇒ 点同步放行拉远端旧字节 `replaceSession`，改动消失。修法四道防线：① 八个写动作共用的 `serialWrite` 改 `withContext(Dispatchers.IO + NonCancellable) { writeGate.withLock { } }`（`withContext` 必须在 `withLock` **外**，等锁期也不许取消）；② 脏缓存**提到几秒自检之前**先落；③ 新增 `cacheRestore` 快照，失败恢复缓存 + 回滚会话，`CancellationException` 只重抛不跑回滚链；④ `updateItem` / `deleteItem` 统一 `launch { withContext(NonCancellable) { } }`（`launch(NonCancellable)` 已 deprecated）。⚠️ 取舍：点完保存立刻锁库 / 切后台时写回仍跑完（几秒），取消半途落盘只会制造三份矛盾的真相。含 6 条坑（deprecated 写法 / `functionBody` 从签名起点截表达式体 / 取消不是失败 / detekt `ThrowsCount` 合并 catch / `runBlocking` 而非 `runTest` / 反证撤 `NonCancellable` 必须变红），新建 `KdbxWriteDurabilityRulesTest` 5 条源码门禁 + `KdbxItemRepositoryTest` 1 条行为测试。门禁：**1827 例单测 0 失败**（179 份 XML）、detekt / `assembleFullDebug` 绿、33 项静态门禁全绿；CI：`7fc7cbc5` → `AI docs consistency` success（12s）、`Android CI (Debug)` success（4m22s，两 job 全绿），preview Release `Vaultix v0.12.0-dev-7fc7cbc · Preview`；[接力记录](../../.ai/issues/06-界面与交互-part25.md) |
| OneDrive 同步 | 2026-11-12 · **#286 数据完整性修复**（**改了生产代码**，非观感问题）：用户报「onedrive 上的 kdbx 改完条目，上传中途返回 / 断网后刷新被云端旧内容覆盖，等于没改成功」⇒ 两个叠加根因：① `persist` 写带 `pendingLocalEdits` 的脏缓存 + `PENDING_UPLOAD`，自动上传一跑就把 DB 状态洗成 `SYNCING`/`FAILED`，而刷新闸门 `impliesLocalChanges` 不认这两个状态 ⇒ 误判「本地没改」→ 拉远端旧字节换会话 → `markResolved` 记已同步 → `fileCache.save` 清脏标记，改动永久消失；② `enqueue` 只在保存那一刻调用，全仓无冷启动 / 恢复补传入口。修法：`hasUnsyncedLocalEdits()`（DB 状态 **或** 缓存脏标记）收敛为唯一判据 + `resolveUsingRemote()` 下载**前 / 后**两道闸门 + `localChangedSinceLastSync()` 委托协调器 + `readRemoteCurrent()` 回写缓存前**现查**脏标记；新增 `resumePendingUploadsNow()` 与 `startUnlockObserver()`（订阅 `Kdbx.sessionRevision()` 按新解锁差集补传）。⚠️ **本轮第二轮补正**：第一版「启动扫一次」在**库还锁着**时是空转 —— `Kdbx.saveVia()` 要 `KdbxSessionStore` 会话，必然 `NotUnlocked` 并写假 `FAILED`；改为只补传已解锁的库（`sessionReady = Kdbx::isUnlocked`），锁态库由解锁事件精准补传。门禁：**1821 例单测 0 失败**（178 份 XML）、detekt / `assembleFullDebug` 绿、23 项静态门禁全绿；CI：`68665533` → `AI docs consistency` success（9s）、`Android CI (Debug)` success（4m51s，两 job 全绿），preview Release `Vaultix v0.12.0-dev-6866553 · Preview`；[接力记录](../../.ai/issues/06-界面与交互-part24.md) |
| 安全红线 | 2026-11-14 · **#291 release 裸 `Log` 泄露 + 生产 3 处 `!!` NPE**（**改了生产代码**）：用户「签名一直放 GitHub Secrets，没问题；安全问题你决定怎么修」⇒ 签名链路完全不动，安全侧由助手拍板只修两项真违规。⚠️ **根因：分支 ≠ buildType** —— `BuildConfig.DEBUG` 由编译期 buildType 决定，R8 只裁 `if (BuildConfig.DEBUG) { … }` 整块，源码里一句裸 `Log.d` 在 release 照输出（`reseal $vaultId` 把完整库标识写进 logcat）。① **S2**：8 处收敛到唯一 `VaultixLog` 出口（6 处真违规 + `AutofillLogger` 2 处半合规；异常交给 `throwable` 参数、`vaultId` 经 `redact()` 脱敏、63 个调用点一行未改），白名单精确到 `VaultixApplication.kt` 门面 sink 单文件；② **S3**：3 处 `!!` 全改「取局部快照 + 空值有去向」（凭据创建 / 断言签名 / 同步编排），顺带修掉 `KdbxSyncOrchestrator` 工厂调两次的隐性 TOCTOU，**不用** `requireNotNull`（那只是把 NPE 换成另一种崩溃）；③ 新增 `check_production_safety.py` + 6 组自测，接 `ci-debug.yml` 两步；④ 同一判据存两份（Kotlin `nonUiCallSites` / Python `_ALLOW_HINTS`）在日志改 trailing lambda 后误报 UI 硬编码 ⇒ 补 `_log_lambda_mask()` 只追踪日志 lambda、selftest 7→9 条，两边都补 `VaultixLog.` 标记。❌ **不夹带**：S6 崩溃可取证（要「建」不是「修」，D07 脱敏边界待拍板）、CP 双能力分歧（`decisions.md:66` 等真机 A/B）、厂商白名单引导、**S1 依赖解析门禁（仍是 P1 第一优先，不混进本提交）**。详见 [part14](../../.ai/issues/01-构建与环境-part14.md)。**CI 真实结论**：首轮 `99b738cb` 的 `Android CI (Debug)` 43557701747 **failure**（detekt `sync` 圈复杂度 15 > 14）⇒ 抽 `resolveSyncSource()` 修红 `fcb77f9d`；修红后 `AI docs consistency` 43559918223 **success**（约 12s）、`Android CI (Debug)` 43559918391 **success**（约 4m27s，两 job 全绿）；preview Release `Vaultix v0.13.1-dev-fcb77f9 · Preview`，资产 `Vaultix-v0.13.1-dev-fcb77f9-preview.apk`（35.8 MB）。⚠️ 只推 `main`、未推 `rele`，正式包不受影响；本轮仍待真机验收 |
| 发布命名 | 2026-11-14 · **#292 预览包 / Release 命名精简**（纯 workflow + 门禁，**无生产代码改动**）：用户「`Vaultix-v0.13.1-dev-fcb77f9-preview.apk` 名字偏长…以后这种名字短一点」⇒ preview APK 39→18 字符 `Vaultix-<7位SHA>.apk`、标题去冗余 `Vaultix ` 前缀（37→29），**stable 侧不动**（`Vaultix-v0.13.1.apk` 本就够短）。⚠️ **#218 身份硬约束一字不砍**（同一用户 11-01 拍板）：标题必带版本 + 短 SHA、APK 名必留 7 位 SHA，**不能**叫 `Vaultix-preview.apk`（多包互相覆盖、对不上构建）⇒ **短 = 删重复，不是删身份**。新增 `check_release_naming.py`（4 模板精确匹配 / 变量数 ≤ 1 / 渲染长度预算 / #218 身份不退化 / 步骤缺失即红）+ 11 组真实 workflow 变异自测，接 `ci-debug.yml` 两步（⚠️ **不能挂 `ci-docs.yml`**：其 paths 不含 `.github/workflows/**` ⇒ 改了命名却不触发门禁 = 假绿灯，#218.7 同源）；长期约定 [8.12-发布产物命名](../../.ai/conventions/8.12-发布产物命名.md)，接力 [part15](../../.ai/issues/01-构建与环境-part15.md)，01 篇索引 64→65。验证：39 项静态 / 文档门禁全绿、长名上先跑门禁 rc=1（5 条违规）→ 改完转绿、三轮「改坏门禁实现」变异反证均被自测抓到、改动段 `bash -n` PASS。❌ 不动签名链路、不推 `rele`、不改 `versionName`。**✅ CI 真实结论**：`AI docs consistency` 43570409121 **success**（约 9s）、`Android CI (Debug)` 43570409124 **success**（07:17:36Z → 07:21:21Z，约 3m45s，两 job 全绿，新命名门禁两步在 CI 均 ✓）；1860 例单测 0 失败；preview Release **`v0.13.1-dev-b28d439 · Preview`**，资产 **`Vaultix-b28d439.apk`**（35.8 MB），CI annotation 实证 `app-full-debug.apk → Vaultix-b28d439.apk`，正文表版本 / 提交 / 构建 / 签名四项完整。⚠️ 只推 `main` 未推 `rele`，正式 `v0.13.1` 不受影响 |
| 正式发布 | 2026-11-14 · **v0.13.2**（**纯发布 + 文档，无生产代码改动、无新增测试**）：用户「已经积累了一定的内容了」⇒ 拍板 `0.13.2`（patch，`versionCode` 自动派生 `13002 > 13001`，可覆盖安装）+ **明确授权快进 `rele` 发正式版**。发版前 `fetch --tags` 确认真实 `origin/rele` tip `dd2e845b`、先 bump `VERSION` `0.13.1 → 0.13.2`（`v0.13.1` 已是 Latest，不升版会被 `release.yml` 幂等步骤覆盖）、手写 `Docs/release-notes/v0.13.2.md` 优先于提交列表。装的是 `dd2e845b..main` 6 提交：**#291**（release 裸 `Log` 收敛 + 生产 `!!` 清零 + CI 红线、detekt 修红）、**#292**（预览包 / Release 命名精简 + 门禁）及其结论回填。⚠️ 签名链路完全不动；#291 的 release 行为真机验收 4 条仍挂 `- [ ]`，静态门禁不能替代真机。**✅ 发布结论（真实 run 结果）**：`Android Stable Release` run `43592092124` `completed/success`（约 7m58s）；`Vaultix v0.13.2` 已置 **Latest**，资产 `Vaultix-v0.13.2.apk`（6,977,802 B / 6.65 MB，混淆）+ `checksums-sha256.txt`，APK SHA-256 `804c39ef…e5b5e853`、签名指纹 `7e6c8c07…1fd8`（与 v0.13.1 一致可覆盖安装）、attestation 1 条；手写发布说明 `v0.13.2.md` 被正确采用；tag `v0.13.2` → `933d629d`，`rele` 与 `main` 齐平（0/0）。main 侧只触发 `AI docs consistency` 43591840939 success（版本 / 文档改动不在 `ci-debug.yml` paths 内，按设计不跑 debug CI）。文档回填只推 `main` 不再推 `rele` ⇒ 不触发二次发版；详见 `.ai/README.md` 顶部「最新状态」 |

## 真机验收（#293 · KDBX 通行密钥登录 · 主路径已闭环）

> ✅ **2026-11-15 真机复验**：老 KDBX 库的 GitHub passkey 登录成功，
> 日志 `PK assertion ready … storedIdForm=base64` + `onFinalResponseReceived`；
> **x.com / Bitwarden 对照 / 多凭证形态未单独复验**，下方保留 `- [ ]`。

> 本轮改的是 **KDBX → 领域模型 → 断言响应** 的 `credentialId` 文本形态。
> 本地 274 例 + 门禁 + CI 全绿**不能替代真机**：这是「只有对上 RP 才暴露」的缺陷。
> 逐条判据见 [`03-通行密钥与凭据提供商-part6.md` §293.8](../../.ai/issues/03-通行密钥与凭据提供商-part6.md)。

- [x] **主路径（GitHub 已通过）**：preview / debug 包解锁用户的 KDBX 库，
      用库里保存的 passkey 登录 **GitHub** 成功，不再提示「通行密钥有问题」；
      **x.com 未单独复验**（见上方完成说明）。
- [ ] **对照仍正常**：同一台机上 Vaultix 的 **Bitwarden 库** passkey 登录不回归；
      本应用**新建/注册**的 KDBX passkey 也能正常断言（写方向 `fromCredential` 未动）。
- [ ] **多凭证 / 特殊 ID**：库里含 `-` / `_` 的 credentialId、一条登录挂多个 passkey
      （`_n` 后缀）都能正常列出并断言。
- [ ] **可取证（若仍失败）**：抓 logcat 的 `VaultixAutofill` tag，看
      `PK assertion ready … rawId=… storedIdForm=...` 一行里的 `rawId` 是否为
      **无填充 base64url**（不含 `+` `/` `=`）。这是根因是否被真正修掉的直接判据。

## 待真机验收（#295 · Bitwarden 条目密钥写方向）

> 本轮改的是**写方向用哪把钥匙**，判据是「别的客户端能不能解开」，
> Vaultix 自己能不能解开**不算证据**（回退会把错误藏起来）。
> 逐条判据见 [`07-数据与同步-part5.md` §295.8](../../.ai/issues/07-数据与同步-part5.md)。

- [ ] **网页端读新值**（关键）：在 Vaultix 里改一条已有条目的 URI / 名称 / 密码并保存 →
      等同步完 → 打开 Vaultwarden 的 Web UI 看该条目，**必须是新值**。
      修前必错的就是这条。
- [ ] **第二条客户端交叉验证**：用官方 Bitwarden 手机端或桌面端登录同一账号看同一条目，
      同样必须是新值（网页端能解、官方客户端不能解 = 还没修干净）。
- [ ] **原本带条目密钥的条目**：挑一条**之前就有** `key` 字段的条目（老版本 Bitwarden 建的，
      或从其它客户端同步来的）改一次，保存后不出现「无法解密」红字，
      服务端内容也确实是新的（证明 `key` 被原样带回、没被抹掉）。
- [ ] **组织条目**：组织库里的条目改一次，其它成员 / 网页端能看到新值
      （组织条目用组织密钥包裹，这条验的是包裹链没被写歪）。
- [ ] **回滚判据**：若以上仍失败，抓 `adb logcat -s VaultixSync` 里是否有
      `cipher.key` 解密失败的 `MacVerificationException` —— 有则说明写进去的还是账号密钥。

## 待真机验收（#297 · 同步可观测性）

> 本轮**只修"看不见"**，不宣称定位到服务端拒收原因。判据是「下一份日志能不能
> 一句话判出故障在写路径还是推送路径」。逐条见
> [`07-数据与同步-part7.md` §297](../../.ai/issues/07-数据与同步-part7.md)。

- [ ] **关键：下面两句必出现其一**（改一条 URI → 保存 → 点同步 → 导出日志）：
      ```
      A) VaultixSync: flushPending 队列为空（无待推送 op）⇒ 本次同步只做拉取，不会上传任何改动
      B) VaultixSync: flushPending 开始：待推送 1 条（UPDATE=1）
      ```
      **A ⇒ 故障在写路径**（改动压根没入队）；**B ⇒ 故障在推送路径**（紧跟的
      `flushPending op=UPDATE ... code=... body=...` 就是服务端拒收原因）。
- [ ] **自动锁定显示档位**（#300）：设置 → 锁与安全 → 自动锁定 → 自定义 1440 分钟，应显示「1 天」而不是「24 小时」；2880 应显示「2 天」。
- [ ] **验证码 ⋮ 对齐**（#300）：验证码列表每张卡片右上角 ⋮，图标视觉右缘应与密码/卡片条目列表的行尾元素贴右一致（不再明显偏左）；点击热区仍是 48dp，不缩水。
- [ ] **条目页 ⋮ 不闪退**（#301.1，**最高优先级**）：条目页顶栏 ⋮ 点开应正常出现三块卡片，不再闪退。
- [ ] **来源行仍中间省略**（#301.1）：菜单第一块的第二行（来源 / 路径）长路径应保头保尾、中间是 `…`，不是被尾部截断；系统字号调到最大仍不溢出。
- [ ] **条目页 ⋮ 菜单分块**（#301）：条目页顶栏 ⋮，应看到**三块各自圆角、彼此有明显间隙、各带一圈细描边**的浮块，浅色下三块要能一眼分出（不明显 ⇒ 描边或间隙还差）；深色下三块也不该显得过疏。
- [ ] **菜单信息完整**（#301）：第一块能看到库类型徽标（Bitwarden / KDBX 图标）+ 库名 + 条目数 + 来源；第二块仍是切换密码库 / 验证码 / 回收站 / 文件夹管理四项，一个不少；第三块状态灯 · 同步 · 锁定与改版前**完全一样**（位置、颜色、点击手感都不变）。
- [ ] **菜单宽度恒定**（#301）：在 Bitwarden 库与 KDBX 库之间来回切，浮层宽度不应跳变（应恒 280dp）。
- [ ] **退化形态**（#301）：库信息不可用时（首屏极短瞬间）菜单只剩两块，不该出现空白的第三块或错位。
- [ ] **写路径要先说话**（同批新增）：点保存后，日志里应立即出现下方两句之一，
      它比 A/B 更早、更直接地指出"改动到底有没有写进去"：
      ```
      C) VaultixSync: 写路径收口：updateItem 已返回（成功）· <itemId>
      D) VaultixSync: 写路径中断：updateItem 抛出 <异常类名> ⇒ 本地未落库、队列未入队
      ```
      **D ⇒ 写路径就没成功**（此前这类异常会直接炸 App 或静默吞掉，界面上毫无差别）；
      **C 而 A ⇒ 写进去了却没入队**（查 `flushAfterLocalWrite`）；
      **C 且 B ⇒ 完全走到推送**，剩下的就是服务端为什么拒收。
- [ ] **保存后不再卡死**：第一次保存若失败（如库未解锁），**再点一次保存必须仍能发出**，
      不能出现"点了没反应、什么都不发生"。对应 `saving` 闸门复位修复。
- [ ] **保存那一刻要看得到**：保存后应出现
      `本地写回完成并已推送：… 无待推送残留` 或
      `本地写回已落库，但推送失败（…），改动仍在队列等待重试`。
      这一行是"点保存时到底发生了什么"的直接证据，不必再用"本地能看到"去推断。
- [ ] **收尾统计对得上**：`flushPending 完成：N 条全部推送成功，队列已清空` 或
      `flushPending 完成：推送 X 条成功，剩 M 条留在队列`。
- [ ] **日志不泄密**：以上各行里不得出现条目 id、EncString 密文、JWT 或长 token。
- [ ] **不再谎报**：若出现 A/B 之外的失败，界面不能显示"刚刚同步成功"。

## 待真机验收（#296 · HTTP 422 失败处置）

> 本轮**没有**宣称 422 的根因已定位 —— 静态对照 + 本地起 1.30/1.33 实测都排除了
> 请求体结构，真正原因只能靠响应体。这一轮修的是「不再静默丢数据 + 能拿到证据」。
> 逐条见 [`07-数据与同步-part6.md` §296](../../.ai/issues/07-数据与同步-part6.md)。

- [ ] **关键：回传带 `body=` 的日志**：用新包复现一次，导出开发者日志，
      找 `VaultixSync: flushPending op=UPDATE ... code=422 ... body=...`。
      body 里的 `missing field ...` / `invalid type ...` 才是下一步修复的依据。
- [ ] **改动不再凭空消失**：即使 422 仍在，队列也应保留该 op，
      `adb logcat -s VaultixSync` 中不应再出现 `definitive=true` 的 422 行。
- [ ] **不再谎报同步成功**：有 422 时顶层结果必须是可重试错误，
      不能出现 `sync skipped: revision 未变` 之后界面显示"刚刚同步成功"。
- [ ] **「修改于」会更新**：推送成功后详情页时间应变成服务端新 revision，
      不再停在导入那一刻。
- [ ] **日志脱敏**：确认 `body=` 里没有 EncString 密文、JWT 或长 token。

## 待真机验收（#294 · Bitwarden 推送基线）

> 本轮改的是**推送成功后的本地收敛**，判据是「服务端真的变了」，静态检查替代不了。
> 逐条判据见 [`07-数据与同步-part4.md` §294.5 / §294.8](../../.ai/issues/07-数据与同步-part4.md)。

- [ ] **连续两次编辑**（关键）：同一条目改第一次 → 等几秒 → 再改第二次 →
      **两次都要**在网页端（Vaultwarden 的 Web UI）看到最新内容。
      改前必错的是第二次，这条直接对上根因。
- [ ] **网页端先改过的条目**：先在网页端改一次，再从 Vaultix 改并保存，
      服务端应出现 Vaultix 这次的内容（基线已跟上）。
- [ ] **不再出现静默弃单**：连续编辑若干条后，`adb logcat -s VaultixSync` 里
      **不应**出现 `definitive=true` 的 `flushPending op=UPDATE` 行。
- [ ] **重启后仍成立**：杀掉进程重开、解锁、再编辑一次，服务端仍能看到改动
      （基线是落在 Room 的列 + 载荷里，不是内存）。

## 待真机验收（#291 · release 行为）

> 本轮改的是 **release 行为与崩溃路径**，本地静态检查 / 类型检查不能替代真机。
> 逐条判据见 [`01-构建与环境-part14.md` §291.9](../../.ai/issues/01-构建与环境-part14.md)。

- [ ] DEBUG 包：快速解锁登记、解锁页多库补开、开关热区告警日志仍可读（门面没被改哑），
      且 logcat 里不再出现完整 `vaultId`。
- [ ] 通行密钥创建（选已有登录条目 / 新建条目两分支）与断言签名（浏览器 / 原生两流程）不崩。
- [ ] OneDrive / WebDAV KDBX 点同步正常；本地 SAF 库仍显示「本地文件」不假报「已同步」。
- [ ] **release 包** logcat 里搜不到 `VaultixQuickUnlock` / `VaultixUnlockVm` /
      `SettingsSwitch` / `VaultixAutofill` 的 DEBUG 行 —— **这条才是 S2 的真正验收**。

## 待真机验收（v0.13.0 · 历史分组，#290 主路径已闭环）

> ✅ **2026-11-14 用户真机确认**：「最新版已经不闪退了」⇒ `v0.13.0-dev-5553bda`
> 上 **#290 注销闪退主路径闭环**。下方 #290 细分项（提示文案 / 清单消失 / logcat）
> 未逐项回报，保留供补验；本轮判据以「不再闪退」为准。

- [x] **#290 注销主路径 · 不闪退**（2026-11-14 用户确认）
- [ ] **#290 提示文案**：弹「已删除该 WebDAV 账号的凭据」/「已注销该 Microsoft 账号」，
      账号从清单消失
- [ ] **#290 成功提示可见**：测试连接成功（#289 主路径）弹「连接成功，账号已保存」且不闪退
- [ ] **#290 连续 / 重复操作**：重复点注销、注销后立刻返回、注销后立刻再进页面都不闪退
- [ ] **#290 错误提示路径**：空字段 / 非法 URL / URL 带凭据时红字正常显示
      （`CloudError.Res` 同样走守卫），不闪退
- [ ] **#290 可取证**：logcat 不再出现 `Invalid resource ID 0x00000000` /
      `String resource ID #0x0`

- [ ] **#289 主路径**：生成应用密码 + 关微信二次验证后点「测试连接」，应显示
      「连接成功，账号已保存」且**不闪退**，账号出现在清单里
- [ ] **#289 成功之后立刻返回 / 停留**：两种都不闪退
- [ ] **#289 失败路径**：401 / 403 / 404 / 断网超时分别显示对应红字（沿用 #288 分流
      文案），不闪退，且失败后凭据确实被回滚
- [ ] **#289 添加页**：选账号 → 进子目录 → 返回上层 → 选 `.kdbx` → 输主密码提交，
      任一处故障显示错误态而不是闪退
- [ ] **#289 其余动作**：注销账号 / OneDrive 登录 / 换号 / 健康探测同样不闪退
- [ ] **#289 可取证**：异常时 logcat 能搜到 `CloudAccounts` / `AddCloudVault` 的 ERROR
      及原因（原本是进程直接消失、什么都没有）

- [ ] **#288 主路径**：坚果云网页端生成**应用密码** + 「安全选项」**关闭微信二次验证**后，
      `https://dav.jianguoyun.com/dav/` 点「测试连接」应显示「连接成功，账号已保存」
- [ ] **#288 仍失败时**：红字必须列出 403 的四条原因，界面**不再**说「账号或密码不正确」
- [ ] **#288 账号页 OK 后**，进「添加密码库 → 从网盘添加」能列出该目录下的文件
- [ ] **#288 故意填错密码**应报 401 且提示应用密码，与 403 文案明显不同
- [ ] **#288 地址填不存在的子目录**应提示「找不到该目录」，不假报连上
- [ ] **#288 同机 Vaultwarden + WebDAV**（同域名不同端口）：WebDAV 仍能连上，
      且 NAS 日志里不出现 `Bearer` / `Bitwarden-Client-*` 头
- [ ] **#288 自建 NAS**（群晖 / Nextcloud / Alist）：PROPFIND 与 HEAD 两种响应都要能过自检

- [ ] **#287 主路径「改完立刻返回」**：OneDrive KDBX 改一条 → 点保存 → **立刻按返回退出
      条目页** → 立刻点同步：改动必须被上传 / 或弹冲突，**不能**变回云端旧条目并显示「已同步」
- [ ] **#287 连续编辑**：改完立刻返回、再进再改，连改 3 笔后立刻点同步，三笔都在
- [ ] **#287 保存后立刻杀进程 / 立刻开飞行模式**：改动仍由 #286 补传链路上云；
      提示「已保存到本地」后断网，恢复网络后上传不丢
- [ ] **#287 冲突对话框「用云端覆盖本地」**：仍能真的拉下远端，不被新的不可取消写回自锁
- [ ] **#287 另七个写动作**：删除 / 新建 / 建文件夹 / 重命名 / 恢复 / 永久删除等
      走同一条 `serialWrite`，立刻返回也不丢
- [ ] **#287 自检失败**：人为损坏字节时脏缓存被恢复、会话回滚，条目仍在且不被当成待上传改动

- [ ] **#286 上传中途返回 / 断网后刷新**：OneDrive KDBX 改一条 → 上传中途返回或开
      飞行模式 → 点刷新：必须弹冲突（或明确提示本地有未上传改动），**不能**变回旧条目
      并显示「已同步」
- [ ] **#286 杀进程冷启动补传**：同上改为强杀进程后重开、暂不解锁 ⇒ 改动不被吞；
      解锁该库后应自动补传上云，**无需再点同步**
- [ ] **#286 「从不锁定」/ autofill 恢复会话**：会话被自动恢复时，脏缓存能被存量扫描
      或解锁订阅之一覆盖
- [ ] **#286 冲突对话框「用云端覆盖本地」**：仍能真的拉下远端（两道闸门被
      `discardLocalChanges = true` 跳过），不出现自锁
- [ ] **#286 下载窗口内再编辑**：`readRemoteCurrent()` 那 1–10 s 里再改一笔，
      会话与**缓存**都不被远端旧字节盖掉

- [ ] **#284 取消按钮**：主题模式 / 锁与安全 / 自动锁定 / 剪贴板 / 导入导出等所有「取消」在
      默认 / 2.0x 字号 / 窄屏下，宽度是否与同对话框的「确定 / 保存」及 `DialogShell` 家族一致
      （不再有 64dp 小方块）；长文案按钮是否随文案伸缩
- [ ] **#284 KDBX 通行密钥**：KDBX 库手工录入一条通行密钥后**关库重开**，凭证是否还在；
      系统 Credential Provider 在 RP 未给 `user.id` 时生成的兜底 userHandle 后续断言能否正常回传
- [ ] **#285 网盘账号卡**：浅 / 深 / OLED 纯黑下与设置页其它组卡是否同色阶；「已连接」
      徽标是否读作状态而非固有属性；切换账号 / 注销两枚按钮是否同高同规格
- [ ] **#285 域名分组**：长列表里是否读成「一节」而不是「一条条」；组间 / 组内 /
      头与首条三档间距在 2.0x 字号下是否仍成立
- [ ] **#285 详情页长标题**：`accounts.spotify.com` 等长域名是否单行缩字号 + 省略号、
      不再出现孤字行；库名顶栏长库名仍可折两行
- [ ] **#285 剪影卡配色**：浅色 / 深色 / OLED 纯黑 / 开动态取色四档下，「+」面板 5 张
      类型卡颜色是否有记忆点且不浑浊；文案与剪影线条对比度是否够
- [ ] **#285 主题 / 动态取色**：**卸载重装**（DataStore 无值）首帧是否为品牌浅色板而非
      壁纸色；Android 11 及以下动态取色开关是否禁用并显示说明，不出现「点了没反应」
- [ ] **#285 KDBX 顶栏标题**：KDBX 库条目页左上角显示 `KDBX`、列表状态行仍带
      `xxx.kdbx` 文件名

- [ ] **#283 深色底栏外围**：常态磨砂 / 液态玻璃**两档**在深色、OLED 纯黑、底栏后面正好
      是条目卡片时，还有没有「脏脏的」灰黑晕；白 rim 是否已从「刺眼白边」收敛成「受光」
      （`sheenScale 0.35` → 上沿 0.17 / rim 0.22）
- [ ] **#283 玻璃边缘厚度有没有被一起削薄**：本轮刻意只降**强度**、不降宽度 / 填充 /
      模糊半径 / 内阴影宽度。若觉得「玻璃没了」，请说明是边界还是中心，别只说「又变薄了」
- [ ] **#283 深色剪影卡**：深色 / 动态取色 / OLED 纯黑下五种类型的类型名与剪影线条
      是否清晰；五个色系分不分得开；卡底 `0.94` / 剪影底 `0.92` 是否过浓
- [ ] **#283 取消按钮**：主题模式 / 剪贴板 / 自动锁定 / 导入导出等所有「取消」在
      默认 / 2.0x 字号 / 窄屏下宽度与对齐是否像 M3 按钮；`DialogShell` 三按钮
      是否仍不换行错位（本轮刻意未动其 16dp padding）
- [ ] **#283 计数低调化**：站点名很长、列表滚动时，去掉胶囊后的 `onSurfaceVariant`
      小字还能不能一眼扫到条数；会不会反而找不着
- [ ] **#282 去细边线后的边界**：删掉两档共有的 1dp `style.stroke` 后，3dp 内阴影 + 镜面 rim
      能否单独立住玻璃轮廓；浅色背景下会不会反而读作「没有边」、深色 / OLED 纯黑下会不会
      与背后内容糊在一起。若仍看到一圈细线，先确认是不是 AGSL shader 内 `uLine` 的 1.5px
      暗线（仅 33+ 液态玻璃档），**别把两件事当成一件改**
- [ ] **#282 液态玻璃通透度**：填充降到 0.44/0.56、clarity 提到 0.13 后，是否达到用户要的
      「透明效果」而不再觉得「薄 / 整体效果不强」。⚠️ 这是 #281 回提与 #280 一路调淡之间的
      平衡点，反馈请给方向（更透 / 更实 / 保持）+ 大致档位，别只说「还不够」
- [ ] **#282 图标对比度**：底栏更透之后，常态与选中态图标在浅色 / 深色下是否仍清晰；
      行尾 `surfaceContainerHigh` 胶囊里四枚点缀（同步 / 收藏 / TOTP / 通行密钥）同时出现时
      是否拥挤、是否与站点图标抢视线
- [ ] **#282 动态取色下的剪影配色**：「+」面板三色系（primary / secondary / tertiary 的
      container 交叉）在开启动态取色、换壁纸后是否仍读作「活泼」而不是「花」；
      卡底 alpha 0.68 / 内层 0.62 是否过浓
- [ ] **#282 设置页对齐**：PIN 页删 5 处手写 Spacer 后，三按钮在**默认 / 2.0x 字号 / 窄屏**
      下是否齐平不换行错位；自动锁定精简文案有没有丢掉「离开 App 不锁定」这个关键警示
- [ ] **#281 双材质观感**：常态磨砂在浅色下是否偏灰、深色 / OLED 纯黑下「隐约透出内容」是否到位；
      液态玻璃回提后（填充 0.50 / 0.62、模糊 24dp、内阴影 0.26、rim 0.62、阴影 8dp）
      是否仍觉得「薄 / 整体效果不强」。⚠️ 直接给
      `/workspace/vaultix-dock-material-preview.html` 里四个滑杆的数值（或「再实一档」），
      下一轮按数值固化，不再靠猜
- [ ] **#281 性能**：默认态也开模糊后，滚动列表时整屏离屏拷贝的帧率（低端机 / API 31）；
      静止时不重绘故无拷贝。明显掉帧则考虑给磨砂单独开关或降半径，**不要退回实色**
- [ ] **#280 玻璃第六轮**：真机 AGSL 是否运行时编译通过、`uClarity` / 局部透镜幅度是否合适、
      **API 31–32 实机**点按是否真有流光与通透厚度、按住拖动是否跟手、亮 120 / 灭 360 余辉手感、
      以及按压期间帧率（SDF 梯度只应影响边缘带）
- [ ] 库卡**色调底 0.22** 会不会过浓；面板主卡水印 `alpha 0.13` 是否合适
- [ ] 启动 V 的**显眼程度**（已从 96dp 蓝方块改为单色剪影 + 180ms 淡入）
- [ ] 面板主卡水印与网格卡剪影是否读作**同一个系统**
- [ ] **2.0x 字号**下主卡 + 4 格 + 工具条是否一屏放得下
- [ ] **24dp 通知栏**里折纸 V 是否仍可辨认

---

## 待办

### P1 · 质量基础设施

> **2026-11-11 整体排查已核实**（[报告](audit/project-audit-2026-11-11.md) §3 / 附录 C）：
> Baseline Profile **确认缺失**（全仓零命中）、Gradle 配置缓存 **确认未启用**
> （CI 每条 gradle 命令都带 `--no-configuration-cache`）、Kover **只在根 `apply false`、未接 CI**。
> 旧注释「未逐项核对是否已完成」就此前三项**已作废**；ViewModel 单测仍待核。

> ⚠️ **2026-11-11 补**：任何整体排查 / 依赖升级 / 规范整改**开工前先读**
> [「不需要修」登记表](audit/evidence/no-fix-entries-2026-11-11.md)（52 条，判据见
> [no-fix-register](audit/no-fix-register-2026-11-11.md)）。产出的每条待修项先与登记 ID 比对，
> 命中即引用登记，不要重写理由、更不要重新列成待办（有 `check_no_fix_register.py` 门禁）。

- [ ] **依赖解析结果纳入 CI 门禁**（最高优先，先于任何升级）：施工单见报告附录 C · S1
- [x] **生产 3 处 `!!` 清零**（P1，报告 §2.2 / §4 · S3）· ✅ **#291 已修（2026-11-14）**：
      三处全改「取局部快照 + 空值有去向」，不用 `requireNotNull`；`check_production_safety.py`
      已把生产 `!!` 归零钉成 CI 红线，[part14 §291.4](../../.ai/issues/01-构建与环境-part14.md)
- [x] **release 下 6 处绕过 `VaultixLog` 的直接 `Log` 收敛**（P1，报告 §2.2 / §6 订正）·
      ✅ **#291 已修（2026-11-14）**：实收敛 **8 处**（含 `AutofillLogger` 2 处半合规），
      `vaultId` 经 `redact()` 脱敏；白名单只剩 `VaultixApplication.kt` 门面 sink 一处。
      ⚠️ 判据：`BuildConfig.DEBUG` 由 **buildType** 决定、不由分支决定，
      R8 只裁 `if (BuildConfig.DEBUG) { … }` 整块 ⇒「源码一致」≠「产物行为一致」，
      [part14 §291.2](../../.ai/issues/01-构建与环境-part14.md)
- [ ] Baseline Profile：生成 `baseline-prof.txt`，覆盖冷启动 / 解锁 / 列表 / 详情 + autofill
- [ ] Gradle 配置缓存：先验 `detekt --configuration-cache`，可用再开
- [ ] Kover 覆盖率基线接入 CI（阈值先取实测值，不一步设 80%）
- [ ] `ViewModel 单测` / `:app` 单测用例补强 —— **本轮未核对，保留待核**

### P2 · 已判推迟（不是待办，勿重新排期）

> **WorkManager 周期同步 + 网络约束 —— 已判推迟，收益≈0**
> （[`decisions.md`](decisions.md)「周期同步 M1 判推迟」；登记为 [C01](audit/evidence/no-fix-entries-2026-11-11.md)）：
> 进程存活时 APP_RESUME / PAGE_ENTER 已覆盖「打开即最新」；进程被杀后会话密钥在内存
> （重启必锁），周期任务无可同步内容。编排器 PERIODIC 已预留，
> **触发转修条件**：引入本地 KDBX 离线写队列（有可同步内容），或用户要求后台自动同步。
> `Docs/17` §3.3：禁止常驻轮询。

### 已知未决（接力者注意）

- 快速解锁 payload 在 `SecureCredentialStore`（key `local_unlock_key::<vaultId>`），
  开关在 DataStore；删除 KEK 仅由系统指纹变更触发（单库 disable 只删 payload + 开关，
  多库时 KEK 共享保留 —— 未来多库需「全库清空」入口）。
- 同一服务器仅一个账号（`vaultId = server`）；设备 id 存 `SecureCredentialStore`
  （卸载即换，服务器端会累积旧设备记录，属正常）。
- Detekt 用 `2.0.0-alpha.6`；2.0 稳定后升级需重新生成默认配置核对。

---

## 上游参考候选（2026-11 调研 · **已拍板搁置**，勿当成待办）

> 🔒 **2026-11-14 用户拍板**：「目前的上游改动对我意义不大，先搁置」⇒ 下表**全部不排期**，
> 仅作存档。发版见 `v0.13.1`；本轮未改生产代码、未新增测试。
>
> ✅ **发布结论（真实 run 结果）**：`Android Stable Release` run `43542269744`
> `completed/success`（约 8m）；`Vaultix v0.13.1` 已置 **Latest**，
> 资产 `Vaultix-v0.13.1.apk`（6.6 MB，混淆）+ `checksums-sha256.txt`，
> tag `v0.13.1` → `dd2e845b`，`rele` 与 `main` 齐平（0/0）。发布说明读手写版，
> 签名 / 校验和 / attestation 齐全；详见 `.ai/README.md` 顶部「最新状态」。
>
> 📅 **月度节奏（文档约定，不建定时任务）**：**每月排查一次上游重大更新** ——
> 只筛 major 版本 / 安全公告 / 互操作性硬 bug（如 KDBX 字段生态变化），出一份简报、
> 不动代码；到点由用户提醒执行。**不建 GitHub Actions 定时 workflow、不建自动化任务**，
> 避免为了「自动」引入一套没人读的告警（同 `codeql.yml` 移除 push 触发的判据）。
> 真源是三家官方 release：`bitwarden/android`（移动真源，非已归档的 `bitwarden/mobile`）、
> `Kunzisoft/KeePassDX`、`PhilippC/keepass2android`。
>
> 2026-11-14 按**真机画像**重筛：`HONOR BKQ-AN00 / Android 17 (SDK 37)`、
> OneDrive + 坚果云 WebDAV 上的 KDBX、#285–#290 六轮反馈全部集中在
> 「网盘链路 + 错误 / 崩溃可见性」，零次提及功能不足。
> 全量调研见 `vaultix-upstream-research-2026-11.html`（A 级 5 / B 级 7 / C 级 5），
> 按实机筛选后的决策见 `vaultix-upstream-targeted-2026-11.html`。
> **本节只是登记，未动生产代码、未新增测试、未改版本号**；施工需另起一轮走完整链路。

| 优先级 | 候选 | 落点 / 约束 |
|---|---|---|
| 先做 | **A1** KDBX 通行密钥 BE/BS 改 `0/1` | `KdbxPasskeyCodec.kt:193-194` 仍写 `true/false`；KP2A 1.16-pre1 已定为 `0/1`。写侧改、读侧双兼容、补跨工具往返测试。唯一确认的互操作性硬 bug，改动 2 行 |
| 先做 | **A2** 远端被第三方改动 → 可见且区分成因 | `resolveUsingRemote()` 已有 `stat().versionToken` 与两道脏闸门、`KdbxConflictDialog` 已存在 ⇒ 缺口比全量报告写的窄：只需把文案拆成「别处改过 / 本地未上传 / 两边都改」三种成因，不引入新状态机 |
| 先做 | **B7** 重复 UUID 写回门禁 | `KdbxItemWriter` 补「写前扫重复 UUID 并拒绝 / 重生成」测试，与 #286 同族，成本极低 |
| 待拍板 | **NEW** 崩溃可取证 | #289/#290 连续两轮靠用户手动导日志；全仓无全局异常兜底实装、`VaultixLog` 无文件 sink，`DeveloperLogsDialog` 只能手动抓 logcat。⚠️ **别撞 D07**（不改门面开关、不让 release 打 DEBUG 日志），须先确认是否走 S6 脱敏诊断包路径 |
| 回归 | **A2 做深 / A4′ / A5′** | 主动版本基线比对若碰 `KdbxCloudSyncCoordinator`，必须完整回归 #286/#287 三组真机项；A4′ 只补 TOTP 越权行为测试（**C05 已否掉详情页动态验证码，不得顺带做 next-code 预览**）；A5′ 只留「编辑后 autofill 上下文」「`allowCredentials` 归一化」两条自查，**Android 10 inline 崩溃在 SDK 37 真机不触发，降为发版前兼容自查** |
| 仅登记 | B1 CXP 接口预留 / B3 KDF 1 秒标定 / B4 PRF 预研 / B5 规则数据化 | 依赖 GMS、密码学改造或主线程风险（#289 刚修过主线程 IO），等实测数据再议 |
| 不采纳 | B2 新条目类型 / B6 Tags / C 级全线 | 六轮实机反馈零次提及；#284 已证「存了读不回来」是新类型典型代价，#282/#285 分组判据刚反复调过，叠第二套维度是自找回归 |
| 不采纳 | **A3** 周期后台拉取 | **已判推迟，见 C01 登记，不重列、不重新表述**：进程被杀后会话密钥只在内存、重启必锁 ⇒ 无可同步内容；且 `decisions.md:46` 记录用户真机反馈「自动同步太频繁」后已移除进页 / 回前台自动拉取。KP2A 有离线写队列而 Vaultix 无此前提；#286 的 `pendingLocalEdits` 让触发条件部分逼近，但补传仍受 `sessionReady = Kdbx::isUnlocked` 约束，锁态依旧无可同步内容 |

> ⚠️ 上游 2026 年最密集的更新是「同步完整性 / 写回持久性 / 错误可见性」，
> 与 #286–#290 同一组题。**Vaultix 在双真源 + 不可取消写回 + 写后读回校验上已比两家上游更严谨**，
> 不需要推倒重来；要补的是把已做对的事情变得**可见、可取证**。

---

## 文档债（2026-11-10 盘点）

- [ ] ⚠️ **`Docs/progress/current-status.md` 文末停在 2026-11-01（#218）**：#280 / #281 /
      #282 都没补，但 `.ai/MEMORY.md` 的「接力顺序」仍把它列为第四份必读 ⇒ 接力者可能把
      11-01 的快照当成现状（连 `SESSION-2026-11-11/12.md` 也已不再逐日写，逐轮细节改由
      `.ai/issues/06-界面与交互-partN.md` 承载）。**现状一律以
      `.ai/README.md` 顶部「🕐 最新状态」为准**；后续要么按 part 分篇摘要重写其文末，
      要么把它从「必读真源」降为「历史归档」，别让它半真半旧地挂着。
- [x] **本文件拆分**：3592 行 / 280 KB → 入口 + 6 份归档（本轮完成）
- [ ] 6 个 `SESSION-*.md` 超 300 行（最大 **768 行** = `SESSION-2026-11-03.md`）。
      ⚠️ 记忆明确「存量未回溯合并 ⇒ **不能盲删**」，拆分要逐段回溯，是独立工程。
- [ ] `Docs/` 根目录 19 个设计期文档已滞后于实现（`08-界面设计规格.md` 33 KB）——
      README 已有已知标记；若要处理，应先定「哪几份还算真源」。

---

## 历史归档（按时间倒序，**勿通读**）

| # | 时间段 | 文件 | 规模 |
|---|---|---|---|
| 1 | 2026-11-05 ~ 10-22 | [next-steps-archive-1.md](./next-steps-archive-1.md) | 574 行 |
| 2 | 2026-10-22 ~ 09-29 | [next-steps-archive-2.md](./next-steps-archive-2.md) | 574 行 |
| 3 | 2026-09-29 ~ 09-15 | [next-steps-archive-3.md](./next-steps-archive-3.md) | 567 行 |
| 4 | 2026-09-15 ~ 09-12 | [next-steps-archive-4.md](./next-steps-archive-4.md) | 612 行 |
| 5 | 2026-09-12 ~ 09-11 | [next-steps-archive-5.md](./next-steps-archive-5.md) | 569 行 |
| 6 | 2026-09-11 ~ 09-08 | [next-steps-archive-6.md](./next-steps-archive-6.md) | 565 行 |

> ⚠️ 归档每份 565–612 行，**超 §7.1 的 300 行上限**——这是刻意的：它们是
> 「按时间段完整的完成记录」，对半切会破坏叙事连续性；入口已声明「勿通读」。
> 若确实需要严格 300 行，切点都在 `##` 边界，可直接对半拆。

### 已过期的旧待办（仅留档，原文见 archive-6 尾部）

- ~~`P0 · M1 收尾（剩余）`~~ —— 真机回归清单（装 `f0f5df6` preview）。M1 早已完成。
- ~~`P3 · M2（KDBX）`~~ —— `data:kdbx` 引擎（kotpass）。KDBX 已实现。
- ~~`P2 · 自动化`~~ —— 与上方 WorkManager 那条重复，已合并。
