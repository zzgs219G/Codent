package com.xixin.codent.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CodePatcherTest {

    @Test
    fun testReplaceFirstExact_ExactMatch() {
        val original = "fun hello() {\n    println(\"Hello\")\n}"
        val search = "println(\"Hello\")"
        val replace = "println(\"Hi\")"
        val expected = "fun hello() {\n    println(\"Hi\")\n}"
        assertEquals(expected, CodePatcher.replaceFirstExact(original, search, replace))
    }

    @Test
    fun testReplaceFirstExact_MultiLineExactMatch() {
        val original = "fun hello() {\n    println(\"Hello\")\n    return\n}"
        val search = "println(\"Hello\")\n    return"
        val replace = "println(\"Hi\")\n    yield"
        val expected = "fun hello() {\n    println(\"Hi\")\n    yield\n}"
        assertEquals(expected, CodePatcher.replaceFirstExact(original, search, replace))
    }

    @Test
    fun testReplaceFirstExact_LineEndingNormalization() {
        val original = "line1\r\nline2\r\nline3"
        val search = "line2\nline3"
        val replace = "line2_new\nline3_new"
        val expected = "line1\nline2_new\nline3_new"
        assertEquals(expected, CodePatcher.replaceFirstExact(original, search, replace))
    }

    @Test
    fun testReplaceFirstExact_FuzzyIndentationMatch() {
        val original = "class A {\n    fun b() {\n        // comment\n    }\n}"
        val search = "  fun b() {\n    // comment\n  }"
        val replace = "  fun updated() {\n    // new comment\n  }"
        val expected = "class A {\n  fun updated() {\n    // new comment\n  }\n}"
        assertEquals(expected, CodePatcher.replaceFirstExact(original, search, replace))
    }

    @Test
    fun testReplaceFirstExact_FuzzyEmptyLinesMatch() {
        val original = "line1\n\nline2\n\nline3"
        val search = "line1\nline2\nline3"
        val replace = "new_line1\nnew_line2\nnew_line3"
        val expected = "new_line1\nnew_line2\nnew_line3"
        assertEquals(expected, CodePatcher.replaceFirstExact(original, search, replace))
    }

    @Test
    fun testReplaceFirstExact_BlankSearchString() {
        val original = "anything"
        val search = "  "
        assertNull(CodePatcher.replaceFirstExact(original, search, "replacement"))
    }

    @Test
    fun testReplaceFirstExact_NoMatch() {
        val original = "line1\nline2"
        val search = "line3"
        assertNull(CodePatcher.replaceFirstExact(original, search, "replacement"))
    }

    @Test
    fun testBuildPatchPreview_WithMatch() {
        val original = "line1\nline2\nline3\nline4\nline5\nline6\nline7\nline8"
        val search = "line4\nline5"
        val replace = "line4_new\nline5_new"
        val preview = CodePatcher.buildPatchPreview("test.kt", original, "", search, replace)

        // Expected parts in the preview
        assertTrue(preview.contains("文件: test.kt"))
        assertTrue(preview.contains("  line1"))
        assertTrue(preview.contains("  line2"))
        assertTrue(preview.contains("  line3"))
        assertTrue(preview.contains("- line4"))
        assertTrue(preview.contains("- line5"))
        assertTrue(preview.contains("+ line4_new"))
        assertTrue(preview.contains("+ line5_new"))
        assertTrue(preview.contains("  line6"))
        assertTrue(preview.contains("  line7"))
        assertTrue(preview.contains("  line8"))
    }

    @Test
    fun testBuildPatchPreview_NoMatchFallback() {
        val original = "line1\nline2"
        val search = "missing"
        val replace = "found"
        val preview = CodePatcher.buildPatchPreview("test.kt", original, "", search, replace)

        assertTrue(preview.contains("文件: test.kt"))
        assertTrue(preview.contains("- missing"))
        assertTrue(preview.contains("+ found"))
    }
}
