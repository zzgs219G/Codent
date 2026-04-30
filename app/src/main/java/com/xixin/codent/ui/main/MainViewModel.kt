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
    private val jsonConfig = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }
    
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
        Tool(function = FunctionDef(name = "search_keyword", description = "全局搜索代码关键字。返回带行号匹配片段。", parameters = buildJsonObject { put("type", "object"); putJsonObject("properties") { putJsonObject("keyword") { put("type", "string") } }; putJsonArray("required") { add(JsonPrimitive("keyword")) } })),
        Tool(function = FunctionDef(name = "list_directory", description = "列出目录内容，根目录传 ''", parameters = buildJsonObject { put("type", "object"); putJsonObject("properties") { putJsonObject("path") { put("type", "string") } }; putJsonArray("required") { add(JsonPrimitive("path")) } })),
        Tool(function = FunctionDef(name = "read_file", description = "读取文件片段。必须指定 start_line 和 end_line", parameters = buildJsonObject { put("type", "object"); putJsonObject("properties") { putJsonObject("path") { put("type", "string") }; putJsonObject("start_line") { put("type", "integer") }; putJsonObject("end_line") { put("type", "integer") } }; putJsonArray("required") { add(JsonPrimitive("path")); add(JsonPrimitive("start_line")); add(JsonPrimitive("end_line")) } })),
        Tool(function = FunctionDef(name = "apply_patch", description = "局部修改代码。精确替换 search_string。", parameters = buildJsonObject { put("type", "object"); putJsonObject("properties") { putJsonObject("path") { put("type", "string") }; putJsonObject("search_string") { put("type", "string") }; putJsonObject("replace_string") { put("type", "string") } }; putJsonArray("required") { add(JsonPrimitive("path")); add(JsonPrimitive("search_string")); add(JsonPrimitive("replace_string")) } })),
        Tool(function = FunctionDef(name = "create_file", description = "新建文件。路径必须是相对项目根目录的路径。", parameters = buildJsonObject { put("type", "object"); putJsonObject("properties") { putJsonObject("path") { put("type", "string") }; putJsonObject("content") { put("type", "string") } }; putJsonArray("required") { add(JsonPrimitive("path")); add(JsonPrimitive("content")) } }))
    )

    init {
        val savedHistory = repository.loadChatHistory()
        _uiState.update { 
            it.copy(
                apiBaseUrl = repository.getApiBaseUrl(),
                apiKey = repository.getApiKey(),
                selectedModel = repository.getSelectedModel(),
                enableThinking = repository.isThinkingEnabled(),
                chatMessages = savedHistory
            ) 
        }
        AppLog.d("ViewModel 初始化: 恢复了 ${savedHistory.size} 条历史")
    }

    fun clearChat() {
        _uiState.update { it.copy(chatMessages = emptyList()) }
        viewModelScope.launch(Dispatchers.IO) { repository.clearChatHistory() }
        AppLog.d("记忆已被清空")
    }

    fun saveConfig(baseUrl: String, key: String, model: String) {
        repository.saveApiBaseUrl(baseUrl)
        repository.saveApiKey(key)
        repository.saveSelectedModel(model)
        _uiState.update { it.copy(apiBaseUrl = baseUrl, apiKey = key, selectedModel = model) }
        AppLog.d("配置已保存")
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

    // 🔥 核心新功能：编辑重发（截断下方所有消息）
    fun editAndResendMessage(index: Int, newText: String) {
        AppLog.d("触发编辑重发，截断索引 $index 及其下方的所有历史消息")
        _uiState.update { state ->
            val msgs = state.chatMessages.toMutableList()
            if (index in msgs.indices && msgs[index].role == "user") {
                val keptMsgs = msgs.subList(0, index)
                state.copy(chatMessages = keptMsgs)
            } else state
        }
        viewModelScope.launch(Dispatchers.IO) { repository.saveChatHistory(_uiState.value.chatMessages) }
        sendChatMessage(newText)
    }

    fun sendChatMessage(userText: String) {
        val state = _uiState.value
        if (state.apiKey.isBlank()) {
            appendMessage(ChatMessage("assistant", "❌ 请先配置 API Key", isLoading = false))
            return
        }

        val rootUri = state.directoryStack.firstOrNull()
        if (rootUri == null) {
            appendMessage(ChatMessage("assistant", "❌ 请先选择项目根目录", isLoading = false))
            return
        }

        appendMessage(ChatMessage("user", userText))
        AppLog.d("发送用户消息: $userText")

        viewModelScope.launch {
            _uiState.update { it.copy(isAgentWorking = true) }

            val systemPrompt = """
                你是一个顶级 Android 架构师 Agent。
                【红线警告】：
                1. 单次读取文件 read_file 不超过 300 行。
                2. 修改代码 apply_patch 时 search_string 必须复制原文片段。
                3. 每次对话绝对只允许 1 个 apply_patch 或 create_file，一旦调用必须立刻结束本次生成，等待用户确认！
                4. 仅使用纯文本回复，禁止扮演用户。
            """.trimIndent()
            
            val apiMessages = mutableListOf(ApiMessage("system", systemPrompt))
            val recentHistory = _uiState.value.chatMessages.filter { !it.isLoading }.takeLast(30)
            
            for (msg in recentHistory) {
                if (msg.content.isNotBlank()) {
                    apiMessages.add(ApiMessage(role = msg.role, content = msg.content))
                }
            }

            var currentChars = systemPrompt.length + userText.length
            var iteration = 0
            var consecutiveToolOnlyIterations = 0   

            var totalAccumulatedText = ""
            var totalAccumulatedReasoning = ""
            var totalPromptTokens = 0
            var totalCompletionTokens = 0

            appendMessage(ChatMessage("assistant", "", isLoading = true))

            while (iteration < 30 && consecutiveToolOnlyIterations < 15) {
                iteration++
                AppLog.d("---- 进入 Agent 推理大循环: 第 $iteration 轮 ----")
                val toolCallsBuffer = mutableListOf<ToolCall>()
                var finalUsage: Usage? = null
                var errorOccurred = false
                var hasReceivedContent = false
                var isFirstReasoning = true

                val flow = aiService.getAgentCompletionStream(
                    baseUrl = state.apiBaseUrl,
                    apiKey = state.apiKey,
                    model = state.selectedModel,
                    messages = apiMessages,
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
                            if (isFirstReasoning) {
                                AppLog.d("🤔 AI 开始进行深度推理思考...")
                                isFirstReasoning = false
                            }
                            totalAccumulatedReasoning += event.text
                            updateLastMessage(totalAccumulatedText, totalAccumulatedReasoning, isLoading = true, uploadChars = currentChars)
                        }
                        is StreamEvent.ToolCallStarted -> {
                            val tip = "> 🤖 正在调度工具: ${event.functionName} ..."
                            totalAccumulatedReasoning += if (totalAccumulatedReasoning.isNotEmpty()) "\n\n$tip" else tip
                            updateLastMessage(totalAccumulatedText, totalAccumulatedReasoning, isLoading = true, uploadChars = currentChars)
                        }
                        is StreamEvent.ToolCallComplete -> {
                            toolCallsBuffer.add(ToolCall(index = toolCallsBuffer.size, id = event.toolCallId, function = FunctionCall(name = event.functionName, arguments = event.arguments)))
                        }
                        is StreamEvent.Done -> finalUsage = event.usage
                        is StreamEvent.Error -> {
                            errorOccurred = true
                            AppLog.d("❌ 发生网络或流解析错误: ${event.message}")
                            totalAccumulatedText += "\n❌ API 报错: ${event.message}"
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

                // 🔥 补齐缺失的最终内容日志输出
                if (totalAccumulatedText.isNotBlank()) {
                    AppLog.d("🤖 AI 最终回复内容: $totalAccumulatedText")
                }

                finalUsage?.let { usage ->
                    totalPromptTokens += usage.promptTokens
                    totalCompletionTokens += usage.completionTokens
                    updateLastMessageUsage(totalPromptTokens, totalCompletionTokens)
                }

                val assistantApiMsg = ApiMessage(
                    role = "assistant",
                    content = if (toolCallsBuffer.isNotEmpty()) null else totalAccumulatedText.takeIf { it.isNotBlank() },
                    toolCalls = toolCallsBuffer.takeIf { it.isNotEmpty() }
                )
                apiMessages.add(assistantApiMsg)

                if (toolCallsBuffer.isEmpty()) {
                    AppLog.d("🏁 AI 认为无需调用工具，本轮推理闭环结束")
                    break
                }

                for (toolCall in toolCallsBuffer) {
                    val funcName = toolCall.function.name ?: "unknown"
                    val argsStr = toolCall.function.arguments ?: ""
                    
                    AppLog.d("🔧 准备执行本地方法: $funcName, 原始 JSON 参数: $argsStr")

                    val displayArgs = try {
                        val jsonObj = toolParser.parseToJsonElement(argsStr).jsonObject
                        when (funcName) {
                            "search_keyword" -> "🔍 搜: ${jsonObj["keyword"]?.jsonPrimitive?.content}"
                            "list_directory" -> "📂 看: ${jsonObj["path"]?.jsonPrimitive?.content}"
                            "read_file" -> "📄 读: ${jsonObj["path"]?.jsonPrimitive?.content}"
                            "apply_patch" -> "✍️ 改: ${jsonObj["path"]?.jsonPrimitive?.content}"
                            "create_file" -> "✨ 建: ${jsonObj["path"]?.jsonPrimitive?.content}"
                            else -> "执行指令中"
                        }
                    } catch (e: Exception) { "执行指令中" }

                    totalAccumulatedReasoning += "\n> 🎯 参数: $displayArgs"
                    updateLastMessage(totalAccumulatedText, totalAccumulatedReasoning, isLoading = true, uploadChars = currentChars)

                    val result = executeTool(name = funcName, args = argsStr, rootUri = rootUri)
                    
                    AppLog.d("📦 本地工具返回结果长度: ${result.length}, 预览: ${result.take(80).replace("\n", " ")}...")

                    val toolResultMsg = ApiMessage(role = "tool", toolCallId = toolCall.id, content = result, name = toolCall.function.name)
                    apiMessages.add(toolResultMsg)
                    currentChars += result.length
                    
                    val resultTip = if (result.length > 50) "> 📦 获取 ${result.length} 个字符" else "> 📦 工具返回: $result"
                    totalAccumulatedReasoning += "\n$resultTip\n"
                    updateLastMessage(totalAccumulatedText, totalAccumulatedReasoning, isLoading = true, uploadChars = currentChars)

                    // 🔥 这里删除了之前的 `break` 逻辑，让系统拦截警告能传给大模型，让大模型在下一轮有开口解释的机会！
                }
            }

            AppLog.d("✅ 整个 Agent 响应流结束")
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
                        '}' -> { braceCount--; if (braceCount == 0) { endIndex = i; break } }
                    }
                }
                if (endIndex > 0 && endIndex < cleaned.length - 1) cleaned.substring(0, endIndex + 1) else cleaned
            } else cleaned
        }
        
        val jsonElement = runCatching { toolParser.parseToJsonElement(cleanedArgs) }.getOrElse {
            AppLog.d("解析大模型 JSON 参数失败: $cleanedArgs")
            return "⚠️ 参数格式错误"
        }
        
        return try {
            val argObj = jsonElement.jsonObject
            when (name) {
                "search_keyword" -> repository.searchKeyword(rootUri, argObj["keyword"]?.jsonPrimitive?.content ?: "")
                "list_directory" -> repository.listDirectoryRelative(rootUri, argObj["path"]?.jsonPrimitive?.content ?: "")
                "read_file" -> {
                    val path = argObj["path"]?.jsonPrimitive?.content ?: ""
                    val fileUri = repository.findFileByRelativePath(rootUri, path)
                    if (fileUri != null) repository.readFileContent(fileUri, argObj["start_line"]?.jsonPrimitive?.intOrNull, argObj["end_line"]?.jsonPrimitive?.intOrNull) else "错误：找不到 $path"
                }
                "create_file" -> {
                    if (_patchQueue.isNotEmpty() || _uiState.value.pendingPatch != null) return "⚠️ 系统强制拦截：每次对话只允许1个修改操作！请结束调度并提示用户确认。"
                    val path = argObj["path"]?.jsonPrimitive?.content ?: ""
                    val content = argObj["content"]?.jsonPrimitive?.content ?: ""
                    repository.createFileByRelativePath(rootUri, path, content)
                }
                "apply_patch" -> {
                    if (_patchQueue.isNotEmpty() || _uiState.value.pendingPatch != null) return "⚠️ 系统强制拦截：每次对话只允许提交1个修改！请结束调度并提示用户确认。"

                    val path = argObj["path"]?.jsonPrimitive?.content ?: ""
                    val searchString = argObj["search_string"]?.jsonPrimitive?.content ?: ""
                    val replaceString = argObj["replace_string"]?.jsonPrimitive?.content ?: ""
                    
                    val targetUri = repository.findFileByRelativePath(rootUri, path)
                    if (targetUri != null) {
                        val original = getApplication<Application>().contentResolver.openInputStream(targetUri)?.bufferedReader()?.use { it.readText() } ?: ""
                        
                        val normOriginal = original.replace("\r\n", "\n")
                        val normSearch = searchString.replace("\r\n", "\n")
                        val normReplace = replaceString.replace("\r\n", "\n")

                        if (normOriginal.contains(normSearch)) {
                            val newContent = normOriginal.replace(normSearch, normReplace)
                            if (normOriginal == newContent) return "⚠️ 错误：文件内容没有任何变化！请仔细检查你的替换逻辑。"
                            
                            val patch = DiffUtils.diff(normOriginal.lines(), newContent.lines())
                            val diffLines = UnifiedDiffUtils.generateUnifiedDiff(path, path, normOriginal.lines(), patch, 3)
                            val proposal = PatchProposal(targetUri, path, original, diffLines.joinToString("\n").ifBlank { "逻辑重构，无明显变化" }, newContent)
                            
                            _patchQueue.add(proposal)
                            _uiState.update { it.copy(pendingPatch = proposal) }
                            "✅ 补丁已进入队列，请结束执行向用户汇报！"
                        } else {
                            val strippedOrig = normOriginal.replace(Regex("\\s+"), "")
                            val strippedSearch = normSearch.replace(Regex("\\s+"), "")
                            if (strippedOrig.contains(strippedSearch)) {
                                "❌ 错误：你的 search_string 匹配失败，但去除了空格后匹配成功。这说明你的缩进或者空格抄错了，请严谨、逐字地复制原本的代码片段！"
                            } else {
                                "❌ 错误：完全找不到 search_string。请严格复制原文。"
                            }
                        }
                    } else "❌ 错误：找不到文件 $path"
                }
                else -> "未知工具: $name"
            }
        } catch (e: Exception) { 
            AppLog.d("工具本地执行捕获异常: ${e.localizedMessage}")
            "工具执行异常: ${e.localizedMessage}" 
        }
    }

    private fun appendMessage(msg: ChatMessage) {
        _uiState.update { state -> state.copy(chatMessages = state.chatMessages + msg) }
        viewModelScope.launch(Dispatchers.IO) { repository.saveChatHistory(_uiState.value.chatMessages) }
    }

    // 保留这个是为了适配 ChatPanel 顶部的一键清空 (-1) 按钮
    fun deleteMessage(index: Int) {
        if (index == -1) clearChat()
    }

    private fun updateLastMessage(text: String, reasoning: String = "", isLoading: Boolean, uploadChars: Int) {
        _uiState.update { state ->
            val msgs = state.chatMessages.toMutableList()
            if (msgs.isNotEmpty()) {
                val last = msgs.last()
                msgs[msgs.lastIndex] = last.copy(content = text, reasoningContent = reasoning, isLoading = isLoading, uploadChars = uploadChars)
            }
            state.copy(chatMessages = msgs)
        }
        if (!isLoading) viewModelScope.launch(Dispatchers.IO) { repository.saveChatHistory(_uiState.value.chatMessages) }
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
            val isSuccess = repository.overwriteFile(patch.targetFileUri, patch.proposedContent)
            
            _patchQueue.remove(patch)
            _uiState.update { it.copy(pendingPatch = _patchQueue.firstOrNull()) }

            if (isSuccess) {
                AppLog.d("用户已确认补丁，文件 ${patch.targetFileName} 已覆盖写入")
                if (_uiState.value.selectedFile?.uri == patch.targetFileUri) {
                    _uiState.update { it.copy(currentCodeContent = patch.proposedContent) }
                }
                sendChatMessage("✅ 我已确认并应用了你对 `${patch.targetFileName}` 的修改。请检查是否还需要后续修改，如果没有，请总结并结束。")
            } else {
                AppLog.d("补丁写入失败")
                sendChatMessage("❌ 写入文件 `${patch.targetFileName}` 失败。请尝试重新分析。")
            }
        }
    }

    fun rejectPatch() {
        val patch = _uiState.value.pendingPatch ?: return
        _patchQueue.remove(patch)
        _uiState.update { it.copy(pendingPatch = _patchQueue.firstOrNull()) }
        AppLog.d("用户已拒绝补丁修改")
        sendChatMessage("🚫 我拒绝了你的修改。请思考是否有更稳妥的方案并重新尝试。")
    }
}