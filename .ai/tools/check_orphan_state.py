#!/usr/bin/env python3
"""Compose 的状态变量是不是「只写不读」？——写进了状态，却没有任何地方拿它渲染。

## 为什么需要这个

2026-09-30 真机实录：给设置首页加回「自动锁定」行时，我把

    var showAutoLockDialog by rememberSaveable { mutableStateOf(false) }
    ...
    onAutoLock = { showAutoLockDialog = true },

都写上了，**却漏了渲染那一半**：

    if (showAutoLockDialog) { AutoLockDialog(...) }

症状：点那行**毫无反应**（用户原话「自动锁定选项不能选取」）。

## 为什么当时五道门禁全都没拦住

| 手段 | 为什么看不见 |
|---|---|
| Kotlin 编译器 | `by` 委托的**局部**变量**不触发** "variable is never used" —— 实测 `--rerun-tasks` 全量重编也**零条** warning |
| detekt | 它的 unused 规则面向 private 属性 / 参数，不解析局部委托变量 |
| 增量编译 | 无输出；任务 UP-TO-DATE 也照样"绿" |
| 孤儿串探针 | `setting_auto_lock` 等在**别处**仍被引用 ⇒ 不是孤儿 |
| 单测 | 本项目没有 Compose UI 测试 |

⇒ 判据是**纯静态可判定**的：「这个标识符是否只出现在"声明"与"赋值左侧"」。
   这类错误不存在"合理的不读"，所以阈值是**零容忍**（不是基线制，与孤儿串探针不同）。

## 覆盖形状

    var NAME by remember { ... }        /  var NAME by rememberSaveable { ... }
    val NAME = remember { mutableStateOf(...) }   ← 也算（读法为 NAME.value）

## ⚠️ 已知局限（别当成"全覆盖"）

1. **同名变量在同一文件里出现多次**时，计数是**按名字合并**的 ⇒ 若两个同名状态里
   只有一个被读，本探针会放过（不报）。这是有意的取舍：宁可漏报，也不要因误报
   被当成噪声而整体忽略（`--verbose` 会列出计数明细供人核对）。
2. 只扫 `app/src/main/java` 下的 `.kt`（UI 都在那里）。
3. 只判"有没有读"，**判不了"读得对不对"**（比如 `if (showX) { /* 空 */ }`）。

## 用法

    python3 .ai/tools/check_orphan_state.py             # 列出只写不读的状态变量
    python3 .ai/tools/check_orphan_state.py --quiet     # 只出统计
    python3 .ai/tools/check_orphan_state.py --verbose    # 附带每个变量的计数明细

退出码：0 = 干净；1 = 发现只写不读的状态变量。

自测：`python3 .ai/tools/tests/selftest_orphan_state.py`
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SCAN_ROOT = ROOT / "app" / "src" / "main" / "java"

# `var NAME by remember` / `var NAME by rememberSaveable` /
# `var NAME = remember` / `val NAME = remember`
#
# ⚠️ 别把两种形态写成"先匹配 `by remember` 再要求一个 `remember`"——
#    那正是本探针第一版的 bug（`by rememberSaveable` 之后没有第二个 remember ⇒ 一个都匹配不到，
#    探针静默返回"全仓干净"）。自测 `selftest_orphan_state.py` 当场把它逮了出来。
DECL = re.compile(
    r"\b(?:var|val)\s+([A-Za-z_][A-Za-z0-9_]*)\s*(?:=\s*remember\w*|by\s+remember\w*)"
)

# 写入 = `NAME = ...`（排除 `==` / `>=` / `<=` / `!=` / 复合赋值）
#      ∪ `NAME.value = ...`（`val x = remember { mutableStateOf(..) }` 形态的写法）
#
# ⚠️ 必须**排除声明自身那个 `=`**（`val barPadding = rememberImmersiveBarPadding(..)`）——
#    不排除的话，凡 `val x = remember…` + 一处 `x.属性` 的正常代码都会被算成
#    "出现 2 = 声明 1 + 赋值 1"（误报）。第一版就是这样在全仓报了 22 处假阳性。
WRITE_TEMPLATE = (
    r"(?<![=!<>+\-*/])(?<!val )(?<!var )\b{name}\s*=(?!=)"
    r"|(?<![=!<>+\-*/])\b{name}\.value\s*=(?!=)"
)


def occurrences(text: str, name: str) -> int:
    """标识符在本文件里出现的总次数（含声明与赋值）。"""
    return len(re.findall(rf"\b{re.escape(name)}\b", text))


def writes(text: str, name: str) -> int:
    """**写入**次数（赋值左侧 / `.value =`）；声明自身不计入。"""
    pattern = re.compile(WRITE_TEMPLATE.format(name=re.escape(name)))
    return len(pattern.findall(text))


def declarations(text: str, name: str) -> int:
    return len(re.findall(rf"\b(?:var|val)\s+{re.escape(name)}\b", text))


def scan_file(path: Path) -> list[tuple[str, int, int, int]]:
    """返回 [(变量名, 出现次数, 写入次数, 声明次数)] —— 只含"只写不读"的候选。"""
    try:
        text = path.read_text(encoding="utf-8")
    except (OSError, UnicodeDecodeError):
        return []

    # 先按名字合并计数（同一文件内的同名变量共享计数，见文件头的"已知局限"）。
    names = {m.group(1) for m in DECL.finditer(text)}
    found = []
    for name in sorted(names):
        total = occurrences(text, name)
        written = writes(text, name)
        decls = declarations(text, name)
        # 去掉声明与写入之后**一次读取都不剩** ⇒ 只写不读
        if total - decls - written <= 0:
            found.append((name, total, written, decls))
    return found


def main() -> int:
    parser = argparse.ArgumentParser(description="检出只会被写入、从不被读取的 Compose 状态变量")
    parser.add_argument("--quiet", action="store_true", help="只出统计，不出清单")
    parser.add_argument("--verbose", action="store_true", help="附带计数明细")
    args = parser.parse_args()

    if not SCAN_ROOT.exists():
        print(f"[error] 找不到扫描目录：{SCAN_ROOT}", file=sys.stderr)
        return 2

    files = sorted(SCAN_ROOT.rglob("*.kt"))
    hits: list[tuple[Path, str, int, int, int]] = []
    for path in files:
        for name, total, assigns, decls in scan_file(path):
            hits.append((path, name, total, assigns, decls))

    print(f"[info] 扫描 {len(files)} 个 .kt 文件，发现 {len(hits)} 个「只写不读」的状态变量。")

    if hits and not args.quiet:
        print()
        print("⚠️ 这些变量被写入了状态，但**没有任何地方拿它渲染** ——")
        print("   典型症状是「点了没反应 / 状态切了但界面不变」。")
        print("   多半是漏了 `if (flag) { SomeDialog(...) }` 那一半。")
        print()
        for path, name, total, assigns, decls in hits:
            rel = path.relative_to(ROOT).as_posix()
            if args.verbose:
                print(f"  {rel}: {name}  (出现 {total} = 声明 {decls} + 赋值 {assigns})")
            else:
                print(f"  {rel}: {name}")

    return 1 if hits else 0


if __name__ == "__main__":
    sys.exit(main())
