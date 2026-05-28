package com.xixin.codent.core.agent

import com.xixin.codent.data.model.ChatMessage as AppChatMessage
import com.xixin.codent.data.repository.LocalFileRepository
import com.xixin.codent.wrapper.log.AppLog
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.memory.chat.MessageWindowChatMemory
import dev.langchain4j.model.chat.response.ChatResponse
import dev.langchain4j.model.openai.OpenAiStreamingChatModel
import dev.langchain4j.service.AiServices
import dev.langchain4j.service.TokenStream
import dev.langchain4j.service.tool.ToolExecution
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

private interface CodentAgentService {
    fun chat(message: String): TokenStream
}

class AiBrain(private val repository: LocalFileRepository) {
    private var cachedProjectTree: String? = null
    private var lastRootPath: String? = null
    private var lastCacheTimeMs: Long = 0L

    private val agentTools = AgentTools(
        repository   = repository,
        rootPath     = "",
        onPatchReady = {}
    )

    fun startConversation(
        rootPath: String,
        apiBaseUrl: String,
        apiKey: String,
        model: String,
        enableThinking: Boolean,
        history: List<AppChatMessage>,
        userText: String
    ): Flow<AgentEvent> = callbackFlow {
        // 🔥 核心修改：增加最高级别的 try-catch 拦截，死保 App 不闪退
        try {
            val dangerKeywords = listOf("销毁项目", "删除所有", "rm -rf", "清空项目")
            if (dangerKeywords.any { userText.contains(it, ignoreCase = true) }) {
                trySend(AgentEvent.Error(DANGER_ZONE))
                close()
                return@callbackFlow
            }

            val now = System.currentTimeMillis()
            val projectTree = if (
                cachedProjectTree != null &&
                lastRootPath == rootPath &&
                now - lastCacheTimeMs < 5 * 60 * 1000L
            ) {
                AppLog.d("🌳 [命中缓存] 复用目录树")
                cachedProjectTree!!
            } else {
                repository.generateProjectTree(rootPath, maxDepth = 12).also {
                    cachedProjectTree  = it
                    lastRootPath       = rootPath
                    lastCacheTimeMs    = now
                    AppLog.d("🌳 [目录树生成完毕] 长度=${it.length}")
                }
            }

            agentTools.rootPath = rootPath
            agentTools.onPatchReady = { proposal -> trySend(AgentEvent.PatchProposed(proposal)) }

            val systemPrompt = """
                你是一个顶级 Android 架构师 Agent。
                【项目全局透视图 (含文件大小)】：
                $projectTree
                【红线警告与执行规范】：
                1. 【全图视野】：我已经把项目文件树交给你了，寻找文件时必须优先对照上面的目录树！
                2. 【大小感知与禁止问路】：严禁调用 find_file 查找已知文件！你要找的文件绝对路径 = 根目录 + /文件名。直接调用 read_file！
                3. 【静默执行与强制总结】：调用工具时直接输出 JSON。全部改完后，必须在最后一轮输出一段中文总结。
                4. 仅使用纯文本回复，禁止扮演用户。
            """.trimIndent()

            val lcHistory: List<ChatMessage> = history.mapNotNull { msg ->
                when (msg.role) {
                    "user"      -> UserMessage.from(msg.content)
                    "assistant" -> AiMessage.from(msg.content)
                    else        -> null
                }
            }

            val cleanBaseUrl = apiBaseUrl.removeSuffix("/chat/completions").trimEnd('/')
            val streamingModel = OpenAiStreamingChatModel.builder()
                .baseUrl(cleanBaseUrl)
                .apiKey(apiKey)
                .modelName(model)
                .build()

            val memory = MessageWindowChatMemory.withMaxMessages(60)
            memory.add(SystemMessage.from(systemPrompt))
            lcHistory.forEach { memory.add(it) }

            val agentService = AiServices.builder(CodentAgentService::class.java)
                .streamingChatModel(streamingModel)
                .tools(agentTools)
                .chatMemory(memory)
                .build()

            var accumulatedText       = ""
            var accumulatedReasoning  = ""
            var totalPromptTokens     = 0
            var totalCompletionTokens = 0

            agentService.chat(userText)
                .onPartialResponse { token ->
                    accumulatedText += token
                    trySend(AgentEvent.ContentUpdate(accumulatedText, accumulatedReasoning, true, 0))
                }
                .onToolExecuted { toolExecution: ToolExecution ->
                    val tip = "> 🤖 正在调度工具: ${toolExecution.request().name()} ..."
                    accumulatedReasoning += if (accumulatedReasoning.isNotEmpty()) "\n\n$tip" else tip
                    trySend(AgentEvent.ContentUpdate(accumulatedText, accumulatedReasoning, true, 0))
                }
                .onCompleteResponse { response: ChatResponse ->
                    response.tokenUsage()?.let { usage ->
                        totalPromptTokens     += usage.inputTokenCount()  ?: 0
                        totalCompletionTokens += usage.outputTokenCount() ?: 0
                        trySend(AgentEvent.UsageUpdate(totalPromptTokens, totalCompletionTokens))
                    }
                    trySend(AgentEvent.ContentUpdate(accumulatedText, accumulatedReasoning, false, 0))
                    close()
                }
                .onError { error ->
                    trySend(AgentEvent.Error(error.message ?: "未知错误"))
                    close(error)
                }
                .start()
                
        } catch (e: Throwable) {
            // 🔥 拦截到了！把原本会导致闪退的异常，化作界面上的一条红色警告
            AppLog.e("AiBrain 致命崩溃: ${e.stackTraceToString()}")
            trySend(AgentEvent.Error("核心调度崩溃: ${e.message ?: e.javaClass.simpleName}"))
            close(e)
        }

        awaitClose { AppLog.d("AiBrain: Flow 已关闭") }
    }
}
