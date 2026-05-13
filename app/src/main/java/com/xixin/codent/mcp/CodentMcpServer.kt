package com.xixin.codent.mcp

import com.xixin.codent.core.agent.CodePatcher
import com.xixin.codent.data.model.PatchProposal
import com.xixin.codent.data.repository.SafRepository
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.ClientOptions
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.shared.Method
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.jsonPrimitive
import android.net.Uri
import io.modelcontextprotocol.kotlin.sdk.shared.ChannelTransport

class CodentMcpServer(private val repository: SafRepository) {

    val server: Server
    val client: Client

    // A callback set by the UI/ViewModel to receive patch proposals
    var onPatchProposed: (suspend (PatchProposal) -> Unit)? = null

    // We store the current root URI mapped from the workspace
    var currentRootUri: Uri? = null

    init {
        server = Server(
            serverInfo = Implementation(
                name = "codent-mcp-server",
                version = "1.0.0"
            ),
            options = ServerOptions(
                capabilities = ServerCapabilities(
                    tools = ServerCapabilities.Tools(listChanged = true)
                )
            )
        )

        client = Client(
            clientInfo = Implementation(
                name = "codent-mcp-client",
                version = "1.0.0"
            ),
            options = ClientOptions()
        )

        setupTools()
    }

    suspend fun start() {
        // Connect them using in-memory channel transport
        val (clientTransport, serverTransport) = ChannelTransport.createPair()

        // Let's connect them
        val serverJob = kotlinx.coroutines.DelicateCoroutinesApi::class.java // workaround
        kotlinx.coroutines.GlobalScope.launch {
            server.connect(serverTransport)
        }
        client.connect(clientTransport)
    }

