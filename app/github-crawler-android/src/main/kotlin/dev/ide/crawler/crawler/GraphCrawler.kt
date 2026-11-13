package dev.ide.crawler.crawler

/**
 * The developer / repository graph crawler.
 *
 * Ported from the in-app crawler
 * (`app/ide-core/src/main/kotlin/dev/ide/core/discovery/`):
 *
 *  * `GitHubDiscoveryClient.crawlRelatedAndAuthorNetwork` - the 2-hop
 *    traversal (author repos, author stars, followed peers, their repos and
 *    stars, second-degree peers).
 *  * `DeveloperGraphEngine.rankAndFilterRelatedToApp` - the relevance
 *    scoring (connection weight + language bonus + keyword bonus + star
 *    bonus).
 *  * `DeveloperGraphEngine.filterAndRankPeerProjects` - the minimum-star
 *    and fork filtering.
 *
 * Graph model: nodes are `developer` (login) and `repo` (owner/name); edges
 * are `AUTHORED`, `STARRED`, `FOLLOWS` and `RELATED`. The Kotlin
 * `ConnectionType` label is preserved in the edge `reason`.
 */
class GraphCrawler(
    private val api: GitHubApi,
    private val store: CrawlerStore,
    private val log: (String) -> Unit = {},
) {

    /** Edge relation vocabulary - identical to the macOS daemon. */
    object Relations {
        const val AUTHORED = "AUTHORED"
        const val STARRED = "STARRED"
        const val FOLLOWS = "FOLLOWS"
        const val RELATED = "RELATED"
    }

    private fun tokens(vararg values: String?): Set<String> = RelevanceScorer.tokens(*values)

    private fun nameTokens(name: String?): Set<String> = RelevanceScorer.nameTokens(name)

    /**
     * Port of `DeveloperGraphEngine.rankAndFilterRelatedToApp` scoring.
     */
    fun relevanceScore(
        connection: String,
        targetLanguage: String,
        peerLanguage: String,
        targetKeywords: Set<String>,
        peerDescription: String,
        peerName: String,
        peerStars: Int,
    ): Pair<Int, String> = RelevanceScorer.score(
        connection = connection,
        targetLanguage = targetLanguage,
        peerLanguage = peerLanguage,
        targetKeywords = targetKeywords,
        peerDescription = peerDescription,
        peerName = peerName,
        peerStars = peerStars,
    )

    // ------------------------------------------------------------------
    /**
     * Expand one developer and (optionally) their follow graph.
     */
    fun expandDeveloper(login: String, depth: Int = CrawlerConfig.GRAPH_MAX_DEPTH): Int {
        val normalised = login.lowercase()
        val bundle = api.developerBundle(normalised) ?: run {
            log("  developer $normalised: no data")
            return 0
        }
        store.upsertDeveloper(bundle.developer)

        var edges = 0
        bundle.repos.forEach { repo ->
            if (repo.slug.isBlank()) return@forEach
            store.upsertRepo(repo, "graph")
            if (store.addEdge(
                    "developer", normalised, "repo", repo.slug, Relations.AUTHORED,
                    RelevanceScorer.SAME_AUTHOR, "Other Work by Author",
                )
            ) edges++
        }
        bundle.starred.forEach { repo ->
            if (repo.slug.isBlank()) return@forEach
            store.upsertRepo(repo, "graph")
            if (store.addEdge(
                    "developer", normalised, "repo", repo.slug, Relations.STARRED,
                    RelevanceScorer.AUTHOR_STARRED, "Starred by Author",
                )
            ) edges++
        }
        bundle.following.forEach { followed ->
            if (store.addEdge(
                    "developer", normalised, "developer", followed, Relations.FOLLOWS,
                    RelevanceScorer.FOLLOWED_PEER, "Built by Author's Trusted Peer",
                )
            ) edges++
            // Seed the followed account as a developer node so it joins the
            // frontier and gets expanded on a later cycle. Without this the
            // traversal never reaches a second hop.
            store.upsertDeveloper(CrawlerDeveloper(login = followed))
        }

        // Second hop: expand a couple of the followed peers.
        if (depth > 1) {
            bundle.following.take(maxOf(1, CrawlerConfig.PEER_FETCH_COUNT / 2)).forEach { peer ->
                val peerBundle = api.developerBundle(
                    peer,
                    repoCount = maxOf(2, CrawlerConfig.PEER_FETCH_COUNT / 2),
                    starCount = maxOf(2, CrawlerConfig.PEER_FETCH_COUNT / 2),
                    followCount = 2,
                ) ?: return@forEach
                store.upsertDeveloper(peerBundle.developer)
                peerBundle.repos.forEach { repo ->
                    if (repo.slug.isBlank()) return@forEach
                    store.upsertRepo(repo, "graph")
                    if (store.addEdge(
                            "developer", peer, "repo", repo.slug, Relations.AUTHORED,
                            RelevanceScorer.FOLLOWED_PEER, "Built by Author's Trusted Peer",
                        )
                    ) edges++
                }
                peerBundle.starred.forEach { repo ->
                    if (repo.slug.isBlank()) return@forEach
                    store.upsertRepo(repo, "graph")
                    if (store.addEdge(
                            "developer", peer, "repo", repo.slug, Relations.STARRED,
                            RelevanceScorer.PEER_STARRED, "Starred by Followed Peer",
                        )
                    ) edges++
                }
            }
        }

        log("  developer $normalised: +${bundle.repos.size} repos, +${bundle.starred.size} stars, " +
            "+${bundle.following.size} follows, +$edges edges")
        return edges
    }

    // ------------------------------------------------------------------
    /** Enrich a trending entry with REST metadata and link its author. */
    fun expandTrendingRepo(record: CrawlerRepo, depth: Int = 1): CrawlerRepo {
        val full = api.getRepo(record.owner, record.name)
        val repo = full ?: record
        val enriched = repo.copy(
            starsInPeriod = record.starsInPeriod,
            trendingRank = record.trendingRank,
            trendingPeriod = record.trendingPeriod,
            trendingLanguage = record.trendingLanguage,
        )
        store.upsertRepo(enriched, "trending")
        store.addSnapshot(
            slug = enriched.slug,
            stars = enriched.stars,
            forks = enriched.forks,
            openIssues = enriched.openIssues,
            starsInPeriod = enriched.starsInPeriod,
            period = enriched.trendingPeriod,
            trendingRank = enriched.trendingRank,
            trendingLanguage = enriched.trendingLanguage,
            source = "trending",
        )
        if (record.owner.isNotBlank()) {
            store.upsertDeveloper(CrawlerDeveloper(login = record.owner.lowercase()))
            store.addEdge(
                "developer", record.owner.lowercase(), "repo", enriched.slug,
                Relations.AUTHORED, RelevanceScorer.SAME_AUTHOR, "Other Work by Author",
            )
            if (depth > 0) expandDeveloper(record.owner.lowercase(), depth = depth)
        }
        return enriched
    }

    // ------------------------------------------------------------------
    /**
     * Derive repo->repo similarity edges from the local index.
     *
     * Purely offline - no API cost - and it is what gives the graph its
     * "related architecture" layer.
     */
    fun computeRelatedEdges(limit: Int = 200): Int {
        val rows = store.reposForLinking(300)
        var added = 0
        val prepared = rows.map { row ->
            Prepared(
                row = row,
                keywords = tokens(row.description) + nameTokens(row.name),
            )
        }
        for (i in prepared.indices) {
            val left = prepared[i]
            for (j in i + 1 until prepared.size) {
                val right = prepared[j]
                if (left.row.owner == right.row.owner) continue
                val sharedTopics = left.row.topics.intersect(right.row.topics)
                val sharedKeywords = left.keywords.intersect(right.keywords)
                if (sharedTopics.isEmpty() && sharedKeywords.size < 2) continue
                val sameLang = left.row.language.isNotBlank() && left.row.language == right.row.language
                if (sharedTopics.isEmpty() && !sameLang) continue

                val (score, reason) = relevanceScore(
                    "RELATED_ARCHITECTURE",
                    left.row.language,
                    right.row.language,
                    left.keywords,
                    right.row.description,
                    right.row.name,
                    right.row.stars,
                )
                val finalReason = if (sharedTopics.isNotEmpty()) {
                    "Shares topic " + sharedTopics.sorted().take(2).joinToString(", ")
                } else {
                    reason
                }
                if (store.addEdge(
                        "repo", left.row.slug, "repo", right.row.slug, Relations.RELATED,
                        score, finalReason,
                    )
                ) added++
                if (added >= limit) return added
            }
        }
        return added
    }

    private data class Prepared(val row: LinkableRepo, val keywords: Set<String>)
}
