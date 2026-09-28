# 接力文档：快速解锁「房子化」批次 2 收工（2026-09-29 夜）

> ⚠️ **本文已被接力**：批次 3 于同日深夜完成，收工文档在
> [`house-rework-batch3-handoff.md`](house-rework-batch3-handoff.md)（**下一会话先读那份**）。
> 本文保留作**动作表 / 重登记向导 / 扇出门禁**的速查，内容不再更新。
> 另：本文「已知遗留 #1」（动作表无单测覆盖）**已在批次 3 解决** ——
> 判定逻辑（`lockState` / `locksToOpen` / `roomSealingBlocker` / `assemble`）
> 已挪到文件级 `internal` 纯函数并被 `QuickUnlockControllerTest`（17 条）钉住。

> **给下一个会话（或任何接手的人）。** 自包含，读完即可续作，无需翻历史会话。
> 前置阅读（本文件不重复其内容）：
> - 设计真源：[`.ai/decisions/快速解锁房子化-两级钥匙层级-定稿.md`](../../.ai/decisions/快速解锁房子化-两级钥匙层级-定稿.md)（下称「定稿」）；
> - 钥匙层模型 / 存储键表 / 16 方法契约：[批次 1 接力文档](house-rework-batch1-handoff.md)；
> - 施工总单（批次 0→5）：[`quick-unlock-house-rework.md`](quick-unlock-house-rework.md)。

## 一句话状态

**批次 2 三件事全部完成，门禁三关实测全绿（detekt / `:app:compileFullDebugKotlin` / 单测 318 全过），已提交推送 `main`（`61ea1a3`）。批次 3-5 未动。真机验收（批次 5）仍必做。**

## 本批做了什么（三件事，逐条对应批次 1 文档末尾的「批次 2 开工点」）

| # | 开工点 | 落点 |
|---|---|---|
| 1 | 动作表完整重排（定稿 §5） | `QuickUnlockController.kt`（+ 类 KDoc 新增「★ 动作表」表格） |
| 2 | 重登记向导（旧信封 + 旧 DataStore 键清理，不半新半旧） | 新建 `LegacyQuickUnlockCleanup.kt` + `VaultixPreferences` 两个方法 + 控制器接入 + 设置页提示行 + 三条文案 |
| 3 | `LocalUnlockFanoutTest` | 新建（8 用例）+ 顺带新建 `LegacyQuickUnlockCleanupTest`（8 用例） |

---

## 一、动作表（房子化后的**唯一**登记心智模型）

| 用户动作 | 干什么 | 碰不碰 Keystore / 门锁 |
|---|---|---|
| **勾 / 取消勾一个库** | 建 / 删**该库的房间信封**（`sealRoomsForVaults`） | **完全不碰** —— 纯软件 AES-GCM |
| **开「指纹」开关** | `enrollFingerprintLock` **一次 wrap**（只包房钥匙） | 弹**一次**系统认证 |
| **开「PIN」开关** | `enrollPinLock` **一次 wrap** | 不碰系统认证 |
| **关某个开关** | 删对应**门锁信封**（一次调用；房间由孤儿清理连带） | 一次删除 |

三条硬规则（写在 `QuickUnlockController` 类 KDoc 里，改代码前先读）：

1. **勾库不再触发指纹**。旧「备料 → 弹指纹 → 一把 cipher 连包 N 个库」的三段式整体消失（H1 病灶）。
2. **已经开着的门锁不重开**。开某把锁 = 一次 wrap，要么成功要么不变。
3. **房间信封只在至少一把门锁已存在时创建**（定稿 §5 顺序约束）。

### ★ 顺序约束的**第二层**：房钥匙还得在内存

房钥匙**绝不落盘**（硬约束 #1）⇒ 进程重启后它就不在了。此时即使门锁信封还在，
封房间也无从下手（`sealRoom` 前置 `isUnlocked`）。

- 暴露方式：`LocalUnlockEnrollment.isHouseKeyReady`（非 suspend，读内存字段）；
- 守卫函数：`QuickUnlockController.roomSealingBlocker(pending, locksToOpen, ui)`；
- **拦在问主密码之前** —— 让用户输完一整轮 KDBX 主密码才说「不行」，是「先校验后包裹」
  那条纪律在编排层的反面教材。

### `Session` 的三个关键字段（本批重排的落点）

```kotlin
val methods: Set<UnlockMethod>       // 用户这次想用哪几种方式
val locksToOpen: Set<UnlockMethod>   // ★ 真正要新开的门锁（已装的不在此列）
fun pendingRooms(): List<String>     // ★ 单维度：房间信封还没建的库（与方式无关）
```

