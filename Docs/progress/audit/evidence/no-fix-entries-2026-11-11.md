# 附录 D · 「不需要修」登记表与逐条证据（2026-11-11）

> 主报告：[`../project-audit-2026-11-11.md`](../project-audit-2026-11-11.md)；判据主篇：[`../no-fix-register-2026-11-11.md`](../no-fix-register-2026-11-11.md)。
> **本文件下方 `NO_FIX_REGISTER` 标记块内的表格是「不修登记」的唯一真源**：
> `.ai/tools/check_no_fix_register.py` 直接解析它，主篇只做摘要与指针，不复制条目。
> 改登记**只改这张表**；改完必须在仓库根跑一次 `python3 .ai/tools/check_no_fix_register.py`。
>
> 分篇说明（8.9）：本文件是**百余行的宽表登记**（每行含长锚点列），行数远低于 300 行上限、
> 相邻章节也远低于 120 行；字节数略超 ≈20KB 属表格性质，且按 8.9「证据 / 大表格移到
> `evidence/`」的纪律，它本来就落在 `audit/evidence/` 下，不拆。

## D.1 表格约定

| 列 | 含义 |
|---|---|
| `ID` | 稳定编号，`A/B/C/D/E` + 两位序号。**编号一经分配不复用、不重排**（正文引用它） |
| `分型` | `A` 刻意冻结 / 知情选择 · `B` 静态误报 / 口径差异 · `C` 已判推迟 / 收益≈0 · `D` 已经做对、不要回退 · `E` 显式豁免 / 框架边界 |
| `锚点` | `文件路径` → `必须仍存在的片段`。门禁逐条检查；**片段消失即判登记失效**，须复核是「代码被改了」还是「该转修了」 |
| `判定理由` | 一句话，回答「凭什么不修」；证据原文见 D.3 |
| `触发转修条件` | 将来什么情况出现就必须重新评估。**「不修」是带有效期的判断，不是永久豁免** |
| `禁止重新表述` | 精确短语，门禁用它扫 `Docs/progress/next-steps.md` 的未完成项 `- [ ]`；命中即红，防止下一轮把同一件事重新列成待办。`—` 表示不设 |

> 锚点尽量落在**配置 / 决策 / 证据文档**上，因为这类文件少变、且 `ci-docs.yml` 的 `paths` 能覆盖。
> 少数落在 `.kt` 上的锚点（D03/D04/D05）由**本地全量门禁**兜底；CI 覆盖边界见主篇 §5。

## D.2 登记表（唯一真源）

