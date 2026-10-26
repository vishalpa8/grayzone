package com.grayzone.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.grayzone.app.ui.theme.GZBorder
import com.grayzone.app.ui.theme.GZBorderSubtle
import com.grayzone.app.ui.theme.GZIsDark
import com.grayzone.app.ui.theme.GZSurface

/** Corner radius shared by every card so the whole app reads as one system. */
val GZCardShape = RoundedCornerShape(20.dp)

/**
 * The standard card. Light theme: white surface with a soft, low shadow and a hairline edge.
 * Dark theme: shadows are invisible on dark surfaces, so it relies on the hairline alone.
 * Pass a tinted [background]/[border] for status cards; tinted cards stay flat.
 */
@Composable
fun GZCard(
    modifier: Modifier = Modifier,
    background: Color = GZSurface,
    border: Color = Color.Unspecified,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val isPlainSurface = background == GZSurface
    val edge = if (border != Color.Unspecified) border else if (GZIsDark) GZBorder else GZBorderSubtle
    val elevated = isPlainSurface && !GZIsDark
    Box(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (elevated) Modifier.shadow(
                    elevation = 6.dp,
                    shape = GZCardShape,
                    ambientColor = Color.Black.copy(alpha = 0.04f),
                    spotColor = Color.Black.copy(alpha = 0.07f)
                ) else Modifier
            )
            .clip(GZCardShape)
            .background(background)
            .border(1.dp, edge, GZCardShape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
    ) { content() }
}
