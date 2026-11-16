#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""#295 门禁自测：每条变异都必须被 `check_item_key_write.py` 判红。

## 为什么基于**真实源码**做变异

判据是「源码里有没有某段形状」。用玩具字符串测只能证明正则写得通，
证明不了它对准了真文件 —— 而假绿多半出在「正则对不上真实缩进 / 修饰符顺序 /
`sanitize` 把判据本身抹掉」这三处。

⚠️ 本关第一版**就是**被 `sanitize` 打红的：它把 `@SerialName("key")` 抹成
`@SerialName("")`，于是「有没有声明 key」恒红。自测 ⑦ 专门锁这一条。

## 纪律

- 每条变异前先断言**锚点存在**：锚点漂了要立刻报错，不能让"变异没生效"
  伪装成"门禁没抓到"；
- 还原用原始字节，不做 `strip()` 之类的重排。
"""
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))      # .ai/tools/tests
TOOLS = os.path.dirname(HERE)                          # .ai/tools
ROOT = os.path.dirname(os.path.dirname(TOOLS))         # 仓库根
sys.path.insert(0, TOOLS)

import check_item_key_write as gate  # noqa: E402

MAPPER_REL = gate.MAPPER
DTO_REL = gate.DTO
TEST_REL = gate.WRITE_TEST


def _path(rel: str) -> str:
    return os.path.join(ROOT, *rel.split("/"))


def read(rel: str) -> str:
    with open(_path(rel), encoding="utf-8") as fh:
        return fh.read()


def write(rel: str, text: str) -> None:
    with open(_path(rel), "w", encoding="utf-8") as fh:
        fh.write(text)


class Sample:
    """三个文件的原文快照 + 还原。"""

    def __init__(self) -> None:
        self.raw = {rel: read(rel) for rel in (MAPPER_REL, DTO_REL, TEST_REL)}

    def restore(self) -> None:
        for rel, text in self.raw.items():
            write(rel, text)


def mutate(rel: str, old: str, new: str, label: str) -> None:
    text = read(rel)
    if old not in text:
        raise AssertionError(f"[{label}] 锚点不存在，变异未生效：{old[:70]!r}")
    write(rel, text.replace(old, new, 1))


def expect_red(label: str) -> None:
    problems = gate.scan(ROOT)
    if not problems:
        raise AssertionError(f"[{label}] 门禁**没有**报错 —— 这是一条假绿")


def expect_green(label: str) -> None:
    problems = gate.scan(ROOT)
    if problems:
        raise AssertionError(f"[{label}] 门禁误报：{problems}")


CASES = []


def case(name: str):
    def deco(fn):
        CASES.append((name, fn))
        return fn
    return deco


@case("① baseline：真实仓库必须绿")
def _baseline():
    expect_green("baseline")


@case("② 回退成「一律用账号密钥」（原来的缺陷形态）")
def _account_key_only():
    mutate(
        MAPPER_REL,
        "val contentKey = itemKey ?: key",
        "val contentKey = key",
        "account-key-only",
    )
    expect_red("内容密钥退回账号密钥")


@case("③ 声明了 contentKey 却不传下去（假修）")
def _not_passed_down():
    text = read(MAPPER_REL)
    old = "buildUpdateRequest(item, stored, key, contentKey, hasItemKey)"
    if old not in text:
        raise AssertionError("[not-passed] 找不到调用锚点")
    write(MAPPER_REL, text.replace(old, "buildUpdateRequest(item, stored, key, key, hasItemKey)", 1))
    expect_red("contentKey 未传下去")


@case("④ 只用 resolveItemKey 却不据此选钥匙")
def _no_resolve():
    mutate(
        MAPPER_REL,
        "val itemKey = resolveItemKey(stored, key)",
        "val itemKey: SymmetricCryptoKey? = null",
        "no-resolve",
    )
    expect_red("没有解条目密钥")


@case("⑤ 某个可编辑字段仍用账号密钥加密（uri 是最先坏的那个）")
def _uri_back_to_account_key():
    mutate(
        MAPPER_REL,
        "login = overlayLogin(item, stored, contentKey, revision.revisionDate)",
        "login = overlayLogin(item, stored, accountKey, revision.revisionDate)",
        "uri-account-key",
    )
    expect_red("login 段用账号密钥")


@case("⑥ 请求体不再带回包裹态 key")
def _no_key_passthrough():
    mutate(
        MAPPER_REL,
        "key = stored.key.takeIf { hasItemKey },",
        "key = null,",
        "no-key-passthrough",
    )
    expect_red("没带走 key")


@case("⑦ 凭空给老条目塞 key（比原缺陷更坏）")
def _always_send_key():
    mutate(
        MAPPER_REL,
        "key = stored.key.takeIf { hasItemKey },",
        "key = stored.key,",
        "always-send-key",
    )
    expect_red("无条件带 key")


@case("⑧ CipherRequest 不再声明 key 字段")
def _dto_field_removed():
    """⚠️ 必须**定位到 CipherRequest 自己那一段**再删。

    本文件里 `@SerialName("key") val key: String? = null,` 出现了 **5 次**
    （CipherDto / LoginDto / CardDto / SshKeyDto / CipherRequest）——
    第一版用裸字符串替换，删掉的是 CipherDto 里那行，门禁当然不红（自测 ⑧ 抓到的）。
    """
    text = read(DTO_REL)
    head = text.find("data class CipherRequest(")
    if head < 0:
        raise AssertionError("[dto-key-removed] 找不到 CipherRequest")
    decl = text.find('@SerialName("key") val key: String? = null', head)
    if decl < 0:
        raise AssertionError("[dto-key-removed] CipherRequest 段内找不到 key 声明")
    line_end = text.find("\n", decl)
    write(DTO_REL, text[:decl] + text[line_end + 1:])
    expect_red("DTO 少了 key")


@case("⑨ toStoredCipherDto 不再顺延 key")
def _stored_dto_drops_key():
    mutate(
        DTO_REL,
        "key = key ?: previous?.key,",
        "key = previous?.key,",
        "stored-dto-drops-key",
    )
    expect_red("本地重建行丢 key")


@case("⑩ 行为侧对偶用例被删")
def _contract_case_removed():
    mutate(
        TEST_REL,
        "改URI后其它客户端必须能解出新值",
        "改URI后随便看看",
        "contract-case",
    )
    expect_red("主契约用例名不见")


@case("⑪ 门禁对 sanitize 后的文本判 @SerialName（本关第一版的假红）")
def _sanitize_regression():
    """把 `_check_dto_and_entity` 改回「用 sanitize 后的文本判 key 字段」。

    正确的实现在**真实仓库**上必须是绿的；若有人改回 sanitize 版本，
    在 baseline 上就会恒红 —— 这里用"临时打一个与真实仓库等价的文件"来验证
    判据本身的行为：直接把 dto 里的字面量抹掉，模拟 sanitize 视图。
    """
    text = read(DTO_REL)
    head = text.find("data class CipherRequest(")
    if head < 0:
        raise AssertionError("[sanitize] 找不到 CipherRequest")
    decl = text.find('@SerialName("key") val key: String? = null', head)
    if decl < 0:
        raise AssertionError("[sanitize] CipherRequest 段内找不到 key 声明")
    # 把注解改成不含字面量的等价写法：原文视图识别不出（门禁必须红），
    # 而 sanitize 视图里两版都长一样 —— 这正是"用错视图"的典型症状。
    write(
        DTO_REL,
        text[:decl] + '@SerialName("k" + "ey") val key: String? = null' + text[decl + len('@SerialName("key") val key: String? = null'):],
    )
    expect_red("key 声明被拆成拼接后必须红")


CASES_ORDER = CASES


def main() -> int:
    sample = Sample()
    failures = []
    try:
        for name, fn in CASES_ORDER:
            try:
                fn()
                print(f"✅ {name}")
            except AssertionError as error:
                failures.append(f"{name}: {error}")
                print(f"❌ {name}: {error}")
            finally:
                sample.restore()
    finally:
        sample.restore()

    # 全部还原后必须回到绿：证明上面每条红都是"变异造成的"，不是环境脏了。
    if gate.scan(ROOT):
        failures.append("还原后门禁仍红 —— 自测把工作区弄脏了")

    if failures:
        print(f"\n{len(failures)} 条自测未通过：")
        for item in failures:
            print(f"  - {item}")
        return 1
    print(f"\n✅ {len(CASES_ORDER)} 条自测全部通过（#295）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
