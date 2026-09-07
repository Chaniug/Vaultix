package com.bastion.app.ui.screens

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.dp
import com.bastion.app.R
import com.bastion.app.data.BottomNavContentTab
import com.bastion.app.ui.LocalAnimatedVisibilityScope
import com.bastion.app.ui.LocalSharedTransitionScope
import com.bastion.app.viewmodel.SettingsViewModel
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import androidx.compose.ui.res.stringResource
@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun BottomNavSettingsScreen(
    viewModel: SettingsViewModel,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsState()
    val bottomNavVisibility = settings.bottomNavVisibility
    val listState = rememberLazyListState()
    var localBottomNavOrder by remember(settings.bottomNavOrder) {
        mutableStateOf(settings.bottomNavOrder.filterNot { it == BottomNavContentTab.PASSKEY })
    }
    val reorderableState = rememberReorderableLazyListState(listState) { from, to ->
        localBottomNavOrder = localBottomNavOrder.toMutableList().apply {
            add(to.index, removeAt(from.index))
        }
        viewModel.updateBottomNavOrder(localBottomNavOrder)
    }

    // 准备共享元素 Modifier
    val sharedTransitionScope = com.bastion.app.ui.LocalSharedTransitionScope.current
    val animatedVisibilityScope = com.bastion.app.ui.LocalAnimatedVisibilityScope.current
    
    var sharedModifier: Modifier = Modifier

    Scaffold(
        modifier = sharedModifier,
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.bottom_nav_settings)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            Text(
                text = stringResource(R.string.bottom_nav_reorder_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp)
            )

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                userScrollEnabled = !reorderableState.isAnyItemDragging,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(
                    items = localBottomNavOrder,
                    key = { it.name }
                ) { tab ->
                    ReorderableItem(
                        reorderableState,
                        key = tab.name,
                        enabled = true
                    ) { isDragging ->
                        val elevation by androidx.compose.animation.core.animateDpAsState(
                            if (isDragging) 8.dp else 0.dp,
                            label = "bottom_nav_drag_elevation"
                        )
                        val isVisible = if (tab == BottomNavContentTab.AUTHENTICATOR) {
                            bottomNavVisibility.authenticator || bottomNavVisibility.passkey
                        } else {
                            bottomNavVisibility.isVisible(tab)
                        }
                        val switchEnabled = !isVisible || bottomNavVisibility.visibleCount() > 1
                        BottomNavConfigRow(
                            icon = tab.toIcon(),
                            title = stringResource(tab.toLabelRes()),
                            subtitle = stringResource(R.string.bottom_nav_toggle_subtitle),
                            checked = isVisible,
                            switchEnabled = switchEnabled,
                            onCheckedChange = { checked ->
                                viewModel.updateBottomNavVisibility(tab, checked)
                            },
                            dragHandleModifier = Modifier.longPressDraggableHandle(),
                            modifier = Modifier.shadow(elevation)
                        )
                    }
                }

                item(key = "bottom_nav_auto_hide_single_tab") {
                    BottomNavConfigRow(
                        icon = Icons.Default.VisibilityOff,
                        title = stringResource(R.string.bottom_nav_auto_hide_single_tab_title),
                        subtitle = stringResource(R.string.bottom_nav_auto_hide_single_tab_subtitle),
                        checked = settings.autoHideBottomNavWhenSingleTab,
                        switchEnabled = true,
                        onCheckedChange = viewModel::updateAutoHideBottomNavWhenSingleTab,
                        showDragHandle = false,
                    )
                }

                // 「可拖拽底部导航栏」开关已移除：+ 号已合并进底栏中间，
                // 抽屉式底栏与当前设计冲突，设置不再暴露（数据字段保留以兼容备份恢复）。
            }
        }
    }
}
