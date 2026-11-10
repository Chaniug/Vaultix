/*
 * Vaultix — app:ui · cardwallet
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * 一句话：**卡包里的两种东西，本来就该长得不一样。**
 *
 * 旧版 `CardWalletRow` 把银行卡和身份条目塞进同一个 `EntryCard` 行模板：
 * 44×30 的品牌小图 + 两行文字 + 右下角有效期。它是「列表行」，不是「卡」——
 * 用户看到建行卡的截图只会觉得"这是一条记录"，完全没有"我有一张卡"的分量。
 *
 * ⇒ 本文件给出两张**造型**：
 * - [BankCardFace]：接近实体银行卡的比例、渐变卡面、凸印卡号、芯片、
 *   持卡人 / 有效期分列底部。深色卡面是印刷品的颜色，**不跟随主题** ——
 *   真实银行卡在浅色主题里也还是深色的。
 * - [IdentityCardFace]：更扁的名片比例、左侧色条 + 圆形首字徽章、
 *   姓名作为唯一视觉主体、下方一条细分隔线与联系方式。底色走 M3 容器色，
 *   与银行卡的"另一个物件"形成层级差。
 *
 * ⚠️ 交互契约不能因为换造型而丢（这是本项目最容易翻车的地方）：
 *   ① 整卡可点开详情；② 长按选中；③ 选中态**必须有可见反馈** ——
 *   它是 `PressAndSwipeToDelete` 的删除前置门槛，不是纯装饰；
 *   ④ 卡号只显示打码后的末四位，明文不进列表。
 *
 * ⚠️ 为什么不用 `EntryCard`：那是"三个列表统一的行卡片"组件，而这两种造型
 *    刻意**不统一**。共用它反而会把卡面压回 16dp 内边距的行模板。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.vaultix.ui.cardwallet

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.vaultix.common.CardBrand
import io.vaultix.common.formatCardNumberGrouped
import io.vaultix.model.VaultItem
import io.vaultix.vaultix.R
import io.vaultix.vaultix.ui.theme.Spacing
import io.vaultix.vaultix.ui.theme.UiSize

/**
 * 卡面比例：ISO/IEC 7810 ID-1 是 1.586，这里取 **1.7** 略扁一点。
 *
 * 不是量错了：列表里一屏要能看到两张卡，1.586 在 328dp 宽下是 207dp 高，
 * 加上间距一屏只剩 1.8 张。1.7 仍是"一眼是银行卡"的比例，但 193dp 能露出下一张的边。
 */
private const val BANK_CARD_RATIO = 1.7f

/** 名片比银行卡更扁，两者并排时一眼分得出"这不是同一类东西"。 */
private const val IDENTITY_CARD_RATIO = 2.45f

private val CARD_FACE_SHAPE = RoundedCornerShape(20.dp)
private val IDENTITY_FACE_SHAPE = RoundedCornerShape(16.dp)

private val FACE_PADDING = Spacing.lg

/**
 * 卡面装饰的几何参数（detekt `MagicNumber` 要求具名；顺带把"两个光斑在哪"写清楚）。
 * 全部是**相对画布尺寸的比例**，所以卡片宽度变化时形状不会散。
 */
private object FaceGeometry {
    const val GLOW_A_ALPHA = 0.07f
    const val GLOW_A_RADIUS = 0.58f
    const val GLOW_A_X = 0.92f
    const val GLOW_A_Y = 0.02f
    const val GLOW_B_ALPHA = 0.05f
    const val GLOW_B_RADIUS = 0.42f
    const val GLOW_B_X = 0.16f
    const val GLOW_B_Y = 1.02f
    const val CHIP_CORNER_DP = 5f
    const val CHIP_LINE_ALPHA = 0.65f
    val CHIP_LINE_FRACTIONS = listOf(0.32f, 0.5f, 0.68f)
}

/** 卡面文字：深色卡面上的白 / 半透明白。 */
private val FaceWhite = Color.White
private val FaceWhiteDim = Color.White.copy(alpha = 0.72f)
private val FaceWhiteFaint = Color.White.copy(alpha = 0.42f)

/**
 * 五组卡面渐变。**按条目 id 稳定挑选**，不是随机：
 * 同一张卡每次进列表颜色都一样，用户能靠颜色认卡；不同卡之间有区分度。
 *
 * ⚠️ 这里的渐变是「印刷品图案」，不是 M3 容器 —— 8.4「嵌套必须换档」约束的是
 *    surface-container 层级，不适用于卡面本身（拿主题容器色画卡面会糊成一块灰）。
 */
