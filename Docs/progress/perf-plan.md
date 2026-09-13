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

## 0.5 测量协议（2026-09-13 实测补充 —— **不遵守就会得到假数字**）

### ★ 先定义清楚「冷启动」（2026-09-13 用户当场纠正）

**`am force-stop` + `am start` 不是冷启动** —— 它只杀掉**进程**，而 dex/代码页、
ART 缓存、page cache **全都还是热的**。凡这样测出来的都叫 **「进程冷 / 系统热」**。

| 名称 | 怎么测 | 实测（release `0.2.0`） |
|---|---|---|
| **进程冷 / 系统热** | `force-stop` 后 `am start`（本脚本的默认口径） | 首次 ~500ms、随后三次 **147~168ms** |
| **真·冷启动** | `adb reboot` 后首次启动；或 root 后 `echo 3 > /proc/sys/vm/drop_caches` | 未测（需重启设备，待用户同意） |

⇒ **禁止**拿「进程冷/系统热」的 150ms 去比 debug 基线的「冷启动 761ms」说"快了 5 倍"——
**两者是不同的量**。要比就必须双方同条件（都用 reboot 后口径，或都用 force-stop 口径）。

下面三条变量都是**当天实测确认**的：同一台机、同一个包（release `0.2.0`），只因状态不同就测出 **3~5 倍**差异。

| 变量 | 实测差异 | 协议 |
|---|---|---|
| **唤醒动画未结束** | 刚 `KEYCODE_WAKEUP` 就 `am start` ⇒ **172~196ms**（假首帧）；屏幕稳定后 **500ms** | 唤醒后 `sleep 3` 再测 |
| **系统侧缓存冷 / 热** | 唤醒后**第一次**启动 **500ms**；随后连续三次 **147~168ms** | 测 **4 次**：首次单列，**只看后三次中位** |
| **库是否已解锁** | 停在**解锁页**启动 ≈160ms；**已解锁进列表**另有一档 | 启动基线必须**与比较对象处于同一解锁状态**（debug 的 761ms 口径需与之一致才对得上） |
| **窗口是否在合成** | 同为解锁页：未合成 `PSS 45.9MB / Graphics 444`；已合成 `PSS 78.4MB / Graphics 28636` | 内存对比前先确认 `Graphics` 量级一致，否则比的是两种状态 |
| **进程是否还在** | `gfxinfo` 回 `No process found` 时，所有帧指标为空但**脚本可能"漂亮地"通过** | 必须**正面证据**校验（前台=包名 + 列表页证据），见下 |

### ★ 勘误：`am force-stop` **不会**把库锁回去（2026-09-13 实拍）

此前在本文件与 `perf-check.sh` 里都写过"`force-stop` 会把已解锁的库立刻锁回去" —— **不成立**。

| 观测 | 结果 |
|---|---|
| `am force-stop` → `am start -W` | `TotalTime **205ms**`、`LaunchState: COLD`（进程确实重启） |
| +1 / +2 / +4 / +7 秒四次取界面 | **全部是密码列表**（无主密码框、无指纹图标） |
| `dumpsys fingerprint` 认证会话计数 | **24 → 24（新增 0 次）** |

⇒ 结论两条，都会影响测量设计：
1. **库的解锁状态跨进程死亡被保留，且不再要求任何认证**（= `VaultTimeout` 取向使然，非 bug）。
   ⇒ 因此"清后台/强停后再打开直接进列表"是**预期行为**，不是"绕过了解锁"。
2. **什么时候才会出现解锁页：库本来就是锁的。** 所以想测"解锁页"相关项（含指纹入口是否出现），
   **必须先在应用内主动锁定库**，而不是靠 `force-stop` 造。

⇒ **`scripts/perf-check.sh` 已把这套协议写死**：自动等唤醒、启动测 4 次并分开报"首次 / 中位"、
锁屏时打警告、内存按标签逐项解析（不再靠"最后一个字段"）、
`--ui-only` 前置校验用**正面证据**（前台包名 + 列表页可证），**进程不在时明确报「读数作废」而不是静默出 0**。

⚠️ 另：手机会自己回 `Dozing`（无人操作时），所以**每条 adb 命令都要自带唤醒**，
不能假设上一轮唤醒的状态还在。

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

## 基线 · release（**2026-09-13 实测，正式基线**）

