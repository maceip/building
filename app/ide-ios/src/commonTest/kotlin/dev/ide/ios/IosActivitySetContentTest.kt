package dev.ide.ios

import kotlinx.coroutines.runBlocking
import platform.Foundation.NSTemporaryDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class IosActivitySetContentTest {

    private val root = IosFiles.join(
        NSTemporaryDirectory().trimEnd('/'),
        "activity-set-content-test-${IosFiles.modifiedMs(NSTemporaryDirectory())}",
    )

    private fun libraryJars(): List<String> = PreviewTestClasspath.jars.filter { jar ->
        listOf("/org.jetbrains.compose", "/androidx.", "/org.jetbrains.kotlin/kotlin-stdlib", "/org.jetbrains.kotlinx/kotlinx-coroutines", "/org.jetbrains.skiko")
            .any { it in jar } && "compiler" !in jar
    }

    @Test
    fun testExtractActivitySetContentComposables_patterns() {
        val analysis = IosKotlinAnalysis(root, classpathJars = emptyList())

        // 1. Direct call
        val direct = """
            package com.example
            class MainActivity : ComponentActivity() {
                fun onCreate() {
                    setContent {
                        HomeScreen()
                    }
                }
            }
        """.trimIndent()
        val c1 = analysis.extractActivitySetContentComposables("/p/MainActivity.kt", direct)
        assertTrue("HomeScreen" in c1, "Direct call should extract HomeScreen: got $c1")

        // 2. Themed wrapper
        val themed = """
            package com.example
            class MainActivity : ComponentActivity() {
                fun onCreate() {
                    setContent {
                        AppTheme {
                            MainContentScreen()
                        }
                    }
                }
            }
        """.trimIndent()
        val c2 = analysis.extractActivitySetContentComposables("/p/MainActivity.kt", themed)
        assertTrue("MainContentScreen" in c2, "Themed wrapper should extract MainContentScreen: got $c2")

        // 3. Multi-layer Surface + Scaffold + Theme
        val multiLayer = """
            package com.example
            class MainActivity : ComponentActivity() {
                fun onCreate() {
                    enableEdgeToEdge()
                    setContent {
                        MaterialTheme {
                            Surface {
                                Scaffold {
                                    Box {
                                        RootNavigation()
                                    }
                                }
                            }
                        }
                    }
                }
            }
        """.trimIndent()
        val c3 = analysis.extractActivitySetContentComposables("/p/MainActivity.kt", multiLayer)
        assertTrue("RootNavigation" in c3, "Multi-layer layout containers should unwrap to RootNavigation: got $c3")

        analysis.close()
    }

    @Test
    fun testMultiFileProject_activitySetContentLowersAndRenders() {
        IosFiles.mkdirs(IosFiles.join(root, "app/src/main/kotlin/com/example/ui"))
        IosFiles.mkdirs(IosFiles.join(root, "app/src/main/kotlin/com/example/model"))

        val activityPath = IosFiles.join(root, "app/src/main/kotlin/com/example/MainActivity.kt")
        val rootUiPath = IosFiles.join(root, "app/src/main/kotlin/com/example/ui/AppRoot.kt")
        val modelPath = IosFiles.join(root, "app/src/main/kotlin/com/example/model/UserData.kt")

        val activitySource = """
            package com.example
            class MainActivity : ComponentActivity() {
                fun onCreate() {
                    setContent {
                        AppTheme {
                            AppRoot()
                        }
                    }
                }
            }
        """.trimIndent()

        val rootUiSource = """
            package com.example.ui
            import androidx.compose.material3.MaterialTheme
            import androidx.compose.material3.Surface
            import androidx.compose.material3.Text
            import androidx.compose.runtime.Composable
            import com.example.model.UserData

            @Composable fun AppRoot() {
                val user = UserData("EmpiricalTester")
                MaterialTheme {
                    Surface {
                        Text("Hello: " + user.name)
                    }
                }
            }
        """.trimIndent()

        val modelSource = """
            package com.example.model
            data class UserData(val name: String)
        """.trimIndent()

        IosFiles.writeText(activityPath, activitySource)
        IosFiles.writeText(rootUiPath, rootUiSource)
        IosFiles.writeText(modelPath, modelSource)

        val analysis = IosKotlinAnalysis(root, classpathJars = libraryJars())

        // Step 1: Discover entry composables from MainActivity.kt
        val candidates = analysis.extractActivitySetContentComposables(activityPath, activitySource)
        assertTrue("AppRoot" in candidates, "Candidates must include AppRoot: $candidates")

        // Step 2: Lower AppRoot in rootUiPath
        val blob = assertNotNull(analysis.lowerPreview(rootUiPath, rootUiSource, "AppRoot"), "AppRoot must lower successfully across multi-file project")
        assertTrue(blob.isNotEmpty())

        // Step 3: Render on iOS preview pipeline
        val renderer = IosPreviewRenderer(PreviewTestClasspath.jars)
        val frameResult = runBlocking {
            IosPreviewThread.run {
                renderer.renderFrame(blob, 300, 200, 2f, 1f, false, 0L)
            }
        }
        val summary = runBlocking {
            IosPreviewThread.run {
                renderer.renderSummary(blob, 300, 200, 2f)
            }
        }
        println("SUMMARY: $summary, PROBLEMS: ${frameResult.problems}")
        assertTrue(frameResult.problems.isEmpty(), "Problems reported: ${frameResult.problems}")
        val inked = summary.substringAfter("inked=").substringBefore(" ").toInt()
        assertTrue(inked > 100, "Rendered frame must contain inked pixels: got $inked")

        // Step 4: Verify PNG frame generation
        val png = runBlocking {
            IosPreviewThread.run {
                renderer.renderPng(blob, 300, 200, 2f)
            }
        }
        assertNotNull(png)
        assertTrue(png.isNotEmpty())
        assertEquals(0x89.toByte(), png[0])
        assertEquals(0x50.toByte(), png[1])

        analysis.close()
    }
}
