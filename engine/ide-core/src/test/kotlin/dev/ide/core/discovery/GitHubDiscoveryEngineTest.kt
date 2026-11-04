package dev.ide.core.discovery

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GitHubDiscoveryEngineTest {

    @Test
    fun testScreenshotExtractorTiersAndFallbacks() {
        val sampleReadme = """
            # LocalSend
            [![CI status](https://img.shields.io/github/workflow/status/ci.svg)](https://ci)
            [![Coverage](https://codecov.io/gh/coverage/badge.svg)](https://coverage)
            
            <img src="https://localsend.org/img/screenshot-iphone.webp" alt="iPhone screenshot" height="300"/>
            <img src="https://localsend.org/img/screenshot-pc.webp" alt="PC screenshot" height="300"/>
            
            ![Architecture Diagram](docs/assets/architecture-preview.png)
        """.trimIndent()

        val sampleFileTree = listOf(
            "fastlane/metadata/android/en-US/images/phoneScreenshots/01_home.png",
            "fastlane/metadata/android/en-US/images/phoneScreenshots/02_transfer.png",
            "assets/screenshots/demo.jpg",
            "app/src/main/res/mipmap-xxxhdpi/ic_launcher.png"
        )

        // Tier 0: Fallback OpenGraph
        val tier0Candidates = ScreenshotExtractor.resolveScreenshots(
            owner = "localsend",
            repo = "localsend",
            maxTier = ScreenshotExtractor.AggressivenessTier.TIER_0_OPENGRAPH
        )
        assertEquals(1, tier0Candidates.size)
        assertEquals("https://opengraph.githubassets.com/1/localsend/localsend", tier0Candidates.first().url)
        assertEquals(ScreenshotExtractor.AggressivenessTier.TIER_0_OPENGRAPH, tier0Candidates.first().tier)

        // Tier 1 & 2: Fastlane + README images
        val fullCandidates = ScreenshotExtractor.resolveScreenshots(
            owner = "localsend",
            repo = "localsend",
            readmeContent = sampleReadme,
            fileTreePaths = sampleFileTree,
            maxTier = ScreenshotExtractor.AggressivenessTier.TIER_2_README_SCRAPE
        )

        assertTrue(fullCandidates.isNotEmpty())
        // Fastlane screenshots should have highest confidence
        val topCandidate = fullCandidates.first()
        assertEquals(ScreenshotExtractor.AggressivenessTier.TIER_1_STORE_METADATA, topCandidate.tier)
        assertTrue(topCandidate.url.contains("fastlane/metadata/android/en-US/images/phoneScreenshots/01_home.png"))

        // Verify README extracted images are present and badges are excluded
        val urls = fullCandidates.map { it.url }
        assertTrue(urls.any { it.contains("screenshot-iphone.webp") })
        assertTrue(urls.any { it.contains("architecture-preview.png") })
        assertFalse(urls.any { it.contains("shields.io") })
        assertFalse(urls.any { it.contains("codecov.io") })
    }

    @Test
    fun testRepoShapeEngineNorthStarAndVelocityLenses() {
        // Target: small student/indie app (500 stars, Compose Multiplatform, local offline first)
        val targetGenome = RepoShapeEngine.RepoGenome(
            toolchain = RepoShapeEngine.BuildToolchain.GRADLE_KMP,
            uiFramework = RepoShapeEngine.UiFramework.COMPOSE_MULTIPLATFORM,
            topology = RepoShapeEngine.Topology.LOCAL_OFFLINE_FIRST,
            topics = setOf("compose", "offline-first", "p2p", "file-sharing"),
            primaryLanguage = "Kotlin",
            stars = 500
        )

        // Candidate 1: LocalSend (58k stars, identical stack and topic domain -> North Star)
        val localSendGenome = RepoShapeEngine.RepoGenome(
            toolchain = RepoShapeEngine.BuildToolchain.GRADLE_KMP,
            uiFramework = RepoShapeEngine.UiFramework.COMPOSE_MULTIPLATFORM,
            topology = RepoShapeEngine.Topology.LOCAL_OFFLINE_FIRST,
            topics = setOf("compose", "offline-first", "p2p", "airdrop", "file-transfer"),
            primaryLanguage = "Kotlin",
            stars = 58000,
            onDeviceRunnableScore = 0.95
        )

        // Candidate 2: High-velocity challenger (recent growth, active commits)
        val risingChallengerGenome = RepoShapeEngine.RepoGenome(
            toolchain = RepoShapeEngine.BuildToolchain.GRADLE_KMP,
            uiFramework = RepoShapeEngine.UiFramework.COMPOSE_MULTIPLATFORM,
            topology = RepoShapeEngine.Topology.LOCAL_OFFLINE_FIRST,
            topics = setOf("compose", "file-sharing", "p2p"),
            primaryLanguage = "Kotlin",
            stars = 3200,
            starsLastMonth = 450,
            daysSinceLastCommit = 2,
            onDeviceRunnableScore = 0.95
        )

        // Test North Star lens
        val northStarMatch = RepoShapeEngine.evaluateComparison(
            targetSlug = "myuser/my-share-app",
            targetGenome = targetGenome,
            candidateSlug = "localsend/localsend",
            candidateGenome = localSendGenome,
            lens = RepoShapeEngine.ComparisonLens.NORTH_STAR
        )
        assertNotNull(northStarMatch)
        assertEquals("localsend/localsend", northStarMatch.candidateSlug)
        assertTrue(northStarMatch.structuralMatchScore >= 0.70)
        assertTrue(northStarMatch.starDifferential.contains("x more stars"))

        // Test High-Velocity lens
        val velocityMatch = RepoShapeEngine.evaluateComparison(
            targetSlug = "myuser/my-share-app",
            targetGenome = targetGenome,
            candidateSlug = "rising/quickshare-kmp",
            candidateGenome = risingChallengerGenome,
            lens = RepoShapeEngine.ComparisonLens.HIGH_VELOCITY
        )
        assertNotNull(velocityMatch)
        assertEquals("rising/quickshare-kmp", velocityMatch.candidateSlug)
        assertTrue(velocityMatch.whyRecommended.contains("Surging in momentum"))
    }

    @Test
    fun testCapabilityAnalyzerAndFeatureDiff() {
        val manifestLocalSend = """
            <manifest package="org.localsend">
                <uses-permission android:name="android.permission.INTERNET" />
                <uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
                <uses-permission android:name="android.permission.NEARBY_WIFI_DEVICES" />
            </manifest>
        """.trimIndent()

        val manifestCloudApp = """
            <manifest package="com.cloud.storage">
                <uses-permission android:name="android.permission.INTERNET" />
                <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
            </manifest>
        """.trimIndent()

        val profileLocalSend = CapabilityAnalyzer.analyze(
            manifestXml = manifestLocalSend,
            readmeContent = "LocalSend is an open-source cross-platform alternative to AirDrop. 100% offline-first with end-to-end encryption and no telemetry."
        )

        val profileCloudApp = CapabilityAnalyzer.analyze(
            manifestXml = manifestCloudApp,
            readmeContent = "Cloud Sync Client connecting to our AWS backend servers."
        )

        assertTrue(profileLocalSend.isZeroCloud)
        assertTrue(profileLocalSend.isOfflineFirst)
        assertTrue(profileLocalSend.coreFeatureTags.contains("100% Zero-Cloud / Private"))
        assertTrue(profileLocalSend.coreFeatureTags.contains("End-to-End Encryption"))
        assertFalse(profileCloudApp.isZeroCloud)

        val diff = CapabilityAnalyzer.diffCapabilities(
            slugA = "LocalSend",
            profileA = profileLocalSend,
            slugB = "CloudSync",
            profileB = profileCloudApp
        )

        assertNotNull(diff.privacyAdvantage)
        assertTrue(diff.privacyAdvantage.contains("LocalSend operates 100% offline without cloud relays"))
    }

    @Test
    fun testDeveloperGraphEngineAttribution() {
        val peerProject = DeveloperGraphEngine.PeerRepository(
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

        val attribution = DeveloperGraphEngine.formatAttribution(peerProject)
        assertEquals("Created by @torvalds, whom @antirez follows", attribution)
    }

    @Test
    fun testUnifiedGitHubDiscoveryEngine() {
        val engine = GitHubDiscoveryEngine()

        val discovered = engine.enrichRepository(
            owner = "localsend",
            name = "localsend",
            description = "An open-source cross-platform alternative to AirDrop",
            stars = 58200,
            forks = 3900,
            language = "Dart",
            htmlUrl = "https://github.com/localsend/localsend",
            readmeContent = "<img src=\"https://localsend.org/img/screenshot-iphone.webp\" alt=\"iPhone screenshot\" height=\"300\"/>",
            fileTreePaths = listOf("fastlane/metadata/android/en-US/images/phoneScreenshots/01.png"),
            topics = listOf("p2p", "airdrop", "file-sharing", "offline-first")
        )

        assertEquals("localsend-localsend", discovered.slug)
        assertTrue(discovered.screenshots.isNotEmpty())
        assertTrue(discovered.capabilities.isZeroCloud)
    }

    @Test
    fun testRealTrendingAndroidRepositoriesAnalysis() {
        val reposRoot = java.nio.file.Path.of("/Users/mac/.gemini/antigravity/worktrees/building/restructure_settings_and_menus/verification_repos/trending_android")
        val clearPdf = reposRoot.resolve("trending-clearpdf")
        val bitChord = reposRoot.resolve("trending-bitchord")

        if (java.nio.file.Files.isDirectory(clearPdf) && java.nio.file.Files.isDirectory(bitChord)) {
            val engine = GitHubDiscoveryEngine()

            // 1. ClearPDF analysis
            val clearPdfManifest = java.nio.file.Files.readString(clearPdf.resolve("app/src/main/AndroidManifest.xml"))
            val clearPdfReadme = java.nio.file.Files.readString(clearPdf.resolve("readme.md"))
            val clearPdfPaths = java.nio.file.Files.walk(clearPdf).use { stream ->
                stream.map { clearPdf.relativize(it).toString() }.toList()
            }

            val clearPdfDiscovered = engine.enrichRepository(
                owner = "Chethan616",
                name = "ClearPDF",
                description = "Modern Android document reader & editor",
                stars = 4200,
                forks = 310,
                language = "Kotlin",
                htmlUrl = "https://github.com/Chethan616/ClearPDF",
                readmeContent = clearPdfReadme,
                manifestXml = clearPdfManifest,
                fileTreePaths = clearPdfPaths,
                topics = listOf("pdf", "android", "offline-first", "material3", "kotlin")
            )

            // Validate Zero-cloud offline detection
            assertTrue(clearPdfDiscovered.capabilities.isZeroCloud, "ClearPDF does not declare INTERNET permission and is zero-cloud")
            // Validate Tier 1 Fastlane screenshot extraction
            val fastlaneCandidate = clearPdfDiscovered.screenshots.find { it.tier == ScreenshotExtractor.AggressivenessTier.TIER_1_STORE_METADATA }
            assertNotNull(fastlaneCandidate, "Should discover Fastlane screenshot in ClearPDF")
            assertTrue(fastlaneCandidate.url.contains("phoneScreenshots/"), "Fastlane screenshot should be under phoneScreenshots")
            assertTrue(fastlaneCandidate.url.startsWith("https://raw.githubusercontent.com/Chethan616/ClearPDF/"), "Should normalize to GitHub raw URL")

            // 2. BitChord analysis
            val bitChordReadme = java.nio.file.Files.readString(bitChord.resolve("README.md"))
            val bitChordPaths = java.nio.file.Files.walk(bitChord).use { stream ->
                stream.map { bitChord.relativize(it).toString() }.toList()
            }

            val bitChordDiscovered = engine.enrichRepository(
                owner = "kushagrasinghx",
                name = "BitChord",
                description = "Aesthetic YouTube Music Client",
                stars = 6100,
                forks = 480,
                language = "Kotlin",
                htmlUrl = "https://github.com/kushagrasinghx/BitChord",
                readmeContent = bitChordReadme,
                fileTreePaths = bitChordPaths,
                topics = listOf("youtube-music", "compose-multiplatform", "kotlin", "music-player")
            )

            // Validate Tier 2 README banner extraction with URL normalization
            val bannerCandidate = bitChordDiscovered.screenshots.find { it.tier == ScreenshotExtractor.AggressivenessTier.TIER_2_README_SCRAPE && it.url.contains("Banner.png") }
            assertNotNull(bannerCandidate, "Should discover Banner.png in BitChord README")
            assertTrue(bannerCandidate.url.contains("raw.githubusercontent.com/kushagrasinghx/BitChord"), "Should normalize relative README image to raw URL")
        }
    }
}
