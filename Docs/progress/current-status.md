# 当前进度快照

> 最后更新：**2026-10-31**（**已发版 v0.10.4** —— #214 两层锁 + #215 一次指纹够所有库；Release 说明改为「人工版 + 回退」双路；更早：双重取反 #213；命中框 #212；锁语义澄清 #210）—— **现状请看文末「当前状态（2026-10-31）」，下方更早的正文只作过程留痕。**
>
> 上一版头注（2026-09-20，第三轮：胶囊阴影掉帧 + 强调色语义 + README/文档对齐）：
>
> 本轮提交：`475ac2a`（胶囊阴影改恒定值）· `738d003`（强调色语义 + 首字母头像上色 +
> README 重写 + 跨 16 文件更正身份字段数 17→18 + `Docs/07` 主题章节对齐现状）。
> **门禁：4 本地脚本 OK / detekt 0 / 双变体编译 ✅ / 单测 928 项 0 失败 0 错误**
> 根因与教训见 `.ai/SESSION-2026-09-20.md` **§6**。
>
> 上轮（同日）：`f4c069f`（全屏编辑壳让位少算系统栏 insets）· `f000d0a`（上下栏随滚动淡出）
> · `dd3919a`（网盘账号搬进设置页）· `618609f`（通行密钥 UV 豁免接通断链），见 §1–§5。
>
> ---

## 里程碑进度

| 期 | 内容 | 状态 |
|---|---|---|
| M0 | 基础骨架 + `core:crypto` | ✅ DONE |
| M1 | **Bitwarden 同步** | ✅ 功能与回归基本收官（d689a37 登录失效修复包与 824c432 同步策略包均已真机实测基本正常；等用户对 824c432 三项最终确认后正式收口） |
| M2 | KDBX 引擎 + 集成 | ✅ **只读 + 写回均已通**：引擎（`879e7c1`）+ SAF 选文件/解锁/读路径分流/切库锁旧库 + 通行密钥·验证码 codec（`fcaf918`）；写入口曾加只读闸（`8fc1125`，坑 #106），**阶段 B（写回）现已落地**并带条件写 + 冲突检测。遗留：库内 SSH 字段映射未做 |
| M2-a | **自动填充服务**（提前于 KDBX 启动） | 🔄 骨架/解析/匹配/保存/CP 集成已落地（a06f037→f474654）；`5682bc6` 补「解锁即回填」；2026-09-17 填充下拉条目行收窄（`5017029`，窄面板不适用 360dp+ 的 M3 列表项规格） |
| M2-b | **网盘同步（OneDrive / WebDAV）** | ✅ **文件源与账号管理已落地**：MSAL 8.4.2 登录 + Graph 客户端 + WebDAV（含明文 HTTP 风险提示）；KDBX 可直接建于 OneDrive / WebDAV 并同步（条件写 + 冲突检测）。**网盘账号已从「添加库」搬进设置页的独立入口**（`dd3919a`，定稿 §13）；凭据写入收敛为 `CloudAccountConnector` 唯一入口（定稿 §11.7）。方案背景见 [`cloud-sync-plan.md`](./cloud-sync-plan.md) |
| M3 | 平台集成（Autofill / 安全中心） | ⬜ autofill 主体已并入 M2-a；安全中心待做 |
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
| ~~`core:ui`~~ | 🗑️ 已移除 | 2026-10-05 删除。模块内只有一个 `VaultixTheme`（蓝色系色板），**全仓零引用**，且与实际生效的 `app/.../ui/theme/Theme.kt` 同名不同色。主题色板现由 `app/.../ui/theme/VaultixBrandColor.kt` 单处提供 |
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

## 当前状态（2026-09-10）

- **★ Credential Provider 集成闭环（总根因 f474654）**：manifest `<service>` intent-filter
  action 误写 `android.credentials.CredentialProviderService`（漏 `service.` 段）→ 系统从未
  发现 Vaultix 是 Provider → 无启用项 / `credential_service` 恒空 / Edge 永不弹 / passkey
  查不到。已修为 `android.service.credentials.CredentialProviderService`。链上修复：
  CP 注册 + passkey 查询/创建 + 双能力（PUBLIC_KEY + PASSWORD）+ inline（98edb37 / 8ba40d9）；
  设置页「凭据提供商」状态行 + 直达启用界面（e92d215）；老路认证回灌 onCreate→onResume
  （f474654）；认证宿主独立 `taskAffinity`（dba5ae1）+ 去 NEW_TASK（e52779e）；
  MODE_UNLOCK 原地生物解锁（3c7c0b8）；搜索框乱弹抑制（b9a1d6e）
- **autofill 服务 M2-a 已落地**：骨架与清单注册（a06f037）、解析层 ParsedStructure +
  HintClassifier + AssistStructureParser（f0a7cb4）、Bitwarden 风格匹配器（ea78ee6）、
  Mozilla PSL ~10325 条（dffc490）、保存流程 onSaveRequest（a90e2d5）、快捷入口三件套
  （磁贴 / 手动填充 / 智能复制，00f4235）
- **CI**：只构建与发布 full 分发，offline flavor 暂停参与构建（b64ccb6）
- ⏳ **真机待验证（第一优先）**：① 设置页「凭据提供商」应弹启用界面；② 启用后 Edge/Chrome
  弹密码 + passkey（核心闭环）；③ Via 填充密码应能填进；④ 搜索框不应乱弹。
  完整清单见 `.ai/SESSION-2026-09-10.md`
- **遗留（next-steps ⑥⑦）**：provider.xml 补 `settingsActivity`（通行密钥专属管理页）；
  privileged allowlist（`CallingAppInfo.getOrigin()`）
- 质量基线：编译 + detekt + 单测全绿（本轮无回归）

## 当前状态（2026-09-12）

### ★ 通行密钥两轮 P0 修复（均已 CI 全绿）

