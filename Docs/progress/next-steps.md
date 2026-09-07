# 下一步任务清单

> 更新于 2026-09-08（第二轮）。P0 已全部落地：详情/编辑/删除 + 自动锁定 + 复制。
> 状态：`TODO` / `DOING` / `DONE` / `BLOCKED`

## 已完成（本轮 2026-09-08 第二轮，供对照）

- [x] **条目详情页（S9 最小版）**：条目行点击进入；分区卡片（用户名/密码/备注），
      密码显隐 + 等宽；不存在/已删除态
- [x] **敏感复制 + 剪贴板自动清除**：`VaultixClipboard`（借鉴 Bastion ClipboardUtils：
      「触发即忘」延迟清空、清空前校验内容未被改写、API 33+ IS_SENSITIVE），
      复用 `clipboardClearMs` 偏好（默认 30s），Snackbar 反馈含清除秒数
- [x] **条目编辑（S10 最小版）**：详情页编辑按钮 → 预填表单（与新建共用
      `ItemFormDialog`）→ `ItemRepository.updateItem`：沿用原 id/revisionDate/
      folderId/favorite，密文覆盖本地行 → UPDATE 入队 → 轻量推送（PUT）
- [x] **删除（软删除 = 回收站）**：二次确认（含回收站 30 天提示）→ 本地行标记
      deletedDate（列表立即隐藏）→ SOFT_DELETE 入队 → 推送（DELETE /ciphers/{id}）
- [x] **自动锁定**：`AutoLockController`（ProcessLifecycleOwner）+ 切后台计时
      （elapsedRealtime）、回前台超时 ≥ `autoLockTimeoutMs`（默认 5 分钟）即
      lockAll + 锁定代次事件强制导航回库列表根
- [x] data:repository 新增 6 个写路径/解密流单测（共 11 个通过）
- [x] 双 flavor 编译通过

## P0 · M1 收尾（剩余）

- [ ] **2FA / 新设备 OTP 登录**（当前 UI 明示不支持；需 identity 层 two-factor
      分支 + 输入步骤 UI，参考 Bastion BitwardenLoginScreen 交互，勿整搬）
- [ ] 移除库入口（含二次确认：仅移除本地记录）；设置页骨架（自动锁定时长、
      剪贴板清除时长目前无 UI 可调，先提供设置页最小版）
- [ ] 解锁页 5 次失败冷却 30s（Docs/08 S6 规格）
- [ ] 回收站视图（软删除条目的恢复 / 永久删除）
- [ ] UI 文案抽查迁 `strings.xml`（详情/编辑已用资源；sync 提示文案仍由 data 层中文直供）

## P1 · 质量基础设施

- [ ] **Detekt**：启用 LongMethod/TooManyFunctions/LongParameterList/LargeClass
- [ ] **Baseline Profile**（启动与首次滚动性能）
- [ ] Gradle 配置缓存（注意：lint 需 --no-configuration-cache，见 CI 修复 #6）
- [ ] ViewModel 单测（ItemsViewModel / ItemDetailViewModel 用 fake repository）

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
