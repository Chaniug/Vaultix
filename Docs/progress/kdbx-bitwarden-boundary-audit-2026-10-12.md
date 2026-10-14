# KDBX × Bitwarden 双库功能完善度与边界审计

> 日期：2026-10-12 · 基线：`main`（含 CI 修复 `f470bc4`）
> 性质：**只读排查**，未改任何生产代码。方法：静态读码 + `.ai`/`Docs` 既有结论对拍。
> 前置阅读：`completeness-audit-2026-10-10.html`（全仓审计）、`json-kdbx-lossless-conversion.md`（W1–W6）、`.ai/conventions/8.3-M2KDBX.md`

---

## 0. 一句话结论

**两条主链都真能跑，但"完善"不对称**：Bitwarden 是功能完整的主力库（≈95%）；KDBX 的「增删改查 + 回收站 + 网盘同步 + 冲突」已闭环，但存在**三个"静默丢失/不可见"型边界**（本文 A 组，均为本次新发现、尚未登记 ISSUES），外加一批已跟踪未收口项（B 组）。

---

## 1. 能力矩阵（代码实证）

| 能力 | Bitwarden | KDBX | 证据 / 备注 |
|---|---|---|---|
| 条目 增/改/删/恢复/永久删 | ✅ 五类型全量 | ✅ 走 `KdbxItemRepository.persist` | `ItemRepositoryImpl` 五个写方法开头 `isKdbx` 分流 |
| 回收站列表/恢复/永删 | ✅ | ✅ | S5 落地：`kdbxTrash` 映射回收站子树 |
| 回收站 30 天自动清理 | ✅ `TrashCleanupPolicy` | ❌ 设计不支 | `cleanupExpiredTrash` 对 KDBX 显式拦截；KDBX 无独立删除时间 |
| 五类型（登录/卡/身份/笔记/SSH） | ✅ 读写全量 | ⚠️ **读写都坍缩成 Login/SecureNote** |  mapper:124 / writer 只落 5 标准字段，见 A3 |
| TOTP | ✅ | ✅ 往返已钉死（W3，11 形态） | `KdbxTotpRoundTripTest` |
| 通行密钥 | ✅ 创建+管理 | ⚠️ **只读** | 读侧多凭证已修（W2）；写被 `requireNotKdbx` 拦（`ItemRepositoryImpl:455/478`） |
| 多 URI / 匹配规则 / 应用 URI | ✅ | ✅ 无损降级（W4） | `VPX_URL_n` / `VPX_MATCH_n` / `App Package Name` |
| 自定义字段（4 态） | ✅ | ✅（Hidden 由 Protected 映射） | R1 标准键防覆盖（W1） |
| **收藏 favorite** | ✅ 读写+筛选+填充排序 | ❌ **读写全丢** | 见 A1 |
| **文件夹** | ✅ 同步+选用+分组 | ❌ **App 内完全不可见** | 见 A2 |
| 文件夹 增/删/改 | ❌ 两端都不做（官方端管理） | — | `FolderRepository` 只读接口，有意设计 |
| 自动填充 | ✅ | ✅ 共享链路（活跃库） | `AutofillCandidateSource`；卡/身份候选对 KDBX 恒不出现（A3 连带） |
| 网盘同步（OneDrive/WebDAV） | ✅ 增量同步 | ✅ 保存即自动上传 + 手动拉取 + 条件写 | S1–S6 + 洞 A/B/C 全修 |
| 离线 | ✅ Room 快照+队列 | ✅ 文件缓存 + 直接写文件 | B1 缓存落地 |
| 冲突处理 | ✅ | ✅ 三选项（无自动合并） | `KdbxFileConflictException`；竞品基线亦多无合并 |
| 快速解锁（指纹/PIN） | ✅ ≈33ms | ✅ 已支持但慢（#132） | Keystore+keyfile 方案；B1 后 ≈1.2–1.5s |
| 导入（BW 加密 JSON） | ✅ | ✅（落库时分流） | 文件夹按名映射 ⇒ KDBX 全落根组（A2 连带） |
| 导出（BW 加密 JSON） | ✅ | ⚠️ 条目全，**文件夹丢** | A2 连带 |
| JSON⇄KDBX 互转 | — | ⏳ W1–W4 已完成，**W5 入口 / W6 真机互操作未做** | 转换器本体也待建 |
| 附件 | ❌ 元信息保留、不传输 | ❌ 只统计不读 | 双侧同一缺口；KDBX 写侧有保真守卫防丢 |
| 密码历史 | ❌ 领域模型未承载 | ❌ 写入保留、不展示 | 双侧同一缺口 |
| Send / 组织集合 / 安全中心 / 改密 | ❌ | ❌ | 双侧同一缺口（10-10 审计 P0/P1） |
| 站点图标 | ✅ 服务端预生成端点 | ⚠️ 仅首字母头像 | `SiteIconUrl` 对 `content://` 判不可用 |

---

## 2. 没做好的边界（按优先级）

### A 组 · 代码级新发现（未登记 ISSUES；均为"静默"型）

