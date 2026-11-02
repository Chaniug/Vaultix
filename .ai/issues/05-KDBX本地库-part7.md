# 05-KDBX本地库 · 第 7 篇（#232 ~）

> 主篇索引：[`05-KDBX本地库.md`](./05-KDBX本地库.md)（含计数规则与重号警告）
> 本篇 2 章 · 覆盖 #232 ~ #233
>
> ⚠️ **本篇的共同形态与第 6 篇相反**：第 6 篇是"结局写反了"，本篇是
> **保障本身成了代价**。两道防线（写后读回校验 / 条目写回后的本地补写）都正确，
> 但都**无条件**用了最重的手段 —— 一个每次保存多下一次整库，一个把本地文件的
> 版本令牌永远留成旧值。⇒ 本篇的验收不是"能不能省"，而是
> **「省掉这一步有没有精确报红的用例」**。

---

## 232. OneDrive 每次保存都把远端整库重下一次做读回校验（2026-11-02，用户点名「onedrive 同步」）

- **现象**（真机会明显感知）：库在 OneDrive 上，改一条密码保存 ⇒ 上传 2 MiB，
  紧接着**再下载 2 MiB** 回来比对。蜂窝网络下每次保存多走 2 MiB 下行；
  弱网上保存要多等好几秒。库越大越明显，而 KDBX 库带附件到几十 MiB 很常见。
- **根因**：`OneDriveKdbxFileSource.write` 里那道读回校验是**无条件**的。
  它本身是对的（#172 的第二道闸，覆盖"服务端忽略了我们的条件头 / 传输中被截断"），
  但当时的取舍写成了一句"KDBX 库通常几百 KB，可接受" —— 一旦库到 2 MiB 以上，
  这个"可接受"就不成立了，而**没有任何地方会提醒你它不成立**。
- **⚠️ 第一版修法是错的（写测试时自己撞出来，记下来）**：
  第一版加了"远端 `lastModifiedDateTime` 明显早于本次写入 ⇒ 判不一致"这条判据。
  它**透支了证据**：
  - 时间戳语义是"内容最后一次被修改"，上传进行中它随时可能被 Graph 更新；
  - 而且它是**服务端时钟**，我们比的是**本机时钟** —— 中间隔着 NTP 漂移、
    用户手改时间、服务端秒级截断。

  实测：`OneDriveKdbxFileSourceWriteTest` 的 `写成功后必须读回一次并比对内容` 直接红了 ——
  装置里的假条目时间是 2026-10-17，而"现在"是 2026-11-02，于是**每一次正常保存**
  都会被判成"远端回滚"，用户会看到一个根本不存在的冲突。
  ⇒ 时间戳这条**降级为诊断线索**（`suspiciousTimestamp`），**永不参与结论**。
- **现在的判据（只有一条）**：远端记录的**大小**与写入字节数不等 ⇒ 内容不可能相同
  ⇒ 当场报冲突，连下载都省了。判定收在 `app/.../onedrive/OneDriveReadBackPolicy.kt` 的
  `readBackPlan()`（纯函数，不碰 HTTP、不碰 KDBX）。
- **⚠️ 反向绝不成立**：大小相符 ⇒ **仍然必须下载逐字节比对**。
  "长度对、内容坏"是这条路上最坏的形态（分片重发 / 传输截断都造得出来），
  而元数据对它一无所知。⇒ `readBackPlan` 没有"证明一致"这一档，
  只有「证明不一致」与「证明不了、去下载」。这是本轮**特意钉死**的地方
  （`大小相符时必须照旧下载一次并逐字节比`）。
- **顺带收敛的一处**：Graph 时间戳解析原先在 `OneDriveKdbxFileSource` 的 companion 里，
  现在移到 `OneDriveGraphClient` 的 companion —— 它有两个使用者
  （展示用 `KdbxFileStat.lastModified` / 判定用 `OneDriveEntry.lastModifiedMillis`），
  一份口径比两份更安全。解析失败给 `null` 而**不是 0**：0 会让"0 早于任何时刻"恒成立，
  凭空造出"远端是旧版本"。
- **省下来的量级**：正常路径（大小对得上）仍然下一次；**省掉的是**
  "上传结果本身就可疑"那一类 —— 大小不符时现在是**一次 stat 级别的比对**就结案，
  而不是先下 2 MiB 再说。⚠️ 诚实记录：这条优化对**正常保存**的流量**没有减少**，
  减少的是"出问题时白下一次整库"和"未来若把大小预筛做细"的空间。
