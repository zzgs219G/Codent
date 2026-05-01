
package com.xixin.codent.ui.chat

import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.xixin.codent.data.model.ChatMessage
import com.xixin.codent.data.model.PatchProposal

/**
 * [ChatPanel] - 终端对话面板
 * 已经重构为支持多文件补丁连击模式
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatPanel(
    messages: List<ChatMessage>,
    isAgentWorking: Boolean,
    pendingPatches: List<PatchProposal>, // 🔥 核心修改：改为支持列表
    onSendMessage: (String) -> Unit,
    onConfirmPatch: (PatchProposal) -> Unit, // 🔥 核心修改：回调带上具体的 patch
    onRejectPatch: (PatchProposal) -> Unit,  // 🔥 核心修改：回调带上具体的 patch
    onDeleteMessage: (Int) -> Unit,
    onEditUserMessage: (Int, String) -> Unit
) {
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val keyboardController = LocalSoftwareKeyboardController.current

    // 当消息增多或思考内容更新时，自动滚动到底部
    LaunchedEffect(messages.size, messages.lastOrNull()?.content?.length, messages.lastOrNull()?.reasoningContent?.length) {
        if (messages.isNotEmpty()) {
            val totalItems = listState.layoutInfo.totalItemsCount
            if (totalItems > 0) {
                listState.scrollToItem(totalItems - 1)
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // 顶部操作栏
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("对话记录与调度", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                TextButton(onClick = { onDeleteMessage(-1) }, enabled = !isAgentWorking) {
                    Icon(Icons.Default.DeleteSweep, contentDescription = "清空记忆", modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("清空记忆")
                }
            }
        }

        // 主消息区域
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 渲染对话历史
            itemsIndexed(messages) { index, msg ->
                ChatBubble(
                    msg = msg,
                    messageIndex = index,
                    onEditUserMessage = onEditUserMessage
                )
            }

            // 🔥 核心修改：渲染待处理补丁列表
            // 使用 items 循环显示每一个 PatchProposal
            items(pendingPatches) { patch ->
                PatchConfirmationCard(
                    patch = patch,
                    onConfirm = { onConfirmPatch(patch) },
                    onReject = { onRejectPatch(patch) }
                )
            }

            item { Spacer(modifier = Modifier.height(1.dp)) }
        }

        // 底部输入区域
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
                    placeholder = { 
                        Text(if (isAgentWorking) "AI 调度与思考中..." else "输入指令(支持多行)...") 
                    },
                    maxLines = 6,
                    shape = RoundedCornerShape(24.dp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent
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
}

@Composable
fun ChatBubble(
    msg: ChatMessage, 
    messageIndex: Int = -1,
    onEditUserMessage: (Int, String) -> Unit = { _, _ -> }
) {
    val isUser = msg.role == "user"
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    var showEditDialog by remember { mutableStateOf(false) }
    var editText by remember { mutableStateOf(msg.content) }
    
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val maxBubbleWidth = screenWidth * 0.85f 

    val bubbleShape = if (isUser) {
        RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp)
    } else {
        RoundedCornerShape(20.dp, 20.dp, 20.dp, 4.dp)
    }

    val bubbleColor = if (isUser) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
    }

    val textColor = if (isUser) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Surface(
            shape = bubbleShape,
            color = bubbleColor,
            modifier = Modifier
                .widthIn(max = maxBubbleWidth)
                .then(
                    if (isUser && !msg.isLoading) Modifier.clickable { showEditDialog = true }
                    else Modifier
                )
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
                            if (index % 2 == 1 && !isUser) {
                                CodeBlockCard(codeText = text)
                            } else {
                                SelectionContainer {
                                    SimpleMarkdownText(text = text, textColor = textColor)
                                }
                            }
                        }
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
                                text = "Token: ↑${msg.promptTokens} ↓${msg.completionTokens}",
                                color = textColor.copy(alpha = 0.6f),
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    } else {
                        Spacer(modifier = Modifier.width(1.dp))
                    }

                    if (!isUser && !msg.isLoading && msg.content.isNotEmpty()) {
                        IconButton(
                            onClick = {
                                clipboardManager.setText(AnnotatedString(msg.content))
                                Toast.makeText(context, "已复制完整回复", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = "复制回复",
                                tint = textColor.copy(alpha = 0.5f),
                                modifier = Modifier.size(16.dp)
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
                    value = editText,
                    onValueChange = { editText = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp),
                    maxLines = 10,
                    label = { Text("此条消息以下的历史将会被丢弃") }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (editText.isNotBlank()) {
                        onEditUserMessage(messageIndex, editText.trim())
                    }
                    showEditDialog = false
                }) {
                    Text("覆盖重发")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    editText = msg.content
                    showEditDialog = false
                }) {
                    Text("取消")
                }
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
            if (isBold) {
                withStyle(style = SpanStyle(fontWeight = FontWeight.Bold, color = textColor)) { append(part) }
            } else {
                withStyle(style = SpanStyle(color = textColor)) { append(part) }
            }
            if (index < parts.size - 1) { isBold = !isBold }
        }
    }
    Text(
        text = annotatedString,
        style = MaterialTheme.typography.bodyLarge,
        color = textColor
    )
}

@Composable
fun ReasoningBox(text: String, isLoading: Boolean) {
    var expanded by remember { mutableStateOf(isLoading) }
    LaunchedEffect(isLoading) { if (isLoading) expanded = true else expanded = false }

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
                Icon(
                    imageVector = Icons.Default.Psychology,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isLoading) "深度思考中..." else "思考过程已折叠",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
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

@Composable
fun CodeBlockCard(codeText: String) {
    val lines = codeText.lines()
    val languageInfo = lines.firstOrNull() ?: ""
    val pureCode = if (lines.size > 1) lines.drop(1).joinToString("\n") else codeText
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    Surface(
        color = Color(0xFF1E1E1E),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF2D2D2D))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = languageInfo,
                    color = Color.LightGray,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace
                )
                IconButton(
                    onClick = {
                        clipboardManager.setText(AnnotatedString(pureCode))
                        Toast.makeText(context, "代码已复制", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "复制代码",
                        tint = Color.LightGray,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
            Text(
                text = pureCode,
                color = Color(0xFFD4D4D4),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(12.dp)
            )
        }
    }
}

@Composable
fun PatchConfirmationCard(patch: PatchProposal, onConfirm: () -> Unit, onReject: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shadowElevation = 4.dp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Build,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onTertiaryContainer
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "代码修改提议",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = patch.targetFileName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f)
            )
            Spacer(modifier = Modifier.height(8.dp))

            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp)
            ) {
                Text(
                    text = patch.diffText,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(8.dp)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onReject) {
                    Text("拒绝", color = MaterialTheme.colorScheme.error)
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = onConfirm,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.onTertiaryContainer)
                ) {
                    Text("应用方案")
                }
            }
        }
    }
}


