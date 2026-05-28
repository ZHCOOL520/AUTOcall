package com.example.autocall

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.telephony.TelephonyManager
import android.util.Log
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

class CallStateListener(private val context: Context) {
    private val tag = "CallStateListener"
    private val channel = Channel<CallState>(Channel.CONFLATED)
    val callStateFlow = channel.receiveAsFlow()

    @Volatile private var isRegistered = false

    private val receiver = object : BroadcastReceiver() {
        @Suppress("DEPRECATION")
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
            val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
            val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER) ?: LanguageManager.getString("status.unknown")
            val timestamp = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault())
                .format(java.util.Date())
            
            when (state) {
                TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                    Log.d(tag, "[$timestamp] 📞 CONNECTED | ${LanguageManager.getString("log.number")}: $number")
                    channel.trySend(CallState.CONNECTED)
                }
                TelephonyManager.EXTRA_STATE_IDLE -> {
                    Log.d(tag, "[$timestamp] 📴 IDLE/DISCONNECTED | ${LanguageManager.getString("log.call_ended")}")
                    channel.trySend(CallState.DISCONNECTED)
                }
                TelephonyManager.EXTRA_STATE_RINGING -> {
                    Log.d(tag, "[$timestamp] 🔔 RINGING | ${LanguageManager.getString("log.incoming_number")}: $number")
                    channel.trySend(CallState.RINGING)
                }
            }
        }
    }

    @Synchronized
    fun register() {
        if (isRegistered) return
        context.registerReceiver(receiver, IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED))
        isRegistered = true
        Log.d(tag, LanguageManager.getString("log.listener_registered"))
    }

    @Synchronized
    fun unregister() {
        if (!isRegistered) return
        try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
        isRegistered = false
        Log.d(tag, LanguageManager.getString("log.listener_unregistered"))
    }

    @Synchronized
    fun close() {
        unregister()
        channel.close()
        Log.d(tag, LanguageManager.getString("log.channel_closed"))
    }

    enum class CallState { RINGING, CONNECTED, DISCONNECTED }
}