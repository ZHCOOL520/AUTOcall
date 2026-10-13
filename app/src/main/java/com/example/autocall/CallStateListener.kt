package com.example.autocall

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * 通话状态监听。
 *
 * ★ Task 3.3：Android 12（API 31）起改用 [TelephonyCallback.CallStateListener]（修 B14/B38 的
 * `ACTION_PHONE_STATE_CHANGED` 广播在新系统上的限制与 deprecation）；低版本回退到广播兜底。
 *
 * 通道仍为 `CONFLATED`（只关心最新状态）；"消费陈旧事件"由调用方
 * （[PhoneCallController] 的 index 守卫）解决（修 B28）。
 */
class CallStateListener(private val context: Context) {
    private val tag = "CallStateListener"
    private val channel = Channel<CallState>(Channel.CONFLATED)
    val callStateFlow = channel.receiveAsFlow()

    @Volatile private var isRegistered = false

    private val executor: Executor = Executors.newSingleThreadExecutor()
    private var telephonyCallback: TelephonyCallback? = null

    private val receiver = object : BroadcastReceiver() {
        @Suppress("DEPRECATION")
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
            val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
            val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER) ?: LanguageManager.getString("status.unknown")
            // ★ 修 B14：读取本次通话所属电话账户（SIM 卡槽信息的一环）
            val accountId = intent.getStringExtra(TelephonyManager.EXTRA_PHONE_ACCOUNT_ID)
            when (state) {
                TelephonyManager.EXTRA_STATE_OFFHOOK -> emit(CallState.CONNECTED, "broadcast", number, accountId)
                TelephonyManager.EXTRA_STATE_IDLE -> emit(CallState.DISCONNECTED, "broadcast", number, accountId)
                TelephonyManager.EXTRA_STATE_RINGING -> emit(CallState.RINGING, "broadcast", number, accountId)
            }
        }
    }

    @Synchronized
    fun register() {
        if (isRegistered) return
        val tm = context.getSystemService(TelephonyManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && tm != null) {
            val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) {
                    val mapped = when (state) {
                        TelephonyManager.CALL_STATE_OFFHOOK -> CallState.CONNECTED
                        TelephonyManager.CALL_STATE_RINGING -> CallState.RINGING
                        else -> CallState.DISCONNECTED
                    }
                    emit(mapped, "telephonyCallback", null, null)
                }
            }
            try {
                tm.registerTelephonyCallback(executor, callback)
                telephonyCallback = callback
                isRegistered = true
                Log.d(tag, LanguageManager.getString("log.listener_registered"))
                return
            } catch (e: Exception) {
                Log.e(tag, "registerTelephonyCallback failed, fallback to broadcast: ${e.message}")
            }
        }
        // 广播兜底（API < 31）
        context.registerReceiver(receiver, IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED))
        isRegistered = true
        Log.d(tag, LanguageManager.getString("log.listener_registered"))
    }

    @Synchronized
    fun unregister() {
        if (!isRegistered) return
        val tm = context.getSystemService(TelephonyManager::class.java)
        val cb = telephonyCallback
        if (cb != null) {
            runCatching { tm?.unregisterTelephonyCallback(cb) }
            telephonyCallback = null
        } else {
            runCatching { context.unregisterReceiver(receiver) }
        }
        isRegistered = false
        Log.d(tag, LanguageManager.getString("log.listener_unregistered"))
    }

    @Synchronized
    fun close() {
        unregister()
        channel.close()
        Log.d(tag, LanguageManager.getString("log.channel_closed"))
    }

    private fun emit(state: CallState, source: String, number: String?, accountId: String?) {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault())
            .format(java.util.Date())
        Log.d(tag, "[$timestamp][$source] $state number=$number accountId=$accountId")
        channel.trySend(state)
    }

    enum class CallState { RINGING, CONNECTED, DISCONNECTED }
}