⚠️ `locksToOpen` 与 `methods` 必须分开：混成一个会让每次进向导都重新 wrap 一把已经装好的锁
（用户表现为「我明明配过了，怎么又要按一次指纹」）。

⚠️ `pendingRooms()` 收敛成单维度是动作表重排的**核心**：房间信封是**共享**的
（两把门锁包的是同一把房钥匙），所以「这个库要不要封」根本不取决于选了指纹还是 PIN。
按方式各判一次会在某把门锁被关掉时把**全部库**都算成「没配好」，
于是每次配指纹都白重封一遍已有的房间（幂等无害，但那是 H1 形状的残留）。

### 执行顺序（`executeSession`，四步，不可颠倒）

1. **开 PIN 门锁**（仅当 `locksToOpen` 含它）：一次 Argon2id wrap，当场落盘，带房钥匙进内存；
2. **备料**：只为「要新建房间信封的库」各备一份明文（主密码只收一次的落点）；
3. **开指纹门锁**（仅当 `locksToOpen` 含它）：弹**一次**认证 → 一次 wrap；
4. **软封装房间**：纯软件（指纹路径在 `onAuthenticated`，无指纹路径在 `finishWithoutBiometric`）。

⚠️ 顺序不可颠倒：PIN 当场落盘，指纹要弹一次认证。若先弹认证、再回头收 PIN，
用户会在以为已经完事之后又被要求输一次 PIN（此时界面已回到结果页）。

⚠️ 一段失败**不清掉另一段**：PIN 门锁开不成不影响指纹段的备料与认证，反之亦然。

---

## 二、重登记向导（老用户升级路径）

### 为什么老用户必须重新登记一次

定稿 §8 明确**不写新旧互转的兼容层**：旧信封 = 门锁直包每库凭据，与房钥匙无关，
**无法自动升级**。⇒ 老用户设备上会留下一批**永远不会被再读的垃圾**：

| 残留 | 在哪 | 键形 |
|---|---|---|
| 旧指纹信封 | `SecureCredentialStore` | `local_unlock_key::<vaultId>` |
| 旧 PIN 信封 | `SecureCredentialStore` | `local_pin_key::<vaultId>` |
| 旧 PIN 失败计数 | `SecureCredentialStore` | `local_pin_attempts::<vaultId>` |
| 旧「本地解锁已启用」标记 | DataStore | `local_unlock_enabled_<vaultId>` |
| 旧「PIN 解锁已启用」标记 | DataStore | `pin_unlock_enabled_<vaultId>` |

⚠️ 前三个前缀是**历史数据的指纹**，不是当前代码的常量（来自批次 1 已删除的
`LocalUnlockEnrollment.LOCAL_UNLOCK_STORAGE_PREFIX` 与 `PinUnlockStore` 的两个前缀，
取证于 git `3e1d4cc`）。**改一个字符就再也认不出老数据 —— 不得改动。**

### 落点

| 文件 | 内容 |
|---|---|
| `data/repository/LegacyQuickUnlockCleanup.kt`（新建） | `hasLegacyRemains()`（**只枚举键名，不解密任何值**）+ `clearLegacyRemains()` → `LegacyCleanupReport(envelopesRemoved, preferenceKeysRemoved)` |
| `core/datastore/VaultixPreferences.kt` | 新增 `LEGACY_LOCAL_UNLOCK_ENABLED_PREFIX` / `LEGACY_PIN_UNLOCK_ENABLED_PREFIX` 常量 + `legacyQuickUnlockKeys()` / `removeLegacyQuickUnlockKeys(keys)` |
| `app/.../QuickUnlockController.kt` | 构造注入 `cleanup`；`legacyRemains: StateFlow<Boolean>`（init 里查一次）；`finish()` 末尾「新体系真站起来才清」 |
| `app/.../QuickUnlockDialogs.kt` + `VaultManagementScreen.kt` | `legacyRemains = true` 时顶部插一行「快速解锁已升级，需重新登记一次」 |
| `app/src/main/res/values/strings.xml` | `quick_unlock_legacy_title` / `quick_unlock_legacy_desc` / `quick_unlock_report_lock_only` |

### ★★ 「不半新半旧」的落点（`finish()` 里三件事的顺序即语义）

```kotlin
// 1. 先回退：本次开了门锁却一个房间都没建成（且不是用户主动跳过）⇒ 撤掉新开的门锁
if (shouldRollbackLocks(...)) rollbackLocks(vaultRepository, session.locksToOpen)
// 2. 再出报告：报告反映的是**回退之后**的事实
_dialog.value = buildReport(session, committed, extraFailures)
clearSession()
// 3. 最后清旧：只有本次**至少建成一个房间信封**才删旧信封
if (anyRoomSealed(committed)) {
    cleanup.clearLegacyRemains()
    _legacyRemains.value = cleanup.hasLegacyRemains()   // ★ 重新查，不直接置 false
}
```

