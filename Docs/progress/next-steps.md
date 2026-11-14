# 下一步任务清单

> 2026-11-10 · **入口文件**：只放「当前状态 + 待办 + 归档索引」。
> 已完成的历史按时间段归档（见文末），**需要追溯时再打开，不要通读**。
> 上一轮完整记录 → [`.ai/README.md`](../../.ai/README.md)「🕐 最新状态」。
>
> ⚠️ `.ai/archive/` 里 9 月的会话记录引用了本文件的若干小节（如「★最高优先」
> 「Bastion 对齐批次」「第二十六轮清单」），那些内容现已归档到 archive-4/5/6 ——
> 按关键词在归档里检索即可，本文件不再承载。

---

## 当前状态（2026-11-12）

| 项 | 值 |
|---|---|
| 版本 | **v0.12.0**（Latest · 混淆包 6.5 MB · versionCode 12000） |
| 上一轮 | UI 精装修：图标 / 底栏玻璃 / 「+」面板 / 卡包 / 库列表切库 / 启动帧 |
| 门禁 | detekt · 编码 · 图标几何 · assembleFullDebug · 单测 · preview 全绿 |
| 整体排查 | 2026-11-11 · [报告](audit/project-audit-2026-11-11.md)：43 个依赖坐标中 **29 项已最新**、3 项禁单独升、**2 类**真违规（订正后）、3 项质量基建缺失（属「要建」） |
| 不需要修 | 2026-11-11 · [判定方案](audit/no-fix-register-2026-11-11.md) + [52 条登记](audit/evidence/no-fix-entries-2026-11-11.md)；门禁 `check_no_fix_register.py` 已接 `ci-docs.yml` |
| 液态玻璃 | 2026-11-11 上午 · **#280 第六轮**（**改了生产代码**）：修 `uGlow` 高斯量纲、表面流光 / 通透厚度搬回绘制层（31+ 全档）、SDF 梯度折射 + 按压透镜、跟手与亮 120 / 灭 360；detekt + 31 条玻璃单测全绿，[接力记录](../../.ai/issues/06-界面与交互-part18.md) |
| 底栏材质 | 2026-11-11 中午 · **#281 材质分档**（**改了生产代码**）：常态改通透磨砂（0.74/0.80、30dp、无 AGSL），液态玻璃回提质感和通透感（24dp、0.50/0.62、内阴影 0.26、rim 0.62、阴影 8dp）；两档共用采样链路，源层不再乘开关；玻璃单测 38 条，[接力记录](../../.ai/issues/06-界面与交互-part19.md) |
| 第五批 UI | 2026-11-12 · **#282 五组反馈**（**全改生产代码**）：底栏去 1dp 细描边 + 液态玻璃「中心更透、边缘更立」（填充 0.44/0.56、clarity 0.13、vignette 0.06、shader 0.075，模糊与阴影不动）；KDBX 云图标统一 `CloudDone`（**形状统一 ≠ 语义合并**）；「+」剪影卡三色系交叉；设置页删 5 处 Spacer 修 PIN 按钮错位 + 精简自动锁定文案 + 三兄弟「取消」改有边框（**反转 #275 规则 1**）；条目列表分组头成块 / 计数胶囊 / 副标题恒定一行 / 类型徽标对齐详情页 / 行尾点缀整组一胶囊（**零新增 State**）；⚠️ **标题补 `maxLines = 1` 是本轮唯一显示行为变更**（密码页原先折行、其余三页早已单行），已单列第 5 条门禁钉住。门禁：**1778 例单测 0 失败**、detekt / `assembleFullDebug` 绿、全套静态门禁绿；`lintFullDebug` 本地红在 42 条**既有**问题（CI 不阻断，见 part20）、[接力记录](../../.ai/issues/06-界面与交互-part20.md) |
| 深色模式专项 | 2026-11-12 · **#283 三组反馈**（**全改生产代码**）：① 主题模式「取消」及同款按钮补 M3 `MinWidth` + 显式 `contentPadding`（#273 的 `IntrinsicSize.Min` 只治竖排没治「贴字小胶囊」），22 个文件 60+ 处动作槽全走共用组件，`DialogShell` 走 `FlowRow` 刻意不动；② 站点计数去掉 `tertiaryContainer` 胶囊（**反转 #282 判据**）退为 `onSurfaceVariant` 纯文字；③ 深色剪影卡补 `onXxxContainer` 配对 + 叠色画纯，底栏按 Material / Apple / 玻璃拟态三路取证引入 `glassLighting()` 三档（`sheenScale 0.35` / `shadeScale 0.45` / 外影 `8→2dp`），8 处白高光 + 3 处黑暗部统一走 `sheenAt` / `shadeAt`。门禁：**1785 例单测 0 失败**、detekt / `assembleFullDebug` 绿、17 项静态门禁 + 13 项自测全绿；[接力记录](../../.ai/issues/06-界面与交互-part21.md) |
| 按钮规格 / 通行密钥 | 2026-11-12 · **#284 两组反馈**（**全改生产代码**）：① 反编译取证推翻 #273/#283 的 intrinsic 根因（material3 `AlertDialogFlowRow` = 纯 `FlowRow`、Dialog 全家零 `IntrinsicSize`），删固定宽度回归原生 M3 wrap-content（**反转 #283 规则 4**、#282 边框判据不动、7 处注释订正）；② 排查结论 **KDBX 能存通行密钥**（codec → writer 加密字段 → mapper 读回、仓储已放行），管理页结构完整，但 `toCredential()` 五项必填而手工录入不产出 `userHandle` ⇒「存了读不回来」，修法 `WebAuthn.randomUserHandle()` 兜底 + `userName` 必填 + 补齐元数据 + 删死代码，三层测试闭环。门禁：**1791 例单测 0 失败**、detekt / `assembleFullDebug` 绿、16 个 `check_*.py` + 13 条自测 + 编码 / 图标几何 / 探针共 32 项全绿；[接力记录](../../.ai/issues/06-界面与交互-part22.md) |
| 六组 UI / 主题 | 2026-11-12 · **#285 六组反馈**（**全改生产代码**）：① 网盘账号卡从「一组多行」壳改为一个账号一张紧凑卡（状态徽标收进卡内、两枚同规格 M3 按钮、同款圆角）；② **反转 #282 分组头成块**，改无背景 section header + 三档间距（组间 20 > 组内 4 > 头与首条 2dp）；③ 详情页 `TopBarTitle.singleLine` 只走缩字号 / 省略号；④ **作废 #282/#283 的 `xxxContainer` 交叉 + alpha 分档**，剪影卡改 M3 fixed accent roles（浅深同值、删光 7 个 alpha 常量与深浅判档）；⑤ 反编译取证补齐 12 个 fixed roles + 三处 `dynamicColor` 默认值统一 `false` + `supportsDynamicColor()` 单一真源让低版本禁用 + OLED 纯黑压整套 9 档 surface；⑥ KDBX 顶栏统一显示 `KDBX`（`vaultTopBarTitle` 单一真源，列表状态行仍带文件名）。门禁：**1806 例单测 0 失败**、detekt / `assembleFullDebug` 绿、33 项静态门禁 / 自测全绿；[接力记录](../../.ai/issues/06-界面与交互-part23.md) |
| 网盘账号注销闪退 / 资源 ID | 2026-11-13 · **#290 点「注销」网盘账号即闪退**（**改了生产代码**）：真机日志 `0.13.0-dev-731cdae` 两次 FATAL `Resources$NotFoundException: String resource ID #0x0`，栈在 `NoticeSnackbar` → `stringResource(0)`，经 `Recomposer.performRecompose` ⇒ **Compose 重组期异常**，`launchGuarded` 兜不住。根因：`CloudAccountsViewModel` 11 条 `const val SETTINGS_CLOUD_* = R.string.*` 被 Kotlin 内联成 **0**（AGP 编译期 R 类 `0x0` 占位、运行时才 `0x7f0f01xx`；`javap -constants` 11 条全 `= 0`），`strings.xml` / `resources.arsc` 文案都在 ⇒ 不是资源被裁。修法：① 11 条改普通 `val`（`<clinit>` `getstatic R$string.* → putstatic`，`javap -c` 验证）；② 抽 `safeStringRes()` 认 `Resources.ID_NULL`，`NoticeSnackbar` 与 `CloudError.Res` 两处变量式解析都过它；③ 无效 id 也 `onConsumed()`；④ `CloudError.Res` 降级 `error_unknown_no_detail`、抽 `cloudErrorText()` 压回 detekt 圈复杂度 14。⚠️ **订正 #289**：`probeWebDav()` 成功同样下发 0 ⇒ 两次闪退同源，`launchGuarded` 仍必要但不是当时那次闪退的根因。含 6 条坑，新建 `CloudAccountsResourceIdRulesTest` 4 条 + `CloudAccountsResourceIdBehaviorTest` 5 例，反证 `const val` 回退 3 例红 / 撤守卫 2 例红；门禁：**1860 例单测 0 失败**（186 份 XML）、`detekt` + `test` + `assembleFullDebug` `BUILD SUCCESSFUL in 1m 52s`、33 项静态门禁全绿；CI：`5553bda4` → `Android CI (Debug)` success（约 4m36s，两 job 全绿）、preview Release `Vaultix v0.13.0-dev-5553bda · Preview`（`Vaultix-v0.13.0-dev-5553bda-preview.apk` 35.8MB）；[接力记录](../../.ai/issues/06-界面与交互-part28.md) |
| WebDAV 闪退 / 异常边界 | 2026-11-13 · **#289 连上 WebDAV 后闪退、重进账号已添加**（**改了生产代码**）：「账号已添加」= 凭据已落盘 ⇒ 崩溃点在**成功之后**（`_state.update` → `refresh()` → `CloudAccountInventory.list()`：Room Flow + Keystore 解密 + MSAL 会话枚举）；工程**没有全局 `CoroutineExceptionHandler`**（#98 实测）、两个 ViewModel 全是裸 `viewModelScope.launch` ⇒ 任一处抛出即**进程退出**，且凭据写入 / 自检 / 清单读取全压在默认主线程。修法：两个 ViewModel 共 10 个动作收口 `launchGuarded`（`runCatching { }.onFailure { }` + 取消 / `Error` 原样重抛 + 异常转可见错误态与 `VaultixLog.e`）、重 IO 显式 `withContext(Dispatchers.IO)`（⚠️ 不在统一入口改 dispatcher，MSAL 必须主线程）、`probeWebDav()` 内部两处 `runCatching` 也区分取消以免误删凭据、添加页四动作同步收口。含 6 条坑（无全局兜底时裸 launch = 进程退出 / detekt `TooGenericExceptionCaught` / 设置页中文日志 label 被判 UI 硬编码 ⇒ 10 个改 ASCII / `runCatching` 吞 `CancellationException` / 统一入口别切 dispatcher / 沙箱 `local.properties` 丢失报 `SDK location not found`），新建 `CloudAccountsCrashGuardTest` 4 例 + `WebDavLaunchGuardRulesTest` 4 条，反证撤兜底后 2 例变红。门禁：**1851 例单测 0 失败**（184 份 XML）、detekt / `assembleFullDebug` 绿（`BUILD SUCCESSFUL in 3m 19s`）、全套静态门禁全绿；CI：`731cdaee` → `Android CI (Debug)` success（约 17m，两 job 全绿）、`Sandbox unit tests` 3m10s，preview Release `Vaultix v0.13.0-dev-731cdae · Preview`（`Vaultix-v0.13.0-dev-731cdae-preview.apk` 37.6MB）；[接力记录](../../.ai/issues/06-界面与交互-part27.md) |
| WebDAV 连接 | 2026-11-13 · **#288 坚果云 403「登录不上去」**（**改了生产代码**）：真机「测试连接 → WebDAV 账号或密码不正确（HTTP 403）」。匿名 / 假 Basic 凭据 × 4 方法 × 3 UA 差分实测 **12/12 全 401**（`WWW-Authenticate: Basic realm="nutstore"`）⇒ 用户带凭据拿到的 403 是**认证已通过后的服务端拒绝**（官方 FAQ：微信二次验证 / 访问频率 / 应用密码被撤销 / 目录权限），旧实现把 401/403 合并成「密码错」把用户锁死在重输密码上；**证伪上一轮 P0**（`accessTokenForHost()` 对陌生 host `return null`）但顺手修掉。四处修法：① `testConnection()` 主探 `PROPFIND Depth: 0`（`405/501` 才降级 HEAD，与 `listChildren()` 同源，404 不再假报成功），两条路径共用 `probeFailureOf()` 判据表；② 401/403 分流到 `credentialsMessage` / `forbiddenMessage` 两处真源，**`errorMessage()` 也分流**（stat/read/list/强写共同出口），403 明写「不是密码错」+ 四条可排除原因；③ 密码框下加「应用密码 / 关二次验证」提示；④ 新增 `webDavProductionClient(shared)`（`newBuilder()` 共享连接池、清 interceptors / networkInterceptors / Authenticator），`CloudAccountConnector` 与 `KdbxCloudSyncCoordinator` 两个构造点都过它，并订正三处过时 KDoc。含 6 条坑（403≠401 / 自检与使用同源 / 分类放共同出口 / **Kotlin 块注释可嵌套** / `forEach {}` 要 `}` 闭合 / detekt `ThrowsCount` 与 `MaxLineLength`），新建 `WebDavConnectionTest` 7 例 + `WebDavConnectionRulesTest` 5 条，反证撤 `interceptors().clear()` 后 P0 用例变红。门禁：**1843 例单测 0 失败**（182 份 XML）、detekt / `assembleFullDebug` 绿、33 项静态门禁全绿；CI：`f5635822` → `AI docs consistency` success（10s）、`Android CI (Debug)` success（4m23s，两 job 全绿），preview Release `Vaultix v0.13.0-dev-f563582 · Preview`（`Vaultix-v0.13.0-dev-f563582-preview.apk` 37.6MB）；[接力记录](../../.ai/issues/06-界面与交互-part26.md) |
| 写回持久性 | 2026-11-12 · **#287 协程取消 / 写回持久性修复**（**改了生产代码**，判据第三次反转）：用户补充「点保存停 5 秒看到『已保存到本地 · 正在同步到云端』才生效，大部分人点完就返回」⇒ 根因**不是 #286 的同步闸门**，而是更前面：`mutateLocked` 已把新会话换进内存（本地立刻可见），`persist()` 旧顺序先跑 `Kdbx.verifyWrite()`（KDF 往返 1~5 s）再写脏缓存，用户在这几秒返回 ⇒ `viewModelScope` 被 `onCleared()` 取消 ⇒ `fileCache.save` / `markLocalEdited` / `autoUploader.enqueue` 全没跑 ⇒ DB 仍 `IN_SYNC`、缓存无脏标记 ⇒ #286 双真源两个都干净 ⇒ 点同步放行拉远端旧字节 `replaceSession`，改动消失。修法四道防线：① 八个写动作共用的 `serialWrite` 改 `withContext(Dispatchers.IO + NonCancellable) { writeGate.withLock { } }`（`withContext` 必须在 `withLock` **外**，等锁期也不许取消）；② 脏缓存**提到几秒自检之前**先落；③ 新增 `cacheRestore` 快照，失败恢复缓存 + 回滚会话，`CancellationException` 只重抛不跑回滚链；④ `updateItem` / `deleteItem` 统一 `launch { withContext(NonCancellable) { } }`（`launch(NonCancellable)` 已 deprecated）。⚠️ 取舍：点完保存立刻锁库 / 切后台时写回仍跑完（几秒），取消半途落盘只会制造三份矛盾的真相。含 6 条坑（deprecated 写法 / `functionBody` 从签名起点截表达式体 / 取消不是失败 / detekt `ThrowsCount` 合并 catch / `runBlocking` 而非 `runTest` / 反证撤 `NonCancellable` 必须变红），新建 `KdbxWriteDurabilityRulesTest` 5 条源码门禁 + `KdbxItemRepositoryTest` 1 条行为测试。门禁：**1827 例单测 0 失败**（179 份 XML）、detekt / `assembleFullDebug` 绿、33 项静态门禁全绿；CI：`7fc7cbc5` → `AI docs consistency` success（12s）、`Android CI (Debug)` success（4m22s，两 job 全绿），preview Release `Vaultix v0.12.0-dev-7fc7cbc · Preview`；[接力记录](../../.ai/issues/06-界面与交互-part25.md) |
| OneDrive 同步 | 2026-11-12 · **#286 数据完整性修复**（**改了生产代码**，非观感问题）：用户报「onedrive 上的 kdbx 改完条目，上传中途返回 / 断网后刷新被云端旧内容覆盖，等于没改成功」⇒ 两个叠加根因：① `persist` 写带 `pendingLocalEdits` 的脏缓存 + `PENDING_UPLOAD`，自动上传一跑就把 DB 状态洗成 `SYNCING`/`FAILED`，而刷新闸门 `impliesLocalChanges` 不认这两个状态 ⇒ 误判「本地没改」→ 拉远端旧字节换会话 → `markResolved` 记已同步 → `fileCache.save` 清脏标记，改动永久消失；② `enqueue` 只在保存那一刻调用，全仓无冷启动 / 恢复补传入口。修法：`hasUnsyncedLocalEdits()`（DB 状态 **或** 缓存脏标记）收敛为唯一判据 + `resolveUsingRemote()` 下载**前 / 后**两道闸门 + `localChangedSinceLastSync()` 委托协调器 + `readRemoteCurrent()` 回写缓存前**现查**脏标记；新增 `resumePendingUploadsNow()` 与 `startUnlockObserver()`（订阅 `Kdbx.sessionRevision()` 按新解锁差集补传）。⚠️ **本轮第二轮补正**：第一版「启动扫一次」在**库还锁着**时是空转 —— `Kdbx.saveVia()` 要 `KdbxSessionStore` 会话，必然 `NotUnlocked` 并写假 `FAILED`；改为只补传已解锁的库（`sessionReady = Kdbx::isUnlocked`），锁态库由解锁事件精准补传。门禁：**1821 例单测 0 失败**（178 份 XML）、detekt / `assembleFullDebug` 绿、23 项静态门禁全绿；CI：`68665533` → `AI docs consistency` success（9s）、`Android CI (Debug)` success（4m51s，两 job 全绿），preview Release `Vaultix v0.12.0-dev-6866553 · Preview`；[接力记录](../../.ai/issues/06-界面与交互-part24.md) |

