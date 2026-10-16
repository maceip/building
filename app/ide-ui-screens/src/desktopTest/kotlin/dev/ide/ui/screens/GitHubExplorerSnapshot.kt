package dev.ide.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.ide.ui.StubBackend
import dev.ide.ui.backend.GitHubDiscoveryService
import dev.ide.ui.backend.UiDiscoveredRepo
import dev.ide.ui.backend.UiDiscoveryFeed
import dev.ide.ui.backend.UiNorthStarBenchmark
import dev.ide.ui.backend.UiPeerProject
import dev.ide.ui.theme.CodeAssistTheme
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test

/**
 * Snapshot test rendering the GitHub Explorer feed off-screen in both Dark and Light modes.
 */
class GitHubExplorerSnapshot {

    private val sampleFeed = UiDiscoveryFeed(
        trendingHero = listOf(
            UiDiscoveredRepo(
                slug = "chethan616-clearpdf",
                owner = "Chethan616",
                name = "ClearPDF",
                description = "Modern Android document reader & editor without cloud trackers",
                stars = 4200,
                forks = 310,
                language = "Kotlin",
                htmlUrl = "https://github.com/Chethan616/ClearPDF",
                screenshotUrl = "https://raw.githubusercontent.com/Chethan616/ClearPDF/main/fastlane/metadata/android/en-US/images/phoneScreenshots/1.jpg",
                isZeroCloud = true,
                isOfflineFirst = true,
                usesCompose = true,
                usesNativeNdk = true,
                permissions = listOf("CAMERA", "READ_EXTERNAL_STORAGE"),
                featureTags = listOf("PDF", "OCR", "Offline-First")
            ),
            UiDiscoveredRepo(
                slug = "kushagrasinghx-bitchord",
                owner = "kushagrasinghx",
                name = "BitChord",
                description = "Aesthetic YouTube Music Client with Compose Multiplatform and lossless audio",
                stars = 6100,
                forks = 480,
                language = "Kotlin",
                htmlUrl = "https://github.com/kushagrasinghx/BitChord",
                screenshotUrl = "https://raw.githubusercontent.com/kushagrasinghx/BitChord/main/Banner.png",
                isZeroCloud = false,
                isOfflineFirst = false,
                usesCompose = true,
                usesNativeNdk = false,
                permissions = listOf("INTERNET", "FOREGROUND_SERVICE"),
                featureTags = listOf("Audio", "Compose-Multiplatform", "Lyrics")
            )
        ),
        northStarBenchmarks = listOf(
            UiNorthStarBenchmark(
                targetRepoName = "BitChord",
                northStarName = "Spotube",
                starMultiplierText = "14x more stars",
                matchPercentage = 94,
                whyRecommended = "Proven reference architecture: shares your Compose + Kotlin audio pipeline with battle-tested maturity.",
                privacyOrCapabilityAdvantage = "Operates 100% without cloud relays",
                northStarUrl = "https://github.com/KRTirtho/Spotube"
            )
        ),
        authorPeerPicks = listOf(
            UiPeerProject(
                slug = "torvalds/subsurface",
                name = "subsurface",
                owner = "torvalds",
                description = "Divelog program with native mobile support",
                stars = 2400,
                language = "C",
                attributionText = "Created by @torvalds, whom @antirez follows",
                htmlUrl = "https://github.com/torvalds/subsurface"
            )
        )
    )

    private class DiscoveryStubBackend(val feedData: UiDiscoveryFeed) : StubBackend() {
        private val mockScreenshotBytes: ByteArray by lazy {
            val surf = org.jetbrains.skia.Surface.makeRasterN32Premul(280, 130)
            val canvas = surf.canvas
            val paint = org.jetbrains.skia.Paint().apply { color = 0xFF1E293B.toInt() }
            canvas.drawRect(org.jetbrains.skia.Rect.makeWH(280f, 130f), paint)
            surf.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)!!.bytes
        }

        override val discovery: GitHubDiscoveryService = object : GitHubDiscoveryService {
            override suspend fun getFeed(topic: String, language: String, refresh: Boolean): UiDiscoveryFeed = feedData
            override suspend fun search(query: String): List<UiDiscoveredRepo> = feedData.trendingHero
            override suspend fun downloadScreenshot(url: String): String? = null
            override suspend fun loadScreenshotBytes(url: String): ByteArray? {
                // Return bitmap for BitChord to test dynamic image decoding, null for others to test fallback
                return if (url.contains("BitChord")) mockScreenshotBytes else null
            }
            override fun supported(): Boolean = true
        }
    }

    @Test
    fun renderDark() = snapshot("github-explorer-dark.png", dark = true)

    @Test
    fun renderLight() = snapshot("github-explorer-light.png", dark = false)

    @OptIn(ExperimentalComposeUiApi::class)
    private fun snapshot(name: String, dark: Boolean) {
        val backend = DiscoveryStubBackend(sampleFeed)
        val scene = ImageComposeScene(width = 824, height = 2400, density = Density(2f)) {
            CodeAssistTheme(dark = dark) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(vertical = 16.dp)) {
                    GitHubExplorerFeed(
                        backend = backend,
                        onCloneAndOpen = { _, _ -> },
                        initialFeed = sampleFeed
                    )
                }
            }
        }
        try {
            scene.render()
            for (frame in 1..20) scene.render(frame * 50_000_000L)
            val png = scene.render(2_000_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes
            File("$OUT_DIR/$name").apply { parentFile?.mkdirs() }.writeBytes(png)
        } finally {
            scene.close()
        }
    }

    private companion object {
        const val OUT_DIR = "build/reports/snapshots"
    }
}
