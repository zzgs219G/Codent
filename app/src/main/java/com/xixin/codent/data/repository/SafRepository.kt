package com.xixin.codent.data.repository

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.xixin.codent.data.model.FileNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

class SafRepository(private val context: Context) {

    private val ignoredDirectories = setOf("build", ".git", ".gradle", ".idea", "node_modules", "captures")
    private val allowedCodeExtensions = setOf("kt", "java", "xml", "kts", "gradle", "json", "properties", "md", "txt")
    
    private val prefs = context.getSharedPreferences("codent_settings", Context.MODE_PRIVATE)

    fun saveApiKey(key: String) = prefs.edit().putString("api_key", key).apply()
    fun getApiKey(): String = prefs.getString("api_key", "") ?: ""

    fun saveSelectedModel(model: String) = prefs.edit().putString("selected_model", model).apply()
    fun getSelectedModel(): String = prefs.getString("selected_model", "deepseek-v4-flash") ?: "deepseek-v4-flash"

    // 新增
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

    fun listFilesFlow(folderUri: Uri): Flow<List<FileNode>> = flow {
        val documentFile = DocumentFile.fromTreeUri(context, folderUri)
        val fileList = mutableListOf<FileNode>()
        if (documentFile != null && documentFile.isDirectory) {
            val files = documentFile.listFiles()
            files.forEachIndexed { index, file ->
                val name = file.name ?: "Unknown"
                if (!(file.isDirectory && ignoredDirectories.contains(name))) {
                    fileList.add(FileNode(name, file.uri, file.isDirectory, file.length()))
                }
                if (index % 20 == 0) {
                    emit(fileList.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })))
                }
            }
            emit(fileList.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })))
        }
    }.flowOn(Dispatchers.IO)

    suspend fun readFileContent(fileUri: Uri): String = withContext(Dispatchers.IO) {
        try {
            val documentFile = DocumentFile.fromSingleUri(context, fileUri)
            if (documentFile != null && documentFile.length() > 1024 * 1024) {
                return@withContext "错误：文件过大 (>1MB)，Agent 拒绝读取以保护 Token 成本。"
            }
            context.contentResolver.openInputStream(fileUri)?.use { inputStream ->
                BufferedReader(InputStreamReader(inputStream)).use { it.readText() }
            } ?: "无法读取文件 (InputStream 为空)"
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

    suspend fun overwriteFile(fileUri: Uri, newContent: String): Boolean = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openOutputStream(fileUri, "wt")?.use { outputStream ->
                outputStream.write(newContent.toByteArray())
                outputStream.flush()
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
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
            if (targetDoc == null || !targetDoc.isDirectory) {
                return@withContext "错误: 找不到目录 `$relativePath`"
            }
            val files = targetDoc.listFiles().filter { it.name != null && !(it.isDirectory && ignoredDirectories.contains(it.name)) }
            if (files.isEmpty()) return@withContext "该目录目前为空。"
            buildString {
                append("目录 `$relativePath` 内容如下：\n")
                files.forEach { file ->
                    val icon = if (file.isDirectory) "📁" else "📄"
                    append("- $icon ${file.name}\n")
                }
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
            if (results.size >= 12) return
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
                            val content = stream.bufferedReader().use { it.readText() }
                            if (content.contains(keyword, ignoreCase = true)) {
                                results.add(currentPath)
                            }
                        }
                    } catch (e: Exception) { }
                }
            }
        }
        try {
            traverse(rootDoc, "")
            if (results.isEmpty()) "在项目中未检索到包含 `$keyword` 的相关文件。"
            else buildString {
                append("雷达搜索结果 (包含关键字 `$keyword` 的文件):\n")
                results.forEach { append("- $it\n") }
            }
        } catch (e: Exception) {
            "搜索过程发生异常: ${e.message}"
        }
    }
    // 在 Sa
}