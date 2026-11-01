# 05-KDBX本地库 · 第 1 篇（#59 ~ #94）

> 主篇索引：[`05-KDBX本地库.md`](./05-KDBX本地库.md)（含计数规则与重号警告）
> 本篇 5 章 · 16.3KB · 覆盖 #59 ~ #94

## 59. M2 KDBX 落地：引擎选型与「为什么没搬 Keyguard」（2026-09-12，第四十六轮）

**用户诉求**：「Keyguard 的 KDBX 做得很好，能否搬过来移植？」——查证后结论是**不需要搬 Keyguard**，
而且搬它反而不划算。三条事实：

1. **Keyguard 本体是 `All Rights Reserved`**（仓库根 `LICENSE` 全文就一句 "All Rights Reserved"，
   README：「The source code is available for **personal use** only」）。即便拿到作者个人授权，
   把它并入**公开的 GPL-3.0 仓库**仍有再分发授权瑕疵；用户授权解的是「自己用」，解不了
   「GPL 要求我们有权以 GPL 再授权」这一层。
2. **Keyguard 的 KDBX 能力本来就来自 kotpass**：`D:\Vaultix-refs\keyguard-app\util\kdbx\` 是
   vendored 的 `app.keemobile.kotpass`，且其 `LICENSE-kotpass.txt` 明确 **MIT**
   （Copyright (c) 2021 Denis T.）。**MIT 与 GPL-3.0 兼容**，直接用上游即可。
3. 那份 vendored 副本被**改过**：`cryptography/PlatformCrypto.kt` 直接 import 了
   `com.artemchep.keyguard.util.foundation.crypto.randomBytes`（换成 Keyguard 自己的
   foundation/crypto 模块）。搬它 = 先拆 Keyguard 的补丁，纯属自找麻烦。

**定案**：`data:kdbx` 直接依赖 **`app.keemobile:kotpass:0.13.0`（Maven Central，MIT，纯 Kotlin/JVM，
唯一传递依赖 okio）**。Bastion（GPL-3.0，仓库内 `reference/bastion/`）的
`KeePassCredentialSupport` / `KeePassCodecSupport` **只作行为参考**并已在本模块注明溯源。

**本轮落地（M2 阶段 A 的引擎层，已全绿）**：
| 文件 | 职责 |
|---|---|
| `KdbxFormat.kt` | 文件头识别（签名 `03 D9 A2 9A 67 FB 4B B5` + 版本）——**先判格式再解密**，否则「选错文件」会被报成「密码错误」 |
| `KdbxCredentialCandidate.kt` | 凭据候选：keyfile 的 raw / XML `<Data>`(hex或base64) / 64 位 hex 文本 / sha256(raw) 四种形态；空密码时同时试 `key-only` 与 `empty-password+key` |
| `KdbxCipherProviders.kt` | 基础套件 + **Twofish**（KDBX 3.1 老库；不注册会「版本识别通过、解密失败」） |
| `KdbxOpener.kt` | 依次尝试候选 → 解密；结构化失败原因（NotKdbx / UnsupportedVersion / InvalidCredentials(attempted) / Unknown） |
| `KdbxItemMapper.kt` | kotpass → `VaultItem`/`VaultFolder`：**受保护字段取 `.content` 自动解密**；TOTP 多约定统一转 `otpauth://`（复用既有 `OtpUriParser`）；回收站整棵子树排除并计数；根分组不算文件夹（其子分组才是顶层） |
| `KdbxSessionStore` | 已解锁会话只存内存（锁库即丢），绝不落盘明文 |

**测试**：`KdbxReadPathTest` **10/10 通过**（真实 KDBX 字节往返：字段映射 / Hidden 自定义字段 /
TOTP→otpauth / 分组→文件夹 / 安全笔记判定 / 回收站排除 / 错误密码分类 / 非 KDBX 拒绝 / 版本识别 /
keyfile 形态）。⚠️ 加密路径的测试宁可多断言——读错一个字段用户不会立刻发现，后果却很重。

