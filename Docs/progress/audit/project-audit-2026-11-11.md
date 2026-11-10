# Vaultix 项目整体排查报告（2026-11-11 凌晨）

> **范围**：依赖升级盘点 · Android 编码规范符合性 · 优化完善项 · 稳定性 / 性能提升方案与实现路径。
> **口径**：静态审查。`libs.versions.toml` / wrapper 的**声明** vs Maven Central / Google Maven /
> `services.gradle.org` 的**发布元数据**；源码按 `ai-code-standards` 适用性裁剪后全仓扫描，
> 依赖来源按 `skills-security-check` 的供应链思路核对。
> **未做**：真机回归、`assemble`、APK 体积实测、`dependencyInsight` 实跑（沙箱无 Android SDK / Gradle）。
> 凡涉及「运行时是否兼容」的结论，均标注需在哪一步验证，不冒充已验证。

---

## 0. 结论速览

| 维度 | 结论 | 量级 |
|---|---|---|
| 依赖 | **43 个 Maven 坐标 + Gradle wrapper** 中 **29 项已是最新稳定版**；真正「落后」的很少，问题不在版本旧，而在**三项绝对不能单独动** | 6 项低风险可升 / 3 项禁动 / 4 项需立项 / 2 项预发布线 |
| 规范 | 真违规集中在 **3 类**，且都在生产代码 | 日志绕过统一门面 3 处、生产 `!!` 3 处、裸 `catch` 18 处 |
| 规范误报 | 启发式扫描的大数字**绝大多数是误报或口径差异**，不能当整改清单 | 159 条「注释缺空格」≈ URL 中 `//` 误判；52 条「硬编码密钥」= 51 测试向量 + 1 字段名 |
| 质量基建 | **Baseline Profile 缺失、Kover 未接 CI、Gradle 配置缓存未启用** | 3 项，均为可交付工程 |
| 文档/记忆 | 版本注释漂移 3 处；`.ai/ISSUES.md` 条数漂移 1 处（本轮已修） | 4 处 |
| 供应链 | 干净：`dynamic_version = 0`，无非官方仓库 / 远程脚本 / 未固定 commit | ✅ |

> **一句话**：这个项目的依赖管理比看起来健康得多 —— 大半已经最新，#239 的真机崩溃不是「没升级」，
> 是「只升了一半 + 传递依赖顶版本」。因此最高价值的动作**不是升级，是把「依赖解析结果」纳入门禁**。

---

## 1. 依赖升级盘点

### 1.1 可直接排期（低风险）

| 项 | 当前 → 目标 | 风险 | 验收 |
|---|---|---|---|
| `mockk` | `1.14.11` → `1.14.12` | 测试依赖，补丁 | CI 单测全绿 |
| `orgJson` | `20260719` → `20260814` | 仅 `testRuntimeOnly`，日期版本号滚动 | `UnitTestEnvironmentTest` 仍绿 |
| `androidx-junit`（`[libraries]` 内硬编码） | `1.2.1` → `1.3.0` | 测试依赖 | 建议同时收进 `[versions]` 消除硬编码 |
| `espresso-core`（同上硬编码） | `3.6.1` → `3.7.0` | 测试依赖，当前无 androidTest 用例 | 同上 |
| `agp` | `9.5.0` → `9.5.1` | 同 minor 补丁 | `assembleFullDebug` + lint |
| Gradle wrapper | `9.8.2` → `9.9.1` | 注释与实际已脱节，见 §1.4 | 全量 CI，重点看 detekt |

> ⚠️ Gradle 一项需先验证：`detekt 2.0.0-alpha.6` 的官方兼容表写的是 **Gradle 9.6.1**，
> 而项目实际已跑在 **9.8.2**（CI 绿）⇒ 兼容表已过期。升 9.9.1 前用
> `./gradlew detekt --configuration-cache` 单独试一次，不要整条 CI 一起推。

### 1.2 需真机回归才动（中风险）

