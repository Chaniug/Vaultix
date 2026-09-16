#!/usr/bin/env python3
"""用了实验性 API 却没写 `@OptIn`？

## 为什么需要这个

Compose 里相当一部分 API 标了 `@ExperimentalMaterial3Api` /
`@ExperimentalMaterial3ExpressiveApi`：用它们**必须**在调用点 opt-in，
否则**编译期报错**：

    e: SettingsScreen.kt:517:5  This material API is experimental and is
       likely to change or to be removed in the future.

2026-09-15 实录：
- 引入 `BasicAlertDialog` 做对话框外壳时，三处调用点都漏了 `@OptIn`
  ⇒ CI `Build Debug APK` 直接失败，**后续所有步骤被 skip**（含 APK 发布）。

**为什么本地发现不了**：detekt 只跑静态规则（不做符号解析、不看注解）；
`check_signature_types.py` 只看函数签名里的类型名；
`check_import_packages.py` 只看 import 的包路径。
本工程**没有**任何工程级 `optIn` 编译参数（`app/build.gradle.kts` 的
`kotlin { compilerOptions }` 里只有 `jvmTarget`），所以每个调用点都得手写。
⇒ 又一条只有真实编译器能发现的缝。本脚本把它补上。

## 判据（关键：必须模拟「注解作用域」而不是简单文本搜索）

Kotlin 的 `@OptIn` 作用于**被它注解的那个函数**。而 `@Composable`
函数调用会被内联进调用方，所以只要**调用链上有一层**带了 `@OptIn`，
实际就是合法的（典型的：顶层 Screen 带 opt-in，内部子 composable 直接用）。

于是判据是：
1. 找出所有用到实验性符号的**函数**（按函数体区间划分，而不是行号猜测）；
2. 一个函数「已 opt-in」当且仅当：
   - 它**自己的注解块**（紧邻 `fun` 之前、以及参数列表与函数体之间的
     `: Return { @OptIn ... }` 这种也算）里有覆盖该符号的 `@OptIn`；或
   - 它在**同一个文件内**调用了某个「已 opt-in」的函数（内联继承）。
     迭代到不动点。
3. 剩下的就是真问题。

⚠️ 上一版用「`fun` 之前 4000 字符内出现过 `@OptIn` 就算过」，结果在干净仓库上
报了 **17 处误报**（`TopAppBar` / `ExposedDropdownMenuBox` / `CircularWavy…`
全是内联继承的合法用法），连它该抓的 3 处都淹没了。
**宁可漏报，不可误报** —— 见 `.ai/ISSUES.md` #101 的教训。

## 已知的漏报（接受了）

跨文件的「调用了一个已 opt-in 的 composable」这种情况本脚本判断不了
（需要真解析，超出单文件正则的能力）。实测这类结构在本仓库不存在，
且漏报的代价只是「CI 再炸一次」，而误报的代价是「工具被弃用」。

退出码：0 = 没发现问题；1 = 有可疑调用点。

## 用法

    python3 .ai/tools/check_experimental_optin.py            # 扫全部
    python3 .ai/tools/check_experimental_optin.py <文件>...  # 指定文件
    python3 .ai/tools/check_experimental_optin.py --changed  # 只扫本次改动
    python3 .ai/tools/check_experimental_optin.py --selftest # 自测
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

# 🔴 2026-09-16 修正：原先只扫 `app/src` ⇒ 改为全模块（app / core / data / domain）。
#    app/ 之外的代码同样会用实验性 API，同样会被 CI 的编译期 opt-in 检查拦下。
#    ⚠️ 排除 `reference/`（Bastion 对照源码，不参与构建）与 build/。
SRC_ROOTS = [ROOT / m for m in ("app", "core", "data", "domain")]
SRC_ROOT = ROOT / "app" / "src"

# ---------------------------------------------------------------------------
# 已知的实验性 API 符号 → 需要哪个 opt-in 注解
# ---------------------------------------------------------------------------
# ⚠️ 只登记**本仓库实际用到、且确认需要 opt-in** 的。
# 不确定的一律不登记 —— 误报会让人不再信任工具（ISSUES #101 的教训）。
EXPERIMENTAL_SYMBOLS: dict[str, str] = {
    "BasicAlertDialog": "ExperimentalMaterial3Api",
    "ModalBottomSheet": "ExperimentalMaterial3Api",
    "TopAppBar": "ExperimentalMaterial3Api",
    "TopAppBarDefaults": "ExperimentalMaterial3Api",
    "SearchBar": "ExperimentalMaterial3Api",
    "DatePicker": "ExperimentalMaterial3Api",
    "DatePickerDialog": "ExperimentalMaterial3Api",
    "TimePicker": "ExperimentalMaterial3Api",
    "ExposedDropdownMenuBox": "ExperimentalMaterial3Api",
    "CircularWavyProgressIndicator": "ExperimentalMaterial3ExpressiveApi",
    "LinearWavyProgressIndicator": "ExperimentalMaterial3ExpressiveApi",
    "ButtonGroup": "ExperimentalMaterial3ExpressiveApi",
    "ToggleButton": "ExperimentalMaterial3ExpressiveApi",
}

OPTIN_RE = re.compile(r"@OptIn\s*\(([^)]*)\)", re.DOTALL)
ANNOTATION_CLASS_RE = re.compile(r"([A-Za-z_]\w*(?:\.[A-Za-z_]\w*)*)\s*::class")

# 找 `fun` 关键字（含泛型 / 接收者 / 中缀修饰符）
FUN_RE = re.compile(
    r"(?:^|[^\w.])(?:(?:internal|private|public|protected|override|suspend|"
    r"inline|operator|infix|tailrec|external|expect|actual|const)\s+)*"
    r"fun\s+(?:<[^>{}()]*>\s*)?(?:[A-Za-z_]\w*(?:\.[A-Za-z_]\w*)*\.)?"
    r"([A-Za-z_]\w*)\s*\(",
    re.MULTILINE,
)

# 形如 `body: @Composable () -> Unit` 的注解形参（会作用于 lambda，不作用于本函数）
PARAM_ANNOTATION_HINT = re.compile(r":\s*@\w+")

# 注解块与 `fun` 之间最多允许这么长的「非注解」内容
# （覆盖 `@OptIn(...)` 后接 KDoc 尾、`@Suppress`、泛型换行等）
ANNOTATION_TAIL_LIMIT = 400


def strip_comments_and_strings(text: str) -> str:
    """把注释与字符串替换成等长空白（保留换行，行号不错位）。

    KDoc 里的 `@OptIn` 是**文档**不是注解，必须先抹掉，否则误判。
    """
    out: list[str] = []
    i, n = 0, len(text)
    while i < n:
        ch = text[i]
        if ch == "/" and i + 1 < n and text[i + 1] == "/":
            while i < n and text[i] != "\n":
                out.append(" ")
                i += 1
            continue
        if ch == "/" and i + 1 < n and text[i + 1] == "*":
            depth = 1
            out.append("  ")
            i += 2
            while i < n and depth > 0:
                if text[i] == "/" and i + 1 < n and text[i + 1] == "*":
                    depth += 1
                    out.append("  ")
                    i += 2
                    continue
                if text[i] == "*" and i + 1 < n and text[i + 1] == "/":
                    depth -= 1
                    out.append("  ")
                    i += 2
                    continue
                out.append("\n" if text[i] == "\n" else " ")
                i += 1
            continue
        if text.startswith('"""', i):
            out.append("   ")
            i += 3
            while i < n and not text.startswith('"""', i):
                out.append("\n" if text[i] == "\n" else " ")
                i += 1
            if i < n:
                out.append("   ")
                i += 3
            continue
        if ch in "\"'":
            quote = ch
            out.append(" ")
            i += 1
            while i < n:
                if text[i] == "\\" and i + 1 < n:
                    out.append("  ")
                    i += 2
                    continue
                if text[i] == quote:
                    out.append(" ")
                    i += 1
                    break
                out.append("\n" if text[i] == "\n" else " ")
                i += 1
            continue
        out.append(ch)
        i += 1
    return "".join(out)


