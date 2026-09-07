package com.bastion.app.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.Flow
import com.bastion.app.data.Category
import com.bastion.app.data.KeePassOperationBlockReason
import com.bastion.app.data.LocalKeePassDatabase
import com.bastion.app.data.isBastionLocalCategory
import com.bastion.app.data.writeOperationAvailability
import com.bastion.app.data.bitwarden.BitwardenFolder
import com.bastion.app.data.bitwarden.BitwardenVault
import com.bastion.app.data.model.StorageTarget
import com.bastion.app.data.model.dedupedStorageTargets
import com.bastion.app.data.model.normalizedStorageTargets
import com.bastion.app.data.model.withStorageTargetSelected
import com.bastion.app.data.model.withoutStorageTarget
import com.bastion.app.utils.KeePassGroupInfo
import com.bastion.app.utils.buildLocalCategoryPathOptions
import com.bastion.app.utils.decodeKeePassPathForDisplay
import com.bastion.app.utils.localCategoryHierarchyLabel
import com.bastion.app.R

private enum class StoragePickerSelectionMode {
    SINGLE,
    MULTI
}

private sealed interface StoragePickerSource {
    val key: String
    val icon: ImageVector

    data object BastionLocal : StoragePickerSource {
        override val key: String = "bastion"
        override val icon: ImageVector = Icons.Default.Shield
    }

    data class KeePassDatabase(val database: LocalKeePassDatabase) : StoragePickerSource {
        override val key: String = "keepass:${database.id}"
        override val icon: ImageVector = Icons.Default.Key
    }

    data class BitwardenVaultSource(val vault: BitwardenVault) : StoragePickerSource {
        override val key: String = "bitwarden:${vault.id}"
        override val icon: ImageVector = Icons.Default.Cloud
    }
}

