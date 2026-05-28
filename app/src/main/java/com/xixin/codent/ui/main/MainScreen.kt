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
import androidx.hilt.navigation.compose.hiltViewModel
import com.xixin.codent.presentation.common.MainUiEvent
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
fun MainScreen(viewModel: MainViewModel = hiltViewModel()) { // 🔥 使用 hiltViewModel()
    val uiState by viewModel.uiState.collectAsState()
    var currentTab by remember { mutableStateOf(WorkspaceTab.EXPLORER) }
    val context = LocalContext.current

    // 🔥 收集副作用
    LaunchedEffect(Unit) {
        viewModel.uiEffect.collect { effect ->
            when (effect) {
                is MainUiEffect.ShowToast -> {
                    Toast.makeText(context, effect.message, Toast.LENGTH_SHORT).show()
                }
                MainUiEffect.NavigateToPreview -> {
                    currentTab = WorkspaceTab.PREVIEW
                }
            }
        }
    }

    // 🔥 错误提示
    if (uiState.errorMessage != null) {
        LaunchedEffect(uiState.errorMessage) {
            Toast.makeText(context, uiState.errorMessage, Toast.LENGTH_LONG).show()
            viewModel.onEvent(MainUiEvent.ClearError)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Environment.isExternalStorageManager()) {
            viewModel.onEvent(MainUiEvent.InitWorkspace("/storage/emulated/0/"))
        }
    }

    BackHandler(enabled = uiState.directoryStack.isNotEmpty() && currentTab == WorkspaceTab.EXPLORER) {
        viewModel.onEvent(MainUiEvent.NavigateBack)
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
                        if (Environment.isExternalStorageManager()) {
                            viewModel.onEvent(MainUiEvent.InitWorkspace("/storage/emulated/0/"))
                        } else {
                            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                            intent.data = Uri.parse("package:${context.packageName}")
                            permissionLauncher.launch(intent)
                        }
                    },
                    onNavigateBack = { viewModel.onEvent(MainUiEvent.NavigateBack) },
                    onFolderClick = { path -> viewModel.onEvent(MainUiEvent.NavigateIntoFolder(path)) },
                    onFileClick = { fileNode -> viewModel.onEvent(MainUiEvent.OpenFile(fileNode)) }
                )
                WorkspaceTab.PREVIEW -> EditorPanel(
                    fileName = uiState.selectedFile?.name ?: "未选中文件",
                    content = uiState.currentCodeContent
                )
                WorkspaceTab.AGENT -> ChatPanel(
                    messages = uiState.chatMessages,
                    isAgentWorking = uiState.isAgentWorking,
                    pendingPatches = emptyList(), // 🔥 这个参数已经没用了，可以删除
                    onAction = { action ->
                        when (action) {
                            is ChatAction.SendMessage -> viewModel.onEvent(MainUiEvent.SendMessage(action.text))
                            is ChatAction.ConfirmPatch -> viewModel.onEvent(MainUiEvent.ConfirmPatch(action.messageIndex, action.patchIndex, action.patch))
                            is ChatAction.RejectPatch -> viewModel.onEvent(MainUiEvent.RejectPatch(action.messageIndex, action.patchIndex, action.patch))
                            is ChatAction.UndoPatch -> viewModel.onEvent(MainUiEvent.UndoPatch(action.messageIndex, action.patchIndex, action.patch))
                            is ChatAction.DeleteMessage -> viewModel.onEvent(MainUiEvent.DeleteMessage(action.index))
                            is ChatAction.EditMessage -> viewModel.onEvent(MainUiEvent.EditAndResendMessage(action.index, action.text))
                        }
                    }
                )
                WorkspaceTab.SETTINGS -> SettingsPanel(
                    apiBaseUrl = uiState.apiBaseUrl,
                    apiKey = uiState.apiKey,
                    currentModel = uiState.selectedModel,
                    enableThinking = uiState.enableThinking,
                    onSaveConfig = { baseUrl, key, model -> viewModel.onEvent(MainUiEvent.SaveConfig(baseUrl, key, model)) },
                    onSaveThinking = { enabled -> viewModel.onEvent(MainUiEvent.SaveThinkingEnabled(enabled)) },
                    onApplyProvider = { provider -> viewModel.onEvent(MainUiEvent.ApplyProvider(provider)) }
                )
            }
        }
    }
}
