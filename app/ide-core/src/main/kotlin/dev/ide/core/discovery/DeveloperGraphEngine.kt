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
        val peerFollowerCount: Int
    )

    enum class ConnectionType(val label: String) {
        /** Directly authored by the same creator. */
        SAME_AUTHOR("Other Work by Author"),

        /** Authored by an engineer the original creator follows. */
        FOLLOWED_PEER("Built by Author's Trusted Peer"),

        /** Explicitly starred by the original creator. */
        AUTHOR_STARRED("Starred by Author"),

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
        return when (peer.connectionReason) {
            ConnectionType.SAME_AUTHOR ->
                "Also created by @${peer.connectedViaAuthor}"
            ConnectionType.FOLLOWED_PEER ->
                "Created by @${peer.owner}, whom @${peer.connectedViaAuthor} follows"
            ConnectionType.AUTHOR_STARRED ->
                "Personally starred by @${peer.connectedViaAuthor}"
            ConnectionType.SHARED_ORGANIZATION ->
                "From the team behind @${peer.connectedViaAuthor}'s organization"
        }
    }
}
