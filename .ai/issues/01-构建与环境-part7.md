# 01-构建与环境 · 第 7 篇（#145）

> 主篇索引：[`01-构建与环境.md`](./01-构建与环境.md)（含计数规则与重号警告）
> 本篇 1 章 · 22.1KB · 覆盖 #145

## 145. 🔴 CI 的单测步是 `continue-on-error: true` —— **测试失败也不会变红**（2026-10-01）

**⚠️ 这一条会让人"以为全绿"**：`.github/workflows/ci-debug.yml` 里
`Run unit tests (non-blocking)` 标了 `continue-on-error: true`。

- ⇒ job 的 conclusion 是 `success`，**只代表"这条命令跑完了"**，不代表测试通过。
- 实测（run 36765603778）：job **`success`**，日志里却是
  `BUILD FAILED in 41s` + `:data:repository:compileDebugUnitTestKotlin FAILED`
  （新写的测试有 4 处编译错误）。
- 沙箱**看不到**这件事：只看 `gh run list` / `gh run view` 都是绿的。

**纪律**：
1. **推完之后必须下载 job 日志逐行看**，不能只看 conclusion：
   ```
   gh api repos/<owner>/<repo>/actions/jobs/<job_id>/logs > joblog.txt
   grep -E "^e: |FAILED|BUILD (SUCCESSFUL|FAILED)|tests completed" joblog.txt
   ```
   ⚠️ 日志存在 `productionresultssa2.blob.core.windows.net`，沙箱里同样被劫持 ⇒
   要一并把它的真实 IP 写进 hosts（DoH 查 `blob.bl5prdstrz24a.store.core.windows.net` 的 A 记录）。
   `gh run view --log` 与 `gh run download`（artifact）走的也是同一个域名。
2. **一个新写的测试"通过"了 = 它至少被编译过**。`:compileDebugUnitTestKotlin` 挂了的话，
   那些用例**一条都没跑**，却在 CI 上显示 success —— 这是"假绿"最隐蔽的一种形式。
3. 本仓库单测**按模块显式登记**（`ci-debug.yml` 里那串 `:<module>:testDebugUnitTest`）。
   ⚠️ 注释自己写着：**新增带单测的模块必须同时加进那串，否则测试等于从未执行**
   （有先例：`data:kdbx` 曾漏登记，用例从未真正跑过）。本次 `data:repository` 已在串里。

**⚠️ 仓库当前挂着一个既有失败（与本次改动无关，未修）**：
`AutoRestoreTriggerTest > 档位离开Never_删信封且不恢复` —— `AssertionError at :188`。
记录在案，别把它算到自己的账上；也正因为它，**"单测步骤红不红"这个信号已经失效了**
⇒ 更得逐行看日志，并且要能分辨"既有失败"与"我引入的失败"。

**★ 2026-10-01 追加实测（run 36778401768）—— 这条的"反向"也要注意**：

- 本轮 job 卡片是 **`success`**，而日志里 `:app:testFullDebugUnitTest` **确实 FAILED**
  （`307 tests completed, 1 failed`，唯一那例正是上面那个 flaky）⇒
  **"卡片绿但里面有红"** 又出现了一次，与 #145 开头说的完全一致。
- ⚠️ 但**反过来也成立、且更容易骗人**：`continue-on-error` 让**失败也会被吞**，
  于是"卡片红"时**未必是 test 步的红**（可能是 detekt / 编码检查 / Build APK 真红）。
  本轮卡片红是 test 步、而 detekt 与 Build 都是 ✅ ⇒ **不能从卡片颜色反推是哪一步挂的**。
- ⇒ 唯一可靠的动作永远是：**定位到具体的 job → 拉日志 → 分别查四件事**
  ```
  grep -cE "(^|Z )e: " joblog.txt                  # ⚠️ 见下面 4，必须带 (^|Z )
  grep -E "BUILD (SUCCESSFUL|FAILED)" joblog.txt # 三个 BUILD 各自的结果
  grep -E "tests completed|> Task :.*UnitTest" joblog.txt   # 哪些模块的测试真跑了
  grep -E "FAILED" joblog.txt                    # 逐条分辨"既有 flaky / 我引入的"
  ```
  只看 `tests completed, N failed` 会漏掉"**某个模块压根没跑**"（见下面第 3 条）。
