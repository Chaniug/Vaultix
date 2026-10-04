#!/usr/bin/env bash
# 本地 Kotlin **真编译 + 真运行**（2026-10-03 新增，见 issues/01 #145.4 #145.5#145.7）
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
#   · Maven / Google Maven 拉mockk / coroutines / junit / byte-buddy / androidx；
#   · Gradle 缓存里的 `kotlin-compiler-embeddable` 编译；
#   · `JUnitCore` 真跑。
#
# ## 为什么不干脆装 Android SDK
#
# 装 SDK 要下的东西（build-tools / platform-tools / AGP 依赖树）远大于
# 「一个 jar 提供签名」—— 而**类型检查与这些单测的执行根本不需要那套**。
# 实测：130MB `android-all` + 依赖 ≈ 200MB，本地能编译**且能跑**。
#
# ## 被检查文件怎么凑齐（这里踩过两次坑，别退回旧写法）
#
# 朴素做法是"按测试文件名去掉 `Test` 后缀猜被测类"。**这是错的**：
#
#   实测反例：`AesCbcHmacTest` 测的是 `VaultixCrypto` / `SymmetricCryptoKey` /
#   `ParsedCipherString`，三个类**分属三个文件**，没有一个叫 `AesCbcHmac.kt`。
#   猜错的后果不是"少编一个文件"，而是**满屏 `unresolved reference`**——
#   真正的原因被几千行无关报错埋掉了。
#
# 第二个朴素做法是"整个模块的 src/main/java 一起编"。**对 core/、data/ 可行，
# 对 app/ 不行**（app 有 173 个主源码文件，大量 Compose，没有 Compose 编译器插件）。
#
# ⇒ 本脚本用第三种：**按包索引求项目内符号的传递闭包**。
#   从被检查文件出发，反复做两件事：
#     ① `import io.vaultix.x.y.ClassName` → 解析成`src/main/java/x/y/ClassName.kt`；
#        找不到同名文件就在该包目录下按声明（class/object/interface/typealias）grep；
#     ② **同包引用没有 import** ⇒ 整个包目录一起纳入。
#   只跟进 `io.vaultix.*`，其余（`kotlinx.`/`androidx.`/`dagger.`…）交给依赖清单。
#   外部依赖解析不到 ⇒ **立刻硬失败并报出是哪个符号**，而不是让它变成 unresolved。
#
# ⚠️ ② 不是"锦上添花"，是**必需**：实测 `AesCbcHmacTest` 与被测的 `VaultixCrypto`
#   同为 `io.vaultix.crypto` 包，测试文件里**一个 `io.vaultix` import 都没有**
#   （只 import 了 `javax.crypto`）。只按 import 走⇒ 闭包为空 ⇒ 满屏 unresolved。
#   这也是"按包索引"而不是"按 import 逐个找文件"的直接原因。
#
# ## 批量模式为什么按**包**分组，而不是整模块一次编
#
# app 的 35 个测试散在 16 个包，其中 `ui/*` 那几包需要 Compose 编译器插件
#（本工具没有，见下）。整模块一次编的话，**一个包编不过 ⇒ 35 个测试全部落空**，
# 连纯逻辑的 `autofill/engine`、`util` 也一起陪葬。
# ⇒ 分组后：能查的查出来，不能查的**如实单独点名**（不静默吞掉）。

# ## 能查什么、不能查什么
#
# | 能查 | 不能查 |
# |---|---|
# | 类型不匹配、未解析引用、可见性、`import` 写错 | Android 资源（`R` 类）、`BuildConfig` |
# | 泛型推断、lambda 签名、mockk 语法 | Compose 编译（要 AGP 的 Compose 插件） |
# | `mockk()` 参数形态（CI 踩过的坑都在这列） | KSP/Hilt **代码生成**（只做类型检查，不生成实现） |
# | 项目自身类型错误（自动带齐传递闭包） | detekt 规则、编码检查 |
# | **运行期行为**：竞态、flaky、虚拟时间陷阱 | **依赖 Android SDK 空注解**的写法（见下） |
#
# ⚠️ **已知盲区（实测确认，别拿本地绿灯当证据）**：
#   `android-all.jar` 字节码**没有 `@NonNull`** ⇒ Kotlin 把这些参数当**平台类型**
#   ⇒ `onActivitySaveInstanceState(a, null)` 这种在真实 android.jar 下编译不过的
#   写法，**本地检查会放行**。凡"因 Kotlin 空安全而不能这么写"的，只能靠 CI 验。
# ⚠️ 第二个盲区：KSP 不跑 ⇒ `Hilt_*` / `*_Factory` 等生成物不存在。
#   本工具只做**类型检查 + 运行已有单测**，不替代 `./gradlew testFullDebugUnitTest`。
#
# ⇒ 它**不能替代** CI，但把反馈从**一轮 CI** 压到 **20 秒**。
#
# ## 它抓到的最典型一例（CI run `37132804360`，2026-10-03）
#
# 那次 CI **编译 0 错误**，但 6 条用例里 1 条红在 `IllegalStateException`——
# 根因是`runTest` 的**虚拟时间**把 `withTimeout(5000)` 直接跳满，于是"等真实线程"
# 变成"立刻超时"。**这种错编译永远查不出来，只有真跑才看得见。**
# ⚠️ 它在本地的表现是**随机的**（连跑 5 次：2绿 3 红）⇒ 极易被误判成
# "偶发 flaky，算了"。换 `runBlocking` 后连跑 8 次全绿。
#
# ## 用法
#
# 单文件/单测试（最常用，20 秒内出结果）：
#     bash .ai/tools/typecheck_kotlin.sh <文件.kt> [更多文件...]
#     VAULTIX_RUN_TESTS=0 bash .ai/tools/typecheck_kotlin.sh <文件># 只编译不跑
#
# 批量巡检整个仓库（按模块分组，逐模块编译 + 真跑，最后汇总）：
#     bash .ai/tools/typecheck_kotlin.sh --all
#     bash .ai/tools/typecheck_kotlin.sh --all core/crypto data/kdbx# 只跑指定模块
#     VAULTIX_RUN_TESTS=0 bash .ai/tools/typecheck_kotlin.sh --all           # 只编译
#
# 首次运行下载约 200MB 依赖到 ~/.cache/vaultix-typecheck/，之后走缓存。
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
#   - 回退到 `runTest`（**运行期**坏，编译完全正常）→ 连跑 5 次有 3 次红；
#   - 把被测源文件挪出源码树 → 脚本报"闭包未覆盖"并硬失败，不报 ✅。
#
# 好版本（三个编译错误已修 + `runBlocking`）：20 个 class、6 条用例连跑 8 次全绿。
#
# ### 批量模式自身也做过变异测试（2026-10-04）
#
# 把"扫全仓"这件事做出来时，工具自己先病倒了 —— 记录在此，因为**每一条都是
# 「看起来在跑，其实什么都没查」那一类**：
#
#   - 分组值用 `mapfile -t` 拆（实为**一行空格分隔**）⇒ 整行变成 1 个元素
#     ⇒ 28 个组齐刷刷报"源文件不存在"。**由脚本自己的"源文件存在"闸门抓出**。
#   - 关联数组命名 `GROUPS` ⇒ bash 保留变量（当前用户所属组 ID 的数组）
#     ⇒ `cannot convert indexed to associative array`。
#   - `"$m__$(...)"` ⇒ bash 把 `$m__` 当变量名 ⇒ `unbound variable` 直接退出。
#   - 模块发现写 `find -maxdepth 2` ⇒ 只捞到 `app` 一个模块，
#     **而且有汇总有输出**，不会报错 —— 纯静默漏检（97 个测试文件里只看了 35 个）。
#   - 依赖缓存名用 `${gav##*:}` ⇒ 三个同版本 androidx artifact
#     （`datastore-preferences` / `datastore-core` / `datastore-preferences-android`
#     都是 1.2.1）**撞成同一个 `1.2.1.jar`**，后两个静默命中前一个的缓存。
#   - room-runtime 2.8.4 的 `.aar` 是**空壳**（4.5KB，只有 LICENSE，没有 classes.jar，
#     因为它是 KMP 多平台发布）⇒ 字节码在 `<artifact>-jvm` 变体里。
#   - truth 漏了传递依赖 guava ⇒ **编译完全正常**，只有运行期炸
#     `NoClassDefFoundError: com/google/common/collect/ImmutableList`。
#   - `curl -I`（HEAD）探测 Maven 坐标 ⇒ Central 对 HEAD 返回 404，
#     我据此**误判"dagger 2.60.1 不存在"**并写进了 #145.5。用 GET 复核后确认存在。
#     ⇒ 一条**已发布文档里的错账**，本轮已订正。
#
# 好版本（--all 扫 8 个模块）：core/crypto 191、core/common 146、core/model 5 条全绿。

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CACHE="${VAULTIX_TYPECHECK_CACHE:-$HOME/.cache/vaultix-typecheck}"
LIBS="$CACHE/libs"
OUT="$CACHE/out"

