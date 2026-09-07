# Compose BOM + material3 升级：API 迁移执行计划（2026-09）

> 编制日期：2026-09-03。上游版本基于 **maven-metadata 实测**（非记忆/推测）。
> 分支：`dev`。前置文档：[`dependency-upgrade-plan-2026-08.md`](./dependency-upgrade-plan-2026-08.md)
> （记录了 2026-08 首次尝试升级 → 70 处编译失败 → 回退的完整过程，**开工前必读**）。
>
> 本文面向「AI 接力」编写：目标是让**没有本轮上下文的执行者**能安全、可回滚地完成迁移。

---

## 0. 结论摘要（先读这里）

| 问题 | 结论 |
|---|---|
| **需不需要升级？** | **需要**，但不是紧急项。收益是长期可维护性与性能改进，不是修必须修的 bug。 |
| **现在就升吗？** | **建议暂缓 1~2 个月**，等 material3 `1.5.0-beta01/rc01`；`alpha27` 序号已很高，通常紧接着进 beta。 |
| **为什么不建议现在升？** | 现在升只能到 **alpha27（仍是 alpha）**，beta/rc 阶段可能再出现 API 变更 → **二次返工**。 |
| **现在能做什么？** | 做 **§3 的 29 处语义普查**（纯文档工作，**不改任何版本、零风险**），把不确定性前置消除。 |
| **拖着不动有风险吗？** | 有。当前 material3 停留在 `alpha16`（落后 11 个 alpha），alpha 线无补丁、API 不稳定，**建议 1~2 个月内完成**。 |

---

## 1. 现状与上游实测（2026-09-03）

### 1.1 版本现状

| 组件 | 当前 | 上游最新 | 差距 |
|---|---|---|---|
| `composeBom` | `2026.03.00` | **`2026.08.00`**（2026-08-12 发布） | 落后 5 个版本 |
| `material3Expressive` | `1.5.0-alpha16` | **`1.5.0-alpha27`** | 落后 11 个 alpha |
| material3 **stable** 线 | — | `1.4.0` | **1.5.0 尚未 stable** |
| Kotlin / KSP | `2.3.21` / `2.3.11` | Kotlin 2.4.0 已发布，但 **KSP 无 2.4.x** | ❌ 升级被阻塞 |

> 上游版本来源（实测）：
> `https://dl.google.com/dl/android/maven2/androidx/compose/material3/material3/maven-metadata.xml`
> `https://dl.google.com/dl/android/maven2/androidx/compose/compose-bom/maven-metadata.xml`
> KSP：`https://github.com/google/ksp/releases`（最新 `2.3.11`，无 2.4.x）

### 1.2 为什么这两者必须一起升（硬绑定）

来自 2026-08 文档的实测依赖链：

| 组件 | 依赖的 Compose runtime/foundation/ui |
|---|---|
| `compose-bom 2026.03.00`（当前） | `1.10.5` |
| `compose-bom 2026.08.00`（目标） | `1.12.0` |
| `material3 1.5.0-alpha16`（当前） | `1.11.0-beta02` |
| `material3 1.5.0-alpha27`（目标） | `1.12.0-beta01` |

**推论**：
1. 项目实际跑在 `1.11.0-beta02`（Gradle 冲突解决取高者），**不是** BOM 声称的 `1.10.5`。
2. 只升 BOM 而 material3 留在 alpha16 → 解析到 `1.12.0`，但 alpha16 的字节码是针对 `1.11.0-beta02` 编译的，
   跨 minor 属**二进制不兼容**，会 `NoSuchMethodError`。
3. 这正是 `bd8591b` 曾把 BOM 从 `2026.08.00` 静默回退到 `2026.03.00` 的真正原因（**非误操作**）。

### 1.3 上次失败的教训（2026-08-29）

