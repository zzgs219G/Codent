// [文件路径: app/src/main/java/com/xixin/codent/ui/chat/components/ChatInputBar.kt]
package com.xixin.codent.ui.chat.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp

@Composable
fun ChatInputBar(
    isAgentWorking: Boolean,
    onSendMessage: (String) -> Unit
) {
    var inputText by remember { mutableStateOf("") }
    val keyboardController = LocalSoftwareKeyboardController.current

    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                modifier = Modifier.weight(1f),
                enabled = !isAgentWorking,
                placeholder = { Text(if (isAgentWorking) "AI 调度与思考中..." else "输入指令(支持多行)...") },
                maxLines = 6,
                shape = RoundedCornerShape(24.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent
                )
            )

            FilledIconButton(
                onClick = {
                    if (inputText.isNotBlank() && !isAgentWorking) {
                        onSendMessage(inputText.trim())
                        inputText = ""
                        keyboardController?.hide()
                    }
                },
                enabled = !isAgentWorking && inputText.isNotBlank(),
                modifier = Modifier.size(52.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                if (isAgentWorking && inputText.isBlank()) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "发送")
                }
            }
        }
    }
}
