package com.xixin.codent.data.repository

import com.xixin.codent.data.model.FileNode
import com.xixin.codent.wrapper.log.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import okio.buffer
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.*

class LocalFileRepository {

    private val ignoredDirectories = setOf("build", ".git", ".gradle", ".idea", "node_modules", "captures")
    private val allowedCodeExtensions = setOf("kt", "java", "xml", "kts", "gradle", "json", "properties", "md", "txt")

    // ── 1. 目录浏览（极其干净的 NIO.2 扩展写法） ────────────────────────────────────
    fun listFilesFlow(folderPath: String): Flow<List<FileNode>> = flow {
        try {
            val rootPath = Paths.get(folderPath)
            if (!rootPath.exists() || !rootPath.isDirectory()) {
                emit(emptyList())
                return@flow
            }

            val fileList = rootPath.listDirectoryEntries().mapNotNull { path ->
                val name = path.name
                val isDir = path.isDirectory()
                if (isDir && name in ignoredDirectories) return@mapNotNull null
                
                FileNode(
                    name = name,
                    path = path.pathString, 
                    isDirectory = isDir,
                    size = if (isDir) 0L else path.fileSize()
                )
            }
            emit(fileList.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })))
        } catch (e: Exception) {
            AppLog.e("LocalFileRepository: listFilesFlow 失败: ${e.message}")
            emit(emptyList())
        }
    }.flowOn(Dispatchers.IO)

    // ── 2. 文件内容读取 (狂暴的 Okio 引擎) ──────────────────────────────────────────
    suspend fun readFileContent(filePath: String, startLine: Int? = null, endLine: Int? = null): String = withContext(Dispatchers.IO) {
        try {
            val okioPath = Paths.get(filePath).toOkioPath()
            val metadata = FileSystem.SYSTEM.metadata(okioPath)
            
            if ((metadata.size ?: 0L) > 1024 * 1024) {
                return@withContext "错误：文件过大 (>1MB)，拒绝读取以保护 Token 成本。"
            }

            FileSystem.SYSTEM.source(okioPath).buffer().use { source ->
                val allLines = source.readUtf8().lines()
                if (allLines.isEmpty()) return@withContext "文件为空"

                val actualStart = (startLine ?: 1).coerceAtLeast(1) - 1
                var actualEnd = (endLine ?: allLines.size).coerceAtMost(allLines.size)
                
                if (actualStart >= allLines.size || actualStart > actualEnd) {
                    return@withContext "错误：行号范围超出文件长度 (总行数: ${allLines.size})"
                }

                var isTruncated = false
                if (actualEnd - actualStart > 300) {
                    actualEnd = actualStart + 300
                    isTruncated = true
                }

                val snippet = allLines.subList(actualStart, actualEnd)
                    .mapIndexed { index, line -> "${actualStart + index + 1} | $line" }
                    .joinToString("\n")

                return@withContext buildString {
                    append("文件总行数: ${allLines.size}\n")
                    append("当前片段 (行 ${actualStart + 1} - $actualEnd):\n")
                    append(snippet)
                    if (isTruncated) append("\n\n...(系统截断：文件过长，超过 300 行部分已被折叠)...")
                }
            }
        } catch (e: Exception) {
            AppLog.e("LocalFileRepository: readFileContent 失败: ${e.message}")
            "读取代码失败: ${e.message}"
        }
    }

    suspend fun readRawFileContent(filePath: String): String = withContext(Dispatchers.IO) {
        try {
            FileSystem.SYSTEM.read(Paths.get(filePath).toOkioPath()) { readUtf8() }
        } catch (e: Exception) {
            AppLog.e("LocalFileRepository: readRawFileContent 失败: ${e.message}")
            "读取异常: ${e.message}"
        }
    }

    // ── 3. 核心写入操作 (MainViewModel 必须用到) ─────────────────────────
    suspend fun overwriteFile(filePath: String, newContent: String): Boolean = withContext(Dispatchers.IO) {
        try {
            File(filePath).writeText(newContent)
            true
        } catch (e: Exception) {
            AppLog.e("LocalFileRepository: overwriteFile 失败: ${e.message}")
            false
        }
    }

    // ── 4. 项目全局透视图 (NIO.2 walk 极速遍历) ─────────────────────────
        // ── 4. 项目全局透视图 (极速遍历) ─────────────────────────
    suspend fun generateProjectTree(rootPathString: String, maxDepth: Int = 12): String = withContext(Dispatchers.IO) {
        try {
            val rootFile = File(rootPathString)
            val heavyIgnore = ignoredDirectories + setOf(".cg", ".kotlin", "res", "drawable", "layout", "mipmap", "values")
            val dirMap = mutableMapOf<String, MutableList<String>>()

            rootFile.walkTopDown()
                .onEnter { 
                    it.name !in heavyIgnore && 
                    (it.absolutePath.count { char -> char == '/' } - rootPathString.count { char -> char == '/' }) <= maxDepth 
                }
                .filter { it.isFile && it.extension in allowedCodeExtensions }
                .forEach { file ->
                    val relativePath = file.absolutePath.removePrefix(rootPathString).removePrefix("/")
                    val dirKey = if (relativePath.contains("/")) relativePath.substringBeforeLast("/") else "/"
                    
                    val kb = file.length() / 1024.0
                    val sizeStr = if (kb < 1.0) "<1K" else String.format("%.1fK", kb)
                    
                    dirMap.getOrPut(dirKey) { mutableListOf() }.add("${file.name}($sizeStr)")
                }

            buildString {
                append("【项目目录结构】\n")
                dirMap.toSortedMap().forEach { (dir, fileList) ->
                    append(dir).append(": [").append(fileList.joinToString(", ")).appendLine("]")
                }
            }
        } catch (e: Exception) {
            AppLog.e("LocalFileRepository: generateProjectTree 失败: ${e.message}")
            "None"
        }
    }


    // ── 5. 供 Agent 调用的极速检索工具 ─────────────────────────
    suspend fun listDirectoryRelative(rootPath: String, relativePath: String): String = withContext(Dispatchers.IO) {
        try {
            val targetDir = if (relativePath.isBlank() || relativePath == ".") File(rootPath) else File(rootPath, relativePath)
            if (!targetDir.exists() || !targetDir.isDirectory) return@withContext "错误: 找不到目录 `$relativePath`"

            val entries = targetDir.listFiles()?.mapNotNull { file ->
                if (file.isDirectory && file.name in ignoredDirectories) null
                else "- ${if (file.isDirectory) "📁" else "📄"} ${file.name}"
            } ?: emptyList()

            if (entries.isEmpty()) "该目录目前为空。"
            else "目录 `$relativePath` 内容如下：\n${entries.joinToString("\n")}"
        } catch (e: Exception) {
            "读取目录列表出错: ${e.message}"
        }
    }

    suspend fun findFilesByName(rootPath: String, fileName: String): String = withContext(Dispatchers.IO) {
        if (fileName.isBlank()) return@withContext "搜索文件名不能为空。"
        val results = mutableListOf<String>()
        try {
            File(rootPath).walkTopDown()
                .onEnter { it.name !in ignoredDirectories }
                .filter { it.isFile && it.name.contains(fileName, ignoreCase = true) }
                .take(10)
                .forEach { file ->
                    val relPath = file.absolutePath.removePrefix(rootPath).removePrefix("/")
                    results.add("📄 $relPath")
                }
            if (results.isEmpty()) "未找到包含 `$fileName` 的文件。"
            else "找到以下文件路径:\n${results.joinToString("\n")}"
        } catch (e: Exception) {
            "搜索异常: ${e.message}"
        }
    }

    suspend fun searchKeyword(rootPath: String, keyword: String): String = withContext(Dispatchers.IO) {
        if (keyword.isBlank()) return@withContext "搜索关键字不能为空。"
        val results = mutableListOf<String>()
        try {
            File(rootPath).walkTopDown()
                .onEnter { it.name !in ignoredDirectories }
                .filter { it.isFile && it.extension in allowedCodeExtensions && it.length() < 300 * 1024 }
                .forEach { file ->
                    if (results.size >= 8) return@forEach
                    val lines = file.readLines()
                    val matchedIndices = lines.mapIndexedNotNull { i, line ->
                        if (line.contains(keyword, ignoreCase = true)) i else null
                    }
                    if (matchedIndices.isNotEmpty()) {
                        val relPath = file.absolutePath.removePrefix(rootPath).removePrefix("/")
                        val sb = StringBuilder("📄 $relPath\n")
                        matchedIndices.take(3).forEach { idx ->
                            val start = maxOf(0, idx - 1)
                            val end = minOf(lines.lastIndex, idx + 1)
                            sb.append("...\n")
                            for (i in start..end) sb.append("${i + 1} | ${lines[i]}\n")
                        }
                        sb.append("...\n")
                        results.add(sb.toString())
                    }
                }
            if (results.isEmpty()) "未检索到包含 `$keyword` 的文件。"
            else "搜索结果:\n${results.joinToString("\n")}"
        } catch (e: Exception) {
            "搜索异常: ${e.message}"
        }
    }
}
