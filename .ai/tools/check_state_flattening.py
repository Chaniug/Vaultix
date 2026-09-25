#!/usr/bin/env python3
"""多态状态（sealed interface）被压成二值开关了吗？

## 为什么需要这个

2026-09-26 实录：`QuickUnlockSettingsRows` 里两个开关写的是

    Switch(checked = state.biometric is CapabilityState.On, ...)

而 `CapabilityState` 是**三态**：

    Off（没启用） / Partial(n)（配了一半，还差 n 个） / On（全部生效）

`is On` 把 `Off` 和 `Partial` **合并成同一个"关"**。于是 `Partial` 渲染成关着的开关，
用户点一下，预期"打开"，实际发生的是"进入配置向导向导接着配"——
真机表现就是「点了开关没打开，反而弹了个框」。

要害在于：**`checked = x is On` 是完全合法的 Kotlin**。
类型检查不报、detekt 不报，`when` 的穷尽性也管不到它（压根不是 `when`）。
换句话说，**这个 bug 在现有全部自动化手段下都是不可见的**，
它只在真机上、且只有当用户恰好处于 `Partial` 状态时才会暴露。

⇒ 需要一条**专门盯这个形状**的检查：凡是拿 `is <某状态>.On` / `== <某状态>.On`
去喂开关类控件的 `checked=`，而该状态的类型有 **3 个及以上**分支时，报出来。

## 判据（保守，宁可误报不可漏报）

1. 扫出所有 `sealed interface/class` 的**分支数**（直接实现者），分支数 < 3 的不管
   —— 二态压二态是对的，没有可合并的东西。
2. 在同一文件里找 `checked = <表达式>` 形式的实参，表达式里含
   `is <Short>.On` 或 `== <Short>.On`（`On` 是本仓库表达"全部就绪"的约定名）。
3. 若 `<Short>` 能关联到一个分支数 ≥ 3 的 sealed 类型 ⇒ 报出。
4. 该实参处于一个显式的 `when (<expr>)` 分支体内 ⇒ **放行**。
   因为在 `when` 里 `is On ->` 是**穷尽分派**，`Partial` 有自己的分支，
   不属于"压成二态"。判据 4 是这条探针最重要的**负例护栏**，
   没有它会把正确写法全部误报（见自测用例 `when_dispatch_ok`）。

## 已知局限（说清，避免误以为它能保证正确）

- 只看同文件内的类型声明。跨文件的 sealed 类型 + 开关在同文件的分派场景会漏报
  （本项目该场景由 `CapabilityToggle` 集中在组件内，暂不构成缺口）。
- 只认 `On` 这个名字。若某类型用别的名字表示"全部就绪"，会漏报。
- 不做控制流分析。`if (x is On) ... else ...` 里的 `checked` 不会放行，
  会被报出来 —— 这类写法若确实正确，需要显式改成 `when` 或加 `# noqa`。
  这是**刻意选择的方向**：宁可让作者写清 `when`，也不放过真 bug。

## 用法

    python3 .ai/tools/check_state_flattening.py            # 扫全部改动文件
    python3 .ai/tools/check_state_flattening.py --all      # 扫整个源码树
    python3 .ai/tools/check_state_flattening.py <文件>...

退出码：0 = 没发现问题；1 = 有可疑写法（详见输出）。

自测：`python3 .ai/tools/tests/selftest_state_flattening.py`
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

SRC_ROOTS = [ROOT / m for m in ("app", "core", "data", "domain")]

# 表达"全部就绪"的约定名（本仓库 QuickUnlockController.CapabilityState.On 是这个用法）。
READY_MARKERS = ("On",)

# sealed 类型声明：`sealed interface Foo {` / `sealed class Foo<...> {`
SEALED_DECL = re.compile(
    r"^\s*sealed\s+(?:interface|class)\s+([A-Za-z_]\w*)",
    re.MULTILINE,
)

def collect_polyadic_names(files: list[Path]) -> set[str]:
    """全源码树里分支数 ≥ 3 的 sealed 类型名。

    ## 为什么必须跨文件

    探针第 1 版只在**同文件**里找 sealed 声明，于是漏掉了真实场景：
    `CapabilityState` 定义在 `QuickUnlockController.kt`，
    而把 `is CapabilityState.On` 喂给开关的是 `QuickUnlockDialogs.kt` —— 两个文件。
    用一个只扫单文件的探针去扫那个 bug，**它会说"没问题"**。
    （实测：修复前的 `QuickUnlockDialogs.kt` 报 0 处，实际有 2 处。）

    ⇒ 类型定义的位置与误用的位置解耦，必须先建一张**全树**的
    「哪些名字是多态状态」的表，再拿它去扫具体文件。

    ⚠️ 代价：同名类型跨模块语义不同时可能误报。本项目该名字
    （`CapabilityState`）只有一处定义，暂不构成问题；真出现时按 `# noqa` 处理。
    """
    names: set[str] = set()
    for f in files:
        if is_excluded(f):
            continue
        try:
            src = f.read_text(encoding="utf-8")
        except (UnicodeDecodeError, OSError):
            continue
        for name in set(SEALED_DECL.findall(src)):
            if branch_count(src, name) >= 3:
                names.add(name)
    return names


def _split_args(block: str) -> list[tuple[int, str]]:
    """把括号内的一整块实参按**顶层逗号**切成 [(块内偏移, 文本)]。

    ⚠️ 不能简单地 `block.split(",")`：泛型 `<String, Int>`、函数类型
    `(Boolean) -> Unit`、lambda 里的逗号都会把实参切碎，
    切碎后 `checked = ...On` 与它的上下文分离，判据 4（when 放行）就失效。
    """
    parts: list[tuple[int, str]] = []
    depth = 0
    start = 0
    for i, ch in enumerate(block):
        if ch in "(<{[":
            depth += 1
        elif ch in ")>}]":
            depth -= 1
        elif ch == "," and depth == 0:
            parts.append((start, block[start:i]))
            start = i + 1
    parts.append((start, block[start:]))
    return parts


# 开关类控件：checked= 出现在这些调用的实参里才管。
# 本项目另有 SettingsSwitch(value = ...)，故 value= 也算，但**要求**
# 调用名属于开关家族，避免把 ViewModel 的 `value = someState == X.On` 误报。
TOGGLE_CALLEES = ("Switch", "Checkbox", "RadioButton", "SettingsSwitch", "ToggleButton")
# 抓 `<Callee>(` 之后到配对 `)` 的整块实参
CALL_HEAD = re.compile(
    r"\b(" + "|".join(TOGGLE_CALLEES) + r")\s*\(",
)

# 表达式里对状态做的二值判定：`x is SomeState.On` / `x == SomeState.On`
# 也覆盖全限定名 `x is pkg.Outer.CapabilityState.On` —— 取 `.On` **前面最后一段**当类型名。
#
# ⚠️ 2026-09-26 血泪：原本写的是 `(?:is|==)\s*([A-Za-z_]\w*)\.([A-Za-z_]\w*)`，
#    在真实代码 `state.biometric is QuickUnlockController.CapabilityState.On` 上，
#    它贪婪地匹配成 `(QuickUnlockController, CapabilityState)` —— 把**倒数第二段**
#    当成了类型名。于是查表查到 `QuickUnlockController`（不在多态类型表里）直接跳过，
#    **探针在真实 bug 上返回 0 处**。
#    而我的自测用例写的是短名 `Cap.On`，恰好能匹配 ⇒ **7/7 全绿却完全无效**。
#    教训：自测用例的写法必须包含**生产代码的真实形态**（含全限定名），
#    否则自测只证明了"探针能处理我编的简化输入"，不能证明它有用。
READY_TEST = re.compile(r"(?:is|==)\s*(?:[A-Za-z_]\w*\.)*([A-Za-z_]\w*)\.([A-Za-z_]\w*)")


def iter_kt_files() -> list[Path]:
    files: list[Path] = []
    for root in SRC_ROOTS:
        if not root.is_dir():
            continue
        files.extend(p for p in root.rglob("*.kt") if "/build/" not in p.as_posix())
    return sorted(set(files))


def changed_files() -> list[Path]:
    """相对 HEAD 有改动的 .kt 文件（含未跟踪）。取不到就退回全量。"""
    try:
        out = subprocess.run(
            ["git", "-C", str(ROOT), "status", "--porcelain"],
            capture_output=True, text=True, check=True,
        ).stdout
    except (subprocess.CalledProcessError, FileNotFoundError):
        return []
    files: list[Path] = []
    for line in out.splitlines():
        if len(line) < 4:
            continue
        path = line[3:].strip()
        if path.endswith(".kt"):
            abs_path = ROOT / path
            if abs_path.is_file():
                files.append(abs_path)
    return sorted(set(files))


def branch_count(source: str, sealed_name: str) -> int:
    """数一个 sealed 类型有几个直接分支。

    做法：找到它的声明体（第一个 `{` 到匹配的 `}`，用花括号配对），
    在体内数 `data object X` / `object X` / `data class X` / `class X` 顶层成员。
    """
    decl = SEALED_DECL.search(source)
    while decl:
        if decl.group(1) == sealed_name:
            break
        decl = SEALED_DECL.search(source, decl.end())
    if not decl:
        return 0

    start = source.find("{", decl.end())
    if start == -1:
        return 0

    depth = 0
    end = -1
    for i in range(start, len(source)):
        ch = source[i]
        if ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
            if depth == 0:
                end = i
                break
    if end == -1:
        return 0

    body = source[start + 1:end]
    # 只数体**内**的直接成员：靠缩进判断不可靠，改用"花括号深度 0"的声明。
    members = 0
    depth = 0
    for line in body.splitlines():
        stripped = line.strip()
        if depth == 0 and re.match(
            r"^(?:data\s+)?(?:object|class|interface)\s+[A-Za-z_]\w*", stripped
        ):
            members += 1
        depth += line.count("{") - line.count("}")
        if depth < 0:
            depth = 0
    return members


def is_inside_when_dispatch(source: str, expr_start: int, expr_end: int) -> bool:
    """该表达式是否处在一个显式 `when (<subject>)` 的分支体内？

    做法：向上找最近的、且花括号尚未闭合的 `when (`；若找到，再看
    `when` 与表达式之间是否出现过 `->`（说明表达式落在某个分支体内）。
    """
    head = source[:expr_start]
    when_idx = head.rfind("when (")
    if when_idx == -1:
        return False

    # when 之后到表达式之前，必须至少有一个 `->`（即我们处于分支体里），
    # 且这段里不能有把它闭合掉的 `}`（用深度判断）。
    between = source[when_idx:expr_start]
    depth = 0
    for ch in between:
        if ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
    if depth <= 0:
        return False
    return "->" in between


def _call_args(source: str, open_paren: int) -> tuple[int, str] | None:
    """从 `(` 的位置出发，返回配对括号内内容的 (起始偏移, 文本)。"""
    depth = 0
    for i in range(open_paren, len(source)):
        ch = source[i]
        if ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
            if depth == 0:
                return open_paren + 1, source[open_paren + 1:i]
    return None


def scan(source: str, polyadic: set[str]) -> list[tuple[int, str]]:
    """返回 [(行号, 说明)]。

    [polyadic] 是**全树**扫描出的"分支数 ≥ 3 的 sealed 类型名"集合
    （见 [collect_polyadic_names]）—— 不能只在本文件里找，否则真实场景必漏。
    """
    findings: list[tuple[int, str]] = []
    if not polyadic:
        return findings

    for head in CALL_HEAD.finditer(source):
        callee = head.group(1)
        got = _call_args(source, head.end() - 1)
        if got is None:
            continue
        args_start, args_text = got

        for off, chunk in _split_args(args_text):
            # 只看 `checked = ...` / `value = ...` 这种具名实参
            m = re.match(r"\s*(checked|value)\s*=\s*(.*)$", chunk, re.DOTALL)
            if not m:
                continue
            arg_name, expr = m.group(1), m.group(2)

            for tm in READY_TEST.finditer(expr):
                short, member = tm.groups()
                if member not in READY_MARKERS:
                    continue
                if short not in polyadic:
                    continue
                # 表达式在源码里的绝对位置（用于 when 放行判断与行号）
                expr_abs_start = args_start + off + m.start(2)
                if is_inside_when_dispatch(source, expr_abs_start, expr_abs_start + len(expr)):
                    continue
                line_no = source.count("\n", 0, expr_abs_start) + 1
                findings.append((
                    line_no,
                    f"{callee}({arg_name} = ...) 里用了 `{short}.{member}` —— {short} 是三态类型"
                    f"（≥3 分支），这个二值判定会把 Off 与 Partial 合并成同一个「关」。"
                    f"若这里是**穷尽分派**请改用 `when`；若确实要二值化，请显式说明为何 Partial 可与 Off 合并。",
                ))
    return findings


def is_excluded(path: Path) -> bool:
    p = path.as_posix()
    return "/build/" in p or "/reference/" in p


def iter_docs(files: list[Path]):
    for f in files:
        if is_excluded(f):
            continue
        try:
            yield f, f.read_text(encoding="utf-8")
        except (UnicodeDecodeError, OSError):
            continue


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--all", action="store_true", help="扫整个源码树")
    ap.add_argument("files", nargs="*", type=Path, help="只扫指定文件")
    args = ap.parse_args(argv)

    if args.files:
        files = args.files
    elif args.all:
        files = iter_kt_files()
    else:
        files = changed_files() or iter_kt_files()

    # 先建全树的"多态状态名"表：类型定义与误用位置常常不在同一个文件
    # （实测：CapabilityState 在 QuickUnlockController.kt 定义，
    #   被压扁的开关在 QuickUnlockDialogs.kt）⇒ 只扫单文件必漏。
    polyadic = collect_polyadic_names(iter_kt_files())
    # 显式点名的文件若不在源码树内（如 /tmp 下的对照副本），
    # 也要把**它自己**的多态类型算进去，否则自测与"扫 HEAD 版本"都会退化成空集。
    for f in files:
        if is_excluded(f) or not f.is_file():
            continue
        try:
            src = f.read_text(encoding="utf-8")
        except (UnicodeDecodeError, OSError):
            continue
        for name in set(SEALED_DECL.findall(src)):
            if branch_count(src, name) >= 3:
                polyadic.add(name)

    total = 0
    for path, source in iter_docs(files):
        findings = scan(source, polyadic)
        if not findings:
            continue
        try:
            rel = path.relative_to(ROOT)
        except ValueError:
            rel = path
        for line_no, reason in findings:
            print(f"{rel}:{line_no}: {reason}")
            total += 1

    if total:
        print(f"\n❌ {total} 处可疑的状态压扁写法。")
        return 1
    print("✅ 未发现把多态状态压成二值开关的写法。")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
