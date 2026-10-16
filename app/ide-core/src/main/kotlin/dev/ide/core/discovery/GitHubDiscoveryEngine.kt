package dev.ide.core.discovery

/**
 * The unified GitHub Discovery Engine for `building`.
 *
 * Integrates:
 * 1. [ScreenshotExtractor] — Multi-tier visual resolution (OpenGraph -> Fastlane -> README -> Assets -> AST).
 * 2. [RepoShapeEngine] — Structural topology fingerprinting and multi-lens recommendations ("Shaped like X, but...").
 * 3. [CapabilityAnalyzer] — Runtime permissions, zero-cloud offline topology, native NDK, and feature tags.
 * 4. [DeveloperGraphEngine] — Author following network and peer repository discovery.
 */
class GitHubDiscoveryEngine {

    data class DiscoveredRepository(
        val slug: String,
        val owner: String,
        val name: String,
        val description: String,
        val stars: Int,
        val forks: Int,
        val language: String,
        val htmlUrl: String,
        val screenshots: List<ScreenshotExtractor.ScreenshotCandidate>,
        val genome: RepoShapeEngine.RepoGenome,
        val capabilities: CapabilityAnalyzer.CapabilityProfile,
        val authorAttribution: String? = null
    )

    data class DiscoveryFeed(
        val trendingHero: List<DiscoveredRepository>,
        val northStarBenchmarks: List<RepoShapeEngine.ComparisonResult>,
        val highVelocityRising: List<RepoShapeEngine.ComparisonResult>,
        val authorPeerPicks: List<DeveloperGraphEngine.PeerRepository>
    )

    /**
     * Enriches a raw repository with full visual, structural, and capability intelligence.
     */
    fun enrichRepository(
        owner: String,
        name: String,
        description: String,
        stars: Int,
        forks: Int,
        language: String,
        htmlUrl: String,
        readmeContent: String? = null,
        manifestXml: String? = null,
        buildScriptContent: String? = null,
        fileTreePaths: List<String> = emptyList(),
        topics: List<String> = emptyList(),
        license: String = "MIT",
        maxScreenshotTier: ScreenshotExtractor.AggressivenessTier = ScreenshotExtractor.AggressivenessTier.TIER_2_README_SCRAPE
    ): DiscoveredRepository {
        val slug = "$owner/$name".lowercase().replace('/', '-')

        // 1. Resolve Screenshots across tiers
        val screenshots = ScreenshotExtractor.resolveScreenshots(
            owner = owner,
            repo = name,
            readmeContent = readmeContent,
            fileTreePaths = fileTreePaths,
            maxTier = maxScreenshotTier
        )

        // 2. Infer Structural Genome
        val genome = RepoShapeEngine.inferGenome(
            filePaths = fileTreePaths,
            topics = topics,
            language = language,
            stars = stars,
            license = license,
            hasReleases = true
        )

        // 3. Analyze Runtime Capabilities & Features
        val capabilities = CapabilityAnalyzer.analyze(
            manifestXml = manifestXml,
            buildScriptContent = buildScriptContent,
            readmeContent = readmeContent,
            filePaths = fileTreePaths
        )

        return DiscoveredRepository(
            slug = slug,
            owner = owner,
            name = name,
            description = description,
            stars = stars,
            forks = forks,
            language = language,
            htmlUrl = htmlUrl,
            screenshots = screenshots,
            genome = genome,
            capabilities = capabilities
        )
    }

    /**
     * Evaluates a candidate pool of repositories to find peers "Shaped like [target]"
     * across different lenses (North Star, High-Velocity, Modern Upgrade, Cross-Platform).
     */
    fun findShapedPeers(
        target: DiscoveredRepository,
        candidates: List<DiscoveredRepository>,
        lens: RepoShapeEngine.ComparisonLens,
        limit: Int = 5
    ): List<RepoShapeEngine.ComparisonResult> {
        return candidates
            .mapNotNull { candidate ->
                RepoShapeEngine.evaluateComparison(
                    targetSlug = target.slug,
                    targetGenome = target.genome,
                    candidateSlug = candidate.slug,
                    candidateGenome = candidate.genome,
                    lens = lens
                )
            }
            .sortedByDescending { it.structuralMatchScore }
            .take(limit)
    }

    /**
     * Diffs two discovered repositories by capability, privacy, and feature tags.
     */
    fun compareRepositories(
        repoA: DiscoveredRepository,
        repoB: DiscoveredRepository
    ): CapabilityAnalyzer.CapabilityDiff {
        return CapabilityAnalyzer.diffCapabilities(
            slugA = repoA.name,
            profileA = repoA.capabilities,
            slugB = repoB.name,
            profileB = repoB.capabilities
        )
    }
}
