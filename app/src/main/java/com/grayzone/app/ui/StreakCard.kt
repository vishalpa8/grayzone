package com.grayzone.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.grayzone.app.data.StreakManager
import com.grayzone.app.ui.components.*
import com.grayzone.app.ui.theme.*

@Composable
fun StreakCard() {
    val context = LocalContext.current
    val streakManager = remember { StreakManager(context) }
    val lifecycleOwner = LocalLifecycleOwner.current

    var currentStreak by remember { mutableIntStateOf(streakManager.getCurrentStreak()) }
    var bestStreak by remember { mutableIntStateOf(streakManager.getLongestStreak()) }
    var achievements by remember { mutableStateOf(streakManager.getAchievements().filter { it.isUnlocked }) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                currentStreak = streakManager.getCurrentStreak()
                bestStreak = streakManager.getLongestStreak()
                achievements = streakManager.getAchievements().filter { it.isUnlocked }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    GZCard(modifier = Modifier.padding(horizontal = GZGutter)) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(Icons.Filled.LocalFireDepartment, tint = GZStreakFire)
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Current streak", color = GZTextSecondary, fontSize = 13.sp)
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            "$currentStreak",
                            color = GZTextPrimary,
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = (-0.5).sp
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (currentStreak == 1) "day" else "days",
                            color = GZTextSecondary,
                            fontSize = 14.sp,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("Best", color = GZTextTertiary, fontSize = 12.sp)
                    Text(
                        "$bestStreak ${if (bestStreak == 1) "day" else "days"}",
                        color = GZTextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            if (achievements.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(achievements) { achievement ->
                        Text(
                            achievement.title,
                            color = GZStreakGold,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(GZAchievementBg)
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        }
    }
}