| commit | 根因 | CI |
|---|---|---|
| `1d2d242` | **`rawId` / `userHandle` 被二次 Base64 解码** —— 注册侧写 b64url 文本落库，登录侧又 decode→re-encode ⇒ 标准 Base64（`+`/`/`）被规范化成 b64url（`-`/`_`）⇒ RP 逐字节比对失配 ⇒ 「能列出候选、能进登录、最后一步校验报错」 | ✅ `34679687783` |
| `aa5fa27` | **`clientDataJSON` 回传空占位符**（**推翻第三十轮的错判**）—— 官方 `set a placeholder value` 那句带前置条件 `If you retrieve an origin`（仅特权应用名单场景适用），而 W3C 规范 §7.1/§7.2 要求 RP 解析**明文**校验 `C.challenge` ⇒ 空数组连 JSON 解析都过不了 ⇒ GitHub 报 `Security key authentication failed` | ✅ `34686274203` |

**正确判据（勿再混淆）**：两条流程**都**回传自建真实 JSON；
唯一差别是**签名覆盖哪份哈希**（浏览器用系统给的 `clientDataHash`，原生流程用 `sha256(自建 JSON)`）；
浏览器流程**不写** `androidPackageName`。

### 其他本轮完成
- `22d7273` 修复**全新安装启动死锁**（`remember { startDestination }` 固化首帧错误结论 +
  loading 分支无出口 ⇒ 永久转圈）。
- `7a99635` **Bastion 主界面骨架 + 活跃库真源收敛**（迁移阶段 1/2）：
  新增 `MainShellScreen` / `ActiveVaultStore.resolve()`，7 处消费点只读活跃库；
  多库从「路由参数」降级为「Tab 内筛选维度」。
- **沙箱构建环境打通**：可真实跑 `detekt` / 单测 / `:app:compileFullDebugKotlin`
  （此前完全跑不了 Gradle）。细则见 `ISSUES.md` #44。

### 质量基线
`:core:common:testDebugUnitTest` 95 用例 0 失败 / `testFullDebugUnitTest` 129 用例 0 失败 /
`detekt` 全模块通过 / `:app:compileFullDebugKotlin` BUILD SUCCESSFUL。

### ⏳ 真机待验证（下一优先）
1. **GitHub 注册通行密钥**（此前报 `Security key authentication failed`）→ 应通过；
   再验证**登录**（`rawId` 修复的目标场景）。
2. `logcat` 断言：`jsonMatchesBrowserHash=false` 属**预期**（系统那份哈希来自浏览器），
   **不再是故障信号**。
3. 回归确认：密码自动填充 / 启动（全新安装不应再转圈）/ 设置页切换活跃库后填充目标跟随。

## 当前状态（2026-09-12 第四十三轮）

用户一次报四件事 + 要求搬 Bastion 阶段 3 观感，全部落在**同一提交**。详见 `next-steps.md` 顶部。

| # | 事项 | 根因（一句话） | 落地 |
|---|---|---|---|
| ① | **Edge 账号框不出候选也填不进去**（P0） | 浏览器 WebView 把整棵 DOM 建成可填节点（真机日志实测一页 221 个），且分类信号含 `node.text` ⇒ `<label>Password</label>` 等展示节点被判成 USERNAME 占住语义位 ⇒ 真账号框永远 UNKNOWN ⇒ Dataset 只有密码值 | 节点准入闸 `EditableNodePolicy` + 信号改 `formSignalOf`（去 `text`、补 `autocomplete`）；日志加 `seq=` 诊断 |
| ② | **填充下拉图标又大又花** | 上一轮把 40dp **彩色启动图标**塞进每一行 | 20dp 单色语义矢量（地球/卡片/人像/盾牌）+ `setColorFilter`，对齐上游 `autofill_remote_view.xml` |
| ③ | **覆盖安装 + 重启后指纹解锁不生效** | 三个独立缺陷：弹窗错误未复位 `submitting`（整页死锁）/ `UserNotAuthenticatedException` 误判为永久失效（自毁注册）/ 解密路径静默新建 KEK | 见 `ISSUES.md` #53；另修 CI 手动触发的包用一次性密钥签（盖不上 preview） |
| ④ | **填充辅助没有独立开关** | 搬运时只落机制没落设置项 | `fill_assist_enabled` 偏好 + 设置页开关（文案取上游官方中文） |
| ⑤ | **Bastion 阶段 3 观感** | — | Tab 转场（fadeIn+1/16 屏高上移）+ `SaveableStateHolder` 状态保留 + 验证码倒计时平滑动画 |

### 质量基线（本轮）
`:app:compileFullDebugKotlin` / `:app:testFullDebugUnitTest` / `:data:repository:testDebugUnitTest` /
全模块 `detekt` —— 本地真跑**全绿**（`BUILD SUCCESSFUL`）。

### ⏳ 真机待验证（本轮，按优先级）
1. **搜索框仍不乱弹**（本轮同时改了信号来源与节点准入闸 ⇒ 最大回归风险点）；
2. Edge 登录页点账号框 → 出候选、一键填账号 + 密码；
3. 填充下拉图标小而克制、类型图标正确；
4. 覆盖安装 + 重启后指纹解锁可用（且失败不再卡死整页）；
5. 设置 → 自动填充 → 填充行为出现「启用填充辅助」并即时生效；
6. Tab 切换有淡入上移过渡，切回不丢滚动位置与搜索词。

## 当前状态（2026-09-12 第四十八轮 · 四个提交）

用户要求按 `.ai/ISSUES.md` #60 执行 1b，再依次做完 1c/1d/2/3/4，并继续第 2、3、4 批。

