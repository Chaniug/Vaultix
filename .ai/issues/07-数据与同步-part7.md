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

---

## 补：写路径的第一道闸门会卡死（同批 #297，2026-11-16）

上面那套日志只解决了"看得见"，但排查过程中顺着「A 判据」（队列为空）往回走，
在写路径的入口上发现了一个**能让队列永远为空的真 bug**，且完全符合用户描述。

### 现象与代码

三个入口都用了 `saving` 当**防连点闸门**：

```kotlin
fun updateItem(item: VaultItem) {
    if (current.saving) return          // ← 闸门
    _state.update { it.copy(saving = true) }
    viewModelScope.launch {
        val outcome = itemRepository.updateItem(...)   // ← 抛异常
        _state.update { it.copy(saving = false) }      // ← 永远走不到
        ...
    }
}
```

复位写成了调用**之后**的一句。只要调用抛出，`saving` 就**永久停在 true**，
之后每一次点保存都被开头的 `if (...saving) return` 静默吞掉：

| 用户感受 | 实际发生 |
|---|---|
| 内容改了，本地也看得到 | 那一次确实落库了 |
| 再改再保存 | **一行都没写**，`saving` 卡住后所有保存直接 return |
| 点同步 → "同步成功" | 队列空 ⇒ 无可推送 ⇒ `Skipped` ⇒ 前端归入成功类 |

而仓储**确实会抛**：`ItemRepositoryImpl.createItem` / `updateItem` 里用的是
`keyOf(vaultId) ?: error("库未解锁…")`、`cipherDao.get(id) ?: error("条目不存在…")`
—— 是 `error(...)`（抛），不是 `Result.failure(...)`（返回）。返回值类型写着
`Result` 容易让人以为"不会抛"，这是一处**签名与实现不一致的陷阱**。

### 顺带查出的第二个问题：异常会炸 App

`viewModelScope` 是监督作用域，`launch` 里逃出去的异常直接进
`CoroutineExceptionHandler`。所以这些"预期内的失败"（库未解锁、条目不存在）
过去是**要么崩、要么静默**，另一端没有正常路径可走。

### 修法（三处，同一口径）

| 文件 | 入口 | 修法 |
|---|---|---|
| `ItemDetailViewModel` | `updateItem` | 复位移入 `finally`；成功后补一句 `写路径收口` 日志 |
| `ItemsViewModel` | `createItem` | 复位移入 `finally` + `catch (e: Throwable)` 收口成 `SaveEvent.Failed` |
| `AutofillSaveViewModel` | `saveNew` / `updateExisting` | 复位移入 `finally`（否则遮罩永久停在「保存中」） |

`createItem` 的 catch 顺序（**这个顺序不能换**）：

```kotlin
} catch (e: CancellationException) {
    throw e                       // 取消必须原样上抛，不能当成保存失败
} catch (e: Throwable) {
    Result.failure(e)             // Catch 边界本就该宽：见下
} finally {
    _state.update { it.copy(saving = false) }
}
```

> ⚠️ `Throwable` 而不是 `Exception`。原因有两层：① Android 上 `mockk` 把「没打桩的
> 调用」落成 `AssertionError`（属 `Error`），`Exception` 接不住 —— 这是自测里真实
> 踩到的；② 能让写路径中断的东西本来就该全留痕并提示用户，而不是挑异常族。
> detekt `TooGenericExceptionCaught` 用**函数级** `@Suppress` + 就近注释说明理由
> （与 `Kdbx.kt` / `VaultixCrypto.kt` 的先例一致）。

三个入口的日志共用 `VaultixSync` tag（`ItemDetailViewModel` 与 `ItemsViewModel`
各自补了 `private const val SYNC_TAG = "VaultixSync"`），于是**一次保存的完整链路**在
`adb logcat -s VaultixSync` 下是连续的一串：

```text
写路径中断：createItem 抛出 IllegalStateException ⇒ 本地未落库、队列未入队
   ↑ 或者
写路径收口：updateItem 已返回（成功）· <itemId>
flushPending 开始：待推送 1 条（UPDATE=1）
flushPending 完成：1 条全部推送成功，队列已清空
   ↑ 或者推送失败时
flushPending 完成：推送 0 条成功，剩 1 条留在队列
```

### 验证

`app/src/test/java/io/vaultix/vaultix/ui/items/ItemsViewModelSavingGateTest.kt` 三例：

| 用例 | 防的回归 |
|---|---|
| 仓储抛异常时 saving 闸门必须复位 | 复位退回"下一句" |
| 闸门复位之后第二次新建仍会真的打到仓储 | 异常没收口（第二次连不上）/ 闸门吞掉后续调用 |
| 闸门本身仍生效_保存中再点一次不会再打仓储 | 为了复位把 `if (saving) return` 整个删掉 |

**三条变异反证**（改完立刻 `cp` 还原并确认复绿）：

| 变异 | 结果 |
|---|---|
| 复位移回"下一句" | ✅ 转红 |
| `catch` 收窄成接不住的一类异常 | ✅ 两例转红 |
| 删掉 `if (_state.value.saving) return` | ✅ 第三例转红（前两例仍绿 ⇒ 证明第三例不是冗余） |

> 第三条变异最初是**绿的** —— 前两例抓不到"闸门被删"，因为闸门只在"上一笔未结束"
> 时才起作用。补第三例（仓储 `awaitCancellation()` 卡住第一笔，再点第二次并断言
> `coVerify(exactly = 1)`）才把它钉住。**"复位"与"闸门"是两个机制、故障表现相反，
> 必须分别有用例**。

### 一处自测坑（值得记）

`coEvery { ... } throws X andThen Result.success(...)` 在 mockk 1.13.x 上会解析到
`andThen(Answer)` 重载，第二次直接抛 `MockKException: no answer found` —— 表观症状是
"第二次调用没成功"，极容易误判成业务 bug。改用 `coAnswers { if (计数++ == 0) throw ... }`。

### 结论

- 这是"队列为空"的**一个**候选成因，但**不等于**用户这次就是它（他那台机器是否卡过，
  日志里没有证据）。修掉它是因为它确实会让队列永远为空、且症状完全吻合。
- 真正的定论仍等真机日志的 **A / B** 判据。
