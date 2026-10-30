package dev.ide.ui.backend

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Time window for GitHub trending repository lists (Daily, Weekly, Monthly).
 */
enum class UiTrendingPeriod(
    val id: String,
    val label: String,
    val githubSince: String,
    val velocitySuffix: String
) {
    DAILY("daily", "Daily", "daily", "today"),
    WEEKLY("weekly", "Weekly", "weekly", "this week"),
    MONTHLY("monthly", "Monthly", "monthly", "this month");

    val sinceParam: String get() = githubSince

    companion object {
        fun fromId(raw: String?): UiTrendingPeriod =
            entries.firstOrNull { it.id.equals(raw?.trim(), ignoreCase = true) } ?: DAILY
    }
}

/**
 * Provenance tier indicating how an application's preview image was produced.
 *
 * Ordered by preference:
 * 1. [LOCAL_BUILD_PREVIEW]: Built locally on-demand and captured from its GUI window (SwiftUI/Cupertino, NodeGUI)
 *    or Compose `@Preview` / Android Layout renderer.
 * 2. [BUNDLED_BUILD_TIME]: Pre-built/fetched and bundled into the application at build time.
 * 3. [REPO_OR_WEBPAGE_SCREENSHOT]: Scraped from the GitHub repository or its linked homepage webpage.
 * 4. [DYNAMIC_SOURCE_SYNTHESIS]: Dynamically rendered by inspecting the repository's source code AST/tokens.
 * 5. [OPENGRAPH_FALLBACK]: GitHub's default OpenGraph social card.
 */
enum class UiScreenshotSourceTier(
    val id: String,
    val label: String,
    val badgeText: String
) {
    LOCAL_BUILD_PREVIEW("local_build_preview", "Built & Rendered Locally (GUI / Compose Preview)", "LIVE BUILD PREVIEW"),
    BUNDLED_BUILD_TIME("bundled_build_time", "Bundled at Build Time", "BUNDLED PREVIEW"),
    REPO_OR_WEBPAGE_SCREENSHOT("repo_or_webpage", "Extracted from Repo / Linked Webpage", "WEB / REPO SHOT"),
    DYNAMIC_SOURCE_SYNTHESIS("dynamic_source", "Generated Dynamically from Source Code", "SOURCE RENDER"),
    OPENGRAPH_FALLBACK("opengraph_fallback", "GitHub Social Card", "GITHUB CARD");

    val badgeLabel: String get() = badgeText

    companion object {
        fun fromId(raw: String?): UiScreenshotSourceTier =
            entries.firstOrNull { it.id.equals(raw?.trim(), ignoreCase = true) } ?: REPO_OR_WEBPAGE_SCREENSHOT
    }
}

/**
 * A popular programming language supported by the on-device toolchains and runtimes on this phone.
 */
data class UiSupportedPhoneLanguage(
    val id: String,
    val label: String,
    val githubTrendingSlug: String,
    val defaultSearchQuery: String
)

val SUPPORTED_PHONE_LANGUAGES: List<UiSupportedPhoneLanguage> = listOf(
    UiSupportedPhoneLanguage("all", "All Phone Languages", "", "topic:android OR language:kotlin OR language:swift OR language:python"),
    UiSupportedPhoneLanguage("kotlin", "Kotlin", "kotlin", "language:kotlin"),
    UiSupportedPhoneLanguage("swift", "Swift", "swift", "language:swift"),
    UiSupportedPhoneLanguage("python", "Python", "python", "language:python"),
    UiSupportedPhoneLanguage("typescript", "TypeScript", "typescript", "language:typescript"),
    UiSupportedPhoneLanguage("javascript", "JavaScript", "javascript", "language:javascript"),
    UiSupportedPhoneLanguage("java", "Java", "java", "language:java"),
    UiSupportedPhoneLanguage("rust", "Rust", "rust", "language:rust"),
    UiSupportedPhoneLanguage("c++", "C / C++", "c++", "language:c++")
)

/**
 * Authentication and rate limit state for the GitHub Explorer experience.
 */
data class UiGitHubAuthState(
    val isAuthenticated: Boolean = false,
    val username: String? = null,
    val tokenPreview: String? = null,
    val rateLimit: Int = 60,
    val rateRemaining: Int = 60,
    val errorMessage: String? = null
)

/**
 * UI-facing data transfer objects for the GitHub Explorer experience.
 */
data class UiDiscoveredRepo(
    val slug: String,
    val owner: String,
    val name: String,
    val description: String,
    val stars: Int,
    val forks: Int,
    val language: String,
    val htmlUrl: String,
    val screenshotUrl: String? = null,
    val isZeroCloud: Boolean = false,
    val isOfflineFirst: Boolean = false,
    val usesCompose: Boolean = false,
    val usesNativeNdk: Boolean = false,
    val permissions: List<String> = emptyList(),
    val featureTags: List<String> = emptyList(),
    val topics: List<String> = emptyList(),
    val starsInPeriod: Int = 0,
    val trendingPeriod: UiTrendingPeriod = UiTrendingPeriod.DAILY,
    val homepageUrl: String? = null,
    val defaultBranch: String = "main",
    val screenshotSourceTier: UiScreenshotSourceTier = UiScreenshotSourceTier.REPO_OR_WEBPAGE_SCREENSHOT,
    val builtByAvatars: List<String> = emptyList()
)

