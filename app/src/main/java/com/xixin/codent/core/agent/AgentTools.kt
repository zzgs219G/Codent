package com.xixin.codent.core.agent

import com.xixin.codent.data.model.PatchProposal
import com.xixin.codent.data.repository.LocalFileRepository
import com.xixin.codent.wrapper.log.AppLog
import kotlinx.coroutines.runBlocking
import java.io.File 

class AgentTools(
    private val repository: LocalFileRepository,
    var rootPath: String, 
    var onPatchReady: (PatchProposal) -> Unit = {}
) {
    // 🔥 已经安全移除所有 LangChain4j 的 @Tool 和 @P 注解
    fun searchKeyword(keyword: String): String {
        AppLog.d("✅ [Tool] search_keyword($keyword)")
        return runBlocking { repository.searchKeyword(rootPath, keyword) }
    }

    fun findFile(file_name: String): String {
        AppLog.d("✅ [Tool] find_file($file_name)")
        return runBlocking { repository.findFilesByName(rootPath, file_name) }
    }

    fun listDirectory(path: String): String {
        AppLog.d("✅ [Tool] list_directory($path)")
        return runBlocking { repository.listDirectoryRelative(rootPath, path) }
    }

    fun readFile(path: String, start_line: Int, end_line: Int): String {
        AppLog.d("✅ [Tool] read_file($path, $start_line~$end_line)")
        return runBlocking {
            val fullPath = if (path.isEmpty() || path == ".") rootPath else "$rootPath/$path"
            if (!File(fullPath).exists()) return@runBlocking "错误：找不到文件 `$path`"
            repository.readFileContent(fullPath, start_line, end_line)
        }
    }

    fun applyPatch(path: String, search_string: String, replace_string: String): String {
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
                targetFilePath   = fullPath, 
                targetFileName   = path,
                originalContent  = original,
                diffText         = CodePatcher.buildPatchPreview(path, original, proposed, search_string, replace_string),
                proposedContent  = proposed
            )
            onPatchReady(proposal)
            "✅ 已生成修改方案，等待用户确认后落盘。目标文件: $path"
        }
    }

    fun createFile(path: String, content: String): String {
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
