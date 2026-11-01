# 05-KDBX本地库 · 第 4 篇（#167 ~ #219）

> 主篇索引：[`05-KDBX本地库.md`](./05-KDBX本地库.md)（含计数规则与重号警告）
> 本篇 7 章 · 16.2KB · 覆盖 #167 ~ #219

## 167. A3：KDBX 库能建出「类型是卡、字段全空」的残骸（2026-10-13）

- **现象**：KDBX 库里「新建银行卡」能一路点到底，**保存成功**，
  详情页一片空白 —— 类型是 `Card`，字段全空。用户视角是「功能坏了」，
  实际是「存进去了一个残骸」。
- **根因**：KDBX mapper 只产 `Login` / `SecureNote`，writer 对
  `Card` / `Identity` / `SshKey` **没有对应字段区，且不报错**（静默丢弃）；
  表单（`ItemFormDialog`）又不做类型裁剪 ⇒ 用户点什么类型都"能存"。
- **解法：三层防线**（缺一层就有漏网路径）：
  1. **表单裁剪** —— 新增 `ui/common/ItemTypePolicy.kt` 纯函数，KDBX 只给
     两枚筹码 + 一行说明（否则用户以为「功能没做完」而不是「这个库就这两种」）；
  2. **UI 门控** —— 卡包 FAB / 外部 `addRequest` / 空态三处都问
     `cardCreationSupported`（活跃库类型的 StateFlow，初值 `true` 不闪避）；
  3. **仓储硬闸** —— `KdbxItemRepository.requireSupportedType`：表单裁的只是
     「用户手点」这条路，**导入**（`VaultExportRepositoryImpl`）同样汇入 `create`
     —— 一份 Bitwarden 加密 JSON 里的银行卡会绕开表单 ⇒ 必须在写动作入口拦，
     类型不对就整体拒绝，会话与文件都不碰。
- **三处判据同源**：mapper 产出集 = 表单可选集 = 仓储支持集，各自单测钉住；
  漂移的两种死法（表单让建、仓储拒收 / 反之）都由对面那组测试兜住。
- **⚠️ 判据**：**静默丢弃比报错更糟** —— writer 对不支持类型不抛错，
  上层就只能报「成功」，用户得到的是看起来完好的残骸。
  且**导入是第二条写入路径**，只裁表单必然漏（这正是硬闸存在的理由）。
- **测试**：`ItemTypePolicyTest` 4 例 + `KdbxItemRepositoryTest` 3 例
  （拒收银行卡人话报错且会话/文件不碰 / 拒收「登录改写银行卡」原条目毫发无损 /
  两种支持类型照常放行并落盘）。

## 169. A4：KDBX 安全笔记类型往返翻转 —— 空笔记存盘再读回变成登录（2026-10-16）

- **现象**：KDBX 库新建一条**空备注的安全笔记**（只填标题，备注留空——表单该字段可空），
  保存成功后**当时看着正常**；退出再进（或下次解锁），详情页**变成登录条目**：
  用户名/密码两行空字段杵在那里，条目出现在「登录」分类与筛选里。
- **根因**：KDBX **没有条目类型概念**（KeePass 标准五键里没有"这是安全笔记"），
  `KdbxItemMapper.toVaultItem` 靠纯启发式推导：
  `无用户名 && 无密码 && 有笔记 ⇒ SecureNote`。空笔记的安全笔记三个标准字段全空
  ⇒ `isNote=false` ⇒ **类型静默翻转**。写入侧从未把类型信息落盘，读侧自然无从恢复。
  Bitwarden 侧类型是服务端显式字段，无此问题 —— 又是「两个库两种命运」。
- **解法（复用 A1 的 `VPX_` 工具区模式，读侧三态）**：
  - `KdbxToolFields` 新增 `SECURE_NOTE` / `SECURE_NOTE_TRUE`；真值判定
    `isSecureNoteTrue` 与 `isFavoriteTrue` 共走私有 `isTruthyFlag`
    （`true`/`1`/`yes` 宽松口径的唯一出口）；
  - 读侧 `isNote = fields.secureNoteMarker() ?: 启发式` —— **marker 优先，缺失才回落**；
  - 写侧 `applySecureNote`：类型未变**一字不动**；SecureNote ⇒ 写 `"true"`；
    类型离开 SecureNote ⇒ **删键**（回落到启发式，与「从未标记」在文件里同形）。
- **⚠️ 三态语义（本篇第二次撞同一堵墙）**：`secureNoteMarker()` 的 `null` 是
  「**没有标记**」（第三方条目 ⇒ 回落启发式），`false` 是「标记显式为假值」
  （用户把安全笔记改成了登录型 ⇒ 信标记）。二者不可压成一个布尔 ——
  压了就等于宣布「没有标记的条目都不是笔记」，第三方库里的安全笔记会一夜之间
  全部变成登录，**比原 bug 波及面更大**（#121「三态压二值」的同款事故）。
