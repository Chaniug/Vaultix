# 02-自动填充 · 第 3 篇（#255 ~ #308）

> 主篇索引：[`02-自动填充.md`](./02-自动填充.md)（含计数规则与重号警告）
> 本篇 4 章 · 覆盖 #255（填充面板的**观感层**：行卡片、品牌头、宽高取舍）、
> #306（夸克浏览器兼容名单：XML compatibility-package + Kotlin 已知浏览器集合）、
> #307（夸克无法填充：UC 内核系统级限制，Bitwarden 亦无解，已结案非缺陷）
> 与 #308（FillResponse / Dataset 组装切回主线程，图标 I/O 留后台）

## 255. 面板「一坨黑块」：行背景资源**存在但从未接线**，图标还叠着两层圆底（2026-11-09）

**现象**：用户发截图问「这个密码列表还能做的好看一点吗」。深色面板上是**一整块纯黑方角**，
三行「QQ / 手机号」直接糊在黑底上，行与行之间没有任何边界。

**先厘清一件事：外框不归我们管。** 那个弹窗是系统 `FillUi` 的 `PopupWindow`，
背景 / 圆角 / 阴影 / 宽度 / 行分隔 / 按压高亮**全部由系统或 ROM 决定**。
同一份 `autofill_dataset_item.xml` 在 Pixel 上是浅色圆角卡片，在 OPPO/一加系 ROM 上是
纯黑方角。**所以"外框丑"不能当成"行内没做好"来修** —— 这条区分是本节最值钱的地方。

**根因（行内真的有两个缺陷）**：
1. **`autofill_item_frame.xml`（12dp 圆角行背景）全仓库零引用。**
   2026-09-13 建这个资源是为了"把描边改成实底面"，但 `autofill_dataset_item.xml` 的
   根布局从来没设过 `android:background` ⇒ 资源建好了、**没接线**，一年没人发现。
2. **图标双重圆底**：`letterAvatar()` 自己 `drawCircle(半径=size/2)` 画了整块彩圆，
   外层 `FrameLayout` 又套了一层 32dp 的 `autofill_item_icon_bg`（夜间 `#49454F`）
   ⇒ 深色面板上一圈"脏边"，浅色面板上一圈灰环。
3. 顺带发现：站点图标 `circular()` 垫的是**固定 `Color.WHITE`**，深色面板上一枚
   "白圆 + 浅色 logo"是整屏最亮的东西，和旁边自绘彩圆的字母头像完全不是一套语言。

**修法**：
- 行布局外层加一层**透明 wrapper**（4dp 水平 / 2dp 垂直 padding），内层才是圆角卡片。
  ⚠️ 行距用 **padding** 而不是 `layout_margin`：RemoteViews 塞进系统列表后 root 的
  margin 会被 `AbsListView.LayoutParams` 吃掉，**不可靠**。
  **总高仍是 52dp**（48 卡片 + 上下各 2dp）—— 上一轮刚因"偏大"缩过一次，
  本轮只改"层次"、不改"密度"。
- 去掉 `FrameLayout` 的容器底；`ICON_BACKDROP` 按 uiMode 分亮暗两档（暗 `#49454F`）。
- 副标题加 `android:fontFeatureSettings="'tnum'"`：账号多是手机号，逐行数字等宽对齐。
  ⚠️ 值必须写成 **CSS 语法带单引号**；裸写 `tnum` 不报错也不生效，静默什么都不做。
- `contentDescription` 从卡片挪到 wrapper：描述要覆盖整行（含新的行距），
  否则读屏聚焦到 padding 区没有文字可读。

**同时加了品牌头**（`FillResponse.Builder.setHeader` + `layout/autofill_header.xml`）：
面板里每行都是「图标 + 两行字」的通用造型，十几家密码管理器长得一模一样，
没有头用户分不清"这是 Vaultix 给的还是输入法自带的"。
- ⚠️ header 的 RemoteViews **不可点击**（文档明确 dead）⇒ 做不了「管理 / 打开 App」。
  想给入口只能另加 dataset，而 dataset 一点就会尝试填充 —— 会往密码框里填脏值，不做。
- ⚠️ **不能用 `Space` 做弹性占位**：它不在 RemoteViews 的 View 白名单里，
  会直接 `InflateException: Class not allowed`。靠右对齐只能给标题设 `weight=1`。
- ⚠️ 整个 `setHeader` 包在 `runCatching` 里：头是锦上添花，**绝不能因为它让整次填充失败**。