## 待真机验收（v0.13.0）

> ✅ **2026-11-14 用户真机确认**：「最新版已经不闪退了」⇒ `v0.13.0-dev-5553bda`
> 上 **#290 注销闪退主路径闭环**。下方 #290 细分项（提示文案 / 清单消失 / logcat）
> 未逐项回报，保留供补验；本轮判据以「不再闪退」为准。

- [x] **#290 注销主路径 · 不闪退**（2026-11-14 用户确认）
- [ ] **#290 提示文案**：弹「已删除该 WebDAV 账号的凭据」/「已注销该 Microsoft 账号」，
      账号从清单消失
- [ ] **#290 成功提示可见**：测试连接成功（#289 主路径）弹「连接成功，账号已保存」且不闪退
- [ ] **#290 连续 / 重复操作**：重复点注销、注销后立刻返回、注销后立刻再进页面都不闪退
- [ ] **#290 错误提示路径**：空字段 / 非法 URL / URL 带凭据时红字正常显示
      （`CloudError.Res` 同样走守卫），不闪退
- [ ] **#290 可取证**：logcat 不再出现 `Invalid resource ID 0x00000000` /
      `String resource ID #0x0`

- [ ] **#289 主路径**：生成应用密码 + 关微信二次验证后点「测试连接」，应显示
      「连接成功，账号已保存」且**不闪退**，账号出现在清单里
