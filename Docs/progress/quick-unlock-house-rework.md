# 施工工作单：快速解锁「房子化」（两级钥匙层级）

> **自包含、零上下文可做。** 设计真源：[`.ai/decisions/快速解锁房子化-两级钥匙层级-定稿.md`](../../.ai/decisions/快速解锁房子化-两级钥匙层级-定稿.md)（下称「定稿」）；
> 根因与证据：[`Docs/progress/audit/bitwarden-kdbx-sync-audit.md`](audit/bitwarden-kdbx-sync-audit.md)（下称「报告」）。
> **论证一律看定稿，不在会话里重新论证。**
>
> 状态：🚧 施工中（**批次 1-4 + 批次 5.0 ✅ 2026-09-29 收工（含批次 3.5 真机验收反馈修复），
> 门禁三关全绿；批次 5（真机验收）未动。**
> 批次 4（失效矩阵）内容见下方专节；批次 5.0（软锁 + 前台门禁 + 解锁提速）见下方专节；
> 批次 3.5 内容见下方专节
> （「从不」档自动恢复对齐 Bitwarden + autofill 解密缓存 + 设置页三行精简）；
> **接力入口：[`house-rework-batch3-handoff.md`](house-rework-batch3-handoff.md)**——批次 3 的
> 删五类、判定逻辑可测化、结果页与副标题改动都在那份里；
> 批次 2（动作表 / 重登记向导）见 [`house-rework-batch2-handoff.md`](house-rework-batch2-handoff.md)；
> 钥匙层模型速查见 [`house-rework-batch1-handoff.md`](house-rework-batch1-handoff.md)。）
>
> 门禁纪律（每批次收尾必做，详见 `conventions/8.6-工程质量.md`）：**三关分开单跑**
> （detekt → compile → test，连跑会触发 daemon 环境崩）；**UP-TO-DATE ≠ 有效门禁**
> （必要时 `--rerun-tasks`）；**改完 detekt 必须再真跑 compile**（detekt 先于编译，掩蔽编译错误）。
> 任务名带 Full（flavor 化）；gradle 用解压发行版绝对路径（`./gradlew` 本机不可用）。
> ⚠️ `VaultRepositoryImpl` 已顶 40 函数上限：**新逻辑进新类，别往它身上堆。**

---

## 批次 0：P0 一分钟实验（用户配合，先做；判别 H1/H2）

1. adb 无线连接（端口轮换：`adb mdns services` 取当前端口；每条命令自带
   kill-server/start-server；多设备条目时必须 `-s`）。
2. 起全量 logcat 现场录制（静默过滤器下体积不变不能判死；分析前先 `grep -v adbd`）。
3. **把 OneDrive KDBX 库切为活跃库** → `am force-stop` → 冷启动到该库解锁页 →
   **指纹作为首个库解锁**（⚠️ 真手指，不可 adb 模拟）。
4. 读 `LocalUnlockFanout` 日志（`fanout first=…`）。

- **first 仍失败 = H1**（登记期 payload 坏）→ 做批次 0.5；
- **成功 = H2**（扇出授权缺口）→ 跳过 0.5，直接批次 1。
- 产出：结论写回本批次状态 + 报告 §3.4 勾选。

## 批次 0.5（仅 H1 成立）：止血补丁

`LocalUnlockEnrollment.commitForVaults`（L263-289）一把 cipher 连包 N 库 → 改**每库独立 cipher**。

- 代价如实告知用户：auth-per-use 下每库 wrap 各需一次指纹授权（N 库 = N 次弹窗）——
  这就是它只配当过渡的原因。
- 批次 1 落地后**整体删除**此补丁。

## 批次 1：钥匙层核心（最大批；先核对、后动工）

新增 `HouseKeyStore`（与 `LocalUnlockKeyStore` 同层）：

- 随机 256-bit 房钥匙；仅内存持有 + 用完即擦；**绝不落盘（定稿硬约束 #1）**；
- 指纹信封经 `LocalUnlockKeyStore.wrap/unwrap`（收窄为只对房钥匙一个 blob）；
- PIN 信封经 `PinKeyWrapper`（Argon2id）同款收窄；
- 房间信封每库一份 AES-GCM 纯软件封装（复用 `KdbxUnlockPayload` 长度前缀编解码）。

