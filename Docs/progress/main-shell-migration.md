# 主界面骨架迁移方案（Bastion 双/多 Tab → Vaultix）

> 决策依据 2026-09-10：需求定调「**Bitwarden 的功能 + Bastion 的页面**」，
> 用户拍板选项 **A**（照搬 Bastion 的底部导航结构）。
> 本文档是**执行前的勘察报告 + 分步计划**，不是最终实现。
> **状态：阶段 1 + 阶段 2 已完成（2026-09-12 第三十七轮），待真机验收。**
> 剩余：阶段 3 观感（Tab 转场动画、卡片样式）。
>
> 📌 **修订记录**：初稿把「多库」误判为导航障碍（A1/A2/A3 三方案）；
> 用户指出「bastion 多库在设置页面里面，基本上解锁就能进单库」→ 复核源码证实
> Bastion 主界面**零 `vaultId`**，多库是**筛选状态**而非导航层 → 方案改为 **A4**。
> 再经用户澄清项目本质（见 §0）→ A4 的「库筛选」进一步明确为**单活跃库**语义。

---

## 0. ★★ 项目本质（2026-09-10 用户澄清，最高共识）

**用户原话**：
> 「我这个项目相当于只是从 bastion 里面把它本地的一个本地库 bastion 拔掉，
> 保留 bitwarden 能力和 kdbx 能力，但是这个能力在**登录的时候只能进一样**。
> 类似 keyguard 的做法，因为这样的条目就不会错乱和保存重复之类的了」

### 0.1 项目定义（一句话）

> **Vaultix = Bastion 去掉「私有本地库」，保留「Bitwarden 能力 + KDBX 能力」，
> 且二者在登录时二选一（单一活跃库）。**

### 0.2 为什么是「二选一」——不是限制，是设计

**「登录时只能进一样」是一个刻意的架构决策，理由（用户给出）**：
> **「这样的条目就不会错乱和保存重复之类的了」**

即：**避免跨后端条目混乱与重复保存**。若 Bitwarden 库与 KDBX 库同时活跃，
同一个网站会有两条来源不同的条目 → 自动填充时无法裁决用哪条、
保存新密码时无法确定写回哪个库 → 条目错乱 / 重复条目。

**这与 Keyguard 的做法一致**（Vaultix 本就对标 Keyguard 路线，
见 MEMORY「产品定位」）。

### 0.3 与 Bastion 的关键差异

| | Bastion | Vaultix |
|---|---|---|
| 库类型 | **私有本地库** + Bitwarden + KeePass/KDBX | **Bitwarden + KDBX**（无私有本地库） |
| 库数量 | 可同时挂多个（`BitwardenRepository` 有 vault 表 + `KEY_ACTIVE_VAULT_ID`） | **单一活跃库**（登录即定） |
| 条目来源 | 多后端聚合 + 跨库去重（`DedupEngine`） | **单来源**，无需去重 |
| UI 统一模型 | `UnifiedCategoryFilterSelection`（**需要**统一多后端） | **不需要**——单库无「统一」问题 |

⚠️ **重要推论**：Bastion 的 `UnifiedCategoryFilterSelection` 是为了**抹平多后端差异**
而存在的。Vaultix **不需要这个抽象**——因为按定义只有一条数据线。
→ 所以 A4 对「筛选状态」的实现应当**大幅简化**为「当前活跃库」单个值，
而非 Bastion 那套 filter union。

### 0.4 对迁移方案的直接影响

1. **`MainShellRoute` 不需要「库筛选器 UI」**——只需持「当前活跃库 id」（单值）
2. **多库切换 = 切换活跃库**（不是筛选叠加）；切换入口放设置页
3. **不搬** `UnifiedCategoryFilterSelection`（多后端产物）
4. **不搬** `DedupEngine`（单库无需去重）
5. Tab 内容（密码/验证器/通行密钥）**天然共用同一活跃库**，无歧义

---

## 1. 勘察结论：Bastion 的「双 Tab」实际是「可配置多 Tab」

⚠️ **先纠正一个预期偏差**：先前描述为「双 Tab（密码 / 验证器+通行密钥）」，
实际读源码后发现 Bastion 的底部导航远比这复杂：

