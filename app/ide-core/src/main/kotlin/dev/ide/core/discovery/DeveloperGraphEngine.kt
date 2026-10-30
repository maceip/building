package dev.ide.core.discovery

/**
 * Developer Social Graph & Affinity Engine.
 *
 * Traverses the open-source human network beyond the repository itself:
 * - Who authored this trending project?
 * - Who do they follow on GitHub (their peer network of trusted engineers)?
 * - What projects are those peers building that haven't hit mainstream trending yet?
 * - What repositories has the author personally starred?
 *
 * Uncovers high-signal, hidden gems that algorithmic search engines miss.
 */
object DeveloperGraphEngine {

    data class DeveloperProfile(
        val login: String,
        val name: String?,
        val bio: String?,
        val avatarUrl: String,
        val company: String?,
        val blogUrl: String?,
        val followersCount: Int,
        val followingCount: Int,
        val publicReposCount: Int,
        val organizations: List<String> = emptyList()
    )

    data class PeerRepository(
        val slug: String,
        val name: String,
        val owner: String,
        val description: String,
        val stars: Int,
        val language: String,
        val connectionReason: ConnectionType,
        val connectedViaAuthor: String,
        val peerFollowerCount: Int,
        val socialChain: List<String> = emptyList(),
        val relevanceScore: Int = 75,
        val relevanceReason: String = "",
        val screenshotUrl: String? = null
    )

    enum class ConnectionType(val label: String) {
        /** Directly authored by the same creator. */
        SAME_AUTHOR("Other Work by Author"),

        /** Authored by an engineer the original creator follows (1st-hop). */
        FOLLOWED_PEER("Built by Author's Trusted Peer"),

        /** Explicitly starred by the original creator. */
        AUTHOR_STARRED("Starred by Author"),

        /** Starred by an engineer the original creator follows (1st-hop peer star). */
        PEER_STARRED("Starred by Followed Peer"),

        /** Authored by an engineer followed by someone the creator follows (2nd-hop). */
        SECOND_DEGREE_PEER("2nd-Hop Followed Developer"),

        /** Starred by a 2nd-hop developer in the author's network. */
        SECOND_DEGREE_STARRED("2nd-Hop Starred Gem"),

        /** Related to the selected app by architecture, language, or domain keywords. */
        RELATED_ARCHITECTURE("Related to Selected App"),

        /** Shared organization or core maintainer group. */
        SHARED_ORGANIZATION("Same Core Team / Org")
    }

    data class DeveloperEcosystem(
        val author: DeveloperProfile,
        val moreByAuthor: List<PeerRepository>,
        val peerRecommendations: List<PeerRepository>,
        val authorStarred: List<PeerRepository>
    )

    /**
     * Constructs GitHub API endpoints for traversing an author's peer network.
     */
    object ApiRoutes {
        fun userProfile(login: String) = "https://api.github.com/users/$login"
        fun userFollowing(login: String, page: Int = 1, perPage: Int = 30) =
            "https://api.github.com/users/$login/following?page=$page&per_page=$perPage"
        fun userStarred(login: String, page: Int = 1, perPage: Int = 30) =
            "https://api.github.com/users/$login/starred?page=$page&per_page=$perPage"
        fun userRepos(login: String, page: Int = 1, perPage: Int = 30) =
            "https://api.github.com/users/$login/repos?sort=updated&page=$page&per_page=$perPage"
        fun userOrgs(login: String) = "https://api.github.com/users/$login/orgs"
    }

    /**
     * Ranks and filters peer repositories discovered through an author's network.
     * Prioritizes high-signal projects over dead forks or trivial config repos.
     */
    fun filterAndRankPeerProjects(
        authorLogin: String,
        rawPeerProjects: List<PeerRepository>,
        minStars: Int = 10,
        excludeForks: Boolean = true
    ): List<PeerRepository> {
        return rawPeerProjects
            .filter { it.stars >= minStars }
            .filter { !it.owner.equals(authorLogin, ignoreCase = true) || it.connectionReason == ConnectionType.SAME_AUTHOR }
            .sortedWith(
                compareByDescending<PeerRepository> { it.connectionReason == ConnectionType.SAME_AUTHOR }
                    .thenByDescending { it.connectionReason == ConnectionType.AUTHOR_STARRED }
                    .thenByDescending { it.stars }
            )
    }

