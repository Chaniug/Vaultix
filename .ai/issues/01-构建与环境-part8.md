# 01-构建与环境 · 第 8 篇（#146 ~ #173）

> 主篇索引：[`01-构建与环境.md`](./01-构建与环境.md)（含计数规则与重号警告）
> 本篇 6 章 · 14.7KB · 覆盖 #146 ~ #173

## 146. mockk：stub 里的**裸方法名会被 `MockKMatcherScope` 遮蔽**（2026-10-01）

    coEvery { get(any()) } coAnswers { row(...) }
    // e: Return type mismatch: expected 'MockKMatcherScope.DynamicCall', actual 'VaultEntity'

- **根因**：`coEvery { }` 块的 receiver 是 `MockKMatcherScope`，它自己有 `get`
  （用于动态调用、返回 `DynamicCall`）⇒ 裸写的 `get` 解析到它，而不是 `VaultDao.get`。
- **报错信息完全不提"遮蔽"**，看起来像"返回类型写错了" ⇒ 极易往错的方向查
  （本次第一轮就误判成 `firstArg` 的 import 问题，白花一轮 CI）。
- **修法**：先建 mock、再**显式带 receiver** stub：
  ```kotlin
  val dao = mockk<VaultDao>()
  coEvery { dao.get(any()) } coAnswers { row(...) }   // ✅
  ```
- **这解释了仓库既有测试的写法**：`BitwardenSyncOrchestratorTest` 全篇
  `coEvery { vaultRepository.syncVault(vaultId) }` —— **不是风格偏好，是避坑**。
  ⇒ 写新测试时照抄这个形式，别图省事写裸方法名。
- 附带两条同一文件里踩到的：
  - `coAnswers` / `firstArg` **都不需要 import**（`MockKAnswerScope` 的成员）；
    写 `import io.mockk.coAnswers` 反而是 `Unresolved reference`。
  - 要读**每次调用时的实时值**必须用 `coAnswers` 而不是 `returns`
    （`returns` 只求值一次 ⇒ 模拟不出"状态被消费掉"）。

---

## 160. 🔴 索引与正文**第四**次漂移：四种形态，且每次都是由"我以为在维护"引入的（2026-10-02）

- **背景**：`.ai/ISSUES.md` 是索引、`issues/*.md` 是正文，**同一事实两份**。
  2026-09-21 / 09-26 已两次记账说"必然漂移"，约定"改哪篇顺手改那个数字"。
  本次双库健康度审计补 #153–#159 时顺手核对，发现**该约定彻底失效**：

  | 形态 | 实例 | 为什么它躲得过"顺手改" |
  |---|---|---|
  | ① **标题层级写错 ⇒ 从未被计入** | 01 篇 **#102–#105** 是 `###` 三级标题 | 计数规则只数 `## NN.`；它们还**被埋在 #101 的视觉层级下**，读起来像 #101 的子条 |
  | ② **增量式改法** | 06 篇题注从 33 写成 **+1 = 34**（实际应 +4） | "看起来在维护"最像维护的一种：**把自己的改动叠加到旧的错基数上**，上一轮加的 #142–#144 从来没进过名单 |
  | ③ **题注数字与名单互不可证** | 03 篇写「18 条」却只列 **16 个号** | 只校数字会漏（数字恰好蒙对、名单错），只校名单也会漏（反之亦然）|
  | ④ **跨表错位 / 重复行** | #88 正文在 04、改列在 06 表；#128 在 01 表重复两行；#83 两表都列、正文里根本没有 | 改自己那篇时看不见别篇那一行 |

  七篇差额：实际 vs 索引列 = **12 / 0 / 1 / 0 / 4 / 4 / 13** 条。
  ⚠️ 注意 02、04 篇 **恰好为 0** —— 这不能被当作"不用查"，恰好相反：
  **漂移是随机的，"这次没漂"不含任何预言能力**（与 #145「job 卡片颜色既不能证明也不能否证」同构）。

- **解法**：`check_issues_index.py`（查三件事：编号集合相等 / 题注数字相等 / 总表条数列相等）
  + `tests/selftest_issues_index.py`（**6 例正反用例**，且反向要求"报错必须指到被改坏的那一处"——
  只看有没有输出，看不出它抓错了东西，见 #124）。
  ⚠️ **重号不修**：#50（02 vs 03）、#103（01 vs 06）是历史上两个坑共用一号；改编号会让代码注释里
  `见 .ai/ISSUES.md #NN` 全部失真 ⇒ **维持编号、放进 `KNOWN_DUPLICATES`，要求引用处带分篇名**。