private val CARD_FACE_PALETTES = listOf(
    listOf(Color(0xFF16325C), Color(0xFF2C5FA8)),
    listOf(Color(0xFF1F2733), Color(0xFF44546B)),
    listOf(Color(0xFF4A1630), Color(0xFF8A2E52)),
    listOf(Color(0xFF123B33), Color(0xFF1F6B5C)),
    listOf(Color(0xFF2A1B4A), Color(0xFF5540A0)),
)

private fun paletteFor(item: VaultItem): List<Color> =
    CARD_FACE_PALETTES[Math.floorMod(item.id.hashCode(), CARD_FACE_PALETTES.size)]

/**
 * 银行卡造型。**整卡可点 / 长按选中**，选中时描 primary 边（功能性反馈）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun BankCardFace(
    item: VaultItem,
    brand: CardBrand,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val card = item.card
    val palette = remember(item.id) { paletteFor(item) }
    val outline = MaterialTheme.colorScheme.primary
    val shape = CARD_FACE_SHAPE
    val number = remember(card?.number) {
        val raw = card?.number.orEmpty()
        if (raw.isBlank()) "" else maskCardNumber(formatCardNumberGrouped(raw))
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(BANK_CARD_RATIO)
            .clip(shape)
            .background(Brush.linearGradient(palette))
            .then(
                if (selected) Modifier.border(2.dp, outline, shape) else Modifier,
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(FACE_PADDING),
    ) {
        // 光斑是纯装饰（不拦截手势，也不进无障碍树）。两处半径按画布短边取，
        // 卡片变宽时不会变成一块生硬的方斑。
        Canvas(modifier = Modifier.matchParentSize()) {
            val r = size.minDimension
            drawCircle(
                color = FaceWhite.copy(alpha = FaceGeometry.GLOW_A_ALPHA),
                radius = r * FaceGeometry.GLOW_A_RADIUS,
                center = Offset(
                    size.width * FaceGeometry.GLOW_A_X,
                    size.height * FaceGeometry.GLOW_A_Y,
                ),
            )
            drawCircle(
                color = FaceWhite.copy(alpha = FaceGeometry.GLOW_B_ALPHA),
                radius = r * FaceGeometry.GLOW_B_RADIUS,
                center = Offset(
                    size.width * FaceGeometry.GLOW_B_X,
                    size.height * FaceGeometry.GLOW_B_Y,
                ),
            )
        }

        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            BankCardTopLine(item = item, brand = brand)
            Column {
                CardChip()
                Spacer(Modifier.height(Spacing.sm))
                Text(
                    text = number.ifBlank { stringResource(R.string.card_face_no_number) },
                    style = MaterialTheme.typography.titleLarge,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 1.5.sp,
                    color = FaceWhite,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            BankCardBottomLine(
                cardholder = card?.cardholderName.orEmpty(),
                expiry = expiryLabel(card?.expMonth, card?.expYear),
            )
        }
    }
}

@Composable
private fun BankCardTopLine(item: VaultItem, brand: CardBrand) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = FaceWhite,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = item.card?.brand?.takeIf { it.isNotBlank() } ?: brand.displayName,
                style = MaterialTheme.typography.labelSmall,
                color = FaceWhiteDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(Spacing.sm))
        // 品牌 logo 自带浅色底板，落在深色卡面上正好像实体卡右上角的标识块。
        CardBrandIcon(brand = brand, modifier = Modifier.size(44.dp, 28.dp))
    }
}

/** 芯片：34×26 的金色圆角块 + 三道触点线，纯绘制。 */
@Composable
private fun CardChip() {
    Canvas(modifier = Modifier.size(34.dp, 26.dp)) {
        val chipColor = Color(0xFFD9BE7A)
        drawRoundRect(
            color = chipColor,
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(FaceGeometry.CHIP_CORNER_DP.dp.toPx()),
        )
        val line = Color(0xFF8A7440).copy(alpha = FaceGeometry.CHIP_LINE_ALPHA)
        val stroke = 1.dp.toPx()
        FaceGeometry.CHIP_LINE_FRACTIONS.forEach { fraction ->
            drawLine(
                color = line,
                start = Offset(0f, size.height * fraction),
                end = Offset(size.width, size.height * fraction),
                strokeWidth = stroke,
            )
        }
        drawLine(
            color = line,
            start = Offset(size.width / 2f, 0f),
            end = Offset(size.width / 2f, size.height),
            strokeWidth = stroke,
        )
    }
}

