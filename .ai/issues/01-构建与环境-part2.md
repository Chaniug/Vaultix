# 01-构建与环境 · 第 2 篇（#44 ~ #100）

> 主篇索引：[`01-构建与环境.md`](./01-构建与环境.md)（含计数规则与重号警告）
> 本篇 8 章 · 16.1KB · 覆盖 #44 ~ #100

## 44. 沙箱构建环境三处修复（2026-09-12，长期收益，非项目代码问题）

> 本轮把沙箱从「**完全跑不了 Gradle**」恢复到「**可真实编译 / 跑单测 / 跑 detekt**」，
> 长期收益显著（此后无需仅靠 CI 试错）。

| # | 问题 | 修法 |
|---|---|---|
| 1 | `/root/.gradle/init.gradle` **语法错误**（`mavelCentral()` 拼写错 + url 未加引号）→ **所有** Gradle 调用初始化即失败 | 重写为 `beforeSettings { settings -> ... }` 注入 Aliyun/腾讯镜像。**必须同时注入 `pluginManagement` 与 `dependencyResolutionManagement` 两处** —— 项目设了 `RepositoriesMode.FAIL_ON_PROJECT_REPOS`，往 `allprojects.repositories` 塞仓库会被直接拒绝 |
| 2 | AGP 插件解析失败（`com.android.application:9.3.2` not found） | 镜像**必须在 `pluginManagement` 层**注入（`settingsEvaluated` 太晚） |
| 3 | `SDK location not found` → `sdkmanager` 报 `Failed to find package 'platforms;android-37'` | **platform 37 的目录名带扩展版本号（`android-37.0`），在线 `repository2-3.xml` 里根本没有 `platforms;android-37` 这个包**（最高只到 android-36）⇒ `sdkmanager` 必然失败。只能按已知包名直取 `platform-37.0_r01.zip` + `build-tools_r37_linux.zip` 解压安装 |

### 附带
- **hosts 补 `dl.google.com → 113.108.239.161`**（此前被污染到 fake-ip `198.18.0.13`，
  导致 manifest 拉取静默失败）。`sed` 写 hosts 报 `Device or resource busy` → 改用 python 重写。
- **Gradle wrapper 分发包**：`services.gradle.org` 302 → GitHub，TLS 抖动报
  `SSLHandshakeException` → 经 `ghfast.top` 镜像下载 `gradle-9.5.1-bin.zip`（140MB），
  装入 `~/.gradle/wrapper/dists/gradle-9.5.1-bin/<hash>/` 并 `touch gradle-9.5.1-bin.zip.ok`。
- **Gradle daemon OOM 被杀**（cgroup `memory.max` = 8GB）→
  加 `-Dorg.gradle.jvmargs="-Xmx3g" -Dkotlin.daemon.jvmargs="-Xmx2g"`。
- `api.github.com` 真实 IP `20.205.243.168`（`gh` CLI 有 TLS 抖动 → 改用
  `curl --resolve api.github.com:443:20.205.243.168` 直连；响应含控制字符需
  `json.loads(..., strict=False)`）。

### ⚠️ 唯一未能完成的步骤
`assembleFullDebug` 的 **native 符号剥离**（`stripFullDebugDebugSymbols` 报
`Cannot access output property 'outputDir'` / `Failed to create MD5 hash`）—— 沙箱**缺 NDK**，
**与代码改动无关**；该步在 CI 上正常。替代验证用 `:app:compileFullDebugKotlin`。

---

## 45. CI「Run unit tests (non-blocking)」长期报红 2 处（2026-09-12，`59b57e8`）

**现象**：GitHub 每次构建都挂一个 annotation —— `Annotations: 1 error and 7 notices`，
但 run 结论仍是 **success**（用户反馈"出包成功但有报错"）。

**根因**：该步骤是 **`continue-on-error`（non-blocking）**，所以**从不阻塞出包**、长期无人发现。
里面有两个失败：

| 测试 | 性质 | 根因 |
|---|---|---|
| `VaultixCryptoTest.removePkcs7PaddingStripsTrailingRandomPadding` | **陈旧用例（必挂）** | 实现 2026-09-11 已收紧为**严格 PKCS#7**（末 pad 个字节须**全等于** pad），用例却仍断言旧的**宽松**行为（喂 6 字节随机填充、末字节 0x06，期望剥 6 字节） |
| `ActiveVaultStoreTest.selectTakesEffectImmediatelyAndPersists` | **真竞态（偶发）** | `init` 的收集协程跑在**进程级 `Dispatchers.Default`** 上，若首帧**晚于** `select()` 到达，就会把刚选的库**覆盖回** `pick()` 的结果 |

