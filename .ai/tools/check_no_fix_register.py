#!/usr/bin/env python3
"""「不需要修」登记表的守门人：防止误报被重新列成待办、防止登记悄悄失效。

## 为什么需要这个

项目里**早就有**「不做登记」的惯例：
`.ai/decisions/快速解锁房子化-两级钥匙层级-定稿-part3.md` 的
「§11 被否 / 刻意不学（连同理由，避免新会话重复提议）」、
`.ai/decisions/条目页-M3-视觉方案.md` 的「附：不做的事（明确排除）」。

但它们是**散落在各篇决策里的人读文字**。整体排查时扫描器不会因为读过它们就少吐一行，
于是 159 条「注释缺空格」、52 条「硬编码密钥」、131 个「超长方法」每轮都会被
重新输出、重新被人（或 AI）当成待办。同样地，「composeBom / material3 /
markdownRenderer 三项禁单独升」写在版本目录注释里，Dependabot 也读不到。

⇒ **手工核对一次不算，要变成脚本**（与 `check_compile_smells.py` 同一条教训）。

本关做三件事：

| 检查 | 抓什么 |
|---|---|
| ① 登记完整性 | 表格被误删、列写坏、ID 重复、分型与 ID 段不一致 |
| ② 锚点有效性 | 代码 / 配置被改了，登记的前提不再成立 ⇒ **必须有人复核**，不能静默放过 |
| ③ 防重复列入待办 | 下一轮又把已判定不修的事写成 `- [ ]` |

② 是「触发转修条件」的自动化：D 类锚点消失通常是配置被回退，
A 类锚点消失通常是有人动了冻结的版本键 —— 两种都要看一眼。

## 唯一真源

`Docs/progress/audit/evidence/no-fix-entries-2026-11-11.md` 里
`NO_FIX_REGISTER_BEGIN` / `NO_FIX_REGISTER_END` 之间的 Markdown 表格。
**本脚本不内置任何条目**，改登记只改那张表（避免「同一事实存两份」，#160 的老毛病）。

表格 7 列：`ID | 项 | 分型 | 锚点 | 判定理由 | 触发转修条件 | 禁止重新表述`

- 锚点格式：`` `文件路径` → `必须仍存在的片段` ``
- 禁止重新表述：`—` 表示不设；多个短语用中文分号 `；` 分隔

## 用法

    python3 .ai/tools/check_no_fix_register.py                 # 扫当前仓库
    python3 .ai/tools/check_no_fix_register.py --quiet        # 只打印失败
    python3 .ai/tools/check_no_fix_register.py --list         # 打印登记表概览后正常校验
    python3 .ai/tools/check_no_fix_register.py --root <dir>   # 自测 / 指定仓库根
    python3 .ai/tools/check_no_fix_register.py <dir>          # 位置参数等价 --root

退出码：0 = 全部通过；1 = 存在失败项。
"""

from __future__ import annotations

import argparse
import os
import re
import sys
from collections import Counter
from dataclasses import dataclass
from pathlib import Path

REGISTER_REL = Path("Docs/progress/audit/evidence/no-fix-entries-2026-11-11.md")
TODO_REL = Path("Docs/progress/next-steps.md")
BEGIN = "<!-- NO_FIX_REGISTER_BEGIN -->"
END = "<!-- NO_FIX_REGISTER_END -->"

# 当前登记 52 条。低于这个数说明表格被误删了一大块，而不是「正常减少」。
# 若将来确有条目转修而降到基线以下，应同步下调本常量并写明原因。
MIN_ENTRIES = 40
VALID_KINDS = {"A", "B", "C", "D", "E"}
ID_RE = re.compile(r"^([A-E])(\d{2})$")
ANCHOR_RE = re.compile(r"`([^`]+)`\s*→\s*`([^`]+)`")
COLUMNS = 7


