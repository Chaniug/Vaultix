#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""自检 `check_kdbx_passkey_id.py`：拿**真实源码**做变异，断言门禁分别判红/判绿。

## 为什么必须自检

真仓库改完只会得到一条绿，那条绿无法区分「契约确实成立」和「门禁根本没在检查」。
本自测把 4 个真实文件复制到临时 root，逐个改坏一个点，要求门禁红 ——
哪天有人「简化」检测逻辑、或把读侧转换悄悄加回来，这里先红。

尤其两条容易翻车的：
- **注释不误报**：KDoc 里必须能自由写 `normalizeCredentialId` / 标准 base64 编码器的
  历史教训，否则门禁会变成「不许写教训」（#291 同源的假红教训）；
- **错误修法要挡住**：为「修」KDBX 而反向改 `WebAuthn.rawIdFromStored` 成
  decode→re-encode，是 2026-09-12 真机证伪过的路，必须判红。

⚠️ 每个变异都断言「原文锚点存在」；替换不生效 ⇒ 自检直接失败，
   不给「看起来全绿」的机会。
"""
import os
import shutil
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
TOOLS = os.path.dirname(HERE)
CHECKER = os.path.join(TOOLS, "check_kdbx_passkey_id.py")
ROOT = os.path.abspath(os.path.join(HERE, "..", "..", ".."))

FILES = {
    "codec": "data/kdbx/src/main/java/io/vaultix/data/kdbx/KdbxPasskeyCodec.kt",
    "webauthn": "core/common/src/main/java/io/vaultix/common/WebAuthn.kt",
    "contract": "data/kdbx/src/test/java/io/vaultix/data/kdbx/KdbxPasskeyCodecTest.kt",
    "conversion": "data/kdbx/src/test/java/io/vaultix/data/kdbx/KdbxConversionTest.kt",
}

# 读侧那行赋值：契约的核心锚点。
PLAIN_ASSIGN = "            credentialId = credentialId,"
CONTRACT_CASE = "KDBX credential id survives into assertion rawId byte for byte"


def _seed(tmp: str):
    """把真实文件按原相对路径铺进临时 root。"""
    for rel in FILES.values():
        dst = os.path.join(tmp, *rel.split("/"))
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        shutil.copyfile(os.path.join(ROOT, rel), dst)


def _write(tmp: str, key: str, text: str):
    with open(os.path.join(tmp, *FILES[key].split("/")), "w", encoding="utf-8") as fh:
        fh.write(text)


def _read(tmp: str, key: str) -> str:
    with open(os.path.join(tmp, *FILES[key].split("/")), encoding="utf-8") as fh:
        return fh.read()


def _mutate(tmp: str, key: str, old: str, new: str, label: str):
    text = _read(tmp, key)
    assert old in text, f"[{label}] 变异锚点不存在，自检失效：{old!r}"
    _write(tmp, key, text.replace(old, new, 1))


def _run(tmp: str) -> int:
    return subprocess.run(
        [sys.executable, CHECKER, tmp],
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
    ).returncode


def _case(label: str, mutate, expect_rc: int, results: list):
    with tempfile.TemporaryDirectory(prefix="selftest-kdbx-id-") as tmp:
        _seed(tmp)
        mutate(tmp)
        rc = _run(tmp)
    ok = rc == expect_rc
    results.append((label, expect_rc, rc, ok))
    print(("  ✅ " if ok else "  ✗ ") + f"{label}: 期望 rc={expect_rc}，实得 rc={rc}")
    return ok


def main() -> int:
    results = []

    # ① 真实源码原样 → 绿
    _case("baseline 真实源码", lambda t: None, 0, results)

    # ② 读侧把裸变量改回旧转换 → 红
    _case(
        "读侧复活 normalizeCredentialId 调用",
        lambda t: _mutate(
            t, "codec", PLAIN_ASSIGN,
            "            credentialId = normalizeCredentialId(credentialId),",
            "normalize 调用",
        ),
        1, results,
    )

    # ③ 读侧改成「善意」的 base64url 归一 → 也要红（任何改写都不许）
    _case(
        "读侧改成 toBase64Url 归一",
        lambda t: _mutate(
            t, "codec", PLAIN_ASSIGN,
            "            credentialId = toBase64Url(credentialId),",
            "toBase64Url 归一",
        ),
        1, results,
    )

    # ④ 旧函数整段复活（哪怕没被调用）→ 红
    _case(
        "codec 里复活 normalizeCredentialId 定义",
        lambda t: _mutate(
            t, "codec", "    fun toCredential(",
            "    private fun normalizeCredentialId(raw: String): String = raw\n\n"
            "    fun toCredential(",
            "旧函数定义",
        ),
        1, results,
    )

    # ⑤ 标准 base64 编码器出现在 codec 里 → 红
    _case(
        "codec 出现 Base64.getEncoder()",
        lambda t: _mutate(
            t, "codec", "    fun toBase64Url(raw: String): String {",
            "    fun toBase64Url(raw: String): String {\n"
            '        Base64.getEncoder().encodeToString("x".toByteArray())',
            "标准编码器",
        ),
        1, results,
    )

    # ⑥ 为修 KDBX 反向改 rawIdFromStored → 红（守住真机决策）
    _case(
        "WebAuthn 原样返回分支被删",
        lambda t: _mutate(
            t, "webauthn", "trimmed.takeIf", "trimmed.let", "原样分支",
        ),
        1, results,
    )

    # ⑦ 契约测试被删 → 红
    _case(
        "跨模块契约用例被改名",
        lambda t: _mutate(
            t, "contract", CONTRACT_CASE, "some renamed test", "契约用例",
        ),
        1, results,
    )

    # ⑧ 契约测试不再引用 rawIdFromStored（形同虚设）→ 红
    _case(
        "契约测试去掉 rawIdFromStored 调用",
        lambda t: _mutate(
            t, "contract", "WebAuthn.rawIdFromStored(credential.credentialId)",
            "credential.credentialId", "契约引用",
        ),
        1, results,
    )

    # ⑨ 往返测试把旧缺陷当规格换回来 → 红
    _case(
        "往返期望值改回标准 base64 \\\"////\\\"",
        lambda t: _mutate(
            t, "conversion", 'isEqualTo("AQIDBA")', 'isEqualTo("////")', "往返期望",
        ),
        1, results,
    )

    # ⑩ 关键假红防护：KDoc 里写历史教训（含旧函数名 + 标准编码器）→ 仍然绿
    _case(
        "KDoc 里写旧函数名与编码器不误报",
        lambda t: _mutate(
            t, "codec", "     * 映射为领域凭证；关键字段缺失返回 null",
            "     * 映射为领域凭证；关键字段缺失返回 null。旧实现 normalizeCredentialId 曾调\n"
            "     * Base64.getEncoder().encodeToString(decoded) 把 ID 转成标准 base64（#293 已删）。\n"
            "     * 映射为领域凭证；关键字段缺失返回 null",
            "KDoc 注释干扰",
        ),
        0, results,
    )

    # ⑪ 关键文件被移走 → 红（不许静默通过）
    def _remove_codec(t):
        os.remove(os.path.join(t, *FILES["codec"].split("/")))

    _case("codec 文件缺失", _remove_codec, 1, results)

    passed = sum(1 for *_, ok in results if ok)
    total = len(results)
    print(f"\n自检结果：{passed}/{total} 通过")
    if passed != total:
        print("FAIL check_kdbx_passkey_id 自检")
        return 1
    print("PASS check_kdbx_passkey_id 自检")
    return 0


if __name__ == "__main__":
    sys.exit(main())
