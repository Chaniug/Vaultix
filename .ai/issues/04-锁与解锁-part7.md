# 04-锁与解锁 · 第 7 篇（#248）

> 主篇索引：[`04-锁与解锁.md`](./04-锁与解锁.md)（含计数规则与重号警告）
> 本篇 1 条 · 2 节 · 覆盖 #248

## 248. 🟡 保存一次要跑两遍 30 秒 KDF + 「从不」档文案说反了（2026-11-04，#247 续修）

### 起点：用户「继续完成修复」

#247 把冷启动 30 秒收掉了（single-flight + 派生密钥落盘），收尾留了三条待办：
落盘缓存要不要 UI 开关（待拍板）、保存路径顺带缓存新版本派生结果、
`auto_lock_never_warning` 旧文案与实现不一致。本轮做后两条 —— 前一条是安全取舍，
仍等用户拍板，不擅自改默认值。

### 🔴 先更正 #247 的一处旧结论

[`04-锁与解锁-part6.md`](./04-锁与解锁-part6.md) §2.6 与「待办」里写
「`KdbxEncoder.encode` 不接 `KdfProvider` ⇒ 保存后新版本的盘条目失效，
下次冷启动会重算一次」。

**后半句是错的**：`KdbxRoundTrip.verifyEncoded` 早就用
`KdbxTransformKeyCache.providerFor(bytes)` 解码**新字节**，那次 decode 会把
新版本的 `transformedKey` 按 `digest(新字节) + digest(compositeKey)` 写进内存 + 盘。
⇒ 保存后（含冷启动）打开新版本**本来就命中**，不需要重算。

剩下、也是真正的那条：**`encode` 自己那一遍 KDF 白跑了**。
一次保存 = encode 一遍 30 秒 + `verifyEncoded` 一遍 30 秒 = **60 秒**，
而两遍的结果完全相同（同一个新 header、同一组 credentials）。
用户此前说的「点完覆盖就卡在页面不动」，体感来源就在这里。

> 教训与 #246「不必 fork kotpass」同类：**先数清楚 KDF 到底跑了几次，再谈优化**。
> 只盯着「缓存有没有命中」会漏掉「同一份结果被算了两遍」。

### 第 1 章：为什么 encode 那一遍接不上缓存

#### 1.1 鸡生蛋：providerFor 要先有字节

`providerFor(bytes)` 的 key 是整份文件字节的 SHA-256，可 encode 的顺序是：

```
regenerateVectors(random, ciphers)      // 换 IV / KDF 种子 / 加密随机流
  → KeyTransform.transformedKey(...)   // 跑一遍 Argon2（≈30 秒）
  → 写出 KDBX 字节                     // 到这里才有 bytes
```

字节最后才产出 ⇒ encode 期间既无法按内容哈希查缓存，也无法把结果按正确 key 存起来。
而且 KDF 种子刚换过，旧条目**本来就不适用** —— 拿旧 `transformedKey` 去加密新文件
就是写坏库，绝不能「复用旧缓存」。

#### 1.2 javap 核对过的三件事（kotpass 0.13.0）

| 事实 | 证据 |
|---|---|
| `encode` 确实收 `KdfProvider` | `EncoderKt.encode(KeePassDatabase, OutputStream, XmlContentParser, List<CipherProvider>, KdfProvider, SecureRandom)` |
| 默认 provider = `BaseKdfProvider.INSTANCE` | `encode$default` 的 mask `8` 分支 `getstatic BaseKdfProvider.INSTANCE` |
| encode 里只调一次 `transformKey` | `encodeAsBinary` → `KeyTransform.transformedKey(kdfProvider, header, credentials)` |

⇒ 与 `KotpassKdfBridge.defaultProvider()` 是**同一个对象**。注入一个包装它的 provider
不改变任何加密行为，只是多一次记账 —— 这正是 `KdbxEncoder` 旧注释
「显式传会引入两处装配漂移」在接了缓存之后不再成立的理由。

⚠️ `decode` 侧拿到的 `compositeKey` 与 encode 侧同源（都是
`KeyTransform.compositeKey(credentials)`），所以缓存 key 对得上；
万一凭据不同，key 不同 ⇒ 老实 miss，最坏只是多跑一次 KDF。

#### 1.3 修法：捕获 → 产完字节再提交

`KdbxTransformKeyCache` 新增三个东西：

