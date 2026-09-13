# Vaultix 项目长期笔记

> **接力顺序（先读这四份，再动手）**：
> 本文件（约定 / 硬约束 / 决策）→ `.ai/ISSUES.md`（坑：现象-根因-解法，最新 #65）→
> `Docs/progress/next-steps.md`（待办，最新在顶部）→ `Docs/progress/current-status.md`（进度快照）
> → `.ai/SESSION-YYYY-MM-DD.md`（逐轮流水，需要细节才翻）。
>
> 最后更新：2026-09-13（第四十九轮交付后）。**本文件已压缩重写**：逐轮叙事折叠进
> 第 10 节「历史轮次索引」，只留「不知道就会写错、且错了不报错」的内容。

---

## 1. 产品定位（2026-09-07 用户拍板）
- **Bitwarden 优先的客户端**，对标 **Keyguard 路线**（区别于 Monica / Bastion 的「本地优先·聚合」）
- 支持 **2 种库**：Bitwarden 云端（主）+ **KDBX 本地**（次）
- 不采纳 Bastion 的「私有本地库」—— 用开放标准 KDBX 承担本地角色，避免用户数据锁定

## 2. 架构约定
- 领域模型以 **Bitwarden `Cipher` 为规范模型（canonical）**，KDBX 为**降级映射端**
- 保真度三级：**无损 / 约定承载**（落 `Vaultix.*` 自定义字段）/ **有损**
  - 强制往返测试：`Bitwarden → VaultItem → KDBX → VaultItem → Bitwarden`
  - 有损字段必须在 `VaultCapabilities` 登记 + UI 提示，**禁止静默丢弃**
- 对齐 Bitwarden **官方客户端实际行为**（非仅文档）：KDF salt 为 UTF-8 字符串；
  `encKeyValidation` 明文为 UUID（非 `"Bitwarden"`）；Vaultwarden 忽略 `sinceRevisionDate`
- **DTO 有字段 ≠ 数据不丢**：新增/核对字段必须逐处确认 `toDomain` 读、`toRequest` 写、
  `toUpdateRequest` 写，并补单测（本项目最贵教训之一）

## 3. 里程碑
| 期 | 内容 | 状态 |
|---|---|---|
| M0 | 基础骨架 + `core:crypto` | ✅ DONE |
| M1 | **Bitwarden 同步** | ✅ 基本收官（等 824c432 三项最终确认） |
| M2 | KDBX 引擎 + 集成 | 🔄 **阶段 A（只读）完成**；阶段 B（写回）未做 |
| M2-a | 自动填充服务（提前启动） | 🔄 主体已落地；继续按真机反馈打磨 |
| M3 | 平台集成（Autofill / 安全中心） | ⬜ autofill 已并入 M2-a；安全中心待做 |
| M4 / M5 | 发布准备 / 1.0 | ⬜ TODO |

## 4. 技术栈与硬约束
Gradle 9.5.1 / AGP 9.3.2 / Kotlin 2.4.10 / KSP 2.3.11 / Hilt 2.60.1 / compileSdk 37 / JDK 17

- **Hilt 与 Kotlin 强绑定**：Kotlin 2.4.x → Hilt **2.60.1**；AGP 9 要求 Hilt ≥ 2.59
- AGP 9 **禁止** apply `kotlin-android`（内置 Kotlin 冲突）
- version catalog 别名避免 `kotlin-` 前缀（撞内置 `kotlin {}` DSL）；KGP 用别名 `kgp`
- 类型安全项目访问器需 `enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")`
- 本机 SDK 在 `C:\AndroidSDK`，**无 android-36** ⇒ compileSdk **必须 ≥ 37**
- **daemon 堆各 4g**（`org.gradle.jvmargs` 与 `kotlin.daemon.jvmargs`），**禁止回退 2g**
  （含多 Compose 模块 + Bitwarden 全载荷映射，分析大型 composable 时易 OOM）
