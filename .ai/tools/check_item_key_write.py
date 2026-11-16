#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""#295：编辑已有条目时，**可编辑字段必须用条目自己的那把钥匙加密**。

## 为什么单列一关（2026-11-16，#295）

真机症状（用户原话）：「在 bitwarden 库中已有条目里面，增加或者修改一条网址内容，
点击保存，保存本地能够显示，但是云端却没有修改后的内容。」

Bitwarden 里同时有**两把**钥匙在干活：

| 加密谁 | 用什么钥匙 | 依据 |
|---|---|---|
| 条目字段（name / login / fields / …） | **该条目的密钥**（条目带 `key` 时） | 官方 `Cipher.toEncryptedNetworkCipher()`；Keyguard `buildCipherCodecPair` 的 `itemCrypto` |
| `key` 这个字段本身 | 账号密钥（组织条目用组织密钥） | Keyguard `keyBase64 ... globalCrypto::transformBase64` |

而 Vaultix 此前：

- **读**方向是对的 —— `toDomain` 先用条目密钥解，失败才回退账号密钥（`decryptToString`）；
- **写**方向一律用账号密钥加密，且 `CipherRequest` **压根没声明 `key`**。

于是「带 key 的条目」被编辑一次之后：

```
Vaultix 自己读    → 条目密钥失败 → 回退账号密钥 → 正常（本地看起来一切正常）
官方/网页读      → 只按 key 用条目密钥解 → MacVerificationException
服务端           → 只搬运密文，看不出问题，revisionDate 照常返回新值
```

用户看到的正是「保存成功、云端没变、点同步也拉不回旧版」（本地行确实是新内容）。
**没有任何一环会报错**，编译 / 单测 / detekt 全绿 —— 所以必须有这一关。

## 检查项

1. `toUpdateRequest` 必须解出条目密钥并据此选**内容密钥**：
   `val contentKey = itemKey ?: key`；
2. 该 `contentKey` 必须**真的传进** `buildUpdateRequest(...)`（只声明不传 = 假修，
   自测 ③ 专门抓这种）；
3. `buildUpdateRequest` 里所有"可编辑字段"的加密必须用 `contentKey`，
   **不能**再出现用它外面那把账号密钥加密的行 —— 逐个 `encryptString(...)` 点名；
4. 请求体必须把条目密钥的**包裹态原样带回**：
   `key = stored.key.takeIf { hasItemKey }`（不带 = 服务端清掉 key = 死数据；
   凭空塞 = 老条目跳到另一个加密域）；
5. `CipherRequest` 必须声明 `key` 字段，且 `toStoredCipherDto` 必须顺延它
   （否则本地重建行时把 key 丢了，同一处缺陷换个入口复活）；
6. 回归测试 `CipherMapperItemKeyWriteTest` 在，且 4 条主契约用例名不得改。

