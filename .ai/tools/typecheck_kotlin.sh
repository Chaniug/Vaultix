#!/usr/bin/env bash
# 本地 Kotlin **真编译 + 真运行**（2026-10-03 新增，见 issues/01 #145.4 #145.5）
#
# ## 它补的是哪个洞
#
# 本仓库沙箱**没有 Android SDK**，所以新写的 Kotlin 只能靠 CI。而 CI 有两个特性
# 让"靠 CI 兜底"比看上去贵得多：
#
#   1. `continue-on-error: true` 使 job 卡片绿/红都不可信（#145）；
#   2. 编译失败藏在日志里，必须拉日志数 `(^|Z )e: ` 才知道（#145.1）。
#
# ⇒ 代价：**每写错一个字符，赔一轮 CI**。2026-10-03 为此栽了两次
# （`capture` 未用 `capture()` 包裹、多写了一行 `import io.mockk.capture`）。
#
# 本脚本走**另一条路**：沙箱没有 SDK，但**跑这些单测并不需要 SDK** ——
# 需要的是被引用类的**签名与可替换实现**：
#   · `org.robolectric:android-all` 提供 `android.*` 真实字节码（含 API 29+ 回调）；
#   · Maven 拉 mockk / coroutines / junit / byte-buddy；
#   · Gradle 缓存里的 `kotlin-compiler-embeddable` 编译；
#   · `JUnitCore` 真跑。
#
# ## 为什么不干脆装 Android SDK
#
# 装SDK 要下的东西（build-tools / platform-tools / AGP 依赖树）远大于
# 「一个 jar 提供签名」—— 而**类型检查与这些单测的执行根本不需要那套**。
# 实测：130MB `android-all` + 20MB 依赖 = 本地能编译**且能跑**。
#
# ## 能查什么、不能查什么
#
# | 能查 | 不能查 |
# |---|---|
# | 类型不匹配、未解析引用、可见性、`import` 写错 | Android 资源（`R` 类）、`BuildConfig` |
# | 泛型推断、lambda 签名、mockk 语法 | Compose 编译插件（要 AGP） |
# | `mockk()` 参数形态（CI 踩过的坑都在这列） | detekt 规则、编码检查 |
# | 项目自身类型错误（`XxxTest` 自动带被测类） | **依赖 Android SDK 空注解**的写法（见下） |
# | **运行期行为**：竞态、flaky、虚拟时间陷阱 | |
#
# ⚠️ **已知盲区（实测确认，别拿本地绿灯当证据）**：
#   `android-all.jar` 字节码**没有 `@NonNull`** ⇒ Kotlin 把这些参数当**平台类型**
#   ⇒ `onActivitySaveInstanceState(a, null)` 这种在真实 android.jar 下编译不过的
#   写法，**本地检查会放行**。凡"因 Kotlin 空安全而不能这么写"的，只能靠 CI 验。
#
# ⇒ 它**不能替代** CI，但把反馈从**一轮 CI** 压到 **20 秒**。
#
# ## 它抓到的最典型一例（CI run `37132804360`，2026-10-03）
#
# 那次 CI **编译 0 错误**，但 6 条用例里 1 条红在 `IllegalStateException`——
# 根因是 `runTest` 的**虚拟时间**把 `withTimeout(5000)` 直接跳满，于是"等真实线程"
# 变成"立刻超时"。**这种错编译永远查不出来，只有真跑才看得见。**
# ⚠️ 它在本地的表现是**随机的**（连跑 5 次：2 绿 3 红）⇒ 极易被误判成
# "偶发 flaky，算了"。换 `runBlocking` 后连跑 8 次全绿。
#
# ## 用法
#
#     bash .ai/tools/typecheck_kotlin.sh <文件.kt> [更多文件...]
#     VAULTIX_RUN_TESTS=0 bash .ai/tools/typecheck_kotlin.sh <文件>   # 只编译不跑
#
# `XxxTest.kt` 会**自动按包路径带上被测类**（`ScreenSecurityGuardTest` →
# `src/main/java/…/ScreenSecurityGuard.kt`）并自动运行该测试类。
# 首次运行下载约 160MB 依赖到 ~/.cache/vaultix-typecheck/，之后走缓存。
#
# ## 自身也做过变异测试（2026-10-03）
#
# "能红的检查"和"永远绿的检查"长得一模一样。此脚本在交付前用**已知坏**变体
# 验证过——它们必须红，且报错与 CI 逐字一致：
#
#   - `registerActivityLifecycleCallbacks(captor)`（漏 `capture()`）
#     → `argument type mismatch: actual type is 'CapturingSlot<…>'`；
#   - 多写 `import io.mockk.capture` → `unresolved reference 'capture'`；
#   - `verify(exactlyCount = 3)` → `no parameter with name 'exactlyCount' found`；
#   - 回退到 `runTest`（**运行期**坏，编译完全正常）→ 连跑 5 次有 3 次红。
#
# 好版本（三个编译错误已修 + `runBlocking`）：20 个 class、6 条用例连跑 8 次全绿。

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CACHE="${VAULTIX_TYPECHECK_CACHE:-$HOME/.cache/vaultix-typecheck}"
LIBS="$CACHE/libs"
OUT="$CACHE/out"

