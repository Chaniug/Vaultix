# 设置页改造 —— 三项自包含工作单（2026-09-17 晚）

> **本文档的用途**：让**一个全新会话（零上下文）**照着一项一项做完。
> 设计依据 = `.ai/decisions/设置页信息架构-定稿.md` 的 **§11.10 / §11.11 / §11.12**
> （**先读那三节**，本文只给"怎么落"）。
> ⚠️ 顺序 = §1 → §2 → §3 → §4。**§1 收益最直接，先做。**

---

## §0 施工纪律（**先读，别跳**；每条都付过代价）

| # | 纪律 |
|---|---|
| 1 | **门禁必须含 `test`**，不能只跑 `compile`（"任务 UP-TO-DATE" ≠ "那段代码是好的"）。 |
| 2 | **detekt 有两种口径**：CI 用 `./gradlew detekt`（带 `buildUponDefaultConfig`）。本地 CLI 不加 `--build-upon-default-config` 会得到 **0 findings 的假绿**。 |
| 3 | **改完 detekt 必须再真跑一次 compile**（detekt 先于编译，会掩盖编译错误）。 |
| 4 | `VaultRepositoryImpl` **顶格 40 个函数**（detekt 上限）⇒ 新能力要开**独立接口 + 独立实现**，别往里加。 |
| 5 | **一个文件只放一个顶层类**，名字须与文件名一致（detekt `MatchingDeclarationName`）。 |
| 6 | **commit message 不要用反引号**（bash 会当命令替换）；`git commit -F -` + heredoc。 |
| 7 | 本地出包：`~/.gradle/wrapper/dists/gradle-9.5.1-bin/*/gradle-9.5.1/bin/gradle`；任务是 `:app:assembleFullDebug`（flavor 化）。**签名已自动化**（`keystore.properties`），**不需要手动重签**。 |
| 8 | **装机后必须比 SHA-256 核证**（`install` 输出可能是空白的"假成功"）。⚠️ **锁屏（`isKeyguardShowing=true`）+ `Dozing` 时 `install` 会被拒**。 |
| 9 | **python 是 Windows 版，不认 `/d/...` 路径**（先 `cd` 进目录再用相对路径）；`adb pull` 目标要给 **Windows 路径**（`D:/…`）。 |
| 10 | 每个**早退分支**必须「要么改状态、要么留日志」；**禁止把失败渲染成空**（「空」有三态）。 |

---

## §1 「解锁方式」合并精简（定稿 §11.11）

### 目标（用户原话："逻辑很麻烦、操作很复杂"）
把 **逐库 × 两步 × N 次** 改成 **一次流程 + 默认全勾**。

### 现状（要改掉的四件事）
| # | 现状 | 性质 |
|---|---|---|
| 1 | **逐库一行**，要逐个点开 | 可优化 |
| 2 | **两步对话框**（先选库 → 再确认） | 可优化 |
| 3 | ★「一个 PIN 打开多个库」**必须一次配齐** | ⚠️ **硬约束，不能改** |
| 4 | 指纹与 PIN 是**两条独立路径**，用户得先理解二者关系 | 可优化 |

### 目标形态
```
设置 → 密码库管理 → 「解锁方式」
  ┌───────────────────────────────────┐
  │ 快速解锁（指纹）         [开关]    │
  │ ● 3 个库已启用                     │  ← 一行汇总，不再逐库列行
  │ 应用内 PIN                [开关]   │
  │ [管理解锁方式]                     │
  └───────────────────────────────────┘
                ↓ 一次流程
  ① 列出所有库（★默认全勾）→ ② 需要主密码的就地填 → ③ 一次认证/PIN → 全部完成
```

### 三个不能丢的要点
1. ★ **默认全勾** —— 这是**最大的省事点**。用户 99% 想要"所有库都能快速解锁"，"逐个勾选"是纯负担。
2. ★ **硬约束要如实告知**：PIN 场景**必须一次做完**（PIN 只在输入那一刻存在，包裹只能同流程完成）
   ⇒ UI **不能**让用户以为"可以事后再给某个库补 PIN" —— 那会得到一个**永远解不开的信封**。
   （与今天那类"看起来配好了、实际打不开"同族。）
3. ★ **指纹与 PIN 合成一个入口**：它们是同一问题的两种答案。

