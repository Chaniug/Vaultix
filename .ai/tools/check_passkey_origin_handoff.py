#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""#299：浏览器流程下 CP 层读到的真实 origin 必须透传给 PasskeyCreateActivity。

## 为什么单列一关（2026-11-16 真机）

Via 浏览器（mark.via，WebView）上注册通行密钥，弹窗点保存即报
"Origin does not match relying party"。日志：

```
CP CREATE begin caller=mark.via origin=https://pwd.vv1234.cn/ rpId=pwd.vv1234.cn  ← CP 读到了
PKC origin caller=- request=- rpId=pwd.vv1234.cn verdict=Rejected                 ← Activity 没读到
PKC rejected: origin 无法解析出主机（origin=）
```

根因：CP 层用真实 callingAppInfo 读到 origin，但 createIntent() 没透传；
Activity 用 retrieveProviderCreateCredentialRequest() 取回的 callingAppInfo
在浏览器流程下丢了 origin（自证名单读不出），两条来源都空 ⇒ 校验拒绝。

修法是三段交接，缺一不可 —— 删任何一段都会让浏览器通行密钥再次失效，
而这类"少传一个参数"编译过、detekt 过、单测若不覆盖也过，只有门禁能钉住：

1. PasskeyProviderIntents 定义 EXTRA_ORIGIN 且 createIntent 写入；
2. CP 层 buildCreateResponse 用 CallingAppOrigin 读 origin 并传给 createIntent；
3. PasskeyCreateActivity 读 EXTRA_ORIGIN 并作为第一优先级来源。

