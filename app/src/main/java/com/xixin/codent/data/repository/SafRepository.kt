// [修改] 加入了协程支持和 build 文件夹过滤，解决卡死问题
package com.xixin.codent.data.repository

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.xixin.codent.data.model.FileNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

class SafRepository(private val context: Context) {

    // 过滤掉庞大的无用构建目录，防止 SAF 读取卡死
    private val ignoredDirectories = setOf("build", ".git", ".gradle", ".idea", "node_modules")

    fun takePersistableUriPermission(uri: Uri) {
        try {
            val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, takeFlags)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    suspend fun listFiles(folderUri: Uri): List<FileNode> = withContext(Dispatchers.IO) {
        val fileList = mutableListOf<FileNode>()
        val documentFile = DocumentFile.fromTreeUri(context, folderUri)

        if (documentFile != null && documentFile.isDirectory) {
            documentFile.listFiles().forEach { file ->
                val name = file.name ?: "Unknown"
                if (file.isDirectory && ignoredDirectories.contains(name)) return@forEach

                fileList.add(
                    FileNode(
                        name = name,
                        uri = file.uri,
                        isDirectory = file.isDirectory,
                        size = file.length()
                    )
                )
            }
        }
        return@withContext fileList.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
    }

    suspend fun readFileContent(fileUri: Uri): String = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openInputStream(fileUri)?.use { inputStream ->
                BufferedReader(InputStreamReader(inputStream)).use { reader ->
                    reader.readText()
                }
            } ?: "无法读取文件 (InputStream 为空)"
        } catch (e: Exception) {
            "读取出错: ${e.message}"
        }
    }
}