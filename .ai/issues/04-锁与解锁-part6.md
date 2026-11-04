# 04-锁与解锁 · 第 6 篇（#247）

> 主篇索引：[`04-锁与解锁.md`](./04-锁与解锁.md)（含计数规则与重号警告）
> 本篇 1 条 · 2 节 · 覆盖 #247

## 247. 🟡 解锁页两个加载圈 + 冷启动 KDBX 仍要等 30 秒（2026-11-04，真机日志第六轮）

### 用户原话与两件事

> 「我给你日志，另外进入 kdbx 密码库的时候，加载圈怎么有两个，只需要一个即可啊」

同一轮里给了 `vaultix_logs_20261104_153848.txt` 和一张解锁页截图。
两件事看似无关，其实都指向「解锁页在等同一件慢事」：界面把 `submitting` 画了两遍，
而 `submitting` 之所以久到让人盯着数圈，是因为 KDF 要跑 30 秒。

### 日志（关键 8 行，包版本 `0.11.0-dev-f2af8ab`）

```
15:38:09.509  VaultixKdbxCache: 后台校验：缓存与远端一致
15:38:11.045  VaultixAutofill: fanout start first=onedrive:…/Keepass%2Fvalk.kdbx rest=0
15:38:38.241  VaultixKdbx: 派生密钥缓存未命中 ⇒ 执行 KDF（缓存 1/16 条）
15:38:38.578  VaultixKdbx: unlock 耗时分解：read=2ms（33926B）+ open(KDF+解析)=32000ms；
              本次 KDF 缓存命中 0 次（累计命中 0 / 未命中 1）
15:38:38.579  VaultixAutoRestore: autoRestore → rooms=2 opened=2 failed=0 …
15:38:41.360  VaultixKdbx: 派生密钥缓存未命中 ⇒ 执行 KDF（缓存 1/16 条）
15:38:41.466  VaultixKdbx: unlock 耗时分解：read=1ms（33926B）+ open(KDF+解析)=30381ms；
              本次 KDF 缓存命中 0 次（累计命中 0 / 未命中 2）
15:38:41.466  VaultixAutofill: fanout first=… → Opened
```

### 第 1 章：两个加载圈

**真因**：`submitting` 同时驱动了两处转圈 ——

| 位置 | 渲染 |
|---|---|
| `PasswordForm` 末尾 `if (state.submitting)` | 一个独立的 `CircularProgressIndicator`（截图里 PIN 下方居中的小圆弧） |
| `PrimaryActionButton(busy = state.submitting)` | 按钮内一个 `CircularProgressIndicator`（截图里「解锁」左侧的小圆弧） |

`ViewLockedContent` 同样有两处（独立圈 + 按钮 busy 圈），只是多带一行
`unlock_view_lock_verifying` 文案。

**修法**：全页只保留主按钮里的那一个圈。

- `PasswordForm` 删掉独立圈，并把那段「只留一个转圈」的老注释改写成
  「忙碌指示唯一 = 主按钮里的圈」。文案不补回来：真机 KDF 是 30 秒，
  「约需 1–3 秒」是误导；走指纹快速解锁时更没有「派生密钥」这回事。
- `ViewLockedContent` 把文案挪进按钮的 `busyText`
  （`PrimaryActionButton` 本来就有这个参数），删掉 `Row` + 独立圈。
- `CircularProgressIndicator` 的 import 随之删除。

> 为什么删独立圈、而不是关掉按钮的 `busy`：`busy` 还负责 **禁用按钮** 和给读屏播
> `action_in_progress`（`PrimaryActionButton.kt` 的 `stateDescription`）。
> 为这一页关掉它，等于把忙碌态的语义拆成两份真相（审计 §2.3 的老坑）。

### 第 2 章：30 秒从哪来，为什么 #246 的内存缓存没救到

#### 2.1 三个被日志钉死的事实

1. **KDF 单次 ≈ 30 秒**。`open(KDF+解析)=32000ms`，而 `read=2ms` ⇒
   网络 / 文件缓存 / OneDrive 全不是瓶颈。此前文档按「几百毫秒」估算是错的：
   这份真机库是 Argon2d 64 MiB / 46 轮，kotpass 的纯 JVM 实现跑出了 30 秒。
2. **冷启动内存缓存必然 miss**。用户场景是「退出 App → 再打开 → 切到 KDBX → 被锁」，
   #246 第五批的 LRU 只活在一个进程里，进程一死全没了 ⇒ `累计命中 0`。
