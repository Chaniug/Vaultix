# 下一步任务清单 · 归档 4/6

> **历史归档，勿通读。** 覆盖 2026-09-15 ~ 2026-09-12 的已完成记录。
> 入口与当前待办见 [`next-steps.md`](./next-steps.md)。
> 拆出于 2026-11-10：原文件 3592 行 / 280 KB，远超 §7.1 的 300 行上限，
> 且真正的待办被埋在 96% 位置。归档按时间倒序，**只在需要追溯时检索**。

---

> ## ✅ 【2026-09-15 第六十六轮】设置页 IA + M3E P0 / P1-1 —— **已完成，待本机验收**
>
> 用户睡前交待的两件事都做完了，**已推送**。详见 `.ai/SESSION-2026-09-15.md`。
>
> | 项 | 结果 |
> |---|---|
> | 设置页 **7 组 → 5 组** + 文案统一 | ✅ 与定稿逐行一致（`decisions/设置页信息架构-定稿.md` §6.1） |
> | M3E **P0-1 间距标尺** | ✅ 新建 `ui/theme/Spacing.kt`，**369 处**替换；反演法证明**纯等价**（版式零变化） |
> | M3E **P0-2 表达式搜索栏** | ✅ `SearchTopAppBar` 改 contained search；**inset 未动** |
> | M3E **P1-1 波浪进度指示器** | ✅ **9 处**（`ui/common/ExpressiveProgress.kt` 封装，可单点回退） |
> | M3E **P1-2 / P1-3 / P2** | ❌ 未排期（非"不可做"：CI 能编译，API 已查明） |
>
> ✅ **已推送 `main`，CI 全绿**（最新 run `34876446924`，3m0s，commit `036b9c5`）：
> detekt ✓ / 编码检查 ✓ / **Build Debug APK ✓** / 单测 ✓。
> **签名 debug APK 已发布到 preview Release**（`dev-036b9c5`，30.7MB；
> 证书与本机一致 ⇒ 覆盖安装不丢数据，可直接下载装机）。
> ⇒ 沙箱"无编译环境"的缺口已由 CI 覆盖 —— **alpha 波浪 API 也编译通过了**，
> 只剩真机目视。
> **验收清单：`decisions/设置页信息架构-定稿.md` §8.1 + 本文档下方「波浪进度条」一条。**
>
> **装机验收补充 —— 设置页点「未解锁的库」（本轮修的空白页 bug，#99）**：
> 1. 设置 → 密码库 → 点**已解锁**的库 ⇒ **正常切过去**（行为不变）；
> 2. 设置 → 密码库 → 点**未解锁**的库 ⇒ 应**直接进它的解锁页**，
>    且那一行左边是 **⇒ 箭头**（不是单选圆点）、副标题「未解锁 · 点这里输入主密码」；
> 3. 解锁后回条目页 / 验证码页 ⇒ **条目正常显示**（不再白屏）；
> 4. 兜底验证：若因任何原因落在「锁着的活跃库」上，两页应显示
>    「**这个库还没解锁**」+「立即解锁」按钮，**而不是**「还没有保存的密码」。
> 5. 「设为默认」仍可对未解锁库使用（改冷启动先开哪个）。
>
> ⚠️ **本轮 CI 结论的诚实说明**（详见 `.ai/ISSUES.md` **#100** / **#100.1**）：
> 1. `Run lint` 带 `if: github.event_name != 'push'` ⇒ 在 **push 链路恒为 `skipped`**
>    （已核实 run `34921886414` 的步骤列表明写着 skipped）。
>    ⇒ 所以「push 全绿」这条结论里**从来不包含 lint**（不是它坏了，是它没跑）。
> 2. **100.1 更正**：lint 本身**是好用的** —— 我手动触发 run `34921953234` 时
>    它**报 success**，后续 detekt / 编码 / Build Debug APK / 单测全部真的跑起来了。
>    上一次非 push 失败（`34920991273`）的真正原因是**非 push 链路的步骤守卫**
>    （`Collect debug APK metadata` 等 `if: push` 的步骤）⇒ **待下一轮修**。
> 3. 判断 CI 结果一律以 **`gh run view --json conclusion`** 为准（**不能看管道退出码**）。
> 4. 另：`ci-debug.yml` 固定 Gradle **8.11.1**，而仓库 wrapper 是 **9.5.1** ⇒ 待确认。

> **装机验收补充 —— 波浪进度条**（本轮**唯一肉眼可见**的变化；间距标尺是纯等价的）：
> 1. 冷启动 → 解锁页那个转圈（现在应是**波浪**圆）；
> 2. 同步时条目页顶部的同步横幅（线性波浪，**比原来厚**：高 4→10dp）；
> 3. 添加 Bitwarden / KDBX 库时「提交中」的进度条。
> ⚠️ 按钮里 18/20dp 的小转圈**应该还是老样式**（换波浪会被撑到 48dp）。
> 若不喜欢 ⇒ 改 `ui/common/ExpressiveProgress.kt` 两个函数体即可**全量回退**。
>
> **下一轮可接着做的**（按性价比）：
> 1. `detail_totp_hidden`「点右侧眼睛显示验证码」—— 同类指路语，在详情页，本轮没动；
> 2. M3E **P1-2**（ButtonGroup 快捷筛选行）/ **P1-3**（tonal elevation + Styles API）——
>    不再是"不可做"：CI 能编译、签名已查明（`conventions/8.8` §4）。
>    **P2 表达力 AppBar 仍建议等 beta/stable**（框架级替换，回归风险最高）；
> 3. ⭐ 应用内 PIN 解锁（仍是唯一的欠账，已连续多轮）。

> ## 📌 【2026-09-15 02:00 追加】两份新文档 + 5 项待办（**接力先读这里**）
>
> **两份新定稿文档**（都放 `.ai/`，已挂到 `README.md` / `MEMORY.md §8`）：
>
> | 文档 | 内容 | 状态 |
> |---|---|---|
> | `.ai/decisions/设置页信息架构-定稿.md` | 设置页 **7 组 → 5 组** + 全量文案改动表（含 string key）+ 开工步骤 | 结构定稿 ✅ / 未动代码 ❌ |
> | `.ai/conventions/8.8-M3Expressive-采纳范围与顺序.md` | M3 Expressive(2026) **借哪一半 / 不借哪一半 / 按什么顺序**，含本项目实测（依赖已带全套 API、540 处手写 dp） | 盘点完成 ✅ / 未换组件 ❌ |
>
> **待真机验收（已装包，等用户手指确认）**：
>
> 1. **图标 V4**（整组缩小：安全圈余量 1.40 → 5.18dp）—— 已装 00:14，看桌面图标观感；
> 2. **顶栏误收起修复**（切回「全部」时标题缩成 16sp + 该状态下更卡）—— 已装，
>    路径：点开筛选面板 → 全部→验证码→通行密钥→SSH→**回全部**，标题应保持 26sp；
> 3. **KDBX 生物快解闪退**（`.ai/issues/05-KDBX本地库.md` **#98**）—— 已修并装包，但
>    用户 2026-09-14 23:57 那一轮跑的是**启动自动弹的解锁**（日志可证只有一次指纹会话），
>    **启用登记那条路径尚未被走到** ⇒ 验收动作：设置 → 生物识别快速解锁 →
>    对 `valkjin.kdbx` **先关闭再启用** → 输主密码 → 应弹指纹而不是闪退；
> 4. **批量 PIN 键盘宽度** / **快速解锁对话框排版** —— 已改，顺路目视即可。
>
> **待开工**：设置页 IA（上面那份文档的 §6）、M3E 的 P0（间距标尺 + 表达式搜索栏）。
>
> ⚠️ 上面这些代码**均未提交**（工作区改动含图标、ItemsScreen、SiteIcon、ExpressiveTopBar、
> 设置/解锁/添加库等）。

