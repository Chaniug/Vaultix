# 沙箱能做什么、不能做什么（2026-11-10 实测）

> 起因：用户问「本地可以做 CI 吗，沙箱环境除了不出 APK 还能提效什么」。
> 本文只写**跑过、有数字**的部分；未验证的一律标注「未测」。
>
> ⚠️ **本文上半部分已过时**。装好 Android SDK 之后，「不能出 APK / 不能 lint」
> 全部翻转，详见下面的 [§ 装了 Gradle + SDK 之后](#装了-gradle--sdk-之后2026-11-10-下半场)。
> 保留原文是因为：手工 kotlinc 那条路**仍然有用**（比 gradle 快一个数量级，见对比表）。

## 结论先放这（**旧**：只有 kotlinc 时）

| 能 | 不能 |
|---|---|
| **真编译 + 真跑单测**（core / domain / data 六个模块，共 **940 条用例**，约 3–4 分钟） | 出 APK / 跑 lint / detekt |
| 查类型错误、未解析引用、可见性、generics、mockk 用法 | 查 `ui/*` 的 Compose 签名（要 Compose 编译器插件，`app` 整包被它拖住） |
| 查 KSP 之外的「纯逻辑」；混编 `.java` 也纳入了 | 查 Android 资源 `R.*`、`BuildConfig`、Hilt 生成物 |
| 静态门禁全套（`.ai/tools/check_*.py`，17 个，秒级） | 真机行为、IME、渲染 |

**一句话**：把「写错一个字符 = 赔一轮 CI（4 分钟）」变成「20–60 秒本地就知道」。

## 怎么用

```bash
# 首次（沙箱 / 任何没跑过 gradle 的机器）—— 约 58MB，一次性
bash .ai/tools/typecheck_kotlin.sh --fetch-compiler

# 改了一个文件：连它依赖的闭包一起编，再跑同包测试
bash .ai/tools/typecheck_kotlin.sh data/repository/src/test/.../SafCreatePolicyTest.kt

# 改了一个模块：整模块按包分组编 + 跑
bash .ai/tools/typecheck_kotlin.sh --all data/kdbx
```

## 2026-11-10 之前它**在沙箱里根本跑不起来**

这是本轮最值得记的一条：工具写了半年，但一直没有在沙箱里真正生效过 ——
它从 `~/.gradle/caches` 找 kotlin 编译器，而**沙箱不会跑 gradle**，
所以每次都是「请先跑一次 gradle」然后退出。写错一个字仍然只能等 CI。

修好的四件事（都是"看起来在跑、其实什么都没查"那一类）：

| 症状 | 真因 |
|---|---|
| `NoClassDefFoundError: kotlin/jvm/internal/Intrinsics` | `-cp` 里没有 stdlib：回退到 `$LIBS` 的分支没写对（两轮才定位） |
| `unresolved reference: KotpassKdfBridge` | 工具只收 `.kt`，而那是 `.java`；且 kotlinc 只**读** Java 符号不产出字节码 ⇒ 运行期还要补一次 `javac` |
| 22 个测试文件整包编不过 | 批量模式用的是同一个 `.kt`-only 闭包 |
| 批量跑测试时 `ClassNotFoundException` | 同上，缺 `javac` 那一步 |

## 本轮怎么用它

- `KdbxReadPathTest` 新增的「0 字节 → EmptyFile」用例：**3.2 秒**出结果；
- `SafCreatePolicyTest` 四条边界：**40 秒**（含冷编译）；
- 全量六个模块回归：**940 条用例全绿**，作为「改完没碰坏别处」的基线；
- 期间 `KdbxOpenError` 加了分支 —— 这正是过去**只能靠 CI** 才发现的
  `when must be exhaustive`，现在本地就能看到。

## 已顺手接进 CI

`ci-debug.yml` 新增 `sandbox-tests` job（与 `android-ci` **并行**），用**同一份脚本**。
好处不是"多跑一遍"，而是：**本地绿 ⇒ CI 不会红在同一件事上**，
且测试错误 **3 分钟**可见，不必排在 lint → detekt → build 之后。

### ⚠️ 接进 CI 时踩的坑：workflow 里**注释的缩进也算缩进**

第一次推送后，整个 `ci-debug.yml` 被判成 "workflow file issue"，
`total_count: 0`（**一个 job 都没创建**），连带着 `ci-docs.yml` 也一起红 ——
后者这次根本没改过，所以别被"两个 workflow 同时失败"误导去查共同依赖。

真因：我把 job 上方的说明注释块写成了 **0 缩进**，而 `jobs:` 的直接子级必须 ≥ 2。
YAML 里块内注释与其后的键**同属一层**，缩进不一致即解析失败。
`PyYAML` 照样能 `safe_load` 成功（它容错），所以**本地解析通过 ≠ GitHub 收**。

排查手法（下次直接用）：

```bash
# 1) job 数为 0 ⇒ 文件级拒绝，不是构建失败
gh api repos/<owner>/<repo>/actions/runs/<id>/jobs --jq '.total_count'
# 2) 扫 jobs 段内所有缩进 < 2 的行（含注释）
python3 - <<'EOF'
lines=open('.github/workflows/ci-debug.yml',encoding='utf-8').read().split('\n')
started=False
for i,l in enumerate(lines,1):
    if l.startswith('jobs:'): started=True; continue
    if started and l.strip() and 0 < len(l)-len(l.lstrip()) < 2:
        print(i, repr(l[:70]))
EOF
```

判据：**`total_count == 0` 就是文件没被接受**，此时去翻步骤日志是白翻（没有步骤）。

还有一种更阴的中间态：**`total_count == 1`（只有老 job，新 job 没创建）而 run 结论是 failure**。
成因是推送瞬间 GitHub 抓到了改动前的快照 —— 文件本身已经是对的。
判别手法：`gh workflow run ci-debug.yml --ref main` 手动触发一次，
若这时 `total_count == 2` 且两个 job 都跑起来，就证明文件没问题，**别再去改它**。

### CI 实测（`ae9bf97d`，run `42673103197`）

```
✅ 8 组全绿 / ❌ 0 组有问题 / ⚠️ 0 组本地查不了    耗时 3m29s
```

| 包 | 测试文件 | 用例 |
|---|---:|---:|
| `io/vaultix/crypto` | 9 | 195 |
| `io/vaultix/common` | 12 | 144 |
| `io/vaultix/common/logging` | 1 | 7 |
| `io/vaultix/model` | 1 | 5 |
| `io/vaultix/domain` | 2 | 14 |
| `io/vaultix/data/kdbx` | 22 | 271 |
| `io/vaultix/data/repository` | 20 | 191 |
| `io/vaultix/data/repository/kdbx` | 12 | 113 |
| **合计** | **79** | **940** |

⇒ 与沙箱本地结果**逐项一致**（同一份脚本、同一份依赖清单），
「沙箱绿 ⇒ CI 绿」这条现在是被验证过的，不再是推测。

## 下一步可以做的（都没做，先记着）

1. **给 app 层加 Compose 编译器插件**：`app` 是单一 source set，
   没有插件就整包编不过（实测 6 分 21 秒后红在 `MainActivity` 的 `androidx.compose.*`）。
   要做就得把 Compose runtime / compiler 与 `androidx.activity` 也纳入依赖清单 —— 工作量不小，
   但收益最大：**UI 层签名错误**（比如上一轮的带接收者 lambda）从此不必等 CI。
2. `data:bitwarden` / `core:database` / `core:datastore`：缺 Room / DataStore 的生成物，
   要么加 KSP 预生成，要么手工桩 —— 先把它们从 CI 白名单里排除（现状）。

---

# 装了 Gradle + SDK 之后（2026-11-10 下半场）

用户问「可以装 gradle 吗」。结论：**gradle 本来就在（sdkman 9.3.0），但它跑不了这个项目**；
真正缺的是 SDK 和 JDK 21。补齐之后沙箱能做的事**和 CI 完全一致**。

## 实测（逐项跑过，不是推测）

| 命令 | 结果 | 耗时 |
|---|---|---|
| `./gradlew projects` | ✅ 13 个模块全部 configure，AGP 9.5.0 加载 | 1m 20s |
| `:core:crypto:compileDebugKotlin` + `:data:kdbx:compileDebugKotlin` | ✅（连带 KSP + Room + DataStore） | 1m 46s |
| **`:app:compileFullDebugKotlin`** | ✅ **含 Compose / Hilt / KSP 全链路** | **2m 07s** |
| `detekt` | ✅ 10 个任务全过 | 1m 12s |
| `testFullDebugUnitTest` | ✅ 全模块单测通过 | 58s |
| **`:app:assembleFullDebug`** | ✅ **产出 36MB APK** | **1m 59s** |
| `:app:lintFullDebug` | ⚠️ 跑通，但**报 39 error / 268 warning** | 5m 04s |

热缓存下第二遍会快很多（`testFullDebugUnitTest` 58s 就是复用编译产物后的数字）。

## 三个坑（都不是项目的问题，是环境）

### 1. gradle 版本：不要装，用 wrapper

sdkman 装的是 **9.3.0**，AGP 9.5.0 最低要 **9.5.0** ⇒ 直接拒绝构建。
但**正确的修法不是升级 gradle**，而是 `./gradlew` —— 项目 wrapper 已经是 9.8.2。
版本管理交给 wrapper，环境里装什么 gradle 都无所谓。

### 2. `platforms;android-37` 不存在

```
Warning: Failed to find package 'platforms;android-37'
```

**要带小版本号：`platforms;android-37.0`**。同理 build-tools 用 `37.0.0`。
查法：`sdkmanager --list | grep 'platforms;android-3'`。

### 3. `/root/.gradle/init.gradle` 是坏的（沙箱镜像预置）

任何 gradle 调用都在**初始化阶段**就失败：

```
Groovy DSL script compilation problem
  initialization script '/root/.gradle/init.gradle': 1: Unexpected input: '{' @ line 1, column 13
     allprojects {
```

⚠️ **行号骗人**：它指 line 1，真实错误在 line 3–4：

```groovy
maven { url https://mirrors.tencent.com/... }   // url 后缺 `=`
mavelCentral()                                  // 应为 mavenCentral（拼错）
```

修好之后还有第二道坎：项目在 `settings.gradle.kts` 里设了
`repositoriesMode = FAIL_ON_PROJECT_REPOS`，**在 project 层加仓库会让构建失败**。
必须挂到 `settingsEvaluated` 的 `dependencyResolutionManagement` 上。
现已改成正确版本（保留腾讯/阿里镜像，末尾仍留 `google()`/`mavenCentral()` 兜底）。

## 现在推荐的分工

| 场景 | 用什么 | 理由 |
|---|---|---|
| 改了一个纯逻辑文件，想秒级反馈 | `typecheck_kotlin.sh <file>` | **3–40 秒**，比 gradle 快一个数量级（gradle 配置阶段就要 20s+） |
| 改了 `ui/*` / 动了 Compose 或 Hilt | `./gradlew :app:compileFullDebugKotlin` | 只有它能查 Compose 签名与 Hilt 生成物 |
| 提交前 | `./gradlew detekt testFullDebugUnitTest` | 与 CI 同一条链路，绿了 CI 必绿 |
| 想看真实 APK 行为 | `./gradlew :app:assembleFullDebug` | 2 分钟出包；但**出包仍以 CI 为准**（签名/发布流程在 CI） |

## lint：39 个 error 是**真问题**，只是 CI 一直吞掉

CI 里 `Run lint` 步骤同时具备两个「看不见」的属性：

1. `continue-on-error: true`（`.ai/ISSUES.md` #100 的止血措施）
2. `if: github.event_name != 'push'` —— 而本仓库 50+ 次运行**全是 push**

⇒ 这条门禁**至今没有被真正验证过**。沙箱第一次跑就红了，39 个 error 里典型几类：

| 类型 | 例子 | 性质 |
|---|---|---|
| `NewApi` | `markUserPreVerified` 要 API 34、`SigningInfo#hasMultipleSigners` 要 28，minSdk 26 | **可能真崩**（缺 `@RequiresApi` 或版本分支） |
| `MissingPermission` | `SmartCopyNotifier` 调用需权限的 API 未检查 | **可能真崩** |
| `ContextCastToActivity` | 3 处 `LocalContext as Activity` | 新 lint 规则，官方建议用 `LocalActivity` |
| `StartActivityAndCollapseDeprecated` | `AutofillTileService` | 仅过时 |

处置建议（**未执行，需你定**）：先只看 `NewApi` + `MissingPermission` 这两类
（`grep -E "\[NewApi\]|\[MissingPermission\]"`），它们是崩溃风险；
其余归入「规则升级噪音」，可在 `lint.xml` 里按需 baseline，而不是关掉整条门禁。

## 复现这套环境

```bash
bash .ai/tools/setup_android_sandbox.sh   # ~6 分钟、~1.5GB
source /opt/vaultix-android-env.sh        # 每个新 shell 都要
./gradlew :app:compileFullDebugKotlin
```