**下一步（阶段 A 集成层，尚未做）**：SAF 选文件 → 建 KDBX 库行（`vaults.kind=KDBX`、
`origin`=文件 URI，**无需迁移**）→ 主密码/keyfile 解锁 → `ItemRepositoryImpl` 读路径分流到会话
（UI/自动填充零改动）→ 可选的 local-unlock KEK 包裹 KDBX 凭据。之后才是阶段 B（写回：
原子替换 + `.kdbx.bak` 备份 + 保真度登记 + 往返测试）。
> ⚠️ **2026-10-02 更正**：本段是 2026-09-12 的**历史快照**，其中「阶段 A 集成层，尚未做」
> **早已不成立** —— 阶段 A 已由 `879e7c1`（引擎层）+ `fcaf918`（集成层：SAF 选文件 → 库行 →
> 主密码/keyfile 解锁 → 读路径分流）交付，**阶段 B 写回亦已落地**（`Kdbx.save` /
> `saveVia` / 回收站映射 `6397de1`）。本段保留原文是为了记录当时的判断链，不是现状。
> 现状见下方 `## 106.` 与 `.ai/conventions/8.3-M2KDBX.md`。

---

## 64. KDBX 集成：与 Bitwarden **不是同一套会话模型**（2026-09-12，第四十八轮）

### 三处必须记住的架构差异

| | Bitwarden | KDBX |
|---|---|---|
| 内存会话 | 一把对称密钥（`VaultSessionManager`） | **整库明文**（`data:kdbx` 的会话持有者） |
| 条目存储 | Room `ciphers` 表（密文） | 只在内存（**不落 ciphers 表**） |
| 解锁 | 联网 + 可能 2FA | 离线（文件 + 主密码 + 可选 keyfile） |

⇒ KDBX **不能**塞进 `VaultSessionManager`，也不能抄一份密文到 Room（保真度靠原文件保证，
抄一份只会引入两处真源）。于是：

1. **读路径分流**放在 `ItemRepositoryImpl.observeItems / observeTrash / observeItem`，
   UI / 自动填充侧**零改动**。
   ⚠️ 库种类是**挂起**查询（`vaultDao.get`）→ 不能写在方法体里（那会让 `observeItems`
   不再是纯函数），要放进 `flatMapLatest`。
2. **会话变化通知**单独抽成 `KdbxSessionFlow`（**只带一个代次计数、不带任何库内容**）：
   给明文会话挂 `MutableStateFlow` 会把「明文」与「可观察状态」耦合在一起
   （一次 `println(state)` 就可能把明文写进日志）。
3. **切库即锁旧库**放在 `ItemsViewModel.init`：KDBX 的「密钥」是整库明文，
   多库同时解锁会让「同时只能进一个库」的产品约束在**内存层面**失效。
   放这里而不是 ActiveVaultStore：切库由路由 / 设置页两处触发，而**真正进入某个库**
   必然经过本页初始化，懒打开语义自然一致。

### 引擎门面：内部类型绝不外泄
`KdbxSession` / `KdbxOpener` / `KdbxSessionStore` 保持 `internal`，对外只给
`Kdbx.unlock / contentOf / ...` 与 `KdbxSource`（URI → 字节的函数式接口，由
`data:repository` 用 `ContentResolver` 实现）。
**理由**：内部类型握着 kotpass 的 `KeePassDatabase`（明文整库），
一旦出现在跨模块签名里，就迟早有人把它传进日志或 UI 状态。

### 失败必须分类
`SourceUnavailable`（文件读不到）与 `InvalidCredentials`（密码/keyfile 不对）是
**用户要做的事完全不同**的两件事（重选文件 vs 重输密码）。
一律报「密码错误」会让用户反复重输一个正确的密码。

