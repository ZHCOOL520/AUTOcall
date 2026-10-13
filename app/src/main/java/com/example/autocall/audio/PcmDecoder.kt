package com.example.autocall.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.nio.ByteOrder

/**
 * 统一解码器：把 mp3 / m4a / aac / wav / ogg / flac / webm 等解码为
 * **16 kHz 单声道 PCM(16bit)**，供免提回授与 ROOT 上行写入共用。
 *
 * 依赖 MediaExtractor + MediaCodec，不引入任何第三方解码库。
 */
object PcmDecoder {

    const val TARGET_RATE = 16_000
    private const val TIMEOUT_US = 10_000L
    private const val INITIAL_CAPACITY = 16_384

    fun decodeTo16kMono(path: String): ShortArray {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(path)
            val trackIndex = selectAudioTrack(extractor)
            if (trackIndex < 0) return ShortArray(0)
            extractor.selectTrack(trackIndex)

            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return ShortArray(0)

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val srcRate = format.optInteger(MediaFormat.KEY_SAMPLE_RATE, TARGET_RATE)
            val srcChannels = format.optInteger(MediaFormat.KEY_CHANNEL_COUNT, 1)

            val out = ShortArrayBuilder(INITIAL_CAPACITY)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            while (!outputDone) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val inBuf = codec.getInputBuffer(inIndex)
                        val size = if (inBuf != null) extractor.readSampleData(inBuf, 0) else -1
                        if (size < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    outIndex >= 0 -> {
                        val outBuf = codec.getOutputBuffer(outIndex)
                        if (outBuf != null && info.size > 0) {
                            outBuf.position(info.offset)
                            outBuf.limit(info.offset + info.size)
                            val shorts = ShortArray(info.size / 2)
                            outBuf.order(ByteOrder.nativeOrder()).asShortBuffer().get(shorts)
                            out.add(downmixAndResample(shorts, srcChannels, srcRate))
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        // 输出格式变化（采样率/声道可能改变），以最新值为准
                    }
                    outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        // 稍后重试
                    }
                }
            }
            return out.toArray()
        } catch (_: Exception) {
            return ShortArray(0)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun MediaFormat.optInteger(key: String, def: Int): Int =
        if (containsKey(key)) getInteger(key) else def

    private fun selectAudioTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) return i
        }
        return -1
    }

    /** 下混为单声道（多声道取均值），再线性插值重采样到 [TARGET_RATE]。 */
    private fun downmixAndResample(input: ShortArray, channels: Int, rate: Int): ShortArray {
        if (channels <= 0 || input.isEmpty()) return input
        val mono = ShortArray(input.size / channels)
        for (i in mono.indices) {
            var sum = 0
            for (c in 0 until channels) sum += input[i * channels + c]
            mono[i] = (sum / channels).toShort()
        }
        if (rate == TARGET_RATE || rate <= 0) return mono
        val outLen = (mono.size.toLong() * TARGET_RATE / rate).toInt().coerceAtLeast(1)
        val out = ShortArray(outLen)
        for (i in out.indices) {
            val src = i.toDouble() * rate / TARGET_RATE
            val i0 = src.toInt().coerceIn(0, mono.size - 1)
            val i1 = (i0 + 1).coerceAtMost(mono.size - 1)
            val frac = src - i0
            out[i] = (mono[i0] * (1 - frac) + mono[i1] * frac).toInt().toShort()
        }
        return out
    }

    private class ShortArrayBuilder(initial: Int) {
        private var buf = ShortArray(initial)
        private var len = 0

        fun add(a: ShortArray) {
            if (a.isEmpty()) return
            if (len + a.size > buf.size) {
                var n = buf.size
                while (n < len + a.size) n *= 2
                buf = buf.copyOf(n)
            }
            System.arraycopy(a, 0, buf, len, a.size)
            len += a.size
        }

        fun toArray(): ShortArray = buf.copyOf(len)
    }
}
