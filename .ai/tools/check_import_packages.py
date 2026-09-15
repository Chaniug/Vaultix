#!/usr/bin/env python3
"""`import` 里的**包路径**写对了吗？

## 为什么需要这个（与 check_signature_types.py 的分工）

`check_signature_types.py` 校验的是**函数签名里的类型名是否存在**。
它**只看名字、不看 import**，所以下面这类错误它完全看不见：

    // 符号名写对了，但包路径写错 —— 编译不过，然而名字"看起来"都存在
    import androidx.compose.material3.WindowInsets        // ❌ 不存在
    import androidx.compose.foundation.layout.WindowInsets // ✅ 正确

detekt 同样查不出：它只跑静态规则集，**不做符号解析、不做类型检查**。

2026-09-15 实录：`PasskeysScreen.kt` 误导入 `androidx.compose.material3.WindowInsets`，
本地 detekt 全绿、自检工具也全绿，**只有 CI 编译才炸**：

    e: PasskeysScreen.kt:47:35  Unresolved reference 'WindowInsets'.
    e: PasskeysScreen.kt:137:73 Unresolved reference 'WindowInsets'.

整个仓库里 `WindowInsets` 被正确地导入自 `foundation.layout` 的有 8 个文件 ——
也就是说这个错误的答案就在仓库里，只是没有一个工具去比对。

## 判据（两条，都是"用仓库自己当基准"）

**规则 A：同名符号的"独占包"一致性**
   只对**在整个仓库里只从一个包导入过**的符号生效
   （如 `WindowInsets` 全仓库 8 处、全部来自 foundation.layout）。
   这种"独占包"一旦被打破，几乎必然是写错了。

   ⚠️ 若某个名字在多个包里都出现过（哪怕只出现一次），就**跳过不查** ——
   因为 Kotlin/Compose 里确实存在同名重载或跨包迁移的 API：
     - `lerp` 同时在 ui.graphics（颜色插值）与 ui.unit（Dp 插值）下；
     - `LocalLifecycleOwner` 同时在 compose.ui.platform（旧）与
       lifecycle.compose（新）下。
   这两类都是**合法**用法。2026-09-15 首版用"多数包"判据时，
   正是把这两个误报成了错误 —— 故改为"独占包"判据，宁可漏报不可误报。

**规则 B：已知跨包陷阱表**
   少数 Compose 符号在多个包下**同名但语义不同**，
   仓库里可能长期只用到其中一个，规则 A 的样本量不足。
   对这些名字查一张硬编码表。

退出码：0 = 没发现问题；1 = 有可疑 import。

## 用法

    python3 .ai/tools/check_import_packages.py            # 扫全部 .kt
    python3 .ai/tools/check_import_packages.py <文件>...  # 只扫指定文件
    python3 .ai/tools/check_import_packages.py --changed  # 只扫最近一次提交改动的
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SRC_ROOT = ROOT / "app" / "src"

IMPORT_RE = re.compile(r"^\s*import\s+([\w.]+)(?:\s+as\s+(\w+))?", re.MULTILINE)

# ---------------------------------------------------------------------------
# 规则 B：已知的「同名不同包」陷阱
# ---------------------------------------------------------------------------
# 只登记**确实容易记混**的。每一条都写清楚为什么。
# value = 本仓库应当使用的正确包。
KNOWN_PACKAGE_TRAPS: dict[str, str] = {
    # WindowInsets 在 material3 下**不存在**（material3 用的是 WindowInsets 的别名
    # 与 ScaffoldDefaults）；真正的定义在 foundation.layout。
    "WindowInsets": "androidx.compose.foundation.layout",
    "WindowInsetsCompat": "androidx.core.view",
    # Modifier 是 foundation.layout 里的接口；material3 不导出同名顶层类型。
    "Modifier": "androidx.compose.ui",
    # 这三个都是 foundation 的，material3 下没有。
    "BasicTextField": "androidx.compose.foundation.text",
    "LazyColumn": "androidx.compose.foundation.lazy",
    "LazyRow": "androidx.compose.foundation.lazy",
}


def simple_name(path: str, alias: str | None) -> str:
    return alias or path.rsplit(".", 1)[-1]


def package_of(path: str) -> str:
    return path.rsplit(".", 1)[0]


def iter_kt_files() -> list[Path]:
    return sorted(SRC_ROOT.rglob("*.kt"))


def changed_files() -> list[Path]:
    for rev in (["HEAD~1", "HEAD"], ["HEAD"]):
        try:
            out = subprocess.run(
                ["git", "diff", "--name-only", *rev],
                cwd=ROOT, capture_output=True, text=True, check=True,
            ).stdout
        except (subprocess.CalledProcessError, FileNotFoundError):
            continue
        files = [ROOT / l.strip() for l in out.splitlines() if l.strip()]
        files = [f for f in files if f.suffix == ".kt" and f.exists()]
        if files:
            return files
    return []


def build_exclusive_map(files: list[Path]) -> dict[str, str]:
    """扫全仓库，得出「简单名 → 唯一包」，只保留**全仓库仅从一个包导入过**的符号。

    这类符号是"独占包"，一旦某处从别的包导入，几乎必然是写错了。
    反之，只要该名字在 ≥2 个不同包里出现过，就认定为合法的跨包同名
    （重载或 API 迁移），整体跳过 —— 宁可漏报，不可误报。
    """
    tally: dict[str, dict[str, int]] = defaultdict(lambda: defaultdict(int))
    for path in files:
        try:
            text = path.read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue
        for m in IMPORT_RE.finditer(text):
            fp, alias = m.group(1), m.group(2)
            name = simple_name(fp, alias)
            tally[name][package_of(fp)] += 1

    exclusive: dict[str, str] = {}
    for name, pkgs in tally.items():
        if len(pkgs) == 1:                      # 全仓库只有一个包 ⇒ 独占
            only_pkg, n = next(iter(pkgs.items()))
            if n >= 2:                          # 至少要有 2 个样本才算"约定"
                exclusive[name] = only_pkg
    return exclusive


def check_file(
    path: Path, exclusive: dict[str, str]
) -> list[tuple[int, str, str, str]]:
    """返回 [(行号, 简单名, 实际包, 期望包)]。"""
    try:
        text = path.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return []

    problems: list[tuple[int, str, str, str]] = []
    for m in IMPORT_RE.finditer(text):
        fp, alias = m.group(1), m.group(2)
        name = simple_name(fp, alias)
        actual_pkg = package_of(fp)
        line_no = text.count("\n", 0, m.start()) + 1

        # 规则 B 优先：陷阱表是硬知识，比统计更可信
        expected = KNOWN_PACKAGE_TRAPS.get(name)
        if expected is not None:
            if actual_pkg != expected:
                problems.append((line_no, name, actual_pkg, expected))
            continue

        # 规则 A：独占包一致性
        # 仅对 androidx.* / io.vaultix.* 生效，避免误伤第三方库的合法同名。
        if not (fp.startswith("androidx.") or fp.startswith("io.vaultix.")):
            continue
        want = exclusive.get(name)
        if want is not None and actual_pkg != want:
            problems.append((line_no, name, actual_pkg, want))
    return problems


def _display_path(path: Path) -> Path:
    """尽量显示成相对仓库根的路径；仓库外的文件（测试用临时副本）原样显示。"""
    try:
        return path.relative_to(ROOT)
    except ValueError:
        return path


def main() -> int:
    ap = argparse.ArgumentParser(description="校验 import 的包路径是否与仓库约定一致")
    ap.add_argument("files", nargs="*", help="要检查的 .kt 文件；缺省=全部")
    ap.add_argument("--changed", action="store_true", help="只扫最近一次提交改动的文件")
    args = ap.parse_args()

    all_files = iter_kt_files()
    exclusive = build_exclusive_map(all_files)
    print(f"[info] 扫描 {len(all_files)} 个 .kt，"
          f"识别出 {len(exclusive)} 个「全仓库独占单一包」的符号")

    if args.changed:
        targets = changed_files()
    elif args.files:
        targets = [Path(f).resolve() for f in args.files]
    else:
        targets = all_files

    if not targets:
        print("[warn] 没有要检查的文件")
        return 0

    total = 0
    for path in targets:
        if not path.exists() or path.suffix != ".kt":
            continue
        problems = check_file(path, exclusive)
        if problems:
            rel = _display_path(path)
            print(f"\n{rel}")
            for line_no, name, actual, expected in problems:
                print(f"  L{line_no}: `{name}` 导入自 `{actual}`，"
                      f"但本仓库约定为 `{expected}`")
                total += 1

    print()
    if total:
        print(f"[FAIL] 共 {total} 处可疑 import 包路径。"
              f"这类错误 detekt 与 check_signature_types.py 都查不出，"
              f"只在 CI 编译时炸 —— 现在改掉。")
        return 1
    print(f"[OK] 检查了 {len(targets)} 个文件，import 包路径未见异常。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
