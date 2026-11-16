# ISSUES 分篇 · 数据模型 / 同步 / 会话（part4）

> 返回索引：[`07-数据与同步.md`](./07-数据与同步.md)（编号 → 分篇 · 章节明细）
>
> ⚠️ 本文件是 **2026-11-16 追加**：part1~part3 合计 761 行，part3 已到 277 行
> （8.9 §7.1 上限 300 行），#294 正文放不下 ⇒ 另开一篇。
> `## NN.` 锚点格式与前面几篇一致，代码注释按 `#294` 引用时仍能 grep 到。

---

## 294. ⭐⭐ 推送 UPDATE 成功 ≠ 基线推进：第二次编辑必然被服务端拒（2026-11-16）

### 294.1 现象（真机原话）

> 「bitwarden 自建的服务器，我修改了条目，点击了同步，显示的是正常的，
> 但是服务器端却没有看见改动的内容。」

关键限定词：**自建服务器**（Vaultwarden）、**显示正常**、**服务端无变化**。
三者同时成立，说明请求要么没发出去、要么被拒了，而 UI 拿到的是「成功」。

### 294.2 根因：一条被丢弃的返回值

`BitwardenSyncService.flushPending()` 里这段（修改前）：

```kotlin
runCatching { executeOperation(api, effective) }
    .onSuccess { response ->
        if (effective.op == OP_CREATE) {
            remapCreatedLocalRow(effective, response)?.let { ... }   // ← 只有 CREATE 用
        }
        pendingOpDao.remove(op.localId)
    }
```

`OP_UPDATE -> api.updateCipher(...)` **是有返回值的**（`CipherResponse`，
内含服务端新的 `revisionDate`），但只有 CREATE 分支取用，UPDATE 的**整个丢弃**。

### 294.3 这条返回值为什么是"必须品"

`CipherMapper.toUpdateRequest()` 第 294 行：

```kotlin
lastKnownRevisionDate = stored.revisionDate.takeIf { it.isNotBlank() },
```

`stored` 来自本地行的 `encryptedPayload`。服务端拿这个字段做乐观并发校验 ——
Vaultwarden `src/api/core/ciphers.rs`：

```rust
if let Some(dt) = data.last_known_revision_date {
    match NaiveDateTime::parse_from_str(&dt, "%+") {
        Err(err) => warn!("Error parsing LastKnownRevisionDate '{}': {}", dt, err),
        Ok(dt) if cipher.updated_at.signed_duration_since(dt).num_seconds() > 1
            => err!("The client copy of this cipher is out of date. Resync the client and try again."),
        Ok() => (),
    }
}
```

⇒ 本地基线比服务端旧 **超过 1 秒**就**直接拒绝**（不是警告、不是容忍）。

于是：

```
① 第一次编辑 → 推送成功 → 本地 revisionDate 仍是编辑前那个值（本条缺陷）
② 第二次编辑 → lastKnownRevisionDate = 旧值 → 服务端 updated_at 早已前进 → 拒
③ 400 → 落在 DEFINITIVE_HTTP_CODES（401/409/408/429 之外的 4xx）
④ ⇒ 按「确定性失败」**弃单**：pendingOpDao.remove，本地行保留
⑤ ItemRepositoryImpl.flushAfterLocalWrite 对推送失败只返回 Queued
⑥ UPDATE 不是 CREATE ⇒ 不抛异常 ⇒ UI 显示「已保存」
```

**改动永久留在本机，服务端一无所知，全程零报错。** 这与用户描述逐字吻合。

### 294.4 为什么既有保护没兜住

| 既有机制 | 为什么没用 |
|---|---|
| `#91` 跳过 pendingIds 防覆盖 | 它防「拉取覆盖本地」，不防「推送被拒」 |
| `#190` 409 保留队列 | Vaultwarden 这里返回的是 **400**（`err!` 默认 400），不是 409 |
| `#148`「基线只在真跟上远端后推进」 | 纪律方向对，但只管 KDBX 的 `remoteVersionToken`，Bitwarden 侧**没有对应实现** |
| `#192` 空分组保护 | 无关 |
| 单测 108 条全绿 | 全部只测**失败分支的处置**，没有一条测「成功之后本地该收敛成什么」 |

⚠️ 这条最值得记住的一点：**#148 的纪律早就写在文档里，但只在一条链路上落实了。**
「某个判据只在 A 路径成立、B 路径没有」——这种半落实比完全没做更危险，
因为它会让人以为纪律已经覆盖。

