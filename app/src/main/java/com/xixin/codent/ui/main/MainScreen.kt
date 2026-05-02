// [文件路径: app/src/main/java/com/xixin/codent/ui/main/MainScreen.kt]
package com.xixin.codent.ui.main

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xixin.codent.ui.chat.ChatPanel
import com.xixin.codent.ui.chat.ChatAction
import com.xixin.codent.ui.editor.EditorPanel
import com.xixin.codent.ui.explorer.ExplorerPanel
import com.xixin.codent.ui.settings.SettingsPanel

enum class WorkspaceTab(val title: String, val icon: ImageVector) {
    EXPLORER("资源", Icons.Default.Folder),
    PREVIEW("预览", Icons.Default.Code),
    AGENT("终端", Icons.Default.Terminal),
    SETTINGS("设置", Icons.Default.Settings)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MainScreen(viewModel: MainViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    var currentTab by remember { mutableStateOf(WorkspaceTab.EXPLORER) }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? -> uri?.let { viewModel.initWorkspace(it) } }

    BackHandler(enabled = uiState.directoryStack.isNotEmpty() && currentTab == WorkspaceTab.EXPLORER) {
        viewModel.navigateBack()
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        bottomBar = {
            NavigationBar {
                WorkspaceTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = currentTab == tab,
                        onClick = { currentTab = tab },
                        icon = { Icon(tab.icon, contentDescription = tab.title) },
                        label = { Text(tab.title) }
                    )
                }
            }
        }
    ) { innerPadding ->
        AnimatedContent(
            targetState = currentTab,
            label = "Tab Transition",
            transitionSpec = {
                slideInHorizontally { width -> width / 2 } + fadeIn() togetherWith 
                slideOutHorizontally { width -> -width / 2 } + fadeOut()
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding) 
        ) { tab ->
            when (tab) {
                WorkspaceTab.EXPLORER -> ExplorerPanel(
                    uiState = uiState,
                    onInitWorkspace = { folderPickerLauncher.launch(null) },
                    onNavigateBack = { viewModel.navigateBack() },
                    onFolderClick = { viewModel.navigateIntoFolder(it) },
                    onFileClick = { fileNode -> viewModel.openFile(fileNode) { currentTab = WorkspaceTab.PREVIEW } }
                )
                WorkspaceTab.PREVIEW -> EditorPanel(
                    fileName = uiState.selectedFile?.name ?: "未选中文件",
                    content = uiState.currentCodeContent
                )
                WorkspaceTab.AGENT -> ChatPanel(
                    messages = uiState.chatMessages,
                    isAgentWorking = uiState.isAgentWorking,
                    pendingPatches = uiState.pendingPatches, 
                    onAction = { action ->
                        // 🔥 核心修复点：这里的 when 必须和我们在 ChatPanel 里定义的 ChatAction 一一对应
                        when (action) {
                            is ChatAction.SendMessage -> viewModel.sendChatMessage(action.text)
                            
                            // 1. 确认补丁：传入三个参数 (消息索引, 补丁索引, 补丁对象)
                            is ChatAction.ConfirmPatch -> viewModel.confirmPatch(
                                action.messageIndex, 
                                action.patchIndex, 
                                action.patch
                            )
                            
                            // 2. 拒绝补丁
                            is ChatAction.RejectPatch -> viewModel.rejectPatch(
                                action.messageIndex, 
                                action.patchIndex, 
                                action.patch
                            )
                            
                            // 3. 🔥 补齐缺失的 UndoPatch 分支（编译器刚才报错就在这）
                            is ChatAction.UndoPatch -> viewModel.undoPatch(
                                action.messageIndex, 
                                action.patchIndex, 
                                action.patch
                            )
                            
                            is ChatAction.DeleteMessage -> viewModel.deleteMessage(action.index)
                            
                            is ChatAction.EditMessage -> viewModel.editAndResendMessage(
                                action.index, 
                                action.text
                            )
                        }
                    }
                )
                WorkspaceTab.SETTINGS -> SettingsPanel(
                    apiBaseUrl = uiState.apiBaseUrl,
                    apiKey = uiState.apiKey,
                    currentModel = uiState.selectedModel,
                    enableThinking = uiState.enableThinking,
                    onSaveConfig = { baseUrl, key, model -> viewModel.saveConfig(baseUrl, key, model) },
                    onSaveThinking = { enabled -> viewModel.saveThinkingEnabled(enabled) }
                )
            }
        }
    }
}
