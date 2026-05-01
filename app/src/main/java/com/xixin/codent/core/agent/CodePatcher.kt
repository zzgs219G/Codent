package com.xixin.codent.core.agent

object CodePatcher {

    /**
     * 防御性编程：消除 \r\n 和 \n 的鸿沟，防止大模型幻觉导致的匹配失败
     */
    fun replaceFirstExact(original: String, searchString: String, replaceString: String): String? {
        if (searchString.isBlank()) return null
        
        val normalizedOriginal = original.replace("\r\n", "\n")
        val normalizedSearch = searchString.replace("\r\n", "\n")
        
        val regex = Regex(Regex.escape(normalizedSearch))
        val result = regex.find(normalizedOriginal) ?: return null
        
        return normalizedOriginal.replaceRange(result.range, replaceString.replace("\r\n", "\n"))
    }

    fun buildPatchPreview(fileName: String, original: String, proposed: String, searchString: String, replaceString: String): String {
        return buildString {
            appendLine("文件: $fileName")
            appendLine("----- 建议内容预览 -----")
            appendLine(proposed.take(1200))
        }
    }
}
