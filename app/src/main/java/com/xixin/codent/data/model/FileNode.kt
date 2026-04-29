package com.xixin.codent.data.model

import android.net.Uri

/**
 * 这是一个数据类，用来表示一个文件或文件夹。
 * AI 时代不用管底层指针，只要知道名字、路径、是不是文件夹就行了。
 */
data class FileNode(
    val name: String,         // 文件名，比如 "MainActivity.kt"
    val uri: Uri,             // 文件的系统唯一地址（SAF 专属的 Uri 格式）
    val isDirectory: Boolean, // 是不是文件夹
    val size: Long = 0L       // 文件大小（备用）
)