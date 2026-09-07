# 问题与坑整理（AI 接力必读）

> 每个坑都写清：现象 → 根因 → 解法。避免重复踩。

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

## 3. Argon2 黄金向量错误（2026-09-07）

- **现象**：`KdfTest` 两个 Argon2 参考向量测试失败
- **根因**：**测试向量是臆造的，实现是对的**
- **解法**：用 Python `argon2-cffi` 独立校准。校准方法：
  `hash_secret_raw(secret=pwd, salt=sha256(email), time_cost=t, memory_cost=m*1024, parallelism=p, hash_len=32, type=ID)`
- **教训**：密码学黄金值必须用权威实现生成，不能手写

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

## 8. 「登录易失效」三根因（2026-09-08，真机驱动，d689a37 修复）

- **现象**：app 高频提示「登录已失效，请重新登录」；重启后快速解锁尤其必现
- **根因（3 条叠加）**：
  1. 网络层从不预挂 `Authorization`——每个请求先 401 → OkHttp Authenticator 刷新 → 重试；每次调用都轮换 token，任何一次刷新失败即误报失效；
  2. `host→server` 映射只在**登录**时登记且仅存内存——进程重启后走快速解锁（不重登）则映射为空，刷新必失败（即使 access token 仍有效）；
  3. 刷新失败不分类——403（CF/WAF）/429/5xx/网络抖动全被当凭据失效（Bastion 明示过此误伤）
- **解法**：① 请求拦截器按 host 预挂 Bearer，expiresIn 落盘、到期前 60s 预刷新；② 解锁路径（含本地快速解锁）`registerServer`；③ 刷新结果三分：400/401=Invalid（重登）/ 其余=Transient（保留登录态，sync 401 按 `refreshFailureOf(server)` 归类）
- **防回退**：任何「请求不带 Bearer 直接发」或「把非 401 失败当登出」的改动都是回归

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