- [ ] **#289 成功之后立刻返回 / 停留**：两种都不闪退
- [ ] **#289 失败路径**：401 / 403 / 404 / 断网超时分别显示对应红字（沿用 #288 分流
      文案），不闪退，且失败后凭据确实被回滚
- [ ] **#289 添加页**：选账号 → 进子目录 → 返回上层 → 选 `.kdbx` → 输主密码提交，
      任一处故障显示错误态而不是闪退
- [ ] **#289 其余动作**：注销账号 / OneDrive 登录 / 换号 / 健康探测同样不闪退
- [ ] **#289 可取证**：异常时 logcat 能搜到 `CloudAccounts` / `AddCloudVault` 的 ERROR
      及原因（原本是进程直接消失、什么都没有）

- [ ] **#288 主路径**：坚果云网页端生成**应用密码** + 「安全选项」**关闭微信二次验证**后，
      `https://dav.jianguoyun.com/dav/` 点「测试连接」应显示「连接成功，账号已保存」
- [ ] **#288 仍失败时**：红字必须列出 403 的四条原因，界面**不再**说「账号或密码不正确」
- [ ] **#288 账号页 OK 后**，进「添加密码库 → 从网盘添加」能列出该目录下的文件
- [ ] **#288 故意填错密码**应报 401 且提示应用密码，与 403 文案明显不同
- [ ] **#288 地址填不存在的子目录**应提示「找不到该目录」，不假报连上
- [ ] **#288 同机 Vaultwarden + WebDAV**（同域名不同端口）：WebDAV 仍能连上，
      且 NAS 日志里不出现 `Bearer` / `Bitwarden-Client-*` 头
