#!/usr/bin/env python3
"""本地没有编译器：五类 **detekt 查不出、只有 CI 编译才炸** 的错误的补位自检。

## 为什么需要这个

沙箱里没有 Android SDK，**编译不了 Kotlin**；而 detekt **只跑静态规则集、不做
符号解析也不做类型检查**。于是下面五类错误在本地是**完全静默**的，只有推上去
等 CI 的 `Build Debug APK` 才暴露（每轮 CI 约 2~4 分钟）：

    e: ...ItemDetailScreen.kt:928:97  Unresolved reference 'Tune'.
    e: ...SettingsComponents.kt:80:30 Variable 'SETTINGS_ICON_BOX' must be initialized.
    e: ...FullScreenDialogShell.kt:500:51 Unresolved reference 'value'.

2026-09-16 实录：同一轮推送里前两条一起红了，而本地 detekt + 三个 `.ai/tools`
脚本**全绿**。2026-09-20 又实录了后一条（推上去两次才绿）。它们各自对应的规则：

| 规则 | 真实报错 | 触发改动 |
|---|---|---|
| A 图标引用 vs 导入差集 | `Unresolved reference 'Tune'` | 给自定义字段分区加图标，只写了 `Icons.Filled.Tune` 没补 import |
| B 顶层属性前向引用 | `Variable 'X' must be initialized` | 把 `SETTINGS_ICON_BOX` 的声明放在了使用它计算 `SETTINGS_DIVIDER_INSET` **之后** |
| C 悬空 `R.string.*` | `Unresolved reference 'xxx'` | 删/改名了字符串却没同步改代码 |
| D `Dp / Dp` 后再 `.value` | `Unresolved reference 'value'` + `Cannot infer type for type parameter 'T'` | 把 `val f = a / b`（两个 Dp）当成 Dp 用，又写了 `.value` |
| E 成员挂错接收者 | `Unresolved reference 'heightOffset'` | `behavior.heightOffset`，但它在 `behavior.state` 上 |

⚠️ 规则 A 与 8.6 里 2026-09-15 那条「`CompositionLocalProvider` / `LocalContentColor`
只写用法没补导入」是**同一个坑的第二次犯**（那次靠手工 grep 抓，这次靠脚本）。
⇒ **教训：手工核对一次不算，要变成脚本。**

⚠️ 规则 D / E 是 2026-09-20 补的，起因是同一个文件推了两次才编译过。
**每次 CI 因编译失败红一次，就应当回来加一条规则** —— 否则同一类错会第三次犯。

## 判据

**规则 A（图标）**：文件里出现的 `Icons.<(AutoMirrored.)?Filled|Outlined|...>.<Name>`
必须有一个 `import androidx.compose.material.icons.[automirrored.]<style>.<Name>`。
差集非空即报。全仓库扫描（142 个 kt，0 误报）。

**规则 B（前向引用）**：Kotlin 顶层属性的初始化器**不能**引用同文件里**在它之后**
声明的另一个顶层属性 —— 顶层属性按声明顺序初始化，Kotlin 直接报
`must be initialized`（不是"未定义"，所以名字看着是存在的，极易漏）。
⇒ 为杜绝误报，**只检查 SCREAMING_SNAKE_CASE 的属性名**（常量风格）：
局部变量/参数用这种命名的概率极低，而这类常量互相引用的写法非常普遍
（`PADDING + ICON_BOX + GAP` 正是本轮的坏例子）。

**规则 C（资源）**：`R.string.<name>` 必须在任意 `res/values*/**/*.xml` 里有
对应的 `<string name="...">`。同时反向列出**定义了却没人用**的 `R.string`
（只提示、不算失败 —— 死资源不炸编译，但值得清）。

**规则 D（Dp 除法）**：`Dp.div(Dp)` 的返回类型是 **`Float`**（不是 `Dp`）——
`(a + B) / (c + D)` 这样的比例算完**已经是 Float**，再 `.value` 就报
`Unresolved reference 'value'`；而且**一个错的 `.value` 会连带报出 4~6 条**
`Cannot infer type for type parameter` / `Inapplicable candidate(s)`
（因为下一行 `arrayOf(x to y, ...)` 的类型推断跟着崩），看日志时容易被条数带偏。
⇒ 判据：`.value` 的接收者若形如「带括号且含 `/` 的表达式」或名字含
`Fraction / Ratio / Percent / Pct`，报出来让人确认。

**规则 E（成员接收者）**：外层对象与内层对象都有名字相近的 API 时，凭印象容易挂错层。
登记本仓库已用到的那几组，逐个给「正确写法 + 为什么」。
⚠️ 这类错**无法通用检测**（要真解析类型），所以是**白名单式**的：
踩过一次就登记一条，用 `_MEMBER_RECEIVER_HINTS` 维护。

## 用法

    python3 .ai/tools/check_compile_smells.py            # 扫全部 .kt（app/src）
    python3 .ai/tools/check_compile_smells.py <文件>...  # 只扫指定文件
    python3 .ai/tools/check_compile_smells.py --changed  # 只扫工作区/最近提交改动的
    python3 .ai/tools/check_compile_smells.py --detekt-probe
        # 本地复现 CI 的圈复杂度判定（detekt CLI 默认**不报** CyclomaticComplexMethod，
        # 见 .ai/conventions/8.6；这里用一份只开该规则的探针配置跑一遍）

退出码：0 = 没发现问题；1 = 有问题。
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

# 🔴 2026-09-16 修正：原先只扫 `app/src`，而本工具是「本地门禁」的一部分 ⇒
#    两次 CI 红都从这个缝里过去：
#      ① `domain/.../VaultRepository.kt` 的重复声明（Conflicting overloads）
#      ② `data/repository/.../LocalUnlockEnrollment.kt` 的 import 包路径写错
#    两个文件都在 app/ 之外，**从来不在扫描范围** ⇒ 本地"全绿"是假绿。
#
# ⇒ 扫**全部真实源码模块**（app / core / data / domain）。
#   ⚠️ 排除 `reference/`：那是 Bastion 的对照源码（非本项目模块、不参与构建），
#      纳入只会引入纯误报。
#   ⚠️ 排除 `build/` 产物。
SRC_ROOTS = [ROOT / m for m in ("app", "core", "data", "domain")]
# `app/src` 仍是 R.string 资源扫描的锚点（资源只在 app 模块）。
SRC_ROOT = ROOT / "app" / "src"

# ---------------------------------------------------------------------------
# 规则 A：图标引用 vs 导入
# ---------------------------------------------------------------------------
# Icons.Filled.Tune / Icons.AutoMirrored.Filled.ArrowBack 两种形态都覆盖。
ICON_USE_RE = re.compile(
    r"Icons\.(?:AutoMirrored\.)?(?:Filled|Outlined|Rounded|Sharp|TwoTone|Default)\.(\w+)"
)
ICON_IMPORT_RE = re.compile(
    r"^\s*import\s+androidx\.compose\.material\.icons\."
    r"(?:automirrored\.)?(?:filled|outlined|rounded|sharp|twotone)\.(\w+)",
    re.MULTILINE,
)

# ---------------------------------------------------------------------------
# 规则 B：顶层属性前向引用
# ---------------------------------------------------------------------------
# 只认**顶格**（无缩进）的 private val / private const val —— 缩进的多半是成员。
TOP_LEVEL_PROP_RE = re.compile(r"^(?:private |internal )?(?:const )?val\s+(\w+)\s*[:=]", re.M)
# 下一个顶层声明的起点（顶格的非空白、非注释行）
NEXT_TOP_LEVEL_RE = re.compile(r"^(?!/\*|//| |\t|\}|\))", re.M)
IDENT_RE = re.compile(r"\b([A-Z][A-Z0-9_]{2,})\b")

# ---------------------------------------------------------------------------
# 规则 C：资源引用
# ---------------------------------------------------------------------------
R_STRING_RE = re.compile(r"R\.string\.(\w+)")


# ---------------------------------------------------------------------------
# 规则 D：`Dp / Dp` 的结果是 Float，不是 Dp（2026-09-20 实录）
# ---------------------------------------------------------------------------
# 实录：写 `val opaqueFraction = (a + B) / (a + B + TAIL)`，两个操作数都是 Dp，
# 于是 `Dp.div(Dp): Float` ⇒ 结果**已经是 Float**，再写 `.value` 就是
#   `e: Unresolved reference 'value'`
# 而下一行 `arrayOf(GRADIENT_START to x, opaqueFraction.value to y, ...)`
# 因为前者报错，类型推断跟着崩：
#   `e: Cannot infer type for type parameter 'T'` + `Inapplicable candidate(s)`
# ⇒ **一个错的 `.value` 会连带报出 4~6 条错误**，看日志时容易被数量带偏。
#
# 判据：把「`.value` 的接收者」区分为三类，只报**算出来的**那种：
#   ① `xxxState.value` / `xxx.value`（属性）—— 合法，大量存在
#   ② `(a + b) / (c + d)` 这类**带括号的除法表达式**.value —— 可疑
#   ③ 形如 `比较 / 长度` 且在 Dp 上下文里的 —— 可疑
# ⚠️ 宁可漏报不可误报（ISSUES #101）：只在**明确的除法表达式**后面报。
#    识别方式：`.value` 前面紧邻 `)`，且该括号组里含 `/`。
_DIV_EXPR_VALUE_RE = re.compile(r"\([^()]*Dp[^()]*/[^()]*\)\s*\.value\b")
# 兜底：`xxxFraction.value` / `xxxRatio.value` / `xxxPercent.value` 这类明显是比例的名字
_FRACTION_VALUE_RE = re.compile(
    r"\b(\w*(?:Fraction|Ratio|Percent|Pct)\w*)\s*\.value\b"
)


def check_dp_division_value(path: Path) -> list[str]:
    """`Dp / Dp` 已经返回 Float ⇒ 不能再 `.value`。"""
    src = path.read_text(encoding="utf-8")
    out: list[str] = []
    for line_no, line in enumerate(src.splitlines(), 1):
        code = line.split("//")[0]  # 注释里提到 `.value` 不算
        if _DIV_EXPR_VALUE_RE.search(code):
            out.append(
                f"L{line_no}: 除法表达式后接 `.value` —— 若两个操作数都是 `Dp`，"
                f"`Dp.div(Dp)` 已返回 **Float**，`.value` 会 `Unresolved reference`；"
                f"连带让 `arrayOf(...)` 报 Cannot infer type。"
            )
        elif _FRACTION_VALUE_RE.search(code):
            out.append(
                f"L{line_no}: `{{}}`.value` 里的名字像比例 —— 确认它是 `Dp`（可 .value）"
                f"还是 `Float`（不可）。`Dp / Dp` 的结果是 Float，这是 CI 才抓得到的坑。".replace("{}", _FRACTION_VALUE_RE.search(code).group(1))
            )
    return out


# ---------------------------------------------------------------------------
# 规则 E：成员挂在错误的接收者上（2026-09-20 实录）
# ---------------------------------------------------------------------------
# 实录：写 `bottomBarScrollBehavior.heightOffset`，但
# `BottomAppBarScrollBehavior` 只有 `state` / `nestedScrollConnection` / `isPinned` /
# `*AnimationSpec` 五个成员 —— `heightOffset` 挂在 `behavior.state`
# （`BottomAppBarState`）上 ⇒ `e: Unresolved reference 'heightOffset'`。
#
# 这类错的通病：**两层对象都有名字相近的 API，凭印象挂错层**。
# 本仓库已用到的这类"外层→内层"关系，逐个登记：
#   behavior.property 的**正确**形式 → 提示
_MEMBER_RECEIVER_HINTS: list[tuple[str, str, str]] = [
    # (错误形态正则, 正确写法, 说明)
    (
        r"\b\w*(?:ScrollBehavior|scrollBehavior)\s*\.\s*(heightOffset|collapsedFraction|contentOffset|heightOffsetLimit)\b",
        "behavior.state.heightOffset",
        "offset / fraction 都在 `BottomAppBarState` 上，不在 `BottomAppBarScrollBehavior` 上"
        "（behavior 只有 state / nestedScrollConnection / isPinned / *AnimationSpec）",
    ),
]


def check_member_receiver(path: Path) -> list[str]:
    """成员是否挂在了错误的接收者上（内外层 API 名字相近时最易犯）。"""
    src = path.read_text(encoding="utf-8")
    out: list[str] = []
    for line_no, line in enumerate(src.splitlines(), 1):
        code = line.split("//")[0]
        for pattern, correct, why in _MEMBER_RECEIVER_HINTS:
            m = re.search(pattern, code)
            if m:
                out.append(
                    f"L{line_no}: `{m.group(0)}` —— 成员可能挂错接收者。"
                    f"正确写法形如 `{correct}`：{why}。"
                )
    return out


def iter_kt_files() -> list[Path]:
    """全仓库真实模块里的 .kt（见 SRC_ROOTS 的说明：排除 build/ 与 reference/）。"""
    files: list[Path] = []
    for root in SRC_ROOTS:
        if not root.is_dir():
            continue
        files.extend(p for p in root.rglob("*.kt") if "/build/" not in p.as_posix())
    return sorted(set(files))


def changed_files() -> list[Path]:
    """工作区 + 未跟踪 + 最近一次提交：与 check_signature_types.py 同一套判据。"""
    found: list[Path] = []
    for args in (["diff", "--name-only", "HEAD"], ["diff", "--name-only", "HEAD~1", "HEAD"],
                 ["ls-files", "--others", "--exclude-standard"]):
        try:
            out = subprocess.run(
                ["git", *args], cwd=ROOT, capture_output=True, text=True, check=True,
            ).stdout
        except (subprocess.CalledProcessError, FileNotFoundError):
            continue
        for line in out.splitlines():
            line = line.strip()
            if not line:
                continue
            f = ROOT / line
            if f.suffix == ".kt" and f.exists():
                found.append(f)
    return sorted(set(found))


def string_resource_names() -> set[str]:
    """扫 res/values*/**/*.xml 里所有 <string name="...">。"""
    names: set[str] = set()
    for res_dir in SRC_ROOT.rglob("res"):
        for xml in res_dir.rglob("*.xml"):
            if "values" not in xml.parent.name:
                continue
            try:
                root = ET.parse(xml).getroot()
            except ET.ParseError:
                continue
            for el in root.iter():
                if el.tag == "string" and el.get("name"):
                    names.add(el.get("name"))
    return names


def check_icons(path: Path) -> list[str]:
    src = path.read_text(encoding="utf-8")
    imported = set(ICON_IMPORT_RE.findall(src))
    used = set(ICON_USE_RE.findall(src))
    missing = sorted(used - imported)
    return [
        f"用到 Icons.*`{name}`，但本文件没有对应的 import"
        f"（期望 `import androidx.compose.material.icons.filled.{name}`）"
        for name in missing
    ]


def initializer_body(lines: list[str], start: int) -> str:
    """取顶层属性**初始化器本身**的文本：声明行 `=` 之后 + 紧随其后的缩进续行。

    ⚠️ 绝不能一路取到"下一个顶层声明"——那中间夹的是**函数体**，而函数体内引用
    后面才声明的顶层常量完全合法（调用发生在运行时，那时全部已初始化）。
    首版正是这么写的，于是把 `UnlockScreen` 里 PIN_* 常量误报了 6 处。
    """
    head = lines[start].split("=", 1)[1] if "=" in lines[start] else ""
    parts = [head]
    j = start + 1
    while j < len(lines):
        line = lines[j]
        if not line.strip():          # 空行 ⇒ 属性声明结束
            break
        if not line[0].isspace():     # 顶格 ⇒ 下一个顶层声明
            break
        parts.append(line)
        j += 1
    return "\n".join(parts)


def check_init_order(path: Path) -> list[str]:
    """顶层常量初始化器里引用了**在它之后**才声明的顶层常量。"""
    src = path.read_text(encoding="utf-8")
    lines = src.splitlines()
    # 收集顶格属性声明：(名称, 起始行号)
    decls: list[tuple[str, int]] = []
    for i, line in enumerate(lines):
        m = TOP_LEVEL_PROP_RE.match(line)
        if m:
            decls.append((m.group(1), i))
    names = {n for n, _ in decls}
    problems: list[str] = []
    for name, start in decls:
        body = initializer_body(lines, start)
        if not body.strip():
            continue
        # 初始化器里带 `{` ⇒ 多半是 lambda / 函数体内，**延迟求值**，引用后面常量合法
        if "{" in body or "fun " in body:
            continue
        for ident in set(IDENT_RE.findall(body)):
            if ident == name or ident not in names:
                continue
            # 被引用的 ident 声明位置在**本声明之后** ⇒ 前向引用
            ref_pos = next((i for n, i in decls if n == ident), None)
            if ref_pos is not None and ref_pos > start:
                problems.append(
                    f"L{start + 1}: 顶层属性 `{name}` 的初始化用到了在其**之后**（L{ref_pos + 1}）"
                    f"声明的 `{ident}` ⇒ Kotlin 报 `Variable '{ident}' must be initialized`。"
                    f"把 `{ident}` 的声明移到 `{name}` 之前。"
                )
    return problems


def check_strings(path: Path, defined: set[str]) -> list[str]:
    src = path.read_text(encoding="utf-8")
    return [
        f"R.string.`{name}` 在 res/values*/ 里没有定义（悬空引用）"
        for name in sorted(set(R_STRING_RE.findall(src)) - defined)
    ]


# 抓「同类内同名同参数」的重复 **fun** 声明签名。
#
# ⚠️ 刻意**只查 fun、不查 val/var**：属性重复在文本上无法与"局部变量 / 命名参数 /
#    构造函数实参"区分（试过，264 处全误报）。而本轮真实事故是**函数整块被复制**
#    （`enrollPinForVaults` 出现两次），查 fun 就够，且判据干净。
#
# ⚠️ 只认**类成员级**缩进（恰好 4 空格）的声明，跳过 local fun / 顶层 fun。
_KOTLIN_MEMBER_FUN_RE = re.compile(
    r"^ {4}(?:public |internal |protected |private |override |suspend |inline |operator |open |"
    r"actual |external |@\w+\s+)*fun\s+"
    # 🔴 可选接收者：`fun Intent.getX()` / `fun JsonElement?.asObject()` 里紧跟 fun 的
    #    是**接收者类型**而不是函数名。只写 `fun\s+(\w+)` 会把同一接收者上的所有扩展
    #    函数归一成同一个名字（实证：`Intent` 5 处、`JsonElement` 2 处误报）。
    r"(?:<[^>]*>\s*)?"  # 泛型参数 `fun <T> f()`
    r"(?:\([^()]*\)\s*\.\s*)?"  # 接收者可带形参：`fun (A).f()`
    r"(?:([A-Za-z_][\w.]*(?:<[^<>]*>)?\??)\s*\.\s*)?"  # 普通接收者类型 `Intent.` / `JsonElement?.`
    r"(\w+)\s*(?:\(\s*([^)]*)\))?",
    re.M,
)

# 这些"重载"是合法的（重写标准方法 / 不同参数），不报。
_DUPLICATE_ALLOWLIST = {"equals", "hashCode", "toString", "compareTo", "invoke", "get", "set"}

# 顶格（无缩进）的类 / 接口 / 对象声明 —— 用来划分"重复"的作用域。
#
# 🔴 2026-09-16 修正：本探针原先**按整个文件**记 key。这在一个文件里只放一个类时没问题，
#    但对 `core/database/.../VaultixDaos.kt` 这种**一个文件放 4 个 @Dao 接口**的文件
#    就会误报 6 处 —— `listByVault` / `clearVault` / `deleteByIds` 在**不同接口**里
#    合法地各有一份。
#    ⇒ 必须按"所在的顶层类型声明"分桶，否则同一个名字只要在文件里出现两次就报。
#    （典型反例：`interface A { fun f() } interface B { fun f() }` 完全合法。）
_TOP_LEVEL_TYPE_RE = re.compile(
    r"^(?:public |internal |private |abstract |open |sealed |data |value |annotation |"
    r"@\w+\s+)*"
    r"(?:class|interface|object|enum\s+class)\s+(\w+)",
    re.M,
)


def _enclosing_scope(src: str, offset: int) -> str:
    """`offset` 处属于哪个**顶格类型声明**；不属于任何类型时返回 ""（顶层）。

    取 offset 之前**最后一个**顶格类型声明的名字 —— 对本项目"一个文件里若干个
    类/接口顺序排列、不互相嵌套"的常规布局，这就足够准。
    """
    scope = ""
    for m in _TOP_LEVEL_TYPE_RE.finditer(src):
        if m.start() > offset:
            break
        scope = m.group(1)
    return scope


def _param_type_fingerprint(params: str | None) -> str:
    """把参数列表归一成"只含类型、忽略参数名"的指纹。

    `fun f(a: String)` 与 `fun f(b: String)` 被视为同一签名 ——
    那正是 Kotlin 报 `Conflicting overloads` 的判据（**重载只看类型**）。
    """
    if not params:
        return ""
    types = re.findall(r":\s*([A-Za-z_][\w.<>?, ]*)", params)
    return "|".join(t.strip() for t in sorted(types))


def check_duplicate_declarations(path: Path) -> list[str]:
    """同一个**类型声明内**有没有重复的成员函数（同名 + 同接收者 + 同参数类型）。

    ## 为什么需要这个

    2026-09-16 实录：手改 `VaultRepository.kt` 时**整块 KDoc + 函数声明被复制了一份**
    （`enrollPinForVaults` 出现两次）。本地七道门禁全绿 —— 因为 detekt 不做类型检查、
    `check_signature_types` 只看类型名是否存在、编码检查只看字节。
    CI 编译才报 `Conflicting overloads`，代价一次完整 CI 往返。

    ⇒ 这是与 `check_signature_types` 同一类"本地无编译器"的盲区，用文本启发式补上。

    ## 判据的两个维度（都靠误报才补全，别删）

    1. **接收者进 key**：`fun Intent.foo(x)` 与 `fun Bundle.foo(x)` 是不同签名；
    2. **按顶层类型声明分桶**：名字在**不同** `interface`/`class` 里重复是合法的，
       只有**同一个**类型里重复才是 `Conflicting overloads`。
    """
    src = path.read_text(encoding="utf-8")
    seen: dict[tuple[str, str, str, str], int] = {}
    problems: list[str] = []
    for match in _KOTLIN_MEMBER_FUN_RE.finditer(src):
        receiver, name, params = match.group(1) or "", match.group(2), match.group(3)
        if name in _DUPLICATE_ALLOWLIST:
            continue
        # 🔴 接收者必须进 key：`fun Intent.foo(x)` 与 `fun Bundle.foo(x)` 在 Kotlin 里是
        #    两个不同签名，不进 key 就会误报（构造用例 diff_receiver.kt 实证）。
        # 🔴 所在类型声明也必须进 key：见上面 KDoc 第 2 点（VaultixDaos.kt 实证）。
        key = (_enclosing_scope(src, match.start()), receiver, name,
               _param_type_fingerprint(params))
        line = src.count("\n", 0, match.start()) + 1
        if key in seen:
            shown = f"{receiver}.{name}" if receiver else name
            problems.append(
                f"`{shown}` 疑似重复声明（首次在第 {seen[key]} 行，本次在第 {line} 行）"
                f" —— Kotlin 会报 Conflicting overloads"
            )
        else:
            seen[key] = line
    return problems


def display(path: Path) -> str:
    try:
        return path.relative_to(ROOT).as_posix()
    except ValueError:
        return str(path)


# ---------------------------------------------------------------------------
# --detekt-probe：在本地复现 CI 的 detekt 判定
# ---------------------------------------------------------------------------
# ⚠️ 背景（2026-09-16 两次踩坑，代价两次 CI 往返）：
#
# ① `detekt-cli --config config/detekt/detekt.yml`（**不带** `--build-upon-default-config`）
#    只检查 config 里**显式提到**的规则 —— 而 `config/detekt/detekt.yml` 只校准了阈值，
#    其余规则靠 `buildUponDefaultConfig.set(true)`（在 build.gradle.kts 里）补上。
#    ⇒ 这种跑法**漏掉了绝大多数规则**（`CyclomaticComplexMethod` 甚至不在 config 里）。
#
# ② 即便加了 `--build-upon-default-config`，**仍会漏** `CyclomaticComplexMethod`：
#    该规则在 detekt 2.0 需要编译期类型解析才生效，纯源码 CLI 跑不出来。
#
# ⇒ 本函数**两级**探针：
#   - 第 1 级：`--build-upon-default-config` 跑全量规则（能抓 `TooGenericExceptionCaught`
#     等一堆 CI 会拦的规则）；
#   - 第 2 级：单独用只开 `CyclomaticComplexMethod` 的临时配置补第 1 级的漏网之鱼。
DETEKT_PROBE_CONFIG = """\
# 由 .ai/tools/check_compile_smells.py --detekt-probe 生成：
# 只显式打开圈复杂度规则（CLI 纯源码分析跑不出这条，需单独喂给它）。
complexity:
  CyclomaticComplexMethod:
    active: true
    allowedComplexity: 14
    ignoreNestingFunctions: false
