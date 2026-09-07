# Android 端优化与杂项（内部交接）

> 汇总 UI/视觉优化、预览功能整合、开发者设置清理、CI/代码治理杂项、以及与 Monica 等上游的对照。
> 最后整理：2026-08-27。

---

## 1. UI / 视觉优化

- `安卓端UI优化计划.md`：系统性「正确性 / 维护性 / 一致性 / 体验」优化（非视觉重设计），lint 清理已全部完成。状态：待确认。
- `安卓端界面视觉优化计划.md`（P4 视觉打磨）：B1 + B2 已实施并合 main，待真机验收。
- `安卓端预览功能整合与首页分割线清理.md`：合并"毕业"多项预览开关 + 移除分类文件夹 Tab 中"新建分类"按钮上方孤悬分割线。
- `安卓端可拖拽底栏与滚动隐藏FAB迁移到专属二级页.md`：把 `useDraggableBottomNav`/`hideFabOnScroll` 两个开关从实验弹窗迁入「界面与布局」专属二级页并做 UI 风格对齐；附 CI #415 编译失败修复记录。
- `安卓端BastionPlus全链路移除记录.md`：QuickSetup 向导中早已无用的"Bastion Plus 已开启"纯文本页清理（PLUS 早已转"免费已激活"展示）。

## 2. 开发者设置与冗余清理

- `开发者设置冗余功能清理记录.md`：开发者设置冗余功能清理（代码清理已完成，待 dev CI 验收合 main）。设备背景：荣耀 MagicOS / Android 17。
- `5e9a7e1a`：清理冗余与过期设置，精简死代码。
- `安卓端PluralsCandidate细化方案.md`：P3-d 已评估 → **建议跳过**（前提失效，2026-08-15 复核）。

## 3. 首次引导与体验

- `31936346`：首次引导从 9 步精简到 4 步（方案 A）。
- `879dc2e9`：首次引导移除语言选择卡片（面向国内中文用户，默认中文）。
- `cc0aebf2`：修复首次登录输入密码卡顿（`isMasterPasswordSet` 缓存 + `remember`）。

## 4. WebDAV / 存储

- `bfa33298`：WebDAV 原子条件写入（If-Match）+ 密钥文件内部副本与指纹。
- `2ea7a95a`：降低 kdbx 远端同步频率。
- `bf4ee86fd`：修复 `parseLinkedAppBindings` split 分隔符类型混用。
- `4237a576`：仅手动 sync 弹"正在同步"对话框 + 切后台自动抑制。
- `BastionDocs/docs/02.配置/02.相关文档/` 下有「WebDAV 备份格式规范」「本地存储与加密技术文档」对外技术文档。

## 5. Bastion 工具多格式存储

- `b0cb6b99`/`7b5fa239`/`9de65a2a`/`3d0e9243`/`360c15d5`：Bastion 工具多格式存储（JSON/CSV 后端），Phase 0 数据模型与 UI 改名 → Phase 1 JSON/CSV 后端 → 收敛格式、移除 CSV 创建入口、JSON 加区分文案。

## 6. 清空数据 / TOTP

- `83de32e0`：修复「清空所有数据」失效 + TOTP 条目消失 + 云端/本地重复 TOTP 双显示（K2 suspend lambda 跨文件解析缺陷）。
- `d4003067`：保留 `collapseDuplicateBoundStoredTotps` 的 `val key` 形态通过回归守卫。
- `47d192a4`/`dd5e8b83`：修复 `onClearAllData` 中 `this@MainActivity` 标签解析、清空数据编译失败。

## 7. CI / 工作流 / 代码治理

- `workflow-consolidation-record.md`：删除 `Android-Preview.yml` 并入 `main.yml`；`deploy-pages`/`Desktop-Build` 触发分支收窄为仅 `[dev]`；`main.yml` 合并 main 时不再重复构建。
- `codeql-triage-plan.md`：CodeQL 告警 triage（56 陈旧残留 + 44 当前逐条判定）；`codeql.yml` 仅扫 `dev`。
- `guard-test-style-guide.md` + `guard-test-validation/`：守卫测试写法规范与校验脚本（见 `架构与路线图.md` §2）。
- `42fff881`：CodeQL Action v3→v4 升级。

## 8. 上游对照与参考

- `bitwarden-sync-monica-comparison.md`：对照 Monica-Pass/Monica 的 Bitwarden 同步修复（见 `bitwarden同步与密码库生态.md` §7）。
- `azure-app-registration-guide.md`：自注册 Azure 应用对接 OneDrive（见 `自动填充与浏览器兼容.md` §4）。

## 9. 待办 / 未决

- 中文品牌 PNG 图标资产打包（见 `自动填充与浏览器兼容.md` §5）。
- UI 优化计划（P4 之外）与视觉打磨待真机验收。
- 本地数据库统一同步架构重构（见 `架构与路线图.md` §6，待确认）。
