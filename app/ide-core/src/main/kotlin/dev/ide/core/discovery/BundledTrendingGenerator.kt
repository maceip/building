package dev.ide.core.discovery

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import javax.imageio.ImageIO

/**
 * Build-time generator that crawls live GitHub Trending (`daily`, `weekly`, `monthly`) across
 * all popular languages supported on the phone (`all`, `kotlin`, `swift`, `python`, `typescript`,
 * `javascript`, `java`, `rust`, `c++`), resolves/builds/synthesizes a screenshot for each app,
 * traverses the author's 2-hop social graph + starred repos + related repos, and writes:
 * - `src/main/resources/discovery/bundled-trending-catalog.json`
 * - `src/main/resources/discovery/shots/<owner>__<repo>.png`
 */
object BundledTrendingGenerator {

    @JvmStatic
    fun main(args: Array<String>) {
        val outputRoot = if (args.isNotEmpty()) {
            Paths.get(args[0])
        } else {
            Paths.get("app/ide-core/src/main/resources")
        }
        val itemsPerBucket = args.getOrNull(1)?.toIntOrNull() ?: 4
        generateBundle(outputRoot, itemsPerBucket)
    }

    fun generateBundle(resourcesRoot: Path, itemsPerBucket: Int = 4) {
        val discoveryDir = resourcesRoot.resolve("discovery")
        val shotsDir = discoveryDir.resolve("shots")
        Files.createDirectories(shotsDir)

        val token = System.getenv("GITHUB_TOKEN")?.takeIf { it.isNotBlank() }
        val client = GitHubDiscoveryClient(
            token = token,
            cacheDir = Paths.get(System.getProperty("java.io.tmpdir"), "building_discovery_gen_cache"),
            useBundledCatalogFallback = false
        )

        println("[BundledTrendingGenerator] Crawling GitHub Trending (daily, weekly, monthly) for ${BundledTrendingCatalog.SUPPORTED_LANGUAGE_KEYS.size} languages...")

        val rawBuckets = ConcurrentHashMap<String, List<GitHubDiscoveryClient.RawRepoItem>>()
        val pool = Executors.newFixedThreadPool(8)

        try {
            // 1. Fetch all 27 (language, period) trending lists in parallel
            val bucketTasks = BundledTrendingCatalog.SUPPORTED_LANGUAGE_KEYS.flatMap { langKey ->
                BundledTrendingCatalog.SUPPORTED_PERIODS.map { period ->
                    Callable {
                        val key = BundledTrendingCatalog.listKey(langKey, period)
                        val fetched = client.fetchLiveTrendingRepositories(
                            language = langKey,
                            period = period,
                            limit = itemsPerBucket
                        )
                        rawBuckets[key] = fetched
                        println("  -> Fetched $key (${fetched.size} trending repos)")
                    }
                }
            }
            pool.invokeAll(bucketTasks)

            // 2. Deduplicate unique repositories across all buckets so we only generate screenshot & crawl author graph once per unique repo
            val uniqueRepos = LinkedHashMap<String, GitHubDiscoveryClient.RawRepoItem>()
            for (list in rawBuckets.values) {
                for (item in list) {
                    val slugKey = "${item.owner}/${item.name}".lowercase()
                    uniqueRepos.putIfAbsent(slugKey, item)
                }
            }

            println("[BundledTrendingGenerator] Enriching ${uniqueRepos.size} unique trending repositories (screenshots + 2-hop author network)...")

            data class EnrichedAsset(
                val resourceRelPath: String,
                val tier: String,
                val origin: String,
                val peers: List<BundledTrendingCatalog.BundledPeerEntry>
            )

            val enrichedBySlug = ConcurrentHashMap<String, EnrichedAsset>()
            val enrichTasks = uniqueRepos.entries.map { (slugKey, raw) ->
                Callable {
                    val safeFileSlug = "${raw.owner}__${raw.name}".replace(Regex("[^a-zA-Z0-9._-]"), "_")
                    val shotFileName = "$safeFileSlug.png"
                    val shotTargetFile = shotsDir.resolve(shotFileName)
                    val resourceRelPath = "discovery/shots/$shotFileName"

                    // Generate or resolve screenshot via SmartAppImageGenerator (Tier 1 -> Tier 2 -> Tier 3)
                    val generated = client.imageGenerator.generateOrResolve(
                        owner = raw.owner,
                        repo = raw.name,
                        language = raw.language,
                        description = raw.description,
                        defaultBranch = raw.defaultBranch,
                        homepageUrl = raw.homepageUrl,
                        preferLocalBuild = true
                    )

                    val compactBytes = compactImageToPng(generated.bytes, raw)
                    Files.write(shotTargetFile, compactBytes)

                    // Crawl author's 1st-hop & 2nd-hop social graph + starred repos
                    val peers = crawlAuthorGraphForBundle(client, raw, uniqueRepos.values.toList())

                    enrichedBySlug[slugKey] = EnrichedAsset(
                        resourceRelPath = resourceRelPath,
                        tier = generated.tier.name,
                        origin = generated.originDescription,
                        peers = peers
                    )
                }
            }
            pool.invokeAll(enrichTasks)

            // 3. Assemble final BundledCatalogData
            val listsMap = LinkedHashMap<String, List<BundledTrendingCatalog.BundledRepoEntry>>()
            for (langKey in BundledTrendingCatalog.SUPPORTED_LANGUAGE_KEYS) {
                for (period in BundledTrendingCatalog.SUPPORTED_PERIODS) {
                    val key = BundledTrendingCatalog.listKey(langKey, period)
                    val rawList = rawBuckets[key].orEmpty()
                    val bundledList = rawList.map { raw ->
                        val slugKey = "${raw.owner}/${raw.name}".lowercase()
                        val asset = enrichedBySlug[slugKey]
                        BundledTrendingCatalog.BundledRepoEntry(
                            owner = raw.owner,
                            name = raw.name,
                            description = raw.description,
                            language = raw.language,
                            stars = raw.stars,
                            forks = raw.forks,
                            starsInPeriod = raw.starsInPeriod,
                            period = period,
                            languageKey = langKey,
                            htmlUrl = raw.htmlUrl,
                            homepageUrl = raw.homepageUrl,
                            defaultBranch = raw.defaultBranch,
                            builtByAvatars = raw.builtByAvatars,
                            topics = raw.topics,
                            bundledScreenshotResource = asset?.resourceRelPath,
                            screenshotTier = asset?.tier ?: "DYNAMIC_SOURCE_SYNTHESIS",
                            screenshotOrigin = asset?.origin ?: "Bundled at build time",
                            relatedPeers = asset?.peers.orEmpty()
                        )
                    }
                    listsMap[key] = bundledList
                }
            }

            val catalogData = BundledTrendingCatalog.BundledCatalogData(
                bundledAtIso = Instant.now().toString(),
                generatorVersion = "2.0",
                lists = listsMap
            )

            val jsonText = BundledTrendingCatalog.encodeCatalogJson(catalogData)
            val catalogFile = discoveryDir.resolve("bundled-trending-catalog.json")
            Files.writeString(catalogFile, jsonText)
            println("[BundledTrendingGenerator] Wrote ${catalogFile.toAbsolutePath()} (${listsMap.size} lists, ${enrichedBySlug.size} saved screenshots)")
        } finally {
            pool.shutdownNow()
        }
    }

