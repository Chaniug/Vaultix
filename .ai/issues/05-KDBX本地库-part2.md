# 05-KDBX本地库 · 第 2 篇（#95 ~ #106）

> 主篇索引：[`05-KDBX本地库.md`](./05-KDBX本地库.md)（含计数规则与重号警告）
> 本篇 5 章 · 15.5KB · 覆盖 #95 ~ #106

## 95. 库列表卡片把 SAF 原始 `content://` URI 裸露给用户（2026-09-14）

**现象**：库列表页 KDBX 卡片第二行显示

```
content://com.android.externalstorage.documents/document/primary%3A%20我的文件%2Fvalkjin.kdbx
```

真机 `uiautomator` dump 实证（`bounds` 落在卡片副标题位置）。

**根因**：KDBX 的 `vault.id` **就是** `sourceUri`（`addKdbxVault` 第一行
`vaultId = sourceUri`），而卡片副标题直接渲染 `origin`/`id` 字段，没有做
「KDBX 显示文件名而非 URI」的分支（Bitwarden 的 `origin` 是 `https://...`，
恰好可读，所以问题只在 KDBX 上暴露）。

**解法（✅ 已实施，2026-09-14 第五十四轮）**：卡片副标题按库里取文案 ——
`VaultListScreen` 新增 `vaultSubtitle(vault)`：
- **KDBX** → `字符串资源 vault_card_kdbx_subtitle`（「本地文件 · %1$s」），取 `vault.name`
  （它本身就是添加时从 SAF 取的 `DISPLAY_NAME`），**绝不渲染 `origin`/`id`**；
- **Bitwarden** → 保留 `account ?: origin`（其 `origin` 是 `https://...`，本身可读）。
  另加 `maxLines = 1` 防止长值撑高卡片。

**判据**：**给用户看的字段必须是用户语义的。** URL 编码的 SAF URI
（`primary%3A%20...`）是给系统看的，裸露出来既不可读也不美观（见 #90 同源思路：
内部标识不该直接进 UI）。

---

## 96. 【结构】多库并存时**没有「切换密码库」的正面入口** ⇒ KDBX 库「找不到、打不开」（2026-09-14）

**用户原话（关键前提）**：「默认打开是 bitwarden 的界面，登录也是 bitwarden 优先，
**必须先进 bitwarden 的界面，打开设置，才能看到我添加的 kdbx 库的选项**」+
「就是不能指引我正确打开库的意思，然后我自己在密码库里找到了 kdbx 库，能够顺利打开了。」

**现象**：用户以为 KDBX「打不开」，实际是**找不到打开的入口**；最后靠自己摸到
「我的密码库」页才成功打开。库本身完全正常。

**根因（与 #69 同源）**：`VaultListRoute`（标题「我的密码库」）在整个导航图里
**仍然只有 4 个入口**，且没有一个是「切换/选择库」的语义：

| 位置 | 触发条件 | 语义 |
|---|---|---|
| `VaultixApp.kt:100` | `RootNavState.Onboarding` | 一个库都没有（已有库 => 永不触发） |
| `VaultixApp.kt:220` | `onNoVault` | 解锁时发现库被删（异常兜底） |
| `VaultixApp.kt:262` | `onLocked` | **主动锁定当前库** |
| `VaultixApp.kt:277` | `onNoVault` 兜底 | 异常兜底 |

⇒ 已有 ≥1 个库时，用户能碰到的唯一入口是 **`onLocked`（锁定）**，
而它的产品语义是「我不想看这个库了」——**没有任何一处是「我要换一个库看」**。
主界面 `MainShellScreen` 也没有库切换入口；
右侧溢出菜单实测只有「验证码 / 回收站 / 显示选项 / 同步 / 锁定」，
**没有「切换密码库」**。

**为什么在 KDBX 场景才暴露**：Bitwarden 是「登录即进主界面」的单库心智，
用户从不觉得需要切库；而 KDBX 是**手动添加的第二个库**，
「添加了却找不到」的落差立刻把人卡住。

**解法（✅ 已实施，2026-09-14 第五十四轮；取向 = A + B 合用）**：
- **A 类正面入口**：`ItemsMoreMenu`（⋮）**最上方**新增「切换密码库」（`SwapHoriz`），
  经 `ItemsScreen → MainShellScreen → VaultixApp` 透传，落到 `VaultListRoute`。
  ⚠️ **仅 >1 库时显示**：`MainShellViewModel.vaultCount` 传下来的回调为 null 即整项隐藏
  —— 单库用户显示「切换密码库」是噪音（同 #93「能力不存在时隐藏」的纪律）。
