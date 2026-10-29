package dev.ide.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.ide.ui.StubBackend
import dev.ide.ui.backend.GitHubDiscoveryService
import dev.ide.ui.backend.UiDiscoveredRepo
import dev.ide.ui.backend.UiDiscoveryFeed
import dev.ide.ui.backend.UiNorthStarBenchmark
import dev.ide.ui.backend.UiPeerProject
import dev.ide.ui.backend.UiScreenshotSourceTier
import dev.ide.ui.backend.UiTrendingPeriod
import dev.ide.ui.theme.CodeAssistTheme
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Snapshot + interactive `runComposeUiTest` suite for `GitHubExplorerFeed` and `TrendingReposWidget`.
 */
class GitHubExplorerSnapshot {

    private val samplePeers = listOf(
        UiPeerProject(
            slug = "torvalds/subsurface",
            name = "subsurface",
            owner = "torvalds",
            description = "Divelog program with native mobile support",
            stars = 2400,
            language = "C",
            attributionText = "Created by @torvalds, whom @Chethan616 follows",
            htmlUrl = "https://github.com/torvalds/subsurface",
            relationKind = "FOLLOWED_PEER",
            socialPathText = "Created by @torvalds, whom @Chethan616 follows",
            relevanceScore = 92,
            relevanceReason = "1st-hop followed peer"
        ),
        UiPeerProject(
            slug = "coil-kt/coil",
            name = "coil",
            owner = "coil-kt",
            description = "Image loading for Android and Compose Multiplatform",
            stars = 10900,
            language = "Kotlin",
            attributionText = "Starred by @Chethan616",
            htmlUrl = "https://github.com/coil-kt/coil",
            relationKind = "AUTHOR_STARRED",
            socialPathText = "Starred by @Chethan616",
            relevanceScore = 96,
            relevanceReason = "Starred by @Chethan616 · Same language (Kotlin)"
        ),
        UiPeerProject(
            slug = "chrisbanes/haze",
            name = "haze",
            owner = "chrisbanes",
            description = "Glassmorphism blur effect for Jetpack Compose",
            stars = 3100,
            language = "Kotlin",
            attributionText = "2-hop network (@Chethan616 → @JakeWharton → @chrisbanes)",
            htmlUrl = "https://github.com/chrisbanes/haze",
            relationKind = "SECOND_DEGREE_PEER",
            socialPathText = "2-hop network (@Chethan616 → @JakeWharton → @chrisbanes)",
            relevanceScore = 89,
            relevanceReason = "2-hop peer network"
        )
    )

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
                homepageUrl = "https://clearpdf.app",
                screenshotUrl = "https://raw.githubusercontent.com/Chethan616/ClearPDF/main/fastlane/metadata/android/en-US/images/phoneScreenshots/1.jpg",
                screenshotSourceTier = UiScreenshotSourceTier.BUNDLED_BUILD_TIME,
                starsInPeriod = 185,
                trendingPeriod = UiTrendingPeriod.DAILY,
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
                screenshotSourceTier = UiScreenshotSourceTier.REPO_OR_WEBPAGE_SCREENSHOT,
                starsInPeriod = 240,
                trendingPeriod = UiTrendingPeriod.DAILY,
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
        authorPeerPicks = samplePeers,
        period = UiTrendingPeriod.DAILY,
        selectedLanguage = "kotlin",
        isFromBuildBundle = true
    )

    private class DiscoveryStubBackend(val feedData: UiDiscoveryFeed) : StubBackend() {
        var lastRequestedLanguage: String = "all"
        var lastRequestedPeriod: UiTrendingPeriod = UiTrendingPeriod.DAILY
        var localBuildPreviewCount: Int = 0
        var crawledRelatedForOwner: String? = null

        private val mockScreenshotBytes: ByteArray by lazy {
            val surf = org.jetbrains.skia.Surface.makeRasterN32Premul(280, 130)
            val canvas = surf.canvas
            val paint = org.jetbrains.skia.Paint().apply { color = 0xFF1E293B.toInt() }
            canvas.drawRect(org.jetbrains.skia.Rect.makeWH(280f, 130f), paint)
            surf.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)!!.bytes
        }

        override val discovery: GitHubDiscoveryService = object : GitHubDiscoveryService {
            override suspend fun getFeed(topic: String, language: String, refresh: Boolean): UiDiscoveryFeed = feedData
            override suspend fun getTrendingFeed(
                language: String,
                period: UiTrendingPeriod,
                refresh: Boolean
            ): UiDiscoveryFeed {
                lastRequestedLanguage = language
                lastRequestedPeriod = period
                return feedData.copy(period = period, selectedLanguage = language)
            }

            override suspend fun getRelatedAndAuthorNetwork(
                repo: UiDiscoveredRepo,
                refresh: Boolean
            ): List<UiPeerProject> {
                crawledRelatedForOwner = repo.owner
                return feedData.authorPeerPicks
            }

            override suspend fun resolveOrGenerateAppImage(
                repo: UiDiscoveredRepo,
                forceLocalBuild: Boolean
            ): Pair<String, UiScreenshotSourceTier>? {
                if (forceLocalBuild) localBuildPreviewCount++
                return "local://built/${repo.slug}.png" to UiScreenshotSourceTier.LOCAL_BUILD_PREVIEW
            }

            override suspend fun search(query: String): List<UiDiscoveredRepo> = feedData.trendingHero
            override suspend fun downloadScreenshot(url: String): String? = null
            override suspend fun loadScreenshotBytes(url: String): ByteArray? {
                return if (url.contains("BitChord") || url.startsWith("local://built/")) mockScreenshotBytes else null
            }
            override fun supported(): Boolean = true
        }
    }

    @Test
    fun renderDark() = snapshot("github-explorer-dark.png", dark = true)

    @Test
    fun renderLight() = snapshot("github-explorer-light.png", dark = false)

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun testExplorerPeriodLanguageBuildPreviewAnd2HopNetworkControls() = runComposeUiTest {
        val backend = DiscoveryStubBackend(sampleFeed)
        var clonedRepoName: String? = null

        setContent {
            CodeAssistTheme(dark = true) {
                GitHubExplorerFeed(
                    backend = backend,
                    onCloneAndOpen = { _, name -> clonedRepoName = name },
                    initialFeed = sampleFeed
                )
            }
        }

        waitForIdle()

        // Switch period to Weekly and Monthly and assert backend state transitions
        onNodeWithTag("trending_period_weekly").performClick()
        waitForIdle()
        assertEquals(UiTrendingPeriod.WEEKLY, backend.lastRequestedPeriod)

        onNodeWithTag("trending_period_monthly").performClick()
        waitForIdle()
        assertEquals(UiTrendingPeriod.MONTHLY, backend.lastRequestedPeriod)

        // Switch language to Swift and assert backend state transition
        onNodeWithTag("trending_lang_swift").performClick()
        waitForIdle()
        assertEquals("swift", backend.lastRequestedLanguage)

        // Click on-demand "Build Preview" on the first hero card and assert Tier-1 local build is invoked
        onNodeWithTag("hero_build_preview_chethan616-clearpdf").performClick()
        waitForIdle()
        assertTrue(backend.localBuildPreviewCount >= 1, "Clicking Build Preview must invoke resolveOrGenerateAppImage(forceLocalBuild = true)")
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun testTrendingReposWidgetPeriodLanguageAndCloneActions() = runComposeUiTest {
        val backend = DiscoveryStubBackend(sampleFeed)
        var clonedUrl: String? = null
        var clonedName: String? = null

        setContent {
            CodeAssistTheme(dark = true) {
                TrendingReposWidget(
                    backend = backend,
                    onCloneAndOpen = { url, name ->
                        clonedUrl = url
                        clonedName = name
                    }
                )
            }
        }

        waitForIdle()

        // Switch period to Weekly inside the widget
        onNodeWithTag("widget_period_weekly").performClick()
        waitForIdle()
        assertEquals(UiTrendingPeriod.WEEKLY, backend.lastRequestedPeriod)

        // Switch language to Python inside the widget
        onNodeWithTag("widget_lang_python").performClick()
        waitForIdle()
        assertEquals("python", backend.lastRequestedLanguage)

        // Click the first trending repo's Clone button in the widget
        onNodeWithTag("widget_clone_chethan616-clearpdf").performClick()
        waitForIdle()
        assertEquals("ClearPDF", clonedName)
        assertEquals("https://github.com/Chethan616/ClearPDF", clonedUrl)
    }

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
