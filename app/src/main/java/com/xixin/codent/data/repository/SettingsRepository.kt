// 文件路径: app/src/main/java/com/xixin/codent/data/repository/SettingsRepository.kt
//
// 重构内容：
//   - 从 SafRepository 中拆分出来，单一职责：只管 API 配置和 App 设置的持久化
//   - SafRepository 不再承担 SharedPreferences 读写职责
package com.xixin.codent.data.repository

import android.content.Context

class SettingsRepository(context: Context) {

    private val prefs = context.getSharedPreferences("codent_settings", Context.MODE_PRIVATE)

    // ── API 配置 ──────────────────────────────────────────────

    fun saveApiBaseUrl(url: String) =
        prefs.edit().putString("api_base_url", url).apply()

    fun getApiBaseUrl(): String =
        prefs.getString("api_base_url", DEFAULT_API_BASE_URL) ?: DEFAULT_API_BASE_URL

    fun saveApiKey(key: String) =
        prefs.edit().putString("api_key", key).apply()

    fun getApiKey(): String =
        prefs.getString("api_key", "") ?: ""

    fun saveSelectedModel(model: String) =
        prefs.edit().putString("selected_model", model).apply()

    fun getSelectedModel(): String =
        prefs.getString("selected_model", DEFAULT_MODEL) ?: DEFAULT_MODEL

    fun saveThinkingEnabled(enabled: Boolean) =
        prefs.edit().putBoolean("thinking_enabled", enabled).apply()

    fun isThinkingEnabled(): Boolean =
        prefs.getBoolean("thinking_enabled", true)

    companion object {
        const val DEFAULT_API_BASE_URL = "https://api.deepseek.com/chat/completions"
        const val DEFAULT_MODEL = "deepseek-reasoner"
    }
}
