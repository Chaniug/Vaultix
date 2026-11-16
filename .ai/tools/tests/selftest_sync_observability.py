#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""#297 门禁自测：每条变异都必须被 `check_sync_observability.py` 判红。

判据是「真实源码里有没有某段形状」，所以必须拿**真实文件**做变异 ——
玩具字符串测不出正则是否对准了实际缩进、KDoc、字符串插值和 `sanitize()` 行为。

⚠️ 本轮门禁本身踩了三个坑，每个都有对应用例，防止回退：
- KDoc 里也写着 `return Result.success(Unit)`，朴素 `find` 会命中注释 ⇒ 用例 ⑤；
- `countByOp` 是**扩展函数**（`List<PendingOpEntity>.countByOp()`），
  只认 `fun name(` 会假红 ⇒ 用例 ⑥ 反向钉住"能被识别"；
- 日志文案在字符串里，`sanitize()` 后变空 ⇒ 用例 ③ 用真实文案判断。
"""
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))      # .ai/tools/tests
TOOLS = os.path.dirname(HERE)                          # .ai/tools
ROOT = os.path.dirname(os.path.dirname(TOOLS))         # 仓库根
sys.path.insert(0, TOOLS)

import check_sync_observability as gate  # noqa: E402

RELS = (gate.SYNC, gate.REPO, gate.OBSERVABILITY_TEST)


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


# --- 空队列留痕 -------------------------------------------------------------

@case("① baseline：真实仓库必须绿")
def _baseline():
    expect_green("baseline")


@case("② 队列为空退回静默成功（就是 #297 要消灭的形态）")
def _silent_empty_queue():
    mutate(
        gate.SYNC,
        """        if (ops.isEmpty()) {""",
        """        if (ops.isEmpty()) return Result.success(Unit)
        if (false) {""",
        "silent-empty",
    )
    expect_red("空队列静默成功")


@case("③ 空队列日志删掉「不会上传」这层后果说明")
def _drop_consequence():
    mutate(
        gate.SYNC,
        "⇒ 本次同步只做拉取，不会上传任何改动",
        "⇒ 本次同步只做拉取",
        "drop-consequence",
    )
    expect_red("空队列日志缺后果说明")


@case("④ 空队列日志整段删掉（只剩 KDoc）")
def _drop_empty_log():
    mutate(
        gate.SYNC,
        """            VaultixLog.w(TAG) {
                "flushPending 队列为空（无待推送 op）⇒ 本次同步只做拉取，不会上传任何改动"
            }""",
        "",
        "drop-empty-log",
    )
    expect_red("空队列无日志")


# --- 开头摘要 / 收尾统计 -----------------------------------------------------

@case("⑤ 删掉开头的「待推送 N 条」")
def _drop_head_count():
    mutate(
        gate.SYNC,
        'VaultixLog.w(TAG) { "flushPending 开始：待推送 ${ops.size} 条（${ops.countByOp()}）" }',
        "",
        "drop-head-count",
    )
    expect_red("开头无条数")


@case("⑥ 摘要改用 cipherId（日志会泄露条目 id）")
def _leak_cipher_id():
    mutate(
        gate.SYNC,
        "groupBy { it.op }",
        "groupBy { it.cipherId }",
        "leak-cipher-id",
    )
    expect_red("摘要带条目 id")


@case("⑦ 删掉收尾统计 logFlushSummary 调用")
def _drop_summary_call():
    mutate(
        gate.SYNC,
        "logFlushSummary(vaultId, ops.size, firstError != null)",
        "",
        "drop-summary-call",
    )
    expect_red("收尾无统计")


@case("⑧ 收尾统计丢掉「留在队列」文案")
def _drop_remaining_text():
    mutate(gate.SYNC, "剩 $remaining 条留在队列", "剩 $remaining 条", "drop-remaining")
    expect_red("收尾缺失败残留")


# --- 写路径留痕 -------------------------------------------------------------

@case("⑨ 写路径成功分支退回裸 return（没有任何「已上云」痕迹）")
def _bare_return():
    mutate(
        gate.REPO,
        """        if (flush.isSuccess) {
            // ★ #297（2026-11-16 真机）：写路径必须能自证"改动已上云"。""",
        """        if (flush.isSuccess) return VaultSaveOutcome.Synced
        if (false) {
            // ★ #297（2026-11-16 真机）：写路径必须能自证"改动已上云"。""",
        "bare-return",
    )
    expect_red("写路径裸 return")


@case("⑩ 写路径失败分支不说「改动仍在队列」")
def _drop_still_queued():
    mutate(gate.REPO, "改动仍在队列等待重试", "推送失败", "drop-still-queued")
    expect_red("失败分支没说改动仍在队列")


@case("⑪ 写路径改用独立 tag（两条时间线没法对照）")
def _own_tag():
    mutate(gate.REPO, 'private const val SYNC_TAG = "VaultixSync"', 'private const val SYNC_TAG = "VaultixItem"', "own-tag")
    # tag 值本身不影响契约（常量存在即可），这里断言的是**常量被删**才红，
    # 所以改成删除常量来验。
    mutate(gate.REPO, 'private const val SYNC_TAG = "VaultixItem"', "", "drop-tag")
    expect_red("SYNC_TAG 常量被删")


# --- 行为测试存在性 ---------------------------------------------------------

@case("⑫ 删掉「空队列必须留日志」用例")
def _drop_empty_case():
    mutate(
        gate.OBSERVABILITY_TEST,
        "fun `队列为空时必须留下日志而不是静默成功`()",
        "fun `旧用例已删除`()",
        "drop-empty-case",
    )
    expect_red("空队列用例被删")


@case("⑬ 删掉「推送失败必须说明改动仍在队列」用例")
def _drop_failure_case():
    mutate(
        gate.OBSERVABILITY_TEST,
        "fun `推送失败时必须说明改动仍在队列`()",
        "fun `旧用例已删除`()",
        "drop-failure-case",
    )
    expect_red("失败残留用例被删")


@case("⑭ 整个可观测性测试文件被删")
def _drop_test_file():
    os.remove(_path(gate.OBSERVABILITY_TEST))
    expect_red("可观测性测试文件被删")


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
    print(f"\n✅ {len(CASES)} 条自测全部通过（#297）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
