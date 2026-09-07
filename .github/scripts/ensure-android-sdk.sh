#!/usr/bin/env bash
# ensure-android-sdk.sh —— 在 GitHub Actions (ubuntu-latest) runner 上
# 安装 Vaultix 构建所需的 Android SDK 组件。
#
# Vaultix 构建需求（见 Docs/11-工程规范与构建体系.md）：
#   compileSdk = 36,  targetSdk = 36,  minSdk = 26
#
# 关键点（参考 Bastion 在 2026-08 踩到的坑）：
#   - runner 镜像自带的 cmdline-tools 可能把平台装成带修订号的目录名
#     （android-36.0 / android-36.1）。AGP 8.x 对修订号不敏感，但为稳妥，
#     这里用 sdkmanager 显式安装，让它落到 platforms/android-36。
#   - 之后用 sdkmanager --list_installed 校验，缺失则报错中断，避免 Gradle
#     在“找不到平台”时才失败（那种失败信息更难定位）。
#
# 用法：bash "$GITHUB_WORKSPACE/.github/scripts/ensure-android-sdk.sh"
set -euo pipefail

SDK_ROOT="${ANDROID_SDK_ROOT:-/usr/local/lib/android/sdk}"
export ANDROID_SDK_ROOT="$SDK_ROOT"
SDKM="$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager"
yes_cmd="yes"

# GitHub runner 自带的 sdkmanager 是 bash 脚本，需要 JAVA_HOME 指向一个可用的 JDK。
# setup-java 只把 JDK 加进 PATH，未必导出 JAVA_HOME；这里显式定位 JDK 17，
# 否则 sdkmanager 会“找不到 java”或用了不兼容的 JDK 而安装失败。
if [ -z "${JAVA_HOME:-}" ]; then
  candidate="$( (command -v java >/dev/null 2>&1 && readlink -f "$(command -v java)") || true )"
  if [ -n "$candidate" ]; then
    export JAVA_HOME="$(dirname "$(dirname "$candidate")")"
  fi
fi
echo "JAVA_HOME=${JAVA_HOME:-<未设置>}"

echo "ANDROID_SDK_ROOT=$SDK_ROOT"
echo "sdkmanager=$SDKM"

if [ ! -x "$SDKM" ]; then
  echo "::error title=Missing sdkmanager::找不到 $SDKM，runner 镜像可能未预装 cmdline-tools。"
  exit 1
fi

# 解析最新的 build-tools 版本号（平台 36 时代通常是 36.0.0）
build_tools="$(
  "$SDKM" --list 2>/dev/null \
    | grep -oE 'build-tools;[0-9]+\.[0-9]+\.[0-9]+' \
    | sort -t';' -k2 -V \
    | tail -1 \
    | cut -d';' -f2
)" || build_tools=""
if [ -z "$build_tools" ]; then
  build_tools="36.0.0"
  echo "::warning title=build-tools 探测失败::回退到 $build_tools"
fi

echo "将安装：platforms;android-36  build-tools;$build_tools  platform-tools"

# 先刷新 cmdline-tools 自身索引，否则旧版 sdkmanager 本地清单里可能没有 API 36 的包。
"$SDKM" --update >/dev/null 2>&1 || echo "::warning title=sdkmanager update::--update 失败（可忽略，继续尝试安装）。"

# 接受许可（不静默吞错，便于定位）
yes | "$SDKM" --licenses >/dev/null 2>&1 || true

# 安装平台/构建工具，失败则重试一次（dl.google.com 偶发不稳定）
if ! yes | "$SDKM" "platforms;android-36" "build-tools;$build_tools" "platform-tools"; then
  echo "::warning title=SDK install retry::首次安装失败，5 秒后重试一次。"
  sleep 5
  yes | "$SDKM" "platforms;android-36" "build-tools;$build_tools" "platform-tools" || {
    echo "::error title=SDK install failed::安装 Android SDK 组件失败，请检查 runner 网络与 cmdline-tools 版本。"
    echo "::error title=SDK install debug::尝试列出可用 platforms 以辅助排查："
    "$SDKM" --list 2>&1 | grep -i "platforms;android-36" || echo "本地清单中未找到 platforms;android-36（可能需要更新 cmdline-tools 或检查网络）。"
    exit 1
  }
fi

echo "::notice title=installed platforms::$(ls "$SDK_ROOT/platforms" 2>/dev/null | tr '\n' ' ')"
echo "::notice title=installed build-tools::$(ls "$SDK_ROOT/build-tools" 2>/dev/null | tr '\n' ' ')"

# 校验平台确实存在，否则 Gradle 会在构建期才报 “Failed to find Platform SDK”
if [ ! -d "$SDK_ROOT/platforms/android-36" ]; then
  echo "::error title=Platform android-36 missing::安装后仍未找到 $SDK_ROOT/platforms/android-36"
  exit 1
fi

echo "Android SDK 就绪：compileSdk=36 可用。"
