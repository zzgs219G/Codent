// 文件路径: app/src/main/java/com/xixin/codent/core/agent/AgentTools.kt
//
// 替换掉：
//   - AiActionRunner.kt  (手解 JSON + when(name) 分发)
//   - AiToolbox.kt       (手拼 JSON Schema)
//
// LangChain4j 会自动：
//   1. 从 @Tool 注解生成 JSON Schema 注册给模型
//   2. 模型返回 tool_call 时自动反序列化参数、路由到对应函数
//   3. 把函数返回值作为 tool result 塞回对话历史

package com.xixin.codent.core.agent

import android.net.Uri
import com.xixin.codent.data.model.PatchProposal
import com.xixin.codent.data.repository.SafRepository
import com.xixin.codent.wrapper.log.AppLog
import dev.langchain4j.agent.tool.P
import dev.langchain4j.agent.tool.Tool
import kotlinx.coroutines.runBlocking

/**
 * Agent 工具箱。
 *
 * 每个 @Tool 方法 = 原来 AiToolbox 里一个 Tool 定义 + AiActionRunner 里一个 handleXxx 方法。
 *
 * @param repository    SAF 文件操作层，保持不变
 * @param rootUri       当前项目根目录 URI，由 AiBrain 在每次对话开始前注入
 * @param onPatchReady  有补丁需要用户确认时的回调，由 AiBrain 注入
 */
class AgentTools(
    private val repository: SafRepository,
    var rootUri: Uri,                                        // var：每轮对话前 AiBrain 更新
    var onPatchReady: (PatchProposal) -> Unit = {}           // var：同上
) {

    // ─────────────────────────────────────────────
    // 只读工具
    // ─────────────────────────────────────────────

    @Tool("全局搜索代码关键字，返回带行号的匹配片段，最多返回 8 处结果。")
    fun searchKeyword(
        @P("要搜索的关键字") keyword: String
    ): String {
        AppLog.d("✅ [Tool] search_keyword($keyword)")
        return runBlocking { repository.searchKeyword(rootUri, keyword) }
    }

    @Tool("通过文件名在整个项目中查找文件的准确相对路径，速度快于 list_directory，优先使用。")
    fun findFile(
        @P("文件名，支持模糊匹配") file_name: String
    ): String {
        AppLog.d("✅ [Tool] find_file($file_name)")
        return runBlocking { repository.findFilesByName(rootUri, file_name) }
    }

    @Tool("列出指定目录的直接子文件和子目录，根目录传空字符串。")
    fun listDirectory(
        @P("相对于项目根目录的路径，根目录传 ''") path: String
    ): String {
        AppLog.d("✅ [Tool] list_directory($path)")
        return runBlocking { repository.listDirectoryRelative(rootUri, path) }
    }

    @Tool(
        "读取文件的指定行范围。必须同时指定 start_line 和 end_line，" +
        "单次不得超过 800 行，文件小于 3KB 时直接读 1~100 行。"
    )
    fun readFile(
        @P("文件相对路径") path: String,
        @P("起始行号（从 1 开始）") start_line: Int,
        @P("结束行号（含）") end_line: Int
    ): String {
        AppLog.d("✅ [Tool] read_file($path, $start_line~$end_line)")
        return runBlocking {
            val uri = repository.findFileByRelativePath(rootUri, path)
                ?: return@runBlocking "错误：找不到文件 `$path`"
            repository.readFileContent(uri, start_line, end_line)
        }
    }

    // ─────────────────────────────────────────────
    // 写操作工具（生成 PatchProposal，等待用户确认）
    // ─────────────────────────────────────────────

    @Tool(
        "精确替换文件中的一段代码。search_string 必须与原文完全一致（含缩进空格），" +
        "禁止携带行号前缀。替换后会弹出用户确认卡片，确认前不会写入磁盘。"
    )
    fun applyPatch(
        @P("文件相对路径") path: String,
        @P("要被替换的原始代码片段，必须与文件内容完全一致") search_string: String,
        @P("替换后的新代码片段") replace_string: String
    ): String {
        AppLog.d("✅ [Tool] apply_patch → $path")
        return runBlocking {
            val fileUri = repository.findFileByRelativePath(rootUri, path)
                ?: return@runBlocking "错误：找不到文件 `$path`"

            val original = repository.readRawFileContent(fileUri)
            if (original.startsWith("读取异常") || original == "无法读取文件") {
                return@runBlocking "错误：无法读取原文件内容，无法生成补丁。"
            }

            val proposed = CodePatcher.replaceFirstExact(original, search_string, replace_string)
                ?: return@runBlocking "错误：search_string 未在文件中找到，补丁未生成。请确保代码一模一样，不要带行号！"

            val proposal = PatchProposal(
                targetFileUri    = fileUri,
                targetFileName   = path,
                originalContent  = original,
                diffText         = CodePatcher.buildPatchPreview(
                                       path, original, proposed, search_string, replace_string
                                   ),
                proposedContent  = proposed
            )
            AppLog.d("✂️ [Patch 生成成功] $path")
            onPatchReady(proposal)
            "✅ 已生成修改方案，等待用户确认后落盘。目标文件: $path"
        }
    }

    @Tool(
        "新建文件，或在用户确认后覆盖已有文件。" +
        "路径必须是相对项目根目录的完整路径，例如 app/src/main/java/com/example/Foo.kt。"
    )
    fun createFile(
        @P("文件相对路径") path: String,
        @P("文件完整内容") content: String
    ): String {
        AppLog.d("✅ [Tool] create_file → $path")
        return runBlocking {
            val existingUri = repository.findFileByRelativePath(rootUri, path)

            if (existingUri != null) {
                // 文件已存在：走覆盖确认流程
                val original = repository.readRawFileContent(existingUri)
                val diffMsg  = if (content.isEmpty())
                    "⚠️ 警告：AI 请求清空此文件内容（等同于删除）"
                else
                    "⚠️ 警告：AI 请求完全覆盖此文件"

                onPatchReady(
                    PatchProposal(
                        targetFileUri   = existingUri,
                        targetFileName  = path,
                        originalContent = original,
                        diffText        = "$diffMsg\n\n预览内容:\n$content",
                        proposedContent = content
                    )
                )
                "✅ 已发起覆盖/清空文件提议，必须等待用户审批！"
            } else {
                // 真正新建：先占位拿 URI，再走确认流程
                repository.createFileByRelativePath(rootUri, path, "")
                val newUri = repository.findFileByRelativePath(rootUri, path)
                    ?: return@runBlocking "❌ 创建空文件占位失败，无法发起提议。"

                onPatchReady(
                    PatchProposal(
                        targetFileUri   = newUri,
                        targetFileName  = "$path（新建）",
                        originalContent = "",
                        diffText        = "✨ AI 请求创建新文件，预览如下:\n\n$content",
                        proposedContent = content
                    )
                )
                "✅ 已发起新建文件提议，等待用户审批。"
            }
        }
    }
}
