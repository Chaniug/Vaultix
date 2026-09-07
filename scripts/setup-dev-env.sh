#!/usr/bin/env bash
# setup-dev-env.sh —— Vaultix 本地开发环境一键搭建（Linux / macOS）
#
# 安装：
#   1. JDK 17（Temurin，与 Docs/11-工程规范 中 Java 17 要求一致）
#   2. Android cmdline-tools + platforms;android-36 + build-tools + platform-tools
#
# 用法：
#   bash scripts/setup-dev-env.sh            # 默认装到 ~/AndroidDev
#   ANDROID_HOME=/opt/android-sdk bash scripts/setup-dev-env.sh
#
# 需要网络可访问：api.adoptium.net（JDK）、dl.google.com（Android SDK）。
# 沙箱/国内网络不通时，可改用镜像：
#   - Android SDK 镜像：https://mirrors.cloud.tencent.com/AndroidSDK/
#   - 把 CMDLINE_URL 替换为镜像地址即可。

set -euo pipefail

INSTALL_ROOT="${ANDROID_DEV_ROOT:-$HOME/AndroidDev}"
JDK_DIR="$INSTALL_ROOT/jdk17"
SDK_DIR="${ANDROID_HOME:-$INSTALL_ROOT/android-sdk}"

JDK_URL="${JDK_URL:-https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse}"
CMDLINE_URL="${CMDLINE_URL:-https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip}"
COMPILE_SDK=36

echo "==> 安装根目录: $INSTALL_ROOT"
echo "==> JDK 目录:    $JDK_DIR"
echo "==> SDK 目录:    $SDK_DIR"

mkdir -p "$INSTALL_ROOT" "$SDK_DIR"

# ---------- 1. JDK 17 ----------
if [ -x "$JDK_DIR/bin/java" ]; then
  echo "==> JDK 已存在，跳过下载"
else
  echo "==> 下载 JDK 17 ..."
  tmp="$(mktemp -d)"
  curl -fL "$JDK_URL" -o "$tmp/jdk.zip"
  echo "==> 解压 JDK ..."
  unzip -q "$tmp/jdk.zip" -d "$tmp"
  mv "$tmp"/jdk-17*/* "$JDK_DIR"/ 2>/dev/null || mv "$tmp"/jdk-17*/. "$JDK_DIR"/ 
  rm -rf "$tmp"
fi
"$JDK_DIR/bin/java" -version

# ---------- 2. Android cmdline-tools ----------
CMDLINE_DIR="$SDK_DIR/cmdline-tools"
if [ -x "$CMDLINE_DIR/latest/bin/sdkmanager" ]; then
  echo "==> cmdline-tools 已存在，跳过"
else
  echo "==> 下载 Android cmdline-tools ..."
  tmp="$(mktemp -d)"
  curl -fL "$CMDLINE_URL" -o "$tmp/cmdline.zip"
  echo "==> 解压 cmdline-tools ..."
  mkdir -p "$CMDLINE_DIR"
  unzip -q "$tmp/cmdline.zip" -d "$CMDLINE_DIR"
  # 内层目录必须重命名为 latest
  if [ -d "$CMDLINE_DIR/cmdline-tools" ]; then
    mv "$CMDLINE_DIR/cmdline-tools" "$CMDLINE_DIR/latest"
  fi
  rm -rf "$tmp"
fi

SDKM="$CMDLINE_DIR/latest/bin/sdkmanager"
export JAVA_HOME="$JDK_DIR"
export ANDROID_HOME="$SDK_DIR"
export ANDROID_SDK_ROOT="$SDK_DIR"

# ---------- 3. SDK 组件 ----------
LATEST_BT="$("$SDKM" --list 2>/dev/null | grep -oE 'build-tools;[0-9.]+' | sort -t';' -k2 -V | tail -1 | cut -d';' -f2)"
LATEST_BT="${LATEST_BT:-36.0.0}"

echo "==> 安装 SDK 组件：platforms;android-$COMPILE_SDK / build-tools;$LATEST_BT / platform-tools"
yes | "$SDKM" --licenses >/dev/null 2>&1 || true
yes | "$SDKM" "platforms;android-$COMPILE_SDK" "build-tools;$LATEST_BT" "platform-tools"

# ---------- 4. 输出环境变量（写入 ~/.bashrc 可选） ----------
echo ""
echo "=============================================="
echo "✅ 开发环境就绪"
echo "请把以下内容加入你的 shell 配置（~/.bashrc / ~/.zshrc）："
echo ""
echo "export JAVA_HOME=\"$JDK_DIR\""
echo "export ANDROID_HOME=\"$SDK_DIR\""
echo "export ANDROID_SDK_ROOT=\"$SDK_DIR\""
echo "export PATH=\"\$JAVA_HOME/bin:\$ANDROID_HOME/platform-tools:\$ANDROID_HOME/cmdline-tools/latest/bin:\$PATH\""
echo "=============================================="
