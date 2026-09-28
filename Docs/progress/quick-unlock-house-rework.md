# 施工工作单：快速解锁「房子化」（两级钥匙层级）

> **自包含、零上下文可做。** 设计真源：[`.ai/decisions/快速解锁房子化-两级钥匙层级-定稿.md`](../../.ai/decisions/快速解锁房子化-两级钥匙层级-定稿.md)（下称「定稿」）；
> 根因与证据：[`Docs/progress/audit/bitwarden-kdbx-sync-audit.md`](audit/bitwarden-kdbx-sync-audit.md)（下称「报告」）。
> **论证一律看定稿，不在会话里重新论证。**
>
> 状态：🚧 施工中（**批次 1-3 ✅ 2026-09-29 收工，门禁三关全绿；批次 4-5 未动。**
> **接力入口：[`house-rework-batch3-handoff.md`](house-rework-batch3-handoff.md)**——批次 3 的
> 删五类、判定逻辑可测化、结果页与副标题改动都在那份里；
> 批次 2（动作表 / 重登记向导）见 [`house-rework-batch2-handoff.md`](house-rework-batch2-handoff.md)；
> 钥匙层模型速查见 [`house-rework-batch1-handoff.md`](house-rework-batch1-handoff.md)。）
>
> 门禁纪律（每批次收尾必做，详见 `conventions/8.6-工程质量.md`）：**三关分开单跑**
> （detekt → compile → test，连跑会触发 daemon 环境崩）；**UP-TO-DATE ≠ 有效门禁**
> （必要时 `--rerun-tasks`）；**改完 detekt 必须再真跑 compile**（detekt 先于编译，掩蔽编译错误）。
> 任务名带 Full（flavor 化）；gradle 用解压发行版绝对路径（`./gradlew` 本机不可用）。
> ⚠️ `VaultRepositoryImpl` 已顶 40 函数上限：**新逻辑进新类，别往它身上堆。**

---

## 批次 0：P0 一分钟实验（用户配合，先做；判别 H1/H2）

1. adb 无线连接（端口轮换：`adb mdns services` 取当前端口；每条命令自带
   kill-server/start-server；多设备条目时必须 `-s`）。
2. 起全量 logcat 现场录制（静默过滤器下体积不变不能判死；分析前先 `grep -v adbd`）。
3. **把 OneDrive KDBX 库切为活跃库** → `am force-stop` → 冷启动到该库解锁页 →
   **指纹作为首个库解锁**（⚠️ 真手指，不可 adb 模拟）。
4. 读 `LocalUnlockFanout` 日志（`fanout first=…`）。

- **first 仍失败 = H1**（登记期 payload 坏）→ 做批次 0.5；
- **成功 = H2**（扇出授权缺口）→ 跳过 0.5，直接批次 1。
- 产出：结论写回本批次状态 + 报告 §3.4 勾选。

## 批次 0.5（仅 H1 成立）：止血补丁

`LocalUnlockEnrollment.commitForVaults`（L263-289）一把 cipher 连包 N 库 → 改**每库独立 cipher**。

- 代价如实告知用户：auth-per-use 下每库 wrap 各需一次指纹授权（N 库 = N 次弹窗）——
  这就是它只配当过渡的原因。
- 批次 1 落地后**整体删除**此补丁。

## 批次 1：钥匙层核心（最大批；先核对、后动工）

新增 `HouseKeyStore`（与 `LocalUnlockKeyStore` 同层）：

- 随机 256-bit 房钥匙；仅内存持有 + 用完即擦；**绝不落盘（定稿硬约束 #1）**；
- 指纹信封经 `LocalUnlockKeyStore.wrap/unwrap`（收窄为只对房钥匙一个 blob）；
- PIN 信封经 `PinKeyWrapper`（Argon2id）同款收窄；
- 房间信封每库一份 AES-GCM 纯软件封装（复用 `KdbxUnlockPayload` 长度前缀编解码）。

改造点（锚点见报告 §3）：

- `LocalUnlockKeyStore.kt:158-213` —— wrap/unwrap 语义收窄；KEK 创建不动；
- `LocalUnlockEnrollment.kt:263-289` —— 删多库连包 → 单包房钥匙 + 逐库软件封装；
- `LocalUnlockFanout.kt:84-104` —— rest 库「现取新 cipher」整段删除 →
  一次 Keystore 解密 + 软件解密循环；
