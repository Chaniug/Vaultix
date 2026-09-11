package io.vaultix.vaultix.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
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
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
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
                    .padding(end = 8.dp),
            )
        },
    )
    if (autoFocus) {
        LaunchedEffect(Unit) {
            focusRequester.requestFocus()
        }
    }
}
