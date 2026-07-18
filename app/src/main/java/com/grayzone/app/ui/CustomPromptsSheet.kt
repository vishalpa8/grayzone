package com.grayzone.app.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.grayzone.app.Prompts
import com.grayzone.app.PrefsKeys
import com.grayzone.app.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomPromptsSheet(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE) }
    val gson = remember { Gson() }
    
    var useCustomOnly by remember { mutableStateOf(prefs.getBoolean(PrefsKeys.USE_CUSTOM_PROMPTS_ONLY, false)) }
    
    val initialPrompts = remember {
        val json = prefs.getString(PrefsKeys.CUSTOM_PROMPTS_JSON, null)
        if (json != null) {
            try {
                val type = object : TypeToken<List<String>>() {}.type
                gson.fromJson<List<String>>(json, type) ?: emptyList()
            } catch (e: Exception) { emptyList() }
        } else {
            emptyList()
        }
    }
    
    var prompts by remember { mutableStateOf(initialPrompts) }
    var newPrompt by remember { mutableStateOf("") }
    
    fun savePrompts(newList: List<String>) {
        prompts = newList
        prefs.edit().putString(PrefsKeys.CUSTOM_PROMPTS_JSON, gson.toJson(newList)).apply()
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
                "Custom Prompts",
                color = GZTextPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            Text("Add your own reflection questions to the pause screen.", color = GZTextSecondary, fontSize = 14.sp)
            
            Spacer(Modifier.height(24.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Use Custom Only", color = GZTextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    Text("Hide default reflection prompts", color = GZTextSecondary, fontSize = 13.sp)
                }
                Switch(
                    checked = useCustomOnly,
                    onCheckedChange = { 
                        useCustomOnly = it
                        prefs.edit().putBoolean(PrefsKeys.USE_CUSTOM_PROMPTS_ONLY, it).apply()
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = GZPrimary,
                        uncheckedThumbColor = GZTextTertiary,
                        uncheckedTrackColor = GZSurfaceHigh
                    )
                )
            }
            
            Spacer(Modifier.height(24.dp))
            Divider(color = GZBorder, thickness = 0.5.dp)
            Spacer(Modifier.height(24.dp))
            
            // Add new prompt
            OutlinedTextField(
                value = newPrompt,
                onValueChange = { newPrompt = it },
                label = { Text("New Prompt", color = GZTextTertiary) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = GZPrimary,
                    unfocusedBorderColor = GZBorder,
                    focusedTextColor = GZTextPrimary,
                    unfocusedTextColor = GZTextPrimary,
                    cursorColor = GZPrimary
                ),
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    IconButton(
                        onClick = {
                            if (newPrompt.isNotBlank()) {
                                savePrompts(prompts + newPrompt.trim())
                                newPrompt = ""
                            }
                        },
                        enabled = newPrompt.isNotBlank()
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = "Add", tint = if (newPrompt.isNotBlank()) GZPrimary else GZTextTertiary)
                    }
                }
            )
            
            Spacer(Modifier.height(32.dp))
            Text("Your Prompts", color = GZTextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
            Spacer(Modifier.height(16.dp))
            
            if (prompts.isEmpty()) {
                Text("No custom prompts added yet.", color = GZTextTertiary, modifier = Modifier.padding(bottom = 16.dp))
            } else {
                prompts.forEachIndexed { index, prompt ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "\"$prompt\"",
                            color = GZTextPrimary,
                            modifier = Modifier.weight(1f),
                            fontStyle = FontStyle.Italic
                        )
                        IconButton(onClick = {
                            val newList = prompts.toMutableList()
                            newList.removeAt(index)
                            savePrompts(newList)
                        }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = GZRed.copy(alpha = 0.8f))
                        }
                    }
                    if (index < prompts.lastIndex) {
                        Divider(color = GZBorder.copy(alpha = 0.5f), thickness = 0.5.dp)
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
            
            if (!useCustomOnly) {
                Divider(color = GZBorder, thickness = 0.5.dp)
                Spacer(Modifier.height(24.dp))
                
                Text("Default Prompts", color = GZTextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                Spacer(Modifier.height(8.dp))
                Text("These will be shown randomly alongside your custom prompts.", color = GZTextSecondary, fontSize = 13.sp)
                Spacer(Modifier.height(16.dp))
                
                Prompts.DEFAULT.forEachIndexed { index, prompt ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "\"$prompt\"",
                            color = GZTextSecondary,
                            modifier = Modifier.weight(1f),
                            fontStyle = FontStyle.Italic
                        )
                    }
                    if (index < Prompts.DEFAULT.lastIndex) {
                        Divider(color = GZBorder.copy(alpha = 0.3f), thickness = 0.5.dp)
                    }
                }
            }
        }
    }
}
