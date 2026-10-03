#!/usr/bin/env bash
# 本地 Kotlin 类型检查（2026-10-03 新增，见 issues/01 #145.4）
#
# ## 它补的是哪个洞
#
# 本仓库的沙箱环境**没有 Android SDK**，所以新写的 Kotlin 只能靠 CI 编译。
# 而 CI 有两个特性让"靠 CI 兜底"这条路比看上去贵得多：
#
#   1. `continue-on-error: true` 使 job 卡片绿/红都不可信（#145）；
#   2. 编译失败藏在日志里，必须拉日志数 `(^|Z )e: ` 才知道（#145.1）。
#
# ⇒ 代价是：**每写错一个字符，就要赔一轮 CI（提交 + 等 + 拉日志 + 数）**。
# 2026-10-03 为此栽了两次（`capture` 未用 `capture()` 包裹、多写了一行
# `import io.mockk.capture`），两次都是"本地无SDK + 卡片是绿的"。
#
# 本脚本用**另一条路**补上：沙箱虽然没有 SDK，但**有Maven 网络**。
# 而"类型检查"这件事**不需要 SDK**——它只需要被引用类的**签名**。
# 于是：借`org.robolectric:android-all` 提供 `android.*` 的真实字节码，
# 从 Maven 拉mockk / coroutines / junit，直接调 Kotlin 编译器。
#
# ## 能查什么、不能查什么
#
# | 能查 | 不能查 |
# |---|---|
# | 类型不匹配、未解析引用、可见性、`import` 写错 | Android 资源（`R` 类）、`BuildConfig` |
# | 泛型推断、lambda 签名、mockk 语法 | Compose 编译插件（要 AGP） |
# | `mockk()` 参数形态（CI 上踩过的坑都在这一列） | detekt 规则、编码检查 |
# | 项目自身类型错误（`XxxTest` 会自动带上被测类） | **依赖 Android SDK 的空注解**（见下） |
# | | 单测的**运行时**行为（本脚本只编译，不执行） |
#
# ⚠️ **已知盲区（实测确认，别拿本地绿灯当证据）**：
#   `android.*` 用的是 robolectric 的 `android-all.jar`，其字节码**没有 `@NonNull` 注解**
#   ⇒ Kotlin 把这些参数当**平台类型**，于是 `onActivitySaveInstanceState(a, null)` 这种
#   在真实 android.jar 下会编译不过的写法，**本地检查会放行**。
#   凡是"因为 Kotlin 空安全而不能这么写"的问题，**只能靠 CI 验**。
#
# ⇒ 它**不能替代** CI，只能把"一字符之差赔一轮 CI"降成"一秒"。绿灯之后**仍要**跑 CI。
#
# ## 用法
#
#     bash .ai/tools/typecheck_kotlin.sh <文件.kt> [更多文件...]
#
# 只检查给定文件（连同它们在仓库里引用的项目源码一起编）。
# 首次运行要下载约 160MB 依赖到 ~/.cache/vaultix-typecheck/，之后走缓存。
#
# ## 自身也做过变异测试（2026-10-03）
#
# 一个"能红的检查"和一个"永远绿的检查"长得一模一样。此脚本在交付前用两个
# **已知坏**变体验证过——它们必须红，且报错信息与 CI 逐字一致：
#
#   - `registerActivityLifecycleCallbacks(captor)`（漏了 `capture()`）
#     → `argument type mismatch: actual type is 'CapturingSlot<…>'`；
#   - 多写 `import io.mockk.capture`
#     → `unresolved reference 'capture'`。
#
# 好版本（两个错误都已修）产出 0 error、18 个 class 文件。
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
echo "⚠️  这只覆盖类型/mockk 语法。资源、detekt、编码、单测运行仍需 CI。" >&2
