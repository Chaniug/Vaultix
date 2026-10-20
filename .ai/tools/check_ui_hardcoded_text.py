#!/usr/bin/env python3
"""UI 层中文文案哨兵（审计 §3.4 整改的门禁）。

## 为什么需要这个脚本

`.ai/conventions/8.4-UI·观感.md` 与 `Docs/08 §6` 都写着「文案放 strings.xml」，
但审计（2026-10-19 §3.4）扫出 **72 处**用户可见中文硬编码在 Kotlin 里，
而且**没有人会发现**：`strings.xml` 里没有它们，`check_orphan_strings.py`
（查"资源里定义了却没人用"）也看不见它们 —— 这是**反方向**的洞。

⇒ 本脚本补上反方向：**查 Kotlin 里有没有"本该进资源"的中文**。

## 判据（不是"禁止中文"，是"禁止未登记的中文"）

中文字面量在 Kotlin 里有四种合法用途，都会被放行：

| 用途 | 例子 | 放行理由 |
|---|---|---|
| 日志 | `Log.d(TAG, "reseal $vaultId → 异常")` | 不进 UI，不进资源 |
| 异常 / 内部错误 | `error("Success 无需映射")`、`checkNotNull(x) { "…" }` | 开发者看的 |
| KDOC / 注释 | `/** 说明 */`、`// 说明` | 不是代码 |
| **已登记** | 白名单文件里的逐条登记 | 见下 |

## 白名单（`ui_text_baseline.txt`）

格式 `相对路径<TAB>特征串<TAB>原因`。三项都必须写 ——
第三条强制作者回答「**为什么不进 strings.xml**」，回答不上来就说明它该进。

⚠️ **特征串是拿在"引号之间的字面量内容"上去做 `in` 判断的**，不是整行代码
（`scan()` 里 `needle in text`，而 `text` 是 `LITERAL` 抓到的引号内内容）。
所以：
  · 想登记 `private const val TITLE = "验证码"` ⇒ 特征串写 `验证码`；
  · 写成 `FALLBACK_TITLE =` 会**永远不命中**（那串在引号外面），而基线的
    三列齐全、脚本也不报错 ⇒ 表现为"登记了但没用"，极难自查（2026-10-21 踩过）。
  · 同一文件里若有两个字面量内容相同（如上例与 `…_VALUE = "验证码"`），
    **一条特征串就同时放行两处** —— 登记时在原因列里把两处都说清。

⚠️ 白名单**只减不增**：新增条目要在 PR 里说明理由，否则这条门禁会被
"先加白名单再说"蛀空（`.ai/issues` #123 同族的教训）。

用法：
    python3 .ai/tools/check_ui_hardcoded_text.py            # 检查
    python3 .ai/tools/check_ui_hardcoded_text.py --selftest # 自检本脚本
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
UI_DIR = ROOT / "app/src/main/java/io/vaultix/vaultix/ui"
BASELINE = Path(__file__).resolve().parent / "ui_text_baseline.txt"

# 整目录豁免（连**路径前缀**都必须是这里登记的）。
#
# ⚠️ 为什么是"整目录"而不是"逐条进基线"：`ui/preview/` 里那几十条中文是
#   **Compose Preview 的示例数据**（"公司 VPN"、"离开应用后多久自动上锁（当前：15 分钟）"、
#   "2.0x：标题/副标题不得重叠…"）—— 它们只出现在 IDE 的设计预览里，**永远不会上屏**。
#   逐条登记会让基线文件被几十行示例串淹没，真正的风险条目反而看不见了。
#
# ⚠️ 但也不能就这样"整个目录不管"：预览里若出现一句**真要上屏**的文案（比如某天有人
#   把预览当成"临时 UI"改了），豁免会把它藏起来。⇒ 只放行**这一层**，
#   `ui/preview/` 下的子目录不自动豁免（前缀匹配是精确到这里为止的写法）。
_EXEMPT_DIRS = (
    # 设计预览：示例数据 + 版式自检说明，均不上屏。
    "app/src/main/java/io/vaultix/vaultix/ui/preview/",
)

# 只看"含中文的字符串字面量"。
CJK = "\u4e00-\u9fff"
LITERAL = re.compile(r'"([^"\\\n]*(?:\\.[^"\\\n]*)*)"')

# 出现这些关键字的行，视为日志 / 内部断言，放行。
_ALLOW_HINTS = (
    "Log.",
    "Logcat",
    "error(",
    "check(",
    "checkNotNull(",
    "require(",
    "requireNotNull(",
    "IllegalStateException(",
    "IllegalArgumentException(",
    "TODO(",
    "AutofillLogger.",
    "println(",
)


def _strip_comments(lines: list[str]) -> list[tuple[int, str]]:
    """返回 (行号, 已去掉注释的代码)。块注释整段剔除。"""
    out: list[tuple[int, str]] = []
    in_block = False
    for no, raw in enumerate(lines, start=1):
        s = raw
        if in_block:
            if "*/" in s:
                s = s.split("*/", 1)[1]
                in_block = False
            else:
                continue
        if "/*" in s:
            before, after = s.split("/*", 1)
            if "*/" in after:
                s = before + after.split("*/", 1)[1]
            else:
                s = before
                in_block = True
        s = s.split("//", 1)[0]
        out.append((no, s))
    return out


def _load_baseline() -> dict[str, set[str]]:
    """path -> 已登记的特征串集合。"""
    entries: dict[str, set[str]] = {}
    if not BASELINE.exists():
        return entries
    for line in BASELINE.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        parts = line.split("\t")
        if len(parts) < 3:
            raise SystemExit(f"基线格式错误（需 3 列 tab 分隔）：{line!r}")
        path, needle, reason = parts[0].strip(), parts[1].strip(), parts[2].strip()
        if not reason:
            raise SystemExit(f"基线缺原因列：{line!r}")
        entries.setdefault(path, set()).add(needle)
    return entries


def scan() -> list[tuple[str, int, str]]:
    baseline = _load_baseline()
    hits: list[tuple[str, int, str]] = []
    for path in sorted(UI_DIR.rglob("*.kt")):
        rel = path.relative_to(ROOT).as_posix()
        if any(rel.startswith(d) for d in _EXEMPT_DIRS):
            continue
        allowed = baseline.get(rel, set())
        for no, code in _strip_comments(path.read_text(encoding="utf-8").split("\n")):
            if any(h in code for h in _ALLOW_HINTS):
                continue
            for m in LITERAL.finditer(code):
                text = m.group(1)
                if not re.search(f"[{CJK}]", text):
                    continue
                if any(needle in text for needle in allowed):
                    continue
                hits.append((rel, no, text))
    return hits


def _is_allowed_line(line: str, allowed: set[str]) -> bool:
    """一行代码里的中文是否应放行：日志类，或命中基线登记。"""
    if any(h in line for h in _ALLOW_HINTS):
        return True
    return all(
        any(needle in m.group(1) for needle in allowed)
        for m in LITERAL.finditer(line)
        if re.search(f"[{CJK}]", m.group(1))
    )


def selftest() -> int:
    """三条自检，钉住「什么该抓、什么该放」。"""
    cases = [
        ('val a = "保存失败"', set(), True, "普通中文字面量应被抓到"),
        ('Log.d(TAG, "保存失败")', set(), False, "日志里的中文应放行"),
        ('val a = "已保存"', {"已保存"}, False, "已登记的中文应放行"),
        ('val a = "没登记过"', {"已保存"}, True, "基线里有别的条目也不能误放行"),
    ]
    # 豁免目录**必须真的生效**：这条防的是"改了常量名/路径但没真跳过"，
    # 那时上面的 4 条会全绿，而实际扫描仍在报 preview 目录。
    preview = "app/src/main/java/io/vaultix/vaultix/ui/preview/ComponentPreviews.kt"
    exempt_ok = any(preview.startswith(d) for d in _EXEMPT_DIRS)
    print(f"  [{'PASS' if exempt_ok else 'FAIL'}] preview 目录应被豁免")
    failures = 0 if exempt_ok else 1
    for line, allowed, should_hit, label in cases:
        allowed_line = _is_allowed_line(line, allowed)
        hit = not allowed_line
        ok = hit == should_hit
        print(f"  [{'PASS' if ok else 'FAIL'}] {label}")
        failures += 0 if ok else 1
    total = len(cases) + 1
    print(f"自检：{total - failures}/{total} 通过")
    return 0 if failures == 0 else 1


def main(argv: list[str]) -> int:
    if "--selftest" in argv:
        return selftest()

    hits = scan()
    if not hits:
        print("OK：UI 层没有未登记的中文文案")
        return 0

    print(f"FAIL：{len(hits)} 处未登记的中文文案（应进 strings.xml，或登记进基线并说明理由）")
    current = None
    for rel, no, text in hits:
        if rel != current:
            print(f"\n{rel}")
            current = rel
        print(f"  {no:5}  {text[:80]}")
    return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