| 项 | 当前 → 目标 | 为什么不是「顺手升」 |
|---|---|---|
| `cameraX` | `1.5.2` → `1.6.2` | 跨 minor。`1.6.2` POM 新带 `androidx.core:core-backported-fixes:1.0.0`、`atomicfu:0.28.0`，且声明 coroutines `1.9.0`（会被项目 `1.11.0` 顶掉 ⇒ 又是一处「解析结果 ≠ 声明」）。扫码页需真机验证 |
| `biometric` | `1.4.0-alpha07` → `1.4.0-beta01` | 同 minor 递进，但**最新 stable 是 `1.1.0`（更旧）**，版本目录注释已写明刻意留在 alpha 线。涉及指纹解锁与 Keystore，必须真机回归 |

### 1.3 🔴 禁止单独升级（#239 真机闪退链）

这三项是**同一个约束**，不是三个独立升级：

| 版本键 | 当前 | 最新 | 实际绑定 |
|---|---|---|---|
| `composeBom` | `2026.06.01`（foundation **1.11.4**） | `2026.09.00`（**1.12.1**） | 与 `material3` 硬绑定 |
| `material3` | `1.5.0-alpha16`（针对 **1.11.0-beta02** 编译） | stable `1.4.0` / 预发布 `1.5.0-rc01`（foundation **1.12.0**） | 与 `composeBom` 硬绑定 |
| `markdownRenderer` | `0.45.0`（foundation **1.11.0**） | `0.46.0`（**1.12.1**） | 0.46.0 是分界线 |

**本轮新增的硬证据**（此前只记在 `.ai` 里，这次直接抓了产物）：

```xml
<!-- multiplatform-markdown-renderer-android-0.46.0.pom -->
<groupId>org.jetbrains.compose.foundation</groupId>
<artifactId>foundation-android</artifactId>
<version>1.12.1</version>
```

而 `0.45.0` 的对应位置是 `1.11.0`。**关键方法论**：这个依赖**只出现在 Android 变体 POM 里** ——
KMP 根 `*.module` 与 `-m3` 的 `*.module` 里都搜不到 `foundation` / `1.12.1`。
⇒ 只看根 POM 或 Gradle Module Metadata 会**漏判**，必须看最终解析出的 runtime classpath。

后果（#239 实测，荣耀 BKQ-AN00 / SDK 37）：`AbstractMethodError: CustomStyle.applyStyle`
落在 `OutlinedTextFieldDefaults`，**一进含 `OutlinedTextField` 的页面即崩**，清数据无效。
另外 `material3 alpha27` 会引发 **70 处编译失败**（`menuAnchor()` 无参重载被移除），属业务代码改造。

**结论**：三项必须作为**一个专项**一起动（bom + material3 + markdownRenderer + Compose 业务代码），
且开工第一步是 `dependencyInsight` 而非改版本号。当前组合是刻意冻结的知情选择，**不是技术债**。

### 1.4 版本注释漂移（文档债，会误导下一次升级）

| 文件 | 注释说 | 实际 |
|---|---|---|
| `gradle/wrapper/gradle-wrapper.properties:3-5` | 「取 9.5.1」「未取更新的 9.7.x」 | `distributionUrl` 是 **9.8.2** |
| `gradle/libs.versions.toml:3` | 「AGP 9.3.2 要求最低 Gradle 9.5.0，取 9.5.1」 | `agp = "9.5.0"`，Gradle 9.8.2 |
| `gradle/libs.versions.toml:5-11` | 「Kotlin 停在 2.3.21，升 2.4.x 会被 KSP 阻塞」与「Kotlin 2.4.10 是当前最新」并存，第 8 行又说该结论已过时 | `kotlin = "2.4.21"`（三处口径打架） |
| `libs.versions.toml:199-210` | Kover「选 0.9.9（当前最新稳定版）」；detekt 兼容表「Kotlin 2.4.10 / AGP 9.3.1 / Gradle 9.6.1」 | `kover = "0.9.11"`；实际 Kotlin 2.4.21 / AGP 9.5.0 / Gradle 9.8.2 |

> 这类注释不产生编译错误，但会让人按错误前提做升级决策（#239 就是这么来的）。
> 建议：把「**为什么冻结**」留在 `.ai`，版本目录里只留「**当前值 + 唯一约束**」。

### 1.5 跨 major 升级：建议不排期

