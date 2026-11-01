#!/usr/bin/env python3
"""
`check_md_links.py` 的正反用例回归集。

## 为什么要自测

这类门禁最危险的失败不是"漏报"，而是**自己判错**：把 `https://` 当相对路径、
把带锚点的 `foo.md#bar` 整串当文件名、把中文文件名当成不存在 —— 都会制造假红，
而假红跑几次之后，人就会开始忽略它（真的断链也就跟着被忽略了）。

所以每个"跳过/解析"分支都要有一条用例钉住。反向方向同样要有：
断链那次，输出必须**指到具体文件**，不是随便报点什么。

用法：`python3 .ai/tools/tests/selftest_md_links.py`
"""

from __future__ import annotations

import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from check_md_links import scan  # noqa: E402  （有意在插路径之后导入）

BASE_OK = {
    ".ai/a.md": "[ok](./b.md)\n",
    ".ai/b.md": "# b\n",
}

CASES = [
    ("① 全部有效 ⇒ 零问题", BASE_OK, 0),
    ("② 断链 ⇒ 必须报，且指到具体文件", {".ai/a.md": "[bad](./missing.md)\n"}, 1),
    (
        "③ 带锚点 ⇒ 只验文件名（不把 # 后面当路径）",
        {".ai/a.md": "[ok](./b.md#sec)\n", ".ai/b.md": "# b\n"},
        0,
    ),
    (
        "④ http / mailto / 纯锚点 ⇒ 一律跳过",
        {".ai/a.md": "[h](https://x.test/a.md) [m](mailto:a@b.c) [a](#local)\n"},
        0,
    ),
    (
        "⑤ ../ 相对路径 ⇒ 按文件所在目录解析",
        {".ai/sub/a.md": "[up](../b.md)\n", ".ai/b.md": "# b\n"},
        0,
    ),
    (
        "⑥ 指向目录 ⇒ 存在即通过",
        {".ai/a.md": "[d](./sub/)\n", ".ai/sub/x.md": "# x\n"},
        0,
    ),
    (
        "⑦ 中文名 URL 编码 ⇒ 解码后判断",
        {".ai/a.md": "[c](./%E4%B8%AD.md)\n", ".ai/\u4e2d.md": "# 中\n"},
        0,
    ),
    (
        "⑧ Docs/ 也在扫描范围（不是只看 .ai）",
        {"Docs/a.md": "[bad](../.ai/nope.md)\n"},
        1,
    ),
]


def build(root: Path, files: dict[str, str]) -> None:
    for rel, content in files.items():
        p = root / rel
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(content, encoding="utf-8")


def main() -> int:
    failures = 0
    for name, files, expect in CASES:
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            build(root, files)
            problems, _ = scan(root)
        got = 1 if problems else 0
        ok = got == expect
        detail = ""
        # 反向精度：报了，就要报在点子上
        if ok and expect == 1:
            joined = " | ".join(problems)
            if "missing.md" in name or name.startswith("⑧"):
                token = "missing.md" if "missing.md" in name else "nope.md"
                if token not in joined:
                    ok, detail = False, f"没指到具体文件：{joined}"
        print(("✅ " if ok else "❌ ") + name + (f"  ← {detail}" if detail else ""))
        if not ok:
            failures += 1
            for p in problems:
                print(f"      ok？ → {p}")
    print(f"\n{'✅ 全部通过' if not failures else f'❌ {failures} 例失败'}")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
