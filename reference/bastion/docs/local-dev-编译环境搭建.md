# 本地轻量编译环境搭建（AGP 9 + Gradle 9.5.1 + 沙箱网络受限）

> 用途：改 Kotlin/Java 后先在本地把编译跑绿再推送，避免用 CI 轮次试错；GitHub Actions 只负责构建完整安装包。
> 更新时间：2026-09-05。关联项目规范第 6、7 条。配套脚本：`scripts/localcheck.sh`。

## 0. 快速开始（环境已就绪的机器）

```bash
cd Bastion && /opt/gradle/gradle-9.5.1/bin/gradle :app:compileDebugKotlin
# 或
./scripts/localcheck.sh                      # 默认 compileDebugKotlin
./scripts/localcheck.sh :app:compileDebugUnitTestKotlin   # 连单测一起编
```

## 1. 网络拓扑（本沙箱实测）

| 目标 | 状态 | 用途/备注 |
|---|---|---|
| `maven.aliyun.com` | ✅ 直连，~83MB/s | 所有 maven 依赖 |
| `mirrors.cloud.tencent.com` | ✅ | Gradle 发行版 |
| `github.com:22 (SSH)` | ✅ | git fetch/push（需公钥） |
| `api.github.com:443` | ✅（需 `--resolve 140.82.121.6`，限速 ~20KB/s，并发>2 拒绝） | gh / REST / 大文件 Range 分块续传 |
| `doh.pub` | ✅ | 解析真实 IP |
| `dl.google.com` `maven.google.com` `services.gradle.org` `repo.maven.apache.org` | ❌ SNI 层掐断 | 真实 IP 也无效，必须走镜像 |

## 2. 组件安装

### 2.1 JDK
系统自带 Java 20（`java -version`）即可用——项目 sourceCompatibility/jvmTarget 全部是 17，**无需另装 JDK 17/21**。

### 2.2 Gradle 9.5.1（绕过 wrapper，因 services.gradle.org 被墙）

```bash
curl -L -o /tmp/gradle.zip https://mirrors.cloud.tencent.com/gradle/gradle-9.5.1-bin.zip
sudo unzip /tmp/gradle.zip -d /opt/gradle
/opt/gradle/gradle-9.5.1/bin/gradle --version
```

仓库里的 `gradlew` 仍会尝试联网下载 wrapper，本地直接用 `/opt/gradle/gradle-9.5.1/bin/gradle`。

### 2.3 Maven 镜像（init script，不污染仓库）

写入 `~/.gradle/init.d/aliyun-mirrors.init.gradle`：

```groovy
// 所有 settings 的仓库替换为国内镜像（pluginManagement + dependencyResolutionManagement）
settingsEvaluated { settings ->
    def aliyun = ['https://maven.aliyun.com/repository/gradle-plugin',
                  'https://maven.aliyun.com/repository/google',
                  'https://maven.aliyun.com/repository/public']
    def tencent = ['https://mirrors.cloud.tencent.com/nexus/repository/maven-public/']
    settings.pluginManagement.repositories { r ->
        r.clear(); aliyun + tencent.each { u -> r.maven { url u } }
    }
    settings.dependencyResolutionManagement.repositories { r ->
        r.clear(); (aliyun + tencent + ['https://jitpack.io']).each { u -> r.maven { url u } }
    }
}
```

> 注意：删除过旧版 `/root/.gradle/init.gradle`（其 `allprojects { mavelCentral() }` 语法在 Gradle 9 已失效且拼写错误）。

## 3. Android SDK 手工构造（/opt/android-sdk）

AGP 9.0 用 sdklib 32.3.2，探测机制关键点：
- **`RepoManager` 新路径只认 `package.xml`**（`source.properties`/`build.prop` 仅作 fallback）
- **platform 目录名必须带 minor：`platforms/android-37.0`**（`android-37` 不认）
- build-tools 校验是**逐文件存在性检查**（内容不校验，空文件即可通过）

最终布局：

```
/opt/android-sdk/
├── local.properties 指向的根（Bastion/local.properties: sdk.dir=/opt/android-sdk，已被 .gitignore）
├── licenses/android-sdk-license          # 4 个 hash（见 3.3）
├── build-tools/36.0.0/                   # 3.2 伪造
└── platforms/android-37.0/               # 3.1 真材实料
    ├── android.jar                       # API 37 正式版 58.8MB
    ├── source.properties
    ├── build.prop
    └── package.xml                       # sdklib 自动生成的标准元数据
```

### 3.1 platforms/android-37.0

