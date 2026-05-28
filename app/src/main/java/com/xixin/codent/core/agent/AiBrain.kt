// 文件路径: app/src/main/java/com/xixin/codent/core/agent/AiBrain.kt
package com.xixin.codent.core.agent

import android.net.Uri
import com.xixin.codent.data.model.ChatMessage as AppChatMessage
import com.xixin.codent.data.repository.SafRepository
import com.xixin.codent.wrapper.log.AppLog
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.memory.chat.MessageWindowChatMemory
import dev.langchain4j.model.chat.response.ChatResponse          // ← 1.0.0 新包路径
import dev.langchain4j.model.openai.OpenAiStreamingChatModel
import dev.langchain4j.service.AiServices
import dev.langchain4j.service.TokenStream
import dev.langchain4j.service.tool.ToolExecution                  // ← onToolExecuted 参数类型
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

private interface CodentAgentService {
    fun chat(message: String): TokenStream
}

class AiBrain(private val repository: SafRepository) {

    private var cachedProjectTree: String? = null
    private var lastRootUriString: String? = null
    private var lastCacheTimeMs: Long = 0L

    private val agentTools = AgentTools(
        repository   = repository,
        rootUri      = Uri.EMPTY,
        onPatchReady = {}
    )

    fun startConversation(
        rootUri: Uri,
        apiBaseUrl: String,
        apiKey: String,
        model: String,
        enableThinking: Boolean,
        history: List<AppChatMessage>,
        userText: String
    ): Flow<AgentEvent> = callbackFlow {

        val dangerKeywords = listOf("销毁项目", "删除所有", "rm -rf", "清空项目")
        if (dangerKeywords.any { userText.contains(it, ignoreCase = true) }) {
            trySend(AgentEvent.Error(DANGER_ZONE))
            close()
            return@callbackFlow
        }

        val now       = System.currentTimeMillis()
        val uriString = rootUri.toString()
        val projectTree = if (
            cachedProjectTree != null &&
            lastRootUriString == uriString &&
            now - lastCacheTimeMs < 5 * 60 * 1000L
        ) {
            AppLog.d("🌳 [命中缓存] 复用目录树")
            cachedProjectTree!!
        } else {
            repository.generateProjectTree(rootUri, maxDepth = 12).also {
                cachedProjectTree  = it
                lastRootUriString  = uriString
                lastCacheTimeMs    = now
                AppLog.d("🌳 [目录树生成完毕] 长度=${it.length}")
            }
        }

        agentTools.rootUri      = rootUri
        agentTools.onPatchReady = { proposal -> trySend(AgentEvent.PatchProposed(proposal)) }

        val systemPrompt = """
            你是一个顶级 Android 架构师 Agent。
            【项目全局透视图 (含文件大小)】：
            $projectTree
            【红线警告与执行规范】：
            1. 【全图视野】：我已经把项目文件树交给你了，寻找文件时必须优先对照上面的目录树！
            2. 【大小感知与禁止问路】：观察文件名括号中的大小（如 30.5KB）。1KB 约等于 30-40 行代码。绝对严禁调用 find_file 查找已知文件！地图中的 . 代表基准路径。你要找的文件绝对路径 = 基准路径 + /文件名。你必须在脑解中完成拼接，并直接调用 read_file！
            3. 【大胃王读取 (省钱关键)】：严禁进行小于 100 行的"试探性"读取！如果文件 < 3KB，请直接 read_file(1, 100) 一次性读完。如果文件较大，首轮读取建议范围 1-300 行。目标是在 2 轮内解决战斗。
            4. 【静默执行与强制总结】：调用工具时直接输出 JSON。但是，在执行完所有的修改（apply_patch / create_file）后，你必须在最后一轮输出一段中文，总结你修改了什么，让用户在界面上点击确认。严禁静默结束！
            5. 【精准替换】：修改代码 apply_patch 时 search_string 必须完全复制原文。
            6. 【连击协同】：你具备一次性修改多个文件的能力。如果需求涉及多个类，请连续调用多次 apply_patch。
            7. 【单次上限】：单次读取文件 read_file 不得超过 800 行。
            8. 仅使用纯文本回复，禁止扮演用户。
        """.trimIndent()

        val lcHistory: List<ChatMessage> = history.mapNotNull { msg ->
            when (msg.role) {
                "user"      -> UserMessage.from(msg.content)
                "assistant" -> AiMessage.from(msg.content)
                else        -> null
            }
        }

        val cleanBaseUrl = apiBaseUrl
            .removeSuffix("/chat/completions")
            .trimEnd('/')

        val streamingModel = OpenAiStreamingChatModel.builder()
            .baseUrl(cleanBaseUrl)
            .apiKey(apiKey)
            .modelName(model)
            .build()

        val memory = MessageWindowChatMemory.withMaxMessages(60)
        memory.add(SystemMessage.from(systemPrompt))
        lcHistory.forEach { memory.add(it) }

        val agentService = AiServices.builder(CodentAgentService::class.java)
            .streamingChatModel(streamingModel)   // ← 1.0.0 新方法名
            .tools(agentTools)
            .chatMemory(memory)
            .build()

        var accumulatedText       = ""
        var accumulatedReasoning  = ""
        var totalPromptTokens     = 0
        var totalCompletionTokens = 0

        AppLog.d("AiBrain: 开始对话 | model=$model | user=${userText.take(60)}")

        agentService.chat(userText)
            .onPartialResponse { token ->              // ← 替换废弃的 onNext
                accumulatedText += token
                trySend(AgentEvent.ContentUpdate(
                    text        = accumulatedText,
                    reasoning   = accumulatedReasoning,
                    isLoading   = true,
                    uploadChars = 0
                ))
            }
            .onToolExecuted { toolExecution: ToolExecution ->   // ← 显式类型，消灭歧义
                val tip = "> 🤖 正在调度工具: ${toolExecution.request().name()} ..."
                accumulatedReasoning += if (accumulatedReasoning.isNotEmpty()) "\n\n$tip" else tip
                AppLog.d("🔧 [Tool 执行完毕]: ${toolExecution.request().name()}")
                trySend(AgentEvent.ContentUpdate(
                    text        = accumulatedText,
                    reasoning   = accumulatedReasoning,
                    isLoading   = true,
                    uploadChars = 0
                ))
            }
            .onCompleteResponse { response: ChatResponse ->      // ← 新类型 ChatResponse
                response.tokenUsage()?.let { usage ->
                    totalPromptTokens     += usage.inputTokenCount()  ?: 0
                    totalCompletionTokens += usage.outputTokenCount() ?: 0
                    AppLog.d("✅ 对话完成 | prompt=$totalPromptTokens | completion=$totalCompletionTokens")
                    trySend(AgentEvent.UsageUpdate(totalPromptTokens, totalCompletionTokens))
                }
                trySend(AgentEvent.ContentUpdate(
                    text        = accumulatedText,
                    reasoning   = accumulatedReasoning,
                    isLoading   = false,
                    uploadChars = 0
                ))
                close()
            }
            .onError { error ->
                AppLog.e("❌ AiBrain 流错误: ${error.message}")
                trySend(AgentEvent.Error(error.message ?: "未知错误"))
                close(error)
            }
            .start()

        awaitClose { AppLog.d("AiBrain: Flow 已关闭") }
    }
}