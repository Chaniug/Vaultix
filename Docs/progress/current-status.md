# 当前进度快照

> 最后更新：2026-09-08（第十一轮 · M1 代码侧收官 + 真机回归两轮基本通过）

## 里程碑进度

| 期 | 内容 | 状态 |
|---|---|---|
| M0 | 基础骨架 + `core:crypto` | ✅ DONE |
| M1 | **Bitwarden 同步** | ✅ 功能与回归基本收官（d689a37 登录失效修复包与 824c432 同步策略包均已真机实测基本正常；等用户对 824c432 三项最终确认后正式收口） |
| M2 | KDBX 引擎 | ⬜ TODO（启动前通读 Docs/18 §4.3 + reference/bastion 快照 KDBX 资产） |
| M3 | 平台集成（Autofill / 安全中心） | ⬜ TODO |
| M4 / M5 | 发布准备 / 1.0 | ⬜ TODO |

## M1 收官内容（2026-09-08 全部推送）

- **对齐审计**（`Docs/progress/audit/bitwarden-alignment.md`）：vs Bastion reference 差距表 M1-1..7 / M2-1..3 / 不做
- **批 1 数据安全**（b0ad421）：DTO 全载荷 + 合并更新（编辑不再丢 uri/totp/卡/SSH 载荷）+ type5 建模 + 类型守恒守卫 + 全量后 prune + flush 4xx 弃单
- **回收站视图**（1d0f299）：恢复 / 永久删除（本地先行 + RESTORE/DELETE 队列）
- **登录失效修复**（d689a37）：预挂 Bearer + 到期前 60s 预刷新 + 刷新失败三分（400/401 才判失效）+ 解锁路径 registerServer
- **移除库入口**（d689a37）：⋮ 菜单 + 二次确认 → 本地全清（会话/快速解锁/凭据/队列/级联行）
- **周期同步 M1 判推迟 → P2**（a300189）：进程被杀无解锁会话可同步，收益≈0；PERIODIC 已预留
- **同步触发策略收敛**（824c432）：移除进页/回前台自动拉取；自动同步 = 本地修改 flush；拉取 = 手动（顶栏 + 下拉刷新）
- 门禁每批全绿：full flavor + Hilt + 各模块单测 + detekt（offline flavor 自 2026-09-09 起
  暂停参与构建，见 decisions「只构建/发布 full」）

## 真机回归记录（2026-09-08）

- **d689a37**（登录失效修复）：logcat 全程无崩溃/无 ANR；用户实测**基本正常**（重启+快速解锁不再报失效）
- **824c432**（同步策略）：用户已装包复测；三项确认待回（进页不自动同步 / 下拉手动同步正常 / 保存即推不受影响）

## 模块状态

| 模块 | 状态 | 说明 |
|---|---|---|
| `app` | ✅ 可用 | 库列表 / 连接 / 解锁 / 条目列表 / 详情 + 回收站 + 移除库 + 自动锁 + 快速解锁 + 设置页 + 类型徽标/强度条/同步状态条 |
| `core:common` | ✅ | safeCall、PasswordStrength 评分卡（Bastion 移植） |
| `core:model` | ✅ | VaultItem（Login/SecureNote/Card/Identity/SshKey）/ VaultKind / VaultSummary |
| `core:crypto` | ✅ | 行覆盖 91.4% |
| `core:database` | ✅ | Room v2；ciphers（整包密文）/ folders / pending_ops / 回收站流查询 |
| `core:datastore` | ✅ | DataStore 设置 + Keystore 凭据（含 local_unlock_key） |
| `core:ui` | ✅ | VaultixTheme |
| `data:bitwarden` | ✅ | 同步/认证/2FA/合并更新/预挂 Bearer+预刷新/刷新三分（400/401=失效，其余可重试） |
| `data:repository` | ✅ | VaultRepositoryImpl / ItemRepositoryImpl + VaultSessionManager + BitwardenSyncOrchestrator；单测覆盖会话/写路径/回收站/移除库/编排器 |
| `domain` | ✅ | VaultRepository / ItemRepository（observe/CRUD/回收站/移除库）+ SyncTrigger/VaultSyncStatus/VaultSaveOutcome |
| `data:kdbx` | ⬜ | M2 |
| `feature/*` | ⬜ | 暂不拆分（按包名组织） |

