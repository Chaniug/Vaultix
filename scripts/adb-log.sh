#!/usr/bin/env bash
# adb-log.sh —— Vaultix 真机日志助手（Windows Git Bash / Linux / macOS 通用）
#
# 为什么需要它：Vaultix 的真机联调（Edge 填充 / 通行密钥 / 快速解锁）反复依赖 logcat，
# 但本机实测有三个固定摩擦点，每次手敲都会踩：
#
#   1) adb 不在 PATH —— SDK 路径只记在 local.properties 的 sdk.dir（C\:/AndroidSDK）。
#   2) 无线调试的 IP/端口会漂 —— 手抄的 `192.168.1.144:46415` 连不上，
#      真实地址在 mDNS 广播里（实测 .114 而非 .144，只有端口对得上）。
#   3) 同一台设备会被「显式 connect」和「mDNS 自动发现」注册成两条
#      → 不带 -s 就报 `more than one device/emulator`。
#
# 用法：
#   bash scripts/adb-log.sh connect              # 连设备并打印身份/版本/组件状态
#   bash scripts/adb-log.sh dump                 # 导出缓冲区里已有的 Vaultix 日志
#   bash scripts/adb-log.sh live                 # 清缓冲 + 实时跟随（复现问题时用，Ctrl-C 退出）
#   bash scripts/adb-log.sh dump --wide          # 连系统侧凭据/自动填充日志一起看
#   bash scripts/adb-log.sh --dev 192.168.1.114:46415 dump
#
# ★ 设备端脱离式捕获（推荐用于真机复现，见下方 capture/pull）：
#   bash scripts/adb-log.sh capture              # 在手机端后台起 logcat，写文件 + 轮转
#   bash scripts/adb-log.sh mark "点 Retry"      # 往日志里插锚点，便于定位那一次操作
#   bash scripts/adb-log.sh pull                 # 把捕获拉回本地并打印关键行
#   bash scripts/adb-log.sh stop                 # 停掉捕获
#
#   为什么不用 live：本机 adb server 在命令之间会被回收，`live` 的管道会随之断掉。
#   设备端 `setsid nohup logcat -f <file>` 完全脱离 adb 会话，跨命令存活，
#   配合 `-r/-n` 轮转即可获得数十分钟的回溯窗口。
#
# ⚠️ 开启系统侧详细日志（否则 Autofill/CredentialManager 侧一片空白）：
#   adb shell cmd autofill set log_level verbose
#   （默认是 off；`cmd credential` 无 shell 实现，CP 侧只能靠 logcat）
#
# ⚠️ 无线调试「配对」与「连接」是**两个不同端口**（2026-09-12 实测踩坑）：
#   1) 首次 / 重新配对：手机「无线调试 → 使用配对码配对设备」对话框给出
#      **IP:配对端口 + 6 位配对码**，必须在对话框**保持打开**时执行
#      `adb pair <IP>:<配对端口> <码>`（关掉即失效，报
#      `protocol fault (couldn't read status message)`）；
#   2) 配对成功后，还要用「无线调试」主界面那个 **IP:连接端口** 执行 `adb connect`，
#      它是**另一个端口**。本脚本 `--dev` 传的应是这个**连接端口**。
#
# ⚠️ 两套 adb 会抢 server：`C:\adb` 与 `C:\AndroidSDK\platform-tools` 若混用，
#   会互相 kill 掉 5037 上的 server，表现为「connect 报成功、紧接着 devices 为空、
#   daemon 反复重启」。统一只用一个二进制（推荐 `ADB=/c/adb/adb.exe`）：
#     taskkill //F //IM adb.exe     # 先清干净，再全程用同一个 adb
#   并尽量把「connect + 操作」压进**同一条命令**（server 在命令之间可能被回收）。
#   另：ICMP 常被手机/路由禁 —— `ping` 不可达**不代表**主机不在线；改用 `adb connect` 区分：
#   10061「积极拒绝」= 主机在、但该端口无服务；10060「超时」= 主机/端口不可达。
#
# 可选环境变量：
#   ADB=/path/to/adb      显式指定 adb（默认自动探测）
#   VAULTIX_TAG=TagName   覆盖日志 tag（默认 VaultixAutofill）
#   VAULTIX_CAP_FILE=path 设备端捕获文件（默认 /data/local/tmp/vaultix-capture.log）
#
# 说明：应用日志只有一个 tag —— `VaultixAutofill`（见 app/.../autofill/AutofillLogger.kt），
# 消息体带 `CP ` / `PK ` 前缀区分凭据提供商与通行密钥两条链路；且仅 debug 构建输出。

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PKG="io.vaultix.vaultix"
TAG="${VAULTIX_TAG:-VaultixAutofill}"
CAP_FILE="${VAULTIX_CAP_FILE:-/data/local/tmp/vaultix-capture.log}"

