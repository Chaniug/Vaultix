# Bitwarden 锁定 / 解锁**时机矩阵**（逐条源码取证）

> **用途**：用户在 2026-09-29 反复表达「Bitwarden 虽然设了从不，但安全性感觉更完善，
> 比如扫描验证的时机、app 加锁的时机」⇒ 本表把 Bitwarden 的**全部触发源 × 全部档位**
> 穷举出来，供决策「Vaultix 要不要学」。
>
> **取证来源**：本地源码 `D:\Vaultix-refs\bitwarden-android`（`com.x8bit.bitwarden`）。
> 每一条都标了**文件:行号**，可复查。
>
> ⚠️ 本表**只描述 Bitwarden**。Vaultix 的对照放在 §3。

---

## 1. 全部门锁口径：两个正交的枚举

Bitwarden 把一个「锁定事件」拆成**两个独立的枚举**，这是理解一切的前提：

### 1.1 `VaultTimeout` —— 「多久自动锁」（7 档 + 2 特例）

`data/platform/repository/model/VaultTimeout.kt`

| 档位 | `vaultTimeoutInMinutes` | 含义 |
|---|---|---|
| `Immediately` | `0` | 立即 |
| `OneMinute` | `1` | 1 分钟 |
| `FiveMinutes` | `5` | 5 分钟 |
| `FifteenMinutes` | `15` | 15 分钟 |
| `ThirtyMinutes` | `30` | 30 分钟 |
| `OneHour` | `60` | 1 小时 |
| `FourHours` | `240` | 4 小时 |
| `OnAppRestart` | `-1` | **进程重启时锁** |
| `Never` | **`null`** | **从不自动锁** |
| `Custom(n)` | `n` | 用户自定义 |

### 1.2 `VaultTimeoutAction` —— 「超时后做什么」（2 档）

`data/platform/repository/model/VaultTimeoutAction.kt`

| 值 | 行为 |
|---|---|
| `LOCK("0")` | 锁定（清密钥，需重新解锁） |
| `LOGOUT("1")` | **软登出**（比锁定更狠，需重新登录） |

> ★ **关键洞察**：`VaultTimeoutAction` 是**用户可配**的（同档位可以是「锁」或「登出」）。
> 这解释了为什么 Bitwarden 感觉"更完善"——**它把"多久"和"锁到什么程度"拆成了两个维度**。

---

## 2. 触发源 × 档位 —— 完整矩阵

### 2.1 触发源清单（`CheckTimeoutReason`，共 3 个）

`VaultLockManagerImpl.kt:764-789`

| 触发源 | 何时发生 |
|---|---|
| `AppBackgrounded` | 应用切到后台（**仍在运行**） |
| `AppCreated(firstTimeCreation, createdForAutofill)` | 进程被创建；`firstTimeCreation` = 是否该进程首次创建 |
| `UserChanged` | 当前活跃用户变化（多账号切换） |

### 2.2 核心分流表（`VaultLockManagerImpl.kt:592-644`）

| 档位 \ 触发源 | `AppCreated(firstTime)` | `AppCreated(非首次)` | `AppBackgrounded` | `UserChanged` |
|---|---|---|---|---|
| **`Never`** | **不锁** | **不锁** | **不锁** | **不锁** |
| `OnAppRestart` | **执行 Action** | **执行 Action**<br>（⚠️ 为 autofill 拉起则**豁免**） | 不锁 | 不锁 |
| 其它所有档位 | **执行 Action** | 不处理 | **延迟 N 分钟后执行** | **延迟 N 分钟后执行** |

> ⚠️ 「⚠️ 为 autofill 拉起则豁免」= `createdForAutofill == true` 且非首次创建时跳过。
> 源码 `VaultLockManagerImpl.kt:598-611`。

**「执行 Action」= `VaultTimeoutAction.LOCK` → `setVaultToLocked`；`.LOGOUT` → `softLogout`**
（`VaultLockManagerImpl.kt:675-687`）

### 2.3 核心分流代码（原文，可直接对照）

```kotlin
// VaultLockManagerImpl.kt:592
when (vaultTimeout) {
    VaultTimeout.Never -> {
        // No action to take for Never timeout.
        return                                    // ★ 任何触发源都不锁
    }
    VaultTimeout.OnAppRestart -> {
        if (checkTimeoutReason is CheckTimeoutReason.AppCreated) {
            if (checkTimeoutReason.firstTimeCreation ||
                !checkTimeoutReason.createdForAutofill) {
                handleTimeoutAction(...)
            }
        }
    }
    else -> when (checkTimeoutReason) {
        is CheckTimeoutReason.AppCreated -> {
            if (checkTimeoutReason.firstTimeCreation) { handleTimeoutAction(...) }
        }
        CheckTimeoutReason.AppBackgrounded,
        CheckTimeoutReason.UserChanged,
            -> handleTimeoutActionWithDelay(
                delayMs = vaultTimeout.vaultTimeoutInMinutes?.minutes ?: 0L,
            )
    }
}
```

