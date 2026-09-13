# ISSUES 分篇 · KDBX 本地库

> 索引：[`../ISSUES.md`](../ISSUES.md)（编号 → 分篇）· 共 **3** 条：#59、#64、#69

---

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

