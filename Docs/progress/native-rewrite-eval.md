# 原生化评估：哪些地方值得用 Rust / Go 重写

> 建档：2026-09-18 · 应「接力任务收尾 + 给方案」的要求而写
> 上游：本轮完成了设置页 §2 / §3 / §4（`settings-rework.md`），本文是**收尾那半件事**
> 配套：性能现状见 [`perf-plan.md`](perf-plan.md)（真机基线在那儿，本文不复述）

---

## 0. 结论先行（**如果不看细节，只看这一节**）

| 结论 | 内容 |
|---|---|
| **① 现在不要动** | 全仓库**没有一个**已量化的性能问题在等 Rust/Go。`perf-plan.md` 里唯一未达标项是 **P6 切 Tab 掉帧（3.97~4.28%）**，那是 **Compose 重组范围**问题 —— **换成任何语言都治不了**。 |
| **② 真有收益的只有一处，且已经量化** | **KDF（密钥派生）**。项目当前用 **BouncyCastle 纯 Java** 跑 PBKDF2-HMAC-SHA256 600k 次，实测 **353ms**；同样的活由原生实现是 **97~133ms** ⇒ **2.7~3.6 倍**。它在**解锁这条唯一的必经路径**上。 |
| **③ 但先不要用 Rust 去拿这个收益** | 存在一个 **零新工具链**的中间选项（换 JCE/Conscrypt），能吃掉大部分收益。**先量真机，再决定要不要上 Rust。** |
| **④ 如果一定要引入一门语言 ⇒ 选 Rust，不要选 Go** | Go 走 `gomobile bind` 会把 **Go runtime 整包打进 APK**（每 ABI 数 MB ~ 十 MB 量级），且 Android/ARM 侧长期不是它的主场。Rust 走 `cargo-ndk` + UniFFI 产出的是**普通 `.so`**，体积与可控性都好一个量级。 |
| **⑤ Rust 的真正价值不只在"快"** | 见 §3.3：BC 是**为了跨设备确定性**才被选中的（JCE 有 provider 编码差异的历史）；Rust 能同时拿到 **确定性 + 原生速度**，这是 JCE 给不了的。另外 Argon2 的 JVM 回退会在 **Android 堆上**分配 64MiB（代码里已写明是"实打实的线上崩溃点"），原生实现能把这个风险整个移走。 |

> ### ★ 一句话
> **"为了快而引入 Rust" 不成立；"为了在解锁路径上同时拿到确定性与原生速度" 才成立，而且它排在一次真机测量之后。**

---

## 1. 先立判据：**什么样的东西才配得上"重写成 Rust/Go"**

按这个顺序过滤，过不了第一条的就没必要往下谈：

| # | 判据 | 为什么 |
|---|---|---|
| 1 | **它是 CPU-bound，并且已被测量确认是瓶颈** | IO / 等待 / 重组导致的慢，换语言一律无效 |
| 2 | **原生实现能带来 ≥2 倍的常数级改进** | 换语言的成本是**结构性的**（见 §5），1.2 倍不值 |
| 3 | **这段代码是纯算法、跨边界调用次数少** | JNI 单次调用有固定开销；**在热循环里逐项跨边界会比纯 Kotlin 更慢** |
| 4 | **它不碰 UI** | UI 的一切都在 Compose/Skia 里，与语言无关 |
| 5 | **现有实现要么没有原生版，要么原生版有附加代价** | 已经有 JNI 原生库（如 Argon2）的，先看看是不是用法问题 |

---

## 2. 实测：**KDF 是唯一被量化的热点**

### 2.1 怎么测的（可复现）

参数对齐项目默认值：`VaultixCrypto.DEFAULT_PBKDF2_ITERATIONS = 600_000`，
PBKDF2-HMAC-SHA256，输出 32 字节。同一台机器、同一组输入：

| 实现 | 命令 | 实测 |
|---|---|---|
| **BouncyCastle 纯 Java（项目当前）** | `PKCS5S2ParametersGenerator(SHA256Digest())`，跑 3 次取最快 | **353.3 ms** |
| JCE `PBKDF2WithHmacSHA256` | `SecretKeyFactory` + `PBEKeySpec` | **132.7 ms** |
| OpenSSL 3.0.13（C 原生） | `openssl kdf -keylen 32 -kdfopt digest:SHA2-256 -kdfopt iter:600000 PBKDF2`，3 次共 0.38s | **≈127 ms**（含进程启动，实际更低） |
| Go 标准库（`crypto/hmac` + `crypto/sha256`，复用 `hash.Hash`） | 见附录 | **97.5 ms** |

⇒ **纯 Java : 原生 ≈ 2.7 ~ 3.6 倍。**

