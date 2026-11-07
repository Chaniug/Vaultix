# 2026-11-07 · 第九批：配色事故 / 闪退止血 / CI 往返

> 主篇 [`SESSION-2026-11-07.md`](./SESSION-2026-11-07.md) 已 288 行 / 19KB，
> 按篇末指针另开本篇。本篇只写**主篇没有的事实**，不复制 §7–§9。
> 遵守 [`conventions/8.9-文档分篇.md`](./conventions/8.9-文档分篇.md) §7。

---

## §1 密码高亮：两轮翻车与最终修法

**回答「为什么深色模式下密码强调块成了刺眼亮粉」**

- 原实现 `EmphasisBlock` = `primaryContainer.copy(alpha = 0.30f)` 叠在
  `surfaceContainerHighest` 卡片上，用户反馈「深色一点的边框，很生硬」。
- 第八批改成**不透明** `tertiaryContainer` 具名色档 —— 判断失误：
  项目开了 Material You 动态取色，深色主题下 `tertiaryContainer` 可能由壁纸
  生成亮粉紫，整块高亮直接糊成一坨（用户真机截图：「灾难」「颜色和卡片完全失误」）。
- 最终改 `tertiaryContainer.copy(alpha = 0.10f)`：仍是**不同色相**（不是 8.4
  禁止的同色叠 alpha），但压到与卡片几乎同明度，只留一层可辨暖调。
- ⚠️ `FieldIcon` 徽章也用 `tertiaryContainer`，但**刻意不动**：小面积亮色是点缀，
  截图里没问题。批量替换时断言 `count=1` 挡住了误改第二处（该串出现 2 次）。

- 判据：**动态取色下，任何「大面积」容器色都不能假设它是低饱和的**；
  彩色只放在小面积点缀，大面积强调必须压 alpha 或退回 surface 色阶。

---

## §2 文件夹删除闪退：止血，根因未定

**回答「为什么 KDBX 删文件夹 100% 闪退，改到哪一步」**

- 真机取证：`adb logcat -d` 找到 `18:46:18 Force removing ActivityRecord …
  app died, no saved state`（pid 18657），18:47:05 进程重启 ⇒ 崩溃属实。
  但 crash buffer 只有一行系统哈希，**没有 Java 堆栈**，不能假称已定位根因。
- 代码链路全被 `runCatching` 包着：`FolderRepositoryImpl.deleteFolder` →
  `KdbxItemRepository.deleteFolder` → `Kdbx.deleteFolder`；UI 侧
  `state.rows.firstOrNull` 也有 null 守卫。肉眼没找到确定抛出点。
- 找到一个真实防线缺口：`FoldersViewModel.launchWrite` 只有 `try/finally`、
  **没有 catch**。KDoc 写着「意外不该把页面卡在忙碌态」，但 finally 只清 busy，
  `block()` 若直接抛异常会穿出 `viewModelScope.launch` 成为未捕获异常 ⇒ 杀进程。
- 修复：`runCatching { block() }` 包住，意外转 `UiEvent.Failed`（Snackbar 可见），
  `CancellationException` 显式重抛避免误吞取消；finally 仍清 busy。
- ⚠️ **这是止血 + 暴露错误信息，不等于根治**：装新包后请复现一次删除，
  若不再闪退会看到 Snackbar 里的错误原因；若仍闪退，需重新起 logcat 抓
  `FATAL EXCEPTION` 堆栈（或确认是否 native / OOM 类死亡）。

- 判据：**「只有 finally 没有 catch」不是异常兜底**；ViewModel 的写操作外壳
  必须同时保证「状态复位」与「异常不越出协程」这两件独立的事。

---

## §3 CI 三次往返

**回答「这一批为什么推了四个 commit 才绿」**

| 轮次 | run | 结果 |
|---|---|---|
| `04101de0` | `42365066619` | 单测 577 例 1 失败：`SyncBadgeTest.kt:70` 仍断言 `PENDING_UPLOAD -> null`，而本批已映射成 `UPLOADING` |
| `f84e3e24` | `42365875071` | detekt `TooGenericExceptionCaught`：`catch (Throwable)` 过宽 |
| `1d7c17c0` | `42366019039` | detekt 仍红：连 `catch (Exception)` 都判过宽 |
| `0d0540af` | `42366185744` | ✅ 全链路绿（3m9s、单测 1639 例 0 失败、编译通过） |

