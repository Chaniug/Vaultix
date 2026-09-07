# 依赖升级方案（2026-08）

> 生成日期：2026-08-29，基于上游 maven-metadata / POM 实测核对。
> 分支：`dev`。
>
> **执行状态（2026-08-29 当日更新）**
> - **P0 已完成并合入** —— commit `a143f4f`，CI run `33240303643` 绿。
> - **P1 已实测失败并回退** —— commit `a143f4f` 升级后 CI run `33239556904` **编译失败（70 处错误）**，
>   已由 commit `2150a1e` 回退。失败原因不是 motion 回归，而是 material3 alpha 线的
>   **破坏性 API 变更**，详见第 3 节的「实测结果」。

---

## 0. 结论摘要

| 批次 | 内容 | 风险 | 建议 |
|---|---|---|---|
| **P0** | 删 `slice-core` / `slice-builders` 死依赖 | 无 | **✅ 已完成**（`a143f4f`） |
| **P1** | `compose-bom` + `material3` **捆绑**升级 | **高** | **❌ 已回退**（`2150a1e`）—— 实测编译失败，需先做 API 迁移，见第 3 节 |
| **P2** | `AGP 9.3.2 → 9.4.0` | 中高 | **暂缓**，先确认 Gradle 要求 |
| **P3** | accompanist / sardine / scrypt / credentials 清理替换 | 低~高 | 拆开单独评估 |
| — | Kotlin 2.3.21 | — | **不升**（被 KSP 阻塞） |

**性能预期：本轮升级不解决性能问题。** 收益是安全、兼容性与可维护性。
真正的性能杠杆是 Compose 重组优化，见第 6 节。

---

## 1. 核心发现：compose-bom 与 material3 是硬绑定关系

这是本轮最重要的发现，也是此前 `bd8591b` 静默回退 compose-bom 的**真正原因**。

### 实测版本依赖链

| 组件 | 锁定/依赖的 Compose 版本 |
|---|---|
| `compose-bom 2026.03.00`（当前） | runtime / foundation / ui / ui-text / foundation-layout = **1.10.5** |
| `compose-bom 2026.08.00`（最新） | 同上 = **1.12.0** |
| `material3 1.5.0-alpha16`（当前） | 依赖 runtime/foundation/ui-text/foundation-layout = **1.11.0-beta02** |
| `material3 1.5.0-alpha27`（最新） | 依赖同上 = **1.12.0-beta01** |

### 由此得出的三个推论

**① 当前项目并未跑在 BOM 声称的版本上。**
BOM 锁 `1.10.5`，但 material3 alpha16 要求 `1.11.0-beta02`。Gradle 冲突解决取高者，
**实际生效的 Compose runtime 是 `1.11.0-beta02`（beta 线）**，BOM 对
runtime/foundation/ui 的约束已被 material3 架空。**BOM 的"兼容性保证"实际未生效。**

**② 只升 BOM 会炸。**
若 BOM 升到 `2026.08.00`（1.12.0）而 material3 仍锁 alpha16（要 1.11.0-beta02），
解析结果会是 **1.12.0**——而 alpha16 的字节码是针对 `1.11.0-beta02` 编译的，
跨一个 minor 跑在 1.12.0 上属**二进制不兼容**，典型症状是
`NoSuchMethodError` / `NoClassDefFoundError`，或 Compose 运行时行为异常。

**③ 这就是 `bd8591b` 静默回退的原因。**
该提交把 `composeBom` 从 `2026.08.00` 改回 `2026.03.00`，提交信息与文件注释均**未说明原因**。
现在证据链闭合：不是误操作，而是单独升 BOM 后 material3 alpha16 不兼容，
被迫回退到能配得上 alpha16 的 BOM 版本。

### 结论

> **`compose-bom` 与 `material3` 必须捆绑升级，任何单独升一个的操作都会失败或埋雷。**

---

## 2. P0 — 删除死依赖（立即，零风险）

`androidx.slice:slice-core` 与 `androidx.slice:slice-builders`（均 `1.1.0-alpha02`）