### 落点（现成文件）
- `app/src/main/java/io/vaultix/vaultix/ui/settings/QuickUnlockController.kt`
- `app/src/main/java/io/vaultix/vaultix/ui/settings/QuickUnlockDialogs.kt`
- `app/src/main/java/io/vaultix/vaultix/ui/settings/SettingsScreen.kt`
- `domain/.../VaultRepository.kt` 的 `localUnlockAvailable` / `enrollLocalUnlock*` / `enrollPinForVaults`
  ⚠️ **不要**把新逻辑塞进 `VaultRepositoryImpl`（见 §0-4）
- 已有的多库编排：`PinEnrollmentCoordinator`（"给哪些库、按什么顺序"）· `LocalUnlockEnrollment`（备料）
  ⇒ **优先复用**，不要另写一套"多库"逻辑（两份实现必然漂移）

### 施工记录（2026-09-17 深夜 —— **代码已完成；⚠️ 真机验收尚未做**）

- **承载形式取了「内联」**：三个设置行直接进「密码库管理」页的「解锁方式」组。
  旧形态是"组内一行入口 + 点进去的对话框" —— 组名与组内唯一的内容语义重复，还白多一次导航。
- **流程取了两法「一次配完」**：`Dialog.Configure` 一次问「对哪些库 + 用哪些方式」，再走
  `主密码 →（PIN 当场落盘）→（指纹备料 + 一次认证）→ 结果`。
  ★ 依据不是"合成一个入口"这句话，而是一个**可查的事实**：**KDBX 主密码两种方式都要用**
  ⇒ 分开跑就是每个库要输两遍（见定稿 §11.13.2）。只缩短"选库"那一步，重复次数一点都不会少。
- **「默认全勾」用新增的独立标记实现**，**没有**借"范围为空"表达 —— 空集在这里已经是指
  "一个都不要"，借它会造出"用户全不勾、界面显示全勾"（定稿 §11.13.1）。
- 顺手修掉两处**沉默 / 假状态**（定稿 §11.13.3）：旧 `Notice` 通道无人消费；指纹取消后
  PIN 段已落盘却不报。

**门禁（三条全绿，实测）**：detekt ✅ · `:app:compileFullDebugKotlin` ✅ ·
`:app:testFullDebugUnitTest` ✅（20 个测试类 / 182 用例 / 0 失败 / 0 跳过）。

⚠️ 中途 `:app:compileFullDebugKotlin` 出现过一次 **Gradle daemon 崩溃**
（`daemon disappeared unexpectedly` + git-bash 的 `child_copy` 报错），**不是代码错**。
⇒ 三关**分开单跑**即可稳定通过；别把"三关串成一条命令跑"的失败读成编译错误。

### 验收（代码已实现；⚠️ **待真机验收**）

- [x] 从设置里能**一次**给所有库开启快速解锁（不必逐库点）⇒ 开关 → 向导（默认全勾）
- [x] 默认全勾；取消勾选某个库是**可选动作**而不是必须动作 ⇒ `isQuickUnlockScopeConfirmed()`
- [x] 需要主密码的库**就地**能填（不必退出去重新进）⇒ 同一次流程内串联问密码
- [x] PIN 流程里明确告知"必须一次做完"（不能事后再补）⇒ 向导内 `quick_unlock_configure_pin_warning`
- [x] 关掉其中一个（指纹/PIN）**不会顺手关掉另一个** ⇒ `disableAll(method)` 只管一种方式

---

## §2 密码库管理页改「行 + ⋮」（定稿 §11.10）

⚠️ **这是对 §11.1 的修订** —— §11.1 定的"卡片"**不做了**。理由见 §11.10：
真病根是「退出数据库」**全局动作、范围不可见**，修它只需"每个库有自己的动作"；
卡片只多"多动作常驻"这一件事，代价是列表迅速变长 + 与设置页其余紧凑行不一致。

### 目标
每个库**一行**：库名 + 状态副标题 + 右侧 **⋮**；⋮ 里放 **锁定 / 同步 / 退出 / 移除**。

| 动作 | 后果 | 表达 |
|---|---|---|
| 锁定 | 丢弃内存密钥，秒解 | 普通 |
| 同步 | 无损（仅网盘库有） | 普通；**本地 KDBX 不出现此项** |
| 退出 | 清**该库**缓存 + token，需重登；**远程不动** | 二次确认，写明"需重新登录" |
| 移除库 | 只删本地那一行，**远程不动** | 红色 + 确认 |