    /**
     * Generates human-readable context explaining why a peer project was recommended.
     */
    fun formatAttribution(peer: PeerRepository): String {
        val chainText = if (peer.socialChain.size >= 2) {
            peer.socialChain.joinToString(" → ") { if (it.startsWith("@")) it else "@$it" }
        } else {
            null
        }
        return when (peer.connectionReason) {
            ConnectionType.SAME_AUTHOR ->
                "Also created by @${peer.connectedViaAuthor}"
            ConnectionType.FOLLOWED_PEER ->
                "Created by @${peer.owner}, whom @${peer.connectedViaAuthor} follows"
            ConnectionType.AUTHOR_STARRED ->
                "Personally starred by @${peer.connectedViaAuthor}"
            ConnectionType.PEER_STARRED ->
                chainText?.let { "$it ★ ${peer.slug}" }
                    ?: "Starred by @${peer.connectedViaAuthor}'s followed peer"
            ConnectionType.SECOND_DEGREE_PEER ->
                chainText ?: "2nd-hop peer via @${peer.connectedViaAuthor}'s network (@${peer.owner})"
            ConnectionType.SECOND_DEGREE_STARRED ->
                chainText?.let { "$it ★ ${peer.slug}" }
                    ?: "Starred in @${peer.connectedViaAuthor}'s 2-hop developer circle"
            ConnectionType.RELATED_ARCHITECTURE ->
                peer.relevanceReason.ifBlank { "Related ${peer.language} project to @${peer.connectedViaAuthor}'s app" }
            ConnectionType.SHARED_ORGANIZATION ->
                "From the team behind @${peer.connectedViaAuthor}'s organization"
        }
    }

    /**
     * Scores, filters, and ranks candidate repositories discovered from the author's 2-hop
     * social network (author repos, author stars, 1st-hop following, 2nd-hop following, and their stars)
     * by how closely related they are to the currently selected app.
     */
    fun rankAndFilterRelatedToApp(
        targetOwner: String,
        targetName: String,
        targetLanguage: String,
        targetDescription: String,
        targetTopics: List<String> = emptyList(),
        candidates: List<PeerRepository>,
        limit: Int = 18
    ): List<PeerRepository> {
        val targetSlug = "$targetOwner/$targetName".lowercase()
        val stopWords = setOf(
            "the", "and", "for", "with", "that", "this", "from", "your", "into", "using",
            "app", "application", "project", "repo", "repository", "library", "tool", "open", "source"
        )
        val targetKeywords = (
            targetDescription.lowercase().split(Regex("[^a-z0-9+#-]+")) +
                targetName.lowercase().split(Regex("[-_.]")) +
                targetTopics.map { it.lowercase() }
            )
            .map { it.trim() }
            .filter { it.length >= 3 && it !in stopWords }
            .toSet()

        val scored = candidates
            .filter { it.slug.lowercase() != targetSlug }
            .distinctBy { it.slug.lowercase() }
            .map { peer ->
                val peerTokens = (
                    peer.description.lowercase().split(Regex("[^a-z0-9+#-]+")) +
                        peer.name.lowercase().split(Regex("[-_.]"))
                    )
                    .map { it.trim() }
                    .filter { it.length >= 3 && it !in stopWords }
                    .toSet()

                val sharedKeywords = targetKeywords.intersect(peerTokens)
                val sameLang = targetLanguage.isNotBlank() &&
                    peer.language.equals(targetLanguage, ignoreCase = true)

                val baseConnectionWeight = when (peer.connectionReason) {
                    ConnectionType.SAME_AUTHOR -> 36
                    ConnectionType.AUTHOR_STARRED -> 32
                    ConnectionType.FOLLOWED_PEER -> 28
                    ConnectionType.PEER_STARRED -> 25
                    ConnectionType.SECOND_DEGREE_PEER -> 22
                    ConnectionType.SECOND_DEGREE_STARRED -> 20
                    ConnectionType.RELATED_ARCHITECTURE -> 30
                    ConnectionType.SHARED_ORGANIZATION -> 28
                }

                val langBonus = if (sameLang) 24 else 0
                val keywordBonus = (sharedKeywords.size * 12).coerceAtMost(36)
                val starBonus = when {
                    peer.stars >= 10_000 -> 12
                    peer.stars >= 1_000 -> 9
                    peer.stars >= 100 -> 6
                    peer.stars >= 15 -> 3
                    else -> 0
                }

                val totalScore = (baseConnectionWeight + langBonus + keywordBonus + starBonus).coerceIn(10, 99)
                val reasonParts = mutableListOf<String>()
                if (sameLang) reasonParts.add("Shared ${peer.language} stack")
                if (sharedKeywords.isNotEmpty()) {
                    reasonParts.add("Matches ${sharedKeywords.take(3).joinToString(", ")}")
                }
                if (reasonParts.isEmpty()) {
                    reasonParts.add(peer.connectionReason.label)
                }

                peer.copy(
                    relevanceScore = totalScore,
                    relevanceReason = peer.relevanceReason.ifBlank { reasonParts.joinToString(" • ") },
                    screenshotUrl = peer.screenshotUrl ?: ScreenshotExtractor.openGraphCardUrl(peer.owner, peer.name)
                )
            }

        return scored
            .sortedWith(
                compareByDescending<PeerRepository> { it.relevanceScore }
                    .thenByDescending { it.stars }
            )
            .take(limit)
    }
}
