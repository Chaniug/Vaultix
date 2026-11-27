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

## 🕐 最新状态（2026-11-27 · 附件完善方案已产出，待用户确认起手）

- **本轮**：只做调查 + 方案，**未改生产代码、未发版、未合 `rele`**。
  用户要「附件功能如何完善」，确认「先出可落地方案 + Bitwarden/KDBX 均衡」。
- **★ 关键技术发现（推翻旧结论）**：kotpass **0.13.0 已有公开 binary API**
  （`db.binaries` / `modifyBinaries{}` / `BinaryData.getContent()`，Maven 最新版即 0.13.0），
  KDBX 附件**无需升版、无需反射**即可读写；`KdbxItemMapper` 的「只能反射」注释已过时。
  Bitwarden 上传协议清晰（`/attachment/v2` + fileUploadType 0=Azure/1=Direct），bastion 有参考。
- **方案真源**：[`PLAN-附件完善-2026-11-27.md`](./PLAN-附件完善-2026-11-27.md)，
  P0 Bitwarden 上传/删除 → P1 KDBX 导出 → P2 KDBX 写入+安全收尾；含文件级清单、门禁、风险、验收。
  过程见 [`SESSION-2026-11-27.md`](./SESSION-2026-11-27.md)。待用户确认后从 P0 起手。

## 上一轮（2026-11-26 · v0.13.11 正式发布：#305/#306/#308 随版）

- **发布结论（真实 run）**：用户 `发一个release` + 确认 `0.13.11 并合 rele`。`VERSION`
  `0.13.10 → 0.13.11`，手写 `Docs/release-notes/v0.13.11.md`。因无 gh token / helper，
  采用等价纯 git：本地 `rele-release` 合并 `origin/main`（`--no-ff`，历史 PR 同款合并提交）
  推 `origin rele-release:rele`。`Android Stable Release` run `45746632447` **success**
  （6m43s，仓库固定密钥签名），tag `v0.13.11` 与 `rele` 同指 `322e45a`，Release **Latest**。
- **产物**：`Vaultix-v0.13.11.apk`（6.7 MB，混淆）、`checksums-sha256.txt`；APK SHA-256
  `880f07e4…c60f1`，签名指纹 `7e6c8c07…1fd8`（与 v0.13.10 一致可覆盖安装），下载 HTTP 200。
  发布说明手写版被正确采用。发布内容 = #305 指纹 KEK 显式生物失效 + #306 夸克名单 +
  #307 诊断（仅元数据）+ #308 填充组装切主线程（真机 Edge 已验警告消失、填充照常）。
- **只推 rele 触发发布，文档回填仍只在 main**（不再推 rele ⇒ 不二次发版）。

## 上一轮（2026-11-26 · #308 填充组装切主线程，只推 main）

- **起因**：#307 的 Edge 全链路日志夹 `AutofillValue: forText() not called on main thread`
  （DefaultDispatcher-worker）。功能未坏，但 framework 契约要求 Parcelable 组装在主线程，
  OEM 差异下有偶发填充 UI 异常风险。
- **修法**：`AutofillDatasets` 加 `IconSource`（FromUrl / Ready / None，参数守 detekt ≤8）；
  `AutofillDatasetFactory.prepareIcon()` 在后台预生成图标（Coil 磁盘 I/O 不进主线程）；
  `VaultixAutofillService` 正常 / 锁定 / 无匹配三条分支的 dataset 与 FillResponse 组装
  全部包进 `withContext(Dispatchers.Main)`。匹配、认证意图、SaveInfo、TOTP 票据一字未动。
- **门禁**：新增 `check_autofill_main_thread.py`（剥注释，3 判据 + 4 变异自测），
  本轮补接 `ci-debug.yml` 两步——写完门禁却漏接 CI 等于假绿（与 #305/#306 同训）。
- **验证**：提交 `91b518ea`，Android CI `45741019593`（4m47s，两 job + detekt + 新门禁两步全绿）、
  docs `45741019603` / `45741114251` 全绿；preview `0.13.10-dev-91b518e` /
  `Vaultix-91b518e.apk`（下载链接 HTTP 200 已核实）。
- **真机已结案（11-26 10:55）**：用户装 preview 用 Edge 填 GitHub，日志
  `vaultix_logs_20261126_105544.txt`——`fillResponse datasets=1` 填充照常、
  全文零 `forText() not called on main thread` 警告（旧包 `2476b17` 同场景有两条）。
  **先发的 `095544` 附件是旧包日志（版本 `2476b17`、导出早于 preview 构建 23min），发错附件非修复无效**——
  延续 #307 教训：先验证据有效性（版本 / 时间）再读内容。