改造点（锚点见报告 §3）：

- `LocalUnlockKeyStore.kt:158-213` —— wrap/unwrap 语义收窄；KEK 创建不动；
- `LocalUnlockEnrollment.kt:263-289` —— 删多库连包 → 单包房钥匙 + 逐库软件封装；
- `LocalUnlockFanout.kt:84-104` —— rest 库「现取新 cipher」整段删除 →
  一次 Keystore 解密 + 软件解密循环；
- `VaultRepositoryImpl.kt:420-575` —— `enroll/completeLocalUnlock*` 契约改两级，新逻辑进新协调类；
- `PinUnlockStore` —— N 信封 → 1 信封；失败计数改全局。

**施工前先核对**：现有每库信封 blob 的实际存放位置与读写路径（报告未覆盖此细节，先取证再动）。

单测（批次收尾一并交付）：

- 房钥匙指纹/PIN 两信封各解出**同一把**房钥匙（往返）；
- 房间信封篡改必败（GCM tag）；
- 房钥匙明文擦除验证；
- fanout 语义 = 1 次 Keystore + N 次软件解密。

## 批次 2：登记与迁移（✅ 2026-09-29 夜完成，`61ea1a3`；三关全绿、单测 318 全过）

> 落地细节见 [`house-rework-batch2-handoff.md`](house-rework-batch2-handoff.md)。
> ⚠️ 已知遗留：**动作表重排没有单测覆盖**（控制器硬编码 `Dispatchers.IO`，纯 JVM 下
> `runTest` 无法确定性推进）⇒ 随批次 3 的 `QuickUnlockControllerTest` 改写一起解决。

- `QuickUnlockController` 动作表按定稿 §5 更新：勾库 = 软件封装不碰指纹；开锁 = 各一次 wrap；
  ⚠️ **房间信封只在至少一把门锁已存在时创建**（定稿 §5 顺序约束——否则房钥匙无落点，进程一死即孤儿信封）；
- 重登记向导：旧信封检测 → 提示 → 逐库问密码（可跳过）→ 派生房钥匙 → 包两把门锁 →
  软件包各房间 → 删旧信封；**中途失败不半新半旧**（同批生效或整体回退）；
- 不写新旧兼容层（定稿 §8）。

## 批次 3：设置页简化（✅ 2026-09-29 深夜完成；三关全绿、单测 328 全过）

- 删五类：每库「指纹/PIN」标记（范围列表变纯复选框）、Partial 态与「有 N 个库未完成」、
  三态推导（`QuickUnlockControllerTest` 7 条**改写**为两布尔 + 范围语义）、
  每库 `enabled`/`pinEnabled` 双键、结果页逐库成败分类（改「已纳入 N / 跳过 M」）；
- 副标题：指纹「用系统指纹打开所有已纳入的库」/ PIN「6 位数字；连续输错 5 次将锁定」；
- 孤儿串：`check_orphan_strings --gate`，**被取代的删**；
- ⚠️ XML 资源注释里别放 markdown 表格（`--` 会炸 aapt2）。

## 批次 3.5：真机验收反馈修复（✅ 2026-09-29 深夜完成；三关全绿、含新增 3 条 auto 信封用例）

> 触发：用户真机装 debug 版验收（定稿批次 5 的提前反馈）——「从不锁定下划掉后台后，
> 填充框弹条目不及时，看看 Bitwarden 的『从不』怎么实现的」+「解锁方式三行冗余」。
> **硬约束 #1 修订（定稿级，顶部横幅已加）**：「房钥匙绝不落盘」→「绝不以**明文**落盘」。

- **第三把锁（auto 恢复锁）**：`AutoUnlockKeyStore`（core:datastore，免认证 Keystore 密钥
  `setUserAuthenticationRequired(false)`）+ 信封 `house_lock_auto`；对标 Bitwarden
  `userAutoUnlockKey`（`reference/bitwarden/` 源码核证：keystoreEncryptedPreferences 载体、
  进程重启无交互恢复、主动锁删 key、解锁成功幂等补写——四条规则全部对齐）；