@dataclass(frozen=True)
class Entry:
    id: str
    item: str
    kind: str
    anchor: str
    reason: str
    trigger: str
    forbidden: str
    line_no: int


def repo_root_of(argv_root: str | None) -> Path:
    if argv_root:
        return Path(argv_root).resolve()
    # .ai/tools/<this>.py → 上三级是仓库根
    return Path(__file__).resolve().parents[2]


def fail(errors: list[str], msg: str) -> None:
    errors.append(msg)


def parse_register(text: str, errors: list[str], min_entries: int = MIN_ENTRIES) -> list[Entry]:
    if BEGIN not in text or END not in text:
        fail(errors, f"登记表缺失标记块：找不到 {BEGIN} / {END}")
        return []
    block = text.split(BEGIN, 1)[1].split(END, 1)[0]
    rows = [ln for ln in block.splitlines() if ln.lstrip().startswith("|")]
    # 丢弃表头与 |---| 分隔行
    body: list[tuple[int, str]] = []
    for idx, ln in enumerate(rows, start=1):
        cells = [c.strip() for c in ln.strip().strip("|").split("|")]
        if all(re.fullmatch(r":?-{2,}:?", c or "") for c in cells):
            continue
        if cells and cells[0].lower() in {"id", "序号"}:
            continue
        body.append((idx, ln))

    entries: list[Entry] = []
    for idx, ln in body:
        cells = [c.strip() for c in ln.strip().strip("|").split("|")]
        if len(cells) != COLUMNS:
            fail(errors, f"登记表第 {idx} 行：应为 {COLUMNS} 列，实为 {len(cells)} 列 → {ln[:90]}")
            continue
        e = Entry(*cells, idx)  # type: ignore[arg-type]
        m = ID_RE.match(e.id)
        if not m:
            fail(errors, f"登记表第 {idx} 行：ID 格式应为 [A-E]+两位数字，实为 {e.id!r}")
            continue
        if e.kind not in VALID_KINDS:
            fail(errors, f"{e.id}：分型 {e.kind!r} 不在 A/B/C/D/E 内")
        elif m.group(1) != e.kind:
            fail(errors, f"{e.id}：ID 段 {m.group(1)} 与分型 {e.kind} 不一致")
        if not ANCHOR_RE.search(e.anchor):
            fail(errors, f"{e.id}：锚点格式应为 `路径` → `片段`，实为 {e.anchor!r}")
        if not e.reason or e.reason == "—":
            fail(errors, f"{e.id}：缺「判定理由」—— 说不出凭什么不修，不允许登记")
        if not e.trigger or e.trigger == "—":
            fail(errors, f"{e.id}：缺「触发转修条件」—— 「不修」不是「永不修」")
        entries.append(e)

    if len(entries) < min_entries:
        fail(errors, f"登记表只有 {len(entries)} 条，低于基线 {min_entries} 条（表格被误删？）")

    dup = [k for k, v in Counter(e.id for e in entries).items() if v > 1]
    if dup:
        fail(errors, f"ID 重复：{', '.join(sorted(dup))}")
    return entries


def check_anchors(root: Path, entries: list[Entry], errors: list[str]) -> int:
    ok = 0
    cache: dict[str, str] = {}
    for e in entries:
        m = ANCHOR_RE.search(e.anchor)
        if not m:
            continue
        rel, fragment = m.group(1), m.group(2)
        path = root / rel
        if not path.is_file():
            fail(errors, f"{e.id}：锚点文件不存在 → {rel}")
            continue
        if rel not in cache:
            cache[rel] = path.read_text(encoding="utf-8", errors="replace")
        if fragment not in cache[rel]:
            hint = "（D 类：这通常是配置被回退）" if e.kind == "D" else (
                "（A 类：这可能意味着冻结的版本键被改动）" if e.kind == "A" else ""
            )
            fail(
                errors,
                f"{e.id}：锚点片段已消失 → {rel} 中找不到 {fragment!r}{hint}。"
                f"请复核：恢复锚点，或把该条判定为「已转修」并移出登记表。",
            )
            continue
        ok += 1
    return ok