> ## 🔧 【本轮追加·待真机验收】#88 快速解锁「指纹入口时有时无」——**已定位并修复**（2026-09-13 22:45）
>
> **根因不是密钥**（旧的"KEK/包裹密钥坏了"结论**已勘误**，见 `.ai/issues/04-锁与解锁.md` #88）：
> `UnlockViewModel` 的 **`vaultId` 竞态** —— "选库"与"订阅 `localUnlockAvailable`"并发收集同一个
> `observeVaults()`，订阅侧若先处理同一次发射，读到的 `var vaultId` 还是空串 ⇒ 跳过且**永不重订阅**
> ⇒ 状态**永久停在初值 `false`** ⇒ 指纹入口不渲染、生物识别也不自动弹。
> 重建 ViewModel（手动返回 / 清后台重开）才重掷一次 —— 与用户全部观感吻合。
>
> **实测钉死的三条前提**：开关 **true**（DataStore 字节 `12 02 08 01`）· 包裹 payload **在**
> （⇒ `clearBrokenLocalUnlock` 从未执行）· token 在（不用重登）。
>
> **已做**：新增 `UnlockViewModelTest`（3 例，**修前精确红 → 修后绿**）；全模块 detekt / compile /
> assemble / app 全量单测均绿；已用发布密钥重签并 `install -r` 到真机
> （证书一致、`firstInstallTime` 未变 ⇒ 数据未丢）。
>
> ⏳ **请验收**：**锁定密码库** → 解锁页应**稳定出现指纹入口**（可反复锁/解锁多次）。
> 若**仍**不出现 ⇒ 转向 `keyAvailable`（KEK 别名）那条线 —— 这是可证伪的判据。
>
> ✅ **已于 2026-09-13 22:53 真机验收通过**：用户确认解锁页**出现了指纹入口**、且**实际可以解锁**。
> 四条证据闭环见 [`quick-unlock-plan.md`](./quick-unlock-plan.md) §1.8（单测红→绿 / 自动弹窗真的发起 /
> `keystore2` 的 `HardwareAuthToken` 密码学操作成功 / 用户点按进入列表）。
> ✅ **已于 2026-09-13 23:38 提交并推送** `main`（`e724757` 修复 + `989ba34` 脚本 + `147014a` 文档）。
>
> ## 🎨 APP 图标 —— 已体检 + **已重新设计与实现**（2026-09-13 23:55，待视觉拍板）
>
> **现状**：图标 = **绿色盾牌**，但源码里**自己标注为占位**
> （`drawable/ic_launcher.xml` 注释：「盾形图标…（**占位，后续替换为原创品牌图标**）」）。
> 真机（应用信息页）已确认渲染的就是这个绿盾。
>
> **三个可修的问题**（都不是画质问题，矢量不会模糊）：
> 1. ⚠️ **自适应图标没被使用**：`mipmap-anydpi-v26/ic_launcher.xml` 已定义 adaptive-icon
>    （背景 shape + 前景矢量），但 **manifest 引用的是 `@drawable/ic_launcher`**，
>    且不存在 `drawable-anydpi-v26/` 变体 ⇒ 系统按 **legacy 图标**处理（被套框、留白，观感与其它 App 不一致）。
>    ⇒ 修法：manifest 改指 `@mipmap/ic_launcher`，或把 XML 挪到 `drawable-anydpi-v26/`。
> 2. ⚠️ **无 `<monochrome>` 层** ⇒ Android 13+ 的「主题图标（跟随壁纸取色）」用不了。
> 3. ⚠️ **两套图形互相矛盾**：占位图是**盾牌**（`#1B5E20` + `#A5D6A7`）；
>    未启用的自适应前景是**白锁 + 蓝弧**（`#3B5BDB`）⇒ **品牌标识尚未定**。
>    ⚠️ **这是设计决策，需用户拍板**（盾 or 锁 or 其它），定了再把 1+2 一起做完。
>
> **规范对照（用路径几何实测，不是印象）**：基准 = Google **自适应图标**规范（API 26+）
>
> | 项 | 规范要求 | 当前**实际使用**的图标 |
> |---|---|---|
> | 画布 | 108×108 dp | **108×108 ✅ 唯一符合的一项** |
> | 图层 | 背景 + 前景**两层** | ❌ 单层，且**无背景层** |
> | 可见区 | 外圈 18dp 可能被遮罩裁掉 ⇒ x/y **18..90**（72×72） | 主图形 **y 16..100（高 84）⇒ 超出** |
> | 安全区 | 关键内容 ≈中心 66×66 ⇒ x/y **21..87** | 主图形 **x 20..88 / y 16..100 ⇒ 超出** |
> | `monochrome` 层（API 33+ 主题图标） | 推荐 | ❌ 无 |
> | 密度位图 | 矢量即可（minSdk 26） | ✅ 矢量（无模糊风险） |
>
> ⇒ **结论：只有"画布尺寸"符合规范，其余都不符合** —— 所以它被系统当 **legacy 图标**处理
> （缩放变小 + 由 OEM 补背景容器，真机上就是"盾牌偏小、四周一圈留白"）。
> ⇒ 那套**未启用**的自适应前景反而更规范：锁形实测 **x 30..78 / y 26..78，落在安全区内** ✅
> —— 也就是说**结构和图层都写对了，只是没接上总线**（见上面第 1 条）。
>
> ### ✅ 已实现并上机（2026-09-14 00:16，用户已定稿）
>
> **设计（三稿迭代后的定稿）**：**徽章盾 + V 带**，蓝金配色。
> - 三层结构：**白描边盾 →（留一道缝）→ 白色内场 → 金色 V 带** ⇒ 徽章/珐琅的层次感，不再是"一块白板"；
> - **V 呼应项目名 Vaultix，盾形本身是 U** ⇒ "U 中含 V"；
> - 背景 = **三档对角渐变** `#5B7BF0 → #3B5BDB → #1E2C8C`（左上亮→右下深）；
> - **金 V**（`#E0A33A`）= 全图标唯一的第二色相（蓝金 = 保险库/黄铜的经典搭配）。
>
> **迭代记录（两次被否，各修掉一个真问题）**：
> 1. **第一版"盾 + 钥匙孔"被否**：用户反馈"像一张邪恶的笑"。
>    根因可量化 —— 钥匙孔的**圆（直径 12）与锥形槽（顶宽 9）几乎等宽**，合并后就是"一张咧开的嘴"
>    （空想性错视）。⇒ 不是配色问题，是**比例失误**。
> 2. **人字带方案被否**："改成 **V** 字带更好，可以呼应项目名" ⇒ 采纳并深化。
>    同时用户提出"两种颜色太单调" ⇒ 背景升级为三档渐变、并引入金色第二色相。
>
> **★ 关键工程点：V 的"视觉居中"必须算，不能靠眼**
> | 对象 | 包围盒中心 y | **面积质心 y** |
> |---|---|---|
> | 内场 | 57.39 | **54.73** |
> | 首版 V | 54.00 | **50.89** ⇒ 视觉重心比内场**高 3.84dp** |
>
> 根因：**V 带的重量集中在上面两条臂**（顶部最厚）⇒ 面积质心天然高于包围盒中心，
> 只对齐包围盒是不够的。解：**下移 3.84 + 半宽 13→11** ⇒ 质心偏差 **0.000**、
> 边采样（步长 0.5）越界数 **0**。最终 V 路径 `M43,45 L49,45 L54,63 L59,45 L65,45 L54,71 Z`。
> 48dp 实际尺寸：V 高 11.5dp、臂宽 2.7dp。
>
> **落地的文件**：
> | 文件 | 作用 |
> |---|---|
> | `drawable/ic_launcher_background.xml` | 满幅 **三档对角**渐变（`angle=315`；三档必须给 `centerColor`） |
> | `drawable/ic_launcher_foreground.xml` | 白描边盾（stroke 3.2）+ 内场 + **金色 V** |
> | `drawable/ic_launcher_monochrome.xml` | 单色层：同几何，但 **V 改成挖空**（单色层只有一种着色，填色的 V 会与盾同色而消失） |
> | `mipmap-anydpi-v26/ic_launcher.xml` | adaptive-icon 声明（background + foreground + monochrome） |
> | `values/colors.xml` | `ic_launcher_bg_top/mid/bottom` + `ic_launcher_accent` |
> | ~~`drawable/ic_launcher.xml`~~ | **已删除**（旧占位图，manifest 改指后成为死文件） |
> | `AndroidManifest.xml:43` | `@drawable/ic_launcher` → **`@mipmap/ic_launcher`**（整件事的关键一步） |
>
> **验证**：① 五遮罩预览图形均完整；② 全模块 `detekt` + `:app:assembleFullDebug` 通过；
> ③ **真机**：发布密钥重签 → `install -r` → **设备上 APK 与本地包 SHA-256 完全一致**
> （`29e077f7…`，这才叫"确实装上了"）→ 应用信息页截图确认蓝金 V 带盾渲染正常。
> ⚠️ 过程中踩到两个坑：**aapt2 报 `Failed to parse XML file`** 是因为
> **XML 注释里放了 markdown 表格分隔行 `|---|---|`**（注释不允许出现连续两个短横线）；
> **`adb install` 被拒**（`INSTALL_FAILED_ABORTED: User rejected permissions`）是因为**设备锁屏**——
> 且重跑时输出可能只剩空白行、看着像装上了，必须用 SHA-256 核证。
> ⏳ 未验：主题图标（`monochrome`）需在系统里开启「主题图标」才能看效果，未擅自改你的系统设置。

