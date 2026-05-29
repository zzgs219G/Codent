// [文件路径: app/src/main/java/com/xixin/codent/ui/main/MainScreen.kt]
package com.xixin.codent.ui.main

import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.xixin.codent.presentation.common.MainUiEvent
import com.xixin.codent.presentation.common.MainUiEffect
import com.xixin.codent.data.model.FileNode
import com.xixin.codent.ui.chat.ChatPanel
import com.xixin.codent.ui.chat.ChatAction
import com.xixin.codent.ui.editor.EditorPanel
import com.xixin.codent.ui.explorer.ExplorerPanel
import com.xixin.codent.ui.settings.SettingsPanel
import com.xixin.codent.presentation.main.MainViewModel
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

enum class WorkspaceTab(val title: String, val icon: ImageVector, val desc: String) {
    AGENT("AI 智能助手", Icons.Default.Terminal, "代码对话与指令下发"),
    EXPLORER("项目资源", Icons.Default.Folder, "浏览与装载源码文件"),
    PREVIEW("编辑器快照", Icons.Default.Code, "当前被读取的代码快照"),
    SETTINGS("系统设置", Icons.Default.Settings, "配置接口端点与 API Key")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MainScreen(viewModel: MainViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    var currentTab by remember { mutableStateOf(WorkspaceTab.AGENT) }
    val context = LocalContext.current
    
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var lastBackPressTime by remember { mutableLongStateOf(0L) }

    LaunchedEffect(Unit) {
        viewModel.uiEffect.collect { effect ->
            when (effect) {
                is MainUiEffect.ShowToast -> Toast.makeText(context, effect.message, Toast.LENGTH_SHORT).show()
                MainUiEffect.NavigateToPreview -> currentTab = WorkspaceTab.PREVIEW
            }
        }
    }

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

    // 🔥 顶级防误触路由守卫
    BackHandler(enabled = true) {
        when {
            drawerState.isOpen -> scope.launch { drawerState.close() }
            currentTab == WorkspaceTab.EXPLORER && uiState.directoryStack.size > 1 -> viewModel.onEvent(MainUiEvent.NavigateBack)
            currentTab != WorkspaceTab.AGENT -> currentTab = WorkspaceTab.AGENT
            else -> {
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastBackPressTime < 2000) {
                    (context as? android.app.Activity)?.finish()
                } else {
                    Toast.makeText(context, "再按一次退出 Codent", Toast.LENGTH_SHORT).show()
                    lastBackPressTime = currentTime
                }
            }
        }
    }

    // 🔥 融合你的流体侧边栏底层架构
    FluidNavigationDrawer(
        drawerState = drawerState,
        currentTab = currentTab,
        onTabSelect = { tab ->
            currentTab = tab
            scope.launch { drawerState.close() }
        }
    ) {
        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0)
        ) { innerPadding ->
            AnimatedContent(
                targetState = currentTab,
                label = "Tab Transition",
                transitionSpec = {
                    slideInHorizontally { width -> width / 3 } + fadeIn() togetherWith
                            slideOutHorizontally { width -> -width / 3 } + fadeOut()
                },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .consumeWindowInsets(innerPadding)
            ) { tab ->
                when (tab) {
                    WorkspaceTab.EXPLORER -> ExplorerPanel(
                        uiState = uiState,
                        onOpenDrawer = { scope.launch { drawerState.open() } },
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
                        content = uiState.currentCodeContent,
                        onOpenDrawer = { scope.launch { drawerState.open() } }
                    )
                    WorkspaceTab.AGENT -> ChatPanel(
                        messages = uiState.chatMessages,
                        isAgentWorking = uiState.isAgentWorking,
                        onOpenDrawer = { scope.launch { drawerState.open() } },
                        onNavigateTab = { target -> currentTab = target },
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
                        onOpenDrawer = { scope.launch { drawerState.open() } },
                        onSaveConfig = { baseUrl, key, model -> viewModel.onEvent(MainUiEvent.SaveConfig(baseUrl, key, model)) },
                        onSaveThinking = { enabled -> viewModel.onEvent(MainUiEvent.SaveThinkingEnabled(enabled)) },
                        onApplyProvider = { provider -> viewModel.onEvent(MainUiEvent.ApplyProvider(provider)) }
                    )
                }
            }
        }
    }
}