★ **按钮按能力显示**：本地 KDBX（`origin` 以 `content://` 开头）**没有「同步」**；
★ **KDBX 不给「退出」**（与锁定同义 —— 条目不在 Room、无 token、无队列）。

### 落点
`ui/settings/VaultManagementScreen.kt`（现 213 行）· `SettingsViewModel`（或新开一个 VM，⚠️ §0-4）
判据用现成的 `origin` 前缀；**不要**新加 `sourceType` 列（方案 §2：一加就有两处真相）。

### 验收
- [ ] 全局「退出数据库」**已移除**（它是本次事故的入口）
- [ ] 「退出」只影响**那一个库**（另一个库的条目仍在）
- [ ] 「移除库」后远程数据仍在（用另一设备/网页确认）
- [ ] 本地 KDBX 行上**没有**「同步」

### 施工记录（2026-09-18 —— 代码已完成；⚠️ 真机验收未做）

- **新增 `VaultActionsController`**（`ui/settings/`），不往 `VaultRepositoryImpl` 里加函数
  （§0-4，它已顶格 40）。动作封装成 `Outcome` 密封接口，UI 只负责把它翻成人话。
- **按钮按能力显示**用 `VaultSummary` 上的两个扩展判定，判据就是现成的 `origin`：
  ```kotlin
  canSyncToRemote()   = !origin.startsWith("content://")   // 本地 KDBX 没得同步
  canSignOutOfDevice() = kind == VaultKind.BITWARDEN        // KDBX 无 token/无队列，与锁定同义
  ```
  ⚠️ **没有**新增 `sourceType` 列（方案 §2：一加就有两处真相）。
- **全局「退出数据库」整条删除**：`SettingsScreen` 的行 + `ExitDatabaseDialog` +
  `SettingsViewModel.exitDatabase()` + `sessionRepository` 依赖 + 三条 `setting_exit_database*` 文案。
- 冲突处理**复用**已有的 `KdbxConflictDialog`（用本地 / 用远程 / 稍后），没另写一套。
- ⚠️ 文案踩过一次坑：新加的 `vault_remove_confirm` 与既有第 98 行**同名**，
  Android 资源报 `Found item String/vault_remove_confirm more than one time`
  ⇒ 删掉新写的、直接复用既有 `vault_remove_*` / `vault_removed` 四条。

---

## §3 SSH 条目「生成密钥对」（定稿 §11.12）

### ★ 先做这个决定（不做会白写）
**私钥导出成哪种格式？**

| 选项 | 成本 | 兼容性 |
|---|---|---|
| **PKCS#8 PEM**（`-----BEGIN PRIVATE KEY-----`） | **近乎零**（Android `KeyPairGenerator` 直接给） | ⚠️ 很多 SSH 客户端/老工具**不认** |
| **OpenSSH 新格式**（`-----BEGIN OPENSSH PRIVATE KEY-----`） | ★ **要自实现 bcrypt-kdf + chacha20-poly1305 封装**，是独立一块 | ✅ 通用 |

~~**建议**：先做 **PKCS#8 + `ssh-ed25519` 公钥**（能很快落地、立刻可用），
OpenSSH 新格式导出**列为第二期**（等真有人抱怨客户端不认再补）。~~

> ### 🔴 **已被推翻（2026-09-18 实测）—— 这里的建议不要照做**
>
> 实测环境 OpenSSH **9.6p1**（Ubuntu），`ssh-keygen -y -f <私钥>`：
>
> | 假设 | 实测 |
> |---|---|
> | PKCS#8 PEM 的 **RSA** 私钥 | ✅ 能读，`-y` 导出的公钥与本实现逐位一致 |
> | PKCS#8 PEM 的 **Ed25519** 私钥 | 🔴 **`Load key "id_ed25519.pem": invalid format`** |
>
> ⇒ **Ed25519 走 PKCS#8 = 生成出来绝大多数 SSH 客户端直接用不了**。
> 而本条自己点名的失败模式正是「做出一个**生成了但用不上**的按钮」—— 照原建议做，正好撞上。
>
> **最终做法：直接产出 OpenSSH 新格式私钥**（`-----BEGIN OPENSSH PRIVATE KEY-----`，
> 内部 `cipher=none` / `kdf=none`）。无口令变体**不需要** bcrypt-kdf —— 那才是定稿说的
> "独立一块"，而对"存在密码管理器里的私钥"来说，它已被库的主密码保护，不加密是合理的。
> ⇒ 成本比定稿估计的低一个量级，兼容性却是满的（`ssh` / `ssh-keygen` / `ssh-add` /
> PuTTYgen / KeePassXC-KeeAgent 都认）。