def unchecked_blocks(text: str) -> list[str]:
    """收集待办文件里所有未完成项（`- [ ]` 行 + 其后缩进续行）。"""
    lines = text.splitlines()
    blocks: list[str] = []
    cur: list[str] | None = None
    for ln in lines:
        if re.match(r"^\s*- \[ \]", ln):
            if cur:
                blocks.append("\n".join(cur))
            cur = [ln]
        elif cur is not None:
            if re.match(r"^\s+\S", ln) and not re.match(r"^\s*- \[[ x]\]", ln):
                cur.append(ln)
            elif ln.strip() and not ln.startswith(" ") and not ln.startswith("#"):
                blocks.append("\n".join(cur))
                cur = None
            elif not ln.strip():
                blocks.append("\n".join(cur))
                cur = None
            else:
                blocks.append("\n".join(cur))
                cur = None
    if cur:
        blocks.append("\n".join(cur))
    return blocks


def check_todos(root: Path, entries: list[Entry], errors: list[str]) -> int:
    path = root / TODO_REL
    if not path.is_file():
        fail(errors, f"待办文件不存在 → {TODO_REL}")
        return 0
    blocks = unchecked_blocks(path.read_text(encoding="utf-8", errors="replace"))
    hits = 0
    for e in entries:
        if not e.forbidden or e.forbidden == "—":
            continue
        for phrase in (p.strip() for p in e.forbidden.split("；")):
            if not phrase:
                continue
            for b in blocks:
                if phrase in b:
                    fail(
                        errors,
                        f"待办里重新列入了已判定「不修」的 {e.id}（{e.item}）：命中 {phrase!r}。"
                        f"若确已触发转修，请先更新登记表再写待办。",
                    )
                    hits += 1
                    break
    return hits


def print_summary(entries: list[Entry]) -> None:
    kinds = Counter(e.kind for e in entries)
    guarded = sum(1 for e in entries if e.forbidden and e.forbidden != "—")
    print(
        f"登记表 {len(entries)} 条"
        f"（A {kinds['A']} / B {kinds['B']} / C {kinds['C']} / D {kinds['D']} / E {kinds['E']}），"
        f"其中 {guarded} 条设了「禁止重新表述」"
    )
    print("分型：A 刻意冻结 · B 静态误报 · C 已判推迟 · D 已经做对 · E 显式豁免")


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="校验「不需要修」登记表")
    ap.add_argument("root_pos", nargs="?", help="仓库根（位置参数）")
    ap.add_argument("--root", help="仓库根")
    ap.add_argument("--quiet", action="store_true", help="只打印失败项")
    ap.add_argument("--list", action="store_true", help="打印登记表概览")
    ap.add_argument("--min-entries", type=int, default=MIN_ENTRIES, help="条目数下限（自测用）")
    args = ap.parse_args(argv)

    root = repo_root_of(args.root or args.root_pos)
    reg = root / REGISTER_REL
    if not reg.is_file():
        print(f"❌ 找不到登记表 → {reg}", file=sys.stderr)
        return 1
    errors: list[str] = []
    entries = parse_register(
        reg.read_text(encoding="utf-8", errors="replace"), errors, min_entries=args.min_entries
    )
    if args.list and not args.quiet:
        print_summary(entries)
    anchored = check_anchors(root, entries, errors)
    check_todos(root, entries, errors)

    if errors:
        print(f"❌ 不修登记校验失败（{len(errors)} 项）：", file=sys.stderr)
        for e in errors:
            print(f"   - {e}", file=sys.stderr)
        return 1
    if not args.quiet:
        print(f"✅ 不修登记 {len(entries)} 条：锚点 {anchored}/{len(entries)} 有效，待办无重复列入")
    return 0


if __name__ == "__main__":
    sys.exit(main())
