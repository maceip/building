package dev.ide.core.discovery

import kotlin.math.ln
import kotlin.math.max

/**
 * Structural Architecture & Topology Fingerprinting Engine.
 * Determines a repository's architectural "shape" and powers multi-lens comparisons:
 * - "Shaped like this one, but with 50x more stars (Battle-Tested North Star)"
 * - "Shaped like this one, but surging in velocity"
 * - "Shaped like this one, but modern Compose rewrite"
 * - "Shaped like this one, but iOS SwiftUI counterpart"
 */
object RepoShapeEngine {

    enum class BuildToolchain {
        GRADLE_KMP,
        GRADLE_ANDROID,
        CARGO_RUST,
        NPM_NODE_VITE,
        SWIFT_PM,
        PYTHON_UV,
        GO_MODULE,
        CMAKE_NDK,
        UNKNOWN
    }

    enum class UiFramework {
        COMPOSE_MULTIPLATFORM,
        JETPACK_COMPOSE,
        SWIFT_UI,
        FLUTTER,
        REACT_WEB,
        TAURI,
        TERMINAL_TUI,
        HEADLESS_DAEMON,
        UNKNOWN
    }

    enum class Topology {
        MONOREPO,
        CLIENT_SERVER,
        LOCAL_OFFLINE_FIRST,
        DAEMON_WITH_UI,
        COMMAND_LINE_UTILITY,
        LIBRARY_PLUGIN
    }

    enum class ComparisonLens(val displayName: String, val description: String) {
        /** Repositories sharing the exact architectural genome with 10x-50x more stars (production reference). */
        NORTH_STAR("North Star Reference", "Shaped like this one, but with 10x-50x more stars"),

        /** High-velocity challengers gaining the most momentum in the last 30 days. */
        HIGH_VELOCITY("High-Velocity Momentum", "Shaped like this one, but surging in growth"),

        /** Modernized successor (e.g. Kotlin + Compose rewrite of older Java/XML tools). */
        MODERN_UPGRADE("Modern Successor", "Same intent, but modernized architecture"),

        /** Cross-platform counterpart (e.g. the iOS SwiftUI counterpart to an Android Compose tool). */
        CROSS_PLATFORM("Cross-Platform Twin", "Shaped like this one, but for iOS / Desktop"),

        /** Clean permissive license alternative (MIT/Apache-2.0 vs AGPL). */
        UNENCUMBERED("Permissive Alternative", "Shaped like this one, but MIT / Apache-2.0")
    }

    data class RepoGenome(
        val toolchain: BuildToolchain,
        val uiFramework: UiFramework,
        val topology: Topology,
        val topics: Set<String>,
        val primaryLanguage: String,
        val stars: Int,
        val starsLastMonth: Int = 0,
        val daysSinceLastCommit: Int = 0,
        val license: String = "MIT",
        val hasPrebuiltRelease: Boolean = false,
        val onDeviceRunnableScore: Double = 0.0
    )

    data class ComparisonResult(
        val candidateSlug: String,
        val lens: ComparisonLens,
        val structuralMatchScore: Double,
        val whyRecommended: String,
        val starDifferential: String,
        val onDeviceRunnable: Boolean
    )

