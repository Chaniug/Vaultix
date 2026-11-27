#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""P1：KDBX 附件导出必须走 kotpass 0.13.0 的公开 binary API，禁止反射（2026-11-28）。

## 背景

旧注释称 `Meta.binaries` 是 `@PublishedApi internal`、「只能反射、密码库不可接受」。
实测 0.13.0 tag 已有公开 `KeePassDatabase.binaries` / `BinaryData.getContent()`，
所以 KDBX 附件导出无需升版、无需反射。本门禁钉住三点：

1. `Kdbx.attachmentBytes()` 存在且用 `db.binaries[hash]?.getContent()`；
2. KDBX 模块不出现 `getDeclaredField` / `java.lang.reflect` / `isAccessible`；
3. 过时结论已从注释里删掉（否则下一位接手者又被误导去走反射）。

配套 3 组变异自测，任一被破坏都必须红。
"""
import os
import re
import sys

KDBX = "data/kdbx/src/main/java/io/vaultix/data/kdbx/Kdbx.kt"
MAPPER = "data/kdbx/src/main/java/io/vaultix/data/kdbx/KdbxItemMapper.kt"
MAPPED = "data/kdbx/src/main/java/io/vaultix/data/kdbx/KdbxMappedContent.kt"
REPO = "data/repository/src/main/java/io/vaultix/data/repository/ItemRepositoryImpl.kt"


def _read(root, rel):
    path = os.path.join(root, *rel.split("/"))
    if not os.path.isfile(path):
        return ""
    with open(path, encoding="utf-8") as fh:
        return fh.read()


def _fn_body(text, marker):
    pos = text.find(marker)
    if pos < 0:
        return ""
    start = text.find("{", pos)
    if start < 0:
        return ""
    depth = 0
    for i in range(start, len(text)):
        if text[i] == "{":
            depth += 1
        elif text[i] == "}":
            depth -= 1
            if depth == 0:
                return text[start:i + 1]
    return text[start:]


def check(root):
    errors = []
    kdbx = _read(root, KDBX)
    mapper = _read(root, MAPPER)
    mapped = _read(root, MAPPED)
    repo = _read(root, REPO)

    body = _fn_body(kdbx, "fun attachmentBytes")
    if not body:
        errors.append("Kdbx 缺少 attachmentBytes()：KDBX 附件导出未接通")
    else:
        if not re.search(r"\.binaries\s*\[", body):
            errors.append("attachmentBytes 未用公开的 db.binaries[hash] 取二进制")
        if "getContent()" not in body:
            errors.append("attachmentBytes 未用 BinaryData.getContent()（压缩态会读成 gzip 原文）")

    for rel, text in [(KDBX, kdbx), (MAPPER, mapper), (MAPPED, mapped)]:
        if re.search(r"getDeclaredField|java\.lang\.reflect|isAccessible", text):
            errors.append(f"{rel} 出现反射访问 kotpass internal：密码库纪律红线")

    if "只能反射" in mapper or "只能反射" in mapped:
        errors.append("KDBX 附件「只能反射」的过时结论仍在注释里，会误导后续接手者")

    if "KDBX 附件暂不支持导出" in repo:
        errors.append("ItemRepositoryImpl 仍拒绝 KDBX 附件下载（P1 接通后必须删掉）")
    return errors


def selftest():
    import tempfile
    base = {
        KDBX: (
            "object Kdbx {\n"
            "  fun attachmentBytes(vaultId: String, hash: String): ByteArray? {\n"
            "    val hashKey = ByteString.fromHex(hash)\n"
            "    return db.binaries[hashKey]?.getContent()\n"
            "  }\n"
            "}\n"
        ),
        MAPPER: "// 附件字节导出见 Kdbx.attachmentBytes\nobject KdbxItemMapper\n",
        MAPPED: "class KdbxMappedContent\n",
        REPO: "class ItemRepositoryImpl {\n  fun downloadAttachment() { sink.write(bytes) }\n}\n",
    }
    mutations = [
        ("删掉 attachmentBytes", KDBX,
         "  fun attachmentBytes(vaultId: String, hash: String): ByteArray? {\n    val hashKey = ByteString.fromHex(hash)\n    return db.binaries[hashKey]?.getContent()\n  }\n", ""),
        ("改用 getDeclaredField 反射", KDBX,
         "return db.binaries[hashKey]?.getContent()", "return getDeclaredField(\"binaries\") as ByteArray"),
        ("过时结论回到注释", MAPPER,
         "// 附件字节导出见 Kdbx.attachmentBytes", "// 想导出只能反射，密码库不可接受"),
        ("仓储继续拒绝 KDBX", REPO,
         "  fun downloadAttachment() { sink.write(bytes) }\n",
         "  fun downloadAttachment() { require(!isKdbx(vaultId)) { \"KDBX 附件暂不支持导出\" } }\n"),
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
        print("❌ KDBX 附件公开 API 门禁失败：")
        for e in errors:
            print("  -", e)
        return 1
    print("✅ KDBX 附件走公开 binary API、无反射、无过时结论（P1）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
