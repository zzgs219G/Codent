package com.xixin.codent.ui.main

import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
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
import androidx.compose.ui.platform.LocalContext
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
    val context = LocalContext.current

    // 🔥 全新的超级权限请求发射器
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Environment.isExternalStorageManager()) {
            // 授权成功！直接写死加载 Android 默认存储根目录
            viewModel.initWorkspace("/storage/emulated/0/")
        }
    }

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
                    onInitWorkspace = { 
                        // 🔥 修复点：用全新的权限判断逻辑，替换掉报错的 folderPickerLauncher
                        if (Environment.isExternalStorageManager()) {
                            viewModel.initWorkspace("/storage/emulated/0/") 
                        } else {
                            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                            intent.data = Uri.parse("package:${context.packageName}")
                            permissionLauncher.launch(intent)
                        }
                    },
                    onNavigateBack = { viewModel.navigateBack() },
                    onFolderClick = { path -> viewModel.navigateIntoFolder(path) },
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
                        when (action) {
                            is ChatAction.SendMessage -> viewModel.sendChatMessage(action.text)
                            is ChatAction.ConfirmPatch -> viewModel.confirmPatch(action.messageIndex, action.patchIndex, action.patch)
                            is ChatAction.RejectPatch -> viewModel.rejectPatch(action.messageIndex, action.patchIndex, action.patch)
                            is ChatAction.UndoPatch -> viewModel.undoPatch(action.messageIndex, action.patchIndex, action.patch)
                            is ChatAction.DeleteMessage -> viewModel.deleteMessage(action.index)
                            is ChatAction.EditMessage -> viewModel.editAndResendMessage(action.index, action.text)
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
