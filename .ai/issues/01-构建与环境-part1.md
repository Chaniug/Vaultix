# 01-构建与环境 · 第 1 篇（#1 ~ #42）

> 主篇索引：[`01-构建与环境.md`](./01-构建与环境.md)（含计数规则与重号警告）
> 本篇 18 章 · 16.0KB · 覆盖 #1 ~ #42

## 1. Git Bash 与原生 git 的路径映射错位（2026-09-07）

- **现象**：Bash 中 `git clone /d/Vaultix` 报"目录非空"（实际为空）；`git clone /tmp/xxx` 退出码 0 但目录不存在
- **根因**：MSYS 与原生 Windows git 对 `/d/`、`/tmp` 解析不一致，`/tmp/xxx` 被落到 `D:\tmp\xxx`
- **解法**：涉及 git/Gradle 的目录操作一律用 **PowerShell + 原生 `盘符:\路径`**

## 2. AGP 9 迁移五连坑（2026-09-07）

| # | 坑 | 解法 |
|---|---|---|
| 1 | 不能 apply `kotlin-android` | AGP 9 内置 Kotlin，apply 会报 `Cannot add extension with name 'kotlin'` |
| 2 | `kotlinOptions` 废弃 | 改用顶层 `kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_17 } }`（枚举，非 `JavaVersion`） |
| 3 | catalog 别名不能叫 `kotlin-jvm` | 与内置 `kotlin {}` DSL 撞名；改用 `kgp`，仅根工程 `apply false` |
| 4 | 类型安全项目访问器未启用 | `settings.gradle.kts` 加 `enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")` |
| 5 | Hilt 与 AGP/Kotlin 双绑定 | AGP 9 要求 ≥2.59；Kotlin 2.4.x 要求 **2.60.1**（metadata 版本不匹配会报 `Provided Metadata instance has version 2.4.0`） |

⚠️ Bastion 注释称"升 Kotlin 2.4 被 KSP 阻塞"**已过时**，真正在卡的是 Hilt。

## 4. 误用 `New-Item -Force` 覆盖已有文件（2026-09-07）

- **现象**：3 个已存在的测试文件被清空为 0 字节
- **教训**：创建文件前**必须先确认是否已存在**；切勿对可能已存在的文件用 `-Force`

## 5. 本机 SDK 没有 android-36（2026-09-07）

- `C:\AndroidSDK` 只有 35 / 37 / 37.0，故 `compileSdk` 必须 ≥ 37
- `D:\AndroidSDK` 是空壳，不可用

## 6. PowerShell 生成含 `$` 的代码时的转义陷阱（2026-09-07）

- **现象**：用 here-string 生成 Kotlin 代码时，`"bw_access::$server"` 被写成
  `"bw_access::server"`（`$` 被插值吞掉）——**所有库的凭据共用同一个 key，
  多库场景下 token 会互相覆盖**。这是真实上线级 Bug。
- **试过的错误转法**：`"`${'$'}server"` 产出 `$` 消失；单引号 here-string 里 `` `$ `` 保留反引号
- **解法**：代码里改用**常量前缀 + 字符串拼接**（`PREFIX + server`），完全避开 `$`
- **教训**：①生成脚本写完必须**校验输出文件内容**，不能只看脚本本身；
  ②需要插值变量时优先拼接而非模板；③写含 `$` 的代码用单引号 here-string `@'...'@`

## 7. 覆盖安装前提（记录，避免日后误改）

debug(preview) 与 release 可互相覆盖安装的**硬性前提**：
- 两者 applicationId 相同（debug **不加** applicationIdSuffix）
- 两者签名相同（已统一走 SIGNING_* Secret，jks 在 `D:\vaultix-release.jks`，密码用户自留）

## 9. KDoc 里写字面 `/**` → 注释嵌套直到 EOF（2026-09-08，排查最久的一个）