- commit `a143f4f` 捆绑升级 → CI run `33239556904` **编译失败：70 处错误** → commit `2150a1e` 回退。
- 失败**不是** motion 回归，而是 **material3 alpha 线的破坏性 API 变更**：
  - `menuAnchor()` 无参重载被移除 → 必须显式传 `ExposedDropdownMenuAnchorType`
  - `ExposedDropdownMenu` 相关引用失效
- **关键纠正**：当时记录的「50 + 20」是**编译错误条数**；2026-09-03 实测调用点为
  `menuAnchor` **29 处**、`ExposedDropdownMenu` **29 处**、`ExposedDropdownMenuBox` **35 处**（共 93 处命中、16 个文件）。

---

## 2. 决策：现在升，还是等？

### 支持「现在升」的理由
- `runtime/foundation/ui` `1.10.5 → 1.12.0`：滚动、文本、懒列表的性能与 bug 修复
- material3 累积 11 个 alpha 的修复与 Expressive 组件完善
- 越晚迁移，技术债越大（业务代码越多）

### 支持「再等等」的理由（本文建议）
- **目标版本仍是 alpha**：升完还在预发布质量上，且 beta/rc 可能再改 API → 二次返工
- 项目当前**功能完整**，且 2026-09 刚完成一轮界面打磨（顶栏悬浮/透明、设置页、版本更新页），
  这些正是用 material3 做的，升级后**已验收的观感需全部重验**
- 迁移本身要 2~3 小时 + 多轮 CI（每轮 ~10 分钟）+ 16 个页面真机回归

### 建议路径

```
现在（2026-09）          等待期（1~2 个月）              迁移窗口
─────────────────────    ────────────────────────      ─────────────────────
做 §3 语义普查（零风险） → 观察 material3 1.5.0-beta01 → 按 §4 分 4 批迁移
不改任何版本号             / rc01 发布                    一次迁到准稳定版
```

**触发条件**：`maven-metadata.xml` 中出现 `1.5.0-beta01` 或更高 → 启动迁移。
**兜底条件**：若 2 个月内未进 beta，则直接迁 `alpha27`（此时至少拿到 11 个 alpha 的修复）。

---

## 3. 迁移范围：29 处 `menuAnchor` 精确清单

### 3.1 新 API 用法

```kotlin
// 旧（alpha16 及之前）
Modifier.menuAnchor()

// 新（alpha17+）
Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)  // 纯选择器，文本框只读
Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable)     // 可输入的下拉（如服务器地址）
```

### 3.2 判断规则（**必须逐处读上下文确认，禁止批量替换**）

| 判据 | 结论 |
|---|---|
| 配对的 TextField 有 `readOnly = true`，或点击只弹出选项、不接受输入 | `PrimaryNotEditable` |
| 用户可以在框里打字过滤/自定义值（如 URL、路径、自定义算法参数） | `PrimaryEditable` |
| 拿不准 | 按 `PrimaryNotEditable`，真机验证时重点试「能否输入」 |

**典型上下文特征**：`menuAnchor().fillMaxWidth()` 常配合 `OutlinedTextField(readOnly = true)`；
`.menuAnchor()` 单独成行的多为标准写法，需上下 20 行内找字段定义。

### 3.3 清单（2026-09-03 实测行号，执行时逐行确认并在最后一列打勾）

#### 🟢 P1 低风险（8 处，独立页面、非核心路径）

| # | 文件 | 行 | 初步判断 | 依据 | 已确认 |
|---|---|---|---|---|---|
| 1 | `BitwardenLoginScreen.kt` | 303 | `PrimaryEditable` | 服务器地址可自定义输入 | ☐ |
| 2 | `GeneratorSshKeySection.kt` | 111 | 待确认 | 生成器参数选择 | ☐ |
| 3 | `GeneratorSshKeySection.kt` | 158 | 待确认 | 生成器参数选择 | ☐ |
| 4 | `LocalKeePassOneDriveBrowser.kt` | 1007 | 待确认 | 云端文件浏览 | ☐ |
| 5 | `LocalKeePassScreen.kt` | 1553 | 待确认 | 本地库选择 | ☐ |
| 6 | `LocalKeePassWebDavBrowser.kt` | 1024 | 待确认 | WebDAV 浏览 | ☐ |
| 7 | `PasswordFieldCustomizationScreen.kt` | 699 | 待确认 | 字段定制 | ☐ |
| 8 | `SecurityQuestionsSetupScreen.kt` | 437 | 待确认 | 安全问题选择 | ☐ |

