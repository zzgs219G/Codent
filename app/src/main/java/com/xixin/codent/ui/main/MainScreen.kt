// [修改] 移除了冗余的 WindowInsets 和 imePadding，让 Scaffold 原生接管键盘
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
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xixin.codent.ui.chat.AgentChatPanel
import com.xixin.codent.ui.editor.EditorPanel
import com.xixin.codent.ui.explorer.ExplorerPanel

enum class WorkspaceTab(val title: String, val icon: ImageVector) {
    EXPLORER("资源", Icons.Default.Folder),
    EDITOR("代码", Icons.Default.Code),
    AGENT("终端", Icons.Default.Terminal)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var currentTab by remember { mutableStateOf(WorkspaceTab.EXPLORER) }

    val isKeyboardVisible = WindowInsets.isImeVisible

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let { viewModel.initWorkspace(it) }
    }

    BackHandler(enabled = uiState.directoryStack.isNotEmpty() && currentTab == WorkspaceTab.EXPLORER) {
        viewModel.navigateBack()
    }

    // 关键修复：不要强制指定 contentWindowInsets，让 Scaffold 使用默认的 safeDrawing（包含键盘和导航栏）
    Scaffold(
        bottomBar = {
            AnimatedVisibility(
                visible = !isKeyboardVisible,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
            ) {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ) {
                    WorkspaceTab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = currentTab == tab,
                            onClick = { currentTab = tab },
                            icon = { Icon(tab.icon, contentDescription = tab.title) },
                            label = { Text(tab.title) },
                            colors = NavigationBarItemDefaults.colors(
                                indicatorColor = MaterialTheme.colorScheme.secondaryContainer
                            )
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        AnimatedContent(
            targetState = currentTab,
            label = "Tab Transition",
            modifier = Modifier
                .fillMaxSize()
                // 关键修复：只需要 innerPadding，绝不能再加 imePadding()，否则高度会翻倍！
                .padding(innerPadding)
        ) { tab ->
            when (tab) {
                WorkspaceTab.EXPLORER -> {
                    ExplorerPanel(
                        uiState = uiState,
                        onInitWorkspace = { folderPickerLauncher.launch(null) },
                        onNavigateBack = { viewModel.navigateBack() },
                        onFolderClick = { uri -> viewModel.navigateIntoFolder(uri) },
                        onFileClick = { fileNode ->
                            viewModel.openFile(fileNode) {
                                currentTab = WorkspaceTab.EDITOR 
                            }
                        }
                    )
                }
                WorkspaceTab.EDITOR -> {
                    EditorPanel(
                        fileName = uiState.selectedFile?.name ?: "未选中文件",
                        content = uiState.currentCodeContent
                    )
                }
                WorkspaceTab.AGENT -> {
                    AgentChatPanel(
                        messages = uiState.chatMessages,
                        onSendMessage = { text -> viewModel.sendChatMessage(text) }
                    )
                }
            }
        }
    }
}