- **B 类修正**：`SettingsViewModel.switchableVaults` 由「只列已解锁」改为
  **列全部库**；`ActiveVaultDialog` 的 `VaultChoiceRow` 对未解锁项标
  「未解锁 · 需先输入主密码」。⇒ 「找得到我的库」不再依赖解锁状态。
- **解锁页出口**：`UnlockScreen` 新增 `onSwitchVault`（轻量 `TextButton`，
  仅多库 + 非 2FA + 非查看锁时显示）——「默认库不是我想开的那个」时有路可走。
- **C 类**（Snackbar 动作按钮「立即打开」）**未做**：依赖 #94，两件事分开更稳。

**判据**：**新增一类库之前，先画一遍「用户从冷启动到打开这个库」的完整路径。**
若路径上只有「异常兜底」和「语义相反的入口」（如用「锁定」当「切换」），
那就是 #69 的翻版——功能在，入口不在。

### 96.1 为什么「每次冷启动都要重走一遍」是**必然**（安全设计，非缺陷）

用户补充：「我退出 APP，再打开，又要重复一遍，先进 Bitwarden 的界面，
打开设置，才能打开 KDBX。」

**结论：重走一遍解锁流程是设计使然且必须保留**。证据在 `Kdbx.kt:18`：

```
安全约定（与 Bitwarden 侧一致）：解出来的明文只活在内存，锁库即丢弃，绝不落盘。
```

`KdbxSessionStore` 是 `ConcurrentHashMap`（纯内存）⇒ **进程退出即会话消失**，
KDBX 库在下次冷启动后必然是「未解锁」状态，必然要重新输主密码。

⚠️ **不要把「重走解锁」当成 bug 去修**：让 KDBX 会话跨进程存活 = 必须把明文或
等价的解密凭据落盘，这与「明文绝不落盘」的安全约定直接冲突。
（若真要免密，正确做法是 #93 提到的「Keystore 包裹主密码」，且需用户明确拍板。）

**真正要修的是「入口不可达」（#96 本体）**：解锁是正当成本，
**找不到解锁入口**才是缺陷。⇒ 修复目标是让「重走」变成
「打开 App → 一眼看到 KDBX 库卡片 → 点它 → 输密码」，
而不是「先进 Bitwarden → 设置 → 摸到库列表」。

---


---

## 98. KDBX 启用**生物识别**快速解锁 = **必闪退**（2026-09-14，第五十九轮）

**现象**：设置页 / 库列表横幅给 KDBX 库点「启用」→ 输主密码 → **进程直接崩**。
PIN 那条路（`enrollPinKdbx`）完全正常 —— 用户因此怀疑是竞态。

**它不是竞态，是「顺序错了」**，而且是**确定性**的（同一条路径 100% 崩）：

| 环节 | 事实 |
|---|---|
| 保护器 | `LocalUnlockKeyStore` 的 KEK 用 `setUserAuthenticationParameters(0, …)` ⇒ **auth-per-use**：只有被 `BiometricPrompt` 授权过的**那一个 Cipher 实例**能 `doFinal`（`LocalUnlockKeyStore.kt:204-208`） |
| 旧实现 | `enrollLocalUnlockKdbx` **在弹指纹之前**就 `localUnlockKeyStore.wrap(cipher, plaintext)` |
| ⇒ 结果 | `cipher.doFinal()` 抛 `UserNotAuthenticatedException` |
| ⇒ 放大成闪退 | 调用点 `viewModelScope.launch { … }` **没有 try/catch**，工程里也没有全局 `CoroutineExceptionHandler` ⇒ 未捕获异常 = 进程退出 |

**为什么 PIN 不崩**：PIN 的保护器是 `PinKeyWrapper` + `SecureCredentialStore` 的硬件外层密钥，
**不需要系统认证** ⇒ 不存在「cipher 还没被授权」这回事（`VaultRepositoryImpl` 的 `enrollPinKdbx`
注释里已点明这条差别）。

**为什么 Bitwarden 侧不崩**：它的 `wrap` 发生在 `BiometricPrompter.onSuccess` 回调里
（`enrollWithCipher`），天然在认证之后。

### 修法：把「准备」与「提交」拆开（两阶段）

| 方法 | 时机 | 做什么 |
|---|---|---|
| `prepareKdbxEnroll(vaultId, masterPassword, keyFileUri)` | 弹指纹**之前** | 用凭据**真解一次库**校验 → 组装 `KdbxUnlockPayload` 明文 → **暂存在仓储**（`stagedKdbxPayload`） |
| `commitKdbxEnroll(vaultId, cipher)` | 指纹**通过之后** | `wrap` + 落盘 + 置位开关；无论成败都擦掉暂存明文 |
| `discardKdbxEnroll()` | 指纹**被取消/终止** | 擦掉暂存明文（幂等） |

