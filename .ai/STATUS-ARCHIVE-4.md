# 状态归档 · 四（2026-11-01 ~ 2026-11-03）

> 主入口：[`README.md`](./README.md) · 前一篇：[`STATUS-ARCHIVE-3.md`](./STATUS-ARCHIVE-3.md)
> **新块在最上面**（倒序）。

---

## 🕐 上一轮状态（**2026-11-03 深夜** · 三个闪退全灭 + KDBX 快速解锁补齐）

> **一句话**：用户报「点添加密码库就闪退」→ 查出**三个互不相关的崩溃**；
> 紧接着报「新加的 KDBX 库每次都要输主密码」→ 查出 **#222 只修了 Bitwarden 那一半**。
> 四件事一起收口，均已推送 main。
>
> | # | 现象 | 真正原因 |
> |---|---|---|
> | **239** | 进「添加账号」表单闪退 | `AbstractMethodError: CustomStyle.applyStyle` —— **依赖跨 minor**（markdown-renderer 0.46.0 把 foundation 顶到 1.12.1） |
> | **240** | 添加库 → 本地 KDBX 闪退 | `navigate(AddKdbxRoute)` **少一对括号**，`@Serializable` 生成的 `Companion` 被当成了路由 |
> | **238** | 切 OneDrive → 点登录闪退 | `OutlinedTextField` 测量期子节点已不在（切来源前未 `focusClear`） |
> | **241** | KDBX 库不进快速解锁 | `autoEnrollNewVault` 只有 Bitwarden 调用点；且签名写死 `{ _ -> null }` 在类型层面排除了 KDBX |
> | **242** | 打开 OneDrive KDBX **恒报冲突**、点覆盖后卡死 | 新接入库写 `PENDING_UPLOAD` 而**基线令牌留空** ⇒ `remoteChanged` 恒真；`resolveKdbxConflict` 无异常兜底 |
>
> **四条必须记住的判据**：
> ① **`AbstractMethodError` / `NoSuchMethodError` = 版本错配**，别读业务代码，
> 直接 `dependencyInsight`。**依赖对齐是「解析结果」的属性，不是「声明」的属性** ——
> 回退 `composeBom` 无效，因为 `markdown-renderer 0.46.0` 的传递依赖仍会顶上 1.12.1。
> ② **`data object` 用 `navigate(X)`，`data class` 必须 `navigate(X(..))`**。
> 漏括号能编译通过（`Companion` 也是 `Any`），运行期才炸。
> ③ **同一个 App 可以有多个不相关的闪退**，**每份日志都要独立读第一行**。
> ④ **能力加在 A 类对象上时，逐个路径数一遍 B 类对象**。#222 只盯 Bitwarden，
> 因为报障的人用的是 Bitwarden —— **报障渠道决定了视野**。
> ⑤ **「没有基线」≠「远端变了」**，但**「问不到」也 ≠「对齐了」**。#242：新接入先去
> stat 一次把远端当前版本记成基线；问不到才退回保守值。**"保守"不能用来给"不问就下结论"背书**。
> ⑥ **有后果的用户动作（覆盖/放弃/删除）必须有异常出口** —— 无兜底的 `launch`
> 把"崩了"呈现成"没反应"，比报错难查十倍。
>
> **#241 的产品决策（用户 2026-11-03 拍板）**：**静默纳入**，不弹确认框。
> 原话「指纹解锁和 PIN 码是 vaultix 最高优先级的」。
> ⇒ 新 KDBX 库添加成功后，用**刚输入的主密码**直接封房间信封。
> ⚠️ 实质是「允许在未逐库确认的情况下把主密码加密存盘」；三道守卫仍生效
> （房钥匙不在内存 / 一把门锁都没装 / 范围为空集）⇒ **没启用过快速解锁的人不会被悄悄开启**。
>
> **验证**：`:data:repository` 266 例 + `:app` 565 例 **0 失败**；detekt 全 10 模块通过；
> 9 个脚本门禁 + 文档门禁全绿。依赖实测 `foundation/ui/runtime = 1.11.4`，
> `org.jetbrains.compose` 残留 **0**。
> ⚠️ **仍需真机验**：① 添加库→本地 KDBX ② 进网盘表单 ③ 点 WebDAV 输入框→切 OneDrive→点登录
> ④ 加 OneDrive KDBX → 杀进程重开 → 指纹应能直接打开。
>
> **#242 的修法**：新增 `adoptRemoteBaseline`，建完行立刻判三选一（有令牌 ⇒ `IN_SYNC`；
> 无令牌且内容一致 ⇒ `IN_SYNC`；无令牌且内容不同/读不出 ⇒ `REMOTE_CHANGED`，**只读不推**）。
> ⚠️ 不能"为了不报冲突"一律写 `IN_SYNC` —— 从网盘添加时本机可能连缓存都没有，
> 拿别的字节去条件写就是**静默覆盖远端**。
>
> 详见 [`SESSION-2026-11-03.md`](./SESSION-2026-11-03.md) §十三～§十六；
> [`issues/01-构建与环境-part13.md`](./issues/01-构建与环境-part13.md) **#239**、
> [`issues/06-界面与交互-part10.md`](./issues/06-界面与交互-part10.md) **#238/#240**、
> [`issues/04-锁与解锁-part5.md`](./issues/04-锁与解锁-part5.md) **#241**、
> [`issues/07-数据与同步-part3.md`](./issues/07-数据与同步-part3.md) **#242**。