#### 🟡 P2 中风险（11 处，各编辑页）

| # | 文件 | 行 | 初步判断 | 依据 | 已确认 |
|---|---|---|---|---|---|
| 9 | `AddEditPasswordScreen.kt` | 2536 | 待确认 | — | ☐ |
| 10 | `AddEditPasswordScreen.kt` | 3448 | 待确认 | — | ☐ |
| 11 | `AddEditPasswordScreen.kt` | 3489 | 待确认 | — | ☐ |
| 12 | `AddEditPasswordScreen.kt` | 3532 | 待确认 | — | ☐ |
| 13 | `AddEditPasswordScreen.kt` | 4737 | 待确认 | — | ☐ |
| 14 | `AddEditSshKeyScreen.kt` | 731 | 待确认 | — | ☐ |
| 15 | `AddEditSshKeyScreen.kt` | 771 | 待确认 | — | ☐ |
| 16 | `AddEditBankCardScreen.kt` | 599 | 待确认 | `menuAnchor().fillMaxWidth()` | ☐ |
| 17 | `AddEditDocumentScreen.kt` | 584 | 待确认 | `menuAnchor().fillMaxWidth()` | ☐ |
| 18 | `AddEditTotpScreen.kt` | 966 | 待确认 | `menuAnchor().fillMaxWidth()` | ☐ |
| 19 | `AddEditWifiScreen.kt` | 650 | 待确认 | — | ☐ |

#### 🔴 P3 高风险（10 处，自动填充核心路径）

| # | 文件 | 行 | 初步判断 | 依据 | 已确认 |
|---|---|---|---|---|---|
| 20 | `AutofillPickerActivityV2.kt` | 4120 | 待确认 | 自动填充选择器 | ☐ |
| 21 | `AutofillPickerActivityV2.kt` | 4172 | 待确认 | 自动填充选择器 | ☐ |
| 22 | `AutofillPickerActivityV2.kt` | 4232 | 待确认 | 自动填充选择器 | ☐ |
| 23 | `AutofillPickerActivityV2.kt` | 4283 | 待确认 | 自动填充选择器 | ☐ |
| 24 | `PasswordEntryPickerBottomSheet.kt` | 261 | 待确认 | 条目选择（高频） | ☐ |
| 25 | `PasswordEntryPickerBottomSheet.kt` | 302 | 待确认 | 条目选择（高频） | ☐ |
| 26 | `PasswordEntryPickerBottomSheet.kt` | 345 | 待确认 | 条目选择（高频） | ☐ |
| 27 | `AutofillSettingsV2Screen.kt` | 365 | 待确认 | 自动填充设置 | ☐ |
| 28 | `AutofillSettingsV2Screen.kt` | 418 | 待确认 | 自动填充设置 | ☐ |
| 29 | `AutofillSettingsV2Screen.kt` | 479 | 待确认 | 自动填充设置 | ☐ |

> 文件根目录：`Bastion/app/src/main/java/com/bastion/app/ui/`
> 重新定位命令（行号会随代码变动而失效，执行前务必重跑）：
> ```powershell
> cd Bastion\app\src\main\java
> Get-ChildItem -Recurse -Filter *.kt | ForEach-Object {
>   $m = Select-String -Path $_.FullName -Pattern "menuAnchor"
>   if ($m) { foreach ($x in $m) { "{0}:{1}: {2}" -f $_.Name, $x.LineNumber, $x.Line.Trim() } }
> }
> ```

