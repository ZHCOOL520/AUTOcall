package com.example.autocall.record

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import com.example.autocall.audio.RecordChannel
import java.io.File

/**
 * 录音门面：按 [RecordChannel] 分派音源，统一产出 `MPEG_4 + AAC`、16 kHz 单声道。
 *
 * - `ROOT_VOICE_CALL` → `AudioSource.VOICE_CALL`
 * - 其它（免提回授） → `AudioSource.MIC`
 *
 * 录到临时文件，通话结束后由 [RecordingStore.commit] 重命名落盘（因为最终文件名里含时长与结果）。
 * 修 B2/B5/B12/B17/B27。
 */
class CallRecorder(private val context: Context) {

    private val tag = "CallRecorder"

    @Volatile
    private var recording = false

    private var recorder: MediaRecorder? = null
    private var tempFile: File? = null
    private var channel: RecordChannel = RecordChannel.MIC_LOOPBACK
    private var errorMessage: String? = null

    /** 当前录音的实时峰值幅度（供 EchoGuard 做啸叫检测）。 */
    @Volatile
    private var lastAmplitude = 0

    fun amplitude(): Int = lastAmplitude

    fun lastError(): String? = errorMessage

    fun isRecording(): Boolean = recording

    fun start(recordChannel: RecordChannel): Boolean {
        if (recording) return true
        errorMessage = null
        return try {
            channel = recordChannel
            val dir = File(context.getExternalFilesDir(null), "call_records_tmp").apply { if (!exists()) mkdirs() }
            tempFile = File(dir, "rec_${System.currentTimeMillis()}.m4a")

            @Suppress("DEPRECATION")
            val src = if (channel == RecordChannel.ROOT_VOICE_CALL)
                MediaRecorder.AudioSource.VOICE_CALL else MediaRecorder.AudioSource.MIC

            recorder = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else MediaRecorder()).apply {
                setAudioSource(src)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(16_000)
                setAudioChannels(1)
                setOutputFile(tempFile!!.absolutePath)
                setOnErrorListener { _, what, extra ->
                    errorMessage = "MediaRecorder error what=$what extra=$extra"
                    Log.e(tag, errorMessage ?: "")
                }
                prepare()
                start()
            }
            recording = true
            true
        } catch (e: Exception) {
            errorMessage = e.message
            Log.e(tag, "start failed: ${e.message}")
            releaseQuietly()
            false
        }
    }

    /**
     * 停止并返回临时文件。
     * 修 B17：即使 `stop()` 抛异常，只要已写出非空内容，仍返回该文件（不丢录音）。
     */
    fun stop(): File? {
        if (!recording) return null
        val file = tempFile
        return try {
            recorder?.apply { stop(); release() }
            recorder = null
            recording = false
            tempFile = null
            if (file != null && file.exists() && file.length() > MIN_VALID_BYTES) file else null
        } catch (e: Exception) {
            errorMessage = e.message
            Log.e(tag, "stop failed: ${e.message}")
            runCatching { recorder?.release() }
            recorder = null
            recording = false
            tempFile = null
            if (file != null && file.exists() && file.length() > 0) file else null
        }
    }

    /** 周期性读取峰值，供啸叫检测使用（调用方在播放循环里轮询）。 */
    fun sampleAmplitude() {
        lastAmplitude = runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0)
    }

    fun release() {
        if (recording) stop()
        releaseQuietly()
    }

    private fun releaseQuietly() {
        runCatching { recorder?.release() }
        recorder = null
        recording = false
        tempFile = null
    }

    companion object {
        private const val MIN_VALID_BYTES = 2_048L
    }
}
