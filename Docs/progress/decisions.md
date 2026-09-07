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
| 2026-09-08 | **preview 只发 full 一个 debug 包** | offline 分发（仅 KDBX）M2 前无功能；保留 offline 编译验证防 flavor 退化 |
| 2026-09-08 | **签名 Secrets 修复（根因 2 条，CI 已全绿）** | ① Secrets 不自动进 env：必须 step env 显式注入（此前 `${SIGNING_STORE_PASSWORD}` 恒空 → 一次性密钥）；② PKCS12 jks 私钥密码 = store 密码，KEY_PASSWORD 传同值（独立随机 keypass 被 keytool 忽略导致 AGP 读 key 失败）。判定只看 `##[notice]固定密钥`/`##[warning]一次性` 行，勿信脚本回显 |
| 2026-09-08 | **Bastion 冻结为 reference implementation；Vaultix = 唯一演进线** | Bastion 仍有人使用，代码与 GitHub 均不再改动；新功能/重构/修复全部在 Vaultix 进行。参考索引见 `Docs/18-Bastion参考地图.md` |
| 2026-09-08 | **从 Bastion 只搬三类资产，不做文件级搬迁** | Bastion 主源码约 664 文件 / 25.8 万行（单模块 `:app`），直接灌入 13 模块架构会重演纠缠；只搬 ①行为知识 ②测试向量与保真矩阵 ③无依赖的核，逐功能在 Vaultix 重写，以"对拍清单"验收（流程见 `Docs/18-Bastion参考地图.md` §5） |
