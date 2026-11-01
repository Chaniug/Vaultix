# 快速解锁房子化-两级钥匙层级-定稿 · 第 2 篇

> 主篇索引：[`快速解锁房子化-两级钥匙层级-定稿.md`](./快速解锁房子化-两级钥匙层级-定稿.md)
> 本篇 1 章 · 16.1KB · 起于「§6 失效处理矩阵（比旧模型干净的地方：两类失效彻底分开…」

## §6 失效处理矩阵（比旧模型干净的地方：两类失效彻底分开）

| 失效 | 旧模型（钥匙串） | 新模型（房子） |
|---|---|---|
| 系统重录指纹 → 平台密钥失效 | N 个信封全死；KDBX 侧永不自愈（静默） | **开门状态**：rearm——用内存里的房子钥匙静默重包 1 次门锁信封（学 Keyguard `rearmBiometricIfNeeded`）；**关门状态**：降级禁用指纹锁 + 明确文案 + 回主密码 |
| 某库主密码在别处改了 | StaleCredentials → 输新密码自动重包（D3） | 同左，但重包的是**该房间的软件信封**，门锁不动 |
| 某房间信封损坏 | 该库快解死，KDBX 侧静默 Unknown | AEAD 完整性校验可判「坏」；只影响该库，可删可重建 |
| PIN 计数 | 每库独立 5 次 | **全局一份 5 次**（门锁是全局的）——比旧模型更严 |

### §6.1 实施记录（2026-09-29 批次 4 落地）

四类失效的**落地形态**与**为什么是这个形态**（施工见 `Docs/progress/quick-unlock-house-rework.md` 批次 4）：

| 目标 | 落地 | 关键决策与理由 |
|---|---|---|
| **rearm** | `HouseKeyStore.rearmFingerprintLock(cipher)` + 标记 `house_lock_fingerprint_rearm_pending`；`UnlockRecoveryRepository` 转出到 app 层 | 硬约束 #2（auth-per-use：一次授权只保一次 `doFinal`）⇒ **真正静默重包物理上不可能**，必须再弹一次 `BiometricPrompt`。故拆成「**失效时只打标记**（不弹窗）→ **下次认证时补写新信封**」的**延迟重装**（用户 2026-09-29 拍板「可以接受重新安装」） |
| **降级** | `UnlockRecoveryRepository.degradeFingerprintLock(): FingerprintDegradeReport` → `disableFingerprintLock()` + 回主密码 | **绝不静默**（硬约束 #5）：按 `roomsRemoved` / `remainingLocks` 三选一用户可见文案；无指纹信封时 no-op（不误伤房间信封） |
| **StaleCredentials** | `RoomResealRepository.resealRoom(vaultId, newMasterPassword)` 复用 `LocalUnlockEnrollment.prepareForVaults` → 只重包该房间软件信封 | `UnlockViewModel` 在 `RoomUnlockOutcome.StaleCredentials` 分支用**刚输入的主密码**重包；失败一律静默（不把「能进去」这个好消息变报错） |
| **PIN 熔断全局 5 次** | 批次 1 已把 N 信封收敛为 1 门锁信封 ⇒ 计数天然全局 | 本批无需改计数（旧每库计数随旧信封一起删除） |
| **auto 信封失效** | `HouseKeyStore.openAutoEnvelope` 返回 null ⇒ 就地删信封自愈（批次 3.5 已做，单测已钉） | 密钥不可用 / 信封损坏不打扰用户：Never 档的恢复能力本就"想恢复才要"，恢复不了退回普通解锁即可 |

**★ 本批最重要的一条判据**：开门 / 关门的**唯一正确判据**是 `HouseKeyStore.isUnlocked`。
误用「门锁信封存在」当判据会把 rearm 走成降级 —— 那会**连带清掉房间信封**（用户丢库）。
`UnlockRecoveryRepository` 的 KDoc 里有开门态 / 关门态的判定表。