### 2.4 `handleTimeoutActionWithDelay` 的**时间补偿**（Vaultix 没有）

`VaultLockManagerImpl.kt:651-668` + `737-749`

```kotlin
// 起一个 delay job，并记录 startTimeMs
userIdTimerJobMap[userId] = TimeoutJobData(
    job = unconfinedScope.launch { delay(delayMs); handleTimeoutAction(...) },
    startTimeMs = realtimeManager.elapsedRealtimeMs,
    durationMs = delayMs,
)

// 注册 ACTION_SCREEN_ON 广播：亮屏时**重算剩余延迟**
userIdTimerJobMap.map { (userId, data) ->
    val durationSoFarMs = elapsedRealtimeMs - data.startTimeMs
    handleTimeoutActionWithDelay(delayMs = data.durationMs - durationSoFarMs)
}
```

> ★ **这是「亮屏时间补偿」**：设备深度睡眠时 CPU 挂起，协程 `delay()` 的计时会**欠账**
> ——熄屏 30 分钟，`delay(5分钟)` 可能只推进了 2 分钟，导致实际锁定时间比配置的晚。
> Bitwarden 用亮屏事件**按单调时钟重算剩余时间**修正。
>
> ✅ **Vaultix 已于 2026-09-29 补齐**（`VaultLockManagerImpl.onScreenOn()` +
> 注册 `ACTION_SCREEN_ON`），逐句对齐本段；两处有意收口见该方法的 KDoc。

### 2.5 矩阵之外的锁定入口（**不看档位**）

| 入口 | 代码位置 | Never 档会不会锁 | 说明 |
|---|---|---|---|
| **用户主动锁** | `:168-170` | ✅ 会 | `isUserInitiated = true`，同时置 `isFromLockFlow = true` |
| **登出完成** | `:503-508` | ✅ 会 | `logoutEventFlow` → `setVaultToLocked` |
| **`syncVaultState` 取钥失败** | `:308-317` | ✅ 会 | `getUserEncryptionKey` 失败 ⇒ 判为锁定；**仅 TDE 注册路径调用** |
| **解锁失败** | 见 §2.6 | — | — |
| **无效解锁次数超限** | `incrementInvalidUnlockCount` | ✅ 会登出 | ≥ `MAXIMUM_INVALID_UNLOCK_ATTEMPTS` 时 `softLogout` |

> ⚠️ **注意 `syncVaultState`**：它的逻辑是「试着取钥，取不到就判锁」——
> **不看档位**。Never 档下如果 `getUserEncryptionKey` 因任何原因失败，
> 一样会 `setVaultToLocked`。**这是 Never 档唯一的"意外锁"风险点。**

### 2.6 解锁时「要不要自动弹指纹」—— 独立于上面一切

`VaultUnlockViewModel.kt:437-441`

```kotlin
private fun promptForBiometricsIfAvailable() {
    val cipher = authRepository.getOrCreateCipher(state.userId)
    if (state.showBiometricLogin && cipher != null && !state.isFromLockFlow) {
        sendEvent(VaultUnlockEvent.PromptForBiometrics(cipher = cipher))
    }
}
```

其中：

| 条件 | 来源 |
|---|---|
| `showBiometricLogin` = `isBiometricEnabled && isBiometricsValid` | `VaultUnlockViewModel.kt:480` |
| `isBiometricsValid` = `isBiometricIntegrityValid()` | `BiometricsEncryptionManagerImpl.kt:93` |
| `isFromLockFlow` | **由 `lockVault(isUserInitiated)` 赋值**（`:169`，`isFromLockFlow = isUserInitiated`） |

⇒ **语义**：

> **「因超时/系统原因被锁」→ 回来自动弹指纹（无感）**
> **「用户主动点锁定」→ 不弹指纹，必须输主密码**

**这是 Bitwarden 一个刻意的安全设计**：用户主动锁 = 声明「我要离开」，
那就把指纹通道一起收起，不给"偷偷加个指纹就能进"的后门。

---

## 3. Vaultix 对照（2026-09-29 收尾后）

