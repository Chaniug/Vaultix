# 施工单：Bitwarden JSON ⇄ KDBX 无损互转（含 TOTP / 通行密钥）

> 状态：**待施工**（2026-10-05 立单）
> 目标：**无损**双向转换。转换后用户手上的数据不缺、不乱、可再转回去。
> 前置：`KdbxPasskeyCodec` 读侧已实现（阶段 A），但**写侧全缺**；字段规范化无任何防护。

---

## 0. 一句话立项理由

用户要在 Vaultix 里加「Bitwarden JSON 转 KDBX」。动手前取证发现**三处缺口**，
其中一处是**现网 bug**（自定义字段可覆盖标准字段，毁掉库名）。

用户另有同域项目 `Chaniug/bw2keepass`（Python + JS 双实现），
字段知识可直接借鉴 —— 本单的字段码本以**两边的并集**为准。

---

## 1. ★ 取证结论（不是推测，全部实测/读码得出）

### 1.1 kotpass `EntryFields` 标准键语义 —— 实测探针结果

在 `data:kdbx` 模块写了临时探针跑 `:data:kdbx:testDebugUnitTest`，实测（kotpass 0.13.0）：

| 探针 | 行为 | 结论 |
|---|---|---|
| `+ ("Title" to X)` | `.title` **变成 X**，size 不变 | ⚠️ **真覆盖** |
| `+ ("title" to X)` | `.title` **不变**（仍真库名），多出一条 `title` | ⚠️ **大小写敏感** ⇒ 同名不同大小写会**并存两条** |
| `+ ("Url" to X)` | `.url` = **空**，但 `["Url"]` = X | ⚠️ **写 `Url` 读不出 `url`** |
| `+ ("Notes" to X)` | `.notes` = X | 真覆盖 |
| `- "Title"` | 键消失，`.title` = null | ✅ `minus` 干净 |
| 同一自定义键写两次 | 保留第二个，size +1 | ⚠️ **不去重**（size 4→6） |

**三条硬结论**：
1. **Vaultix 现有 `KdbxItemWriter.applyCustomFields`（:269）无条件 `result + (name to …)`**
   ⇒ 用户一个叫 `Title` / `UserName` / `Password` / `Notes` 的自定义字段
   **会直接覆盖标准字段**。这不是"显示乱"，是**数据毁**。已存在的真 bug。
2. **键名大小写敏感** ⇒ 规范化必须**自己**做大小写折叠，不能指望 `EntryFields`。
3. **`STANDARD_FIELD_KEYS`（`KdbxItemMapper:213`）写的是 `"URL"`，而写侧写的是 `"Url"`**
   ⇒ 读侧标准字段白名单**漏了 URL**。同一处根因的第二个 bug。

### 1.2 缺口清单（读全通，写全缺）

| 环节 | 现状 | 位置 |
|---|---|---|
| BW JSON → 领域模型 | ✅ **含 TOTP + 通行密钥**，有测试 | `BitwardenImportMapper:59` |
| 领域模型 → BW JSON | ✅ 含 TOTP + 通行密钥 | `BitwardenExportMapper:115` |
| 领域模型 ← KDBX | ✅ 含 TOTP + 通行密钥 | `KdbxItemMapper:110-124` |
| **领域模型 → KDBX（TOTP）** | ⚠️ 有 `applyTotp`，但**从未做过往返验证** | `KdbxItemWriter:226` |
| **领域模型 → KDBX（通行密钥）** | ❌ **完全缺失** | `KdbxItemWriter` 无 passkey |
| **字段规范化** | ❌ 无任何防护 | 见 1.1 |

### 1.3 与 bw2keepass 的字段差异（互补，非重合）

`bw2keepass/web/engine.js:288-300` 已在写 `KPEX_PASSKEY_*`，但两边字段集**互补**：

| 字段 | bw2keepass | Vaultix | 处置 |
|---|---|---|---|
| `CREDENTIAL_ID` / `PRIVATE_KEY_PEM` / `RELYING_PARTY` / `USER_HANDLE` / `USERNAME` | ✅ | ✅ | 共有 |
| `RP_NAME` | ✅ | ❌ | **补**（Bitwarden 有值才写） |
| `USER_DISPLAY_NAME` | ✅ | ❌ | **补** |
| `CREATION_DATE` | ✅ | ❌ | **补** |
| `FLAG_BE` / `FLAG_BS` | ❌ | ✅ | 共有（写侧补，KDoc 已预告） |

