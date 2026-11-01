# 快速解锁房子化-两级钥匙层级-定稿 · 第 3 篇

> 主篇索引：[`快速解锁房子化-两级钥匙层级-定稿.md`](./快速解锁房子化-两级钥匙层级-定稿.md)
> 本篇 9 章 · 11.1KB · 起于「§7 会话语义：全屋常开（用户已拍板）」

## §7 会话语义：全屋常开（用户已拍板）

- 解锁 = 解出房子钥匙 → 各房间软件解密打开；填充/跨库搜索不再受「哪个库是活跃库」限制
  （排查报告里 `singleActiveVault` 取错库 → 静默空候选那类问题的**根治前提**）；
- **不做「切换后锁定旧库」**：用户威胁模型已排除该场景（§1 第二条）；
  手动锁 = 杀后台，性质由硬约束 #1 保住（Never 档例外：选了「从不」= 用户显式
  要求进程重启后自动恢复，但**用户主动锁库仍立即真锁**——恢复信封随之删除）；
  ⚠️ **2026-09-29 二次定稿补充**：Never 档下「切后台」**不锁**（对齐 Bitwarden，
  见 §6.3）——密钥常驻内存直到进程死亡，「杀后台即锁定」在 Never 档**由系统回收进程兑现**，
  不由应用主动清密钥；
- 顺带收益：填充场景一次指纹 → 全部库可用（今天 B′ 验证的场景里，fanout 的
  rest 库失败面从「N 次 Keystore」缩到「0 次」）；
- 若未来场景变化（常借手机等），**另立决策**加「切换后锁旧库」，本定稿不预埋。

---

## §8 迁移路径（一次性）

老用户的每库信封无法自动升级（旧信封 = 门锁直包每库凭据，与房子钥匙无关）：

1. 提示「快速解锁已升级，需重新登记一次」；
2. 解锁所有库（逐库问主密码，**复用现有流程与 UI**，可跳过、如实计未完成）；
3. 派生房子钥匙 → 包两把门锁信封 → 逐库软件包房间信封 → 删旧信封；
4. 中途失败：**不半新半旧混用**——门锁信封与房间信封要么同批生效、要么整体回退
   （失败原子性在施工工作单里细化，本定稿只定方向）。

⚠️ 呼应钥匙串定稿 §2「用户选择让用户重新装」的先例：本项目用户基数小，
重登记一次可接受，**不写新旧互转的兼容层**。

---

## §9 与 P0 决定性实验的关系（排期依据，不是阻塞）

实验照做（报告 §3.4：OneDrive 库切为活跃库 → 锁定 → 指纹作为首库解锁——
仍失败 = H1；成功 = H2）。作用变为「**先止血还是直接搬家**」：

- **H1 成立** → 有最小止血补丁（每库独立 cipher），房子化可从容排期；
- **H2 成立** → 现行结构**无最小补丁**（授权语义如此），房子化是唯一解，直接排最前。

---

## §10 影响面概览（施工工作单的输入，不是工作单本身）

| 层 | 文件/组件 | 方向 |
|---|---|---|
| core/datastore | `LocalUnlockKeyStore` | wrap/unwrap 收窄为只对「房子钥匙」；KEK 创建逻辑不变 |
| data/repository | `LocalUnlockEnrollment.commitForVaults` | 多库连包 → 单包房钥匙 + 逐库软件封装 |
| data/repository | `PinUnlockStore` / `PinKeyWrapper` | N 信封 → 1 信封（全局计数） |
| app/ui | `LocalUnlockFanout` | N 次 Keystore → 1 次 Keystore + 软件解密循环（大幅简化） |
| domain+data | `VaultRepositoryImpl` 的 `enrollLocalUnlock*` / `completeLocalUnlock*` | 契约改两级；⚠️ 该类已顶 `TooManyFunctions` 40 上限，新逻辑进新控制器 |
| app/ui | `QuickUnlockController` / `QuickUnlockDialogs` | 骨架保留，动作表按 §5/§5.1 更新（含 §5.1 删除清单） |
| 偏好 | 每库 `enabled` / `pinEnabled` ×N | 收敛为「门锁信封存在性」全局判定 + 每库「房间信封存在性」（真源仍是信封本身）；键位施工时定 |
| 测试 | `QuickUnlockControllerTest`（7 条三态语义锁） | 按 §5.1 **改写**为两布尔（门锁信封存在性）+ 范围语义；`KdbxUnlockPayloadTest` 保留复用 |

