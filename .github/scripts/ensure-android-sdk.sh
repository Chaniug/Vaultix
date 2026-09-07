#!/usr/bin/env bash
# ensure-android-sdk.sh —— 在 GitHub Actions (ubuntu-latest) runner 上确保
# Vaultix 构建所需的 Android SDK 组件（compileSdk=37, targetSdk=37）就绪。
#
# 关键事实：GitHub ubuntu-latest runner 镜像已预装
#   platforms;android-37, build-tools;36.0.0, platform-tools
# 因此本脚本【优先检测、能复用就绝不联网重装】，避免无谓的网络 / --update 风险。
set -euo pipefail

SDK_ROOT="${ANDROID_SDK_ROOT:-/usr/local/lib/android/sdk}"
export ANDROID_SDK_ROOT="$SDK_ROOT"
SDKM="$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager"

# sdkmanager 是 bash 脚本，需要 JAVA_HOME 指向可用的 JDK（setup-java 不一定导出 JAVA_HOME）
if [ -z "${JAVA_HOME:-}" ]; then
  jp="$(readlink -f "$(command -v java)")"
  export JAVA_HOME="$(dirname "$(dirname "$jp")")"
fi
echo "JAVA_HOME=${JAVA_HOME:-<未设置>}"
echo "ANDROID_SDK_ROOT=$SDK_ROOT"

if [ ! -x "$SDKM" ]; then
  echo "::error title=Missing sdkmanager::找不到 $SDKM，runner 镜像可能未预装 cmdline-tools。"
  exit 1
fi

# 是否已安装某包（用 --list_installed，不触发联网刷新）
installed() {
  "$SDKM" --list_installed 2>/dev/null | grep -qF "$1"
}

# 按需安装：已预装则跳过；未预装才联网安装，并打印真实错误便于定位
ensure() {
  local pkg="$1"
  if installed "$pkg"; then
    echo "::notice title=skip::$pkg 已预装，跳过。"
    return 0
  fi
  echo "::warning title=install::$pkg 未预装，尝试联网安装..."
  yes | "$SDKM" --licenses >/dev/null 2>&1 || true
  if ! yes | "$SDKM" "$pkg" 2>&1 | tee /tmp/sdk_install.log; then
    echo "::error title=install-failed::$pkg 安装失败，sdkmanager 真实输出："
    cat /tmp/sdk_install.log
    exit 1
  fi
}

ensure "platforms;android-37"
ensure "build-tools;36.0.0"
ensure "platform-tools"

# 最终校验：AGP 构建期需要 API 37 的 platform 目录。
# 注意：Android 16（API 36）起 platform 目录带扩展版本号，GitHub runner 镜像上的实际目录名
# 为 android-37.0 / android-37.1 / android-37.2，而**不一定存在**名为 android-37 的目录
# （本机 SDK 两者都有，故本地不会暴露此差异）。因此必须按前缀 android-37* 匹配。
if ! ls -d "$SDK_ROOT/platforms"/android-37* >/dev/null 2>&1; then
  echo "::error title=Platform android-37 missing::安装后仍未找到 $SDK_ROOT/platforms/android-37*"
  ls "$SDK_ROOT/platforms" 2>/dev/null || true
  exit 1
fi

echo "::notice title=installed platforms::$(ls "$SDK_ROOT/platforms" 2>/dev/null | tr '\n' ' ')"
echo "::notice title=installed build-tools::$(ls "$SDK_ROOT/build-tools" 2>/dev/null | tr '\n' ' ')"
echo "Android SDK 就绪：compileSdk=37 可用。"
