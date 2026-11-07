# 2026-11-07 · 第九批：配色事故 / 闪退止血 / CI 往返

> 主篇 [`SESSION-2026-11-07.md`](./SESSION-2026-11-07.md) 已 288 行 / 19KB，
> 按篇末指针另开本篇。本篇只写**主篇没有的事实**，不复制 §7–§9。
> 遵守 [`conventions/8.9-文档分篇.md`](./conventions/8.9-文档分篇.md) §7。

---

## §1 密码高亮：两轮翻车与最终修法

**回答「为什么深色模式下密码强调块成了刺眼亮粉」**

- 原实现 `EmphasisBlock` = `primaryContainer.copy(alpha = 0.30f)` 叠在
  `surfaceContainerHighest` 卡片上，用户反馈「深色一点的边框，很生硬」。
- 第八批改成**不透明** `tertiaryContainer` 具名色档 —— 判断失误：
  项目开了 Material You 动态取色，深色主题下 `tertiaryContainer` 可能由壁纸
  生成亮粉紫，整块高亮直接糊成一坨（用户真机截图：「灾难」「颜色和卡片完全失误」）。
- 最终改 `tertiaryContainer.copy(alpha = 0.10f)`：仍是**不同色相**（不是 8.4
  禁止的同色叠 alpha），但压到与卡片几乎同明度，只留一层可辨暖调。
- ⚠️ `FieldIcon` 徽章也用 `tertiaryContainer`，但**刻意不动**：小面积亮色是点缀，
  截图里没问题。批量替换时断言 `count=1` 挡住了误改第二处（该串出现 2 次）。

- 判据：**动态取色下，任何「大面积」容器色都不能假设它是低饱和的**；
  彩色只放在小面积点缀，大面积强调必须压 alpha 或退回 surface 色阶。

---

## §2 文件夹删除闪退：止血，根因未定

**回答「为什么 KDBX 删文件夹 100% 闪退，改到哪一步」**

- 真机取证：`adb logcat -d` 找到 `18:46:18 Force removing ActivityRecord …
  app died, no saved state`（pid 18657），18:47:05 进程重启 ⇒ 崩溃属实。
  但 crash buffer 只有一行系统哈希，**没有 Java 堆栈**，不能假称已定位根因。
- 代码链路全被 `runCatching` 包着：`FolderRepositoryImpl.deleteFolder` →
  `KdbxItemRepository.deleteFolder` → `Kdbx.deleteFolder`；UI 侧
  `state.rows.firstOrNull` 也有 null 守卫。肉眼没找到确定抛出点。
- 找到一个真实防线缺口：`FoldersViewModel.launchWrite` 只有 `try/finally`、
  **没有 catch**。KDoc 写着「意外不该把页面卡在忙碌态」，但 finally 只清 busy，
  `block()` 若直接抛异常会穿出 `viewModelScope.launch` 成为未捕获异常 ⇒ 杀进程。
- 修复：`runCatching { block() }` 包住，意外转 `UiEvent.Failed`（Snackbar 可见），
  `CancellationException` 显式重抛避免误吞取消；finally 仍清 busy。
- ⚠️ **这是止血 + 暴露错误信息，不等于根治**：装新包后请复现一次删除，
  若不再闪退会看到 Snackbar 里的错误原因；若仍闪退，需重新起 logcat 抓
  `FATAL EXCEPTION` 堆栈（或确认是否 native / OOM 类死亡）。

- 判据：**「只有 finally 没有 catch」不是异常兜底**；ViewModel 的写操作外壳
  必须同时保证「状态复位」与「异常不越出协程」这两件独立的事。

---

## §3 CI 三次往返

**回答「这一批为什么推了四个 commit 才绿」**

| 轮次 | run | 结果 |
|---|---|---|
| `04101de0` | `42365066619` | 单测 577 例 1 失败：`SyncBadgeTest.kt:70` 仍断言 `PENDING_UPLOAD -> null`，而本批已映射成 `UPLOADING` |
| `f84e3e24` | `42365875071` | detekt `TooGenericExceptionCaught`：`catch (Throwable)` 过宽 |
| `1d7c17c0` | `42366019039` | detekt 仍红：连 `catch (Exception)` 都判过宽 |
| `0d0540af` | `42366185744` | ✅ 全链路绿（3m9s、单测 1639 例 0 失败、编译通过） |

- 判据：① 给枚举加值后**主动搜所有 `when` 与旧断言**（Kotlin 穷尽 when 会编译报错，
  但 `Truth.assertThat(...).isNull()` 这类语义断言不会）；
  ② detekt 的 `TooGenericExceptionCaught` 连 `Exception` 都禁 ⇒ 用项目同款
  `runCatching`，并显式重抛 `CancellationException`；
  ③ 前置门禁一红，后面的编译 / 单测一步都没跑（§8.6.0 的老规律再次复现）。

---

## §4 出包与验收清单

**回答「装哪个包、装完验什么」**

- Preview：`Vaultix v0.11.2-dev-0d0540a · Preview`（滚动 tag `preview`，
  19:01 发布），资产 `app-full-debug.apk` 37,323,879 bytes。
  稳定版 `v0.11.2` Latest 未动，`rele` 未推，本地 main `0/0`。
- 装完请验：① 新建/编辑条目时密码强调块是否已与卡片协调（不再亮粉大块）；
  ② 保存后本地立刻出结果、详情页顶栏「转动 → 小云」；
  ③ ⋮ 菜单「正在上传改动」；④ **KDBX 文件夹删除**是否还闪退，
  若变成 Snackbar 请把那句错误文案告诉我。
- 仍未修（主篇 §9）：B 条目页 ⋮ 同步按钮只接 Bitwarden 编排器导致 KDBX 空转；
  C 一次远端拉取约两次整库下载 + 两次 KDF 导致偏慢。两者都需单独一批。