- **验证**：新增 `OneDriveReadBackPolicyTest`（11 例），
  `OneDriveKdbxFileSourceWriteTest` +1 例（大小相符必须照旧下载）。
  变异验证（三组，均精确报红后还原）：

  | 变异 | 报红用例 |
  |---|---|
  | 大小判据改成恒 false | `大小不符必须直接判不一致` / `大小不符优先于时间戳诊断` |
  | 时间戳重新升成判死 | `远端时间戳明显偏旧也仍然要下载` / `刚好越过容差才标成可疑` |
  | `requiresDownload` 改成恒 false（永不下载） | `大小相符时必须照旧下载一次并逐字节比` / `写成功后必须读回一次并比对内容` / `读回失败必须抛冲突而不是当成功` |

- **⚠️ detekt 的一课**：三类失败各要一个 `throw`（元数据已证不一致 / 读不回来 /
  字节不一致），`ThrowsCount` 上限 2。**按结局的种类拆函数**（`verifyReadBack` 只判
  "该不该下载"，字节层两条进 `verifyDownloadedBytes`），而不是放宽阈值 ——
  放宽等于把这扇门关掉。

## 233. 条目保存后，远端缓存里的版本令牌被永远留成旧值（2026-11-02，用户点名「onedrive 同步」）

- **现象链**（三处各自"看起来对"，合起来是一条丢改动的路）：
  1. `KdbxItemRepository.persist` 写回成功后保存缓存：
     `fileCache.save(origin, CachedKdbxFile(bytes, previousToken))` —— 刻意沿用旧令牌，
     理由写得很清楚：它是下一次条件写的基线，换成新值就检测不到"远端变过"；
  2. 随后 `markLocalEdited` 把状态标成 `PENDING_UPLOAD`，自动上传器**立即**跑一轮同步；
     那一轮真的把字节推上去了，`markStatus(..., versionToken = report.writtenVersion)`
     把 `vaults.remoteVersionToken` 更新成新 eTag —— 但**缓存对象没动**；
  3. 下一次解锁：`CachedKdbxFileSource.read()` 有缓存就直接用缓存，
     于是 `stat()` 拿到的远端新 eTag 与**缓存里那个旧令牌**一比 ⇒ 永远不等；
     后台校验判"远端已变" ⇒ 重下一次（内容完全相同）、把新鲜度置成 `STALE`、
     调 `onRemoteChanged` ⇒ 状态被标成 `REMOTE_CHANGED`。
- **最终表现**：用户什么都没改，界面一直显示「远端有更新」；每次解锁都白下一次整库。
  而"远端有更新"角标是**要人处理**的那一类，用户会以为有冲突。
- **根因**：**两处真相**。`vaults.remoteVersionToken`（服务端给的最新版）与
  缓存里的 `versionToken`（这份字节对应哪一版）各存一份，而成功路径只更新前者。
  两者在"上传成功"这一刻**本该相同**，代码里却没有任何机制保证它们收敛。
- **修法**：新增 `CachedKdbxFileSource.recordUploaded(bytes, newVersionToken)`
  —— 上传成功后**把这两个真相一起刷新**：
  ```kotlin
  fun recordUploaded(bytes: ByteArray, newVersionTag: String?) {
      scope.launch {
          runCatching { cache.save(cacheKey, CachedKdbxFile(bytes, newVersionTag)) }
              .onFailure { error -> VaultixLog.w(TAG) { "上传成功后刷新缓存失败：${error.message}" } }
          // 刚上传成功 ⇒ 缓存 vs 远端**必然是同一版**，不需要再去 stat 一次。
          _freshness.value = Freshness.VERIFIED
      }
  }
  ```
- **⚠️ 为什么状态能被**直接**设成 `VERIFIED`**：这不是"猜"。
  刚刚那次 `write` 写完的就是 `bytes`，服务端回的新令牌就是它对应的版本 ——
  缓存现在装的正是这一版 ⇒ "缓存与远端一致"是**已知事实**，不是推断。
  再去 stat 一次只会多一次网络往返，而且还会把"刚上传成功"降级成"待校验"。
- **⚠️ 缓存刷新失败不得影响上传结果**：缓存是**优化**，不是正确性前提。
  ⇒ `runCatching` 吞掉并**留日志**（静默吞掉会让"缓存永远不更新"这类问题查不出来）；
  新鲜度照样置 `VERIFIED` —— 它随字节一起对，与缓存写没写成是两件事。
- **⚠️ 为什么用 `scope.launch` 而不是 `suspend`**：`KdbxSyncOrchestrator` 是纯决策层，
  它的 `sync` 不应因为一次本地缓存写而变成挂起函数（那条路径的可测性全靠同步签名）。
  代价是刷新与"记状态"之间有极小的窗口：此时若正好解锁，可能仍读旧缓存 →
  后台校验会**如实**把它判成 `STALE` 并重下一次，属于"多跑一次"而不是"报错状态"。
