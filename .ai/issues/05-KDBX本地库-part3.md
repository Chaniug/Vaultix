# 05-KDBX本地库 · 第 3 篇（#135 ~ #166）

> 主篇索引：[`05-KDBX本地库.md`](./05-KDBX本地库.md)（含计数规则与重号警告）
> 本篇 7 章 · 15.0KB · 覆盖 #135 ~ #166

## 135. 🔴 kotpass 会**原地改写**你传进去的 keyfile 数组 ⇒ 快速解锁信封存下**废数据**（2026-09-30，**已修**）

**怎么发现的**：不是用户报的 —— 是写阶段 B 的**新建库**（W0）单测时，
「用 keyfile 建的库必须带同一份 keyfile 才打得开」这条**红了**，
而且红的方式很怪：**只给主密码开不了（对）**，**密码 + 原样同一份 keyfile 也开不了（错）**。
两者矛盾 ⇒ 只能是"同一份字节在两次使用之间变了"。

**实证**（一条诊断用例把它钉死）：
给 `createEmpty` 传 `ByteArray(32){ it+1 }`，建库后**调用方那个数组已经不是 01 02 03…**
（变成了一段随机-looking 字节）。也就是说**我们传进去的数组被就地改了**。

**根因（上游源码，`EncryptedValue.fromBinary`）**：

```kotlin
val salt = ByteArray(bytes.size); random.nextBytes(salt)
for (i in bytes.indices) { bytes[i] = bytes[i] xor salt[i] }   // ← 改的是**调用方的数组**
return EncryptedValue(bytes, salt)
```

那是它「内存里不裸放密钥」的设计（`getBinary()` 再 XOR 回来，对**它自己那一份**自洽）。
而 `Credentials.parseKeyfile(32 字节)` 又是"**原样返回同一个数组引用**"：
⇒ **恰好 32 字节的裸 keyfile** 会把调用方那份就地 XOR 掉。
（64 字节 hex / XML 形态走 `decodeHexToArray()` / `sha256()`，都是**新建数组**，不在此列
—— 所以这个坑很**窄**，但撞上时**完全静默**：本次操作照样成功。）

**受害点（真实存在，不是假想）**：`LocalUnlockEnrollment.prepareKdbx`

```
val keyFileBytes = kdbxFileSources.readBytes(keyFileUri)
Kdbx.verify(..., keyFileBytes = keyFileBytes)             // ← 此处数组被就地改写
KdbxUnlockPayload.encode(masterPassword, keyFileBytes)    // ← 组装信封用的还是它 ⇒ 信封里是废数据
```

⇒ 症状：**带 keyfile 的库一旦启用快速解锁，指纹就再也开不了这个库**
（信封解出来的是被改写的字节 ⇒ 复合密钥不对 ⇒ 解锁失败）。
与 #132（KDBX 信封包主密码）同族，但那条是**性能**问题，这条是**正确性**问题。

**修法（一处边界，覆盖两条入口）**：
`buildCredentialCandidates` 是 `data:kdbx` 里**唯一**把 keyfile 交给 kotpass 的地方，
在函数开头 `keyFileBytes?.copyOf()` 一次 —— `Kdbx.unlock` 与 `Kdbx.verify` 一起被护住。
新建路径同理：`KdbxCreator.credentialsFor` 也先 copy。
（**不在每个调用点各修一遍**：散着修的话，下一个新入口必然又漏。）

**判据（写进纪律）**：
> **把密钥/凭据类字节交给第三方库之前，先 copy。**
> 判据不是"这个库看起来会不会改"，而是：**它有没有可能持有一份引用**
> —— `parseKeyfile` 这种"原样返回入参"的实现就是典型的引用外泄。
> 并且：**同一份密钥字节被复用两次的流程（信封组装、多库解锁）必须有一条
> "连用两次都要成功"的用例** —— 只测一次成功永远发现不了这类缺陷。

**回归门禁**：`data/kdbx` 的 `KdbxCreatorTest` 两条 ——
`诊断_新建不会改动调用方传进来的 keyfile 字节` 与
`校验与解锁都不会改动调用方的 keyfile 字节`（后者还断言"同一份字节连用两次都成功"）。

## 136. ⚠️ kotpass 的 `getGroupBy` 在真实库上会返回 null（同一份数据直接遍历就能找到）（2026-09-30）

