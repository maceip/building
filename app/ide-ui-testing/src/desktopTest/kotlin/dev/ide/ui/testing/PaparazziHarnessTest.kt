package dev.ide.ui.testing

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PaparazziHarnessTest {

    @Test
    fun capturesAccessibilitySnapshotAnd3PaneDiffAndDetectsDeadClickables() {
        val tempDir = Files.createTempDirectory("paparazzi-test").toFile()
        try {
            var counter = 0
            PaparazziSession(
                deviceConfig = DeviceConfig.PIXEL_9_PRO.withHeight(600),
                reportDir = tempDir,
            ) {
                var uiCount by remember { mutableIntStateOf(0) }
                Column(Modifier.fillMaxSize().background(Color(0xFF141510)).padding(20.dp)) {
                    Box(
                        Modifier
                            .size(200.dp, 56.dp)
                            .background(Color(0xFFD4E84A))
                            .clickable {
                                counter++
                                uiCount = counter
                            }
                    ) {
                        Text("Increment ($uiCount)", color = Color.Black)
                    }
                }
            }.use { session ->
                // 1. Snapshot with AccessibilityRenderExtension side-by-side legend
                val snapFile = session.snapshot("counter_a11y", includeAccessibilityOverlay = true)
                assertTrue(snapFile.exists() && snapFile.length() > 1000)

                // 2. Verify 3-pane golden comparison
                val cmp = session.verifySnapshot("counter_golden")
                assertTrue(cmp.passed)
                assertEquals(0, cmp.changedPixels)
                assertTrue(cmp.compositeFile.exists())

                // 3. Verify assertNoDeadClickables clicks "Increment (0)" and sees `counter` mutate
                val verified = session.assertNoDeadClickables(backendStateSnapshot = { counter })
                assertEquals(1, verified.size)
                assertEquals(1, counter)

                // 4. HTML report generated
                assertTrue(File(tempDir, "index.html").exists())
            }

            // 5. Verify a dead button (`onClick = {}`) is caught immediately by assertNoDeadClickables!
            PaparazziSession(
                deviceConfig = DeviceConfig.PIXEL_9_PRO.withHeight(400),
                reportDir = tempDir,
            ) {
                Column(Modifier.fillMaxSize()) {
                    Box(
                        Modifier
                            .size(160.dp, 48.dp)
                            .clickable { /* DEAD NO-OP BUTTON */ }
                    ) {
                        Text("Fake Download")
                    }
                }
            }.use { deadSession ->
                val error = assertFailsWith<AssertionError> {
                    deadSession.assertNoDeadClickables(backendStateSnapshot = { "unchanged" })
                }
                assertTrue(error.message!!.contains("Fake Download"))
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
