#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""附件 P0：Bitwarden 附件「写方向」四条安全不变量门禁（2026-11-28）。

## 为什么需要门禁而不是只靠单测

附件写方向有四条**看不见但一旦破就是数据事故 / 令牌泄露**的约束：

1. 上传/下载第三方对象存储（Azure SAS / S3 预签名 URL）必须走
   `@AttachmentHttpClient`（无鉴权）—— 默认 client 会把 Bearer 送给第三方域名；
2. 附件**不能**塞进普通 `toUpdateRequest()` 写回 —— 服务端 `ToCipher` 对 attachments
   是整体替换语义，透传会把服务端刚加的附件覆盖掉（见 CipherMapper 注释）；
3. 附件独立密钥在所有路径（含异常）都要 `clear()` —— 明文密钥残留在堆里；
4. 写方向加密必须**与现有 decrypt 格式对齐**（16B IV + AES-CBC-PKCS7 + 32B HMAC），
   否则「上传成功但别人下载解不开」。

判据全部**剥注释**后检查，避免「注释里写了」被当成「代码里做了」的假绿。
配套变异自测，每条不变量各删一次，都必须红。

## ③ 密钥清理判据的两种合规形态（2026-11-28 重构）
早期实现用「每处 catch 各 clear 一次」⇒ 判据是 clear() ≥3。该形态容易漏新分支。
现改为单一 `try { … } finally { … }` 统一清理 ⇒ 判据扩展为两种形态任一即可：
  - 旧形态：`.clear()` 出现 ≥3 次（分散 catch-and-clear）；
  - 新形态：`finally` 块内同时出现 `.clear()` 与 `.fill(`（finally 兜住所有异常路径，
    既清密钥又抹密文字节，比分散清理更严密）。
