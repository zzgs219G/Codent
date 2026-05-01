package com.xixin.codent.data.api

import com.xixin.codent.wrapper.log.AppLog
import io.ktor.client.HttpClient
import io.ktor.http.contentType 
import io.ktor.client.request.header
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
data class Tool(val type: String = "function", val function: FunctionDef)

@Serializable
data class FunctionDef(
    val name: String,
    val description: String,
    val parameters: JsonElement
)

@Serializable
data class ToolCall(
    val index: Int = 0,
    val id: String = "",
    val type: String = "function",
    val function: FunctionCall
)

@Serializable
data class FunctionCall(
    val name: String? = null,
    val arguments: String = ""
)

@Serializable
data class ApiMessage(
    val role: String,
    val content: String? = null,
    @SerialName("reasoning_content") val reasoningContent: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ToolCall>? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
    val name: String? = null
)

@Serializable
data class DeltaMessage(
    val role: String? = null,
    val content: String? = null,
    @SerialName("reasoning_content") val reasoningContent: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ToolCall>? = null
)

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

sealed class StreamEvent {
    data class Content(val text: String) : StreamEvent()
    data class Reasoning(val text: String) : StreamEvent()
    data class ToolCallStarted(val functionName: String) : StreamEvent()
    data class ToolCallComplete(val toolCallId: String, val functionName: String, val arguments: String) : StreamEvent()
    data class Done(val usage: Usage?) : StreamEvent()
    data class Error(val message: String) : StreamEvent()
}

class AiApiService(private val client: HttpClient) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private class ToolCallTracker(
        var id: String = "",
        var name: String = "",
        val args: StringBuilder = StringBuilder(),
        var startedEmitted: Boolean = false
    )

    /**
     * 流式调用模型接口。
     * 这里保留了 AppLog.d，因为你明确要求要能追踪 AI 的每一步行为。
     */
    fun getAgentCompletionStream(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<ApiMessage>,
        tools: List<Tool>? = null,
        enableThinking: Boolean = true
    ): Flow<StreamEvent> = flow {
        AppLog.d("AiApiService: 准备发起请求 -> $baseUrl | model=$model | messages=${messages.size} | tools=${tools?.size ?: 0} | thinking=$enableThinking")

        val thinkingParam = if (enableThinking) {
            buildJsonObject { put("type", "enabled") }
        } else {
            buildJsonObject { put("type", "disabled") }
        }

        try {
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
                    val errorBody = runCatching { response.bodyAsText() }.getOrElse { it.message ?: "unknown error" }
                    AppLog.d("AiApiService: HTTP 失败 -> ${response.status.value} | body=${errorBody.take(500)}")
                    emit(StreamEvent.Error("HTTP ${response.status.value}\n$errorBody"))
                    return@execute
                }

                AppLog.d("AiApiService: HTTP 成功，开始解析 SSE 流")

                val channel = response.bodyAsChannel()
                val trackerMap = linkedMapOf<Int, ToolCallTracker>()

                while (!channel.isClosedForRead) {
                    val line = channel.readUTF8Line() ?: break
                    val trimmed = line.trim()

                    if (trimmed.isEmpty()) continue
                    if (!trimmed.startsWith("data: ")) continue

                    val data = trimmed.removePrefix("data: ").trim()

                    if (data == "[DONE]") {
                        AppLog.d("AiApiService: 收到 [DONE]")
                        break
                    }

                    try {
                        val chunk = json.decodeFromString(ChunkResponse.serializer(), data)

                        chunk.usage?.let {
                            AppLog.d("AiApiService: usage -> prompt=${it.promptTokens}, completion=${it.completionTokens}")
                            emit(StreamEvent.Done(it))
                        }

                        val choice = chunk.choices.firstOrNull() ?: continue
                        val delta = choice.delta

                        if (!delta.reasoningContent.isNullOrBlank()) {
                            emit(StreamEvent.Reasoning(delta.reasoningContent))
                        }

                        if (!delta.content.isNullOrBlank()) {
                            emit(StreamEvent.Content(delta.content))
                        }

                        delta.toolCalls?.forEach { tc ->
                            val tracker = trackerMap.getOrPut(tc.index) { ToolCallTracker() }

                            if (tc.id.isNotBlank()) tracker.id = tc.id

                            tc.function.name?.let { name ->
                                tracker.name = name
                                if (!tracker.startedEmitted) {
                                    tracker.startedEmitted = true
                                    AppLog.d("AiApiService: 工具调用开始 -> $name (id=${tracker.id})")
                                    emit(StreamEvent.ToolCallStarted(name))
                                }
                            }

                            if (tc.function.arguments.isNotEmpty()) {
                                tracker.args.append(tc.function.arguments)
                            }
                        }

                        if (choice.finishReason == "tool_calls" && trackerMap.isNotEmpty()) {
                            trackerMap.values.forEach { tracker ->
                                if (tracker.name.isNotBlank()) {
                                    AppLog.d("AiApiService: 工具参数接收完成 -> ${tracker.name} | id=${tracker.id} | args=${tracker.args}")
                                    emit(
                                        StreamEvent.ToolCallComplete(
                                            toolCallId = tracker.id,
                                            functionName = tracker.name,
                                            arguments = tracker.args.toString()
                                        )
                                    )
                                }
                            }
                        }
                    } catch (e: Exception) {
                        AppLog.d("AiApiService: SSE 片段解析失败 -> ${e.message}")
                    }
                }

                AppLog.d("AiApiService: 流读取结束")
            }
        } catch (e: Exception) {
            AppLog.d("AiApiService: 连接异常 -> ${e.localizedMessage}")
            emit(StreamEvent.Error("连接异常: ${e.localizedMessage ?: "unknown"}"))
        }
    }
}