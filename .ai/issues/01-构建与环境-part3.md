# 01-构建与环境 · 第 3 篇（#101 ~ #104）

> 主篇索引：[`01-构建与环境.md`](./01-构建与环境.md)（含计数规则与重号警告）
> 本篇 4 章 · 13.9KB · 覆盖 #101 ~ #104

## 101. 🔴 `import` 的**包路径**写错：detekt 与自检工具**双双查不出**，只有 CI 编译能炸（2026-09-15，`e3f6f05` 已修）

**现象**：CI run `34929618286`（commit `0f83efe`）在 `Build Debug APK` 步骤失败：

```
e: .../ui/passkeys/PasskeysScreen.kt:47:35  Unresolved reference 'WindowInsets'.
e: .../ui/passkeys/PasskeysScreen.kt:137:73 Unresolved reference 'WindowInsets'.
```

**根因**：`WindowInsets` 属于 `androidx.compose.foundation.layout`，
而该文件误写成了 `androidx.compose.material3.WindowInsets`（**该包下并不存在此符号**）。

**为什么两个本地工具都没拦住 —— 这是本次真正的收获**：

| 工具 | 它查什么 | 为什么漏掉 |
|---|---|---|
| detekt | 静态规则集（复杂度/行长/命名/未用参数…） | **不做符号解析、不做类型检查**，"符号名写对、包名写错"它对不上号 |
| `check_signature_types.py` | 函数**签名里**的类型名是否存在 | 只取名字去查表，**从不读 `import` 语句本身** |

⇒ **`import` 行是一条无人看守的缝**。而 Compose 的符号恰恰横跨
`foundation.layout` / `material3` / `ui` / `ui.graphics` 多个包、大量同名，
是最容易"凭印象写"的地方。

**取证要点（可复用）**：沙箱**下不到 CI 的 raw log**（302 跳 blob 存储、取不到）。
用 **check-run annotations** API 直接拿编译错误行：

```bash
gh api repos/Chaniug/Vaultix/check-runs/<job_id>/annotations \
  --jq '.[] | .path, .start_line, .message'
```

两条错误一次拿全，比翻日志快得多。

**修复**：`material3.WindowInsets` → `foundation.layout.WindowInsets`，
位置放在 `...layout.Spacer` 之后、`...layout.fillMaxSize` 之前，
与 `TotpCodesScreen.kt` / `ItemsScreen.kt` / `SettingsScreen.kt` 的导入序一致。

**验证**：run `34930096397` → `conclusion: success`，
`Build Debug APK (build gate)` 与 `Publish Debug APK to preview Release` 均 success，
APK 已产出：`preview` Release / tag 带 commit 短 sha（`dev-e3f6f056…`）。

### 101.1 新增守护工具：`.ai/tools/check_import_packages.py`

为堵上这条缝，新增了 import 包路径校验器。**判据用仓库自己当基准，不维护 Compose 符号表**：

- **规则 A · 独占包一致性**：某简单名若在全仓库**只从一个包**导入过（且样本 ≥2），
  则该符号的包已形成约定；任何一处从别的包导入即报错。
  （`WindowInsets` 全仓库 8 处、全部来自 `foundation.layout` ⇒ 独占 ⇒ 可判定）
- **规则 B · 陷阱表**：少数硬知识符号（`WindowInsets` / `Modifier` / `LazyColumn`…）直接查表。

**⚠️ 设计过程中的一次自我纠正（重要）**：规则 A 的**首版用的是"多数包"**，
结果产生 2 处**误报**，且都是**合法**用法：

| 误报名 | 实际情况 |
|---|---|
| `lerp` | `ui.graphics.lerp`（颜色插值）与 `ui.unit.lerp`（`Dp` 插值）**是两个真函数**；`ExpressiveTopBar.kt` 同时导入二者，各自都用到了 |
| `LocalLifecycleOwner` | `compose.ui.platform`（旧）与 `lifecycle.compose`（新）**都存在**，属 API 迁移期共存 |