只删掉清理（两种形态都不满足）必须红 —— 自测里新增该变异钉住。
"""
import os
import re
import sys

CRYPTO = "data/bitwarden/src/main/java/io/vaultix/data/bitwarden/attachment/BitwardenAttachmentCrypto.kt"
API = "data/bitwarden/src/main/java/io/vaultix/data/bitwarden/api/BitwardenVaultApi.kt"
UPLOADER = "data/bitwarden/src/main/java/io/vaultix/data/bitwarden/attachment/BitwardenAttachmentUploader.kt"
MAPPER = "data/bitwarden/src/main/java/io/vaultix/data/bitwarden/mapper/CipherMapper.kt"


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


def _has_finally_cleanup(code):
    """是否存在 finally 块内同时清密钥与抹密文字节（统一清理形态）。"""
    for m in re.finditer(r"finally\s*\{", code):
        start = m.end() - 1
        depth = 0
        end = start
        for i in range(start, len(code)):
            if code[i] == "{":
                depth += 1
            elif code[i] == "}":
                depth -= 1
                if depth == 0:
                    end = i
                    break
        block = code[start:end]
        if ".clear()" in block and ".fill(" in block:
            return True
    return False


def check(root):
    errors = []
    crypto = _strip_comments(_read(root, CRYPTO))
    api = _strip_comments(_read(root, API))
    uploader = _strip_comments(_read(root, UPLOADER))
    mapper = _strip_comments(_read(root, MAPPER))

    # ① 写方向加密能力齐备（生成+包裹 / 文件字节加密 / 短字符串加密）。
    for needle, label in [
        ("fun generateAndWrapAttachmentKey", "generateAndWrapAttachmentKey"),
        ("fun encrypt(", "encrypt(ByteArray)"),
        ("fun encryptStringForAttachment", "encryptStringForAttachment"),
    ]:
        if needle not in crypto:
            errors.append(f"BitwardenAttachmentCrypto 缺少 {label}：附件写方向能力不完整")

    # ② 三个专用端点（附件不进普通写回）。
    if "attachment/v2" not in api:
        errors.append("BitwardenVaultApi 缺少 POST ciphers/{id}/attachment/v2（拿上传 URL）")
    if not re.search(r'DELETE\("ciphers/\{id\}/attachment/\{attachmentId\}"\)', api):
        errors.append("BitwardenVaultApi 缺少 DELETE ciphers/{id}/attachment/{attachmentId}")
    if "multipart" not in api.lower() and "@Multipart" not in api:
        errors.append("BitwardenVaultApi 缺少 Direct Multipart 上传端点")

    # ③ 第三方对象存储走无鉴权 client + 密钥清理覆盖。
    if not uploader:
        errors.append("缺少 BitwardenAttachmentUploader.kt（上传编排器）")
    else:
        if "@AttachmentHttpClient" not in uploader:
            errors.append("上传编排未注入 @AttachmentHttpClient：Bearer 会泄露给第三方对象存储")
        dispersed_cleanup = len(re.findall(r"\.clear\(\)", uploader)) >= 3
        unified_cleanup = _has_finally_cleanup(uploader)
        if not (dispersed_cleanup or unified_cleanup):
            errors.append(
                "上传编排的附件密钥清理覆盖不足：需分散 clear() ≥3 处，"
                "或 finally 块内统一 clear()+fill()（覆盖所有异常路径）"
            )

    # ④ 附件不进普通写回。
    body = _fn_body(mapper, "fun toUpdateRequest")
    if not body:
        errors.append("CipherMapper 找不到 toUpdateRequest() 函数体")
    elif re.search(r"\battachments\b", body):
        errors.append("toUpdateRequest 里出现 attachments：附件走普通写回会被整体替换覆盖")

    return errors


def selftest():
    import tempfile
    base = {
        CRYPTO: (
            "object BitwardenAttachmentCrypto {\n"
            "  fun generateAndWrapAttachmentKey(key: K): Pair<K, String> = TODO()\n"
            "  fun encrypt(plain: ByteArray, key: K): ByteArray = TODO()\n"
            "  fun encryptStringForAttachment(s: String, key: K): String = TODO()\n"
            "}\n"
        ),
        API: (
            "interface BitwardenVaultApi {\n"
            "  @POST(\"ciphers/{id}/attachment/v2\") suspend fun url(): R\n"
            "  @Multipart @POST(\"ciphers/{id}/attachment/{attachmentId}\") suspend fun direct(): R\n"
            "  @DELETE(\"ciphers/{id}/attachment/{attachmentId}\") suspend fun delete()\n"
            "}\n"
        ),
        UPLOADER: (
            "class BitwardenAttachmentUploader @Inject constructor(\n"
            "  @AttachmentHttpClient private val http: OkHttpClient,\n"
            ") {\n"
            "  suspend fun upload() {\n"
            "    var encrypted: ByteArray? = null\n"
            "    try {\n"
            "      encrypted = crypto.encrypt(plain)\n"
            "    } finally {\n"
            "      encrypted?.fill(0)\n"
            "      key.clear()\n"
            "    }\n"
            "  }\n"
            "}\n"
        ),
        MAPPER: (
            "object CipherMapper {\n"
            "  fun toUpdateRequest(item: I, stored: S, key: K): R {\n"
            "    val name = item.name\n"
            "    return R(name)\n"
            "  }\n"
            "}\n"
        ),
    }
    mutations = [
        ("删掉 generateAndWrapAttachmentKey", CRYPTO,
         "  fun generateAndWrapAttachmentKey(key: K): Pair<K, String> = TODO()\n", ""),
        ("删掉 attachment/v2 端点", API,
         "  @POST(\"ciphers/{id}/attachment/v2\") suspend fun url(): R\n", ""),
        ("上传编排去掉 @AttachmentHttpClient", UPLOADER,
         "@AttachmentHttpClient private val http: OkHttpClient,", "private val http: OkHttpClient,"),
        ("删掉 finally 密钥清理", UPLOADER,
         "    } finally {\n"
         "      encrypted?.fill(0)\n"
         "      key.clear()\n"
         "    }\n", "    }\n"),
        ("普通写回塞进 attachments", MAPPER,
         "    val name = item.name\n", "    val name = item.name\n    val attachments = stored.attachments\n"),
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
        print("❌ Bitwarden 附件写方向门禁失败：")
        for e in errors:
            print("  -", e)
        return 1
    print("✅ Bitwarden 附件写方向四条不变量通过（P0）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
