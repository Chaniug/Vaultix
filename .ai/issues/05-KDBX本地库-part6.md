# 05-KDBX本地库 · 第 6 篇（#228 ~）

> 主篇索引：[`05-KDBX本地库.md`](./05-KDBX本地库.md)（含计数规则与重号警告）
> 本篇 4 章 · 覆盖 #228 ~ #231
>
> ⚠️ **本篇的共同形态**：四条的代码都已存在、都能编译、都"看起来是对的" ——
> 它们坏在**某个分支的结局写反了**（失败当成功 / 不适用当不适用之外的另一个 null /
> 一个状态被降级成信息更少的那个 / 功能没挂入口）。
> ⇒ 四条的验收都不是"看代码通顺"，而是**写一条能让主路径精确报红的用例**（含变异验证）。

---

## 228. 🔴 WebDAV 写后读回校验：**读不回来被当成写成功**（2026-11-02，用户点名「安全性」）

- **现象**：PUT 返回 200、紧接着的 `GET` 因为服务端 503 / 网络断开而失败 ⇒ 同步链路继续
  走"上传成功"那条分支：状态记成 `IN_SYNC`、界面不再提示。
  用户以为改动已存进 NAS，其实**那一版到底落没落地完全没人知道**。
- **根因**（一行）：
  ```kotlin
  val readBack = runCatching { read() }
  if (readBack.isSuccess && !readBack.getOrThrow().contentEquals(expected)) { throw … }
  ```
  `isSuccess` 为假时**直接返回**。校验的三态被压成了两态：`true && 相等 → 成功`、
  `true && 不等 → 冲突`、`false → 走了 else，什么都不做`。
- **为什么这条特别危险**：WebDAV 服务器**没有事务**，"写后读回"是这条路径上
  唯一能发现"写没落地"的手段（很多 NAS 不给 ETag，连条件写都用不上，见 `WebDavKdbxFileSource` 的坑 2）。
  唯一的手段失效时，用户**没有任何其他信号**。
- **修法**：三态显式化 —— 读不回来也抛 `KdbxFileConflictException`（与
  `OneDriveKdbxFileSource.verifyReadBack` 完全同款，那条路径早就这么做了）。
  异常里带 `cause`（原始读取异常）：否则"到底是哪一步坏了"就没有线索。
  文案区分「无法读回校验」与「读回校验不一致」—— 两者用户要做的事不同
  （前者重试可能就好了，后者必须比对远端）。
- **验证**：新增 `WebDavKdbxFileSourceReadBackTest`（6 例，手工 `Interceptor` 搭假 WebDAV）。
  **变异验证**：把 `if (actual == null) throw …` 改回 `return` ⇒ 6 例里**精确红 2 例**
  （"读回请求本身失败" + "服务器不给 ETag 时读回失败"），其余 4 例保持绿。
- **教训**：`runCatching` + `isSuccess` 是一个**能把"失败"整段吞掉**的组合。
  凡是"这个结果决定要不要记成功"的地方，`Result` 必须**三个分支都写出来** ——
  `getOrNull()` 为 null 时必须有一条显式的失败路径。

## 229. 🔴 新建 / 加入的网盘库 `syncStatus` 一直是 null ⇒「立即同步」入口凭空消失（2026-11-02，用户点名「onedrive 同步」）

- **现象**：在「添加库 → 网盘」里新建或加入一个 OneDrive / WebDAV 库之后，
  库列表卡片上**没有「立即同步」**这一项；自动同步也不会立刻跑一轮。
  用户只能等下次自动触发，或者退出重进。
- **根因**：`vaults.syncStatus` 的 **null 语义是「该库不适用网盘同步」**
  （`VaultixEntities` 的字段注释与 `KdbxSyncStatus` 的类 KDoc 都写着）。可两条登记路径
  （`KdbxCreateRepositoryImpl.createVault` / `VaultRepositoryImpl.addKdbxVault`）
  **都没有写这一列** ⇒ 它们落成的是"一个没有云端的库"。
  而 `VaultListScreen` 的入口判据是 `onSyncNow = if (vault.syncStatus != null)`。