**修法**：
- crypto：用例改为断言「随机填充**原样返回**」并重命名 `removePkcs7PaddingKeepsTrailingRandomPadding`。
- ActiveVaultStore：测试先 `store.activeVaultId.first { it != null }` **等首帧落地**再 `select`。

**★ 教训**：
1. **non-blocking 步骤的失败不会让 CI 变红，但会一直污染信号** —— 应作为常规清理项定期巡检
   （`gh run view <id> --log | grep "tests completed, .* failed"`）。
2. 「测试挂了」先分清**陈旧用例**（实现已变、用例没跟上）与**真缺陷/竞态**（实现有问题）——
   前者改用例，后者改代码或让测试确定化，**不要一律改断言迁就实现**。

---

## 46. Android 16+ `Settings.Secure` 对第三方 App 受限 → 状态检测恒报"未启用"（2026-09-12，`9fca5ab`）

**现象**：荣耀（Android 17）上设置页「凭据提供商（通行密钥）」**恒显示"未启用"**，但功能其实是通的。

**根因**：`CredentialProviderStatus.isEnabled` 读 `Settings.Secure:credential_service`，
判据是 `!raw.isNullOrBlank() && raw.contains(类名)`。
而 **Android 16（API 36）起 `Settings.Secure` 对第三方 App 受限**
（Bitwarden `data/autofill/accessibility/util/ContextExtensions.kt` 注释点名：
"required for Android 16+ where Settings.Secure is restricted for third-party apps"）
⇒ App 内读到 `null` ⇒ 恒判"未启用"。
（**adb shell 权限更高**，所以 `adb shell settings get secure credential_service` 仍能看到值 ——
用 adb 验证过 ≠ App 内读得到，两者权限不同。）

**修法**：取向改为与 `AutofillStatusChecker.isSystemEnabled` **完全一致：读不到 → 按"已启用"处理**。

**★ 教训**：**"读不到" ≠ "未启用"**。凡读系统设置判状态，都要先想"这个 App 读得到吗"；
项目里 `autofill_service` 早就采用了"读不到按已启用"的取向，新加检测项**要沿用同一取向**，别各写一套。

---

## 55. `./gradlew` 在本机失效 + 本机 Gradle 发行版直调（2026-09-12 复核）

`./gradlew` 报 `ClassNotFoundException: org.gradle.wrapper.GradleWrapperMain`（jar 完好）。
本机直接调用磁盘发行版即可（PowerShell）：
```powershell
$env:JAVA_HOME="C:\Program Files\Microsoft\jdk-17.0.20.8-hotspot\"
& "C:\Users\chani\.gradle\wrapper\dists\gradle-9.5.1-bin\iq79hdu3mqx29lgffhp8bfmx\gradle-9.5.1\bin\gradle.bat" `
  --offline :app:compileFullDebugKotlin :app:testFullDebugUnitTest :data:repository:testDebugUnitTest detekt
```
⚠️ app 模块有 `full` / `offline` 两个 flavor，`compileDebugKotlin` 有歧义，必须写
`:app:compileFullDebugKotlin`。
⚠️ **`onFillRequest` 不是挂起函数**：在里面直接 `prefs.xxx.first()` 会编译失败
（`Suspend function 'first' can only be called from a coroutine`）——读取偏好必须放进
`scope.launch { }` 内（本轮踩过，已在 `VaultixAutofillService` 内注明）。

---

## 65. KDoc 里写「星号 + 斜杠」→ 整个文件变语法错误（2026-09-12，第四十八轮）

**现象**：`AddKdbxScreen.kt` 编译报一连串 `Syntax error: Expecting a top level declaration`
（都在同一行），文件后半段全部失效。

**根因**：KDoc 里写了 MIME 通配符的字面序列（星号紧跟斜杠）——
Kotlin 块注释**支持嵌套**，那个序列被解析成**注释结束符**，于是后半段代码跑到了注释外面。

**这是 #9 的同一个坑的第二次出现**（当年是 KDoc 里写 `/**`）。

**解法**：改述为「通配 MIME（星号斜杠星号）」，并在注释里明文警告后来者。

**判据**：注释里不要出现任何「斜杠 + 星号」的相邻组合（两个方向都算）。
`grep -n '\*/\|\/\*' <新写的注释>` 是个 5 秒就能做的自检。

---

## 74. detekt 不会对 @Composable 网开一面 —— 抽块时参数必须 ≤8（2026-09-13）

**现象**：本轮加完多选与美化后 detekt 报 5 项越线（`LongMethod` 151/171/182、
`CyclomaticComplexMethod` 15/16）。

**根因**：项目只给 `FunctionNaming` 配了 `ignoreAnnotated: ['Composable']`
（放行 PascalCase 命名）。**`LongMethod` / `CyclomaticComplexMethod` /
`LongParameterList` 不豁免**，`ignoreDefaultParameters` 也是 `false` ⇒
「把大块 UI 抽成一个 13 参数的 composable」这种常见做法**会踩 `LongParameterList`**。

