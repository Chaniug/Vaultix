# Bastion 项目文档 · 提交与重点 Bug 修复总结

> 维护约定：开发在 `dev` 分支，验证（GitHub Actions + 荣耀 Android 17 真机）通过后合并 `main`。
> 本文档为内部交接总览，对外用户文档请见 `BastionDocs/` 文档站（VitePress，含中/英/日/俄/越）。
> 最后整理：2026-08-27

---

## 1. 项目简介

**Bastion** 是一款开源、本地优先的密码管理器（Android，Kotlin/Compose），管理密码、2FA 验证码与加密便签，数据默认完全留在本地设备。

- 仓库：`https://github.com/Chaniug/bastion`
- 多后端架构：**KDBX**（本地 KeePass 文件）、**Bitwarden**（含自建 Vaultwarden 同步）、**BastionLocal**（纯本地库）。
- 桌面端：`desktop/`（Compose Multiplatform，独立构建，物理隔离于 `Bastion/` 安卓模块）。

## 2. 技术栈与运行环境

| 项 | 版本/说明 |
|---|---|
| 语言/UI | Kotlin 2.2 + Jetpack Compose（Material 3） |
| 构建 | AGP 9.1.1 / Gradle 9.3.1 |
| 目标平台 | `compileSdk`/`targetSdk` = Android 17（API 37），`minSdk` = 26 |
| 桌面端 | Kotlin Multiplatform + Compose for Desktop + SQLDelight |
| 真机验收 | 荣耀 MagicOS / Android 17 |
| CI | GitHub Actions：`Android CI debug`（lint + assembleDebug + 单测）、`CodeQL Advanced`（仅扫 `dev`）、`Desktop-Build`（桌面端，仅 `dev`） |

## 3. 分支与 CI 约定（重要）

- 所有改动先落 `dev`，CI 双绿（Android + CodeQL）后由维护者下令合 `main`。
- `main.yml` / `codeql.yml` 触发分支已收窄为仅 `[dev]`，合并 `main` 不再重复构建。
- `deploy-pages.yml`、`Desktop-Build.yml` 同样仅触发 `dev`。
- 推送注意：`gh-proxy.com` 镜像 + GitHub API 真实直连 IP；限流时优先用 `git ls-remote` / shields.io badge 绕开（user `26280126` 共享额度易耗尽）。

### 3.1 排错环境：直连不通 + 代理"假限流"（必读）

沙箱内 `api.github.com`、`github.com`、`dl.google.com` 均为**直连不通**（HTTP 000，非超时非 403），
只有 `gh-proxy.com` 可达。由此派生两个坑：

**坑 1：本地无法编译。** `dl.google.com` 被阻断 → 装不了 Android SDK → 无法在本地跑
`./gradlew :app:assembleDebug`。所有编译错误只能靠推到 `dev` 看 CI 反馈，
一轮往返约 8–10 分钟。**因此提交前务必逐项静态自检**（见 3.2），别把低级错误推上去浪费轮次。

**坑 2：gh-proxy 会缓存 403，表现为"假限流"。**
查 `/rate_limit` 明明 `remaining: 5000/5000`，实际请求却持续返回 403，
且报错里的 user ID 在 `26280126` / `108915192` / `10094017` 之间反复横跳——
这是代理池不同出口身份的配额状况被缓存后串在一起回吐。

解法：每个请求带随机 cache-buster 绕过代理缓存，实测首次即通：

```bash
curl -sL "https://gh-proxy.com/https://api.github.com/repos/Chaniug/bastion/actions/runs/33159066546?_cb=$RANDOM$RANDOM" \
  -H "Authorization: Bearer $GH_TOKEN" -H "Cache-Control: no-cache"
```

**取 CI 报错的最优通道是 annotations，不要下载日志。** 日志走
`blob.core.windows.net`，在该环境会被 DNS 劫持到保留段，拿不到数据；
而 annotation 走 checks API，稳定可读。`main.yml` 的
"Build Debug APK" 步骤会把 `e: ` 开头的编译错误逐条打成 annotation：

```bash
curl -sL "https://gh-proxy.com/https://api.github.com/repos/Chaniug/bastion/check-runs/<JOB_ID>/annotations?per_page=100&_cb=$RANDOM$RANDOM" \
  -H "Authorization: Bearer $GH_TOKEN" -H "Cache-Control: no-cache"
```

### 3.2 无法本地编译时的提交前自检清单

按顺序过一遍，能拦掉绝大多数编译错误：

