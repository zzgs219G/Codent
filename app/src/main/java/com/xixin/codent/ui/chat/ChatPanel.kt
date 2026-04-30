package com.xixin.codent.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xixin.codent.data.model.ChatMessage
import com.xixin.codent.data.model.PatchProposal

@Composable
fun ChatPanel(
    messages: List<ChatMessage>,
    isAgentWorking: Boolean,
    pendingPatch: PatchProposal?,
    onSendMessage: (String) -> Unit,
    onConfirmPatch: () -> Unit,
    onRejectPatch: () -> Unit,
    modifier: Modifier = Modifier
) {
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val haptic = LocalHapticFeedback.current

    LaunchedEffect(messages.size, messages.lastOrNull()?.content?.length, pendingPatch) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    Column(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
            contentPadding = PaddingValues(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            items(messages) { msg -> ChatBubble(message = msg) }
            
            if (pendingPatch != null) {
                item { PatchConfirmationCard(patch = pendingPatch, onConfirm = onConfirmPatch, onReject = onRejectPatch) }
            }
        }

        Surface(color = MaterialTheme.colorScheme.surfaceVariant, tonalElevation = 2.dp) {
            Row(
                modifier = Modifier.fillMaxWidth().imePadding().padding(16.dp).navigationBarsPadding(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(if (isAgentWorking) "Agent 脑力运转中..." else "输入需求...") },
                    enabled = !isAgentWorking,
                    shape = RoundedCornerShape(24.dp),
                    maxLines = 3,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline
                    )
                )
                Spacer(modifier = Modifier.width(8.dp))
                FilledIconButton(
                    onClick = {
                        if (inputText.isNotBlank() && !isAgentWorking) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onSendMessage(inputText)
                            inputText = ""
                        }
                    },
                    enabled = inputText.isNotBlank() && !isAgentWorking,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "发送")
                }
            }
        }
    }
}

@Composable
fun PatchConfirmationCard(patch: PatchProposal, onConfirm: () -> Unit, onReject: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Code, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(8.dp))
                Text("即将修改: ${patch.targetFileName}", style = MaterialTheme.typography.titleMedium)
            }
            Spacer(modifier = Modifier.height(12.dp))
            
            // 🔥 老板要的：硬核红绿 Diff 代码块渲染 🔥
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 250.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF1E1E1E))
                    .padding(8.dp)
            ) {
                Column(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    patch.diffString.split("\n").forEach { line ->
                        val textColor = when {
                            line.startsWith("+") -> Color(0xFF4CAF50) // 绿色增
                            line.startsWith("-") -> Color(0xFFE53935) // 红色删
                            line.startsWith("@@") -> Color(0xFF29B6F6) // 蓝色行号
                            else -> Color(0xFFE0E0E0)
                        }
                        Text(text = line, color = textColor, fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 1)
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onReject) {
                    Icon(Icons.Default.Close, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("拒绝", color = MaterialTheme.colorScheme.error)
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(onClick = onConfirm) {
                    Icon(Icons.Default.Check, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("确认应用 Patch")
                }
            }
        }
    }
}

@Composable
fun ChatBubble(message: ChatMessage) {
    val isUser = message.role == "user"
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        Box(
            modifier = Modifier
                .widthIn(min = 60.dp, max = 320.dp)
                .clip(RoundedCornerShape(
                    topStart = 20.dp, topEnd = 20.dp, 
                    bottomStart = if (isUser) 20.dp else 4.dp, 
                    bottomEnd = if (isUser) 4.dp else 20.dp
                ))
                .background(if (isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer)
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            if (message.isLoading && message.content.isEmpty()) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Text(
                    text = message.content, 
                    color = if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer,
                    style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 24.sp)
                )
            }
        }

        // 🔥 老板要的：用量账单角标 🔥
        if (!isUser && message.promptTokens > 0) {
            Spacer(modifier = Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 8.dp)) {
                Icon(Icons.Default.ReceiptLong, null, modifier = Modifier.size(12.dp), tint = MaterialTheme.colorScheme.outline)
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "上传约 ${message.uploadChars} 字符 | 消耗: ${message.promptTokens} 读 / ${message.completionTokens} 写",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}