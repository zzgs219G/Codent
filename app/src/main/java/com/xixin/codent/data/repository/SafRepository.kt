package com.xixin.codent.data.repository

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
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

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun saveApiBaseUrl(url: String) {
        prefs.edit().putString("api_base_url", url).apply()
    }

    fun getApiBaseUrl(): String {
        return prefs.getString("api_base_url", "https://api.deepseek.com/chat/completions")
            ?: "https://api.deepseek.com/chat/completions"
    }

    fun saveApiKey(key: String) {
        prefs.edit().putString("api_key", key).apply()
    }

    fun getApiKey(): String {
        return prefs.getString("api_key", "") ?: ""
    }

    fun saveSelectedModel(model: String) {
        prefs.edit().putString("selected_model", model).apply()
    }

    fun getSelectedModel(): String {
        return prefs.getString("selected_model", "deepseek-reasoner") ?: "deepseek-reasoner"
    }

    fun saveThinkingEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("thinking_enabled", enabled).apply()
    }

    fun isThinkingEnabled(): Boolean {
        return prefs.getBoolean("thinking_enabled", true)
    }

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
            val validMessages = messages.filterNot { it.isLoading }
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
        if (chatHistoryFile.exists()) {
            chatHistoryFile.delete()
        }
    }

    fun listFilesFlow(folderUri: Uri): Flow<List<FileNode>> = flow {
        val fileList = mutableListOf<FileNode>()
        try {
            val docId = DocumentsContract.getDocumentId(folderUri)
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(folderUri, docId)
            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE
            )

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
                    val isDirectory = mimeType == DocumentsContract.Document.MIME_TYPE_DIR

                    if (isDirectory && ignoredDirectories.contains(name)) continue

                    val childUri = DocumentsContract.buildDocumentUriUsingTree(folderUri, childDocId)
                    fileList.add(FileNode(name, childUri, isDirectory, size))
                }
            }
            emit(fileList.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })))
        } catch (e: Exception) {
            val documentFile = DocumentFile.fromTreeUri(context, folderUri)
            if (documentFile != null && documentFile.isDirectory) {
                documentFile.listFiles().forEach { file ->
                    val name = file.name ?: "Unknown"
                    if (file.isDirectory && ignoredDirectories.contains(name)) return@forEach
                    fileList.add(FileNode(name, file.uri, file.isDirectory, file.length()))
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

                if (actualStart >= lines.size || actualStart > actualEnd) {
                    return@withContext "错误：行号范围超出文件长度 (总行数: ${lines.size})"
                }

                var isTruncated = false
                if (actualEnd - actualStart > 300) {
                    actualEnd = actualStart + 300
                    isTruncated = true
                }

                val snippet = lines.subList(actualStart, actualEnd)
                    .mapIndexed { index, line -> "${actualStart + index + 1} | $line" }
                    .joinToString("\n")

                buildString {
                    append("文件总行数: ${lines.size}\n")
                    append("当前片段 (行 ${actualStart + 1} - $actualEnd):\n")
                    append(snippet)
                    if (isTruncated) {
                        append("\n\n...(系统截断：文件过长，超过 300 行部分已被折叠)...")
                    }
                }
            } ?: "无法读取文件"
        } catch (e: Exception) {
            "读取代码失败: ${e.message}"
        }
    }

    suspend fun readRawFileContent(fileUri: Uri): String = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openInputStream(fileUri)?.use { inputStream ->
                inputStream.bufferedReader().readText()
            } ?: "无法读取文件"
        } catch (e: Exception) {
            "读取异常: ${e.message}"
        }
    }

    suspend fun findFileByRelativePath(rootUri: Uri, relativePath: String): Uri? = withContext(Dispatchers.IO) {
        var currentDoc = DocumentFile.fromTreeUri(context, rootUri) ?: return@withContext null
        val segments = relativePath.split("/").filter { it.isNotBlank() }

        for (segment in segments) {
            val nextDoc = currentDoc.listFiles().find { it.name == segment } ?: return@withContext null
            currentDoc = nextDoc
        }
        currentDoc.uri
    }

    suspend fun createFileByRelativePath(rootUri: Uri, relativePath: String, content: String): String = withContext(Dispatchers.IO) {
        try {
            var currentDoc = DocumentFile.fromTreeUri(context, rootUri) ?: return@withContext "无法访问项目根目录"
            val segments = relativePath.split("/").filter { it.isNotBlank() }
            if (segments.isEmpty()) return@withContext "路径无效"

            val fileName = segments.last()
            val folderSegments = segments.dropLast(1)

            for (segment in folderSegments) {
                var nextDoc = currentDoc.listFiles().find { it.name == segment && it.isDirectory }
                if (nextDoc == null) {
                    nextDoc = currentDoc.createDirectory(segment)
                    if (nextDoc == null) return@withContext "创建目录 $segment 失败"
                }
                currentDoc = nextDoc
            }

            var fileDoc = currentDoc.listFiles().find { it.name == fileName }
            if (fileDoc == null) {
                val mimeType = guessMimeType(fileName)
                fileDoc = currentDoc.createFile(mimeType, fileName)
                if (fileDoc == null) return@withContext "创建文件 $fileName 失败"
            }

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
            val targetDoc = resolveDirectory(rootUri, relativePath)
                ?: return@withContext "错误: 找不到目录 `$relativePath`"

            val files = targetDoc.listFiles()
                .filter { it.name != null && !(it.isDirectory && ignoredDirectories.contains(it.name)) }

            if (files.isEmpty()) return@withContext "该目录目前为空。"

            buildString {
                append("目录 `$relativePath` 内容如下：\n")
                files.forEach { file ->
                    append("- ${if (file.isDirectory) "📁" else "📄"} ${file.name}\n")
                }
            }
        } catch (e: Exception) {
            "读取目录列表出错: ${e.message}"
        }
    }

    suspend fun findFilesByName(rootUri: Uri, fileName: String): String = withContext(Dispatchers.IO) {
        if (fileName.isBlank()) return@withContext "搜索文件名不能为空。"
        val results = mutableListOf<String>()
        val rootDoc = DocumentFile.fromTreeUri(context, rootUri) ?: return@withContext "无法访问根目录。"

        suspend fun traverse(doc: DocumentFile, currentPath: String) {
            if (results.size >= 10) return
            if (doc.isDirectory) {
                val name = doc.name ?: return
                if (ignoredDirectories.contains(name)) return
                doc.listFiles().forEach { child ->
                    val nextPath = if (currentPath.isEmpty()) child.name.orEmpty() else "$currentPath/${child.name.orEmpty()}"
                    traverse(child, nextPath)
                }
                return
            }
            if (doc.name?.contains(fileName, ignoreCase = true) == true) {
                results.add(currentPath)
            }
        }

        try {
            traverse(rootDoc, "")
            if (results.isEmpty()) {
                "未找到包含 `$fileName` 的文件。"
            } else {
                buildString {
                    append("找到以下文件路径 (请复制准确路径使用):\n")
                    results.forEach { append("📄 $it\n") }
                }
            }
        } catch (e: Exception) {
            "搜索异常: ${e.message}"
        }
    }

    suspend fun searchKeyword(rootUri: Uri, keyword: String): String = withContext(Dispatchers.IO) {
        if (keyword.isBlank()) return@withContext "搜索关键字不能为空。"
        val results = mutableListOf<String>()
        val rootDoc = DocumentFile.fromTreeUri(context, rootUri) ?: return@withContext "无法访问根目录。"

        suspend fun traverse(doc: DocumentFile, currentPath: String) {
            if (results.size >= 8) return

            if (doc.isDirectory) {
                val name = doc.name ?: return
                if (ignoredDirectories.contains(name)) return
                doc.listFiles().forEach { child ->
                    val nextPath = if (currentPath.isEmpty()) child.name.orEmpty() else "$currentPath/${child.name.orEmpty()}"
                    traverse(child, nextPath)
                }
                return
            }

            val name = doc.name.orEmpty()
            val ext = name.substringAfterLast('.', "")
            if (!allowedCodeExtensions.contains(ext) || doc.length() >= 300 * 1024) return

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
                            val end = minOf(lines.lastIndex, idx + 1)
                            snippetBuilder.append("...\n")
                            for (i in start..end) {
                                snippetBuilder.append("${i + 1} | ${lines[i]}\n")
                            }
                        }
                        snippetBuilder.append("...\n")
                        results.add(snippetBuilder.toString())
                    }
                }
            } catch (e: Exception) {
                // Ignore search error on specific file
            }
        }

        try {
            traverse(rootDoc, "")
            if (results.isEmpty()) {
                "未检索到包含 `$keyword` 的文件。"
            } else {
                buildString {
                    append("搜索结果:\n")
                    results.forEach { append("$it\n") }
                }
            }
        } catch (e: Exception) {
            "搜索异常: ${e.message}"
        }
    }

    suspend fun generateProjectTree(rootUri: Uri, maxDepth: Int = 12): String = withContext(Dispatchers.IO) {
        val rootDoc = DocumentFile.fromTreeUri(context, rootUri) ?: return@withContext "None"
        val sb = StringBuilder()
        val heavyIgnore = ignoredDirectories + setOf(".cg", ".kotlin", "res", "drawable", "layout", "mipmap", "values")

        suspend fun traverse(doc: DocumentFile, depth: Int) {
            if (depth > maxDepth) return
            val files = doc.listFiles().filter { file ->
                val name = file.name ?: return@filter false
                if (file.isDirectory) !heavyIgnore.contains(name)
                else allowedCodeExtensions.any { name.endsWith(".$it") }
            }.sortedWith(compareBy({ !it.isDirectory }, { it.name?.lowercase() }))

            for (file in files) {
                val name = file.name ?: continue
                repeat(depth) { sb.append(".") } 
                
                if (file.isDirectory) {
                    sb.append(name).append("/\n")
                    traverse(file, depth + 1)
                } else {
                    // 🔥 [核心升级]: 增加文件大小输出，让 AI 感知代码规模
                    val length = file.length()
                    val sizeStr = if (length < 1024) {
                        "${length}B"
                    } else {
                        val kb = length / 1024.0
                        String.format("%.1fKB", kb)
                    }
                    sb.append(name).append(" (").append(sizeStr).append(")\n")
                }
            }
        }
        traverse(rootDoc, 0)
        sb.toString()
    }


    private fun resolveDirectory(rootUri: Uri, relativePath: String): DocumentFile? {
        val rootDoc = DocumentFile.fromTreeUri(context, rootUri) ?: return null
        if (relativePath.isBlank() || relativePath == ".") return rootDoc

        var current = rootDoc
        val segments = relativePath.split("/").filter { it.isNotBlank() }

        for (segment in segments) {
            current = current.listFiles().find { it.name == segment } ?: return null
        }
        return current
    }

    private fun guessMimeType(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        if (ext.isBlank()) return "text/plain"
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "text/plain"
    }
}