### 其余
- **算法**：Ed25519 优先（现代、短、KeePassXC 默认），RSA 作为可选项。
- ★ **字段名必须与 KeePassXC 逐字对齐**（`publicKey` / `privateKey`），否则在 KeePassXC 里看不到。
- ★ **私钥字段必须是受保护（Hidden）类型**。
- ★ **安全提示不可省**：私钥**不可再生** ⇒ 生成后要明确提示"立即备份/立刻用一次"
  （与"passkey 私钥是唯一不可再生的东西"同一类）。
- 相关现状：`SshKeyFields`（#97）+ `SshFingerprint`（`SHA256:<b64>`，与 `ssh-keygen -lf` 逐位一致）
  ⇒ 生成后**用它算出指纹并显示**，用户能立刻核对。

### 施工记录（2026-09-18 —— 代码已完成；⚠️ 真机验收未做）

- **算法落在 `core:common` 的 `SshKeyGenerator`**（纯 JVM、不依赖 `android.*`，可在单测里跑）。
  ⚠️ **必须自己实现 Ed25519**：Android JCA 从 **API 33** 才给 Ed25519，而 `minSdk = 26`
  ⇒ 直接用会在绝大多数设备上抛 `NoSuchAlgorithmException`。走 **Bouncy Castle**
  （`core:crypto` 早就依赖同一个库，没有引入新的第三方）。
- **私钥段按 OpenSSH 约定写**：Ed25519 的"私钥"是 **64 字节 = 种子 || 公钥**，
  少了后半段 32 字节，`ssh-keygen -y` 会报公私钥不匹配。RSA 私有段顺序
  `n, e, d, iqmp(q⁻¹ mod p), p, q`，`mpint` 最高位为 1 时**必须**前置 `0x00`，
  否则那部分密钥会算出**错误且隐蔽**（另一端能解析、只是对不上）的指纹。
- **UI 三步**（`ui/common/SshKeyGenerateDialog.kt`）：选算法 → 生成中 → 结果 + 备份闸门。
  - 生成走 `Dispatchers.Default`：**Ed25519 是毫秒级，RSA-3072 是百毫秒级**，主线程会掉帧；
  - 结果页**现算指纹**给用户当场与 `ssh-keygen -lf` 核对；
  - 确认按钮写作「**我已备份，填入条目**」⇒ 备份提示是**必须点掉的闸门**，不是一句说明；
  - 关掉对话框后表单里**仍留一行**提示（真正需要"立刻备份"的时刻是离开编辑页之后）；
  - 已有密钥时先弹**覆盖确认**（私钥不可再生，覆盖 = 销毁）。
- **单测 6 条**（`SshKeyGeneratorTest`）：只断言**容器结构**（magic / cipher / kdf /
  密钥数 / 内嵌公钥与公钥行逐字节一致 / 两个校验字相同 / 两次生成不同）。
  ⚠️ "这把钥匙真的能用"由**真机 `ssh-keygen`** 交叉验证（JVM 单测里跑不了），
  已实测 Ed25519 与 RSA 各一组：`-y` 与 `-lf` 全部逐位一致。
- ⚠️ **未涉及**：KDBX 侧目前**没有** SSH 条目的字段映射（`data/kdbx` 里搜不到 `sshKey`），
  所以「字段名与 KeePassXC 逐字对齐」这条**尚未落地** —— 等 KDBX 写回阶段 B 覆盖 SSH 时再做。

---

## §4 最后一处小活（定稿 §11.9 第三条只部分达成）

「添加密码库 → 从网盘添加」在**没有网盘账号**时，目前仍会落到**原表单**
（服务器地址 / 账号 / 密码 / 「连接并浏览」/「使用 Microsoft 账号登录」）。
~~⇒ **整段摘掉**，改成就地给「去设置里配置」的引导（可带一个跳转到「网盘账号」的按钮）。~~