### 2.2 ⚠️ 这组数字的用法边界（照 `perf-plan.md` §0.5 的纪律）

- 环境是 **x86-64 + HotSpot JIT + SHA 扩展**的服务器，**不是 ART**。
- 但方向只会被放大，不会反转：**ART 的 JIT 不如 HotSpot 激进，手机单核也弱得多**，
  纯 Java 版在真机上的相对劣势**只会更大**。
- ⇒ **这组数字只用来判"量级与方向"（值得查），不能当真机结论（不能据此改代码）。**
  照 `perf-plan.md` 的铁律：**必须在 release 包 + 已解锁态下再采一次真机数。**

### 2.3 为什么它在要害上

`UnlockViewModel.submit()` 的注释自己写着：

> 「PBKDF2 / Argon2 派生是**秒级 CPU 活**（Bitwarden 默认 600k 次迭代），必须离开主线程」

⇒ 它已经在 `Dispatchers.IO` 上了，**主线程不卡** —— 所以这块**不是"卡顿"问题，是"解锁要等"问题**。
它值不值得优化，取决于**用户感知**：解锁是每次冷启动都要过的一道门，600k 次派生的
**绝对耗时**直接等于"按下登录到进列表"的等待。**这是本仓库里唯一一处"确定存在的秒级等待"。**

---

## 3. 候选清单（逐条过 §1 的五条判据）

### 3.1 ✅ 候选一：PBKDF2-HMAC-SHA256（Bitwarden `KdfType=0`）

| 项 | 内容 |
|---|---|
| 现状 | `core/crypto/…/Kdf.kt` → `pbkdf2Sha256()` → **BouncyCastle 纯 Java** |
| 判据 1（CPU-bound + 已量化） | ✅ 已量化，2.7~3.6 倍（§2） |
| 判据 3（跨边界次数） | ✅ 一次解锁 **1 次**调用，天然适合 |
| 判据 5（现有原生版） | ⚠️ 有（JCE），但见下 |
| **预期收益** | 解锁等待 **−60%~70%**（待真机确认） |

★ **一个必须先想清楚的点**：项目为什么放着更快的 JCE 不用、偏要用 BC？
`Kdf.kt` 的文件头写得很直白 —— **JCE 的 `PBEKeySpec` 只接受 `char[]`，而 MasterKey 的
seed 是任意二进制**；且跨 Android 版本/provider，密码的**字节编码**并不保证一致
（历史上出过 ISO-8859-1 / UTF-8 的差异）。⇒ **BC 买的是"跨设备确定性"。**

⇒ 于是三条路的性质很不一样：

| 路线 | 速度 | 确定性 | 新增工具链 |
|---|---|---|---|
| 保持 BC | 1.0× | ✅ 自己控编码 | 无 |
| 换 JCE/Conscrypt | ≈2.7× | ⚠️ **依赖于 provider 的编码实现**（不同 ROM/版本有差） | 无 |
| **换 Rust（自实现 HMAC-SHA256 + PBKDF2）** | ≈3× | ✅ 自己控编码 | **有** |

⇒ **Rust 在"PBKDF2"这一项上的真正卖点是"确定性与速度同时拿到"，而不是单纯更快。**
这也是本文唯一一处认为"引入 Rust 有独立价值"的地方。

> ⚠️ 但顺序不能反：**先做 §4 的阶段 0（真机测量）**，确认"解锁等待"真的被用户抱怨、
> 且真的有几百毫秒量级。若实测只有 150ms，**整件事就不值得做了**。

### 3.2 ✅ 候选二：Argon2id 的 **JVM 回退路径**（含 PIN 解锁）

| 项 | 内容 |
|---|---|
| 现状 | `deriveArgon2WithFallback()`：**native（argon2kt）优先 → 失败回退 BouncyCastle** |
| 已经原生了吗 | **主路径已经是原生**（`argon2kt` = JNI）⇒ 主路径**不是**候选 |
| 为什么回退路径仍是候选 | 代码里自己写着：回退会「**在 Android 堆上一次性分配同等大小的块**，是实打实的线上崩溃点」，且用 `Argon2MemoryGuard`（堆上限/2）与 `ARGON2_JVM_FALLBACK_MAX_MEMORY_MB = 64` 两道闸去挡 |
| **收益的性质** | **不是"更快"，是"少一个崩溃模式"** —— 原生实现在 JVM 堆**之外**分配，内存护栏这道复杂度可以直接删掉 |

⇒ 这一项的排序应**高于** 3.1：**它同时消除一个已知崩溃点并简化代码**，
而 3.1 只是"少等一会儿"。⚠️ 但它只在**回退被触发时**才生效 —— 先确认回退在真机上
到底会不会被触发（若永不触发，收益为 0）。

