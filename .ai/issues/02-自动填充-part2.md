# 02-自动填充 · 第 2 篇（#72 ~ #187）

> 主篇索引：[`02-自动填充.md`](./02-自动填充.md)（含计数规则与重号警告）
> 本篇 7 章 · 16.4KB · 覆盖 #72 ~ #187

## 72. 填充下拉由系统用 RemoteViews 渲染 —— 能力边界与网络禁令（2026-09-13）

**现象**：填充时弹出的条目框偏大、不精致，且**没有站点 logo**（全是统一图标）。

**根因**：这个下拉**不是 Compose 画的**，是系统按 `RemoteViews` 渲染的 ——
MaterialCardView 的完整阴影、水波纹、非系统字体、动态取色**一概不支持**；
此前行高 56dp + 外层圆角底框 + 20dp 小图标，视觉上又松又钝。
图标缺失则是因为 presentation 只肯接受本地 `Bitmap`，而站点图标是网络资源。

**解法**：
- 行高 56 → **48dp**、去掉外层圆角底框、图标 20 → **24dp**（`centerCrop`）、
  标题 15 → 14sp、副标题 13 → 12sp（对齐 Bitwarden 填充下拉的紧凑观感）。
- 新增 `AutofillItemIcon`：**先查 Coil 磁盘缓存**（`imageLoader.diskCache?.openSnapshot(url)`）
  取站点图标并圆形裁切；未命中则按标题哈希在 8 色里取一色**本地绘制字母头像**。
  **全程不发网络** —— 填充响应有系统超时，等一次下载就会超时丢候选。
  ⚠️ `DiskCache.Snapshot.data` 是 **okio.Path**，不是 File，需 `.toFile().absolutePath`。

**判据**：**写 RemoteViews 之前先确认「这个效果系统能不能画」**；
拿不准的一律降级为「App 侧合成好 Bitmap 再交给系统」。

---

## 78. 系统填充下拉是 RemoteViews 渲染 —— 「Material」只能靠 App 侧模拟到哪一层算哪一层（2026-09-13，第五十一轮）

**现象**：填充候选框尺寸已经紧凑了，但「风格和最新的 material 有点差距」。

**根因**：`autofill_dataset_item.xml` 是交给**系统的 Autofill 界面（RemoteViews）**渲染的，
不在我们的 Compose 树里，因此 **M3 的 `ListItem` 只在「尺寸与配色规格」这一层可比**，
「形状 / 高度 / 动效」这一层是 RemoteViews 能力的硬边界（详见 `ISSUES.md` #72）：
- ✅ 能做：行高 `minHeight`、内边距、shape 背景、`setTextViewText` / `setTextColor` /
  `setImageViewBitmap` / `setContentDescription`；
- ❌ 不能做：`MaterialCardView` 的完整阴影与 elevation、水波纹（ripple）、
  非系统字体、动态取色（需按 `uiMode` 自判，用 `drawable-night/` 给深色变体）。

**解法**：把「能对齐的那一层」照 M3 `ListItem` 规格一项项对：
- 行高 **56dp**（M3 两行列表项标准值；旧值 48dp 是紧凑值，观感偏「工具栏」）；
- 图标包进 **40dp 圆形 tonal 底板**（M3 的 icon container 规格）+ 位图 24dp→**34dp**
  （避免在 40dp 容器里上采样发虚）；
- 图标与文字间距 **16dp**（M3 leading→label 间距）；
- 标题 **16sp**（`bodyLarge`）/ 副标题 **14sp**（`bodyMedium`）；
- 水平内边距 **16dp**；
- 圆形底板用 `drawable/` + **`drawable-night/`** 两套（浅 `#E6E0E9` / 深 `#49454F`，
  即 `surfaceContainerHighest` 等效值），因为 RemoteViews 拿不到 Compose 动态取色。

**判据**：**改填充下拉前先问「这一层 RemoteViews 支不支持」**，不支持就别试
（阴影、ripple、动态色都属此类），只对齐尺寸与静态配色。

---

## 82. 认证结果被系统丢弃：`NEW_TASK` 与 `taskAffinity` 是同一条死路的两个入口（2026-09-13，第五十二轮）

- **现象**：库锁定时在第三方 App（QQ/浏览器）点填充 → 指纹解锁**成功** → 却**什么都没发生**，
  仍是"解锁 Vaultix"那一项 → 反复解锁、**永远看不到密码条目**；返回宿主 App 重进登录页才能填。