M2="${MAVEN_MIRROR:-https://repo1.maven.org/maven2}"
# androidx 只在 Google Maven（Central 上 404）。两个都试，谁有算谁。
GMAVEN="${GOOGLE_MAVEN:-https://dl.google.com/dl/android/maven2}"

BATCH=0
MODULE_ARGS=()
if [ "${1:-}" = "--all" ]; then
  BATCH=1; shift
  MODULE_ARGS=("$@")
fi

# ---- 依赖清单 ----
# 格式 "gav|kind"，kind 为空表示普通 jar，为 `aar` 则下载后解出 classes.jar。
# ⚠️ 加新依赖前先核对坐标：`curl -s -o /dev/null -w '%{http_code}' <M2>/<推导路径>`。
#   **别用 `curl -I`（HEAD）探测** —— Central 对 HEAD 返回 404，
#   我据此误判"dagger 2.60.1 在 Central 不存在"，实际存在（已在本轮订正）。
#   也不要照抄 libs.versions.toml 就算完：aar/jar 的产物类型要看仓库实际发布的是什么。
DEPS=(
  "io.mockk:mockk:1.14.11"
  "io.mockk:mockk-dsl-jvm:1.14.11"
  "io.mockk:mockk-agent-jvm:1.14.11"
  "io.mockk:mockk-agent-api-jvm:1.14.11"
  "io.mockk:mockk-core-jvm:1.14.11"
  "io.mockk:mockk-jvm:1.14.11"
  "org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.11.0"
  "org.jetbrains.kotlinx:kotlinx-coroutines-test-jvm:1.11.0"
  "org.jetbrains.kotlinx:kotlinx-serialization-core-jvm:1.8.1"
  "org.jetbrains.kotlinx:kotlinx-datetime-jvm:0.6.2"
  "org.jetbrains.kotlinx:kotlinx-serialization-json-jvm:1.8.1"
  "junit:junit:4.13.2"
  "org.hamcrest:hamcrest-core:1.3"
  "org.jetbrains.kotlin:kotlin-stdlib:2.1.20"
  "org.jetbrains:annotations:26.0.2"
  "javax.inject:javax.inject:1"
  "com.google.truth:truth:1.4.4"
  # ⚠️ truth 的传递依赖 guava **必须显式列出**（Gradle 会自动带上，本脚本不会）。
  #   漏掉的症状：编译**完全正常**，只有运行期炸，而且报错与真因八竿子打不着——
  #   `NoClassDefFoundError: com/google/common/collect/ImmutableList`
  #   出现在 `Truth.<clinit>` 里，看起来像"truth 版本不对"。
  #   ⚠️ 版本号后缀是 `-jre` 不是 `-jvm`（`guava-33.4.0-jvm.jar` 是 404）。
  "com.google.guava:guava:33.7.2-jre"
  "org.checkerframework:checker-qual:3.43.0"
  "app.cash.turbine:turbine-jvm:1.2.0"
  "com.squareup.okio:okio-jvm:3.9.0"
  # ↓ 运行测试才需要（编译不需要）—— mockk 的字节码增强靠 byte-buddy + objenesis。
  #   漏掉它们的症状很有迷惑性：`ExceptionInInitializerError` +
  #   `Could not initialize class io.mockk.impl.JvmMockKGateway`，看起来像 mockk 坏了。
  "org.jetbrains.kotlin:kotlin-reflect:2.1.20"
  "net.bytebuddy:byte-buddy:1.12.19"
  "net.bytebuddy:byte-buddy-agent:1.12.19"
  "org.objenesis:objenesis:3.3"
  # ↓ 以下是**主源码**才需要的（闭包把主源码一起编进来后必然命中）
  "org.bouncycastle:bcprov-jdk18on:1.85.2"
  "com.lambdapioneer.argon2kt:argon2kt:1.6.0|aar"
  "com.google.dagger:dagger:2.60.1"
  # ⚠️ `@InstallIn` / `SingletonComponent` 在**hilt-core**，不在 hilt-android。
  #   只放 hilt-android 会得到 `unresolved reference 'InstallIn'` ——
  #   报错指向 `di/CryptoModule.kt`，真实原因（少一个 jar）完全看不出来。
  "com.google.dagger:hilt-core:2.60.1"
  # ↓ kotlinx.serialization 的**编译器插件**（不是运行时库）。
  #   ⚠️ 版本必须与 kotlin-compiler-embeddable **完全一致**（2.1.20）——
  #   插件按编译器 ABI 加载，版本错了不是"报个错"，而是**静默不生效**：
  #   编译全绿，跑到`Json.decodeFromString<PreLoginResponse>()` 才炸
  #   `Serializer for class 'PreLoginResponse' is not found`。
  #   症状与"漏了依赖"几乎一样，但真因是插件没加载 ⇒ 排查时极易走错路。
  "org.jetbrains.kotlin:kotlin-serialization-compiler-plugin-embeddable:2.1.20"
  "com.google.dagger:hilt-android:2.60.1|aar"
  "app.keemobile:kotpass:0.13.0"
  "com.squareup.retrofit2:retrofit:2.11.0"
  "com.jakewharton.retrofit:retrofit2-kotlinx-serialization-converter:1.0.0"
  "com.squareup.okhttp3:okhttp:4.12.0"
  "com.squareup.okhttp3:logging-interceptor:4.12.0"
  "androidx.datastore:datastore-preferences-core-jvm:1.2.1"
  "androidx.datastore:datastore-core-jvm:1.2.1"
  "androidx.datastore:datastore-preferences-android:1.2.1|aar"
  "androidx.room:room-runtime-jvm:2.8.4"
  "androidx.room:room-common-jvm:2.8.4"
  "androidx.room:room-ktx:2.8.4|aar"
  # androidx.lifecycle：不是 Compose，普通 jar/aar 就能提供 ⇒ 能查。
  #   少了它 `AutoLockController` 报 `'onStart' overrides nothing`+
  #   `unresolved reference 'LifecycleOwner'`，看着像代码坏了，其实只是缺依赖。
  "androidx.lifecycle:lifecycle-common-jvm:2.11.0"
  "androidx.lifecycle:lifecycle-runtime-android:2.11.0|aar"
  "androidx.lifecycle:lifecycle-viewmodel-android:2.11.0|aar"
  "androidx.lifecycle:lifecycle-viewmodel-ktx:2.11.0|aar"
  "androidx.lifecycle:lifecycle-process:2.11.0|aar"
)

