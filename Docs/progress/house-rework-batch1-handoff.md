# 接力文档：快速解锁「房子化」批次 1 收工（2026-09-29）

> **给下一个会话（或任何接手的人）。** 自包含，读完即可续作，无需翻历史会话。
> 设计真源：[`.ai/decisions/快速解锁房子化-两级钥匙层级-定稿.md`](../../.ai/decisions/快速解锁房子化-两级钥匙层级-定稿.md)（「定稿」）；
> 施工总单：[`quick-unlock-house-rework.md`](quick-unlock-house-rework.md)（批次 0→5）。

## 一句话状态

**批次 1（钥匙层核心）代码施工全部完成，门禁三关实测全绿（detekt / compileFullDebugKotlin / 单测，含新增 HouseKeyStoreTest 6 用例），已提交推送 `main`。批次 2-5 未动。**

## 模型速查（两级钥匙）

```
门锁层（全局各一把，各只有一个信封）
┌──────────────────────┐    ┌──────────────────────┐
│ 指纹锁 · Keystore KEK │    │ PIN 锁 · Argon2id    │
│ auth-per-use         │    │ 全局 5 次熔断         │
└──────────┬───────────┘    └──────────┬───────────┘
           └───────────┬───────────────┘
                       ▼
       房子钥匙（随机 256-bit，仅内存、绝不落盘）
                       │
       ┌───────────────┼───────────────┐
       ▼               ▼               ▼
  房间信封①        房间信封②      …（每库一份，纯软件 AES-GCM，AAD 绑 vaultId）
```

- **H1**（一把 cipher 连包 N 库）与 **H2**（rest 库现取新 cipher 无人授权）已**结构性消灭**：解锁路径上唯一碰 Keystore 的是 `completeFingerprintUnlock`，其后全是软件解密。
- 顺序约束（定稿 §5）：**开第二把锁必须先解第一把锁**（否则两把门锁包不同钥匙）——由 `HouseKeyStore.obtainKeyForLockEnrollment` 守卫，违反返回 `LockEnrollResult.HouseKeyUnavailable`。
- 孤儿清理：最后一把门锁删除时房间信封全部连带清掉（`trimRoomsIfNoLocksRemain`）。

## 存储键（全部在 SecureCredentialStore）

| 键 | 内容 |
|---|---|
| `house_lock_fingerprint` | KEK 包裹的房钥匙（`LocalUnlockKeyStore.wrap` 产物） |
| `house_lock_pin` | Argon2id(PIN) 包裹的房钥匙（`PinKeyWrapper.wrap` 产物） |
| `house_pin_attempts` | PIN 全局失败计数（一份） |
| `house_room::<vaultId>` | 房钥匙软封装的库凭据（AAD = `vaultix-room-v1:<vaultId>`） |
| （DataStore）`fingerprint_lock_enrolled` / `pin_lock_enrolled` | 门锁存在性**镜像**（真源是上面的信封键） |
| （DataStore）`QUICK_UNLOCK_SCOPE` | 房间信封存在性**镜像**（真源是 `house_room::` 前缀） |

## 新契约速查（`domain/VaultRepository.kt`，16 方法两级语义）

```kotlin
// 可见性（Flow）
fingerprintLockAvailable(): Flow<Boolean>            // 全局指纹门锁
pinLockAvailable(): Flow<Boolean>                    // 全局 PIN 门锁
fingerprintQuickUnlockAvailable(vaultId): Flow<Boolean>  // 门锁在 && 该库房间在

// 指纹门锁（Keystore KEK，auth-per-use）
prepareFingerprintEnroll(): Cipher?                  // 开锁第一步（备授权 cipher）
enrollFingerprintLock(cipher): Boolean               // 开锁第二步（包房钥匙；false = 顺序约束）
prepareFingerprintUnlock(): Cipher?                  // 解锁第一步（null = 门锁未启用/KEK 失效）
completeFingerprintUnlock(cipher): Boolean           // 解锁第二步（唯一 Keystore 操作）
disableFingerprintLock()                             // 关锁（+孤儿清理）

// PIN 门锁（Argon2id，全局计数）
validatePin(pin): PinEnrollOutcome?                  // 位数门槛（先验后做昂贵事）
enrollPinLock(pin): Boolean                          // 开锁（覆盖旧信封 = 改 PIN）
openHouseWithPin(pin): PinUnlockOutcome              // 解锁（成功即清失败计数）
disablePinLock()                                     // 关锁（+孤儿清理）

// 房间信封（生效范围）
sealRoomsForVaults(prepared): Map<String, LocalUnlockEnrollOutcome>  // 软封装（不收 cipher）
removeVaultFromScope(vaultId)                        // 取消勾选（删房间，不碰门锁）
quickUnlockCandidateVaultIds(): List<String>         // 全部库 id（不过滤）

// 解锁扇出
unlockVaultFromRoom(vaultId): RoomUnlockOutcome      // 软件解密 + 按类型开库
```

