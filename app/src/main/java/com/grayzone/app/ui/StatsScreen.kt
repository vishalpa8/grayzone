package com.grayzone.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
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
import com.grayzone.app.data.UsageDatabase
import com.grayzone.app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(onBack: () -> Unit = {}) {
    val context = LocalContext.current
    var totalBlocked by remember { mutableStateOf(0) }
    var totalSavedMillis by remember { mutableStateOf(0L) }
    var dailySummary by remember { mutableStateOf<List<com.grayzone.app.data.DailySummaryRow>>(emptyList()) }
    var weeklyTotals by remember { mutableStateOf<List<com.grayzone.app.data.DateTotalRow>>(emptyList()) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        try {
            withContext(Dispatchers.IO) {
                val dao = UsageDatabase.getInstance(context).usageDao()
                totalBlocked = dao.getTotalSessionsBlocked()
                totalSavedMillis = dao.getTotalBlockedDurationMillis() ?: 0L
                val todayKey = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
                dailySummary = dao.getDailySummary(todayKey)
                
                // Weekly chart - get last 7 days
                val calendar = Calendar.getInstance()
                calendar.add(Calendar.DAY_OF_YEAR, -6)
                val fromKey = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(calendar.time)
                weeklyTotals = dao.getWeeklyTotals(fromKey)
            }
        } catch (e: Exception) {
            errorMsg = "Could not load stats: ${e.message}"
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Usage Analytics", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = GZBackground,
                    titleContentColor = GZTextPrimary,
                    navigationIconContentColor = GZTextPrimary
                )
            )
        },
        containerColor = GZBackground
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            if (errorMsg != null) {
                item {
                    Text(errorMsg!!, color = GZRed, fontSize = 14.sp)
                }
            }
            item {
                SummaryCard(totalBlocked, totalSavedMillis)
            }
            item {
                Text("Weekly Overview", style = MaterialTheme.typography.titleMedium, color = GZTextPrimary, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 4.dp))
                Spacer(Modifier.height(12.dp))
                WeeklyBarChart(weeklyTotals)
            }
            item {
                Text("Today's Breakdown", style = MaterialTheme.typography.titleMedium, color = GZTextPrimary, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 4.dp))
                Spacer(Modifier.height(12.dp))
                DailyUsageList(dailySummary)
            }
        }
    }
}

@Composable
private fun SummaryCard(blocked: Int, savedMillis: Long) {
    GZCard(modifier = Modifier.fillMaxWidth(), background = GZPrimaryContainer) {
        Row(
            modifier = Modifier.padding(24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Blocks Resisted", color = GZTextSecondary, fontSize = 14.sp)
                Spacer(Modifier.height(4.dp))
                Text("$blocked", color = GZTextPrimary, fontWeight = FontWeight.ExtraBold, fontSize = 28.sp)
            }
            
            // Vertical Divider
            Box(modifier = Modifier.height(50.dp).width(1.dp).background(GZBorder.copy(alpha = 0.5f)))
            
            Column(modifier = Modifier.weight(1f).padding(start = 24.dp)) {
                Text("Time Saved", color = GZTextSecondary, fontSize = 14.sp)
                val mins = savedMillis / 60000
                val secs = (savedMillis % 60000) / 1000
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("$mins", color = GZTextPrimary, fontWeight = FontWeight.ExtraBold, fontSize = 28.sp)
                    Text("m ", color = GZTextSecondary, fontSize = 16.sp, modifier = Modifier.padding(bottom = 4.dp))
                    Text("$secs", color = GZTextPrimary, fontWeight = FontWeight.ExtraBold, fontSize = 28.sp)
                    Text("s", color = GZTextSecondary, fontSize = 16.sp, modifier = Modifier.padding(bottom = 4.dp))
                }
            }
        }
    }
}

@Composable
private fun DailyUsageList(rows: List<com.grayzone.app.data.DailySummaryRow>) {
    if (rows.isEmpty()) {
        GZCard(modifier = Modifier.fillMaxWidth()) {
            Box(modifier = Modifier.padding(32.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("No usage data recorded for today yet.", color = GZTextTertiary, fontSize = 14.sp)
            }
        }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        rows.forEach { row ->
            GZCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(row.appName, color = GZTextPrimary, fontWeight = FontWeight.Medium, fontSize = 16.sp)
                    val mins = row.totalMillis / 60000
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(GZPrimary.copy(alpha = 0.1f))
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text("${mins}m spent", color = GZPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun WeeklyBarChart(totals: List<com.grayzone.app.data.DateTotalRow>) {
    GZCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp)) {
            if (totals.isEmpty()) {
                Box(modifier = Modifier.padding(16.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("Not enough data to display weekly trends.", color = GZTextTertiary, fontSize = 14.sp)
                }
                return@GZCard
            }
            
            val max = totals.maxOf { it.totalMillis }.coerceAtLeast(1L)
            val dateFormatIn = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            val dateFormatOut = SimpleDateFormat("EEE", Locale.getDefault())

            Row(
                modifier = Modifier.fillMaxWidth().height(160.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.Bottom
            ) {
                totals.forEach { row ->
                    val proportion = (row.totalMillis.toFloat() / max).coerceIn(0.05f, 1f) // Ensure tiny bars are visible
                    val dateObj = try { dateFormatIn.parse(row.dateKey) } catch (e: Exception) { null }
                    val dayName = if (dateObj != null) dateFormatOut.format(dateObj) else "?"
                    val mins = row.totalMillis / 60000

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Bottom,
                        modifier = Modifier.fillMaxHeight()
                    ) {
                        // Value label
                        if (mins > 0) {
                            Text("${mins}m", color = GZTextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                            Spacer(Modifier.height(4.dp))
                        }
                        
                        // Bar
                        Box(
                            modifier = Modifier
                                .width(32.dp)
                                .fillMaxHeight(proportion)
                                .background(GZAccent, shape = RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                        )
                        Spacer(Modifier.height(8.dp))
                        
                        // Day Label
                        Text(dayName, color = GZTextPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}