- **全仓库 0 处 import**（已用 `androidx.slice` 精确匹配 `app/src` 全部源码，无命中）。
- `app/build.gradle` 注释写的 "Slice (for InlinePresentation)" **已过时**：
  autofill 的 `InlinePresentation` 来自 `androidx.autofill`，与 Slice 无关。
- 上游 1.1.0-alpha02 后已停更。

**改动**：删除 `app/build.gradle` 中两行 `implementation`，删除 catalog 中
`slice` 版本与两个 library 条目。

**验证**：`./gradlew :app:assembleDebug` + 单测基线闸门保持 `failed=0`。

**✅ 状态**：已完成（commit `a143f4f`）。CI run `33240303643`：
`build_failed=false`、`total=655 failed=0 baseline=0 verdict=PASS`。

---

## 3. P1 — compose-bom + material3 捆绑升级（中风险，收益最大）

### 改动

```toml
# Bastion/gradle/libs.versions.toml
-composeBom = "2026.03.00"
+composeBom = "2026.08.00"
-material3Expressive = "1.5.0-alpha16"
+material3Expressive = "1.5.0-alpha27"
```

`material3-window-size-class` 复用同一变量，自动跟随，无需额外改动。

### 升级后解析结果

- Compose runtime / foundation / ui = **1.12.0**（BOM）
- material3 = **1.5.0-alpha27**（显式声明覆盖 BOM 的 1.4.0）
- alpha27 要求 1.12.0-beta01，1.12.0 > 1.12.0-beta01，最终取 **1.12.0**
- beta01 → 正式版 API 已冻结，风险远低于当前的 alpha16 跨 minor 场景

### ⛔ 实测结果（2026-08-29）：编译失败，共 70 处错误

按上述改动实际执行（commit `a143f4f`），CI run `33239556904` 在
`Build Debug APK (build gate)` 挂掉。**不是 motion 回归，是编译不过。**

两类错误，均为 material3 1.5.0-alpha 线的**破坏性 API 变更**：

| 错误 | 处数 | 说明 |
|---|---|---|
| `No value passed for parameter 'type'` | **50** | `Modifier.menuAnchor()` 的无参重载被移除，必须显式传 `ExposedDropdownMenuAnchorType` |
| `Unresolved reference 'ExposedDropdownMenu'` | **20** | `ExposedDropdownMenu` 组件引用失效（连带引发 `@Composable invocations can only happen from...` 报错） |

**涉及的业务文件**（均使用 `ExposedDropdownMenu` 系列组件）：

- `ui/components/PasswordEntryPickerBottomSheet.kt`
- `autofill_ng/AutofillPickerActivityV2.kt`（4 处）
- `ui/screens/AddEditWifiScreen.kt`
- `ui/screens/AutofillSettingsV2Screen.kt`
- `ui/screens/AddEditTotpScreen.kt`
- `ui/screens/AddEditSshKeyScreen.kt`
- `bitwarden/ui/BitwardenLoginScreen.kt`
- 及其他（日志截断，总数 70 处）

**结论：升 alpha27 不是"改两行版本号"，而是一次涉及 70 处的业务代码迁移。**
已回退（commit `2150a1e`），并将该事实固化进 catalog 注释，避免重复踩坑。

### 若仍要升级 alpha27，前置条件

1. **先完成 `ExposedDropdownMenu` 系列 API 迁移**（70 处，独立改造项，建议单独排期）
   - `menuAnchor()` → `menuAnchor(type = ExposedDropdownMenuAnchorType.…)`（需按每处语义选 PrimaryEditable / PrimaryNotEditable）
   - 确认 alpha27 中 `ExposedDropdownMenu` 的新名称/位置后逐处替换
2. 迁移完成、编译通过**之后**，才进入下面的 motion 验证
3. 注意：alpha 线后续版本仍可能再次 break，这是长期维护成本

### 风险（motion 回归，迁移完成后仍需验证）

当初锁死 alpha16 的理由是防 UI 回归：

> `b97b39e` 回退 Expressive 导航项改造 → `e6047c3` 显式 `MotionScheme.standard()`
> 修掉底部导航切 tab 图标闪烁 → 刻意锁 alpha16 防复发

