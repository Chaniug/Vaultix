## 296. 🔴 HTTP 422 被当"确定性失败"弃单 ⇒ 改动静默丢失，「同步成功」是假的（2026-11-16，真机）

> 现场：`vaultix_logs_20261116_142908.txt`，`0.13.4-dev-abeb7bc`（**已含 #295**）。
>
> ```
> 14:28:52.805  VaultixSync: flushPending op=UPDATE id=d19f28e7-… code=422 definitive=true conflict=false
> 14:29:03.471  VaultixSync: sync skipped: revision 未变（…35）
> ```

### 现象与链条

用户在已有 Bitwarden 条目里改了网址，本地显示新值，服务端始终没有改动。
详情页「修改于」也不动。用户点同步，界面像成功了。

日志已经把链条钉死，不再需要猜：

1. `UPDATE` 请求被服务端以 **422** 拒绝；
2. 422 落在 `DEFINITIVE_HTTP_CODES = (400..499) - {401,409,408,429}` 里 ⇒
   `handleOperationFailure()` 判为"确定性失败"，`pendingOpDao.remove(op.localId)` **弃单**；
3. 队列空了，下一次 `executeSync()` 没有 op 可推；
4. 服务端没接受改动 ⇒ `revision` 没变 ⇒ `sync skipped: revision 未变` ⇒ 直接 `Skipped`；
5. 上层把 `Skipped` 当成功处理（记 `lastSuccessAt`、清零重试）。

⇒ **用户改动留在本机、服务端一无所知、界面报成功**。这是数据丢失，不是显示 bug。

### 与 #295 的关系（别混为一谈）

| | #295 | #296 |
|---|---|---|
| 服务端行为 | **收下**了密文，但只有 Vaultix 自己解得开 | **直接拒收**（HTTP 422） |
| 修的是什么 | 写方向用错钥匙（字段该用条目密钥） | 失败分类把 422 当 404 用 |
| 用户体感 | 云端永远旧值 | 云端永远旧值 + "同步成功" |

两条叠在同一条真机路径上，症状看起来一模一样。#295 修完后用户再测仍失败，
正是因为这第二条独立的缺陷。

### 静态排查：排除了什么（这部分别删，下次省两轮）

对照官方 `bitwarden/clients` 的 `libs/common/src/vault/models/request/cipher.request.ts`
与 `Cipher` 类的加密格式说明；对照 Vaultwarden 1.24 / 1.28 / 1.30 / 1.33 / main 的
`CipherData` 与 `put_cipher` 签名：

- Vaultwarden 的**业务错误一律 HTTP 400**（`Error::new_msg` 默认 `BAD_REQUEST`，
  源码里没有任何 `with_code(422)`）⇒ 422 基本只来自 Rocket 的 `Json<CipherData>` 守卫
  （缺字段 / 类型不符 / 非法 JSON）；
- `key` 字段在 1.30 / 1.33 / main 都是 `Option<String>`；1.24–1.28 没有该字段，
  未知字段被 serde 忽略 ⇒ **#295 新增 `key` 不是 422 的原因**；
- `JsonUpcase`（1.30 及更早）会把 camelCase 首字母转成大写，`name → Name`、
  `lastKnownRevisionDate → LastKnownRevisionDate` 依然对得上；
- 官方 `CipherRequest` 里我们没发的 `encryptedFor` / `encryptedByKeyId` / `data` /
  `archivedDate` 在服务端都是 Optional，"仅在发送时校验"；
- 本机起 Vaultwarden **1.30.0 与 1.33.0** 两个真实实例，用与 Vaultix 同构的请求体
  实测 `PUT /api/ciphers/{id}`（含 key / 不含 key / null key / 各可选字段），**全部 200**。

⇒ 请求体**结构**不是充分原因。缺的是服务端那句 `missing field ...` / `invalid type ...`。
**结论：不在没有证据时再改第三版请求体。**

