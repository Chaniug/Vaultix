# 接力文档 · 批次 5.0：软锁 + 前台门禁 + 解锁提速（2026-09-29）

> **自包含、零上下文可做。** 设计真源 = 定稿
> [`快速解锁房子化-两级钥匙层级-定稿.md`](../../.ai/decisions/快速解锁房子化-两级钥匙层级-定稿.md)
> 的 **§6.2 实施记录**；施工工作单 = [`quick-unlock-house-rework.md`](quick-unlock-house-rework.md)
> 的「批次 5.0」专节。
> **论证一律看定稿，不在会话里重新论证。**

---

## 一、一句话状态

> 用户真机报两条问题：①「**从不**档锁屏/清后台后 Vaultix 直接就是开着的，有风险」；
> ②「指纹解锁进密码库要等好几秒」。
> 已按用户拍板的**方案 A**（对齐 Bitwarden：离开 App 就真锁，回来靠恢复信封免交互自动开）
> 全部落地：**6 生产 + 5 测试**文件，门禁三关全绿，单测 **398 全过 0 failed**。
> `main` 已推送，`rele` 未动。**下一步 = 批次 5 真机验收清单 1-11（需真手指）。**

---

## 二、四条目标对照（做完的）

| 目标 | 落地 | 关键点 |
|---|---|---|
| ① Never 档离场软锁 | `AutoUnlockRepository.softLock()` + `VaultLockManagerImpl` Never 分支 | 清密钥、**留**信封 |
| ② 恢复的前台门禁（安全底线） | `AutoLockController.isForeground` → `AutoRestoreTrigger` `combine` 第三源 | 非前台一律不恢复 |
| ③ 指纹提速 · 去 Keystore 串行 | `UnlockViewModel.candidateVaultIds()` 改一次读范围快照 | N 次 Keystore → 1 次 |
| ④ 指纹提速 · 先核心库后其余 | `completeLocalUnlock` 拆两段 + `LocalUnlockFanout.openRest` | 其余库是附加收益 |

---

## 三、★★★ 三条最重要的认知

### 1. 软锁 vs 硬锁 = 信封的留与删

| | 触发 | 房钥匙 | 恢复信封 | 回来自动开 |
|---|---|---|---|---|
| **软锁**（新 `softLock`） | 离开 App（划后台/锁屏） | 清零 | **保留** | ✅ 免交互 |
| **硬锁**（既有 `lockVault`/`lockAll`） | 点锁定/退出数据库 | 清零 | **删除** | ❌ 过门锁 |

> 旧 bug 根因：`VaultTimeout.Never -> return@launch` —— **任何原因都不锁**，
> 房钥匙常驻内存，除「锁定」按钮外无路径能清掉。

### 2. 前台门禁是安全底线，不是优化

软锁把 `houseKeyInMemory` 从 true 翻成 false，而 `AutoRestoreTrigger` 正是在 `combine`
里观察这个值。**没有门禁的话**：

```
划后台 → softLock（清钥匙）→ houseKeyInMemory=false
  → 该 combine 立刻触发恢复分支 → 钥匙回到内存
  ⇒ 软锁从未发生：后台进程照样抓着密钥
```

⇒ **若哪天有人"顺手"把这个门禁去掉，`AutoRestoreTriggerTest` 必须变红。**

### 3. 提速的关键 = 消除 N 次串行 Keystore 往返

旧 `candidateVaultIds()` 对每个库调 `fingerprintQuickUnlockAvailable(id).first()`，
那条 Flow 内含 `localUnlockKeyStore.keyAvailable` = **一次 Keystore 往返**
（源码注释自述「冷启动可达数百毫秒」）。N 库 × 数百毫秒**全串行**，
且卡在「认证成功 → 结果返回」之间 ⇒ 用户感知「指纹过了却要等好几秒」。

正确判据是**两个全局事实**相乘：
1. 指纹门锁是否可用 —— **全局一份**（房子化后门锁只有一把）；
2. 该库是否在生效范围内 —— 一次读偏好（`house_key_scope`，DataStore，**零 Keystore**）。

---

## 四、改动文件清单

