# Vault 冷启动加载：Bitwarden 官方 / Keyguard 对标调研

> 背景：实机反馈「已登录 Bitwarden、同步正常，手机放置一晚后再次点开 App，Bitwarden 有加载过程，理应从本地缓存秒加载」。
> 本文对标 **bitwarden/android（官方客户端）** 与 **AChep/keyguard-app（第三方客户端）**，提炼可借鉴设计，并列出 Bastion 的差距与改造方向。
> 调研时间：2026-08-30

---

## 一、官方 Bitwarden 客户端的核心设计

参考：`app/src/main/kotlin/com/x8bit/bitwarden/data/vault/`

### 1. 解密管道：本地 Flow + 解锁门控 + 批量解密

`manager/VaultSyncManagerImpl.kt:411-438`

```kotlin
private fun observeVaultDiskCiphersToCipherListView(
    userId: String,
): Flow<DataState<DecryptCipherListResult>> =
    vaultDiskSource
        .getCiphersFlow(userId = userId)                    // ① 数据源是本地磁盘 Flow（Room），离线优先
        .onStart { mutableDecryptCipherListResultFlow.updateToPendingOrLoading() }
        .map {
            vaultLockManager.waitUntilUnlocked(userId = userId)          // ② 未解锁则「挂起等待」，不抛异常
            vaultSdkSource
                .decryptCipherListWithFailures(                           // ③ 批量解密（Rust SDK 单次调用）
                    userId = userId,
                    cipherList = it.toEncryptedSdkCipherList(),
                )
                .fold(
                    onSuccess = { result -> DataState.Loaded(result...) },// ④ 部分失败仍返回 successes
                    onFailure = { throwable -> DataState.Error(...) },
                )
        }
        .map {
            it.takeUnless { settingsDiskSource.getLastSyncTime(userId) == null }
                ?: DataState.Loading                                      // ⑤ 仅「从未同步过」才算 Loading
        }
        .onEach { mutableDecryptCipherListResultFlow.value = it }
```

**五个关键点**：
1. 数据源是**本地磁盘 Flow**，与网络无关 → 离线优先。
2. `waitUntilUnlocked()`：未解锁时**挂起等待**，解锁后自动继续；不会抛异常、不会触发全局重认证。
3. `decryptCipherListWithFailures`：**批量解密**，一次 SDK 调用处理整个列表（密钥由 SDK client 持有，不逐条取 key）。
4. 返回 `DecryptCipherListResult`（`successes` + `failures`）：**单条失败不影响整体**。
5. `getLastSyncTime == null` 才是 Loading：本地已有数据就立即 `Loaded`，不做无谓等待。

### 2. 结果共享：单一 StateFlow，UI 全部派生

`manager/VaultSyncManagerImpl.kt:129-154`

```kotlin
override val vaultDataStateFlow: StateFlow<DataState<VaultData>> =
    combine(decryptCipherListResultStateFlow, foldersStateFlow, collectionsStateFlow, sendDataStateFlow) { ... }
        .stateIn(scope = unconfinedScope, started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_DELAY_MS),
                initialValue = DataState.Loading)
```

所有 UI 查询（`VaultRepositoryImpl.kt:114-202` 的 `getVaultItemStateFlow` / `getVaultListItemStateFlow` / `getAuthCodesFlow`）
都只对这个共享流做 `map` / `find` / `filter`——**解密只发生一次，UI 不重复解密**。

### 3. 安全模型：明文只在内存，锁定即清空

- `VaultSyncManagerImpl.init`（156-170 行）注释明确：用户切换或 vault 锁定时**清空内存中的 vault 数据**。
- 官方帮助文档：磁盘**只存密文**；解锁时在内存解密；锁定则删除全部解密数据（含 account encryption key）。
- 解锁可用 PIN / 生物识别 / 主密码，**解锁不需要网络**。

---

## 二、Keyguard 的核心设计

参考：`common/src/commonMain/kotlin/com/artemchep/keyguard/`

| 设计 | 实现 |
|------|------|
| **decoder 复用** | `provider/bitwarden/crypto/BitwardenCrypto.kt`：`BitwardenCr.decoder(key)` 一次构造 decoder（持有内存中的 `SymmetricCryptoKey2`），后续对全部字段/cipher 复用该纯函数，**无锁** |
| **无冷启动预热** | `core/store/bitwarden/BitwardenCipherRepositoryImpl.kt`：`get()` 返回 SQLDelight 的 reactive `Flow<List<BitwardenCipher>>`，仓库层只提供**密文领域对象**，解密在上层管道按需进行 |
| **离线优先** | 标语即为 "Offline access to decrypted vault items with background sync"；本地 SQLCipher 加密数据库 + 后台同步 + 增量同步（revisionDate 比对） |
| **会话管理** | auto-lock timeout + vault session persistence + 生物识别解锁 |

### 补充验证：Keyguard 生产代码确认无「解密预热」

用 GitHub 代码搜索核对 `warmup` / `preload` 关键字（2026-08-30）：

- `warmup` 共 12 处，**全部位于 `desktopTest` 的 benchmark 代码**
  （`BitwardenCryptoBenchmarkHarness.kt`、`WatchtowerBenchmarkTest.kt`、`CipherUrlCheckBenchmarkTest.kt`、
  `TldServiceBenchmarkHarness.kt`、`NativeCryptoLayerBenchmarkHarness.kt` 等）——即性能测试的 JIT 预热；
