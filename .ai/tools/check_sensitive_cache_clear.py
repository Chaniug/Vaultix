#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""附件 P2：敏感明文缓存「三链路清理」门禁（2026-11-28）。

## 为什么需要门禁
附件下载后以**明文**落在 `cacheDir/vaultix-sensitive/attachments/<itemId>/`。
系统只在存储紧张时回收 cacheDir —— 一台大容量手机可能几个月都不回收。
所以有三条必须成立的清理链路，任何一条断了都是「明文长期滞留磁盘」：

1. **锁库**：`VaultLockManagerImpl` 锁库时调 `sensitiveCache.clearAll()`；
2. **删条目**：`ItemDetailViewModel` 软删成功后调 `sensitiveCache.invalidateFor(itemId)`；
3. **登记表齐全**：`SensitiveCache.CACHE_KINDS` 必须含 `KIND_ATTACHMENTS`
   与 `KIND_THUMBS`，否则 `invalidateFor`（按表逐项清）会漏掉附件。
4. **写落点统一**：下载写入必须走 `dirForItem(KIND_ATTACHMENTS, itemId)`，
   保证被上面两条链路覆盖。

判据全部**剥注释**后检查（避免「注释里写了」被当成「代码里做了」的假绿）。
配套 4 组变异自测，每条判据各删一次，都必须红。
"""
import os
import re
import sys

CACHE = "app/src/main/java/io/vaultix/vaultix/util/SensitiveCache.kt"
LOCK = "app/src/main/java/io/vaultix/vaultix/security/VaultLockManagerImpl.kt"
DETAIL = "app/src/main/java/io/vaultix/vaultix/ui/detail/ItemDetailViewModel.kt"


def _read(root, rel):
    path = os.path.join(root, *rel.split("/"))
    if not os.path.isfile(path):
        return ""
    with open(path, encoding="utf-8") as fh:
        return fh.read()


def _strip_comments(code):
    code = re.sub(r"/\*.*?\*/", "", code, flags=re.S)
    return "\n".join(line.split("//")[0] for line in code.splitlines())


def check(root):
    errors = []
    cache = _strip_comments(_read(root, CACHE))
    lock = _strip_comments(_read(root, LOCK))
    detail = _strip_comments(_read(root, DETAIL))

    # ③ 登记表齐全：invalidateFor 按 CACHE_KINDS 逐项清，漏登一种就漏清一种。
    m = re.search(r"CACHE_KINDS\s*=\s*listOf\(([^)]*)\)", cache)
    if not m:
        errors.append("SensitiveCache 找不到 CACHE_KINDS 登记表")
    else:
        kinds = m.group(1)
        if "KIND_ATTACHMENTS" not in kinds:
            errors.append("CACHE_KINDS 缺少 KIND_ATTACHMENTS：删条目时附件明文不会被清")
        if "KIND_THUMBS" not in kinds:
            errors.append("CACHE_KINDS 缺少 KIND_THUMBS：删条目时缩略图不会被清")

    # ③b 两个清理入口本身存在。
    if "fun invalidateFor(" not in cache:
        errors.append("SensitiveCache 缺少 invalidateFor()：删条目无法清该条目缓存")
    if "fun clearAll(" not in cache:
        errors.append("SensitiveCache 缺少 clearAll()：锁库无法清空敏感缓存")

    # ① 锁库链路。
    if not re.search(r"sensitiveCache\s*\.\s*clearAll\s*\(\s*\)", lock):
        errors.append("锁库路径未调用 sensitiveCache.clearAll()：明文附件会在锁库后长期滞留")

    # ② 删条目链路。
    if not re.search(r"sensitiveCache\s*\.\s*invalidateFor\s*\(\s*itemId\s*\)", detail):
        errors.append("删条目路径未调用 sensitiveCache.invalidateFor(itemId)：删条目后明文附件残留")

    # ④ 写落点统一：下载必须写进 KIND_ATTACHMENTS 子目录，才会被上面清掉。
    if not re.search(r"dirForItem\s*\(\s*SensitiveCache\.KIND_ATTACHMENTS\s*,", detail):
        errors.append("下载写入未走 dirForItem(KIND_ATTACHMENTS, itemId)：该缓存不在清理覆盖内")

    return errors


def selftest():
    import tempfile
    base = {
        CACHE: (
            "class SensitiveCache {\n"
            "  fun invalidateFor(itemId: String) {\n"
            "    CACHE_KINDS.forEach { kind -> }\n"
            "  }\n"
            "  fun clearAll() {}\n"
            "  companion object {\n"
            "    const val KIND_ATTACHMENTS = \"attachments\"\n"
            "    const val KIND_THUMBS = \"thumbs\"\n"
            "    val CACHE_KINDS = listOf(KIND_ATTACHMENTS, KIND_THUMBS)\n"
            "  }\n"
            "}\n"
        ),
        LOCK: (
            "class VaultLockManagerImpl {\n"
            "  fun lock() {\n"
            "    runCatching { sensitiveCache.clearAll() }\n"
            "  }\n"
            "}\n"
        ),
        DETAIL: (
            "class ItemDetailViewModel {\n"
            "  fun download() {\n"
            "    val dir = sensitiveCache.dirForItem(SensitiveCache.KIND_ATTACHMENTS, itemId)\n"
            "  }\n"
            "  fun delete() {\n"
            "    runCatching { sensitiveCache.invalidateFor(itemId) }\n"
            "  }\n"
            "}\n"
        ),
    }
    mutations = [
        ("CACHE_KINDS 漏登附件", CACHE,
         "listOf(KIND_ATTACHMENTS, KIND_THUMBS)", "listOf(KIND_THUMBS)"),
        ("锁库去掉 clearAll", LOCK,
         "    runCatching { sensitiveCache.clearAll() }\n", "    // 不再清\n"),
        ("删条目去掉 invalidateFor", DETAIL,
         "    runCatching { sensitiveCache.invalidateFor(itemId) }\n", "    // 不再清\n"),
        ("下载写落点脱离登记表", DETAIL,
         "sensitiveCache.dirForItem(SensitiveCache.KIND_ATTACHMENTS, itemId)",
         "File(cacheDir, \"attachments\")"),
    ]
    passed = 0
    with tempfile.TemporaryDirectory() as td:
        def write_all(files):
            for rel, content in files.items():
                path = os.path.join(td, *rel.split("/"))
                os.makedirs(os.path.dirname(path), exist_ok=True)
                with open(path, "w", encoding="utf-8") as fh:
                    fh.write(content)

        write_all(base)
        errs = check(td)
        if errs:
            print(f"  [假红] 未变异 base 报错：{errs}")
            return 1

        for label, rel, old, new in mutations:
            if old not in base[rel]:
                print(f"  [自测跳过] {label}：锚点不存在")
                return 1
            files = dict(base)
            files[rel] = base[rel].replace(old, new)
            write_all(files)
            errs = check(td)
            if errs:
                passed += 1
            else:
                print(f"  [假绿] 变异未被抓到：{label}")
                return 1
    print(f"  变异自测 {passed}/{len(mutations)} 全部被抓到 ✅")
    return 0


def main():
    args = [a for a in sys.argv[1:] if a != "--selftest"]
    root = args[0] if args else os.getcwd()
    if "--selftest" in sys.argv:
        return selftest()
    errors = check(root)
    if errors:
        print("❌ 敏感缓存清理门禁失败：")
        for e in errors:
            print("  -", e)
        return 1
    print("✅ 敏感明文缓存三链路清理通过（P2）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
