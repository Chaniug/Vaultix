# 06-界面与交互 · 第 3 篇（#97 ~ #103）

> 主篇索引：[`06-界面与交互.md`](./06-界面与交互.md)（含计数规则与重号警告）
> 本篇 2 章 · 7.5KB · 覆盖 #97 ~ #103

## 97. SSH 密钥类型：**只能看、不能填** —— 新建/编辑时专属字段无输入框（2026-09-14）

**用户原话**：「SSH 编辑添加不了」。

**实证（代码级）**：模型层 `VaultItemType.SshKey` 与 `VaultSshKey`
（`privateKey` / `publicKey` / `fingerprint`）都存在，**详情页 `SshKeySection`
也能完整展示 + 复制**；但**新建/编辑表单里没有任何 SSH 输入框**：

```kotlin
// ItemFormDialog.kt:210
VaultItemType.SecureNote, VaultItemType.SshKey -> Unit   // ← 什么都不渲染
```

只有上面第 183–193 行的一句提示
（`item_edit_type_fields_readonly` = 「「%1$s」的专属字段暂为只读，
本次仅保存名称与备注的修改」）。

⇒ 后果：**用户能建出一个"空壳" SSH 条目**（只有名称 + 备注 + 自定义字段），
**私钥/公钥/指纹根本填不进去**。从 Bitwarden 同步下来的 SSH 条目才完整。
换句话说：**「看别人的」可以，「自己建」不行。**

**解法（待实施）**：在 `ItemFormDialog` 的 `when (type)` 里为 `SshKey` 补一个
`SshKeyFields(...)`（私钥多行输入 + 公钥 + 指纹，私钥默认掩码 + 可切换可见），
并在 `FormValues` 侧补 `sshKeyValuesOf()` 的读写对称（参考
`cardValuesOf` / `identityValuesOf`，⚠️ 注意 ISSUES #22：读写字段必须对称，
否则静默破坏数据）；保存路径上确认 `VaultSshKey` 能序列化落到 `encryptedPayload`。

**判据**：**提示文案说"暂为只读"不等于这个功能可以放着**——
用户看到的是"这个类型能选，但选了什么都填不了"，这比"类型干脆不出现"更困惑。
要么补齐输入，要么在选择器里对该类型给出明确的能力说明。

### 97.1 ✅ 已实现（2026-09-14，第五十六轮）

**走的是上面第一条路：补齐输入。**

**新增**
- `core/common/.../SshFingerprint.kt` —— OpenSSH 公钥指纹（`SHA256:<base64>`），
  与 `ssh-keygen -lf` **逐位一致**。纯派生工具，与加解密无关。
- `FormValues.kt`：`sshValuesOf` / `buildSshKey` / `applyPublicKeyChange`
  + 三个下标常量（`SSH_PRIVATE_KEY_INDEX` / `SSH_PUBLIC_KEY_INDEX` /
  `SSH_FINGERPRINT_INDEX`，**三处共用**）。
- `ItemFormDialog.kt`：`SshKeyFields`（私钥 / 公钥多行 + 指纹单行，三项等宽）。

**⚠️ 接口取舍**：`ssh` 是 `buildSnapshot` 的**第 5 个参数**，没塞进 `FormValues`
—— 后者已占满 8 个参数、正卡在 detekt `allowedConstructorParameters: 8` 硬上限。
与当年 `ItemMeta` 被拆出去同一个理由，沿用同一做法（而非放宽门禁）。

**★ 指纹派生：比较值，不记 `fingerprintEdited` 标志位**
「当前指纹为空 **或** == 旧公钥推导值」⇒ 自动、可覆盖；否则保留用户的值。
**比较法可自愈** —— 用户把手改值改回推导值（或清空）即自动重回跟随，
不会永久卡在「手动」。推导失败写**空串**（宁可空着，也不挂一个与当前公钥
不符的指纹）；初始化时若指纹为空而公钥可解析则**补上**，已有指纹**绝不覆盖**。
⚠️ 边界：遗留 **MD5 格式**指纹永不等于 SHA256 推导值 ⇒ 判为自定义而保留。

**两处有意偏离原解法**
1. 原文写「私钥默认掩码 + 可切换可见」⇒ **改明文**：详情页 `SshKeySection` 本
   就是明文 + 复制，表单遮蔽会让用户**无法确认粘贴正确**，而那正是要解决的问题。
2. 字段名按模型是 `keyFingerprint`（对齐 Bitwarden `JsonSshKey`），非 `fingerprint`。