> ## 【最新】第五十二轮（2026-09-13）：**用户 13 条反馈已交付并推送**（提交 `e903c4b`）
>
> 根因见 `.ai/ISSUES.md` **#81~#87**。**已推送 `main`**。用户逐条真机验收，其中
> ①②③④⑤ + 双影/红边/全屏编辑/去返回箭头 **已确认修好**；⑥⑦ 待复测。
>
> **本轮交付**：
> - ① 手势返回"画面缩小 1~2 帧" = Android **预测式返回预览**（与转场无关）
>   ⇒ manifest 显式 `android:enableOnBackInvokedCallback="false"`（#81）✅ 已确认
> - ② 二级路由转场统一为 **Tab 那套**（`fadeIn + 上移 1/16`）——用户明确"不要花哨动效"（#87）✅
> - ③ 自动填充解锁后**反复解锁 / 看不到条目** ⇒ 认证意图去 `NEW_TASK` + `AutofillActivity` 去
>   `taskAffinity`（两者都把 Activity 推进独立 task ⇒ 认证结果送不回系统），回灌改 `FillResponse`（#82）✅
>   > ⚠️ 2026-09-26 更正：此处原写作「#82/#83」，但 **`#83` 从未有过正文条目**（全仓只此一处引用它）。
>   > 整条修复的根因记录在 **#82**。引用一个不存在的号，等于把读者指向空白 —— 已改。
> - ④ 覆盖安装后**生物验证不出现** ⇒ KEK 判据"探测宽、失败判定严"（#85）✅
> - ⑤ 生物验证**自动弹出**非 100% ⇒ 区分"用户放弃 / 系统侧终止"，后者归还机会
>   （`UiState.autoPromptAborts`，上限 2 次）✅
> - ⑥ 验证码页**冷启动 1~2 秒假空态** ⇒ `loading` 与空态分支分离，加载中显示转圈（#84）⏳ 待复测
> - ⑦ 其余：左滑删除**加门槛**（必须先长按进多选）/ 深色**红边**（#86）/ 移除下拉刷新指示器 /
>   详情页**沉浸式顶栏 + 去返回箭头** / 编辑页**整页全屏**（新增 `FullScreenDialogShell`）/
>   修复返回**双影**（MainShell 重进组合会重放 Tab 过渡，加"跳过首次"旗标）✅ 多数已确认
>
> **⚠️ 本轮最大教训**：「手势返回画面缩小」我**改了三次转场**都没打中，因为**复现方式本身是错的**
> —— 用 `input keyevent 4`（按键返回）去复现**手势**返回的问题。同理"填充反复解锁"白改一轮，
> 因为只抓了自己的日志 tag、看不到系统框架侧说"结果被丢弃"。**先对齐复现路径、先取实证，再动代码。**
>
> **待办（下一轮）**：
>
> 0. ⭐⭐ **【最高优先】#88：升级后快速解锁失效** —— debug→release **原地覆盖**（数据与签名都没变）
>    之后，包裹密钥变成不可解密，用户要"重新登录 + 删掉快速解锁 + 重新启用"才恢复。
>    **不阻断发布**（可自愈、数据不丢），但**每个升级的用户都会遇到**。
>    ⇒ **专项计划已写好：[`quick-unlock-plan.md`](./quick-unlock-plan.md)**
>    （含复现步骤、埋点清单、观测→根因判据表、以及一处可先行的 UX 改进）。
>
> 1. ⭐ **应用内 PIN 解锁**（用户连续三轮要求，一直欠着）。定位 = **「解锁便利」而非找回手段** ——
>    PIN 只用于解开 Keystore 里已包好的那份密钥（与指纹同一条链），**不参与派生库密钥**；
>    需要：设置页开启/修改/关闭 + 解锁页数字键盘入口 + 主密码兜底。
> 2. ⭐ **性能专项**：见 [`perf-plan.md`](./perf-plan.md)。
>    **P0 必须先换 CI 的 release 包建立基线** —— 现有数字来自 debug 包，不能作为优化依据。
> 3. 同类"假空态"尚存于 `ItemsScreen:310`（密码列表）与卡包页；密码页因活跃库 id **同步**解析
>    暂未复现。若出现，照 #84 同一套改（加 `loading` + 分支前移）。
> 4. `deliverPendingFill()` 拿不到有效暂存时是**静默** `finish()`（`RESULT_CANCELED`），
>    用户看不到任何解释 —— 值得补一条"填充已失效，请重试"的提示。
> 5. **密保问题：结论是不做** —— 它不是找回手段，只会给同一个库再加一把更弱的钥匙；
>    Bitwarden 的找回走服务端机制（提示语 / 2FA 恢复码 / 组织重置），KDBX 忘记主密码＝打不开，
>    这是格式的固有属性，客户端变不出来。
>
> ## 第五十一轮（2026-09-13）：**用户复盘后 6 条反馈已交付**（提交 `d062792`）
>
> 根因见 `.ai/ISSUES.md` **#76~#80**；逐条对照见 `current-status.md` 末节。**已推送**，等真机验收。
>
> 用户三个歧义点经 `AskUserQuestion` 拍板：③**「长按只勾选，滑动才显红」** /
> ⑤**「密码框 → 图标 → 解锁按钮」** / ⑥**「按在线 / 离线状态」**。
>
> - **①切页闪**：用户确认已修好，本轮**无需改动**。
> - **②验证码页不沉浸**：让位又写回了**滚动容器外的 `Column.padding(top)`**
>   ⇒ 内容永远画不到顶栏区域。改成 `LazyColumn.contentPadding(top = barPadding + 8.dp)`，
>   进度条改**列表首项**随滚动滑走（= `#67 同根因残留`，修一类 bug 时要全仓搜该模式）。
> - **③长按与删除冲突**：长按**只**回调 `onLongPress`（进多选、只勾选），不显红不位移；
>   **红底 alpha 与位移只由手指位移驱动**（`reveal = -offsetX / thresholdPx`）；
>   删除底改**常驻** + `graphicsLayer { alpha = reveal }`（去条件式增删节点）；整套 `armed` 机制删除。
>   「一个手势事件只承担一种语义」。
> - **④填充下拉 M3**：56dp 行高 / 40dp 圆形 tonal 底板 / 16dp 间距 / 标题 16sp · 副标题 14sp /
>   水平 16dp；位图 24→34dp 避免上采样发虚；`drawable/` + `drawable-night/` 两套底板
>   （**RemoteViews 拿不到 Compose 动态取色**）。
> - **⑤指纹图标**：去掉 `FilledTonalButton` + 长文案，改 **64dp 指纹图标**（primary 色）
>   置于密码框与解锁按钮之间；解锁按钮仍灰色 `FilledTonalButton`，置灰规则与自动弹出逻辑不变。
> - **⑥云图标 + 下拉空隙**：新增 `PendingOpDao.observeItemLevelIds`
>   （**只取 `op IN ('CREATE','UPDATE')`** —— 判定集合必须 = 展示集合）→
>   `ItemRepository.observeSyncStates` → 两页 `syncStates` → 共用 `CloudSyncIcon`；
>   已同步 = `CloudDone`（弱化色），待推送 = `CloudOff`（error 色），**KDBX 恒空不画云**；
>   下拉指示器加 `padding(top = topInset)` 与列表首条对齐。
>
> **门禁（本地真跑全绿）**：全模块 `detekt` ✓ / `:app:compileFullDebugKotlin` ✓ /
> `:app:assembleFullDebug` ✓ / `testFullDebugUnitTest` ✓（`BUILD SUCCESSFUL in 33s`，258 tasks）。
>
> **⏳ 真机待验收（7 条）**：验证码页沉浸 / 长按只勾选且滑动跟手 / 填充下拉 M3 /
> 指纹图标与优先弹出 / 云图标（KDBX 不画）/ 下拉无空隙 / 回归无破坏。

