package com.xixin.codent.data.api

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

@Serializable data class Tool(val type: String = "function", val function: FunctionDef)
@Serializable data class FunctionDef(val name: String, val description: String, val parameters: JsonElement)
@Serializable data class ToolCall(val id: String = "", val type: String = "function", val function: FunctionCall)
@Serializable data class FunctionCall(val name: String? = null, val arguments: String = "")

@Serializable data class ApiMessage(
    val role: String,
    val content: String? = null,
    @SerialName("reasoning_content") val reasoningContent: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ToolCall>? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null
)

@Serializable data class ChatRequest(
    val model: String,
    val messages: List<ApiMessage>,
    val stream: Boolean = true,
    @SerialName("stream_options") val streamOptions: JsonObject = buildJsonObject { put("include_usage", true) },
    val tools: List<Tool>? = null
)

@Serializable data class ChunkResponse(val choices: List<ChunkChoice> = emptyList(), val usage: Usage? = null)
@Serializable data class ChunkChoice(val delta: ApiMessage, @SerialName("finish_reason") val finishReason: String? = null)
@Serializable data class Usage(@SerialName("prompt_tokens") val promptTokens: Int = 0, @SerialName("completion_tokens") val completionTokens: Int = 0)

sealed class StreamEvent {
    data class Content(val text: String) : StreamEvent()
    data class ToolCallStarted(val functionName: String) : StreamEvent()
    data class ToolCallComplete(val toolCallId: String, val functionName: String, val arguments: String) : StreamEvent()
    data class Done(val usage: Usage?) : StreamEvent()
    data class Error(val message: String) : StreamEvent()
}

class AiApiService(private val client: HttpClient) {
    private val baseUrl = "https://api.deepseek.com/chat/completions"
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun getAgentCompletionStream(apiKey: String, model: String, messages: List<ApiMessage>, tools: List<Tool>? = null): Flow<StreamEvent> = flow {
        try {
            client.preparePost(baseUrl) {
                header(HttpHeaders.Authorization, "Bearer $apiKey")
                contentType(ContentType.Application.Json)
                setBody(ChatRequest(model = model, messages = messages, tools = tools, stream = true))
            }.execute { response ->
                if (!response.status.isSuccess()) {
                    emit(StreamEvent.Error("HTTP 错误: ${response.status.value}"))
                    return@execute
                }

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
                        
                        // 1. 发射 Usage (账单)
                        if (chunk.usage != null) emit(StreamEvent.Done(chunk.usage))

                        val choice = chunk.choices.firstOrNull() ?: continue
                        val delta = choice.delta

                        // 2. 发射打字机内容
                        val text = delta.content ?: delta.reasoningContent
                        if (!text.isNullOrEmpty()) emit(StreamEvent.Content(text))

                        // 3. 拼装工具调用碎片
                        delta.toolCalls?.firstOrNull()?.let { tc ->
                            if (tc.id.isNotEmpty()) toolId = tc.id
                            tc.function.name?.let {
                                funcName = it
                                emit(StreamEvent.ToolCallStarted(it))
                            }
                            funcArgsAccumulator.append(tc.function.arguments)
                        }

                        // 4. 工具调用判定结束
                        if (choice.finishReason == "tool_calls") {
                            emit(StreamEvent.ToolCallComplete(toolId, funcName, funcArgsAccumulator.toString()))
                        }
                    } catch (e: Exception) {
                        // 忽略脏数据分块
                    }
                }
            }
        } catch (e: Exception) {
            emit(StreamEvent.Error("连接异常: ${e.localizedMessage}"))
        }
    }
}