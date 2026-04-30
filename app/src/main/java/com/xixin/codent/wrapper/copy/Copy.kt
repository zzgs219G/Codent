package com.xixin.codent.wrapper.copy

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast

// 这是一个扩展函数，必须在 com.xixin.box.wrapper.copy 包下
fun Context.copy(text: String) {
    if (text.isEmpty()) return
    
    val clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clipData = ClipData.newPlainText("WebSource", text)
    clipboardManager.setPrimaryClip(clipData)
    
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show()
    }
}