**`reference/bastion/.../ui/main/navigation/BottomNavModel.kt`**
- **9 个导航项**：`VaultV2` / `Passwords` / `Authenticator` / `CardWallet` /
  `Generator` / `Notes` / `Send` / `Passkey` / `Settings`
- 每项有 **fullLabel + shortLabel** 两套文案
- 支持**用户自定义排序**（`BottomNavContentTab.DEFAULT_ORDER` +
  `sanitizeOrder()` 去重补全）

**`reference/bastion/.../data/AppSettings.kt`（`BottomNavVisibility`）**
- 每项有**独立的可见性开关**，默认值：
  ```
  passwords=true  authenticator=true  cardWallet=true  passkey=true  notes=true
  generator=false  send=false  vaultV2=false
  ```
- 即「默认可见 5 项、可开关到 8 项」

**`AdaptiveMainScaffold.kt`（103 行，纯 UI）**
- 窄屏 → `NavigationBar`（底部）；宽屏 → `NavigationRail`（侧边，可滚动）
- **无 `SettingsManager` 耦合，可原样搬**

---

## 2. ★ 核心障碍：导航范式冲突（必须先决策）

这是本次迁移**唯一的架构级难点**，其余都是常规搬迁。

> **🔴 本节已于 2026-09-10 重写（用户纠正）**：初稿把「多库」定性为导航障碍，
> 并给出 A1/A2/A3 三方案 —— **该定性错误**。经源码复核，Bastion 根本不把多库
> 当导航问题（详见 §2.0）。原 A1/A2/A3 是伪命题，正确路径是 **A4**。

### 2.0 ★ 源码纠正：Bastion 如何处理多库（用户判断正确）

**实证**（`reference/bastion/` grep 结果）：
```
grep -rn "vaultId" reference/bastion/.../ui/main/   → 零命中
```
→ **Bastion 主界面（含所有 Tab 内容）完全没有 `vaultId` 概念**。

多库的真实处理方式：**`vaultId` 是「筛选条件」，不是「导航参数」**。

**`VaultV2Pane.kt` 的 `UnifiedCategoryFilterSelection`**——把「库」降级为
与「文件夹」「分类」平级的**过滤维度**：
```kotlin
UnifiedCategoryFilterSelection.BitwardenVaultFilter(vaultId)      // 按库筛
UnifiedCategoryFilterSelection.BitwardenFolderFilter(vaultId, folderId)  // 按文件夹筛
UnifiedCategoryFilterSelection.KeePassDatabaseFilter(databaseId)  // 按 KDBX 库筛
UnifiedCategoryFilterSelection.KeePassGroupFilter(databaseId, groupPath)
```

**用户原话印证**：「bastion 多库在设置页面里面，基本上解锁就能进单库啊」
→ 即：**解锁后直接进主界面**（不做库选择）；多库管理是设置里的功能。

| | Vaultix 现状 | Bastion |
|---|---|---|
| **起点** | `VaultListRoute`（库列表） | 解锁 → `Screen.Main`（**直接进主界面**） |
| **导航层次** | 列表 → 解锁 → 条目（线性栈） | 主界面（Tab 容器）→ 各内容页 |
| **库与 UI 的关系** | **路由参数**（`ItemsRoute(vaultId)` 等 4 条） | **筛选状态**（`UnifiedCategoryFilterSelection`），主界面无此参数 |
| **多库入口** | 库列表页（一级） | **设置页 / `VaultV2` Tab（默认关闭）** |
| **锁定时** | 回 `VaultListRoute` 清栈 | 回 `Screen.Login` |

### 2.1 正确路径：A4「vaultId 参数 → 筛选状态」降级

| 方案 | 做法 | 代价 | 说明 |
|---|---|---|---|
| **A4 ✅ 推荐** | `vaultId` 从路由参数改为**Tab 容器内的筛选状态**；解锁后直接进 `MainShellRoute`；多库管理移入设置 | 中 | **对齐 Bastion 真实做法**，用户已认可该范式 |
| ~~A1 单库 Tab 化~~ | ~~Tab 容器只承载当前库；多库回列表~~ | — | 伪命题：仍把库当导航层 |
| ~~A2 Tab 内嵌库选择~~ | ~~Tab 内加库切换器~~ | — | Bastion 不在主界面放库选择器 |
| ~~A3 库列表降为 Tab~~ | ~~库列表变成 Tab 之一~~ | — | 伪命题：Bastion 的 `VaultV2` 是**筛选/管理页**，非导航必需项 |

