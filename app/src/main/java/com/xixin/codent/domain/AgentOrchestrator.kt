package com.xixin.codent.domain

import android.net.Uri
import com.xixin.codent.data.api.*
import com.xixin.codent.data.model.PatchProposal
import com.xixin.codent.data.repository.SafRepository
import com.xixin.codent.wrapper.log.AppLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.*

sealed class AgentEvent {
    data class ContentUpdate(val text: String, val reasoning: String, val isLoading: Boolean, val uploadChars: Int) : AgentEvent()
    data class UsageUpdate(val promptTokens: Int, val completionTokens: Int) : AgentEvent()
    data class PatchProposed(val proposal: PatchProposal) : AgentEvent()
    data class Error(val message: String) : AgentEvent()
}

class AgentOrchestrator(
    private val aiService: AiApiService,
    private val repository: SafRepository
) {
    private val toolParser = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private val agentTools = listOf(
        Tool(
            function = FunctionDef(
                name = "search_keyword",
                description = "全局搜索代码关键字。返回带行号匹配片段。",
                parameters = buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") { putJsonObject("keyword") { put("type", "string") } }
                    putJsonArray("required") { add(JsonPrimitive("keyword")) }
                }
            )
        ),
        Tool(
            function = FunctionDef(
                name = "find_file",
                description = "通过文件名全局查找文件的准确路径，速度快于 list_directory。",
                parameters = buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") { putJsonObject("file_name") { put("type", "string") } }
                    putJsonArray("required") { add(JsonPrimitive("file_name")) }
                }
            )
        ),
        Tool(
            function = FunctionDef(
                name = "list_directory",
                description = "列出目录内容，根目录传 ''",
                parameters = buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") { putJsonObject("path") { put("type", "string") } }
                    putJsonArray("required") { add(JsonPrimitive("path")) }
                }
            )
        ),
        Tool(
            function = FunctionDef(
                name = "read_file",
                description = "读取文件片段。必须指定 start_line 和 end_line",
                parameters = buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("path") { put("type", "string") }
                        putJsonObject("start_line") { put("type", "integer") }
                        putJsonObject("end_line") { put("type", "integer") }
                    }
                    putJsonArray("required") {
                        add(JsonPrimitive("path"))
                        add(JsonPrimitive("start_line"))
                        add(JsonPrimitive("end_line"))
                    }
                }
            )
        ),
        Tool(
            function = FunctionDef(
                name = "apply_patch",
                description = "局部修改代码。精确替换 search_string。",
                parameters = buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("path") { put("type", "string") }
                        putJsonObject("search_string") { put("type", "string") }
                        putJsonObject("replace_string") { put("type", "string") }
                    }
                    putJsonArray("required") {
                        add(JsonPrimitive("path"))
                        add(JsonPrimitive("search_string"))
                        add(JsonPrimitive("replace_string"))
                    }
                }
            )
        ),
        Tool(
            function = FunctionDef(
                name = "create_file",
                description = "新建文件。路径必须是相对项目根目录的路径。",
                parameters = buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("path") { put("type", "string") }
                        putJsonObject("content") { put("type", "string") }
                    }
                    putJsonArray("required") {
                        add(JsonPrimitive("path"))
                        add(JsonPrimitive("content"))
                    }
                }
            )
        )
    )

    fun startConversation(
        rootUri: Uri,
        apiBaseUrl: String,
        apiKey: String,
        model: String,
        enableThinking: Boolean,
        history: List<ApiMessage>,
        userText: String
    ): Flow<AgentEvent> = flow {
        
        val systemPrompt = """
            你是一个顶级 Android 架构师 Agent。
            【红线警告】：
            1. 单次读取文件 read_file 不超过 300 行。
            2. 如果不知道文件路径，优先使用 find_file 查找，禁止盲目使用 list_directory 逐级展开。
            3. 修改代码 apply_patch 时 search_string 必须完全复制原文。
            4. 每次对话绝对只允许 1 个 apply_patch 或 create_file，一旦调用必须立刻结束本次生成，等待用户确认！
            5. 仅使用纯文本回复，禁止扮演用户。
        """.trimIndent()

        val apiMessages = mutableListOf<ApiMessage>()
        apiMessages.add(ApiMessage(role = "system", content = systemPrompt))
        apiMessages.addAll(history)
        apiMessages.add(ApiMessage(role = "user", content = userText))

        AppLog.d("runAgentConversation: system=${systemPrompt.length} chars | history=${history.size} | user=$userText")

        var iteration = 0
        var consecutiveToolOnlyIterations = 0
        var totalAccumulatedText = ""
        var totalAccumulatedReasoning = ""
        var totalPromptTokens = 0
        var totalCompletionTokens = 0
        var currentUploadChars = estimateChars(apiMessages)
        var shouldTerminateConversation = false

        while (iteration < 30 && consecutiveToolOnlyIterations < 15 && !shouldTerminateConversation) {
            iteration++
            AppLog.d("Agent Loop: 第 $iteration 轮开始 | apiMessages=${apiMessages.size}")

            val toolCallsBuffer = mutableListOf<ToolCall>()
            var finalUsage: Usage? = null
            var errorOccurred = false
            var hasReceivedContent = false

            val stream = aiService.getAgentCompletionStream(
                apiBaseUrl, apiKey, model, apiMessages, agentTools, enableThinking
            )

            stream.collect { event ->
                when (event) {
                    is StreamEvent.Content -> {
                        hasReceivedContent = true
                        totalAccumulatedText += event.text
                        emit(AgentEvent.ContentUpdate(totalAccumulatedText, totalAccumulatedReasoning, true, currentUploadChars))
                    }

                    is StreamEvent.Reasoning -> {
                        AppLog.d("🤔 [AI 思考]: ${event.text.replace("\n", " ")}")
                        totalAccumulatedReasoning += event.text
                        emit(AgentEvent.ContentUpdate(totalAccumulatedText, totalAccumulatedReasoning, true, currentUploadChars))
                    }

                    is StreamEvent.ToolCallStarted -> {
                        AppLog.d("🔧 [AI 调度工具]: ${event.functionName}")
                        val tip = "> 🤖 正在调度工具: ${event.functionName} ..."
                        totalAccumulatedReasoning += if (totalAccumulatedReasoning.isNotEmpty()) "\n\n$tip" else tip
                        emit(AgentEvent.ContentUpdate(totalAccumulatedText, totalAccumulatedReasoning, true, currentUploadChars))
                    }

                    is StreamEvent.ToolCallComplete -> {
                        toolCallsBuffer.add(
                            ToolCall(
                                index = toolCallsBuffer.size,
                                id = event.toolCallId,
                                function = FunctionCall(name = event.functionName, arguments = event.arguments)
                            )
                        )
                    }

                    is StreamEvent.Done -> finalUsage = event.usage

                    is StreamEvent.Error -> {
                        errorOccurred = true
                        AppLog.e("❌ 流错误: ${event.message}")
                        emit(AgentEvent.Error(event.message))
                    }
                }
            }

            if (errorOccurred) return@flow

            if (!hasReceivedContent && toolCallsBuffer.isNotEmpty()) {
                consecutiveToolOnlyIterations++
            } else {
                consecutiveToolOnlyIterations = 0
            }

            finalUsage?.let {
                totalPromptTokens += it.promptTokens
                totalCompletionTokens += it.completionTokens
                AppLog.d("Agent Loop: usage 累加 -> prompt=$totalPromptTokens | completion=$totalCompletionTokens")
                emit(AgentEvent.UsageUpdate(totalPromptTokens, totalCompletionTokens))
            }

            if (totalAccumulatedText.isBlank() && toolCallsBuffer.isEmpty()) {
                totalAccumulatedText = "（等待分析中...）"
            }

            if (totalAccumulatedText.isNotBlank()) {
                AppLog.d("Agent Loop: 当前回答内容 -> ${totalAccumulatedText.take(500)}")
            }

            apiMessages.add(
                ApiMessage(
                    role = "assistant",
                    content = if (toolCallsBuffer.isNotEmpty()) null else totalAccumulatedText.takeIf { it.isNotBlank() },
                    toolCalls = toolCallsBuffer.takeIf { it.isNotEmpty() }
                )
            )

            if (toolCallsBuffer.isEmpty()) {
                AppLog.d("Agent Loop: 本轮没有工具调用，结束")
                break
            }

            for (toolCall in toolCallsBuffer) {
                val funcName = toolCall.function.name ?: "unknown"
                val argsStr = toolCall.function.arguments

                val displayArgs = buildDisplayArgs(funcName, argsStr)
                totalAccumulatedReasoning += "\n> 🎯 参数: $displayArgs"
                emit(AgentEvent.ContentUpdate(totalAccumulatedText, totalAccumulatedReasoning, true, currentUploadChars))

                val result = executeToolInner(funcName, argsStr, rootUri) { proposal ->
                    emit(AgentEvent.PatchProposed(proposal))
                }

                apiMessages.add(ApiMessage(role = "tool", toolCallId = toolCall.id, content = result, name = funcName))
                currentUploadChars += result.length

                totalAccumulatedReasoning += "\n> 📦 工具返回长度: ${result.length} 字符\n"
                emit(AgentEvent.ContentUpdate(totalAccumulatedText, totalAccumulatedReasoning, true, currentUploadChars))
                
                if (funcName == "apply_patch" || funcName == "create_file") {
                    AppLog.d("⚠️ 检测到关键修改操作 [${funcName}]，强行斩断后续无意义循环以保护 Token。")
                    shouldTerminateConversation = true
                    break
                }
            }
        }

        AppLog.d("runAgentConversation: 结束")
        emit(AgentEvent.ContentUpdate(totalAccumulatedText, totalAccumulatedReasoning, false, currentUploadChars))
    }

    private suspend fun executeToolInner(
        name: String, 
        args: String, 
        rootUri: Uri, 
        onPatchProposed: suspend (PatchProposal) -> Unit
    ): String {
        return try {
            val jsonObj = toolParser.parseToJsonElement(args).jsonObject
            when (name) {
                "search_keyword" -> {
                    val keyword = jsonObj["keyword"]?.jsonPrimitive?.content.orEmpty()
                    AppLog.d("✅ 执行工具: search_keyword($keyword)")
                    repository.searchKeyword(rootUri, keyword)
                }
                "find_file" -> {
                    val fileName = jsonObj["file_name"]?.jsonPrimitive?.content.orEmpty()
                    AppLog.d("✅ 执行工具: find_file($fileName)")
                    repository.findFilesByName(rootUri, fileName)
                }
                "list_directory" -> {
                    val path = jsonObj["path"]?.jsonPrimitive?.content.orEmpty()
                    AppLog.d("✅ 执行工具: list_directory($path)")
                    repository.listDirectoryRelative(rootUri, path)
                }
                "read_file" -> {
                    val path = jsonObj["path"]?.jsonPrimitive?.content.orEmpty()
                    val startLine = jsonObj["start_line"]?.jsonPrimitive?.content?.toIntOrNull()
                    val endLine = jsonObj["end_line"]?.jsonPrimitive?.content?.toIntOrNull()
                    AppLog.d("✅ 执行工具: read_file($path, $startLine, $endLine)")
                    val fileUri = repository.findFileByRelativePath(rootUri, path)
                    if (fileUri == null) "错误：找不到文件 `$path`" else repository.readFileContent(fileUri, startLine, endLine)
                }
                "apply_patch" -> {
                    val path = jsonObj["path"]?.jsonPrimitive?.content.orEmpty()
                    val searchString = jsonObj["search_string"]?.jsonPrimitive?.content.orEmpty()
                    val replaceString = jsonObj["replace_string"]?.jsonPrimitive?.content.orEmpty()
                    AppLog.d("✅ 执行工具: apply_patch 尝试修改文件: $path")

                    val fileUri = repository.findFileByRelativePath(rootUri, path)
                        ?: return "错误：找不到文件 `$path`"

                    val pureOriginal = repository.readRawFileContent(fileUri)
                    if (pureOriginal.startsWith("读取异常") || pureOriginal == "无法读取文件") {
                        return "错误：无法读取原文件内容，无法生成补丁。"
                    }

                    val newContent = replaceFirstExact(pureOriginal, searchString, replaceString)
                        ?: return "错误：search_string 未在原文件中找到，补丁未生成。请确保代码一模一样，不要带行号！"

                    val proposal = PatchProposal(
                        targetFileUri = fileUri,
                        targetFileName = path,
                        originalContent = pureOriginal,
                        diffText = buildPatchPreview(path, pureOriginal, newContent, searchString, replaceString),
                        proposedContent = newContent
                    )
                    
                    // 🔥 这里是你要的 Diff 打印日志
                    AppLog.d("✂️ [Patch Diff 生成成功]:\n--- 🔍 查找 ---\n$searchString\n--- ✨ 替换为 ---\n$replaceString")
                    
                    onPatchProposed(proposal)
                    "✅ 已生成修改方案，等待用户确认后落盘。目标文件: $path"
                }
                "create_file" -> {
                    val path = jsonObj["path"]?.jsonPrimitive?.content.orEmpty()
                    val content = jsonObj["content"]?.jsonPrimitive?.content.orEmpty()
                    AppLog.d("✅ 执行工具: create_file($path)")
                    repository.createFileByRelativePath(rootUri, path, content)
                }
                else -> "未知工具: $name"
            }
        } catch (e: Exception) {
            "工具执行异常: ${e.localizedMessage}"
        }
    }

    private fun estimateChars(messages: List<ApiMessage>): Int =
        messages.sumOf { (it.content?.length ?: 0) + (it.reasoningContent?.length ?: 0) }

    private fun buildDisplayArgs(funcName: String, argsStr: String): String = try {
        val jsonObj = toolParser.parseToJsonElement(argsStr).jsonObject
        when (funcName) {
            "search_keyword" -> "🔍 搜: ${jsonObj["keyword"]?.jsonPrimitive?.content.orEmpty()}"
            "find_file" -> "🗺️ 找: ${jsonObj["file_name"]?.jsonPrimitive?.content.orEmpty()}"
            "list_directory" -> "📂 看: ${jsonObj["path"]?.jsonPrimitive?.content.orEmpty()}"
            "read_file" -> "📄 读: ${jsonObj["path"]?.jsonPrimitive?.content.orEmpty()}"
            "apply_patch" -> "✍️ 改: ${jsonObj["path"]?.jsonPrimitive?.content.orEmpty()}"
            "create_file" -> "✨ 建: ${jsonObj["path"]?.jsonPrimitive?.content.orEmpty()}"
            else -> "执行指令中"
        }
    } catch (_: Exception) { "执行指令中" }

    private fun replaceFirstExact(original: String, searchString: String, replaceString: String): String? {
        if (searchString.isBlank()) return null
        val normalizedOriginal = original.replace("\r\n", "\n")
        val normalizedSearch = searchString.replace("\r\n", "\n")
        
        val regex = Regex(Regex.escape(normalizedSearch))
        val result = regex.find(normalizedOriginal) ?: return null
        
        return normalizedOriginal.replaceRange(result.range, replaceString.replace("\r\n", "\n"))
    }

    private fun buildPatchPreview(fileName: String, original: String, proposed: String, searchString: String, replaceString: String): String {
        return buildString {
            appendLine("文件: $fileName")
            appendLine("----- 建议内容预览 -----")
            appendLine(proposed.take(1200))
        }
    }
}
