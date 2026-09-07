# 下一步任务清单

> 更新于 2026-09-08。P0 最小闭环已打通（添加库 → 解锁 → 条目列表 → 新建条目）。
> 状态：`TODO` / `DOING` / `DONE` / `BLOCKED`

## 已完成（本轮 2026-09-08，供对照）

- [x] **库注册 + 会话管理**：登录成功写 `VaultEntity`（v2 加 `account` 邮箱列，
      Migration 1→2）；`VaultSessionManager`（内存持有对称密钥、lock 清零、幂等）
- [x] **data:repository 落地**：`VaultRepositoryImpl` / `ItemRepositoryImpl` +
      domain 接口（`domain` 首个真实代码）；5 个会话单测通过
- [x] **UI 最小闭环**（app 内按包组织）：库列表（空态/锁定徽标）→
      连接 Bitwarden 表单（自托管地址 + 2FA/凭据/网络错误分类文案）→
      解锁页 → 条目列表（空态、同步提示条、手动同步、立即锁定）→ 新建条目对话框
      （`CipherMapper.toRequest` → 密文行 + dirty 队列 → 轻量推送）
- [x] **新建条目推送换 id 重映射**：服务端 `POST /ciphers` 分配新 id 后本地行
      按服务端 id 重建（密文来自请求体，无需重拉），避免全量同步后双行
- [x] 双 flavor 编译通过（full / offline；offline 隐藏 Bitwarden 入口）
- [x] 大标题随滚动缩放（M3 `LargeTopAppBar` + nestedScroll）已应用在库列表与条目列表

## P0 · M1 收尾（当前主线，还剩这些）

- [ ] **条目编辑/删除入口**：详情页（S9）或先做编辑表单（S10），复用
      `toRequest`；编辑 = UPDATE op 入队（注意：更新**不**换 id，直推 `PUT`）
- [ ] **自动锁定**：`AppLifecycleObserver`（后台超时）+ 切后台即锁，
      驱动 `VaultSessionManager.lockAll()`（Docs/10 §4）
- [ ] 解锁/2FA 语义细化：Bitwarden 新设备 OTP / TOTP 2FA 登录（M1 目前提示不支持）
- [ ] 移除库（本地记录）入口与二次确认
- [ ] UI 文案后续迁 `strings.xml` 对照检查（本轮已用资源；sync 提示文案暂由
      data 层中文直供，见 decisions）

## P1 · 质量基础设施（趁代码量还小）

- [ ] **Detekt**：启用 LongMethod/TooManyFunctions/LongParameterList/LargeClass
- [ ] **Baseline Profile**（启动与首次滚动性能）
- [ ] Gradle 配置缓存（注意：lint 需 --no-configuration-cache，见 CI 修复 #6）
- [ ] data:repository / data:bitwarden 其余单测（VaultRepositoryImpl 分类逻辑等）

## P2 · 自动化

- [ ] WorkManager 周期同步 + 网络约束（`Docs/17` §3.3：禁止常驻轮询）

## P3 · M2（KDBX）

- [ ] `data:kdbx` 引擎（kotpass），按 `Docs/02` §3.4 做往返保真度测试

## 已知未决（接力者注意）

- `VaultEntity.id = 规范化服务器 URL`（M1 简化：**同一服务器仅支持一个账号**，
  token/refresher 亦按 server 键控）；多账号同服务器需先改凭据键空间，见 decisions
- `VaultItem` 仍是雏形（6 字段：+password），与 `Docs/02` 超集模型差距大；
  补字段时同步补 Mapper（密码字段已双向）
- 同步编排刻意未做节流/优先级/被动同步（M1 不需要，勿过度设计）
- 新建条目「推送成功但响应丢失」的极端情况可能产生服务端重复条目（重试重发），
  暂无幂等键，量级可接受，勿过度设计
- UI 暂无搜索/筛选/多选/详情页（最小闭环口径，用户已确认）