⚠️ **多凭证**：bw2keepass 用 `_1`/`_2` 后缀（`engine.js:290`）区分多个通行密钥，
但 Vaultix `KdbxPasskeyCodec` 只有**单个** `KdbxPasskeyFields`。
bw2keepass 的 `extractPasskeysFromFields`（`engine.js:144-170`）已处理，
Vaultix 读侧**需要补**同样的拆解，否则「一条登录挂 2 个通行密钥」只读得出一个。

### 1.4 bw2keepass 踩过的坑（血泪，直接抄它的结论）

- **保留键**（`converter.py:310-316`）：pykeepass 的 `UserName` / `otp` 是保留键，
  `set_custom_property` **抛 AssertionError 直接崩整个转换**。
  ⇒ kotpass 不抛，但**静默覆盖**。规范化必须在**我们这层**挡住。
- **多 URL**（`engine.js:261-266`）：主 URL 进标准 `URL`，其余进 `KP2A_URL_1/_2`。
  ⇒ 照搬，KDBX 单 URL 字段的容量限制由此变**无损**。
- **Android URI**（`engine.js:222`）：`androidapp://` / `android://` 必须走
  KeePassDX 的 `AndroidApp` + `AndroidApp Signature`，塞 `URL` 里 KC/DX 会坏。
- **标准字段优先**（`engine.js:285`）：`if (!fields[cf.name])` —— 已有标准键就不被覆盖。
  ⇒ 这条规则**必须搬到 Vaultix**（目前没有，是 bug 1 的修法）。

---

## 2. 规范化方案（核心）

### 2.1 命名空间：三级键区，永不交叉

```
① 标准区（5 个，KDBX 语义固定）      Title / UserName / Password / URL / Notes
   ⚠️ 修正：现有白名单的 "URL" 必须改成 "Url"（探针 PROBE D）
② 工具区（前缀 VPX_，本应用专用）     VPX_PW_HISTORY / VPX_BW_TYPE / VPX_BW_ID
                                      VPX_URL_1 / VPX_URL_2 …
   ⚠️ 为什么用 VPX_ 而不是照抄 bw2keepass 的 KP2A_：
      KPEX_ 已被通行密钥占用；且 KP2A_ 是对方项目的标识，本库写出后
      别人看不出是谁写的。用自己的前缀才能识别「哪些是转换工具生成的」。
③ 通行密钥区（前缀 KPEX_，KeePassDX 生态）  KPEX_PASSKEY_*（共 8 字段 + BE/BS 标记）
④ 外来区（无前缀）                     第三方工具写的自定义字段，**原样保留**
```

**铁律 R1（标准键不可被覆盖）**：写入自定义字段前，若键名（**大小写折叠后**）
命中标准区/TOTP 区/通行密钥区 ⇒ **跳过 + 计数上报**，绝不写入。

**铁律 R2（工具字段加前缀）**：转换器产出的任何非标准字段一律加 `VPX_`。
好处是**可逆** —— 反向转换时能认出「这个是工具生成的，原名是什么」，
不靠猜。单纯跳过就永久丢数据。

**铁律 R3（外来字段原样保留）**：读进来的、无前缀且不属于已知区的一律原样带回。
绝因为"看不懂"就丢（现有 `applyCustomFields` 的删除判据已遵守此条，见 :249 注释）。

### 2.2 一对多降级（无损，不是截断）

| 来源 | KDBX 落法 | 反向还原 |
|---|---|---|
| Bitwarden `login.uris`（多条） | 第 1 条 → 标准 `URL`；其余 → `VPX_URL_n` | 按 `VPX_URL_n` 拼回数组 |
| `login.fido2Credentials`（多条） | `KPEX_PASSKEY_*` + `_n` 后缀 | 按后缀拆回列表 |
| `passwordHistory` | `VPX_PW_HISTORY`（JSON，与 bw2keepass 对齐） | 解析回数组 |
| `androidapp://` URI | `AndroidApp` + `AndroidApp Signature` | 还原 URI |
| Bitwarden `type` | `VPX_BW_TYPE` | 还原类型（KDBX 无类型概念） |
| Bitwarden `id` | `VPX_BW_ID` | 还原 id，保持可重复转换的幂等 |

### 2.3 字段名规范化（`normalizeFieldName`）

对**所有**待写键名统一处理，顺序固定：
1. 折叠大小写后判区（R1）
2. 剔除 KDBX 不接受的控制字符（`\x00`–`\x1F`）
3. 空名 → 丢弃 + 计数
4. 超长截断（KDBX 键名上限按 255 保守处理）
5. 同名冲突（两条不同来源落到同一键）→ 后者加 `_2`、`_3`

