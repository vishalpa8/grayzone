package com.grayzone.app.ui

import android.app.TimePickerDialog
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grayzone.app.data.ScheduleManager
import com.grayzone.app.data.ScheduleRule
import com.grayzone.app.ui.theme.*
import kotlinx.coroutines.delay
import java.util.Calendar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleSheet(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scheduleManager = remember { ScheduleManager(context) }
    
    var rules by remember { mutableStateOf(scheduleManager.getScheduleRules()) }
    var isFocusMode by remember { mutableStateOf(scheduleManager.isFocusModeActive()) }
    var focusDuration by remember { mutableStateOf(30) }
    var focusRemainingMillis by remember { mutableStateOf(scheduleManager.getFocusModeRemainingMillis()) }
    
    var showAddRuleDialog by remember { mutableStateOf(false) }
    
    LaunchedEffect(isFocusMode) {
        if (isFocusMode) {
            while (true) {
                focusRemainingMillis = scheduleManager.getFocusModeRemainingMillis()
                if (focusRemainingMillis <= 0) {
                    isFocusMode = false
                    break
                }
                delay(1000)
            }
        }
    }
    
    fun reloadRules() {
        rules = scheduleManager.getScheduleRules()
    }

    if (showAddRuleDialog) {
        AddRuleDialog(
            context = context,
            onDismiss = { showAddRuleDialog = false },
            onSave = { rule ->
                scheduleManager.addRule(rule)
                reloadRules()
                showAddRuleDialog = false
            }
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = GZSurfaceElevated,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp)
                .padding(bottom = 32.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                "Focus Mode & Schedule",
                color = GZTextPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(16.dp))
            
            // Educational Context Box
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(GZPrimary.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
                    .padding(16.dp)
            ) {
                Column {
                    Text("How it works", color = GZPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "During an active schedule or focus mode, all monitored apps are strictly locked. " +
                        "There are no reflection prompts and no skip timers. Use this to protect your most productive hours and sleep.",
                        color = GZTextSecondary,
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )
                }
            }

            
            Spacer(Modifier.height(24.dp))
            
            // --- Quick Focus Mode ---
            Text("Quick Focus Mode", color = GZTextPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(Modifier.height(8.dp))
            
            if (isFocusMode) {
                val remainingMins = focusRemainingMillis / 1000 / 60
                val remainingSecs = (focusRemainingMillis / 1000) % 60
                Text("Focus mode active for ${remainingMins}m ${remainingSecs}s", color = GZAccent, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        scheduleManager.stopFocusMode()
                        isFocusMode = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = GZRed, contentColor = Color.White)
                ) {
                    Text("Stop Focus Mode")
                }
            } else {
                Text("Lock all apps immediately for $focusDuration minutes", color = GZTextSecondary, fontSize = 13.sp)
                Slider(
                    value = focusDuration.toFloat(),
                    onValueChange = { focusDuration = it.toInt() },
                    valueRange = 5f..120f,
                    steps = 22,
                    colors = SliderDefaults.colors(thumbColor = GZAccent, activeTrackColor = GZAccent)
                )
                Button(
                    onClick = {
                        scheduleManager.startFocusMode(focusDuration)
                        isFocusMode = true
                        focusRemainingMillis = scheduleManager.getFocusModeRemainingMillis()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = GZAccent, contentColor = Color.White)
                ) {
                    Text("Start Focus Mode")
                }
            }
            
            Spacer(Modifier.height(24.dp))
            Divider(color = GZBorder, thickness = 0.5.dp)
            Spacer(Modifier.height(24.dp))
            
            // --- Schedule Rules ---
            Text("Automated Schedules", color = GZTextPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(Modifier.height(16.dp))
            
            if (rules.isEmpty()) {
                Text("No schedules configured.", color = GZTextTertiary, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
            } else {
                rules.forEach { rule ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(rule.name, color = GZTextPrimary, fontWeight = FontWeight.SemiBold)
                            val startStr = String.format("%02d:%02d", rule.startHour, rule.startMinute)
                            val endStr = String.format("%02d:%02d", rule.endHour, rule.endMinute)
                            Text("$startStr to $endStr", color = GZTextSecondary, fontSize = 13.sp)
                        }
                        Switch(
                            checked = rule.enabled,
                            onCheckedChange = { 
                                scheduleManager.toggleRule(rule.id, it)
                                reloadRules()
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = GZPrimary,
                                uncheckedThumbColor = GZTextTertiary,
                                uncheckedTrackColor = GZSurfaceHigh
                            )
                        )
                        IconButton(onClick = {
                            scheduleManager.removeRule(rule.id)
                            reloadRules()
                        }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = GZRed)
                        }
                    }
                }
            }
            
            Spacer(Modifier.height(24.dp))
            
            Button(
                onClick = { showAddRuleDialog = true },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = GZPrimaryContainer, contentColor = GZTextPrimary),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("+ Add Custom Schedule", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
fun AddRuleDialog(
    context: Context,
    onDismiss: () -> Unit,
    onSave: (ScheduleRule) -> Unit
) {
    var name by remember { mutableStateOf("My Schedule") }
    
    var startHour by remember { mutableStateOf(9) }
    var startMin by remember { mutableStateOf(0) }
    var endHour by remember { mutableStateOf(17) }
    var endMin by remember { mutableStateOf(0) }
    
    val startStr = String.format("%02d:%02d", startHour, startMin)
    val endStr = String.format("%02d:%02d", endHour, endMin)
    
    var weekdaysSelected by remember { mutableStateOf(true) }
    var weekendsSelected by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = GZSurface,
        titleContentColor = GZTextPrimary,
        textContentColor = GZTextSecondary,
        title = { Text("Add Custom Schedule", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Schedule Name") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = GZPrimary,
                        unfocusedBorderColor = GZBorder,
                        focusedTextColor = GZTextPrimary,
                        unfocusedTextColor = GZTextPrimary
                    )
                )
                Spacer(Modifier.height(16.dp))
                
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Start Time:", color = GZTextPrimary, modifier = Modifier.weight(1f))
                    Button(
                        onClick = {
                            TimePickerDialog(context, { _, hour, minute ->
                                startHour = hour
                                startMin = minute
                            }, startHour, startMin, true).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = GZSurfaceElevated, contentColor = GZPrimary)
                    ) {
                        Text(startStr, fontWeight = FontWeight.Bold)
                    }
                }
                
                Spacer(Modifier.height(12.dp))
                
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("End Time:", color = GZTextPrimary, modifier = Modifier.weight(1f))
                    Button(
                        onClick = {
                            TimePickerDialog(context, { _, hour, minute ->
                                endHour = hour
                                endMin = minute
                            }, endHour, endMin, true).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = GZSurfaceElevated, contentColor = GZPrimary)
                    ) {
                        Text(endStr, fontWeight = FontWeight.Bold)
                    }
                }
                
                Spacer(Modifier.height(24.dp))
                Text("Applies on:", color = GZTextPrimary)
                
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = weekdaysSelected,
                        onCheckedChange = { weekdaysSelected = it },
                        colors = CheckboxDefaults.colors(checkedColor = GZPrimary)
                    )
                    Text("Weekdays (Mon-Fri)", color = GZTextSecondary)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = weekendsSelected,
                        onCheckedChange = { weekendsSelected = it },
                        colors = CheckboxDefaults.colors(checkedColor = GZPrimary)
                    )
                    Text("Weekends (Sat-Sun)", color = GZTextSecondary)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val days = mutableSetOf<Int>()
                    if (weekdaysSelected) days.addAll(ScheduleManager.WEEKDAYS)
                    if (weekendsSelected) days.addAll(ScheduleManager.WEEKEND)
                    
                    onSave(
                        ScheduleRule(
                            name = name,
                            startHour = startHour,
                            startMinute = startMin,
                            endHour = endHour,
                            endMinute = endMin,
                            daysOfWeek = days
                        )
                    )
                },
                enabled = (weekdaysSelected || weekendsSelected) && name.isNotBlank()
            ) {
                Text("Save", color = GZPrimary, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = GZTextTertiary)
            }
        }
    )
}