- **现象**：KSP 报一连串误导性错误——`ModuleProcessingStep ... 'BitwardenAuthInterceptor' could not be resolved`，且对**所有** @Provides 方法重复（连无参方法也是）；clean/重启 daemon/清缓存均无效
- **根因**：新文件 KDoc 写了「仅 `/api/**` 数据端点预挂」——Kotlin 块注释**支持嵌套**，`/**` 即 `/*` 开启一层嵌套注释，文件里再无第二个 `*/` 闭合 → 注释一直未闭合到 EOF → 整个文件解析失败 → 所有引用它的符号不可解析
- **解法**：注释文案避开 `/*` 序列（如改述「路径以 /api/ 开头」）
- **教训**：KSP 报「类无法解析」先怀疑**被引用文件本身有没有解析错误**（尤其是注释/编码），不要只在引用侧找原因

## 10. KSP2 对 @Provides 签名里的具体新类解析失败（2026-09-08）

- **现象**：`@Provides fun x(...): MyNewClass`（同模块新类）→ KSP「could not be resolved」；改为返回接口类型（`okhttp3.Interceptor`）后在函数体内构造具体类 → 通过
- **解法**：@Provides 签名一律用接口/已有类型；具体类只出现在函数体
- **备注**：疑似 KSP2 增量/新文件符号注册缺陷；2.0 稳定后复测可移除该限制

## 11. mockk：普通函数用 `every`，suspend 用 `coEvery`（2026-09-08）

- **现象**：`coEvery { repo.logout(any()) }` 配普通（非挂起）函数 → 运行时报 `no answer found for ... logout(...)`，编译不报错
- **解法**：非 suspend 函数 stub 用 `io.mockk.every`；仅 suspend 用 `coEvery`
- **关联**：DAO 是 suspend（Room）用 coEvery；领域普通方法（lockVault/logout）用 every

## 12. 测试 fixture 用占位字符串掩盖真实路径（2026-09-08）

- **现象**：引入「更新=合并上传」后 `updateItem_preservesIdAndRevision` 失败——`IllegalStateException: 本地密文损坏`
- **根因**：既有 fixture 的 `encryptedPayload = "stale"`（非 JSON）——以前 update 不读 payload，现在要解码合并
- **解法**：fixture 重建为真实链路产物（`mapper.toRequest(...).toStoredCipherDto(...)` 再 encode）
- **教训**：测试数据要贴近真实格式；存储层语义变更后旧占位 fixture 是最先暴露的假阳性

## 13. Dagger 依赖环新形态：拦截器引入认证仓库（2026-09-08）

- **现象**：给 OkHttp 加「预挂 Bearer 拦截器」后 Hilt 报
  `Retrofit.Builder → BitwardenApiFactory → ... → OkHttpClient` 构造期环
- **根因**：拦截器构造即拉 `AuthRepository`，而 AuthRepository → ApiFactory → Builder → OkHttpClient
- **解法**：拦截器注入 `Provider<AccessTokenProvider>` 请求时再 `get()`（与 BitwardenAuthenticator 同款断环；**eager 会死锁**）
- **判据**：网络栈出现 client→auth→client 自环一律 Provider 懒断环

## 14. Gradle daemon 大任务批崩（2026-09-08）

- **现象**：一次跑 6 模块单测+detekt，daemon 直接消失（heap 2GiB 不够）「daemon disappeared unexpectedly」
- **解法**：拆批执行（编译一批 / 单测一批 / detekt 单独）；单测失败先从 XML 报告定位再重跑

## 17. Detekt 门禁常见三连（2026-09-09）

- `MatchingDeclarationName`：文件里只有**一个**顶层类/对象声明时，文件名须与其同名
  → 把 data class 拆到独立文件（如 `AppInfo.kt` 与 `AppPickerDialog.kt` 分离）
- 给表单 / 页面新增 UI 后 `LongMethod` / `CyclomaticComplexMethod` 超阈值 → 把
  `if (showX) { Dialog { ... } }` 抽成 `XHost(...)` 私有 composable（比事后重构便宜得多）
