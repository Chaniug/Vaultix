#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""#305：指纹门锁 KEK 必须显式设置 setInvalidatedByBiometricEnrollment(true)。

## 为什么要显式（KeePassDX #2805 借鉴，2026-11-24）

Android 文档说「生物识别认证密钥默认 invalidatedByBiometricEnrollment=true」，
但 KeePassDX 4.5.9 修的真实案例（Samsung S22 / Android 8.0、厂商 overlay 未照默认）
表明：新增家人指纹后，旧 KEK 仍能被新指纹解开 —— 依赖默认值在部分 OEM 上不成立，
于是「录入新指纹 ⇒ 生物门禁失效 ⇒ 回主密码」的安全保证被静默击穿。
KeePassDX 的修复（提交 a52970d）就是**显式**调用 setInvalidatedByBiometricEnrollment(true)。

Vaultix 的 LocalUnlockKeyStore 指纹 KEK 正是 auth-per-use + minSdk 26 的同类密钥，
却只在注释里写「默认 true」而没有显式调用。显式声明不改变主流机行为，却能在 OEM
不守默认时兜住这条安全边界。

⚠️ 判据必须剥注释再查：当前源码第 219 行注释就写着「默认 invalidatedByBiometricEnrollment
=true」，朴素 `in` 会把「注释里提到」当成「代码里设置」而假绿。故先去 `//` 行注释与
`/* */` 块注释，只看真实代码；同时钉住不得出现 setInvalidatedByBiometricEnrollment(false)
（AutoUnlockKeyStore / KdbxDerivedKeyDiskCache 那两把**刻意 false** 的钥匙在别的文件，
本关只管指纹门锁这一把，不误伤）。

配套 selftest 三段变异：删调用 / 改 false / 只写进注释 —— 三者都必须红。
"""
import os
import re
import sys

TARGET = "core/datastore/src/main/java/io/vaultix/datastore/LocalUnlockKeyStore.kt"


def _read(root, rel):
    path = os.path.join(root, *rel.split("/"))
    if not os.path.isfile(path):
        return ""
    with open(path, encoding="utf-8") as fh:
        return fh.read()


def _strip_comments(code):
    code = re.sub(r"/\*.*?\*/", "", code, flags=re.S)
    return "\n".join(line.split("//")[0] for line in code.splitlines())


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
    src = _read(root, TARGET)
    if not src:
        return [f"缺少目标文件 {TARGET}"]

    body = _fn_body(src, "fun obtainOrCreateKey")
    if not body:
        return ["LocalUnlockKeyStore 找不到 obtainOrCreateKey() 函数体"]

    code = _strip_comments(body)
    if not re.search(r"\.setInvalidatedByBiometricEnrollment\(\s*true\s*\)", code):
        errors.append(
            "指纹门锁 KEK 未显式 .setInvalidatedByBiometricEnrollment(true)："
            "依赖默认值在部分 OEM overlay 上不成立（KeePassDX #2805）"
        )
    if re.search(r"\.setInvalidatedByBiometricEnrollment\(\s*false\s*\)", code):
        errors.append("指纹门锁 KEK 被设成 setInvalidatedByBiometricEnrollment(false)：生物变更不再失效")
    return errors


def selftest():
    import tempfile
    base = (
        "class LocalUnlockKeyStore {\n"
        "  @Suppress(\"DEPRECATION\")\n"
        "  private fun obtainOrCreateKey(): SecretKey {\n"
        "    loadKey()?.let { return it }\n"
        "    deleteKey()\n"
        "    val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)\n"
        "    val spec = KeyGenParameterSpec.Builder(KEY_ALIAS, PURPOSE_ENCRYPT or PURPOSE_DECRYPT)\n"
        "      .setBlockModes(KeyProperties.BLOCK_MODE_GCM)\n"
        "      // 默认 invalidatedByBiometricEnrollment=true：新增/删除指纹使人脸失效\n"
        "      .setInvalidatedByBiometricEnrollment(true)\n"
        "      .build()\n"
        "    generator.init(spec)\n"
        "    return generator.generateKey()\n"
        "  }\n"
        "}\n"
    )
    # base 必须绿：注释里那句「默认 true」不能造成假绿，也不能误伤。
    mutations = [
        (
            "删掉显式调用（只剩注释提到默认 true）",
            "      .setInvalidatedByBiometricEnrollment(true)\n",
            "",
        ),
        (
            "显式设置成 false",
            ".setInvalidatedByBiometricEnrollment(true)",
            ".setInvalidatedByBiometricEnrollment(false)",
        ),
        (
            "显式调用只写进注释",
            "      .setInvalidatedByBiometricEnrollment(true)\n",
            "      // .setInvalidatedByBiometricEnrollment(true)\n",
        ),
    ]
    passed = 0
    with tempfile.TemporaryDirectory() as td:
        path = os.path.join(td, *TARGET.split("/"))
        os.makedirs(os.path.dirname(path), exist_ok=True)

        with open(path, "w", encoding="utf-8") as fh:
            fh.write(base)
        errs = check(td)
        if errs:
            print(f"  [假红] 未变异 base 报错：{errs}")
            return 1

        for label, old, new in mutations:
            if old not in base:
                print(f"  [自测跳过] {label}：锚点不存在")
                return 1
            mutated = base.replace(old, new)
            with open(path, "w", encoding="utf-8") as fh:
                fh.write(mutated)
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
        print("❌ 指纹门锁生物失效门禁失败：")
        for e in errors:
            print("  -", e)
        return 1
    print("✅ 指纹门锁 KEK 显式生物失效设置检查通过（#305）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
