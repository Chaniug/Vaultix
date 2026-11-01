# 04-锁与解锁 · 第 4 篇（#213 ~ #214）

> 主篇索引：[`04-锁与解锁.md`](./04-锁与解锁.md)（含计数规则与重号警告）
> 本篇 2 章 · 13.3KB · 覆盖 #213 ~ #214

## 213. 🔴 用户报「还是点不动」的真因：**两个负号相消**，开关被接成恒等映射（2026-10-30 二次回归）

### 现象

#212 修完命中框后，用户复测：**「好像还是无法点击这个按钮，按钮始终是打开状态的」**。
注意后半句 —— "始终是打开状态"不是"没反应"，是**状态永远不变**。这句话本身就是线索：
如果点击压根没落到控件上，用户通常说"点不动"；说"始终是打开"，说明他看到的是
**一个会回弹/不动的确定状态**。

### 真因：`checked` 与 `onCheckedChange` 的语义不同向

第一版把偏好写成**反义**（`true` = 仍需验证），UI 又在调用点取反了一次：

```kotlin
// 偏好：true = 仍需验证（与开关标题「保持不锁」相反）
SettingsSwitch(
    value = relaxScreenLock,                                   // true → 开关显示 ON
    onCheckedChange = { relax -> setDevRelaxScreenLock(!relax) }, // 又取反一次
)
```

而 Compose 的 `Switch` 内部是 `toggleable(value = checked)`，回调传进来的
**已经是"新值"**（`!checked`）。于是：

| 步 | 值 |
|---|---|
| 偏好 | `true` |
| 开关 `checked` | `true`（ON） |
| 用户点一下 → `onValueChange(!checked)` | 回调收到 `false` |
| 调用点再取反：`setDevRelaxScreenLock(!false)` | 写入 `true` |
| 回流 | `true` → 开关**仍是 ON** |

**`f(!x) = x`**：每次点击都把**当前值**写回去，是恒等映射。而且它是**双向**不动点 ——
初始为 `false` 时同样写回 `false`，开关同样不动。用户看到的就是"永远是打开状态"。

### 为什么上一轮没抓到

- 数据流单测全部通过：**写口确实写成功了**（写的就是那个旧值），只是写的值没变；
- 编译/detekt/单测都看不见：类型完全合法，`!` 是最普通的运算符；
- #212 的命中框问题**也是真的**（52×32dp 确实小于 48dp），修完它之后这个逻辑 bug 才浮出来，
  于是被误读成"上一轮的修复没生效"。

⇒ 这正是 #121 / #212 的同一族：**"点了没反应"至少有两层（命中框 / 状态机），修完第一层要重新复测，
不能假设第二层不存在。**

### 修法：把语义翻正，让"显示的值"和"写入的值"是同一个布尔

| 层 | 改动 |
|---|---|
| 偏好 | 语义改为**与键名同向**：`true` = 保持不锁（不验证），默认 `false`（= 与正式版一致） |
| 键名 | 换成 `dev_relax_screen_lock_v2` —— 旧键存的是反义，复用会让老机器上的 `true` 静默变成"不锁" |
| 锁管理 | `if (!devRelaxScreenLockEnabled()) handleTimeoutAction(vaultId)`；读失败默认 `false`（不放松 = 照常锁） |
| UI | `onCheckedChange = viewModel::setDevRelaxScreenLock` —— **不做任何取反**、不写 lambda |
| 文案 | `desc_normal` / `desc_relaxed` 两个名字按"当前行为"命名，不再用 on/off（on/off 指的是开关，容易再次读反） |

### 判据（三条，都能复用）

1. **布尔控件回调的参数是"新值"**：Compose 的 `Switch` / `Checkbox` / `toggleable` 传
   `!checked`。在调用点再取反，等于把状态机接成 `f(!x)=x` 的**恒等映射** ——
   症状是"点了永远不动"，而不是"点了报错"。
2. **一个布尔偏好的名字必须与真值同向**。`dev_relax_screen_lock = true` 却是"要验证"，
   就是这次双负号的土壤。取名时先问一句："`true` 时开关该是开还是关？"
3. **改语义时要换键，不要复用**。旧键可能已被旧语义写过；复用 = 让旧数据在新语义下被误读，
   而这类误读的方向往往正是"放松安全"（本次是 `true` 从"要验证"变成"不锁"）。

## 214. ✅ 「从不」档对齐 Bitwarden：**两层锁**（密钥层不锁 / 界面层验证指纹）+ 新增「系统锁屏时」档（2026-10-30）

### 现象与用户原话

> 「感觉这一块的逻辑可以直接参考 bitwarden 的，它的保护效果很不错，另外开启了从不锁定的设置，
> 屏幕熄屏或者清除后台后，bitwarden 好像还是会需要指纹解锁生物验证解锁。但是好像填充并不会受影响，
> 意思是开启了从不锁定，自动填充依然是生效的，只是进入 bitwarden 这个软件的时候需要验证指纹，其他的不变。
> 你仔细看一下相关的代码就知道我说的了」

用户描述的是一个**同时成立**的组合，而不是三选一：