- **detekt `2.0.0-alpha.6`**（精确对齐 Kotlin 2.4.10/AGP 9.3/Gradle 9.5，1.23.x 不可用）
  - 阈值 = `Docs/16` 硬上限：`LongMethod ≤ 150` / `LargeClass ≤ 1200` / 参数 ≤ 8 / 单文件 ≤ 60 函数
  - **行长上限 120**，按 Kotlin `String.length`（UTF-16）算 —— CJK 按**字符**，别用字节数
  - **`ComplexCondition` 阈值 3**（比默认 4 严）
  - Compose `@Composable` PascalCase、命名参数 MagicNumber 已豁免
- **`offline` flavor 暂停参与构建**（2026-09-09 拍板）：CI 收敛为 `assembleFullDebug` /
  `lintFullDebug` / `assembleFullRelease`，本地门禁也只跑 full。flavor 定义与 `AppFlavor`
  分支代码保留在仓库，恢复点写在 workflow 注释里

## 5. 协议、代码来源与参考源
- Vaultix = **GPL-3.0**（2026-09-07 从 MIT 切换）；搬运文件**必须**带溯源声明
- **Bastion**（GPL-3.0，Copyright 2025 JoyinJoester）= 主要搬运源，双方均 GPL ⇒ 合法
- **Keyguard = "All Rights Reserved" / 仅个人使用授权** ⇒ **禁止复用代码**，仅可参考 UI 交互
- 参考源位置：
  | 项目 | 路径 | 说明 |
  |---|---|---|
  | Bastion | `reference/bastion/`（**仓内 vendored @369ed56**） | 只读参考，不参与构建/detekt |
  | Bitwarden Android | `D:\Vaultix-refs\bitwarden-android`（仓外，`--depth 1`） | **功能层真源** |
  | Keyguard | `D:\Vaultix-refs\keyguard-app`（仓外） | **仅 UI 交互参考**，含 `androidLibAutofill/` |
- **复用判断口诀**：搬「思想」和「无依赖的核」，**不搬**「缠成一团的业务实现」。
  可搬 ✅ 安全策略 / 算法 / 流程模式（空库保护、失败分类、KDF、401 刷新）；
  不可搬 ❌ 强耦合其模型与存储的巨型实现（`BitwardenSyncService.kt` 2594 行、
  `AddEditPasswordScreen` 3500 行）、UI 层（缠 SettingsManager）
- 对齐字段**优先查 Bitwarden 官方**（clients / SDK），不要只看 Bastion
- 遇协议层争议 → **先拉规范原文逐字比对**（用户原话：「应该是有标准的才对」）

## 6. 目标平台、发布与签名
- **Android 17 = API 37**：minSdk 26 / targetSdk 37 / compileSdk 37；只出 `arm64-v8a`
- **本地只小编译不出包，GitHub Actions 负责出包**：main → debug preview；
  `rele` 分支或 `v*` tag → 签名 release APK
- CI 脚本 `.github/scripts/ensure-android-sdk.sh` 必须装 `platforms;android-37`；
  runner 上目录名可能是 `android-37.0` ⇒ **校验用前缀匹配 `android-37*`**（保持 LF 换行）
- 签名：4 个 `SIGNING_*` Secret 已上传；jks 本体 `D:\vaultix-release.jks`（**项目外**）、
  alias `vaultix`；密码在 `D:\vaultix-signing-passwords.txt`（项目外）。
  ⚠️ **jks + 密码缺一即无法再发布更新**，需用户自行备份
- **jks 是 PKCS12 ⇒ 私钥密码 = store 密码**（`SIGNING_KEY_PASSWORD = STORE_PASSWORD`，不可拆开）
- GitHub Secrets **不会自动变环境变量** ⇒ workflow 必须用 step `env:` 显式注入
- **判定签名只信 CI 日志的 notice 行**：`##[notice]固定密钥解码并校验通过`（成功）/
  `##[warning]…一次性`（失败）。两个分支的 echo 都会打印，**不要信脚本回显**
- **CI 已修的 7 类坑（勿回退）**：① SDK 装 `android-37`；② 校验改前缀匹配；
  ③ 取 APK 改用 `find`（多 flavor 展开会 too many arguments）；④ workflow `paths` 必须含
  `.sh` / `.toml` / `.github/scripts/**`；⑤ 歧义任务改显式 variant（`lintFullDebug` /
  `testFullDebugUnitTest`）；⑥ lint 步骤加 `--no-configuration-cache`；
  ⑦ androidTest 补 Compose BOM。另：单测步骤必须**显式列出有真用例的模块**（如 `:core:crypto`）