- **⚠️ 判据（三条，可迁移到任何"数字要保持一致"的地方）**：
  1. **数字不许用增量算，必须从源头重数。** 写 "+N" 那一刻你就承认了旧基数是可信的——而它正是不可信的那个。
  2. **同一事实的两处表达要同时校，不能只校一处。** 数字与名单的关系是"谁也证明不了谁"，
     03 篇的 18 vs 16 就是这么留下的。
  3. **人的注意力天然落在自己刚碰过的东西上** ⇒ 漏的永远是别人的（或上个月的自己）。
     ⇒ 凡是"我这的确对了"这种感觉，都要用"全量重算"替代。

---

## 168. ⭐ detekt 两个「顶格」类：给贴上限的类加分支前，先想好腾哪（2026-10-14）

- **现象**：一批看似微小的 UI 改动接连撞红 —— `CardWalletScreen` 加 3 个门控分支
  ⇒ `CyclomaticComplexMethod` **17**（上限 14）；`ItemFormDialog` 加 1 个初始类型兜底
  ⇒ **16**；`QuickUnlockController` 加 1 个方法 ⇒ `TooManyFunctions` **41**（上限 40）。
- **根因**：这些类**本来就贴着上限**（`CardWalletScreen` 原值就是 14，
  `QuickUnlockController` 正好 40）—— 与 #40（同文件 helper 被累加进调用方）、
  #103（`VaultRepositoryImpl` 40 顶格逼出块级搬运事故）同族：
  **上限不是"快到线了"的警告，是"再动一格就红"的现实。**
- **解法**：给顶格类加东西之前**先查它离上限几个位**，新分支一律抽
  **文件级纯函数**（`CardWalletScreen` → `cardWalletFabVisible` / `cardEmptyStateRes`；
  `ItemFormDialog` → `fallbackFormType`；`QuickUnlockController` 把 `newPinInvalid`
  从类方法挪成文件级 `internal`）。文件级纯函数还有两个附赠：
  ① 不进类的函数计数；② **天然可单测**（`newPinInvalid` 因此补了 3 条用例）。
- **⚠️ 判据**：**"拆出来"永远比"塞进去"便宜** —— 塞进去的代价是撞红后回头拆
  （还可能拆出 #103 那种搬运事故），拆出来只是多写三行 KDoc。
  动手前 `grep -c "fun "` 数一下 + 看一眼现有复杂度，是 10 秒的自检。

---

## 171. 🔴 过期 test-results XML 误导核数：编译失败时旧报告残留，「核数」数出一堆幽灵失败（2026-10-16）

- **现象**：跑 `:data:kdbx:testDebugUnitTest` 后核 JUnit XML，报「1 例失败」——
  `Card条目_载荷守卫` FAIL（expected Login, was Card）。但同一份日志里
  **编译先失败了**（追加分组用例漏 `import app.keemobile.kotpass.models.Group`），
  测试任务这次根本没跑。
- **根因**：`testDebugUnitTest` 编译失败时，**上一次运行的 XML 原样残留在
  `build/test-results/testDebugUnitTest/`**（Gradle 的 up-to-date 判定只管任务本身，
  不清产物目录）。核数脚本 glob 全量 XML，把 **22:47 变异验证时留下的、本来就
  期望报红的那份**当成了本次结果。两份事实矛盾（日志说没跑 / XML 说有失败）时，
  顺着 XML 走就会去「修一个不存在的 bug」——本次差点这么干。
- **解法**：核数前先对时 ——
  `ls --time-style=full-iso <模块>/build/test-results/testDebugUnitTest/*.xml`，
  确认最晚 XML 的 mtime **晚于本次任务启动**；或干脆先
  `rm -rf` 各模块 `build/test-results` 再跑。核数脚本本身应把
  「扫描到的最早/最晚 XML mtime」一并输出，让过期一眼可见。
- **⚠️ 判据**：**任何从构建产物里读出的「事实」，先证明这份产物是本次的。**
  与 #145 同构（job 卡片颜色既不能证明也不能否证测试结果）：
  过期 XML 也「看起来就是结果」。纪律：**核数结论与当次日志矛盾时，
  先怀疑读到的数据不是这次的**，再去改代码。

