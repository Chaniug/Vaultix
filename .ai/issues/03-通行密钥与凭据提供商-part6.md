# ISSUES 分篇 · 通行密钥 / Credential Provider / 凭据回灌 · part6

> 索引：[`03-通行密钥与凭据提供商.md`](./03-通行密钥与凭据提供商.md)（编号 → 分篇）
> 本篇覆盖 **#293**（2026-11-15）。⚠️ 引用请带分篇名：`03-通行密钥与凭据提供商-part6.md #293`。
> 篇大小按 8.9 §7.1 控制（≤300 行 / 20KB）；part5 已 19.6KB，故另起一篇。

---

## 293. 🔴 正式版 KDBX 通行密钥登录 GitHub / x.com 报「通行密钥有问题」（2026-11-15）

> **触发（用户原话）**：「出现一个问题，使用 kdbx 上保存的通行密钥，登录 github 或者
> x.com 的时候，提示通行密钥有问题。但是使用 bitwarden 的通行密钥则没有问题，
> kdbx 是通过电脑上 XC 转换 json 后的库，XC 上没有问题，但是手机版 vaultix 上有问题，
> 正式 release 版的」。
> 版本：**正式 v0.13.2**（`933d629d` release 包），非 debug、非 preview。

### 293.1 现象与边界（三条对照，一句都别删）

这条反馈自带**差分实验**，比任何猜测都值钱：

| 对照 | 结果 | 排除什么 |
|---|---|---|
| 同一个 KDBX 库在**电脑 KeePassXC** 上用 passkey | ✅ 正常 | 排除「库里数据坏了 / 私钥不对 / 字段读不出」 |
| Vaultix 里的 **Bitwarden 库** passkey 登录同站点 | ✅ 正常 | 排除 Credential Provider 通用链路、P-256 签名、`clientDataJSON`、GitHub/x.com RP 侧 |
| Vaultix 手机版读**同一份 KDBX** 的 passkey | ❌ 「通行密钥有问题」 | ⇒ 根因只能落在 **KDBX → 领域模型这一层的映射** |

⚠️ 尤其第一条：候选能列出来、能选、能走到最后一步校验才报错，说明
`KdbxPasskeyCodec.toCredential()` **成功返回了凭证**（5 个必填字段都在）。
所以不是「字段体系不兼容」—— 下文 293.3 会订正一条误导人的旧注释。

### 293.2 根因：读侧把 base64url 文本改成了标准 base64

链条（每一环都有代码/测试实证）：

```
KPEX_PASSKEY_CREDENTIAL_ID（KeePass 生态约定 = base64url 无填充文本）
  ↓ KdbxPasskeyCodec.toCredential()  ← 旧实现调 normalizeCredentialId()
VaultFido2Credential.credentialId = 标准 base64（补 `=` 填充、`-`/`_` → `+`/`/`）
  ↓ PasskeyGetActivity → WebAuthn.buildGetResponseJsonFromStoredId()
  ↓ WebAuthn.rawIdFromStored()  ← 决策是「原样回传存储文本」
断言响应 id / rawId = 带 `=`（约 3/4 概率含 `+` `/`）的文本
  ↓ RP / CredMan 按 base64url 解码 ⇒ 失败 ⇒ 「通行密钥有问题」
```

**为什么只有 KDBX 中招**：
- `BitwardenImportMapper.toVaultFido2()`：`credentialId = credentialId` 原样，**不经任何转换**；
- `PasskeyCreateActivity`（本应用注册）：用 `WebAuthn.base64Url(...)` 写库，也是 base64url；
- 唯独 `KdbxPasskeyCodec.toCredential()` 里有一句 `normalizeCredentialId()`，
  把 KeePass 写进来的 base64url **反方向**转成了标准 base64。

**为什么「原样回传」是对的**：`WebAuthn.rawIdFromStored()` 是 2026-09-12 真机证伪
`base64Url(decode(stored))` 之后定下的决策（`WebAuthnTest` ② 明确钉死：
标准 / URL 两种合法形态都**不得改写**，见 #49 的 UUID 陷阱）。它成立的前提是
「领域模型里的文本 == 该凭证注册时 RP 拿到并会在 `allowCredentials` 里持有的那份文本」。
KDBX 字段正是那份文本 ⇒ **读侧唯一正确的动作就是什么都不做**。