> ## 【已归档】第五十轮（2026-09-13）：**睡前 5 bug + 1 美化已交付**（提交 `23c3629`，CI `34713640553` success）
>
> 根因见 `.ai/ISSUES.md` **#70~#75**。
>
> - **①切页闪**：cross-fade 进出**同时长 + alpha 互补**（不同步 ⇒ 中间帧双向半透明、
>   背景漏光 25%，深色底就是一次闪光）。
> - **②倒计时条**：`lerp(44dp → 0dp)` + `alpha = 1 - frac` + `tween(200)`（对齐 Bastion）。
> - **③卡片按钮 / 多选**：主操作 = 点卡片，低频 = MoreVert 菜单，批量 = 长按进选择态
>   （`Checkbox` + 底部批量条，密码页与验证码页共用）；左滑删除保留且不与长按抢事件。
> - **④填充下拉**：48dp 行高 + 去底框 + 24dp 图标；`AutofillItemIcon` 取 Coil 缓存站点图，
>   未命中画哈希配色字母头像（**RemoteViews 只吃 Bitmap，且不能发网络**）。
> - **⑤解锁提速**：Keystore / 密钥派生全部 `Dispatchers.IO` + `flowOn(IO)` + `prewarmCipher`。
> - **⑥美化**：表单分区图标化、分隔线留白加大、条目行尾收藏星标。
>
> **门禁（本地真跑全绿）**：全模块 `detekt` ✓ / `:app:compileFullDebugKotlin` ✓ /
> `:app:assembleFullDebug` ✓ / `testFullDebugUnitTest` ✓。
>
> **⏳ 真机待验收（6 条）**：切页不闪 / 倒计时条随滚动 / 长按多选 + 左滑删 /
> 填充下拉有站点图标 / 指纹即时弹出 / Bitwarden 与 KDBX 无回归。

> ## 【已归档】第四十九轮（2026-09-13）：**用户 8 条反馈全部落地**（P0 解锁逻辑 + 布局三症 + 观感）
>
> 提交 `bc09995` + `678be0d`，已推送。根因见 `.ai/ISSUES.md` **#66~#69**。
>
> - **① P0 解锁**：CP 的锁态判据从「**存在**锁定的库」改为「**当前**库是否解锁」，
>   并让解锁 Activity 区分 CP 流程（CP 没有暂存，解锁完直接 `finish`）⇒ 修掉**无限解锁环**；
>   passkey / 密码回灌的锁态统一改问**仓储**（含 KDBX），不再把「字段都为空」当锁定。
> - **②③④ 布局三症是同一个根因**：顶部让位写在外层容器 `padding` 上（**4 个 Tab 全是**）
>   ⇒ 改成 `LazyColumn.contentPadding` / 可滚动 `Spacer`；底栏改**叠层悬浮** +
>   各页 `bottomInset`；搜索态加 `BackHandler`（主界面是根路由，不拦就退桌面）。
> - **⑤** 长按滑删补 **armed 可见反馈**（手势本来就有，只是用户看不见）。
> - **⑥** 设置页「密码库」补「添加密码库」→ 可进 KDBX 选文件流程（此前入口挂在不可达路由后）。
> - **⑦** 填充下拉 56dp / 16·10 内边距 / 12dp 圆角实底 / 15sp · 13sp。
> - **⑧** 全局输入框 12dp 圆角（`shapes.extraSmall`）、类型彩色徽标、详情页图标头部、
>   分区卡片 `surfaceContainerHigh`、验证码 36sp ExtraBold 等宽（≤5s 转 error）。
>
> **门禁（本地真跑）**：`:app:compileFullDebugKotlin` ✓ / 全模块 `detekt` ✓ /
> **333 tests, 0 failures**。
>
> **⏳ 真机待验收**：**凭据面板不再反复解锁**（最优先）/ 上下沉浸 / 筛选条不压首条 /
> 搜索无黑横幅且返回只退搜索 / 长按可见反馈 / 设置页能加 KDBX / 观感五件 / Bitwarden 无回归。