原 `enrollLocalUnlockKdbx` 已删除；`KdbxEnrollOutcome.Enrolled` 改名 **`Prepared`**
（名字要如实：它只代表「校验通过、已暂存」，**不代表已落盘** —— 一个撒谎的名字
会让调用方以为可以跳过 commit）。

### ★ 顺手修掉的第二个 bug（更隐蔽）

两处 `PromptForEnroll` 的 `onSuccess` **都**接到 `enrollLocalUnlock`（Bitwarden 收尾）上。
KDBX 走那条路时 `sessions.keyOf(vaultId)` **恒为 null** ⇒ 返回 false ⇒
**指纹按了、也过了，什么都没包上**，而且不报错。旧代码因为先崩，从没走到这一步。
现在两处都按库类型分流（`isKdbxVault()`，与 `AutofillActivity.completeLocalUnlockByKind`
同一条纪律）。

### 顺带修掉的第三个：`KdbxEnrollState.Ready` 会卡死下一次

`Ready` 经 `busy` 把密码框的确认键置灰。用户在指纹框点「取消」时旧代码不重置它
⇒ 下次再点「启用」，确认键**点不动**，且没人告诉他为什么。现在
`discardPendingKdbxEnroll()` 同时把状态归零。

### 门禁

detekt ✅ / `:app:compileFullDebugKotlin` ✅ / 单测 ✅（40 个类函数上限仍为 40：
删 `enrollLocalUnlockKdbx`、新增三个方法、把 `buildFullKey` 与 `classifyKdbxError`
两个纯函数提到**文件作用域**腾出余量）。

---

## 99. 设置里点**未解锁**的库 ⇒ 条目页/验证码页**全白**（2026-09-15，用户报）

**用户原话**：「我在设置里面选择密码库，比如当前是 bitwarden 情况下，选择 kdbx 查看，
不点默认，返回到密码条目页是空白的，验证码页面也都是空白的，好像加载不出来的样子。
只能通过密码条目界面 3 个点的按钮点开，点击切换密码库，然后进入到登录界面，
验证生物解锁才可以顺利的看到密码条目。」

**根因是三个"都对"的行为叠在一起** —— 单看每一环都合理，合起来就是死局：

| # | 环节 | 行为 | 单独看合理吗 |
|---|---|---|---|
| 1 | `ActiveVaultDialog` 的 `onSelect` | 对未解锁项也**无条件** `select(vaultId)` | 合理（#96 的 B 类修正就是为了"点得动"） |
| 2 | `ItemRepositoryImpl.observeItems` | KDBX 走 `Kdbx.contentOf(id)?.items.orEmpty()` ⇒ **真实的空列表** | 合理（无会话就是没内容） |
| 3 | `ItemsScreen` 空态 | 空列表 ⇒ 「还没有保存的密码」 | 合理（它不知道"为什么"是空的） |

**为什么是「全白」而不是「这个库没内容」**：三处叠加后，
「库锁着」这个**唯一真正的原因**在数据层被 `orEmpty()` 抹掉，在 UI 层被
「还没有保存的密码」**替换成一句假话**。没有任何一层留下"锁着"的信号。

**放大成"两个库都进不去"**：`ItemsViewModel.init` 的「切库即锁旧库」策略
立即锁掉原来在看的 Bitwarden ⇒ 用户不但进不了 KDBX，**连 Bitwarden 也回不去了**。
这正是他描述里"只能走 ⋮ → 切换密码库 → 解锁页"这条唯一生路。

**解法（✅ 已实施，2026-09-15；取向由用户拍板）**：
- **选项 A（用户选的）**：设置页点未解锁的库 ⇒ **直接去解锁页**
  （新增 `SettingsScreen.onOpenLockedVault` → `UnlockRoute(vaultId)`）。
  未解锁行**不再画 RadioButton**（单选语义是"现在看哪个"，它不参与），
  改右侧箭头 + 副标题「未解锁 · 点这里输入主密码」。
- **兜底 B**：条目页/验证码页新增「库未解锁」空态 —— **不再谎称"没有条目"**，
  并给「立即解锁」出口。正常路径走不到，但能防住同类静默状态
  （冷启动恢复 / 未来新入口）。
- **「设为默认」保持可用**：改"冷启动先开哪个"正当且**不要求当下解锁**
  （用户明知要输密码、仍希望下次直奔它），只是不再同步切活跃库。

