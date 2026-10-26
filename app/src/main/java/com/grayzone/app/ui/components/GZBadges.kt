package com.grayzone.app.ui.components

import androidx.compose.runtime.Composable

@Composable
fun ActivePill(isActive: Boolean) {
    StatusChip(
        text = if (isActive) "Active" else "Off",
        tone = if (isActive) ChipTone.GOOD else ChipTone.NEUTRAL
    )
}
