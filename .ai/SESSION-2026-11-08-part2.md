# SESSION-2026-11-08-part2 · 长表单滚动时底栏按钮跟着滑动错位

> 本篇只记 11-08 **第二批**（底栏位移隐藏）。同日第一批（移除顶部 ✕）见
> [`SESSION-2026-11-08.md`](./SESSION-2026-11-08.md)；主线索见 [`README.md`](./README.md) 顶部状态块。
> 约定正文：[`conventions/8.8-M3Expressive-采纳范围与顺序.md`](./conventions/8.8-M3Expressive-采纳范围与顺序.md)
> （本批已按实测结果修订其中「只借状态机」那条建议）。

## 0. 一句话结论

用户报「内容比较长时，滑动正文，下方取消/保存两个按钮跟着滑动错位」→ 根因是
`FullScreenDialogShell.ActionBar` 借 M3 `exitAlwaysScrollBehavior` 做滚动隐藏时
**只借了状态机、没借布局语义**（三条叠加）→ 按用户拍板**砍掉整套位移隐藏，底栏常驻不动**，
保留 9-21 定稿的背景 alpha 淡出。

## 1. 现象 / 需求

- 用户原话：「在 vaultix 中，在新建编辑条目中，进入编辑状态下，修改条目中的内容，
  内容比较长，滑动内容的时候，会发现下方的取消 保存两个按钮也跟着滑动错位了。」
- 关键词是**内容比较长**：短表单滚动量小、位移不明显；长表单才复现。
- 诉求是让操作条钉住，不是加动画、不是改按钮布局。

## 2. 根因 / 约束（为什么只能这样改）

三条叠加，缺一条都不会这么明显。对照 material3 `1.5.0-alpha16` 的 `AppBar.kt` →
`BottomAppBarLayout` 源码（下载 `material3-1.5.0-alpha16-sources.jar` 核实）：

| | M3 官方 | 本壳（改前） |
|---|---|---|
| 负 `heightOffset` 怎么用 | `layout { val height = (placeable.height + offset).coerceAtLeast(0f) }` ⇒ **收缩栏高度**、从底部裁剪，内容 `place(0, 0)` 不动 | `graphicsLayer { translationY = offset }` ⇒ **整条栏向上平移**，飘进正文 |
| 下界 `heightOffsetLimit` | 同一个 `layout` 里设 `-placeable.height.toFloat()` | **从未设置**；`rememberBottomAppBarState()` 默认 `-Float.MAX_VALUE`，`coerceIn` 等于没约束 ⇒ 滚上千 px 栏就被拖上千 px |
| 按钮 | 随整条栏高度一起被裁掉 | `backgroundAlpha` 只作用于 `.background(...)`，**按钮不受影响** ⇒ 底色透明后只剩两个按钮在飘 |

第三条决定了用户看到的是「**按钮**跟着滑动错位」，而不是「一条栏在动」。

历史脉络：`exitAlways` 是 9-20 引入的滚动隐藏；9-21 用户反馈「滑动不透明、上下栏
遮住内容」并明确「不要这些效果」后，那轮只把渐变改成 alpha 硬切
（`archive/sessions-2026-09/SESSION-2026-09-21.md` B 项 / commit `92f7ca59`），
**位移状态机留了下来** —— 两套滚动效果并存至今，正是本 bug 的土壤。

### 决策点（已问过用户）

| 选项 | 结论 |
|---|---|
| ① 砍掉位移隐藏、底栏常驻（用户选） | 保存是这张表单唯一出口，不该随滚动跑掉；回到 9-21「不要这些效果」；改动最小 |
| ② 保留隐藏但补下界 + 改方向 + 按钮随栏收起 | 滚到底时按钮处于收起态，用户得往回拉才能点保存；改动面更大 ⇒ 否 |

⚠️ **不能关掉 `barAlpha`**：11-07 的 `ActionBar` KDoc 明确警告，滚动后 alpha 0 是
为修 9-20「栏遮住内容」真机验证过的，关掉会把那个修复一起推翻。

## 3. 做法（改了什么，文件:行）

单文件 `app/.../ui/common/FullScreenDialogShell.kt`，36 增 52 删。

1. 删 `bottomBarScrollBehavior` 声明、`Column` 的 `.nestedScroll(...)`、
   `ActionBar` 的 `scrollOffset` 参数、`.graphicsLayer { translationY = ... }`。
   ⇒ `ActionBar` 参数 8 → 7（detekt `LongParameterList` 减负）。
2. 清理 4 个失效 import（`BottomAppBarDefaults` / `ExperimentalMaterial3Api` /
   `graphicsLayer` / `nestedScroll`）与 `@OptIn(ExperimentalMaterial3Api::class)`；
   顺手删掉一段**位置已漂移**的 `@OptIn` 说明注释（夹在两处 KDoc 之间，所述 API 已不存在）。
3. KDoc 新增「为什么不做滚动隐藏」：三条根因、决策，以及**将来若要恢复必须同时
   补齐的三件事**（设 `heightOffsetLimit` / 高度收缩而非平移 / 按钮随整条栏一起收起）；
   `ActionBar` KDoc 去掉「自动隐藏」，`@param backgroundAlpha` 改注明「仅背景、按钮不受影响」；
   删掉「滚到底时底栏会自动展开把留白填上」这句已不成立的旧推理。
4. 同步修订 `conventions/8.8` 中「M3 状态机可以只借」那条建议（本批实测推翻）。

## 4. 验证

- 本地 9 个静态门禁全绿（清单同 [`SESSION-2026-11-08.md`](./SESSION-2026-11-08.md) §4）；
  `check_experimental_optin` 确认删除 `@OptIn` 后本文件已无实验性 API。
- 孤儿串未超基线；无新增 >120 字符行；`setValue` 仍是委托导入（字面统计误报）。
- 代码里已无 `heightOffset` / `nestedScroll` / `translationY` 残留，仅 KDoc 留作根因说明。
- 不本地编译 / 不出包（约束 #6），交 GitHub Actions。

## 5. CI 结论与指针

- 提交 `f6f8fda1`（`fix(ui): 长表单滚动时底部取消/保存按钮跟着滑动错位`），
  分支 `main`，`rele` 未动。
- **`Android CI (Debug)` run [`42442855625`](https://github.com/Chaniug/Vaultix/actions/runs/42442855625)
  `conclusion=success`**（3m44s）。单个 job `Lint, test diagnostics, and debug build`
  全绿：`:app:detekt` `BUILD SUCCESSFUL in 25s`、Debug 编译 `BUILD SUCCESSFUL in 1m 56s`、
  单测硬门禁 `BUILD SUCCESSFUL in 41s`。⚠️ 本次日志同样未打印单测**总例数**，不写数字。
- preview 已更新：`tag=preview` → `Vaultix v0.11.2-dev-f6f8fda · Preview`，
  `app-full-debug.apk`（35.6 MB）上传成功；稳定版 `v0.11.2` 未动，`rele` 未推。
- 本批只改 `.kt` ⇒ 未触发文档门禁；本篇与 `8.8` 修订推送后由 `ci-docs.yml` 单独跑。
- 真机待验（交用户）：长表单一路滚到底，取消 / 保存**始终钉在底部原位**，
  不随内容移动、不叠在输入框上，滚到底仍可点保存；短表单观感与改前一致
  （仅底色随滚动淡出）。
