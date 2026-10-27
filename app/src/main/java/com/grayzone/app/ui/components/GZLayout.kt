package com.grayzone.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grayzone.app.ui.theme.*

/** Horizontal page gutter used by every screen. */
val GZGutter = 20.dp

/**
 * The one screen header: large title, optional subtitle, optional back arrow and trailing slot.
 * Same top spacing on every tab so switching tabs never makes the title jump.
 */
@Composable
fun ScreenHeader(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = if (onBack != null) 8.dp else GZGutter, end = GZGutter, top = 24.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = GZTextPrimary)
            }
            Spacer(Modifier.width(4.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                color = GZTextPrimary,
                fontSize = if (onBack != null) 22.sp else 28.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.5).sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(subtitle, color = GZTextSecondary, fontSize = 14.sp)
            }
        }
        trailing()
    }
}

/** Rounded-square tinted holder for a leading icon. */
@Composable
fun IconBadge(icon: ImageVector, tint: Color, modifier: Modifier = Modifier, size: Int = 40) {
    Box(
        modifier = modifier
            .size(size.dp)
            .clip(RoundedCornerShape((size * 0.3f).dp))
            .background(tint.copy(alpha = if (GZIsDark) 0.16f else 0.10f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size((size * 0.55f).dp))
    }
}

enum class ChipTone { NEUTRAL, PRIMARY, GOOD, WARN, BAD }

/** Small status label, e.g. "Locked · 12m". Replaces ad-hoc coloured text and emoji. */
@Composable
fun StatusChip(text: String, tone: ChipTone, modifier: Modifier = Modifier) {
    val color = when (tone) {
        ChipTone.NEUTRAL -> GZTextSecondary
        ChipTone.PRIMARY -> GZPrimary
        ChipTone.GOOD -> GZGreen
        ChipTone.WARN -> GZAmber
        ChipTone.BAD -> GZRed
    }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = if (GZIsDark) 0.16f else 0.10f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(6.dp).clip(RoundedCornerShape(50)).background(color))
        Spacer(Modifier.width(6.dp))
        Text(text, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** A tappable row inside a card: icon, title, subtitle, chevron (or a custom trailing control). */
@Composable
fun SettingsRow(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // clickable(enabled = false) keeps the row a button for TalkBack and announces "disabled".
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            IconBadge(icon, tint = if (enabled) GZPrimary else GZTextTertiary, size = 36)
            Spacer(Modifier.width(14.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                color = if (enabled) GZTextPrimary else GZTextTertiary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp
            )
            if (subtitle != null) {
                Text(subtitle, color = GZTextSecondary, fontSize = 13.sp)
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        } else if (onClick != null) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = GZTextTertiary
            )
        }
    }
}

/** Hairline between rows inside one card. */
@Composable
fun RowDivider(startIndent: Int = 18) {
    Divider(
        color = GZBorderSubtle,
        thickness = 1.dp,
        modifier = Modifier.padding(start = startIndent.dp)
    )
}

/** Label + value header and a themed slider, used for every duration setting. */
@Composable
fun LabeledSlider(
    label: String,
    valueText: String,
    help: String?,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    enabled: Boolean = true,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                color = if (enabled) GZTextPrimary else GZTextTertiary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                modifier = Modifier.weight(1f)
            )
            Text(
                valueText,
                color = if (enabled) GZPrimary else GZTextTertiary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp
            )
        }
        if (help != null) {
            Text(help, color = GZTextSecondary, fontSize = 12.sp)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            enabled = enabled,
            onValueChangeFinished = onValueChangeFinished,
            colors = SliderDefaults.colors(
                thumbColor = GZPrimary,
                activeTrackColor = GZPrimary,
                inactiveTrackColor = GZSurfaceHigh,
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent,
                disabledThumbColor = GZTextTertiary,
                disabledActiveTrackColor = GZTextTertiary.copy(alpha = 0.5f),
                disabledInactiveTrackColor = GZSurfaceHigh
            )
        )
    }
}