- `MagicNumber`：`fillMaxWidth(0.95f)` 这类比例值也要常量化（Compose 的 `.dp` 数字不受影响）
- `Unresolved reference 'Bolt'`：`Icons.Filled.*` 并非全量 Material 图标都可用，
  换用项目里已验证存在的图标（如 `ContentCopy`）——编译前先确认图标存在

## 18. Kotlin 默认参数不能调用 suspend 函数（2026-09-09）

- **现象**：`suspend fun f(clearMs: Long = prefs.preference.first())` 编译失败
  （默认参数表达式不是挂起上下文）
- **解法**：默认参数只能是非挂起表达式 → 在函数体内读取偏好，或由调用方显式传值

## 39. 沙箱无法推送 GitHub（2026-09-11，P1，已解决）

**现象**：`git push` 报 `could not read Username`；`git fetch` 报 `gnutls_handshake() failed`；
`ssh` 报 22 端口超时。

**根因**：沙箱把 GitHub 域名解析到 `198.18.0.x`（保留测试网段，被网关劫持），HTTPS TLS 被断；
SSH 22 端口被墙；`git-credential-helper` 对 github.com 返回空、非交互环境取不到 HTTPS 凭证。

**解决**：阿里 DoH 查真实 IP → 写 `/etc/hosts` **并同步 `~/.user_hosts`** →
`~/.ssh/config` 令 `github.com` 走 `ssh.github.com:443` → `git remote` 改 SSH URL。
推送成功。**恢复步骤见 `.ai/MEMORY.md` 第三十三轮「可复用配方 1」。**

### 39.1 恢复配方（2026-10-28 实测，逐条可复制）

```bash
# 1) 私钥：写在 ~/.ssh/github_chani（**必须保留 PEM 的换行**，见下方 ⚠️）
chmod 600 ~/.ssh/github_chani
ssh-keygen -l -f ~/.ssh/github_chani
#   期望：256 SHA256:r99kZ2srjaVvBVmUeBKb6S6svJxK/IIDGUZ7+oEXIqA github-chani (ED25519)

# 2) ~/.ssh/config —— 走 443，绕开 22 端口不通
cat > ~/.ssh/config <<'EOF'
Host github.com
    HostName ssh.github.com
    Port 443
    User git
    IdentityFile ~/.ssh/github_chani
    IdentitiesOnly yes
EOF
chmod 600 ~/.ssh/config

# 3) 验证（应打印 Hi Chaniug!）
ssh -T git@github.com

# 4) 远端换回 SSH（不要长期把 token 写进 .git/config）
git remote set-url origin git@github.com:Chaniug/Vaultix.git
git fetch origin --prune && git push --dry-run origin main
```

**⚠️ 坑 A：私钥"贴过来不能用"，多半是换行被吞了。**
PEM 是有结构的：`-----BEGIN …-----` / 若干行 base64 / `-----END …-----`。
把正文压成**一行**（很多聊天框会这么做）后，`ssh-keygen` 会报
`invalid format`／`error in libcrypto` —— 而**指纹还能算出来是不对的**（那说明没真解析）。
判据：**先跑 `ssh-keygen -y -f <私钥>`，能吐出与公钥一字不差的 `ssh-ed25519 AAAA…`，
才算这把钥匙可用**；只对 `ssh-keygen -l` 的指纹不算（指纹只是个摘要，不校验结构）。
> 本项目的历史事故：2026-10-28 之前的会话把私钥判成"解不开、弃用"，改走 HTTPS+token；
> 实际是**换行被吞成一行的完整 key**，按 70/70/70/… 折行后首次即通过。
> ⇒ **"我解不开"与"这把钥匙本身是坏的"是两件事，先按 PEM 结构折行再下结论。**

