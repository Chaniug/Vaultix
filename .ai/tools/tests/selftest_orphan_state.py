#!/usr/bin/env python3
"""`check_orphan_state.py` 的自测：**正样本要报、负样本不能报**。

探针本身也会出错，而"探针说没问题"和"真的没问题"是两件事 ——
没有自测的探针给出的是**假绿**（本项目对假绿的态度见 `.ai/ISSUES.md` 里那一族记录）。

跑法：`python3 .ai/tools/tests/selftest_orphan_state.py`
"""

from __future__ import annotations

import importlib.util
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]

spec = importlib.util.spec_from_file_location(
    "check_orphan_state", ROOT / ".ai" / "tools" / "check_orphan_state.py"
)
mod = importlib.util.module_from_spec(spec)
assert spec.loader is not None
spec.loader.exec_module(mod)

CASES: list[tuple[str, str, bool]] = [
    (
        "只写不读（真机实录的形状，必须报）",
        """
        @Composable
        fun Screen() {
            var showAutoLockDialog by rememberSaveable { mutableStateOf(false) }
            SettingsRow(onClick = { showAutoLockDialog = true })
        }
        """,
        True,
    ),
    (
        "正常渲染（不能报）",
        """
        @Composable
        fun Screen() {
            var showAutoLockDialog by rememberSaveable { mutableStateOf(false) }
            SettingsRow(onClick = { showAutoLockDialog = true })
            if (showAutoLockDialog) { AutoLockDialog(onDismiss = { showAutoLockDialog = false }) }
        }
        """,
        False,
    ),
    (
        "读出来当参数传走（不能报 —— 读不一定写成 if）",
        """
        @Composable
        fun Screen() {
            var query by remember { mutableStateOf("") }
            SearchBar(value = query, onChange = { query = it })
        }
        """,
        False,
    ),
    (
        "val + remember 形状（必须报）",
        """
        @Composable
        fun Screen() {
            val open = remember { mutableStateOf(false) }
            Button(onClick = { open.value = true })
        }
        """,
        True,
    ),
    (
        "完全没用到的状态（必须报 —— 比漏渲染更该删）",
        """
        @Composable
        fun Screen() {
            var unusedFlag by remember { mutableStateOf(0) }
            Text("hi")
        }
        """,
        True,
    ),
]


def run() -> int:
    failures = 0
    with tempfile.TemporaryDirectory() as tmp:
        for index, (title, code, should_report) in enumerate(CASES):
            file = Path(tmp) / f"Case{index}.kt"
            file.write_text(code, encoding="utf-8")
            hits = mod.scan_file(file)
            reported = bool(hits)
            ok = reported == should_report
            mark = "✅" if ok else "❌"
            print(f"{mark} {title}  → 应当{'报' if should_report else '不报'}，实际{'报' if reported else '不报'}")
            if not ok:
                failures += 1
                print(f"     hits={hits}")

    print()
    if failures:
        print(f"❌ 自测失败 {failures} 例 —— 探针本身不可信，别拿它的绿灯当结论。")
        return 1
    print(f"✅ 自测全过（{len(CASES)} 例）")
    return 0


if __name__ == "__main__":
    sys.exit(run())
