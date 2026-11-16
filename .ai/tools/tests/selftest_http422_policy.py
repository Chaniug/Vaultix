#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""#296 门禁自测：每条变异都必须被 `check_http422_policy.py` 判红。

和 #295 一样基于**真实源码**做变异：判据是「源码里有没有某段形状」，
用玩具字符串测不出正则是否对准了真文件的缩进 / 修饰符 / sanitize 行为。

特别地，断言字符串字面量（`<enc>` / `<jwt>`）必须读原文 ——
`sanitize()` 会把它们抹掉，这正是 #295 踩过的坑。
"""
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))      # .ai/tools/tests
TOOLS = os.path.dirname(HERE)                          # .ai/tools
ROOT = os.path.dirname(os.path.dirname(TOOLS))         # 仓库根
sys.path.insert(0, TOOLS)

import check_http422_policy as gate  # noqa: E402

RELS = (gate.SYNC, gate.FAILURE_TEST, gate.REVISION_TEST)


def _path(rel: str) -> str:
    return os.path.join(ROOT, *rel.split("/"))


def read(rel: str) -> str:
    with open(_path(rel), encoding="utf-8") as fh:
        return fh.read()


def write(rel: str, text: str) -> None:
    with open(_path(rel), "w", encoding="utf-8") as fh:
        fh.write(text)


class Sample:
    def __init__(self) -> None:
        self.raw = {rel: read(rel) for rel in RELS}

    def restore(self) -> None:
        for rel, text in self.raw.items():
            write(rel, text)


def mutate(rel: str, old: str, new: str, label: str) -> None:
    text = read(rel)
    if old not in text:
        raise AssertionError(f"[{label}] 锚点不存在，变异未生效：{old[:70]!r}")
    write(rel, text.replace(old, new, 1))


def expect_red(label: str) -> None:
    if not gate.scan(ROOT):
        raise AssertionError(f"[{label}] 门禁**没有**报错 —— 假绿")


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


@case("② 把 422 重新塞回弃单集合（用户实测的缺陷形态）")
def _422_definitive_again():
    mutate(
        gate.SYNC,
        "it !in setOf(401, HTTP_CONFLICT, HTTP_UNPROCESSABLE_ENTITY, 408, 429)",
        "it !in setOf(401, HTTP_CONFLICT, 408, 429)",
        "422-definitive",
    )
    expect_red("422 回到弃单集合")


@case("③ 常量声明成别的数字（只改名不接线）")
def _wrong_code():
    mutate(gate.SYNC, "HTTP_UNPROCESSABLE_ENTITY = 422", "HTTP_UNPROCESSABLE_ENTITY = 423", "wrong-code")
    expect_red("422 常量写错")


@case("④ 失败日志不再打响应体（退回只能靠猜的状态）")
def _no_body_log():
    mutate(
        gate.SYNC,
        '(httpError?.let { safeErrorBody(it) }?.let { " body=$it" } ?: "")',
        '""',
        "no-body-log",
    )
    expect_red("失败日志丢掉响应体")


@case("⑤ 删掉 EncString 脱敏（日志会泄密）")
def _no_enc_redaction():
    mutate(gate.SYNC, '"<enc>"', '"<x>"', "no-enc-redaction")
    expect_red("EncString 未脱敏")


@case("⑥ 删掉 JWT 脱敏")
def _no_jwt_redaction():
    mutate(gate.SYNC, '"<jwt>"', '"<x>"', "no-jwt-redaction")
    expect_red("JWT 未脱敏")


@case("⑦ 删掉日志限长")
def _no_limit():
    mutate(gate.SYNC, ".take(512)", ".take(1_000_000)", "no-limit")
    expect_red("错误体未限长")


@case("⑧ UPDATE 成功后不清本地展示时间（「修改于」继续骗人）")
def _keep_local_timestamp():
    mutate(gate.SYNC, "vaultixLocalRevisionDate = null,", "vaultixLocalRevisionDate = dto.vaultixLocalRevisionDate,", "keep-local-time")
    expect_red("未清除本地展示时间")


@case("⑨ 删掉 422 行为用例")
def _drop_case():
    mutate(gate.FAILURE_TEST, "422 必须保留在队列而不是弃单", "旧用例已删除", "drop-422-case")
    expect_red("422 行为用例被删")


@case("⑩ 删掉脱敏行为用例")
def _drop_redaction_case():
    mutate(gate.FAILURE_TEST, "错误响应体必须脱敏后可见", "旧用例已删除", "drop-redaction-case")
    expect_red("脱敏行为用例被删")


def main() -> int:
    sample = Sample()
    failures = []
    try:
        for name, fn in CASES:
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

    if gate.scan(ROOT):
        failures.append("还原后门禁仍红 —— 自测把工作区弄脏了")

    if failures:
        print(f"\n{len(failures)} 条自测未通过：")
        for item in failures:
            print(f"  - {item}")
        return 1
    print(f"\n✅ {len(CASES)} 条自测全部通过（#296）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
