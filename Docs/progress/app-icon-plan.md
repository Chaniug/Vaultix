# App 启动图标优化 · 专项施工单（B2-A）

> 2026-11-09 · 范围：**仅 App 启动图标**（用户在总单 §6 批注选定）
> 不含：站点品牌包 / 条目图标渲染 / 底部功能栏 / 页面精装修。
> 状态：**已施工完成**（方案 ⑤ 折纸 V + 现行低饱和蓝，提交 `e6391e92`）。
> 本单不是设计稿；设计推导见下，几何真源已入库到 `tools/icon/generate_launcher_icon.py`。
> 总单：[`ui-polish-plan.md`](./ui-polish-plan.md)
> **设计提案**：[`app-icon-design-proposal.md`](./app-icon-design-proposal.md)
> **交互预览**：[`app-icon-design-preview.html`](./app-icon-design-preview.html)
> （依据 2026-10-19 更新的 Android 规范 + Apple iOS 26/27 图标原则 + 2026 风格走向；
>  四方案可切遮罩 / 尺寸 / 主题图标。⚠️ 提案阶段**未修改任何 `res/` 文件**）

---

## 0. 一句话结论：原计划那句「重绘前景几何」要改

`ui-polish-plan.md` §4 B2-A 原文是「重绘前景几何 / 渐变 / 单色层」。
**这个默认动作指向了本项目最不能动的部分**：

1. 图标几何已经迭代 **V1 → V5 五轮**，且用户亲自拍板过两次
   （09-13 定稿「徽章盾 + V 带，蓝金」；09-16「短一点、稍微胖一点」⇒ V5）。
2. 每一轮都有实测数据钉着（见 §2），不是"随手画的"。
3. `#89` 记着这一带最贵的坑：内层必须「等比缩放 + 质心对齐」，
   手工拼曲线会让内盾漂移；**缝宽四边相等与左右对称在几何上不可兼得**。

⇒ 「重绘几何」= 推翻定案 + 重走五轮校准 + 重做 monochrome 的形状语义。
**除非用户明确要换符号，否则不动一个顶点。**

---

## 1. 现状地图（文件级，行号可核对）

| 层 / 文件 | 内容 |
|---|---|
| `res/mipmap-anydpi-v26/ic_launcher.xml` | 自适应声明：`background` / `foreground` / `monochrome` 三层 |
| `drawable/ic_launcher_background.xml` | 满幅 108×108 三档对角渐变，`angle=315`（左上亮 → 右下深） |
| `drawable/ic_launcher_foreground.xml` | **白描边盾 → 缝 → 白内场 → 金色 V 带**；V 带用 `aapt:attr` 内联线性渐变 |
| `drawable/ic_launcher_monochrome.xml` | Android 13+ 主题图标；与彩色前景**同一几何**，但 V 改成 `evenOdd` 挖洞 |
| `values/colors.xml` | 6 个 `ic_launcher_*` 色值（见 §2） |
| `AndroidManifest.xml:75` | `android:icon="@mipmap/ic_launcher"` ✅ 正确；无 `roundIcon`（minSdk 26，符合） |
| 几何工具 | `~/.workbuddy/skills/android-adaptive-icon-geometry/scripts/icon_geometry.py`（`solve_inner` / `verify`） |

前景几何的硬数据（V5，写在文件头注释里）：

| 项 | 值 |
|---|---|
| 画布 / 系统保证可见区 | 108×108 / **中心 66dp 圆（半径 33）** |
| 外盾最远点（含描边一半） | 26.05dp，**距安全圈余量 6.95dp** |
| 外盾宽高比 | 36.96 / 43.52 = **0.8494**（V4 是 0.7547，偏细长 ⇒ 用户要求改） |
| 内外质心偏差 | 内场 0.000541 · V 带 0.000180 |
| 左右留白差 | **0.000000**（构造性对称） |
| 缝宽 | 左 3.93 / 右 3.93 / 顶 4.03 / 底 5.22（四边不等是**几何必然**，不是缺陷） |

---