---

## 172. 🔴 OneDrive 无基线写入 = 无条件覆盖；且分片上传的 uploadSession 请求体一直是**非法 JSON**（2026-10-17）

- **现象**：两件事，一起在「给写路径补单测」时暴露。
  ① `OneDriveKdbxFileSource.write` 在 `expectedVersion = null`（无基线）时，
     `simpleUpload` **一个条件头都不发** ⇒ Graph 直接覆盖同名文件。
  ② 更严重：`createUploadSession` 的请求体是**手拼字符串**，
     `append(if (forceReplace) "replace" else "fail")` **值没加引号**，
     实际发出 `{"item":{"@microsoft.graph.conflictBehavior":fail}}`。
     这是**非法 JSON**，Graph 回 400 ⇒ **任何 > 2MiB 的库在 OneDrive 上从来没存进去过**
     （小库走简单上传，根本不经过这里，所以长期无人发现）。
- **根因**：
  - ①「`expectedVersion = null`」被当成了"随便写"的许可。而 `KdbxFileSource.write` 的契约是
    **无基线 ⇒ 退化为最严保障**（`SafKdbxFileSource` / `WebDavKdbxFileSource` 都做了
    写前预检 + 写后读回，只有 OneDrive 两样都没有）。
    一直没炸，是因为唯一的无基线调用方 `Kdbx.createVault` 恰好先 `stat` 过 ——
    **那是调用方的自觉，不是来源层的保证**。一旦新增一条无基线写入路径，就是静默数据丢失。
  - ② 手拼 JSON。少一对引号，编译器一声不吭。
- **解法**：
  1. 接口加 `failIfExists: Boolean = false`（独立参数，**不靠 null 推断** —— 与 `force`
     单独存在同构：null 表示"没有基线"，failIfExists 表示"存在就是错"，两者不同）；
     `Kdbx.createVault` 传 `failIfExists = true`；
     三个来源各自对齐（SAF 在 `openOutputStream("wt")` **之前**拦；WebDAV 强制丢弃基线；
     OneDrive 发 `If-None-Match: *`）。
  2. OneDrive 补**写后读回校验**（`verifyReadBack`：先比 size 做廉价预筛，
     再下载逐字节比对；读不回来也算冲突 —— **不能**当成功）。
  3. 请求体改由 kotlinx-serialization 生成 DTO，并把 `Json` 的
     `explicitNulls = false` 显式打开（否则 `ifMatch: null` 会一起发出去）。
- **⚠️ 为写这组测试顺带踩出的三件事**（都对以后有用）：
  - **`Uri.encode` 是 Android 桩方法**：`unitTests.isReturnDefaultValues = true` 让它返回
    `null` ⇒ `encode(...) must not be null` 的 NPE，**任何**走 Graph 的用例都跑不起来。
    解法：换成纯 Kotlin 的 `percentEncodePathSegment`（RFC 3986 路径段规则），
    顺带消掉一个没必要的平台依赖。
  - **`runTest` 测不了真线程**：裸 `runTest` 用虚拟时间，而 `upload` 是
    `withContext(Dispatchers.IO)` ⇒ 断言抢在拦截器之前执行；改
    `runTest(Dispatchers.Default)` 直接抛 `Dispatcher must implement TestDispatcher`。
    ⇒ 用 `runBlocking`（要验的是真实请求长什么样，本来就不需要虚拟时间）。
  - **Hilt 不接受"带默认值参数的 `@Inject constructor`"**：Kotlin 为默认值生成的合成构造
    与 `@Inject` 那个撞车 ⇒ `may only contain one injected constructor`。
    ⇒ 改 `@Provides`；类还要 `open`（mockk 给 final 类打桩要挂 inline agent）。
- **⚠️ 判据**：**"实现方遵守契约"不能靠调用方的自觉来兜。** 契约写在接口上、却没有一条
  测试盯着实现方 —— 那这份契约就只是注释。给 `KdbxFileSource.write` 加参数的同一轮，
  必须同时给三个实现各补一条"无基线/只创建"用例，否则下次加第四个来源照样漏。
  另：**手拼结构化文本（JSON/XML/SQL）是可控缺陷源**，能交给序列化器就交出去。