- [ ] **#288 自建 NAS**（群晖 / Nextcloud / Alist）：PROPFIND 与 HEAD 两种响应都要能过自检

- [ ] **#287 主路径「改完立刻返回」**：OneDrive KDBX 改一条 → 点保存 → **立刻按返回退出
      条目页** → 立刻点同步：改动必须被上传 / 或弹冲突，**不能**变回云端旧条目并显示「已同步」
- [ ] **#287 连续编辑**：改完立刻返回、再进再改，连改 3 笔后立刻点同步，三笔都在
- [ ] **#287 保存后立刻杀进程 / 立刻开飞行模式**：改动仍由 #286 补传链路上云；
      提示「已保存到本地」后断网，恢复网络后上传不丢
- [ ] **#287 冲突对话框「用云端覆盖本地」**：仍能真的拉下远端，不被新的不可取消写回自锁
- [ ] **#287 另七个写动作**：删除 / 新建 / 建文件夹 / 重命名 / 恢复 / 永久删除等
      走同一条 `serialWrite`，立刻返回也不丢
- [ ] **#287 自检失败**：人为损坏字节时脏缓存被恢复、会话回滚，条目仍在且不被当成待上传改动

- [ ] **#286 上传中途返回 / 断网后刷新**：OneDrive KDBX 改一条 → 上传中途返回或开
      飞行模式 → 点刷新：必须弹冲突（或明确提示本地有未上传改动），**不能**变回旧条目
      并显示「已同步」
