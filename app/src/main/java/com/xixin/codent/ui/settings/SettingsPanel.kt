// [文件路径: app/src/main/java/com/xixin/codent/ui/settings/SettingsPanel.kt]
package com.xixin.codent.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.xixin.codent.data.repository.ApiProvider
import com.xixin.codent.data.repository.PRESET_PROVIDERS

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsPanel(
    apiBaseUrl      : String,
    apiKey          : String,
    currentModel    : String,
    enableThinking  : Boolean,
    onOpenDrawer    : () -> Unit, // 🔥 引入 Drawer 触发回调
    onSaveConfig    : (String, String, String) -> Unit,
    onSaveThinking  : (Boolean) -> Unit,
    onApplyProvider : (ApiProvider) -> Unit = {}
) {
    var inputBaseUrl     by remember { mutableStateOf(apiBaseUrl) }
    var inputKey         by remember { mutableStateOf(apiKey) }
    var inputModel       by remember { mutableStateOf(currentModel) }
    var thinkingEnabled  by remember { mutableStateOf(enableThinking) }
    var passwordVisible  by remember { mutableStateOf(false) }
    var showProviders    by remember { mutableStateOf(false) }
    val haptic           = LocalHapticFeedback.current

    LaunchedEffect(apiBaseUrl)   { inputBaseUrl = apiBaseUrl }
    LaunchedEffect(currentModel) { inputModel   = currentModel }

    Column(modifier = Modifier.fillMaxSize()) {
        // 🔥 将普通 Top Bar 升级并配置 Drawer 的 Hamburger 按钮
        CenterAlignedTopAppBar(
            title = { Text("Codent 核心配置", fontWeight = FontWeight.Bold) },
            navigationIcon = {
                IconButton(onClick = onOpenDrawer) {
                    Icon(Icons.Default.Menu, contentDescription = "主菜单")
                }
            },
            colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            )
        )
        
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // 一键切换服务商
            OutlinedCard(
                modifier = Modifier.fillMaxWidth(),
                colors   = CardDefaults.outlinedCardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { showProviders = !showProviders }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("🚀 快速切换 AI 服务商", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text("DeepSeek 503 时一键备用", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(
                            Icons.Default.ExpandMore,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    AnimatedVisibility(
                        visible = showProviders,
                        enter   = expandVertically() + fadeIn(),
                        exit    = shrinkVertically() + fadeOut()
                    ) {
                        Column(
                            modifier = Modifier.padding(top = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            PRESET_PROVIDERS.forEach { provider ->
                                val isSelected = inputBaseUrl == provider.baseUrl && inputModel == provider.defaultModel
                                ProviderItem(
                                    provider   = provider,
                                    isSelected = isSelected,
                                    onClick    = {
                                        if (provider.baseUrl.isNotBlank()) inputBaseUrl = provider.baseUrl
                                        if (provider.defaultModel.isNotBlank()) inputModel = provider.defaultModel
                                        onApplyProvider(provider)
                                        showProviders = false
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    }
                                )
                            }
                        }
                    }
                }
            }
            
            // 手动配置
            OutlinedCard(
                modifier = Modifier.fillMaxWidth(),
                colors   = CardDefaults.outlinedCardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("⚙️ 手动配置 (兼容所有 OpenAI 格式接口)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedTextField(
                        value         = inputBaseUrl,
                        onValueChange = { inputBaseUrl = it },
                        label         = { Text("Base URL") },
                        leadingIcon   = { Icon(Icons.Default.Link, contentDescription = null) },
                        placeholder   = { Text("https://api.deepseek.com/v1/chat/completions") },
                        modifier      = Modifier.fillMaxWidth(),
                        singleLine    = true
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value         = inputKey,
                        onValueChange = { inputKey = it },
                        label         = { Text("API Key") },
                        leadingIcon   = { Icon(Icons.Default.Key, contentDescription = null) },
                        trailingIcon  = {
                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                Icon(
                                    imageVector = if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = if (passwordVisible) "隐藏" else "显示"
                                )
                            }
                        },
                        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions      = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next),
                        modifier             = Modifier.fillMaxWidth(),
                        singleLine           = true
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value         = inputModel,
                        onValueChange = { inputModel = it },
                        label         = { Text("模型名称") },
                        placeholder   = { Text("deepseek-chat") },
                        modifier      = Modifier.fillMaxWidth(),
                        singleLine    = true
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // 深度思考开关
                    Row(
                        modifier              = Modifier.fillMaxWidth(),
                        verticalAlignment     = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("🧠 深度思考模式", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "仅 deepseek-reasoner 等模型支持，其他模型请关闭",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Switch(
                            checked         = thinkingEnabled,
                            onCheckedChange = {
                                thinkingEnabled = it
                                onSaveThinking(it)
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            }
                        )
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onSaveConfig(inputBaseUrl, inputKey, inputModel)
                        },
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Icon(Icons.Default.Save, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("保存配置")
                    }
                }
            }
            
            // 提示卡片
            Surface(
                color  = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f),
                shape  = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "💡 遇到 503 错误？点「快速切换」改用 deepseek-chat 或 Groq，立刻恢复使用。",
                    style    = MaterialTheme.typography.bodySmall,
                    color    = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }
    }
}

@Composable
private fun ProviderItem(
    provider   : ApiProvider,
    isSelected : Boolean,
    onClick    : () -> Unit
) {
    val borderColor = if (isSelected) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.outlineVariant
    val bgColor     = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
    else MaterialTheme.colorScheme.surface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(bgColor)
            .border(width = if (isSelected) 1.5.dp else 0.5.dp, color = borderColor, shape = RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text  = provider.displayName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            )
            Text(
                text  = provider.hint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (isSelected) {
            Icon(
                Icons.Default.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}