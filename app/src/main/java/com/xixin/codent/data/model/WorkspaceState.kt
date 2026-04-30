package com.xixin.codent.data.model

import android.net.Uri

data class PatchProposal(
    val targetFileUri: Uri,
    val targetFileName: String,
    val originalContent: String,
    val diffString: String,
    val proposedContent: String 
)

data class WorkspaceState(
    val directoryStack: List<Uri> = emptyList(),
    val currentFiles: List<FileNode> = emptyList(),
    val isSafLoading: Boolean = false,
    val selectedFile: FileNode? = null,
    val currentCodeContent: String = "// 在“资源”中选择一个文件打开，AI 将以此为上下文",
    
    val chatMessages: List<ChatMessage> = listOf(
        ChatMessage("assistant", "Codent Agent 已就绪。请先配置 API Key。")
    ),
    
    val apiKey: String = "",
    val selectedModel: String = "deepseek-v4-flash",
    val enableThinking: Boolean = true,
    
    val pendingPatch: PatchProposal? = null,
    val isAgentWorking: Boolean = false 
)