- **⚠️ 与 A1 的刻意差异：不写 `"false"`**。收藏「缺失即假」是对的（没星就是没星）；
  而类型**缺失不等于登录** —— 第三方工具写的笔记型条目（有笔记、无账号密码）
  靠启发式判安全笔记，KeePass 生态就是这个 convention。给每条登录镶一个恒假字段
  纯属噪音。代价：「Vaultix 自建的、只有笔记的登录条目」仍会被启发式判成安全笔记 ——
  该内容形态本身歧义，且与第三方工具的解释一致，可接受（KDoc 有完整论证）。
- **测试**：`KdbxItemWriterTest` 9 例 —— 核心回归（空笔记往返）/ 带笔记 /
  登录条目不写键 / 第三方无标记**兼容不回归**（+反证）/ 宽松真值 `"1"` /
  显式 `"false"` 压启发式 / 未变更不重写第三方的 `"1"` / 更新不翻回。
  其中「第三方无标记」两条是兼容性闸：**闸比 bug 本身更值得守**。

## 170. A6：KDBX 标准字段的「内存保护」属性被静默剥掉（2026-10-16）

- **现象**：用 KeePassXC / DX 建库时给**用户名或备注**打了内存保护标记
  （`Protected="True"`，KeePass 允许标在任意字段上），条目流转到 Vaultix
  编辑一次（哪怕只改个标题）保存后，保护标记**没了**——再打开库，
  那些字段变成明文属性。密码字段没这个问题（早就单独保留了）。
- **根因**：`KdbxItemWriter.applyDomainFields` 里 Title / UserName / Notes
  **无条件写 `EntryValue.Plain`**，只有密码走 `passwordValue` 保留
  Encrypted 形态。而 kotpass 的编码器 `marshalFields` 只对
  `EntryValue.Encrypted` 写 `Protected="True"` ⇒ **落盘即丢属性**。
  读侧 `.content` 两种形态都解密，所以值不丢、界面无感——
  丢的是安全属性，用户无从察觉。
- **修法**：`protectedAware(existing, key, value)` 泛化原 `passwordValue`
  的逻辑，四个标准字段一律走它。语义与 Kotlin 侧 typed accessor
  逐字等价（都是 `fields[BasicField.X.key]` 的 Map.get，大小写敏感的
  已知坑不变）——泛化不改变任何既有行为。
- **⚠️ 判据**：这次是**从编码器倒推**坐实的（`marshalFields` 只认
  `Encrypted`）。教训：读写两侧的"保真"不能只对拍领域模型，
  要一路看到**序列化边界**——领域层看起来"值没丢"的属性，
  可能在落盘那一步才丢。与 #22「读写字段不对称」同族，但这次丢的不是值，
  是**值的属性**。
- **测试**：`KdbxItemWriterTest` 2 例——受保护的用户名与备注更新后
  保护不丢、值不丢（主用例）；未受保护的不被无端加密（反证：
  把用户没标的字段升级成 Encrypted 同样是一种改坏用户库）。
  变异验证（恒 Plain）精确 2 红：主用例 + 既有密码用例。

## 178. 🔴 `Kdbx.mutate` 的读-改-写不原子 ⇒ 并发编辑**静默丢一笔**，两次都返回成功（2026-10-20）

- **现象**：用户"保存一笔、紧接着再改一笔"，其中一笔改动凭空消失，界面与日志都没有任何异常。
- **根因**：`mutate` 的形状是 `KdbxSessionStore.get → 改 → put`，而 `KdbxSessionStore` 用的是
  `ConcurrentHashMap` —— 它只保证 **map 操作本身**安全，**不保证这三步合起来原子**。两条写并发时：
  ```
  A.get → B.get → A.put(含甲) → B.put(含乙、不含甲)   ⇒ 甲被静默丢掉
  ```
- **为什么本项目真的会并发**（不是臆想）：五个写入口都在 `withContext(Dispatchers.IO)` 里跑；
  自动上传器 `KdbxAutoUploader` 自带 `Dispatchers.IO` 的 scope，上传时 `saveVia` 读**同一个**
  `session.database` 去序列化。"保存后立刻再改一笔"是最常见的操作序列。
- **解法**：每库一把 `Mutex`（`ConcurrentHashMap<String, Mutex>` + `getOrPut`），`mutate` 与
  `replaceSession` 共用。为什么是 `Mutex` 而不是 `synchronized`：临界区里有挂起点；
  为什么**不**把网络/文件 IO 纳入这把锁：那会让"上传时点保存"卡十几秒，那边的正确性由
  `expectedVersion` 条件写保证。
