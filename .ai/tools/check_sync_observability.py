#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""#297：同步链路必须能自证「改动到底有没有进队列、有没有推上去」。

## 为什么单列一关（2026-11-16，真机第二份日志）

#296 之后用户再测，日志退化成只剩一行：

```
15:51:54.315  VaultixSync: sync skipped: revision 未变（<phone>35）
```

**连一条 `flushPending` 都没有。** 而此前（#296 之前）明明是有的：

```
14:28:52.805  VaultixSync: flushPending op=UPDATE id=... code=422 definitive=true
```

同一个操作、同一个库，日志从"有 422"变成"什么都没有"，于是无法区分两种
**修法完全不同**的故障：

| 可能 | 该改哪里 |
|---|---|
| 改动**没进队列**（写路径没跑 / 被前置条件挡住） | `ItemRepositoryImpl` |
| 改动进了队列但**被清掉**（推送路径丢了） | `BitwardenSyncService.flushPending` |

根因是 `flushPending` 在队列为空时**静默 `return Result.success(Unit)`**：
没日志、没痕迹，而 `executeSync` 随后照常报 `Skipped`（上层当成功）。

⇒ 本关钉住四件事：

1. `flushPending` 的空队列分支**必须留 WARN 日志**（含"队列为空"与"不会上传"两处语义）；
2. `flushPending` 开头必须报「待推送 N 条」与按 op 的类型摘要；
3. `flushPending` 收尾必须有成功/失败统计（`logFlushSummary`）；
4. 写路径的统一出口 `flushAfterLocalWrite` 在成功/失败两条分支上都要留痕。

外加一条**日志铁律**：摘要不得包含条目 id（`countByOp` 只允许聚合 op 类型）。

## 为什么这类检查值得单列

