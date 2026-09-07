# 05 · KDBX 存储引擎

## 1. 职责边界

`data:kdbx` 负责：解析/序列化 KDBX 文件、管理 Group/Entry 树、保护流加解密、附件读写、原子保存与冲突检测。**对外只暴露领域模型**，UI 与 Domain 不接触 XML。

## 2. 文件格式支持

**只支持 KDBX 4.x（major = 4），写入恒为 4.1。**

| 能力 | 支持情况 |
|---|---|
| 读取 KDBX 4.1 / 4.0 | ✅（4.0 打开后保存即升级为 4.1） |
| 写入 | ✅ **仅 KDBX 4.1** |
| 读取 KDBX 3.1 及更早 | ❌ 直接拒绝并提示转换 |
| 外层加密 AES-256-CBC | ✅（新建库默认） |
| 外层加密 ChaCha20 | ✅（可切换） |
| 外层加密 Twofish | ❌ |
| KDF Argon2id | ✅（唯一支持的 KDF） |
| KDF AES-KDF / Argon2d | ❌ 报错，不静默降级 |
| 内层保护流 ChaCha20 | ✅（唯一） |
| 内层保护流 Salsa20 / RC4 | ❌ 报错 |
| 压缩 GZip | ✅（可关闭） |
| 历史记录 `History` | ✅ |
| 附件（KDBX4 内层 header 二进制表） | ✅ |
| 自定义图标 | ✅（读取使用，编辑延后） |
| 自定义数据 `CustomData` | ✅（Meta / Group / Entry 级） |
| 密钥文件（XML / 32B 二进制 / 任意文件） | ✅ |

