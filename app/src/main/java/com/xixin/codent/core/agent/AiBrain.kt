package com.xixin.codent.core.agent

import com.xixin.codent.data.model.ChatMessage as AppChatMessage
import com.xixin.codent.data.repository.LocalFileRepository
import com.xixin.codent.wrapper.log.AppLog
import com.aallam.openai.api.BetaOpenAI
import com.aallam.openai.api.chat.*
import com.aallam.openai.api.core.Usage
import com.aallam.openai.api.http.Timeout
import com.aallam.openai.api.model.ModelId
import com.aallam.openai.client.OpenAI
import com.aallam.openai.client.OpenAIConfig
import com.aallam.openai.client.OpenAIHost
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

// ── 错误分类器，让报错信息对用户更友好 ─────────────────────────
private fun classifyApiError(message: String?): String {
    val msg = message?.lowercase() ?: ""
    return when {
        msg.contains("503") || msg.contains("service_unavailable") || msg.contains("service is too busy") ->
            "⚠️ DeepSeek 服务当前过载 (503)。\n\n建议：\n① 切换到「设置」中将模型改为 `deepseek-chat`（更稳定）\n② 或稍等 1~2 分钟后重试"
        msg.contains("401") || msg.contains("unauthorized") || msg.contains("invalid api key") ->
            "❌ API Key 无效或已过期，请在「设置」中重新填写正确的 Key"
        msg.contains("429") || msg.contains("rate limit") || msg.contains("too many requests") ->
            "⚠️ 请求频率超限 (429)，请稍等片刻后重试"
        msg.contains("timeout") || msg.contains("timed out") ->
            "⏱️ 请求超时，网络可能不稳定，请重试"
        msg.contains("connection") || msg.contains("connect") ->
            "🔌 无法连接到 API 服务器，请检查网络或 Base URL 是否正确"
        msg.contains("context_length_exceeded") || msg.contains("maximum context") ->
            "📄 对话历史太长，Token 超限。建议点击「清空记忆」后重新提问"
        else -> "API 连接中断: ${message ?: "未知错误"}"
    }
}

