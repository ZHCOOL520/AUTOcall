package com.example.autocall.record

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 呼叫方向 */
enum class CallDirection(val code: String, val tag: String) {
    OUT("O", "out"),
    IN("I", "in")
}

/** 通话结果码 */
enum class CallResult(val code: String) {
    OK("ok"), FAIL("fail"), NO_ANSWER("noans"), BUSY("busy"), DROPPED("drop")
}

/**
 * 录音命名规范的**唯一实现处**（问题一答案）。
 *
 * 格式：
 * ```
 * {seq:05d}_{direction}{sim}_{caller}_{callee}_{yyyyMMdd_HHmmssSSS}_{duration}s_{result}.{ext}
 * ```
 * 例：`00001_O1_13800138000_13900139000_20260608_143052317_46s_ok.m4a`
 */
object RecordingNaming {

    /** Windows/Linux 双边非法字符 + 空白 */
    private val ILLEGAL = Regex("""[\\/:*?"<>|\s]""")

    /** 保留：字母数字下划线、中文、连字符 */
    private val STRIP = Regex("""[^\w\u4e00-\u9fa5-]""")

    /**
     * 净化文件名片段。
     * @param keepPlusAsP `+` 转义为 `p`（`+86…` → `p86…`），避免在部分 ROM 上被解析异常（修 B6）
     */
    fun sanitizeToken(raw: String?, keepPlusAsP: Boolean = true): String {
        if (raw.isNullOrBlank()) return "unknown"
        var s = raw.trim()
        if (keepPlusAsP) s = s.replace("+", "p")
        s = s.replace(ILLEGAL, "")
        s = s.replace(STRIP, "")
        return s.take(32).ifEmpty { "unknown" }
    }

    /** 稳定主键：`yyyyMMdd-HHmmss-SSS-00001` */
    fun buildCallId(seq: Int, startedAtMs: Long): String =
        SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date(startedAtMs)) +
            "-" + String.format(Locale.US, "%05d", seq)

    fun buildFileName(
        seq: Int,
        direction: CallDirection,
        simSlot: Int,
        caller: String?,
        callee: String?,
        startedAtMs: Long,
        durationSec: Long,
        result: CallResult,
        ext: String = "m4a"
    ): String {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmssSSS", Locale.US).format(Date(startedAtMs))
        return String.format(
            Locale.US,
            "%05d_%s%d_%s_%s_%s_%ds_%s.%s",
            seq, direction.code, simSlot,
            sanitizeToken(caller), sanitizeToken(callee),
            ts, durationSec, result.code, ext
        )
    }

    /** 私有目录分层：`2026/06/08` */
    fun relativeDir(startedAtMs: Long): String =
        SimpleDateFormat("yyyy/MM/dd", Locale.US).format(Date(startedAtMs))
}
