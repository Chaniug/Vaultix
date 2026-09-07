# Vault 冷启动加载 —— 修复记录（2026-08-30）

> 背景与对标调研见 [vault冷启动加载-对标调研.md](./vault冷启动加载-对标调研.md)。
> 本文记录**已落地的修复**、**真机验证方法**与**尚未做的后续项**。

## 一、症状

已登录 Bitwarden 且同步正常，手机放置一晚后重新打开 App，Bitwarden 区块会出现
一段可见的「加载」过程。预期行为：本地已有缓存，应当秒开。

## 二、根因（两个因素叠加）

### 根因 1：冷启动存在「全库解密预热」

`PasswordViewModel.init` 会无条件启动一个 `Dispatchers.IO` 任务：

```
warmupBitwardenOfflineSecretCache()
  → repository.getAllPasswordEntries().first()   // 全表
  → 逐条 decodePassword(entry.password)
      → unwrapPasswordLayersForDisplay()          // 每条最多 3 轮
          → synchronized(decryptLock) { securityManager.decryptData(...) }
```

影响：

- 每个条目 1~3 次 `decryptData`，且全部串在同一把 `decryptLock` 上；
- 与 `BitwardenRepository.restoreUnlockStateFromVault()`（每个 Vault 3 次 Keystore 解密）
  **争抢 Keystore**，把 Bitwarden 恢复解锁态的时间拖长 —— 这正是用户看到「加载」的直接原因；
- `init` 里另有两次修复扫描，加上预热共 **3 次全表扫描**。

官方 Bitwarden 与 Keyguard 均**没有**冷启动预热（Keyguard 的 `warmup` 只存在于
`desktopTest` benchmark，详见对标文档），两者都是「本地 Flow + 解锁门控 + 按需解密」。

### 根因 2：主安全密钥没有内存缓存

`SecurityManager.getOrGenerateSecureKey()` 每次调用都要走一遍
`KeyStore.getInstance + load + getEntry` —— 这是一次**跨进程 Binder 往返**。
同一文件里的 `getOrGenerateCompatSecureKey()` 早已用
`@Volatile cachedCompatDataKey + synchronized(compatDataKeyLock)` 做了双检锁缓存，
主密钥却漏了。于是 N 个条目 × 最多 3 轮 = 最多 3N 次 Keystore 往返。

（该密钥带 `setUserAuthenticationRequired(true)`，认证过期时 `Cipher.init()` 会抛
`UserNotAuthenticatedException`；`shouldForceVaultReauthenticationAfterDecryptFailure`
对这类异常无条件返回 true，批量场景下会把会话逐条击穿。）

## 三、已落地的改动

| # | 文件 | 改动 |
|---|------|------|
| 1 | `viewmodel/PasswordViewModel.kt` | 删除 `init` 里的预热任务；3 次全表扫描合并为 1 次；删除 `warmupBitwardenOfflineSecretCache()` / `rememberDecodedBitwardenSecrets()` |
| 2 | `viewmodel/BitwardenOfflineSecretCacheFacade.kt` | 删除随之变为死代码的 `rememberDecodedSecrets()` 与其 `decodePassword` 构造参数（外观类回归纯委托） |
| 3 | `bitwarden/cache/BitwardenOfflineSecretCache.kt` | 删除只服务预热的 `warmMemory()`（`clearMemoryCache()` 保留，锁定时仍会清内存明文） |
| 4 | `security/SecurityManager.kt` | `getOrGenerateSecureKey()` 增加 `cachedDataKey` + `dataKeyLock` 双检锁缓存；新增 `invalidateCachedSecureKey()` 并在别名删除 / 密钥永久失效 / 不可恢复三处调用 |
| 5 | `viewmodel/PasswordViewModel.kt` | `unwrapPasswordLayersForDisplay`：第 2 轮起先做前缀快筛，跳过注定原样返回的 `decryptData` |
| 6 | `viewmodel/PasswordViewModel.kt` | 新增 `shouldSkipDecryptAttempt()` 解密门控：MDK 包装丢失（必须重输主密码）时整体短路，不再逐条触发 `forceVaultReauthentication` |
| 7 | `security/SecurityManager.kt` | `requiresPasswordReentryForWrapperRebuild()` 的一次性告警闸门（它现在被逐条调用） |
| 8 | 三处 | 加冷启动耗时日志（见下） |

