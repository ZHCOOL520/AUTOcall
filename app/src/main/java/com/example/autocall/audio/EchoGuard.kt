package com.example.autocall.audio

/**
 * 免提回授的啸叫（正反馈自激）检测。
 *
 * 调用方传入实时的麦克风峰值幅度（由录音侧提供）。超过阈值即认为出现啸叫，
 * 并以最小间隔节流，避免频繁降档。返回 true 时调用方应下调回授音量。
 */
object EchoGuard {

    /** 16bit PCM 峰值阈值，约为满量程的 85%。 */
    private const val HOWL_THRESHOLD = 28_000

    /** 两次降档之间的最小间隔，避免抖动。 */
    private const val MIN_INTERVAL_MS = 800L

    @Volatile
    private var lastDownshiftAt = 0L

    fun check(peakAmplitude: Int): Boolean {
        if (peakAmplitude < HOWL_THRESHOLD) return false
        val now = System.currentTimeMillis()
        if (now - lastDownshiftAt < MIN_INTERVAL_MS) return false
        lastDownshiftAt = now
        return true
    }
}
