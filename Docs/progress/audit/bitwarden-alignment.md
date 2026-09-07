# Bitwarden 对齐审计（M1 收尾 · 2026-09-08）

> 对照：`reference/bastion/app/src/main/java/com/bastion/app/bitwarden/`（快照 @369ed56）
> 范围：M1 内 Vaultix `data:bitwarden` + `data:repository` + 领域模型与 Bastion 的差距。
> 方法：代码逐文件比对 + Bastion 内部文档（reference/bastion/docs/bitwarden同步与密码库生态.md）
> 结论用途：排 M1 批次；不做的项注明理由与未来落点（Docs/18 导航）。

## 1. 总体结论

Vaultix 同步**骨架已对齐**（整包密文落库无损、revision 预检、空库保护、轻量推送、
per-item key 解密），但**写路径有真实数据丢失风险**：编辑任何条目都会用"仅
name/notes/login"的请求体重写整条 cipher——登录条目的 uri/totp/fido2、非登录条目
（card/identity/sshKey/secureNote）的整段载荷全部被清掉；type≥5 还会发生类型漂移
（SSH 条目被当 Login 编辑后 type 5→1）。其次：全量拉取后不做服务端删除清理、
flush 队列无 404 毒丸处理。UI 对条目类型零感知（无徽标、无编辑门禁）。

## 2. 差距清单

| # | 主题 | Bastion 设计（位置） | Vaultix 现状（位置） | 差距 | M1 建议 |
|---|---|---|---|---|---|
| M1-1 | **编辑丢字段（登录类）** | 编辑走完整 cipher 载荷，uri/totp/fido2 随条目标签保留（CipherUploadProcessor.kt / BitwardenApi.kt Login 载荷全字段） | `updateItem → mapper.toRequest` 只重建 username/password，uri/totp/fido2 全部丢弃（CipherMapper.kt:65 / ItemRepositoryImpl.kt:110） | 登录条目编辑后网址与 TOTP 从服务端消失 | **做**（本批） |
| M1-2 | **编辑毁非登录载荷（卡/身份/SSH/笔记）** | 按类型承载 card/identity/sshKey/secureNote 全载荷 | CipherDto/CipherRequest 无这些载荷字段（BitwardenDto.kt）；更新整条重写 | 真机库 card(3)/identity(4)/sshKey(5) 条目误编辑即毁 | **做**（本批） |
| M1-3 | **type≥5 类型漂移** | type5=sshKey 显式建模（BitwardenApi.kt:777） | mapType else→Login；mapTypeToInt(Login)=1（CipherMapper.kt:104） | SSH 条目保存后变成 type1 | **做**（本批：枚举+类型守恒守卫） |
| M1-4 | **UI 类型零感知** | 条目卡/详情带类型徽标与只读字段区（ui/components、ui/screens） | app UI 无任何 VaultItemType 引用 | 用户不知道条目类型；编辑非登录条目的表单误导 | **做**（本批：徽标+非 Login 隐藏登录字段） |
| M1-5 | **服务端删除不清理** | 全量拉取后 deleteNotIn 同步本地（BitwardenSyncService.kt:400-401 folder） | persistCiphers/Folders 仅 upsert（BitwardenSyncService.kt:184-192） | 网页端永久删除的条目/文件夹本地残留（回收站幽灵项） | **做**（本批：带 pending 保护的 prune） |
| M1-6 | **flush 404 毒丸** | 单条失败隔离 + 分类（service/CipherSyncProcessor.kt 外层双层保障） | 失败仅 incrementRetry，永久 404 无限重试（BitwardenSyncService.kt:111-114） | restore/delete 目标已不存在时队列卡死、每次同步都重试 | **做**（本批：4xx 弃单+按语义对账） |
| M1-7 | 注释过时 | — | BitwardenSyncService.kt:44 声称"暂不实现节流/编排"，data:repository 已实现 | 误导接力者 | **做**（顺手修注释） |
| M2-1 | 类型字段只读展示 | 卡/身份/SSH 详情字段区 | 领域模型未承载 | 展示需先定 VaultItem 超集字段 | 推迟（M2/M1.5，Docs/02 字段对拍矩阵） |
| M2-2 | 附件/密码历史/回收站完整流 | attachments/PasswordHistoryManager | DTO 有附件元数据但不使用 | 只读附件属 1.x（Docs/18 §4.5） | 推迟 |
| M2-3 | 文件夹管理 UI/端点流 | folder CRUD 全链路 | 端点已建未用，folderId 仅存列 | 文件夹管理 UI 超出 M1 最小口径 | 推迟 |
| 不做 | 离线队列/冲突合并/多账号等 | BastionLocal 语义/StorageTarget | — | 与 Vaultix 决策冲突（Docs/18 §3） | 不做 |

## 3. 建议批次

1. **批 1（数据安全 + 类型保真，本报告落地）**：M1-1..M1-7 —— DTO 全载荷承载
   （card/identity/secureNote/sshKey + login uri/totp 保留）、更新=合并上传
   （overlay 可编辑明文，未编辑段沿用原密文）、type5 建模+写路径类型守恒、
   UI 类型徽标 + 非 Login 编辑隐藏登录字段、全量后 prune（排除 pending）、
   flush 4xx 弃单。验收：新/编辑登录条目 uri 保留；卡条目改标题后 card 载荷
   原样往返；单测覆盖合并矩阵。
2. **批 2（M1 收尾清单）**：回收站视图（restore/永久删除 UI，复用已有 RESTORE/
   DELETE op）+ 移除库入口 + 真机回归。
3. **批 3（P2 前移候选）**：WorkManager 周期同步（PERIODIC 已预留）。
4. M2 前：Docs/02 字段对拍矩阵（Docs/18 §4.2），随后 `data:kdbx`。
