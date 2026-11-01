# 01-构建与环境 · 第 4 篇（#105 ~ #125）

> 主篇索引：[`01-构建与环境.md`](./01-构建与环境.md)（含计数规则与重号警告）
> 本篇 6 章 · 15.4KB · 覆盖 #105 ~ #125

## 105. 🔴 沙箱「下不动 APK」是两层问题 —— hosts 只是第一层

**背景**：2026-09-16 收尾时想按 issues #102 的规矩真下载一次 APK 验证，
连续三次失败，而且**每次失败的原因都不一样** —— 很容易误判成同一个问题。

| 次 | 现象 | 真实原因 |
|---|---|---|
| ① | `http=302 size=0` | `release-assets.githubusercontent.com` → `198.18.0.34`，**hosts 记录丢了** |
| ② | 240s 只到 3.4MB | hosts 已修好，**带宽极低 ⇒ 超时截断** |
| ③ | `http=000`，90s 放弃，0 字节 | hosts 正常，**整文件连续传输不可靠** |

**关键诊断（区分第二层和第一层）**：

> 用 **range 请求** `curl -r 0-0` 对同一 URL —— 拿到 **`http=206` + 正确 1 字节**，
> 落点 `185.199.108.133`；但**不带 range 的整体下载反复 `000`**。
> ⇒ **CDN 可达、有数据，问题在「整文件持续传输」这一环节。**

**如果没做这个对比**，看到 `000` 会以为又是 hosts 问题，去反复改 hosts —— **白折腾**。

**有效解法：1MB 分块 + 重试 + 回滚**
```bash
# ① 探总长（range 请求读 Content-Range）
curl -sL --max-time 45 -r 0-0 -D - -o /dev/null "$URL" | grep -i '^content-range'   # → 32329710
# ② 逐块追加，每块 5 次重试；失败时 truncate -s <上一次确认成功的偏移>
curl -sL --max-time 60 -r ${off}-${end} "$URL" >> "$OUT"
# ③ 每块后校验 stat -c%s "$OUT" >= end+1，不满足就回滚重试
```

**已沉淀为可复用脚本**：`.ai/tools/fetch_release_asset.sh <url> <out>`
（三段式：探长 → 分块续传 → `zipfile` 校验，全自动）。

**⚠️ 实测证据（说明重试是必需的，不是过度设计）**：脚本跑通后再单独连测 3 次探长，
结果是 `''` / `''` / `'32329710'` —— **前两次空返回，第三次才拿到**。
⇒ 同一 URL、同一时刻，**失败是常态而非偶发**，任何单次尝试都不足为凭。

**两条必须记住的判断口径**：

1. **`gh release view` 能看到资产 ≠ 能下下来。** 前者只走 API，后者要过 CDN，
   **两者必须分开验证**（issues #102 已记一次，本次是再次验证）。
2. **不许因为 release 页面看得到就写「已验证可下载」。**
   拿不到完整 zip，结论就只能写「release 已发布，沙箱未能完整验证」。

**「完整」的判据**（与 #102 配套）：

> ❌ **别用 `file` 判断 APK 完整性。** 它只看头几个字节 ——
> 本次那个 3.4MB 的截断文件照样被报成 "Android package"。
> ✅ **用 `zipfile` 打开**：能列 `namelist()` 且含 `AndroidManifest.xml` 才算完整。

**一般化教训**：**同一个外部依赖（CDN）上的失败，可能是多层不同的原因叠在一起。**
定位时不要假设"还是上次那个原因"，要用**能区分层次的对比实验**（range vs 整体）
一次把层次分开。否则会在错误的那一层反复修。

---

## 112. 🔴 Windows 版 Python 写文件把 `\n` 变成 `\r\n` ⇒ 行式数据（id 列表）**全部失效**（2026-09-21）

**现象**：用 Windows Python 生成一份"待删缓存 id"清单（每行一个 id），
再用 bash 循环 `gh cache delete` ⇒ **80 次全部失败**，而第一轮我把 stderr 吞了
（`>/dev/null 2>&1`），看起来像"跑完了、删了 79 条"。

**根因**：Windows Python 的 `open(path, 'w')` 默认**文本模式**，会把 `\n` 写成 `\r\n`
⇒ bash `read -r id` 读回时每行**尾部带 `\r`** ⇒ 拼进命令后 id 非法 ⇒ 每次都失败。

**修法**：读侧 `id=$(printf '%s' "$id" | tr -d '\r\n')`；
或写侧用 `open(path, 'w', newline='')` / 二进制模式。

