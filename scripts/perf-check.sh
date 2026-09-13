#!/usr/bin/env bash
# =============================================================================
# Vaultix 性能复测脚本  ——  perf-plan.md 的 P7（把测量固化成脚本与门禁）
#
# 产出四项指标：
#   1. 冷启动 TotalTime（×3，取稳定值）
#   2. 内存 PSS App Summary（TOTAL / Java Heap / Native Heap / Graphics / Private Other）
#   3. 分动作 Janky%（每动作单独 reset，样本才干净）
#   4. 帧时间百分位（50th / 90th / 95th / 99th）+ Janky frames%
#
# 用法：
#   bash scripts/perf-check.sh              # 四项全跑（需 App 已解锁并停在密码列表）
#   bash scripts/perf-check.sh --launch     # 只测冷启动 + 内存（不需要解锁，最省事）
#   bash scripts/perf-check.sh --ui-only    # 只测分动作 Janky + 帧百分位（**不冷启动**）
#   bash scripts/perf-check.sh --tabs 8     # P6 专用：来回切 N 轮再读一次（把样本做够）
#   bash scripts/perf-check.sh --ui-dump    # 只 dump UI 取控件中心坐标（校准下面的 TAP_*）
#
# ⚠️ `--ui-only` 的用处：**不要把宝贵的手动解锁状态弄丢**。
#   （勘误 2026-09-13：曾写"force-stop 会把已解锁的库锁回去"—— **实测不成立**。
#    实拍：`am force-stop` 后启动 `TotalTime 205ms`、`LaunchState COLD`，
#    但界面在 +1/+2/+4/+7 秒**全是密码列表**，且 `dumpsys fingerprint` 的
#    认证会话计数 24→24（**新增 0 次**）⇒ **解锁状态跨进程死亡被保留、且无需认证**。
#    什么时候会出现解锁页：库**本来就是锁的**。所以 `--ui-only` 的价值是
#    「不重启、不打断当前状态」，而不是"防止被锁回去"。）
#
# 输出：
#   控制台对比表 + 落盘 Docs/progress/perf-runs/<时间戳>.txt
#   历史汇总 Docs/progress/perf-runs/history.tsv（追加一行，便于跨次对比）
#
# ⚠️ 三条硬约束（踩过的坑，勿改）：
#   1. adb daemon 会在两次脚本调用之间被回收 ⇒ 本脚本每次自己 start-server + 重连。
#   2. 无线调试端口**会轮换** ⇒ 权威地址 = `adb mdns services` 里
#      `_adb-tls-connect._tcp` 行的末列；连不上再回退 LAST_PORT。
#   3. mDNS 与显式 connect 会把同一设备注册成两条 ⇒ 全程必须带 -s，
#      否则报 "more than one device/emulator"。
#
# ⚠️ 包类型决定能测什么：
#   release 包 run-as 被拒 ⇒ 只能测时序类指标（本脚本全部四项都属于此类）；
#   凡是需要"读应用数据"的（ciphers 行数 / WAL 大小 / 图标缓存条数）**必须换 debug 包**。
# =============================================================================
set -uo pipefail

PKG=io.vaultix.vaultix
ACTIVITY="$PKG/.MainActivity"
LAST_IP=192.168.1.114
LAST_PORT=37875
ADB_BIN="${ADB_BIN:-/c/AndroidSDK/platform-tools/adb.exe}"
[ -x "$ADB_BIN" ] || ADB_BIN="$(command -v adb || true)"
if [ -z "$ADB_BIN" ]; then echo "✗ 找不到 adb（设 ADB_BIN 环境变量指定）" >&2; exit 1; fi

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUTDIR="$ROOT/Docs/progress/perf-runs"
mkdir -p "$OUTDIR"

MODE="full"
case "${1:-}" in
  --launch)  MODE="launch" ;;
  --cold)    MODE="cold" ;;
  --ui-only) MODE="ui-only" ;;
  --tabs)    MODE="tabs"; TABS_ROUNDS="${2:-8}" ;;
  --ui-dump) MODE="ui-dump" ;;
  --help|-h) sed -n '2,30p' "$0"; exit 0 ;;
