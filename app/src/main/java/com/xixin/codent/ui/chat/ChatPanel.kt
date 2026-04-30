package com.xixin.codent.ui.chat

import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
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
    onDeleteMessage: (Int) -> Unit
) {
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(messages.size, messages.lastOrNull()?.content?.length, messages.lastOrNull()?.reasoningContent?.length) {
        if (messages.isNotEmpty()) {
            val totalItems = listState.layoutInfo.totalItemsCount
            if (totalItems > 0) {
                val lastVisibleItemIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                val isNearBottom = lastVisibleItemIndex >= totalItems - 3
                val isNewUserMessage = messages.lastOrNull()?.role == "user" && messages.lastOrNull()?.content?.isNotEmpty() == true
                
                if (isNearBottom || isNewUserMessage) {
                    listState.scrollToItem(totalItems - 1)
                }
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            itemsIndexed(messages) { index, msg ->
                ChatBubble(
                    msg = msg,
                    canDelete = true,
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

            item { Spacer(modifier = Modifier.height(1.dp)) }
        }

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
                        // 🔥 告别死板的提示词，让输入框直接变身进度提示器！
                        Text(if (isAgentWorking) "AI 调度与思考中，请稍候..." else "输入指令(支持多行)...") 
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
fun ChatBubble(msg: ChatMessage, canDelete: Boolean = false, onDelete: () -> Unit = {}) {
    val isUser = msg.role == "user"
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    
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
            modifier = Modifier.widthIn(max = maxBubbleWidth)
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

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (canDelete && !msg.isLoading) {
                            IconButton(
                                onClick = onDelete,
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.DeleteOutline,
                                    contentDescription = "删除消息",
                                    tint = textColor.copy(alpha = 0.5f),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(4.dp))
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
    }
}

@Composable
fun SimpleMarkdownText(text: String, textColor: Color) {
    val codeBgColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    val codeTextColor = MaterialTheme.colorScheme.primary

    val annotated = buildAnnotatedString {
        val boldParts = text.split("**")
        boldParts.forEachIndexed { bIndex, bPart ->
            if (bIndex % 2 == 1) {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    append(bPart)
                }
            } else {
                val codeParts = bPart.split("`")
                codeParts.forEachIndexed { cIndex, cPart ->
                    if (cIndex % 2 == 1) {
                        withStyle(
                            SpanStyle(
                                background = codeBgColor,
                                color = codeTextColor,
                                fontFamily = FontFamily.Monospace
                            )
                        ) {
                            append(" $cPart ")
                        }
                    } else {
                        append(cPart)
                    }
                }
            }
        }
    }
    
    Text(
        text = annotated,
        color = textColor,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.padding(vertical = 4.dp)
    )
}

@Composable
fun ReasoningBox(text: String, isLoading: Boolean) {
    var isExpanded by remember { mutableStateOf(isLoading) } 
    val scrollState = rememberScrollState()

    LaunchedEffect(text, isLoading) {
        if (isLoading) {
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }

    // 🔥 核心魔法：智能解析最后一行工作状态，彻底消灭黑盒焦虑！
    val titleText = remember(text, isLoading) {
        if (isLoading) {
            val lines = text.lines().filter { it.isNotBlank() }
            val lastLog = lines.lastOrNull()
            when {
                lastLog?.startsWith("> 📦") == true -> "🧠 分析返回结果..."
                lastLog?.startsWith("> 🎯 调度参数:") == true -> "🧠 正在调度: " + lastLog.substringAfter("> 🎯 调度参数: ").take(25) + "..."
                lastLog?.startsWith("> 🤖 正在调度工具:") == true -> "🧠 唤醒工具: " + lastLog.substringAfter("> 🤖 正在调度工具: ")
                else -> "🧠 AI 深思与执行中..."
            }
        } else {
            val actionCount = text.lines().count { it.startsWith("> 🤖") }
            "💡 思考完毕 (共执行 $actionCount 个动作)"
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { isExpanded = !isExpanded }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isExpanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = titleText, // 使用动态计算的炫酷标题
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        AnimatedVisibility(visible = isExpanded) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 240.dp)
                    .padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
            ) {
                SelectionContainer {
                    Text(
                        text = text.trim(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                        modifier = Modifier.verticalScroll(scrollState)
                    )
                }
            }
        }
    }
}

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

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 120.dp)
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

    if (showDetailDialog) {
        PatchFullScreenDialog(
            patch = patch,
            onDismiss = { showDetailDialog = false }
        )
    }
}

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
            dismissOnClickOutside = true,
            decorFitsSystemWindows = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.4f)),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxSize(0.95f) 
                    .clip(RoundedCornerShape(16.dp)),
                color = MaterialTheme.colorScheme.surface
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "📝 完整代码预览",
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
}