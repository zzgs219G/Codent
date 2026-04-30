// [新建] 负责管理核心的逻辑交互 (MVVM)
package com.xixin.codent.ui.main

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xixin.codent.data.model.ChatMessage
import com.xixin.codent.data.model.FileNode
import com.xixin.codent.data.model.WorkspaceState
import com.xixin.codent.data.repository.SafRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {
    
    private val repository = SafRepository(application)
    
    private val _uiState = MutableStateFlow(WorkspaceState())
    val uiState: StateFlow<WorkspaceState> = _uiState.asStateFlow()

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
            _uiState.update { it.copy(isSafLoading = true) }
            val files = repository.listFiles(uri)
            _uiState.update { it.copy(currentFiles = files, isSafLoading = false) }
        }
    }

    fun openFile(fileNode: FileNode, onOpenComplete: () -> Unit) {
        viewModelScope.launch {
            _uiState.update { it.copy(
                selectedFile = fileNode,
                currentCodeContent = "正在加载代码，请稍候..."
            ) }
            
            val content = repository.readFileContent(fileNode.uri)
            _uiState.update { it.copy(currentCodeContent = content) }
            onOpenComplete()
        }
    }

    fun sendChatMessage(userText: String) {
        val userMsg = ChatMessage("user", userText)
        val loadingMsg = ChatMessage("assistant", "", isLoading = true)
        
        _uiState.update { state ->
            state.copy(chatMessages = state.chatMessages + userMsg + loadingMsg)
        }

        viewModelScope.launch {
            delay(1200)
            val targetFile = _uiState.value.selectedFile?.name ?: "全局项目"
            val aiResponse = ChatMessage(
                role = "assistant",
                content = "已收到关于 [ $targetFile ] 的任务：\n$userText\n\n(系统升级完毕，等待 Ktor 接入...)"
            )
            
            _uiState.update { state ->
                val newMessages = state.chatMessages.dropLast(1) + aiResponse
                state.copy(chatMessages = newMessages)
            }
        }
    }
}