package com.xixin.codent.presentation.main

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xixin.codent.core.agent.AgentEvent
import com.xixin.codent.core.agent.AiBrain
import com.xixin.codent.data.model.ChatMessage
import com.xixin.codent.data.model.PatchItem
import com.xixin.codent.data.model.PatchState
import com.xixin.codent.data.model.PatchProposal
import com.xixin.codent.data.model.FileNode
import com.xixin.codent.data.repository.ApiProvider
import com.xixin.codent.data.repository.ChatHistoryRepository
import com.xixin.codent.data.repository.LocalFileRepository
import com.xixin.codent.data.repository.SettingsRepository
import com.xixin.codent.presentation.common.MainUiEffect
import com.xixin.codent.presentation.common.MainUiEvent
import com.xixin.codent.presentation.common.MainUiState
import com.xixin.codent.wrapper.log.AppLog
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel // 🔥 标记为 Hilt ViewModel
class MainViewModel @Inject constructor(
    application: Application,
    private val localFileRepository: LocalFileRepository,
    private val settingsRepo: SettingsRepository,
    private val chatHistoryRepo: ChatHistoryRepository,
    private val aiBrain: AiBrain
) : AndroidViewModel(application) {

    // 🔥 单一状态源
    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    // 🔥 单一副作用通道
    private val _uiEffect = Channel<MainUiEffect>(Channel.BUFFERED)
    val uiEffect: Flow<MainUiEffect> = _uiEffect.receiveAsFlow()

    private var directoryLoadJob: Job? = null
    private var agentJob: Job? = null

    init {
        // 初始化状态
        _uiState.update {
            it.copy(
                apiBaseUrl = settingsRepo.getApiBaseUrl(),
                apiKey = settingsRepo.getApiKey(),
                selectedModel = settingsRepo.getSelectedModel(),
                enableThinking = settingsRepo.isThinkingEnabled(),
                chatMessages = chatHistoryRepo.load()
            )
        }
    }

    // 🔥 统一事件入口
    fun onEvent(event: MainUiEvent) {
        when (event) {
            is MainUiEvent.InitWorkspace -> initWorkspace(event.path)
            is MainUiEvent.NavigateIntoFolder -> navigateIntoFolder(event.path)
            MainUiEvent.NavigateBack -> navigateBack()
            is MainUiEvent.OpenFile -> openFile(event.fileNode)
            is MainUiEvent.SendMessage -> sendChatMessage(event.text)
            is MainUiEvent.ConfirmPatch -> confirmPatch(event.messageIndex, event.patchIndex, event.patch)
            is MainUiEvent.RejectPatch -> rejectPatch(event.messageIndex, event.patchIndex, event.patch)
            is MainUiEvent.UndoPatch -> undoPatch(event.messageIndex, event.patchIndex, event.patch)
            is MainUiEvent.DeleteMessage -> deleteMessage(event.index)
            is MainUiEvent.EditAndResendMessage -> editAndResendMessage(event.index, event.text)
            is MainUiEvent.SaveConfig -> saveConfig(event.baseUrl, event.key, event.model)
            is MainUiEvent.SaveThinkingEnabled -> saveThinkingEnabled(event.enabled)
            is MainUiEvent.ApplyProvider -> applyProvider(event.provider)
            MainUiEvent.ClearError -> clearError()
        }
    }

    // 🔥 所有私有方法保持不变，但现在只负责状态更新
    private fun initWorkspace(path: String) {
        _uiState.update { it.copy(directoryStack = listOf(path)) }
        loadDirectory(path)
    }

    private fun navigateIntoFolder(path: String) {
        _uiState.update { it.copy(directoryStack = it.directoryStack + listOf(path)) }
        loadDirectory(path)
    }

    private fun navigateBack(): Boolean {
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

    private fun openFile(fileNode: FileNode) {
        viewModelScope.launch {
            _uiState.update { it.copy(selectedFile = fileNode, currentCodeContent = "正在加载...") }
            val content = localFileRepository.readFileContent(fileNode.path)
            _uiState.update { it.copy(currentCodeContent = content) }
            _uiEffect.send(MainUiEffect.NavigateToPreview)
        }
    }

    private fun clearChat() {
        _uiState.update { it.copy(chatMessages = emptyList()) }
        viewModelScope.launch(Dispatchers.IO) { chatHistoryRepo.clear() }
    }

    private fun deleteMessage(index: Int) {
        if (index < 0) {
            clearChat()
            return
        }
        if (index !in _uiState.value.chatMessages.indices) return
        _uiState.update { state ->
            state.copy(chatMessages = state.chatMessages.subList(0, index).toList())
        }
        persistChatHistoryAsync()
    }

    private fun editAndResendMessage(index: Int, newText: String) {
        val state = _uiState.value
        if (index !in state.chatMessages.indices || state.chatMessages[index].role != "user") return
        _uiState.update { current ->
            current.copy(chatMessages = current.chatMessages.subList(0, index).toList())
        }
        sendChatMessage(newText)
    }

    private fun confirmPatch(messageIndex: Int, patchIndex: Int, patch: PatchProposal) {
        viewModelScope.launch {
            val success = localFileRepository.overwriteFile(patch.targetFilePath, patch.proposedContent)
            if (success) {
                AppLog.d("💾 [文件落盘]: ✅ 用户确认修改成功: ${patch.targetFileName}")
                _uiState.update { state ->
                    val msgs = state.chatMessages.toMutableList()
                    if (messageIndex in msgs.indices) {
                        val targetMsg = msgs[messageIndex]
                        val updatedPatches = targetMsg.patches.toMutableList()
                        if (patchIndex in updatedPatches.indices) {
                            updatedPatches[patchIndex] = updatedPatches[patchIndex].copy(state = PatchState.APPLIED)
                            msgs[messageIndex] = targetMsg.copy(patches = updatedPatches)
                        }
                    }
                    val updatedContent = if (state.selectedFile?.path == patch.targetFilePath)
                        patch.proposedContent else state.currentCodeContent
                    state.copy(chatMessages = msgs, currentCodeContent = updatedContent)
                }
                persistChatHistoryAsync()
                _uiEffect.send(MainUiEffect.ShowToast("✅ 修改已应用"))
            } else {
                AppLog.e("❌ [文件落盘]: 保存失败: ${patch.targetFileName}")
                _uiEffect.send(MainUiEffect.ShowToast("❌ 保存失败"))
            }
        }
    }

    private fun rejectPatch(messageIndex: Int, patchIndex: Int, patch: PatchProposal) {
        _uiState.update { state ->
            val msgs = state.chatMessages.toMutableList()
            if (messageIndex in msgs.indices) {
                val targetMsg = msgs[messageIndex]
                val updatedPatches = targetMsg.patches.toMutableList()
                if (patchIndex in updatedPatches.indices) {
                    updatedPatches[patchIndex] = updatedPatches[patchIndex].copy(state = PatchState.REJECTED)
                    msgs[messageIndex] = targetMsg.copy(patches = updatedPatches)
                }
            }
            state.copy(chatMessages = msgs)
        }
        persistChatHistoryAsync()
    }

    private fun undoPatch(messageIndex: Int, patchIndex: Int, patch: PatchProposal) {
        viewModelScope.launch {
            _uiEffect.send(MainUiEffect.ShowToast("撤回功能研发中..."))
        }
    }

    private fun sendChatMessage(userText: String) {
        val cleanedText = userText.trim()
        if (cleanedText.isBlank()) return

        val snapshot = _uiState.value
        if (snapshot.apiKey.isBlank()) {
            appendMessage(ChatMessage("assistant", "❌ 请先在「设置」中配置 API Key"))
            return
        }

        val rootPath = snapshot.directoryStack.lastOrNull() ?: run {
            appendMessage(ChatMessage("assistant", "❌ 请先在「资源」中选择项目根目录"))
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
                    rootPath = rootPath,
                    apiBaseUrl = snapshot.apiBaseUrl,
                    apiKey = snapshot.apiKey,
                    model = snapshot.selectedModel,
                    enableThinking = snapshot.enableThinking,
                    history = history,
                    userText = cleanedText
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
                                val lastMsg = msgs.last()
                                val newPatch = PatchItem(event.proposal, PatchState.PENDING)
                                msgs[msgs.lastIndex] = lastMsg.copy(patches = lastMsg.patches + newPatch)
                                state.copy(chatMessages = msgs)
                            }
                        }
                        is AgentEvent.Error -> {
                            updateLastMessage(event.message, "", false, 0)
                            _uiState.update { it.copy(errorMessage = event.message) }
                        }
                    }
                }
            } catch (e: Exception) {
                val errorMsg = "❌ 运行异常: ${e.localizedMessage}"
                updateLastMessage(errorMsg, "", false, 0)
                _uiState.update { it.copy(errorMessage = errorMsg) }
            } finally {
                _uiState.update { it.copy(isAgentWorking = false) }
                persistChatHistoryAsync()
            }
        }
    }

    private fun saveConfig(baseUrl: String, key: String, model: String) {
        settingsRepo.saveApiBaseUrl(baseUrl)
        settingsRepo.saveApiKey(key)
        settingsRepo.saveSelectedModel(model)
        _uiState.update { it.copy(apiBaseUrl = baseUrl, apiKey = key, selectedModel = model) }
        viewModelScope.launch {
            _uiEffect.send(MainUiEffect.ShowToast("✅ 配置已保存"))
        }
    }

    private fun saveThinkingEnabled(enabled: Boolean) {
        settingsRepo.saveThinkingEnabled(enabled)
        _uiState.update { it.copy(enableThinking = enabled) }
    }

    private fun applyProvider(provider: ApiProvider) {
        settingsRepo.applyProvider(provider)
        _uiState.update { state ->
            state.copy(
                apiBaseUrl = if (provider.baseUrl.isNotBlank()) provider.baseUrl else state.apiBaseUrl,
                selectedModel = if (provider.defaultModel.isNotBlank()) provider.defaultModel else state.selectedModel
            )
        }
        viewModelScope.launch {
            _uiEffect.send(MainUiEffect.ShowToast("已切换到 ${provider.displayName}"))
        }
    }

    private fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    // 🔥 私有辅助方法保持不变
    private fun appendMessage(message: ChatMessage) {
        _uiState.update { state -> state.copy(chatMessages = state.chatMessages + message) }
    }

    private fun updateLastMessage(text: String, reasoning: String, isLoading: Boolean, uploadChars: Int) {
        _uiState.update { state ->
            val messages = state.chatMessages.toMutableList()
            if (messages.isEmpty()) return@update state
            messages[messages.lastIndex] = messages.last().copy(
                content = text,
                reasoningContent = reasoning,
                isLoading = isLoading,
                uploadChars = uploadChars
            )
            state.copy(chatMessages = messages)
        }
    }

    private fun updateLastMessageUsage(promptTokens: Int, completionTokens: Int) {
        _uiState.update { state ->
            val messages = state.chatMessages.toMutableList()
            if (messages.isEmpty()) return@update state
            messages[messages.lastIndex] = messages.last().copy(
                promptTokens = promptTokens,
                completionTokens = completionTokens
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
                _uiState.update { it.copy(isSafLoading = false, errorMessage = "加载目录失败: ${e.message}") }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        directoryLoadJob?.cancel()
        agentJob?.cancel()
    }
}
