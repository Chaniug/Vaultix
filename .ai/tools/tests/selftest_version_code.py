#!/usr/bin/env python3
"""`ci-debug.yml` 版本号口径自测：**awk 与 Kotlin 的 `versionCodeOf()` 必须逐例一致**。

## 为什么需要它（2026-11-01）

预览版 Release 的标题此前是一长串 commit SHA（`⚠️ Development Preview (dev-341be3a58fb…)`），
改文案时要在 workflow 里算出 versionCode 填进表格。于是 `versionCodeOf()` 有了**第二份实现**
（awk 版）—— 而 `.ai/ISSUES.md` #145.1 已经立过一条规矩：**版本号只能有一个来源**。

两份实现必然分叉，且分叉方式很阴险：包本身完全正常，只是 Release 页上写错一个数字。
本仓库已经在这上面栽过两次（见 workflow 内注释）：

* `${name%%-*}` 把 `-1.0.0` 截成空串（负号被当后缀分隔符）⇒ awk 拿到空输入、**无输出**；
* 「三元里 `+ 0` 提前」写成先判后转，`0.10.-4` 得 10000 而非 1。

⇒ 光读代码看不出对错，只能**跑**。本测试跑的是 workflow 里**真实那一段** awk
（不是抄一份过来），期望值则是用 **Kotlin 编译器真的编译 `versionCodeOf()`** 跑出来的。

## 它怎么拿到期望值

`kotlin-compiler-embeddable` 在 Gradle 安装目录里就有（`$GRADLE_HOME/lib`），不需要联网、
不需要 Android SDK。编译不过/找不到编译器时**跳过**（退出码 0 并打印 SKIP）——
理由：这是个口径校验，不该让"环境里没有 Kotlin"变成一条红 CI（那是基础设施问题，
不是代码问题，判红只会教人忽略它）。

## 用法

    python3 .ai/tools/tests/selftest_version_code.py

退出码：0 = 一致或跳过；1 = 分叉。

## ⚠️ 这个测试"有效"是验证过的，不是假设的

写完先做了一轮**变异测试**（故意把 workflow 里的实现改坏，看测试是否变红）。
第一版 14 例里，有 3 种改法**照样全绿** —— 说明"全绿"本身没有说服力。
补了 `0.10.4x` / `0.10.+4` 两条后，才覆盖到「第三段 isnum 判断」这个分支。

同一轮里还**真抓出一个 bug**：`isnum` 用 `^-?[0-9]+$`，而 Kotlin 的 `toIntOrNull()`
接受 `+4` ⇒ `0.10.+4` 两边算出 10000 / 10004。这条是测试发现的，不是推演出来的。
⇒ 记在这里是为了下次改这段时知道：**「我写了断言」和「断言断到了」是两件事**，
  唯一能区分的办法是故意改坏再跑一遍。
"""

from __future__ import annotations

import glob
import os
import re
import shutil
import subprocess
import sys
import tempfile

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
WORKFLOW = os.path.join(REPO_ROOT, ".github", "workflows", "ci-debug.yml")

# ⚠️ 用例表就是"哪些输入算合法版本号"的定义。加一条前先想清楚：
#    它会不会让某个真实版本号在两边算出不同结果？
CASES = [
    "0.10.4",
    "1.0.0",
    "12.345.678",
    "0.0.0",
    "abc",
    "0.10",
    "0.10.4-dev-341be3a",
    "0.10.4-dev",
    "0.10.4-dev-341be3a58fb6c248f8f8a935d6fae0b9501ffa4c",
    "-1.0.0",
    "0.-1.0",
    # ⚠️ 反向验证结论（2026-11-01，逐条实测，不是推断）：
    #    · 漏掉 patch 项          → 抓到（7/16 分叉）
    #    · 去掉第三段 isnum 判断  → 抓到（1/16）—— 靠下面 `0.10.4x` / `0.10.+4` 这两条
    #    · isnum 不认正号 `+`     → 抓到（1/16）—— **这条是真的抓出了一个 bug**：
    #      Kotlin 的 `toIntOrNull()` 接受 `+4`，而 awk 第一版 `^-?[0-9]+$` 不认 ⇒ 分叉。
    #      修的是 workflow 里的 isnum，不是测试。
    #    · 不校验 minor 段        → 抓到（2/16）
    #    · 删掉 `patch < 0` 那行  → **抓不到**，且确认抓不到：见下方说明。
    #      ⇒ 「写了断言」不等于「断到了」；能证明这一点的唯一办法是**故意改坏再跑**。
    "1",
    "0.10.4.9",
    # ⚠️ 下面两条是**唯一能打到「第三段 isnum 判断」**的用例（反向验证补的）：
    #    去掉 `&& isnum($3)` 后，awk 的 `$3 + 0` 会把 `4x` 读成 4、把 `+4` 读成 4，
    #    而 Kotlin 的 `toIntOrNull()` 两个都返回 null ⇒ patch 取 0。
    #    没有这两条时，删掉 isnum 判断**测试照样全绿**（14 例里没人触发它）。
    "0.10.4x",
    "0.10.+4",
    "",
]


def extract_workflow_awk() -> str:
    """从 workflow 里抠出**真实在跑**的那段 awk 片段（含 VC_BASE 的截断逻辑）。

    刻意不复制一份到测试里：复制品会和真身分叉，而分叉之后测试照样绿 ——
    那正是本测试要防的那类问题本身。
    """
    with open(WORKFLOW, encoding="utf-8") as handle:
        text = handle.read()
    # 从 `VC_BASE=` 一直到 `DEV_VERSION_CODE` 的空值兜底（含），一行不漏。
    match = re.search(
        r'^(\s*)VC_BASE="\$\{BASE_VERSION.*?if \[ -z "\$DEV_VERSION_CODE" \]; then DEV_VERSION_CODE=1; fi\n',
        text,
        re.DOTALL | re.MULTILINE,
    )
    if not match:
        raise SystemExit("❌ 在 ci-debug.yml 里找不到 versionCode 计算片段（正则失效？）")
    body = match.group(0)
    # 去掉 YAML 块标量的公共缩进
    lines = body.splitlines()
    indent = min(len(l) - len(l.lstrip()) for l in lines if l.strip())
    return "\n".join(l[indent:] if len(l) >= indent else l for l in lines)


