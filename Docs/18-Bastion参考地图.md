# 18 · Bastion 参考地图

> 读者：Vaultix 上的接力 AI / 开发者。
> 目的：Bastion（2026-09-08 起冻结）不再演进，仅作为 **reference implementation（行为规格参考）** 服务 Vaultix 开发与对拍。
> 本文按里程碑/功能域标注：参考 Bastion 的哪些文件、提取什么行为、**别搬什么**。
> 最后更新：2026-09-08。

## 1. 背景与决策

| 决策 | 说明 |
|---|---|
| **Bastion 冻结** | 代码与 GitHub 仓库均不再改动；已有用户继续使用现网版本（Bastion 已发布至 v1.0.1601+） |
| **Vaultix = 唯一演进线** | 新功能、重构、修复全部在 Vaultix 进行 |
| **搬运规则** | 只搬三类资产：① 行为知识 ② 测试向量与保真矩阵 ③ 无依赖的核心算法。**不做文件级搬迁** |

理由要点：Bastion 主源码约 **664 文件 / 25.8 万行**、单模块 `:app`，与 Vaultix 13 模块结构不兼容；
其真正价值沉淀在行为细节与踩坑记录中，而非代码行数。文件级整搬 = 把纠缠原样搬回来。
决策记录见 `Docs/progress/decisions.md`。

## 2. 源仓库访问

| 项 | 值 |
|---|---|
| 远程仓库 | `github.com/Chaniug/bastion` |
| 本地 clone | `D:\Bastion\bastion`（只读参考；本机 `dl.google.com` 被 DNS 劫持无法构建 Bastion，**无需构建**） |
| **仓库内快照（推荐入口）** | **`reference/bastion/`**（本仓库内 vendored 快照 @`369ed56`：主源码 664 + 单测 155 + 仓库 docs + BastionDocs md + workflows 参考，≈13 MB，见该目录 README）。接力 AI **无需访问 D:\Bastion**，本图路径前缀均可用 `reference/bastion/` 解析 |
| 参考分支 | `dev`（本地 head `369ed56`，2026-09-06）。冻结后以**代码为最终事实**；Bastion 内部文档可能滞后于代码，先看其 `docs/README.md` 的时效声明 |
| 接力入口（Bastion 侧文档） | 仓库根 `docs/README.md`、`docs/架构与路线图.md`、`docs/bitwarden同步与密码库生态.md` |

路径前缀约定（下文均指 Bastion 仓库内相对路径，快照内结构一致）：

- `app/` = `Bastion/app/src/main/java/com/bastion/app/`
- `repo-docs/` = Bastion 仓库根 `docs/`

## 3. 别读 / 别搬（省时间清单）

| 资产 | 原因 |
|---|---|
| MDBX 引擎与规范（`mdbx/`） | 已移除的自研引擎历史（Bastion Phase A 决策），非现网行为依据 |
| BastionLocal / 私有本地库语义：`PasswordOwnership`、`Conflict`、`StorageTarget`、跨库 copy/move 全家桶 | Vaultix 明确无此模型（决策：不引入私有本地库） |
| WebDAV 私有整库备份格式（`utils/WebDavHelper.kt` 的备份/恢复部分） | Vaultix 不做自有云备份；KDBX 文件同步交给用户方案 |
| 文本守卫测试（GuardTest） | Bastion 单模块时代的产物；Vaultix 用常规单测 + Detekt 门禁 |
| Koin 装配、单模块内 SettingsManager 式强耦合模式 | 与 Hilt 模块化架构冲突 |
| KEEPASS 重构等历史计划文档（架构路线图 §7 引用项） | 多已归档/路径失效；以代码与实际存在的文档为准 |
| `desktop/`（Compose Multiplatform PC 端） | Vaultix 无桌面目标；仅未来做 KDBX↔OneDrive 内置同步（1.5）时再读 |

## 4. 参考索引（按里程碑）

> 用法：**提取行为 → 在 Vaultix 对应模块重写 → 对拍测试验收**；禁止整体复制。

### 4.1 横切：Bitwarden API / 同步行为（M1 已基本落地，用于核对与补漏）