- 判据：① 给枚举加值后**主动搜所有 `when` 与旧断言**（Kotlin 穷尽 when 会编译报错，
  但 `Truth.assertThat(...).isNull()` 这类语义断言不会）；
  ② detekt 的 `TooGenericExceptionCaught` 连 `Exception` 都禁 ⇒ 用项目同款
  `runCatching`，并显式重抛 `CancellationException`；
  ③ 前置门禁一红，后面的编译 / 单测一步都没跑（§8.6.0 的老规律再次复现）。

---

## §4 出包与验收清单

**回答「装哪个包、装完验什么」**

- Preview：`Vaultix v0.11.2-dev-0d0540a · Preview`（滚动 tag `preview`，
  19:01 发布），资产 `app-full-debug.apk` 37,323,879 bytes。
  稳定版 `v0.11.2` Latest 未动，`rele` 未推，本地 main `0/0`。
- 装完请验：① 新建/编辑条目时密码强调块是否已与卡片协调（不再亮粉大块）；
  ② 保存后本地立刻出结果、详情页顶栏「转动 → 小云」；
  ③ ⋮ 菜单「正在上传改动」；④ **KDBX 文件夹删除**是否还闪退，
  若变成 Snackbar 请把那句错误文案告诉我。
- 仍未修（主篇 §9）：B 条目页 ⋮ 同步按钮只接 Bitwarden 编排器导致 KDBX 空转；
  C 一次远端拉取约两次整库下载 + 两次 KDF 导致偏慢。两者都需单独一批。


---

## §10 第十批：手机改动永远传不上 OneDrive（19:02–19:40）

**回答「真机看到『本地已保存、直接显示小云、电脑端没有改动』，根因在哪、怎么修」**

### 取证链（实证，非推断）

- 设备装 `0.11.2-dev-0d0540a`，`dumpsys package` 确认 19:02:57 安装、37.3MB debug 包。
- crash buffer 拉到完整 Java 堆栈：`FATAL EXCEPTION: main` /
  `java.util.MissingFormatArgumentException: Format specifier '%2$d'`，栈顶
  `FoldersScreenKt.DeleteFolderDialog$lambda$4(FoldersScreen.kt:338)`。
- 设备 DB + **WAL 原始帧**（不交给 sqlite，避免 checkpoint 抹掉历史）显示状态迁移
  `PENDING_UPLOAD → SYNCING → IN_SYNC`，而 `remoteVersionToken` 始终是
  `ctag:"c:{77DB...},33"`、`lastSyncedAt` 没变。
- 整段 logcat **零条**「上传成功，缓存令牌已刷新」，却有
  19:06:59「缓存已是远端当前版本（58182B）⇒ 跳过整份下载」+「派生密钥内存缓存命中
  ⇒ 本次解码跳过 KDF」—— 这是 `adoptRemoteIfSameContent` 的特征。
- 对照 11-07 上午成功那次（18:22:45 / 18:23:11）有两条上传成功、令牌哈希变化。

### 根因（一句话）

`KdbxItemRepository.persist` 把**本地新字节**写进缓存却**沿用旧 versionToken**
（注释说那是下一次条件写的基线），于是 `CachedKdbxFileSource.readRemoteCurrent()`
的「令牌一致 ⇒ 缓存即远端」短路把**本地改动**当成远端当前字节返回。

内容对账 `Kdbx.adoptBaselineIfMatches` 一比当然相等 ⇒ 状态洗成 `IN_SYNC`、
自动上传被判定为不需要 ⇒ OneDrive 一个字节没写，用户只看到「直接显示小云」。

⚠️ 这比「上传失败」更危险：**真冲突（XC 改过 + 手机也改过）被静默洗白**，
本该弹三选项让用户拍板，却被判成「已同步」。

### 修法（缓存脏标记，落盘）

- `CachedKdbxFile` 加 `pendingLocalEdits: Boolean = false`；
  `FileKdbxFileCache` 线格式 `VKC1 → VKC2`（header 加 1 byte flags），
  旧格式 magic 不匹配自动判未命中并清掉（缓存是优化，丢一次只多下一次库）。
- `persist` 写缓存时置 `true`；`write()` 上传成功后写回干净版本（默认 false）。
- `readRemoteCurrent()`：脏缓存**不许短路**（真 `delegate.read()`）、
  **不许回写缓存**（否则用远端旧字节盖掉用户编辑）。