**判据（三条）**：
1. **"这个资源存在" ≠ "这个资源在用"。** 改观感前先 `grep` 引用数 ——
   `autofill_item_frame` 就是"建好了忘接线"的标本，静态门禁（含 detekt）全查不出：
   drawable 有引用计数、但**没有任何工具检查"该用的地方用了吗"**。
2. **给图标垫底是一次性的。** 位图自己已经是完整圆（字母头像画满、站点图标 `circular` 裁圆），
   再托一层容器只会得到脏边。加"底板"之前先确认**里面那层有没有底**。
3. **面板外框改不了，别把系统的问题当自己的问题。** 用户说"丑"时，先分清
   「系统/ROM 画的」与「我们画的」，否则会去调字号、行高、间距 —— 全是白费
   （前两轮已经这么白费过两次）。

**验证**：CI `42552802039`；真机待验 ① 深色 / 浅色面板各看一次，行卡片边界清楚、
图标无灰环；② 三行以上时缝隙均匀，最后一行不被裁；③ 品牌头在国产 ROM 上确实出现
（部分 ROM 会忽略 `setHeader`，属预期，不报错）；④ 按压时整行变亮（系统高亮是方角，
与圆角卡片共存，属预期）。

**第二轮（同日真机反馈）：「宽度稍微差一点，显得有点窄；文案和图标占了一点空间」**

- **先确认一条硬约束**：**面板宽度改不了**。`FillUi.DropdownPopup` 用 `wrap_content`
  包一层，宽度由**最宽的那一行**决定 —— 把头做多宽都撑不开那个弹窗。
  所以"显得窄"只能从**纵向**（多露一行）和**行内横向**（占位让给文字）两头补。
- **品牌头压成一行内联**：总高 30 → **24dp**（上 8→6 / 下 4→2），盾牌 14 → **12dp**，
  间距 6 → **4dp**，文案 12 → **11sp**、条数 11 → **10sp**，两者都加
  `includeFontPadding="false"`（小字号下默认 font padding 白占 2~3dp）。
  ⚠️ 别压到 20dp 以下：11sp 中文会贴边、部分 ROM 直接裁掉。
- **行内四刀合计让出 12dp**（240dp 面板上约 5%）：卡片内边距 10 → **8**、
  图标 26 → **24**（`ICON_DP` 必须同步，否则降采样发虚）、图标到文字 10 → **8**。
  文字起始位置左移 6dp，右侧多容纳约 1.5 个数字字符。
- **纵向尺寸一律不动**（48dp 卡片 / 2dp 行距 / 15sp + 13sp）：那几项是 09-16
  「偏大」那轮定的，这轮反馈的是"横向"，**不顺手一起改**。

**追加判据（第四条）**：**用户说"窄"时，先分清是"容器窄"还是"内容挤"。**
这轮两个都有 —— 容器那半（面板宽度）改不了，只能明说改不了并换别的补偿；
内容那半（行内占位）才是能动的地方。把两者混在一起调，就会去改容器宽度、
改字号、改行高，全是白费。

---

## 306. 夸克浏览器不弹填充：两份兼容名单都漏了 `com.quark.browser`（2026-11-24）

**现象**：用户问「目前的项目好像在夸克浏览器上无法弹出填充，是正常的吗」。此前对 Edge /
Chrome / Via / UC 都做过兼容名单，唯独夸克没有。

**根因（不是"内核无解"，是 Vaultix 自己两份名单漏了包名）**：

1. `app/src/main/res/xml/autofill_service.xml` 没有夸克的 `compatibility-package`。
   Chromium 系浏览器只有被声明为兼容填充提供方，系统才会把地址栏 `webDomain` 上报给
   AutofillService。缺这条 ⇒ 请求里的 `webDomain` 恒为 null。
2. `AutofillRequestContextPolicy.KNOWN_BROWSER_PACKAGES` 没有 `"com.quark.browser"`。
   `AutofillCandidateSource.matchLogins()` 的降级逻辑是"只有已知浏览器、且拿不到域名时
   才列全库候选"（Bitwarden 的"少给"策略，见本分类收紧史）。夸克不在名单 ⇒
   连降级都被拒 ⇒ 候选为空。

**两处的对应关系**：缺 ① 会导致拿不到域名；缺 ② 会导致即使拿不到域名也不给候选。
任一缺失，用户看到的都是"完全不弹"。所以必须两处一起补，只补一处仍然不弹。

**修法（小到只有两行）**：
- XML 单独分组加 `<compatibility-package android:name="com.quark.browser"
  android:maxLongVersionCode="@string/version_code_max" />`。
- Kotlin 在 UC 系包名后加 `"com.quark.browser",`（同属阿里系，语义相邻）。

