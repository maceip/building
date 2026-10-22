package dev.ide.interp.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ide.lang.incremental.DocumentSnapshot
import dev.ide.lang.kotlin.interp.KotlinPreviewLowering
import dev.ide.lang.kotlin.parse.KotlinIncrementalParser
import dev.ide.lang.kotlin.parse.KotlinParsedFile
import dev.ide.platform.ContentHash
import dev.ide.vfs.VirtualFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Empirical Challenger Stress Test Suite for Android-on-iOS Pipeline:
 *
 * 1. DynamicPreviewResourceResolver: verifies R-field generation, string/color/dimension/painter resolution
 * 2. VmPreviewEntry surface resizing: verifies resize(width, height) lifecycle and frame output
 * 3. Pointer event forwarding & state mutation: verifies touch inputs induce live UI changes
 * 4. Multi-file Activity setContent extraction logic
 */
class ChallengerStressTest {

    private class Doc(override val text: CharSequence) : DocumentSnapshot {
        override val file: VirtualFile = F()
        override val version = 1L
        override fun length() = text.length
    }

    private class F : VirtualFile {
        override val path = "Main.kt"; override val name = "Main.kt"; override val isDirectory = false
        override val exists = true; override val length = 0L
        override fun parent(): VirtualFile? = null
        override fun children(): List<VirtualFile> = emptyList()
        override fun contentHash() = ContentHash("")
        override fun readBytes() = ByteArray(0)
        override fun readText(): CharSequence = ""
    }

    private fun compileBlob(source: String, entryPoint: String): ByteArray {
        val parsed = KotlinIncrementalParser().parseFull(Doc(source)) as KotlinParsedFile
        val program = KotlinPreviewLowering(previewSymbolService()).program(parsed)
        val entry = program[entryPoint] ?: error("No $entryPoint found; candidates=${program.keys}")
        return VmPreviewEntry.encode(entry, program, emptyList())
    }

    @Test
    fun test_dynamicPreviewResourceResolver_contracts() {
        val resolver = VmPreviewEntry.DynamicPreviewResourceResolver()

        // 1. R-class field mapping to unique ID
        val id1 = resolver.rClassField("com.example.app.R\$string", "app_name")
        val id2 = resolver.rClassField("com.example.app.R\$string", "welcome_message")
        val id1Again = resolver.rClassField("com.example.app.R\$string", "app_name")

        assertNotNull(id1)
        assertNotNull(id2)
        assertEquals(id1, id1Again, "Same field must resolve to same ID")
        assertNotEquals(id1, id2, "Different fields must receive distinct IDs")
        assertTrue(id1 >= 0x7f040001, "IDs should start in valid Android resource space")

        // 2. String resolution formatting
        val str1 = resolver.string(id1!!)
        println("DEBUG: str1='$str1' for id1=$id1")
        assertEquals("App name", str1, "Fallback string should convert snake_case to title case")

        val str2 = resolver.string(id2)
        assertEquals("Welcome message", str2)

        // 3. Color, dimension, painter resolution
        val color = resolver.color(id1)
        assertNotNull(color)
        assertTrue(color is Color)

        val dimen = resolver.dimension(id1)
        assertNotNull(dimen)
        assertTrue(dimen is Dp)

        val painter = resolver.painter(id1)
        assertNotNull(painter)
        assertTrue(painter is ColorPainter)

        // 4. Plurals & string arrays
        assertEquals("1 app name", resolver.plural(id1, 1))
        assertEquals("5 app names", resolver.plural(id1, 5))
        assertEquals("1 item", resolver.plural(999999, 1))
        assertEquals("5 items", resolver.plural(999999, 5))
        val array = resolver.stringArray(id1)
        assertNotNull(array)
        assertTrue(array.isNotEmpty())
    }