| 提交 | 内容 |
|---|---|
| `5682bc6` | **锁/解锁规范化 1b~4**：主页锁按钮只锁查看层（不清密钥）/ 根导航判据加 viewLocked / 解锁页「仅认证」分支 / 超时真锁清标记 / 「立即锁定」→「退出数据库」/ **解锁即回填**（`PendingFillStore` + 共享 `AutofillCandidateSource`·`AutofillDatasetFactory`） |
| `e88bda2` | **第 2 批**：顶栏胶囊化（🔍 + ⋮＝验证码/回收站/显示选项/同步/锁定）+ 点库名展开/收起快捷筛选（验证码/通行密钥/SSH/笔记/收藏） |
| `93c814c` | **第 3 批**：站点图标 `<服务器>/icons/<域名>/icon.png`（`core:common/SiteIconUrl` 纯字符串 + Coil 缓存 + 失败回退首字母） |
| `fcaf918` | **第 4 批**：KDBX 集成（SAF 选文件 → 库行 → 解锁 → 读路径分流 → 切库即锁旧库）+ `KdbxTotpCodec` / `KdbxPasskeyCodec` 补全多种历史约定 |

### 本轮的三条关键取舍（详见 `.ai/ISSUES.md` #61/#62/#63）
1. **查看锁是界面门禁、不是加密门禁**：`viewLock` 只置内存标记、密钥原样留着，
   所以认证一次就回到原界面；真锁才清密钥（两条路径的成功分支必须分开写，
   合并成一条就会出现「认证完还得重登」）。
2. **KDBX 与 Bitwarden 是两套会话模型**：前者是整库明文（内存）、后者是一把对称密钥，
   所以 KDBX 不写 ciphers 表、不进 `VaultSessionManager`，靠 `KdbxSessionFlow`
   （只带代次计数）通知上层重读。
3. **站点图标 URL 会把域名拼进路径** ⇒ 域名必须白名单过滤（单测里有路径注入的反向用例）。

### 质量基线（本轮）
`:app:compileFullDebugKotlin` / `:app:testFullDebugUnitTest` / `:data:repository` /
`:data:kdbx` / `:core:common` 单测 —— 合计 **333 tests, 0 failures**；全模块 `detekt` 全绿。

### ⏳ 真机待验证（本轮，按优先级）
1. **主页锁按钮**：按一下应立刻回解锁页 → 指纹一次回到原界面（**不要求主密码、不联网**）；
   自动锁定设为「从不」时，按锁**仍应锁**（那是用户显式动作）。
2. **「退出数据库」**：设置页确认后库行保留、条目为空、重新登录能拉回全部条目。
3. **解锁即回填**：浏览器点 Vaultix 行 → 解锁一次 → 密码**自动填好**（不必回浏览器再点一次）。
4. **顶栏**：🔍 + ⋮ 两个动作；点库名展开筛选条、箭头翻转、筛选后标题显示「库名 · 筛选名」。
5. **站点图标**：密码列表与验证码列表出现真实站点图标；取不到的站点回退首字母（不是破图）。
6. **KDBX**：库列表「+」→ 打开 KDBX 文件 → 选 `.kdbx` + 主密码（有 keyfile 再加）→
   条目/文件夹/验证码/通行密钥都能读；切到别的库后回来应要求重新解锁（切库即锁旧库）。
7. 回归：Bitwarden 侧登录/同步/填充不受影响（读路径分流动了 `observeItems` 的入口）。

## 当前状态（2026-09-13 第四十九轮 · 用户一次报 8 件事）

用户原话含「通行秘钥部分**反复让人解锁**……**而且我本地已经是解锁状态了**……拒绝闭门造车」。
先派 4 个并行探查取事实，再动手。根因见 `.ai/ISSUES.md` **#66~#69**。

| # | 用户报告 | 根因（一句话） | 落地 |
|---|---|---|---|
| 1 | 通行密钥/凭据**反复让人解锁**，本地明明已解锁 | CP 用「**存在**锁定的库」（`count{!unlocked}>0`）冒充「**当前**库锁定」⇒ 多库恒真、候选全灭；叠加 `EXTRA_CREDENTIAL_FLOW` **只写不读** ⇒ 解锁完 `finish()` 无结果 ⇒ 判据未变 ⇒ **无限解锁环** | 删 `lockedCount` 分支；`AutofillActivity` 识别 CP 流程；passkey/密码回灌的锁态统一改问**仓储**（含 KDBX），不再把「字段都为空」当锁定 |
| 2 | 筛选条展开时挡住密码条目 | 筛选行浮在内容之上，但**列表没有为它让位** | 列表顶部留白改为 `contentPadding`，并按展开态叠加筛选行高（带动画） |
| 3 | 上方/下方都不沉浸 | 让位写成**外层容器 padding**（4 个 Tab 全这样）⇒ 内容永远画不到顶栏区域；底栏走 `Scaffold(bottomBar)` 同理 | 顶部让位改到 `contentPadding` / 可滚动 `Spacer`；底栏改**叠层悬浮** + 各页 `bottomInset` |
| 4 | 搜索开后有巨大黑横幅；手势返回直接退桌面 | 搜索态「Scaffold 已让位 + 外层再叠一次」= 双重留白；且**没有 `BackHandler`**（主界面是根路由） | 搜索态顶部留白归零 + 加 `SearchBackHandler`（密码页与验证码页） |
| 5 | 按住条目左滑删除（分组也要） | 手势**三个列表早就接了**（含分组路径），只是长按后只有 2% 缩放 ⇒ 用户看不出「已解锁」 | 补 armed 可见反馈：32% 红底 + 内容左移 16dp |
| 6 | 设置页密码库没有 KDBX 入口 | KDBX 添加入口只在库列表页「+」，而该路由在「已有 ≥1 个库」时**不可达** | `AddVaultTypeDialog` 提到 `ui/common`；设置页「密码库」加「添加密码库」，回调透传到 Tab 内 |
| 7 | 填充下拉不够现代 | RemoteViews 布局偏扁（48dp/12·8）、描边外框生硬 | 56dp / 16·10 内边距 / 12dp 圆角**实底**（低 alpha）/ 标题 15sp medium / 副标题 13sp / 补 `contentDescription` |
| 8 | 新建/详情页丑、验证码字体难看、字段展开不好看 | 输入框是 M3 基线 4dp 圆角；详情页**无头部**；无类型徽标；验证码 24sp | 全局 `shapes.extraSmall = 12dp`；新增 `TypeBadge`；详情页补图标头部 + 分区改 `surfaceContainerHigh`；验证码 **36sp ExtraBold 等宽 + ≤5s 转 error**；表单加分组分隔线 |