# 系统侧与凭据/填充链路的 tag（--wide 时使用）
WIDE_PATTERN='VaultixAutofill|CredentialManager|CredentialProvider|AutofillManager|Autofill|BiometricPrompt|ActivityTaskManager'

# ---------------------------------------------------------------- adb 探测
find_adb() {
  if [ -n "${ADB:-}" ] && [ -x "$ADB" ]; then printf '%s' "$ADB"; return 0; fi
  if command -v adb >/dev/null 2>&1; then command -v adb; return 0; fi

  local cand="" v=""
  # 从 local.properties 的 sdk.dir 推导
  if [ -f "$ROOT/local.properties" ]; then
    v="$(sed -n 's/^sdk\.dir=//p' "$ROOT/local.properties" | head -1 | tr -d '\r')"
    if [ -n "$v" ]; then
      v="${v//\\:/:}"; v="${v//\\//}"
      if [[ "$v" =~ ^([A-Za-z]):/ ]]; then v="/${BASH_REMATCH[1],,}/${v:3}"; fi
      cand="$v/platform-tools/adb.exe"
      [ -x "$cand" ] || cand="$v/platform-tools/adb"
      [ -x "$cand" ] && { printf '%s' "$cand"; return 0; }
    fi
  fi

  for cand in \
    "${ANDROID_HOME:-}/platform-tools/adb.exe" \
    "${ANDROID_SDK_ROOT:-}/platform-tools/adb.exe" \
    "/c/AndroidSDK/platform-tools/adb.exe" \
    "$HOME/AppData/Local/Android/Sdk/platform-tools/adb.exe" \
    "$HOME/Android/Sdk/platform-tools/adb" ; do
    [ -n "$cand" ] && [ -x "$cand" ] && { printf '%s' "$cand"; return 0; }
  done

  echo "找不到 adb：请设置 ADB 环境变量，或在 local.properties 里配好 sdk.dir" >&2
  return 1
}

# ---------------------------------------------------------------- 参数解析
DEV=""
MODE=""
WIDE=0
MARK_MSG=""
while [ $# -gt 0 ]; do
  case "$1" in
    --dev)  DEV="${2:-}"; shift 2 ;;
    --wide) WIDE=1; shift ;;
    connect|dump|live|capture|pull|stop) MODE="$1"; shift ;;
    mark) MODE="mark"; shift; MARK_MSG="${1:-marker}"; shift ;;
    -h|--help) sed -n '2,40p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "未知参数：$1（-h 看用法）" >&2; exit 2 ;;
  esac
done
[ -n "$MODE" ] || MODE="connect"

ADB="$(find_adb)"

# ---------------------------------------------------------------- 设备定位
# 输出一个可直接给 -s 用的目标；优先 mDNS 广播的真实地址（IP/端口漂了也能自愈）。
resolve_device() {
  "$ADB" start-server >/dev/null 2>&1 || true

  if [ -n "$DEV" ]; then
    "$ADB" connect "$DEV" >/dev/null 2>&1 || true
  fi

  # mDNS 广播里取真实 ip:port（无线调试的权威来源）
  local addr="" i=0
  while [ $i -lt 10 ]; do
    addr="$("$ADB" mdns services 2>/dev/null \
      | awk '$2=="_adb-tls-connect._tcp" {print $NF}' | head -1 || true)"
    [ -n "$addr" ] && break
    sleep 1; i=$((i+1))
  done

  if [ -n "$addr" ] && [ "$addr" != "$DEV" ]; then
    "$ADB" connect "$addr" >/dev/null 2>&1 || true
  fi

  # 优先用显式地址（可 -s 指定，避免 mDNS 那条重复）
  if [ -n "$DEV" ]; then printf '%s' "$DEV"; return 0; fi
  if [ -n "$addr" ]; then printf '%s' "$addr"; return 0; fi

  echo "未发现任何无线调试设备：确认手机「无线调试」已开启且与本机同一局域网" >&2
  return 1
}

TARGET="$(resolve_device)"
echo "[adb-log] adb    = $ADB"
echo "[adb-log] target = $TARGET"

dev() { "$ADB" -s "$TARGET" "$@"; }

