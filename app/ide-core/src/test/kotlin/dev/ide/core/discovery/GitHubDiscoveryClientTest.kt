package dev.ide.core.discovery

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GitHubDiscoveryClientTest {

    @Test
    fun testDiscoveryFeedGenerationWithFallbacks(@TempDir tempDir: Path) {
        val client = GitHubDiscoveryClient(
            token = null,
            apiBase = "http://127.0.0.1:9999", // Intentional offline endpoint with bundled fallback disabled
            cacheDir = tempDir,
            useBundledCatalogFallback = false
        )

        // When offline and bundled catalog fallback is disabled, getDiscoveryFeed must not fabricate fake apps
        val feed = client.getDiscoveryFeed(limit = 3)

        assertNotNull(feed)
        assertTrue(feed.trendingHero.isEmpty(), "Trending hero list must not contain fabricated apps when offline")
    }

    @Test
    fun testBundledBuildTimeCatalogCoversAllSupportedLanguagesAndPeriods(@TempDir tempDir: Path) {
        val catalog = BundledTrendingCatalog.loadCatalog()
        assertNotNull(catalog, "Build-time bundled trending catalog resource must be present on classpath")
        assertTrue(catalog.bundledAtIso.isNotBlank(), "Catalog must record bundledAtIso timestamp")

        for (lang in BundledTrendingCatalog.SUPPORTED_LANGUAGE_KEYS) {
            for (period in BundledTrendingCatalog.SUPPORTED_PERIODS) {
                val entries = BundledTrendingCatalog.getTrendingEntries(lang, period)
                assertTrue(
                    entries.isNotEmpty(),
                    "Bundled trending entries for ($lang, $period) must not be empty"
                )
                val first = entries.first()
                assertTrue(first.owner.isNotBlank())
                assertTrue(first.name.isNotBlank())
                val shotRes = assertNotNull(first.bundledScreenshotResource, "Bundled entry ${first.owner}/${first.name} must include a bundled screenshot resource")

                val bytes = BundledTrendingCatalog.loadBundledScreenshotBytes(shotRes)
                assertNotNull(bytes, "Bundled screenshot bytes for ${first.owner}/${first.name} must be loadable from classpath")
                assertTrue(bytes.size > 100, "Bundled screenshot must be non-trivial")
                val isPng = bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte()
                val isJpeg = bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()
                val isWebpOrGif = (bytes[0] == 0x52.toByte() && bytes[1] == 0x49.toByte()) || (bytes[0] == 0x47.toByte() && bytes[1] == 0x49.toByte())
                assertTrue(isPng || isJpeg || isWebpOrGif, "Bundled screenshot for ${first.owner}/${first.name} must have a valid raster image header")

                val materializedPath = BundledTrendingCatalog.materializeBundledScreenshot(shotRes, tempDir)
                assertNotNull(materializedPath)
                assertTrue(File(materializedPath).exists())
            }
        }
    }

    @Test
    fun testGetTrendingFeedServesBundledDailyWeeklyMonthly(@TempDir tempDir: Path) {
        val client = GitHubDiscoveryClient(cacheDir = tempDir, useBundledCatalogFallback = true)

        for (period in listOf("daily", "weekly", "monthly")) {
            val feed = client.getTrendingFeed(language = "kotlin", period = period, limit = 4, preferLive = false)
            assertTrue(feed.isFromBuildBundle, "Default trending feed should serve bundled build-time snapshot immediately")
            assertEquals(period, feed.period)
            assertEquals("kotlin", feed.selectedLanguage)
            assertTrue(feed.trendingHero.isNotEmpty(), "Trending hero list for kotlin:$period must not be empty")
            val top = feed.trendingHero.first()
            val localShot = assertNotNull(top.localScreenshotPath, "Bundled hero repo must have a materialized local screenshot path")
            assertTrue(File(localShot).exists(), "Materialized screenshot file must exist on disk")
        }
    }

    @Test
    fun testParseTrendingHtmlExtractsVelocityAndAvatars() {
        val sampleHtml = """
            <article class="Box-row">
              <h2 class="h3 lh-condensed">
                <a href="/JetBrains/compose-multiplatform">
                  <span>JetBrains /</span> compose-multiplatform
                </a>
              </h2>
              <p class="col-9 color-fg-muted my-1 pr-4">
                Declarative UI framework for Kotlin across Android, iOS, Desktop, and Web.
              </p>
              <div class="f6 color-fg-muted mt-2">
                <span itemprop="programmingLanguage">Kotlin</span>
                <a href="/JetBrains/compose-multiplatform/stargazers">18,450</a>
                <a href="/JetBrains/compose-multiplatform/forks">1,320</a>
                <span class="d-inline-block float-sm-right">412 stars this week</span>
                <img class="avatar mb-1 avatar-user" src="https://avatars.githubusercontent.com/u/878437?s=40&v=4" />
              </div>
            </article>
        """.trimIndent()

        val client = GitHubDiscoveryClient(useBundledCatalogFallback = false)
        val parsed = client.parseTrendingHtml(sampleHtml, defaultLanguage = "kotlin", period = "weekly")
        assertEquals(1, parsed.size)
        val repo = parsed.first()
        assertEquals("JetBrains", repo.owner)
        assertEquals("compose-multiplatform", repo.name)
        assertEquals("Kotlin", repo.language)
        assertEquals(18450, repo.stars)
        assertEquals(1320, repo.forks)
        assertEquals(412, repo.starsInPeriod)
        assertEquals("weekly", repo.trendingPeriod)
        assertTrue(repo.builtByAvatars.isNotEmpty())
    }

    @Test
    fun testAuthorPeerCircleAttributionAnd2HopGraphRanking() {
        val peer1 = DeveloperGraphEngine.PeerRepository(
            slug = "torvalds/subsurface",
            name = "subsurface",
            owner = "torvalds",
            description = "Divelog program",
            stars = 2400,
            language = "C",
            connectionReason = DeveloperGraphEngine.ConnectionType.FOLLOWED_PEER,
            connectedViaAuthor = "antirez",
            peerFollowerCount = 190000
        )
        assertEquals("Created by @torvalds, whom @antirez follows", DeveloperGraphEngine.formatAttribution(peer1))

        val peer2Hop = DeveloperGraphEngine.PeerRepository(
            slug = "chrisbanes/haze",
            name = "haze",
            owner = "chrisbanes",
            description = "Glassmorphism blur effect for Jetpack Compose Multiplatform",
            stars = 3100,
            language = "Kotlin",
            connectionReason = DeveloperGraphEngine.ConnectionType.SECOND_DEGREE_PEER,
            connectedViaAuthor = "JakeWharton",
            peerFollowerCount = 5000,
            socialChain = listOf("saket", "JakeWharton", "chrisbanes")
        )
        assertEquals("@saket → @JakeWharton → @chrisbanes", DeveloperGraphEngine.formatAttribution(peer2Hop))

        val peerStarred = DeveloperGraphEngine.PeerRepository(
            slug = "coil-kt/coil",
            name = "coil",
            owner = "coil-kt",
            description = "Image loading for Android and Compose Multiplatform in Kotlin",
            stars = 10900,
            language = "Kotlin",
            connectionReason = DeveloperGraphEngine.ConnectionType.PEER_STARRED,
            connectedViaAuthor = "JakeWharton",
            peerFollowerCount = 5000,
            socialChain = listOf("saket", "JakeWharton")
        )

        val unrelated = DeveloperGraphEngine.PeerRepository(
            slug = "someone/fortran-weather",
            name = "fortran-weather",
            owner = "someone",
            description = "Legacy atmospheric table parser",
            stars = 12,
            language = "Fortran",
            connectionReason = DeveloperGraphEngine.ConnectionType.SECOND_DEGREE_STARRED,
            connectedViaAuthor = "other",
            peerFollowerCount = 10,
            socialChain = listOf("saket", "other")
        )

        val ranked = DeveloperGraphEngine.rankAndFilterRelatedToApp(
            targetOwner = "saket",
            targetName = "telephoto",
            targetLanguage = "Kotlin",
            targetDescription = "Zoomable image composable for Jetpack Compose Multiplatform",
            targetTopics = listOf("compose", "kotlin", "image"),
            candidates = listOf(unrelated, peer2Hop, peerStarred),
            limit = 5
        )

        assertTrue(ranked.size >= 2)
        assertEquals("coil-kt/coil", ranked.first().slug, "Compose image library should rank highest for telephoto")
        assertTrue(ranked.first().relevanceReason.isNotBlank())
    }
}
