#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""#293：KDBX passkey 的 `credentialId` 必须**逐字符保真**，不得转标准 base64。

## 为什么单列一关（2026-11-15，#293）

真机症状：v0.13.2 正式包用 **KDBX 库里保存的通行密钥**登录 GitHub / x.com
报「通行密钥有问题」；同一个库在电脑 KeePassXC 上正常，Vaultix 里的
**Bitwarden 库**通行密钥也正常。三条路径唯一差别在 KDBX 读侧：

```
KDBX 字段 KPEX_PASSKEY_CREDENTIAL_ID   = base64url 无填充（KeePass 生态约定）
   ↓ KdbxPasskeyCodec.toCredential()  ← 旧实现在这里调 normalizeCredentialId()
领域模型 VaultFido2Credential.credentialId = 标准 base64（补 `=`、`-_`→`+/`）
   ↓ WebAuthn.rawIdFromStored()       ← 决策是「原样回传存储文本」
断言响应 id / rawId                   = 带 `=`（约 3/4 概率含 `+` `/`）的文本
   ↓ RP / CredMan 按 base64url 解码失败 ⇒ 「通行密钥有问题」
```

`WebAuthn.rawIdFromStored` 的「原样回传」是 2026-09-12 真机证伪
`base64Url(decode(stored))` 之后定下的决策（`WebAuthnTest` 明确钉死：
标准 / URL 两种合法形态都**不得改写**）。它成立的前提是
「领域模型里的文本 == 该凭证注册时 RP 拿到的文本」。
Bitwarden 导入（`BitwardenImportMapper`）与本应用注册（`PasskeyCreateActivity`
用 `WebAuthn.base64Url`）都满足，唯独 KDBX 读侧把文本改坏了。

编译 / 单测 / detekt 全都查不出这件事：它只是「字符串形态」被改，
类型、字节数、签名都合法，只有真机对上 RP 才暴露。
⇒ 用源码文本把这条**跨模块隐式契约**钉死，并配自测防假红。

## 检查项

1. `KdbxPasskeyCodec.toCredential()` 里 `credentialId =` 的右值必须是**裸变量**
   `credentialId`，且函数体内不得出现任何 base64 编码 / 替换形状；
2. 全文件不得复活 `normalizeCredentialId`（或任何标准 base64 编码器）；
3. `WebAuthn.rawIdFromStored()` 的「原样返回合法 base64 文本」分支仍在
   —— 防止有人为「修」本问题反向把它改成 decode→re-encode，那会推翻
   2026-09-12 的真机决策（这是守住契约**另一侧**，不是重复判据：
   `WebAuthnTest` 钉运行期行为，本关钉源码形状）；
4. 跨模块契约测试 `KdbxPasskeyCodecTest` 仍在，且引用 `WebAuthn.rawIdFromStored`；
5. `KdbxConversionTest` 不得把 `"////"` 重新写成往返期望值
   —— 那等于把「读侧转回标准 base64」这个缺陷当规格。

⚠️ 注释与字符串字面量里的旧函数名不算违规：KDoc 正在解释「以前为什么错」，
   算成违规会让门禁变成不许写教训。判据直接复用
   [check_production_safety.sanitize]（#291 的教训：同一判据别抄第二份）。