**怎么发现的**：写阶段 B 的条目写回（W1）单测时，`从回收站恢复回原分组` 这条
在 `restored.getGroupBy { uuid == group.uuid }!!` 处 NPE。加探针打出来的是：

```
直接子组=true  getGroupBy(工作)=false  getGroupBy(根)=false   子组清单=[工作, Recycle Bin]
```

即：**那个组确实是 `content.group.groups` 的直接子项**（`groups.any { it.uuid == 目标 }` 为 true），
但 `getGroupBy` 返回 null；更离谱的是**连根组自己**（谓词恒真的那个）也返回 null。

**上游源码看着没问题**（`develop` 分支）：

```kotlin
fun KeePassDatabase.getGroupBy(predicate: Group.() -> Boolean): Group? =
    if (predicate(content.group)) content.group
    else content.group.findChildGroup(null, predicate)?.let { (_, group) -> group }
```

字节码也与之逐条对应（`ifeq` → else 分支）。⇒ **行为与源码不符，原因未查清**
（怀疑与 `findChildGroup` 内部的 `GroupOverride` 判定或某个其它分支有关），
但**这不重要** —— 重要的是结论：

> **不能拿它做数据安全判断。** 它返回 null 时的后果是"以为分组不存在"，
> 而我们的用法正是"分组存在性守卫"（防 `modifyGroup` 匹配不到 ⇒ 条目静默落到根组）。

**处置**：生产代码与测试都**不再用 `getGroupBy`/`getEntryBy` 类查找**，
改成自己走一遍树（`Group.containsGroup`，几行、确定性由我们掌握）。
⚠️ 注意区分上游同一族的几个函数，它们的 lambda 形态**不一样**，
混用直接编译不过（本项目已踩两次）：

| 函数 | 谓词形态 | 写法 |
|---|---|---|
| `getEntry`（无 By） | 普通 lambda | `{ it.uuid == x }` |
| `getEntryBy` | **接收者** lambda | `{ uuid == x }` |
| `getGroup`（无 By） | 普通 lambda | `{ it.uuid == x }` |
| `getGroupBy` | **接收者** lambda | `{ uuid == x }`（⚠️ 就是本文这条，别用） |

**判据（写进纪律）**：
> **第三方 API 的行为与它的源码不一致时，以"行为"为准，并且不要再依赖它做判断。**
> 尤其是"找不到就返回 null"这种**静默失败**签名 —— 它会让调用方把
> "查不动"当成"不存在"。自己写几行遍历比赌一个行为可疑的 API 便宜得多。

### 106.1 ✅ 已修（2026-10-01，批次 W2 + W3）

阶段 B 的写回上线，本条的两半都被处理掉了：

| 环节 | 之前 | 现在 |
|---|---|---|
| KDBX 写路径 | **无 kind 判断** ⇒ 写出一条 Room 孤儿行（新建）/ 报"条目不存在"（编辑） | 五个写方法各自**先分流**到 `KdbxItemRepository` |
| Room 侧 | 被写脏 | **一个字节都不碰**（有用例逐条 `coVerify(exactly = 0)` 钉住） |
| UI | 藏起「+ / 编辑 / 删除」并用一行提示说明"只读" | 闸**撤掉**：两种库都能增删改 |

★ 那条"KDBX 写被拒绝"的回归用例（`ItemRepositoryImplTest`）已按新行为**重写**，
并补了一条**反证**用例（"Room 没被写"也可能是"整条路径根本没跑" ⇒ 必须同时断言
"委托调用确实发生了"）。这正是「变异验证」要解决的问题：**断言"没发生"的用例天生弱**。

⚠️ 残留的"不支持"只剩两处（都不属于本条）：
**通行密钥的写**（`KPEX_*` 由浏览器/服务端创建）与**旧孤儿行的回收站清理**。
`requireWritable` 因此改名 `requireNotKdbx(vaultId, reason)` —— 名字再叫"只读"会误导。

---

## 157. 🔴 KDBX 软删除 / 永久删除**谎报成功**（2026-10-02，双库健康度审计）

- **现象**：删一个不存在的条目（并发做两次删除、或队列幂等重试），
  界面弹「已移入回收站 / 已永久删除」，条目却还在原地。