- **协调器 `AutoRestoreTrigger`**（app:security，`VaultixApplication` 字段注入强制早期构造）：
  重建挂「解锁成功事件」（`unlockedIds` **新增**元素）而非状态组合（防 lockVault 死角）；
  档位≠Never 且信封在→删；主动锁库（`VaultRepositoryImpl.lockVault/lockAll` 各一行）→删；
- **域接口**：独立 `AutoUnlockRepository`（VaultRepositoryImpl 顶格 40 函数，KdbxSyncRepository 先例）；
- **autofill 双管齐下**：`buildResponse` 1s 恢复等待窗口（对齐 Bitwarden
  `firstWithTimeoutOrNull(500)` 等 UNLOCKING）；`ItemRepositoryImpl.observeItems` 改
  **Eagerly 共享缓存**（原冷流每次 `.first()` 全量重解密 = 卡顿主源；锁库即清、明文可 GC）；
- **设置页三行→两行**：删「管理解锁方式」第三行；行点击 = 进向导（`manageBiometric`/
  `managePin`），开关 = On 关 / Off 进向导（关闭留给开关防误触）；
  `quick_unlock_manage_action` 保留（向导标题），`settings_quick_unlock_manage_desc` 删；
- **测试**：`HouseKeyStoreTest` +3（auto 生命周期 / 损坏自愈 / 门锁全删连带清）；
  `ItemRepositoryImplTest` 1 条按响应式契约改写（解锁后 `first{非空}`）。

## 批次 4：失效矩阵（✅ 2026-09-29 完成；三关全绿、单测 377 全过）

- rearm：开门状态检测平台密钥失效 → 内存房钥匙静默重包门锁信封；
- 降级：`ERROR_KEY_INVALIDATED` 类 → 禁用该锁 + 明确文案 + 回主密码
  （**绝不静默「本地解锁凭据不可用」**）；
- StaleCredentials：某库主密码变更 → 重包**该房间软件信封**，门锁不动；
- PIN 熔断改全局 5 次：旧每库计数作废，从 0 起；
- **auto 信封失效**（批次 3.5 新增）：`AutoUnlockKeyStore.decrypt` 返回 null（密钥不可用 /
  信封损坏）⇒ 就地删信封自愈（`HouseKeyStore.openAutoEnvelope` 已实现，单测已钉）。

> ⚠️ **rearm 的真实形态是「延迟重装」**，不是「静默重包」：硬约束 #2（auth-per-use：一次
> 授权只保一次 `doFinal`）⇒ 重写门锁信封必须再弹一次 `BiometricPrompt`。
> 故落地为「失效时只打标记 → 下次认证时补写」，决策理由与实现形态见
> **定稿 §6.1 实施记录**（用户 2026-09-29 拍板「可以接受重新安装」）。
> ⚠️ 开门 / 关门的唯一判据是 `HouseKeyStore.isUnlocked`；误用「信封存在」会把 rearm
> 走成降级、连带清掉房间信封。

本批新增 / 改动（10 个生产文件 + 6 个测试文件）：

