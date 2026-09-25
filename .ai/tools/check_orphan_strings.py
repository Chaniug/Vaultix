#!/usr/bin/env python3
"""`strings.xml` 里有没有**零引用**的字符串？

## 为什么需要这个

2026-09-26 实录：删掉 `SettingsViewModel.passkeyCount` 那批死代码时，
它的**文案留在了 `strings.xml`**（`setting_passkey_saved` 等）。
这类残留不会报错、不会变慢、不影响功能，所以**没有任何现有手段会提它** ——
只会随着一轮轮重构越积越多，最后没人敢动 `strings.xml`。

摸底结果：本项目 834 条字符串里，**126 条零引用**（2026-09-26）。

## ⚠️ 这个工具的定位：**报告器，不是删除器**

零引用 ≠ 该删。本项目 `.ai/` 里有一条既存判据：

    **被"搬走"的留，被"取代"的删。**

（见 `strings.xml` 里 `quick_unlock_scope_title`、`about_channel` 附近的注释：
代码里明确写着"长解释留在 `about_channel_preview` 那份里"、
"不用 `settings_quick_unlock_desc`"——这些零引用的条目**是刻意保留的对照/备用文案**。）

自动化手段**无法区分**这两种零引用：
- 「功能没了，文案该跟着走」⇒ 该删；
- 「表述被取代了，但旧的留着做对照 / 等换个场景用」⇒ 该留。

⇒ 本工具**只负责把候选集列出来供人逐条判读**，绝不自动删。
若改成"自动清理"，会把上面第二类一起清掉 —— 那类删了没有编译错误，
**只会在某天需要它时发现它已经不在了**。

## 用法

    python3 .ai/tools/check_orphan_strings.py            # 列出全部候选 + 统计
    python3 .ai/tools/check_orphan_strings.py --quiet    # 只出统计，不出清单
    python3 .ai/tools/check_orphan_strings.py --gate     # 门禁模式：超出基线即失败

`--gate` 用 `.ai/tools/orphan_strings_baseline.txt` 作基线（首次运行自动生成）。
**基线只减不增**：新增的零引用字符串会让门禁失败，提醒作者顺手清掉；
而存量 126 条由人逐条判读后慢慢消减，不阻塞当前工作。

退出码：`--gate` 下 0 = 未超出基线；1 = 有新增零引用。

自测：`python3 .ai/tools/tests/selftest_orphan_strings.py`
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

STRINGS_XML = ROOT / "app" / "src" / "main" / "res" / "values" / "strings.xml"
RES_DIR = ROOT / "app" / "src" / "main" / "res"
BASELINE = Path(__file__).resolve().parent / "orphan_strings_baseline.txt"

SRC_ROOTS = [ROOT / m for m in ("app", "core", "data", "domain")]

# `<string name="foo">` —— 只看 string，不看 string-array / plurals（后两者引用方式不同，
# 且本项目暂无；真加进来时补规则，别用一条宽泛正则蒙）。
STRING_DECL = re.compile(r'<string\s+name="([A-Za-z_]\w*)"')

# 代码里的静态引用
KT_REF = re.compile(r"R\.string\.([A-Za-z_]\w*)")
# XML 里的引用（@string/foo、@string/foo 出现在 style/layout/manifest 等）
XML_REF = re.compile(r"@string/([A-Za-z_]\w*)")
# tools:ignore="MissingTranslation" 等场景下也会出现 @string/ 之外的写法，暂不覆盖。


def declared_strings(xml_path: Path) -> list[str]:
    try:
        text = xml_path.read_text(encoding="utf-8")
    except OSError:
        return []
    return STRING_DECL.findall(text)


def _iter_files(root: Path, patterns: tuple[str, ...]):
    for pat in patterns:
        for p in root.rglob(pat):
            if "/build/" in p.as_posix():
                continue
            yield p


def referenced_names() -> set[str]:
    """全仓库（源码 + 资源 XML）里出现过的字符串资源名。"""
    used: set[str] = set()

    for root in SRC_ROOTS:
        if not root.is_dir():
            continue
        for p in _iter_files(root, ("*.kt", "*.java")):
            try:
                used |= set(KT_REF.findall(p.read_text(encoding="utf-8")))
            except (UnicodeDecodeError, OSError):
                continue

    if RES_DIR.is_dir():
        for p in _iter_files(RES_DIR, ("*.xml",)):
            try:
                used |= set(XML_REF.findall(p.read_text(encoding="utf-8")))
            except (UnicodeDecodeError, OSError):
                continue

    return used


def find_orphans(xml_path: Path = STRINGS_XML) -> list[str]:
    keys = declared_strings(xml_path)
    used = referenced_names()
    return [k for k in keys if k not in used]


def _load_baseline() -> set[str]:
    if not BASELINE.is_file():
        return set()
    return {
        line.strip()
        for line in BASELINE.read_text(encoding="utf-8").splitlines()
        if line.strip() and not line.startswith("#")
    }


def _write_baseline(names: list[str]) -> None:
    BASELINE.write_text(
        "# 零引用字符串基线（2026-09-26 首次生成）。\n"
        "# ⚠️ 这是**只减不增**的名单：清理掉一条就删一行。\n"
        "# 新增条目会让 `check_orphan_strings.py --gate` 失败。\n"
        "# 判读依据见该脚本开头：被「搬走」的留，被「取代」的删。\n"
        + "\n".join(sorted(names)) + "\n",
        encoding="utf-8",
    )


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--quiet", action="store_true", help="只输出统计")
    ap.add_argument("--gate", action="store_true", help="门禁模式：超出基线即失败")
    ap.add_argument(
        "--update-baseline",
        action="store_true",
        help="把当前孤儿集合写为新基线（仅在**判读并清理**之后用）",
    )
    args = ap.parse_args(argv)

    all_keys = declared_strings(STRINGS_XML)
    orphans = find_orphans()

    if args.update_baseline:
        _write_baseline(orphans)
        print(f"✅ 基线已更新：{len(orphans)} 条（共 {len(all_keys)} 条字符串）")
        return 0

    print(f"[info] strings.xml 共 {len(all_keys)} 条，零引用 {len(orphans)} 条。")

    if not args.gate and not args.quiet:
        print(
            "\n⚠️ 零引用 ≠ 该删 —— 本项目判据是「被搬走的留，被取代的删」。\n"
            "   下列条目需**逐条人工判读**，本工具不做删除。\n"
        )
        for name in orphans:
            print(f"  {name}")
        print()

    if args.gate:
        baseline = _load_baseline()
        if not baseline:
            _write_baseline(orphans)
            print(
                f"ℹ️  首次运行，已生成基线 {BASELINE.relative_to(ROOT)}（{len(orphans)} 条）。"
                " 本次判为通过。"
            )
            return 0
        added = sorted(set(orphans) - baseline)
        if added:
            print(f"\n❌ 新增 {len(added)} 条零引用字符串（基线 {len(baseline)} 条）：")
            for name in added:
                print(f"  + {name}")
            print(
                "\n  处理方式二选一：\n"
                "  ① 该删 ⇒ 删掉 strings.xml 里对应行，并跑 --update-baseline；\n"
                "  ② 该留 ⇒ 在代码里引用它，或确认是刻意保留后跑 --update-baseline。"
            )
            return 1
        removed = sorted(baseline - set(orphans))
        if removed:
            print(
                f"\nℹ️  有 {len(removed)} 条基线条目已不再零引用（好事）：{', '.join(removed[:8])}"
                + ("…" if len(removed) > 8 else "")
            )
            print("   跑 --update-baseline 收窄基线。")
        print("✅ 未超出基线。")
        return 0

    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
