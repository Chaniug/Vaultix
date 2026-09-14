# Vaultix 功能健康度审计报告

> 审计日期：2026-09-14
> 审计范围：Bitwarden 全链路（官方 API 同步 / 本地导入导出 / 六类条目 CRUD / 自动填充 / 设置项）
> 方法：静态审计（4 个并行深度审计）+ 代码级实证核对 + 编译验证
> 真机：荣耀 BKQ-AN00（mDNS 连接，原 `192.168.1.114:36813` 端口已轮换失效）

---

## 一、结论速览

**Bitwarden 功能整体架构完整、设计意图清晰**，但存在 **1 个 P0 缺陷 + 5 个 P1 缺陷**，其中 3 个已在本次修复。

| 级别 | 数量 | 已修 | 说明 |
|---|---|---|---|
| **P0** | 1 | ✅ 已修 | 自动填充解锁回灌失效（`noHistory`） |
| **P1** | 5 | ✅ 2 已修 | 编辑回退 / URI 规则丢失已修；同步冲突 / SSH 只读 / 空库保护待定 |
| **P2** | 8+ | — | 文案、退避、性能、清理类 |

**回答你的核心问题：**
- **同步 / 拉取 / 上传**：功能存在且完整（官方 API + 本地文件双通道），但**有数据丢失风险**（见 P0-2/P0-3 同步项）。
- **六类条目 CRUD**：基本健康。**独立验证码条目**采用 Bitwarden 兼容形态（`password` 为空的 Login），设计正确。
- **设置项**：无空壳设置，所有开关均落地生效（这点做得很好）。
- **填充与登录**：**发现 P0 缺陷**——解锁后的回灌链路被 Manifest 配置打断。
- **Passkey 仪式感**：**完全可实现**，且项目里已有现成基础（`passkeyCount` 字段）。

---

## 二、P0 缺陷（本次已修复）

### P0-1 自动填充「解锁即回填」被静默打断

**现象**：用户在输入法/站点触发自动填充 → 被引导去解锁 → 指纹解锁成功 → **返回后条目列表空白，密码填不进去**。表现为"反复解锁却永远看不到密码条目"。

**根因**（双重矛盾）：
- `AutofillActivity` 的架构要求"**留在栈上**"——类注释明确写：
  > "关键设计：走『打开 Vaultix 解锁』那条路时本 Activity **不 finish** —— 它必须留在栈上，否则解锁完成后没有任何人能投递认证结果"
  > （`AutofillActivity.kt:89-91`）
- `openVaultAndFinish()` 也确实**故意不 finish**（`AutofillActivity.kt:255-259`），靠 `onResume`（`:196`）收尾。
- 但 `AndroidManifest.xml:79` 声明了 `android:noHistory="true"`。

`noHistory` 的语义是"**一旦不可见即销毁并 finish**"，与"留在栈上"**物理互斥** ⇒ 用户跳去解锁的瞬间 Activity 被销毁 ⇒ `onResume` 永不触发 ⇒ 回灌静默丢失。

**根因的来源（值得记录）**：`noHistory` 是从 Bitwarden **照抄配置**来的（注释自己写着"对照 Bitwarden 的认证 Activity 声明：exported + launchMode=singleTop + **noHistory** + 透明主题"），但**没意识到本项目采用了与 Bitwarden 不同的回灌架构**——Bitwarden 走 `NEW_TASK` + 独立进程回灌，本项目走"留在栈上等 onResume"。**照抄配置却承袭了不同架构**是这类 bug 的典型成因。

**修复**：移除 `android:noHistory="true"`，并在 Manifest 补充完整因果注释（编号 `#90`）。

**文件**：`app/src/main/AndroidManifest.xml:79`

---

## 三、P1 缺陷

### P1-1 编辑条目时「文件夹 / 收藏」被静默回退 ✅ 已修

**现象**：在编辑页勾选"收藏"、选择文件夹，保存后**又变回原样**。

**根因**：`ItemRepositoryImpl.kt:204` 有一行
```kotlin
.copy(folderId = existing.folderId, favorite = existing.favorite)
```
用 DB 旧值覆盖了表单传来的新值。

**为什么这是明确 Bug**：`CipherMapper.toUpdateRequest` 的注释白纸黑字写着
> "2026-09-08：改为按**表单意图**写入（此前固定沿用 stored，等于用户在 Vaultix 里根本无法改文件夹 / 收藏）"
> （`CipherMapper.kt:208-211`）

**mapper 已经专门修过这个问题，但调用方又把它覆盖回去了**——等于 9-08 的修复被抵消。本地行第 212 行的 `favorite = existing.favorite` 同样是回退。

**修复**：移除那次 `.copy`，本地行改按表单意图落库。

**文件**：`data/repository/.../ItemRepositoryImpl.kt:200-215`

### P1-2 编辑 Login 后 URI 匹配规则被清空 ✅ 已修