- 详见 [`SESSION-2026-11-26.md`](./SESSION-2026-11-26.md) 与
  [`issues/02-自动填充-part3.md`](./issues/02-自动填充-part3.md) #308。

---

## 上一轮（2026-11-26 · #307 结案——夸克 UC 内核限制，非缺陷，只推 main）

- **结案**：夸克浏览器无法填充 = 其 UC 自绘内核不向系统 AutofillService 上报可填字段 /
  不发起 `onFillRequest`。**用户实测 Bitwarden 在夸克上同样无法填充** ⇒ 行业标杆亦无解，
  属内核边界，**不改代码、不上无障碍兜底、不逆向内核**。
- 三条闭环证据：① Edge 同站点全链路通（`webDomain=github.com`、`webView=true`、
  `targets=2 matched=1 datasets=1`、passkey 完整）；② 夸克登录页零 `VaultixAutofill:` 行、
  `onFillRequest` 未触发；③ Bitwarden 对照失败。
- 曲折教训：名单漏项 → 点错位置（被否认）→ 请求未到入口 → Bitwarden 对照定案。
  **竞品表现 / 用户操作无法从元数据反推，先问清 + 找对照组**；入口内诊断在入口不触发时全白加。
- 提交链 `0daf5f10`（detekt 红）→ `79848d20` → `c4c027a3`（formSignal）→ `f01e24ae`（误判）
  → `2476b17f`（结构诊断，CI `45736003514` 全绿，preview `Vaultix-2476b17.apk` 已核实）。
  结案仅文档，最后代码提交仍是 `2476b17f`。
- 该轮留下的 `AutofillValue` 主线程警告**已转 #308 修复**。
- 详见 [`SESSION-2026-11-25.md`](./SESSION-2026-11-25.md) 与
  [`issues/02-自动填充-part3.md`](./issues/02-自动填充-part3.md) #307（307-c 结案）。

---

## 上一轮（2026-11-24 · #306 夸克浏览器自动填充兼容名单，只推 main）

- 用户实测夸克不弹填充：两份名单都漏 `com.quark.browser` —— XML 缺 compatibility-package
  ⇒ webDomain 不上报；KNOWN_BROWSER_PACKAGES 缺包名 ⇒ 无域名降级也被拒 ⇒ 候选为空。
- 两处各加一行；新增剥注释门禁 `check_quark_autofill_compat.py` 接 CI 两步（4 变异自测）。
  TDD 旧实现红灯、补齐转绿，本地 27/27 门禁全绿。
- 提交 `7df7680f`：`Android CI (Debug)` `45641648112` 两 job 全 success（新门禁两步 CI 实跑），
  docs `45641647673` success；preview tag 指向本次提交；只推 `main`，不合 `rele`。
- 内核是否上报域名不受控，不 hook；最差保证"能弹能选"，精准匹配待真机。
- 详见 [`SESSION-2026-11-24.md`](./SESSION-2026-11-24.md) 与
  [`issues/02-自动填充-part3.md`](./issues/02-自动填充-part3.md) #306。

---

## 上一轮（2026-11-24 · #305 指纹 KEK 显式生物失效加固，只推 main）

- 借鉴 KeePassDX #2805：指纹门锁 KEK 显式 `.setInvalidatedByBiometricEnrollment(true)`，
  不再依赖可能被 OEM overlay 破坏的默认值；新增剥注释源码门禁接 CI（3 变异自测）。
- 本地 555 例逻辑测试 + 26/26 门禁全绿；门禁在旧实现红灯、加显式调用转绿。
- 提交 `67176e0c` 的 `Android CI (Debug)` `45384271666` 整次 success（新门禁两步在 CI 实跑），
  docs `45384271649` success；只推 `main`，不合 `rele`，待下次发版随行。
- 详见 [`SESSION-2026-11-24.md`](./SESSION-2026-11-24.md) 与
  [`issues/04-锁与解锁-part12.md`](./issues/04-锁与解锁-part12.md) #305。

---

## 上一轮（2026-11-24 · v0.13.10 正式发布：#304 安全修复随版）

- `VERSION` 0.13.9 → **0.13.10**；发布内容 = #304 两项 P0 安全修复（查看锁取消不再放行、PIN 熔断串行化 + 计数落盘）。
- 发布链：版本提交 `edefe4f6` → PR #26 合并 `main → rele`（合并提交 `46b46100`）→ `Android Stable Release`
  run `45303375086` success → tag `v0.13.10` 与 `rele` 同指 `46b46100`，Release 为 **Latest**。