| 主题 | 提取内容 | Bastion 参考位置 |
|---|---|---|
| 401 反应式刷新 | host→server 映射、`Authenticator` 同步回调 runBlocking 桥接、`priorResponse != null` 防死循环 | `app/bitwarden/api/BitwardenApiFactory.kt` |
| 同步编排与安全边界 | 空库保护、数据骤减 >50% 拦截、上传/下载解耦、revision-date 轻量预检、失败分类 | `app/bitwarden/service/BitwardenSyncService.kt`、`app/bitwarden/sync/BitwardenRepositorySync.kt`、`app/sync/`；`repo-docs/bitwarden同步与密码库生态.md` §2–§4 |
| 单条失败隔离 | `CipherSyncResult` sealed + 外层 try 双层保障 | `app/bitwarden/service/CipherSyncProcessor.kt` |
| passkey 历史迁移合并 | 仅替换 `login.fido2Credentials`、幂等标记、15s 超时保护 | `app/bitwarden/service/BitwardenHistoricalPasskeyMergeService.kt` |
| 离线缓存语义 | 本地密文快照、逐条降级 | `app/bitwarden/cache/BitwardenOfflineSecretCache.kt` |

> **已吸收（2026-09-08 研读回写，d689a37）**：登录态保持语义——Bastion `BitwardenRepository.refreshTokenDetailed` 的 `RefreshOutcome` 三分（仅 400/401=AuthInvalid 需重登；403/429/5xx/网络=Transient 保留凭据）与其 ApiFactory `shouldRetry`/403 注释（**403 不等于登录过期**），已在 Vaultix 落地为：请求预挂 Bearer + 到期前 60s 预刷新（accessTokenExpiresAt 语义）+ `refreshFailureKind` 三分 + sync 401 按 `refreshFailureOf` 归类。回归防护见 `.ai/ISSUES.md` §8。

### 4.2 领域模型与字段超集（M1 收尾必做：字段对拍矩阵，产物进 Docs/02）

| 主题 | 提取内容 | Bastion 参考位置 |
|---|---|---|
| canonical 字段全集 | 登录/安全笔记/卡/身份/自定义字段/TOTP 等两侧字段与 BW 密文结构；每类条目在 BW / KDBX 两侧的承载形态 | `app/data/PasswordEntry.kt`、`app/data/model/`（SshKey / Wifi / PasskeyBinding / CardWallet…）、`app/bitwarden/mapper/` |
| 保真矩阵素材 | KPH / 自定义字段命名规范、TOTP 双格式、WiFi kp2a 模板、SSH type5、fido2Credentials | `app/keepass/KeePassFieldRegistry.kt` |
| 附加语义 | 附件、密码历史、收藏、软删除回收站 | `app/attachments/`、`app/data/PasswordHistoryManager.kt` |

> 验收锚点：Docs/02 §3.4 强制往返测试（`Bitwarden → VaultItem → KDBX → VaultItem → Bitwarden`）。

### 4.3 M2 `data:kdbx`（Bastion 资产密度最高的一站，启动前通读）

| 主题 | 提取内容 | Bastion 参考位置 |
|---|---|---|
| KDBX 读写语义 | kotpass 用法坑（UUID / EntryValue / Ver4x.create）、4.1 写入、Argon2id 参数、附件/历史/回收站/密钥文件、外部编辑保真 | `app/utils/KeePassKdbxService.kt`（约 4 千行；**行为对照用，不整搬**） |
| 字段映射 | KPH 字段注册表、外来条目无标记时的 matchByKey 兜底 | `app/keepass/KeePassFieldRegistry.kt`、`app/utils/KeePassKdbxService.kt` |
| 本地镜像 / 保存策略 | kdbx 内容镜像与写回、群组树语义、远程源配置的取舍（OneDrive/WebDAV 属 Vaultix 1.5） | `app/data/LocalKeePassDatabase.kt`、`app/repository/KeePassWorkspaceRepository.kt`、`app/repository/KeePassCompatibilityBridge.kt` |

### 4.4 M3 Autofill / 平台集成