| 项 | Bitwarden | Vaultix | 差距 |
|---|---|---|---|
| 档位枚举 | 7 档 + `OnAppRestart` + `Never` + `Custom` | `Never` / `OnAppRestart` / N 分钟 | 大致对齐 |
| 超时后动作 | `LOCK` / `LOGOUT` 可选 | 仅锁定 | **无「软登出」档**（用户已拍板不做） |
| `Never` 档自动锁 | **不锁** | **不锁**（批次 5.1 对齐） | ✅ 已对齐 |
| `AppBackgrounded` 延迟锁 | ✅ | ✅ | 对齐 |
| autofill 拉起豁免 | ✅ `createdForAutofill` | ✅ 同款 | 对齐 |
| **亮屏时间补偿** | ✅ `ACTION_SCREEN_ON` + 单调时钟重算 | ✅ **已补齐**（`onScreenOn()`） | ✅ 已对齐 |
| **「主动锁 ⇒ 不弹指纹」** | ✅ `isFromLockFlow`（在解锁 ViewModel 消费） | ✅ **`isViewLocked`**（会话层 / 逐库） | ✅ **语义已在，载体不同** |
| 「主动锁」的强度 | 清密钥 + 收起指纹 | 双入口：查看层锁 / 真锁 | ✅ 用户拍板的更强设计 |

### 3.1 ⚠️ 本节曾判断错误，此处更正（务必读）

**本表初稿的 §3.1 断言**：「Vaultix 的 `isFromLockFlow` 是坏的 ⇒ 实际没实现
『主动锁收起指纹』」。**该断言错误**，保留此段是为了让接力者不再重蹈。

**错在哪**：只检查了 `VaultLockManagerImpl` 里那个字段本身（确认它无消费方、赋值写反），
就**外推**成「Vaultix 没有这个行为」。实际情况是：

> Vaultix 用 **`VaultSessionRepository.isViewLocked`** 实现了**同一语义、且粒度更细**
> 的机制 —— Bitwarden 的 `isFromLockFlow` 是**全局单值**（一个 bool 管所有库），
> 而 `viewLocked` 是**会话层 + 逐库**的。

所以三个「事实」要全部重写：

| 初稿断言 | 更正后 |
|---|---|
| 「无消费方」 | ✅ 对 `isFromLockFlow` 这个字段成立（它确实是抄残的） |
| 「赋值写反」 | ✅ 成立（`:121` 应为 `= isUserInitiated`） |
| 「⇒ 行为缺失」 | ❌ **错**。行为由 `viewLocked` 提供，且分得更细 |

**结论**：`isFromLockFlow` 是**死字段**（已删除），但它的**语义早已以更好的形式存在**。
—— 这正是记忆里那条纪律的实例：**「现象相同 ≠ 真因相同」，且「字段缺失 ≠ 功能缺失」**。

**如何避免再犯**：判断「某功能是否缺失」时，**必须搜消费侧的行为**，不能只看
某个名字相同的字段是否存在。搜 `isViewLocked` / `viewLock` 的用法，而不是搜
`isFromLockFlow`。

### 3.2 双入口（用户已拍板，非「待决策」）

`UnlockViewModel.kt:45-52` 有两条语义完全不同的路径：

| 入口 | 效果 | 解锁方式 |
|---|---|---|
| 主页的锁按钮 | **查看层锁**：`viewLocked = true`，**不清密钥** | 生物识别即可（密钥还在） |
| 「退出数据库」 | **真锁**：清密钥 | 主密码 + 2FA |

这**比 Bitwarden 的单档更细**：Bitwarden 只有一个「锁」，用户无法表达
「我只是暂时遮一下」与「我要彻底退出」的差别。Vaultix 的双入口把这两个意图分开，
既保留了快速解锁的便利，也让「真离开」有了明确、更重的操作。

> ⚠️ 与 Bitwarden 的 `isFromLockFlow` **不构成冲突**：Bitwarden 用它表达
> 「主动锁 ⇒ 解锁时不自动弹指纹」，Vaultix 的对应表达是
> 「查看层锁（`viewLocked=true`）⇒ 只走生物识别，不要求主密码」。

---

## 4. 待决策清单 → 已全部拍板（2026-09-29）

| # | 议题 | 结论 |
|---|---|---|
| 1 | 「主动锁 ⇒ 收起指纹」要不要做 | ✅ 已由 `viewLocked` 提供（双入口），无需另做 |
| 2 | 要不要加「软登出」档 | ❌ **不做**（用户采纳建议；理由见定稿文档） |
| 3 | 要不要补「亮屏时间补偿」 | ✅ **已做**（`onScreenOn()` + `ACTION_SCREEN_ON`） |

---

## 5. 复盘：本表的结论（一句话）

> Bitwarden 的「安全性更完善」**不是**因为它锁得更频繁（`Never` 档它也不锁），
> 而是因为它有三个维度：**时间修正**（亮屏补偿）、**程度可选**（LOCK/LOGOUT）、
> **原因关联**（`isFromLockFlow` 关联"锁定的原因"与"解锁的方式"）。
>
> Vaultix 的现状：**时间修正已补**（`onScreenOn`）；**程度可选**经评估不做
> （用双入口替代，更符合用户心智）；**原因关联**早已以 `viewLocked` 实现，
> 且粒度**优于** Bitwarden 的全局单值。
