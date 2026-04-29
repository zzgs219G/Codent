package com.xixin.codent.data.repository

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.xixin.codent.data.model.FileNode
import java.io.BufferedReader
import java.io.InputStreamReader

class SafRepository(private val context: Context) {

    fun takePersistableUriPermission(uri: Uri) {
        val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        context.contentResolver.takePersistableUriPermission(uri, takeFlags)
    }

    /**
     * 读取目录下的文件（保持不变，增加空判断）
     */
    fun listFiles(folderUri: Uri): List<FileNode> {
        val fileList = mutableListOf<FileNode>()
        val documentFile = DocumentFile.fromTreeUri(context, folderUri)
        
        if (documentFile != null && documentFile.isDirectory) {
            documentFile.listFiles().forEach { file ->
                fileList.add(
                    FileNode(
                        name = file.name ?: "Unknown",
                        uri = file.uri,
                        isDirectory = file.isDirectory,
                        size = file.length()
                    )
                )
            }
        }
        return fileList.sortedWith(compareBy({ !it.isDirectory }, { it.name }))
    }

    /**
     * 【新增】读取文件源码
     * 通过 Uri 打开输入流，把二进制转换成我们看得懂的字符串
     */
    fun readFileContent(fileUri: Uri): String {
        return try {
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