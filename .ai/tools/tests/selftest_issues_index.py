#!/usr/bin/env python3
"""
`check_issues_index.py` 的正反用例回归集（见 01 篇 #104：探针必须配正反用例）。

四种损坏各造一份：**每一份都必须被探针抓住**，否则探针是假绿的。
反过来的方向同样重要：探针说"报了"，报的必须是**那几行**，而不是别的东西
（正是 #144 那次教训：只看是否有输出，看不出它抓错了东西）。

用法：`python3 .ai/tools/tests/selftest_issues_index.py`
"""

from __future__ import annotations

import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from check_issues_index import check  # noqa: E402  （有意在插路径之后导入）

GOOD_BODY = """# ISSUES 分篇 · 甲

> 索引：[`../ISSUES.md`](../ISSUES.md)（编号 → 分篇）· 共 **2** 条：#1、#2

---

## 1. 第一条坑（测试的）

正文。

## 2. 第二条坑（测试的）

正文。
"""

GOOD_INDEX = """# 问题与坑整理

| 分篇 | 主题 | 条数 |
|---|---|---|
| [甲-样例](./issues/甲-样例.md) | 样例 | 2 |
| [乙-样例](./issues/乙-样例.md) | 样例 | 1 |

### [甲-样例](./issues/甲-样例.md) — 样例（2 条）

| # | 标题 |
|---|---|
| 1 | 第一条坑 |
| 2 | 第二条坑 |

### [乙-样例](./issues/乙-样例.md) — 样例

| # | 标题 |
|---|---|
| 3 | 乙篇的坑 |
"""

B_BODY = GOOD_BODY.replace("## 1. 第一条坑（测试的）", "## 7. 第一条坑（编号改成别的）")
C_INDEX_MISSING_ROW = GOOD_INDEX.replace("| 2 | 第二条坑 |\n", "")
D_INDEX_WRONG_COUNT = GOOD_INDEX.replace("| [甲-样例](./issues/甲-样例.md) | 样例 | 2 |", "| [甲-样例](./issues/甲-样例.md) | 样例 | 5 |")
E_BODY_WRONG_SUMMARY = GOOD_BODY.replace("共 **2** 条", "共 **9** 条")

# 「正文有、索引缺」方向：正文加一条、索引不动
F_BODY_EXTRA = GOOD_BODY + "\n## 3. 第三条坑（索引还没登记）\n\n正文。\n"

# ⑦ 分节标题行里的「（N 条）」写错，而总表/题注/编号表**三处全对**
#    —— 这正是 2026-10-02 探针漏掉的那一处（#160 自称校准了，实际没查它）。
#    如果探针只查三处，本例会静默通过 ⇒ 用例存在的意义就是钉死这条。
G_SECTION_TITLE_COUNT = GOOD_INDEX.replace("— 样例（2 条）", "— 样例（7 条）")


def build(tmp: Path, body: str, index: str) -> Path:
    (tmp / ".ai" / "issues").mkdir(parents=True, exist_ok=True)
    (tmp / ".ai" / "issues" / "甲-样例.md").write_text(body, encoding="utf-8")
    (tmp / ".ai" / "issues" / "乙-样例.md").write_text(
        "# ISSUES 分篇 · 乙\n\n> 索引：[`../ISSUES.md`](../ISSUES.md)（编号 → 分篇）· 共 **1** 条：#3\n\n---\n\n## 3. 乙篇的坑\n\n正文。\n",
        encoding="utf-8",
    )
    (tmp / ".ai" / "ISSUES.md").write_text(index, encoding="utf-8")
    return tmp


CASES = [
    ("① 干净：一致 ⇒ 必须零错", build_body := GOOD_BODY, GOOD_INDEX, False),
    ("② 正文改编号、没改索引 ⇒ 双向不符", B_BODY, GOOD_INDEX, True),
    ("③ 索引少一行 ⇒ 正文有、索引缺", GOOD_BODY, C_INDEX_MISSING_ROW, True),
    ("④ 总表条数列写错 ⇒ 必须报", GOOD_BODY, D_INDEX_WRONG_COUNT, True),
    ("⑤ 题注数字写错、名单却是对的 ⇒ 单看名单发现不了", E_BODY_WRONG_SUMMARY, GOOD_INDEX, True),
    ("⑥ 正文多一条、索引没动 ⇒ 同 ② 的方向", F_BODY_EXTRA, GOOD_INDEX, True),
    ("⑦ 分节标题行「（7 条）」写错、三处全对 ⇒ 只查三处会静默漏掉", GOOD_BODY, G_SECTION_TITLE_COUNT, True),
]


def main() -> int:
    failures = 0
    for name, body, index, expect_errors in CASES:
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            build(root, body, index)
            errors, _ = check(root)
        got = bool(errors)
        ok = got == expect_errors
        # 反向精度：出错的用例里，报错必须落到被改坏的东西上（不是随便报点什么）
        detail = ""
        if ok and expect_errors:
            joined = " | ".join(errors)
            if name.startswith("②") and "7" not in joined:
                ok, detail = False, f"没指到具体编号：{joined}"
            if name.startswith("④") and "总表条数列" not in joined:
                ok, detail = False, f"没落在总表列：{joined}"
            if name.startswith("⑤") and "题注" not in joined:
                ok, detail = False, f"没落在题注：{joined}"
            if name.startswith("⑦") and "分节标题行" not in joined:
                ok, detail = False, f"没落在分节标题行：{joined}"
        print(("✅ " if ok else "❌ ") + name + (f"  ← {detail}" if detail else ""))
        if not ok:
            failures += 1
            for e in errors:
                print(f"      ok？ → {e}")
    print(f"\n{'✅ 全部通过' if not failures else f'❌ {failures} 例失败'}")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
