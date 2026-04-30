// [新建] 将所有 UI 状态抽离到 Model 层
package com.xixin.codent.data.model

import android.net.Uri

data class WorkspaceState(
    val directoryStack: List<Uri> = emptyList(),
    val currentFiles: List<FileNode> = emptyList(),
    val isSafLoading: Boolean = false,
    val selectedFile: FileNode? = null,
    val currentCodeContent: String = "// 在“资源管理器”中选择一个文件打开，AI 就会以它作为目标开始工作。",
    val chatMessages: List<ChatMessage> = listOf(
        ChatMessage("assistant", "Codent Agent 已就绪。请先在资源管理器中打开你要修改的文件。")
    )
)