- [ ] **#286 杀进程冷启动补传**：同上改为强杀进程后重开、暂不解锁 ⇒ 改动不被吞；
      解锁该库后应自动补传上云，**无需再点同步**
- [ ] **#286 「从不锁定」/ autofill 恢复会话**：会话被自动恢复时，脏缓存能被存量扫描
      或解锁订阅之一覆盖
- [ ] **#286 冲突对话框「用云端覆盖本地」**：仍能真的拉下远端（两道闸门被
      `discardLocalChanges = true` 跳过），不出现自锁
- [ ] **#286 下载窗口内再编辑**：`readRemoteCurrent()` 那 1–10 s 里再改一笔，
      会话与**缓存**都不被远端旧字节盖掉

- [ ] **#284 取消按钮**：主题模式 / 锁与安全 / 自动锁定 / 剪贴板 / 导入导出等所有「取消」在
      默认 / 2.0x 字号 / 窄屏下，宽度是否与同对话框的「确定 / 保存」及 `DialogShell` 家族一致
      （不再有 64dp 小方块）；长文案按钮是否随文案伸缩
- [ ] **#284 KDBX 通行密钥**：KDBX 库手工录入一条通行密钥后**关库重开**，凭证是否还在；
      系统 Credential Provider 在 RP 未给 `user.id` 时生成的兜底 userHandle 后续断言能否正常回传
- [ ] **#285 网盘账号卡**：浅 / 深 / OLED 纯黑下与设置页其它组卡是否同色阶；「已连接」
      徽标是否读作状态而非固有属性；切换账号 / 注销两枚按钮是否同高同规格
- [ ] **#285 域名分组**：长列表里是否读成「一节」而不是「一条条」；组间 / 组内 /
      头与首条三档间距在 2.0x 字号下是否仍成立
- [ ] **#285 详情页长标题**：`accounts.spotify.com` 等长域名是否单行缩字号 + 省略号、
      不再出现孤字行；库名顶栏长库名仍可折两行
- [ ] **#285 剪影卡配色**：浅色 / 深色 / OLED 纯黑 / 开动态取色四档下，「+」面板 5 张
      类型卡颜色是否有记忆点且不浑浊；文案与剪影线条对比度是否够
- [ ] **#285 主题 / 动态取色**：**卸载重装**（DataStore 无值）首帧是否为品牌浅色板而非
      壁纸色；Android 11 及以下动态取色开关是否禁用并显示说明，不出现「点了没反应」
- [ ] **#285 KDBX 顶栏标题**：KDBX 库条目页左上角显示 `KDBX`、列表状态行仍带
      `xxx.kdbx` 文件名

- [ ] **#283 深色底栏外围**：常态磨砂 / 液态玻璃**两档**在深色、OLED 纯黑、底栏后面正好
      是条目卡片时，还有没有「脏脏的」灰黑晕；白 rim 是否已从「刺眼白边」收敛成「受光」
      （`sheenScale 0.35` → 上沿 0.17 / rim 0.22）
- [ ] **#283 玻璃边缘厚度有没有被一起削薄**：本轮刻意只降**强度**、不降宽度 / 填充 /
      模糊半径 / 内阴影宽度。若觉得「玻璃没了」，请说明是边界还是中心，别只说「又变薄了」
- [ ] **#283 深色剪影卡**：深色 / 动态取色 / OLED 纯黑下五种类型的类型名与剪影线条
      是否清晰；五个色系分不分得开；卡底 `0.94` / 剪影底 `0.92` 是否过浓
