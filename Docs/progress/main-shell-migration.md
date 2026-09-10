# 主界面骨架迁移方案（Bastion 双/多 Tab → Vaultix）

> 决策依据 2026-09-10：需求定调「**Bitwarden 的功能 + Bastion 的页面**」，
> 用户拍板选项 **A**（照搬 Bastion 的底部导航结构）。
> 本文档是**执行前的勘察报告 + 分步计划**，不是最终实现。
> 状态：勘察完成，待确认后开工。

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

| | Vaultix 现状 | Bastion |
|---|---|---|
| **起点** | `VaultListRoute`（库列表）——**支持多库** | `Screen.Main`（直接进主界面）——**单库语义** |
| **导航层次** | 列表 → 解锁 → 条目（**线性栈**） | 主界面（Tab 容器）→ 各内容页 |
| **库与 UI 的关系** | 路由参数携带 `vaultId`（`ItemsRoute(vaultId)`） | Tab 内切换内容，无 `vaultId` 传递 |
| **锁定时** | 回 `VaultListRoute` 清栈 | 回 `Screen.Login` |

**冲突点**：Bastion 的 Tab 容器**假设只有一个当前库**。而 Vaultix 的
`ItemsRoute` / `TotpCodesRoute` / `PasskeysRoute` / `TrashRoute` **全部带 `vaultId`**，
且支持「Bitwarden 云端库 + KDBX 本地库」两种库共存。

### 可选解决路径

| 方案 | 做法 | 代价 | 保真度 |
|---|---|---|---|
| **A1 单库 Tab 化** | Tab 容器只承载**当前已解锁库**；多库切换仍回库列表（或顶部库切换器） | 中；需引入「当前库」状态 | 高（贴近 Bastion 观感） |
| **A2 Tab 内嵌库选择** | Tab 容器内加库切换器（顶部下拉），各 Tab 显示当前库内容 | 中大；需重排各 Screen 签名 | 中高 |
| **A3 库列表降为 Tab 之一** | 把「库列表」变成一个 Tab（对应 Bastion `VaultV2`），其余 Tab 跟随选中库 | 大；改动面最广 | 最高（完全对齐 Bastion 的 9 项制） |

**我的建议：A1**。理由：
- Bastion 的 `VaultV2` Tab（库管理）**默认关闭**，说明它自己也不把「库列表」
  当主入口 —— A3 对齐的是一个默认隐藏的 Tab，性价比低
- Vaultix 是 Bitwarden canonical 单后端，绝大多数用户**只有一个云端库**，
  A1 的「当前库」状态几乎恒等于那个库，复杂度可控
- 多库保留在库列表页，不破坏既有 M1 闭环

---

## 3. 分步计划（若选 A1）

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
- [ ] 新增 `MainShellRoute`（无 `vaultId` 参数 → 内部持「当前库」状态）
- [ ] `VaultixApp` 导航图调整：
      ```
      VaultListRoute ──选中已解锁库──▶ MainShellRoute（携带初始 vaultId）
      MainShellRoute 内：Tab 切换（不新增路由）
      ```
- [ ] **各 Tab 复用现有 Screen**，但改为「无 back 按钮 + 接收当前库」形态：
      | Tab | 复用 | 改造点 |
      |---|---|---|
      | Passwords | `ItemsScreen` | 去掉 `onBack`；顶栏去掉回收站入口（移入设置或 Tab 内菜单） |
      | Authenticator | `TotpCodesScreen` | 去掉 `onBack` |
      | Passkey | `PasskeysScreen` | 去掉 `onBack` |
      | Generator | **新建**（搬 Bastion `GeneratorScreen`） | 全新建 |
      | Settings | `SettingsScreen` | 去掉 `onBack` |
- [ ] **二级页仍走路由**：`ItemRoute` / `TrashRoute` / `AutofillSettingsRoute`
      照旧 push（Tab 容器不拦截）
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

**预估**：阶段 1 ≈ 0.5 天；阶段 2 ≈ 1–2 天；阶段 3 ≈ 按需（视觉打磨无上限）。

**主要风险**：
1. **各 Screen 的 `onBack` 语义**——现有 Screen 都假设自己是栈顶，
   改为 Tab 内容后要清理返回逻辑与顶栏，容易漏（建议逐个过一遍）
2. **`vaultId` 传递链**——`ItemsViewModel` / `TotpCodesViewModel` 等
   通过 `SavedStateHandle` 取 `vaultId`；Tab 容器若不自带路由参数，
   需改为显式注入（这是阶段 2 最易出错处）
3. **多库场景**——A1 方案下「切换库」入口要保留在库列表页，别被 Tab 化吃掉
4. **自动锁定**——`lockEpoch` 清栈逻辑要覆盖新的 `MainShellRoute`

**门禁**：每阶段完成后跑 `compileFullDebugKotlin` + `detekt` +
`testFullDebugUnitTest`，全绿再进下一步。

---

## 6. 待用户确认

1. **导航范式**：选 A1 / A2 / A3？（建议 A1）
2. **Tab 集合**：先做 5 项（密码/验证器/通行密钥/生成器/设置），
   还是要包含 Notes / CardWallet？
3. **是否要「自定义 Tab 排序与可见性」**（Bastion 的完整能力），
   还是固定顺序即可？
