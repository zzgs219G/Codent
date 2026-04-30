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
    
    private val _patchQueue = mutableListOf<PatchProposal>()
    
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
                description = "全局搜索代码关键字。返回带行号的文件匹配片段。",
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
                description = "列出指定目录下的内容。根目录传 ''。",
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
                description = "读取文件片段。必须指定 start_line 和 end_line，单次最多300行！",
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
                description = "局部修改代码：用 replace_string 精确替换 search_string。",
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
            appendMessage(ChatMessage("assistant", "❌ 请先配置 API Key", isLoading = false))
            return
        }

        val userMsg = ChatMessage("user", userText)
        appendMessage(userMsg)

        val rootUri = state.directoryStack.firstOrNull()
        if (rootUri == null) {
            appendMessage(ChatMessage("assistant", "❌ 请先选择项目根目录", isLoading = false))
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isAgentWorking = true) }

            // 🔥 提示词加入防挂起红线
            val systemPrompt = """
                你是一个拥有上帝视角的顶级 Android 架构师 Agent。
                【最高红线警告】：
                1. 找代码必须直接使用 search_keyword 全局搜索。单次读取 read_file 不超过 300 行。
                2. 修改代码必须使用 apply_patch 工具，且 search_string 必须与原文完美匹配。
                3. 【严禁连发补丁】：每一轮对话中，你【最多只能调用 1 次 apply_patch】！如果你需要修改多个地方或多个文件，你必须一次只提交一个，并在对话末尾告诉用户：“请先确认当前修改，确认后请告诉我继续”。绝不允许在一个思考循环里连续轰炸发起修改！
                4. 请仅使用纯文本回复，禁止使用 **加粗** 等 Markdown 符号，代码一律用多行代码块。
            """.trimIndent()
            
            val messages = mutableListOf(
                ApiMessage("system", systemPrompt),
                ApiMessage("user", userText)
            )
            var currentChars = systemPrompt.length + userText.length

            var iteration = 0
            var currentMessages = messages
            var consecutiveToolOnlyIterations = 0   

            var totalAccumulatedText = ""
            var totalAccumulatedReasoning = ""
            var totalPromptTokens = 0
            var totalCompletionTokens = 0

            appendMessage(ChatMessage("assistant", "", isLoading = true))

            while (iteration < 30 && consecutiveToolOnlyIterations < 15) {
                iteration++

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
                            totalAccumulatedText += event.text
                            updateLastMessage(totalAccumulatedText, totalAccumulatedReasoning, isLoading = true, uploadChars = currentChars)
                        }
                        is StreamEvent.Reasoning -> { 
                            totalAccumulatedReasoning += event.text
                            updateLastMessage(totalAccumulatedText, totalAccumulatedReasoning, isLoading = true, uploadChars = currentChars)
                        }
                        is StreamEvent.ToolCallStarted -> {
                            val tip = "> 🤖 正在调度工具: ${event.functionName} ..."
                            totalAccumulatedReasoning += if (totalAccumulatedReasoning.isNotEmpty()) "\n\n$tip" else tip
                            updateLastMessage(totalAccumulatedText, totalAccumulatedReasoning, isLoading = true, uploadChars = currentChars)
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
                            totalAccumulatedText += "\n❌ 网络异常或 API 报错: ${event.message}"
                            updateLastMessage(totalAccumulatedText, totalAccumulatedReasoning, isLoading = false, uploadChars = currentChars)
                            _uiState.update { it.copy(isAgentWorking = false) }
                        }
                    }
                }

                if (errorOccurred) return@launch

                if (!hasReceivedContent && toolCallsBuffer.isEmpty() && totalAccumulatedText.isBlank()) {
                    totalAccumulatedText = "（等待分析中...）"
                } else if (toolCallsBuffer.isNotEmpty() && !hasReceivedContent) {
                    consecutiveToolOnlyIterations++
                } else {
                    consecutiveToolOnlyIterations = 0   
                }

                finalUsage?.let { usage ->
                    totalPromptTokens += usage.promptTokens
                    totalCompletionTokens += usage.completionTokens
                    updateLastMessageUsage(totalPromptTokens, totalCompletionTokens)
                }

                val assistantApiMsg = ApiMessage(
                    role = "assistant",
                    content = if (toolCallsBuffer.isNotEmpty()) null else totalAccumulatedText.takeIf { it.isNotBlank() },
                    toolCalls = toolCallsBuffer.takeIf { it.isNotEmpty() },
                    reasoningContent = null
                )
                currentMessages.add(assistantApiMsg)

                if (toolCallsBuffer.isEmpty()) break

                var allToolsSucceeded = true
                for (toolCall in toolCallsBuffer) {
                    val funcName = toolCall.function.name ?: "unknown"
                    val argsStr = toolCall.function.arguments ?: ""
                    
                    val displayArgs = try {
                        val jsonObj = toolParser.parseToJsonElement(argsStr).jsonObject
                        when (funcName) {
                            "search_keyword" -> "🔍 搜索关键字: ${jsonObj["keyword"]?.jsonPrimitive?.content}"
                            "list_directory" -> "📂 查看目录: ${jsonObj["path"]?.jsonPrimitive?.content}"
                            "read_file" -> "📄 读取文件: ${jsonObj["path"]?.jsonPrimitive?.content}"
                            "apply_patch" -> "✍️ 准备修改: ${jsonObj["path"]?.jsonPrimitive?.content}"
                            else -> "执行指令中"
                        }
                    } catch (e: Exception) {
                        "执行指令中"
                    }

                    totalAccumulatedReasoning += "\n> 🎯 调度参数: $displayArgs"
                    updateLastMessage(totalAccumulatedText, totalAccumulatedReasoning, isLoading = true, uploadChars = currentChars)

                    val result = executeTool(name = funcName, args = argsStr, rootUri = rootUri)
                    
                    val toolResultMsg = ApiMessage(
                        role = "tool",
                        toolCallId = toolCall.id,
                        content = result,
                        name = toolCall.function.name
                    )
                    currentMessages.add(toolResultMsg)
                    currentChars += result.length
                    
                    val resultTip = if (result.length > 50) "> 📦 执行完毕，获取 ${result.length} 个字符" else "> 📦 工具返回: $result"
                    totalAccumulatedReasoning += "\n$resultTip\n"
                    updateLastMessage(totalAccumulatedText, totalAccumulatedReasoning, isLoading = true, uploadChars = currentChars)

                    if (result.startsWith("⚠️ 系统强制拦截")) {
                        allToolsSucceeded = false // 触发打断
                    }
                }

                if (!allToolsSucceeded) {
                    break // 强制退出大循环，让 AI 开始正式回复
                }
            }

            updateLastMessage(totalAccumulatedText, totalAccumulatedReasoning, isLoading = false, uploadChars = currentChars)
            _uiState.update { it.copy(isAgentWorking = false) }
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
            return "⚠️ 工具执行异常: 参数格式错误"
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
                "apply_patch" -> {
                    // 🔥 终极防挂起锁：一轮对话绝对不允许 2 个补丁！
                    if (_patchQueue.isNotEmpty() || _uiState.value.pendingPatch != null) {
                        return "⚠️ 系统强制拦截：你已经提交了一个修改提议！为了防止代码冲突和系统超时，每次对话【只允许提交1个修改】！请立刻结束本轮工具调度，回复用户并等待用户在界面上点击确认！"
                    }

                    val path = argObj["path"]?.jsonPrimitive?.content ?: ""
                    val searchString = argObj["search_string"]?.jsonPrimitive?.content ?: ""
                    val replaceString = argObj["replace_string"]?.jsonPrimitive?.content ?: ""
                    
                    val targetUri = repository.findFileByRelativePath(rootUri, path)
                    if (targetUri != null) {
                        val original = getApplication<Application>().contentResolver
                            .openInputStream(targetUri)?.bufferedReader()?.use { it.readText() } ?: ""
                            
                        if (original.contains(searchString)) {
                            val newContent = original.replace(searchString, replaceString)
                            
                            // 🔥 防幻觉锁：确保真的有改变
                            if (original == newContent) {
                                return "⚠️ 错误：你提供的 replace_string 替换后，文件内容没有任何变化！请仔细检查你的替换逻辑。"
                            }
                            
                            val cleanOriginalLines = original.lines()
                            val newLines = newContent.lines()
                            val patch = DiffUtils.diff(cleanOriginalLines, newLines)
                            val diffLines = UnifiedDiffUtils.generateUnifiedDiff(path, path, cleanOriginalLines, patch, 3)
                            val diffText = diffLines.joinToString("\n")
                            
                            val proposal = PatchProposal(targetUri, path, original, diffText.ifBlank { "逻辑重构，无明显变化" }, newContent)
                            
                            _patchQueue.add(proposal)
                            _uiState.update { it.copy(pendingPatch = proposal) }
                            
                            "✅ 补丁已成功生成并放入等待队列，请立刻结束执行并向用户汇报修改结果！"
                        } else {
                            "❌ 错误：找不到匹配的 search_string。请严格复制原文。"
                        }
                    } else {
                        "❌ 错误：找不到文件 $path"
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

    fun deleteMessage(index: Int) {
        _uiState.update { state ->
            val msgs = state.chatMessages.toMutableList()
            if (index in msgs.indices && msgs[index].role == "user") {
                msgs.removeAt(index)
                state.copy(chatMessages = msgs)
            } else {
                state
            }
        }
        viewModelScope.launch(Dispatchers.IO) { repository.saveChatHistory(_uiState.value.chatMessages) }
    }

    private fun updateLastMessage(text: String, reasoning: String = "", isLoading: Boolean, uploadChars: Int) {
        _uiState.update { state ->
            val msgs = state.chatMessages.toMutableList()
            if (msgs.isNotEmpty()) {
                val last = msgs.last()
                msgs[msgs.lastIndex] = last.copy(
                    content = text, 
                    reasoningContent = reasoning, 
                    isLoading = isLoading, 
                    uploadChars = uploadChars
                )
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
                appendMessage(ChatMessage("assistant", "✅ 成功修改：${patch.targetFileName}"))
                if (_uiState.value.selectedFile?.uri == patch.targetFileUri) {
                    _uiState.update { it.copy(currentCodeContent = patch.proposedContent) }
                }
            } else {
                appendMessage(ChatMessage("assistant", "❌ 写入失败，请检查权限。"))
            }
            
            _patchQueue.remove(patch)
            _uiState.update { it.copy(pendingPatch = _patchQueue.firstOrNull()) }
        }
    }

    fun rejectPatch() {
        val patch = _uiState.value.pendingPatch ?: return
        appendMessage(ChatMessage("assistant", "🚫 已拒绝修改：${patch.targetFileName}"))
        
        _patchQueue.remove(patch)
        _uiState.update { it.copy(pendingPatch = _patchQueue.firstOrNull()) }
    }
}