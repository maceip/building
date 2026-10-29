package dev.ide.core.backend

import dev.ide.core.discovery.DeveloperGraphEngine
import dev.ide.core.discovery.GitHubDiscoveryClient
import dev.ide.core.discovery.GitHubDiscoveryEngine
import dev.ide.core.discovery.RepoShapeEngine
import dev.ide.ui.backend.GitHubDiscoveryService
import dev.ide.ui.backend.SUPPORTED_PHONE_LANGUAGES
import dev.ide.ui.backend.UiDiscoveredRepo
import dev.ide.ui.backend.UiDiscoveryFeed
import dev.ide.ui.backend.UiGitHubAuthState
import dev.ide.ui.backend.UiNorthStarBenchmark
import dev.ide.ui.backend.UiPeerProject
import dev.ide.ui.backend.UiScreenshotSourceTier
import dev.ide.ui.backend.UiSupportedPhoneLanguage
import dev.ide.ui.backend.UiTrendingPeriod
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
    private val cacheDir: Path? = null,
    private val localProjectsDir: Path? = null
) : GitHubDiscoveryService {

    private val feedCache = ConcurrentHashMap<String, UiDiscoveryFeed>()
    private val relatedCache = ConcurrentHashMap<String, List<UiPeerProject>>()
    private val _authState = MutableStateFlow(UiGitHubAuthState())
    override val authState: StateFlow<UiGitHubAuthState> = _authState.asStateFlow()

    init {
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

    override fun supportedLanguages(): List<UiSupportedPhoneLanguage> = SUPPORTED_PHONE_LANGUAGES

    override suspend fun getFeed(
        topic: String,
        language: String,
        refresh: Boolean
    ): UiDiscoveryFeed = withContext(Dispatchers.IO) {
        val cacheKey = "feed:$topic:$language"
        val held = feedCache[cacheKey]
        if (!refresh && held != null) {
            return@withContext held
        }

        val rawFeed = client.getDiscoveryFeed(topic = topic, language = language)
        val uiFeed = rawFeed.toUiFeed(UiTrendingPeriod.DAILY, language.ifBlank { topic })
        feedCache[cacheKey] = uiFeed
        uiFeed
    }

    override suspend fun getTrendingFeed(
        language: String,
        period: UiTrendingPeriod,
        refresh: Boolean
    ): UiDiscoveryFeed = withContext(Dispatchers.IO) {
        val normLang = language.lowercase().trim().ifBlank { "all" }
        val cacheKey = "trending:$normLang:${period.sinceParam}"
        val held = feedCache[cacheKey]
        if (!refresh && held != null) {
            return@withContext held
        }

        val rawFeed = client.getTrendingFeed(
            language = normLang,
            period = period.sinceParam,
            limit = 10,
            preferLive = refresh
        )
        val uiFeed = rawFeed.toUiFeed(period, normLang)
        feedCache[cacheKey] = uiFeed
        uiFeed
    }

    override suspend fun getRelatedAndAuthorNetwork(
        repo: UiDiscoveredRepo,
        refresh: Boolean
    ): List<UiPeerProject> = withContext(Dispatchers.IO) {
        val key = "${repo.owner}/${repo.name}".lowercase()
        val held = relatedCache[key]
        if (!refresh && !held.isNullOrEmpty()) {
            return@withContext held
        }

        val peers = client.crawlRelatedAndAuthorNetwork(
            owner = repo.owner,
            repoName = repo.name,
            language = repo.language,
            description = repo.description,
            topics = repo.topics,
            limit = 16
        ).map { it.toUi() }

        if (peers.isNotEmpty()) {
            relatedCache[key] = peers
        }
        peers
    }

    override suspend fun resolveOrGenerateAppImage(
        repo: UiDiscoveredRepo,
        forceLocalBuild: Boolean
    ): Pair<String, UiScreenshotSourceTier>? = withContext(Dispatchers.IO) {
        val candidateLocalDir = localProjectsDir?.let { root ->
            val byName = root.resolve(repo.name)
            val bySlug = root.resolve("${repo.owner}-${repo.name}")
            when {
                Files.isDirectory(byName) -> byName
                Files.isDirectory(bySlug) -> bySlug
                else -> null
            }
        }

        val resolved = client.resolveOrGenerateAppImage(
            owner = repo.owner,
            repoName = repo.name,
            language = repo.language,
            description = repo.description,
            defaultBranch = repo.defaultBranch,
            homepageUrl = repo.homepageUrl,
            forceLocalBuild = forceLocalBuild,
            localRepoDir = candidateLocalDir
        ) ?: return@withContext null

        val tierEnum = mapTierString(resolved.second)
        resolved.first to tierEnum
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
            relatedCache.clear()
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
        relatedCache.clear()
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

    private fun GitHubDiscoveryEngine.DiscoveryFeed.toUiFeed(
        period: UiTrendingPeriod,
        selectedLang: String
    ): UiDiscoveryFeed {
        // Seed relatedCache from any pre-bundled relatedPeers on discovered repos
        for (r in trendingHero) {
            if (r.relatedPeers.isNotEmpty()) {
                val key = "${r.owner}/${r.name}".lowercase()
                relatedCache.putIfAbsent(key, r.relatedPeers.map { it.toUi() })
            }
        }
        return UiDiscoveryFeed(
            trendingHero = trendingHero.map { it.toUi() },
            northStarBenchmarks = northStarBenchmarks.map { it.toUi() },
            authorPeerPicks = authorPeerPicks.map { it.toUi() },
            highVelocityRising = highVelocityRising.map { it.toUi() },
            period = period,
            selectedLanguage = selectedLang,
            isFromBuildBundle = isFromBuildBundle,
            bundledAtIso = bundledAtIso
        )
    }

    private fun GitHubDiscoveryEngine.DiscoveredRepository.toUi(): UiDiscoveredRepo {
        val bestShot = localScreenshotPath ?: screenshots.firstOrNull()?.url
        val periodEnum = when (trendingPeriod.lowercase()) {
            "weekly" -> UiTrendingPeriod.WEEKLY
            "monthly" -> UiTrendingPeriod.MONTHLY
            else -> UiTrendingPeriod.DAILY
        }
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
            topics = genome.topics.toList(),
            starsInPeriod = starsInPeriod,
            trendingPeriod = periodEnum,
            homepageUrl = homepageUrl,
            defaultBranch = defaultBranch,
            screenshotSourceTier = mapTierString(screenshotSourceTier),
            builtByAvatars = builtByAvatars
        )
    }

    private fun mapTierString(raw: String): UiScreenshotSourceTier {
        return when (raw.uppercase()) {
            "LOCAL_BUILD_PREVIEW" -> UiScreenshotSourceTier.LOCAL_BUILD_PREVIEW
            "BUNDLED_BUILD_TIME" -> UiScreenshotSourceTier.BUNDLED_BUILD_TIME
            "REPO_OR_WEBPAGE_SCREENSHOT" -> UiScreenshotSourceTier.REPO_OR_WEBPAGE_SCREENSHOT
            "DYNAMIC_SOURCE_SYNTHESIS" -> UiScreenshotSourceTier.DYNAMIC_SOURCE_SYNTHESIS
            else -> UiScreenshotSourceTier.OPENGRAPH_FALLBACK
        }
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
        val pathText = if (socialChain.isNotEmpty()) {
            socialChain.joinToString(" → ") { if (it.startsWith("@") || it.startsWith("★")) it else "@$it" }
        } else {
            "@$connectedViaAuthor → @$owner"
        }
        return UiPeerProject(
            slug = slug,
            name = name,
            owner = owner,
            description = description,
            stars = stars,
            language = language,
            attributionText = DeveloperGraphEngine.formatAttribution(this),
            htmlUrl = "https://github.com/$slug",
            relationKind = connectionReason.name,
            socialPathText = pathText,
            relevanceReason = relevanceReason,
            relevanceScore = relevanceScore,
            screenshotUrl = screenshotUrl
        )
    }
}