- **⚠️ 判据**：**`ConcurrentHashMap` 不等于线程安全**，它只保护单次操作。凡是
  "get → 改 → put" 的写法都要问一句"这两步之间有没有别人能插进来"。
- **验证**：用**线程**（不是协程，`runBlocking` 是单线程事件循环，撞不进窗口）并发写两个不同条目，
  断言两笔都在。撤掉锁 → 5/5 失败（`missing (1): 改乙`）；装回锁 → 10/10 通过。

## 179. `writeLocks` 只增不减：锁掉 / 删除的库仍握着一把活锁（2026-10-20）

- **现象**：`getOrPut` 建的锁永不释放；`VaultRepositoryImpl.removeVault` / `signOut` 都调
  `Kdbx.lock(vaultId)`，但那条路径**只** `close` 会话，`vaultId` 永远留在 map 里。
- **为什么算 bug 而不算"可忽略的泄漏"**：语义上它让一个"已经锁掉、甚至删掉"的库还握着一把活锁，
  与 `KdbxSessionStore.close` 那句"锁库即丢弃"的约定不一致。
- **解法**：`lock` / `lockAll` 一并 `remove` / `clear`；把两处 `getOrPut` 收口成 `lockOf`。
  ⚠️ **顺序不能反**：先 `close` 会话再丢锁（反过来的话挤进来的写会先造一把新锁、刚拿到的会话就没了）。
- **安全说明**：正在被等待的 `Mutex` 被 `remove` 是安全的 —— 等待者握的是对象引用不是 map 键；
  且会话已丢弃，`mutateLocked` 会直接返回 `NotUnlocked`，不存在"两把锁同时放行两笔写"。
- **验证**：`KdbxMutationTest > 锁库后写锁一并释放_不留孤儿锁`，**同一用例内含反证**（先确认写一次后
  计数 `> 0`，再 `lock` 后 `== 0`）。撤掉 `remove` → 该用例失败。

---

## 190. 🔴 内容指纹只摘 5 个字段 ⇒ #185 的写回基线防线对"改备注/改 TOTP"完全失效（2026-10-26，对比审计）

- **背景**：#185 的写回基线防线靠 `KdbxEncoder.contentFingerprint` 判断"我手里这份
  是不是远端那一版"。它最初只摘 `id / title / username / password / notes`。
- **后果**：用户在本地改**备注之外**的任何字段（网址、TOTP 密钥、自定义字段、
  卡片号、SSH 私钥、身份、收藏、文件夹归属）后保存 —— 指纹**不变** ⇒ 基线判为
  "内容没动过" ⇒ 落盘放行 ⇒ **基于旧版本覆盖远端**。#185 那道防线在
  这些字段上等于不存在，而且**没有任何报错**。
- **修法**：`contentFingerprint` 扩展为**全字段**：
  `type` / `folderId` / `favorite` / `reprompt` / `uris(uri+match)` / `totp` /
  `fido2Credentials`（11 字段）/ `card` / `sshKey` / `identity` / `customFields`
  （`name+value+type+linkedId`）/ `secureNote` / `passwordHistory`。
- **⚠️ 三个坑**：
  1. **Map 顺序不稳会造成假阳性**：`binaryNames` 是 `Map`，直接遍历的话同一份内容
     两次算出的摘要可能不同 ⇒ "所有库永远算变过"（与 #185 的坑 1 同源）。
     ⇒ **按 hash 排序后**再参与。
  2. **拼接不给分隔符可以构造碰撞**：「标题 = `ab`、用户名 = `c`」与
     「标题 = `a`、用户名 = `bc`」摘要相同。⇒ 新增 `update(digest, vararg values: String)`，
     逐串前先写 `FIELD_SEPARATOR`（`0.toByte()`）。
  3. **"没有值"和"值是空串"要能区分**：`null` 与 `""` 落盘后语义不同（前者是字段缺席）。
     ⇒ `ABSENT_MARK` / `PRESENT_MARK`、`NOT_FAVORITE_MARK` / `FAVORITE_MARK`。
- **验证**：`KdbxFingerprintCoverageTest`（6 条）—— **表驱动** `FIELD_MUTATORS` 覆盖 21 个
  字段，每个字段改一次断言指纹**必变**；另测无分隔碰撞、稳定性（连算两次相同）、
  附件排序无关、文件夹/回收站计数。
- **纪律**：**给 `VaultItem` 加字段必须往 `FIELD_MUTATORS` 表里加一条**（KDoc 已写明）。
  没有这条纪律，"全字段"会随着模型演进慢慢退化回"部分字段"。
- **判据**：**凡是"用指纹判断相等"的地方，指纹漏掉任何可变字段，这个判断就在那些字段上
  恒为真**。而且它会**静默**地恒为真 —— 不报错、不降级，只是防线消失。

---

