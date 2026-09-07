package com.bastion.app.ui.icons

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.bastion.app.data.UnmatchedIconHandlingStrategy
import kotlin.math.abs

fun shouldShowFallbackSlot(strategy: UnmatchedIconHandlingStrategy): Boolean {
    return strategy != UnmatchedIconHandlingStrategy.HIDE
}

/**
 * 首字母兜底图标使用的确定性调色板。
 * 按文本哈希取色，保证同一条目颜色稳定、不同条目颜色尽量区分，提升“未匹配图标”的可辨识度。
 */
private val MONOGRAM_PALETTE = listOf(
    Color(0xFFEF5350), Color(0xFFEC407A), Color(0xFFAB47BC), Color(0xFF7E57C2),
    Color(0xFF5C6BC0), Color(0xFF42A5F5), Color(0xFF29B6F6), Color(0xFF26C6DA),
    Color(0xFF26A69A), Color(0xFF66BB6A), Color(0xFF9CCC65), Color(0xFFFFA726),
    Color(0xFFFF7043), Color(0xFF8D6E63), Color(0xFF78909C)
)

/**
 * 根据种子文本返回稳定的兜底底色。空文本时回退到调色板首个颜色。
 */
fun monogramColorFor(seed: String): Color {
    if (seed.isBlank()) return MONOGRAM_PALETTE.first()
    return MONOGRAM_PALETTE[abs(seed.hashCode()) % MONOGRAM_PALETTE.size]
}

@Composable
fun UnmatchedIconFallback(
    strategy: UnmatchedIconHandlingStrategy,
    primaryText: String?,
    secondaryText: String?,
    defaultIcon: ImageVector,
    iconSize: Dp
) {
    when (strategy) {
        UnmatchedIconHandlingStrategy.DEFAULT_ICON -> {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(iconSize)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = defaultIcon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(iconSize * 0.6f)
                    )
                }
            }
        }

        UnmatchedIconHandlingStrategy.WEBSITE_OR_TITLE_INITIAL -> {
            val seed = (primaryText ?: secondaryText).orEmpty()
            val backgroundColor = remember(seed) { monogramColorFor(seed) }
            Surface(
                shape = CircleShape,
                color = backgroundColor,
                modifier = Modifier.size(iconSize)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = resolveInitial(primaryText, secondaryText),
                        color = Color.White,
                        fontSize = (iconSize.value * 0.42f).sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        UnmatchedIconHandlingStrategy.HIDE -> Unit
    }
}

private fun resolveInitial(primaryText: String?, secondaryText: String?): String {
    val raw = listOf(primaryText, secondaryText)
        .firstNotNullOfOrNull { source ->
            source
                ?.trim()
                ?.firstOrNull { !it.isWhitespace() }
                ?.uppercaseChar()
                ?.toString()
        }
    return raw ?: "#"
}
