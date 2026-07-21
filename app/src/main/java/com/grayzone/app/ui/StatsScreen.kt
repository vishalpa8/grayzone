package com.grayzone.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.grayzone.app.GZCard
import com.grayzone.app.data.DateTotalRow
import com.grayzone.app.data.UsageDatabase
import com.grayzone.app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

private data class StatsSnapshot(
    val blocked: Int,
    val savedMs: Long,
    val daily: List<com.grayzone.app.data.DailySummaryRow>,
    val weekly: List<DateTotalRow>,
    val totalEvents: Int,
    val peakHour: Int?
)

/** Retains the last loaded stats across tab switches so re-entry is instant. */
private object StatsCache {
    @Volatile var snapshot: StatsSnapshot? = null
}

private fun formatPeakHour(h: Int): String {
    val amPm1 = if (h < 12) "AM" else "PM"
    val h1    = if (h % 12 == 0) 12 else h % 12
    val next  = (h + 1) % 24
    val amPm2 = if (next < 12) "AM" else "PM"
    val h2    = if (next % 12 == 0) 12 else next % 12
    return "$h1:00 $amPm1 – $h2:00 $amPm2"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    // Seed from the last in-memory snapshot so returning to this tab paints
    // instantly instead of flashing a full-screen spinner and re-querying.
    val cached = remember { StatsCache.snapshot }
    var reloadKey by remember { mutableIntStateOf(0) }
    var isLoading by remember { mutableStateOf(cached == null) }
    var totalBlocked by remember { mutableIntStateOf(cached?.blocked ?: 0) }
    var totalSavedMillis by remember { mutableLongStateOf(cached?.savedMs ?: 0L) }
    var dailySummary by remember { mutableStateOf<List<com.grayzone.app.data.DailySummaryRow>>(cached?.daily ?: emptyList()) }
    var weeklyTotals by remember { mutableStateOf<List<DateTotalRow>>(cached?.weekly ?: emptyList()) }
    var mostDistractingTime by remember { mutableStateOf(cached?.peakHour?.let { formatPeakHour(it) }) }
    var totalEvents by remember { mutableIntStateOf(cached?.totalEvents ?: -1) }  // -1 = not yet loaded
    var errorMsg by remember { mutableStateOf<String?>(null) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) reloadKey++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(reloadKey) {
        // Only block with a spinner when there's nothing to show yet; otherwise
        // keep the cached data on screen and refresh it silently.
        if (StatsCache.snapshot == null) isLoading = true
        errorMsg = null
        try {
            val snap = withContext(Dispatchers.IO) {
                val dao      = UsageDatabase.getInstance(context).usageDao()
                val blocked  = dao.getTotalSessionsBlocked()
                val savedMs  = dao.getTotalBlockedDurationMillis() ?: 0L
                val todayKey = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
                val daily    = dao.getDailySummary(todayKey)
                val cal      = Calendar.getInstance().also { it.add(Calendar.DAY_OF_YEAR, -6) }
                val fromKey  = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(cal.time)
                val weekly   = fillWeeklyTotals(dao.getWeeklyTotals(fromKey))
                val total    = dao.getTotalEventCount()
                
                val totals = dao.getStartTimeTotals()
                val peak = totals.maxByOrNull { it.totalMillis }?.startTime?.toInt()

                StatsSnapshot(blocked, savedMs, daily, weekly, total, peak)
            }
            // All state assignments on main thread — no partial-update races
            StatsCache.snapshot = snap
            totalBlocked        = snap.blocked
            totalSavedMillis    = snap.savedMs
            dailySummary        = snap.daily
            weeklyTotals        = snap.weekly
            totalEvents         = snap.totalEvents
            mostDistractingTime = snap.peakHour?.let { formatPeakHour(it) }
        } catch (e: Exception) {
            if (e is kotlin.coroutines.cancellation.CancellationException) throw e
            android.util.Log.e("StatsScreen", "Could not load stats", e)
            errorMsg = "Could not load stats: ${e.javaClass.simpleName}"
        } finally {
            isLoading = false
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
        if (isLoading) {
            Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                com.grayzone.app.GZLoadingSpinner()
            }
        } else if (errorMsg != null) {
            Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding).padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("⚠️", fontSize = 40.sp)
                    Spacer(Modifier.height(16.dp))
                    Text("Couldn't load stats", color = GZTextPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Spacer(Modifier.height(8.dp))
                    Text("Try closing and reopening the app.", color = GZTextSecondary, fontSize = 14.sp)
                }
            }
        } else if (totalEvents == 0) {
            // Clean empty state — no sessions recorded yet
            Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding).padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("📊", fontSize = 48.sp)
                    Spacer(Modifier.height(16.dp))
                    Text("No usage data yet", color = GZTextPrimary, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Start monitoring apps on the Apps tab.\nYour usage stats will appear here after your first session.",
                        color = GZTextSecondary,
                        fontSize = 14.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                item {
                    SummaryCard(totalBlocked, totalSavedMillis)
                }
                if (mostDistractingTime != null) {
                    item {
                        DistractingTimeCard(mostDistractingTime!!)
                    }
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
                    val secs = (row.totalMillis % 60000) / 1000
                    val timeLabel = if (mins > 0) "${mins}m ${secs}s spent" else "${secs}s spent"
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(GZPrimary.copy(alpha = 0.1f))
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(timeLabel, color = GZPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                }
            }
        }
    }
}

