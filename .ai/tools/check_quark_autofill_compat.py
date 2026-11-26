#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""#306：夸克浏览器自动填充兼容名单必须补齐 com.quark.browser。

## 为什么要钉这两处（2026-11-24）

用户实测夸克浏览器（com.quark.browser）不弹自动填充候选。排查结论：不是"内核无解"，
而是 Vaultix 自己的两份名单都漏了夸克：

1. ``app/src/main/res/xml/autofill_service.xml`` 没有夸克的 ``compatibility-package``。
   Chromium 系浏览器只有被声明为兼容填充提供方，系统才会把地址栏 webDomain 上报给
   AutofillService；缺这条 ⇒ webDomain 恒为 null。
2. ``AutofillRequestContextPolicy.KNOWN_BROWSER_PACKAGES`` 没有 ``"com.quark.browser"``。
   ``AutofillCandidateSource.matchLogins()`` 在 webDomain 为空时，只对"已知浏览器"降级
   列全库候选（Bitwarden 的"少给"策略）；夸克不在名单 ⇒ 连降级都被拒 ⇒ 候选为空。

两处任一缺失，用户看到的现象都是"完全不弹"。补齐后，即便夸克定制内核仍不上报域名，
至少能走无域名降级"能弹能选"；精准匹配能否成立取决于夸克是否上报 webDomain，那是
夸克内核的事，Vaultix 不 hook、不逆向。

## 判据为什么剥注释

- XML：注释里出现过 ``com.quark.browser`` 不代表有 compatibility-package 元素；
  先剥 ``<!-- -->`` 再要求完整元素标签。
- Kotlin：注释里出现过包名不代表在 setOf 集合里；先剥 ``//`` 与 ``/* */``，
  再在 KNOWN_BROWSER_PACKAGES 的 setOf 括号体内找精确字符串 ``"com.quark.browser"``。

