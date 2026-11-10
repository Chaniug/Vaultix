# 附录 B · 编码规范扫描口径与逐项明细（2026-11-11）

> 主报告：[`../project-audit-2026-11-11.md`](../project-audit-2026-11-11.md) §2。
> 判据来源：`ai-code-standards`（团队强制）；供应链检查方法借鉴 `skills-security-check`。
> **本文件是证据，不是整改清单** —— 大部分命中是误报或口径差异。

## B.1 扫描范围与口径

| 项 | 值 |
|---|---|
| 扫描根 | `app/src`、`core/*/src`、`data/*/src`、`domain/src` |
| 文件数 | **539** 个 `.kt` |
| 总行数 | **149,498**（非空且非 `//` 开头的代码行约 129,953） |
| 阈值（团队规范） | 行宽 120 / 方法 80 行 / 类 800 行 |
| 对照（项目 detekt） | `config/detekt/detekt.yml`：方法 **150** 行 / 类 **1200** 行 / 参数 8 / 单类函数 40 |
| 脚本 | `/tmp/scan_standards.py` → `/tmp/scan_results.json`（临时文件，不在仓库） |

⚠️ **方法/类长度的启发式算法与 detekt 不等价**：脚本按「相邻 `fun` / `class` 声明之间的行距」
计数，**包含 KDoc、注释与空行**；detekt `LongMethod` 只数代码行
（`.ai/conventions/8.6-工程质量.md` 有探针实验：45 行代码 + 45 行注释 + 6 行 KDoc ⇒ detekt 报 47）。
⇒ 本附录的 `long_method` / `long_class` **只能用于定位**，不能声称「CI 会红」。

## B.2 19 类命中统计（生产 / 测试拆分）

| 类别 | 总数 | 生产 | 测试 |
|---|---:|---:|---:|
| `long_method`（>80 行） | 135 | 131 | 4 |
| `long_class`（>800 行） | 11 | 9 | 2 |
| `long_line`（>120 字符） | 1 | 1 | 0 |
| `bare_catch` | 26 | 21 | 5 |
| `swallow_catch` | 0 | 0 | 0 |
| `system_out` | 2 | 2 | 0 |
| `print_stack` | 0 | 0 | 0 |
| `log_direct` | 15 | 14 | 1 |
| `inline_fqcn` | 0 | 0 | 0 |
| `comment_no_space` | 159 | 130 | 29 |
| `todo` | 0 | 0 | 0 |
| `bang_bang` | 225 | 3 | 222 |
| `global_scope` | 0 | 0 | 0 |
| `run_blocking` | 95 | 13 | 82 |
| `thread_sleep` | 7 | 3 | 4 |
| `plaintext_url` | 1 | 0 | 1 |
| `hardcoded_secret` | 52 | 1 | 51 |
| `dynamic_version` | 0 | — | — |
| `webview_file` | 0 | — | — |

## B.3 真违规明细（生产代码）

### B.3.1 直接 `Log.*`（14 条命中，按文件分类）

| 文件 | 判定 |
|---|---|
| `VaultixApplication.kt:112-114` | ✅ **合规**：这是 `VaultixLog.install(enabled = BuildConfig.DEBUG)` 的门面实现 |
| `AutofillLogger.kt:28,38` | ⚠️ **半合规**：`if (BuildConfig.DEBUG) Log.d(...)`，release 不输出，但未走统一门面 |
| `QuickUnlockController.kt:867` | 🔴 `Log.d(TAG, "清除待重装标记失败：...")` |
| `UnlockViewModel.kt:926,958,960,962` | 🔴 `Log.d(TAG, "reseal $vaultId → ...")` 等 4 处 |
| `SettingsComponents.kt:460` | 🔴 `Log.w(TAG, ...)`，位于 Compose 组合阶段 |
| `VaultixLog.kt:85-87` | ✅ KDoc 里的示例代码，非真实调用 |

⇒ **6 处真绕过**，release 仍会输出 DEBUG/WARN，违反规范 §15。

### B.3.2 生产 `!!`（3 处）

```
app/.../passkey/PasskeyCreateActivity.kt:509  itemRepository.updateFido2Credentials(vaultId, selectedLoginId!!, merged)
app/.../passkey/PasskeyGetActivity.kt:495    WebAuthn.signAssertionHash(authData, clientDataHash!!, key)
data/repository/.../kdbx/KdbxSyncOrchestrator.kt:198  val source = fileSourceFactory(origin)!!
```

### B.3.3 裸 `catch`（21 条生产命中 = 18 处真实代码 + 3 处 KDoc 提及）

