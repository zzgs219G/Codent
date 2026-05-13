package com.xixin.codent.wrapper.copy

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class CopyTest {

    @Test
    fun `copy with empty string does nothing`() {
        val context = mockk<Context>()

        context.copy("")

        verify(exactly = 0) { context.getSystemService(Context.CLIPBOARD_SERVICE) }
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.S]) // Below TIRAMISU
    fun `copy sets clipboard and shows toast below Tiramisu`() {
        val context = mockk<Context>(relaxed = true)
        val clipboardManager = mockk<ClipboardManager>(relaxed = true)

        every { context.getSystemService(Context.CLIPBOARD_SERVICE) } returns clipboardManager

        mockkStatic(Toast::class)
        val toastMock = mockk<Toast>(relaxed = true)
        every { Toast.makeText(context, "已复制", Toast.LENGTH_SHORT) } returns toastMock

        val textToCopy = "Hello World"
        context.copy(textToCopy)

        val clipDataSlot = slot<ClipData>()
        verify { clipboardManager.setPrimaryClip(capture(clipDataSlot)) }
        assertEquals("WebSource", clipDataSlot.captured.description.label)
        assertEquals(textToCopy, clipDataSlot.captured.getItemAt(0).text.toString())

        verify { Toast.makeText(context, "已复制", Toast.LENGTH_SHORT) }
        verify { toastMock.show() }
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.TIRAMISU]) // TIRAMISU and above
    fun `copy sets clipboard and does not show toast on Tiramisu and above`() {
        val context = mockk<Context>(relaxed = true)
        val clipboardManager = mockk<ClipboardManager>(relaxed = true)

        every { context.getSystemService(Context.CLIPBOARD_SERVICE) } returns clipboardManager

        mockkStatic(Toast::class)

        val textToCopy = "Test String 123"
        context.copy(textToCopy)

        val clipDataSlot = slot<ClipData>()
        verify { clipboardManager.setPrimaryClip(capture(clipDataSlot)) }
        assertEquals("WebSource", clipDataSlot.captured.description.label)
        assertEquals(textToCopy, clipDataSlot.captured.getItemAt(0).text.toString())

        verify(exactly = 0) { Toast.makeText(any(), any<String>(), any()) }
    }
}