"""
import os
import re
import sys

from check_production_safety import sanitize

CODEC = "data/kdbx/src/main/java/io/vaultix/data/kdbx/KdbxPasskeyCodec.kt"
WEBAUTHN = "core/common/src/main/java/io/vaultix/common/WebAuthn.kt"
CONTRACT_TEST = "data/kdbx/src/test/java/io/vaultix/data/kdbx/KdbxPasskeyCodecTest.kt"
CONVERSION_TEST = "data/kdbx/src/test/java/io/vaultix/data/kdbx/KdbxConversionTest.kt"

CONTRACT_CASE = "KDBX credential id survives into assertion rawId byte for byte"

# 读侧一旦出现这些形状，就是在改写 credentialId 文本（写侧 toBase64Url 不算，
# 因为检查范围严格限定在 toCredential 函数体内）。
FORBIDDEN_IN_READ = (
    ("normalizeCredentialId", "调用了旧的标准 base64 转换"),
    (r"Base64\s*\.\s*getEncoder\s*\(", "标准 base64 编码器"),
    (r"getUrlEncoder\s*\(", "base64url 编码器（读侧连归一也不许做）"),
    (r"encodeToString\s*\(", "base64 编码输出"),
    (r"replace\s*\(\s*'-'\s*,\s*'\+'\s*\)", "base64url → 标准 base64 字符替换"),
)


def _function_body(code: str, name: str) -> str:
    """截出 object 内一个顶层方法的函数体（到下一个同级 fun 为止）。

    `sanitize` 后字符串已抹空，大括号/字符串里的干扰不影响本切分。
    找不到返回空串，由调用方报「函数不见了」而不是静默放行。
    """
    head = re.search(r"\n    fun " + re.escape(name) + r"\(", code)
    if not head:
        return ""
    nxt = re.search(r"\n    (?:private |internal |public )?fun ", code[head.end():])
    end = head.end() + nxt.start() if nxt else len(code)
    return code[head.start():end]


def _read(root: str, rel: str) -> str:
    path = os.path.join(root, *rel.split("/"))
    if not os.path.isfile(path):
        return ""
    return open(path, encoding="utf-8").read()


def scan(root: str):
    """返回可直接打印的问题列表；空列表 = 通过。"""
    problems = []

    raw_codec = _read(root, CODEC)
    raw_webauthn = _read(root, WEBAUTHN)
    if not raw_codec:
        return [f"找不到 {CODEC} —— 文件被移动/改名时本关必须红，不得静默通过"]
    if not raw_webauthn:
        return [f"找不到 {WEBAUTHN} —— 契约另一侧缺失"]

    code = sanitize(raw_codec)
    body = _function_body(code, "toCredential")
    if not body:
        problems.append(f"{CODEC}: 找不到 `toCredential` 函数体（改名/重构后本关失效）")
    else:
        # ⚠️ 逐行区分「局部变量声明」与「VaultFido2Credential 构造参数」：
        #   toCredential 里有 `val credentialId = fields.credentialId.trim()`，
        #   直接 global search 会先撞上它 ⇒ 假红（自测 ①baseline 会抓到）。
        #   只认构造参数那种不带 val/var 前缀、以逗号收尾的赋值行。
        assigns = []
        for line in body.splitlines():
            if re.search(r"\b(?:val|var)\s+credentialId\s*=", line):
                continue
            m = re.match(r"\s*credentialId\s*=\s*([^,\n]+),?\s*$", line)
            if m:
                assigns.append(m.group(1).strip())
        if not assigns:
            problems.append(f"{CODEC}: toCredential 里找不到构造参数 `credentialId =`")
        elif assigns != ["credentialId"]:
            problems.append(
                f"{CODEC}: toCredential 把 credentialId 赋成了 {assigns} —— "
                "必须是裸变量 `credentialId` 原样保留（#293）"
            )
        for pattern, label in FORBIDDEN_IN_READ:
            if re.search(pattern, body):
                problems.append(
                    f"{CODEC}: toCredential 函数体内出现{label} `{pattern}` —— "
                    "读侧不得对 credentialId 做任何形态转换（#293）"
                )

    # 旧转换函数 / 标准 base64 编码器不得在整个 codec 里复活。
    if re.search(r"fun\s+normalizeCredentialId", code):
        problems.append(f"{CODEC}: 旧 `normalizeCredentialId` 复活了（#293 已删除）")
    if re.search(r"Base64\s*\.\s*getEncoder\s*\(", code):
        problems.append(
            f"{CODEC}: 出现标准 base64 编码器 —— 写侧只允许 `getUrlEncoder`，"
            "读侧只允许原样保留（#293）"
        )

    # 契约另一侧：rawIdFromStored 的「原样返回」分支必须还在。
    webauthn_code = sanitize(raw_webauthn)
    if "trimmed.takeIf" not in webauthn_code:
        problems.append(
            f"{WEBAUTHN}: `rawIdFromStored` 的「原样返回合法 base64 文本」分支"
            "（`trimmed.takeIf`）不见了 —— 不要为修 KDBX 反向改成 decode→re-encode，"
            "那是 2026-09-12 真机证伪过的路"
        )

    # 跨模块契约测试不得被删。
    contract = _read(root, CONTRACT_TEST)
    if CONTRACT_CASE not in contract or "WebAuthn.rawIdFromStored" not in contract:
        problems.append(
            f"{CONTRACT_TEST}: 跨模块契约用例 `{CONTRACT_CASE}` 缺失或未引用 "
            "`WebAuthn.rawIdFromStored` —— 这条契约以前没人钉住才漂移了三个月"
        )

    # 往返测试不得把旧缺陷当规格换回来。
    conversion = _read(root, CONVERSION_TEST)
    if 'isEqualTo("////")' in conversion:
        problems.append(
            f'{CONVERSION_TEST}: 又把 `isEqualTo("////")` 当成往返期望值 —— '
            '那要求读侧把 base64url 转回标准 base64，正是 #293 根因'
        )

    return problems


def main(argv):
    root = argv[1] if len(argv) > 1 else os.getcwd()
    problems = scan(root)
    if problems:
        print("::error title=kdbx passkey credentialId::KDBX 通行密钥 ID 保真契约检查未通过")
        for p in problems:
            print("  ✗", p)
        return 1
    print(
        "✅ KDBX passkey credentialId 保真契约通过"
        "（读侧原样 · 无标准 base64 转换 · rawId 原样分支在位 · 契约测试在位）"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
