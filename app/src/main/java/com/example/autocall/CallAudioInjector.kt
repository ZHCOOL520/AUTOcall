package com.example.autocall

import android.content.Context
import android.media.*
import android.util.Log
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume

/**
 * 通话音频注入器（最终稳定版）
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

        return suspendCancellableCoroutine { cont ->
            isInjecting = true
            currentContinuation = cont

            try {
                val file = File(audioPath)
                if (!file.exists()) {
                    Log.e(tag, LanguageManager.getString("log.file_not_exist", audioPath))
                    cont.resume(false)
                    return@suspendCancellableCoroutine   // ✅ 立即返回
                }

                // 保存并设置通话音频模式
                val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                if (am == null) {
                    Log.e(tag, LanguageManager.getString("log.cannot_get_audio_manager"))
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
                    setAudioStreamType(AudioManager.STREAM_VOICE_CALL)

                    setOnPreparedListener { mp ->
                        Log.d(tag, LanguageManager.getString("log.mediaplayer_prepared"))
                        mp.start()
                    }

                    setOnCompletionListener {
                        Log.d(tag, LanguageManager.getString("log.playback_completed"))
                        restoreAudio()
                        release()
                        cont.resume(true)   // ✅ 完成后 resume，且后续无代码
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
                    // ⚠️ 此处不能 resume，系统会自动抛出 CancellationException
                }

            } catch (e: Exception) {
                Log.e(tag, LanguageManager.getString("log.inject_exception", e.message ?: ""), e)
                restoreAudio()
                release()
                cont.resume(false)         // ✅ 仅 resume，无后续代码
            }
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
            isInjecting = false
        }
    }
}