关键类型：`RoomUnlockOutcome`（Opened/StaleCredentials/Unavailable）、`PinOpen`（Opened/WrongPin/LockedOut/Unavailable，成功不带 payload）、`LockEnrollResult`（Enrolled/HouseKeyUnavailable）、`RoomOpen`（Opened/NoKey/NotEnrolled/Damaged）。

⚠️ 备料入口 `LocalUnlockEnrollment.prepareForVaults` **故意不在接口上**（`VaultRepositoryImpl` 函数数卡 detekt 40 上限）——控制器直接注入 `LocalUnlockEnrollment` 调用，沿用 2026-09-16 的纪律。

## 批次 1 落地清单（刀序）

| # | 文件 | 内容 |
|---|---|---|
| 1 | `core/datastore/SecureCredentialStore.kt` | 新增 `keysWithPrefix`（只读键名探存在性，不过 Keystore） |
| 2 | `core/datastore/VaultixPreferences.kt` | 删每库双键（`isLocalUnlockEnabled` 等四组）；加全局门锁镜像键 `fingerprint_lock_enrolled` / `pin_lock_enrolled` |
| 3 | `data/repository/HouseKeyStore.kt` | **新建**（~470 行）：两级钥匙层唯一协调者 + `PinOpen`/`RoomOpen`/`LockEnrollResult` + `toFailureOutcome`/`toRoomFailure` 扩展 |
| 4 | `core/datastore/LocalUnlockKeyStore.kt` | KDoc 语义收窄（只包房钥匙一个 blob），零行为改动 |
| 5 | `domain/VaultRepository.kt` | 契约 17→16 方法；删 `KdbxEnrollOutcome`；新增 `RoomUnlockOutcome`；修复接口提前闭合的结构破损 |
| 6 | `data/repository/LocalUnlockEnrollment.kt` | 重写为「备料 + 软封装」：`prepareForVaults`（Bitwarden 当场取 fullKey）+ `sealRoomsForVaults`（前置 isUnlocked）；删 `commitForVaults` 等 |
| 7 | （删除三文件） | `PinUnlockStore.kt` / `PinEnrollment.kt` / `PinEnrollmentCoordinator.kt`（职责并入 HouseKeyStore + LocalUnlockEnrollment） |
| 8 | `data/repository/VaultRepositoryImpl.kt` | 新契约全套实现 + `unlockVaultFromRoom`（按 kind 分流）+ `removeRoomEnvelope`；删旧 PIN 区段；`lockAll` 首行 `houseKeyStore.lock()`；`normalizeServer` 挪文件级（函数数保 40 卡线） |
| 9 | `app/.../LocalUnlockFanout.kt` | **重写**：一次 `completeFingerprintUnlock` + 循环 `unlockVaultFromRoom`；首库特殊化消失；门锁开失败早退 |
| 10 | `app/.../UnlockViewModel.kt` | 入口订阅换新契约；`submitPin` 两段式（开门锁→开房间）；`completeLocalUnlock` 消费 `RoomUnlockOutcome` 三态 |
| 11 | `app/.../AutofillActivity.kt` | `prepareBiometricUnlock` 换全局门锁 cipher；CP 判据 `is RoomUnlockOutcome.Opened` |
| 12 | `app/.../VaultListViewModel.kt` | banner 可用性换 `fingerprintQuickUnlockAvailable` |
| 13 | `app/.../QuickUnlockController.kt` | **编译级适配**（见下方遗留）；`executeSession` 重构为「备料一次→先 PIN 后指纹门锁→房间统一 seal」；`onAuthenticated` 两段式；`disableAll` 关全局门锁 + 范围镜像清零 |
| 14 | `app/.../SettingsViewModel.kt` | 删死方法 `disableQuickUnlock`（零调用方） |
| 15 | 测试 | `UnlockViewModelTest` / `VaultRepositorySignOutTest` / `VaultRepositoryRemoveTest` 适配新契约；**新建 `HouseKeyStoreTest`（6 用例）** |

## 门禁实测（2026-09-29，全部真跑）

