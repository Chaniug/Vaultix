# 附录 A · 依赖矩阵与原始证据（2026-11-11）

> 主报告：[`../project-audit-2026-11-11.md`](../project-audit-2026-11-11.md) §1。
> 本文件只放**大表格与原始证据**，不放结论推演。

## A.1 取证方法（可复现）

| 数据源 | 取法 |
|---|---|
| Maven Central | `https://repo1.maven.org/maven2/<group/path>/<artifact>/maven-metadata.xml` → 解析 `<versions>`，按 `^[\d.]+$` 取 `last_stable`，`latest` / `release` 另存 |
| Google Maven | `https://dl.google.com/dl/android/maven2/<group/path>/<artifact>/maven-metadata.xml`，同上 |
| Gradle | `https://services.gradle.org/versions/current` → `9.9.1` |
| 声明版本 | `tomllib` 解析 `gradle/libs.versions.toml` 的 `[versions]` / `[libraries]` |
| 传递依赖 | 直接 GET 候选版本的 `.pom` 与 `.module`（Gradle Module Metadata） |

⚠️ 临时脚本在 `/tmp/fetch_dep_versions.py`、`/tmp/fetch_poms.py`、`/tmp/scan_standards.py`，
产物 `/tmp/dep_versions.json`、`/tmp/pom_analysis.json`。**均不在仓库内**，复现需重跑脚本。

## A.2 43 项逐条对照

判定口径：`✅ 已最新` = 声明值等于最新稳定版；`🔴 禁止单独升` = 受 #239 硬绑定约束；
`⚠️` = 刻意使用预发布线 / 未核对；`⬆️` = 有更新但需按风险分级处理。

| 版本键 | 当前 | 最新稳定 | 判定 | 备注 |
|---|---|---|---|---|
| `kotlin` | `2.4.21` | `2.4.21` | ✅ 已最新 | KGP / Compose 编译器同源 |
| `ksp` | `2.3.13` | `2.3.13` | ✅ 已最新 | KSP 独立版本线，非与 Kotlin 一一对应 |
| `hilt` | `2.60.1` | `2.60.1` | ✅ 已最新 | 与 Kotlin metadata 强绑定 |
| `agp` | `9.5.0` | `9.5.1` | ⬆️ 同 minor 补丁 | |
| `composeBom` | `2026.06.01` | `2026.09.00` | 🔴 禁止单独升 | 与 material3 硬绑定 |
| `material3` | `1.5.0-alpha16` | `1.4.0` | 🔴 禁止单独升 | stable 反而更旧；预发布 `1.5.0-rc01` 也跨 minor |
| `coreKtx` | `1.19.1` | `1.19.1` | ✅ 已最新 | |
| `lifecycle` | `2.11.0` | `2.11.0` | ✅ 已最新 | alpha05 为最新发布但不取 |
| `activityCompose` | `1.13.0` | `1.13.0` | ✅ 已最新 | |
| `navigationCompose` | `2.10.2` | `2.10.2` | ✅ 已最新 | |
| `fragment` | `1.9.1` | `1.9.1` | ✅ 已最新 | |
| `room` | `2.8.5` | `2.8.5` | ✅ 已最新 | |
| `datastore` | `1.3.0` | `1.3.0` | ✅ 已最新 | |
| `securityCrypto` | `1.1.0` | `1.1.0` | ✅ 已最新 | |
| `biometric` | `1.4.0-alpha07` | `1.1.0` | ⚠️ 刻意预发布线 | stable 更旧，不是「可升级」 |
| `credential` | `1.6.0` | `1.6.0` | ✅ 已最新 | `1.7.0-alpha03` 不取 |
| `workManager` | `2.12.0` | `2.12.0` | ✅ 已最新 | |
| `cameraX` | `1.5.2` | `1.6.2` | ⬆️ 跨 minor | 需真机扫码回归 |
| `hiltNavigationCompose` | `1.4.0` | `1.4.0` | ✅ 已最新 | |
| `retrofit` | `2.12.0` | `3.0.0` | ⚠️ 跨 major | 建议不排期 |
| `okhttp` | `4.12.1` | `5.5.1` | ⚠️ 跨 major | 建议不排期 |
| `retrofitSerialization` | `1.0.0` | `1.0.0` | ✅ 已最新 | |
| `okio` | `3.18.2` | `3.18.2` | ✅ 已最新 | 与 okhttp 成对维护 |
| `coil` | `2.7.0` | `2.7.0` | ✅ 已最新 | 3.x 是另一坐标（最新 `3.6.3`） |
| `kotpass` | `0.13.0` | `0.13.0` | ✅ 已最新 | |
| `bcprov` | `1.86` | `1.86` | ✅ 已最新 | |
| `argon2kt` | `1.6.0` | `1.6.0` | ✅ 已最新 | 上游无更高稳定版 |
| `zxing` | `3.5.4` | `3.5.4` | ✅ 已最新 | |
| `msal` | `8.6.0` | `8.6.0` | ✅ 已最新 | |
| `coroutines` | `1.11.0` | `1.11.0` | ✅ 已最新 | |
| `kotlinxSerializationJson` | `1.12.0` | `1.12.0` | ✅ 已最新 | |
| `kotlinxDatetime` | `0.8.0` | `0.8.0` | ✅ 已最新 | `latest` 为 `0.8.0-0.6.x-compat` 变体，非升级 |
| `collectionsImmutable` | `0.5.2` | — | ⚠️ 未核对 | 本次未抓该坐标元数据 |
| `markdownRenderer` | `0.45.0` | `0.46.0` | 🔴 禁止单独升 | 0.46.0 是分界线 |
| `junit` | `4.13.2` | `4.13.2` | ✅ 已最新 | |
| `truth` | `1.4.5` | `1.4.5` | ✅ 已最新 | |
| `turbine` | `1.2.1` | `1.2.1` | ✅ 已最新 | |
| `mockk` | `1.14.11` | `1.14.12` | ⬆️ 补丁 | 低风险 |
| `orgJson` | `20260719` | `20260814` | ⬆️ 日期版本滚动 | 仅 `testRuntimeOnly` |
| `kover` | `0.9.11` | `0.9.11` | ✅ 已最新 | 但未接 CI，见主报告 §3 |
| `detekt` | `2.0.0-alpha.6` | 无 stable | ⚠️ 预发布线 | 唯一可选版本 |
| `androidx-junit`（硬编码） | `1.2.1` | `1.3.0` | ⬆️ 低风险 | 在 `[libraries]` 里写死，建议收进 `[versions]` |
| `espresso-core`（硬编码） | `3.6.1` | `3.7.0` | ⬆️ 低风险 | 同上；当前无 androidTest 用例 |
| Gradle wrapper | `9.8.2` | `9.9.1` | ⬆️ 需验 detekt 兼容 | 注释仍写 9.5.1 |