- `ci-debug.yml` 的固定密钥解码**不按事件名 gating**（`event_name != 'pull_request'`），
  改按磁盘上是否有 `release.jks` 注入 —— 否则手动触发的包含一次性 debug key，
  **盖不上 preview 包**（用户只能卸载重装，数据 + Keystore 全丢，伪装成「指纹解锁被清除」）
- **CI 的 non-blocking 步骤**（`Run unit tests (non-blocking)`）失败**不会**让 run 变红，
  但会留 annotation ⇒ **沉默的债**，需定期巡检

## 7. 协作文件夹（均已入库，便于 AI 接力）

> ⚠️ **2026-09-13 起「大文件」统一改为「索引 + 分篇」**：入口保持稳定路径，正文按主题分篇、
> **按需只开一篇**（别全读 —— 全读会把真正需要的上下文挤掉）。仓库里 115 处
> 「见 `.ai/ISSUES.md` #NN」的引用仍然有效：先看索引的「编号 → 分篇」表，再跳分篇。

- `Docs/progress/`：`environment` / `current-status` / `next-steps`（**待办唯一真源**）/
  `decisions` / `main-shell-migration` / **`perf-plan.md`（性能专项）** / `audit/`
- `.ai/`：
  - `MEMORY.md` —— 本文件（接力起手式：定位/架构/约会速查索引入口）
  - **`conventions/`** —— §8「长期约定速查」的正文分篇（`8.1-自动填充` … `8.7-环境`）
  - `ISSUES.md` —— **索引**（编号 → 分篇）
  - **`issues/`** —— 坑的正文分篇（`01-构建与环境` … `07-数据与同步`，**含推翻链，接力必读**）
  - `SESSION-*.md` —— 会话日志（逐轮流水，append-only）
  - `README.md` —— 索引

---

## 8. 长期约定速查（写代码前必读）

> ⚠️ 2026-09-13 起本节的正文**按主题拆到 `conventions/` 分篇** —— **只读你这次要动的那一篇**，
> 不要全读（原本节约 250 行，全读会挤掉真正需要的上下文）。

| 分篇 | 主题 | 条目 |
|---|---|---|
| [8.1 自动填充](./conventions/8.1-自动填充.md) | 自动填充 | 16 |
| [8.2 锁与解锁](./conventions/8.2-锁与解锁.md) | 锁与解锁 | 6 |
| [8.3 M2KDBX](./conventions/8.3-M2KDBX.md) | M2KDBX | 12 |
| [8.4 UI·观感](./conventions/8.4-UI·观感.md) | UI·观感 | 12 |
| [8.5 通行密钥](./conventions/8.5-通行密钥.md) | 通行密钥 | 12 |
| [8.6 工程质量](./conventions/8.6-工程质量.md) | 工程质量 | 12 |
| [8.7 环境](./conventions/8.7-环境.md) | 环境 | 9 |

## 9. 当前状态与下一批（**第五十二轮**接力起手式）

> ⚠️ **逐轮历史不在这里维护** —— 与本文件早期做法不同：历史只保留一处，避免两处不同步。
> 最新待办 → [`Docs/progress/next-steps.md`](../Docs/progress/next-steps.md)（最新在顶部）·
> 逐轮流水 → `.ai/SESSION-YYYY-MM-DD.md` · 坑 → `.ai/ISSUES.md`（索引，正文在 `issues/`）·
> 性能专项 → [`Docs/progress/perf-plan.md`](../Docs/progress/perf-plan.md)。

**第五十二轮（已提交 `e903c4b` + `a8b09c1` + `2206329`，已推送 `main`）**

用户一次性报了 **13 项**体验问题，逐条修复并真机验收通过；本轮另完成两项结构改造：
**记忆分层**（`MEMORY.md` 18.3KB → 2.3KB 索引 + `chapters/` 分篇）与
**文档分篇**（`ISSUES.md` 139KB → 9KB 索引 + `issues/` 7 篇；本文件 §8 → `conventions/` 7 篇）。