### 2.4 转换产物（用户已拍板）

- **生成 `.kdbx` 文件**（SAF 选路径），与「导出」对称，零上下文切换
- **先平铺**（不映射 Bitwarden 文件夹层级），后续再叠分组
- **通行密钥写回必须先做**（否则转换出的文件里通行密钥**静默丢失**）

---

## 3. 施工批次（每批独立可验收）

### W1 · 修 bug：标准键覆盖 + URL 白名单
> **先做这个** —— 它是现网数据毁风险，与新功能无关。

- `KdbxItemWriter.applyCustomFields`：写前做 R1 判区，命中即跳过 + 计数
- `KdbxItemMapper.STANDARD_FIELD_KEYS`：`"URL"` → `"Url"`
- 新增 `KdbxFieldKeys` 单一真源（标准 5 + TOTP 集 + 通行密钥集 + `VPX_` 前缀），
  两侧共用，**杜绝"抄一份必然漂移"**
- 单测：自定义字段名 = `Title`/`title`/`UserName`/`Notes`/`URL`/`Url` 时标准字段**不变**；
  `minus` 仍能删干净

### W2 · 通行密钥写回
- `KdbxPasskeyCodec` 加对称的 `fromCredential(credential, index) → Map<String,String>`
  - 字段集 = **两边并集**（1.3 表格，10 个键）
  - 多凭证带 `_n` 后缀（对齐 bw2keepass）
  - `FLAG_BE`/`FLAG_BS` 写回（KDoc `:139` 已预告）
- `KdbxItemMapper.toPasskeyFields` → 改成**列表**，支持多凭证拆解（1.3 末）
- 私钥 PEM 写为 `EntryValue.Encrypted`（**受保护**）—— bw2keepass 用 kdbxweb，
  是否设了保护**尚未核实**；这条按更严的一侧执行，理由是安全语义不可回退
- 单测：写 → 读往返一致（`credentialId` 的 base64url↔base64 也要往返）

### W3 · TOTP 往返验证
- 现有 `applyTotp` 从未验证过往返 ⇒ 补**参数化**测试：
  `otpauth://` 全形态（SHA1/256/512、6/8 位、秒级/分钟级、counter）
  → 写 KDBX → 读回 → 断言与原 URI 等价
- 发现不一致就修 `KdbxTotpCodec`，**不改** otpauth 语义

### W4 · VPX_ 工具字段 + 多值降级 ✅ `92d62a0`
- 多 URL → `VPX_URL_n`；`androidapp://` → `App Package Name` 字段
  （⚠️ **不是**本文档 §2.2 写的 `AndroidApp`；Bastion 实测读的是 `App Package Name`
  / `App Name` 并兼容 5 个别名，见 `reference/bastion/.../KeePassKdbxService.kt:3780`）
- `VPX_BW_TYPE` / `VPX_BW_ID`（⚠️ `VPX_PW_HISTORY` **不做**：领域模型 `VaultItem` 无该字段，
  写了"就再也读不出来"，等于换个地方丢数据 —— 理由见 `KdbxToolFields` 文件头表格）
- 单测：3 条 URI 的登录条目往返后仍是 3 条 ✅（`KdbxToolFieldsRoundTripTest`，24 例）

**落法（写侧）**：第 0 条 → 标准 `Url`；第 2 条起 → `VPX_URL_n`（下标从 1 起）；
每条的匹配档位另存 `VPX_MATCH_n`；`androidapp://` → `App Package Name`。

**三个必须记住的坑（都已在单测里钉死）**：

| 坑 | 不这么做会怎样 |
|---|---|
| `applyUris` 必须**整段替换**而非按键名增删 | 3 条删到 2 条时旧 `VPX_URL_2` 变成新 `VPX_URL_1`，留下**错位的旧值**（比丢数据更坏：网址还在但对应关系错了） |
| `typeOf` 的数字码是 1–5（0 不分配） | 曾整段错位一位（2→Card/3→Identity/4→SshKey）⇒ 卡片往返后变成身份，无任何症状 |
| kotpass `EntryFields.get(String)` 是普通 `Map.get` ⇒ **键名大小写敏感**（已 `javap` 核实） | 读侧只认自己写的键名/大小写 ⇒ **读不出第三方库**，单向丢数据 |

**已知取舍（钉死，别当bug 顺手"修"）**：多个 `androidapp://` 只保留第一个
（`App Package Name` 是单个字段位，无 `_n` 落法；硬塞进 `VPX_URL_n` 会被 KeePassXC
当域名匹配、必然匹配不上，等于为多存一条而让**所有**应用 URI 匹配全失效）。

