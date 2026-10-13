package com.example.autocall

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.example.autocall.audio.AudioInjector
import com.example.autocall.audio.AudioSession
import com.example.autocall.audio.Capabilities
import com.example.autocall.audio.ChannelProbe
import com.example.autocall.audio.RecordChannel
import com.example.autocall.record.CallDirection
import com.example.autocall.record.CallRecorder
import com.example.autocall.record.CallResult
import com.example.autocall.record.RecordingIndex
import com.example.autocall.record.RecordingNaming
import com.example.autocall.record.RecordingStore
import com.example.autocall.record.StoreRequest
import com.example.autocall.service.CallRecordingService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale

/**
 * ★ Task 3.1：拨号主循环的宿主接口。
 *
 * 由 [AutoCallViewModel] 用**匿名对象**实现（因此可实现类仍能访问自身的私有成员），
 * 从而把循环搬出 ViewModel，同时不破坏封装。
 */
interface PhoneCallHost {
    // —— 读取 ——
    fun phoneList(): List<PhoneEntry>
    fun simCardMode(): Int
    fun currentSimCard(): Int
    fun callIntervalMs(): Long
    fun isRecordingEnabled(): Boolean
    fun isAudioPlaybackEnabled(): Boolean
    fun isAccessibilityEnabled(): Boolean
    fun dialRetryCount(): Int
    fun isRunning(): Boolean
    fun isPaused(): Boolean
    fun existingRecords(): List<CallRecord>
    fun resolveAudioPath(entry: PhoneEntry): String?
    fun lastCompletedIndex(): Int

    // —— 写入 ——
    fun setCurrentSimCard(slot: Int)
    fun setRunning(running: Boolean)
    fun setPaused(paused: Boolean)
    fun updateStatus(status: String)
    fun setProgress(value: Int)
    fun setCurrentIndex(index: Int)
    fun setCapabilities(caps: Capabilities)
    fun setRecords(records: List<CallRecord>)
    fun markAsCalled(index: Int)
    fun saveData()
    fun generateStatistics(records: List<CallRecord>)
    fun setLastCompletedIndex(index: Int)
}

/**
 * ★ Task 3.1 / 3.2：拨号主循环。
 *
 * - 循环 `Job` 由本控制器持有，[stop] 可真正取消（修 B22）；
 * - 收尾（写记录 / 落盘 / 复位状态）包在 `withContext(NonCancellable)` 中，
 *   任何暂停 / 停止 / 取消 / 异常路径下都会执行（修 B23/B24）；
 * - 记录 `callDuration` 为**真实通话时长**（接通→挂断，修 B39）。
 */
