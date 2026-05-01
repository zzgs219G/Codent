package com.xixin.codent.ui.main

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xixin.codent.core.agent.AgentEvent
import com.xixin.codent.core.agent.AiBrain
import com.xixin.codent.data.api.AiApiService
import com.xixin.codent.data.api.ApiMessage
import com.xixin.codent.data.model.ChatMessage
import com.xixin.codent.data.model.FileNode
import com.xixin.codent.data.model.PatchProposal
import com.xixin.codent.data.model.WorkspaceState
import com.xixin.codent.data.repository.SafRepository
import com.xixin.codent.wrapper.log.AppLog
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * [MainViewModel] - 项目的核心调度员
 * 负责 UI 状态维护与业务逻辑分发
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = SafRepository(application)
    private val _uiState = MutableStateFlow(WorkspaceState())
    val uiState: StateFlow<WorkspaceState> = _uiState.asStateFlow()

    private val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true })
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 300_000L
            connectTimeoutMillis = 90_000L
            socketTimeoutMillis = 300_000L
        }
        install(Logging) {
            logger = object : Logger { override fun log(message: String) { AppLog.d("[Network] $message") } }
            level = LogLevel.INFO
        }
    }

    private val aiService = AiApiService(httpClient)
    private val aiBrain = AiBrain(aiService, repository)

    private var directoryLoadJob: Job? = null
    private var agentJob: Job? = null

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
    }

    // ==========================================
    // 第一部分：配置管理 (Settings)
    // ==========================================

    fun clearChat() {
        // 清空消息的同时，也要清空所有待办补丁
        _uiState.update { it.copy(chatMessages = emptyList(), pendingPatches = emptyList()) }
        persistChatHistoryAsync()
        viewModelScope.launch(Dispatchers.IO) { repository.clearChatHistory() }
    }

    fun saveConfig(baseUrl: String, key: String, model: String) {
        repository.saveApiBaseUrl(baseUrl)
        repository.saveApiKey(key)
        repository.saveSelectedModel(model)
        _uiState.update { it.copy(apiBaseUrl = baseUrl, apiKey = key, selectedModel = model) }
    }

    fun saveThinkingEnabled(enabled: Boolean) {
        repository.saveThinkingEnabled(enabled)
        _uiState.update { it.copy(enableThinking = enabled) }
    }

    // ==========================================
    // 第二部分：文件系统管理 (File System)
    // ==========================================

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
            _uiState.update { it.copy(directoryStack = emptyList(), currentFiles = emptyList(), selectedFile = null) }
            false
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

    /** 删除单条消息 */
    fun deleteMessage(index: Int) {
        if (index < 0) { clearChat(); return }
        if (index !in _uiState.value.chatMessages.indices) return
        _uiState.update { state -> state.copy(chatMessages = state.chatMessages.subList(0, index).toList()) }
        persistChatHistoryAsync()
    }

    /** 编辑并重发 */
    fun editAndResendMessage(index: Int, newText: String) {
        val state = _uiState.value
        if (index !in state.chatMessages.indices || state.chatMessages[index].role != "user") return
        _uiState.update { current -> current.copy(chatMessages = current.chatMessages.subList(0, index).toList()) }
        sendChatMessage(newText)
    }

    // ==========================================
    // 第三部分：AI 调度与 批量补丁流程
    // ==========================================

    /** * 确认并应用补丁
     * @param patch 指定要操作的补丁对象
     */
    fun confirmPatch(patch: PatchProposal) {
        viewModelScope.launch {
            val success = repository.overwriteFile(patch.targetFileUri, patch.proposedContent)
            if (success) {
                AppLog.d("💾 [文件落盘]: ✅ 用户确认修改成功: ${patch.targetFileName}")
                _uiState.update { state ->
                    // 如果当前编辑器正好打开着这个文件，同步刷新编辑器内容
                    val updatedContent = if (state.selectedFile?.uri == patch.targetFileUri) {
                        patch.proposedContent 
                    } else {
                        state.currentCodeContent
                    }
                    
                    state.copy(
                        // 从待办列表中移除该补丁
                        pendingPatches = state.pendingPatches.filter { it != patch },
                        currentCodeContent = updatedContent,
                        chatMessages = state.chatMessages + ChatMessage(
                            role = "assistant", 
                            content = "✅ **已成功修改并保存** `${patch.targetFileName}`！"
                        )
                    )
                }
                persistChatHistoryAsync()
            } else {
                AppLog.e("❌ [文件落盘]: 保存失败: ${patch.targetFileName}")
                _uiState.update { state ->
                    state.copy(
                        pendingPatches = state.pendingPatches.filter { it != patch },
                        chatMessages = state.chatMessages + ChatMessage(
                            role = "assistant", 
                            content = "❌ **保存失败**，无法覆写 `${patch.targetFileName}`。"
                        )
                    )
                }
                persistChatHistoryAsync()
            }
        }
    }

    /** * 拒绝补丁提议
     * @param patch 指定要操作的补丁对象
     */
    fun rejectPatch(patch: PatchProposal) {
        AppLog.d("🚫 [文件落盘]: 用户拒绝了修改提议: ${patch.targetFileName}")
        _uiState.update { state -> 
            state.copy(
                pendingPatches = state.pendingPatches.filter { it != patch },
                chatMessages = state.chatMessages + ChatMessage(
                    role = "assistant", 
                    content = "🚫 已取消对 `${patch.targetFileName}` 的修改。"
                )
            )
        }
        persistChatHistoryAsync()
    }

    fun sendChatMessage(userText: String) {
        val cleanedText = userText.trim()
        if (cleanedText.isBlank()) return

        val snapshot = _uiState.value
        if (snapshot.apiKey.isBlank()) { appendMessage(ChatMessage("assistant", "❌ 请先配置 API Key")); return }
        
        val rootUri = snapshot.directoryStack.firstOrNull()
        if (rootUri == null) { appendMessage(ChatMessage("assistant", "❌ 请先选择项目根目录")); return }

        agentJob?.cancel()

        appendMessage(ChatMessage("user", cleanedText))
        appendMessage(ChatMessage("assistant", "", isLoading = true))

        agentJob = viewModelScope.launch {
            _uiState.update { it.copy(isAgentWorking = true) }
            
            val history = snapshot.chatMessages.filterNot { it.isLoading }.takeLast(30).mapNotNull {
                if (it.content.isNotBlank()) ApiMessage(role = it.role, content = it.content) else null
            }

            try {
                aiBrain.startConversation(
                    rootUri = rootUri,
                    apiBaseUrl = snapshot.apiBaseUrl,
                    apiKey = snapshot.apiKey,
                    model = snapshot.selectedModel,
                    enableThinking = snapshot.enableThinking,
                    history = history,
                    userText = cleanedText
                ).collect { event ->
                    when (event) {
                        is AgentEvent.ContentUpdate -> {
                            updateLastMessage(event.text, event.reasoning, event.isLoading, event.uploadChars)
                        }
                        is AgentEvent.UsageUpdate -> {
                            updateLastMessageUsage(event.promptTokens, event.completionTokens)
                        }
                        is AgentEvent.PatchProposed -> {
                            // 🔥 这里是核心：使用列表累加补丁，不再覆盖
                            _uiState.update { it.copy(pendingPatches = it.pendingPatches + event.proposal) }
                        }
                        is AgentEvent.Error -> {
                            updateLastMessage("❌ API 报错: ${event.message}", "", false, 0)
                        }
                    }
                }
            } catch (e: Exception) {
                updateLastMessage("❌ 运行异常: ${e.localizedMessage}", "", false, 0)
            } finally {
                _uiState.update { it.copy(isAgentWorking = false) }
                persistChatHistoryAsync()
            }
        }
    }

    private fun appendMessage(message: ChatMessage) {
        _uiState.update { state -> state.copy(chatMessages = state.chatMessages + message) }
    }

    private fun updateLastMessage(text: String, reasoning: String, isLoading: Boolean, uploadChars: Int) {
        _uiState.update { state ->
            val messages = state.chatMessages.toMutableList()
            if (messages.isEmpty()) return@update state
            messages[messages.lastIndex] = messages.last().copy(
                content = text, reasoningContent = reasoning, isLoading = isLoading, uploadChars = uploadChars
            )
            state.copy(chatMessages = messages)
        }
    }

    private fun updateLastMessageUsage(promptTokens: Int, completionTokens: Int) {
        _uiState.update { state ->
            val messages = state.chatMessages.toMutableList()
            if (messages.isEmpty()) return@update state
            messages[messages.lastIndex] = messages.last().copy(promptTokens = promptTokens, completionTokens = completionTokens)
            state.copy(chatMessages = messages)
        }
    }

    private fun persistChatHistoryAsync() {
        val snapshot = _uiState.value.chatMessages.filterNot { it.isLoading }
        viewModelScope.launch(Dispatchers.IO) { repository.saveChatHistory(snapshot) }
    }

    private fun loadDirectory(uri: Uri) {
        directoryLoadJob?.cancel()
        directoryLoadJob = viewModelScope.launch {
            _uiState.update { it.copy(isSafLoading = true, currentFiles = emptyList()) }
            try {
                repository.listFilesFlow(uri).collect { files ->
                    _uiState.update { it.copy(currentFiles = files, isSafLoading = false) }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isSafLoading = false) }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        directoryLoadJob?.cancel()
        agentJob?.cancel()
        httpClient.close()
    }
}