| # | 边界 | 用户看到什么 | 位置 | 工作量 |
|---|---|---|---|---|
| **A1** | **KDBX 收藏读写全丢** | 表单里点星标 → 保存成功 → 星不显示；「收藏」快捷筛选对 KDBX 恒空；自动填充排序里 KDBX 条目永不被当收藏 | `data:kdbx` 全模块 **0 处** `favorite`；`KdbxItemMapper.toVaultItem` 不置位、`applyDomainFields` 不写回 | 小（一个字段，两侧） |
| **A2** | **KDBX 文件夹在 App 内完全不可见** | ① 表单文件夹下拉为空 ⇒ 新建条目只能落根组；② 按文件夹分组时全部归「无文件夹」；③ 导出 JSON 丢文件夹归属；④ 导入 JSON 进 KDBX 全落根组 | `FolderRepositoryImpl.observeFolders` **只读 Room**、无 `isKdbx` 分流；而 `KdbxMappedContent.folders` 已算出却**无任何消费方**（死数据）。⚠️ `VaultExportRepositoryImpl` 的 KDoc「KDBX 读路径由仓储内部分流」与实际不符 | 小-中（一个分流 + 四个消费点） |
| **A3** | **KDBX 类型坍缩 + 表单不拦** | 在 KDBX 库新建「银行卡/身份/SSH」⇒ 类型专属字段**静默丢失**（提示保存成功）；卡包页、SSH 筛选、卡/身份自动填充对 KDBX 恒不出现 | mapper 恒映射 Login/SecureNote；writer 只落 5 标准字段 + URI/TOTP/通行密钥/自定义字段；`ItemFormDialog` 对库类型**零感知**（`VaultItemType.entries.forEach` 五类型全offer）。⚠️ `KdbxRoundTrip` 自检只保"文件没坏"，保不了"领域字段没进去" | 中（要么补 Card/Identity/SSH 的 KDBX 映射，要么表单按库裁剪类型并明示） |

> A2/A3 的共同根因：**读侧分流做在 `ItemRepositoryImpl`，没做在 `FolderRepository` 与 UI 层**——"读分流了，写/消费就必须同时分流"这条判据（#106 的教训）只贯彻了一半。

### B 组 · 已知未收口（已有文档/ISSUES 跟踪）

| # | 事项 | 状态 | 跟踪处 |
|---|---|---|---|
| B1 | W5 转换入口 UI（选 JSON→密码→预览→SAF 产出）+ 转换器本体 | 待做；W1–W4 已落地 | `json-kdbx-lossless-conversion.md` |
| B2 | W6 真机互操作（KeePassXC/DX 打开产出文件） | 待真机 | 同上（历史上两次返工都因没先真机验） |
| B3 | KDBX 解锁提速 B2（信封改包派生密钥，跳过 KDF） | 待真机数字决定；B1 缓存已落地（3.58s→1.2–1.5s） | #132 / 多库锁模型定稿 §5 |
| B4 | KDBX 网盘直建（OneDrive/WebDAV 上新建库） | 待做；目前仅 SAF 本地 | next-steps「W0 云的一半」 |
| B5 | #161「PIN 忘记可在设置里重设」文案承诺了功能、入口不存在 | 待做 | ISSUES 04 篇 |
| B6 | KDBX 通行密钥只读（写能力已具备但仓库层拒绝；FLAG_BE/BS 不保真） | 产品策略待拍板 | 10-10 审计 P1 |
| B7 | KDBX 回收站无自动清理（无删除时间，取 lastModificationTime 如实转换） | 设计取舍，已明示 | 施工单 S5 |
| B8 | `VPX_BW_TYPE`/`VPX_BW_ID` 目前是**预告常量、零写入点** | 等 W5 转换器 | `KdbxToolFields` KDoc |

### C 组 · 双库共同缺口（竞品基线，10-10 审计已列 P0/P1）

导入器矩阵（CSV/KeePass XML/…）· 附件传输 · 安全中心（HIBP/弱/重复/过期）· 主密码改密 · 组织/集合 · Send · 冲突自动合并 · 硬件密钥。

### D 组 · Bitwarden 侧独有边界（有意设计，知悉即可）

- **编辑类型守恒守卫**：服务端出现未知 `type`（如未来 BW 新类型）⇒ 该条目**只能看不能改**，报「已停止保存以防数据丢失」（`ItemRepositoryImpl:290`）。宁可不编辑也不漂移。
- 站点图标依赖 Bitwarden 服务端 `/icons/` 端点 ⇒ 自托管/无该端点时只有首字母头像。

---

## 3. 建议的下一步顺序

1. **A 组三条**（都是静默丢失/不可见，且工作量集中在"补一个分流/一个字段"）—— 其中 A1 最小、A2 收益最大（一举修好表单/分组/导出/导入四个面）、A3 需要先拍板"补映射还是裁类型"。
2. W5/W6 收口 JSON⇄KDBX 互转（转换器本体 + 入口 + 真机互操作）。
3. B3（B2 提速）看真机实测数字再决定；B4/B5 随手可清。
4. C 组按产品定位排期（安全中心与改密是"作为安全产品"的硬伤，导入器是迁移第一门槛）。

---

## 4. 本次审计的方法论备注

- 所有结论均有代码位置支撑；「已修复/已落地」均以 CI 实证或单测存在为准，未采信提交信息自述。
- A 组三条已全仓 grep 确认**未登记** `.ai/ISSUES.md` 任一篇（05/07 篇逐条读过）。
- 未改动任何代码；本文档未提交 git。