- **根因**：`AutofillActivity` 被**推进独立 task** 了，于是
  `setResult(RESULT_OK, EXTRA_AUTHENTICATION_RESULT)` **送不回系统**。
  两个入口**任一成立即可致命**：
  ① 认证意图带 `FLAG_ACTIVITY_NEW_TASK`；② Activity 声明了与包名不同的 `taskAffinity`。
- **系统日志实证**（必须连框架侧一起抓才看得到）：
  ```
  AutofillSession: authenticate() ... intentSender=PendingIntent ... startActivity
  AutofillManager: onAuthenticationResult(): data=null
  AutofillManager: onAuthenticationResult(): empty intent     ← 系统在启动后 ~16ms 就判定"没有返回"
  ```
- **解法**：① dataset 级认证意图**绝不加 NEW_TASK**（只有"引导型"意图才补）；
  ② `AutofillActivity` **不声明 `taskAffinity`**。
  对照 `D:\Vaultix-refs\bitwarden-android` 的 `AutofillCallbackActivity`：`exported` +
  `launchMode=singleTop` + `noHistory` + 透明主题，**无 taskAffinity、无 excludeFromRecents**。
- ⚠️ **但不要照抄它的 `launchMode="singleTop"`**：本项目没重写 `onNewIntent`，
  单实例复用会**丢掉新的认证意图**（本项目要求 standard）。
- ⚠️ **排 autofill 问题必须连系统框架侧日志一起抓**（`AutofillManager` / `RemoteFillService` /
  `AutofillSession`）：只抓自己的 tag 时，"系统把我们的结果丢了"**完全不可见**。

## 90. `noHistory="true"` 打断「解锁即回填」——#82 的第**三**个入口（2026-09-14）

- **现象**：库锁定时点填充 → 指纹解锁**成功** → 返回后**条目列表空白 / 密码填不进去**
  （与 #82 表象相同，但成因不同）。
- **根因**：`AndroidManifest.xml` 的 `AutofillActivity` 声明了 `android:noHistory="true"`。
  而本项目的回灌架构**要求该 Activity 留在栈上**——
  `AutofillActivity.kt` 类注释写明「本 Activity **不 finish** …… 它必须留在栈上，
  否则解锁完成后没有任何人能投递认证结果」，`openVaultAndFinish()` 里也确实**故意不 finish**，
  靠 `onResume` 投递 `EXTRA_AUTHENTICATION_RESULT`。
  `noHistory` 的语义是「**一旦不可见即销毁并 finish**」，与「留在栈上」**物理互斥**
  ⇒ 用户跳去解锁的瞬间 Activity 被销毁 ⇒ `onResume` 永不触发 ⇒ 回灌静默丢失。
- **解法**：**删除 `android:noHistory="true"`**。
- ★ **教训（比坑本身更重要）**：`noHistory` 是从 Bitwarden「对照配置」照抄来的
  （#82 的注释里甚至列了它）。**但两家回灌架构不同**：Bitwarden 走 NEW_TASK + 独立进程
  回灌，本项目走「留在栈上等 `onResume`」。
  ⇒ **照抄配置前，必须先确认"对方为什么要这么配"**——同一个属性在两种架构下含义相反。
  ⇒ 本项目认证 Activity 的正确声明是：`exported` + `excludeFromRecents` + 透明主题，
    **无 `noHistory`、无 `taskAffinity`、无 `launchMode`**（要保持 standard，见 #82）。


- **语义分岔**：放 **`FillResponse`** = 系统把里面的 datasets **列成候选让用户挑**；
  放 **`Dataset`** = 系统**直接把它填进去**（用户看不到任何候选列表）。
- **用法**：「解锁后要让用户**选**条目」→ `FillResponse`；「用户**已经选定**某条目后的
  二次验证 / 主密码复核回灌」→ `Dataset`。
- ⚠️ 这是"同一个 extra 放不同类型、行为完全不同"的平台 API，**必须查文档、不能靠类比**。


## 113. 容器重定向缺失 + 逐字段站点用全局单值 —— 「偶尔不弹条目 / 偶尔提示无匹配」的结构性根因（2026-09-22，P0，第五十三轮）

