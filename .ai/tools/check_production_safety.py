#!/usr/bin/env python3
"""生产 Kotlin 代码的两条安全红线：`android.util.Log` 唯一出口 + 零 `!!`。

## 为什么单列一关（2026-11-14，#291）

`VaultixLog` 是密码管理器的日志门面：默认 `enabled=false`，关闭时**连消息 lambda
都不求值**，还有每 tag 50 条 / 60s 限频与「sink 抛异常不拖垮调用方」。但 R8 只裁
`if (BuildConfig.DEBUG) { ... }` 整块，**裁不掉源码里一句裸 `Log.d(...)`** ——
`BuildConfig.DEBUG` 由 buildType 决定、不由分支决定，所以「main 与 rele 源码一致」
并不等于「产物行为一致」。2026-11-11 整体排查实测 6 处绕过门面的裸 `Log`，
其中 `reseal $vaultId` 把完整库标识写进了 release 的 logcat。

`!!` 同理：编译 / 单测 / detekt 全绿，但可变 UI 状态在判空与断言之间被改一下，
就是凭据创建 / 断言签名 / 同步编排主路径上的一次 NPE。这类问题的共同点是
**编译期看不出来、真机上才炸**，所以把「形状」用源码文本钉死。

## 两条规则

1. 全仓各模块 `src/main/**/*.kt`：**禁止** `import android.util.Log` 与
   `Log.d/w/i/e/v/wtf(...)` 调用。唯一例外是 `VaultixApplication.kt` —— 那里是
   `VaultixLog.install { ... }` 的 sink 实现，必须直接用平台 Log，且本关会额外
   校验该装配仍在、仍按 `BuildConfig.DEBUG` 开启（白名单不许变成空子）。
2. 同样范围：**禁止** `!!`。测试代码（222 处断言习惯，登记表 B05）不在扫描范围，
   冻结的 `reference/bastion/` 也不在（它是只读参考实现，不进产物）。

注释与字符串字面量里的违规写法不算数 —— KDoc 正在解释「以前为什么错」，
把它算成违规会让门禁变成不许写教训。
"""
import os
import re
import sys

# 唯一允许直接用 android.util.Log 的文件：门面 sink 实现。
FACADE_SINK = "app/src/main/java/io/vaultix/vaultix/VaultixApplication.kt"
FACADE_INSTALL = "VaultixLog.install(enabled = BuildConfig.DEBUG)"

# 冻结参考实现不进产物；构建产物与 VCS 元数据不扫。
SKIP_DIRS = {"build", ".gradle", ".git", ".idea", "reference"}

BARE_LOG = re.compile(r"\bLog\.(?:d|w|i|e|v|wtf)\(|^\s*import\s+android\.util\.Log", re.M)
BANG_BANG = re.compile(r"!!")
STRING_LITERAL = re.compile(r'"(?:[^"\\]|\\.)*"')


def sanitize(text: str) -> str:
    """剥掉注释与字符串字面量，只留可用于形状判定的代码。

    - KDoc / 块注释行（以 `*` 或 `/*` 开头）整行跳过；
    - 行注释（含行内 `//`）一律从 `//` 截断；
    - 字符串字面量抹成 `""`，避免文案里的 `!!` 或 `Log.d` 造成假红。

    ⚠️ 刻意不追求完整词法分析：`"${foo!!}"` 这种字符串模板里的断言会被抹掉，
    本项目生产代码没有这种写法；真出现时以「别在模板里写 !!」处理，而不是放宽规则。
    """
    lines = []
    for line in text.splitlines():
        stripped = line.lstrip()
        if stripped.startswith("*") or stripped.startswith("/*"):
            continue
        comment = line.find("//")
        if comment >= 0:
            line = line[:comment]
        lines.append(line)
    return STRING_LITERAL.sub('""', "\n".join(lines))


def iter_main_kotlin(root: str):
    """遍历各模块 `src/main` 下的 Kotlin；跳过生成物 / 冻结参考实现。"""
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
        for name in filenames:
            if not name.endswith(".kt"):
                continue
            path = os.path.join(dirpath, name)
            if "/src/main/" in path.replace("\\", "/"):
                yield path


def scan(root: str):
    """返回 (裸 Log 违规, `!!` 违规, 门面问题)，每项是可直接打印的字符串列表。"""
    log_offenders = []
    bang_offenders = []
    for path in iter_main_kotlin(root):
        rel = os.path.relpath(path, root).replace("\\", "/")
        code = sanitize(open(path, encoding="utf-8").read())
        for lineno, line in enumerate(code.splitlines(), 1):
            if BANG_BANG.search(line):
                bang_offenders.append(f"{rel}:{lineno}: {line.strip()}")
            if rel != FACADE_SINK and BARE_LOG.search(line):
                log_offenders.append(f"{rel}:{lineno}: {line.strip()}")

    facade = os.path.join(root, *FACADE_SINK.split("/"))
    facade_problems = []
    if not os.path.isfile(facade):
        facade_problems.append(f"门面 sink 文件不存在：{FACADE_SINK}")
    elif FACADE_INSTALL not in open(facade, encoding="utf-8").read():
        facade_problems.append(
            f"{FACADE_SINK} 里找不到 `{FACADE_INSTALL}` —— "
            "白名单只允许 DEBUG-only 装配，门面被删/被改成常开时本关必须红"
        )
    return log_offenders, bang_offenders, facade_problems


def main(argv):
    root = argv[1] if len(argv) > 1 else os.getcwd()
    log_offenders, bang_offenders, facade_problems = scan(root)
    problems = []
    if log_offenders:
        problems.append(
            "生产代码绕过 VaultixLog 门面（release 下 R8 裁不掉裸 Log）：\n  "
            + "\n  ".join(log_offenders)
        )
    if bang_offenders:
        problems.append(
            "生产代码出现 !! 空值断言（可变状态漂移即 NPE）：\n  "
            + "\n  ".join(bang_offenders)
        )
    if facade_problems:
        problems.append("日志门面装配异常：\n  " + "\n  ".join(facade_problems))
    if problems:
        print("::error title=production safety::生产代码安全红线检查未通过")
        for p in problems:
            print("  ✗", p)
        return 1
    print("✅ 生产代码安全红线通过（裸 Log 0 · !! 0 · 门面 DEBUG-only 装配在位）")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