旧 `normalizeCredentialId` 的注释写的是「转成标准 base64（Bitwarden 侧的表示）」——
那是把「领域模型该长什么样」的早期猜测，套在了一个**必须逐字符保真的标识**上。
字符串形态被改：类型对、字节数对、签名对，编译 / 单测 / detekt 全查不出，只有真机对上 RP 才炸。

### 293.3 订正过时溯源：KPEX 由 KeePassXC 定义，不存在 `KPXC_*` 体系

`KdbxPasskeyCodec.kt` 文件头旧注释写：
> 「该前缀来自 KeePassDX 的通行密钥插件……（KeePassXC 走自己的 `KPXC_*` 体系且版本间有差异）」

2026-11-15 查证 KeePassDX 官方 wiki 与 KeePass 生态资料：**`KPEX_PASSKEY_*` 正是由
KeePassXC 定义（2.7.7 起），KeePassDX / Strongbox / KeePassium 遵循同一命名**，
是跨客户端共享格式。旧注释是过时的二手信息。

⚠️ 这条注释误导性很强：按它推，用户「XC 转换的库」会被误判成「字段体系不兼容 →
Vaultix 读不出 passkey」，从而去做错误的兼容层；而真因在**读出来之后**被改坏。
已订正为可查证的表述，并写明「别因此误判」。

### 293.4 修法（只动读侧，不碰真机验证过的 `WebAuthn`）

1. `KdbxPasskeyCodec.toCredential()`：`credentialId = normalizeCredentialId(credentialId)`
   → **`credentialId = credentialId`**（原样，已 `trim()`）。
2. **删除** `private fun normalizeCredentialId()`（全仓仅此一处定义 + 一处调用）。
3. `toBase64Url()` 的 KDoc 订正：它只服务**写方向**（KDBX 落库按 base64url 约定），
   明确写上「读方向没有、也不许有它的逆运算」。
4. 文件头溯源订正（293.3）。

❌ **明确不动**：
- `WebAuthn.rawIdFromStored()` —— 2026-09-12 真机证伪过的 decode→re-encode 不许回来；
- `fromCredential()` 写方向（仍 `toBase64Url`，KDBX 互操作性靠它）；
- 签名链路、`PasskeyGetActivity`、`CredentialProviderEntryBuilder`（候选构造与断言查找
  用的是同一个 `credentialId`，同源，读侧改回原样不会造成错配）。

### 293.5 三处「把缺陷当规格」的测试订正（首轮编译 3 红）

首轮本地真编译真跑 `data/kdbx`：**274 例 / 3 红**。逐条查清后按该文件自己记过的先例
（`KdbxConversionTest` 里「`counter` 断言写 `0` 是把缺陷当成规格」）订正：

| 失败用例 | 真因 | 处置 |
|---|---|---|
| `通行密钥_往返保真且私钥字段受保护` | 样本 `credentialId = "////"`（标准 base64）靠读侧转回 `"____"` 才恒等 | 换成写读幂等的 base64url 文本 `"AQIDBA"`，断言同步 |
| `全字段对账_领域模型每个字段往返后都不丢` | 同上的整对象 `isEqualTo` | 同上换样本，注释写明「别把缺陷当规格换回来」 |
| `already standard base64 credential id is kept as is` | 我新加的 `rawIdFromStored("AQID+BA==")` 断言：这个短串是**畸形 base64**（长度非 4 倍数），会走该函数「非法文本 → UTF-8 兜底」分支 | 删掉该断言；rawId 契约交给真实 32 字节 ID 的用例 + `WebAuthnTest` ② |

⚠️ 第 3 条是**我自己引入的假红**：拿脏数据去做本不属于它的契约断言。已写进测试注释，
避免后人再踩。最终 `data/kdbx` **274 例全绿**。

### 293.6 跨模块契约测试 + 门禁 + 两轮反证

**契约测试**（`KdbxPasskeyCodecTest`）：新增
`KDBX credential id survives into assertion rawId byte for byte` —— 用真实 32 字节 ID
（首字节 `FB FF FE` 保证标准 base64 必现 `+` `/`）走
`toCredential()` → `WebAuthn.rawIdFromStored()`，断言逐字符相等、且不含 `+` `/` `=`。
这是历史缺失的那条**跨模块隐式契约**：`rawIdFromStored` 假设「库里文本 == RP 文本」，
以前没有任何测试钉住这一点，才让读侧与它漂移了三个月。