## 2. ★ 真正有硬证据的缺口：**配色滞后于品牌色板**

这是本轮最扎实、且零几何风险的优化点。

| 时间 | 事件 | 色值 |
|---|---|---|
| 2026-09-13 | 图标定稿「蓝金」 | 背景中档 `#3B5BDB`（indigo-7） |
| **2026-10-08** | 用户反馈「蓝色太亮」⇒ `VaultixBrandColor` 把 `primary` 降到低饱和蓝 | `#3B5BDB` → **`#3B5BC4`**（blue-8） |
| **现在** | `colors.xml` 图标色**没跟着改** | `ic_launcher_bg_mid` 仍是 **`#3B5BDB`**；`ic_launcher_background` 同；`bg_top` 更亮的 `#5B7BF0` |

`VaultixBrandColor.kt` 的 KDoc 里写着实测：
`#3B5BDB` 饱和度 **69.0%**、被用户判为「偏荧光」；改成 `#3B5BC4` 后降到 **53.7%**，
且对白底对比度反而从 5.67 **升到 6.04**。

⇒ **App 内已经降饱和了，桌面图标还停在被否决的那版高饱和蓝。**
在 launcher 上一眼就比 App 内"艳"，这是品牌一致性的实证缺口，不是审美偏好。

⚠️ 但改色值前要确认一件事：8.8 §6② 允许「品牌色出现在应用图标」，
所以这里**可以**用品牌色；问题是**用哪一版品牌色**。建议对齐现行 `primary`。
⚠️ 不要顺手改 `VaultixBrandColor` 的色值（有 WCAG 单测钉着），只改图标这 6 个。

---

## 3. 接力断层：生成脚本已经丢了（动几何前必须先补）

`ic_launcher_foreground.xml` 文件头写着：

> 「想再调比例，改 KX/KY 重跑 `build/sign/v5_stretch.py` + `v5_write.py`，不要手改顶点。」

实测：**这两个脚本不存在，也不在 git 里**（`build/` 是构建产物目录，被忽略）。
同样丢失的还有注释里提到的 `solve_v3c.py`。
⇒ 那条指令现在是**死链**：想微调几何，只能手改顶点，而手改顶点正是 #89 明令禁止的。

**还活着的是 skill**：`android-adaptive-icon-geometry/scripts/icon_geometry.py`
提供 `solve_inner()` 与 `verify()`（安全圈 / 质心 / 左右留白 / 越界采样四条校验）。

⇒ 若最终选了「动几何」的方向，**第一步必须是重建生成脚本并放进版本控制**
（建议 `tools/icon/`），把 KX/KY、k、圆角半径等参数收进一份配置；
否则这一代改完，下一代又断一次。

---

## 4. 可动 / 禁动

| 可动（低风险） | 禁动 / 需重验 |
|---|---|
| `colors.xml` 6 个色值 | ❌ 任何 `pathData` 顶点（除非重建脚本 + 重跑四条校验） |
| 渐变档数 / 角度 / 端点色 | ❌ 描边宽度 2.82（改了要重算安全圈余量） |
| 内场填充色（白 → 别的浅色） | ❌ `viewportWidth/Height = 108` |
| monochrome 的挖洞形状（**必须与彩色前景同几何**） | ❌ manifest 的 `@mipmap/ic_launcher`（历史踩过） |
| 背景层满幅渐变（**不许内缩**） | ❌ `roundIcon`（minSdk 26 无意义） |

⚠️ 一条结构性约束：**彩色前景与 monochrome 的内场 path 必须逐字符一致**，
否则普通图标与 Android 13 主题图标会错位（`ic_launcher_monochrome.xml` 注释明写）。
改形状 ⇒ 两个文件同步改。

⚠️ 上表「V 必须是洞」是**现状的形状约束**（单色层只有一种着色，填色 V 会与盾同色消失）。
2026 提案建议改成「独立实心形状 + 明度差」（见 `app-icon-design-proposal.md` §4.3）——
这与 Apple WWDC 2026 的建议一致，且能提升 Android 16 QPR 2 自动主题化下的可读性。
**换方案时这两条一起改，别只改一半。**

