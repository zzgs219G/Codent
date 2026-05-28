// 文件路径: app/src/main/java/com/xixin/codent/core/agent/AiBrain.kt
//
// 重构内容：
//   - 删除手写 while 循环状态机（原 130 行）
//   - 删除对 AiApiService / AiToolbox 的所有依赖
//   - LangChain4j AiServices 接管 Tool Use Loop、消息历史、流式输出
//   - MainViewModel.sendChatMessage() 调用侧签名不变，零改动

package com.xixin.codent.core.agent

import android.net.Uri
import com.xixin.codent.data.repository.SafRepository
import com.xixin.codent.wrapper.log.AppLog
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.memory.chat.MessageWindowChatMemory
import dev.langchain4j.model.openai.OpenAiStreamingChatModel
import dev.langchain4j.model.output.Response
import dev.langchain4j.service.AiServices
import dev.langchain4j.service.TokenStream
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

// ─────────────────────────────────────────────────────────────
// Agent 接口：LangChain4j 会在运行时自动生成实现类
// ─────────────────────────────────────────────────────────────
private interface CodentAgentService {
    fun chat(message: String): TokenStream
}

// ─────────────────────────────────────────────────────────────
// AiBrain：对外接口与原来完全一致，MainViewModel 零改动
// ─────────────────────────────────────────────────────────────
class AiBrain(private val repository: SafRepository) {

    // 项目树缓存（逻辑不变，只是移到 Brain 内部）
    private var cachedProjectTree: String? = null
    private var lastRootUriString: String? = null
    private var lastCacheTimeMs: Long = 0L

    // AgentTools 实例在 Brain 生命周期内复用，rootUri/onPatchReady 每轮对话前更新
    private val agentTools = AgentTools(
        repository   = repository,
        rootUri      = Uri.EMPTY,       // 占位，startConversation 开头会更新
        onPatchReady = {}               // 占位，同上
    )