**⚠️ 坑 B：`/etc/hosts` 里同一个域名写了两条不同的 IP，解析是不确定的。**
本次实测到 `github.com` 同时存在 `20.205.243.166` 与 `140.82.112.4`。
`getaddrinfo` 取第一条还是轮询取决于 libc / nsswitch 配置 ⇒ 症状是**时好时坏**
（一次 `git push` 通、下一次 `GnuTLS recv error (-110)`）。
判据：**补 hosts 前先 `grep` 一遍，同域名只能留一条**；`/etc/hosts` 与 `~/.user_hosts`
必须是同一份内容的拷贝（沙箱重启会还原 `/etc/hosts`，所以真源是 `~/.user_hosts`）。

**⚠️ 坑 C：`GnuTLS recv error (-110)` 是 TLS 断连，不是认证失败。**
重试通常就好（本次 `push` 第 1 次失败、第 2 次成功）；**别当成密钥或权限问题**去改配置。

---

## 40. detekt 圈复杂度「越拆越高」——同文件 helper 被累加进调用方（2026-09-11，P0）

**现象**：`VaultixCredentialProviderService.resolvePasskeys` 报 `CyclomaticComplexMethod`
complexity **41**（阈值 14）。把它拆成若干**同文件** private helper 后，复杂度不降反升
（原内联版 19 → 拆后 41）。

**根因**：detekt **2.0.0-alpha.6** 的 `CyclomaticComplexMethod` 会把**同一文件内**被调用的
私有函数的复杂度**累加**进调用方（非标准 McCabe 行为）。同时该版本
`ignoreNestingFunctions` 默认 **false**（1.x 为 true）→ 作用域函数
（`let`/`run`/`with`/`apply`/`also`/`forEach`/`use`）每个 +1。

**实证方法（可复用）**：把可疑函数的**函数体临时 stub** 成 `return emptyList()` 再跑 detekt。
- 违规**消失** ⇒ 复杂度来自「调用」（本情况）；
- 违规**仍在** ⇒ 复杂度在自身函数体。
> 不要凭"标准 McCabe 是 per-function"的常识推断 —— 本版本就是不走标准。

**解法**：把 helper 拆到**独立文件**（detekt 逐文件分析、**不跨文件累加**）。
本轮新建 `app/src/main/java/io/vaultix/vaultix/passkey/PasskeyResolution.kt`：
- `PasskeyMatch` / `PasskeyCounts` 改 `internal` 顶层；
- `collectPasskeyMatches(itemRepository, unlocked, rpId)` / `applyAllowedFilter(rpMatched, allowed, log)`
  / `collectStoredRpIds(itemRepository, unlocked)` 顶层函数（inject 依赖以参数传入）；
- `resolvePasskeys` 主函数仅编排（自身 ~5），各 helper 独立 < 14。

**副作用**：`PasskeyMatch` 从 service 内 private 嵌套类移到同包顶层 `internal`（`publicKeyEntry`
同包引用，无需 import）；`WebAuthn` / `VaultFido2Credential` import 随之从 service 移除。

---

## 41. CI 1 分钟就红 → 其实是 detekt 先于 compile 失败，掩盖了编译错误（2026-09-11，P0）

**现象**：`main` 推送后 CI ~1 分钟即失败（正常约 3 分钟）。表面只见 detekt 报错，
容易误以为"修完 detekt 就好"。

**根因**：workflow 步骤顺序 = **detekt → (lint) → compile → assemble**。detekt 一失败即中断，
**编译步骤永不被执行** ⇒ 上一提交引入的 3 处 `Int?` 空安全**编译错误**被完全掩盖
（`d6f3409` 引入，但它同时把 detekt 弄红，编译错误一直没暴露）。

3 处编译错误与修法：
| 文件 | 错误 | 修法 |
|---|---|---|
| `core/datastore/.../VaultTimeout.kt` | `associateBy { it.vaultTimeoutInMinutes }` 键实为 `Int?` | `mapNotNull { t -> t.vaultTimeoutInMinutes?.let { it to t } }.toMap()` |
| `app/.../ui/settings/SettingsScreen.kt` | 组合 `when` 分支不做智能转换，`timeout.vaultTimeoutInMinutes` 仍 `Int?` | `requireNotNull(timeout.vaultTimeoutInMinutes)` |
| `app/.../passkey/CredentialProviderIntentUtils.kt` | 返回类型写成 `BeginCreateCredentialRequest?`，实际是 `CreateCredentialRequest?` | 改返回类型为 `CreateCredentialRequest?`（见下） |

