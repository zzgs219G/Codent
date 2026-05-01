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
        // 1. 开启沉浸式 Edge-to-Edge，状态栏图标颜色由 Surface 背景自动适配
        enableEdgeToEdge()

        super.onCreate(savedInstanceState)

        // 2. 请求 120Hz 高刷新率，提升界面滑动流畅度
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            window.attributes = window.attributes.also {
                it.preferredDisplayModeId = 0
                it.preferredRefreshRate = 120f
            }
        }

        // 3. 日志标记：确认 Activity 创建流程执行完毕
        android.util.Log.d("BaseActivity", "onCreate -> EdgeToEdge + 高刷 已生效")

    }
}