### 质量基线（本轮，本地真跑）

`:app:compileFullDebugKotlin` ✓ / 全模块 `detekt` ✓ /
**333 tests, 0 failures**（`:core:common` 109 + `:data:kdbx` 33 + `:data:repository` 37 +
`:app` 154）。

### ⏳ 真机待验收（本轮 8 条对应的验收点）

见 `next-steps.md` 顶部与 MEMORY §9：**凭据面板不再反复解锁** / 上下沉浸 / 筛选条不压首条 /
搜索无黑横幅且返回只退搜索 / 长按可见反馈 / 设置页能加 KDBX / 观感五件 / Bitwarden 侧无回归。


---

## 第五十轮（2026-09-13）：睡前 5 个 bug + 1 个美化

> 提交 `23c3629`；CI run **`34713640553` success（3m24s）**，出 `debug-apk`。
> 用户原话：「**不用询问我，先用最优解方案解决好问题，可以参考 bastion 和 Monica 等项目的方案和壳。
> 我先睡觉去。**」⇒ 全程自主：改 → 本地门禁 → 推送 → 轮询 CI → 归档。
> 根因全表见 `.ai/ISSUES.md` **#70~#75**。

| # | 用户报告 | 根因（一句话） | 落地 |
|---|---|---|---|
| 1 | Tab 切换闪一下，深色模式晃眼 | `fadeIn(220)` + `fadeOut(120)` **时长不同步** ⇒ 中间帧两页同时半透明，合成漏光约 25% | 进出**同时长 140ms + alpha 互为补数**（0.95 / 0.05），去掉叠加的 slide |
| 2 | 验证码页下滑时倒计时条不变小 | 进度条没有随滚动折叠的包装 | 外套 `height = lerp(44dp → 0dp)` + `alpha = 1 - frac`，`tween(200)`，`clipToBounds`（对齐 Bastion `TotpListContent`） |
| 3 | 验证码卡片「编辑 / 移除 / 复制」按钮多余；长按多选 + 左滑删除没弄好 | 主操作（复制）做成图标、低频操作常驻行内；列表**没有「长按进选择态」**这条路径 | 去掉复制图标与底部文字按钮行，收进 MoreVert；两页统一长按多选（`Checkbox` + 底部批量条）；左滑保留且不与长按抢事件 |
| 4 | 填充条目框偏大、不精致、没有 logo | 该下拉由系统按 **RemoteViews** 渲染（阴影/水波纹/网络都不支持）；此前行高 56dp + 圆角底框 + 20dp 小图标 | 行高 48dp、去底框、图标 24dp、字号收紧；新增 `AutofillItemIcon`（Coil 磁盘缓存站点图 → 未命中则哈希配色字母头像，**不发网络**） |
| 5 | 覆盖安装后指纹不能第一时间弹出、开库慢 | `viewModelScope.launch` **默认主线程**，里面跑 Keystore 往返与 PBKDF2 / Argon2 派生；cipher 也是点按钮才创建 | 密钥运算全切 `Dispatchers.IO`；`localUnlockAvailable` 加 `flowOn(IO)`；可用时后台 `prewarmCipher` |
| 6 | 美化新增条目界面 + 条目展示 | 表单分区层次弱；列表看不出收藏 | 分区标题加 16dp 主色图标、分隔线留白 12 → 18/14dp；条目行尾加收藏星标（对齐 Bitwarden） |

### 质量基线（本轮，本地真跑）

全模块 `detekt` ✓（本轮 5 项越线全部修完：拆 7 个 composable + `toggleSelection` helper） /
`:app:compileFullDebugKotlin` ✓ / `:app:assembleFullDebug` ✓ / `testFullDebugUnitTest` ✓。

### ⏳ 真机待验收（6 条）

1. **切页不闪**：深色模式密码 ↔ 验证码来回切，无闪白 / 闪黑。
2. **倒计时条**：下滑收起、上滑展开。
3. **多选 + 滑删**：长按 → 勾选框 + 底部条（全选 / 清空 / 删除所选）；左滑仍能删单条。
4. **填充下拉**：更紧凑且**有站点图标**（缓存未命中时是彩色字母头像）。
5. **解锁**：覆盖安装后冷启动，指纹框立即弹出，进库无可见停顿。
6. **回归**：Bitwarden 同步 / KDBX 读写 / 通行密钥登录不受影响。

---

## 当前状态（2026-10-25 晚）· 已归档

> 本节是**现状快照**，取代上方的历史轮次记录（那些保留作过程留痕）。
> 上方正文最后一次整体刷新停在 2026-09-20；**不要再拿它当"当前"读**。

### 本轮落地：写回基线（#185）

| 项 | 结论 |
|---|---|
| 问题 | 主写路径带的是**缓存里的旧令牌**，"内容与令牌配套"这条前提天生不成立 ⇒ 远端变过而没拉时，服务端会放行写入 ⇒ **静默覆盖远端改动** |
| 防线 1 | `KdbxSyncOrchestrator`：「只有远端变」分支**不推进基线**，改做内容对账（相同才降级 `IN_SYNC`） |
| 防线 2 | 新增 `KdbxBaseStore`（内容指纹）+ `KdbxPendingWrite.baselineAligned` + `persist` 落字节前拦截 |
| 新状态 | `KdbxWriteFailure.StaleBaseline` 全链路贯通到设置页/列表页两处提示文案 |
| 边界 | 闸门**只对云端来源**生效；同步编排器那条写路径显式"不表态"（交给条件写） |
| 门禁 | 编译 ✅ · detekt ✅ · **162 套 / 1620 例 / 0 失败** ✅ |

### 里程碑（与 `.ai/MEMORY.md` §3 同源）