> ## 【已归档】第四十八轮（2026-09-12 深夜）：**#60 全清单交付**（1b~4 + 第 2/3/4 批）
>
> 四个提交：`5682bc6`（锁/解锁 1b~4）→ `e88bda2`（顶栏胶囊化 + 点库名筛选）→
> `93c814c`（站点图标）→ `fcaf918`（KDBX 集成 + codec）。
> 坑与取舍见 `.ai/ISSUES.md` **#61~#65**；验收清单见 `current-status.md` 末节。
>
> ### 交付摘要
> - **1b** 主页锁按钮 = `VaultSessionRepository.viewLock`（**不清密钥**）——用户原话
>   「只锁生物验证那一层」；
> - **1c** `RootNavState.VaultLocked(vaultId)`：查看锁排在「已解锁」判定**之前**；
> - **1d** 解锁页 `ViewLockedContent`：**只有**生物识别，无主密码 / 无 2FA / 不联网；
> - **2** 超时真锁在 `VaultSessionManager.lock/lockAll` 里一并清查看锁标记（一处收口）；
> - **3** 「立即锁定」→「**退出数据库**」：`VaultRepository.signOut` = 清会话 + 快速解锁凭据
>   + token + 待推送队列 + 密文条目与文件夹 + 同步基线，**保留库行、不碰远程**（带确认对话框）；
> - **4** **解锁即回填**：`PendingFillStore`（2 分钟 TTL + 一次性消费）+
>   `AutofillCandidateSource` / `AutofillDatasetFactory`（与 Service 共用，
>   保证「解锁前匹配」与「解锁后回填」结论一致）；
> - **第 2 批** 顶栏 6 个图标 → 🔍 + ⋮；点库名展开筛选（验证码/通行密钥/SSH/笔记/收藏），
>   筛选条**浮在内容之上** + 透明遮罩，状态**刻意不持久化**；
> - **第 3 批** `core:common/SiteIconUrl`（纯字符串）+ Coil 缓存 + 首字母兜底；
> - **第 4 批** KDBX 集成（SAF → 库行 → 解锁 → **读路径分流** → 切库即锁旧库）+
>   `KdbxTotpCodec` / `KdbxPasskeyCodec`。
>
> ### ⏳ 真机待验收（7 条，按优先级见 current-status 末节）
> 锁按钮只认证一次 / 退出数据库 / 解锁即回填 / 顶栏筛选 / 站点图标 / KDBX 全链路 /
> Bitwarden 侧无回归。
>
> ### 明确**未做**（下一批候选）
> 1. **KDBX 阶段 B（写回）**：原子替换 + `.kdbx.bak` + 保真度登记 + 往返测试（目标 KDBX 4.1）；
>    ⚠️ 铁律：插件字段（`KPEX_*`）一律原样保留。
> 2. KDBX 的快速解锁（KEK 包裹，免每次输主密码）。
> 3. KDBX 回收站映射（现在只计数，不进回收站页）。

> ## 【已归档】第四十七轮（2026-09-12 深夜）：**交接单 — 锁/解锁规范化 + 后续三批**
>
> ⚠️ 接力请直接读 **`.ai/ISSUES.md` #60**（含已查证事实、逐步改动点、上游依据），本文件顶部不再重复。
> 一句话状态：ViewLocked 载体已入库（`24b5b4f`，行为零变化）→ 待接线 1b~4
> （锁按钮不清密钥 / RootNav 判据 / 仅认证解锁 / 超时清标记 / 「退出数据库」/ 解锁即回填）；
> 之后依次：顶栏胶囊化 + 点库名展开分类筛选 → 站点图标（`/icons/<域名>/icon.png`）→
> KDBX 集成（引擎已完成 `879e7c1`，10/10 单测）+ 通行密钥/验证码解析（移植 Bastion codec）。
>
> 已交付批次：`4cc4dc1`（Edge 账号框 P0 + 图标 + 快速解锁三缺陷 + 填充辅助开关 + 阶段 3 动效）、
> `310bb25`（卡片外框统一 + 分组 + 按住滑动删除 + 沉浸式顶栏 + 统一进度条）、
> `cb6cbad`（设置页观感对齐 Bastion + 显示选项）、`879e7c1`（KDBX 引擎）、
> `297b12d`（指纹入口回归 + 二次验证对齐 Bitwarden）。

