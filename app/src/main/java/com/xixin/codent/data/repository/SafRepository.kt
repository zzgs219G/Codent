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

    private val ignoredDirectories = setOf("build", ".git", ".gradle", ".idea", "node_modules")
    private val prefs = context.getSharedPreferences("codent_settings", Context.MODE_PRIVATE)

    fun saveApiKey(key: String) = prefs.edit().putString("api_key", key).apply()
    fun getApiKey(): String = prefs.getString("api_key", "") ?: ""

    fun takePersistableUriPermission(uri: Uri) {
        try {
            val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, takeFlags)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // 优化：使用 Flow 增量发射列表，避免超大目录卡死 UI
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
                return@withContext "文件过大 (>1MB)，拒绝读取以保护内存。"
            }

            context.contentResolver.openInputStream(fileUri)?.use { inputStream ->
                BufferedReader(InputStreamReader(inputStream)).use { it.readText() }
            } ?: "无法读取文件 (InputStream 为空)"
        } catch (e: Exception) {
            "读取出错: ${e.message}"
        }
    }
}