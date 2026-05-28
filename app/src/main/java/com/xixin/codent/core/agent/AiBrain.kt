package com.xixin.codent.core.agent

import com.xixin.codent.data.model.ChatMessage as AppChatMessage
import com.xixin.codent.data.repository.LocalFileRepository
import com.xixin.codent.wrapper.log.AppLog
import com.aallam.openai.api.BetaOpenAI
import com.aallam.openai.api.chat.*
import com.aallam.openai.api.core.Usage // 🔥 核心修复：漏掉的 Token 统计类导包补上了！
import com.aallam.openai.api.http.Timeout
import com.aallam.openai.api.model.ModelId
import com.aallam.openai.client.OpenAI
import com.aallam.openai.client.OpenAIConfig
import com.aallam.openai.client.OpenAIHost
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

class AiBrain(private val repository: LocalFileRepository) {
    private var cachedProjectTree: String? = null
    private var lastRootPath: String? = null
    private var lastCacheTimeMs: Long = 0L

    private val agentTools = AgentTools(
        repository   = repository,
        rootPath     = "",
        onPatchReady = {}
    )

    @Serializable
    private data class ToolCallInstruction(
        val name: String = "",
        val arguments: JsonObject = JsonObject(emptyMap())
    )

    @OptIn(BetaOpenAI::class)
    fun startConversation(
        rootPath: String,
        apiBaseUrl: String,
        apiKey: String,
        model: String,
        enableThinking: Boolean,
        history: List<AppChatMessage>,
        userText: String
    ): Flow<AgentEvent> = callbackFlow {
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
                }
            }

            agentTools.rootPath = rootPath
            agentTools.onPatchReady = { proposal -> trySend(AgentEvent.PatchProposed(proposal)) }

            val systemPrompt = """
                你是一个顶级的 Android 架构师 AI 编程 Agent。你可以通过调用本地工具阅读和修改项目源码。
                
                【当前项目相对目录结构】：
                $projectTree
                
                【可用本地工具】：
                1. "readFile": 阅读文件。参数 {"path": "相对路径", "start_line": 1, "end_line": 300}
                2. "searchKeyword": 关键字检索。参数 {"keyword": "..."}
                3. "applyPatch": 精确修改代码。参数 {"path": "...", "search_string": "旧代码", "replace_string": "新代码"}
                4. "createFile": 新建文件。参数 {"path": "...", "content": "..."}
                5. "findFile": 查找文件相对路径。参数 {"file_name": "..."}
                6. "listDirectory": 浏览目录。参数 {"path": "..."}
                
                【核心工作流与红线约束】：
                1. 工具的 path 参数绝对不能以 `/` 开头！
                2. 如果你需要调用工具，请在回复中输出一个且仅包含一个 Markdown JSON 代码块，格式如下：
                ```json
                {
                  "name": "readFile",
                  "arguments": {"path": "app/build.gradle.kts", "start_line": 1, "end_line": 50}
                }
                ```
                3. 每次回复最多只能调用一个工具！系统会将执行结果返回给你，你可以继续思考并调用下一个工具。
                4. 如果你已经完成了所有任务（例如代码修改完毕），或者不需要任何工具就能回答用户，请直接使用自然语言中文回复，绝对不要再输出 ```json 代码块！
            """.trimIndent()

            val config = OpenAIConfig(
                token = apiKey,
                host = OpenAIHost(apiBaseUrl.substringBefore("/chat")),
                timeout = Timeout(request = 180.seconds) 
            )
            val openai = OpenAI(config)

            val messages = mutableListOf<ChatMessage>().apply {
                add(ChatMessage(role = ChatRole.System, content = systemPrompt))
                history.forEach { msg ->
                    add(ChatMessage(role = if (msg.role == "user") ChatRole.User else ChatRole.Assistant, content = msg.content))
                }
                add(ChatMessage(role = ChatRole.User, content = userText))
            }

            val jsonDecoder = Json { ignoreUnknownKeys = true }

            var step = 0
            val maxSteps = 8 
            var isTaskFinished = false
            var totalReasoning = ""

            while (step < maxSteps && !isTaskFinished) {
                step++
                AppLog.d("🔄 [Agent 循环] 第 ${step} 轮思考开始...")
                
                var accumulatedText = ""
                var stepReasoning = ""
                var hasNetworkError = false
                var lastUsage: Usage? = null 

                try {
                    openai.chatCompletions(ChatCompletionRequest(ModelId(model), messages)).collect { chunk ->
                        val choice = chunk.choices.firstOrNull() ?: return@collect
                        val delta = choice.delta ?: return@collect
                        
                        val jsonElement = try { jsonDecoder.encodeToJsonElement(ChatDelta.serializer(), delta).jsonObject } catch (e: Exception) { null }
                        val thinkingToken = jsonElement?.get("reasoning_content")?.jsonPrimitive?.content
                        if (!thinkingToken.isNullOrEmpty()) {
                            stepReasoning += thinkingToken
                            trySend(AgentEvent.ContentUpdate(accumulatedText, totalReasoning + stepReasoning, true, 0))
                        }

                        delta.content?.let { token ->
                            accumulatedText += token
                            trySend(AgentEvent.ContentUpdate(accumulatedText, totalReasoning + stepReasoning, true, 0))
                        }
                        
                        chunk.usage?.let { lastUsage = it }
                    }
                } catch (e: Throwable) {
                    AppLog.e("API 请求异常或超时: ${e.message}")
                    trySend(AgentEvent.Error("API 连接中断: ${e.localizedMessage}"))
                    hasNetworkError = true
                    break
                }

                if (hasNetworkError) break
                totalReasoning += stepReasoning

                val toolRegex = Regex("```json\\s*(\\{.*?\\})\\s*```", RegexOption.DOT_MATCHES_ALL)
                val match = toolRegex.find(accumulatedText)

                if (match != null) {
                    val jsonStr = match.groupValues[1]
                    val instruction = try { jsonDecoder.decodeFromString<ToolCallInstruction>(jsonStr) } catch(e: Exception) { null }

                    if (instruction != null && instruction.name.isNotBlank()) {
                        val name = instruction.name
                        val toolParams = instruction.arguments
                        
                        var rawPath = toolParams["path"]?.jsonPrimitive?.content ?: ""
                        if (rawPath.startsWith("/")) rawPath = rawPath.removePrefix("/")

                        AppLog.i("⚙️ [第 $step 轮调度] 执行本地工具: $name | 参数: $toolParams")
                        val tip = "\n> 🤖 正在调度本地工具: $name ..."
                        totalReasoning += tip
                        trySend(AgentEvent.ContentUpdate(accumulatedText, totalReasoning, true, 0))

                        val result = when (name) {
                            "readFile" -> {
                                val start = toolParams["start_line"]?.jsonPrimitive?.intOrNull ?: 1
                                val end = toolParams["end_line"]?.jsonPrimitive?.intOrNull ?: 300
                                agentTools.readFile(rawPath, start, end)
                            }
                            "searchKeyword" -> {
                                val keyword = toolParams["keyword"]?.jsonPrimitive?.content ?: ""
                                agentTools.searchKeyword(keyword)
                            }
                            "findFile" -> {
                                val fileName = toolParams["file_name"]?.jsonPrimitive?.content ?: ""
                                agentTools.findFile(fileName)
                            }
                            "listDirectory" -> {
                                val path = toolParams["path"]?.jsonPrimitive?.content ?: ""
                                agentTools.listDirectory(path)
                            }
                            "applyPatch" -> {
                                val search = toolParams["search_string"]?.jsonPrimitive?.content ?: ""
                                val replace = toolParams["replace_string"]?.jsonPrimitive?.content ?: ""
                                agentTools.applyPatch(rawPath, search, replace)
                            }
                            "createFile" -> {
                                val content = toolParams["content"]?.jsonPrimitive?.content ?: ""
                                agentTools.createFile(rawPath, content)
                            }
                            else -> "执行失败：系统中不存在名为 $name 的工具。"
                        }

                        AppLog.d("⚙️ [第 $step 轮工具返回] 结果长度=${result.length} 字符")
                        totalReasoning += "\n> ⚙️ 执行结果已返回，继续思考。\n"
                        trySend(AgentEvent.ContentUpdate(accumulatedText, totalReasoning, true, 0))

                        messages.add(ChatMessage(role = ChatRole.Assistant, content = accumulatedText))
                        messages.add(ChatMessage(role = ChatRole.User, content = "系统返回 `$name` 执行结果:\n```\n$result\n```\n请根据结果决定下一步。如果任务完成，请直接回答用户，不要再输出 JSON。"))
                        
                    } else {
                        AppLog.w("⚠️ 工具解析失败，已请求 AI 重试")
                        messages.add(ChatMessage(role = ChatRole.Assistant, content = accumulatedText))
                        messages.add(ChatMessage(role = ChatRole.User, content = "系统拦截：你的 JSON 参数解析失败，请检查语法并重新输出标准的 ```json 块。"))
                    }
                } else {
                    AppLog.i("=================== 🏁 AI 最终任务报表 ===================")
                    AppLog.d("📄 [AI 完整输出内容]:\n$accumulatedText")
                    
                    if (lastUsage != null) {
                        AppLog.i("📊 [Token 真实开销] 💡 输入(Prompt): ${lastUsage?.promptTokens} | ✍️ 输出(Completion): ${lastUsage?.completionTokens} | 💰 总计: ${lastUsage?.totalTokens}")
                    } else {
                        val estPrompt = messages.sumOf { it.content?.length ?: 0 } / 2
                        val estCompletion = accumulatedText.length / 2
                        AppLog.i("📊 [Token 本地粗估] 💡 上下文输入约: $estPrompt tokens | ✍️ 本轮内容生成约: $estCompletion tokens")
                    }
                    AppLog.i("========================================================")

                    isTaskFinished = true
                    trySend(AgentEvent.ContentUpdate(accumulatedText, totalReasoning, false, 0))
                }
            }

            if (!isTaskFinished && step >= maxSteps) {
                AppLog.w("⚠️ 达到最大思考深度，强制截断")
                trySend(AgentEvent.ContentUpdate("执行已达到最大步骤限制 ($maxSteps 次)，已强制停止。请用户审查当前成果。", totalReasoning, false, 0))
            }

            close() 

        } catch (e: Throwable) {
            AppLog.e("核心调度致命崩溃: ${e.stackTraceToString()}")
            trySend(AgentEvent.Error("调度异常: ${e.localizedMessage}"))
            close(e)
        }
        awaitClose { AppLog.d("AiBrain: Flow 已挂起并最终关闭") }
    }
}