# ---------------------------------------------------------------------------
# 函数体区间定位（不依赖缩进：用「先找 fun 头，再配对括号」的方式）
# ---------------------------------------------------------------------------


def _skip_ws(text: str, i: int) -> int:
    while i < len(text) and text[i] in " \t\r\n":
        i += 1
    return i


def _match_balanced(text: str, i: int, open_ch: str, close_ch: str) -> int:
    """从 text[i] == open_ch 开始配对，返回对应的 close_ch 之后的位置。"""
    assert text[i] == open_ch, (i, text[i : i + 20])
    depth = 0
    while i < len(text):
        c = text[i]
        if c in "([{":
            depth += 1
        elif c in ")]}":
            depth -= 1
            if depth == 0:
                return i + 1
        i += 1
    return len(text)


def _annotations_before(text: str, fun_pos: int) -> set[str]:
    """取紧邻 `fun` 之前那一段注解块里的 `@OptIn(...)`。

    Kotlin 的注解块与 `fun` 之间只允许有其它注解 / KDoc 的残余空白，
    不允许有别的声明。这里用「从 fun 往前、遇到连续空行就停」定位块起点，
    再在块内找 `@OptIn`。KDoc 已在 `strip_comments_and_strings` 里被抹平，
    所以文档里的 `@OptIn` 不会被误当成注解。
    """
    head = text[:fun_pos]
    blank = re.search(r"\n[ \t]*\n", head[::-1])
    if blank:
        start = len(head) - blank.start()
    else:
        start = 0
    block = text[start:fun_pos]
    # 块里不能夹着另一个声明（比如上一个函数的收尾 `}`），否则视为无关
    if "}" in block or "=" in block:
        # 只保留最后一个 `}` 之后的部分
        last_brace = max(block.rfind("}"), block.rfind("="))
        block = block[last_brace + 1:]
    anns: set[str] = set()
    for om in OPTIN_RE.finditer(block):
        anns.update(ANNOTATION_CLASS_RE.findall(om.group(1)))
    return anns