> ### 🔴 **已被推翻（2026-09-18）—— 照原样摘掉会造出一条死路**
>
> 两个事实同时成立：
>
> 1. `CloudAccountInventory` 是**按设计从库的 origin 反推**账号的（该文件头写明了取舍：
>    不另存一份账号注册表）⇒ **"配了但还没建库"的账号列不出来**；
> 2. 「网盘账号」页（`CloudAccountsScreen`）是**只读清单** —— 只有 `refresh()`，
>    **没有"添加账号"能力**；而 `WebDavCredentialStore` 的写入方
>    （`webDavCredentials.save(...)`）全仓库**只有 `AddCloudVaultViewModel.connectWebDav()` 一处**。
>
> ⇒ 摘掉表单后：**首次使用网盘的人再也加不了网盘库**，而引导他去的那个页面同样加不了。
>
> **实际做法**：保留表单，但让它**退居「连接其他账号」之后** —— 无账号时直接展开，
> 有账号时默认只给「选账号 + 去网盘账号管理」。§4 想要的"不再以原表单为主路径"达成了。
>
> 顺带修掉原实现的一个**真 bug**：账号列表非空时是 **早退（`return`）**，
> 表单被整块跳过 ⇒ **已经配过一个账号的人，永远连不上第二个账号**。

### 施工记录（2026-09-18 —— 代码已完成；⚠️ 真机验收未做）

- `ConfigSection` 不再早退；表单收进新的 `NewAccountForm`，由 `showNewAccountForm` 控制。
- 新增「去网盘账号管理」按钮 ⇒ `AddCloudVaultScreen` 多一个 `onOpenCloudAccounts` 参数，
  由 `VaultixApp` 的 `settingsGraph` 接到 `CloudAccountsRoute`。
- `AddCloudVaultViewModel` 的 `connectWebDav` / `connectOneDrive` **保留**（它们是全仓库
  唯一的建账号入口，删了就真没了），只是不再是无账号时的默认路径。
- 新增文案 2 条：`add_cloud_connect_other` / `add_cloud_manage_accounts`。

---

## §5 顺序与依赖

```
§1 解锁方式精简        ✅ 2026-09-17 落地（待真机验收）
§2 库页改「行 + ⋮」     ✅ 2026-09-18 落地（待真机验收）
§3 SSH 生成密钥对       ✅ 2026-09-18 落地（待真机验收）
                        ⚠️ 导出格式已由实测改判为 OpenSSH 新格式（见 §3）
§4 添加页表单          ✅ 2026-09-18 落地（★ 改判：不摘掉，见 §4）
  ↓
真机验收（每项各自的验收清单 + 装机后比 SHA-256）   ← **当前在这里**
```

---

## §6 本轮整体收尾（2026-09-18）

**门禁（三条，实测全绿）**

| 门禁 | 结果 |
|---|---|
| `detekt` | ✅ **0 findings**（全模块） |
| `:app:compileFullDebugKotlin` | ✅ |
| `gw test`（全模块，含单测） | ✅ **866 个用例 / 0 失败 / 0 错误** |
| `.ai/tools/check_*.py` 四道脚本 | ⚠️ 3 道 OK；`check_compile_smells.py` 报 3 处，**经核为误报**（详见 §6.1） |

### §6.1 一处已知误报（别浪费时间再查一次）

`check_compile_smells.py` 报：`data/kdbx/.../KdbxCredentialCandidate.kt` 第 160 行的
文件级 `private fun sha256Hex` 被 `SafKdbxFileSource.kt:80/111/134` 调用。
**实际**：`SafKdbxFileSource.kt:195` 在自己的 companion object 里**另有一份同名** `sha256Hex`。
脚本按名字跨文件匹配 ⇒ 误报。两处文件本轮**都没改过**，且全模块编译通过。

### §6.2 下一步（顺势接的两件事）

1. **真机验收** —— §1~§4 各自的验收清单，装机后比 SHA-256。
2. **原生化评估** —— 见 [`native-rewrite-eval.md`](native-rewrite-eval.md)
   （Rust / Go 重写的方案与建议；结论是"**现在别动**，先量真机"）。