- **⚠️ 遗留（需真机）**：Graph 官方 `driveitem-put-content` 文档**只字未提**
  `If-None-Match` 的语义，社区实证可行但属"副作用而非契约"。
  ⇒ 已用标准写法实现，**201/412 的实际表现需真机验证**（本环境无 Microsoft 账号）。
- **⚠️ 顺带一个 detekt 冷知识**（`ThrowsCount`, `excludeGuardClauses: true`）：
  它把**"函数体第一条非声明语句"的 throw 当守卫子句放行**，但只要前面出现了一次
  **suspend 调用**（哪怕只是 `runCatching { suspend() }`），后面的 throw 就**不再算守卫**。
  实测一个函数里三个 throw：全排在一起报 3（上限 2）；把"大小预筛"抽成独立的
  `verifySize` 后正好只剩 2 条 ⇒ 绿。**不要**为此放宽阈值，也不要加 `@Suppress`
  （那等于把这扇门关掉）。

## 173. 🔴 沙箱里"全绿"的 1155 例，推到 CI 却红在单测步 —— **本地 Gradle 缓存喂了幻觉**（2026-10-17）

- **现象**：同一 commit（`75ae07f`），本地按 CI 同款 8 条命令 `--rerun-tasks` 跑
  得出 **1155 例 0 失败**；推到 GitHub 后 run `39042480023` 的
  `Run unit tests (hard gate)` 是 **failure**。其余步骤（detekt / 编码 / Build APK /
  preview 发布）全绿，说明**不是编译错误**，是**测试真的挂了**。
- **根因（已用 A/B 实验隔离确认）**：
  1. 先做 **A**：把本次改动的 18 个 `.kt` 移出构建路径、把 `strings.xml` 还原成基线
     ⇒ CI **全绿**。⇒ 红与改动有关，执行环境/网络/配额无关。
  2. 再做 **B**：在某个测试类里注入一条 `fail("CI 探针")` ⇒ CI 的
     `Run unit tests (hard gate)` 变红（另有一次 `Publish Debug APK to preview Release`
     也红，那是 `preview` release 发布步骤自身的偶发/竞争问题，与本次无关）。
     ⇒ 该步骤在 push 链路上**确实会执行、也确实会跑用例**，且**没有**
     `continue-on-error`（#145 的另一半是真的）。⇒ **红是真的，本地绿是假的。**
  3. 假的来源：沙箱 `~/.gradle`（2.5 GB）里**早就有**同一版本（3fc8649…6ae9bcd 那一批）的
     `gradle-build-cache` 命中记录 ⇒ `FROM-CACHE` 不会重新执行用例，**但会从缓存里
     拿回旧的 test-results**。所以"XML mtime 晚于启动时刻"这条判据**在这个场景下失效**。
- **解法 / 判据**：
  - ⚠️ **只有本机全新的、无 build-cache 命中风险的执行**才算数。
    验证"缓存没参与"要看任务行是 `> Task :x:test…` 而**不是** `FROM-CACHE` / `UP-TO-DATE`
    （`--rerun-tasks` 能治 `UP-TO-DATE`，**治不了 build-cache**）。
  - ⚠️ **只拿文件 mtime 当"新鲜度"证据会被 build-cache 骗过**（#171 是同一族陷阱的
    另一种形态：那条是"旧 XML 残留"，这条是"新 XML 里的旧结果"）。
  - 🔴 **不要在这个时候去 `gh cache delete` 清 CI 侧缓存来"消毒"**：清掉缓存后
    若 CI 再红，本来清晰的 "与改动相关" 会退化成 "与冷缓存相关"——**把一次隔离实验
    变成两次未知**。要清也只清本地 `~/.gradle`，别动 CI 侧。
- **处置（本轮）**：**无论单测那一行到底红了哪几例，本次改动一行都不回滚**
  —— 三个 CI 步骤——`detekt` / `Build Debug APK` / `编码检查`——全绿，说明改动本身
  **可编译、可过质量门**。取证走"workflow 里加一个临时取证步骤，看失败用例名"。
  ⚠️ **踩到的 YAML 坑**：步骤名写 `- name: [TEMP PROBE] …` 会被 YAML 当成
  **流序列（flow sequence）** ⇒ workflow 解析失败 ⇒ run 在 0 秒内 `failure` 且
  `jobs: []` 是空的（API 返回 `total_count: 0`）。**步骤名别用方括号开头。**


