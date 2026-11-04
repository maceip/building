package dev.ide.core.discovery

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SmartAppImageGeneratorTest {

    private fun assertValidPng(bytes: ByteArray) {
        assertTrue(bytes.size > 120, "Generated PNG must be > 120 bytes (actual=${bytes.size})")
        assertEquals(0x89.toByte(), bytes[0], "PNG signature byte 0")
        assertEquals(0x50.toByte(), bytes[1], "PNG signature byte 1 ('P')")
        assertEquals(0x4E.toByte(), bytes[2], "PNG signature byte 2 ('N')")
        assertEquals(0x47.toByte(), bytes[3], "PNG signature byte 3 ('G')")
    }

    @Test
    fun testTier1LocalBuildPreviewForSwiftUiApp(@TempDir tempDir: Path) {
        val swiftFile = tempDir.resolve("ContentView.swift")
        Files.writeString(
            swiftFile,
            """
            import SwiftUI

            struct ContentView: View {
                @State private var count = 42
                @State private var darkMode = true

                var body: some View {
                    NavigationStack {
                        VStack(spacing: 16) {
                            Text("Pulse Financier")
                                .font(.title)
                            Text("Active Portfolio: $128,450")
                            Toggle("Realtime Alerts", isOn: ${'$'}darkMode)
                            Button("Execute Trade") {
                                count += 1
                            }
                        }
                        .navigationTitle("Portfolio")
                    }
                }
            }
            """.trimIndent()
        )

        val generator = SmartAppImageGenerator()
        val result = generator.generateOrResolve(
            owner = "AfrazCodes",
            repo = "SwiftUI-Pulse",
            language = "Swift",
            description = "SwiftUI financial dashboard",
            localRepoDir = tempDir,
            preferLocalBuild = true
        )

        assertEquals(SmartAppImageGenerator.ResultTier.LOCAL_BUILD_PREVIEW, result.tier)
        assertValidPng(result.bytes)
        assertTrue(result.originDescription.contains("SwiftUI", ignoreCase = true))
    }

    @Test
    fun testTier1LocalBuildPreviewForComposeApp(@TempDir tempDir: Path) {
        val ktFile = tempDir.resolve("MainScreen.kt")
        Files.writeString(
            ktFile,
            """
            package com.example.habits

            import androidx.compose.runtime.Composable
            import androidx.compose.ui.tooling.preview.Preview

            @Preview(showBackground = true)
            @Composable
            fun HabitTrackerPreview() {
                Scaffold(
                    topBar = { TopAppBar(title = { Text("Habit Streak") }) }
                ) {
                    Column {
                        Card {
                            Text("Morning Meditation")
                            Switch(checked = true, onCheckedChange = {})
                        }
                        Button(onClick = {}) {
                            Text("Log Session")
                        }
                    }
                }
            }
            """.trimIndent()
        )

        val generator = SmartAppImageGenerator()
        val result = generator.generateOrResolve(
            owner = "android",
            repo = "habit-streak",
            language = "Kotlin",
            description = "Jetpack Compose habit tracker",
            localRepoDir = tempDir,
            preferLocalBuild = true
        )

        assertEquals(SmartAppImageGenerator.ResultTier.LOCAL_BUILD_PREVIEW, result.tier)
        assertValidPng(result.bytes)
        assertTrue(result.originDescription.contains("Compose", ignoreCase = true) || result.originDescription.contains("Preview", ignoreCase = true))
    }

    @Test
    fun testTier2LinkedWebpageScreenshotSearch() {
        // Simulate a repo whose README has no image, but its GitHub-linked webpage (`homepageUrl`) hosts an app screenshot
        val samplePng = PureKotlinPngCanvas(240, 160).apply {
            fillVerticalGradient(0, 0, 240, 160, 0xFF0F172A.toInt(), 0xFF1E293B.toInt())
            fillRoundedRect(20, 20, 200, 120, 12, 0xFF38BDF8.toInt())
            drawText("WEB SCREENSHOT", 36, 60, 0xFFFFFFFF.toInt(), scale = 2)
        }.encodePng()

        val generator = SmartAppImageGenerator(
            httpFetcher = { url ->
                when {
                    url.contains("README.md") -> "# My CLI & GUI App\nVisit https://mycoolapp.dev for screenshots."
                    url.startsWith("https://mycoolapp.dev") -> """
                        <html>
                          <head><title>MyCoolApp</title></head>
                          <body>
                            <img src="/assets/hero-dashboard-screenshot.png" alt="App screenshot" />
                          </body>
                        </html>
                    """.trimIndent()
                    else -> null
                }
            },
            binaryFetcher = { url ->
                if (url == "https://mycoolapp.dev/assets/hero-dashboard-screenshot.png") samplePng else null
            }
        )

        val result = generator.generateOrResolve(
            owner = "cooldev",
            repo = "mycoolapp",
            language = "TypeScript",
            description = "Next-gen developer workbench",
            homepageUrl = "https://mycoolapp.dev",
            localRepoDir = null,
            preferLocalBuild = false
        )

        assertEquals(SmartAppImageGenerator.ResultTier.REPO_OR_WEBPAGE_SCREENSHOT, result.tier)
        assertValidPng(result.bytes)
        assertTrue(result.originDescription.contains("mycoolapp.dev") || result.originDescription.contains("Webpage", ignoreCase = true))
    }

    @Test
    fun testTier3DynamicSourceCodeSynthesisLastDitchEffort() {
        val generator = SmartAppImageGenerator(
            httpFetcher = { null },
            binaryFetcher = { null }
        )

        val sourceFiles = mapOf(
            "src/main.rs" to """
                use axum::{Router, routing::get};
                pub struct TelemetryServer {
                    pub port: u16,
                }
                pub async fn handle_metrics() -> &'static str {
                    "GET /api/v1/metrics -> 200 OK (#10B981)"
                }
            """.trimIndent()
        )

        val synthesized = generator.synthesizeFromSourceCode(
            owner = "tokio-rs",
            repo = "axum-telemetry",
            language = "Rust",
            description = "High-throughput async telemetry server",
            sourceFiles = sourceFiles
        )

        assertEquals(SmartAppImageGenerator.ResultTier.DYNAMIC_SOURCE_SYNTHESIS, synthesized.tier)
        assertValidPng(synthesized.bytes)
    }
}