**现象（静默）**：导入的条目若带 `match = Exact(3)` / `RegularExpression(4)` / `Never(5)`，用户**只要编辑保存过一次**，规则就被清成 `null`（退化为默认基域匹配）。

**根因**：`FormValues.kt:76`
```kotlin
uris = values.uris.filter { it.isNotBlank() }.map { VaultUri(it) }   // match 恒为 null
```
表单只编辑 URL 文本、不编辑 match，但重建对象时丢了 match。

**实际影响**：`Never` 规则的站点（明确"永不填充"）在编辑后会**反而被自动填充**——这是实打实的行为改变，用户完全无感知。

**修复**：按索引保留原有 match
```kotlin
uris = values.uris.filter { it.isNotBlank() }.mapIndexed { index, url ->
    VaultUri(url, initial.uris.getOrNull(index)?.match)
}
```

**文件**：`app/.../ui/common/FormValues.kt:76`

### P1-3 拉取覆盖本地未推送修改（同步冲突） ⏳ 待定

**根因**：`BitwardenSyncService.kt:196-199` 的 `persistCiphers` 无条件 `upsertAll`，**不比较 revisionDate**。本地已改但还没推送成功的条目，若远端也被改过，会被服务端版本**直接覆盖**。

`pendingIds` 只保护"不被 prune 删除"，**不保护"不被 upsert 覆盖"**。

**影响**：多设备场景下可能丢改动。属 last-write-wins 且偏向服务端。

### P1-4 4xx 弃单后本地新建条目被静默删除 ⏳ 待定

**根因**：`BitwardenSyncService.kt:118-121` 对 4xx（400/403/404/405）永久弃单并移除队列；紧接着全量 sync 的 `pruneRemovedRows`（`:213-220`）发现该临时 UUID 不在服务端列表里，**直接删掉本地行**。

**影响**：用户新建的条目凭空消失，且无任何提示。属本地数据丢失。

### P1-5 空库保护无"确认继续"出口 ⏳ 待定

**根因**：`EmptyVaultProtection` 在"非首同步 + 本地>0 + 服务端==0"时 `Blocked`，但**没有让用户确认后强制继续的路径**。

**影响**：用户若真在其它设备清空了全部条目，本机同步会**永久卡死**在 Blocked 状态。

### P1-6 SSH 密钥字段全部只读 ⏳ 待定（可能是有意设计）

`ItemFormDialog.kt:210` 将 SSH 段渲染为只读。导入的 SSH 密钥无法在 App 内修改私钥/公钥/指纹。若是有意为之可忽略。

---

## 四、P2 问题清单

| # | 问题 | 位置 |
|---|---|---|
| 1 | 429 不读 `Retry-After`，退避不感知限流 | `BitwardenSyncService.kt:269` |
| 2 | `flushPending` 单条失败无次数上限，毒丸条目永久占用队列 | `BitwardenSyncService.kt:117-126` |
| 3 | 刷新 token 未带 device 头，与登录路径不一致 | `BitwardenAuthRepository.kt:213-219` |
| 4 | `refreshMutex` 全 server 共享单锁，多库互相阻塞 | `BitwardenAuthRepository.kt:64,208` |
| 5 | `decryptToString` 失败静默降级空串，更新时可能写空 | `CipherMapper.kt:404-405` |
| 6 | 导入**无去重**，重复导入产生重复条目 | `VaultExportRepositoryImpl.kt:89-102` |
| 7 | 导入**非事务**，部分失败会写入一半且不回滚 | `VaultExportRepositoryImpl.kt:100-102` |
| 8 | 新建条目进文件夹本地显示"未分类"（需下次同步归位） | `ItemRepositoryImpl.kt:169` |
| 9 | 批量删除逐条网络推送，非原子 | `ItemsScreen.kt:286` |
| 10 | `Blocked` 状态文案括号为空（占位符未插值） | `EmptyVaultProtection.kt:55-56` |
| 11 | 死字符串残留：`setting_lock_now` / `setting_passkey_saved*` 等 | `strings.xml:124,180-187` |
| 12 | 无文件夹 CRUD（只读）、无标签功能 | `FolderRepository.kt:7-20` |
| 13 | 无 `asset_statements`，WebAuthn asset link 校验路径缺失 | 全仓 |
| 14 | `versionCode` 固定为 1，不随版本递增 | `app/build.gradle.kts:22` |

---

## 五、表现良好的部分（明确记录，避免日后误改）

