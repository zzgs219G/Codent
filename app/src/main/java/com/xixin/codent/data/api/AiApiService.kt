package com.xixin.codent.data.api

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// --- 适配 Python 中的 extra_body={"thinking": {"type": "enabled"}} ---
@Serializable
data class ThinkingConfig(
    val type: String = "enabled"
)

@Serializable
data class ChatRequest(
    val model: String = "deepseek-v4-pro",
    val messages: List<ApiMessage>,
    val stream: Boolean = false,
    @SerialName("reasoning_effort") val reasoningEffort: String = "high",
    val thinking: ThinkingConfig = ThinkingConfig()
)

// --- 适配返回体，接收 AI 的"思考过程" ---
@Serializable
data class ApiMessage(
    val role: String,
    val content: String? = null,
    @SerialName("reasoning_content") val reasoningContent: String? = null
)

@Serializable
data class ChatResponse(val choices: List<Choice> = emptyList())

@Serializable
data class Choice(val message: ApiMessage)

class AiApiService(private val client: HttpClient) {
    private val baseUrl = "https://api.deepseek.com/chat/completions"

    suspend fun getCompletion(apiKey: String, prompt: String, systemPrompt: String): String {
        if (apiKey.isBlank()) return "错误：请先在设置中配置 API Key"
        
        return try {
            val response: ChatResponse = client.post(baseUrl) {
                header(HttpHeaders.Authorization, "Bearer $apiKey")
                contentType(ContentType.Application.Json)
                setBody(ChatRequest(
                    messages = listOf(
                        ApiMessage("system", content = systemPrompt),
                        ApiMessage("user", content = prompt)
                    )
                ))
            }.body()
            
            val message = response.choices.firstOrNull()?.message
            
            if (message == null) {
                return "AI 返回了空消息"
            }

            // 丝滑展现：如果 AI 有思考过程，我们将思考过程拼接到最终回复前
            buildString {
                if (!message.reasoningContent.isNullOrBlank()) {
                    append("🤔 **思考过程：**\n")
                    append(message.reasoningContent)
                    append("\n\n---\n\n")
                }
                append(message.content ?: "")
            }
            
        } catch (e: Exception) {
            "网络或解析错误: ${e.localizedMessage}\n请检查 API Key、模型名称或网络连通性。"
        }
    }
}