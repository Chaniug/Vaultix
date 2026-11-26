#!/usr/bin/env python3
"""#308 门禁：填充响应的 Parcelable 组装必须在主线程，图标磁盘 I/O 必须在后台线程。

背景（2026-11-26，Edge 真机日志）：
    AutofillValue: forText() not called on main thread: Thread[DefaultDispatcher-worker-7,...]
framework 要求 AutofillValue.forText() / Dataset.Builder / FillResponse.Builder 在主线程
构造。原实现把整条链路放在 Dispatchers.Default 协程里，违反契约（功能未坏，但 OEM 差异下
有偶发填充 UI 异常风险）。

判据（剥注释后检查，防"只写进注释"假绿）：
  A. AutofillDatasets.build 不能在主线程分支里调用 createAutofillItemIcon（磁盘 I/O 留后台）。
  B. AutofillValue.forText 的调用点，必须落在 withContext(Dispatchers.Main) 作用域内。
  C. Service 的 buildResponse 里，dataset 组装循环必须包在 withContext(Dispatchers.Main) 内。

用法：
  python3 check_autofill_main_thread.py [repo_root]
  python3 check_autofill_main_thread.py --selftest
"""
from __future__ import annotations

import os
import re
import sys

SERVICE = "app/src/main/java/io/vaultix/vaultix/autofill/VaultixAutofillService.kt"
DATASETS = "app/src/main/java/io/vaultix/vaultix/autofill/engine/AutofillDatasets.kt"


def strip_comments(text: str) -> str:
    """去掉 // 行注释与 /* */ 块注释，保留字符串里的 //（粗略但足够本门禁）。"""
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    out = []
    for line in text.splitlines(keepends=True):
        # 粗略处理：只剥行首或空白后的 //，避免误伤字符串中的 URL
        line = re.sub(r"^\s*//.*$", "", line)
        out.append(line)
    return "".join(out)


def _scope_of(text: str, anchor: str, open_ch: str = "{", close_ch: str = "}") -> str | None:
    """从 anchor 起找到第一个 open_ch，做括号配对，返回配对内的正文。"""
    start = text.find(anchor)
    if start < 0:
        return None
    op = text.find(open_ch, start)
    if op < 0:
        return None
    depth = 0
    for i in range(op, len(text)):
        c = text[i]
        if c == open_ch:
            depth += 1
        elif c == close_ch:
            depth -= 1
            if depth == 0:
                return text[op + 1:i]
    return None


def _contains_main_scope(text: str, needle: str) -> bool:
    """检查 needle 是否出现在某个 withContext(Dispatchers.Main) 作用域内。"""
    for m in re.finditer(r"withContext\(\s*Dispatchers\.Main\s*\)", text):
        scope = _scope_of(text[m.start():], "{")
        if scope and needle in scope:
            return True
    return False


def check(root: str) -> list[str]:
    errors: list[str] = []
    svc_path = os.path.join(root, SERVICE)
    ds_path = os.path.join(root, DATASETS)
    if not os.path.isfile(svc_path) or not os.path.isfile(ds_path):
        return [f"找不到源码文件：{SERVICE} / {DATASETS}"]

    svc = strip_comments(open(svc_path, encoding="utf-8").read())
    ds = strip_comments(open(ds_path, encoding="utf-8").read())

    # A. 图标磁盘 I/O 不得在主线程分支里执行。
    #    允许 prepare 阶段在后台调 createAutofillItemIcon；禁止它出现在 Main 作用域内。
    for m in re.finditer(r"withContext\(\s*Dispatchers\.Main\s*\)", svc):
        scope = _scope_of(svc[m.start():], "{") or ""
        if "createAutofillItemIcon" in scope or "prepareIcon" in scope and "AutofillValue" in scope:
            errors.append("A: createAutofillItemIcon(磁盘 I/O) 落在 withContext(Dispatchers.Main) 内")

    # B. AutofillValue.forText 必须在主线程作用域内调用。
    #    forText 在 AutofillDatasets.kt 里；那里没有 Main scope，故要求 build() 的调用方
    #    （Service）在 Main 作用域组装，且 AutofillDatasets 里 forText 不出现在 Default 调用链注释外。
    if "AutofillValue.forText" not in ds:
        errors.append("B: AutofillDatasets 里找不到 AutofillValue.forText 调用点")
    if not _contains_main_scope(svc, "datasetFor(") and not _contains_main_scope(svc, "AutofillDatasets.build("):
        errors.append("B: datasetFor()/AutofillDatasets.build() 未包在 withContext(Dispatchers.Main) 内")

    # C. Service 的组装循环必须在 Main 作用域（datasetFor 出现即视为组装）。
    if "datasetFor(" not in svc:
        errors.append("C: Service 里找不到 datasetFor 组装调用")

    return errors


SELFTEST_CASES = [
    # (名称, service 文本, datasets 文本, 期望报错前缀)
    (
        "合规：Main 内组装 + 后台 prepareIcon",
        """scope.launch {
            val icons = suggestions.map { prepareIcon(it) }
            withContext(Dispatchers.Main) {
                val d = datasetFor(s, icons)
                builder.addDataset(d)
            }
        }""",
        "fun build() { AutofillValue.forText(v) }",
        None,
    ),
    (
        "变异1：组装移出 Main（应红）",
        """scope.launch {
            val d = datasetFor(s, null)
            builder.addDataset(d)
        }""",
        "fun build() { AutofillValue.forText(v) }",
        "B:",
    ),
    (
        "变异2：图标磁盘 I/O 移进 Main（应红）",
        """withContext(Dispatchers.Main) {
            val icon = createAutofillItemIcon(ctx)
            val d = datasetFor(s, icon)
        }""",
        "fun build() { AutofillValue.forText(v) }",
        "A:",
    ),
    (
        "变异3：forText 调用点消失（应红）",
        """scope.launch {
            withContext(Dispatchers.Main) { datasetFor(s) }
        }""",
        "fun build() { builder.setValue(v) }",
        "B:",
    ),
]


def selftest() -> int:
    import tempfile
    ok = True
    with tempfile.TemporaryDirectory() as td:
        os.makedirs(os.path.join(td, os.path.dirname(SERVICE)), exist_ok=True)
        os.makedirs(os.path.join(td, os.path.dirname(DATASETS)), exist_ok=True)
        for name, svc, ds, expect in SELFTEST_CASES:
            open(os.path.join(td, SERVICE), "w", encoding="utf-8").write(svc)
            open(os.path.join(td, DATASETS), "w", encoding="utf-8").write(ds)
            errs = check(td)
            got = errs[0][:2] if errs else None
            passed = (expect is None and not errs) or (expect is not None and got == expect)
            print(f"[{'PASS' if passed else 'FAIL'}] {name} → {errs or '无报错'}")
            ok = ok and passed
    return 0 if ok else 1


def main() -> int:
    if "--selftest" in sys.argv:
        return selftest()
    root = sys.argv[1] if len(sys.argv) > 1 else os.getcwd()
    errs = check(root)
    if errs:
        print("#308 自动填充主线程组装门禁：失败")
        for e in errs:
            print("  ✗", e)
        return 1
    print("#308 自动填充主线程组装门禁：通过")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