**A4 的关键设计**：
- `MainShellRoute` **不带 `vaultId`**；内部持「当前库筛选」状态（默认=唯一已解锁库）
- 各 Tab（Passwords / Authenticator / Passkey）**共用同一个库筛选**
- 库切换：① 库列表页仍保留（`VaultListRoute` 作为**解锁/添加**入口）；
  ② 多库场景下在设置页提供管理入口（对齐 Bastion）
- `VaultListRoute` 的角色变化：**从「主页」变为「入口/解锁页」**
  —— 与 Bastion「解锁即进主界面」对齐

---

## 3. 分步计划（A4）

### 阶段 1：可独立搬运件（零架构风险，先做）
- [x] **搬 `AdaptiveMainScaffold.kt`**（103 行，纯 UI）
      → `app/.../ui/shell/AdaptiveMainScaffold.kt`；`BottomNavItem` 引用改本地枚举
      - ⚠️ **需扩展**：原版无「+」按钮。宽屏 `NavigationRail` 亦需插入「+」
- [x] **搬悬浮胶囊底栏**（`SimpleMainScreen.kt` 内联实现，~110 行）
      → `app/.../ui/shell/VaultixBottomDock.kt`（**新建文件，抽取内联实现**）
      - 精确规格见 §6.1.1（胶囊 60dp / 圆角 50 / 留白 12·6·20 /「+」52×48dp 圆角 16）
      - 行为参数化：`onAdd: (currentTab) -> Unit`，由容器按当前 Tab 分发
- [x] **建本地导航模型** `app/.../ui/shell/VaultixNavModel.kt`
      - 枚举 Vaultix 需要的 Tab（**按用户答复定稿**）：
        `Passwords` / `Authenticator` / `CardWallet` / `Settings`
        （**共 4 个 Tab + 中央「+」**；通行密钥并入验证码页入口，见 §6.1.2）
      - 暂不搬「自定义排序」（后续可选；Vaultix 偏好层加 `bottomNavOrder`）
- [x] **图标与文案**：`Lock` / `Security` / `Wallet` / `Settings` / `Add` / `Fingerprint`，
      字符串入 `strings.xml`
- [x] **搬卡面视觉组件**（988 行中的可搬部分，见 §6.1.3）
      - `CardBrandIcon.kt`（242 行）+ `CardBrandLibraryLogo.kt`（212 行）
        → `app/.../ui/cardwallet/`；`com.bastion.app.data.model.CardBrand` →
        `io.vaultix.common.CardBrand`；GPL 溯源声明保留
      - 校验：Vaultix `core/common/CardBrand.kt` 的枚举值是否覆盖 Bastion 全部品牌
        （VISA/MASTERCARD/AMEX/DINERS/DISCOVER/JCB/...）

### 阶段 2：Tab 容器接线（核心）
- [x] 新增 `MainShellRoute`（**无 `vaultId` 参数**，对齐 Bastion）
- [x] **★ 活跃库状态载体**：新建 `ActiveVaultStore`（单例 / ViewModel 持有
      `StateFlow<String?>`，**单值，非集合**）
      - 语义：**当前活跃库 only**（见 §0「登录时只能进一样」）
      - 初始值：登录/解锁成功时那一个库
      - 切换：设置页「库管理」→ 切换活跃库（**互斥**，不同时活跃）
      - ⚠️ **不搬** Bastion `UnifiedCategoryFilterSelection`（多后端产物，见 §0.3）
- [x] `VaultixApp` 导航图调整：
      ```
      VaultListRoute ──点已解锁库 / 解锁成功──▶ MainShellRoute（不进 Unlock）
      VaultListRoute ──点已锁定库──▶ UnlockRoute ──成功──▶ MainShellRoute
      MainShellRoute 内：Tab 切换（不新增路由）
      ```
      ⚠️ 与现状差异：解锁成功后**进 `MainShellRoute` 而非 `ItemsRoute`**