1. **新增枚举值 / sealed 子类** → 全局搜该类型，检查所有 `when` 是否穷尽（无 `else` 的一定会挂）。
   实测踩过两次：`WebDavErrorKind` 加 3 个值挂了 `WebDavHelper.buildConnectionErrorMessage`；
   `KeePassErrorCode` 加 4 个值挂了 `ImportFormatDetection.keepassImportSuggestion`。
2. **新增函数 / 常量引用** → 逐个 `grep` 确认符号存在，别凭记忆写签名（优先看声明处的完整参数表）。
3. **第三方 API 的嵌套类型** → 确认是顶层类还是父接口的嵌套接口。
   实测踩过：MSAL 的 `RemoveAccountCallback` 是
   `IMultipleAccountPublicClientApplication.RemoveAccountCallback`，**不存在顶层类**，写顶层 import 会报
   Unresolved reference。
4. **空安全** → `takeIf { }` 的返回值是可空的，后面不能直接 `.ifBlank {}` / `.length`，
   需拆成非空变量与可空变量两个。
5. **UI 里赋值的状态变量** → 确认该 `var` 在当前文件确实存在。
   实测踩过：在 A 文件写了 `browserEntries = emptyList()`，但该变量只在同名的 B 文件声明。
6. **Kotlin 不可重入锁** → `Mutex` 不是 `ReentrantLock`，外层已 `withLock` 的函数不能再对内层的同名锁 `withLock`，会死锁。
   加锁点应放在公开入口，内部私有函数不加锁。
7. **XML** → `python3 -c "import xml.etree.ElementTree as ET; ET.parse('.../strings.xml')"` 验结构；
   并确认新增 string 的 name 与代码引用完全一致。

---

## 4. 提交历史归纳（按主题）

### 4.1 Bitwarden 同步（最长演进线，见 `bitwarden同步与密码库生态.md`）
- 性能基线：同步写库包单事务 + OkHttp 快失败（`3eb0187f`）。
- 增量尝试：`sinceRevisionDate` 增量同步 + 节流（`9aee0c97`）；后被证实 **Vaultwarden 忽略增量游标**，等于空转。
- 提速核心：**上传与全量下载解耦**（`10228d6` → 合 `ce539395`），新建条目走 `POST /ciphers` 轻量路径。
- 轻量预检：`GET /accounts/revision-date` 比对跳过全量 + 60s 防抖（`349daef7`）。
- 卡死根治（2026-08-27 大修复，合 `aff292c1`）：诊断日志补全 → 401 自动恢复 + 超时收紧 + 主界面错误提示 + 上传埋点 → **passkeyMerge 全量逐条 GET 根因修复**（`f647ba90`）。

### 4.2 Passkey
- 绑定型 passkey **合并进密码 cipher 的 `login.fido2Credentials`**（`d692acaa` 等，`1bef7fc9` 合 main）。
- 历史独立 `[Passkey]` cipher 同步时自动迁移合并（`f0fd1d03`）。
- 创建页"移动到分类"卡片点击失效修复（`870dc244`）。
- Passkey 登录/选择器 UI 与文案优化（`841cec65`）。

### 4.3 自动填充（见 `自动填充与浏览器兼容.md`）
- AutofillPicker UI 重构（MD3 选择器替代系统原生列表）。
- WebView 认证回灌半填充修复（Edge + GitHub 只填密码不填账号，`0472eaf1`）。
- Via 系统 WebView 密码框回填、京东搜索栏误弹、OTP 剪贴板等专项修复。
- 条目字段兼容性 P0–P2（monica 遗留绑定读取、Bitwarden/KeePass 应用绑定互通，`45dd526c`）。

### 4.4 架构与性能（见 `架构与路线图.md`）
- **Phase A**：移除 MDBX 自研引擎，落地 KDBX + Bitwarden + BastionLocal 三后端（`69c9f8b5`）。
- **Phase B**：代码治理、守卫测试脆弱性治理、PasswordViewModel 拆分（4162→3472 行）。
- **Phase C**：运行时性能优化（AutofillConfigCache 预加载、热路径 runBlocking 替换等）。
- **Phase D / targetSdk 37**：AGP 9 + Gradle 9 跨代升级，拉到 Android 17。
- PC 端 Phase 3（SQLDelight 持久化）、Phase 4（SyncScheduler 接线）已完成。

### 4.5 其他
- 清理冗余与过期设置、精简死代码（`eb89f35d`）。
- Bastion Plus 全链路移除（`安卓端BastionPlus全链路移除记录.md`）。
- WebDAV 原子条件写入（If-Match）+ 密钥文件内部副本与指纹（`bfa33298`）。
- CodeQL Action v3→v4 升级（`42fff881`）。

