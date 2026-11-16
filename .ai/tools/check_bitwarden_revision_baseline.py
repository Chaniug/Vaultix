#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""#294：Bitwarden 推送 UPDATE 成功后，本地 `revisionDate` 基线**必须**跟上服务端。

## 为什么单列一关（2026-11-16，#294）

真机症状（用户原话）：「bitwarden 自建的服务器，我修改了条目，点击了同步，
显示的是正常的，但是服务器端却没有看见改动的内容。」

整条链上**每一环单独看都合法**，所以编译 / 单测 / detekt 一个都抓不住：

```
编辑保存 → CipherMapper.toUpdateRequest()
             把本地行的 revisionDate 塞进 lastKnownRevisionDate
   ↓
flushPending → PUT /ciphers/{id}
   ↓ 服务端（Vaultwarden src/api/core/ciphers.rs）
   Ok(dt) if cipher.updated_at - dt > 1s
       => err!("The client copy of this cipher is out of date.")
   ↓ 若基线过期 ⇒ 直接拒绝
400 ⇒ 落在 DEFINITIVE_HTTP_CODES ⇒ 按「确定性失败」**弃单**
   ↓ 队列行被 remove，本地行保留
ItemRepositoryImpl.flushAfterLocalWrite → 返回 Queued
   ↓ UPDATE 不是 CREATE，不抛异常
UI 显示「已保存」 —— 改动只留在本机，服务端一无所知
```

**基线的缺口**在中间那一环：`flushPending` 此前把 `executeOperation` 的返回值
**只给 CREATE 用**（`remapCreatedLocalRow`），UPDATE 的 `CipherResponse.revisionDate`
被整个丢掉 ⇒ 本地永远停在编辑前那一刻 ⇒ 用户的**第二次**编辑必然带过期基线上门。

## 检查项

1. `flushPending` 的 UPDATE 成功分支必须调用 `advanceLocalRevision(...)`
   —— 删掉它整条链立刻复活；
2. `advanceLocalRevision` 函数体必须**同时**写两处：
   - `revisionDate`（列值）
   - `encryptedPayload`（下一次编辑真正读的那份 `CipherDto`）
   只写一处 = 看起来修了、实际没修（`updateItem` 走的是载荷里那份）；
3. `advanceLocalRevision` 必须**拒绝空串**（`isNotBlank()` 判据）
   —— 把非空基线写成空 = 不带 `lastKnownRevisionDate` = 服务端放弃并发检测 =
   多设备静默互相覆盖，比旧基线更坏；
4. 回归测试 `BitwardenSyncRevisionBaselineTest` 仍在，且主契约用例名不得改
   —— 它是本关的行为侧对偶（源码关管形状，用例管运行期）。

⚠️ 注释里提到旧写法不算违规：KDoc 正在解释「以前为什么错」。
   判据复用 [check_production_safety.sanitize]（#291 的教训：同一判据别抄第二份）。
