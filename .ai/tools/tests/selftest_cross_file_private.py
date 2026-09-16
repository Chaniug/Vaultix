#!/usr/bin/env python3
"""`check_cross_file_private` 的**端到端**自测（真改仓库、看它报不报）。

## 为什么是端到端

2026-09-16 CI 实录：`VaultRepositoryImpl.kt` 的 `private fun buildFullKey`
被 `PinEnrollment.kt` / `LocalUnlockEnrollment.kt` 跨文件调用 ⇒ 编译失败。
这类错误**纯文本可判**，但当时没有任何工具在查。

## 这个探针自己踩过的两个坑（都靠实测才发现）

1. **把扩展函数的接收者当成函数名**：`private fun Entry.toVaultItem()` 里的 `Entry`
   被认成函数名 ⇒ 别处的 `Entry(...)` 构造调用被当成"跨文件调用" ⇒ **103 处误报**。
   （与 `selftest_duplicate_declarations` 里 `JsonElement` 那个坑**同源**。）
2. **同名私有函数散落多文件**：`sha256` 在 3 个文件各有独立实现，
   被调用的是"它自己那一份" ⇒ 4 处误报。
   ⇒ 名字在全仓库不唯一时**整体跳过**（宁可漏报）。

## 做法（三步，与 selftest_import_packages 同构）

1. 要求初始 OK（不误报）；
2. 把 `buildFullKey` 从 `internal` 改回 `private`，要求报出**恰好 2 处**
   （`PinEnrollment.kt` 与 `LocalUnlockEnrollment.kt`）；
3. 还原，要求重新 OK。

⚠️ 中途异常退出会留下脏文件，务必 `git diff --stat` 确认已还原。
"""

from __future__ import annotations

import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
TOOL = ROOT / ".ai" / "tools" / "check_compile_smells.py"
OWNER = ROOT / "data/repository/src/main/java/io/vaultix/data/repository/VaultRepositoryImpl.kt"

GOOD = "internal fun buildFullKey(key: SymmetricCryptoKey): ByteArray {"
BAD = "private fun buildFullKey(key: SymmetricCryptoKey): ByteArray {"

# 期望被报出的调用方（文件名即可）
EXPECTED_CALLERS = {"PinEnrollment.kt", "LocalUnlockEnrollment.kt"}


def run_tool() -> tuple[int, str]:
    proc = subprocess.run(
        [sys.executable, str(TOOL)], cwd=ROOT, capture_output=True, text=True
    )
    return proc.returncode, proc.stdout + proc.stderr


def main() -> int:
    failures = 0

    code, out = run_tool()
    if code != 0:
        print("[FAIL] 初始状态不是 OK —— 仓库里本来就有跨文件 private 调用，先修它")
        print(out)
        return 1
    print("[PASS] ① 初始状态：OK（不误报）")

    original = OWNER.read_text(encoding="utf-8")
    try:
        assert GOOD in original, "找不到 `internal fun buildFullKey` 定义行"
        OWNER.write_text(original.replace(GOOD, BAD), encoding="utf-8")

        code, out = run_tool()
        found = {c for c in EXPECTED_CALLERS if c in out}
        if code == 1 and found == EXPECTED_CALLERS:
            print(f"[PASS] ② 改回 private：报出全部 {len(found)} 个调用方 {sorted(found)}")
        else:
            failures += 1
            print(f"[FAIL] ② 期望报出 {sorted(EXPECTED_CALLERS)}，实得 {sorted(found)}（退出码 {code}）")
            print(out)
    finally:
        OWNER.write_text(original, encoding="utf-8")

    code, out = run_tool()
    if code == 0:
        print("[PASS] ③ 还原后：OK（仓库已恢复干净）")
    else:
        failures += 1
        print("[FAIL] ③ 还原后仍报错 —— 仓库被留脏了，检查 git diff")
        print(out)

    if failures:
        print(f"\n[FAIL] {failures} 项未过。")
        return 1
    print("\n[OK] 跨文件 private 门禁的端到端行为正确。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
