package com.xixin.codent.core.agent

import com.xixin.codent.data.model.PatchProposal
import com.xixin.codent.data.repository.LocalFileRepository
import com.xixin.codent.wrapper.log.AppLog
import dev.langchain4j.agent.tool.P
import dev.langchain4j.agent.tool.Tool
import kotlinx.coroutines.runBlocking
import java.io.File // 🔥 拥抱最直接的文件操作

class AgentTools(
    private val repository: LocalFileRepository,
    var rootPath: String, // 🔥 已经是干净的绝对路径了
    var onPatchReady: (PatchProposal) -> Unit = {}
) {

    @Tool("全局搜索代码关键字，返回带行号的匹配片段，最多返回 8 处结果。")
    fun searchKeyword(@P("要搜索的关键字") keyword: String): String {
        AppLog.d("✅ [Tool] search_keyword($keyword)")
        return runBlocking { repository.searchKeyword(rootPath, keyword) }
    }

    @Tool("通过文件名在整个项目中查找文件的准确相对路径，速度快于 list_directory，优先使用。")
    fun findFile(@P("文件名，支持模糊匹配") file_name: String): String {
        AppLog.d("✅ [Tool] find_file($file_name)")
        return runBlocking { repository.findFilesByName(rootPath, file_name) }
    }

    @Tool("列出指定目录的直接子文件和子目录，根目录传空字符串。")
    fun listDirectory(@P("相对于项目根目录的路径，根目录传 ''") path: String): String {
        AppLog.d("✅ [Tool] list_directory($path)")
        return runBlocking { repository.listDirectoryRelative(rootPath, path) }
    }

    @Tool("读取文件的指定行范围。必须同时指定 start_line 和 end_line...")
    fun readFile(
        @P("文件相对路径") path: String,
        @P("起始行号") start_line: Int,
        @P("结束行号") end_line: Int
    ): String {
        AppLog.d("✅ [Tool] read_file($path, $start_line~$end_line)")
        return runBlocking {
            // 🔥 直接用 "/" 拼接绝对路径，干脆利落！
            val fullPath = if (path.isEmpty() || path == ".") rootPath else "$rootPath/$path"
            if (!File(fullPath).exists()) return@runBlocking "错误：找不到文件 `$path`"
            repository.readFileContent(fullPath, start_line, end_line)
        }
    }

    @Tool("精确替换文件中的一段代码...")
    fun applyPatch(
        @P("文件相对路径") path: String,
        @P("原始代码片段") search_string: String,
        @P("新代码片段") replace_string: String
    ): String {
        AppLog.d("✅ [Tool] apply_patch → $path")
        return runBlocking {
            val fullPath = "$rootPath/$path"
            if (!File(fullPath).exists()) return@runBlocking "错误：找不到文件 `$path`"

            val original = repository.readRawFileContent(fullPath)
            if (original.startsWith("读取异常") || original == "无法读取文件") {
                return@runBlocking "错误：无法读取原文件内容，无法生成补丁。"
            }

            val proposed = CodePatcher.replaceFirstExact(original, search_string, replace_string)
                ?: return@runBlocking "错误：search_string 未在文件中找到..."

            val proposal = PatchProposal(
                targetFilePath   = fullPath, // 🔥 注意：去 WorkspaceState.kt 里把 Uri 改成 String
                targetFileName   = path,
                originalContent  = original,
                diffText         = CodePatcher.buildPatchPreview(path, original, proposed, search_string, replace_string),
                proposedContent  = proposed
            )
            onPatchReady(proposal)
            "✅ 已生成修改方案，等待用户确认后落盘。目标文件: $path"
        }
    }

    @Tool("新建文件，或在用户确认后覆盖已有文件...")
    fun createFile(
        @P("文件相对路径") path: String,
        @P("文件完整内容") content: String
    ): String {
        AppLog.d("✅ [Tool] create_file → $path")
        return runBlocking {
            val fullPath = "$rootPath/$path"
            val fileExists = File(fullPath).exists()

            if (fileExists) {
                val original = repository.readRawFileContent(fullPath)
                val diffMsg  = if (content.isEmpty()) "⚠️ 警告：AI 请求清空此文件内容" else "⚠️ 警告：AI 请求完全覆盖此文件"
                onPatchReady(
                    PatchProposal(
                        targetFilePath  = fullPath,
                        targetFileName  = path,
                        originalContent = original,
                        diffText        = "$diffMsg\n\n预览内容:\n$content",
                        proposedContent = content
                    )
                )
                "✅ 已发起覆盖提议，等待审批！"
            } else {
                onPatchReady(
                    PatchProposal(
                        targetFilePath  = fullPath,
                        targetFileName  = "$path（新建）",
                        originalContent = "",
                        diffText        = "✨ AI 请求创建新文件，预览如下:\n\n$content",
                        proposedContent = content
                    )
                )
                "✅ 已发起新建文件提议，等待审批。"
            }
        }
    }
}