private data class StorageTargetChip(
    val target: StorageTarget,
    val label: String,
    val icon: ImageVector,
    val sourceKey: String
)

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class,
    ExperimentalLayoutApi::class
)
@Composable
fun MultiStorageTargetPickerBottomSheet(
    visible: Boolean,
    selectedTargets: List<StorageTarget>,
    lockedTargetKeys: Set<String>,
    categories: List<Category>,
    keepassDatabases: List<LocalKeePassDatabase>,
    bitwardenVaults: List<BitwardenVault>,
    getBitwardenFolders: (Long) -> Flow<List<BitwardenFolder>>,
    getKeePassGroups: (Long) -> Flow<List<KeePassGroupInfo>>,
    onDismiss: () -> Unit,
    onSelectedTargetsChange: (List<StorageTarget>) -> Unit,
    onTargetClicked: ((StorageTarget) -> Unit)? = null,
    forceMultiSelectionMode: Boolean = false,
    /**
     * 是否允许用户把选择清空（跨库迁移语义）。
     *
     * false（默认）—— 既有调用方（Note / Document / Wifi / SSH 等）行为不变：
     *   取消最后一个位置会被兜底回 Bastion 本地，选择永不为空。
     * true —— 密码 / 验证器 / 卡包三页：支持取消所有位置；再次点击已选中的
     *   数据库 chip 即取消；确认按钮在空选择时也可点击（由调用方在保存前做空校验）。
     */
    allowEmptySelection: Boolean = false,
    // 【单一归属】不再提供「复制 / 多位置保存」模式：一条数据只保存在一个库里。
    // 隐藏该切换后，选择器走单选逻辑（selectedTargets<=1 时自动进入 SINGLE），
    // 用户直接点选目标库；切换到另一个库＝迁移（旧位置移除，见 PasswordViewModel 的 staleReplicas 清理）。
    showSelectionModeToggle: Boolean = false,
    showBitwardenFolderTargets: Boolean = true,
    confirmButtonText: String? = null,
    onConfirmSelection: ((List<StorageTarget>) -> Unit)? = null
) {
    if (!visible) return

    // 用窗口实际高度（多窗口/折叠屏下更准确）替代 Configuration.screenHeightDp
    val windowHeightDp = with(LocalDensity.current) {
        LocalWindowInfo.current.containerSize.height.toDp()
    }
    val maxSheetHeight = windowHeightDp * 0.82f
    val minSheetHeight = (windowHeightDp * 0.45f)
        .coerceIn(300.dp, 430.dp)
    val effectiveMinSheetHeight = if (minSheetHeight > maxSheetHeight) {
        maxSheetHeight
    } else {
        minSheetHeight
    }

    val sources = remember(keepassDatabases, bitwardenVaults) {
        buildList {
            add(StoragePickerSource.BastionLocal)
            keepassDatabases.forEach { add(StoragePickerSource.KeePassDatabase(it)) }
            bitwardenVaults.forEach { add(StoragePickerSource.BitwardenVaultSource(it)) }
        }
    }
    val bastionOnlyLabel = stringResource(R.string.vault_bastion_only)
    val categoryNoneLabel = stringResource(R.string.category_none)
    val bitwardenRootLabel = stringResource(R.string.folder_no_folder_root)
    val keepassRootLabel = stringResource(R.string.storage_picker_keepass_root)
    val keepassUnavailableFormat = stringResource(R.string.keepass_connection_status_unavailable_format)
    val keepassMissingLabel = stringResource(R.string.keepass_connection_status_missing)
    val keepassNeedsRefreshLabel = stringResource(R.string.keepass_connection_status_needs_refresh)
    val keepassSyncingLabel = stringResource(R.string.keepass_connection_status_syncing)
    val keepassConflictLabel = stringResource(R.string.keepass_connection_status_conflict)
    val keepassFailedLabel = stringResource(R.string.keepass_connection_status_failed)
    val selectedKeys = selectedTargets.map(StorageTarget::stableKey).toSet()
    val selectedSourceKeys = remember(selectedTargets) {
        selectedTargets.map { it.toSourceKey() }.toSet()
    }
    val bitwardenFoldersByVault = mutableMapOf<Long, List<BitwardenFolder>>()
    bitwardenVaults.forEach { vault ->
        val folders by getBitwardenFolders(vault.id).collectAsState(initial = emptyList())
        bitwardenFoldersByVault[vault.id] = folders
    }
    val keepassGroupsByDatabase = mutableMapOf<Long, List<KeePassGroupInfo>>()
    keepassDatabases.forEach { database ->
        val groups by getKeePassGroups(database.id).collectAsState(initial = emptyList())
        keepassGroupsByDatabase[database.id] = groups
    }
    // 空选择时 primarySourceKey 为空串：所有数据库 chip 都不显示选中，
    // 而不是默认高亮 Bastion（否则"清空"后 UI 仍显示 Bastion 被选，与数据不一致）。
    val primarySourceKey = selectedTargets.firstOrNull()?.toSourceKey().orEmpty()
    val singleModeAllowed = lockedTargetKeys.isEmpty()
    var selectionMode by remember(visible, selectedTargets, singleModeAllowed) {
        mutableStateOf(
            if (forceMultiSelectionMode) {
                StoragePickerSelectionMode.MULTI
            } else if (!singleModeAllowed) {
                StoragePickerSelectionMode.MULTI
            } else if (selectedTargets.size > 1) {
                StoragePickerSelectionMode.MULTI
            } else {
                StoragePickerSelectionMode.SINGLE
            }
        )
    }
    var singleSourceKey by remember(visible, primarySourceKey) { mutableStateOf(primarySourceKey) }
    val activeSourceKeys = remember(visible, selectedTargets) {
        mutableStateListOf<String>().apply {
            addAll(selectedSourceKeys)
        }
    }

    fun rootTargetForSource(source: StoragePickerSource): StorageTarget {
        return when (source) {
            StoragePickerSource.BastionLocal -> StorageTarget.BastionLocal(null)
            is StoragePickerSource.KeePassDatabase -> StorageTarget.KeePass(source.database.id, null)
            is StoragePickerSource.BitwardenVaultSource -> StorageTarget.Bitwarden(source.vault.id, null)
        }
    }

    fun labelForSource(source: StoragePickerSource): String {
        return when (source) {
            StoragePickerSource.BastionLocal -> bastionOnlyLabel
            is StoragePickerSource.KeePassDatabase -> {
                val availability = source.database.writeOperationAvailability()
                if (availability.canOperate) {
                    source.database.name
                } else {
                    val reason = when (availability.reason) {
                        KeePassOperationBlockReason.MISSING_DATABASE -> keepassMissingLabel
                        KeePassOperationBlockReason.NEEDS_REFRESH -> keepassNeedsRefreshLabel
                        KeePassOperationBlockReason.SYNCING -> keepassSyncingLabel
                        KeePassOperationBlockReason.CONFLICT -> keepassConflictLabel
                        KeePassOperationBlockReason.FAILED -> keepassFailedLabel
                        null -> keepassNeedsRefreshLabel
                    }
                    "${source.database.name} · ${keepassUnavailableFormat.format(reason)}"
                }
            }
            is StoragePickerSource.BitwardenVaultSource -> source.vault.displayName ?: source.vault.email
        }
    }

    fun statusDotColorForSource(source: StoragePickerSource): Color? {
        return when (source) {
            StoragePickerSource.BastionLocal -> null
            is StoragePickerSource.KeePassDatabase -> {
                if (source.database.writeOperationAvailability().canOperate) StorageHealthyGreen else null
            }
            is StoragePickerSource.BitwardenVaultSource -> {
                if (source.vault.hasHealthyConnection()) StorageHealthyGreen else null
            }
        }
    }

    fun sourceByKey(key: String): StoragePickerSource {
        return sources.firstOrNull { it.key == key } ?: StoragePickerSource.BastionLocal
    }

    fun buildTargetsForSource(source: StoragePickerSource): List<StorageTargetChip> {
        return when (source) {
            StoragePickerSource.BastionLocal -> buildList {
                add(
                    StorageTargetChip(
                        target = StorageTarget.BastionLocal(null),
                        label = categoryNoneLabel,
                        icon = Icons.Default.FolderOff,
                        sourceKey = source.key
                    )
                )
                buildLocalCategoryPathOptions(
                    categories.filter(Category::isBastionLocalCategory),
                    includeVirtualParents = false
                ).forEach { option ->
                    val category = option.category ?: return@forEach
                    add(
                        StorageTargetChip(
                            target = StorageTarget.BastionLocal(category.id),
                            label = localCategoryHierarchyLabel(option.path),
                            icon = Icons.Default.Folder,
                            sourceKey = source.key
                        )
                    )
                }
            }

            is StoragePickerSource.KeePassDatabase -> buildList {
                add(
                    StorageTargetChip(
                        target = StorageTarget.KeePass(source.database.id, null),
                        label = keepassRootLabel,
                        icon = Icons.Default.FolderOff,
                        sourceKey = source.key
                    )
                )
                keepassGroupsByDatabase[source.database.id].orEmpty().forEach { group ->
                    add(
                        StorageTargetChip(
                            target = StorageTarget.KeePass(source.database.id, group.path),
                            label = decodeKeePassPathForDisplay(group.path),
                            icon = Icons.Default.Folder,
                            sourceKey = source.key
                        )
                    )
                }
            }

            is StoragePickerSource.BitwardenVaultSource -> buildList {
                add(
                    StorageTargetChip(
                        target = StorageTarget.Bitwarden(source.vault.id, null),
                        label = bitwardenRootLabel,
                        icon = Icons.Default.FolderOff,
                        sourceKey = source.key
                    )
                )
                if (showBitwardenFolderTargets) {
                    bitwardenFoldersByVault[source.vault.id].orEmpty().forEach { folder ->
                        add(
                            StorageTargetChip(
                                target = StorageTarget.Bitwarden(source.vault.id, folder.bitwardenFolderId),
                                label = folder.name,
                                icon = Icons.Default.Folder,
                                sourceKey = source.key
                            )
                        )
                    }
                }
            }
        }
    }

    fun updateSingleSource(sourceKey: String) {
        if (!singleModeAllowed) return
        // 再次点击已选中的数据库 = 取消该位置（仅允许清空的页面）。
        // 取消后 singleSourceKey 置空，数据库区全部取消高亮，用户可重新点选任意位置。
        if (allowEmptySelection && sourceKey == singleSourceKey) {
            singleSourceKey = ""
            onSelectedTargetsChange(emptyList())
            return
        }
        singleSourceKey = sourceKey
        val source = sourceByKey(sourceKey)
        onSelectedTargetsChange(listOf(rootTargetForSource(source)))
    }

    fun toggleMultiSource(sourceKey: String) {
        val source = sourceByKey(sourceKey)
        val rootTarget = rootTargetForSource(source)
        if (sourceKey in activeSourceKeys) {
            if (selectedTargets.any { it.toSourceKey() == sourceKey && it.stableKey in lockedTargetKeys }) {
                return
            }
            activeSourceKeys.remove(sourceKey)
            onSelectedTargetsChange(
                selectedTargets
                    .filterNot { it.toSourceKey() == sourceKey }
                    // 允许清空的页面：取消后不做兜底，空列表合法；
                    // 既有调用方仍走 normalizedStorageTargets 保持原兜底行为。
                    .let { if (allowEmptySelection) it.dedupedStorageTargets() else it.normalizedStorageTargets() }
            )
        } else {
            activeSourceKeys.add(sourceKey)
            onSelectedTargetsChange(
                selectedTargets.withStorageTargetSelected(
                    rootTarget,
                    fallbackIfEmpty = !allowEmptySelection
                )
            )
        }
    }

    fun updateSelectionMode(newMode: StoragePickerSelectionMode) {
        if (forceMultiSelectionMode) return
        if (selectionMode == newMode) return
        if (newMode == StoragePickerSelectionMode.SINGLE && !singleModeAllowed) return
        selectionMode = newMode
        if (newMode == StoragePickerSelectionMode.SINGLE) {
            val retained = selectedTargets.firstOrNull()
            if (retained == null && allowEmptySelection) {
                // 允许清空的页面：空选择切回单选时保持空，不兜底出 Bastion 本地。
                singleSourceKey = ""
                onSelectedTargetsChange(emptyList())
            } else {
                val fallback = retained ?: StorageTarget.BastionLocal(null)
                singleSourceKey = fallback.toSourceKey()
                onSelectedTargetsChange(listOf(fallback))
            }
        } else {
            activeSourceKeys.clear()
            activeSourceKeys.addAll(selectedSourceKeys)
        }
    }

    val folderTargets = remember(
        selectionMode,
        singleSourceKey,
        activeSourceKeys.toList(),
        categories,
        keepassDatabases,
        bitwardenVaults,
        bitwardenFoldersByVault,
        keepassGroupsByDatabase
    ) {
        if (selectionMode == StoragePickerSelectionMode.SINGLE) {
            buildTargetsForSource(sourceByKey(singleSourceKey))
        } else {
            activeSourceKeys
                .map(::sourceByKey)
                .flatMap(::buildTargetsForSource)
                .distinctBy { it.target.stableKey }
        }
    }
    val groupedFolderTargets = remember(
        sources,
        activeSourceKeys.toList(),
        categories,
        keepassDatabases,
        bitwardenVaults,
        bitwardenFoldersByVault,
        keepassGroupsByDatabase
    ) {
        val activeSourceKeySet = activeSourceKeys.toSet()
        sources
            .filter { it.key in activeSourceKeySet }
            .associateWith(::buildTargetsForSource)
    }

    BastionModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = effectiveMinSheetHeight, max = maxSheetHeight)
                .animateContentSize(
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    )
                )
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = stringResource(R.string.vault_select_storage),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 6.dp)
            )

            if (showSelectionModeToggle && !forceMultiSelectionMode) {
                StoragePickerModeToggleGroup(
                    selectionMode = selectionMode,
                    singleEnabled = singleModeAllowed,
                    onModeSelected = ::updateSelectionMode
                )
            }

            if (!singleModeAllowed) {
                Text(
                    text = stringResource(R.string.storage_picker_edit_multi_append_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            StorageSelectorSectionTitle(text = stringResource(R.string.category_selection_menu_databases))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                sources.forEach { source ->
                    val selected = if (selectionMode == StoragePickerSelectionMode.SINGLE) {
                        source.key == singleSourceKey
                    } else {
                        source.key in activeSourceKeys
                    }
                    BastionExpressiveFilterChip(
                        selected = selected,
                        onClick = {
                            if (selectionMode == StoragePickerSelectionMode.SINGLE) {
                                updateSingleSource(source.key)
                            } else {
                                toggleMultiSource(source.key)
                            }
                        },
                        label = labelForSource(source),
                        leadingIcon = source.icon,
                        statusDotColor = statusDotColorForSource(source)
                    )
                }
            }

            StorageSelectorSectionTitle(text = stringResource(R.string.category_selection_menu_folders))
            if (selectionMode == StoragePickerSelectionMode.SINGLE) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    folderTargets.forEach { chip ->
                        FolderTargetChip(
                            chip = chip,
                            selectedTargets = selectedTargets,
                            selectedKeys = selectedKeys,
                            targetLocked = chip.target.stableKey in lockedTargetKeys,
                            sourceHasLockedTarget = selectedTargets.any {
                                it.toSourceKey() == chip.sourceKey &&
                                    it.stableKey in lockedTargetKeys
                            },
                                    singleMode = true,
                                    singleModeAllowed = singleModeAllowed,
                                    allowEmptySelection = allowEmptySelection,
                                    onSelectedTargetsChange = onSelectedTargetsChange,
                                    onTargetClicked = onTargetClicked
                                )
                    }
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    groupedFolderTargets.forEach { (source, chips) ->
                        if (chips.isEmpty()) return@forEach
                        SourceFolderGroup(
                            title = labelForSource(source),
                            icon = source.icon
                        ) {
                            chips.forEach { chip ->
                                FolderTargetChip(
                                    chip = chip,
                                    selectedTargets = selectedTargets,
                                    selectedKeys = selectedKeys,
                                    targetLocked = chip.target.stableKey in lockedTargetKeys,
                                    sourceHasLockedTarget = selectedTargets.any {
                                        it.toSourceKey() == chip.sourceKey &&
                                            it.stableKey in lockedTargetKeys
                                    },
                                    singleMode = false,
                                    singleModeAllowed = singleModeAllowed,
                                    allowEmptySelection = allowEmptySelection,
                                    onSelectedTargetsChange = onSelectedTargetsChange,
                                    onTargetClicked = onTargetClicked
                                )
                            }
                        }
                    }
                }
            }

            if (onConfirmSelection != null) {
                FilledTonalButton(
                    onClick = { onConfirmSelection.invoke(selectedTargets) },
                    // 允许清空的页面（allowEmptySelection=true）：空选择也可确认，
                    // 由调用方在保存前做空校验并提示；其余调用方维持"必须至少选一个"。
                    enabled = allowEmptySelection || selectedTargets.isNotEmpty(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                ) {
                    Text(confirmButtonText ?: stringResource(R.string.confirm))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun StoragePickerModeToggleGroup(
    selectionMode: StoragePickerSelectionMode,
    singleEnabled: Boolean,
    onModeSelected: (StoragePickerSelectionMode) -> Unit
) {
    Row(
        modifier = Modifier.wrapContentWidth(Alignment.Start),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)
    ) {
        StoragePickerModeButton(
            // 「移动」＝只存在选中的这一个位置（唯一归属）
            label = stringResource(R.string.storage_picker_mode_move),
            selected = selectionMode == StoragePickerSelectionMode.SINGLE,
            enabled = singleEnabled,
            position = 0,
            lastIndex = 1,
            onClick = { onModeSelected(StoragePickerSelectionMode.SINGLE) }
        )
        StoragePickerModeButton(
            // 「复制」＝选中的每个位置各存一份（同一条目的副本）
            label = stringResource(R.string.storage_picker_mode_copy),
            selected = selectionMode == StoragePickerSelectionMode.MULTI,
            enabled = true,
            position = 1,
            lastIndex = 1,
            onClick = { onModeSelected(StoragePickerSelectionMode.MULTI) }
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun StoragePickerModeButton(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    position: Int,
    lastIndex: Int,
    onClick: () -> Unit
) {
    ToggleButton(
        checked = selected,
        onCheckedChange = { onClick() },
        enabled = enabled,
        modifier = Modifier
            .heightIn(min = 40.dp)
            .sizeIn(minWidth = 56.dp)
            .semantics { role = Role.RadioButton },
        shapes = when (position) {
            0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
            lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
            else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
        }
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1
        )
    }
}

@Composable
private fun SourceFolderGroup(
    title: String,
    icon: ImageVector,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                content()
            }
        }
    }
}

@Composable
private fun FolderTargetChip(
    chip: StorageTargetChip,
    selectedTargets: List<StorageTarget>,
    selectedKeys: Set<String>,
    targetLocked: Boolean,
    sourceHasLockedTarget: Boolean,
    singleMode: Boolean,
    singleModeAllowed: Boolean,
    allowEmptySelection: Boolean,
    onSelectedTargetsChange: (List<StorageTarget>) -> Unit,
    onTargetClicked: ((StorageTarget) -> Unit)?
) {
    val targetKey = chip.target.stableKey
    val selected = if (singleMode) {
        selectedTargets.firstOrNull()?.stableKey == targetKey
    } else {
        targetKey in selectedKeys
    }

    BastionExpressiveFilterChip(
        selected = selected,
        onClick = {
            if (singleMode) {
                // 允许清空的页面：再次点击已选中的具体位置 = 取消（单选下点同一个 chip）。
                if (allowEmptySelection && selected &&
                    selectedTargets.firstOrNull()?.stableKey == targetKey
                ) {
                    onSelectedTargetsChange(emptyList())
                    return@BastionExpressiveFilterChip
                }
                onSelectedTargetsChange(listOf(chip.target))
                onTargetClicked?.invoke(chip.target)
            } else {
                val updatedTargets = if (selected) {
                    if (targetLocked) {
                        selectedTargets
                    } else {
                        selectedTargets.withoutStorageTarget(
                            chip.target,
                            allowScopeFallback = !allowEmptySelection
                        )
                    }
                } else if (sourceHasLockedTarget) {
                    selectedTargets
                } else {
                    selectedTargets.withStorageTargetSelected(
                        chip.target,
                        fallbackIfEmpty = !allowEmptySelection
                    )
                }
                onSelectedTargetsChange(updatedTargets)
                if (!selected) {
                    onTargetClicked?.invoke(chip.target)
                }
            }
        },
        label = chip.label,
        leadingIcon = chip.icon
    )
}

@Composable
private fun StorageSelectorSectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold
    )
}

private fun StorageTarget.toSourceKey(): String {
    return when (this) {
        is StorageTarget.BastionLocal -> "bastion"
        is StorageTarget.KeePass -> "keepass:$databaseId"
        is StorageTarget.Bitwarden -> "bitwarden:$vaultId"
    }
}

private val StorageHealthyGreen = Color(0xFF22C55E)

private fun BitwardenVault.hasHealthyConnection(): Boolean {
    return isConnected && !encryptedRefreshToken.isNullOrBlank()
}
