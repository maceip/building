@file:OptIn(androidx.compose.ui.test.InternalTestApi::class, androidx.compose.ui.test.ExperimentalTestApi::class)
package com.pureswift.swiftui

import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.*
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Actual Swift-exported trees, not hand-written approximations of each surface.
 * The fixture generator is the Swift CupertinoWireTests export test. In the parent
 * SwiftKey checkout it writes artifacts/cupertino/swift-fixtures. Standalone module
 * callers can provide CUPERTINO_FIXTURES_DIR and CUPERTINO_SCREENSHOTS_DIR.
 *
 * The one lazy provider is deliberately a recorded Kotlin test double: serialized
 * callback IDs have no Swift process owner in this JVM. The Swift lazy-materializer
 * tests and CupertinoStateBridgeTests separately exercise that boundary.
 */
@RunWith(Parameterized::class)
class CupertinoCatalogRenderTests(private val fixture: File, private val dark: Boolean, private val fontScale: Float, private val target: String) {
    private val originalSink = SwiftBridge.sink
    private val requestedRows = mutableListOf<Int>()

    @After fun restoreSink() { SwiftBridge.sink = originalSink }

    @Test fun swiftFixtureRendersAtPhoneWidthAndProducesReviewablePixels() {
        assumeTrue("Generate Swift catalogue fixtures or set CUPERTINO_FIXTURES_DIR", fixture.isFile)
        runSkikoComposeUiTest(size = Size(360f, 720f), density = Density(1f, fontScale)) {
            val compose = this
            val sourceTree = Json.decodeFromString<ViewNode>(fixture.readText())
            val tree = if (target == "Material3") {
                fun materialTarget(node: ViewNode): ViewNode = node.copy(
                    props = if (node.string("name") == "AdaptiveTheme") JsonObject(node.props + ("target" to JsonPrimitive("Material3"))) else node.props,
                    children = node.children.map(::materialTarget))
                ViewNode("Composable", "material-target", buildJsonObject { put("name", "AdaptiveTheme"); put("target", "Material3"); put("isDark", dark) },
                    children = listOf(ViewNode("CupertinoSlot", "material-target-content", buildJsonObject { put("name", "content") }, children = listOf(materialTarget(sourceTree)))))
            } else sourceTree
            SwiftBridge.sink = object : CallbackSink {
                override fun invokeVoid(id: Long) {}
                override fun invokeBool(id: Long, value: Boolean) {}
                override fun invokeDouble(id: Long, value: Double) {}
                override fun invokeInt(id: Long, value: Int) {}
                override fun invokeString(id: Long, value: String) {}
                override fun itemNode(id: Long, index: Int): ViewNode {
                    requestedRows += index
                    return ViewNode("Text", "fixture-provider-$id-$index", buildJsonObject { put("text", "Lazy item $index") })
                }
            }
            compose.setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f, fontScale), LocalAppearanceIsDark provides dark) {
                    MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                        Surface(modifier = Modifier.requiredSize(360.dp, 720.dp).testTag("catalog-capture"),
                            color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) {
                            RenderChild(tree)
                        }
                    }
                }
            }
            compose.waitForIdle()
            if (fixture.name == "CupertinoSearchTextFieldDefaults.cancelButton-open.json") {
                compose.onNode(hasSetTextAction()).performClick()
                compose.onNodeWithText("Cancel").assertIsDisplayed()
            }
            val text = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true)
                .fetchSemanticsNodes().flatMap { it.config[SemanticsProperties.Text] }.map { it.text }
            val diagnostics = text.filter { it.endsWith("unavailable.") ||
                (it.contains(" requires ") && !it.contains("requires iOS")) }
            if (fixture.name.startsWith("LazySectionScope.items-")) {
                assertTrue(requestedRows.isNotEmpty(), "The fixture must request visible rows from its provider")
                assertTrue(requestedRows.size < 100, "Lazy rendering must not eagerly materialize all rows")
            }
            val output = screenshotDirectory(fixture)
            output.mkdirs()
            val stem = "${fixture.nameWithoutExtension}-${if (dark) "dark" else "light"}-360px-font${fontScale}${if (target == "Material3") "-material3" else ""}"
            val bitmap = compose.onNodeWithTag("catalog-capture").captureToImage()
            assertEquals(360, bitmap.width, "Capture should be phone width, independent of host density")
            assertEquals(720, bitmap.height)
            Image.makeFromBitmap(bitmap.asSkiaBitmap()).use { image ->
                image.encodeToData(EncodedImageFormat.PNG)?.use { File(output, "$stem.png").writeBytes(it.bytes) }
                    ?: error("Skia could not encode ${fixture.name}")
            }
            // Popups/dialogs are separate Compose roots; capture them explicitly too.
            val roots = compose.onAllNodes(isRoot(), useUnmergedTree = true)
            val rootCount = roots.fetchSemanticsNodes().size
            if (rootCount > 1) for (index in 1 until rootCount) {
                val popup = roots[index].captureToImage()
                assertTrue(popup.width <= 360 && popup.height <= 720, "Popup capture must retain the actual phone viewport")
                Image.makeFromBitmap(popup.asSkiaBitmap()).use { image ->
                    image.encodeToData(EncodedImageFormat.PNG)?.use { File(output, "$stem-popup$index.png").writeBytes(it.bytes) }
                        ?: error("Skia could not encode popup ${fixture.name}")
                }
            }
            File(output, "$stem.json").writeText(buildJsonObject {
                put("fixture", fixture.name)
                put("fixtureSHA256", java.security.MessageDigest.getInstance("SHA-256").digest(fixture.readBytes()).joinToString("") { "%02x".format(it) })
                put("width", bitmap.width); put("height", bitmap.height); put("dark", dark); put("fontScale", fontScale)
                put("adaptiveTarget", target); put("rootCount", rootCount); put("mockLazyProviderRequests", requestedRows.size)
                put("diagnostics", JsonArray(diagnostics.map(::JsonPrimitive)))
            }.toString())
            assertTrue(diagnostics.isEmpty(), "${fixture.name} has bridge diagnostics: $diagnostics")
            if (fixture.name == "AdaptiveAlertDialog-open.json" || fixture.name == "AdaptiveAlertDialogNative-open.json") {
                val dialogTitle = compose.onNodeWithText(if (fixture.name.contains("Native")) "Catalog title" else "Catalog dialog")
                File(output, "$stem-semantics.txt").writeText(dialogTitle.printToString() + "\n" +
                    roots.fetchSemanticsNodes().joinToString("\n") { "root: ${it.boundsInRoot}, window: ${it.boundsInWindow}" })
                dialogTitle.assertIsDisplayed()
            }
        }
    }

    companion object {
        private fun fixtureDirectory(): File? = System.getenv("CUPERTINO_FIXTURES_DIR")?.let(::File)
            ?: generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
                .map { File(it, "artifacts/cupertino/swift-fixtures") }.firstOrNull { it.isDirectory }

        private fun screenshotDirectory(fixture: File): File = System.getenv("CUPERTINO_SCREENSHOTS_DIR")?.let(::File)
            ?: File(fixture.parentFile.parentFile, "screenshots")

        @JvmStatic @Parameterized.Parameters(name = "{0}, dark={1}, fontScale={2}, target={3}")
        fun fixtures(): List<Array<Any>> {
            val fixtures = fixtureDirectory()?.listFiles { f -> f.extension == "json" }?.sortedBy { it.name }.orEmpty()
            if (fixtures.isEmpty()) return listOf(arrayOf(File("missing-swift-cupertino-fixtures.json"), false, 1f, "Cupertino"))
            val representative = setOf("CupertinoScaffold", "CupertinoButton", "CupertinoBorderedTextField", "CupertinoSearchTextField",
                "CupertinoNavigationBar", "CupertinoSegmentedControl", "CupertinoSection", "CupertinoDatePicker", "CupertinoAlertDialog",
                "CupertinoBottomSheetScaffold", "AdaptiveTheme", "CupertinoWheelPicker")
            return buildList {
                for (file in fixtures) {
                    add(arrayOf(file, false, 1f, "Cupertino"))
                    if (file.name.startsWith("Adaptive") || file.name.startsWith("RowScope.Adaptive")) add(arrayOf(file, false, 1f, "Material3"))
                    if (file.name.endsWith("-open.json") && file.name.removeSuffix("-open.json") in representative) {
                        add(arrayOf(file, true, 1f, "Cupertino")); add(arrayOf(file, false, 1.4f, "Cupertino")); add(arrayOf(file, true, 1.4f, "Cupertino"))
                    }
                }
            }
        }
    }
}