编译 / detekt / 单测都看不出"名单里少一个包名"，只有源码门禁能钉住。
配套 selftest 四段变异：XML 删元素 / XML 只写注释 / Kotlin 删条目 / Kotlin 只写注释 ——
四者都必须红。
"""
import os
import re
import sys

XML_TARGET = "app/src/main/res/xml/autofill_service.xml"
KT_TARGET = "app/src/main/java/io/vaultix/vaultix/autofill/match/AutofillRequestContextPolicy.kt"
PKG = "com.quark.browser"
KT_ENTRY = '"com.quark.browser"'


def _read(root, rel):
    path = os.path.join(root, *rel.split("/"))
    if not os.path.isfile(path):
        return ""
    with open(path, encoding="utf-8") as fh:
        return fh.read()


def _strip_xml_comments(text):
    return re.sub(r"<!--.*?-->", "", text, flags=re.S)


def _strip_kotlin_comments(code):
    code = re.sub(r"/\*.*?\*/", "", code, flags=re.S)
    return "\n".join(line.split("//")[0] for line in code.splitlines())


def _setof_body(code, marker):
    """提取 `KNOWN_BROWSER_PACKAGES ... setOf( ... )` 的括号体（含括号）。"""
    pos = code.find(marker)
    if pos < 0:
        return ""
    start = code.find("(", pos)
    if start < 0:
        return ""
    depth = 0
    for i in range(start, len(code)):
        if code[i] == "(":
            depth += 1
        elif code[i] == ")":
            depth -= 1
            if depth == 0:
                return code[start:i + 1]
    return code[start:]


def check(root):
    errors = []

    # ① XML 里的 compatibility-package 元素（剥注释后判）
    xml = _strip_xml_comments(_read(root, XML_TARGET))
    if not xml:
        errors.append(f"缺少目标文件 {XML_TARGET}")
    elif not re.search(
        r'<compatibility-package\s+android:name="' + re.escape(PKG) + r'"',
        xml,
    ):
        errors.append(
            "autofill_service.xml 缺少 com.quark.browser 的 compatibility-package："
            "系统不会把 Vaultix 视为兼容夸克的填充提供方，webDomain 不上报"
        )

    # ② Kotlin KNOWN_BROWSER_PACKAGES 集合里的精确条目（剥注释后判）
    kt = _read(root, KT_TARGET)
    if not kt:
        errors.append(f"缺少目标文件 {KT_TARGET}")
    else:
        body = _setof_body(_strip_kotlin_comments(kt), "KNOWN_BROWSER_PACKAGES")
        if not body:
            errors.append("AutofillRequestContextPolicy 找不到 KNOWN_BROWSER_PACKAGES 的 setOf 块")
        elif KT_ENTRY not in body:
            errors.append(
                'KNOWN_BROWSER_PACKAGES 缺少 "com.quark.browser"：'
                "夸克拿不到域名时无法降级列候选，表现为完全不弹"
            )
    return errors


def selftest():
    import tempfile

    xml_base = (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<autofill-service xmlns:android="http://schemas.android.com/apk/res/android">\n'
        '    <!-- 夸克浏览器 -->\n'
        '    <compatibility-package android:name="com.quark.browser" '
        'android:maxLongVersionCode="@string/version_code_max" />\n'
        '</autofill-service>\n'
    )
    kt_base = (
        'internal object AutofillRequestContextPolicy {\n'
        '    // 已知浏览器包名（小写比较）\n'
        '    private val KNOWN_BROWSER_PACKAGES: Set<String> = setOf(\n'
        '        "com.android.chrome",\n'
        '        "com.quark.browser",\n'
        '    )\n'
        '}\n'
    )
    xml_line = (
        '    <compatibility-package android:name="com.quark.browser" '
        'android:maxLongVersionCode="@string/version_code_max" />\n'
    )
    kt_line = '        "com.quark.browser",\n'
    mutations = [
        ("XML 删掉 compatibility-package 元素", XML_TARGET, xml_line, ""),
        ("XML 只把包名写进注释", XML_TARGET, xml_line,
         '    <!-- android:name="com.quark.browser" -->\n'),
        ("Kotlin 删掉 setOf 里的条目", KT_TARGET, kt_line, ""),
        ("Kotlin 只把包名写进注释", KT_TARGET, kt_line,
         '        // "com.quark.browser",\n'),
    ]
    passed = 0
    with tempfile.TemporaryDirectory() as td:
        xml_path = os.path.join(td, *XML_TARGET.split("/"))
        kt_path = os.path.join(td, *KT_TARGET.split("/"))
        os.makedirs(os.path.dirname(xml_path), exist_ok=True)
        os.makedirs(os.path.dirname(kt_path), exist_ok=True)
        with open(xml_path, "w", encoding="utf-8") as fh:
            fh.write(xml_base)
        with open(kt_path, "w", encoding="utf-8") as fh:
            fh.write(kt_base)

        # base 必须绿
        errs = check(td)
        if errs:
            print(f"  [假红] 未变异 base 报错：{errs}")
            return 1

        for label, target, old, new in mutations:
            if old not in (xml_base if target == XML_TARGET else kt_base):
                print(f"  [自测跳过] {label}：锚点不存在")
                return 1
            path = xml_path if target == XML_TARGET else kt_path
            base = xml_base if target == XML_TARGET else kt_base
            with open(path, "w", encoding="utf-8") as fh:
                fh.write(base.replace(old, new))
            errs = check(td)
            if errs:
                passed += 1
            else:
                print(f"  [假绿] 变异未被抓到：{label}")
                return 1
            # 还原本文件，避免影响后续变异
            with open(path, "w", encoding="utf-8") as fh:
                fh.write(base)
    print(f"  变异自测 {passed}/{len(mutations)} 全部被抓到 ✅")
    return 0


def main():
    args = [a for a in sys.argv[1:] if a != "--selftest"]
    root = args[0] if args else os.getcwd()
    if "--selftest" in sys.argv:
        return selftest()
    errors = check(root)
    if errors:
        print("❌ 夸克自动填充兼容门禁失败：")
        for e in errors:
            print("  -", e)
        return 1
    print("✅ 夸克浏览器自动填充兼容名单已补齐（#306）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
