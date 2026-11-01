# 库选择与快速解锁-逻辑定稿 · 第 2 篇

> 主篇索引：[`库选择与快速解锁-逻辑定稿.md`](./库选择与快速解锁-逻辑定稿.md)
> 本篇 3 章 · 10.1KB · 起于「§7 下一会话：第一批任务清单（用户已认可分批）」

## §7 下一会话：第一批任务清单（用户已认可分批）

> **门槛纪律**：detekt → 编译 → **单测**，三门禁全绿才算完成
> （⚠️ detekt 先于编译，会**掩盖编译错误**，改完必须**再真跑一次 compile**）。

### 第一批（能立刻消掉真实痛点，优先做）—— ✅ **已全部落地（2026-09-14 第五十四轮）**

| 序 | 任务 | 对应 issue | 关键点 | 状态 |
|---|---|---|---|---|
| 1 | **拆「活跃库 / 默认库」两键** | §3 新发现 | `ActiveVaultStore.select()` 改只写 `activeVaultId`（**不再写 default**）；`defaultVaultId` 唯一写入点 = 设置页（`SettingsViewModel.setDefaultVault`）。**同步修 `ActiveVaultStoreTest`**（原断言固化的正是该 bug） | ✅ |
| 2 | **补「切换密码库」正面入口** | **#96** | `ItemsMoreMenu` 最上方新增「切换密码库」（`SwapHoriz`），**仅 >1 库时显示**（`MainShellViewModel.vaultCount`）；设置页 `switchableVaults` 改为**列全部库**、未解锁项标注并可点进去 | ✅ |
| 3 | **修 KDBX 行裸露 `content://` URI** | **#95** | `VaultListScreen` 新增 `vaultSubtitle()`：KDBX 走「本地文件 · 文件名」，Bitwarden 保留 account/origin | ✅ |
| 4 | **补重复添加同一 kdbx 的反馈** | **#94** | 新增 `KdbxAddOutcome`（Added/Updated/Failed），`addKdbxVault` 返回它；`AddKdbxScreen` 补 Snackbar。**D5 定稿 = 允许覆盖 + 成功反馈** | ✅ |
| 5 | **冷启动默认库优先 + 解锁页换库出口** | #96 / §1⑤ | `RootNavViewModel` 全锁分支改为 `VaultLocked(vaultId = 默认库.takeIf{在列表里} ?: vaults.first().id)`；`UnlockScreen` 新增 `onSwitchVault` | ✅ |

**门槛结果**：detekt ✅ / `:app:compileFullDebugKotlin` ✅ / `testFullDebugUnitTest` ✅
（app 模块 **162 项、0 失败**；`ActiveVaultStoreTest` 7/7、`RootNavViewModelTest` 10/10）。
⚠️ 构建备忘：`app` 是 **flavor 化**的，任务名是 `compileFullDebugKotlin` 而非
`compileDebugKotlin`；`./gradlew` 在本机 git-bash 会报 `GradleWrapperMain` 找不到，
改用已解压发行版直接调用。

### 第二批（体量大、风险高，单独一批）

| 序 | 任务 | 对应 issue | 关键点 | 状态 |
|---|---|---|---|---|
| 6 | **KDBX 快速解锁（生效范围 UI + `enrollLocalUnlockKdbx`）** | **#93** + §4 | 新增仓库方法 + 主密码输入对话框（宽松重试）+ **wrap 前必须校验** | ✅ |
| 7 | **SSH 键输入表单** | **#97** | `ItemFormDialog.kt` 原为 `SecureNote, SshKey -> Unit` ⇒ 只能建空壳；已补 `SshKeyFields` + 指纹**自动推导** | ✅ |

#### 第 6 项落地记录（2026-09-14 第五十五轮）

**新增文件**
- `data/repository/.../KdbxUnlockPayload.kt` —— 包裹物编解码。**必须长度前缀**：
  主密码与 keyfile 都是变长项，裸拼会出现
  `("ab", [0x63])` 与 `("abc", null)` 编出**同一串字节**的歧义。

