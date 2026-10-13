package com.example.autocall.audio

/**
 * 通话音频注入器的统一抽象。
 *
 * 两种可互换实现：
 *  - [RootUplinkInjector]       通道 A · ROOT 直通（写入通话上行，本机不外放）
 *  - [SpeakerLoopbackInjector]  通道 B · 免提回授（默认，免 ROOT 可用，本机会外放）
 */
interface AudioInjector {
    /** 播放 [path] 到通话信道；[onProgress] 回调 0f..1f。 */
    suspend fun inject(path: String, onProgress: (Float) -> Unit = {}): InjectResult

    /** 请求停止当前注入。 */
    fun stop()
}

sealed class InjectResult {
    /** 正常播放完毕 */
    object Ok : InjectResult()

    /** 免提设备不可用 / 无法切换通信设备 */
    object NoSpeaker : InjectResult()

    /** 被 [AudioInjector.stop] 主动中断 */
    object Stopped : InjectResult()

    /** 其它异常 */
    data class Error(val msg: String?) : InjectResult()
}
