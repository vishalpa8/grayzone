package com.grayzone.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grayzone.app.GZCard
import com.grayzone.app.data.StreakManager
import com.grayzone.app.ui.theme.*

@Composable
fun StreakCard() {
    val context = LocalContext.current
    val streakManager = remember { StreakManager(context) }
    
    // We don't have a reactive flow setup yet, so we just read it on composition.
    // Real-time updates could use a LaunchedEffect to poll or a Flow from a Room DB.
    val currentStreak = remember { streakManager.getCurrentStreak() }
    val bestStreak = remember { streakManager.getLongestStreak() }
    val achievements = remember { streakManager.getAchievements().filter { it.isUnlocked } }

    GZCard(modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.Star, contentDescription = null, tint = GZAmber, modifier = Modifier.size(32.dp))
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Current Streak", color = GZTextSecondary, fontSize = 13.sp)
                    Text("$currentStreak Days", color = GZTextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("Best", color = GZTextSecondary, fontSize = 13.sp)
                    Text("$bestStreak Days", color = GZTextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
            }

            if (achievements.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Divider(color = GZBorder, thickness = 0.5.dp)
                Spacer(Modifier.height(16.dp))
                Text("Achievements", color = GZTextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(12.dp))
                
                // Horizontal list of badges
                androidx.compose.foundation.lazy.LazyRow(
                    modifier = Modifier.fillMaxWidth(), 
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(achievements.size) { index ->
                        val achievement = achievements[index]
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(GZAmber.copy(alpha = 0.15f))
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(
                                achievement.title,
                                color = GZAmber,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}
