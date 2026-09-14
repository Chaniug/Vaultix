package io.vaultix.vaultix.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.vaultix.vaultix.ui.theme.Spacing

/**
 * 搜索容器高度（M3 contained search 规格：56dp）。
 *
 * ⚠️ 与外层 `TopAppBar`（64dp）的差值是刻意的：上下各留 4dp，
 * 容器不贴顶栏边缘。改动这个值会影响调用方的让位计算，别随手调。
 */
private val SEARCH_BAR_HEIGHT = 56.dp

/**
 * 容器圆角 = 高度的一半（56/2）⇒ 胶囊形，contained search 的标准形状。
 *
 * ⚠️ 写成字面量而非 `SEARCH_BAR_HEIGHT / 2`：[RoundedCornerShape] 有
 * `Dp` 与 `Int`（**百分比**）两个重载，用除法会让这里多一层类型推断；
 * 28dp 这个值也与 `AppPickerDialog` 的既有写法一致。
 * 改高度时记得同步改这里。
 */
private val SEARCH_BAR_CORNER = 28.dp

/**
 * 搜索态顶栏（对齐 Bitwarden 的 `BitwardenSearchTopAppBar`）。
 *
 * 为什么需要它：搜索输入框若嵌在 [`LargeTopAppBar`]（可变高度、随滚动折叠）内部，
 * 或在顶栏外侧的 `Column` 中另起一行，滚动 / 展开 / 收起时输入框位置与高度
 * 会反复重算，视觉上表现为「搜索框乱跳」，焦点也容易漂移。
 *
 * 本组件的做法（与 Bitwarden 一致）：
 * - 使用**固定高度**的 [`TopAppBar`]，绝不用大标题栏；
 * - 搜索态时输入框**整体占据 `title` 槽**，与标题二选一、不并存；
 * - 通过 [`FocusRequester`] + `LaunchedEffect` **主动请求焦点**，不依赖系统自动聚焦；
 * - `imeAction = ImeAction.Done`，有输入时右侧出现清除按钮（带动画）。
 *
 * **2026-09-15：输入框改为 M3 Expressive 的 contained search（常驻填充容器）** ——
 * 原先是透明裸输入框（只有文字，看不出"这是个输入框"），现在给一层
 * `surfaceContainerHigh` 的胶囊容器 + 左侧搜索图标。
 *
 * ⚠️ **只改观感，不动结构**：容器高度仍是 [SEARCH_BAR_HEIGHT]，外层仍是同一个
 * [`TopAppBar`] ⇒ 调用方的 inset 计算（`itemsTopInset` 等）**一行都不用改**。
 * 这一点是刻意的 —— `ISSUES.md` #67 / #76 记录的都是"让位算错"的坑，
 * 为了换个样式去动 inset 不值得。
 *
 * ⚠️ **没有**换成 alpha 的 `SearchBar` 组件：8.8 号文档 §6 明确要求
 * 「交互框架（AppBar / 搜索）等 beta/stable 再换」，这里用稳定的 [TextField]
 * 自绘同样的观感，零 alpha API 依赖。
 *
 * 调用方只需在 `searchActive` 为真时**整体替换**普通顶栏，切勿叠加。
 *
 * @param searchTerm 当前搜索词（受控）。
 * @param placeholder 占位提示文案。
 * @param onSearchTermChange 搜索词变化回调（清除按钮也会回调空串）。
 * @param onClose 关闭搜索（返回普通顶栏）；作为左侧关闭按钮的行为。
 * @param clearIconContentDescription 清除按钮的无障碍描述。
 * @param scrollBehavior 与调用方列表共用的滚动行为；须为固定（pinned）语义。
 * @param autoFocus 是否在进入搜索态时自动聚焦并弹出键盘。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultixSearchTopAppBar(
    searchTerm: String,
    placeholder: String,
    onSearchTermChange: (String) -> Unit,
    onClose: () -> Unit,
    clearIconContentDescription: String,
    scrollBehavior: TopAppBarScrollBehavior,
    modifier: Modifier = Modifier,
    autoFocus: Boolean = true,
) {
    val focusRequester = remember { FocusRequester() }
    TopAppBar(
        modifier = modifier,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        scrollBehavior = scrollBehavior,
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = clearIconContentDescription,
                )
            }
        },
        title = {
            TextField(
                value = searchTerm,
                onValueChange = onSearchTermChange,
                placeholder = { Text(placeholder) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge,
                // contained search：容器**本身就是边界**，故不再需要 filled TextField
                // 的下划线（指示器全部透明）；容器高度取半 ⇒ 胶囊形。
                shape = RoundedCornerShape(SEARCH_BAR_CORNER),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                leadingIcon = {
                    Icon(
                        Icons.Filled.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                trailingIcon = {
                    AnimatedVisibility(
                        visible = searchTerm.isNotEmpty(),
                        enter = scaleIn() + fadeIn(),
                        exit = scaleOut() + fadeOut(),
                    ) {
                        IconButton(onClick = { onSearchTermChange("") }) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = clearIconContentDescription,
                            )
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier
                    .focusRequester(focusRequester)
                    .fillMaxWidth()
                    .height(SEARCH_BAR_HEIGHT)
                    .padding(end = Spacing.sm),
            )
        },
    )
    if (autoFocus) {
        LaunchedEffect(Unit) {
            focusRequester.requestFocus()
        }
    }
}
