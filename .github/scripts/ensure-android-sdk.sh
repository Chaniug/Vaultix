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

yes | "$SDKM" --licenses >/dev/null 2>&1 || true
yes | "$SDKM" "platforms;android-36" "build-tools;$build_tools" "platform-tools" || {
  echo "::error title=SDK install failed::安装 Android SDK 组件失败，请检查 runner 网络与 cmdline-tools 版本。"
  exit 1
}

echo "::notice title=installed platforms::$(ls "$SDK_ROOT/platforms" 2>/dev/null | tr '\n' ' ')"
echo "::notice title=installed build-tools::$(ls "$SDK_ROOT/build-tools" 2>/dev/null | tr '\n' ' ')"

# 校验平台确实存在，否则 Gradle 会在构建期才报 “Failed to find Platform SDK”
if [ ! -d "$SDK_ROOT/platforms/android-36" ]; then
  echo "::error title=Platform android-36 missing::安装后仍未找到 $SDK_ROOT/platforms/android-36"
  exit 1
fi

echo "Android SDK 就绪：compileSdk=36 可用。"