| 新增 | 作用 |
|---|---|
| `CapturedKdf : KdfProvider` | 委托同源默认实现，只记一份 `(compositeKey, transformedKey)`；**不做缓存查找** |
| `captureProvider()` | 给 `KdbxEncoder.encode` 用 |
| `commitCaptured(capture, bytes)` | 拿到新字节后按 `keyOf(digest(bytes), compositeKey)` 写内存 LRU + 落盘 |

`KdbxEncoder.encode` 改成显式传 `kdfProvider = capture`，`toByteArray()` 之后立刻提交。
于是：

| 时点 | 修复前 | 修复后 |
|---|---|---|
| encode | 1 遍 KDF（结果丢弃） | 1 遍 KDF（结果被记下） |
| `verifyEncoded` 的 decode | 1 遍 KDF（结果写缓存） | **命中，0 遍** |
| 保存后下次打开（含冷启动） | 命中（本来就有） | 命中 |
| **一次保存合计** | **2 遍 ≈60 秒** | **1 遍 ≈30 秒** |

顺带把缓存 key 公式抽成**唯一的** `keyOf(contentDigest, compositeKey)`，
`getOrCompute` 与 `commitCaptured` 共用 —— 两处各写一遍迟早漂移，
而这里漂移的表现是「密码明明对却说密码错」。

#### 1.4 四道正确性守卫（写错缓存比多跑一次 KDF 危险得多）

1. **只认恰好一次派生**。`snapshot()` 在 0 次（没跑到 KDF）或 >1 次
   （kotpass 换了内部实现）时返回 null，直接放弃提交；空结果同样不提交。
2. **`enabled = false` / 空字节不写**。全局开关一关，缓存完全不参与。
3. **写盘失败 `runCatching` 兜住**，与 `computeJoined` 同一口径：
   缓存只是优化，不能把保存成功翻成失败。
4. **捕获错了不会写坏库**：`verifyEncoded` 会拿这份缓存去解自己刚写的字节，
   密钥不对就解不开 ⇒ `isSafeToWrite = false`，一个字节都不落盘。
   （这条是白捡的 —— 往返自检本来就在主写路径上。）

> ⚠️ 边界：encode 那一遍 KDF **省不掉**。`regenerateVectors` 换了 KDF 种子，
> 必须重新派生；想连这一遍也跳过就得改 kotpass 的 `regenerateVectors` 行为
> （等于改文件格式 / fork），不做。别承诺「保存零 KDF」。

### 第 2 章：顺带修 —— 「从不」档文案说反了

`VaultLockManagerImpl` 里 `VaultTimeout.Never -> return@launch`，
注释写得很清楚：「进程创建、切后台、用户切换 —— 一律不触发任何锁定动作，
密钥常驻内存，直到进程死亡」。

但 `auto_lock_never_warning` 写的是
「**离开 App 时仍会清除内存中的密钥**；但回到 App 会自动恢复」—— 与实现**相反**。
用户据此会以为切后台是安全的，实际恰恰是最该提醒的风险。

改成如实描述：不锁、密钥常驻内存直到进程结束、回到 App 无需再验证；
后半句「手机解锁状态下任何人都能打开 / 自动填充」原样保留（那才是真正的警告）。

### 测试

`KdbxTransformKeyCacheTest` 从 13 条扩到 **19 条**，新增 6 条：

| 用例 | 守住什么 |
|---|---|
| `保存路径捕获_按新字节命中_往返自检不再跑KDF` | encode 的结果被随后 decode 直接复用 |
| `保存捕获同时写盘_冷启动打开新版本仍秒开` | 不只写内存，盘上也得有 |
| `捕获零次或多于一次_都不写缓存` | 不确定该信哪一次就宁可重算 |
| `开关关闭_保存不写缓存` | 全局开关真的能整条关掉 |
| `空字节_保存不写缓存` | 异常输入不污染缓存 |
| `保存后旧版本字节_不误命中新版本缓存` | 文件字节变了必须 miss |

`CapturedKdf.record()` 把「记账」与「真跑 KDF」拆开，纯 JVM 单测不必构造
kotpass 的抽象类 `KdfParameters`。

### 验证（如实记账）

- detekt `--rerun-tasks` **全绿**。第一次抓到 `snapshot()` 的
  `ComplexCondition`（4 个条件 > 上限 3），拆成两段判据后转绿 —— 门禁真的在查。
