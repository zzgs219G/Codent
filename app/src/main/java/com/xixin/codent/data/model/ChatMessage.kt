package com.xixin.codent.data.model

/**
 * 聊天消息的数据模型
 * 包含了流式加载状态以及 Token 账单信息
 */
data class ChatMessage(
    val role: String,
    val content: String,
    val isLoading: Boolean = false,
    // 老板专属：计费和统计字段
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val uploadChars: Int = 0
)