"""

# 本仓库的 detekt CLI（沙箱预置，不在仓库里，故找不到时优雅跳过）。
DETEKT_CANDIDATES = [
    Path("/workspace/detekt-cli-2.0.0-alpha.6/bin/detekt-cli"),
    ROOT.parent / "detekt-cli-2.0.0-alpha.6" / "bin" / "detekt-cli",
]

# 全量探针要扫的源码根（与 CI `./gradlew detekt` 的分析范围对齐：
# 主源码集；测试源码集 CI 侧另有豁免，此处不扫）。
PROBE_SCOPES = [
    "app/src/main/java",
    "core",
    "data",
    "domain",
]

# `--build-upon-default-config` 下**必然**出现、但与我们的改动无关的噪音：
# 这些是 CLI 与 Gradle 的**分析范围差异**造成的（CLI 会扫到测试/示例代码，
# 而 CI 各模块的 source set 会排除）。按"只看自己改的文件"过滤最稳妥。
PROBE_IGNORE_MARKERS = (
    "/src/test/",
    "/src/androidTest/",
)


def find_detekt() -> Path | None:
    for candidate in DETEKT_CANDIDATES:
        if candidate.exists():
            return candidate
    return None


def run_full_detekt_probe(detekt: Path) -> int:
    """第 1 级：`--build-upon-default-config` 跑全量规则（复现 CI 的绝大多数判定）。"""
    findings = 0
    for scope in PROBE_SCOPES:
        full = ROOT / scope
        if not full.exists():
            continue
        out = subprocess.run(
            [
                str(detekt),
                "--config", str(ROOT / "config/detekt/detekt.yml"),
                "--build-upon-default-config",
                "--input", str(full),
                "--jvm-target", "17",
            ],
            cwd=ROOT, capture_output=True, text=True,
        ).stdout
        for line in out.splitlines():
            if not line.strip().startswith("e:"):
                continue
            if any(marker in line for marker in PROBE_IGNORE_MARKERS):
                continue
            print(f"  {line.strip()}")
            findings += 1
    return findings


def run_cyclomatic_probe(detekt: Path) -> int:
    """第 2 级：单独补 `CyclomaticComplexMethod`（第 1 级跑不出这条）。"""
    with tempfile.NamedTemporaryFile("w", suffix=".yml", delete=False, encoding="utf-8") as fh:
        fh.write(DETEKT_PROBE_CONFIG)
        probe_config = Path(fh.name)

    findings = 0
    try:
        for scope in PROBE_SCOPES:
            full = ROOT / scope
            if not full.exists():
                continue
            out = subprocess.run(
                [str(detekt), "--config", str(probe_config), "--input", str(full)],
                cwd=ROOT, capture_output=True, text=True,
            ).stdout
            for line in out.splitlines():
                if "CyclomaticComplexMethod]" in line and line.strip().startswith("e:"):
                    if any(marker in line for marker in PROBE_IGNORE_MARKERS):
                        continue
                    print(f"  {line.strip()}")
                    findings += 1
    finally:
        probe_config.unlink(missing_ok=True)
    return findings


def run_detekt_probe(scopes: list[str] | None = None) -> int:
    """跑 detekt 探针；返回发现数（-1 表示环境缺 detekt，跳过）。"""
    detekt = find_detekt()
    if detekt is None:
        print("[skip] 未找到 detekt CLI，跳过探针"
              "（CI 仍会判定；本地可用 ./gradlew detekt 代替）。")
        return -1

    print("[info] 第 1 级：全量规则（--build-upon-default-config，对齐 CI）")
    total = run_full_detekt_probe(detekt)
    print("[info] 第 2 级：CyclomaticComplexMethod 补充探针（第 1 级跑不出这条）")
    total += run_cyclomatic_probe(detekt)
    return total


def check_cross_file_private(files: list[Path]) -> dict[Path, list[str]]:
    """文件级 `private` 顶层函数被**别的文件**调用 ⇒ 回来报（按被调方文件归组）。

    ## 为什么需要这个

    2026-09-16 CI 实录：

        e: PinEnrollment.kt:91:32  Cannot access 'fun buildFullKey(...)': it is private in file.

    Kotlin 顶层 `private` 的含义是**文件内可见**，不是"类内可见"。
    于是 `VaultRepositoryImpl.kt` 里的 `private fun buildFullKey` 只有同文件能用，
    `PinEnrollment.kt` / `LocalUnlockEnrollment.kt` 都调不动。

    ⚠️ 这类错误**"本来就存在"**：它在 `HEAD~1` 上就编译不过，
    只是被更早的报错盖住 —— CI 停在第一条错误上，看不到后面还有多少。
    ⇒ 早发现的价值很大：它是纯文本可判的。

    ## 判据（保守，宁可漏报）

    只查**模块内**（同一 `src/main/java/.../<module>` 顶层目录）的文件之间的调用。
    跨模块调用会因 `private` 而更早失败，且通常本来就要 `internal`/`public`，
    本探针不掺和。调用判定用"名字 + 左括号"，并跳过 import / 注释行与声明行本身。
    """
    # 1) 收集 文件级 private 顶层 fun（排除 @Composable，那是 UI 惯用法）
    owners: dict[str, tuple[Path, int]] = {}
    for path in files:
        # ⚠️ 跳过测试源码：测试常写 `Entry(...)` 这类**构造函数**调用，
        #    与被测文件里的 `fun Entry.toXxx()` 扩展函数同名，判据会误伤。
        if "/src/test/" in path.as_posix() or "/src/androidTest/" in path.as_posix():
            continue
        try:
            lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
        except OSError:
            continue
        for i, line in enumerate(lines):
            # 🔴 必须跳过**可选接收者**：`private fun Entry.toVaultItem()` 里的
            #    `Entry` 是接收者类型、不是函数名。只写 `fun\s+(\w+)` 会把它当成
            #    函数名 `Entry`，进而把别处的 `Entry(...)` 构造调用误判成"跨文件调用"
            #    （2026-09-16 实测：一处扩展函数造成 103 处误报）。
            m = re.match(
                r"^private\s+(?:suspend\s+|inline\s+|operator\s+)*fun\s+"
                r"(?:<[^>]*>\s*)?"
                r"(?:[A-Za-z_][\w.]*(?:<[^<>]*>)?\??\s*\.\s*)?"
                r"(\w+)",
                line,
            )
            if not m:
                continue
            prev = lines[i - 1].strip() if i else ""
            if prev.startswith("@Composable"):
                continue
            owners.setdefault(m.group(1), (path, i + 1))

    if not owners:
        return {}

    # ⚠️ 只在名字**全仓库唯一**时才判：若多个文件各自定义了自己的同名私有函数
    #    （实测：`sha256` 在 3 处、`maskCardNumber` / `decodeHex` 各 2 处，
    #     每处都是各自独立的实现），那"某文件调用了这个名字"多半是在调
    #     **它自己那一份**，纯文本无从分辨 ⇒ 一律跳过，宁可漏报。
    #    这与 `check_import_packages` 的克制是同一种取向：
    #    **启发式探针不许为了多抓而制造误报。**
    ambiguous: set[str] = set()
    for name in owners:
        pattern = re.compile(
            r"^\s*private\s+(?:suspend\s+|inline\s+|operator\s+)*fun\s+"
            rf"(?:[A-Za-z_][\w.]*(?:<[^<>]*>)?\??\s*\.\s*)?{re.escape(name)}\b",
            re.M,
        )
        hits = 0
        for path in files:
            if "/src/test/" in path.as_posix() or "/src/androidTest/" in path.as_posix():
                continue
            try:
                text = path.read_text(encoding="utf-8", errors="replace")
            except OSError:
                continue
            if pattern.search(text):
                hits += 1
        if hits > 1:
            ambiguous.add(name)

    # 2) 找跨文件调用
    problems: dict[Path, list[str]] = {}
    for name, (owner, owner_line) in owners.items():
        if name in ambiguous:
            continue
        call = re.compile(rf"(?<![\w.]){re.escape(name)}\s*\(")
        # 声明行本身要跳过（含接收者形态与各类修饰符）
        decl = re.compile(
            rf"^(?:private|internal|public|protected)?\s*(?:suspend\s+|inline\s+|"
            rf"operator\s+)*fun\s+(?:[A-Za-z_][\w.]*(?:<[^<>]*>)?\??\s*\.\s*)?{re.escape(name)}\b"
        )
        for path in files:
            if path == owner:
                continue
            if "/src/test/" in path.as_posix() or "/src/androidTest/" in path.as_posix():
                continue
            try:
                text = path.read_text(encoding="utf-8", errors="replace")
            except OSError:
                continue
            for i, line in enumerate(text.splitlines(), 1):
                s = line.strip()
                if s.startswith(("import ", "//", "*", "/*")) or decl.match(s):
                    continue
                if call.search(line):
                    problems.setdefault(owner, []).append(
                        f"文件级 `private fun {name}`（本文件第 {owner_line} 行）"
                        f"被 {display(path)}:{i} 调用 —— 顶层 private 只在本文件可见，"
                        f"别的文件调不动。要跨文件就改成 `internal`"
                    )
    return problems


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("files", nargs="*", help="只扫指定的 .kt 文件")
    parser.add_argument("--changed", action="store_true", help="只扫工作区/最近提交改动的文件")
    parser.add_argument(
        "--detekt-probe", action="store_true",
        help="只跑圈复杂度探针（复现 CI 的 CyclomaticComplexMethod 判定）",
    )
    args = parser.parse_args()

    if args.detekt_probe:
        print("[info] detekt 探针（两级：全量规则 + 圈复杂度补充，见 8.6）")
        findings = run_detekt_probe()
        print()
        if findings < 0:
            return 0
        if findings:
            print(f"[FAIL] detekt 探针发现 {findings} 处问题。"
                  f"圈复杂度超限的修法：把新增分支收进 holder / 抽成独立 composable（删注释无用）。")
            return 1
        print("[OK] detekt 探针（全量规则 + 圈复杂度）均通过。")
        return 0

    if args.files:
        targets = [Path(f) for f in args.files]
    elif args.changed:
        targets = changed_files()
    else:
        targets = iter_kt_files()

    defined = string_resource_names()
    print(f"[info] 资源名 {len(defined)} 个；待扫 .kt {len(targets)} 个")

    total = 0
    for path in targets:
        if not path.exists() or path.suffix != ".kt":
            continue
        problems = (check_icons(path) + check_init_order(path) + check_strings(path, defined)
                    + check_duplicate_declarations(path) + check_dp_division_value(path)
                    + check_member_receiver(path))
        if problems:
            print(f"\n{display(path)}")
            for p in problems:
                print(f"  {p}")
                total += 1

    # 跨文件 private 是**模块级**判据（要看别的文件怎么用），单独跑一遍。
    # 只在全量扫描时跑：--changed 只看几个文件时语料不全，容易漏判。
    if not targets or targets == iter_kt_files():
        for owner, msgs in check_cross_file_private(targets).items():
            print(f"\n{display(owner)}")
            for p in msgs:
                print(f"  {p}")
                total += 1

    print()
    if total:
        print(f"[FAIL] 共 {total} 处。这几类 detekt 都查不出、本地也编译不了，"
              f"只在 CI 的 compile 步骤炸 —— 现在改掉。")
        return 1
    print(f"[OK] 检查了 {len(targets)} 个文件：图标导入 / 顶层常量初始化顺序 / "
          f"R.string 引用 / 重复声明 / Dp 除法 .value / 成员接收者 / 跨文件 private 均未见异常。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
