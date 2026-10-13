package com.example.autocall

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/** 一组通话中按键关键词：含匹配词、排除词、优先视图 ID（★ Task 4.1）。 */
data class KeywordSet(
    val text: List<String>,
    val excludeText: List<String>,
    val preferViewIds: List<String>
)

/**
 * ★ Task 4.1：从 `assets/incall_button_keywords.json` 加载通话中按键关键词表。
 *
 * 外置后各 ROM 关键词可**热更新**（改 assets 重装或覆盖即可），不必改代码。
 */
object IncallKeywordTable {

    private const val TAG = "IncallKeywordTable"
    private const val ASSET_NAME = "incall_button_keywords.json"

    @Volatile
    private var cached: Map<String, KeywordSet>? = null

    fun load(context: Context, force: Boolean = false): Map<String, KeywordSet> {
        if (!force) cached?.let { return it }
        val result = mutableMapOf<String, KeywordSet>()
        runCatching {
            val json = context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
            val root = JSONObject(json)
            root.keys().forEach { key ->
                val obj = root.optJSONObject(key) ?: return@forEach
                result[key] = KeywordSet(
                    text = obj.optJSONArray("text").toStringList(),
                    excludeText = obj.optJSONArray("excludeText").toStringList(),
                    preferViewIds = obj.optJSONArray("preferViewIds").toStringList()
                )
            }
        }.onFailure { Log.e(TAG, "load failed: ${it.message}") }
        cached = result
        return result
    }

    fun get(context: Context, key: String): KeywordSet =
        load(context)[key] ?: KeywordSet(emptyList(), emptyList(), emptyList())

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { optString(it).takeIf { s -> s.isNotEmpty() } }
    }
}
