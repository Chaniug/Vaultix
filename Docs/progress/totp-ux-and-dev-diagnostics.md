# 验证码 UX + 设置页丰富 + 开发者诊断 —— 自包含工作单（2026-09-21）

> **用途**：让一个**全新会话（零上下文）**照着一项一项做完。
> **顺序建议**：§1 → §2 → §3 → §5 → §4（§1/§2 最小且用户已明确要；§4 依赖 §5 的取舍结论）。
> ⚠️ 施工纪律见 [`settings-rework.md`](./settings-rework.md) **§0**（门禁必须含 test、detekt 两种口径、
> 装机必比 SHA-256、commit message 不用反引号…），**先读那份 §0，本文不重复**。

---

## §1 验证码页：点标题隐藏数字（保留前 3 位 / 跨重启保持 / 全页统一）

### 用户原话

> 「点击验证码页面的验证码 3 个字就能够隐藏一部分，比如 6 位数的验证码，隐藏到只显示 3 位数。」
> 「类似密码条目页面，点击 bitwarden 字就能收拢展开的样子。」「默认是展开。」
> 「点击隐藏后，不再次点击，之后不论是打开还是关闭 APP，都是保持隐藏的。」
> 「隐藏的时候并不能影响正常的点击复制、正常的填充。」

### 现状（已核）

- 顶栏在 `app/src/main/java/io/vaultix/vaultix/ui/totp/TotpCodesScreen.kt:498`：
  `VaultixExpressiveTopBar(title = stringResource(R.string.totp_screen_title))`。
- ★ **该组件已支持 `onTitleClick` + `titleExpanded` + `titleClickHint`**（列表页用它做"点库名展开快捷筛选"）
  ⇒ **箭头、点击、无障碍提示都是现成的**，不需要新写交互。
- Vaultix **没有任何隐藏/遮罩能力**（`grep -i "mask\|hide" totp/` 无命中）。

### 改法

1. **偏好**（`core/datastore/src/main/java/io/vaultix/datastore/VaultixPreferences.kt`，与 `SCREEN_SECURITY` 同处）：
   `val TOTP_CODES_HIDDEN = booleanPreferencesKey("totp_codes_hidden")`，**默认 `false`（= 展开）**。
2. **接线**（`TotpCodesScreen.kt:498`）：
   `titleExpanded = !hidden`、`onTitleClick = { 取反并写偏好 }`、`titleClickHint` 给出"点此隐藏/显示验证码"。
3. **遮罩函数**（放 `ui/totp/`，**纯函数便于单测**）：
   `maskCode(code: String): String` = 保留前 `maxOf(3, code.length / 2)` 位，其余替换为 `•`。
   ⇒ 6 位→留 3、8 位→留 4。（用户明确要"6 位留 3 位"。）
4. **渲染**：只有**显示**套遮罩；**复制仍用原始 `codeToCopy`**（见 `:697`），自动填充路径完全不动。
5. ⚠️ **不要**做"每张卡各自隐藏"：那需要把点击落在卡片上，会与"点卡片即复制"抢手势。

### 验收

- [ ] 点顶栏「验证码」→ 全页所有码只显示前 3 位（6 位码）；再点恢复
- [ ] **杀进程重开 App** → 仍保持上次的隐藏/展开状态
- [ ] 隐藏态下点卡片复制 → 剪贴板里是**完整**验证码
- [ ] 隐藏态下自动填充 → 正常
- [ ] `maskCode` 单测：6→3、8→4、空串、非数字码（Bitwarden 也可能给字母）

---

## §2 验证码页：临期"复制下一个码"加开关（+ 可选震动）

### ★ 先看现状（别重复造）

**行为早就有了**（`TotpCodesScreen.kt:687-697`，2026-09-18 落地，注释里引的就是用户原话）：

```kotlin
val isExpiring = !isHotp && remaining <= TOTP_HOT_WARNING_SECONDS
val codeToCopy = if (isExpiring) nextCode else code
```

⚠️ **语义是"你点复制的那一刻给哪个码"**，**不是**定时器自动写剪贴板。
参考实现 Bastion 也是如此（`reference/bastion/.../ui/components/TotpCodeCard.kt:236-245` +
`onClick` 在 `:312-329`）；它全仓库写剪贴板只有 4 处，**没有一处在定时器里**。
⇒ 「到 5 秒自动复制」在 Android 上意义有限：**API 29+ 只有前台应用能写剪贴板**，
而需要它的场景（用户在别的 App 等码）恰恰是前台之外。**故不做。**