# group:artifact:version -> group/artifact/version/artifact-version.<ext>
# ⚠️ 三段都要对：group 用 `/` 分隔、**目录名是 artifact**、文件名是 artifact-version。
#   曾经写错成 `com.google.truth/truth/1.4.4/1.4.4-1.4.4.jar`（目录与文件名都用了版本）——
#   之前没暴露，纯粹因为那些依赖早已在缓存里；**一加新依赖立刻 404**。
maven_path() { # gav ext
  local gav="$1" ext="$2"
  local group="${gav%%:*}"     # com.google.truth
  local rest="${gav#*:}"       # truth:1.4.4
  local artifact="${rest%%:*}" # truth
  local version="${rest##*:}"  # 1.4.4
  echo "${group//.//}/${artifact}/${version}/${artifact}-${version}.${ext}"
}

fetch_jar() { # gav -> path
  local gav="$1" name jar
  # ⚠️ 缓存名必须带**artifact**，不能只用版本号。
  #   实测踩过：`androidx.datastore:datastore-preferences:1.2.1`、
  #   `datastore-core:1.2.1`、`datastore-preferences-android:1.2.1` 三个
  #   artifact同版本，用 `${gav##*:}.jar` 会**撞成同一个 `1.2.1.jar`**，
  #   后两个直接命中前一个的缓存 ⇒ 静默拿到错的字节码。
  name="$(echo "$gav" | tr ':.' '--')"
  jar="$LIBS/$name.jar"
  [ -s "$jar" ] && { echo "$jar"; return 0; }
  echo "  ↓ 下载 $gav" >&2
  curl -sSfL --retry 3 --stderr /dev/null -o "$jar" "$M2/$(maven_path "$gav" jar)" && { echo "$jar"; return 0; }
  rm -f "$jar"
  # androidx 只在 Google Maven；kotpass 之类可能只在 Central。都试一遍。
  curl -sSfL --retry 3 --stderr /dev/null -o "$jar" "$GMAVEN/$(maven_path "$gav" jar)" && { echo "$jar"; return 0; }
  rm -f "$jar"
  # ⚠️ androidx 从 2.x 起不少artifact 是 **KMP 多平台发布**：主 artifact 的
  #   `.aar`/`.jar` 是**空壳**（room-runtime 2.8.4 的 aar 只有 4.5KB，里面
  #   只有 LICENSE，没有 classes.jar！），真正的字节码在 `<artifact>-jvm` 变体里。
  #   ⇒ 显式写 -jvm 坐标最省事，但为防漏，这里再兜一层。
  local gv="${gav%:*}"jvm
  curl -sSfL --retry 3 --stderr /dev/null -o "$jar" "$GMAVEN/$(maven_path "$gv" jar)" && { echo "$jar"; return 0; }
  rm -f "$jar"; return 1
}

fetch_aar() { # gav -> classes.jar 路径
  local gav="$1" name aar ext
  name="$(echo "$gav" | tr ':.' '--')"   # 同上：必须带 artifact
  aar="$LIBS/$name.aar"; ext="$LIBS/$name-classes.jar"
  [ -s "$ext" ] && { echo "$ext"; return 0; }
  if [ ! -s "$aar" ]; then
    echo "  ↓ 下载 $gav (aar)" >&2
    curl -sSfL --retry 3 --stderr /dev/null -o "$aar" "$M2/$(maven_path "$gav" aar)" \
      || curl -sSfL --retry 3 --stderr /dev/null -o "$aar" "$GMAVEN/$(maven_path "$gav" aar)" \
      || { rm -f "$aar"; echo "下载失败: $gav" >&2; return 1; }
  fi
  # aar 里除了 classes.jar 还有 res/、AndroidManifest.xml —— 字节码只在 classes.jar。
  # ⚠️ 顺带记一笔：同一个 artifact 同版本可能**同时发 jar 和 aar**
  #   （`androidx.datastore:datastore-core:1.2.1` 两个都有）。所以 fetch_jar
  #   先试 jar —— 命中就不会走到这里。这里只处理"只有 aar"的情况。
  unzip -p "$aar" classes.jar > "$ext" 2>/dev/null && [ -s "$ext" ] || {
    rm -f "$ext"; echo "aar 里没有 classes.jar: $gav" >&2; return 1; }
  echo "$ext"
}