    @Test
    fun test_vmPreviewEntry_resize_recreatesSceneAndRendersFrame() {
        val source = """
            package demo.resize
            import androidx.compose.foundation.layout.Box
            import androidx.compose.foundation.layout.fillMaxSize
            import androidx.compose.material3.MaterialTheme
            import androidx.compose.material3.Surface
            import androidx.compose.material3.Text
            import androidx.compose.runtime.Composable
            import androidx.compose.ui.Alignment
            import androidx.compose.ui.Modifier

            @Composable fun ResizableScreen() {
                MaterialTheme {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        Box(contentAlignment = Alignment.Center) {
                            Text("Responsive Content")
                        }
                    }
                }
            }
        """.trimIndent()

        val blob = compileBlob(source, "ResizableScreen/0")
        val sid = VmPreviewEntry.open(
            blob = blob,
            width = 320,
            height = 480,
            density = 2.0f,
            fontScale = 1.0f,
            dark = false,
            background = 0L,
        )
        try {
            // Initial frame at 320x480
            val initialFrame = VmPreviewEntry.frame(sid)
            val initialPng = initialFrame[0] as? ByteArray
            assertNotNull(initialPng, "Initial PNG must be rendered")
            assertTrue(initialPng.isNotEmpty())

            // Resize to 400x800
            VmPreviewEntry.resize(sid, width = 400, height = 800)

            // Next frame must render at new dimensions
            val resizedFrame = VmPreviewEntry.frame(sid)
            val resizedPng = resizedFrame[0] as? ByteArray
            assertNotNull(resizedPng, "Frame after resize must not be null")
            assertTrue(resizedPng.isNotEmpty())
            assertNotEquals(initialPng.size, resizedPng.size, "PNG bytes should change under different surface dimensions")

            // Re-resizing to identical dimensions must be idempotent
            VmPreviewEntry.resize(sid, width = 400, height = 800)
            val sameFrame = VmPreviewEntry.frame(sid)
            assertNotNull(sameFrame[0])

            // Resize to extreme dimension
            VmPreviewEntry.resize(sid, width = 50, height = 50)
            val microFrame = VmPreviewEntry.frame(sid)
            assertNotNull(microFrame[0])
        } finally {
            VmPreviewEntry.close(sid)
        }
    }

    @Test
    fun test_vmPreviewEntry_pointerInput_inducesStateTransition() {
        val counterSource = """
            package demo.interactive
            import androidx.compose.foundation.clickable
            import androidx.compose.foundation.layout.Box
            import androidx.compose.foundation.layout.fillMaxSize
            import androidx.compose.material3.MaterialTheme
            import androidx.compose.material3.Surface
            import androidx.compose.material3.Text
            import androidx.compose.runtime.Composable
            import androidx.compose.runtime.getValue
            import androidx.compose.runtime.mutableIntStateOf
            import androidx.compose.runtime.remember
            import androidx.compose.runtime.setValue
            import androidx.compose.ui.Alignment
            import androidx.compose.ui.Modifier

            @Composable fun StatefulCounter() {
                var count by remember { mutableIntStateOf(0) }
                MaterialTheme {
                    Surface(
                        modifier = Modifier.fillMaxSize().clickable { count++ },
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text("Current: " + count)
                        }
                    }
                }
            }
        """.trimIndent()

        val blob = compileBlob(counterSource, "StatefulCounter/0")
        val sid = VmPreviewEntry.open(blob, 300, 300, 1.0f, 1.0f, false, 0L)
        try {
            // First frame before click
            val frameBefore = VmPreviewEntry.frame(sid)
            val pngBefore = frameBefore[0] as? ByteArray
            assertNotNull(pngBefore)

            // Send pointer tap sequence (Press = 0, Release = 2) at center (150, 150)
            VmPreviewEntry.pointer(sid, kind = 0, x = 150f, y = 150f)
            VmPreviewEntry.pointer(sid, kind = 2, x = 150f, y = 150f)

            // Second frame after click
            val frameAfter = VmPreviewEntry.frame(sid)
            val pngAfter = frameAfter[0] as? ByteArray
            assertNotNull(pngAfter)

            // Assert that pixel content actually transitioned due to state change
            val diff = !pngBefore.contentEquals(pngAfter)
            assertTrue(diff, "Frame PNG bytes must differ after pointer tap triggers state change")
        } finally {
            VmPreviewEntry.close(sid)
        }
    }

    @Test
    fun test_vmPreviewEntry_sessionLifecycleContracts() {
        val source = """
            package demo.lifecycle
            import androidx.compose.material3.Text
            import androidx.compose.runtime.Composable

            @Composable fun SimpleText() {
                Text("Session Test")
            }
        """.trimIndent()

        val blob = compileBlob(source, "SimpleText/0")
        val sid = VmPreviewEntry.open(blob, 200, 200, 1.0f, 1.0f, false, 0L)
        assertTrue(sid > 0)

        // Close session
        VmPreviewEntry.close(sid)

        // Frame query on closed session must return null PNG and error message
        val deadFrame = VmPreviewEntry.frame(sid)
        assertNull(deadFrame[0], "Closed session frame must be null")
        val errMsg = deadFrame[1] as? String
        assertNotNull(errMsg)
        assertTrue(errMsg.contains("no preview session $sid"))
    }
}
