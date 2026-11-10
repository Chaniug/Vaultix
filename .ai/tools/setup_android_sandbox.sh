#!/usr/bin/env bash
# 沙箱内安装完整 Android 构建环境（Gradle + JDK 21 + SDK 37）
#
# 前提：本脚本只面向**沙箱**（root、Ubuntu、有网）。CI runner 自带 SDK，
# 不要用它；本地开发机器也别用（会把 SDK 装到 /opt）。
#
# ## 为什么需要这个：沙箱原本缺三样东西
#   1. Gradle 版本不对 —— sdkman 装的是 9.3.0，而 AGP 9.5.0 最低要 9.5.0，
#      直接用会报 "Minimum supported Gradle version is 9.5.0"。
#      ⇒ **不要装新的 gradle**，用项目自带 wrapper（9.8.2），那是版本管理的正解。
#   2. 没有 Android SDK —— 报 "SDK location not found"。
#   3. JDK 是 20 —— AGP 9 要求 17 或 21，20 不在支持列表里。
#
# ## 包名坑（踩过）
#   `platforms;android-37` **不存在**（公共源报 "Failed to find package"）。
#   正确写法是带小版本：`platforms;android-37.0`。
#   查法：`sdkmanager --list | grep 'platforms;android-3'`
#
# ## 用法
#   bash .ai/tools/setup_android_sandbox.sh        # 首次安装（约 6 分钟、~1.5GB）
#   source /opt/vaultix-android-env.sh             # 之后每个 shell 先加载环境
#   ./gradlew :app:compileFullDebugKotlin
#
set -uo pipefail

SDK_ROOT="${ANDROID_SDK_ROOT_DIR:-/opt/android-sdk}"
JDK_DIR="/usr/lib/jvm/java-21-openjdk-amd64"
CMDTOOLS_ZIP_URL="https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip"

log() { printf '\n\033[1m==> %s\033[0m\n' "$*"; }

# ── 1. JDK 21 ────────────────────────────────────────────────────────────────
if [ ! -d "$JDK_DIR" ]; then
    log "安装 OpenJDK 21"
    apt-get update -qq
    apt-get install -y openjdk-21-jdk-headless
else
    log "JDK 21 已存在：$JDK_DIR"
fi

# ── 2. Android 命令行工具 ────────────────────────────────────────────────────
BIN="$SDK_ROOT/cmdline-tools/latest/bin"
if [ ! -x "$BIN/sdkmanager" ]; then
    log "下载 Android cmdline-tools"
    mkdir -p "$SDK_ROOT/cmdline-tools"
    tmp="$(mktemp -d)"
    curl -sL -o "$tmp/cmdtools.zip" "$CMDTOOLS_ZIP_URL"
    unzip -q "$tmp/cmdtools.zip" -d "$tmp"
    mv "$tmp/cmdline-tools" "$SDK_ROOT/cmdline-tools/latest"
    rm -rf "$tmp"
else
    log "cmdline-tools 已存在"
fi

export ANDROID_HOME="$SDK_ROOT" ANDROID_SDK_ROOT="$SDK_ROOT"
export JAVA_HOME="$JDK_DIR"
export PATH="$BIN:$PATH"

# ── 3. SDK 平台与构建工具 ────────────────────────────────────────────────────
# ⚠️ 版本必须与 gradle/libs.versions.toml 的 compileSdk 对齐，否则 AGP 会要
#    自动下载（在无网/慢网时表现为卡住）。改 compileSdk 时同步改这里。
log "接受许可证"
yes | sdkmanager --licenses >/dev/null 2>&1

log "安装 platform 37.0 / build-tools 37.0.0 / platform-tools"
# 用 `tr '\r' '\n'` 把进度条的分行输出抹平，否则日志会变成一行巨型字符
sdkmanager "platforms;android-37.0" "build-tools;37.0.0" "platform-tools" 2>&1 \
    | tr '\r' '\n' | grep -vE '^\s*\[' | grep -viE 'loading|fetch|computing' | tail -5

# ── 4. 固化环境变量 ─────────────────────────────────────────────────────────
log "写入 /opt/vaultix-android-env.sh"
cat > /opt/vaultix-android-env.sh <<ENVEOF
# 沙箱 Android 构建环境（由 .ai/tools/setup_android_sandbox.sh 生成）
#
# 四项必须同时对齐，任何一项漏了都会报成**另一项**的错：
#   JAVA_HOME 漏 → AGP 报 Gradle 版本不对
#   ANDROID_HOME 漏 → 报 "SDK location not found"（而不是 platform 缺失）
export JAVA_HOME=$JDK_DIR
export ANDROID_HOME=$SDK_ROOT
export ANDROID_SDK_ROOT=$SDK_ROOT
export PATH="\$JAVA_HOME/bin:$BIN:$SDK_ROOT/platform-tools:\$PATH"

# ⚠️ 用项目自带 wrapper（./gradlew = 9.8.2），**不要**裸调 sdkman 的 gradle 9.3.0：
#    AGP 9.5.0 最低要求 Gradle 9.5.0，9.3.0 会直接拒绝构建。
ENVEOF

# ── 5. 自检 ─────────────────────────────────────────────────────────────────
log "自检"
source /opt/vaultix-android-env.sh
printf '  java    : %s\n' "$(java -version 2>&1 | head -1)"
printf '  sdk     : %s\n' "$(ls "$ANDROID_HOME/platforms" 2>/dev/null | tr '\n' ' ')"
printf '  bt      : %s\n' "$(ls "$ANDROID_HOME/build-tools" 2>/dev/null | tr '\n' ' ')"

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
if [ -x "$REPO_ROOT/gradlew" ]; then
    printf '  gradle  : %s\n' "$(cd "$REPO_ROOT" && ./gradlew --version 2>/dev/null | grep '^Gradle ' | head -1)"
fi

log "完成。之后每次开新 shell 先执行：source /opt/vaultix-android-env.sh"
