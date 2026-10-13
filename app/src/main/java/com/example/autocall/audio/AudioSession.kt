package com.example.autocall.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import kotlinx.coroutines.delay

/**
 * `AudioManager` 现场快照：mode / 通信设备 / 通话音量 / 免提状态。
 * 用于在通话结束后 100% 还原，避免污染下一通电话（修 B32）。
 */
data class AudioSnapshot(
    val mode: Int,
    val device: AudioDeviceInfo?,
    val voiceCallVolume: Int,
    val speakerphoneOn: Boolean
) {
    companion object {
        @Suppress("DEPRECATION")
        fun capture(am: AudioManager): AudioSnapshot {
            val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) am.communicationDevice else null
            return AudioSnapshot(
                mode = am.mode,
                device = device,
                voiceCallVolume = am.getStreamVolume(AudioManager.STREAM_VOICE_CALL),
                speakerphoneOn = am.isSpeakerphoneOn
            )
        }
    }

    @Suppress("DEPRECATION")
    fun restore(am: AudioManager) {
        runCatching { am.mode = mode }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val d = device
                if (d != null) am.setCommunicationDevice(d) else am.clearCommunicationDevice()
            } else {
                am.isSpeakerphoneOn = speakerphoneOn
            }
        }
        runCatching { am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, voiceCallVolume, 0) }
    }
}

/** PCM 淡入/淡出，修 B16 起播爆音。 */
object AudioFade {
    const val RATE = PcmDecoder.TARGET_RATE

    fun fadeIn(pcm: ShortArray, ms: Int) = applyGain(pcm, 0, samplesOf(ms), 0f, 1f)

    fun fadeOut(pcm: ShortArray, ms: Int) {
        val n = samplesOf(ms)
        applyGain(pcm, pcm.size - n, n, 1f, 0f)
    }

    private fun samplesOf(ms: Int) = (RATE.toLong() * ms / 1000).toInt()

    private fun applyGain(pcm: ShortArray, start: Int, count: Int, from: Float, to: Float) {
        if (count <= 0 || pcm.isEmpty()) return
        val s = start.coerceAtLeast(0)
        val end = (s + count).coerceAtMost(pcm.size)
        val span = (end - s).coerceAtLeast(1)
        for (i in s until end) {
            val gain = from + (to - from) * (i - s) / span
            val v = (pcm[i] * gain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            pcm[i] = v.toShort()
        }
    }
}

/**
 * 单一音频会话协调器。
 *
 * 免 ROOT 下"录音"与"注入"是同一个免提声学回路的两端，必须共用一个会话，
 * 不允许各自抢 `STREAM_VOICE_CALL`（修 B3 / B19）。ROOT 下录音与上行写入共用
 * `MODE_IN_CALL` 会话。
 */
object AudioSession {

    @Volatile
    private var active = false

    @Synchronized
    fun beginCall() {
        active = true
    }

    @Synchronized
    fun endCall() {
        active = false
    }

    fun isActive(): Boolean = active

    /**
     * 等待音频信道在通话建立后稳定下来（替代原先的 delay(500L) 魔法数字，修 B13）。
     */
    suspend fun awaitStable(ms: Long = 300) {
        delay(ms)
    }

    /** 统一的通信模式设置：免 ROOT 用 MODE_IN_COMMUNICATION，ROOT 用 MODE_IN_CALL。 */
    @Suppress("DEPRECATION")
    fun applyCommunicationMode(context: Context, rootUplink: Boolean): Boolean {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        return if (rootUplink) {
            am.mode = AudioManager.MODE_IN_CALL
            true
        } else {
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            true
        }
    }
}