    private fun setupTools() {
        // 1. search_keyword
        server.addTool(
            name = "search_keyword",
            description = "全局搜索代码关键字。返回带行号匹配片段。",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("keyword") { put("type", "string") }
                },
                required = listOf("keyword")
            )
        ) { request ->
            val keyword = request.arguments?.get("keyword")?.jsonPrimitive?.content ?: ""
            val rootUri = currentRootUri ?: return@addTool CallToolResult(content = listOf(TextContent("错误: 未选择根目录")))
            val result = repository.searchKeyword(rootUri, keyword)
            CallToolResult(content = listOf(TextContent(result)))
        }

        // 2. find_file
        server.addTool(
            name = "find_file",
            description = "通过文件名全局查找文件的准确路径，速度快于 list_directory。",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("file_name") { put("type", "string") }
                },
                required = listOf("file_name")
            )
        ) { request ->
            val fileName = request.arguments?.get("file_name")?.jsonPrimitive?.content ?: ""
            val rootUri = currentRootUri ?: return@addTool CallToolResult(content = listOf(TextContent("错误: 未选择根目录")))
            val result = repository.findFilesByName(rootUri, fileName)
            CallToolResult(content = listOf(TextContent(result)))
        }

        // 3. list_directory
        server.addTool(
            name = "list_directory",
            description = "列出目录内容，根目录传 ''",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("path") { put("type", "string") }
                },
                required = listOf("path")
            )
        ) { request ->
            val path = request.arguments?.get("path")?.jsonPrimitive?.content ?: ""
            val rootUri = currentRootUri ?: return@addTool CallToolResult(content = listOf(TextContent("错误: 未选择根目录")))
            val result = repository.listDirectoryRelative(rootUri, path)
            CallToolResult(content = listOf(TextContent(result)))
        }

        // 4. read_file
        server.addTool(
            name = "read_file",
            description = "读取文件片段。必须指定 start_line 和 end_line",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("path") { put("type", "string") }
                    putJsonObject("start_line") { put("type", "integer") }
                    putJsonObject("end_line") { put("type", "integer") }
                },
                required = listOf("path", "start_line", "end_line")
            )
        ) { request ->
            val path = request.arguments?.get("path")?.jsonPrimitive?.content ?: ""
            val startLine = request.arguments?.get("start_line")?.jsonPrimitive?.content?.toIntOrNull()
            val endLine = request.arguments?.get("end_line")?.jsonPrimitive?.content?.toIntOrNull()
            val rootUri = currentRootUri ?: return@addTool CallToolResult(content = listOf(TextContent("错误: 未选择根目录")))

            val fileUri = repository.findFileByRelativePath(rootUri, path)
            val result = if (fileUri == null) "错误：找不到文件 `$path`" else repository.readFileContent(fileUri, startLine, endLine)
            CallToolResult(content = listOf(TextContent(result)))
        }

        // 5. apply_patch
        server.addTool(
            name = "apply_patch",
            description = "局部修改代码。精确替换 search_string。",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("path") { put("type", "string") }
                    putJsonObject("search_string") { put("type", "string") }
                    putJsonObject("replace_string") { put("type", "string") }
                },
                required = listOf("path", "search_string", "replace_string")
            )
        ) { request ->
            val path = request.arguments?.get("path")?.jsonPrimitive?.content ?: ""
            val searchString = request.arguments?.get("search_string")?.jsonPrimitive?.content ?: ""
            val replaceString = request.arguments?.get("replace_string")?.jsonPrimitive?.content ?: ""
            val rootUri = currentRootUri ?: return@addTool CallToolResult(content = listOf(TextContent("错误: 未选择根目录")))

            val fileUri = repository.findFileByRelativePath(rootUri, path)
                ?: return@addTool CallToolResult(content = listOf(TextContent("错误：找不到文件 `$path`")))

            val pureOriginal = repository.readRawFileContent(fileUri)
            if (pureOriginal.startsWith("读取异常") || pureOriginal == "无法读取文件") {
                return@addTool CallToolResult(content = listOf(TextContent("错误：无法读取原文件内容，无法生成补丁。")))
            }

            val newContent = CodePatcher.replaceFirstExact(pureOriginal, searchString, replaceString)
                ?: return@addTool CallToolResult(content = listOf(TextContent("错误：search_string 未在原文件中找到，补丁未生成。请确保代码一模一样，不要带行号！")))

            val proposal = PatchProposal(
                targetFileUri = fileUri,
                targetFileName = path,
                originalContent = pureOriginal,
                diffText = CodePatcher.buildPatchPreview(path, pureOriginal, newContent, searchString, replaceString),
                proposedContent = newContent
            )

            onPatchProposed?.invoke(proposal)
            CallToolResult(content = listOf(TextContent("✅ 已生成修改方案，等待用户确认后落盘。目标文件: $path")))
        }

        // 6. create_file
        server.addTool(
            name = "create_file",
            description = "新建文件。路径必须是相对项目根目录的路径。",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("path") { put("type", "string") }
                    putJsonObject("content") { put("type", "string") }
                },
                required = listOf("path", "content")
            )
        ) { request ->
            val path = request.arguments?.get("path")?.jsonPrimitive?.content ?: ""
            val content = request.arguments?.get("content")?.jsonPrimitive?.content ?: ""
            val rootUri = currentRootUri ?: return@addTool CallToolResult(content = listOf(TextContent("错误: 未选择根目录")))

            val existingUri = repository.findFileByRelativePath(rootUri, path)

            if (existingUri != null) {
                val original = repository.readRawFileContent(existingUri)
                val diffMsg = if (content.isEmpty()) "⚠️ 警告：AI 请求清空此文件内容 (等同于删除)" else "⚠️ 警告：AI 请求完全覆盖此文件"

                val proposal = PatchProposal(
                    targetFileUri = existingUri,
                    targetFileName = path,
                    originalContent = original,
                    diffText = "$diffMsg\n\n预览内容:\n$content",
                    proposedContent = content
                )
                onPatchProposed?.invoke(proposal)
                CallToolResult(content = listOf(TextContent("✅ 已发起覆盖/清空文件提议，必须等待用户审批！")))
            } else {
                repository.createFileByRelativePath(rootUri, path, "")
                val newUri = repository.findFileByRelativePath(rootUri, path)
                    ?: return@addTool CallToolResult(content = listOf(TextContent("❌ 尝试创建空文件占位失败，无法发起提议。")))

                val proposal = PatchProposal(
                    targetFileUri = newUri,
                    targetFileName = "$path (新建)",
                    originalContent = "",
                    diffText = "✨ AI 请求创建一个全新文件，预览如下:\n\n$content",
                    proposedContent = content
                )
                onPatchProposed?.invoke(proposal)
                CallToolResult(content = listOf(TextContent("✅ 已发起新建文件提议，等待用户审批。")))
            }
        }
    }
}
