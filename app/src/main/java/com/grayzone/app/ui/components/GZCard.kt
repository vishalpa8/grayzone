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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.grayzone.app.ui.theme.GZBorder
import com.grayzone.app.ui.theme.GZSurface

/**
 * A standard, reusable card component across the entire app.
 * By centralizing this, changing corner radii, borders, or default colors
 * updates the entire app automatically.
 */
@Composable
fun GZCard(
    modifier: Modifier = Modifier,
    background: Color = GZSurface,
    border: Color = GZBorder,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(background)
            .border(1.dp, border, RoundedCornerShape(16.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
    ) { content() }
}