"""
import os
import re
import sys

from check_production_safety import sanitize

SERVICE = "data/bitwarden/src/main/java/io/vaultix/data/bitwarden/sync/BitwardenSyncService.kt"
BASELINE_TEST = (
    "data/bitwarden/src/test/java/io/vaultix/data/bitwarden/sync/"
    "BitwardenSyncRevisionBaselineTest.kt"
)

# 主契约用例名（源码门禁与行为测试的对偶锚点）。
CONTRACT_CASE = "UPDATE 推送成功后本地条目的 revisionDate 必须跟上服务端"

ADVANCE_FN = "advanceLocalRevision"


def _read(root: str, rel: str) -> str:
    path = os.path.join(root, *rel.split("/"))
    if not os.path.isfile(path):
        return ""
    return open(path, encoding="utf-8").read()


def _function_body(code: str, name: str) -> str:
    """截出一个方法体（到下一个同级 `fun` 或类尾为止）。

    本文件里 `advanceLocalRevision` 是 private suspend，缩进 4 空格；
    `flushPending` 是 public suspend，同样 4 空格。用宽松匹配以免被
    修饰符顺序改动（private suspend / suspend private）打成假红。
    """
    head = re.search(r"\n    (?:private |internal |public )?suspend fun " + re.escape(name) + r"\(", code)
    if not head:
        head = re.search(r"\n    fun " + re.escape(name) + r"\(", code)
    if not head:
        return ""
    nxt = re.search(r"\n    (?:private |internal |public |override )*(?:suspend )?fun ", code[head.end():])
    end = head.end() + nxt.start() if nxt else len(code)
    return code[head.start():end]


def scan(root: str):
    """返回可直接打印的问题列表；空列表 = 通过。"""
    problems = []

    raw_service = _read(root, SERVICE)
    if not raw_service:
        return [f"找不到 {SERVICE} —— 文件被移动/改名时本关必须红，不得静默通过"]

    code = sanitize(raw_service)

    # ── 1) 成功分支必须回写基线 ──────────────────────────────────────
    flush_body = _function_body(code, "flushPending")
    if not flush_body:
        problems.append(f"{SERVICE}: 找不到 `flushPending` 函数体（改名/重构后本关失效）")
    else:
        if not re.search(r"\b" + ADVANCE_FN + r"\s*\(", flush_body):
            problems.append(
                f"{SERVICE}: flushPending 里没有调用 `{ADVANCE_FN}(...)` —— "
                "UPDATE 推送成功后必须把服务端回的新 revisionDate 写回本地（#294）"
            )
        # 只在 UPDATE 分支调：CREATE 走的是 remap（服务端换 id），语义不同。
        if not re.search(r"OP_UPDATE\s*\)\s*\{[^}]*" + ADVANCE_FN, flush_body):
            problems.append(
                f"{SERVICE}: `{ADVANCE_FN}` 没有被 `OP_UPDATE` 分支包住 —— "
                "CREATE / DELETE 成功时不该走这条回写（#294）"
            )

    # ── 2) 回写函数本身必须两处都写 ──────────────────────────────────
    advance_body = _function_body(code, ADVANCE_FN)
    if not advance_body:
        problems.append(
            f"{SERVICE}: 找不到 `{ADVANCE_FN}` —— 没有它，基线就没人推进（#294）"
        )
    else:
        has_column = re.search(r"revisionDate\s*=\s*fresh", advance_body)
        has_payload = re.search(r"encryptedPayload\s*=", advance_body)
        if not has_column:
            problems.append(
                f"{SERVICE}: `{ADVANCE_FN}` 没有写 `revisionDate = fresh` —— 列值没推进（#294）"
            )
        if not has_payload:
            problems.append(
                f"{SERVICE}: `{ADVANCE_FN}` 没有重建 `encryptedPayload` —— "
                "下一次编辑读的是载荷里那份 revisionDate，只改列等于没修（#294）"
            )
        if not re.search(r"copy\s*\(\s*revisionDate\s*=\s*fresh", advance_body):
            problems.append(
                f"{SERVICE}: `{ADVANCE_FN}` 没有把载荷里 `CipherDto.revisionDate` "
                "换掉（`dto.copy(revisionDate = fresh)`）（#294）"
            )
        # ⚠️ 上面那条只看"有没有 copy(revisionDate = fresh)"，但这行**可以存在却不生效**：
        #   写成 `json.encodeToString(CipherDto.serializer(), dto)` 时，
        #   copy 表达式还躺在 `payload?.let` 之外。所以还要正向确认
        #   **编码那一行的参数里真的带着更新后的 dto**（自测 ⑥ 抓到的假绿）。
        encode_calls = re.findall(r"encodeToString\s*\(\s*CipherDto\.serializer\s*\(\s*\)\s*,\s*([^)]+)", advance_body)
        if not encode_calls:
            problems.append(
                f"{SERVICE}: `{ADVANCE_FN}` 里没有 `encodeToString(CipherDto.serializer(), ...)` "
                "—— 载荷重建不见了（#294）"
            )
        elif not any("copy" in call and "revisionDate" in call for call in encode_calls):
            problems.append(
                f"{SERVICE}: `{ADVANCE_FN}` 编码载荷时没有带上更新后的 dto"
                f"（实得 {encode_calls}）—— 载荷里的 revisionDate 仍是旧值（#294）"
            )
        # ── 3) 空串必须挡住 ─────────────────────────────────────────
        if not re.search(r"isNotBlank\s*\(\s*\)", advance_body):
            problems.append(
                f"{SERVICE}: `{ADVANCE_FN}` 没有 `isNotBlank()` 判据 —— "
                "把非空基线写成空串 = 放弃并发检测，比旧基线更坏（#294）"
            )

    # ── 4) 行为侧对偶仍在 ───────────────────────────────────────────
    raw_test = _read(root, BASELINE_TEST)
    if not raw_test:
        problems.append(
            f"找不到 {BASELINE_TEST} —— 源码关必须配一条运行期用例，"
            "否则「回写写错了」没人抓得住（#294）"
        )
    else:
        # ⚠️ 在**原始文本**里找用例名，不能用 sanitize 后的：
        #   Kotlin 的 `fun \`中文用例名\`()` 里那个名字本身是字符串字面量，
        #   sanitize 会把它抹空 ⇒ 假红（本关第一版就红在这）。
        #   这里不是在检查"有没有偷偷做编码转换"，只在确认用例还在，用原文更准。
        if CONTRACT_CASE not in raw_test:
            problems.append(
                f"{BASELINE_TEST}: 主契约用例 `{CONTRACT_CASE}` 不见了 —— "
                "改名可以，但要同步改本关的 CONTRACT_CASE（#294）"
            )

    return problems


def main() -> int:
    root = sys.argv[1] if len(sys.argv) > 1 else "."
    problems = scan(root)
    if not problems:
        print("✅ Bitwarden 推送后 revisionDate 基线契约通过（#294）")
        return 0
    print("❌ Bitwarden 推送后 revisionDate 基线契约被破坏（#294）：")
    for item in problems:
        print(f"  - {item}")
    return 1


if __name__ == "__main__":
    sys.exit(main())