M2="${MAVEN_MIRROR:-https://repo1.maven.org/maven2}"

# 清单按"gav|缓存文件名"成对给出。文件名必须与 Maven 的实际产物名一致，
# 所以显式写出而不是从 gav 推导 —— 推导会在 `-jvm` 这类 classifier 上出错。
DEPS=(
  "io.mockk:mockk:1.14.11|mockk-1.14.11.jar"
  "io.mockk:mockk-dsl-jvm:1.14.11|mockk-dsl-jvm-1.14.11.jar"
  "io.mockk:mockk-agent-jvm:1.14.11|mockk-agent-jvm-1.14.11.jar"
  "io.mockk:mockk-agent-api-jvm:1.14.11|mockk-agent-api-jvm-1.14.11.jar"
  "io.mockk:mockk-core-jvm:1.14.11|mockk-core-jvm-1.14.11.jar"
  "io.mockk:mockk-jvm:1.14.11|mockk-jvm-1.14.11.jar"
  "org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.11.0|kotlinx-coroutines-core-jvm-1.11.0.jar"
  "org.jetbrains.kotlinx:kotlinx-coroutines-test-jvm:1.11.0|kotlinx-coroutines-test-jvm-1.11.0.jar"
  "junit:junit:4.13.2|junit-4.13.2.jar"
  "org.hamcrest:hamcrest-core:1.3|hamcrest-core-1.3.jar"
  "org.jetbrains.kotlin:kotlin-stdlib:2.1.20|kotlin-stdlib-2.1.20.jar"
  "org.jetbrains:annotations:26.0.2|annotations-26.0.2.jar"
  "javax.inject:javax.inject:1|javax.inject-1.jar"
  # ↓ 运行测试才需要（编译不需要）—— mockk 的字节码增强靠byte-buddy + objenesis。
  #   漏掉它们的症状很有迷惑性：`ExceptionInInitializerError` +
  #   `Could not initialize class io.mockk.impl.JvmMockKGateway`，看起来像 mockk 坏了。
  "org.jetbrains.kotlin:kotlin-reflect:2.1.20|kotlin-reflect-2.1.20.jar"
  "net.bytebuddy:byte-buddy:1.12.19|byte-buddy-1.12.19.jar"
  "net.bytebuddy:byte-buddy-agent:1.12.19|byte-buddy-agent-1.12.19.jar"
  "org.objenesis:objenesis:3.3|objenesis-3.3.jar"
)

# group:artifact:version -> group/artifact/version/artifact-version.jar
maven_path() {
  local gav="$1" path="${1//:/\/}" artifact="${1##*:}"
  artifact="${artifact%:*}"
  echo "${path}/${artifact}-${1##*:}.jar"
}

fetch() { # gav|filename -> path
  local gav="${1%%|*}" name="${1##*|}" f="$LIBS/${1##*|}"
  [ -s "$f" ] && { echo "$f"; return; }
  echo "  ↓ 下载 $gav" >&2
  curl -sSfL --retry 3 -o "$f" "$M2/$(maven_path "$gav")" \
    || { echo "下载失败: $gav" >&2; return 1; }
  echo "$f"
}

mkdir -p "$LIBS" "$OUT"

# ---- 1. 依赖 ----
CP=""
for gav in "${DEPS[@]}"; do
  f="$(fetch "$gav")" || exit 1
  CP="$CP:$f"
done

# android-all：提供 android.* 的真实签名（API 14 版的android.jar 缺
# `onActivityPreCreated`（API 29），用它会报**假**失败——那比不检查更坏）。
ANDROID_ALL="$LIBS/android-all-14-robolectric-10818077.jar"
if [ ! -s "$ANDROID_ALL" ]; then
  echo "  ↓ 下载 org.robolectric:android-all:14-robolectric-10818077（约 130MB，仅首次）" >&2
  curl -sSfL --retry 3 -o "$ANDROID_ALL" \
    "$M2/org/robolectric/android-all/14-robolectric-10818077/android-all-14-robolectric-10818077.jar"
