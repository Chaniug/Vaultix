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
 *   ⚠️ 卡面颜色**由银行决定**（[BankCardPalette]），不是按条目 id 随机挑：
 *   同名银行恒同色、不同银行尽量异色，用户才能靠颜色认卡。
 * - [IdentityCardFace]：更扁的名片比例、机构抬头 + 大姓名 + 细分隔线 +
 *   横排联系方式，配半透明同心圆水印与渐变抬头竖条。底色走 M3 容器色，
 *   与银行卡的"另一个物件"形成层级差。
 *
 * ⚠️ 交互契约不能因为换造型而丢（这是本项目最容易翻车的地方）：
 *   ① 整卡可点开详情；② 长按选中；③ 选中态**必须有可见反馈** ——
 *   它是 `PressAndSwipeToDelete` 的删除前置门槛，不是纯装饰；
 *   ④ 卡号只显示打码后的末四位，明文不进列表；
 *   ⑤ 装饰性绘制（光斑 / 水印）不拦截手势、不进无障碍树。
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
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.vaultix.common.BankCardPalette
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

/**
 * 名片比银行卡更扁，两者并排时一眼分得出"这不是同一类东西"。
 *
 * 2.45 太扁（328dp 宽只有 134dp 高）：抬头 / 大姓名 / 分隔线 / 联系方式四层挤成一团，
 * 只能把姓名压回列表行的字号。2.05 → 160dp，名片有了排版空间，仍明显扁于银行卡的 193dp。
 */
private const val IDENTITY_CARD_RATIO = 2.05f

private val CARD_FACE_SHAPE = RoundedCornerShape(20.dp)
private val IDENTITY_FACE_SHAPE = RoundedCornerShape(18.dp)

private val FACE_PADDING = Spacing.lg
private val IDENTITY_ACCENT_WIDTH = 5.dp
private val IDENTITY_MONOGRAM_SIZE = 34.dp

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

/** 名片水印 / 徽章的几何与透明度，集中具名以过 detekt `MagicNumber`。 */
private object IdentityGeometry {
    const val WATERMARK_ALPHA = 0.05f
    const val WATERMARK_RING_ALPHA = 0.10f
    const val WATERMARK_RADIUS = 0.48f
    const val WATERMARK_RING_RADIUS = 0.66f
    const val WATERMARK_X = 0.99f
    const val WATERMARK_Y = 1.06f
    const val WATERMARK_STROKE_DP = 1f
    const val HEADER_LETTER_SPACING = 1.2f
    const val DIVIDER_ALPHA = 0.65f
    const val MONOGRAM_FILL_ALPHA = 0.16f
    const val MONOGRAM_BORDER_ALPHA = 0.38f
    const val ACCENT_CORNER_DP = 3f
}

/** 卡面文字：深色卡面上的白 / 半透明白。 */
private val FaceWhite = Color.White
private val FaceWhiteDim = Color.White.copy(alpha = 0.72f)
private val FaceWhiteFaint = Color.White.copy(alpha = 0.42f)