---

## 5. 重点 Bug 修复总结

| # | 现象（真机） | 根因 | 修复 | 关键提交 |
|---|---|---|---|---|
| 1 | 已登录点同步"很慢/感觉没同步"，退出重登正常 | 连接池复用被反代静默关闭的空闲连接，挂到 60s 超时；且 `passkeyMerge` 每次同步对 vault 全部条目逐条 `GET /ciphers/{id}` 检查 legacy 字段（216 条量级）卡死 | OkHttp 超时 60s→30s + 连接池 5min→2min + `pingInterval`；`passkeyMerge` 加 vault 级完成标记 + 15s 超时保护，跳过已处理 | `f647ba90` `9bdba9a8` |
| 2 | 同步中途令牌过期 → 整次失败、从头重试雪崩 | 仅按 `accessTokenExpiresAt` 预判刷新一次，无反应式 401 恢复 | 新增 OkHttp `Authenticator`：`refreshForHost` 按 host 刷新 token 并重试一次（防死循环） | `9bdba9a8`（类型在 `1907eba6` 修正） |
| 3 | 同步失败主 Vault 界面无任何提示 | `ShowError` 事件仅在 `SendScreen` 被消费，主界面静默 | `SimpleMainScreen` 用 `when` 消费 `ShowError` 弹 Toast | `9bdba9a8` |
| 4 | 新建条目"一直在同步中"传不上去 | 上传前强制整库下载；Vaultwarden 无增量，整库下载主导耗时 | 上传与下载解耦：新建条目走 `POST /ciphers` 轻量路径，全量仅作后台 reconcile | `10228d6` `ce539395` |
| 5 | 自建服务器每次同步都全量偏慢 | Vaultwarden 忽略 `sinceRevisionDate` | 客户端先 `GET /accounts/revision-date` 比对，一致则跳过整库 pull + 60s 防抖 | `349daef7` |
| 6 | Bitwarden 锁/解锁入口是无效操作 | `forceLock` 只清内存缓存，本地密文由 app 级 MDK 加密，与 Bitwarden 锁正交 | 移除无效锁/解锁 UI，统一依赖 Bastion app 锁 | `6b86427c` |
| 7 | 绑定型 passkey 同步后在服务器显示为独立 `[Passkey]` 条目 | 创建的是独立 login cipher，未塞进密码 cipher 的 `fido2Credentials` | 合并进密码 cipher（基于 GET baseline 去重合并，禁盲 PUT）+ 历史独立 cipher 自动迁移 | `d692acaa` `f0fd1d03` |
| 8 | Edge 登录 Discord 只填用户名、密码空白 | 跨字段填充顺序/目标角色判定问题 | 参照 Bitwarden 填充设计修正填充链 | `Edge浏览器Discord填充半填充Bug修复计划` |
| 9 | Via 系统 WebView 密码框填不进 | dataset 回填在系统 WebView 内核下不可靠 | 解析器保留密码候选 + 直填路径修正 | `Via系统WebView密码框回填不可靠修复计划` |
| 10 | OneDrive 登录 `invalid_request: redirect_uri` | Azure 门户 redirect URI 配置与代码不一致 | 修正 Azure 应用注册 / 回环地址（或自注册 Azure 应用） | `onedrive-login-bug-diagnosis` |
| 11 | 清空所有数据失效 + TOTP 双显示/消失 | K2 suspend lambda 跨文件解析缺陷 + 重复 TOTP 合并逻辑 | 修复 lambda 作用域、收敛 TOTP 去重 | `83de32e0` `d4003067` |
| 12 | **OneDrive 大库上传生成 `file 1.kdbx` 副本而非覆盖（数据丢失）** | `Json { ignoreUnknownKeys = true }` 缺 `encodeDefaults`，`UploadSessionRequestDto` 退化为 `{}`，`conflictBehavior=replace` 未发出，Graph 按默认 rename 处理 | 补 `encodeDefaults = true`（桌面端已是如此，勿改回） | `e0102bc0` |
| 13 | **OneDrive 无法切换/注销账户** | 完全没有注销路径；且单一缓存账户下 `Prompt.SELECT_ACCOUNT` 被 MSAL 优化为静默登录，点了"切换账户"仍是原账号 | 新增 `signOut()` 调 MSAL `removeAccount` + UI"退出登录"按钮；切换时改 `Prompt.LOGIN` 强制重新认证 | `e0102bc0` |
| 14 | **WebDAV 多设备首次上传互相静默覆盖** | 首次上传直接 `sardine.put` 无条件写 | 改条件写 `If-None-Match: *`（`WebDavWriteMode.CREATE_ONLY`），已存在则拒绝并提示重新同步 | `e0102bc0` |
| 15 | **本地改动永不上传，界面却显示同步成功** | `runCatchingObserved { stat() }.getOrDefault(FileSourceStat())` 把 token 过期/网络超时降级成全空对象，被误判为"远端无变化"而跳过上传 | 新增 `remoteStatOrNull()`，区分"远端不存在"（返回 null）与"读取失败"（抛 IOException） | `e0102bc0` |
| 16 | 连点同步 / 前后台并发导致互相覆盖 | `stat→read→merge→write` 非原子，两条链路交错 | 按 `databaseId` 加 `Mutex` 串行化（注意 `Mutex` 不可重入，锁只能加在公开入口） | `e0102bc0` |
| 17 | WebDAV 条件写被 412 拒绝 / 版本比对误报"远端已变化" | Nextcloud 等返回弱校验 ETag `W/"abc"`，直接用作 `If-Match` 值或字符串比对 | 新增 `normalizeEtag()` 剥离 `W/` 前缀与引号，`stat()` 与 `matchesExpectedVersion` 统一走归一化 | `e0102bc0` |
| 18 | 多 MB kdbx 上传总超时，但小文件备份正常 | `WebDavGateway` 的 `callTimeout=15s` 覆盖整通调用（含请求体上传） | 新增 `buildBulkClient()`：write 120s / call 300s，kdbx 同步专用 | `e0102bc0` |
| 19 | 服务器返回超大 `Retry-After` 导致同步永久卡死 | 盲目信任服务端值，且该状态持久化跨进程生效 | `MAX_RETRY_AFTER_MS = 5min` 截断 | `e0102bc0` |
| 20 | KDBX 打开失败只提示"格式不支持或文件已损坏" | 6 类 `FormatError` 塌缩为同一提示，用户无法自助处理 | 拆为版本过新 / 非 KDBX / 文件损坏三类独立提示；新增 Challenge-Response（YubiKey）判定并置于"密码错误"之前（否则被误报为密码错误） | `e0102bc0` |
| 21 | WebDAV 507/423/409/412 被归为 Unknown | `classifySardine` 未覆盖这些状态码 | 补 `InsufficientStorage` / `Locked` / `PreconditionFailed` 分类及中文提示 | `e0102bc0` |