### 3.3 ⚠️ 候选三：KDBX 打开/保存（kotpass：XML + Argon2 + AES）

| 项 | 内容 |
|---|---|
| 现状 | `data/kdbx`（`kotpass 0.13.0`，纯 Kotlin）+ `KdbxEngine` |
| 判据 1 | ❌ **没有测量**。不知道大库（数千条目 / 数 MB）打开要多久 |
| 判据 3 | ✅ 一次打开一次调用，边界清晰 |
| 风险 | ⚠️ **高**：KDBX 的保真度（`KdbxFidelity`）、变体兼容（`KdbxCredentialCandidate`）是**本项目的核心资产**，替换等于重写一条已被真机验证过的链路 |

⇒ **结论：先测量，不先动手。** 判据：真机上打开一个 5000 条目的 KDBX 若 > 2s，
才立项；且**只换"加密变换 + XML 解析"这两段，保真度逻辑留在 Kotlin**。

### 3.4 ❌ 明确不做

| 项 | 为什么不值得 |
|---|---|
| **AES-GCM 加解密**（`core/crypto/AesGcm.kt`） | 走 JCE ⇒ Android 上已是 **Conscrypt/BoringSSL 原生**。换 Rust 只是把一次 native 调用换成另一次 native 调用 |
| **P6 切 Tab 掉帧** | 性质是 **Compose 重组范围**（`SaveableStateHolder` + `AnimatedContent`）。**与语言无关**，重写成什么都一样掉帧 |
| **列表滚动 / 搜索 / 过滤** | 滚动实测 **Janky 0.0%**，已达标；搜索是内存里几百~几千条的字符串匹配，Kotlin 足够 |
| **Room / SQLite** | IO 与事务，不是 CPU 算法 |
| **网络层（Bitwarden / WebDAV / OneDrive）** | IO-bound；且 OkHttp 已在 native 侧 |
| **冷启动 763ms** | 主要是 Hilt / Compose 首帧组合，换语言无收益 |
| **JSON / CSV 导入导出** | IO + 一次性操作 |
| **UI 任何部分** | 见上 |

---

## 4. 落地方案（三阶段，**阶段 0 不做完不许往下走**）

```
阶段 0  测量（1~2 天，无代码改动）
  ├─ 0.1 真机 release 包：解锁一次 Bitwarden 库（KdfType=0，600k），
  │      从「点登录」到「列表可见」分段打点，把 KDF 那一段单独掐出来
  ├─ 0.2 真机：Argon2 回退到底会不会被触发（打日志统计一次）
  └─ 0.3 真机：打开一个大 KDBX（目标 ≥2000 条目）计时
  ⇒ 出口判据：拿到三个数。**任何一个没到「值得做」的量级，对应那条就到此为止。**

阶段 1  Kotlin 层就能拿到的（0 新工具链）
  ├─ 1.1 若 0.1 的 KDF 段确实是大头：评估换 JCE/Conscrypt，
  │      并**用一轮跨设备验证抵掉 provider 编码差异的风险**
  │      （判据：同一密码在 ≥3 台设备/ROM 上派生出**同一个** MasterKey）
  └─ 1.2 与 3.2 的取舍一起定：若 Argon2 回退在真机上根本不触发，
         ⇒ 直接把回退路径的**内存护栏复杂度**降级，别为它引入 Rust

阶段 2  只在阶段 1 不够时才上 Rust（定向替换，不是模块重写）
  ├─ 2.1 新建 `native/crypto` 一个 Rust crate（workspace 之外，独立 Cargo 工程）
  ├─ 2.2 用 **UniFFI** 生成 Kotlin 绑定（比手写 JNI 少一整类错误）
  ├─ 2.3 **只导出两个函数**：`pbkdf2_sha256(seed, salt, iter, len)` 与
  │      `argon2id(pwd, salt, t, m_mb, p)`。就两个，别多
  ├─ 2.4 `core:crypto` 里把 `pbkdf2Sha256` / `deriveArgon2WithFallback` 的实现
  │      换成"调原生；失败仍回退 BC" ⇒ **保留现有健壮性设计（勿删，那是 Bastion 搬来的）**
  └─ 2.5 门禁：**866 个单测必须全绿** + 新增一条"与 BC 实现逐字节对比"的交叉测试
```

---

## 5. 代价清单（**做之前先把这些摆到桌上**）

