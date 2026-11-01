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

import subprocess
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from check_md_links import scan  # noqa: E402  （有意在插路径之后导入）

TOOL = Path(__file__).resolve().parents[1] / "check_md_links.py"

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
    (
        "⑨ 行内代码里的链接语法 ⇒ 示例不是链接，跳过",
        # ⚠️ 两个形态都要放：
        #   ① `(...)` 是 8.9 里的真实写法 —— 但它在 Windows 上会被解析成一个**存在的**目录
        #      （实测：去掉跳过逻辑后本用例仍绿 ⇒ 假绿），只有 Linux 才报错；
        #   ② `./nonexistent-example.md` 在所有平台都不存在 ⇒ 跳过逻辑一旦失效，本用例**必红**。
        #   教训：用例的输入要选「能判红」的，不能依赖某平台的路径怪癖。
        {
            ".ai/a.md": (
                "- 只写 `见 [8.x](...)` / `见 SESSION-...`。\n"
                "- 反例保护：`见 [8.x](./nonexistent-example.md)`\n"
            )
        },
        0,
    ),
    (
        "⑩ 代码块里的链接语法 ⇒ 跳过",
        {".ai/a.md": "```\nsample [a](./nope.md)\n```\n"},
        0,
    ),
]


def build(root: Path, files: dict[str, str]) -> None:
    for rel, content in files.items():
        p = root / rel
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(content, encoding="utf-8")


def cli_case() -> tuple[bool, str]:
    """命令行接口：位置参数 = 仓库根（CI 的真实调用方式）。

    与 selftest_issues_index 的 ⑦ 同一条教训：`scan()` 测通不代表命令行能跑。
    `check_md_links` 的参数是**手写解析**（不是 argparse），更容易在改动中失配。
    """
    with tempfile.TemporaryDirectory() as d:
        root = Path(d)
        build(root, BASE_OK)
        r = subprocess.run(
            [sys.executable, str(TOOL), str(root)],
            capture_output=True,
            text=True,
        )
        detail = (r.stderr or r.stdout).strip().splitlines()
        return r.returncode == 0, (detail[-1][:120] if detail else "")


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

    cli_ok, cli_detail = cli_case()
    print(("✅ " if cli_ok else "❌ ") + "⑪ 命令行：位置参数必须被接受（CI 真实用法）"
          + (f"  ← {cli_detail}" if not cli_ok else ""))
    if not cli_ok:
        failures += 1

    print(f"\n{'✅ 全部通过' if not failures else f'❌ {failures} 例失败'}")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