```properties
# source.properties
Pkg.Revision=1
Platform.Version=37.0
AndroidVersion.ApiLevel=37
AndroidVersion.MinorLevel=0
```

```properties
# build.prop
ro.build.version.sdk=37
ro.build.version.codename=REL
```

**android.jar**（API 37 正式版，**已落地验证**）：从 `Reginer/aosp-android-jar` 仓库下载，共 58,780,395 字节，`unzip -t` 通过，编译实战 OK。单连接限速，用 api.github.com + `Accept: application/vnd.github.raw` + Range 分块（每段 ≤20MB、串行）断点续传：

```bash
S=0; CHUNK=20000000; URL="https://api.github.com/repos/Reginer/aosp-android-jar/contents/android-37/android.jar"
# 循环: curl -r $CUR_S-$((CUR_S+CHUNK-1)) --resolve api.github.com:443:140.82.121.6 -H "Accept: application/vnd.github.raw" >> out
# 完成后 unzip -t 校验
```

临时方案可用 Robolectric 的 `android-all-instrumented-16-robolectric-*.jar`（213MB）改名占位——**有 API 36 缺 API 37 符号的风险**，编译类签名错误时换正式版。

**package.xml（血泪重点）**：
- 根元素必须是 `<repository>`，命名空间 `http://schemas.android.com/repository/android/common/02`（不是 generic/02！手工写对也会被 fallback 重写，见下）
- **推荐做法：不手写。** 放好 source.properties 后触发一次 sdklib 加载，`LegacyLocalRepoLoader` 会自动从 source.properties 解析并**重写标准 package.xml**：

```java
// Probe2.java 思路：AndroidSdkHandler(Paths.get("/opt/android-sdk"), null)
// → rm.loadSynchronously(0, progress, null, null)   ← 必须显式调用，否则 getPackages() 永远为空！
```

### 3.2 build-tools/36.0.0（伪造，实际工具链 maven 拉取）

```
aapt2 aidl d8 dx apksigner zipalign dexdump   # 空文件 chmod +x
core-lambda-stubs.jar checkreturn.jar lib/dx.jar lib/d8.jar lib/apksig.jar
```

> **jar 必须是合法空 ZIP**（含 `META-INF/MANIFEST.MF`），0 字节文件能骗过 sdklib 的存在性校验，但 Gradle artifact transform 会报 `zip file is empty`：
> ```bash
> mkdir -p /tmp/ej/META-INF && printf "Manifest-Version: 1.0\r\n\r\n" > /tmp/ej/META-INF/MANIFEST.MF
> (cd /tmp/ej && zip -r -X /tmp/empty.jar META-INF)   # 再 cp 覆盖上述 5 个 jar
> ```

`source.properties` 示例：`Pkg.Revision=36 / AndroidVersion.ApiLevel=36` 等。`--info` 会依次报缺哪个文件，逐个补。

### 3.3 licenses（缺任何一步都会 "Failed to find target"）

```
8933bad161af4178b1185d1a37fbf41ea5269c55
d56f5187479451eabf01fb78af6dfcb131a6481e
24333f8a63b6825ea9c5514f83c2829b004d1fee
da39a3ee5e6b4b0d3255bfef95601890afd80709    # ← 空文本 license 的 SHA1，兜底
```

## 4. 踩坑清单（每条都真实花费过排查时间）

1. **gradle daemon 脏缓存（最阴）**：`DefaultSdkLoader` 是 JVM 级 static 单例，`AndroidTargetManager.mTargets` 只计算一次并永久缓存。SDK 元数据修复**之前**启动的 daemon，之后无论 RepoManager 重扫多少次（日志显示 "SDK Manager found ... platforms;android-37.0" 也白搭），`getTargetFromHashString` 永远命中缓存的空表 → `Failed to find target with hash string 'android-37.0'`。**修完 SDK 必须 `gradle --stop` 再构建**。用独立 Java 进程（Probe）能成功而 gradle 里失败，就是它。
2. **`Failed to find target with hash string 'android-37.0'`** 的四层原因，逐层排查：
   a. 无 package.xml 且 fallback 失败 → Probe 报 0 包；
   b. fallback 生成的 package.xml 带**空 license**（`<uses-license>`）→ AGP 视为未接受 → 剔除该包 → 删掉 package.xml 里的 `<uses-license .../>`，并在 licenses 里加空文本 hash `da39a3ee...`；
   c. daemon 脏缓存（见第 1 条）；
   d. build-tools 文件缺失 → `--info` 逐个补。
