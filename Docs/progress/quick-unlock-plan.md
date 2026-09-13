# 专项计划 · 升级后快速解锁失效（#88）

> 建档：2026-09-13（第五十二轮）· 坑的原始记录：`.ai/issues/04-锁与解锁.md` **#88**
> 配套：性能专项 [`perf-plan.md`](./perf-plan.md)（两者都需要 debug 包 + ADB，建议同一会话连着做）
> 本文档是**执行计划**：每条都给出「验证步骤 → 命令 → 判据 → 修法方向」，可照做。

---

## 1. 现象与已排除项（**都是实测，不是推断**）

**现象**（用户报，2026-09-13）：
> CI release `0.2.0` 覆盖本地 debug 包后，「生物验证好像失效了」；
> 重新登录 → 删掉快速解锁 → 重新开启，才能继续指纹解锁。

**已排除的三项**：

| 候选原因 | 实测证据 | 结论 |
|---|---|---|
| 卸载重装 ⇒ 数据被清 | `firstInstallTime=2026-09-12 13:46:46`、`lastUpdateTime=2026-09-13 21:39:03` | ❌ 排除了，是**真·原地更新** |
| 签名不一致 ⇒ 被迫重装 | CI release APK 与手机上已安装 APK，`apksigner verify --print-certs` 的 **SHA-256 完全相同**（`7e6c8c07…`） | ❌ 排除了 |
| release 包（R8）把功能压坏 | 重新启用后**指纹立刻可用** | ❌ 排除了，代码路径正常 |

⇒ **剩下唯一解释：那对「包裹密钥 / KEK」在原地更新过程中变成了不可解密。**
**影响面：每一个升级的用户都会遇到**（不阻断发布 —— 可两下操作自愈、数据不丢）。

## 2. 关键结构（先看清才能查）

两个**独立**的 Keystore 密钥，别混：

| 密钥 | 别名常量所在 | 保护方式 | 用途 |
|---|---|---|---|
| **凭据库密钥** | `SecureCredentialStore.KEY_ALIAS` | Keystore AES-256-GCM，**不要求用户认证** | 加密 `SharedPreferences` 里的**值**（Bitwarden token、`local_unlock_key::<vaultId>` 包裹串） |
| **KEK（快速解锁）** | `LocalUnlockKeyStore.KEY_ALIAS` | `setUserAuthenticationRequired(true)` + 逐次认证 | 包裹账号对称密钥（64B fullKey） |

⇒ **排查的第一个分叉点**：如果**只有快速解锁**坏（Bitwarden token 还在、不用重新登 Bitwarden）⇒ 问题在 **KEK**；
如果**连 token 也读不出来**（要重新登 Bitwarden）⇒ 问题在**凭据库密钥**（影响面更大）。
**⚠️ 用户原话「我重新登录」有歧义 —— 必须问清是"输入主密码解锁"还是"重新登录 Bitwarden 账号"。这一条直接决定往哪边查。**

## 3. 复现（确定性，当前就能做）

当前状态正好是实验台：**release 0.2.0 已装、快速解锁已重新启用且可用**。

```bash
# 1) 记录"好"的状态（先取证，别急着装）
adb shell dumpsys package io.vaultix.vaultix | grep -E "firstInstallTime|lastUpdateTime"
#    记下当时的 kekStatus 与 keyAvailable（见 §4 埋点）

# 2) 用本地 debug 包原地覆盖（**同一把密钥 ⇒ 数据不丢**）
BT=/c/AndroidSDK/build-tools/36.0.0; ADB=/c/AndroidSDK/platform-tools/adb.exe
cp app/build/outputs/apk/full/debug/app-full-debug.apk raw.apk
"$BT/zipalign.exe" -p -f 4 raw.apk aligned.apk
"$BT/apksigner.bat" sign --ks D:/vaultix-release.jks --ks-key-alias vaultix \
  --ks-pass pass:<store> --key-pass pass:<store> --out signed.apk aligned.apk
$ADB install -r -d signed.apk

# 3) 立刻冷启动并抓日志（关键窗口）
$ADB shell am force-stop io.vaultix.vaultix
$ADB logcat -c
$ADB logcat -s VaultixUnlock:V LocalUnlockKeyStore:V AndroidRuntime:E > /tmp/unlock.log &
$ADB shell am start -n io.vaultix.vaultix/.MainActivity
```

**判据**：若快速解锁**再次失效** ⇒ **可稳定复现**，按 §5 对照定位；
若**没失效** ⇒ 说明触发条件另有其它（例如当时跨的是"一次性密钥签的 preview 包"），
需要在用户真机历史上回溯当时的安装来源（`dumpsys package` 的 `installerPackageName` 是 `com.microsoft.emmx`，说明是从 Edge 浏览器下载安装的 —— **这条线索要追**）。

