package com.example.autocall

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.autocall.audio.AudioInjector
import com.example.autocall.audio.ChannelProbe
import kotlinx.coroutines.*
import java.io.File

class AutoCallAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "AutoCallAccessibility"

        /** ★ Task 4.2：节点遍历耗时上限（避免卡住无障碍主线程） */
        private const val MAX_SEARCH_MS = 200L
        /** ★ Task 4.2：递归深度上限 */
        private const val MAX_DEPTH = 40

        @Volatile var isRecordingRequested: Boolean = false
        @Volatile var isRecordingActive: Boolean = false
        @Volatile var currentPhoneNumber: String = ""
        @Volatile private var stopRequested: Boolean = false

        // 自动拨号相关状态
        @Volatile var isAutoDialActive: Boolean = false
        @Volatile var currentDialNumber: String = ""
        @Volatile var isAutoAnswerEnabled: Boolean = false

        // 音频注入相关状态
        @Volatile var isAudioInjectionRequested: Boolean = false
        @Volatile var isAudioInjectionActive: Boolean = false
        @Volatile var audioInjectionFilePath: String = ""
        @Volatile var isAccessibilityRecordingRequested: Boolean = false
        @Volatile var isAccessibilityRecordingActive: Boolean = false

        // ====== 新增功能状态 ======
        // 自动挂断
        @Volatile var isAutoHangupEnabled: Boolean = false
        @Volatile private var autoHangupDelayMs: Long = 0 // 0表示不自动挂断

        // 扬声器控制
        @Volatile var isSpeakerphoneAutoEnabled: Boolean = false

        // 自动接听延迟（毫秒）
        @Volatile var autoAnswerDelayMs: Long = 0

        // DTMF发送
        @Volatile var pendingDtmfTones: String = ""
        @Volatile var isDtmfSendingActive: Boolean = false

        // 拨号重试
        @Volatile var dialRetryCount: Int = 0
        @Volatile private var currentRetryAttempt: Int = 0

        @Volatile private var callState: CallAccessibilityState = CallAccessibilityState.IDLE

        private var instance: AutoCallAccessibilityService? = null
        private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        private var injectionJob: Job? = null
        private var autoAnswerJob: Job? = null
        private var hangupJob: Job? = null
        private val mainHandler = Handler(Looper.getMainLooper())

        /**
         * 请求发送DTMF音
         */
        internal fun requestSendDtmf(tones: String) {
            pendingDtmfTones = tones
            instance?.trySendDtmf()
        }

        /**
         * 获取当前无障碍通话状态
         */
        fun getCallAccessibilityState(): CallAccessibilityState = callState

        fun isServiceEnabled(context: android.content.Context): Boolean {
            val serviceName = "${context.packageName}/.AutoCallAccessibilityService"
            val enabledServices = android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return enabledServices.contains(serviceName)
        }

        fun openAccessibilitySettings(context: android.content.Context) {
            val intent = Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }

        /**
         * ★ Task 4.4：诊断当前拨号器界面能否找到各"通话中按键"（录音/免提/接听/挂断）。
         * 返回 key -> 是否找到。仅在无障碍服务已启用且存在通话界面时有意义。
         */
        fun diagnose(context: android.content.Context): Map<String, Boolean> {
            val svc = instance
            return listOf("recordStart", "speaker", "answer", "hangup")
                .associateWith { key -> svc?.probeButton(key) ?: false }
        }

        /** ★ Task 4.4：当前关键词表（供诊断页展示命中项）。 */
        fun keywordSnapshot(context: android.content.Context): Map<String, KeywordSet> =
            IncallKeywordTable.load(context)
    }

    /**
     * 无障碍通话状态枚举
     */
    enum class CallAccessibilityState {
        IDLE,           // 空闲
        DIALING,        // 正在拨号
        RINGING,        // 响铃中
        IN_CALL,        // 通话中
        INCOMING_CALL   // 来电
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        // 增强服务配置：监听更多事件类型，支持视图ID报告
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 100
        }
        serviceInfo = info
        Log.d(TAG, LanguageManager.getString("accessibility.service_connected"))
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        when (event?.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val className = event.className?.toString() ?: ""
                handleWindowStateChange(className)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // 录音检测
                if (isRecordingRequested && !isRecordingActive && !stopRequested) {
                    tryStartRecording()
                }
                // 拨号状态检测
                if (isAutoDialActive && callState == CallAccessibilityState.DIALING) {
                    checkCallConnected()
                }
                // 来电自动接听检测
                if (callState == CallAccessibilityState.INCOMING_CALL && isAutoAnswerEnabled) {
                    tryAutoAnswer()
                }
                // 通话中自动挂断由 scheduleAutoHangup 定时任务处理，此处无需额外操作
            }
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
                val className = event.className?.toString() ?: ""
                if (isIncomingCallScreen(className)) {
                    callState = CallAccessibilityState.INCOMING_CALL
                    Log.d(TAG, LanguageManager.getString("log.accessibility_incoming_call_detected"))
                    if (isAutoAnswerEnabled) {
                        tryAutoAnswer()
                    }
                }
            }
        }
    }

    private fun handleWindowStateChange(className: String) {
        when {
            isIncomingCallScreen(className) -> {
                callState = CallAccessibilityState.INCOMING_CALL
                Log.d(TAG, LanguageManager.getString("log.accessibility_incoming_call_detected"))
                if (isAutoAnswerEnabled) {
                    tryAutoAnswer()
                }
            }
            isDialerScreen(className) -> {
                if (isAutoDialActive) {
                    callState = CallAccessibilityState.DIALING
                    Log.d(TAG, LanguageManager.getString("log.accessibility_dialer_detected"))
                    performAutoDial()
                }
            }
            isInCallScreen(className) -> {
                val previousState = callState
                callState = CallAccessibilityState.IN_CALL
                if (previousState != CallAccessibilityState.IN_CALL) {
                    Log.d(TAG, LanguageManager.getString("log.accessibility_in_call_detected"))
                }
                // 通话接通后，自动触发音频注入和录音
                if (isRecordingRequested && !isRecordingActive) {
                    tryStartRecording()
                }
                if (isAudioInjectionRequested && !isAudioInjectionActive) {
                    tryStartAudioInjection()
                }
                // 通话接通后，自动开启扬声器
                if (isSpeakerphoneAutoEnabled) {
                    enableSpeakerphone(true)
                }
                // 通话接通后，发送DTMF
                if (pendingDtmfTones.isNotEmpty() && !isDtmfSendingActive) {
                    trySendDtmf()
                }
                // 通话接通后，触发无障碍录音
                if (isAccessibilityRecordingRequested && !isAccessibilityRecordingActive) {
                    tryStartAccessibilityRecording()
                }
                // 通话接通后，安排自动挂断
                if (isAutoHangupEnabled && autoHangupDelayMs > 0) {
                    scheduleAutoHangup()
                }
            }
            isHomeOrRecentsScreen(className) -> {
                if (callState == CallAccessibilityState.IN_CALL ||
                    callState == CallAccessibilityState.DIALING) {
                    onCallEnded()
                }
            }
            isCallEndedScreen(className) -> {
                // 新增：专门的通话结束界面检测
                onCallEnded()
            }
        }
    }

    /**
     * 通话结束处理
     */
    private fun onCallEnded() {
        callState = CallAccessibilityState.IDLE
        isAutoDialActive = false
        currentRetryAttempt = 0
        // 取消自动挂断任务
        hangupJob?.cancel()
        hangupJob = null
        // 取消自动接听任务
        autoAnswerJob?.cancel()
        autoAnswerJob = null
        // 通话结束，自动停止音频注入和录音
        tryStopAudioInjection()
        tryStopAccessibilityRecording()
        // 恢复扬声器状态
        if (isSpeakerphoneAutoEnabled) {
            enableSpeakerphone(false)
        }
        Log.d(TAG, LanguageManager.getString("log.accessibility_call_ended_detected"))
    }

    override fun onInterrupt() {
        Log.d(TAG, LanguageManager.getString("accessibility.service_interrupted"))
    }

    override fun onDestroy() {
        if (instance == this) instance = null
        isRecordingRequested = false
        isRecordingActive = false
        stopRequested = false
        isAutoDialActive = false
        isAutoAnswerEnabled = false
        isAudioInjectionRequested = false
        isAudioInjectionActive = false
        isAccessibilityRecordingRequested = false
        isAccessibilityRecordingActive = false
        isAutoHangupEnabled = false
        isSpeakerphoneAutoEnabled = false
        pendingDtmfTones = ""
        isDtmfSendingActive = false
        dialRetryCount = 0
        currentRetryAttempt = 0
        injectionJob?.cancel()
        autoAnswerJob?.cancel()
        hangupJob?.cancel()
        serviceScope.cancel()
        callState = CallAccessibilityState.IDLE
        super.onDestroy()
        Log.d(TAG, LanguageManager.getString("accessibility.service_destroyed"))
    }

    // ==================== 自动拨号 ====================

    private fun performAutoDial() {
        if (!isAutoDialActive || currentDialNumber.isEmpty()) return

        val rootNode = rootInActiveWindow ?: run {
            Log.w(TAG, "rootInActiveWindow is null, trying direct intent dial")
            directDial(currentDialNumber)
            return
        }

        // 尝试查找并点击拨号/呼叫按钮
        val dialKeywords = listOf(
            "拨打", "呼叫", "call", "dial", "拨打电话",
            "place call", "Call", "Dial", "拨号", "呼叫",
            "start call", "make call", "呼出"
        )

        if (findAndClickButton(rootNode, dialKeywords)) {
            callState = CallAccessibilityState.DIALING
            Log.d(TAG, LanguageManager.getString("log.accessibility_dial_button_clicked"))
            return
        }

        // 尝试在拨号盘的输入框中输入号码
        val numberEntered = enterPhoneNumber(rootNode, currentDialNumber)
        if (numberEntered) {
            Log.d(TAG, LanguageManager.getString("log.accessibility_number_entered", currentDialNumber))
            // 输入号码后，尝试点击拨号按钮
            mainHandler.postDelayed({
                val rootNode2 = rootInActiveWindow
                if (rootNode2 != null) {
                    findAndClickButton(rootNode2, dialKeywords)
                }
            }, 500)
        } else {
            Log.d(TAG, LanguageManager.getString("log.accessibility_enter_number_failed"))
            directDial(currentDialNumber)
        }
    }

    /**
     * 带重试的自动拨号
     */
    private fun performAutoDialWithRetry() {
        if (!isAutoDialActive || currentDialNumber.isEmpty()) return

        val rootNode = rootInActiveWindow ?: run {
            Log.w(TAG, "rootInActiveWindow is null, trying direct intent dial")
            directDialWithRetry(currentDialNumber)
            return
        }

        // 尝试查找并点击拨号/呼叫按钮
        val dialKeywords = listOf(
            "拨打", "呼叫", "call", "dial", "拨打电话",
            "place call", "Call", "Dial", "拨号", "呼出"
        )

        if (findAndClickButton(rootNode, dialKeywords)) {
            callState = CallAccessibilityState.DIALING
            Log.d(TAG, LanguageManager.getString("log.accessibility_dial_button_clicked"))
            return
        }

        // 尝试在拨号盘的输入框中输入号码
        val numberEntered = enterPhoneNumber(rootNode, currentDialNumber)
        if (numberEntered) {
            Log.d(TAG, LanguageManager.getString("log.accessibility_number_entered", currentDialNumber))
            mainHandler.postDelayed({
                val rootNode2 = rootInActiveWindow
                if (rootNode2 != null) {
                    findAndClickButton(rootNode2, dialKeywords)
                }
            }, 500)
        } else {
            Log.d(TAG, LanguageManager.getString("log.accessibility_enter_number_failed"))
            directDialWithRetry(currentDialNumber)
        }
    }

    /**
     * 直接使用Intent拨号（作为备用方案）
     */
    private fun directDial(phoneNumber: String) {
        try {
            val intent = Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse("tel:$phoneNumber")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            callState = CallAccessibilityState.DIALING
            Log.d(TAG, LanguageManager.getString("log.accessibility_direct_dial", phoneNumber))
        } catch (e: Exception) {
            Log.e(TAG, LanguageManager.getString("log.accessibility_direct_dial_failed", e.message ?: ""))
            isAutoDialActive = false
            callState = CallAccessibilityState.IDLE
        }
    }

    /**
     * 带重试的直接拨号
     */
    private fun directDialWithRetry(phoneNumber: String) {
        try {
            val intent = Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse("tel:$phoneNumber")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            callState = CallAccessibilityState.DIALING
            Log.d(TAG, LanguageManager.getString("log.accessibility_direct_dial", phoneNumber))
        } catch (e: Exception) {
            Log.e(TAG, LanguageManager.getString("log.accessibility_direct_dial_failed", e.message ?: ""))
            // 重试逻辑
            if (currentRetryAttempt < dialRetryCount) {
                currentRetryAttempt++
                Log.d(TAG, LanguageManager.getString("log.accessibility_dial_retry", currentRetryAttempt, dialRetryCount))
                mainHandler.postDelayed({
                    if (isAutoDialActive) {
                        directDialWithRetry(phoneNumber)
                    }
                }, 2000L * currentRetryAttempt) // 递增延迟
            } else {
                isAutoDialActive = false
                callState = CallAccessibilityState.IDLE
                Log.e(TAG, LanguageManager.getString("log.accessibility_dial_retry_exhausted", dialRetryCount))
            }
        }
    }

    /**
     * 在拨号盘的输入框中输入电话号码
     */
    private fun enterPhoneNumber(rootNode: AccessibilityNodeInfo, phoneNumber: String): Boolean {
        val inputNode = findInputNode(rootNode) ?: return false
        val arguments = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                phoneNumber
            )
        }
        return inputNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }

    /**
     * 递归查找输入框节点
     */
    private fun findInputNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isEditable || node.className?.toString()?.contains("EditText") == true) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findInputNode(child)
            if (result != null) return result
        }
        return null
    }

    // ==================== 通话状态检测 ====================

    /**
     * 检测通话是否已接通（通过UI内容变化判断）
     */
    private fun checkCallConnected() {
        val rootNode = rootInActiveWindow ?: return
        val inCallKeywords = listOf(
            "通话中", "已接通", "In call", "Connected", "通话时间",
            "00:", "01:", "02:", "03:", "04:", "05:",
            "Calling", "呼叫中", "通话时长"
        )
        if (containsText(rootNode, inCallKeywords)) {
            callState = CallAccessibilityState.IN_CALL
            Log.d(TAG, LanguageManager.getString("log.accessibility_call_connected_detected"))
            if (isRecordingRequested && !isRecordingActive) {
                tryStartRecording()
            }
            // 通话接通，触发音频注入
            if (isAudioInjectionRequested && !isAudioInjectionActive) {
                tryStartAudioInjection()
            }
            // 通话接通，触发无障碍录音
            if (isAccessibilityRecordingRequested && !isAccessibilityRecordingActive) {
                tryStartAccessibilityRecording()
            }
            // 通话接通，自动开启扬声器
            if (isSpeakerphoneAutoEnabled) {
                enableSpeakerphone(true)
            }
            // 通话接通，发送DTMF
            if (pendingDtmfTones.isNotEmpty() && !isDtmfSendingActive) {
                trySendDtmf()
            }
            // 通话接通，安排自动挂断
            if (isAutoHangupEnabled && autoHangupDelayMs > 0) {
                scheduleAutoHangup()
            }
        }
    }

    // ==================== 自动接听 ====================

    /**
     * 尝试自动接听来电（支持延迟接听）
     */
    private fun tryAutoAnswer() {
        if (!isAutoAnswerEnabled) return

        // 如果设置了延迟，使用延迟接听
        if (autoAnswerDelayMs > 0) {
            autoAnswerJob?.cancel()
            autoAnswerJob = serviceScope.launch {
                delay(autoAnswerDelayMs)
                if (isAutoAnswerEnabled && callState == CallAccessibilityState.INCOMING_CALL) {
                    performAutoAnswer()
                }
            }
        } else {
            performAutoAnswer()
        }
    }

    /**
     * 执行自动接听操作
     */
    private fun performAutoAnswer() {
        val rootNode = rootInActiveWindow ?: return

        val kw = IncallKeywordTable.get(this, "answer")
        if (findAndClickButton(rootNode, kw.text, kw.excludeText, kw.preferViewIds)) {
            callState = CallAccessibilityState.IN_CALL
            Log.d(TAG, LanguageManager.getString("log.accessibility_auto_answered"))
            // 自动接听后，延迟触发音频注入和录音
            serviceScope.launch {
                delay(2000) // 等待通话通道建立
                if (isAudioInjectionRequested && !isAudioInjectionActive) {
                    tryStartAudioInjection()
                }
                if (isAccessibilityRecordingRequested && !isAccessibilityRecordingActive) {
                    tryStartAccessibilityRecording()
                }
                // 自动开启扬声器
                if (isSpeakerphoneAutoEnabled) {
                    enableSpeakerphone(true)
                }
                // 发送DTMF
                if (pendingDtmfTones.isNotEmpty() && !isDtmfSendingActive) {
                    trySendDtmf()
                }
                // 安排自动挂断
                if (isAutoHangupEnabled && autoHangupDelayMs > 0) {
                    scheduleAutoHangup()
                }
            }
        }
    }

    // ==================== 自动挂断 ====================

    /**
     * 安排自动挂断
     */
    private fun scheduleAutoHangup() {
        hangupJob?.cancel()
        hangupJob = serviceScope.launch {
            delay(autoHangupDelayMs)
            if (callState == CallAccessibilityState.IN_CALL && isAutoHangupEnabled) {
                performAutoHangup()
            }
        }
    }

    /**
     * 执行自动挂断
     */
    private fun performAutoHangup() {
        val rootNode = rootInActiveWindow ?: run {
            // 如果无法获取根节点，尝试使用全局返回操作
            performGlobalAction(GLOBAL_ACTION_BACK)
            return
        }

        val kw = IncallKeywordTable.get(this, "hangup")
        if (findAndClickButton(rootNode, kw.text, kw.excludeText, kw.preferViewIds)) {
            Log.d(TAG, LanguageManager.getString("log.accessibility_auto_hangup_clicked"))
            onCallEnded()
        } else {
            // 如果找不到挂断按钮，尝试全局返回操作
            Log.w(TAG, LanguageManager.getString("log.accessibility_hangup_button_not_found"))
            performGlobalAction(GLOBAL_ACTION_BACK)
        }
    }

    // ==================== DTMF音发送 ====================

    /**
     * 尝试发送DTMF音
     */
    private fun trySendDtmf() {
        if (pendingDtmfTones.isEmpty()) return
        if (callState != CallAccessibilityState.IN_CALL) return
        if (isDtmfSendingActive) return

        isDtmfSendingActive = true
        val tones = pendingDtmfTones
        pendingDtmfTones = "" // 清空，防止重复发送

        serviceScope.launch {
            var toneGenerator: ToneGenerator? = null
            try {
                // 使用ToneGenerator发送DTMF音（通过语音通道）
                toneGenerator = ToneGenerator(AudioManager.STREAM_VOICE_CALL, 100)
                if (toneGenerator == null) {
                    Log.e(TAG, "Cannot create ToneGenerator for DTMF")
                    return@launch
                }

                Log.d(TAG, "Sending DTMF tones: $tones")
                for (char in tones) {
                    val toneType = when (char) {
                        '0' -> ToneGenerator.TONE_DTMF_0
                        '1' -> ToneGenerator.TONE_DTMF_1
                        '2' -> ToneGenerator.TONE_DTMF_2
                        '3' -> ToneGenerator.TONE_DTMF_3
                        '4' -> ToneGenerator.TONE_DTMF_4
                        '5' -> ToneGenerator.TONE_DTMF_5
                        '6' -> ToneGenerator.TONE_DTMF_6
                        '7' -> ToneGenerator.TONE_DTMF_7
                        '8' -> ToneGenerator.TONE_DTMF_8
                        '9' -> ToneGenerator.TONE_DTMF_9
                        '*' -> ToneGenerator.TONE_DTMF_S
                        '#' -> ToneGenerator.TONE_DTMF_P
                        'A', 'a' -> ToneGenerator.TONE_DTMF_A
                        'B', 'b' -> ToneGenerator.TONE_DTMF_B
                        'C', 'c' -> ToneGenerator.TONE_DTMF_C
                        'D', 'd' -> ToneGenerator.TONE_DTMF_D
                        else -> continue
                    }
                    toneGenerator.startTone(toneType, 300) // 每个音300ms
                    delay(400) // 音之间间隔100ms
                }
                Log.d(TAG, "DTMF tones sent successfully: $tones")
            } catch (e: Exception) {
                Log.e(TAG, "DTMF send exception: ${e.message}")
            } finally {
                toneGenerator?.release()
                isDtmfSendingActive = false
            }
        }
    }

    // ==================== 扬声器控制 ====================

    /**
     * 控制扬声器开关
     */
    private fun enableSpeakerphone(enable: Boolean) {
        try {
            val audioManager = getSystemService(AudioManager::class.java)
            if (audioManager != null) {
                audioManager.isSpeakerphoneOn = enable
                Log.d(TAG, "Speakerphone ${if (enable) "enabled" else "disabled"}")
            } else {
                Log.w(TAG, "Cannot get AudioManager for speakerphone control")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Speakerphone control exception: ${e.message}")
        }
    }

    // ==================== 音频注入控制 ====================

    /**
     * 尝试启动音频注入。
     *
     * ★ 阶段 4：改用新的 [AudioInjector] 抽象 + [ChannelProbe] 通道探测，替换旧的 `CallAudioInjector`。
     */
    private fun tryStartAudioInjection() {
        if (!isAudioInjectionRequested || isAudioInjectionActive) return
        if (callState != CallAccessibilityState.IN_CALL) return
        if (audioInjectionFilePath.isEmpty()) return

        val file = File(audioInjectionFilePath)
        if (!file.exists()) {
            Log.w(TAG, "Audio file not found: $audioInjectionFilePath")
            return
        }

        isAudioInjectionActive = true
        Log.d(TAG, "Starting audio injection: $audioInjectionFilePath")

        injectionJob = serviceScope.launch {
            try {
                val caps = ChannelProbe.probe(this@AutoCallAccessibilityService, isServiceEnabled(this@AutoCallAccessibilityService))
                val injector: AudioInjector = ChannelProbe.injectorFor(this@AutoCallAccessibilityService, caps)
                val result = injector.inject(audioInjectionFilePath)
                Log.d(TAG, "Audio injection completed: $result")
            } catch (e: Exception) {
                Log.e(TAG, "Audio injection exception: ${e.message}")
            } finally {
                isAudioInjectionActive = false
                isAudioInjectionRequested = false
            }
        }
    }

    /**
     * 尝试停止音频注入
     */
    private fun tryStopAudioInjection() {
        if (!isAudioInjectionActive) return
        injectionJob?.cancel()
        injectionJob = null
        isAudioInjectionActive = false
        isAudioInjectionRequested = false
        Log.d(TAG, "Audio injection stopped")
    }

    // ==================== 无障碍录音控制 ====================

    /**
     * 尝试启动无障碍模式录音。
     *
     * ★ 修 B11：无障碍通道**无法注入音频，只能点击系统录音键**。
     * 不再自建 `VOICE_CALL` 录音器（Android 10+ 第三方无 `CAPTURE_AUDIO_OUTPUT`，必然失败/静默空录）；
     * 系统录音件保存在厂商私有目录，App 侧读不到（如实告知见诊断页）。
     */
    private fun tryStartAccessibilityRecording() {
        if (!isAccessibilityRecordingRequested || isAccessibilityRecordingActive) return
        if (callState != CallAccessibilityState.IN_CALL) return

        val rootNode = rootInActiveWindow ?: return
        val kw = IncallKeywordTable.get(this, "recordStart")
        if (findAndClickButton(rootNode, kw.text, kw.excludeText, kw.preferViewIds)) {
            isAccessibilityRecordingActive = true
            Log.d(TAG, "Accessibility recording started via UI click")
        } else {
            Log.w(TAG, "Accessibility record button not found")
        }
    }

    /**
     * 尝试停止无障碍模式录音
     */
    private fun tryStopAccessibilityRecording() {
        if (!isAccessibilityRecordingActive) return
        isAccessibilityRecordingRequested = false
        isAccessibilityRecordingActive = false
        Log.d(TAG, "Accessibility recording stopped by request")
    }

    // ==================== 录音控制（UI按钮点击） ====================

    private fun tryStartRecording() {
        if (!isRecordingRequested || isRecordingActive || stopRequested) return

        val rootNode = rootInActiveWindow ?: run {
            Log.w(TAG, "rootInActiveWindow is null")
            return
        }

        // ★ Task 4.2：从外置关键词表读取（含排除词 + 优先 viewId）
        val kw = IncallKeywordTable.get(this, "recordStart")
        if (findAndClickButton(rootNode, kw.text, kw.excludeText, kw.preferViewIds)) {
            isRecordingActive = true
            Log.d(TAG, LanguageManager.getString("log.accessibility_recording_started"))
        } else {
            Log.d(TAG, LanguageManager.getString("log.accessibility_record_button_not_found"))
        }
    }

    private fun tryStopRecording() {
        if (!stopRequested || !isRecordingActive) return

        val rootNode = rootInActiveWindow ?: return

        val kw = IncallKeywordTable.get(this, "recordStop")
        if (findAndClickButton(rootNode, kw.text, kw.excludeText, kw.preferViewIds)) {
            isRecordingActive = false
            isRecordingRequested = false
            stopRequested = false
            Log.d(TAG, LanguageManager.getString("log.accessibility_recording_stopped"))
        }
    }

    // ==================== 通用UI工具方法 ====================

    /**
     * ★ Task 4.2：递归查找并点击按钮。
     *  - [excludeText]：命中排除词的节点跳过（避免误点"录音设置/录音列表"）；
     *  - [preferViewIds]：优先按资源 ID 精确命中（各 ROM 更稳）；
     *  - 遍历深度与耗时受限（[MAX_DEPTH] / [MAX_SEARCH_MS]），避免卡住无障碍主线程。
     */
    private fun findAndClickButton(
        node: AccessibilityNodeInfo?,
        keywords: List<String>,
        excludeText: List<String> = emptyList(),
        preferViewIds: List<String> = emptyList()
    ): Boolean {
        for (id in preferViewIds) {
            if (id.isNotEmpty() && clickNodeByViewId(id)) return true
        }
        val deadline = System.currentTimeMillis() + MAX_SEARCH_MS
        return searchAndClick(node, keywords.map { it.lowercase() }, excludeText.map { it.lowercase() }, deadline, 0)
    }

    private fun searchAndClick(
        node: AccessibilityNodeInfo?,
        keywords: List<String>,
        excludeText: List<String>,
        deadline: Long,
        depth: Int
    ): Boolean {
        if (node == null || System.currentTimeMillis() > deadline || depth > MAX_DEPTH) return false

        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        val text = node.text?.toString()?.lowercase() ?: ""
        val combined = "$desc $text"
        val excluded = excludeText.any { it.isNotEmpty() && combined.contains(it) }
        if (!excluded && keywords.any { it.isNotEmpty() && combined.contains(it) }) {
            if (clickSelfOrAncestor(node)) return true
        }

        for (i in 0 until node.childCount) {
            if (searchAndClick(node.getChild(i), keywords, excludeText, deadline, depth + 1)) return true
        }
        return false
    }

    /** 点击节点自身（若可点），否则向上找最近的可点击祖先。 */
    private fun clickSelfOrAncestor(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable) {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            return true
        }
        var parent = node.parent
        while (parent != null) {
            if (parent.isClickable) {
                parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return true
            }
            parent = parent.parent
        }
        return false
    }

    /** 按资源 ID 精确查找并点击（★ Task 4.2）。 */
    private fun clickNodeByViewId(targetId: String): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        val nodes = rootNode.findAccessibilityNodeInfosByViewId(targetId)
        if (nodes.isNullOrEmpty()) return false
        for (n in nodes) if (clickSelfOrAncestor(n)) return true
        return false
    }

    /** ★ Task 4.4：探测当前界面是否存在对应按键（不点击）。 */
    private fun probeButton(key: String): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        val kw = IncallKeywordTable.get(this, key)
        return containsAny(rootNode, kw.text, kw.excludeText)
    }

    /** 递归判断节点树是否含关键词（支持排除词，不点击）。 */
    private fun containsAny(node: AccessibilityNodeInfo?, keywords: List<String>, excludeText: List<String>): Boolean {
        if (node == null) return false
        val combined = "${node.contentDescription?.toString()?.lowercase() ?: ""} ${node.text?.toString()?.lowercase() ?: ""}"
        val excluded = excludeText.any { it.isNotEmpty() && combined.contains(it.lowercase()) }
        if (!excluded && keywords.any { it.isNotEmpty() && combined.contains(it.lowercase()) }) return true
        for (i in 0 until node.childCount) {
            if (containsAny(node.getChild(i), keywords, excludeText)) return true
        }
        return false
    }

    /**
     * 通过资源ID查找节点并点击
     */
    @Suppress("unused")
    private fun findAndClickByViewId(targetId: String): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        val nodes = rootNode.findAccessibilityNodeInfosByViewId(targetId)
        if (nodes != null && nodes.isNotEmpty()) {
            for (node in nodes) {
                if (node.isClickable) {
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    return true
                }
                var parent = node.parent
                while (parent != null) {
                    if (parent.isClickable) {
                        parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        return true
                    }
                    parent = parent.parent
                }
            }
        }
        return false
    }

    /**
     * 递归检查节点树中是否包含指定关键词
     */
    private fun containsText(node: AccessibilityNodeInfo?, keywords: List<String>): Boolean {
        if (node == null) return false

        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        val text = node.text?.toString()?.lowercase() ?: ""

        for (keyword in keywords) {
            val kw = keyword.lowercase()
            if (desc.contains(kw) || text.contains(kw)) return true
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (containsText(child, keywords)) return true
        }

        return false
    }

    // ==================== 屏幕类型识别（增强版） ====================

    /**
     * 来电界面检测 - 覆盖主流厂商
     */
    private fun isIncomingCallScreen(className: String): Boolean {
        val keywords = listOf(
            // 通用/AOSP
            "InCallActivity", "IncomingCall", "CallScreen",
            "TelephonyActivity", "PhoneApp",
            // 三星 Samsung
            "InCallService", "CallActivity",
            // 小米 MIUI/HyperOS
            "IncallActivity", "DialerActivity",
            // 华为 EMUI/HarmonyOS
            "PhoneCallActivity", "InCallUI",
            // OPPO ColorOS
            "CallActivity", "OppoCallActivity",
            // Vivo OriginOS
            "VivoCallActivity", "InCallActivity",
            // 联想/摩托罗拉
            "MotoCallActivity", "InCallActivity"
        )
        return keywords.any { className.contains(it, ignoreCase = true) }
    }

    /**
     * 拨号界面检测 - 覆盖主流厂商
     */
    private fun isDialerScreen(className: String): Boolean {
        val keywords = listOf(
            // 通用/AOSP
            "DialerActivity", "DialtactsActivity", "TwelveKeyDialer",
            "DialpadFragment", "PhoneDialer",
            // 三星
            "DialerActivity", "SmartDialerActivity",
            // 小米
            "DialerActivity", "MiuiDialerActivity",
            // 华为
            "DialerActivity", "TwtDialerActivity",
            // OPPO
            "ColorDialerActivity", "DialpadActivity",
            // Vivo
            "VivoDialerActivity", "DialpadActivity",
            // 通用拨号
            "Dialpad", "Dialer", "TwtDialer"
        )
        return keywords.any { className.contains(it, ignoreCase = true) }
    }

    /**
     * 通话中界面检测 - 覆盖主流厂商
     */
    private fun isInCallScreen(className: String): Boolean {
        val keywords = listOf(
            // 通用/AOSP
            "InCallActivity", "InCallUI", "PhoneGlobals",
            "CallActivity", "InCallScreen",
            // 三星
            "InCallService", "CallActivity",
            // 小米
            "IncallActivity", "MiuiInCallActivity",
            // 华为
            "PhoneCallActivity", "InCallUI",
            // OPPO
            "OppoCallActivity", "InCallActivity",
            // Vivo
            "VivoCallActivity", "InCallActivity",
            // 摩托罗拉
            "MotoCallActivity", "InCallActivity"
        )
        return keywords.any { className.contains(it, ignoreCase = true) }
    }

    /**
     * 通话结束界面检测（新增）
     */
    private fun isCallEndedScreen(className: String): Boolean {
        val keywords = listOf(
            "CallEnded", "CallFinish", "CallLog",
            "LastCallResult", "CallHistory",
            "CallDetailActivity", "CallLogActivity"
        )
        return keywords.any { className.contains(it, ignoreCase = true) }
    }

    /**
     * 桌面/最近任务界面检测
     */
    private fun isHomeOrRecentsScreen(className: String): Boolean {
        val keywords = listOf(
            "Launcher", "HomeActivity", "RecentsActivity",
            "RecentApps", "Desktop",
            // 各厂商桌面
            "MiuiLauncher", "SamsungLauncher", "HuaweiLauncher",
            "OppoLauncher", "VivoLauncher", "NovaLauncher",
            // 最近任务
            "Recents", "Overview"
        )
        return keywords.any { className.contains(it, ignoreCase = true) }
    }
}
