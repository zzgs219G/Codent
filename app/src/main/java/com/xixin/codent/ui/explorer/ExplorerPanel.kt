// [修改] 修复了 TopAppBar 的颜色强制覆盖导致的“双拼色”断层
package com.xixin.codent.ui.explorer

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xixin.codent.data.model.FileNode
import com.xixin.codent.data.model.WorkspaceState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExplorerPanel(
    uiState: WorkspaceState,
    onInitWorkspace: () -> Unit,
    onNavigateBack: () -> Unit,
    onFolderClick: (Uri) -> Unit,
    onFileClick: (FileNode) -> Unit
) {
    if (uiState.directoryStack.isEmpty()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(Icons.Default.FolderOpen, null, modifier = Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(16.dp))
            Text("尚未挂载 Android 项目空间", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(32.dp))
            FilledTonalButton(
                onClick = onInitWorkspace,
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) {
                Text("选择本地项目根目录", fontSize = 16.sp)
            }
        }
    } else {
        Column(modifier = Modifier.fillMaxSize()) {
            CenterAlignedTopAppBar(
                title = { 
                    Text(
                        text = if (uiState.directoryStack.size > 1) "项目子模块" else "项目根目录",
                        style = MaterialTheme.typography.titleMedium
                    ) 
                },
                navigationIcon = {
                    if (uiState.directoryStack.size > 1) {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    }
                }
                // 关键修复：去掉了 colors 属性，让 TopAppBar 和状态栏一起使用透明/背景色，融为一体
            )

            if (uiState.isSafLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (uiState.currentFiles.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("该目录下没有文件", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(uiState.currentFiles, key = { it.uri.toString() }) { fileNode ->
                        ListItem(
                            headlineContent = { Text(fileNode.name) },
                            leadingContent = {
                                Icon(
                                    imageVector = if (fileNode.isDirectory) Icons.Default.Folder else Icons.AutoMirrored.Filled.InsertDriveFile,
                                    contentDescription = null,
                                    tint = if (fileNode.isDirectory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                                )
                            },
                            modifier = Modifier.clickable {
                                if (fileNode.isDirectory) onFolderClick(fileNode.uri)
                                else onFileClick(fileNode)
                            }
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, thickness = 0.5.dp)
                    }
                }
            }
        }
    }
}