---

## §11 被否 / 刻意不学（连同理由，避免新会话重复提议）

| 方案 | 理由 |
|---|---|
| Keyguard 的持久会话（`Origin.Persisted`：主钥匙落盘、重启自动开） | **无条件**持久仍然不学（用户 23:39 拍板反着选）。2026-09-29 修订后的边界：Vaultix 只在 Never 档有**条件化**恢复信封（Keystore 加密、可被锁库撤销，见硬约束 #1）——与「无条件持久」不同物 |
| 「切换后锁定旧库」设置 | 用户威胁模型已排除；预埋 = 过度设计（需要时另立决策） |
| 每库信封打补丁续命（H1 止血修法） | 仅当 H1 成立时作为**止血备选**；终局仍是本定稿 |
| 包裹「整库明文」以省掉主密码 | 钥匙串定稿 §6 已否（与 `Kdbx.kt:18`「明文绝不落盘」冲突），不重议 |
| 新旧信封兼容互转层 | 见 §8：一次性重登记，不写兼容层 |

---

## §12 证据索引

- P0 真机日志 ×4（first=Success / rest=Unknown(本地解锁凭据不可用) / opened=0）：
  `Docs/progress/audit/evidence/2026-09-28-bprime-verify-VaultixAutofill.log`
- 排查报告：`Docs/progress/audit/bitwarden-kdbx-sync-audit.md`（H1/H2 推理 §3.2；实验 §3.4；P0.5 §3.3）
- Keyguard 源码核证（`D:/Vaultix-refs/keyguard-app/common/src/commonMain/kotlin/com/artemchep/keyguard/common/`）：
  - `model/MasterSession.kt` —— 一把 app 级主钥匙；`Origin.Persisted`（落盘加载、不能假设用户已认证）vs `Origin.Authenticated`
  - `usecase/impl/EnableBiometricImpl.kt:88-93` —— 唯一信封 `FingerprintBiometric(iv, encryptedMasterKey)`
  - `usecase/impl/UnlockUseCaseImpl.kt:343-385`（一次授权一次解包）/ `:386-397`（`ERROR_KEY_INVALIDATED → disableBiometricQuietly`）/ `:599-618`（`rearmBiometricIfNeeded` 开门换锁）
- Vaultix 现状代码：`LocalUnlockKeyStore.kt:158-213`（wrap 先读 iv + auth-per-use KEK）、
  `LocalUnlockEnrollment.kt:263-289`（一把 cipher 连包 N 库）、`LocalUnlockFanout.kt:84-104`（rest 库现取新 cipher）、
  `VaultRepositoryImpl.kt:420-575`（`localUnlockAvailable` / `completeLocalUnlock` 自愈 vs `completeLocalUnlockKdbx` 勿删登记）
- 2026-09-28 真机取证：`vault_timeout = Never`（DataStore 手解 protobuf）；3 库；
  `pin_unlock_enabled_*` ×3、`local_unlock_enabled_*` ×2（登记状态每库漂移的实证）

---

## §13 定稿记录与后续

1. ✅ 2026-09-28 23:48 用户拍板「房子化吧」（追加设置页简化，§5.1）；
2. ✅ 草案转定稿（本文件）；两篇旧定稿顶部已加「已被修订」横幅（历史保留，勿删）；
3. ✅ 施工工作单已成：`Docs/progress/quick-unlock-house-rework.md`（批次 0 实验起，含真机验收）；
4. ✅ 2026-09-29 硬约束 #1 修订（顶部横幅）：真机验收反馈 + Bitwarden `userAutoUnlockKey`
   对标 ⇒ 「绝不落盘」→「绝不明文落盘」；Never 档自动恢复信封落地
   （`AutoUnlockKeyStore` / `AutoUnlockRepository` / `AutoRestoreTrigger`）；
   同日设置页三行精简为两行（行点击 = 进向导管理，见 `QuickUnlockSettingsRows` KDoc）。
4. ✅ 2026-09-29 **批次 4（失效矩阵，§6）落地**：四类失效全部有实现 + 单测，
   门禁三关全绿（`data:repository` 104 + `app` 273 = 377 条零失败），孤儿串未超基线。
   实现形态见 **§6.1 实施记录**（含延迟重装的决策理由、与 Bitwarden 的有意偏离）。