3. **依赖解析缺 `com.microsoft.device.display:display-mask`**：它只发布在微软 Azure Duo feed（`settings.gradle` 已声明 `pkgs.dev.azure.com/...Duo-SDK-Feed...`），国内 maven 镜像全没有。init script 清空仓库时**必须保留这个 feed**（沙箱实测可直连，匿名 GET 200；HEAD 会 401 是正常的）。
4. **build 目录残留**：曾报 `Failed to create MD5 hash for file .../values.xml` → `rm -rf app/build build .gradle` 清掉重来。
5. **Probe 误报**：`RepoManager.getPackages()` 不触发加载，必须先 `loadSynchronously(0, progress, null, null)`，否则永远 0 包。
6. **Probe classpath** 额外需要：`kotlinx-coroutines-core-jvm`、`javax.activation-1.1.1`、`gson`（maven.aliyun.com 均有）。
7. **configuration cache 缓存 SDK 状态**：SDK 元数据变化后失败 → `--no-configuration-cache`（localcheck.sh 已内置自动降级重试）。
8. **远程清单拉取**（dl.google.com addons_list-*.xml SSL 失败）是无害噪音：local 包加载不受影响。
9. zsh 下 glob 无匹配整条命令失败（`ls /tmp/android*.jar*`）；`echo ===` 被解析成命令。
10. 大文件下载：api.github.com 单连接 ~20KB/s，`Range` 分块（≤20MB/段、串行）+ `-H "Accept: application/vnd.github.raw"` 续传。
11. 诊断工具（CFR 反编译 sdklib/AGP 看逻辑，比盲猜快十倍）：`maven.aliyun.com/repository/public/org/benf/cfr/0.152/cfr-0.152.jar`。本项目排障用过的关键类：`LocalRepoLoaderImpl`（扫描/package.xml）、`AndroidTargetManager`（target 表/license 剔除）、`DefaultSdkLoader`（AGP 装包/报错点）、`PlatformTarget`（build.prop 检查）。

## 5. 验证工具链（/tmp 下）

| 文件 | 用途 |
|---|---|
| `cp_base.txt` / `cp_full.txt` | Probe 的 classpath（后者含 coroutines/activation/gson） |
| `Probe2.java` | RepoManager 全量日志加载 + 列包 |
| `Probe3/4.java` | AndroidTargetManager：targets 列表、hash 匹配、license 错误 |
| `Probe5.java` | license 文本/hash/checkAccepted |
| `Probe.java` `Marshal.java` `Marshal2.java` | 早期版本（Probe 未触发 load，已弃用） |

## 6. 当前状态与待办

- ✅ 2026-09-05：`:app:compileDebugKotlin` 本地全链路跑通（含批 1-4 拆分后的代码），BUILD SUCCESSFUL。
- [ ] GitHub fine-grained PAT（Actions: read + Contents: read，仅 bastion 仓库）配置后：`gh auth login --with-token` → `gh run view <id> --log-failed` 直接拉 CI 失败日志。

## 12. 踩坑补充：单测路径需要 core-for-system-modules.jar（2026-09-05）

`:app:compileDebugKotlin` 不需要它，但 `:app:testDebugUnitTest` 会级联 `compileDebugJavaWithJavac` / `compileDebugUnitTestJavaWithJavac`，其 `JdkImageTransform` 要对 `platforms/android-37.0/core-for-system-modules.jar` 执行 `javac --system=none --patch-module=java.base=<jar> module-info.java` 生成 jdk image。**该文件缺失或为空 ZIP 都会在 configuration 解析期报 "Failed to transform"**，`-x compileDebugJavaWithJavac` 也绕不过（transform 在 provider 解析期触发）。

**已验证的构造方法**（无需下载官方 platform zip）：从本地 JDK 的 `java.base.jmod` 提取全部 classes（6723 个），剔除 `module-info.class` 后打包为该 jar（13.6MB）：

```bash
/root/.sdkman/candidates/java/20.fx-zulu/bin/jmod extract --dir /tmp/jsysmod/jbase \
    /root/.sdkman/candidates/java/20.fx-zulu/jmods/java.base.jmod
rm -f /tmp/jsysmod/jbase/classes/module-info.class
cd /tmp/jsysmod/jbase/classes && \
  $JDK/bin/jar --create --file /opt/android-sdk/platforms/android-37.0/core-for-system-modules.jar .
```

注意：与 build-tools 空 jar 不同，**这里必须是真类**（空 ZIP 会让 javac 因 java.base 为空而失败）。
另：本地 JDK 20 跑全量单测有 53 个 `ClassFormatError` 级失败（BitwardenJsonExportTest 等），均为 JDK 20 加载测试类的兼容性问题，**CI（JDK 17）不受影响**，勿据此误判回归。