⇒ 改为**"独占包"判据**：只要该名字在 ≥2 个包里出现过，**一律跳过不查**。
**宁可漏报，不可误报** —— 误报会让人开始怀疑工具，最终弃用。

**回归验证（证明它真能拦住原 bug）**：把 `material3.WindowInsets` 重新注入副本文件后：

```
L47: `WindowInsets` 导入自 `androidx.compose.material3`，但本仓库约定为 `androidx.compose.foundation.layout`
```

**报错行号 `L47` 与 CI 报的 `47:35` 完全吻合。** 若当时有它，可省掉整整一轮 CI。

退出码：`0` 干净 / `1` 有可疑项（可直接接进 CI 或 pre-commit）。

### 101.2 同一条缝的第二次发作：实验性 API 的 `@OptIn`

`check_import_packages.py` 上线同一天，**又踩了同一类坑**：引入 `BasicAlertDialog`
重做对话框外壳（设置页「当前密码库 / 快速解锁」+「添加密码库」三处），
三个调用点**全漏了 `@OptIn(ExperimentalMaterial3Api::class)`**。
CI `Build Debug APK` 直接失败，**后续步骤（含 APK 发布）全被 skip**：

```
e: .../ui/settings/SettingsScreen.kt:979:5  This material API is experimental and is likely to change or to be removed in the future.
e: .../ui/settings/SettingsScreen.kt:517:5  This material API is experimental and is likely to change or to be removed in the future.
e: .../ui/common/AddVaultTypeDialog.kt:85:5 This material API is experimental and is likely to change or to be removed in the future.
```

**为什么又是本地工具的死角**：三个工具全都不看注解 ——

| 工具 | 它查什么 | 为什么漏掉 |
|---|---|---|
| detekt | 静态规则集 | 不做符号解析，**不判断某个符号是否带 `@Experimental*`** |
| `check_signature_types.py` | 签名里的类型名 | 只看名字，不看注解 |
| `check_import_packages.py` | import 的包路径 | `BasicAlertDialog` 的包路径**完全正确**（确实在 `material3`） |

而且本工程**没有任何工程级 opt-in 编译参数**——`app/build.gradle.kts` 的
`kotlin { compilerOptions }` 里只有 `jvmTarget`，所以**每个调用点都得手写**，
不存在"漏了也能过"的兜底。**只能靠真实编译器。**

**取证**同 #101：annotations API，一次拿全三行，无需日志。

**修复**：三处补 `@OptIn(ExperimentalMaterial3Api::class)`（commit `fc1fa6c`），
CI run `34938509113` → `conclusion: success`。

### 101.3 新增守护工具：`.ai/tools/check_experimental_optin.py`

**判据必须模拟「注解作用域」，不能是文本搜索**。Kotlin 的 `@OptIn` 作用于
**被它注解的那个函数**；而 `@Composable` 函数会被**内联**进调用方，
所以只要**调用链上有一层**带了 `@OptIn`，实际就是合法的
（典型结构：顶层 `Screen` 带 opt-in，内部子 composable 直接用）。

于是判据是「迭代到不动点」：

1. **自证**：函数自己的注解块覆盖了它用到的全部实验性符号 ⇒ 合法；
2. **继承**：有另一个**已合法**的函数**调用了它** ⇒ 合法（内联向下传递）。

⚠️ 第 2 条是**反向边**——合法性从「调用方」流向「被调方」。
实现时**第一次把方向写反了**（去查"我调用了谁"），自测立刻抓出来了。

**⚠️ 头一版更糟，直接在干净仓库上报了 17 处误报**：当时用"`fun` 之前 4000 字符内
出现过 `@OptIn` 就算过"这种懒判据，结果 `TopAppBar` / `ExposedDropdownMenuBox` /
`CircularWavyProgressIndicator` 这些**内联继承的合法用法**全被误判，
连它本该抓的那 3 处都淹没了。**宁可漏报，不可误报**（#101 的教训）。

