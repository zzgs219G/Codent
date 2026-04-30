package com.xixin.codent.data.api

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.Serializable

@Serializable
data class ChatRequest(
    val model: String = "deepseek-chat",
    val messages: List<ApiMessage>,
    val stream: Boolean = false
)

@Serializable
data class ApiMessage(val role: String, val content: String)

class AiApiService(private val client: HttpClient) {
    private val apiKey = "YOUR_DEEPSEEK_API_KEY" // 建议从配置读取
    private val baseUrl = "[https://api.deepseek.com/chat/completions](https://api.deepseek.com/chat/completions)"

    suspend fun getCompletion(prompt: String, systemPrompt: String): String? {
        return try {
            val response: HttpResponse = client.post(baseUrl) {
                header(HttpHeaders.Authorization, "Bearer $apiKey")
                contentType(ContentType.Application.Json)
                setBody(ChatRequest(
                    messages = listOf(
                        ApiMessage("system", systemPrompt),
                        ApiMessage("user", prompt)
                    )
                ))
            }
            // 这里根据实际 API 响应结构进行反序列化
            // 简化版演示：
            response.bodyAsText() 
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }
}