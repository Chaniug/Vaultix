#!/usr/bin/env python3
"""`check_no_fix_register.py` 的正反用例回归集。

## 为什么要自测

这关最危险的失败不是「漏报」，而是**自己判错**：锚点正则把反引号里的路径吃错、
把正常待办当成「重复列入」、或登记表被误删却因为解析出 0 条而静默通过。
假红跑几次之后，人就会开始忽略它 —— 于是真的回退也就跟着被忽略了。

尤其要钉住两条：
- **锚点消失必须报，且 D 类要提示「配置被回退」**（这是防回退的核心）；
- **待办里重新列入已判不修的事必须报**（这是防复发的核心）。

用法：`python3 .ai/tools/tests/selftest_no_fix_register.py`
"""

from __future__ import annotations

import subprocess
import sys
import tempfile
from pathlib import Path

TOOL = Path(__file__).resolve().parents[1] / "check_no_fix_register.py"
REG = "Docs/progress/audit/evidence/no-fix-entries-2026-11-11.md"
TODO = "Docs/progress/next-steps.md"
HEAD = """# 登记表

<!-- NO_FIX_REGISTER_BEGIN -->
| ID | 项 | 分型 | 锚点 | 判定理由 | 触发转修条件 | 禁止重新表述 |
|---|---|---|---|---|---|---|
"""
TAIL = "<!-- NO_FIX_REGISTER_END -->\n"

R_A = "| A01 | 冻结项 | A | `cfg/a.txt` → `KEEP_A` | 有决策背书 | 上游发布新版 | — |\n"
R_C = (
    "| C01 | 推迟项 | C | `cfg/b.txt` → `KEEP_B` | 收益为零 | 用户重新拍板 | "
    "WorkManager 周期同步 |\n"
)
R_D = "| D01 | 已做对 | D | `cfg/c.txt` → `KEEP_C` | 勿回退 | 出现真机缺陷 | — |\n"

BASE = {
    REG: HEAD + R_A + R_C + R_D + TAIL,
    TODO: "- [ ] Baseline Profile\n- [ ] Kover 基线\n",
    "cfg/a.txt": "KEEP_A\n",
    "cfg/b.txt": "KEEP_B\n",
    "cfg/c.txt": "KEEP_C\n",
}


def with_files(**changes: str) -> dict[str, str]:
    d = dict(BASE)
    d.update(changes)
    return d


def register(*rows: str) -> dict[str, str]:
    return with_files(**{REG: HEAD + "".join(rows) + TAIL})


CASES: list[tuple[str, dict[str, str], int, str]] = [
    ("① 全部有效 ⇒ 通过", BASE, 0, ""),
    (
        "② A 类锚点消失 ⇒ 报登记失效",
        with_files(**{"cfg/a.txt": "GONE\n"}),
        1,
        "锚点片段已消失",
    ),
    (
        "③ D 类锚点消失 ⇒ 必须提示「配置被回退」",
        with_files(**{"cfg/c.txt": "GONE\n"}),
        1,
        "配置被回退",
    ),
    (
        "④ 待办重新列入已判不修项 ⇒ 必须报",
        with_files(**{TODO: "- [ ] WorkManager 周期同步 + 网络约束\n"}),
        1,
        "重新列入了已判定「不修」的 C01",
    ),
    (
        "⑤ 待办里只是解释历史 ⇒ 不误报",
        with_files(**{TODO: "> 已判推迟：WorkManager 周期同步收益为零\n- [ ] 别的事\n"}),
        0,
        "",
    ),
    ("⑥ ID 重复 ⇒ 报", register(R_A, R_A, R_C, R_D), 1, "ID 重复"),
    (
        "⑦ 缺触发转修条件 ⇒ 报（「不修」≠「永不修」）",
        register("| B01 | 项 | B | `cfg/a.txt` → `KEEP_A` | 理由 | — | — |\n", R_C, R_D),
        1,
        "触发转修条件",
    ),
    (
        "⑧ 分型与 ID 段不一致 ⇒ 报",
        register("| B01 | 项 | A | `cfg/a.txt` → `KEEP_A` | 理由 | 触发 | — |\n", R_C, R_D),
        1,
        "不一致",
    ),
    ("⑨ 标记块缺失 ⇒ 报", with_files(**{REG: "# 没有表格\n"}), 1, "缺失标记块"),
    (
        "⑩ 锚点格式写坏 ⇒ 报",
        register("| A01 | 项 | A | 随便写写 | 理由 | 触发 | — |\n", R_C, R_D),
        1,
        "锚点格式",
    ),
    (
        "⑪ 表格被误删到只剩 1 条 ⇒ 低于基线必须报",
        register(R_A),
        1,
        "低于基线",
    ),
    (
        "⑫ 列数写坏 ⇒ 报",
        register("| A01 | 项 | A | `cfg/a.txt` → `KEEP_A` | 理由 |\n", R_C, R_D),
        1,
        "应为 7 列",
    ),
]


def run(files: dict[str, str]) -> tuple[int, str]:
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        for rel, content in files.items():
            p = root / rel
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text(content, encoding="utf-8")
        r = subprocess.run(
            [sys.executable, str(TOOL), "--root", str(root), "--min-entries", "2", "--quiet"],
            capture_output=True,
            text=True,
            check=False,
        )
        return r.returncode, r.stdout + r.stderr


def main() -> int:
    failed = 0
    for name, files, want_code, want_msg in CASES:
        code, out = run(files)
        ok = code == want_code and (not want_msg or want_msg in out)
        print(f"{'✅' if ok else '❌'} {name}")
        if not ok:
            failed += 1
            print(f"    期望 exit={want_code} 且含 {want_msg!r}；实得 exit={code}\n    {out.strip()[:400]}")
    print(f"\n{len(CASES) - failed}/{len(CASES)} 通过")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
