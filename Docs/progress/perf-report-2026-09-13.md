# Vaultix 真机性能体检报告

- **设备**：Honor BKQ-AN00，Android 17（targetSdk 37）
- **被测包**：本地 debug 包（`:app:assembleFullDebug`，发布密钥重签）
- **方法**：`am start -W` 冷启动计时 / `dumpsys meminfo` / `dumpsys gfxinfo` 分动作复位统计 /
  `run-as` 读取本地缓存载体
- **时间**：2026-09-13 20:48~20:52

---

## ⚠️ 先说结论的前提：这是 **debug 包**

debug 包 **没有 R8（混淆/裁剪）**、Compose 带调试校验、Hilt 带额外跟踪 ——
**内存与卡顿都会被显著放大**（经验上内存高 30~60%、帧耗时高 20~40%）。

⇒ **下面的数字用于"找异常项"，不能用于"下结论说某个页面慢"。**
要判断真实体验，请用 CI 出的 **release 包**复测同一套命令（命令见文末）。

---

## 1. 冷启动

| 次数 | TotalTime | WaitTime | LaunchState |
|---|---|---|---|
| 1 | 766 ms | 771 ms | COLD |
| 2 | 761 ms | 763 ms | COLD |
| 3 | 763 ms | — | COLD |

- **三次极稳定（761~766ms）** ⇒ 启动路径没有随机慢查询/网络等待，属于"确定性的耗时"。
- `WaitTime ≈ TotalTime` ⇒ 没有额外的调度/分发延迟。
- 参考：Hilt + Room + Compose 的应用，release 包通常能做到 400~550ms。
- 启动后列表**直接从本地缓存渲染**（见第 4 节），没有"先空态再填"的等待。

## 2. 内存（PSS ≈ 195~209 MB）

`dumpsys meminfo` App Summary：

| 项 | PSS |
|---|---|
| Java Heap | 17 MB |
| Native Heap | 27 MB |
| Code | 4.4 MB |
| Stack | 2.3 MB |
| Graphics | 29 MB（其中 EGL mtrack 28 MB = 表面缓冲） |
| **Private Other** | **96 MB** ← 异常项 |
| System | 23 MB |
| **TOTAL PSS** | **≈ 200 MB** |

- Java Heap 只有 17MB ⇒ **托管堆很健康**（没有明显的 Java 层泄漏）。
- `Views: 8`、`Activities: 1`、`WebViews: 0` ⇒ 纯 Compose，没有 View 泄漏迹象。
- ⚠️ **`Private Other` 96MB 是唯一的异常**。这个分类装的是"未归入 Java/Native 堆的私有匿名映射"，
  常见来源：
  1. **站点图标位图缓存**（缓存目录里有 **225 个**图标；若每个都按原始尺寸解码驻留，
     225 × ~200KB ≈ 45MB，量级吻合）；
  2. SQLite 的 mmap / page cache；
  3. 未计入 nativheap 的 native 分配。
- 下一次采集请在 **release 包**上做，若 `Private Other` 仍然 >40MB，再针对上面第 1 条定位
  （重点：图标解码尺寸是否被限制、是否有 LRU 上限）。

## 3. 卡顿（`gfxinfo`）

**混合动作合计**（滚动 2 次 + 切 4 个 Tab + 开详情 + 详情滚动 + 返回）：

| 指标 | 值 |
|---|---|
| Total frames | 369 |
| Janky frames | 34（**9.21%**） |
| Janky（legacy） | 51（13.82%） |
| 50th 百分位 | **6 ms** ✅ |
| 90th | 31 ms |
| 95th | 40 ms |
| 99th | **150 ms** ⚠️ |
| Missed Vsync | 20 |

**分动作**（每项单独 reset 后测，样本较小、百分比噪声大）：

| 动作 | 帧数 | Janky |
|---|---|---|
| 切到验证码 | 38 | 3（7.9%） |
| 切到卡包 | 31 | 2（6.5%） |
| 切到设置 | 19 | 1（5.3%） |
| 切回密码 | 19 | 2（10.5%） |
| 列表滚动一次 | 87 | 5（5.8%） |
| 打开条目详情 | 22 | 2（9.1%） |
| 返回列表 | 3 | 1（33%，样本太小无意义） |

