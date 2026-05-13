package com.xixin.codent.core.agent

import android.net.Uri
import com.xixin.codent.data.model.PatchProposal
import com.xixin.codent.data.repository.SafRepository
import com.xixin.codent.mcp.CodentMcpServer
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import kotlinx.serialization.json.*

class AiActionRunner(
    private val repository: SafRepository,
    private val mcpServer: CodentMcpServer
) {

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

            // Route to the MCP server
            mcpServer.currentRootUri = rootUri
            mcpServer.onPatchProposed = onPatchProposed

            // Since we set up the client, we MUST call the tools through the MCP Client, NOT the Server.
            val result = mcpServer.client.callTool(
                CallToolRequest(
                    name = name,
                    arguments = jsonObj
                )
            )

            // MCP returns a list of contents. Extract the text content.
            result.content.joinToString("\n") {
                if (it is io.modelcontextprotocol.kotlin.sdk.types.TextContent) it.text else ""
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
