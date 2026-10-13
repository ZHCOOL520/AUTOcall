package com.example.autocall.record

import android.content.Context

/**
 * 全局自增序号（`seq`）的持久化。
 *
 * 使用 `SharedPreferences.commit()`（同步写）而非 `apply()`，保证进程随时被杀也不重号。
 * 首次使用时以持久化值为起点；若持久化缺失则从 0 开始。
 */
object RecordingIndex {

    private const val PREFS = "recording_index"
    private const val KEY_SEQ = "seq"

    private val lock = Any()

    @Volatile
    private var last = -1

    /** 取下一个序号（严格递增、进程内同步、落盘同步）。 */
    fun next(context: Context): Int = synchronized(lock) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (last < 0) last = prefs.getInt(KEY_SEQ, 0)
        last += 1
        prefs.edit().putInt(KEY_SEQ, last).commit()
        last
    }

    /** 读取当前已分配的最大序号（不递增）。 */
    fun peek(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_SEQ, 0)
}