**★ 与 Bitwarden 的有意偏离**：Bitwarden 生物识别解密失败即 `BiometricDecodingError`、
锁定计数达 5 次即登出（`VaultLockManagerImpl.kt:340-352`），**不做静默重装**。
Vaultix 选更软的延迟重装路径，前提是 **Bitwarden 没有的**：房子钥匙已在内存
（开门态）⇒ 重装只差一次认证，不需要用户重新走完整个登记向导。

### §6.2 实施记录（2026-09-29 批次 5.0：软锁 + 前台门禁 + 解锁提速）

> 触发：用户真机反馈两条 —— ①「设置了**从不**，锁屏/清后台后 Vaultix 直接就是开着的，
> 有风险」（对照 Bitwarden 会加锁）；②「指纹解锁进密码库要等好几秒，正常应秒解秒进」。
> 用户拍板**方案 A**：对齐 Bitwarden —— **离开 App 就真锁（密钥清零）**，
> 但回来靠恢复信封**免交互自动开**。

#### 核心洞察：软锁 vs 硬锁（唯一差别 = 信封的留与删）

| | 触发 | 房钥匙 | 恢复信封 | 回来自动开 |
|---|---|---|---|---|
| **软锁**（本批新增 `AutoUnlockRepository.softLock`） | 用户离开 App（划后台 / 锁屏） | **清零** | **保留** | ✅ 免交互 |
| **硬锁**（既有 `lockVault` / `lockAll`） | 点「锁定」/ 退出数据库 | 清零 | **删除** | ❌ 过门锁 |

> 这就是方案 A 与方案 B 的**全部分界**：删信封 = 回来要重新指纹（B）；留信封 = 回来自动开（A）。
> 旧 bug 的根因：`VaultTimeout.Never -> return@launch` —— 从不软锁 ⇒ 信封永久存在且密钥常驻内存。
>
> ⚠️⚠️ **上面这套「软锁 + 前台门禁」已在同日被 §6.3 推翻**（用户三次拍板「对齐 Bitwarden」）。
> **本节保留为历史记录，不要照它施工。**当前定稿 = §6.3。

#### 改动清单（⚠️ 已被 §6.3 部分回退 —— 见 §6.3 的「回退清单」）

| 位置 | 改动 |
|---|---|
| `domain/AutoUnlockRepository` | 新增 `suspend fun softLock(): List<String>` |
| `data/AutoUnlockRepositoryImpl` | `softLock()`：先 `houseKeyStore.lock()` 清房钥匙 → `sessions.lockAll()` + `Kdbx.lockAll()` 收两条会话模型 → **绝不** `removeAutoEnvelope()` |
| `app/AutoLockController` | 新增 `isForeground: StateFlow<Boolean>`（`onStart`/`onStop` 驱动） |
| `app/VaultLockManagerImpl` | `Never` 分支：`AppBackgrounded` 时调 `softLockForBackground()`（`AppCreated` 不调 —— 钥匙本就不在内存） |
| `app/AutoRestoreTrigger` | **前台门禁（B2，安全底线）**：`combine` 增第三源 `foreground`，非前台一律不恢复 |
| `app/UnlockViewModel` | ① `candidateVaultIds` 从「逐库订阅 `fingerprintQuickUnlockAvailable`」改为「一次读范围快照 + 内存求交」；② `completeLocalUnlock` 拆两段：先开核心库放行 → 异步补开其余库 |

#### ★ 两条不可省的判据

1. ~~**前台门禁是安全底线，不是优化（B2）**~~ —— ⚠️ **已被 §6.3 推翻**（门禁已删）。
   ~~软锁会把 `houseKeyInMemory` 翻成 false，而 `AutoRestoreTrigger` 正在 observe 这个值
   —— 没有门禁的话，软锁后该 combine 立刻触发恢复分支把钥匙读回内存 ⇒ 软锁从未发生。~~
   保留此条仅为说明门禁**为何曾存在**；当前 `AutoRestoreTrigger` **不判前台**。
