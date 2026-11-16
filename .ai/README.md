# AI 协作记忆

> 供 **AI 接力**使用。开工前必读，收工后必更新。

> 🗺 **找"东西在哪 / 该信哪份文档"** → [`conventions/8.10-架构与目录地图.md`](./conventions/8.10-架构与目录地图.md)
> （模块依赖图 · 三层钥匙图 · 模块/目录/包地图 · **`Docs/` 是设计期蓝图、已滞后于实现**）

## ⚠️ 唯一真源在这里（2026-09-13 起）

**`.ai/` 是记忆的唯一定稿处，已入库、并被代码注释直接引用。**
`.workbuddy/memory/MEMORY.md` 只是**入口指针 + 3 条保命规则**，**不再镜像正文** ——
此前约定「两边保持同步」，结果是同一主题两处各有一份、必然漂移（AI 会看错位置）。
**要改内容，只改这里。**
## 🔧 沙箱能力（2026-11-10 打通 · 别再"写错一个字赔一轮 CI"）

> 详见 [`.ai/tools/SANDBOX-CAPABILITIES.md`](./tools/SANDBOX-CAPABILITIES.md)。

- **能**：真编译 + 真跑单测（core / domain / data 六个模块 **940 条用例 ≈ 3–4 分钟**）、
  类型错误 / 未解析引用 / 混编 Java、全套 `check_*.py` 静态门禁。
- **不能**：APK、lint/detekt、`app` 的 Compose 签名（单一 source set，没插件整包编不过）。
- **首次**：`bash .ai/tools/typecheck_kotlin.sh --fetch-compiler`（约 58MB）。
- ⚠️ 这套工具**此前在沙箱里从未真正跑起来**（它找 gradle 缓存里的编译器，而沙箱不跑 gradle）。
  本轮修了四处才通：stdlib 回退、`.java` 进闭包、`javac` 补字节码、批量模式同款。
- 已接进 CI：`ci-debug.yml` 的 `sandbox-tests` job 用**同一份脚本**，与 `android-ci` 并行
  ⇒ 本地绿 ⇒ CI 不会红在同一件事上。

## 🕐 最新状态（2026-11-16 · #298 请求体缺 encryptedFor ⇒ 1.37+ 服务端 422）

> **一句话**：真机改网址同步，服务端始终不变——根因锁定为**请求体缺 `encryptedFor`**，
> 而 Vaultwarden 1.37+（web-v2025.6.0）在 `Json<CipherData>` 守卫里把它设为必填，
> 缺即在 handler 之前 **422**。官方 bitwarden-android / keyguard / Monica 三客户端都发。

- **真机取证链（四层闭环，首次全落地）**：
  ① 日志 `flushPending op=UPDATE id=d19f28e7… code=422 definitive=false`，body 是 Rocket 默认 HTML；
  ② 从设备 `pending_ops.payload` 直读被拒请求体原文（`createdAt` 与日志相差 12ms，确证是本次）；
  ③ 三参考项目对照都发 `encryptedFor` = JWT `sub`；
  ④ Vaultix `CipherRequest` 全模块零出现 ⇒ 必然 422。
  ⚠️ 推翻了两个早期错误假设：不是通行密钥（217 条里带 fido2 的 ~20 条）、不是 `creationDate` 微秒精度。
- **为什么 #294~#297 没找到**：本地起 1.30/1.33 全 200（旧版无此必填字段），静态对照的也是旧版服务端模型；
  变化发生在 1.37。这不是排查失误，是服务端协议静默升级。
- **已落地修复**（本次，`58e3440b` 之后）：
  ① `CipherRequest` 加 `@SerialName("encryptedFor")`，默认 null（存量兼容）；
  ② 统一发送出口 `executeOperation` 在 CREATE/UPDATE 发送前注入 `encryptedFor` = access token 的 `sub`（UUID 校验），
     **存量 op 也救活**（不必清队列）；取不到 token 时降级跳过 + WARN，不阻断同步；
  ③ 新增 `BitwardenSyncEncryptedForTest` 4 例（UPDATE/CREATE 注入、token 缺失降级、非 UUID 降级）；
  ④ 新增 `check_encrypted_for.py` + 7 组变异自测，接 `ci-debug.yml` 两步。