⚠️ 顺序不能换：
- 先掀旧的、新的又没立起来 ⇒ 用户等于被清空了快速解锁却什么都没得到；
- 报告在回退之后出 ⇒ 否则会谎报「门锁已开」；
- `_legacyRemains` **重新查一次**而不是置 `false` —— 清没清干净是**事实问题**，
  置 `false` 就是把「其实没删掉」演成「已清理」（谎报状态那一族）。

### 结果页新增 `lockOnly`

`Dialog.Report.lockOnly = true` = 本次**只开了门锁、没有房间要封**（房间早就封好了）。
与既有的 `scopeOnly`（只改了范围）同属「无事可做」，但因果不同：
得让用户知道「锁开了、库没动」，否则空结果页会被读成「开了但没生效」。

---

## 三、新增测试

| 测试 | 位置 | 用例数 | 钉的是什么 |
|---|---|---|---|
| `LocalUnlockFanoutTest` | `app/src/test/.../ui/unlock/` | 8 | **扇出形状**：`completeFingerprintUnlock` **恰好 1 次且不随库数增长** + N 次 `unlockVaultFromRoom`（旧形状 N 库 = N 次 Keystore = H2）；门锁解不开 ⇒ 一次软件解密都不跑；逐库独立成败不牵连 |
| `LegacyQuickUnlockCleanupTest` | `data/repository/src/test/.../` | 8 | 三个信封前缀 + 两个偏好前缀**认得出**；只删旧前缀**不误伤** `house_*` / `quick_unlock_scope`；清理后**真的**消失（用会变空的假存储，不只数调用次数） |

⚠️ 这两类断言的共通点是**形状而非结果**：只断言「开了几个库」会漏掉「偷偷多取了一把 cipher」
这类回归 —— 正是 H2 当初的形态。

---

## 本批落地清单（刀序）

| # | 文件 | 内容 |
|---|---|---|
| 1 | `data/repository/LocalUnlockEnrollment.kt` | 新增 `isHouseKeyReady`（供控制器在问密码**之前**判断封房前置） |
| 2 | `data/repository/LegacyQuickUnlockCleanup.kt` | **新建**（~118 行）：检测 + 清理 + `LegacyCleanupReport` |
| 3 | `core/datastore/VaultixPreferences.kt` | 两个旧前缀常量 + `legacyQuickUnlockKeys()` / `removeLegacyQuickUnlockKeys()` |
| 4 | `app/.../QuickUnlockController.kt` | 动作表重排（`composeState` / `assemble` / `confirmConfigure` / `executeSession` / `finish` 重写）；`VaultUi` 加 `roomReady`；删 `EnvelopeFlags`；`Dialog.Report` 加 `lockOnly`；`legacyRemains` 流 |
| 5 | `app/.../QuickUnlockDialogs.kt` / `VaultManagementScreen.kt` | 旧残留提示行 |
| 6 | `app/.../SettingsViewModel.kt` / `VaultListViewModel.kt` | 注入 `LegacyQuickUnlockCleanup`（**两处**都构造了控制器，别漏） |
| 7 | `app/src/main/res/values/strings.xml` | 三条文案 |
| 8 | 测试 | `LocalUnlockFanoutTest`（新建 8）+ `LegacyQuickUnlockCleanupTest`（新建 8）+ `QuickUnlockControllerTest` 的 `row()` 工厂补 `roomReady` 参数 |

### 过程坑（本批真踩的）

| 坑 | 解法 |
|---|---|
| detekt `TooManyFunctions` 43 > 40 | 把 `lockState` / `lockExists` / `anyLockExists` 挪到**文件级**（沿用批次 1 `normalizeServer` 的纪律） |
| 加 cleanup 后又 41 > 40 | 再把 `anyRoomSealed` 挪文件级 |
| 编译报 `No value passed for parameter 'cleanup'`（`VaultListViewModel.kt:98`） | `VaultListViewModel` **也**构造了 `QuickUnlockController`，两处都要注入 |
| `:app:compileFullDebugUnitTestKotlin` 报尾随 lambda 吃掉（`LocalUnlockFanoutTest.kt:171`） | **#75 同款**：带默认值的尾部参数会吃掉调用点的尾随 lambda —— 把 `outcomeOf` 挪到参数表**最后** |
| `LegacyQuickUnlockCleanupTest > 只删旧前缀下的键` 红 | 生产代码无条件调了 `removeLegacyQuickUnlockKeys(emptySet())`：空集也照调是白开一次 DataStore 写事务，且让「没有残留」与「清掉了残留」在调用轨迹上无法区分 ⇒ 加 `if (prefKeys.isEmpty()) 0 else …` 守卫 |
| 编译一直报 `Failed to find target with hash string 'android-37.0'` | **#128**：真凶是 Gradle daemon 里没失效的 SDK loader 缓存，不是 sdklib。`gradle --stop` + 删 `.gradle`/`~/.gradle/daemon` |

