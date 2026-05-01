package com.xixin.codent.core.agent

import android.net.Uri
import com.xixin.codent.data.model.PatchProposal
import com.xixin.codent.data.repository.SafRepository
import com.xixin.codent.wrapper.log.AppLog
import kotlinx.serialization.json.*

class AiActionRunner(private val repository: SafRepository) {

    private val toolParser = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    suspend fun executeTool(
        name: String,
        args: String,
        rootUri: Uri,
        onPatchProposed: suspend (PatchProposal) -> Unit
    ): String {
        return try {
            val jsonObj = toolParser.parseToJsonElement(args).jsonObject
            when (name) {
                "search_keyword" -> {
                    val keyword = jsonObj["keyword"]?.jsonPrimitive?.content.orEmpty()
                    AppLog.d("✅ 执行工具: search_keyword($keyword)")
                    repository.searchKeyword(rootUri, keyword)
                }
                "find_file" -> {
                    val fileName = jsonObj["file_name"]?.jsonPrimitive?.content.orEmpty()
                    AppLog.d("✅ 执行工具: find_file($fileName)")
                    repository.findFilesByName(rootUri, fileName)
                }
                "list_directory" -> {
                    val path = jsonObj["path"]?.jsonPrimitive?.content.orEmpty()
                    AppLog.d("✅ 执行工具: list_directory($path)")
                    repository.listDirectoryRelative(rootUri, path)
                }
                "read_file" -> {
                    val path = jsonObj["path"]?.jsonPrimitive?.content.orEmpty()
                    val startLine = jsonObj["start_line"]?.jsonPrimitive?.content?.toIntOrNull()
                    val endLine = jsonObj["end_line"]?.jsonPrimitive?.content?.toIntOrNull()
                    AppLog.d("✅ 执行工具: read_file($path, $startLine, $endLine)")
                    val fileUri = repository.findFileByRelativePath(rootUri, path)
                    if (fileUri == null) "错误：找不到文件 `$path`" else repository.readFileContent(fileUri, startLine, endLine)
                }
                "apply_patch" -> {
                    val path = jsonObj["path"]?.jsonPrimitive?.content.orEmpty()
                    val searchString = jsonObj["search_string"]?.jsonPrimitive?.content.orEmpty()
                    val replaceString = jsonObj["replace_string"]?.jsonPrimitive?.content.orEmpty()
                    AppLog.d("✅ 执行工具: apply_patch 尝试修改文件: $path")

                    val fileUri = repository.findFileByRelativePath(rootUri, path)
                        ?: return "错误：找不到文件 `$path`"

                    val pureOriginal = repository.readRawFileContent(fileUri)
                    if (pureOriginal.startsWith("读取异常") || pureOriginal == "无法读取文件") {
                        return "错误：无法读取原文件内容，无法生成补丁。"
                    }

                    val newContent = CodePatcher.replaceFirstExact(pureOriginal, searchString, replaceString)
                        ?: return "错误：search_string 未在原文件中找到，补丁未生成。请确保代码一模一样，不要带行号！"

                    val proposal = PatchProposal(
                        targetFileUri = fileUri,
                        targetFileName = path,
                        originalContent = pureOriginal,
                        diffText = CodePatcher.buildPatchPreview(path, pureOriginal, newContent, searchString, replaceString),
                        proposedContent = newContent
                    )
                    
                    AppLog.d("✂️ [Patch Diff 生成成功]:\n--- 🔍 查找 ---\n$searchString\n--- ✨ 替换为 ---\n$replaceString")
                    
                    onPatchProposed(proposal)
                    "✅ 已生成修改方案，等待用户确认后落盘。目标文件: $path"
                }
                "create_file" -> {
                    val path = jsonObj["path"]?.jsonPrimitive?.content.orEmpty()
                    val content = jsonObj["content"]?.jsonPrimitive?.content.orEmpty()
                    AppLog.d("✅ 执行工具: create_file 尝试安全接管: $path")
                    
                    // 🚨 物理锁：先检查文件是不是已经存在！
                    val existingUri = repository.findFileByRelativePath(rootUri, path)
                    
                    if (existingUri != null) {
                        // 【危险操作】：文件已存在，AI 是想覆盖或者清空它！
                        val original = repository.readRawFileContent(existingUri)
                        val diffMsg = if (content.isEmpty()) "⚠️ 警告：AI 请求清空此文件内容 (等同于删除)" else "⚠️ 警告：AI 请求完全覆盖此文件"
                        
                        val proposal = PatchProposal(
                            targetFileUri = existingUri,
                            targetFileName = path,
                            originalContent = original,
                            diffText = "$diffMsg\n\n预览内容:\n$content",
                            proposedContent = content
                        )
                        onPatchProposed(proposal)
                        "✅ 已发起覆盖/清空文件提议，必须等待用户审批！"
                        
                    } else {
                        // 【安全操作】：这是真正的新建文件
                        // 技巧：先创建一个体积为 0 的空占位文件拿到系统 URI，但不写入实质内容
                        repository.createFileByRelativePath(rootUri, path, "")
                        val newUri = repository.findFileByRelativePath(rootUri, path)
                            ?: return "❌ 尝试创建空文件占位失败，无法发起提议。"
                            
                        val proposal = PatchProposal(
                            targetFileUri = newUri,
                            targetFileName = "$path (新建)",
                            originalContent = "",
                            diffText = "✨ AI 请求创建一个全新文件，预览如下:\n\n$content",
                            proposedContent = content
                        )
                        onPatchProposed(proposal)
                        "✅ 已发起新建文件提议，等待用户审批。"
                    }
                }
                else -> "未知工具: $name"
            }
        } catch (e: Exception) {
            "工具执行异常: ${e.localizedMessage}"
        }
    }

    fun buildDisplayArgs(funcName: String, argsStr: String): String = try {
        val jsonObj = toolParser.parseToJsonElement(argsStr).jsonObject
        when (funcName) {
            "search_keyword" -> "🔍 搜: ${jsonObj["keyword"]?.jsonPrimitive?.content.orEmpty()}"
            "find_file" -> "🗺️ 找: ${jsonObj["file_name"]?.jsonPrimitive?.content.orEmpty()}"
            "list_directory" -> "📂 看: ${jsonObj["path"]?.jsonPrimitive?.content.orEmpty()}"
            "read_file" -> "📄 读: ${jsonObj["path"]?.jsonPrimitive?.content.orEmpty()}"
            "apply_patch" -> "✍️ 改: ${jsonObj["path"]?.jsonPrimitive?.content.orEmpty()}"
            "create_file" -> "✨ 建/删: ${jsonObj["path"]?.jsonPrimitive?.content.orEmpty()}"
            else -> "执行指令中"
        }
    } catch (_: Exception) { "执行指令中" }
}