- ⚠️ 本例还有一个值得记的细节：日志里 `w: …KdbxSyncOrchestratorTest.kt:167:17
  Expression is unused` 是一条**编译器警告**（`coAnswers` 块用裸 `Unit` 收尾），
  detekt 查不出、编译也过。**警告要读** —— 它最常见的内情是"这段代码没起到你以为的作用"。

### 145.1 🔴 **`grep -c '^e: '` 会假 0** —— 日志每行带时间戳前缀（2026-10-02 实测）

- **现象**：CI run `36949293837` 的 job **卡片是绿的**，拉日志后按上面那条命令查
  `e:` 得到 **0** ⇒ "能编译"的错觉。**实际有 6 条 `Unresolved reference 'parse'`**，
  `:core:common:compileDebugUnitTestKotlin FAILED`（本轮新增的 13 条断言**一条都没跑**）。
- **根因**：GitHub job 日志每行都是 `2026-10-02T01:09:37.5188470Z e: file:///…`，
  **`e:` 不在行首** ⇒ `^e:` 一条都配不上。修正：`grep -cE "(^|Z )e: "`。
  ⚠️ 这又是"检查本身有一个假的通过路径"：它不报错，只安静地返回 0。
- **更隐蔽的第二层**：同一轮里 **其余四个模块（bitwarden / repository / kdbx / app）的
  单测照常跑完了**，日志里它们的 `testDebugUnitTest` 都出现且无 FAILED。
  ⇒ **"别的都正常"会让人误判"挂的那个不重要"** —— 与 #154「有保护的那条路径看起来正常，
  会掩盖紧邻的真崩溃」是同一个形状。
- **⇒ 改成的判据**：`e:` 计数为 0 时，**要确认这个 0 是"真的没有"而不是"没匹配上"**
  —— 用一条已知存在的模式**反向校准**（本例：先 `grep -c Z` 确认行数不为零；
  或直接用「grep 到的行数」与文件总行数比对）。凡是"计数类检查"都适用这条。
  ```
  wc -l joblog.txt; grep -cE "(^|Z )e: " joblog.txt   # 两者都非 0，"e: = 0" 才可信
  ```

### 145.2 🟡 **CI 有 `paths` 白名单 ⇒ 纯文档 / 只改探针的推送压根不触发 CI**（2026-10-02 实测）

- **现象**：推送 `81abaa2`（纯 docs）与 `4d9bfc5`（docs + 2 个 `.py` 探针）后，
  `actions/runs` 列表里**最新的 run 仍是上一轮的 `36950743186`** —— **没有新 run、没有卡片**。
- **根因**：`.github/workflows/ci-debug.yml` 的 `on.push.paths` 是**白名单**，
  只列了 `.kt / .java / .xml / .json / .gradle / .gradle.kts / .kts / .toml / .sh /
  gradle/** / .github/scripts/** / .github/workflows/ci-debug.yml / config/**`。
  **`.md` 与 `.py` 都不在列** ⇒ 文档和探针改动**永远不触发 CI**。
- **⚠️ 与 145.1 是两种不同的"没有证据"，别混为一谈**：
  | | 145.1（卡片撒谎） | 145.2（没有卡片） |
  |---|---|---|
  | 有无 run | **有**，结论是假的 | **根本没有 run** |
  | "CI 没报错" | 不可信（日志里有错） | 也不可信（压根没测） |
  ⇒ 两者都指向同一句判据：**"CI 没报错"四个字本身不构成证据**。
- **实际影响面**：`.ai/tools/check_*.py` 六个探针**只靠本地跑**，CI 不会替你验。
  改了探针后说"CI 是绿的"，等价于什么都没说 —— 必须本地 `python3` 实跑并贴输出。
- **为什么值得记**：workflow 注释里已经写了"依赖版本目录与构建脚本改动同样会影响构建，
  必须纳入触发范围，否则修改它们后 CI 不运行，形成**「改了却验证不到」的盲区**"
  ⇒ 说明作者有过这个意识，但**遗漏了 `.py` 探针**。这是"白名单"这类机制的通病：
  **新增一类文件时，没人会想到去更新触发列表**（与 #158「KDoc 承诺的兜底入口零调用点」
  是同一个形状：写的时候是对的，之后周围的世界变了）。
- **⇒ 判据**：提交后如果"CI 没动静"，**先确认是没触发还是没跑完** ——
  查 `actions/runs?per_page=3` 里最新 run 的 `head_sha` 是否等于你的提交。
  不等 ⇒ 本次压根没触发，别写"CI 通过"。

