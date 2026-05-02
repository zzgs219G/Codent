// [文件路径: app/src/main/java/com/xixin/codent/core/agent/AiBrain.kt]
package com.xixin.codent.core.agent

import android.net.Uri
import com.xixin.codent.data.api.*
import com.xixin.codent.data.repository.SafRepository
import com.xixin.codent.wrapper.log.AppLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class AiBrain(
    private val aiService: AiApiService,
    private val repository: SafRepository
) {
    private val actionRunner = AiActionRunner(repository)

    // 🔥 核心修复：引入轻量级内存缓存，防止连续对话时重复遍历 SAF
    private var cachedProjectTree: String? = null
    private var lastRootUriString: String? = null
    private var lastCacheTimeMs: Long = 0L

    fun startConversation(
        rootUri: Uri,
        apiBaseUrl: String,
        apiKey: String,
        model: String,
        enableThinking: Boolean,
        history: List<ApiMessage>,
        userText: String
    ): Flow<AgentEvent> = flow {
        
        // 🛡️ 安全拦截
        val dangerKeywords = listOf("销毁项目", "删除所有", "rm -rf", "清空项目")
        if (dangerKeywords.any { userText.contains(it, ignoreCase = true) }) {
            emit(AgentEvent.Error("⛔ 操作被安全拦截：检测到危险指令，已拒绝执行。"))
            return@flow
        }

        // 🌳 地图扫描 (🔥 加入缓存拦截机制)
        val currentTime = System.currentTimeMillis()
        val uriString = rootUri.toString()
        val projectTree = if (cachedProjectTree != null && lastRootUriString == uriString && (currentTime - lastCacheTimeMs < 5 * 60 * 1000)) {
            AppLog.d("🌳 [命中内存缓存] 5分钟内免遍历，极速复用全局目录树！")
            cachedProjectTree!!
        } else {
            val tree = repository.generateProjectTree(rootUri, maxDepth = 12)
            cachedProjectTree = tree
            lastRootUriString = uriString
            lastCacheTimeMs = currentTime
            tree
        }
        AppLog.d("🌳 [项目全局目录树生成完毕] (长度: ${projectTree.length} 字符):\n$projectTree")

        // 🧠 [完全保留]：你的 8 大红线提示词，一字未改！
        // 如果你想让它配合断路器闭嘴，请手动修改第 4 条。
        val systemPrompt = """
            你是一个顶级 Android 架构师 Agent。
            
            【项目全局透视图 (含文件大小)】：
            $projectTree

            【红线警告与执行规范】：
            1. 【全图视野】：我已经把项目文件树交给你了，寻找文件时必须优先对照上面的目录树！
            2. 【大小感知与禁止问路】：观察文件名括号中的大小（如 30.5KB）。1KB 约等于 30-40 行代码。绝对严禁调用 find_file 查找已知文件！地图中的 . 代表基准路径。你要找的文件绝对路径 = 基准路径 + /文件名。你必须在脑海中完成拼接，并直接调用 read_file！
            3. 【大胃王读取 (省钱关键)】：严禁进行小于 100 行的“试探性”读取！如果文件 < 3KB，请直接 read_file(1, 100) 一次性读完。如果文件较大，首轮读取建议范围 1-300 行。目标是在 2 轮内解决战斗。
            4. 【静默执行与强制总结】：调用工具时直接输出 JSON。但是，在执行完所有的修改（apply_patch / create_file）后，你必须在最后一轮输出一段中文，总结你修改了什么，让用户在界面上点击确认。严禁静默结束！
            5. 【精准替换】：修改代码 apply_patch 时 search_string 必须完全复制原文。
            6. 【连击协同】：你具备一次性修改多个文件的能力。如果需求涉及多个类，请连续调用多次 apply_patch。
            7. 【单次上限】：单次读取文件 read_file 不得超过 800 行。
            8. 仅使用纯文本回复，禁止扮演用户。
        """.trimIndent()

        val apiMessages = mutableListOf<ApiMessage>()
        apiMessages.add(ApiMessage(role = "system", content = systemPrompt))
        apiMessages.addAll(history)
        apiMessages.add(ApiMessage(role = "user", content = userText))

        AppLog.d("runAgentConversation: system=${systemPrompt.length} chars | history=${history.size} | user=$userText")

        var iteration = 0
        var consecutiveToolOnlyIterations = 0
        var totalAccumulatedText = ""
        var totalAccumulatedReasoning = ""
        var totalPromptTokens = 0
        var totalCompletionTokens = 0
        var currentUploadChars = estimateChars(apiMessages)
        
        while (iteration < 30 && consecutiveToolOnlyIterations < 15) {
            iteration++
            AppLog.d("Agent Loop: 第 $iteration 轮开始 | apiMessages=${apiMessages.size}")

            val toolCallsBuffer = mutableListOf<ToolCall>()
            var finalUsage: Usage? = null
            var errorOccurred = false
            var hasReceivedContent = false

            val stream = aiService.getAgentCompletionStream(
                apiBaseUrl, apiKey, model, apiMessages, AiToolbox.agentTools, enableThinking
            )

            stream.collect { event ->
                when (event) {
                    is StreamEvent.Content -> {
                        hasReceivedContent = true
                        totalAccumulatedText += event.text
                        emit(AgentEvent.ContentUpdate(totalAccumulatedText, totalAccumulatedReasoning, true, currentUploadChars))
                    }
                    is StreamEvent.Reasoning -> {
                        AppLog.d("🤔 [AI 思考]: ${event.text.replace("\n", " ")}")
                        totalAccumulatedReasoning += event.text
                        emit(AgentEvent.ContentUpdate(totalAccumulatedText, totalAccumulatedReasoning, true, currentUploadChars))
                    }
                    is StreamEvent.ToolCallStarted -> {
                        AppLog.d("🔧 [AI 调度工具]: ${event.functionName}")
                        val tip = "> 🤖 正在调度工具: ${event.functionName} ..."
                        totalAccumulatedReasoning += if (totalAccumulatedReasoning.isNotEmpty()) "\n\n$tip" else tip
                        emit(AgentEvent.ContentUpdate(totalAccumulatedText, totalAccumulatedReasoning, true, currentUploadChars))
                    }
                    is StreamEvent.ToolCallComplete -> {
                        toolCallsBuffer.add(
                            ToolCall(
                                index = toolCallsBuffer.size,
                                id = event.toolCallId,
                                function = FunctionCall(name = event.functionName, arguments = event.arguments)
                            )
                        )
                    }
                    is StreamEvent.Done -> finalUsage = event.usage
                    is StreamEvent.Error -> {
                        errorOccurred = true
                        AppLog.e("❌ 流错误: ${event.message}")
                        emit(AgentEvent.Error(event.message))
                    }
                }
            }

            if (errorOccurred) return@flow

            if (!hasReceivedContent && toolCallsBuffer.isNotEmpty()) {
                consecutiveToolOnlyIterations++
            } else {
                consecutiveToolOnlyIterations = 0
            }

            finalUsage?.let {
                totalPromptTokens += it.promptTokens
                totalCompletionTokens += it.completionTokens
                AppLog.d("Agent Loop: usage 累加 -> prompt=$totalPromptTokens | completion=$totalCompletionTokens")
                emit(AgentEvent.UsageUpdate(totalPromptTokens, totalCompletionTokens))
            }

            apiMessages.add(
                ApiMessage(
                    role = "assistant",
                    content = if (toolCallsBuffer.isNotEmpty()) null else totalAccumulatedText.takeIf { it.isNotBlank() },
                    toolCalls = toolCallsBuffer.takeIf { it.isNotEmpty() }
                )
            )

            if (toolCallsBuffer.isEmpty()) {
                AppLog.d("Agent Loop: 本轮没有工具调用，对话正常结束")
                break
            }

            var hasModificationInThisRound = false
            var hasReadingInThisRound = false

            for (toolCall in toolCallsBuffer) {
                val funcName = toolCall.function.name ?: "unknown"
                val argsStr = toolCall.function.arguments

                // 记录本轮动作，用于判断是否触发断路器
                if (funcName == "apply_patch" || funcName == "create_file") hasModificationInThisRound = true
                if (funcName == "read_file" || funcName == "search_keyword" || funcName == "find_files_by_name" || funcName == "list_directory") hasReadingInThisRound = true

                val displayArgs = actionRunner.buildDisplayArgs(funcName, argsStr)
                totalAccumulatedReasoning += "\n> 🎯 参数: $displayArgs"
                emit(AgentEvent.ContentUpdate(totalAccumulatedText, totalAccumulatedReasoning, true, currentUploadChars))

                val result = actionRunner.executeTool(funcName, argsStr, rootUri) { proposal ->
                    emit(AgentEvent.PatchProposed(proposal))
                }

                apiMessages.add(ApiMessage(role = "tool", toolCallId = toolCall.id, content = result, name = funcName))
                currentUploadChars += result.length

                totalAccumulatedReasoning += "\n> 📦 工具返回长度: ${result.length} 字符\n"
                emit(AgentEvent.ContentUpdate(totalAccumulatedText, totalAccumulatedReasoning, true, currentUploadChars))
            }

            // 🛑 [终极物理断路器]：直接省掉第三轮的钱！
            if (hasModificationInThisRound && !hasReadingInThisRound) {
                AppLog.d("💰 [极客节流]: 补丁已全部抛出，UI已接管审批，直接掐断 AI 总结回合！")
                break
            }
        }

        AppLog.d("runAgentConversation: 结束")
        emit(AgentEvent.ContentUpdate(totalAccumulatedText, totalAccumulatedReasoning, false, currentUploadChars))
    }

    private fun estimateChars(messages: List<ApiMessage>): Int =
        messages.sumOf { (it.content?.length ?: 0) + (it.reasoningContent?.length ?: 0) }
}
