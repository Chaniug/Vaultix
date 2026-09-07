package com.bastion.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import com.bastion.app.R
import com.bastion.app.data.Category
import com.bastion.app.data.PasswordListQuickFilterItem
import com.bastion.app.data.PasswordListTopModule
import com.bastion.app.data.PasswordPageContentType
import com.bastion.app.viewmodel.CategoryFilter

internal data class PasswordListCategoryChipMenuReorderableModulesParams(
    val orderedModules: List<PasswordListTopModule>,
    val quickFiltersExpanded: Boolean,
    val onQuickFiltersExpandedChange: (Boolean) -> Unit,
    val foldersExpanded: Boolean,
    val onFoldersExpandedChange: (Boolean) -> Unit,
    val categoryEditMode: Boolean,
    val menuWidth: Dp,
    val quickFilterOrder: List<PasswordListQuickFilterItem>,
    val quickFilterMeasuredSizes: MutableMap<PasswordListQuickFilterItem, IntSize>,
    val quickFilterChipState: PasswordQuickFilterChipState,
    val quickFilterChipCallbacks: PasswordQuickFilterChipCallbacks,
    val onQuickFilterOrderCommitted: (List<PasswordListQuickFilterItem>) -> Unit,
    val currentFilter: CategoryFilter,
    val quickFolderShortcuts: List<PasswordQuickFolderShortcut>,
    val categories: List<Category>,
    val onRequestCategoryAction: (Category) -> Unit,
    val onSelectFilter: (CategoryFilter) -> Unit,
    val moduleDisplayOffset: (PasswordListTopModule) -> Offset,
    val isActiveDragModule: (PasswordListTopModule) -> Boolean,
    val onModuleBoundsChanged: (PasswordListTopModule, Rect) -> Unit,
    val onDragStart: (PasswordListTopModule) -> Unit,
    val onDragCancel: (PasswordListTopModule) -> Unit,
    val onDragEnd: (PasswordListTopModule) -> Unit,
    val onDragDelta: (PasswordListTopModule, Offset) -> Unit,
    val isExpandedStateLoaded: Boolean = true,
    // 方案 A：普通模式由顶部 Tab 驱动，仅渲染选中 Tab 对应模块并隐藏折叠头；
    // 编辑模式 showHeaders=true，保留折叠头作为拖拽排序抓手、两个模块都渲染。
    val activeTab: Int = 1,
    val showHeaders: Boolean = false,
)

@Composable
internal fun PasswordListCategoryChipMenuReorderableModules(
    params: PasswordListCategoryChipMenuReorderableModulesParams
) {
    params.orderedModules.forEach { module ->
        val moduleTab = when (module) {
            PasswordListTopModule.QUICK_FILTERS -> 1
            PasswordListTopModule.QUICK_FOLDERS -> 2
        }
        // 普通模式只渲染选中 Tab 的模块；编辑模式（showHeaders）渲染全部以便排序。
        if (!params.showHeaders && params.activeTab != moduleTab) return@forEach
        key(module) {
            val headerVisible = params.showHeaders
            val uiStateExpanded = if (module == PasswordListTopModule.QUICK_FILTERS) {
                params.quickFiltersExpanded
            } else {
                params.foldersExpanded
            }
            val sectionParams = PasswordReorderableTopModuleSectionParams(
                title = stringResource(
                    if (module == PasswordListTopModule.QUICK_FILTERS) {
                        R.string.category_selection_menu_quick_filters
                    } else {
                        R.string.category_selection_menu_folders
                    }
                ),
                expanded = if (headerVisible) uiStateExpanded else true,
                onExpandedChange = { expanded ->
                    if (module == PasswordListTopModule.QUICK_FILTERS) {
                        params.onQuickFiltersExpandedChange(expanded)
                    } else {
                        params.onFoldersExpandedChange(expanded)
                    }
                },
                headerVisible = headerVisible,
                categoryEditMode = params.categoryEditMode,
                moduleDisplayOffset = params.moduleDisplayOffset(module),
                isActiveDragModule = params.isActiveDragModule(module),
                onModuleBoundsChanged = { rect -> params.onModuleBoundsChanged(module, rect) },
                onDragStart = { params.onDragStart(module) },
                onDragCancel = { params.onDragCancel(module) },
                onDragEnd = { params.onDragEnd(module) },
                onDragDelta = { dragAmount -> params.onDragDelta(module, dragAmount) },
                animate = params.isExpandedStateLoaded,
            )
            when (module) {
                PasswordListTopModule.QUICK_FILTERS -> {
                    PasswordQuickFiltersMenuModule(
                        params = PasswordQuickFiltersMenuModuleParams(
                            sectionParams = sectionParams,
                            menuWidth = params.menuWidth,
                            categoryEditMode = params.categoryEditMode,
                            quickFilterOrder = params.quickFilterOrder,
                            quickFilterMeasuredSizes = params.quickFilterMeasuredSizes,
                            chipState = params.quickFilterChipState,
                            chipCallbacks = params.quickFilterChipCallbacks,
                            onOrderCommitted = params.onQuickFilterOrderCommitted
                        )
                    )
                }

                PasswordListTopModule.QUICK_FOLDERS -> {
                    PasswordQuickFoldersMenuModule(
                        params = PasswordQuickFoldersMenuModuleParams(
                            sectionParams = sectionParams,
                            currentFilter = params.currentFilter,
                            quickFolderShortcuts = params.quickFolderShortcuts,
                            categoryEditMode = params.categoryEditMode,
                            categories = params.categories,
                            onRequestCategoryAction = params.onRequestCategoryAction,
                            onSelectFilter = params.onSelectFilter
                        )
                    )
                }
            }
        }
    }
}