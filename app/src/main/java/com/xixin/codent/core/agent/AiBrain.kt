package com.xixin.codent.core.agent

import com.xixin.codent.data.model.ChatMessage as AppChatMessage
import com.xixin.codent.data.repository.LocalFileRepository
import com.xixin.codent.wrapper.log.AppLog
import com.aallam.openai.api.BetaOpenAI
import com.aallam.openai.api.chat.*
import com.aallam.openai.api.core.Parameters // 🔥 修复点 1：引入原生的 Parameters 结构
import com.aallam.openai.api.http.Timeout
import com.aallam.openai.api.model.ModelId
import com.aallam.openai.client.OpenAI
import com.aallam.openai.client.OpenAIConfig
import com.aallam.openai.client.OpenAIHost
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
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

            val cleanHost = apiBaseUrl
                .removePrefix("https://")
                .removePrefix("http://")
                .substringBefore("/")

            val config = OpenAIConfig(
                token = apiKey,
                host = OpenAIHost(cleanHost),
                timeout = Timeout(request = 60.seconds)
            )
            val openai = OpenAI(config)

            val messages = mutableListOf<ChatMessage>().apply {
                add(ChatMessage(role = ChatRole.System, content = systemPrompt))
                history.forEach { msg ->
                    val role = if (msg.role == "user") ChatRole.User else ChatRole.Assistant
                    add(ChatMessage(role = role, content = msg.content))
                }
                add(ChatMessage(role = ChatRole.User, content = userText))
            }

            // 🔥 修复点 2：显式声明 List<Tool> 类型，并用标准的 Tool/FunctionTool 构造函数替换无法识别的 ChatTool
            val toolsList = listOf<Tool>(
                Tool(
                    type = ToolType.Function,
                    function = FunctionTool(
                        name = "readFile",
                        description = "读取文件的指定行范围。必须同时指定 start_line 和 end_line。",
                        parameters = Parameters(buildJsonObject {
                            put("type", "object")
                            putJsonObject("properties") {
                                putJsonObject("path") { put("type", "string"); put("description", "文件相对路径") }
                                putJsonObject("start_line") { put("type", "integer"); put("description", "起始行号") }
                                putJsonObject("end_line") { put("type", "integer"); put("description", "结束行号") }
                            }
                            putJsonArray("required") { add("path"); add("start_line"); add("end_line") }
                        })
                    )
                ),
                Tool(
                    type = ToolType.Function,
                    function = FunctionTool(
                        name = "searchKeyword",
                        description = "全局搜索代码关键字，返回带行号的匹配片段，最多返回 8 处结果。",
                        parameters = Parameters(buildJsonObject {
                            put("type", "object")
                            putJsonObject("properties") {
                                putJsonObject("keyword") { put("type", "string"); put("description", "要搜索的关键字") }
                            }
                            putJsonArray("required") { add("keyword") }
                        })
                    )
                ),
                Tool(
                    type = ToolType.Function,
                    function = FunctionTool(
                        name = "findFile",
                        description = "通过文件名在整个项目中查找文件的准确相对路径。",
                        parameters = Parameters(buildJsonObject {
                            put("type", "object")
                            putJsonObject("properties") {
                                putJsonObject("file_name") { put("type", "string"); put("description", "文件名") }
                            }
                            putJsonArray("required") { add("file_name") }
                        })
                    )
                ),
                Tool(
                    type = ToolType.Function,
                    function = FunctionTool(
                        name = "listDirectory",
                        description = "列出指定目录的直接子文件和子目录，根目录传空字符串。",
                        parameters = Parameters(buildJsonObject {
                            put("type", "object")
                            putJsonObject("properties") {
                                putJsonObject("path") { put("type", "string"); put("description", "相对于项目根目录的路径") }
                            }
                            putJsonArray("required") { add("path") }
                        })
                    )
                ),
                Tool(
                    type = ToolType.Function,
                    function = FunctionTool(
                        name = "applyPatch",
                        description = "精确替换文件中的一段代码。",
                        parameters = Parameters(buildJsonObject {
                            put("type", "object")
                            putJsonObject("properties") {
                                putJsonObject("path") { put("type", "string"); put("description", "文件相对路径") }
                                putJsonObject("search_string") { put("type", "string"); put("description", "原始代码片段") }
                                putJsonObject("replace_string") { put("type", "string"); put("description", "新代码片段") }
                            }
                            putJsonArray("required") { add("path"); add("search_string"); add("replace_string") }
                        })
                    )
                ),
                Tool(
                    type = ToolType.Function,
                    function = FunctionTool(
                        name = "createFile",
                        description = "新建文件，或在用户确认后覆盖已有文件。",
                        parameters = Parameters(buildJsonObject {
                            put("type", "object")
                            putJsonObject("properties") {
                                putJsonObject("path") { put("type", "string"); put("description", "文件相对路径") }
                                putJsonObject("content") { put("type", "string"); put("description", "文件完整内容") }
                            }
                            putJsonArray("required") { add("path"); add("content") }
                        })
                    )
                )
            )

            val request = ChatCompletionRequest(
                model = ModelId(model),
                messages = messages,
                tools = toolsList
            )

            var accumulatedText = ""
            var accumulatedReasoning = ""
            
            val toolNameMap = mutableMapOf<Int, String>()
            val toolArgumentsMap = mutableMapOf<Int, StringBuilder>()

            openai.chatCompletions(request).collect { chunk ->
                val choice = chunk.choices.firstOrNull() ?: return@collect
                // 🔥 修复点 3：用 ?. 运算符处理可能为空的 delta 碎片
                val delta = choice.delta ?: return@collect 
                
                // 收集流式文本
                delta.content?.let { token ->
                    accumulatedText += token
                    trySend(AgentEvent.ContentUpdate(accumulatedText, accumulatedReasoning, true, 0))
                }

                // 🔥 修复点 4：移除了不存在的 ToolCallDelta，直接对标准的 ToolCall 列表进行安全空校验和收集
                delta.toolCalls?.forEach { toolCall ->
                    val idx = toolCall.index ?: 0
                    toolCall.function?.name?.let { toolNameMap[idx] = it }
                    toolCall.function?.arguments?.let { argToken ->
                        toolArgumentsMap.getOrPut(idx) { StringBuilder() }.append(argToken)
                    }
                }
            }

            if (toolNameMap.isNotEmpty()) {
                for ((idx, name) in toolNameMap) {
                    val argsStr = toolArgumentsMap[idx]?.toString() ?: "{}"
                    val argsJson = try { Json.parseToJsonElement(argsStr).jsonObject } catch (e: Exception) { JsonObject(emptyMap()) }
                    
                    val tip = "> 🤖 正在调度本地工具: $name ..."
                    accumulatedReasoning += if (accumulatedReasoning.isNotEmpty()) "\n\n$tip" else tip
                    trySend(AgentEvent.ContentUpdate(accumulatedText, accumulatedReasoning, true, 0))

                    val result = when (name) {
                        "readFile" -> {
                            val path = argsJson["path"]?.jsonPrimitive?.content ?: ""
                            val start = argsJson["start_line"]?.jsonPrimitive?.int ?: 1
                            val end = argsJson["end_line"]?.jsonPrimitive?.int ?: 1
                            agentTools.readFile(path, start, end)
                        }
                        "searchKeyword" -> {
                            val keyword = argsJson["keyword"]?.jsonPrimitive?.content ?: ""
                            agentTools.searchKeyword(keyword)
                        }
                        "findFile" -> {
                            val fileName = argsJson["file_name"]?.jsonPrimitive?.content ?: ""
                            agentTools.findFile(fileName)
                        }
                        "listDirectory" -> {
                            val path = argsJson["path"]?.jsonPrimitive?.content ?: ""
                            agentTools.listDirectory(path)
                        }
                        "applyPatch" -> {
                            val path = argsJson["path"]?.jsonPrimitive?.content ?: ""
                            val search = argsJson["search_string"]?.jsonPrimitive?.content ?: ""
                            val replace = argsJson["replace_string"]?.jsonPrimitive?.content ?: ""
                            agentTools.applyPatch(path, search, replace)
                        }
                        "createFile" -> {
                            val path = argsJson["path"]?.jsonPrimitive?.content ?: ""
                            val content = argsJson["content"]?.jsonPrimitive?.content ?: ""
                            agentTools.createFile(path, content)
                        }
                        else -> "错误：未知工具名称"
                    }

                    accumulatedReasoning += "\n> ⚙️ 工具执行返回结果:\n$result"
                    trySend(AgentEvent.ContentUpdate(accumulatedText, accumulatedReasoning, true, 0))
                }
            }

            trySend(AgentEvent.ContentUpdate(accumulatedText, accumulatedReasoning, false, 0))
            close()

        } catch (e: Throwable) {
            AppLog.e("Kotlin AI 核心调度崩溃: ${e.stackTraceToString()}")
            trySend(AgentEvent.Error("核心调度异常: ${e.localizedMessage ?: e.javaClass.simpleName}"))
            close(e)
        }

        awaitClose { AppLog.d("AiBrain: Flow 已关闭") }
    }
}