fetch_dep() { # gav [|aar] -> path
  local spec="$1"
  case "$spec" in
    *"|aar") fetch_aar "${spec%%|*}" ;;
    *)       fetch_jar "$spec" ;;
  esac
}

mkdir -p "$LIBS" "$OUT"

CP=""
MISSING_DEPS=()
for spec in "${DEPS[@]}"; do
  # ⚠️ 缺一个依赖**不能直接 exit**：那会让"这个模块跑不了"变成"整个工具不能用"。
  #   正确做法是跳过它、记一笔，最后**如实报告哪些模块因此跑不了**——
  #   静默假装齐全，才会让"跑通了"变成另一种假绿。
  if f="$(fetch_dep "$spec")"; then
    CP="$CP:$f"
  else
    MISSING_DEPS+=("$spec")
  fi
done

# android-all：提供 android.* 的真实签名（API 14 版的 android.jar 缺
# `onActivityPreCreated`（API 29），用它会报**假**失败——那比不检查更坏）。
ANDROID_ALL="$LIBS/android-all-14-robolectric-10818077.jar"
if [ ! -s "$ANDROID_ALL" ]; then
  echo "  ↓ 下载 org.robolectric:android-all:14-robolectric-10818077（约 130MB，仅首次）" >&2
  curl -sSfL --retry 3 --stderr /dev/null -o "$ANDROID_ALL" \
    "$M2/org/robolectric/android-all/14-robolectric-10818077/android-all-14-robolectric-10818077.jar"
fi
CP="$CP:$ANDROID_ALL"
CP="${CP#:}"

# ---- Kotlin 编译器（embeddable jar，自带 CLI；沙箱无 sdkman/kotlinc）----
KGP="$HOME/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin"
COMPILER="$(find "$KGP" -name 'kotlin-compiler-embeddable-*.jar' 2>/dev/null | sort | tail -1)"
[ -n "$COMPILER" ] || { echo "找不到 kotlin-compiler-embeddable，请先跑一次 gradle" >&2; exit 2; }
COMPILER_VER="${COMPILER##*kotlin-compiler-embeddable-}"
COMPILER_VER="${COMPILER_VER%.jar}"

KCP="$COMPILER"
# ⚠️ 四个 pattern **必须整串加引号**：写成 'kotlin-script-runtime-"*.jar'（引号内一段 + 引号外 *.jar）
#    的话，bash 会把引号外的 * 拿去匹配**当前目录**的 jar（仓库根就有 gradle-wrapper.jar）
#    ⇒ find 静默搜不到、f 为空 ⇒ 该 jar 被跳过（2026-10-04 实测）
#    ⇒ 表现为编译期 NoClassDefFound 却查不出「哪个 jar 没进来」。
#
# ⚠️ 另外：这段注释里**不能出现反引号**，bash 即使在 # 注释里也会做命令替换，
#    会把反引号里的字符串当命令执行（实测炸出一屏 command not found）。
for pat in "kotlin-stdlib-${COMPILER_VER}.jar" "kotlin-script-runtime-${COMPILER_VER}.jar" \
           "kotlin-daemon-embeddable-${COMPILER_VER}.jar" "kotlin-reflect-${COMPILER_VER}.jar"; do
  f="$(find "$KGP" -name "$pat" 2>/dev/null | sort | tail -1)"
  [ -n "$f" ] && KCP="$KCP:$f"
done
# embeddable 编译器的运行期依赖（少任何一个都会在启动时 NoClassDefFoundError）：
#   trove4j      —— 本地虚拟文件路径计算（2026-10-04 实测本机 gradle 缓存里**没有** trove4j，
#                   故下面按「非空才拼」处理，别让它变成 -cp 里的空条目）
#   coroutines   —— 编译器内部用协程
#   annotations  —— 字节码生成阶段读 @NotNull
# ⚠️ 少了 kotlin-reflect 也不会在启动期报错，而是**跑到参数解析才炸**
#    （2026-10-04 实测：NoClassDefFoundError: kotlin/reflect/jvm/ReflectJvmMapping，
#     栈在 ArgumentUtilsKt.getArgumentAnnotation ⇒ 极具迷惑性，别只盯着 compiler/stdlib）。
TROVE="$(find "$HOME/.gradle/caches/modules-2/files-2.1/org.jetbrains.intellij.deps/trove4j" \
          -name 'trove4j-*.jar' 2>/dev/null | sort | tail -1)"
# ⚠️ trove4j 目录为空时 $TROVE 会是空串，直接拼会产生 `-cp a.jar::b.jar` 的**空条目**
#    （Java 把它解释成当前目录，且容易掩盖真正的依赖缺失）。故只在非空时才拼。
[ -n "$TROVE" ] && KCP="$KCP:$TROVE"
# ⚠️ 这里的两个 jar 必须用**缓存名**（点换横杠）——$LIBS 里的文件名是
#    `org-jetbrains-kotlinx-kotlinx-coroutines-core-jvm-1-11-0.jar`，
#    写成 gav 原名的 `kotlinx-coroutines-core-jvm-1.11.0.jar` 会静默找不到
#    ⇒ 编译器跑到启动后期才炸 ClassNotFoundException: kotlinx.coroutines.CoroutineScope
#    （2026-10-04 实测；同一个坑 @ 528 行序列化插件那里已经踩过一次）。
KCP="$KCP:$(ls "$LIBS"/*kotlinx-coroutines-core-jvm-*.jar 2>/dev/null | head -1)"
KCP="$KCP:$(ls "$LIBS"/*jetbrains-annotations-*.jar 2>/dev/null | head -1)"

# ---- 项目内符号解析 ----
cd "$REPO_ROOT"

