package com.xixin.codent.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val MinimalistLightColorScheme = lightColorScheme(
    primary = MinimalistLightPrimary,
    onPrimary = MinimalistLightOnPrimary,
    background = MinimalistLightBackground,
    surface = MinimalistLightSurface,
    surfaceVariant = MinimalistLightSurfaceVariant,
    onSurface = MinimalistLightOnSurface,
    onSurfaceVariant = MinimalistLightOnSurfaceVariant
)

private val MinimalistDarkColorScheme = darkColorScheme(
    primary = MinimalistDarkPrimary,
    onPrimary = MinimalistDarkOnPrimary,
    background = MinimalistDarkBackground,
    surface = MinimalistDarkSurface,
    surfaceVariant = MinimalistDarkSurfaceVariant,
    onSurface = MinimalistDarkOnSurface,
    onSurfaceVariant = MinimalistDarkOnSurfaceVariant
)

/**
 * 全局 Material You 动态主题 + 极简设计
 * 根据系统深浅色模式，自动从用户的手机壁纸中提取颜色。
 * 在不支持动态取色的设备上，使用极简黑白灰配色。
 */
@Composable
fun CodentTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> MinimalistDarkColorScheme
        else -> MinimalistLightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
