# 主界面骨架迁移方案（Bastion 双/多 Tab → Vaultix）

> 决策依据 2026-09-10：需求定调「**Bitwarden 的功能 + Bastion 的页面**」，
> 用户拍板选项 **A**（照搬 Bastion 的底部导航结构）。
> 本文档是**执行前的勘察报告 + 分步计划**，不是最终实现。
> **状态：勘察完成 → 方案已按用户纠正修订为 A4，待确认后开工。**
>
> 📌 **修订记录**：初稿把「多库」误判为导航障碍（A1/A2/A3 三方案）；
> 用户指出「bastion 多库在设置页面里面，基本上解锁就能进单库」→ 复核源码证实
> Bastion 主界面**零 `vaultId`**，多库是**筛选状态**而非导航层 → 方案改为 **A4**。

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
- [ ] **库筛选状态载体**：新建 `CurrentVaultFilter`（Compose 状态 / ViewModel 持有），
      默认值 = 唯一已解锁库；多库时供各 Tab 共用
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
      | Passwords | `ItemsScreen` | 去 `onBack`；`vaultId` 改从筛选状态取（非路由参数）；顶栏回收站入口移入 Tab 内菜单 |
      | Authenticator | `TotpCodesScreen` | 去 `onBack`；`vaultId` 同上 |
      | Passkey | `PasskeysScreen` | 去 `onBack`；`vaultId` 同上 |
      | Generator | **新建**（搬 Bastion `GeneratorScreen`） | 全新建，无库依赖 |
      | Settings | `SettingsScreen` | 去 `onBack`；**新增「库管理」入口**（多库场景） |
- [ ] **二级页仍走路由**：`ItemRoute` / `TrashRoute` / `AutofillSettingsRoute`
      照旧 push（Tab 容器不拦截）；`ItemRoute` 仍需 `vaultId`（条目属于具体库）
- [ ] **锁定处理**：`lockEpoch` 触发时从 `MainShellRoute` 清栈回 `VaultListRoute`（现状保持）

### 阶段 3：观感对齐（Bastion 视觉细节）
- [ ] 主界面卡片样式（参考 `PasswordTabPane.kt` / `NoteListCardComponents.kt`）
- [ ] FAB 行为（参考 `MainScreenFab.kt` 的 1044 行——按需取用，不整体搬）
- [ ] 设置页分组结构（参考 `SettingsScreen.kt` + `SettingsComponents.kt`）

---

## 4. ⛔ 明确不搬

| Bastion 文件 | 不搬理由 |
|---|---|
| `LocalKeePass*.kt`（4 个） | Vaultix 无 KeePass 本地库抽象层的 UI 对应物 |
| `WebDavBackupScreen.kt` / `OneDriveBackupScreen.kt` | 单后端架构，Bitwarden API 已覆盖同步 |
| `DedupEngineScreen.kt` | 多后端合并产生，Vaultix 单后端不存在 |
| `SendScreen.kt` / `SendPane.kt` | Vaultix 无 Send（安全分享）能力，暂不引入 |
| `SimpleMainScreen.kt`（3274 行） | **不是一个可搬单元**——它是 Bastion 所有 Tab 内容的巨型聚合，
  应按 Tab 拆解后**按需参考**，不整体移植 |
| `MainScreenFab.kt`（1044 行） | 同上，按需取 FAB 逻辑片段 |

---

## 5. 工作量与风险

**预估**：阶段 1 ≈ 0.5 天；阶段 2 ≈ 1.5–2.5 天（A4 的 `vaultId` 降级比 A1 略重）；阶段 3 ≈ 按需。

**主要风险**：
1. **`vaultId` 传递链重构（最大风险）**——`ItemsViewModel` / `TotpCodesViewModel` /
   `PasskeysViewModel` / `TrashViewModel` 等目前通过 `SavedStateHandle` 取 `vaultId`；
   A4 要求改为从**筛选状态**注入。这是阶段 2 最易出错处，需逐 ViewModel 核对
2. **各 Screen 的 `onBack` 语义**——现有 Screen 都假设自己是栈顶，
   改为 Tab 内容后要清理返回逻辑与顶栏（建议逐个过一遍）
3. **`ItemRoute` 仍带 `vaultId`**——条目详情属于具体库，需保留参数；
   注意「筛选状态切换库」与「已 push 的 ItemRoute」的一致性（避免显示错库条目）
4. **多库入口**——`VaultListRoute` 保留为入口/解锁页；设置页新增「库管理」，
   确保多库用户能找到切换入口
5. **自动锁定**——`lockEpoch` 清栈逻辑要覆盖新的 `MainShellRoute`

**门禁**：每阶段完成后跑 `compileFullDebugKotlin` + `detekt` +
`testFullDebugUnitTest`，全绿再进下一步。

---

## 6. 待用户确认

1. **导航范式**：**A4**（vaultId 参数 → 筛选状态降级）—— 用户已认可 Bastion 范式，
   此项基本定调，仅需确认「解锁后直接进主界面」是否符合预期
2. **Tab 集合**：先做 5 项（密码/验证器/通行密钥/生成器/设置），
   还是要包含 Notes / CardWallet？
3. **是否要「自定义 Tab 排序与可见性」**（Bastion 的完整能力），
   还是固定顺序即可？（建议先固定，骨架稳后再加）
