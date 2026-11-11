#!/usr/bin/env python3
"""`check_cross_file_private` 的**端到端**自测（真改仓库、看它报不报）。

## 为什么是端到端

2026-09-16 CI 实录：`VaultRepositoryImpl.kt` 的 `private fun buildFullKey`
被 `PinEnrollment.kt` / `LocalUnlockEnrollment.kt` 跨文件调用 ⇒ 编译失败。
这类错误**纯文本可判**，但当时没有任何工具在查。

## 这个探针自己踩过的三个坑（都靠实测才发现）

1. **把扩展函数的接收者当成函数名**：`private fun Entry.toVaultItem()` 里的 `Entry`
   被认成函数名 ⇒ 别处的 `Entry(...)` 构造调用被当成"跨文件调用" ⇒ **103 处误报**。
   （与 `selftest_duplicate_declarations` 里 `JsonElement` 那个坑**同源**。）
2. **同名私有函数散落多文件**：`sha256` 在 3 个文件各有独立实现，
   被调用的是"它自己那一份" ⇒ 4 处误报。
   ⇒ 名字在全仓库不唯一时**整体跳过**（宁可漏报）。
3. **把调用方文件名硬编码进期望（2026-11-11 本地全量自测才暴露）**：
   `PinEnrollment.kt` 早在 `ae80aaec`（快速解锁房子化）就被删掉了，
   `buildFullKey` 现在只剩 `LocalUnlockEnrollment.kt` 一个跨文件调用方；
   而旧版 `EXPECTED_CALLERS` 写死两个文件名 ⇒ ② **恒不成立、长期假红**。
   更值得记的是它为什么一直没人管：本探针**不在 CI 清单里**，
   大家只跑 CI 列的那几条自测 —— 假红几次之后就没人看它了。
   ⇒ 期望改为**从源码现算**（除 owner 外所有出现 `buildFullKey(` 的 .kt 文件），
   并要求至少 1 个，否则变异验证已失去意义。

## 做法（三步，与 selftest_import_packages 同构）

1. 要求初始 OK（不误报）；
2. 把 `buildFullKey` 从 `internal` 改回 `private`，要求报出**当前全部**跨文件调用方；
3. 还原，要求重新 OK。

⚠️ 中途异常退出会留下脏文件，务必 `git diff --stat` 确认已还原。
"""

from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
TOOL = ROOT / ".ai" / "tools" / "check_compile_smells.py"
REPO_DIR = ROOT / "data/repository/src/main/java/io/vaultix/data/repository"
OWNER = REPO_DIR / "VaultRepositoryImpl.kt"

GOOD = "internal fun buildFullKey(key: SymmetricCryptoKey): ByteArray {"
BAD = "private fun buildFullKey(key: SymmetricCryptoKey): ByteArray {"


def expected_callers() -> set[str]:
    """当前仓库里真正出现 `buildFullKey(` 的 .kt 文件名（owner 自己除外）。

    ⚠️ 动态算而不是写死：调用方会随重构增减，硬编码会造出文件头第 3 个坑那种
    「探针恒红却无人发现」。owner 里的 KDoc 也提到这个函数名，必须跳过。
    """
    callers: set[str] = set()
    for path in REPO_DIR.rglob("*.kt"):
        if path == OWNER:
            continue
        text = path.read_text(encoding="utf-8", errors="replace")
        if re.search(r"\bbuildFullKey\s*\(", text):
            callers.add(path.name)
    return callers


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

    # 期望在**变异之前**算好：变异只改 owner，不影响调用方集合，
    # 但先算能避免「改坏了才去问期望是什么」这种自证循环。
    expected = expected_callers()
    original = OWNER.read_text(encoding="utf-8")
    try:
        assert GOOD in original, "找不到 `internal fun buildFullKey` 定义行"
        OWNER.write_text(original.replace(GOOD, BAD), encoding="utf-8")

        code, out = run_tool()
        found = {c for c in expected if c in out}
        if not expected:
            failures += 1
            print("[FAIL] ② 仓库里已无 buildFullKey 的跨文件调用方，变异验证失去意义")
        elif code == 1 and found == expected:
            print(f"[PASS] ② 改回 private：报出全部 {len(found)} 个调用方 {sorted(found)}")
        else:
            failures += 1
            print(f"[FAIL] ② 期望报出 {sorted(expected)}，实得 {sorted(found)}（退出码 {code}）")
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
