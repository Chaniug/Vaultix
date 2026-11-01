# 06-界面与交互 · 第 9 篇（#199 ~ #206）

> 主篇索引：[`06-界面与交互.md`](./06-界面与交互.md)（含计数规则与重号警告）
> 本篇 4 章 · 15.1KB · 覆盖 #199 ~ #206

## 199. 文件夹 CRUD：整条写路径此前不存在（2026-10-27）

**用户需求**（原话）：「继续开发，完善设置，以及日常的功能」。核对功能盘点后发现：
`FolderRepository` **只有 `observeFolders` 三个读方法**，`FolderDao` 只有整体替换的
`replaceByVault`。⇒ 用户能**看到**文件夹（分组视图、表单下拉框都在用），但**建不了、
改不了、删不了**。Bitwarden 客户端与本项目对标的 KeePassDX 都能建分组 —— 这是一块
「别人有、我们没有」的真空。

### 199.1 三个决策点（用户已选定，别再重议）

| 问题 | 选择 | 为什么 |
|---|---|---|
| 写失败怎么办 | **直连 API，失败当场报错** | 见 199.2 |
| 要不要一并支持 KDBX 分组 | **一并支持** | 只做 Bitwarden 等于给一半用户看一个死页面 |
| 入口放哪 | **条目页 ⋮ 菜单「前往」组** | 文件夹是**数据**（能删），不是"看的方式"；后者归「显示选项」 |

### 199.2 🔴 为什么 Bitwarden 侧**不能**照搬条目的「本地先行 + pending_ops 排队」

条目的写是"本地先落库、`pending_ops` 排队、轻量推送"。文件夹有三条硬理由不能抄：

1. `pending_ops` 的主键语义是 `cipherId` + 五种 **cipher** 操作，没有任何一列能装 folder；
2. **`pruneRemovedRows` 会把本地新行当已删清掉** —— 它判定"服务端没返回的 folder 行 = 已删"。
   用户刚建、还没推上去的文件夹会被**下一次同步静默删除**（用户看到自己建的东西凭空消失，
   比"离线时建不了"严重得多）；
3. `FolderDao` 只有 `replaceByVault` 的整体替换语义，没有单行 upsert。

⇒ **直连：成功才写本地，失败一个字都不改。** 代价写进 KDoc 了：离线时不能建文件夹。
这个取舍是**有意的**，后续"顺手统一成队列"会直接复现第 2 条。

### 199.3 实现结构（四层，每层一处判据）