**源码门禁** `.ai/tools/check_kdbx_passkey_id.py` + 11 组变异自测
`tests/selftest_kdbx_passkey_id.py`，接 `ci-debug.yml` 两步（⚠️ 挂 `android-ci`，
不能挂 `ci-docs.yml` —— 后者 paths 不含 `.kt`/`.ai/tools/**`，#292/#218.7 同源的假绿灯）。
检查五项：读侧赋值必须是裸变量且函数体无 base64 转换形状 / 旧函数与标准编码器不得复活 /
`rawIdFromStored` 的「原样返回」分支仍在 / 契约用例在位 / 往返测试不得把 `"////"` 当期望值。
判据**直接复用** `check_production_safety.sanitize()`（#291 教训：同一判据别抄第二份），
自测专设一条「KDoc 里写旧函数名与编码器不误报」，防止门禁变成不许写教训。

⚠️ 门禁首版自身也假红过一次：`credentialId\s*=\s*(...)` 的全局正则先撞上局部变量
`val credentialId = fields.credentialId.trim()`。改为逐行区分声明与构造参数（跳过带
`val`/`var` 前缀的行）后转绿。**判据改动必须重跑 baseline 自测**。

**两轮反证**：
- 门禁层：11 组变异（复活 normalize 调用 / 改成 `toBase64Url` 归一 / 复活旧函数 / 插标准
  编码器 / 删 rawId 原样分支 / 改契约用例名 / 去契约引用 / 换回 `"////"` 期望 / 移走文件）
  全部判红；KDoc 干扰与 baseline 判绿 ⇒ **11/11**。
- 行为层：把 `normalizeCredentialId` 整段与调用**临时注入回生产代码**，
  `data/kdbx` 立刻 5 例红（含契约用例与两条往返用例），字节级还原后 274 例复绿。

### 293.7 CI / 出包结论（已看到真实 run 结果后回填，非预填）

> 施工时先留 `__TBD_*` 占位；提交、观察真实 run、核对 preview 产物后精确回填。
> 状态：`__TBD_CI_STATUS__`

- `AI docs consistency`：`__TBD_DOCS_RUN__`
- `Android CI (Debug)`：`__TBD_DEBUG_RUN__`
- preview Release：`__TBD_PREVIEW_NAME__`，资产 `__TBD_PREVIEW_ASSET__`
- 只推 `main`、**未推 `rele`** ⇒ 正式 `v0.13.2` 不受影响。

### 293.8 明确不做 / 未决

- **不推 `rele`、不发正式版**：本轮修的是 main 上的 bug，是否 bump `0.13.3` 发版由用户拍板
  （硬约束「每次合入 `rele` 都要用户点头」）。
- **真机验收不可省**：本地 274 例 + 门禁 + CI 全绿**不能替代真机**。用户需在
  preview 包上复验：KDBX 库 passkey 登录 GitHub / x.com 是否成功。
- **未复现项**：用户随消息附了一张站点报错截图，本环境未能读取图片内容；
  根因由三条对照 + 源码链路推出，仍需真机确认「提示通行密钥有问题」确为 rawId 校验失败。
- **不夹带**：`rawIdFromStored` 反向改造、BE/BS 标志细化、KeePassXC 2.7.12 的
  `FLAG_BE/BS` 迁移兼容、`KPXC_*` 兼容层（已证伪其存在）、崩溃可取证 S6。

### 293.9 判据（给下一轮，长期有效）

1. **「能被 RP 逐字节比对的标识」不许在中间层改文本形态**。`credentialId` / `userHandle`
   这类字段，读写两侧的 Base64 转换都是可疑的；`rawIdFromStored` 的全部设计就是「按形态归一、
   不改写合法文本」。在数据源层做编解码前，先问「这一层持有的文本，是不是最终要发出去的那份」。
2. **差分对照优先于猜测**。用户给的「Bitwarden 正常 / XC 正常 / 只有 Vaultix 失败」直接把
   根因收敛到唯一差异路径；不要先做兼容性猜测（本轮差点被 `KPXC_*` 旧注释带偏）。
3. **编译 / 单测 / detekt 查不出「字符串形态被改」**。这类契约要么上跨模块行为测试
   （把两层的真实函数接起来跑），要么上源码形状门禁，最好两者都有。
4. **测试红了先查是不是测试把缺陷当规格**，再查代码；但也别把自己的假红藏起来
   （293.5 第 3 条、门禁首版假红都留了注释）。
5. **过时的溯源注释会主动误导排查**。外部格式归属这类事实，写进文件头时留查证日期与出处。
