#!/usr/bin/env python3
"""`check_state_flattening.scan` 的自测用例。

## 为什么有这份文件

`.ai/tools/check_compile_smells.py` 的教训（见 `selftest_duplicate_declarations.py` 开头）：
**写启发式探针必须配正反用例**，否则"它说 OK"和"真没问题"分不清。

本探针的风险比一般探针更高：它的核心判据是「表达式不处于 when 分派体内」，
而**负例（正确写法）恰恰长得很像正例（错误写法）** ——

    错误：Switch(checked = state.biometric is CapabilityState.On, ...)          ← 该报
    正确：when (cap) { is CapabilityState.On -> Switch(checked = true, ...) }   ← 不该报

两者都有 `is CapabilityState.On` 和 `checked =`。第 4 条判据（when 放行）
就是为区分这两者存在的，**没有它会把全部正确代码误报**。

因此下面 `when_dispatch_ok` 与 `flattened_reports` 这一对必须同时通过，
只测其中一个等于没测。
"""

from __future__ import annotations

import importlib.util
import sys
from pathlib import Path

_TOOL = Path(__file__).resolve().parent.parent / "check_state_flattening.py"


def _load_tool():
    spec = importlib.util.spec_from_file_location("vaultix_check_state_flattening", _TOOL)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


TRIPLE = """
sealed interface Cap {
    data object Off : Cap
    data class Partial(val n: Int) : Cap
    data object On : Cap
}
"""

PAIR = """
sealed interface Cap2 {
    data object Off : Cap2
    data object On : Cap2
}
"""

# (用例名, 源码, 期望报出条数, 期望理由)
CASES: list[tuple[str, str, int, str]] = [
    (
        # ★★ 真实生产代码的形态：**全限定名**引用状态。
        #   这条是 2026-09-26 那次假绿的直接补丁 ——
        #   当时 7 条用例全绿，探针却在真实 bug 上返回 0 处，
        #   因为真实代码写的是 `QuickUnlockController.CapabilityState.On`（三段），
        #   而原正则把倒数第二段 `QuickUnlockController` 当成了类型名去查表。
        #   **自测只覆盖短名 ⇒ 只证明了探针能处理我编的简化输入，不能证明它有用。**
        "fully_qualified_reports",
        TRIPLE + """
fun Row(state: Holder) {
    Switch(checked = state.biometric is Outer.Cap.On, onCheckedChange = {})
}
""",
        1,
        "全限定名引用三态类型，同样应报（真实代码就是这个形态）",
    ),
    (
        # 配对负例：全限定名 + when 分派，不该报。
        #   （若只加正例不加这条，探测可能靠"一律报全限定名"蒙对正例。）
        "fully_qualified_when_ok",
        TRIPLE + """
fun Toggle(cap: Outer.Cap) {
    when (cap) {
        is Outer.Cap.Partial -> TextButton(onClick = {})
        is Outer.Cap.On -> Switch(checked = true, onCheckedChange = {})
        Outer.Cap.Off -> Switch(checked = false, onCheckedChange = {})
    }
}
""",
        0,
        "全限定名 + when 穷尽分派，应放行",
    ),
    (
        # ★ 真 bug 的原始形态：三态被 is On 压成二态，直接喂 checked。
        "flattened_reports",
        TRIPLE + """
fun Row(state: Cap) {
    Switch(checked = state is Cap.On, onCheckedChange = {})
}
""",
        1,
        "三态 is On 喂 checked，应报",
    ),
    (
        # ★ 最重要的一条：正确写法（when 穷尽分派）**绝不能**被误报。
        #   若这条挂了，说明 when 放行判据失效，探针会把全项目正确代码刷成红色。
        "when_dispatch_ok",
        TRIPLE + """
fun CapabilityToggle(cap: Cap) {
    when (cap) {
        is Cap.Partial -> TextButton(onClick = {})
        is Cap.On -> Switch(checked = true, onCheckedChange = {})
        Cap.Off -> Switch(checked = false, onCheckedChange = {})
    }
}
""",
        0,
        "when 里的穷尽分派不是压扁，应放行",
    ),
    (
        # 二态类型压二态是对的：没有 Partial 可合并，不该报。
        "two_state_ok",
        PAIR + """
fun Row(state: Cap2) {
    Switch(checked = state is Cap2.On, onCheckedChange = {})
}
""",
        0,
        "二态类型无压扁问题，应放行",
    ),
    (
        # 用等号比较枚举式状态，形态不同但同样是二值化。
        "equality_form_reports",
        TRIPLE + """
fun Row(state: Cap) {
    Switch(checked = state == Cap.On, onCheckedChange = {})
}
""",
        1,
        "== On 同样是二值判定，应报",
    ),
    (
        # 三态判定喂给非开关控件：不是「开关被压扁」，不该报。
        #   拿它当 enabled 之类用途时语义不同（没有"位置"可误解）。
        "non_toggle_ok",
        TRIPLE + """
fun Row(state: Cap) {
    Button(enabled = state is Cap.On, onClick = {})
}
""",
        0,
        "非 checked/value 实参不在本探针范围内",
    ),
    (
        # 表达式里出现 On 但不是本文件的三态类型（比如成员名撞车）。
        "unrelated_on_ok",
        TRIPLE + """
fun Row(other: SomethingElse) {
    Switch(checked = other is Other.On, onCheckedChange = {})
}
""",
        0,
        "Other 不是本文件的三态 sealed 类型，不报",
    ),
    (
        # 三态类型存在，但开关喂的是别的变量 —— 不该被连带报出。
        "unrelated_subject_ok",
        TRIPLE + """
fun Row(state: Cap, plain: Boolean) {
    Switch(checked = plain, onCheckedChange = {})
}
""",
        0,
        "喂的是普通 Boolean，不报",
    ),
]


def _polyadic_of(source: str, tool) -> set[str]:
    """用例自洽：从用例源码里提取它自己的多态类型名。

    生产路径下这张表来自**全树**扫描（见 collect_polyadic_names）——
    因为类型定义与误用位置常常不在同一文件（实测 CapabilityState 就是）。
    自测用例把两者写在一个片段里，所以这里从片段自身提取，效果等价。
    """
    names = set(tool.SEALED_DECL.findall(source))
    return {n for n in names if tool.branch_count(source, n) >= 3}


def main() -> int:
    tool = _load_tool()
    failed = 0
    for name, source, want, why in CASES:
        got = tool.scan(source, _polyadic_of(source, tool))
        ok = len(got) == want
        mark = "✓" if ok else "✗"
        print(f"  {mark} {name:26} 期望 {want} 条，得到 {len(got)} 条   （{why}）")
        if not ok:
            failed += 1
            for line_no, reason in got:
                print(f"      → 第 {line_no} 行: {reason}")

    print()
    if failed:
        print(f"❌ {failed}/{len(CASES)} 个用例未通过")
        return 1
    print(f"✅ {len(CASES)}/{len(CASES)} 个用例通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
