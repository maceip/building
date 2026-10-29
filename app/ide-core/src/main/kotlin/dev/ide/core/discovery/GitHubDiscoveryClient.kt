package dev.ide.core.discovery

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit

/**
 * High-performance client that powers the on-device GitHub Explorer and Trending Service.
 *
 * Capabilities:
 * 1. Build-time bundled + live GitHub Trending (`daily`, `weekly`, `monthly`) across all popular
 *    phone-supported languages (`all`, `kotlin`, `swift`, `python`, `typescript`, `javascript`,
 *    `java`, `rust`, `c++`).
 * 2. Multi-tier Just-In-Time (JIT) app image generation via [SmartAppImageGenerator]:
 *    - Tier 1 (Preferred): Local on-demand GUI build (`SwiftUiPreviewInterpreter` `ViewNode` tree)
 *      or Compose `@Preview` / XML layout preview render.
 *    - Tier 2 (Fallback 1): Best-effort search of the GitHub repository AND the GitHub linked
 *      webpage (`homepage` URL / website links) for a real app screenshot.
 *    - Tier 3 (Last Ditch Effort): Dynamic visual synthesis from inspecting actual source code.
 * 3. Deep 2-Hop Author Social Graph + Starred + Related Apps Crawler:
 *    - Author's other repos (`SAME_AUTHOR`)
 *    - Author's starred repos (`AUTHOR_STARRED`)
 *    - 1st-hop followed peers (`@author -> @peer1`) repos (`FOLLOWED_PEER`) & starred (`PEER_STARRED`)
 *    - 2nd-hop followed peers (`@author -> @peer1 -> @peer2`) repos (`SECOND_DEGREE_PEER`) & starred (`SECOND_DEGREE_STARRED`)
 *    - Architecture & domain related repos (`RELATED_ARCHITECTURE`)
 */
