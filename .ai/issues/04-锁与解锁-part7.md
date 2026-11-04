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