# 建立「包路径 -> 该包下所有 .kt 文件」的索引（一次扫描，全仓共用）。
#
# ⚠️ 为什么要按**包**索引，而不是按 import 逐个找文件：
#   ① Kotlin 的**同包引用不需要 import** —— 实测 `AesCbcHmacTest` 与被测的
#      `VaultixCrypto` 同为 `io.vaultix.crypto`包，测试里**一个 io.vaultix import 都没有**
#      （只 import 了 javax.crypto）。按 import 走 ⇒ 闭包空 ⇒ 满屏 unresolved。
#      所以必须额外做「同包即同包」的映射：测试的包 -> main 的同名包。
#   ② 一个文件里可以有多个顶层类/函数，按文件名找会漏。
#
# 索引键：包路径（相对仓库根，无扩展名），值：该包下的文件（空格分隔）。
declare -A PKG_INDEX=()
declare -A PKG_TEST_INDEX=()
build_index() {
  local f pkg
  while IFS= read -r f; do
    # .../src/main/java/io/vaultix/crypto/X.kt -> io/vaultix/crypto
    pkg="${f#*src/main/java/}"; pkg="${pkg%/*}"
    PKG_INDEX["$pkg"]="${PKG_INDEX[$pkg]:-} $f"
  done < <(find . -path '*/src/main/java/*' -name '*.kt' 2>/dev/null)
  while IFS= read -r f; do
    pkg="${f#*src/test/java/}"; pkg="${pkg%/*}"
    PKG_TEST_INDEX["$pkg"]="${PKG_TEST_INDEX[$pkg]:-} $f"
  done < <(find . -path '*/src/test/java/*' -name '*.kt' 2>/dev/null)
}

# 一个包路径下，有没有声明了 `class/object/interface/typealias/fun/val <cls>` 的文件

# 声明行的公共匹配（class/object/interface/typealias/fun/val + 名字），下面两个函数共用。
#
# ⚠️ 2026-10-04 性能修复：这两个函数原本是 `for cand ... grep -qE "$cand"`，
#    即「未解析符号数 × 该符号所属包的文件数」次 grep —— 每次都是一次进程 fork。
#    data 模块实测要跑 **5 分钟以上**（domain 符号少所以看着还行）。
#    改成**一次** `grep -lE` 把整个包的候选文件都喂进去（grep 本就支持多文件参数），
#    命中数从 fork 数千次降到与符号数同阶。
DECL_PAT='^ *(internal |private |public |abstract |open |sealed |data |enum |value |expect |actual )*(class|object|interface|typealias|fun|val) +'

find_decl() { # pkg_path class_name -> "pkg_path<TAB>file"
  local pkg="$1" cls="$2" f
  [ -n "${PKG_INDEX[$pkg]:-}" ] || return 1
  if [ -f "$pkg/$cls.kt" ]; then printf '%s\t%s\n' "$pkg" "$pkg/$cls.kt"; return 0; fi
  f="$(grep -lE "$DECL_PAT$cls\b" ${PKG_INDEX[$pkg]} 2>/dev/null | head -1)"
  [ -n "$f" ] || return 1
  printf '%s\t%s\n' "$pkg" "$f"
}

# 找出「所有声明了 pkg 里这个类的文件」。同名类在多个模块里都可能出现
# （比如 core/model 与 data/* 各自有 DTO），全都要带上。
find_decls_all() { # pkg_path class_name -> 每行 "pkg<TAB>file"
  local pkg="$1" cls="$2" cand hit=0 out
  [ -n "${PKG_INDEX[$pkg]:-}" ] || return 1
  if [ -f "$pkg/$cls.kt" ]; then printf '%s\t%s\n' "$pkg" "$pkg/$cls.kt"; hit=1; fi
  out="$(grep -lE "$DECL_PAT$cls\b" ${PKG_INDEX[$pkg]} 2>/dev/null)"
  if [ -n "$out" ]; then
    while IFS= read -r cand; do
      [ "$cand" = "$pkg/$cls.kt" ] && continue
      printf '%s\t%s\n' "$pkg" "$cand"; hit=1
    done <<<"$out"
  fi
  return "$hit"
}

