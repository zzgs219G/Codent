package com.xixin.codent.core.agent

import com.xixin.codent.data.api.FunctionDef
import com.xixin.codent.data.api.Tool
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

object AiToolbox {
    val agentTools = listOf(
        Tool(
            function = FunctionDef(
                name = "search_keyword",
                description = "全局搜索代码关键字。返回带行号匹配片段。",
                parameters = buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") { putJsonObject("keyword") { put("type", "string") } }
                    putJsonArray("required") { add(JsonPrimitive("keyword")) }
                }
            )
        ),
        Tool(
            function = FunctionDef(
                name = "find_file",
                description = "通过文件名全局查找文件的准确路径，速度快于 list_directory。",
                parameters = buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") { putJsonObject("file_name") { put("type", "string") } }
                    putJsonArray("required") { add(JsonPrimitive("file_name")) }
                }
            )
        ),
        Tool(
            function = FunctionDef(
                name = "list_directory",
                description = "列出目录内容，根目录传 ''",
                parameters = buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") { putJsonObject("path") { put("type", "string") } }
                    putJsonArray("required") { add(JsonPrimitive("path")) }
                }
            )
        ),
        Tool(
            function = FunctionDef(
                name = "read_file",
                description = "读取文件片段。必须指定 start_line 和 end_line",
                parameters = buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("path") { put("type", "string") }
                        putJsonObject("start_line") { put("type", "integer") }
                        putJsonObject("end_line") { put("type", "integer") }
                    }
                    putJsonArray("required") {
                        add(JsonPrimitive("path"))
                        add(JsonPrimitive("start_line"))
                        add(JsonPrimitive("end_line"))
                    }
                }
            )
        ),
        Tool(
            function = FunctionDef(
                name = "apply_patch",
                description = "局部修改代码。精确替换 search_string。",
                parameters = buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("path") { put("type", "string") }
                        putJsonObject("search_string") { put("type", "string") }
                        putJsonObject("replace_string") { put("type", "string") }
                    }
                    putJsonArray("required") {
                        add(JsonPrimitive("path"))
                        add(JsonPrimitive("search_string"))
                        add(JsonPrimitive("replace_string"))
                    }
                }
            )
        ),
        Tool(
            function = FunctionDef(
                name = "create_file",
                description = "新建文件。路径必须是相对项目根目录的路径。",
                parameters = buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("path") { put("type", "string") }
                        putJsonObject("content") { put("type", "string") }
                    }
                    putJsonArray("required") {
                        add(JsonPrimitive("path"))
                        add(JsonPrimitive("content"))
                    }
                }
            )
        )
    )
}
