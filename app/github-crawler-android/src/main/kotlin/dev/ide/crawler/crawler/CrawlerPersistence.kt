package dev.ide.crawler.crawler

/**
 * The persistence the HTTP client needs.
 *
 * Keeping the client's dependency on this narrow interface (rather than on
 * [CrawlerStore]) means the networking layer can be exercised on the host
 * JVM with an in-memory implementation, which is what the live host test
 * does - the phone is not always connected to the build machine.
 */
interface CrawlerPersistence {

    /** The cached `ETag` and body for [url], or `null to null` when absent. */
    fun cacheGet(url: String): Pair<String?, String?>

    /** Store an `ETag` and body so the next request can be conditional. */
    fun cachePut(url: String, etag: String?, body: String?, status: Int)

    /** Record one outbound request in the audit trail. */
    fun recordApiCall(
        kind: String,
        method: String,
        url: String,
        status: Int,
        resource: String?,
        remaining: Int?,
        limit: Int?,
        cached: Boolean,
        durationMs: Long,
        note: String?,
    )
}

/**
 * An in-memory [CrawlerPersistence] for tests.
 *
 * It reproduces the one behaviour the HTTP client actually depends on: a
 * cached `ETag` turns the next request into a conditional one, and a `304`
 * costs no rate-limit quota.
 */
class InMemoryCrawlerPersistence : CrawlerPersistence {

    private val cache = mutableMapOf<String, Pair<String?, String?>>()
    val calls = mutableListOf<Map<String, Any?>>()

    @Synchronized
    override fun cacheGet(url: String): Pair<String?, String?> = cache[url] ?: (null to null)

    @Synchronized
    override fun cachePut(url: String, etag: String?, body: String?, status: Int) {
        cache[url] = etag to body
    }

    @Synchronized
    override fun recordApiCall(
        kind: String,
        method: String,
        url: String,
        status: Int,
        resource: String?,
        remaining: Int?,
        limit: Int?,
        cached: Boolean,
        durationMs: Long,
        note: String?,
    ) {
        calls += mapOf(
            "kind" to kind,
            "url" to url,
            "status" to status,
            "cached" to cached,
            "note" to note,
        )
    }
}
