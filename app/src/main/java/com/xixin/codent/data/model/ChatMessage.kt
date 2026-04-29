package com.xixin.codent.data.model

/**
 * 聊天消息的数据模型
 * @param role 角色："user" 代表你，"assistant" 代表 AI，"system" 留给内部 Prompt
 * @param content 聊天文本内容
 * @param isLoading 是否正在“思考中”（用于 UI 显示加载动画）
 */
data class ChatMessage(
    val role: String,
    val content: String,
    val isLoading: Boolean = false
)