fi
CP="$CP:$ANDROID_ALL"
CP="${CP#:}"

# ---- 2. Kotlin 编译器（embeddable jar，自带 CLI；沙箱无sdkman/kotlinc）----
KGP="$HOME/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin"
COMPILER="$(find "$KGP" -name 'kotlin-compiler-embeddable-*.jar' 2>/dev/null | sort | tail -1)"
[ -n "$COMPILER" ] || { echo "找不到 kotlin-compiler-embeddable，请先跑一次 gradle" >&2; exit 2; }
COMPILER_VER="${COMPILER##*kotlin-compiler-embeddable-}"
COMPILER_VER="${COMPILER_VER%.jar}"

KCP="$COMPILER"
for pat in "kotlin-stdlib-${COMPILER_VER}.jar" "kotlin-script-runtime-"*.jar \
           "kotlin-daemon-embeddable-${COMPILER_VER}.jar"; do
  f="$(find "$KGP" -name "$pat" 2>/dev/null | sort | tail -1)"
  [ -n "$f" ] && KCP="$KCP:$f"
done
# embeddable 编译器的运行期依赖（少任何一个都会在启动时NoClassDefFoundError）：
#   trove4j      —— 本地虚拟文件路径计算
#   coroutines   —— 编译器内部用协程
#   annotations  —— 字节码生成阶段读@NotNull
TROVE="$(find "$HOME/.gradle/caches/modules-2/files-2.1/org.jetbrains.intellij.deps/trove4j" \
          -name 'trove4j-*.jar' 2>/dev/null | sort | tail -1)"
KCP="$KCP:$TROVE:$LIBS/kotlinx-coroutines-core-jvm-1.11.0.jar:$LIBS/annotations-26.0.2.jar"

# ---- 3. 桩：被检查文件引用的项目源码里那些**太重**的部分 ----
# 只做类型检查 ⇒ 桩只需**签名**一致，实现可以空着。
STUBS="$CACHE/stubs"
mkdir -p "$STUBS"
cat > "$STUBS/prefs.kt" <<'EOF'
package io.vaultix.datastore

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** 类型检查桩：真实实现是 DataStore 派生流（`safeData.map { … }`）。 */
class VaultixPreferences(private val value: Boolean = true) {
    val screenSecurity: Flow<Boolean> = flowOf(value)
}
EOF
cat > "$STUBS/logging.kt" <<'EOF'
package io.vaultix.common.logging

/** 类型检查桩：真实实现见 core/common 的 VaultixLog（带限频与 try/catch）。 */
object VaultixLog {
    fun d(tag: String, message: () -> String) { /* 桩 */ }
}
EOF

# ---- 4. 被检查文件 + 它们依赖的项目源码 ----
SOURCES=("$STUBS/prefs.kt" "$STUBS/logging.kt")
for f in "$@"; do SOURCES+=("$f"); done

