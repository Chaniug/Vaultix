package com.bastion.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.flow.Flow
import com.bastion.app.R
import com.bastion.app.data.Category
import com.bastion.app.data.LocalKeePassDatabase
import com.bastion.app.data.bitwarden.BitwardenFolder
import com.bastion.app.data.bitwarden.BitwardenVault
import com.bastion.app.data.model.StorageTarget
import com.bastion.app.ui.components.MultiStorageTargetPickerBottomSheet
import com.bastion.app.utils.KeePassGroupInfo
import com.bastion.app.utils.isLocalCategoryDescendantPath

@Composable
internal fun PasswordListCategoryChipMenuBottomActions(
    categories: List<Category>,
    keepassDatabases: List<LocalKeePassDatabase>,
    bitwardenVaults: List<BitwardenVault>,
    getBitwardenFolders: (Long) -> Flow<List<BitwardenFolder>>,
    getKeePassGroups: (Long) -> Flow<List<KeePassGroupInfo>>,
    categoryEditMode: Boolean,
    onCategoryEditModeChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onCreateCategory: (() -> Unit)?,
    onMoveCategory: ((Category, Long?) -> Unit)?,
    onMoveCategoryToStorageTarget: ((Category, StorageTarget) -> Unit)?,
    onRenameCategory: ((Category) -> Unit)?,
    onDeleteCategory: ((Category) -> Unit)?,
    categoryActionTarget: Category?,
    onCategoryActionTargetChange: (Category?) -> Unit,
    renameCategoryTarget: Category?,
    onRenameCategoryTargetChange: (Category?) -> Unit,
    renameCategoryInput: String,
    onRenameCategoryInputChange: (String) -> Unit
) {
    var moveCategoryTarget by remember { mutableStateOf<Category?>(null) }
    var moveCategoryTargets by remember { mutableStateOf<List<StorageTarget>>(emptyList()) }
    val availableMoveTargets = moveCategoryTarget?.let { moving ->
        categories.filter { candidate ->
            candidate.id != moving.id && !isLocalCategoryDescendantPath(moving.name, candidate.name)
        }
    }.orEmpty()

    PasswordCategoryActionButtons(
        params = PasswordCategoryActionButtonsParams(
            canCreateCategory = onCreateCategory != null,
            canManageExistingCategories =
                (onMoveCategory != null || onMoveCategoryToStorageTarget != null || onRenameCategory != null || onDeleteCategory != null) &&
                categories.isNotEmpty(),
            categoryEditMode = categoryEditMode,
            onCreateCategory = {
                onCategoryEditModeChange(false)
                onDismiss()
                onCreateCategory?.invoke()
            },
            onToggleEditMode = { onCategoryEditModeChange(!categoryEditMode) }
        )
    )

    PasswordCategoryDialogs(
        params = PasswordCategoryDialogsParams(
            categoryActionTarget = if (onDeleteCategory != null || onRenameCategory != null || onMoveCategory != null || onMoveCategoryToStorageTarget != null) {
                categoryActionTarget
            } else {
                null
            },
            renameCategoryTarget = if (onRenameCategory != null) {
                renameCategoryTarget
            } else {
                null
            },
            renameCategoryInput = renameCategoryInput,
            onDismissCategoryAction = { onCategoryActionTargetChange(null) },
            onStartRename = onRenameCategory?.let {
                { target: Category ->
                    onCategoryActionTargetChange(null)
                    onRenameCategoryTargetChange(target)
                    onRenameCategoryInputChange(target.name)
                }
            },
            onStartMove = if (onMoveCategory != null || onMoveCategoryToStorageTarget != null) {
                { target: Category ->
                    onCategoryActionTargetChange(null)
                    moveCategoryTarget = target
                    moveCategoryTargets = listOf(
                        target.bitwardenVaultId?.let { vaultId ->
                            StorageTarget.Bitwarden(vaultId = vaultId, folderId = target.bitwardenFolderId?.ifBlank { null })
                        } ?: StorageTarget.BastionLocal(categoryId = null)
                    )
                }
            } else {
                null
            },
            moveCategoryTarget = null,
            onDismissMove = { moveCategoryTarget = null },
            availableMoveTargets = emptyList(),
            onConfirmMove = { target, targetParentId ->
                onMoveCategory?.invoke(target, targetParentId)
                moveCategoryTarget = null
                onDismiss()
            },
            onDeleteCategory = onDeleteCategory?.let {
                { target: Category ->
                    onCategoryActionTargetChange(null)
                    onDismiss()
                    it(target)
                }
            },
            onRenameInputChange = onRenameCategoryInputChange,
            onConfirmRename = { target, input ->
                val newName = input.trim()
                if (newName.isNotBlank()) {
                    onRenameCategory?.invoke(target.copy(name = newName))
                    onRenameCategoryTargetChange(null)
                }
            },
            onDismissRename = { onRenameCategoryTargetChange(null) }
        )
    )

    val movingCategory = moveCategoryTarget
    if ((onMoveCategory != null || onMoveCategoryToStorageTarget != null) && movingCategory != null) {
        MultiStorageTargetPickerBottomSheet(
            visible = true,
            selectedTargets = moveCategoryTargets.ifEmpty { listOf(StorageTarget.BastionLocal(null)) },
            lockedTargetKeys = emptySet(),
            onDismiss = { moveCategoryTarget = null },
            categories = availableMoveTargets,
            keepassDatabases = emptyList(),
            bitwardenVaults = bitwardenVaults,
            getBitwardenFolders = getBitwardenFolders,
            getKeePassGroups = getKeePassGroups,
            onSelectedTargetsChange = { moveCategoryTargets = it.takeLast(1) },
            forceMultiSelectionMode = true,
            showSelectionModeToggle = false,
            showBitwardenFolderTargets = false,
            confirmButtonText = stringResource(R.string.move),
            onConfirmSelection = { selectedTargets ->
                val selectedTarget = selectedTargets.lastOrNull()
                    ?: return@MultiStorageTargetPickerBottomSheet
                if (onMoveCategoryToStorageTarget != null) {
                    onMoveCategoryToStorageTarget.invoke(movingCategory, selectedTarget)
                } else {
                    val localTarget = selectedTarget as? StorageTarget.BastionLocal
                        ?: return@MultiStorageTargetPickerBottomSheet
                    onMoveCategory?.invoke(movingCategory, localTarget.categoryId)
                }
                moveCategoryTarget = null
                onDismiss()
            }
        )
    }
}