**已知漏报（接受了）**：跨文件的"调用了一个已 `@OptIn` 的 composable"
判不了（需要真解析，超出单文件正则的能力）。实测本仓库无此结构；
漏报代价只是"CI 再炸一次"，误报代价是"工具被弃用"。

**回归验证（证明它真能拦住原 bug）**：删掉三处 `@OptIn` 后扫描，精确报出且**零噪声**：

```
SettingsScreen.kt:516   `ActiveVaultDialog()` 里用了 `BasicAlertDialog`…
SettingsScreen.kt:978   `QuickUnlockManageDialog()` 里用了 `BasicAlertDialog`…
AddVaultTypeDialog.kt:86 `AddVaultTypeDialog()` 里用了 `BasicAlertDialog`…
```

与 CI 的三处**一一对应**；还原文件后立刻回到 `[OK]`。内置 `--selftest` 五项
（漏注解 / 已注解 / KDoc 里的 `@OptIn` 不算数 / 内联继承 / 跨文件漏报）全绿。

**贯穿 #101 的可复用结论**：detekt + 三个自检脚本覆盖的都是**语法层**，
而**符号解析层**（包是否存在、注解是否齐、API 是否实验性）**只有真实编译器在看**。
每被炸一次，就该问一句：「这条缝能不能用 20 行 Python 补上？」
能补就补，补不了就**明确写下漏报边界**，别假装覆盖了。

退出码：`0` 干净 / `1` 有可疑项。

## 102. ⚠️ 交付前「资产存在」≠「能下下来」（2026-09-15 我自己的误判）

差点把一件没验证的事当成已验证的交付给用户。

**经过**：修好 `@OptIn` 后 CI success、APK 发布。我用 `gh release view preview`
查到 `app-full-debug.apk` / 32193189 bytes，就**在给用户的结论里写了「APK 可下载」**。
随后真去 `curl` 下载 —— **`http=000`、`size=0`，17 分钟后超时失败**。

**根因**：`gh release view` 走的是 **API**（`api.github.com`，hosts 已修好，通）；
而真实下载会 **302 到 `release-assets.githubusercontent.com`**，
该域名在沙箱被解析到 **`198.18.0.22`（保留网段，代理占位）** ⇒ 连接 EOF。
**两个域名，两条链路，一好一坏。**

**教训（重要，跨领域适用）**：

- **「元数据正确」永远不能替代「端到端跑通一次」。**
  大小、tag、checksum 都是从 API 读的，**和能不能下载是两件事**。
- 交付物但凡要用户去取，**交付前必须真取一次**。
  没取成功就别写「可下载」——要么写清楚未验证，要么先修通。
- 排查这类"域名被劫持"的**必要动作**：`getent hosts <域名>`。
  看到 **`198.18.0.x` 就该警觉** —— 这是 RFC 2544 基准测试保留段，
  真实公网服务不可能用，出现即代表被代理/DNS 接管。
  （GitHub 相关域名本沙箱已踩到三个：`github.com`、`api.github.com`、`release-assets`）

**修复**：`/etc/hosts` 写入 `release-assets.githubusercontent.com` 与
`objects.githubusercontent.com` → `185.199.108~111.133`
（真实 IP 用阿里 DoH 查得）。详见 `.ai/conventions/8.7-环境.md`。

---

## 103. 🔴 手改大文件时「整块复制」→ CI 报 `Conflicting overloads`，七道本地门禁全绿（2026-09-16）

**现象**：诉求 C（一个指纹解锁所有库）推送 `a164829` 后，CI 在 **`Build Debug APK`** 红：

```
e: domain/src/main/java/io/vaultix/domain/VaultRepository.kt:390:13 Conflicting overloads
e: domain/src/main/java/io/vaultix/domain/VaultRepository.kt:335:13 Conflicting overloads
```

**根因**：我手改这个 700 行 domain 文件时，**一整块 `KDoc + suspend fun enrollPinForVaults(...)`
声明被复制了一份**（第 341–394 行）。两份**逐字节相同**，Kotlin 重载只看类型 ⇒ 直接报冲突。