### 3.4 另一个待处理项：`ExposedDropdownMenu` 29 处引用失效

上次失败记录中「`ExposedDropdownMenu` 引用失效（20 处）」的**具体原因尚未记录**（2026-08 文档只记了条数）。
**P0 阶段必须先查明**：在临时分支升版本，抓首次编译错误，把错误归类后补进本文 §3.5，再开始批量迁移。

---

## 4. 分批执行计划

> **铁律**：每批一个 commit，每批单独验证，验证不通过**不进入下一批**。
> 版本号变更（P4）**最后做**，前 3 批是在旧版本上把代码改成「新旧 API 都能兼容或已就绪」的形态。

### P0 · 侦察（不建议跳过）
1. 从 `dev` 切分支 `chore/m3-api-migration`
2. 在 `libs.versions.toml` 里把 `composeBom` → `2026.08.00`、`material3Expressive` → `1.5.0-alpha27`（**或当时的 beta/rc**）
3. 推送触发 CI，收集**完整编译错误清单**
4. 把错误归类补进本文 §3.5（尤其是 `ExposedDropdownMenu` 那部分）
5. **回退版本改动**（只保留文档更新），进入 P1

### P1 · 低风险 8 处
- 改 `§3.3` 表格 1~8 项，逐个确认语义后替换
- 本地 `:app:compileDebugKotlin` 验证（注意：本机内存紧张，见 §7.3）
- CI 出包 → 真机验证这 8 个页面的下拉框

### P2 · 中风险 11 处
- 改 9~19 项，流程同 P1
- 重点验证：各编辑页的字段选择器（保存/回填是否正常）

### P3 · 高风险 10 处（自动填充）
- 改 20~29 项
- **必须真机验证**（模拟器测不出自动填充）：
  - 浏览器/App 触发自动填充 → 选择器展开、滚动定位、选中填充
  - 密码条目选择 BottomSheet
  - 自动填充设置页各项下拉

### P4 · 升版本 + 全量回归
1. `libs.versions.toml`：`composeBom` + `material3Expressive` **同时**改（禁止单边）
2. 更新 toml 顶部注释（记录新的锁定原因与验证结论）
3. 全量回归（见 §5）
4. 合入 `dev`，按仓库约定 fast-forward 同步到 `main`

---

## 5. 验收清单

### 编译 / 静态
- [ ] `:app:compileDebugKotlin` 通过
- [ ] CI（`.github/workflows/main.yml`）绿，含 lint 质量门
- [ ] 无新增 lint baseline 条目

### 真机功能（16 个涉及文件全覆盖）
- [ ] 每个下拉框：点击能展开、选项可滚动定位、选中后正确回填
- [ ] **可编辑型**下拉：能在输入框打字过滤（`PrimaryEditable` 处）
- [ ] **只读型**下拉：点击不弹键盘、只弹选项（`PrimaryNotEditable` 处）
- [ ] 编辑页：改动后保存，重新进入仍显示正确值
- [ ] 自动填充三条路径（密码选择、条目选择 BottomSheet、设置页）

### 回归项（历史 bug，升级后必须复验）
- [ ] **底部导航切 tab 图标闪烁**：当初锁 `alpha16` 就是为修它（`MotionScheme.standard()`，
      commit `b97b39e` / `e6047c3`），升级后必须确认不复发
- [ ] **预测性返回**（边缘侧滑）动画：项目用 `predictivePopEnterTransition/ExitTransition` 显式接管，
      升级 navigation 后曾出现「页面缩小」回归（commit `3a62510` / `36544c2`）
- [ ] 顶栏：滚动收起变透明、按钮组缩放、进度条收起（2026-09 刚做的效果，全用 material3 组件）
- [ ] 深色/浅色主题、大字体（用户设备 fontScale ≈ 1.2、状态栏 ~58dp）

---

## 6. 风险与回滚