| 主题 | 提取内容 | Bastion 参考位置 |
|---|---|---|
| 自动填充 | 字段解析/匹配、请求方可信度、兼容白名单、保存流程、防误填 | `app/autofill_ng/`（parser / service / processor / core / data / ui）、`repo-docs/自动填充与浏览器兼容.md`、BastionDocs《自动填充与保底机制》 |
| 解锁/锁定 | 档位语义（0 立即 / N 分钟 / -1 从不）、keyguard 判定、快速解锁 | Vaultix 已吸收大部分（见 .ai MEMORY）；需要时回看 `app/security/lock/` |
| 剪贴板/防截屏 | "触发即忘" + 清空前校验 | `app/utils/ClipboardUtils.kt`（已借鉴并带溯源标注） |

### 4.5 后续功能域（1.x 按需参考）

| 主题 | 提取内容 | Bastion 参考位置 |
|---|---|---|
| TOTP / 独立验证器 | 存储双格式、otpauth 解析（GA 迁移导入为 Vaultix 新增） | `app/ui/totp/`、`app/keepass/KeePassFieldRegistry.kt`（TOTP 字段名） |
| Passkey | fido2Credentials 合并/删除语义、origin 校验（**注意 Bastion 自身 3 项决策未拍板**，Vaultix 需自行拍板） | `app/ui/passkey/`、`app/data/Passkey*.kt`、`repo-docs/passkey-origin校验-对齐bitwarden-方案-2026-09.md`、`repo-docs/passkey备份完整性-设计计划-2026-09.md` |
| 安全中心 | 告警判据口径与数据模型（Vaultix 原创为主，可对照判据） | `app/data/SecurityAnalysisData.kt` 等 |
| 导入导出 | Bitwarden JSON/CSV、KeePass XML 的字段保真与容错 | `app/bitwarden/import/`、`app/bitwarden/export/` |
| 附件 | Bitwarden Premium 附件下载（V1 只读）、KDBX 内层二进制 | `app/attachments/` |
| KDBX 文件同步（1.5） | OneDrive PKCE / Graph 上传会话经验 | `desktop/`（OneDrive* 系列）、BastionDocs Azure 注册指南 |

### 4.6 UI / 交互与性能参考

- **界面流程**：Bastion `app/ui/screens` 与 BastionDocs 仅作**交互参考**；视觉规范以 Vaultix Docs/07、Docs/08 为准。
- **Keyguard**：许可限制（源码仅个人使用授权），**禁止复用代码**，仅参考交互。
- **性能档案**：`repo-docs/性能排查报告-2026-09.md`、`性能优化待办-2026-09.md`、`省电与内存优化计划-2026-09.md`、`vault冷启动加载-修复记录.md`——已转成 Vaultix Docs/16 检查项后逐渐退出引用。

## 5. 测试与对拍流程（MUST）

1. 移植/新增功能前，先写**对拍清单**：列出 Bastion 对应行为的断言场景（来源：Bastion 单测约 680 条、`repo-docs/` 修复记录、守卫测试的"场景意图"）。
2. 在 Vaultix 实现（模块化 + Hilt + Detekt 门禁），用 Vaultix 单测固化行为。
3. 对拍验证：以 Bastion 真机记录（荣耀 BKQ-AN00 / API 37）与既有行为描述核对判据。
4. 文本守卫测试（GuardTest）**不迁移**；其"防止某类回归"的意图转为 Vaultix 行为用例命名与注释。

## 6. 溯源与合规（MUST）

- 复制任何代码/算法前：确认无依赖（或先剥离依赖）；文件头注明来源与用途，示例：

```kotlin
// Ported from Bastion (GPL-3.0, https://github.com/JoyinJoester/Bastion)
// Copyright 2025 JoyinJoester — 2026-09-08 移植，仅取 <xx> 语义，按 Vaultix 架构重写
```

- Vaultix 原创实现按既有惯例标注（先例：`core:crypto` 的 `AesGcm.kt`）。
- Keyguard：只参考交互，禁止代码。

## 7. 维护规则

1. 本图随里程碑**收敛**：某行功能 DONE 后，把"参考位置"降级为一句备注，防止地图本身膨胀成新负担。
2. Bastion 已冻结：其内部文档不再更新，**以 dev 代码为最终事实**，本图仅提供导航。
3. 大段研读 Bastion 后，先更新本图"得出什么结论"，便于接力 agent 复用，避免重复通读。
