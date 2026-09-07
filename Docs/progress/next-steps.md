# 下一步任务清单

> 更新于 2026-09-08（第九轮）。同步编排 UI 接线完成（条目页同步条、库列表同步状态、
> 回前台 APP_RESUME、密码强度条）；Bastion 冻结为 reference（Doc 18，第八轮）。
> 状态：`TODO` / `DOING` / `DONE` / `BLOCKED`

## 已完成（第九轮 2026-09-08 · 同步编排全链路接线 + 强度条 + Bastion 快照）

- [x] **Bastion 参考快照 vendored**：`reference/bastion/`（@369ed56，1012 文件/≈13 MB：
      主源码 664 + 单测 155 + repo docs + BastionDocs md + workflows 参考；只读、不参与
      构建/detekt）；接力 AI 无需访问 D:\Bastion；Docs/18 §2 与 .ai/MEMORY 已更新指针
- [x] **同步编排器（Bastion 语义移植，data:repository）**：触发分类/静默语义/
      90s·180s 节流/运行中合并回放/指数退避×5/per-vault 状态流/解锁门卫；
      单测覆盖（虚拟时间）；Hilt 双构造（@Inject 两绑定参数 + internal 五参测试构造）
- [x] **同步 UI 接线**：条目页提示条（运行中细进度条/警告常驻/手动成功短暂提示）
      由 orchestrator per-vault 状态派生；进页 PAGE_ENTER、手动刷新 MANUAL force；
      AutoLockController.onStart 回前台对已解锁库逐库 APP_RESUME；
      库列表卡片行内同步状态（同步中/同步失败；静默成功不打扰）
- [x] **密码强度条**：新建/编辑表单密码框下五档评分条（core:common PasswordStrength
      移植 Bastion，仅提示非门槛）
- [x] 双 flavor 编译 + Hilt 组件（full/offline）+ 各模块单测 + detekt 全绿

## 已完成（第八轮 2026-09-08 · Bastion 冻结决策与参考地图）

- [x] **决策落地（decisions.md）**：Bastion 冻结 = reference implementation，Vaultix = 唯一演进线；只搬三类资产、不做文件级搬迁
- [x] **新增 `Docs/18-Bastion参考地图.md`**：分里程碑参考索引（M1 同步核对 / M2 data:kdbx / M3 Autofill / 1.x）、别读别搬清单、对拍流程、GPL 溯源规范；README 导航与阅读路径已更新
- [x] 长期记忆同步（.ai/MEMORY.md、.workbuddy/memory/MEMORY.md、SESSION-2026-09-08.md）
- [x] M2 提示：`data:kdbx` 启动前通读 Docs/18 §4.3（Bastion KDBX 资产密度最高的参考站）

## 已完成（第六-七轮 2026-09-08 · 真机联调 + 签名修复 + 快速解锁 + 兼容）

- [x] **签名彻底修复（63be53c CI 全绿）**：Secrets env 注入 + PKCS12 keypass=storepass
      （详见 decisions/MEMORY）；preview 只发 **full 单包**（bbf843c）
- [x] **prelogin/同步兼容**：Vaultwarden camelCase 双形态；CF 后自托管请求头组
      （Bastion 同值：Chrome UA/Sec-Ch-Ua/Keyguard-Client/Bitwarden-Client-*）
- [x] **2FA 登录**（经典 OAuth 扩展）+ 多方式选择（TOTP/邮箱/Duo/YubiKey/org-Duo；
      YubiKey 44 位动态码输入）
- [x] **type 0 遗留密文默认放行**（官方/Bastion 对齐；修复「未命名」条目）
- [x] **设备登记 Header**（connect/token 带 device-type/identifier/name；type 0=Android）
- [x] **本地快速解锁（免主密码/免 2FA）**：Keystore user-auth KEK 包裹 + 生物识别/
      设备 PIN 解封；解锁页按钮 + 登录后启用横幅 + 设置页管理
- [x] 设置页（自动锁档位/剪贴板清除/防截屏/动态取色/立即锁定/快速解锁管理/关于）
- [x] 双 flavor 编译 + 单测 + detekt 全绿

## P0 · M1 收尾（剩余）

- [ ] **真机回归**：新固定签名包（63be53c+）上验证 登录(2FA)→同步→未命名已修复→
      快速解锁启用→锁屏后生物识别重开→设备管理可见；同步编排行为（条目页状态条、
      手动刷新、切后台回前台自动同步、库列表状态行）与密码强度条
- [ ] WorkManager 周期同步（P2 前移候选：用户期待「打开即最新」；编排器 PERIODIC
      触发已预留）
- [ ] 移除库入口（二次确认）；回收站视图
- [ ] UI 文案抽查迁 strings.xml（同步失败/拦截原因文案仍由 data 层直供）

## P1 · 质量基础设施（剩余）