**边界（明确不做什么）**：夸克是定制 Chromium，是否真的上报 `webDomain` 由它的内核决定，
Vaultix 不 hook、不逆向、不强改系统填充框架。补齐名单后，最差情况也能走无域名降级
"能弹能选"；能否精准匹配到具体站点的条目，取决于夸克是否上报域名 —— 这条留给真机验证。

**门禁（为什么单测拦不住）**：编译 / detekt / 单测都看不出"集合里少一个字符串"。
新增 `.ai/tools/check_quark_autofill_compat.py`，判据剥 XML `<!-- -->` 与 Kotlin
`//` `/* */` 注释后，分别要求完整元素标签与 setOf 体内的精确条目 —— 防止"只在注释里
写过包名"造成假绿。4 段变异（XML 删元素 / XML 只写注释 / Kotlin 删条目 / Kotlin
只写注释）全部被抓到；旧实现两条错误都红，补齐后转绿。

---

## 307. 夸克浏览器无法填充：UC 内核系统级限制，非缺陷（2026-11-25 ~ 26，已结案）

> ✅ **状态：已结案——夸克 UC 内核边界，非 Vaultix 缺陷，不改代码**。
> 定案依据（三条闭环）：① Edge 同一站点全链路通（域名/字段/匹配/dataset/passkey 全正常）；
> ② 夸克登录页 `onFillRequest` 从未被调用；③ **用户实测 Bitwarden 在夸克上同样无法填充**。
> 竞品对照是决定性证据——行业标杆也过不去，证明不是 Vaultix 实现问题。
> 曲折：曾 00:39 误判"点错位置"（被否认），09:42 发现"请求未到入口"。教训：**竞品表现/
> 用户操作这类无法从元数据反推的事实，先问清楚 + 找对照组，别凭半份日志下结论**。
> 全程未改任何分类策略，只补诊断。

**地址栏误测记录（2026-11-26 00:37，装 `c4c027a` 后抓）——这段确属点错位置**：
```
probe cls=android.widget.EditText inputType=0x1 a11yType=1 tag=- attrs=[-] id=bk_search_edittext viewHint=搜索历史 signal=搜索历史 bk_search_edittext
probe cls=android.widget.EditText inputType=0x1 ... id=bk_search_edittext viewHint=搜索 signal=搜索 bk_search_edittext
probe cls=android.widget.EditText inputType=0x20001(131073) ... id=- viewHint=搜索、提问或输入网址 signal=搜索、提问或输入网址
fillRequest pkg=com.quark.browser webDomain=null webView=false fields=3 hints={UNKNOWN=3} targets=0
noFillTarget → 不响应（非凭据字段，避免误弹）
```
- `id=bk_search_edittext` + `viewHint=搜索/搜索历史/搜索、提问或输入网址` ⇒ **地址栏/首页搜索框**。
- `inputType=0x20001` = TYPE_CLASS_TEXT | TYPE_TEXT_VARIATION_URI（网址），非密码变体（应为 0x81/0x91）。
- `signal` 命中 `IGNORED_TERMS` 的「搜索」⇒ 判 UNKNOWN 是**有意**（同京东搜索栏误弹的防误弹闸）。
- 所以这里不弹密码候选**完全正确**；若强行位置兜底赋账号/密码，反而会在地址栏误弹 → 回归。

**真机验收欠账（转用户）**：在夸克里真正打开网站登录页、点页内用户名/密码框（非顶部地址栏）再抓日志。
若那时出现 `inputType=0x81/0x91` 或 `signal` 含密码/账号语义 ⇒ 分类链路正常，看匹配/候选；
若那时仍全 UNKNOWN ⇒ 才回到下方 (a)/(b) 两档评估。**在此之前不动分类器**。

**现象**：#306 两处名单已补、用户装的是含 #306 的 preview（`0.13.10-dev-7df7680`），
夸克（com.quark.browser）点密码框仍"完全没反应"。对照 Chrome/Edge 正常、Vaultix 已设为
默认自动填充服务 ⇒ 服务本身没问题，问题在夸克这一条链路的更前面。

**用户导出日志（荣耀 BKQ-AN00 / Android 17）关键三行**：
```
fillRequest pkg=com.hihonor.appmarket ... fields=1 hints={UNKNOWN=1} targets=0 ...
fillRequest pkg=com.quark.browser webDomain=null fallback=null webView=false fields=2 hints={UNKNOWN=2} targets=0 user=false pass=false seq=UNKNOWN,UNKNOWN
noFillTarget → 不响应（非凭据字段，避免误弹）
```
（另有两次 `fields=0` 的空请求，是焦点切换时的噪声，非主因。）

