#!/usr/bin/env python3
"""
ISSUES 索引一致性守护（2026-10-02 新增，见 issues/01 #160；2026-11-01 适配分篇结构）。

## 它守的是什么

`.ai/ISSUES.md` 是**索引**，`issues/*.md` 是**正文**。两者记的是同一件事
（"这个编号是一条坑"），却各存一份 —— 这类"同一事实存两份"必然漂移，本项目已经
漂移过三次（2026-09-21 / 09-26 / 10-02）。

## 2026-11-01 的结构变化（重要）

当天按 8.9 §7.1 把 7 个 issues 篇拆成了 **7 个索引 + 41 个 `-partN` 分篇**：

- `0X-xxx.md` = **索引**：头部题注「共 **N** 条」+「章节明细」表（# → 标题 → part）；
- `0X-xxx-partN.md` = **正文**：`## NN.` 条目在这里。

⇒ 本脚本的统计口径随之改为"**同一分类的所有分篇合并后再数**"，
并新增一条检查：**主篇「章节明细」表的行数** == 合并后的条数
（这条替代了旧的「总表编号集合相等」—— 摘要表已按 §7.4 移出，不再有第二份编号名单）。

## 它查四处（任一不符即 exit 1）

1. **题注数字**：主篇头部「共 **N** 条」 == 该分类所有 part 的 `## NN.` 合计；
2. **章节明细行数**：主篇「章节明细」表行数 == 同一合计；
3. **分节标题**：`ISSUES.md` 分节标题行里的「（N 条）」 == 同一合计
   （⚠️ 这处是 2026-10-02 补的第四份拷贝 —— 它"看起来像说明文字、不像数据"，
   所以三次校准都漏了它；见 #160）；
4. **篇内重号**：同一分类内 `## NN.` 不得重复（`NN.M` 更正子条不计）。

只有**显式写了「（N 条）」**的分节标题行才参与校验（07 篇标题里一个数都没有，
这是合法的 —— 不能反向要求所有标题都带数字）。

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
SUMMARY_RE = re.compile(r"共 \*\*(\d+)\*\* 条")
# 分节标题行里的「（N 条，…）」或「（N 条）」——第四份拷贝，只在写了数字时才校验
SECTION_COUNT_RE = re.compile(r"（(\d+)\s*条")
# 主篇「章节明细」表：`| 12 | 标题 | [part](./01-…-part1.md) |`
DETAIL_ROW_RE = re.compile(r"^\|\s*\d+\s*\|.*\[part\]\(")
# 分篇文件名：`01-构建与环境-part2`
PART_RE = re.compile(r"^(.*)-part\d+$")

# 已知的历史重号（跨独立的两个坑共用一号）。列出它们是**为了不再困惑**，
# 不是承认其合理 —— 改编号会让代码注释里的 `见 .ai/ISSUES.md #NN` 全部失真，
# 因此维持编号、要求在引用处写明分篇。
KNOWN_DUPLICATES = {
    50: "02-自动填充 vs 03-通行密钥",
    103: "01-构建与环境 vs 06-界面与交互",
    190: "05-KDBX本地库 vs 07-数据与同步 vs 03-通行密钥（同一轮三个视角，正文主条目在 05 篇）",
}


def collect(p: Path) -> dict[str, list[int]]:
    """按**分类**聚合条目编号：主篇 + 它的所有 `-partN` 一起数。"""
    out: dict[str, list[int]] = {}
    for f in sorted((p / ".ai" / "issues").glob("*.md")):
        m = PART_RE.match(f.stem)
        stem = m.group(1) if m else f.stem
        nums = [
            int(x.group(1))
            for ln in f.read_text(encoding="utf-8").splitlines()
            if (x := ENTRY_RE.match(ln))
        ]
        out.setdefault(stem, []).extend(nums)
    return out


def split_index_blocks(idx: str) -> dict[str, str]:
    """把 ISSUES.md 切成「篇名 → 该篇分节区域」。"""
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
        if not f.exists():
            errors.append(f"{stem}: 主篇索引不存在")
            continue
        text = f.read_text(encoding="utf-8")

        # ① 题注数字
        head = next(
            (ln for ln in text.splitlines() if ln.lstrip("> ").startswith("索引：")), ""
        )
        m = SUMMARY_RE.search(head)
        if not m:
            errors.append(f"{stem}: 头部题注缺失或没有『共 **N** 条』")
        elif int(m.group(1)) != len(nums):
            errors.append(f"{stem}: 头部题注写 {m.group(1)} 条，分篇合计 {len(nums)} 条")

        # ② 章节明细表行数（替代已移出的「总表编号集合相等」）
        detail = [ln for ln in text.splitlines() if DETAIL_ROW_RE.match(ln)]
        if len(detail) != len(nums):
            errors.append(
                f"{stem}: 章节明细 {len(detail)} 行，分篇合计 {len(nums)} 条"
            )

        # ③ 篇内重号（合并后检查：跨 part 重复也算）
        dup = sorted({n for n in nums if nums.count(n) > 1})
        if dup:
            errors.append(f"{stem}: 篇内重号 → #{', #'.join(map(str, dup))}")

        # ④ 分节标题行里的「（N 条）」
        blk = blocks.get(stem)
        if blk is None:
            errors.append(f"{stem}: ISSUES.md 里没有这一篇的分节")
            continue
        head_line = next((ln for ln in blk.splitlines() if ln.strip()), "")
        ms = SECTION_COUNT_RE.search(head_line)
        if ms and int(ms.group(1)) != len(nums):
            errors.append(
                f"{stem}: 分节标题行写「（{ms.group(1)} 条）」，分篇合计 {len(nums)} 条"
            )

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