**类型陷阱细节**：`BeginCreateCredentialRequest`(`androidx.credentials.provider`) 与
`CreateCredentialRequest`(`androidx.credentials`) 是**两个互不相关**的类（无继承关系）。
`PendingIntentHandler.retrieveProviderCreateCredentialRequest(intent)` 返回
`ProviderCreateCredentialRequest`，其 `callingRequest` = **客户端侧** `CreateCredentialRequest`
（credentials 1.6.0 `javap` 实证）。⇒ 由 Intent **无法**还原服务端侧的 `BeginCreateCredentialRequest`。
连带把 `CredentialProviderRequestManager.createCredentialRequest` / `setCreateCredentialRequest`
也改为 `CreateCredentialRequest`（该字段 **write-only**、无读取方，改动安全）。

**教训**：
1. **detekt 只做静态检查、不做类型检查** ⇒ 用"去魔法数字"等手段改代码后，
   **必须真跑一次 compile**（本地 `:app:compileFullDebugKotlin`）。
2. CI 步骤顺序会让**前置步骤失败掩盖后续步骤的问题** —— 排障先看"哪一步真的跑了"，
   别被"最后一条报错"误导（本次真正待修的还有编译错误）。

---

## 42. 本机（Windows/WorkBuddy）与早前沙箱的环境差异（2026-09-11）

早前多轮记录的"沙箱无 Android SDK / JDK17、`gradlew` 跑不起来、`ghu_` token 对
`api.github.com` 401" **仅适用于沙箱**。本机（用户 Windows + WorkBuddy）**全部可用**：
- JDK 17 + Android SDK（`C:\AndroidSDK`）齐全 → 可本地 gradle 构建/跑测试；
- `gh` CLI 可正常 `gh run list/view/watch`（查 CI 状态无需再读网页端 job 页）；
- `git push` 经 SSH（`git@github.com:Chaniug/Vaultix.git`）正常。

**唯一坑**：Git Bash 下 `./gradlew` 报「找不到或无法加载主类 GradleWrapperMain」。绕过：
```bash
java -classpath "D:/Vaultix/gradle/wrapper/gradle-wrapper.jar" \
     org.gradle.wrapper.GradleWrapperMain <task> [--no-configuration-cache]
# 长时间任务/daemon 配额耗尽：先 ... GradleWrapperMain --stop
```

**参考源码本地副本（不纳入 git，位于仓库外 `D:\Vaultix-refs\`）**：

| 项目 | 路径 | 分支 | 快照 HEAD（2026-10-28） |
|---|---|---|---|
| Bitwarden Android | `D:\Vaultix-refs\bitwarden-android` | `main` | `1e624ad`（2026-10-27） |
| Keyguard | `D:\Vaultix-refs\keyguard-app` | `master` | `693c2073`（2026-10-28） |
| kotpass（KDBX 引擎上游） | `D:\Vaultix-refs\kotpass` | `develop` | `4e0bd6b`（v0.13.0，仓库已停更） |

- 三者均为**浅克隆**，读源码足够；要 `git log` / `git blame` 的完整历史需 `git fetch --unshallow`。
- **刷新**：`git -C <dir> fetch --all --prune` → `git rev-list --count HEAD..origin/<branch>` 看落后数 → `git pull --ff-only`。
- ⚠️ **判断「有没有新内容」必须先 `fetch`**：`git status` 的 "up to date" 只相对上次 fetch，跨会话会误导（2026-10-28 实测踩过：先误判"无新内容"，fetch 后发现 main 落后 73）。
- 查 Bitwarden / Keyguard 实现**先读本地**，不必联网。

---

