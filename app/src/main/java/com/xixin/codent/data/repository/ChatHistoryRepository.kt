// 文件路径: app/src/main/java/com/xixin/codent/data/repository/ChatHistoryRepository.kt
//
// 重构内容：
//   - 从 SafRepository 中拆分出来，单一职责：只管聊天记录的 JSON 持久化
//   - 异常处理统一：原来 catch 后 e.printStackTrace() 很裸，现在用 AppLog 统一
package com.xixin.codent.data.repository

import android.content.Context
import com.xixin.codent.data.model.ChatMessage
import com.xixin.codent.wrapper.log.AppLog
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

class ChatHistoryRepository(context: Context) {

    private val chatHistoryFile = File(context.filesDir, "chat_history.json")

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun save(messages: List<ChatMessage>) {
        try {
            val validMessages = messages.filterNot { it.isLoading }
            chatHistoryFile.writeText(json.encodeToString(validMessages))
        } catch (e: Exception) {
            AppLog.e("ChatHistoryRepository: 保存聊天记录失败: ${e.message}")
        }
    }

    fun load(): List<ChatMessage> {
        if (!chatHistoryFile.exists()) return emptyList()
        return try {
            json.decodeFromString(chatHistoryFile.readText())
        } catch (e: Exception) {
            AppLog.e("ChatHistoryRepository: 加载聊天记录失败，文件可能损坏: ${e.message}")
            emptyList()
        }
    }

    fun clear() {
        if (chatHistoryFile.exists()) {
            chatHistoryFile.delete()
        }
    }
}