# 测试文件引用的主源码（`ScreenSecurityGuard` 等）也要一起编，否则全是未解析引用。
# 做法：从测试文件路径反推**包目录**（`src/test/java/a/b/C.kt` → `a/b`），
# 再按包名去`src/main/java` 下找同名主源码。
# ⚠️ 别把包路径写死成 `io/vaultix/vaultix/security` 那样——换模块就静默失效，
#   而"静默失效"正是这个脚本要消灭的东西。
MISSING_MAIN=0
LAST_GUARD=""
for f in "$@"; do
  base="$(basename "$f" .kt)"
  case "$f" in
    */src/test/java/*)
      module="${f%%/src/test/*}"
      pkg="${f#*/src/test/java/}"; pkg="${pkg%/*}"
      guard="${base%Test}"          # ScreenSecurityGuardTest -> ScreenSecurityGuard
      if [ "$guard" = "$base" ]; then continue; fi   # 不是 `XxxTest` 命名，跳过
      main="$module/src/main/java/$pkg/$guard.kt"
      ;;
    *)
      # 不在源码树里（如 /tmp 下的临时文件）：无从推导被测类。
      # 不静默放过 —— 见下面「假绿闸门」的解释。
      guard="${base%Test}"
      [ "$guard" = "$base" ] && continue
      MISSING_MAIN=1
      echo "  ✗ $f 不在 src/test/java/ 树内，推导不出被测类 $guard" >&2
      continue
      ;;
  esac
  if [ -f "$main" ]; then
    SOURCES+=("$main")
    LAST_GUARD="$guard"
    echo "  + 主源码 $guard.kt" >&2
  else
    echo "  ✗ 找不到被测类 $main" >&2
    MISSING_MAIN=1
  fi
done

rm -rf "$OUT"
echo "▸ 检查 $# 个文件（含 ${#SOURCES[@]} 个源文件）" >&2
# 输出留到判定之后再打印：要先知道有没有 unresolved，否则会漏判。
LOG="$(java -cp "$KCP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -nowarn -classpath "$CP" -d "$OUT" "${SOURCES[@]}" 2>&1 || true)"
echo "$LOG" | grep -vE '^(warning|info):' || true

CLASSES="$(find "$OUT" -name '*.class' 2>/dev/null | wc -l)"

# ⚠️ 关键闸门（假绿防护）：被测类没编进来时，`callbacks` 这类符号是**未解析**的，
#    于是对它的调用**参数个数与类型都不检查** ——
#    实测：拿不到主源码时，`onActivityStarted(a, null)` 这种错传参数**不报错**。
#    也就是说"检查通过"很可能只是因为**根本没检查到**。
#    ⇒ 这里硬失败，否则脚本就是个只会说漂亮话的工具。
if [ -n "$LAST_GUARD" ] && echo "$LOG" | grep -q "unresolved reference '$LAST_GUARD'"; then
  echo "❌ 被测类 $LAST_GUARD 未解析 ⇒ 对它的调用点（参数个数/类型）**根本没被检查**。" >&2
  echo "   这是**假绿**：请在正确的源码树下运行，或显式传入被测源文件。" >&2
  exit 1
fi
if [ "$MISSING_MAIN" = 1 ]; then
  echo "❌ 有被测类没找到。请把文件放在 src/test/java/<包路径>/ 下（包名要对应），" >&2
  echo "   或显式把被测源文件作为第二个参数传进来。" >&2
  exit 1
fi

if [ "$CLASSES" -eq 0 ]; then
  echo "❌ 没产出任何 class —— 编译没过（看上面的 error）" >&2
  exit 1
fi
echo "✅ 编译通过，产出 $CLASSES 个 class" >&2

# ---- 5. 真跑一遍（默认开启；VAULTIX_RUN_TESTS=0 可跳过）----
# 为什么要这一步：2026-10-03 CI run `37132804360` 里，本文件**编译 0 错误**，
# 却有一条用例红在 `IllegalStateException` —— 根因是`runTest` 的**虚拟时间**把
# `withTimeout(5000)` 直接跳到5 秒，于是"等真实线程"变成"立刻超时"。
# 这种错**只有运行才看得见**，编译永远查不到。
# 而等 CI 复核一轮要好几分钟，且卡片绿还会骗人（#145.4）。
[ "${VAULTIX_RUN_TESTS:-1}" = "0" ] && {
  echo "💡 已跳过运行（VAULTIX_RUN_TESTS=0）。只编译查不出虚拟时间 / 竞态类问题。" >&2
  exit 0
}

# 跑测试需要 mockk 的运行期依赖（编译不需要，所以上面没拉）：
RUNCP="$CP:$LIBS/kotlin-reflect-2.1.20.jar:$LIBS/byte-buddy-1.12.19.jar:$LIBS/byte-buddy-agent-1.12.19.jar:$LIBS/objenesis-3.3.jar"
CLASSES_TO_RUN=()
for f in "$@"; do
  base="$(basename "$f" .kt)"
  case "$base" in *Test) ;; *) continue ;; esac   # 只跑 `XxxTest`
  # 从 `…/src/test/java/<包路径>/XxxTest.kt` 反推**全限定类名**：
  # 去掉 `.kt` 后缀，再把目录分隔符换成 `.`。（注意别再 `%/*` 去掉文件名。）
  rel="${f#*/src/test/java/}"
  CLASSES_TO_RUN+=("$(echo "${rel%.kt}" | tr '/' '.')")
done
if [ "${#CLASSES_TO_RUN[@]}" -eq 0 ]; then
  echo "（没有 XxxTest 命名的文件，跳过运行）" >&2; exit 0
fi
echo "▸ 运行 ${CLASSES_TO_RUN[*]}" >&2
set +e
RUN_LOG="$(java -cp "$OUT:$RUNCP" org.junit.runner.JUnitCore "${CLASSES_TO_RUN[@]}" 2>&1)"
RUN_RC=$?
set -e
echo "$RUN_LOG" | tail -25
if [ "$RUN_RC" -eq 0 ]; then
  echo "✅ 测试通过" >&2
else
  echo "❌ 测试**失败** —— 编译是绿的，问题在运行期（看上面堆栈）" >&2
  exit 1
fi
