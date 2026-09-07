# 下一步任务清单

> 更新于 2026-09-08（第三轮）。P0 全部落地 + Detekt 门禁上线。
> 状态：`TODO` / `DOING` / `DONE` / `BLOCKED`

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

- [ ] **2FA / 新设备 OTP 登录**（当前 UI 明示不支持；需 identity 层 two-factor
      分支 + 输入步骤 UI，参考 Bastion BitwardenLoginScreen 交互，勿整搬）
- [ ] 设置页最小版（自动锁定时长 / 剪贴板清除时长目前无 UI 可调）+ 移除库入口
      （二次确认：仅移除本地记录）
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
