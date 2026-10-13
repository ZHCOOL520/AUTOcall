package com.example.autocall

import android.annotation.SuppressLint
import android.content.Context
import android.media.*
import android.util.Log
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume

/**
 * 通话音频注入器（最终稳定版）
 *
 * 修 B9：`isInjecting` 在所有路径（含文件不存在、拿不到 AudioManager 的提前返回）
 * 统一由外层 finally 复位，不再依赖 release() 的副作用，避免"一次坏路径永久废掉播放"。
 * 修 B44：不再同时设置已废弃的 setAudioStreamType 与 setAudioAttributes。
 */
class CallAudioInjector(private val context: Context) {

    private val tag = "CallAudioInjector"

    @Volatile
    private var isInjecting = false

    private var mediaPlayer: MediaPlayer? = null
    private var savedAudioState: AudioState? = null
    private var currentContinuation: kotlin.coroutines.Continuation<Boolean>? = null

    private data class AudioState(val mode: Int, val speakerOn: Boolean, val volume: Int)

    /**
     * 注入音频到通话通道
     */
    @Suppress("DEPRECATION")
    suspend fun injectAudioToCall(audioPath: String): Boolean {
        if (isInjecting) {
            Log.w(tag, LanguageManager.getString("log.already_injecting"))
            return false
        }

        isInjecting = true
        return try {
            suspendCancellableCoroutine { cont ->
                currentContinuation = cont

                try {
                    val file = File(audioPath)
                    if (!file.exists()) {
                        Log.e(tag, LanguageManager.getString("log.file_not_exist", audioPath))
                        currentContinuation = null
                        cont.resume(false)   // 不再提前 return；由外层 finally 复位 isInjecting
                        return@suspendCancellableCoroutine
                    }

                    // 保存并设置通话音频模式
                    val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                    if (am == null) {
                        Log.e(tag, LanguageManager.getString("log.cannot_get_audio_manager"))
                        currentContinuation = null
                        cont.resume(false)
                        return@suspendCancellableCoroutine
                    }
                    savedAudioState = AudioState(
                        am.mode,
                        am.isSpeakerphoneOn,
                        am.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
                    )
                    am.mode = AudioManager.MODE_IN_CALL
                    am.setSpeakerphoneOn(false)
                    am.setStreamVolume(
                        AudioManager.STREAM_VOICE_CALL,
                        am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL),
                        0
                    )

                    mediaPlayer = MediaPlayer().apply {
                        setDataSource(audioPath)
                        setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build()
                        )

                        setOnPreparedListener { mp ->
                            Log.d(tag, LanguageManager.getString("log.mediaplayer_prepared"))
                            mp.start()
                        }

                        setOnCompletionListener {
                            Log.d(tag, LanguageManager.getString("log.playback_completed"))
                            restoreAudio()
                            release()
                            cont.resume(true)
                        }

                        setOnErrorListener { _, what, extra ->
                            Log.e(tag, LanguageManager.getString("log.mediaplayer_error", what, extra))
                            restoreAudio()
                            release()
                            cont.resume(false)
                            true               // 返回 true 表示错误已处理
                        }

                        prepareAsync()
                    }

                    // 注册取消回调
                    cont.invokeOnCancellation {
                        Log.w(tag, LanguageManager.getString("log.coroutine_cancelled"))
                        restoreAudio()
                        release()
                        // 此处不能 resume，系统会自动抛出 CancellationException
                    }

                } catch (e: Exception) {
                    Log.e(tag, LanguageManager.getString("log.inject_exception", e.message ?: ""), e)
                    restoreAudio()
                    release()
                    cont.resume(false)
                }
            }
        } finally {
            // ★ 唯一的复位点：正常完成 / 提前返回 / 异常 / 取消 / stop() 全部覆盖
            currentContinuation = null
            savedAudioState = null
            isInjecting = false
        }
    }

    /**
     * 强制停止
     */
    @Synchronized
    fun stop() {
        if (!isInjecting) return

        val cont = currentContinuation
        currentContinuation = null    // 防止重复 resume

        restoreAudio()
        release()

        // 尝试恢复 continuation（需检查协程是否仍活跃）
        cont?.let {
            try {
                if (it.context.isActive) {
                    it.resume(false)     // 返回 false 表示被手动停止
                }
            } catch (e: Exception) {
                Log.e(tag, LanguageManager.getString("log.resume_continuation_failed", e.message ?: ""), e)
            }
        }
        // isInjecting 由 injectAudioToCall 的外层 finally 复位：
        // 若此处成功 resume，函数随即返回并复位；若协程已取消，同样由 finally 兜底。
    }

    @Suppress("DEPRECATION")
    private fun restoreAudio() {
        val state = savedAudioState ?: return
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (am != null) {
                am.mode = state.mode
                am.setSpeakerphoneOn(state.speakerOn)
                am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, state.volume, 0)
            } else {
                Log.w(tag, LanguageManager.getString("log.cannot_get_audio_manager_skip_restore"))
            }
        } catch (e: Exception) {
            Log.e(tag, LanguageManager.getString("log.restore_audio_state_failed", e.message ?: ""), e)
        } finally {
            savedAudioState = null
        }
    }

    private fun release() {
        try {
            mediaPlayer?.apply {
                if (isPlaying) stop()
                reset()
                release()
            }
        } catch (e: Exception) {
            Log.e(tag, LanguageManager.getString("log.release_mediaplayer_failed", e.message ?: ""), e)
        } finally {
            mediaPlayer = null
        }
    }
}