    fun startConversation(
        rootUri: Uri,
        apiBaseUrl: String,
        apiKey: String,
        model: String,
        enableThinking: Boolean,        // 保留参数，兼容 ViewModel 调用侧；OpenAI 兼容接口通过 model 名控制
        history: List<com.xixin.codent.data.api.ApiMessage>,
        userText: String
    ): Flow<AgentEvent> = callbackFlow {

        // ── 1. 安全拦截（原逻辑不变）──────────────────────────────
        val dangerKeywords = listOf("销毁项目", "删除所有", "rm -rf", "清空项目")
        if (dangerKeywords.any { userText.contains(it, ignoreCase = true) }) {
            trySend(AgentEvent.Error(DANGER_ZONE))
            close()
            return@callbackFlow
        }

        // ── 2. 项目树缓存（原逻辑不变）───────────────────────────
        val now       = System.currentTimeMillis()
        val uriString = rootUri.toString()
        val projectTree = if (
            cachedProjectTree != null &&
            lastRootUriString == uriString &&
            now - lastCacheTimeMs < 5 * 60 * 1000L
        ) {
            AppLog.d("🌳 [命中缓存] 复用目录树")
            cachedProjectTree!!
        } else {
            repository.generateProjectTree(rootUri, maxDepth = 12).also {
                cachedProjectTree  = it
                lastRootUriString  = uriString
                lastCacheTimeMs    = now
                AppLog.d("🌳 [目录树生成完毕] 长度=${it.length}")
            }
        }

        // ── 3. 更新工具实例的运行时上下文 ────────────────────────
        agentTools.rootUri    = rootUri
        agentTools.onPatchReady = { proposal ->
            trySend(AgentEvent.PatchProposed(proposal))
        }

        // ── 4. 构建 System Prompt（8 大红线原文保留）────────────
        val systemPrompt = """
            你是一个顶级 Android 架构师 Agent。
            【项目全局透视图 (含文件大小)】：
            $projectTree
            【红线警告与执行规范】：
            1. 【全图视野】：我已经把项目文件树交给你了，寻找文件时必须优先对照上面的目录树！
            2. 【大小感知与禁止问路】：观察文件名括号中的大小（如 30.5KB）。1KB 约等于 30-40 行代码。绝对严禁调用 find_file 查找已知文件！地图中的 . 代表基准路径。你要找的文件绝对路径 = 基准路径 + /文件名。你必须在脑解中完成拼接，并直接调用 read_file！
            3. 【大胃王读取 (省钱关键)】：严禁进行小于 100 行的"试探性"读取！如果文件 < 3KB，请直接 read_file(1, 100) 一次性读完。如果文件较大，首轮读取建议范围 1-300 行。目标是在 2 轮内解决战斗。
            4. 【静默执行与强制总结】：调用工具时直接输出 JSON。但是，在执行完所有的修改（apply_patch / create_file）后，你必须在最后一轮输出一段中文，总结你修改了什么，让用户在界面上点击确认。严禁静默结束！
            5. 【精准替换】：修改代码 apply_patch 时 search_string 必须完全复制原文。
            6. 【连击协同】：你具备一次性修改多个文件的能力。如果需求涉及多个类，请连续调用多次 apply_patch。
            7. 【单次上限】：单次读取文件 read_file 不得超过 800 行。
            8. 仅使用纯文本回复，禁止扮演用户。
        """.trimIndent()

        // ── 5. 把 ViewModel 传来的历史转成 LangChain4j 消息格式 ─
        val lcHistory: List<ChatMessage> = history.mapNotNull { msg ->
            when (msg.role) {
                "user"      -> UserMessage.from(msg.content ?: return@mapNotNull null)
                "assistant" -> AiMessage.from(msg.content ?: return@mapNotNull null)
                else        -> null   // tool 消息由框架自己管理，不需要手动传入
            }
        }

        // ── 6. 构建 LangChain4j 流式模型（OpenAI 兼容格式）──────
        //    baseUrl 去掉末尾的 /chat/completions，LangChain4j 自己拼
        val cleanBaseUrl = apiBaseUrl
            .removeSuffix("/chat/completions")
            .trimEnd('/')

        val streamingModel = OpenAiStreamingChatModel.builder()
            .baseUrl(cleanBaseUrl)
            .apiKey(apiKey)
            .modelName(model)
            .build()

        // ── 7. 组装 Memory：系统提示 + 历史 ─────────────────────
        //    MessageWindowChatMemory 窗口设 60，足够长对话；超出自动裁剪
        val memory = MessageWindowChatMemory.withMaxMessages(60)
        memory.add(SystemMessage.from(systemPrompt))
        lcHistory.forEach { memory.add(it) }

        // ── 8. 用 AiServices 构建 Agent，注入工具 ────────────────
        //    框架自动：生成 Tool Schema → 管理 Tool Use Loop → 注入工具结果
        val agentService = AiServices.builder(CodentAgentService::class.java)
            .streamingChatLanguageModel(streamingModel)
            .tools(agentTools)              // @Tool 方法自动扫描注册
            .chatMemory(memory)
            .build()

        // ── 9. 发起流式对话，把事件转发到 Flow ───────────────────
        var accumulatedText      = ""
        var accumulatedReasoning = ""
        var totalPromptTokens    = 0
        var totalCompletionTokens = 0

        AppLog.d("AiBrain: 开始对话 | model=$model | user=${userText.take(60)}")

        agentService.chat(userText)
            .onNext { token ->
                accumulatedText += token
                trySend(
                    AgentEvent.ContentUpdate(
                        text        = accumulatedText,
                        reasoning   = accumulatedReasoning,
                        isLoading   = true,
                        uploadChars = 0
                    )
                )
            }
            .onToolExecuted { toolExecution ->
                // 工具被调用时，把函数名追加到 reasoning 区域显示（与原来效果一致）
                val tip = "> 🤖 正在调度工具: ${toolExecution.request().name()} ..."
                accumulatedReasoning += if (accumulatedReasoning.isNotEmpty()) "\n\n$tip" else tip
                AppLog.d("🔧 [Tool 执行完毕]: ${toolExecution.request().name()}")
                trySend(
                    AgentEvent.ContentUpdate(
                        text        = accumulatedText,
                        reasoning   = accumulatedReasoning,
                        isLoading   = true,
                        uploadChars = 0
                    )
                )
            }
            .onComplete { response: Response<AiMessage> ->
                // 累计 token 用量
                response.tokenUsage()?.let { usage ->
                    totalPromptTokens     += usage.inputTokenCount()  ?: 0
                    totalCompletionTokens += usage.outputTokenCount() ?: 0
                    AppLog.d("✅ 对话完成 | prompt=$totalPromptTokens | completion=$totalCompletionTokens")
                    trySend(AgentEvent.UsageUpdate(totalPromptTokens, totalCompletionTokens))
                }
                trySend(
                    AgentEvent.ContentUpdate(
                        text        = accumulatedText,
                        reasoning   = accumulatedReasoning,
                        isLoading   = false,
                        uploadChars = 0
                    )
                )
                close()   // 正常结束，关闭 Flow
            }
            .onError { error ->
                AppLog.e("❌ AiBrain 流错误: ${error.message}")
                trySend(AgentEvent.Error(error.message ?: "未知错误"))
                close(error)
            }
            .start()

        // Flow 取消时无需额外清理（LangChain4j 流本身会被 GC）
        awaitClose { AppLog.d("AiBrain: Flow 已关闭") }
    }
}
