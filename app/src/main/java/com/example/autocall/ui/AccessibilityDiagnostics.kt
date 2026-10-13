package com.example.autocall.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.autocall.AutoCallAccessibilityService
import com.example.autocall.LanguageManager

/**
 * ★ Task 4.4：无障碍自检页。
 *
 * 逐项显示"录音键 / 免提键 / 接听键 / 挂断键"能否在当前拨号器找到，
 * 并**如实标注**：无障碍通道只能点击系统 UI，其录音件存于厂商私有目录，App 读不到。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccessibilityDiagnosticsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val serviceEnabled = remember { mutableStateOf(AutoCallAccessibilityService.isServiceEnabled(context)) }
    val results = remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }

    fun refresh() {
        serviceEnabled.value = AutoCallAccessibilityService.isServiceEnabled(context)
        results.value = AutoCallAccessibilityService.diagnose(context)
    }

    LaunchedEffect(Unit) { refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(LanguageManager.getString("accessibility_diagnostics.title")) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = LanguageManager.getString("common.back"))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (!serviceEnabled.value) {
                Text(
                    LanguageManager.getString("accessibility_diagnostics.service_disabled"),
                    color = MaterialTheme.colorScheme.error
                )
            }

            val rows = listOf(
                "recordStart" to "accessibility_diagnostics.record_start",
                "speaker" to "accessibility_diagnostics.speaker",
                "answer" to "accessibility_diagnostics.answer",
                "hangup" to "accessibility_diagnostics.hangup"
            )
            rows.forEach { (key, labelKey) ->
                val found = results.value[key] == true
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(LanguageManager.getString(labelKey))
                    Text(
                        LanguageManager.getString(
                            if (found) "accessibility_diagnostics.found" else "accessibility_diagnostics.not_found"
                        ),
                        fontWeight = FontWeight.Bold,
                        color = if (found) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                }
            }

            Button(onClick = { refresh() }, modifier = Modifier.fillMaxWidth()) {
                Text(LanguageManager.getString("accessibility_diagnostics.refresh"))
            }

            Text(
                LanguageManager.getString("accessibility_diagnostics.note_system_side"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