- [ ] **#283 取消按钮**：主题模式 / 剪贴板 / 自动锁定 / 导入导出等所有「取消」在
      默认 / 2.0x 字号 / 窄屏下宽度与对齐是否像 M3 按钮；`DialogShell` 三按钮
      是否仍不换行错位（本轮刻意未动其 16dp padding）
- [ ] **#283 计数低调化**：站点名很长、列表滚动时，去掉胶囊后的 `onSurfaceVariant`
      小字还能不能一眼扫到条数；会不会反而找不着
- [ ] **#282 去细边线后的边界**：删掉两档共有的 1dp `style.stroke` 后，3dp 内阴影 + 镜面 rim
      能否单独立住玻璃轮廓；浅色背景下会不会反而读作「没有边」、深色 / OLED 纯黑下会不会
      与背后内容糊在一起。若仍看到一圈细线，先确认是不是 AGSL shader 内 `uLine` 的 1.5px
      暗线（仅 33+ 液态玻璃档），**别把两件事当成一件改**
- [ ] **#282 液态玻璃通透度**：填充降到 0.44/0.56、clarity 提到 0.13 后，是否达到用户要的
      「透明效果」而不再觉得「薄 / 整体效果不强」。⚠️ 这是 #281 回提与 #280 一路调淡之间的
      平衡点，反馈请给方向（更透 / 更实 / 保持）+ 大致档位，别只说「还不够」
- [ ] **#282 图标对比度**：底栏更透之后，常态与选中态图标在浅色 / 深色下是否仍清晰；
      行尾 `surfaceContainerHigh` 胶囊里四枚点缀（同步 / 收藏 / TOTP / 通行密钥）同时出现时
      是否拥挤、是否与站点图标抢视线
- [ ] **#282 动态取色下的剪影配色**：「+」面板三色系（primary / secondary / tertiary 的
      container 交叉）在开启动态取色、换壁纸后是否仍读作「活泼」而不是「花」；
      卡底 alpha 0.68 / 内层 0.62 是否过浓
- [ ] **#282 设置页对齐**：PIN 页删 5 处手写 Spacer 后，三按钮在**默认 / 2.0x 字号 / 窄屏**
      下是否齐平不换行错位；自动锁定精简文案有没有丢掉「离开 App 不锁定」这个关键警示
- [ ] **#281 双材质观感**：常态磨砂在浅色下是否偏灰、深色 / OLED 纯黑下「隐约透出内容」是否到位；
      液态玻璃回提后（填充 0.50 / 0.62、模糊 24dp、内阴影 0.26、rim 0.62、阴影 8dp）
      是否仍觉得「薄 / 整体效果不强」。⚠️ 直接给
      `/workspace/vaultix-dock-material-preview.html` 里四个滑杆的数值（或「再实一档」），
      下一轮按数值固化，不再靠猜
- [ ] **#281 性能**：默认态也开模糊后，滚动列表时整屏离屏拷贝的帧率（低端机 / API 31）；
      静止时不重绘故无拷贝。明显掉帧则考虑给磨砂单独开关或降半径，**不要退回实色**
- [ ] **#280 玻璃第六轮**：真机 AGSL 是否运行时编译通过、`uClarity` / 局部透镜幅度是否合适、
      **API 31–32 实机**点按是否真有流光与通透厚度、按住拖动是否跟手、亮 120 / 灭 360 余辉手感、
      以及按压期间帧率（SDF 梯度只应影响边缘带）
- [ ] 库卡**色调底 0.22** 会不会过浓；面板主卡水印 `alpha 0.13` 是否合适
- [ ] 启动 V 的**显眼程度**（已从 96dp 蓝方块改为单色剪影 + 180ms 淡入）
- [ ] 面板主卡水印与网格卡剪影是否读作**同一个系统**
- [ ] **2.0x 字号**下主卡 + 4 格 + 工具条是否一屏放得下
- [ ] **24dp 通知栏**里折纸 V 是否仍可辨认

---

## 待办

### P1 · 质量基础设施

> **2026-11-11 整体排查已核实**（[报告](audit/project-audit-2026-11-11.md) §3 / 附录 C）：
> Baseline Profile **确认缺失**（全仓零命中）、Gradle 配置缓存 **确认未启用**
> （CI 每条 gradle 命令都带 `--no-configuration-cache`）、Kover **只在根 `apply false`、未接 CI**。
> 旧注释「未逐项核对是否已完成」就此前三项**已作废**；ViewModel 单测仍待核。

> ⚠️ **2026-11-11 补**：任何整体排查 / 依赖升级 / 规范整改**开工前先读**
> [「不需要修」登记表](audit/evidence/no-fix-entries-2026-11-11.md)（52 条，判据见
> [no-fix-register](audit/no-fix-register-2026-11-11.md)）。产出的每条待修项先与登记 ID 比对，
> 命中即引用登记，不要重写理由、更不要重新列成待办（有 `check_no_fix_register.py` 门禁）。

- [ ] **依赖解析结果纳入 CI 门禁**（最高优先，先于任何升级）：施工单见报告附录 C · S1
- [ ] **生产 3 处 `!!` 清零**（P1，会 NPE 崩溃，排在日志之前）：报告 §2.2 / §4 · S3
- [ ] **release 下 6 处绕过 `VaultixLog` 的直接 `Log` 收敛**（P1，由 P0 下调：不崩溃、
      不丢数据，属日志红线与信息泄露风险；`BuildConfig.DEBUG` 由 buildType 决定，不由分支决定）：
      报告 §2.2 / §6 订正