class PhoneCallController(
    private val application: Application,
    private val host: PhoneCallHost
) {
    private val tag = "PhoneCallController"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    private var callStateListener: CallStateListener? = null
    private var audioInjector: AudioInjector? = null
    private var callRecorder: CallRecorder? = null

    @Volatile private var currentProcessingIndex: Int = -1
    @Volatile private var isCallInProgress: Boolean = false

    fun isRunning(): Boolean = job?.isActive == true

    /** 启动（或从 [startIndex] 续拨）。 */
    fun start(context: Context, startIndex: Int) {
        if (host.isRunning() && !host.isPaused()) return
        job?.cancel()                       // 先取消可能残留的旧循环，避免记录串号（修 B22）
        job = scope.launch(Dispatchers.Main) {
            host.setRunning(true)
            host.setPaused(false)
            val list = host.phoneList()
            if (list.isEmpty()) {
                host.updateStatus(LanguageManager.getString("status.phone_list_empty"))
                host.setRunning(false)
                return@launch
            }

            callStateListener?.close()
            callStateListener = CallStateListener(application).also { it.register() }

            val newRecords = mutableListOf<CallRecord>()
            host.updateStatus(LanguageManager.getString("status.auto_call_started", list.size, startIndex + 1))
            try {
                for ((index, entry) in list.withIndex()) {
                    if (index < startIndex) continue

                    if (!host.isRunning()) break
                    if (host.isPaused()) {
                        host.updateStatus(LanguageManager.getString("status.paused_at", index + 1, list.size))
                        while (host.isPaused() && host.isRunning()) delay(500L)
                        if (!host.isRunning()) break
                    }

                    dialOne(context, index, entry, list.size, newRecords)

                    host.setLastCompletedIndex(index)   // ★ Task 3.4：记录断点
                    host.saveData()                     // 每通结束即落盘（修 B24）
                    markCalledAndReset(index)

                    delay(host.callIntervalMs())
                    waitForIdleState()
                }

                // ★ Task 3.5：失败重拨队列 —— 一轮结束后对仍非 OK 的号码统一重试
                val maxRetry = host.dialRetryCount()
                var round = 0
                var pending = failedEntries(list, newRecords)
                while (maxRetry > 0 && round < maxRetry && pending.isNotEmpty() && host.isRunning()) {
                    round++
                    host.updateStatus(LanguageManager.getString("status.retrying", pending.size, round, maxRetry))
                    for ((retryIndex, retryEntry) in pending) {
                        if (!host.isRunning()) break
                        if (host.isPaused()) {
                            while (host.isPaused() && host.isRunning()) delay(500L)
                            if (!host.isRunning()) break
                        }
                        dialOne(context, retryIndex, retryEntry, list.size, newRecords)
                        host.saveData()
                        markCalledAndReset(retryIndex)
                        delay(host.callIntervalMs())
                        waitForIdleState()
                    }
                    pending = failedEntries(list, newRecords)
                }
            } catch (e: CancellationException) {
                Log.d(tag, "loop cancelled, running cleanup")
                throw e
            } finally {
                // ★ 收尾放入 NonCancellable：cancel / 停止 / 异常路径下都会执行
                withContext(NonCancellable) { cleanup(newRecords) }
            }
        }
    }

    /** 完成一整通电话：拨号 → 等待接通 → 录音/注入 → 等待挂断 → 生成记录。 */
    private suspend fun dialOne(
        context: Context,
        index: Int,
        entry: PhoneEntry,
        total: Int,
        outRecords: MutableList<CallRecord>
    ) {
        currentProcessingIndex = index
        isCallInProgress = true

        host.setCurrentIndex(index)
        host.setProgress(index + 1)
        host.updateStatus(LanguageManager.getString("status.dialing", index + 1, total, entry.contactName.ifEmpty { entry.phoneNumber }))
        Log.d(tag, LanguageManager.getString("log.start_dial_index", index, entry.phoneNumber))

        val startTime = System.currentTimeMillis()
        val timestamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            .format(java.util.Date(startTime))

        val audioPath = host.resolveAudioPath(entry)

        val usedSimSlot = when (host.simCardMode()) {
            1 -> 0
            2 -> 1
            3 -> host.currentSimCard()
            else -> 0
        }
        val callSuccess = when (host.simCardMode()) {
            3 -> {
                host.setCurrentSimCard(if (host.currentSimCard() == 0) 1 else 0)
                makeCallWithSimCard(context, entry.phoneNumber, host.currentSimCard())
            }
            1 -> makeCallWithSimCard(context, entry.phoneNumber, 0)
            2 -> makeCallWithSimCard(context, entry.phoneNumber, 1)
            else -> makeCall(context, entry.phoneNumber)
        }

        host.updateStatus(LanguageManager.getString("status.waiting_connect"))
        val connected = waitForCallConnect()

        // ★ Task 3.3（B29）：接通超时后先挂断，避免拨出的电话仍在振铃时就拨下一个
        if (!connected && callSuccess) {
            hangUpCurrentCall()
            waitForIdleState()
        }

        val resultCode = when {
            !callSuccess -> CallResult.FAIL
            connected -> CallResult.OK
            else -> CallResult.NO_ANSWER
        }

        var recordPath: String? = null
        var mediaStoreUri: String? = null
        var recordFileSize = 0L
        var recordMode = "NONE"
        var recordSeq = 0
        var connectedAtMs = 0L
        var endedAtMs = startTime
        var recorderError: String? = null

        if (connected) {
            connectedAtMs = System.currentTimeMillis()
            val caps = ChannelProbe.probe(application, host.isAccessibilityEnabled())
            host.setCapabilities(caps)
            AudioSession.beginCall()
            recordMode = when (caps.record) {
                RecordChannel.ROOT_VOICE_CALL -> "root_voice_call"
                RecordChannel.MIC_LOOPBACK -> "mic_loopback"
                else -> "NONE"
            }
            if (audioInjector == null) {
                audioInjector = ChannelProbe.injectorFor(
                    application, caps,
                    amplitudeSource = { callRecorder?.amplitude() ?: 0 }
                )
            }
            if (callRecorder == null) callRecorder = CallRecorder(application)

            if (host.isRecordingEnabled()) {
                recordSeq = RecordingIndex.next(application)
                val started = callRecorder?.start(caps.record) == true
                if (started) {
                    host.updateStatus(LanguageManager.getString("status.recording_started"))
                    CallRecordingService.start(application)   // ★ Task 3.6：前台服务锚点
                } else {
                    recorderError = callRecorder?.lastError()
                    host.updateStatus(LanguageManager.getString("status.recording_failed"))
                    Log.e(tag, "start recording failed: $recorderError")
                }
                AudioSession.awaitStable(300)
            }

            if (host.isAudioPlaybackEnabled() && !audioPath.isNullOrEmpty()) {
                host.updateStatus(LanguageManager.getString("status.call_connected_playing"))
                val disconnectJob = scope.launch { waitForCallDisconnect(index) }
                val audioJob = scope.launch { audioInjector?.inject(audioPath) }
                disconnectJob.join()
                audioJob.cancel()
                audioInjector?.stop()
            } else {
                host.updateStatus(LanguageManager.getString("status.call_connected_no_audio"))
                waitForCallDisconnect(index)
            }

            endedAtMs = System.currentTimeMillis()
            if (callRecorder?.isRecording() == true) {
                val temp = callRecorder?.stop()
                CallRecordingService.stop(application)   // ★ Task 3.6
                if (temp != null && temp.exists()) {
                    val talkSec = ((endedAtMs - connectedAtMs) / 1000).coerceAtLeast(0)
                    val req = StoreRequest(
                        seq = recordSeq,
                        callId = RecordingNaming.buildCallId(recordSeq, startTime),
                        direction = CallDirection.OUT,
                        simSlot = usedSimSlot,
                        caller = null,
                        callee = entry.phoneNumber,
                        contactName = entry.contactName,
                        accountNumber = entry.accountNumber,
                        balance = entry.balance,
                        startedAtMs = startTime,
                        connectedAtMs = connectedAtMs,
                        endedAtMs = endedAtMs,
                        durationSec = talkSec,
                        result = resultCode,
                        recordMode = recordMode,
                        injectionFile = if (host.isAudioPlaybackEnabled()) audioPath else null
                    )
                    val (privatePath, uri) = RecordingStore.commit(application, temp, req)
                    recordPath = privatePath
                    mediaStoreUri = uri
                    recordFileSize = privatePath?.let { File(it).length() } ?: 0L
                    if (privatePath == null) recordMode = "NONE"
                } else {
                    recorderError = callRecorder?.lastError()
                    recordMode = "NONE"
                }
            }
            host.updateStatus(LanguageManager.getString("status.call_ended"))
        } else {
            endedAtMs = System.currentTimeMillis()
            host.updateStatus(LanguageManager.getString("status.call_not_connected"))
        }

        val talkDurationSec = if (connected) ((endedAtMs - connectedAtMs) / 1000).coerceAtLeast(0) else 0L
        val dialDurationSec = ((endedAtMs - startTime) / 1000).coerceAtLeast(0)
        outRecords.add(
            CallRecord(
                callId = RecordingNaming.buildCallId(recordSeq, startTime),
                seq = recordSeq,
                phoneNumber = entry.phoneNumber,
                contactName = entry.contactName.ifEmpty { LanguageManager.getString("status.unknown") },
                direction = CallDirection.OUT.tag,
                simSlot = usedSimSlot,
                callStatus = resultCode.code,
                resultCode = resultCode.code,
                callDuration = talkDurationSec,
                dialDuration = dialDurationSec,
                timestamp = timestamp,
                connectedAtMs = connectedAtMs,
                recordFilePath = recordPath,
                mediaStoreUri = mediaStoreUri,
                recordMode = recordMode,
                injectionFilePath = if (host.isAudioPlaybackEnabled()) audioPath else null,
                fileSizeBytes = recordFileSize,
                errorMessage = recorderError
            )
        )
    }

    private fun markCalledAndReset(index: Int) {
        host.markAsCalled(index)
        isCallInProgress = false
        currentProcessingIndex = -1
    }

    /** 取当前仍非 OK 的号码（以其最近一次记录的结果判定），返回 (列表索引, 条目)（Task 3.5）。 */
    private fun failedEntries(list: List<PhoneEntry>, records: List<CallRecord>): List<Pair<Int, PhoneEntry>> {
        val latestByNumber = records.groupBy { it.phoneNumber }.mapValues { it.value.last().resultCode }
        return list.withIndex()
            .filter { (_, e) -> latestByNumber.containsKey(e.phoneNumber) && latestByNumber[e.phoneNumber] != CallResult.OK.code }
            .map { it.index to it.value }
    }

    /** 收尾（在 `NonCancellable` 中执行）。 */
    private fun cleanup(newRecords: List<CallRecord>) {
        callStateListener?.close()
        callStateListener = null
        audioInjector?.stop()
        callRecorder?.release()
        CallRecordingService.stop(application)   // ★ Task 3.6
        AudioSession.endCall()

        if (newRecords.isNotEmpty()) {
            host.setRecords((host.existingRecords() + newRecords).distinctBy { it.callId.ifEmpty { it.phoneNumber + it.timestamp } })
            host.generateStatistics(host.existingRecords())
        }
        host.saveData()
        host.updateStatus(LanguageManager.getString("status.all_completed"))
        host.setRunning(false)
        host.setPaused(false)
        isCallInProgress = false
        currentProcessingIndex = -1
    }

    /** 真正停止：取消循环 Job（修 B22）。收尾由循环自身的 `finally` 完成。 */
    fun stop() {
        host.setRunning(false)
        host.setPaused(false)
        isCallInProgress = false
        currentProcessingIndex = -1
        audioInjector?.stop()
        callRecorder?.release()
        AudioSession.endCall()
        callStateListener?.close()
        callStateListener = null
        job?.cancel()
        job = null
    }

    fun pause() {
        if (host.isRunning() && !host.isPaused()) {
            // 仅置暂停位；循环在下一轮检测点会更新“已暂停”状态文案
            host.setPaused(true)
        }
    }

    fun resume() {
        if (host.isRunning() && host.isPaused()) {
            host.setPaused(false)
            host.updateStatus(LanguageManager.getString("status.resuming"))
        }
    }

    /** 用户中途关闭录音开关：停止并丢弃当前临时录音（正常路径由循环 commit 落盘）。 */
    fun cancelRecording() {
        if (callRecorder?.isRecording() == true) {
            callRecorder?.stop()?.delete()
        }
    }

    /** 释放资源（onCleared）。 */
    fun dispose() {
        callStateListener?.close()
        callStateListener = null
        audioInjector?.stop()
        callRecorder?.release()
        job?.cancel()
        job = null
    }

    // ---------- 通话状态 ----------

    private suspend fun waitForCallConnect(): Boolean {
        val listener = callStateListener ?: return false
        val result = CompletableDeferred<Boolean>()
        val collectJob = scope.launch {
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
            collectJob.cancel()
        }
    }

    /** 等待挂断。带 index 守卫，避免消费陈旧事件（修 B28）。 */
    private suspend fun waitForCallDisconnect(expectedIndex: Int) {
        val listener = callStateListener ?: return
        val result = CompletableDeferred<Unit>()
        val collectJob = scope.launch {
            listener.callStateFlow.collect { state ->
                if (currentProcessingIndex != expectedIndex) return@collect
                when (state) {
                    CallStateListener.CallState.DISCONNECTED -> {
                        delay(2000L)   // 确认不是短暂断线重连
                        if (currentProcessingIndex == expectedIndex && isCallInProgress && !result.isCompleted) {
                            audioInjector?.stop()
                            AudioSession.endCall()
                            result.complete(Unit)
                        }
                    }
                    else -> {}
                }
            }
        }
        try {
            // ★ Task 3.3（B30）：上限与自动挂断上限（最长 600s）对齐，避免静默截断长通话
            withTimeoutOrNull(660_000) { result.await() }
        } finally {
            collectJob.cancel()
        }
    }

    private suspend fun waitForIdleState() {
        delay(500L)
        delay(1000L)
    }

    /** ★ Task 3.3（B29）：挂断当前通话（需 ANSWER_PHONE_CALLS）。 */
    @SuppressLint("MissingPermission")
    private fun hangUpCurrentCall() {
        try {
            val telecom = application.getSystemService(android.telecom.TelecomManager::class.java)
            if (telecom != null &&
                application.checkSelfPermission(android.Manifest.permission.ANSWER_PHONE_CALLS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                telecom.endCall()
            }
        } catch (e: Exception) {
            Log.e(tag, "hangUpCurrentCall failed: ${e.message}")
        }
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
            host.updateStatus(LanguageManager.getString("status.dial_failed", e.message ?: ""))
            false
        }
    }

    @SuppressLint("UseKtx")
    private fun makeCallWithSimCard(context: Context, phoneNumber: String, simSlot: Int): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse("tel:$phoneNumber")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    val subscriptionManager = context.getSystemService(android.telephony.SubscriptionManager::class.java)
                    if (subscriptionManager != null &&
                        context.checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) ==
                        android.content.pm.PackageManager.PERMISSION_GRANTED
                    ) {
                        val activeSubscriptions = subscriptionManager.activeSubscriptionInfoList
                        if (!activeSubscriptions.isNullOrEmpty()) {
                            val index = if (simSlot < activeSubscriptions.size) simSlot else 0
                            val subscriptionInfo = activeSubscriptions[index]
                            putExtra("android.telephony.extra.SUBSCRIPTION_INDEX", subscriptionInfo.subscriptionId)
                            putExtra("subscription", subscriptionInfo.subscriptionId)
                        }
                    }
                } catch (e: SecurityException) {
                    putExtra("com.android.phone.force.slot", true)
                    putExtra("Cdma_Supp", true)
                    putExtra("slot", simSlot)
                    putExtra("simSlot", simSlot)
                } catch (e: Exception) {
                    putExtra("com.android.phone.force.slot", true)
                    putExtra("Cdma_Supp", true)
                    putExtra("slot", simSlot)
                    putExtra("simSlot", simSlot)
                }
            }
            context.startActivity(intent)
            host.updateStatus(
                LanguageManager.getString(
                    "sim_card.using_sim",
                    if (simSlot == 0) LanguageManager.getString("sim_card.mode_sim1") else LanguageManager.getString("sim_card.mode_sim2")
                )
            )
            true
        } catch (e: Exception) {
            host.updateStatus(LanguageManager.getString("status.dial_failed", e.message ?: ""))
            false
        }
    }
}
