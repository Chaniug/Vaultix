# 接力文档：快速解锁「房子化」批次 4 收工（2026-09-29）

> **给下一个会话（或任何接手的人）。** 自包含，读完即可续作，无需翻历史会话。
> 前置阅读（本文件不重复其内容）：
> - 设计真源：[`.ai/decisions/快速解锁房子化-两级钥匙层级-定稿.md`](../../.ai/decisions/快速解锁房子化-两级钥匙层级-定稿.md)（下称「定稿」），**§6.1 = 本批的实施记录**；
> - 钥匙层模型 / 存储键 / 16 方法契约：[批次 1 接力文档](house-rework-batch1-handoff.md)；
> - 动作表 / 重登记向导 / 扇出门禁：[批次 2 接力文档](house-rework-batch2-handoff.md)；
> - 设置页简化 / 判定逻辑可测化 / 批次 3.5「从不」档：[批次 3 接力文档](house-rework-batch3-handoff.md)；
> - 施工总单（批次 0→5）：[`quick-unlock-house-rework.md`](quick-unlock-house-rework.md)。

## 一句话状态

**批次 4（失效矩阵，定稿 §6）全部完成，门禁三关全绿（detekt / `:app:compileFullDebugKotlin` /
单测 377 全过 + 孤儿串未超基线），已提交推送 `main`。批次 5（真机验收）未动，仍必做。**

## 本批做了什么（工作单四条目标逐条对照）

| 工作单项 | 结果 |
|---|---|
| **rearm** | ✅ 但**落地形态是「延迟重装」**（见下文「一、」）——`HouseKeyStore.rearmFingerprintLock(cipher)` + 待重装标记；`UnlockRecoveryRepository` 暴露给 app 层 |
| **降级** | ✅ `UnlockRecoveryRepositoryImpl.degradeFingerprintLock()` → 禁锁 + 三选一文案 + 回主密码；**绝不静默** |
| **StaleCredentials** | ✅ `RoomResealRepositoryImpl.resealRoom(vaultId, newMasterPassword)` 只重包该房间软件信封 |
| **PIN 熔断全局 5 次** | ✅ 批次 1 已达成（N 信封 → 1 门锁信封 ⇒ 计数天然全局），本批无需改 |
| 失效三态分类 API | ✅ `LocalUnlockFailureKind { Recoverable, Rearmable, Unavailable }` |
| 门禁 / 孤儿串 | ✅ 三关全绿；`strings.xml` 新增 1 条（有引用） |

---

## 一、★ 为什么 rearm 只能是「延迟重装」而不是「静默重包」（本批最重要的认知）

工作单原文写的是「开门状态检测平台密钥失效 → 内存房钥匙**静默重包**门锁信封」。
**这做不到**，原因是硬约束 #2：

> 指纹门锁的信封 KEK 是 **`auth-per-use`** —— 只有被 `BiometricPrompt` 授权过的那**一个**
> `Cipher` 实例才能 `doFinal`。一次授权只保**一次**密钥使用。

⇒ 只要需要**重写**门锁信封（= 再一次 `wrap`），就**必须**再来一次认证弹窗。
「静默」与「重写门锁信封」在 auth-per-use 下**互斥**。这不是实现偷懒，是平台约束。

于是本批采用**延迟重装（rearm-on-next-auth）**：

```
检测到失效（解锁现场）
      │
      ├─ 开门态（房钥匙还在内存）→ 只写标记 house_lock_fingerprint_rearm_pending
      │                             （不弹窗、不打扰、不丢任何东西）
      │
      └─ 关门态（房钥匙不在内存）→ 降级：禁用指纹锁 + 明确文案 + 回主密码
                                    （此时没有房钥匙，重装也无从谈起）
      ↓
下次用户主动用指纹解锁 / 进设置向导开指纹
      │
      └─ 拿到本次认证的 cipher → 写新门锁信封 → 清标记
```

**用户已拍板接受**（2026-09-29）：「可以接受重新安装」——接受「标记 → 下次补写」这个
延迟形态，而非物理上不可能实现的「当场静默重包」。

**与 Bitwarden 的有意偏离**（定稿 §6.1 有全文）：

| | Bitwarden | Vaultix |
|---|---|---|
| 生物识别解密失败 | `BiometricDecodingError`（`VaultRepositoryImpl.kt:288-349`），用户重走全流程 | 延迟重装：只差一次认证，不用重走登记向导 |
| 锁定计数达 5 次 | 登出（`VaultLockManagerImpl.kt:340-352`） | 全局熔断，主密码可进 |