⚠️ 安全边界：PasskeyOriginPolicy 的严格校验**不得**被绕过 —— 透传的是系统给的真实
origin，Activity 仍须过 check()，绝不允许改成"从 rpId 自造 origin 直接放行"。
"""
import os
import re
import sys

INTENTS = "app/src/main/java/io/vaultix/vaultix/passkey/PasskeyProviderIntents.kt"
SERVICE = "app/src/main/java/io/vaultix/vaultix/passkey/VaultixCredentialProviderService.kt"
ACTIVITY = "app/src/main/java/io/vaultix/vaultix/passkey/PasskeyCreateActivity.kt"
POLICY = "app/src/main/java/io/vaultix/vaultix/passkey/PasskeyOriginPolicy.kt"


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
    intents = _read(root, INTENTS)
    service = _read(root, SERVICE)
    activity = _read(root, ACTIVITY)
    policy = _read(root, POLICY)

    # 1. extra 定义 + createIntent 写入
    if 'EXTRA_ORIGIN = "vaultix.passkey.origin"' not in intents:
        errors.append("PasskeyProviderIntents 缺少 EXTRA_ORIGIN 定义")
    # ⚠️ createIntent 是**表达式体函数**（`): Intent = Intent(...)`，没有 `{}`），
    #    不能用 _fn_body（靠花括号配平）截取。改为从 `fun createIntent` 到下一个 `fun` 锚定。
    ci_pos = intents.find("fun createIntent")
    if ci_pos < 0:
        create_fn = ""
    else:
        nxt = intents.find("\n    fun ", ci_pos + 1)
        create_fn = intents[ci_pos:nxt if nxt > 0 else len(intents)]
    if "origin: String? = null" not in create_fn:
        errors.append("createIntent 缺少 origin 参数")
    if "putExtra(EXTRA_ORIGIN, origin)" not in create_fn:
        errors.append("createIntent 未写入 EXTRA_ORIGIN")

    # 2. CP 层读取真实 origin 并透传
    build_fn = _fn_body(service, "fun buildCreateResponse")
    if "CallingAppOrigin.originOrNull(request.callingAppInfo)" not in build_fn:
        errors.append("CP 层 buildCreateResponse 未用真实 callingAppInfo 读 origin")
    if not re.search(r'origin\s*=\s*origin', build_fn):
        errors.append("CP 层未把 origin 传给 createIntent")

    # 3. Activity 读取且作为第一优先级
    if "getStringExtra(PasskeyProviderIntents.EXTRA_ORIGIN)" not in activity:
        errors.append("PasskeyCreateActivity 未读 EXTRA_ORIGIN")
    if not re.search(r'origin\s*=\s*originFromProvider\s*\?:\s*originFromCaller', activity):
        errors.append("EXTRA_ORIGIN 未作为第一优先级来源")

    # 4. 安全边界：严格校验仍在，且没被"rpId 自造放行"污染
    check_fn = _fn_body(policy, "fun check(")
    if "origin 无法解析出主机" not in check_fn:
        errors.append("PasskeyOriginPolicy 的空 origin 拒绝被移除（安全退化）")
    # ⚠️ 必须剥掉注释再查：Activity 里有一段讲历史教训的 KDoc 就写着
    #    `?: "https://$rpId"`（"删掉旧兜底"的注释），朴素 in 会假红。
    code_only = "\n".join(
        line.split("//")[0] for line in activity.splitlines()
    )
    if '?: "https://$rpId"' in code_only or '"https://$rpId"' in code_only:
        errors.append("Activity 出现从 rpId 自造 origin 的兜底（安全退化）")

    return errors


def selftest():
    import tempfile
    base = {
        INTENTS: (
            'const val EXTRA_ORIGIN = "vaultix.passkey.origin"\n'
            # 复刻真实形态：表达式体函数（无花括号），否则自测假绿。
            'fun createIntent(\n origin: String? = null,\n): Intent = Intent()\n'
            ' .putExtra(EXTRA_ORIGIN, origin)\n'
            'fun passwordGetIntent() {}\n'
        ),
        SERVICE: (
            'fun buildCreateResponse() {\n'
            ' val origin = CallingAppOrigin.originOrNull(request.callingAppInfo)\n'
            ' createIntent(origin = origin)\n'
            '}\n'
        ),
        ACTIVITY: (
            'val originFromProvider = intent.getStringExtra(PasskeyProviderIntents.EXTRA_ORIGIN)\n'
            'origin = originFromProvider ?: originFromCaller ?: originFromRequest.orEmpty()\n'
            '// 历史注释：`?: "https://$rpId"` 是自造兜底，已删\n'
            'fun check(origin, rpId) { Rejected("origin 无法解析出主机") }\n'
        ),
        POLICY: 'fun check(origin, rpId) { Rejected("origin 无法解析出主机") }\n',
    }
    mutations = [
        (INTENTS, 'putExtra(EXTRA_ORIGIN, origin)', '', "createIntent 不写 extra"),
        (INTENTS, 'origin: String? = null', '', "createIntent 无 origin 参数"),
        (SERVICE, 'CallingAppOrigin.originOrNull(request.callingAppInfo)', 'null', "CP 不读真实 origin"),
        (SERVICE, 'createIntent(origin = origin)', 'createIntent()', "CP 不透传"),
        (ACTIVITY, 'getStringExtra(PasskeyProviderIntents.EXTRA_ORIGIN)', '', "Activity 不读 extra"),
        (ACTIVITY, 'originFromProvider ?: originFromCaller', 'originFromCaller ?: originFromProvider', "优先级被换"),
        (POLICY, 'origin 无法解析出主机', '', "空 origin 拒绝被删"),
    ]
    passed = 0
    with tempfile.TemporaryDirectory() as td:
        # 先验证未变异的 base 必须绿（含注释里的 `https://$rpId`，不能被误报安全退化）
        for r, content in base.items():
            p = os.path.join(td, *r.split("/"))
            os.makedirs(os.path.dirname(p), exist_ok=True)
            with open(p, "w", encoding="utf-8") as fh:
                fh.write(content)
        if check(td):
            print(f"  [假红] 未变异的 base 报错：{check(td)}")
            return 1
        for rel, old, new, label in mutations:
            files = dict(base)
            if old not in files[rel]:
                print(f"  [自测跳过] {label}：锚点不存在")
                continue
            files[rel] = files[rel].replace(old, new)
            for r, content in files.items():
                p = os.path.join(td, *r.split("/"))
                os.makedirs(os.path.dirname(p), exist_ok=True)
                with open(p, "w", encoding="utf-8") as fh:
                    fh.write(content)
            if check(td):
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
        print("❌ passkey origin 透传契约检查失败：")
        for e in errors:
            print("  -", e)
        return 1
    print("✅ passkey origin 透传契约检查通过（#299）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
