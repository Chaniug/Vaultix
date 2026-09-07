# 下一步任务清单

> 更新于 2026-09-07 晚。M1 数据链路已全通，剩余工作如下。
> 状态：`TODO` / `DOING` / `DONE` / `BLOCKED`

## 已完成（本轮，供对照）

- [x] `core:crypto`（172 例测试，行覆盖 91.4%，Argon2 向量经 argon2-cffi 校准）
- [x] `core:database`（Room：vaults/ciphers/folders/pending_ops，只存密文）
- [x] `core:datastore`（DataStore 设置 + Keystore 安全凭据）
- [x] `data:bitwarden`：Identity+Vault API、DTO、Json 容错、OkHttp（30s/401/防死连接）
- [x] 认证链路：prelogin→KDF→hash→token；refresh 用 Mutex 串行；host→server 映射
- [x] 同步编排：预检 revision→全量→空库保护→落库；dirty 队列推送
- [x] 解密链路：unpackAccountKey（stretch→解包→清零）+ CipherMapper 双向映射
- [x] CI 修复 7 处（SDK 37 / 多 flavor / 触发盲区 / BOM），push 与手动触发均绿

## P0 · M1 收尾（当前主线）

- [ ] **UI 层接入**：登录页、库列表、条目列表（空态/错误态）
      交互基线见 `Docs/16` §4：顶部大标题随滚动缩放、功能折叠进标题、沉浸式半透明，
      必须由滚动位置驱动（NestedScrollConnection/ScrollState）
      先研究 Bastion 的实现（用户亲手打磨，可直接借鉴交互），再决定移植或重写
- [ ] **条目创建/编辑入口**：调 CipherMapper.toRequest → 入 dirty 队列
- [ ] **解包密钥的会话管理**：unlock 后的 SymmetricCryptoKey 放哪、锁定时如何清零

## P1 · 质量基础设施（趁代码量还小）

- [ ] **Detekt**：启用 LongMethod/TooManyFunctions/LongParameterList/LargeClass
- [ ] **Baseline Profile**（启动与首次滚动性能）
- [ ] Gradle 配置缓存（注意：lint 需 --no-configuration-cache，见 CI 修复 #6）

## P2 · 自动化

- [ ] WorkManager 周期同步 + 网络约束（`Docs/17` §3.3：禁止常驻轮询）

## P3 · M2（KDBX）

- [ ] `data:kdbx` 引擎（kotpass），按 `Docs/02` §3.4 做往返保真度测试

## 已知未决（接力者注意）

- `TokenRefresher` 已接真实实现，但**尚无 UI 会话管理**（unlock/lock 状态机未建）
- `VaultItem` 仍是 M0 雏形（5 字段），与 `Docs/02` 超集模型差距大；补字段时同步补 Mapper
- 同步编排刻意未做节流/优先级/被动同步（M1 不需要，勿过度设计）