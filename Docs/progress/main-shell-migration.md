# 主界面骨架迁移方案（Bastion 双/多 Tab → Vaultix）

> 决策依据 2026-09-10：需求定调「**Bitwarden 的功能 + Bastion 的页面**」，
> 用户拍板选项 **A**（照搬 Bastion 的底部导航结构）。
> 本文档是**执行前的勘察报告 + 分步计划**，不是最终实现。
> **状态：勘察完成 → 方案已按用户纠正修订为 A4，待确认后开工。**
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
- [ ] **搬 `AdaptiveMainScaffold.kt`**（103 行，纯 UI）
      → `app/.../ui/shell/AdaptiveMainScaffold.kt`；`BottomNavItem` 引用改本地枚举
- [ ] **建本地导航模型** `app/.../ui/shell/VaultixNavModel.kt`
      - 枚举 Vaultix 需要的 Tab：`Passwords` / `Authenticator` / `Passkey` /
        `Generator` / `Settings`（**先不做 Send/CardWallet/Notes/VaultV2**）
      - 暂不搬「自定义排序」（后续可选；Vaultix 偏好层加 `bottomNavOrder`）
- [ ] **图标与文案**：复用 Material Icons（`Lock` / `Security` / `Key` /
      `AutoAwesome` / `Settings`），字符串入 `strings.xml`

### 阶段 2：Tab 容器接线（核心）
- [ ] 新增 `MainShellRoute`（**无 `vaultId` 参数**，对齐 Bastion）
- [ ] **★ 活跃库状态载体**：新建 `ActiveVaultStore`（单例 / ViewModel 持有
      `StateFlow<String?>`，**单值，非集合**）
      - 语义：**当前活跃库 only**（见 §0「登录时只能进一样」）
      - 初始值：登录/解锁成功时那一个库
      - 切换：设置页「库管理」→ 切换活跃库（**互斥**，不同时活跃）
      - ⚠️ **不搬** Bastion `UnifiedCategoryFilterSelection`（多后端产物，见 §0.3）
- [ ] `VaultixApp` 导航图调整：
      ```
      VaultListRoute ──点已解锁库 / 解锁成功──▶ MainShellRoute（不进 Unlock）
      VaultListRoute ──点已锁定库──▶ UnlockRoute ──成功──▶ MainShellRoute
      MainShellRoute 内：Tab 切换（不新增路由）
      ```
      ⚠️ 与现状差异：解锁成功后**进 `MainShellRoute` 而非 `ItemsRoute`**
- [ ] **各 Tab 复用现有 Screen**，改造点：
      | Tab | 复用 | 改造点 |
      |---|---|---|
      | Passwords | `ItemsScreen` | 去 `onBack`；`vaultId` 改从 `ActiveVaultStore` 取；顶栏回收站入口移入 Tab 内菜单 |
      | Authenticator | `TotpCodesScreen` | 去 `onBack`；`vaultId` 同上 |
      | Passkey | `PasskeysScreen` | 去 `onBack`；`vaultId` 同上 |
      | Generator | **新建**（搬 Bastion `GeneratorScreen`） | 全新建，无库依赖（纯计算） |
      | Settings | `SettingsScreen` | 去 `onBack`；**新增「库管理」入口**（切换活跃库） |
- [ ] **二级页仍走路由**：`ItemRoute` / `TrashRoute` / `AutofillSettingsRoute`
      照旧 push（Tab 容器不拦截）；`ItemRoute` 仍需 `vaultId`（条目属于具体库）
- [ ] **锁定处理**：`lockEpoch` 触发时从 `MainShellRoute` 清栈回 `VaultListRoute`（现状保持）

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

### 6.1 Tab 集合（✅ 已定，5 项）

| # | Tab | 图标（Material） | 复用/新建 |
|---|---|---|---|
| 1 | 密码 | `Icons.Default.Lock` | 复用 `ItemsScreen` |
| 2 | 验证码 | `Icons.Default.Security` | 复用 `TotpCodesScreen` |
| 3 | **+ 号按钮**（添加条目） | `Icons.Default.Add` | **非 Tab**——是 FAB/中央按钮，触发新建条目流程 |
| 4 | 卡包 | `Icons.Default.Wallet` | 复用卡片条目视图（Vaultix 已有 CardBrandDetector） |
| 5 | 设置 | `Icons.Default.Settings` | 复用 `SettingsScreen` |

⚠️ **注意「+ 号按钮」的定位**：用户原话把它列在导航条里 → 对齐 Bastion 的
`MainScreenFab.kt`（1044 行），即**底部导航中央的突出 FAB**，不是普通 Tab。
→ 需确认：是 NavigationBar 中央的 docked FAB，还是独立悬浮 FAB？

⚠️ **通行密钥 Tab 的去留**：用户此次未提通行密钥页（此前讨论有）。
→ 需确认：并入「密码」Tab 内入口，还是保留独立 Tab？

### 6.2 明确要求的观感项（阶段 3 必做）

- **页面滑动效果**——Tab 切换转场动画，参考：
  - `AuthenticatorPasskeyAnimatedContent.kt`（43 行，AnimatedContent 转场）
  - `LocalSharedTransition.kt`（18 行，共享元素过渡）
  - `CompactDraggableTabContent.kt`（411 行，**可拖拽 Tab 内容**，重点参考）
- **界面风格**——卡片样式 / 配色 / 间距 / 圆角，参考 `PasswordTabPane.kt` +
  `NoteListCardComponents.kt` + `theme/` 目录

### 6.3 待确认（剩余）

1. **「+ 号按钮」形态**：NavigationBar 中央 docked FAB，还是独立悬浮 FAB？
2. **通行密钥页**：并入密码 Tab 内入口，还是保留独立 Tab？
3. **是否要「自定义 Tab 排序与可见性」**（Bastion 完整能力）？
   建议先固定顺序，骨架稳后再加（代价仅偏好层加一个字段）
4. **卡包 Tab 的数据来源**：Vaultix 的卡片是 Bitwarden `Cipher type=3` 条目，
   与 Bastion 的 `CardWalletPane`（有独立卡面可视化）差异较大——
   是仅复用筛选视图，还是要搬 Bastion 的卡面 UI？