- [ ] Baseline Profile：生成 `baseline-prof.txt`，覆盖冷启动 / 解锁 / 列表 / 详情 + autofill
- [ ] Gradle 配置缓存：先验 `detekt --configuration-cache`，可用再开
- [ ] Kover 覆盖率基线接入 CI（阈值先取实测值，不一步设 80%）
- [ ] `ViewModel 单测` / `:app` 单测用例补强 —— **本轮未核对，保留待核**

### P2 · 已判推迟（不是待办，勿重新排期）

> **WorkManager 周期同步 + 网络约束 —— 已判推迟，收益≈0**
> （[`decisions.md`](decisions.md)「周期同步 M1 判推迟」；登记为 [C01](audit/evidence/no-fix-entries-2026-11-11.md)）：
> 进程存活时 APP_RESUME / PAGE_ENTER 已覆盖「打开即最新」；进程被杀后会话密钥在内存
> （重启必锁），周期任务无可同步内容。编排器 PERIODIC 已预留，
> **触发转修条件**：引入本地 KDBX 离线写队列（有可同步内容），或用户要求后台自动同步。
> `Docs/17` §3.3：禁止常驻轮询。

### 已知未决（接力者注意）

- 快速解锁 payload 在 `SecureCredentialStore`（key `local_unlock_key::<vaultId>`），
  开关在 DataStore；删除 KEK 仅由系统指纹变更触发（单库 disable 只删 payload + 开关，
  多库时 KEK 共享保留 —— 未来多库需「全库清空」入口）。
- 同一服务器仅一个账号（`vaultId = server`）；设备 id 存 `SecureCredentialStore`
  （卸载即换，服务器端会累积旧设备记录，属正常）。
- Detekt 用 `2.0.0-alpha.6`；2.0 稳定后升级需重新生成默认配置核对。

---

## 上游参考候选（2026-11 调研 · **已拍板搁置**，勿当成待办）

> 🔒 **2026-11-14 用户拍板**：「目前的上游改动对我意义不大，先搁置」⇒ 下表**全部不排期**，
> 仅作存档。发版见 `v0.13.1`；本轮未改生产代码、未新增测试。
>
> ✅ **发布结论（真实 run 结果）**：`Android Stable Release` run `43542269744`
> `completed/success`（约 8m）；`Vaultix v0.13.1` 已置 **Latest**，
> 资产 `Vaultix-v0.13.1.apk`（6.6 MB，混淆）+ `checksums-sha256.txt`，
> tag `v0.13.1` → `dd2e845b`，`rele` 与 `main` 齐平（0/0）。发布说明读手写版，
> 签名 / 校验和 / attestation 齐全；详见 `.ai/README.md` 顶部「最新状态」。
>
> 📅 **月度节奏（文档约定，不建定时任务）**：**每月排查一次上游重大更新** ——
> 只筛 major 版本 / 安全公告 / 互操作性硬 bug（如 KDBX 字段生态变化），出一份简报、
> 不动代码；到点由用户提醒执行。**不建 GitHub Actions 定时 workflow、不建自动化任务**，
> 避免为了「自动」引入一套没人读的告警（同 `codeql.yml` 移除 push 触发的判据）。
> 真源是三家官方 release：`bitwarden/android`（移动真源，非已归档的 `bitwarden/mobile`）、
> `Kunzisoft/KeePassDX`、`PhilippC/keepass2android`。
>
> 2026-11-14 按**真机画像**重筛：`HONOR BKQ-AN00 / Android 17 (SDK 37)`、
> OneDrive + 坚果云 WebDAV 上的 KDBX、#285–#290 六轮反馈全部集中在
> 「网盘链路 + 错误 / 崩溃可见性」，零次提及功能不足。
> 全量调研见 `vaultix-upstream-research-2026-11.html`（A 级 5 / B 级 7 / C 级 5），
> 按实机筛选后的决策见 `vaultix-upstream-targeted-2026-11.html`。
> **本节只是登记，未动生产代码、未新增测试、未改版本号**；施工需另起一轮走完整链路。