`retrofit 3.0.0` POM 反而依赖 `okhttp:4.12.0`；`okhttp 5.5.1` 带 `okio 3.18.1`（项目锁 `3.18.2`）；
`coil3 3.6.3` 是**新坐标 + API 变更**。网络层只服务 `data:bitwarden`、Graph 调用手写 OkHttp，
**无明确收益驱动** ⇒ 保持观察，待安全公告或明确 bug 再立项。

### 1.6 供应链（借鉴 `skills-security-check` 审计思路）

- 动态版本 / `SNAPSHOT` / `latest.release` / `changing = true`：**0 命中**（扫 `libs.versions.toml`、全部 `build.gradle.kts`、workflows）。
- 仓库来源：标准 Google Maven / Maven Central；未发现远程脚本执行、未固定 commit 的远程依赖。
- 已知传递风险已处置：MSAL 遥测链带入的 `opentelemetry-api 1.62.0` 落在 GHSA-ccrj-gw8r-854m
  受影响区间，项目用 `constraints` 顶到 `1.67.0`，且**没有改 MSAL 传递树** —— 做法正确，保持。
- 待补：把「依赖解析结果」而非「声明版本」写进 CI 断言，见 §4。

---

## 2. Android 编码规范符合性

### 2.1 适用性裁剪（先说清楚，否则整改会变成灾难）

`ai-code-standards` 是 **Java / 传统 View 时代**的团队强制规范。本项目是
**纯 Kotlin + Compose + 多模块 + Hilt/Room**，以下条款**不适用**，强行套用只会制造无意义 diff：

| 规范条款 | 项目实际 | 处置 |
|---|---|---|
| 成员变量 `m` 前缀、布尔 `is` 前缀 | Kotlin 属性 / `State<T>` / `derivedStateOf` | 登记例外，不批量改名 |
| ViewBinding / RecyclerView / ConstraintLayout / Glide | Compose `LazyColumn` / Coil | 不适用 |
| Gson / FastJson 解析、AsyncTask、`ActivityTestRule` | kotlinx-serialization / 协程 / Robolectric 式 JVM 单测 | 不适用 |
| Git Flow + PR Review | 单人项目，直接 push `main`，15 个静态门禁 + CI 双 job 替代 | 登记例外 |
| **注释只写 what，禁写 why** | `.ai` 记忆与源码 KDoc 刻意保存 why（#239 崩溃根因、#236 死循环） | **不删历史记忆**；新增代码按规范写 what，历史 why 保留并登记例外 |
| 覆盖率 ≥80% | Kover 只在根 `apply false`，未接 CI，无法证明 | 见 §4 · S5，先建基线再逐步提 |

> 原则：**新代码按规范写，存量登记例外、按风险分批动**。
> 项目自己在 `.ai/conventions/8.6` 里已有「不要为了干净删历史记忆」的教训，本轮沿用。

### 2.2 真违规（生产代码）

| 级别 | 项 | 位置 | 说明 |
|---|---|---|---|
| **P0** | 绕过统一日志门面 | `QuickUnlockController.kt:867`、`UnlockViewModel.kt:926/958/960/962`、`SettingsComponents.kt:460` | `VaultixLog.install(enabled = BuildConfig.DEBUG)` 是唯一出口，release 下门面在拼串前就返回。这 6 处直接 `Log.d/w` ⇒ **release 仍输出 DEBUG/WARN**，违反规范 §15「生产环境禁止输出 DEBUG」「使用统一日志工具类」；`reseal $vaultId → …` 还把库标识写进 logcat。另有 `AutofillLogger.kt` 2 处有 `BuildConfig.DEBUG` 保护（半合规）、`VaultixApplication.kt` 3 处是门面实现（合规） |
| **P1** | 生产代码 `!!` | `PasskeyCreateActivity.kt:509`、`PasskeyGetActivity.kt:495`、`KdbxSyncOrchestrator.kt:198` | 凭据创建 / 断言签名 / 同步编排三条关键路径，空值会直接 NPE 崩溃。测试里的 222 处 `!!` 是断言习惯，不计 |
| **P1** | 裸 `catch (Exception/Throwable)` | 18 处（`Kdbx.kt` 4、`Totp.kt` 4、`CachedKdbxFileSource.kt` 等） | 规范 §5「禁止直接 catch 基类，如有必要须提示用户确认」。项目多处用于「解析/加解密失败统一转用户可读错误」，**边界层保留有合理性**，但需逐处分级 + 显式登记 |
| **P2** | 阻塞式退避 | `OneDriveGraphClient.kt:429/604/794` | `Thread.sleep(500)` 在 `withContext(Dispatchers.IO)` 调用链内 ⇒ **不阻塞主线程**，但占住 IO 线程，且不可取消。见 §4 · S4 |
| **P2** | 单行 169 字符 | `FullScreenDialogShell.kt:119` | KDoc 里的 developer.android.com 链接，超 120。建议改成 Markdown 引用式链接或折行 |
| **P2** | 超大类 / 超长方法 | 见 §3 | 与项目现行门禁口径不同，见 §2.3 |

