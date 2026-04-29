package com.xixin.codent.ui.main

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xixin.codent.data.model.ChatMessage
import com.xixin.codent.data.model.FileNode
import com.xixin.codent.data.repository.SafRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class WorkspaceTab(val title: String, val icon: ImageVector) {
    EXPLORER("资源管理器", Icons.Default.Folder),
    EDITOR("代码编辑器", Icons.Default.Code),
    AGENT("AI 终端", Icons.Default.Terminal)
}

@Composable
fun MainScreen() {
    val context = LocalContext.current
    val repository = remember { SafRepository(context) }
    val coroutineScope = rememberCoroutineScope()

    var currentTab by remember { mutableStateOf(WorkspaceTab.EXPLORER) }
    val directoryStack = remember { mutableStateListOf<Uri>() }
    var files by remember { mutableStateOf<List<FileNode>>(emptyList()) }
    
    // 【修复 SAF 无响应】增加加载状态
    var isSafLoading by remember { mutableStateOf(false) }
    
    var currentCodeContent by remember { mutableStateOf("// 在“资源管理器”中选择一个文件打开，AI 就会以它作为目标开始工作。") }
    var currentFileName by remember { mutableStateOf("未选中文件") }

    var chatMessages by remember { mutableStateOf<List<ChatMessage>>(
        listOf(ChatMessage("assistant", "Codent Agent 已就绪。请先在资源管理器中打开你要修改的文件。"))
    )}

    val loadDirectory: (Uri) -> Unit = { uri ->
        coroutineScope.launch {
            isSafLoading = true // 开始转圈
            try {
                val result = withContext(Dispatchers.IO) { repository.listFiles(uri) }
                files = result
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                isSafLoading = false // 结束转圈
            }
        }
    }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            // 【修复授权崩溃】加入 try-catch 拦截异常
            try {
                val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                context.contentResolver.takePersistableUriPermission(uri, takeFlags)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            directoryStack.clear()
            directoryStack.add(uri)
            loadDirectory(uri)
        }
    }

    // 【大厂级防遮挡】监听键盘是否弹起，弹起时隐藏底部导航栏！
    val isKeyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0

    Scaffold(
        bottomBar = {
            // 只有键盘收起时，才显示底部导航栏。完美解决输入框飞天问题！
            if (!isKeyboardVisible) {
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
        },
        // 这里不要加 imePadding，交给内部的面板去处理键盘弹起
        modifier = Modifier.fillMaxSize()
    ) { innerPadding ->
        
        Box(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            when (currentTab) {
                WorkspaceTab.EXPLORER -> {
                    ExplorerPanel(
                        directoryStack = directoryStack,
                        files = files,
                        isLoading = isSafLoading,
                        onInitWorkspace = { folderPickerLauncher.launch(null) },
                        onNavigateBack = {
                            if (directoryStack.size > 1) {
                                directoryStack.removeLast()
                                loadDirectory(directoryStack.last())
                            } else {
                                directoryStack.clear()
                                files = emptyList()
                            }
                        },
                        onFolderClick = { uri ->
                            directoryStack.add(uri)
                            loadDirectory(uri)
                        },
                        onFileClick = { fileNode ->
                            currentFileName = fileNode.name
                            currentCodeContent = "正在加载代码，请稍候..."
                            currentTab = WorkspaceTab.EDITOR // 自动跳转编辑器
                            
                            coroutineScope.launch {
                                val content = withContext(Dispatchers.IO) { 
                                    repository.readFileContent(fileNode.uri) 
                                }
                                currentCodeContent = content
                            }
                        }
                    )
                }
                WorkspaceTab.EDITOR -> {
                    EditorPanel(fileName = currentFileName, content = currentCodeContent)
                }
                WorkspaceTab.AGENT -> {
                    AgentChatPanel(
                        messages = chatMessages,
                        onSendMessage = { userText ->
                            chatMessages = chatMessages + ChatMessage("user", userText)
                            chatMessages = chatMessages + ChatMessage("assistant", "", isLoading = true)
                            coroutineScope.launch {
                                delay(1200)
                                chatMessages = chatMessages.dropLast(1) + ChatMessage(
                                    role = "assistant",
                                    content = "已收到关于 [ $currentFileName ] 的任务：\n$userText\n\n(等待下一步接入 Ktor 网络引擎...)"
                                )
                            }
                        }
                    )
                }
            }
        }
    }
}

