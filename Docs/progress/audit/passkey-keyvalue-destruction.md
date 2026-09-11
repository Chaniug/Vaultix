# 审计：通行密钥私钥材料可被写路径抹除（P0，不可逆）

> 日期：2026-09-11　发现方式：真机 adb 排障（Edge + GitHub 通行密钥登录失败）反查
> 状态：**✅ 已修复**（`overlayLogin` 改为按 credentialId 保留服务端原密文；回归测试见
> `CipherMapperTotpUriFido2Test` 的 5 个 P0 用例）
> 关联证据：`build/adb-capture/passkey-failure-evidence.txt`、`build/adb-capture/passkey-keyvalue-audit.txt`

---

## 1. 一句话结论

**`overlayLogin` 在更新登录条目时，会把整张 `fido2Credentials` 表按领域模型重新加密写回；
只要模型里某条凭据的 `keyValue` 为空，服务端已存的私钥材料就会被覆写成 `null` ——
官方客户端与其它设备此后同样无法再签名，且不可恢复。**

## 2. 缺陷链条（三处代码，均已核对原文）

```kotlin
// ① data/bitwarden/.../CipherMapper.kt:596  空串 → null
private fun encryptOpt(text: String, key: SymmetricCryptoKey): String? =
    text.takeIf { it.isNotBlank() }?.let { crypto.encryptString(it, key) }

// ② 同文件:526  写请求：模型为空就写成 null（字段消失）
keyValue = encryptOpt(c.keyValue.orEmpty(), key),

// ③ 同文件:248  更新路径 overlayLogin：对 fido2 列表【全量重写】
fido2Credentials = item.fido2Credentials.map { mapFido2Request(it, key) },
```

②③ 合起来即：**模型空 → 服务端 null**。

## 3. 铁证：同文件其它段全都保留原密文，只有 login 的 fido2 例外

| 段落 | 做法 | 原文注释 |
|---|---|---|
| `overlayCard` | `… ?: stored.card` | 其余类型沿用服务端原密文（**防丢载荷**） |
| `overlayIdentity` | `… ?: stored.identity` | 其余类型沿用服务端原密文 |
| `overlaySshKey` | `… ?: stored.sshKey` | 其余类型沿用服务端原密文 |
| `overlaySecureNote` | 沿用 stored | — |
| **`overlayLogin`（fido2）** | **`item.fido2Credentials.map { … }` 全量重加密** | 「领域模型已加载全部凭证（含服务端原值），按表单意图完整重加密」 |

那个「含服务端原值」的前提，**在模型 `keyValue` 为空时直接崩塌** —— 而它恰恰是我们
正在排查的那个状态。作者的假设与受害场景是同一个。

## 4. 与既有文档承诺矛盾

`Docs/progress/decisions.md:38`（2026-09-08）：

> 条目更新 = 合并上传，绝不整条重写 …… uri/totp/**fido2**/card/identity/sshKey/secureNote/
> fields 未编辑段**沿用服务端原密文**随请求提交

**文档承诺了 fido2 保留，代码没有实现。**（同类 doc↔code 背离本项目已出现多次，如
`TYPE_PASSWORD_CREDENTIAL` 的四份文档滞后。）

## 5. 测试盲区（为什么全绿也挡不住）

`data/bitwarden/src/test/.../CipherMapperTotpUriFido2Test.kt`：

- 第 153 行注入 `keyValue = crypto.encryptString("PRIVATE-KEY-MATERIAL", accountKey)`
- 第 176 行解密比对，断言往返一致

即**只覆盖「模型有 keyValue」的路径**。破坏性情形 —— **模型 `keyValue` 为 null 而服务端有值**
—— **无任何测试覆盖**。（该测试存在的理由来自 KDoc:421：曾因「只读 8 字段、写回用默认值顶」
破坏数据；但把「读全 13 字段」修好之后，**「读到了空值同样会覆写」这条更隐蔽的路径被漏掉**。）

## 6. 第二条同源入口（UI 层）

- `ui/common/SavePasskeyDialog.kt`：50（`keyValue` 初值为空串）、126–127（可编辑文本框）、
  156（`keyValue.takeIf { it.isNotBlank() }`）—— 让用户**手工填写一张通行密钥，含私钥字段**。
- `ui/passkeys/PasskeysViewModel.kt:138`：`keyValue = keyValue.ifBlank { null }`

⇒ 用户在「新增通行密钥」对话框里**留空私钥保存**，即向模型写入 `null`；
若该条目随后被更新，服务端原私钥材料被抹除。

> 设计层面：让用户手工粘贴私钥本身就不合理（私钥不应经人手输入）。
> 该入口应改为「仅允许从设备/文件导入」或明确标注其破坏性。

## 7. 真机旁证

库内共 15 条通行密钥，**14 条 `keyValue` 有密文，1 条字段整个缺失**
（`keyValue: MISSING`，非空串 —— 与 `encryptOpt("") → null` 的序列化结果一致）。
失败的那条（github.com）正是「点击后 `cannot parse keyValue (blank)`」的当事条目。

⚠️ 但**「字段缺失 = 被我们抹除」尚未证实**，另一种同样自洽的解释是「解密失败导致读到的为空」。
二者在日志上完全无法区分 —— 因为 `decryptToString` 的 `getOrDefault("")` 把
「字段缺失」与「解密异常」**统一成了空字符串**（`CipherMapper.kt:329-341`）。
见下节行动序。