- **根因**：`KdbxWriteResult.applied` **默认 true**，而 `moveEntry`/`removeEntry` 找不到目标时
  **不抛异常、原样返回旧库** ⇒ `applied` 一路保持 true ⇒ 上层把"什么都没改"当成成功，
  去落盘一份内容与原来一模一样的文件。同一个 helper 里的对比：
  `restoreFromRecycleBin` **早就有**这个守卫，另外两个没有 —— 同一份代码里两样写法。
- **解法**：两个函数开头补 `database.getEntry { it.uuid == uuid } ?: return ...applied = false`。
- **⚠️ 判据**：**密码管理器里最坏的错是"谎报成功"**。默认值取"成功" ⇔ 把"没做存在性校验"
  这条缺省路径变成了上报者的"成功"。凡是表达「改动是否真的发生了」的标志位，
  默认必须是"没发生"，让每条路径**显式证明**改动真的发生了，才敢往上报告成功。

## 158. ⭐ KDBX **主写路径**绕过往返自检，而"保底"的 `Kdbx.save(File)` 是一条死路径（2026-10-02）

- **现象**：`Docs/09` 与 `SafKdbxFileSource` 的 KDoc 都写着"本地写回由上层 `Kdbx.save`
  保障（往返自检 + 原子替换 + `.kdbx.bak` 备份）"。**这个前提在代码里不成立**：
  `Kdbx.save(File)` 全项目**零生产调用点**；真正的主路径 `KdbxItemRepository.persist`
  此前直接 `source.write(bytes)`，一步自检都没做。
- **代价**：一旦出现编码损坏（kotpass 对未知 XML 标签静默丢弃）只能等下次打开才发现；
  网盘库更糟 —— 坏字节会上云，**把别的设备上的好副本一起覆盖**。
- **解法**：① 新加 `Kdbx.verifyWrite(vaultId, bytes)` 走 `KdbxRoundTrip.verifyEncoded`
  —— **只解码不重编码**（单次编码代价＝一次 KDF，"太贵所以被跳过"从来不该是一场正确性保障的下场）；
  ② `persist` 在写之前调用它，**本地与网盘两个分支共用同一个咽点**；
  ③ 五个写动作统一 `withContext(Dispatchers.IO)`（`mutate` 的 KDoc 早就要求调用方切调度器，
  而 ViewModel 在主线程协程里调它们 ⇒ 每次保存同步跑一遍 KDF）；
  ④ 把 `SafKdbxFileSource` 那句**假前提注释改成如实陈述**（✅ 自检 / ✅ 写后读回 /
  ❌ `.bak` 备份：SAF 拿不到兄弟文件写权限，**本路径做不到** —— 与其假装，不如不承诺）。
- **⚠️ 判据（与 #147 同源）**：**注释/KDoc 里写着"由某某兜底"，而那个某某没人调用**
  —— 这是比"没有兜底"更危险的状态：它让"这条链是通的"在审查时**看起来成立**。
  写下这种句子就要当场确认那个调用点存在。

---

## 164. B6：KDBX 通行密钥「读得到、存不下」—— writer 早就写好了，仓储层一刀切拒绝（2026-10-13，边界审计新发现）

- **现象**：KDBX 库的登录条目详情页**能看到**通行密钥列表，一保存就得到一句
  「KDBX 库的通行密钥由浏览器 / 服务端管理，本应用不能修改」
  （`PASSKEY_READ_ONLY_REASON`）。读写在用户眼里是一件事，这里只做了一半。
- **根因**：写侧 `KdbxItemWriter.applyPasskeys` 自 W2（2026-10-06）起就写好了
  （私钥 PEM / credentialId / userHandle 强制 `EntryValue.Encrypted`），
  但仓储层 `updateFido2Credentials` / `removeFido2Credential` **第一行就是
  `requireNotKdbx`** —— 能力在 writer 里、开关在仓储层，两层互不感知地漂移了三个月。
- **解法**：两个方法去掉一刀切拒绝；解锁校验改**双会话模型各问各的**
  （Bitwarden 内存密钥 / KDBX 内存整库会话）；`loadItem` 增 KDBX 分支
  （条目活在内存会话，回收站在 `trashItems`，`items` 取不到即「不存在」——
  与 Bitwarden 侧 `deletedDate` 视同不存在同口径）；写回经 `updateItem` 既有分流
  委托 `KdbxItemRepository`。`requireNotKdbx` 随之收缩为只剩「旧孤儿行回收站清理」
  一件事，常量更名 `KDBX_TRASH_CLEANUP_REASON`。详情页文案
  「由服务器 / 平台管理」→「不可编辑」（KDBX 现在确实能编辑，旧文案是错的）。
