# 05-KDBX本地库 · 第 5 篇（#224 ~）

> 主篇索引：[`05-KDBX本地库.md`](./05-KDBX本地库.md)（含计数规则与重号警告）
> 本篇 1 章 · 覆盖 #224 起

## 224. 🔴 「转换无损」要逐字段对账，不能凭印象说「齐了」（2026-11-01，用户追问「还有别的没补全的吗」）

- **背景**：#219 接通了载荷读写、#223 修正了键名生态。两轮之后"该补的都补了"听起来很自然 ——
  但这两轮解决的是**「字段有没有」和「键名通不通」**，从没做过**全字段逐项对账**。
  用户直接问到点上：JSON→KDBX 涉及的数据**不能遗漏、不能错误**。
- **做法**：不读代码凭印象，而是**写一条对账测试让缺口自己露出来** ——
  `allFieldsItem()` 给领域模型的**每一个字段**填互不相同的哨兵值，
  `build → read` 后逐字段 `assertWithMessage`（刻意不用整对象 `isEqualTo`：
  整对象失败只给一大坨 diff，看不出是哪一项丢）。
- **当场抓出 6 处真缺口**（全部已修）：

  | # | 缺口 | 后果 |
  |---|---|---|
  | 1 | `organizationId` / `collectionIds` **完全没写** | 组织库凭证转回 Bitwarden 时落进个人库 —— **权限事故**，且无提示 |
  | 2 | `CustomFieldType.Boolean` → `Text`，`Linked` 的 `linkedId` 丢失 | 开关变成一行 "true"；「这个字段就是用户名」的语义没了，自动填充不再认 |
  | 3 | 应用 URI（`androidapp://`）的 `UriMatch.Never` 读回成 null | 匹配策略被改写，自动填充行为变化 |
  | 4 | 多个应用 URI 只保留第一个，且**顺序丢失** | 「一条登录挂 2 个 App」往返后剩 1 个 |
  | 5 | 通行密钥 `counter` / `discoverable` 恒被写成 0 / true | 防克隆计数清零（安全信号）、免用户名登录配置被改 |
  | 6 | `App Package Name` 漏进自定义字段区 | 详情页重复展示同一事实 |

- **修法**：
  1. 新增 `VPX_BW_ORG` / `VPX_BW_COLLECTIONS`（`BW_*` 家族同款「只写、由转换器还原」），
     `provenanceFields` 写、`restoreProvenance` 还原；
  2. 新增 `VPX_FIELD_META_<字段名>` 承载自定义字段类型与 `linkedId`。
     ⚠️ **按字段名索引，不是按下标** —— 按下标时删掉一个字段会让后面所有字段的类型**张冠李戴**；
  3. URI 改为**按需升级**：仅当列表含应用 URI 才用「原始下标全量」格式（写 `VPX_URL_0..n`），
     纯 web 列表保持旧格式（零冗余、字节不变）；读侧靠 `VPX_URL_0` 是否存在分流；
  4. 通行密钥新增 `VPX_PASSKEY_META = "<counter>:<discoverable?1:0>"`。
     ⚠️ 与 #33「本应用生成的新凭证 counter 从 0 开始」**不冲突**：那条针对「生成」，
     转换场景的角色是**搬运工，不许改写数据**。此前两者混成一条，导致转换也清零；
  5. `isToolFieldName` 纳入应用包名键（`APP_PACKAGE_KEYS`）⇒ 不再重复出现在自定义字段区。
- **补充修掉的（对账过程连带发现）**：
  - `KdbxItemWriter.createEntry` 新增 `extraFields` 参数。转换器此前是**建完再 `modifyEntry` 补写**
    provenance，而 kotpass 的 `modifyEntry` 会把 `lastModificationTime` 强制重置成 `Instant.now()`
    （反编译 `EntryKt.modifyEntry` 核实）⇒ `createdAt` 保住了、`updatedAt` **静默变成导入时刻**。
    `updateEntry` 同理，加了一次「上游盖完时间戳之后重算余数」的修正。
  - `BitwardenExportMapper` / `BitwardenImportMapper` 补 `organizationId` / `collectionIds`。
    导出侧文件头注释写着「恒输出」，构造时**根本没传这两个参数**（注释与代码不一致）。
- **验证**：kdbx 229 例 + bitwarden 全绿；新增/改写 8 条用例，覆盖上表 6 项 + 组织往返 + 时长往返。
- **⚠️ 三个教训**：
  1. **「逐功能测试全绿」证明不了「全字段无损」**。逐个功能写的往返测试天然漏掉
     「领域模型加了字段、写侧没人补」—— 而这正是这类转换器最典型的坏法。
     **验收要有一条集中清单式的对账用例**：加字段的人只要往清单里加一行，漏写当场红。
  2. **注释不是事实**。`BitwardenExportMapper` 的文件头写着「`organizationId` 恒输出」，
     而 `BitwardenExportCipher(...)` 的构造参数里根本没有它们。**读注释会得到与读代码相反的结论**。
  3. **「已知取舍」也要定期证伪**。旧测试 `多个应用 URI 时只保留第一个（这是已知取舍不是 bug）`
     的理由是「`VPX_URL_n` 里的 `androidapp://` 会被 KeePassXC 当域名匹配」；
     查 KeePassXC 源码 `KdbxXmlReader.cpp` / `KeePass2.cpp`：**全文没有 `AdditionalUrlAttribute`
     引用、也不扫全部字段找 URL**，`KP2A_URL_n` 只出现在 Bitwarden/ProtonPass **导入器**（写方向）。
     理由不成立 ⇒ 改为全部保留。**写着「已知取舍」的注释最容易挡住后来人复核。**
- **判据**：**「格式装不下」≠「可以丢」**。KDBX 没有组织概念、没有字段类型概念、
  `<Times>` 只到整秒 —— 三者都是真的，但都不是丢数据的理由：
  用 `VPX_` 私有键带走，KDBX 侧看不见，双向转换时原样还回去。
  判断标准是「用户会不会在某一天发现数据变了」，而不是「KDBX 有没有这个概念」。
- **仍未解决的格式限制（如实记录，不假装齐了）**：
  | 项 | 原因 |
  |---|---|
  | 附件 | Bitwarden **明文 JSON 导出不含附件**（官方行为，已核实）；KDBX 侧受 kotpass `Meta.binaries` 为 `@PublishedApi internal` 限制，只能反射 |
  | `deletedDate`（回收站） | Bitwarden 明文 JSON **不含回收站条目**（官方行为） |
  | `<Times>` 毫秒 | 加密 v4 的 `<Times>` 是「自公元 1 年起的**整秒**」8 字节二进制（`InstantKt.marshal`：`getEpochSecond() + 62135596800L`）⇒ **格式层就没有毫秒**。已用 `VPX_TIME_MS` 补存秒以下余数解掉，但**第三方工具读不到它** |