**统计**：已最新 29 · 可分级升级 8 · 禁止单独升 3 · 预发布/未核对 3。

## A.3 #239 硬绑定链的原始证据

### A.3.1 `markdown-renderer` 的 Android 变体 POM

```
multiplatform-markdown-renderer-android-0.46.0.pom
  <groupId>org.jetbrains.compose.foundation</groupId>
  <artifactId>foundation-android</artifactId>
  <version>1.12.1</version>

multiplatform-markdown-renderer-android-0.45.0.pom
  <version>1.11.0</version>
```

**易漏判点**：`multiplatform-markdown-renderer-0.46.0.module`（KMP 根元数据）与
`multiplatform-markdown-renderer-m3-0.46.0.module` 里均 **搜不到** `foundation` / `1.12.1`。
依赖只出现在 **Android 变体 POM**。⇒ 静态审查只看根 POM / `.module` 会得出「没有风险」的错误结论，
必须看最终 **runtime classpath 的解析结果**（`dependencyInsight`）。

### A.3.2 各版本 POM 的 Compose 基础库版本

| 工件 | 版本 | 声明的 Compose 基础库 |
|---|---|---|
| `compose-bom` | `2026.03.00` | foundation / ui `1.10.5` |
| `compose-bom` | `2026.06.01`（当前） | `1.11.4` |
| `compose-bom` | `2026.08.00` | `1.12.0`（跨 minor 起点） |
| `compose-bom` | `2026.09.00` | `1.12.1`（崩溃组合） |
| `material3` | `1.5.0-alpha16`（当前） | `1.11.0-beta02` 全家桶 |
| `material3` | `1.5.0-rc01` | `1.12.0` 全家桶（仍跨 minor） |

`compose-bom-2026.09.00.pom` 共 **247** 条依赖声明，全部为 `1.12.1` 系列。