@Composable
private fun BankCardBottomLine(cardholder: String, expiry: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.card_face_cardholder),
                style = MaterialTheme.typography.labelSmall,
                color = FaceWhiteFaint,
            )
            Text(
                text = cardholder.ifBlank { "—" },
                style = MaterialTheme.typography.bodyMedium,
                color = FaceWhite,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (expiry.isNotBlank()) {
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = stringResource(R.string.card_face_valid_thru),
                    style = MaterialTheme.typography.labelSmall,
                    color = FaceWhiteFaint,
                )
                Text(
                    text = expiry,
                    style = MaterialTheme.typography.bodyMedium,
                    color = FaceWhite,
                    maxLines = 1,
                )
            }
        }
    }
}

private fun expiryLabel(month: String?, year: String?): String =
    listOf(month.orEmpty(), year.orEmpty())
        .filter { it.isNotBlank() }
        .takeIf { it.size == 2 }
        ?.joinToString("/")
        .orEmpty()

/**
 * 身份条目的名片造型。
 *
 * 底色走 `surfaceContainerLow`（跟随深浅主题），与银行卡的深色印刷面形成对比；
 * 左侧 4dp 主色竖条是名片的"抬头"，右侧姓名是唯一视觉主体。
 * 联系方式只在真的有值时才画，避免名片下半截挂着两行空白。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun IdentityCardFace(
    item: VaultItem,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val identity = item.identity
    val shape = IDENTITY_FACE_SHAPE
    val outline = MaterialTheme.colorScheme.primary
    val container = if (selected) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }
    val fullName = remember(item.identity, item.title) {
        listOf(
            identity?.title,
            identity?.firstName,
            identity?.middleName,
            identity?.lastName,
        ).filter { !it.isNullOrBlank() }.joinToString(" ").ifBlank { item.title }
    }
    val affiliation = remember(item.identity, item.title) {
        listOf(identity?.company, identity?.city)
            .filter { !it.isNullOrBlank() }
            .joinToString(" · ")
            .ifBlank { item.title }
    }
    val contacts = remember(item.identity) {
        listOfNotNull(
            identity?.email?.takeIf { it.isNotBlank() }?.let { Contact(it, true) },
            identity?.phone?.takeIf { it.isNotBlank() }?.let { Contact(it, false) },
        )
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(IDENTITY_CARD_RATIO)
            .clip(shape)
            .background(container)
            .border(
                width = if (selected) 2.dp else 0.5.dp,
                color = if (selected) outline else MaterialTheme.colorScheme.outlineVariant,
                shape = shape,
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(FACE_PADDING),
    ) {
        Row(modifier = Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            // 名片抬头：一条主色竖条，替代旧版那个孤零零的 Badge 图标。
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(56.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(outline),
            )
            Spacer(Modifier.width(Spacing.md))
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(outline.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = fullName.take(1),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = outline,
                )
            }
            Spacer(Modifier.width(Spacing.md))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                Text(
                    text = fullName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = affiliation,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (contacts.isNotEmpty()) {
                    IdentityContacts(contacts = contacts)
                }
            }
        }
    }
}

private data class Contact(val value: String, val isEmail: Boolean)

@Composable
private fun IdentityContacts(contacts: List<Contact>) {
    Column(verticalArrangement = Arrangement.spacedBy(UiSize.HairGap)) {
        contacts.take(2).forEach { contact ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (contact.isEmail) Icons.Filled.Email else Icons.Filled.Phone,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(UiSize.MicroIcon),
                )
                Spacer(Modifier.width(Spacing.xs))
                Text(
                    text = contact.value,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * 卡号打码：保留末 4 位，其余替换为「•」。
 *
 * ⚠️ 分组格式化后的分隔符保留，避免把「1234 5678」压成一串难以辨认的圆点。
 *    `internal` 是因为 `CardWalletScreen.walletSubtitle` 的搜索摘要也用它 ——
 *    **打码规则只能有一份**，两边各写一遍迟早一处漏改。
 */
internal fun maskCardNumber(grouped: String): String {
    if (grouped.isBlank()) return ""
    val totalDigits = grouped.count { it.isDigit() }
    var index = 0
    return grouped.map { char ->
        if (!char.isDigit()) {
            char
        } else {
            val keep = index >= totalDigits - 4
            index++
            if (keep) char else '•'
        }
    }.joinToString("")
}