**三层事实（已确认）**：
1. **请求到了我们**：有 `fillRequest` ⇒ 系统正常派单，`autofill_service.xml` 声明 /
   服务绑定 / 默认服务设置都正常。**不是 #306 名单问题，也不是"没请求"那一档**。
2. **字段进了解析表但没认出语义**：`fields=2 hints={UNKNOWN=2}` ⇒ 通过了
   `isEditableNode` 准入闸，但 `HintClassifier.classify(hints, inputType, formSignal)`
   三个信号全落空 → UNKNOWN。
3. **`targets=0` 触发有意静默**：`buildResponse` 在 `allFillableIds` 为空时
   `callback.onSuccess(null)`（对齐 Bitwarden `Unfillable`），**这是设计行为，不是 bug** ——
   bug 在上游"为什么没认出字段"。

**为什么日志还不够（本次改动）**：现有诊断只打**分类结果**（`seq=UNKNOWN,UNKNOWN`），
看不到**原始控件信号**。分不清两种可能，修法相反：
- (a) 夸克 UC 内核给了语义，但在 `htmlInfo.attributes` 的非标准键 / 值里，被
  `BrowserUrlBars.HTML_SIGNAL_KEY_TERMS` 白名单漏掉 → 补白名单即可，低风险；
- (b) 内核压根没给 hint / inputType / autocomplete → 只能靠"用户点了哪个框 + 字段顺序"
  做兜底推断，有误填风险，需单独评估。

**本次落地**：新增 `UnknownFieldDiagnostics`（纯函数，单测 6 例）在 `AssistStructureParser`
对 UNKNOWN 字段打一行 `probe cls=… inputType=0x…(…) a11yType=… tag=… attrs=[…] id=… viewHint=… cd=…`。
**安全红线**：签名里根本没有 text 参数，编译期杜绝输入值入日志；每个字段限长 80 字符 + 去换行。
TDD：6 绿 → 去掉截断变异 1 红 → 还原 6 绿。

**下一步（等用户再抓一次日志）**：看 `probe` 行的 `attrs=[…]` / `inputType` / `cls`：
- 有 `type=password` / `autocomplete=current-password` 之类 → 走 (a)，扩白名单 + 门禁；
- 全空且 inputType=0 → 走 (b)，先评估误填风险再决定做不做。

### 307-b：登录页 `fields=0` + 结构级诊断（2026-11-26 09:23）

用户明确：11-26 00:46 抓日志时点的是**网站登录页密码框**，非地址栏。日志关键形态：
```
00:46:08.090 fillRequest pkg=com.quark.browser webDomain=null fallback=null webView=false fields=0 hints={} targets=0
00:46:09.584 fillRequest pkg=com.quark.browser webDomain=null fallback=null webView=false fields=0 hints={} targets=0
```
`fields=0` 且**一条 probe 都没有**——说明没有任何节点通过 `isEditableNode` 准入闸。
现有 `UnknownFieldDiagnostics` 只覆盖"进表但 UNKNOWN"，对三种可能全盲：
① 树里无输入框；② 有 autofillId + TEXT 节点但 className/htmlTag 非标准被准入闸拒；③ 规则覆盖拒绝。

**落地**：新增 `parser/StructureDiagnostics`（纯函数，8 例单测）输出
`structSummary windows/nodes/withId/textFillable/focused` + `structNode cls/a11yType/inputType/
tag/id/hints/focus/children`（带 autofillId / focused / 可疑类名才采，上限 16）。
`AssistStructureParser.parse` 在 `fields.isEmpty()` 时单独遍历整树输出。安全红线同前。
TDD：8 绿 → 摘要字段变异 1 红 → 还原 8 绿；门禁 27/27、生产安全红线通过。

### 307-c：结案——Bitwarden 对照 + Edge 全链路（2026-11-26 09:55）

新 preview（`2476b17`，含结构诊断）抓日志出现**第三次形态**：09:42 那份零 `VaultixAutofill:`
行、`structSummary` 也不出现 ⇒ `onFillRequest` 根本没被调用。**入口没进来，入口内的诊断
（`UnknownFieldDiagnostics` / `StructureDiagnostics`）全部白加**——停止"加诊断→发版→抓日志"循环。

用户随后提供决定性对照：
- **Edge** 同一站点：`fillRequest pkg=com.microsoft.emmx webDomain=github.com webView=true
  fields=2 hints={USERNAME=1,PASSWORD=…} targets=2 user=true pass=true` →
  `fillResponse candidates=212 matched=1 datasets=1` → CP GET / passkey assertion 全完成。
- **Bitwarden 在夸克上同样无法填充**（用户实测）。