### 关于「批量解密」

调研时的设想是新增一个批量解密入口（复用单个解码器，替代 N×3 轮）。
落地时改为**直接缓存密钥句柄**，原因：

- 真正的开销在 Keystore 的 `getEntry`（跨进程 Binder），不在 AES-GCM 本身；
- 密钥缓存之后，所谓「批量」与「逐条循环」的差别只剩一次函数调用，再包一层
  `decryptBatch(List)` 只是增加抽象，不产生收益；
- 用户库 100~500 条，逐条按需解密的量级完全可接受。

### 安全性说明

缓存的是 Keystore 返回的密钥**句柄**，密钥材料仍留在 Keystore 内。
`setUserAuthenticationRequired(true)` 的校验发生在 `Cipher.init()` 而非 `getEntry()`，
因此缓存句柄**不会绕过生物认证**，认证过期时仍照常抛 `UserNotAuthenticatedException`。

### 移除预热后是否影响离线可用

不影响。预热只填**内存**；真正的离线兜底由 `remember()` 在用户查看/复制时**写盘**，
`recall()` 未命中内存时会从磁盘解密并回填内存。移除预热只是把「提前解密」变成
「按需解密」。

## 四、真机验证方法（荣耀 / Android 17）

### 1. 看日志

```bash
adb logcat -c
# 冷启动 App（放置一晚后第一次打开）
adb logcat | grep -E "PasswordViewModel|SecurityManager|BitwardenRepository"
```

关注的四条日志：

| 日志 | 含义 | 期望 |
|------|------|------|
| `Startup maintenance done: entries=N, costMs=M` | 启动维护（**不含任何解密**） | M 应为几十 ms 量级 |
| `restoreUnlockedVaults done: vaults=N, restored=R, costMs=M` | Bitwarden 恢复解锁态（3 次 Keystore 解密/Vault） | M 应明显下降 |
| `getMdkForCrypto: unwrapped MDK in Xms` | 首次解包 MDK | 冷启动只应出现一次 |
| `Offline secret cache warmup skipped` | 旧预热日志 | **不应再出现** |

### 2. 体感

- 隔夜后打开 App：Bitwarden 区块应直接显示已解锁内容，无加载过程；
- 点开任意条目查看密码：单次解密，应即时显示（首次会有一次 MDK 解包）。

### 3. 回归检查

- 修改/保存密码后重新打开，内容一致；
- 开启生物识别解锁后重启 App，能正常用指纹解锁；
- 关闭「永不锁定」后，冷启动应要求主密码/生物识别（不应因为预热移除而绕过）；
- 断网状态下查看历史查看过的密码，仍能显示（离线兜底未受影响）。

## 五、尚未做的后续项（留给后续 agent）

1. **共享解密结果 `StateFlow`**：对标官方 `vaultDataStateFlow`（Room Flow → 解密 →
   `stateIn(WhileSubscribed)`），让 UI 从单一数据流派生，彻底消除各 ViewModel 各自解密。
   改动面较大，需单独一轮。
2. **明文本生命周期收紧**：把内存中的明文与解锁态绑定 —— 锁定 / 会话过期时调用
   `BitwardenOfflineSecretCache.clearMemoryCache()`（该方法已存在，目前在
   `BitwardenViewModel` 锁定时调用一次），并对齐官方「lock 即清」语义。
   当前预热移除后，内存中明文只会来自真实查看/复制，暴露面已大幅缩小，故此条优先级下降。
3. ~~**`BitwardenViewModel.warmBitwardenOfflineSecretCacheForVault()` 阻塞同步完成回调**~~
   —— **已修复（见 §六）**：手动同步的整库预热改为后台调度
   （`scheduleManualOfflineCacheWarm`，1.5s 延迟 + `Dispatchers.IO`），
   与早已异步化的静默路径对称。原先卡住改动的「`offlineReadyCount` 依赖返回值」
   这一层担忧已排除：经核算 `warmedCount ≤ availableOfflineCount` 恒成立，
   `maxOf` 从来只取 DAO 计数，同步预热对显示数字零贡献。