class GitHubDiscoveryClient(
    var token: String? = null,
    private val apiBase: String = DEFAULT_API_BASE,
    private val webBase: String = DEFAULT_WEB_BASE,
    private val cacheDir: Path? = null,
    private val http: OkHttpClient = defaultHttpClient(),
    private val engine: GitHubDiscoveryEngine = GitHubDiscoveryEngine(),
    private val useBundledCatalogFallback: Boolean = (apiBase == DEFAULT_API_BASE),
    var headlessPreviewRunner: ((filePath: String, sourceCode: String) -> ByteArray?)? = null
) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    val imageGenerator: SmartAppImageGenerator
        get() = SmartAppImageGenerator(
            httpFetcher = ::executeWebGet,
            binaryFetcher = ::loadScreenshotBytes,
            headlessPreviewRunner = headlessPreviewRunner
        )

    /**
     * Fetches the trending feed for a given [language] (`all`, `kotlin`, `swift`, `python`,
     * `typescript`, `javascript`, `java`, `rust`, `c++`) and [period] (`daily`, `weekly`, `monthly`).
     *
     * Serves build-time bundled trending lists & pre-saved screenshots immediately when
     * [preferLive] is false, or crawls live GitHub Trending HTML / API when [preferLive] is true.
     */
    fun getTrendingFeed(
        language: String = "all",
        period: String = "daily",
        limit: Int = 10,
        preferLive: Boolean = false
    ): GitHubDiscoveryEngine.DiscoveryFeed {
        val normLang = BundledTrendingCatalog.normalizeLanguageKey(language)
        val normPeriod = period.lowercase().trim().takeIf { it in BundledTrendingCatalog.SUPPORTED_PERIODS } ?: "daily"

        val bundledCatalog = if (useBundledCatalogFallback) BundledTrendingCatalog.loadCatalog() else null
        val bundledEntries = if (useBundledCatalogFallback) {
            BundledTrendingCatalog.getTrendingEntries(normLang, normPeriod)
        } else {
            emptyList()
        }

        // 1. If not forcing live refresh and we have build-time bundled entries for this (language, period),
        // materialize their bundled screenshots and return immediately.
        if (!preferLive && bundledEntries.isNotEmpty()) {
            val enriched = bundledEntries.take(limit).map { entry ->
                bundledEntryToDiscoveredRepo(entry)
            }
            val topRepo = enriched.firstOrNull()
            val peerPicks = topRepo?.relatedPeers?.ifEmpty {
                bundledEntries.flatMap { it.relatedPeers }.map { bundledPeerToEnginePeer(it) }.distinctBy { it.slug }.take(12)
            } ?: emptyList()

            val (northStars, highVelocity) = computeCrossComparisons(enriched)
            return GitHubDiscoveryEngine.DiscoveryFeed(
                trendingHero = enriched,
                northStarBenchmarks = northStars,
                highVelocityRising = highVelocity,
                authorPeerPicks = peerPicks,
                period = normPeriod,
                selectedLanguage = normLang,
                isFromBuildBundle = true,
                bundledAtIso = bundledCatalog?.bundledAtIso
            )
        }

        // 2. Crawl live GitHub Trending HTML (`https://github.com/trending/{lang}?since={period}`)
        val liveTrendingRaw = fetchLiveTrendingRepositories(normLang, normPeriod, limit)
        if (liveTrendingRaw.isNotEmpty()) {
            val enriched = liveTrendingRaw.take(limit).map { raw ->
                // Check if we already have a bundled entry for this repo to reuse its pre-rendered screenshot if available
                val matchingBundled = bundledEntries.find {
                    it.owner.equals(raw.owner, ignoreCase = true) && it.name.equals(raw.name, ignoreCase = true)
                }
                val localShot = matchingBundled?.bundledScreenshotResource?.let {
                    BundledTrendingCatalog.materializeBundledScreenshot(it, cacheDir)
                }
                val enrichedRepo = enrichRemoteRepo(
                    repo = raw,
                    skipRemoteMetadata = false,
                    localScreenshotPath = localShot,
                    screenshotSourceTier = matchingBundled?.screenshotTier ?: "REPO_OR_WEBPAGE_SCREENSHOT"
                )
                enrichedRepo
            }
            val firstRepo = enriched.firstOrNull()
            val peerPicks = if (firstRepo != null) {
                crawlRelatedAndAuthorNetwork(
                    owner = firstRepo.owner,
                    repoName = firstRepo.name,
                    language = firstRepo.language,
                    description = firstRepo.description,
                    limit = 12
                )
            } else {
                emptyList()
            }
            val (northStars, highVelocity) = computeCrossComparisons(enriched)
            return GitHubDiscoveryEngine.DiscoveryFeed(
                trendingHero = enriched,
                northStarBenchmarks = northStars,
                highVelocityRising = highVelocity,
                authorPeerPicks = peerPicks,
                period = normPeriod,
                selectedLanguage = normLang,
                isFromBuildBundle = false,
                bundledAtIso = bundledCatalog?.bundledAtIso
            )
        }

        // 3. Fallback to bundled catalog if live crawl failed (e.g. offline mode)
        if (bundledEntries.isNotEmpty()) {
            val enriched = bundledEntries.take(limit).map { bundledEntryToDiscoveredRepo(it) }
            val peerPicks = enriched.firstOrNull()?.relatedPeers.orEmpty()
            val (northStars, highVelocity) = computeCrossComparisons(enriched)
            return GitHubDiscoveryEngine.DiscoveryFeed(
                trendingHero = enriched,
                northStarBenchmarks = northStars,
                highVelocityRising = highVelocity,
                authorPeerPicks = peerPicks,
                period = normPeriod,
                selectedLanguage = normLang,
                isFromBuildBundle = true,
                bundledAtIso = bundledCatalog?.bundledAtIso
            )
        }

        return getDiscoveryFeed(topic = normLang, language = if (normLang == "all") "kotlin" else normLang, limit = limit)
    }

    /**
     * Builds a complete, multi-dimensional [GitHubDiscoveryEngine.DiscoveryFeed] for the explorer screen.
     */
    fun getDiscoveryFeed(
        topic: String = "android",
        language: String = "kotlin",
        limit: Int = 10
    ): GitHubDiscoveryEngine.DiscoveryFeed {
        val normTopic = topic.lowercase().trim()

        val searchQuery = when (normTopic) {
            "swift", "swiftui", "ios" -> "language:swift stars:>200 size:<5000"
            "python" -> "language:python topic:cli stars:>300 size:<5000"
            "node", "nodejs", "node.js", "javascript" -> "language:javascript topic:nodejs stars:>300 size:<5000"
            "typescript" -> "language:typescript stars:>300 size:<5000"
            "java" -> "language:java stars:>300 size:<5000"
            "rust" -> "language:rust stars:>300 size:<5000"
            "c++", "cpp" -> "language:c++ stars:>300 size:<8000"
            "agents" -> "topic:ai-agents language:python stars:>300 size:<8000"
            "llm", "large language models", "llms" -> "topic:llm language:python stars:>500 size:<8000"
            "penetration-testing", "pentesting", "pen testing" -> "topic:security language:python stars:>500 size:<8000"
            "reverse-engineering", "reverse engineering", "reverse eng" -> "topic:reverse-engineering stars:>500 size:<10000"
            "gaming" -> "topic:game-engine stars:>500 size:<15000"
            "science" -> "topic:scientific-computing language:python stars:>500 size:<10000"
            "all", "", "android" -> if (language.isNotBlank()) "topic:android language:$language size:<5000" else "topic:android size:<5000"
            else -> if (topic.startsWith("topic:") || topic.contains(' ')) "$topic size:<8000" else "$topic stars:>50 size:<8000"
        }

        val (repos, fromOfflineFallback) = searchRepositoriesWithOrigin(
            query = searchQuery,
            category = topic,
            sort = "stars",
            order = "desc",
            limit = limit
        )

        // If REST search failed or rate-limited and bundled catalog fallback is enabled, serve bundled catalog
        if (repos.isEmpty() && useBundledCatalogFallback) {
            val langKey = BundledTrendingCatalog.normalizeLanguageKey(normTopic.ifBlank { language })
            val bundled = BundledTrendingCatalog.getTrendingEntries(langKey, "daily")
            if (bundled.isNotEmpty()) {
                val enrichedBundled = bundled.take(limit).map { bundledEntryToDiscoveredRepo(it) }
                val (northStars, highVelocity) = computeCrossComparisons(enrichedBundled)
                return GitHubDiscoveryEngine.DiscoveryFeed(
                    trendingHero = enrichedBundled,
                    northStarBenchmarks = northStars,
                    highVelocityRising = highVelocity,
                    authorPeerPicks = enrichedBundled.firstOrNull()?.relatedPeers.orEmpty(),
                    period = "daily",
                    selectedLanguage = langKey,
                    isFromBuildBundle = true,
                    bundledAtIso = BundledTrendingCatalog.loadCatalog()?.bundledAtIso
                )
            }
        }

        val enriched = repos.take(limit).map { enrichRemoteRepo(it, skipRemoteMetadata = fromOfflineFallback) }

        val topRepo = enriched.firstOrNull()
        val peerPicks = if (topRepo != null && !fromOfflineFallback) {
            crawlRelatedAndAuthorNetwork(
                owner = topRepo.owner,
                repoName = topRepo.name,
                language = topRepo.language,
                description = topRepo.description,
                limit = 8
            ).ifEmpty { getOfflineAuthorPeerPicks(topic) }
        } else {
            getOfflineAuthorPeerPicks(topic)
        }

        val (northStars, highVelocity) = computeCrossComparisons(enriched)

        return GitHubDiscoveryEngine.DiscoveryFeed(
            trendingHero = enriched,
            northStarBenchmarks = northStars,
            highVelocityRising = highVelocity,
            authorPeerPicks = peerPicks
        )
    }

    /**
     * Crawls GitHub Trending (`https://github.com/trending/{lang}?since={period}`)
     * and parses the HTML `<article class="Box-row">` listings.
     */
    fun fetchLiveTrendingRepositories(
        language: String = "all",
        period: String = "daily",
        limit: Int = 10
    ): List<RawRepoItem> {
        if (apiBase != DEFAULT_API_BASE && webBase == DEFAULT_WEB_BASE) {
            // Respect custom offline test endpoints
            return emptyList()
        }
        val normLang = BundledTrendingCatalog.normalizeLanguageKey(language)
        val langSegment = when (normLang) {
            "all" -> ""
            "c++" -> "/c++"
            else -> "/$normLang"
        }
        val sinceParam = period.lowercase().trim().takeIf { it in BundledTrendingCatalog.SUPPORTED_PERIODS } ?: "daily"
        val url = "$webBase/trending$langSegment?since=$sinceParam"
        val html = executeWebGet(url) ?: return emptyList()
        return parseTrendingHtml(
            html = html,
            period = sinceParam,
            defaultLanguage = if (normLang == "all") "" else normLangDisplayName(normLang)
        ).take(limit)
    }

    /**
     * Parses GitHub's `/trending` HTML page into structured [RawRepoItem] entries,
     * extracting owner, name, description, language, total stars, forks, star velocity in period,
     * and contributor avatars.
     */
    fun parseTrendingHtml(
        html: String,
        period: String = "daily",
        defaultLanguage: String = ""
    ): List<RawRepoItem> {
        if (html.isBlank()) return emptyList()
        val articleRegex = Regex("""<article\s+class=["'][^"']*Box-row[^"']*["'][^>]*>([\s\S]*?)</article>""", RegexOption.IGNORE_CASE)
        val h2HrefRegex = Regex("""<h2[^>]*>[\s\S]*?<a\s+[^>]*?href=["']/([^/"'\s]+)/([^/"'\s]+)["']""", RegexOption.IGNORE_CASE)
        val descRegex = Regex("""<p\s+class=["'][^"']*col-9[^"']*["'][^>]*>([\s\S]*?)</p>""", RegexOption.IGNORE_CASE)
        val langRegex = Regex("""itemprop=["']programmingLanguage["'][^>]*>([^<]+)</span>""", RegexOption.IGNORE_CASE)
        val stargazersRegex = Regex("""href=["']/[^"']+/stargazers["'][^>]*>([\s\S]*?)</a>""", RegexOption.IGNORE_CASE)
        val forksRegex = Regex("""href=["']/[^"']+/forks["'][^>]*>([\s\S]*?)</a>""", RegexOption.IGNORE_CASE)
        val periodStarsRegex = Regex("""([\d,]+)\s+stars?\s+(?:today|this\s+week|this\s+month)""", RegexOption.IGNORE_CASE)
        val avatarRegex = Regex("""<img\s+[^>]*?class=["'][^"']*avatar-user[^"']*["'][^>]*?src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)

        val results = mutableListOf<RawRepoItem>()
        for (articleMatch in articleRegex.findAll(html)) {
            val block = articleMatch.groupValues[1]
            val slugMatch = h2HrefRegex.find(block) ?: continue
            val owner = slugMatch.groupValues[1].trim()
            val name = slugMatch.groupValues[2].trim()
            if (owner.isBlank() || name.isBlank() || owner == "sponsors" || owner == "login") continue

            val rawDesc = descRegex.find(block)?.groupValues?.getOrNull(1).orEmpty()
            val cleanDesc = stripHtmlTags(rawDesc).trim()
            val lang = langRegex.find(block)?.groupValues?.getOrNull(1)?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: defaultLanguage.ifBlank { "Kotlin" }

            val totalStarsText = stripHtmlTags(stargazersRegex.find(block)?.groupValues?.getOrNull(1).orEmpty())
            val totalStars = parseCompactCount(totalStarsText)

            val forksText = stripHtmlTags(forksRegex.find(block)?.groupValues?.getOrNull(1).orEmpty())
            val totalForks = parseCompactCount(forksText)

            val periodStarsText = periodStarsRegex.find(block)?.groupValues?.getOrNull(1).orEmpty()
            val starsInPeriod = parseCompactCount(periodStarsText)

            val avatars = avatarRegex.findAll(block)
                .map { it.groupValues[1].trim() }
                .filter { it.startsWith("http") }
                .distinct()
                .take(5)
                .toList()

            results.add(
                RawRepoItem(
                    owner = owner,
                    name = name,
                    description = cleanDesc,
                    stars = totalStars,
                    forks = totalForks,
                    language = decodeHtmlEntities(lang),
                    htmlUrl = "https://github.com/$owner/$name",
                    defaultBranch = "main",
                    topics = emptyList(),
                    starsInPeriod = starsInPeriod,
                    trendingPeriod = period,
                    builtByAvatars = avatars
                )
            )
        }
        return results
    }

    /**
     * Deep 2-Hop Author Social Graph + Starred Repos + Related Apps Crawler.
     *
     * Goes above and beyond for the currently selected app (`owner/repoName`):
     * 1. Inspects `@owner`'s other repositories (`SAME_AUTHOR`).
     * 2. Inspects `@owner`'s starred repositories (`AUTHOR_STARRED`).
     * 3. Inspects who `@owner` follows (`@peer1` — 1st-hop peers), their repositories (`FOLLOWED_PEER`),
     *    and their starred repositories (`PEER_STARRED`).
     * 4. Inspects who those `@peer1` developers follow (`@peer2` — 2nd-hop peers), their repositories
     *    (`SECOND_DEGREE_PEER`), and their starred repositories (`SECOND_DEGREE_STARRED`).
     * 5. Cross-references repositories in the bundled catalog and live discovery pool to surface
     *    architecturally/topically related apps (`RELATED_ARCHITECTURE`).
     * 6. Ranks and filters all candidates by relevance to `owner/repoName`.
     */
    fun crawlRelatedAndAuthorNetwork(
        owner: String,
        repoName: String,
        language: String,
        description: String,
        topics: List<String> = emptyList(),
        limit: Int = 16
    ): List<DeveloperGraphEngine.PeerRepository> {
        val rawCandidates = mutableListOf<DeveloperGraphEngine.PeerRepository>()
        val targetSlugLower = "$owner/$repoName".lowercase()

        // 0. Include any pre-bundled social graph entries for this repository first
        if (useBundledCatalogFallback) {
            val catalog = BundledTrendingCatalog.loadCatalog()
            val matchingBundled = catalog?.lists?.values?.flatten()?.firstOrNull {
                it.owner.equals(owner, ignoreCase = true) && it.name.equals(repoName, ignoreCase = true)
            }
            if (matchingBundled != null) {
                rawCandidates.addAll(matchingBundled.relatedPeers.map { bundledPeerToEnginePeer(it) })
            }
        }

        // 1. Author's other repositories (SAME_AUTHOR)
        val authorRepos = fetchUserRepositories(owner, perPage = 6)
        for (r in authorRepos) {
            if ("${r.owner}/${r.name}".lowercase() == targetSlugLower) continue
            rawCandidates.add(
                DeveloperGraphEngine.PeerRepository(
                    slug = "${r.owner}/${r.name}",
                    name = r.name,
                    owner = r.owner,
                    description = r.description,
                    stars = r.stars,
                    language = r.language.ifBlank { language },
                    connectionReason = DeveloperGraphEngine.ConnectionType.SAME_AUTHOR,
                    connectedViaAuthor = owner,
                    peerFollowerCount = 0,
                    socialChain = listOf(owner)
                )
            )
        }

        // 2. Author's starred repositories (AUTHOR_STARRED)
        val authorStarred = fetchUserStarredRepositories(owner, perPage = 6)
        for (r in authorStarred) {
            if ("${r.owner}/${r.name}".lowercase() == targetSlugLower) continue
            rawCandidates.add(
                DeveloperGraphEngine.PeerRepository(
                    slug = "${r.owner}/${r.name}",
                    name = r.name,
                    owner = r.owner,
                    description = r.description,
                    stars = r.stars,
                    language = r.language.ifBlank { language },
                    connectionReason = DeveloperGraphEngine.ConnectionType.AUTHOR_STARRED,
                    connectedViaAuthor = owner,
                    peerFollowerCount = 0,
                    socialChain = listOf(owner, "★ ${r.owner}/${r.name}")
                )
            )
        }

        // 3. Who the author follows (1st-hop: @owner -> @peer1).
        // If @owner is an organization (no public following list), fall back to the repository's top human contributors.
        val directFollowing = fetchUserFollowingLogins(owner, maxUsers = 4)
        val firstHopLogins = if (directFollowing.isNotEmpty()) {
            directFollowing
        } else {
            fetchRepoContributorLogins(owner, repoName, maxUsers = 3)
        }
        for (peer1 in firstHopLogins.take(3)) {
            // 3A. 1st-hop peer's own top repositories (FOLLOWED_PEER)
            val peer1Repos = fetchUserRepositories(peer1, perPage = 3)
            for (r in peer1Repos) {
                rawCandidates.add(
                    DeveloperGraphEngine.PeerRepository(
                        slug = "${r.owner}/${r.name}",
                        name = r.name,
                        owner = r.owner,
                        description = r.description,
                        stars = r.stars,
                        language = r.language.ifBlank { language },
                        connectionReason = DeveloperGraphEngine.ConnectionType.FOLLOWED_PEER,
                        connectedViaAuthor = owner,
                        peerFollowerCount = 0,
                        socialChain = listOf(owner, peer1)
                    )
                )
            }

            // 3B. 1st-hop peer's starred repositories (PEER_STARRED)
            val peer1Stars = fetchUserStarredRepositories(peer1, perPage = 3)
            for (r in peer1Stars) {
                rawCandidates.add(
                    DeveloperGraphEngine.PeerRepository(
                        slug = "${r.owner}/${r.name}",
                        name = r.name,
                        owner = r.owner,
                        description = r.description,
                        stars = r.stars,
                        language = r.language.ifBlank { language },
                        connectionReason = DeveloperGraphEngine.ConnectionType.PEER_STARRED,
                        connectedViaAuthor = owner,
                        peerFollowerCount = 0,
                        socialChain = listOf(owner, peer1)
                    )
                )
            }

            // 4. Who @peer1 follows (2nd-hop: @owner -> @peer1 -> @peer2)
            val secondHopLogins = fetchUserFollowingLogins(peer1, maxUsers = 2)
                .filter { !it.equals(owner, ignoreCase = true) && it !in firstHopLogins }
            for (peer2 in secondHopLogins.take(2)) {
                val peer2Repos = fetchUserRepositories(peer2, perPage = 2)
                for (r in peer2Repos) {
                    rawCandidates.add(
                        DeveloperGraphEngine.PeerRepository(
                            slug = "${r.owner}/${r.name}",
                            name = r.name,
                            owner = r.owner,
                            description = r.description,
                            stars = r.stars,
                            language = r.language.ifBlank { language },
                            connectionReason = DeveloperGraphEngine.ConnectionType.SECOND_DEGREE_PEER,
                            connectedViaAuthor = owner,
                            peerFollowerCount = 0,
                            socialChain = listOf(owner, peer1, peer2)
                        )
                    )
                }

                val peer2Stars = fetchUserStarredRepositories(peer2, perPage = 2)
                for (r in peer2Stars.take(1)) {
                    rawCandidates.add(
                        DeveloperGraphEngine.PeerRepository(
                            slug = "${r.owner}/${r.name}",
                            name = r.name,
                            owner = r.owner,
                            description = r.description,
                            stars = r.stars,
                            language = r.language.ifBlank { language },
                            connectionReason = DeveloperGraphEngine.ConnectionType.SECOND_DEGREE_STARRED,
                            connectedViaAuthor = owner,
                            peerFollowerCount = 0,
                            socialChain = listOf(owner, peer1, peer2)
                        )
                    )
                }
            }
        }

        // 5. Also cross-check related apps in the bundled trending catalog (same language / shared domain keywords)
        if (useBundledCatalogFallback) {
            val catalog = BundledTrendingCatalog.loadCatalog()
            val allBundled = catalog?.lists?.values?.flatten().orEmpty()
                .distinctBy { "${it.owner}/${it.name}".lowercase() }
            for (b in allBundled) {
                val bSlug = "${b.owner}/${b.name}".lowercase()
                if (bSlug == targetSlugLower) continue
                if (b.owner.equals(owner, ignoreCase = true)) {
                    rawCandidates.add(
                        DeveloperGraphEngine.PeerRepository(
                            slug = "${b.owner}/${b.name}",
                            name = b.name,
                            owner = b.owner,
                            description = b.description,
                            stars = b.stars,
                            language = b.language,
                            connectionReason = DeveloperGraphEngine.ConnectionType.SAME_AUTHOR,
                            connectedViaAuthor = owner,
                            peerFollowerCount = 0,
                            socialChain = listOf(owner),
                            screenshotUrl = BundledTrendingCatalog.materializeBundledScreenshot(b.bundledScreenshotResource, cacheDir)
                        )
                    )
                } else if (b.language.equals(language, ignoreCase = true)) {
                    rawCandidates.add(
                        DeveloperGraphEngine.PeerRepository(
                            slug = "${b.owner}/${b.name}",
                            name = b.name,
                            owner = b.owner,
                            description = b.description,
                            stars = b.stars,
                            language = b.language,
                            connectionReason = DeveloperGraphEngine.ConnectionType.RELATED_ARCHITECTURE,
                            connectedViaAuthor = owner,
                            peerFollowerCount = 0,
                            socialChain = listOf(owner, b.owner),
                            screenshotUrl = BundledTrendingCatalog.materializeBundledScreenshot(b.bundledScreenshotResource, cacheDir)
                        )
                    )
                }
            }
        }

        return DeveloperGraphEngine.rankAndFilterRelatedToApp(
            targetOwner = owner,
            targetName = repoName,
            targetLanguage = language,
            targetDescription = description,
            targetTopics = topics,
            candidates = rawCandidates,
            limit = limit
        )
    }

    /**
     * Resolves or generates the app preview screenshot on demand using [SmartAppImageGenerator],
     * writes the resulting PNG to disk cache, and returns `Pair(localFilePath, tierName)`.
     */
    fun resolveOrGenerateAppImage(
        owner: String,
        repoName: String,
        language: String,
        description: String,
        defaultBranch: String = "main",
        homepageUrl: String? = null,
        forceLocalBuild: Boolean = false,
        localRepoDir: Path? = null
    ): Pair<String, String>? {
        // If not forcing a fresh local build, check if we have a build-time bundled screenshot first
        if (!forceLocalBuild && useBundledCatalogFallback) {
            val catalog = BundledTrendingCatalog.loadCatalog()
            val bundledEntry = catalog?.lists?.values?.flatten()?.firstOrNull {
                it.owner.equals(owner, ignoreCase = true) && it.name.equals(repoName, ignoreCase = true)
            }
            if (bundledEntry?.bundledScreenshotResource != null) {
                val materialized = BundledTrendingCatalog.materializeBundledScreenshot(
                    bundledEntry.bundledScreenshotResource,
                    cacheDir
                )
                if (materialized != null) {
                    return materialized to bundledEntry.screenshotTier
                }
            }
        }

        val generated = imageGenerator.generateOrResolve(
            owner = owner,
            repo = repoName,
            language = language,
            description = description,
            defaultBranch = defaultBranch,
            homepageUrl = homepageUrl,
            localRepoDir = localRepoDir,
            preferLocalBuild = true
        )

        val targetDir = cacheDir?.resolve("discovery/shots")
            ?: Path.of(System.getProperty("java.io.tmpdir"), "building_discovery_shots")
        return try {
            Files.createDirectories(targetDir)
            val safeOwner = owner.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val safeRepo = repoName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val outFile = targetDir.resolve("${safeOwner}__${safeRepo}__${generated.tier.name.lowercase()}.png")
            Files.write(outFile, generated.bytes)
            outFile.toAbsolutePath().toString() to generated.tier.name
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Fetches the logins of developers that [login] follows (via REST API first, then GitHub HTML `?tab=following`).
     */
    fun fetchUserFollowingLogins(login: String, maxUsers: Int = 5): List<String> {
        // 1. Try REST API if reachable
        val apiUrl = "$apiBase/users/$login/following?per_page=$maxUsers"
        val apiText = executeGet(apiUrl)
        if (!apiText.isNullOrBlank()) {
            try {
                val arr = json.parseToJsonElement(apiText).jsonArray
                val logins = arr.mapNotNull { it.jsonObject["login"]?.jsonPrimitive?.content }.take(maxUsers)
                if (logins.isNotEmpty()) return logins
            } catch (_: Exception) {
            }
        }

        // 2. Fallback to GitHub HTML `?tab=following` (zero API quota)
        if (apiBase != DEFAULT_API_BASE && webBase == DEFAULT_WEB_BASE) return emptyList()
        val html = executeWebGet("$webBase/$login?tab=following") ?: return emptyList()
        return parseFollowingHtml(html, login).take(maxUsers)
    }

    fun parseFollowingHtml(html: String, excludeLogin: String = ""): List<String> {
        val spanRegex = Regex("""<span\s+class=["'][^"']*Link--secondary[^"']*["'][^>]*>([A-Za-z0-9-]+)</span>""")
        return spanRegex.findAll(html)
            .map { it.groupValues[1].trim() }
            .filter { it.isNotBlank() && !it.equals(excludeLogin, ignoreCase = true) }
            .distinct()
            .toList()
    }

    /**
     * Fetches repositories starred by [login] (via REST API first, then GitHub HTML `?tab=stars`).
     */
    fun fetchUserStarredRepositories(login: String, perPage: Int = 5): List<RawRepoItem> {
        val apiUrl = "$apiBase/users/$login/starred?per_page=$perPage"
        val apiText = executeGet(apiUrl)
        if (!apiText.isNullOrBlank()) {
            try {
                val arr = json.parseToJsonElement(apiText).jsonArray
                val items = parseRestRepoArray(arr).take(perPage)
                if (items.isNotEmpty()) return items
            } catch (_: Exception) {
            }
        }

        if (apiBase != DEFAULT_API_BASE && webBase == DEFAULT_WEB_BASE) return emptyList()
        val html = executeWebGet("$webBase/$login?tab=stars") ?: return emptyList()
        return parseStarredOrUserReposHtml(html).take(perPage)
    }

    /**
     * Fetches repositories owned by [login] (via REST API first, then GitHub HTML `?tab=repositories`).
     */
    fun fetchUserRepositories(login: String, perPage: Int = 5): List<RawRepoItem> {
        val apiUrl = "$apiBase/users/$login/repos?sort=pushed&per_page=$perPage"
        val apiText = executeGet(apiUrl)
        if (!apiText.isNullOrBlank()) {
            try {
                val arr = json.parseToJsonElement(apiText).jsonArray
                val items = parseRestRepoArray(arr).take(perPage)
                if (items.isNotEmpty()) return items
            } catch (_: Exception) {
            }
        }

        if (apiBase != DEFAULT_API_BASE && webBase == DEFAULT_WEB_BASE) return emptyList()
        val html = executeWebGet("$webBase/$login?tab=repositories") ?: return emptyList()
        return parseStarredOrUserReposHtml(html, defaultOwner = login).take(perPage)
    }

    /**
     * Extracts the top human contributors for a repository (useful when [owner] is a GitHub Organization).
     */
    fun fetchRepoContributorLogins(owner: String, repoName: String, maxUsers: Int = 3): List<String> {
        val apiUrl = "$apiBase/repos/$owner/$repoName/contributors?per_page=${maxUsers + 2}"
        val apiText = executeGet(apiUrl)
        if (!apiText.isNullOrBlank()) {
            try {
                val arr = json.parseToJsonElement(apiText).jsonArray
                val logins = arr.mapNotNull { it.jsonObject["login"]?.jsonPrimitive?.content }
                    .filter {
                        !it.equals(owner, ignoreCase = true) &&
                            !it.endsWith("[bot]") &&
                            !it.endsWith("-bot") &&
                            it != "web-flow" &&
                            it != "claude"
                    }
                    .take(maxUsers)
                if (logins.isNotEmpty()) return logins
            } catch (_: Exception) {
            }
        }

        if (apiBase != DEFAULT_API_BASE && webBase == DEFAULT_WEB_BASE) return emptyList()
        val commitsHtml = executeWebGet("$webBase/$owner/$repoName/commits") ?: return emptyList()
        val loginRegex = Regex(""""login"\s*:\s*"([A-Za-z0-9-]+)"""")
        return loginRegex.findAll(commitsHtml)
            .map { it.groupValues[1].trim() }
            .filter {
                it.isNotBlank() &&
                    !it.equals(owner, ignoreCase = true) &&
                    !it.endsWith("-bot") &&
                    it != "web-flow" &&
                    it != "claude"
            }
            .distinct()
            .take(maxUsers)
            .toList()
    }

    fun parseStarredOrUserReposHtml(html: String, defaultOwner: String = ""): List<RawRepoItem> {
        if (html.isBlank()) return emptyList()
        val results = mutableListOf<RawRepoItem>()
        // Split on each repository item container (`?tab=stars` uses `div.col-12.d-block`, `?tab=repositories` uses `li.public.source`)
        val itemStartRegex = Regex(
            """<(?:div|li)\s+class=["'][^"']*(?:col-12\s+d-block|public\s+source)[^"']*["'][^>]*>""",
            RegexOption.IGNORE_CASE
        )
        val hrefRegex = Regex("""<h3[^>]*>[\s\S]*?<a\s+[^>]*?href=["']/([^/"'\s]+)/([^/"'\s]+)["'][^>]*>""", RegexOption.IGNORE_CASE)
        val fallbackHrefRegex = Regex("""<a\s+[^>]*?href=["']/([^/"'\s]+)/([^/"'\s]+)["'][^>]*>""", RegexOption.IGNORE_CASE)
        val descRegex = Regex("""<p\s+[^>]*?itemprop=["']description["'][^>]*>([\s\S]*?)</p>""", RegexOption.IGNORE_CASE)
        val langRegex = Regex("""itemprop=["']programmingLanguage["'][^>]*>([^<]+)</span>""", RegexOption.IGNORE_CASE)
        val starsRegex = Regex("""href=["']/[^"']+/stargazers["'][^>]*>([\s\S]*?)</a>""", RegexOption.IGNORE_CASE)

        val chunks = itemStartRegex.split(html).drop(1)
        for (rawChunk in chunks) {
            val block = rawChunk.take(4000)
            val hrefMatch = hrefRegex.find(block) ?: fallbackHrefRegex.find(block) ?: continue
            val owner = hrefMatch.groupValues[1].trim().ifBlank { defaultOwner }
            val name = hrefMatch.groupValues[2].trim()
            if (owner.isBlank() || name.isBlank() || owner in setOf("login", "sponsors", "orgs", "topics")) continue
            val desc = stripHtmlTags(descRegex.find(block)?.groupValues?.getOrNull(1).orEmpty()).trim()
            val lang = langRegex.find(block)?.groupValues?.getOrNull(1)?.trim().orEmpty()
            val stars = parseCompactCount(stripHtmlTags(starsRegex.find(block)?.groupValues?.getOrNull(1).orEmpty()))

            results.add(
                RawRepoItem(
                    owner = owner,
                    name = name,
                    description = desc,
                    stars = stars.coerceAtLeast(15),
                    forks = 0,
                    language = decodeHtmlEntities(lang),
                    htmlUrl = "https://github.com/$owner/$name"
                )
            )
        }
        return results.distinctBy { "${it.owner}/${it.name}".lowercase() }
    }

    /**
     * Searches repositories on GitHub with query parameters.
     */
    fun searchRepositories(
        query: String,
        category: String = "all",
        sort: String = "stars",
        order: String = "desc",
        limit: Int = 10
    ): List<RawRepoItem> = searchRepositoriesWithOrigin(query, category, sort, order, limit).first

    private fun searchRepositoriesWithOrigin(
        query: String,
        category: String = "all",
        sort: String = "stars",
        order: String = "desc",
        limit: Int = 10
    ): Pair<List<RawRepoItem>, Boolean> {
        val url = "$apiBase/search/repositories?q=${java.net.URLEncoder.encode(query, "UTF-8")}&sort=$sort&order=$order&per_page=$limit"
        val responseText = executeGet(url) ?: return emptyList<RawRepoItem>() to true

        return try {
            val root = json.parseToJsonElement(responseText).jsonObject
            val items = root["items"]?.jsonArray ?: JsonArray(emptyList())
            val parsed = parseRestRepoArray(items)
            parsed to false
        } catch (_: Exception) {
            emptyList<RawRepoItem>() to true
        }
    }

    private fun parseRestRepoArray(items: JsonArray): List<RawRepoItem> {
        return items.filterIsInstance<JsonObject>().mapNotNull { item ->
            val ownerObj = item["owner"]?.jsonObject
            val owner = ownerObj?.get("login")?.jsonPrimitive?.content ?: return@mapNotNull null
            val name = item["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val isFork = item["fork"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
            if (isFork) return@mapNotNull null
            RawRepoItem(
                owner = owner,
                name = name,
                description = item["description"]?.jsonPrimitive?.content.orEmpty(),
                stars = item["stargazers_count"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                forks = item["forks_count"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                language = item["language"]?.jsonPrimitive?.content.orEmpty(),
                htmlUrl = item["html_url"]?.jsonPrimitive?.content ?: "https://github.com/$owner/$name",
                defaultBranch = item["default_branch"]?.jsonPrimitive?.content ?: "main",
                topics = item["topics"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(),
                homepageUrl = item["homepage"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            )
        }
    }

    /**
     * Traverses the author's peer network: who they follow, and repos from those peers.
     */
    fun getAuthorPeerProjects(authorLogin: String, limit: Int = 5): List<DeveloperGraphEngine.PeerRepository> {
        return crawlRelatedAndAuthorNetwork(
            owner = authorLogin,
            repoName = "",
            language = "",
            description = "",
            limit = limit
        )
    }

    /**
     * Enriches a raw repository item into a full [GitHubDiscoveryEngine.DiscoveredRepository].
     */
    fun enrichRemoteRepo(
        repo: RawRepoItem,
        skipRemoteMetadata: Boolean = false,
        localScreenshotPath: String? = null,
        screenshotSourceTier: String = "OPENGRAPH_FALLBACK"
    ): GitHubDiscoveryEngine.DiscoveredRepository {
        val readmeContent = if (skipRemoteMetadata) null else {
            val rawReadmeUrl = "https://raw.githubusercontent.com/${repo.owner}/${repo.name}/${repo.defaultBranch}/README.md"
            executeGet(rawReadmeUrl)
        }
        val manifestXml = if (skipRemoteMetadata) null else {
            val rawManifestUrl = "https://raw.githubusercontent.com/${repo.owner}/${repo.name}/${repo.defaultBranch}/app/src/main/AndroidManifest.xml"
            executeGet(rawManifestUrl)
        }

        return engine.enrichRepository(
            owner = repo.owner,
            name = repo.name,
            description = repo.description,
            stars = repo.stars,
            forks = repo.forks,
            language = repo.language,
            htmlUrl = repo.htmlUrl,
            readmeContent = readmeContent,
            manifestXml = manifestXml,
            topics = repo.topics,
            starsInPeriod = repo.starsInPeriod,
            trendingPeriod = repo.trendingPeriod,
            homepageUrl = repo.homepageUrl,
            defaultBranch = repo.defaultBranch,
            builtByAvatars = repo.builtByAvatars,
            localScreenshotPath = localScreenshotPath,
            screenshotSourceTier = screenshotSourceTier
        )
    }

    private fun bundledEntryToDiscoveredRepo(entry: BundledTrendingCatalog.BundledRepoEntry): GitHubDiscoveryEngine.DiscoveredRepository {
        val materializedShot = BundledTrendingCatalog.materializeBundledScreenshot(
            entry.bundledScreenshotResource,
            cacheDir
        )
        val relatedPeers = entry.relatedPeers.map { bundledPeerToEnginePeer(it) }
        val enriched = engine.enrichRepository(
            owner = entry.owner,
            name = entry.name,
            description = entry.description,
            stars = entry.stars,
            forks = entry.forks,
            language = entry.language,
            htmlUrl = entry.htmlUrl,
            topics = entry.topics,
            starsInPeriod = entry.starsInPeriod,
            trendingPeriod = entry.period,
            homepageUrl = entry.homepageUrl,
            defaultBranch = entry.defaultBranch,
            builtByAvatars = entry.builtByAvatars,
            localScreenshotPath = materializedShot,
            screenshotSourceTier = entry.screenshotTier,
            relatedPeers = relatedPeers
        )
        return if (materializedShot != null) {
            val bundledCandidate = ScreenshotExtractor.ScreenshotCandidate(
                url = materializedShot,
                tier = ScreenshotExtractor.AggressivenessTier.TIER_5_ACTIVE_HEADLESS_RENDER,
                confidenceScore = 1.0,
                caption = entry.screenshotOrigin,
                isHeroBanner = true
            )
            enriched.copy(screenshots = listOf(bundledCandidate) + enriched.screenshots)
        } else {
            enriched
        }
    }

    private fun bundledPeerToEnginePeer(p: BundledTrendingCatalog.BundledPeerEntry): DeveloperGraphEngine.PeerRepository {
        val connType = runCatching {
            DeveloperGraphEngine.ConnectionType.valueOf(p.connectionType)
        }.getOrDefault(DeveloperGraphEngine.ConnectionType.FOLLOWED_PEER)
        return DeveloperGraphEngine.PeerRepository(
            slug = p.slug,
            name = p.name,
            owner = p.owner,
            description = p.description,
            stars = p.stars,
            language = p.language,
            connectionReason = connType,
            connectedViaAuthor = p.connectedViaAuthor,
            peerFollowerCount = 0,
            socialChain = p.socialChain,
            relevanceScore = p.relevanceScore,
            relevanceReason = p.relevanceReason,
            screenshotUrl = ScreenshotExtractor.openGraphCardUrl(p.owner, p.name)
        )
    }

    private fun computeCrossComparisons(
        enriched: List<GitHubDiscoveryEngine.DiscoveredRepository>
    ): Pair<List<RepoShapeEngine.ComparisonResult>, List<RepoShapeEngine.ComparisonResult>> {
        val northStars = mutableListOf<RepoShapeEngine.ComparisonResult>()
        val highVelocity = mutableListOf<RepoShapeEngine.ComparisonResult>()

        for (i in enriched.indices) {
            for (j in enriched.indices) {
                if (i == j) continue
                val targetFull = "${enriched[i].owner}/${enriched[i].name}"
                val candidateFull = "${enriched[j].owner}/${enriched[j].name}"
                RepoShapeEngine.evaluateComparison(
                    targetSlug = targetFull,
                    targetGenome = enriched[i].genome,
                    candidateSlug = candidateFull,
                    candidateGenome = enriched[j].genome,
                    lens = RepoShapeEngine.ComparisonLens.NORTH_STAR
                )?.let { northStars.add(it) }

                RepoShapeEngine.evaluateComparison(
                    targetSlug = targetFull,
                    targetGenome = enriched[i].genome,
                    candidateSlug = candidateFull,
                    candidateGenome = enriched[j].genome,
                    lens = RepoShapeEngine.ComparisonLens.HIGH_VELOCITY
                )?.let { highVelocity.add(it) }
            }
        }
        return northStars to highVelocity
    }

    /**
     * Downloads a screenshot image URL into the local disk cache for instant UI rendering.
     * Returns the local absolute path if successful.
     */
    fun cacheScreenshot(url: String): String? {
        if (url.startsWith("/") || url.startsWith("file://")) {
            val cleanPath = url.removePrefix("file://")
            if (Files.exists(Paths.get(cleanPath))) return cleanPath
        }
        if (cacheDir == null) return null
        val filename = "${url.hashCode().toUInt().toString(16)}.img"
        val targetPath = cacheDir.resolve("discovery/shots/$filename")
        if (Files.exists(targetPath) && Files.size(targetPath) > 0) {
            return targetPath.toAbsolutePath().toString()
        }

        Files.createDirectories(targetPath.parent)
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Building-IDE/1.0 (Android; GitHubExplorer)")
            .build()

        return try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val bytes = response.body?.bytes() ?: return null
                Files.write(targetPath, bytes)
                targetPath.toAbsolutePath().toString()
            }
        } catch (_: IOException) {
            null
        }
    }

    /**
     * Retrieves the image bytes for a screenshot URL or local file path, reading from disk cache
     * if present, or fetching remotely.
     */
    fun loadScreenshotBytes(url: String): ByteArray? {
        if (url.startsWith("/") || url.startsWith("file://")) {
            val path = Paths.get(url.removePrefix("file://"))
            if (Files.exists(path)) {
                return runCatching { Files.readAllBytes(path) }.getOrNull()
            }
        }
        if (url.startsWith("discovery/shots/")) {
            BundledTrendingCatalog.loadBundledScreenshotBytes(url)?.let { return it }
        }
        val cachedPath = cacheScreenshot(url)
        if (cachedPath != null) {
            return try {
                Files.readAllBytes(Paths.get(cachedPath))
            } catch (_: IOException) {
                null
            }
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) return null
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Building-IDE/1.0 (Android; GitHubExplorer)")
            .build()
        return try {
            http.newCall(request).execute().use { response ->
                if (response.isSuccessful) response.body?.bytes() else null
            }
        } catch (_: IOException) {
            null
        }
    }

    internal fun executeGet(url: String): String? {
        val builder = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Building-IDE/1.0 (Android; GitHubExplorer)")

        if (!token.isNullOrBlank()) {
            builder.header("Authorization", "Bearer $token")
        }

        return try {
            http.newCall(builder.build()).execute().use { response ->
                if (response.isSuccessful) response.body?.string() else null
            }
        } catch (_: IOException) {
            null
        }
    }

    internal fun executeWebGet(url: String): String? {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return null
        val builder = Request.Builder()
            .url(url)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 15; Pixel 11 Pro XL) AppleWebKit/537.36 Chrome/131.0.0.0 Mobile Safari/537.36")

        return try {
            http.newCall(builder.build()).execute().use { response ->
                if (response.isSuccessful) response.body?.string() else null
            }
        } catch (_: Exception) {
            null
        }
    }

    fun getOfflineSampleRepositories(category: String = "all"): List<RawRepoItem> = emptyList()

    private fun getOfflineAuthorPeerPicks(category: String): List<DeveloperGraphEngine.PeerRepository> = emptyList()

    /**
     * Tests a token against GitHub's /user and /rate_limit endpoints.
     * Returns Pair(username, Pair(limit, remaining)) if valid, or null if invalid.
     */
    fun verifyToken(tokenToTest: String): Pair<String, Pair<Int, Int>>? {
        val testToken = tokenToTest.trim()
        if (testToken.isBlank()) return null
        val req = Request.Builder()
            .url("$apiBase/user")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Building-IDE/1.0 (Android; GitHubExplorer)")
            .header("Authorization", "Bearer $testToken")
            .build()

        return try {
            http.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return null
                val body = res.body?.string() ?: return null
                val userObj = json.parseToJsonElement(body).jsonObject
                val login = userObj["login"]?.jsonPrimitive?.content ?: return null
                val rate = fetchRateLimit(testToken)
                login to rate
            }
        } catch (_: Exception) {
            null
        }
    }

    fun fetchRateLimit(tokenToUse: String? = token): Pair<Int, Int> {
        val req = Request.Builder()
            .url("$apiBase/rate_limit")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Building-IDE/1.0 (Android; GitHubExplorer)")

        if (!tokenToUse.isNullOrBlank()) {
            req.header("Authorization", "Bearer $tokenToUse")
        }

        return try {
            http.newCall(req.build()).execute().use { res ->
                if (!res.isSuccessful) return if (tokenToUse.isNullOrBlank()) 60 to 60 else 5000 to 5000
                val body = res.body?.string() ?: return 60 to 60
                val obj = json.parseToJsonElement(body).jsonObject
                val core = obj["resources"]?.jsonObject?.get("core")?.jsonObject
                val limit = core?.get("limit")?.jsonPrimitive?.content?.toIntOrNull() ?: 60
                val remaining = core?.get("remaining")?.jsonPrimitive?.content?.toIntOrNull() ?: 60
                limit to remaining
            }
        } catch (_: Exception) {
            if (tokenToUse.isNullOrBlank()) 60 to 60 else 5000 to 5000
        }
    }

    data class RawRepoItem(
        val owner: String,
        val name: String,
        val description: String,
        val stars: Int,
        val forks: Int,
        val language: String,
        val htmlUrl: String,
        val defaultBranch: String = "main",
        val topics: List<String> = emptyList(),
        val starsInPeriod: Int = 0,
        val trendingPeriod: String = "daily",
        val homepageUrl: String? = null,
        val builtByAvatars: List<String> = emptyList()
    )

    companion object {
        const val DEFAULT_API_BASE = "https://api.github.com"
        const val DEFAULT_WEB_BASE = "https://github.com"

        private fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()

        internal fun stripHtmlTags(raw: String): String {
            return decodeHtmlEntities(raw.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " "))
        }

        internal fun decodeHtmlEntities(raw: String): String {
            return raw
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&nbsp;", " ")
        }

        internal fun parseCompactCount(raw: String): Int {
            val clean = raw.lowercase().replace(",", "").trim()
            if (clean.isBlank()) return 0
            val numMatch = Regex("""(\d+(?:\.\d+)?)\s*([km])?""").find(clean) ?: return 0
            val base = numMatch.groupValues[1].toDoubleOrNull() ?: return 0
            val suffix = numMatch.groupValues[2]
            return when (suffix) {
                "k" -> (base * 1_000).toInt()
                "m" -> (base * 1_000_000).toInt()
                else -> base.toInt()
            }
        }

        internal fun normLangDisplayName(key: String): String = when (key) {
            "kotlin" -> "Kotlin"
            "swift" -> "Swift"
            "python" -> "Python"
            "typescript" -> "TypeScript"
            "javascript" -> "JavaScript"
            "java" -> "Java"
            "rust" -> "Rust"
            "c++" -> "C++"
            else -> "Kotlin"
        }
    }
}
