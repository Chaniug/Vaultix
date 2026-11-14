#!/usr/bin/env python3
"""`check_production_safety.py` 的自测：探针自己判错会制造假红，假红几次就没人看它了。

覆盖三类必须正确的行为：
  ① 真违规抓得到（裸 Log / `!!` / 门面被删）；
  ② 正当写法不误报（KDoc 里的反面示例、字符串里的 `!!`、DEBUG-only 门面实现）；
  ③ 扫描范围正确（冻结 `reference/`、构建产物、`src/test` 都不算生产代码）。
"""
import importlib.util
import os
import sys
import tempfile

TOOLS = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
spec = importlib.util.spec_from_file_location(
    "check_production_safety", os.path.join(TOOLS, "check_production_safety.py")
)
cps = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cps)

FACADE = cps.FACADE_SINK
PKG = "app/src/main/java/io/vaultix/vaultix"


def put(root, rel, text):
    path = os.path.join(root, *rel.split("/"))
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as fh:
        fh.write(text)


def make_root():
    root = tempfile.mkdtemp(prefix="vaultix-safety-")
    put(root, "settings.gradle.kts", "// marker\n")
    put(
        root,
        FACADE,
        """package io.vaultix.vaultix
import android.util.Log
import io.vaultix.common.logging.VaultixLog

fun install() {
    VaultixLog.install(enabled = BuildConfig.DEBUG) { tag, level, message, throwable ->
        when (level) {
            LogLevel.DEBUG -> Log.d(tag, message, throwable)
            LogLevel.ERROR -> Log.e(tag, message, throwable)
            else -> Unit
        }
    }
}
""",
    )
    return root


def check(cond, message):
    if not cond:
        print("  ✗", message)
        return 1
    return 0


def main():
    failures = 0

    # ① 基线：只有门面实现时应当全绿。
    root = make_root()
    log_hits, bang_hits, facade = cps.scan(root)
    failures += check(not log_hits, f"门面 sink 是白名单，不该报裸 Log：{log_hits}")
    failures += check(not bang_hits, f"基线不该报 !!：{bang_hits}")
    failures += check(not facade, f"门面装配在位，不该报错：{facade}")

    # ② 正当但不「干净」的写法：KDoc 反面示例 + 字符串里的 !! + 走门面的日志。
    put(
        root,
        f"{PKG}/Documented.kt",
        """package io.vaultix.vaultix
/**
 * 以前写的是 `if (BuildConfig.DEBUG) Log.d(TAG, msg)`，还有 `foo!!` —— 都别再写。
 * 字符串模板 `${x}` 只是说明，不是代码。
 */
object Documented {
    private const val TAG = "VaultixDocumented"
    fun note() {
        VaultixLog.w(TAG) { "注意!! 这行文案里有双叹号，但不该被判违规" }
    }
}
""",
    )
    log_hits, bang_hits, _ = cps.scan(root)
    failures += check(not log_hits, f"KDoc 里的反面示例不该误报：{log_hits}")
    failures += check(not bang_hits, f"字符串/KDoc 里的 !! 不该误报：{bang_hits}")

    # ③ 扫描范围：冻结参考实现 / 构建产物 / 测试源码都不算生产代码。
    for rel in (
        "reference/bastion/app/src/main/java/com/bastion/Frozen.kt",
        "app/build/generated/source/Gen.kt",
        "app/src/test/java/io/vaultix/vaultix/Assertions.kt",
    ):
        put(root, rel, "val x = frozen!!\nLog.d(TAG, \"frozen\")\n")
    log_hits, bang_hits, _ = cps.scan(root)
    failures += check(not log_hits, f"非生产代码不该报裸 Log：{log_hits}")
    failures += check(not bang_hits, f"非生产代码不该报 !!：{bang_hits}")

    # ④ 真违规：裸 Log 必须抓到，且只指向那一个文件。
    put(
        root,
        f"{PKG}/Leaky.kt",
        """package io.vaultix.vaultix
import android.util.Log
object Leaky {
    fun go() { Log.d(TAG, "reseal $vaultId") }
}
""",
    )
    log_hits, _, _ = cps.scan(root)
    failures += check(
        len(log_hits) >= 2 and all("Leaky.kt" in h for h in log_hits),
        f"裸 Log 的 import 与调用都应命中且只落在 Leaky.kt：{log_hits}",
    )

    # ⑤ 真违规：`!!` 必须抓到。
    put(root, f"{PKG}/Crashy.kt", "val target = selectedLoginId!!\n")
    _, bang_hits, _ = cps.scan(root)
    failures += check(
        len(bang_hits) == 1 and "Crashy.kt" in bang_hits[0],
        f"生产 !! 应命中且只落在 Crashy.kt：{bang_hits}",
    )

    # ⑥ 门面被删 / 改成常开：白名单不许变成空子。
    put(root, FACADE, "object NoFacade {}\n")
    _, _, facade = cps.scan(root)
    failures += check(bool(facade), "门面 DEBUG-only 装配消失时必须报错")

    if failures:
        print(f"::error title=production safety selftest::{failures} 条自测失败")
        return 1
    print("✅ 生产安全门禁自测通过（6 组：基线 / 注释与字符串 / 扫描范围 / 裸 Log / !! / 门面）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