偏离的**前提**是 Bitwarden 没有的东西：**房子钥匙已在内存**（开门态）。
有它在，重装只是「再按一次指纹」，代价远小于 Bitwarden 的全流程重建 —— 所以更软的路径
在这里**成立**。若哪天这个前提不成立（例如未来改成「切换即锁库」），这条偏离要重新评估。

## 二、★ 开门 / 关门的唯一判据是 `HouseKeyStore.isUnlocked`

不是「门锁信封存不存在」。这两者在**重录系统指纹**后会出现分叉：

- 用户重录了指纹 → 平台 KEK 永久失效，但**信封文件还在**；
- 若用「信封在 ⇒ 视为开门」去判 → 会走进**降级**分支 → `disableFingerprintLock()`
  → **连带清掉房间信封**（用户丢库）。
- 正确判据 `isUnlocked`（房钥匙是否在内存）：开门态 ⇒ 走 rearm（安全），
  关门态 ⇒ 才走降级（此时确实无钥匙可用，降级是对的）。

`UnlockRecoveryRepository` 的 KDoc 里有一张「开门态 vs 关门态」判定表，**动这块前先读它**。

## 三、新增的 `LocalUnlockFailureKind` 三态（刀 4.0）

| 值 | 含义 | 典型异常 | 处置 |
|---|---|---|---|
| `Recoverable` | 稍后可自愈 | 密钥暂不可读（`kekStatus == UNKNOWN`） | 什么都不做，下次再试 |
| `Rearmable` | 可重装 | 平台密钥失效类 | 开门态 → rearm 标记；关门态 → 降级 |
| `Unavailable` | 该锁彻底不可用 | KEK `MISSING` / 信封坏 | 降级 |

判定入口 `Throwable.localUnlockFailureKind()`（`data:repository/LocalUnlockFailure.kt`），
沿用既有 `isLocalUnlockUnrecoverable()` 的异常链遍历（深度 ≤8）风格。13 条单测钉住。

---

## 四、本批改动的文件（10 生产 + 6 测试）

**新增文件**

| 文件 | 作用 |
|---|---|
| `domain/.../UnlockRecoveryRepository.kt` | 失效善后契约 + `FingerprintDegradeReport` + 判定表 KDoc |
| `domain/.../RoomResealRepository.kt` | `resealRoom(vaultId, newMasterPassword): RoomResealOutcome` |
| `data/repository/.../UnlockRecoveryRepositoryImpl.kt` | 薄转发；降级如实算 `roomsRemoved` |
| `data/repository/.../RoomResealRepositoryImpl.kt` | 复用 `LocalUnlockEnrollment.prepareForVaults` 只包该房间 |
| `data/repository/.../LocalUnlockFailureTest.kt` | 13 条 |
| `data/repository/.../UnlockRecoveryRepositoryImplTest.kt` | 10 条 |
| `data/repository/.../RoomResealRepositoryImplTest.kt` | 8 条 |
| `app/.../unlock/StaleRoomResealTest.kt` | 6 条 |

**改动文件**

| 文件 | 改了什么 |
|---|---|
| `data/repository/.../LocalUnlockFailure.kt` | 加 `LocalUnlockFailureKind` + `localUnlockFailureKind()` |
| `data/repository/.../HouseKeyStore.kt` | `isFingerprintLockInvalidated()`（信封在 **且** KEK `INVALIDATED`）+ rearm 四方法 |
| `data/repository/.../RepositoryModule.kt` | 两个新 `@Binds` |
| `app/.../unlock/LocalUnlockFanout.kt` | 门锁失败 → `handleLockFailure(recovery)`（可空参数，兼容 `AutofillActivity`） |
| `app/.../unlock/UnlockViewModel.kt` | 接两个新依赖；`StaleCredentials` → `resealStaleRoomIfPossible()` |
| `app/.../settings/QuickUnlockController.kt` | 第 5 个 `combine` 源读待重装标记；`onAuthenticated` 成功后清标记 |
| `app/.../settings/QuickUnlockDialogs.kt` + `strings.xml` | 「需要重新启用」副标题 |
| `app/.../settings/SettingsViewModel.kt`、`app/.../vaultlist/VaultListViewModel.kt` | `recovery = unlockRecovery` 接线 |

---

## 五、下一轮可能踩的坑（本批实测经验）

