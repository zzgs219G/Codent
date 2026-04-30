
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

/**
 * 满血修正版 Codent 调度中心
 * 修正了上一版中的命名不一致问题 (appendStatusMessage / charsCount)
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = SafRepository(application)
    
    private val jsonConfig = Json { 
        ignoreUnknownKeys = true 
        isLenient = true 
        encodeDefaults = true 
    }
    
    private val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) { 
            json(jsonConfig) 
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 180_000 
            connectTimeoutMillis = 30_000
            socketTimeoutMillis = 180_000
        }
        install(Logging) {
            logger = object : Logger {
                override fun log(message: String) {
                    Log.i("Codent-Network", message)
                }
            }
            level = LogLevel.INFO
        }
    }
    
    private val aiService = AiApiService(httpClient)
    
    private val _uiState = MutableStateFlow(WorkspaceState())
    val uiState: StateFlow<WorkspaceState> = _uiState.asStateFlow()

    private val toolParser = Json { ignoreUnknownKeys = true }

    // ========================================================================
    // 🛠️ Agent 工具集定义
    // ========================================================================
    
    private val agentTools = listOf(
        Tool(
            function = FunctionDef(
                name = "search_keyword",
                description = "全局搜索：在整个项目中搜索代码关键字。返回文件路径列表。",
                parameters = buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("keyword") { 
                            put("type", "string")
                            put("description", "要搜索的关键字") 
                        }
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
                        putJsonObject("path") { 
                            put("type", "string")
                            put("description", "相对路径，如 'app/src'") 
                        }
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
                        putJsonObject("path") { 
                            put("type", "string")
                            put("description", "相对路径，如 'MainActivity.kt'") 
                        }
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
                        putJsonObject("path") { 
                            put("type", "string") 
                        }
                        putJsonObject("new_content") { 
                            put("type", "string")
                        }
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
                selectedModel = repository.getSelectedModel()
            ) 
        }
    }

    // ========================================================================
    // 📂 基础设置与导航逻辑
    // ========================================================================

    fun saveConfig(key: String, model: String) {
        repository.saveApiKey(key)
        repository.saveSelectedModel(model)
        _uiState.update { it.copy(apiKey = key, selectedModel = model) }
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

    // ========================================================================
    // 🧠 AI Agent 逻辑 (支持 SSE 与 Tool Calling)
    // ========================================================================

    fun sendChatMessage(userText: String) {
        val state = _uiState.value
        if (state.apiKey.isBlank()) {
            appendUiStatusMessage("❌ 错误：请先在设置中配置 API Key")
            return
        }

        val userMsg = ChatMessage("user", userText)
        val assistantInit = ChatMessage("assistant", "", isLoading = true)
        
        _uiState.update { 
            it.copy(
                chatMessages = it.chatMessages + userMsg + assistantInit,
                isAgentWorking = true
            ) 
        }

        viewModelScope.launch {
            val rootUri = state.directoryStack.firstOrNull()
            val systemPrompt = "你是一个拥有上帝视角的顶级 Android 架构师 Agent。必须通过工具搜索、阅读，最后修改。"

            val conversation = mutableListOf(
                ApiMessage("system", systemPrompt),
                ApiMessage("user", userText)
            )

            val totalChars = systemPrompt.length + userText.length
            runAgentLoop(conversation, rootUri, totalChars)
        }
    }

    private suspend fun runAgentLoop(messages: MutableList<ApiMessage>, rootUri: Uri?, uploadChars: Int) {
        val state = _uiState.value
        var accumulatedText = ""
        var wasToolCallTriggered = false

        aiService.getAgentCompletionStream(
            apiKey = state.apiKey,
            model = state.selectedModel,
            messages = messages,
            tools = agentTools
        ).collect { event ->
            when (event) {
                is StreamEvent.Content -> {
                    accumulatedText += event.text
                    updateLastMessageState(accumulatedText, isLoading = false, uploadChars = uploadChars)
                }
                is StreamEvent.ToolCallStarted -> {
                    updateLastMessageState("🤖 准备调用工具: `${event.functionName}`...", isLoading = true, uploadChars = uploadChars)
                }
                is StreamEvent.ToolCallComplete -> {
                    wasToolCallTriggered = true
                    val aiMsg = ApiMessage(
                        role = "assistant",
                        content = accumulatedText,
                        toolCalls = listOf(
                            ToolCall(
                                id = event.toolCallId,
                                function = FunctionCall(name = event.functionName, arguments = event.arguments)
                            )
                        )
                    )
                    messages.add(aiMsg)
                    
                    // 修正：参数名改为统一的 charsCount
                    processToolAction(
                        name = event.functionName,
                        args = event.arguments,
                        id = event.toolCallId,
                        rootUri = rootUri,
                        history = messages,
                        charsCount = uploadChars
                    )
                }
                is StreamEvent.Done -> {
                    event.usage?.let { finalizeMessageStats(it, uploadChars) }
                    if (!wasToolCallTriggered) {
                        _uiState.update { it.copy(isAgentWorking = false) }
                    }
                }
                is StreamEvent.Error -> {
                    updateLastMessageState("❌ 错误: ${event.message}", isLoading = false, uploadChars = uploadChars)
                    _uiState.update { it.copy(isAgentWorking = false) }
                }
            }
        }
    }

    private suspend fun processToolAction(
        name: String, 
        args: String, 
        id: String, 
        rootUri: Uri?, 
        history: MutableList<ApiMessage>,
        charsCount: Int
    ) {
        try {
            val argObj = toolParser.parseToJsonElement(args).jsonObject
            var observation = "结果未知"

            if (rootUri == null) {
                observation = "错误：未挂载根目录"
            } else {
                when (name) {
                    "search_keyword" -> {
                        val keyword = argObj["keyword"]?.jsonPrimitive?.content ?: ""
                        observation = repository.searchKeyword(rootUri, keyword)
                    }
                    "list_directory" -> {
                        val path = argObj["path"]?.jsonPrimitive?.content ?: ""
                        observation = repository.listDirectoryRelative(rootUri, path)
                    }
                    "read_file" -> {
                        val path = argObj["path"]?.jsonPrimitive?.content ?: ""
                        val fileUri = repository.findFileByRelativePath(rootUri, path)
                        observation = if (fileUri != null) repository.readFileContent(fileUri) else "错误：找不到 $path"
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
                            updateLastMessageState("我已为您生成了重构方案，请查看下方的 Diff 预览。", false, charsCount)
                            return 
                        } else {
                            observation = "错误：找不到文件 $path"
                        }
                    }
                }
            }

            val newTotalChars = charsCount + observation.length
            history.add(ApiMessage(role = "tool", toolCallId = id, content = observation))
            appendUiStatusMessage("Agent 正在分析反馈数据...") // 修正：方法名对齐
            
            runAgentLoop(history, rootUri, newTotalChars)

        } catch (e: Exception) {
            history.add(ApiMessage(role = "tool", toolCallId = id, content = "执行崩溃: ${e.localizedMessage}"))
            runAgentLoop(history, rootUri, charsCount)
        }
    }

    // ========================================================================
    // 📊 辅助方法
    // ========================================================================

    private fun updateLastMessageState(text: String, isLoading: Boolean, uploadChars: Int) {
        _uiState.update { s ->
            val msgs = s.chatMessages.toMutableList()
            if (msgs.isNotEmpty()) {
                msgs[msgs.lastIndex] = msgs.last().copy(content = text, isLoading = isLoading, uploadChars = uploadChars)
            }
            s.copy(chatMessages = msgs)
        }
    }

    private fun finalizeMessageStats(usage: Usage, uploadChars: Int) {
        _uiState.update { s ->
            val msgs = s.chatMessages.toMutableList()
            if (msgs.isNotEmpty()) {
                msgs[msgs.lastIndex] = msgs.last().copy(
                    promptTokens = usage.promptTokens,
                    completionTokens = usage.completionTokens,
                    uploadChars = uploadChars
                )
            }
            s.copy(chatMessages = msgs)
        }
    }

    private fun appendUiStatusMessage(text: String) { // 统一方法名
        _uiState.update { 
            it.copy(chatMessages = it.chatMessages + ChatMessage("assistant", text, isLoading = true)) 
        }
    }

    fun confirmPatch() {
        val patch = _uiState.value.pendingPatch ?: return
        viewModelScope.launch {
            if (repository.overwriteFile(patch.targetFileUri, patch.proposedContent)) {
                _uiState.update { s ->
                    s.copy(
                        chatMessages = s.chatMessages + ChatMessage("assistant", "✅ 修改已落盘：${patch.targetFileName}"),
                        currentCodeContent = if (s.selectedFile?.uri == patch.targetFileUri) patch.proposedContent else s.currentCodeContent
                    )
                }
            } else {
                appendUiStatusMessage("❌ 写入失败，请检查权限。")
            }
            _uiState.update { it.copy(pendingPatch = null) }
        }
    }

    fun rejectPatch() {
        _uiState.update { it.copy(chatMessages = it.chatMessages + ChatMessage("assistant", "🚫 修改已取消。"), pendingPatch = null) }
    }
}