- **设置项全部落地**：审计了 19 个 key，**未发现任何"UI 有开关但后端没接"的空壳设置**。自动锁定、剪贴板清理、防截屏、生物识别、主题、回收站清理、自动填充各开关均真正生效。
- **错误分类完善**：导入错误细化为 4 类（密码错 / 格式错 / KDF 不支持 / 信封缺失），UI 全部展示。
- **密钥安全**：master key 用毕 `zero()`、token 用 Android Keystore AES-256-GCM 加密落盘、未发现日志打印明文。
- **401 处理正确**：有防死循环保护（`priorResponse != null` 直接放弃）、并发去重（mutex）。
- **三态空态**：填充列表 / 保存界面 / Passkey 列表均正确区分"加载中 / 真的没有 / 查询无结果"，**未发现假空态**。
- **Passkey 流程完整**：Credential Provider 的 GET（断言）与 CREATE（注册）双链路都实现了，签名构造正确。
- **删除语义正确**：软删除（回收站）+ 硬删除 + 自动清理，删除不破坏关联密文（可恢复）。
- **类型守恒守卫**：编辑时校验服务端 type，防止未知类型漂移导致数据重写。

---

## 六、Passkey「登录次数」仪式感 —— 可行性与方案

**结论：完全可实现，且比想象中简单。**

### bastion 的原实现（已查证）

| 项 | 实现 |
|---|---|
| 数据 | Room 表 `passkeys` 两个字段：`last_used_at: Long`、`use_count: Int` |
| 更新时机 | **使用 passkey 签名后**（非保存时）+1 |
| SQL | `UPDATE passkeys SET last_used_at = ?, use_count = use_count + 1, sign_count = ? WHERE credential_id = ?` |
| UI | 详情页"活动"分区三行：创建时间 / 最近使用 / 使用次数 |
| 位置 | `PasskeyAuthActivity.kt:540-555`（更新）、`PasskeyDetailPanes.kt:124-182`（展示） |

**注意**：bastion 是"保存后异步更新、不阻塞签名返回"，这个细节很重要（不能因为统计拖慢登录）。

### Vaultix 的落地条件

**好消息**：
1. 项目里**已有** `passkeyCount` 字段（`SettingsViewModel.kt:270`）统计 passkey 总数，口径设计得很讲究——它就是你想要的功能的现成基础。
2. Passkey 的 GET/CREATE 链路已完整（`PasskeyGetActivity` / `PasskeyCreateActivity`），**插入统计埋点的位置现成**。
3. `VaultFido2Credential` 模型已有 `counter` 字段（WebAuthn 签名计数器），可复用为 `use_count` 的近亲。

**要做的**：
1. 在 `VaultFido2Credential` 增加 `lastUsedAt: Long?` 与 `useCount: Int`（或复用 `counter`）。
   ⚠️ **注意**：该模型会参与 Bitwarden 导入导出映射，新增字段需确认序列化兼容（官方 DTO 无这两字段，导出时应 skip）。
2. 在 `PasskeyGetActivity` 签名成功后，异步 +1（参照 bastion 不阻塞返回的做法）。
3. 在 `ItemDetailScreen` 的 passkey 区域（现在是 `:818-833` 只显示条数）展开为"活动"卡片，展示创建时间 / 最近使用 / 使用次数。
4. 可选：把 `SettingsViewModel.passkeyCount` 接到设置页（顺带消灭这个死代码）。

**风险提示**：`VaultFido2Credential` 是**要参与导入导出**的模型，与 bastion 那个纯本地表不同。新增统计字段若处理不当，可能污染 Bitwarden 导出文件或破坏往返一致性——**必须做序列化兼容测试**（项目里已有 `CipherMapperTotpUriFido2Test` 可扩展）。

---

## 七、关于 KDBX 补齐

**当前 KDBX 现状**：
- `data/kdbx/` 是**纯只读模块**（依赖 kotpass），支持 unlock / contentOf / 映射为 VaultItem。
- **写入 / 导出未实现**（grep `save/write/export` 无匹配）——**这是你准备补齐的部分，确实是空白**。
- 类型映射**有损**：仅 Login / SecureNote 两型，其余一律映射为 Login（`KdbxItemMapper.kt:107-119`）。
- 回收站条目不计入（`KdbxItemMapper.kt:61-72`）。
- **UI 未接入设置页导入导出**：`ImportExportScreen.kt` 全文无 `kdbx` 字样，KDBX 目前只作为"添加库"入口存在。

**建议顺序**：先修完上述 P0/P1（尤其同步类的 P1-3/P1-4，它们会影响 KDBX 落地后的数据一致性），再动 KDBX 写入。

---

## 八、本次改动清单

| 文件 | 改动 |
|---|---|
| `app/src/main/AndroidManifest.xml` | 移除 `noHistory="true"`，补因果注释（#90） |
| `data/repository/.../ItemRepositoryImpl.kt` | 移除覆盖表单值的 `.copy`，本地行按表单意图落库 |
| `app/.../ui/common/FormValues.kt` | URI 重建时按索引保留 `match` |
| `app/build.gradle.kts` | `versionName` 回退来源改为读 `VERSION` 文件 |

**验证状态**：`BUILD SUCCESSFUL`（`:app:compileFullDebugKotlin` + `:data:repository:compileDebugKotlin`）
