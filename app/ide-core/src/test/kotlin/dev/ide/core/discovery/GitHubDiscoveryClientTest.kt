package dev.ide.core.discovery

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GitHubDiscoveryClientTest {

    @Test
    fun testDiscoveryFeedGenerationWithFallbacks(@TempDir tempDir: Path) {
        val client = GitHubDiscoveryClient(
            token = null,
            apiBase = "http://127.0.0.1:9999", // Intentional offline endpoint to test graceful fallback
            cacheDir = tempDir
        )

        // When offline or without API token, getDiscoveryFeed must not fabricate fake trending apps
        val feed = client.getDiscoveryFeed(limit = 3)

        assertNotNull(feed)
        assertTrue(feed.trendingHero.isEmpty(), "Trending hero list must not contain fabricated apps when offline")
    }

    @Test
    fun testAuthorPeerCircleAttribution() {
        val peer = DeveloperGraphEngine.PeerRepository(
            slug = "torvalds/subsurface",
            name = "subsurface",
            owner = "torvalds",
            description = "Divelog program",
            stars = 2400,
            language = "C",
            connectionReason = DeveloperGraphEngine.ConnectionType.FOLLOWED_PEER,
            connectedViaAuthor = "antirez",
            peerFollowerCount = 190000
        )

        val attr = DeveloperGraphEngine.formatAttribution(peer)
        assertEquals("Created by @torvalds, whom @antirez follows", attr)
    }
}