### 294.5 修法

新增 `advanceLocalRevision(cipherId, response)`，在 UPDATE 成功分支调用：

```kotlin
if (effective.op == OP_UPDATE) {
    advanceLocalRevision(effective.cipherId, response)
}
```

函数体三个要点：

1. **两处都写**：`CipherEntity.revisionDate`（列）+ `encryptedPayload` 里
   `CipherDto.revisionDate`。只写列等于没修 —— `updateItem` 走的是载荷里那份。
2. **空串必须挡**：`takeIf { it.isNotBlank() } ?: return`。把非空基线写成空串，
   `lastKnownRevisionDate` 就变成 null ⇒ 服务端**放弃并发检测** ⇒
   多设备静默互相覆盖，比旧基线更坏。
3. **失败静默**：本地行已不在（别处删了）就跳过，不让整条队列卡住。

不改的地方（刻意）：`executeOperation` 的返回类型、CREATE 的 remap 语义、
400 的弃单判据（那是防毒丸的，改它会引入另一类丢数据）。

### 294.6 测试与反证

新建 `BitwardenSyncRevisionBaselineTest`（3 例）：

| 用例 | 守什么 |
|---|---|
| `UPDATE 推送成功后本地条目的 revisionDate 必须跟上服务端` | 主契约：列 + 载荷都推进 |
| `服务端未回 revisionDate 时保留本地原值不清空` | 绝不写空串（放弃并发检测） |
| `UPDATE 请求体携带本地 revisionDate 作为 lastKnownRevisionDate` | 入口条件：链的另一端 |

**首轮 3 红全是夹具的错，不是产品的错**（按纪律先查测试）：

- 第 1、2 例 `rowSlot.isCaptured == false` —— 这是**真缺陷**（`upsertAll` 根本没被调）；
- 第 3 例拿到 `lastKnownRevisionDate = null` —— **夹具错了**：我手写了
  `{"type":1,"name":"2.xxx"}`，真实 payload 由 `toUpdateRequest` 生成，必带该字段。
  改成 `updateOpWithBaseline(...)` 后转绿。
- 修完主契约后第 2 例仍红 —— 又是夹具在替产品发明语义（我断言"必须有 upsert 调用"，
  而正确行为是"直接 return，连写都不写"）。改成断言**结果性质**后转绿。

**行为反证**：临时删掉 `advanceLocalRevision` 调用 ⇒ 主契约立刻红；字节级还原 ⇒ 3 例复绿。

### 294.7 门禁

新增 `.ai/tools/check_bitwarden_revision_baseline.py`（4 组检查）+ 11 组变异自测，
接 `ci-debug.yml` 的 `android-ci`（两步，接在 #293 之后；steps 36 → 38）。

自测第 ⑥ 条曾抓到门禁自身的假绿：只检查 `copy(revisionDate = fresh)` 是否出现，
但把它挪到 `payload?.let` 外面、编码时仍传裸 `dto`，门禁照样绿。
补「编码那一行的参数必须带 copy + revisionDate」的正向检查后转绿。

### 294.7b CI 真实结论（观察到真实 run 后回填）

- 提交 `96e8728d`，**只推 `main`、未推 `rele`**（`origin/rele` 仍 `933d629d`）⇒ 正式 v0.13.4 不受影响。
- `AI docs consistency` run `43808735293` **success**（05:12:42Z）。
- `Android CI (Debug)` run `43808735246` **success**（05:12:42Z → 05:16:21Z，约 3m39s，**两 job 全绿**）。
  - `Check Bitwarden revision baseline advance (#294)` ✓，日志实证
    `✅ Bitwarden 推送后 revisionDate 基线契约通过（#294）`；
  - `Self-test Bitwarden revision baseline checker` ✓；
  - `Run detekt (quality gate)` ✓ `BUILD SUCCESSFUL in 12s` ——
    ⚠️ 这条要单独看一眼：给 `flushPending` 多加了一个 `if` + 一层 `runCatching`，
    而 detekt 圈复杂度上限是 14。**实测没超**，但下次再往里加分支要先数。
- preview Release `v0.13.4-dev-96e8728 · Preview`，资产 **`Vaultix-96e8728.apk`**
  （37,592,130 B ≈ 35.8 MB），#292 短命名约定同步生效。

