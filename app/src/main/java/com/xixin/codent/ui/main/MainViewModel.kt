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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

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
            requestTimeoutMillis = 300_000L
            connectTimeoutMillis = 90_000L
            socketTimeoutMillis = 300_000L
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
                description = "全局搜索：在整个项目中搜索代码关键字。返回带行号的文件匹配片段。",
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
                description = "阅读源码：读取文件片段。⚠️ 注意：你必须指定 start_line 和 end_line，单次最多允许读取 300 行！",
                parameters = buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("path") { put("type", "string") }
                        putJsonObject("start_line") { 
                            put("type", "integer")
                            put("description", JsonPrimitive("起始行号 (从1开始)")) 
                        }
                        putJsonObject("end_line") { 
                            put("type", "integer")
                            put("description", JsonPrimitive("结束行号 (最大不要超过起始行+300)")) 
                        }
                    }
                    putJsonArray("required") { 
                        add(JsonPrimitive("path")) 
                        add(JsonPrimitive("start_line")) 
                        add(JsonPrimitive("end_line")) 
                    }
                }
            )
        ),
        // 🔥 全新升级的防爆手术刀机制！
        Tool(
            function = FunctionDef(
                name = "apply_patch",
                description = "局部修改代码：用 replace_string 精确替换文件中的 search_string。",
                parameters = buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("path") { put("type", "string") }
                        putJsonObject("search_string") { 
                            put("type", "string") 
                            put("description", JsonPrimitive("需要被替换的原文片段（必须与源码完全一致，包含完整缩进）")) 
                        }
                        putJsonObject("replace_string") { 
                            put("type", "string") 
                            put("description", JsonPrimitive("修改后的新代码片段")) 
                        }
                    }
                    putJsonArray("required") { 
                        add(JsonPrimitive("path")) 
                        add(JsonPrimitive("search_string")) 
                        add(JsonPrimitive("replace_string")) 
                    }
                }
            )
        )
    )

    init {
        val savedHistory = repository.loadChatHistory()
        _uiState.update { 
            it.copy(
                apiKey = repository.getApiKey(),
                selectedModel = repository.getSelectedModel(),
                enableThinking = repository.isThinkingEnabled(),
                chatMessages = savedHistory
            ) 
        }
        AppLog.d("MainViewModel 初始化完成, 恢复了 ${savedHistory.size} 条历史记忆")
    }

    fun clearChat() {
        _uiState.update { it.copy(chatMessages = emptyList()) }
        viewModelScope.launch(Dispatchers.IO) { repository.clearChatHistory() }
    }

    fun saveConfig(key: String, model: String) {
        repository.saveApiKey(key)
        repository.saveSelectedModel(model)
        _uiState.update { it.copy(apiKey = key, selectedModel = model) }
    }

    fun saveThinkingEnabled(enabled: Boolean) {
        repository.saveThinkingEnabled(enabled)
        _uiState.update { it.copy(enableThinking = enabled) }
    }

    fun initWorkspace(uri: Uri) {
        repository.takePersistableUriPermission(uri)
        _uiState.update { it.copy(directoryStack = listOf(uri)) }
        loadDirectory(uri)
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

    fun sendChatMessage(userText: String) {
        val state = _uiState.value
        
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

            // 🔥 注入全新手术刀认知
            val systemPrompt = """
                你是一个拥有上帝视角的顶级 Android 架构师 Agent。
                【最高红线警告】：
                1. 找代码必须直接使用 search_keyword 全局搜索特征词。
                2. 使用 read_file 读取文件时，必须强制使用 start_line 和 end_line 翻页阅读！
                3. 修改代码时，必须使用 apply_patch 工具！【极其重要】：apply_patch 采用精确匹配替换。你必须在 search_string 中提供与原文完全一致的代码块（包含所有的换行和缩进），然后在 replace_string 中提供新代码。
                4. 除非明确要求长篇解释，否则回复必须【极度简明扼要】，直击痛点。
            """.trimIndent()
            
            val messages = mutableListOf(
                ApiMessage("system", systemPrompt),
                ApiMessage("user", userText)
            )
            var currentChars = systemPrompt.length + userText.length

            var iteration = 0
            var currentMessages = messages
            var consecutiveToolOnlyIterations = 0   

            while (iteration < 20 && consecutiveToolOnlyIterations < 10) {
                iteration++
                
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
                        }
                        is StreamEvent.ToolCallStarted -> {
                            val tip = "🤖 调用工具: ${event.functionName} ..."
                            if (!hasReceivedContent && accumulatedText.isEmpty()) {
                                updateLastMessage(tip, isLoading = true, uploadChars = currentChars)
                            } else {
                                accumulatedText += if (accumulatedText.isNotEmpty()) "\n\n$tip" else tip
                                updateLastMessage(accumulatedText, isLoading = true, uploadChars = currentChars)
                            }
                        }
                        is StreamEvent.ToolCallComplete -> {
                            toolCallsBuffer.add(
                                ToolCall(
                                    index = toolCallsBuffer.size,
                                    id = event.toolCallId,
                                    function = FunctionCall(
                                        name = event.functionName,
                                        arguments = event.arguments
                                    )
                                )
                            )
                        }
                        is StreamEvent.Done -> {
                            finalUsage = event.usage
                        }
                        is StreamEvent.Error -> {
                            errorOccurred = true
                            updateLastMessage("❌ API 错误: ${event.message}", isLoading = false, uploadChars = currentChars)
                            _uiState.update { it.copy(isAgentWorking = false) }
                        }
                    }
                }

                if (errorOccurred) return@launch

                if (accumulatedText.isNotBlank()) {
                    AppLog.d("🤖 AI 本轮回复内容:\n$accumulatedText")
                }
                if (toolCallsBuffer.isNotEmpty()) {
                    AppLog.d("🛠️ AI 本轮准备调用 ${toolCallsBuffer.size} 个工具: ${toolCallsBuffer.joinToString { it.function.name ?: "" }}")
                }

                if (!hasReceivedContent && toolCallsBuffer.isEmpty() && accumulatedText.isBlank()) {
                    accumulatedText = "（AI 没有返回任何文本...）"
                } else if (toolCallsBuffer.isNotEmpty() && accumulatedText.isBlank()) {
                    consecutiveToolOnlyIterations++
                    accumulatedText = ""   
                } else {
                    consecutiveToolOnlyIterations = 0   
                }

                if (accumulatedText.isNotBlank()) {
                    updateLastMessage(accumulatedText, isLoading = false, uploadChars = currentChars)
                } else {
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

                val assistantApiMsg = ApiMessage(
                    role = "assistant",
                    content = if (toolCallsBuffer.isNotEmpty()) null else accumulatedText.takeIf { it.isNotBlank() },
                    toolCalls = toolCallsBuffer.takeIf { it.isNotEmpty() },
                    reasoningContent = null
                )
                currentMessages.add(assistantApiMsg)

                if (toolCallsBuffer.isEmpty()) break

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
                    currentMessages.add(toolResultMsg)
                    currentChars += result.length
                    if (result.startsWith("工具执行异常") || result.startsWith("错误")) {
                        allToolsSucceeded = false
                    }
                    appendMessage(ChatMessage("assistant", "📦 工具 `${toolCall.function.name}` 返回结果，继续分析...", isLoading = false))
                }

                if (!allToolsSucceeded) {
                    appendMessage(ChatMessage("assistant", "⚠️ 工具调用失败，无法继续分析。", isLoading = false))
                    break
                }
            }

            _uiState.update { it.copy(isAgentWorking = false) }
            
            if (iteration >= 20) {
                appendMessage(ChatMessage("assistant", "⚠️ 工具调用超过限制（20次），已停止。", isLoading = false))
            } else if (consecutiveToolOnlyIterations >= 10) {
                appendMessage(ChatMessage("assistant", "⚠️ 连续10次只搜索未说话，被系统打断。", isLoading = false))
            }
        }
    }

    private suspend fun executeTool(name: String, args: String, rootUri: Uri): String {
        val cleanedArgs = run {
            var cleaned = args.trim()
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
                if (endIndex > 0 && endIndex < cleaned.length - 1) cleaned.substring(0, endIndex + 1)
                else cleaned
            } else cleaned
        }
        
        val jsonElement = runCatching {
            toolParser.parseToJsonElement(cleanedArgs)
        }.getOrElse { error ->
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
                    val startLine = argObj["start_line"]?.jsonPrimitive?.intOrNull
                    val endLine = argObj["end_line"]?.jsonPrimitive?.intOrNull
                    val fileUri = repository.findFileByRelativePath(rootUri, path)
                    if (fileUri != null) repository.readFileContent(fileUri, startLine, endLine) else "错误：找不到 $path"
                }
                // 🔥 全新执行逻辑：绕开限制全量读取 -> 执行精确替换 -> 生成 Diff
                "apply_patch" -> {
                    val path = argObj["path"]?.jsonPrimitive?.content ?: ""
                    val searchString = argObj["search_string"]?.jsonPrimitive?.content ?: ""
                    val replaceString = argObj["replace_string"]?.jsonPrimitive?.content ?: ""
                    
                    val targetUri = repository.findFileByRelativePath(rootUri, path)
                    if (targetUri != null) {
                        // 越过 300 行防爆墙，使用底层 API 读取全量原始文件用于内存替换
                        val original = getApplication<Application>().contentResolver
                            .openInputStream(targetUri)?.bufferedReader()?.use { it.readText() } ?: ""
                            
                        if (original.contains(searchString)) {
                            val newContent = original.replace(searchString, replaceString)
                            
                            val cleanOriginalLines = original.lines()
                            val newLines = newContent.lines()
                            val patch = DiffUtils.diff(cleanOriginalLines, newLines)
                            val diffLines = UnifiedDiffUtils.generateUnifiedDiff(path, path, cleanOriginalLines, patch, 3)
                            val diffText = diffLines.joinToString("\n")
                            
                            _uiState.update { 
                                it.copy(
                                    pendingPatch = PatchProposal(targetUri, path, original, diffText.ifBlank { "逻辑重构，无变化" }, newContent),
                                    isAgentWorking = false 
                                ) 
                            }
                            "已生成预览，等待用户确认"
                        } else {
                            "错误：找不到匹配的 search_string。请严格复制原文片段（注意缩进和空行）。"
                        }
                    } else {
                        "错误：找不到文件 $path"
                    }
                }
                else -> "未知工具: $name"
            }
        } catch (e: Exception) {
            "工具执行异常: ${e.localizedMessage}"
        }
    }

    private fun appendMessage(msg: ChatMessage) {
        _uiState.update { state -> state.copy(chatMessages = state.chatMessages + msg) }
        viewModelScope.launch(Dispatchers.IO) { repository.saveChatHistory(_uiState.value.chatMessages) }
    }

    // 🔥 AI 刚才试图给你写的删除消息功能，保留在此！
    fun deleteMessage(index: Int) {
        _uiState.update { state ->
            val msgs = state.chatMessages.toMutableList()
            // 限定只能删除用户自己的消息（日志不让删）
            if (index in msgs.indices && msgs[index].role == "user") {
                msgs.removeAt(index)
                state.copy(chatMessages = msgs)
            } else {
                state
            }
        }
        viewModelScope.launch(Dispatchers.IO) { repository.saveChatHistory(_uiState.value.chatMessages) }
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
        if (!isLoading) {
            viewModelScope.launch(Dispatchers.IO) { repository.saveChatHistory(_uiState.value.chatMessages) }
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
        viewModelScope.launch(Dispatchers.IO) { repository.saveChatHistory(_uiState.value.chatMessages) }
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