| 层 | 文件 | 内容 |
|---|---|---|
| data:repository | `LocalUnlockFailure.kt` | 新增 `LocalUnlockFailureKind { Recoverable, Rearmable, Unavailable }` + `Throwable.localUnlockFailureKind()` |
| data:repository | `HouseKeyStore.kt` | `isFingerprintLockInvalidated()`（信封在 **且** KEK `INVALIDATED`）+ rearm 四方法 + 标记常量 |
| data:repository | `UnlockRecoveryRepositoryImpl.kt` **新** | 薄转发；`degradeFingerprintLock()` 如实算 `roomsRemoved` |
| data:repository | `RoomResealRepositoryImpl.kt` **新** | 复用 `LocalUnlockEnrollment` 只重包该房间软件信封 |
| data:repository | `RepositoryModule.kt` | 两个新 `@Binds` |
| domain | `UnlockRecoveryRepository.kt` **新** | 失效善后契约（含 `FingerprintDegradeReport`）+ 开门态/关门态判定表 KDoc |
| domain | `RoomResealRepository.kt` **新** | `resealRoom(vaultId, newMasterPassword): RoomResealOutcome` |
| app | `LocalUnlockFanout.kt` | 门锁失败 → `handleLockFailure(recovery)`：开门态打标记 / 关门态降级，三选一文案 |
| app | `UnlockViewModel.kt` | 接 `UnlockRecoveryRepository` + `RoomResealRepository`；`StaleCredentials` → `resealStaleRoomIfPossible()`（失败静默） |
| app | `QuickUnlockController.kt` / `QuickUnlockDialogs.kt` / `strings.xml` | 设置页「待重装」状态呈现（`biometricRearmPending`）；`onAuthenticated` 成功后清标记 |
| app | `SettingsViewModel.kt` / `VaultListViewModel.kt` | 接线 |

测试：`LocalUnlockFailureTest` +13（新）· `HouseKeyStoreTest` 18 · `UnlockRecoveryRepositoryImplTest` +10（新）·
`RoomResealRepositoryImplTest` +8（新）· `LocalUnlockFanoutTest` +7 · `StaleRoomResealTest` +6（新）·
`QuickUnlockControllerTest` +3。合计 **377 条零失败**。

## 批次 5.0：软锁 + 前台门禁 + 解锁提速（✅ 2026-09-29 完成；三关全绿、单测 398 全过）

> 触发：用户真机反馈两条 —— ①「设置了**从不**，锁屏/清后台后 Vaultix 直接就是开着的，
> 有风险」（对照：Bitwarden 会加锁）；②「指纹解锁进密码库要等好几秒，正常应秒解秒进」。
> **用户拍板方案 A**：对齐 Bitwarden —— 离开 App 就真锁（密钥清零），回来靠恢复信封免交互自动开。
> 完整理由与对照见 **定稿 §6.2 实施记录**。

### 问题①：Never 档「从不锁定」= 安全漏洞

- 旧实现 `VaultTimeout.Never -> return@launch` —— **任何原因都不锁**，房钥匙常驻内存，
  进程被内存转储时可捞到；除「锁定」按钮外无任何路径能清掉。
- 修复 = **离场软锁**：
  - `domain/AutoUnlockRepository` 新增 `softLock(): List<String>`；
  - `data/AutoUnlockRepositoryImpl.softLock()`：`houseKeyStore.lock()` 清房钥匙 →
    `sessions.lockAll()` + `Kdbx.lockAll()` 收两条会话模型 → **绝不 `removeAutoEnvelope()`**；
  - `app/VaultLockManagerImpl`：`Never` 分支在 `AppBackgrounded` 时调 `softLockForBackground()`
    （`AppCreated` 不调 —— 钥匙本就不在内存）。
- **前台门禁（安全底线）**：`AutoLockController` 新增 `isForeground`；
  `AutoRestoreTrigger` 的 `combine` 增第三源，**非前台一律不恢复** ——
  否则软锁后 `houseKeyInMemory=false` 会立刻被自己的恢复分支撤销，等于没锁。

### 问题②：指纹解锁慢

- 根因：`UnlockViewModel.candidateVaultIds()` 对每个库调
  `fingerprintQuickUnlockAvailable(id).first()` → 内含 `localUnlockKeyStore.keyAvailable`
  = **一次 Keystore 往返**（冷启动可达数百毫秒），N 库 × 数百毫秒全串行，
  且卡在「认证成功 → 结果返回」之间。
- 修复两层：
  1. 候选库改为**一次快照**：`preferences.quickUnlockScope().first()`（DataStore，零 Keystore）
   - 与库列表在内存求交；
  2. `completeLocalUnlock` 拆两段：**先开核心库 → 立即放行** → 异步补开其余库
     （`LocalUnlockFanout.openRest`，新增 `Result.lockOpened` 供判断房钥匙在不在）。

### 改动文件（6 生产 + 5 测试）

