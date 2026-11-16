#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""#296：HTTP 422 不得静默弃单，且失败必须留下可定位的脱敏响应体。

## 为什么单列一关（2026-11-16，真机日志）

```
14:28:52.805 VaultixSync: flushPending op=UPDATE id=... code=422 definitive=true conflict=false
14:29:03.471 VaultixSync: sync skipped: revision 未变
```

链路：422 落入 `DEFINITIVE_HTTP_CODES` ⇒ pending op 被 `remove` ⇒ 下一次同步
没有 op 可推、revision 又没变 ⇒ `Skipped` ⇒ 界面像同步成功，云端什么都没有。
这是**用户数据静默丢失**，而且 `flushPending` 只记 code、不记响应体，
排查只能靠猜（本次就猜错了两轮）。

Vaultwarden 的业务校验错误一律 HTTP 400；422 基本只来自 Rocket 的
`Json<CipherData>` 守卫，响应体里会写明 `missing field` / `invalid type`。
所以正确处置是：

1. 422 与 409 一样**留队重试**，不许弃单；
2. 失败日志必须带**脱敏**响应体（`safeErrorBody`），否则下一轮还是没法定位；
3. UPDATE 推送成功后必须清掉 `vaultixLocalRevisionDate`，否则详情页
   「修改于」永远停在导入时间，用户会继续拿错误判据判断同步结果。

## 检查项

- 常量 `HTTP_UNPROCESSABLE_ENTITY = 422` 存在；
- `DEFINITIVE_HTTP_CODES` 的排除集合里含该常量（不能只声明不接线）；
- `flushPending` 失败日志调用 `safeErrorBody(...)`；
- `safeErrorBody` 里对 EncString / JWT / 长 token 三种形态都做了替换，并限长；
- `advanceLocalRevision` 里清 `vaultixLocalRevisionDate = null`；
- 行为测试里 422 留队用例与脱敏用例都在。