| 层 | 文件 | 内容 |
|---|---|---|
| 领域 | `domain/FolderNamePolicy.kt` | 名字校验**唯一一处**：trim / 空 / ≤100 字符 / 禁 `/` `\` |
| 领域 | `domain/FolderRepository.kt` | 三个写方法，均 `Result<Unit>` |
| KDBX | `data/kdbx/KdbxItemWriter.kt` | `createGroup` / `renameGroup` / `removeGroup` / `deletionCostOf` |
| KDBX | `data/kdbx/Kdbx.kt` | 门面 + `folderDeletionCostOf`（只读预览） |
| 仓储 | `data/repository/FolderRepositoryImpl.kt` | `resolve(vaultId)` 分流到 `Remote(api)` / `Kdbx` |
| 库 | `core/database/dao/AtomicWriteDao.kt` | `upsertFolder` / `deleteFolder`（`@Transaction`） |
| UI | `ui/folders/` | `FoldersViewModel` + `FoldersScreen` + `FolderNameDialog` |

🔴 **为什么 `/` 和 `\` 必须禁**：KDBX 的 `VaultFolder.name` 存的是**完整路径**
（`KdbxItemMapper` 用 `"$parentPath/${group.name}"` 拼），组名里带 `/` 会让层级
不可解析 —— 建得进去、读出来全是错的。

🔴 **`AtomicWriteDao` 的两个文件夹方法故意写成「非 abstract」**：用 Room `@Upsert`
的**已注册实体**形态（`FolderEntity` 在 `@Database(entities=…)` 里），Room 自己生成实现。
代价是单测里那些手写的 `AtomicWriteDao` 转发子类**一个字都不用改**；
若哪天有人改回 `protected abstract`，那 5 个子类会立刻编译失败 —— **这个失败是想要的**。

### 199.4 删除代价的「数法只能有一处」

`KdbxItemWriter.removeGroup` 递归删子树时要报 `GroupDeletion(subGroups, entries)`，
而 UI 的确认框也要显示"会连带删掉 N 条"。两处各算一遍必然漂移 ⇒
**抽 `deletionCostOf(database, folderId)` 只读预览**，`removeGroup` 内部也改用它。

⚠️ **UI 侧 KDBX 那一支刻意不显示条目数**：`FoldersViewModel` 只统计 `folderId`
**恰好等于该分组完整路径**的条目，**子组里的没算进去** ⇒ 系统性偏小。
给一个偏小的数字比不给更危险（用户会想"才 2 条，删吧"），
而算准它要重走整棵树、为确认框跑一次文件解析不划算。

### 199.5 两种库的删除语义**必须分开写文案**

- **Bitwarden**：文件夹是扁平标签 ⇒ 删掉只让条目变「无文件夹」，**条目不删**；
- **KDBX**：分组是树 ⇒ 删掉是**递归删整棵子树**（子组 + 条目全没，不可撤销）。

用同一句"删除文件夹？"会让 KDBX 用户在毫无预警的情况下丢数据。
这是本页唯一一处"两种库必须长得不一样"的地方（文案键都分开：`folder_delete_*_message`）。

### 199.6 三层测试与两条反证

| 测试 | 例数 | 锁定什么 |
|---|---|---|
| `FolderNamePolicyTest` | 6 | 边界值、trim、禁字符 |
| `KdbxGroupWriteTest` | 11 | 建/改名/删子树、回收站组不许动、代价统计 |
| `FolderRepositoryImplTest`（新增 10） | 共 14 | 双路径分流与失败零写入 |

★★ **两条必留的反证**：
- `新建失败_本地零写入`（`coVerify(exactly = 0) { folderDao.upsertOne(any()) }`）——
  没有它，"先写本地再调远端"的实现也能让其它用例全绿，而那正是 199.2 第 2 条的幽灵行来源；
- `KDBX 库建文件夹转交 KdbxItemRepository` + `coVerify(exactly = 0) { api.createFolder(any()) }`
  —— 两个库的写路径**不能串**，串了就是把 KDBX 分组发去某个 Bitwarden 服务器。

⚠️ **本轮踩的坑（值得记）**：`FolderRepositoryImplTest` 连报 10 条
`Suspension functions can only be called within coroutine body`，我第一反应是
"`coVerify` 写到了 `runTest` 块外面"。**错了** —— 报错行是 `every { vaultDao.get(...) }`，
而 `VaultDao.get` 是 `suspend`，挂起函数必须用 `coEvery`。**读报错要看行号指向哪个符号，
不要看自己最可疑的那段。**

---

## 200. 液态玻璃：源「只录不画」⇒ 整个页面变白（2026-10-28）

> 用户原话：「开启液态玻璃后，整个页面变白」。
> 这是 #198 第二轮（#198.1「源 → 消费两段分离」）**自己引入**的回归 ——
> 那一轮修好了"图标被一起糊掉"，代价是除底栏外的整屏都不画了。

### 200.1 根因：源录进 layer 之后**没有画回屏幕**

第二轮 `Modifier.liquidGlassSource` 的实现只有一句 `source.layer.record { drawContent() }`，
KDoc 里写的是"屏幕上不落任何像素，真正显示它的是浮层"，还自称这是**与 Haze 的有意分歧**。

**这个分歧不成立**：浮层只覆盖底栏那一小块，而源录的是**整屏 Tab 内容**。
没有浮层覆盖的那 95% 区域在屏幕上没有任何绘制命令 ⇒ 直接露出窗口底色
（浅色主题下就是白屏）。用户看到的"整个页面变白"由此而来。

Haze 源码恰恰是**先录、再画回屏幕**：

```kotlin
contentLayer.record { this@draw.drawContentSafely() }   // 内容进 layer
drawLayer(contentLayer)                                  // 再画回屏幕（清晰的那份）
```

⇒ 修法就是补上第二行。**本项目现在与 Haze 零分歧。**

> 🔴 **一条可复用的判据**：凡是"把某棵子树的绘制接管到离屏 layer"的写法，
> 都要先回答"**屏幕上的像素由谁负责**"。只回答了"浮层会画一小块"，
> 就等于承认其余区域没人画 —— 而"没人画"在 Compose 里**不是报错，是露底色**。

### 200.2 派生约束：模糊**不能**挂在 contentLayer 上

一旦内容要靠 `drawLayer(contentLayer)` 回屏，`contentLayer.renderEffect` 会
**把屏幕上那份也一起糊掉**（一块 layer 只有一个 renderEffect，两个消费者共享它）。
⇒ 模糊必须挂在一块**独立的中转层**上：

```
contentLayer  ← 源：record { drawContent() }，随后 drawLayer 回屏（始终干净、无效果）
effectLayer   ← 浮层：record(源尺寸) { drawLayer(contentLayer) } → 挂模糊 → 画到浮层位置
```

这也是 Haze `hazeEffect` 内部对源图层的处理方式。**只挂一处、且只能挂 effectLayer**：
挂回 contentLayer ⇒ 整屏糊；两边都不挂 ⇒ 底栏不糊（"开关坏了"）。

### 200.3 采样偏移的**符号**：`translate` 用的是 offset 本身

`backdropOffset(源, 浮层) = 源左上角 − 浮层左上角`（底栏在源下方 ⇒ y **为负**），
而绘制端做的是 `canvas.translate(offset.x, offset.y)` —— **同一个值，不再取反**。

第二轮写成 `translate(-offset.x, -offset.y)`（多取一次反）⇒ 底栏糊的是屏幕外的一块空白。
它当时被"整页变白"盖住了，肉眼分不出来 ⇒ **抽成纯函数 + 4 例单测钉住方向**
（`LiquidGlassSourceTest`）。

### 200.4 两处"编译期才知道"的 API 形态

1. **`GraphicsLayer.record` 只有 `ContentDrawScope` 的成员扩展** ⇒ 浮层的采样必须用
   `Modifier.drawWithContent { }`，写成 `drawBehind` / `drawWithCache { onDrawBehind }`
   会直接 `Unresolved reference`（这两个的 receiver 静态类型是 `DrawScope`）。
2. **`record` 不传 `size` 就用当前 DrawScope 的尺寸** ⇒ 在浮层里（只有胶囊那么高）
   不显式传源的 `IntSize`，整屏内容会被裁成底栏那一小条。
   ⚠️ `drawWithCache` 里的 `record` 是 `CacheDrawScope` 那一份（只在缓存期跑一次），
   **不能**用它做每帧重录。

### 200.5 顺带：源只在真的会挂模糊时登记

录一帧源 = 一次整屏离屏拷贝。API < 31 根本没有 `RenderEffect`（底栏走实色分支），
让老设备每帧白拷一次全屏没有意义 ⇒ 源修饰符的 `enabled` 取
`开关 && isLiquidGlassBlurSupported()`，而**不是**只取开关。

### 200.6 待真机验收（本次改动的验收清单）

1. 开启液态玻璃后**页面内容正常显示**（不再是白屏）—— 本条是本次 bug 的核心；
2. 列表滚动时胶囊后面有内容在动、被糊开，且**页面本身是清晰的**（不是整屏糊）；
3. 图标 / 文字 / 中央「+」清晰，糊影不越出胶囊边界；
4. 深色主题下玻璃与背景能分出边界；
5. API 30 及以下 → 实色底栏 + 设置页提示"将使用实色底栏"；
6. 关掉开关 → 与从未开启时完全一致（源修饰符不挂，零开销）。

---

## 205. 🔴 打开液态玻璃后**整条底栏消失** —— 子节点全用 `matchParentSize()` ⇒ 父 Box 高度塌成 0（2026-10-28）

- **现象**：设置里打开「液态玻璃底栏」后，底部胶囊**完全不见**（不是变透明、不是错位，是整条没了）。
  关掉开关立刻恢复。无崩溃、无日志、无 lint 告警。
- **根因**（`LiquidGlassLayer` 的布局，第二轮 `dfd1060` 起就存在，被"整页变白"盖住了）：

  ```kotlin
  Box(modifier = modifier) {                                  // ← 高度由谁决定？
      Box(Modifier.matchParentSize().clip(shape).liquidGlassBackdrop(...))   // 层 A
      Box(Modifier.matchParentSize().clip(shape).drawBehind { drawGlass() }) // 层 B
  }
  ```

  `matchParentSize()` 的语义是**「尺寸跟随父，且不参与父的尺寸计算」**。
  两个子节点都用它 ⇒ **没有任何子节点参与测量** ⇒ 父 `Box` 只能取 `constraints.minHeight`。

  `foundation-layout` 的 `Box.kt` 源码（已下载 1.10.5 源核对，非推测）：

  ```kotlin
  var boxHeight = constraints.minHeight                              // = 0
  measurables.fastForEachIndexed { i, m ->
      if (!m.matchesParentSize) { boxHeight = max(boxHeight, p.height) }  // 一个都没进来
      else hasMatchParentSizeChildren = true
  }
  ...
  val c = Constraints(minWidth = boxWidth, minHeight = boxHeight,   // minHeight = maxHeight = 0
                      maxWidth = boxWidth, maxHeight = boxHeight)
  ```

  ⇒ 父 Box 高 **0** ⇒ 层 A/层 B 也被 `Constraints(minHeight=0, maxHeight=0)` 测成 0
  ⇒ `clip(shape)` 再把 0 高裁一遍 ⇒ 底栏整条归零。宽度没事，因为外层 `fillMaxWidth()`
  给了 `minWidth = maxWidth`，只有高度没有约束。
- **修法**：层 B 去掉 `matchParentSize()`，改 `fillMaxWidth()`（只锁宽度，高度随 `content()`），
  让它成为**唯一参与父测量**的子节点；层 A 保持 `matchParentSize()`（它本来就该盖在父身上）。
- **为什么藏了整整两轮才发现**：第二轮修完是"整页变白"——**屏幕一片白时，没人看得出底栏没了**。
  第三轮修好白屏，底栏消失才暴露。⇒ **一个显眼的故障会遮住它旁边那个不显眼的**，
  "主诉症状缓解"之后必须**重新完整过一遍验收清单**，而不是宣布修完。
- **判据**：**`matchParentSize` 只能"盖在别人身上"，不能"撑起父容器"。**
  每写一个都要能指出"谁在决定父尺寸"；指不出来就是 0。
- **同类写法已排查**：`PressAndSwipeToDelete` / `ItemsScreen` 两处的父 Box 都有正常子节点
  （一张卡 / 一层列表），不构成塌缩。
- **另一处顺带加固**：`liquidGlassBackdrop` 的提前返回分支原先**不调 `drawContent()`**——
  "条件不满足就把子树整个吞掉"，与本次同族。已补上（层 A 目前是空 Box，看不出后果，
  但这种写法一旦被复制到有内容的层上就是下一次"消失"）。
- **真机验收**：见 #200.6 的六条 + **第七条**：*胶囊可见、高度与实色态一致*。

---

## 206. 开发者日志：**屏幕显示没有脱敏**，且 `E/F` 兜底把白名单架空（2026-10-29）

**现象**：设置 → 开发者 → 日志，对话框里逐行显示的是**原文**；而同一份内容点「导出」时
才走 `sanitize()`。日志源是 logcat —— 谁往里打了什么不受我们控制（第三方 SDK 常把请求体
整段打出来）。⇒ **导出那份是干净的，正看着屏幕的那份不是。**

**根因（两个洞，同源）**：

| 洞 | 写法 | 后果 |
|---|---|---|
| ① 脱敏只挂在出口 | 只有 `buildExportText` 调 `sanitize` | 屏幕显示漏 |
| ② 兜底比主判据宽 | `KEEP_PATTERN.containsMatchIn(text) \|\| level in "EF"` | 白名单对 `E`/`F` 级**完全失效**。而白名单存在的唯一理由就是挡噪声（Honor 机型 `afehal` 实测几十秒淹没有效记录） |

> **"兜底"比"主判据"更宽，等于没有判据** —— 写的时候是想"别漏掉自家崩溃"，
> 实际效果是"凡是错误级别一律放行"。

**修法**：
- 抽出 `LogScrubber`（`ui/settings/LogScrubber.kt`）：**采集即脱敏**（一处生效，显示与
  导出都干净，且只给"确定要显示"的行付正则成本）；
- `E/F` 兜底收窄为「**且**文本含 `vaultix`」（[SELF_HINT]）：自家的崩溃/断言照进，
  系统噪声照挡；
- 导出仍是 `scrub` 一次（**双保险**：头部有机主填的设备名，那是人填的用户信息）。

> **判据**：① **日志脱敏要保护的是"看的人"，不只是"分享出去的那份"**；
> ② **兜底只能比主判据更严，不能更宽**。
>
> ⚠️ 顺带更正一条旧认知：本文件曾记「logcat 只能读本应用自己进程」，那么"别人的日志"
> 其实读不到 ⇒ 洞 ② 的真实代价是**自家**噪声刷屏 + 自家日志里的敏感串原样上屏，
> 而不是"拉进别人的数据"。脱敏该做仍然该做（我们自己也可能会打错东西）。

---