3. **两条路径并发各跑一遍**。`autoRestore`（进程启动即开始）与 autofill `fanout`
   几乎同时起步，都在对方写回缓存之前查了一次 ⇒ 双双 miss。
   两条 30 秒 Argon2 抢内存带宽，谁都更慢。两次日志都写「缓存 1/16 条」，
   证明它们写的是**同一个 key**（不是两个库、不是两种凭据）。

⇒ 光有 LRU 挡不住「正在算」这个窗口，也挡不住进程重启。

#### 2.2 修法一：single-flight 合并同 key 并发 KDF

`KdbxTransformKeyCache` 增加 `ConcurrentHashMap<String, CompletableFuture<ByteArray>>`：

- 抢到 `putIfAbsent` 的线程负责跑 KDF，算完（成功或异常）`complete` 并 `remove`；
- 后来者阻塞在 `owner.get()` 上复用同一份结果，记一次「并发合并命中」；
- 失败走 `completeExceptionally` + 重抛 `cause`，**等待者不许永久挂住**。

⚠️ 没用 `computeIfAbsent` 直接在 mapping 里算：那会持着 bin 锁跑 30 秒，
不同 key 撞到同一 bin 时被无辜拖住，resize 期间还有死锁风险。
`transformKey` 是 kotpass 的**同步**接口，这里也不能 `await`（跑在 IO 调度器上，
阻塞调用线程是唯一选择）。

#### 2.3 修法二：派生密钥安全落盘（真正的冷启动秒解）

新增 `KdbxDerivedKeyDiskCache.kt`：

- `DerivedKeyDiskCache` 接口（`load` / `save` / `clear`），默认 `null`
  ⇒ **纯 JVM 单测不碰 Android Keystore / `android.util.Base64`**；
- `AndroidDerivedKeyDiskCache`：Keystore 里一把**不可导出**的 AES-256-GCM 密钥
  （独立别名 `vaultix_kdf_derived_kek`，与 `AutoUnlockKeyStore` /
  `LocalUnlockKeyStore` 分开），`iv.b64|cipher.b64` 每 key 一个小文件，
  目录级 LRU 上限 48 条；
- `VaultixApplication.onCreate()` 在 `VaultixLog.install` 之后、
  **任何解锁动作之前**调 `installKdbxDerivedKeyDiskCache(File(filesDir, "kdf_derived"))`；
- 查找顺序固定 **内存 → 落盘 → 并发占位 → 真跑 KDF**；
  盘上条目被 `takeIf { isNotEmpty() }` 兜底，解不开 / 空数据一律当成「没有」。

**失效判据仍然是内容哈希**：索引 = `SHA256(文件字节哈希 + compositeKey 哈希)`。
同步拉到新版本 / 保存后换 KDF 种子 / 改密码或 keyfile ⇒ key 变 ⇒ 老实重算。
⇒ **永远没有「拿旧密钥解新文件」的窗口**，最坏情况只是多等一次 KDF。

#### 2.4 清除时机：超时全锁**不删盘**

这是最容易做错的一刀。`VaultLockManagerImpl.lockAll()`（超时全锁 / 用户全锁）
→ `VaultRepositoryImpl.lockAll()` → `Kdbx.lockAll()` → `KdbxTransformKeyCache.clear()`。
若 `clear()` 连盘一起删，用户下次解锁又回到 30 秒 —— 等于没修。

| 调用 | 语义 | 动作 |
|---|---|---|
| `KdbxTransformKeyCache.clear()` | 超时全锁 / 用户全锁 | 只清内存 LRU + 并发占位 |
| `clear(includeDisk = true)` | `Kdbx.forgetDerivedKeys()`（退出数据库 / 删库 / 改主密码） | 连盘一起删 |
| `Kdbx.lock(vaultId)` | 单库锁定 | 两者都不清 |

⚠️ `forgetDerivedKeys()` 是**全局**清盘（盘上 key 由内容哈希构成，不含 vaultId，
这一层分不出是哪个库）。退出/删除其中一个 KDBX 库，会连带让其它库下次冷启动
各重算一次 KDF；正确性无损，只是低频操作后的一次性代价。要按库精确删，
得把 vaultId 传进 `transformKey`，那是另一个改动，本轮不做。

#### 2.5 ★ 安全取舍（默认开，但摆到台面上）

| 已有模型 | 落盘内容 |
|---|---|
| 快速解锁房间信封 | 主密码经指纹 KEK 包裹后落盘 |
| 「从不」档自动恢复 | 房钥匙经无认证 Keystore 密钥包裹后落盘 |
| **本缓存** | 32 字节 `transformedKey` 经无认证 Keystore 密钥包裹后落盘 |