## 219. 🔴 JSON→KDBX 转换"无损"是空话：卡 / 身份 / SSH / 密码历史**读写两侧都没接**，文件夹还被主动丢弃（2026-11-01）

- **现象**：用户拿 Bitwarden 加密 JSON 转 .kdbx，再用 KeePassXC 打开，银行卡、身份、
  SSH 条目**只剩标题与备注**；所有条目都堆在根组，文件夹全没了。
  整个链路**不报错、不警告**，预览页也只写一行"其中 N 个条目会降级"。
- **对照**：用户明确要求「KeePassXC 能把 Bitwarden 的 JSON 无损转成 kdbx，搬它的实现」。
  KeePassXC `src/format/BitwardenReader.cpp`（GPL-2.0/3.0）的做法是：
  1. `writeVaultToDatabase` 读 `folders`（组织库读 `collections`），
     `createGroup` 按 `name.split("/")` **逐级建组**，item 的 `folderId` → 组；
  2. 身份信息、银行卡、自定义字段分别转成 `identity_*` / `card_*` / 原名字段
     （`code` / `ssn` / `passportNumber` / `licenseNumber` 标为受保护）；
  3. 多 URL：第一条进 `Url`，其余进 `KP2A_URL_n`；
  4. TOTP：裸密钥补成 `otpauth://totp/<标题>:<用户名>?secret=<密钥>`；
  5. 通行密钥：`credentialId` 十六进制 → base64url，私钥包成 PEM 存 `KPEX_PASSKEY_*`；
  6. 时间戳写 `creationDate` / `revisionDate`；密码历史逐条建历史条目。
- **根因（两处同时断，任一修好都还会丢）**：
  - **写侧**：`KdbxItemWriter.applyDomainFields` 只写 5 个标准字段 + URI + TOTP +
    通行密钥 + 收藏 + 安全笔记标记。`VaultItem.card / identity / sshKey /
    passwordHistory / reprompt` **一个都不写**。
  - **读侧**：`KdbxItemMapper.toVaultItem` 也不产出这五个字段 —— 所以"只补写侧"
    会变成**写进文件但读不回来**（更隐蔽的静默丢）。
  - **文件夹**：`KdbxConversionEngine.build` 写死 `folderId = null`，
    注释称"施工单 §2.4 用户拍板平铺"—— 用户现在明确否掉了这个拍板。
- **修法**：
  - 新增 `VPX_CARD_*` / `VPX_IDENTITY_*` / `VPX_SSH_*` / `VPX_PW_HISTORY_n` / `VPX_REPROMPT`
    五组专属字段（`KdbxToolFields`），写侧 `applyPayloads`、读侧三个 `toVault*` 成对接上；
  - 卡号 / CVV / SSN / 护照 / 执照按 `EntryValue.Encrypted` 写（敏感值不能因为
    "换个容器"就变明文）；
  - 密码历史编码为 `epoch毫秒|密码`，按**数值**下标排序（字符串序会把 `_10` 排到 `_2` 前）；
  - 类型判定顺序改为 **载荷 > 显式标记 > 启发式**（否则"只填了卡名没填卡号"的卡
    会被启发式判成笔记，再转回 Bitwarden 就变成一条笔记）；
  - `build` 接收 `folders`，`createFolderPath` 按 `/` 逐级建组并缓存**完整路径**
    （`Work/Dev` 与 `Home/Dev` 不能折叠成同一个 `Dev`）。
- **验证**：`KdbxConversionTest` 新增 13 条（卡 / 身份 18 字段 / SSH / 12 条历史顺序 /
  密码含 `|` / reprompt / 受保护写入 / 文件夹层级 / 重复路径 / 空名退回根组 /
  卡改登录后旧键清理）。旧的「Card 载荷守卫 ⇒ 回退 Login」用例**反向改写**成
  「载荷完整往返」，它同时是"边界已消除"的哨兵。
- **⚠️ 三个坑**：
  1. **只改一侧 = 静默丢**：写侧写入、读侧不产出 ⇒ 数据在文件里躺着，界面上没有。
     这类改动的验收标准必须是**往返测试**，而不是"写进去了（单测断言字段存在）"。
  2. **读侧 `VaultFolder.name` 是完整路径**（`父/子`），而 `id` 指向真实分组。
     断言归属必须按 id 查名，按名字字面匹配会把 `工作/服务器` 判成不存在。
  3. **`typeDegradedCount` 从此恒 0**：它是"还有多少条降级"的哨兵，
     哪天冒出新的降级字段要记得把它加回来，否则用户又看不到降级提示。
- **判据**：**"支持某类条目"必须以往返为证** —— `Bitwarden → Item → KDBX → Item`
  全字段相等，才算支持。领域模型有字段、DTO 有字段、写侧写了字段，三者**都不算**。
