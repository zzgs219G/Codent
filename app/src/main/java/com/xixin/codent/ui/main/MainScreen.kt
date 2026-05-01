package com.xixin.codent.ui.main

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
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
import com.xixin.codent.wrapper.log.DebugFloatingConsole
import com.xixin.codent.ui.chat.ChatPanel
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
    
    val isKeyboardVisible = WindowInsets.isImeVisible

    val folderPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? -> uri?.let { viewModel.initWorkspace(it) } }

    BackHandler(enabled = uiState.directoryStack.isNotEmpty() && currentTab == WorkspaceTab.EXPLORER) {
        viewModel.navigateBack()
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        bottomBar = {
            AnimatedVisibility(
                visible = !isKeyboardVisible,
                enter = slideInVertically(animationSpec = tween(300)) { it } + fadeIn(),
                exit = slideOutVertically(animationSpec = tween(300)) { it } + fadeOut()
            ) {
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
                    // 🔥 修复点 1：这里改为传递列表 pendingPatches
                    pendingPatches = uiState.pendingPatches, 
                    onSendMessage = { viewModel.sendChatMessage(it) },
                    // 🔥 修复点 2：回调现在需要接收一个 patch 对象并传给 ViewModel
                    onConfirmPatch = { patch -> viewModel.confirmPatch(patch) }, 
                    // 🔥 修复点 3：同理，拒绝也需要知道拒绝的是哪一个
                    onRejectPatch = { patch -> viewModel.rejectPatch(patch) },
                    onDeleteMessage = { index -> viewModel.deleteMessage(index) },
                    onEditUserMessage = { index, text -> viewModel.editAndResendMessage(index, text) }
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