// ==========================================
// 模块 1：资源管理器
// ==========================================
@Composable
fun ExplorerPanel(
    directoryStack: List<Uri>,
    files: List<FileNode>,
    isLoading: Boolean,
    onInitWorkspace: () -> Unit,
    onNavigateBack: () -> Unit,
    onFolderClick: (Uri) -> Unit,
    onFileClick: (FileNode) -> Unit
) {
    BackHandler(enabled = directoryStack.isNotEmpty()) {
        onNavigateBack()
    }

    if (directoryStack.isEmpty()) {
        // 【修复按钮飞天】使用 Arrangement.Center 完美居中对齐
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(16.dp))
            Text("还未连接本地代码仓库", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = onInitWorkspace,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(16.dp)
            ) {
                Text("选择 Android 项目根目录", fontSize = 16.sp)
            }
        }
    } else {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            Spacer(modifier = Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (directoryStack.size > 1) {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
                Text(
                    text = if (directoryStack.size > 1) "子目录" else "项目根目录",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider()

            // 【修复无反应问题】增加 Loading 状态
            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (files.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("空文件夹", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(files) { fileNode ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { if (fileNode.isDirectory) onFolderClick(fileNode.uri) else onFileClick(fileNode) }
                                .padding(vertical = 14.dp, horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (fileNode.isDirectory) Icons.Default.Folder else Icons.AutoMirrored.Filled.InsertDriveFile,
                                contentDescription = null,
                                tint = if (fileNode.isDirectory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Text(text = fileNode.name, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
        }
    }
}

// ==========================================
// 模块 2：代码编辑器
// ==========================================
@Composable
fun EditorPanel(fileName: String, content: String) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Code, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
            Spacer(Modifier.width(8.dp))
            Text(text = fileName, style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(8.dp))
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(8.dp)
        ) {
            Box(modifier = Modifier.padding(12.dp).verticalScroll(rememberScrollState())) {
                Text(
                    text = content,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ==========================================
// 模块 3：AI 终端 (键盘处理神级优化)
// ==========================================
@Composable
fun AgentChatPanel(messages: List<ChatMessage>, onSendMessage: (String) -> Unit) {
    var inputText by remember { mutableStateOf("") }
    
    // 使用 imePadding 确保只把输入框推上来，配合底栏隐藏，体验极佳
    Column(modifier = Modifier.fillMaxSize().imePadding()) {
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
            contentPadding = PaddingValues(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(messages) { msg ->
                val isUser = msg.role == "user"
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
                ) {
                    Box(
                        modifier = Modifier
                            .widthIn(max = 280.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (isUser) MaterialTheme.colorScheme.primary 
                                else MaterialTheme.colorScheme.surfaceVariant
                            )
                            .padding(12.dp)
                    ) {
                        if (msg.isLoading) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Text(
                                text = msg.content,
                                color = if (isUser) MaterialTheme.colorScheme.onPrimary 
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        Surface(tonalElevation = 8.dp) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("让 Codent 修改代码...") },
                    shape = RoundedCornerShape(24.dp),
                    maxLines = 4
                )
                Spacer(modifier = Modifier.width(8.dp))
                FilledIconButton(
                    onClick = {
                        if (inputText.isNotBlank()) {
                            onSendMessage(inputText)
                            inputText = ""
                        }
                    },
                    modifier = Modifier.size(50.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "发送")
                }
            }
        }
    }
}