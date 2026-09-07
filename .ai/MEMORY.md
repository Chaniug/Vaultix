# Vaultix 项目长期笔记

## 产品定位（2026-09-07 用户拍板）
- **Bitwarden 优先的客户端**，对标 **Keyguard 路线**（区别于 Monica / Bastion 的"本地优先·聚合"）
- 支持 2 种库：Bitwarden 云端（主）+ KDBX 本地（次）
- 用户理由：Bitwarden 是目前主流
- 注意：不采纳 Bastion 的"私有本地库"——用开放标准的 KDBX 承担本地角色，避免用户数据锁定

## 架构约定
- 领域模型以 **Bitwarden `Cipher` 为规范模型（canonical）**，KDBX 为**降级映射端**
- 保真度三级：**无损** / **约定承载**（落 `Vaultix.*` 自定义字段）/ **有损**
  - 强制往返测试：`Bitwarden → VaultItem → KDBX → VaultItem → Bitwarden`
  - 有损字段必须在 `VaultCapabilities` 登记，UI 提示，**禁止静默丢弃**
- 对齐 Bitwarden **官方客户端实际行为**（非仅文档）：
  - KDF salt 为 UTF-8 编码字符串
  - `encKeyValidation` 明文为 UUID（非 `"Bitwarden"` 字符串）
  - Vaultwarden 忽略 `sinceRevisionDate`（增量同步会空转）

## 里程碑（2026-09 已调换 M1/M2）
| 期 | 内容 | 状态 |
|---|---|---|
| M0 | 基础骨架 + core:crypto | ✅ 已完成 |
| **M1** | **Bitwarden 同步** | 下一步 |
| **M2** | **KDBX 引擎** | 后置 |
| M3 | 平台集成（Autofill / 安全中心） | |
| M4/M5 | 发布准备 / 1.0 | |

## 技术栈（2026-09-07 升级并验证出 APK）
Gradle 9.5.1 / AGP 9.3.2 / Kotlin 2.4.10 / KSP 2.3.11 / Hilt 2.60.1 / compileSdk 37 / JDK 17

⚠️ **硬约束**
- Hilt 与 Kotlin 强绑定：Kotlin 2.4.x → Hilt **2.60.1**；AGP 9 要求 Hilt ≥ 2.59
- AGP 9 禁止 apply `kotlin-android`（内置 Kotlin 会冲突）
- version catalog 别名避免 `kotlin-` 前缀（撞内置 `kotlin {}` DSL），KGP 用别名 `kgp`
- 类型安全项目访问器需 `enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")`
- 本机 SDK 在 `C:\AndroidSDK`，**无 android-36**，compileSdk 必须 ≥ 37

## 代码来源与协议
- Vaultix = **GPL-3.0**（2026-09-07 从 MIT 切换）
- 参考/搬运源 Bastion（GPL-3.0，Copyright 2025 JoyinJoester）
- 搬运文件**必须**带溯源声明；Vaultix 原创部分需标注（如 `AesGcm.kt`）
- Keyguard 为"源码仅个人使用授权"，**禁止复用代码**，仅可参考 UI 交互

## 首次启动流程（方向，未落地）
主推连接 Bitwarden → 次选创建本地 KDBX → 兜底"稍后在设置里连接"
- 需区分**库凭据**（Bitwarden 账户密码 / KDBX 主密码）与**应用锁**（PIN / 生物识别）
- 建议 M2 后两入口并列，契合"聚合"能力

## 目标平台与发布（2026-09-07 确认）
- **Android 17 = API 37**：minSdk 26 / targetSdk 37 / compileSdk 37
- 只出 arm64-v8a；**本地只小编译不出包，GitHub Actions 负责出包**
  （main → debug preview Release；rele 分支或 v* tag → 签名 release APK）
- CI 脚本 `.github/scripts/ensure-android-sdk.sh` 必须装 `platforms;android-37`
  （原为 36，与 compileSdk=37 冲突会导致 CI 失败；已修，并保持 LF 换行）
- 本机 SDK `C:\AndroidSDK` 有 35 / 37 / 37.0，**无 android-36**，故 compileSdk 必须 ≥ 37

## 协作文件夹（均已入库，便于 AI 接力）
- `Docs/progress/`：environment（环境+构建策略）/ current-status（进度快照）/
  next-steps（下一步清单）/ decisions（决策记录）
- `.ai/`：MEMORY.md（长期约定）/ ISSUES.md（踩坑：现象-根因-解法）/ SESSION-*.md（会话日志）
- 两者内容同步自 `.workbuddy/memory/`
