// [文件路径: app/src/main/java/com/xixin/codent/core/agent/CodePatcher.kt]
package com.xixin.codent.core.agent

object CodePatcher {

    /**
     * 核心逻辑：执行替换，并处理缩进/空格模糊匹配
     */
    fun replaceFirstExact(original: String, searchString: String, replaceString: String): String? {
        if (searchString.isBlank()) return null
        val normOriginal = original.replace("\r\n", "\n")
        val normSearch = searchString.replace("\r\n", "\n")
        val exactIndex = normOriginal.indexOf(normSearch)
        if (exactIndex != -1) {
            return normOriginal.replaceRange(exactIndex, exactIndex + normSearch.length, replaceString.replace("\r\n", "\n"))
        }

        val searchLines = normSearch.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val originalLines = normOriginal.lines()
        for (i in 0..originalLines.size - searchLines.size) {
            var searchLineIdx = 0
            var origLineIdx = i
            var matchStartIndex = -1
            while (searchLineIdx < searchLines.size && origLineIdx < originalLines.size) {
                val oLine = originalLines[origLineIdx].trim()
                if (oLine.isEmpty()) { origLineIdx++; continue }
                if (oLine == searchLines[searchLineIdx]) {
                    if (searchLineIdx == 0) matchStartIndex = origLineIdx
                    searchLineIdx++; origLineIdx++
                } else break
            }
            if (searchLineIdx == searchLines.size) {
                val startCharIdx = originalLines.take(matchStartIndex).sumOf { it.length + 1 }
                val endCharIdx = originalLines.take(origLineIdx).sumOf { it.length + 1 } - 1
                return normOriginal.replaceRange(startCharIdx, endCharIdx, replaceString.replace("\r\n", "\n"))
            }
        }
        return null
    }

    /**
     * 🔥 核心升级：生成带有“上下文”的真实 Git Style Diff
     */
    fun buildPatchPreview(fileName: String, original: String, proposed: String, searchString: String, replaceString: String): String {
        val originalLines = original.replace("\r\n", "\n").lines()
        val normSearch = searchString.replace("\r\n", "\n")
        
        // 1. 寻找匹配位置，为了抓取上下文
        val matchStartLine = findMatchStartLine(originalLines, normSearch)
        val matchEndLine = if (matchStartLine != -1) matchStartLine + normSearch.lines().size else -1

        return buildString {
            appendLine("文件: $fileName")
            appendLine("----- 🔍 Git Style Diff (含上下文) -----")
            
            if (matchStartLine != -1) {
                // 👆 抓取上文 3 行 (白色)
                val beforeContext = originalLines.subList(maxOf(0, matchStartLine - 3), matchStartLine)
                beforeContext.forEach { appendLine("  $it") }
                
                // 📝 核心差异计算 (红绿)
                val diffLines = computeInlineDiff(searchString.lines(), replaceString.lines())
                diffLines.forEach { appendLine(it) }

                // 👇 抓取下文 3 行 (白色)
                val afterContext = originalLines.subList(matchEndLine, minOf(originalLines.size, matchEndLine + 3))
                afterContext.forEach { appendLine("  $it") }
            } else {
                // 如果找不到位置（比如 AI 幻觉），退回到普通对比
                computeInlineDiff(searchString.lines(), replaceString.lines()).forEach { appendLine(it) }
            }
        }
    }

    /**
     * 辅助方法：在原文中定位补丁开始的行号
     */
    private fun findMatchStartLine(originalLines: List<String>, searchString: String): Int {
        val searchLines = searchString.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (searchLines.isEmpty()) return -1
        
        for (i in 0..originalLines.size - searchLines.size) {
            var sIdx = 0
            var oIdx = i
            while (sIdx < searchLines.size && oIdx < originalLines.size) {
                if (originalLines[oIdx].trim().isEmpty()) { oIdx++; continue }
                if (originalLines[oIdx].trim() == searchLines[sIdx]) { sIdx++; oIdx++ } else break
            }
            if (sIdx == searchLines.size) return i
        }
        return -1
    }

    /**
     * 🧠 LCS 差异算法 (保持不变)
     */
    private fun computeInlineDiff(oldLines: List<String>, newLines: List<String>): List<String> {
        val dp = Array(oldLines.size + 1) { IntArray(newLines.size + 1) }
        for (i in 1..oldLines.size) {
            for (j in 1..newLines.size) {
                if (oldLines[i - 1] == newLines[j - 1]) dp[i][j] = dp[i - 1][j - 1] + 1
                else dp[i][j] = maxOf(dp[i - 1][j], dp[i][j - 1])
            }
        }
        var i = oldLines.size
        var j = newLines.size
        val result = mutableListOf<String>()
        while (i > 0 || j > 0) {
            if (i > 0 && j > 0 && oldLines[i - 1] == newLines[j - 1]) {
                result.add("  " + oldLines[i - 1]); i--; j--
            } else if (j > 0 && (i == 0 || dp[i][j - 1] >= dp[i - 1][j])) {
                result.add("+ " + newLines[j - 1]); j--
            } else if (i > 0 && (j == 0 || dp[i][j - 1] < dp[i - 1][j])) {
                result.add("- " + oldLines[i - 1]); i--
            }
        }
        return result.reversed()
    }
}