def find_functions(text: str) -> list[dict]:
    """返回 [{name, header_start, body_start, body_end, annotations}]。

    - header_start: `fun` 关键字位置
    - body_start / body_end: 函数体 `{ ... }` 区间；无函数体（接口/抽象）则为 None
    - annotations: 该函数**自己**函数头里出现的 `@OptIn(...)` 注解名集合
      （既含 `fun` 上方的注解块，也含 `: Type { @OptIn ... }` 这种表达式体的）
    """
    funcs: list[dict] = []
    fun_keyword = re.compile(r"(?<![\w.])fun\s")
    for m in FUN_RE.finditer(text):
        name_pos = m.start(1)
        # 从函数名往前找**最近的** `fun` 关键字（不能越过上一个函数名）
        fun_pos = -1
        for km in fun_keyword.finditer(text, max(0, name_pos - 120), name_pos):
            fun_pos = km.start()
        if fun_pos < 0:
            continue
        # 两个函数不能指向同一个 `fun`（防止正则回退串味）
        if funcs and funcs[-1]["header_start"] >= fun_pos:
            continue
        # 定位参数列表
        name_end = name_pos + len(m.group(1))
        if text[name_end:name_end + 1] == "<":
            gt = text.find(">", name_end)
            name_end = gt + 1 if gt > 0 else name_end
        paren = text.find("(", name_end)
        if paren < 0:
            continue
        after_params = _match_balanced(text, paren, "(", ")")
        # 跳过 `: ReturnType` / `where ...`，找函数体
        j = _skip_ws(text, after_params)
        body_start = body_end = None
        header_end = after_params
        while j < len(text):
            if text[j] == "{":
                body_start = j
                body_end = _match_balanced(text, j, "{", "}")
                header_end = j
                break
            if text[j] == "=":
                # 表达式体：到行尾（或到下一个顶层结构）为止，粗略取 800 字符
                nl = text.find("\n", j)
                header_end = nl if nl > 0 else min(len(text), j + 800)
                body_start = j
                body_end = header_end
                break
            nxt = _skip_ws(text, j)
            j = nxt if nxt != j else j + 1
            if j - after_params > 4000:
                header_end = j
                break

        # 函数头区间里的 @OptIn（如 `: Unit = run { @OptIn(...) ... }`）本仓库暂无先例，
        # 上面的 `_annotations_before` 已覆盖紧邻函数上方的注解块这一主流写法。
        anns = _annotations_before(text, fun_pos)

        funcs.append(
            {
                "name": m.group(1),
                "header_start": fun_pos,
                "body_start": body_start,
                "body_end": body_end,
                "annotations": anns,
                "header_end": header_end,
            }
        )
    return funcs


