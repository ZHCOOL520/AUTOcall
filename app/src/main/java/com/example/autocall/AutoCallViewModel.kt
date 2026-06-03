package com.example.autocall

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.opencsv.CSVReader
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.apache.poi.ss.usermodel.*
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.*
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class AutoCallViewModel(application: Application) : AndroidViewModel(application) {

    private val tag = "AutoCallViewModel"

    private val _phoneList = MutableStateFlow<List<PhoneEntry>>(emptyList())
    val phoneList: StateFlow<List<PhoneEntry>> = _phoneList

    private val _currentStatus = MutableStateFlow(LanguageManager.getString("status.ready"))
    val currentStatus: StateFlow<String> = _currentStatus

    fun updateStatus(status: String) {
        _currentStatus.value = status
    }

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning

    private val _progress = MutableStateFlow(0)
    val progress: StateFlow<Int> = _progress

    private var audioDirectory: File? = null

    private val sampleAudios = listOf("yu_peng_buzu.wav", "ding.wav", "no.wav")

    private val _userAudios = MutableStateFlow<List<String>>(emptyList())

    private val _selectedAudioIndex = MutableStateFlow(0)
    val selectedAudioIndex: StateFlow<Int> = _selectedAudioIndex

    private val _callRecords = MutableStateFlow<List<CallRecord>>(emptyList())

    private val _statistics = MutableStateFlow("")
    val statistics: StateFlow<String> = _statistics

    private var callStateListener: CallStateListener? = null
    private var currentMediaPlayer: MediaPlayer? = null
    private var audioInjector: CallAudioInjector? = null
    private var audioRecorder: CallAudioRecorder? = null

    private val _isRecordingEnabled = MutableStateFlow(false)
    val isRecordingEnabled: StateFlow<Boolean> = _isRecordingEnabled

    private val _isAudioPlaybackEnabled = MutableStateFlow(false)
    val isAudioPlaybackEnabled: StateFlow<Boolean> = _isAudioPlaybackEnabled

    private val _sortByBalance = MutableStateFlow(1) // 0: 不排序, 1: 从小到大, 2: 从大到小
    val sortByBalance: StateFlow<Int> = _sortByBalance

    private val _sortByCallCount = MutableStateFlow(1) // 0: 不排序, 1: 从小到大, 2: 从大到小
    val sortByCallCount: StateFlow<Int> = _sortByCallCount

    private val _currentIndex = MutableStateFlow(0) // 当前拨打的索引位置
    val currentIndex: StateFlow<Int> = _currentIndex

    private val _isPaused = MutableStateFlow(false) // 是否处于暂停状态
    val isPaused: StateFlow<Boolean> = _isPaused

    private val _callInterval = MutableStateFlow(3000L) // 拨打间隔时间（毫秒），默认3秒
    val callInterval: StateFlow<Long> = _callInterval

    @Volatile private var currentProcessingIndex: Int = -1 // 当前正在处理的索引（原子性保护）
    @Volatile private var isCallInProgress: Boolean = false // 是否有通话正在进行

    // SIM卡相关状态
    private val _simCardMode = MutableStateFlow(0) // 0: 默认卡, 1: SIM1, 2: SIM2, 3: 双卡交替
    val simCardMode: StateFlow<Int> = _simCardMode

    private val _currentSimCard = MutableStateFlow(0) // 当前使用的SIM卡 (0或1)
    val currentSimCard: StateFlow<Int> = _currentSimCard

    // 自动检查更新相关状态
    private val _autoCheckUpdateEnabled = MutableStateFlow(false)
    val autoCheckUpdateEnabled: StateFlow<Boolean> = _autoCheckUpdateEnabled

    private val _checkUpdateInterval = MutableStateFlow(2) // 0: 每日, 1: 每周, 2: 每月
    val checkUpdateInterval: StateFlow<Int> = _checkUpdateInterval

    // 语言设置相关状态
    private val _selectedLanguage = MutableStateFlow("zh") // "zh": 中文, "en": English
    val selectedLanguage: StateFlow<String> = _selectedLanguage

    // 无障碍服务相关状态
    private val _isAccessibilityServiceEnabled = MutableStateFlow(false)
    val isAccessibilityServiceEnabled: StateFlow<Boolean> = _isAccessibilityServiceEnabled

    private val prefs by lazy {
        getApplication<Application>().getSharedPreferences("app_data", Context.MODE_PRIVATE)
    }

    init {
        loadData()
        loadUpdateSettings()
        loadLanguageSettings()
        refreshAccessibilityServiceStatus()
    }

    fun toggleSimCardMode() {
        // 循环切换：0(默认) -> 1(SIM1) -> 2(SIM2) -> 3(双卡交替) -> 0(默认)
        _simCardMode.value = (_simCardMode.value + 1) % 4
        _currentSimCard.value = 0 // 重置当前卡
        when (_simCardMode.value) {
            0 -> _currentStatus.value = LanguageManager.getString("sim_card.status_default")
            1 -> _currentStatus.value = LanguageManager.getString("sim_card.status_sim1")
            2 -> _currentStatus.value = LanguageManager.getString("sim_card.status_sim2")
            3 -> _currentStatus.value = LanguageManager.getString("sim_card.status_alternate")
        }
    }

    @Suppress("UNUSED_PARAMETER")
    fun setSimCardMode(mode: Int) {
        if (mode in 0..3) {
            _simCardMode.value = mode
            _currentSimCard.value = 0
            when (mode) {
                0 -> _currentStatus.value = LanguageManager.getString("sim_card.status_default")
                1 -> _currentStatus.value = LanguageManager.getString("sim_card.status_sim1")
                2 -> _currentStatus.value = LanguageManager.getString("sim_card.status_sim2")
                3 -> _currentStatus.value = LanguageManager.getString("sim_card.status_alternate")
            }
        }
    }

    fun toggleAutoCheckUpdate() {
        _autoCheckUpdateEnabled.value = !_autoCheckUpdateEnabled.value
        saveUpdateSettings()
    }

    @Suppress("UNUSED_PARAMETER")
    fun setAutoCheckUpdate(enabled: Boolean) {
        _autoCheckUpdateEnabled.value = enabled
        saveUpdateSettings()
    }

    fun setCheckUpdateInterval(interval: Int) {
        if (interval in 0..2) {
            _checkUpdateInterval.value = interval
            saveUpdateSettings()
        }
    }

    fun setLanguage(languageCode: String) {
        if (languageCode in listOf("zh", "en")) {
            _selectedLanguage.value = languageCode
            // 更新LanguageManager
            try {
                LanguageManager.switchLanguage(getApplication<Application>(), languageCode)
            } catch (e: Exception) {
                Log.e(tag, "Error switching language", e)
            }
            // 刷新所有已计算的本地化字符串，确保UI立即更新
            refreshLocalizedStrings()
            saveLanguageSettings()
        }
    }
    
    /**
     * 刷新所有已计算的本地化字符串（语言切换后调用）
     */
    private fun refreshLocalizedStrings() {
        // 刷新状态消息（仅在空闲时刷新，避免打断正在进行的操作）
        if (!_isRunning.value) {
            _currentStatus.value = LanguageManager.getString("status.ready")
        }
        // 刷新统计信息
        if (_callRecords.value.isNotEmpty()) {
            generateStatistics(_callRecords.value)
        }
    }

    fun getLanguageText(): String {
        return LanguageManager.getLanguageDisplayName(_selectedLanguage.value)
    }

    /**
     * 刷新无障碍服务状态
     */
    fun refreshAccessibilityServiceStatus() {
        _isAccessibilityServiceEnabled.value = 
            AutoCallAccessibilityService.isServiceEnabled(getApplication<Application>())
    }

    /**
     * 打开无障碍设置页面
     */
    fun openAccessibilitySettings() {
        AutoCallAccessibilityService.openAccessibilitySettings(getApplication<Application>())
    }

    private fun saveUpdateSettings() {
        viewModelScope.launch(Dispatchers.IO) {
            prefs.edit()
                .putBoolean("auto_check_update", _autoCheckUpdateEnabled.value)
                .putInt("check_update_interval", _checkUpdateInterval.value)
                .apply()
        }
    }

    fun getSimCardModeText(): String {
        return when (_simCardMode.value) {
            0 -> LanguageManager.getString("sim_card.mode_default")
            1 -> LanguageManager.getString("sim_card.mode_sim1")
            2 -> LanguageManager.getString("sim_card.mode_sim2")
            3 -> LanguageManager.getString("sim_card.mode_alternate")
            else -> LanguageManager.getString("sim_card.mode_default")
        }
    }

    fun toggleRecording() {
        val newState = !_isRecordingEnabled.value
        _isRecordingEnabled.value = newState
        if (newState) {
            _currentStatus.value = LanguageManager.getString("status.recording_enabled")
        } else {
            _currentStatus.value = LanguageManager.getString("status.recording_disabled")
            if (audioRecorder?.isRecording() == true) {
                audioRecorder?.stopRecording()
            }
        }
    }

    fun toggleAudioPlayback() {
        val newState = !_isAudioPlaybackEnabled.value
        _isAudioPlaybackEnabled.value = newState
        if (newState) {
            _currentStatus.value = LanguageManager.getString("status.audio_playback_enabled")
        } else {
            _currentStatus.value = LanguageManager.getString("status.audio_playback_disabled")
        }
    }

    fun setCallInterval(seconds: Int) {
        if (seconds in 1..10) {
            _callInterval.value = seconds * 1000L
        }
    }

    fun getCallIntervalText(): String {
        return LanguageManager.getString("status.call_interval_seconds", _callInterval.value / 1000)
    }

    fun toggleSortByBalance() {
        // 循环切换：0(不排序) -> 1(从小到大) -> 2(从大到小) -> 0(不排序)
        _sortByBalance.value = (_sortByBalance.value + 1) % 3
        sortPhoneList()
        when (_sortByBalance.value) {
            0 -> _currentStatus.value = LanguageManager.getString("status.sort_balance_cancelled")
            1 -> _currentStatus.value = LanguageManager.getString("status.sort_balance_asc")
            2 -> _currentStatus.value = LanguageManager.getString("status.sort_balance_desc")
        }
    }

    fun toggleSortByCallCount() {
        // 循环切换：0(不排序) -> 1(从小到大) -> 2(从大到小) -> 0(不排序)
        _sortByCallCount.value = (_sortByCallCount.value + 1) % 3
        sortPhoneList()
        when (_sortByCallCount.value) {
            0 -> _currentStatus.value = LanguageManager.getString("status.sort_call_count_cancelled")
            1 -> _currentStatus.value = LanguageManager.getString("status.sort_call_count_asc")
            2 -> _currentStatus.value = LanguageManager.getString("status.sort_call_count_desc")
        }
    }

    private fun sortPhoneList() {
        val currentList = _phoneList.value.toMutableList()
        
        // 先按拨打次数排序
        when (_sortByCallCount.value) {
            0 -> { /* 不排序 */ }
            1 -> {
                // 从小到大排序
                currentList.sortWith { a, b -> a.callCount.compareTo(b.callCount) }
            }
            2 -> {
                // 从大到小排序
                currentList.sortWith { a, b -> b.callCount.compareTo(a.callCount) }
            }
        }
        
        // 再按余额排序（如果拨打次数相同）
        when (_sortByBalance.value) {
            0 -> { /* 不排序 */ }
            1 -> {
                // 从小到大排序
                currentList.sortWith { a, b ->
                    val balanceA = parseBalance(a.balance)
                    val balanceB = parseBalance(b.balance)
                    balanceA.compareTo(balanceB)
                }
            }
            2 -> {
                // 从大到小排序
                currentList.sortWith { a, b ->
                    val balanceA = parseBalance(a.balance)
                    val balanceB = parseBalance(b.balance)
                    balanceB.compareTo(balanceA)
                }
            }
        }
        
        _phoneList.value = currentList
    }

    private fun parseBalance(balance: String?): Double {
        if (balance.isNullOrEmpty()) return Double.MAX_VALUE // 无余额的排到最后
        return try {
            // 提取数字部分，去除可能的单位
            val numberStr = balance.replace(Regex("[^0-9.-]"), "")
            numberStr.toDoubleOrNull() ?: Double.MAX_VALUE
        } catch (_: Exception) {
            Double.MAX_VALUE
        }
    }

    private fun markAsCalled(index: Int) {
        val currentList = _phoneList.value.toMutableList()
        if (index in currentList.indices) {
            val entry = currentList[index]
            currentList[index] = entry.copy(isCalled = true, callCount = entry.callCount + 1)
            _phoneList.value = currentList
        }
    }

    fun markAsCalledManually(phoneNumber: String) {
        val currentList = _phoneList.value.toMutableList()
        val index = currentList.indexOfFirst { it.phoneNumber == phoneNumber }
        if (index != -1) {
            val entry = currentList[index]
            currentList[index] = entry.copy(isCalled = true, callCount = entry.callCount + 1)
            _phoneList.value = currentList
            saveData()
        }
    }

    fun clearPhoneList() {
        _phoneList.value = emptyList()
        _currentStatus.value = LanguageManager.getString("status.list_cleared")
        saveData()
    }

    fun setAudioDirectory(context: Context, directoryPath: String) {
        audioDirectory = File(directoryPath)
        if (!audioDirectory!!.exists()) {
            audioDirectory!!.mkdirs()
        }
        copySampleAudiosFromRaw(context)
    }

    private fun copySampleAudiosFromRaw(context: Context) {
        if (audioDirectory == null) return
        val rawResources = mapOf(
            "yu_peng_buzu.wav" to R.raw.yu_peng_buzu,
            "ding.wav" to R.raw.ding,
            "no.wav" to R.raw.no
        )
        rawResources.forEach { (fileName, resId) ->
            val file = File(audioDirectory, fileName)
            if (!file.exists()) {
                try {
                    context.resources.openRawResource(resId).use { input ->
                        file.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(tag, LanguageManager.getString("log.copy_sample_audio_failed", e.message ?: ""))
                }
            }
        }
    }

    fun getAllAudios(): List<String> = sampleAudios + _userAudios.value

    fun selectAudio(index: Int) {
        val all = getAllAudios()
        if (index in all.indices) _selectedAudioIndex.value = index
    }

    fun getCurrentAudioName(): String {
        val all = getAllAudios()
        val idx = _selectedAudioIndex.value
        return if (idx in all.indices) all[idx] else all.firstOrNull() ?: ""
    }

    // --- 文件导入移至 IO 线程，UI 更新切回 Main ---
    fun importFromExcel(context: Context, excelFilePath: String) {
        viewModelScope.launch(Dispatchers.IO) {
            var workbook: Workbook? = null
            try {
                val file = File(excelFilePath)
                if (!file.exists()) {
                    withContext(Dispatchers.Main) { _currentStatus.value = LanguageManager.getString("status.excel_not_found") }
                    return@launch
                }
                val inputStream = FileInputStream(file)
                workbook = if (excelFilePath.endsWith(".xlsx", true)) XSSFWorkbook(inputStream)
                else WorkbookFactory.create(inputStream)

                val sheet = workbook.getSheetAt(0)
                val phoneList = mutableListOf<PhoneEntry>()
                val firstRow = sheet.getRow(0)
                var startIndex = 0
                var phoneCol = 0; var nameCol = 1; var audioCol = 2; var accountNumberCol = -1; var balanceCol = -1
                if (firstRow != null) {
                    val map = detectColumnHeaders(firstRow)
                    phoneCol = map["phone"] ?: 0
                    nameCol = map["name"] ?: 1
                    audioCol = map["audio"] ?: 2
                    accountNumberCol = map["accountNumber"] ?: -1
                    balanceCol = map["balance"] ?: -1
                    if (map.isNotEmpty()) startIndex = 1
                }
                for (i in startIndex..sheet.lastRowNum) {
                    val row = sheet.getRow(i) ?: continue
                    val phone = extractPhoneNumber(getCellValue(row.getCell(phoneCol)))
                    if (phone.isNullOrEmpty()) continue
                    val name = getCellValue(row.getCell(nameCol))?.trim() ?: ""
                    val audioName = if (audioCol < row.physicalNumberOfCells) getCellValue(row.getCell(audioCol))?.trim() else null
                    val audioPath = if (!audioName.isNullOrEmpty()) getAudioPath(context, audioName) else null
                    val accountNumber = if (accountNumberCol >= 0 && accountNumberCol < row.physicalNumberOfCells) getCellValue(row.getCell(accountNumberCol))?.trim() else null
                    val balance = if (balanceCol >= 0 && balanceCol < row.physicalNumberOfCells) getCellValue(row.getCell(balanceCol))?.trim() else null
                    phoneList.add(PhoneEntry(phone, name, audioPath, accountNumber, balance))
                }
                workbook.close()
                inputStream.close()
                withContext(Dispatchers.Main) {
                    _phoneList.value = phoneList
                    _currentStatus.value = LanguageManager.getString("status.import_success", phoneList.size)
                    saveData()
                }
            } catch (e: Exception) {
                Log.e(tag, LanguageManager.getString("log.import_excel_failed", e.message ?: ""))
                withContext(Dispatchers.Main) { _currentStatus.value = LanguageManager.getString("status.import_failed", e.message ?: "") }
                workbook?.close()
            }
        }
    }

    fun importFromCSV(context: Context, csvFilePath: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val file = File(csvFilePath)
                if (!file.exists()) {
                    withContext(Dispatchers.Main) { _currentStatus.value = LanguageManager.getString("status.csv_not_found") }
                    return@launch
                }
                val reader = CSVReader(FileReader(file))
                val allRows = reader.readAll()
                reader.close()
                if (allRows.isEmpty()) {
                    withContext(Dispatchers.Main) { _currentStatus.value = LanguageManager.getString("status.csv_empty") }
                    return@launch
                }
                val phoneList = mutableListOf<PhoneEntry>()
                val firstRow = allRows[0]
                var startIndex = 0
                var phoneCol = 0; var nameCol = 1; var audioCol = 2; var accountNumberCol = -1; var balanceCol = -1
                
                // 检测列标题
                if (firstRow.isNotEmpty()) {
                    val phoneKeywords = listOf(
                        "电话", "手机", "手机号", "联系方式", "联系电话", "号码", "电话号码",
                        "phone", "tel", "telephone", "mobile", "cell"
                    )
                    val nameKeywords = listOf(
                        "姓名", "联系人", "名字", "称呼", "用户名", "客户",
                        "name", "contact", "person", "username", "customer"
                    )
                    val audioKeywords = listOf(
                        "语音", "音频", "录音", "文件", "语音文件",
                        "audio", "voice", "sound", "file", "recording"
                    )
                    val accountNumberKeywords = listOf(
                        "户号", "账号", "账户", "用户id", "客户编号",
                        "account", "account number", "user id", "customer_id"
                    )
                    val balanceKeywords = listOf(
                        "余额", "金额", "余额(元)", "账户余额", "剩余金额",
                        "balance", "amount", "money", "fund"
                    )
                    
                    for ((index, header) in firstRow.withIndex()) {
                        val lowerHeader = header.lowercase()
                        if (phoneKeywords.any { it in lowerHeader }) phoneCol = index
                        else if (nameKeywords.any { it in lowerHeader }) nameCol = index
                        else if (audioKeywords.any { it in lowerHeader }) audioCol = index
                        else if (accountNumberKeywords.any { it in lowerHeader }) accountNumberCol = index
                        else if (balanceKeywords.any { it in lowerHeader }) balanceCol = index
                    }
                    startIndex = 1
                }
                
                for (i in startIndex until allRows.size) {
                    val row = allRows[i]
                    if (row.isNotEmpty() && row.size > phoneCol && row[phoneCol].isNotBlank()) {
                        val phone = row[phoneCol].trim()
                        val name = if (row.size > nameCol) row[nameCol].trim() else ""
                        val audioName = if (row.size > audioCol && audioCol >= 0 && row[audioCol].isNotBlank()) row[audioCol].trim() else null
                        val audioPath = audioName?.let { getAudioPath(context, it) }
                        val accountNumber = if (accountNumberCol >= 0 && row.size > accountNumberCol && row[accountNumberCol].isNotBlank()) row[accountNumberCol].trim() else null
                        val balance = if (balanceCol >= 0 && row.size > balanceCol && row[balanceCol].isNotBlank()) row[balanceCol].trim() else null
                        phoneList.add(PhoneEntry(phone, name, audioPath, accountNumber, balance))
                    }
                }
                withContext(Dispatchers.Main) {
                    _phoneList.value = phoneList
                    _currentStatus.value = LanguageManager.getString("status.import_success", phoneList.size)
                    saveData()
                }
            } catch (e: Exception) {
                Log.e(tag, LanguageManager.getString("log.import_csv_failed", e.message ?: ""))
                withContext(Dispatchers.Main) { _currentStatus.value = LanguageManager.getString("status.import_failed", e.message ?: "") }
            }
        }
    }

    fun importFromClipboard(context: Context, clipboardText: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (clipboardText.isBlank()) {
                    withContext(Dispatchers.Main) { _currentStatus.value = LanguageManager.getString("status.clipboard_empty") }
                    return@launch
                }
                    
                val phoneList = mutableListOf<PhoneEntry>()
                    
                // 先尝试按常见分隔符拆分（逗号、分号、竖线等），再按行分割
                val separators = Regex("[,;，；|\\n\\r]+")
                val segments = clipboardText.split(separators)
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    
                for (segment in segments) {
                    // 尝试从每个片段中提取电话号码
                    val phone = extractPhoneNumber(segment)
                    if (!phone.isNullOrEmpty()) {
                        // 检查是否已存在相同号码
                        if (phoneList.none { it.phoneNumber == phone }) {
                            phoneList.add(PhoneEntry(phoneNumber = phone))
                        }
                    }
                }
                    
                if (phoneList.isEmpty()) {
                    withContext(Dispatchers.Main) { _currentStatus.value = LanguageManager.getString("status.clipboard_no_phone") }
                    return@launch
                }
                    
                withContext(Dispatchers.Main) {
                    // 追加到现有列表，而不是替换
                    val currentList = _phoneList.value.toMutableList()
                    currentList.addAll(phoneList)
                    _phoneList.value = currentList
                    _currentStatus.value = LanguageManager.getString("status.clipboard_import_success", phoneList.size)
                    saveData()
                }
            } catch (e: Exception) {
                Log.e(tag, LanguageManager.getString("log.clipboard_import_failed", e.message ?: ""))
                withContext(Dispatchers.Main) { _currentStatus.value = LanguageManager.getString("status.import_failed", e.message ?: "") }
            }
        }
    }

    private fun detectColumnHeaders(row: Row): Map<String, Int> {
        val map = mutableMapOf<String, Int>()
        val phoneKeywords = listOf(
            "电话", "手机", "手机号", "联系方式", "联系电话", "号码", "电话号码", "联系号码",
            "phone", "tel", "telephone", "mobile", "cell", "phonenumber", "contact_number"
        )
        val nameKeywords = listOf(
            "姓名", "联系人", "名字", "称呼", "用户名", "客户", "客户名称",
            "name", "contact", "person", "username", "customer"
        )
        val audioKeywords = listOf(
            "语音", "音频", "录音", "文件", "语音文件", "音频文件",
            "audio", "voice", "sound", "file", "recording", "media"
        )
        val accountNumberKeywords = listOf(
            "户号", "账号", "账户", "用户id", "客户编号", "账户号",
            "account", "account number", "user id", "account_id", "customer_id"
        )
        val balanceKeywords = listOf(
            "余额", "金额", "余额(元)", "账户余额", "剩余金额", "钱",
            "balance", "amount", "money", "fund", "remaining"
        )
        for (i in 0 until row.physicalNumberOfCells) {
            val cellValue = row.getCell(i)?.toString()?.trim()?.lowercase() ?: continue
            if (phoneKeywords.any { it in cellValue }) map["phone"] = i
            else if (nameKeywords.any { it in cellValue }) map["name"] = i
            else if (audioKeywords.any { it in cellValue }) map["audio"] = i
            else if (accountNumberKeywords.any { it in cellValue }) map["accountNumber"] = i
            else if (balanceKeywords.any { it in cellValue }) map["balance"] = i
        }
        return map
    }

    private fun extractPhoneNumber(value: String?): String? {
        if (value.isNullOrEmpty()) return null
        
        // 第零步：预处理 - 去除序号前缀（如 "1."、"2)"、"3、" 等）
        var processed = value.trim()
        // 匹配常见的序号格式：数字+标点符号开头
        processed = processed.replace(Regex("^\\d+[.、,)）\\s]+"), "")
        
        // 第一步：处理常见电话号码分隔符
        // 去除空格、横杠、括号、点号、逗号、斜杠等常见格式化字符
        processed = processed.replace(Regex("[\\s\\-()（）\\[\\]{}.，、/]+"), "")
        
        // 第二步：基础清理 - 去除所有非数字和+号字符
        var cleaned = processed.replace(Regex("[^0-9+]"), "")
        
        // 第三步：处理国家代码
        // 去除+86或86前缀（中国大陆）
        if (cleaned.startsWith("+86")) cleaned = cleaned.substring(3)
        else if (cleaned.startsWith("86") && cleaned.length > 11) cleaned = cleaned.substring(2)
        // 去除其他常见国际区号
        else if (cleaned.startsWith("+852")) cleaned = cleaned.substring(4) // 香港
        else if (cleaned.startsWith("+853")) cleaned = cleaned.substring(4) // 澳门
        else if (cleaned.startsWith("+886")) cleaned = cleaned.substring(4) // 台湾
        else if (cleaned.startsWith("+1")) cleaned = cleaned.substring(2) // 美国/加拿大
        else if (cleaned.startsWith("+44")) cleaned = cleaned.substring(3) // 英国
        else if (cleaned.startsWith("+81")) cleaned = cleaned.substring(3) // 日本
        else if (cleaned.startsWith("+82")) cleaned = cleaned.substring(3) // 韩国
        
        // 第四步：处理国内长途前缀0
        if (cleaned.startsWith("0") && cleaned.length in 11..12) cleaned = cleaned.substring(1)
        
        // 第五步：严格验证并返回有效号码（仅接受中国大陆手机号）
        return when {
            // 中国大陆手机号：1开头的11位数字，第二位为3-9
            cleaned.matches(Regex("^1[3-9]\\d{9}$")) -> cleaned
            // 其他格式一律拒绝（包括固话、短号码等），防止误拨户号
            else -> {
                Log.w(tag, LanguageManager.getString("log.invalid_number", value, cleaned))
                null
            }
        }
    }

    private fun getCellValue(cell: Cell?): String? {
        if (cell == null) return null
        return when (cell.cellType) {
            CellType.STRING -> cell.stringCellValue
            CellType.NUMERIC -> {
                if (DateUtil.isCellDateFormatted(cell)) {
                    // 日期格式
                    cell.dateCellValue.toString()
                } else {
                    // 数字格式 - 保留原始格式，避免科学计数法
                    val numericValue = cell.numericCellValue
                    // 如果是整数，不显示小数点
                    if (numericValue == numericValue.toLong().toDouble()) {
                        numericValue.toLong().toString()
                    } else {
                        // 浮点数，保留2位小数
                        String.format("%.2f", numericValue)
                    }
                }
            }
            CellType.BOOLEAN -> cell.booleanCellValue.toString()
            CellType.FORMULA -> {
                // 尝试获取公式计算结果
                try {
                    when (cell.cachedFormulaResultType) {
                        CellType.STRING -> cell.stringCellValue
                        CellType.NUMERIC -> cell.numericCellValue.toString()
                        else -> cell.cellFormula
                    }
                } catch (e: Exception) {
                    cell.cellFormula
                }
            }
            else -> null
        }
    }

    private fun getAudioPath(context: Context, fileName: String): String? {
        audioDirectory?.let {
            val file = File(it, fileName)
            if (file.exists()) return file.absolutePath
        }
        val defaultDir = context.getExternalFilesDir(null)
        if (defaultDir != null) {
            val file = File(defaultDir, fileName)
            if (file.exists()) return file.absolutePath
        }
        return null
    }

    fun importAudioFile(context: Context, uri: Uri): Boolean {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val fileName = getFileNameFromUri(context, uri)
                if (!isSupportedAudioFormat(fileName)) {
                    withContext(Dispatchers.Main) { _currentStatus.value = LanguageManager.getString("status.audio_format_unsupported") }
                    return@launch
                }
                if (audioDirectory == null) {
                    audioDirectory = File(context.getExternalFilesDir(null), "audio").also { it.mkdirs() }
                }
                var destFile = File(audioDirectory, fileName)
                var counter = 1
                while (destFile.exists()) {
                    val nameWithoutExt = fileName.substringBeforeLast(".")
                    val ext = fileName.substringAfterLast(".", "")
                    destFile = File(audioDirectory, "${nameWithoutExt}_$counter.$ext")
                    counter++
                }
                context.contentResolver.openInputStream(uri)?.use { input ->
                    destFile.outputStream().use { output -> input.copyTo(output) }
                }
                val newList = _userAudios.value.toMutableList()
                newList.add(destFile.name)
                _userAudios.value = newList
                val newIndex = sampleAudios.size + (newList.size - 1)
                withContext(Dispatchers.Main) {
                    _selectedAudioIndex.value = newIndex
                    _currentStatus.value = LanguageManager.getString("status.audio_import_success", destFile.name)
                }
            } catch (e: Exception) {
                Log.e(tag, LanguageManager.getString("log.audio_import_failed", e.message ?: ""))
                withContext(Dispatchers.Main) { _currentStatus.value = LanguageManager.getString("status.audio_import_failed", e.message ?: "") }
            }
        }
        return true
    }

    private fun getFileNameFromUri(context: Context, uri: Uri): String {
        var fileName = "audio_${System.currentTimeMillis()}.mp3"
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && nameIndex != -1) fileName = cursor.getString(nameIndex)
        }
        return fileName
    }

    private fun isSupportedAudioFormat(fileName: String): Boolean {
        val exts = listOf("mp3", "wav", "ogg", "m4a", "aac", "flac", "wma", "amr", "3gp", "opus", "webm", "mkv")
        return fileName.substringAfterLast(".", "").lowercase() in exts
    }

    // ---------- 核心拨打流程 ----------
    fun startAutoCall(context: Context) {
        startAutoCallFromIndex(context, 0)
    }

    private fun startAutoCallFromIndex(context: Context, startIndex: Int) {
        if (_isRunning.value && !_isPaused.value) return
        viewModelScope.launch(Dispatchers.Main) {
            _isRunning.value = true
            _isPaused.value = false
            val list = _phoneList.value
            if (list.isEmpty()) {
                _currentStatus.value = LanguageManager.getString("status.phone_list_empty")
                _isRunning.value = false
                return@launch
            }

            // 清理旧监听器，新建并注册
            callStateListener?.close()
            callStateListener = CallStateListener(getApplication()).also { it.register() }

            val records = mutableListOf<CallRecord>()
            _currentStatus.value = LanguageManager.getString("status.auto_call_started", list.size, startIndex + 1)

            for ((index, entry) in list.withIndex()) {
                if (index < startIndex) continue // 跳过已经拨打过的
                
                // 检查是否被停止或暂停
                if (!_isRunning.value) break
                if (_isPaused.value) {
                    // 暂停状态下，保持_isRunning为true，等待恢复
                    _currentStatus.value = LanguageManager.getString("status.paused_at", index + 1, list.size)
                    // 等待直到不再暂停或被停止
                    while (_isPaused.value && _isRunning.value) {
                        delay(500L)
                    }
                    // 如果被停止则退出
                    if (!_isRunning.value) break
                }
                
                // 【关键修复】设置当前处理索引，防止状态混乱
                currentProcessingIndex = index
                isCallInProgress = true
                
                _currentIndex.value = index
                _progress.value = index + 1
                _currentStatus.value = LanguageManager.getString("status.dialing", index + 1, list.size, entry.contactName.ifEmpty { entry.phoneNumber })
                Log.d(tag, LanguageManager.getString("log.start_dial_index", index, entry.phoneNumber))

                val startTime = System.currentTimeMillis()
                val timestamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                    .format(java.util.Date(startTime))

                val audioPath = entry.audioFilePath ?: getCurrentAudioFile(getApplication())
                
                // 根据SIM卡模式选择拨打方式
                val callSuccess = when (_simCardMode.value) {
                    3 -> {
                        // 双卡交替模式
                        _currentSimCard.value = if (_currentSimCard.value == 0) 1 else 0
                        makeCallWithSimCard(context, entry.phoneNumber, _currentSimCard.value)
                    }
                    1 -> makeCallWithSimCard(context, entry.phoneNumber, 0) // SIM1
                    2 -> makeCallWithSimCard(context, entry.phoneNumber, 1) // SIM2
                    else -> makeCall(context, entry.phoneNumber) // 默认卡
                }

                _currentStatus.value = LanguageManager.getString("status.waiting_connect")
                Log.d(tag, LanguageManager.getString("log.waiting_connect"))
                val connected = waitForCallConnect()
                Log.d(tag, LanguageManager.getString("log.connect_result", connected))

                var recordPath: String? = null
                if (connected) {
                    Log.d(tag, LanguageManager.getString("log.call_connected"))
                    // 初始化音频注入器和录音器
                    if (audioInjector == null) audioInjector = CallAudioInjector(getApplication())
                    if (audioRecorder == null) audioRecorder = CallAudioRecorder(getApplication())

                    // 启动录音（如果开启）
                    if (_isRecordingEnabled.value) {
                        recordPath = audioRecorder?.startRecording(entry.phoneNumber)
                        _currentStatus.value = LanguageManager.getString("status.recording_started")
                        Log.d(tag, LanguageManager.getString("log.recording_started"))
                        delay(500L)
                    }

                    // 播放音频（如果开启且存在音频文件）
                    if (_isAudioPlaybackEnabled.value && !audioPath.isNullOrEmpty()) {
                        _currentStatus.value = LanguageManager.getString("status.call_connected_playing")
                        Log.d(tag, LanguageManager.getString("log.start_playing_audio"))
                        val disconnectJob = viewModelScope.launch { waitForCallDisconnect(index) }
                        val audioJob = viewModelScope.launch { audioInjector?.injectAudioToCall(audioPath) }

                        disconnectJob.join()
                        Log.d(tag, LanguageManager.getString("log.disconnect_detected"))
                        audioJob.cancel()
                        audioInjector?.stop()
                        stopAudioPlayback()
                    } else {
                        // 不播放音频，只等待挂断
                        _currentStatus.value = LanguageManager.getString("status.call_connected_no_audio")
                        Log.d(tag, LanguageManager.getString("log.waiting_manual_hangup"))
                        waitForCallDisconnect(index)
                        Log.d(tag, LanguageManager.getString("log.disconnect_detected_no_audio"))
                    }

                    // 停止录音（如果开启）
                    if (_isRecordingEnabled.value && audioRecorder?.isRecording() == true) {
                        recordPath = audioRecorder?.stopRecording()
                        Log.d(tag, LanguageManager.getString("log.recording_stopped"))
                    }
                    _currentStatus.value = LanguageManager.getString("status.call_ended")
                    Log.d(tag, LanguageManager.getString("log.call_flow_end"))
                } else {
                    _currentStatus.value = LanguageManager.getString("status.call_not_connected")
                    Log.d(tag, LanguageManager.getString("log.not_connected_skip"))
                    recordPath = null // 明确赋值为null
                }

                val status = if (connected && callSuccess) LanguageManager.getString("status.call_success") else LanguageManager.getString("status.call_failed")
                records.add(CallRecord(
                    phoneNumber = entry.phoneNumber,
                    contactName = entry.contactName.ifEmpty { LanguageManager.getString("status.unknown") },
                    callStatus = status,
                    callDuration = (System.currentTimeMillis() - startTime) / 1000,
                    timestamp = timestamp,
                    recordFilePath = recordPath
                ))
                
                // 【关键修复】仅在确认当前通话彻底结束后，才标记为已拨打
                Log.d(tag, LanguageManager.getString("log.mark_index_called", index))
                markAsCalled(index)
                
                // 重置状态标志
                isCallInProgress = false
                currentProcessingIndex = -1
                
                // 等待通话完全结束并进入IDLE状态
                Log.d(tag, LanguageManager.getString("log.wait_call_interval", _callInterval.value / 1000))
                delay(_callInterval.value)
                
                // 确保系统处于空闲状态后再继续
                waitForIdleState()
                Log.d(tag, LanguageManager.getString("log.index_completed", index))
            }

            // 只有在真正完成或停止时才清理状态
            if (!_isPaused.value) {
                callStateListener?.close()
                callStateListener = null
                _callRecords.value = records
                generateStatistics(records)
                _currentStatus.value = LanguageManager.getString("status.all_completed")
                _isRunning.value = false
            }
        }
    }

    private suspend fun waitForCallConnect(): Boolean {
        val listener = callStateListener ?: return false
        val result = CompletableDeferred<Boolean>()
        val job = viewModelScope.launch {
            listener.callStateFlow.collect { state ->
                when (state) {
                    CallStateListener.CallState.CONNECTED -> if (!result.isCompleted) result.complete(true)
                    CallStateListener.CallState.DISCONNECTED -> if (!result.isCompleted) result.complete(false)
                    else -> {}
                }
            }
        }
        return try {
            withTimeoutOrNull(30_000) { result.await() } ?: false
        } finally {
            job.cancel()
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun waitForCallDisconnect(expectedIndex: Int) {
        val listener = callStateListener ?: return
        val result = CompletableDeferred<Unit>()
        
        val job = viewModelScope.launch {
            listener.callStateFlow.collect { state ->
                Log.d(tag, LanguageManager.getString("log.state_monitor", state, expectedIndex, currentProcessingIndex))
                
                // 【关键修复】只有当前处理的索引匹配时才响应状态变化
                if (currentProcessingIndex != expectedIndex) {
                    Log.w(tag, LanguageManager.getString("log.index_mismatch_skip", currentProcessingIndex, expectedIndex))
                    return@collect
                }
                
                when (state) {
                    CallStateListener.CallState.DISCONNECTED -> {
                        Log.d(tag, LanguageManager.getString("log.disconnect_event_detected"))
                        
                        // 【关键修复】等待短暂时间确认是否真的断开（防止短暂断线重连）
                        delay(2000L)
                        
                        // 再次检查状态，如果仍然是DISCONNECTED且索引匹配，才确认为真正断开
                        if (currentProcessingIndex == expectedIndex && isCallInProgress) {
                            Log.d(tag, LanguageManager.getString("log.confirmed_disconnect"))
                            if (!result.isCompleted) {
                                audioInjector?.stop()
                                stopAudioPlayback()
                                if (_isRecordingEnabled.value && audioRecorder?.isRecording() == true) {
                                    audioRecorder?.stopRecording()
                                }
                                // 重置音频模式
                                val am = getApplication<Application>().getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                                if (am != null) {
                                    am.mode = AudioManager.MODE_NORMAL
                                    am.setSpeakerphoneOn(false)
                                } else {
                                    Log.w(tag, LanguageManager.getString("log.cannot_get_audio_manager"))
                                }
                                result.complete(Unit)
                            }
                        } else {
                            Log.w(tag, LanguageManager.getString("log.state_changed_or_mismatch"))
                        }
                    }
                    CallStateListener.CallState.CONNECTED -> {
                        Log.d(tag, LanguageManager.getString("log.reconnect_detected"))
                    }
                    else -> {}
                }
            }
        }
        try {
            withTimeoutOrNull(300_000) { result.await() }
        } finally {
            job.cancel()
        }
    }

    private fun stopAudioPlayback() {
        currentMediaPlayer?.apply {
            if (isPlaying) stop()
            release()
        }
        currentMediaPlayer = null
    }

    private fun generateStatistics(records: List<CallRecord>) {
        val total = records.size
        val success = records.count { it.callStatus == LanguageManager.getString("status.call_success") }
        val failed = total - success
        val totalDuration = records.sumOf { it.callDuration }
        _statistics.value = LanguageManager.getString("status.statistics", total, success, failed, totalDuration)
    }

    fun stopAutoCall() {
        Log.d(tag, LanguageManager.getString("log.stop_auto_call"))
        _isRunning.value = false
        _isPaused.value = false
        isCallInProgress = false
        currentProcessingIndex = -1
        audioInjector?.stop()
        audioRecorder?.release()
        _currentStatus.value = LanguageManager.getString("status.stopped")
    }

    fun pauseAutoCall() {
        if (_isRunning.value && !_isPaused.value) {
            Log.d(tag, LanguageManager.getString("log.pause_auto_call", _currentIndex.value))
            _isPaused.value = true
            _currentStatus.value = LanguageManager.getString("status.paused_at", _currentIndex.value + 1, _phoneList.value.size)
        }
    }

    fun resumeAutoCall(context: Context) {
        if (_isRunning.value && _isPaused.value) {
            Log.d(tag, LanguageManager.getString("log.resume_auto_call", _currentIndex.value))
            // 仅解除暂停状态，让原协程循环从暂停处自然继续，不启动新协程
            // 原循环在 while (_isPaused.value && _isRunning.value) 处等待，
            // 恢复后从 _currentIndex 对应的号码继续拨打，不会跳过
            _isPaused.value = false
            _currentStatus.value = LanguageManager.getString("status.resuming")
        }
    }

    private suspend fun waitForIdleState() {
        val listener = callStateListener ?: return
        // 短暂等待以确保状态稳定
        delay(500L)
        // 简单延迟等待，确保系统进入IDLE状态
        // 由于StateFlow不支持tryReceive，我们直接等待固定时间
        delay(1000L)
    }

    @SuppressLint("UseKtx")
    private fun makeCall(context: Context, phoneNumber: String): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse("tel:$phoneNumber")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            _currentStatus.value = LanguageManager.getString("status.dial_failed", e.message ?: "")
            false
        }
    }

    @SuppressLint("UseKtx")
    private fun makeCallWithSimCard(context: Context, phoneNumber: String, simSlot: Int): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse("tel:$phoneNumber")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                // 使用Android标准API指定SIM卡槽
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP_MR1) {
                    // 主要方式：使用SubscriptionManager获取正确的slotId
                    try {
                        val subscriptionManager = context.getSystemService(android.telephony.SubscriptionManager::class.java)
                        if (subscriptionManager != null) {
                            // 检查权限
                            if (context.checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                                val activeSubscriptions = subscriptionManager.activeSubscriptionInfoList
                                if (activeSubscriptions != null && activeSubscriptions.isNotEmpty()) {
                                    // simSlot是0或1，对应列表索引
                                    val index = if (simSlot < activeSubscriptions.size) simSlot else 0
                                    val subscriptionInfo = activeSubscriptions[index]
                                    // 使用subscriptionId而不是slotId
                                    putExtra("android.telephony.extra.SUBSCRIPTION_INDEX", subscriptionInfo.subscriptionId)
                                    putExtra("subscription", subscriptionInfo.subscriptionId)
                                }
                            } else {
                                Log.w(tag, LanguageManager.getString("log.missing_read_phone_state"))
                            }
                        }
                    } catch (e: SecurityException) {
                        Log.e(tag, LanguageManager.getString("log.permission_insufficient", e.message ?: ""))
                        // 降级方案：使用旧的extra参数
                        putExtra("com.android.phone.force.slot", true)
                        putExtra("Cdma_Supp", true)
                        putExtra("slot", simSlot)
                        putExtra("simSlot", simSlot)
                    } catch (e: Exception) {
                        Log.e(tag, LanguageManager.getString("log.get_subscription_info_failed", e.message ?: ""))
                        // 降级方案：使用旧的extra参数
                        putExtra("com.android.phone.force.slot", true)
                        putExtra("Cdma_Supp", true)
                        putExtra("slot", simSlot)
                        putExtra("simSlot", simSlot)
                    }
                }
            }
            context.startActivity(intent)
            _currentStatus.value = LanguageManager.getString("sim_card.using_sim", if (simSlot == 0) LanguageManager.getString("sim_card.mode_sim1") else LanguageManager.getString("sim_card.mode_sim2"))
            true
        } catch (e: Exception) {
            _currentStatus.value = LanguageManager.getString("status.dial_failed", e.message ?: "")
            false
        }
    }

    private fun getCurrentAudioFile(context: Context): String? {
        val name = getCurrentAudioName()
        if (name.isEmpty()) return null
        return getAudioPath(context, name)
    }

    fun exportCallRecordsToUri(context: Context, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val phoneList = _phoneList.value
                if (phoneList.isEmpty()) {
                    withContext(Dispatchers.Main) { _currentStatus.value = LanguageManager.getString("status.no_contacts_to_export") }
                    return@launch
                }
                context.contentResolver.openOutputStream(uri)?.use { os ->
                    // Windows适配：BOM + UTF-8编码
                    os.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
                    val writer = OutputStreamWriter(os, Charsets.UTF_8)
                    
                    // CSV头部（使用逗号分隔，Windows Excel兼容）
                    writer.write("\uFEFF") // BOM标记
                    writer.write(LanguageManager.getString("status.csv_header") + "\r\n")
                    
                    // 写入联系人数据
                    phoneList.forEach { entry ->
                        val name = escapeCsvField(entry.contactName.ifEmpty { LanguageManager.getString("status.unknown") })
                        val phone = escapeCsvField(entry.phoneNumber)
                        val balance = escapeCsvField(entry.balance ?: "")
                        val accountNumber = escapeCsvField(entry.accountNumber ?: "")
                        val callCount = entry.callCount.toString()
                        
                        writer.write("$name,$phone,$balance,$accountNumber,$callCount\r\n")
                    }
                    
                    writer.flush()
                }
                withContext(Dispatchers.Main) { 
                    _currentStatus.value = LanguageManager.getString("status.export_success", phoneList.size)
                }
            } catch (e: Exception) {
                Log.e(tag, LanguageManager.getString("log.export_failed", e.message ?: ""))
                withContext(Dispatchers.Main) { _currentStatus.value = LanguageManager.getString("status.export_failed", e.message ?: "") }
            }
        }
    }
    
    private fun escapeCsvField(field: String): String {
        // 如果字段包含逗号、引号或换行符，需要用引号包裹
        return if (field.contains(",") || field.contains("\"") || field.contains("\n") || field.contains("\r")) {
            "\"${field.replace("\"", "\"\"")}\"" // 转义引号
        } else {
            field
        }
    }

    override fun onCleared() {
        super.onCleared()
        callStateListener?.close()
        audioInjector?.stop()
        audioRecorder?.release()
        stopAudioPlayback()
        saveData()
    }

    @SuppressLint("UseKtx")
    private fun saveData() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val gson = Gson()
                // 保存电话列表
                val phoneListJson = gson.toJson(_phoneList.value)
                prefs.edit().putString("phone_list", phoneListJson).apply()
                
                // 保存通话记录
                val recordsJson = gson.toJson(_callRecords.value)
                prefs.edit().putString("call_records", recordsJson).apply()
                
                // 保存统计信息
                prefs.edit().putString("statistics", _statistics.value).apply()
            } catch (e: Exception) {
                Log.e(tag, LanguageManager.getString("log.save_data_failed", e.message ?: ""))
            }
        }
    }

    private fun loadData() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val gson = Gson()
                // 加载电话列表
                val phoneListJson = prefs.getString("phone_list", null)
                if (!phoneListJson.isNullOrEmpty()) {
                    val type = object : TypeToken<List<PhoneEntry>>() {}.type
                    val loadedList = gson.fromJson<List<PhoneEntry>>(phoneListJson, type)
                    withContext(Dispatchers.Main) {
                        _phoneList.value = loadedList
                    }
                }
                
                // 加载通话记录
                val recordsJson = prefs.getString("call_records", null)
                if (!recordsJson.isNullOrEmpty()) {
                    val type = object : TypeToken<List<CallRecord>>() {}.type
                    val loadedRecords = gson.fromJson<List<CallRecord>>(recordsJson, type)
                    withContext(Dispatchers.Main) {
                        _callRecords.value = loadedRecords
                    }
                }
                
                // 加载统计信息
                val stats = prefs.getString("statistics", "")
                if (!stats.isNullOrEmpty()) {
                    withContext(Dispatchers.Main) {
                        _statistics.value = stats
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, LanguageManager.getString("log.load_data_failed", e.message ?: ""))
            }
        }
    }

    private fun loadUpdateSettings() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val autoCheckEnabled = prefs.getBoolean("auto_check_update", false)
                val interval = prefs.getInt("check_update_interval", 2) // 默认每月
                withContext(Dispatchers.Main) {
                    _autoCheckUpdateEnabled.value = autoCheckEnabled
                    _checkUpdateInterval.value = interval
                }
            } catch (e: Exception) {
                Log.e(tag, LanguageManager.getString("log.load_update_settings_failed", e.message ?: ""))
            }
        }
    }

    private fun saveLanguageSettings() {
        viewModelScope.launch(Dispatchers.IO) {
            prefs.edit()
                .putString("app_language", _selectedLanguage.value)
                .apply()
        }
    }

    private fun loadLanguageSettings() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // 检查是否有保存的语言设置
                val hasSavedLanguage = prefs.contains("app_language")
                
                val language = if (hasSavedLanguage) {
                    // 使用保存的设置
                    prefs.getString("app_language", "zh") ?: "zh"
                } else {
                    // 首次启动，根据系统语言自动选择
                    val systemLocale = java.util.Locale.getDefault()
                    val systemLanguage = systemLocale.language.lowercase()
                    
                    when {
                        systemLanguage == "zh" -> "zh"  // 中文（包括简体、繁体）
                        systemLanguage == "en" -> "en"  // 英文
                        else -> "zh"  // 默认使用中文
                    }
                }
                
                withContext(Dispatchers.Main) {
                    _selectedLanguage.value = language
                    // 同步更新LanguageManager
                    LanguageManager.switchLanguage(getApplication<Application>(), language)
                }
            } catch (e: Exception) {
                Log.e(tag, LanguageManager.getString("log.load_language_settings_failed", e.message ?: ""))
            }
        }
    }
}