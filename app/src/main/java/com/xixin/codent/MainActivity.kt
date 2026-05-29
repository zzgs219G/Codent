
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
import dagger.hilt.android.AndroidEntryPoint // 🔥 导入 Hilt 必须的入口注解

/**
 * 主 Activity —— 应用唯一的入口 Activity
 * 🔥 必须添加 @AndroidEntryPoint 注解，否则 Compose 内部调用 hiltViewModel() 会引发闪退
 */
@AndroidEntryPoint
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
                    }
                }
            }
        }
    }
}


