#!/usr/bin/env python3
"""`check_orphan_strings` 的自测用例。

## 为什么有这份文件

这个工具的**误判方向特别危险**：它说"零引用"、人照着删了 ——
如果实际是被引用了的，**只在运行时才炸**（`Resources$NotFoundException`），
而且往往是在某个不常走的路径上（错误分支、空列表提示、某个弹窗）。

所以自测必须覆盖**各种合法的引用形态**，确认每一种都能被认出来。
下面 `declared_and_used_*` 这一组就是为此存在的：
只要漏掉任何一种形态，对应的字符串就会被误报为"零引用"。

同时也要有真零引用的正例（`orphan_*`），否则"全都说成被引用"也能通过。
"""

from __future__ import annotations

import importlib.util
import sys
import tempfile
from pathlib import Path

_TOOL = Path(__file__).resolve().parent.parent / "check_orphan_strings.py"


def _load_tool():
    spec = importlib.util.spec_from_file_location("vaultix_check_orphan_strings", _TOOL)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


# (用例名, strings.xml 内容, {相对路径: 文件内容}, 期望孤儿的集合)
CASES: list[tuple[str, str, dict[str, str], set[str]]] = [
    (
        "kt_ref_ok",
        '<resources><string name="a_header">标题</string></resources>',
        {"app/src/main/java/X.kt": "val t = stringResource(R.string.a_header)"},
        set(),
    ),
    (
        # XML 里 @string/ 引用（layout / style / manifest 都走这条）。
        "xml_ref_ok",
        '<resources><string name="a_header">标题</string></resources>',
        {"app/src/main/res/layout/x.xml": '<TextView android:text="@string/a_header"/>'},
        set(),
    ),
    (
        # ⚠️ 形态：`R.string.` 出现在**字符串内**（比如这个工具自己的文档、或测试数据）。
        #   这是**假阳性来源**，但方向是安全的一侧（漏报孤儿，不会误删在用的）。
        #   刻意断言它被当作"已引用" —— 与其误删，不如少报。
        "string_literal_counts_as_used",
        '<resources><string name="a_header">标题</string></resources>',
        {"app/src/main/java/X.kt": 'val doc = "请用 R.string.a_header 引用"'},
        set(),
    ),
    (
        # 真零引用：代码里提都没提。
        "orphan_reports",
        '<resources><string name="a_orphan">没人用</string></resources>',
        {"app/src/main/java/X.kt": "val x = 1"},
        {"a_orphan"},
    ),
    (
        # 混合：一条在用、一条没用，只有后者该被报出。
        #   这条防的是"要么全报要么全不报"的粗暴实现。
        "mixed_only_orphan_reported",
        '<resources>'
        '<string name="a_used">在用</string>'
        '<string name="a_orphan">没用</string>'
        '</resources>',
        {"app/src/main/java/X.kt": "stringResource(R.string.a_used)"},
        {"a_orphan"},
    ),
    (
        # 名字前缀相同：`a_head` 与 `a_header`。
        #   防的是用 `in` / `startswith` 做匹配的实现 —— 那会把 a_header 的引用
        #   错算到 a_head 头上（或反之），是本类工具最经典的误判。
        "prefix_names_not_confused",
        '<resources>'
        '<string name="a_head">短名</string>'
        '<string name="a_header">长名</string>'
        '</resources>',
        {"app/src/main/java/X.kt": "stringResource(R.string.a_header)"},
        {"a_head"},
    ),
    (
        # 只统计 `<string>`，不把 `string-array` / `plurals` 的名字算进来。
        #   它们的引用方式（`R.array.` / `R.plurals.`）不同，混进来会造成误报。
        "ignores_non_string_resources",
        '<resources>'
        '<string-array name="a_array"><item>x</item></string-array>'
        '<string name="a_str">话</string>'
        '</resources>',
        {"app/src/main/java/X.kt": "stringResource(R.string.a_str)"},
        set(),
    ),
]


def _run(tool, xml_text: str, files: dict[str, str]) -> set[str]:
    with tempfile.TemporaryDirectory() as td:
        root = Path(td)
        xml = root / "app/src/main/res/values/strings.xml"
        xml.parent.mkdir(parents=True, exist_ok=True)
        xml.write_text(xml_text, encoding="utf-8")
        for rel, content in files.items():
            p = root / rel
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text(content, encoding="utf-8")

        # 把工具里指向真实仓库的常量临时重定向到临时目录。
        saved = (tool.ROOT, tool.STRINGS_XML, tool.RES_DIR, tool.SRC_ROOTS)
        tool.ROOT = root
        tool.STRINGS_XML = xml
        tool.RES_DIR = root / "app/src/main/res"
        tool.SRC_ROOTS = [root / m for m in ("app", "core", "data", "domain")]
        try:
            return set(tool.find_orphans(xml))
        finally:
            tool.ROOT, tool.STRINGS_XML, tool.RES_DIR, tool.SRC_ROOTS = saved


def main() -> int:
    tool = _load_tool()
    failed = 0
    for name, xml_text, files, want in CASES:
        got = _run(tool, xml_text, files)
        ok = got == want
        mark = "✓" if ok else "✗"
        detail = "" if ok else f"  期望 {sorted(want)}，得到 {sorted(got)}"
        print(f"  {mark} {name:32}{detail}")
        if not ok:
            failed += 1

    print()
    if failed:
        print(f"❌ {failed}/{len(CASES)} 个用例未通过")
        return 1
    print(f"✅ {len(CASES)}/{len(CASES)} 个用例通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
