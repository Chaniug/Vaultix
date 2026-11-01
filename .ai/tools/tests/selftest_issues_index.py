#!/usr/bin/env python3
"""
`check_issues_index.py` 的正反用例回归集（见 01 篇 #104：探针必须配正反用例）。

## 2026-11-01 重写原因

当天按 8.9 §7.1 把 issues 拆成「索引 + `-partN` 正文」，门禁的统计口径随之改为
「同一分类的所有分篇合并后再数」，**明细表也从"比行数"升级成"比编号集合"**
（#160 的原始病灶就是"数量恰好蒙对、名单是错的"）。样例不跟着改，自测会一直红。

## 六种损坏各造一份，每一份都必须被探针抓住

反过来的方向同样重要：探针说"报了"，报的必须是**那几处**，而不是别的东西
（#144 的教训：只看是否有输出，看不出它抓错了东西）。

用法：`python3 .ai/tools/tests/selftest_issues_index.py`
"""

from __future__ import annotations

import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from check_issues_index import check  # noqa: E402  （有意在插路径之后导入）

GOOD_MAIN_JIA = """# ISSUES 分篇 · 甲

> 索引：[`../ISSUES.md`](../ISSUES.md)（编号 → 分篇）· 共 **2** 条：#1、#2

---

## 分篇索引

| 篇 | 覆盖 | 章数 | 大小 |
|---|---|---|---|
| [`甲-样例-part1.md`](./甲-样例-part1.md) | #1 ~ #2 | 2 | 1.0KB |

## 章节明细

| # | 标题 | 篇 |
|---|---|---|
| 1 | 第一条坑 | [part](./甲-样例-part1.md) |
| 2 | 第二条坑 | [part](./甲-样例-part1.md) |
"""

GOOD_PART_JIA = """# 甲-样例 · 第 1 篇（#1 ~ #2）

> 主篇索引：[`甲-样例.md`](./甲-样例.md)

## 1. 第一条坑（测试的）

正文。

## 2. 第二条坑（测试的）

正文。
"""

GOOD_MAIN_YI = """# ISSUES 分篇 · 乙

> 索引：[`../ISSUES.md`](../ISSUES.md)（编号 → 分篇）· 共 **1** 条：#3

---

## 章节明细

| # | 标题 | 篇 |
|---|---|---|
| 3 | 乙篇的坑 | [part](./乙-样例-part1.md) |
"""

GOOD_PART_YI = """# 乙-样例 · 第 1 篇（#3）

> 主篇索引：[`乙-样例.md`](./乙-样例.md)

## 3. 乙篇的坑

正文。
"""

GOOD_INDEX = """# 问题与坑整理

## 编号 → 分篇

### [甲-样例](./issues/甲-样例.md) — 样例（2 条）

> 逐条明细见主篇。

### [乙-样例](./issues/乙-样例.md) — 样例
"""

# ② 分篇正文改编号、明细没动 ⇒ 编号集合不符（数量仍是 2，只有比名单才抓得住）
B_PART_RENUMBER = GOOD_PART_JIA.replace(
    "## 1. 第一条坑（测试的）", "## 7. 第一条坑（编号改成别的）"
)
# ③ 明细少一行 ⇒ 分篇有、明细缺
C_DETAIL_MISSING = GOOD_MAIN_JIA.replace(
    "| 2 | 第二条坑 | [part](./甲-样例-part1.md) |\n", ""
)
# ④ 题注写错、名单却是对的
D_SUMMARY_WRONG = GOOD_MAIN_JIA.replace("共 **2** 条", "共 **5** 条")
# ⑤ 分节标题行「（N 条）」写错，题注与明细全对
E_SECTION_WRONG = GOOD_INDEX.replace("— 样例（2 条）", "— 样例（7 条）")
# ⑥ 分篇多一条、明细没登记
F_PART_EXTRA = GOOD_PART_JIA + (
    "\n## 3. 第三条坑（明细还没登记）\n\n正文。\n"
)


def build(root: Path, main_jia: str, part_jia: str, index: str) -> Path:
    issues = root / ".ai" / "issues"
    issues.mkdir(parents=True, exist_ok=True)
    (issues / "甲-样例.md").write_text(main_jia, encoding="utf-8")
    (issues / "甲-样例-part1.md").write_text(part_jia, encoding="utf-8")
    (issues / "乙-样例.md").write_text(GOOD_MAIN_YI, encoding="utf-8")
    (issues / "乙-样例-part1.md").write_text(GOOD_PART_YI, encoding="utf-8")
    (root / ".ai" / "ISSUES.md").write_text(index, encoding="utf-8")
    return root


CASES = [
    ("① 干净：一致 ⇒ 必须零错", GOOD_MAIN_JIA, GOOD_PART_JIA, GOOD_INDEX, False),
    ("② 分篇改编号、明细没动 ⇒ 编号集合不符（数量仍相等）", GOOD_MAIN_JIA, B_PART_RENUMBER, GOOD_INDEX, True),
    ("③ 明细少一行 ⇒ 分篇有、明细缺", C_DETAIL_MISSING, GOOD_PART_JIA, GOOD_INDEX, True),
    ("④ 题注数字写错、名单却是对的", D_SUMMARY_WRONG, GOOD_PART_JIA, GOOD_INDEX, True),
    ("⑤ 分节标题行「（7 条）」写错、题注与明细全对", GOOD_MAIN_JIA, GOOD_PART_JIA, E_SECTION_WRONG, True),
    ("⑥ 分篇多一条、明细没动", GOOD_MAIN_JIA, F_PART_EXTRA, GOOD_INDEX, True),
]


def main() -> int:
    failures = 0
    for name, main_jia, part_jia, index, expect_errors in CASES:
        with tempfile.TemporaryDirectory() as d:
            root = build(Path(d), main_jia, part_jia, index)
            errors, _ = check(root)
        got = bool(errors)
        ok = got == expect_errors
        # 反向精度：出错的用例里，报错必须落到被改坏的那处（不是随便报点什么）
        detail = ""
        if ok and expect_errors:
            joined = " | ".join(errors)
            if name.startswith("②") and "7" not in joined:
                ok, detail = False, f"没指到具体编号：{joined}"
            if name.startswith("③") and "#2" not in joined:
                ok, detail = False, f"没落在缺失编号：{joined}"
            if name.startswith("④") and "题注" not in joined:
                ok, detail = False, f"没落在题注：{joined}"
            if name.startswith("⑤") and "分节标题行" not in joined:
                ok, detail = False, f"没落在分节标题行：{joined}"
            if name.startswith("⑥") and "#3" not in joined:
                ok, detail = False, f"没落在多出的编号：{joined}"
        print(("✅ " if ok else "❌ ") + name + (f"  ← {detail}" if detail else ""))
        if not ok:
            failures += 1
            for e in errors:
                print(f"      ok？ → {e}")
    print(f"\n{'✅ 全部通过' if not failures else f'❌ {failures} 例失败'}")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
