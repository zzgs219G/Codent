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
import com.xixin.codent.ui.chat.ChatPanel
import com.xixin.codent.ui.editor.EditorPanel
import com.xixin.codent.ui.explorer.ExplorerPanel
import com.xixin.codent.ui.settings.SettingsPanel

enum class WorkspaceTab(val title: String, val icon: ImageVector) {
    EXPLORER("资源", Icons.Default.Folder),
    EDITOR("代码", Icons.Default.Code),
    AGENT("终端", Icons.Default.Terminal),
    SETTINGS("设置", Icons.Default.Settings)
}

// 修复点：添加了 ExperimentalLayoutApi::class 授权使用最新的键盘检测 API
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
                    onFileClick = { fileNode -> viewModel.openFile(fileNode) { currentTab = WorkspaceTab.EDITOR } }
                )
                WorkspaceTab.EDITOR -> EditorPanel(
                    fileName = uiState.selectedFile?.name ?: "未选中文件",
                    content = uiState.currentCodeContent
                )
                WorkspaceTab.AGENT -> ChatPanel(
                    messages = uiState.chatMessages,
                    onSendMessage = { viewModel.sendChatMessage(it) }
                )
                WorkspaceTab.SETTINGS -> SettingsPanel(
                    apiKey = uiState.apiKey,
                    onSaveApiKey = { viewModel.saveApiKey(it) }
                )
            }
        }
    }
}