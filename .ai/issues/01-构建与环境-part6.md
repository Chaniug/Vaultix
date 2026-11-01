# 01-构建与环境 · 第 6 篇（#137 ~ #138）

> 主篇索引：[`01-构建与环境.md`](./01-构建与环境.md)（含计数规则与重号警告）
> 本篇 2 章 · 4.2KB · 覆盖 #137 ~ #138

## 137. 接力沙箱**跑不了编译与单测**：detekt 全绿 ≠ 代码能编译（2026-10-01）

**⚠️ 这一条比任何一条代码纪律都重要**：它决定了"我交出去的东西到底验证到什么程度"。

- **现象**：沙箱里能跑 detekt CLI（纯 JVM 静态分析），但 `:app:compileFullDebugKotlin` 与
  `:testFullDebugUnitTest` **一次都跑不起来** —— 沙箱**没有 Android SDK**。
- **为什么补不上**：`dl.google.com` 与 `services.gradle.org` 全不可达；
  华为云 Android 镜像**只到 platform-29**，而本仓库要 **API 37** ⇒ 装不全。
  （可通的只有腾讯镜像 `mirrors.tencent.com` / `mirrors.cloud.tencent.com`。）
- **后果**：detekt 只做**静态分析**，查不出
  **编译错误 / 函数签名笔误 / `@Composable` 误用 / import 不存在**。
  ⚠️ 也就是说：那个"新建了文件 + 改了 5 个调用点"的改动，
  **detekt 全绿 与 能编译 之间没有因果关系**。

**⚠️ 2026-10-01 实证（不是推演，是真事）**：同批次推了 8 笔，本地
「detekt 三关全绿 + 6 个自检脚本全绿」，CI 却在 **KSP** 阶段就挂了：

    [ksp] InjectProcessingStep was unable to process
    'KdbxSyncRepositoryImpl(KdbxCloudSyncCoordinator, VaultDao, KdbxSessionFlow)'
    because 'KdbxSessionFlow' could not be resolved.

原因：加构造参数时**漏了 import**（该类型在父包 `io.vaultix.data.repository`，
本文件在其子包 `...repository.kdbx` —— **子包不会自动继承父包的可见性**）。
⇒ **detekt 全绿、自检脚本全绿，照样能推出一个编译不过的东西。**

**纪律（2026-10-01 定）**：
1. 在这种沙箱里交付，**提交信息禁止写「门禁全绿」** —— 只写
   「detekt 三关 + 6 自检脚本通过，**编译 / 单测留给 CI**」。
   （本仓库就吃过一次"自述全绿、实际 detekt 是红"的亏 —— 见 §4 纪律；
   这次的风险方向相反：**真的绿，但绿的东西不代表能编译**。）
1b. **推完之后去盯 CI**（`gh -R <repo> run list`），别推完就算完。
   ⚠️ KSP/编译错误**只在第一个处停** ⇒ 修一个可能冒出下一个，要盯到全绿为止。
   沙箱里 `api.github.com` 同样被劫持，需一并写进 hosts（见 #138）。
2. **detekt 全绿必须自证不是空跑**：改配置里某条规则的阈值到极端值跑一遍，
   确认它真扫到了你新加的文件与 test 源集。
   本次实证：`style.MaxLineLength.maxLineLength = 40` 跑 `data` 关 ⇒ 命中 8880 处，
   其中 `src/test/` 2837 处、新建的 `KdbxAutoUploaderTest.kt` 75 处。
   ⚠️ 探针配置要放在 `default-config` 合并**之后**且**用完即删**（别进仓库）。
   ⚠️ 配置段写错层（把 `MaxLineLength` 写在顶层而不是 `style:` 下）会
   `Run failed with 1 invalid config property` ⇒ **探针自己空跑，还以为证明了全绿**。
3. **交付说明里要明写"没验证到的部分"**，别让下一位以为跑过了。

## 138. GitHub 在沙箱被劫持到 `198.18.0.x`；`~/.ssh/config` 的 `HostName` **必须写 IP**（2026-10-01）

- **现象**：`git clone https://github.com/...` 报 `SSL_ERROR_SYSCALL`；
  SSH 22 端口超时、443 端口 `Connection closed`。
- **根因**：GitHub 域名被解析到保留网段 `198.18.0.x`（`dig github.com` 可直接看到）。
- **解法（三步，缺一不可）**：
  1. 用阿里 DoH 查真实 IP：`curl -s 'https://dns.alidns.com/resolve?name=github.com'`
     （本次：`github.com` → `20.205.243.166`，`ssh.github.com` → `20.205.243.160`）。
  2. 写 `/etc/hosts` **和** `/root/.user_hosts`（后者是本仓库的持久化位置，见 `8.7-环境.md`）。
  3. ⚠️ **`/root/.ssh/config` 的 `HostName` 要直接写 IP，不要写域名** ——
     写域名会**绕过 hosts** 再去解析一次，等于第 2 步白做
     （这条就是 `8.7-环境.md` 记过的"坑 2"，本次又踩了一次并记录在此）。
- **克隆**：若 SSH 一时不通，可走 `https://ghfast.top/https://github.com/OWNER/REPO.git`，
  随后 `git remote set-url origin git@github.com:OWNER/REPO.git` 换回 SSH 再推。
- **验证**：`ssh -T git@github.com` 应回 `Hi <user>! You've successfully authenticated`。

