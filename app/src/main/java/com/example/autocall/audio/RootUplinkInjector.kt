package com.example.autocall.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 通道 A · ROOT 直通（音质最佳，仅 ROOT 且 ROM 放行时可用）。
 *
 * 通过 `AudioTrack(USAGE_VOICE_COMMUNICATION)` 把 16k 单声道 PCM 写入通话上行，
 * 本机不外放。需由 [ChannelProbe] 在探测通过后才会被选用；探测不通过时自动降级到
 * [SpeakerLoopbackInjector]。
 */
class RootUplinkInjector(
    private val context: Context,
    /** 实时麦克风幅度来源（保留参数位，ROOT 通道本机不外放，一般不做啸叫降档）。 */
    @Suppress("unused") private val amplitudeSource: () -> Int = { 0 }
) : AudioInjector {

    private var track: AudioTrack? = null

    @Volatile
    private var stopFlag = false

    @Suppress("DEPRECATION")
    override suspend fun inject(path: String, onProgress: (Float) -> Unit): InjectResult =
        withContext(Dispatchers.IO) {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                ?: return@withContext InjectResult.Error("no AudioManager")
            val snapshot = AudioSnapshot.capture(am)
            try {
                am.mode = AudioManager.MODE_IN_CALL
                am.isSpeakerphoneOn = false

                val max = am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
                am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, (max * 0.9f).toInt().coerceAtLeast(1), 0)

                val pcm = PcmDecoder.decodeTo16kMono(path)
                if (pcm.isEmpty()) return@withContext InjectResult.Error("decode empty")
                AudioFade.fadeIn(pcm, 300)
                AudioFade.fadeOut(pcm, 300)

                val minBuf = AudioTrack.getMinBufferSize(
                    PcmDecoder.TARGET_RATE,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                val chunk = minBuf.coerceAtLeast(1024)
                track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(PcmDecoder.TARGET_RATE)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(minBuf * 2)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                track?.play()

                var offset = 0
                while (offset < pcm.size && !stopFlag) {
                    val written = track?.write(pcm, offset, minOf(chunk, pcm.size - offset)) ?: 0
                    if (written <= 0) break
                    offset += written
                    onProgress(offset.toFloat() / pcm.size)
                }
                if (stopFlag) InjectResult.Stopped else InjectResult.Ok
            } catch (e: Exception) {
                InjectResult.Error(e.message)
            } finally {
                track?.let {
                    runCatching { it.pause() }
                    runCatching { it.flush() }
                    runCatching { it.stop() }
                    runCatching { it.release() }
                }
                track = null
                snapshot.restore(am)
                stopFlag = false
            }
        }

    override fun stop() {
        stopFlag = true
        runCatching { track?.pause() }
    }
}
