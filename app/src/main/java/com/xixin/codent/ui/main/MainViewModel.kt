package com.xixin.codent.ui.main

import android.app.Application
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xixin.codent.core.agent.AgentEvent
import com.xixin.codent.core.agent.AiBrain
import com.xixin.codent.data.model.ChatMessage
import com.xixin.codent.data.model.FileNode
import com.xixin.codent.data.model.PatchItem
import com.xixin.codent.data.model.PatchProposal
import com.xixin.codent.data.model.PatchState
import com.xixin.codent.data.model.WorkspaceState
import com.xixin.codent.data.repository.ChatHistoryRepository
import com.xixin.codent.data.repository.LocalFileRepository
import com.xixin.codent.data.repository.SettingsRepository
import com.xixin.codent.wrapper.log.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val localFileRepository = LocalFileRepository()
    private val settingsRepo      = SettingsRepository(application)
    private val chatHistoryRepo   = ChatHistoryRepository(application)
    private val aiBrain           = AiBrain(localFileRepository)

    private val _uiState = MutableStateFlow(WorkspaceState())
    val uiState: StateFlow<WorkspaceState> = _uiState.asStateFlow()

    private var directoryLoadJob: Job? = null
    private var agentJob: Job? = null

    init {
        _uiState.update {
            it.copy(
                apiBaseUrl     = settingsRepo.getApiBaseUrl(),
                apiKey         = settingsRepo.getApiKey(),
                selectedModel  = settingsRepo.getSelectedModel(),
                enableThinking = settingsRepo.isThinkingEnabled(),
                chatMessages   = chatHistoryRepo.load()
            )
        }
    }

    fun saveConfig(baseUrl: String, key: String, model: String) {
        settingsRepo.saveApiBaseUrl(baseUrl)
        settingsRepo.saveApiKey(key)
        settingsRepo.saveSelectedModel(model)
        _uiState.update { it.copy(apiBaseUrl = baseUrl, apiKey = key, selectedModel = model) }
    }

    fun saveThinkingEnabled(enabled: Boolean) {
        settingsRepo.saveThinkingEnabled(enabled)
        _uiState.update { it.copy(enableThinking = enabled) }
    }

    fun initWorkspace(path: String) {
        _uiState.update { it.copy(directoryStack = listOf(path)) }
        loadDirectory(path)
    }

    fun navigateIntoFolder(path: String) {
        _uiState.update { it.copy(directoryStack = it.directoryStack + listOf(path)) }
        loadDirectory(path)
    }

    fun navigateBack(): Boolean {
        val stack = _uiState.value.directoryStack
        return if (stack.size > 1) {
            val newStack = stack.dropLast(1)
            _uiState.update { it.copy(directoryStack = newStack) }
            loadDirectory(newStack.last())
            true
        } else {
            _uiState.update {
                it.copy(directoryStack = emptyList(), currentFiles = emptyList(), selectedFile = null)
            }
            false
        }
    }

    fun openFile(fileNode: FileNode, onOpenComplete: () -> Unit) {
        viewModelScope.launch {
            _uiState.update { it.copy(selectedFile = fileNode, currentCodeContent = "正在加载...") }
            val content = localFileRepository.readFileContent(fileNode.path)
            _uiState.update { it.copy(currentCodeContent = content) }
            onOpenComplete()
        }
    }

    fun clearChat() {
        _uiState.update { it.copy(chatMessages = emptyList(), pendingPatches = emptyList()) }
        viewModelScope.launch(Dispatchers.IO) { chatHistoryRepo.clear() }
    }

    fun deleteMessage(index: Int) {
        if (index < 0) { clearChat(); return }
        if (index !in _uiState.value.chatMessages.indices) return
        _uiState.update { state ->
            state.copy(chatMessages = state.chatMessages.subList(0, index).toList())
        }
        persistChatHistoryAsync()
    }

    fun editAndResendMessage(index: Int, newText: String) {
        val state = _uiState.value
        if (index !in state.chatMessages.indices || state.chatMessages[index].role != "user") return
        _uiState.update { current ->
            current.copy(chatMessages = current.chatMessages.subList(0, index).toList())
        }
        sendChatMessage(newText)
    }

    fun confirmPatch(messageIndex: Int, patchIndex: Int, patch: PatchProposal) {
        viewModelScope.launch {
            val success = localFileRepository.overwriteFile(patch.targetFilePath, patch.proposedContent)
            if (success) {
                AppLog.d("💾 [文件落盘]: ✅ 用户确认修改成功: ${patch.targetFileName}")
                _uiState.update { state ->
                    val msgs = state.chatMessages.toMutableList()
                    if (messageIndex in msgs.indices) {
                        val targetMsg      = msgs[messageIndex]
                        val updatedPatches = targetMsg.patches.toMutableList()
                        if (patchIndex in updatedPatches.indices) {
                            updatedPatches[patchIndex] =
                                updatedPatches[patchIndex].copy(state = PatchState.APPLIED)
                            msgs[messageIndex] = targetMsg.copy(patches = updatedPatches)
                        }
                    }
                    val updatedContent = if (state.selectedFile?.path == patch.targetFilePath)
                        patch.proposedContent else state.currentCodeContent
                    state.copy(chatMessages = msgs, currentCodeContent = updatedContent)
                }
                persistChatHistoryAsync()
            } else {
                AppLog.e("❌ [文件落盘]: 保存失败: ${patch.targetFileName}")
            }
        }
    }

    fun rejectPatch(messageIndex: Int, patchIndex: Int, patch: PatchProposal) {
        _uiState.update { state ->
            val msgs = state.chatMessages.toMutableList()
            if (messageIndex in msgs.indices) {
                val targetMsg      = msgs[messageIndex]
                val updatedPatches = targetMsg.patches.toMutableList()
                if (patchIndex in updatedPatches.indices) {
                    updatedPatches[patchIndex] =
                        updatedPatches[patchIndex].copy(state = PatchState.REJECTED)
                    msgs[messageIndex] = targetMsg.copy(patches = updatedPatches)
                }
            }
            state.copy(chatMessages = msgs)
        }
        persistChatHistoryAsync()
    }

    fun undoPatch(messageIndex: Int, patchIndex: Int, patch: PatchProposal) {
        viewModelScope.launch(Dispatchers.Main) {
            Toast.makeText(getApplication(), "撤回功能研发中...", Toast.LENGTH_SHORT).show()
        }
    }

        fun sendChatMessage(userText: String) {
        val cleanedText = userText.trim()
        if (cleanedText.isBlank()) return

        val snapshot = _uiState.value
        if (snapshot.apiKey.isBlank()) {
            appendMessage(ChatMessage("assistant", "❌ 请先配置 API Key"))
            return
        }

        // 🔥 修复点：把 firstOrNull() 改成 lastOrNull()
        // 这样你在“资源”里点进哪个文件夹，AI 的大脑就只扫描哪个文件夹！
        val rootPath = snapshot.directoryStack.lastOrNull() ?: run {
            appendMessage(ChatMessage("assistant", "❌ 请先选择项目根目录"))
            return
        }

        


        agentJob?.cancel()
        appendMessage(ChatMessage("user", cleanedText))
        appendMessage(ChatMessage("assistant", "", isLoading = true))

        agentJob = viewModelScope.launch {
            _uiState.update { it.copy(isAgentWorking = true) }
            val history = snapshot.chatMessages
                .filterNot { it.isLoading }
                .filter { it.content.isNotBlank() }
                .takeLast(30)
            try {
                aiBrain.startConversation(
                    rootPath       = rootPath,
                    apiBaseUrl     = snapshot.apiBaseUrl,
                    apiKey         = snapshot.apiKey,
                    model          = snapshot.selectedModel,
                    enableThinking = snapshot.enableThinking,
                    history        = history,
                    userText       = cleanedText
                ).collect { event ->
                    when (event) {
                        is AgentEvent.ContentUpdate ->
                            updateLastMessage(event.text, event.reasoning, event.isLoading, event.uploadChars)
                        is AgentEvent.UsageUpdate ->
                            updateLastMessageUsage(event.promptTokens, event.completionTokens)
                        is AgentEvent.PatchProposed -> {
                            _uiState.update { state ->
                                val msgs = state.chatMessages.toMutableList()
                                if (msgs.isEmpty()) return@update state
                                val lastMsg  = msgs.last()
                                val newPatch = PatchItem(event.proposal, PatchState.PENDING)
                                msgs[msgs.lastIndex] = lastMsg.copy(patches = lastMsg.patches + newPatch)
                                state.copy(chatMessages = msgs)
                            }
                        }
                        is AgentEvent.Error ->
                            updateLastMessage("❌ API 报错: ${event.message}", "", false, 0)
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
            messages[messages.lastIndex] = messages.last().copy(
                promptTokens = promptTokens, completionTokens = completionTokens
            )
            state.copy(chatMessages = messages)
        }
    }

    private fun persistChatHistoryAsync() {
        val snapshot = _uiState.value.chatMessages.filterNot { it.isLoading }
        viewModelScope.launch(Dispatchers.IO) { chatHistoryRepo.save(snapshot) }
    }

    private fun loadDirectory(path: String) {
        directoryLoadJob?.cancel()
        directoryLoadJob = viewModelScope.launch {
            _uiState.update { it.copy(isSafLoading = true, currentFiles = emptyList()) }
            try {
                localFileRepository.listFilesFlow(path).collect { files ->
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
    }
}