4. **预热写盘仍是 N 次 `apply()` + O(N²) 内存拷贝**（本次未动，建议下一轮）：
   `BitwardenOfflineSecretCache.remember()` 每条都做一次
   `encryptDataLegacyCompat()` + 一次 `prefs.edit().apply()`，且 `putMemory()`
   每次都 `memoryCache.toMutableMap()` 全量拷贝（上限 1000 条时冷启动首轮为
   O(N²)）。改成「批量 `edit()` 一次提交 + 单次构建 map」可显著缩短预热窗口，
   代价是要给    `BitwardenOfflineSecretCache` 加一个批量 API。背景化后这条
   已从「卡 UI」降级为「缩短后台窗口」，优先级中等。

## 六、修复记录：手动同步完成后的整库预热改为后台调度

### 背景：两条同步路径不对称

| 路径 | 预热方式 | 位置 |
| --- | --- | --- |
| 静默自动同步 | 后台异步：`viewModelScope` + `delay(12s)` + `Dispatchers.IO` + 可取消 job | `scheduleSilentOfflineCacheWarm()` |
| **手动点同步** | **同步阻塞**：在 `SyncResult.Success` 分支里直接调用 | 原 `BitwardenViewModel.kt:1484` |

结果是「自动同步很顺、手动点同步反而卡」，且库越大越明显 —— 手动路径会把
N 条 ×（最多 3 轮 `decryptData` + 1 次 `encryptDataLegacyCompat` + 1 次
`prefs.apply()`）全部压在同步完成回调上。

### 卡点排除：预热返回值其实从未被使用

原先不敢改，是因为同步摘要里的 `offlineReadyCount` 看起来依赖预热返回值：

```kotlin
val warmedCount = if (silent) 0 else warmBitwardenOfflineSecretCacheForVault(vault.id)
val offlineReadyCount = maxOf(result.availableOfflineCount, warmedCount)
```

核算两者口径后确认这个担忧不成立：

- `result.availableOfflineCount` = `getAvailableOfflineSecretCount(vaultId)`
  = `SELECT COUNT(*) FROM password_entries WHERE bitwarden_vault_id=? AND isDeleted=0 AND isArchived=0`
  —— **未删除未归档的条目总数**（Room 计数查询，不解密）。
- `warmedCount` = 其中「已绑定 cipher 且 `password` 非空且解密成功」的条数
  —— 是前者的**子集**。

因此 `warmedCount ≤ availableOfflineCount` 恒成立，`maxOf` 从来只取后者。
**那次昂贵的同步预热对显示数字零贡献**，它唯一的产出是副作用（填充离线缓存）。
所以后台化是零语义损失的改动。

### 改动内容

1. `BitwardenViewModel` 新增 `MANUAL_SYNC_CACHE_WARM_DELAY_MS = 1_500L`
   （同步完成 → 列表刷新先落地，1.5s 后再后台预热；不像静默路径能等 12s）。
2. 抽出公共的 `scheduleOfflineCacheWarm(vaultId, delayMs)`，
   `scheduleSilentOfflineCacheWarm` / `scheduleManualOfflineCacheWarm` 分别委托它
   （12s / 1.5s）。两条路径共用同一个 `silentCacheWarmJobs`，保证同一时刻
   不会有两次整库解密抢 `decryptLock`。
3. 手动分支在 `refreshVaultListSnapshot()` 之前调用
   `scheduleManualOfflineCacheWarm(vault.id)`；`warmedCount` 的 else 分支改为
   直接取 `result.availableOfflineCount`（等价且不再解密）。
4. `BiometricUnlockRegressionGuardTest` 增补两条守卫：
   - 正向：源码必须含 `MANUAL_SYNC_CACHE_WARM_DELAY_MS` 与
     `scheduleManualOfflineCacheWarm(vault.id)`；
   - 反向：源码**不得**再出现 `warmBitwardenOfflineSecretCacheForVault(vault.id)`
     （防止有人把同步预热加回回调里）。

### 验证方式

- 本地 `:app:compileDebugKotlin` 通过，无新增警告；
- 跑 `BiometricUnlockRegressionGuardTest`（源码文本守卫）确认无回归；
- **真机**：手动下拉同步，观察同步完成提示是否即时出现、完成后列表滚动是否
  不再掉帧；同步完成约 1.5s 后后台预热会跑一次（logcat 无异常即可）。
  离线兜底行为不变 —— 未预热到的条目仍由 `recall()` 从磁盘解密。