- [ ] Baseline Profile；Gradle 配置缓存；ViewModel 单测；:app 单测用例

## P2 · 自动化

- [ ] WorkManager 周期同步 + 网络约束（`Docs/17` §3.3：禁止常驻轮询）

## P3 · M2（KDBX）

- [ ] `data:kdbx` 引擎（kotpass），按 `Docs/02` §3.4 做往返保真度测试

## 已知未决（接力者注意）

- 快速解锁 payload 在 SecureCredentialStore（key `local_unlock_key::<vaultId>`），
  开关在 DataStore；删除 KEK 仅由系统指纹变更触发（单库 disable 只删 payload+开关，
  多库时 KEK 共享保留——未来多库需「全库清空」入口）
- 同一服务器仅一个账号（vaultId=server）；设备 id 存 SecureCredentialStore（卸载即换，
  服务器端会累积旧设备记录，属正常）
- Detekt 用 2.0.0-alpha.6；2.0 稳定后升级重新生成默认配置核对

## 已完成（第三轮 2026-09-08 · 质量门禁）

- [x] **Detekt 门禁（P1）**：`dev.detekt` 2.0.0-alpha.6（官方兼容表对齐
      Kotlin 2.4.10 / AGP 9.3 / Gradle 9.5）；根工程统一为全部 Android 模块开启，
      阈值在 `config/detekt/detekt.yml`（对齐 Docs/16 硬上限：
      LongMethod ≤150 行 / LargeClass ≤1200 / 参数 ≤8 / 单类函数 ≤40），
      Compose 函数命名与命名参数数字做政策级豁免
- [x] 存量违规清零（crypto 有意捕获用 @Suppress+理由注释、网络超时常量化、
      ItemDetailScreen 拆分降圈复杂度、RepositoryModule→interface、
      ItemRepositoryImpl 解密调度器注入化）
- [x] CI：push/PR 均执行 `gradlew detekt`（--no-configuration-cache）；
      `config/**` 纳入触发路径；单测补充 :data:repository
- [x] `./gradlew detekt` 全模块（main+test）绿

## 已完成（第二轮 2026-09-08 · 详情/编辑/删除 + 自动锁定）

- [x] 条目详情页（S9 最小版）+ 敏感复制（剪贴板自动清除，Bastion 思路 +
      GPL 溯源）+ 编辑（S10 最小版，updateItem 沿用原 id）+ 软删除（回收站语义，
      SOFT_DELETE 入队 + 轻量推送）
- [x] 自动锁定（AutoLockController：切后台计时 elapsedRealtime、超时 lockAll +
      回根导航）
- [x] data:repository 新增 6 个写路径/解密流单测（共 11 个）；双 flavor 编译通过

## P0 · M1 收尾（剩余）

- [ ] **设置页最小版**：自动锁档位（Bastion 选项 0/1/5/10/15/30/60/300/1440/-1，
      逻辑已就绪仅差 UI）+ 剪贴板清除时长 + 移除库入口（二次确认）
- [ ] **2FA / 新设备 OTP 登录**（当前 UI 明示不支持；需 identity 层 two-factor
      分支 + 输入步骤 UI，参考 Bastion BitwardenLoginScreen 交互，勿整搬）
- [ ] 解锁页 5 次失败冷却 30s（Docs/08 S6 规格）
- [ ] 回收站视图（软删除条目的恢复 / 永久删除）
- [ ] UI 文案抽查迁 `strings.xml`（详情/编辑已用资源；sync 提示文案仍由 data 层中文直供）

## P1 · 质量基础设施（剩余）

- [ ] **Baseline Profile**（启动与首次滚动性能）
- [ ] Gradle 配置缓存（lint/detekt 需 --no-configuration-cache，见 CI 注释）
- [ ] ViewModel 单测（ItemsViewModel / ItemDetailViewModel 用 fake repository）
- [ ] :app 单测配置（testFullDebugUnitTest 现无用例）

## P2 · 自动化

- [ ] WorkManager 周期同步 + 网络约束（`Docs/17` §3.3：禁止常驻轮询）

## P3 · M2（KDBX）

- [ ] `data:kdbx` 引擎（kotpass），按 `Docs/02` §3.4 做往返保真度测试

## 已知未决（接力者注意）

- 自动锁定只做了「切后台超时」一档；「切后台立即锁 / 屏幕锁定时锁」等设置项
  待设置页落地（VaultSessionManager.lockAll 已幂等就绪）
- `VaultItem` 仍是雏形（6 字段），与 `Docs/02` 超集模型差距大；补字段同步补 Mapper
- 新建「推送成功但响应丢失」极端情况可能产生服务端重复条目（无幂等键），已知可接受
- UI 尚无搜索/筛选/多选/回收站视图（均非最小闭环口径）
- Detekt 用了 2.0.0-alpha.6：2.0 稳定发布后应升级并重新生成默认配置核对