- 资产：`Vaultix-v0.13.10.apk`（6,998,186 B，SHA-256 `0d6cfcbb…9631`）、`checksums-sha256.txt`；
  手写 `Docs/release-notes/v0.13.10.md` 被正确采用；签名指纹与 v0.13.9 一致可覆盖安装。
- 纯发布 + 文档，无生产代码改动；源码门禁仍以 `da1f51b4` 的 Android CI `45206052108` 为准。
- 详见 [`SESSION-2026-11-24.md`](./SESSION-2026-11-24.md)。发布后文档回填只推 `main`，不合 `rele`。

---

## 上一轮（2026-11-23 · #304 查看锁与 PIN 熔断修复）

- 自动查看锁取消生物识别后不再放行；PIN 验证与计数改为互斥，失败计数使用可确认落盘。
- 修改 PIN 增加提交中去重；计数保存失败只返回不可用，不删除门锁或库数据。
- 本地 repository 360 例、crypto 195 例与 25/25 静态门禁全绿；最终 `da1f51b4` 的
  Android CI `45206052108` 两 job 成功，preview 为 `v0.13.9-dev-da1f51b · Preview`。
- 详见 [`SESSION-2026-11-23.md`](./SESSION-2026-11-23.md) 与
  [`issues/04-锁与解锁-part11.md`](./issues/04-锁与解锁-part11.md) #304。只推 `main`，不合 `rele`。

---

## 上一轮（2026-11-22 · v0.13.9 正式发布：Keystore 暂时故障不再误删快速解锁状态）

- `VERSION` 0.13.8 → **0.13.9**；根因修复是 #303：指纹 / auto 解封区分暂时故障与永久失效，
  暂时故障只报失败并保留信封，不触发 rearm / degrade / 删除。
- 发布链：`865730a4`（版本 + 发布说明）→ `AI docs consistency` `44968485729` 成功 →
  PR #20 远程合并 `main → rele`（合并提交 `bdd11fb5`）→ `Android Stable Release`
  `44968854719` 成功 → tag `v0.13.9` 指向 `bdd11fb5`，Release 为 **Latest**。
- 资产：`Vaultix-v0.13.9.apk`（6,998,182 B，SHA-256 `f3a29de5…9bdeef8c76`）、
  `checksums-sha256.txt`；源码代码门禁仍以 `d9d6511f` 的 Android CI `44907156878` 为准。
- 详见 [`SESSION-2026-11-22.md`](./SESSION-2026-11-22.md) 与
  [`issues/04-锁与解锁-part10.md`](./issues/04-锁与解锁-part10.md) #303。发布后文档回填只推 `main`。

---

## 上一轮（2026-11-21 · #303 Keystore 修复落地）

- 指纹解封改为 `Opened / Unavailable / TemporarilyUnavailable`：暂时异常只报失败，不进入 rearm/degrade；
  永久不可解才沿用原有降级与清理。
- Never 档 auto 解封增加暂时故障态：仅密钥永久失效或信封损坏才删 `house_lock_auto`。
- `UnrecoverableKeyException` 按可重试处理；异常链里明确的 `KeyPermanentlyInvalidatedException` 仍判永久失效。
- 验证：`data/repository` **358 例全绿**，全部静态门禁通过；GitHub Actions `44907156878`
  两个 job 均成功（Debug APK 构建、完整单测、Sandbox 单测），docs `44907156930` 成功。
- preview：`v0.13.8-dev-d9d6511 · Preview` / `Vaultix-d9d6511.apk`（37,645,466 B）。
  详见 [`issues/04-锁与解锁-part10.md`](./issues/04-锁与解锁-part10.md) #303。只推 `main`，不合 `rele`。

---

## 更早（2026-11-18 · v0.13.8 正式发布：自动填充精准度 + 通行密钥 + 主题图标）

> **一句话**：用户「都没问题」验收后指令「合入 rele 发一个 release」。当日完成四条线：
> ① 自动填充误弹/包名专项（7 处 + 12 例）；② passkey `b64.` 前缀 + allow/exclude 统一字节比较；
> ③ 主题色 4 档 + 图标深海军蓝（方案④）；④ 填充面板折纸 V 品牌标识（方案 B）。全部进 v0.13.8。

- **自动填充**：默认值从「多给」改为 Bitwarden 的「少给」——无域名只对**已知浏览器**降级，
  原生 App 不列整库；包名三级兜底 + `file://` 精确例外；按焦点窗口出候选；IP 守卫 /
  严格模式闸 / 等价域归一基域。打分算法本身没动（本就接近 Bitwarden）。
