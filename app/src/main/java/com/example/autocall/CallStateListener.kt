package com.example.autocall

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.telephony.TelephonyManager
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * 电话状态监听器
 * 监听电话接通状态
 */
class CallStateListener(private val context: Context) {

    private val _callStateChannel = Channel<CallState>(Channel.CONFLATED)
    val callStateFlow = _callStateChannel.receiveAsFlow()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == TelephonyManager.ACTION_PHONE_STATE_CHANGED) {
                val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
                when (state) {
                    TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                        // 电话接通（摘机）
                        _callStateChannel.trySend(CallState.CONNECTED)
                    }
                    TelephonyManager.EXTRA_STATE_IDLE -> {
                        // 电话挂断（空闲）
                        _callStateChannel.trySend(CallState.DISCONNECTED)
                    }
                    TelephonyManager.EXTRA_STATE_RINGING -> {
                        // 电话响铃
                        _callStateChannel.trySend(CallState.RINGING)
                    }
                }
            }
        }
    }

    /**
     * 注册监听器
     */
    fun register() {
        val filter = IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
        context.registerReceiver(receiver, filter)
    }

    /**
     * 取消注册监听器
     */
    fun unregister() {
        try {
            context.unregisterReceiver(receiver)
        } catch (_: Exception) {
            // 忽略未注册的异常
        }
    }

    /**
     * 电话状态枚举
     */
    enum class CallState {
        RINGING,      // 响铃
        CONNECTED,    // 已接通
        DISCONNECTED  // 已挂断
    }
}