> **可复用纪律**：**跨 Linux 工具与 Windows Python 传"行式数据"时，先剥 `\r`。**
> 同族纪律（已有）：**别吞 stderr** —— 这一轮正因为它，"80 次失败"变成了"看起来成功"，
> 让我多做了一轮无用功。同类陷阱：`python 是 Windows 版、不认 /tmp 与 /d/... 路径`
> （用 Windows 路径或 `cd` 进目录再用相对路径）。

## 115. CI 红的定位方法，与 detekt `MatchingDeclarationName` 的两记坑（2026-09-22）

- **背景**：push `c97449a`（自动填充 P0 修复）后 CI 在 `Run detekt (quality gate)`
  一步失败，日志只给到 `Analysis failed with 1 issues`，不含具体违规。
- **坑一：沙箱取不到 CI 日志**（两层的 ~/.hosts 屏蔽 + 大文件传输失败，老毛病的第三层）
  1. `gh api .../logs` 与 curl 直连 **都失败**（`gh` 输出不到控制台直接原因）。
  2. 根因是 **Azure blob 域名被解析到假 IP**：`productionresultssa3.blob.core.windows.net`
     → `198.18.0.27`（与 github.com 的 `198.18.0.26` 同族）。
      ⇒ **DoH 解析真实 IP 再写 `/etc/hosts`**：`https://dns.alidns.com/resolve?name=<host>&type=A`，
     实测真值 `57.150.86.161`。
  3. ⚠️ **GitHub 的 `/logs` 端点不支持 Range**（返回 415），但 **Azure blob 直链支持**
     （`206`）。故正确顺序是：先拿 API 的 `Location` 重定向 URL（`-w "%{redirect_url}"`），
     **再**对 blob 直链做 **1MB 分块 + 重试**（同 #105 的手法）。
  4. 直链有效期很短（带 `se=` 过期参数，约 20 分钟）—— 拿到就立刻下，别先去干别的。
- **坑二：detekt `MatchingDeclarationName`（2.x 里叫 `Filename`）**
  - **规则**：文件里若**只有一个顶层类 / object 声明**，文件名必须与它同名。
  - 踩法：策略文件叫 `FillTargetResolver.kt`，里面唯一的顶层**类**却是
    `FillTargetNode`（其余是顶层函数与 `const val`，**不计入**）
    ⇒ `The file name 'FillTargetResolver' does not match the name of the single
    top-level declaration 'FillTargetNode'`。
  - **解法**：把那组顶层函数收进 `object FillTargetResolver`。这样既满足文件名匹配，
    又给策略带了命名空间（调用处 `FillTargetResolver.xxx` 比裸函数更好读）。
    ——**不要用 `@Suppress` 绕过**：这是一条能实际改善结构的门禁。
- **可用替代验证手段**（无 Android SDK 时也很实用）：
  detekt **2.0.0-alpha.6 未进 Maven Central**，但**默认规则绝大多数与 1.23.8 同构**。
  用 `detekt-cli-1.23.8-all.jar --build-upon-default-config` 对改动文件单独跑，
  **能复现 CI 那条违规**（实测行号与文案完全一致）。
  ⚠️ 但 1.23.8 **不认本仓库 detekt.yml 的属性名**（2.x 的 `allowedLines` vs 1.x 的
  `threshold`）→ 跑的时候**不要传项目配置**，只能拿默认规则做交叉验证。
- ⇒ **纪律**：本地无法跑完整 Gradle 时，push 前至少做「默认规则 detekt 交叉验证」；
  且 **push 后必须回来盯 CI**，不能假定通过。

## 116. 「假绿」的本地验证：只编译代码片段 = 没验证（2026-09-23）

- **现象**：`c97449a` 的自动填充修复本地「验证通过」（策略层单测 16/16 + 片段编译 OK），
  推上去 CI 却在 `:app:compileFullDebugKotlin` 炸：
  `AssistStructureParser.kt:167:30 Argument type mismatch: actual type is 'Int', but 'AutofillId' was expected`。
  而它在 `eded353` 修掉 detekt 违规后**才**暴露 —— 因为 detekt 步骤先失败、fail-fast 挡住了编译。