- **现象**：Edge 里**偶尔**不弹出密码条目；**偶尔**弹出但内容提示「密码库中无匹配条目」。
  「偶尔」二字是关键判据 —— 同一站点两次捕获表现不同，说明**输入信号在两次捕获间变了**，
  不是某个静态判定写错了。
- **根因一：语义落在不可填容器上（容器重定向缺失）**
  浏览器 / WebView 常把语义挂在外层容器上（`<form autocomplete="username">`、
  包住 `<input>` 的 `<div>`），而容器自身 `autofillType == AUTOFILL_TYPE_NONE`。
  解析时把 hint 落在容器上后，框架回填发现目标不可填 → **丢弃整条 dataset**。
  上游 Bitwarden 原文：
  > `the container's type=0 (NONE) would cause buildFilledItemOrNull to return null
  > and drop the field from the fill dataset entirely`

  注意丢的是**整条 dataset**（连带同批次的账号 / 密码），不是这一个字段 ——
  所以表现是「整个条目都不出现」，而非「填了一半」。
- **根因二：逐字段站点用了全局单值**
  旧实现 `val webDomain = webDomains.firstOrNull()` 把「页面域名」当成**整个页面唯一**的值。
  但框架只在**跨域 iframe 的根节点**上带 `webDomain`，iframe 内部的每个输入框自身是 `null`。
  ⇒ 跨域 iframe 里的登录框被当成「主文档的框」，填充阶段拿主文档域名做站点校验
  ⇒ 校验不过就静默不填（`targetIdsFor` 的 `UriMatcher.sameSite` 过滤掉）。
- **解法**（对齐 Bitwarden 官方两条）：
  1. **容器重定向**：`resolveFillTarget` —— 自己 `AUTOFILL_TYPE_TEXT` 就用自己，
     否则**前序深度优先**下钻到第一个可填且带 `autofillId` 的后代；整棵子树都不可填则
     **丢弃该字段**（不是保留成填不进去的目标）。语义落点、值的读取、可见性判定
     全部改用**下钻后**的节点。
  2. **逐字段站点 + 父级继承**：`webDomain = node.webDomain ?: parentWebDomain`
     （上游 `website = this.website ?: parentWebsite`），自顶向下在 `traverse` 里传递。
- ⚠️ **判据是 `autofillType`，不是「是不是输入控件」**。这两者在真机上会分岔：
  一个 `<input>` 完全可能 `autofillType == NONE`（`type=hidden` / `disabled` / `readonly`），
  一个 EditText 也可能因 `importantForAutofill=no` 而不可填。
  本项目已有的 [isEditableNode] 判的是**控件类型**（`htmlInfo.tag == "input"`），
  它是「该不该进入字段表」的准入闸，**不能**兼职当「能不能收值」的判据 ——
  拿它当后者会把 `type=hidden` 的框当成可填目标，等于没重定向。
- ⚠️ **单测陷阱**：`ViewNode` 是 `@SystemApi` 抽象类，JVM 单测**构造不出实例**，
  所以重定向逻辑必须抽成纯函数才可测（见 `FillTargetResolver.kt`）。
  另外 AGP 的 `android.jar` 是 stub jar，**字段常量在单测里恒为 0**
  ⇒ 直接引用 `View.AUTOFILL_TYPE_TEXT` 会与 `AUTOFILL_TYPE_NONE` 撞车、
  让全部重定向断言假通过。故策略层显式复制真值（`0` / `1`）并留一处断言锁住。
- **验证**：`FillTargetResolverTest` 16 条用例全绿；另做**变异测试**
  （把 `if (node.canReceiveValue) return node` 改成 `if (false)`）→ 7 条用例失败，
  确认这套断言真的能挡住回归，而不是恒真。

## 114. 一个 `firstOrNull()` 假设同时污染「匹配」与「填充」两侧（2026-09-22）

- **教训（比坑本身重要）**：`webDomains.firstOrNull()` 这种「页面级单值」写法，
  错的地方**不止一处** —— 它同时决定了：
  ① 匹配阶段用哪个域名去检索条目（`AutofillCandidateSource.webDomainOf`）；
  ② 填充阶段拿哪个域名做逐字段站点校验（`AutofillDatasets.targetIdsFor`）。
  改其中一处、漏另一处，就会出现「候选能列出来但点下去填不进」这种**更难查**的半坏状态。
- ⇒ **凡是「页面上可能有多个 X」的信息，在解析层就必须做成逐字段的**，
  别在上层用 `firstOrNull()` 抹平 —— 抹平点越靠上，下游要用时就越取不回来。


