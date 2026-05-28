package com.example.autocall

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * 语言管理器 - 处理多语言配置文件的加载和文本获取
 */
object LanguageManager {
    private const val TAG = "LanguageManager"
    private const val LANGUAGES_DIR = "languages"
    private const val DEFAULT_LANGUAGE = "zh"
    
    private var currentLanguage = DEFAULT_LANGUAGE
    private var languageData: JSONObject? = null
    private var fallbackData: JSONObject? = null
    
    /**
     * 初始化语言管理器
     * @param context 应用上下文
     * @param languageCode 语言代码 (如 "zh", "en")
     */
    fun init(context: Context, languageCode: String) {
        currentLanguage = languageCode
        
        // 确保语言目录存在
        val languagesDir = File(context.filesDir, LANGUAGES_DIR)
        if (!languagesDir.exists()) {
            languagesDir.mkdirs()
        }
        
        // 从assets复制默认语言文件（如果不存在）
        copyDefaultLanguagesFromAssets(context)
        
        // 加载当前语言和回退语言
        loadLanguage(context, languageCode)
    }
    
    /**
     * 从assets复制默认语言文件到内部存储
     * 每次启动时始终覆盖，确保使用最新的翻译
     */
    private fun copyDefaultLanguagesFromAssets(context: Context) {
        try {
            val languagesDir = File(context.filesDir, LANGUAGES_DIR)
            val assetManager = context.assets
            
            // 列出assets中的语言文件
            val assetFiles = assetManager.list("languages") ?: return
            
            for (fileName in assetFiles) {
                val targetFile = File(languagesDir, fileName)
                // 始终从assets覆盖，确保语言文件为最新版本
                assetManager.open("languages/$fileName").use { input ->
                    targetFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                Log.d(TAG, "Copied $fileName to internal storage")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error copying default languages", e)
        }
    }
    
    /**
     * 加载指定语言的配置文件
     */
    private fun loadLanguage(context: Context, languageCode: String) {
        try {
            // 加载当前语言
            val languageFile = File(context.filesDir, "$LANGUAGES_DIR/${languageCode}.json")
            if (languageFile.exists()) {
                val content = languageFile.readText()
                languageData = JSONObject(content)
                Log.d(TAG, "Loaded language: $languageCode")
            } else {
                Log.w(TAG, "Language file not found: $languageCode, using fallback")
                languageData = null
            }
            
            // 加载回退语言（中文）
            if (languageCode != DEFAULT_LANGUAGE) {
                val fallbackFile = File(context.filesDir, "$LANGUAGES_DIR/${DEFAULT_LANGUAGE}.json")
                if (fallbackFile.exists()) {
                    val content = fallbackFile.readText()
                    fallbackData = JSONObject(content)
                    Log.d(TAG, "Loaded fallback language: $DEFAULT_LANGUAGE")
                }
            } else {
                fallbackData = null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading language file", e)
            languageData = null
        }
    }
    
    /**
     * 切换语言
     */
    fun switchLanguage(context: Context, languageCode: String) {
        currentLanguage = languageCode
        // 重新同步assets文件，确保使用最新翻译
        copyDefaultLanguagesFromAssets(context)
        loadLanguage(context, languageCode)
    }
    
    /**
     * 确保LanguageManager已初始化（如果未初始化则自动初始化）
     */
    fun ensureInitialized(context: Context) {
        if (languageData == null && fallbackData == null) {
            Log.d(TAG, "LanguageManager not initialized, auto-initializing with default language")
            init(context, DEFAULT_LANGUAGE)
        }
    }
    
    /**
     * 获取翻译文本
     * @param key 键路径，使用点号分隔，如 "settings.title"
     * @param args 格式化参数
     * @return 翻译后的文本，如果找不到则返回键名
     */
    fun getString(key: String, vararg args: Any): String {
        // 确保已初始化（防止在ViewModel加载前就调用）
        try {
            val context = getApplicationInstance()
            if (context != null) {
                ensureInitialized(context)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not auto-initialize LanguageManager", e)
        }
        
        try {
            // 尝试从当前语言获取
            var value = getValueFromJson(languageData, key)
            
            // 如果未找到，尝试从回退语言获取
            if (value == null && fallbackData != null) {
                value = getValueFromJson(fallbackData, key)
            }
            
            // 如果仍未找到，返回键名
            if (value == null) {
                Log.w(TAG, "Translation key not found: $key")
                return key
            }
            
            // 格式化字符串（如果有参数）
            return if (args.isNotEmpty()) {
                String.format(value, *args)
            } else {
                value
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting string for key: $key", e)
            return key
        }
    }
    
    /**
     * 获取Application实例（用于自动初始化）
     */
    private fun getApplicationInstance(): android.app.Application? {
        try {
            // 通过反射获取当前的Application实例
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val currentApplicationMethod = activityThreadClass.getMethod("currentApplication")
            return currentApplicationMethod.invoke(null) as? android.app.Application
        } catch (e: Exception) {
            return null
        }
    }
    
    /**
     * 从JSONObject中根据点号分隔的路径获取值
     */
    private fun getValueFromJson(json: JSONObject?, key: String): String? {
        if (json == null) return null
        
        val keys = key.split(".")
        var current: Any = json
        
        for (k in keys) {
            if (current is JSONObject) {
                if (current.has(k)) {
                    current = current.get(k)
                } else {
                    return null
                }
            } else {
                return null
            }
        }
        
        return if (current is String) current else null
    }
    
    /**
     * 获取当前语言代码
     */
    fun getCurrentLanguage(): String {
        return currentLanguage
    }
    
    /**
     * 获取语言显示名称
     */
    fun getLanguageDisplayName(languageCode: String): String {
        return when (languageCode) {
            "zh" -> "中文"
            "en" -> "English"
            else -> languageCode
        }
    }
}
