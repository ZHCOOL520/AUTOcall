package com.example.autocall

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.autocall.ui.theme.AUTOCallTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

// 常量定义
private const val GITHUB_RELEASES_URL = "https://github.com/ZHCOOL520/AUTOcall/releases"
private const val GITHUB_API_URL = "https://api.github.com/repos/ZHCOOL520/AUTOcall/releases/latest"
private const val PREFS_NAME = "app_prefs"
private const val DISCLAIMER_KEY = "disclaimer_accepted"
private const val UPDATE_CHECK_PREFS = "update_check"
private const val LAST_CHECK_TIME_KEY = "last_check_time"

class MainActivity : ComponentActivity() {

    private val viewModel: AutoCallViewModel by viewModels()

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    private val installApkLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { }

    private val openDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { importFileFromUri(it) }
    }

    private val importAudioLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            viewModel.importAudioFile(this, it)
        }
    }

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        uri?.let {
            viewModel.exportCallRecordsToUri(this, it)
        }
    }

    private fun importFromClipboard() {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager
        if (clipboard == null) {
            viewModel.updateStatus(LanguageManager.getString("status.clipboard_service_unavailable"))
            return
        }
        if (clipboard.hasPrimaryClip()) {
            val clipData = clipboard.primaryClip
            if (clipData != null && clipData.itemCount > 0) {
                val text = clipData.getItemAt(0).text?.toString()
                if (!text.isNullOrEmpty()) {
                    viewModel.importFromClipboard(this, text)
                } else {
                    viewModel.updateStatus(LanguageManager.getString("status.clipboard_empty"))
                }
            }
        } else {
            viewModel.updateStatus(LanguageManager.getString("status.clipboard_empty"))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        checkAndRequestPermissions()
        
        // 注意：不在这里初始化LanguageManager，等待ViewModel加载设置后自动初始化
        // LanguageManager会在loadLanguageSettings()中被正确初始化

        setContent {
            AUTOCallTheme {
                var showDisclaimer by remember { mutableStateOf(!isDisclaimerAccepted()) }
                
                if (showDisclaimer) {
                    DisclaimerDialog(
                        onAccept = {
                            acceptDisclaimer()
                            showDisclaimer = false
                        },
                        onDecline = {
                            finish()
                        }
                    )
                } else {
                    val navController = rememberNavController()
                    NavHost(navController = navController, startDestination = "main") {
                        composable("main") {
                            MainScreen(
                                viewModel = viewModel,
                                onImportFile = { openDocumentLauncher.launch("*/*") },
                                onExport = {
                                    val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                                    exportLauncher.launch("call_records_$ts.csv")
                                },
                                onImportClipboard = { importFromClipboard() },
                                onNavigateToSettings = { navController.navigate("settings") }
                            )
                        }
                        composable("settings") {
                            SettingsScreen(
                                viewModel = viewModel,
                                onBack = { navController.popBackStack() },
                                onImportAudio = { importAudioLauncher.launch("audio/*") },
                                onDeclineDisclaimer = {
                                    declineDisclaimer()
                                    finish()
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    private fun isDisclaimerAccepted(): Boolean {
        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        return prefs.getBoolean("disclaimer_accepted", false)
    }

    @SuppressLint("UseKtx")
    private fun acceptDisclaimer() {
        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        prefs.edit().putBoolean("disclaimer_accepted", true).apply()
    }

    @SuppressLint("UseKtx")
    private fun declineDisclaimer() {
        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        prefs.edit().putBoolean("disclaimer_accepted", false).apply()
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.RECORD_AUDIO
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        val toRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (toRequest.isNotEmpty()) {
            requestPermissionLauncher.launch(toRequest.toTypedArray())
        }
    }

    private fun importFileFromUri(uri: Uri) {
        try {
            val inputStream = contentResolver.openInputStream(uri)
            val fileName = getFileNameFromUri(uri)
            val file = File(cacheDir, fileName)
            inputStream?.use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
            when {
                fileName.endsWith(".csv", true) -> viewModel.importFromCSV(this, file.absolutePath)
                fileName.endsWith(".xlsx", true) || fileName.endsWith(".xls", true) ->
                    viewModel.importFromExcel(this, file.absolutePath)
                else -> viewModel.updateStatus(LanguageManager.getString("status.file_format_unsupported"))
            }
        } catch (e: Exception) {
            Log.e("MainActivity", LanguageManager.getString("log.import_file_failed", e.message ?: ""), e)
            viewModel.updateStatus(LanguageManager.getString("status.import_failed", e.message ?: ""))
        }
    }

    private fun getFileNameFromUri(uri: Uri): String {
        return contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val col = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && col != -1) cursor.getString(col) else "imported_file.csv"
        } ?: "imported_file.csv"
    }
}

@SuppressLint("UseKtx")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: AutoCallViewModel,
    onImportFile: () -> Unit,
    onExport: () -> Unit,
    onImportClipboard: () -> Unit,
    onNavigateToSettings: () -> Unit
) {
    val context = LocalContext.current
    val phoneList by viewModel.phoneList.collectAsState()
    val currentStatus by viewModel.currentStatus.collectAsState()
    val isRunning by viewModel.isRunning.collectAsState()
    val progress by viewModel.progress.collectAsState()
    val statistics by viewModel.statistics.collectAsState()
    val sortByBalance by viewModel.sortByBalance.collectAsState()
    val sortByCallCount by viewModel.sortByCallCount.collectAsState()
    val isPaused by viewModel.isPaused.collectAsState()
    // 关键修复：监听语言状态变化，触发UI重组
    val selectedLanguage by viewModel.selectedLanguage.collectAsState()

    var showAboutDialog by remember { mutableStateOf(false) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    var updateInfo by remember { mutableStateOf<Triple<String, String, String>?>(null) }

    LaunchedEffect(Unit) {
        val audioDir = File(context.getExternalFilesDir(null), "audio")
        viewModel.setAudioDirectory(context, audioDir.absolutePath)
        
        // 自动检查更新
        if (viewModel.autoCheckUpdateEnabled.value) {
            checkUpdateIfNeeded(
                context = context,
                viewModel = viewModel,
                onNewVersionFound = { version, content, url ->
                    showUpdateDialog = true
                    updateInfo = Triple(version, content, url)
                }
            )
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        LanguageManager.getString("main_screen.title"),
                        modifier = Modifier.clickable { showAboutDialog = true }
                    ) 
                },
                actions = {
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = LanguageManager.getString("common.settings"))
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
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                StatusCard(status = currentStatus, progress = progress, total = phoneList.size)

                ControlButtons(
                    isRunning = isRunning,
                    isPaused = isPaused,
                    onStart = { viewModel.startAutoCall(context) },
                    onPause = { viewModel.pauseAutoCall() },
                    onResume = { viewModel.resumeAutoCall(context) },
                    onStop = { viewModel.stopAutoCall() },
                    onImportFile = onImportFile,
                    onImportClipboard = onImportClipboard
                )

                Text(
                    text = LanguageManager.getString("main_screen.phone_list", phoneList.size),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                // 排序和清空按钮区域
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // 拨打次数排序按钮
                    val callCountSortText = when (sortByCallCount) {
                        0 -> LanguageManager.getString("main_screen.sort.call_count")
                        1 -> LanguageManager.getString("main_screen.sort.call_count_asc")
                        2 -> LanguageManager.getString("main_screen.sort.call_count_desc")
                        else -> LanguageManager.getString("main_screen.sort.call_count")
                    }
                    FilterChip(
                        selected = sortByCallCount != 0,
                        onClick = { viewModel.toggleSortByCallCount() },
                        label = { Text(callCountSortText, style = MaterialTheme.typography.bodySmall) },
                        modifier = Modifier.weight(1f)
                    )

                    // 余额排序按钮
                    if (phoneList.any { !it.balance.isNullOrEmpty() }) {
                        val sortText = when (sortByBalance) {
                            0 -> LanguageManager.getString("main_screen.sort.balance")
                            1 -> LanguageManager.getString("main_screen.sort.balance_asc")
                            2 -> LanguageManager.getString("main_screen.sort.balance_desc")
                            else -> LanguageManager.getString("main_screen.sort.balance")
                        }
                        FilterChip(
                            selected = sortByBalance != 0,
                            onClick = { viewModel.toggleSortByBalance() },
                            label = { Text(sortText, style = MaterialTheme.typography.bodySmall) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    
                    // 清空列表按钮
                    if (phoneList.isNotEmpty()) {
                        var showClearDialog by remember { mutableStateOf(false) }
                        
                        FilterChip(
                            selected = false,
                            onClick = { showClearDialog = true },
                            label = { Text(LanguageManager.getString("main_screen.clear_list"), style = MaterialTheme.typography.bodySmall) },
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                            ),
                            modifier = Modifier.weight(1f)
                        )
                        
                        if (showClearDialog) {
                            AlertDialog(
                                onDismissRequest = { showClearDialog = false },
                                title = { Text(LanguageManager.getString("main_screen.confirm_clear.title")) },
                                text = { Text(LanguageManager.getString("main_screen.confirm_clear.message")) },
                                confirmButton = {
                                    Button(
                                        onClick = {
                                            viewModel.clearPhoneList()
                                            showClearDialog = false
                                        },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.error
                                        )
                                    ) {
                                        Text(LanguageManager.getString("main_screen.confirm_clear.confirm"))
                                    }
                                },
                                dismissButton = {
                                    TextButton(onClick = { showClearDialog = false }) {
                                        Text(LanguageManager.getString("common.cancel"))
                                    }
                                }
                            )
                        }
                    }
                }

                PhoneList(
                    phoneList = phoneList,
                    onPhoneClick = { entry ->
                        viewModel.markAsCalledManually(entry.phoneNumber)
                        val intent = Intent(Intent.ACTION_CALL).apply {
                            data = Uri.parse("tel:${entry.phoneNumber}")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                    }
                )

                if (statistics.isNotEmpty()) {
                    StatisticsCard(statistics = statistics)
                }
            }

            if (phoneList.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Box(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                        ExportButton(onExport = onExport)
                    }
                }
            }
        }
    }

    if (showAboutDialog) {
        AboutSoftwareDialog(
            onDismiss = { showAboutDialog = false },
            context = context
        )
    }
    
    // 更新对话框
    if (showUpdateDialog && updateInfo != null) {
        var isDownloading by remember { mutableStateOf(false) }
        var downloadProgress by remember { mutableStateOf(0f) }
        val snackbarHostState = remember { SnackbarHostState() }
        
        AlertDialog(
            onDismissRequest = { if (!isDownloading) showUpdateDialog = false },
            title = { Text(LanguageManager.getString("update_dialog.title")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(LanguageManager.getString("update_dialog.latest_version", updateInfo!!.first), fontWeight = FontWeight.Bold)
                    Text(LanguageManager.getString("update_dialog.update_content"), fontWeight = FontWeight.Bold)
                    Text(updateInfo!!.second)
                    
                    if (isDownloading) {
                        Column(modifier = Modifier.padding(top = 8.dp)) {
                            LinearProgressIndicator(
                                progress = { downloadProgress },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                LanguageManager.getString("update_dialog.downloading_progress", (downloadProgress * 100).toInt()),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        isDownloading = true
                        downloadAndInstallApk(
                            context = context,
                            apkUrl = updateInfo!!.third,
                            snackbarHostState = snackbarHostState,
                            onProgress = { progress ->
                                downloadProgress = progress
                            },
                            onComplete = {
                                isDownloading = false
                                showUpdateDialog = false
                            },
                            onError = {
                                isDownloading = false
                            }
                        )
                    },
                    enabled = !isDownloading
                ) {
                    if (isDownloading) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(end = 8.dp),
                            strokeWidth = 2.dp
                        )
                    }
                    Text(if (isDownloading) LanguageManager.getString("update_dialog.downloading") else LanguageManager.getString("update_dialog.install_now"))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { 
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(updateInfo!!.third))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                        showUpdateDialog = false 
                    },
                    enabled = !isDownloading
                ) {
                    Text(LanguageManager.getString("update_dialog.browser_download"))
                }
            }
        )
    }
}

@Composable
fun AboutSoftwareDialog(
    onDismiss: () -> Unit,
    context: Context
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(LanguageManager.getString("about_dialog.title")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(LanguageManager.getString("about_dialog.app_name"), fontWeight = FontWeight.Bold)
                Text(LanguageManager.getString("about_dialog.developer"))
                
                Text(LanguageManager.getString("about_dialog.links_title"), fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
                Text(
                    text = LanguageManager.getString("about_dialog.github_link"),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/ZHCOOL520/AUTOcall"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                    }
                )
                Text(
                    text = LanguageManager.getString("about_dialog.bilibili_link"),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://space.bilibili.com/1414910921?spm_id_from=333.1007.0.0"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                    }
                )
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) { Text(LanguageManager.getString("common.confirm")) }
        }
    )
}

@SuppressLint("UseKtx")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: AutoCallViewModel,
    onBack: () -> Unit,
    onImportAudio: () -> Unit,
    onDeclineDisclaimer: () -> Unit = {}
) {
    val context = LocalContext.current
    val currentStatus by viewModel.currentStatus.collectAsState()
    val isRecordingEnabled by viewModel.isRecordingEnabled.collectAsState()
    val isAudioPlaybackEnabled by viewModel.isAudioPlaybackEnabled.collectAsState()
    val selectedAudioIndex by viewModel.selectedAudioIndex.collectAsState()
    val simCardMode by viewModel.simCardMode.collectAsState()
    val autoCheckUpdateEnabled by viewModel.autoCheckUpdateEnabled.collectAsState()
    val checkUpdateInterval by viewModel.checkUpdateInterval.collectAsState()
    val callInterval by viewModel.callInterval.collectAsState()
    val selectedLanguage by viewModel.selectedLanguage.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var isCheckingUpdate by remember { mutableStateOf(false) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    var updateInfo by remember { mutableStateOf<Triple<String, String, String>?>(null) } // (version, content, url)
    var showFeatureIntro by remember { mutableStateOf(false) }
    var showPrivacyPolicy by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }

    // 启动时检查更新
    LaunchedEffect(Unit) {
        if (viewModel.autoCheckUpdateEnabled.value) {
            checkUpdateIfNeeded(context, viewModel) { version, content, url ->
                updateInfo = Triple(version, content, url)
                showUpdateDialog = true
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(LanguageManager.getString("settings_screen.title")) },
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
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 音频播放开关
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (isAudioPlaybackEnabled) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(LanguageManager.getString("settings_screen.audio_playback.title"), fontWeight = FontWeight.Bold)
                        Text(
                            if (isAudioPlaybackEnabled) LanguageManager.getString("settings_screen.audio_playback.enabled") else LanguageManager.getString("settings_screen.audio_playback.disabled"),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Switch(
                        checked = isAudioPlaybackEnabled,
                        onCheckedChange = { viewModel.toggleAudioPlayback() }
                    )
                }
            }

            // 音频选择器（仅在开启时显示）
            if (isAudioPlaybackEnabled) {
                AudioSelector(
                    viewModel = viewModel,
                    selectedIndex = selectedAudioIndex,
                    onImportAudio = onImportAudio
                )
            }

            // 通话录音开关
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (isRecordingEnabled) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(LanguageManager.getString("settings_screen.call_recording.title"), fontWeight = FontWeight.Bold)
                        Text(
                            if (isRecordingEnabled) LanguageManager.getString("settings_screen.call_recording.enabled") else LanguageManager.getString("settings_screen.call_recording.disabled"),
                            style = MaterialTheme.typography.bodySmall
                        )
                        if (isRecordingEnabled) {
                            Text(
                                LanguageManager.getString("settings_screen.call_recording.status", currentStatus),
                                style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.primary)
                            )
                        }
                    }
                    Switch(
                        checked = isRecordingEnabled,
                        onCheckedChange = { viewModel.toggleRecording() }
                    )
                }
            }

            // SIM卡选择
            SimCardSelector(
                simCardMode = simCardMode,
                onModeSelected = { mode -> viewModel.setSimCardMode(mode) }
            )

            // 拨打间隔设置
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(LanguageManager.getString("settings_screen.call_interval.title"), fontWeight = FontWeight.Bold)
                    Text(
                        LanguageManager.getString("settings_screen.call_interval.current", viewModel.getCallIntervalText()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        LanguageManager.getString("settings_screen.call_interval.description"),
                        style = MaterialTheme.typography.bodySmall
                    )
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(1, 2, 3, 5, 8).forEach { seconds ->
                            FilterChip(
                                selected = (callInterval / 1000L).toInt() == seconds,
                                onClick = { viewModel.setCallInterval(seconds) },
                                label = { Text(LanguageManager.getString("settings_screen.call_interval.seconds", seconds)) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // 自动检查更新设置
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(LanguageManager.getString("settings_screen.auto_update.title"), fontWeight = FontWeight.Bold)
                            Text(
                                if (autoCheckUpdateEnabled) LanguageManager.getString("settings_screen.auto_update.enabled") else LanguageManager.getString("settings_screen.auto_update.disabled"),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Switch(
                            checked = autoCheckUpdateEnabled,
                            onCheckedChange = { viewModel.toggleAutoCheckUpdate() }
                        )
                    }
                    
                    if (autoCheckUpdateEnabled) {
                        Text(LanguageManager.getString("settings_screen.auto_update.frequency_title"), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf(
                                0 to LanguageManager.getString("settings_screen.auto_update.daily"),
                                1 to LanguageManager.getString("settings_screen.auto_update.weekly"),
                                2 to LanguageManager.getString("settings_screen.auto_update.monthly")
                            ).forEach { (value, label) ->
                                FilterChip(
                                    selected = checkUpdateInterval == value,
                                    onClick = { viewModel.setCheckUpdateInterval(value) },
                                    label = { Text(label) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            }

            // 版本信息
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(LanguageManager.getString("settings_screen.version_info.title"), fontWeight = FontWeight.Bold)
                            Text(LanguageManager.getString("settings_screen.version_info.current_version", getAppVersion(context)), style = MaterialTheme.typography.bodySmall)
                        }
                        Button(
                            onClick = {
                                CoroutineScope(Dispatchers.Main).launch {
                                    isCheckingUpdate = true
                                    try {
                                        withContext(Dispatchers.IO) {
                                            val client = OkHttpClient()
                                            val request = Request.Builder()
                                                .url("https://api.github.com/repos/ZHCOOL520/AUTOcall/releases/latest")
                                                .header("Accept", "application/vnd.github.v3+json")
                                                .build()
                                            
                                            val response = client.newCall(request).execute()
                                            if (!response.isSuccessful) {
                                                throw Exception("HTTP ${response.code}")
                                            }
                                            
                                            val responseBody = response.body?.string()
                                            if (responseBody != null) {
                                                val json = JSONObject(responseBody)
                                                val version = json.getString("tag_name")
                                                val content = json.optString("body", LanguageManager.getString("status.update_content_empty"))
                                                val htmlUrl = json.getString("html_url")
                                                
                                                withContext(Dispatchers.Main) {
                                                    isCheckingUpdate = false
                                                    val currentVersion = getAppVersion(context)
                                                    val compareResult = compareVersions(currentVersion, version)
                                                    if (compareResult == 1) {
                                                        updateInfo = Triple(version, content, htmlUrl)
                                                        showUpdateDialog = true
                                                    } else {
                                                        snackbarHostState.showSnackbar(
                                                            LanguageManager.getString("settings_screen.version_info.latest"),
                                                            duration = SnackbarDuration.Short
                                                        )
                                                    }
                                                }
                                            } else {
                                                withContext(Dispatchers.Main) {
                                                    isCheckingUpdate = false
                                                    snackbarHostState.showSnackbar(
                                                        LanguageManager.getString("settings_screen.version_info.check_failed", LanguageManager.getString("status.empty_response")),
                                                        duration = SnackbarDuration.Long
                                                    )
                                                }
                                            }
                                        }
                                    } catch (e: Exception) {
                                        withContext(Dispatchers.Main) {
                                            isCheckingUpdate = false
                                            snackbarHostState.showSnackbar(
                                                LanguageManager.getString("settings_screen.version_info.check_failed", e.message ?: LanguageManager.getString("status.unknown_error")),
                                                duration = SnackbarDuration.Long
                                            )
                                        }
                                    }
                                }
                            },
                            enabled = !isCheckingUpdate
                        ) {
                            if (isCheckingUpdate) {
                                CircularProgressIndicator(
                                    modifier = Modifier.padding(4.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Text(LanguageManager.getString("settings_screen.version_info.check_update"))
                            }
                        }
                    }
                }
            }

            // 语言切换设置
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showLanguageDialog = true },
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(LanguageManager.getString("settings_screen.language.title"), fontWeight = FontWeight.Bold)
                        Text(
                            LanguageManager.getString("settings_screen.language.current", viewModel.getLanguageText()),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = LanguageManager.getString("settings_screen.language.select_description"),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 网络提示
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f)
                )
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(LanguageManager.getString("settings_screen.network_notice.title"), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onTertiaryContainer)
                    Text(
                        LanguageManager.getString("settings_screen.network_notice.content"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                    
                    Button(
                        onClick = {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_RELEASES_URL))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(intent)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(LanguageManager.getString("settings_screen.network_notice.download_button"))
                    }
                }
            }

            // 功能说明
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(LanguageManager.getString("settings_screen.feature_intro.title"), fontWeight = FontWeight.Bold)
                    LanguageManager.getString("settings_screen.feature_intro.features").split("\n").forEach { feature ->
                        if (feature.isNotBlank()) {
                            Text(feature, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    
                    Button(
                        onClick = { showFeatureIntro = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(LanguageManager.getString("settings_screen.feature_intro.view_full_button"))
                    }
                }
            }
            
            // 隐私政策与免责声明
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f)
                )
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(LanguageManager.getString("settings_screen.privacy_policy.title"), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                    LanguageManager.getString("settings_screen.privacy_policy.items").split("\n").forEach { item ->
                        if (item.isNotBlank()) {
                            Text(item, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    
                    Button(
                        onClick = { showPrivacyPolicy = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text(LanguageManager.getString("settings_screen.privacy_policy.view_button"))
                    }
                }
            }
        }
    }

    // 更新对话框
    if (showUpdateDialog && updateInfo != null) {
        var isDownloading by remember { mutableStateOf(false) }
        var downloadProgress by remember { mutableStateOf(0f) }
        
        AlertDialog(
            onDismissRequest = { if (!isDownloading) showUpdateDialog = false },
            title = { Text(LanguageManager.getString("update_dialog.title")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(LanguageManager.getString("update_dialog.latest_version", updateInfo!!.first), fontWeight = FontWeight.Bold)
                    Text(LanguageManager.getString("update_dialog.update_content"), fontWeight = FontWeight.Bold)
                    Text(updateInfo!!.second)
                    
                    if (isDownloading) {
                        Column(modifier = Modifier.padding(top = 8.dp)) {
                            LinearProgressIndicator(
                                progress = { downloadProgress },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                LanguageManager.getString("update_dialog.downloading_progress", (downloadProgress * 100).toInt()),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        isDownloading = true
                        downloadAndInstallApk(
                            context = context,
                            apkUrl = updateInfo!!.third,
                            snackbarHostState = snackbarHostState,
                            onProgress = { progress ->
                                downloadProgress = progress
                            },
                            onComplete = {
                                isDownloading = false
                                showUpdateDialog = false
                            },
                            onError = {
                                isDownloading = false
                            }
                        )
                    },
                    enabled = !isDownloading
                ) {
                    if (isDownloading) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(end = 8.dp),
                            strokeWidth = 2.dp
                        )
                    }
                    Text(if (isDownloading) LanguageManager.getString("update_dialog.downloading") else LanguageManager.getString("update_dialog.install_now"))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { 
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(updateInfo!!.third))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                        showUpdateDialog = false 
                    },
                    enabled = !isDownloading
                ) {
                    Text(LanguageManager.getString("update_dialog.browser_download"))
                }
            }
        )
    }
    
    // 功能介绍对话框
    if (showFeatureIntro) {
        AlertDialog(
            onDismissRequest = { showFeatureIntro = false },
            title = { Text(LanguageManager.getString("feature_intro_dialog.title")) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                        )
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(LanguageManager.getString("feature_intro_dialog.auto_call.title"), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                            LanguageManager.getString("feature_intro_dialog.auto_call.items").split("\n").forEach { item ->
                                if (item.isNotBlank()) {
                                    Text(item, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                    
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f)
                        )
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(LanguageManager.getString("feature_intro_dialog.audio_playback.title"), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                            LanguageManager.getString("feature_intro_dialog.audio_playback.items").split("\n").forEach { item ->
                                if (item.isNotBlank()) {
                                    Text(item, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                    
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f)
                        )
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(LanguageManager.getString("feature_intro_dialog.call_recording.title"), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                            LanguageManager.getString("feature_intro_dialog.call_recording.items").split("\n").forEach { item ->
                                if (item.isNotBlank()) {
                                    Text(item, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                    
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(LanguageManager.getString("feature_intro_dialog.sim_card.title"), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                            LanguageManager.getString("feature_intro_dialog.sim_card.items").split("\n").forEach { item ->
                                if (item.isNotBlank()) {
                                    Text(item, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                    
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)
                        )
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(LanguageManager.getString("feature_intro_dialog.statistics.title"), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                            LanguageManager.getString("feature_intro_dialog.statistics.items").split("\n").forEach { item ->
                                if (item.isNotBlank()) {
                                    Text(item, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                    
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.2f)
                        )
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(LanguageManager.getString("feature_intro_dialog.auto_update.title"), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                            LanguageManager.getString("feature_intro_dialog.auto_update.items").split("\n").forEach { item ->
                                if (item.isNotBlank()) {
                                    Text(item, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = { showFeatureIntro = false }) {
                    Text(LanguageManager.getString("common.close"))
                }
            }
        )
    }
    
    // 隐私政策对话框（查看模式，拒绝时退出应用）
    if (showPrivacyPolicy) {
        DisclaimerDialog(
            onAccept = { showPrivacyPolicy = false },
            onDecline = {
                showPrivacyPolicy = false
                onDeclineDisclaimer()
            },
            canDecline = true // 允许拒绝，拒绝后退出应用
        )
    }
    
    // 语言选择对话框
    if (showLanguageDialog) {
        AlertDialog(
            onDismissRequest = { showLanguageDialog = false },
            title = { Text(LanguageManager.getString("settings_screen.language.dialog_title")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    listOf(
                        "zh" to LanguageManager.getLanguageDisplayName("zh"),
                        "en" to LanguageManager.getLanguageDisplayName("en")
                    ).forEach { (code, name) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.setLanguage(code)
                                    showLanguageDialog = false
                                }
                                .padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = selectedLanguage == code,
                                    onClick = {
                                        viewModel.setLanguage(code)
                                        showLanguageDialog = false
                                    }
                                )
                                Text(
                                    name,
                                    fontWeight = if (selectedLanguage == code) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                            
                            if (code == "en") {
                                Text(
                                    LanguageManager.getString("settings_screen.language.ai_translated"),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                )
                            }
                        }
                    }
                    
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    
                    Text(
                        LanguageManager.getString("settings_screen.language.coming_soon"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showLanguageDialog = false }) {
                    Text(LanguageManager.getString("common.cancel"))
                }
            }
        )
    }
}

@Composable
fun DisclaimerDialog(
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    canDecline: Boolean = true // 是否允许拒绝（首次启动时为true，查看时为false）
) {
    AlertDialog(
        onDismissRequest = { },
        title = { 
            Text(
                LanguageManager.getString("disclaimer_dialog.title"),
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.error
            ) 
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 安全声明
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(LanguageManager.getString("disclaimer_dialog.security_notice.title"), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
                        LanguageManager.getString("disclaimer_dialog.security_notice.items").split("\n").forEach { item ->
                            if (item.isNotBlank()) {
                                Text(item, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }

                // 核心功能限制提示
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(LanguageManager.getString("disclaimer_dialog.function_limitation.title"), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                        Text(
                            LanguageManager.getString("disclaimer_dialog.function_limitation.warning"),
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        LanguageManager.getString("disclaimer_dialog.function_limitation.items").split("\n").forEach { item ->
                            if (item.isNotBlank()) {
                                Text(item, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        Text(LanguageManager.getString("disclaimer_dialog.root_suggestion"), style = MaterialTheme.typography.bodySmall)
                    }
                }

                // 权限说明
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(LanguageManager.getString("disclaimer_dialog.permission_notice.title"), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        LanguageManager.getString("disclaimer_dialog.permission_notice.items").split("\n").forEach { item ->
                            if (item.isNotBlank()) {
                                Text(item, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }

                // 隐私政策
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f)
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(LanguageManager.getString("disclaimer_dialog.privacy_policy.title"), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onTertiaryContainer)
                        LanguageManager.getString("disclaimer_dialog.privacy_policy.items").split("\n").forEach { item ->
                            if (item.isNotBlank()) {
                                Text(item, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }

                Text(
                    LanguageManager.getString("disclaimer_dialog.accept_notice"),
                    style = MaterialTheme.typography.bodySmall,
                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onAccept,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Text(LanguageManager.getString("disclaimer_dialog.accept_button"))
            }
        },
        dismissButton = {
            if (canDecline) {
                TextButton(onClick = onDecline) {
                    Text(LanguageManager.getString("disclaimer_dialog.decline_button"), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    )
}

// ---------- 各组件（均无变动，仅 AudioSelector 有优化） ----------

@Composable
fun StatusCard(status: String, progress: Int, total: Int) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(LanguageManager.getString("status_card.title"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(status, style = MaterialTheme.typography.bodyLarge)
            if (total > 0) {
                LinearProgressIndicator(
                    progress = { if (total > 0) progress.toFloat() / total else 0f },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(LanguageManager.getString("status_card.progress", progress, total), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
fun AudioSelector(
    viewModel: AutoCallViewModel,
    selectedIndex: Int,
    onImportAudio: () -> Unit
) {
    val allAudios = viewModel.getAllAudios()

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(LanguageManager.getString("audio_selector.title"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Button(onClick = onImportAudio) {
                    Text(LanguageManager.getString("audio_selector.import_button"))
                }
            }

            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(horizontal = 4.dp)
            ) {
                itemsIndexed(allAudios) { index, _ ->
                    val displayName = when (index) {
                        0 -> LanguageManager.getString("audio_selector.audio_0")
                        1 -> LanguageManager.getString("audio_selector.audio_1")
                        2 -> LanguageManager.getString("audio_selector.audio_2")
                        else -> LanguageManager.getString("audio_selector.custom_audio", index - 2)
                    }
                    FilterChip(
                        selected = selectedIndex == index,
                        onClick = { viewModel.selectAudio(index) },
                        label = { Text(displayName) }
                    )
                }
            }

            Text(
                text = LanguageManager.getString("audio_selector.current_selected", allAudios.getOrElse(selectedIndex) { allAudios.firstOrNull() ?: "" }),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
fun StatisticsCard(statistics: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(LanguageManager.getString("statistics_card.title"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(statistics, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun SimCardSelector(simCardMode: Int, onModeSelected: (Int) -> Unit) {
    var showDialog by remember { mutableStateOf(false) }
    val modeText = when (simCardMode) {
        0 -> LanguageManager.getString("sim_card.mode_default")
        1 -> LanguageManager.getString("sim_card.mode_sim1")
        2 -> LanguageManager.getString("sim_card.mode_sim2")
        3 -> LanguageManager.getString("sim_card.mode_alternate")
        else -> LanguageManager.getString("sim_card.mode_default")
    }
    
    Card(
        modifier = Modifier.fillMaxWidth().clickable { showDialog = true },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(LanguageManager.getString("sim_card_selector.title"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(
                    LanguageManager.getString("sim_card_selector.current_mode", modeText),
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    LanguageManager.getString("sim_card_selector.hint"),
                    style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.primary)
                )
            }
            Icon(
                Icons.Default.ArrowDropDown,
                contentDescription = LanguageManager.getString("common.select"),
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(LanguageManager.getString("sim_card_selector.dialog_title")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        0 to LanguageManager.getString("sim_card.option_0"),
                        1 to LanguageManager.getString("sim_card.option_1"),
                        2 to LanguageManager.getString("sim_card.option_2"),
                        3 to LanguageManager.getString("sim_card.option_3")
                    ).forEach { (mode, description) ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onModeSelected(mode)
                                    showDialog = false
                                },
                            colors = CardDefaults.cardColors(
                                containerColor = if (simCardMode == mode) 
                                    MaterialTheme.colorScheme.primaryContainer
                                else 
                                    MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        description.substringBefore(" - "),
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        description.substringAfter(" - "),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                if (simCardMode == mode) {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = LanguageManager.getString("sim_card.selected"),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text(LanguageManager.getString("common.cancel"))
                }
            }
        )
    }
}

@Composable
fun ExportButton(onExport: () -> Unit) {
    Button(onClick = onExport, modifier = Modifier.fillMaxWidth()) {
        Text(LanguageManager.getString("export_button.text"))
    }
}

@Composable
fun ControlButtons(
    isRunning: Boolean,
    isPaused: Boolean,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onImportFile: () -> Unit,
    onImportClipboard: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onImportFile, enabled = !isRunning, modifier = Modifier.weight(1f)) {
                Text(LanguageManager.getString("control_buttons.import_phone"))
            }
            Button(onClick = onImportClipboard, enabled = !isRunning, modifier = Modifier.weight(1f)) {
                Text(LanguageManager.getString("control_buttons.import_clipboard"))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onStart, enabled = !isRunning, modifier = Modifier.weight(1f)) {
                Text(LanguageManager.getString("control_buttons.start_call"))
            }
            if (isRunning && !isPaused) {
                Button(
                    onClick = onPause, 
                    enabled = isRunning, 
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                ) {
                    Text(LanguageManager.getString("control_buttons.pause"))
                }
            } else if (isRunning && isPaused) {
                Button(
                    onClick = onResume, 
                    enabled = isRunning, 
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text(LanguageManager.getString("control_buttons.resume"))
                }
            }
            Button(
                onClick = onStop, enabled = isRunning, modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Text(LanguageManager.getString("control_buttons.stop"))
            }
        }
    }
}

@Composable
fun PhoneList(phoneList: List<PhoneEntry>, onPhoneClick: (PhoneEntry) -> Unit = {}) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        if (phoneList.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) {
                Text(LanguageManager.getString("phone_list.empty"))
            }
        } else {
            LazyColumn(
                modifier = Modifier.heightIn(max = 400.dp),
                contentPadding = PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(phoneList) { entry ->
                    PhoneItem(entry, onClick = { onPhoneClick(entry) })
                }
            }
        }
    }
}

@Composable
fun PhoneItem(entry: PhoneEntry, onClick: () -> Unit = {}) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (entry.isCalled) 
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            else 
                MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    entry.contactName.ifEmpty { LanguageManager.getString("phone_item.unknown_contact") },
                    style = MaterialTheme.typography.titleSmall, 
                    fontWeight = FontWeight.Bold,
                    color = if (entry.isCalled) 
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    else 
                        MaterialTheme.colorScheme.onSurface
                )
                Text(
                    entry.phoneNumber,
                    style = MaterialTheme.typography.bodyMedium, 
                    color = if (entry.isCalled) 
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                    else 
                        MaterialTheme.colorScheme.primary
                )
                
                // 显示拨打状态
                Text(
                    if (entry.isCalled) LanguageManager.getString("phone_item.called", entry.callCount) else LanguageManager.getString("phone_item.not_called"),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (entry.isCalled) 
                        MaterialTheme.colorScheme.primary
                    else 
                        MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
                
                // 显示户号信息
                if (!entry.accountNumber.isNullOrEmpty()) {
                    Text(
                        LanguageManager.getString("phone_item.account_number", entry.accountNumber),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (entry.isCalled) 
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        else 
                            MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                // 显示余额信息
                if (!entry.balance.isNullOrEmpty()) {
                    Text(
                        LanguageManager.getString("phone_item.balance", entry.balance),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (entry.isCalled) 
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                        else 
                            MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

// 获取应用版本号
fun getAppVersion(context: Context): String {
    return try {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        packageInfo.versionName ?: "1.0"
    } catch (e: Exception) {
        "1.0"
    }
}

// 比较版本号，返回: 1(远程更新), 0(相同), -1(本地更新)
fun compareVersions(localVersion: String, remoteVersion: String): Int {
    fun parseVersion(version: String): List<Int> {
        return version.trimStart('v').split(".").map { it.toIntOrNull() ?: 0 }
    }
    
    val local = parseVersion(localVersion)
    val remote = parseVersion(remoteVersion)
    
    val maxSize = maxOf(local.size, remote.size)
    for (i in 0 until maxSize) {
        val localPart = local.getOrElse(i) { 0 }
        val remotePart = remote.getOrElse(i) { 0 }
        if (remotePart > localPart) return 1
        if (remotePart < localPart) return -1
    }
    return 0
}

// 检查是否需要更新（根据设置的频率）
fun checkUpdateIfNeeded(
    context: Context,
    viewModel: AutoCallViewModel,
    onNewVersionFound: (version: String, content: String, url: String) -> Unit
) {
    CoroutineScope(Dispatchers.Main).launch {
        val prefs = context.getSharedPreferences(UPDATE_CHECK_PREFS, Context.MODE_PRIVATE)
        val lastCheckTime = prefs.getLong(LAST_CHECK_TIME_KEY, 0)
        val currentTime = System.currentTimeMillis()
        
        // 根据设置的频率计算需要间隔的时间
        val interval = when (viewModel.checkUpdateInterval.value) {
            0 -> 24 * 60 * 60 * 1000L // 每日
            1 -> 7 * 24 * 60 * 60 * 1000L // 每周
            2 -> 30 * 24 * 60 * 60 * 1000L // 每月
            else -> 30 * 24 * 60 * 60 * 1000L
        }
        
        // 如果距离上次检查时间超过设定间隔，则检查更新
        if (currentTime - lastCheckTime >= interval) {
            try {
                withContext(Dispatchers.IO) {
                    val client = OkHttpClient()
                    val request = Request.Builder()
                        .url(GITHUB_API_URL)
                        .header("Accept", "application/vnd.github.v3+json")
                        .build()
                    
                    val response = client.newCall(request).execute()
                    if (!response.isSuccessful) {
                        throw Exception("HTTP ${response.code}")
                    }
                    
                    val responseBody = response.body?.string()
                    if (responseBody != null) {
                        val json = JSONObject(responseBody)
                        val version = json.getString("tag_name")
                        val content = json.optString("body", LanguageManager.getString("status.update_content_empty"))
                        val htmlUrl = json.getString("html_url")
                        
                        // 获取APK下载链接
                        val assets = json.getJSONArray("assets")
                        var apkUrl = ""
                        for (i in 0 until assets.length()) {
                            val asset = assets.getJSONObject(i)
                            if (asset.getString("name").endsWith(".apk")) {
                                apkUrl = asset.getString("browser_download_url")
                                break
                            }
                        }
                        
                        withContext(Dispatchers.Main) {
                            val currentVersion = getAppVersion(context)
                            val compareResult = compareVersions(currentVersion, version)
                            if (compareResult == 1) {
                                onNewVersionFound(version, content, apkUrl.ifEmpty { htmlUrl })
                            }
                            // 更新最后检查时间
                            prefs.edit().putLong(LAST_CHECK_TIME_KEY, currentTime).apply()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("UpdateCheck", LanguageManager.getString("log.auto_update_check_failed", e.message ?: ""))
            }
        }
    }
}

// 下载并安装APK
fun downloadAndInstallApk(
    context: Context,
    apkUrl: String,
    snackbarHostState: androidx.compose.material3.SnackbarHostState,
    onProgress: (Float) -> Unit = {},
    onComplete: () -> Unit = {},
    onError: () -> Unit = {}
) {
    CoroutineScope(Dispatchers.Main).launch {
        try {
            withContext(Dispatchers.IO) {
                val client = OkHttpClient()
                val request = Request.Builder().url(apkUrl).build()
                val response = client.newCall(request).execute()
                
                if (!response.isSuccessful) {
                    throw Exception(LanguageManager.getString("status.download_failed_http", response.code))
                }
                
                val body = response.body ?: throw Exception(LanguageManager.getString("status.empty_response_body"))
                val contentLength = body.contentLength()
                val inputStream = body.byteStream()
                
                // 保存到缓存目录
                val apkFile = File(context.cacheDir, "update.apk")
                apkFile.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var totalBytesRead = 0L
                    
                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalBytesRead += bytesRead
                        
                        // 更新进度
                        if (contentLength > 0) {
                            val progress = totalBytesRead.toFloat() / contentLength.toFloat()
                            withContext(Dispatchers.Main) {
                                onProgress(progress)
                            }
                        }
                    }
                }
                
                withContext(Dispatchers.Main) {
                    // 触发安装
                    val uri = androidx.core.content.FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        apkFile
                    )
                    
                    val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                        data = uri
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
                        putExtra(Intent.EXTRA_RETURN_RESULT, false)
                    }
                    
                    try {
                        context.startActivity(intent)
                        onComplete()
                    } catch (e: Exception) {
                        // 如果INSTALL_PACKAGE失败，尝试ACTION_VIEW
                        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, "application/vnd.android.package-archive")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(viewIntent)
                        onComplete()
                    }
                }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                snackbarHostState.showSnackbar(
                    LanguageManager.getString("update_dialog.download_failed", e.message ?: ""),
                    duration = SnackbarDuration.Long
                )
                onError()
            }
        }
    }
}
