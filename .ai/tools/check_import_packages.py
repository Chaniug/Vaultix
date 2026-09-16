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

## ⚠️ 覆盖面（2026-09-16 修正过一次，别缩回去）

扫 **`app/` `core/` `data/` `domain/`** 四个真实源码模块 —— 即**全仓库**。
排除 `reference/`（Bastion 对照源码，非本项目模块、不参与构建）。

**修的原因**：首版只扫 `app/src`，于是
`data/repository/.../LocalUnlockEnrollment.kt` 里
`import io.vaultix.domain.VaultKind`（应为 `io.vaultix.model.VaultKind`）**不在扫描范围内**，
本地报"未见异常"却是**假绿**，CI 编译才炸。
⇒ **判据对、覆盖面错，等于没查。** 改工具时**先确认它扫的是不是全部。**

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

# 🔴 2026-09-16 修正：原先只扫 `app/src`，而工具自称是仓库级门禁 ⇒
#    报出一个真实漏网：新文件 `data/repository/.../LocalUnlockEnrollment.kt`
#    写了 `import io.vaultix.domain.VaultKind`（正确是 `io.vaultix.model.VaultKind`，
#    全仓库另外 17 处都对）。该文件**根本不在扫描范围内**，
#    于是本地 "import 包路径未见异常" 是**假绿**，CI 编译才炸：
#      e: LocalUnlockEnrollment.kt:24:26 Unresolved reference 'VaultKind'
#    这正是 #101 那类错误的第二次发作 —— 判据本身是对的，是**覆盖面**漏了。
#
# ⇒ 改为扫**全部真实源码模块**（app / core / data / domain）。
#   ⚠️ 刻意**排除 `reference/`**：那是 Bastion 的对照源码（非本项目模块、
#      不参与构建），它的图标合法地取自不同 icon group（filled / outlined），
#      纳入扫描会引入一片纯误报。
SRC_ROOTS = [ROOT / m for m in ("app", "core", "data", "domain")]
# 仍然保留 SRC_ROOT 别名，供旧调用点/测试引用。
SRC_ROOT = SRC_ROOTS[0]

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

# ---------------------------------------------------------------------------
# 合法跨包同名：**豁免**规则 A 的"多数派"判定
# ---------------------------------------------------------------------------
# ⚠️ 为什么需要单独一张表：规则 A 靠"多数派"推断约定，但有些名字
#    **确实在多个包里都存在**，两个都是正确 API —— 此时"哪个文件多"纯属偶然，
#    不构成"另一个是写错了"的证据。2026-09-16 把规则 A 从"唯一包"改成
#    "多数派"后，这两个名字立刻被误报，正说明它们必须显式豁免。
#
# 判定口诀：**"另一个包是不是根本不导出这个符号？"**
#   是 → 写错了（如 `io.vaultix.domain.VaultKind`，domain 里根本没有它）；
#   否 → 合法，进本表。
_LEGIT_CROSS_PACKAGE: frozenset[str] = frozenset({
    # ui.graphics 的颜色插值 与 ui.unit 的 Dp 插值，两个 lerp 都是公开 API。
    "lerp",
    # compose.ui.platform（旧）与 lifecycle.compose（新）两包都真实存在，
    # 后者是迁移目标，并存期间同时用不算错。
    "LocalLifecycleOwner",
})


def simple_name(path: str, alias: str | None) -> str:
    return alias or path.rsplit(".", 1)[-1]


def package_of(path: str) -> str:
    return path.rsplit(".", 1)[0]


def iter_kt_files() -> list[Path]:
    """全仓库真实模块里的 .kt（排除 build 产物与 reference/ 对照源码）。"""
    files: list[Path] = []
    for root in SRC_ROOTS:
        if not root.is_dir():
            continue
        files.extend(p for p in root.rglob("*.kt") if "/build/" not in p.as_posix())
    return sorted(set(files))


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
    """扫全仓库，得出「简单名 → 应当来自的包」。

    ## 🔴 判据（2026-09-16 第二次修正，别改回去）

    原先的判据是「**全仓库只从一个包导入过**」才算约定。它有**自证伪**的致命缺陷：

        写错一处 import —— 把 18 处 `io.vaultix.model.VaultKind` 之一写成
        `io.vaultix.domain.VaultKind` ⇒ 该名字变成"两个包都用过"
        ⇒ 从约定表里消失 ⇒ 检查**跳过它** ⇒ 本地依旧"未见异常"。

    **犯下这个错的动作本身，把这个错变成了查不出来。**
    这就是 2026-09-16 CI 第三次红的真实原因 —— 而且它在"只扫 app/src"的
    覆盖面 bug 修好之后**依然**拦不住。

    ## 现在的判据：多数派，而不是唯一性

    - 取**出现次数最多**的包作为"应当来自的包"；
    - 少数派即便只出现 **1 次**也算可疑 —— 这是关键，单个错误改不动多数派；
    - 若最高票与次高票**接近**（无明显多数派），整体跳过，
      因为那可能是合法的跨包同名（如 `lerp`、`LocalLifecycleOwner`）。

    ⇒ 这样**单个错误 import 不会再抹掉自己的参照系**。
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
        if len(pkgs) == 1:                      # 只有一个包 ⇒ 毫无疑问
            only_pkg, n = next(iter(pkgs.items()))
            if n >= 2:                          # 至少 2 个样本才算"约定"
                exclusive[name] = only_pkg
            continue
        # 多包：取多数派，但要求"多数派优势明显"，否则视为合法跨包同名
        ranked = sorted(pkgs.items(), key=lambda kv: -kv[1])
        top_pkg, top_n = ranked[0]
        _, runner_n = ranked[1]
        if top_n >= 2 and top_n > runner_n:
            exclusive[name] = top_pkg
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

        # 规则 A：独占/多数包一致性
        # 仅对 androidx.* / io.vaultix.* 生效，避免误伤第三方库的合法同名。
        if not (fp.startswith("androidx.") or fp.startswith("io.vaultix.")):
            continue
        if name in _LEGIT_CROSS_PACKAGE:        # 两个包都真实存在，不算错
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
          f"识别出 {len(exclusive)} 个「有明确约定包」的符号")

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
