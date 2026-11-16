#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""#298：CREATE / UPDATE 请求体必须在发送前带 `encryptedFor`（= user id）。

## 为什么单列一关（2026-11-16，真机 422）

真机改网址同步，日志 `flushPending op=UPDATE code=422`，从 pending_ops.payload
直读的请求体确无 `encryptedFor`。根因：Vaultwarden 1.37+（web-v2025.6.0）在
Json<CipherData> 守卫里要求该字段（当时非 Option），缺即在 handler 前 422。
官方 bitwarden-android / keyguard / Monica 三客户端都发，值 = JWT 的 `sub`。

这类缺陷"删掉一行"不触发编译错误、单测若不覆盖 body 也不会红、detekt 更抓不到
——需要源码门禁钉死，防止以后重构又把注入点删掉。

## 本关钉住

1. `CipherRequest` 声明了 `@SerialName("encryptedFor")` 字段，默认 null（存量兼容）；
2. `executeOperation` 在 CREATE/UPDATE 分支用的是**注入后的 body**（`ready`），
   且经过 `withEncryptedFor`；
3. `withEncryptedFor` 调用的取值函数读 JWT 的 `sub`，并有 UUID 校验；
4. 取不到 user id 时**降级跳过**（不阻断同步）——`withEncryptedFor` 含 null 分支。
"""
import os
import re
import sys

DTO = "data/bitwarden/src/main/java/io/vaultix/data/bitwarden/model/BitwardenDto.kt"
SYNC = "data/bitwarden/src/main/java/io/vaultix/data/bitwarden/sync/BitwardenSyncService.kt"
TEST = "data/bitwarden/src/test/java/io/vaultix/data/bitwarden/sync/BitwardenSyncEncryptedForTest.kt"


def _read(root, rel):
    path = os.path.join(root, rel)
    if not os.path.isfile(path):
        return ""
    with open(path, encoding="utf-8") as fh:
        return fh.read()


def _brace_body(text, marker):
    """取 marker 所在函数从第一个 `{` 起的括号配平函数体（避免注释假绿）。"""
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
    dto = _read(root, DTO)
    sync = _read(root, SYNC)
    test = _read(root, TEST)

    # 1. DTO 字段（用 sanitize 无关，直接找串行特征）
    if not re.search(r'@SerialName\("encryptedFor"\)\s*val\s+encryptedFor\s*:\s*String\?\s*=\s*null', dto):
        errors.append("CipherRequest 缺少 encryptedFor 字段（或默认值不为 null）")

    # 2. executeOperation 用注入后的 body 发送 CREATE/UPDATE
    exec_body = _brace_body(sync, "private suspend fun executeOperation")
    if "withEncryptedFor" not in exec_body:
        errors.append("executeOperation 未调用 withEncryptedFor 注入")
    if not re.search(r'OP_CREATE\s*->\s*if\s*\(ready\s*!=\s*null\)\s*api\.createCipher\(ready\)', exec_body):
        errors.append("CREATE 分支未用注入后的 ready 发送")
    if not re.search(r'OP_UPDATE\s*->\s*if\s*\(ready\s*!=\s*null\)\s*api\.updateCipher\([^,]+,\s*ready\)', exec_body):
        errors.append("UPDATE 分支未用注入后的 ready 发送")

    # 3. 取值读 sub + UUID 校验
    uid_body = _brace_body(sync, "private fun userIdFromToken")
    if '"sub"' not in uid_body and "'sub'" not in uid_body:
        errors.append("userIdFromToken 未读 JWT 的 sub claim")
    if "USER_ID_PATTERN" not in sync:
        errors.append("缺少 user id 的 UUID 格式校验 USER_ID_PATTERN")

    # 4. 降级分支（withEncryptedFor 里必须有 null 处理）
    wef = _brace_body(sync, "private fun withEncryptedFor")
    if "userId == null" not in wef and "userId===null" not in wef.replace(" ", ""):
        errors.append("withEncryptedFor 缺少取不到 user id 时的降级分支")

    # 5. 测试覆盖存在
    if not test:
        errors.append("缺少 BitwardenSyncEncryptedForTest 行为测试")
    else:
        for required in ("encryptedFor 等于 user id", "CREATE", "token 缺失", "非 UUID"):
            if required not in test:
                errors.append(f"行为测试缺少覆盖：{required}")

    return errors


def selftest():
    """变异自测：把关键契约逐个改坏，check 必须报红；能蒙混过关说明门禁假绿。"""
    import tempfile
    base = {
        DTO: 'data class CipherRequest(\n @SerialName("encryptedFor") val encryptedFor: String? = null,\n)',
        SYNC: (
            'private suspend fun executeOperation(...) {\n'
            ' val ready = body?.let { withEncryptedFor(it, server) }\n'
            ' when (op.op) {\n'
            '  OP_CREATE -> if (ready != null) api.createCipher(ready) else null\n'
            '  OP_UPDATE -> if (ready != null) api.updateCipher(op.cipherId, ready) else null\n'
            ' }\n'
            '}\n'
            'private fun withEncryptedFor(...) {\n'
            ' val userId = userIdFromToken(server)\n'
            ' if (userId == null) return body\n'
            ' return body.copy(encryptedFor = userId)\n'
            '}\n'
            'private fun userIdFromToken(...) { obj["sub"]; USER_ID_PATTERN.matches(it) }\n'
        ),
        TEST: 'UPDATE 请求体必须带 encryptedFor 等于 user id / CREATE / token 缺失 / 非 UUID',
    }
    mutations = [
        (DTO, 'val encryptedFor: String? = null', 'val encryptedFor: String? = ""', "DTO 默认值被改"),
        (SYNC, 'withEncryptedFor(it, server)', 'it', "去掉注入调用"),
        (SYNC, 'api.createCipher(ready)', 'api.createCipher(body)', "CREATE 用回原 body"),
        (SYNC, 'api.updateCipher(op.cipherId, ready)', 'api.updateCipher(op.cipherId, body)', "UPDATE 用回原 body"),
        (SYNC, 'obj["sub"]', 'obj["user"]', "取值不读 sub"),
        (SYNC, 'if (userId == null) return body', '', "去掉降级分支"),
        (TEST, 'token 缺失', '', "删测试覆盖"),
    ]
    passed = 0
    with tempfile.TemporaryDirectory() as td:
        for rel, old, new, label in mutations:
            files = dict(base)
            if old not in files[rel]:
                print(f"  [自测跳过] {label}：锚点不存在（脚本需更新）")
                continue
            files[rel] = files[rel].replace(old, new)
            for r, content in files.items():
                p = os.path.join(td, r)
                os.makedirs(os.path.dirname(p), exist_ok=True)
                with open(p, "w", encoding="utf-8") as fh:
                    fh.write(content)
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
        print("❌ encryptedFor 契约检查失败：")
        for e in errors:
            print("  -", e)
        return 1
    print("✅ encryptedFor 契约检查通过（#298）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