| 期 | 内容 | 状态 |
|---|---|---|
| M0 | 基础骨架 + `core:crypto` | ✅ DONE |
| M1 | Bitwarden 同步 | ✅ 基本收官（等 824c432 三项最终确认） |
| M2 | KDBX 引擎 + 集成 | ✅ **只读 + 写回均已可用**，条目级编辑全部放开，写回基线已补（#185）。剩余：真机 W6 互操作验收 |
| M2-a | 自动填充服务 | 🔄 主体已落地，按真机反馈打磨 |
| M2-b | 网盘同步（OneDrive / WebDAV） | ✅ 文件源 + 账号管理 + 条件写 + 冲突检测 |
| M3 | 平台集成（安全中心） | ⬜ 安全中心待做（autofill 已并入 M2-a） |
| M4 / M5 | 发布准备 / 1.0 | ⬜ TODO |

### 模块实际状态（更正上表里已过期的两行）

| 模块 | 真实状态 |
|---|---|
| `data:kdbx` | ✅ **不是"⬜ M2"**：解锁/读路径/写回/回收站/通行密钥·验证码 codec/往返自检/写回基线全部就位，`KdbxItemRepository` 五动作可用 |
| `core:ui` | 🗑️ 已于 2026-10-05 删除（全仓零引用），主题色板单处在 `app/.../ui/theme/VaultixBrandColor.kt` |

### 已知未做（按优先级，详见 `next-steps.md` 与 `audit/bitwarden-kdbx-sync-audit.md`）

1. `KdbxAutoUploader.notifyLocalChangedDuringUpload` 在 app 层仍零调用（大部分场景已被"多轮现查"顶掉）；
2. 安全中心（M3）**整块为空**；
3. `Docs/08` S11–S20 多项未做（`archivedDate` 领域模型**根本没有字段**）；
4. 附件**只能看不能加**（`uploadAttachment` 零命中）；导出只有 Bitwarden 加密 JSON；
5. 周期同步未接（M1 判推迟，理由见决策记录）。

## 当前状态（2026-10-26）

> 本节是**现状快照**，取代上方所有历史轮次记录（那些保留作过程留痕）。

### 本轮落地：与开源项目对标审计 → 九组修复（#186–#195）

做法：先跑**四维度只读审计**（备份与自动填充 / 锁与设置面 / 通行密钥与 KDBX / 安全加固与 CI），
基准为 Bitwarden 官方 Android、Keyguard、KeePassDX、Keepass2Android、Monica、Bastion 快照，
**再逐条落地**。

| 编号 | 一句话 | 关键判据 |
|---|---|---|
| #186 | `allowBackup` 默认 true ⇒ 锁库信封被云备份搬走，Keystore 不跟着走 | **加密材料与解密密钥分开存时，备份策略按后者决定** |
| #187 | 自动填充保存链四处断裂（锁定态无 `saveInfo`、字段选错、撞号、明文进 Intent） | **`PendingIntent` 的 requestCode 是身份**；**凭据不进 `Intent`** |
| #188 | 锁库不清剪贴板 + 网址被当敏感内容 30 秒后清空 | **定时清理必须配一个立即清理的触发点** |
| #189 | 熄屏不保证 `onStop` ⇒ 自动锁定不启动 | **「用户看不见了」有熄屏/切后台/分屏三条通路** |
| #190 | 409 被当弃单（同步）+ 内容指纹只摘 5 字段（KDBX） | **4xx ≠ 可以扔**；**指纹漏字段 = 那道防线在该字段上恒失效且静默** |
| #191 | 搜索不覆盖备注与自定义字段 | **界面上展示的字段都该可搜索**，例外要反向锁定 |
| #192 | 空分组被当「远端删了」⇒ 能清空本地文件夹 | **「远端没有」≠「远端删了」** |
| #193 | `release.yml` 写死 `gradle-version: 8.11.1`（`ci-debug.yml` 已修，这条路漏网） | **同一条规则写在两处，改一处就"看起来已修"** |
| #194 | 附件解密后的明文留在 `cacheDir` 锁库不清 | **「临时文件」≠「安全文件」** |
| #195 | `keyAlgorithm` 两个名字（`ECDSA`/`ES256`）+ 四处 `FLAG_MUTABLE` + TOTP 密钥进 extras | 同上；**IMMUTABLE 是默认，MUTABLE 要理由** |

### 门禁

| 项 | 结果 |
|---|---|
| 编译（full / offline 双变体） | ✅ |
| `./gradlew detekt` | ✅ |
| `./gradlew test --offline --max-workers=1 --no-daemon` | ✅ **131 套 / 1301 例 / 0 失败**（第四批 #198 液态玻璃 +1 套 / +8 例） |
| `python3 .ai/tools/check_issues_index.py` | ✅ 索引 = 题注 = 正文 全部一致 |

### 里程碑（与 `.ai/MEMORY.md` §3 同源）

| 期 | 内容 | 状态 |
|---|---|---|
| M0 | 基础骨架 + `core:crypto` | ✅ DONE |
| M1 | Bitwarden 同步 | ✅ 基本收官（等 824c432 三项最终确认） |
| M2 | KDBX 引擎 + 集成 | ✅ 只读 + 写回均已可用；写回基线（#185）+ **内容指纹全字段（#190）** 均已补。剩余：真机 W6 互操作验收 |
| M2-a | 自动填充服务 | 🔄 主体已落地；本轮补齐保存链（#187） |
| M2-b | 网盘同步（OneDrive / WebDAV） | ✅ 文件源 + 账号管理 + 条件写 + 冲突检测 |
| M3 | 平台集成（安全中心） | ⬜ 安全中心待做 |
| M4 / M5 | 发布准备 / 1.0 | ⬜ TODO |

### 已知未做（按优先级，详见 `next-steps.md`）

1. **自动拉取零接线**：`SyncTrigger.PAGE_ENTER / APP_RESUME / PERIODIC` 三个枚举值全仓零调用
   （2026-09-08 决策「自动同步 = 本地修改 flush；拉取 = 手动」，现仍是手动）；