### 修法（已落地）

1. **422 不再弃单**：新增 `HTTP_UNPROCESSABLE_ENTITY = 422`，
   与 409 / 408 / 429 / 401 一起从 `DEFINITIVE_HTTP_CODES` 排除，走"保留队列 + 累计重试"。
   420-ish 的语义是"这次请求没被接受"，不是"目标不存在"；弃单 = 丢用户数据。
2. **失败日志带脱敏响应体**：新增 `safeErrorBody(HttpException)`，
   `flushPending` 的失败分支写成
   `code=... definitive=... conflict=... body=...`。
   脱敏三件套：EncString（`2.iv|ct|mac`）→ `<enc>`、JWT 三段式 → `<jwt>`、
   连续 24 位以上 base64/JWT 片段 → `<redacted>`，最后限长 512、压平换行。
   下一次真机日志应能直接看到 Rocket 的具体字段错误。
3. **「修改于」不再骗人**：`advanceLocalRevision()` 在推送成功后把
   `vaultixLocalRevisionDate` 清成 null。`CipherMapper.toDomain()` 读的是
   `vaultixLocalRevisionDate ?: revisionDate`，不清的话这个导入期展示字段会永远压住
   服务端时间 —— 用户正是拿它判断"同步成功没有"。
   注意空串保护保留：`response.revisionDate` 为空时**不动**本地行（写了空 = 放弃并发检测）。

### 验证

- 新增 3 例行为测试：422 留队重试、错误体脱敏、清除本地展示时间；
  `cleanTest` 后 `data:bitwarden` **121 例全绿**（118 + 3）。
- 新增 `check_http422_policy.py` + **10 条真实源码变异自测**
  （422 回弃单集合、常量写错、日志丢 body、三种脱敏缺失、未限长、
  未清本地时间、两个用例被删），全红后再还原复绿。
- 顺手修正 #294 旧自测的两处锚点：#296 改变了 `advanceLocalRevision` 的排版
  （多行 `encodeToString` + 多清一个字段），旧变异用例的精确字符串锚点失效 ——
  换成按函数段/更小的 `dto.copy(` 锚点，11/11 复绿。
- 22 个静态门禁 / 19 个自测本地全绿；
  `compileFullDebugKotlin` / `compileFullDebugUnitTestKotlin` 通过。

### 长期判据

- **4xx 不是同一种东西**：400/403/404 = 目标不存在/不可执行（可弃单）；
  409/422 = "这次请求没被接受"（留队重试）。把它们混为一谈会静默丢数据（#190 已踩过 409）。
- **HTTP 失败日志只记 code 等于把定位权交给猜**，必须带脱敏响应体；
  日志脱敏是硬约束，密文/JWT 不得进开发者日志。
- **UI 上的时间字段若来自本地专用字段，必须显式说明并在同步成功后清除**，
  否则用户会拿一个不变的量当同步判据，反复误报。
- 服务端为什么拒收这类问题，**静态对照 + 本地复现只能缩小范围**；
  没有响应体时不要连续改请求体猜（本次先猜"缺 key"、再猜"字段结构"，都不对）。

### 待真机验收（关键，决定下一步）

用新包复现一次，回传开发者日志，重点看：

```text
VaultixSync: flushPending op=UPDATE id=... code=422 ... body=...
```

- 若 body 含 `missing field` / `invalid type`：按指名字段对齐官方 `CipherRequest`
  后补写方向修复；
- 若 body 是业务文案（400 的姿态却回 422）：按文案对应的服务端分支修；
- 若改动不再 422：`HTTP_UNPROCESSABLE_ENTITY` 的"留队"策略仍保留，
  避免下次再遇到同类拒收时静默丢数据。

> 同时确认详情页「修改于」在推送成功后变成新的时间；若仍不变，
> 说明时间不是从 `CipherDto` 走的，要继续查 `toDomain()` 的上游。