// ── 解析 OpenAI 兼容格式的 host ──────────────────────────────
private fun parseHost(apiBaseUrl: String): String {
    // 兼容用户填写各种格式：
    // https://api.deepseek.com/chat/completions  → https://api.deepseek.com
    // https://api.deepseek.com/v1/chat/completions → https://api.deepseek.com
    // https://api.deepseek.com/v1 → https://api.deepseek.com
    // https://api.deepseek.com → https://api.deepseek.com
    return apiBaseUrl
        .substringBefore("/v1")
        .substringBefore("/chat")
        .trimEnd('/')
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
            // ── 安全拦截 ─────────────────────────────────────────
            val dangerKeywords = listOf("销毁项目", "删除所有", "rm -rf", "清空项目")
            if (dangerKeywords.any { userText.contains(it, ignoreCase = true) }) {
                trySend(AgentEvent.Error(DANGER_ZONE))
                close()
                return@callbackFlow
            }

            // ── 项目目录树（带缓存，5分钟失效）─────────────────────
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

            agentTools.rootPath     = rootPath
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

            // ── 构建 OpenAI 客户端（自动解析 host）────────────────
            val resolvedHost = parseHost(apiBaseUrl)
            AppLog.d("🌐 [API Host] 解析结果: $resolvedHost (原始: $apiBaseUrl)")

            val config = OpenAIConfig(
                token   = apiKey,
                host    = OpenAIHost(resolvedHost),
                timeout = Timeout(request = 180.seconds)
            )
            val openai = OpenAI(config)

            val messages = mutableListOf<ChatMessage>().apply {
                add(ChatMessage(role = ChatRole.System, content = systemPrompt))
                history.forEach { msg ->
                    add(ChatMessage(
                        role    = if (msg.role == "user") ChatRole.User else ChatRole.Assistant,
                        content = msg.content
                    ))
                }
                add(ChatMessage(role = ChatRole.User, content = userText))
            }

            val jsonDecoder     = Json { ignoreUnknownKeys = true }
            var step            = 0
            val maxSteps        = 8
            var isTaskFinished  = false
            var totalReasoning  = ""

            // ── 主 Agent 循环 ─────────────────────────────────────
            while (step < maxSteps && !isTaskFinished) {
                step++
                AppLog.d("🔄 [Agent 循环] 第 ${step} 轮思考开始...")

                var accumulatedText  = ""
                var stepReasoning    = ""
                var hasNetworkError  = false
                var lastUsage: Usage? = null

                // 带指数退避的重试（最多 2 次）
                val maxRetries = 2
                var retryCount = 0
                var retrySuccess = false

                while (retryCount <= maxRetries) {
                    try {
                        openai.chatCompletions(
                            ChatCompletionRequest(ModelId(model), messages)
                        ).collect { chunk ->
                            val choice = chunk.choices.firstOrNull() ?: return@collect
                            val delta  = choice.delta  ?: return@collect

                            val jsonElement = try {
                                jsonDecoder.encodeToJsonElement(ChatDelta.serializer(), delta).jsonObject
                            } catch (e: Exception) { null }

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
                        retrySuccess = true
                        break // 请求成功，跳出重试循环

                    } catch (e: Throwable) {
                        val rawMsg = e.localizedMessage ?: e.message ?: "未知错误"
                        AppLog.e("API 请求异常 (第 ${retryCount + 1} 次): $rawMsg")

                        // 503 / 429 / 超时 才值得重试
                        val isRetryable = rawMsg.lowercase().let {
                            it.contains("503") || it.contains("429") ||
                            it.contains("timeout") || it.contains("service_unavailable") ||
                            it.contains("service is too busy")
                        }

                        if (isRetryable && retryCount < maxRetries) {
                            retryCount++
                            val waitMs = 2000L * retryCount // 2s, 4s
                            AppLog.w("⏳ [重试] 等待 ${waitMs}ms 后第 $retryCount 次重试...")
                            trySend(AgentEvent.ContentUpdate(
                                accumulatedText,
                                totalReasoning + "\n> ⏳ 服务繁忙，${waitMs / 1000}秒后自动重试 ($retryCount/$maxRetries)...\n",
                                true, 0
                            ))
                            delay(waitMs)
                            accumulatedText = "" // 重试前清空，防止重复内容
                            stepReasoning   = ""
                        } else {
                            // 不可重试，或已用完重试次数
                            val friendlyError = classifyApiError(rawMsg)
                            trySend(AgentEvent.Error(friendlyError))
                            hasNetworkError = true
                            break
                        }
                    }
                }

                if (!retrySuccess && hasNetworkError) break

                totalReasoning += stepReasoning

                // ── 工具调用解析 ────────────────────────────────────
                val toolRegex = Regex("```json\\s*(\\{.*?\\})\\s*```", RegexOption.DOT_MATCHES_ALL)
                val match     = toolRegex.find(accumulatedText)

                if (match != null) {
                    val jsonStr     = match.groupValues[1]
                    val instruction = try {
                        jsonDecoder.decodeFromString<ToolCallInstruction>(jsonStr)
                    } catch (e: Exception) { null }

                    if (instruction != null && instruction.name.isNotBlank()) {
                        val name       = instruction.name
                        val toolParams = instruction.arguments
                        var rawPath    = toolParams["path"]?.jsonPrimitive?.content ?: ""
                        if (rawPath.startsWith("/")) rawPath = rawPath.removePrefix("/")

                        AppLog.i("⚙️ [第 $step 轮调度] 执行本地工具: $name | 参数: $toolParams")
                        val tip = "\n> 🤖 正在调度本地工具: $name ..."
                        totalReasoning += tip
                        trySend(AgentEvent.ContentUpdate(accumulatedText, totalReasoning, true, 0))

                        val result = when (name) {
                            "readFile" -> {
                                val start = toolParams["start_line"]?.jsonPrimitive?.intOrNull ?: 1
                                val end   = toolParams["end_line"]?.jsonPrimitive?.intOrNull   ?: 300
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
                                val search  = toolParams["search_string"]?.jsonPrimitive?.content ?: ""
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
                        messages.add(ChatMessage(
                            role    = ChatRole.User,
                            content = "系统返回 `$name` 执行结果:\n```\n$result\n```\n请根据结果决定下一步。如果任务完成，请直接回答用户，不要再输出 JSON。"
                        ))
                    } else {
                        AppLog.w("⚠️ 工具解析失败，已请求 AI 重试")
                        messages.add(ChatMessage(role = ChatRole.Assistant, content = accumulatedText))
                        messages.add(ChatMessage(
                            role    = ChatRole.User,
                            content = "系统拦截：你的 JSON 参数解析失败，请检查语法并重新输出标准的 ```json 块。"
                        ))
                    }
                } else {
                    // ── 任务完成 ─────────────────────────────────────
                    AppLog.i("=================== 🏁 AI 最终任务报表 ===================")
                    AppLog.d("📄 [AI 完整输出内容]:\n$accumulatedText")
                    if (lastUsage != null) {
                        AppLog.i("📊 [Token 真实开销] 💡 输入: ${lastUsage?.promptTokens} | ✍️ 输出: ${lastUsage?.completionTokens} | 💰 总计: ${lastUsage?.totalTokens}")
                    } else {
                        val estPrompt     = messages.sumOf { it.content?.length ?: 0 } / 2
                        val estCompletion = accumulatedText.length / 2
                        AppLog.i("📊 [Token 粗估] 上下文: ~$estPrompt | 本轮: ~$estCompletion")
                    }
                    AppLog.i("========================================================")
                    isTaskFinished = true
                    trySend(AgentEvent.ContentUpdate(accumulatedText, totalReasoning, false, 0))
                }
            }

            if (!isTaskFinished && step >= maxSteps) {
                AppLog.w("⚠️ 达到最大思考深度，强制截断")
                trySend(AgentEvent.ContentUpdate(
                    "执行已达到最大步骤限制 ($maxSteps 次)，已强制停止。请用户审查当前成果。",
                    totalReasoning, false, 0
                ))
            }

            close()

        } catch (e: Throwable) {
            AppLog.e("核心调度致命崩溃: ${e.stackTraceToString()}")
            trySend(AgentEvent.Error(classifyApiError(e.localizedMessage)))
            close(e)
        }

        awaitClose { AppLog.d("AiBrain: Flow 已挂起并最终关闭") }
    }
}
