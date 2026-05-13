package com.xixin.codent.core.agent

import com.xixin.codent.data.api.FunctionDef
import com.xixin.codent.data.api.Tool
import io.modelcontextprotocol.kotlin.sdk.client.Client
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

object AiToolbox {

    suspend fun getAgentToolsFromMcp(client: Client): List<Tool> {
        val mcpTools = client.listTools().tools
        return mcpTools.map { mcpTool ->
            // Use kotlinx.serialization to convert ToolSchema to JsonElement
            val schemaJsonString = Json.encodeToString(mcpTool.inputSchema)
            val parametersElement = Json.parseToJsonElement(schemaJsonString)

            Tool(
                type = "function",
                function = FunctionDef(
                    name = mcpTool.name,
                    description = mcpTool.description ?: "",
                    parameters = parametersElement
                )
            )
        }
    }
}