### 2.3 口径差异：不要把「扫描数」说成「违规数」

| 项 | 团队规范 | 项目 detekt 门禁 | 本次启发式扫描 | 实情 |
|---|---|---|---|---|
| 行宽 | 120 | 默认 120 | 1 处（169） | 一致 |
| 方法长度 | 80 行 | **150 行**（`allowedLines`） | >80：131；>150：29 | 脚本按「下一个 `fun` 行距」算，**含 KDoc / 空行**；detekt 只数代码行（`.ai/conventions/8.6` 已实测）。29 这个数字只能当定位线索 |
| 类长度 | 800 行 | **1200 行** | >800：9；>1200：6 | 其中 `PublicSuffixData.kt` 10,344 行是 **PSL 自动生成数据**，文件头已有 `@file:Suppress("LargeClass")`，属正当豁免 |

> **CI 口径 `detekt` 当前是绿的**。两套阈值不一致是「团队规范 vs 项目 `Docs/16` 纪律」的历史分歧，
> 建议**登记例外**，不要为了对齐 80/800 去拆 131 个方法 —— 那会重演 `.ai` 里记过的
> 「抽类对函数数减益比预期小 / 抽 composable 凭记忆写类型名炸 CI」。

### 2.4 误报裁剪（这些**不是**整改项）

| 命中 | 数 | 判定 |
|---|---|---|
| 注释缺空格 | 159 | 规则 `//\S` 命中 `file://` / `content://` / `androidapp://` 等 URL 中的 `//`，几乎全是误判 |
| 硬编码密钥 | 52 | 51 条是 RFC 6238 TOTP 测试向量；1 条生产命中是 KDBX **字段名常量** ⇒ 无线上密钥泄露 |
| `System.out` | 2 | 均为 KDoc 里的反面示例；`printStackTrace` 0 |
| `runBlocking` | 95 | 82 条测试；生产仅 2 处真实调用，是 OkHttp Authenticator 同步回调的框架边界（`Dispatchers.IO` + `@Suppress("InjectDispatcher")`）⇒ 合规 |
| `GlobalScope` / 明文 HTTP | 0 / 1 | 1 条是测试注释举例 |

完整分类明细与脚本口径见 [`evidence/standards-scan-2026-11-11.md`](evidence/standards-scan-2026-11-11.md)。

---

## 3. 需要优化完善

| # | 项 | 现状证据 | 影响 |
|---|---|---|---|
| 1 | **Baseline Profile 缺失** | 全仓 `find -iname "*baseline*prof*"` 零结果；`next-steps.md` P1 待办里也标着「未逐项核对」 | 冷启动 / 列表滚动无 ART 预编译引导，Compose 首次组合代价全额付 |
| 2 | **Kover 未接 CI** | 仅根 `build.gradle.kts:16` 有 `alias(libs.plugins.kover) apply false`；无模块应用、无 `koverVerify` 步骤 | 规范「覆盖率 ≥80%」无法证明，也没有回归基线 |
| 3 | **Gradle 配置缓存未启用** | `gradle.properties` 无 `org.gradle.configuration-cache`；CI 每条 gradle 命令都带 `--no-configuration-cache` | 每次 CI 重新配置 10+ 模块，`detekt 2.0.0-alpha.6` 是已知阻塞点 |
| 4 | **版本注释漂移** | §1.4 四处 | 误导升级决策 |
| 5 | **AI 记忆数字漂移** | `.ai/ISSUES.md:17` 写 71，06 篇题注与分节标题写 73，实测 `## NN.` 也是 **73** | 项目反复踩的「同一事实存两份」老毛病；本轮已修 |
| 6 | 超大类 | `ItemsScreen` 2149 行、`QuickUnlockController` 1314、`OneDriveGraphClient` 1234、`VaultRepositoryImpl` 1226 | 可维护性；但按现行 1200 门禁只有 4 个真超标（PSL 生成文件已豁免） |
| 7 | 注释「why」存量 | 全仓大量解释性 KDoc | 不删（项目资产），但需登记例外，避免每次审查重复判违规 |

