package com.example.autocall.audio

import android.content.Context
import android.media.MediaRecorder
import java.io.File
import java.util.concurrent.TimeUnit

/** 注入通道 */
enum class InjectionChannel { ROOT_UPLINK, SPEAKER_LOOPBACK, ACCESSIBILITY_UI, NONE }

/** 录音通道 */
enum class RecordChannel { ROOT_VOICE_CALL, MIC_LOOPBACK, SYSTEM_UI, NONE }

/** 当前设备实际生效的音频能力 */
data class Capabilities(
    val record: RecordChannel,
    val inject: InjectionChannel,
    val uiAssist: Boolean,
    val hasRoot: Boolean = false
)

/**
 * ROOT 探测：`su -c id` 输出包含 `uid=0` 才算可用。带 1.5s 超时，避免 ANR。
 */
object SuProbe {
    @Volatile
    private var cached: Boolean? = null

    fun isRootAvailable(force: Boolean = false): Boolean {
        if (!force) cached?.let { return it }
        val ok = try {
            val process = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val finished = process.waitFor(1500, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                false
            } else {
                output.contains("uid=0")
            }
        } catch (_: Exception) {
            false
        }
        cached = ok
        return ok
    }
}

/**
 * VOICE_CALL 试录探测：录 200ms，`maxAmplitude` 恒为 0 视为不可用。
 * 用于识别"API 调用成功、但实际录到静音"这一非 ROOT 设备上的典型情形。
 */
object VoiceCallProbe {
    @Suppress("DEPRECATION")
    fun canRecordVoiceCall(): Boolean {
        val recorder = MediaRecorder()
        return try {
            val tmp = File.createTempFile("voice_call_probe", ".m4a")
            recorder.setAudioSource(MediaRecorder.AudioSource.VOICE_CALL)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setOutputFile(tmp.absolutePath)
            recorder.prepare()
            recorder.start()
            var peak = 0
            val start = System.currentTimeMillis()
            while (System.currentTimeMillis() - start < 200) {
                peak = maxOf(peak, recorder.maxAmplitude)
                Thread.sleep(20)
            }
            peak > 0
        } catch (_: Exception) {
            false
        } finally {
            runCatching { recorder.release() }
        }
    }
}

/**
 * 通道自动探测决策树。
 *
 * 说明：默认始终以"免提回授"作为录音/注入基线（任何设备都能工作），
 * ROOT 直通仅在探测通过时优先使用。录音默认走 MIC_LOOPBACK 而非 SYSTEM_UI，
 * 以保证 App 侧仍能拿到自己的录音文件（SYSTEM_UI 的录制件在厂商私有目录，App 读不到）。
 */
object ChannelProbe {

    fun probe(context: Context, svcEnabled: Boolean): Capabilities {
        val hasRoot = SuProbe.isRootAvailable()
        val canCaptureCall = hasRoot && VoiceCallProbe.canRecordVoiceCall()
        return Capabilities(
            record = when {
                canCaptureCall -> RecordChannel.ROOT_VOICE_CALL
                else -> RecordChannel.MIC_LOOPBACK
            },
            inject = if (canCaptureCall) InjectionChannel.ROOT_UPLINK
            else InjectionChannel.SPEAKER_LOOPBACK,
            uiAssist = svcEnabled,
            hasRoot = hasRoot
        )
    }

    fun injectorFor(
        context: Context,
        caps: Capabilities,
        amplitudeSource: () -> Int = { 0 }
    ): AudioInjector = when (caps.inject) {
        InjectionChannel.ROOT_UPLINK -> RootUplinkInjector(context, amplitudeSource)
        else -> SpeakerLoopbackInjector(context, amplitudeSource)
    }
}