- [x] **各 Tab 复用现有 Screen**，改造点（Tab 集合已定稿：密码/验证码/卡包/设置）：
      | Tab | 复用 | 改造点 |
      |---|---|---|
      | Passwords | `ItemsScreen` | 去 `onBack`；`vaultId` 改从 `ActiveVaultStore` 取；回收站入口移入 Tab 内菜单 |
      | Authenticator | `TotpCodesScreen` | 去 `onBack`；`vaultId` 同上；**通行密钥入口改造**（见 §6.1.2：进度条右侧 + 空态兜底） |
      | CardWallet | **新建**（搬卡面视觉组件，见 §6.1.3） | 内容源 = `Cipher type=3`；`vaultId` 同上 |
      | Settings | `SettingsScreen` | 去 `onBack`；**新增「库管理」入口**（切换活跃库） |
- [x] **二级页仍走路由**：`ItemRoute` / `TrashRoute` / `AutofillSettingsRoute` /
      `PasskeysRoute` 照旧 push（Tab 容器不拦截）；`ItemRoute` 仍需 `vaultId`
- [x] **★ 全局活跃库真源（用户提问引出的关键扩展）** —— 2026-09-12 第三十七轮**全部收敛完毕**
      `ActiveVaultStore` 不只是导航态，已覆盖 **autofill / CP / 保存**三层：
      | 消费方 | 原状 | 现在 |
      |---|---|---|
      | 主界面 Tab | 路由参数 `vaultId`（**构造期一次性取值，切库不跟随**） | `ActiveVaultStore`（流驱动，切库后内容自动跟随） |
      | `VaultixAutofillService.collectCandidates` | `for (vaultId in unlocked)` 遍历所有已解锁库 | `singleActiveVault(unlocked)` **只取活跃库** |
      | `VaultixCredentialProviderService.buildGetResponse` | `observeUnlockedVaultIds()` | 同上（`sources`） |
      | `PasskeyCreateActivity` | `unlockedVaultIds` 全量列表 | 只有活跃库（下拉仍显示库名，但不可选到别的库） |
      | `AutofillSaveViewModel.resolveTarget` | `unlocked.firstOrNull()` 任取一个 | 活跃库 |
      | `ManualFillViewModel` | `combine` 聚合所有已解锁库 | 只订阅 `activeVaultId` |
      | `SettingsViewModel.passkeyCount` | 跨库累加 | 只数活跃库（口径与填充侧一致） |
      > 📌 依据：用户指出「条目不会错乱和保存重复」——该问题**真实存在于自动填充层**
      > （云端库与 KDBX 库同时解锁时，同网站出现两条来源不同的候选）。
      >
      > ⚠️ 关键实现点：新增 `ActiveVaultStore.resolve()`（挂起、**每次真实重算**）。
      > 原因：`activeVaultId` 由 `init` 协程异步填充，系统冷启动可能只拉起 autofill / CP 服务
      > （主界面从未打开）→ 首帧未到时读到 `null`。**不能**「读到 null 就退化成遍历全部」，
      > 那会重新引入要消灭的问题；必须等一次真实计算。
- [x] **设置页「库管理」入口**：`VaultSection` + `ActiveVaultDialog`，只列**已解锁**库
      （锁定库没有内存密钥，切过去也是空列表）；切换后 Tab / autofill / CP 三处同时生效
- [x] **「+」按钮分发**：按 `VaultixNavItem.addTarget` 分发；**密码 Tab 的「+」弹类型选择器**
      （避免在密码页建出 SecureNote 等不该出现的类型），验证码/卡包直接进对应编辑器
- [x] **锁定处理**：`lockEpoch` 触发时从 `MainShellRoute` 清栈回 `VaultListRoute`（现状保持）

### 阶段 3：观感对齐（Bastion 视觉细节）
- [ ] **页面滑动效果**（用户明确要求）：搬 Bastion Tab 切换的转场动画
      → 参考 `AuthenticatorPasskeyAnimatedContent.kt`（43 行）、`LocalSharedTransition.kt`（18 行）
      → 以及 `CompactDraggableTabContent.kt`（411 行，**可拖拽 Tab 内容**，重点参考）
- [ ] 主界面卡片样式（参考 `PasswordTabPane.kt` / `NoteListCardComponents.kt`）
- [ ] FAB 行为（参考 `MainScreenFab.kt` 的 1044 行——按需取用，不整体搬）
- [ ] 设置页分组结构（参考 `SettingsScreen.kt` + `SettingsComponents.kt`）
- [ ] Tab 保留滚动状态（参考 `VaultV2RetainedSnapshotStore.kt`——切 Tab 回来不丢位置）