- **验证**：`CachedKdbxFileSourceTest` 新增 3 例
  （上传成功后缓存令牌必须刷新 / 新鲜度必须置 VERIFIED / 缓存写失败不得影响调用方与新鲜度）；
  `KdbxSyncOrchestratorTest` +1 例（上传成功必须把新令牌交给缓存）。
  详表见下方「本轮验证记录」。

---

## 本轮验证记录（2026-11-02 收尾）

| 项 | 结果 |
|---|---|
| `:data:kdbx:test` | 绿 |
| `:data:repository:testDebugUnitTest` | **244 例，0 失败 0 错误** |
| `:app:testFullDebugUnitTest` | **533 例，0 失败 0 错误** |
| `detekt` | 绿（过程中 `ThrowsCount` 红过一次，按"拆函数"改掉，未放宽阈值） |
| `check_issues_index` / `check_md_links` | ✅ 索引 = 题注 = 正文一致 / 185 文件链接全有效 |
| `check_orphan_strings --gate` / `check_ui_hardcoded_text` | ✅ 未超基线 / 无未登记文案 |
| `check_workflow_shell` / `check_workflow_secret_env` | ✅ |
| `check_experimental_optin` / `check_import_packages` / `check_signature_types` / `check_compile_smells` | ✅（500 文件） |

**变异验证汇总**（每条都跑过、看过红的是哪几例、再还原）

| # | 变异 | 精确报红 |
|---|---|---|
| 232 | 大小判据恒 false | 2 例 |
| 232 | 时间戳升成判死 | 2 例 |
| 232 | 永不下载 | 3 例 |

> **233 的变异第一次没红，记下来**：把协调器里 `onUploaded = { … }` **整行删掉**之后测试仍全绿。
> 查 `javap` 才发现 Kotlin 增量编译**没重编译**那个 class，跑的还是上次的产物。
> ⇒ 改用语义等价、但对编译器更"显式"的变异形态，三组各自精确报红：
>
> | 变异 | 报红用例 |
> |---|---|
> | `onUploaded = { _, _ -> System.err.println("变异：吞掉收敛") }` | `上传成功后_协调器必须把收敛回调接上` |
> | 删掉 `freshHolder.set(Freshness.VERIFIED)`（只刷缓存不置新鲜度） | 同上 |
> | `freshHolder = FreshnessHolder()`（装饰器每次自建容器） | 同上 |
>

## 四条教训

1. **保障本身也要算账**：读回校验是对的，但"每次多下一次整库"在库变大之后
   就不再"可接受"，而它**不会自己报警**。凡是把"通常很小"写进取舍理由的地方，
   都该有一条能随规模变化而被重新审视的判据。
2. **别用两种时钟比大小**：服务端时间戳 vs 本机时钟，隔着漂移、手改、秒级截断。
   用它做**诊断**（"可疑"）可以，做**结论**就是拿用户的数据赌运气 ——
   实测里它让每一次正常保存都报了一个假冲突。
3. **同一件事存两份，就必须有让它收敛的机制**：`vaults.remoteVersionToken` 与
   缓存里的 `versionToken` 是"同一版"的两个侧面，成功路径只更新一个 ⇒
   另一个永久漂移，而漂移的表现不是报错，是**一直挂着的假角标 + 每次白下一次**。
4. **变异后仍然全绿，先怀疑"没编译"，再怀疑测试**：删掉一行之后 Kotlin 增量编译
   有时不重编译那个 class（`javap` 才看得出跑的是旧产物）。⇒ 变异要选编译器**看得见**的
   形态（改赋值、改 lambda 体），而不是只删一行。

## 仍未做（如实记录，不假装齐了）

- OneDrive 分片上传后仍无"分片级校验和"：`putChunkWithRetry` 只能靠
  `nextExpectedRanges` 对齐进度，最终正确性仍押在整份读回上。
- SAF 来源的 `stat()` 每次都整份读算 SHA-256（它的令牌就是内容指纹）—— 同一个问题的
  本地版本，省法不同（要 mtime + size 的快速路径），本轮未动。
- 新建库 KDF 仍是 kotpass 默认（Argon2d / 32 MiB / parallelism 2 / iterations 8），
  改它需要两边真机实测耗时，**不靠猜**。
- `ACTION_SEND` + `EXTRA_STREAM`（分享面板「发送到」）未支持，理由见 part6 #231。
