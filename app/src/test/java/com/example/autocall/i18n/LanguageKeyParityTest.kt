package com.example.autocall.i18n

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * ★ Task 5.5（修 B20 漏翻译风险）：断言 `zh.json` 与 `en.json` 的**扁平化 key 集合完全一致**。
 *
 * 使用 Gson（JVM 可用）解析，避免依赖 Android 的 org.json 桩。
 */
class LanguageKeyParityTest {

    private val gson = Gson()

    @Test
    fun `zh and en have identical flat key sets`() {
        val zh = flatKeys(load("zh"))
        val en = flatKeys(load("en"))
        assertEquals("missing in en: ${zh - en}", zh, en)
    }

    @Suppress("UNCHECKED_CAST")
    private fun load(lang: String): Map<*, *> {
        val file = File("src/main/assets/languages/$lang.json")
        return gson.fromJson(file.readText(), Map::class.java) as Map<*, *>
    }

    private fun flatKeys(map: Map<*, *>, prefix: String = ""): Set<String> {
        val out = mutableSetOf<String>()
        for ((k, v) in map) {
            val key = k as String
            val full = if (prefix.isEmpty()) key else "$prefix.$key"
            if (v is Map<*, *>) out += flatKeys(v, full) else out += full
        }
        return out
    }
}