---

## 6. 当前状态与未决项

- **已稳定**：Bitwarden 同步卡死系列（#1–#5）已随 `aff292c1` 合 main，CI 在 `dev` 双绿。
- **已合 main（2026-08-28）**：#12–#21 三模块同步修复 + 导航闪烁修复 + README/Pages 视觉文案重构，
  合并提交 `6142d911`。合并前 `merge-tree` 预演零冲突，合并后 `git diff origin/dev HEAD` 为空，
  main 与 dev 内容完全一致。真机（荣耀 / Android 17）已验证同步与导航无异常。
- **CI 覆盖说明**：`main.yml`、`codeql.yml`、`deploy-pages.yml` 的 `push` 触发器**只监听 `dev`**，
  `Android-Release.yml` 只监听 `v*` tag。因此推送 `main` 不会触发任何工作流；
  main 分支的绿灯状态需以合并前 dev 上的最后一次 run 为准。
- **已知边界**：passkey 解绑场景不会自动从密码 cipher 移除旧 credential；多设备并发追加 passkey 极短覆盖窗口（已缓解）。
- **本轮遗留 / 未做**：
  - 未用真实 KDBX 3.1 / 4.0 / 4.1 样本做兼容性回归（沙箱装不了 SDK，只能靠 CI 编译验证）。
  - kotpass 版本升级评估未做（当前 0.10.0）。
  - OneDrive 分片上传重试未覆盖"服务端已接收但响应丢失"的幂等重传（当前重试会重发整片，依赖 Content-Range 幂等性）。
- **待续方向**：本地数据库统一同步架构重构（三条同步通路归一）、KEEPASS 独立客户端化、桌面端 Phase 5 三 Tab UI 完善。

## 7. 文档索引（本仓库仅保留以下 5 篇内部交接文档）

1. `项目文档-提交与重点Bug修复总结.md`（本篇）
2. `架构与路线图.md`
3. `bitwarden同步与密码库生态.md`
4. `自动填充与浏览器兼容.md`
5. `android端优化与杂项.md`

> 其余历史交接笔记（原 `docs/` 50 篇 + `Bastion/docs/` 38 篇）已合并精简进上述 5 篇；对外文档以 `BastionDocs/` 为准。
