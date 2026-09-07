# 下一步任务清单

> 按优先级排列；每项含"为什么做"。状态：`TODO` / `DOING` / `DONE` / `BLOCKED`

## P0 · 主线 M1（Bitwarden 同步）

- [ ] **补全 `data:bitwarden` 的 Vault API**：`sync` / `accounts/revision-date` / ciphers CRUD / folders
      *为什么：目前只有身份端点，无法真正同步*
- [ ] **移植 401 自动刷新拦截器**（OkHttp `Authenticator`，按 host 刷新 token）
      *为什么：Bastion 踩过坑的成果，token 过期是同步失败第一大原因*
- [ ] 建 `core:database`（Room 密文缓存 + dirty 队列）
      *为什么：离线可用与增量同步的前提*
- [ ] 建 `core:datastore`（设置 + Keystore 包装）
- [ ] 序列化容错：`ignoreUnknownKeys = true`
      *为什么：Bitwarden 服务端新增字段时，严格解析会直接崩*

## P1 · 质量基础设施（越早越好，趁代码少）

- [ ] 接入 **Detekt**，启用 `LongMethod` / `TooManyFunctions` / `LongParameterList` / `LargeClass`
      *为什么： Docs/16 的规模上限需要工具强制，否则形同虚设*
- [ ] 配置 **Baseline Profile**
      *为什么：显著提升启动与首次滚动性能，是官方推荐的首屏优化手段*
- [ ] 开启 Gradle **配置缓存**与并行执行
      *为什么：缩短构建反馈时间*

## P2 · UI / 交互

- [ ] 研究 Bastion 的**滚动缩放标题 / 功能折叠 / 沉浸式透明**实现，决定移植还是重写
- [ ] 搭空壳导航（库列表 / 解锁 / 主列表 / 设置）+ 动态取色

## P3 · M2（KDBX）

- [ ] `data:kdbx` 引擎（kotpass），并按 `Docs/02` 3.4 做往返保真度测试

## 暂缓

- `feature/*` 模块拆分（文档已标注：暂不拆）
