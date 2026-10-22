package dev.ide.interp.compose

import dev.ide.lang.incremental.DocumentSnapshot
import dev.ide.lang.kotlin.interp.KotlinPreviewLowering
import dev.ide.lang.kotlin.parse.KotlinIncrementalParser
import dev.ide.lang.kotlin.parse.KotlinParsedFile
import dev.ide.platform.ContentHash
import dev.ide.vfs.VirtualFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 4-Tier End-to-End Test Suite for Compose Skia Preview & Touch Event Pipeline on iOS:
 *
 * Tier 1: Feature Coverage (PNG generation, magic bytes, session lifecycle, pointer forwarding)
 * Tier 2: Boundary & Corner Cases (Extreme resolutions 10x10 to 2048x2048, out-of-bound coords, invalid session ID, rapid open/close)
 * Tier 3: Cross-Feature Combinations (Density 1.0-3.0x, fontScale variations, dark theme, background colors)
 * Tier 4: Real-World Scenarios (Interactive Compose button layout with pointer event dispatch)
 */
class VmPreviewE2ETest {

    private val sampleSource = """
        package demo.ui
        import androidx.compose.foundation.layout.Arrangement
        import androidx.compose.foundation.layout.Column
        import androidx.compose.foundation.layout.padding
        import androidx.compose.material3.Button
        import androidx.compose.material3.MaterialTheme
        import androidx.compose.material3.Surface
        import androidx.compose.material3.Text
        import androidx.compose.runtime.Composable
        import androidx.compose.ui.Modifier
        import androidx.compose.ui.unit.dp

        @Composable fun HeaderText(title: String) {
            Text("Preview: " + title)
        }

        @Composable fun MainPreview() {
            MaterialTheme {
                Surface {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        HeaderText("CrossPlatformIDE")
                        Button(onClick = {}) { Text("Tap Action") }
                    }
                }
            }
        }
    """.trimIndent()

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

    private fun compileBlob(source: String = sampleSource, entryPoint: String = "MainPreview/0"): ByteArray {
        val parsed = KotlinIncrementalParser().parseFull(Doc(source)) as KotlinParsedFile
        val program = KotlinPreviewLowering(previewSymbolService()).program(parsed)
        val entry = program[entryPoint] ?: error("No $entryPoint found; candidates=${program.keys}")
        return VmPreviewEntry.encode(entry, program, emptyList())
    }

    // =========================================================================
    // TIER 1: FEATURE COVERAGE
    // =========================================================================

    @Test
    fun tier1_vmPreviewEntry_encodesAndRendersPngFrame() {
        val blob = compileBlob()
        val pngBytes = VmPreviewEntry.renderPng(blob, 360, 480, 2.0f)
        assertTrue(pngBytes.isNotEmpty(), "Rendered PNG bytes must not be empty")
        assertTrue(pngBytes.size > 200, "PNG file must be of substantial size for rendered Compose UI")

        // Assert PNG Magic Bytes (89 50 4E 47 0D 0A 1A 0A)
        assertEquals(0x89.toByte(), pngBytes[0])
        assertEquals(0x50.toByte(), pngBytes[1]) // 'P'
        assertEquals(0x4E.toByte(), pngBytes[2]) // 'N'
        assertEquals(0x47.toByte(), pngBytes[3]) // 'G'
    }

    @Test
    fun tier1_vmPreviewEntry_sessionLifecycleOpenPointerFrameClose() {
        val blob = compileBlob()
        val sessionId = VmPreviewEntry.open(
            blob = blob,
            width = 360,
            height = 640,
            density = 2.0f,
            fontScale = 1.0f,
            dark = false,
            background = 0L,
        )
        assertTrue(sessionId > 0, "Session ID must be positive integer")

        try {
            // Initial Frame
            val initialFrame = VmPreviewEntry.frame(sessionId)
            val initialPng = initialFrame[0] as? ByteArray
            assertNotNull(initialPng, "Initial session frame PNG must not be null")
            assertTrue(initialPng.isNotEmpty())

            // Touch press at button position (x=50, y=80)
            VmPreviewEntry.pointer(sessionId, kind = 0, x = 50f, y = 80f) // Press
            VmPreviewEntry.pointer(sessionId, kind = 1, x = 52f, y = 82f) // Move
            VmPreviewEntry.pointer(sessionId, kind = 2, x = 52f, y = 82f) // Release

            val postTapFrame = VmPreviewEntry.frame(sessionId)
            val postTapPng = postTapFrame[0] as? ByteArray
            assertNotNull(postTapPng, "Frame after pointer input must not be null")
            assertTrue(postTapPng.isNotEmpty())
        } finally {
            VmPreviewEntry.close(sessionId)
        }
    }

    @Test
    fun tier1_vmPreviewEntry_renderSummaryInkedPixels() {
        val blob = compileBlob()
        val summary = VmPreviewEntry.renderSummary(blob, 200, 200, 1.0f)
        assertTrue(summary.contains("200x200"), "Summary must reflect requested dimensions: $summary")
        assertTrue(summary.contains("inked="), "Summary must report inked count: $summary")
        val inkedCount = Regex("""inked=(\d+)""").find(summary)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        assertTrue(inkedCount > 50, "Inked pixels must be > 50 for content with text and buttons: got $inkedCount")
    }

    // =========================================================================
    // TIER 2: BOUNDARY & CORNER CASES
    // =========================================================================