| 场景 | 「从不」档下用户期望 | 上一版行为 | 判定 |
|---|---|---|---|
| 熄屏后回到 App | 要指纹 | 直接进去（`Never` 任何触发源都不锁） | ❌ |
| 清后台后重开 | 要指纹 | 信封**免交互**恢复，直接进去 | ❌ |
| 自动填充弹窗 | **不受影响** | 正常 | ✅ |
| 库里密钥 | **不要求重新输主密码** | 在内存里 | ✅ |

关键在**后两行**：如果按最直觉的修法「熄屏就锁库」，虽然第 1、2 行满足了，但第 3 行（填充要重新认证）
和第 4 行（密钥清零、下次要主密码）会被一起破坏 —— **用户明确说了"其他的不变"**。

### 真因：只有一层锁，而用户要的是两层

读代码确认了两处（用户说的"仔细看一下相关的代码"指的就是这里）：

1. `AutoRestoreTrigger`：`Never` + 密钥在内存 ⇒ 注册信封；`Never` + 已锁 + 有信封 ⇒
   **`restore()` 不要求生物识别**（信封用的是不需要认证的 Keystore 密钥）。所以"清后台"这条路
   是**完全静默**的 —— 进程被杀了也能一声不响地开回来。
2. `VaultixApplication` / `AutoLockController` 只调 `onAppCreated` / `onAppBackgrounded`，
   `Never` 分支对两者都直接 `return` ⇒ **没有任何东西去挡界面**。

⇒ 结论：缺的不是"更狠的锁"，而是**把「密钥可见性」和「界面可见性」拆成两个独立开关**。
只加一层（锁得更狠）必然违反"其他的不变"。

### 修法：两层锁（复用已有的 `viewLocked`，不新增锁类型）

第二层其实**项目里早就有** —— `VaultSessionManager.viewLockedIds`（"查看锁"，#61）：
锁住界面但**不动密钥**，`UnlockScreen` 会渲染成"只有一个指纹按钮"的 `ViewLockedContent`。
上一版没用它的原因很单纯：`viewLockedIds` 是**手动**的（用户长按"锁此库"），
没有任何地方在熄屏/恢复后自动给它加标记。于是本轮做的是"给已有机制补一个**自动**入口"：

| 层 | 状态位 | 熄屏/清后台后 | 填充链路 | 谁设置 |
|---|---|---|---|---|
| 密钥层 | `sessions`（已派生密钥） | **保持** | 照常用 | 只有真锁（超时/手动锁库）才清 |
| 界面层 | `viewLockedIdsState`（含 `autoViewLockedIdsState` 子集） | **置位** | 直通（看穿） | `AutoViewGate` |

`viewLocked` 加了 `automatic: Boolean = false` 参数，自动标记放在
`autoViewLockedIdsState` 这个**子集**里，目的是让两件事同时可判定：

- `isViewLocked(id)` —— 界面要不要挡（手动 + 自动都算）；
- `isAutoViewLocked(id)` / `anyManualViewLocked()` —— **这是不是"只是挡住界面"**。

自动标记是**可升级**的：同一库先被自动挡、再被手动锁，`viewLocked(automatic = false)`
会把 `autoViewLockedIdsState` 里的 id **减掉**（手动锁的语义更重，填充要认证）。
`lock()` / `lockAll()` / `clearViewLock()` / `clearAllViewLocks()` 两套标记**一并清**。

### `AutoViewGate`：只有两个触发点，且五个前置条件缺一不可

新增 `security/AutoViewGate.kt`，**只**在这两个时刻被调用：

1. `VaultLockManagerImpl.onScreenOff()`（`ACTION_SCREEN_OFF`）；
2. `AutoRestoreTrigger` 信封恢复**成功且确实开了库**（`report.opened > 0`）之后 ——
   对应"清后台再打开"。注释写在调用点：**恢复 ≠ 免验证**。

⚠️ **普通切后台不触发**。这是刻意的：复制密码→切去粘贴是最高频的操作路径，
如果在 `onAppBackgrounded` 就挡界面，`Never` 会退化成 `Immediately`（用户要的是"其他的不变"）。

五个前置条件（`gateUnlockedVaults()` 依次判，任一不满足就**整体跳过**）：

| 条件 | 不满足时 |
|---|---|
| 有已解锁的库 | 无事可做 |
| 产品开关 `autoViewGateOnNever`（默认 `true`） | 用户主动关掉 |
| 该库档位 == `Never` | 其他档位本来就会真锁，不需要门禁 |
| 指纹门锁可用（`fingerprintLockAvailable`） | **跳过**，否则退化成"每次回来输主密码" |
| 开发者「保持不锁」关 | 开发便利优先 |

⚠️ **指纹可用性这一条是硬要求，不是优化**：没有指纹时"门禁"= 每次回来输主密码，
比不做门禁更糟，而且会被当成 bug 报上来。

