package com.xixin.codent.ui.chat.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xixin.codent.ui.main.WorkspaceTab

@Composable
fun ChatInputBar(
    isAgentWorking: Boolean,
    onNavigateTab: (WorkspaceTab) -> Unit,
    onClearChat: () -> Unit,
    onSendMessage: (String) -> Unit
) {
    var inputText by remember { mutableStateOf("") }
    val keyboardController = LocalSoftwareKeyboardController.current
    
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp, // 抹平低质感阴影，纯白/纯黑才是大厂风
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars) // 🔥 绝杀隐性需求：完美贴紧底部全面屏导航白条
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp)
        ) {
            
            // 1. 流线型快捷胶囊栏（上边缘微对齐）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                InputShortcutChip(label = "附加资源", icon = Icons.Default.Folder, onClick = { onNavigateTab(WorkspaceTab.EXPLORER) })
                InputShortcutChip(label = "代码快照", icon = Icons.Default.Code, onClick = { onNavigateTab(WorkspaceTab.PREVIEW) })
                InputShortcutChip(label = "核心配置", icon = Icons.Default.Settings, onClick = { onNavigateTab(WorkspaceTab.SETTINGS) })
                Spacer(modifier = Modifier.weight(1f))
                InputShortcutChip(label = "清空记忆", icon = Icons.Default.DeleteSweep, tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f), onClick = onClearChat)
            }
            
            // 2. 🔥 核心整容：大厂级内嵌框体组合
            Surface(
                shape = RoundedCornerShape(26.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.7f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.Bottom // 当文本多行输入时，输入框往上顶，发送按钮永远稳稳吸附在底部右侧
                ) {
                    TextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        modifier = Modifier.weight(1f),
                        enabled = !isAgentWorking,
                        placeholder = { 
                            Text(
                                text = if (isAgentWorking) "智能体正在编码/分析中..." else "下发重构指令...",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            ) 
                        },
                        maxLines = 6,
                        textStyle = MaterialTheme.typography.bodyLarge,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            disabledIndicatorColor = Color.Transparent
                        )
                    )
                    
                    // 🔥 发送按钮工业设计重写：内包于输入框体内部
                    val isSendActive = inputText.isNotBlank() && !isAgentWorking
                    val buttonColor = if (isSendActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
                    val iconColor = if (isSendActive) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    
                    Box(
                        modifier = Modifier
                            .padding(bottom = 2.dp, end = 2.dp)
                            .size(40.dp)
                            .background(buttonColor, shape = RoundedCornerShape(20.dp))
                            .clickable(enabled = isSendActive) {
                                onSendMessage(inputText.trim())
                                inputText = ""
                                keyboardController?.hide()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        if (isAgentWorking && inputText.isBlank()) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = MaterialTheme.colorScheme.primary,
                                strokeWidth = 2.5.dp
                            )
                        } else {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = "发送",
                                tint = iconColor,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun InputShortcutChip(
    label: String,
    icon: ImageVector,
    tint: Color = MaterialTheme.colorScheme.primary,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        modifier = Modifier.height(30.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
            Text(text = label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Medium)
        }
    }
}