---

## 4. ⛔ 明确不搬

**4.1 架构差异产物（见 §0 项目本质）**

| Bastion 文件 | 不搬理由 |
|---|---|
| `UnifiedCategoryFilterSelection`（`VaultV2Pane.kt` 内） | **多后端聚合抽象**——Bastion 需要抹平「私有库+Bitwarden+KDBX」差异；Vaultix 单活跃库无此需求 |
| `DedupEngineScreen.kt` | 多后端合并才需要去重；**单库来源不可能重复**（用户点明的设计目的） |
| `LocalKeePass*.kt`（4 个） | Vaultix 无「私有本地库」；KDBX 能力走 M2 的 `data:kdbx` 引擎，非 UI 层对应物 |
| `WebDavBackupScreen.kt` / `OneDriveBackupScreen.kt` | Bitwarden API 已覆盖同步；KDBX 走本地文件 |
| `VaultV2Pane*.kt`（5 个） | 库筛选/管理页——被 `ActiveVaultStore` + 设置页「库管理」取代 |

**4.2 非可搬单元（体量/耦合原因）**

| Bastion 文件 | 说明 |
|---|---|
| `SimpleMainScreen.kt`（3274 行） | **不是可搬单元**——Bastion 所有 Tab 内容的巨型聚合，按 Tab 拆解后**按需参考** |
| `MainScreenFab.kt`（1044 行） | 同上，按需取 FAB 逻辑片段 |

**4.3 暂不引入（能力缺口）**

| Bastion 文件 | 说明 |
|---|---|
| `SendScreen.kt` / `SendPane.kt` | Vaultix 无 Send（安全分享）能力，暂不引入 |

---

## 5. 工作量与风险

**预估**：阶段 1 ≈ 0.5 天；阶段 2 ≈ 1.5–2.5 天（A4 的 `vaultId` 降级比 A1 略重）；阶段 3 ≈ 按需。

**主要风险**：
1. **`vaultId` 传递链重构（最大风险）**——`ItemsViewModel` / `TotpCodesViewModel` /
   `PasskeysViewModel` / `TrashViewModel` 等目前通过 `SavedStateHandle` 取 `vaultId`；
   A4 要求改为从 **`ActiveVaultStore`** 注入。这是阶段 2 最易出错处，需逐 ViewModel 核对
2. **各 Screen 的 `onBack` 语义**——现有 Screen 都假设自己是栈顶，
   改为 Tab 内容后要清理返回逻辑与顶栏（建议逐个过一遍）
3. **`ItemRoute` 仍带 `vaultId`**——条目详情属于具体库，需保留参数；
   注意「切换活跃库」与「已 push 的 ItemRoute」的一致性（避免显示错库条目）
4. **活跃库切换的用户可见性**——`VaultListRoute` 保留为入口/解锁页；设置页新增
   「库管理」，确保切换活跃库的入口可发现
5. **自动锁定**——`lockEpoch` 清栈逻辑要覆盖新的 `MainShellRoute`
6. **单活跃库的持久化**——活跃库 id 需落盘（参考 Bastion `KEY_ACTIVE_VAULT_ID`，
   存 SecurePrefs），否则重启后无从恢复

**门禁**：每阶段完成后跑 `compileFullDebugKotlin` + `detekt` +
`testFullDebugUnitTest`，全绿再进下一步。

---

## 6. 用户已明确的要求（2026-09-10 原话）

> 「我要搬到就是 bastion 里面的底部导航，底部导航条里面有**密码页面、验证码页面、
> +号按钮（添加条目的）、卡包页面、设置页面**，然后就是**页面的滑动效果**，
> **界面风格**之类的」

### 6.1 Tab 集合（✅ 已定，用户 2026-09-10 答复）

| # | Tab | 图标（Material） | 复用/新建 |
|---|---|---|---|
| 1 | 密码 | `Icons.Default.Lock` | 复用 `ItemsScreen` |
| 2 | 验证码 | `Icons.Default.Security` | 复用 `TotpCodesScreen` |
| 3 | **「+」按钮** | `Icons.Default.Add` | **导航条内**（非 Tab，见 §6.1.1） |
| 4 | 卡包 | `Icons.Default.Wallet` | 复用条目视图 + **搬卡面 UI**（见 §6.1.3） |
| 5 | 设置 | `Icons.Default.Settings` | 复用 `SettingsScreen` |