⚠️ 这里的读失败全部 **fail-open**（`getOrDefault(false)`），与 `devRelaxScreenLockEnabled()`
的 fail-closed **方向相反**，且是故意的：这一层决定的是「**要不要挡界面**」（可用性问题），
不是「要不要清密钥」（安全问题）。读不到偏好时挡住用户 = 把人关在门外，代价远大于放行。

### 填充为什么不受影响：看穿自动标记

`AutofillActivity` 原本的 `hasViewLockedVault()` 改名为 `hasManualViewLockedVault()`：

```kotlin
if (!sessionRepository.anyManualViewLocked()) return false      // 没有任何手动锁 → 直接放行
val unlocked = vaultRepository.observeUnlockedVaultIds().first()           // 取已解锁库
return unlocked.any { sessionRepository.isViewLocked(it) && !sessionRepository.isAutoViewLocked(it) }
```

即：**手动锁仍然要认证**（安全不能被门禁偷偷削掉），**自动门禁一律看穿**（用户："填充并不会受影响"）。
两个方向都有单测钉住（含"手动锁覆盖自动标记"的那条）。

### 顺手补齐 Bitwarden 的档位：`VaultTimeout.OnSystemLock`

用户说"直接参考 Bitwarden"，而 Bitwarden 2025.11.0 的锁定档位里有 **`On system lock`**。
本轮把它补上（`STORAGE_ON_SYSTEM_LOCK = -3`）：

```kotlin
override fun onScreenOff() {
    onAppBackgrounded()                                  // 原有：分钟档计时
    scope.launch { autoViewGate.gateUnlockedVaults() }    // 新增：从不档界面门禁
    scheduleSystemLockCheck()                            // 新增：系统锁屏档
}
```

- 判定用 **`KeyguardManager.isDeviceLocked()`（严格）**，不是 `isKeyguardLocked`：
  后者在"只滑动解锁"的设备上熄屏即为 `true`，会让这档变成"熄屏即锁"，与文案不符。
- 熄屏瞬间系统**还没**把设备标记为 locked ⇒ 300ms 后**复查一次**；`screenOffToken`
  在 `onScreenOff` / `onScreenOn` 都会自增，用于取消过期复查（亮屏了就不用再锁）。
- 该档在 `CheckTimeoutReason` 里只认 `SystemLocked`：进程创建 (`AppCreated`)、
  切后台、换用户都**不锁**（否则它就跟 `Immediately` 没区别了）。有专门的回归用例。

⚠️ **`-3` 与"负数 = 旧键的 Never"冲突**：`fromLegacyMinutes()` 对 `-3` **仍然返回 `Never`**
（旧版根本没这个档，读出 `-3` 只能是脏数据/被误写），`assertNotEquals` 钉死。
新键 `fromStorageValue` 才认 `-3`。

### 判据（四条）

1. **"锁"至少有两种，先问锁哪一层**。「密钥在不在内存」和「界面要不要验证」是两个独立问题；
   把它们合成一个布尔，就必然要在"填充要重新认证"和"清后台能直接进"之间二选一。
2. **门禁类改动必须先问"退化路径长什么样"**。没有指纹时门禁会退化成主密码门，
   ⇒ 前置条件里必须有"能力可用"，否则安全改动会变成可用性事故。
3. **自动置位的锁态必须是可区分的子集**，不能和手动锁共用同一个标志。
   共用之后，下游（这里是自动填充）只能二选一：要么一律要认证（违反用户），
   要么一律放行（把手动锁废掉）—— 后者是安全倒退。
4. **读失败的默认方向由"代价更大的一侧"决定**：清密钥失败 = 放行（可用性灾难，fail-closed）；
   挡界面失败 = 挡住（把人关在门外，同样灾难）⇒ 同一项目里两种方向可以并存，
   但**每一个都必须写出理由**，否则下一个人会"统一"成一种。

### CI 结论（四个提交，红了两次）

| 提交 | 内容 | CI |
|---|---|---|
| `812218b` | 功能本体（15 文件，+631/−34） | ❌ `Build Debug APK` |
| `f7ac3d2` | `AutoRestoreTriggerTest` 补构造参数 + 2 条门禁接线用例 | ❌ `Run unit tests` |
| `788d805` | `AutoViewGate` 改从 `VaultTimeoutPreferences` 读产品开关 | ❌ 同上（看不见是哪个用例） |
| `d3485d0` | CI 加「失败用例转 annotation」 | ✅ **success** |

⚠️ **两次红都是"我以为它在那个类里"**：

1. 给 `AutoRestoreTrigger` 加构造参数，**忘了测试里的构造点**（`grep "XxxTrigger("` 就一条都没漏）；
2. `autoViewGateOnNever` 在 `VaultTimeoutPreferences`，却按 `preferences.`（`VaultixPreferences`）去读
   ⇒ 报错是 `Unresolved reference` + 三条 `Cannot infer type`，**看着像泛型问题**。

判据：**用某个属性前先 `grep -rn "名字"` 确认它在哪个类**。
本轮的教训不是"更小心"，而是**"两个开关听起来是一组"不构成任何证据** ——
`autoViewGateOnNever`（超时偏好）与 `devRelaxScreenLock`（主偏好）就分属两个类。

---

