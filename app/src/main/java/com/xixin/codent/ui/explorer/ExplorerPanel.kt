package com.xixin.codent.ui.explorer

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
import com.xixin.codent.presentation.common.MainUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExplorerPanel(
    uiState: MainUiState,
    onInitWorkspace: () -> Unit,
    onNavigateBack: () -> Unit,
    onFolderClick: (String) -> Unit, // 🔥 这里改成了 String
    onFileClick: (FileNode) -> Unit
) {
    if (uiState.directoryStack.isEmpty()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(48.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.FolderOpen,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
            Spacer(Modifier.height(24.dp))
            Text(
                "Select a workspace",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Choose the root directory of your project",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(48.dp))
            Button(
                onClick = onInitWorkspace,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = MaterialTheme.shapes.extraLarge,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Text("Open Folder", style = MaterialTheme.typography.titleMedium)
            }
        }
    } else {
        Column(modifier = Modifier.fillMaxSize()) {
            LargeTopAppBar(
                title = {
                    Text(
                        text = if (uiState.directoryStack.size > 1) uiState.directoryStack.last().substringAfterLast("/") else "Workspace",
                        style = MaterialTheme.typography.headlineMedium
                    )
                },
                navigationIcon = {
                    if (uiState.directoryStack.size > 1) {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                )
            )
            if (uiState.isSafLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            } else if (uiState.currentFiles.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Empty folder", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 80.dp) // space for nav bar
                ) {
                    items(uiState.currentFiles, key = { it.path }) { fileNode -> 
                        ListItem(
                            headlineContent = {
                                Text(
                                    text = fileNode.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            },
                            leadingContent = {
                                Icon(
                                    imageVector = if (fileNode.isDirectory) Icons.Default.Folder else Icons.AutoMirrored.Filled.InsertDriveFile,
                                    contentDescription = null,
                                    tint = if (fileNode.isDirectory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                    modifier = Modifier.size(24.dp)
                                )
                            },
                            colors = ListItemDefaults.colors(
                                containerColor = MaterialTheme.colorScheme.surface
                            ),
                            modifier = Modifier
                                .clickable {
                                    if (fileNode.isDirectory)
                                        onFolderClick(fileNode.path)
                                    else
                                        onFileClick(fileNode)
                                }
                                .padding(horizontal = 8.dp)
                        )
                        // Minimalist: No dividers between list items. The spacing and typography provide enough hierarchy.
                    }
                }
            }
        }
    }
}