2. **提速的关键是消除 N 次串行 Keystore 往返**：旧实现每库调一次
   `fingerprintQuickUnlockAvailable(id).first()`，而那条 Flow 内含
   `localUnlockKeyStore.keyAvailable`（一次 Keystore 往返，冷启动可达数百毫秒）。
   N 库 × 数百毫秒全串行，且卡在「认证成功 → 结果返回」之间 ⇒ 用户感知「指纹过了却要等好几秒」。
   正解 = 两个**全局事实**相乘：门锁可用性（全局一份）+ 生效范围（一次读偏好，零 Keystore）。
   ✅ **这条仍然有效，未被推翻。**

#### 与 Bitwarden 的对照（⚠️ 本节结论已被 §6.3 修正）

- ~~**对齐**：Bitwarden `setVaultToLocked` **无条件清** `userAutoUnlockKey`；Vaultix 软锁同样清密钥 ——
  「离开 App 后密钥不在内存」这条安全保证两侧一致。~~
  ⚠️ **误读**：`setVaultToLocked` 是**主动锁库**路径（用户点锁定 / 超时），Never 档根本不走它。
  Bitwarden 的 `Never -> return` 恰恰**不锁**。详见 §6.3。
- ~~**有意偏离**：Bitwarden 不在熄屏时锁库……Vaultix 在 `AppBackgrounded` 软锁~~ —— **已被 §6.3 回退**。
- ✅ **已补齐（2026-09-29 批次 5.2）**：Bitwarden 的亮屏时间补偿
  （`ACTION_SCREEN_ON` + `elapsedRealtimeMs` 重算剩余延迟）—— 见 §6.4。
- ⚠️ **2026-10-30 修正：「锁屏即锁」档位已做（`VaultTimeout.OnSystemLock`）**，本节原结论作废 ——
  不是因为它错了，而是**外部事实变了**：Bitwarden **2025.11.0** 新增了 `On system lock` 档位
  （Android 先行，2025.12.1 全客户端），原判断「Bitwarden 也没有这档」不再成立。
  实现细节与判定（严格 `isDeviceLocked()`、300ms 复查、`-3` 与旧键负数的冲突）见
  [`../issues/04-锁与解锁.md`](../issues/04-锁与解锁.md) **#214**。
  同一轮还补齐了 Bitwarden 的**两层锁**（`Never` 档：密钥不锁 / 界面验指纹），见同一节。
- ❌ **仍不做（已拍板）**：「软登出（LOGOUT）」档位。

---

### §6.3 实施记录（2026-09-29 二次定稿：**Never 档对齐 Bitwarden，取消离场软锁**）

> **用户拍板三次**（同日，逐次加重）：「对齐 Bitwarden（不软锁）」→「对齐bitwarden的设计方案吧」→「对齐bitwarden的设计理念吧」。
> 本节**推翻 §6.2**。§6.2 保留为历史（说明「为什么会先做错」）。

#### 为什么推翻 §6.2（这是本节最重要的内容，别再重走一遍）

§6.2 把 `VaultTimeout.Never -> return@launch` 判定为「**安全漏洞**」，理由是
「密钥常驻内存，进程被内存转储时可捞到」。**这个判断错了**，错在误判了 Bitwarden 的信任模型：

> Bitwarden 敢在 Never 档 `return`，**不是因为它有恢复信封兜底**，而是因为它信任
> **Android 的进程内存模型**：密钥只活在进程地址空间里，**进程一死就没了**。
>
> 「内存转储」这个威胁的前提是攻击者能拿到特权级访问（root / 物理取证）——
> **而那个前提下，Vaultix 的恢复信封同样会被解开**：信封用的是
> `AutoUnlockKeyStore`（`setUserAuthenticationRequired(false)`，**免认证**）。
> ⇒ **信封根本不构成额外的安全层**，它只把「进程死亡」这一个场景从「要指纹」
> 变成「不要指纹」——而这正是 Never 档的**定义**。