---

## 门禁实测（2026-09-29 夜，三关**分开**单跑，全部真跑）

| 关 | 命令 | 结果 |
|---|---|---|
| detekt | `gradle detekt` | ✅ BUILD SUCCESSFUL（6s） |
| 编译 | `:app:compileFullDebugKotlin` | ✅ BUILD SUCCESSFUL（43s） |
| 单测 | `:app:testFullDebugUnitTest` + `:data:repository:testDebugUnitTest` | ✅ 318 tests / **0 failed** / 0 skipped |

---

## 已知遗留（诚实记录）

1. **动作表重排没有单测覆盖**。原因不是偷懒：`QuickUnlockController` 要 mock
   `VaultRepository` + `LocalUnlockEnrollment` + `VaultixPreferences` + `LegacyQuickUnlockCleanup`
   全套依赖，而它内部**硬编码 `Dispatchers.IO`**（`withContext`），纯 JVM 下
   `runTest` 无法确定性推进那次线程跳转 ⇒ 强写会得到一个**间歇性红**的测试，
   比没有更糟。⇒ **随批次 3 的 `QuickUnlockControllerTest` 改写一起解决**
   （工作单给的方案就是把它改成两布尔 + 范围语义，那时顺带把调度注入化或改测编排层纯函数）。
   ⚠️ 也就是说：**动作表目前只有「三关绿」背书，没有行为级证据，真机验收要专门走一遍。**
2. `Partial` 三态**仍未删**（批次 3 的活）。`assemble` 里开关已不再产出 `Partial`
   （`lockState(exists)` 二值），但 `CapabilityState.Partial` 类型与 `deriveCapabilityState`
   仍在（`VaultListScreen` 等还在用）。
3. **真机全链路未验**（批次 5）。本批新增/改动的行为点，真机上要专门验：
   - 「已装指纹锁时再勾一个新库」⇒ **不应该**再弹指纹，只问一次主密码；
   - 「进程重启后（房钥匙不在内存）勾库」⇒ 应该在问主密码**之前**就提示先解锁一次；
   - 「开了门锁但一个房间都没建成」⇒ 门锁应被回滚（不留下「看着配好了实际不能用」）；
   - 老用户升级 ⇒ 设置页顶部应出现重登记提示，登记成功后旧信封被清掉。
4. 旧信封清理**只在登记成功路径上触发**：用户若一直不重新登记，残留会一直在
   （这是刻意的 —— 见「不半新半旧」，但意味着提示行是唯一的引导，别让它消失）。

## 批次 3 开工点（✅ 已于 2026-09-29 深夜完成）

1. **设置页简化**（工作单「删五类」）：每库标记 / `Partial` 态 / 三态推导 / 双键 / 逐库成败分类
   + 副标题文案 + `check_orphan_strings --gate`。
2. **`QuickUnlockControllerTest` 改写**为两布尔 + 范围语义 —— 顺便解决上面遗留 #1
   （把调度注入化，或把动作表的判定抽成可测的纯函数）。
3. 之后：批次 4（失效矩阵：rearm / 降级 / StaleCredentials 重包房间 / PIN 熔断全局 5 次）
   → 批次 5（真机验收 1-7）。

## 门禁命令速查

```bash
# gradle 用解压发行版绝对路径（./gradlew 本机不可用）
GRADLE=~/.gradle/wrapper/dists/gradle-9.5.1-bin/*/gradle-9.5.1/bin/gradle

"$GRADLE" -p D:/Vaultix detekt                          # 第一关
"$GRADLE" -p D:/Vaultix :app:compileFullDebugKotlin     # 第二关
"$GRADLE" -p D:/Vaultix :app:testFullDebugUnitTest \
                      :data:repository:testDebugUnitTest  # 第三关
# ⚠️ 三关分开单跑（连跑会触发 daemon 环境崩）；改完 detekt 必须再真跑 compile。
# ⚠️ 若报 "Failed to find target with hash string 'android-…'"：先 `gradle --stop`
#    并删掉 <project>/.gradle 与 ~/.gradle/daemon 再重试（见 .ai/ISSUES.md #128）。
# ⚠️ push 到 rele 分支 = 直接发正式版，日常只 push main。
```