- **这为什么属于"同步"而不是"UI 小毛病"**：`syncStatus` 同时是
  `localChangedSinceLastSync` 的**唯一判据**（`KdbxCloudSyncStatus.impliesLocalChanges`）。
  留 null ⇒ 用户在首次同步前改的东西会被判成"没改" ⇒ 走"只有远端变"的分支 ⇒
  **那笔改动不会上传**，而且状态会被记成 `IN_SYNC`。
- **修法**：新增纯函数 `KdbxSyncTransitions.forNewlyRegisteredVault(hasCloudSource)`：
  - 网盘来源 ⇒ `PENDING_UPLOAD`。它是**事实**（"有条件推、但还没推过"），
    `needsAnotherRound` 与 `impliesLocalChanges` 都为真 ⇒ 自动上传会来跑一轮，
    首次同步也不会漏推任何改动。**首次同步判出冲突是正确结局**：
    `remoteVersionToken` 为空 ⇒ `remoteChanged` 为真，而 `localChanged` 也为真 ⇒
    远端确实有一份我们没看过的内容，必须由人拍板（绝不能让"条件是空的"变成"允许覆盖"）。
  - 本地 SAF ⇒ `null`（真·不适用；标了会让 UI 在一个没有云端的库上渲染「待上传」角标）。
- **⚠️ 写法上的两个坑（都写进了代码注释）**：
  1. **不能顺手用 `upsert` 一起写**。`upsert` 是整行替换，重复创建同一路径时要把旧行的
     `remoteVersionToken` / `lastSyncedAt` 抄回来，**漏抄一列就是静默清空一列**。
     ⇒ 改用单列 `updateSyncState(id, status, versionToken = existing?.…, syncedAt = existing?.…)`。
  2. `addKdbxVault` **只补空白、绝不覆盖**：它只是"再解锁一次并登记行"，
     用户手上的库可能正处在 `CONFLICT`，重置成 `PENDING_UPLOAD` 会**盖掉一个要人拍板的结论**。
     新建库（`createVault`）可以重置 —— 因为那次确实又写了一遍文件。
- **顺带修掉的判据分散**：「这个 origin 是不是网盘」此前被抄了**三份**
  （协调器的 `hasCloudSource`、建库仓储的 `isCloudOrigin`、而 `addKdbxVault` 压根拿不到）。
  现在下沉到接口默认方法 `KdbxFileSourceResolver.isCloudOrigin(origin)`，三处都委托它。
  这类判据分散的失效方式是"某条路径认为这个库没有云端" —— **没有任何报错**。
- **验证**：`KdbxCreateRepositoryTest` 新增 3 例（网盘必须 PENDING_UPLOAD / 本地必须 null /
  重复创建不得抹掉基线令牌）。⚠️ 第三条第一版是**永远绿**的（装置里旧行没有令牌），
  改成"旧行真的带令牌"之后才有意义 —— **一条永远绿的用例比没有更糟**。

## 230. 降级标记不对称：同一语义在两条路径上两种行为（2026-11-02）

- **现象**：`KdbxSyncOrchestrator.markLocalEdited` 只在状态是 `CONFLICT` 时才不动，
  而 `markRemoteChanged` 拦了 `CONFLICT` / `PENDING_UPLOAD` / `PENDING_UPLOAD_WITH_LOCAL_CHANGES`
  三个。于是"条目刚保存成功、后台又发现远端也变了"这条时间线上：
  本地编辑的标记会把 `PENDING_UPLOAD_WITH_LOCAL_CHANGES` **降级**成 `PENDING_UPLOAD`，
  丢掉"上一份已经推上去过"这条事实。
- **影响范围**：不丢数据（两者都会继续触发下一轮），丢的是**状态精度** ——
  而 UI 与自动上传都靠状态决定"要不要提醒、要不要再跑一轮"。
- **修法**：判据收敛成一个集合 `NON_DOWNGRADABLE = {CONFLICT, PENDING_UPLOAD_WITH_LOCAL_CHANGES}`，
  两个入口共用；`markRemoteChanged` 额外加上 `PENDING_UPLOAD`（"推不上去"比"远端有更新"更紧急，
  不该被盖掉）。
