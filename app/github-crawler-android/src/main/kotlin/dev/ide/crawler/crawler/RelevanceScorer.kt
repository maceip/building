package dev.ide.crawler.crawler

import kotlin.math.max
import kotlin.math.min

/**
 * Relevance scoring for graph edges.
 *
 * Ported from `DeveloperGraphEngine.rankAndFilterRelatedToApp` in
 * `app/ide-core/src/main/kotlin/dev/ide/core/discovery/DeveloperGraphEngine.kt`:
 *
 *  * a base weight per connection type,
 *  * a language bonus when the peer shares the target's language,
 *  * a keyword bonus for overlapping description/name tokens,
 *  * a star bonus scaled to the peer's popularity,
 *
 * clamped to the same 10..99 range.
 *
 * It lives in its own object (rather than on [GraphCrawler]) so it can be
 * unit tested on the JVM without an Android context.
 */
object RelevanceScorer {

    /** Base connection weights, identical to DeveloperGraphEngine. */
    const val SAME_AUTHOR = 36
    const val AUTHOR_STARRED = 32
    const val FOLLOWED_PEER = 28
    const val PEER_STARRED = 25
    const val SECOND_DEGREE_PEER = 22
    const val SECOND_DEGREE_STARRED = 20
    const val RELATED_ARCHITECTURE = 30
    const val SHARED_ORGANIZATION = 28

    private val stopWords = setOf(
        "the", "and", "for", "with", "that", "this", "from", "your", "into", "using",
        "app", "application", "project", "repo", "repository", "library", "tool", "open", "source",
    )
    private val tokenSplit = Regex("[^a-z0-9+#-]+")
    private val nameSplit = Regex("[-_.]")

    /** Description/name tokens that carry signal. */
    fun tokens(vararg values: String?): Set<String> {
        val out = mutableSetOf<String>()
        values.forEach { value ->
            if (value.isNullOrBlank()) return@forEach
            out += tokenSplit.split(value.lowercase()).filter { it.length >= 3 && it !in stopWords }
        }
        return out
    }

    /** Hyphen/underscore/dot separated name tokens. */
    fun nameTokens(name: String?): Set<String> {
        if (name.isNullOrBlank()) return emptySet()
        return nameSplit.split(name.lowercase()).filter { it.length >= 3 && it !in stopWords }.toSet()
    }

    /**
     * Score one candidate against a target.
     *
     * Returns the relevance score and the human-readable reason.
     */
    fun score(
        connection: String,
        targetLanguage: String,
        peerLanguage: String,
        targetKeywords: Set<String>,
        peerDescription: String,
        peerName: String,
        peerStars: Int,
    ): Pair<Int, String> {
        val peerKeywords = tokens(peerDescription) + nameTokens(peerName)
        val shared = targetKeywords.intersect(peerKeywords)
        val sameLang = targetLanguage.isNotBlank() && peerLanguage.equals(targetLanguage, ignoreCase = true)

        val base = when (connection) {
            "SAME_AUTHOR" -> SAME_AUTHOR
            "AUTHOR_STARRED" -> AUTHOR_STARRED
            "FOLLOWED_PEER" -> FOLLOWED_PEER
            "PEER_STARRED" -> PEER_STARRED
            "SECOND_DEGREE_PEER" -> SECOND_DEGREE_PEER
            "SECOND_DEGREE_STARRED" -> SECOND_DEGREE_STARRED
            "RELATED_ARCHITECTURE" -> RELATED_ARCHITECTURE
            "SHARED_ORGANIZATION" -> SHARED_ORGANIZATION
            else -> 20
        }
        val langBonus = if (sameLang) 24 else 0
        val keywordBonus = min(shared.size * 12, 36)
        val starBonus = when {
            peerStars >= 10_000 -> 12
            peerStars >= 1_000 -> 9
            peerStars >= 100 -> 6
            peerStars >= 15 -> 3
            else -> 0
        }
        val total = max(10, min(99, base + langBonus + keywordBonus + starBonus))

        val parts = mutableListOf<String>()
        if (sameLang) parts.add("Shared $peerLanguage stack")
        if (shared.isNotEmpty()) parts.add("Matches " + shared.sorted().take(3).joinToString(", "))
        if (parts.isEmpty()) {
            parts.add(connection.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() })
        }
        return total to parts.joinToString(" • ")
    }
}