/**
 * 卡面渐变是「印刷品图案」，不是 M3 容器 —— 8.4「嵌套必须换档」约束的是
 * surface-container 层级，不适用于卡面本身（拿主题容器色画卡面会糊成一块灰）。
 *
 * ⚠️ 颜色表**不在这个文件**：见 `core/common` 的 [BankCardPalette]。旧版这里放 5 组
 *    渐变、按 `item.id.hashCode()` 挑 —— 同银行的两张卡颜色可以不同、不同银行会撞色，
 *    而且"颜色由谁决定"这条判据被埋在 UI 里没法单测。现在只在 [BankCardFace] 里
 *    把解析结果转成 `Color`，纯逻辑归纯逻辑。
 */

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
    // 颜色由银行决定：把解析结果（含识别出的银行名）一次算好，别在重组里反复查表。
    val tone = remember(item.title, card?.brand, brand, item.id) {
        BankCardPalette.resolve(
            title = item.title,
            storedBrand = card?.brand.orEmpty(),
            cardBrand = brand,
            idHash = item.id.hashCode(),
        )
    }
    val palette = remember(tone.startArgb, tone.endArgb) {
        listOf(Color(tone.startArgb), Color(tone.endArgb))
    }
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
            BankCardTopLine(item = item, brand = brand, bankName = tone.bankName)
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
private fun BankCardTopLine(item: VaultItem, brand: CardBrand, bankName: String?) {
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
                // 银行名与卡组织是两回事：识别出银行就把两家信息都摆出来，
                // 让"为什么这张卡是红色"在卡面上自解释；没识别时维持旧的卡组织文本。
                text = buildList {
                    bankName?.let { add(it) }
                    add(item.card?.brand?.takeIf { it.isNotBlank() } ?: brand.displayName)
                }.distinct().joinToString(" · "),
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
 * 版式按真实名片的层次来：**机构抬头 → 大姓名 → 细分隔线 → 横排联系方式**，
 * 而不是把四层信息压成一条列表行。底色走 M3 容器色（跟随深浅主题），
 * 与银行卡那张「深色印刷品」形成材质差；右下角同心圆水印 + 渐变抬头竖条给纸面一点质感。
 *
 * ⚠️ 联系方式只在真的有值时才画；水印是纯绘制，不拦手势也不进无障碍树。
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
    val tertiary = MaterialTheme.colorScheme.tertiary
    val fallbackLabel = stringResource(R.string.card_face_identity_label)
    val base = if (selected) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }
    val baseEnd = if (selected) {
        MaterialTheme.colorScheme.tertiaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainer
    }
    val fullName = remember(item.identity, item.title) {
        listOf(
            identity?.title,
            identity?.firstName,
            identity?.middleName,
            identity?.lastName,
        ).filter { !it.isNullOrBlank() }.joinToString(" ").ifBlank { item.title }
    }
    val organization = remember(item.identity, fallbackLabel) {
        identity?.company?.takeIf { it.isNotBlank() }
            ?: identity?.city?.takeIf { it.isNotBlank() }
            ?: fallbackLabel
    }
    val detail = remember(item.identity) {
        listOf(identity?.state, identity?.country)
            .filter { !it.isNullOrBlank() }
            .joinToString(" · ")
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
            .background(Brush.linearGradient(listOf(base, baseEnd)))
            .border(
                width = if (selected) 2.dp else 0.5.dp,
                color = if (selected) outline else MaterialTheme.colorScheme.outlineVariant,
                shape = shape,
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(FACE_PADDING),
    ) {
        IdentityWatermark(tint = outline)
        Row(modifier = Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            // 抬头竖条贯穿整张名片，primary → tertiary 渐变，比纯色块更像印刷压边。
            Box(
                modifier = Modifier
                    .width(IDENTITY_ACCENT_WIDTH)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(IdentityGeometry.ACCENT_CORNER_DP.dp))
                    .background(Brush.verticalGradient(listOf(outline, tertiary))),
            )
            Spacer(Modifier.width(Spacing.md))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                IdentityHeader(organization = organization, monogram = fullName.take(1))
                IdentityName(name = fullName, detail = detail)
                IdentityDivider()
                if (contacts.isNotEmpty()) {
                    IdentityContacts(contacts = contacts)
                }
            }
        }
    }
}

/** 纸面质感：右下角外溢的一实一虚两个同心圆，纯装饰。 */
@Composable
private fun BoxScope.IdentityWatermark(tint: Color) {
    Canvas(modifier = Modifier.matchParentSize()) {
        val r = size.minDimension
        val center = Offset(
            size.width * IdentityGeometry.WATERMARK_X,
            size.height * IdentityGeometry.WATERMARK_Y,
        )
        drawCircle(
            color = tint.copy(alpha = IdentityGeometry.WATERMARK_ALPHA),
            radius = r * IdentityGeometry.WATERMARK_RADIUS,
            center = center,
        )
        drawCircle(
            color = tint.copy(alpha = IdentityGeometry.WATERMARK_RING_ALPHA),
            radius = r * IdentityGeometry.WATERMARK_RING_RADIUS,
            center = center,
            style = Stroke(width = IdentityGeometry.WATERMARK_STROKE_DP.dp.toPx()),
        )
    }
}

@Composable
private fun IdentityHeader(organization: String, monogram: String) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = organization,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            letterSpacing = IdentityGeometry.HEADER_LETTER_SPACING.sp,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IdentityMonogram(monogram = monogram)
    }
}

@Composable
private fun IdentityMonogram(monogram: String) {
    val outline = MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier
            .size(IDENTITY_MONOGRAM_SIZE)
            .clip(CircleShape)
            .background(
                Brush.linearGradient(
                    listOf(
                        outline.copy(alpha = IdentityGeometry.MONOGRAM_FILL_ALPHA),
                        MaterialTheme.colorScheme.tertiary
                            .copy(alpha = IdentityGeometry.MONOGRAM_FILL_ALPHA),
                    ),
                ),
            )
            .border(
                width = 1.dp,
                color = outline.copy(alpha = IdentityGeometry.MONOGRAM_BORDER_ALPHA),
                shape = CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = monogram,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = outline,
        )
    }
}

@Composable
private fun IdentityName(name: String, detail: String) {
    Column {
        Text(
            text = name,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (detail.isNotBlank()) {
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun IdentityDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(
                MaterialTheme.colorScheme.outlineVariant
                    .copy(alpha = IdentityGeometry.DIVIDER_ALPHA),
            ),
    )
}

private data class Contact(val value: String, val isEmail: Boolean)

/** 联系方式横排：两项各占一半，比旧版竖排省高度，也更像名片脚注。 */
@Composable
private fun IdentityContacts(contacts: List<Contact>) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        contacts.take(2).forEach { contact ->
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
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