- **根因（两层）**
  1. **代码**：`ParsedField.id` 要的是 `AutofillId`，我写成了 `target.id`。
     `AssistStructure.ViewNode` 上确实有个 `getId(): Int`（`R.id.xxx` 那个视图 id），
     **和自动填充寻址毫无关系** —— 两个名字极易混。
  2. **验证失真（更严重）**：所谓「编译验证」是把手写的**函数片段**抽出来编译，
     **没包含真正出问题的 `traverse()` 消费点**；而 stub 的 `ViewNode` 里干脆没定义 `id`，
     连类型对照的能力都没有。**片段编译通过了 ≠ 调用处通过。**
- **修法**
  - 代码：`val targetId = target?.autofillId; if (target != null && targetId != null)`，
    顺便避开 `!!`。
  - 验证升级为**整个包的全量类型检查**（不再是片段）：
    `parser/*.kt` + `model/ParsedStructure.kt` + `fillassist/{Matcher,Rules}.kt`
    这套依赖闭包很干净（只依赖 Android 的几个类型），可拿 kotlinc 直接编。
  - **stub 必须保真**：补 `android.view.autofill.AutofillId`、`android.view.ViewStructure.HtmlInfo`、
    `android.util.Pair`、`android.content.ComponentName`、`android.text.InputType`，
    并**刻意保留 `ViewNode.id: Int`** —— 保真到能把同类坑再咬出来。
  - kotlinc 用法：`java -cp <编译器 jars> org.jetbrains.kotlin.cli.jvm.K2JVMCompiler <src> -cp <分析 classpath> -no-stdlib -jvm-target 17`。
    ⚠️ 两个 classpath 别混：运行编译器自己的 `-cp`（要含 `coroutines.jar`，否则
    `NoClassDefFoundError: kotlinx/coroutines/CoroutineScope`）与被分析的 `-cp`。
- **验证本身也要被验证（变异测试）**：把 `target.id` 改回去重编，
  **能逐字复现 CI 那行报错**（同在第 30 列）才证明这套 harness 有牙齿。
  ⇒ **纪律**：① stub 的类型签名必须与真实 API 一致；
  ② 编译范围必须包含**所有消费点**；③ 凡是新搭的验证手段，**先做一次变异测试**。
- **附：本次顺手修好的环境坑** —— 日志重定向的另一条真身是
  `results-receiver.actions.githubusercontent.com`（被解析到 `198.18.0.25`），
  同样要 DoH 解析后写 `/etc/hosts`（实测 `140.82.114.22`）；写入后
  `gh api .../logs` 的直链**一次就能下载成功**，不必再走 #115 的分块流程。

---

## 124. ⭐ 新增探针 `check_state_flattening.py`：抓「多态状态被压成二值开关」—— 现有手段全都不可见的一类 bug（2026-09-26）

- **背景**：见 #121。`Switch(checked = x is S.On)` 这种写法是**合法 Kotlin**、
  不是 `when`（穷尽性检查管不到）、detekt 也无对应规则 ⇒
  **类型检查 + detekt + 穷尽性检查三者都看不见它**。
  它只在真机上、且只有当状态恰好处在"被合并掉的那个分支"时才暴露。
- **探针判据（4 条，保守）**：
  1. 先全树扫出**分支数 ≥ 3** 的 sealed 类型名（< 3 的不管 —— 二态压二态是对的）；
  2. 找 `Switch/Checkbox/RadioButton/SettingsSwitch/ToggleButton` 的 `checked=` / `value=` 实参；
  3. 实参里含 `is <S>.On` / `== <S>.On` 且 `<S>` 在表里 ⇒ 报；
  4. 该实参处于显式 `when (<expr>)` 的分支体内 ⇒ **放行**。
- **★ 判据 4 是这个探针最重要的护栏**：没有它会把**全部正确写法**误报。
  错误写法与正确写法长得极像：
  ```
  错误：Switch(checked = state.biometric is CapabilityState.On, ...)     ← 该报
  正确：when (cap) { is CapabilityState.On -> Switch(checked = true,…) }  ← 不该报
  ```
  两者都有 `is CapabilityState.On` 和 `checked =`。⇒ 自测里
  `flattened_reports`（正例）与 `when_dispatch_ok`（负例）**必须成对存在**，
  只测一个等于没测。
- **★ 两次自纠（这是本条最该记住的部分）**：
  1. **只在同文件找 sealed 声明** ⇒ 扫真实 bug **报 0 处**。
     `CapabilityState` 定义在 `QuickUnlockController.kt`、误用在 `QuickUnlockDialogs.kt`。
     ⇒ **类型定义的位置与误用的位置是解耦的**，必须建全树表。
  2. **7/7 自测全绿，扫真实代码仍报 0 处**。根因：正则在
     `Outer.CapabilityState.On` 上把**倒数第二段**当类型名，
     而自测用例**全写短名** `Cap.On`，恰好匹配。
     ⇒ **自测只证明"探针能处理我编的简化输入"，不能证明它有用。**
     **自测用例必须包含生产代码的真实形态**（含全限定名）。
