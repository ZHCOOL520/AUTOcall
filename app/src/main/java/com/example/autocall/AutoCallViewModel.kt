package com.example.autocall

import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opencsv.CSVReader
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.apache.poi.ss.usermodel.*
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.File
import java.io.FileInputStream
import java.io.FileReader

/**
 * 自动拨打ViewModel
 * 管理电话列表、拨打状态和语音播放
 */
class AutoCallViewModel : ViewModel() {

    private val tag = "AutoCallViewModel"

    // 电话列表
    private val _phoneList = MutableStateFlow<List<PhoneEntry>>(emptyList())
    val phoneList: StateFlow<List<PhoneEntry>> = _phoneList

    // 当前拨打状态
    private val _currentStatus = MutableStateFlow("准备就绪")
    val currentStatus: StateFlow<String> = _currentStatus
    
    // 更新状态的方法
    fun updateStatus(status: String) {
        _currentStatus.value = status
    }

    // 是否正在执行
    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning

    // 当前进度
    private val _progress = MutableStateFlow(0)
    val progress: StateFlow<Int> = _progress
    
    // 音频文件目录
    private var audioDirectory: File? = null
    
    // 示例音频列表（对应res/raw中的音频文件）
    private val sampleAudios = listOf(
        "yu_peng_buzu.wav",
        "ding.wav",
        "no.wav"
    )
    
    // 用户导入的音频列表
    private val _userAudios = MutableStateFlow<List<String>>(emptyList())
    
    // 当前选中的音频索引（-1表示未选择，0-2为示例音频，3+为用户音频）
    private val _selectedAudioIndex = MutableStateFlow(0)
    val selectedAudioIndex: StateFlow<Int> = _selectedAudioIndex
    
    // 通话记录列表
    private val _callRecords = MutableStateFlow<List<CallRecord>>(emptyList())
    val callRecords: StateFlow<List<CallRecord>> = _callRecords
    
    // 统计信息
    private val _statistics = MutableStateFlow("")
    val statistics: StateFlow<String> = _statistics
    
    // 电话状态监听器
    private var callStateListener: CallStateListener? = null
    
    // 当前播放的MediaPlayer
    private var currentMediaPlayer: MediaPlayer? = null
    
    // 录音开关
    private val _isRecordingEnabled = MutableStateFlow(false)
    val isRecordingEnabled: StateFlow<Boolean> = _isRecordingEnabled
    
    /**
     * 切换录音开关
     */
    fun toggleRecording() {
        _isRecordingEnabled.value = !_isRecordingEnabled.value
    }
    
    /**
     * 设置音频文件目录
     */
    fun setAudioDirectory(context: Context, directoryPath: String) {
        audioDirectory = File(directoryPath)
        if (!audioDirectory!!.exists()) {
            audioDirectory!!.mkdirs()
        }
        
        // 从res/raw复制示例音频文件
        copySampleAudiosFromRaw(context)
    }
    