| 层 | 文件 | 内容 |
|---|---|---|
| domain | `AutoUnlockRepository.kt` | `+softLock()` |
| data:repository | `AutoUnlockRepositoryImpl.kt` | `+softLock()`（清密钥、留信封）+ 注入 `VaultSessionManager` |
| app | `AutoLockController.kt` | `+isForeground: StateFlow<Boolean>` |
| app | `VaultLockManagerImpl.kt` | Never 档 `AppBackgrounded` → `softLockForBackground()` |
| app | `AutoRestoreTrigger.kt` | 前台门禁（`combine` 三源） |
| app | `UnlockViewModel.kt` / `LocalUnlockFanout.kt` | 候选库一次快照 + 先开核心库异步补开 + `openRest`/`lockOpened` |

测试：`VaultLockManagerImplNeverTest` **新**（+3，**已做变异验证：还原旧实现必变红**）·
`AutoRestoreTriggerTest` **新**（+6，前台门禁）· `AutoUnlockRepositoryImplTest` **新**（+9，软锁不删信封）·
`UnlockViewModelTest` +3（候选筛选：排除目标 / 排除范围外 / 排除已解锁）·
`StaleRoomResealTest`（补 `VaultixPreferences` 参数）。合计 **398 条零失败**。

> ⚠️ **写测试踩过的坑**：`HouseKeyStore.lock()` / `isUnlocked` 是**非 suspend** 成员，
> 只能 `every`，用 `coEvery` 会静默失效（运行时才报 `no answer found`）；
> `softLock()` 内含 `withContext(Dispatchers.IO)`，纯 JVM `runTest` 下**必须轮询终态**，
> `advanceUntilIdle()` 管不到真实线程池。

## 批次 5：真机验收清单

1. 指纹一次 → 范围内全部库打开（日志：1 次 Keystore + N 次软件）；
2. 勾选 KDBX 库进范围：只弹主密码框，**不弹指纹**；
3. **硬约束 #1 专项**：解锁后杀后台 → 重启必须要求重新解锁（房钥匙未落盘的直接验证；
   ⚠️ **Never 档除外**——批次 3.5 后 Never 档应**免交互自动恢复**，改验下面第 8 条）；
4. 重录系统指纹：开门状态自动 rearm 无感；关门状态明确降级 + 主密码可进；
5. PIN 连错 5 次 → 全局熔断，主密码可进，重置恢复；
6. 升级迁移：老包升新包 → 重登记向导走通、旧信封已清；
7. 装机核证照旧（`dumpsys package` + `pm path` 拉 APK 比 SHA-256）；
8. **（批次 3.5 新增）Never 档填充及时性**：设置从不锁定 → 解锁一次 → 划掉后台 →
   任意 app 聚焦输入框 → 填充条目 **1s 内弹出**（恢复等待窗口 + 解密缓存，日志
   `VaultixAutoRestore` 可核）且**不再要求重新解锁**；随后主动锁库（库列表 ⋮ → 锁定）→
   再聚焦输入框 → **必须要求重新认证**（信封已删，真锁）；
9. **（批次 3.5 新增）设置页**：解锁方式组只剩两行；On 态点整行 = 进向导（不关闭），
   点开关 = 关闭；Off 态点行/开关 = 进向导。
10. **（批次 5.0 新增）Never 档离场软锁**：设置**从不**锁定 → 解锁一次 →
    划掉后台（或锁屏）→ 观察日志 `VaultixAutoRestore → 离场软锁` → **回到前台应免交互自动开**
    （信封保留）；随后主动锁库 → 必须要求重新认证（信封已删，真锁）。
    ⚠️ 与第 8 条的区别：第 8 条验**填充路径**，本条验**生命周期软锁**。
11. **（批次 5.0 新增）指纹提速**：多库（≥4）场景下指纹一次 → 进库应**秒进**（先开核心库放行），
    其余库在后台补开（日志 `fanout rest`）；不再出现「指纹过了等好几秒」。

---

## 完成定义

全批次三关门禁全绿 + 真机验收 1-11 全过 + 定稿补「实施记录」+ 本单各批次标 ✅。