- `preload` 仅 1 处，位于 `server/web/src/layouts/BaseLayout.astro`（官网页面，与 App 无关）。

**生产代码中不存在全库解密预热机制**，与官方客户端结论一致。

> 附注：Keyguard 仓库含 Rust 代码（约 2.3%），加密下沉到 `util/crypto` 的 native crypto 层，
> 与官方「加密委托 Rust SDK」思路一致：把高频加解密移出逐条 JVM/Keystore 调用路径。

---

## 三、Bastion 现状与差距

Bastion 关键路径：`Bastion/app/src/main/java/com/bastion/app/`

| 维度 | 官方 Bitwarden | Keyguard | **Bastion 现状** |
|------|---------------|----------|-----------------|
| 数据源 | 本地磁盘 Flow | 本地 SQLDelight Flow | 本地 Room Flow ✅（列表流本身不解密，OK） |
| 未解锁时 | `waitUntilUnlocked()` 挂起等待 | 会话/自动锁定管理 | 解密抛异常 → `handleVaultDecryptFailure` → 可能 `forceVaultReauthentication` ❌ |
| 解密粒度 | 批量 `decryptCipherListWithFailures` | 复用 decoder 的流式管道 | 逐条 `decodePassword` → 每条最多 3 轮 `decryptData`，且在**全局锁 `synchronized(decryptLock)`** 内 ❌ |
| 密钥获取 | SDK client 持有（init 一次） | decoder 持有 key 对象 | 每次 `getMdkForCrypto()` / `getOrGenerateSecureKey()`；冷启动首条需现场从 Keystore 解包 MDK ❌ |
| 失败处理 | 收集为 `failures`，不中断 | 单点 `DecodeException` | 每条失败走重认证判定，最坏 N 次 ❌ |
| 结果复用 | 单一共享 `vaultDataStateFlow` | 上层管道 | 各处各自解密 + 额外跑一次全库预热 ❌ |
| 冷启动预热 | **无**（解锁后才解密） | **无** | `PasswordViewModel.init` 无条件启动 `warmupBitwardenOfflineSecretCache()`，全库取数逐条解密明文进内存 ❌ |
| 冷启动全表扫描 | 一次 | 一次 | `init` 内 3 次 `getAllPasswordEntries().first()`（repair×2 + 预热×1）❌ |
| 明文持久化 | 不写盘（锁定即清） | 加密 DB | `warmMemory` 只写内存；`remember()` 写盘但仅在真实查看/复制时触发 |

**结论**：Bastion 的列表数据流本身是对的（本地 Flow、不解密、可秒出），
问题出在 `PasswordViewModel.init` 的**冷启动全库解密预热**——
它以「全量取数 × 逐条 × 最多 3 轮 × 全局锁 × Keystore」的方式，
**抢占了 Bitwarden 恢复解锁态所需的 Keystore 与解密锁**（`BitwardenRepository.restoreUnlockStateFromVault` 需做 3 次 Keystore 解密），
于是表现为「放置一晚后冷启动，Bitwarden 有加载过程」；而进程存活时内存缓存命中，故秒开。

---

## 四、建议改造方向（按优先级）

1. **引入「解锁门控」语义**（对齐 `waitUntilUnlocked`）
   解密前先确认密钥就绪；未就绪则挂起/跳过，**不得**逐条触发 `handleVaultDecryptFailure` 与 `forceVaultReauthentication`。

2. **批量解密入口**（对齐 `decryptCipherListWithFailures`）
   一次取得密钥并构造 decoder，批量解密整个列表；返回 `(successes, failures)`，单条失败不影响整体。

3. **共享解密结果流**（对齐 `vaultDataStateFlow`）
   `stateIn(WhileSubscribed)` 缓存批量解密结果，UI 派生查询，消除重复解密。

4. **取消/延后冷启动全库预热**
   改为解锁后按需批量解密；若保留预热，则必须：延后到解锁就绪后、限量分批 + `yield()`、首条失败即中止整轮。
   同时把 `init` 内 3 次全表扫描合并为 1 次。

5. **收敛明文生命周期（安全对齐）**
   当前冷启动即在会话过期状态下把全库密码明文解密进内存，偏离官方「明文仅在解锁后内存、锁定即清」模型。
   建议：内存明文缓存绑定解锁态，锁定/会话过期即 `clearMemoryCache()`；离线兜底优先缓存**展示字段**（name/username），密码明文按需解密。

6. **（可选）离线展示字段落盘**
   对齐 Keyguard「offline decrypted items」：在有密钥时把展示字段用本地设备密钥加密持久化，
   冷启动直接读本地缓存渲染，无需等待 vault 密钥 → 真正的「秒加载 + 离线」。

---

## 五、参考链接

- 官方客户端：`https://github.com/bitwarden/android`
  - `data/vault/manager/VaultSyncManagerImpl.kt`（解密管道、共享流、锁定清理）
  - `data/vault/datasource/sdk/VaultSdkSource.kt`（`decryptCipherListWithFailures`）
  - `data/vault/repository/VaultRepositoryImpl.kt`（UI 派生查询）
- Keyguard：`https://github.com/AChep/keyguard-app`
  - `provider/bitwarden/crypto/BitwardenCrypto.kt`（decoder 复用）
  - `core/store/bitwarden/BitwardenCipherRepositoryImpl.kt`（reactive 密文仓库）
- 官方登录 vs 解锁说明：`https://bitwarden.com/help/understand-log-in-vs-unlock`
