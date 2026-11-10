#!/usr/bin/env python3
"""KDBX 来源标签唯一真值源哨兵（2026-11-10，真机反馈 #264 引入）。

## 为什么需要这个脚本

`ui/common/VaultOriginLabel.kt` 的 `vaultOriginLabel()` 是**唯一**该判断
「这个 KDBX 库到底在本地 / OneDrive / WebDAV」的地方。

但 2026-11-10 用户报：OneDrive 上的 KDBX 库，在**库列表**里显示「本地文件 · uit」。
真因是 `VaultListScreen.vaultSubtitle()` **自己又判了一遍**：

```kotlin
VaultKind.KDBX -> stringResource(R.string.vault_card_kdbx_subtitle, vault.name)
```

注释还写着「标注『本地文件』让用户一眼知道它不在云端」——
对 OneDrive 库来说这恰恰是**假话**。而密码页的库状态行用的是
`vaultOriginLabel`，是对的 ⇒ 同一份数据在两个页面叫两个名字。

## 本脚本查什么

在 `ui/` 下，**除 `VaultOriginLabel.kt` 之外**的任何文件里，
不允许出现同时满足下面两条的 `when (…kind)` 分支：

1. 分支值是 `VaultKind.KDBX`；
2. 分支体内引用了 `vault_card_kdbx_subtitle`。

（`vault_origin_onedrive` / `vault_origin_webdav` 也一样禁止 —— 那是
`kdbxSourceLabel` 的私有实现细节，别处出现就是在复制那套判据。）

命中即报错并指路 `vaultOriginLabel`。

## 为什么是"字符串哨兵"而不是单测

`vaultSubtitle()` 是 `private @Composable`，纯 JVM 单测测不到；
用 Compose UI 测试（instrumentation）来测"副标题文案"成本极高，
而它出错的方式又极其隐蔽 —— 只在一个特定来源的库上显示错一个词。
静态扫描是这一层唯一性价比合理的门禁（同 `check_ui_hardcoded_text.py` 的思路）。

退出码：0 = 通过，1 = 有违例。
"""

from __future__ import annotations

import pathlib
import re
import sys

REPO_ROOT = pathlib.Path(__file__).resolve().parents[2]
UI_DIR = REPO_ROOT / "app/src/main/java/io/vaultix/vaultix/ui"

# 唯一真值源自己当然可以引用这些资源。
ALLOWED_FILE = "VaultOriginLabel.kt"

# 这些资源只该出现在 kdbxSourceLabel() 里。
FORBIDDEN_RES = ("vault_card_kdbx_subtitle", "vault_origin_onedrive", "vault_origin_webdav")

# `VaultKind.KDBX ->` 起头的一段（到下一个同/更浅缩进的分支或 `}` 为止）。
# 用"取分支值之后的一小段原文"来判，够用且不会跨函数。
KDBX_BRANCH = re.compile(r"VaultKind\.KDBX\s*->")


def branch_body(text: str, start: int, max_chars: int = 300) -> str:
    """粗略取 `->` 之后的分支体：到同缩进的 `VaultKind.` 分支或 `}` 收尾。"""
    rest = text[start:start + max_chars]
    cut = re.search(r"\n\s*VaultKind\.|\n\s*\}", rest)
    return rest[: cut.start()] if cut else rest


def main() -> int:
    if not UI_DIR.is_dir():
        print(f"❌ 找不到 UI 目录：{UI_DIR}", file=sys.stderr)
        return 1

    violations = []
    for path in sorted(UI_DIR.rglob("*.kt")):
        if path.name == ALLOWED_FILE:
            continue
        text = path.read_text(encoding="utf-8")
        for m in KDBX_BRANCH.finditer(text):
            body = branch_body(text, m.end())
            for res in FORBIDDEN_RES:
                if res in body:
                    line = text[: m.start()].count("\n") + 1
                    rel = path.relative_to(REPO_ROOT)
                    violations.append((rel, line, res))

    if violations:
        print("❌ KDBX 来源标签被复制成了第二份：", file=sys.stderr)
        for rel, line, res in violations:
            print(f"   {rel}:{line}  引用了 {res}", file=sys.stderr)
        print(
            "\n   这些资源只属于 ui/common/VaultOriginLabel.kt 的 kdbxSourceLabel()。\n"
            "   要显示 KDBX 库的来源，请调用 vaultOriginLabel(vault) ——\n"
            "   它已经分好本地 / OneDrive / WebDAV 三种，别在别处再判一次：\n"
            "   两份判据必然漂移（2026-11-10 的 #264 就是 OneDrive 库被显示成「本地文件」）。",
            file=sys.stderr,
        )
        return 1

    print("✅ KDBX 来源标签只有唯一真值源（vaultOriginLabel）")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