### 两个 codec 的易错点（都有单测）
- `KdbxTotpCodec`：位置式 `TOTP Settings = "30;6;SHA1"` 必须按**出现顺序**填
  （不能按「是否等于默认值」判——用户真写 30 就会串位）；
  `TimeOtp-Secret-Hex|Base64` 必须**真解码再转 base32**，
  直接当 base32 解析会**静默算错码**（字符集部分重叠，没有任何报错）。
- `KdbxPasskeyCodec`：`credentialId` 的 base64url → 标准 base64 归一，
  **解不出来必须原样保留**（硬转会把非 base64 的旧数据变成空串 = 弄丢用户的通行密钥）。
- 两个码本都提供 `isXxxFieldName`，`customFieldsOf` 用它排除专属字段 ——
  否则详情页会把**私钥 PEM / TOTP 密钥**当成「隐藏自定义字段」展示（掩码仍可复制）。

---

## 69. KDBX 添加入口挂在**不可达路由**后面 ⇒ 集成交付了却用不到（2026-09-13）

**现象**：用户「设置页面密码库，没有 kdbx 的入口，比如添加按钮之类的。
目前只有 bitwarden 一个密码库」。

**根因**：KDBX 的选文件入口只在 `VaultListScreen` 的「+」
（→ `AddVaultTypeDialog` → `AddKdbxRoute`），而 `VaultListRoute` 在整个导航图里只有 4 个入口，
且**全在「一个库都没有」或异常兜底的路径上**（`Onboarding` / `onNoVault` / 主界面 `onLocked`）。
只要已有 ≥1 个库，根导航就落在解锁页或主界面 ⇒ 库列表**不可达** ⇒
用户**永远加不了第二个库**（KDBX 集成等于没交付）。

**解法**：把 `AddVaultTypeDialog` 从 `VaultListScreen` 的私有函数提到 `ui/common`，
在设置页「密码库」分区补一行「添加密码库」，两个回调从 `VaultixApp`
经 `MainShellScreen` 一路透传到 Tab 内的 `SettingsScreen`。

**判据**：**新增入口时先画一遍「从冷启动到该入口」的可达路径。**
功能挂在一个不可达的路由后面，等于没做。

---

## 93. KDBX 快速解锁（生物识别）**结构上不可实现** ⇒ 开关永远打不开（2026-09-14）

**现象**：用户「生物验证这一块在 kdbx 上好像不起效果」。

**根因**：`enrollLocalUnlock` 的第一行就是

```kotlin
val key = sessions.keyOf(vaultId) ?: return false
```

`sessions` = `VaultSessionManager`，它是 **Bitwarden 的对称密钥仓库**（持一把 KEK/主密钥，
可被 BiometricPrompt 的 `Cipher` 包裹后落盘）。而 **KDBX 会话在 `KdbxSessionStore` 里
持的是「整库明文」，根本没有「一把可以包裹的密钥」**（见 #64 的会话模型差异）。

⇒ KDBX 库调用 `enrollLocalUnlock` **必然在第一行返回 false**；
⇒ `localUnlockAvailable` 恒为 false；
⇒ 解锁页 `quickUnlockVisible = state.localUnlockAvailable && ...` 恒为 false，
   指纹按钮**永远不显示**；
⇒ 但设置页 `quickUnlockVaults` 是**遍历所有库**生成的 ⇒ 会给 KDBX 也渲染一个
   「生物识别快速解锁」开关，用户打开它**必然无效且无任何错误提示**。

**解法（2026-09-14 用户已拍板 ✅，完整逻辑见 `.ai/decisions/库选择与快速解锁-逻辑定稿.md`）**：

⚠️ **本条的定性已精确化**（用户追问「为什么是无效的，照理说是生效的吧」）：

> **它不是一个「没生效的开关」，它是一个「谎报状态的开关」。**
> 开关值**真写入了** `preferences`（所以设置页看起来是勾上的、重启也还在），
> 但**保险箱（Keystore 包裹物）里是空的** —— 状态与现实不一致。

