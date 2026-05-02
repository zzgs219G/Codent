// [文件路径: app/src/main/java/com/xixin/codent/data/model/ChatMessage.kt]
package com.xixin.codent.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * 🔥 补丁状态：现在是全局唯一的标准
 */
enum class PatchState { PENDING, APPLIED, REJECTED }

/**
 * 补丁包装类
 */
data class PatchItem(
    val proposal: PatchProposal,
    val state: PatchState = PatchState.PENDING
)

/**
 * 聊天消息的数据模型
 */
@Serializable
data class ChatMessage(
    val role: String,
    val content: String,
    val reasoningContent: String = "", 
    val isLoading: Boolean = false,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val uploadChars: Int = 0,
    
    // 🔥 核心链接：补丁现在是 ChatMessage 的一部分
    @Transient
    val patches: List<PatchItem> = emptyList() 
)