# ---------------------------------------------------------------- 子命令
case "$MODE" in
  connect)
    echo
    echo "== 设备 =="
    dev shell "getprop ro.product.brand; getprop ro.product.model; \
getprop ro.build.version.release; getprop ro.build.version.sdk; getprop ro.product.cpu.abi"
    echo "== wlan0 =="
    dev shell "ip -4 addr show wlan0 2>/dev/null | grep -o 'inet [0-9.]*'" || true
    echo "== 应用 =="
    dev shell "dumpsys package $PKG | grep -E 'versionName|lastUpdateTime'" || true
    echo "== 启用状态 =="
    printf 'autofill_service = %s\n' "$(dev shell 'settings get secure autofill_service' | tr -d '\r')"
    printf 'credential_service = %s\n' "$(dev shell 'settings get secure credential_service' | tr -d '\r')"
    ;;

  dump)
    echo
    if [ "$WIDE" = "1" ]; then
      echo "== 已有日志（Vaultix + 系统凭据/填充侧，最近 400 行）=="
      dev logcat -d -t 3000 2>/dev/null | grep -iE "$WIDE_PATTERN" | tail -400 || true
    else
      echo "== 已有日志（tag=$TAG，最近 200 行）=="
      dev logcat -d -s "$TAG" -t 200 2>/dev/null || true
    fi
    echo
    echo "[提示] 上面为空 = 缓冲区里没有该 tag 的记录（没复现过，或装的是 release 包）"
    ;;

  live)
    echo
    echo "== 清空缓冲区，开始实时跟随 =="
    dev logcat -c 2>/dev/null || true
    echo ">>> 现在去复现问题（Edge 触发填充 / 通行密钥），Ctrl-C 结束 <<<"
    echo
    if [ "$WIDE" = "1" ]; then
      dev logcat -v time 2>/dev/null | grep --line-buffered -iE "$WIDE_PATTERN" || true
    else
      dev logcat -v time -s "$TAG" || true
    fi
    ;;

  capture)
    echo
    echo "== 开启系统侧详细日志（autofill log_level=verbose，默认是 off）=="
    dev shell "cmd autofill set log_level verbose" 2>/dev/null || true
    printf 'log_level = %s\n' "$(dev shell 'cmd autofill get log_level' | tr -d '\r')"
    echo "== 清理旧捕获 =="
    # 注意 'vaultix-captur[e]' 的方括号写法：避免 pkill 匹配到自身的命令行（自杀陷阱）
    dev shell "pkill -f 'vaultix-captur[e]' 2>/dev/null; sleep 1; rm -f $CAP_FILE*" 2>/dev/null || true
    echo "== 启动设备端后台捕获（main+system / 16MB x 24 轮转）=="
    dev shell "setsid nohup logcat -b main,system -v threadtime -f $CAP_FILE \
-r 16384 -n 24 >/dev/null 2>&1 < /dev/null &" 2>/dev/null || true
    sleep 4
    dev shell "log -t VAULTIX_MARKER '=== CAPTURE START ==='" 2>/dev/null || true
    sleep 1
    echo "== 状态 =="
    dev shell "ps -A -o PID,ARGS 2>/dev/null | grep 'logcat -b main' | grep -v grep" 2>&1 || true
    dev shell "ls -l $CAP_FILE* 2>/dev/null" 2>&1 || true
    echo
    echo "[提示] 回溯窗口约 40-45 分钟；复现完执行 'pull' 取回。"
    ;;

  pull)
    echo
    echo "== 锚点 =="
    dev shell "grep VAULTIX_MARKER $CAP_FILE 2>/dev/null" 2>&1 | grep -v adbd || true
    mkdir -p "$ROOT/build/adb-capture"
    OUT="$ROOT/build/adb-capture/device-log.txt"
    dev exec-out "cat $CAP_FILE" > "$OUT" 2>/dev/null || true
    echo "== 已保存 $(wc -l < "$OUT" 2>/dev/null || echo 0) 行 → $OUT"
    echo
    if [ "$WIDE" = "1" ]; then
      echo "== 关键行（Vaultix + 系统凭据/填充/浏览器侧）=="
      grep -iE "$WIDE_PATTERN|emmx|webauthn|assertion|passkey" "$OUT" 2>/dev/null | tail -200 || true
    else
      echo "== Vaultix 自身日志（tag=$TAG）=="
      grep -F "$TAG" "$OUT" 2>/dev/null | tail -200 || true
    fi
    ;;

  mark)
    dev shell "log -t VAULTIX_MARKER '$MARK_MSG'" 2>/dev/null || true
    echo "[adb-log] 已插入锚点：$MARK_MSG"
    ;;

  stop)
    dev shell "pkill -f 'vaultix-captur[e]' 2>/dev/null; sleep 1; rm -f $CAP_FILE*" 2>/dev/null || true
    echo "[adb-log] 已停止捕获并清理设备端文件"
    ;;
esac
