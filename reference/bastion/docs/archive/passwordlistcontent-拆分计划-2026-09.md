# PasswordListContent 拆分计划（让 ART 能 JIT 编译）

> 生成时间：2026-09-05
> 起点提交：`50c6192`（第一步已合入 dev / main）
> 数据来源：开发日志导出 `bastion_logs_20260905_014034.txt`（2150 行 / 43 秒）
> 相关文档：[lint 债务清理计划](./lint债务清理计划.md) · [compose-material3-api-migration-plan-2026-09](./compose-material3-api-migration-plan-2026-09.md)

---

## 零、一句话结论

`PasswordListContent` 一个函数 1595 行，编译后 **18895 条指令**，超过 ART 的方法编译上限，导致**永远无法被 JIT 编译、只能解释执行**，且每次尝试编译都会刷一条 `Method exceeds compiler instruction limit`（实测 428 次）。

要把这块拆到上限以下，需要分多轮，按"内聚度"逐块把状态与 UI 区块下沉到独立 Composable。**已完成第一步**（快捷筛选开关下沉，`50c6192`）。后续步骤见 [三、下一步候选](#三下一步候选按内聚度排序)。

---

## 一、背景与问题

### 1.1 现象

实测 logcat：

```
I com.bastion.app: Method exceeds compiler instruction limit: 18895 in void
  com.bastion.app.ui.PasswordListContentKt.PasswordListContent(...)
```

- 一份 2150 行的开发日志里，这一条**刷了 428 次**（占应用进程日志的 90%）。
- 同一时段还观察到主线程栈顶 `androidx.compose.runtime.GapComposer.updateValue`——说明确实有真实的重组耗时，不只是日志噪音。

### 1.2 根因

ART 对单方法有指令数上限（约 16K，因版本略有差异）。超过就**拒绝 JIT 编译**，方法只能走解释执行：
- 列表重组性能受损
- 每次尝试编译都打一条警告

### 1.3 目标

降到上限以下，让 JIT 能编译 `PasswordListContent`。**唯一权威判定方式**：装包后 logcat 不再出现 `Method exceeds compiler instruction limit`。

---

## 二、现状（截至 `50c6192`）

### 2.1 文件与函数范围

`app/src/main/java/com/bastion/app/ui/password/PasswordListContent.kt`：

| 函数 / 类 | 行号（参考，会变） | 说明 |
|------|------|------|
| `PasswordListContent` | 313 - ~1907 | 主函数，1595 行，**超标点** |
| `PasswordListQuickFilterToggles`（class） | ~1883 | 第一步新增的状态容器 |
| `rememberPasswordListQuickFilterToggles` | ~1930 | 第一步新增的工厂 Composable |
| `PasswordListMainPaneHost` | ~2001 | 已存在的子 Composable（**参数签名不要动**） |
| 嵌套 `RenderPasswordListTopSection` | ~1550 | 嵌套在 PasswordListContent 内，转发 ~60 个参数 |
| 嵌套 `RenderPasswordListMainPaneHost` | ~1647 | 嵌套在 PasswordListContent 内，转发 ~100 个参数 |

> ⚠️ 行号会随每步拆分变化。**定位时用函数名/字符串搜索，不要靠行号。**

### 2.2 第一步（`50c6192`）做了什么

下沉"快捷筛选"状态块（原 719-753）：

```kotlin
// 原来：15 个开关 + 3 个派生 + 3 个清空 effect，全内联
var quickFilterFavorite by rememberSaveable { mutableStateOf(false) }
// ... 14 个类似
val hasAnyWifiEntry = remember(passwordEntries) { passwordEntries.any { it.isWifiEntry() } }
LaunchedEffect(hasAnyWifiEntry) { if (!hasAnyWifiEntry) quickFilterWifi = false }
// ...

// 现在：1 行调用
val quickFilterToggles = rememberPasswordListQuickFilterToggles(passwordEntries)
```

15 个开关 + WIFI/SSH/条码三个"是否存在对应条目"的派生值 + 3 个清空 `LaunchedEffect` 全部进 `rememberPasswordListQuickFilterToggles` 与容器 `PasswordListQuickFilterToggles`。父函数处由 35 行声明收缩为 1 行调用。

### 2.3 必须遵守的约束（后续步骤同样适用）

1. **各开关仍用 `rememberSaveable`**——屏幕旋转、Activity 重建后的恢复语义不变。
2. **状态容器每次重组同步最新值**，读取方拿到的值与原先逐个读布尔完全等价。
3. **重置副作用、筛选函数入参、两个 UI 组件的 setter 回调均经容器转发**；**组件契约未动**（`PasswordListMainPaneHost` 的参数名保持原名）。
4. 行为严格等价，**不做功能改动**。

---

## 三、下一步候选（按内聚度排序）

### 3.1 第二步：`configuredQuickFilterItems` + 重置 effect

**范围**（行号以当前代码为准）：

```kotlin
val configuredQuickFilterItems = remember(
    appSettings.passwordPageAggregateEnabled,
    aggregateUiState.visibleContentTypes
) {
    appendAggregateContentQuickFilterItems(
        configuredItems = resolvedQuickFilterBaseItems(appSettings.passwordListQuickFilterItems),
        visibleTypes = aggregateUiState.visibleContentTypes,
        aggregateEnabled = appSettings.passwordPageAggregateEnabled,
        includePasskeyChip = false
    )
}

LaunchedEffect(configuredQuickFilterItems) {
    if (FAVORITE !in configuredQuickFilterItems) quickFilterToggles.favorite = false
    // ... 11 个类似
}
```

**依赖**：`appSettings`、`aggregateUiState`、`quickFilterToggles`（已下沉）

**做法**：
- 抽到 `@Composable private fun rememberPasswordListConfiguredQuickFilterItems(appSettings, aggregateUiState): List<PasswordListQuickFilterItem>`
- 重置 effect 一并搬进去（访问 `quickFilterToggles` 的 setter，把它作为参数传入）

**收益估计**：约 30 行 + 1 个 effect。

### 3.2 第三步：两个嵌套 Composable（单步最大收益）

**范围**：
- `RenderPasswordListTopSection`（~1550-1637，转发 ~60 个参数给 `PasswordListTopSection`）
- `RenderPasswordListMainPaneHost`（~1647-1748，转发 ~100 个参数给 `PasswordListMainPaneHost`）

**做法**：从嵌套（在 `PasswordListContent` 内）提升为顶层 `private @Composable fun`。

**为什么有效**：嵌套函数能访问父函数局部变量 → 提升后这些变量必须显式传参；**参数值计算**（如 `isAuthenticated && topActionsMenuExpanded`、setter lambda `{ x = it }`）随之移到新函数内 → 父函数指令数真正减少。

**注意**：
- 参数极多（~60 / ~100），但都是机械转发。**推荐用 IDE 的 Extract Function 重构**，比手写可靠。
- 提升后，父函数仍持有那些状态（被多处使用），只是 UI 调用 + 参数计算移走。

**收益估计**：~160 行参数计算移走，是单步最大收益。

### 3.3 第四步：其余状态与派生（⏸ 待实机数据决策 + 需用户确认后动手）

约 80 个 `collectAsState` + 大量 `remember` / `derivedStateOf` + 23 个 `LaunchedEffect`/`BackHandler`。按职责分组继续下沉：

- 滚动状态（`listState`、`listTopPadding`、滚动重置 effect）
- 选择状态（`isSelectionMode`、`selectedItemKeys`、`swipeSelectionAnchorKey` + 相关 effect）
- 对话框状态（`showMoveToCategoryDialog`、`showManualStackConfirmDialog`、`showBatchDeleteDialog` 等）
- 派生筛选结果（`preStackFilteredPasswordEntries`、`visiblePasswordEntries`、`visibleAggregateItems`）

每组下沉为一个独立 Composable + 状态容器。

**⏸ 决策门槛（2026-09-05 补充）**：步骤 0-3 完成后主函数余 1410 行（签名 36 + 函数体 1374）。第四步与前三步不同，属于**语义有风险的重点改动**，需满足两个前置条件才动手：
1. **实机数据支撑**：装包实测后 `compiler instruction limit` 警告仍在，才值得继续；若已消失则停止（避免无收益重构）。
2. **用户确认方案**：推荐用 `rememberXxxState()` 状态容器模式（与已完成的 `rememberPasswordListQuickFilterToggles` 同款），**不要再开 Host**——状态下沉后调用处仍需读写这些状态，再开 Host 会导致参数回调爆炸。分组顺序建议：对话框状态 → 选择状态 → 滚动状态 → 派生筛选结果（前两组机械安全，后两组涉及重组边界，需逐组装包回归）。

> 接力的 AI：第四步动手前务必确认上面两个前置条件均已满足。

---

## 四、验证

每步合入后装 debug 包：

```bash
# 清空旧日志
adb logcat -c

# 滚动密码列表、打开详情、做几个操作

# 看是否还有编译器警告
adb logcat -d -v time --pid=$(adb shell pidof com.bastion.app) | grep "compiler instruction limit"
```

- **还有** → 继续下一步
- **没了** → 完成，JIT 已生效

**不要用本地 `.class` 大小估收益**——ART 报的是 dex 指令数，与 JVM 字节码不是同一度量，靠装包验证才是权威。

---

## 五、已踩的坑（务必避开）

### 5.1 `@Stable` 不能用于 class

```kotlin
@Stable private class Xxx   // ❌ 编译失败：annotation is not applicable to target 'class'
private class Xxx           // ✅ 普通即可
```

`@Stable` 的适用目标是 function / type usage / type parameter / getter，**不适用于 class**。

### 5.2 新代码插进注解与函数名之间

**错误锚点**：`private fun PasswordListMainPaneHost(`

```kotlin
// 原文件
@Composable
private fun PasswordListMainPaneHost(...)

// 用上面那个锚点插入新代码 → 结果：
@Composable
// 新插入的 class / 函数（一堆）
private fun PasswordListMainPaneHost(...)   // ← @Composable 被"抢走"，修饰了新插入的 class
```

**正确做法**：锚点选注解**之前**的独立位置（如前一个函数的结束 `}` + 空行），或用 IDE 的"在此处插入"功能。

### 5.3 批量正则改名的三类误伤

用 sed / PowerShell `-replace` 批量改变量名（如 `quickFilterFavorite` → `quickFilterToggles.favorite`）会误伤：

1. **函数定义的参数名**：`quickFilterFavorite: Boolean,` → 被改成 `quickFilterToggles.favorite: Boolean,`（语法错误）
2. **函数调用的命名参数名**：`quickFilterFavorite = false,`（左侧）→ 被改成 `quickFilterToggles.favorite = false,`（"Only expressions are allowed in this context"）
3. **同文件其他函数的同名局部变量**：如 `PasswordListMainPaneHost` 函数体内它自己的参数 `quickFilterFavorite`

**对策**：大范围改名**优先用 IDE 的 rename symbol**（语义级，不误伤）。如果只能用正则，替换后必须**靠编译迭代修复**，且修复要按"函数定义 → 命名参数 → 函数体引用"分类逐一处理。本次第一步靠编译迭代了 4 轮才干净。

### 5.4 命名参数 vs 赋值

Kotlin 的命名参数 `xxx = value` 与赋值 `xxx = value` 在文本上完全一样，但语义不同。批量替换无法区分，必须靠编译错误定位修复。

---

## 六、禁止动作

- ❌ 不要改 `PasswordListTopSection` / `PasswordListMainPaneHost` 等子组件的**参数签名**——它们被多处调用，改动会牵连太广。
- ❌ 不要把 `rememberSaveable` 改成 `remember`——会丢失旋屏恢复。
- ❌ 不要在没有装包验证的情况下"猜"收益——靠 logcat 的 ART 警告判定。
- ❌ 不要一次拆太多块——每步合入后装包验证，确认行为无回归再继续。

---

## 七、参考提交

| 提交 | 说明 |
|------|------|
| `50c6192` | 第一步：快捷筛选状态下沉（本计划的起点） |
| `bbdd921` | 日志风暴修复：`extractHost` / `parseWebsite` 改静默 `runCatching` |
| `77ecffe` | 看门狗：息屏/低功耗下心跳延迟误判为主线程卡死 |
| `d9fa20e` | 看门狗：心跳链断裂导致 `blockedForMs` 无限累加 |
| `feec0cf` | 滑动删除改长按激活（方案 A） |
| `1fd8490` | WiFi/SSH/笔记/证件 编辑页移动语义对齐 |
| `843a73b` | 恢复验证器/通行密钥/卡包页的快捷筛选入口 |

---

## 八、执行进度

| 步骤 | 提交 | 状态 | 说明 |
|------|------|------|------|
| 0 | `50c6192` | ✅ 已完成 | 快捷筛选状态下沉 |
| 1 | `a7702b7` | ✅ 已完成（CI #33908818507 绿） | `configuredQuickFilterItems` + 重置 effect 下沉为 `rememberPasswordListConfiguredQuickFilterItems` |
| 2 | `530a674` + `38bc726` | ✅ 已完成（CI #33934428304 绿） | 两个嵌套 Composable 提升为顶层 `PasswordListTopSectionHost` / `PasswordListMainPaneSection`；quickFilter 32 参数收拢为 `quickFilterToggles` 容器 |
| 3 | `95954e3` + `c8efb57f` | ✅ 已完成（CI 绿） | 三个大块调用全部提升为顶层 Host：`PasswordListQuickStatusDialogsHost`（10 参数）/ `PasswordListDialogsHost`（31 参数）/ `PasswordBatchMoveSheetHost`（19 参数）；主函数余 1410 行 |
| 实测 | c8efb57 包 | ⚠️ 未达标 | 2026-09-05 荣耀真机日志：`PasswordListContent` **18199 指令**（拆分前 18895，仅降 696/3.7%），330 次警告持续触发，仍超 16384 上限；`SimpleMainScreen` 16652 指令 17 次警告（另一大户，需单独拆）。结论：**调用块提升收益已尽，须做第四步状态下沉** |
| 4 | `323cb9c6` + `af476fa9` | ✅ 批1 已完成（CI 绿） | 对话框状态（17 var）下沉为 `PasswordListDialogState` 容器（独立文件，internal）；主函数 55 处读写点改 `dialogState.xxx`；`af476fa9` 同步修复源码守卫测试断言（CI #33938599186 红→绿） |
| 4 | `377d1c64` | ✅ 批2 已完成（CI 绿） | 选择状态（3 var）下沉为 `PasswordListSelectionState` 容器；主函数 40 处替换；守卫测试断言同步更新 |
| 4 | `1946ef5d` + `32e28c3d` | ✅ 批3 已完成（CI #33940176619 绿） | 滚动状态（listState/收起判定/顶距/空态防抖）下沉为 `PasswordListScrollState` 容器；`32e28c3d` 修复：**委托属性不能用 `private set`**，改 val 委托只读暴露 |
| 4 | `55223e27` + `a33cf757` | ✅ 批4 已完成（CI #33941279186 绿） | manualStack 元数据 + 派生筛选链（groupingConfig→preStack 过滤→聚合堆叠→可见列表）下沉为 `PasswordListManualStackMeta` / `PasswordListDerivedFilters` 两个容器（同文件 private）；主函数删除 223 行（315..1468，体 1152 行，起始 1410 行）；`a33cf757` 修复编译（补 2 个异包 import + 容器可见性 private） |
| 实测 | 77d70057 包 | ✅ PasswordListContent 达标 / ⚠️ SimpleMainScreen 仍超 | 2026-09-05 14:14 荣耀真机日志（主界面进出条目场景，版本 1.0.0-dev-77d7005）：**`PasswordListContent` 警告 0 次（批 4 生效，从 18199 降到 16384 以下）**；`SimpleMainScreen` **16652 指令 × 1 次警告**（超上限 268 条 / 1.6%）。结论：PasswordListContent 拆分收官；剩余目标改为 SimpleMainScreen，进入第五批（批 5） |
| 5 | `6ecf9a1`（待补） | ✅ 已完成（实测达标） | 三组跨 tab 选择状态（TOTP/证件/银行卡，19 个 `var by remember` 注册 + 105 处引用）下沉为 `CrossTabSelectionState` 容器（`ui/CrossTabSelectionState.kt`，internal）。**实测结论：2026-09-05 20:04 荣耀真机日志（版本 `1.0.0-dev-47b56c1`，进程全生命周期 36 秒，含密码列表点条目/返回路径）`compiler instruction limit` 警告 0 条**——`PasswordListContent` 与 `SimpleMainScreen` 双双降至 16384 以下，**JIT 拆分任务整体收官** |

**实测数据明细（2026-09-05 10:04 导出，版本 1.0.0-dev-c8efb57）**：
- 警告分布：`PasswordListContent` 330 次 / `SimpleMainScreen` 17 次，从启动 10:03:50 起每秒约 30 条持续触发（非一次性）
- 主函数体量构成（c8efb57f 时点）：remember ×92、collectAsState ×17、LaunchedEffect ×18、BackHandler ×4、DisposableEffect ×1、derivedStateOf ×1；最大 UI 块 `Box`（1488-1612，125 行，内为 MainPaneSection 转发层，提升收益趋近零）
- **指令大头在状态注册区（约 1080 行）**，而非 UI 调用块——继续提升调用块无收益，必须状态下沉

> 接力的 AI：完成一步后请更新本表 + 在 [二、现状](#二现状截至-50c6192) 补充新提交的函数范围。
>
> **步骤 2 实施备注（供后续参考）**：
> - host 函数参数类型直接从子组件签名复制（零推断风险）；调用处除「修改外部 var 的 setter lambda」外全部 `xxx = xxx` 同名转发；
> - 修改外部 var 的 13 个 setter lambda 仍由主函数创建后传入（捕获语义不变）；
> - 踩坑记录：提升为顶层后参数需要显式类型名，`BitwardenViewModel`、`CoroutineScope` 此前从未被 import（嵌套函数靠类型推断），需补齐（`38bc726`）。
>
> **步骤 3 实施备注（供后续参考）**：
> - 4 个赋值 lambda（QS）+ 12 个赋值 lambda（DLG，含超大的 `onDeleteSelection` 整体转发）仍由主函数创建，host 内 `xxx = xxx` 简单转发，捕获语义不变；
> - `selectedCount` 不再作为 host 参数，host 内直接 `selectedItemKeys.size` 计算；
> - 踩坑记录：`PasswordBatchTransferGlobalProgressState` / `PasswordBatchDeleteGlobalProgressState` 定义在 `com.bastion.app.ui.password` 包，而 PasswordListContent.kt 的 package 是 `com.bastion.app.ui`——**同名目录≠同包**，参数类型需写全限定名（参照 MainPaneSection 写法）；`ManualStackDialogMode` / `QuickStatusKeePassSyncState` 虽然文件在 `ui/password/` 目录，但 package 声明是 `com.bastion.app.ui`，同包短名可直接用；
> - 校验手段：内容锚定 + 括号配平定位调用块（行号漂移免疫），插入后全文件 `()` `{}` 配平 0/0、host 调用/定义各 1、全部参数类型按「import ∪ 同包 ∪ 内建」规则判定可解析。
>
> **步骤 4 实施备注（供后续参考）**：
> - 四批全部采用「状态容器」模式：`internal/private class XxxState`（字段 `var x by mutableStateOf(...)`）+ `@Composable fun rememberXxxState(...)`，主函数读写点改为 `stateName.field` 属性访问——重组订阅语义与原 var 完全等价；
> - **源码守卫测试是最大的回归面**：`MultiPasswordSaveRegressionGuardTest` 用 `source.contains("...源码文本...")` 断言实现细节，每批改动后都要同步更新断言（读容器文件 + 加前缀）。修复前先本地用 Python 模拟全部断言再提交，避免 CI 往返；
> - **批 3 踩坑**：`var x by State<T>` 之后不能写 `private set`（Kotlin 委托属性不允许自定义 accessor，编译错误）——容器对内用 MutableState 实例写值，对外 val 委托只读暴露；
> - **批 4 踩坑 1（前向引用）**：容器调用插在主函数的位置必须满足依赖顺序——`rememberPasswordListManualStackMeta` 依赖 `shouldLoadManualStackMetadata`，插入点必须在该 val 定义之后，不能想当然放在原 var 声明位置；
> - **批 4 踩坑 2（异包类型，同步骤 3 的坑又踩一次）**：`PasswordAggregateManualStackBuildResult`（`com.bastion.app.ui.password` 包）与 `PasswordPageAggregateStackEntry`（`com.bastion.app.data` 包）此前从未在 PasswordListContent.kt 中以裸名出现（靠类型推断），容器类签名首次写出裸名即 unresolved——**凡容器签名新写出的类型名，逐一核对「import ∪ 同包 ∪ 同文件」**；
> - **批 4 踩坑 3（可见性）**：`internal` 函数不能暴露 file-private 类型（`PasswordListQuickFilterToggles` 是 private class）——同文件容器函数应声明 `private`；private class 的属性可以暴露 internal 类型（可见性放宽方向合法）；
> - **批 4 搬移零修改技巧**：派生链搬进容器时靠局部别名（`val effectiveManualStackGroupByEntryId = manualStackMeta.effectiveManualStackGroupByEntryId`）保持搬移代码原文不变，降低搬运 diff 风险；
> - **语义等价性论证**：容器函数每次重组返回新对象，但下游 remember keys 全是值（Map/Set equals 内容比较），与原先「每重组重算 effectiveXxx val」行为一致，不引入多余重组。

---

## 九、第五批（批 5）：SimpleMainScreen 指令超限（✅ 已收官，2026-09-05 实测 0 警告）

> **收官结论**：JIT 指令上限问题已全部解决（PasswordListContent + SimpleMainScreen）。
> 后续性能工作转入「重组/布局」方向，见 [十、批 6](#十批-6重组与布局方向)。

### 9.1 背景（2026-09-05 实测修正）

批 4 装包实测（版本 1.0.0-dev-77d7005，荣耀真机，主界面进出条目场景）：

| 函数 | 拆分前 | 当前 | 状态 |
|------|--------|------|------|
| `PasswordListContent` | 18895（330 次警告） | **16384 以下（0 次警告）** | ✅ 收官 |
| `SimpleMainScreen` | 16652（17 次警告） | **16652（1 次警告）** | ⚠️ 超上限 268 条（1.6%） |

**原批 5 预判修正**：本文档早前预判"批 5 = `groupedPasswords` 分组段下沉"（指向 PasswordListContent 链路，43 处引用）。实测 PasswordListContent 已达标，**该预判作废，批 5 目标修正为 `SimpleMainScreen`**（`ui/SimpleMainScreen.kt:750`，约 60 参数、函数体约 2500 行）。

### 9.2 目标

`SimpleMainScreen` 指令数 < 16384。**只需减 268+ 条（1.6%），拆一段中等复杂度的状态段即可达标**，属小批次收尾，非大批次重构。

### 9.3 方案：沿用批 4 已验证的状态容器模式

批 4 结论"指令大头在状态注册区（remember/collectAsState 的内联 Compose 代码），而非 UI 调用块"对 SimpleMainScreen 同样适用。注意：Kotlin 局部函数（`RenderMainSurface()`、`buildMainScreenHandlers()`、`tryActivateQueuedMiniHints()`）编译为独立合成方法，**不计入**主函数指令数，拆它们无收益。

**候选下沉段（动手前需按函数体实测定夺，优先级序）**：

1. **passwordPage 内容类型段**：`passwordPageVisibleContentTypes`（remember 派生）+ `passwordPageSelectedContentTypes`（rememberSaveable）+ 相关联动——一组内聚的派生状态，独立性强，预计单段减 200-400 条。
2. **密码历史页模式段**：`passwordHistoryPageMode` + `passwordHistoryInitialTrashScopeKey` + 关联 rememberSaveable。
3. **Bitwarden 页上下文段**：`isBitwardenPageContext` / `bitwardenStatusVaultId` 两个 when 派生 + 相关联动。

任选其一实施后装包验证，达标即停（不为"更优雅"继续拆）。

### 9.4 实施注意（沿用批 4 踩坑清单）

- 容器模式：`internal/private class XxxState`（`var x by mutableStateOf(...)`）+ `@Composable fun rememberXxxState(...)`，主函数读写点改 `state.field`；
- **前向引用**：容器调用插入点必须在依赖的 val 之后；
- **异包类型**：容器签名首次写出的类型名逐一核对「import ∪ 同包 ∪ 同文件」；
- **源码守卫测试**：`MultiPasswordSaveRegressionGuardTest` 有源码文本断言，改后同步更新；
- SimpleMainScreen 参数 60+ 且被 `main/navigation` 多处调用，**不动签名**，只拆函数体内部状态。

### 9.5 验证

1. 本地：`scripts/localcheck.sh`（compileDebugKotlin）绿；
2. CI：dev 分支 Android CI 绿；
3. 装包实测：主界面进出条目 + 各 tab 切换后导出日志，`grep -i 'instruction limit'` 期望 **0 命中**；
4. 回归确认：密码列表滚动/多选/对话框/聚合堆叠行为无变化。

**实测结果（2026-09-05 20:04，荣耀 BKQ-AN00 / API 37，版本 `1.0.0-dev-47b56c1`）**：
日志覆盖进程启动至导出的完整 36 秒（含密码列表点开条目、返回、滚动恢复路径），
`instruction limit` **0 命中** ✅，第 1-3 项均通过。

---

## 十、批 6：重组与布局方向（🔄 进行中）

指令上限解决后，实机卡顿的主因转向「每帧重组 / 每帧重新布局」。

### 10.1 已修：顶栏胶囊逐帧回调触发整屏重组（`6ef9262b`）

**根因链**：

```
ExpressiveTopBar 胶囊 onGloballyPositioned   （顶栏收起/展开动画期间逐帧回调）
  → categoryPillBoundsInWindow = bounds      （写 State）
  → PasswordListContent / TotpListContent / PasskeyListScreen / CardWalletScreen / NoteListScreen
    主函数读取该 state（订阅）
  → 200ms 动画内每帧重组整个列表屏幕
```

关键点：该 bounds 在**全部 5 个界面都无消费方**（`PasswordListTopSection` 接收后从未使用），是死状态。

**修复**：5 个界面停用 `onActionPillBoundsChanged` 回调（密码界面保留参数链路不动 Host 签名，其余 4 个直接移除死状态）。

### 10.2 已修：胶囊内按钮缩放与外框不同步（`6ef9262b`）

`ExpressiveTopBar` 顶栏所有属性都走 200ms 动画，唯独按钮组缩放直读快照值 `scrollCollapseFraction`
→ 切换瞬间硬跳一下（用户手感为"卡顿"，实为无插值的突变）。改为跟随新增的 `collapseProgress`（同一条 200ms 插值）。

### 10.3 已修：密码条目返回重影（`646b15b5`）

`PasswordEntryCard.sharedBounds` 原用 `ResizeMode.RemeasureToBounds`：详情返回列表时，
详情页内容被按卡片尺寸**重新布局**后再绘制 → 短暂出现"条目仍是打开状态"的重影。
卡片类共享元素改 `ResizeMode.scaleToBounds()`：内容不重排，只做尺寸过渡。

### 10.4 诊断手段：滚动掉帧采样（`646b15b5`）

新增 `ui/perf/ScrollJankMonitor.kt`：`ScrollJankReporter(listState, label)` 在滚动期间逐帧采样，
**仅在一次滚动结束且确实掉帧时输出一行摘要**，正常滚动完全静默：

```
ScrollPerf: jank label=passwords frames=142 janky=9(6%) avg=17.1ms max=68ms
```

已接入密码 / TOTP / 通行密钥三个列表（卡包用 reorderable state，类型不同未接入）。
用途：量化卡顿、验证修复效果，避免"凭手感"判断。

**挂载确认行**（后补）：采样器只在掉帧时输出，导致"日志里 0 行 ScrollPerf"无法区分
"滚动真的流畅"还是"采样器没挂上"。因此每个 label 在整进程内打**一次**挂载确认：

```
ScrollPerf: attach label=passwords
```

判定口诀：装包后滚一遍列表 → 日志里**必须有 attach 行**（证明采样器在跑），
attach 之后**没有 jank 行**（证明真的不掉帧）。二者缺一都说明结论不成立。

### 10.5 待观察项（本次未动，需实测数据决定）

下列项在顶栏收起/展开的 200ms 内也会持续变化，若批 6 装包后仍有掉帧，按此顺序处理：

1. **列表顶部 padding 动画**（`PasswordListScrollState.listTopPadding`）：72→48dp 联动，
   LazyColumn 的 contentPadding 每帧变化 → 可见项每帧重新 measure/layout。
   如需修：可改为「动画期间用固定 padding，动画结束后一次性对齐」（视觉取舍，需确认）。
2. **标题字号动画**（`ExpressiveTopBar.titleFontSize` 32→16sp）：每帧触发 Text 重新布局；
   且 `onTextLayout` 里回写 `titleAutoScale` 会形成布局→状态→重组的连锁。
   如需修：字号改为整数档位切换（避免每帧重排）或收起时不再缩放字号（纯视觉取舍）。

> 接力的 AI：处理 10.5 前请先导出日志确认 `ScrollPerf` 是否仍有 jank，避免无收益改动。

### 10.6 日志降噪（2026-09-05，同批提交）

导出日志时发现两类纯噪音、且会掩盖真实故障，已改：

1. **协程取消被当故障记录**：`logSwallowed` 对 `j.u.c.CancellationException` 家族
   （含 internal 的 `JobCancellationException`）**完全静默**——取消是控制流（服务断开、
   界面退出、作用域取消），实测一次 OTP 通知会话结束就连打 11 条完整堆栈。
   行为由 `SwallowedExceptionLoggerTest.logSwallowed_ignoresCancellationException` 锁住：
   用例里真实 `async{}.cancel()` + `await()` 取出 `JobCancellationException` 实例
   （该类是 internal，测试内无法直接构造；`joinAll` 一个被取消的 job 不会抛，必须用 `await`）。
2. **探测性解析打完整堆栈**：`PasskeyCredentialIdCodec` 的 UUID / Base64 探测改用静默
   `runCatching`——credentialId "不是 UUID" 是预期分支而非故障
   （原实现一次 github 登录打 2 份 17 行堆栈）。

> 日志准则：**能定位问题的才打**。控制流、预期分支、正常路径一律静默；
> 真实故障保留完整堆栈 + 每 tag 60 秒 50 条限频。

### 10.7 本地构建环境踩坑（沙箱专用，CI 不受影响）

- Kotlin 编译器 `java.lang.InternalError: a fault occurred in an unsafe memory access operation`
  （`jarfs.LargeDynamicMappedBuffer` mmap 失败）：加 `-Dkotlin.compiler.jvm.fast.jar.fs.mode=disabled`
  即可绕过，与代码改动无关。
- 本地跑单测约 53 条失败，全部来自 JDK 20 + android.jar stub：
  mockk inline instrumentation 的 `InternalError` 与 `ClassFormatError: Absent Code attribute`。
  CI 用 JDK 17，这些类在 CI 上是绿的；判定回归时按**堆栈特征**过滤，别误判为改动引入。
