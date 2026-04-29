package com.xixin.codent

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.xixin.codent.BaseActivity
import com.xixin.codent.ui.explorer.ProjectExplorerScreen
import com.xixin.codent.ui.theme.CodentTheme
import com.xixin.codent.ui.main.MainScreen
class MainActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        setContent {
            CodentTheme {
                // Surface 会自动铺满全屏（包括状态栏底下），并应用动态背景色
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen()
                }
            }
        }
    }
}