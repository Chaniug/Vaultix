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


def dangling(path: Path) -> list[tuple[int, str]]:
    text = path.read_text(encoding="utf-8", errors="ignore")
    bad: list[tuple[int, str]] = []
    for m in LINK.finditer(text):
        raw = m.group(1)
        if raw.startswith(SKIP_PREFIX):
            continue
        target = raw.split("#", 1)[0]
        if not target:
            continue
        resolved = (path.parent / unquote(target)).resolve()
        if not resolved.exists():
            line = text.count("\n", 0, m.start()) + 1
            bad.append((line, raw))
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