---

## 🕐 同日另一条线（**2026-11-03** · KDBX 与 OneDrive 加固第四批）

> **一句话**：用户点名「onedrive 同步 + kdbx 的填充 / 数据结构 / 增删改查 / 安全性」，
> 一轮修掉 **9 条（#228–#236）**，按形态分三组：**结局写反**（#228–#231）·
> **保障成了代价**（#232–#234，多下一次整库 / 多读一遍文件）· **少算 / 少走**（#235–#236）。
>
> **两条必须记住的取舍**：
> ① 内容指纹里**必须排除根分组 uuid** —— `Ver4x.create` 每次随机生成，算进去会让
> 「同一份内容建两次库」指纹不同 ⇒ 每次保存都判内容过期 ⇒ **用户一条都存不进去**；
> ② OneDrive 分片续传**永不跳跃** —— `nextExpectedRanges` 说超前一律**夹回**确认位重发，
> 否则文件中间永久缺一段，KDBX 错一个字节就整库打不开。
>
> **验证**：`:data:kdbx` 236 / `:data:repository` 258 / `:app` full 552、offline 518 例，0 失败；
> `detekt` + 13 个脚本门禁 + 文档门禁全绿。`:app` 的改动都跑过变异验证。
>
> **下一步**：① 真机验收四项（`.kdbx` 打开入口 / 文件名预填 / 「立即同步」入口 / 断网保存报冲突）；
> ② #237 数据库层（`updateSyncState` 三列全量 SET、无事务、域名匹配）仅审计未修。
>
> 详见 [`SESSION-2026-11-03.md`](./SESSION-2026-11-03.md) §九 与
> [`issues/05-KDBX本地库-part9.md`](./issues/05-KDBX本地库-part9.md)。

---

## 🕐 同日另一条线（**2026-11-03** · GitHub 安全门禁落地）

