// [文件路径: app/src/main/java/com/xixin/codent/data/repository/SafRepository.kt]
package com.xixin.codent.data.repository

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.xixin.codent.data.model.ChatMessage
import com.xixin.codent.data.model.FileNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

class SafRepository(private val context: Context) {

    private val ignoredDirectories = setOf("build", ".git", ".gradle", ".idea", "node_modules", "captures")
    private val allowedCodeExtensions = setOf("kt", "java", "xml", "kts", "gradle", "json", "properties", "md", "txt")
    
    private val prefs = context.getSharedPreferences("codent_settings", Context.MODE_PRIVATE)

    private val chatHistoryFile = File(context.filesDir, "chat_history.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun saveApiBaseUrl(url: String) = prefs.edit().putString("api_base_url", url).apply()
    fun getApiBaseUrl(): String = prefs.getString("api_base_url", "https://api.deepseek.com/chat/completions") ?: "https://api.deepseek.com/chat/completions"

    fun saveApiKey(key: String) = prefs.edit().putString("api_key", key).apply()
    fun getApiKey(): String = prefs.getString("api_key", "") ?: ""

    fun saveSelectedModel(model: String) = prefs.edit().putString("selected_model", model).apply()
    fun getSelectedModel(): String = prefs.getString("selected_model", "deepseek-reasoner") ?: "deepseek-reasoner"

    fun saveThinkingEnabled(enabled: Boolean) = prefs.edit().putBoolean("thinking_enabled", enabled).apply()
    fun isThinkingEnabled(): Boolean = prefs.getBoolean("thinking_enabled", true)

    fun takePersistableUriPermission(uri: Uri) {
        try {
            val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, takeFlags)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun saveChatHistory(messages: List<ChatMessage>) {
        try {
            val validMessages = messages.filter { !it.isLoading }
            chatHistoryFile.writeText(json.encodeToString(validMessages))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun loadChatHistory(): List<ChatMessage> {
        if (!chatHistoryFile.exists()) return emptyList()
        return try {
            json.decodeFromString(chatHistoryFile.readText())
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    fun clearChatHistory() {
        if (chatHistoryFile.exists()) chatHistoryFile.delete()
    }

    fun listFilesFlow(folderUri: Uri): Flow<List<FileNode>> = flow {
        val fileList = mutableListOf<FileNode>()
        try {
            val docId = DocumentsContract.getDocumentId(folderUri)
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(folderUri, docId)
            val projection = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE)

            context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)

                while (cursor.moveToNext()) {
                    val childDocId = cursor.getString(idIdx)
                    val name = cursor.getString(nameIdx) ?: "Unknown"
                    val mimeType = cursor.getString(mimeIdx)
                    val size = if (!cursor.isNull(sizeIdx)) cursor.getLong(sizeIdx) else 0L
                    val isDir = mimeType == DocumentsContract.Document.MIME_TYPE_DIR
                    
                    if (!(isDir && ignoredDirectories.contains(name))) {
                        val childUri = DocumentsContract.buildDocumentUriUsingTree(folderUri, childDocId)
                        fileList.add(FileNode(name, childUri, isDir, size))
                    }
                }
            }
            emit(fileList.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })))
        } catch (e: Exception) {
            val documentFile = DocumentFile.fromTreeUri(context, folderUri)
            if (documentFile != null && documentFile.isDirectory) {
                documentFile.listFiles().forEach { file ->
                    val name = file.name ?: "Unknown"
                    if (!(file.isDirectory && ignoredDirectories.contains(name))) {
                        fileList.add(FileNode(name, file.uri, file.isDirectory, 0L))
                    }
                }
                emit(fileList.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })))
            }
        }
    }.flowOn(Dispatchers.IO)

    suspend fun readFileContent(fileUri: Uri, startLine: Int? = null, endLine: Int? = null): String = withContext(Dispatchers.IO) {
        try {
            val documentFile = DocumentFile.fromSingleUri(context, fileUri)
            if (documentFile != null && documentFile.length() > 1024 * 1024) {
                return@withContext "错误：文件过大 (>1MB)，拒绝读取以保护 Token 成本。"
            }
            context.contentResolver.openInputStream(fileUri)?.use { inputStream ->
                val lines = BufferedReader(InputStreamReader(inputStream)).readLines()
                if (lines.isEmpty()) return@withContext "文件为空"

                val actualStart = (startLine ?: 1).coerceAtLeast(1) - 1
                var actualEnd = (endLine ?: lines.size).coerceAtMost(lines.size)

                var isTruncated = false
                if (actualEnd - actualStart > 300) {
                    actualEnd = actualStart + 300
                    isTruncated = true
                }

                if (actualStart >= lines.size || actualStart > actualEnd) {
                    return@withContext "错误：行号范围超出文件长度 (总行数: ${lines.size})"
                }

                val snippet = lines.subList(actualStart, actualEnd).mapIndexed { index, line -> "${actualStart + index + 1} | $line" }.joinToString("\n")

                buildString {
                    append("文件总行数: ${lines.size}\n当前片段 (行 ${actualStart + 1} - $actualEnd):\n")
                    append(snippet)
                    if (isTruncated) append("\n\n...(系统截断：文件过长，超过 300 行部分已被折叠)...")
                }
            } ?: "无法读取文件"
        } catch (e: Exception) {
            "读取代码失败: ${e.message}"
        }
    }

    suspend fun findFileByRelativePath(rootUri: Uri, relativePath: String): Uri? = withContext(Dispatchers.IO) {
        var currentDoc = DocumentFile.fromTreeUri(context, rootUri) ?: return@withContext null
        val segments = relativePath.split("/").filter { it.isNotBlank() }
        for (segment in segments) {
            val nextDoc = currentDoc.listFiles().find { it.name == segment }
            if (nextDoc == null) return@withContext null
            currentDoc = nextDoc
        }
        return@withContext currentDoc.uri
    }

    // 🔥 新增：创建文件/文件夹
    suspend fun createFileByRelativePath(rootUri: Uri, relativePath: String, content: String): String = withContext(Dispatchers.IO) {
        try {
            var currentDoc = DocumentFile.fromTreeUri(context, rootUri) ?: return@withContext "无法访问项目根目录"
            val segments = relativePath.split("/").filter { it.isNotBlank() }
            if (segments.isEmpty()) return@withContext "路径无效"

            val fileName = segments.last()
            val folderSegments = segments.dropLast(1)

            // 递归创建目录
            for (segment in folderSegments) {
                var nextDoc = currentDoc.listFiles().find { it.name == segment && it.isDirectory }
                if (nextDoc == null) {
                    nextDoc = currentDoc.createDirectory(segment)
                    if (nextDoc == null) return@withContext "创建目录 $segment 失败"
                }
                currentDoc = nextDoc
            }

            // 创建文件
            var fileDoc = currentDoc.listFiles().find { it.name == fileName }
            if (fileDoc == null) {
                fileDoc = currentDoc.createFile("*/*", fileName)
                if (fileDoc == null) return@withContext "创建文件 $fileName 失败"
            }

            // 写入内容
            val success = overwriteFile(fileDoc.uri, content)
            if (success) "✅ 成功创建并写入文件: $relativePath" else "❌ 写入文件失败"
        } catch (e: Exception) {
            "创建文件异常: ${e.message}"
        }
    }

    suspend fun overwriteFile(fileUri: Uri, newContent: String): Boolean = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openOutputStream(fileUri, "rwt")?.use { outputStream ->
                outputStream.write(newContent.toByteArray())
                outputStream.flush()
            } ?: return@withContext false
            true
        } catch (e: Exception) {
            e.printStackTrace()
            try {
                context.contentResolver.openOutputStream(fileUri, "w")?.use { outputStream ->
                    outputStream.write(newContent.toByteArray())
                    outputStream.flush()
                } ?: return@withContext false
                true
            } catch (e2: Exception) {
                e2.printStackTrace()
                false
            }
        }
    }

    suspend fun listDirectoryRelative(rootUri: Uri, relativePath: String): String = withContext(Dispatchers.IO) {
        try {
            val targetDoc = if (relativePath.isBlank() || relativePath == ".") {
                DocumentFile.fromTreeUri(context, rootUri)
            } else {
                var current = DocumentFile.fromTreeUri(context, rootUri)
                val segments = relativePath.split("/").filter { it.isNotBlank() }
                for (segment in segments) {
                    current = current?.listFiles()?.find { it.name == segment }
                    if (current == null) break
                }
                current
            }
            if (targetDoc == null || !targetDoc.isDirectory) return@withContext "错误: 找不到目录 `$relativePath`"
            val files = targetDoc.listFiles().filter { it.name != null && !(it.isDirectory && ignoredDirectories.contains(it.name)) }
            if (files.isEmpty()) return@withContext "该目录目前为空。"
            buildString {
                append("目录 `$relativePath` 内容如下：\n")
                files.forEach { file -> append("- ${if (file.isDirectory) "📁" else "📄"} ${file.name}\n") }
            }
        } catch (e: Exception) {
            "读取目录列表出错: ${e.message}"
        }
    }

    suspend fun searchKeyword(rootUri: Uri, keyword: String): String = withContext(Dispatchers.IO) {
        if (keyword.isBlank()) return@withContext "搜索关键字不能为空。"
        val results = mutableListOf<String>()
        val rootDoc = DocumentFile.fromTreeUri(context, rootUri) ?: return@withContext "无法访问根目录。"
        
        suspend fun traverse(doc: DocumentFile, currentPath: String) {
            if (results.size >= 8) return
            if (doc.isDirectory) {
                if (ignoredDirectories.contains(doc.name)) return
                doc.listFiles().forEach { child ->
                    val nextPath = if (currentPath.isEmpty()) child.name ?: "" else "$currentPath/${child.name}"
                    traverse(child, nextPath)
                }
            } else {
                val name = doc.name ?: ""
                val ext = name.substringAfterLast('.', "")
                if (allowedCodeExtensions.contains(ext) && doc.length() < 300 * 1024) {
                    try {
                        context.contentResolver.openInputStream(doc.uri)?.use { stream ->
                            val lines = stream.bufferedReader().readLines()
                            val matchedIndices = lines.mapIndexedNotNull { index, line ->
                                if (line.contains(keyword, ignoreCase = true)) index else null
                            }
                            if (matchedIndices.isNotEmpty()) {
                                val snippetBuilder = StringBuilder().append("📄 $currentPath\n")
                                matchedIndices.take(3).forEach { idx ->
                                    val start = maxOf(0, idx - 1)
                                    val end = minOf(lines.size - 1, idx + 1)
                                    snippetBuilder.append("...\n")
                                    for (i in start..end) snippetBuilder.append("${i + 1} | ${lines[i]}\n")
                                }
                                snippetBuilder.append("...\n")
                                results.add(snippetBuilder.toString())
                            }
                        }
                    } catch (e: Exception) { }
                }
            }
        }
        try {
            traverse(rootDoc, "")
            if (results.isEmpty()) "未检索到包含 `$keyword` 的文件。"
            else buildString {
                append("搜索结果:\n")
                results.forEach { append("$it\n") }
            }
        } catch (e: Exception) {
            "搜索异常: ${e.message}"
        }
    }
}
