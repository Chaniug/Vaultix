# 底栏 P1 施工单 · 方案 ③ + 方案 A 合并

> 2026-11-09 · 状态：**已施工并推 `main`（`3fd27fe6`），CI 出包中**。
> 用户 19:54：「先按目前的方案改，我实机看一下效果」⇒ 开工令。
> 用户 19:47：「按照你的推荐来」⇒ 采纳架构方案 A（不扩 Tab、卡包不动）。
> 前置文档：[`dock-polish-plan.md`](./dock-polish-plan.md)（思路）·
> [`dock-slot-proposal.md`](./dock-slot-proposal.md)（架构评估）·
> [`dock-polish-preview.html`](./dock-polish-preview.html)（可点击对照）

---

## 0. 一句话

底栏 P1 与「+」动作菜单**都改 `AddButton`**，必须合并成一批做，否则两批会互相覆盖。
本单把已拍板的四件事（方案 ③ / 加号反馈 / 图标 one-shot / 方案 A）收成一份可执行的施工计划。

---

## 1. 合并后的范围

| # | 内容 | 来源 | 状态 |
|---|---|---|---|
| ① | Tab 点击：弹簧松手 + 选中 pill 形变（16dp → 胶囊） | 用户选方案 ③ | 已拍板 |
| ② | 加号反馈：**压缩回弹**，符号恒为「+」，绝不旋转 | 用户指出旋转不对 | 已拍板 |
| ③ | 图标 one-shot：**弹跳回弹**，播完停住、不循环 | 用户 19:38 提出 | 已拍板（推荐档） |
| ④ | 中央「+」升级为**动作菜单** | 架构方案 A | 用户 19:47 拍板 |
| ⑤ | 便签走已有筛选器、生成器加独立入口 | 架构方案 A | 用户 19:47 拍板 |
| ⑥ | T0 叠 M3 10% state layer | 可选项 | 未定，施工时做成可开关 |
| ⑦ | 卡包位置 / 身份 | **完全不动** | 定案 |

---

## 2. 形态决策（我替你定的，理由一并写清）

### 2.1 「+」动作菜单

**选 `ModalBottomSheet`，不选 M3E `FloatingActionButtonMenu`。**
理由：8.8 §6① 明确「alpha API 只用于叶子组件，交互框架等 beta/stable」；
`material3 1.5.0-alpha16` 里的 FAB Menu 是 alpha，拿它承载导航级动作风险不划算。
`ModalBottomSheet` 是稳定组件，且 8.4 禁止的是「Dialog / BottomSheet **里开 Popup 选择器**」——
面板里放 `ListItem` 列表不属于 Popup，不违规。

面板结构（遵循 8.4「同一屏只有一个 Filled 按钮」）：

```
┌ 新建 ───────────────────────────┐
│ [ 新建登录 ]   ← 唯一 Filled 主按钮 │
│  安全笔记                        │
│  银行卡                          │
│  身份                            │
│  SSH 密钥                        │
│ ─────────────────────────────── │
│  生成密码（工具，用完即走）        │
└──────────────────────────────────┘
```

**三个复用点（这是方案 A 便宜的关键）**：

1. `VaultixAddTarget` 现有枚举决定**面板默认高亮哪一项** —— 不用新增导航模型。
   （当前 Settings Tab 回退 Login 的语义可原样保留。）
2. `ItemFormDialog` 已有 `typeEditable` + `selectableTypes` 参数
   （`ItemFormDialog.kt:129-130`）—— 选中类型直接透传，**不新建表单**。
   ⚠️ KDBX 库只放行 Login / SecureNote（`ItemTypePolicy.kt`），面板要按库类型过滤可选项，
   不能把 5 种一律摆出来。
3. `PasswordGeneratorDialog(onUse, onDismiss)`（`PasswordGeneratorDialog.kt:55`）接口已够用：
   表单内原样回填；独立入口时 `onUse` 改为**复制到剪贴板**（无表单上下文）。

### 2.2 便签 / 生成器入口

- **便签**：`QuickFilterPanel` 里已有 `ItemsQuickFilter.Note`（`ItemsQuickFilter.kt:46`），
  不用新建页面。做两件小事：把它在面板里提到显眼位置；在 ⋮「前往」组加一条直达项。
- **生成器**：保持 `ItemFormDialog.kt:1115` 的表单内入口不动，另从动作菜单给独立入口。
- **卡包**：位置、图标、`addTarget` 全不动。

### 2.3 ★ 必须知情的代价

**「新建登录」从 1 次点击变成 2 次**（点 + → 点主按钮）。
这是方案 A 唯一的体验回退，也是当年「固定 4 项 + 单一 +」设计换来的便利。

补偿措施：
- 面板第一项就是 Filled 主按钮，拇指落点几乎在同一处；
- 按当前 Tab 的 `addTarget` 默认高亮，减少判断；
- **不引入长按快捷手势**——8.4 明确「隐藏手势必须有可见反馈」，
  长按新建会变成用户发现不了的隐藏入口。

若真机上觉得这一步不可接受，回退方案是 B（第 3 槽位恒定为「工具」容器），
但那会让卡包从一步变两步，代价更大。**建议先按 2 步做、真机验过再判断。**

---

## 3. 批次（每批可单独开关、单独验收）

