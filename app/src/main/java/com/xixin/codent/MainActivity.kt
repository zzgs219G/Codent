package com.xixin.codent

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.xixin.codent.wrapper.log.DebugFloatingConsole  // 导入
import com.xixin.codent.ui.main.MainScreen
import com.xixin.codent.ui.theme.CodentTheme

/**
 * 主 Activity —— 应用唯一的入口 Activity
 * - 继承自 BaseActivity，已自动处理沉浸式状态栏与高刷新率
 * - 使用 Jetpack Compose 加载 MainScreen 作为根 UI
 * - 可开启 DebugFloatingConsole 浮窗日志以辅助调试
 *
 * //老铁666
 */
class MainActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 【调试开关】开启后可在屏幕上看到浮窗日志，方便开发调试
        com.xixin.codent.wrapper.log.AppLog.isVisible = true

        setContent {
            CodentTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        MainScreen()
                        // 调试浮窗，仅在 isVisible=true 时渲染，不影响生产包
                        DebugFloatingConsole()
                    }
                }
            }
        }
    }
}