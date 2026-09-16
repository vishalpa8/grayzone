package com.grayzone.app.ui.components



import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grayzone.app.ui.theme.GZGreen
import com.grayzone.app.ui.theme.GZGreenContainer
import com.grayzone.app.ui.theme.GZRed
import com.grayzone.app.ui.theme.GZRedContainer

@Composable
fun ActivePill(isActive: Boolean) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (isActive) GZGreenContainer else GZRedContainer)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(if (isActive) GZGreen else GZRed)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            if (isActive) "Active" else "Inactive",
            color = if (isActive) GZGreen else GZRed,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
    }
}
