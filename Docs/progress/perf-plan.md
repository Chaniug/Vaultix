# 性能专项优化计划

> 建档：2026-09-13 · 基线数据来自真机实测（Honor BKQ-AN00 / Android 17）
> 配套报告：[`perf-report-2026-09-13.md`](../../.workbuddy/artifacts/perf-report-2026-09-13.md)
> 本文档是**执行计划**：每条都给出「现象 → 假设 → 验证命令 → 判据 → 验收」，可直接照做。
> 方法论与阈值对齐 [`Docs/16-性能与交互规范.md`](../16-性能与交互规范.md)。

---

## 0. 铁律：先换 release 包再谈优化

**基线是在 `assembleFullDebug` 包上测的** —— debug 包没有 R8（无混淆/裁剪）、Compose 带调试校验、
Hilt 带额外跟踪，**内存与帧耗时都会被显著放大**（经验值：内存高 30~60%、帧耗时高 20~40%）。

⇒ **P0 未完成前，任何"某页慢 / 内存高"的结论都不成立。** 不要拿 debug 数字去改代码。

⚠️ **但有一条副作用要先知道**（2026-09-13 实测）：**release 包 `run-as` 被拒**
（`run-as: package not debuggable: io.vaultix.vaultix`）。
⇒ 用 release 包测**时序类指标**（冷启动 / 帧时间 / PSS 摘要）没问题；
但**凡是需要"读应用数据"的项**（例如核对 `ciphers` 行数、看 WAL 大小、验证图标缓存条数）
**必须换回 debug 包** —— 两者同签一把密钥，可原地互相覆盖、数据不丢。

---

## 复测命令（整套，可直接复制）

```bash
PKG=io.vaultix.vaultix
SER=$(adb devices | awk 'NR>1 && $2=="device"{print $1; exit}')

# —— 冷启动（×3 取稳定值）——
for i in 1 2 3; do
  adb -s "$SER" shell am force-stop $PKG; sleep 2
  adb -s "$SER" shell am start -W -n $PKG/.MainActivity | grep TotalTime
  sleep 3
done

# —— 内存（看 App Summary 的 TOTAL PSS 与 Private Other）——
adb -s "$SER" shell dumpsys meminfo $PKG | grep -A 12 "App Summary"

# —— 逐动作卡顿（每个动作单独 reset，样本才干净）——
measure () {                       # 用法：measure "动作名" "adb 命令"
  adb -s "$SER" shell dumpsys gfxinfo $PKG reset >/dev/null
  eval "$2"
  sleep 1.2
  adb -s "$SER" shell dumpsys gfxinfo $PKG \
    | grep -E "Total frames|Janky frames:" | tr '\n' ' '; echo "  <- $1"
}
measure "切到验证码"   "adb -s \"$SER\" shell input tap 395 2624"
measure "切到卡包"     "adb -s \"$SER\" shell input tap 863 2624"
measure "切到设置"     "adb -s \"$SER\" shell input tap 1097 2624"
measure "切回密码"     "adb -s \"$SER\" shell input tap 160 2624"
measure "列表滚动"     "adb -s \"$SER\" shell input swipe 628 1800 628 700 300"
measure "打开条目详情" "adb -s \"$SER\" shell input tap 628 905"
measure "返回列表"     "adb -s \"$SER\" shell input keyevent 4"

# —— 完整帧时间线（找 100ms+ 的尖峰落在哪个动作）——
adb -s "$SER" shell dumpsys gfxinfo $PKG framestats
```

**读法**：看 **50th / 90th / 95th / 99th 百分位** 与 **Janky frames %**。
目标：`Janky < 1%`、`95th < 16ms`（一帧 16.6ms）。当前 debug 基线是 `Janky 9.2%`、`99th 150ms`。

---

## 基线（debug 包，仅供定位异常，不可作优化依据）

| 指标 | 实测 | 备注 |
|---|---|---|
| 冷启动 TotalTime | **761 / 763 / 766 ms** | 三次极稳 ⇒ 确定性耗时，无随机慢查询 |
| TOTAL PSS | **≈ 200 MB** | Java Heap 仅 17MB（托管堆健康） |
| **Private Other** | **96 MB** | ⚠️ 唯一异常项 |
| Graphics | 29 MB | EGL 表面缓冲 28MB，正常 |
| Janky frames | **9.21%**（369 帧 / 34 帧） | 混合动作合计 |
| 帧时间 | 50th **6ms** ✅ / 90th 31ms / 99th **150ms** ⚠️ | 大多数帧流畅，问题在长尾 |
| 分布特征 | 各页均匀 5~10% | ⇒ **系统性**问题，不是某一页写坏了 |
| 直方图 | 有 6 帧**正好 150ms** | ⇒ 像是"某个固定动作"，不是随机抖动 |

**本地缓存（已确认生效 ✅）**：`ciphers` 表 **219 行**、`image_cache` **225 个图标**（2.0MB）、
`pending_ops` **0 行**；冷启动 763ms 已出列表 ⇒ 走本地库渲染，无"等网络"空态。

---

## P0 · 建立 release 基线（**前置，必做**）

- **动作**：用 CI 产出的 release 包（`rele` 分支或 `v*` tag）装到真机，跑上面整套命令。
- **判据**：拿到 release 的 冷启动 / PSS / Janky% / 各百分位。
- **验收**：数字入库（更新本文件"基线"表，标注 release）。
- ⚠️ 覆盖安装前确认签名一致（本仓库 CI 两个渠道共用 `SIGNING_*`，可直接覆盖、数据不丢）。