> ## 【最新】第四十四轮（2026-09-12 晚）：观感与交互批次 —— 卡片外框 / 分组 / 沉浸式顶栏 / 统一进度条
>
> 承接第四十三轮，用户连续补充要求：「密码条目外框还能再优化吗」「搬运 Bastion 的外观与动效」
> 「验证码界面点击复制 + 按住滑动删除」「密码条目分组」「统一倒计时进度条」
> 「详情页不要动态验证码（降功耗）」。全部落地于本提交。**待真机验收**。
>
> **① 条目卡片外框（三个列表统一）** —— 新增 `ui/common/EntryCard.kt`，规格照 Bastion
> `PasswordEntryCard`：M3 默认 `Card` 底色/高度 + **12dp 圆角** + **16dp 内边距** + 标题 SemiBold
> + 6dp 行距。此前三处都是 `Surface(color = surface)`（与背景同色、无高度 ⇒ 卡片边界看不见），
> 卡包更是**裸 Row**（连外框都没有）。
> ⚠️ 「外框」属于**组件级卡片样式**，与「壳」（底部导航/Tab 容器/转场）无关 —— 壳第四十三轮已搬完。
>
> **② 密码条目分组** —— 新增 `ui/items/ItemsGrouping.kt`（纯逻辑）+ 顶栏图标菜单，
> 四种模式：**不分组（默认）/ 按类型 / 按文件夹 / 按首字母**，落偏好 `items_group_mode`。
> 分组标题可点折叠（箭头 0°↔-90°，200ms）。未搬 Bastion 的 app/note/smart 分组 —— 依赖其私有条目类型。
>
> **③ 按住后滑动删除（密码 + 验证码 + 卡包）** —— 新增 `ui/common/PressAndSwipeToDelete.kt`：
> 长按 → 卡片轻抬 + 红色删除底渐显 → 向左滑 → 过阈值松手删除；未过阈值回弹。
> 用 `detectDragGesturesAfterLongPress` + `change.consume()`，与「点击」不冲突；
> 删除走**软删除进回收站**（可恢复），列表给一句提示。详见 `.ai/ISSUES.md` #56。
>
> **④ 沉浸式顶栏（4 个 Tab 全部接线）** —— 新增 `ui/common/ExpressiveTopBar.kt`（移植 Bastion）：
> 标题 32sp→16sp、栏高 72dp→48dp、**收起后栏背景 alpha→0**、动作胶囊 48→40dp + 缩放 0.85；
> 快照式收起（首个可见项 > 8dp）+ 200ms 补间。配合 `AdaptiveMainScaffold` 的
> `contentWindowInsets = 0` 与 `rememberImmersiveBarPadding`，内容真正从半透明栏下穿过
> （状态栏沉浸 + 右上按钮沉浸）。⚠️ 三个条件缺一不可，见 `.ai/ISSUES.md` #57。
>
> **⑤ 验证码页统一进度条** —— 新增 `ui/totp/UnifiedProgressBar.kt`（移植 Bastion）：整页一条倒计时，
> 剩余 ≤5s 变警示色，「通行密钥」入口挂在它的尾部插槽（空列表独立成行兜底）；
> **行内进度条全部删除**（每秒每行重绘纯浪费）。
>
> **⑥ 验证码页点击 = 复制**；行内新增「编辑」按钮（点击改复制后编辑入口不能丢）。
>
> **⑦ 详情页去动态验证码（降功耗）** —— 详情页不再每秒重算验证码/重绘进度条，
> 只提示「该条目含有验证码 / 已绑定 N 个通行密钥」，查看去「验证码」页。
>
> **⑧ 2FA 第二步页面完全不响应** —— `isOtpOnly` → 不列条目、也不弹「没有匹配的密码」
> （验证码上一步已进剪贴板，对齐上游 `Unfillable → onSuccess(null)`）。
>
> **⑨ 填充下拉行外框** —— 12dp 圆角**描边**（不填底色：猜错 ROM 面板底色比没框更难看），
> 描边色浅/深两套（`values` / `values-night`）。
>
> **门禁（本地真跑）**：`:app:compileFullDebugKotlin` / `:app:testFullDebugUnitTest` /
> `:data:repository:testDebugUnitTest` / `:core:common:testDebugUnitTest` / 全模块 `detekt` —— 全绿。
>
> **⏳ 真机待验收**：①卡片外框（三列表一致、卡包不再裸行）；②分组开关与折叠；③按住左滑删除
> （三个列表）；④验证码页点一下即复制、行内可编辑；⑤滑动时大标题缩小 + 状态栏/右上按钮沉浸；
> ⑥统一进度条 + 通行密钥入口在条上；⑦详情页只剩提示、不再每秒刷新；⑧填充下拉行外框；
> ⑨ **搜索框仍不乱弹**（信号改动后的持续回归点）。
>
> ### 📋 Bastion 设置页清单（用户要求「能看到的都搬」，待逐项确认）
> | 类别 | Bastion 项 | 状态 |
> |---|---|---|
> | 已具备 | 自动锁定档位 / 剪贴板清除 / 主题（系统-浅-深）/ 动态取色 / OLED 纯黑 / 防截屏 / 回收站自动清理 / 快速解锁 / 库管理 / 自动填充二级页 / 通行密钥状态 | ✅ 已有 |
> | **本轮可补** | 设置页 SectionCard 观感（圆角/间距/字号）与分组标题统一、条目分组模式选择、显示选项（是否显示图标/副标题）、列表密度 | ⬜ 建议下一批 |
> | 需后端能力 | 密码建议 / 智能标题 / 屏蔽字段规则 / 通知时长 / 诊断日志导出 / 安全分析（HIBP）/ 附件与历史 / Send | ⛔ 未做（搬来是点不动的假开关） |
> | 有意不搬 | Bastion 私有本地库 / WebDAV / OneDrive 备份 / 分类（自有体系 → Vaultix 用文件夹） | ⛔ 架构决策 |
>
> **下一步（建议顺序）**：①设置页观感与「本轮可补」三项 → ②真机回归后按反馈修 →
> ③M2 KDBX 引擎（M1/M2-a 已收官）。
>
> ## 【已归档】第四十三轮（2026-09-12）：Edge 账号框 / 填充图标 / 指纹解锁 / 填充辅助开关 + 阶段 3 动效
>
> 用户一次报四件事并要求「搬运 bastion 的外观和动效的改动连通这些修复一起做，一个提交推送」。
> 全部落地于同一提交。逐条见下（**待真机验收**）。
>
> **① Edge 浏览器「账号框不出候选也填不进去」（P0，根因有真机实证）**
> - 直接证据来自上一轮留存的真机日志 `build/adb-capture/device-log.txt`：
>   ```
>   fillRequest pkg=com.microsoft.emmx webDomain=github.com fields=221
>     hints={UNKNOWN=215, PASSWORD=5, USERNAME=2} user=true pass=true
>   ```
>   —— 一个页面解析出 **221 个「字段」**，其中 5 个被判 PASSWORD（GitHub 登录页只有 1 个密码框）。
> - 根因：浏览器 WebView 会把**整棵 DOM** 建成带 `autofillId` 的节点，`<label>Password</label>`、
>   「Username or email address」这类**展示节点**被文本启发式判成 PASSWORD / USERNAME →
>   占掉真账号框的语义位 → `promoteUsernameField` 的「没有可见 USERNAME 才升格」被假 USERNAME 挡住
>   → 真账号框永远 UNKNOWN → `hasUsernameField=false` → Dataset 里只有密码值
>   ⇒ **点密码框能弹（只填密码），点账号框什么都不弹也填不进**（与 QQ 修复前同型）。
> - 修复（两处，均对齐上游 Bitwarden `ViewNodeExtensions.toAutofillView`）：
>   1. **节点准入闸** `isEditableNode()`（新文件 `EditableNodePolicy.kt`）：有 `htmlInfo` 时
>      只认 `tag == "input"`；无 htmlInfo 时认 className 的 EditText 家族；两者都判不出才放行；
>      带标准 autofillHints 一律放行。非输入控件**直接丢弃**（上游同款闸门）。
>   2. **语义信号不再包含 `node.text`**（新函数 `BrowserUrlBars.formSignalOf`）：上游启发式从不读
>      `node.text`，只看 `idEntry` / `hint` / `htmlInfo` —— `text` 是内容/标签文字，正是误判之源。
>      `text` 仍作为字段**值**解析（保存流程用），两件事分开。
>      HTML 属性键同时补齐上游的 `autocomplete` / `label` / `hint` / `autofill`
>      （站点用 `<input autocomplete="username">` 声明语义时直接判成账号框，不再依赖邻近升格）。
> - **新增诊断**：`fillRequest` 日志追加 `seq=USERNAME,UNKNOWN(hid),PASSWORD,…`（字段序列 + 可见性）。
>   账号框填不进去时，「真账号框是 UNKNOWN」与「被假 USERNAME 占位」只看计数分不清，看序列一眼可定。
> - 单测：`EditableNodePolicyTest`（7 例，锁「web 展示节点不得进字段表」）。
>
> **② 填充下拉的图标（用户：太丑、别人家都是小而精致的）**
> - 上一版是 **40dp 彩色启动图标**（`R.mipmap.ic_launcher`）；现对齐 Bitwarden
>   `autofill_remote_view.xml` 的真实规格：**20dp 单色语义图标** + 12/6dp 内边距 + 48dp 最小高度。
> - 新增 4 个矢量：`ic_autofill_login`（地球）/ `ic_autofill_card`（卡片）/
>   `ic_autofill_identity`（人像）/ `ic_autofill_vaultix`（盾牌+钥匙孔，整表认证行专用，不上色）。
> - 上色方式对齐上游 `Context.iconTint`：RemoteViews `setColorFilter`，亮色 `#44474E` / 暗色 `#C4C6CF`。
> - 类别经 `AutofillIntents.EXTRA_CATEGORY` 一路带到认证回灌，保证二次验证后图标不跳变。
>
> **③ 指纹解锁「覆盖安装 + 重启后不生效」（三个独立缺陷叠加）**
> - **A. 解锁页会卡死在 `submitting`**（最能解释「按钮在、点了没反应」）：
>   `onError` 只处理「用户取消」，而 `ERROR_TIMEOUT` / `ERROR_CANCELED` / `ERROR_HW_UNAVAILABLE` /
>   `ERROR_LOCKOUT` 都不在白名单里 → `submitting` 永远为真 → 指纹按钮与主密码按钮双双置灰、
>   转圈不散、后续点击被守卫直接 return ⇒ **整页死锁只能杀进程**。
>   修复：任何错误都复位；仅「用户/系统取消」保持安静，其余显示原因；
>   `ERROR_CANCELED` 归入「取消」。
> - **B. 自动弹窗时机**：新增的「进解锁页自动弹一次」若在 Activity 尚未 RESUMED 时发起，
>   重启后（生物识别 HAL 未就绪）很容易被系统以非取消错误结束 → 正好踩中 A。
>   修复：改为 `lifecycle.withResumed { … }` 之后再发起。
> - **C. 一次瞬时失败会「自毁」注册**：`isLocalUnlockUnrecoverable` 把
>   `UserNotAuthenticatedException`（语义 = **本次未认证**）当成「密钥已废」，
>   触发 `clearBrokenLocalUnlock` **真删用户的快速解锁注册**；
>   且 `LocalUnlockKeyStore.obtainKey()` 在别名缺失时会**静默新建 KEK**，
>   把「钥匙丢了」掩盖成「认证成功 + 解密失败」→ 照样走到自毁分支。
>   修复：①`UserNotAuthenticatedException` 移出「不可恢复」（对齐 `Docs/03` 的
>   「应拉起 BiometricPrompt 重认证」）；②`LocalUnlockKeyStore` 拆
>   `loadKey()`（只读，绝不新建）与 `obtainOrCreateKey()`（仅启用路径可新建，
>   且先删失效别名再建）；③新增三态 `kekStatus`（LOADABLE / MISSING / INVALIDATED）——
>   `containsAlias` **不能**当健康检查（平台在密钥永久失效时让它静默返回 false）。
> - **D. CI 层「假覆盖安装」**：`ci-debug.yml` 原先只在 **push** 时注入固定密钥 ⇒
>   手动 `workflow_dispatch` 出的包是 runner 现生成的一次性 debug key 签的，
>   **盖不上 preview 包**（只能卸载重装 ⇒ 数据 + Keystore 一起没，表现恰是「指纹解锁被清除」）。
>   已改为 `event_name != 'pull_request'` 即解码，构建步骤按磁盘上是否存在 `release.jks` 决定是否签名。
>
> **④ 填充辅助（Fill Assist）独立开关（用户：上游有独立按钮，我这个 APP 上没有）**
> - 新增偏好 `fill_assist_enabled`（默认开）+ `VaultixPreferences.fillAssistEnabled` /
>   `setFillAssistEnabled`；设置 → 自动填充 → 填充行为新增一行开关（图标 `AutoFixHigh`）。
> - 文案直接取上游官方中文 `values-zh-rCN`：**启用填充辅助** /
>   「填充辅助通过使用站点特定的规则，提高在受支持站点上的自动填充的准确性」。
> - 关闭后**完全不读规则表**（识别退回纯启发式，行为等于搬运 Fill Assist 之前），
>   且不再做 6 小时节流的规则刷新（省掉无意义联网与磁盘写）。
> - 说明：上游是 **feature flag + 设置项**双重门控（且 bitwarden.com 上 flag 为 false），
>   Vaultix 只用设置项 —— 自建 Vaultwarden 通常不返回该 flag，照搬会让功能永远关着。
>
> **⑤ 阶段 3 观感（Bastion 外观与动效）**
> - **Tab 切换转场**：新增 `ui/shell/TabTransitions.kt`（移植 Bastion `NavTransitions` 的 tab 部分：
>   进入 = fadeIn + 从 1/16 屏高上移，220ms；退出 = 纯 fadeOut，120ms；缓动
>   `CubicBezierEasing(0.6, 0, 0.4, 1)`），并在 `MainShellScreen` 用
>   `AnimatedContent + SizeTransform(clip = false)` 接线（对齐 Bastion
>   `AuthenticatorPasskeyAnimatedContent` 的 `contentKey` 用法）。
> - **Tab 状态保留**：`rememberSaveableStateHolder()` + `SaveableStateProvider(tab.name)` ——
>   切走再切回不再回到列表顶部、搜索词不丢（对齐 Bastion 的 `cardWalletSaveableStateHolder`）。
> - **验证码倒计时平滑进度**：移植 Bastion `rememberTotpSmoothProgress`
>   （秒级数据源 + 绘制层 1s 线性动画，周期翻转时 `snap()` 防倒卷）。
> - 未做（阶段 3 剩余）：主界面卡片样式细化、FAB 行为按需取用、设置页分组结构复核。
>
> **门禁**：`:app:compileFullDebugKotlin` + `:app:testFullDebugUnitTest` +
> `:data:repository:testDebugUnitTest` + `detekt` **全绿**（本地真跑，非推测）。
> **待真机验收**：①Edge 账号框能弹能填；②搜索框仍不乱弹（回归重点）；
> ③填充下拉图标小而克制；④覆盖安装 + 重启后指纹解锁可用；
> ⑤设置页出现「启用填充辅助」开关；⑥Tab 切换有淡入上移过渡且切回不丢滚动位置。
>

