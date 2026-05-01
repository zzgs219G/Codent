package com.xixin.codent.core.agent
//AgentEvent.kt

import com.xixin.codent.data.model.PatchProposal

/**
 * 危险操作拦截提示语，集中管理以便统一修改。
 * 被 AiBrain 引用，当用户输入包含危险词时抛出此错误。
 */
const val DANGER_ZONE = "⛔ 操作被安全拦截：检测到危险指令，已拒绝执行。"

sealed class AgentEvent {
    data class ContentUpdate(val text: String, val reasoning: String, val isLoading: Boolean, val uploadChars: Int) : AgentEvent()
    data class UsageUpdate(val promptTokens: Int, val completionTokens: Int) : AgentEvent()
    data class PatchProposed(val proposal: PatchProposal) : AgentEvent()
    data class Error(val message: String) : AgentEvent()
}