## 质量指标

| 指标 | 当前值 | 目标 | 状态 |
|---|---|---|---|
| `core:crypto` 行覆盖 | 91.4% | ≥ 80% | ✅ |
| Detekt（全模块 main+test） | 0 违规 | 0 | ✅ |
| 构建 | full flavor 编译 + Hilt 通过（offline 暂停构建，2026-09-09） | 通过 | ✅ |
| 单测 | core:crypto 172；data:repository（会话/写路径/回收站/移除库/编排器）；data:bitwarden（2FA 解析/prelogin/per-item key/载荷保真/刷新分类）；app（AutoLockPolicy）全绿 | 全绿 | ✅ |

## 待办（真机回归通过后 M1 收口）

- [ ] 用户对 824c432 三项最终确认（进页不自动同步 / 下拉手动同步 / 保存即推）
- [ ] P1 质量基础设施：Baseline Profile、ViewModel 单测、Gradle 配置缓存
- [ ] P2：WorkManager 周期同步（决策已记录推迟理由）
- [ ] M2：`data:kdbx`（Docs/18 §4.3 通读 → 字段对拍矩阵进 Docs/02）

## 参考资产（2026-09-08）

- Bastion 冻结为 reference；`Docs/18-Bastion参考地图.md` 分里程碑参考索引 + 别搬清单 + 对拍流程
- `reference/bastion/`：仓库内 vendored 快照（@369ed56，1012 文件 ≈13 MB，只读不参与构建）
- 对齐审计报告：`Docs/progress/audit/bitwarden-alignment.md`

## 当前状态（2026-09-08 晚，f0f5df6）

- **M1 字段对齐已闭合**：官方「添加登录」界面的全部字段（名称/文件夹/收藏/用户名/
  密码/验证器密钥/网址/备注/主密码二次验证/自定义字段 4 类型）均可编辑并按
  「表单意图」正确往返服务端；新建条目可选全部五种类型
- **Bastion 对齐第一批落地**（新策略：分批搬代码与 UI，保持 Vaultix 架构）：
  随机密码生成 + 表单滚动修复；批次②验证码五类型 / ③回收站自动清理 / ④设置 /
  ⑤通行密钥 / ⑥卡包 待做（清单见 next-steps.md「Bastion 对齐批次」）
- 修复两个真机 bug：验证器「取消=删除」（文案/动作错位）、表单内容超高被裁剪
- 单测：core:crypto 172 / core:common 23 / core:model 5 / app 20 /
  data:repository 14 / data:bitwarden 28，全绿；全模块 detekt 0 违规
- **待办 P0**：用户真机回归（f0f5df6 preview）→ M1 close-out
- 等待 CI 的包版本以 `git rev-list` 短 hash 标注在 versionName（0.1.0-dev-xxxxxxx）

## 当前状态（2026-09-09 晚）

- **新一批 autofill 修复已推**（a90e2d5 / 9e0ee12 / dc7ac19）：保存流程 onSaveRequest、
  磁贴/App 列表修复、TOTP 链路补全、通行密钥字段保真、填充中转 Activity 不再渲染 UI
- **回收站自动清理完成**（第十七轮）：autoDeleteDays DataStore 设置（默认 30）+
  TrashCleanupPolicy 纯函数 + 到期清理入队；observeTrash 改携 deletedDate 的 TrashEntry
- **★ 最高优先待办：Credential Provider 集成**（第十八轮真机诊断定案）——
  Edge 密码填充、登录时通行密钥显示，同缺 `CredentialProviderService` 注册；
  叠加解锁链改造（点候选不跳 MainActivity）+ inline + 字段角色推断。
  明细见 next-steps.md「★最高优先」段；诊断全程见 .ai/SESSION-2026-09-09.md 第十八轮
- 单元测试基线：全模块绿；detekt 0 违规（本轮诊断无代码改动，基线未动）