### 145.3 🟡 **CI 里同时存在两个 Gradle**：setup-gradle 装 8.11.1、`./gradlew` 跑 9.5.1（2026-10-02 实证，已修）

- **现象**：`ci-debug.yml` 的 `gradle/actions/setup-gradle@v6` 写死 `gradle-version: 8.11.1`，
  而 `gradle-wrapper.properties` 是 `gradle-9.5.1-bin.zip`。
- **实证（日志里两个路径同时出现）**：
  ```
  Provision Gradle 8.11.1  → /home/runner/work/_temp/.gradle-actions/gradle-installations/…
  Stopping Gradle daemons  → /home/runner/.gradle/wrapper/dists/gradle-9.5.1-bin/…
  ```
  ⇒ **实际构建跑的是 wrapper 的 9.5.1**（本 workflow 所有构建命令都用 `./gradlew`），
  8.11.1 只是被 provision 了、**没被用于构建**。
- **三个后果**：
  1. 8.11.1 **白下载白解压**（每次约 10s + 一条缓存条目）；
  2. setup-gradle 的缓存按 Gradle 版本分区 ⇒ 日志里 `Cache hit for: gradle-8.11.1`
     **看着像命中了，其实命中的是个没人用的版本**；
  3. **最危险**：哪天有人把 `./gradlew` 改成 `gradle`，构建会**静默从 9.5.1 掉到 8.11.1**
     —— 一次无声的版本分叉，且只有"突然编不过了"才会被发现。
- **修法**：**删掉 `gradle-version`**。`setup-gradle` 默认就是「用仓库 wrapper 声明的版本」，
  不指定即正确，且**版本只有一个来源**（wrapper properties），天然不会分叉。
  ⚠️ 顺带修了兜底步骤里写死的 `gradle wrapper --gradle-version 8.11.1`
  （jar 丢了时会生成 8.11.1 而 properties 仍写 9.5.1 ⇒ 分叉），
  改为**从 properties 读取**，解析不出就 `exit 1` 拒绝猜测。
- **⇒ 可迁移的判据**（与 #125.1 同一个形状）：**凡是"版本号"这类约束，只允许有一个来源。**
  看到同一事实被抄成两份（这里 = CI 配置一份 + wrapper 一份），
  不要去"同步它们"，要**删掉其中一份**。
- **踩到的小坑**：从 `distributionUrl` 解析版本时，sed **不能用 `|` 当分隔符** ——
  正则里的 `(bin|all)` 会让 sed 认为 `s` 命令提前结束并报
  `unknown option to 's'`。改用 `#`。（已做正反验证：正常解析出 9.5.1 / 畸形时拒绝猜测）

### 145.4 🔴 **连续两次：新写的 Kotlin 编译不过，而 job 卡片是绿的**（2026-09-30 / 10-03 实录）

同一个坑在两周内踩了两次，**两次都是「本地无 SDK + 卡片绿」**：

| # | 写错了什么 | 卡片 | 日志里 |
|---|---|---|---|
| 1 | `TotpGenerator.parse(...)` —— 该入口不存在，实际是 `OtpUriParser.parse(...)` | 🟢 **success** | `e: …Unresolved reference 'parse'` ×6 · `BUILD FAILED in 51s` |
| 2 | `every { app.registerActivityLifecycleCallbacks(captor) }` —— slot 少包一层 `capture()` | 🟢 **success** | `e: …Argument type mismatch: actual type is 'CapturingSlot<…>'` · `:app:compileFullDebugUnitTestKotlin` 失败 |

- **两次的共同点**：**都是"我以为能编译"** —— 括号配平、类型名看着对、
  甚至还做了结构自查（`{}` 18/18、`()` 60/60），但**编译器不接受**。
  静态自查能抓语法，抓不到**类型不匹配**与**名字不存在**。
- ⇒ **判据（本条唯一要记住的一句）**：
  **沙箱里没有 Android SDK 时，新写的 Kotlin 在自己拉过 CI 日志、数过 `e:` 之前一律不算数。**
  **job 卡片变绿不等于编译过了** —— 单测步是 `continue-on-error: true`，
  它把"编译失败"也一并吞成绿。
- **配套动作**：写完 Kotlin 先跑 **`.ai/tools/typecheck_kotlin.sh`**（见 #145.5，
  本地真编译，约 20 秒），**再**提交。