<!-- NO_FIX_REGISTER_BEGIN -->
| ID | 项 | 分型 | 锚点 | 判定理由 | 触发转修条件 | 禁止重新表述 |
|---|---|---|---|---|---|---|
| A01 | `composeBom` / `material3` / `markdownRenderer` 三项冻结 | A | `gradle/libs.versions.toml` → `composeBom = "2026.06.01"` | #239 真机 `AbstractMethodError` 的硬绑定专项，三项必须一起动；当前组合是知情选择不是技术债 | 出现针对该组合的安全公告，或 `material3` stable 追上 foundation 1.12.x，且已完成 S1 解析门禁 | 升级 composeBom；单独升级 material3；顺手升级 markdownRenderer |
| A02 | `material3` 刻意保留 `1.5.0-alpha16` | A | `gradle/libs.versions.toml` → `刻意保留 alpha16` | alpha27 会引发 70 处编译失败（`menuAnchor()` 无参重载被移除），属业务代码改造而非版本切换 | 愿意单独立项改 70 处调用点，且 CI 门禁 S1 已上线 | 替换 alpha 组件；升级到 alpha27 |
| A03 | `markdownRenderer` 锁 `0.45.0` | A | `gradle/libs.versions.toml` → `markdownRenderer = "0.45.0"` | 0.46.0 是分界线，Android 变体 POM 带入 `foundation-android:1.12.1` 顶掉 bom 约束 | 与 A01 同批专项一起动，且先跑 `dependencyInsight` | 升级 markdown 渲染器 |
| A04 | `biometric` 留在 `1.4.0-alpha` 线 | A | `gradle/libs.versions.toml` → `稳定版 1.1.0 比 alpha 线更旧` | 最新 stable 反而更旧；升 beta 涉及指纹与 Keystore，需真机回归，无收益驱动 | 上游发 stable 且带来明确 bugfix，或指纹解锁出现真机缺陷 | 升 biometric 到稳定版 |
| A05 | `argon2kt` 锁 `1.6.0` | A | `gradle/libs.versions.toml` → `上游无更高稳定版` | 上游即最新；`rawHashAsByteArray()` 等 API 被清零逻辑依赖，升级收益为零 | 上游发布新稳定版且 API 兼容 | 升级 argon2kt |
| A06 | MSAL 不配 client secret | A | `gradle/libs.versions.toml` → `不配 client secret` | public client + PKCE 是移动端唯一正确做法；配 secret 反而是错的 | 上游改为要求机密客户端（与移动端威胁模型冲突，需重新决策） | 给 MSAL 配 client secret |
| A07 | `opentelemetry-api` 用 `constraints` 顶到 `1.67.0` | A | `gradle/libs.versions.toml` → `不去改 MSAL 的传递树` | GHSA-ccrj-gw8r-854m 已处置且没接管上游依赖决议；改传递树下轮升级必炸 | 上游 MSAL 修复自身 POM，或出现 constraints 无法覆盖的新 CVE | 改 MSAL 传递依赖树 |
| A08 | `offline` flavor 暂停参与构建、定义保留 | A | `Docs/progress/decisions.md` → `offline flavor 暂停参与构建` | 用户拍板：无本地库引擎时 offline 包是空壳，双 variant 让 CI 时间与缓存翻倍；恢复点已写进 workflow 注释 | `data:kdbx` 本地库能力足以支撑纯本地版，且用户重新拍板 | 构建 offline 包；删除 offline flavor 定义 |
| A09 | Markdown 渲染器带来 APK 体积代价 | A | `gradle/libs.versions.toml` → `已知代价（知情选择` | 2026-09-28 用户拍板接受；仅用于更新日志对话框，JetBrains 解析器比误记的 commonmark 更轻 | release 包体积超过明确阈值，或出现体积敏感的发布渠道 | 自写轻量 Markdown 渲染 |
| A10 | Graph 调用手写 OkHttp，不走统一 Retrofit 拦截器 | A | `gradle/libs.versions.toml` → `Retrofit 与 OkHttp 成对维护` | 对齐 Bitwarden 官方客户端；Retrofit 只服务 `data:bitwarden`，强行统一会引入无意义抽象 | 网络层出现第二处需要相同拦截逻辑的服务 | 统一网络拦截器；Graph 改 Retrofit |
| A11 | 不引自定义字体 / 不换 M3 alpha 组件 / 不动品牌色 | A | `.ai/decisions/条目页-M3-视觉方案.md` → `不引入自定义字体文件` | 中文字体动辄 10MB+；alpha 组件等 stable；品牌色有 WCAG 单测，改了 CI 红 | 对应组件转 stable，或用户重新提出明确观感诉求 | 换 M3 Expressive alpha 组件；改 VaultixBrandColor |
| A12 | 快速解锁「软登出」「切换后锁旧库」等被否方案 | A | `.ai/decisions/快速解锁房子化-两级钥匙层级-定稿-part3.md` → `§11 被否 / 刻意不学` | 用户威胁模型已排除；预埋属过度设计，定稿 §11 连理由一起登记避免重复提议 | 用户明确变更威胁模型 | 加软登出档；切换后锁定旧库 |
| A13 | 设置页「同一偏好两个入口」不下沉合并 | A | `.ai/decisions/设置页信息架构-定稿-part4.md` → `都是**同一份偏好的第二个入口**` | 条目显示 / 回收站清理的第二个入口复用同一弹层，不是第二套实装 | 某入口开始出现独立状态或独立实现 | 合并设置页重复入口 |
| B01 | 159 条「注释缺空格」 | B | `Docs/progress/audit/evidence/standards-scan-2026-11-11.md` → `comment_no_space` | 规则 `//\S` 命中 `file://` / `content://` / `androidapp://` 等 scheme 中的 `//`，几乎全是 KDoc 里的 URL | 改用能识别 URL 的扫描器后重新计数，且确有非 URL 命中 | 修 159 条注释缺空格；批量补注释空格 |
| B02 | 52 条「硬编码密钥」 | B | `Docs/progress/audit/evidence/standards-scan-2026-11-11.md` → `RFC 6238 TOTP 测试向量` | 51 条是 RFC 6238 测试向量，1 条生产命中是 KDBX 字段名常量，无线上密钥泄露 | 生产代码出现真实凭据，或密钥扫描换成语义级工具 | 清理 52 条硬编码密钥 |
| B03 | 2 条 `System.out` | B | `Docs/progress/audit/evidence/standards-scan-2026-11-11.md` → `反面示例` | 都在 KDoc 里当反面示例（「一次 println(session) 就可能把明文写进日志」）；`printStackTrace` 为 0 | 出现真实 `System.out` / `printStackTrace` 调用点 | 删除 System.out |
| B04 | 95 条 `runBlocking` | B | `Docs/progress/audit/evidence/standards-scan-2026-11-11.md` → `OkHttp Authenticator` | 82 条测试；生产 13 条中 10 条是注释 / import，真实调用 2 处全是 OkHttp Authenticator 同步回调框架边界 | 生产新增第 3 处真实调用，或该边界改为 suspend API | 清零 runBlocking；替换 Authenticator |
| B05 | 222 条测试代码 `!!` | B | `Docs/progress/audit/evidence/standards-scan-2026-11-11.md` → `断言中` | `taken!!.username` / `parse(...)!!` 是常见测试断言写法，不是生产风险 | 生产 `!!` 计数（3 处，见 S3）清零后仍新增 | 清理测试里的双叹号 |
| B06 | 4 条测试 `Thread.sleep` | B | `Docs/progress/audit/evidence/standards-scan-2026-11-11.md` → `并发时序构造` | 2 条是注释（明写「别把 Thread.sleep 写回来」），2 条是并发时序构造 | 出现生产侧新增 `Thread.sleep`（S4 处理的是既有 3 处） | 清理测试里的 Thread.sleep |
| B07 | 1 条「明文 HTTP」 | B | `Docs/progress/audit/evidence/standards-scan-2026-11-11.md` → `plaintext_url` | 测试注释举例，且 `GlobalScope` / `webview_file` / `todo` 均为 0 命中 | Manifest 出现 `usesCleartextTraffic="true"` | 修明文 HTTP |
| B08 | 131 个超长方法 / 9 个超大类 | B | `config/detekt/detekt.yml` → `allowedLines: 150` | 团队规范 80/800 与项目 detekt 150/1200 口径不同，且脚本按声明行距算含 KDoc 空行、detekt 只数代码行；CI 口径 `detekt` 当前绿 | 项目主动调整 detekt 阈值，或某个类真的触发 CI `LargeClass` / `LongMethod` 失败 | 对齐 80/800 阈值；拆 131 个方法；批量拆大类 |
| B09 | `PublicSuffixData.kt` 10,344 行 | B | `app/src/main/java/io/vaultix/vaultix/autofill/match/PublicSuffixData.kt` → `@file:Suppress("LargeClass", "MayBeConstant")` | PSL 自动生成数据，文件头已显式豁免，64KB 分块是刻意设计 | 生成方式变化导致不再需要分块，或 PSL 数据源更新流程改变 | 拆分 PublicSuffixData |
| B10 | 8 处未写 `@Suppress` 的裸 `catch` | B | `Docs/progress/audit/evidence/standards-scan-2026-11-11.md` → `swallow_catch` | detekt `SwallowedException` 扫描为 **0**；8 处全在解析降级路径（URI 解析返 null、扫码每帧常态、TOTP 返占位符、日志设施兜底），有明确降级行为而非吞异常 | detekt 升级后开始报，或某处降级行为被删只剩空 catch | 收窄 8 处裸 catch；给 8 处补 Suppress |
| B11 | 1 条 169 字符超长行 | B | `Docs/progress/audit/evidence/standards-scan-2026-11-11.md` → `KDoc 里的 Markdown 链接` | KDoc 中的 developer.android.com 链接；detekt 未报，属观感项 | CI 开始报 `MaxLineLength`，或该文件 KDoc 被整体重写 | 修 169 字符超长行 |
| B12 | 3 处 KDoc 里提及的 `catch (Exception)` | B | `Docs/progress/audit/evidence/standards-scan-2026-11-11.md` → `KDoc 提及` | 是说明文字（含「不用 catch(Exception)」的反面示例），不是代码 | 说明文字被误替换成真实调用 | 统计裸 catch 时重复计入 KDoc |
| C01 | WorkManager 周期同步 | C | `Docs/progress/decisions.md` → `WorkManager 周期同步 M1 判推迟` | 进程存活时 APP_RESUME/PAGE_ENTER 已覆盖「打开即最新」；进程被杀后会话密钥只在内存、重启必锁 ⇒ 无可同步内容，收益≈0 | 引入本地 KDBX 离线写队列（有可同步内容），或用户要求后台自动同步 | WorkManager 周期同步；后台定时同步 |
| C02 | `feature/*` 11 个模块拆分 | C | `Docs/progress/decisions.md` → `11 个模块暂不拆分` | 代码量小时模块化收益为负，先按包名组织 | 模块边界出现真实并行开发 / 构建隔离需求 | 拆分 feature 模块 |
| C03 | 本地 KDBX 一键迁移到网盘 | C | `.ai/decisions/设置页信息架构-定稿-part4.md` → `为什么不做一键迁移` | 库身份 / 加密信封 / 已登记指纹与 PIN 都绑在文件位置；自动搬失败会毁掉已配好的解锁方式，故只做三步引导页 | 出现安全的库身份迁移方案并经用户拍板 | 做本地库一键上云 |
| C04 | 设置页 UI 自动化测试 | C | `.ai/decisions/设置页信息架构-定稿-part1.md` → `不做自动化测试` | 当轮无 ViewModel 逻辑变化，靠真机目视 + 现有 JVM 单测兜底 | 设置页出现复杂状态机逻辑 | 补设置页 Espresso 测试 |
| C05 | 条目详情页动态验证码 | C | `.ai/decisions/条目页-M3-视觉方案.md` → `不给详情页加动态验证码` | 8.4 已定「降功耗，只提示不滚动刷新」 | 用户明确要求详情页实时滚动 TOTP | 详情页加动态验证码 |
| C06 | 不做跨库条目（复制 / 多选 / 镜像） | C | `.ai/decisions/多库锁模型-定稿-part1.md` → `不做跨库条目` | 多库锁模型 D6 定稿：写隔离、读聚合不限，格式互转只在导出 | 用户提出明确的跨库整理需求 | 做跨库条目复制 |
| C07 | 不写新旧信封兼容互转层 | C | `.ai/decisions/快速解锁房子化-两级钥匙层级-定稿-part3.md` → `一次性重登记，不写兼容层` | 定稿选择一次性重登记；兼容层会永久保留两套钥匙语义 | 出现无法要求用户重登记的存量数据场景 | 写信封兼容层 |
| C08 | `retrofit 3` / `okhttp 5` / `coil3` 跨 major | C | `Docs/progress/audit/project-audit-2026-11-11.md` → `建议不排期` | retrofit 3 POM 反而依赖 okhttp 4.12.0；okhttp 5 带 okio 3.18.1（项目锁 3.18.2）；coil3 是新坐标 + API 变更，无明确收益 | 上游安全公告，或出现只有新 major 能修的 bug | 升级 retrofit 3；迁移 coil3；升级 okhttp 5 |
| C09 | `Docs/00~18` 设计期蓝图滞后，不盲改 / 不盲删 | C | `Docs/progress/next-steps.md` → `README 已有已知标记` | 它们是设计期蓝图不是现状描述，README 已标记滞后；盲改会抹掉决策上下文，盲删会断掉 `.ai/archive/` 里的引用 | 先定「哪几份还算真源」并逐份回溯，单独立项 | 批量更新 Docs 根目录设计文档；删除滞后文档 |
| C10 | 6 个 `SESSION-*.md` 超 300 行不盲拆 | C | `Docs/progress/next-steps.md` → `SESSION-*.md` | 记忆明确「存量未回溯合并 ⇒ 不能盲删」，按段回溯是独立工程 | 完成逐段回溯，且 8.9 分篇约束要求严格合规 | 直接对半拆 SESSION 文档 |
| C11 | 覆盖率「不足 80%」不当成违规 | C | `Docs/progress/audit/project-audit-2026-11-11.md` → `先建基线再逐步提` | Kover 未接 CI、无基线数字，阈值 80% 无从证明；这是「要建」不是「要修」 | S5 基线接入 CI 并稳定一个季度后，再按季上调 | 一步把覆盖率阈值设成 80% |
| C12 | Baseline Profile / 配置缓存 / Kover 属「要建」 | C | `Docs/progress/audit/project-audit-2026-11-11.md` → `均为可交付工程` | 三者是缺失的能力，不是被写坏的代码；不能混进「违规整改」清单 | 已按 P1/P3/S5 建成后，条目转为 D 类「不要回退」 | 把质量基建缺失列为代码违规 |
| D01 | `android:allowBackup="false"` | D | `app/src/main/AndroidManifest.xml` → `android:allowBackup="false"` | #186 由 true 改 false，同时关掉云备份与 D2D 迁移两条明文外泄通路；KDoc 写了「为什么不是只加排除规则」 | 用户明确要求云备份，且已设计加密备份方案 | 恢复 allowBackup；加备份排除规则替代 |
| D02 | release `isMinifyEnabled` + `isShrinkResources` | D | `app/build.gradle.kts` → `isMinifyEnabled = true` | release 混淆与资源压缩已开；debug 刻意 false，不要为了「统一」改 | release 出现需要临时关闭混淆的调试需求（应临时，须回退） | 关闭 release 混淆 |
| D03 | R8 keep 规则 | D | `app/proguard-rules.pro` → `-keepattributes Signature` | Signature / Annotation / InnerClasses / Hilt / kotlinx-serialization 的 keep 已齐，是历次 release 踩坑沉淀 | 新增反射型依赖且 release 真机报 `ClassNotFoundException` 类问题 | 精简 proguard 规则 |
| D04 | Room `exportSchema = true` + `addMigrations` | D | `core/database/src/main/java/io/vaultix/database/VaultixDatabase.kt` → `exportSchema = true` | schema 导出与显式迁移都在，禁止改回 `fallbackToDestructiveMigration` | 出现需要破坏性迁移的极端场景（须单独立项并通知用户） | 改 fallbackToDestructiveMigration |
| D05 | Coil 全局 `respectCacheHeaders(false)` + `crossfade(false)` | D | `app/src/main/java/io/vaultix/vaultix/VaultixApplication.kt` → `respectCacheHeaders(false)` | 图标加载的缓存策略已定，crossfade 关闭是刻意的（避免底栏图标淡入抖动） | 出现真实的图标缓存失效问题且有实测数据 | 开启 crossfade；改用默认缓存策略 |
| D06 | OkHttp 三处超时 + `retryOnConnectionFailure` + `pingInterval` | D | `data/bitwarden/src/main/java/io/vaultix/data/bitwarden/di/NetworkModule.kt` → `.pingInterval(PING_INTERVAL_SECONDS` | 反代会静默关闭空闲连接、复用即挂死，`pingInterval` 是针对性修复，勿当冗余删 | 网络栈整体重构 | 删除 pingInterval；去掉超时配置 |
| D07 | `VaultixLog` 的 DEBUG-only 装配与限频 | D | `app/src/main/java/io/vaultix/vaultix/VaultixApplication.kt` → `VaultixLog.install(enabled = BuildConfig.DEBUG)` | 门面提供总开关 / 每 tag 50 条 60s 限频 / 异常兜底，默认 `enabled=false`；S2 要修的是**绕过它的 6 处**，不是门面本身 | 用户要求 release 可观测性（走 S6 脱敏诊断包，不改门面开关） | 让 release 打 DEBUG 日志；删除限频 |
| D08 | `dependency-review.yml` PR 级高危门禁 | D | `.github/workflows/dependency-review.yml` → `fail-on-severity: high` | 已有 PR 级 high/critical 依赖审查；S1 补的是「解析结果对齐」，不是重复造安全门禁 | GitHub 变更 Action 行为或项目改为非 PR 流程 | 移除 dependency-review |
| D09 | `dependency-submission.yml` 提交解析后依赖图 | D | `.github/workflows/dependency-submission.yml` → `dependency-submission` | 已把 Gradle 运行期才确定的依赖图提交给 GitHub，与 S1 互补 | GitHub 依赖图服务下线 | 移除 dependency-submission |
| D10 | daemon 堆各 4g | D | `gradle.properties` → `org.gradle.jvmargs=-Xmx4g` | 大型 composable 分析易 OOM，MEMORY §4 明写「禁止回退 2g」 | 全量构建实测内存余量充足且有数据 | 回退 daemon 堆到 2g |
| D11 | detekt 阈值 150 / 1200 / 参数 8 / 单类函数 40 | D | `config/detekt/detekt.yml` → `allowedLines: 1200` | 这是项目现行门禁口径；与团队规范 80/800 的分歧已登记为 B08 | 项目主动决定统一到团队规范（须同时改 B08 与 CI） | 单方面改 detekt 阈值对齐 80/800 |
| D12 | `VaultOriginLabel.kt` 等既有静态门禁 16 项 | D | `.ai/tools/check_compile_smells.py` → `Unresolved reference` | 15 个 `check_*.py` + `check-encoding.py` 全绿，各自带 why；新增门禁应复用风格而非替换 | 某条门禁出现连续假红（应修门禁，不是删门禁） | 删减静态门禁 |
| E01 | 10 处函数级 `@Suppress` 裸 `catch` 豁免 | E | `core/crypto/src/main/java/io/vaultix/crypto/VaultixCrypto.kt` → `TooGenericExceptionCaught` | 18 处生产裸 catch 中 10 处已在函数级登记理由（异常族无法穷举 / 必须一律回滚 / 连 Error 也要 completeExceptionally），是合规的显式豁免 | 新增未登记的裸 catch，或注解被删除 | 批量移除 Suppress 注解 |
| E02 | 2 处 `runBlocking` + `@Suppress("InjectDispatcher")` | E | `data/bitwarden/src/main/java/io/vaultix/data/bitwarden/auth/BitwardenAuthRepository.kt` → `InjectDispatcher` | OkHttp Authenticator 是同步回调框架边界，已 `Dispatchers.IO` + 写明理由 | OkHttp 提供 suspend 版 Authenticator | 去掉 InjectDispatcher 豁免 |
| E03 | `catch (ignored: ...)` 命名豁免 | E | `app/src/main/java/io/vaultix/vaultix/ui/qr/QrScannerContent.kt` → `allowedExceptionNameRegex` | 扫码每帧「未识别到二维码」是常态；变量名 `ignored` 走 detekt `allowedExceptionNameRegex` 放行，注释已写明 | detekt 配置移除该放行规则 | 改扫码异常变量名 |
<!-- NO_FIX_REGISTER_END -->

