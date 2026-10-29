package dev.ide.android.discovery

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.ide.android.widget.TrendingReposAppWidgetProvider
import dev.ide.core.backend.DiscoveryBackend
import dev.ide.core.discovery.BundledTrendingCatalog
import dev.ide.core.discovery.DeveloperGraphEngine
import dev.ide.core.discovery.GitHubDiscoveryClient
import dev.ide.core.discovery.SmartAppImageGenerator
import dev.ide.ui.backend.UiScreenshotSourceTier
import dev.ide.ui.backend.UiTrendingPeriod
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File

/**
 * Extensive live end-to-end instrumented tests executed on the connected physical Android device
 * against real GitHub Trending (`https://github.com/trending/...`), live repository/webpage screenshot
 * extraction, on-device GUI/Compose preview synthesis, 2-hop developer social graph traversal,
 * `DiscoveryBackend`, and `TrendingReposAppWidgetProvider`.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class LiveGitHubTrendingDeviceTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private val testCacheDir: File
        get() = File(context.cacheDir, "live_github_trending_test").apply { mkdirs() }

    @Test
    fun test01_liveGitHubTrendingDailyWeeklyMonthlyAcrossPhoneLanguages() {
        val client = GitHubDiscoveryClient(cacheDir = testCacheDir.toPath())
        Log.i(TAG, "=== [TEST 01] Device=${Build.MANUFACTURER} ${Build.MODEL} (SDK ${Build.VERSION.SDK_INT}) ===")

        // 1. Verify Daily, Weekly, and Monthly live trending on key phone languages
        val periods = listOf("daily", "weekly", "monthly")
        val primaryLanguages = listOf("all", "kotlin", "swift", "python", "typescript", "rust")

        var totalLiveReposCrawled = 0
        for (period in periods) {
            for (lang in primaryLanguages) {
                val liveRepos = client.fetchLiveTrendingRepositories(
                    language = lang,
                    period = period,
                    limit = 5
                )
                Log.i(
                    TAG,
                    "LIVE GitHub Trending [lang=$lang, period=$period] -> ${liveRepos.size} repos: " +
                        liveRepos.joinToString { "${it.owner}/${it.name} (★${it.stars}, +${it.starsInPeriod}/${it.trendingPeriod})" }
                )
                assertTrue(
                    "Expected live trending repos from https://github.com/trending/$lang?since=$period",
                    liveRepos.isNotEmpty()
                )
                val top = liveRepos.first()
                assertTrue("Repo owner must not be blank", top.owner.isNotBlank())
                assertTrue("Repo name must not be blank", top.name.isNotBlank())
                assertTrue("Repo stars must be > 0 for ${top.owner}/${top.name}", top.stars > 0)
                assertEquals(period, top.trendingPeriod)
                totalLiveReposCrawled += liveRepos.size
            }
        }

        // 2. Also verify remaining supported phone languages ("javascript", "java", "c++") in daily trending
        for (extraLang in listOf("javascript", "java", "c++")) {
            val liveRepos = client.fetchLiveTrendingRepositories(
                language = extraLang,
                period = "daily",
                limit = 5
            )
            Log.i(
                TAG,
                "LIVE GitHub Trending [lang=$extraLang, period=daily] -> ${liveRepos.size} repos: " +
                    liveRepos.joinToString { "${it.owner}/${it.name} (★${it.stars}, +${it.starsInPeriod})" }
            )
            assertTrue("Expected live trending repos for language=$extraLang", liveRepos.isNotEmpty())
            totalLiveReposCrawled += liveRepos.size
        }

        Log.i(TAG, "=== [TEST 01 PASS] Total live trending repos crawled from GitHub on device: $totalLiveReposCrawled ===")
        assertTrue("Expected at least 80 live trending repo entries across languages/periods", totalLiveReposCrawled >= 80)
    }

    @Test
    fun test02_bundledBuildTimeCatalogAndAllScreenshotsDecodeOnAndroidArt() {
        val catalog = BundledTrendingCatalog.loadCatalog()
        assertNotNull("Build-time bundled catalog must be packaged in APK resources", catalog)
        val nonNullCatalog = catalog!!

        Log.i(
            TAG,
            "=== [TEST 02] Bundled Catalog bundledAt=${nonNullCatalog.bundledAtIso}, " +
                "languages=${BundledTrendingCatalog.SUPPORTED_LANGUAGE_KEYS.size}, " +
                "periods=${BundledTrendingCatalog.SUPPORTED_PERIODS}, lists=${nonNullCatalog.lists.size} ==="
        )
        assertEquals(27, nonNullCatalog.lists.size)

        var decodedBitmaps = 0
        val distinctResources = nonNullCatalog.lists.values
            .flatten()
            .mapNotNull { it.bundledScreenshotResource }
            .distinct()

        assertTrue("Expected at least 50 distinct bundled screenshots in APK", distinctResources.size >= 50)

        for (resPath in distinctResources) {
            val bytes = BundledTrendingCatalog.loadBundledScreenshotBytes(resPath)
            assertNotNull("Bundled screenshot bytes missing for $resPath", bytes)
            val bitmap = BitmapFactory.decodeByteArray(bytes!!, 0, bytes.size)
            assertNotNull("Android BitmapFactory failed to decode $resPath (${bytes.size} bytes)", bitmap)
            assertTrue("Expected width >= 120 for $resPath, got ${bitmap!!.width}", bitmap.width >= 120)
            assertTrue("Expected height >= 80 for $resPath, got ${bitmap.height}", bitmap.height >= 80)
            decodedBitmaps++
        }

        Log.i(TAG, "=== [TEST 02 PASS] Successfully decoded $decodedBitmaps build-time bundled screenshots on Android ART ===")
    }

    @Test
    fun test03_liveTier1LocalBuildPreviewForSwiftUiAndComposeAppsOnDevice() {
        val client = GitHubDiscoveryClient(cacheDir = testCacheDir.toPath())
        val generator = client.imageGenerator

        // 3A. Tier 1A: On-device SwiftUI -> SwiftUiPreviewInterpreter -> ViewNode GUI render
        val swiftSources = mapOf(
            "Sources/App/ContentView.swift" to """
                import SwiftUI
                struct ContentView: View {
                    var body: some View {
                        NavigationStack {
                            VStack(spacing: 14) {
                                Image(systemName: "sparkles")
                                Text("Live On-Device SwiftUI Preview")
                                    .font(.headline)
                                Text("Rendered via SwiftUiPreviewInterpreter on ${Build.MODEL}")
                                    .font(.subheadline)
                                HStack {
                                    Button("Build & Run") {}
                                    Button("Inspect AST") {}
                                }
                                Toggle("Realtime Telemetry", isOn: .constant(true))
                            }
                            .navigationTitle("SwiftUI Trending")
                        }
                    }
                }
            """.trimIndent()
        )

        val swiftImage = generator.generateOrResolve(
            owner = "live-device",
            repo = "swiftui-workbench",
            language = "Swift",
            description = "On-device SwiftUI workbench preview",
            localSourceFiles = swiftSources,
            preferLocalBuild = true
        )
        assertEquals(SmartAppImageGenerator.ResultTier.LOCAL_BUILD_PREVIEW, swiftImage.tier)
        val shotsOutDir = File(testCacheDir, "discovery/shots").apply { mkdirs() }
        File(shotsOutDir, "tier1_swiftui_ondevice.png").writeBytes(swiftImage.bytes)
        val swiftBitmap = BitmapFactory.decodeByteArray(swiftImage.bytes, 0, swiftImage.bytes.size)
        assertNotNull("Tier 1 SwiftUI preview must decode to valid Android Bitmap", swiftBitmap)
        assertEquals(560, swiftBitmap!!.width)
        assertEquals(320, swiftBitmap.height)
        Log.i(
            TAG,
            "=== [TEST 03A] Tier 1 SwiftUI Local Build Preview: ${swiftImage.originDescription} " +
                "(${swiftImage.bytes.size} bytes, ${swiftBitmap.width}x${swiftBitmap.height}) ==="
        )

        // 3B. Tier 1C: On-device Jetpack Compose @Preview renderer
        val composeSources = mapOf(
            "app/src/main/kotlin/TrendingDashboardScreen.kt" to """
                package ai.building.trending
                import androidx.compose.runtime.Composable
                import androidx.compose.ui.tooling.preview.Preview

                @Preview(showBackground = true)
                @Composable
                fun TrendingDashboardPreview() {
                    Scaffold(
                        topBar = { TopAppBar(title = { Text("GitHub Live Trending") }) }
                    ) {
                        Column {
                            Card {
                                Text("Daily + Weekly + Monthly Crawler")
                                Text("2-Hop Author Social Graph")
                            }
                            Row {
                                Button(onClick = {}) { Text("Clone & Run") }
                                Switch(checked = true, onCheckedChange = {})
                            }
                        }
                    }
                }
            """.trimIndent()
        )

        val composeImage = generator.generateOrResolve(
            owner = "live-device",
            repo = "compose-trending-dashboard",
            language = "Kotlin",
            description = "Jetpack Compose dashboard for live GitHub trending",
            localSourceFiles = composeSources,
            preferLocalBuild = true
        )
        assertEquals(SmartAppImageGenerator.ResultTier.LOCAL_BUILD_PREVIEW, composeImage.tier)
        File(shotsOutDir, "tier1_compose_ondevice.png").writeBytes(composeImage.bytes)
        val composeBitmap = BitmapFactory.decodeByteArray(composeImage.bytes, 0, composeImage.bytes.size)
        assertNotNull("Tier 1 Compose @Preview must decode to valid Android Bitmap", composeBitmap)
        assertEquals(560, composeBitmap!!.width)
        assertEquals(320, composeBitmap.height)
        Log.i(
            TAG,
            "=== [TEST 03B] Tier 1 Compose @Preview Local Build: ${composeImage.originDescription} " +
                "(${composeImage.bytes.size} bytes, ${composeBitmap.width}x${composeBitmap.height}) ==="
        )

        // 3C. Live remote SwiftUI repository fetched from GitHub -> built locally on device via Tier 1
        val liveRemoteSwift = client.resolveOrGenerateAppImage(
            owner = "AfrazCodes",
            repoName = "SwiftUI-Calculator",
            language = "Swift",
            description = "iOS Calculator built with SwiftUI",
            defaultBranch = "main",
            forceLocalBuild = true
        )
        assertNotNull("Expected live remote SwiftUI repo to generate image on device", liveRemoteSwift)
        val remoteFile = File(liveRemoteSwift!!.first)
        assertTrue("Generated file must exist on device storage", remoteFile.exists() && remoteFile.length() > 500)
        val remoteBitmap = BitmapFactory.decodeFile(remoteFile.absolutePath)
        assertNotNull("Generated file must decode as valid Android Bitmap", remoteBitmap)
        Log.i(
            TAG,
            "=== [TEST 03C] Live Remote Repo Local Build (AfrazCodes/SwiftUI-Calculator): " +
                "tier=${liveRemoteSwift.second}, path=${remoteFile.absolutePath}, size=${remoteFile.length()}B, " +
                "bitmap=${remoteBitmap!!.width}x${remoteBitmap.height} ==="
        )
    }

    @Test
    fun test04_liveTier2RepoAndLinkedWebpageScreenshotExtractionAgainstRealGitHub() {
        val client = GitHubDiscoveryClient(cacheDir = testCacheDir.toPath())
        val generator = client.imageGenerator

        // Fetch live trending repos right now and test Tier 2 extraction on a real repo with README/webpage images
        val liveDailyAll = client.fetchLiveTrendingRepositories(language = "all", period = "daily", limit = 8)
        assertTrue("Expected live daily trending repos", liveDailyAll.isNotEmpty())

        var tier2Extracted: SmartAppImageGenerator.GeneratedAppImage? = null
        var testedRepoSlug = ""

        for (candidate in liveDailyAll) {
            val attempt = generator.tryFindRepoOrWebpageScreenshot(
                owner = candidate.owner,
                repo = candidate.name,
                defaultBranch = candidate.defaultBranch,
                homepageUrl = null
            )
            if (attempt != null) {
                tier2Extracted = attempt
                testedRepoSlug = "${candidate.owner}/${candidate.name}"
                break
            }
        }

        // Also test explicit linked homepageUrl webpage scraping if any trending repos were text-only
        if (tier2Extracted == null) {
            tier2Extracted = generator.tryFindRepoOrWebpageScreenshot(
                owner = "JetBrains",
                repo = "compose-multiplatform",
                defaultBranch = "master",
                homepageUrl = "https://www.jetbrains.com/lp/compose-multiplatform/"
            )
            testedRepoSlug = "JetBrains/compose-multiplatform"
        }

        assertNotNull("Expected Tier 2 screenshot extraction to succeed on live GitHub repo", tier2Extracted)
        val shot = tier2Extracted!!
        assertEquals(SmartAppImageGenerator.ResultTier.REPO_OR_WEBPAGE_SCREENSHOT, shot.tier)
        val bitmap = BitmapFactory.decodeByteArray(shot.bytes, 0, shot.bytes.size)
        assertNotNull("Live extracted screenshot from $testedRepoSlug must decode on Android", bitmap)
        assertTrue("Extracted bitmap width (${bitmap!!.width}) must be >= 120", bitmap.width >= 120)
        assertTrue("Extracted bitmap height (${bitmap.height}) must be >= 60", bitmap.height >= 60)

        Log.i(
            TAG,
            "=== [TEST 04 PASS] Live Tier 2 Screenshot Extracted for $testedRepoSlug: " +
                "origin='${shot.originDescription}', url=${shot.sourceUrlOrPath}, " +
                "bytes=${shot.bytes.size}, bitmap=${bitmap.width}x${bitmap.height} ==="
        )
    }

    @Test
    fun test05_liveTier3DynamicSourceCodeSynthesisAgainstRealTrendingRepo() {
        val client = GitHubDiscoveryClient(cacheDir = testCacheDir.toPath())
        val liveRust = client.fetchLiveTrendingRepositories(language = "rust", period = "daily", limit = 3)
        assertTrue("Expected live Rust trending repos", liveRust.isNotEmpty())
        val target = liveRust.first()

        // Invoke Tier-3 source synthesis directly with live source inspection from the trending repo
        val generator = client.imageGenerator
        val synthesized = generator.synthesizeFromSourceCode(
            owner = target.owner,
            repo = target.name,
            language = target.language,
            description = target.description,
            sourceFiles = mapOf(
                "src/main.rs" to """
                    pub struct TrendingRuntimeEngine {
                        pub stars: u64,
                        pub period_velocity: u64,
                    }
                    pub fn execute_pipeline() -> Result<(), String> {
                        println!("Running ${target.owner}/${target.name} on Android");
                        Ok(())
                    }
                """.trimIndent()
            )
        )

        assertEquals(SmartAppImageGenerator.ResultTier.DYNAMIC_SOURCE_SYNTHESIS, synthesized.tier)
        val shotsOutDir = File(testCacheDir, "discovery/shots").apply { mkdirs() }
        File(shotsOutDir, "tier3_source_synth_ondevice.png").writeBytes(synthesized.bytes)
        val bitmap = BitmapFactory.decodeByteArray(synthesized.bytes, 0, synthesized.bytes.size)
        assertNotNull("Tier 3 synthesized PNG must decode to valid Android Bitmap", bitmap)
        assertEquals(560, bitmap!!.width)
        assertEquals(320, bitmap.height)

        Log.i(
            TAG,
            "=== [TEST 05 PASS] Tier 3 Dynamic Source Synthesis for live repo ${target.owner}/${target.name}: " +
                "origin='${synthesized.originDescription}', bytes=${synthesized.bytes.size}, " +
                "bitmap=${bitmap.width}x${bitmap.height} ==="
        )
    }

    @Test
    fun test06_live2HopAuthorSocialGraphAndStarredReposCrawlerOnDevice() {
        val client = GitHubDiscoveryClient(cacheDir = testCacheDir.toPath())

        // Pick a live trending repository authored by an individual developer (or #1 weekly Kotlin)
        val liveKotlin = client.fetchLiveTrendingRepositories(language = "kotlin", period = "weekly", limit = 5)
        assertTrue("Expected live weekly Kotlin trending repos", liveKotlin.isNotEmpty())
        val selectedApp = liveKotlin.firstOrNull { !it.owner.equals("android", ignoreCase = true) } ?: liveKotlin.first()

        Log.i(
            TAG,
            "=== [TEST 06] Crawling live 2-hop author social graph for ${selectedApp.owner}/${selectedApp.name} " +
                "(★${selectedApp.stars}, +${selectedApp.starsInPeriod}/weekly) ==="
        )

        val network = client.crawlRelatedAndAuthorNetwork(
            owner = selectedApp.owner,
            repoName = selectedApp.name,
            language = selectedApp.language,
            description = selectedApp.description,
            topics = selectedApp.topics,
            limit = 16
        )

        assertTrue("Expected non-empty related & author social graph network", network.isNotEmpty())
        val distinctReasons = network.map { it.connectionReason }.toSet()
        Log.i(
            TAG,
            "Crawled ${network.size} related/peer repos for ${selectedApp.owner}/${selectedApp.name} " +
                "across connection types: $distinctReasons"
        )
        for ((idx, peer) in network.withIndex()) {
            val attribution = DeveloperGraphEngine.formatAttribution(peer)
            Log.i(
                TAG,
                "  [${idx + 1}] ${peer.slug} (★${peer.stars}, lang=${peer.language}) " +
                    "reason=${peer.connectionReason}, chain=${peer.socialChain.joinToString(" -> ")}, " +
                    "score=${peer.relevanceScore}, attribution='$attribution', why='${peer.relevanceReason}'"
            )
            assertFalse(
                "Target repo itself must not appear in its own related list",
                peer.slug.equals("${selectedApp.owner}/${selectedApp.name}", ignoreCase = true)
            )
            assertTrue("Peer repo slug must be valid owner/name", peer.slug.contains("/"))
            assertTrue("Attribution must not be blank", attribution.isNotBlank())
        }

        // Also verify full 2-hop social graph traversal on @jakewharton (`@jakewharton -> @peer1 -> @peer2` + stars)
        val jakeNetwork = client.crawlRelatedAndAuthorNetwork(
            owner = "JakeWharton",
            repoName = "mosaic",
            language = "Kotlin",
            description = "Terminal UI library powered by Jetpack Compose runtime",
            limit = 14
        )
        val jakeReasons = jakeNetwork.map { it.connectionReason }.toSet()
        Log.i(
            TAG,
            "=== [TEST 06B] Crawled ${jakeNetwork.size} 2-hop social graph repos for JakeWharton/mosaic " +
                "across connection types: $jakeReasons ==="
        )
        for ((idx, peer) in jakeNetwork.withIndex()) {
            Log.i(
                TAG,
                "  [JW ${idx + 1}] ${peer.slug} (★${peer.stars}, lang=${peer.language}) " +
                    "reason=${peer.connectionReason}, chain=${peer.socialChain.joinToString(" -> ")}, score=${peer.relevanceScore}"
            )
        }
        assertTrue(
            "Expected multiple social connection types (author repos, stars, 1st-hop or 2nd-hop peers) for JakeWharton/mosaic, got $jakeReasons",
            jakeReasons.size >= 2
        )
    }

    @Test
    fun test07_discoveryBackendLiveEndToEndOnDevice() {
        runBlocking {
            val backend = DiscoveryBackend(
                client = GitHubDiscoveryClient(cacheDir = testCacheDir.toPath()),
                cacheDir = testCacheDir.toPath()
            )

            // 1. Supported phone languages check
            val langs = backend.supportedLanguages()
            assertEquals(9, langs.size)

            // 2. Fast initial load from build-time bundle (`refresh = false`)
            val bundledSwiftDaily = backend.getTrendingFeed(
                language = "swift",
                period = UiTrendingPeriod.DAILY,
                refresh = false
            )
            assertTrue("Bundled Swift daily feed must not be empty", bundledSwiftDaily.trendingHero.isNotEmpty())
            assertTrue("Expected isFromBuildBundle=true when refresh=false", bundledSwiftDaily.isFromBuildBundle)

            // 3. Live refresh against real GitHub Trending (`refresh = true`)
            val liveKotlinWeekly = backend.getTrendingFeed(
                language = "kotlin",
                period = UiTrendingPeriod.WEEKLY,
                refresh = true
            )
            assertTrue("Live Kotlin weekly feed must not be empty", liveKotlinWeekly.trendingHero.isNotEmpty())
            assertFalse("Expected isFromBuildBundle=false when refresh=true", liveKotlinWeekly.isFromBuildBundle)
            assertEquals(UiTrendingPeriod.WEEKLY, liveKotlinWeekly.period)

            val topRepo = liveKotlinWeekly.trendingHero.first()
            Log.i(
                TAG,
                "=== [TEST 07] DiscoveryBackend Live Weekly Kotlin #1: ${topRepo.owner}/${topRepo.name} " +
                    "(★${topRepo.stars}, +${topRepo.starsInPeriod} this week) ==="
            )

            // 4. Live 2-hop social graph via DiscoveryBackend
            val relatedPeers = backend.getRelatedAndAuthorNetwork(topRepo, refresh = true)
            assertTrue("Expected related peers from DiscoveryBackend", relatedPeers.isNotEmpty())

            // 5. On-demand JIT image generation via DiscoveryBackend
            val resolvedShot = backend.resolveOrGenerateAppImage(topRepo, forceLocalBuild = true)
            assertNotNull("Expected DiscoveryBackend.resolveOrGenerateAppImage to return valid path & tier", resolvedShot)
            val shotBytes = backend.loadScreenshotBytes(resolvedShot!!.first)
            assertNotNull("Expected loadScreenshotBytes to load generated PNG bytes", shotBytes)
            val decoded = BitmapFactory.decodeByteArray(shotBytes!!, 0, shotBytes.size)
            assertNotNull("Expected generated PNG bytes to decode to Android Bitmap", decoded)

            Log.i(
                TAG,
                "=== [TEST 07 PASS] DiscoveryBackend resolved image for ${topRepo.owner}/${topRepo.name}: " +
                    "tier=${resolvedShot.second}, path=${resolvedShot.first}, bitmap=${decoded!!.width}x${decoded.height} ==="
            )

            // 6. Integrated README.md verification (both bundled App README.md and live repo README.md)
            val appReadme = backend.getBundledAppReadmeMarkdown()
            assertTrue("Expected bundled App README.md to contain Smart GitHub Trending section", appReadme.contains("Smart GitHub Trending"))
            val repoReadme = backend.fetchReadmeMarkdown(topRepo)
            assertTrue("Expected live repo README.md to be non-blank", repoReadme.isNotBlank())
            Log.i(
                TAG,
                "=== [TEST 07B PASS] Integrated README.md verified: appReadme=${appReadme.length} chars, " +
                    "repoReadme(${topRepo.owner}/${topRepo.name})=${repoReadme.length} chars ==="
            )
        }
    }

    @Test
    fun test08_trendingReposAppWidgetProviderLiveBroadcastsOnDevice() {
        val prefs = context.getSharedPreferences("trending_repos_widget_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("period", "daily").putString("language", "all").commit()

        val provider = TrendingReposAppWidgetProvider()

        // Cycle period: daily -> weekly -> monthly -> daily
        provider.onReceive(
            context,
            Intent(context, TrendingReposAppWidgetProvider::class.java).apply {
                action = TrendingReposAppWidgetProvider.ACTION_CYCLE_PERIOD
            }
        )
        assertEquals("weekly", prefs.getString("period", null))

        provider.onReceive(
            context,
            Intent(context, TrendingReposAppWidgetProvider::class.java).apply {
                action = TrendingReposAppWidgetProvider.ACTION_CYCLE_PERIOD
            }
        )
        assertEquals("monthly", prefs.getString("period", null))

        // Cycle language: all -> kotlin -> swift
        provider.onReceive(
            context,
            Intent(context, TrendingReposAppWidgetProvider::class.java).apply {
                action = TrendingReposAppWidgetProvider.ACTION_CYCLE_LANGUAGE
            }
        )
        assertEquals("kotlin", prefs.getString("language", null))

        provider.onReceive(
            context,
            Intent(context, TrendingReposAppWidgetProvider::class.java).apply {
                action = TrendingReposAppWidgetProvider.ACTION_CYCLE_LANGUAGE
            }
        )
        assertEquals("swift", prefs.getString("language", null))

        Log.i(TAG, "=== [TEST 08 PASS] TrendingReposAppWidgetProvider period & language cycling verified on device ===")
    }

    @Test
    fun test09_androidAiCoreAndLiteRtLmCornerOnDevice() {
        // Bring MainActivity into the foreground in-process so Android AI Core (com.google.android.aicore)
        // permits foreground EdgeTPU / Gemini Nano session creation.
        context.startActivity(
            Intent(context, dev.ide.android.MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
        )
        Thread.sleep(2000L)

        dev.ide.android.litert.AndroidAiCoreBridge.install(context)
        val bridge = dev.ide.android.litert.AndroidAiCoreBridge(context)
        val status = bridge.probeStatus()
        Log.i(
            TAG,
            "=== [TEST 09] AndroidAiCoreBridge status on Pixel: available=${status.available}, " +
                "pkg=${status.packageName}, version=${status.versionName}, odi=${status.systemServicePresent} ==="
        )
        assertTrue("Expected Android AI Core (com.google.android.aicore) to be detected on Pixel 11 Pro XL", status.available)

        val storageRoot = File(testCacheDir, "litert-corner").apply { mkdirs() }.toPath()
        val manager = dev.ide.agent.impl.LiteRtLmServingManager(storageRoot = storageRoot)
        try {
            val snap = manager.currentSnapshot()
            val aiCoreEntry = snap.entries.firstOrNull { it.spec.id == dev.ide.agent.impl.LiteRtLmCatalog.AICORE_MODEL_ID }
            assertNotNull("Expected Android AI Core entry in LiteRT-LM Corner catalog on Pixel", aiCoreEntry)
            val smokeResult = manager.runSmokeTestNow("Write a one-line Kotlin greeting function")
            Log.i(
                TAG,
                "=== [TEST 09 PASS] LiteRT-LM Corner & Android AI Core smoke test: ok=${smokeResult.ok}, " +
                    "model=${smokeResult.modelId}, latency=${smokeResult.latencyMs}ms, text=${smokeResult.text} ==="
            )
            assertTrue("Expected on-device AICore / LiteRT-LM smoke test to succeed", smokeResult.ok)
            assertTrue("Expected non-blank generated response", smokeResult.text.isNotBlank())
        } finally {
            manager.close()
        }
    }

    companion object {
        private const val TAG = "LiveGitHubTrendingTest"
    }
}
