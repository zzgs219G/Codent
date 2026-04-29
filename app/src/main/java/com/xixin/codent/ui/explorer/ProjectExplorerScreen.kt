package com.xixin.codent.ui.explorer

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xixin.codent.data.model.ChatMessage
import com.xixin.codent.data.model.FileNode
import com.xixin.codent.data.repository.SafRepository
import com.xixin.codent.ui.chat.ChatPanel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectExplorerScreen() {
    val context = LocalContext.current
    val repository = remember { SafRepository(context) }
    val coroutineScope = rememberCoroutineScope()

    val directoryStack = remember { mutableStateListOf<Uri>() }
    var files by remember { mutableStateOf<List<FileNode>>(emptyList()) }
    var previewContent by remember { mutableStateOf<String?>(null) }
    var previewFileName by remember { mutableStateOf("") }

    // ====== 新增：AI 对话相关状态 ======
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    var showChatSheet by remember { mutableStateOf(false) }
    var chatMessages by remember { mutableStateOf<List<ChatMessage>>(
        listOf(ChatMessage("assistant", "你好！我是 Codent。我已经准备好操作你的项目代码了。"))
    )}
    // ===================================

    val loadDirectory: (Uri) -> Unit = { uri ->
        coroutineScope.launch {
            val result = withContext(Dispatchers.IO) { repository.listFiles(uri) }
            files = result
        }
    }

    BackHandler(enabled = directoryStack.isNotEmpty()) {
        if (directoryStack.size > 1) {
            directoryStack.removeLast()
            loadDirectory(directoryStack.last())
        } else {
            directoryStack.clear()
            files = emptyList()
        }
    }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            repository.takePersistableUriPermission(uri)
            directoryStack.clear()
            directoryStack.add(uri)
            loadDirectory(uri)
        }
    }

    // 使用 Scaffold 包裹，这样右下角就有了一个悬浮按钮
    Scaffold(
        floatingActionButton = {
            if (directoryStack.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = { showChatSheet = true },
                    icon = { Icon(Icons.Default.AutoAwesome, contentDescription = null) },
                    text = { Text("召唤 AI") },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding) // 防止被悬浮球挡住底部
                .systemBarsPadding()
                .padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            if (directoryStack.isEmpty()) {
                Button(
                    onClick = { folderPickerLauncher.launch(null) },
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(12.dp)
                ) {
                    Icon(Icons.Default.Terminal, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("初始化 Codent 项目工作区")
                }
                
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("等待授权访问 Android 项目源码", color = MaterialTheme.colorScheme.outline)
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (directoryStack.size > 1) {
                        IconButton(onClick = {
                            directoryStack.removeLast()
                            loadDirectory(directoryStack.last())
                        }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    }
                    Icon(Icons.Default.Code, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (directoryStack.size > 1) "子目录 (${files.size})" else "根目录 (${files.size})",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))

                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(files) { fileNode ->
                        FileItemRow(
                            fileNode = fileNode,
                            onFolderClick = {
                                directoryStack.add(fileNode.uri)
                                loadDirectory(fileNode.uri)
                            },
                            onFileClick = {
                                previewFileName = fileNode.name
                                coroutineScope.launch {
                                    val content = withContext(Dispatchers.IO) { repository.readFileContent(fileNode.uri) }
                                    previewContent = content
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    // ====== 新增：底部弹出的对话框 ======
    if (showChatSheet) {
        ModalBottomSheet(
            onDismissRequest = { showChatSheet = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxHeight(0.85f) // 占据屏幕 85% 高度
        ) {
            ChatPanel(
                messages = chatMessages,
                onSendMessage = { userText ->
                    // 1. 把用户发的话放到 UI 上
                    chatMessages = chatMessages + ChatMessage("user", userText)
                    
                    // 2. 放入一个带有 Loading 动画的假 AI 气泡
                    chatMessages = chatMessages + ChatMessage("assistant", "", isLoading = true)
                    
                    // 3. 模拟 AI 思考网络请求 (等我们加上 Ktor 后这里就换写真实的请求)
                    coroutineScope.launch {
                        delay(1500) // 假装思考了 1.5 秒
                        
                        // 替换掉最后一个 Loading 气泡，给出正式回答
                        chatMessages = chatMessages.dropLast(1) + ChatMessage(
                            role = "assistant",
                            content = "你要求：“$userText”。\n但我现在还没接上大脑 (Ktor 网络库)，请主人快给我写请求代码吧！"
                        )
                    }
                }
            )
        }
    }
    // ===================================

    // 代码预览弹窗保持不变
    if (previewContent != null) {
        AlertDialog(
            onDismissRequest = { previewContent = null },
            title = { Text(previewFileName) },
            text = {
                Box(modifier = Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                    Text(text = previewContent!!, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
            },
            confirmButton = { TextButton(onClick = { previewContent = null }) { Text("关闭") } }
        )
    }
}

// FileItemRow 函数保持原样不变，直接粘贴在这里即可...
@Composable
fun FileItemRow(
    fileNode: FileNode,
    onFolderClick: () -> Unit,
    onFileClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { if (fileNode.isDirectory) onFolderClick() else onFileClick() }
            .padding(vertical = 12.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val isCodeFile = fileNode.name.endsWith(".kt") || fileNode.name.endsWith(".gradle")
        Icon(
            imageVector = if (fileNode.isDirectory) Icons.Default.Folder else Icons.AutoMirrored.Filled.InsertDriveFile,
            contentDescription = null,
            tint = when {
                fileNode.isDirectory -> MaterialTheme.colorScheme.primary
                isCodeFile -> MaterialTheme.colorScheme.tertiary
                else -> MaterialTheme.colorScheme.secondary
            }
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(text = fileNode.name, style = MaterialTheme.typography.bodyLarge)
    }
}