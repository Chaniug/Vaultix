package com.bastion.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

@Composable
internal fun PasswordMenuSection(
    title: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    headerModifier: Modifier = Modifier,
    toggleEnabled: Boolean = true,
    animate: Boolean = true,
    headerVisible: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 0f else -90f,
        animationSpec = tween(durationMillis = 160),
        label = "password_menu_section_arrow"
    )
    // 普通模式下由顶部 Tab 驱动内容切换时隐藏折叠头（避免与 Tab 重复），内容恒显；
    // 编辑模式下保留折叠头作为拖拽排序抓手。
    val contentVisible = if (headerVisible) expanded else true
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (headerVisible) {
            val baseHeaderModifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp)
            Row(
                modifier = if (toggleEnabled) {
                    baseHeaderModifier
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { onExpandedChange(!expanded) }
                        .then(headerModifier)
                } else {
                    baseHeaderModifier.then(headerModifier)
                },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.graphicsLayer { rotationZ = arrowRotation }
                )
            }
        }
        if (animate) {
            AnimatedVisibility(
                visible = contentVisible,
                enter = expandVertically(animationSpec = tween(160)) + fadeIn(animationSpec = tween(100)),
                exit = shrinkVertically(animationSpec = tween(120)) + fadeOut(animationSpec = tween(80))
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    content = content
                )
            }
        } else if (contentVisible) {
            // 持久化状态加载完成前用纯 if/else 显示，
            // 避免 visible 从 true 突变到 false 触发 AnimatedVisibility 的退出动画。
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                content = content
            )
        }
    }
}