#### 6.1.1 ★「+」按钮：导航条内的圆角方块（非 FAB）

**用户答复**：「1、在底部和导航条一起的。」

**Bastion 实现规格**（`SimpleMainScreen.kt:2075-2145`，精确参数）：
```kotlin
// 底栏 = 悬浮胶囊（Surface）
Surface(
    shape = RoundedCornerShape(50),                    // 全圆角胶囊
    color = MaterialTheme.colorScheme.surfaceContainerHigh,
    tonalElevation = 3.dp,
    shadowElevation = 6.dp,
)
// 胶囊内 Row：height(60.dp)，左右留白 start/end=12dp, top=6dp, bottom=20dp（总高 82dp）
// 布局：左侧 2 个 Tab + 中间「+」 + 右侧 2 个 Tab（各 weight(1f) 均分）

// 中间「+」= 圆角方块（非 FAB！）
Surface(
    onClick = { /* 行为随当前 Tab 变化 */ },
    shape = RoundedCornerShape(16.dp),
    color = MaterialTheme.colorScheme.primary,
    contentColor = MaterialTheme.colorScheme.onPrimary,
    modifier = Modifier.width(52.dp).height(48.dp),
) { Icon(Icons.Default.Add, modifier = Modifier.size(26.dp)) }
```

**关键行为**：「+」的动作**随当前 Tab 变化**：
| 当前 Tab | 「+」行为 |
|---|---|
| 密码 | 新建密码条目 |
| 验证码 | 新建 TOTP |
| 卡包 | 新建卡片 |
| 设置 | 回退为新建密码（Bastion `else ->` 分支） |

⚠️ **与标准 `AdaptiveMainScaffold` 的差异**：Bastion 的 `AdaptiveMainScaffold.kt`
用的是标准 `NavigationBar`（无「+」），而**实际主界面用的是自定义悬浮胶囊**
（`SimpleMainScreen.kt` 内联实现）。→ **两者都要搬**：宽屏走 `AdaptiveMainScaffold`
的 `NavigationRail`，窄屏走自定义悬浮胶囊。

#### 6.1.2 ★ 通行密钥入口：验证码界面内的小按钮

**用户答复**：「2、bastion 的做法是放在验证码界面，然后在验证码界面放一个小按钮，点击进入的。」

**Bastion 实现规格**（`TotpListContent.kt:942-987`）：
```kotlin
// 验证器 → 通行密钥快捷入口
val passkeyEntryButton = @Composable {
    IconButton(onClick = onNavigateToPasskeys, modifier = Modifier.size(32.dp)) {
        Icon(
            imageVector = Icons.Default.Fingerprint,   // ⚠️ 指纹图标，非钥匙
            contentDescription = stringResource(R.string.nav_passkey),
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}
```
**两处挂载位置（含兜底，重要）**：
1. **首选**：挂在**统一倒计时进度条右侧**（`UnifiedProgressBar(trailingContent=...)`）
2. **兜底**：进度条关闭 **或当前无 TOTP 条目**时 → **独立成行靠右显示**

> 📌 Bastion 注释原文：「进度条被关闭或当前无 TOTP 条目时改为独立成行兜底显示，
> **否则用户会在空列表等场景下彻底失去进入通行秘钥页的路径**」
> → **这是踩过坑的设计，搬迁时不可简化掉兜底分支。**

**→ Vaultix 对应改动**：`TotpCodesScreen` 顶栏的通行密钥按钮**改为进度条右侧 + 空态兜底**
（Vaultix 现有实现是顶栏按钮，不受进度条开关影响，但仍应加空态兜底以保证一致体验）。

#### 6.1.3 ★ 卡面 UI：搬视觉组件，不搬 pane

**用户答复**：「3、卡面 UI 也搬过来吧。」

**Bastion 卡包 UI 总量 988 行**，但**存在一条清晰的迁移分界线**：

