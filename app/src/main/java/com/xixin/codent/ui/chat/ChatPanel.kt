// [文件路径: app/src/main/java/com/xixin/codent/ui/chat/ChatPanel.kt]
package com.xixin.codent.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xixin.codent.data.model.ChatMessage
import com.xixin.codent.data.model.PatchProposal
import com.xixin.codent.ui.chat.components.*
import com.xixin.codent.ui.main.WorkspaceTab
import kotlinx.coroutines.launch

sealed class ChatAction {
    data class SendMessage(val text: String) : ChatAction()
    data class ConfirmPatch(val messageIndex: Int, val patchIndex: Int, val patch: PatchProposal) : ChatAction()
    data class RejectPatch(val messageIndex: Int, val patchIndex: Int, val patch: PatchProposal) : ChatAction()
    data class UndoPatch(val messageIndex: Int, val patchIndex: Int, val patch: PatchProposal) : ChatAction()
    data class DeleteMessage(val index: Int) : ChatAction()
    data class EditMessage(val index: Int, val text: String) : ChatAction()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatPanel(
    messages: List<ChatMessage>,
    isAgentWorking: Boolean,
    pendingPatches: List<PatchProposal> = emptyList(), // 参数保留兼顾签名
    onOpenDrawer: () -> Unit,
    onNavigateTab: (WorkspaceTab) -> Unit,
    onAction: (ChatAction) -> Unit
) {
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    
    val isAtBottom by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val totalItems = layoutInfo.totalItemsCount
            if (totalItems == 0) return@derivedStateOf true
            val lastVisibleItem = layoutInfo.visibleItemsInfo.lastOrNull()
            lastVisibleItem?.index == totalItems - 1
        }
    }
    
    LaunchedEffect(messages.size, messages.lastOrNull()?.content?.length, messages.lastOrNull()?.reasoningContent?.length) {
        if (messages.isNotEmpty() && isAtBottom) {
            listState.scrollToItem(listState.layoutInfo.totalItemsCount - 1)
        }
    }
    
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
        ) {
            // 🔥 将旧的简单行顶栏升级为标准的 CenterAlignedTopAppBar
            CenterAlignedTopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "Codent 助手",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (isAgentWorking) "自主进化调度运行中..." else "随时准备阅读和优化项目代码",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isAgentWorking) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer) {
                        Icon(imageVector = Icons.Default.Menu, contentDescription = "打开主菜单")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { onAction(ChatAction.DeleteMessage(-1)) },
                        enabled = !isAgentWorking
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteSweep,
                            contentDescription = "清空记忆",
                            tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            )
            
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
                        messageIndex = index,
                        onEditUserMessage = { idx, text -> onAction(ChatAction.EditMessage(idx, text)) },
                        onPatchAction = { action -> onAction(action) }
                    )
                }
                item { Spacer(modifier = Modifier.height(1.dp)) }
            }
            
            // 🔥 为 InputBar 传入导航切换回调，实现输入栏上方的胶囊按键操作
            ChatInputBar(
                isAgentWorking = isAgentWorking,
                onNavigateTab = onNavigateTab,
                onClearChat = { onAction(ChatAction.DeleteMessage(-1)) },
                onSendMessage = { onAction(ChatAction.SendMessage(it)) }
            )
        }
        
        AnimatedVisibility(
            visible = !isAtBottom && messages.isNotEmpty(),
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut(),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = 160.dp, end = 16.dp) // 🔥 提升高度避让输入框上的快捷键
        ) {
            SmallFloatingActionButton(
                onClick = {
                    coroutineScope.launch {
                        listState.animateScrollToItem(listState.layoutInfo.totalItemsCount - 1)
                    }
                },
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            ) {
                Icon(Icons.Default.ArrowDownward, contentDescription = "回到底部")
            }
        }
    }
}