## 4. 需要加的埋点（当前这条链路日志不足）

在 `LocalUnlockKeyStore` / `VaultRepositoryImpl.completeLocalUnlock` 加**常驻**日志（tag 建议 `VaultixUnlock`）：

| 打点位置 | 记录内容 | 为什么需要 |
|---|---|---|
| App 启动（或解锁页 init） | `kekStatus`（三态）+ `keyAvailable` | 判定"是探测判废了，还是解密真失败" |
| `localUnlockAvailable(vaultId)` 求值处 | `enabled`（偏好开关）+ `keyAvailable` + payload 是否存在 | 三者任一为假都会让指纹入口消失 |
| `completeLocalUnlock` 入口 | payload 是否拿到（`wrappedPayload != null`） | 区分"凭据库解不开"与"KEK 解不开" |
| `unwrap` 失败处 | **异常类型全链**（`AEADBadTagException` / `UnrecoverableKeyException` / `KeyPermanentlyInvalidatedException`） | 这三种对应三条完全不同的修法 |
| `clearBrokenLocalUnlock` 被调用时 | 明确一行"清了谁的注册、为什么" | 确认到底是谁清掉的（用户手动 / App 自动） |

## 5. 判据表（观测 → 根因 → 修法方向）

| 观测 | 根因 | 修法方向 |
|---|---|---|
| `kekStatus == MISSING`（别名不存在） | 更新后 Keystore 条目被清（OEM 行为？） | ① 让 `keyAvailable=false` 时给出**显式提示**而非静默隐藏入口；② 排查 OEM 是否在 update 时重建别名 |
| `kekStatus == INVALIDATED`（`KeyPermanentlyInvalidatedException`） | KEK 被永久失效 | 同上 + 确认触发条件（生物识别变更？更新？） |
| `cipher init`/`unwrap` 抛 `AEADBadTagException` | payload 与 KEK 不匹配（**被替换/不同源**） | 检查写入时机：是否在"旧 KEK 已失效 + 新 KEK 已建"的窗口里写了 payload |
| payload 读不出来（`wrappedPayload()==null`） | 凭据库密钥失效 ⇒ **影响面更大**（token 也会丢） | 与"用户是否要重新登 Bitwarden"交叉验证；查 `SecureCredentialStore` 的 KEY_ALIAS 生命周期 |
| 日志显示 `clearBrokenLocalUnlock` 被调用 | App 走了"不可恢复 ⇒ 清理注册"分支（**行为本身是对的**） | 保留行为，但**要在 UI 上告知用户**"快速解锁已失效，请重新启用"，别让用户自己发现 |

## 6. 无论根因如何，都该做的一处 UX 改进（低风险、收益确定）

现在的问题之一是**静默**：指纹入口消失后，用户只能自己摸索"删掉再开启"。

⇒ **在设置页与解锁页显式给出状态**：

- 偏好开关 = 开、但 `keyAvailable == false` ⇒ 显示一行：
  **「快速解锁不可用（密钥已被系统回收），请关闭后重新开启以恢复」** + 一个"重新启用"按钮；
- 解锁页同理给出一行提示，而不是只留主密码表单。

这条**不依赖**根因定位，可以先行落地；也是"用户不必再猜"的最小闭环。

## 7. 验收标准

1. debug ⇄ release **原地互相覆盖**后，快速解锁**仍然可用**（或至少：失效时 UI 明确告知 + 一键恢复）；
2. 冷启动日志能一眼看出 `kekStatus` / payload / 异常三者的状态（不依赖复现用户现场）；
3. 不破坏既有回归：真锁/查看锁两条路径、`LocalUnlockFailure` 的"不可恢复 ⇒ 清理"语义、
   以及 #85 的"探测宽、失败判定严"边界。

## 8. 协作与前提

- **必须用 debug 包**：release 包 `run-as` 被拒（`run-as: package not debuggable`），
  读应用数据（`shared_prefs` / `databases`）只能在 debug 包上做；两者同签一把密钥，可原地互装、数据不丢。
- **一次只改一处、改完立刻同指标复测**（避免多变量叠加导致无法归因）。
- 相关代码：`core/datastore/LocalUnlockKeyStore.kt`、`core/datastore/SecureCredentialStore.kt`、
  `core/datastore/VaultixPreferences.kt`（`localUnlockEnabled`）、
  `data/repository/VaultRepositoryImpl.kt`（`localUnlockAvailable` / `completeLocalUnlock` /
  `clearBrokenLocalUnlock`）、`data/repository/LocalUnlockFailure.kt`。