**新增契约**（`domain/VaultRepository.kt`）
- `enrollLocalUnlockKdbx(vaultId, masterPassword, keyFileUri, cipher) → KdbxEnrollOutcome`
- `completeLocalUnlockKdbx(vaultId, cipher) → KdbxUnlockOutcome`
- 与 Bitwarden 侧**分成两个方法**而非重载：包裹物本质不同
  （密钥 vs 主密码 + keyfile），混用会解出错误语义。

**关键实现约束（都有注释钉住）**
1. ★ **先校验、后包裹**：`enrollLocalUnlockKdbx` 先用这组凭据**真的解一次库**
   （复用 `unlockKdbxInternal`），失败即返回且**不写任何东西**。
   `wrap` 只管封字节、不管字节对不对 —— 先包后校会得到「启用成功但躺的是错密码」。
2. ★ **keyfile 走字节不走临时文件**：`Kdbx.unlock` 新增可选 `keyFileBytes`
   参数（默认 null，向后兼容）。原方案要为此把 keyfile 落成临时文件，
   与「明文绝不落盘」冲突 —— 改签名比写盘干净。
3. **明文用完即擦**：包裹物明文、解出的 keyfile 字节都 `fill(0)`。

**解锁路径按库类型分流**（三处，缺一处就是错语义）
- `UnlockViewModel.completeLocalUnlock`
- `AutofillActivity.unlockAll`
- 库列表 / 设置页的**启用**路径

**「谎报状态」的根治**（§4.2 的病根）
- 设置页 `QuickUnlockManageDialog` 改为「**生效范围**」语义（用户 §4.7 的提法），
  逐库列出并标注 KDBX 需先输主密码。
- KDBX 分支改走 `startKdbxQuickUnlock` → 主密码框 → 校验 → 指纹（宽松重试）。
  横幅（`VaultListScreen`）同款。
- `QuickUnlockVaultUi` 增 `kind`：两条登记流程的差异必须在 UI 可见，
  否则用户不明白「为什么点 KDBX 会弹密码框」。

**D3（失效自愈）落地**：`KdbxUnlockOutcome.StaleCredentials`（指纹过了但
包裹物打不开）→ UI 文案改为「**主密码可能已在别处变更，请输入当前主密码**」，
走进主密码路径即可自愈；**不删快速解锁登记**（与 Bitwarden 侧「不可恢复即清」
取向不同 —— 那侧包裹物是密钥）。

**测试**：`KdbxUnlockPayloadTest` 9 项全绿（含两条★歧义/截断不变量）；
`ActiveVaultStoreTest` 增至 9 项。

#### 第 7 项落地记录（2026-09-14 第五十六轮）

**新增文件**
- `core/common/.../SshFingerprint.kt` —— OpenSSH 公钥指纹（`SHA256:<base64>`），
  算法与 `ssh-keygen -lf` **逐位一致**（ed25519 / RSA / ECDSA 三组对照）。

**新增逻辑**（`FormValues.kt`，纯函数、可单测）
- `sshValuesOf` / `buildSshKey` —— 与 `cardValuesOf` / `buildCard` 同款读写对称。
- `applyPublicKeyChange(values, newPublicKey)` —— 指纹跟随公钥的策略。
- 三个下标常量 `SSH_PRIVATE_KEY_INDEX` / `SSH_PUBLIC_KEY_INDEX` /
  `SSH_FINGERPRINT_INDEX`：**三处共用**，杜绝「公钥填进指纹框」这类错位。

**⚠️ 接口取舍：`ssh` 为什么是 `buildSnapshot` 的第 5 个参数**
`FormValues` 已占满 8 个参数，正卡在 detekt `allowedConstructorParameters: 8` 的
硬上限 —— 加第 9 个直接违规。这与当年 `ItemMeta` 被拆出去**同一个理由**，
沿用同一做法（而非放宽门禁）。

**★ 指纹派生策略：比较值，而不是记 `fingerprintEdited` 标志位**
判据 = 「当前指纹为空 **或** == 旧公钥的推导值」⇒ 判为自动、可覆盖；否则
视为用户自定义、原样保留。**比较法的好处是可自愈** —— 用户把手改的值又改回
推导值（或清空）时自动重回跟随，不会永久卡在「手动」。
- 推导失败 ⇒ 写**空串**而非保留旧值：宁可空着，也不能挂一个与当前公钥不符的
  指纹（会骗人的错信息，属 #84「三种空」纪律）。