```
app/.../autofill/match/UriMatcher.kt:34,56
app/.../ui/qr/QrScannerContent.kt:225
core/common/.../Totp.kt:199,219,259,485
core/common/.../logging/VaultixLog.kt:143
core/crypto/.../VaultixCrypto.kt:197
data/bitwarden/.../BitwardenAuthRepository.kt:318
data/kdbx/.../Kdbx.kt:280,478,488,801
data/kdbx/.../KdbxTransformKeyCache.kt:298
data/repository/.../BitwardenSyncOrchestrator.kt:204
data/repository/.../KdbxItemRepository.kt:347
data/repository/.../kdbx/CachedKdbxFileSource.kt:253
```

KDoc 提及（**不计**）：`FoldersViewModel.kt:154`、`Totp.kt:57,295`（含「不用 catch(Exception)：detekt 的
TooGenericExceptionCaught」的说明）。

> 分级建议：UI / 业务层收窄到具体异常；**加密、IO、第三方 KDBX 解析等边界层**保留
> `catch (Exception)` 但需显式登记（这些地方「任何解析失败都转用户可读错误」是有意为之）。

### B.3.4 阻塞式退避（3 处）

```
app/.../remote/onedrive/OneDriveGraphClient.kt:429  Thread.sleep(CHUNK_RETRY_DELAY_MS)
                                            :604  Thread.sleep(CHUNK_RETRY_DELAY_MS)
                                            :794  if (noProgress > 0) Thread.sleep(...)
```

**准确定性**：`upload()` 是 `suspend fun ... = withContext(Dispatchers.IO)`，
`chunkedUpload()` 是 `private suspend fun`，`putChunkWithRetry` / `resumeStep` 是普通 `fun`
但都在 IO 调用链内 ⇒ **不阻塞主线程、不会 ANR**；问题是占住一个 IO 线程 500ms 且不可取消。
`CHUNK_RETRY_DELAY_MS = 500L`（`:1130`）。

### B.3.5 超长行（1 处）

```
app/.../ui/common/FullScreenDialogShell.kt:119  169 字符
  * [developer.android.com/design/ui/mobile/guides/layout-and-content/edge-to-edge](https://developer....
```
KDoc 里的 Markdown 链接，建议改引用式链接或折行。

## B.4 长度类：两套口径对照

| 阈值 | `long_method` | `long_class` |
|---|---:|---:|
| 团队规范 80 / 800（脚本口径，含注释） | 131（生产） | 9（生产） |
| 项目 detekt 150 / 1200 | **29**（脚本口径定位线索） | **6** |

**脚本口径下 >150 行的前 10**（仅定位用）：

| 位置 | 行数 |
|---|---:|
| `domain/.../VaultRepository.kt:382` | 382（接口 + 大段 KDoc，非单方法体） |
| `app/.../ui/settings/SettingsScreen.kt:113` | 249 |
| `app/.../ui/addvault/AddKdbxScreen.kt:157` | 245 |
| `app/.../ui/items/ItemsScreen.kt:492` | 242 |
| `app/.../ui/settings/VaultManagementScreen.kt:112` | 219 |
| `app/.../ui/vaultlist/VaultListScreen.kt:200` | 211 |
| `app/.../ui/cardwallet/CardWalletScreen.kt:81` | 204 |
| `app/.../ui/common/ItemFormDialog.kt:124` | 201 |
| `data/kdbx/.../KdbxToolFields.kt:219` | 199 |
| `data/kdbx/.../Kdbx.kt:1175` | 195 |

**脚本口径下 >1200 行的类**：

| 位置 | 行数 | 说明 |
|---|---:|---|
| `app/.../autofill/match/PublicSuffixData.kt:12` | 10,344 | **PSL 自动生成**，头部已 `@file:Suppress("LargeClass","MayBeConstant")`，正当豁免 |
| `app/.../ui/items/ItemsScreen.kt:196` | 2,149 | 真超标 |
| `app/.../ui/settings/QuickUnlockController.kt:358` | 1,314 | 真超标 |
| `app/.../remote/onedrive/OneDriveGraphClient.kt:68` | 1,234 | 真超标 |
| `data/repository/.../VaultRepositoryImpl.kt:69` | 1,226 | 真超标 |
| `data/kdbx/.../Kdbx.kt:162` | 1,208 | 边界，含大量 KDoc |

## B.5 误报裁剪（这些**不是**整改项）