esac

# ---------------------------------------------------------------------------
# 0. 引导：起 daemon → 定位设备（mDNS 优先，回退上一次端口）→ 固定 -s
# ---------------------------------------------------------------------------
boot() {
  "$ADB_BIN" start-server >/dev/null 2>&1
  local ser
  ser="$("$ADB_BIN" devices | awk 'NR>1 && $2=="device"{print $1; exit}')"
  if [ -z "$ser" ]; then
    # mDNS 权威地址
    local mdns_host mdns_port
    mdns_host="$("$ADB_BIN" mdns services 2>/dev/null | awk '/_adb-tls-connect\._tcp/{split($3,a,":"); print a[1]; exit}')"
    mdns_port="$("$ADB_BIN" mdns services 2>/dev/null | awk '/_adb-tls-connect\._tcp/{split($3,a,":"); print a[2]; exit}')"
    if [ -n "${mdns_host:-}" ] && [ -n "${mdns_port:-}" ]; then
      "$ADB_BIN" connect "$mdns_host:$mdns_port" >/dev/null 2>&1
    fi
    ser="$("$ADB_BIN" devices | awk 'NR>1 && $2=="device"{print $1; exit}')"
  fi
  if [ -z "$ser" ]; then
    echo "· mDNS 无广告，回退上次端口 $LAST_IP:$LAST_PORT" >&2
    "$ADB_BIN" connect "$LAST_IP:$LAST_PORT" >/dev/null 2>&1
    sleep 1
    ser="$("$ADB_BIN" devices | awk 'NR>1 && $2=="device"{print $1; exit}')"
  fi
  if [ -z "$ser" ]; then
    echo "✗ 没有可用设备。请在手机上打开【无线调试】，然后重跑本脚本。" >&2
    exit 1
  fi
  echo "$ser"
}

SER="$(boot)"
echo "设备: $SER"

# 屏幕必须亮着，否则 screencap 全黑、点击也会打在锁屏上
WAKEFUL="$("$ADB_BIN" -s "$SER" shell dumpsys power 2>/dev/null | awk -F= '/mWakefulness=/{print $2; exit}' | tr -d '\r')"
if [ "$WAKEFUL" != "Awake" ]; then
  echo "· 屏幕未唤醒（$WAKEFUL）→ KEYCODE_WAKEUP"
  "$ADB_BIN" -s "$SER" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1
  sleep 1
fi

sh_() { "$ADB_BIN" -s "$SER" shell "$@"; }