---

## 5. 设计方向（**2026-11-09 下午更新：已出正式提案**）

用户明确要求「按最新主流 APP 图标设计规范重新设计，配色 / 风格 / 图案都可大胆提」。
⇒ 完整方案、规范依据、四方案评分与推荐已展开到
[`app-icon-design-proposal.md`](./app-icon-design-proposal.md)，
可交互效果见 [`app-icon-design-preview.html`](./app-icon-design-preview.html)。

**当前推荐：方案 ②「V 库门」**（白圆角方门 + 蓝粗 V，两色一焦点，换掉与 Bitwarden 撞车的盾）。
理由：现状问题不在几何精度，在**信息密度**；差异化做在形状上，不做在被单测钉死的色相上。

<details>
<summary>原「保守三方向」（若最终决定只做小改，仍适用）</summary>

| 方向 | 做什么 | 改动量 | 风险 |
|---|---|---|---|
| ① 配色对齐 | 只把 6 个 `ic_launcher_*` 对齐现行低饱和蓝 | `colors.xml` 一个文件 | 极低，几何零改动 |
| ② 配色 + 层次 | ① 之上再调渐变档数 / 内场色 | 2 XML + colors | 低～中 |
| ③ 重绘符号 | 推翻盾 + V | 3 XML + 重建脚本 + 重跑校准 | 高 |

</details>

⚠️ 一个**独立于方向**的验收项：主题图标（Android 13 单色层）在真机上是否可辨认？
现状 V 是挖洞、笔画细，24dp 下可能糊。Android 16 QPR 2 起系统会自动主题化没做好的图标，
所以这项不再是"顺手看看"，而是必查。

---

## 6. 验收（**本机不能编译，必须先解决"怎么看到"**）

| # | 检查 | 方式 |
|---|---|---|
| 1 | 六种 OEM 遮罩下图形不被切 | 用 SVG/HTML 静态预览模拟：圆 / 圆角方 / squircle / 水滴 / 直角方；叠加 66dp 安全圈。不依赖 Android SDK |
| 2 | 彩色版与主题图标形状语义一致 | 并排预览两张，看「盾 + V」是否都读得出来 |
| 3 | 小尺寸可读性 | 48dp（应用列表）/ 24dp（通知、最近任务）各渲染一次，看 V 臂 2.83dp 是否糊 |
| 4 | 主题图标 | Android 13+ 开「主题图标」，换浅/深壁纸各一次 |
| 5 | 真机桌面 | 至少两家 launcher（圆遮罩 / 方遮罩）截图 |
| 6 | manifest | 仍指 `@mipmap/ic_launcher` —— 历史上这里错过一次（09-13 #89 同轮） |

**门禁**：改的是 `res/` XML，不碰 Kotlin，但 CI 仍要跑
`assembleFullDebug`（资源引用 / aapt 渐变解析）+ `detekt` + `test`。
⚠️ 三关**分开单跑**；CI 里 detekt 在编译之前，detekt 红 ⇒ 后面一个都不跑。

---

## 7. 参考索引

| 要什么 | 去哪读 |
|---|---|
| 五轮几何的全部推导与实测 | `drawable/ic_launcher_foreground.xml` **文件头注释** |
| 内盾漂移事故（#89） | `.ai/issues/06-界面与交互-part2.md` §89 |
| 图标定稿与 manifest 事故 | `.ai/archive/sessions-2026-09/SESSION-2026-09-13.md` 第五十四轮 |
| 品牌色降饱和的实测数据 | `ui/theme/VaultixBrandColor.kt` KDoc §二 |
| 「品牌色只许出现在两处」 | `.ai/conventions/8.8-M3Expressive-采纳范围与顺序.md` §6② |
| 几何工具 | `~/.workbuddy/skills/android-adaptive-icon-geometry/SKILL.md` |
| 总单 / 其余三块 | `Docs/progress/ui-polish-plan.md` |
