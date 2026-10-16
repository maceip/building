package dev.ide.ui.backend

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

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
    val topics: List<String> = emptyList()
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
    val htmlUrl: String
)

data class UiDiscoveryFeed(
    val trendingHero: List<UiDiscoveredRepo> = emptyList(),
    val northStarBenchmarks: List<UiNorthStarBenchmark> = emptyList(),
    val authorPeerPicks: List<UiPeerProject> = emptyList(),
    val highVelocityRising: List<UiNorthStarBenchmark> = emptyList()
)

/**
 * Service for querying GitHub trending feeds, screenshot extraction, North Star benchmarks,
 * and author peer social graph traversal.
 */
interface GitHubDiscoveryService {
    suspend fun getFeed(topic: String = "android", language: String = "kotlin", refresh: Boolean = false): UiDiscoveryFeed
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