三条闭环 ⇒ **夸克 UC 自绘内核不向系统 AutofillService 上报可填字段/不发起 fill request，
行业标杆亦无解，属内核边界，非 Vaultix 缺陷，结案不改代码**。不上无障碍兜底 / 内核逆向。
诊断日志（仅 debug、无 text、限长）保留作长期取证工具。

**顺带记录待办（独立 issue，不在 #307 改）**：Edge 日志有
`AutofillValue: forText() not called on main thread: DefaultDispatcher-worker`，
`AutofillDatasets.build()` L168 的 `AutofillValue.forText()` 在 IO 线程构建，违反框架主线程
约定。Edge 上未致故障，属潜在稳定性隐患，后续单独评估。

只推 `main`，不合 `rele`。

---

## 308. 填充响应组装切回主线程：图标 I/O 留后台，Parcelable 归 Main（2026-11-26）

**现象**：#307 的 Edge 全链路日志里夹一条系统警告
`AutofillValue: forText() not called on main thread: Thread[DefaultDispatcher-worker-7,5,main]`。
Edge 上填充、passkey 全部正常，**功能未坏**。

**根因**：`VaultixAutofillService.scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)`，
`onFillRequest` 里的 `buildResponse()` 整条链跑在 Default。而
`AutofillValue.forText()` / `Dataset.Builder` / `FillResponse.Builder` 是 framework 的
Parcelable 组装，**契约要求在主线程构造**。当前只在 Edge 日志里告警，国产 OEM ROM 上对
RemoteViews / Parcelable 的实现差异更大，有偶发填充 UI 异常风险——属潜在稳定性隐患，值得修。

**修法（只改线程归属，不改任何填充语义）**：
1. `AutofillDatasets` 新增 `IconSource` 密封接口（`FromUrl` / `Ready` / `None`），
   把互斥的「现算」与「预生成位图」合成一个参数——`build()` 参数数因此守住 detekt ≤8 上限。
2. `AutofillDatasetFactory.prepareIcon()` 在**后台**预生成位图（Coil 磁盘缓存读，属 I/O），
   `datasetFor()` 新增可选 `icon` 参数；传 null 时保持旧的 `FromUrl` 现算行为。
3. `VaultixAutofillService.buildResponse()` 先把 `suggestions` 的图标全算好，再
   `withContext(Dispatchers.Main)` 里做纯对象组装（dataset / addDataset / header / build）。
   locked 与 noMatch 两条 `buildFallback()` 同样切主线程；`callback.onSuccess` 本就已在 Main。
4. `totpTickets = totpTickets` 原样透传——**验证码复制路径一个字没动**（曾误写 null，已纠正）。

**为什么不会影响现在的填充（回应真机风险疑问）**：候选匹配、字段映射、认证意图、
SaveInfo、TOTP 票据、header 全部沿用原实现，只是把「在哪里 new 这些对象」从 Default 挪到 Main；
图标事先算好，主线程零磁盘 I/O；三条分支（正常 / 锁定 / 无匹配）都覆盖，不存在漏网路径。

**门禁**：新增 `.ai/tools/check_autofill_main_thread.py`（剥注释）三条判据——
A 图标 I/O 不得落进 Main、B `forText` 调用点存在且组装在 Main、C Service 有 `datasetFor` 组装；
4 组变异自测全红（组装移出 Main / I/O 移进 Main / forText 消失 / 合规绿）。
**本轮补接 CI 两步**（`#308` 门禁 + 自测）——脚本写完却漏接 CI 等于假绿，与 #305/#306 同训。

提交 `91b518ea`（Android CI `45741019593` 全绿、preview `Vaultix-91b518e.apk` 已挂），
只推 `main`，不合 `rele`。

**真机验收通过（2026-11-26 10:55，用户 HONOR BKQ-AN00 / Android 17）**：装
`0.13.10-dev-91b518e` 后 Edge 填 GitHub 登录页，导出日志
`vaultix_logs_20261126_105544.txt`——① `fillRequest fields=2 targets=2` →
`fillResponse matched=1 datasets=1`，填充照常；② 全文零 `forText() not called on main thread`
警告（旧包 `2476b17` 同场景有两条）。**#308 结案**。

> 插曲：用户先发的 `vaultix_logs_20261126_095544.txt`（导出 09:55、`versionName=2476b17`）
> 是旧包日志，比 preview 构建时间（10:18）还早 23min，物理上不可能含修复。文件名时间戳 +
> 日志内 `versionName` + 构建时间三者核对才定位到「发错附件」，不是修复无效。教训延续 #307：
> **先验证据本身的有效性（版本/时间），再读证据内容**。