### 294.8 长期判据

1. **服务端返回的版本令牌，是客户端的"下一次通行证"** —— 拿到了不存下来，
   下一次就过不去。凡是服务端在响应里回 `revisionDate` / `eTag` / `versionToken`
   的写接口，成功分支都必须问一句「这个值我存哪了」。
2. **"只给 CREATE 用"是一种常见的返回值浪费**：UPDATE 的响应体往往只带
   id + revision，看起来"没用"，但它是基线。
3. **弃单判据把 400 和 409 分开**是对的（409 能重试、400 不能），但前提是
   客户端不会**自己造出** 400。基线不推进就是在自己造 400。
4. 排「服务器没变化」这类问题时，第一个要问的不是"请求发出去了吗"，而是
   **"请求带的条件字段过期了吗"** —— 后者不会在日志里留下任何异常。

---

## 295. ⭐⭐⭐ 编辑已有条目时用错了钥匙：字段用账号密钥加密，而条目有自己的密钥（2026-11-16，真机）

> ⚠️ 与 #294 **不是同一件事**。#294 是「发不出去」（被服务端 400 拒），
> #295 是「发出去了、服务端也收了，但存下来的密文**别人解不开**」。
> 两条同时存在时会互相掩盖：#294 修好后症状变成「第一次编辑生效、第二次不生效」，
> 而 #295 的症状是「**每次**都不生效，但本地一切正常」。

### 295.1 现象（真机原话）

> 「我是修改或者在 bitwarden 库中已有条目里面，增加或者修改一条网址内容 点击保存，
> 保存本地能够显示，但是云端却没有修改后的内容。点击同步，发现服务器端的内容好像也没覆盖下来。」

三条同时成立，每一条都排除掉一批假设：

| 观察 | 排除掉的假设 |
|---|---|
| 保存后**本地**显示新值 | 编辑表单 / 保存路径没问题 |
| **云端**仍是旧值 | 服务端没收到，或收到了但不认这份密文 |
| 点同步**没被云端覆盖回来** | `pending_ops` 队列已清（否则 #91 的 `pendingIds` 保护会挡住覆盖） |
| 全程**无报错**，同步显示成功 | 不是 HTTP 失败，`DEFINITIVE_HTTP_CODES` / 409 都不沾边 |

⇒ 只剩一种可能：**PUT 成功了，服务端也存下了，但存下来的密文解不出用户的新值。**

### 295.2 根因：读用条目密钥，写用账号密钥

Bitwarden 里同时有**两把**钥匙在干活（这不是 Vaultix 的发明，是协议本身）：

| 加密谁 | 用什么钥匙 | 依据 |
|---|---|---|
| 条目字段（name / login / fields / …） | **该条目自己的密钥**（条目带 `key` 时） | 官方 `Cipher.toEncryptedNetworkCipher()`；Keyguard `buildCipherCodecPair` 的 `itemCrypto` |
| `key` 这个字段本身 | 账号密钥（组织条目用组织密钥） | Keyguard `keyBase64 … globalCrypto::transformBase64` |

Vaultix 此前：

- **读**方向是对的 —— `toDomain` 先解条目密钥，失败才回退账号密钥
  （`CipherMapper.decryptToString`）；
- **写**方向一律用账号密钥加密（`toRequest` / `toUpdateRequest` 里 10 处
  `crypto.encryptString(..., key)`），而且 `CipherRequest` **压根没声明 `key` 字段**。

于是「带 key 的条目」被编辑一次之后：

```
Vaultix 自己读   → 条目密钥失败 → 回退账号密钥 → 正常（本地看起来一切正常）
官方客户端读     → 只按 key 用条目密钥解 → MacVerificationException
服务端           → 只搬运密文，看不出问题；revisionDate 照常返回新值
                                  ↑ #294 的修复在这里让"看起来更成功"
```

> 真机库 2026-09-08 抓过数：219 条里 **31 条**带 `key`。这类条目在网页端
> 或官方 App 里新建/编辑过，很常见。

### 295.3 为什么既有测试全绿

`CipherMapperItemKeyTest` 只覆盖**读**方向（三个用例全是 `toDomain`）。
写方向没有任何用例，所以「写错了钥匙」在 CI 里是隐形的。

而**断言本身也很容易被写歪**：如果断言写成「Vaultix 自己能不能解开」，
本缺陷下**照样通过** —— 回退机制把错误盖住了。正确的断言是
**「其它客户端能不能解开」**。

