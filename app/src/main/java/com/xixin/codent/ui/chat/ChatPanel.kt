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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.xixin.codent.data.model.ChatMessage
import com.xixin.codent.data.model.PatchProposal
import com.xixin.codent.ui.chat.components.*
import kotlinx.coroutines.launch

// 🔥 精确制导：带上消息索引，ViewModel 才知道该去改哪条消息的状态
sealed class ChatAction {
    data class SendMessage(val text: String) : ChatAction()
    data class ConfirmPatch(val messageIndex: Int, val patchIndex: Int, val patch: PatchProposal) : ChatAction()
    data class RejectPatch(val messageIndex: Int, val patchIndex: Int, val patch: PatchProposal) : ChatAction()
    data class UndoPatch(val messageIndex: Int, val patchIndex: Int, val patch: PatchProposal) : ChatAction()
    data class DeleteMessage(val index: Int) : ChatAction()
    data class EditMessage(val index: Int, val text: String) : ChatAction()
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ChatPanel(
    messages: List<ChatMessage>,
    isAgentWorking: Boolean,
    pendingPatches: List<PatchProposal>, // 参数保留防止上层报错，但内部完全不用它了
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
            Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("对话记录与调度", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    TextButton(
                        onClick = { onAction(ChatAction.DeleteMessage(-1)) }, 
                        enabled = !isAgentWorking
                    ) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = "清空记忆", modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("清空记忆")
                    }
                }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                contentPadding = PaddingValues(vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 🔥 核心升级：补丁已经融合进 ChatBubble 里了，这里只需要循环气泡！
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

            ChatInputBar(
                isAgentWorking = isAgentWorking,
                onSendMessage = { onAction(ChatAction.SendMessage(it)) }
            )
        }

        AnimatedVisibility(
            visible = !isAtBottom && messages.isNotEmpty(),
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut(),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = 100.dp, end = 16.dp)
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