- **对"同步快"的说明**：Bitwarden 协议只有 `GET /sync` 全量端点（Monica 亦然），无增量拉取。
  修好本 bug 后就地消掉了"每次推 422 + 全量拉取"的无用功，revision 未变时直接 Skipped——
  这才是体感上最大的提速；真有改动时的全量下载是协议限制，短期不动。
- ⚠️ **待 CI 真编译 + 单测**（本机无法编译）；只 push `main`，绝不推 `rele`。
  装机复现判据：日志出现 `flushPending op=UPDATE … code=200`（不再是 422），服务端修改时间推进。
- **✅ CI 真实结论（2026-11-16 17:13，commit `5a3b6a41`）**：两 run 全 success ——
  `Android CI (Debug)` [43837076839](https://github.com/Chaniug/Vaultix/actions/runs/43837076839)
  （detekt `BUILD SUCCESSFUL in 18s`、新门禁输出「✅ encryptedFor 契约检查通过（#298）」、
  4 例新测试与全量单测通过）、`AI docs consistency` 43837076961。
  preview `v0.13.4-dev-5a3b6a4 · Preview`，资产 `Vaultix-5a3b6a4.apk`（37.6 MB）。
  ⚠️ 只推 `main` 未推 `rele`，正式 v0.13.4 不受影响。
- **⚠️ 首轮真机验收修红（用户装 `5a3b6a4` 复现仍 422）**：日志出现本轮新增的
  `encryptedFor 未注入（无法从 access token 解析 user id）` ⇒ 修复代码跑了，但取值传错参数：
  `userIdFromToken` 把 `row.origin`（`https://pwd.…`，完整 baseUrl）传给 `accessTokenForHost`，
  而它的 key 是 `URL(server).host`（纯 host）⇒ 查不到 ⇒ 降级跳过 ⇒ 依旧 422。
  `BitwardenAuthInterceptor` 用 `url.host` 才是同口径；单测当时也 mock 成完整 URL，跟着一起错。
  修法：先 `URL(server).host` 再查；测试改 mock key + verify 钉住；门禁加变异用例（8/8 自测）。
  **✅ 修复后 CI 全绿**：两 run [43840280935](https://github.com/Chaniug/Vaultix/actions/runs/43840280935)
  / 43840280850 success，preview `v0.13.4-dev-8a0bc21`，资产 `Vaultix-8a0bc21.apk`（37.6 MB）。
  ⇒ **待用户装 `8a0bc21` 做第二轮真机验收**；队列里 2 条存量 UPDATE 正好验证"存量也救活"。

## 🕐 上一轮（2026-11-16 · #297 同步看不见 ⇒ 无法定位）

> **一句话**：云端还是没变、点同步还是"成功"，但这次的日志比上一轮**更少** ——
> 只剩一行 `sync skipped: revision 未变`，**连 `flushPending` 都没有**。
> 于是分不清「改动没进队列」与「进了队列被清掉」，两种故障修法完全不同。

- **证据**：`vaultix_logs_20261116_155316.txt`（`0.13.4-dev-d881fb3`，**已含 #296**）。
  15:51:54 点同步；15:51:54.315 只有 `sync skipped: revision 未变`。
  对比上一份 `..._142908.txt` 里明明有 `flushPending ... code=422`。
- **为什么这轮没能直接给出服务端字段**：唯一根因是**可观测性缺口**。
  `flushPending` 在队列为空时是**静默 `return Result.success(Unit)`** ——
  没日志、没痕迹，而 `executeSync` 随后照常报 `Skipped`（上层当成功）。
  写路径 `flushAfterLocalWrite` 成功时同样**一条日志都没有**。
- **静态排查（又一次）**：`BitwardenJson` 是 `explicitNulls=false`、未开
  `encodeDefaults` ⇒ null 字段被省略；`buildUpdateRequest` 里"改 URI"路径上的
  `type` / `match` / `reprompt` 都是有界 Int，`uri` / `name` 是 EncString 或 null
  ⇒ **请求体值层面也不存在能凭空造出 422 的非法值**（与本地起 1.30/1.33 实测全 200 一致）。
- **已落地的处置**：① `flushPending` 空队列**必须留 WARN**（"队列为空 + 不会上传"两层语义）；
  ② 开头报「待推送 N 条（按 op 类型）」、收尾报「全部成功 / 剩 M 条留在队列」；
  ③ 写路径统一出口 `flushAfterLocalWrite` 成功与失败**都留痕**，且共用 `VaultixSync` tag
  ⇒ 用户一次 `adb logcat -s VaultixSync` 就能看到「入了什么队 + 推了什么」；
  ④ 日志摘要只聚合 op 类型，**不得带条目 id**（`VaultixLog` 铁律）。
- **验证**：新增 `BitwardenSyncFlushObservabilityTest` 3 例
  （空队列留痕 / 成功报条数且无 id / 失败说明改动仍在队列），
  `data/bitwarden` **124 例全绿**（121+3）；行为反证：把空队列改回静默 ⇒ 用例立刻红，
  字节级还原复绿。新增 `check_sync_observability.py` + **14 条变异自测**，
  接 `ci-debug.yml` 两步（`android-ci` 42 → 44 步）。
  ⚠️ 写门禁时**自测抓出 3 个真实假绿**（KDoc 里同词、按"下一个 fun"截取把注释算进函数体、
  判断常量名用了 sanitize 后的文本），均已修并有对应用例。
- **同批追加（写路径闸门卡死）**：顺着"A 判据"（队列为空）往回查，在三个写入口发现
  `saving` 这个**防连点闸门会卡死**：复位写成 `repo.xxx()` 的**下一句**，而仓储
  `ItemRepositoryImpl` 用的是 `error(...)`（**抛异常**）而不是 `Result.failure(...)`
  ⇒ 一次"库未解锁 / 条目不存在"就能让 `saving` 永久 `true`，之后**每一次点保存都被
  开头的 `if (saving) return` 静默吞掉**：界面像保存了，实际一行没落库、一条 op 没入队，
  点同步自然只能得到"无改动"——与用户描述完全吻合。
  处置：`ItemDetailViewModel.updateItem` / `ItemsViewModel.createItem` /
  `AutofillSaveViewModel.saveNew`·`updateExisting` 四处复位移入 `finally`；
  `createItem` 另加 `catch (e: CancellationException) { throw e }` →
  `catch (e: Throwable) { Result.failure(e) }` 收口（原本逃逸会**炸 App**）。
  三个入口共用 `VaultixSync` tag ⇒ 保存链路在日志里是连续的一串。
- **验证（闸门）**：`ItemsViewModelSavingGateTest` 3 例（复位 / 第二次仍能打到仓储 / 闸门本身仍生效），
  三条变异反证全红（复位移回下一句 ✅、catch 收窄 ✅、删闸门 ✅）。
  `app` **724 例全绿**、`data:bitwarden` **124**、`data:repository` **352**，detekt 通过；
  **23 个门禁 + 20 个自测**全绿（顺带把 README / next-steps 里写错的「24 个」改为 23）。
- **CI 实证（2026-11-16）**：`58e3440b` → `Android CI (Debug)` 43829870101 / `AI docs` 43829870031 全绿；
  `c1f3cc54` → `Android CI (Debug)` 43834201528（`Lint/test diagnostics/debug build` +
  `Sandbox unit tests` 两 job）/ `AI docs` 43834201510 全绿。preview 资产 **`Vaultix-c1f3cc5.apk`**
  （35.85 MB，`v0.13.4-dev-c1f3cc5 · Preview`）。
- **下一步（需要用户配合）**：装新包 `Vaultix-c1f3cc5.apk` 复现一次，回传日志。这次一定能看到
  `flushPending 队列为空（无待推送 op）⇒ 本次同步只做拉取，不会上传任何改动`
  或 `flushPending 开始：待推送 N 条（UPDATE=1）` + `flushPending op=UPDATE ... code=... body=...`；
  保存那一刻还会先出现 `写路径收口：updateItem 已返回（成功）· <id>` 或 `写路径中断：…`。
  这几句直接判定故障在**写路径**还是**推送路径**，不必再猜。

> 上一轮（#296 HTTP 422 静默弃单）已按 8.9 §6 迁出：
> [archive/README-状态块-2026-11-16-296.md](./archive/README-状态块-2026-11-16-296.md)。
> 再上一轮（#295 编辑条目用错钥匙）见 [archive/README-状态块-2026-11-16-295.md](./archive/README-状态块-2026-11-16-295.md)。

---
## 🕐 上一轮（2026-11-15 · v0.13.4 正式发布）

> **一句话**：真机取证 → 修复 → 正式发版闭环，连发 v0.13.3 / v0.13.4。

- **通行密钥**：单行 PEM 私钥解析修复（按 BEGIN/END 标记剥离，不再依赖换行）+ 诊断串带
  输入形态指纹；A 库私钥自始完好，**旧库无需迁移、无需重注册**。#293 凭据 ID 保真一并进正式版。
- **判据**：`TYPE_NO_CREDENTIAL` 先查系统「首选凭据服务」开关（覆盖安装会重置），
  不是 App 解析失败。见 [8.5 通行密钥](./conventions/8.5-通行密钥.md)。
- **省电 / UI**：TOTP 页后台挂起每秒时钟；查看层锁页重排为**密码库主体**
  （放大库徽标、文案砍到一句、主色 CTA）。两者均在 v0.13.4。
- **发布**：v0.13.4 已置 Latest（run 43705947147，签名 7e6c8c07…）；
  说明见 [v0.13.4 发布说明](../Docs/release-notes/v0.13.4.md)。
- **验证**：CI 1866 例单测 0 失败；TOTP 生命周期与 dev 包全链路真机复验通过。

---
## 🕐 更早状态（2026-11-01 ~ 2026-11-09）

> 已按日期拆成 4 篇（2026-11-09 二次拆分，原文 481 行 / 37KB 越 §7.1 上限）：
> [`STATUS-ARCHIVE.md`](./STATUS-ARCHIVE.md)（11-09）·
> [`STATUS-ARCHIVE-2.md`](./STATUS-ARCHIVE-2.md)（11-06 ~ 11-08）·
> [`STATUS-ARCHIVE-3.md`](./STATUS-ARCHIVE-3.md)（11-04 ~ 11-05）·
> [`STATUS-ARCHIVE-4.md`](./STATUS-ARCHIVE-4.md)（11-01 ~ 11-03）。
> 本节只留指针 —— README 是**入口**，只该承载"最新 + 上一轮"。

---


## 目录结构（索引 + 分篇，**按需只开一篇**）

| 路径 | 是什么 | 规模 |
|---|---|---|
| `README.md` | 本文件：接力入口（只承载**最新 + 上一轮**状态） | 172 行 / 15.3KB |
| `STATUS-ARCHIVE.md` 等 4 篇 | **状态归档**（倒序，新块在最上面）：`-`（11-09）· `-2`（11-06~11-08）· `-3`（11-04~11-05）· `-4`（11-01~11-03）。2026-11-09 二次拆分：单篇曾 481 行 / 37KB 越 8.9 §7.1 上限 | 各 8~11KB |
| `MEMORY.md` | 项目长期笔记（定位 / 架构 / 里程碑 / 技术栈硬约束 / 协议与来源 / 发布签名） | **23KB**（2026-11-01 从 109KB 瘦身：§9 历史状态已归档） |
| `MEMORY.md §8` | **索引**：写代码前的长期约定 → 正文在 ↓ | — |
| `conventions/` | 约定正文 11 篇：`8.1-自动填充` `8.2-锁与解锁` `8.3-M2KDBX` `8.4-UI·观感` `8.5-通行密钥` `8.6-工程质量` `8.7-环境` `8.8-M3Expressive-采纳范围与顺序` `8.9-文档分篇` **`8.10-架构与目录地图`** `8.11-七类不报错的错` | 各 2~24KB；**8.4 / 8.6 已拆 `-partN`**（主篇保留速查清单） |
| `ISSUES.md` | **索引**：坑的「分类 → 主篇索引」（最新 **#256**；#255 自动填充篇，#249 / #254 / #256 界面篇）。⚠️ 逐条摘要**与正文重复**，已按 8.9 §7.4 移出 —— 查某条：点分类 → 主篇「章节明细」 | **8.4KB**（原 84KB） |
| `issues/` | 坑的**正文**：7 个分类 = 7 个索引 + **41 个分篇**（`-partN`）。`## NN.` 锚点原样保留，代码注释里的 `#NN` 仍能 grep 到 | 每篇 ≤22KB |
| `decisions/` | **逻辑定稿**（用户拍板的方向，非根因、非实现）：`快速解锁房子化-两级钥匙层级-定稿.md`（**快速解锁现行真源**，2026-09-28 起修订前两篇）、`库选择与快速解锁-逻辑定稿.md`、`快速解锁能力级重构-定稿.md`、`设置页信息架构-定稿.md`、`通行密钥UV豁免-定稿.md`、`条目页-M3-视觉方案.md`（**P0 已实施，P1/P2 待做**） | — |
| `SESSION-YYYY-MM-DD.md` | 逐轮工作日志（append-only）。**2026-09 的 17 篇已按时间归档**到 `archive/sessions-2026-09/`（不拆内容 —— 历史日志没人整读，拆开只会让文件数翻倍） | 21 篇 ≈320KB |
| `archive/` | **归档区**（只挪不删，均为历史，**不要当现状读**）：`MEMORY-9-历史状态-2026-09至11.md`、`README-状态块-2026-09至10.md`、`sessions-2026-09/` | ≈1MB |
| `tools/` | **本地自检脚本**（不需 Android SDK，`python3` 直接跑）。覆盖「编译器才能发现、detekt 查不到」的缝：<br>`check_compile_smells.py` = 图标导入 / 顶层常量顺序 / `R.string` 悬空引用 / **重复声明**（第四个检查，见 #103；带 `--detekt-probe` 两级探针）；<br>`check_signature_types.py` = 签名里的类型名是否存在；<br>`check_import_packages.py` = `import` 的包路径对不对（#101 / #104）；<br>`check_experimental_optin.py` = 实验性 API 有没有 `@OptIn`（#101.2，带 `--selftest`）；<br>`check_issues_index.py` = **`ISSUES.md` 索引与分篇正文的一致**（#160：曾漂四次；查编号集合 / 题注数字 / 总表条数列三处相等，配套 `tests/selftest_issues_index.py` **6 例正反用例**）；<br>`check_orphan_strings.py` = `strings.xml` 里**零引用**的字符串（#126；⚠️ 是**报告器不是删除器** —— 「被搬走的留，被取代的删」需人判读；`--gate` 用基线做**只减不增**约束）；<br>`check_state_flattening.py` = **多态状态（sealed ≥3 分支）有没有被压成二值开关**（#124；`Switch(checked = x is S.On)` 会把 `Partial` 与 `Off` 合并 —— `is On` 是合法 Kotlin，类型检查与 detekt **双双查不出**）；<br>`check_orphan_state.py` = **Compose 状态变量是不是「只写不读」**（2026-09-30 真机实录：`var showX by remember…` + `onClick = { showX = true }` 都写了、**漏了 `if (showX) { XDialog() }`** ⇒ 点那行毫无反应；⚠️ 这一形状**五道门禁全盲**：Kotlin 对 `by` 委托的**局部**变量不报 unused、detekt 不查局部委托、孤儿串看不见。判据=「标识符只出现在声明与赋值左侧」；**零容忍**（无基线）。配套自测 `tests/selftest_orphan_state.py`）；<br>`fetch_release_asset.sh` = **分块断点续传下载 release 资产**（沙箱整文件下载不可靠，见 #105；自带 `zipfile` 完整性校验） |
| `tools/tests/` | **门禁自己的自测**（改探针前必跑）：<br>`selftest_duplicate_declarations.py` = 7 个正反用例；<br>`selftest_import_packages.py` = **端到端**（真把 import 改错、跑脚本、看退出码、还原）；<br>`selftest_cross_file_private.py` = **端到端**（真把 `internal` 改回 `private`、验证报出两个调用方、还原）；<br>`selftest_orphan_strings.py` = 7 个用例（含**前缀撞名** `a_head`/`a_header` 的经典误判，以及"零引用被误删只在运行时才炸"的各种引用形态）；<br>`selftest_state_flattening.py` = 9 个正反用例。⚠️ 含**全限定名**形态（`Outer.Cap.On`）—— 少了它，探针会在真实代码上静默失效（见该文件开头） |

> ⚠️ **各道脚本的扫描范围都是 `app/ core/ data/ domain/`**（排除 `reference/` 对照源码与 `build/`）。
> 2026-09-16 之前只扫 `app/src`，**同一个盲区造成两次 CI 红**
> （domain 的重复声明、data 的错 import 都扫不到）。**改门禁时先确认它扫的是不是全部 —— 看报的数字。** — |

> **为什么这么拆**：原先 `.ai/ISSUES.md` 139KB、`.ai/MEMORY.md` 43KB ——
> 全读会把真正需要的上下文挤掉，定位只能靠 grep。现在入口稳定、**正文按需读一篇**。
> 仓库里 115 处「见 `.ai/ISSUES.md` #NN」的引用依然有效：先查索引表，再跳分篇。

## 接力流程

1. **先读本文件顶部「🕐 最新状态」** —— 当前版本 / 上一轮 / 真机验收欠账 / 发布结论的
   **唯一真源**（倒序）。`MEMORY.md` §9 也把「当前状态」指回这里，
   **别从中间的历史状态块起手**（2026-11-14 校正：旧版这里写「读 MEMORY.md 拿当前状态」，
   与 `MEMORY.md` 自己的声明矛盾）。
1b. 再读 `MEMORY.md` —— 项目定位、架构、硬约束与长期约定（**不当当前状态读**）；
   待办看 `Docs/progress/next-steps.md` 顶部，`current-status.md` 已冻结存档。
2. **要动「快速解锁」→ 先读 `decisions/快速解锁房子化-两级钥匙层级-定稿.md`**
   （2026-09-28 起的现行真源，**别重新论证**；施工进度 = `Docs/progress/quick-unlock-house-rework.md`，
   **批次 1-4 已收工（含批次 3.5 真机反馈修复），下一会话从
   `Docs/progress/house-rework-batch4-handoff.md` 进；失效矩阵决策见定稿 §6.1**）
   「库选择 / 默认库」→ 仍读 `decisions/库选择与快速解锁-逻辑定稿.md`（其快速解锁章节已被房子化定稿修订）
2b. **要动「设置页」→ 先读 `decisions/设置页信息架构-定稿.md`**
   （7 组 → 5 组的目标结构 + 全量文案改动表，**别重新设计分组**；§6 是开工步骤）
2c. **要动「Material 3 / 观感 / 动效」→ 先读 `conventions/8.8-M3Expressive-采纳范围与顺序.md`**
   （哪些该借、哪些明令不借、按什么顺序做；含 2026 的设计口径与本项目实测）
2d. **要动「KDBX 网盘同步（OneDrive / WebDAV）」→ 先读
   [`Docs/progress/cloud-sync-plan.md`](../Docs/progress/cloud-sync-plan.md)**
   （★ 前置是 KDBX 阶段 B 写回，勿跳过；冲突策略有三个方案待拍板，**别自行决定**）
2e. **要动「性能」→ 读 [`Docs/progress/perf-plan.md`](../Docs/progress/perf-plan.md)**
2f. **要做「原生化 / Rust / Go 重写」→ 先读
   [`Docs/progress/native-rewrite-eval.md`](../Docs/progress/native-rewrite-eval.md)**
   （已给结论：**现在别动**；唯一量化热点是 KDF；要引就引 Rust 不要 Go；**先量真机**）

3. **按本次要动的模块**，只开 `conventions/` 里对应的一篇（例：动填充 → `8.1-自动填充`）
4. **按本次要碰的模块**，只开 `issues/` 里对应的一篇（例：动填充 → `02-自动填充`）
5. 读 `Docs/progress/next-steps.md`（**待办唯一真源**；已完成的历史已按时间段归档，
   见文件末尾的归档索引，**勿通读**）与 `current-status.md`
6. 完成后**更新上述文件**：新坑 → 写进对应 `issues/*.md` 并在 `ISSUES.md` 索引表补一行；
   新约定 → 写进对应 `conventions/*.md`。**别把正文堆回索引。**

## 两类记录的分工（2026-09-13 明确，**别混**）

| | `.ai/SESSION-YYYY-MM-DD.md`（本目录，入库） | `.workbuddy/memory/YYYY-MM-DD.md`（本地，未入库） |
|---|---|---|
| 定位 | **面向接力者的纪要** | **工作流水**（详细过程） |
| 写什么 | 这轮改了什么 · 为什么 · 怎么验证的 · 结论 | 命令、中间尝试、失败路径、逐条取证过程 |
| 长度 | 精简 | 可以很长 |
| 谁看 | 任何接手的人（含新克隆仓库的人） | 只有本机 AI |

**收工规则（单向，从 2026-09-13 起）**：
1. 把当天本地流水里**对接力有用的部分**整理成一段，**追加**进 `.ai/SESSION-YYYY-MM-DD.md`；
2. 然后把**本地当天的日志删掉**（它只是过程草稿，次日不再保留）。

> ⚠️ **历史未回溯合并**：本地流水现存 **2026-09-08 / 09-09 / 09-10 / 09-11 / 09-14 /
> 09-15 / 09-16 / 09-17** 八天（约 320KB），与 `.ai/SESSION-<同日>.md` **内容并不相同、
> 各有一方独有**（不是副本，是两套独立写下的流水）⇒ **不能盲删**。
> **查结论以 `.ai/SESSION-*` 为准，查过程细节参考本地流水。**
> ⇒ 清理步骤见 **[`Docs/progress/docs-slimming.md`](../Docs/progress/docs-slimming.md) §2.2**：
> 先逐份比对、把独有内容**追加**进 `.ai/SESSION-*`，确认无遗漏后再删；**一次只处理一天**。

## ⚠️ 读记忆的正确姿势

**被推翻的旧结论不会删除，而是就地标注 —— 看到 `~~删除线~~` / `🔴 已被推翻` /
`~~xx~~` 标记时，务必读完整标注再决定要不要照做。**

真实案例：`issues/` 里 #35 曾判定「通行密钥浏览器流程应回传**空占位符**」，
该结论于 2026-09-12 被 **#43** 推翻（回传空占位符会让 GitHub 注册直接失败）。
**只读 #35 不读 #43，就会重新引入同一个 bug。**

两条纪律：

| 纪律 | 说明 |
|---|---|
| **旧结论就地加勘误指针，不删原文** | 保留错误推演过程有价值（能看出当时怎么想歪的），但**必须在标题处**给出推翻标记与正确去向 |
| **推翻类记录必须写「错在哪」** | 否则接力者只知结论变了、不知**为什么**变，容易又绕回去 |

## 另有一条工作纪律（2026-09-13 补）

**遇「用户看得到、我复现不了」的问题，先取实证再动代码**，且**先对齐复现路径**
（哪个手势 / 哪个入口 / 哪台设备）。
本轮为此付出代价：「手势返回画面缩小」改了三次转场都没中，因为一直在用**按键返回**复现
**手势**返回的问题；「填充反复解锁」白改一轮，因为只抓了自己的日志 tag，
看不到系统框架侧说「认证结果被丢弃」。详见 `issues/06-界面与交互.md` #81 与
`issues/02-自动填充.md` #82。
