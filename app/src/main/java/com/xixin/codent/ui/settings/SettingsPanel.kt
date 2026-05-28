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

    // 当外部传入值变化时同步（比如 onApplyProvider 触发了 ViewModel 更新）
    LaunchedEffect(apiBaseUrl)   { inputBaseUrl = apiBaseUrl }
    LaunchedEffect(currentModel) { inputModel   = currentModel }

    Column(modifier = Modifier.fillMaxSize()) {

        LargeTopAppBar(
            title = {
                Text(
                    "Settings",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            colors = TopAppBarDefaults.largeTopAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp)
                .padding(bottom = 80.dp), // Space for nav bar
            verticalArrangement = Arrangement.spacedBy(32.dp) // Minimalist: Generous spacing between sections
        ) {

            // ── Section 1: AI Provider ───────────────────────────
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    "AI Provider",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )

                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(vertical = 8.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showProviders = !showProviders }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text("Quick Switch", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                                Text("Select a preset provider", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Icon(
                                Icons.Default.ExpandMore,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        AnimatedVisibility(
                            visible = showProviders,
                            enter   = expandVertically() + fadeIn(),
                            exit    = shrinkVertically() + fadeOut()
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
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
            }

            // ── Section 2: Manual Configuration ─────────────────────────────────
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    "Manual Configuration",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )

                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        OutlinedTextField(
                            value         = inputBaseUrl,
                            onValueChange = { inputBaseUrl = it },
                            label         = { Text("Base URL") },
                            leadingIcon   = { Icon(Icons.Default.Link, contentDescription = null) },
                            placeholder   = { Text("https://api.deepseek.com/v1/chat/completions") },
                            modifier      = Modifier.fillMaxWidth(),
                            singleLine    = true,
                            shape = RoundedCornerShape(12.dp)
                        )

                        OutlinedTextField(
                            value         = inputKey,
                            onValueChange = { inputKey = it },
                            label         = { Text("API Key") },
                            leadingIcon   = { Icon(Icons.Default.Key, contentDescription = null) },
                            trailingIcon  = {
                                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                    Icon(
                                        imageVector = if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = if (passwordVisible) "Hide" else "Show"
                                    )
                                }
                            },
                            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions      = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next),
                            modifier             = Modifier.fillMaxWidth(),
                            singleLine           = true,
                            shape = RoundedCornerShape(12.dp)
                        )

                        OutlinedTextField(
                            value         = inputModel,
                            onValueChange = { inputModel = it },
                            label         = { Text("Model Name") },
                            placeholder   = { Text("deepseek-chat") },
                            modifier      = Modifier.fillMaxWidth(),
                            singleLine    = true,
                            shape = RoundedCornerShape(12.dp)
                        )

                        // 深度思考开关
                        Row(
                            modifier              = Modifier.fillMaxWidth().padding(top = 8.dp),
                            verticalAlignment     = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Thinking Mode", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                                Text(
                                    "Requires a reasoning model",
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

                        Button(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onSaveConfig(inputBaseUrl, inputKey, inputModel)
                            },
                            modifier = Modifier.fillMaxWidth().padding(top = 16.dp).height(50.dp),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Icon(Icons.Default.Save, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Save Configuration")
                        }
                    }
                }
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