def used_symbols(body: str) -> set[str]:
    """函数体里用到的实验性符号。"""
    found = set()
    for sym in EXPERIMENTAL_SYMBOLS:
        if re.search(r"(?<![\w.])" + re.escape(sym) + r"\s*\(", body):
            found.add(sym)
    return found


def check_text(text: str, label: str) -> list[tuple[int, str, str, str]]:
    """返回 [(行号, 符号, 需要的注解, 所在函数名)]。"""
    funcs = find_functions(text)
    if not funcs:
        return []

    # 每个函数用到的实验性符号
    usage: dict[int, set[str]] = {}
    for idx, f in enumerate(funcs):
        if f["body_start"] is None:
            continue
        body = text[f["body_start"]: f["body_end"]]
        syms = used_symbols(body)
        if syms:
            usage[idx] = syms

    if not usage:
        return []

    # 迭代到不动点。一个函数「合法」当且仅当满足其一：
    #   (a) 它自己的注解块覆盖了它用到的全部实验性符号；或
    #   (b) 有另一个「已合法」的函数调用了它（Compose 的 @Composable 会被内联，
    #       调用方的 @OptIn 会向下传递到被内联的函数体）。
    # ⚠️ (b) 是**反向边**：合法性从「调用方」流向「被调方」。
    # 上一版把方向搞反了（查自己调用了谁），所以自测里准确报出了差异。
    callers: dict[int, set[int]] = {idx: set() for idx in usage}
    for idx in usage:
        body = text[funcs[idx]["body_start"]: funcs[idx]["body_end"]]
        for other in usage:
            if other == idx:
                continue
            oname = funcs[other]["name"]
            if re.search(r"(?<![\w.])" + re.escape(oname) + r"\s*\(", body):
                callers[other].add(idx)  # idx 调用了 other

    opted_in: set[int] = set()
    changed = True
    while changed:
        changed = False
        for idx in usage:
            if idx in opted_in:
                continue
            f = funcs[idx]
            need = usage[idx]
            # (a) 自己的注解覆盖了所需符号
            if all(EXPERIMENTAL_SYMBOLS[s] in f["annotations"] for s in need):
                opted_in.add(idx)
                changed = True
                continue
            # (b) 有已合法的调用方
            if any(c in opted_in for c in callers[idx]):
                opted_in.add(idx)
                changed = True

    problems: list[tuple[int, str, str, str]] = []
    for idx in sorted(usage):
        if idx in opted_in:
            continue
        f = funcs[idx]
        body = text[f["body_start"]: f["body_end"]]
        for sym in sorted(usage[idx]):
            ann = EXPERIMENTAL_SYMBOLS[sym]
            if ann in f["annotations"]:
                continue
            mm = re.search(r"(?<![\w.])" + re.escape(sym) + r"\s*\(", body)
            if not mm:
                continue
            line_no = text.count("\n", 0, f["body_start"] + mm.start()) + 1
            problems.append((line_no, sym, ann, f["name"]))
    return problems


def iter_kt_files() -> list[Path]:
    files: list[Path] = []
    for root in SRC_ROOTS:
        if not root.is_dir():
            continue
        files.extend(p for p in root.rglob("*.kt") if "/build/" not in p.as_posix())
    return sorted(set(files))