- 后台校验 `markStalePending` 远端变时同样不覆盖脏缓存，只如实上报。
- `KdbxCloudSyncCoordinator.sync()` 把 `cachedEdits` 并入
  `localChangedSinceLastSync`：`sync` 开头 `markStatus(SYNCING)` 会冲掉
  `PENDING_UPLOAD`，而 `impliesLocalChanges(SYNCING) = false` ⇒ 手动点同步
  曾被判「两边都没变」直接洗白。脏标记是第二个真源，堵住这条路。
- `resolveUsingRemote` 成功后显式写回干净缓存（会话已是远端那一版，
  留着脏缓存会让下次解锁读回被丢弃的本地内容）。
- 回归单测：`CachedKdbxFileSourceTest`「脏缓存不许短路、真读远端、不覆盖缓存」；
  `FileKdbxFileCacheTest` 两条脏标记往返。

### 同批修掉的另两件

- **删文件夹 100% 闪退**：两条 `folder_delete_*_message` 都含 `%2$d`，
  `text` 槽却只传 `name`。KDBX 文案去掉数字占位符（本就因「子组条目没算进去」
  而不该给偏小的数）；Bitwarden 侧补 `itemCount`（`FolderRowUi` 本来就有）。
- **云图标移位 + 进度**：从顶栏 `actions` 移到 `DetailHeader` 类型徽标行最右
  （Spacer.weight 推右，Row 补 `fillMaxWidth`）；上传中在徽标行下加
  M3 `LinearProgressIndicator` 不确定进度细条，完成只剩 `CloudDone`。
  ⚠️ **`remember` 的 key 必须带 `state.syncStatus`** —— 漏了它图标永远停在
  第一次组合那一帧，这正是「压根不转、直接显示小云」的 UI 侧一半原因。

### 判据（可复用）

1. 「本地已保存 + 直接显示已同步 + 远端没变」优先查**状态是不是被洗白**，
   别先查网络；看 WAL 状态帧比看 Room 最终值信息量大。
2. 缓存短路的前提是「令牌一致 ⇒ 内容等于远端」。只要存在「写新字节沿用旧令牌」
   的路径，这个前提就破了，必须有显式脏标记。
3. 从 `crash` buffer 拉堆栈比从 main buffer grep 可靠；WAL 要用 `exec-out` 原始字节读，
   交给 sqlite 会 checkpoint 掉历史帧。
4. UI 状态不刷新先查 `remember` 的 key，再查数据源。

### 仍未修（下一批）

- **B**：条目页 ⋮ 同步按钮 `ItemsViewModel.retrySync()` 只调
  `BitwardenSyncOrchestrator`，KDBX 空转。修它要给已 9 个参数的构造再加依赖
  （detekt 标称上限 8），本批不与缓存格式升级混在一起。
  ⚠️ 首页「密码库」页 ⋮ 同步走 `syncKdbxVault`，本批加的 `cachedEdits`
  判据已让它能正确把本地改动推上去；用户不必依赖条目页那个按钮。
- **C**：一次远端拉取约两次整库下载 + 两次 KDF（对账读完丢弃、`pull` 再读一遍），
  是「同步偏慢」的根因，需单独一批加真机计时。


---

## §11 第十一批：上传可靠性、KDBX 银行卡放行、回收站可见（20:05–20:40）

**回答「十条真机反馈里哪些是 bug、哪些是设计，各自怎么落」**

### 先纠正 §2

§2 写「删文件夹闪退：止血，根因未定」。§10 已用 crash buffer 完整堆栈定位：
`MissingFormatArgumentException: Format specifier '%2$d'` @ `FoldersScreen.kt:338`
（两条 `folder_delete_*_message` 都要两个参数、代码只传一个），并已根治。
§2 的「止血」记录保留作历史，结论以 §10 为准。

### 上传链路：从"通了"到"每次都通"

- 真机装 `0.11.2-dev-9809620` 后，`20:10:24.580` 出现
  「上传成功，缓存令牌已刷新为 #31751fc1」，令牌 `#31751fa2 → #31751fc1`，
  用户在 XC 端看到了改动 ⇒ §10 的修复生效。