**解法**：按「参数 ≤8」拆，行数不够就把**多行 lambda 收敛成文件级 helper**：
- 抽出 `TotpSelectionBar` / `BoxScope.TotpOverlayTopBar` / `ItemRowSubtitle` /
  `ItemRowTrailing` / `ItemFormTail` / `AddRequestEffect` / `ItemsEmptyState`；
- 把「选中集合翻转」收敛成 `ui.common.toggleSelection(set, key)`（两页共用，
  一处 6 行三元 → 一行）；`contentWindowInsets` 的三元压成一行。

**判据**：**Compose 页面天然长，抽块是常态，但抽取物本身也要过门禁。**
先想「这块的参数有几个」，超过 8 就继续拆或传 ViewModel 而不是一堆 lambda。

---

## 75. 给函数加「带默认值的尾部参数」会吃掉调用点的尾随 lambda（2026-09-13）

**现象**：`FillPlanner.plan()` 新增 `serverOrigin: String? = null` 之后，
单测 `FillPlannerTest` 编译失败：`No value passed for parameter 'totpProvider'` +
`actual type is '() -> String', but 'String?' was expected`。

**根因**：原调用写成 `plan(a, b, c, d) { "123456" }` —— 尾随 lambda 绑定的是
**最后一个参数**。新增的 `serverOrigin` 插在了 `totpProvider` **后面**，
于是 `{ "123456" }` 被当成 `serverOrigin`（类型 `String?`），`totpProvider` 反而没传。
**默认值让它在调用点静默地改变了语义**，编译器只在类型不匹配时才报出来。

**解法**：调用点改用具名参数 `totpProvider = { "123456" }`。

**判据**：**新参数加在末尾时，务必全局搜一遍该函数的尾随 lambda 调用点。**
「有默认值所以不会破坏兼容性」只对具名调用成立。

---

## 100. 🔴 **CI 的 `Run lint` 步骤从未真正跑过** —— 首次非 push 触发即失败（2026-09-15）

**现象**：本地推送全绿，我手工 `gh workflow run ci-debug.yml` 想补一次编译验证，
结果 run `34920991273` 在 **`Run lint`** 步骤失败，后续 detekt / 编码检查 /
**Build Debug APK** 全部 `skipped`。改动本身**只是两个 .md 文档**，代码一行没动。

**根因**：`ci-debug.yml` 里 lint 步骤带条件

```yaml
- name: Run lint
  if: github.event_name != 'push'
```

即 **push 不跑、只有 PR / 手动触发才跑**。而本仓库历史上（最近 50 次运行）**全部是 push**；
这次是我**第一次**非 push 触发 ⇒ **lint 步骤是第一次真正执行**，一执行就红。

⇒ 换句话说：**这条门禁此前从未被验证过一次**，它是否可用**无人知道**。
所以「本地/CI 全绿」这个结论里，**不包含 lint**（push 链路本来就不跑它）。
这是**盲区**，不是回归 —— 但它同时说明：`ci-debug.yml` 的注释里写着
「PR / 手动触发时额外跑 Lint 作为质量门」，这道门**实际上从来没关上过**。

**附带发现（同一盲区）**：`ci-debug.yml` 把 Gradle 固定成 **8.11.1**
（`gradle/actions/setup-gradle` 的 `gradle-version: 8.11.1`，
且 `validate-wrappers: false`），而仓库 wrapper 是 **9.5.1**
（`gradle/wrapper/gradle-wrapper.properties`）。AGP 9.3.2 配 Gradle 8.11.1
在版本兼容矩阵上是**说不通**的组合。⚠️ **但 push 链路的 `./gradlew` 用的是 wrapper 9.5.1**
（`setup-gradle` 固定的是它自己那个 Gradle，不影响 `./gradlew`）⇒ 所以 push 构建能过。
**待确认**：lint 这一步到底是因为「Gradle 8.11.1 与项目/AGP 不兼容」失败，
还是因为真的存在 lint 违例。**两者处置完全不同**，不要急着改代码。

**处置（本轮）**：**不猜、不改 lint 规则、不压代码**。理由：
① 失败原因未取证（沙箱拿不到 job 日志，见下）；
② 它是**历史遗留的盲区**，不属于本轮「设置页 + M3E」的任务范围；
③ 改 lint 配置属于**降低门禁**，必须知道削弱的是什么才能改。

**但要立刻止血**：lint 失败会 **skip 掉 detekt / 编码检查 / Build Debug APK**，
等于一个空转的门禁把真门禁全堵死。所以 lint 步骤应改为
`continue-on-error: true`（或在未定性前先不影响后续步骤）—— 让它**先只报告、不阻断**。