拿到密文 + 能调用本 app 的 Keystore 别名就能解开这个库，暴露面与
`AutoUnlockKeyStore` 同级，且**不比已有的房间信封更弱**（后者落盘的是主密码本身）。
安全边界仍是：手机锁屏 + app 沙箱 + TEE。之所以不能绑生物认证：
KDF 跑在 kotpass 的同步 `decode` 里，那一层不可能弹 `BiometricPrompt`。

出口：`installKdbxDerivedKeyDiskCache(..., enabled = false)` 整条关掉，
退回 #246 第五批「派生密钥只在内存」的旧语义；`KdbxTransformKeyCache.enabled`
仍是全局开关。**是否要给设置页一个 UI 开关 / 改成默认关，仍待用户拍板。**

#### 2.6 边界（不要承诺过头）

- 新包**首次**解锁仍要付一次 30 秒 KDF —— 那一刻才把结果写进盘。
  从第二次（含冷启动 / 超时全锁后）起才是秒开。
- 用户**编辑并保存**后文件字节变化（`regenerateVectors` 换种子）⇒ 该版本的盘条目
  失效，下次冷启动会重算一次。没改条目的日常打开不受影响（日志里
  「后台校验：缓存与远端一致」正是这个情形）。
- 打开库剩下的 AES 解密 + XML 解析是毫秒级，`read` 已经是 1–2ms，
  所以命中之后的 `open` 应当掉到几十~几百毫秒，而不是 30 秒。
- `KdbxEncoder.encode` 不接 `KdfProvider`（它只传 `cipherProviders`），
  保存时的那一遍 KDF 没进缓存 —— 这是下一步可选的优化，不是本轮必须。

### 测试

`KdbxTransformKeyCacheTest` 从 7 条扩到 13 条，新增 6 条守：

| 用例 | 守住什么 |
|---|---|
| `冷启动后落盘命中_不再执行KDF` | 进程重启（`clear()` 只清内存）后不重跑 KDF |
| `全量锁定只清内存_不删落盘条目` | `Kdbx.lockAll()` 不能把盘删了 |
| `彻底忘记_连落盘条目一起删` | `clear(includeDisk = true)` 真删 |
| `落盘条目解不开_老实重算KDF并覆盖` | 坏密文绝不能被当成 `transformedKey` |
| `同一key并发_只跑一次KDF_另一条复用结果` | 日志里「未命中 2」不再发生 |
| `并发算KDF失败_等待者拿到异常而不是永久挂住` | `CompletableFuture` 不会卡死解锁 |

### 验证（如实记账）

- detekt `--rerun-tasks` **全绿**。第一次被抓到 `computeJoined` 的 `ThrowsCount`（3>2），
  把「等待者怎么失败」抽成 `awaitOwner()` 后转绿 —— 又一次是门禁真的在查。
- 15 个 `check_*.py` 脚本门禁全绿（含 `check_issues_index` / `check_md_links` /
  `check_ui_hardcoded_text`）。
- 纯 JVM 类型检查：**编译通过，275 个 class**；13 条用例里**新增 6 条全绿**。
- ⚠️ 脚本下 `真实KDBX连续解锁两次…` 报「密码不正确」。用 `git worktree` 建
  **HEAD 基线**跑同一脚本，**7 条里同一条也红** ⇒ 是 typecheck 脚本不生成
  `KotpassKdfBridge.java` 的 class（`runCatching` 把 `NoClassDefFoundError`
  吞成"密码错"）的环境限制，**与本轮改动无关**；CI 的 Gradle 单测会真编译 Java 桥。
  ⇒ 不能拿这条本地红灯去改代码，但也不能假装它绿了。

### 判据（真机复验）

`adb logcat -s VaultixKdbx`，退出 App 再打开、切到 `valk.kdbx`、指纹进入：

- 首次：一条 `派生密钥缓存未命中` + `open≈30000ms`（写盘）；
- 之后每次（含冷启动 / 超时全锁）：`派生密钥落盘缓存命中 ⇒ 冷启动跳过 KDF`，
  `open` 掉到几十~几百毫秒；
- 若同一时刻两条路径进来：应看到 `派生密钥并发合并 ⇒ 复用同一次 KDF`，
  而不是两条 `未命中`。

### 待办

- [ ] 真机按上面三条判据复验（`open` 数字说话，不再猜）。
- [ ] 待拍板：落盘缓存要不要 UI 开关 / 默认关。
- [ ] 保存路径顺带缓存新版本的派生结果（见 2.6）。
- [ ] `auto_lock_never_warning` 旧文案与实现不一致（历史遗留，见 SESSION §10）。