- **⚠️ 判据**：**「下游能力就绪」从来不是「上游放行」的证据** ——
  `applyPasskeys` 存在了三个月，没有一个调用点。审计必须端到端对拍
  「谁能读 → 谁能写 → 中间谁拦着」，单看任何一层都会得出错误结论。
- **测试**：`ItemRepositoryImplTest` 2 例 —— 委托确实发生 + 委托体 = 原条目透传 +
  Room 侧完全安静；删除单条时**播种即挂两条完整凭证**（不借道 mock 预写，
  防「从来只有一条」假绿）。

## 165. A1：KDBX 收藏是静默 no-op —— 读写两头都没有（2026-10-13）

- **现象**：KDBX 库里给条目点星标，退出再进**星没了**；「收藏」快捷筛选
  对 KDBX 库**恒为空**。同一个星标，Bitwarden 库（Room 有列）正常。
- **根因**：领域模型有 `VaultItem.favorite`，KDBX 侧 **mapper 不读、writer 不写**
  —— 整个字段读写双缺，点星是一个没有任何下游的 UI 动作。
- **存储选型（KDoc 有完整候选表）**：KDBX 没有「收藏」的标准字段。逐个否决：
  复用 `Tags`（用户可见可编辑，会被当普通标签删掉，且别的工具会显示出来）/
  复用自定义字段名 `Favorite`（用户完全可能自己起同名 ⇒ **撞名即互相覆盖**，
  正是 R1 要防的事故）/ `Icon`（展示层信息，语义不对）。
  ⇒ 落工具区 `VPX_FAVORITE`：私有前缀不撞名；读侧 `customFieldsOf` 按工具区排除
  （不会以自定义字段身份泄漏进详情页）。
- **读写纪律**：读 = 宽松真值（`true`/`1`/`yes`，大小写不敏感），缺失即假；
  写 = **值未变一字不动**（第三方写的 `"1"` 继续是 `"1"`，一次无关编辑不该把
  用户的库改写得满是 `"true"`）、收藏 ⇒ 写 `"true"`、取消收藏 ⇒ **删键**
  （不写 `"false"`，缺失即假，与「从未收藏」在文件里同形）。
- **⚠️ 判据（与 #22「Mapper 读写字段不对称」同族）**：那次是**一个字段读写不对称**，
  这次是**整个字段读写双缺**。选私有前缀键而不复用任何用户可见键，是因为
  **用户能看见的键，用户就能改** —— 收藏这种"机器语义"不能寄存在用户可编辑的载体上。
- **测试**：`KdbxItemWriterTest` 4 例（写键+读回+不泄漏自定义字段 / 不写空字段 /
  取消删键 / 未变更不重写）。

## 166. A2：KDBX 文件夹 App 内完全不可见 —— mapper 早就映射好了，缺的只是消费方（2026-10-13）

- **现象**：KDBX 库的分组**四处不可见**：表单的文件夹下拉框恒为空、
  「按文件夹分组」视图里所有条目都落「无文件夹」、JSON 导出丢分组、
  导入时全部落根组。
- **根因**：`FolderRepositoryImpl.observeFolders` **只绑 Room**
  （`folderDao.observeByVault`），而 `KdbxMappedContent.folders` 其实早就映射好了
  —— 数据在、没人读，缺的只是一个按库类型分流的消费方。
- **解法**：读侧判据与 `ItemRepositoryImpl` **逐字同构**
  （`vaultDao.observeAll → VaultKind.fromName → distinctUntilChanged → flatMapLatest`，
  每次订阅重新解析种类，不引入缓存真相源）；KDBX 分支用 `KdbxSessionFlow`
  当触发器（会话是纯内存结构、没有可订阅 Flow，解锁/锁定/移除库 bump 一次重读）；
  未解锁返回空列表（与 Bitwarden 侧同口径，**不泄露「这个库有几个组」**）。
- **⚠️ 判据（与 #147「恒定失败的占位实现」同源）**：**「映射好了」不等于「用户看得见」**
  —— 中间隔着的消费方断一节，数据就是不可见。两条都是「链看着通、其实断」，
  审查时的正确姿势是**从用户可见的那个端点倒着往源头走一遍**。
- **测试**：新增 `FolderRepositoryImplTest` 4 例。

