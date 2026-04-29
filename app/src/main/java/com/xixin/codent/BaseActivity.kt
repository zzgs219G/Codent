package com.xixin.codent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge

/**
 * 全局基础 Activity
 * 统一管理沉浸式状态栏、高刷新率等 Window 级别属性
 */
abstract class BaseActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // 1. 开启沉浸式边缘到边缘 (Edge-to-Edge)
        // 官方最新 API，会自动根据下面的 Surface 颜色切换状态栏图标是黑色还是白色！
        enableEdgeToEdge()
        
        super.onCreate(savedInstanceState)

        // 2. 注入高帧率基因 (120Hz)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            window.attributes = window.attributes.also {
                it.preferredDisplayModeId = 0 // 自动选择最高刷新率
                it.preferredRefreshRate = 120f
            }
        }
    }
}