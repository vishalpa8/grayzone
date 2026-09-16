package com.grayzone.app.ui.components



import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.grayzone.app.ui.theme.GZPrimary
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
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Color.White,
            checkedTrackColor = checkedTrackColor,
            uncheckedThumbColor = GZTextTertiary,
            uncheckedTrackColor = GZSurfaceHigh,
            disabledCheckedThumbColor = Color.White.copy(alpha = 0.6f),
            disabledCheckedTrackColor = checkedTrackColor.copy(alpha = 0.4f),
            disabledUncheckedThumbColor = GZTextTertiary.copy(alpha = 0.6f),
            disabledUncheckedTrackColor = GZSurfaceHigh.copy(alpha = 0.6f)
        )
    )
}
