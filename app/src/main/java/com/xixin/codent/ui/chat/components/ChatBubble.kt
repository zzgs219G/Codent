// [文件路径: app/src/main/java/com/xixin/codent/ui/chat/components/ChatBubble.kt]
package com.xixin.codent.ui.chat.components

import android.widget.Toast
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.xixin.codent.data.model.ChatMessage
import com.xixin.codent.ui.chat.ChatAction

@Composable
fun ChatBubble(
    msg: ChatMessage, 
    messageIndex: Int = -1,
    onEditUserMessage: (Int, String) -> Unit = { _, _ -> },
    // 🔥 新增：统一处理该气泡内所有补丁的事件回调
    onPatchAction: (ChatAction) -> Unit = {} 
) {
    val isUser = msg.role == "user"
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    var showEditDialog by remember { mutableStateOf(false) }
    var editText by remember { mutableStateOf(msg.content) }
    
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val maxBubbleWidth = screenWidth * 0.95f // 稍微放宽一点，让补丁卡片有呼吸感

    val bubbleShape = if (isUser) RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp) else RoundedCornerShape(20.dp, 20.dp, 20.dp, 4.dp)
    val bubbleColor = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
    val textColor = if (isUser) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Surface(
            shape = bubbleShape, color = bubbleColor,
            modifier = Modifier.widthIn(max = maxBubbleWidth).then(if (isUser && !msg.isLoading) Modifier.clickable { showEditDialog = true } else Modifier)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                if (msg.reasoningContent.isNotEmpty()) {
                    ReasoningBox(text = msg.reasoningContent, isLoading = msg.isLoading)
                    Spacer(modifier = Modifier.height(8.dp))
                }
                
                if (msg.content.isNotEmpty()) {
                    val contentParts = msg.content.split("```")
                    contentParts.forEachIndexed { index, part ->
                        val text = part.trim()
                        if (text.isNotEmpty()) {
                            if (index % 2 == 1 && !isUser) CodeBlockCard(codeText = text)
                            else SelectionContainer { SimpleMarkdownText(text = text, textColor = textColor) }
                        }
                    }
                }

                // 🔥 核心升级：遍历渲染该条消息关联的所有历史补丁！永远不会消失！
                if (msg.patches.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    msg.patches.forEachIndexed { patchIndex, patchItem ->
                        PatchConfirmationCard(
                            patch = patchItem.proposal,
                            patchState = patchItem.state,
                            onConfirm = { onPatchAction(ChatAction.ConfirmPatch(messageIndex, patchIndex, patchItem.proposal)) },
                            onReject = { onPatchAction(ChatAction.RejectPatch(messageIndex, patchIndex, patchItem.proposal)) },
                            onUndo = { onPatchAction(ChatAction.UndoPatch(messageIndex, patchIndex, patchItem.proposal)) }
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (msg.isLoading) {
                        LinearProgressIndicator(modifier = Modifier.width(60.dp).height(2.dp), color = MaterialTheme.colorScheme.primary)
                    } else if (!isUser && msg.promptTokens > 0) {
                        Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f), shape = RoundedCornerShape(4.dp)) {
                            Text("Token: ↑${msg.promptTokens} ↓${msg.completionTokens}", color = textColor.copy(alpha = 0.6f), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                        }
                    } else Spacer(modifier = Modifier.width(1.dp))

                    if (!isUser && !msg.isLoading && msg.content.isNotEmpty()) {
                        IconButton(
                            onClick = {
                                clipboardManager.setText(AnnotatedString(msg.content))
                                Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(Icons.Default.ContentCopy, "复制", tint = textColor.copy(alpha = 0.5f), modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }
    }
    
    if (showEditDialog && isUser) {
        AlertDialog(
            onDismissRequest = { showEditDialog = false },
            title = { Text("编辑并重新发送") },
            text = {
                OutlinedTextField(
                    value = editText, onValueChange = { editText = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp), maxLines = 10,
                    label = { Text("此条消息以下的历史将会被丢弃") }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (editText.isNotBlank()) onEditUserMessage(messageIndex, editText.trim())
                    showEditDialog = false
                }) { Text("覆盖重发") }
            },
            dismissButton = {
                TextButton(onClick = { editText = msg.content; showEditDialog = false }) { Text("取消") }
            }
        )
    }
}

@Composable
fun SimpleMarkdownText(text: String, textColor: Color) {
    val annotatedString = buildAnnotatedString {
        var isBold = false
        val parts = text.split("**")
        parts.forEachIndexed { index, part ->
            if (isBold) withStyle(style = SpanStyle(fontWeight = FontWeight.Bold, color = textColor)) { append(part) }
            else withStyle(style = SpanStyle(color = textColor)) { append(part) }
            if (index < parts.size - 1) isBold = !isBold
        }
    }
    Text(text = annotatedString, style = MaterialTheme.typography.bodyLarge, color = textColor)
}

@Composable
fun ReasoningBox(text: String, isLoading: Boolean) {
    var expanded by remember { mutableStateOf(isLoading) }
    LaunchedEffect(isLoading) { expanded = isLoading }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
    ) {
        Column(modifier = Modifier.animateContentSize()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Icon(Icons.Default.Psychology, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(if (isLoading) "深度思考中..." else "思考过程已折叠", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                Icon(if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
            }
            if (expanded) {
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                Box(modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp).verticalScroll(rememberScrollState())) {
                    Text(text = text, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace), color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f), modifier = Modifier.padding(12.dp))
                }
            }
        }
    }
}

@Composable
fun CodeBlockCard(codeText: String) {
    val lines = codeText.lines()
    val languageInfo = lines.firstOrNull() ?: ""
    val pureCode = if (lines.size > 1) lines.drop(1).joinToString("\n") else codeText
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(languageInfo, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                IconButton(
                    onClick = {
                        clipboardManager.setText(AnnotatedString(pureCode))
                        Toast.makeText(context, "代码已复制", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(Icons.Default.ContentCopy, "复制", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Text(
                text = pureCode,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.horizontalScroll(rememberScrollState()).padding(12.dp)
            )
        }
    }
}
