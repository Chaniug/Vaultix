# 接力文档：快速解锁「房子化」批次 3 收工（2026-09-29 深夜）

> **给下一个会话（或任何接手的人）。** 自包含，读完即可续作，无需翻历史会话。
> 前置阅读（本文件不重复其内容）：
> - 设计真源：[`.ai/decisions/快速解锁房子化-两级钥匙层级-定稿.md`](../../.ai/decisions/快速解锁房子化-两级钥匙层级-定稿.md)（下称「定稿」）；
> - 钥匙层模型 / 存储键 / 16 方法契约：[批次 1 接力文档](house-rework-batch1-handoff.md)；
> - 动作表 / 重登记向导 / 扇出门禁：[批次 2 接力文档](house-rework-batch2-handoff.md)；
> - 施工总单（批次 0→5）：[`quick-unlock-house-rework.md`](quick-unlock-house-rework.md)。

## 一句话状态

**批次 3（设置页简化）全部完成，门禁三关实测全绿（detekt / `:app:compileFullDebugKotlin` / 单测 328 全过），已提交推送 `main`。批次 4-5 未动。真机验收（批次 5）仍必做。**

## 本批做了什么（工作单「删五类」逐条对照）

| 工作单项 | 结果 |
|---|---|
| 删每库「指纹 / PIN」标记（范围列表变纯复选框） | ✅ `ConfigureRow` 只剩 `vaultId` / `name` / `checked`；删 `readyLabel` |
| 删 `Partial` 态与「有 N 个库未完成」 | ✅ `CapabilityState` 收敛为 `On` / `Off`；删 `deriveCapabilityState`；`CapabilityToggle` 变普通 `Switch` |
| 三态推导改写（`QuickUnlockControllerTest` → 两布尔 + 范围语义） | ✅ 7 条 → **17 条**，顺带把动作表判定也钉住了（见下） |
| 删每库 `enabled`/`pinEnabled` 双键 | ✅ 批次 1 已做 |
| 结果页逐库成败分类 → 「已纳入 N / 跳过 M」 | ✅ 成功/跳过改计数，**失败仍逐条列** |
| 副标题文案（定稿 §5.1） | ✅ 指纹「用系统指纹打开所有已纳入的库」/ PIN「N 位数字；连续输错 M 次将锁定」 |
| 孤儿串 `check_orphan_strings --gate` | ✅ 未超基线；本批**净删 4 条**，新增 2 条全部有引用 |

---

## 一、为什么 `Partial` 必须删（不只是"少一个状态"）

房子化之后，开关只对应**一把全局门锁**：开门锁 = 一次 wrap，要么成功要么不变
⇒ **「部分完成」在结构上不存在**（定稿 §5.1）。旧的 `Partial(n)`（「范围内还有 n 个库没配好」）
因此成了一个**永远产不出来**的状态。留着它的代价：

1. 每个渲染点多写一个**不可能走到**的分支 —— 死分支 = 每个读代码的人被骗一次；
2. 一旦有人又按「范围内 N 个库建好了几个」去推导开关，它会**悄悄复活**，
   而那正是 issue #93「谎报状态的开关」的成因（用户主动关掉后被显示成"还差几个"）。

⇒ 直接删掉，让**类型本身**说清"这东西只有装 / 没装两种事实"。

> 历史（保留在 `QuickUnlockDialogs` 的 KDoc 里，别删）：2026-09-26 曾把 `Partial` 从
> "关着的开关"改成「继续」按钮（#121）。那段设计针对的是**旧模型**（每库各一份信封、
> 开关按范围内进度推导）；新模型下它整体作废，开关重新变回普通二值开关。

## 二、★ 批次 2 遗留 #1 的解法：把判定逻辑放到**文件级纯函数**上

批次 2 收工时留了一条诚实遗留：**动作表重排没有单测覆盖** —— 控制器内部硬编码
`Dispatchers.IO`，纯 JVM 下 `runTest` 无法确定性推进那次线程跳转 ⇒ 强写会得到**间歇性红**的测试。

本批的解法是**把判定从编排里剥出来**，而不是给控制器打洞：