> 包：CI release **`0.2.0`**（`installerPackageName=com.microsoft.emmx`，非 debuggable，`run-as` 被拒）。
> 设备：Honor **BKQ-AN00** / Android 17 / 1256×2760。命令：`bash scripts/perf-check.sh`。

| 指标 | 条件 | 实测 | 对比 debug 基线 |
|---|---|---|---|
| **启动 TotalTime**（进程冷/系统热） | 停在解锁页、屏幕已稳定 | 首次 **500ms**；随后 147 / 152 / 163 / 168 ⇒ 中位 **152ms** | debug 761ms（**口径未必同**，勿直接比倍数） |
| **启动 TotalTime**（进程冷 → **直进已解锁列表**） | **库处于解锁态**（`force-stop` 后重开也直接进列表） | **205 / 262 ms** | 与 debug 761ms **同条件**（"已解锁进列表"）⇒ 可比，但 page cache 冷热仍不可控 |
| **Janky frames**（混合，224 帧） | **已解锁 · 列表页** | **2.23%**（5 帧） | 9.21% ⇒ 降 76% |
| 50th / 90th / 95th / 99th | 同上 | **5 / 7 / 8 / 22 ms** | 6 / 31 / 40 / **150** ms |
| GPU 99th | 同上 | **8 ms** | — |
| **列表滚动 Janky** | 同上 | **0.0%**（101 / 103 帧） | 5.8% ✅ |
| 切 Tab Janky | 同上 | 切验证码 4.4% / 切卡包 **0%** / 切设置 5.6% / 切回密码 5.3% | 5.3~10.5% |
| **TOTAL PSS** | 解锁页 · 窗口已合成 | **78.6 MB**（Java 10.8 / Native 20.1 / Code 1.3 / Graphics 28.7 / **Private Other 8.6** / System 7.9） | debug 189MB、Private Other 51.5MB |
| **TOTAL SWAP PSS** | 同上 | **108 KB** | debug **53.4 MB**（debug 大量被换出） |
| **TOTAL RSS** | 同上 | 247 MB | ⚠️ 见下方「口径」说明 |
| 分动作原始记录 | — | `Docs/progress/perf-runs/`（含 `history.tsv` 对比表） | — |

### ★ 三条由基线直接得出的结论

0. **「占用看起来很大」多半是口径问题，不是真的大**（2026-09-13 19:xx 补测）：
   `dumpsys meminfo` **进程列表里的聚合值 242MB 与 `TOTAL RSS` 247MB 几乎一致**
   ⇒ 系统/第三方工具展示的"内存占用"通常是**含共享页的 RSS 口径**，
   而应用的**私有 `TOTAL PSS` 只有 78.6MB**。**下结论前必须说明用的是哪个口径。**
   ⚠️ 这也是 debug 包数字（PSS 189MB / 进程列表 241MB）不可用的原因之一：
   它同时还伴随 **53.4MB 的 SWAP**（release 仅 108KB），说明 debug 真在被换出。

1. **150ms 尖峰在 release 下不存在** ⇒ **P1（图标解码）假设被推翻**，见 P1 节的实测结论。
2. **滚动已经达标**（0.0%）⇒ **P5（每帧读状态驱动重组）基本可关闭**。
3. **剩下的卡顿集中在「切 Tab」**（4.4~5.6%，而滚动是 0%）⇒ **P6 升为下一号嫌疑**，
   与"`SaveableStateHolder` + `AnimatedContent` 切 Tab 重建整棵子树"的假设吻合。
   ⚠️ 但切 Tab 单次只有 18~45 帧，**百分比噪声大**：下结论前需把每动作样本做大
   （重复切 5~10 轮再合并读数）。

**仍未做**：真·冷启动（需 `adb reboot`，待用户同意）；release + 列表页状态下的
内存与缓存条数复核（后者需 debug 包 ⇒ 要 `run-as`）。

---

## P0 · 建立 release 基线 —— ✅ **已完成（2026-09-13）**

- **动作**：~~换 release 包~~ ⇒ **发现真机上装的就是 CI release `0.2.0`**，**无需重装**。
- **判据**：拿到 release 的启动 / PSS / Janky% / 各百分位 ⇒ **见上方「基线 · release」**。
- **验收**：数字已入库 ✅。
- **遗留**：① 真·冷启动待 `adb reboot`；② P1/P2 的原始假设已按实测修正（见各自小节）。