| 优先级 | 候选 | 落点 / 约束 |
|---|---|---|
| 先做 | **A1** KDBX 通行密钥 BE/BS 改 `0/1` | `KdbxPasskeyCodec.kt:193-194` 仍写 `true/false`；KP2A 1.16-pre1 已定为 `0/1`。写侧改、读侧双兼容、补跨工具往返测试。唯一确认的互操作性硬 bug，改动 2 行 |
| 先做 | **A2** 远端被第三方改动 → 可见且区分成因 | `resolveUsingRemote()` 已有 `stat().versionToken` 与两道脏闸门、`KdbxConflictDialog` 已存在 ⇒ 缺口比全量报告写的窄：只需把文案拆成「别处改过 / 本地未上传 / 两边都改」三种成因，不引入新状态机 |
| 先做 | **B7** 重复 UUID 写回门禁 | `KdbxItemWriter` 补「写前扫重复 UUID 并拒绝 / 重生成」测试，与 #286 同族，成本极低 |
| 待拍板 | **NEW** 崩溃可取证 | #289/#290 连续两轮靠用户手动导日志；全仓无全局异常兜底实装、`VaultixLog` 无文件 sink，`DeveloperLogsDialog` 只能手动抓 logcat。⚠️ **别撞 D07**（不改门面开关、不让 release 打 DEBUG 日志），须先确认是否走 S6 脱敏诊断包路径 |
| 回归 | **A2 做深 / A4′ / A5′** | 主动版本基线比对若碰 `KdbxCloudSyncCoordinator`，必须完整回归 #286/#287 三组真机项；A4′ 只补 TOTP 越权行为测试（**C05 已否掉详情页动态验证码，不得顺带做 next-code 预览**）；A5′ 只留「编辑后 autofill 上下文」「`allowCredentials` 归一化」两条自查，**Android 10 inline 崩溃在 SDK 37 真机不触发，降为发版前兼容自查** |
| 仅登记 | B1 CXP 接口预留 / B3 KDF 1 秒标定 / B4 PRF 预研 / B5 规则数据化 | 依赖 GMS、密码学改造或主线程风险（#289 刚修过主线程 IO），等实测数据再议 |
| 不采纳 | B2 新条目类型 / B6 Tags / C 级全线 | 六轮实机反馈零次提及；#284 已证「存了读不回来」是新类型典型代价，#282/#285 分组判据刚反复调过，叠第二套维度是自找回归 |
| 不采纳 | **A3** 周期后台拉取 | **已判推迟，见 C01 登记，不重列、不重新表述**：进程被杀后会话密钥只在内存、重启必锁 ⇒ 无可同步内容；且 `decisions.md:46` 记录用户真机反馈「自动同步太频繁」后已移除进页 / 回前台自动拉取。KP2A 有离线写队列而 Vaultix 无此前提；#286 的 `pendingLocalEdits` 让触发条件部分逼近，但补传仍受 `sessionReady = Kdbx::isUnlocked` 约束，锁态依旧无可同步内容 |

> ⚠️ 上游 2026 年最密集的更新是「同步完整性 / 写回持久性 / 错误可见性」，
> 与 #286–#290 同一组题。**Vaultix 在双真源 + 不可取消写回 + 写后读回校验上已比两家上游更严谨**，
> 不需要推倒重来；要补的是把已做对的事情变得**可见、可取证**。

---

## 文档债（2026-11-10 盘点）

- [ ] ⚠️ **`Docs/progress/current-status.md` 文末停在 2026-11-01（#218）**：#280 / #281 /
      #282 都没补，但 `.ai/MEMORY.md` 的「接力顺序」仍把它列为第四份必读 ⇒ 接力者可能把
      11-01 的快照当成现状（连 `SESSION-2026-11-11/12.md` 也已不再逐日写，逐轮细节改由
      `.ai/issues/06-界面与交互-partN.md` 承载）。**现状一律以
      `.ai/README.md` 顶部「🕐 最新状态」为准**；后续要么按 part 分篇摘要重写其文末，
      要么把它从「必读真源」降为「历史归档」，别让它半真半旧地挂着。
- [x] **本文件拆分**：3592 行 / 280 KB → 入口 + 6 份归档（本轮完成）
- [ ] 6 个 `SESSION-*.md` 超 300 行（最大 **768 行** = `SESSION-2026-11-03.md`）。
      ⚠️ 记忆明确「存量未回溯合并 ⇒ **不能盲删**」，拆分要逐段回溯，是独立工程。
- [ ] `Docs/` 根目录 19 个设计期文档已滞后于实现（`08-界面设计规格.md` 33 KB）——
      README 已有已知标记；若要处理，应先定「哪几份还算真源」。

---

## 历史归档（按时间倒序，**勿通读**）

| # | 时间段 | 文件 | 规模 |
|---|---|---|---|
| 1 | 2026-11-05 ~ 10-22 | [next-steps-archive-1.md](./next-steps-archive-1.md) | 574 行 |
| 2 | 2026-10-22 ~ 09-29 | [next-steps-archive-2.md](./next-steps-archive-2.md) | 574 行 |
| 3 | 2026-09-29 ~ 09-15 | [next-steps-archive-3.md](./next-steps-archive-3.md) | 567 行 |
| 4 | 2026-09-15 ~ 09-12 | [next-steps-archive-4.md](./next-steps-archive-4.md) | 612 行 |
| 5 | 2026-09-12 ~ 09-11 | [next-steps-archive-5.md](./next-steps-archive-5.md) | 569 行 |
| 6 | 2026-09-11 ~ 09-08 | [next-steps-archive-6.md](./next-steps-archive-6.md) | 565 行 |

> ⚠️ 归档每份 565–612 行，**超 §7.1 的 300 行上限**——这是刻意的：它们是
> 「按时间段完整的完成记录」，对半切会破坏叙事连续性；入口已声明「勿通读」。
> 若确实需要严格 300 行，切点都在 `##` 边界，可直接对半拆。

### 已过期的旧待办（仅留档，原文见 archive-6 尾部）

- ~~`P0 · M1 收尾（剩余）`~~ —— 真机回归清单（装 `f0f5df6` preview）。M1 早已完成。
- ~~`P3 · M2（KDBX）`~~ —— `data:kdbx` 引擎（kotpass）。KDBX 已实现。
- ~~`P2 · 自动化`~~ —— 与上方 WorkManager 那条重复，已合并。