- **顺带一条 mockk 语法**：`slot<T>()` 在 `every {}` 里**必须用 `capture(slot)` 包裹**，
  直接传 slot 会报 `Argument type mismatch: actual type is 'CapturingSlot<…>'`。
  -⚠️ **且不要写 `import io.mockk.capture`** —— `capture` 是 `MockKMatcherScope` 的成员，
  `every {}` 的 lambda 自带该 receiver，多写那行import 反而得到
  `Unresolved reference 'capture'`（2026-10-03 为此**第三次**栽在同一个类上，
  两次 CI 才发现）。既有用法见 `ItemRepositoryImplTest:151`，其 mockk import 只有
  `coEvery`/`coVerify`/`every`/`mockk`/`slot`。

### 145.5 🟢 **本地真编译工具`typecheck_kotlin.sh`** —— 把"赔一轮 CI"降成"20秒"（2026-10-03新增，已用）

- **它补的是 #145.4 的哪个洞**：#145.4 的代价是"每写错一个字符赔一轮 CI
  （提交 + 等 + 拉日志 + 数 `e:`）"。本仓库沙箱**没有 Android SDK**，
  但**类型检查这件事不需要 SDK** —— 它只需要被引用类的**签名**。
  ⇒ 借 `org.robolectric:android-all`（含真实字节码与 `onActivityPreCreated` 等
  API 29+ 回调）提供 `android.*`，从 Maven 拉mockk / coroutines / junit，
  直接调Gradle 缓存里的 `kotlin-compiler-embeddable`。
- **用法**（2026-10-04 扩成两种模式，见 #145.7）：
  - `bash .ai/tools/typecheck_kotlin.sh <文件.kt> [...]` —— 单文件/单测试，
    被测代码**自动按包索引闭包带齐**，不需要手动列；
  - `bash .ai/tools/typecheck_kotlin.sh --all` —— **批量巡检整个仓库**
    （按包分组，逐组编译 + 真跑，最后汇总）。
- **覆盖与不覆盖**：

  | 能查 | 不能查 |
  |---|---|
  | 类型不匹配、未解析引用、`import` 写错 | Android 资源（`R`）、`BuildConfig`、Compose 插件 |
  | 泛型推断、lambda 签名、**mockk 语法** | detekt 规则、编码检查 |
  | 项目自身源码的类型错误 | KSP/Hilt **代码生成**（`Hilt_*` / `*_Factory` 不存在） |
  | **单测的运行期行为**（竞态 / flaky / 虚拟时间陷阱） | **依赖 Android SDK 空注解的空安全**（见下） |

- ⚠️ **已知盲区（实测确认）**：`android-all.jar` 字节码**没有 `@NonNull`** ⇒
  `android.*` 参数被 Kotlin 当**平台类型** ⇒把 `onActivitySaveInstanceState(a, null)`
  改成null，本地检查**依然报绿**（已实测）。凡"因空安全而不能这么写"的问题**只能靠 CI 验**。
- ⚠️ **假绿闸门（工具自身也踩过，已修）**：拿不到主源码时 `callbacks` 是未解析符号，
  于是对它的调用**参数个数与类型都不检查** —— 实测 `onActivityStarted(a, null)`
  错传两个参数都不报。**"检查通过"很可能只是因为根本没检查到。**
  ⇒ 脚本对"被测类未解析 / 不在源码树内"**硬失败**，不报✅。
  这正是本仓库反复吃亏的那类"静默失效"，探针自身必须免疫。
- **它自己也做过变异测试**（否则"能红的检查"和"永远绿的检查"长得一模一样）：
  4 个已知坏变体全部被抓、报错与 CI **逐字一致**——
  | 变体 | 实际结果 |
  |---|---|
  | `registerActivityLifecycleCallbacks(captor)`（漏 `capture()`） | `argument type mismatch: actual type is 'CapturingSlot<…>'` |
  | 多写 `import io.mockk.capture` | `unresolved reference 'capture'` |
  | `verify(exactlyCount = 3)` | `no parameter with name 'exactlyCount' found` |
  | 回退到 `runTest`（**运行期**坏，编译完全正常） | 连跑 5 次有 3 次红 |
  | 被测类拿不到（放在源码树外） | 脚本硬失败，不报 ✅（防"假绿"） |
  好版本（三个编译错误已修 + `runBlocking`）：20 个 class、6 条用例连跑 8 次全绿。
- ⇒ **它不能替代 CI**，但把 #145.4 那种"提交后才知道写错"的反馈从**一轮 CI**
  压到 **20 秒**。#145.4 的判据依然成立（本地绿 ≠ CI 绿），只是不再需要为每个
  字符错误都付一次 CI。
