# 当前进度快照

> 最后更新：2026-09-12（第四十八轮 · 锁/解锁规范化 1b~4 + 顶栏胶囊化 + 站点图标 +
> **M2 KDBX 集成 + 通行密钥/验证码解析补全**）
>
> 本轮四个提交：`5682bc6`（锁/解锁 1b~4）/ `e88bda2`（第 2 批顶栏）/ `93c814c`（第 3 批站点图标）/
> `fcaf918`（第 4 批 KDBX 集成 + codec）。逐步交接单见 `.ai/ISSUES.md` #60。
>
> 逐轮流水见 `next-steps.md`（最新在文件顶部）与 `.ai/SESSION-2026-09-12.md`。

## 里程碑进度

| 期 | 内容 | 状态 |
|---|---|---|
| M0 | 基础骨架 + `core:crypto` | ✅ DONE |
| M1 | **Bitwarden 同步** | ✅ 功能与回归基本收官（d689a37 登录失效修复包与 824c432 同步策略包均已真机实测基本正常；等用户对 824c432 三项最终确认后正式收口） |
| M2 | KDBX 引擎 + 集成 | 🔄 **阶段 A（只读）集成完成**：引擎（`879e7c1`）+ SAF 选文件/解锁/读路径分流/切库锁旧库 + 通行密钥·验证码 codec（`fcaf918`）。**阶段 B（写回）未做** |
| M2-a | **自动填充服务**（提前于 KDBX 启动） | 🔄 骨架/解析/匹配/保存/CP 集成已落地（a06f037→f474654）；本轮补「解锁即回填」（`5682bc6`） |
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

