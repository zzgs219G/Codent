package com.xixin.codent.ui.main

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xixin.codent.data.api.AiApiService
import com.xixin.codent.data.model.ChatMessage
import com.xixin.codent.data.model.FileNode
import com.xixin.codent.data.model.WorkspaceState
import com.xixin.codent.data.repository.SafRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

class MainViewModel(application: Application) : AndroidViewModel(application) {
    
    private val repository = SafRepository(application)
    
    // 关键升级：深度思考模型通常需要 30~90 秒才会返回结果
    private val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; isLenient = true })
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 180_000 // 180秒总请求超时
            connectTimeoutMillis = 15_000  // 15秒连接超时
            socketTimeoutMillis = 180_000  // 180秒读写超时，给足 AI 思考的时间
        }
    }
    private val aiService = AiApiService(httpClient)
    
    private val _uiState = MutableStateFlow(WorkspaceState())
    val uiState: StateFlow<WorkspaceState> = _uiState.asStateFlow()

    init {
        _uiState.update { it.copy(apiKey = repository.getApiKey()) }
    }

    fun saveApiKey(key: String) {
        repository.saveApiKey(key)
        _uiState.update { it.copy(apiKey = key) }
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
        val currentState = _uiState.value
        val userMsg = ChatMessage("user", userText)
        val loadingMsg = ChatMessage("assistant", "", isLoading = true)
        
        _uiState.update { it.copy(chatMessages = it.chatMessages + userMsg + loadingMsg) }

        viewModelScope.launch {
            val contextFile = currentState.selectedFile?.name ?: "无上下文"
            val systemPrompt = "你是一个 Android AI 编程助手。当前用户正在查看文件: $contextFile。\n代码内容:\n${currentState.currentCodeContent.take(2000)}"
            
            val responseText = aiService.getCompletion(
                apiKey = currentState.apiKey,
                prompt = userText,
                systemPrompt = systemPrompt
            )
            
            val aiMsg = ChatMessage(role = "assistant", content = responseText)
            _uiState.update { state ->
                val newMessages = state.chatMessages.dropLast(1) + aiMsg
                state.copy(chatMessages = newMessages)
            }
        }
    }
}