    /**
     * 从res/raw复制示例音频到存储目录
     */
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
                    Log.d(tag, "复制示例音频: $fileName")
                } catch (e: Exception) {
                    Log.e(tag, "复制示例音频失败: ${e.message}", e)
                }
            }
        }
    }
    
    /**
     * 获取所有音频列表（示例+用户）
     */
    fun getAllAudios(): List<String> {
        return sampleAudios + _userAudios.value
    }
    
    /**
     * 选择音频
     */
    fun selectAudio(index: Int) {
        val allAudios = getAllAudios()
        if (index in allAudios.indices) {
            _selectedAudioIndex.value = index
        }
    }
    
    /**
     * 获取当前选中的音频文件名
     */
    fun getCurrentAudioName(): String {
        val allAudios = getAllAudios()
        val index = _selectedAudioIndex.value
        return if (index in allAudios.indices) allAudios[index] else allAudios.firstOrNull() ?: ""
    }

    /**
     * 从Excel文件导入电话列表
     * 支持.xls和.xlsx格式
     */
    fun importFromExcel(context: Context, excelFilePath: String) {
        viewModelScope.launch {
            var workbook: Workbook? = null
            try {
                val file = File(excelFilePath)
                if (!file.exists()) {
                    _currentStatus.value = "Excel文件不存在"
                    return@launch
                }

                val inputStream = FileInputStream(file)
                workbook = if (excelFilePath.endsWith(".xlsx", ignoreCase = true)) {
                    XSSFWorkbook(inputStream)
                } else {
                    WorkbookFactory.create(inputStream)
                }

                val sheet = workbook.getSheetAt(0)
                val phoneList = mutableListOf<PhoneEntry>()

                // 检测标题行，识别列位置
                val firstRow = sheet.getRow(0)
                var startIndex = 0
                var phoneColumnIndex = 0
                var nameColumnIndex = 1
                var audioColumnIndex = 2
                
                if (firstRow != null) {
                    val columnMap = detectColumnHeaders(firstRow)
                    phoneColumnIndex = columnMap["phone"] ?: 0
                    nameColumnIndex = columnMap["name"] ?: 1
                    audioColumnIndex = columnMap["audio"] ?: 2
                    
                    // 如果检测到标题行，从第二行开始
                    if (columnMap.isNotEmpty()) {
                        startIndex = 1
                    }
                }

                for (i in startIndex..sheet.lastRowNum) {
                    val row = sheet.getRow(i) ?: continue
                    
                    // 读取电话号码
                    val phoneCell = row.getCell(phoneColumnIndex)
                    val phoneNumber = extractPhoneNumber(getCellValue(phoneCell))
                    
                    if (phoneNumber.isNullOrEmpty()) continue

                    // 读取联系人姓名
                    val nameCell = row.getCell(nameColumnIndex)
                    val contactName = getCellValue(nameCell)?.trim() ?: ""

                    // 读取语音文件名（可选）
                    val audioCell = if (audioColumnIndex < row.physicalNumberOfCells) {
                        row.getCell(audioColumnIndex)
                    } else null
                    val audioFileName = getCellValue(audioCell)?.trim()
                    
                    val audioPath = if (!audioFileName.isNullOrEmpty()) {
                        getAudioPath(context, audioFileName)
                    } else null

                    phoneList.add(PhoneEntry(
                        phoneNumber = phoneNumber,
                        contactName = contactName,
                        audioFilePath = audioPath
                    ))
                }

                workbook.close()
                inputStream.close()

                _phoneList.value = phoneList
                _currentStatus.value = "成功导入 ${phoneList.size} 个电话"
                Log.d(tag, "Excel导入完成: ${phoneList.size} 条记录")
            } catch (e: Exception) {
                Log.e(tag, "导入Excel失败: ${e.message}", e)
                _currentStatus.value = "导入失败: ${e.message}"
                workbook?.close()
            }
        }
    }

    /**
     * 检测表格标题行，返回列名到索引的映射
     */
    private fun detectColumnHeaders(row: Row): Map<String, Int> {
        val columnMap = mutableMapOf<String, Int>()
        
        // 电话列关键词
        val phoneKeywords = listOf("电话", "手机", "手机号", "联系方式", "联系电话", "号码", 
                                   "phone", "tel", "telephone", "mobile", "cell")
        // 姓名列关键词
        val nameKeywords = listOf("姓名", "联系人", "名字", "称呼", "name", "contact", "person")
        // 语音列关键词
        val audioKeywords = listOf("语音", "音频", "录音", "文件", "audio", "voice", "sound", "file")
        
        for (i in 0 until row.physicalNumberOfCells) {
            val cell = row.getCell(i)
            val cellValue = cell?.toString()?.trim()?.lowercase() ?: continue
            
            if (phoneKeywords.any { it in cellValue }) {
                columnMap["phone"] = i
            } else if (nameKeywords.any { it in cellValue }) {
                columnMap["name"] = i
            } else if (audioKeywords.any { it in cellValue }) {
                columnMap["audio"] = i
            }
        }
        
        return columnMap
    }

    /**
     * 从单元格值中提取电话号码
     * 处理各种格式：带空格、横线、括号、国家码等
     */
    private fun extractPhoneNumber(value: String?): String? {
        if (value.isNullOrEmpty()) return null
        
        // 移除所有非数字字符，但保留+号（国际区号）
        var cleaned = value.replace(Regex("[^0-9+]"), "")
        
        // 处理常见格式
        when {
            // 去掉开头的0或86（中国大陆）
            cleaned.startsWith("+86") -> cleaned = cleaned.substring(3)
            cleaned.startsWith("86") && cleaned.length > 11 -> cleaned = cleaned.substring(2)
            cleaned.startsWith("0") && cleaned.length == 12 -> cleaned = cleaned.substring(1)
        }
        
        // 验证是否为有效手机号（11位，以1开头）
        if (cleaned.matches(Regex("^1[3-9]\\d{9}$"))) {
            return cleaned
        }
        
        // 验证是否为固话（区号+号码）
        if (cleaned.matches(Regex("^0\\d{2,3}\\d{7,8}$"))) {
            return cleaned
        }
        
        // 如果是纯数字且长度合理，也返回
        if (cleaned.matches(Regex("^\\d{7,15}$"))) {
            return cleaned
        }
        
        // 都不匹配，返回原始清理后的值
        return if (cleaned.isNotEmpty()) cleaned else null
    }

    /**
     * 获取单元格的字符串值
     */
    private fun getCellValue(cell: Cell?): String? {
        if (cell == null) return null
        
        return when (cell.cellType) {
            CellType.STRING -> cell.stringCellValue
            CellType.NUMERIC -> {
                // 判断是否为日期
                if (DateUtil.isCellDateFormatted(cell)) {
                    cell.dateCellValue.toString()
                } else {
                    // 电话号码可能是数字，转换为字符串
                    val numValue = cell.numericCellValue
                    if (numValue == numValue.toLong().toDouble()) {
                        numValue.toLong().toString()
                    } else {
                        numValue.toString()
                    }
                }
            }
            CellType.BOOLEAN -> cell.booleanCellValue.toString()
            CellType.FORMULA -> {
                try {
                    cell.cellFormula
                } catch (_: Exception) {
                    null
                }
            }
            else -> null
        }
    }

    /**
     * 从CSV文件导入电话列表
     * CSV格式: 电话号码,联系人姓名,语音文件名
     */
    fun importFromCSV(context: Context, csvFilePath: String) {
        viewModelScope.launch {
            try {
                val file = File(csvFilePath)
                if (!file.exists()) {
                    _currentStatus.value = "CSV文件不存在"
                    return@launch
                }

                val reader = CSVReader(FileReader(file))
                val allRows = reader.readAll()
                reader.close()

                if (allRows.isEmpty()) {
                    _currentStatus.value = "CSV文件为空"
                    return@launch
                }

                val phoneList = mutableListOf<PhoneEntry>()
                // 跳过标题行（如果第一行是标题）
                val firstRow = allRows[0]
                val startIndex = if (firstRow.isNotEmpty() && 
                    (firstRow[0].equals("电话", ignoreCase = true) || 
                     firstRow[0].equals("phoneNumber", ignoreCase = true))) 1 else 0

                for (i in startIndex until allRows.size) {
                    val row = allRows[i]
                    if (row.isNotEmpty() && row[0].isNotBlank()) {
                        val phoneNumber = row[0].trim()
                        val contactName = if (row.size > 1) row[1].trim() else ""
                        val audioFileName = if (row.size > 2 && row[2].isNotBlank()) row[2].trim() else null
                        
                        val audioPath = if (audioFileName != null) {
                            getAudioPath(context, audioFileName)
                        } else null

                        phoneList.add(PhoneEntry(
                            phoneNumber = phoneNumber,
                            contactName = contactName,
                            audioFilePath = audioPath
                        ))
                    }
                }

                _phoneList.value = phoneList
                _currentStatus.value = "成功导入 ${phoneList.size} 个电话"
                Log.d(tag, "导入完成: ${phoneList.size} 条记录")
            } catch (e: Exception) {
                Log.e(tag, "导入CSV失败: ${e.message}", e)
                _currentStatus.value = "导入失败: ${e.message}"
            }
        }
    }

    /**
     * 初始化示例数据
     * 实际使用时可以从CSV/Excel文件读取
     */
    fun initializeSampleData(context: Context) {
        val sampleList = listOf(
            PhoneEntry(
                phoneNumber = "13800138000",
                contactName = "测试联系人1",
                audioFilePath = getAudioPath(context, "audio1.mp3")
            ),
            PhoneEntry(
                phoneNumber = "13900139000",
                contactName = "测试联系人2",
                audioFilePath = getAudioPath(context, "audio2.mp3")
            ),
            PhoneEntry(
                phoneNumber = "13700137000",
                contactName = "测试联系人3",
                audioFilePath = null // 无语音文件
            )
        )
        _phoneList.value = sampleList
    }

    /**
     * 从文件路径获取音频完整路径
     */
    private fun getAudioPath(context: Context, fileName: String): String? {
        // 优先从自定义目录查找
        if (audioDirectory != null) {
            val file = File(audioDirectory, fileName)
            if (file.exists()) return file.absolutePath
        }
        
        // 从默认目录查找
        val defaultDir = context.getExternalFilesDir(null)
        if (defaultDir != null) {
            val file = File(defaultDir, fileName)
            if (file.exists()) return file.absolutePath
        }
        
        return null
    }
    
    /**
     * 获取当前选中的音频文件完整路径
     */
    private fun getCurrentAudioFile(context: Context): String? {
        val audioName = getCurrentAudioName()
        if (audioName.isEmpty()) return null
        return getAudioPath(context, audioName)
    }
    
    /**
     * 导入音频文件
     * 支持格式：mp3, wav, ogg, m4a, aac, flac, wma, amr
     */
    fun importAudioFile(context: Context, uri: Uri): Boolean {
        try {
            val fileName = getFileNameFromUri(context, uri)
            if (!isSupportedAudioFormat(fileName)) {
                _currentStatus.value = "不支持的音频格式"
                return false
            }
            
            // 确保音频目录存在
            if (audioDirectory == null) {
                audioDirectory = File(context.getExternalFilesDir(null), "audio")
                if (!audioDirectory!!.exists()) {
                    audioDirectory!!.mkdirs()
                }
            }
            
            val destFile = File(audioDirectory, fileName)
            
            // 如果文件已存在，添加序号
            var finalFile = destFile
            var counter = 1
            while (finalFile.exists()) {
                val nameWithoutExt = fileName.substringBeforeLast(".")
                val ext = fileName.substringAfterLast(".", "")
                finalFile = File(audioDirectory, "${nameWithoutExt}_$counter.$ext")
                counter++
            }
            
            // 复制文件
            context.contentResolver.openInputStream(uri)?.use { input ->
                finalFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            
            _currentStatus.value = "音频文件导入成功: ${finalFile.name}"
            Log.d(tag, "音频文件已保存: ${finalFile.absolutePath}")
            
            // 添加到用户音频列表
            val newUserAudios = _userAudios.value.toMutableList()
            newUserAudios.add(finalFile.name)
            _userAudios.value = newUserAudios
            
            // 自动选择新导入的音频（索引为 sampleAudios.size + 新用户音频索引）
            val newIndex = sampleAudios.size + (newUserAudios.size - 1)
            _selectedAudioIndex.value = newIndex
            
            return true
        } catch (e: Exception) {
            Log.e(tag, "导入音频失败: ${e.message}", e)
            _currentStatus.value = "导入音频失败: ${e.message}"
            return false
        }
    }
    
    /**
     * 从URI获取文件名
     */
    private fun getFileNameFromUri(context: Context, uri: Uri): String {
        var fileName = "audio_${System.currentTimeMillis()}.mp3"
        
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && nameIndex != -1) {
                fileName = cursor.getString(nameIndex)
            }
        }
        
        return fileName
    }
    
    /**
     * 检查是否为支持的音频格式
     */
    private fun isSupportedAudioFormat(fileName: String): Boolean {
        val supportedExtensions = listOf(
            "mp3", "wav", "ogg", "m4a", "aac", "flac", "wma", "amr",
            "3gp", "opus", "webm", "mkv"
        )
        val ext = fileName.substringAfterLast(".", "").lowercase()
        return ext in supportedExtensions
    }

    /**
     * 开始自动拨打流程
     */
    fun startAutoCall(context: Context) {
        if (_isRunning.value) {
            Log.w(tag, "已经在运行中")
            return
        }

        viewModelScope.launch {
            _isRunning.value = true
            val list = _phoneList.value
            val records = mutableListOf<CallRecord>()
            
            if (list.isEmpty()) {
                _currentStatus.value = "电话列表为空"
                _isRunning.value = false
                return@launch
            }
            
            // 初始化电话监听器
            callStateListener = CallStateListener(context)
            callStateListener?.register()

            _currentStatus.value = "开始自动拨打，共 ${list.size} 个电话"
            
            for ((index, entry) in list.withIndex()) {
                if (!_isRunning.value) {
                    _currentStatus.value = "已停止"
                    break
                }

                _progress.value = index + 1
                _currentStatus.value = "正在拨打第 ${index + 1}/${list.size} 个: ${entry.contactName.ifEmpty { entry.phoneNumber }}"
                
                val startTime = System.currentTimeMillis()
                val timestamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                    .format(java.util.Date(startTime))
                
                // 获取当前选中的音频文件路径
                val currentAudioPath = if (entry.audioFilePath.isNullOrEmpty()) {
                    // 如果表格中没有指定音频，使用当前选中的默认音频
                    getCurrentAudioFile(context)
                } else {
                    entry.audioFilePath
                }
                
                // 拨打电话
                val callSuccess = makeCall(context, entry.phoneNumber)
                
                // 等待电话接通（监听电话状态）
                _currentStatus.value = "等待接通..."
                val connected = waitForCallConnect()
                
                val endTime = System.currentTimeMillis()
                val duration = (endTime - startTime) / 1000
                
                // 如果接通，播放语音
                if (connected && !currentAudioPath.isNullOrEmpty()) {
                    _currentStatus.value = "电话已接通，正在播放语音..."
                    
                    // 启动后台协程监听通话结束，无论是否录音都要在通话结束时停止音频
                    val disconnectJob = viewModelScope.launch {
                        try {
                            waitForCallDisconnect()
                        } catch (e: Exception) {
                            Log.e(tag, "监听通话结束失败: ${e.message}")
                        }
                    }
                    
                    // 播放音频（会在通话结束时被stopAudioPlayback停止）
                    try {
                        playAudio(context, currentAudioPath)
                    } catch (e: Exception) {
                        Log.e(tag, "播放音频失败: ${e.message}")
                    }
                    
                    // 等待通话结束
                    disconnectJob.join()
                    
                    _currentStatus.value = "通话已结束"
                } else if (!connected) {
                    _currentStatus.value = "未接通，跳过播放"
                } else {
                    _currentStatus.value = "无语音文件，跳过播放"
                }
                
                // 记录通话结果
                val status = if (connected && callSuccess) "成功" else "失败"
                records.add(CallRecord(
                    phoneNumber = entry.phoneNumber,
                    contactName = entry.contactName.ifEmpty { "未知" },
                    callStatus = status,
                    callDuration = duration,
                    timestamp = timestamp
                ))
                
                // 每个电话之间间隔3秒
                delay(3000)
            }
            
            // 取消注册监听器
            callStateListener?.unregister()
            callStateListener = null
            
            // 保存记录并生成统计
            _callRecords.value = records
            generateStatistics(records)
            
            _currentStatus.value = "全部拨打完成"
            _isRunning.value = false
        }
    }
    
    /**
     * 等待电话接通
     * 最多等待30秒
     */
    private suspend fun waitForCallConnect(): Boolean {
        val listener = callStateListener ?: return false
        
        // 使用CompletableDeferred来等待结果
        val result = kotlinx.coroutines.CompletableDeferred<Boolean>()
        
        // 启动协程收集状态
        val job = viewModelScope.launch {
            listener.callStateFlow.collect { state ->
                when (state) {
                    CallStateListener.CallState.CONNECTED -> {
                        if (!result.isCompleted) {
                            result.complete(true)
                        }
                    }
                    CallStateListener.CallState.DISCONNECTED -> {
                        if (!result.isCompleted) {
                            result.complete(false)
                        }
                    }
                    else -> {}
                }
            }
        }
        
        // 等待结果或超时
        return try {
            withTimeoutOrNull(30000) {
                result.await()
            } ?: false
        } finally {
            job.cancel()
        }
    }
    
    /**
     * 等待电话挂断
     * 最多等待5分钟
     */
    private suspend fun waitForCallDisconnect() {
        val listener = callStateListener ?: return
        
        val result = kotlinx.coroutines.CompletableDeferred<Unit>()
        
        // 先等待OFFHOOK状态（确保通话已开始）
        var isInCall = false
        
        val job = viewModelScope.launch {
            listener.callStateFlow.collect { state ->
                Log.d(tag, "电话状态: $state")
                when (state) {
                    CallStateListener.CallState.CONNECTED -> {
                        isInCall = true
                    }
                    CallStateListener.CallState.DISCONNECTED -> {
                        // 只有在通话中收到DISCONNECTED才停止
                        if (isInCall && !result.isCompleted) {
                            Log.d(tag, "检测到通话结束，停止音频")
                            stopAudioPlayback()
                            result.complete(Unit)
                        }
                    }
                    else -> {}
                }
            }
        }
        
        try {
            withTimeoutOrNull(300000) {
                result.await()
            }
        } finally {
            job.cancel()
        }
    }
    
    /**
     * 停止音频播放
     */
    private fun stopAudioPlayback() {
        currentMediaPlayer?.let {
            try {
                if (it.isPlaying) {
                    it.stop()
                }
                it.release()
                Log.d(tag, "音频播放已停止")
            } catch (e: Exception) {
                Log.e(tag, "停止音频失败: ${e.message}")
            } finally {
                currentMediaPlayer = null
            }
        }
    }
    
    /**
     * 生成统计信息
     */
    private fun generateStatistics(records: List<CallRecord>) {
        val total = records.size
        val success = records.count { it.callStatus == "成功" }
        val failed = total - success
        val totalDuration = records.sumOf { it.callDuration }
        
        _statistics.value = "总计: $total | 成功: $success | 失败: $failed | 总时长: ${totalDuration}秒"
        _currentStatus.value = "拨打完成 - $_statistics.value"
    }

    /**
     * 停止自动拨打
     */
    fun stopAutoCall() {
        _isRunning.value = false
        _currentStatus.value = "已停止"
    }

    /**
     * 拨打电话
     */
    private fun makeCall(context: Context, phoneNumber: String): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse("tel:$phoneNumber")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Log.d(tag, "正在拨打: $phoneNumber")
            true
        } catch (e: Exception) {
            Log.e(tag, "拨打电话失败: ${e.message}", e)
            _currentStatus.value = "拨打失败: ${e.message}"
            false
        }
    }

    /**
     * 播放语音文件（通过扬声器播放，让对方听到）
     */
    private suspend fun playAudio(context: Context, audioPath: String) {
        var mediaPlayer: MediaPlayer? = null
        try {
            val file = File(audioPath)
            if (!file.exists()) {
                Log.w(tag, "语音文件不存在: $audioPath")
                _currentStatus.value = "语音文件不存在"
                return
            }

            // 设置音频模式为通话模式，让对方能听到
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            val originalMode = audioManager.mode
            audioManager.mode = android.media.AudioManager.MODE_IN_CALL
            audioManager.setSpeakerphoneOn(true)

            mediaPlayer = MediaPlayer().apply {
                setDataSource(audioPath)
                val audioAttributes = android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                setAudioAttributes(audioAttributes)
                prepare()
                start()
            }

            Log.d(tag, "开始播放语音: $audioPath")
            _currentStatus.value = "正在播放语音..."

            // 等待播放完成
            while (mediaPlayer?.isPlaying == true) {
                delay(100)
            }

            Log.d(tag, "语音播放完成")
            
            // 恢复原始音频模式
            audioManager.mode = originalMode
            
            // 清除引用
            currentMediaPlayer = null
        } catch (e: Exception) {
            Log.e(tag, "播放语音失败: ${e.message}", e)
            _currentStatus.value = "语音播放失败"
        } finally {
            mediaPlayer?.release()
        }
    }
    
    /**
     * 导出通话记录到指定URI（用户选择位置）
     */
    fun exportCallRecordsToUri(context: Context, uri: android.net.Uri) {
        viewModelScope.launch {
            try {
                val records = _callRecords.value
                if (records.isEmpty()) {
                    _currentStatus.value = "没有可导出的记录"
                    return@launch
                }
                
                // 写入CSV到URI
                context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                    // 写入UTF-8 BOM，防止Windows乱码
                    outputStream.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
                    
                    val writer = java.io.OutputStreamWriter(outputStream, Charsets.UTF_8)
                    
                    // 写入标题
                    writer.write("电话号码,联系人姓名,通话状态,通话时长(秒),通话时间\n")
                    
                    // 写入数据
                    records.forEach { record ->
                        writer.write("${record.phoneNumber},${record.contactName},${record.callStatus},${record.callDuration},${record.timestamp}\n")
                    }
                    
                    // 写入统计信息
                    writer.write("\n统计信息\n")
                    writer.write("$_statistics.value\n")
                    
                    writer.flush()
                }
                
                _currentStatus.value = "导出成功"
                Log.d(tag, "通话记录已导出到: $uri")
            } catch (e: Exception) {
                Log.e(tag, "导出失败: ${e.message}", e)
                _currentStatus.value = "导出失败: ${e.message}"
            }
        }
    }
}
