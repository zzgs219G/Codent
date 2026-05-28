package com.xixin.codent.presentation.common

import com.xixin.codent.data.model.ChatMessage
import com.xixin.codent.data.model.FileNode
import com.xixin.codent.data.model.PatchProposal
import com.xixin.codent.data.repository.SettingsRepository
import com.xixin.codent.data.repository.ApiProvider

/**
 * 统一的 UI 状态
 * 🔥 所有状态都在这里，没有分散的状态变量
 */
data class MainUiState(
    val directoryStack: List<String> = emptyList(),
    val currentFiles: List<FileNode> = emptyList(),
    val isSafLoading: Boolean = false,
    val selectedFile: FileNode? = null,
    val currentCodeContent: String = "// 在“资源”中选择一个文件打开，可以在此进行代码预览",
    val chatMessages: List<ChatMessage> = listOf(
        ChatMessage("assistant", "Codent Agent 架构师已就绪。请先配置 API 参数。")
    ),
    val apiBaseUrl: String = SettingsRepository.DEFAULT_API_BASE_URL,
    val apiKey: String = "",
    val selectedModel: String = SettingsRepository.DEFAULT_MODEL,
    val enableThinking: Boolean = true,
    val isAgentWorking: Boolean = false,
    val errorMessage: String? = null // 🔥 统一错误状态
)

/**
 * 统一的 UI 事件
 * 🔥 所有用户操作都通过事件发送
 */
sealed class MainUiEvent {
    // 工作区事件
    data class InitWorkspace(val path: String) : MainUiEvent()
    data class NavigateIntoFolder(val path: String) : MainUiEvent()
    object NavigateBack : MainUiEvent()
    data class OpenFile(val fileNode: FileNode) : MainUiEvent()
    
    // 聊天事件
    data class SendMessage(val text: String) : MainUiEvent()
    data class ConfirmPatch(val messageIndex: Int, val patchIndex: Int, val patch: PatchProposal) : MainUiEvent()
    data class RejectPatch(val messageIndex: Int, val patchIndex: Int, val patch: PatchProposal) : MainUiEvent()
    data class UndoPatch(val messageIndex: Int, val patchIndex: Int, val patch: PatchProposal) : MainUiEvent()
    data class DeleteMessage(val index: Int) : MainUiEvent()
    data class EditAndResendMessage(val index: Int, val text: String) : MainUiEvent()
    
    // 设置事件
    data class SaveConfig(val baseUrl: String, val key: String, val model: String) : MainUiEvent()
    data class SaveThinkingEnabled(val enabled: Boolean) : MainUiEvent()
    data class ApplyProvider(val provider: ApiProvider) : MainUiEvent()
    
    // 通用事件
    object ClearError : MainUiEvent()
}

/**
 * 统一的 UI 副作用
 * 🔥 一次性事件（Toast、导航等）
 */
sealed class MainUiEffect {
    data class ShowToast(val message: String) : MainUiEffect()
    object NavigateToPreview : MainUiEffect()
}