    private fun crawlAuthorGraphForBundle(
        client: GitHubDiscoveryClient,
        target: GitHubDiscoveryClient.RawRepoItem,
        allTrendingPool: List<GitHubDiscoveryClient.RawRepoItem>
    ): List<BundledTrendingCatalog.BundledPeerEntry> {
        val candidates = mutableListOf<DeveloperGraphEngine.PeerRepository>()
        val owner = target.owner
        val targetSlug = "$owner/${target.name}".lowercase()

        // 1. Author's starred repos (via HTML ?tab=stars)
        for (s in client.fetchUserStarredRepositories(owner, perPage = 3)) {
            if ("${s.owner}/${s.name}".lowercase() == targetSlug) continue
            candidates.add(
                DeveloperGraphEngine.PeerRepository(
                    slug = "${s.owner}/${s.name}",
                    name = s.name,
                    owner = s.owner,
                    description = s.description,
                    stars = s.stars,
                    language = s.language.ifBlank { target.language },
                    connectionReason = DeveloperGraphEngine.ConnectionType.AUTHOR_STARRED,
                    connectedViaAuthor = owner,
                    peerFollowerCount = 0,
                    socialChain = listOf(owner, "★ ${s.owner}/${s.name}")
                )
            )
        }

        // 2. Author's other repos (via HTML ?tab=repositories)
        for (r in client.fetchUserRepositories(owner, perPage = 3)) {
            if ("${r.owner}/${r.name}".lowercase() == targetSlug) continue
            candidates.add(
                DeveloperGraphEngine.PeerRepository(
                    slug = "${r.owner}/${r.name}",
                    name = r.name,
                    owner = r.owner,
                    description = r.description,
                    stars = r.stars,
                    language = r.language.ifBlank { target.language },
                    connectionReason = DeveloperGraphEngine.ConnectionType.SAME_AUTHOR,
                    connectedViaAuthor = owner,
                    peerFollowerCount = 0,
                    socialChain = listOf(owner)
                )
            )
        }

        // 3. 1st-hop following (@owner -> @peer1) and 2nd-hop following (@owner -> @peer1 -> @peer2)
        val firstHop = client.fetchUserFollowingLogins(owner, maxUsers = 2)
        for (peer1 in firstHop) {
            for (r in client.fetchUserRepositories(peer1, perPage = 2)) {
                candidates.add(
                    DeveloperGraphEngine.PeerRepository(
                        slug = "${r.owner}/${r.name}",
                        name = r.name,
                        owner = r.owner,
                        description = r.description,
                        stars = r.stars,
                        language = r.language.ifBlank { target.language },
                        connectionReason = DeveloperGraphEngine.ConnectionType.FOLLOWED_PEER,
                        connectedViaAuthor = owner,
                        peerFollowerCount = 0,
                        socialChain = listOf(owner, peer1)
                    )
                )
            }
            for (s in client.fetchUserStarredRepositories(peer1, perPage = 2).take(1)) {
                candidates.add(
                    DeveloperGraphEngine.PeerRepository(
                        slug = "${s.owner}/${s.name}",
                        name = s.name,
                        owner = s.owner,
                        description = s.description,
                        stars = s.stars,
                        language = s.language.ifBlank { target.language },
                        connectionReason = DeveloperGraphEngine.ConnectionType.PEER_STARRED,
                        connectedViaAuthor = owner,
                        peerFollowerCount = 0,
                        socialChain = listOf(owner, peer1)
                    )
                )
            }
            val secondHop = client.fetchUserFollowingLogins(peer1, maxUsers = 1)
                .filter { !it.equals(owner, ignoreCase = true) }
            for (peer2 in secondHop) {
                for (r2 in client.fetchUserRepositories(peer2, perPage = 1)) {
                    candidates.add(
                        DeveloperGraphEngine.PeerRepository(
                            slug = "${r2.owner}/${r2.name}",
                            name = r2.name,
                            owner = r2.owner,
                            description = r2.description,
                            stars = r2.stars,
                            language = r2.language.ifBlank { target.language },
                            connectionReason = DeveloperGraphEngine.ConnectionType.SECOND_DEGREE_PEER,
                            connectedViaAuthor = owner,
                            peerFollowerCount = 0,
                            socialChain = listOf(owner, peer1, peer2)
                        )
                    )
                }
            }
        }

        // 4. Related architecture candidates from the trending pool
        for (other in allTrendingPool) {
            val otherSlug = "${other.owner}/${other.name}".lowercase()
            if (otherSlug == targetSlug) continue
            if (other.language.equals(target.language, ignoreCase = true)) {
                candidates.add(
                    DeveloperGraphEngine.PeerRepository(
                        slug = "${other.owner}/${other.name}",
                        name = other.name,
                        owner = other.owner,
                        description = other.description,
                        stars = other.stars,
                        language = other.language,
                        connectionReason = DeveloperGraphEngine.ConnectionType.RELATED_ARCHITECTURE,
                        connectedViaAuthor = owner,
                        peerFollowerCount = 0,
                        socialChain = listOf(owner, other.owner)
                    )
                )
            }
        }

        val ranked = DeveloperGraphEngine.rankAndFilterRelatedToApp(
            targetOwner = owner,
            targetName = target.name,
            targetLanguage = target.language,
            targetDescription = target.description,
            targetTopics = target.topics,
            candidates = candidates,
            limit = 8
        )

        return ranked.map { p ->
            BundledTrendingCatalog.BundledPeerEntry(
                slug = p.slug,
                name = p.name,
                owner = p.owner,
                description = p.description,
                stars = p.stars,
                language = p.language,
                connectionType = p.connectionReason.name,
                connectedViaAuthor = p.connectedViaAuthor,
                socialChain = p.socialChain,
                relevanceScore = p.relevanceScore,
                relevanceReason = p.relevanceReason
            )
        }
    }

    private fun compactImageToPng(rawBytes: ByteArray, raw: GitHubDiscoveryClient.RawRepoItem): ByteArray {
        if (rawBytes.size <= 95_000) return rawBytes
        return try {
            val srcImg = ImageIO.read(ByteArrayInputStream(rawBytes)) ?: return rawBytes
            val targetW = 560
            val targetH = ((srcImg.height.toDouble() / srcImg.width.toDouble()) * targetW).toInt().coerceIn(220, 360)
            val scaled = BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_RGB)
            val g2d = scaled.createGraphics()
            g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g2d.drawImage(srcImg, 0, 0, targetW, targetH, null)
            g2d.dispose()
            val out = ByteArrayOutputStream()
            ImageIO.write(scaled, "png", out)
            val result = out.toByteArray()
            if (result.isNotEmpty() && result.size < rawBytes.size) result else rawBytes
        } catch (_: Exception) {
            rawBytes
        }
    }
}