private fun fillWeeklyTotals(raw: List<DateTotalRow>): List<DateTotalRow> {
    val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    val byDate = raw.associateBy { it.dateKey }
    val cal = Calendar.getInstance()
    cal.add(Calendar.DAY_OF_YEAR, -6)
    return buildList {
        repeat(7) {
            val key = dateFormat.format(cal.time)
            add(byDate[key] ?: DateTotalRow(key, 0L))
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
    }
}

@Composable
private fun WeeklyBarChart(totals: List<DateTotalRow>) {
    GZCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp)) {
            val max = totals.maxOfOrNull { it.totalMillis }?.coerceAtLeast(1L) ?: 1L
            val dateFormatIn = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            val dateFormatOut = SimpleDateFormat("EEE", Locale.getDefault())

            Row(
                modifier = Modifier.fillMaxWidth().height(160.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.Bottom
            ) {
                totals.forEach { row ->
                    val proportion = if (row.totalMillis == 0L) {
                        0f
                    } else {
                        (row.totalMillis.toFloat() / max).coerceIn(0.05f, 1f)
                    }
                    val dateObj = try { dateFormatIn.parse(row.dateKey) } catch (e: Exception) { null }
                    val dayName = if (dateObj != null) dateFormatOut.format(dateObj) else "?"
                    val mins = row.totalMillis / 60000

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Bottom,
                        modifier = Modifier.fillMaxHeight()
                    ) {
                        if (mins > 0) {
                            Text("${mins}m", color = GZTextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                            Spacer(Modifier.height(4.dp))
                        } else {
                            Spacer(Modifier.height(18.dp))
                        }
                        
                        var visible by remember { mutableStateOf(false) }
                        LaunchedEffect(Unit) { visible = true }
                        val animatedProportion by androidx.compose.animation.core.animateFloatAsState(
                            targetValue = if (visible) proportion else 0f,
                            animationSpec = androidx.compose.animation.core.tween(durationMillis = 800, delayMillis = 100),
                            label = "bar_${row.dateKey}"
                        )

                        Box(
                            modifier = Modifier
                                .width(32.dp)
                                .fillMaxHeight(animatedProportion)
                                .background(GZAccent, shape = RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(dayName, color = GZTextPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun DistractingTimeCard(timeRange: String) {
    GZCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(48.dp).clip(CircleShape).background(GZAmber.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Text("🔥", fontSize = 24.sp)
            }
            Spacer(Modifier.width(16.dp))
            Column {
                Text("Most Distracting Time", color = GZTextSecondary, fontSize = 14.sp)
                Spacer(Modifier.height(4.dp))
                Text(timeRange, color = GZTextPrimary, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp)
            }
        }
    }
}