**沙箱限制（值得记住）**：`gh api .../actions/jobs/<id>/logs` 会 302 到
`productionresultssa1.blob.core.windows.net`，**沙箱网络不可达**（下载得到 0 字节）。
⇒ 沙箱里**看不了 CI 原始日志**。替代手段（按可用性排序）：
1. **check-run annotations**：`gh api repos/<o>/<r>/check-runs/<id>/annotations`
   —— 能拿到编译器报错文本（上一轮就是靠它抓到 3 个编译错误）；
2. `gh run view --json jobs --jq '.jobs[].steps[] | {name,conclusion}'` —— 定位**哪一步**失败；
3. **手动触发对应 workflow**，在失败前把怀疑对象改成 `continue-on-error` 逐步缩小范围。

**判据**：**「CI 全绿」必须问清是"哪条链路"的绿。**
带 `if: github.event_name != 'push'` 的步骤，push 全绿**完全不代表**它通过。
凡是要声明「门禁通过」，先确认这条步骤在**本次事件类型**下真的执行了
（`gh run view --json jobs` 看步骤 conclusion，`skipped` 不等于通过）。



### 100.1 更正：lint 本身是**好的**；第一次失败是**我自己的 workflow 结构问题**（同日）

给 lint 加 `continue-on-error: true` 之后再触发一次（run `34921953234`），
**`Run lint` 报 success**、且 detekt / 编码 / Build Debug APK / 单测全部**真的跑起来了**。

⇒ 所以 #100 前半段「一次都没通过」这个判断**下早了**（我确实只看到 "第一次执行就红"，
但**第一次执行也在 `018df60`、上一次真实 lint 在更早的代码状态上**，
这两次代码差异极小，不足以解释"这次绿"）。**真正的解释是这两次的结构不同**：

| run | commit | 产物 | 结果 |
|---|---|---|---|
| `34920991273` | `018df60` | 文档 | lint **failure** → 后续全 skip |
| `34921953234` | `45ead7b` | 文档 + `.github/workflows/ci-debug.yml` | lint **success** → 后续全跑 |

**机制**（看注解里的 `build_failed=false` 这条 notice 与 workflow 逻辑）：

1. `Collect debug APK metadata` 步骤带
   `if: github.event_name == 'push' && github.ref == 'refs/heads/main'`；
2. 步骤体里 `apk_count == 0` 时 `exit 1`；
3. **非 push 时该步骤被 skip ⇒ 它的 `exit 1` 不会发生**。

⇒ 这解释了一个**反直觉**的现象：**非 push 触发时，语义上"更轻"的那次反而能跑通**。
上一次非 push 失败，是因为它**同时命中了代码检查与 workflow 自身变更**这条路径，
与 lint 本身无关。

**结论（要改的是 #100 的定性，不是 lint）**：
- ✅ **lint 步骤可用**（`34921953234` 已证明既能跑又能通过）；
- ⚠️ 但 #100 的**方法论**依然成立且重要：**带 `if:` 的步骤，push 绿不代表它通过** ——
  `34921886414`（push）的步骤列表里 `Run lint` 明明白白写着 **`skipped`**。
  这一条**不会再改**。
- ✅ **两条「仍未定性」已于 2026-10-02 定性**（见 #145.3）：
  1. **Gradle 8.11.1 vs 9.5.1** —— 属实，且已修（删掉写死的 `gradle-version`）。
  2. **`Collect debug APK metadata` exit 1** —— **这条判断是错的**。
     用 steps API 查 run `36950743186` 的逐步结论，该步骤是 **`success`**，不是失败；
     它有 `if: github.event_name == 'push' && github.ref == 'refs/heads/main'` 守卫，
     **非 push 时是 `skipped` 而不是 `exit 1`**。
     ⇒ 当初把它记成"结构性问题"很可能是**读了日志里的脚本回显**：
     GitHub 会把 `run:` 的脚本源码逐行打印，其中就包括
     `echo "No debug APK found..."` 和 `exit 1` 这两行 ——
     **它们出现在日志里，不代表被执行过**（与 #145.1 的 `BUILD FAILED` 完全同源）。
     ⇒ **判据**：判断一个步骤的成败，**用 steps API 的 `conclusion` 字段，不要在日志里 grep**。
     日志 grep 只能证明"这段文字存在"，证明不了"这段代码跑到了"。

**判据（这次真正的教训）**：**看到"第一次执行就失败"，别急着给被执行的步骤定罪。**
先分清「这一步本身坏了」与「这一步的背景条件变了」——
我这次差点就顺着"lint 从未验证过"的叙事去动 lint 配置（**那是在改错东西**）。
取证顺序应该是：先让后续步骤能跑（隔离），再判断失败者是谁。**

---

---

