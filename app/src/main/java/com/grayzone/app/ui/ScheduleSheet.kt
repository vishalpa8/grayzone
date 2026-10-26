package com.grayzone.app.ui

import com.grayzone.app.ui.components.*

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
import com.grayzone.app.ResumedInterval
import com.grayzone.app.data.ScheduleManager
import com.grayzone.app.data.ScheduleRule
import com.grayzone.app.ui.theme.*
import java.util.Calendar
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleSheet(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val gate = LocalPermissionGate.current
    val scheduleManager = remember { ScheduleManager(context) }
    
    var rules by remember { mutableStateOf(scheduleManager.getScheduleRules()) }
    var isFocusMode by remember { mutableStateOf(scheduleManager.isFocusModeActive()) }
    var focusDuration by remember { mutableStateOf(30) }
    var focusRemainingMillis by remember { mutableStateOf(scheduleManager.getFocusModeRemainingMillis()) }
    
    var showAddRuleDialog by remember { mutableStateOf(false) }
    var confirmStop by remember { mutableStateOf(false) }
    val ticker by com.grayzone.app.rememberResumedTicker(5_000L)
    // While a schedule is blocking apps its rules can't be switched off or deleted (that would be a bypass).
    val scheduleActive = remember(rules, ticker, isFocusMode) { scheduleManager.isCurrentlyScheduled() }
    
    // Countdown only ticks while focus mode is active AND the sheet is visible.
    ResumedInterval(enabled = isFocusMode, intervalMs = 1000L) {
        focusRemainingMillis = scheduleManager.getFocusModeRemainingMillis()
        if (focusRemainingMillis <= 0) {
            isFocusMode = false
            false
        } else {
            true
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

    if (confirmStop) {
        EndDeepWorkDialog(
            onConfirm = {
                confirmStop = false
                scheduleManager.stopFocusMode()
                isFocusMode = false
            },
            onDismiss = { confirmStop = false }
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = GZSurface,
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
                "Deep Work & schedules",
                color = GZTextPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(16.dp))
            
            // Educational Context Box
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(GZPrimaryContainer, RoundedCornerShape(16.dp))
                    .padding(16.dp)
            ) {
                Column {
                    Text("How it works", color = GZPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "During an active schedule or Deep Work, all monitored apps are strictly locked. " +
                        "There are no reflection prompts and no skip timers. Use this to protect your most productive hours and sleep.",
                        color = GZTextSecondary,
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )
                }
            }

            
            Spacer(Modifier.height(24.dp))
            
            // --- Quick Focus Mode ---
            Text("Deep Work", color = GZTextPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(Modifier.height(8.dp))
            
            if (isFocusMode) {
                val remainingMins = focusRemainingMillis / 1000 / 60
                val remainingSecs = (focusRemainingMillis / 1000) % 60
                Text("Active · ${remainingMins}m ${remainingSecs.toString().padStart(2, '0')}s left", color = GZPrimary, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                GZSmallButton(
                    text = "End early",
                    onClick = { confirmStop = true },
                    containerColor = GZRedContainer,
                    contentColor = GZRed
                )
            } else {
                Text("Lock every monitored app for $focusDuration minutes", color = GZTextSecondary, fontSize = 13.sp)
                Slider(
                    value = focusDuration.toFloat(),
                    onValueChange = { focusDuration = it.roundToInt() },
                    valueRange = 5f..120f,
                    steps = 22,
                    colors = SliderDefaults.colors(thumbColor = GZPrimary, activeTrackColor = GZPrimary, inactiveTrackColor = GZSurfaceHigh)
                )
                Button(
                    onClick = {
                        gate.requireSetup {
                            scheduleManager.startFocusMode(focusDuration)
                            isFocusMode = true
                            focusRemainingMillis = scheduleManager.getFocusModeRemainingMillis()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = GZPrimary, contentColor = GZOnPrimary)
                ) {
                    Text("Start Deep Work")
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
                        GZSwitch(
                            checked = rule.isEffectivelyEnabled(),
                            enabled = !(scheduleActive && rule.isEffectivelyEnabled()),
                            onCheckedChange = { enabled ->
                                val apply = {
                                    scheduleManager.toggleRule(rule.id, enabled)
                                    reloadRules()
                                }
                                if (enabled) gate.requireSetup(apply) else apply()
                            }
                        )
                        IconButton(
                            enabled = !scheduleActive,
                            onClick = {
                                scheduleManager.removeRule(rule.id)
                                reloadRules()
                            }
                        ) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = "Delete ${rule.name}",
                                tint = if (scheduleActive) GZTextTertiary else GZRed
                            )
                        }
                    }
                }
            }
            
            if (scheduleActive) {
                Spacer(Modifier.height(8.dp))
                Text("Schedules can't be switched off or deleted while one is active.", color = GZAmber, fontSize = 12.sp)
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
                // A rule that starts and ends at the same minute would never apply.
                enabled = (weekdaysSelected || weekendsSelected) && name.isNotBlank() &&
                    (startHour != endHour || startMin != endMin)
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