> 表格共 **A 13 / B 12 / C 12 / D 12 / E 3 = 52 条**。编号按分型分段，新增条目取该段最大序号 +1。

## D.3 逐条证据原文索引

| ID 段 | 证据出处 | 关键原文 |
|---|---|---|
| A01–A07 | `gradle/libs.versions.toml` 注释 16–52 / 79 / 110–172 行 | 「⚠️ composeBom 与 material3 是【硬绑定】，禁止只升级其中一个」「刻意的知情选择」「0.46.0 是分界线」 |
| A08 | `Docs/progress/decisions.md:31` | 「只构建/发布 full；offline flavor 暂停参与构建（用户拍板）…… flavor 定义与 `AppFlavor` 分支代码保留在仓库」 |
| A09 | `gradle/libs.versions.toml:149-152` | 「⚠️ 已知代价（知情选择，2026-09-28 用户拍板）：会拉入 Markdown 解析器 → APK 体积增加」 |
| A11 | `.ai/decisions/条目页-M3-视觉方案.md:332-336` | 「## 附：不做的事（明确排除）」下 4 条 |
| A12 | `.ai/decisions/快速解锁房子化-两级钥匙层级-定稿-part3.md:62-72` | 「§11 被否 / 刻意不学（连同理由，避免新会话重复提议）」 |
| A13 | `.ai/decisions/设置页信息架构-定稿-part4.md:203-204` | 「其余看似『分散』的，都是同一份偏好的第二个入口，不是第二套实装 —— 不动」 |
| B01–B12 | `evidence/standards-scan-2026-11-11.md` §B.2 / §B.5 / §B.6 | 19 类命中统计、误报裁剪表、detekt 阈值对照 |
| B10 附加 | 本轮复核（2026-11-11） | 生产裸 catch 真实代码 **18** 处：函数级已豁免 **10**、未豁免 **8**；8 处全部有降级行为；detekt `swallow_catch = 0` |
| C01 | `Docs/progress/decisions.md:45` | 「收益≈0（Bastion 周期同步服务的是其本地库离线队列，Vaultix 架构无此前提）」 |
| C02 | `Docs/progress/decisions.md:10` | 「代码量小时模块化的收益为负；先按包名组织」 |
| C03 | `.ai/decisions/设置页信息架构-定稿-part4.md:236-238` | 「为什么不做一键迁移：库身份……都绑在文件位置上」 |
| C06 | `.ai/decisions/多库锁模型-定稿-part1.md:46` | 「D6 库间隔离：不做跨库条目」 |
| D01 | `app/src/main/AndroidManifest.xml:53-71` | 「为什么不是『只加排除规则』：allowBackup=false 同时关掉云备份与 D2D 迁移两条通路」 |
| D04 | `core/database/.../VaultixDatabase.kt:47`、`DatabaseModule.kt:37` | `exportSchema = true` / `.addMigrations(` |
| D06 | `data/bitwarden/.../network/BitwardenNetwork.kt:13` | 「retryOnConnectionFailure + pingInterval：反代会静默关闭空闲连接，复用即挂死」 |
| E01 | 生产 `.kt` 全文回溯 | 10 处函数级注解，典型理由「来源可能是 IO / 网络 / HTTP，异常族无法穷举」「连 Error 也要 completeExceptionally，否则等待者永远挂住」「落盘链路异常族无法穷举，且此处必须一律回滚」 |

## D.4 ⚠️ 一个必须避开的陷阱：历史决策会被后续推翻

复核时差点把「**从 Bastion 只搬三类资产，不做文件级搬迁**」当成现行不修项。
`Docs/progress/decisions.md:47` 明确记着后续策略变更：

> **⚠️ 策略变更：Bastion 代码与 UI 分批整体搬入（推翻「不做文件级搬迁」）** —— 用户拍板……

⇒ **判定不修前必须沿时间线确认该决策没有被后续条目推翻**。判据主篇 §2 的「三问」把它列为第 2 问的强制子步骤；
登记表只收**现行有效**的判断，被推翻的旧决策留在 `decisions.md` 当历史，不进表。

同类易错项：`.ai/MEMORY.md` §4 顶部的技术栈口径仍写 `Gradle 9.5.1 / AGP 9.3.2 / Kotlin 2.4.10`，
而 `libs.versions.toml` 实际是 `9.8.2 / 9.5.0 / 2.4.21` —— 这是主报告 §1.4「版本注释漂移」的**第 5 处**，
本轮已顺手订正，不是「不修」项。