| 风险 | 概率 | 应对 |
|---|---|---|
| 迁移后 API 又变（beta/rc 二次变更） | 中 | 按 §2 等 beta/rc 再动；若已迁 alpha27，二次变更量通常很小 |
| 自动填充路径行为异常 | 中 | P3 单独一批，真机重点验；出问题只回滚 P3 的 commit |
| 底部导航图标闪烁复发 | 中 | 回滚 material3 版本；或改用 `MotionScheme.standard()` 之外的方案（见 `b97b39e`） |
| 编译错误超预期 | 高 | P0 侦察阶段先摸清完整错误清单，不盲目批量改 |
| 本机编译卡死 | 中 | 见 §7.3，**优先用 CI 验证** |

**回滚方式**（按粒度从细到粗）：
1. 单批问题：`git revert <该批 commit>`
2. 版本问题：只回退 `libs.versions.toml` 两行（`composeBom` / `material3Expressive`），保留已迁移代码
3. 整体放弃：`git revert` 迁移区间所有 commit，分支删掉即可

> 参考：2026-08 的回退就是 commit `2150a1e`（回退 `a143f4f`），流程已验证可行。

---

## 7. 给接手执行者（AI / 人）的交接清单

### 7.1 开工前必读
1. 本文全文
2. [`dependency-upgrade-plan-2026-08.md`](./dependency-upgrade-plan-2026-08.md)（上次失败的完整记录）
3. `Bastion/gradle/libs.versions.toml` 顶部注释（每个锁定版本的原因都写在里面）

### 7.2 关键文件
| 文件 | 作用 |
|---|---|
| `Bastion/gradle/libs.versions.toml` | 版本目录，改版本只动这里 |
| `Bastion/gradle/wrapper/gradle-wrapper.properties` | Gradle 版本（AGP 9.3 要求 ≥ 9.5.0） |
| `.github/workflows/main.yml` | 质量门（只监听 `Bastion/**/*.kt` 等路径，**纯文档改动不触发构建**） |

### 7.3 环境注意事项
- **本机内存只有 4~6G 可给 Gradle**，跑 `compileDebugKotlin` 会起 3 个 java 进程占 ~4.4GB，机器会明显卡顿。
  - 优先用 **CI 验证**（云端不占本机）
  - 若必须本地编译，跑完立即清理：
    ```powershell
    .\gradlew.bat --stop
    Get-CimInstance Win32_Process -Filter "Name='java.exe'" | ForEach-Object { Stop-Process -Id $_.ProcessId -Force }
    ```
- **Kotlin LSP 的 lint 不可信**，必须真实编译验证。
- **推送后必须查 CI 状态**（曾出现本地 lint 干净但 CI 编译失败）：
  ```bash
  gh run list --branch dev --limit 3
  gh run view <run-id> --log-failed
  ```

### 7.4 仓库约定
- 只动 `dev` / `main`，`dev` 是默认分支与 CI 触发分支
- `dev → main` 用 fast-forward：`git checkout main && git merge --ff-only dev && git push origin main`
- 验证流程：推 `dev` → CI preview APK → Release 下载 → 真机验证
- 用户用**手势导航（边缘侧滑）**，预测性返回必须验证跟手性

### 7.5 用户设备参数（真机验证时按此校准）
- 分辨率 1256×2760、density 2.75、状态栏 **~58dp（很高）**、fontScale ≈ 1.2
- adb 无线调试：`C:\adb\adb.exe connect <IP>:<端口>`（端口每次会变）

---

## 附：进度记录（执行时填写）

| 批次 | 状态 | commit | CI run | 完成日期 | 备注 |
|---|---|---|---|---|---|
| P0 侦察 | ☐ 未开始 | | | | |
| P1 低风险 8 处 | ☐ 未开始 | | | | |
| P2 中风险 11 处 | ☐ 未开始 | | | | |
| P3 高风险 10 处 | ☐ 未开始 | | | | |
| P4 升版本 | ☐ 未开始 | | | | |