| 类别 | 误报原因 |
|---|---|
| `comment_no_space` 159 | 规则 `re.search(r"//\S")` 命中了 `file://`、`content://`、`androidapp://`、`msauth://`、`otpauth://`、`otpauth://` 等 **scheme 里的 `//`**，且只排除了含 `http` 的行。逐条看明细，绝大多数是 KDoc 中带 URL 的说明行 |
| `hardcoded_secret` 52 | 51 条在 `*/src/test/`，是 RFC 6238 TOTP 测试向量（`JBSWY3DPEHPK3PXP`、`GEZDGNBVGY3TQOJQ...`）；1 条生产命中 `KdbxPasskeyCodec.kt:68` 是 `FIELD_PRIVATE_KEY = "KPEX_PASSKEY_PRIVATE_KEY_PEM"`，**KDBX 字段名常量**，不是密钥 |
| `system_out` 2 | `Kdbx.kt:14`、`KdbxSessionFlow.kt:27` 都是 KDoc 里的**反面示例**：「一次 `println(session)` 就可能把明文写进日志」 |
| `run_blocking` 95 | 82 条测试；生产 13 条中 10 条是注释 / import / 历史说明（如 `VaultRepositoryImpl.kt:524` 记「已由 `fun + runBlocking` 改为 `suspend fun`」）。真实调用只有 2 处：`BitwardenAuthRepository.kt:343`、`BitwardenTokenRefresher.kt:44`，均为 OkHttp Authenticator 的**同步回调框架边界**，已 `runBlocking(Dispatchers.IO)` + `@Suppress("InjectDispatcher")` + 写明理由 |
| `plaintext_url` 1 | `PasskeyOriginPolicyTest.kt:108` 注释：「策略层只做语法校验，不区分 scheme；`http://` 是否可作 origin 由 RP 决定」 |
| `bang_bang` 测试 222 | 断言中 `taken!!.username` / `parse(...)!!` 属常见测试写法，不视为生产风险 |
| `thread_sleep` 测试 4 | 2 条是注释（明确写「别把 Thread.sleep 写回来」），2 条是 `KdbxTransformKeyCacheTest` 的并发时序构造 |

## B.6 适用性裁剪：不适用条款清单

`ai-code-standards` 为 Java / 传统 View 时代规范，以下条款在本项目（Kotlin + Compose + 多模块）不适用，
应登记例外而非整改：

| 规范条款 | 项目实际 |
|---|---|
| 成员变量 `m` 前缀、布尔 `is` 前缀 | Kotlin 属性 / `State<T>` / `derivedStateOf`，无 Java 字段 |
| ViewBinding / `findViewById` / RecyclerView / ConstraintLayout | Compose `LazyColumn` / `Modifier` |
| Glide 图片加载 | Coil 2.x（全局 `ImageLoader` 已配） |
| Gson / FastJson | kotlinx-serialization |
| AsyncTask / Handler 静态内部类 / LiveData | 协程 + `StateFlow` |
| `ActivityTestRule` + Espresso UI 测试 | 以 JVM 单测为主（CI 双 job 之一为纯逻辑沙箱测试） |
| Git Flow + PR Review | 单人项目直推 `main`，15 个静态门禁 + CI 双 job 替代 |
| **注释只写 what、禁写 why** | 项目 `.ai` 与源码 KDoc 刻意保存 why（#239 崩溃根因、#236 死循环、#247 缓存时序），是核心资产；新代码按规范写 what，历史 why 保留并登记例外 |
| 覆盖率 ≥80% | Kover 未接 CI，无法证明（主报告 §4 · S5 给出建立基线的路径） |
| 网络统一拦截器 / RESTful | Graph 调用是**手写 OkHttp**（对齐 Bitwarden），Retrofit 只服务 `data:bitwarden` |
| OkHttp `MODERN_TLS` + `CertificatePinner` | 自建服务器 / 用户自托管场景无法预置证书指纹；已配三处超时 + `pingInterval` |

## B.7 门禁现状

| 门禁 | 状态 |
|---|---|
| `.ai/tools/check_issues_index.py` | 本轮实测 `exit=0`（索引 = 题注 = 正文，3 条已知跨篇重号为警告） |
| `config/detekt/detekt.yml` | CI 口径 `./gradlew detekt --no-configuration-cache` 绿 |
| 15 个 `.ai/tools/check_*.py` + `check-encoding.py` | 上一轮 `PASS=15 FAIL=0` |
| `.github/workflows/ci-debug.yml` | 双 job：`Sandbox unit tests` / `Lint, test diagnostics, and debug build`；含 lint、detekt、编码、单测、assembleFullDebug |
| 缺失 | 依赖解析结果断言、直接 `Log` 白名单、生产 `!!` 计数、Kover 覆盖率 |
