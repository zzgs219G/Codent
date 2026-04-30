package com.xixin.codent.ui.main

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.github.difflib.DiffUtils
import com.github.difflib.UnifiedDiffUtils
import com.xixin.codent.data.api.*
import com.xixin.codent.data.model.*
import com.xixin.codent.data.repository.SafRepository
import com.xixin.codent.wrapper.log.AppLog
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import kotlinx.serialization.SerializationException

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = SafRepository(application)
    
    private val jsonConfig = Json { 
        ignoreUnknownKeys = true 
        isLenient = true 
        encodeDefaults = true 
    }
    
    private val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) { json(jsonConfig) }
        install(HttpTimeout) {
            requestTimeoutMillis = 180_000
            connectTimeoutMillis = 30_000
            socketTimeoutMillis = 180_000
        }
        install(Logging) {
            logger = object : Logger {
                override fun log(message: String) {
                    Log.i("Codent-Network", message)
                    AppLog.d("[Network] $message")
                }
            }
            level = LogLevel.INFO
        }
    }
    
    private val aiService = AiApiService(httpClient)
    
    private val _uiState = MutableStateFlow(WorkspaceState())
    val uiState: StateFlow<WorkspaceState> = _uiState.asStateFlow()

    private val toolParser = Json { ignoreUnknownKeys = true }

    private val agentTools = listOf(
        Tool(
            function = FunctionDef(
                name = "search_keyword",
                description = "全局搜索：在整个项目中搜索代码关键字。返回文件路径列表。",
                parameters = buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("keyword") { put("type", "string") }
                    }
                    putJsonArray("required") { add(JsonPrimitive("keyword")) }
                }
            )
        ),
        Tool(
            function = FunctionDef(
                name = "list_directory",
                description = "目录浏览器：列出指定目录下的内容。根目录传 ''。",
                parameters = buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("path") { put("type", "string") }
                    }
                    putJsonArray("required") { add(JsonPrimitive("path")) }
                }
            )
        ),
        Tool(
            function = FunctionDef(
                name = "read_file",
                description = "阅读源码：读取指定路径的文件完整内容。",
                parameters = buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("path") { put("type", "string") }
                    }
                    putJsonArray("required") { add(JsonPrimitive("path")) }
                }
            )
        ),
        Tool(
            function = FunctionDef(
                name = "apply_patch",
                description = "提交修改：将修改后的新代码应用到文件。会生成 Diff 供用户确认。",
                parameters = buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("path") { put("type", "string") }
                        putJsonObject("new_content") { put("type", "string") }
                    }
                    putJsonArray("required") { 
                        add(JsonPrimitive("path"))
                        add(JsonPrimitive("new_content")) 
                    }
                }
            )
        )
    )

    init {
        _uiState.update { 
            it.copy(
                apiKey = repository.getApiKey(),
                selectedModel = repository.getSelectedModel(),
                enableThinking = repository.isThinkingEnabled()
            ) 
        }
        AppLog.d("MainViewModel 初始化完成, thinking=${_uiState.value.enableThinking}")
    }

    fun saveConfig(key: String, model: String) {
        repository.saveApiKey(key)
        repository.saveSelectedModel(model)
        _uiState.update { it.copy(apiKey = key, selectedModel = model) }
        AppLog.d("保存配置: model=$model")
    }

    fun saveThinkingEnabled(enabled: Boolean) {
        repository.saveThinkingEnabled(enabled)
        _uiState.update { it.copy(enableThinking = enabled) }
        AppLog.d("思考模式: ${if(enabled) "开启" else "关闭"}")
    }

    fun initWorkspace(uri: Uri) {
        repository.takePersistableUriPermission(uri)
        _uiState.update { it.copy(directoryStack = listOf(uri)) }
        loadDirectory(uri)
        AppLog.d("初始化工作目录: $uri")
    }

    fun navigateIntoFolder(uri: Uri) {
        _uiState.update { it.copy(directoryStack = it.directoryStack + uri) }
        loadDirectory(uri)
    }

    fun navigateBack(): Boolean {
        val stack = _uiState.value.directoryStack
        return if (stack.size > 1) {
            val newStack = stack.dropLast(1)
            _uiState.update { it.copy(directoryStack = newStack) }
            loadDirectory(newStack.last())
            true
        } else {
            _uiState.update { it.copy(directoryStack = emptyList(), currentFiles = emptyList()) }
            false
        }
    }

    private fun loadDirectory(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isSafLoading = true, currentFiles = emptyList()) }
            repository.listFilesFlow(uri).collect { files ->
                _uiState.update { it.copy(currentFiles = files, isSafLoading = false) }
            }
        }
    }

    fun openFile(fileNode: FileNode, onOpenComplete: () -> Unit) {
        viewModelScope.launch {
            _uiState.update { it.copy(selectedFile = fileNode, currentCodeContent = "正在加载...") }
            val content = repository.readFileContent(fileNode.uri)
            _uiState.update { it.copy(currentCodeContent = content) }
            onOpenComplete()
        }
    }

    // ======================== 核心 AI 逻辑 ========================

    fun sendChatMessage(userText: String) {
        val state = _uiState.value
        AppLog.d("发送消息: $userText")
        
        if (state.apiKey.isBlank()) {
            appendMessage(ChatMessage("assistant", "❌ 请先在设置中配置 API Key", isLoading = false))
            return
        }

        val userMsg = ChatMessage("user", userText)
        appendMessage(userMsg)

        val rootUri = state.directoryStack.firstOrNull()
        if (rootUri == null) {
            appendMessage(ChatMessage("assistant", "❌ 请先选择一个项目根目录", isLoading = false))
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isAgentWorking = true) }

            val systemPrompt = "你是一个拥有上帝视角的顶级 Android 架构师 Agent。必须通过工具搜索、阅读，最后修改。"
            val messages = mutableListOf(
                ApiMessage("system", systemPrompt),
                ApiMessage("user", userText)
            )
            var currentChars = systemPrompt.length + userText.length

            var iteration = 0
            var currentMessages = messages
            var consecutiveToolOnlyIterations = 0   // 记录连续只有工具调用无文本的迭代次数

            AppLog.d("开始 AI 循环, model=${state.selectedModel}, thinking=${state.enableThinking}")

            while (iteration < 10 && consecutiveToolOnlyIterations < 3) {   // 最多连续3次纯工具调用
                iteration++
                AppLog.d("--- 迭代 $iteration ---")
                
                // 添加一个空的占位消息（正在思考）
                appendMessage(ChatMessage("assistant", "", isLoading = true))

                var accumulatedText = ""
                val toolCallsBuffer = mutableListOf<ToolCall>()
                var finalUsage: Usage? = null
                var errorOccurred = false
                var hasReceivedContent = false

                val flow = aiService.getAgentCompletionStream(
                    apiKey = state.apiKey,
                    model = state.selectedModel,
                    messages = currentMessages,
                    tools = agentTools,
                    enableThinking = state.enableThinking
                )

                flow.collect { event ->
                    when (event) {
                        is StreamEvent.Content -> {
                            hasReceivedContent = true
                            accumulatedText += event.text
                            updateLastMessage(accumulatedText, isLoading = true, uploadChars = currentChars)
                            AppLog.d("收到 content: ${event.text.take(20)}...")
                        }
                        is StreamEvent.ToolCallStarted -> {
                            val tip = "🤖 调用工具: ${event.functionName} ..."
                            if (!hasReceivedContent && accumulatedText.isEmpty()) {
                                updateLastMessage(tip, isLoading = true, uploadChars = currentChars)
                            } else {
                                accumulatedText += if (accumulatedText.isNotEmpty()) "\n\n$tip" else tip
                                updateLastMessage(accumulatedText, isLoading = true, uploadChars = currentChars)
                            }
                            AppLog.d("工具调用开始: ${event.functionName}")
                        }
                        is StreamEvent.ToolCallComplete -> {
                            toolCallsBuffer.add(
                                ToolCall(
                                    id = event.toolCallId,
                                    function = FunctionCall(
                                        name = event.functionName,
                                        arguments = event.arguments
                                    )
                                )
                            )
                            AppLog.d("工具调用完成: ${event.functionName}, 参数长度=${event.arguments.length}")
                        }
                        is StreamEvent.Done -> {
                            finalUsage = event.usage
                            AppLog.d("流结束, usage=${event.usage}")
                        }
                        is StreamEvent.Error -> {
                            errorOccurred = true
                            AppLog.e("API 错误事件: ${event.message}")
                            updateLastMessage("❌ API 错误: ${event.message}", isLoading = false, uploadChars = currentChars)
                            _uiState.update { it.copy(isAgentWorking = false) }
                        }
                    }
                }

                if (errorOccurred) return@launch

                // 处理空回复
                if (!hasReceivedContent && toolCallsBuffer.isEmpty() && accumulatedText.isBlank()) {
                    AppLog.w("未收到任何 content 且无工具调用")
                    accumulatedText = "（AI 没有返回任何文本，可能是网络问题或模型暂时不可用。请重试或更换模型。）"
                } else if (toolCallsBuffer.isNotEmpty() && accumulatedText.isBlank()) {
                    AppLog.d("只有工具调用，无文本回复")
                    consecutiveToolOnlyIterations++
                    accumulatedText = ""   // 保持为空，让最终回复不显示额外内容
                } else {
                    consecutiveToolOnlyIterations = 0   // 有文本回复，重置计数
                }

                // 更新 UI 消息（如果有文本）
                if (accumulatedText.isNotBlank()) {
                    updateLastMessage(accumulatedText, isLoading = false, uploadChars = currentChars)
                } else {
                    // 如果没有文本，移除占位消息，避免显示空白
                    _uiState.update { state ->
                        val msgs = state.chatMessages.toMutableList()
                        if (msgs.isNotEmpty() && msgs.last().content.isBlank()) {
                            msgs.removeAt(msgs.lastIndex)
                        }
                        state.copy(chatMessages = msgs)
                    }
                }

                finalUsage?.let { usage ->
                    updateLastMessageUsage(usage.promptTokens, usage.completionTokens)
                }

                // 构建 assistant 消息
                val assistantApiMsg = ApiMessage(
                    role = "assistant",
                    content = if (toolCallsBuffer.isNotEmpty()) null else accumulatedText.takeIf { it.isNotBlank() },
                    toolCalls = toolCallsBuffer.takeIf { it.isNotEmpty() },
                    reasoningContent = null
                )
                AppLog.d("Assistant msg: content=${assistantApiMsg.content}, toolCalls=${assistantApiMsg.toolCalls?.size}")
                currentMessages.add(assistantApiMsg)

                if (toolCallsBuffer.isEmpty()) {
                    AppLog.d("无工具调用，结束循环")
                    break
                }

                // 执行工具调用
                var allToolsSucceeded = true
                for (toolCall in toolCallsBuffer) {
                    val result = executeTool(
                        name = toolCall.function.name ?: "",
                        args = toolCall.function.arguments ?: "",
                        rootUri = rootUri
                    )
                    val toolResultMsg = ApiMessage(
                        role = "tool",
                        toolCallId = toolCall.id,
                        content = result,
                        name = toolCall.function.name
                    )
                    AppLog.d("Tool msg: role=${toolResultMsg.role}, toolCallId=${toolResultMsg.toolCallId}, name=${toolResultMsg.name}")
                    currentMessages.add(toolResultMsg)
                    currentChars += result.length
                    if (result.startsWith("工具执行异常") || result.startsWith("错误")) {
                        allToolsSucceeded = false
                    }
                    // 在 UI 中插入一条系统提示（可选）
                    appendMessage(ChatMessage("assistant", "📦 工具 `${toolCall.function.name}` 返回结果，继续分析...", isLoading = false))
                }

                // 如果工具执行全部失败，强制中断循环给出提示
                if (!allToolsSucceeded) {
                    appendMessage(ChatMessage("assistant", "⚠️ 工具调用失败，无法继续分析。请检查项目权限或手动提供信息。", isLoading = false))
                    break
                }
            }

            _uiState.update { it.copy(isAgentWorking = false) }
            if (iteration >= 10) {
                appendMessage(ChatMessage("assistant", "⚠️ 工具调用超过限制，已停止。请尝试更具体的指令。", isLoading = false))
                AppLog.w("达到最大迭代次数 10")
            } else if (consecutiveToolOnlyIterations >= 3) {
                appendMessage(ChatMessage("assistant", "⚠️ 连续多次只有工具调用而无回复，已自动停止。请尝试更具体的指令。", isLoading = false))
                AppLog.w("连续纯工具调用超过3次，已停止")
            }
        }
    }

    private suspend fun executeTool(name: String, args: String, rootUri: Uri): String {
    AppLog.d("执行工具: $name, args=$args")
    
    // 预处理参数：如果包含多个 JSON 对象拼接（如 {"a":1}{"b":2}），只取第一个完整的对象
    val cleanedArgs = run {
        var cleaned = args.trim()
        // 尝试找到第一个完整的 JSON 对象结束位置（简单的栈计数）
        if (cleaned.startsWith('{')) {
            var braceCount = 0
            var endIndex = -1
            for (i in cleaned.indices) {
                when (cleaned[i]) {
                    '{' -> braceCount++
                    '}' -> {
                        braceCount--
                        if (braceCount == 0) {
                            endIndex = i
                            break
                        }
                    }
                }
            }
            if (endIndex > 0 && endIndex < cleaned.length - 1) {
                val firstObject = cleaned.substring(0, endIndex + 1)
                val remaining = cleaned.substring(endIndex + 1).trim()
                if (remaining.isNotEmpty()) {
                    AppLog.w("参数包含多余内容，已截取第一个对象: $firstObject (剩余: $remaining)")
                }
                firstObject
            } else {
                cleaned
            }
        } else {
            cleaned
        }
    }
    
    val jsonElement = runCatching {
        toolParser.parseToJsonElement(cleanedArgs)
    }.getOrElse { error ->
        AppLog.e("参数解析失败: ${error.message}, args=$args")
        return "工具执行异常: 参数格式错误 - ${error.message}\n原始参数: $args"
    }
    
    return try {
        val argObj = jsonElement.jsonObject
        when (name) {
            "search_keyword" -> {
                val keyword = argObj["keyword"]?.jsonPrimitive?.content ?: ""
                repository.searchKeyword(rootUri, keyword)
            }
            "list_directory" -> {
                val path = argObj["path"]?.jsonPrimitive?.content ?: ""
                repository.listDirectoryRelative(rootUri, path)
            }
            "read_file" -> {
                val path = argObj["path"]?.jsonPrimitive?.content ?: ""
                val fileUri = repository.findFileByRelativePath(rootUri, path)
                if (fileUri != null) repository.readFileContent(fileUri) else "错误：找不到 $path"
            }
            "apply_patch" -> {
                val path = argObj["path"]?.jsonPrimitive?.content ?: ""
                val newContent = argObj["new_content"]?.jsonPrimitive?.content ?: ""
                val targetUri = repository.findFileByRelativePath(rootUri, path)
                if (targetUri != null) {
                    val original = repository.readFileContent(targetUri)
                    val originalLines = original.split("\n")
                    val newLines = newContent.split("\n")
                    val patch = DiffUtils.diff(originalLines, newLines)
                    val diffLines = UnifiedDiffUtils.generateUnifiedDiff(path, path, originalLines, patch, 3)
                    val diffText = diffLines.joinToString("\n")
                    _uiState.update { 
                        it.copy(
                            pendingPatch = PatchProposal(targetUri, path, original, diffText.ifBlank { "逻辑重构，字符无变化" }, newContent),
                            isAgentWorking = false 
                        ) 
                    }
                    "已生成预览，等待用户确认"
                } else {
                    "错误：找不到文件 $path"
                }
            }
            else -> "未知工具: $name"
        }
    } catch (e: Exception) {
        AppLog.e("工具执行异常: ${e.message}")
        "工具执行异常: ${e.localizedMessage}"
    }
}

    // ======================== UI 辅助 ========================

    private fun appendMessage(msg: ChatMessage) {
        _uiState.update { state ->
            state.copy(chatMessages = state.chatMessages + msg)
        }
    }

    private fun updateLastMessage(text: String, isLoading: Boolean, uploadChars: Int) {
        _uiState.update { state ->
            val msgs = state.chatMessages.toMutableList()
            if (msgs.isNotEmpty()) {
                val last = msgs.last()
                msgs[msgs.lastIndex] = last.copy(content = text, isLoading = isLoading, uploadChars = uploadChars)
            }
            state.copy(chatMessages = msgs)
        }
    }

    private fun updateLastMessageUsage(promptTokens: Int, completionTokens: Int) {
        _uiState.update { state ->
            val msgs = state.chatMessages.toMutableList()
            if (msgs.isNotEmpty()) {
                val last = msgs.last()
                msgs[msgs.lastIndex] = last.copy(promptTokens = promptTokens, completionTokens = completionTokens)
            }
            state.copy(chatMessages = msgs)
        }
    }

    fun confirmPatch() {
        val patch = _uiState.value.pendingPatch ?: return
        viewModelScope.launch {
            if (repository.overwriteFile(patch.targetFileUri, patch.proposedContent)) {
                appendMessage(ChatMessage("assistant", "✅ 修改已落盘：${patch.targetFileName}"))
                if (_uiState.value.selectedFile?.uri == patch.targetFileUri) {
                    _uiState.update { it.copy(currentCodeContent = patch.proposedContent) }
                }
            } else {
                appendMessage(ChatMessage("assistant", "❌ 写入失败，请检查权限。"))
            }
            _uiState.update { it.copy(pendingPatch = null, isAgentWorking = false) }
        }
    }

    fun rejectPatch() {
        appendMessage(ChatMessage("assistant", "🚫 修改已取消。"))
        _uiState.update { it.copy(pendingPatch = null, isAgentWorking = false) }
    }
}