### A.3.3 崩溃形态（历史实测，见 `.ai/issues/06-界面与交互*.md` #239）

```
java.lang.AbstractMethodError: abstract method
  "void androidx.compose.foundation.style.CustomStyle.applyStyle(CustomStyleScope)"
  on receiver androidx.compose.material3.OutlinedTextFieldDefaults$$ExternalSyntheticLambda0
  at androidx.compose.foundation.style.ResolvedStyle.build(ResolvedStyle.kt:109)
```

真机荣耀 BKQ-AN00 / SDK 37，**一进含 `OutlinedTextField` 的页面即崩**，清 App 数据无效。
`grep -rl CustomStyle` 扫 alpha16 的 `classes.jar` 为 0 命中 ⇒ 编译期不存在该类，运行期被定为抽象方法。

## A.4 其它候选 POM 摘录

| 工件 | POM 依赖要点 | 结论 |
|---|---|---|
| `retrofit:3.0.0` | `com.squareup.okhttp3:okhttp:4.12.0`、`kotlin-stdlib:2.1.21` | 跨 major 却仍绑 4.x OkHttp，与项目 5.x 升级方向不一致 |
| `okhttp:5.5.1` | `kotlin-stdlib:2.1.21`；属性 `okio 3.18.1` | 项目锁 `okio 3.18.2`，需同步核对 |
| `retrofit2-kotlinx-serialization-converter:1.0.0` | `retrofit:2.9.0`、`serialization-core:1.5.0`、`kotlin-stdlib-jdk8:1.8.10` | 声明基于 Retrofit 2.x，跨 major 组合无验证 |
| `camera-camera2:1.6.2` | 新增 `core-backported-fixes:1.0.0`、`atomicfu:0.28.0`；`coroutines:1.9.0` | 与项目 `1.11.0` 冲突仲裁，又一处「解析 ≠ 声明」 |
| `biometric:1.4.0-beta01` | `biometric-compose`、`kotlin-stdlib:2.2.20` | 同 minor 递进，但需真机指纹回归 |
| `security-crypto:1.1.0` | 无显式 deps | 已是稳定版 |
| `androidx.test.ext:junit` | `1.3.0` 为最新稳定 | 低风险 |
| `espresso-core` | `3.7.0` 为最新稳定 | 低风险 |

## A.5 版本注释漂移原文

```
gradle/wrapper/gradle-wrapper.properties:3-5
  # AGP 9.3.2 要求最低 Gradle 9.5.0，官方默认即 9.5.0；取 9.5 线补丁 9.5.1。
  # 未取更新的 9.7.x：Gradle 小版本间出现过 R8/RecordTag 类加载断裂类问题，
:6 distributionUrl=https\://services.gradle.org/distributions/gradle-9.8.2-bin.zip

gradle/libs.versions.toml:5-12
  # Kotlin 停在 2.3.21。升 2.4.x 会被 KSP 阻塞：KSP 至今只发到 2.3.11 ...
  # Kotlin 2.4.10（当前最新稳定版）。
  # 注：Bastion 的注释称「升 2.4.x 会被 KSP 阻塞」，实测该结论已过时。
  kotlin = "2.4.21"

gradle/libs.versions.toml:199-210
  # Kover：行覆盖率工具。选 0.9.9（当前最新稳定版）
  kover = "0.9.11"
  # 官方兼容表 2.0.0-alpha.6 = Gradle 9.6.1 / Kotlin 2.4.10 / AGP 9.3.1 / JDK25
  detekt = "2.0.0-alpha.6"   ← 实际 Kotlin 2.4.21 / AGP 9.5.0 / Gradle 9.8.2，CI 仍绿
```

## A.6 供应链检查

| 检查项 | 结果 |
|---|---|
| 动态版本（`+` / `latest.release` / `SNAPSHOT` / `changing=true`） | **0 命中**（`libs.versions.toml` + 全部 `build.gradle.kts` + `.github/workflows/*.yml`） |
| 非官方 Maven 仓库 / 远程脚本执行 / 未固定 commit | 0 命中 |
| 已知传递风险 | MSAL 遥测链 `opentelemetry-api 1.62.0` ∈ GHSA-ccrj-gw8r-854m，已用 `constraints` 顶到 `1.67.0`，未改上游传递树 |
| 未覆盖 | 全量 CVE 比对（本轮只核已知一条） |
