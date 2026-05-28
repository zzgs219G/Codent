package com.xixin.codent.ui.chat.components

import android.widget.Toast
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.xixin.codent.data.model.ChatMessage
import com.xixin.codent.ui.chat.ChatAction
import dev.jeziellago.compose.markdowntext.MarkdownText

@Composable
fun ChatBubble(
    msg: ChatMessage,
    messageIndex: Int = -1,
    onEditUserMessage: (Int, String) -> Unit = { _, _ -> },
    onPatchAction: (ChatAction) -> Unit = {}
) {
    val isUser = msg.role == "user"
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    var showEditDialog by remember { mutableStateOf(false) }
    var editText by remember { mutableStateOf(msg.content) }
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val maxBubbleWidth = screenWidth * 0.9f
    // Minimalist: Softer, larger corners, more subtle differences between user and AI
    val bubbleShape = if (isUser) RoundedCornerShape(24.dp, 24.dp, 8.dp, 24.dp) else RoundedCornerShape(24.dp, 24.dp, 24.dp, 8.dp)
    // Minimalist: Very subtle background colors for bubbles
    val bubbleColor = if (isUser) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f) else Color.Transparent
    val textColor = MaterialTheme.colorScheme.onSurface

    Box(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Surface(
            shape = bubbleShape,
            color = bubbleColor,
            // Minimalist: Only AI gets a slight border if it's completely transparent
            border = if (!isUser) null else null,
            modifier = Modifier
                .widthIn(max = maxBubbleWidth)
                .then(if (isUser && !msg.isLoading) Modifier.clickable { showEditDialog = true } else Modifier)
        ) {
            Column(modifier = Modifier.padding(if (isUser) 16.dp else 8.dp)) {

                if (msg.reasoningContent.isNotEmpty()) {
                    ReasoningBox(text = msg.reasoningContent, isLoading = msg.isLoading)
                    Spacer(modifier = Modifier.height(8.dp))
                }

                if (msg.content.isNotEmpty()) {
                    if (isUser) {
                        SelectionContainer {
                            Text(
                                text = msg.content,
                                style = MaterialTheme.typography.bodyLarge,
                                color = textColor
                            )
                        }
                    } else {
                        // AI 回复：MarkdownText 修复
                        MarkdownText(
                            markdown = msg.content,
                            isTextSelectable = true, // 🔥 核心修复：开启底层原生 TextView 的长按局部选择功能（支持表格、代码块等大部分区域的选择）
                            style = TextStyle(
                                color = textColor,
                                fontSize = MaterialTheme.typography.bodyLarge.fontSize,
                                lineHeight = MaterialTheme.typography.bodyLarge.lineHeight,
                            )
                        )
                    }
                }

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
                        LinearProgressIndicator(
                            modifier = Modifier.width(60.dp).height(2.dp),
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else if (!isUser && msg.promptTokens > 0) {
                        Surface(
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                "Token: ↑${msg.promptTokens} ↓${msg.completionTokens}",
                                color = textColor.copy(alpha = 0.6f),
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    } else {
                        Spacer(modifier = Modifier.width(1.dp))
                    }

                    if (!isUser && !msg.isLoading && msg.content.isNotEmpty()) {
                        // 🔥 核心修复：移除了原本严重压缩点击热区的 Modifier.size(24.dp)
                        // 恢复系统默认的 48dp 黄金触摸面积，一戳即中，完美执行全篇复制！
                        IconButton(
                            onClick = {
                                clipboardManager.setText(AnnotatedString(msg.content))
                                Toast.makeText(context, "已复制完整 AI 消息", Toast.LENGTH_SHORT).show()
                            }
                        ) {
                            Icon(
                                Icons.Default.ContentCopy, "复制",
                                tint = textColor.copy(alpha = 0.6f),
                                modifier = Modifier.size(20.dp) // 图标本身保持精致
                            )
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
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Icon(Icons.Default.Psychology, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    if (isLoading) "深度思考中..." else "思考过程已折叠",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp)
                )
            }
            if (expanded) {
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 200.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        }
    }
}