- **★ 有效性验证的唯一可靠方式**（与 #104 / #118 同一纪律，但这次更严）：
  **把目标文件还原成 bug 版本跑全树扫描，要求精确报出那几行；再恢复，要求 0 处。**
  只有这个**在真实仓库上的双向闭环**能证明探针有牙齿。
  ⚠️ 自测全绿**不算**证明 —— 本条的第二次自纠就是活反例。
- **同类教训（本文件已有记录，此处再次印证）**：探针报了不可能的问题 = 探针坏了；
  这里更进一步：**探针什么都不报也可能是坏了**（正是本条两次自纠的形态）。

## 125. ✅ `check_import_packages.py` 把 `Icons.Filled.Build` 误判成 `android.os.Build`（2026-09-26 发现；**已修** `a3d05b2`：统计口径与检查口径对齐）

- **现象**：`SettingsScreen.kt:32` 的
  `import androidx.compose.material.icons.filled.Build` 被报为
  「`Build` 导入自 `…icons.filled`，但本仓库约定为 `android.os`」。
- **这是误报**：`Icons.Filled.Build` 是**合法的 Material 图标**（扳手），
  与 `android.os.Build` 毫无关系 —— 只是**短名撞车**。
- **影响面**：
  - 该文件**在 HEAD 上就如此**，非某轮改动引入；
  - 但它会让 `.ai/tools/tests/selftest_import_packages.py` 的
    **步骤 1（"先跑一次要求 OK"）直接失败** —— 即**门禁自己的自测是坏的**。
    跑该自测会看到 `[FAIL] 初始状态就不是 OK —— 仓库里本来就有可疑 import，先修它`。
- **为什么没在本轮修**：修它要动探针的"多数派"判定或豁免表，
  而豁免表已有 `lerp` / `LocalLifecycleOwner` 两例（同理的跨包同名）。
  这是**独立于设置页的一件事**，与 #121/#124 混在一起会看不清是哪件改坏了。
  ⇒ 单列本条，留待专门一轮处理。
- **修的时候注意**：不要简单地往豁免表加 `Build` ——
  那会让**真正的** `android.os.Build` 漏检。正确方向是让判定**带上包路径上下文**
  （`Icons.Filled.Build` 里的 `Build` 前面是 `.filled.`，与该符号的约定包根本不同源），
  或对 `material.icons.*` 整条路径整体豁免。

### 125.1 ✅ **实际修法比上面建议的更根本**（`a3d05b2`，2026-09-28）

没有走"豁免表"或"包路径上下文"，而是**抽出 `rule_a_in_scope()` 让统计与检查共用一条口径**：

```python
def rule_a_in_scope(import_path: str) -> bool:
    """规则 A 的作用域：只对 androidx.* / io.vaultix.* 生效。"""
    return import_path.startswith("androidx.") or import_path.startswith("io.vaultix.")
```

- **根因**：`build_exclusive_map`（统计"多数派"）把所有包都数进去，
  而 `check_file` 只对 `androidx.*` / `io.vaultix.*` 报错 ⇒
  **两条口径不同**。于是 `android.os.Build`（全文数十处、**却永不参与检查**）
  成了 `Build` 的"约定包"，反过来把**合法的** `androidx...icons.filled.Build` 判成偏离约定。
- **⇒ 可迁移的判据**：**从没参与过检查的东西，不该有权定义"约定"。**
  （凡"先从数据推规则、再拿规则去检查数据"的工具都适用 —— 推导集的边界
  必须 ≤ 检查集的边界，否则会出现"规则来自一个不被规则管辖的宇宙"。）
- **为什么比原建议好**：豁免表和包路径上下文都是**打补丁**（下一个撞名符号再来一次）；
  对齐口径是**消根因**（任何平台包都不会再成为约定包），且想改口径只能改一处。
- **验证**：探针 `check_import_packages.py` → `[OK] 检查了 395 个文件，import 包路径未见异常`；
  其自测 3 步（初始 OK / 写错包被抓 / 还原后 OK）**全 PASS** ——
  而此前步骤 ①「初始状态 OK」是**失败的**（那正是本条的现象）。

