## 297. 🔴 同步之后日志里**只剩一行**「revision 未变」⇒ 改动静默丢失且无法定位（2026-11-16，真机）

> 现场：`vaultix_logs_20261116_155316.txt`，`0.13.4-dev-d881fb3`（**已含 #295 与 #296**）。
>
> ```
> 15:51:54.315  VaultixSync: sync skipped: revision 未变（<phone>35）
> ```
>
> 全文件只有这一条 `VaultixSync`。**没有 `flushPending`，没有 422，什么都没有。**

### 与 #296 的对比：日志反而变少了

| 日志 | 时间 | `VaultixSync` 内容 |
|---|---|---|
| `..._142908.txt` | 14:28 | `flushPending op=UPDATE ... code=422 definitive=true` + `sync skipped` |
| `..._155316.txt` | 15:51 | **只有** `sync skipped: revision 未变` |

同一个操作、同一个库、同一个用户。#296 之前能看见 422，#296 之后连推送尝试都看不见
⇒ 排查直接失去着力点：无法区分两种**修法完全不同**的故障。

| 可能 | 该改哪里 |
|---|---|
| 改动**没进队列**（写路径没跑 / 被前置条件挡住） | `ItemRepositoryImpl.updateItem` |
| 改动进了队列但**被清掉**（推送路径丢了） | `BitwardenSyncService.flushPending` |

### 根因：两条路径都"成功了但什么都不说"

`BitwardenSyncService.flushPending` 开头：

```kotlin
val ops = pendingOpDao.listByVault(vaultId)
if (ops.isEmpty()) return Result.success(Unit)   // ← 静默
```

`ItemRepositoryImpl.flushAfterLocalWrite`：

```kotlin
val flush = syncService.flushPending(vaultId, server)
if (flush.isSuccess) return VaultSaveOutcome.Synced   // ← 静默
```

于是「队列为空」这条最关键的中间事实**完全不可观测**。而 `executeSync` 在
`revision` 未变时会照常返回 `Skipped`，上层 `BitwardenSyncOrchestrator` 的
`Skipped` 分支会记 `lastSuccessAt`、清零 `attempt`、不排重试
⇒ **界面显示"同步成功"是符合当前代码逻辑的**，问题在于这个"成功"没有包含
"有没有东西被推上去"这一层信息。

### 为什么会变成"队列为空"（上一轮遗留的判断题，本轮不下结论）

本轮**不假装已经定位到 422 的具体字段**（那需要服务端响应体）。但为了下一轮不再空转，
把两条候选路径的判据写清楚：

- 若是**写路径**没跑：`updateItem` 会在 `sessions.keyOf(vaultId) == null` 时抛
  「库未解锁，无法保存」——用户会在 UI 看到红字，但**日志里一条都没有**（本轮一并补上）。
- 若是**推送路径**丢了：`flushPending` 会打印「开始：待推送 N 条」但收尾「剩 N 条留在队列」。

### 静态排查：请求体值层面也排除了（续 #296）

- `BitwardenJson`：`ignoreUnknownKeys = true`、`explicitNulls = false`、**未开 `encodeDefaults`**
  ⇒ 所有 `null` 字段在出站 JSON 里被**省略**（不是发 `null`）。
- `buildUpdateRequest` 里"改 URI"路径上的取值：
  `type` = `mapTypeToInt()`（有界 Int）、`reprompt` = `repromptToInt()`（有界）、
  `login.uris[].match` = `matchToInt()`（有界）、`uri`/`name` = EncString 或 null。
  ⇒ **不存在能凭空造出反序列化失败（422）的非法值**。
- 与 #296 本地起 Vaultwarden 1.30.0 / 1.33.0 同构 PUT **全部 200** 的结论一致。

⇒ 结论同 #296：**请求体结构与本轮涉及的字段值都已排除，必须靠服务端响应体或
队列状态来定位**。本轮把"队列状态"这条证据链接上了。

### 修法（已落地）

1. **空队列必须留痕**（`flushPending`）：
   `flushPending 队列为空（无待推送 op）⇒ 本次同步只做拉取，不会上传任何改动`（WARN）。
2. **开头报意图**：`flushPending 开始：待推送 N 条（UPDATE=1 CREATE=2）`
   —— 按 op 类型聚合，见 `List<PendingOpEntity>.countByOp()`。