## 8. 行动序（★ P0 不依赖任何诊断，先做）

- **P0（无条件）**：`overlayLogin` 改为**保留服务端 fido2 原密文** ——
  按解密出的 `credentialId` 与 `stored.login.fido2Credentials` 配对，配对上的**原样沿用**；
  仅对**新增**凭据执行加密。并加硬护栏：**绝不用 null/空值覆盖已存在的 `keyValue`**。
  同步收敛 `SavePasskeyDialog` 的手工私钥入口。
  **该项与「github 那条到底怎么坏的」无关 —— 数据破坏本身就该先止血。**
- **P1（只读，无需新建通行密钥）**：用 **Bitwarden 官方客户端**对同一条目发起该通行密钥登录。
  能签 ⇒ 服务端仍有材料（问题在我方同步/映射）；不能签 ⇒ 已被覆写（不可逆）。
  ※ 比「新建一条通行密钥」可行得多：GitHub 侧无法随手新建，且会与既有凭据冲突。
- **P2**：候选可用性判据（严格过滤 `keyValue` 非空）+ **区分性诊断**
  （把「字段缺失」与「解密失败」分开记录，替换 `getOrDefault("")` 的静默合并）。
  `unusable` 计数可顺手扩为「`keyValue` 为空也计入」，点一次即可读出全库不可签条数。

## 9. 方法论教训

1. **「字段存在」≠「字段能解密」**：本轮曾把二者混为一谈，并据此提出「恢复严格过滤」的方案 ——
   若真因是解密失败，该方案会原样重现 `fc076ea` 的「一个候选都不显示」。**自己刚指出的陷阱，
   自己再踩一次。**
2. **别把「某提交之后好了」当成「该提交修对了」**：`fec4032`（放宽过滤）与 `f2593b4`
   （修 trim / rpId 归一化）时间相邻，前者的效果很可能是后者带来的。
3. **静默降级会摧毁诊断能力**：`getOrDefault("")` 让两种本质不同的故障同形，
   直接导致本次多花数轮才能推进。

---

## 10. 修复记录（2026-09-11）

### 10.1 写路径：`overlayLogin` 改为保守合并
`CipherMapper` 新增 `mergeFido2Credentials(modelList, storedList, storedDto, key)`，
`overlayLogin` 改为调用它。规则：
- model 命中 stored（按解密后的 `credentialId`）→ **沿用 stored 原文，密文一字不改**
- stored 中未被命中 → 用户删除，剔除
- model 中未命中 stored → 新增，按明文加密
- **失败一律保守**：任一条 stored 的 credentialId 解不开，或 model 出现空 credentialId
  ⇒ 直接返回 stored（宁可不改，绝不冒险抹除）

对齐 Bastion `Fido2CredentialCodec.mergeByCredentialId`（「其余既有条目全部保留，
避免覆盖丢失」）与 `encryptIfNeeded`（已加密值原样透传）。

### 10.2 回归测试（`CipherMapperTotpUriFido2Test`，5 个新用例）
1. `updateKeepsServerKeyValueWhenModelKeyValueIsBlank` —— ★ 事故本身：
   模型 `keyValue = null`、服务端有值 ⇒ 请求里的密文必须**逐字节一致**
   （CBC 随机 IV ⇒ 只要重加密就必然不等，故该断言很硬）
2. `updateDoesNotReEncryptUntouchedFido2Credentials` —— 未改动即不重加密（整 DTO 相等）
3. `updateAppendsNewCredentialAndKeepsExistingCipherText` —— 追加时既有条目原样保留
4. `updateDropsCredentialRemovedFromModel` —— 删除生效，其余保留
5. `updateFallsBackToStoredWhenStoredCredentialIdIsUndecryptable` —— 保守回退路径

### 10.3 既有测试的一处夹具失真（顺带修正）
`CipherPayloadPreservationTest.updatePreservesLoginUrisTotpFido2AndFields` 原先用
**假的占位明文** `Fido2CredentialDto(credentialId = "enc:fido")` 当夹具。旧代码「整表重加密」
恰好把它加密成合法密文，断言才解得开 —— **等于在无意中为破坏性行为背书**。
已改为真实密文，并把断言加强为「密文逐字节保留 + keyValue 明文不丢」。

### 10.4 第二条入口：`SavePasskeyDialog` 手工填私钥
- `keyValue` 由「（可选）」改为**必填**，留空即报错拦截（新增字符串
  `passkey_error_key_value_required`）。
- 依据：参考实现（Keyguard `PasskeyCreateRequest`）里 `keyValue` 一律来自真实密钥对
  `PasskeyBase64.encodeToString(keyMaterial.privateKeyPkcs8)`，**没有任何实现允许手工留空**。
- **真机旁证**：库中唯一缺 `keyValue` 的那条凭据（正是失败的 github.com 那条），
  形态与该对话框「填 credentialId/rpId、私钥留空」的产物完全吻合。

### 10.5 验证
`detekt`（10 模块全绿）+ `:app:compileFullDebugKotlin` +
`:data:bitwarden:testDebugUnitTest`（48 用例）+ `:app:testFullDebugUnitTest` 全部通过。