- 纯 JVM 类型检查：**编译通过，276 个 class**。
- ⚠️ **脚本下 19 条里 18 绿、1 红**：`真实KDBX连续解锁两次…` 抛
  `NoClassDefFoundError: KotpassKdfBridge`。这是
  `.ai/tools/typecheck_kotlin.sh` 的已知盲区 —— kotlinc 只**解析** `.java`
  源码、不产出 class。本轮 encode 也接上 Java 桥后，这个洞从「被 `runCatching`
  吞成密码错」变成直接抛出来（与 #247「验证」那条同根）。
  ⇒ **补编译 Java 桥后真跑，19/19 全绿**：
  ```bash
  OUT="$HOME/.cache/vaultix-typecheck/out/single"
  CP=$(find "$HOME/.cache/vaultix-typecheck/libs" -name '*.jar' -printf '%p:')
  javac -proc:none -cp "${CP}${OUT}" -d "$OUT" \
    data/kdbx/src/main/java/io/vaultix/data/kdbx/KotpassKdfBridge.java
  java -cp "${OUT}:${CP}" org.junit.runner.JUnitCore \
    io.vaultix.data.kdbx.KdbxTransformKeyCacheTest   # OK (19 tests)
  ```
  CI 的 Gradle 单测会真编译 Java 桥，这条一直是绿的。
- 15 个 `check_*.py` 门禁：13 个直接绿；`check_orphan_state` /
  `check_orphan_strings --gate` 不接受位置参数，用正确调用方式重跑绿。
- ⚠️ `selftest_cross_file_private` 报 `VaultRepositoryImpl.kt` 的
  `buildFullKey` 跨文件 private，**未改动的 HEAD 基线同样红**
  （`git worktree add /tmp/… HEAD` 对照）⇒ 既有问题，与本轮无关。
- 出包仍交给 GitHub CI（本地不装 Android SDK，`testDebugUnitTest` 会因
  `SDK location not found` 失败）。

### 判据（真机复验）

`adb logcat -s VaultixKdbx`，编辑任一条目并保存：

- 出现一条 `派生密钥写入缓存（保存路径）⇒ 往返自检与下次打开跳过 KDF`；
- 同一次保存里**只应有一条** `派生密钥缓存未命中 ⇒ 执行 KDF`，
  而不是两条 `open(KDF+解析)≈30000ms`；
- 保存后杀进程重开该库：`派生密钥落盘缓存命中 ⇒ 冷启动跳过 KDF`；
- 设置页「从不」档提示不再说「会清除内存中的密钥」。

### 待办

- [ ] 真机按上面四条复验（保存耗时用数字说话）。
- [ ] 待拍板：落盘缓存 UI 开关 / 默认关（#247 §2.5 遗留，本轮未动）。
- [ ] `selftest_cross_file_private` 的既有红（基线同红，独立处理）。
- [ ] `KdbxEncoder.encode` 那一遍 KDF 本身仍需 ≈30 秒，除非改 kotpass 换种子行为。

## 265. 切库时 B/C 两个 KDBX 库互相锁定 + OneDrive 库指纹解锁很慢 —— 是设计，但时机错了（2026-11-10，真机反馈）

**现象**（用户原话）：

> 我有 bitwarden 库 A，放在 onedrive 上的 kdbx 库 B，以及本地的 kdbx 库 C。
> 随便在一个库点击切换库的时候，会发现 B 和 C 都会有一个被锁定，打开 C 的时候 B 会锁定，
> 打开 B 的时候 C 会锁定，而 A 库不受影响。而且 onedrive 上的库在被锁起来的时候，
> 需要验证指纹解锁，这时候指纹解锁的速度就很慢了。

### 先说结论：**互锁是设计，指纹慢是它的必然代价**

| 你的观察 | 判定 |
|---|---|
| B/C 互锁 | ✅ **设计如此**（`#60` 内存约束），不是状态错乱 |
| A 不受影响 | ✅ 设计如此（判据只在「进的是 KDBX」时成立） |
| 指纹解锁慢 | ❌ **这条是被放大的代价** —— 本轮要修的就是它 |

### 机制：一行调用 + 一句判据

```kotlin
// ItemsViewModel.init
runCatching { vaultRepository.lockOtherKdbxVaults(vaultId) }

// VaultRepositoryImpl
internal fun shouldLockOtherKdbx(keepKind: VaultKind?): Boolean = keepKind == VaultKind.KDBX
```