| 函数 | 负责的判定 | 为什么值得单测 |
|---|---|---|
| `lockState(exists)` | 开关 = 门锁存在性（**二值**） | #93 的复发点 |
| `locksToOpen(methods, ui)` | 只开**还没装**的门锁（动作表第 2 条） | 漏了它 ⇒ 用户每次进向导都要重按一次指纹 |
| `roomSealingBlocker(...)` | 封房间前的两道闸（门锁存在 + 房钥匙在内存） | 顺序约束（定稿 §5）的唯一判定点 |
| `assemble(vaults, scope, confirmed, …)` | 范围语义（默认全勾 / 房间存在性单维度 / 每库 ready 复合判定） | 「空有三态」那条纪律的落点 |

四个都是 `internal` + 文件级 ⇒ 单测可以直接钉，且不必 mock
`VaultRepository` / `LocalUnlockEnrollment` / `VaultixPreferences` / `LegacyQuickUnlockCleanup`。
顺带还给 `QuickUnlockController` 的函数数腾了位（detekt `TooManyFunctions` 40 卡线）。

⚠️ **这是有代价的取舍，别自欺**：这批用例证明的是「**判定逻辑**正确」，
**不是**「整条编排流程正确」（备料→弹认证→wrap→封房间→回退→清旧）。
后者仍要靠批次 5 的真机验收 —— 见「已知遗留」。

## 三、结果页：为什么成功/跳过报数、失败却要逐条

| 段 | 呈现 | 理由 |
|---|---|---|
| 已纳入 | **计数**（「已纳入 3 个库」） | 用户刚勾完 8 个库，再让他核对一份 8 行清单，他只会扫一眼 |
| 跳过 | **计数**（「跳过 1 个」） | 同上；且跳过是用户自己的选择，不需要逐库复核 |
| 未成功 | **逐条**（库名 · 原因） | 失败必须**可行动**：只知道"有 2 个失败了"但不知道是哪两个、为什么，用户唯一的出路是全部重来一遍 |

⇒ 数量给结论、逐条给出路 —— 两者不是同一种信息，不该用同一种呈现。

⚠️ 计数为 0 时**不显示**：空段只会把"这次其实没动库"演成"纳入了 0 个"（噪音）；
那种情况已由 `scopeOnly` / `lockOnly` 的说明句接住。

## 四、副标题文案（定稿 §5.1）

| 行 | 旧 | 新 | 为什么改 |
|---|---|---|---|
| 指纹 | 「用系统指纹或设备 PIN 验证，免输主密码」 | **「用系统指纹打开所有已纳入的库」** | 旧句把"设备 PIN"也算进指纹方式，而两者是**并列的两把门锁**（各有各的信封）⇒ 会让人以为开了指纹就等于开了 PIN；新句讲的是**作用范围** |
| PIN | 「用 N 位数字解锁，不依赖系统锁屏」 | **「N 位数字；连续输错 M 次将锁定」** | 旧句讲的是"不依赖什么"，而用户真正要提前知道的是**输错会怎样**（熔断） |

⚠️ PIN 那句的两个数字都由代码传入（`PIN_MIN_LENGTH` / `PIN_MAX_ATTEMPTS`），
**不写死在文案里**：文案里的数字一旦与阈值分叉，就是一句不成立的承诺（谎报状态那一族）。

## 五、本批落地清单（刀序）

| # | 文件 | 内容 |
|---|---|---|
| 1 | `QuickUnlockController.kt` | `CapabilityState` 删 `Partial`；`ConfigureRow` 删两个标记字段；`Dialog.Report` 的 `succeeded`/`skipped` → `enrolledCount`/`skippedCount`（`failed` 保留）；删 `deriveCapabilityState` / `succeededNames` / `skippedNames` / `matchingNames`；新增 `enrolledIds` / `skippedIds` |
| 2 | 同上（文件级） | `assemble` / `roomSealingBlocker` 挪到文件级并改 `internal`；新增 `locksToOpen`；`lockState` 改 `internal` |
| 3 | `QuickUnlockDialogs.kt` | `CapabilityToggle` 变普通 `Switch`（`checked` 由 `when` 分派）；`biometricSummary` / `pinSummary` 删 `Partial` 分支；`ConfigureVaultRow` 删行尾角标 + 删 `readyLabel`；`ReportSection` → `ReportCount` |
| 4 | `strings.xml` | 删 6 条（`partial_hint` / `partial_action` / `cap_biometric` / `cap_pin` / `report_succeeded` / 旧 `report_skipped`）；新增 2 条（`report_enrolled` / 新 `report_skipped`）；改 2 条副标题；带注释说明"被取代的删" |
| 5 | `QuickUnlockControllerTest.kt` | **改写**：7 条（三态推导）→ 17 条（开关二值 + 范围语义 + 动作表 + 顺序约束） |

