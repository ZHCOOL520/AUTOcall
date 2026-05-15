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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
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
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        if (clipboard.hasPrimaryClip()) {
            val clipData = clipboard.primaryClip
            if (clipData != null && clipData.itemCount > 0) {
                val text = clipData.getItemAt(0).text?.toString()
                if (!text.isNullOrEmpty()) {
                    viewModel.importFromClipboard(this, text)
                } else {
                    viewModel.updateStatus("剪贴板内容为空")
                }
            }
        } else {
            viewModel.updateStatus("剪贴板无内容")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        checkAndRequestPermissions()

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
                                onImportAudio = { importAudioLauncher.launch("audio/*") }
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
                else -> viewModel.updateStatus("不支持的文件格式")
            }
        } catch (e: Exception) {
            e.printStackTrace()
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
                        "自动电话拨打系统",
                        modifier = Modifier.clickable { showAboutDialog = true }
                    ) 
                },
                actions = {
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "设置")
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
                    text = "电话列表 (${phoneList.size}个)",
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
                        0 -> "按拨打次数"
                        1 -> "✓ 次数升序"
                        2 -> "✓ 次数降序"
                        else -> "按拨打次数"
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
                            0 -> "按余额"
                            1 -> "✓ 余额升序"
                            2 -> "✓ 余额降序"
                            else -> "按余额"
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
                            label = { Text("🗑️ 清空", style = MaterialTheme.typography.bodySmall) },
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                            ),
                            modifier = Modifier.weight(1f)
                        )
                        
                        if (showClearDialog) {
                            AlertDialog(
                                onDismissRequest = { showClearDialog = false },
                                title = { Text("确认清空") },
                                text = { Text("确定要清空所有联系人吗？此操作不可恢复。") },
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
                                        Text("确认清空")
                                    }
                                },
                                dismissButton = {
                                    TextButton(onClick = { showClearDialog = false }) {
                                        Text("取消")
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
            title = { Text("发现新版本") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("最新版本: ${updateInfo!!.first}", fontWeight = FontWeight.Bold)
                    Text("\n更新内容:", fontWeight = FontWeight.Bold)
                    Text(updateInfo!!.second)
                    
                    if (isDownloading) {
                        Column(modifier = Modifier.padding(top = 8.dp)) {
                            LinearProgressIndicator(
                                progress = { downloadProgress },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                "下载中... ${(downloadProgress * 100).toInt()}%",
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
                    Text(if (isDownloading) "下载中" else "后台下载安装")
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
                    Text("浏览器下载")
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
        title = { Text("关于软件") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("自动电话拨打系统", fontWeight = FontWeight.Bold)
                Text("开发者: ZHCOOL520")
                
                Text("\n相关链接：", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
                Text(
                    text = "• GitHub: https://github.com/ZHCOOL520/AUTOcall",
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/ZHCOOL520/AUTOcall"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                    }
                )
                Text(
                    text = "• Bilibili: https://space.bilibili.com/1414910921",
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
            Button(onClick = onDismiss) { Text("确定") }
        }
    )
}

@SuppressLint("UseKtx")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: AutoCallViewModel,
    onBack: () -> Unit,
    onImportAudio: () -> Unit
) {
    val context = LocalContext.current
    val currentStatus by viewModel.currentStatus.collectAsState()
    val isRecordingEnabled by viewModel.isRecordingEnabled.collectAsState()
    val isAudioPlaybackEnabled by viewModel.isAudioPlaybackEnabled.collectAsState()
    val selectedAudioIndex by viewModel.selectedAudioIndex.collectAsState()
    val simCardMode by viewModel.simCardMode.collectAsState()
    val autoCheckUpdateEnabled by viewModel.autoCheckUpdateEnabled.collectAsState()
    val checkUpdateInterval by viewModel.checkUpdateInterval.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var isCheckingUpdate by remember { mutableStateOf(false) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    var updateInfo by remember { mutableStateOf<Triple<String, String, String>?>(null) } // (version, content, url)
    var showFeatureIntro by remember { mutableStateOf(false) }
    var showPrivacyPolicy by remember { mutableStateOf(false) }

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
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
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
                        Text("音频播放", fontWeight = FontWeight.Bold)
                        Text(
                            if (isAudioPlaybackEnabled) "✅ 开启：通话时自动播放音频" else "❌ 关闭：不播放音频",
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
                        Text("通话录音", fontWeight = FontWeight.Bold)
                        Text(
                            if (isRecordingEnabled) "✅ 开启：通话时自动录音" else "❌ 关闭：不录音",
                            style = MaterialTheme.typography.bodySmall
                        )
                        if (currentStatus.contains("录音", ignoreCase = true)) {
                            Text(
                                "状态: $currentStatus",
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
                            Text("自动检查更新", fontWeight = FontWeight.Bold)
                            Text(
                                if (autoCheckUpdateEnabled) "✅ 开启" else "❌ 关闭",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Switch(
                            checked = autoCheckUpdateEnabled,
                            onCheckedChange = { viewModel.toggleAutoCheckUpdate() }
                        )
                    }
                    
                    if (autoCheckUpdateEnabled) {
                        Text("检查频率:", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf(
                                0 to "每日",
                                1 to "每周",
                                2 to "每月"
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
                            Text("版本信息", fontWeight = FontWeight.Bold)
                            Text("当前版本: ${getAppVersion(context)}", style = MaterialTheme.typography.bodySmall)
                        }
                        Button(
                            onClick = {
                                GlobalScope.launch(Dispatchers.Main) {
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
                                                val content = json.optString("body", "暂无更新说明")
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
                                                            "已是最新版本",
                                                            duration = SnackbarDuration.Short
                                                        )
                                                    }
                                                }
                                            } else {
                                                withContext(Dispatchers.Main) {
                                                    isCheckingUpdate = false
                                                    snackbarHostState.showSnackbar(
                                                        "检查更新失败: 响应为空",
                                                        duration = SnackbarDuration.Long
                                                    )
                                                }
                                            }
                                        }
                                    } catch (e: Exception) {
                                        withContext(Dispatchers.Main) {
                                            isCheckingUpdate = false
                                            snackbarHostState.showSnackbar(
                                                "检查更新失败: ${e.message}",
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
                                Text("检查更新")
                            }
                        }
                    }
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
                    Text("🌐 网络说明", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onTertiaryContainer)
                    Text(
                        "由于国内网络原因，GitHub可能无法直接访问。\n" +
                        "如果自动更新失败，请使用网络代理工具（魔法）后再试。",
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
                        Text("前往下载页面")
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
                    Text("📖 功能介绍", fontWeight = FontWeight.Bold)
                    Text("• 支持Excel/CSV文件导入联系人", style = MaterialTheme.typography.bodySmall)
                    Text("• 自动拨打电话并播放语音", style = MaterialTheme.typography.bodySmall)
                    Text("• 支持通话录音（需ROOT）", style = MaterialTheme.typography.bodySmall)
                    Text("• 导出通话记录统计", style = MaterialTheme.typography.bodySmall)
                    Text("• 支持剪贴板导入电话号码", style = MaterialTheme.typography.bodySmall)
                    Text("• 支持多SIM卡选择与双卡交替拨打", style = MaterialTheme.typography.bodySmall)
                    Text("• 自动检查更新与后台下载安装", style = MaterialTheme.typography.bodySmall)
                    
                    Button(
                        onClick = { showFeatureIntro = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("查看完整功能介绍")
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
                    Text("⚠️ 隐私政策与免责声明", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                    Text("• 本软件仅供学习研究使用", style = MaterialTheme.typography.bodySmall)
                    Text("• 严禁用于任何非法用途", style = MaterialTheme.typography.bodySmall)
                    Text("• 使用者需自行承担法律责任", style = MaterialTheme.typography.bodySmall)
                    
                    Button(
                        onClick = { showPrivacyPolicy = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("查看完整协议")
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
            title = { Text("发现新版本") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("最新版本: ${updateInfo!!.first}", fontWeight = FontWeight.Bold)
                    Text("\n更新内容:", fontWeight = FontWeight.Bold)
                    Text(updateInfo!!.second)
                    
                    if (isDownloading) {
                        Column(modifier = Modifier.padding(top = 8.dp)) {
                            LinearProgressIndicator(
                                progress = { downloadProgress },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                "下载中... ${(downloadProgress * 100).toInt()}%",
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
                    Text(if (isDownloading) "下载中" else "后台下载安装")
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
                    Text("浏览器下载")
                }
            }
        )
    }
    
    // 功能介绍对话框
    if (showFeatureIntro) {
        AlertDialog(
            onDismissRequest = { showFeatureIntro = false },
            title = { Text("📖 功能介绍") },
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
                            Text("📞 自动拨打", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                            Text("• 支持从Excel/CSV文件批量导入联系人", style = MaterialTheme.typography.bodySmall)
                            Text("• 自动识别户号和余额信息", style = MaterialTheme.typography.bodySmall)
                            Text("• 按顺序自动拨打电话", style = MaterialTheme.typography.bodySmall)
                            Text("• 支持暂停、继续和停止操作", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f)
                        )
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("🔊 音频播放", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                            Text("• 通话时自动播放预设语音文件", style = MaterialTheme.typography.bodySmall)
                            Text("• 支持自定义导入音频文件", style = MaterialTheme.typography.bodySmall)
                            Text("• 可选择不同场景的语音（余额不足、停电等）", style = MaterialTheme.typography.bodySmall)
                            Text("• 注意：非ROOT设备可能无法让对方听到", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f)
                        )
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("🎙️ 通话录音", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                            Text("• 自动录制通话内容", style = MaterialTheme.typography.bodySmall)
                            Text("• 保存到本地存储", style = MaterialTheme.typography.bodySmall)
                            Text("• 注意：需要ROOT权限才能正常工作", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("💳 SIM卡管理", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                            Text("• 支持选择SIM卡1或SIM卡2拨打", style = MaterialTheme.typography.bodySmall)
                            Text("• 双卡交替模式，轮流使用两张卡", style = MaterialTheme.typography.bodySmall)
                            Text("• 智能识别可用SIM卡", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)
                        )
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("📊 数据统计", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                            Text("• 实时统计拨打次数和成功率", style = MaterialTheme.typography.bodySmall)
                            Text("• 支持按拨打次数排序", style = MaterialTheme.typography.bodySmall)
                            Text("• 支持按余额排序", style = MaterialTheme.typography.bodySmall)
                            Text("• 导出CSV格式的通话记录", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.2f)
                        )
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("🔄 自动更新", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                            Text("• 启动时自动检查最新版本", style = MaterialTheme.typography.bodySmall)
                            Text("• 后台下载APK文件", style = MaterialTheme.typography.bodySmall)
                            Text("• 显示下载进度", style = MaterialTheme.typography.bodySmall)
                            Text("• 下载完成后自动触发安装", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = { showFeatureIntro = false }) {
                    Text("关闭")
                }
            }
        )
    }
    
    // 隐私政策对话框
    if (showPrivacyPolicy) {
        DisclaimerDialog(
            onAccept = { showPrivacyPolicy = false },
            onDecline = { showPrivacyPolicy = false }
        )
    }
}

@Composable
fun DisclaimerDialog(
    onAccept: () -> Unit,
    onDecline: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { },
        title = { 
            Text(
                "⚠️ 用户协议与隐私政策",
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
                        Text("🔒 重要声明", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
                        Text("• 本软件仅供学习研究使用，严禁用于任何非法用途", style = MaterialTheme.typography.bodySmall)
                        Text("• 使用者需自行承担全部法律责任", style = MaterialTheme.typography.bodySmall)
                        Text("• 开发者不对任何使用后果负责", style = MaterialTheme.typography.bodySmall)
                        Text("• 请勿骚扰他人或进行恶意拨打", style = MaterialTheme.typography.bodySmall)
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
                        Text("❗ 功能限制说明", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                        Text(
                            "⚠️ 由于Android系统安全限制，以下功能可能受限：",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text("• 通话录音功能在非ROOT设备上可能无法正常工作", style = MaterialTheme.typography.bodySmall)
                        Text("• 音频注入（让对方听到语音）可能被系统阻止", style = MaterialTheme.typography.bodySmall)
                        Text("• 部分Android版本会严格限制应用访问通话音频通道", style = MaterialTheme.typography.bodySmall)
                        Text("\n💡 建议：如需完整功能，请使用已ROOT的设备或特定品牌手机", style = MaterialTheme.typography.bodySmall)
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
                        Text("📋 权限使用说明", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text("• CALL_PHONE：用于自动拨打电话功能", style = MaterialTheme.typography.bodySmall)
                        Text("• READ_PHONE_STATE：用于监听通话状态和SIM卡信息", style = MaterialTheme.typography.bodySmall)
                        Text("• RECORD_AUDIO：用于通话录音功能（可选）", style = MaterialTheme.typography.bodySmall)
                        Text("• READ_MEDIA_AUDIO：用于读取和播放音频文件", style = MaterialTheme.typography.bodySmall)
                        Text("• INTERNET：用于检查软件更新", style = MaterialTheme.typography.bodySmall)
                        Text("• REQUEST_INSTALL_PACKAGES：用于安装更新的APK文件", style = MaterialTheme.typography.bodySmall)
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
                        Text("🔐 隐私保护", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onTertiaryContainer)
                        Text("• 本软件不会收集、上传或分享任何个人信息", style = MaterialTheme.typography.bodySmall)
                        Text("• 所有数据仅存储在本地设备中", style = MaterialTheme.typography.bodySmall)
                        Text("• 联系人信息、通话记录等数据完全由用户控制", style = MaterialTheme.typography.bodySmall)
                        Text("• 软件不包含任何广告或追踪代码", style = MaterialTheme.typography.bodySmall)
                    }
                }

                Text(
                    "点击「我同意」表示您已阅读并完全理解以上内容，同意使用本软件",
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
                Text("我同意")
            }
        },
        dismissButton = {
            TextButton(onClick = onDecline) {
                Text("拒绝", color = MaterialTheme.colorScheme.error)
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
            Text("当前状态", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(status, style = MaterialTheme.typography.bodyLarge)
            if (total > 0) {
                LinearProgressIndicator(
                    progress = { if (total > 0) progress.toFloat() / total else 0f },
                    modifier = Modifier.fillMaxWidth()
                )
                Text("进度: $progress / $total", style = MaterialTheme.typography.bodySmall)
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
                Text("默认音频选择", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Button(onClick = onImportAudio) {
                    Text("导入音频")
                }
            }

            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(horizontal = 4.dp)
            ) {
                itemsIndexed(allAudios) { index, _ ->
                    val displayName = when (index) {
                        0 -> "余额不足"
                        1 -> "停电"
                        2 -> "欠费"
                        else -> "自定义${index - 2}"
                    }
                    FilterChip(
                        selected = selectedIndex == index,
                        onClick = { viewModel.selectAudio(index) },
                        label = { Text(displayName) }
                    )
                }
            }

            Text(
                text = "当前选中: ${allAudios.getOrElse(selectedIndex) { allAudios.firstOrNull() ?: "" }}",
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
            Text("通话统计", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(statistics, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun SimCardSelector(simCardMode: Int, onModeSelected: (Int) -> Unit) {
    var showDialog by remember { mutableStateOf(false) }
    val modeText = when (simCardMode) {
        0 -> "默认卡"
        1 -> "SIM卡1"
        2 -> "SIM卡2"
        3 -> "双卡交替"
        else -> "默认卡"
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
                Text("SIM卡选择", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(
                    "当前模式: $modeText",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "点击选择拨打方式",
                    style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.primary)
                )
            }
            Icon(
                Icons.Default.ArrowDropDown,
                contentDescription = "选择",
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("选择SIM卡模式") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        0 to "默认卡 - 使用系统默认SIM卡",
                        1 to "SIM卡1 - 强制使用卡槽1",
                        2 to "SIM卡2 - 强制使用卡槽2",
                        3 to "双卡交替 - 轮流使用两张卡"
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
                                        contentDescription = "已选择",
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
                    Text("取消")
                }
            }
        )
    }
}

@Composable
fun ExportButton(onExport: () -> Unit) {
    Button(onClick = onExport, modifier = Modifier.fillMaxWidth()) {
        Text("导出联系人列表")
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
                Text("导入电话")
            }
            Button(onClick = onImportClipboard, enabled = !isRunning, modifier = Modifier.weight(1f)) {
                Text("导入剪贴板")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onStart, enabled = !isRunning, modifier = Modifier.weight(1f)) {
                Text("开始拨打")
            }
            if (isRunning && !isPaused) {
                Button(
                    onClick = onPause, 
                    enabled = isRunning, 
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                ) {
                    Text("暂停")
                }
            } else if (isRunning && isPaused) {
                Button(
                    onClick = onResume, 
                    enabled = isRunning, 
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text("继续")
                }
            }
            Button(
                onClick = onStop, enabled = isRunning, modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Text("停止")
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
                Text("暂无电话数据")
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
                    entry.contactName.ifEmpty { "未知联系人" },
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
                    if (entry.isCalled) "✓ 已拨打 (${entry.callCount}次)" else "○ 未拨打",
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
                        "户号: ${entry.accountNumber}",
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
                        "余额: ${entry.balance}",
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
    GlobalScope.launch(Dispatchers.Main) {
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
                        val content = json.optString("body", "暂无更新说明")
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
                Log.e("UpdateCheck", "自动检查更新失败: ${e.message}")
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
    GlobalScope.launch(Dispatchers.Main) {
        try {
            withContext(Dispatchers.IO) {
                val client = OkHttpClient()
                val request = Request.Builder().url(apkUrl).build()
                val response = client.newCall(request).execute()
                
                if (!response.isSuccessful) {
                    throw Exception("下载失败: HTTP ${response.code}")
                }
                
                val body = response.body ?: throw Exception("响应体为空")
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
                    "下载失败: ${e.message}",
                    duration = SnackbarDuration.Long
                )
                onError()
            }
        }
    }
}