if [ "$MODE" = "ui-dump" ]; then
  echo "—— 控件中心坐标（据此校准脚本顶部的 TAP_* 变量）——"
  sh_ uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  sh_ cat /sdcard/ui.xml 2>/dev/null \
    | tr '>' '>\n' | grep -oE 'text="[^"]*"[^>]*bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' \
    | while read -r line; do
        txt="$(echo "$line" | sed -E 's/.*text="([^"]*)".*/\1/')"
        b="$(echo "$line" | grep -oE '\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]')"
        l="$(echo "$b" | sed -E 's/\[([0-9]+),.*/\1/')"
        t="$(echo "$b" | sed -E 's/\[[0-9]+,([0-9]+)\].*/\1/')"
        r="$(echo "$b" | sed -E 's/.*\]\[([0-9]+),.*/\1/')"
        bt="$(echo "$b" | sed -E 's/.*,([0-9]+)\]$/\1/')"
        [ -n "${l:-}" ] && echo "  $(( (l+r)/2 )) $(( (t+bt)/2 ))   [$txt]"
      done
  exit 0
fi

# ⚠️ 这些必须在**跳过段之前**初始化：`ui-only` / `tabs` 模式会跳过冷启动与内存段，
#   而下面「落盘」一节仍会引用它们 ⇒ 漏初始化会因 `set -u` 直接报
#   `COLD_TRUE: unbound variable`（实测踩到，脚本在写报告前就退出）。
COLD_TRUE="-"; COLD_FIRST="-"; COLD_MED="-"; COLD=()
TOTAL_PSS=""; JAVA_HEAP=""; NATIVE_HEAP=""; GRAPHICS=""; PRIV_OTHER=""
if [ "$MODE" != "ui-only" ] && [ "$MODE" != "tabs" ]; then
# ---------------------------------------------------------------------------
# 1. 冷启动 ×4
# ---------------------------------------------------------------------------
echo
echo "=== 1/4 启动耗时（×4：首次单独看，后三次取中位）==="
# ⚠️⚠️ **这里测的不是真正的「冷启动」** —— 2026-09-13 用户当场纠正，必须记住：
#   `am force-stop` 只杀掉**进程**；dex/代码页、ART 缓存、page cache **全都还是热的**。
#   ⇒ 凡「force-stop 后启动」得到的都是 **进程冷 / 系统热**。
#   实测同机同包：唤醒后第一次 500ms，随后连续三次 147~168ms —— 差 3 倍，
#   差距就来自系统侧缓存冷热，不是应用代码。
#   ⇒ **真正的冷启动**（重启后首次 / drop_caches）只能靠 `adb reboot` 后测，
#     或 root 后 `echo 3 > /proc/sys/vm/drop_caches`（本脚本不擅自重启用户设备）。
#   ⇒ 因此：本项指标一律标「**进程冷/系统热**」，**禁止**拿去和 debug 的"冷启动 761ms"
#     直接比倍数 —— 那是两个不同的量。要比就必须双方同条件。
#   ⇒ 仍测 4 次：首次单列（系统缓存偏冷），优化目标只看后三次中位。
COLD_TRUE="-"
if [ "$MODE" = "cold" ]; then
  # ⚠️ 真·冷启动（--cold）：**刚重启完立刻用**，而且**绝对不能先 force-stop** ——
  #   这一次启动的 system cache（dex/ART/page cache）也是冷的，才是真正的冷启动。
  #   先 force-stop 再启动只是「进程冷/系统热」，那是另一个量（见文件头协议）。
  echo "  —— 真·冷启动（重启后首次，**不 force-stop**）——"
  T="$(sh_ am start -W -n "$ACTIVITY" 2>/dev/null | awk -F': ' '/TotalTime/{print $2; exit}' | tr -d '\r')"
  COLD_TRUE="${T:-N/A}"
  echo "  真·冷启动 TotalTime = $COLD_TRUE ms"
  sleep 3
fi

COLD=()
for i in 1 2 3 4; do
  sh_ am force-stop "$PKG" >/dev/null 2>&1; sleep 2
  T="$(sh_ am start -W -n "$ACTIVITY" 2>/dev/null | awk -F': ' '/TotalTime/{print $2; exit}' | tr -d '\r')"
  if [ "$i" = "1" ]; then
    echo "  第 1 次（冷 page cache）TotalTime = ${T:-N/A} ms"
  else
    echo "  第 $i 次 TotalTime = ${T:-N/A} ms"
  fi
  COLD+=("${T:-0}")
done
COLD_FIRST="${COLD[0]}"
COLD_MED="$(printf '%s\n' "${COLD[@]:1}" | sort -n | sed -n 2p)"

# ---------------------------------------------------------------------------
# 2. 内存
# ---------------------------------------------------------------------------
echo
echo "=== 2/4 内存 PSS ==="
MEM_RAW="$(sh_ dumpsys meminfo "$PKG" 2>/dev/null | sed -n '/App Summary/,/Objects/p')"
# ⚠️ 不能取「最后一个字段」：`TOTAL PSS:` 那一行尾上还挂着 `TOTAL RSS:` / `TOTAL SWAP PSS:`，
# 会把 "PSS:" 这种标签当成数值解析出来（首版就踩了这个坑）。
# 正确做法：按行首标签匹配，剥掉到**第一个**冒号为止的前缀，再取第一个字段。
mem_of() { echo "$MEM_RAW" | grep -E "^ *$1:" | head -1 | sed -E 's/^[^:]*: *//' | awk '{print $1}'; }
TOTAL_PSS="$(mem_of 'TOTAL PSS')"
JAVA_HEAP="$(mem_of 'Java Heap')"
NATIVE_HEAP="$(mem_of 'Native Heap')"
GRAPHICS="$(mem_of 'Graphics')"
PRIV_OTHER="$(mem_of 'Private Other')"
printf '  TOTAL PSS=%-6s Java=%-5s Native=%-5s Graphics=%-5s PrivateOther=%-6s\n' \
  "${TOTAL_PSS:-?}" "${JAVA_HEAP:-?}" "${NATIVE_HEAP:-?}" "${GRAPHICS:-?}" "${PRIV_OTHER:-?}"
fi   # ← ui-only 模式跳过「启动 + 内存」（避免重启进程、打断当前已解锁状态）

if [ "$MODE" = "launch" ]; then
  echo
  echo "（--launch 模式到此结束；完整分动作测量请在 App 已解锁、停在密码列表时用 --ui-only）"
fi

# ---------------------------------------------------------------------------
# 3+4. 分动作 Janky 与帧百分位（需要 App 已解锁并停在密码列表）
# ---------------------------------------------------------------------------
# ⚠️ 坐标必须用 `--ui-dump` 实测校准，不要照抄（不同分辨率/导航栏会失效）
TAB_TOTP_X=395;  TAB_CARD_X=863;  TAB_SETTINGS_X=1097; TAB_ITEMS_X=160
TAB_Y=2624
LIST_SWIPE_FROM="628 1800"; LIST_SWIPE_TO="628 700"; LIST_SWIPE_MS=300
ENTRY_TAP="628 905"

J_SUM=""; P_SUM=""
if [ "$MODE" = "ui-only" ] || [ "$MODE" = "tabs" ]; then
  # ⚠️ 前置校验必须用**正面证据**，不能用「没有 X」反证 —— 首版就是只用
  #   「dump 里没有『主密码』」当成「在列表页」，结果**桌面同样没有主密码**，
  #   校验通过、七次测量全打在空进程上（`gfxinfo` 回 "No process found"）。
  #   这与项目 ISSUES #84 的「假空态」是同一类错误：判定集合必须 = 展示集合。
  #   ⚠️ 另：dump 可能抓到**叠加窗**（如 `biometrics_dialog`），故先确认窗口名是宿主。
  sh_ uiautomator dump /sdcard/perfcheck.xml >/dev/null 2>&1
  DUMP="$(sh_ cat /sdcard/perfcheck.xml 2>/dev/null)"
  if ! echo "$DUMP" | grep -q 'io\.vaultix\.vaultix/'; then
    echo "✗ 前台不是 Vaultix（当前窗口里没有 io.vaultix.vaultix）⇒ 先启动应用再重跑。" >&2; exit 1
  fi
  if echo "$DUMP" | grep -q "主密码"; then
    echo "✗ 停在**解锁页**（库未解锁）⇒ 分动作测量无效。请先解锁库。" >&2; exit 1
  fi
  if ! echo "$DUMP" | grep -q "通行密钥"; then
    echo "✗ 看不到列表页证据（通行密钥/Tab）⇒ 当前不是列表页，拒绝产出假数据。" >&2; exit 1
  fi
  echo "· 前置校验通过：Vaultix 前台 + 已解锁 + 停在列表页"
fi

if [ "$MODE" = "tabs" ]; then
  # ⚠️ P6 专用协议：**单次切 Tab 只有 18~45 帧，百分比噪声极大**（实测切卡包 0%、
  #   切设置 5.6%、切回密码 5.3% 全在同一批数据里）⇒ 必须把样本做大才能下结论。
  #   做法：reset 一次，然后**来回切 N 轮**，最后读一次总账。
  echo
  echo "=== P6：切 Tab ×${TABS_ROUNDS} 轮（单次读取，样本才够）==="
  sh_ dumpsys gfxinfo "$PKG" reset >/dev/null 2>&1
  for r in $(seq 1 "$TABS_ROUNDS"); do
    sh_ input tap $TAB_TOTP_X $TAB_Y >/dev/null 2>&1;     sleep 1
    sh_ input tap $TAB_CARD_X $TAB_Y >/dev/null 2>&1;     sleep 1
    sh_ input tap $TAB_SETTINGS_X $TAB_Y >/dev/null 2>&1; sleep 1
    sh_ input tap $TAB_ITEMS_X $TAB_Y >/dev/null 2>&1;    sleep 1
  done
  sh_ input tap $TAB_ITEMS_X $TAB_Y >/dev/null 2>&1; sleep 1.5
  P_SUM="$(sh_ dumpsys gfxinfo "$PKG" 2>/dev/null | grep -iE 'total frames|janky frames|percentile|[0-9]+th' | sed 's/^/  /' | tr -d '\r')"
  echo "$P_SUM"
fi

if [ "$MODE" = "full" ] || [ "$MODE" = "ui-only" ]; then
  echo
  echo "=== 3/4 分动作 Janky ==="
  measure() {
    sh_ dumpsys gfxinfo "$PKG" reset >/dev/null 2>&1
    eval "$2" >/dev/null 2>&1
    sleep 1.2
    local row frames janky
    row="$(sh_ dumpsys gfxinfo "$PKG" 2>/dev/null)"
    # ⚠️ 解析要**容错**：不同 Android 版本的行文与列序都会变（Android 17 上
    # `gfxinfo` 还会在进程不存在时直接回 "No process found"）。
    # 取「第一个纯数字字段」比按固定列号切更稳；两种写法都试，失败就留空并显式报警。
    frames="$(echo "$row" | awk 'tolower($0) ~ /total frames/{for(i=1;i<=NF;i++) if($i ~ /^[0-9]+$/) {print $i; exit}}' | head -1)"
    janky="$(echo "$row" | awk '/Janky frames:/{for(i=1;i<=NF;i++) if($i ~ /^[0-9]+$/) {print $i; exit}}' | head -1)"
    if [ -z "$frames" ]; then
      # 最常见的两种失败：进程不在（被清后台 / 没启动）/ gfxinfo 行文不认识
      if echo "$row" | grep -qi "No process found"; then
        echo "  ⚠️ $1：**进程不在**（被清后台或未启动）⇒ 本次读数作废，不是「流畅」。" >&2
      else
        echo "  ⚠️ $1：gfxinfo 行文无法解析 ⇒ 本次读数作废。" >&2
      fi
      J_SUM="${J_SUM}\n  $1 = 作废"
      return
    fi
    local pct="-"
    if [ -n "${frames:-}" ] && [ "${frames:-0}" -gt 0 ] 2>/dev/null; then
      pct="$(awk -v j="${janky:-0}" -v f="$frames" 'BEGIN{printf "%.1f%%", j*100/f}')"
    fi
    printf '  %-14s 帧 %-5s Janky %-5s %s\n' "$1" "${frames:-?}" "${janky:-?}" "$pct"
    J_SUM="${J_SUM}\n  $1 = $pct"
  }
  measure "切到验证码"   "sh_ input tap $TAB_TOTP_X $TAB_Y"
  measure "切到卡包"     "sh_ input tap $TAB_CARD_X $TAB_Y"
  measure "切到设置"     "sh_ input tap $TAB_SETTINGS_X $TAB_Y"
  measure "切回密码"     "sh_ input tap $TAB_ITEMS_X $TAB_Y"
  measure "列表滚动"     "sh_ input swipe $LIST_SWIPE_FROM $LIST_SWIPE_TO $LIST_SWIPE_MS"
  measure "打开条目详情" "sh_ input tap $ENTRY_TAP"
  measure "返回列表"     "sh_ input keyevent 4"

  echo
  echo "=== 4/4 帧时间百分位（**新起一轮混合动作**）==="
  # ⚠️ 必须重新 reset 再走一遍混合动作：若直接读上一步的缓冲，拿到的是
  # `measure "返回列表"` 那一小段（实测只有 17 帧）—— 样本太小，百分位没有意义。
  sh_ dumpsys gfxinfo "$PKG" reset >/dev/null 2>&1
  sh_ input tap $TAB_TOTP_X $TAB_Y >/dev/null 2>&1;     sleep 1
  sh_ input tap $TAB_CARD_X $TAB_Y >/dev/null 2>&1;     sleep 1
  sh_ input tap $TAB_SETTINGS_X $TAB_Y >/dev/null 2>&1; sleep 1
  sh_ input tap $TAB_ITEMS_X $TAB_Y >/dev/null 2>&1;    sleep 1
  sh_ input swipe $LIST_SWIPE_FROM $LIST_SWIPE_TO $LIST_SWIPE_MS >/dev/null 2>&1; sleep 1
  sh_ input tap $ENTRY_TAP >/dev/null 2>&1;             sleep 1.5
  sh_ input keyevent 4 >/dev/null 2>&1;                 sleep 1.5
  P_SUM="$(sh_ dumpsys gfxinfo "$PKG" 2>/dev/null | grep -iE 'total frames|janky frames|percentile|[0-9]+th' | sed 's/^/  /' | tr -d '\r')"
  echo "$P_SUM"
fi

# ---------------------------------------------------------------------------
# 落盘 + 与上次对比
# ---------------------------------------------------------------------------
TS="$(date '+%Y-%m-%d %H:%M:%S')"
STAMP="$(date '+%Y%m%d-%H%M%S')"
REPORT="$OUTDIR/perf-$STAMP.txt"
{
  echo "# Vaultix 性能复测  $TS"
  echo "设备: $SER   包: $PKG"
  echo
  echo "## 启动 TotalTime(ms)【进程冷/系统热 —— 非真冷启动】: 首次=$COLD_FIRST  后续=[${COLD[*]:1}]  中位=$COLD_MED"
  [ "$COLD_TRUE" != "-" ] && echo "## 真·冷启动 TotalTime(ms)【重启后首次，system cache 也冷】: $COLD_TRUE"
  echo "## 内存 PSS(kB): TOTAL=$TOTAL_PSS Java=$JAVA_HEAP Native=$NATIVE_HEAP Graphics=$GRAPHICS PrivateOther=$PRIV_OTHER"
  [ -n "$J_SUM" ] && echo -e "## 分动作 Janky:$J_SUM"
  [ -n "$P_SUM" ] && { echo "## 帧百分位:"; echo "$P_SUM"; }
} > "$REPORT"

HIST="$OUTDIR/history.tsv"
if [ "$MODE" != "ui-only" ] && [ "$MODE" != "tabs" ]; then
  # ⚠️ 列名刻意不叫 cold_*：`am force-stop` 后启动是「进程冷/系统热」，不是真冷启动
  #   （真冷启动要 reboot 或 root drop_caches）。命名诚实，才不会跨条件比倍数。
  [ -f "$HIST" ] || printf 'timestamp\ttrue_cold_ms\tlaunch_first_ms\tlaunch_med_ms\tpss_total\tprivate_other\tjava_heap\tnative\tgraphics\n' > "$HIST"
  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$TS" "${COLD_TRUE:-}" "${COLD_FIRST:-}" "${COLD_MED:-}" "${TOTAL_PSS:-}" "${PRIV_OTHER:-}" "${JAVA_HEAP:-}" "${NATIVE_HEAP:-}" "${GRAPHICS:-}" >> "$HIST"
fi

echo
echo "=== 与上次对比（history.tsv 末两行）==="
if [ "$(wc -l < "$HIST")" -ge 3 ]; then
  { head -1 "$HIST"; tail -2 "$HIST"; } | column -t -s $'\t'
else
  echo "  （首次记录，暂无对比基线）"
fi
echo
echo "报告已落盘: $REPORT"