/**
 * 🔥 基于你的核心手势重构的纯净版流体侧边栏
 */
@Composable
fun FluidNavigationDrawer(
    drawerState: DrawerState,
    currentTab: WorkspaceTab,
    onTabSelect: (WorkspaceTab) -> Unit,
    content: @Composable () -> Unit
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val drawerWidthDp = LocalConfiguration.current.screenWidthDp.dp * 0.82f
    val drawerWidthPx = with(density) { drawerWidthDp.toPx() }

    // 绝对真相来源：offsetX
    var offsetX by remember {
        mutableFloatStateOf(if (drawerState.currentValue == DrawerValue.Open) 0f else -drawerWidthPx)
    }

    val progress by remember { derivedStateOf { 1f + (offsetX / drawerWidthPx) } }
    val isVisible by remember { derivedStateOf { progress > 0.001f } }

    // 监听外部 DrawerState
    LaunchedEffect(drawerState.targetValue) {
        val targetOffsetX = if (drawerState.targetValue == DrawerValue.Open) 0f else -drawerWidthPx
        animate(
            initialValue = offsetX,
            targetValue = targetOffsetX,
            animationSpec = spring(dampingRatio = 0.8f, stiffness = 380f)
        ) { value, _ -> offsetX = value }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // 第一层：底层主页面
        content()

        // 第二层：丝滑渐变蒙层
        if (isVisible) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = (progress * 0.32f).coerceIn(0f, 0.32f)))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { scope.launch { drawerState.close() } }
            )
        }

        // 第三层：物理跟手抽屉
        if (isVisible) {
            Box(
                modifier = Modifier
                    .width(drawerWidthDp)
                    .fillMaxHeight()
                    .offset { IntOffset(x = offsetX.roundToInt(), y = 0) }
                    .clip(RoundedCornerShape(topEnd = 28.dp, bottomEnd = 28.dp))
                    .pointerInput(drawerWidthPx) {
                        val velocityTracker = VelocityTracker()
                        detectHorizontalDragGestures(
                            onDragStart = { velocityTracker.resetTracking() },
                            onHorizontalDrag = { change, dragAmount ->
                                change.consume()
                                velocityTracker.addPointerInputChange(change)
                                offsetX = (offsetX + dragAmount).coerceIn(-drawerWidthPx, 0f)
                            },
                            onDragEnd = {
                                val velocityX = velocityTracker.calculateVelocity().x
                                val shouldClose = when {
                                    velocityX < -300f -> true
                                    velocityX > 300f  -> false
                                    else -> offsetX < -drawerWidthPx / 2f
                                }
                                scope.launch {
                                    if (shouldClose) {
                                        animate(
                                            initialValue = offsetX,
                                            targetValue = -drawerWidthPx,
                                            animationSpec = spring(dampingRatio = 0.8f, stiffness = 380f)
                                        ) { value, _ -> offsetX = value }
                                        drawerState.close()
                                    } else {
                                        animate(
                                            initialValue = offsetX,
                                            targetValue = 0f,
                                            animationSpec = spring(dampingRatio = 0.8f, stiffness = 380f)
                                        ) { value, _ -> offsetX = value }
                                        drawerState.open()
                                    }
                                }
                            }
                        )
                    }
            ) {
                ModalDrawerSheet(
                    drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxSize()
                ) {
                    DrawerHeader()
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    WorkspaceTab.entries.forEach { tab ->
                        NavigationDrawerItem(
                            icon = { Icon(tab.icon, contentDescription = tab.title) },
                            label = {
                                Column {
                                    Text(tab.title, fontWeight = FontWeight.Bold)
                                    Text(
                                        text = tab.desc,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                    )
                                }
                            },
                            selected = currentTab == tab,
                            onClick = { onTabSelect(tab) },
                            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                        )
                    }
                    
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = "Codent AI Agent • v1.0.0",
                        modifier = Modifier
                            .padding(16.dp)
                            .align(Alignment.CenterHorizontally),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        }
    }
}

@Composable
fun DrawerHeader() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                        MaterialTheme.colorScheme.surfaceContainerLow
                    )
                )
            )
            .padding(all = 24.dp)
            .padding(top = 28.dp) // 预留物理状态栏避让空间
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .background(MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Terminal,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(28.dp)
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Codent AI",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = "流体动力学交互引擎",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary
        )
    }
}