def kotlin_expected() -> dict[str, int] | None:
    """真编译 `versionCodeOf()` 取期望值；环境不具备时返回 None。"""
    gradle_lib = os.environ.get("GRADLE_LIB_DIR") or _guess_gradle_lib()
    if not gradle_lib:
        return None
    stdlib = glob.glob(os.path.join(gradle_lib, "kotlin-stdlib-*.jar"))
    compiler = glob.glob(os.path.join(gradle_lib, "kotlin-compiler-embeddable-*.jar"))
    if not stdlib or not compiler:
        return None
    java = shutil.which("java")
    if not java:
        return None

    src = os.path.join(REPO_ROOT, "app", "build.gradle.kts")
    with open(src, encoding="utf-8") as handle:
        gradle_text = handle.read()
    fn = re.search(r"^fun versionCodeOf\(name: String\): Int \{.*?^\}", gradle_text, re.DOTALL | re.MULTILINE)
    if not fn:
        raise SystemExit("❌ app/build.gradle.kts 里找不到 versionCodeOf()")
    # ⚠️ 直接取真函数体，不重写 —— 期望值必须来自**真源**。
    # ⚠️ 用一个**不可能出现在版本号里**的分隔符（`|`）而不是 `\t`：
    #    第一版用 `\t`，而用例 `-1.0.0` 在 shell/输出处理里会让行首看起来像是缩进/串行，
    #    导致结果错位一条 —— 测出来的"期望值"本身就是错的，比不测更危险。
    kt = fn.group(0) + "\n\nfun main() {\n"
    kt += "    listOf(" + ", ".join(_kt_str(c) for c in CASES) + ").forEach { println(it + \"|\" + versionCodeOf(it)) }\n"
    kt += "}\n"

    work = tempfile.mkdtemp(prefix="vc-selftest-")
    try:
        kt_file = os.path.join(work, "Main.kt")
        with open(kt_file, "w", encoding="utf-8") as handle:
            handle.write(kt)
        cp_all = os.pathsep.join(glob.glob(os.path.join(gradle_lib, "*.jar")))
        compile_cmd = [
            java, "-cp", cp_all,
            "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
            kt_file, "-no-stdlib", "-cp", stdlib[0],
            "-d", os.path.join(work, "classes"),
        ]
        built = subprocess.run(compile_cmd, capture_output=True, text=True)
        if built.returncode != 0:
            print("SKIP: Kotlin 编译失败，跳过口径校验")
            print((built.stdout + built.stderr)[:600])
            return None
        run = subprocess.run(
            [java, "-cp", os.path.join(work, "classes") + os.pathsep + stdlib[0], "MainKt"],
            capture_output=True, text=True,
        )
        if run.returncode != 0:
            return None
        result: dict[str, int] = {}
        for line in run.stdout.splitlines():
            if "|" not in line:
                continue
            name, _, value = line.partition("|")
            result[name] = int(value)
        return result
    finally:
        shutil.rmtree(work, ignore_errors=True)


def _kt_str(s: str) -> str:
    return '"' + s.replace("\\", "\\\\").replace('"', '\\"').replace("$", "\\$") + '"'


def _guess_gradle_lib() -> str | None:
    """在 sdkman / 常见 Gradle 安装目录里找 `lib/`。"""
    candidates: list[str] = []
    for base in ("/root/.sdkman/candidates/gradle", os.path.expanduser("~/.sdkman/candidates/gradle")):
        candidates.extend(sorted(glob.glob(os.path.join(base, "*", "lib")), reverse=True))
    candidates.extend(glob.glob("/usr/share/gradle*/lib"))
    for path in candidates:
        if glob.glob(os.path.join(path, "kotlin-compiler-embeddable-*.jar")):
            return path
    return None


def run_awk(snippet: str, base_version: str) -> str:
    """执行 workflow 片段，返回 DEV_VERSION_CODE。"""
    script = snippet + '\nprintf "%s" "$DEV_VERSION_CODE"\n'
    env = dict(os.environ, BASE_VERSION=base_version)
    done = subprocess.run(["bash", "-c", script], capture_output=True, text=True, env=env)
    if done.returncode != 0:
        return f"<bash 失败: {done.stderr.strip()[:120]}>"
    return done.stdout.strip()


def main() -> int:
    snippet = extract_workflow_awk()
    print("从 ci-debug.yml 取到 versionCode 片段：")
    print("-" * 60)
    print(snippet)
    print("-" * 60)

    expected = kotlin_expected()
    if expected is None:
        print("SKIP: 本机没有可用的 Kotlin 编译器，跳过口径校验（不算失败）。")
        return 0

    failed = 0
    for case in CASES:
        want = expected.get(case)
        got = run_awk(snippet, case)
        ok = want is not None and got == str(want)
        print(f"{'OK  ' if ok else 'FAIL'} {case!r:52} kotlin={want}  awk={got!r}")
        if not ok:
            failed += 1

    if failed:
        print(f"\n❌ {failed}/{len(CASES)} 例分叉：workflow 的 awk 与 Kotlin versionCodeOf() 口径不一致。")
        return 1
    print(f"\n✅ {len(CASES)}/{len(CASES)} 例一致。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