### 295.4 修法

1. `CipherRequest` 增加 `@SerialName("key") val key: String?`（包裹态，原样透传）；
2. `toUpdateRequest` 先 `resolveItemKey(stored, key)`，用 `contentKey = itemKey ?: key`
   加密**所有可编辑字段**，`finally { itemKey?.clear() }`（条目密钥是复制实例，
   用完必须清零；账号密钥归会话所有，**绝不能**清）；
3. 请求体 `key = stored.key.takeIf { hasItemKey }`：
   - 有 ⇒ **原样带回**（服务端对 key 是整体替换语义，不带 = 清掉 ⇒ 字段仍是条目密钥
     密文 ⇒ 谁都解不开的死数据）；
   - 没有 ⇒ **必须 null**（凭空塞一个会让老条目在其它客户端跳到另一个加密域）；
4. `toStoredCipherDto` 顺延 `key`（`key ?: previous?.key`）—— 否则本地重建行时把 key
   丢了，同一缺陷换个入口复活；
5. `toRequest`（新建）**保持不变**：新建路径服务端还没有 key，一律账号密钥。

### 295.5 测试与反证

新增 `CipherMapperItemKeyWriteTest`（7 条），断言全部落在「**其它客户端**能否解开」：

| 用例 | 钉住什么 |
|---|---|
| 改URI后其它客户端必须能解出新值 | 用户报的那条原样复现 |
| 改名后其它客户端必须能解出新值 | 名称走同一条路径 |
| 改自定义字段后其它客户端必须能解出新值 | 字段走的是另一个 map 函数，最易漏 |
| 改密码后其它客户端必须能解出新值 | overlay 路径共用 |
| 没有条目密钥的条目写回时不带key | 不得凭空塞 key |
| 有条目密钥时写回必须原样带上包裹态key | 不得丢 key |
| 新建路径不携带key且可用账号密钥解开 | 新建语义未被误改 |

**行为反证**（实际跑过）：把 `contentKey` 改回 `key`（即缺陷形态），
上述 4 条主契约用例**全部变红**；还原后 121 条全绿。

### 295.6 门禁

`check_item_key_write.py`（源码形状）+ `selftest_item_key_write.py`（11 条真实变异）：
回退成账号密钥 / 声明了不传下去 / 不解条目密钥 / 某个字段仍用账号密钥 /
不带 key / 无条件带 key / DTO 少字段 / 不顺延 key / 用例被删 / 用错 sanitize 视图。

> ⚠️ 门禁自己踩了两个坑，都被自测抓出来（详见脚本注释）：
> ① `sanitize()` 会把 `@SerialName("key")` 抹成 `@SerialName("")` ⇒ 恒红；
> ② 同文件里有 **5 处** `@SerialName("key") val key`（`CipherDto`/`LoginDto`/`CardDto`/
>   `SshKeyDto`/`CipherRequest`），只搜全文 ⇒ 删掉 `CipherRequest` 那行也照样命中 ⇒ 假绿。
> ⇒ 必须**逐数据类括号配平切段**再判。

### 295.7 长期判据

1. **加密与解密必须用同一把钥匙**——这不是"最佳实践"，是正确性本身。
   凡是"读的时候有回退链、写的时候只有一把钥匙"的地方，都要当成缺陷看待
   （回退会把写错的错误**盖住**，让问题只在别的客户端上出现）。
2. **测试要断言"别人能不能读懂"，不是"自己能不能读懂"**。
   序列化 / 加密 / 协议字段这类跨端契约，自己读得回来几乎不提供信息量。
3. **"两边都没变"是最强的诊断信号**：云端没更新 + 本地没被云端覆盖 ⇒
   请求成功了、队列清了，那就只剩"存下来的东西不对"。
4. 一个字段在 DTO 里出现多次时（本文件里 `key` 出现 5 次），
   任何基于文本匹配的检查都必须**先定位到所属类型**。

### 295.8 真机验收判据

- [ ] 在网页端新建一条条目（带 key），Vaultix 里改 URI → **网页端**看到新值；
- [ ] 反向：网页端改 URI → Vaultix 同步后能看到；
- [ ] 老条目（2025 年前、无 key）编辑一次，官方 App 能正常解开（不得变成空值）；
- [ ] `adb logcat -s VaultixSync` 无 `definitive=true`。