密码学细节见 [03 · 密码学与密钥管理](./03-密码学与密钥管理.md#3-kdbx-侧)。

## 3. 库文件接入方式

```
用户通过 SAF（ACTION_OPEN_DOCUMENT）选择 .kdbx
  → takePersistableUriPermission（READ_WRITE | PERSISTABLE）
  → 记录 URI + 显示名 + 上次哈希/修改时间
  → 可选：设置"自动保存"（保存时通过 contentResolver 写回原 URI）
```

| 来源 | 方案 |
|---|---|
| 本地文件 | SAF URI，持久授权 |
| WebDAV / SMB / 云盘 | 由用户安装的同步 App 自行同步本地目录；Vaultix 直接打开**本地副本** |
| 内置 WebDAV 客户端 | V1.5，若实现需独立模块并强制 TLS |

**冲突检测**：打开前比较 `(size, lastModified, 前 4KB 哈希)` 与上次记录；发现外部修改即提示"文件已变更"。

## 4. 内存模型

```kotlin
class KdbxSession(
    val meta: KdbxMeta,
    val root: GroupNode,              // Group 树
    val entriesById: Map<UUID, EntryNode>,
    val innerStreamKey: SecureBytes,  // 保护流密钥（SHA-256 后使用）
    val innerStreamId: InnerStream,   // 恒为 ChaCha20（打开时校验，非此值即报错）
    val binaries: Map<Int, BinaryRef>,// 附件
    val cipherKey: SecureBytes,
    val hmacKey: SecureBytes,
) : Closeable { override fun close() { /* 全部 zero() */ } }
```

- **Protected 字段在内存中保持密文**：`EntryNode` 的 `Password`、`StringField(Protected=True)` 存的是 Base64 密文，只有调用 `decryptField()` 才解。
- 图标/附件按需加载，附件默认不读入内存（仅持文件偏移或 URI）。
- 锁定/关闭时 `close()` 清零所有 `SecureBytes`。

## 5. 读写流程

### 5.1 打开

```
1. 通过 contentResolver 打开 InputStream，读取签名 + 版本号
   - major ≠ 4（即 KDBX 3.1 及更早）→ UnsupportedFormat，提示用 KeePassXC 转换为 4.x
   - minor > 1（如未来的 4.2）→ 提示"存在未知字段，保存可能丢失"，仍允许只读打开
   - Minor 为 0（KDBX 4.0）→ 正常打开，保存时自动写为 4.1
2. 解析外层 header（含 VariantDictionary 的 KDF 参数）
   - `$UUID` ≠ Argon2id → UnsupportedFormat("该库使用了不受支持的 KDF：AES-KDF / Argon2d")
   - CipherID 非 AES-256-CBC / ChaCha20 → UnsupportedFormat("Twofish 等遗留算法不支持")
3. 组装凭证：
   - 主密码：SecureString（CharSequence，用后立即清零）
   - KeyFile：
     · XML 格式 → 取 <Data> 的 Base64 内容
     · 32 字节二进制 → 直接使用
     · 其他长度 / 任意文件 → SHA-256(文件内容)
   - 在 UI 上明确区分"密码 / 密钥文件 / 两者都需要"
4. Argon2id 派生 → 校验 header HMAC → 逐块校验 → 解密 → 解压 → 读内层 header
   - `InnerRandomStreamID ≠ 3` → UnsupportedFormat("保护流非 ChaCha20，请转换数据库")
5. 构建 KdbxSession；Protected 保持密文
```

### 5.2 保存

```
1. 在内存模型上序列化为 KDBX XML（Protected 字段用新建的 ChaCha20 保护流重新加密）
2. 生成新的 MasterSeed、IV、Argon2id salt（每次保存都换 → 同密码不同密文）
3. 压缩（可选 GZip）→ 加密（AES-256-CBC 或 ChaCha20）→ 分块并计算每块 HMAC
4. 组装：签名 + 版本字 `0x00040001` + 外层 header + header SHA-256 + header HMAC + 块流
5. 原子写：写 <target>.tmp → fsync → rename → 校验回读（重新解析 header + 首块 HMAC）
6. 更新本地记录（size/mtime/hash）
```

**自动保存策略**（可配置）：

| 触发 | 默认 |
|---|---|
| 每次修改后立即保存 | 关（大库开销大） |
| 修改后 30 s 空闲 | 开 |
| 切后台 / 锁定前 | **强制开** |
| 定时 5 分钟 | 关 |

保存前若检测到外部变更 → 弹窗三选一：覆盖 / 重载（丢弃本地）/ 另存为。

## 6. 数据映射与兼容约定

| 领域概念 | KDBX 表达 | 兼容处理 |
|---|---|---|
| 条目类型 | `Vaultix.Type` 自定义字符串字段 | 缺省按 `LOGIN` |
| 收藏 | `Vaultix.Favorite` = `true` 或 `Tags` 含 `Favorite` | 读取时兼容 KeePassDX/KeePass2Android 的 `Favorite` 标签 |
| 重验证主密码 | `Vaultix.Reprompt` | — |
| TOTP | `otp` 字段（`otpauth://...`）或 `TOTP Seed`/`TOTP Settings` 组合 | 兼容 KeePassXC 与 KeePass2Android 两种写法 |
| 条目颜色/图标 | `CustomIconUUID` / `ForegroundColor` | 展示 |
| 回收站 | `Meta/RecycleBinUuid` 指向的 Group | 若未启用，删除即永久删除（提示用户） |
| 附件 | `Binary`（Key/Value）+ 内层 header 二进制项 | 大附件 > 5 MB 提示性能影响 |

**导出兼容模式**：勾选后不写入任何 `Vaultix.*` 字段，保证 KeePass 原版（2.x，KDBX 4.x）可无损读取（收藏、重验证等信息会丢失，UI 需预告）。
（无 KDBX 3.1 导出路径——旧格式不再支持。）

## 7. 性能与体积

| 场景 | 策略 |
|---|---|
| 大库（> 5000 条目） | 流式 XML 解析；分页加载列表；索引复用 |
| Argon2 内存 | 按设备可用内存动态下调（低端机 32 MiB），并提示"参数低于推荐" |
| 附件 | 懒加载；导出到临时文件用 `FileProvider` 分享 |
| KDBX 4.0 → 4.1 升级 | 打开旧 minor 版本后保存即升级（无损）；保存前强制备份提示 |

## 8. 测试要求

必须包含（见 [12](./12-测试与发布.md)）：

- **官方测试向量**：KeePass 官方与社区提供的 **KDBX 4.0 / 4.1** 样例库（Argon2id × AES-256-CBC / ChaCha20、含附件、历史记录、回收站、自定义图标）必须能正确打开。
- **拒绝用例**：KDBX 3.1 文件、AES-KDF 参数的 4.x 文件、Argon2d 文件、Salsa20 保护流文件、Twofish 文件 → 均须返回准确的 `UnsupportedFormat` 原因，且不得部分解密。
- **往返测试**：随机生成库 → 保存 → 重新打开 → 全字段断言一致。
- **交叉测试**：Vaultix 写出的文件能被 KeePass2 / KeePassXC 打开；反之亦然。
- **损坏测试**：逐字节翻转 header / 中间块 / 末尾，验证均被检出且给出准确错误阶段。
