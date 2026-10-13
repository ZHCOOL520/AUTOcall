package com.example.autocall.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 通道 B · 免提回授（默认通道，必须最稳）。
 *
 * 原理：开免提 → 播放语音从扬声器外放 → 本机麦克风拾取（同时用于录音）→ 送回上行。
 * 这是声学耦合，不是 API 注入，因此在**任何设备**上都能工作；代价是**本机必然外放**且音质一般。
 *
 * 关键工程点：
 *  - 用 `setCommunicationDevice(TYPE_SPEAKER)`（API 31+），替代已废弃的 `setSpeakerphoneOn`；
 *  - 回授音量取 70% 甜点，避免正反馈啸叫；检出啸叫时自动降档；
 *  - 16 kHz 单声道 + 300ms 淡入淡出，避免爆音（修 B16）；
 *  - `finally` 用快照 100% 还原音频现场（修 B32）。
 */
class SpeakerLoopbackInjector(
    private val context: Context,
    /** 实时麦克风幅度来源（由 CallRecorder 提供）；未接时返回 0，表示不做啸叫检测。 */
    private val amplitudeSource: () -> Int = { 0 }
) : AudioInjector {

    private val tag = "SpeakerLoopback"

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
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    am.mode = AudioManager.MODE_IN_COMMUNICATION
                    val speaker = am.availableCommunicationDevices
                        .firstOrNull { it.type == AudioDeviceInfo.TYPE_SPEAKER }
                        ?: return@withContext InjectResult.NoSpeaker
                    if (!am.setCommunicationDevice(speaker)) return@withContext InjectResult.NoSpeaker
                } else {
                    am.mode = AudioManager.MODE_IN_CALL
                    am.isSpeakerphoneOn = true
                }

                val max = am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
                var volume = (max * 0.7f).toInt().coerceAtLeast(1)
                am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, volume, 0)

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
                    if (EchoGuard.check(amplitudeSource())) {
                        volume = (volume * 0.7f).toInt().coerceAtLeast(1)
                        runCatching { am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, volume, 0) }
                        Log.w(tag, "howling detected, downshift volume to $volume")
                    }
                }
                if (stopFlag) InjectResult.Stopped else InjectResult.Ok
            } catch (e: Exception) {
                InjectResult.Error(e.message)
            } finally {
                releaseTrack()
                snapshot.restore(am)
                stopFlag = false
            }
        }

    override fun stop() {
        stopFlag = true
        runCatching { track?.pause() }
    }

    private fun releaseTrack() {
        track?.let {
            runCatching { it.pause() }
            runCatching { it.flush() }
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        track = null
    }
}