2. 通行密钥：`excludeCredentials` 仍不读（**Origin 校验与 `authenticatorSelection` 已于
   2026-10-26 修**，见 #196 / #197；`backupEligible / backedUp` 强制 true 属有意为之）；
3. PIN 熔断无冷却、忘记 PIN 可绕过；「从不」档 KEK 不绑认证；
4. 安全中心（M3）**整块为空**；`Docs/08` S11–S20 多项未做（`archivedDate` 领域模型**没有字段**）；
5. 附件**只能看不能加**；导出只有 Bitwarden 加密 JSON，且丢密码历史/时间戳；
   无 CSV 导入；无文件夹 CRUD；`passwordRevisionDate` 从不更新；
6. `UpdateChecker` 走 `ghproxy.net` 且不校验 SHA-256；`FillAssistRepository` 默认联网拉规则；
   `DeveloperLogsDialog` 的 E/F 兜底不脱敏；
7. `KdbxRoundTrip.verify` 只比字段名不比值；`KdbxFidelity.NON_DEFAULT_CIPHER` 从不产生。

---

## 当前状态（2026-10-29）

> 本节是**现状快照**，取代上方所有历史轮次记录（那些保留作过程留痕）。

### 本轮：查漏补齐四项（#206–#209）

做法：先把上面「已知未做」那七类**逐个 grep 核实**（清单会过期），再落地四项。
核实结论里有两条清单已经过期：`UpdateChecker` 走 ghproxy（镜像只作用于下载页、
**默认关闭**）、`KdbxRoundTrip.verify` 比字段值（10-28 那批已修）。

| # | 一句话 | 关键判据 |
|---|---|---|
| #206 | 开发者日志**屏幕显示没脱敏** + `E/F` 兜底架空白名单 | **脱敏保护的是"看的人"**；**兜底只能比主判据更严** |
| #207 | 镜像下载后无处可验 → 「核对校验和」入口（**不走镜像**） | **校验物来源必须与被测物独立** |
| #208 | `excludeCredentials` 从不读 ⇒ 同站点能注册两把密钥 | 规范写死的语义，不实现就是洞 |
| #209 | 改密码后 revision 不更新、密码历史**永不增长** | **「沿用原密文」是手段，不是目的** |

### 门禁

| 项 | 结果 |
|---|---|
| 本地脚本（8 项） | ✅ 索引一致性 ✅ |
| 新增单测 | +27 例（`LogScrubberTest` / `PasskeyExcludePolicyTest` / `CipherMapperPasswordRevisionTest` / `UpdateCheckerTest`） |
| 编译 / detekt | ✅ CI run **40908552787**（head `358c7a6`）：10 模块 detekt + 9 模块单测 + `assembleFullDebug`，`e:` **0** |
| 发版 | v0.10.3 已发（`d631c3d`）；#204 的校验和区块在真实发版路径上验过 ⇒ **闭环** |

### 已知未做（2026-10-29 重新核实过）

1. **自动拉取零接线**（`SyncTrigger.PAGE_ENTER / APP_RESUME / PERIODIC`）——
   ⚠️ **不是漏做，是 2026-09-08 决定不做**（「自动同步 = 本地修改 flush；拉取 = 手动」），
   要改须先动决策，不是补代码；
2. PIN 熔断无冷却；「从不」档 KEK 不绑认证（**忘记 PIN 入口已于 #161/B5 落地**）；
3. 安全中心（M3）**整块为空**；`Docs/08` S11–S20 多项未做（`archivedDate` 无字段）；
4. 附件**只能看不能加**；**无 CSV 导入**；
5. `FillAssistRepository` 默认联网拉规则（无开关）；
6. `KdbxFidelity.NON_DEFAULT_CIPHER` 从不产生（只声明、未检测）。

---

## 当前状态（2026-10-30）· 锁语义澄清 + 开发者「保持不锁」

触发：用户四问（日志内容 / 日志性能 / 两版指纹差异 / 要不要开发者设置），前提是"两个环境
都设了自动锁定 = **从不**"。

### 结论（一句话各一条）

| 问题 | 一句话 |
|---|---|
| 两版指纹差异 | 「清后台 / 熄屏回来要验证」= **`OnAppRestart`（重启 App 时）** 档，**不是「从不」**。「从不」下任何触发源都不锁、进程死后免交互恢复 ⇒ 同是「从不」不可能一个锁一个不锁，先核对**实际档位**（设置页那行副标题）与解锁页形态（只有指纹按钮 = 查看层锁） |
| 日志内容 | **上一轮 #206 已优化**（采集即脱敏 + 兜底收窄）。真要再动是"过滤范围"，不是脱敏 |
| 日志性能 | **release 零开销**（`enabled=BuildConfig.DEBUG`，关着时连 lambda 都不执行）；debug 只是 `Log.d`（系统缓冲，不写文件）；采集只在点开对话框时跑一次 `logcat -d -t 1200`（IO 线程） |
| 要不要开发者设置 | **已加**（#210），与日志同组，`BuildConfig.DEBUG` 才渲染 |

### 改动

| 文件 | 改动 |
|---|---|
| `core/datastore/.../VaultixPreferences.kt` | 新键 ~~`dev_relax_screen_lock`（默认 `true`）~~ → **已改为 `dev_relax_screen_lock_v2`（默认 `false`）**，见下文 #213 + Flow + write-only 属性写口（**不加第 41 个函数**） |
| `app/.../security/VaultLockManagerImpl.kt` | `OnAppRestart` 分支加开关判断；`devRelaxScreenLockEnabled()`（读失败按 `true`=锁） |
| `app/.../ui/settings/SettingsScreen.kt` | `DeveloperSection` 加「保持不锁（开发用）」行 |
| `app/.../ui/settings/SettingsViewModel.kt` | `devRelaxScreenLock: StateFlow<Boolean?>` + setter |
| `app/.../passkey/PasskeyCreateActivity.kt` | `excludeCredentials` 命中的 type 串改成规范值（#211） |

