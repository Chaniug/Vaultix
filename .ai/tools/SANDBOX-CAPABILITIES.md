# 沙箱能做什么、不能做什么（2026-11-10 实测）

> 起因：用户问「本地可以做 CI 吗，沙箱环境除了不出 APK 还能提效什么」。
> 本文只写**跑过、有数字**的部分；未验证的一律标注「未测」。

## 结论先放这

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

## 下一步可以做的（都没做，先记着）

1. **给 app 层加 Compose 编译器插件**：`app` 是单一 source set，
   没有插件就整包编不过（实测 6 分 21 秒后红在 `MainActivity` 的 `androidx.compose.*`）。
   要做就得把 Compose runtime / compiler 与 `androidx.activity` 也纳入依赖清单 —— 工作量不小，
   但收益最大：**UI 层签名错误**（比如上一轮的带接收者 lambda）从此不必等 CI。
2. `data:bitwarden` / `core:database` / `core:datastore`：缺 Room / DataStore 的生成物，
   要么加 KSP 预生成，要么手工桩 —— 先把它们从 CI 白名单里排除（现状）。