注释里提到旧写法不算违规（复用 `check_production_safety.sanitize`），
但字符串字面量会被抹掉，所以脱敏占位符那一条读原文。
"""
import os
import re
import sys

from check_production_safety import sanitize

SYNC = "data/bitwarden/src/main/java/io/vaultix/data/bitwarden/sync/BitwardenSyncService.kt"
FAILURE_TEST = (
    "data/bitwarden/src/test/java/io/vaultix/data/bitwarden/sync/"
    "BitwardenSyncPendingOpFailureTest.kt"
)
REVISION_TEST = (
    "data/bitwarden/src/test/java/io/vaultix/data/bitwarden/sync/"
    "BitwardenSyncRevisionBaselineTest.kt"
)


def _read(root: str, rel: str) -> str:
    path = os.path.join(root, *rel.split("/"))
    if not os.path.isfile(path):
        return ""
    return open(path, encoding="utf-8").read()


def _method(code: str, name: str) -> str:
    """截一个方法体：从 `fun name(` 起到下一个 `fun` 为止（缩进不敏感）。

    #296 新增的 `safeErrorBody` / `logFlushFailure` 是**顶层**私有函数（无缩进），
    只认 4 空格会取不到函数体而产生假绿，这里必须两种缩进都认。
    """
    m = re.search(
        r"\n[ \t]*(?:private |internal |public )?(?:suspend )?fun " + re.escape(name) + r"\(", code
    )
    if not m:
        return ""
    tail = code[m.end():]
    nxt = re.search(r"\n[ \t]*(?:private |internal |public )?(?:suspend )?fun ", tail)
    return tail[:nxt.start()] if nxt else tail


def scan(root: str):
    problems = []
    raw = _read(root, SYNC)
    if not raw:
        return [f"找不到 {SYNC} —— 文件移动/改名时本关必须红，不得静默通过"]

    code = sanitize(raw)

    if not re.search(r"HTTP_UNPROCESSABLE_ENTITY\s*=\s*422", code):
        problems.append(f"{SYNC}: 缺少 `HTTP_UNPROCESSABLE_ENTITY = 422` 常量（#296）")

    definitive = re.search(r"DEFINITIVE_HTTP_CODES\s*=(.*?)\n\s*}", code, re.S)
    if not definitive:
        problems.append(f"{SYNC}: 找不到 `DEFINITIVE_HTTP_CODES` 定义（改名后本关失效）")
    elif not re.search(r"!in\s+setOf\([^)]*HTTP_UNPROCESSABLE_ENTITY", definitive.group(1)):
        problems.append(
            f"{SYNC}: 422 仍在 DEFINITIVE_HTTP_CODES 的弃单集合里 —— "
            "用户改动会被静默丢弃，下一次同步直接 Skipped（#296）"
        )

    flush = _method(code, "flushPending")
    if "logFlushFailure(" not in flush:
        problems.append(
            f"{SYNC}: flushPending 失败分支没有调用 logFlushFailure —— "
            "422 的真实原因（missing field / invalid type）进不了日志（#296）"
        )

    log_fn = _method(code, "logFlushFailure")
    # `::safeErrorBody` 这种函数引用没有括号；只认 `safeErrorBody(` 会假红。
    if "safeErrorBody" not in log_fn:
        problems.append(
            f"{SYNC}: logFlushFailure 没有调用 safeErrorBody —— "
            "失败日志只记 code，下一轮还是只能靠猜（#296）"
        )

    safe_fn = re.search(r"fun safeErrorBody\(", raw)
    if not safe_fn:
        problems.append(f"{SYNC}: 缺少 safeErrorBody 定义（#296）")
    else:
        body = raw[safe_fn.start():safe_fn.start() + 1600]
        # 字符串字面量被 sanitize 抹掉，这里必须读原文。
        for token, why in (
            ("<enc>", "EncString 密文"),
            ("<jwt>", "JWT"),
            ("<redacted>", "长 base64 token"),
        ):
            if f'"{token}"' not in body:
                problems.append(f"{SYNC}: safeErrorBody 没有替换{why}（占位符 `{token}` 缺失）")
        if not re.search(r"\.take\(\s*MAX_ERROR_BODY_CHARS\s*\)", body):
            problems.append(f"{SYNC}: safeErrorBody 没有限长，错误体可能冲爆日志（#296）")
        if not re.search(r"MAX_ERROR_BODY_CHARS\s*=\s*512", code):
            problems.append(f"{SYNC}: 错误体限长常量不是 512（#296）")

    advance = _method(code, "advanceLocalRevision")
    if not re.search(r"vaultixLocalRevisionDate\s*=\s*null", advance):
        problems.append(
            f"{SYNC}: UPDATE 推送成功后没有清掉 vaultixLocalRevisionDate —— "
            "详情页「修改于」永远停在导入时间，用户会误判同步结果（#296）"
        )

    failure_test = _read(root, FAILURE_TEST)
    if "422" not in failure_test or "422 必须保留在队列" not in failure_test:
        problems.append(f"{FAILURE_TEST}: 缺少 422 留队重试的行为用例（#296）")
    if "错误响应体必须脱敏后可见" not in failure_test:
        problems.append(f"{FAILURE_TEST}: 缺少响应体脱敏用例（#296）")

    revision_test = _read(root, REVISION_TEST)
    if "UPDATE 推送成功后必须清除本地展示时间" not in revision_test:
        problems.append(f"{REVISION_TEST}: 缺少清除本地展示时间的行为用例（#296）")

    return problems


def main() -> int:
    root = sys.argv[1] if len(sys.argv) > 1 else "."
    problems = scan(root)
    if not problems:
        print("✅ HTTP 422 失败处置契约通过（#296）")
        return 0
    print("❌ HTTP 422 失败处置契约被破坏（#296）：")
    for item in problems:
        print(f"  - {item}")
    return 1


if __name__ == "__main__":
    sys.exit(main())