alpha16 → alpha27 跨 **11 个 alpha**，motion 行为会变，有较高概率复现该闪烁问题。

### 验证清单

1. `./gradlew :app:assembleDebug` 编译通过
2. `./gradlew :app:testDebugUnitTest` 基线闸门 `failed=0`
3. **真机验证**（不可跳过）：
   - 底部导航 / 侧边导航切换 tab，确认图标无缩放放大、无闪烁
   - 确认 `MotionScheme.standard()` 仍生效（无 Expressive 回弹）
   - 页面切换过渡动画正常
   - 深浅色主题、动态取色正常

### 回滚

仅两行 catalog 版本，改回即可，无代码改动。

---

## 4. P2 — AGP 升级（暂缓，先确认前置条件）

### 现状与上游

- 当前 `agp = 9.3.2`，上游最新稳定 **9.4.0**（9.5.0 仍处 alpha03）。
- Gradle wrapper 当前 **9.5.1**。

### 阻塞点

官方 AGP/Gradle 对应表（lastUpdated 2026-06-17，**AGP 9.4 发布于 2026-07-22，表尚未覆盖**）：

| AGP | 最低 Gradle |
|---|---|
| 9.0 | 9.1.0 |
| 9.1 | 9.3.1 |
| 9.2 | 9.4.1 |
| 9.3 | 9.5.0（项目注释佐证） |
| **9.4** | **官方表未更新，待确认** |

按既有趋势（每个 AGP minor 带动约 1 个 Gradle minor）推断，
**AGP 9.4 大概率要求 Gradle 9.6+ / 9.7+**。

而 wrapper 注释明确写着：

> 未取更新的 9.7.1：Gradle 小版本间出现过 R8/RecordTag 类加载断裂类问题，
> 优先用 AGP 官方配对验证过的线。

### 结论

**P2 暂缓。** 升之前必须先查 AGP 9.4.0 release notes 确认 Gradle 最低要求：

- 若 ≤ 9.5.x → 可与 P1 同批做
- 若要求 9.6/9.7 → **建议跳过 9.4**，等 AGP 9.5 稳定后再评估，
  避免被迫踩进项目刻意避开的 Gradle 9.7 线

---

## 5. P3 — 陈旧依赖清理（逐项单独评估）

| 依赖 | 现状 | 评估 | 建议 |
|---|---|---|---|
| `accompanist-drawablepainter` | 仅 `AutofillScaffold` **1 处**使用 | 迁移成本极低 | **可移除**，自绘替换 |
| `accompanist-permissions` | lastUpdated **2025-04-28**，16 个月未发版 | **无官方 androidx 替代**（`rememberPermissionState` 未并入 androidx，见 google/accompanist#1328）；停更多半是"完成使命"，API 稳定 | **保留**，风险可控 |
| `sardine-android 0.8` | WebDAV，4 个文件使用 | 传递依赖 **OkHttp 4.9.0**，与项目 5.5.0 冲突靠 force 化解，是隐患 | **立项评估**替换或 fork，改动涉及业务功能 |
| `com.lambdaworks:scrypt 1.4.0` | Aegis 解密，3 个文件 | 2015 年停更 | **立项评估**替换 |
| `androidx.credentials 1.3.0` | 最新 1.6.0 | 1.6.0 把 `callingAppInfo.origin` 收紧为 internal，7 处引用 | **单独立项**，先改用 public API 再升 |
| `biometric 1.2.0-alpha05` | 最新 1.4.0-alpha07 | 关系解锁路径，刻意锁 | 保持，排期再验 |
| `com.google.android.material` | 仅 1 个文件使用 | 注释写"for BottomSheet" | 低优先级，可随重构移除 |

---

## 6. 关于性能：本轮升级不是答案

**升依赖带来的运行时性能提升是边际的**，不要以此为立项理由：

- `compose-bom` 升 5 个月 → runtime 1.11.0-beta02 → 1.12.0，重组/布局/文本有优化，
  列表滚动**可能**略顺，通常是百分之几级别，感知不强。
