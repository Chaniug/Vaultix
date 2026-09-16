#!/usr/bin/env python3
"""`check_compile_smells.check_duplicate_declarations` 的自测用例。

## 为什么有这份文件

2026-09-16 `enrollPinForVaults` 整块被复制，CI 报 `Conflicting overloads`。
补救时新写的文本探针**头两版都是坏的**：

- 第 1 版正则 `^[ \t]+...(fun|val|var)` ⇒ **264 处误报**（抓到了局部变量/命名参数）。
- 第 2 版收紧成类成员级 `fun` ⇒ 剩 **5 处误报**，全在**扩展函数**上：
  `fun JsonElement?.asObject()` 里的 `JsonElement` 被当成了函数名，于是同一接收者的
  多个扩展函数归一成同一个 key。

教训：**写启发式探针必须配正反用例**，否则"它说 OK"和"真没问题"分不清。
本文件是那五类判据的永久回归集，改动正则后必须跑。
"""

from __future__ import annotations

import importlib.util
import sys
import tempfile
from pathlib import Path

_TOOL = Path(__file__).resolve().parent.parent / "check_compile_smells.py"


def _load_tool():
    spec = importlib.util.spec_from_file_location("vaultix_check_compile_smells", _TOOL)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


# (用例名, 源码, 期望报出条数, 期望理由)
CASES: list[tuple[str, str, int, str]] = [
    (
        "real_dup",
        """
class A {
    suspend fun enrollPinForVaults(vaultIds: List<String>, pin: String): Map<String, Int> = TODO()
    suspend fun enrollPinForVaults(vaultIds: List<String>, pin: String): Map<String, Int> = TODO()
}
""",
        1,
        "真实事故：整块 KDoc + 声明被复制，同名同参 ⇒ 必须报",
    ),
    (
        "legal_overload",
        """
class B {
    fun f(a: String): Int = 0
    fun f(a: Int): Int = 0
    fun f(): Int = 0
}
""",
        0,
        "参数类型不同的重载是合法的 ⇒ 不能报",
    ),
    (
        "ext_fun_same_receiver",
        """
class C {
    private fun JsonElement?.asObject(): JsonObject? = null
    private fun JsonElement?.asArray(): List<JsonElement>? = null
    fun Intent.getCreateOrNull(): X? = null
    fun Intent.getGetOrNull(): Y? = null
}
""",
        0,
        "同一接收者上的不同扩展函数 ⇒ 不能报（第 2 版正则就栽在这）",
    ),
    (
        "ext_fun_dup",
        """
class D {
    fun Intent.foo(x: Int): Int = 0
    fun Intent.foo(x: Int): Int = 0
}
""",
        1,
        "扩展函数真重复 ⇒ 必须报",
    ),
    (
        "diff_receiver",
        """
class E {
    fun Intent.foo(x: Int): Int = 0
    fun Bundle.foo(x: Int): Int = 0
    fun foo(x: Int): Int = 0
}
""",
        0,
        "接收者不同（含无接收者）在 Kotlin 里是不同签名 ⇒ 不能报（接收者须进 key）",
    ),
    (
        "same_name_different_types",
        """
@Dao
interface VaultDao {
    fun listByVault(vaultId: String): List<VaultEntity>
    fun clearVault(vaultId: String)
}

@Dao
interface FolderDao {
    fun listByVault(vaultId: String): List<FolderEntity>
    fun clearVault(vaultId: String)
}
""",
        0,
        "名字在不同 interface 里重复是合法的 ⇒ 不能报（须按顶层类型分桶）",
    ),
    (
        "same_name_same_type",
        """
interface F {
    fun listByVault(vaultId: String): List<X>
    fun listByVault(vaultId: String): List<X>
}
""",
        1,
        "同一个 interface 里真重复 ⇒ 必须报",
    ),
]


def main() -> int:
    tool = _load_tool()
    failed = 0
    with tempfile.TemporaryDirectory() as tmp:
        for name, code, want, reason in CASES:
            path = Path(tmp) / f"{name}.kt"
            path.write_text(code, encoding="utf-8")
            got = tool.check_duplicate_declarations(path)
            ok = len(got) == want
            failed += 0 if ok else 1
            print(f"[{'PASS' if ok else 'FAIL'}] {name}：期望 {want} 实得 {len(got)} —— {reason}")
            if not ok:
                for line in got:
                    print(f"         {line}")
    if failed:
        print(f"\n[FAIL] {failed} 个用例未过：重复声明探针的正则改坏了。")
        return 1
    print(f"\n[OK] {len(CASES)} 个用例全绿。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
