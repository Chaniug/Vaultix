# 关键决策记录

> 简版 ADR；完整背景见 `docs/adr/`（待建）与各文档正文。

| 日期 | 决策 | 理由 |
|---|---|---|
| 2026-09-07 | **产品定位：Bitwarden 优先（Keyguard 路线）** | Bitwarden 是当前主流；据此 M1/M2 对调 |
| 2026-09-07 | **不引入私有本地库**，用 KDBX 承担本地角色 | 开放标准，避免用户数据锁定；免去第二套本地存储维护 |
| 2026-09-07 | **协议 MIT → GPL-3.0** | 需合规参考/复用 Bastion（GPL-3.0）实现 |
| 2026-09-07 | **`feature/*` 11 个模块暂不拆分** | 代码量小时模块化的收益为负；先按包名组织 |
| 2026-09-07 | 技术栈升级至 Kotlin 2.4.10 / Hilt 2.60.1 / AGP 9.3.2 | 与 Bastion 同代并取其新；AGP 9 有破坏性变更，趁代码少改 |
| 2026-09-07 | **CipherString type 0 默认拒绝解密** | ~~无 MAC 意味着密文可被篡改；需显式 `allowLegacyWithoutMac` 才放行~~ **2026-09-08 反转（真机证据），见下行** |
| 2026-09-08 | **type 0 改为默认放行（官方 / Bastion 对齐）** | 真机库中历史条目（旧客户端 / 导入）大量为无 MAC 的 type 0，此前默认拒绝导致这些条目显示为「未命名」；Bastion 与官方客户端均无 MAC 即直接 CBC 解密。保留 `allowLegacyWithoutMac=false` 逃生阀供严格场景显式拒绝；Docs/03 §2.4 描述以本行为准 |
| 2026-09-07 | **本地不出包，出包交给 GitHub Actions** | 保证产物可复现与签名统一 |
| 2026-09-07 | **只出 arm64-v8a** | 面向现代 64 位设备；省体积、省构建时间 |
| 2026-09-08 | **`VaultEntity.id = 规范化服务器 URL`（M1 简化）** | 与认证层 / 401 刷新器按 server 键控 token 的既有语义一致。代价：**同一服务器仅支持一个账号**；未来多账号需先改凭据键空间（server → account）再放开 |
| 2026-09-08 | **新建条目 = 本地 uuid 临时行 + dirty 队列 + 轻量推送** | Bastion 教训「新建走轻量 POST 不等整库下载」；推送成功后服务端分配的新 id 触发本地行重映射（请求密文即服务端密文，无需重拉）。推送失败条目留队待下次同步 |
| 2026-09-08 | **UI 文案进 `strings.xml`（中文）** | 对齐 Docs/08 §6 本地化方向；例外：同步失败/拦截原因文案由 data 层中文直供（M1 简化，后续收编） |
| 2026-09-08 | **2FA / 新设备 OTP 登录 M1 不支持，UI 明示** | `connect/token` 2FA 分支未实现；错误分类区分 401 / 400 `two_factor` 并给出可执行文案 |
| 2026-09-08 | **软删除 = 回收站语义（Bitwarden 原生）** | 本地行标记 deletedDate（列表立即隐藏）+ SOFT_DELETE 入队推送；服务端 30 天自动清理，UI 文案如实说明 |
| 2026-09-08 | **编辑沿用原条目 id，不做重映射** | PUT /ciphers/{id} 响应无新 id；本地行仅替换密文载荷，revisionDate 等下次全量同步刷新 |
| 2026-09-08 | **自动锁定只做「切后台超时」一档** | 默认 5 分钟（autoLockTimeoutMs），elapsedRealtime 计时（Bastion 实战经验）；「立即锁/屏幕锁」等设置项待设置页 |
| 2026-09-08 | **剪贴板清空 =「触发即忘」+ 清空前校验** | 借鉴 Bastion ClipboardUtils（GPL 溯源标注）；不清掉用户之后复制的新内容；时长取 clipboardClearMs |
| 2026-09-08 | **Detekt 取 2.0.0-alpha.6（dev.detekt）** | 官方兼容表精确对齐 Kotlin 2.4.10 / AGP 9.3 / Gradle 9.5（1.23.x 只到 Kotlin 2.0）；Analysis API 默认开、按 compilation 自动注册；2.0 稳定后升级 |
| 2026-09-08 | **Detekt 阈值 = Docs/16 硬上限，建议值不设门禁** | LongMethod ≤150 / LargeClass ≤1200 / 参数 ≤8 / 单类函数 ≤40（建议 60/600/6/11 仅风格参考）；Compose PascalCase（ignoreAnnotated）与命名参数数字豁免；crypto「有意捕获」用带理由 @Suppress |
| 2026-09-08 | **自动锁档位改为分钟制（对齐 Bastion autoLockMinutes）** | `0`=切后台立即锁；`>0`=离开 N 分钟锁（默认 5）；`<0`=从不自动锁；回前台时设备屏幕仍 keyguard 锁定 → 立即锁（Bastion「屏幕锁定时必须重新验证」）；判定逻辑抽纯函数 `AutoLockPolicy` 可单测。Vaultix 密钥只在内存 → Bastion 的「-2 重启后锁定」无需建模 |
| 2026-09-08 | **Hilt 断环：Authenticator 注入 `Provider<TokenRefresher>`** | OkHttpClient→Authenticator→TokenRefresher→AuthRepository→ApiFactory→Retrofit.Builder→OkHttpClient 构造期环（此前未被任何注入点展开，ViewModel 注入点出现后暴露）；401 回调时才解析 refresher，彼时认证仓库必已构造完成 |
| 2026-09-08 | **本地快速解锁（Keystore 包裹，Bastion/官方模型）** | 登录成功后用 Keystore user-auth KEK 包裹账号对称密钥落盘；锁库只清内存；再开 = 生物识别/设备 PIN 本地解封（离线、免主密码、免 2FA）。API 30+ 支持 DEVICE_CREDENTIAL，26-29 仅强生物识别；指纹变更自动失效回退主密码 |
| 2026-09-08 | **设备登记走 HTTP Header（修正）** | connect/token 的 device-type/device-identifier/device-name 仅放 body 时服务器端设备管理不认（真机验证）；Header + body 双放（Bastion 实测组合）；deviceType 0=Android（原误用 1=iOS） |
| 2026-09-08 | **preview 只发 full 一个 debug 包** | offline 分发（仅 KDBX）M2 前无功能；保留 offline 编译验证防 flavor 退化（**2026-09-09 已收紧，见下行**） |
| 2026-09-09 | **只构建/发布 full；offline flavor 暂停参与构建（用户拍板）** | KDBX 引擎（`data:kdbx`）属 P3 待办，现在出 offline 包是空壳（装上没有本地库可用）；双 variant 编译让 CI 时间与缓存空间翻倍。CI 收敛为 `assembleFullDebug` / `lintFullDebug` / `assembleFullRelease`；**flavor 定义与 `AppFlavor` 分支代码保留在仓库**，待 Bitwarden 收尾后决定是否做纯本地版，届时加回 `compileOfflineDebugKotlin` / `lintOfflineDebug` / `assembleOfflineRelease`（恢复点已写在 workflow 注释里） |
| 2026-09-08 | **签名 Secrets 修复（根因 2 条，CI 已全绿）** | ① Secrets 不自动进 env：必须 step env 显式注入（此前 `${SIGNING_STORE_PASSWORD}` 恒空 → 一次性密钥）；② PKCS12 jks 私钥密码 = store 密码，KEY_PASSWORD 传同值（独立随机 keypass 被 keytool 忽略导致 AGP 读 key 失败）。判定只看 `##[notice]固定密钥`/`##[warning]一次性` 行，勿信脚本回显 |
| 2026-09-08 | **Bastion 冻结为 reference implementation；Vaultix = 唯一演进线** | Bastion 仍有人使用，代码与 GitHub 均不再改动；新功能/重构/修复全部在 Vaultix 进行。参考索引见 `Docs/18-Bastion参考地图.md` |
| 2026-09-08 | **从 Bastion 只搬三类资产，不做文件级搬迁** | Bastion 主源码约 664 文件 / 25.8 万行（单模块 `:app`），直接灌入 13 模块架构会重演纠缠；只搬 ①行为知识 ②测试向量与保真矩阵 ③无依赖的核，逐功能在 Vaultix 重写，以"对拍清单"验收（流程见 `Docs/18-Bastion参考地图.md` §5） |
| 2026-09-08 | **同步请求统一走编排器，UI 不再直调 syncVault** | 触发分类（MANUAL/PAGE_ENTER/APP_RESUME）→ 节流/解锁门卫/失败退避/状态流由 `BitwardenSyncOrchestrator` 单点保证；提示条语义：静默自动成功不打扰，手动成功短暂提示，最近错误常驻可重试；库列表行内仅显示运行中/失败（Bastion 静默同步语义） |
| 2026-09-08 | **新建/编辑密码框加强度条（提示非门槛）** | 评分卡 `PasswordStrength`（Bastion 移植，core:common，0–100 五档）；仅 UI 提示不拦截保存（安全规则属后续「设置→安全规则」范围） |
| 2026-09-08 | **测试注入口走 internal 次构造（Hilt 双构造模式）** | @Inject 构造只能含可绑定参数（VaultRepository/VaultSessionManager）；scope/config/now 等测试参数（虚拟时间/调度器）放 internal 完整构造，同模块测试可见、生产不可误用 |
| 2026-09-08 | **条目更新 = 合并上传，绝不整条重写（对齐审计批 1）** | `toUpdateRequest(item, stored, key)`：只重加密可编辑明文（name/notes/login 的 username/password），uri/totp/fido2/card/identity/sshKey/secureNote/fields 未编辑段**沿用服务端原密文**随请求提交——修复「编辑登录条目丢网址/TOTP、编辑卡/身份/SSH 条目毁载荷」的数据丢失（审计 M1-1/2） |
| 2026-09-08 | **写路径类型守恒守卫** | type5=sshKey 显式建模；未知类型（未来 type>5）在领域层按 Login 展示但更新前校验 `existing.type == serverTypeOf(item.type)`，不一致即拒存（防 type 漂移改写服务端条目）；UI 对非 Login 类型编辑只开放名称/备注并明示 |
| 2026-09-08 | **全量同步后收敛服务端已移除行 + flush 4xx 弃单** | 拉取成功后删除本地「不在服务端集合且无 pending ops」的 cipher/folder 行（排除离线未推送数据；Bastion deleteNotIn 语义）；上传遇 4xx（401/408/429 除外）视为服务端目标已不存在 → 永久弃单，本地行由下次全量同步裁决——修复毒丸条目卡死队列（审计 M1-5/6） |
| 2026-09-08 | **回收站视图 = 本地先行 + dirty 队列（恢复/永久删除）** | 恢复：本地清 deletedDate（主列表立即出现）→ RESTORE 入队补推；永久删除：DELETE 入队 → 本地行立即移除（离线时队列联网补推，服务端 404 弃单）。observeItem 对已删除行视同不存在（详情 null 语义不变）；回收站行随全量同步收敛（服务端 30 天清除 / 永久删除后消失） |
| 2026-09-08 | **请求预挂 Bearer + 过期前预刷新 + 刷新失败三分（登录失效修复，Bastion 对齐）** | 真机高频「登录失效」三根因：请求从不带 Authorization（每次 401 再刷新）、host→server 登记仅登录时（重启+快速解锁后必失效）、刷新失败不分类（CF 403/网络抖动误报失效）。修复：拦截器按 host 预挂 Bearer（expiresIn 落盘、到期前 60s 预刷新，Bastion accessTokenExpiresAt 语义）；refresh 结果三分——400/401=Invalid（重登）/403/429/5xx/网络=Transient（保留登录态，绝不踢重登）；sync 层 401 按最近刷新类型归类 |
| 2026-09-08 | **移除库 = 本地数据全清 + 凭据登出（云端不动）** | ⋮ 菜单 + 二次确认；清内存会话 → 快速解锁痕迹 → authRepository.logout（token/登记）→ 待推送队列（先于删行，防同服务器重加账号误推旧队列）→ vault 行级联删 ciphers/folders |
| 2026-09-08 | **KSP2/工程经验：KDoc 别写字面 `/**`；@Provides 签名用接口类型** | KDoc 内 `/api/**` 中的 `/*` 开启嵌套块注释 → 外层注释到 EOF 未闭合，KSP 报出误导性的「类无法解析」连锁错误；另 KSP2 对 @Provides 签名中的具体新类解析有 bug → 返回类型用接口（如 okhttp3.Interceptor），具体类在函数体内构造 |
| 2026-09-08 | **WorkManager 周期同步 M1 判推迟（P2 归位）** | 进程存活时 APP_RESUME/PAGE_ENTER 已覆盖「打开即最新」；进程被杀后会话对称密钥只在内存（重启必锁），周期任务没有可同步的解锁会话 → 收益≈0（Bastion 周期同步服务的是其本地库离线队列，Vaultix 架构无此前提）。编排器 PERIODIC 触发已预留，待 P2 与本地 KDBX 队列推送一并设计 |
| 2026-09-08 | **同步触发策略收敛：自动同步只在本地修改后触发** | 真机反馈「自动同步太频繁」→ 移除 PAGE_ENTER（进页）与 APP_RESUME（回前台）自动拉取；自动同步 = 本地修改（保存/删除/恢复/永久删除 → dirty 队列 flush 即时推送）；拉取统一走**手动**（条目页顶栏按钮 + 下拉刷新 = MANUAL force）；失败自动重试（RETRY）保留；编排器 PERIODIC/门卫仍预留 |
| 2026-09-08 | **⚠️ 策略变更：Bastion 代码与 UI 分批整体搬入（推翻「不做文件级搬迁」）** | 用户拍板：保持 Vaultix 架构（多模块 / Bitwarden canonical / CipherDto 密文存储），把 Bastion 设置/密码条目/验证码/通行密钥/卡包的代码与 UI 先搬过来再改。硬边界：**数据模型与 Room 明文表不搬**（保住 M1 保真成果），包名/模型/DI/偏好键必替换，GPL 溯源声明必须保留。顺序：密码条目 → 验证码 → 设置 → 通行密钥 → 卡包 |
| 2026-09-08 | **字段写入语义 = 按表单意图覆盖（folderId/favorite/reprompt/fields/card/identity）** | 此前这些字段 toUpdateRequest 固定沿用 stored 旧值 → 用户在 Vaultix 里改了也不上传（等于不可编辑）。统一改为「表单加载完整条目 → 编辑 → 按表单意图重加密写回」；未编辑段（sshKey/secureNote/通行密钥等）仍沿用原密文。凡新增可编辑字段必须走该语义并配「往返不变」单测 |
| 2026-09-08 | **linkedId 采用 Bitwarden 官方分段编码（100 登录 / 300 卡 / 400 身份）** | 此前误用顺序编号 1/2/3/4，服务端 linkedId=100 匹配不到 → Linked 字段退化「未知关联字段」。core:model 新增 VaultLinkedId 枚举（27 值 + 容错解析）。**原则：对齐字段优先查 Bitwarden 官方（bitwarden/clients / 官方 SDK），Bastion 仅参考**——其自定义字段只有 3 态且 folder/favorite 绕了自己的中间字段 |