---

## P1 · 站点图标的解码时机（150ms 尖峰的头号嫌疑）

> ### ⛔ 实测结论（2026-09-13）：**假设不成立，本项降级 / 基本可关闭**
> - **代码侧**：`app/src/main/java/io/vaultix/vaultix/ui/common/SiteIcon.kt` 用的是
>   **Coil `AsyncImage` + `.size(96)` + 内存/磁盘缓存** ⇒ 解码**本来就在后台**、
>   **本来就已经限了目标尺寸**（96px，非原图）。**没有"组合期同步解码"这回事。**
>   ⇒ 照原假设去"把解码移到后台"，是**白做**。
> - **真机侧**（release `0.2.0`，已解锁列表页）：**列表滚动 103 帧、Janky 0.0%**；
>   混合动作 99th 百分位 **25ms**，**没有任何 150ms 级别的尖峰**。
> - ⇒ 那个「6 帧正好 150ms」是 **debug 包特有**（无 R8 + Compose 调试校验）的产物，
>   在 release 下不复现。**不要拿它去改代码。**
> - 保留一条次级观察：`AsyncImage` 开了 `.crossfade(true)`（Coil 默认 100ms 淡入），
>   若将来真出现列表 jank，它是**第一个该看的**（淡入会带来逐帧重绘）。

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

> ### ⚠️ 实测修订（2026-09-13）：原文的算术不成立，嫌疑对象要换
> - 原假设「225 张 × ~200KB ≈ 45MB」是按**原尺寸**算的；但 `SiteIcon` 请求的是
>   `.size(96)` ⇒ 96px ARGB_8888 = **36KB/张**，225 张 ≈ **8MB**，**量级差 5 倍以上**。
> - 且 release 实测：解锁页（窗口已合成）`TOTAL PSS 78.4MB`，其中
>   `Graphics 28.6MB`（EGL 表面缓冲）、`Native 19.1MB`、`Private Other **4.1MB**`。
>   ⇒ debug 里的 `Private Other 96MB` **在 release 下没有复现**（相差 23 倍）。
> - ⇒ 结论：**先别按"图标位图驻留"去优化**。当前读数下 `Private Other` 不是异常项。
> - ⚠️ 但两次测量**状态不同**（debug 那时库已解锁、列表已渲染；release 这次停在解锁页）
>   ⇒ 要下最终结论，需在 **release + 已解锁 + 列表页**同一状态下再采一次 PSS。

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

> ⬇️ **实测降级（2026-09-13）**：release 下**列表滚动 101 / 103 帧、Janky 0.0%**
> （连续两轮复现），**已优于本项「≤2%」的验收线** ⇒ **滚动路径不是瓶颈，本项暂缓**。
> 除非将来滚动出现回归，才回来做这里的 Perfetto 重组范围分析。

- **假设**：列表顶栏收起用 `rememberScrollCollapseFraction(scrollState)`，
  若下游按帧读取并使整个 `LazyColumn` 重组，会持续制造 5~10% 的 jank。
- **验证**：Perfetto 看滚动期间的重组范围；或临时把顶栏收起改成 `derivedStateOf` 包一层对比。
- **验收**：滚动期间只有顶栏区域重组；滚动 Janky% ≤ 2%。

## P6 · Tab 切换要重建整棵子树 —— ✅ **已量化（2026-09-13 23:27）**

> ### 📊 实测（release `0.2.0`，`scripts/perf-check.sh --tabs 8`，`N≈700`）
> 两轮独立复现，结论稳定：
>
> | 指标 | 第 1 轮 | 第 2 轮 | 目标 |
> |---|---|---|---|
> | Total frames | 725 | 706 | — |
> | **Janky** | **31（4.28%）** | **28（3.97%）** | < 1% ❌ |
> | 50th / 90th | 5 / 8 ms | 5 / 8 ms | — |
> | **95th** | **14 ms** | **13 ms** | < 16 ms ✅ |
> | 99th | 22 ms | 21 ms | — |
> | GPU 99th | 13 ms | 11 ms | — |
>
> ⇒ **判定：切 Tab 是唯一未达标的路径**（滚动 0.0%、95th 已达标）。
> 性质 = **轻微、分散**：95th 仍在 16ms 预算内，99th 约 1.3 帧预算 ——
> 不是肉眼明显顿挫，而是"每切几次掉一帧"。
> ⇒ 结论与假设吻合（`SaveableStateHolder` + `AnimatedContent` 重建子树），
> 但**收益有限**：要动的是 Tab 容器的组合范围，风险高于收益，**建议排在最后**。
>
> ⚠️ 早先"混合动作 224 帧 Janky 2.23%"之所以更低，是因为混入了滚动的 0%——
> **要判断切 Tab 必须单独把样本做大**，否则会被稀释（这正是 `--tabs` 存在的理由）。