- 但 `20:05:47` 保存后到 `20:10:24` 才上传成功，中间 4 分半；WAL 里
  `PENDING_UPLOAD → SYNCING → IN_SYNC` 反复出现 ⇒ **有时不上传**。

**根因**：`KdbxAutoUploader.drain()` 的 while 条件只看 DB 的
`hasPendingUpload(status)`；`sync` 一开头 `markStatus(SYNCING)` 会冲掉
`PENDING_UPLOAD`，并发/紧随的另一次同步再把它洗成 `IN_SYNC` 时，
整轮上传根本不跑。而 `runCatching { drain }` 把异常吃得一点不剩。

**修法**：`hasWork = hasPendingUpload || coordinator.hasCachedLocalEdits`；
缓存脏标记只在 `write()` 成功后才清，是更稳的真源。`enqueue` 失败留
`VaultixKdbxUpload` 日志。补反证单测「状态已 IN_SYNC 但缓存脏 ⇒ 仍触发上传」。

### 「有时不显示上传」与「慢」

- 三次 Room 写库常在几十毫秒内合并成一次失效通知 ⇒ 详情页一帧转动都收不到。
  ⇒ `ItemDetailViewModel.publishSyncStatus` 给上传态加 **1200ms 最短可见窗口**：
  只延长展示、不改写数据层状态；期间重新进入上传态会取消旧 job 重新计时。
- 「慢」拆两半：① `20:05` 的 `read=8035ms` / `5574ms` 是缓存线格式
  `VKC1 → VKC2` 升级导致旧缓存一次性失效、且两个 room 各下一次，**一次性**；
  ② `20:10:21 → 20:10:24` 的 3.3s 是 `stat()` + 整库条件写，OneDrive 固有成本。
  ⇒ 不为了"感觉慢"去动同步核心，先做可观测与状态可见。

### KDBX 银行卡：闸门过时，已解除

用户问「XC 高级里有很多属性，能否作为内容接入 KDBX；XC 支持银行卡吗」。

- **XC 没有原生银行卡条目类型**，但「高级」页就是任意自定义字符串字段，
  社区常用 `card_*` 这类字段承载卡号/有效期/CVV。
- 查代码发现 `KdbxItemWriter.applyPayloads` 早在 11-01 就把 Card / Identity /
  SSH 写成自定义字段载荷（`card_*` 正是 **KeePassXC 的 Bitwarden 导入器
  用的生态标准键名**），`KdbxItemMapper.toVaultCard/Identity/SshKey` 双向读回，
  卡号与 CVV 按 `EntryValue.Encrypted` 保护。
- ⇒ A3 时代的「KDBX 只能建登录/安全笔记」是**纯 UI 层的历史限制**，共三处：
  `ItemTypePolicy.selectableItemTypes`、`KdbxItemRepository.KDBX_SUPPORTED_TYPES`
  （仓储硬闸，不解除则 UI 放开也保存不了）、`cardWalletFabVisible` 的 KDoc。
  三处一起解除，白名单改成 `VaultItemType.entries.toSet()` 防再次漂移；
  同步改 UI 文案、单测与导入路径注释。
- 卡包页「+」被挡时不再静默消费请求，改为明确提示原因。

### 回收站：设计决策，按只读行展示

`KdbxItemMapper.toMappedContent` 有意把回收站整棵子树排除出 `folders`
（对齐 Bitwarden 软删语义，且 `guardedGroupUuid` 禁止删/改名回收站组）。

⇒ **不改数据流**（放开会让用户能点删除却被静默拒绝），而是在
`FoldersScreen` 用 `observeTrash(vaultId).size` 画一条**只读**行：
XC 里那个分组在手机上可见，但不给菜单。这是"看得见"与"不能破坏"的折中。

### 判据（可复用）

1. UI 闸门放开前，必须沿写入链查到**最后一道**校验；只改 UI 常白做
   （本批差点漏掉仓储 `requireSupportedType`）。
2. 「状态流 + 缓存」双真源时，判定"还有没有活"要以**只在成功后清除**的
   那个为准；DB 状态会被并发覆盖。
3. 真机"有时不显示"先看**状态窗口是否被合并**，别先改数据源；最短可见
   窗口只延长展示、不编造状态。
4. 平台能力问题先查自家代码再查外部：本项目数据层早已支持卡字段，
   限制只活在上层策略与注释里。
