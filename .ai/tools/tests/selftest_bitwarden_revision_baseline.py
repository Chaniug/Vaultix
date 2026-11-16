#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""#294 门禁自测：每条变异都必须被 `check_bitwarden_revision_baseline.py` 判红。

## 为什么自测要基于**真实源码**做变异

判据是「源码里有没有某段形状」。用玩具字符串测，只能证明正则写得通，
证明不了它对准了真文件 —— 而 8 成假绿都出在「正则对不上真实缩进 / 修饰符顺序」。
所以每条用例都读真文件、做一处外科手术式改动、断言门禁变红，再字节级还原。

## 纪律

- 每条变异前先断言**锚点存在**：锚点漂了要立刻报错，不能让"变异没生效"
  伪装成"门禁没抓到"；
- 还原用原始字节，不做 `strip()` 之类的重排。
"""
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))      # .ai/tools/tests
TOOLS = os.path.dirname(HERE)                          # .ai/tools
ROOT = os.path.dirname(os.path.dirname(TOOLS))         # 仓库根（.ai/tools → 上一级 → 仓库根）
sys.path.insert(0, TOOLS)

import check_bitwarden_revision_baseline as gate  # noqa: E402

SERVICE_REL = gate.SERVICE
TEST_REL = gate.BASELINE_TEST


def _path(rel: str) -> str:
    return os.path.join(ROOT, *rel.split("/"))


def read(rel: str) -> str:
    with open(_path(rel), encoding="utf-8") as fh:
        return fh.read()


def write(rel: str, text: str) -> None:
    with open(_path(rel), "w", encoding="utf-8") as fh:
        fh.write(text)


class Sample:
    """一个文件的原文快照 + 还原。"""

    def __init__(self) -> None:
        self.raw = {}
        for rel in (SERVICE_REL, TEST_REL):
            self.raw[rel] = read(rel)

    def restore(self) -> None:
        for rel, text in self.raw.items():
            write(rel, text)


def mutate(rel: str, old: str, new: str, label: str) -> None:
    text = read(rel)
    if old not in text:
        raise AssertionError(f"[{label}] 锚点不存在，变异未生效：{old[:60]!r}")
    write(rel, text.replace(old, new, 1))


def expect_red(label: str) -> None:
    problems = gate.scan(ROOT)
    if not problems:
        raise AssertionError(f"[{label}] 门禁**没有**报错 —— 这是一条假绿")


def expect_green(label: str) -> None:
    problems = gate.scan(ROOT)
    if problems:
        raise AssertionError(f"[{label}] 门禁误报：{problems}")


def case(name: str):
    def deco(fn):
        CASES.append((name, fn))
        return fn
    return deco


CASES = []


@case("① baseline：真实仓库必须绿")
def _baseline():
    expect_green("baseline")


@case("② 删掉 flushPending 里的 advanceLocalRevision 调用")
def _no_advance_call():
    text = read(SERVICE_REL)
    start = text.find("                    if (effective.op == OP_UPDATE) {")
    if start < 0:
        raise AssertionError("[no-call] 找不到 OP_UPDATE 分支锚点")
    end = text.find("                    pendingOpDao.remove(op.localId)", start)
    if end < 0:
        raise AssertionError("[no-call] 找不到 remove 锚点")
    write(SERVICE_REL, text[:start] + text[end:])
    expect_red("删回写调用")


@case("③ 把回写挪出 OP_UPDATE 分支（改成无条件调）")
def _outside_update_branch():
    text = read(SERVICE_REL)
    guard = "                    if (effective.op == OP_UPDATE) {"
    if guard not in text:
        raise AssertionError("[outside-branch] 找不到 OP_UPDATE 守卫")
    write(SERVICE_REL, text.replace(guard, "                    if (true) {", 1))
    expect_red("回写不再限定 UPDATE")



def _column_only():
    text = read(SERVICE_REL)
    start = text.find("    private suspend fun advanceLocalRevision(")
    if start < 0:
        raise AssertionError("[column-only] 找不到 advanceLocalRevision")
    end = text.find("\n    private suspend fun executeOperation", start)
    if end < 0:
        raise AssertionError("[column-only] 找不到函数结束锚点")
    body = text[start:end]
    # 把"重建载荷"整段抹掉，只留写列。
    stripped = body.replace(
        """        val payload = runCatching {
            json.decodeFromString<CipherDto>(row.encryptedPayload)
        }.getOrNull()
        val updatedPayload = payload?.let { dto ->
            runCatching {
                json.encodeToString(CipherDto.serializer(), dto.copy(revisionDate = fresh))
            }.getOrNull()
        } ?: row.encryptedPayload
        cipherDao.upsertAll(listOf(row.copy(revisionDate = fresh, encryptedPayload = updatedPayload)))""",
        "        cipherDao.upsertAll(listOf(row.copy(revisionDate = fresh)))",
        1,
    )
    if stripped == body:
        raise AssertionError("[column-only] 未能抹掉载荷重建段，锚点漂了")
    write(SERVICE_REL, text[:start] + stripped + text[end:])
    expect_red("只写列不写载荷")


@case("④ 回写只改列、不重建 encryptedPayload")
def _column_only():
    text = read(SERVICE_REL)
    start = text.find("    private suspend fun advanceLocalRevision(")
    if start < 0:
        raise AssertionError("[column-only] 找不到 advanceLocalRevision")
    end = text.find("\n    private suspend fun executeOperation", start)
    if end < 0:
        raise AssertionError("[column-only] 找不到函数结束锚点")
    body = text[start:end]
    # 把"重建载荷"整段抹掉，只留写列（用正则整段替换，避免缩进漂移打不中）。
    # ⚠️ #296 后载荷重建里多了 `vaultixLocalRevisionDate = null`，
    #    且 upsertAll 改成多行；这里按"从 val payload 到 cipherDao.upsertAll 结束"
    #    整段抹掉，避免继续钉死某一行的排版。
    stripped = re.sub(
        r"        val payload = runCatching \{[\s\S]*?cipherDao\.upsertAll\([\s\S]*?\n        \)",
        "        cipherDao.upsertAll(listOf(row.copy(revisionDate = fresh)))",
        body,
        count=1,
    )
    if stripped == body:
        raise AssertionError("[column-only] 未能抹掉载荷重建段，锚点漂了")
    write(SERVICE_REL, text[:start] + stripped + text[end:])
    expect_red("只写列不写载荷")


@case("⑤ 回写丢掉 isNotBlank 判据")
def _no_blank_guard():
    mutate(
        SERVICE_REL,
        'val fresh = response?.revisionDate?.takeIf { it.isNotBlank() } ?: return',
        'val fresh = response?.revisionDate ?: return',
        "no-blank-guard",
    )
    expect_red("空串未挡")


@case("⑥ 载荷里的 copy 不再换 revisionDate")
def _payload_copy_missing():
    # #296 把 encodeToString 改成多行并多清一个字段；锚点取更小的 `dto.copy(`，
    # 变异成 `dto.noCopy(` 后门禁的「编码参数里必须带 copy」判据必然红。
    mutate(
        SERVICE_REL,
        "dto.copy(",
        "dto.noCopy(",
        "payload-copy",
    )
    expect_red("载荷未换 revision")


@case("⑦ 整个 advanceLocalRevision 函数被删")
def _function_deleted():
    text = read(SERVICE_REL)
    start = text.find("    private suspend fun advanceLocalRevision(")
    end = text.find("\n    private suspend fun executeOperation", start)
    if start < 0 or end < 0:
        raise AssertionError("[fn-deleted] 定位失败")
    write(SERVICE_REL, text[:start] + text[end + 1:])
    expect_red("函数被删")


@case("⑧ 主契约用例被改名")
def _contract_case_renamed():
    mutate(
        TEST_REL,
        "fun `UPDATE 推送成功后本地条目的 revisionDate 必须跟上服务端`()",
        "fun `推送后基线推进`()",
        "case-renamed",
    )
    expect_red("用例改名")


@case("⑨ 行为测试文件缺失")
def _test_file_missing():
    os.rename(_path(TEST_REL), _path(TEST_REL) + ".moved")
    try:
        expect_red("测试文件缺失")
    finally:
        os.rename(_path(TEST_REL) + ".moved", _path(TEST_REL))


@case("⑩ KDoc 里提到旧写法不得误报")
def _kdoc_not_flagged():
    mutate(
        SERVICE_REL,
        "    private suspend fun advanceLocalRevision(",
        "    // 旧写法：flushPending 从不回写；advanceLocalRevision 不存在。\n"
        "    private suspend fun advanceLocalRevision(",
        "kdoc",
    )
    expect_green("KDoc 注释不算违规")


def main() -> int:
    sample = Sample()
    failures = []
    for name, fn in CASES:
        try:
            fn()
            print(f"  ✅ {name}")
        except AssertionError as exc:
            print(f"  ❌ {name}\n     {exc}")
            failures.append(name)
        finally:
            sample.restore()

    # 还原后必须回到绿，否则说明还原本身坏了。
    try:
        expect_green("after-restore")
        print("  ✅ ⑪ 全部还原后真实仓库复绿")
    except AssertionError as exc:
        print(f"  ❌ ⑪ 还原后未复绿：{exc}")
        failures.append("after-restore")

    total = len(CASES) + 1
    if failures:
        print(f"\n❌ 自测失败 {len(failures)}/{total}：{', '.join(failures)}")
        return 1
    print(f"\n✅ 自测 {total}/{total} 全通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
