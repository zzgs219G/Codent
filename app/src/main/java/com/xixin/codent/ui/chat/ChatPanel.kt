package com.xixin.codent.ui.chat

import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.xixin.codent.data.model.ChatMessage
import com.xixin.codent.data.model.PatchProposal

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatPanel(
    messages: List<ChatMessage>,
    isAgentWorking: Boolean,
    pendingPatch: PatchProposal?,
    onSendMessage: (String) -> Unit,
    onConfirmPatch: () -> Unit,
    onRejectPatch: () -> Unit,
    onDeleteMessage: (Int) -> Unit // 🔥 修复报错 2 & 3：加上了缺失的删除回调
) {
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val keyboardController = LocalSoftwareKeyboardController.current

    // 智能防打扰自动滚动逻辑 
    LaunchedEffect(messages.size, messages.lastOrNull()?.content?.length) {
        if (messages.isNotEmpty()) {
            val totalItems = listState.layoutInfo.totalItemsCount
            if (totalItems > 0) {
                val lastVisibleItemIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                val isNearBottom = lastVisibleItemIndex >= totalItems - 3
                if (isNearBottom || messages.lastOrNull()?.role == "user") {
                    listState.scrollToItem(totalItems - 1)
                }
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // 聊天记录区
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 🔥 使用 itemsIndexed 以便获取 index 用于删除
            itemsIndexed(messages) { index, msg ->
                ChatBubble(
                    msg = msg,
                    canDelete = msg.role == "user",
                    onDelete = { onDeleteMessage(index) }
                )
            }

            if (pendingPatch != null) {
                item {
                    PatchConfirmationCard(
                        patch = pendingPatch,
                        onConfirm = onConfirmPatch,
                        onReject = onRejectPatch
                    )
                }
            }

            // 隐形底部垫脚石
            item { Spacer(modifier = Modifier.height(1.dp)) }
        }

        // 底部输入区
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
                    placeholder = { Text(if (isAgentWorking) "AI 思考中..." else "输入指令...") },
                    maxLines = 4,
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

// 🔥 恢复大厂级别气泡，同时融入 AI 想要的删除功能
@Composable
fun ChatBubble(msg: ChatMessage, canDelete: Boolean = false, onDelete: () -> Unit = {}) {
    val isUser = msg.role == "user"
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    
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
            modifier = Modifier.widthIn(max = 340.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                
                // 核心：简易 Markdown 渲染引擎，剥离纯文本和代码块
                val contentParts = msg.content.split("```")
                
                contentParts.forEachIndexed { index, part ->
                    val text = part.trim()
                    if (text.isNotEmpty()) {
                        if (index % 2 == 1 && !isUser) {
                            // 代码块渲染
                            CodeBlockCard(codeText = text)
                        } else {
                            // 普通文本渲染
                            SelectionContainer {
                                Text(
                                    text = text,
                                    color = textColor,
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.padding(vertical = 4.dp)
                                )
                            }
                        }
                    }
                }

                // 底部状态栏 (Token 消耗 & 复制按钮 & 删除按钮)
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
                        Text(
                            text = "耗时: ${msg.promptTokens}↑ ${msg.completionTokens}↓",
                            color = textColor.copy(alpha = 0.5f),
                            style = MaterialTheme.typography.labelSmall
                        )
                    } else {
                        Spacer(modifier = Modifier.width(1.dp))
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // 🔥 用户的消息专属删除按钮
                        if (canDelete) {
                            IconButton(
                                onClick = onDelete,
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.DeleteOutline,
                                    contentDescription = "删除消息",
                                    tint = textColor.copy(alpha = 0.6f),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                        }

                        // AI 回复专属复制按钮
                        if (!isUser && !msg.isLoading) {
                            IconButton(
                                onClick = {
                                    clipboardManager.setText(AnnotatedString(msg.content))
                                    Toast.makeText(context, "已复制全部回复", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ContentCopy,
                                    contentDescription = "复制回复",
                                    tint = textColor.copy(alpha = 0.6f),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// 精美的代码块组件
@Composable
fun CodeBlockCard(codeText: String) {
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    
    val lines = codeText.split("\n")
    val language = if (lines.isNotEmpty() && !lines.first().contains(" ")) lines.first() else "code"
    val cleanCode = if (lines.size > 1 && language != "code") {
        lines.drop(1).joinToString("\n")
    } else codeText

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = language.lowercase(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace
                )
                IconButton(
                    onClick = {
                        clipboardManager.setText(AnnotatedString(cleanCode))
                        Toast.makeText(context, "代码已复制", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "复制代码",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
            
            Box(modifier = Modifier.fillMaxWidth()) {
                SelectionContainer {
                    Text(
                        text = cleanCode,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .padding(12.dp)
                            .horizontalScroll(rememberScrollState())
                    )
                }
                
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .width(16.dp)
                        .fillMaxHeight()
                        .background(Brush.horizontalGradient(listOf(Color.Transparent, MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha=0.8f))))
                )
            }
        }
    }
}

// 补丁确认卡片
@Composable
fun PatchConfirmationCard(patch: PatchProposal, onConfirm: () -> Unit, onReject: () -> Unit) {
    var showDetailDialog by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "📝 代码修改提议",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Text(
                text = "文件: ${patch.targetFileName}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                modifier = Modifier.padding(bottom = 8.dp)
            )

            // 预览小窗口
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 120.dp) // 压缩高度，鼓励点开详情看
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            ) {
                SelectionContainer {
                    Text(
                        
                        text = patch.diffText,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(8.dp)
                    )
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(32.dp)
                        .background(Brush.verticalGradient(listOf(Color.Transparent, MaterialTheme.colorScheme.surfaceContainerLowest)))
                )
            }

            Row(
                horizontalArrangement = Arrangement.End,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
            ) {
                TextButton(onClick = onReject) {
                    Text("拒绝", color = MaterialTheme.colorScheme.onSecondaryContainer)
                }
                Spacer(modifier = Modifier.width(4.dp))
                // 查看详情按钮
                TextButton(onClick = { showDetailDialog = true }) {
                    Text("全屏查看详情", color = MaterialTheme.colorScheme.onSecondaryContainer)
                }
                Spacer(modifier = Modifier.width(4.dp))
                Button(
                    onClick = onConfirm,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        contentColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Text("应用修改")
                }
            }
        }
    }

    // 弹窗组件：全屏展示代码
    if (showDetailDialog) {
        PatchFullScreenDialog(
            patch = patch,
            onDismiss = { showDetailDialog = false }
        )
    }
}

// 独立的 Dialog 组件，用于安全地展示全量代码
@Composable
private fun PatchFullScreenDialog(
    patch: PatchProposal,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding(),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // 顶部标题栏
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "📝 修改后的完整代码",
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(
                            text = patch.targetFileName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "关闭")
                    }
                }

                HorizontalDivider()

                // 全屏代码查看区域（支持双向滚动）
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                ) {
                    val verticalScrollState = rememberScrollState()
                    val horizontalScrollState = rememberScrollState()

                    SelectionContainer {
                        Text(
                            text = patch.proposedContent,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier
                                .padding(16.dp)
                                .verticalScroll(verticalScrollState)
                                .horizontalScroll(horizontalScrollState)
                        )
                    }
                }
            }
        }
    }
}
