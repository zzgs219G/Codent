package com.xixin.codent.data.api

import com.xixin.codent.wrapper.log.AppLog
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

// ============================== 1. 工具定义 ==============================
@Serializable
data class Tool(
    val type: String = "function",
    val function: FunctionDef
)

@Serializable
data class FunctionDef(
    val name: String,
    val description: String,
    val parameters: JsonElement
)

@Serializable
data class ToolCall(
    val id: String = "",
    val type: String = "function",
    val function: FunctionCall
)

@Serializable
data class FunctionCall(
    val name: String? = null,
    val arguments: String = ""
)

// ============================== 2. 消息结构 ==============================
/**
 * 标准消息（用于请求和普通响应）
 * ⚠️ 关键：增加 name 字段（tool 消息必须提供工具名）
 */
@Serializable
data class ApiMessage(
    val role: String,
    val content: String? = null,
    @SerialName("reasoning_content") val reasoningContent: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ToolCall>? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
    val name: String? = null          // tool 消息专用
)

/**
 * 流式 Delta 消息（无 role 字段）
 */
@Serializable
data class DeltaMessage(
    val role: String? = null,
    val content: String? = null,
    @SerialName("reasoning_content") val reasoningContent: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ToolCall>? = null
)

// ============================== 3. 请求/响应包装 ==============================
@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<ApiMessage>,
    val stream: Boolean = true,
    @SerialName("stream_options") val streamOptions: JsonObject = buildJsonObject { put("include_usage", true) },
    val tools: List<Tool>? = null,
    val thinking: JsonObject? = null
)

@Serializable
data class ChunkResponse(
    val choices: List<ChunkChoice> = emptyList(),
    val usage: Usage? = null
)

@Serializable
data class ChunkChoice(
    val delta: DeltaMessage,
    @SerialName("finish_reason") val finishReason: String? = null
)

@Serializable
data class Usage(
    @SerialName("prompt_tokens") val promptTokens: Int = 0,
    @SerialName("completion_tokens") val completionTokens: Int = 0
)

// ============================== 4. 流式事件 ==============================
sealed class StreamEvent {
    data class Content(val text: String) : StreamEvent()
    data class ToolCallStarted(val functionName: String) : StreamEvent()
    data class ToolCallComplete(val toolCallId: String, val functionName: String, val arguments: String) : StreamEvent()
    data class Done(val usage: Usage?) : StreamEvent()
    data class Error(val message: String) : StreamEvent()
}

// ============================== 5. API 服务 ==============================
class AiApiService(private val client: HttpClient) {
    private val baseUrl = "https://api.deepseek.com/chat/completions"
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    fun getAgentCompletionStream(
        apiKey: String,
        model: String,
        messages: List<ApiMessage>,
        tools: List<Tool>? = null,
        enableThinking: Boolean = true
    ): Flow<StreamEvent> = flow {
        AppLog.d("AiApiService 请求: model=$model, enableThinking=$enableThinking, messages.size=${messages.size}")

        // 调试：打印完整请求体（取消注释以诊断）
        // val reqBody = ChatRequest(model, messages, true, buildJsonObject { put("include_usage", true) }, tools, if (enableThinking) buildJsonObject { put("type", "enabled") } else null)
        // AppLog.d("Request body: ${json.encodeToString(reqBody)}")

        try {
            val thinkingParam = if (enableThinking) {
    buildJsonObject { put("type", "enabled") }
} else {
    buildJsonObject { put("type", "disabled") }   // 明确禁用，而不是 null
}
            client.preparePost(baseUrl) {
                header(HttpHeaders.Authorization, "Bearer $apiKey")
                contentType(ContentType.Application.Json)
                setBody(
                    ChatRequest(
                        model = model,
                        messages = messages,
                        tools = tools,
                        stream = true,
                        thinking = thinkingParam
                    )
                )
            }.execute { response ->
                if (!response.status.isSuccess()) {
                    val errorBody = runCatching { response.bodyAsText() }.getOrNull()
                    val errorMsg = "HTTP ${response.status.value}: ${response.status.description}"
                    AppLog.e("API 响应错误: $errorMsg, body=$errorBody")
                    emit(StreamEvent.Error("$errorMsg\n$errorBody"))
                    return@execute
                }
                AppLog.d("API 连接成功，开始读取流")

                val channel = response.bodyAsChannel()
                var toolId = ""
                var funcName = ""
                val funcArgsAccumulator = StringBuilder()

                while (!channel.isClosedForRead) {
                    val line = channel.readUTF8Line()?.trim() ?: break
                    if (line.isEmpty() || !line.startsWith("data: ")) continue

                    val data = line.removePrefix("data: ").trim()
                    if (data == "[DONE]") break

                    try {
                        val chunk = json.decodeFromString<ChunkResponse>(data)
                        if (chunk.usage != null) emit(StreamEvent.Done(chunk.usage))

                        val choice = chunk.choices.firstOrNull() ?: continue
                        val delta = choice.delta

                        val text = delta.content ?: delta.reasoningContent
                        if (!text.isNullOrEmpty()) emit(StreamEvent.Content(text))

                        delta.toolCalls?.firstOrNull()?.let { tc ->
                            if (tc.id.isNotEmpty()) toolId = tc.id
                            tc.function.name?.let {
                                funcName = it
                                emit(StreamEvent.ToolCallStarted(it))
                            }
                            funcArgsAccumulator.append(tc.function.arguments)
                        }

                        if (choice.finishReason == "tool_calls" && funcName.isNotEmpty()) {
                            emit(StreamEvent.ToolCallComplete(toolId, funcName, funcArgsAccumulator.toString()))
                        }
                    } catch (e: Exception) {
                        AppLog.w("SSE 解析跳过: ${e.message}")
                    }
                }
                AppLog.d("流读取完成")
            }
        } catch (e: Exception) {
            AppLog.e("连接异常: ${e.message}")
            emit(StreamEvent.Error("连接异常: ${e.localizedMessage}"))
        }
    }
}