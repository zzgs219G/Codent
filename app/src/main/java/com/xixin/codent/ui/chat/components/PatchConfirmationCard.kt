// [文件路径: app/src/main/java/com/xixin/codent/ui/chat/components/PatchConfirmationCard.kt]
package com.xixin.codent.ui.chat.components

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.xixin.codent.data.model.PatchProposal
// 🔥 核心修复：链接到 model 里的状态，不再在本地重复定义
import com.xixin.codent.data.model.PatchState 

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PatchConfirmationCard(
    patch: PatchProposal, 
    patchState: PatchState = PatchState.PENDING,
    onConfirm: () -> Unit = {}, 
    onReject: () -> Unit = {},
    onUndo: () -> Unit = {}
) {
    var isExpanded by remember { mutableStateOf(patchState == PatchState.PENDING) }
    var isFullScreen by remember { mutableStateOf(false) }

    // 提取纯文件名用于小窗展示
    val shortFileName = patch.targetFileName.substringAfterLast("/")

    ElevatedCard(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = if (isExpanded) 4.dp else 1.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp)
            .animateContentSize(animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium))
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            
            // 细长的日志头部
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isExpanded = !isExpanded }
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Icon(Icons.Default.Code, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                
                Text(
                    text = shortFileName,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                
                StatusBadge(patchState)
                Spacer(modifier = Modifier.width(8.dp))
                Icon(if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, null, tint = MaterialTheme.colorScheme.outline)
            }

            if (isExpanded) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("代码修改预览", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
                        IconButton(onClick = { isFullScreen = true }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.Fullscreen, "全屏", tint = MaterialTheme.colorScheme.primary)
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Box(modifier = Modifier.padding(10.dp)) {
                            DiffTextRenderer(patch.diffText, maxLines = 8)
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // 底部动态按钮区域
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        when (patchState) {
                            PatchState.PENDING -> {
                                OutlinedButton(
                                    onClick = { onReject(); isExpanded = false },
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f))
                                ) {
                                    Icon(Icons.Default.Close, null, Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("拒绝")
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Button(onClick = { onConfirm(); isExpanded = false }) {
                                    Icon(Icons.Default.Check, null, Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("应用方案")
                                }
                            }
                            PatchState.APPLIED -> {
                                OutlinedButton(onClick = onUndo) {
                                    Icon(Icons.Default.Undo, null, Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("撤回此修改")
                                }
                            }
                            PatchState.REJECTED -> {
                                Text("已丢弃此补丁", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline, modifier = Modifier.align(Alignment.CenterVertically))
                            }
                        }
                    }
                }
            }
        }
    }

    // 全屏 Dialog
    if (isFullScreen) {
        Dialog(
            onDismissRequest = { isFullScreen = false },
            properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)
        ) {
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                Column(modifier = Modifier.fillMaxSize()) {
                    TopAppBar(
                        title = { 
                            Column {
                                Text("代码审查", style = MaterialTheme.typography.titleMedium)
                                Text(patch.targetFileName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            }
                        },
                        actions = {
                            IconButton(onClick = { isFullScreen = false }) {
                                Icon(Icons.Default.FullscreenExit, "退出")
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                    )
                    
                    SelectionContainer(modifier = Modifier.fillMaxSize().weight(1f)) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                                .verticalScroll(rememberScrollState())
                                .horizontalScroll(rememberScrollState())
                        ) {
                            DiffTextRenderer(patch.diffText, maxLines = Int.MAX_VALUE)
                        }
                    }
                    
                    if (patchState == PatchState.PENDING) {
                        Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
                            Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.End) {
                                OutlinedButton(
                                    onClick = { isFullScreen = false; onReject(); isExpanded = false },
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f))
                                ) { Text("拒绝") }
                                Spacer(modifier = Modifier.width(16.dp))
                                Button(onClick = { isFullScreen = false; onConfirm(); isExpanded = false }) { Text("应用并关闭") }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun StatusBadge(state: PatchState) {
    val color = when (state) {
        PatchState.PENDING -> MaterialTheme.colorScheme.tertiary
        PatchState.APPLIED -> Color(0xFF4CAF50)
        PatchState.REJECTED -> MaterialTheme.colorScheme.error
    }
    val text = when (state) {
        PatchState.PENDING -> "待审批"
        PatchState.APPLIED -> "已应用"
        PatchState.REJECTED -> "已拒绝"
    }
    
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(text = text, color = color, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, fontSize = 10.sp)
    }
}

@Composable
fun DiffTextRenderer(diffText: String, maxLines: Int) {
    val isDark = isSystemInDarkTheme()
    val addColor = if (isDark) Color(0xFF81C784) else Color(0xFF2E7D32)
    val removeColor = if (isDark) Color(0xFFE57373) else Color(0xFFC62828)
    val warnColor = if (isDark) Color(0xFFFFD54F) else Color(0xFFF57F17)
    val baseTextColor = MaterialTheme.colorScheme.onSurfaceVariant

    val annotatedString = buildAnnotatedString {
        diffText.lines().forEach { line ->
            when {
                line.startsWith("⚠️") -> withStyle(SpanStyle(color = warnColor, fontWeight = FontWeight.Bold)) { append(line) }
                line.startsWith("---") || line.startsWith("-----") || line.startsWith("文件:") -> withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)) { append(line) }
                line.startsWith("- ") -> withStyle(SpanStyle(color = removeColor, background = removeColor.copy(alpha = if (isDark) 0.15f else 0.1f))) { append(line) }
                line.startsWith("+ ") -> withStyle(SpanStyle(color = addColor, background = addColor.copy(alpha = if (isDark) 0.15f else 0.1f))) { append(line) }
                else -> withStyle(SpanStyle(color = baseTextColor)) { append(line) }
            }
            append("\n")
        }
    }

    Text(
        text = annotatedString,
        fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.bodySmall,
        lineHeight = 18.sp,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis
    )
}