| 进入的库 | 结果 |
|---|---|
| Bitwarden A | 不锁任何 KDBX |
| KDBX C | 锁掉所有其它 KDBX（B） |
| KDBX B | 锁掉 C |

设计依据：`.ai/decisions/多库锁模型-定稿-part1.md` §3.4 明写「**维持不变**」——
KDBX 会话握的是**整库明文**，多库同时解锁违反「同时只能进一个库」的内存约束。
2026-11-04 还专门修过一次判据（旧版「进任何库都锁 KDBX」会连 Bitwarden 一起触发）。

### 为什么「慢」是同一个原因

项目里有**两层锁**，代价差一个数量级：

| | 查看锁（viewLock） | 真锁（`Kdbx.lock`） |
|---|---|---|
| 触发 | 熄屏门禁 / 主页锁按钮 | **切库** / 超时 / 手动锁 |
| 内存密钥 | 仍在 | **丢弃整库明文** |
| 解锁代价 | 指纹一次，瞬时 | 重读文件 + KDF + 解析整库 |

`openKdbxFromRoom` 有一条短路专为查看锁准备：

```kotlin
if (Kdbx.isUnlocked(vaultId)) { …; return RoomUnlockOutcome.Opened }   // 不重跑 KDF
```

而切库走的是**真锁**，短路永远不生效。于是每次切回来要付三段：

1. `stat()` 一次网络往返（OneDrive 库独有，缓存命中也要确认远端没变）；
2. Argon2 + 解析（KDF 缓存命中约 1.2s，未命中 3s+）；
3. 整库解析成条目列表。

⇒ OneDrive 库比本地库**多一次网络往返**，所以它的「慢」最刺眼。

### 修法：把约束从「数量维度」换到「时间维度」

`KdbxGraceLocks`（新文件）：进入新库时**不立刻锁**，改为 60 秒倒计时。

| 时刻 | 行为 |
|---|---|
| 进入库 X | 其它已解锁 KDBX 各起一份倒计时 |
| 60 秒内切回库 Y | **撤销 Y 的倒计时** ⇒ 会话还在 ⇒ 解锁瞬时 |
| 倒计时到点 | 真锁 + 清查看锁 + 代次自增 |
| 手动锁 / 退出 / 删库 / 全锁 | **立即**锁，并撤销该库倒计时 |

**为什么是 60 秒**：10 秒级覆盖不了「打开 B 看一眼，切回 C 抄个密码」；
5 分钟级又让 `#60` 的初衷落空。60 秒 ≈ 一次「查看 → 对比 → 返回」的自然时长。

**为什么不是「完全不锁」**：那会把内存约束彻底废掉。折中放在**时间**上
（只允许最近两个库在 60 秒内短暂并存），比放在「库数量」上更贴近真实使用。

### ★ 最容易被写错的一条：重复进入**不能续期**

若每次 `keep` 都重置计时，用户在 C 里多点几下别的库就能让 B 永远不到点 ——
那种实现看起来人畜无害（每次只是 `delay(60s)`），跑起来就是**永不锁定**，
而整个内存约束都建立在「最终会锁」之上。`KdbxGraceLocksTest` 有一条用例专门钉它：
中途 `keep` 一次，断言仍在第 60 秒锁。

### ★ 判据

> **安全约束可以从「数量维度」换到「时间维度」，但不能悄悄消失。**
>
> 「同时只能有 1 份明文」改成「同时最多 2 份，且第二份 60 秒后必然消失」——
> 上限仍是常数、终点仍然确定，只是把瞬时峰值让给了体验。
> 一旦改成「不锁」或「有空再锁」，约束就不存在了。

### 副作用与边界

- **不改判据**：`shouldLockOtherKdbx` 一个字没动，`ShouldLockOtherKdbxTest` 三条仍绿；
- **不改接口语义之外的行为**：`lockOtherKdbxVaults` 的返回值**不再代表「已经锁完了」**，
  已在接口 KDoc 写明；需要立刻锁的调用方走 `lockVault`；
- **未做「进后台立即 flush」**：收益只是后台少留 60 秒明文，而要多穿一层接口。
  真要更保守，改 `KdbxGraceLocks.DEFAULT_GRACE_MS` 一处即可。

### 测试

`KdbxGraceLocksTest`，7 条：到点才锁 / 切回撤销 / **重复进入不续期** /
cancel / cancelAll / 多库独立计时 / 到点后不残留。