5. ⏳ **下一步 = 批次 5（真机验收清单 1-9）**：需用户真手指，清单见
   `Docs/progress/quick-unlock-house-rework.md` 批次 5。
6. ✅ 2026-09-29 **批次 5.0（软锁 + 前台门禁 + 解锁提速）落地**（见 **§6.2**）：
   用户真机反馈「从不档锁屏/清后台不锁库」+「指纹解锁要等好几秒」，
   拍板**方案 A**。门禁三关全绿（`data:repository` 113 + `app` 285 = **398 条零失败**），
   孤儿串未超基线。
   新增回归门禁：`VaultLockManagerImplNeverTest`（已做**变异验证** —— 还原旧实现必变红）、
   `AutoRestoreTriggerTest`、`AutoUnlockRepositoryImplTest`、`UnlockViewModelTest` 候选筛选三条。

---

## §14 ★ 2026-09-30 傍晚修订：**取消"生效范围"选择**（用户拍板）

用户原话：「设置里面的解锁方式…我要的是简洁，这两个一定是**全库都启用**的。不需要什么
生效的库还是什么的选择项，必选的部分，这是二者可以都开或者只开一种。」

⇒ 落地（门禁已过）：

- **向导里的逐库复选框列表删除**（`ConfigureRow` / `toggleConfigureVault` / `ConfigureVaultRow`
  一并删）：配置一律 = **当前全部库**；
- 「取消勾选会清掉该库凭据」那条后果**随动作一起消失**（不再有取消勾选这回事），
  对应的提示串与 §5.1 的"勾库两步登记"一栏作废；
- 向导只说一句**如实说明**：「本次配置将对全部 N 个密码库生效，无需逐个选择」；
- 到「一个库都没有」时不再给「开始配置」（一个必然失败的按钮 = 空承诺）；
- 两个方式开关**互不联动、可只开一种**（维持 §5.1 既有语义）。

⚠️ **§5.1 里的「生效范围 —— 纯复选框列表」与本文 §5 的「保留：范围列表本身」**
**以本节为准**（那两处说的"排除高价值库"这个安全口子，用户明确不要）。

⚠️ **连带**：`preferences.confirmQuickUnlockScope(vaultIds)` 仍必需（它是"房间信封已建"
的镜像，`roomReady` 的真源），只是现在每次都等于"全部库"；`removeVaultFromScope()`
**失去唯一调用方**（见多库锁模型定稿 §9 的待定项）。

---

## §15 ★ 2026-09-30 晚：**配置向导弹窗整体删除 → 拨开关就是配置**

用户原话：「解锁方式里面的生物验证和PIN码…这两个选项是可以点击、有弹出页面的，这个弹出页面
做的不好看，逻辑混乱，让人摸不着头脑，需要简化。…只需要设置一个打开的按钮即可」。

⇒ 落地（门禁已过）：`Dialog.Configure` / `ConfigureDialog` / `ConfigureMethodRow` /
`toggleConfigureMethod` / `confirmConfigure` / `showConfigure` / `manageUnlock` /
`manageBiometric` / `managePin` **全部删除**。现在：

| 点哪里 | 干什么 |
|---|---|
| **整行** | 与拨开关**完全等效**（向导没了，"点行"不再有第二种含义） |
| **开关本体** | `Off` → `startEnrollment(该方式)`；`On` → 关掉这把门锁 |

流程本身不变（`各库主密码 → PIN 输入 → 指纹认证 → 结果`），只是**入口少了一层**。
另加**重入保护**（流程进行中忽略新的拨动）。

### ★ 关键认识：原向导想省的"重复输主密码"**根本不存在**

向导当年的理由（本文 §5.1 与能力级定稿）是「两种方式各跑一遍 ⇒ 每个 KDBX 库要输两遍主密码」。
但**房间信封是每库一份、与方式无关**（它包的是该库凭据，不是"某种方式"的凭据）⇒
先开指纹时已把房间封好，再开 PIN 时 `pendingRooms()` 为空、**不会再问**。
⇒ 那张"把两种方式放一起勾"的表格**从来就没有存在必要**（这也解释了删掉它为何零代价）。

⚠️ 由此作废：§5.1 目标形态里的「三个设置行」表述，以及能力级定稿里"合并成一次流程"的设计；
   以本节 + `QuickUnlockSettingsRows` 的 KDoc 为准。
⚠️ 「旧模型残留」提示行**不再可点**（它与开关做的是同一件事），文案已改为"打开下面的开关"。