    /**
     * Infers a repository's structural genome from file trees and metadata.
     */
    fun inferGenome(
        filePaths: List<String>,
        topics: List<String>,
        language: String,
        stars: Int,
        license: String = "MIT",
        hasReleases: Boolean = false
    ): RepoGenome {
        val pathSet = filePaths.toSet()
        val topicSet = topics.map { it.lowercase() }.toSet()

        // 1. Detect Build Toolchain
        val toolchain = when {
            pathSet.any { it.endsWith("settings.gradle.kts") || it.endsWith("build.gradle.kts") } &&
                (topicSet.contains("kmp") || topicSet.contains("compose-multiplatform") || pathSet.any { it.contains("commonMain") }) ->
                BuildToolchain.GRADLE_KMP
            pathSet.any { it.endsWith("build.gradle") || it.endsWith("build.gradle.kts") } ->
                BuildToolchain.GRADLE_ANDROID
            pathSet.any { it.endsWith("Cargo.toml") } ->
                BuildToolchain.CARGO_RUST
            pathSet.any { it.endsWith("Package.swift") } ->
                BuildToolchain.SWIFT_PM
            pathSet.any { it.endsWith("package.json") || it.endsWith("vite.config.ts") } ->
                BuildToolchain.NPM_NODE_VITE
            pathSet.any { it.endsWith("pyproject.toml") || it.endsWith("requirements.txt") } ->
                BuildToolchain.PYTHON_UV
            pathSet.any { it.endsWith("go.mod") } ->
                BuildToolchain.GO_MODULE
            pathSet.any { it.endsWith("CMakeLists.txt") } ->
                BuildToolchain.CMAKE_NDK
            else -> BuildToolchain.UNKNOWN
        }

        // 2. Detect UI Framework
        val uiFramework = when {
            topicSet.contains("compose-multiplatform") || pathSet.any { it.contains("composeApp") } ->
                UiFramework.COMPOSE_MULTIPLATFORM
            topicSet.contains("jetpack-compose") || pathSet.any { it.contains("ui/theme") || it.contains("Composable") } ->
                UiFramework.JETPACK_COMPOSE
            topicSet.contains("swiftui") || pathSet.any { it.endsWith(".swift") && it.contains("View") } ->
                UiFramework.SWIFT_UI
            topicSet.contains("flutter") || pathSet.any { it.endsWith("pubspec.yaml") } ->
                UiFramework.FLUTTER
            topicSet.contains("react") || topicSet.contains("nextjs") || pathSet.any { it.endsWith(".tsx") } ->
                UiFramework.REACT_WEB
            topicSet.contains("tauri") || pathSet.any { it.contains("src-tauri") } ->
                UiFramework.TAURI
            topicSet.contains("terminal") || topicSet.contains("tui") || topicSet.contains("cli") ->
                UiFramework.TERMINAL_TUI
            else -> UiFramework.UNKNOWN
        }

        // 3. Detect Topology
        val topology = when {
            pathSet.count { it.endsWith("build.gradle.kts") || it.endsWith("package.json") || it.endsWith("Cargo.toml") } > 2 ->
                Topology.MONOREPO
            pathSet.any { it.contains("server") || it.contains("backend") || it.contains("api") } &&
                pathSet.any { it.contains("client") || it.contains("frontend") || it.contains("app") } ->
                Topology.CLIENT_SERVER
            topicSet.contains("terminal") || topicSet.contains("cli") ->
                Topology.COMMAND_LINE_UTILITY
            topicSet.contains("offline-first") || pathSet.any { it.contains("db") || it.contains("database") } ->
                Topology.LOCAL_OFFLINE_FIRST
            else -> Topology.LOCAL_OFFLINE_FIRST
        }

        // 4. Calculate On-Device Runnability in `building`
        val runnableScore = when (toolchain) {
            BuildToolchain.GRADLE_ANDROID, BuildToolchain.GRADLE_KMP -> 0.95 // Direct local ART/aapt2/dexer
            BuildToolchain.NPM_NODE_VITE -> 0.90 // Vivari Node runtime
            BuildToolchain.PYTHON_UV -> 0.85 // Vivari Python runtime
            BuildToolchain.SWIFT_PM -> 0.80 // XTool Swift on-device compiler
            BuildToolchain.CARGO_RUST -> 0.70 // NDK clang / cargo cross-runner
            else -> 0.40
        }

        return RepoGenome(
            toolchain = toolchain,
            uiFramework = uiFramework,
            topology = topology,
            topics = topicSet,
            primaryLanguage = language,
            stars = stars,
            license = license,
            hasPrebuiltRelease = hasReleases,
            onDeviceRunnableScore = runnableScore
        )
    }