- `AGP` 只影响**构建速度**，与运行时无关。
- 删死依赖减的是包体/方法数，启动收益几乎无感。

### 真正的性能杠杆（已埋好但未拉动）

`app/build.gradle` 已开启 Compose 编译器报告：

```kotlin
composeCompiler {
    reportsDestination = layout.buildDirectory.dir("compose_compiler")
}
```

其注释写明用途：

> 用于定位被误判为「不稳定」的数据类，后续可针对性地添加 `@Immutable` / `@Stable`
> 注解以减少不必要重组

**但该"后续"步骤尚未执行。** 对密码管理器这类列表型 App，
无效重组的开销远大于依赖版本差异——这是投入产出比最高的方向。

配套现状：

- LeakCanary 因 Android 17 启动崩溃已回退（`2.14` 在 ContentProvider 自启期崩），
  **内存泄漏目前零监控**
- 已有 `docs/android17-perf-optimization.md`、
  `framework-optimization-analysis-2026-07-29.md` 可接续

---

## 7. 治理债（建议穿插处理）

### 7.1 硬编码未走 catalog（15+ 处）

`app/build.gradle` 中以下版本未走 `libs.versions.toml`，与 catalog 双轨维护，
**改一处漏一处只是时间问题**：

```
lifecycle-process:2.11.0      profileinstaller:1.4.1
sardine-android:0.8           kotpass:0.13.0
zxcvbn:1.9.0                  commonmark-ext-gfm-tables:0.30.0
commonmark-ext-gfm-strikethrough:0.30.0
commonmark-ext-task-list-items:0.30.0
retrofit:3.0.0 (×2)           okhttp:5.5.0 (×3)
msal:8.4.2                    moshi-adapters:1.15.2
bcprov-jdk18on:1.85.2         argon2kt:1.6.0
```

其中 `commonmark` 一条版本号硬编码 **4 遍**，升级要改 4 处。

### 7.2 resolutionStrategy.force 与 catalog 重复

`app/build.gradle` 的 `resolutionStrategy.force` 里又写死了一遍
Kotlin `2.3.21`（5 个 stdlib 坐标）与 serialization `1.11.0`（5 个坐标）。
将来升 Kotlin 若只改 catalog，**force 会把新版本压回旧版本**。

### 7.3 两个独立 Gradle 工程需同步

根目录 `desktop/` 是**独立 Gradle 工程**（根 `settings.gradle` 只 `include ':app'`），
有自己的 `libs.versions.toml`。以下版本必须两边同步：

`kotlin` / `kotpass` / `bcprov` / `retrofit` / `okhttp` / `kotlinxSerialization` / `coroutines`

`kotpass` 注释已明确写了原因：desktop/shared 与 app 共享 KDBX 代码，
`KeePassKdbxService` 的 public API 暴露了 kotpass 类型，版本不一致会导致
两端对同一份 KDBX 的解析行为分叉。

---

## 8. 执行顺序建议

```
P0 删 slice 死依赖                    ✅ 已完成（a143f4f）
 │
 ├─ P1 compose-bom + material3 捆绑升  ❌ 已回退（2150a1e）
 │     └─ 前置：先做 ExposedDropdownMenu 系列 API 迁移（70 处）
 │          迁移后 → 真机验证 motion（重点盯导航闪烁）
 │
 ├─ 7.x 治理债（穿插，独立 CL）
 │
 ├─ P2 AGP  ← 阻塞：先查 9.4 的 Gradle 最低要求
 │
 └─ P3 逐项评估（accompanist-drawablepainter → sardine → scrypt → credentials）
```

每个批次单独一个 CL，便于定位与回滚。

**建议下一步**：P1 已证明不是版本切换能解决的事，若要继续需单独立项做 API 迁移。
在此之前，可先推进零风险的 7.x 治理债（硬编码收拢 catalog），
该项风险低、不触碰 UI 行为，能实打实降低后续升级的漏改风险。

---

## 9. 2026-08 全量升级执行记录（已合入 main）

> 更新时间：2026-08-29。分支：`dev` 已完成，并以 `--no-ff` 合并进 `main`（合并点 `7c0c6b7b`）。
> 升级前约定（来自更早会话）：一次性全推，JDK / 依赖升到新版本。