| 关 | 命令 | 结果 |
|---|---|---|
| detekt | `gradle detekt` | ✅ BUILD SUCCESSFUL |
| 编译 | `:app:compileFullDebugKotlin` | ✅ BUILD SUCCESSFUL |
| 单测 | `:app:testFullDebugUnitTest` + `:data:repository:testDebugUnitTest` | ✅ 全过（含 HouseKeyStoreTest） |

HouseKeyStoreTest 覆盖：①两门锁解出同一把钥匙（重启后双路径开同一房间）②篡改必败（改密文/AAD 错位）③sealRoom 后入参全零 ④顺序约束双向拒绝 ⑤最后一把门锁删除→房间全清。

## 已知遗留（诚实记录，批次 1 内的取舍）

1. **QuickUnlockController 只做了编译级适配**——动作表按定稿 §5 的完整重排、`Partial` 三态简化（批次 3 删）、副标题文案，都还是旧形态。当前行为可用但有几处别扭：`targetsFor(method)` 的判定基础还是「每库每方式」快照（新语义下两方式共享房间信封，部分场景会重封房间——幂等无害，但批次 2 应收干净）。
2. **单测第四类（fanout 语义 = 1 次 Keystore + N 次软件）没写**——需要 mock `VaultRepository` 断言 `completeFingerprintUnlock` 恰好 1 次、`unlockVaultFromRoom` 恰好 N 次。落点：`app/src/test/.../LocalUnlockFanoutTest.kt`（新建）。
3. **旧数据清理未做（批次 2 重登记向导）**：老用户升级后，旧 DataStore 键（`local_unlock_*` / `pin_unlock_*`）与旧信封 blob（`LOCAL_UNLOCK_STORAGE_PREFIX` 前缀）会残留成垃圾。定稿 §8 明确**不写兼容层**，但一次性清理（检测→提示→删）属于批次 2。
4. **批次 0（P0 一分钟实验）跳过**：批次 1 的结构性修复同时消灭 H1/H2，判别实验已无必要；真机验收（批次 5 清单）仍必做。
5. **真机全链路未验**——尤其「指纹一次开多库」「杀后台后必须重新解锁（硬约束 #1）」。

## 批次 2 开工点（下一轮从这里开始）

1. **QuickUnlockController 动作表完整重排**（定稿 §5）：
   - 勾库 = 纯软件封装，**不碰指纹不碰门锁**（`sealRoomsForVaults`）；
   - 开锁 = 各一次 wrap（`enrollFingerprintLock` / `enrollPinLock`）；
   - ⚠️ 房间信封只在至少一把门锁已存在时创建（顺序约束——否则房钥匙无落点）；
   - `Session.targetsFor` 收敛为「房间信封存在性」单维度。
2. **重登记向导**：旧信封检测（`LOCAL_UNLOCK_STORAGE_PREFIX` 与旧 DataStore 键）→ 提示 → 逐库问密码（可跳过）→ 派生房钥匙 → 包两把门锁 → 软包各房间 → 删旧信封；**中途失败不半新半旧**（同批生效或整体回退）。
3. **LocalUnlockFanoutTest**（单测第四类，半小时的活）。

## 批次 3-5 摘要（详见工作单）

- **批次 3（设置页简化）**：删五类（每库标记 / Partial 态 / 三态推导 / 双键 / 逐库成败分类）+ 副标题 + `check_orphan_strings --gate`。`QuickUnlockControllerTest` 7 条用例**改写**为两布尔 + 范围语义。
- **批次 4（失效矩阵）**：rearm / 降级（`LocalUnlockFailure.kt` 的 `isLocalUnlockUnrecoverable` 仍在、测试仍绿，直接复用）/ StaleCredentials 重包房间信封 / PIN 熔断全局 5 次。
- **批次 5（真机验收）**：工作单清单 1-7。

## 门禁命令速查

```bash
# gradle 用解压发行版绝对路径（./gradlew 本机不可用）
GRADLE=~/.gradle/wrapper/dists/gradle-9.5.1-bin/*/gradle-9.5.1/bin/gradle

"$GRADLE" -p D:/Vaultix detekt                          # 第一关
"$GRADLE" -p D:/Vaultix :app:compileFullDebugKotlin     # 第二关
"$GRADLE" -p D:/Vaultix :app:testFullDebugUnitTest \
                      :data:repository:testDebugUnitTest  # 第三关
# ⚠️ 三关分开单跑（连跑会触发 daemon 环境崩）；改完 detekt 必须再真跑 compile。
# ⚠️ push 到 rele 分支 = 直接发正式版，日常只 push main。
```
