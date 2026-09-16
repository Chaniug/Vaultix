#!/usr/bin/env python3
"""本地没有编译器：三类 **detekt 查不出、只有 CI 编译才炸** 的错误的补位自检。

## 为什么需要这个

沙箱里没有 Android SDK，**编译不了 Kotlin**；而 detekt **只跑静态规则集、不做
符号解析也不做类型检查**。于是下面三类错误在本地是**完全静默**的，只有推上去
等 CI 的 `compileFullDebugKotlin` 才暴露（每轮 CI 约 4 分钟）：

    e: ...ItemDetailScreen.kt:928:97  Unresolved reference 'Tune'.
    e: ...SettingsComponents.kt:80:30 Variable 'SETTINGS_ICON_BOX' must be initialized.

2026-09-16 实录：同一轮推送里这两条一起红了，而本地 detekt + 三个 `.ai/tools`
脚本**全绿**。它们各自对应的规则：

| 规则 | 真实报错 | 触发改动 |
|---|---|---|
| A 图标引用 vs 导入差集 | `Unresolved reference 'Tune'` | 给自定义字段分区加图标，只写了 `Icons.Filled.Tune` 没补 import |
| B 顶层属性前向引用 | `Variable 'X' must be initialized` | 把 `SETTINGS_ICON_BOX` 的声明放在了使用它计算 `SETTINGS_DIVIDER_INSET` **之后** |
| C 悬空 `R.string.*` | `Unresolved reference 'xxx'` | 删/改名了字符串却没同步改代码 |

⚠️ 规则 A 与 8.6 里 2026-09-15 那条「`CompositionLocalProvider` / `LocalContentColor`
只写用法没补导入」是**同一个坑的第二次犯**（那次靠手工 grep 抓，这次靠脚本）。
⇒ **教训：手工核对一次不算，要变成脚本。**

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


def iter_kt_files() -> list[Path]:
    return sorted(SRC_ROOT.rglob("*.kt"))


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


def display(path: Path) -> str:
    try:
        return path.relative_to(ROOT).as_posix()
    except ValueError:
        return str(path)


# ---------------------------------------------------------------------------
# --detekt-probe：在本地复现 CI 的圈复杂度判定
# ---------------------------------------------------------------------------
# ⚠️ 背景：`detekt-cli --config config/detekt/detekt.yml` **永远不报**
#    `CyclomaticComplexMethod` —— 该规则要 `buildUponDefaultConfig`（gradle 侧参数），
#    CLI 传不进去。于是本地 detekt 全绿、CI 才红（2026-09-16 实测，代价一次 CI 往返）。
#    本函数用一份**只显式打开该规则**的临时配置跑一遍，把这条盲区补上。
DETEKT_PROBE_CONFIG = """\
# 由 .ai/tools/check_compile_smells.py --detekt-probe 生成：
# 只显式打开圈复杂度规则（CLI 默认读不到 config/detekt/detekt.yml 里的该规则）。
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


def find_detekt() -> Path | None:
    for candidate in DETEKT_CANDIDATES:
        if candidate.exists():
            return candidate
    return None


def run_detekt_probe(scopes: list[str] | None = None) -> int:
    """跑圈复杂度探针；返回发现数（-1 表示环境缺 detekt，跳过）。"""
    detekt = find_detekt()
    if detekt is None:
        print("[skip] 未找到 detekt CLI，跳过探针"
              "（CI 仍会判定；本地可用 ./gradlew detekt 代替）。")
        return -1

    with tempfile.NamedTemporaryFile("w", suffix=".yml", delete=False, encoding="utf-8") as fh:
        fh.write(DETEKT_PROBE_CONFIG)
        probe_config = Path(fh.name)

    targets = scopes or ["app/src/main/java"]
    findings = 0
    try:
        for target in targets:
            full = ROOT / target
            if not full.exists():
                continue
            out = subprocess.run(
                [str(detekt), "--config", str(probe_config), "--input", str(full)],
                cwd=ROOT, capture_output=True, text=True,
            ).stdout
            for line in out.splitlines():
                if "CyclomaticComplexMethod]" in line and line.strip().startswith("e:"):
                    print(f"  {line.strip()}")
                    findings += 1
    finally:
        probe_config.unlink(missing_ok=True)
    return findings


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
        print("[info] 圈复杂度探针（detekt CLI 默认不报这条规则，见 8.6）")
        findings = run_detekt_probe()
        print()
        if findings < 0:
            return 0
        if findings:
            print(f"[FAIL] {findings} 处圈复杂度超限（>14）。"
                  f"修法：把新增分支收进 holder / 抽成独立 composable（删注释无用）。")
            return 1
        print("[OK] 圈复杂度均在 14 以内。")
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
        problems = check_icons(path) + check_init_order(path) + check_strings(path, defined)
        if problems:
            print(f"\n{display(path)}")
            for p in problems:
                print(f"  {p}")
                total += 1

    print()
    if total:
        print(f"[FAIL] 共 {total} 处。这三类 detekt 都查不出、本地也编译不了，"
              f"只在 CI 的 compile 步骤炸 —— 现在改掉。")
        return 1
    print(f"[OK] 检查了 {len(targets)} 个文件：图标导入 / 顶层常量初始化顺序 / "
          f"R.string 引用 均未见异常。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
