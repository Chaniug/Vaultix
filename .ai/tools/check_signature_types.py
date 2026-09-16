#!/usr/bin/env python3
"""新签名里的类型名，在本仓库里真的存在吗？

## 为什么需要这个

detekt **只做静态检查、不做类型检查**，而 CI 里 detekt 先于 compile。
于是「凭记忆写错类型名」这类错误**本地全绿、只在 CI 编译时才炸**。

2026-09-15 实录：把 `ItemsScreen` 一大段抽成 `ItemsBody` 时，签名里按印象写了
`ItemGroup` / `ItemsDisplayMode` —— 仓库里根本不存在这两个名字
（真名是 `ItemsGroup` / `ItemsCardDisplayMode`），而且同包内所以「看上去不需要 import」。
detekt 全过、本地无感，CI 编译才报 `Unresolved reference`。

⇒ **抽/改函数签名之后跑一遍这个脚本**，几秒钟把这个类别杀干净。

## 用法

    python3 .ai/tools/check_signature_types.py            # 扫全部改动文件
    python3 .ai/tools/check_signature_types.py --all      # 扫整个 app/src/main
    python3 .ai/tools/check_signature_types.py <文件>...  # 只扫指定文件

退出码：0 = 没发现问题；1 = 有可疑类型名（详见输出）。

## 判据（保守，宁可误报不可漏报）

对每个「函数/Composable 参数列表」里的类型名，检查它是否满足下列任一：
1. 出现在该文件的 `import` 里（取最后一段或显式 alias）；
2. 与该文件**同包**，且仓库里存在同名顶层类型定义；
3. 是 Kotlin / Java 默认可见的类型（`String` `Int` `Boolean` … `Unit` `Any`…）；
4. 是泛型类型参数（如 `<T>`）或函数类型里出现的名字；
5. 名字里带 `.`（全限定名）。

不满足 ⇒ 报出来。
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

# 🔴 2026-09-16 修正：原先只扫 `app/src`，而 app/ 之外（core/ data/ domain/）
#    同样是本项目源码、同样会被本工具该管的错误波及 ⇒ 改为全模块。
#    ⚠️ 排除 `reference/`（Bastion 对照源码，非本项目模块、不参与构建）与 build/。
SRC_ROOTS = [ROOT / m for m in ("app", "core", "data", "domain")]
SRC_ROOT = ROOT / "app" / "src"


def iter_kt_files() -> list[Path]:
    files: list[Path] = []
    for root in SRC_ROOTS:
        if not root.is_dir():
            continue
        files.extend(p for p in root.rglob("*.kt") if "/build/" not in p.as_posix())
    return sorted(set(files))

# ---------------------------------------------------------------------------
# 1. 建「全仓库顶层类型定义」集合
# ---------------------------------------------------------------------------

# class / interface / object / enum class / annotation class / typealias / fun interface
TYPE_DEF_RE = re.compile(
    r"^\s*(?:@\w+(?:\([^)]*\))?\s*)*"          # 允许 @Composable 之类的注解在前
    r"(?:public|internal|private|protected|abstract|open|sealed|data|value|"
    r"inner|enum|annotation|fun|external|expect|actual|inline|operator|"
    r"infix|suspend|const|lateinit|override|tailrec|vararg|crossinline|noinline|\s)*"
    r"\b(?:class|interface|object|typealias)\s+([A-Za-z_][A-Za-z0-9_]*)\b",
    re.MULTILINE,
)

# 顶层常量（`const val` / `val`，SCREAMING_CASE 命名）：它们常出现在**参数默认值**里
# （`maxLines: Int = MONOSPACE_MAX_LINES`），不是类型。
# 2026-09-16 补：此前表内只有 class/interface/object/typealias，于是这类常量
# 全部被报成「找不到定义的类型名」（实测 `MONOSPACE_MAX_LINES` / `PLAIN_MAX_LINES`
# 两处假报）——噪音会让人不再看输出，而本工具唯一的价值就是「报了就该改」。
CONST_DEF_RE = re.compile(
    r"^\s*(?:public|internal|private|protected)?\s*(?:const\s+)?val\s+"
    r"([A-Z][A-Z0-9_]*)\s*[=:]",
    re.MULTILINE,
)

PACKAGE_RE = re.compile(r"^\s*package\s+([\w.]+)", re.MULTILINE)
IMPORT_RE = re.compile(r"^\s*import\s+([\w.]+)(?:\s+as\s+(\w+))?", re.MULTILINE)


def iter_repo_sources() -> list[Path]:
    """全仓库 .kt（**排除** reference/ 参照工程与 build/ 产物）。

    2026-09-16 修正：原先只扫 `app/src`，于是 **data / core / domain 模块里的类型
    全部不在表里** —— 同包引用（如 data:bitwarden 的 `KdfProfile`）被误报成
    「找不到定义」，噪音大到没人愿意看输出。参照工程必须排除：它与主工程有大量同名
    类型，混进来会让「仓库里唯一一个」这类判据彻底失效。
    """
    skip_dirs = {"reference", "build", ".git", ".gradle", ".ai"}
    return [
        kt
        for kt in ROOT.rglob("*.kt")
        if not any(part in skip_dirs for part in kt.relative_to(ROOT).parts)
    ]


def collect_type_defs() -> tuple[dict[str, set[str]], set[str]]:
    """返回 ({类型名 -> {所在包...}}, 全部 top-level fun 名)。"""
    defs: dict[str, set[str]] = {}
    for kt in iter_repo_sources():
        try:
            text = kt.read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue
        pkg_m = PACKAGE_RE.search(text)
        pkg = pkg_m.group(1) if pkg_m else ""
        for m in TYPE_DEF_RE.finditer(text):
            defs.setdefault(m.group(1), set()).add(pkg)
        for m in CONST_DEF_RE.finditer(text):
            defs.setdefault(m.group(1), set()).add(pkg)
    return defs, set()


# ---------------------------------------------------------------------------
# 2. 从参数列表里抽类型名
# ---------------------------------------------------------------------------

# ⚠️ 关键：**不能**用「标识符 : 类型」这种朴素正则去扫整个参数串 ——
# 它会把**默认值**里的 `Foo.Bar` / `"字符串"` 也当成类型，产生大量误报
# （2026-09-15 首版实测：22 处误报，全是 `ThemeMode = ThemeMode.SYSTEM`
# 里的 `SYSTEM`、`String = "Vaultix"` 里的 `Vaultix` 这种）。
# 正确做法：按**顶层逗号**切分参数，每段只取**第一个**「name: Type」，
# 且 Type 截止到该段里**第一个 `=` 或该段末尾**。
#
# 还需要处理：显式类型里带全限定名（`android.content.Context`）与嵌套类
# （`BiometricPrompt.AuthenticationResult`）—— 这两种都自带包路径，
# 天然可解析，不该报。


def split_top_level_commas(s: str) -> list[str]:
    """按顶层逗号切分（忽略 () [] {} <> 以及字符串/字符字面量内部的逗号）。"""
    parts: list[str] = []
    depth = 0
    buf: list[str] = []
    i = 0
    quote: str | None = None
    while i < len(s):
        ch = s[i]
        if quote:
            buf.append(ch)
            if ch == "\\" and i + 1 < len(s):
                buf.append(s[i + 1])
                i += 2
                continue
            if ch == quote:
                quote = None
            i += 1
            continue
        if ch in "\"'":
            quote = ch
            buf.append(ch)
            i += 1
            continue
        if ch in "([{<":
            depth += 1
        elif ch in ")]}>":
            depth -= 1
        if ch == "," and depth == 0:
            parts.append("".join(buf))
            buf = []
            i += 1
            continue
        buf.append(ch)
        i += 1
    if buf:
        parts.append("".join(buf))
    return parts


def split_top_level_equals(s: str) -> str:
    """取参数段里**顶层 `=` 之前**的部分（即 name: Type），忽略 == >= <= != 与嵌套。"""
    depth = 0
    i = 0
    quote: str | None = None
    while i < len(s):
        ch = s[i]
        if quote:
            if ch == "\\":
                i += 2
                continue
            if ch == quote:
                quote = None
            i += 1
            continue
        if ch in "\"'":
            quote = ch
            i += 1
            continue
        if ch in "([{<":
            depth += 1
        elif ch in ")]}>":
            depth -= 1
        elif ch == "=" and depth == 0:
            # 排除 == >= <= !=
            prev = s[i - 1] if i > 0 else ""
            nxt = s[i + 1] if i + 1 < len(s) else ""
            if prev not in "=!<>" and nxt != "=":
                return s[:i]
        i += 1
    return s


# 一段参数（已切好）里的 `name: Type`；name 可缺省（如 `: Int`）
ONE_PARAM_RE = re.compile(
    r"^\s*(?:@\w+(?:\([^)]*\))?\s*)*"        # 允许参数注解
    r"(?:(vararg|noinline|crossinline|val|var)\s+)?"
    r"([A-Za-z_][A-Za-z0-9_]*)?\s*:\s*(.+?)\s*$",
    re.DOTALL,
)

# Kotlin 与 java.lang 默认可见（不 import 也能用）
BUILTIN = {
    "String", "Char", "Boolean", "Byte", "Short", "Int", "Long", "Float",
    "Double", "Unit", "Any", "Nothing", "Number", "Array", "ByteArray",
    "CharArray", "ShortArray", "IntArray", "LongArray", "FloatArray",
    "DoubleArray", "BooleanArray", "Pair", "Triple", "Comparable", "CharSequence",
    "Throwable", "Exception", "RuntimeException", "Error", "Enum", "Annotation",
    "ThreadLocal", "Void", "Object", "Suppress", "Deprecated", "JvmStatic",
    "Result", "Regex", "Duration", "UByte", "UShort", "UInt", "ULong",
    "ArrayDeque", "StringBuilder", "StringBuffer", "Cloneable", "AutoCloseable",
    "Sequence", "Iterable", "Collection", "List", "Set", "Map", "MutableList",
    "MutableSet", "MutableMap", "Lazy", "Function",
    # Compose 里极其常见的、不需要 import 的（同包或隐式）
    "Modifier", "Composable",
}

# 泛型参数、类型参数名
GENERIC_RE = re.compile(r"\b([A-Z][A-Za-z0-9_]*)\b")

SKIP_KEYWORDS = {
    "suspend", "inline", "vararg", "crossinline", "noinline", "internal",
    "private", "public", "open", "override", "operator", "infix", "tailrec",
    "reified", "actual", "expect", "const", "lateinit", "out", "in", "where",
}


def extract_signature_types(text: str) -> list[str]:
    """返回该文件里所有「形如函数参数列表」中出现的顶层类型名。"""
    names: list[str] = []
    # 逐行找 `fun` / `private fun` / `internal fun` ... 后面的参数列表
    for m in re.finditer(r"\bfun\s+(?:<[^>]*>\s*)?[A-Za-z_][\w.]*\s*\(", text):
        # 找到匹配的右括号
        i = m.end() - 1
        depth = 0
        j = i
        while j < len(text):
            if text[j] == "(":
                depth += 1
            elif text[j] == ")":
                depth -= 1
                if depth == 0:
                    break
            j += 1
        params = text[i + 1 : j]
        for pm in PARAM_RE.finditer(params):
            names.append(pm.group(2))
    return names


def top_level_type_names(type_text: str) -> set[str]:
    """从一段类型文本里抽出「需要能解析」的类型名。

    规则（**只取"需要 import 或被同包定义"的那些**，避免误报）：

    - **全限定名整段跳过**：`android.content.Context`、`javax.crypto.Cipher`
      —— 自带包路径，任何情况下都能解析，不用查表。
    - `PasswordStrength.Level`（嵌套类）：**只查最左边那一段**（`PasswordStrength`），
      因为最左边那个才是需要 import 的。右边是它的成员。
    - `Result<VaultSaveOutcome>`：`Result` 是 kotlin 默认可见（在 BUILTIN），
      `VaultSaveOutcome` 需查表。
    - `(A, B) -> Unit`：A/B/Unit 都要能解析。

    实现：把「大写开头的标识符链」按 `.` 切开，链长为 1 时取该名字；
    链长 ≥ 2 时取链头（其余是成员，不需要单独 import）。
    链前若紧跟其他标识符字符则说明它是小写开头的前缀（如包名），整链跳过。
    """
    found: set[str] = set()
    # 只匹配「点分隔的大写开头标识符链」
    for m in re.finditer(r"(?<![\w.])(?:[A-Za-z_][A-Za-z0-9_]*)(?:\.(?:[A-Za-z_][A-Za-z0-9_]*))+"
                         r"|(?<![\w.])([A-Z][A-Za-z0-9_]*)", type_text):
        chain = m.group(0)
        segments = chain.split(".")
        head = segments[0]
        # 链头必须是大写开头；否则是包名前缀（如 `android.content.Context`
        # 整体已被上一个分支匹配，这里处理的是 `foo.bar` 这类）—— 跳过
        if not head[:1].isupper():
            continue
        if len(segments) >= 2:
            # 链里若全是小写段 ⇒ 是包名/全限定名，无需解析
            if all(s[:1].islower() for s in segments):
                continue
            # 链头有大写但有多个大写段 ⇒ 取链头（如 PasswordStrength.Level）
            found.add(head)
        else:
            found.add(head)
        if head in SKIP_KEYWORDS:
            found.discard(head)
    return found


def imports_of(text: str) -> tuple[set[str], set[str]]:
    """返回 (import 的简单名集合, import 的全限定名集合)。"""
    simple: set[str] = set()
    fq: set[str] = set()
    for m in IMPORT_RE.finditer(text):
        path = m.group(1)
        alias = m.group(2)
        fq.add(path)
        if alias:
            simple.add(alias)
        else:
            simple.add(path.rsplit(".", 1)[-1])
        # `import a.b.C` 里若 C 是嵌套类（A.B.C），也登记倒数第二段
        parts = path.split(".")
        for seg in parts:
            simple.add(seg)
    return simple, fq


def strip_comments_and_strings(text: str) -> str:
    """把注释与字符串字面量替换成等长空白（**保留换行**，行号才不错位）。

    ⚠️ 必须先做这一步：KDoc / 行注释里写着 `fun foo(...)` 的**示例**极常见
    （本仓库注释密度很高），不剥掉就会把注释当成真函数定义去解析参数列表。
    2026-09-15 首版实测：没剥注释 ⇒ 报出 `Tab` 这种来自注释散文的假类型名。
    """
    out: list[str] = []
    i = 0
    n = len(text)
    while i < n:
        ch = text[i]
        # 行注释
        if ch == "/" and i + 1 < n and text[i + 1] == "/":
            while i < n and text[i] != "\n":
                out.append(" ")
                i += 1
            continue
        # 块注释（Kotlin 支持嵌套）
        if ch == "/" and i + 1 < n and text[i + 1] == "*":
            depth = 1
            out.append(" ")
            out.append(" ")
            i += 2
            while i < n and depth > 0:
                if text[i] == "/" and i + 1 < n and text[i + 1] == "*":
                    depth += 1
                    out.append(" ")
                    out.append(" ")
                    i += 2
                    continue
                if text[i] == "*" and i + 1 < n and text[i + 1] == "/":
                    depth -= 1
                    out.append(" ")
                    out.append(" ")
                    i += 2
                    continue
                out.append("\n" if text[i] == "\n" else " ")
                i += 1
            continue
        # 三引号字符串
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
        # 普通字符串 / 字符字面量
        if ch in "\"'":
            quote = ch
            out.append(" ")
            i += 1
            while i < n:
                if text[i] == "\\" and i + 1 < n:
                    out.append(" ")
                    out.append(" ")
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


def check_file(path: Path, defs: dict[str, set[str]]) -> list[tuple[int, str, str]]:
    raw = path.read_text(encoding="utf-8", errors="replace")
    text = strip_comments_and_strings(raw)
    pkg_m = PACKAGE_RE.search(raw)
    pkg = pkg_m.group(1) if pkg_m else ""
    imported, imported_fq = imports_of(raw)

    problems: list[tuple[int, str, str]] = []
    for m in re.finditer(r"\bfun\s+(?:<[^>]*>\s*)?[A-Za-z_][\w.]*\s*\(", text):
        line_no = text.count("\n", 0, m.start()) + 1
        i = m.end() - 1
        depth, j = 0, i
        while j < len(text):
            if text[j] == "(":
                depth += 1
            elif text[j] == ")":
                depth -= 1
                if depth == 0:
                    break
            j += 1
        params = text[i + 1 : j]

        # 收集本函数签名里声明的泛型参数（<T> 在 fun 名后）
        generic_params: set[str] = set()
        gm = re.match(r"\bfun\s+<([^>]*)>", text[m.start() :])
        if gm:
            generic_params = {g.strip().split(":")[0].strip() for g in gm.group(1).split(",")}

        for raw in split_top_level_commas(params):
            decl = split_top_level_equals(raw)
            pm = ONE_PARAM_RE.match(decl)
            if not pm:
                continue
            type_text = pm.group(3)
            if not type_text:
                continue
            for name in top_level_type_names(type_text):
                if name in BUILTIN:
                    continue
                if name in generic_params:
                    continue
                if name in imported:
                    continue
                # 同包且有定义？
                if name in defs and pkg in defs[name]:
                    continue
                # 有定义且恰好是唯一一个（宽容：同包判断失败但仓库里只有一个）
                if name in defs and len(defs[name]) == 1:
                    continue
                # 全限定名形式
                if any(name in f.split(".") for f in imported_fq):
                    continue
                problems.append((line_no, name, type_text.strip()))
    return problems


def _git_names(*args: str) -> list[str]:
    try:
        out = subprocess.run(
            ["git", "diff", "--name-only", *args],
            cwd=ROOT, capture_output=True, text=True, check=True,
        ).stdout
    except (subprocess.CalledProcessError, FileNotFoundError):
        return []
    return [line.strip() for line in out.splitlines() if line.strip()]


def changed_files() -> list[Path]:
    """最近一次提交 **+ 工作区未提交** 的改动（并集）。

    ⚠️ 2026-09-16 修正：此前只有 `HEAD~1..HEAD`，且仅当它为空时才回退到工作区。
    于是**只要上一条提交改过文件（几乎总是）**，工作区里未提交的改动就**永远不被检查**
    —— 恰好是本轮的情形：新增 `SettingsGroupCard(content: @Composable ColumnScope.() -> Unit)`
    漏了 `ColumnScope` 的 import，脚本却只查了上一条提交改的 2 个文件，报了 OK。
    这类错误 detekt 查不出，只能靠本脚本，而"默认不查当前在改的东西"等于白装。
    """
    names = set(_git_names("HEAD~1", "HEAD"))
    names |= set(_git_names("HEAD"))
    try:
        out = subprocess.run(
            ["git", "ls-files", "--others", "--exclude-standard"],
            cwd=ROOT, capture_output=True, text=True, check=True,
        ).stdout
        names |= {line.strip() for line in out.splitlines() if line.strip()}
    except (subprocess.CalledProcessError, FileNotFoundError):
        pass
    files = [ROOT / n for n in names]
    return [f for f in files if f.suffix == ".kt" and f.exists()]


def main() -> int:
    ap = argparse.ArgumentParser(description="校验新签名里的类型名在本仓库是否存在")
    ap.add_argument("files", nargs="*", help="要检查的 .kt 文件；缺省=最近一次提交改动的")
    ap.add_argument("--all", action="store_true", help="检查全部源码模块下的 .kt")
    args = ap.parse_args()

    defs, _ = collect_type_defs()
    print(f"[info] 全仓库顶层类型定义 {len(defs)} 个")

    if args.all:
        targets = iter_kt_files()
    elif args.files:
        targets = [Path(f).resolve() for f in args.files]
    else:
        targets = changed_files()

    if not targets:
        print("[warn] 没有要检查的文件（可用 --all 或显式传文件名）")
        return 0

    total = 0
    for path in targets:
        if not path.exists() or path.suffix != ".kt":
            continue
        problems = check_file(path, defs)
        if problems:
            rel = path.relative_to(ROOT) if path.is_absolute() else path
            print(f"\n{rel}")
            for line_no, name, type_text in problems:
                print(f"  L{line_no}: 类型名 `{name}` 在仓库里找不到定义或 import"
                      f"   (来自 `{type_text}`)")
                total += 1

    print()
    if total:
        print(f"[FAIL] 共 {total} 处可疑类型名。"
              f"这类错误 detekt 查不出、只在 CI 编译时炸 —— 现在改掉。")
        return 1
    print(f"[OK] 检查了 {len(targets)} 个文件，签名类型名全部可解析。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