### W5 · 转换入口（UI）
- 导入导出页加「转换」：`选 BW JSON` → `文件密码` → **`VPX_ 输出库主密码`**
  → `预览条目数 + 各项计数（TOTP n / 通行密钥 n / 被跳过 n）` → `SAF 选路径` → 产出
- ⚠️ 密码**用完即弃**（沿用 `ImportExportViewModel` 现有纪律）
- 失败时**原 JSON 不受影响**（只读输入）

### W6 · 互操作验收（真机，不可省）
- 产出的 `.kdbx` 用 **KeePassXC / KeePassDX 真机**打开，确认：
  条目数一致、TOTP 能出码、通行密钥字段存在且**私钥受保护**、多 URL 都在
- 反向：VC 导出的 `.kdbx` → 转换回 BW JSON → 与原始 JSON 对比
- ⚠️ 历史上 `4c3aa72` / `95ae0b4` 两次返工都因为**没先在真机验**；
  本批的验收标准是**三方互操作**，不是"我们的参数看起来对"

---

## 4. 风险清单

| 风险 | 后果 | 处置 |
|---|---|---|
| R1 没实现到位 | 用户数据被静默毁 | W1 独立一批 + 单测钉死 |
| 私钥 PEM 写成明文 | KDBX 被别人打开即泄露私钥 | W2 强制 `Encrypted` |
| 通行密钥多凭证只读一个 | 丢凭证 | W2 读侧改列表 |
| kotpass 键名大小写敏感 | `Title`/`title` 并存两条 | R1 自己做大小写折叠 |
| 产出的库 VC 打不开 | 功能整体作废 | W6 真机验收前置 |
| 转换不幂等 | 转两次数据漂移 | `VPX_BW_ID` 保 id，往返测试断言幂等 |

---

## 5. 与 bw2keepass 的关系

- **借鉴**：字段码本（`KPEX_*` 全集 + `KP2A_*` 降级思路 + 保留键/多 URL/Android URI 三条血泪）
- **不引入**：其代码本身（Python/JS 双实现，Kotlin 侧重新实现，字段清单一致即可）
- **差异化**：本库用 `VPX_` 前缀（见 2.1），故与对方产出的文件**不共享**工具字段区，
  但 `KPEX_PASSKEY_*` 区**互通**（KeePassDX 生态标准）
- ⚠️ 对方字段集**没有** `FLAG_BE`/`FLAG_BS`（备份态）。
  本库写出会多这两个键 —— 对 KeePassDX 是**未知自定义字段**，会被原样保留不报错，
  但需在 W6 顺带确认 XC 不会因此报"字段无法识别"

---

## 6. 自包含验收清单（零上下文可执行）

- [ ] W1：`applyCustomFields` 遇标准键（含大小写变体）跳过；`STANDARD_FIELD_KEYS` 用 `"Url"`
- [x] W2：`KdbxPasskeyCodec.fromCredential` 存在且覆盖 10 个键；多凭证带后缀；私钥 `Encrypted`
  （2026-10-06 落地 `569c398`，CI `37348461753` 全绿；⚠️ `FLAG_BE`/`FLAG_BS` 不参与往返保真，见下）
- [x] W3：TOTP 参数化往返全绿
  （2026-10-06 落地 `4ea40f5` + `15ff84b`，CI `37444254405` 全绿。
  **往返本来就通，`KdbxTotpCodec` 一行未改**；新增 `KdbxTotpRoundTripTest` 9 条 / 11 形态钉死。
  ⚠️ 两次返工：① 曾试图"修"位置式 `TOTP Settings` 的 HOTP counter，但既无 KeePass 格式佐证、
  既有测试亦明确断言 `counter == 0L`（有意设计）⇒ **没有证据就不改语义**，已回退；
  ② CI 对勾 ≠ 单测通过（该步骤 non-blocking，`37443116170` 显示绿而单测编译其实 FAILED））
- [x] W4：多 URL 往返仍是 N 条；`VPX_*` 可反向识别（`d141538`，CI `37612405103` 141 例零失败）
- [ ] W5：转换入口可跑通，密码用完即弃
- [ ] W6：VC/DX 真机打开产出文件，条目/TOTP/通行密钥/多 URL 全对
- [ ] 门禁三关**分开单跑**全绿：detekt → `:app:compileFullDebugKotlin` → `:app:testFullDebugUnitTest`
- [ ] `.ai/conventions/8.x` 更新：标准键铁律 + `KdbxFieldKeys` 单一真源的位置