**写路径无需改**：`CipherMapper.overlaySshKey` 已按 `item.sshKey` 重加密覆盖
⇒ 本 issue 是**纯输入侧缺口**。

**测试**：`SshFingerprintTest` 11 项（期望值取自 `ssh-keygen -lf`，
**不是**自算回填）+ `FormValuesTest` 19 项（含类型守恒：改别的类型不冲掉 SSH 段）。

---

---

## 103. ⚠️ **回 ViewModel 读 `_state.value` 拿界面数据 = 漏订阅**：页面打开是空态，点一下别的按钮才出现（2026-09-15，`8eae74b` 已修）

**现象**（用户真机报告，连续两轮都报同一条）：
> 点进通行密钥页面，看不到通行密钥的条目，但是**点击搜索后却又看得到**。

**第一次的误判**：我先前把这条归到 #84「空态分支塌进一个 `isEmpty()`」，
改了分支顺序 —— **没有修好**，用户复测仍然是空。因为**根因根本不在分支里**。

**真根因**：`PasskeysScreen` 这样拿要展示的行：

```kotlin
val rows = viewModel.passkeyRows()      // ❌ 函数内部读 _state.value
```

`passkeyRows()` 是 ViewModel 里的普通方法，内部读 **裸 `StateFlow.value`**。
**函数式调用不会订阅 Flow、不参与 Compose 快照**。于是：

1. 首帧渲染时 `state.items` 可能还没到（`observeItems` 的首帧是异步的）⇒ `rows` 算出**空列表**；
2. 渲染空态「还没有通行密钥」；
3. 之后**没有任何机制保证再重组一次** —— 数据到了也不会自动刷新；
4. 点搜索 ⇒ `searchActive` 变化 ⇒ **强制重组** ⇒ 这一次才读到真数据 ⇒ 列表出现。

所以「必须点搜索」**不是玄学，是一次漏订阅**。
`collectAsStateWithLifecycle()` 拿到的 `state` 是**订阅着的**，
但界面**没用它**算派生数据，而是绕回去读了后端的 `_state.value`。

**修法**（照抄验证码页早已修过的同一坑）：

```kotlin
// ViewModel：纯逻辑抽成不碰 _state 的扩展
fun List<VaultItem>.toPasskeyRows(): List<PasskeyRow> = ...

// Screen：绑定到**已收集的 state 快照**
@Composable
private fun rememberPasskeyRows(state: PasskeysViewModel.UiState): List<PasskeyRow> =
    remember(state.items, state.query) {
        state.items.toPasskeyRows().filter { it.matches(state.query) }
    }
```

**这是同一个坑的第二次踩**：验证码页 `TotpCodesScreen.kt` 的 `rememberTotpEntries`
早已修过，KDoc 里写得明明白白 ——

> 必须吃 `state.items` / `state.query` 两个 Compose State（而非
> `viewModel.filteredEntries()` 直读 `_state.value`）—— **后者不感知快照**，
> Tab 切换 / `SaveableStateProvider` 恢复时偶发不刷新
> （用户反馈「密码页新建的含验证码条目，在验证码页搜不到」）。

**⇒ 可复用判据（写代码时自问）**：

> **界面要用的任何派生数据，都必须从「已收集的 state 快照」算出来，
> 不要回 ViewModel 读 `_state.value`。**

ViewModel 里凡是给界面用的 `fun xxx(): List<...>`，只要内部读了 `_state.value`，
就是一个**定时炸弹**：它依赖"碰巧还有别的状态变化触发重组"才正常。
**正确形态**永远是把纯逻辑做成不碰 `_state` 的扩展/顶层函数，
由界面用 `remember(state.a, state.b) { ... }` 绑定输入重算。

**顺带修掉的同源问题**：

| 问题 | 原状 | 现状 |
|---|---|---|
| 空态分支混淆 | `state.items.isEmpty() \|\| rows.isEmpty()` 一个分支 | 拆成 `noPasskeysAtAll` 与 `rows.isEmpty()` 两级（对齐验证码页），搜索无结果不再错报「还没有通行密钥」 |
| loading 文案自相矛盾 | `passkeys_empty_title`（"还没有"）+ 计数硬编码 `0` | `title = null` + 新增 `passkeys_loading_body`「正在读取通行密钥…」 |

**排查手法（值得复用）**：用户描述里出现「**要点一下某个无关按钮才出现**」时，
八成就是这一类**漏订阅 / 少一次重组**，而不是数据或分支错。
先看界面拿数据的那个函数是不是绕开了 `collectAsState*` 的快照。

---