3. **收尾报结果**（`logFlushSummary`）：
   `flushPending 完成：N 条全部推送成功，队列已清空` /
   `flushPending 完成：推送 X 条成功，剩 M 条留在队列`。
4. **写路径统一出口留痕**（`flushAfterLocalWrite`）：
   成功 `本地写回完成并已推送：<vault> 无待推送残留`；
   失败 `本地写回已落库，但推送失败（…），改动仍在队列等待重试`。
5. **共用同一个 tag**：`ItemRepositoryImpl` 新增
   `private const val SYNC_TAG = "VaultixSync"`，与 `data:bitwarden` 一致
   ⇒ 用户一次 `adb logcat -s VaultixSync` 就能看到「入了什么队 + 推了什么」，同一条时间线。
6. **日志铁律不动**：摘要**只聚合 op 类型**，不得出现条目 id / 明文 / 密文
   （`countByOp` 里出现 `cipherId` 即判红）。

### 验证

- 新增 `BitwardenSyncFlushObservabilityTest` 3 例：
  ① 队列为空必须留 WARN 且含"队列为空"/"不会上传"；
  ② 推送成功必须报条数**且不含条目 id**；
  ③ 推送失败必须说明"剩 M 条留在队列"。
  `data/bitwarden` cleanTest 后 **124 例全绿**（121 + 3）。
- **行为反证**：把空队列分支改回 `return Result.success(Unit)` ⇒ 用例 ① 立刻红；
  字节级还原后复绿。
- 新增 `check_sync_observability.py` + **14 条真实源码变异自测**，接 `ci-debug.yml` 两步
  （`android-ci` 42 → 44 步）。
- 24 个门禁 / 20 个自测本地全绿；`data:repository` **352 例全绿**；detekt 通过。

### 门禁自身的三个坑（都已修，并写进自测）

| 坑 | 表现 | 修法 |
|---|---|---|
| `sanitize()` 抹掉字符串字面量 | 所有"日志文案必须有某词"的断言**假红**（#295 同款） | 结构判断走 sanitize，**文案判断走原文**（`_raw_method`） |
| KDoc 里也写着 `return Result.success(Unit)` | 朴素 `find()` 命中**注释**，分支体被截成空 ⇒ 假红 | 用行首锚定的正则只找**语句** |
| 按"下一个 `fun` 为止"截取函数体 | 下一函数的 KDoc 里也有"留在队列"⇒ 删文案的变异**假绿** | 做**花括号配平**（`_brace_body`） |

> 第二条与第三条都是"注释与代码用词雷同"导致的，**这是本仓库的高频坑**
> （#295 的 `@SerialName("key")` 也是同源）。凡"按字符串判源码"的门禁都要先想这句。

### 长期判据

- **"成功"必须带证据**：任何一个返回"成功"的入口，都应该能在日志里回答
  「做了什么 / 做了几条」；否则成功与"什么都没做"在观测上完全等价。
- **空集合不是"没事"，是"一个关键事实"**：队列为空 / 结果为空 / 跳过集合为空，
  都要显式说出来，不能靠"没有日志"来表达。
- **写路径与推送路径必须能串起来**：共用 tag + 都用"条数 + 类型"的语言，
  排查时才能一眼看出改动是在哪一段断掉的。
- **不要在没有观测手段时继续改业务逻辑**：本次若继续猜请求体字段，就是第三轮瞎猜。

### 待真机验收（直接决定下一步）

用新包复现一次并回传日志，**以下两句必出现其一**：

```text
A) VaultixSync: flushPending 队列为空（无待推送 op）⇒ 本次同步只做拉取，不会上传任何改动
B) VaultixSync: flushPending 开始：待推送 1 条（UPDATE=1）
```

- 出现 **A** ⇒ 故障在**写路径**（改动压根没入队），下一轮查 `updateItem` 与 `flushAfterLocalWrite` 之间；
- 出现 **B** ⇒ 故障在**推送路径**，紧跟的 `flushPending op=UPDATE ... code=... body=...`
  会直接给出服务端拒收原因。

> 同时确认保存时是否有 `本地写回完成并已推送` 或 `本地写回已落库，但推送失败` 一行 ——
> 有它就能确定"点保存那一刻"到底发生了什么，不必再靠"本地能看到"来推断。