| 代价 | 说明 |
|---|---|
| **构建** | 需要 NDK + Rust 工具链；`cargo-ndk -t arm64-v8a -t armeabi-v7a -t x86 -t x86_64`；本地与 CI 都要装 |
| **CI** | `.github/workflows/release.yml` 要加 Rust 缓存与交叉编译步骤；**release 是 push `rele` 就触发**，构建链变长 = 出错面变大 |
| **体积** | 4 个 ABI 各一份 `.so`。只做 PBKDF2 + Argon2 的话量级在**几百 KB 级**；若开启 ABI split / AAB 则单设备只下一份 |
| **调试** | native crash 走的是 tombstone，不再是 Java 栈；**解锁路径出错 = 用户进不去库**，风险等级最高 |
| **许可证（GPL-3.0）** | 选 crate 只用 **MIT / Apache-2.0 / BSD**（Rust 生态主流即这些，不冲突）；但**必须**在 `NOTICE` / 第三方声明里列清楚 —— 本项目对 Bastion / Keyguard / bitwarden 都有溯源声明，标准要一致 |
| **人员** | 之后每个动 crypto 的人都要能同时读 Kotlin 与 Rust |

**Go 为什么不选**（对比用）：

| | Rust（cargo-ndk + UniFFI） | Go（gomobile bind） |
|---|---|---|
| 产物 | 普通 `.so`，直接进 `jniLibs` | `.aar`，**内含 Go runtime** |
| 体积量级 | 百 KB 级 | **数 MB ~ 十 MB 级 / ABI** |
| Android 支持 | 一等公民（NDK 官方支持） | `gomobile` 维护活跃度低，ARM/Android 侧长期非主场 |
| 绑定 | UniFFI 生成 Kotlin，类型安全 | 生成 Java，可用但笨重 |
| 内存 | 无 GC，可精确控制在堆外分配 | 自带 GC 与 runtime 调度 |

⇒ 结论清楚：**要引就引 Rust。**

---

## 6. 验收判据（写死，防"感觉快了"）

| 阶段 | 判据 |
|---|---|
| 0 | 三个真机数字入库（解锁 KDF 耗时 / Argon2 回退触发率 / 大 KDBX 打开耗时） |
| 1 | 若换 JCE：≥3 台设备派生结果**逐字节一致**，且解锁耗时下降 ≥40% |
| 2 | 若上 Rust：① 与 BC 实现**逐字节对比**的新增测试存在且绿；② 866 个既有单测仍全绿；③ 真机解锁耗时下降 ≥50%；④ APK 体积增幅 ≤1MB（开启 ABI 分包后单设备 ≤500KB）；⑤ release 构建链在 CI 上**连续两次成功** |

⚠️ 全程遵守 `perf-plan.md` §0：**不换 release 包就不下结论**；
遵守 §0.5：**同条件对比**（口径/解锁态/系统缓存冷热必须一致）。

---

## 7. 附录：可复现的基准命令

**JVM（BouncyCastle，项目当前路径）与 JCE 对照**

```java
// 依赖：org.bouncycastle:bcprov-jdk18on（本项目锁 1.85.2）
PKCS5S2ParametersGenerator g = new PKCS5S2ParametersGenerator(new SHA256Digest());
g.init(passwordUtf8, saltUtf8, 600_000);
byte[] key = ((KeyParameter) g.generateDerivedMacParameters(32 * 8)).getKey();
// 预热后再跑 3 次取最快
```

**OpenSSL（C 原生参照）**

```bash
openssl kdf -keylen 32 -kdfopt digest:SHA2-256 \
  -kdfopt pass:'correct horse battery staple' \
  -kdfopt salt:user@example.com -kdfopt iter:600000 PBKDF2
```

**Go（标准库，注意必须复用 `hash.Hash`，否则 600k 次 `hmac.New` 会把数字打高 2.6 倍）**

```go
mac := hmac.New(sha256.New, pw)      // 只建一次
for i := 1; i < iter; i++ {
    mac.Reset(); mac.Write(t); t = mac.Sum(t[:0])
    for j := range result { result[j] ^= t[j] }
}
```

> 实测踩坑：第一版 Go 在循环里 `hmac.New` ⇒ **257.7ms**；改成复用后 **97.5ms**。
> ⇒ **基准里"每次迭代重新分配"是最容易骗到自己的一处**，写基准时先确认它。

---

## 8. 与既有文档的关系

| 文档 | 分工 |
|---|---|
| [`perf-plan.md`](perf-plan.md) | **性能现状与真机基线的唯一真源**（P0~P7、测量协议）。本文的数字**不覆盖**那里的任何结论 |
| [`settings-rework.md`](settings-rework.md) | 本轮完成的 §2/§3/§4 施工单 |
| 本文 | **"要不要用 Rust/Go"这一件事**的方案与建议。**结论会随阶段 0 的实测更新** |