⇒ 结论：**软锁换来的「后台期间密钥不在内存」是有代价的伪安全**。
真正的安全边界是「**进程是否活着**」，而非「是否在前台」。§6.2 把这条边界搞错了。

#### 改动清单（本批实际落地）

| 位置 | 改动 |
|---|---|
| `app/VaultLockManagerImpl` | `Never` 分支回归 **`return@launch`**（任何原因都不锁）；删除 `softLockForBackground()`；构造参数移除 `AutoUnlockRepository`（不再需要） |
| `app/AutoRestoreTrigger` | **删除前台门禁**：`combine` 从三源（档位 × 钥匙 × 前台）回归**两源**（档位 × 钥匙）；构造参数移除 `AutoLockController` |
| `data/AutoUnlockRepositoryImpl` | `softLock()` **保留实现，但已无生产调用点**（原唯一调用者已删）；KDoc 标注「别接回 Never 档」 |
| `app/AutoLockController` | `isForeground` **保留字段**（`onStop` 仍配套驱动 `onAppBackgrounded()`），但已无生产消费者；KDoc 标注 |
| `app/AutofillActivity` | `prepareBiometricUnlock` 的「Never 档免交互恢复」分支**保留**，但理由更新：不再是「补门禁的洞」，而是「**主动按需恢复** vs `AutoRestoreTrigger` 的**被动 combine 响应**」的时序互补（fillRequest 可能早于 combine 首次求值 32ms） |
| 测试 | `VaultLockManagerImplNeverTest` **反转**为「切后台**绝不锁定**」；`AutoRestoreTriggerTest` **反转**为「不含前台门禁」（后台也恢复） |

#### ★ 本批的判据（与 §6.2 完全相反）

| 触发 | Never 档应做什么 | 守它的测试 |
|---|---|---|
| `onAppBackgrounded()` | **不锁**（密钥常驻内存至进程死亡） | `VaultLockManagerImplNeverTest.Never档_切后台_绝不锁定` |
| `onAppCreated()` | **不锁** | `Never档_进程创建_不锁定` |
| `onAppForegrounded()` | **不锁** | `Never档_回前台_不锁定` |
| 非 Never 档切后台 | **不立即锁**（由超时定时器负责） | `非Never档_切后台_不立即锁` |
| Never + 无钥匙 + 有信封 | **恢复**（⚠️ **不判前台**） | `AutoRestoreTriggerTest.Never且无钥匙_后台也恢复` |

#### 遗留：代价与未解问题（如实记录）

1. **接受的代价**：Never 档进程存活期间房钥匙常驻内存，`adb`/root 下可 dump。
   **与 Bitwarden 完全一致**（用户明确接受）。锁屏防线 = **系统锁屏**。
2. **第二三遍不弹**（用户真机反馈）与软锁策略**无关** —— 真因是荣耀/鸿蒙
   `AppFastHibernation` 激进冻杀（实测 `doze → unF_Z → fillRequest → FiStopAppProc`
   进程活不过 0.7s）。本批**不修**，另案。
3. **`isForeground` 现状为「无消费者」**：若下次有人要加「锁屏即锁」档，
   **不要**把它接回 `Never`，应**新增独立档位**（本定稿 §11 同款纪律）。

---

### §6.4 实施记录（2026-09-29 批次 5.2：**补齐亮屏时间补偿 + 删死字段 + 清理**）

> 用户指令：「**按你推荐的来吧。然后顺手也清理一下这个项目中无用过期的文件产物，日志等内容**」
> —— 「按推荐」= ① 删死字段 `isFromLockFlow` ② 做亮屏时间补偿 ③ **不做**软登出档。

#### 改动清单