"删掉一行日志"不会有编译错误、不会有测试失败、也不会被 detekt 抓到，
但它会让**下一次**排查重新回到靠猜。这正是 #296 花了两轮才走出来的坑。
"""
import os
import re
import sys

from check_production_safety import sanitize

SYNC = "data/bitwarden/src/main/java/io/vaultix/data/bitwarden/sync/BitwardenSyncService.kt"
REPO = "data/repository/src/main/java/io/vaultix/data/repository/ItemRepositoryImpl.kt"
OBSERVABILITY_TEST = (
    "data/bitwarden/src/test/java/io/vaultix/data/bitwarden/sync/"
    "BitwardenSyncFlushObservabilityTest.kt"
)


def _read(root: str, rel: str) -> str:
    path = os.path.join(root, rel)
    if not os.path.isfile(path):
        return ""
    with open(path, encoding="utf-8") as handle:
        return handle.read()


def _brace_body(text: str) -> str:
    """从第一个 `{` 起做括号配平，返回**函数体本身**。

    ⚠️ 为什么必须配平，不能只靠"下一个 `fun`"作下界（本轮自测 ⑧ 抓到的假绿）：
    下一个函数的 KDoc 里也写着 `留在队列` / `保留在队列` 之类的词，按"到下一个 fun
    为止"截取会把**注释**算进函数体，于是"删掉函数里的文案"这种变异被判绿。
    """
    start = text.find("{")
    if start < 0:
        return text
    depth = 0
    for idx in range(start, len(text)):
        ch = text[idx]
        if ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
            if depth == 0:
                return text[: idx + 1]
    return text


def _method(code: str, name: str) -> str:
    """截一个函数体：从 `fun name(` 起到下一个 `fun` 为止（缩进不敏感）。

    支持**扩展函数**形态（`List<PendingOpEntity>.countByOp()`）—— 只认 `fun name(`
    会把 `countByOp` 判成"缺失"而假红（#296 已在 `safeErrorBody` 上踩过同类问题）。
    """
    m = re.search(
        r"\n[ \t]*(?:private |internal |public )?(?:suspend )?fun "
        r"(?:[\w.<>\[\], ]+\.)?" + re.escape(name) + r"\(",
        code,
    )
    if not m:
        return ""
    return _brace_body(code[m.end():])


def _raw_method(raw: str, name: str) -> str:
    """在**原文**上截同名函数体（用来检查字符串字面量里的中文文案）。

    ⚠️ 为什么不直接用 `_method`：#295 已经踩过一次 —— `sanitize()` 会把
    字符串字面量整体抹成 `""`，于是所有"日志文案里必须有某个词"的断言都会
    在**代码完全正确**时假红。结构判断走 sanitize，文案判断必须走原文。
    """
    m = re.search(
        r"\n[ \t]*(?:private |internal |public )?(?:suspend )?fun "
        r"(?:[\w.<>\[\], ]+\.)?" + re.escape(name) + r"\(",
        raw,
    )
    if not m:
        return ""
    return _brace_body(raw[m.end():])


def scan(root: str):
    problems = []

    raw_sync = _read(root, SYNC)
    if not raw_sync:
        return [f"找不到 {SYNC} —— 文件移动/改名时本关必须红，不得静默通过"]
    sync = sanitize(raw_sync)

    flush = _method(sync, "flushPending")
    # 原文版本：日志文案都在字符串字面量里，sanitize 后会变成 `""`（#295 的坑）。
    raw_flush = _raw_method(raw_sync, "flushPending")
    if not flush:
        problems.append(f"{SYNC}: 找不到 flushPending（改名后本关失效）")
    else:
        if re.search(r"if\s*\(\s*ops\.isEmpty\(\)\s*\)\s*return\s+Result\.success", flush):
            problems.append(
                f"{SYNC}: flushPending 队列为空时又变回静默成功 —— "
                "日志里将分不清「没进队列」与「被清掉」（#297）"
            )
        else:
            # 空队列分支必须留痕：日志文案里要能读出这两层语义。
            #
            # ⚠️ 这里**只能看原文前 60 行里第一个 `if (ops.isEmpty())` 之后、
            #    第一个 `return Result.success(Unit)` 之前那一段**：
            #    - 用 sanitize 后的文本看不到中文文案（字面量被抹成 `""`）；
            #    - 但整段 KDoc 里也写着 `return Result.success(Unit)`（"此前是静默…"），
            #      所以不能用"全大写下界"，必须先锚到真正的 `if (ops.isEmpty()) {`。`raw_flush` 已在上面取好。
            anchor = re.search(r"if\s*\(\s*ops\.isEmpty\(\)\s*\)\s*\{", raw_flush)
            if not anchor:
                problems.append(f"{SYNC}: flushPending 找不到 `if (ops.isEmpty()) {{` 分支（#297）")
            else:
                # ⚠️ 用行首锚定的正则找**语句**，不能用 `find("return Result.success(Unit)")`：
                #    KDoc 注释里也写着 `return Result.success(Unit)`（"此前是静默…"），
                #    朴素查找会命中注释而把分支体截成空 —— 本轮就因此假红了两次。
                end_m = re.search(
                    r"\n[ \t]+return\s+Result\.success\(Unit\)", raw_flush[anchor.end():]
                )
                block = raw_flush[anchor.end(): anchor.end() + end_m.start()] if end_m else ""
                if "VaultixLog" not in block:
                    problems.append(
                        f"{SYNC}: flushPending 空队列分支没有留日志 —— "
                        "用户日志里会完全没有同步痕迹（#297）"
                    )
                for token, why in (
                    ("队列为空", "空队列事实"),
                    ("不会上传", "后果说明"),
                ):
                    if token not in block:
                        problems.append(
                            f"{SYNC}: flushPending 空队列日志缺少「{why}」（找不到 `{token}`）（#297）"
                        )

        # 开头摘要：待推送条数 + 按 op 的类型分布。
        # ⚠️ 两处都在**字符串插值**里，sanitize 后整串被抹成 `""` ⇒ 必须读原文。
        if not re.search(r"待推送\s*\$?\{?ops\.size", raw_flush):
            problems.append(f"{SYNC}: flushPending 开头没有报「待推送 N 条」（#297）")
        if "countByOp()" not in raw_flush:
            problems.append(f"{SYNC}: flushPending 开头没有按 op 类型摘要（countByOp）（#297）")
        if "logFlushSummary(" not in flush:
            problems.append(
                f"{SYNC}: flushPending 收尾没有调用 logFlushSummary —— "
                "推上去几条无从判断（#297）"
            )

    # 摘要只许聚合 op 类型，不许带条目 id。
    count_fn = _method(sync, "countByOp")
    if not count_fn:
        problems.append(f"{SYNC}: 缺少 countByOp 扩展（#297）")
    elif re.search(r"\bcipherId\b", count_fn):
        problems.append(
            f"{SYNC}: countByOp 引用了 cipherId —— 日志摘要不得带条目 id（VaultixLog 铁律）（#297）"
        )

    summary_fn = _method(sync, "logFlushSummary")
    if not summary_fn:
        problems.append(f"{SYNC}: 缺少 logFlushSummary（#297）")
    else:
        # ⚠️ 必须在**函数体原文**里找文案：整份 raw_sync 里 KDoc 也写了这些词，
        #    用全文查找会让"删掉函数里的文案"这种变异假绿（本轮自测 ⑧ 抓到的）。
        raw_summary = _raw_method(raw_sync, "logFlushSummary")
        for token, why in (("全部推送成功", "成功条数"), ("留在队列", "失败残留")):
            if token not in raw_summary:
                problems.append(
                    f"{SYNC}: logFlushSummary 缺少「{why}」文案（找不到 `{token}`）（#297）"
                )

    # 写路径的统一出口：成功与失败都必须留痕。
    raw_repo = _read(root, REPO)
    if not raw_repo:
        problems.append(f"找不到 {REPO} —— 写路径日志无法校验（#297）")
    else:
        repo = sanitize(raw_repo)
        flush_after = _method(repo, "flushAfterLocalWrite")
        if not flush_after:
            problems.append(f"{REPO}: 找不到 flushAfterLocalWrite（改名后本关失效）")
        else:
            if re.search(r"if\s*\(\s*flush\.isSuccess\s*\)\s*return\s+VaultSaveOutcome\.Synced", flush_after):
                problems.append(
                    f"{REPO}: flushAfterLocalWrite 成功分支又变回直接 return —— "
                    "写路径将没有任何「已上云」痕迹（#297）"
                )
            elif "VaultixLog" not in flush_after:
                problems.append(f"{REPO}: flushAfterLocalWrite 没有留日志（#297）")
            if "VaultixLog" in flush_after and "改动仍在队列" not in raw_repo:
                problems.append(
                    f"{REPO}: flushAfterLocalWrite 失败分支没有说明「改动仍在队列」—— "
                    "用户会以为改动丢了（#297）"
                )
        # ⚠️ 必须匹配**常量定义**，不能只判断 "SYNC_TAG" 是否出现：
        #    使用处（`VaultixLog.w(SYNC_TAG)`）会让"删掉定义"的变异假绿（自测 ⑪）。
        if not re.search(r'const\s+val\s+SYNC_TAG\s*=\s*"VaultixSync"', raw_repo):
            problems.append(
                f'{REPO}: 缺少 `const val SYNC_TAG = "VaultixSync"` —— '
                "写路径与推送必须共用同一个 tag 才能在一条时间线上对照（#297）"
            )

    # 行为测试：三条可观测性契约都要有用例，否则撤掉日志不会有人发现。
    obs_test = _read(root, OBSERVABILITY_TEST)
    if not obs_test:
        problems.append(f"{OBSERVABILITY_TEST}: 缺少可观测性行为测试（#297）")
    else:
        for case, why in (
            ("队列为空时必须留下日志", "空队列留痕"),
            ("推送成功时必须报告条数", "成功摘要"),
            ("推送失败时必须说明改动仍在队列", "失败残留说明"),
        ):
            if case not in obs_test:
                problems.append(f"{OBSERVABILITY_TEST}: 缺少「{why}」用例（#297）")

    return problems


def main() -> int:
    root = sys.argv[1] if len(sys.argv) > 1 else "."
    problems = scan(root)
    if not problems:
        print("✅ 同步可观测性契约通过（#297）")
        return 0
    print("❌ 同步可观测性契约被破坏（#297）：")
    for item in problems:
        print(f"  - {item}")
    return 1


if __name__ == "__main__":
    sys.exit(main())
