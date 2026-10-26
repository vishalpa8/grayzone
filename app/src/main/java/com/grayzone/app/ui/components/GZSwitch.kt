package com.grayzone.app.ui.components

import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.grayzone.app.ui.theme.GZBorder
import com.grayzone.app.ui.theme.GZOnPrimary
import com.grayzone.app.ui.theme.GZPrimary
import com.grayzone.app.ui.theme.GZSurface
import com.grayzone.app.ui.theme.GZSurfaceHigh
import com.grayzone.app.ui.theme.GZTextTertiary

@Composable
fun GZSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    checkedTrackColor: Color = GZPrimary
) {
    val checkedThumb = if (checkedTrackColor == GZPrimary) GZOnPrimary else Color.White
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedThumbColor = checkedThumb,
            checkedTrackColor = checkedTrackColor,
            checkedBorderColor = Color.Transparent,
            // Off state must still read as a control (>=3:1) against white and dark cards.
            uncheckedThumbColor = GZTextTertiary,
            uncheckedTrackColor = GZSurfaceHigh,
            uncheckedBorderColor = GZTextTertiary,
            disabledCheckedThumbColor = GZSurface,
            disabledCheckedTrackColor = checkedTrackColor.copy(alpha = 0.4f),
            disabledUncheckedThumbColor = GZTextTertiary.copy(alpha = 0.5f),
            disabledUncheckedTrackColor = GZSurfaceHigh.copy(alpha = 0.6f)
        )
    )
}