### 9.1 已落地的升级内容

| 项 | 改动 | commit | CI 验证 |
|---|---|---|---|
| JDK 21 + Gradle 9.5.1 | 构建工具链升级（Desktop `jvmToolchain(21)` 与 `jvmTarget JVM_21` 对齐） | `f7389f50` 系列 | Desktop Build ✅ / Android CI ✅ |
| AGP 9.3 平台精确匹配 | `platforms;android-37.0` 精确锁定（CI 镜像曾装成 `android-37.1` 触发"Failed to find Platform SDK"） | `ensure-android-sdk.sh` 共享脚本（main/codeql/Release 三处统一调用） | Android CI ✅ |
| `androidx.credentials` 锁 1.3.0 | 1.6.0 把 `CallingAppInfo.origin` 收紧为 internal，Passkey 相关 7 处引用编译失败；锁回 1.3.0 | `0cf3ba1b` | Android CI ✅（单元测失败数 0） |
| 显式声明 `androidx.documentfile` | 此前靠 credentials 1.3.0 传递带入、从未声明，升级后链断；补 `1.1.0` | `0cf3ba1b` | Android CI ✅ |
| `navigation` 2.10.0 | `predictivePop*` 显式接管预测性返回，修升 2.10 后的「页面缩小」回归 | `3a62510` / `36544c2` | Android CI ✅ |
| lint-baseline 重生成 workflow | 新增可手动触发的 `regenerate-lint-baseline.yml`，修其在 CI 上失败（全量分析崩溃 → 改增量更新 + 关 config-cache + 超时 45→60） | `8ea64bc9` | Lint baseline run ✅ |

**验证结论**：dev 上 Android CI（`ed27c3f` 含升级 + credentials/documentfile 修复）success，
单元测基线门禁 `BASELINE_FAILURES=0` 下失败数 **0** → 依赖升级未引入测试回归；
Desktop Build（`4ff2192`）success；Lint baseline（`8ea64bc9`）success。

### 9.2 lint 质量门复位

`Bastion/app/build.gradle` 的 `lint {}` 块此前为 nav 2.10 过渡临时加了 `abortOnError false`
（nav 2.10 传递升级 compose-ui 引入大量新 lint 问题，首个为 `NonObservableLocale`，
本地无 Android SDK 无法重新生成 baseline 吸收）。现已确认 `lint-baseline.xml` 已吸收这些问题
（grep 确认含 `NonObservableLocale`，且 regenerate 流程报 "No baseline changes"），
故**移除 `abortOnError false`**，lint 重新成为硬质量门。
保留：`baseline = file(...)` 与 Kotlin 2.x detector 崩溃的 `disable` 兜底清单（属独立 workaround，非临时项）。

### 9.3 待稳定 / 阻塞项

- **Kotlin 2.4 待 KSP 2.4**：当前 `kotlin = 2.3.21`，KSP 只发到 `2.3.11`、无 2.4.x，
  Room 编译器跑不起来。待 KSP 2.4 发布后再升（已在 `libs.versions.toml` 注释固化）。
- **Compose Multiplatform 1.12 待稳定**：desktop 工程 `compose = 1.10.3`，
  1.12.0 于 2026-08-25 发布但刻意保留 1.10.3（与 Kotlin 版本强绑定 + 桌面端为次要模块）。
  待 1.12 线稳定后单独升。

### 9.4 二分回退项（已知良好回退版本）

后续升级若再次破坏，以下为实测可用的回退锚点（"试过"列是踩坑版本，"回退"列是当前锁定且验证可用的版本）：

| 依赖 | 试过（坏） | 回退（好，当前锁定） | 位置 |
|---|---|---|---|
| app `composeBom` | `2026.08.00` | `2026.03.00` | `Bastion/gradle/libs.versions.toml` |
| desktop `sqldelight` | `2.3.2` | `2.1.0` | `desktop/gradle/libs.versions.toml` |
| desktop `jna` | `5.19.1` | `5.14.0` | `desktop/gradle/libs.versions.toml` |