- 初始化时：指纹为空而公钥可解析 ⇒ **补上**（导入条目常缺指纹）；
  已存有指纹 ⇒ **绝不覆盖**，避免静默重写数据。
- ⚠️ 已知边界：旧指纹若是**遗留 MD5 格式**，永不等于 SHA256 推导值 ⇒ 判为
  自定义而保留 ⇒ 改公钥后会陈旧。取舍：「尊重用户填过的值」优先。

**与原计划的两处有意偏离**
1. 原写「私钥默认掩码 + 可切换可见」⇒ **改为明文**：详情页 `SshKeySection`
   本就是明文 + 复制；表单若遮蔽，用户**没法确认自己粘对了没有**，
   而那正是本表单要解决的问题。两侧一致优于单侧"更安全"。
2. 原写字段名 `fingerprint` ⇒ 模型实为 `keyFingerprint`（对齐 Bitwarden
   `JsonSshKey`），按模型来。

**写路径无需改动**（原本就通）：`CipherMapper.overlaySshKey` 在
`item.type == SshKey` 时按 `item.sshKey` 重新加密覆盖 ⇒ 本问题是
**纯输入侧缺口**，不是数据链路问题。

**顺手清理**：`item_edit_type_fields_readonly` 已无引用（唯一使用点即被替换的
只读提示）⇒ 删除；SSH 字符串注释改为「详情分区与编辑表单共用」。

**测试**：`SshFingerprintTest` 11 项 + `FormValuesTest` 19 项全绿
（期望值取 `ssh-keygen -lf`，**不是**自算回填）。

### 待用户确认项 —— ✅ **均已拍板并落地（2026-09-14）**

- **D5**：重复添加同一 kdbx 文件 → **「允许覆盖 + 成功反馈」**（Snackbar 文案区分
  「已添加密码库」/「该文件已在列表中，已更新」）。
- **首次接入是否顺手设为默认库** → **是，且「仅在默认库为空时写」**。
  **已实现**（2026-09-14 第五十五轮）：
  - `VaultixPreferences.trySetDefaultVaultIfAbsent(id)` —— 判空 + 写在同一 `edit`
    事务内，避免两个库并发接入时都读到空；
  - 落点在**新库接入成功**这条业务路径（`registerVaultRow` 的新库分支 /
    `addKdbxVault` 的 `Added` 分支），**不在** `ActiveVaultStore.select()`。
    ⚠️ 这条边界是硬的：放进 `select()` 就退化成第一批修掉的那个 bug。
  - 绝不覆盖用户已设过的选择（这正是第一批任务 1 的同一种病，只换了触发点）。

---

## §8 顺带确认的两件事

1. **`VaultixPreferences.defaultVaultId` 已存在**（`:225` / `:348`）——
   **推翻**「需新增键」的旧认知。任务 1 是「**拆分**」不是「新增」。
2. **`ActiveVaultStore.pick()` 的兜底是 `unlocked.minOrNull()`**
   （`ActiveVaultStore.kt:97-102`）——按 **id 字符串字典序**，不是 `createdAt`。
   Bitwarden 的 id 是 UUID、KDBX 的是 `content://...` URI ⇒
   **谁排前面基本是随机的**。有了「默认库」后，这个兜底也应**先尊重默认库**，
   其次才是稳定排序。

---

## §9 相关文档索引

| 文档 | 内容 |
|---|---|
| `.ai/issues/05-KDBX本地库.md` | #59 / 64 / 69 / **93 / 94 / 95 / 96 / 96.1** 的根因与证据 |
| `.ai/issues/04-锁与解锁.md` | #60 锁/解锁模型规范化、#61、#73、#85 |
| `.ai/issues/06-界面与交互.md` | #84 三种「空」、#87 转场只允许 `translationX`、#88 覆盖安装后指纹失效 |
| `Docs/progress/quick-unlock-plan.md` | 快速解锁既有设计（21KB） |
| `Docs/progress/main-shell-migration.md` §0 | 「**单活跃库**」产品定义（Bitwarden 能力 + KDBX 能力，登录时二选一） |
| `.ai/SESSION-2026-09-14.md` | 本日完整流水（含真机实证链） |