CLOSED=""            # 已纳入的文件（相对仓库根，newline 分隔）
UNRESOLVED=""       # 解析不到的项目内符号
in_closed() { grep -qxF "$1" <<<"$CLOSED"; }
add_closed() { in_closed "$1" || CLOSED="$CLOSED$1
"; }

# 从种子出发求闭包。种子自身的包/模块是起点。
# 输出：新增文件（不含种子）。
expand_closure() { # seed_files...
  local queue=("$@") qi=0
  local cur pkg relmod
  # 种子按包分组：同一个包的 main 文件要全部带上（解决「同包无import」）
  local -A seed_pkgs=()
  for cur in "$@"; do
    case "$cur" in
      *src/test/java/*) pkg="${cur#*src/test/java/}" ;;
      *src/main/java/*) pkg="${cur#*src/main/java/}" ;;
      *) continue ;;
    esac
    seed_pkgs["${pkg%/*}"]=1
    # 同包的测试文件之间也互相引用，一并带上
    for f in ${PKG_TEST_INDEX[${pkg%/*}]:-}; do in_closed "$f" || { add_closed "$f"; queue+=("$f"); }; done
  done
  for pkg in "${!seed_pkgs[@]}"; do
    for f in ${PKG_INDEX[$pkg]:-}; do in_closed "$f" || { add_closed "$f"; queue+=("$f"); }; done
  done

  while [ "$qi" -lt "${#queue[@]}" ]; do
    cur="${queue[$qi]}"; qi=$((qi+1))
    local fqcn p2 hits f
    while read -r fqcn; do
      [ -n "$fqcn" ] || continue
      p2="${fqcn//.//}"; p2="${p2%/*}"
      #⚠️ **全限定名**要去掉"类名 + 可能存在的嵌套类名"才轮到包路径。
      #   `a.b.parser.HintClassifier.SignalStrength` 去掉最后一段得到
      #   `a.b.parser.HintClassifier` —— 那不是包，索引里没有 ⇒ 会被误判成
      #   「符号解析不到」。⇒ 逐级回退直到**索引里真有的包**为止。
      #   这也是为什么 `unresolved reference 'parser'` 会出现：
      #   `parser` 是包名（io.vaultix.vaultix.autofill.parser），不是类名。
      while [ "${p2}" != "${p2%/*}" ] && [ -z "${PKG_INDEX[$p2]:-}" ]; do
        p2="${p2%/*}"
      done
      # 同包：测试引用同包main 类时没有 import，直接把该包 main 文件都带上
      if [ -n "${PKG_INDEX[$p2]:-}" ]; then
        for f in ${PKG_INDEX[$p2]}; do in_closed "$f" || { add_closed "$f"; queue+=("$f"); }; done
      fi
      # 显式 import：解析到具体声明文件
      hits="$(find_decls_all "$p2" "${fqcn##*.}" || true)"
      if [ -z "$hits" ]; then
        # 该包在索引里但类找不到 —— 多半是 Kotlin 的 import别名/重导出，
        # 保守起见整个包带上（宁可多编，不可漏编）。
        if [ -n "${PKG_INDEX[$p2]:-}" ]; then
          for f in ${PKG_INDEX[$p2]}; do in_closed "$f" || { add_closed "$f"; queue+=("$f"); }; done
        else
          UNRESOLVED="$UNRESOLVED $fqcn"
        fi
      else
        while IFS=$'\t' read -r _ f; do
          [ -n "$f" ] || continue
          f="${f#./}"
          in_closed "$f" || { add_closed "$f"; queue+=("$f"); }
        done <<<"$hits"
      fi
    done < <(
      {
        # import 的项目内符号（import 行不会被注释掉，直接取）
      grep -hoE '^import +io\.vaultix\.[A-Za-z0-9_.]+' "$cur" 2>/dev/null | sed -E 's/^import +//'
        # ⚠️ **全限定名**也算引用：`ParsedField` 里写的是
        #   `io.vaultix.vaultix.autofill.parser.HintClassifier.SignalStrength`
        #   而不是 import。只跟 import ⇒ 这个类型解析不到 ⇒
        #   报 unresolved reference 'parser'，而真因是"闭包没跟全限定名"。
        #
        # ⚠️⚠️ 扫全限定名前**必须先剥掉注释**，否则闭包会被**文档**污染：
        #   `ScreenSecurityGuard.kt` 的 KDoc 里写着「由 `VaultixApplication` 用 @Inject……」
        #   「FLAG_SECURE 只挂在 `MainActivity`」，而 MainActivity 通向 Compose。
        #   实测后果：`ScreenSecurityGuardTest` 一个文件把 **475 个**主源码
        #   （含整个 `ui/`）全拖进来，然后整组死在 `unresolved reference 'compose'`。
        #   ⇒ 「扫全文」与「扫代码」的区别：**注释里的名字不是依赖**。
        sed -e 's://.*::' "$cur" \
          | sed -e 's:^[[:space:]]*\*.*::' \
          | grep -hoE 'io\.vaultix\.[a-zA-Z0-9_]+(\.[a-zA-Z0-9_]+)*\.[A-Z][A-Za-z0-9_]*' || true
      } | sort -u
    )
  done
  grep -vxF -f <(printf '%s\n' "$@") <<<"$CLOSED" 2>/dev/null || true
}

# 索引只需建一次（97个测试文件、200+ 主源码文件，逐次重建会明显变慢）
BUILD_INDEX_DONE=0
ensure_index() {
  [ "$BUILD_INDEX_DONE" = 1 ] && return 0
  build_index
  BUILD_INDEX_DONE=1
}

# ---- 编译 ----
compile_and_report() { # outdir sources...
  local outdir="$1"; shift
  local sources=("$@")
  rm -rf "$outdir"; mkdir -p "$outdir"
  local log
  # -Xplugin 挂 kotlinx.serialization 编译器插件。
  # ⚠️ 找不到时**不能静默跳过** —— 那会让所有 @Serializable 类在运行期才炸，
  #   而报错信息（"Serializer not found"）完全看不出是插件缺失。
  local plugarg=()
  # ⚠️ 别按"gav 推导出的文件名"硬编码路径 —— 缓存名里的 `.` 会被换成 `-`
  #   （2.1.20 -> 2-1-20），写成 `${COMPILER_VER}` 就找不到。
  #   ⇒ 直接glob 匹配，唯一性由 artifactId 保证。
  local serplug
  # ⚠️ 插件版本**必须**与 COMPILER_VER 一致（2.4.10 编译器 + 2.1.20 插件 ⇒
  #   运行期 AbstractMethodError: SerializationComponentRegistrar does not define ...
  #   getPluginId() —— 报错指向插件自身，极易误判成业务代码问题，2026-10-04 实测）。
  #   ⇒ 先从 gradle 缓存按版本精确取，取不到才退回 $LIBS 里那个（可能过旧的）全局 jar。
  serplug="$(find "$KGP" -name "kotlin-serialization-compiler-plugin-embeddable-${COMPILER_VER}.jar" 2>/dev/null | sort | tail -1)"
  [ -s "$serplug" ] || serplug="$(ls "$LIBS"/*kotlin-serialization-compiler-plugin-embeddable-*.jar 2>/dev/null | head -1)"
  if [ -n "$serplug" ] && [ -s "$serplug" ]; then
    plugarg=(-Xplugin="$serplug")
  else
    echo "   ⚠️ 序列化编译器插件缺失（$COMPILER_VER）⇒ 带 @Serializable 的测试会在运行期误报失败" >&2
  fi
  # ⚠️ `-jvm-target` 必须显式给 **17**（与各模块 build.gradle.kts 的 jvmTarget 一致）。
  #   不给的话 kotlinc 默认 1.8，碰到 kotpass（字节码是 JVM 11）就报
  #   `cannot inline bytecode built with JVM target 11 into ... JVM target 1.8`
  #   —— 报错指向**业务代码里的 inline 函数**，真因是命令行少一个参数。
  log="$(java -cp "$KCP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -no-stdlib -nowarn -jvm-target 17 "${plugarg[@]}" -classpath "$CP" -d "$outdir" "${sources[@]}" 2>&1)"
  local errs
  errs="$(grep -cE '(^|Z )e: |error:' <<<"$log")"
  if [ "$errs" -gt 0 ]; then
    echo "$log" | grep -E '(^|Z )e: |error:' | head -20 >&2
    return 1
  fi
  CLASSES="$(find "$outdir" -name '*.class' 2>/dev/null | wc -l)"
  if [ "$CLASSES" -eq 0 ]; then
    echo "$log" | tail -20 >&2
    return 1
  fi
  return 0
}

# 跑一批测试类；参数：outdir 类名...
run_tests() { # outdir classes...
  local outdir="$1"; shift
  [ "$#" -eq 0 ] && return 0
  local runcp="$CP:$LIBS/kotlin-reflect-2.1.20.jar:$LIBS/byte-buddy-1.12.19.jar:$LIBS/byte-buddy-agent-1.12.19.jar:$LIBS/objenesis-3.3.jar"
  local log rc
  log="$(java -cp "$outdir:$runcp" org.junit.runner.JUnitCore "$@" 2>&1)"; rc=$?
  RUN_LOG="$log"
  # ⚠️ **区分"代码缺陷"与"沙箱限制"**—— 这两类混在一起会把工具变成谎报器。
  #   `android-all.jar` 里的 `android.os.SystemClock` 只是**签名桩**，
  #   方法体是空的（`throw new RuntimeException("Stub!")` 或直接 native 声明），
  #   于是任何**真的调它**的代码都会炸 `UnsatisfiedLinkError`。
  #   这不是被测代码有 bug，是沙箱没有 Android 运行时。
  #   ⇒ 识别它，并单独归类，避免"4 条红"被误读成"security 模块有问题"。
  if [ $rc -ne 0 ] && grep -q 'UnsatisfiedLinkError' <<<"$log"; then
    STUB_ONLY=1
  else
    STUB_ONLY=0
  fi
  return $rc
}

# ================= 批量模式 =================
if [ "$BATCH" = 1 ]; then
  ensure_index
  MODS=()
  if [ "${#MODULE_ARGS[@]}" -gt 0 ]; then
    MODS=("${MODULE_ARGS[@]}")
  else
    #⚠️ 深度是 3 不是 2：模块在 `core/crypto/src/test`，从 `core` 算是
    #   crypto(1) / src(2) / test(3)。写maxdepth 2 只会捞到 app 一个模块，
    #   而且**看起来是跑过了**（有汇总有输出），不会报错—— 纯静默漏检。
    while IFS= read -r d; do MODS+=("$d"); done < <(
      find app core data domain -maxdepth 3 -type d -path '*/src/test' 2>/dev/null \
        | sed 's|/src/test$||' | sort)
  fi

  TOTAL_PASS=0; TOTAL_FAIL=0; FAILED_MODS=(); SKIPPED_MODS=(); STUB_MODS=()
  PKG_PASS=0; PKG_FAIL=0; PKG_SKIP=0
  echo "════════════════════════════════════════════════════════"
  echo " 批量巡检：${#MODS[@]} 个模块（按**包**分组编，每组独立成败）"
  echo "════════════════════════════════════════════════════════"

  # ⚠️ 为什么按包分组而不是整模块一次编：
  #app 模块的 35 个测试散在 16 个包里，其中 `ui/*` 那几包需要 Compose 编译器插件
  #   （本工具没有，见文件头「不能查什么」）。整模块一次编的话，
  #   **一个包编不过 ⇒ 整个模块 35 个测试全部落空**，连纯逻辑的
  #   `autofill/engine`、`util` 也一起陪葬。
  #   ⇒ 分组后：能查的查出来，不能查的**如实单独点名**（不静默吞掉）。
  for m in "${MODS[@]}"; do
    mapfile -t tsrc_all < <(find "$m/src/test" -name '*Test.kt' 2>/dev/null | sort)
    if [ "${#tsrc_all[@]}" -eq 0 ]; then
      echo; echo "── $m：无测试文件，跳过"; SKIPPED_MODS+=("$m (无测试)"); continue
    fi
    echo; echo "── $m （${#tsrc_all[@]} 个测试文件）"

    # 按包路径分组。
    # ⚠️ 名字不能叫 GROUPS：那是 bash 保留变量（当前用户所属组 ID 的数组），
    #   拿来当关联数组会报 `cannot convert indexed to associative array`。
    #   ⚠️ 每轮必须重新 declare：unset 之后 bash 会把它降级成普通索引数组。
    declare -A TESTPKGS=()
    for f in "${tsrc_all[@]}"; do
      rel="${f#*src/test/java/}"; pkg="${rel%/*}"
      TESTPKGS["$pkg"]="${TESTPKGS[$pkg]:-} $f"
    done
    for pkg in $(printf '%s\n' "${!TESTPKGS[@]}" | sort); do
      # ⚠️ 必须用 `read -ra` 而不是 `mapfile -t`：
      #   分组值是**一行空格分隔**的多个路径（`f1 f2 f3`），
      #   mapfile 只按**换行**分割 ⇒ 整行变成"一个元素" ⇒
      #   后面 `[ -f "$f" ]全都不存在，28 个组齐刷刷报"源文件不存在"。
      #   （这个 bug 的讽刺之处：它正是脚本自己那个"源文件不存在"闸门抓出来的。）
      read -ra tsrc <<<"${TESTPKGS[$pkg]}"
      mapfile -t extra < <(expand_closure "${tsrc[@]}" | sed '/^$/d')
      if [ -n "$UNRESOLVED" ]; then
        echo "   ⚠️  [$pkg] 闭包未覆盖：$UNRESOLVED —— 本地查不了"
        FAILED_MODS+=("$m/$pkg (闭包不全)"); PKG_SKIP=$((PKG_SKIP+1)); UNRESOLVED=""; continue
      fi
      UNRESOLVED=""
      all=("${tsrc[@]}" "${extra[@]}")
      ok=1
      for f in "${all[@]}"; do [ -f "$f" ] || { echo "   ✗ 源文件不存在：'$f'"; ok=0; }; done
      if [ "$ok" != 1 ]; then
        FAILED_MODS+=("$m/$pkg (源文件缺失)"); PKG_FAIL=$((PKG_FAIL+1)); continue
      fi
      if ! compile_and_report "$OUT/${m}__$(echo "$pkg" | tr '/' '_')" "${all[@]}"; then
        echo "   ❌ [$pkg] 编译失败（${#tsrc[@]} 测试 + ${#extra[@]} 主源码）"
        FAILED_MODS+=("$m/$pkg (编译)"); PKG_FAIL=$((PKG_FAIL+1)); continue
      fi
      if [ "${VAULTIX_RUN_TESTS:-1}" = "0" ]; then
        echo "   ✅ [$pkg] 编译通过（$CLASSES class，未运行）"; PKG_PASS=$((PKG_PASS+1)); continue
      fi
      classes=()
      for f in "${tsrc[@]}"; do
        rel="${f#*src/test/java/}"; classes+=("$(echo "${rel%.kt}" | tr '/' '.')")
      done
      if run_tests "$OUT/${m}__$(echo "$pkg" | tr '/' '_')" "${classes[@]}"; then
        n="$(grep -oE 'OK \([0-9]+ tests?\)' <<<"$RUN_LOG" | grep -oE '[0-9]+' || echo '?')"
        echo "   ✅ [$pkg] ${#tsrc[@]} 测试文件 / $n 条用例全绿"
        PKG_PASS=$((PKG_PASS+1))
      else
        if [ "$STUB_ONLY" = 1 ]; then
          echo "   ⚠️ [$pkg] ${#tsrc[@]} 个测试文件已编译，但运行期撞上沙箱限制："
          grep -oE "UnsatisfiedLinkError: '[^']+'" <<<"$RUN_LOG" | sort -u | sed 's/^/        /' >&2
          echo "        （android-all 的桩方法没有实现 ⇒ 这类只能靠 CI/真机）"
          STUB_MODS+=("$m/$pkg"); PKG_SKIP=$((PKG_SKIP+1))
        else
          echo "   ❌ [$pkg] 测试失败："
          grep -E '^[0-9]+\) |Tests run:.*Failures: [1-9]' <<<"$RUN_LOG" | head -12 >&2
          FAILED_MODS+=("$m/$pkg (运行)"); PKG_FAIL=$((PKG_FAIL+1))
        fi
      fi
    done
    unset TESTPKGS
  done

  echo; echo "════════════════════════════════════════════════════════"
  echo " 汇总（按包）：✅ $PKG_PASS 组全绿 / ❌ $PKG_FAIL 组有问题 / ⚠️ $PKG_SKIP 组本地查不了"
  [ "${#SKIPPED_MODS[@]}" -gt 0 ] && echo " 无测试的模块：${SKIPPED_MODS[*]}"
  if [ "${#FAILED_MODS[@]}" -gt 0 ]; then
    echo " 有问题的组："
    for x in "${FAILED_MODS[@]}"; do echo "   - $x"; done
  fi
  if [ "${#MISSING_DEPS[@]}" -gt 0 ]; then
    echo "⚠️ 依赖缺失（可能正是上面某些组跑不起来的原因）：${MISSING_DEPS[*]}"
  fi
  if [ "${#STUB_MODS[@]}" -gt 0 ]; then
    echo "⚠️ 撞上沙箱限制（编译过、运行期 android 桩抛 UnsatisfiedLinkError）："
    for x in "${STUB_MODS[@]}"; do echo "   - $x"; done
  fi
  echo " ⚠️ 本工具查不了的：Compose 编译（app 的 ui/* 包）、KSP 代码生成、"
  echo "    依赖 android.jar 空注解的写法、需要真实 Android 运行时的桩方法。"
  echo "    ⇒ 这部分仍只能靠 CI / 真机。"
  echo "════════════════════════════════════════════════════════"
  [ "$PKG_FAIL" -eq 0 ] || exit 1
  exit 0
fi

# ================= 单文件模式 =================
[ "$#" -eq 0 ] && { echo "用法：$0 <文件.kt> [...] | --all [模块...]" >&2; exit 2; }

ensure_index
CLOSED=""
UNRESOLVED=""
for f in "$@"; do
  case "$f" in /*) p="$f";; *) p="$PWD/$f";; esac
  [ -f "$p" ] || { echo "文件不存在：$f" >&2; exit 2; }
  add_closed "${p#"$REPO_ROOT"/}"
done
mapfile -t extra < <(expand_closure "$@" | sed '/^$/d')
SOURCES=("$@")
[ "${#extra[@]}" -gt 0 ] && SOURCES+=("${extra[@]}")

if [ -n "$UNRESOLVED" ]; then
  echo "❌ 闭包未覆盖的项目内符号：$UNRESOLVED" >&2
  echo "   这些符号在 import 里，但本仓库里找不到定义。可能是：" >&2
  echo "   ① 符号所在模块缺依赖（跨模块 import）；② 符号确实不存在（那才是真错误）。" >&2
  exit 1
fi

# ⚠️ 与批量模式同一个闸门：空元素会让 kotlinc 报
#   `source file or directory not found: `（空路径），那是**工具自己的 bug
#   伪装成代码错误**。这里显式挡掉并指名是哪个文件。
for f in "${SOURCES[@]}"; do
  if [ ! -f "$f" ]; then
    echo "❌ 源文件不存在：'$f'" >&2
    echo "   （空元素通常来自闭包返回了空行 —— 工具的 bug，不是代码问题）" >&2
    exit 1
  fi
done

echo "▸ 检查 $# 个文件（自动带齐 ${#extra[@]} 个项目内依赖文件）" >&2
if ! compile_and_report "$OUT/single" "${SOURCES[@]}"; then
  echo "❌ 编译没过（错误见上）" >&2
  exit 1
fi
echo "✅ 编译通过，产出 $CLASSES 个 class" >&2

# ---- 真跑一遍（默认开启；VAULTIX_RUN_TESTS=0 可跳过）----
# 为什么要这一步：2026-10-03 CI run `37132804360` 里，本文件**编译 0 错误**，
# 却有一条用例红在 `IllegalStateException` —— 根因是`runTest` 的**虚拟时间**把
# `withTimeout(5000)` 直接跳到5 秒，于是"等真实线程"变成"立刻超时"。
# 这种错**只有运行才看得见**，编译永远查不到。
# 而等 CI 复核一轮要好几分钟，且卡片绿还会骗人（#145.4）。
[ "${VAULTIX_RUN_TESTS:-1}" = "0" ] && {
  echo "💡 已跳过运行（VAULTIX_RUN_TESTS=0）。只编译查不出虚拟时间 / 竞态类问题。" >&2
  exit 0
}

classes=()
for f in "$@"; do
  base="$(basename "$f" .kt)"
  case "$base" in *Test) ;; *) continue ;; esac   # 只跑 `XxxTest`
  # 从 `…/src/test/java/<包路径>/XxxTest.kt` 反推**全限定类名**：
  # 去掉 `.kt` 后缀，再把目录分隔符换成 `.`。（注意别再 `%/*` 去掉文件名。）
  rel="${f#*/src/test/java/}"
  classes+=("$(echo "${rel%.kt}" | tr '/' '.')")
done
if [ "${#classes[@]}" -eq 0 ]; then
  echo "（没有 XxxTest 命名的文件，跳过运行）" >&2; exit 0
fi
echo "▸ 运行 ${classes[*]}" >&2
if run_tests "$OUT/single" "${classes[@]}"; then
  tail -3 <<<"$RUN_LOG" >&2
  echo "✅ 测试通过" >&2
else
  tail -30 <<<"$RUN_LOG" >&2
  echo "❌ 测试**失败** —— 编译是绿的，问题在运行期（看上面堆栈）" >&2
  exit 1
fi