| 批 | 文件 | 内容 | 风险 |
|---|---|---|---|
| **P1-a** | `VaultixBottomDock.kt` | ① ② ③ + ⑥ 可开关：弹簧、选中形变、加号压缩回弹、图标弹跳、state layer | 中（动效手感，需逐帧） |
| **P1-b** | `MainShellScreen.kt` + `VaultixNavModel.kt` + `VaultixBottomDock.kt` | ④ 动作菜单接线：`onAdd` 改为「打开面板」→ 面板选类型 → 走现有 `addRequest` 分发 | 中（改导航分发） |
| **P1-c** | `ItemsScreen.kt` + `QuickFilterPanel` + `ItemFormDialog` 调用点 | ⑤ 便签筛选显眼化、生成器独立入口、按库类型过滤可选类型 | 低 |

⚠️ P1-a 与 P1-b 都碰 `AddButton`，**必须同批或严格串行**；建议 P1-a 先合、P1-b 基于它改。
⚠️ `VaultixNavModel.kt` 文件头「Send/Notes/Generator 为 Vaultix 暂不具备的能力」
**已过时**，P1-b 顺手更正为现状（Notes / Generator 已具备、走动作菜单入口）。

---

## 4. 验收

| 项 | 判据 |
|---|---|
| 动效 | 真机**录屏逐帧**；播完停住、不循环；只动被点那一个；不重现「旁边跟着闪」 |
| 状态契约 | `currentTab` / `SaveableStateProvider(tab.name)` / `contentKey` **一行不改**（方案 A 的核心收益，改了说明走偏了） |
| 无障碍 | `Role.Tab` + `selected` + `contentDescription` 不变；面板项有独立标签 |
| 库兼容 | KDBX 库下动作菜单只出现 Login / SecureNote；Bitwarden 库下 5 种齐全 |
| 字号 | 默认 + 2.0x 字号；胶囊 `heightIn(min=48dp)` 不被撑破 |
| 玻璃 | 实色 / 玻璃两分支共用 `DockRow`；按 8.4 别只改一边 |
| 门禁 | `assembleFullDebug` / `detekt` / `test`，**分开单跑**；本机不编译，push `main` 交 CI |
| 真机 | 两家 launcher、浅/深/纯黑、Android 13+ 主题图标 |

⚠️ 施工前按 8.8 的方式解 `material3 1.5.0-alpha16` sources jar 核对
`ModalBottomSheet` / spring / shape morph 的实际签名，不照抄网页示例。

---

## 5. 施工记录（2026-11-09，提交 `3fd27fe6`）

三批已全部落地，改动 10 个文件（+1208 / −33）。实际实现与本文的差异，逐条如实记录：

| 项 | 计划 | 实际 |
|---|---|---|
| 弹簧 / 形变 / 图标 one-shot / 加号压缩回弹 | P1-a | ✅ 全在 `VaultixBottomDock.kt`；`USE_M3_STATE_LAYER` 默认关，改一个布尔即可对比 M3 手感 |
| 选中 pill 形变 | 16dp → 胶囊 | ✅ 用 `RoundedCornerShape(percent)` 33% → 50% 连续 spring；颜色仍 `when` 瞬切 |
| 动作面板 | P1-b | ✅ 新增 `AddActionSheet.kt`；`ModalBottomSheet` + 唯一 Filled 主按钮 + 5 个类型 ListItem + 生成器入口 |
| 默认动作 | 保住原分发 | ✅ `runDefaultAdd` 原样保留 `when (addTarget)` 三个计数器；面板主按钮按当前 Tab 变文案 |
| 库类型过滤 | 面板按库过滤 | ⚠️ **改在表单侧**：`ItemFormDialog` 已有 `fallbackFormType`，会把不在 `selectableTypes` 里的初始类型兜到该库支持的第一种。面板再过滤就成了第二个真源、还要和库摘要加载时序同步 —— 已写进 `AddActionSheet.kt` 注释 |
| 生成器独立入口 | `onUse` 复制剪贴板 | ✅ `StandalonePasswordGeneratorDialog` 用 Android 原生 ClipboardManager + Toast，零 Compose alpha API |
| 便签 ⋮ 直达项 | P1-c | ⏸ **本批未做**。动作面板里的「安全笔记」新建项已是显眼入口，查看已有便签仍用 `QuickFilterPanel` 里本来就有的 `ItemsQuickFilter.Note`；⋮ 菜单要穿 4 层签名，且 `ItemsScreen` 贴 detekt `LongMethod ≤150`，留到真机反馈后再决定值不值 |
| `VaultixNavModel` 文件头 | 更正过时说明 | ✅ 已改 |
| 导航状态契约 | 一行不改 | ✅ `currentTab` / `SaveableStateProvider` / `contentKey` / Tab 枚举 / `addTarget` 全部原样 |

**本地门禁全绿**：`check_compile_smells`（519 文件）/ 编码 / md 链接 / workflow shell / secret /
`git diff --check` / 大括号平衡 / 新增 7 条 `R.string` 引用核对。
本机不编译，`assembleFullDebug` / `detekt` / `test` 由 CI run `42615108355` 验证。

⏳ **待用户实机反馈**：动效强度（弹簧是否过弹、形变是否太慢）、图标弹跳是否讨喜、
「新建登录 1 步 → 2 步」的手感是否可接受。反馈后逐项调，不必重做架构。

---

## 6. 相关决策记录

| 议题 | 结论 | 出处 |
|---|---|---|
| 底栏主方案 | ③ 弹簧 + 选中形变 | `dock-polish-plan.md` §6 |
| 加号 | 否决旋转，只表达「按下了」 | `dock-polish-plan.md` §3 F |
| 图标动效 | one-shot 弹跳回弹，不循环 | `dock-polish-plan.md` §3 H |
| 槽位可替换 | 否决互斥替换，采纳方案 A | `dock-slot-proposal.md` |
| 动效硬约束 | 本阶段放开，旧红线降为历史证据 | `dock-polish-plan.md` §5 |