- 🔴 **本条曾写过一句错账，此处订正**（2026-10-04）：原文写
  「实测 dagger 2.60.1 在 Central **还不存在**（最高 2.56.2）」——
  **错在用 `curl -I`（HEAD）探测**。Central 对 HEAD 请求返回 404，
  用 GET 复核后 `com/google/dagger/dagger/2.60.1/dagger-2.60.1.jar` **确实存在**。
  ⇒ 教训与 #145.1 同源：**探测工具给出的"否定结论"要先复核**，
  否则会被写成"事实"再传播到下一轮决策里。已同步修正脚本注释。

### 145.6 🔴 **`runTest` 的虚拟时间会把「等真实线程」变成「立刻超时」**（2026-10-03 CI 实证，**已修**）

**这是 #145.5 在 CI 上抓到的第一个运行期缺陷，也是它最值钱的一次收获。**

- **现象**：CI run `37132804360`（head `80d0844`）—— **编译 0 错误**
  （`e:` 0 · `unresolved reference` 0 · `Argument type mismatch` 0 · 全部 step `success`），
  但**6 条用例里 1 条红**在 `java.lang.IllegalStateException at ScreenSecurityGuardTest`。
- **根因**：`ScreenSecurityGuard` 的订阅跑在**进程级 `Dispatchers.Default`**
  （真实线程），而测试用 `runTest` —— 它的调度器是**虚拟时间**：
  `withTimeout(5_000)` 会**直接跳到 5 秒**并立即超时，**根本不等到那个线程**。
  ⇒ 那个超时**不是保护，是随机抛异常**。
- ⚠️ **它表现为"随机"而非稳定红**（最容易误判的地方）：
  | 时机 | 结果 |
  |---|---|
  | 信号已就绪（不需要等） | 🟢 **绿** |
  | 信号还没到（要等） | 🔴 **红**（虚拟时间跳满） |
  本地连跑 5 次：前 2 绿、后 3 红；CI 上恰好那条"要等**第二个**信号"的用例红。
  ⇒ **极易被当成"偶发 flaky，算了"** —— 而它 100% 是真 bug，只是被随机化了。
- **修法**：本文件所有等待都在真实线程上 ⇒ `runTest` 的虚拟调度器**毫无价值**，
  换成 **`runBlocking`**（超时恢复为真正的 5 秒）。已连跑 8 次全绿。
- **可迁移的判据**（比"记住这个 API"有用）：
  1. **`runTest` 只对"被测协程也在测试调度器里"的代码有意义**。一旦被测代码用了
     **自己的** `CoroutineScope(Dispatchers.Default)`（正是本仓库 #130 同族的写法），
     `runTest` 的虚拟时间就变成了**假的时间**。
  2. 判据是"**被等的那个协程在不在测试调度器里**"，不是"哪个 API 更常见"。
  3. ⚠️ `runTest` 自带 60 秒自动超时：真实线程卡住时它**60 秒后才报**，
     而 `withTimeout` 又让它**立刻报** —— 两个计时器打架，症状就是"有时绿有时红"。
- **顺带一条 CI 实证**：这次 artifact `debug-unit-test-reports` 里**只有 `data/kdbx`
  的报告，没有 app 的** ⇒ app 模块的测试报告被上传条件跳过了。
  ⇒ 想看失败堆栈**别指望 artifact**，要么从日志挖，要么靠 #145.5 本地跑。

### 145.7 🟢 **批量巡检模式 `--all`** —— 从「逐个靠 CI 发现问题」到「一次跑完 97 个测试文件」（2026-10-04 新增，已用）

- **它补的是 #145.5 的哪个洞**：#145.5 解决了"**单个**文件能不能本地验"，
  但排查进度仍取决于"你想到要查哪个文件"。真实场景是：改了一处底层类型，
  要知道**哪些测试受影响** —— 那是"扫全仓"，不是"查一个文件"。
  ⇒ `--all`：按模块 → 按**包**分组，逐组「编译 + 真跑」，最后汇总。
- **为什么按包分组，而不是整模块一次编**（这是设计里最关键的一条）：
  app 的 35 个测试散在 16 个包，其中 `ui/*` 那几包需要 Compose 编译器插件
  （本工具没有）。整模块一次编 ⇒ **一个包编不过，35 个测试全部落空**，
  连纯逻辑的 `autofill/engine`、`util` 也一起陪葬。
  ⇒ 分组后：**能查的查出来，不能查的如实单独点名**（不静默吞掉）。

