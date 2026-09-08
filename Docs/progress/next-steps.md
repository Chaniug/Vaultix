# 下一步任务清单

> 更新于 2026-09-08（第十轮）。Bitwarden 对齐审计 + 批 1（数据安全/类型保真）
> 落地；审计报告 `Docs/progress/audit/bitwarden-alignment.md`。
> 状态：`TODO` / `DOING` / `DONE` / `BLOCKED`

## 已完成（第十三轮 2026-09-08 · TOTP 统一界面 + 通行密钥只读列表 + 入口 hub）

- [x] **TOTP 统一界面（对齐 Bitwarden 总览 + Bastion 验证器视图）**：从所有登录条目的
      `login.totp` 归一化展示，区分「已绑定（随密码条目）/ 独立（空密码登录条目，Bitwarden
      官方兼容形态）」两类；实时滚动刷新 + 进度条 + 点按复制；搜索发行方/账号；编辑/删除；
      独立项可「绑定到密码条目」合并后删除
- [x] **Steam 验证码**：Totp 引擎 Base64 密钥 + HMAC-SHA1 + 26 字符专属字母表 + 固定 5 位码；
      otpauth 解析按 issuer/label/algorithm 识别 steam；core:common 单测 5 例覆盖
- [x] **通行密钥只读列表（PasskeysScreen + SavePasskeyDialog）**：拉平所有登录条目的
      fido2Credentials 展示；**只读（仅查看/删除）** 不可自建；保存（绑定）时强制挂到所选
      登录条目，对齐 Bitwarden 无独立通行密钥条目形态（CipherMapper 已支持 fido2 逐字段加密写回）
- [x] **入口 hub**：ItemsScreen 顶栏新增 TOTP 入口；VaultixApp 新增 TotpCodes/Passkeys 路由；
      验证码界面顶栏「通行密钥」按钮进入 PasskeysScreen
- [x] **搜索 + 删除**：TOTP 界面、通行密钥界面均含搜索与删除；密码条目入口沿用既有能力
- [x] 修复编译与 detekt 0 违规门禁（超长行拆分、MagicNumber 常量化、UnusedParameter、
      updateItem 补 vaultId、补 passkey_field_user 字符串与必要 import）；双 flavor 编译 +
      全模块 detekt（0 违规）+ 受影响模块单测（core:common / data:bitwarden 全绿）
- [x] 提交并推送 `origin/main`（5e652ce）

## 已完成（第十轮 2026-09-08 · Bitwarden 对齐审计 + 批 1）

- [x] **对齐审计**（Docs/progress/audit/bitwarden-alignment.md）：Vaultix bitwarden 侧
      vs Bastion reference 全量差距表 + 批次划分（M1-1..7 做 / M2-1..3 推迟 / 不做）
- [x] **编辑丢数据修复（M1-1/2）**：DTO 全载荷承载（card/identity/sshKey/secureNote/
      uri/totp/fido2 对齐 Bastion 字段集）；更新 = 合并上传（toUpdateRequest 只
      重加密可编辑明文，未编辑密文段原样并入）
- [x] **类型保真（M1-3）**：type5=SshKey 显式建模；写路径类型守恒守卫拒写未知类型
      （防 type 漂移）；条目列表/详情类型徽标；非 Login 编辑只开放名称/备注并提示
- [x] **同步收敛与毒丸处理（M1-5/6）**：全量成功后 prune 服务端已移除行（排除
      pending ops）；flush 4xx（401/408/429 除外）弃单
- [x] 单测新增（mapper 载荷保真 4 例 + repository 类型守卫 1 例）；双 flavor 编译 +
      Hilt + 各模块单测 + detekt 全绿
- [x] **回收站视图（S19，批 1 后追加）**：domain/data 新增 observeTrash /
      restoreItem / permanentDeleteItem（本地先行 + RESTORE/DELETE 入队补推，
      4xx 弃单）；条目页顶栏回收站入口 → TrashScreen（恢复 / 永久删除二次确认 /
      空态 / 类型徽标）；observeItem 对已删行保持 null 语义；repository 3 例新单测
- [x] 文档同步（MEMORY / decisions / SESSION / audit 报告）

## 已完成（第十二轮 2026-09-08 · WIP 推送 + 编译膨胀审计 + 编译器健康）

- [x] **WIP 推送（76979ac）**：指纹按钮归位 / 条目字段补全(uri·totp·fido2) /
      TOTP 引擎 / 卡包·SSH 读取打通，共 13 文件，已推 `origin/main`
- [x] **编译膨胀审计（排查"编译器过大无法编译"）**：扫描 674 个函数 + 全模块 detekt
      —— 代码已在质量门禁内（最大源函数 ≤153 行、无超 JVM 方法上限、配置缓存 CI 已开）；
      **唯一真实风险 = Gradle/Kotlin daemon 仅 2GB 堆**（ISSUES #14 同根因）
- [x] **构建堆修复（`gradle.properties`）**：`org.gradle.jvmargs` 2g→4g；新增
      `kotlin.daemon.jvmargs=-Xmx4g -XX:ReservedCodeCacheSize=512m`（Kotlin 编译
      daemon 独立 JVM，大模块生成字节码防 OOM）；注释锁死，防后人误改回 2g
- [x] **CI 单测盲区补位（`ci-debug.yml`）**：原单测只跑 core:crypto / app:testFull /
      data:repository，新加的 `:core:common`（TOTP/强度 16 例）、`:data:bitwarden`
      （CipherMapper 保真 28 例）从未在 CI 执行 → 纳入门禁（项目反复警告的"改了却
      验证不到"盲区）
- [x] 验证：双 flavor 编译 + 全模块 detekt（0 违规）+ 受影响模块单测（core:common 16 /
      data:bitwarden 28 全绿）

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

- [ ] **真机回归（待用户，装 d689a37 preview）**：新固定签名包上验证 登录(2FA)→同步→
      未命名已修复→快速解锁→**重启后快速解锁不再报登录失效**（登录失效修复验收）→
      锁屏后生物识别重开→设备管理可见；同步编排行为（状态条/手动刷新/回前台自动
      同步/库列表状态行）、强度条、**载荷保真（编辑含网址/TOTP 条目后字段仍在；
      卡/身份/SSH 条目改名不丢字段）**、回收站（恢复/永久删除）、移除库入口
- [ ] 移除库入口 / 回收站视图的最终目测（d689a37 已含，回归时顺带）

## P2 · 自动化（WorkManager 周期同步归位）

- [ ] **WorkManager 周期同步 + 网络约束（推迟理由见 decisions「周期同步 M1 判推迟」）**：
      进程存活时 APP_RESUME/PAGE_ENTER 已覆盖「打开即最新」；进程被杀后会话密钥在
      内存（重启必锁），周期任务无可同步内容 → 收益≈0。编排器 PERIODIC 触发已预留，
      P2 与本地 KDBX 队列推送一并设计时再启用

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