---

## 187. 🔴 自动填充「保存」链路四处断裂 —— 锁定态永不提示、字段选错、撞号、明文进 Intent（2026-10-26，对比审计）

- **现象**（四个独立缺陷，落在同一条链上，任一都足以让"保存"失败）：
  1. **首次在锁态登录永远不提示保存**；
  2. 页面有多个用户名/密码框时，保存下来的是**第一个框**的值（可能是隐藏框或空框）；
  3. 密码列表里点**第 2 行**，填进去的是第 1 条的密码；
  4. 明文密码出现在 `Intent` extras 里（`dumpsys activity` / 第三方 App 可读）。
- **根因**：
  1. `VaultixAutofillService` 的**锁定态分支**构造 `AssistStructure` 响应时**漏挂
     `saveInfo`** ⇒ 系统根本不知道这个请求可以保存 ⇒ `onSaveRequest` 不会被调用。
     用户的真实路径恰恰是"还没解锁就先在网页上登录"，所以这条分支最该有 `saveInfo`。
  2. `AssistStructureParser.parse(...)` 的 `fillAssistRules` 参数用**方法引用**传进去时
     丢了实参（`::parse` 少一参），规则形同虚设；且字段选取用"第一个 `USERNAME`/`PASSWORD`
     类型的节点"，**不跳过 `isVisible=false` 和空值**。
  3. `AutofillDatasetFactory` 的 `requestCode = suggestion.id.hashCode()` +
     `PendingIntent.FLAG_UPDATE_CURRENT`：**同 id 多次请求会覆盖前一个 PendingIntent**，
     而 `hashCode` 碰撞在几十条密码的库里毫不稀奇 ⇒ 点哪行都回填同一个。
  4. 保存链原先把 `username`/`password` 直接塞 `Intent` extras 和 `SavedStateHandle`。
- **修法**：
  1. 锁定态分支补 `saveInfo = AutofillSaveInfo.build(parsed)`；
  2. `parse(structure, fillAssistRules = …)` 显式传参；新增 `saveValueOf(parsed, hint)`
     —— 按 hint 选、**跳过隐藏与空值**，取不到才退回首个候选；
  3. 新增 `engine/RequestCodes.next()`：`AtomicInteger` CAS 自增，**从 8000 起**
     （让开手写区间 `1..7999`，`REQUEST_UNLOCK_CP = 2101` 之类的常量仍在安全区），
     到 `0x00FFFFFF` 回绕。`AutofillDatasetFactory` 与
     `CredentialProviderEntryBuilder` 的四处 `hashCode` 全部改用它；
  4. 新增 `PendingSaveStore`（`@Singleton`，**纯内存**）：`stage()` 生成一次性票据，
     45/60 秒 TTL，`consume(id)` **用一次即失效**、票据不匹配不消耗。`Intent` 里只带
     `EXTRA_TOKEN` / `EXTRA_PACKAGE` / `EXTRA_DOMAIN`。`AutofillSaveViewModel` 通过
     `savedStateHandle[EXTRA_TOKEN]` 去 store 取明文；取不到则 `expired = true`，
     界面画 `ExpiredBody`（"保存窗口已过期"），**不是**画一个永远转圈的加载态。
- **⚠️ 坑**：
  1. `FLAG_UPDATE_CURRENT` + 值当 key 是**经典撞号**，且症状是"填错密码"而不是崩溃，
     极易被当成用户记错密码；
  2. 票据 `consume` 必须**原子且一次性**：`SavedStateHandle` 在进程重建后会重放，
     若允许二次消费，重放路径会拿到一份**本该已经作废**的明文；
  3. TTL 用 `SystemClock.elapsedRealtime`（不是 `System.currentTimeMillis`），
     否则改系统时间能把过期票据"复活"。
- **验证**：`PendingSaveStoreTest`（7 条：一次性、票据不匹配不消耗、新覆盖旧、clear、
  TTL 过期——用反射把 `stagedAtElapsedMs` 往前推 61 秒，不真等）、
  `RequestCodesTest`（2 条：2 万次发号无重复、首号 ≥ 8000）。
- **判据**：**`PendingIntent` 的 requestCode 是"身份"，不能由内容哈希兼任**；
  **凭据类数据不进 `Intent` / `SavedStateHandle`** —— 这两者都会被系统落盘。