- **passkey**：`b64.<base64url>`（Bitwarden SDK 新形态）全仓零处理 ⇒ 断言必失配；
  allow/exclude 改为同一 codec 按字节比较 + `type=public-key` 过滤。detekt 曾因循环内
  两个 `continue` 红（`LoopWithTooManyJumpStatements`），抽单出口解析函数后过。
- **UI**：图标方案④ `#1B2B63`/`#FFFFFF`/`#7690D4`（白/折 1.69→3.13，折/底 4.27，
  形态顶点零改动）；主题色 A 档 4 预设，默认档零重复定义，门禁改「默认档锁蓝区 + 可选档独立判据」，
  WCAG 底线不放。填充面板盾牌→折纸 V 单色剪影，三处统一 + 深色面板对比 1.23→9.99。
- **发版**：`VERSION` 0.13.7→0.13.8（不 bump 则 `tag -f` 覆盖已发布版）→ push main
  （Android CI `44312250988` + docs `51013` 全绿）→ GitHub PR #19 远程合 rele
  → Stable Release `44313348100` success → tag `v0.13.8` → `07ba1a9e`。
  `Vaultix-v0.13.8.apk` 6.7MB（混淆），SHA-256 `9bdc4484…087ac872`。
- **待真机**：用户对 v0.13.8 的六项验收（见主篇 §5）；桌面图标实物已确认「非常好看」。
  详见 [`SESSION-2026-11-18.md`](./SESSION-2026-11-18.md) /
  [`SESSION-2026-11-18-part2.md`](./SESSION-2026-11-18-part2.md)。

---

## 目录结构（索引 + 分篇，**按需只开一篇**）

| 路径 | 是什么 | 规模 |
|---|---|---|
| `README.md` | 本文件：接力入口（只承载**最新 + 上一轮**状态） | 172 行 / 15.3KB |
| `STATUS-ARCHIVE.md` 等 4 篇 + `archive/README-状态块-2026-11-15至17.md` | **状态归档**（倒序，新块在最上面）：`-`（11-09）· `-2`（11-06~11-08）· `-3`（11-04~11-05）· `-4`（11-01~11-03）。2026-11-09 二次拆分：单篇曾 481 行 / 37KB 越 8.9 §7.1 上限 | 各 8~11KB |
| `MEMORY.md` | 项目长期笔记（定位 / 架构 / 里程碑 / 技术栈硬约束 / 协议与来源 / 发布签名） | **23KB**（2026-11-01 从 109KB 瘦身：§9 历史状态已归档） |
| `MEMORY.md §8` | **索引**：写代码前的长期约定 → 正文在 ↓ | — |
| `conventions/` | 约定正文 11 篇：`8.1-自动填充` `8.2-锁与解锁` `8.3-M2KDBX` `8.4-UI·观感` `8.5-通行密钥` `8.6-工程质量` `8.7-环境` `8.8-M3Expressive-采纳范围与顺序` `8.9-文档分篇` **`8.10-架构与目录地图`** `8.11-七类不报错的错` | 各 2~24KB；**8.4 / 8.6 已拆 `-partN`**（主篇保留速查清单） |
| `ISSUES.md` | **索引**：坑的「分类 → 主篇索引」（最新 **#256**；#255 自动填充篇，#249 / #254 / #256 界面篇）。⚠️ 逐条摘要**与正文重复**，已按 8.9 §7.4 移出 —— 查某条：点分类 → 主篇「章节明细」 | **8.4KB**（原 84KB） |
| `issues/` | 坑的**正文**：7 个分类 = 7 个索引 + **41 个分篇**（`-partN`）。`## NN.` 锚点原样保留，代码注释里的 `#NN` 仍能 grep 到 | 每篇 ≤22KB |
| `decisions/` | **逻辑定稿**（用户拍板的方向，非根因、非实现）：`快速解锁房子化-两级钥匙层级-定稿.md`（**快速解锁现行真源**，2026-09-28 起修订前两篇）、`库选择与快速解锁-逻辑定稿.md`、`快速解锁能力级重构-定稿.md`、`设置页信息架构-定稿.md`、`通行密钥UV豁免-定稿.md`、`条目页-M3-视觉方案.md`（**P0 已实施，P1/P2 待做**） | — |
| `SESSION-YYYY-MM-DD.md` | 逐轮工作日志（append-only）。最新 11-18 分两篇：`SESSION-2026-11-18.md`（自动填充/passkey/发版）+ `-part2.md`（主题/图标）。**2026-09 的 17 篇已按时间归档**到 `archive/sessions-2026-09/`（不拆内容 —— 历史日志没人整读，拆开只会让文件数翻倍） | 23 篇 ≈350KB |
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