> **一句话**：Advanced Security 面板里与本仓库相关的 6 项落成文件并推上 main ——
> CodeQL（Kotlin 手动构建 + `actions` 语言扫 workflow 本身）、Dependabot（只升 minor/patch）、
> 依赖图提交、依赖审查、`SECURITY.md`、发布包的 artifact attestation。
>
> **必须记住的前置关系**：Dependabot alerts 读的是**依赖图**，而 Gradle 的依赖图
> GitHub 静态解析不出来（版本构建期才定）⇒ 没有 `dependency-submission.yml` 提交快照，
> alerts 与 `dependency-review` 都是**空转**。⚠️ 删掉它的后果不是「少一个 workflow」，
> 是「第 2 项整体失效」，而面板会安静地显示为「没有漏洞」。
> **而且更靠前还有一层**：仓库的 `Dependency graph` 设置首轮是**关闭**的
> （`Dependency Submission` 直接报 `The Dependency graph is disabled`）——
> 那是设置项，SSH 改不了。**2026-11-03 已人工打开并重跑，全绿。**
> 打开后依赖图 11 → 730 个包、alerts 0 → 55 条 —— 
> **「面板显示没有漏洞」在开关未开时＝没有数据，不是安全。**
>
> **首跑踩到的坑（已修）**：CodeQL 报「构建成功但提取到 0 个编译单元」的真正原因是
> **Gradle build cache 命中**、编译器根本没执行 ⇒ 必须 `--no-build-cache`。
> 判据：「Build 绿 + 提取为空」几乎必然是缓存，别往 `build-mode` 上想。
>
> **必须记住的口径**：CodeQL 对 Kotlin **不支持 `build-mode: none`** ⇒ 只能 manual；
> 且 Default setup 一旦开着就会与 `codeql.yml` 抢同一次分析（跑两遍、告警重复）。设置页保持关闭。
>
> **必须记住的读法**：55 条告警里**只有 3 个包真进 APK**，真正要修的只有 2 条
> （`msal` 带来的 `httpcore5` high / `opentelemetry-api` medium；`bcprov` 的 4 条 critical
> 打在构建期那份 1.79 上，应用锁的 1.85.2 不在告警区间）。
> 依赖图快照**不区分 scope**，55 条的 `manifest_path` 全是 `settings.gradle.kts` ——
> 要判「进不进包」只能回溯 SBOM 的 `DEPENDS_ON` 链到构建工具节点。
>
> **已完成**：CodeQL 14 条逐条看过代码后**12 条带理由 dismiss**（协议强制 5 / 固定目标与硬编码参数 4 /
> CodeQL 未识别 `BiometricPrompt` 3）—— 剩下 2 条 `insecure-local-key-gen` 是「从不」档的
> **设计取舍**，不是误报，等产品拍板（A 记录 / B 加引导 / C 硬件绑定，见 SESSION §11.1）。
> 顺手修掉一个真 bug：`--no-build-cache` 只关了一半，PR 上仍命中缓存 ⇒ `Analyze (Kotlin)` 红，
> **main 绿 / PR 红先怀疑环境差异**。PR #3 #4 已合。
>
> **依赖已收口（2026-11-03 上午）**：6 个 Dependabot PR 全部处理完 ——
> #2 androidx / #1 compose-bom / #5 kotlin 2.4.21 / #7 库版本 / #8 AGP 9.5.0 + Gradle 9.8.2 全绿合入，
> #6 因「库 + 工具链混在一个 PR」拆开后关闭。**告警 55 → 0**：45 条随升级消失，
> 10 条构建期传递依赖带理由 dismiss（来源链与复查时机见 SESSION §12.3）。
>
> **必须记住的判据**：① 合并前先 rebase 到含最新大版本的 main 再跑一次 CI ——
> 「各自绿 + 组合没验过」是这次唯一的真风险（Kotlin 2.4.21 × AGP 9.5 × Gradle 9.8）；
> ② 「升级上游」≠「顶传递依赖」：msal 8.6.0 的 POM 改没改决定用哪种手段，别凭直觉；
> ③ Dependabot 的 `other` 组**必然**混进 AGP / wrapper，下次还会这样。
>
> **下一步**：① 真机回归（OneDrive 登录 + 解锁链路，CI 绿证明不了运行时）；
> ② CodeQL 剩 2 条 `insecure-local-key-gen` 等产品拍板；③ 上一轮遗留 #222 真机验收。
>
> 详见 [`SESSION-2026-11-03.md`](./SESSION-2026-11-03.md) §十、§十一。

## 🕐 更早状态（2026-11-01 · #222 新增库自动纳入快速解锁）

> **一句话**：新增 Bitwarden 库不再需要「先关掉快速解锁、再重新关联所有」——
> `registerVaultRow` 收尾时若房钥匙在内存，就**零认证**为该库建房间信封（P0）；
> 横幅候选改成「任一未启用的已解锁库」（P1）；新库会重置「以后再说」（P2）。
>
> **必须记住的天花板**：冷启动后房钥匙不在内存时 P0 做不了（物理上没有钥匙可封），
> 此时由横幅兜底 —— 那条路径**不是可选项**，别在优化时把它当冗余删掉。
>
> 本轮 12 例新测试（7 + 5）0 失败；`:data:repository` / `:app` 增量编译、
> `gradle detekt` 全绿。详见 [`SESSION-2026-11-01.md`](./SESSION-2026-11-01.md) 与
> [`issues/04-锁与解锁.md`](./issues/04-锁与解锁.md) **#222**。
>
> **下一步**：① #222 真机验收（热路径自动关联 / 冷启动横幅）；
> ② 文档分篇后的链接抽检（本轮把 7 个 issues 篇拆成 41 个分篇）。

> 📚 **历史状态块（2026-09 ~ 2026-10-21，原第 15–303 行）**：
> [`archive/README-状态块-2026-09至10.md`](./archive/README-状态块-2026-09至10.md)
> —— **是历史，不要当现状读。**
