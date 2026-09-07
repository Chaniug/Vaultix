# Passkey 备份完整性设计计划（2026-09-05）

> 上游对标第 5 项（大，需设计决策确认后实施）。
> 来源：Monica 修复「WebDAV/OneDrive 备份恢复后通行密钥私钥缺失」；bastion 存在同源缺陷。
> 前置调研结论如下，方案选项需项目所有者确认后动工。

## 一、现状调研结论

1. **私钥形态**：bastion 本地 passkey 的私钥是**可导出的 PKCS#8 base64**
   （`PasskeyCreateActivity` 生成后 `keyPair.private.encoded`），
   经 `PasskeyPrivateKeyStore.protectForStorage` 存入
   `SecurityManager.putProtectedString`（EncryptedSharedPreferences），
   Room 里只留非敏感引用 `bastion-passkey-key-ref-v1:<storageKey>`。
2. **失效根源**：EncryptedSharedPreferences 的主密钥在 AndroidKeyStore，
   **不可跨设备迁移**。换机恢复备份后：记录在 → `resolve()` 取私钥失败
   → 通行密钥"看着在、签不了名"（Monica 同款缺陷）。
3. **系统列表可见性**：`PasskeyCredentialDiscoveryPolicy` 仅判断
   `privateKeyAlias.isNotBlank()`，**不校验私钥可解析性** → 失效记录
   仍出现在系统 passkey 登录列表，用户选中后必然失败。
4. **备份加密现状**：WebDav 备份支持可选密码加密（`enableEncryption` +
   `encryption_password`）；KeePass 库本身有库密码；但**私钥材料目前
   不在任何备份内容中**（备份只含 Room 数据，引用值恢复后指向的
   protected preferences 是空的）。

## 二、设计目标

- G1 换机恢复后 passkey 仍可用（私钥随备份迁移）；
- G2 旧备份 / 无私钥记录恢复后**不得**出现在系统 passkey 登录列表（防选中失败），
  元数据保留（rpId/用户名/创建时间）供重建提示；
- G3 备份报告统计 passkey 数量与私钥完整性（导出侧可校验、恢复侧可汇报）；
- G4 不改变 Bitwarden 云端 passkey 的同步行为（其私钥已在服务端加密字段中，天然跨设备）。

## 三、方案选项（需确认）

### 选项 A：私钥仅随「已加密备份」迁移（推荐）
- 导出：`enableEncryption=true` 时，passkey 私钥（`resolve()` 出的 PKCS#8）
  随备份导出（放在加密负载内，与现有备份密码同强度）；未加密备份**不带**私钥，报告注明。
- 恢复：用 `protectForStorage` 重新包裹入库（新设备 Keystore 重新保护）；
  恢复报告统计「passkey 总数 / 私钥齐全 / 私钥缺失」。
- 优点：安全边界清晰（备份文件已由用户密码保护）；实现集中在
  `WebDavHelper` 备份序列化 + `BackupRestoreApplier` 恢复侧。
- 缺点：未加密备份的用户拿不到私钥迁移（这是特性不是缺陷）。

### 选项 B：所有备份带私钥（不推荐）
- 简单，但未加密备份（本地 zip / 未开加密的 WebDav）会把通行密钥私钥
  暴露在明文文件里，违背 passkey 安全承诺。

### 选项 C：独立口令派生加密私钥段（折中，实现量 +30%）
- 私钥段用用户主密码（或备份密码）PBKDF2 派生密钥单独加密，无论备份
  是否开加密都随包；恢复时需用户输入对应密码解锁私钥段。
- 优点：未加密备份也不暴露私钥；缺点：恢复流程多一步密码交互，
  密码丢失则私钥段不可用（需降级为 G2 路径）。

## 四、共同实施内容（任一选项都需要）

1. **恢复后重保护**：`BackupRestoreApplier` / 各恢复通道恢复 passkey 后，
   逐条 `PasskeyPrivateKeyStore.protectForStorage`（把迁移来的明文 PKCS#8
   重新包裹进本机 Keystore 保护的 preferences），并校验
   `PasskeyPrivateKeySupport.decodeFlexiblePrivateKey` 可解析。
2. **失效记录降级**：恢复完成时对每条 passkey 试解析私钥；失败的标记
   `privateKeyAlias = ""`（或新列 `keyMissing = 1`）——
   `PasskeyCredentialDiscoveryPolicy` 增加可解析性校验，失效记录不再
   出现在系统 passkey 登录列表；详情页显示「私钥缺失，可重新注册」。
3. **备份报告**：导出/恢复完成报告增加
   `passkeys: total=X, withKey=Y, keyMissing=Z` 字段。
4. **签名侧兜底**：实际签名入口（`PasskeyPrivateKeySupport` 消费方）
   对 `resolve()==null` 直接返回明确错误（避免运行中才发现）。
5. **守卫测试**：源码守卫（恢复链路必须调用重保护；discovery 必须校验
   可解析性）+ 纯 JVM 单测（PKCS#8 编解码、报告统计）。

## 五、建议实施顺序（选项 A 前提）

1. `PasskeyCredentialDiscoveryPolicy` + 签名入口可解析性校验 + 失效降级（先止血）；
2. 恢复侧重保护 + 报告统计；
3. 导出侧私钥段（仅加密备份）+ 旧备份兼容（无私钥段 → 走降级）；
4. 全链路真机回归：本机备份→恢复→passkey 登录可用；旧备份恢复→降级提示。

> 涉及备份格式兼容与安全策略选择，待确认选项后动工。