### 改法

1. 偏好：`TOTP_COPY_NEXT_ON_EXPIRING = booleanPreferencesKey("totp_copy_next_on_expiring")`，**默认 `true`**（保持现状）。
2. `codeToCopy` 的前置条件加该开关。
3. 设置页加 Switch（放在验证码/外观相关分组）。文案参考 Bastion「即将过期时复制新验证码」。
4. **可选**：≤5 秒震动反馈（Bastion 有 `validatorVibrationEnabled` + `VibrationPatterns.TICK`；Vaultix 没有）。
   - 用**同一个** `TOTP_HOT_WARNING_SECONDS` 常量（`TotpCodesScreen.kt` 注释已点名：
     "同一个『快过期了』概念在两处必须是同一个数"）；
   - `LaunchedEffect(remainingSeconds)` 内 `remaining in 1..TOTP_HOT_WARNING_SECONDS` 时震一次；
   - 需确认 Manifest 有 `<uses-permission android:name="android.permission.VIBRATE"/>`；
   - 默认值：**关**（保守，避免打扰）。
5. ⚠️ **不要抄 Bastion 的一处不一致**：它 `data/AppSettings.kt:513` 声明 `copyNextCodeWhenExpiring = true`，
   而**读取路径** `utils/SettingsManager.kt:115/:535` 是 `false` ⇒ 实际默认关。**Vaultix 只保留一处真源。**

### 验收

- [ ] 关掉开关后，临期复制拿到的是**当前码**（不是下一个）
- [ ] 开关状态跨重启保持
- [ ] （若做震动）≤5 秒每秒一次，>5 秒不震

---

## §3 设置页「关于 / 版本与更新」内容丰富

### 现状（已核）

`app/src/main/java/io/vaultix/vaultix/ui/settings/SettingsScreen.kt`
- `:123` 文件头写明该分区规划为「权限管理 · 版本 · 检查更新 · 源码与反馈 · 开源许可」；
- `:242-252` 实际只有 **版本（只显示 `BuildConfig.VERSION_NAME`）** + **检查更新**；
- ⚠️ `:245` 有一条**明确决策**：「**只显示 versionName，不拼 versionCode**」（2026-09-14 用户报告）。
  **本次用户要求"丰富" ⇒ 视为改回该决策，但必须在注释里写明"为什么改回"**（否则下一个人会照旧注释又改回去）。

### 改法（补哪些）

| 项 | 来源 | 备注 |
|---|---|---|
| versionName | `BuildConfig.VERSION_NAME` | 已有（debug 包自带短 sha，可定位代码） |
| 构建号 / versionCode | `PackageInfo.longVersionCode` | **本次恢复**（写清原因） |
| 更新渠道 | 正式版 / 预览版 | 比照 `UpdateChecker` 的渠道判定 |
| 源码地址 | `https://github.com/Chaniug/Vaultix` | 外链 |
| 反馈入口 | GitHub Issues | 外链 |
| 开源许可 | GPL-3.0 | 文案或内置全文页；Vaultix 本身就是 GPL |
| 更新日志 | Releases 页 | 复用 `UpdateChecker.RELEASES_PAGE_URL` |
| （可选）构建时间 / 短 SHA | 需新增 `buildConfigField` | `app/build.gradle.kts` 目前**无自定义 buildConfigField** |

### 验收

- [ ] 关于页能看到 versionName + 构建号 + 渠道 + 源码/反馈/许可/更新日志入口
- [ ] 各外链在真机可打开
- [ ] 文件头那条"刻意不显示 versionCode"的注释已同步改写（含原因）

---

## §4 debug 开发者模式：查看 / 导出日志

### 现状（已核）

- **Vaultix 没有任何文件日志**：只有 `app/src/main/java/io/vaultix/vaultix/autofill/AutofillLogger.kt`
  （`BuildConfig.DEBUG` 门禁、只写 logcat、不落盘）。
- **没有 FileProvider**：`app/src/main/res/xml/` 只有 `autofill_service.xml` / `credential_provider.xml`。

### 改法

1. **入口**：设置页新增「开发者」组，**仅 `BuildConfig.DEBUG` 可见**（release 连入口都不出现 ⇒ 不误导用户）。
2. **日志源 = logcat 快照**（关键取舍，见 §5）：
   `logcat -d -t N`（N≈1200）→ 按 **本进程 PID** 过滤 → 用 **tag 白名单**剔除系统噪音。
   参考 Bastion `DeveloperLogDebugHelper.collectLogs`（`reference/bastion/.../ui/screens/DeveloperSettingsScreen.kt:595-719`），
   **但不要**照搬它的"持久化文件日志"部分。
