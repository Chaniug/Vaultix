# SESSION 2026-11-28（续）· 附件区改为默认折叠

> 用户反馈原话：「也没有必要这么明显吧，搞成折叠展示的如何，大部分人是不看附件的」。
> 这是对同日早些时候「务实档 UI 改版」的**观感推翻**：入口不该常驻抢注意力。
> 提交 `92eb3579`，只推 `main`，未发版、未合 `rele`。前置部分见
> [`SESSION-2026-11-28.md`](./SESSION-2026-11-28.md)。

## 1. 为什么折叠（判据）

早前版本把「+ 添加附件」做成标题行常驻文字按钮 + 空态可点区域，入口确实明显了，
但**大多数条目没有附件、或用户不关心附件**——常驻按钮等于让 95% 的场景为 5% 的功能
付视觉成本。折叠后：

- 折叠态：只画一行轻量标题 `📎 附件 · N 个` + 展开箭头（整行 48dp 触摸目标）。
  `N` 只在该条目**真有附件**时显示——让它知道「有几份」，但不展开内容、不打扰。
- 展开态：才画 DetailCard（列表 / 空态添加区 / 列表末尾「+ 添加附件」文字按钮）。

## 2. 实现要点

- `var expanded by rememberSaveable { mutableStateOf(false) }`：转屏 / 进程重建不缩回。
  与项目既有折叠惯例一致（`PasswordHistorySection` 1728 行、`UriSection` 1074 行同款）。
- 折叠行**复用 SectionTitle 的字阶与配色**（`titleSmall` + `onSurfaceVariant`，
  图标 `primary`），但自带 clickable 与 `ExpandMore`/`ExpandLess` 箭头；
  箭头 `contentDescription` 用新增的 `attachment_expand` / `attachment_collapse`。
- 空态的可点区域与 100MB 标注**移进展开态**——折叠时完全不渲染。
- 有附件时把「+ 添加附件」按钮放到**列表末尾**而不是标题行，避免顶部视觉竞争。
- 新增 3 条文案：`attachment_count`（`%1$d 个`）、`attachment_expand`、`attachment_collapse`。
- 100MB 前置 SIZE 预检逻辑一字未动（见前置篇 §2.1）。

## 3. 验证
- 本地 29 项 `check_*.py` 门禁全绿；`check_orphan_state` 扫 225 个 kt 文件 0 个孤儿状态
  （`expanded` 被读被写，不算孤儿）。UI 硬编码文案门禁绿。
- Compose 真编译交 CI（沙箱无 Android SDK）。
- **首轮 CI `46122777099` detekt 红 2 条**（提交 `92eb3579`）：
  `AttachmentsSection` too long（158 > 150）+ CyclomaticComplexity（15 > 14）。
  折叠行 / 空态 / 列表 / 删除确认全挤一个函数导致。
  **修复（提交 `7bc17963`）**：抽出 `AttachmentHeaderRow`（折叠行，40 行）
  与 `AttachmentExpandedCard`（展开态，~80 行）两个 Composable；
  主函数只剩状态 + 选择器回调 + 两个调用（~74 行代码）。本地门禁复核绿。
  ⇒ **教训复用**：给已有 Composable 加 `if (expanded)` 整块分支时，先估行数与圈复杂度，
  Compose 分支很容易把单函数推过 150 行 / 14 复杂度，拆函数比加 `@Suppress` 更干净。

## 4. CI / preview / 待办
- **Android CI run `46123518653` success**（lint / detekt / 31 门禁 / Debug APK / 单测 /
  sandbox 单测全绿；首轮 `46122777099` detekt 红已由 `7bc17963` 修复）；
  docs CI `46123518695` success。
- **Preview**：`v0.13.11-dev-7bc1796` / `Vaultix-7bc1796.apk`（35.95 MB），
  https://github.com/Chaniug/Vaultix/releases/download/preview/Vaultix-7bc1796.apk
- **未真机验收、未发正式版、未合 `rele`**。真机重点：① 折叠行点一次展开、再点收起，
  转屏后状态保持；② 有附件时折叠行显示「N 个」；③ 展开后空态点整块可拉起选择器，
  有附件时列表末尾「+ 添加附件」可拉起选择器；④ 100MB 超限提示仍正常。