- 交付清单与逐条验收状态：见 `Docs/progress/next-steps.md` 顶部的第五十二轮块。
- 新坑 **#81~#87**：预测式返回预览 / 认证结果被系统丢弃 / `Dataset` vs `FillResponse` /
  「空」有三态 / KEK 判据探测宽失败严 / 同形状叠放的接缝 / 转场只允许 `translationX`。
- **本轮最贵的两条教训**（已写进 §1 的纪律）：
  ①「手势返回时画面缩小」我**改了三次转场**都没中，因为**复现方式本身是错的**——
  用 `input keyevent 4`（按键返回）去复现**手势**返回的问题；
  ②「填充反复解锁、看不到条目」**白改一轮**，因为只抓了自己的日志 tag，
  看不到系统框架侧说「认证结果被丢弃」。
  ⇒ **先对齐复现路径、先连系统侧取实证，再动代码。**

**★ 下一批（按优先级）**

1. ⭐ **应用内 PIN 解锁**（用户连续三轮要求，一直欠着）。
   定位 = **「解锁便利」而非找回手段**：PIN 只用于解开 Keystore 里已包好的那份密钥
   （与指纹**同一条链**），**不参与派生库密钥**；需要设置页开启/修改/关闭 +
   解锁页数字键盘入口 + 主密码兜底。
2. ⭐ **性能专项**：`Docs/progress/perf-plan.md`。**P0 必须先换 CI 的 release 包建立基线** ——
   现有数字（PSS 200MB / Janky 9.2% / 冷启动 763ms）来自 **debug 包**，不能作为优化依据。
3. 待用户复测：验证码页冷启动的「假空态」是否已变成转圈（#84）。
4. 同类「假空态」尚存于 `ItemsScreen:310` 与卡包页；密码页因活跃库 id **同步**解析暂未复现。
5. `deliverPendingFill()` 拿不到有效暂存时是**静默** `finish()`，值得补一条"填充已失效"提示。
6. **密保问题：结论是不做** —— 它不是找回手段，只会给同一个库再加一把更弱的钥匙。

**门禁（本地真跑全绿才提交）**：全模块 `detekt` → `:app:compileFullDebugKotlin` →
`:app:assembleFullDebug` → 用发布密钥重签 → `adb install -r` → 真机验收。

## 10. 历史轮次索引（细节见 `.ai/SESSION-*.md` 与 `.ai/ISSUES.md`）

> 只留「一句话结论 + 提交号」，用于快速定位；**细节不要从本节推**。

### 2026-09-07 ~ 09-08（M0 / M1 建设）
| 轮次 | 结论 | commit |
|---|---|---|
| M0 骨架 | core:crypto；行覆盖 91.4% | — |
| Bitwarden 认证 | prelogin → MasterKey → masterPasswordHash → connect/token；盐 = `email.trim().lowercase()`；Kdf 0=PBKDF2 / 1=Argon2id（缺省 64/4）；refresh 用 `Mutex` 串行化 | — |
| 同步编排 | 推送 dirty → 预检 revision → 全量拉取 → 空库保护（**服务端 0 条但本地有数据必须阻止**，防不可逆清空）→ 落库；失败五分类 | — |
| P0 最小闭环 | 库列表 → 连接 → 解锁 → 条目列表 → 新建；`VaultEntity.id = 规范化服务器 URL`（同一服务器仅支持一个账号，已记录） | — |
| 打卡 1~5 | 详情/编辑/软删除 + `VaultixClipboard` + 自动锁定 + 2FA 登录 + 本地快速解锁 | — |
| 批 1 数据安全 | DTO 全载荷 + 合并更新 + type5 建模 + 类型守恒守卫 + 全量后 prune + flush 4xx 弃单 | `b0ad421` |
| 回收站视图 | 恢复 / 永久删除（本地先行 + 队列） | `1d0f299` |
| 登录失效修复 | 预挂 Bearer + 到期前 60s 预刷新 + 刷新失败三分（400/401 失效，其余可重试）+ 解锁路径 `registerServer` | `d689a37` |
| 同步触发收敛 | 移除进页/回前台自动拉取；自动同步 = flush；拉取 = 手动 | `824c432` |
| 对齐补齐 | `folder/favorite/reprompt/secureNote` 进领域模型（**DTO 有字段 ≠ 数据不丢**这条教训的出处） | `e6b05d6` / `af41c9e` |
| 身份全字段 | `VaultIdentity` 17 字段 + overlay 写回（**不改 overlay「可编辑」就是 UI 假象**） | — |
| linkedId | 官方是**分段编码**（登录 100 / 卡 300 / 身份 400），不是顺序编号 | — |
| 扫码 | **CameraX + ZXing**（不选 ML Kit：国内依赖 GMS 必踩坑） | — |