**已经做对、不要动的**（避免「优化」变回退）：`allowBackup="false"`、release
`isMinifyEnabled + isShrinkResources`、Room `exportSchema = true` + `addMigrations`、
Coil 全局 `respectCacheHeaders(false)` + `crossfade(false)`、三处 OkHttp 超时、
`VaultixLog` 的 DEBUG-only 装配、`PublicSuffixData` 的 64KB 分块。

---

## 4. 稳定性提升方案

按「投入产出比」排序，**S1 是最高价值项** —— #239 的本质是编译 / 单测 / detekt 全绿，
**真机一进输入框就崩**，而现有 15 个静态门禁全都只看「声明」，看不到传递依赖顶版本。

| 编号 | 方案 | 一句话实现 | 验收 |
|---|---|---|---|
| **S1** | 依赖解析结果纳入门禁 | CI 跑 `:app:dependencies --configuration fullDebugRuntimeClasspath`，断言 foundation 与 material3 同 minor，`By conflict resolution` 命中即报警 | 临时把 bom 改到 `2026.09.00` 必须红（变异验证） |
| **S2** | 日志门面收敛 | 6 处直接 `Log` 改 `VaultixLog`，加白名单静态门禁 | release logcat 无 DEBUG 行 |
| **S3** | 生产 `!!` 清零 | 3 处改显式分支 + 各补 1 条空值单测 | 生产命中数 = 0 |
| **S4** | 上传退避可取消 | 3 处 `Thread.sleep(500)` → `delay()` + `ensureActive()`，**保留** `MAX_STALLS` 与 412/401 语义 | 现有分片测试全绿 + 新增取消用例 |
| **S5** | 覆盖率从 0 到有基线 | Kover 应用到模块 + `koverVerify` 阈值先取当前实测值 | 基线入 CI，再按季上调 |
| **S6** | 关 DEBUG 后补可观测性 | 用户主动导出的**脱敏诊断包**（禁密码 / token / 明文 / 完整 origin） | 红线沿用 `VaultixLog` KDoc |

---

## 5. App 性能提升方案

| 编号 | 方案 | 一句话实现 | 验收 |
|---|---|---|---|
| **P1** | Baseline Profile | 生成 `baseline-prof.txt`，覆盖冷启动 → 解锁 → 列表滚动 → 详情 + autofill 单独一条 | `StartupTimingMetric` 的 `timeToInitialDisplayMs` 中端机 P50 降 10%+ |
| **P2** | 冷启动初始化分层 | `onCreate()` 串行 7 件事分层：防截屏**必须第一**、KDF 缓存**必须早于解锁**，其余用 App Startup 延迟；UI 与 autofill 走不同初始化集 | 冷启动 trace；⚠️ 不得退回构造期自启（单测虚拟时钟会失效） |
| **P3** | Gradle 构建性能 | 先验 `detekt --configuration-cache`；可用则开配置缓存，不可用则把 detekt 拆独立 job；Kover 报告只放 nightly | 配置缓存命中、CI 时长对比 |
| **P4** | Compose 重组与列表 | 全仓 `derivedStateOf` 仅 2 个文件 ⇒ 排查筛选/排序/分组变化引起的整屏重组；逐个审计 10 处 `LazyColumn key` | Layout Inspector 重组计数 + 「切换分组前后条目总数不变」断言 |
| **P5** | PSL 数据加载时机 | `PublicSuffixData.kt` 10,344 行分块运行时拼接；确认是否在 `object` 初始化全量拼接，是则改 `lazy {}` | autofill 冷启动不触发 PSL 拼接 |
| **P6** | **不要重复造轮子** | Coil 缓存策略、三处 OkHttp 超时 + `pingInterval`、release R8 均已配置完毕 | 本轮不改 |

