#!/usr/bin/env python3
"""
ISSUES 索引一致性守护（2026-10-02 双库审计期间新增，见 issues/01 #160）。

## 它守的是什么

`.ai/ISSUES.md` 是**索引**，各 `issues/*.md` 是**正文**。两者记的是同一件事
（"这个编号是一条坑"），却各存一份 —— 这类"同一事实存两份"必然漂移，本项目已经
漂移过三次（2026-09-21 / 09-26 / 10-02）。第三次尤其隐蔽：

- 01 篇四条独立条目（#102–#105）被写成 `###` 三级标题 ⇒ 按"只数 `## NN.`"的规则
  **从未被计入**，视觉上还被埋在 #101 的层级里，看着像它的子条；
- 06 篇题注用了**增量写法**（旧值 33 + 本次新增 1 = 34），于是**上一轮刚加的
  #142–#144 从来没进过名单**；
- 两张编号表里有重号与错位（#83 无正文、#88 归错篇、#128 重复两行）。

⇒ 光靠"改哪篇顺手改哪个数字"已经被证伪三次。改由脚本全量重数。

## 它查三件事（任一不符即 exit 1）

1. **编号集合相等**：某分篇正文里的 `## NN.` 集合 == `ISSUES.md` 该篇表格里的编号集合。
2. **题注数字相等**：该分篇头部「共 **N** 条」 == 实际 `## NN.` 条数。
   ⚠️ 两者**互不可证**：03 篇曾出现"写 18 条、只列 16 个号" —— 数字对了名单还是错的。
3. **索引总表（）顶部的「条数」列** == 实测值。

外加一条**警告**（不置失败）：跨篇重号的编号列表 —— 它们存在且短期内不会改编号，
但引用时必须带分篇名（见 KNOWN_DUPLICATES）。

## 用法

    python3 .ai/tools/check_issues_index.py [--root <仓库根>]

退出码：`0` 一致 / `1` 有不符。
"""

from __future__ import annotations

import argparse
import re
import sys
from collections import defaultdict
from pathlib import Path

# 正式条目：`## NN.`（`NN.M` 更正子条不计）
ENTRY_RE = re.compile(r"^## (\d+)\.(?!\d)")
INDEX_LINE_RE = re.compile(r"^\| (\d+) \|")
COUNT_ROW_RE = re.compile(r"^\| \[([^\]]+)\]\([^)]+\) \| [^|]+\| *(\d+) *\|")
SUMMARY_RE = re.compile(r"共 \*\*(\d+)\*\* 条")

# 已知的历史重号（跨独立的两个坑共用一号）。列出它们是**为了不再困惑**，
# 不是承认其合理 —— 改编号会让代码注释里的 `见 .ai/ISSUES.md #NN` 全部失真，
# 因此维持编号、要求在引用处写明分篇。
KNOWN_DUPLICATES = {50: "02-自动填充 vs 03-通行密钥", 103: "01-构建与环境 vs 06-界面与交互"}


def collect(p: Path) -> dict[str, list[int]]:
    """各分篇的正文条目编号列表。"""
    out: dict[str, list[int]] = {}
    for f in sorted((p / ".ai" / "issues").glob("*.md")):
        nums = [int(m.group(1)) for ln in f.read_text(encoding="utf-8").splitlines() if (m := ENTRY_RE.match(ln))]
        out[f.stem] = nums
    return out


def split_index_blocks(idx: str) -> dict[str, str]:
    """把 ISSUES.md 切成「篇名 → 该篇编号表区域」。"""
    blocks: dict[str, str] = {}
    for chunk in idx.split("### [")[1:]:
        name = chunk.split("]")[0]
        blocks[name] = chunk
    return blocks


def check(root: Path) -> tuple[list[str], list[str]]:
    """返回 (errors, warnings)。"""
    errors: list[str] = []
    warnings: list[str] = []
    bodies = collect(root)
    idx_path = root / ".ai" / "ISSUES.md"
    if not idx_path.exists():
        return [f"找不到 {idx_path}"], warnings
    idx_text = idx_path.read_text(encoding="utf-8")
    blocks = split_index_blocks(idx_text)

    for stem, nums in bodies.items():
        f = root / ".ai" / "issues" / f"{stem}.md"
        head = next((ln for ln in f.read_text(encoding="utf-8").splitlines() if ln.lstrip("> ").startswith("索引：")), "")
        m = SUMMARY_RE.search(head)
        if not m:
            errors.append(f"{stem}: 头部题注缺失或没有『共 **N** 条』")
            continue
        claimed = int(m.group(1))
        if claimed != len(nums):
            errors.append(f"{stem}: 头部题注写 {claimed} 条，正文实测 {len(nums)} 条")

        blk = blocks.get(stem)
        if blk is None:
            errors.append(f"{stem}: 索引里没有这一篇的编号表")
            continue
        table_nums = [int(m.group(1)) for ln in blk.splitlines() if (m := INDEX_LINE_RE.match(ln))]
        only_body = sorted(set(nums) - set(table_nums))
        only_idx = sorted(set(table_nums) - set(nums))
        if only_body:
            errors.append(f"{stem}: 正文有、索引缺 → #{', #'.join(map(str, only_body))}")
        if only_idx:
            errors.append(f"{stem}: 索引有、正文缺 → #{', #'.join(map(str, only_idx))}")
        dup = sorted({n for n in nums if nums.count(n) > 1})
        if dup:
            errors.append(f"{stem}: 篇内重号 → #{', #'.join(map(str, dup))}")

        # 总表「条数」列
        for line in idx_text.splitlines():
            mm = COUNT_ROW_RE.match(line)
            if mm and mm.group(1).startswith(stem):
                if int(mm.group(2)) != len(nums):
                    errors.append(f"总表条数列: {stem} 写 {mm.group(2)}，实测 {len(nums)}")
                break
        else:
            errors.append(f"总表条数列没有 {stem} 这一行")

    # 跨篇重号
    where = defaultdict(list)
    for stem, nums in bodies.items():
        for n in set(nums):
            where[n].append(stem)
    for n, stems in sorted(where.items()):
        if len(stems) > 1:
            known = KNOWN_DUPLICATES.get(n)
            tag = f"（已知：{known}）" if known else "（⚠️ 未登记的新重号，请加进 KNOWN_DUPLICATES）"
            warnings.append(f"#{n} 出现在多篇：{' / '.join(stems)} {tag}")
    return errors, warnings


def main() -> int:
    ap = argparse.ArgumentParser(description="ISSUES 索引一致性检查")
    ap.add_argument("--root", default=".", type=Path, help="仓库根目录")
    args = ap.parse_args()
    errors, warnings = check(args.root)
    for w in warnings:
        print(f"⚠️  {w}")
    for e in errors:
        print(f"❌ {e}")
    if errors:
        print(f"\n共 {len(errors)} 处不符")
        return 1
    print("✅ 索引 = 题注 = 正文，全部一致")
    return 0


if __name__ == "__main__":
    sys.exit(main())