### 2026-09-09 ~ 09-10（M2-a 自动填充 / CP 集成）
| 轮次 | 结论 | commit |
|---|---|---|
| autofill 骨架 | `AssistStructureParser` + `BitwardenLikeAutofillMatcher` + `FillPlanner`；PSL 用完整 Mozilla 表 | `a06f037`/`f0a7cb4`/`ea78ee6`/`dffc490` |
| **CP 总根因** | manifest intent-filter action 误写 `android.credentials.CredentialProviderService`（**漏 `service.` 段**）⇒ 系统从未发现 Vaultix 是 Provider ⇒ 无启用项 / `credential_service` 恒空 / Edge 永不弹 | `f474654` |
| 认证回灌 | Activity 复用后台 task（`taskAffinity` 独立）+ `setResult` 改 `onResume` + 去 `NEW_TASK` + 去 `singleTask` | `dba5ae1`/`f474654`/`e52779e` |
| credentials 版本 | 1.3.0 → **1.6.0**（1.5.0 才引入「凭据选择二级 UI」，Chromium Android 14+ 依赖它） | — |
| 保存流程 | `onSaveRequest` + `AutofillSaveActivity` + `SaveInfo` | `a90e2d5` |
| 快捷入口 | 磁贴 / 手动填充 / 智能复制（**都不依赖无障碍**） | `00f4235` |
| 设置页状态卡 | 三态（未启用/需注意/正常）；`AutofillStatusChecker` 两步判定；**读不到按「已启用」** | `be8a5bf` |
| 只发 full | offline flavor 暂停参与构建 | `b64ccb6` |

### 2026-09-11（锁态模型重写 / CI 转绿）
| 轮次 | 结论 | commit |
|---|---|---|
| 锁态重写 | 按 Bitwarden 换 `VaultTimeout` sealed class + 定时器模型；**删掉自造的 `CredentialFlowGuard`**（`Long.MIN_VALUE` 溢出 ⇒ 自动锁定被永久抑制） | 第 32 轮 |
| CI 转绿 | detekt 门禁 + 修 3 处 `Int?` 编译错误（**被 detekt 掩盖**） | `24af692` |
| 搜索框 | 按 Bitwarden 重写（不进 LargeTopAppBar） | 第 33 轮 |

### 2026-09-12（通行密钥收官 / 观感批次 / KDBX）
| 轮次 | 结论 | commit |
|---|---|---|
| 启动死锁 | `remember { resolveStartDestination }` 固化首帧错误结论 + loading 无出口 ⇒ 永久转圈；补 `Splash`/`Onboarding` 态 | `22d7273` |
| 主界面骨架 | Bastion 式 Tab 容器 + `ActiveVaultStore.resolve()`；**多库是筛选维度而非导航层** | `7a99635` |
| passkey 二次 Base64 | `rawId`/`userHandle` 被二次解码（b64url ↔ 标准 Base64 失配） | `1d2d242` |
| clientDataJSON | **回退占位符**（推翻第三十轮，以 W3C 规范为准） | `aa5fa27` |
| rawId UUID 分支 | UUID 文本被误判成 base64 ⇒ 补 16 字节分支；**真机重登 GitHub 通过，正式结案** | `8fd64f5` |
| Fill Assist | 完整搬运（服务端规则 + 6h 缓存 + 独立开关） | `9fca5ab` |
| 误弹检测 | 完全对齐 Bitwarden（分类结果即证据） | `59b57e8` |
| Edge 账号框 P0 | 浏览器整棵 DOM 可填 + `node.text` 污染语义 ⇒ 节点准入闸 + 信号改 `formSignalOf` | `4cc4dc1` |
| 观感批次 | 卡片外框统一 / 分组 / 按住滑动删除 / 沉浸式顶栏 / 统一进度条 / 设置页对齐 | `310bb25`/`cb6cbad` |
| KDBX 引擎 | kotpass 读路径 + 映射 + 往返测试 10/10 | `879e7c1` |
| 锁/解锁 1b~4 | 查看层锁 / 退出数据库 / 解锁即回填 | `5682bc6` |
| 第 2/3/4 批 | 顶栏胶囊化 + 站点图标 + KDBX 集成 + codec | `e88bda2`/`93c814c`/`fcaf918` |