1. **detekt `TooManyFunctions` 上限 40/类** —— 本批又撞了一次：
   - `QuickUnlockController` 本来顶格 40，加「读待重装标记」直接 41。
     解法：**不要新加私有方法**，把逻辑内联进 `composeState()` 的 `combine` 源；
     并删掉一个已有小方法（`clearRearmPending()` 内联进 `onAuthenticated`）。
   - `VaultRepositoryImpl` 恒顶格 40 ⇒ 新能力一律走**独立接口 + 独立 Impl**
     （本批两个新仓储就是 `KdbxSyncRepository` / `AutoUnlockRepository` 先例的延续）。
2. **`Dispatchers.IO` 在纯 JVM `runTest` 下不可确定性推进**（`StaleRoomResealTest` 血泪）：
   实现内部 `withContext(Dispatchers.IO)` 是**真实线程池**，`advanceUntilIdle()` 管不到它
   ⇒ 断言会跑在 IO 块完成前，得到**间歇性** `expected not to be: null`。
   解法 = 用 `withTimeoutOrNull(5_000L) { while (!predicate()) delay(1) }` **轮询终态**，
   不用 `advanceUntilIdle()`。本批已连跑 3 轮验证确定性。
3. **`Dispatchers.setMain` 必须用块体**：`fun setUp() = Dispatchers.setMain(d)`（表达式体）
   会让 `@After` / `@Before` 生命周期错乱，报
   `Dispatchers.Main was accessed when ... test dispatcher was unset`。
4. **`openWithPin` 返回仓库层 `PinOpen`，不是 domain 层 `PinUnlockOutcome`** ——
   写 `HouseKeyStoreTest` 断言时别搞混。
5. **Gradle 环境**：daemon 会莫名消失（SSL 拉 manifest 失败）⇒ 一律加 `--offline`；
   `--rerun-tasks` 偶发 `transformXxxClassesWithAsm` 的 "Failed to create MD5 hash"
   ⇒ `./gradlew --stop` + 删 `*/build/intermediates/classes/*/transform*ClassesWithAsm` 重试。

---

## 六、门禁命令速查

```bash
# gradle 用解压发行版绝对路径（./gradlew 本机不可用）
GRADLE=~/.gradle/wrapper/dists/gradle-9.5.1-bin/*/gradle-9.5.1/bin/gradle

"$GRADLE" -p D:/Vaultix detekt --offline                        # 第一关
"$GRADLE" -p D:/Vaultix :app:compileFullDebugKotlin --offline   # 第二关
"$GRADLE" -p D:/Vaultix :app:testFullDebugUnitTest --offline \
                      :data:repository:testDebugUnitTest --offline  # 第三关
python3 .ai/tools/check_orphan_strings.py --gate                # 动过 strings.xml 就跑
# ⚠️ 三关分开单跑（连跑会触发 daemon 环境崩）；改完 detekt 必须再真跑 compile。
# ⚠️ push 到 rele 分支 = 直接发正式版，日常只 push main。
```

## 七、批次 5 开工点（下一轮从这里开始）

**真机验收清单（工作单批次 5，清单 1-9；需用户真手指）**：

1. 指纹一次 → 范围内全部库打开（日志：1 次 Keystore + N 次软件）；
2. 勾选 KDBX 库进范围：只弹主密码框，**不弹指纹**；
3. **硬约束 #1 专项**：解锁后杀后台 → 重启必须要求重新解锁
   （⚠️ **Never 档除外**——改验第 8 条）；
4. **★ 本批新增重点**：重录系统指纹 → 开门态自动 rearm 无感（设置页应显示
   「需要重新启用」直到下次认证补写成功）；关门态明确降级 + 主密码可进；
5. PIN 连错 5 次 → 全局熔断，主密码可进，重置恢复；
6. 升级迁移：老包升新包 → 重登记向导走通、旧信封已清；
7. 装机核证照旧（`dumpsys package` + `pm path` 拉 APK 比 SHA-256）；
8. **Never 档填充及时性**：设置从不锁定 → 解锁一次 → 划掉后台 → 任意 app 聚焦输入框 →
   填充条目 **1s 内弹出**且**不再要求重新解锁**；随后主动锁库 → 再聚焦 → **必须重新认证**；
9. **设置页**：解锁方式组只剩两行；On 态点整行 = 进向导，点开关 = 关闭；
   **新增：rearm 待重装时 On 行副标题应显示「需要重新启用」**。