| 文件 | 行数 | 可否搬 | 依据 |
|---|---|---|---|
| `CardBrandIcon.kt` | 242 | ✅ **可搬** | 仅依赖 `CardBrand`（Vaultix 已有 `core/common/CardBrand.kt`）+ 日志工具 |
| `CardBrandLibraryLogo.kt` | 212 | ✅ **可搬** | 品牌 logo 矢量库，纯资源 |
| `WalletListItem.kt` | 129 | ✅ 可搬 | 列表项视觉 |
| `WalletMaterialIcons.kt` | 41 | ✅ 可搬 | 图标定义 |
| `CardWalletDetailPaneContent.kt` | 178 | ⚠️ **改造后搬** | 视觉可复用，但需去掉 Bastion 私有条目类型引用 |
| `CardWalletPane.kt` | 118 | ❌ **不搬** | 依赖 `BankCardViewModel`/`DocumentViewModel`/`BillingAddressViewModel`（Bastion **私有条目类型**，Vaultix 无 Document/BillingAddress） |
| `CardWalletContent.kt` | 53 | ⚠️ 参考 | 容器结构，按 Vaultix 条目模型重写 |
| `CardWalletSyncScope.kt` | 15 | ❌ 不搬 | Bitwarden 同步作用域，Vaultix 架构不同 |

**★ 分界线判据**：
> **只搬「纯视觉组件」（依赖 `CardBrand` 等 Vaultix 已有模型），
> 不搬「以 Bastion 私有条目类型为参数的容器」。**

**Vaultix 侧的衔接点**：
- 已具备 `core/common/CardBrand.kt`（批次⑥ 搬运的检测器）+ `CardBrandTest`（7 例）
- **缺的就是 UI 图标层** → 搬 `CardBrandIcon.kt` 正好补齐「检测器 → 可视化」这一环
- 卡包 Tab 的内容源 = Bitwarden `Cipher type=3`（Card）条目

✅ **枚举覆盖度已核对（2026-09-10）**：Bastion 与 Vaultix 的 `CardBrand` **完全一致**
（同为 18 项：VISA / MASTERCARD / AMERICAN_EXPRESS / DINERS_CLUB / DISCOVER / JCB /
UNIONPAY / MAESTRO / MIR / RUPAY / ELO / DANKORT / MADA / MEEZA / TROY / UATP /
FORBRUGSFORENINGEN / UNKNOWN，`displayName` 亦逐项相同）
→ **`CardBrandIcon.kt` 的 `when(this)` 分支可零改动搬运，仅需改 import 路径。**

### 6.2 明确要求的观感项（阶段 3 必做）

- **页面滑动效果**——Tab 切换转场动画，参考：
  - `AuthenticatorPasskeyAnimatedContent.kt`（43 行，AnimatedContent 转场）
  - `LocalSharedTransition.kt`（18 行，共享元素过渡）
  - `CompactDraggableTabContent.kt`（411 行，**可拖拽 Tab 内容**，重点参考）
- **界面风格**——卡片样式 / 配色 / 间距 / 圆角，参考 `PasswordTabPane.kt` +
  `NoteListCardComponents.kt` + `theme/` 目录

### 6.3 已答复（原待确认项，2026-09-10 全部关闭）

| # | 问题 | 用户答复 | 落地 |
|---|---|---|---|
| 1 | 「+ 号」形态 | **「在底部和导航条一起的」** | 悬浮胶囊内的圆角方块（非 FAB）→ §6.1.1 |
| 2 | 通行密钥页位置 | **「放在验证码界面，放一个小按钮点击进入」** | 并入验证码 Tab → §6.1.2 |
| 3 | 卡面 UI | **「卡面 UI 也搬过来吧」** | 搬视觉组件（§6.1.3 有分界线） |

### 6.4 待确认（本轮新发现）

1. **「+」在「设置」Tab 时的行为**——Bastion `else -> handlePasswordAddOpen()`
   即回退为「新建密码」。Vaultix 是否照此？（或设置 Tab 时隐藏「+」？）
2. **是否要「自定义 Tab 排序与可见性」**（Bastion 完整能力）？
   建议先固定顺序，骨架稳后再加（代价仅偏好层加一个字段）
3. **卡包 Tab 是否包含「文档卡 / 账单地址」**——Bastion 有，Vaultix 无对应条目类型
   → 建议**只做银行卡**（Bitwarden `Cipher type=3`），不引入私有类型
4. **`CardBrand` 枚举覆盖度核对**——搬 `CardBrandIcon.kt` 前需确认 Vaultix
   `core/common/CardBrand.kt` 是否含 Bastion 全部品牌分支（发现缺项需补齐）
