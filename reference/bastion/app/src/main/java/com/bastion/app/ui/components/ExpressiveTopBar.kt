package com.bastion.app.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.ui.unit.sp
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bastion.app.R

internal fun initialSearchTextFieldValue(searchQuery: String): TextFieldValue =
    TextFieldValue(
        text = searchQuery,
        selection = TextRange(searchQuery.length)
    )

internal fun reconcileSearchTextFieldValue(
    currentValue: TextFieldValue,
    searchQuery: String
): TextFieldValue = if (currentValue.text == searchQuery) {
    currentValue
} else {
    initialSearchTextFieldValue(searchQuery)
}

/**
 * M3E 风格的顶部标题栏
 * 支持大标题和集成的搜索展开动画
 */
@Composable
fun ExpressiveTopBar(
    title: String,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    isSearchExpanded: Boolean,
    onSearchExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    searchHint: String? = null,
    navigationIcon: @Composable (() -> Unit)? = null,
    onActionPillBoundsChanged: ((Rect) -> Unit)? = null,
    collapsedTitleEndPadding: Dp = 144.dp,
    /**
     * 若非空，则标题区变为可点击（外加展开/收起箭头）。
     * 为 null 时标题保持纯文本，行为与原有调用方完全一致。
     */
    onTitleClick: (() -> Unit)? = null,
    /**
     * 配合 onTitleClick 使用：true=展开态（显示 ExpandLess），false=收起态（显示 ExpandMore）。
     * 为 null 时视为收起态。仅当 onTitleClick 非空时箭头才会渲染。
     */
    titleExpanded: Boolean? = null,
    actions: @Composable RowScope.() -> Unit = {},
    /**
     * 滚动收起状态（快照式，非按距离渐变）：0=展开，1=收起。
     * 由调用方用一个很小的滚动阈值判定（越过阈值就整体切换），
     * 切换本身由内部 200ms 动画完成，不会随滚动距离连续缩放。
     * - 标题 32sp → 18sp，Bar 64dp → 44dp，胶囊抬升 2dp → 0dp
     * - Bar 自身无背景（透明），收起后列表内容可从其下方穿过
     */
    scrollCollapseFraction: Float = 0f
) {
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val searchInteractionSource = remember { MutableInteractionSource() }
    var searchFieldValueState by remember {
        mutableStateOf(initialSearchTextFieldValue(searchQuery))
    }
    val searchFieldValue = reconcileSearchTextFieldValue(searchFieldValueState, searchQuery)
    val resolvedSearchHint = searchHint ?: stringResource(R.string.topbar_search_hint)

    SideEffect {
        if (searchFieldValueState != searchFieldValue) {
            searchFieldValueState = searchFieldValue
        }
    }

    LaunchedEffect(searchInteractionSource) {
        searchInteractionSource.interactions.collect { interaction ->
            if (interaction is PressInteraction.Press) {
                focusRequester.requestFocus()
                keyboardController?.show()
            }
        }
    }

    // 动画状态：使用 Alpha 而不是 Visibility，避免布局重排导致挤压搜索框
    val titleAlpha by animateFloatAsState(
        targetValue = if (isSearchExpanded) 0f else 1f,
        animationSpec = tween(200),
        label = "TitleAlpha"
    )

    val isLongTitle = title.length > 10
    val titleStyle = when {
        title.length > 18 -> MaterialTheme.typography.bodyLarge
        isLongTitle -> MaterialTheme.typography.titleMedium
        else -> MaterialTheme.typography.headlineLarge
    }
    val pillReserve = if (isSearchExpanded) 0.dp else collapsedTitleEndPadding

    // 标题过长（可用宽度被右侧胶囊挤压）时自动缩小字号，避免尾字符被裁
    var titleAutoScale by remember(title) { mutableStateOf(1f) }

    // 顶栏内容色：标题与右侧按钮同色；滚动收起时整体略暗
    val topBarContentColor by animateColorAsState(
        targetValue = if (isSearchExpanded) {
            MaterialTheme.colorScheme.onBackground
        } else {
            androidx.compose.ui.graphics.lerp(
                MaterialTheme.colorScheme.onBackground,
                MaterialTheme.colorScheme.onSurfaceVariant,
                scrollCollapseFraction
            )
        },
        animationSpec = tween(200),
        label = "topbar_content_color"
    )

    // === 滚动收起动画值（0=展开，1=完全收起）===
    // 落差（32→16sp、72→48dp）。展开态收窄：大标题上方不再留大块空白
    val titleFontSize by animateFloatAsState(
        targetValue = 32f + (16f - 32f) * scrollCollapseFraction,
        animationSpec = tween(200),
        label = "topbar_title_size"
    )
    val barMinHeight by animateDpAsState(
        targetValue = androidx.compose.ui.unit.lerp(72.dp, 48.dp, scrollCollapseFraction),
        animationSpec = tween(200),
        label = "topbar_bar_height"
    )
    val barVerticalPadding by animateDpAsState(
        targetValue = androidx.compose.ui.unit.lerp(8.dp, 4.dp, scrollCollapseFraction),
        animationSpec = tween(200),
        label = "topbar_vpadding"
    )
    // 胶囊垂直抬升（tonalElevation）：原来是 3.dp，让 capsule 渲染色被 primary tint 加深，
    // 浅色 dynamic 主题下 #FEF7FF → 像 #ECE6F0 这种明显灰色、和"贴系统"的诉求冲突。
    // 改为 0.dp → capsule 与 Bar 主背景都是纯 surface，调子一致。
    // 阴影仍由 shadowElevation = 1.dp 承担，保留轻微浮起感但不再有 tint 偏移。
    val pillElevation = 0.dp
    // 内容的垂直偏移：展开时轻微下移（大标题贴近栏底、与右侧按钮胶囊同底线），
    // 收起后回到垂直居中。搜索展开时不偏移。
    val contentOffsetY by animateDpAsState(
        targetValue = if (isSearchExpanded) {
            0.dp
        } else {
            androidx.compose.ui.unit.lerp(8.dp, 0.dp, scrollCollapseFraction)
        },
        animationSpec = tween(200),
        label = "topbar_content_offset"
    )
    // 右侧按钮胶囊：折叠态收窄（56dp→48dp），搜索展开时保持 56dp 容纳输入框
    val actionPillHeight by animateDpAsState(
        targetValue = if (isSearchExpanded) {
            56.dp
        } else {
            androidx.compose.ui.unit.lerp(48.dp, 40.dp, scrollCollapseFraction)
        },
        animationSpec = spring(
            dampingRatio = 0.85f,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "topbar_action_pill_height"
    )
    // 胶囊垂直位置：保持完全位于顶栏背景内（此前下移 5dp 会使胶囊底部凸出背景区，已回退）
    val actionPillOffsetY = 0.dp
    // 收起进度的平滑值（0=展开，1=收起）：顶栏其余属性都走 200ms 动画，
    // 按钮组缩放此前直读快照值 scrollCollapseFraction → 切换瞬间硬跳一下（观感像卡顿），
    // 这里改用同一条 200ms 插值，让外框与内部按钮同步缩放。
    val collapseProgress by animateFloatAsState(
        targetValue = scrollCollapseFraction,
        animationSpec = tween(200),
        label = "topbar_collapse_progress"
    )
    // Bar 自身背景的透明度：展开时不透明（像相册顶部的白色头部栏），
    // 收起后 alpha→0 变透明，列表内容可从 Bar 底下穿过（关键：列表 contentPadding.top=0）。
    // 搜索框展开时始终保持不透明，否则输入时背景消失很怪。
    val barBackgroundAlpha by animateFloatAsState(
        targetValue = if (isSearchExpanded || scrollCollapseFraction < 0.5f) 1f else 0f,
        animationSpec = tween(200),
        label = "topbar_bg_alpha"
    )

    // 当 onTitleClick 非空时，提前解析「点击展开快捷筛选」的本地化字符串，
    // 避免在非 Composable 的 .semantics { } 块里再调用 stringResource。
    val titleClickHint = stringResource(R.string.topbar_title_filter_hint)

    Box(
        modifier = modifier
            .fillMaxWidth()
            // 滚动收起时 Bar 整体高度从 88dp 压到 48dp（落差 40dp，接近相册观感）
            .heightIn(min = barMinHeight)
            // 自带背景：展开不透明、收起透明（列表从 Bar 底下穿过）。
            // 背景在 statusBarsPadding 之前应用 → 覆盖到状态栏区域（沉浸式白/彩头部），
            // 内容通过 statusBarsPadding 下移到状态栏下方。
            //
            // 改用 surface 而非 surfaceContainerHigh：
            //   M3 expressive 设计里 surfaceContainerHigh 是"凸起容器色"，比 surface 略深，
            //   会让 Bar 看起来像一张彩色卡片浮在主背景上——密码/验证器/通行秘钥/卡包四页
            //   顶部因此偏蓝灰，和「设置页 Surface(color = surface, tonalElevation=3dp)」的
            //   纯白顶栏不一致。统一改用 surface 后 Bar 与主背景融合，卡片从底下穿过即形成
            //   用户要的"沉浸、贴系统"观感。
            .background(MaterialTheme.colorScheme.surface.copy(alpha = barBackgroundAlpha))
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = barVerticalPadding),
        contentAlignment = Alignment.Center
    ) {
        // 1. 标题区 (在左侧，始终占位，只改变透明度)
        Row(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset(y = contentOffsetY)
                .graphicsLayer { alpha = titleAlpha }
                .padding(end = pillReserve),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            navigationIcon?.invoke()

            if (onTitleClick == null) {
                Text(
                    text = title,
                    style = titleStyle,
                    // 滚动收起时标题字缩小（保留 titleStyle 的字重/字距/颜色）
                    fontSize = (titleFontSize * titleAutoScale).sp,
                    lineHeight = (titleFontSize * titleAutoScale * 1.2f).sp,
                    fontWeight = FontWeight.SemiBold,
                    color = topBarContentColor,
                    maxLines = if (isLongTitle) 2 else 1,
                    overflow = TextOverflow.Clip,
                    softWrap = false,
                    // 溢出时逐步缩小字号（下限 0.72），避免末尾字符被裁
                    onTextLayout = { result ->
                        if (result.hasVisualOverflow && titleAutoScale > 0.72f) {
                            titleAutoScale = (titleAutoScale - 0.04f).coerceAtLeast(0.72f)
                        }
                    }
                )
            } else {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(role = Role.Button, onClick = onTitleClick)
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                        .semantics {
                            contentDescription = title + ", " + titleClickHint
                        },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = title,
                        style = titleStyle,
                        fontSize = (titleFontSize * titleAutoScale).sp,
                        lineHeight = (titleFontSize * titleAutoScale * 1.2f).sp,
                        fontWeight = FontWeight.SemiBold,
                        color = topBarContentColor,
                        maxLines = if (isLongTitle) 2 else 1,
                        overflow = TextOverflow.Clip,
                        softWrap = false,
                        onTextLayout = { result ->
                            if (result.hasVisualOverflow && titleAutoScale > 0.72f) {
                                titleAutoScale = (titleAutoScale - 0.04f).coerceAtLeast(0.72f)
                            }
                        }
                    )
                    Icon(
                        imageVector = if (titleExpanded == true) {
                            Icons.Default.ExpandLess
                        } else {
                            Icons.Default.ExpandMore
                        },
                        contentDescription = null,
                        modifier = Modifier.size(if (titleStyle.fontSize.value > 24f) 22.dp else 18.dp),
                        tint = topBarContentColor
                    )
                }
            }
        }

        // 2. 搜索/操作胶囊 (在右侧，覆盖在标题之上)
        // 使用 Box + CenterEnd 确保严格的右锚定
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .offset(y = contentOffsetY + actionPillOffsetY),
            contentAlignment = Alignment.CenterEnd
        ) {
            Surface(

                modifier = Modifier

                    .height(actionPillHeight)
                    .onGloballyPositioned { coordinates ->
                        onActionPillBoundsChanged?.invoke(coordinates.boundsInWindow())
                    }
                    // 添加左滑展开/右滑关闭手势
                    .pointerInput(isSearchExpanded) {
                        var totalDrag = 0f
                        detectHorizontalDragGestures(
                            onDragStart = { totalDrag = 0f },
                            onDragEnd = { totalDrag = 0f },
                            onDragCancel = { totalDrag = 0f }
                        ) { change, dragAmount ->
                            totalDrag += dragAmount
                            // 阈值设为 40px，避免过于灵敏
                            val threshold = 40f

                            if (!isSearchExpanded && totalDrag < -threshold) {
                                change.consume()
                                onSearchExpandedChange(true)
                                totalDrag = 0f
                            } else if (isSearchExpanded && totalDrag > threshold) {
                                change.consume()
                                onSearchExpandedChange(false)
                                onSearchQueryChange("")
                                focusManager.clearFocus()
                                totalDrag = 0f
                            }
                        }
                    },
                shape = RoundedCornerShape(50),
                // 跟随 Bar 背景一起透明：收起后按钮浮在内容上（对齐相册的 + 按钮浮在图片上）。
                // 与 Bar 主背景用同一 token（surface），避免胶囊在浅色背景上留下与 Bar 不
                // 同色的"贴片感"。见上方 Box.background 注释了解背景 token 选择。
                color = MaterialTheme.colorScheme.surface.copy(alpha = barBackgroundAlpha),
                // tonalElevation 0：胶囊与 Bar 都是 surface，不再被 primary tint 加深。
                // shadowElevation 1.dp：保留轻微阴影，不让 capsule 完全"飘"在内容上。
                // （滚动收起时已自动 alpha=0；"抬升动画"随之无意义，故 pillElevation 常数化）
                tonalElevation = pillElevation,
                shadowElevation = if (barBackgroundAlpha > 0.5f) 1.dp else 0.dp
            ) {
                // 内容切换
                AnimatedContent(
                    targetState = isSearchExpanded,
                    transitionSpec = {
                        // 展开：淡入 + 轻微放大；收起：快速淡出。尺寸用 spring 平滑过渡（避免生硬跳变）
                        (fadeIn(animationSpec = tween(220, delayMillis = 70)) +
                            scaleIn(
                                initialScale = 0.94f,
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                    stiffness = Spring.StiffnessMediumLow
                                )
                            ))
                            .togetherWith(fadeOut(animationSpec = tween(120)))
                            .using(
                                SizeTransform(
                                    clip = false,
                                    sizeAnimationSpec = { _, _ ->
                                        spring(
                                            dampingRatio = 0.85f,
                                            stiffness = Spring.StiffnessMediumLow
                                        )
                                    }
                                )
                            )
                    },
                    label = "PillContent"
                ) { expanded ->
                    if (expanded) {
                        // 展开状态：搜索框
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // 搜索输入框 (现在在左侧)
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = 16.dp), // 增加左侧边距以平衡视觉
                                contentAlignment = Alignment.CenterStart
                            ) {
                                if (searchQuery.isEmpty()) {
                                    Text(
                                        text = resolvedSearchHint,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                    )
                                }
                                BasicTextField(
                                    value = searchFieldValue,
                                    onValueChange = { newValue ->
                                        searchFieldValueState = newValue
                                        if (newValue.text != searchQuery) {
                                            onSearchQueryChange(newValue.text)
                                        }
                                    },
                                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                                        color = MaterialTheme.colorScheme.onSurface
                                    ),
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(showKeyboardOnFocus = true),
                                    interactionSource = searchInteractionSource,
                                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                    modifier = Modifier.focusRequester(focusRequester)
                                )
                            }
                            
                            // 按钮区域 (现在在右侧)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (searchQuery.isNotEmpty()) {
                                    IconButton(onClick = { onSearchQueryChange("") }) {
                                        Icon(
                                            Icons.Default.Clear,
                                            stringResource(R.string.clear_search),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }

                                IconButton(onClick = { 
                                    onSearchExpandedChange(false)
                                    onSearchQueryChange("")
                                    focusManager.clearFocus()
                                }) {
                                    Icon(
                                        imageVector = Icons.Default.ArrowForward, // 使用向右的箭头，表示收回方向
                                        contentDescription = stringResource(R.string.topbar_close_search),
                                        tint = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                        
                         LaunchedEffect(Unit) {
                            focusRequester.requestFocus()
                        }
                    } else {
                        // 折叠状态：Action Buttons
                        // 按钮组与标题同色：由 LocalContentColor 统一供给
                        CompositionLocalProvider(LocalContentColor provides topBarContentColor) {
                            Row(
                                modifier = Modifier
                                    .graphicsLayer {
                                        // 收起时按钮组跟随标题一起缩小（1.0→0.85），跟随 200ms 插值同步缩放
                                        val scale = 1f + (0.85f - 1f) * collapseProgress
                                        scaleX = scale
                                        scaleY = scale
                                    }
                                    .padding(horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(0.dp) // 紧凑排列
                            ) {
                                actions()
                            }
                        }
                    }
                }
            }
        }
    }
}