### 2026-09-13（第四十九轮 · 用户一次报 8 件事）
| 轮次 | 结论 | commit |
|---|---|---|
| P0 解锁判据 | 「**存在**锁定的库」≠「**当前**库锁定」⇒ 候选全灭 + **无限解锁环** | `bc09995` |
| 布局三症 | 让位写在外层容器 padding ⇒ 筛选条压首条 / 上下不沉浸 / 搜索黑横幅 + 返回退桌面 | `bc09995` |
| 滑删反馈 | 手势**早就接了**、只是没有可见反馈 ⇒ 用户以为没做 | `bc09995` |
| KDBX 入口 | 添加库入口挂在**不可达路由**后面 ⇒ 集成交付了却用不到 | `bc09995` |
| 观感 | 全局输入框圆角 / 类型彩色徽标 / 详情图标头部 / 验证码字体 / 填充下拉实底 | `bc09995` |
| 记忆归档 | ISSUES #66~#69 + 四份接力文件同步 | `678be0d` |

### 2026-09-13（第五十轮 · 睡前 5 bug + 1 美化）
| 轮次 | 结论 | commit |
|---|---|---|
| 切页闪 | cross-fade **进出不同步** ⇒ 中间帧双向半透明，背景漏光 25%（深色 = 闪光） | `23c3629` |
| 倒计时条 | 进度条未随滚动折叠；对齐 Bastion `lerp(44.dp, 0.dp, frac)` + `tween(200)` | `23c3629` |
| 卡片按钮 / 多选 | 主操作 = 点卡片，低频 = MoreVert 菜单，批量 = 长按选择（Bastion 规格） | `23c3629` |
| 填充下拉 | RemoteViews 有硬边界（阴影/水波纹/网络全不支持）⇒ 图标 App 侧合成 Bitmap | `23c3629` |
| 解锁慢 | `viewModelScope.launch` **默认主线程**，Keystore / 密钥派生必须显式切 IO + 预热 cipher | `23c3629` |
| 美化 | 表单分区图标化 + 条目行尾收藏星标 | `23c3629` |

### 2026-09-13（第五十一轮 · 用户复盘后 6 条反馈）
| 轮次 | 结论 | commit |
|---|---|---|
| 验证码页沉浸 | 让位又写回了**滚动容器外的 padding** ⇒ 内容永远画不到顶栏区域（**#67 同根因残留**） | `d062792` |
| 手势分层 | 长按只进选择、**红底与位移只由手指驱动**；删除底改**常驻** + alpha，去条件式增删节点 | `d062792` |
| 填充下拉 M3 | RemoteViews 只能对齐**尺寸与静态配色**；M3 `ListItem` 规格：56dp / 40dp icon container / 16dp / 16·14sp；`drawable-night/` 给深色底板 | `d062792` |
| 指纹图标 | 长文案按钮 → **64dp 大号指纹图标**（primary 色）夹在密码框与灰色解锁按钮之间 | `d062792` |
| 云同步图标 | `PendingOp` 取 `op IN ('CREATE','UPDATE')` 作判据（**判定集合必须 = 展示集合**）；Flow 自动翻转；KDBX 恒空 | `d062792` |
| 下拉空隙 | 指示器默认贴容器顶 ⇒ 与让位后的首条之间是纯背景；自定义 indicator 加 `padding(top = topInset)` | `d062792` |

> **踩坑全表**（现象 → 根因 → 解法，编号 #1~#80）：`.ai/ISSUES.md`。
> ⚠️ 其中 **#35 ↔ #43 ↔ #39 是一条「推翻链」**（passkey clientDataJSON 的错判与更正），
> 接力时必须先看懂，不要只看旧条目就动手。