**核心判断**：
- **50% 分位只有 6ms** ⇒ 大多数帧是流畅的，**不是"整页都卡"**；
- **卡顿是分散的、处处 5~10%** ⇒ 属于**系统性**问题（不是某一页写坏了）；
- **直方图里有 6 帧正好落在 150ms** ⇒ 这是**肉眼可见的顿挫**，且数值整齐
  ⇒ 像是"某个固定动作"（同步 IO / 解码 / 一次 layout）而不是随机抖动。

**最可疑的三处（按优先级，需在 release 包上验证）**：
1. **站点图标解码时机** —— 225 个图标，若 `SiteIcon` 在组合期同步解码（或从磁盘读），
   滚动/切页时就会周期性打出 100ms+ 的帧 ⇒ **与"150ms 整齐出现"高度吻合**；
2. **滚动时的每帧状态读取** —— 列表顶栏收起用 `rememberScrollCollapseFraction(scrollState)`，
   若它按帧驱动整棵列表重组，会持续制造 5~10% 的 jank；
3. **Tab 切换的 `SaveableStateHolder` + `AnimatedContent`** —— 切 Tab 要重建整棵子树。

> 注：`详情页滚动`那次统计到 0 帧（该条目内容不足一屏、没有可滚动区），属测量无效，非卡顿。

## 4. 本地密码库缓存是否生效 ✅ **生效**

用 `run-as` 读出的实际数据量：

| 载体 | 体积 / 数量 |
|---|---|
| `databases/vaultix.db` | 389 KB，**`ciphers` 表 219 行**（本地缓存的条目）、`folders` 1、`vaults` 1 |
| `databases/vaultix.db-wal` | **437 KB** ⚠️（比主库还大） |
| `pending_ops` 表 | **0 行**（没有积压的待同步写操作 ✅） |
| `cache/image_cache/` | **225 个**站点图标，合计约 2.0 MB |
| `files/fill-assist-cache.json` | 27 KB（填充辅助的站点选择器缓存） |
| `files/datastore/vaultix_settings.preferences_pb` | 333 B（设置） |

结论：
- **列表打开即从本地库渲染**（冷启动 763ms 已经出列表，没有"等网络"的空态）⇒ 缓存链路通；
- 219 条条目 + 225 个图标都在本地，`pending_ops = 0` 说明没有离线写积压。

⚠️ **唯一要处理的小问题**：`vaultix.db-wal` **437KB > 主库 389KB** ⇒ WAL 长期未做检查点。
后果是：每次读都要先过 WAL（略慢），且 WAL 会随写入持续增长。
建议确认 `PRAGMA wal_autocheckpoint` 是否被改过，或在合适的时机（例如同步完成后、
应用退到后台时）显式跑一次 `PRAGMA wal_checkpoint(TRUNCATE)`。

---

## 5. 建议的下一步（按优先级）

1. **【必须先做】用 CI 的 release 包复测同一套命令** —— debug 的数字不能作为优化依据；
2. release 包若仍有 150ms 尖峰 ⇒ 查**站点图标的解码时机**（是否主线程 / 是否限尺寸 / 是否有 LRU）；
3. release 包若 `Private Other` 仍 >40MB ⇒ 查图标位图驻留量；
4. WAL 检查点（低风险、收益明确）；
5. 冷启动 763ms 若想压到 500ms 以内 ⇒ 再考虑 Hilt 聚合、首帧延后加载非必要模块（**优先级最低**，
   因为启动时间已经很稳定，收益不如上面 4 条）。

## 复测命令

```bash
# 冷启动
adb shell am force-stop io.vaultix.vaultix
adb shell am start -W -n io.vaultix.vaultix/.MainActivity

# 内存
adb shell dumpsys meminfo io.vaultix.vaultix

# 卡顿（先 reset，做动作，再读汇总）
adb shell dumpsys gfxinfo io.vaultix.vaultix reset
# …做动作…
adb shell dumpsys gfxinfo io.vaultix.vaultix | grep -E "Total frames|Janky|percentile"
```
