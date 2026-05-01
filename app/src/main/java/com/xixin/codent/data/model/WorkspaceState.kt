package com.xixin.codent.data.model

import android.net.Uri

/**
 * 补丁提议模型：代表 AI 对某个文件的修改建议
 */
data class PatchProposal(
    val targetFileUri: Uri,
    val targetFileName: String,
    val originalContent: String,
    val diffText: String,
    val proposedContent: String 
)

/**
 * 全局 UI 状态源
 */
data class WorkspaceState(
    val directoryStack: List<Uri> = emptyList(),
    val currentFiles: List<FileNode> = emptyList(),
    val isSafLoading: Boolean = false,
    val selectedFile: FileNode? = null,
    val currentCodeContent: String = "// 在“资源”中选择一个文件打开，可以在此进行代码预览",
    
    val chatMessages: List<ChatMessage> = listOf(
        ChatMessage("assistant", "Codent Agent 架构师已就绪。请先配置 API 参数。")
    ),
    
    val apiBaseUrl: String = "https://api.deepseek.com/chat/completions",
    val apiKey: String = "",
    val selectedModel: String = "deepseek-reasoner",
    val enableThinking: Boolean = true,
    
    // 🔥 重要修改：由 pendingPatch: PatchProposal? 改为列表，支持一次性存入多个修改建议
    val pendingPatches: List<PatchProposal> = emptyList(), 
    
    val isAgentWorking: Boolean = false 
)