> ## 【已归档】第三十九轮（2026-09-12）：通行密钥 `clientDataJSON` 占位符错误回退（标准合规）
>
> 用户实测 **GitHub 注册通行密钥**报 `Security key authentication failed`，并要求
> 「**按照标准改对，不要失误，不要自己乱加。应该是有标准的才对。**」
> —— 本轮**逐字拉 W3C 规范原文核对**后，确认第三十轮引入的「占位符」方案本身是错的，予以回退。
>
> **① 根因：浏览器流程回传空 `clientDataJSON`，违反规范 §5.8.1.1 / §7.1 / §7.2**
>
> 第三十轮（`90d5e6d`）依据 Android 官方文档那句
> `To avoid JSON parsing issues, set a placeholder value for clientDataJSON`
> 把浏览器流程的 `clientDataJSON` 改成 `ByteArray(0)`。**该句有前置条件**：
> > **If you retrieve an origin**, use the `clientDataHash` ...
>
> 指经 `CallingAppInfo.getOrigin(privilegedAllowlist)` + **特权应用名单**拿到 origin 的场景
> （Google Password Manager 走那条路，challenge 校验由系统侧完成）。
> Vaultix 的 `CallingAppOrigin` 明确走「**自证式读取**」、**不用特权名单** ⇒ 不适用占位符。
>
> 而规范层面 RP **一定**解析 `clientDataJSON` 明文逐项校验：
> > **§7.1** Let JSONtext be the result of running UTF-8 decode on `response.clientDataJSON`.
> > Let C be the result of running a JSON parser on JSONtext.
> > - Verify that `C.type` is `webauthn.create`.
> > - Verify that `C.challenge` equals **the base64url encoding of `options.challenge`**.
> > - Verify that `C.origin` matches the Relying Party's origin.
>
> **§5.8.1.1 `CollectedClientData`** 对字段的定义同样是明文：
> `challenge` = **the base64url encoding of options.challenge**、`origin` = the serialization of callerOrigin。
> ⇒ **空字节数组连 JSON 解析都过不了**，`C.challenge` 校验必然失败。
>
> **② 修法：回传自建真实 JSON，两条流程唯一差别是「签名覆盖哪份哈希」**
> | 文件 | 改动 |
> |---|---|
> | `core/common/WebAuthn.kt` | **删除** `BROWSER_FLOW_CLIENT_DATA_PLACEHOLDER`；`buildClientDataJson` 增 `androidPackageName` 参数；新增语义化封装 **`buildCreateClientDataJson`** / **`buildGetClientDataJson`**；重写 KDoc 引规范 §5.8.1.1/§7.1/§7.2 原文 + 官方文档条件句 + Bastion 依据 |
> | `passkey/PasskeyProviderIntents.kt` | `createIntent` 补 **`clientDataHash: ByteArray? = null`**（对齐 GET 侧的 `getIntent`）+ `putExtra(EXTRA_CLIENT_DATA_HASH)` |
> | `passkey/VaultixCredentialProviderService.kt` | `buildCreateResponse` 透传 `(request.callingRequest as? CreatePublicKeyCredentialRequest)?.clientDataHash` |
> | `passkey/PasskeyCreateActivity.kt` | 删占位符分支，始终 `buildCreateClientDataJson(..., androidPackageName = null)`；接收 `clientDataHash` 只做自检日志；**origin 推导顺序改为 `requestJson.origin` → `CallingAppOrigin` → `https://$rpId`**（对齐 Bastion `PasskeyOriginResolver`） |
> | `passkey/PasskeyGetActivity.kt` | **回退**占位符改动，改 `buildGetClientDataJson(..., androidPackageName = null)`；签名仍用 `clientDataHash ?: sha256(自建 JSON)` |
> | `core/common/.../WebAuthnTest.kt` | 改写 2 个已失效的占位符用例为 **`browser flow signs provided hash and returns real clientDataJSON`** / **`create response always carries real clientDataJSON`**，新增断言「非空 + challenge 为 base64url 形态 + type/origin 正确 + 浏览器流程不含 `androidPackageName`」 |
>
> **③ 关键区分（本轮核心结论，勿再混淆）**
> | 事项 | 浏览器流程 | 原生 App 流程 |
> |---|---|---|
> | **签名**覆盖 | `authData ‖ clientDataHash`（系统给的） | `authData ‖ sha256(自建 JSON)` |
> | **回传** `clientDataJSON` | **自建真实 JSON** | **自建真实 JSON**（同一份） |
> | `androidPackageName` | **不写**（浏览器那份没有该字段，写了会让 RP 重算哈希与浏览器不符 —— Bastion 实测 Microsoft 登录失败） | 可写 |
>
> **④ 验证（沙箱真跑）**
> - `:core:common:testDebugUnitTest` → **95 用例 0 失败**（含改写后的 3 条 clientDataJSON 回归锁）
> - `:app:compileFullDebugKotlin` → **BUILD SUCCESSFUL**
> - 改动文件超 120 字符行长 = 0（detekt `MaxLineLength`）
>
> **待真机验收**：GitHub 注册通行密钥（此前报 `Security key authentication failed`）+ 登录，
> 两条都应通过；`logcat` 抓 `PK create clientDataJson ... jsonMatchesBrowserHash=` ，
> 该值为 `false` 属**预期**（系统那份哈希来自浏览器），不再是故障信号。
>