### 门禁

| 项 | 结果 |
|---|---|
| 文档一致性脚本 | ✅ 七篇条数 = 索引表 = 分节标题 = 篇内头部；索引零孤儿行 |
| detekt | ✅ `dev.detekt 2.0.0-alpha.6` CLI 逐文件，7 个改动文件 0 issue |
| 新增单测 | +3 例（`OnAppRestart` 正向 / 开关关掉 / 反向边界） |
| 编译 | ✅ CI run `5dded0c`（第一轮 `41033038640` ❌：两处 `e:`，均由"想当然"造成，见下） |

### ⚠️ 两条新判据（都付了 CI 时间）

1. **`@get:/@set:JvmName` 只改 JVM 方法名** —— Kotlin 成员解析永远用属性名；
2. **`javap` 的 `public` ≠ Kotlin 的 `public`** —— 依赖里的常量要看 Kotlin 可见性（本次那个是 `internal`）。

### 仍未做（不变）

「已知未做」那六条与 10-29 版一致，本轮未触碰（尤其**自动拉取零接线是决策不是漏做**）。

---

## 当前状态（2026-10-30 晚）· #212 开关命中框

| 项 | 结论 |
|---|---|
| 用户反馈 | 「保持不锁的按钮无法点击」——开关点了没反应 |
| 真因 | **不是数据流**（3 条单测已钉住），是**命中框**：M3 `Switch` 52×32dp，`toggleable` 命中框就这 52×32dp；该行又没传 `onClick` ⇒ 行内唯一热区只有 32dp 高 |
| 修法 | `SwitchWithTouchTarget`：开关占 52×**48**dp 格子、`Switch` 仍居中画 52×32 ⇒ 视觉零变化、热区 +8dp/侧；`SettingsSwitch` 内置（13 调用点）、`CapabilityToggle` 三分支共用；`DisplayOptionsSheet` 矮行 padding 提一档 |
| ⚠️ 死路 | `Modifier.minimumInteractiveComponentSize()` 对 `Switch` **无效**（它只"提供" Local，`Switch` 不读）；`Box.clickable` 扩热区会**切两次弹回原样** |
| 门禁 | detekt 2.0.0-alpha.6 CLI：改动 3 文件 0 issue（先跑过坏样例确认 CLI 会报错）<br>CI `bccb1f9` ✅ success |
| 待办 | **用户真机复测**（按开关边缘也应生效） |

---

## 当前状态（2026-10-30 深夜）· #213 恒等映射（#212 之后仍点不动的真因）

| 项 | 结论 |
|---|---|
| 用户反馈 | 「还是无法点击，**按钮始终是打开状态**」 |
| 真因 | 偏好第一版是**反义**（`true`=仍需验证），UI 又取反一次；而 `Switch` 回调传入的**已是新值** ⇒ 写入 `!(!checked)` = 当前值 ⇒ **`f(!x)=x` 恒等映射**，状态永远不变 |
| 为何上轮没抓到 | 写入是成功的（写的就是旧值），偏好层单测全绿；`!` 类型合法，detekt 看不见；#212 的命中框问题同时存在，掩盖了它 |
| 修法 | 语义翻正（`true`=保持不锁，默认 `false`）+ 换键 `dev_relax_screen_lock_v2` + UI `onCheckedChange = viewModel::setDevRelaxScreenLock` **不取反** + 锁管理 `if (!…)`、读失败默认 `false` |
| 新增测试 | `VaultixPreferencesDevRelaxTest` 3 条（默认值 / 写口往返 / 旧键必须被忽略） |
| 门禁 | detekt 6 个文件 0 issue；`check_issues_index.py` ✅；CI `c69fa9e` ✅ success（含新增 3 条偏好层单测） |
| 待办 | **用户第二次真机复测** |

---

## 当前状态（2026-10-30 本轮）· #214 两层锁 + 「系统锁屏时」档

| 项 | 结论 |
|---|---|
| 用户诉求 | 「开启了从不锁定，熄屏或者清除后台后还是会需要指纹解锁；但填充并不受影响……其他的不变」 |
| 真因（两处） | ① `AutoRestoreTrigger` 用**免认证信封**恢复 ⇒ 清后台静默开回来；② `VaultixApplication`/`AutoLockController` 对 `Never` 直接 return ⇒ 无人挡界面 |
| 为什么不能"熄屏就锁库" | 那会让填充重新认证、密钥清零 ⇒ 违反「其他的不变」。缺的不是更狠的锁，是**两层** |
| 修法 | 第二层复用已有的 `viewLock`（#61，只挡界面不动密钥）+ 新增 `AutoViewGate` 在**仅两个**时刻自动置位（`ACTION_SCREEN_OFF` / 信封恢复且 `opened>0`）；自动标记存 `autoViewLockedIdsState` **子集** ⇒ 界面全挡、填充看穿自动但仍尊重手动 |
| 前置条件 | 已解锁库 / 产品开关 / 档位==`Never` / **指纹可用** / 开发者不锁 —— 缺一即跳过；读失败 **fail-open**（与 `devRelax` 的 fail-closed 相反，KDoc 写了理由） |
| 新档位 | `VaultTimeout.OnSystemLock`（`-3`）：严格 `isDeviceLocked()` + 300ms 复查 + `screenOffToken` 取消；`fromLegacyMinutes(-3)` 仍 `Never`（`assertNotEquals` 钉死） |
| 设置入口 | 「进入 App 时仍需验证」开关，**只在** 全局档位==`Never` 且生物识别可用时显示 |
| 新增单测 | `AutoViewGateTest` 6 条 + `VaultLockManagerImplSystemLockTest` 4 条 + `VaultSessionManagerTest` 2 条 + `VaultTimeoutTest` 3 条 + `AutoRestoreTriggerTest` 2 条（恢复后的门禁接线） |
| 门禁 | detekt 2.0.0-alpha.6：全部改动 + 测试源 **0 issue**；`check_issues_index.py` ✅（04 篇 19→20） |
| CI | `812218b` ❌（编译：`AutoRestoreTriggerTest` 仍三参）→ `f7ac3d2` ❌（`AutoViewGate` 读错偏好类）→ `788d805` → `d3485d0` ✅ **success**（detekt / 编码 / 编译 / 32 个测试类的全部单测 / APK 已发 preview Release） |
| 待办 | **用户真机复测**：从不档 ① 熄屏回来要指纹 ② 清后台重开要指纹 ③ 填充仍直通 ④ 熄屏后回来看条目不用重新输主密码 |

