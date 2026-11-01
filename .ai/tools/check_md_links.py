#!/usr/bin/env python3
"""检查 `.ai/` 与 `Docs/` 下 markdown 的相对链接是否有效。

## 为什么需要它

2026-11-01 拆分文档时（8.9 §7.1），文件被搬进 `-partN` / `archive/`，
相对链接**必然**要跟着改。改漏一处不会报错 —— 点进去才发现是 404，
而"点进去"往往发生在最需要它的那一刻（排查中查判据）。

⇒ 把"搬完顺手验一遍"变成一条能自己判红的命令。

## 用法

    python .ai/tools/check_md_links.py          # 有问题 ⇒ 退出码 1
    python .ai/tools/check_md_links.py -v       # 同时打印扫描过的文件数

## 判据

只检查**相对路径**链接（`http(s)://` / `mailto:` / 纯锚点 `#x` 跳过）。
带锚点的 `foo.md#bar` 只校验 `foo.md` 是否存在 —— 锚点是否有效不在本脚本范围
（标题锚点规则随渲染器变化，纳入会制造假红）。
"""
from __future__ import annotations

import re
import sys
from pathlib import Path
from urllib.parse import unquote

ROOTS = (Path(".ai"), Path("Docs"))
LINK = re.compile(r"\]\(([^)\s]+)(?:\s+\"[^\"]*\")?\)")
SKIP_PREFIX = ("http://", "https://", "mailto:", "#")


def _code_spans(line: str) -> list[tuple[int, int]]:
    """该行内行内代码（反引号）的区间。

    ⚠️ 2026-11-01 首次接 CI 的第二条真因：8.9 里有一句
    ``只写 `见 [8.x](...)` / `见 SESSION-...` `` —— 那是**示例语法**，不是真链接。
    Windows 上 `...` 恰好能解析成一个目录（假绿），Linux 上直接报断链。
    ⇒ 行内代码里的 `](...)` 一律不当链接看。
    """
    spans: list[tuple[int, int]] = []
    i = 0
    while (a := line.find("`", i)) >= 0:
        b = line.find("`", a + 1)
        if b < 0:
            break
        spans.append((a, b))
        i = b + 1
    return spans


def dangling(path: Path) -> list[tuple[int, str]]:
    """返回 [(行号, 原始链接)]。跳过代码块与行内代码。"""
    bad: list[tuple[int, str]] = []
    fence = False
    text = path.read_text(encoding="utf-8", errors="ignore")
    for lineno, line in enumerate(text.splitlines(), 1):
        stripped = line.lstrip()
        if stripped.startswith("```"):
            fence = not fence
            continue
        if fence:
            continue
        spans = _code_spans(line)
        for m in LINK.finditer(line):
            # 落在反引号区间内 ⇒ 是示例语法，不是链接
            if any(a <= m.start() <= b for a, b in spans):
                continue
            raw = m.group(1)
            if raw.startswith(SKIP_PREFIX):
                continue
            target = raw.split("#", 1)[0]
            if not target:
                continue
            resolved = (path.parent / unquote(target)).resolve()
            if not resolved.exists():
                bad.append((lineno, raw))
    return bad


def scan(root: Path, roots: tuple[Path, ...] = ROOTS) -> tuple[list[str], int]:
    """扫描 root 下的文档，返回 (问题列表, 扫描文件数)。

    暴露成函数而不是只在 `main` 里内联：自测（`tests/selftest_md_links.py`）
    要在临时目录上跑正反用例 —— 没有反例的门禁，等于没有门禁。
    """
    files = sorted(
        p
        for r in roots
        if (root / r).exists()
        for p in (root / r).rglob("*.md")
        if "build" not in p.parts
    )
    problems: list[str] = []
    for p in files:
        for line, raw in dangling(p):
            try:
                shown = p.relative_to(root)
            except ValueError:
                shown = p
            problems.append(f"{shown}:{line}: 断链 -> {raw}")
    return problems, len(files)


def main() -> int:
    # 位置参数 = 仓库根（与 check_issues_index.py 等一致，CI 里传 $GITHUB_WORKSPACE）；
    # `-v` 只控制是否打印扫描文件数，不当路径用。
    positional = [a for a in sys.argv[1:] if not a.startswith("-")]
    root = Path(positional[0]) if positional else Path(".")
    verbose = "-v" in sys.argv
    problems, count = scan(root)
    for msg in problems:
        print(msg)
    if verbose:
        print(f"扫描 {count} 个 md 文件")
    if problems:
        print(f"\n❌ {len(problems)} 处断链")
        return 1
    print(f"✅ 链接全部有效（{count} 个文件）")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