1. ~~最小正确：设置页对 `VaultKind.KDBX` 不渲染开关~~ ⇒ **用户否决**：
   用户要的是**真支持**，不是隐藏。
2. **采纳方案**：用 Keystore **包裹主密码 + keyfile 字节**（而非包裹会话密钥）。
   - 技术可行（已验证）：`LocalUnlockKeyStore.wrap(cipher, bytes: ByteArray)` 对字节数组
     **一视同仁**（`LocalUnlockKeyStore.kt:158-174`）；
     `KdbxOpener.open(bytes, password, keyFileBytes)` 三输入齐全即可开库。
   - ⚠️ **不能包裹「整库明文」**：与 `Kdbx.kt:18`「明文绝不落盘」直接冲突，需重构会话模型 ⇒ 不可取。
   - ⚠️ **必须新增一步「校验」**：`wrap` 只管包裹字节、**不管字节对不对**；
     用户输错密码也会 wrap 成功 ⇒ 下次指纹解出错密码 ⇒ 打开失败。
     ⇒ **wrap 之前必须用这组凭据实际解一次库**。
     （校验失败的 UI 取向：用户选 **「宽松」** —— 提示「主密码不正确」，
     **输入框保留、就地重输**，不掉出流程。）
   - ⚠️ **必然要「当场再输一次主密码」**：`KdbxSession` 不持主密码、
     `Kdbx.unlock()` 用完即弃 ⇒ 拿不到可包裹的东西 ⇒ 必须在启用时问用户要。
     （原设想「勾选只登记意愿、下次解锁时顺手包裹」已被用户方案取代 ——
     当场输密码是**即时闭环**，用户每个动作都有立刻可见的结果。）
3. **UI 方案（用户提出，优于原「分两个入口」）**：
   在**快速解锁设置里加一个「生效范围」**，勾选哪些库生效
   ⇒ 心智模型 =「**一把指纹，管理多个密码库**」（一个用户只有一把 KEK）。
   勾选 = 决定「这把钥匙串上挂几把钥匙」。
4. **失效处理（D3，用户选 B）**：指纹过了但打不开
   ⇒ 提示「主密码可能已变更」⇒ 输新密码成功后**自动重新包裹** ⇒ 下次指纹又能用。

⚠️ **安全等级提醒**（KDBX ≠ Bitwarden，将来写用户提示时措辞须审慎）：
Bitwarden 包的是**对称密钥**（泄露只够到本地缓存，可改密码止损）；
KDBX 包的是**主密码**（**全权限**，拿到即整个库失守，且**本地文件无远程撤销**）。
Android 上「指纹 + Keystore 用户认证 KEK、密文绑设备」是**标准且足够强**的做法
（KeePassDX / KeePass2Android 同款）⇒ **支持做**。

**判据**：**凡是「遍历所有库渲染能力开关」的 UI，都要先回答「这个能力对每种库类型
是否成立」。** 能力不存在时应隐藏或禁用，而不是给一个点了必然失败的开关（见 #84 同款思路：
假空态与假开关都是「让 UI 撒谎」）。

### 93.1 ✅ 已实现（2026-09-14，第五十五轮）

**新增**
- `data/repository/.../KdbxUnlockPayload.kt`：包裹物编解码（**长度前缀**，
  避免「密码尾部字节 == keyfile 首字节」的歧义）。
- `domain`：`enrollLocalUnlockKdbx` / `completeLocalUnlockKdbx` + 两个结果类型
  （`KdbxEnrollOutcome` / `KdbxUnlockOutcome`）。与 Bitwarden 侧**两个方法**而非重载。

**三条硬约束（都已在代码注释里钉住）**
1. ★ **先校验后包裹**：`enrollLocalUnlockKdbx` 先复用 `unlockKdbxInternal` 真解一次库，
   失败即返回且**不写任何东西**。