**为什么七道门禁一道都没拦住**（这是本条最重要的部分）：

| 门禁 | 为什么漏 |
|---|---|
| detekt（含两级探针） | **不做类型检查** —— 重复声明不是它任何规则的输入 |
| `check_signature_types` | 只看**类型名能否解析**；两份签名**都合法** |
| `check_import_packages` | 只看 import 的包路径 |
| `check_experimental_optin` | 只看 `@OptIn` 作用域 |
| `check-encoding` | 只看字节 / 编码 |
| `check_compile_smells`（当时） | 只查图标导入 / 顶层常量顺序 / `R.string` 悬空引用 |
| 自测用例 | 当时还不存在 |

⇒ 这是与 #101（import 包路径）同一类的**「本地无编译器」盲区**。

**为什么会发生「整块复制」**：`VaultRepositoryImpl` 有函数数顶格约束
（detekt `TooManyFunctions`，**40 个**），逼我"加一个必须删一个"，
于是频繁在大文件里做**块级搬运** ⇒ **大文件 + 块级搬运 = 复制粘贴事故高发区。**

**解法（两条）**：

1. **当场规矩**：往大文件里贴块之后，**立刻 `grep -c` 数一遍该声明出现几次**。
2. **新增第 4 类本地门禁** `check_duplicate_declarations`（接在 `check_compile_smells.py` 内，
   与图标导入 / 顶层常量顺序 / `R.string` 并列）。判据：类成员级（4 空格缩进）的 `fun`，
   **同名 + 同接收者 + 同参数类型** ⇒ 报（Kotlin 重载只看类型，不看参数名）。

⚠️ 刻意**只查 `fun`、不查 `val`/`var`**：属性重复在纯文本上无法与
「局部变量 / 命名参数 / 构造实参」区分 —— 实测 **264 处全误报**。
而真实事故是函数整块被复制，查 `fun` 足够。

---

## 104. 🔴 写启发式探针必须配正反用例 —— 新探针连坏两版，每版都「看起来很对」

**背景**：#103 的补救工具 `check_duplicate_declarations` 的正则**连坏两版**：

| 版本 | 结果 | 怎么坏的 |
|---|---|---|
| 第 1 版 | **264 处误报** | `^[ \t]+...(fun\|val\|var)` 把局部变量、命名参数、构造实参全抓了 |
| 第 2 版 | **5 处误报** | 收紧成类成员级 `fun`，但 `fun\s+(\w+)` 对**扩展函数**抓到的是**接收者类型**：`fun JsonElement?.asObject()` ⇒ 名字被认成 `JsonElement`，于是同一接收者上的多个扩展函数归一成同一个 key |
| 第 3 版 | ✅ | 正则跳过可选接收者，并**把接收者纳入 key** |

**第 2 版误报的定位方法（值得复用）**：

> 那 5 处全落在**本轮根本没改动**、CI 也从没报过的两个文件里
> （`FillAssistJsonParser.kt` 的 `JsonElement`、`CredentialProviderIntentUtils.kt` 的 `Intent`）。
> ⇒ **「探针报了一个我不可能引入的问题」= 探针坏了，不是源码坏了。**
> 先去核对报错文件的真实签名，**别急着改源码**。

**第 3 版的残留坑（靠用例才抓到）**：若只跳过接收者而**不把接收者放进 key**，
则 `fun Intent.foo(x: Int)` 与 `fun Bundle.foo(x: Int)` 会被误判为重复 ——
它们在 Kotlin 里是**两个不同签名**。这个坑光看真实仓库看不出来（库里没这种写法）。

**解法**：永久回归集 `.ai/tools/tests/selftest_duplicate_declarations.py`，
5 个用例：真重复 2 个**必须报**；合法重载 / 同接收者不同扩展函数 / 不同接收者 3 个**不能报**。
**改这个正则前先跑它。**

**一般化教训**：**启发式探针的「OK」只有配上「必须报的负例」才有意义。**
只测正例会写出恒返回"无问题"的探针，那比没有探针更危险 —— 它给的是假安心。

---