    /**
     * Compares a candidate repository against a target repository across a chosen lens.
     */
    fun evaluateComparison(
        targetSlug: String,
        targetGenome: RepoGenome,
        candidateSlug: String,
        candidateGenome: RepoGenome,
        lens: ComparisonLens
    ): ComparisonResult? {
        if (targetSlug.equals(candidateSlug, ignoreCase = true)) return null

        // 1. Calculate structural similarity (Jaccard topics + Architecture alignment)
        val sharedTopics = targetGenome.topics.intersect(candidateGenome.topics).size
        val totalTopics = max(1, targetGenome.topics.union(candidateGenome.topics).size)
        val topicJaccard = sharedTopics.toDouble() / totalTopics

        var archScore = 0.0
        if (targetGenome.toolchain == candidateGenome.toolchain) archScore += 0.35
        if (targetGenome.uiFramework == candidateGenome.uiFramework) archScore += 0.35
        if (targetGenome.topology == candidateGenome.topology) archScore += 0.15
        if (targetGenome.primaryLanguage.equals(candidateGenome.primaryLanguage, ignoreCase = true)) archScore += 0.15

        val compositeScore = (archScore * 0.7) + (topicJaccard * 0.3)

        // 2. Evaluate lens-specific fit
        return when (lens) {
            ComparisonLens.NORTH_STAR -> {
                // Must have substantially more stars (at least 3x, ideally 10x-50x)
                if (candidateGenome.stars > targetGenome.stars * 2 && compositeScore >= 0.50) {
                    val starDiff = "${candidateGenome.stars / max(1, targetGenome.stars)}x more stars"
                    ComparisonResult(
                        candidateSlug = candidateSlug,
                        lens = lens,
                        structuralMatchScore = compositeScore,
                        whyRecommended = "Proven reference architecture: shares your ${candidateGenome.uiFramework.name} + ${candidateGenome.toolchain.name} stack with battle-tested reliability.",
                        starDifferential = starDiff,
                        onDeviceRunnable = candidateGenome.onDeviceRunnableScore >= 0.75
                    )
                } else null
            }

            ComparisonLens.HIGH_VELOCITY -> {
                // Must have high recent growth and recency
                val isRecent = candidateGenome.daysSinceLastCommit <= 14
                val isFast = candidateGenome.starsLastMonth >= 200 || candidateGenome.stars >= 1000
                if (isRecent && isFast && compositeScore >= 0.40) {
                    ComparisonResult(
                        candidateSlug = candidateSlug,
                        lens = lens,
                        structuralMatchScore = compositeScore,
                        whyRecommended = "Surging in momentum: +${candidateGenome.starsLastMonth} stars this month with active commits in the same technical domain.",
                        starDifferential = "${candidateGenome.stars} ★",
                        onDeviceRunnable = candidateGenome.onDeviceRunnableScore >= 0.75
                    )
                } else null
            }

            ComparisonLens.MODERN_UPGRADE -> {
                // Target has legacy tech (e.g. XML/Java), candidate has modern (Compose/Kotlin)
                val targetIsLegacy = targetGenome.uiFramework == UiFramework.UNKNOWN || targetGenome.primaryLanguage == "Java"
                val candidateIsModern = candidateGenome.uiFramework in listOf(UiFramework.JETPACK_COMPOSE, UiFramework.COMPOSE_MULTIPLATFORM)
                if ((targetIsLegacy && candidateIsModern) || (candidateGenome.uiFramework == UiFramework.COMPOSE_MULTIPLATFORM && targetGenome.uiFramework == UiFramework.JETPACK_COMPOSE)) {
                    ComparisonResult(
                        candidateSlug = candidateSlug,
                        lens = lens,
                        structuralMatchScore = compositeScore,
                        whyRecommended = "Modern architectural upgrade: migrated from legacy patterns to ${candidateGenome.uiFramework.name}.",
                        starDifferential = "${candidateGenome.stars} ★",
                        onDeviceRunnable = candidateGenome.onDeviceRunnableScore >= 0.75
                    )
                } else null
            }

            ComparisonLens.CROSS_PLATFORM -> {
                // Target is Android/Kotlin, candidate is iOS/SwiftUI or Desktop KMP
                val isCrossTwin = (targetGenome.uiFramework == UiFramework.JETPACK_COMPOSE && candidateGenome.uiFramework == UiFramework.SWIFT_UI) ||
                    (targetGenome.uiFramework == UiFramework.SWIFT_UI && candidateGenome.uiFramework in listOf(UiFramework.JETPACK_COMPOSE, UiFramework.COMPOSE_MULTIPLATFORM))
                if (isCrossTwin && (sharedTopics > 0 || topicJaccard >= 0.20)) {
                    ComparisonResult(
                        candidateSlug = candidateSlug,
                        lens = lens,
                        structuralMatchScore = compositeScore,
                        whyRecommended = "Platform twin: solves the exact same problem for ${candidateGenome.uiFramework.name} with native ergonomics.",
                        starDifferential = "${candidateGenome.stars} ★",
                        onDeviceRunnable = candidateGenome.onDeviceRunnableScore >= 0.75
                    )
                } else null
            }

            ComparisonLens.UNENCUMBERED -> {
                // Clean permissive alternative (MIT/Apache vs GPL/AGPL)
                val candidateIsPermissive = candidateGenome.license in listOf("MIT", "Apache-2.0", "BSD-3-Clause")
                if (candidateIsPermissive && compositeScore >= 0.50) {
                    ComparisonResult(
                        candidateSlug = candidateSlug,
                        lens = lens,
                        structuralMatchScore = compositeScore,
                        whyRecommended = "Permissive MIT/Apache-2.0 alternative with matching ${candidateGenome.uiFramework.name} architecture.",
                        starDifferential = "${candidateGenome.stars} ★",
                        onDeviceRunnable = candidateGenome.onDeviceRunnableScore >= 0.75
                    )
                } else null
            }
        }
    }
}