def changed_files() -> list[Path]:
    for rev in (["HEAD~1", "HEAD"], ["HEAD"]):
        try:
            out = subprocess.run(
                ["git", "diff", "--name-only", *rev],
                cwd=ROOT, capture_output=True, text=True, check=True,
            ).stdout
        except (subprocess.CalledProcessError, FileNotFoundError):
            continue
        files = [ROOT / line.strip() for line in out.splitlines() if line.strip()]
        files = [f for f in files if f.suffix == ".kt" and f.exists()]
        if files:
            return files
    return []


def display(path: Path) -> str:
    try:
        return str(path.relative_to(ROOT))
    except ValueError:
        return str(path)


def do_selftest() -> int:
    """自测：正例必须抓到，反例必须不报。"""
    cases: list[tuple[str, str, bool]] = [
        # (名字, 源码, 是否应该报错)
        (
            "漏 @OptIn 的直接调用",
            """
@Composable
fun Bad() {
    BasicAlertDialog(onDismissRequest = {}) { Text("hi") }
}
""",
            True,
        ),
        (
            "自己带 @OptIn",
            """
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Good() {
    BasicAlertDialog(onDismissRequest = {}) { Text("hi") }
}
""",
            False,
        ),
        (
            "KDoc 里的 @OptIn 不算数（真实注释）",
            """
/**
 * 说明：调用方都要写 `@OptIn(ExperimentalMaterial3Api::class)`。
 */
@Composable
fun BadDoc() {
    BasicAlertDialog(onDismissRequest = {}) { Text("hi") }
}
""",
            True,
        ),
        (
            "内联继承：顶层 opt-in + 内部直接用",
            """
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Screen() {
    TopAppBar(title = { Text("t") })
    Inner()
}

@Composable
private fun Inner() {
    BasicAlertDialog(onDismissRequest = {}) { Text("hi") }
}
""",
            False,
        ),
        (
            "跨文件继承（本脚本判不了 ⇒ 承认漏报，按「不报」处理）",
            """
@Composable
private fun UsesFromElsewhere() {
    AnotherFilesComposable()
}
""",
            False,
        ),
    ]

    failed = 0
    for name, src, should_report in cases:
        probs = check_text(strip_comments_and_strings(src), name)
        reported = len(probs) > 0
        ok = reported == should_report
        mark = "OK  " if ok else "BAD "
        if not ok:
            failed += 1
        print(f"[{mark}] {name}: 期望{'报错' if should_report else '通过'}，"
              f"实际{'报错' if reported else '通过'}"
              + (f" -> L{probs[0][0]} {probs[0][1]}" if probs else ""))

    print()
    if failed:
        print(f"[FAIL] 自测 {failed} 项不符预期")
        return 1
    print(f"[OK] 自测 {len(cases)} 项全部符合预期")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description="校验实验性 API 是否有 @OptIn")
    ap.add_argument("files", nargs="*", help="要检查的 .kt 文件；缺省=全部")
    ap.add_argument("--changed", action="store_true", help="只扫最近一次提交改动的")
    ap.add_argument("--selftest", action="store_true", help="跑内置自测")
    args = ap.parse_args()

    if args.selftest:
        return do_selftest()

    if args.changed:
        targets = changed_files()
    elif args.files:
        targets = [Path(f).resolve() for f in args.files]
    else:
        targets = iter_kt_files()

    if not targets:
        print("[warn] 没有要检查的文件")
        return 0

    total = 0
    for path in targets:
        if not path.exists() or path.suffix != ".kt":
            continue
        raw = path.read_text(encoding="utf-8", errors="replace")
        for line_no, sym, ann, fun in check_text(strip_comments_and_strings(raw), display(path)):
            if total == 0:
                pass
            print(f"{display(path)}:{line_no} `{fun}()` 里用了 `{sym}`，"
                  f"但该函数及其同文件调用链上都没有 `@OptIn({ann}::class)`")
            total += 1

    print()
    if total:
        print(f"[FAIL] 共 {total} 处缺 @OptIn。"
              f"这类错误 detekt 与其余自检工具都查不出，只在 CI 编译时炸 —— 现在改掉。")
        return 1
    print(f"[OK] 检查了 {len(targets)} 个文件，实验性 API 的 @OptIn 作用域均覆盖。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