### 过程坑（本批）

| 坑 | 解法 |
|---|---|
| 删 `succeeded`/`skipped` 后 `buildReport` 的 `names` 参数变孤儿 | 两个 `*Names` 函数改成 `*Ids`（返回 `Set<String>` 去重），`matchingNames` 整个删掉 |
| `roomSealingBlocker` 挪到文件级后拿不到 `enrollment.isHouseKeyReady` | 改成入参 `houseKeyReady: Boolean` —— 纯函数不该自己去抓依赖 |
| `enableForVault` 里原本另写了一份 `if (biometric is On)` | 改成共用 `locksToOpen(setOf(BIOMETRIC), snapshot)`，别留第二份判定 |

## 门禁实测（2026-09-29 深夜，三关**分开**单跑，全部真跑）

| 关 | 命令 | 结果 |
|---|---|---|
| detekt | `gradle detekt` | ✅ BUILD SUCCESSFUL |
| 编译 | `:app:compileFullDebugKotlin` | ✅ BUILD SUCCESSFUL |
| 单测 | `:app:testFullDebugUnitTest` + `:data:repository:testDebugUnitTest` | ✅ **328 tests / 0 failed / 0 skipped**（`QuickUnlockControllerTest` 17、`LocalUnlockFanoutTest` 8、`LegacyQuickUnlockCleanupTest` 8） |
| 孤儿串 | `python3 .ai/tools/check_orphan_strings.py --gate` | ✅ 未超基线（844 条中 120 条零引用） |

## 已知遗留（诚实记录）

1. **整条编排流程仍无自动化证据**。本批把**判定逻辑**钉住了，但
   「备料 → 弹认证 → 一次 wrap → 软封装 → 回退 → 清旧残留」这条链
   **只有三关绿背书**。要真正确认，只有批次 5 的真机验收。
2. `VaultUi.biometricReady` / `pinReady` **仍在**（服务于「已对 N 个库生效」的汇总数字）。
   它们与开关是两件事（复合判定 vs 门锁存在性），别拿它们去推导开关。
3. **真机全链路未验**。本批新增/改动的行为点，真机上要专门看：
   - 「已装指纹锁时再勾一个新库」⇒ **不弹指纹**，只问一次主密码；
   - 开关**永远只有开/关两态**（不会再出现「继续」按钮）；
   - 结果页显示「已纳入 N 个库 / 跳过 M 个」，失败仍能看到**是哪个库、为什么**；
   - 指纹副标题「用系统指纹打开所有已纳入的库」、PIN 副标题「6 位数字；连续输错 5 次将锁定」。

## 批次 4 开工点（下一轮从这里开始）

**失效矩阵（定稿 §6）**：

1. **rearm**：开门状态检测平台密钥失效 → 内存房钥匙静默重包门锁信封；
2. **降级**：`ERROR_KEY_INVALIDATED` 类 → 禁用该锁 + 明确文案 + 回主密码
   （**绝不静默「本地解锁凭据不可用」**）；
3. **StaleCredentials**：某库主密码变更 → 重包**该房间软件信封**，门锁不动；
4. **PIN 熔断改全局 5 次**：旧每库计数作废，从 0 起。

→ 之后批次 5（真机验收清单 1-7）。

## 门禁命令速查

```bash
# gradle 用解压发行版绝对路径（./gradlew 本机不可用）
GRADLE=~/.gradle/wrapper/dists/gradle-9.5.1-bin/*/gradle-9.5.1/bin/gradle

"$GRADLE" -p D:/Vaultix detekt                          # 第一关
"$GRADLE" -p D:/Vaultix :app:compileFullDebugKotlin     # 第二关
"$GRADLE" -p D:/Vaultix :app:testFullDebugUnitTest \
                      :data:repository:testDebugUnitTest  # 第三关
python3 .ai/tools/check_orphan_strings.py --gate        # 动过 strings.xml 就跑
# ⚠️ 三关分开单跑（连跑会触发 daemon 环境崩）；改完 detekt 必须再真跑 compile。
# ⚠️ 若报 "Failed to find target with hash string 'android-…'"：先 `gradle --stop`
#    并删掉 <project>/.gradle 与 ~/.gradle/daemon 再重试（见 .ai/ISSUES.md #128）。
# ⚠️ push 到 rele 分支 = 直接发正式版，日常只 push main。
```