---

## P1 · 站点图标的解码时机（150ms 尖峰的头号嫌疑）

- **现象**：直方图里 6 帧正好落在 150ms，整齐得不像随机抖动。
- **假设**：`SiteIcon` 在组合期**同步**读磁盘 / 解码位图，滚动与切页时周期性打出长帧。
- **验证**：
  1. 清空图标缓存后滚动列表（`adb shell run-as $PKG rm -rf cache/image_cache`），
     对比"首次滚动"与"缓存热了之后滚动"的 Janky%；
  2. `framestats` 对齐尖峰出现的时刻是否与列表项进入视口一致；
  3. 代码侧确认 `SiteIcon` 的解码是否在 `LaunchedEffect`/`produceState` 里、
     是否设了目标尺寸（`BitmapFactory.Options.inSampleSize` / Coil 的 `size()`）。
- **判据**：冷缓存滚动的 Janky% 明显高于热缓存 ⇒ 成立。
- **验收**：解码移到后台 + 限制目标尺寸（按显示尺寸，不按原图）+ 内存 LRU 上限；
  冷缓存 G 滚动 Janky% ≤ 2%。

## P2 · 内存 `Private Other` 96 MB

- **假设**：站点图标位图按原尺寸驻留（225 张 × ~200KB ≈ 45MB）；其余为 SQLite mmap / native。
- **验证**：
  1. release 包复测 `Private Other`；
  2. `adb shell dumpsys meminfo $PKG -d` 看细节；必要时用 Android Studio 的 Memory Profiler
     抓 heap dump 看位图来源；
  3. 逐页进出 20 次后复测 PSS，判断是"基线高"还是"逐次增长（泄漏）"。
- **判据**：`Private Other` 在 release 下仍 > 40MB，或**随页面进出持续增长**。
- **验收**：图标位图有明确上限（如 ≤ 8MB）＋ 可被系统回收；20 次进出后 PSS 增幅 < 5MB。

## P3 · SQLite WAL 长期未检查点

- **现象**：`vaultix.db-wal` **437KB > 主库 389KB**。
- **影响**：每次读都要先过 WAL（略慢），WAL 持续增长。
- **验证**：同步/写入若干次后看 WAL 是否继续变大；确认 `PRAGMA wal_autocheckpoint` 是否被改过。
- **验收**：应用退到后台或同步完成后显式 `PRAGMA wal_checkpoint(TRUNCATE)`，WAL 收敛到 < 100KB。
- **风险**：低。收益：明确（读路径 + 磁盘占用）。

## P4 · 冷启动 763ms → 目标 ≤ 550ms

- **现状**：三次 761/763/766，极稳 ⇒ 优化空间在"启动路径上做了什么"，而不是"偶发慢查询"。
- **验证**：`adb shell am start -W` 配合 **Perfetto**（`record_android_trace`）取首帧前的调用栈，
  定位 763ms 花在哪（Hilt 聚合 / Room 打开 / 主题取色 / 首帧组合）。
- **验收**：release 下 `TotalTime ≤ 550ms`，且冷启动后列表在 1.2s 内可见内容（不是空态）。
- **优先级**：低（当前已稳定可用，收益不如 P1/P2）。

## P5 · 滚动时"每帧读状态"是否驱动整棵列表重组

- **假设**：列表顶栏收起用 `rememberScrollCollapseFraction(scrollState)`，
  若下游按帧读取并使整个 `LazyColumn` 重组，会持续制造 5~10% 的 jank。
- **验证**：Perfetto 看滚动期间的重组范围；或临时把顶栏收起改成 `derivedStateOf` 包一层对比。
- **验收**：滚动期间只有顶栏区域重组；滚动 Janky% ≤ 2%。

## P6 · Tab 切换要重建整棵子树

- **假设**：`SaveableStateHolder` + `AnimatedContent` 在切 Tab 时重建整个页面组合（含列表）。
- **验证**：切 Tab 的 `framestats` 与"同一页内滚动"对比；看是否有一次性长帧。
- **验收**：切 Tab 首帧 ≤ 32ms，Janky% ≤ 3%。

## P7 · 把测量固化成脚本与门禁（防回归）

- **动作**：把上面「复测命令」整段存成 `scripts/perf-check.sh`，一条命令产出四项指标；
  记录到 `Docs/progress/current-status.md`（或单独 perf 记录文件），**每次改动前后各跑一次**。
- **验收**：脚本可在 1 分钟内跑完并输出与上次的对比表；出现回归时能一眼看出是哪一项。

---

## 执行顺序建议

```
P0 建立 release 基线   ← 必做，否则后面全是猜
 ├─ P1 图标解码   （若 release 仍见 150ms 尖峰）
 ├─ P2 内存       （若 release 的 Private Other 仍 > 40MB）
 ├─ P3 WAL        （独立、低风险、随时可做）
 ├─ P5/P6 重组范围（若各页 Janky 仍 > 3%）
 └─ P4 冷启动     （最后，收益最低）
P7 固化脚本       ← 建议与 P0 同时做
```

## 协作方式（真机 + ADB）

我会用 ADB 直接量：`am start -W` / `dumpsys meminfo` / `dumpsys gfxinfo` / `run-as` 读缓存，
必要时 `screenrecord` + `ffmpeg` 拆帧定位具体是哪一帧长。
**每条结论都带命令与原始数字**，不写"感觉快了"。同时遵守一条纪律：
**同一症状只准改一处、改完立刻复测同一指标**（避免多变量叠加，无法归因）。
