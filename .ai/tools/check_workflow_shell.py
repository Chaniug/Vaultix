#!/usr/bin/env python3
"""workflow shell 的「静默吞行」检查 —— `bash -n` 抓不到的那一类。

## 为什么需要（2026-11-01，CI #340 实证）

改 `ci-debug.yml` 的签名指纹段时，有两次把两条语句写成了**同一行**：

```yaml
echo "signer_fingerprint=" >> "$GITHUB_OUTPUT"            echo "signer_subject=unknown" >> "$GITHUB_OUTPUT"
```

`bash -n` 说 **OK** —— 因为合并后语法完全合法：`echo` 的参数变成了
`= >> $GITHUB_OUTPUT echo signer_subject=unknown`，`>>` 只重定向一次。
于是：

* CI 全绿（构建、单测、发布都正常）；
* Release 页面**照常生成**，只是「签名」那一行印出
  `` ` `` + `SHA-256 echo signer_subject=unknown`；
* `$GITHUB_OUTPUT` 里 `signer_fingerprint` 的值是 `= >> /path echo signer_subject=unknown`。

⇒ **语法合法 ≠ 语义正确**，而这类错误的表现形式是"页面上一行字很怪"，
   没有任何门禁会因此变红。本脚本补上这一块。

## 判据

1. `>>` / `>` 重定向到 `$GITHUB_OUTPUT` / `$GITHUB_ENV` / `$GITHUB_OUTPUT` 之后，
   同一行若还有**非注释**内容 ⇒ 几乎一定是被吞进来的下一句；
2. 行内出现 `echo "` 两次以上（典型的一行两条 echo）；
3. `then` 之后紧跟非 `fi`/`else` 的裸命令，且同一行末尾又出现 `; fi`
   （本次另一类：`if ...; then X=1; fi` 是合法的单行写法，**不报** —— 见下）。

⚠️ 第 3 条刻意**不**实现成"then 后不许有东西"：`if cond; then X=1; fi` 是常见合法写法，
把它判红只会教人写 `# noqa`。真正危险的是**两条命令挤在一行**，所以只查第 1、2 条。

## 用法

    python3 .ai/tools/check_workflow_shell.py

退出码：0 = 干净；1 = 命中。
"""

from __future__ import annotations

import glob
import os
import re
import sys

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))

# 只查"往 step output 里写"的行：这类行的形状固定（echo "k=v" >> "$GITHUB_OUTPUT"），
# 尾巴上多出任何东西都是可疑的。反过来，普通 shell 行本来就允许塞多条命令，
# 全量去查会把 `cmd1 && cmd2` 这类正常写法一起误伤。
OUTPUT_REDIRECT = re.compile(r'>>\s*"\$(GITHUB_OUTPUT|GITHUB_ENV|GITHUB_PATH)"')

# `echo "..."` 在一行里出现两次及以上 —— 一行两条 echo，第二条的参数被第一条吃掉。
DOUBLE_ECHO = re.compile(r'echo\s+"[^"]*".*echo\s+"')


def check_text(path: str, text: str) -> list[str]:
    problems: list[str] = []
    for lineno, line in enumerate(text.splitlines(), 1):
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        # 1) 重定向后还有内容
        for match in OUTPUT_REDIRECT.finditer(line):
            tail = line[match.end():]
            # 允许行尾注释
            tail = tail.split(" #", 1)[0].strip()
            if tail and not tail.startswith("#"):
                problems.append(f"{path}:{lineno}: `{match.group(0)}` 之后还有内容 → {tail!r}")
        # 2) 一行两条 echo（仅当该行不是 heredoc 内容时；heredoc 内容在这里很难识别，
        #    但 `echo "..."` 出现两次的行在任何语境下都值得人看一眼）
        if len(re.findall(r'echo\s+"', line)) >= 2 and "|" not in line:
            problems.append(f"{path}:{lineno}: 一行里出现 2 个 `echo \"` → 疑似语句被吞并")
    return problems


def main() -> int:
    files = sorted(glob.glob(os.path.join(REPO_ROOT, ".github", "workflows", "*.yml")))
    files += sorted(glob.glob(os.path.join(REPO_ROOT, ".github", "workflows", "*.yaml")))
    if not files:
        print("SKIP: 没找到 workflow 文件")
        return 0

    all_problems: list[str] = []
    for path in files:
        with open(path, encoding="utf-8") as handle:
            all_problems.extend(check_text(os.path.relpath(path, REPO_ROOT), handle.read()))

    if all_problems:
        print(f"❌ 发现 {len(all_problems)} 处疑似「语句被吞并」：")
        for item in all_problems:
            print("  " + item)
        print("\n说明：这类错误 `bash -n` 会判合法，CI 也能全绿，只会让产物内容变怪。")
        return 1
    print(f"✅ {len(files)} 个 workflow 未发现语句吞并。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
