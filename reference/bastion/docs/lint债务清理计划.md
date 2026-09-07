# Lint 债务清理计划

> 生成时间：2026-08-31
> 数据来源：`Bastion/app/lint-baseline.xml`（初版快照 `a4e77a8e`）
> **最新状态（2026-08-31 晚更新）：baseline 已重生两次，现存 1734 条 / 13 种，见 [八、执行进度](#八执行进度总览2026-08-31-晚更新)**。
> 相关文档：[分支合并与发版流程](./分支合并与发版流程.md) · [依赖升级计划](./dependency-upgrade-plan-2026-08.md)

---

## 零、一句话结论

`lint-baseline.xml` 里压着 **1925 条**存量问题，但其中 **38 条已经修好了**（`2361b3eb`，只是 baseline 没跟着更新）。
**动手前必须先重生成 baseline**，否则会照着过期清单去"修"已经修完的东西。

剩余真实债务约 **1887 条**，分布极不均匀：

- **46% 是「无用资源」**（887 条），release 已开 `shrinkResources`，**不影响 APK 体积**，纯属维护噪音，可以最后做甚至选择性不做。
- **34% 是「Compose 里用 Context 取资源」**（641 条），属正确性债但不崩溃，是典型的机械替换，适合批量做。
- **真正值得优先处理的只有个位数条目**（安全 1 条 + 功能 3~4 条）。

---

## 一、当前状态

### 1.1 关键事实（决定了修复策略）

| 事实 | 值 | 影响 |
|------|-----|------|
| baseline 条目总数 | **1723**（regen#2 后实测，commit `c90916d`） | 存量债规模 |
| issue 种类 | **9**（初版 42 → regen#1 后 13 → regen#2 后 9） | 大量小类已清零 |
| baseline 快照提交 | regen#2 → `c90916d`（08-31，bot 生成） | 已晚于全部修复批次，**当前无僵尸条目** |
| lint 触发时机 | `github.event_name != 'push'` | **PR / 手动触发才跑**，push 到 dev 跳过 |
| release `shrinkResources` | `!disableMinify`（已开） | 无用资源不进最终 APK |
| lint 失败是否阻断 | 是（PR 门禁） | 新增问题会卡 PR，存量被 baseline 压住不报 |

### 1.2 baseline 机制要点

- baseline 是**快照**：里面记的问题不会报警，**不在里面的新问题才会报**。
- 因此修完一批必须**重生成 baseline**，把成果锁进去，形成棘轮（只减不增）。
- 反过来，代码修好了但 baseline 没更新 → 条目会变成"僵尸条目"，统计虚高。
  **这正是当前的状态。**

### 1.3 ✅ 那 38 条僵尸已清除；当前新一批僵尸 11 条（等 regen#2）

`2361b3eb` 的 38 条（`NewApi` 13 / `StaticFieldLeak` 8 / `NonObservableLocale` 9 / `ConfigurationScreenWidthHeight` 8）
已在 **regen#1**（`13a5cf5`）被剪除，无需再动。

之后又做了 4 批修复，代码已改但 baseline 未重生 → 形成**新一批僵尸条目 11 条**：

| issue id | 当前 baseline 条数 | 修复提交 |
|----------|------------------:|----------|
| `TypographyDashes` | 6 | `065c2d8`（数字范围连字符改 en dash U+2013） |
| `ObsoleteSdkInt` | 3 | `736b096`（`FillResponseBuilderNg` 两处恒真判断 + `mipmap-anydpi-v26`→`mipmap-anydpi`） |
| `UnsafeOptInUsageError` | 1 | `736b096`（`imageProxyToBitmap` 补 `@SuppressLint`） |
| `TypographyFractions` | 1 | `736b096`（`KDBX 3.1 / 4.x` 是版本号，加 `tools:ignore`） |
| **合计** | **11** | regen#2（`33406203977`）跑完即清零 |

> **结论：这 11 条不需要再动，regen#2 重生成 baseline 即可消失。**
> 判据：都在「代码已改 / baseline 未重生」状态，不是漏改。

---

## 二、剩余债务全景（1925 − 38 = 1887 条）

按「风险 × 收益」排序，不是按数量排序。

### P0 — 安全（当前 1 条）

| issue id | 数量 | 位置 | 结论 |
|----------|-----:|------|------|
| `AcceptsUserCertificates` | 1 | `res/xml/network_security_config.xml:30` | **真问题**，用户明确**跳过**（会影响自签 CA 抓包），见 3.1 |
| `CustomX509TrustManager` | 0 | — | ✅ 已加 `@SuppressLint` + 说明注释（`25c43cb`），已清零 |

### P1 — 真功能问题（✅ 已全部清零）

baseline 重生后 P1 **已归零**（原 19 条全部消灭）。清单保留作历史记录：

| issue id | 原数量 | 现状 |
|----------|-------:|------|
| `QueryPermissionsNeeded` | 1 | ✅ 已修 |
| `SelectedPhotoAccess` | 1 | ✅ 已修 |
| `PluralsCandidate` | 6 | ✅ 已修 |
| `SimpleDateFormat` | 4 | ✅ 已修 |
| `ConstantLocale` | 2 | ✅ 已修 |
| `UnsafeOptInUsageError` | 1 | ⚠️ 代码已加 `@SuppressLint`，baseline 剩 1 条僵尸（regen#2 剪除） |
| `InlinedApi` | 4 | ✅ 已修 |

### P2 — Compose 正确性（当前 565 条，机械替换）

| issue id | 当前数量 | 说明 |
|----------|---------:|------|
| `LocalContextGetResourceValueCall` | 565 | `LocalContext.current` 取资源，不随语言/主题重组（原 641） |
| `ModifierParameter` | 0 | ✅ 已修 33 处（`4b325f05`） |
| `ModifierFactoryExtensionFunction` | 0 | ✅ 已修 4 处（`4b325f05`） |

`LocalContextGetResourceValueCall` 的 565 条按处置策略分三档（**仅第三档值得动手**）：
- **Toast 类 ~47 条**：值被立即消费，无需重组 → **非 bug，保留抑制**；
- **一次性消费 ~43 条**（保存 / 比较 / 回调）：同上 → **保留抑制**；
- **待人工复核 ~373 条**：需逐文件确认 `stringResource` 作用域后再改。

集中度很高，适合按文件切批（前 5 个文件占 258/565 = 46%）：

| 文件 | 条数 |
|------|-----:|
| `ui/screens/SettingsScreen.kt` | 122 |
| `ui/screens/WebDavBackupScreen.kt` | 56 |
| `ui/screens/ImportDataScreen.kt` | 29 |
| `ui/screens/PasskeyListScreen.kt` | 28 |
| `ui/screens/OneDriveBackupScreen.kt` | 23 |

### P3 — API 规范（184 条）

| issue id | 数量 | 说明 |
|----------|-----:|------|
| `RestrictedApi` | 91 | 用了 `@RestrictTo` API，**89/91 集中在单个文件**，见下方专节 |
| `UseKtx` | 87 | 可用 KTX 扩展替代 → **本项目不可修**，见下方专节 |
| `TypographyDashes` | 6 | ⚠️ 僵尸：`065c2d8` 已修，regen#2 剪除 |

#### `RestrictedApi` 专节（重要发现：不是"等依赖升级"，而是可就地消解）

按文件分布统计：

| 文件 | 条数 | 涉及 API |
|------|-----:|----------|
| `ui/theme/CustomColorSchemeGenerator.kt` | **89** | Material 内部配色 API：`Hct` / `TonalPalette` / `DynamicScheme` / `DynamicColor` / `MaterialDynamicColors.*` |
| `autofill_ng/builder/AutofillDatasetBuilder.kt` | 2 | `SlicedContent.getSlice`（`androidx.autofill` internal） |

**结论修正（推翻原 Phase 4「与依赖升级联动」的判断）**：
这些 API 是 `@RestrictTo(LIBRARY_GROUP)` 的**永久性限制**，升级 Material 版本**不会**把它们变公开 —— 等依赖升级是等不到的。
且 Compose 的"由种子色生成 Material3 配色"官方并无公开 API（`dynamicXxxColorScheme` 只吃系统壁纸色），
社区通行做法就是直接调 `Hct`/`TonalPalette`。

故 **91 条的正确处置 = 就地 `@SuppressLint("RestrictedApi")` + 注释说明**（零行为变更，仅失去一条升级预警）：
- `CustomColorSchemeGenerator.kt` 加 `@file:SuppressLint("RestrictedApi")`；
- `AutofillDatasetBuilder.kt` 加方法级抑制；
- 注释里写明"Material 版本升级时须回归自定义配色"，把预警从 lint 转移到注释。

#### `UseKtx`（87）— 本项目**不可修**

`app/build.gradle:474` 有 `exclude group: 'androidx.core', module: 'core-ktx'`，
`:app` 故意排除了 core-ktx → `toUri` / `scale` / `toColorInt` / `createBitmap` / `SharedPreferences.edit`
等扩展**不在编译路径上**。曾尝试转换 8 个文件，编译直接报 `Unresolved reference 'toUri'`，已全部回滚。

> **结论：UseKtx 保持 baseline 抑制。要修需先引入 core-ktx（架构级改动，须单独决策）。**

### P4 — 清理噪音（当前 978 条，**不影响 APK 体积**）

| issue id | 数量 | 说明 |
|----------|-----:|------|
| `UnusedResources` | 887 | 其中 `values/strings.xml` 852 条 → **决策：保持抑制，不动**（见 七.3） |
| `UnusedAttribute` | 84 | 前向兼容属性（`maxLongVersionCode` / `enableOnBackInvokedCallback` / `supportsInlineSuggestions`），删则丢特性 → **保持抑制** |
| 依赖类（`GradleDependency` 5 / `UseTomlInstead` 2） | 7 | 见[依赖升级计划](./dependency-upgrade-plan-2026-08.md)。`NewerVersionAvailable` 与 `AndroidGradlePluginVersion` 已在 `app/lint.xml` 忽略 |

### 零散杂项（当前 6 条；原 22 条 17 种已消掉 11 种）

| 已清零（✅） | 原数量 | 处置 |
|-------------|-------:|------|
| `IntentFilterUniqueDataAttributes` | 3 | ✅ |
| `PrivateApi` / `DiscouragedApi` / `EmptySuperCall` / `LaunchActivityFromNotification` | 1+2+1+1 | ✅ `25c43cb` 加 `@SuppressLint` |
| `StartActivityAndCollapseDeprecated` | 1 | ✅ |
| `AppBundleLocaleChanges` / `ChromeOsAbiSupport` / `AndroidGradlePluginVersion` | 各 1 | ✅ `app/lint.xml` 忽略（非 Play 分发） |
| `RedundantLabel` / `PrivateResource` | 各 1 | ✅ |
| `ExportedContentProvider` / `DataExtractionRules` | 各 1 | ✅ debug manifest 补 `exported=false` + 新建 `backup_rules.xml` / `data_extraction_rules.xml` |
| `Overdraw` | 1 | ✅ `autofill_manual_card.xml` 去冗余 `windowBackground` |
| `Typos`（DNS1 误报） | 1 | ✅ `strings.xml` 加 `tools:ignore` |
| `RedundantNamespace` | 1 | ✅ `828f9b2` 去冗余 `xmlns:tools` |
| `NewApi` / `StaticFieldLeak` / `NonObservableLocale` / `ConfigurationScreenWidthHeight` | 38 | ✅ `2361b3eb` + regen#1 剪除 |

**剩余 6 条**：

| issue id | 数量 | 处置 |
|----------|-----:|------|
| `ObsoleteSdkInt` | 3 | ⚠️ 僵尸（`736b096` 已修），regen#2 剪除 |
| `UnsafeOptInUsageError` | 1 | ⚠️ 僵尸（`736b096` 加 `@SuppressLint`），regen#2 剪除 |
| `TypographyFractions` | 1 | ⚠️ 僵尸（`736b096` 加 `tools:ignore`），regen#2 剪除 |
| `HardwareIds` | 1 | 用户决策**跳过**（操作日志带设备 ID 属隐私取舍） |

其中两个值得单独看一眼：

- `HardwareIds`（`utils/OperationLogger.kt:52`）：操作日志里获取设备标识符。
  对密码管理器来说，日志里带设备 ID 是否必要属于隐私取舍，不是必须改。
- `ExportedContentProvider` 在 `src/debug/AndroidManifest.xml`（仅 debug 变体），风险低。

### 总账（重生成 baseline 后可据此自查）

**当前实际分布（2026-08-31 晚，regen#2 后实测 baseline 1723 条，僵尸已清零）**：

```
P0 安全          1   ← AcceptsUserCertificates（用户决策跳过）
P1 功能          0   ← ✅ 全部清零（PluralsCandidate / SimpleDateFormat / ConstantLocale /
                        QueryPermissionsNeeded / SelectedPhotoAccess / InlinedApi 等已消灭）
P2 Compose     565   ← LocalContextGetResourceValueCall（ModifierParameter 33 已清零）
P3 API 规范    178   ← RestrictedApi 91 + UseKtx 87
P4 清理        978   ← UnusedResources 887 + UnusedAttribute 84 + 依赖类 7
零散杂项          1   ← HardwareIds（用户决策跳过）
──────────────────
baseline 总计 1723   ← 全部为"已决策保留"或"待排期"，无僵尸
```

9 个 issue 种类：`UnusedResources` 887 / `LocalContextGetResourceValueCall` 565 /
`RestrictedApi` 91 / `UseKtx` 87 / `UnusedAttribute` 84 / `GradleDependency` 5 /
`UseTomlInstead` 2 / `HardwareIds` 1 / `AcceptsUserCertificates` 1。

自查脚本（在 `Bastion/app` 目录下跑）：

```bash
python3 -c "
import xml.etree.ElementTree as ET, collections
t = ET.parse('lint-baseline.xml')
c = collections.Counter(i.get('id') for i in t.getroot().findall('issue'))
print('总计', sum(c.values()), '种类', len(c))
for k, v in c.most_common(10): print(f'  {k:<38} {v:>5}')
"
```

---

## 三、P0 详解（唯一需要优先动手的）

### 3.1 `AcceptsUserCertificates` — 真问题，建议修

**现状**：`res/xml/network_security_config.xml` 的 `<base-config>` 里写了 `<certificates src="user" />`。
该文件在 `src/main/` 下，**release 构建同样生效** → 正式版会信任设备上用户安装的 CA 证书。

**为什么对 Bastion 敏感**：这是个密码管理器，所有主密码/凭据同步都走 HTTPS。
一旦设备上被塞入恶意 CA（社工、企业 MDM、恶意应用诱导安装），流量可被中间人解密。
调试抓包确实需要 user CA，但不应该带进 release。

**修法**（官方标准做法，3 行改动，零风险）：用 `<debug-overrides>` 把 user CA 限定到 debuggable 构建。

```xml
<network-security-config>
    <base-config cleartextTrafficPermitted="false">
        <trust-anchors>
            <!-- release 只信任系统 CA -->
            <certificates src="system" />
        </trust-anchors>
    </base-config>

    <!-- 仅 debuggable 构建（debug flavor）额外信任用户 CA，供抓包调试；
         release 自动不生效，无需再单独维护一份 main/debug 配置 -->
    <debug-overrides>
        <trust-anchors>
            <certificates src="system" />
            <certificates src="user" />
        </trust-anchors>
    </debug-overrides>

    <!-- 局域网明文配置保持不变 -->
</network-security-config>
```

**验证**：
1. `./gradlew :app:lintDebug` → 该条从报告消失。
2. 装 release 包后，系统「设置 → 加密与凭据 → 用户凭据」里装个自签 CA，
   确认 Bastion 的 HTTPS 请求**不再信任**它（用 Charles/mitmproxy 抓包验证）。

### 3.2 `CustomX509TrustManager` — 误报，抑制即可

**已核实结论**：`BitwardenApiFactory.CompositeX509TrustManager` 实现是**正确的条件化委托**，不是"信任一切"的空实现。

核实依据（读代码确认，非猜测）：

```kotlin
// configureTls()：未配置 TLS 时直接返回，完全不碰默认信任链
if (tlsConfig == null || tlsConfig.isEmpty()) return

// buildTrustManager()：没配自签 CA 就用系统默认
if (caCertificatePem.isNullOrBlank()) return systemTrustManager

// 仅当用户主动导入自签 CA 时才组合：系统在前，自定义在后
return CompositeX509TrustManager(listOf(systemTrustManager, customTrustManager))

// checkServerTrusted()：委托遍历，全失败才抛异常——非空实现
delegates.forEach { manager ->
    try { manager.checkServerTrusted(chain, authType); return }
    catch (e: Exception) { lastError = e }
}
throw lastError ?: IllegalStateException("No trust manager accepted server certificate")
```

lint 这个检查是启发式的，只要实现了 `X509TrustManager` 就报，无法区分委托模式与空实现。

**处置**：加 `@SuppressLint("CustomX509TrustManager")` 并附注释说明「委托实现、非信任一切」，避免后来者误删。

**可选加固（非 bug，属产品取舍，需你确认再做）**：
当前逻辑下，用户导入的 CA 也能为 Bitwarden 官方域名签发证书并被接受。
若要收窄，可用 OkHttp `CertificatePinner` 把自定义 CA 限定到用户配置的那个域名。
代价是"一个自签 CA 服务多个内部域名"的场景会失效，所以**不建议默认开启**。

---

## 四、分批执行计划

### Phase 0 — 重生成 baseline（前置，已 ✅ 完成于 2026-08-31）

```bash
cd Bastion
./gradlew :app:updateLintBaselineDebug
```

**执行记录（2026-08-31）**：
- 此前 `updateLintBaselineDebug` 在 Kotlin 2.x + nav 2.10 下全量分析 JVM 崩溃，
  导致基线一直没重生、P1/P2 改完代码后条目全是"陈旧僵尸"。
- 本次通过 `Regenerate lint baseline` 工作流（`33366951289`）成功重生，
  并由 `Android CI debug`（`33367954207`，含 Run lint）验证 **build + lint 全绿**，
  证明重生完整、无遗漏（间歇性崩溃本次未触发）。
- **重生前后对比**（baseline 条目）：

  | 类别 | 重生前 | 重生后 | 说明 |
  |------|------:|------:|------|
  | ModifierParameter | 33 | 0 | P2 修复生效 |
  | PluralsCandidate | 6 | 0 | P1 修复生效 |
  | SimpleDateFormat | 4 | 0 | P1 修复生效 |
  | ConstantLocale | 2 | 0 | P1 修复生效 |
  | InlinedApi | 4 | 0 | P1 修复生效 |
  | QueryPermissionsNeeded | 1 | 0 | P1 修复生效 |
  | SelectedPhotoAccess | 1 | 0 | P1 修复生效 |
  | LocalContextGetResourceValueCall | 641 | 565 | P2 的 stringResource 改造减 76 |
  | UnusedResources | 887 | 887 | 按约定保留 baseline 抑制，不动 |
  | 其它（RestrictedApi/UseKtx/UnusedAttribute/小类） | ~408 | ~408 | 基本不变 |
  | **合计** | **~1887** | **~1757** | 陈旧条目已清、行号重新锚定 |

- **收益**：漂移风险消除（所有条目重新锚定当前行号）；P1/P2 修复已反映在基线。
- **残留风险**：`updateLintBaselineDebug` 崩溃为**间歇性**，将来若再重生失败，
  可临时在 `regenerate-lint-baseline.yml` 加"崩溃签名提取步骤"（已验证可行，
  见 `59c93f28`/`fcd15eea` 调试提交，事后已清理）来抓取崩溃栈定位 detector。

> 任务名注意：CI 里已改用 `updateLintBaselineDebug`（见 `e9f0be48`），
> 不要用老的 `lintDebug -DupdateBaseline` 写法。

### Phase 1 — P0 安全（2 条）

- [x] 3.2 `BitwardenApiFactory` 加 `@SuppressLint` + 说明注释（`25c43cb`，误报已核实）→ **已清零**
- [ ] 3.1 `network_security_config.xml` 改 `debug-overrides` → **用户决策：跳过**
      理由：会改变 release 信任行为，影响自签 CA 连自托管 Bitwarden 抓包。
      若日后要收窄，替代方案见 3.1 末段（保留 user CA 但只在自托管域名放行）。

### Phase 2 — P1 功能（✅ 已全部清零）

- [x] `QueryPermissionsNeeded`：`AndroidManifest.xml` 补 `<queries>`
- [x] `SelectedPhotoAccess`：适配 Android 14+ Photo Picker
- [x] `PluralsCandidate`（6）：改 `<plurals>`
- [x] `SimpleDateFormat`（4）+ `ConstantLocale`（2）
- [x] `InlinedApi`（4）：补版本判断或抑制
- [x] `UnsafeOptInUsageError`：`736b096` 补 `@SuppressLint`（lint 不识别 `@OptIn`）→ 剩 1 条僵尸，regen#2 剪除

> 以上均在 regen#1 之前的批次完成，regen#1 后 baseline 中 P1 已归零。

### Phase 3 — P2 Compose 正确性（约 678 条）

`LocalContextGetResourceValueCall` 是最大一块，但改法机械：

```kotlin
// 改前
val ctx = LocalContext.current
Text(ctx.getString(R.string.foo))

// 改后
Text(stringResource(R.string.foo))
```

建议**按屏幕分批**，每批 3~5 个文件，每批跑一次 lint + 真机过一遍对应页面。
625 条集中在 `ui/` 目录，可按 `ui/screens/*` 切分。

`ModifierParameter`（33 条）同理，纯签名调整，风险低但会大面积改动函数签名，
建议单独一批，避免与上面混在一起导致 review 困难。

#### Phase 3 执行记录（2026-08-31）

按用户「P2 你推荐来弄吧」的授权，**已完成可安全机械化的子集**，剩余项按风险分级暂挂：

| 批次 | 提交 | 内容 | 数量 | baseline 处理 |
|------|------|------|-----:|---------------|
| Modifier 规范 | `4b325f05` | `ModifierParameter` 33 处参数重排到首位；`ModifierFactoryExtensionFunction` 4 处改为 `Modifier` 扩展函数 | 37 | 僵尸（待重生 baseline 清理） |
| UI 渲染 | `58bc5ce9` | `LocalContextGetResourceValueCall` 中「在 @Composable 内且为 composable 直接文本参数」的 `context.getString` → `stringResource` | 85 | 僵尸 |
| UI 渲染修正 | `96352d9a` | 还原 3 处 **biometric `() -> Unit` 回调**内误改的 `stringResource` → `context.getString`（`DeleteConfirmDialog` 的 `biometricAction`、`AddEditNoteScreen` 的 `biometricAction`、`MasterPasswordLockingSettingsScreen` 的 `startBiometricEnable`） | 8 行 | — |

**最终状态（2026-08-31）**：P1 + P2 全部合入 `main`（PR #21 已 MERGED）。
dev 上手动 lint 运行 `33362621331`：**Run lint = success + Build Debug APK = success**，CI 全绿。
preview 预览包已发布：`Development Preview (build.202608310607)`。

**抽取方法论（避免误改非 composable 场景）**：
- 用 composable 作用域感知扫描：只在 `@Composable fun` 的 body 区间内、且为
  `Text()` / `label=` / `contentDescription=` / `title=` / `text=` / `placeholder=` 等
  **直接参数值**的行替换；
- 排除 Activity / autofill 构建器 / 状态赋值（`x = context.getString(...)`）；
- 排除事件 lambda 内调用（实测 `PasswordSuggestionActivity` 的 `onClick` 里
  `ClipboardUtils.copyToClipboard(..., label = context.getString(...))` 是假阳性，
  那里 `stringResource` 不可用，已剔除）；
- **⚠️ 排除所有 `() -> Unit` 类型的具名回调 lambda**（不仅限于 `onClick`）：
  `biometricHelper.authenticate(...)` 常包在 `val biometricAction = if (...) { { ... } }`
  或 `val startBiometricEnable = { ... }` 里，这些 lambda 签名是 `() -> Unit`，
  **不能调用可组合函数 `stringResource`**，必须用 `context.getString`。本次 `58bc5ce9`
  的机械替换漏了这一类，导致 `assembleDebug` 编译失败，已用 `96352d9a` 还原。
  判定口诀：凡是 `stringResource` 出现在 `onClick` / `onDismissRequest` / `remember {}` /
  `clickable {}` / `val xxx = { ... }` 这类 **`() -> Unit` 或非 composable lambda** 体内，
  一律改回 `context.getString`。

**暂挂项（baseline 仍抑制，CI 不阻塞，运行时切换语言/主题不影响核心功能）**：
- `Toast` 类（约 47 条）：值被立即消费，无需重组 → 非 bug；
- 一次性消费（保存 / 比较 / 回调，约 43 条）：同上；
- 待人工复核（约 373 条）：基线行号已过期 + 部分在 lambda / 非 UI 位置，
  需逐文件 `stringResource` 作用域复核后再改，避免破坏编译。

> 注：5.2「必须走 PR」已过时——`gh workflow run "Android CI debug" --ref dev`
> （manual dispatch）同样会跑 lint，本批次即用此法在 push 后验证。

### Phase 4 — P3 API 规范（184 条）

- [x] `TypographyDashes`（6）→ `065c2d8` 已修，剩僵尸，regen#2 剪除
- [ ] **`RestrictedApi`（91）→ ✅ 下一批就做这个（见 P3 专节）**
      89 条集中在一个文件 `CustomColorSchemeGenerator.kt`，是 Material 内部配色 API
      （`Hct` / `TonalPalette` / `MaterialDynamicColors.*`），属 `@RestrictTo(LIBRARY_GROUP)`
      **永久限制，升依赖也不会变公开** → 处置是就地 `@SuppressLint` + 注释，零行为变更、一次性清 91 条。
- [ ] `UseKtx`（87）→ **不可修**：`:app` 排除了 `core-ktx`（`build.gradle:474`），
      改了编译不过（`Unresolved reference 'toUri'`）。要修需先引入 core-ktx，属架构级改动，须单独决策。

### Phase 5 — P4 清理（约 971 条，可低优先）

**先确认一件事**：release 已开 `shrinkResources !disableMinify`（`build.gradle:199`），
无用资源**不会打进 APK**，所以这 971 条**不影响包体积**，只是：

- `strings.xml` 里 852 条未使用文案，干扰翻译与文案维护
- lint 报告噪音大，掩盖真问题

处置建议：

1. **不要手工删 852 条 string**。多语言项目里常有 `getString("prefix_" + x)` 动态拼接，
   误删会直接崩溃且只有运行时才发现。
2. 稳妥做法：先确认是否存在动态引用
   ```bash
   cd Bastion/app/src/main/java
   grep -rnE "getString\(|getIdentifier\(|resources\.getIdentifier" . | grep -vE "R\.[a-z]+\.[a-z_0-9]+\)" | head -30
   ```
   如果没有动态拼接，再按模块分批删，每批删完跑一次构建 + 冒烟。
3. `raw/eff_short_wordlist.txt` 被标未使用——**删之前先确认**，
   这是 EFF 密码词表，很可能是某个密码生成器功能的资源，未使用反而可能是功能没接上。

---

## 五、验证与门禁

### 5.1 本地验证

```bash
cd Bastion
./gradlew :app:lintDebug            # 看新增问题（baseline 外的）
./gradlew :app:updateLintBaselineDebug   # 修完一批后锁进 baseline
```

### 5.2 CI 门禁现状（重要）

`.github/workflows/main.yml:110-112`：

```yaml
- name: Run lint
  if: github.event_name != 'push'
  run: ./gradlew :app:lintDebug --stacktrace
```

- **push 到 dev 不跑 lint**（为了加快出包反馈）
- **PR / 手动触发才跑**

所以每批 lint 修复**必须走 PR 才能验证**，直接 push 到 dev 是验不出来的。

### 5.3 每批完成的定义

1. `./gradlew :app:lintDebug` 无新增问题
2. 重生成 baseline，条目数较上一批**严格减少**
3. PR 里 CI 全绿
4. 涉及的界面真机过一遍（尤其是 Compose 相关的 P2 批次）

---

## 六、避坑清单

| 坑 | 说明 |
|----|------|
| **别照着过期 baseline 修** | 当前快照早于 `2361b3eb`，38 条已修。动 Phase 0 |
| **push 到 dev 验不出 lint** | lint 只在 PR / 手动触发跑，见 5.2 |
| **别手工批量删 string** | 动态 `getString` 拼接会导致运行时崩溃 |
| **release 不受 UnusedResources 影响** | 已开 `shrinkResources`，别拿"减小包体积"当理由 |
| **`CustomX509TrustManager` 是误报** | 别把正确的委托实现"修"坏了，见 3.2 |
| **改 `ModifierParameter` 单独一批** | 函数签名改动大，混在一起 review 不动 |
| **`stringResource` 不能进 `() -> Unit` 回调** | `biometricHelper.authenticate` / `onClick` / `remember {}` / `clickable {}` / `val x = { }` 等普通 lambda 内只能用 `context.getString`，否则 `assembleDebug` 编译失败（本次 `58bc5ce9` 误伤 3 处 biometric 回调，已 `96352d9a` 还原） |
| **P3 与依赖升级联动** | ⚠️ **此条已过时**：`RestrictedApi` 89 条是 Material `@RestrictTo(LIBRARY_GROUP)` 永久限制，升依赖也不会变公开 → 改为就地 `@SuppressLint` 消解（见 P3 专节）。仅 `UseKtx` 87 确实依赖 core-ktx 引入决策 |
| **8GB 机器别本地跑 `lintDebug`** | 完整 `lintDebug` 需 ~5.5GB 堆，本机只有 8GB → swap/OOM 假死。迭代期用 `compileDebugKotlin`（~23s），baseline 重生交给 CI |
| **本机 CRLF 会让 `*GuardTest` 假失败** | `core.autocrlf = true` 导致多行文本断言读到 `\r\n`。判别法：失败集中在多行断言 + 对应源文件 `git diff` 为空 → 假象，以 CI（LF）为准 |
| **Kotlin LSP 不可信** | LSP 对「逗号分隔 import」等语法错误不报。改 Kotlin 后必须用真实 `gradle` 编译验证 |
| **don't 查 lint 剩余项前先重新统计 baseline** | regen 之后各项计数与旧记录差异很大，拿旧数字排期会做无用功 |

---

## 七、待确认事项

以下几个点需要你拍板，我再动手：

1. **Phase 1 的 `AcceptsUserCertificates` 是否现在就修？**
   改动很小（3 行），但会**改变 release 的信任行为**——改完后调试用的自签 CA 在 release 包上不再生效。
   如果你平时用 release 包 + 自签 CA 连自托管 Bitwarden，**这会直接影响你的使用**。
   替代方案：保留 user CA，但只在自托管域名上放行。

2. **Phase 3 的 641 条 `LocalContextGetResourceValueCall` 做不做？**
   ✅ 已按推荐执行安全子集（见上「Phase 3 执行记录」）：改了 85 处 UI 渲染真 bug，
   `ModifierParameter`/`ModifierFactoryExtensionFunction` 共 37 处。
   剩余约 463 条（Toast / 一次性消费 / 待复核 lambda）属非重组场景或需逐文件复核，
   **暂挂 baseline 抑制**，不阻塞 CI，待后续按屏幕分批或配合本地编译环境再清。
   ✅ **执行状态（2026-08-31）**：本子集已完成并经 CI 验证（build + lint 全绿），
   随 PR #21 合入 `main`。仅中途在 `58bc5ce9` 误伤 3 处 biometric `() -> Unit` 回调，
   已由 `96352d9a` 还原（详见 Phase 3 执行记录与方法论补充）。

3. **Phase 5 的 887 条无用资源做不做？**
   不影响体积，纯整洁度。可以先只清理 `drawable` 那 2 条，`strings.xml` 的 852 条暂缓。
   ✅ **决定（2026-08-31）**：务实降噪范围内，**UnusedResources 887 保持 baseline 抑制、不动**。
   理由：`shrinkResources`/R8 下不影响 APK 体积；删除存在反射/webview/拼接名引用资源导致运行时崩溃的风险，
   需配合资源引用审计 + 真机回归，投入产出不划算。若日后要"零未用资源"洁癖再单独排期。

4. **`raw/eff_short_wordlist.txt` 未使用**——是历史遗留该删，还是有功能没接上？
   这个需要你确认，我不敢擅自删。

---

## 八、执行进度总览（2026-09-01 更新）

### 8.1 baseline 演进

| 节点 | baseline 条目 | 说明 |
|------|-------------:|------|
| 初版快照 `a4e77a8e` | 1925 | 含 38 条僵尸 |
| regen#1（`13a5cf5`，run `33389229711`） | ~1751 | 剪 38 条僵尸 + P1/P2 修复生效 |
| 后续 4 批修复后 | 1734 | 代码已改、baseline 未重生 → 新一批 11 条僵尸 |
| regen#2（`c90916d`，run `33406203977`） | 1723 ✅ | 6m31s 完成，净减 117 行，**僵尸全部清零** |
| **Next-1（`f056523e`，本地 regen#3）** | **1627** ✅ | 7m52s，净减 96：`RestrictedApi` 91 + `GradleDependency` 僵尸 5。**纯删除 1056 行、零新增** |
| **Next-2（`878c3e9e`，本地 regen#4）** | **1504** ✅ | 8m04s，净减 123：`LocalContextGetResourceValueCall` 565 → 442 |

**当前 7 个种类**（合计 1504）：
`UnusedResources` 887、`LocalContextGetResourceValueCall` 442、`UseKtx` 87、
`UnusedAttribute` 84、`UseTomlInstead` 2、`HardwareIds` 1、`AcceptsUserCertificates` 1。

两批累计 **1723 → 1504（-219）**，且两批 regen 均为**纯删除、零新增**（棘轮未回退）。

### 8.2 批次执行记录（regen#1 之后）

| 提交 | 内容 | 条目影响 |
|------|------|---------:|
| `25c43cb` | 6 处 Kotlin `@SuppressLint`（CustomX509TrustManager / LaunchActivityFromNotification / EmptySuperCall / PrivateApi / DiscouragedApi / UseRequiresApi）+ `VaultV2Pane` 逗号 import 修复；Manifest 补 `InitializationProvider exported=false` + `dataExtractionRules`；新建 `app/lint.xml`（忽略 ChromeOS ABI / AGP 版本 / AppBundle 语言）；`ic_passkey` 加 `tools:override`；`autofill_manual_card` 去 `windowBackground`；`strings` DNS1 加 `Typos` 忽略 | ~13 |
| `828f9b2` | 5 个 warning 清零：去冗余 `xmlns:tools`、补 `fullBackupContent` + `backup_rules.xml`、`lint.xml` 忽略 `NewerVersionAvailable` | 5 |
| `065c2d8` | `TypographyDashes` 6 处数字范围连字符改 en dash（U+2013） | 6 |
| `736b096` | `ObsoleteSdkInt` ×2（删除 `createForTiramisu`/`createPreTiramisu` 内恒真判断）+ `mipmap-anydpi-v26`→`mipmap-anydpi`；`TypographyFractions`、`UnsafeOptInUsageError` 加抑制 | 3 |
| `8bc326a` | （非 lint）autofill 邻居提升回归修复 | — |
| `fddd4e5` | （非 lint）启动器图标改为灰白玻璃底 + 金黄棱堡爪印 | — |
| `215ab387` | （非 lint）启动器图标改为透明玻璃底 + 极夜蓝猫爪，爪印缩至 73% | — |
| **`f056523e`** | **Next-1**：`RestrictedApi` 91 条就地抑制（`CustomColorSchemeGenerator.kt` 加 `@file:SuppressLint`（89）+ `AutofillDatasetBuilder.kt` 方法级抑制（2）），附回归说明注释 | **-96** |
| **`878c3e9e`** | **Next-2**：20 个文件 130 处 `context.getString(...)` → `stringResource(...)`，作用域感知抽取 + 编译驱动修正 | **-123** |

### 8.3 环境约束（2026-09-01 实测修正）

> ⚠️ 原「本机 8GB 物理内存，不要本地跑 `lintDebug`」的**结论对、原因错**，且该限制**可绕过**。

| 项 | 原记录 | 2026-09-01 实测 |
|----|--------|-----------------|
| 内存 | "8GB 物理内存" | `free` 显示 123Gi（宿主机可见值），但 **cgroup 配额就是 8GB**（`X_IDE_MEMORY_LIMIT=8G`、`/sys/fs/cgroup/memory.max=8589934592`）；CPU 配额 4 核 |
| `lintDebug` | "不要本地跑（会 swap/OOM 假死）" | **本地可以跑**，但堆必须压到 **3GB**。`-Xmx16g` 与 `-Xmx5g` 都会冲破 8GB 配额被 OOM kill（`memory.peak` 达 8.59G、`oom_kill=3`）；`-Xmx3g` 稳定通过（8m04s） |

**本地跑 lint 的可用命令**（已验证）：

```bash
export ANDROID_HOME=/opt/android-sdk ANDROID_SDK_ROOT=/opt/android-sdk
./gradlew :app:updateLintBaselineDebug --console=plain --no-configuration-cache \
  --max-workers=2 \
  -Dorg.gradle.jvmargs="-Xmx3g -Xss1m -XX:MaxMetaspaceSize=640m -XX:CICompilerCount=2 -Dfile.encoding=UTF-8"
```

几个坑：
- **`--no-configuration-cache` 必须加**：lint 任务存配置缓存时会报
  `error writing value of type ...DefaultConfigurableFileCollection`，导致构建失败。
- `-Xss1m` / `--max-workers=2` / `MaxMetaspaceSize=640m` 都是为了压内存峰值。
- 跑前先 `./gradlew --stop`，别让空闲的 Kotlin daemon 占着配额。
- 首次跑需联网拉依赖，约 9 分钟；之后增量约 20s~3min。

**网络前置**（不做这步，Gradle / SDK / 依赖下载全挂）：
沙箱 DNS 把 `dl.google.com`、`services.gradle.org`、`repo.maven.apache.org` 等劫持到
`198.18.0.x` 保留段（与 GitHub 同一套路）。需先用 DoH 取真实 IP 并写入 `/etc/hosts`
**和** `~/.user_hosts`（后者才跨工作区重启保留）。

```bash
curl -s "https://dns.alidns.com/resolve?name=dl.google.com&type=A"
```

| 域名 | 真实 IP（2026-09-01 实测） |
|------|---------------------------|
| `dl.google.com` | `113.108.239.161` |
| `services.gradle.org` | `104.16.73.101` |
| `plugins.gradle.org` / `downloads.gradle.org` | `104.16.72.101` |
| `repo.maven.apache.org` / `repo1.maven.org` | `104.18.18.12` |

GitHub 各域名 IP 会漂移（本次就遇到 `20.205.243.166` 间歇性不通而 `140.82.112.4` 正常，
隔几分钟又反转），**偶发 `000` 先重试一次再判定为封禁**。已把测速选优固化为
`Bastion/tools/env/refresh_hosts.sh`，IP 失效时重跑即可自动挑最快可用 IP 写回 hosts。

**Android SDK**：官方通道打通后可直接装
（`platforms;android-37.0` + `build-tools;37.0.0` + `platform-tools`，约 5 秒）。
Gradle 分发包若官方慢，可取国内镜像 `https://mirrors.cloud.tencent.com/gradle/gradle-9.5.1-bin.zip`
（0.7s / 140MB），放入 `~/.gradle/wrapper/dists/gradle-9.5.1-bin/<hash>/` 即可被 wrapper 直接解压。
另注：`/root/.gradle/init.gradle` 若存在语法错误会直接让构建失败，且它会覆盖项目仓库声明，
需要时先检查。

- 本机 `core.autocrlf = true`，`*GuardTest` 有多行文本断言，**本地单测可能假失败**；
  CI（Linux/LF）为准。判别法：失败集中在多行断言 + `git diff` 对应源文件无改动 → 是 CRLF 假象。

### 8.4 下一步任务

**已完成：Next-1 / Next-2**（见 8.1、8.2），baseline 1723 → 1504。

**Next-3（可选，建议暂缓）：`LocalContextGetResourceValueCall` 剩余的"提升变量"改造**

实测结论：**可安全机械替换的空间已基本穷尽**。
全库 877 处 `X.getString(...)` 调用中，本次替换了 130 处；剩余 442 条里绝大多数分布在
回调 lambda / 协程 `launch` / `Toast` / `buildString` 等**一次性消费**场景
（值被立即消费，不随配置变更重组 → 非 bug，按 P2 决策保留抑制）。典型分布：

| 场景 | 示例 | 能否机械替换 |
|------|------|--------------|
| Toast 提示 | `Toast.makeText(ctx, ctx.getString(...), ...)` | 否（在回调内） |
| 错误消息赋值 | `errorMessage = ctx.getString(...)`（onClick 内） | 否（在回调内） |
| 协程内上报 | `scope.launch { ... ctx.getString(...) }` | 否（`launch` 非可组合） |
| `buildString` 拼接 | `buildString { append(ctx.getString(...)) }` | 否 |

唯一能继续的路径是**提升变量**——在 `@Composable` 作用域先取值再传进 lambda：

```kotlin
// 改前（回调内取值，lint 仍报，但属非 bug）
onClick = { Toast.makeText(ctx, ctx.getString(R.string.copied), LENGTH_SHORT).show() }

// 改后（提升到 composable 作用域）
val copiedMsg = stringResource(R.string.copied)
onClick = { Toast.makeText(ctx, copiedMsg, LENGTH_SHORT).show() }
```

- 收益：理论上最多再清约 200 条，但需逐处理解语义，**无法机械替换**。
- 风险：中。改的是事件路径，编译能过但错误只在运行时暴露。
- 建议：**暂缓**。当前占比最大的是 `UnusedResources` 887 条（59%），
  做一次真·清理（删掉确实无引用的资源）收益更确定。

**不做（已决策）**：
- `UnusedResources` 887、`UnusedAttribute` 84、`UseKtx` 87、`AcceptsUserCertificates` 1、`HardwareIds` 1 → 保持抑制。
- 依赖升级类 → 走[依赖升级计划](./dependency-upgrade-plan-2026-08.md)，不与 lint 批次混做。
- `RestrictedApi` 已清零；`GradleDependency` 5 条僵尸已随 regen#3 清除，无需再排期。

### 8.5 配套工具（已入库）

路径均为相对定位，换机器可直接用（需先装好 Android SDK 并配 `local.properties`）。

| 脚本 | 用途 | 用法 |
|------|------|------|
| `Bastion/tools/lint/lint_stat.py` | 统计 baseline 条目与种类分布，支持快照对比 | `python3 lint_stat.py` / `--save 名字` / `--diff 快照.json` |
| `Bastion/tools/lint/analyze_scope.py` | 作用域感知地列出某文件中**可安全**替换为 `stringResource` 的 `getString` 调用，并给出不安全原因 | `python3 analyze_scope.py com/.../XxxScreen.kt` |
| `Bastion/tools/lint/apply_string_resource.py` | 执行替换（自动补 import、写 `.bak`、支持 `--revert`），默认干跑 | `python3 apply_string_resource.py com/.../XxxScreen.kt --apply` |
| `Bastion/tools/env/refresh_hosts.sh` | DoH 解析 + 测速选优，把 GitHub / Gradle / Maven 真实 IP 写回 hosts（含 `~/.user_hosts`） | `bash refresh_hosts.sh` |

Next-2 的替换即由 `analyze_scope.py` + `apply_string_resource.py` 完成；
若将来要推进 Next-3，先改这两个脚本的放行/拦截规则，再**编译驱动**迭代修正即可。
