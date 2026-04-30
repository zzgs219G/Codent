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

class MainActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.xixin.codent.wrapper.log.AppLog.isVisible = true
        setContent {
            CodentTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                    
                        MainScreen()
                        DebugFloatingConsole()
                           // 正确位置：在 Compose 上下文中
                    }
                }
            }
        }
    }
}