2. ★ **keyfile 不落盘**：`Kdbx.unlock` 新增可选 `keyFileBytes`（默认 null）。
   原方案要把 keyfile 写临时文件，与「明文绝不落盘」冲突 ⇒ 改签名更干净。
3. **启用必须当场输主密码**（`KdbxSession` 不持主密码）⇒ 设置页与库列表横幅
   都新增主密码输入框，**宽松重试**（输错只报错、不关框）。

**「谎报状态」根治**：设置页对话框改为「**生效范围**」语义，逐库标注
（KDBX 行写明「启用时需再输一次该库的主密码」）；`QuickUnlockVaultUi` 增 `kind`
让两条登记流程的差异在 UI 可见。

**D3 落地**：`KdbxUnlockOutcome.StaleCredentials`（指纹过了但打不开）
⇒ 文案「主密码可能已在别处变更，请输入当前主密码」⇒ 走主密码自愈；
**不删快速解锁登记**（Bitwarden 侧「不可恢复即清」的取向不适用于此）。

**解锁路径三处分流**（缺一处就是错语义）：`UnlockViewModel` / `AutofillActivity` /
启用路径。⚠️ 判据：「包裹物是什么」决定走哪条路，**不能按「库 id 长得像什么」猜**。

**测试**：`KdbxUnlockPayloadTest` 9/9 绿；`ActiveVaultStoreTest` 9/9 绿。

---

## 94. 重复添加同一 KDBX 文件 = **完全静默**（成功路径零反馈）（2026-09-14）

**现象**：用户「输入 kdbx 的密码，但是 kdbx 好像就没反应一样，打不开也没有提示」。
真机实测（`io.vaultix.vaultix`，HEAD `1b2d2ca`）日志：

```
18:35:50.443 ContentResolver.openInputStream → FileInputStream.read
18:35:50.467 Inflater.inflate → MessageDigest.digest → Mac.doFinal
18:35:50.5xx DocumentBuilder.parse（内层 XML 解出）
18:35:51.047 AutofillManager: commit() called by app
18:35:51.071 VRI: skip draw ... dirty Rect(0, 0 - 0, 0)   ← 界面一帧未重绘
```

**根因**：`addKdbxVault` 用 `vaultDao.upsert(VaultEntity(id = sourceUri, ...))`，
**文件路径即主键**。用户第二次添加**同一个文件**时：
① 文件读到、密码正确、解锁成功（日志三件套齐全）；
② `upsert` 覆盖同 id 行，**内容与原来完全一致** ⇒ 列表零变化；
③ 成功分支只发 `Event.VaultAdded` → `onAdded()` = `popBackStack()`
   ⇒ **无 Snackbar / Toast / 高亮**。

⇒ 用户的读法：**「点了一点反应都没有」**（既非失败也无成功）。

**解法（✅ 已实施，2026-09-14 第五十四轮）**：
1. ✅ 新增 `KdbxAddOutcome`（`Added` / `Updated` / `Failed`），`VaultRepository.addKdbxVault`
   返回类型由 `UnlockResult` 换成它 —— 失败路径包在 `Failed(result)` 里，UI 用 `when` 分流；
2. ✅ **D5 定稿 = 允许覆盖 + 成功反馈**（用户拍板）：`AddKdbxViewModel.Event.VaultAdded(isUpdate)`
   区分两态，`AddKdbxScreen` 补 `Scaffold(snackbarHost)`，
   文案「已添加密码库」/「该文件已在列表中，已更新」；
3. ⏳ 提交中途的 `submitting` 可见性未单独改（`LinearProgressIndicator` 已在，
   但快路径下仍可能一闪而过）—— 留待真机观察。

**判据**：**同步操作的成功路径必须有反馈。** 「什么都没发生」在密码管理器里是
最坏的一种歧义——用户无法区分「成功」「我没点中」「点了但崩了」。

---