> S1/S2/P1/P2 的逐步施工单、改动点定位、验收脚本与变异验证设计见
> [`evidence/stability-performance-plan-2026-11-11.md`](evidence/stability-performance-plan-2026-11-11.md)。

---

## 6. 实现路线

| 阶段 | 内容 | 主要改动 | 验收 | 回滚 |
|---|---|---|---|---|
| **0 · 止血**（本轮可做） | S2 日志收敛、S3 `!!` 清零、修版本注释漂移、`.ai/ISSUES.md` 数字 | 3 个 kt + 2 个静态门禁 + `libs.versions.toml` 注释 | CI 双 job 绿 + 15 个 `check_*.py` 全 PASS + release logcat 无 DEBUG | 单文件 revert，无数据迁移 |
| **1 · 门禁**（最高价值） | S1 依赖解析对齐门禁 + 变异验证 | `.ai/tools/check_compose_alignment.py` + `ci-debug.yml` 一步 | 故意改 bom 必须红，改回必须绿 | 移除 CI 步骤即可 |
| **2 · 低风险升级** | §1.1 六项（测试依赖 + AGP 补丁 + Gradle） | `libs.versions.toml` + `gradle-wrapper.properties` | 全量 CI + 一次真机装包 | 版本目录回退 |
| **3 · 质量基建** | P1 Baseline Profile、S5 Kover、P3 配置缓存 | 新模块 / `build.gradle.kts` / `gradle.properties` | 启动耗时对比报告；覆盖率基线入 CI；配置缓存命中 | 特性开关 / 插件可 `apply false` |
| **4 · 专项**（需单独排期） | S4 上传退避、P2 初始化分层、P4 重组、P5 PSL 懒加载、§1.3 三项依赖一起动 | 多处业务代码 | 各自单测 + 真机回归 | 分批提交，每批独立可回退 |

> **排期建议**：0 和 1 先做（几乎零风险，且 1 能防住下一次 #239）；
> 2 跟在 1 之后（升级前先有护栏）；3/4 按真机反馈排。
> **§1.3 的三项依赖专项不要塞进任何一次「顺手升级」**。

---

## 7. 验收清单与未验证项

**已完成**：43 个坐标元数据核对（Central 25 + Google 18）、候选 POM / `.module` 抓取、
539 个 `.kt` 全仓扫描、15 个静态门禁与 CI 配置核对、`.ai` 记忆核对。

**明确未验证**（不要写成「已通过」）：

- [ ] 未跑 `./gradlew :app:dependencyInsight`，§1.3 的解析结论来自 POM 证据 + `.ai` 里 #239 的历史实测；
- [ ] 未跑 `assemble` / `lint` / `detekt`（沙箱无 SDK），报告中「CI 绿」指**上一轮已完成的 CI 结论**；
- [ ] 未跑真机，相机 / 指纹 / 冷启动 / 重组计数的所有改善幅度均为**待采集**；
- [ ] 未做 CVE 全量比对，仅核对了已知的 `opentelemetry-api` 一条；
- [ ] `collectionsImmutable 0.5.2` 未抓元数据（该坐标不在本次抓取清单），标为**未核对**。

---

## 附录索引

| 文件 | 内容 |
|---|---|
| [`evidence/dependency-matrix-2026-11-11.md`](evidence/dependency-matrix-2026-11-11.md) | 43 项依赖逐条对照、POM / `.module` 原始证据、抓取方法 |
| [`evidence/standards-scan-2026-11-11.md`](evidence/standards-scan-2026-11-11.md) | 扫描脚本口径、19 类命中统计、生产/测试拆分、误报裁剪明细 |
| [`evidence/stability-performance-plan-2026-11-11.md`](evidence/stability-performance-plan-2026-11-11.md) | S1/S2/P1/P2 的逐步施工单、改动点定位、验收脚本、变异验证设计 |

> 接力文档：`.ai/README.md`「🕐 最新状态」；待办真源：`Docs/progress/next-steps.md`。