data class UiNorthStarBenchmark(
    val targetRepoName: String,
    val northStarName: String,
    val starMultiplierText: String,
    val matchPercentage: Int,
    val whyRecommended: String,
    val privacyOrCapabilityAdvantage: String? = null,
    val northStarUrl: String
)

data class UiPeerProject(
    val slug: String,
    val name: String,
    val owner: String,
    val description: String,
    val stars: Int,
    val language: String,
    val attributionText: String,
    val htmlUrl: String,
    val relationKind: String = "FOLLOWED_PEER",
    val socialPathText: String = attributionText,
    val relevanceReason: String = "",
    val relevanceScore: Int = 85,
    val screenshotUrl: String? = null
)

data class UiDiscoveryFeed(
    val trendingHero: List<UiDiscoveredRepo> = emptyList(),
    val northStarBenchmarks: List<UiNorthStarBenchmark> = emptyList(),
    val authorPeerPicks: List<UiPeerProject> = emptyList(),
    val highVelocityRising: List<UiNorthStarBenchmark> = emptyList(),
    val period: UiTrendingPeriod = UiTrendingPeriod.DAILY,
    val selectedLanguage: String = "kotlin",
    val isFromBuildBundle: Boolean = false,
    val bundledAtIso: String? = null
)

/**
 * Service for querying GitHub trending feeds (daily, weekly, monthly), multi-tier JIT app screenshot
 * generation (local GUI/Compose preview build -> repo/linked webpage scrape -> dynamic source synthesis),
 * North Star benchmarks, and 2-hop author social graph + starred + related repository traversal.
 */
interface GitHubDiscoveryService {
    suspend fun getFeed(topic: String = "android", language: String = "kotlin", refresh: Boolean = false): UiDiscoveryFeed

    suspend fun getTrendingFeed(
        language: String = "all",
        period: UiTrendingPeriod = UiTrendingPeriod.DAILY,
        refresh: Boolean = false
    ): UiDiscoveryFeed = getFeed(topic = language, language = language, refresh = refresh)

    suspend fun getRelatedAndAuthorNetwork(
        repo: UiDiscoveredRepo,
        refresh: Boolean = false
    ): List<UiPeerProject> = emptyList()

    suspend fun resolveOrGenerateAppImage(
        repo: UiDiscoveredRepo,
        forceLocalBuild: Boolean = false
    ): Pair<String, UiScreenshotSourceTier>? {
        val url = repo.screenshotUrl
        if (!url.isNullOrBlank()) {
            return url to repo.screenshotSourceTier
        }
        return null
    }

    fun supportedLanguages(): List<UiSupportedPhoneLanguage> = SUPPORTED_PHONE_LANGUAGES

    suspend fun fetchReadmeMarkdown(repo: UiDiscoveredRepo): String = buildString {
        appendLine("# ${repo.owner}/${repo.name}")
        appendLine()
        appendLine(repo.description.ifBlank { "Trending ${repo.language.ifBlank { "open-source" }} repository on GitHub." })
        appendLine()
        appendLine("- **Language:** ${repo.language.ifBlank { "Multi-language" }}")
        appendLine("- **Stars:** ${repo.stars} ★ (${if (repo.starsInPeriod > 0) "+${repo.starsInPeriod} ${repo.trendingPeriod.velocitySuffix}" else repo.trendingPeriod.label})")
        appendLine("- **Forks:** ${repo.forks}")
        appendLine("- **Repository:** ${repo.htmlUrl}")
        if (!repo.homepageUrl.isNullOrBlank()) {
            appendLine("- **Website:** ${repo.homepageUrl}")
        }
    }

    suspend fun getBundledAppReadmeMarkdown(): String = "# CodeAssist\n\nOn-device IDE and Smart GitHub Trending & Discovery Service."

    suspend fun search(query: String): List<UiDiscoveredRepo>
    suspend fun downloadScreenshot(url: String): String?
    suspend fun loadScreenshotBytes(url: String): ByteArray? = null
    val authState: StateFlow<UiGitHubAuthState> get() = MutableStateFlow(UiGitHubAuthState())
    suspend fun submitToken(token: String): Boolean = false
    suspend fun clearToken() {}
    fun getOAuthAuthorizeUrl(): String = "https://github.com/settings/tokens/new?scopes=read:user,repo&description=Building+IDE+Explorer"
    fun supported(): Boolean = false

    object Unsupported : GitHubDiscoveryService {
        override val authState: StateFlow<UiGitHubAuthState> = MutableStateFlow(UiGitHubAuthState())
        override suspend fun getFeed(topic: String, language: String, refresh: Boolean): UiDiscoveryFeed = UiDiscoveryFeed()
        override suspend fun search(query: String): List<UiDiscoveredRepo> = emptyList()
        override suspend fun downloadScreenshot(url: String): String? = null
        override suspend fun loadScreenshotBytes(url: String): ByteArray? = null
        override suspend fun submitToken(token: String): Boolean = false
        override suspend fun clearToken() {}
    }
}
