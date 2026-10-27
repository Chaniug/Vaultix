/*
 * Vaultix — data:kdbx（单测）
 * Copyright (C) 2026 Vaultix contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 */
package io.vaultix.data.kdbx

import app.keemobile.kotpass.constants.BasicField
import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.database.Credentials
import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.modifiers.modifyContent
import app.keemobile.kotpass.database.modifiers.modifyParentGroup
import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.EntryFields
import app.keemobile.kotpass.models.EntryValue
import app.keemobile.kotpass.models.Group
import app.keemobile.kotpass.models.Meta
import com.google.common.truth.Truth.assertThat
import java.util.UUID
import org.junit.Test

/**
 * KDBX **分组（= 文件夹）**写操作的单测（2026-10-27 · 文件夹 CRUD）。
 *
 * ## 这一层最容易出的三类静默错误（每条都配了用例）
 *
 * 1. **"保存成功但什么都没改"**：上游 `modifyGroup` / `removeGroup` 匹配不到 uuid 时
 *    **原样返回、不抛异常**。不显式判存在性，`applied` 会保持 true ⇒ 上层落盘一份内容未变的文件。
 * 2. **重命名把结构弄丢**：若用"新建同名组 + 搬内容"实现改名，组上的自定义图标 /
 *    自定义数据 / 展开状态会全部消失（领域模型没有建模这些）。
 * 3. **删掉的比用户以为的多**：`removeGroup` 是**递归删整棵子树**。
 *    ⇒ `affected` 必须把代价算准，否则确认框会少报。
 */
class KdbxGroupWriteTest {

    // ---------------------------------------------------------------- 新建

    @Test
    fun `在根下建组`() {
        val db = baseDatabase()

        val result = KdbxItemWriter.createGroup(db, parentFolderId = null, name = "工作")

        assertThat(result.applied).isTrue()
        val rootGroups = result.database.content.group.groups
        assertThat(rootGroups.map { it.name }).containsExactly("工作")
        // 返回的 uuid 必须**真的是新组**的（调用方靠它拿新 id）。
        val created = rootGroups.single()
        assertThat(result.entryUuid).isEqualTo(created.uuid)
    }

    @Test
    fun `在子组下建组_父子关系正确`() {
        val db = databaseWith(workGroup(name = "工作"))

        val result = KdbxItemWriter.createGroup(
            db,
            parentFolderId = folderIdOf(workUuid(db)),
            name = "服务器",
        )

        val work = result.database.content.group.groups.single()
        assertThat(work.groups.map { it.name }).containsExactly("服务器")
        // 没有在根上多建一个同名组。
        assertThat(result.database.content.group.groups).hasSize(1)
    }

    /**
     * 父分组不存在 ⇒ **落到根**（与 `createEntry` 同口径），而不是消失。
     *
     * 上游 `modifyGroup` 找不到 uuid 会原样返回 —— 若照着那种行为写，
     * 新组会"保存成功但哪里都没有"。
     */
    @Test
    fun `父分组不存在时落到根_不丢组`() {
        val db = databaseWith(workGroup(name = "工作"))
        val ghostParent = folderIdOf(UUID.randomUUID())

        val result = KdbxItemWriter.createGroup(db, parentFolderId = ghostParent, name = "孤儿")

        assertThat(result.database.content.group.groups.map { it.name })
            .containsExactly("工作", "孤儿")
    }

    // ---------------------------------------------------------------- 重命名

    /**
     * ★ 改名后**子组与条目必须原样保留**。
     *
     * 没有这条，"新建一个同名组再把内容搬过去"那种实现也能通过所有其它用例 ——
     * 而它会静默丢掉组上的自定义图标 / 自定义数据 / 展开状态。
     */
    @Test
    fun `重命名保留子组与条目`() {
        val work = workGroup(name = "工作", withChild = true, withEntry = true)
        val db = databaseWith(work)
        val childUuid = work.groups.single().uuid
        val entryUuid = work.entries.single().uuid

        val result = KdbxItemWriter.renameGroup(db, folderIdOf(work.uuid), "归档")

        val renamed = result.database.content.group.groups.single()
        assertThat(result.applied).isTrue()
        assertThat(renamed.name).isEqualTo("归档")
        // uuid 不变 ⇒ 子组与条目的父引用、以及所有指向它的 id 都还有效。
        assertThat(renamed.uuid).isEqualTo(work.uuid)
        assertThat(renamed.groups.map { it.uuid }).containsExactly(childUuid)
        assertThat(renamed.entries.map { it.uuid }).containsExactly(entryUuid)
    }

    @Test
    fun `重命名不存在的组返回未生效`() {
        val db = databaseWith(workGroup(name = "工作"))

        val result = KdbxItemWriter.renameGroup(db, folderIdOf(UUID.randomUUID()), "新名")

        assertThat(result.applied).isFalse()
        // 库里那个组一个字都没改。
        assertThat(result.database.content.group.groups.single().name).isEqualTo("工作")
    }

    /** 回收站组是**功能组**：改名会让"移入回收站"整体失效（`withRecycleBin` 会再建一个）。 */
    @Test
    fun `回收站组不能改名`() {
        val (db, recycleUuid) = databaseWithRecycleBin()

        val result = KdbxItemWriter.renameGroup(db, folderIdOf(recycleUuid), "改名试试")

        assertThat(result.applied).isFalse()
        assertThat(recycleGroupName(result.database, recycleUuid)).isEqualTo("RecycleBin")
    }