> ## 【已归档】第三十八轮（2026-09-12）：通行密钥「登录最后一步校验失败」根因修复
>
> 用户反馈：「通行密钥能够读取到，能够进入登录，在**最终校验**的时候提示错误。」
> 定位到一条**一字节都不对**的硬伤，已修复并加回归锁。
>
> **① 根因：`rawId` 被二次 Base64 解码（`PasskeyGetActivity.kt:446`）**
>
> 注册侧（`PasskeyCreateActivity`）三处口径一致、正确：
> `credentialId = WebAuthn.base64Url(key.credentialId)` 落库；`authData` 里放**原始 32 字节**；
> `rawId = base64Url(原始 32 字节)`。
>
> 但登录侧把**已经是 b64url 文本**的 `cred.credentialId` **又解码了一次**，再编码回去：
> ```kotlin
> // 旧实现（已证伪）
> val idBytes = decodeBase64UrlOrStandard(cred.credentialId) ?: cred.credentialId.toByteArray(UTF_8)
> buildGetResponseJson(credentialId = idBytes, ...)     // 内部又 base64Url()
> ```
> 规范要求 `rawId` **逐字节等于** RP 在 `allowCredentials` 里持有的 ID，而该路径有两处失配：
> - **标准 Base64（含 `+` `/`）存储的 ID 被规范化成 b64url（`-` `_`）** → 文本不同。
>   实测：`2z85N/AMiSf…qf/i5FU=` 被回传成 `2z85N_AMiSf…qf_i5FU`，任何做字符串比对的 RP 直接拒。
> - 解码抛异常时被 `?:` 兜底成 `text.toByteArray(UTF_8)` → 把 Base64 文本本身当 ID 发出去。
>
> 这解释了全部三个现象：候选能列出（`idMatches` **两边都解码**，自洽 → 麻痹了测试）、
> 能进登录页（只用 rpId/元数据）、**最后一步校验报错**（签名/authData 全对，唯独 rawId 错）。
>
> **② `userHandle` 是同一陷阱的第二实例**
> 注册侧 `userHandle = json.optJSONObject("user")?.optString("id")` **原样落库**（未解码），
> 登录侧却又 `decode` → `base64Url` 编码回去。同样改为原样回传。
>
> **③ 修法：以「存储态文本」为准**
> | 文件 | 改动 |
> |---|---|
> | `core/common/WebAuthn.kt` | 新增 **`rawIdFromStored()`**（合法 Base64 则**原样返回**；脏数据退化为 UTF-8 重编码，不崩）+ **`buildGetResponseJsonFromStoredId()`**（`id`/`rawId`/`userHandle` 全走原样文本）；给旧 `buildGetResponseJson` 加「优先用新函数」的 KDoc 警示 |
> | `passkey/PasskeyGetActivity.kt` | 改用 `buildGetResponseJsonFromStoredId`；删掉 `idBytes` 与 `userHandle` 的两次多余 decode；补 `rawId=` / `storedIsBase64=` 现场诊断日志 |
> | `core/common/.../WebAuthnTest.kt` | 新增 2 例回归锁（`stored credential id is echoed verbatim as rawId`、`rawIdFromStored tolerates blank input`） |
>
> **④ 验证方式（沙箱内真跑，非推测）**
> 本轮把 `core/common/WebAuthn.kt` 连同验证器用 `kotlinc` 单独编译成 jar（绕过 Gradle/Android），
> **14/14 项检查通过**；并跑通真实 Gradle：
> - `:core:common:testDebugUnitTest` → **12/12 通过**（含新增 2 例）
> - `testFullDebugUnitTest` 全量 → **141 用例 0 失败**
> - `detekt` → 通过（修掉 1 处 `MaxLineLength`）
> - `:app:compileFullDebugKotlin` → **BUILD SUCCESSFUL**
>
> ⚠️ 唯一未能在沙箱完成的是 `assembleFullDebug` 的 **native 符号剥离**（缺 NDK，`stripFullDebugDebugSymbols`
> 报 MD5 读取失败）——属沙箱工具链缺口，与本次改动无关；该步在 CI 上正常。
>
> **⑤ 顺手修复沙箱构建环境（长期收益）**
> - `/root/.gradle/init.gradle` 原本**语法错误**（`mavelCentral()` 拼写错 + url 未加引号），
>   导致所有 Gradle 调用初始化即失败 → 重写为 `beforeSettings` 注入 Aliyun/腾讯镜像
>   （pluginManagement + dependencyResolutionManagement 两处都要，项目设了 `FAIL_ON_PROJECT_REPOS`）。
> - 安装 Android SDK：`platforms/android-37.0` + `build-tools/37.0.0`（**注意 37 的目录名带扩展版本号，
>   在线 manifest 里根本没有 `platforms;android-37`**，只能按 `platform-37.0_r01.zip` 直取）。
> - hosts 补 `dl.google.com → 113.108.239.161`（此前被污染到 fake-ip `198.18.0.13`）。
>
> **待真机验收**：用 Google/Edge 打开某站点 passkey 登录，确认最终校验不再报错。
>