⚠️ 判据复用 [check_production_safety.sanitize]，注释里提到旧写法不算违规。
"""
import os
import re
import sys

from check_production_safety import sanitize

MAPPER = "data/bitwarden/src/main/java/io/vaultix/data/bitwarden/mapper/CipherMapper.kt"
DTO = "data/bitwarden/src/main/java/io/vaultix/data/bitwarden/model/BitwardenDto.kt"
WRITE_TEST = (
    "data/bitwarden/src/test/java/io/vaultix/data/bitwarden/mapper/"
    "CipherMapperItemKeyWriteTest.kt"
)

BUILD_FN = "buildUpdateRequest"

# 主契约用例名（源码门禁与行为测试的对偶锚点）。
CONTRACT_CASES = (
    "改URI后其它客户端必须能解出新值",
    "改名后其它客户端必须能解出新值",
    "改自定义字段后其它客户端必须能解出新值",
    "改密码后其它客户端必须能解出新值",
)

# 用内容密钥加密的字段（少一个都会让某类编辑在其它客户端上解不开）。
CONTENT_KEY_FIELDS = ("name", "notes", "login", "card", "identity", "sshKey", "fields")


def _read(root: str, rel: str) -> str:
    path = os.path.join(root, *rel.split("/"))
    if not os.path.isfile(path):
        return ""
    return open(path, encoding="utf-8").read()


def _function_body(code: str, name: str) -> str:
    """截出一个方法体（到下一个同级 `fun` 为止），宽松匹配修饰符顺序。"""
    head = re.search(
        r"\n    (?:private |internal |public )?(?:suspend )?fun " + re.escape(name) + r"\(", code
    )
    if not head:
        return ""
    nxt = re.search(
        r"\n    (?:private |internal |public |override )*(?:suspend )?fun ",
        code[head.end():],
    )
    end = head.end() + nxt.start() if nxt else len(code)
    return code[head.start():end]


def _data_class_body(code: str, name: str):
    """截出 `data class <name>( ... )` 的**括号内**原文；找不到返回 None。

    用括号配平而不是正则 `.*?\\n\\)`：DTO 里有多层嵌套括号（`@SerialName(...)`、
    默认值里的构造调用），非贪婪到第一个 `\\n)` 会提前截断。
    """
    head = re.search(r"data class " + re.escape(name) + r"\s*\(", code)
    if not head:
        return None
    depth = 1
    i = head.end()
    while i < len(code) and depth > 0:
        ch = code[i]
        if ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
        i += 1
    return code[head.end():i - 1] if depth == 0 else None


def _check_content_key_choice(code: str, problems: list) -> None:
    """1) + 2)：条目密钥必须解出来、选进 contentKey，并真的传下去。"""
    update_body = _function_body(code, "toUpdateRequest")
    if not update_body:
        problems.append(f"{MAPPER}: 找不到 `toUpdateRequest`（改名/重构后本关失效）")
        return

    if not re.search(r"resolveItemKey\s*\(\s*stored", update_body):
        problems.append(
            f"{MAPPER}: `toUpdateRequest` 没有解条目密钥 —— "
            "带 key 的条目会用账号密钥加密字段，其它客户端全部解不开（#295）"
        )
    if not re.search(r"val\s+contentKey\s*=\s*itemKey\s*\?:\s*key", update_body):
        problems.append(
            f"{MAPPER}: 缺少 `val contentKey = itemKey ?: key` —— "
            "字段加密用的钥匙必须是「条目密钥优先，回退账号密钥」（#295）"
        )
    # 只声明不传 = 假修：必须看见 contentKey 出现在 buildUpdateRequest 的实参里。
    call = re.search(re.escape(BUILD_FN) + r"\s*\(([^)]*)\)", update_body, re.S)
    if not call:
        problems.append(f"{MAPPER}: `toUpdateRequest` 没有调用 `{BUILD_FN}(...)`（#295）")
    elif "contentKey" not in call.group(1):
        problems.append(
            f"{MAPPER}: 调用 `{BUILD_FN}` 时没有把 `contentKey` 传进去 —— "
            "声明了却不用，属于假修（#295）"
        )


def _check_all_editable_fields(code: str, problems: list) -> None:
    """3) buildUpdateRequest 里每个可编辑字段都必须用 contentKey 加密。"""
    body = _function_body(code, BUILD_FN)
    if not body:
        problems.append(
            f"{MAPPER}: 找不到 `{BUILD_FN}` —— 没有它，条目密钥就没有落点（#295）"
        )
        return

    for field in CONTENT_KEY_FIELDS:
        # 形如 `name = crypto.encryptString(item.title, contentKey)` 或
        #      `login = overlayLogin(item, stored, contentKey, ...)` 或
        #      `fields = item.customFields.map { mapCustomFieldRequest(it, contentKey) }`
        pattern = re.compile(r"\n\s{12}" + field + r"\s*=\s*([^\n]*(?:\n\s{16}[^\n]*)*)")
        hit = pattern.search(body)
        if not hit:
            problems.append(
                f"{MAPPER}: `{BUILD_FN}` 里找不到字段 `{field}` 的赋值 —— "
                "字段被挪走/改名后本关判据要与时俱进（#295）"
            )
            continue
        segment = hit.group(1)
        # 只取到该字段表达式结束（下一个同缩进字段为止，已在正则里用非贪婪收敛）。
        segment = segment.split("\n            ")[0] if "\n            " in segment else segment
        if "contentKey" not in segment and "accountKey" not in segment:
            problems.append(
                f"{MAPPER}: 字段 `{field}` 的加密调用没有出现任何密钥参数 —— "
                "无法确认它用的是条目密钥（#295）"
            )
        elif "contentKey" not in segment:
            problems.append(
                f"{MAPPER}: 字段 `{field}` 仍在用账号密钥加密 —— "
                "带 key 的条目改完，官方客户端/网页会解不开（#295）"
            )

    # 账号密钥只允许出现在「解旧密码 / 决定历史」这类**读**用途里，
    # 不允许再出现 `crypto.encryptString(..., accountKey)` 这种**写**用途。
    if re.search(r"crypto\.encryptString\s*\([^)]*accountKey", body):
        problems.append(
            f"{MAPPER}: `{BUILD_FN}` 里仍有 `crypto.encryptString(..., accountKey)` —— "
            "可编辑字段一律用 contentKey（#295）"
        )


def _check_key_passthrough(code: str, problems: list) -> None:
    """4) 请求体必须原样带回包裹态 key（且只在本来就有 key 时带）。"""
    body = _function_body(code, BUILD_FN)
    if not body:
        return
    if not re.search(r"key\s*=\s*stored\.key\.takeIf\s*\{\s*hasItemKey\s*\}", body):
        problems.append(
            f"{MAPPER}: `{BUILD_FN}` 没有 `key = stored.key.takeIf {{ hasItemKey }}` —— "
            "不带 = 服务端清掉条目密钥（字段仍是条目密钥密文 ⇒ 死数据）；"
            "无脑带 = 老条目凭空多个 key（#295）"
        )
    if not re.search(r"hasItemKey\s*=\s*itemKey\s*!=\s*null", code):
        problems.append(
            f"{MAPPER}: 没有 `hasItemKey = itemKey != null` —— "
            "「本来就有 key」与「本来没有」必须分开，否则会凭空塞 key（#295）"
        )


def _check_dto_and_entity(dto_raw: str, problems: list) -> None:
    """5) DTO 声明 + toStoredCipherDto 顺延。"""
    if not dto_raw:
        problems.append(f"找不到 {DTO} —— 文件被移动/改名时本关必须红，不得静默通过")
        return
    # ⚠️ 这一段**必须看原文**：判据本身是 `@SerialName("key")`，而 sanitize 会把
    #   每一个字符串字面量抹成 `""`（见 check_production_safety.sanitize）。
    #    用 sanitize 后的文本判 ⇒ 恒红（本关第一版就红在这）。
    #
    # ⚠️⚠️ 而且**必须逐个数据类切段再判**：`CipherDto` 自己也有一模一样的
    #   `@SerialName("key") val key: String? = null`。只在全文里搜这个形状，
    #   把 `CipherRequest` 里那行删掉也照样命中 DTO 里那行 ⇒ 假绿（自测 ⑧ 抓到的）。
    request_block = _data_class_body(dto_raw, "CipherRequest")
    if request_block is None:
        problems.append(f"{DTO}: 找不到 `CipherRequest` 定义（改名后本关失效）")
    elif not re.search(r'@SerialName\("key"\)\s*val\s+key\s*:\s*String\?', request_block):
        problems.append(
            f"{DTO}: `CipherRequest` 没有声明 `key` 字段 —— "
            "请求体带不了条目密钥，编辑后其它客户端解不开（#295）"
        )

    code = sanitize(dto_raw)

    stored_fn = re.search(r"fun CipherRequest\.toStoredCipherDto\((.*?)\n\)\s*:\s*CipherDto", code, re.S)
    if not stored_fn:
        problems.append(f"{DTO}: 找不到 `toStoredCipherDto`（改名后本关失效）")
        return
    if not re.search(r"\bkey\s*=\s*key\s*\?:\s*previous\?\.key", code):
        problems.append(
            f"{DTO}: `toStoredCipherDto` 没有顺延 `key` —— "
            "本地重建行时把条目密钥丢了，同一缺陷换个入口复活（#295）"
        )


def _check_write_test(root: str, problems: list) -> None:
    """6) 行为侧对偶仍在（在原始文本里找用例名，sanitize 会抹掉反引号名字）。"""
    raw = _read(root, WRITE_TEST)
    if not raw:
        problems.append(
            f"找不到 {WRITE_TEST} —— 源码关必须配运行期用例，"
            "否则「钥匙用错了」没人抓得住（#295）"
        )
        return
    for case in CONTRACT_CASES:
        if case not in raw:
            problems.append(
                f"{WRITE_TEST}: 主契约用例 `{case}` 不见了 —— "
                "改名可以，但要同步改本关的 CONTRACT_CASES（#295）"
            )


def scan(root: str):
    """返回可直接打印的问题列表；空列表 = 通过。"""
    problems = []

    raw_mapper = _read(root, MAPPER)
    if not raw_mapper:
        return [f"找不到 {MAPPER} —— 文件被移动/改名时本关必须红，不得静默通过"]

    code = sanitize(raw_mapper)
    _check_content_key_choice(code, problems)
    _check_all_editable_fields(code, problems)
    _check_key_passthrough(code, problems)
    _check_dto_and_entity(_read(root, DTO), problems)
    _check_write_test(root, problems)
    return problems


def main() -> int:
    root = sys.argv[1] if len(sys.argv) > 1 else "."
    problems = scan(root)
    if not problems:
        print("✅ 条目密钥写方向契约通过（#295）")
        return 0
    print("❌ 条目密钥写方向契约被破坏（#295）：")
    for item in problems:
        print(f"  - {item}")
    return 1


if __name__ == "__main__":
    sys.exit(main())