- `VaultRepositoryImpl.kt:420-575` —— `enroll/completeLocalUnlock*` 契约改两级，新逻辑进新协调类；
- `PinUnlockStore` —— N 信封 → 1 信封；失败计数改全局。

**施工前先核对**：现有每库信封 blob 的实际存放位置与读写路径（报告未覆盖此细节，先取证再动）。

单测（批次收尾一并交付）：

- 房钥匙指纹/PIN 两信封各解出**同一把**房钥匙（往返）；
- 房间信封篡改必败（GCM tag）；
- 房钥匙明文擦除验证；
- fanout 语义 = 1 次 Keystore + N 次软件解密。

## 批次 2：登记与迁移（✅ 2026-09-29 夜完成，`61ea1a3`；三关全绿、单测 318 全过）

> 落地细节见 [`house-rework-batch2-handoff.md`](house-rework-batch2-handoff.md)。
> ⚠️ 已知遗留：**动作表重排没有单测覆盖**（控制器硬编码 `Dispatchers.IO`，纯 JVM 下
> `runTest` 无法确定性推进）⇒ 随批次 3 的 `QuickUnlockControllerTest` 改写一起解决。

- `QuickUnlockController` 动作表按定稿 §5 更新：勾库 = 软件封装不碰指纹；开锁 = 各一次 wrap；
  ⚠️ **房间信封只在至少一把门锁已存在时创建**（定稿 §5 顺序约束——否则房钥匙无落点，进程一死即孤儿信封）；
- 重登记向导：旧信封检测 → 提示 → 逐库问密码（可跳过）→ 派生房钥匙 → 包两把门锁 →
  软件包各房间 → 删旧信封；**中途失败不半新半旧**（同批生效或整体回退）；
- 不写新旧兼容层（定稿 §8）。

## 批次 3：设置页简化（✅ 2026-09-29 深夜完成；三关全绿、单测 328 全过）

- 删五类：每库「指纹/PIN」标记（范围列表变纯复选框）、Partial 态与「有 N 个库未完成」、
  三态推导（`QuickUnlockControllerTest` 7 条**改写**为两布尔 + 范围语义）、
  每库 `enabled`/`pinEnabled` 双键、结果页逐库成败分类（改「已纳入 N / 跳过 M」）；
- 副标题：指纹「用系统指纹打开所有已纳入的库」/ PIN「6 位数字；连续输错 5 次将锁定」；
- 孤儿串：`check_orphan_strings --gate`，**被取代的删**；
- ⚠️ XML 资源注释里别放 markdown 表格（`--` 会炸 aapt2）。

## 批次 4：失效矩阵（定稿 §6）

- rearm：开门状态检测平台密钥失效 → 内存房钥匙静默重包门锁信封；
- 降级：`ERROR_KEY_INVALIDATED` 类 → 禁用该锁 + 明确文案 + 回主密码
  （**绝不静默「本地解锁凭据不可用」**）；
- StaleCredentials：某库主密码变更 → 重包**该房间软件信封**，门锁不动；
- PIN 熔断改全局 5 次：旧每库计数作废，从 0 起。

## 批次 5：真机验收清单

1. 指纹一次 → 范围内全部库打开（日志：1 次 Keystore + N 次软件）；
2. 勾选 KDBX 库进范围：只弹主密码框，**不弹指纹**；
3. **硬约束 #1 专项**：解锁后杀后台 → 重启必须要求重新解锁（房钥匙未落盘的直接验证）；
4. 重录系统指纹：开门状态自动 rearm 无感；关门状态明确降级 + 主密码可进；
5. PIN 连错 5 次 → 全局熔断，主密码可进，重置恢复；
6. 升级迁移：老包升新包 → 重登记向导走通、旧信封已清；
7. 装机核证照旧（`dumpsys package` + `pm path` 拉 APK 比 SHA-256）。

---

## 完成定义

全批次三关门禁全绿 + 真机验收 1-7 全过 + 定稿补「实施记录」+ 本单各批次标 ✅。