- **被测代码怎么带齐**（三次返工才做对，**别退回旧写法**）：

  | 尝试 | 为什么不行 |
  |---|---|
  | 按"测试文件名去 `Test` 后缀"猜被测类 | `AesCbcHmacTest` 测的是 `VaultixCrypto`/`SymmetricCryptoKey`/`ParsedCipherString`，**三个类分属三个文件**，没有一个叫 `AesCbcHmac.kt` ⇒ 满屏 unresolved，真因被埋掉 |
  | 整个模块的 `src/main/java` 一起编 | 对 core/、data/ 可行；对 app/ 不行（506 个主源码文件，大量 Compose） |
  | **按包索引求 import 传递闭包**（现行） | 兼顾两者，且**多编几个文件没有代价** |

  ⚠️ 闭包里那条"**同包引用没有 import ⇒ 整包纳入**"是**必需**的，不是优化：
  实测 `AesCbcHmacTest` 与被测的 `VaultixCrypto` 同为 `io.vaultix.crypto` 包，
  测试文件里**一个 `io.vaultix` import 都没有**（只 import 了 `javax.crypto`）。
  只按 import 走 ⇒ 闭包为空 ⇒ 又是满屏 unresolved。

- **首次跑就抓到的问题**（都是"编译绿、运行红"或"静默漏检"类）：

  | 问题 | 症状 | 真因 |
  |---|---|---|
  | **序列化插件没挂** | 4 条`PreLoginResponseTest` 全红在 `Serializer for class 'PreLoginResponse' is not found` | 缺 `kotlinx-serialization-compiler-plugin-embeddable`，且**版本必须与编译器完全一致**（2.1.20）—— 版本错了不是报错，是**静默不生效** |
  | **KMP 空壳 jar** | `unresolved reference 'Dao'/'Query'/'Upsert'` | `room-runtime`/`room-common`/`turbine` 的**根 artifact 是元数据**（room-runtime的 `.aar` 仅 4.5KB，只有 LICENSE、没有 classes.jar），字节码在 `<artifact>-jvm` 变体 |
  | **同版本 artifact 缓存名撞车** | 拿到错的字节码且**不报错** | 缓存名用 `${gav##*:}` ⇒ 三个同为 1.2.1 的 androidx artifact 撞成同一个 `1.2.1.jar` |
  | **`hilt-core` 漏了** | `unresolved reference 'InstallIn'`（报错指向 `di/CryptoModule.kt`） | `@InstallIn` 在 hilt-core，不在 hilt-android |
  | **truth 漏传递依赖 guava** | **编译完全正常**，运行期炸 `NoClassDefFoundError: ImmutableList` | Gradle 自动带传递依赖，本脚本不会 |
  | **没给 `-jvm-target 17`** | `cannot inline bytecode built with JVM target 11`（报错指向业务代码的 inline 函数） | kotlinc 默认 1.8 |
  | **`find -maxdepth 2`** | 只捞到 `app` 一个模块，**且有汇总有输出**，不报错 | 模块在 `core/crypto/src/test` 需 depth 3 ⇒ 97 个测试文件里静默只看了 35 个 |

- **工具自身也病倒了 6 次**（全部记在脚本头部，因为每一条都是"看起来在跑、
  其实什么都没查"那一类）：`mapfile -t` 拆空格分隔的分组值、关联数组命名
  `GROUPS`（bash 保留变量）、`"$m__$(...)"` 被解析成变量名 `m__`、
  `-I`(HEAD) 探测 Maven 坐标导致误判"dagger 2.60.1 不存在"（**已订正 #145.5**）…
  ⇒ 与 #145.1 同源：**工具给出的"否定结论"要先复核**。

- **首次全仓巡检结果**（8 个模块 / 97 个测试文件 / 28 个包组）：
  `core/crypto` 191 条、`core/common` 146 条、`data/repository` 145 条、
  `data/kdbx` 85 条、`data/bitwarden` 64 条等**全绿**；
  `app` 的 `ui/*` 等包因缺 Compose 插件**如实列为本地查不了**。
- ⇒ **判据**：#145.4「本地绿 ≠ CI 绿」依然成立，但"值不值得推一轮 CI 去验"
  的门槛变了——**改动落在上表"能查"的范围里时，本地跑 `--all` 即可**。