    // ---------------------------------------------------------------- 删除

    /**
     * ★ `affected` 必须把**整棵子树**算准（确认框就靠它，少报 = 用户以为只删了个空组）。
     *
     * 树形：工作 ─┬─ 服务器 ── (1 条)
     *            └─ (2 条)
     * ⇒ 子组 1 个、条目 3 条。
     */
    @Test
    fun `删除组返回整棵子树的代价`() {
        val db = databaseWith(workGroup(name = "工作", withChild = true, withEntry = true, extraEntries = 1))
        val workFolderId = folderIdOf(workUuid(db))

        val result = KdbxItemWriter.removeGroup(db, workFolderId)

        assertThat(result.applied).isTrue()
        assertThat(result.affected).isEqualTo(GroupDeletion(subGroups = 1, entries = 3))
        // 递归删的是整棵子树。
        assertThat(result.database.content.group.groups.map { it.name }).doesNotContain("工作")
    }

    @Test
    fun `删除空组的代价是零`() {
        val db = databaseWith(workGroup(name = "空组"))

        val result = KdbxItemWriter.removeGroup(db, folderIdOf(workUuid(db)))

        assertThat(result.affected).isEqualTo(GroupDeletion(subGroups = 0, entries = 0))
        assertThat(result.database.content.group.groups).isEmpty()
    }

    @Test
    fun `删除不存在的组返回未生效`() {
        val db = databaseWith(workGroup(name = "工作"))

        val result = KdbxItemWriter.removeGroup(db, folderIdOf(UUID.randomUUID()))

        assertThat(result.applied).isFalse()
        assertThat(result.database.content.group.groups).hasSize(1)
    }

    @Test
    fun `回收站组不能删除`() {
        val (db, recycleUuid) = databaseWithRecycleBin()

        val result = KdbxItemWriter.removeGroup(db, folderIdOf(recycleUuid))

        assertThat(result.applied).isFalse()
        assertThat(recycleGroupName(result.database, recycleUuid)).isEqualTo("RecycleBin")
    }

    /** 解析不出 uuid（id 前缀不对 / 乱码）⇒ 未生效，绝不当成"删除成功"。 */
    @Test
    fun `非法 folderId 返回未生效`() {
        val db = databaseWith(workGroup(name = "工作"))

        assertThat(KdbxItemWriter.removeGroup(db, "not-a-folder-id").applied).isFalse()
        assertThat(KdbxItemWriter.renameGroup(db, "", "x").applied).isFalse()
    }

    // ---------------------------------------------------------------- 夹具

    private fun baseDatabase(): KeePassDatabase = KeePassDatabase.Ver4x.create(
        rootName = "Root",
        meta = Meta(name = "GroupWrite"),
        credentials = CREDENTIALS,
    )

    /**
     * 造一个"根下挂着 [groups]"的库。
     *
     * ⚠️ 走 `modifyParentGroup`（上游 API）而不是 `content.copy(group = ...)`：
     * kotpass 的 `content` 在测试里拿不到 `copy`（它不是 data class 的可见成员），
     * 硬拼只会得到一串 `Unresolved reference`。用上游的修改器反而更贴近生产路径。
     */
    private fun databaseWith(vararg groups: Group): KeePassDatabase =
        baseDatabase().modifyParentGroup { copy(groups = groups.toList()) }

    /** 同 [databaseWith]，但回收站已启用（`meta.recycleBinUuid` 指向 [recycle]）。 */
    private fun databaseWithRecycleBin(): Pair<KeePassDatabase, UUID> {
        val recycle = Group(uuid = UUID.randomUUID(), name = "RecycleBin")
        val db = baseDatabase()
            .modifyParentGroup { copy(groups = listOf(recycle)) }
            .modifyContent { copy(meta = meta.copy(recycleBinEnabled = true, recycleBinUuid = recycle.uuid)) }
        return db to recycle.uuid
    }

    /** 一个叫 [name] 的组；可选带一个子组（"服务器"，内含 1 条）与若干直属条目。 */
    private fun workGroup(
        name: String,
        withChild: Boolean = false,
        withEntry: Boolean = false,
        extraEntries: Int = 0,
    ): Group {
        val child = if (withChild) {
            listOf(
                Group(
                    uuid = UUID.randomUUID(),
                    name = "服务器",
                    entries = listOf(entry("数据库密码")),
                ),
            )
        } else {
            emptyList()
        }
        val entries = buildList {
            if (withEntry) add(entry("主账号"))
            repeat(extraEntries) { add(entry("备用$it")) }
        }
        return Group(uuid = UUID.randomUUID(), name = name, groups = child, entries = entries)
    }

    private fun entry(title: String) = Entry(
        uuid = UUID.randomUUID(),
        fields = EntryFields(
            linkedMapOf<String, EntryValue>(
                BasicField.Title.key to EntryValue.Plain(title),
            ),
        ),
    )

    private fun workUuid(db: KeePassDatabase): UUID = db.content.group.groups.single().uuid

    private fun recycleGroupName(db: KeePassDatabase, uuid: UUID): String? =
        db.content.group.groups.firstOrNull { it.uuid == uuid }?.name

    private companion object {
        const val PASSWORD = "master-pw"
        val CREDENTIALS: Credentials = Credentials.from(EncryptedValue.fromString(PASSWORD))
    }
}