- **⚠️ 刻意**不**一刀切**：`REMOTE_CHANGED` 状态下的本地编辑**必须**能降级成 `PENDING_UPLOAD` ——
  "远端有更新 + 本地也改了"正是冲突的成因，拦住它会让下一次同步判不出冲突
  （`impliesLocalChanges` 为 false ⇒ 走"只有远端变" ⇒ 静默拉取，把本地那笔盖掉）。
- **验证**：`KdbxSyncOrchestratorTest` 新增 4 例，其中 1 例是**反方向**的
  （`远端有更新时本地编辑_必须降级成待上传`）—— 防止后来人按"别降级"这个口号把路封死。

## 231. 没有 `.kdbx` 的打开入口：文件管理器里点 `xxx.kdbx` 说「没有可打开此文件的应用」（2026-11-02，用户点名「功能」）

- **现象**：应用内一切正常；但在「文件」里点开一个 `xxx.kdbx`、或在别的 App 里选「用…打开」，
  系统给出的候选列表里**没有 Vaultix**。用户手里装的就是这个密码管理器，却只能先去
  应用里手动「添加 → 选文件」。
- **根因**：不是逻辑问题，是**入口没挂**。`AndroidManifest.xml` 里 `MainActivity` 只有一个
  `MAIN/LAUNCHER` 过滤器，`<queries>` 里也只有 `MAIN`。
- **修法**（三处缺一不可）：
  1. `MainActivity` 增 `VIEW` 过滤器，`DEFAULT` + `BROWSABLE` 类别，
     `mimeType` 写 `application/x-keepass2` **与** `application/octet-stream` 两个；
  2. `<queries>` 里同样声明这两个 `VIEW` 意图 —— **Android 11 起的包可见性同样约束
     intent resolution**，不声明就有机型的「打开方式」里看不到本应用；
  3. `MainActivity` 加 `launchMode="singleTop"`，并走 `onNewIntent` 更新一条 **Compose 状态**
     （`externalKdbxUri`），不能只读 `intent` —— 与 `exitAfterUnlock` 当年踩的是同一个坑：
     复用 Activity 时 composition 读到的是**旧 intent 对象**。
- **两个 MIME 都写的原因**：KDBX **没有注册过** MIME 类型，各文件管理器说法不一 ——
  多数报 `application/octet-stream`，少数报 `application/x-keepass2`。只写一个就有一半机型匹配不到。
  **有意不写 `*/*`**：那会让本应用出现在任何文件的「打开方式」里。
  ⚠️ 真正的类型判定在打开之后由引擎做（签名 + 版本双重识别，见 `KdbxFormat`）。
- **它不等于「直接解锁」**：外部 URI 只是**临时**读授权（活不过进程重启），
  `file://` 更是在 Android 7+ 直接抛 `FileUriExposedException`。
  ⇒ 只接受 `content:` scheme，且**不跳过**任何一步校验：把 URI 预填进添加页
  （`AddKdbxRoute(externalUri = …)`），用户在那一页确认文件、输主密码，
  顺带 `takePersistableUriPermission` 换长期授权。拿不到持久授权**不算失败** ——
  临时授权足以完成本次添加，下次冷启动读不到时由 `SafKdbxFileSource` 如实报"请重新选择文件"。
- **⚠️ 刻意不做**「判断这个文件已在库里就直接去解锁页」：`vaults.id` 是当初授权的那个 URI 字符串，
  与外部 intent 交付的字符串**常常不相等**（编码差异 / provider 换了 grants）。
  猜错的后果是把用户带到一个**解不开的库**上；走添加页就没有猜错这回事
  （`addKdbxVault` 本来就按"已有同 id"处理重复添加）。
- **未支持**：`ACTION_SEND` + `EXTRA_STREAM`（分享面板「发送到」）。
  部分机型走它只带临时授权、不带 MIME，同一份文件还常被当图片/文本流打开 ⇒
  要额外一套校验，收益不明显。**如实记录为未支持，不假装齐了。**