3. **查看 UI**：`LazyColumn` + 等宽字体 + 级别过滤（全部 / 错误 / 警告）+ 手动刷新。
4. **导出**：`cacheDir/dev_logs/dev_logs_yyyyMMdd_HHmmss.txt` 写全文
   → `FileProvider.getUriForFile(context, "${packageName}.fileprovider", file)`
   → `ACTION_SEND` + `EXTRA_STREAM` + `FLAG_GRANT_READ_URI_PERMISSION`（+ `EXTRA_TEXT` 降级）。
   ⇒ **需新增**（Vaultix 目前都没有）：
   - Manifest `<provider android:name="androidx.core.content.FileProvider"
     android:authorities="${applicationId}.fileprovider" android:exported="false"
     android:grantUriPermissions="true">` + `meta-data` 指向 `@xml/file_paths`；
   - `res/xml/file_paths.xml`：至少 `<cache-path name="dev_logs" path="dev_logs/"/>`。
5. **导出前统一脱敏**（写出口一道正则：密码 / 密钥 / token / 邮箱 / 手机号 →
   ⚠️ Bastion 的 `=== System Logcat ===` 段**不脱敏**，我们补上，因为我们的日志源就是 logcat）。

### 验收

- [ ] debug 包：能看到本进程日志、能按级别过滤、能导出并分享出去
- [ ] 导出文件里**不含**任何密钥/密码（拿真机造一条含密码的日志验证）
- [ ] release 包：设置页**看不到**开发者入口

---

## §5 日志取舍：release 怎么做到"不占空间"（**先定这条，§4 才好做**）

### ★ 核心判断

**占空间的是"往自己的存储写文件"，不是"写 logcat"。**
logcat 是**系统**的环形缓冲，由系统回收，不计入应用存储。
⇒ 因此"release 日志尽量少、不能占内存"的最优解不是"少写日志"，而是：

> ### 一律**不写文件**，只写 logcat。

| 构建 | 日志策略 | 磁盘占用 |
|---|---|---|
| **debug** | 现状（`AutofillLogger` 详细）+ §4 的开发者模式可按需导出 | 0（导出文件由用户主动清理） |
| **release** | 只保留**错误级/关键路径**，且**只写 logcat** | **0** |

### 明确**不做**的事（以及什么时候才该做）

- ❌ **不引入** Bastion 那套"持久化文件日志"（`filesDir/xxx_logs/*.log` + 1 MB 上限 + 轮转 + 环形内存）。
  它的代价是**永久占用磁盘**（Bastion 常驻 ≈ 3×1 MB + 500 条内存），而我们只需要"能排查"，
  不需要"release 用户在无 adb 的情况下回传日志"。
- ✅ 当且仅当出现"release 用户无法 adb、又必须拿到日志"的真实需求时，再按 Bastion 的**容量设计**补：
  内存环形 500 条（`AutofillLogger.kt:33`）、文件上限 1 MB（`:36`）、轮转留尾 4000 行
  （`SecurityDiagLogger.kt:23`）、有界队列丢弃最旧（`BoundedLogExecutorFactory.kt:9`）、
  例行日志 5 秒去重、异常日志每 tag 60 秒限 50 条。

### 验收

- [ ] release 包跑一天后，应用存储里**没有**新增日志目录（`run-as` 或设置里的存储统计核对）
- [ ] release 崩溃时 logcat 仍有足够线索（至少：异常栈 + 出错点 tag）

---

## 附：既有资产（先看，别重做）

- ★ **[`bastion-parity-assessment.md`](./bastion-parity-assessment.md) §3 缺口矩阵里已有本单 §4 那一行**：
  「**开发者** · 调试 / 日志 / 导出诊断 · Bastion ✅ · Vaultix ❌ 缺失 · **工作量：小**」
  ⇒ 本单 §4 与之口径一致，不要重复评估。
  ⚠️ 该文档 2026-09-21 已修一处过期结论（第 74 行原称"Vaultix 连 AutofillService 都没有"）。
- `.ai/issues/01-构建与环境.md` —— 构建/CI/环境类坑。
- `.ai/conventions/8.4`（观感纪律：先做减法的同源纪律）/ `8.6`（工程质量）/ `8.7`（环境）。
