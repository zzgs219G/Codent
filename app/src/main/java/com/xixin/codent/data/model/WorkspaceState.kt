package com.xixin.codent.data.model

import android.net.Uri

/**
 * 唯一的真理之源 (Single Source of Truth)
 */
data class WorkspaceState(
    val directoryStack: List<Uri> = emptyList(),
    val currentFiles: List<FileNode> = emptyList(),
    val isSafLoading: Boolean = false,
    val selectedFile: FileNode? = null,
    val currentCodeContent: String = "// 在“资源”中选择一个文件打开，AI 将以此为上下文",
    
    val chatMessages: List<ChatMessage> = listOf(
        ChatMessage("assistant", "Codent Agent 已就绪。请先配置 API Key 并打开目标文件。")
    ),
    
    // 新增：API Key 配置状态
    val apiKey: String = ""
)