### 判据（本轮新增四条）

1. **「锁」至少两层，先问锁哪一层** —— 密钥可见性与界面可见性是两个独立问题，合成一个布尔必然二选一。
2. **门禁类改动先问"能力不可用时退化长什么样"** —— 没有指纹时门禁 = 每次回来输主密码，比不做更糟。
3. **自动置位的锁态必须是可区分子集** —— 共用标志 ⇒ 下游只能"一律认证"或"一律放行"，后者是安全倒退。
4. **fail-open / fail-closed 按"代价更大的一侧"定，每个都写理由** —— 同项目里两种方向可以并存。

---

## 当前状态（2026-10-31）· 一次指纹够所有库（#215）

触发：用户报「进 App 要验两次指纹」—— 两个密码库，熄屏后重进，验完进第一个库、
**切到第二个库又弹一次**。用户判据是对的：「两个密码库不应该是同一个指纹吗，指纹检验一次就够了啊」。

### 真因：认证是全局的，门禁标记却是按库的

| 环节 | 行为 | 后果 |
|---|---|---|
| `AutoViewGate.gateUnlockedVaults()`（#214） | 熄屏给**每个**已解锁的「从不」库各打一个 `automatic = true` | 两库 ⇒ 两个标记 |
| `RootNavViewModel` | 「**任一**库带查看锁 ⇒ 收回解锁页」 | 剩一个标记，整个 UI 就停在解锁页 |
| `UnlockViewModel.completeLocalUnlock(forViewLock = true)` | 原来只调 `clearViewLock(vaultId)` | 只清当前库 ⇒ 切库再验一次 |

⇒ **验几次 = 用户有几个库**。Bitwarden 无此问题是因为它的锁定态是**账号级**，门禁与认证同粒度；
#214 把门禁细化到库、没同步细化认证，错位就出现了。

### 改动

| 文件 | 改动 |
|---|---|
| `data/repository/.../VaultSessionManager.kt` | 新增 `clearAutoViewLocks()`：`viewLockedIds -= autoViewLockedIds`（**只清自动子集**），同一 `mutex` 临界区内一次改完 |
| `domain/.../VaultSessionRepository.kt` | 同名接口成员 + KDoc（为什么不 `for` 循环 `clearViewLock`） |
| `data/repository/.../VaultSessionRepositoryImpl.kt` | 零逻辑直通 |
| `app/.../ui/unlock/UnlockViewModel.kt` | `forViewLock` 分支一行：`clearViewLock(vaultId)` → `clearAutoViewLocks()` |
| `data/repository/src/test/.../VaultSessionManagerTest.kt` | +1 例：跨库一次清干净 + 手动锁原样保留 |
| `app/src/test/.../ui/unlock/ViewUnlockScopeTest.kt` | **新文件**，2 例：钉「UI 到底调了哪个方法」+ 查看锁不碰密钥 |

刻意**弃用**的两条：`clearAllViewLocks()`（会连带抹掉**手动**锁 = 安全倒退）、
UI 里 `for` 循环 `clearViewLock`（仍是逐库，且漏掉之后才出现的库）。

### 门禁

| 项 | 结果 |
|---|---|
| 编码 / U+FFFD / 括号配平（6 文件） | ✅ 无异常 |
| 新增行 ≤120 列 | ✅ 超出者全是注释（detekt 不计） |
| 七个 `check_*.py` 静态脚本 | ✅ 全 exit 0 |
| `check_issues_index.py` | ✅ 索引 = 题注 = 正文 |
| 编译 / 单测 | ✅ CI `a877736` **一次过**：`build_failed=false`、**1422 例 / 失败 0** |

### ⚠️ 新判据

1. **认证粒度必须与门禁粒度对齐** —— 一全局一按库，立刻退化成「每库各验一次」；
2. **「存储方式」不许升格成「语义」** —— 按库存标记是为了知道锁哪几个库，不是让用户按库出认证；
3. **回归门禁要钉在调用点** —— 只测数据层的话，把 UI 改回 `clearViewLock(vaultId)` 依然全绿。

### 发版：v0.10.4

用户：「发一个 release 吧」。`#214` + `#215` 一起进 **v0.10.4**（`ddcb113`）。

| 项 | 结果 |
|---|---|
| 流程 | `main` bump `VERSION` → `rele` **快进**（落后 18 个提交，无合并提交）→ push `rele` |
| 产物 | `app-full-release.apk` **7.24 MB** + `checksums-sha256.txt` |
| Release | `draft=false` / `prerelease=false` / `make_latest=true` |
| SHA-256 | `55c768d3…3952f175` |

⚠️ 没走 tag 触发：`release.yml` 的「Remove existing release/tag」在 tag 事件下会先删掉刚推的 tag。

### 顺手修的流程问题：Release 说明不再是提交列表

自动 changelog 是提交列表，混着「修 CI 红」这类内部账；而 `PATCH /releases` 需要 token，
沙箱只有 SSH key ⇒ **发出去就改不掉**。已改为：新增 `Docs/release-notes/v<版本>.md`（人工撰写、
正文直接是内容），`release.yml` 优先读它，**文件不存在时回退提交列表**（不断掉发版路径）。
从 v0.10.5 起生效，v0.10.4 的 body 保持原样。

### 仍未做（不变）

「已知未做」六条与 10-29/10-30 版一致，本轮未触碰。