**判据（写进纪律）**：**凡是"空列表"，都要能回答"为什么空"。**
数据层用 `orEmpty()` 把 `null` 抹平时，必须同时在**状态层**留下可区分的信号
（本处是 `VaultSummary.unlocked`），否则 UI 只能撒谎。
另参见 `.ai/ISSUES.md` #76（验证码页"冷启动 1~2 秒假空态"）—— **同一个病，
不同的触发条件**；`TotpCodesScreen` 的 `loading` 分支是本条纪律的最早一次实践。

---

## 106. ✅ 读路径分流了 KDBX、**写路径没分流** ⇒ 新建静默丢失 / 编辑报错（2026-09-17 发现；**已修** `8fc1125` + 写路径五处分流）

**怎么发现的**：不是用户报的 —— 是排查「OneDrive / WebDAV 网盘同步」的前置条件时，
顺着「KDBX 到底能不能写」这条线查出来的（见 `.workbuddy/memory/2026-09-16.md`）。

**现象**：
- 在 KDBX 库**新建**条目 ⇒ 保存看似成功，条目**永远不出现**（无任何报错）。
- 在 KDBX 库**编辑**已有条目 ⇒ 报错「条目不存在：…」。

**根因：一读一写走了两套存储。**

| 环节 | 位置 | 现状 |
|---|---|---|
| 读 | `ItemRepositoryImpl.observeItems`（:84） | `kind == KDBX ⇒ Kdbx.contentOf(vaultId)?.items.orEmpty()` —— **在内存里，不在 Room** |
| 写 | `ItemRepositoryImpl.createItem`（:159） | **无任何 kind 判断**，无条件 `atomicWriteDao.upsertCipherAndEnqueue(...)` ⇒ 写出一条 **Room 孤儿行** |
| 写 | `ItemRepositoryImpl.updateItem`（:195） | 先 `cipherDao.get(item.id) ?: error("条目不存在")`；而 KDBX 条目 id 来自 `KdbxItemMapper.itemIdOf(uuid)`（:108），**不在 Room** ⇒ 直接抛错 |

⇒ **一隐一显**：**新建 = 静默**（写进 Room，读侧永远看不到，用户以为存上了）；
**编辑 = 大声报错**（因为那条根本不在 Room 里）。二者同一个病根：
**阶段 A 是只读的，但写入口没有关门。**

**危害边界（已查）**：`flushAfterLocalWrite`（:414）对非 Bitwarden 库不入队推送
（`server = null`）⇒ **不会误推到服务端**。所以损害限于"本地多出一条幽灵行"，
**没有外泄**；但用户视角的"我存的东西没了"依然成立。

**可佐证 UI 也没拦**：`app` 层对 `VaultKind` 只用于**徽标 / 解锁路由**
（`UnlockScreen.VaultKindBadge`、`UnlockViewModel` 分流），
全局 grep `canEdit` / `isEditable` / `readOnly` **无任何 KDBX 相关命中**。

**为什么现在必须处理**：网盘同步（OneDrive / WebDAV）要求 KDBX **可写**，
而「KDBX 阶段 B（写回）」是三处文档共同的**最大未完成块**
（`.ai/MEMORY.md:33`、`Docs/progress/next-steps.md` 未做第 1 条、`.ai/SESSION-2026-09-12.md:29`）。
本条是它的**前置**：先把写入口的语义定死，再谈写回往哪写。
> ⚠️ **2026-10-02 更正**：本段是 2026-09-17 的历史判断，"阶段 B 是最大未完成块"
> **已不成立** —— 阶段 B 已落地（`Kdbx.save` / `saveVia` / 回收站映射 `6397de1`），
> 本条 #106 自身也已由 `8fc1125` 修复。**仍未做的是网盘端到端互操作**
> （桌面 KeePassXC + 手机 KeePassDX 双向），见 `Docs/progress/kdbx-write-phase-b.md`。

**解法（待定，随阶段 B 一并拍板；三选一）**：
1. 写入口对 KDBX **闸掉并明确提示**「本地 KDBX 库暂为只读」—— 最小改动，且诚实；
2. 把 KDBX 写请求转成**会话内的内存改动** —— 但阶段 A 无写回，关闭即丢，
   **只是把"静默丢失"换成"延迟静默丢失"，不算解决**；
3. 做**阶段 B（写回）**，让读/写共用同一存储 —— 正解，工作量大。

**判据（写进纪律）**：
> **读路径按 `kind` 分流了，写路径就必须同时分流。**
> 否则「读 A 存储、写 B 存储」必然产生幽灵数据 —— 且**两个方向都骗人**：
> 新建静默、编辑报错，用户无法从任何一条错误信息推出真实原因。
> **新增任何 `kind ==` 分支时，读/写两侧成对检查。**