| # | 改动 | 文件 | 对齐对象 |
|---|---|---|---|
| 1 | 删除死字段 `isFromLockFlow`（接口声明 + 实现字段 + 两处赋值） | `VaultLockManager.kt` / `VaultLockManagerImpl.kt` | —— （**无对应**：Bitwarden 有它，Vaultix 用 `viewLocked` 取代） |
| 2 | 新增 `onScreenOn()` + 注册 `ACTION_SCREEN_ON` | `VaultLockManagerImpl.kt` | Bitwarden `:737-749` `ScreenStateBroadcastReceiver` + `:156-159` 注册 |
| 3 | `TimeoutJobData.startTimeMs` 改用 `SystemClock.elapsedRealtime()` | 同上 | Bitwarden `:665` `realtimeManager.elapsedRealtimeMs` |

#### ★ 亮屏补偿的核心原理（改动前必读，别把它当"可选优化"）

协程 `delay(n)` 的计时**依赖线程**，而线程在**设备深度睡眠时被挂起**。后果：
**熄屏期间时间在走，定时器不走**。

> 举例：档位 = 后台 5 分钟锁。用户锁屏放兜里 30 分钟。若无补偿，
> `delay` 可能只推进了 2 分钟 ⇒ 用户掏出手机时**本应早已锁定的库还是开着的**。

补偿办法：亮屏时**不**让原 job 继续，而是按单调时钟重算剩余并重启。
`elapsedRealtime()` 在熄屏期间**照常累加**，这正是它区别于 `currentTimeMillis` 的关键
（后者会被改系统时间 / NTP 校时推动）。

#### 两处对 Bitwarden 的**有意收口**

1. **剩余钳到 `[0, durationMs]`**：Bitwarden 只对 `durationSoFarMs` 保下界，
   `delayMs` 理论上可为负。本项目统一 `coerceIn(0, durationMs)`，
   让「已超时」稳定退化为「立即锁」。
2. **显式 `toList()` 快照**：Bitwarden 靠 `map{}` 先快照规避"边遍历边改 map"。
   本项目写出显式快照，意图更直白（`handleTimeoutActionWithDelay` 内部会 `remove`+`put` 同一 map）。

#### ★ 判据（怎么知道它真的生效了）

- **单测**：`VaultLockManagerImplNeverTest` 新增 2 条 —— Never 档亮屏**绝不锁**、
  非 Never 档切后台后亮屏**不提前锁**。
- **真机**（唯一能验证算术的途径）：档位设 1 分钟 → 熄屏 3 分钟 → 亮屏，
  库应**立即**处于锁定态（而非再等 1 分钟）。
- ⚠️ **单测的已知盲区**（诚实记录）：`unitTests.isReturnDefaultValues = true` ⇒
  JVM 下 `elapsedRealtime()` 恒为 0 ⇒ **无法测"已走过一半"的算术**。
  不为这个单点引 Robolectric（不成比例）。

#### ⚠️ 变异验证**未干净完成**（如实记录，不宣称通过）

本批按纪律尝试了三轮变异（把 `onScreenOn` 改成无条件锁 / 剩余恒 0），
三轮**都"变红"**了，但失败 message **全部**是：

```
java.lang.NoClassDefFoundError: Could not initialize class io.vaultix.datastore.VaultTimeout$Never
java.lang.ExceptionInInitializerError
```

⇒ **变红原因与变异逻辑无关**（是测试顺序敏感的静态初始化竞态，见 issues **#129**），
**因此这三轮都不算验证通过**。

> ★ **由此得出的纪律（已写入 `.ai/MEMORY.md` §9 第 7 条）**：
> **变异验证的判据是「断言失败」，不是「测试失败」。**
> 只看"红了没有"会在这种环境竞态下得到**假的信心**。

#### 同时完成：清理

见 [`Docs/progress/cleanup-report-2026-09-29.md`](../../Docs/progress/cleanup-report-2026-09-29.md)
（**只扫描、未删除**；⚠️ `.workbuddy/memory/` 与 `Docs/progress/audit/evidence/` 明确不删）。

---

