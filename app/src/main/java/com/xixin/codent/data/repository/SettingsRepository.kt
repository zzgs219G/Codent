package com.xixin.codent.data.repository

import android.content.Context

/**
 * API 服务商预设配置
 * 用户可以在设置界面一键切换，无需手动输入 URL
 */
data class ApiProvider(
    val displayName : String,
    val baseUrl     : String,
    val defaultModel: String,
    val hint        : String  // 在 UI 上显示的备注
)

val PRESET_PROVIDERS = listOf(
    ApiProvider(
        displayName  = "DeepSeek（推荐）",
        baseUrl      = "https://api.deepseek.com/v1/chat/completions",
        defaultModel = "deepseek-chat",
        hint         = "稳定版，503 时首选此项"
    ),
    ApiProvider(
        displayName  = "DeepSeek Reasoner（深度思考）",
        baseUrl      = "https://api.deepseek.com/v1/chat/completions",
        defaultModel = "deepseek-reasoner",
        hint         = "重型模型，高峰期可能过载"
    ),
    ApiProvider(
        displayName  = "Moonshot (Kimi)",
        baseUrl      = "https://api.moonshot.cn/v1/chat/completions",
        defaultModel = "moonshot-v1-8k",
        hint         = "国内备用方案"
    ),
    ApiProvider(
        displayName  = "Groq（极速）",
        baseUrl      = "https://api.groq.com/openai/v1/chat/completions",
        defaultModel = "llama-3.3-70b-versatile",
        hint         = "免费、极快，适合调试"
    ),
    ApiProvider(
        displayName  = "OpenRouter（多模型）",
        baseUrl      = "https://openrouter.ai/api/v1/chat/completions",
        defaultModel = "deepseek/deepseek-chat",
        hint         = "聚合平台，一个 Key 用多种模型"
    ),
    ApiProvider(
        displayName  = "本地 Ollama",
        baseUrl      = "http://localhost:11434/v1/chat/completions",
        defaultModel = "qwen2.5-coder:7b",
        hint         = "需先在电脑上启动 Ollama"
    ),
    ApiProvider(
        displayName  = "自定义",
        baseUrl      = "",
        defaultModel = "",
        hint         = "手动填写任意 OpenAI 兼容接口"
    )
)

class SettingsRepository(context: Context) {

    private val prefs = context.getSharedPreferences("codent_settings", Context.MODE_PRIVATE)

    // ── API 配置 ──────────────────────────────────────────────
    fun saveApiBaseUrl(url: String)  = prefs.edit().putString("api_base_url", url).apply()
    fun getApiBaseUrl(): String      = prefs.getString("api_base_url", DEFAULT_API_BASE_URL) ?: DEFAULT_API_BASE_URL

    fun saveApiKey(key: String)      = prefs.edit().putString("api_key", key).apply()
    fun getApiKey(): String          = prefs.getString("api_key", "") ?: ""

    fun saveSelectedModel(model: String) = prefs.edit().putString("selected_model", model).apply()
    fun getSelectedModel(): String       = prefs.getString("selected_model", DEFAULT_MODEL) ?: DEFAULT_MODEL

    fun saveThinkingEnabled(enabled: Boolean) = prefs.edit().putBoolean("thinking_enabled", enabled).apply()
    fun isThinkingEnabled(): Boolean           = prefs.getBoolean("thinking_enabled", true)

    // ── 一键切换服务商 ────────────────────────────────────────
    fun applyProvider(provider: ApiProvider) {
        if (provider.baseUrl.isNotBlank()) saveApiBaseUrl(provider.baseUrl)
        if (provider.defaultModel.isNotBlank()) saveSelectedModel(provider.defaultModel)
    }

    companion object {
        const val DEFAULT_API_BASE_URL = "https://api.deepseek.com/v1/chat/completions"
        const val DEFAULT_MODEL        = "deepseek-chat"
    }
}
