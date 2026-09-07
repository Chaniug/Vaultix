package com.bastion.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Bastion 统一圆角体系（Material Design 3 语义形状）
 *
 * 归一原则：
 * - extraSmall 4dp：标签、小徽章
 * - small 8dp：按钮、输入框、小卡片角
 * - medium 12dp：常规卡片、列表项
 * - large 16dp：大卡片、弹窗内容
 * - extraLarge 28dp：底部弹窗、大容器
 * - 胶囊（pill）：chip / 图标按钮用 CircleShape
 */
val BastionShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp)
)