### ★ 解锁态的工作集与「有没有泄漏」（2026-09-13 23:30 实测）

| 状态 | TOTAL PSS | 说明 |
|---|---|---|
| 停在**解锁页** | **78.6 MB** | 窗口已合成 |
| **已解锁 · 列表页（用过一阵）** | **≈236 MB** | Native Heap ~90MB + Graphics ~34MB，**Java 堆只有 ~8MB** |

**泄漏判定（决定性实验）**：切 Tab ×12 为一组，连续 4 组采样 ⇒
首组 133MB → 239MB，之后 **235.6 / 242.0 / 235.5MB 三点持平（±3%）**，
Native Heap 在 87~92MB 间震荡而**不再单调上升**。

⇒ **不是泄漏，是"达到工作集平台"。** 且峰值与使用时长无关、与"开过哪些页"有关（一次性）。
⇒ 真正的问题不是漏，而是**平台本身偏高**（236MB PSS / 410MB RSS）：
增量集中在 **Native Heap**（20MB → 90MB），而 **Java 堆几乎没变** ⇒
指向**堆外**（位图/Skia/Compose 层），与"站点图标位图驻留"的原始怀疑方向一致，
但**落点在 Native 而非 Private Other**（后者实测只有 ~6.5MB）。
⚠️ 下一步若要继续压：用 Perfetto 的 native heap profiler 定位这 ~70MB 的归属，
**不要**再从 Java 堆找（那里只有 8MB，找不到东西）。


- **假设**：`SaveableStateHolder` + `AnimatedContent` 在切 Tab 时重建整个页面组合（含列表）。
- **验证**：切 Tab 的 `framestats` 与"同一页内滚动"对比；看是否有一次性长帧。
- **验收**：切 Tab 首帧 ≤ 32ms，Janky% ≤ 3%。

## P7 · 把测量固化成脚本与门禁（防回归）

- **动作**：把上面「复测命令」整段存成 `scripts/perf-check.sh`，一条命令产出四项指标；
  记录到 `Docs/progress/current-status.md`（或单独 perf 记录文件），**每次改动前后各跑一次**。
- **验收**：脚本可在 1 分钟内跑完并输出与上次的对比表；出现回归时能一眼看出是哪一项。

---

## 执行顺序建议（**2026-09-13 按实测重排**）

```
P0 建立 release 基线    ✅ 已完成（真机本来就装着 CI release 0.2.0）
P7 固化脚本             ✅ 已完成（scripts/perf-check.sh，含 --launch / --ui-only / --ui-dump）
 ├─ ⛔ P1 图标解码       假设被推翻（Coil 已异步 + size(96)；release 无 150ms 尖峰）→ 关闭
 ├─ ⛔ P2 内存           算术不成立（96px ≈ 8MB 而非 45MB）；release 下 Private Other 仅 4.1MB → 待同状态复核
 ├─ ⛔ P5 滚动重组       滚动 0.0%，已达标 → 暂缓
 ├─ ⭐ P6 切 Tab 重建    **下一号嫌疑**（剩余卡顿集中在此）—— 先把样本做到 ≥200 帧再判
 ├─ P3 WAL             独立、低风险、随时可做（注意：读缓存需 debug 包）
 └─ P4 冷启动          待 `adb reboot` 拿真·冷启动口径
```

## 协作方式（真机 + ADB）

我会用 ADB 直接量：`am start -W` / `dumpsys meminfo` / `dumpsys gfxinfo` / `run-as` 读缓存，
必要时 `screenrecord` + `ffmpeg` 拆帧定位具体是哪一帧长。
**每条结论都带命令与原始数字**，不写"感觉快了"。同时遵守一条纪律：
**同一症状只准改一处、改完立刻复测同一指标**（避免多变量叠加，无法归因）。