| 层 | 文件 | 内容 |
|---|---|---|
| domain | `AutoUnlockRepository.kt` | `+suspend fun softLock(): List<String>` |
| data:repository | `AutoUnlockRepositoryImpl.kt` | `softLock()` + 注入 `VaultSessionManager` |
| app | `AutoLockController.kt` | `+isForeground: StateFlow<Boolean>`（初值 false） |
| app | `VaultLockManagerImpl.kt` | Never 档 `AppBackgrounded` → `softLockForBackground()` |
| app | `AutoRestoreTrigger.kt` | 前台门禁（`combine` 三源） |
| app | `UnlockViewModel.kt` | 候选库一次快照 + 先核心库异步补开其余 |
| app | `LocalUnlockFanout.kt` | `Result.lockOpened` + `openRest()` |
| test | `VaultLockManagerImplNeverTest.kt` **新** | +3，切后台必软锁（**已变异验证**） |
| test | `AutoRestoreTriggerTest.kt` **新** | +6，前台门禁 |
| test | `AutoUnlockRepositoryImplTest.kt` **新** | +9，软锁不删信封 |
| test | `UnlockViewModelTest.kt` | +3，候选筛选（排除目标/范围外/已解锁） |
| test | `StaleRoomResealTest.kt` | 补 `VaultixPreferences` 构造参数 |

---

## 五、⚠️ 五个踩坑（写测试血泪）

1. **非 suspend 成员只能 `every`**：`HouseKeyStore.lock()` / `isUnlocked` 是普通成员，
   误用 `coEvery` **静默失效**，运行时才报 `MockKException: no answer found`。
   本批 6 条 `AutoUnlockRepositoryImplTest` 全因此红。
2. **`withContext(Dispatchers.IO)` 在纯 JVM `runTest` 下不可确定性推进**：
   `softLock()` / `restore()` / `candidateVaultIds()` 内部都是真实线程池，
   `advanceUntilIdle()` 管不到 ⇒ 必须
   `withTimeoutOrNull(5_000L) { while (!predicate()) delay(1) }` **轮询终态**。
   `UnlockViewModelTest` 三条候选筛选用例全因此红（表现为 `submitting=true` 卡住）。
3. **`startAndCapturePrompt` 需要 `TestScope` 接收者**：`advanceUntilIdle()` /
   `backgroundScope` 都在 `TestScope` 上，plain `suspend fun` 里调不到。
4. **回归测试要做变异验证**：`VaultLockManagerImplNeverTest` 建好后，临时还原旧
   `Never -> return@launch` 确认「切后台必须软锁」**变红**，再还原。否则可能是假测试。
5. **`Dispatchers.setMain` 必须块体**、**Gradle 一律 `--offline`**（同项目既有纪律）。

---

## 六、门禁命令（三关分开单跑）

```bash
./gradlew detekt --console=plain --offline
./gradlew :app:compileFullDebugKotlin --console=plain --offline
./gradlew :data:repository:testDebugUnitTest :app:testFullDebugUnitTest --console=plain --offline
python3 .ai/tools/check_orphan_strings.py --gate
```

实测：detekt ✅ / compile ✅ / **单测 398 全过 0 failed**（data 113 + app 285）/ 孤儿串 ✅。

---

## 七、批次 5 开工点（真机验收，需真手指）

清单全文见 [`quick-unlock-house-rework.md`](quick-unlock-house-rework.md) §「批次 5」。
⭐ **本轮新增两条**：

- **第 10 条（Never 档离场软锁）**：设置从不 → 解锁一次 → 划掉后台（或锁屏）→
  观察日志 `VaultixAutoRestore → 离场软锁` → 回前台应**免交互自动开**（信封保留）；
  随后主动锁库 → 必须要求重新认证（信封已删，真锁）。
- **第 11 条（指纹提速）**：多库（≥4）场景指纹一次 → 进库应**秒进**（先开核心库放行），
  其余库后台补开（日志 `fanout rest`）；不再出现「指纹过了等好几秒」。

⏳ **批次 3 可选遗留（用户已确认、本批未做）**：Bitwarden 亮屏时间补偿
（`ACTION_SCREEN_ON` + `elapsedRealtimeMs` 重算剩余延迟）+ 新增「锁屏即锁」档位。
