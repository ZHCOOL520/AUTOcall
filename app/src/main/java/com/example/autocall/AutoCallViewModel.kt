package com.example.autocall

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.autocall.audio.Capabilities
import com.example.autocall.audio.ChannelProbe
import com.example.autocall.record.CallResult
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

/** ★ 修 B43：内置样本音频，带显式标签 key（不再按下标硬编码）。 */
data class SampleAudio(val fileName: String, val labelKey: String)

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

    // ★ 修 B43：样本音频改为「显式标签映射」，不再靠下标 0/1/2 猜标签
    private val sampleAudios = listOf(
        SampleAudio("yu_peng_buzu.wav", "audio_selector.audio_0"),
        SampleAudio("ding.wav", "audio_selector.audio_1"),
        SampleAudio("no.wav", "audio_selector.audio_2")
    )

    private val _userAudios = MutableStateFlow<List<String>>(emptyList())

    private val _selectedAudioIndex = MutableStateFlow(0)
    val selectedAudioIndex: StateFlow<Int> = _selectedAudioIndex

    private val _callRecords = MutableStateFlow<List<CallRecord>>(emptyList())
    // ★ 修 B41：暴露通话记录公开流，供录音库页面（Task 2.9）读取
    val callRecords: StateFlow<List<CallRecord>> = _callRecords

    private val _statistics = MutableStateFlow("")
    val statistics: StateFlow<String> = _statistics

    // ★ Task 3.1：拨号主循环（Job / 状态监听 / 注入器 / 录音器）全部收敛到控制器
    private var callController: PhoneCallController? = null

    // ★ 通道探测结果（Task 1.3/1.9）：UI 可展示当前生效通道
    private val _capabilities = MutableStateFlow<Capabilities?>(null)
    val capabilities: StateFlow<Capabilities?> = _capabilities

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

    // ====== 无障碍高级功能状态 ======
    // 自动挂断
    private val _isAutoHangupEnabled = MutableStateFlow(false)
    val isAutoHangupEnabled: StateFlow<Boolean> = _isAutoHangupEnabled

    private val _autoHangupDelay = MutableStateFlow(30) // 秒
    val autoHangupDelay: StateFlow<Int> = _autoHangupDelay

    // 扬声器自动控制
    private val _isSpeakerphoneAutoEnabled = MutableStateFlow(false)
    val isSpeakerphoneAutoEnabled: StateFlow<Boolean> = _isSpeakerphoneAutoEnabled

    // 自动接听延迟
    private val _autoAnswerDelay = MutableStateFlow(0) // 秒，0表示立即接听
    val autoAnswerDelay: StateFlow<Int> = _autoAnswerDelay

    // DTMF音发送
    private val _dtmfTones = MutableStateFlow("")
    val dtmfTones: StateFlow<String> = _dtmfTones

    // 拨号重试次数
    private val _dialRetryCount = MutableStateFlow(0)
    val dialRetryCount: StateFlow<Int> = _dialRetryCount

    // 重复号码标记（用于UI视觉反馈）
    private val _duplicatePhoneNumbers = MutableStateFlow<Set<String>>(emptySet())
    val duplicatePhoneNumbers: StateFlow<Set<String>> = _duplicatePhoneNumbers

    private val prefs by lazy {
        getApplication<Application>().getSharedPreferences("app_data", Context.MODE_PRIVATE)
    }

    // ★ 修 B24：独立作用域，不依赖 viewModelScope。
    // androidx 的 ViewModel.clear() 会先取消 viewModelScope 再调用 onCleared()，
    // 导致 onCleared 里 launch 的协程体永远不会执行 —— 记录因此从不落盘。
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        loadData()
        loadRuntimeSettings()
        loadUpdateSettings()
        loadLanguageSettings()
        loadAccessibilitySettings()
        refreshAccessibilityServiceStatus()
        refreshCapabilities()
    }

    // ====== 无障碍高级功能控制方法 ======

    fun toggleAutoHangup() {
        _isAutoHangupEnabled.value = !_isAutoHangupEnabled.value
        saveAccessibilitySettings()
        syncAccessibilitySettings()
    }

    fun setAutoHangupDelay(seconds: Int) {
        if (seconds in 5..600) {
            _autoHangupDelay.value = seconds
            saveAccessibilitySettings()
            syncAccessibilitySettings()
        }
    }

    fun toggleSpeakerphoneAuto() {
        _isSpeakerphoneAutoEnabled.value = !_isSpeakerphoneAutoEnabled.value
        saveAccessibilitySettings()
        syncAccessibilitySettings()
    }

    fun setAutoAnswerDelay(seconds: Int) {
        if (seconds in 0..30) {
            _autoAnswerDelay.value = seconds
            saveAccessibilitySettings()
            syncAccessibilitySettings()
        }
    }

    fun setDtmfTones(tones: String) {
        // 只允许有效的DTMF字符
        val validTones = tones.filter { it in "0123456789*#ABCDabcd" }
        _dtmfTones.value = validTones
    }

    fun sendDtmfTones() {
        if (_dtmfTones.value.isNotEmpty()) {
            AutoCallAccessibilityService.requestSendDtmf(_dtmfTones.value)
        }
    }

    fun setDialRetryCount(count: Int) {
        if (count in 0..5) {
            _dialRetryCount.value = count
            saveAccessibilitySettings()
            syncAccessibilitySettings()
        }
    }

    /**
     * 同步无障碍设置到服务
     */
    private fun syncAccessibilitySettings() {
        AutoCallAccessibilityService.isAutoHangupEnabled = _isAutoHangupEnabled.value
        AutoCallAccessibilityService.isSpeakerphoneAutoEnabled = _isSpeakerphoneAutoEnabled.value
        AutoCallAccessibilityService.autoAnswerDelayMs = _autoAnswerDelay.value * 1000L
        AutoCallAccessibilityService.dialRetryCount = _dialRetryCount.value
    }

    /**
     * 保存无障碍设置
     */
    private fun saveAccessibilitySettings() {
        viewModelScope.launch(Dispatchers.IO) {
            prefs.edit()
                .putBoolean("auto_hangup_enabled", _isAutoHangupEnabled.value)
                .putInt("auto_hangup_delay", _autoHangupDelay.value)
                .putBoolean("speakerphone_auto_enabled", _isSpeakerphoneAutoEnabled.value)
                .putInt("auto_answer_delay", _autoAnswerDelay.value)
                .putInt("dial_retry_count", _dialRetryCount.value)
                .apply()
        }
    }

    /**
     * 加载无障碍设置
     */
    private fun loadAccessibilitySettings() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val autoHangupEnabled = prefs.getBoolean("auto_hangup_enabled", false)
                val autoHangupDelay = prefs.getInt("auto_hangup_delay", 30)
                val speakerphoneAutoEnabled = prefs.getBoolean("speakerphone_auto_enabled", false)
                val autoAnswerDelay = prefs.getInt("auto_answer_delay", 0)
                val dialRetryCount = prefs.getInt("dial_retry_count", 0)
                withContext(Dispatchers.Main) {
                    _isAutoHangupEnabled.value = autoHangupEnabled
                    _autoHangupDelay.value = autoHangupDelay
                    _isSpeakerphoneAutoEnabled.value = speakerphoneAutoEnabled
                    _autoAnswerDelay.value = autoAnswerDelay
                    _dialRetryCount.value = dialRetryCount
                    // 同步到服务
                    syncAccessibilitySettings()
                }
            } catch (e: Exception) {
                Log.e(tag, LanguageManager.getString("log.load_accessibility_settings_failed"))
            }
        }
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
        // ★ 修 B1：不再因开启无障碍而强制关闭录音 —— 无障碍恰是高版本唯一可用的录音途径。
    }

    /**
     * 打开无障碍设置页面
     */
    fun openAccessibilitySettings() {
        AutoCallAccessibilityService.openAccessibilitySettings(getApplication<Application>())
    }

    /** 探测当前设备可用的录音/注入通道（Task 1.3） */
    fun refreshCapabilities() {
        _capabilities.value = ChannelProbe.probe(getApplication(), _isAccessibilityServiceEnabled.value)
    }

    // ★ 修 B7：关键运行开关持久化，避免重启 App 后录音/播放开关与选中音频回退默认值
    private fun saveRuntimeSettings() {
        prefs.edit()
            .putBoolean("recording_enabled", _isRecordingEnabled.value)
            .putBoolean("audio_playback_enabled", _isAudioPlaybackEnabled.value)
            .putInt("selected_audio_index", _selectedAudioIndex.value)
            .putString("user_audios", Gson().toJson(_userAudios.value))
            .apply()
    }

    private fun loadRuntimeSettings() {
        _isRecordingEnabled.value = prefs.getBoolean("recording_enabled", false)
        _isAudioPlaybackEnabled.value = prefs.getBoolean("audio_playback_enabled", false)
        _selectedAudioIndex.value = prefs.getInt("selected_audio_index", 0)
        val type = object : TypeToken<List<String>>() {}.type
        _userAudios.value = Gson().fromJson<List<String>>(prefs.getString("user_audios", null), type)
            ?: emptyList()
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
            // 关闭录音时丢弃未落盘的临时文件（正常路径由拨号循环 commit）
            callController?.cancelRecording()
        }
        saveRuntimeSettings()
    }

    fun toggleAudioPlayback() {
        val newState = !_isAudioPlaybackEnabled.value
        _isAudioPlaybackEnabled.value = newState
        if (newState) {
            _currentStatus.value = LanguageManager.getString("status.audio_playback_enabled")
        } else {
            _currentStatus.value = LanguageManager.getString("status.audio_playback_disabled")
        }
        saveRuntimeSettings()
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

    /**
     * 按指定选项排序电话列表
     * @param sortOption 排序选项：0-不排序, 1-号码升序, 2-号码降序, 3-拨打次数升序, 4-拨打次数降序, 5-余额升序, 6-余额降序
     */
    fun sortPhoneListByOption(sortOption: Int) {
        val currentList = _phoneList.value.toMutableList()
        
        when (sortOption) {
            0 -> { /* 不排序，恢复原始顺序 */ }
            1 -> currentList.sortBy { it.phoneNumber }
            2 -> currentList.sortByDescending { it.phoneNumber }
            3 -> currentList.sortBy { it.callCount }
            4 -> currentList.sortByDescending { it.callCount }
            5 -> currentList.sortBy { parseBalance(it.balance) }
            6 -> currentList.sortByDescending { parseBalance(it.balance) }
        }
        
        _phoneList.value = currentList
        saveData()
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

    /**
     * 一键整理去重：移除重复号码并按号码排序
     */
    fun deduplicateAndSortPhoneList() {
        val currentList = _phoneList.value
        if (currentList.isEmpty()) {
            _currentStatus.value = LanguageManager.getString("status.phone_list_empty")
            return
        }

        val originalSize = currentList.size
        
        // 去重：保留第一个出现的号码，移除后续重复项
        val uniqueList = currentList.distinctBy { it.phoneNumber }
        
        // 按号码升序排序
        val sortedList = uniqueList.sortedBy { it.phoneNumber }
        
        val removedCount = originalSize - sortedList.size
        
        _phoneList.value = sortedList
        
        _currentStatus.value = if (removedCount > 0) {
            LanguageManager.getString("status.deduplicate_success", removedCount)
        } else {
            LanguageManager.getString("status.deduplicate_no_duplicates")
        }
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

    fun getAllAudios(): List<String> = sampleAudios.map { it.fileName } + _userAudios.value

    /** ★ 修 B43：样本音频数量（用户音频下标从前在此之后）。 */
    fun sampleAudioCount(): Int = sampleAudios.size

    /** ★ 修 B43：样本音频的显式标签 key；非样本（用户导入）返回 null。 */
    fun getAudioLabelKey(index: Int): String? =
        if (index in sampleAudios.indices) sampleAudios[index].labelKey else null

    fun selectAudio(index: Int) {
        val all = getAllAudios()
        if (index in all.indices) {
            _selectedAudioIndex.value = index
            saveRuntimeSettings()
        }
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
                    phoneList.add(PhoneEntry(phoneNumber = phone, contactName = name, audioFilePath = audioPath, accountNumber = accountNumber, balance = balance, audioName = audioName))
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
                        phoneList.add(PhoneEntry(phoneNumber = phone, contactName = name, audioFilePath = audioPath, accountNumber = accountNumber, balance = balance, audioName = audioName))
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

    /**
     * 从文本输入中识别并导入电话号码
     * @param context 上下文
     * @param text 包含电话号码的文本
     */
    fun importFromTextInput(context: Context, text: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (text.isBlank()) {
                    withContext(Dispatchers.Main) { _currentStatus.value = LanguageManager.getString("status.text_input_empty") }
                    return@launch
                }

                // 显示正在识别状态
                withContext(Dispatchers.Main) { _currentStatus.value = LanguageManager.getString("status.text_input_recognizing") }

                // 收集所有识别到的手机号（包括重复的）
                val allRecognizedPhones = mutableListOf<String>()

                // 第一步：使用正则表达式直接提取所有可能的11位手机号
                val phonePattern = Regex("(?:^|[\\s,;，；|\\n\\r])(1[3-9]\\d{9})(?:$|[\\s,;，；|\\n\\r])")
                val matches = phonePattern.findAll(text)

                for (match in matches) {
                    val phone = match.groupValues[1]
                    if (phone.isNotEmpty()) {
                        allRecognizedPhones.add(phone)
                    }
                }

                // 第二步：如果上面的模式没有匹配到，尝试更宽松的提取方式
                if (allRecognizedPhones.isEmpty()) {
                    val separators = Regex("[,;，；|\\n\\r]+")
                    val segments = text.split(separators)
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }

                    for (segment in segments) {
                        val phone = extractPhoneNumber(segment)
                        if (!phone.isNullOrEmpty()) {
                            allRecognizedPhones.add(phone)
                        }
                    }
                }

                if (allRecognizedPhones.isEmpty()) {
                    withContext(Dispatchers.Main) { _currentStatus.value = LanguageManager.getString("status.text_input_no_phone") }
                    return@launch
                }

                // 第三步：严格去重处理
                // 1. 对识别结果本身去重
                val uniquePhones = allRecognizedPhones.distinct()
                val duplicateInInput = allRecognizedPhones.size - uniquePhones.size

                // 2. 与现有列表去重，找出需要新增的号码
                val existingPhones = _phoneList.value.map { it.phoneNumber }.toSet()
                val newPhones = uniquePhones.filter { it !in existingPhones }
                val duplicateWithExisting = uniquePhones.size - newPhones.size

                // 计算总重复数
                val totalDuplicates = duplicateInInput + duplicateWithExisting

                if (newPhones.isEmpty()) {
                    // 所有号码都已存在于列表中
                    withContext(Dispatchers.Main) {
                        // 设置重复号码标记（用于UI视觉反馈）
                        if (totalDuplicates > 0) {
                            val duplicatesInText = allRecognizedPhones.groupBy { it }
                                .filter { it.value.size > 1 }
                                .keys
                            val duplicatesWithExisting = uniquePhones.filter { it in existingPhones }
                            _duplicatePhoneNumbers.value = (duplicatesInText + duplicatesWithExisting).toSet()
                        }
                        _currentStatus.value = if (totalDuplicates > 0) {
                            LanguageManager.getString("status.text_input_all_duplicates", uniquePhones.size, totalDuplicates)
                        } else {
                            LanguageManager.getString("status.text_input_no_phone")
                        }
                    }
                    return@launch
                }

                // 第四步：导入新的唯一号码
                val phoneListToAdd = newPhones.map { PhoneEntry(phoneNumber = it) }

                withContext(Dispatchers.Main) {
                    // 追加到现有列表，而不是替换
                    val currentList = _phoneList.value.toMutableList()
                    currentList.addAll(phoneListToAdd)
                    _phoneList.value = currentList

                    // 设置重复号码标记（用于UI视觉反馈）
                    if (totalDuplicates > 0) {
                        // 收集所有重复的号码：文本内重复 + 与现有列表重复
                        val duplicatesInText = allRecognizedPhones.groupBy { it }
                            .filter { it.value.size > 1 }
                            .keys
                        val duplicatesWithExisting = uniquePhones.filter { it in existingPhones }
                        _duplicatePhoneNumbers.value = (duplicatesInText + duplicatesWithExisting).toSet()
                    } else {
                        _duplicatePhoneNumbers.value = emptySet()
                    }

                    // 根据是否有重复，显示不同的状态提示
                    _currentStatus.value = if (totalDuplicates > 0) {
                        LanguageManager.getString("status.text_input_success_with_duplicates", phoneListToAdd.size, totalDuplicates)
                    } else {
                        LanguageManager.getString("status.text_input_success", phoneListToAdd.size)
                    }
                    saveData()
                }
            } catch (e: Exception) {
                Log.e(tag, LanguageManager.getString("log.text_input_import_failed", e.message ?: ""))
                withContext(Dispatchers.Main) { _currentStatus.value = LanguageManager.getString("status.import_failed", e.message ?: "") }
            }
        }
    }

    /**
     * 清除重复号码标记（UI调用后标记消失）
     */
    fun clearDuplicatePhoneNumbers() {
        _duplicatePhoneNumbers.value = emptySet()
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
        // 修 B8：导入写入的是 .../audio/ 子目录，兜底也要查这里，而非仅查私有根目录
        val audioSubDir = File(context.getExternalFilesDir(null), "audio")
        if (audioSubDir.exists()) {
            val file = File(audioSubDir, fileName)
            if (file.exists()) return file.absolutePath
        }
        val defaultDir = context.getExternalFilesDir(null)
        if (defaultDir != null) {
            val file = File(defaultDir, fileName)
            if (file.exists()) return file.absolutePath
        }
        return null
    }

    /**
     * ★ 修 B8/B34：播放前按表格里填写的**原始**文件名重解析真实路径。
     *
     * 导入去重时会把同名文件改名成 `name_1.ext`，若只保存导入时解析出的路径，
     * 之后表格改名/文件改名就永久失联。这里每次播放前依次尝试：
     *  1. `.../audio/name` 与私有根目录下的同名文件；
     *  2. `name_N.ext` 去重改名后的文件（前缀 + 扩展名匹配）。
     */
    private fun resolveAudioByName(context: Context, name: String?): String? {
        if (name.isNullOrBlank()) return null
        val dirs = listOfNotNull(
            audioDirectory,
            File(context.getExternalFilesDir(null), "audio"),
            context.getExternalFilesDir(null)
        )
        // 1) 精确同名
        for (d in dirs) {
            val f = File(d, name)
            if (f.exists()) return f.absolutePath
        }
        // 2) 去重改名 name_N.ext（name_1.ext, name_2.ext …）
        val base = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "")
        for (d in dirs) {
            if (!d.exists()) continue
            val hit = d.listFiles()?.firstOrNull { f ->
                f.isFile && f.name.startsWith("${base}_") &&
                    (ext.isEmpty() || f.extension.equals(ext, ignoreCase = true))
            }
            if (hit != null) return hit.absolutePath
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
                    saveRuntimeSettings()
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
        // ★ Task 3.4：全新开始，重置断点
        prefs.edit().putInt("last_completed_index", -1).apply()
        startAutoCallFromIndex(context, 0)
    }

    /** ★ Task 3.4：断点续拨的起始索引（上次已完成的下一通）。 */
    fun resumeIndex(): Int = prefs.getInt("last_completed_index", -1) + 1

    /** ★ Task 3.4：从上次断点继续拨号。 */
    fun startAutoCallFromLastIndex(context: Context) {
        startAutoCallFromIndex(context, resumeIndex())
    }

    private fun startAutoCallFromIndex(context: Context, startIndex: Int) {
        if (_isRunning.value && !_isPaused.value) return
        // ★ Task 3.1：拨号主循环与其 Job 交由 PhoneCallController 持有（含 NonCancellable 收尾）
        callController?.stop()   // 先停掉上一个控制器，避免遗留循环（修 B22）
        callController = PhoneCallController(getApplication(), createPhoneCallHost())
        callController?.start(context, startIndex)
    }

    /**
     * 用**匿名对象**实现 [PhoneCallHost]：匿名对象位于本类词法作用域内，
     * 因此可直接访问 ViewModel 的私有成员，无需放宽任何成员的可见性。
     */
    private fun createPhoneCallHost(): PhoneCallHost = object : PhoneCallHost {
        override fun phoneList(): List<PhoneEntry> = _phoneList.value
        override fun simCardMode(): Int = _simCardMode.value
        override fun currentSimCard(): Int = _currentSimCard.value
        override fun callIntervalMs(): Long = _callInterval.value
        override fun isRecordingEnabled(): Boolean = _isRecordingEnabled.value
        override fun isAudioPlaybackEnabled(): Boolean = _isAudioPlaybackEnabled.value
        override fun isAccessibilityEnabled(): Boolean = _isAccessibilityServiceEnabled.value
        override fun dialRetryCount(): Int = _dialRetryCount.value
        override fun isRunning(): Boolean = _isRunning.value
        override fun isPaused(): Boolean = _isPaused.value
        override fun existingRecords(): List<CallRecord> = _callRecords.value
        override fun resolveAudioPath(entry: PhoneEntry): String? =
            resolveAudioByName(this@AutoCallViewModel.getApplication(), entry.audioName)
                ?: entry.audioFilePath
                ?: getCurrentAudioFile(this@AutoCallViewModel.getApplication())
        override fun lastCompletedIndex(): Int = prefs.getInt("last_completed_index", -1)

        override fun setCurrentSimCard(slot: Int) { _currentSimCard.value = slot }
        override fun setRunning(running: Boolean) { _isRunning.value = running }
        override fun setPaused(paused: Boolean) { _isPaused.value = paused }
        override fun updateStatus(status: String) { _currentStatus.value = status }
        override fun setProgress(value: Int) { _progress.value = value }
        override fun setCurrentIndex(index: Int) { _currentIndex.value = index }
        override fun setCapabilities(caps: Capabilities) { _capabilities.value = caps }
        override fun setRecords(records: List<CallRecord>) { _callRecords.value = records }
        override fun markAsCalled(index: Int) { this@AutoCallViewModel.markAsCalled(index) }
        override fun saveData() { this@AutoCallViewModel.saveData() }
        override fun generateStatistics(records: List<CallRecord>) { this@AutoCallViewModel.generateStatistics(records) }
        override fun setLastCompletedIndex(index: Int) {
            prefs.edit().putInt("last_completed_index", index).apply()
        }
    }



    private fun generateStatistics(records: List<CallRecord>) {
        val total = records.size
        // 修 B26：按稳定结果码统计成功；兼容历史记录里存的翻译文本
        val legacySuccess = LanguageManager.getString("status.call_success")
        val success = records.count { it.resultCode == CallResult.OK.code || it.callStatus == legacySuccess }
        val failed = total - success
        // 修 B42：区隔「总通话时长」与「总拨打耗时」，并格式化为 时:分:秒
        val talkDurationSec = records.sumOf { it.callDuration }
        val dialDurationSec = records.sumOf { it.dialDuration }
        _statistics.value = LanguageManager.getString(
            "status.statistics", total, success, failed,
            formatDuration(talkDurationSec), formatDuration(dialDurationSec)
        )
    }

    companion object {
        /** 修 B42：秒 → `H:MM:SS`（纯逻辑，便于单测）。 */
        fun formatDuration(seconds: Long): String {
            val s = seconds.coerceAtLeast(0)
            return String.format(java.util.Locale.US, "%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
        }

        /** CSV 字段转义：含逗号/引号/换行时用引号包裹并转义引号（纯逻辑，便于单测）。 */
        internal fun escapeCsvField(field: String): String {
            return if (field.contains(",") || field.contains("\"") || field.contains("\n") || field.contains("\r")) {
                "\"${field.replace("\"", "\"\"")}\""
            } else {
                field
            }
        }
    }

    fun stopAutoCall() {
        Log.d(tag, LanguageManager.getString("log.stop_auto_call"))
        // ★ Task 3.1：停止由控制器负责（取消循环 Job，收尾在其 NonCancellable 中完成）
        callController?.stop()
        saveData()
        _currentStatus.value = LanguageManager.getString("status.stopped")
    }

    fun pauseAutoCall() {
        Log.d(tag, LanguageManager.getString("log.pause_auto_call", _currentIndex.value))
        callController?.pause()
    }

    @Suppress("UNUSED_PARAMETER")
    fun resumeAutoCall(context: Context) {
        Log.d(tag, LanguageManager.getString("log.resume_auto_call", _currentIndex.value))
        callController?.resume()
    }

    private fun getCurrentAudioFile(context: Context): String? {
        val name = getCurrentAudioName()
        if (name.isEmpty()) return null
        return getAudioPath(context, name)
    }

    /**
     * 导出**号码表**（姓名/号码/余额/户号/拨打次数）为 CSV。
     * 修 B40：原方法名为 `exportCallRecordsToUri` 但导出的其实是号码表，改名以澄清。
     */
    fun exportPhoneListToUri(context: Context, uri: Uri) {
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
    
    /**
     * ★ 修 B40：导出**真正的通话记录**为 CSV（时间/方向/SIM/号码/姓名/结果/通话时长/录音路径/通道）。
     * 与 [exportPhoneListToUri]（号码表）区分开。
     */
    fun exportCallRecordsToUri(context: Context, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val records = _callRecords.value
                if (records.isEmpty()) {
                    withContext(Dispatchers.Main) { _currentStatus.value = LanguageManager.getString("status.no_contacts_to_export") }
                    return@launch
                }
                context.contentResolver.openOutputStream(uri)?.use { os ->
                    // Windows适配：BOM + UTF-8编码
                    os.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
                    val writer = OutputStreamWriter(os, Charsets.UTF_8)
                    writer.write("\uFEFF") // BOM标记
                    writer.write(LanguageManager.getString("status.csv_header_call_records") + "\r\n")

                    records.forEach { r ->
                        val cols = listOf(
                            escapeCsvField(r.timestamp),
                            escapeCsvField(r.direction),
                            r.simSlot.toString(),
                            escapeCsvField(r.phoneNumber),
                            escapeCsvField(r.contactName),
                            escapeCsvField(r.resultCode.ifEmpty { r.callStatus }),
                            formatDuration(r.callDuration),
                            formatDuration(r.dialDuration),
                            escapeCsvField(r.recordFilePath ?: ""),
                            escapeCsvField(r.recordMode)
                        )
                        writer.write(cols.joinToString(",") + "\r\n")
                    }
                    writer.flush()
                }
                withContext(Dispatchers.Main) {
                    _currentStatus.value = LanguageManager.getString("status.export_success", records.size)
                }
            } catch (e: Exception) {
                Log.e(tag, LanguageManager.getString("log.export_failed", e.message ?: ""))
                withContext(Dispatchers.Main) { _currentStatus.value = LanguageManager.getString("status.export_failed", e.message ?: "") }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        // ★ Task 3.1：控制器负责关闭监听器 / 停止注入 / 释放录音
        callController?.dispose()
        saveData()
    }

    @SuppressLint("UseKtx")
    private fun saveData() {
        // ★ 修 B24：改由独立 ioScope 写盘，onCleared() 里也能真正执行
        val app = getApplication<Application>()
        val phoneListJson = Gson().toJson(_phoneList.value)
        val recordsJson = Gson().toJson(_callRecords.value)
        val statisticsText = _statistics.value
        ioScope.launch {
            try {
                app.getSharedPreferences("app_data", Context.MODE_PRIVATE).edit()
                    .putString("phone_list", phoneListJson)
                    .putString("call_records", recordsJson)
                    .putString("statistics", statisticsText)
                    .apply()
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