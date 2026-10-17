package dev.ide.core.backend

import dev.ide.core.discovery.DeveloperGraphEngine
import dev.ide.core.discovery.GitHubDiscoveryClient
import dev.ide.core.discovery.GitHubDiscoveryEngine
import dev.ide.core.discovery.RepoShapeEngine
import dev.ide.ui.backend.GitHubDiscoveryService
import dev.ide.ui.backend.UiDiscoveredRepo
import dev.ide.ui.backend.UiDiscoveryFeed
import dev.ide.ui.backend.UiGitHubAuthState
import dev.ide.ui.backend.UiNorthStarBenchmark
import dev.ide.ui.backend.UiPeerProject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * Concrete [GitHubDiscoveryService] connecting [GitHubDiscoveryClient] and [GitHubDiscoveryEngine]
 * to the user interface.
 */
class DiscoveryBackend(
    private val client: GitHubDiscoveryClient = GitHubDiscoveryClient(),
    private val cacheDir: Path? = null
) : GitHubDiscoveryService {

    private val feedCache = ConcurrentHashMap<String, UiDiscoveryFeed>()
    private val _authState = MutableStateFlow(UiGitHubAuthState())
    override val authState: StateFlow<UiGitHubAuthState> = _authState.asStateFlow()

    init {
        // Restore persisted PAT if available
        if (cacheDir != null) {
            val tokenFile = cacheDir.resolve("discovery/pat.token")
            if (Files.exists(tokenFile)) {
                runCatching {
                    val token = Files.readString(tokenFile).trim()
                    if (token.isNotBlank()) {
                        client.token = token
                        val verified = client.verifyToken(token)
                        if (verified != null) {
                            _authState.value = UiGitHubAuthState(
                                isAuthenticated = true,
                                username = verified.first,
                                tokenPreview = "..." + token.takeLast(4),
                                rateLimit = verified.second.first,
                                rateRemaining = verified.second.second
                            )
                        }
                    }
                }
            }
        }
    }

    override fun supported(): Boolean = true

    override suspend fun getFeed(
        topic: String,
        language: String,
        refresh: Boolean
    ): UiDiscoveryFeed = withContext(Dispatchers.IO) {
        val cacheKey = "$topic:$language"
        val held = feedCache[cacheKey]
        if (!refresh && held != null) {
            return@withContext held
        }

        val rawFeed = client.getDiscoveryFeed(topic = topic, language = language)

        val uiFeed = UiDiscoveryFeed(
            trendingHero = rawFeed.trendingHero.map { it.toUi() },
            northStarBenchmarks = rawFeed.northStarBenchmarks.map { it.toUi() },
            authorPeerPicks = rawFeed.authorPeerPicks.map { it.toUi() },
            highVelocityRising = rawFeed.highVelocityRising.map { it.toUi() }
        )
        feedCache[cacheKey] = uiFeed
        uiFeed
    }

    override suspend fun search(query: String): List<UiDiscoveredRepo> = withContext(Dispatchers.IO) {
        val repos = client.searchRepositories(query = query)
        repos.map { client.enrichRemoteRepo(it).toUi() }
    }

    override suspend fun downloadScreenshot(url: String): String? = withContext(Dispatchers.IO) {
        client.cacheScreenshot(url)
    }

    override suspend fun loadScreenshotBytes(url: String): ByteArray? = withContext(Dispatchers.IO) {
        client.loadScreenshotBytes(url)
    }

    override suspend fun submitToken(token: String): Boolean = withContext(Dispatchers.IO) {
        val trimmed = token.trim()
        if (trimmed.isBlank()) {
            _authState.value = _authState.value.copy(errorMessage = "Token cannot be blank")
            return@withContext false
        }

        val verified = client.verifyToken(trimmed)
        if (verified != null) {
            client.token = trimmed
            if (cacheDir != null) {
                val tokenFile = cacheDir.resolve("discovery/pat.token")
                runCatching {
                    Files.createDirectories(tokenFile.parent)
                    Files.writeString(tokenFile, trimmed)
                }
            }
            feedCache.clear()
            _authState.value = UiGitHubAuthState(
                isAuthenticated = true,
                username = verified.first,
                tokenPreview = "..." + trimmed.takeLast(4),
                rateLimit = verified.second.first,
                rateRemaining = verified.second.second,
                errorMessage = null
            )
            true
        } else {
            _authState.value = _authState.value.copy(errorMessage = "Invalid GitHub token or authentication rejected")
            false
        }
    }

    override suspend fun clearToken(): Unit = withContext(Dispatchers.IO) {
        client.token = null
        if (cacheDir != null) {
            val tokenFile = cacheDir.resolve("discovery/pat.token")
            runCatching { Files.deleteIfExists(tokenFile) }
        }
        feedCache.clear()
        val rate = client.fetchRateLimit(null)
        _authState.value = UiGitHubAuthState(
            isAuthenticated = false,
            username = null,
            tokenPreview = null,
            rateLimit = rate.first,
            rateRemaining = rate.second,
            errorMessage = null
        )
    }

    private fun GitHubDiscoveryEngine.DiscoveredRepository.toUi(): UiDiscoveredRepo {
        val bestShot = screenshots.firstOrNull()?.url
        return UiDiscoveredRepo(
            slug = slug,
            owner = owner,
            name = name,
            description = description,
            stars = stars,
            forks = forks,
            language = language,
            htmlUrl = htmlUrl,
            screenshotUrl = bestShot,
            isZeroCloud = capabilities.isZeroCloud,
            isOfflineFirst = capabilities.isOfflineFirst,
            usesCompose = genome.uiFramework in listOf(
                RepoShapeEngine.UiFramework.JETPACK_COMPOSE,
                RepoShapeEngine.UiFramework.COMPOSE_MULTIPLATFORM
            ),
            usesNativeNdk = capabilities.usesNativeNdk,
            permissions = capabilities.requestedPermissions,
            featureTags = capabilities.coreFeatureTags,
            topics = genome.topics.toList()
        )
    }

    private fun RepoShapeEngine.ComparisonResult.toUi(): UiNorthStarBenchmark {
        val cleanCandidateSlug = if ('/' in candidateSlug) candidateSlug else candidateSlug.replaceFirst('-', '/')
        val cleanTargetSlug = if ('/' in targetSlug) targetSlug else targetSlug.replaceFirst('-', '/')
        return UiNorthStarBenchmark(
            targetRepoName = cleanTargetSlug,
            northStarName = cleanCandidateSlug,
            starMultiplierText = starDifferential,
            matchPercentage = (structuralMatchScore * 100).toInt(),
            whyRecommended = whyRecommended,
            privacyOrCapabilityAdvantage = null,
            northStarUrl = "https://github.com/$cleanCandidateSlug"
        )
    }

    private fun DeveloperGraphEngine.PeerRepository.toUi(): UiPeerProject {
        return UiPeerProject(
            slug = slug,
            name = name,
            owner = owner,
            description = description,
            stars = stars,
            language = language,
            attributionText = DeveloperGraphEngine.formatAttribution(this),
            htmlUrl = "https://github.com/$slug"
        )
    }
}
