#!/usr/bin/env python3
"""`check_import_packages` 的**端到端**自测：真的往仓库里写错、看它报不报。

## 为什么必须是端到端，而不是单元测试

2026-09-16 CI 第三次红：`data/repository/.../LocalUnlockEnrollment.kt` 写了
`import io.vaultix.domain.VaultKind`（正确是 `io.vaultix.model.VaultKind`），
而这道门禁**报了"未见异常"**。它连坏三处，每一处单看都"很合理"：

1. **覆盖面**：`SRC_ROOT = app/src` —— 那个文件在 `data/` 下，**根本不在扫描范围**。
   判据对，但没扫到 ⇒ 假绿。
2. **自证伪的判据**：原判据是「全仓库只从一个包导入过才算约定」。
   写错一处 ⇒ 该符号变成"两个包都用过" ⇒ 从约定表消失 ⇒ **跳过它**。
   **犯下这个错的动作本身，把这个错变成了查不出来。**
3. **多数派判据的副作用**：改成"多数派"后能抓到 VaultKind 了，
   但立刻误报 `lerp`、`LocalLifecycleOwner` —— 它们在多个包里都是**正确的公开 API**，
   "哪个文件多"纯属偶然。⇒ 需要显式豁免表。

前两处**用单元测试都测不出来**（测的是"函数对给定输入返回什么"，
而这个 bug 是"函数从来没被喂到那个输入" / "喂到之前输入已被错误本身污染"）。
⇒ 唯一可靠的测法是：**在真实仓库里制造这个错误，跑脚本，看它退出码**。

## 做法

1. 先跑一次，要求 OK（证明"本来没事时不乱报"）；
2. 把目标 import 改成错的包，再跑，要求**恰好**报出那个符号（证明"犯错时抓得住"）；
3. 还原，再跑一次要求 OK（证明测完仓库是干净的）。

⚠️ 步骤 2/3 会**临时改写仓库文件并还原**。中途异常退出时务必手工确认已还原：
`git diff --stat` 应只剩预期改动。
"""

from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
TOOL = ROOT / ".ai" / "tools" / "check_import_packages.py"

# (相对路径, 正确 import, 错误 import, 符号名)
CASES = [
    (
        "data/repository/src/main/java/io/vaultix/data/repository/LocalUnlockEnrollment.kt",
        "import io.vaultix.model.VaultKind",
        "import io.vaultix.domain.VaultKind",
        "VaultKind",
    ),
]


# 「**不该**报错」的反向用例：(相对路径, 要临时插入的 import 行, 说明)。
#
# 🔴 2026-11-01 新增，起因是一次真实误报：
#   `SiteIcon.kt` 引入 `import androidx.compose.foundation.Image`（组件，用来画图标）
#   之后，`foundation.Image` 的票数从 1 变 2，于是规则 A 认定
#   "Image 应当来自 foundation"，把 `DisplayOptionsSheet.kt` 里那句
#   `import androidx.compose.material.icons.filled.Image`（**图标**）判成错误。
#
#   两者根本不是同一个符号：前者是 Composable，后者是 `ImageVector` 扩展属性。
#   脚本按简单名比较，看不出这层区别 ⇒ 图标必须整体退出统计（见工具的 is_icon_import）。
#
# 这个用例把"图标不参与约定判定"钉住：插入 `Icons.Outlined.Image` 后
# `Image` 的票数变成 foundation=2 / icons.outlined=1 —— 若图标豁免失效，
# 脚本会立刻把它判成"偏离约定"而报错。
NEGATIVE_CASES = [
    (
        "app/src/main/java/io/vaultix/vaultix/ui/common/AppPickerDialog.kt",
        "import androidx.compose.material.icons.outlined.Image",
        "图标与组件同名（Icons.Outlined.Image vs foundation.Image）",
    ),
]


def run_tool() -> tuple[int, str]:
    proc = subprocess.run(
        [sys.executable, str(TOOL)], cwd=ROOT, capture_output=True, text=True
    )
    return proc.returncode, proc.stdout + proc.stderr


def main() -> int:
    failures = 0

    code, out = run_tool()
    if code != 0:
        print("[FAIL] 初始状态就不是 OK —— 仓库里本来就有可疑 import，先修它")
        print(out)
        return 1
    print("[PASS] ① 初始状态：OK（不误报）")

    for rel, good, bad, symbol in CASES:
        path = ROOT / rel
        original = path.read_text(encoding="utf-8")
        try:
            assert good in original, f"{rel} 里找不到 `{good}`"
            path.write_text(original.replace(good, bad), encoding="utf-8")

            code, out = run_tool()
            hit = re.search(rf"`{re.escape(symbol)}`\s*导入自", out)
            if code == 1 and hit:
                print(f"[PASS] ② 写错 {symbol} 的包：被抓到（退出码 1）")
            else:
                failures += 1
                print(f"[FAIL] ② 写错 {symbol} 的包却没抓到（退出码 {code}）")
                print(out)
        finally:
            path.write_text(original, encoding="utf-8")

    for rel, extra_import, why in NEGATIVE_CASES:
        path = ROOT / rel
        original = path.read_text(encoding="utf-8")
        try:
            marker = "\nimport "
            idx = original.find(marker)
            assert idx >= 0, f"{rel} 里找不到 import 块"
            mutated = original[: idx + 1] + extra_import + "\n" + original[idx + 1 :]
            path.write_text(mutated, encoding="utf-8")

            code, out = run_tool()
            if code == 0:
                print(f"[PASS] ②b 不误报：{why}")
            else:
                failures += 1
                print(f"[FAIL] ②b 误报了：{why}（退出码 {code}）")
                print(out)
        finally:
            path.write_text(original, encoding="utf-8")

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
    print("\n[OK] import 包路径门禁的端到端行为正确。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