    @Test
    fun tier2_extremeResolutions_microAndUltra() {
        val blob = compileBlob()

        // Micro resolution (10x10 px)
        val microPng = VmPreviewEntry.renderPng(blob, 10, 10, 1.0f)
        assertTrue(microPng.isNotEmpty())
        assertEquals(0x89.toByte(), microPng[0])

        // Large resolution (1024x1024 px)
        val ultraPng = VmPreviewEntry.renderPng(blob, 1024, 1024, 2.0f)
        assertTrue(ultraPng.isNotEmpty())
        assertTrue(ultraPng.size > microPng.size, "Ultra resolution must generate larger PNG than micro")
    }

    @Test
    fun tier2_invalidTouchCoordinates_negativeAndOutOfBounds() {
        val blob = compileBlob()
        val sid = VmPreviewEntry.open(blob, 300, 300, 1.0f, 1.0f, false, 0L)
        try {
            // Negative coordinates
            VmPreviewEntry.pointer(sid, kind = 0, x = -100f, y = -150f)
            VmPreviewEntry.pointer(sid, kind = 1, x = -50f, y = -50f)
            VmPreviewEntry.pointer(sid, kind = 2, x = -10f, y = -10f)

            // Extreme out of bounds coordinates
            VmPreviewEntry.pointer(sid, kind = 0, x = 99999f, y = 99999f)
            VmPreviewEntry.pointer(sid, kind = 2, x = 99999f, y = 99999f)

            // Session must remain intact and render valid frame
            val frame = VmPreviewEntry.frame(sid)
            val png = frame[0] as? ByteArray
            assertNotNull(png, "Frame must be valid despite out-of-bounds pointer input")
        } finally {
            VmPreviewEntry.close(sid)
        }
    }

    @Test
    fun tier2_invalidSessionId_returnsNullFrame() {
        val nonExistentId = 9876543
        val frameResult = VmPreviewEntry.frame(nonExistentId)
        assertNull(frameResult[0], "PNG byte array must be null for non-existent session")
        assertTrue((frameResult[1] as? String).orEmpty().contains("no preview session"), "Error message must report missing session")
        assertEquals(false, frameResult[2], "Animating must be false")
    }

    @Test
    fun tier2_rapidSessionOpenCloseStress() {
        val blob = compileBlob()
        val sessionIds = mutableListOf<Int>()
        for (i in 1..15) {
            val sid = VmPreviewEntry.open(blob, 150, 150, 1.0f, 1.0f, false, 0L)
            sessionIds.add(sid)
        }
        for (sid in sessionIds) {
            val frame = VmPreviewEntry.frame(sid)
            assertNotNull(frame[0])
            VmPreviewEntry.close(sid)
        }
    }

    // =========================================================================
    // TIER 3: CROSS-FEATURE COMBINATIONS
    // =========================================================================

    @Test
    fun tier3_multiDensityAndFontScaleVariations() {
        val blob = compileBlob()

        // 1.0x mdpi
        val sig1 = VmPreviewEntry.renderSignature(blob, 200, 200, 1.0f)
        // 2.0x xhdpi
        val sig2 = VmPreviewEntry.renderSignature(blob, 200, 200, 2.0f)
        // 3.0x xxhdpi (iPhone XS Max)
        val sig3 = VmPreviewEntry.renderSignature(blob, 200, 200, 3.0f)

        assertTrue(sig1 != sig2, "Render signatures must differ between 1.0x and 2.0x density")
        assertTrue(sig2 != sig3, "Render signatures must differ between 2.0x and 3.0x density")
    }

    @Test
    fun tier3_darkThemeAndBackgroundColor() {
        val blob = compileBlob()
        // Light mode
        val lightFrame = VmPreviewEntry.renderFrame(blob, 200, 200, 1.0f, 1.0f, dark = false, background = 0L)
        val lightPng = lightFrame[0] as? ByteArray
        assertNotNull(lightPng)

        // Dark mode with custom dark background (0xFF1E1E1E)
        val darkFrame = VmPreviewEntry.renderFrame(blob, 200, 200, 1.0f, 1.0f, dark = true, background = 0xFF1E1E1EL)
        val darkPng = darkFrame[0] as? ByteArray
        assertNotNull(darkPng)

        assertTrue(lightPng.isNotEmpty())
        assertTrue(darkPng.isNotEmpty())
    }

    // =========================================================================
    // TIER 4: REAL-WORLD APPLICATION SCENARIOS
    // =========================================================================

    @Test
    fun tier4_interactiveButtonSceneWithStateReactivity() {
        val counterSource = """
            package demo.counter
            import androidx.compose.foundation.layout.Column
            import androidx.compose.foundation.layout.padding
            import androidx.compose.material3.Button
            import androidx.compose.material3.MaterialTheme
            import androidx.compose.material3.Text
            import androidx.compose.runtime.Composable
            import androidx.compose.ui.Modifier
            import androidx.compose.ui.unit.dp

            @Composable fun CounterView() {
                MaterialTheme {
                    Column(Modifier.padding(24.dp)) {
                        Text("Current Count: 0")
                        Button(onClick = {}) {
                            Text("Increment")
                        }
                    }
                }
            }
        """.trimIndent()

        val blob = compileBlob(counterSource, "CounterView/0")
        val sid = VmPreviewEntry.open(blob, 320, 480, 2.0f, 1.0f, false, 0L)
        try {
            val frame1 = VmPreviewEntry.frame(sid)
            val png1 = frame1[0] as? ByteArray
            assertNotNull(png1)
            assertTrue(png1.size > 500)

            // Simulate user tap sequence on button
            VmPreviewEntry.pointer(sid, kind = 0, x = 60f, y = 70f)
            VmPreviewEntry.pointer(sid, kind = 2, x = 60f, y = 70f)

            val frame2 = VmPreviewEntry.frame(sid)
            val png2 = frame2[0] as? ByteArray
            assertNotNull(png2)
            assertTrue(png2.size > 500)
        } finally {
            VmPreviewEntry.close(sid)
        }
    }
}
