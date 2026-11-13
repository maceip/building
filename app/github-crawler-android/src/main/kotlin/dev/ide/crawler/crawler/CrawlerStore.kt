package dev.ide.crawler.crawler

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log

/**
 * The phone-side index, history and graph store.
 *
 * The schema is deliberately identical to the macOS daemon's SQLite database
 * (tools/github-crawler/crawler/store.py) so the two indexes can be merged
 * later: same tables, same columns, same edge vocabulary.
 *
 * @see dev.ide.crawler.crawler.CrawlerStore for the schema notes.
 */
class CrawlerStore(context: Context) :
    SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION), CrawlerPersistence {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS repos (
                slug TEXT PRIMARY KEY, owner TEXT NOT NULL, name TEXT NOT NULL,
                description TEXT, stars INTEGER DEFAULT 0, forks INTEGER DEFAULT 0,
                watchers INTEGER DEFAULT 0, open_issues INTEGER DEFAULT 0, language TEXT,
                topics TEXT, homepage TEXT, html_url TEXT, default_branch TEXT, license TEXT,
                archived INTEGER DEFAULT 0, is_fork INTEGER DEFAULT 0, owner_type TEXT,
                size_kb INTEGER, created_at TEXT, pushed_at TEXT, updated_at TEXT,
                first_seen_at TEXT, last_seen_at TEXT, last_changed_at TEXT, source TEXT,
                metadata_json TEXT
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS repo_snapshots (
                id INTEGER PRIMARY KEY AUTOINCREMENT, slug TEXT NOT NULL, observed_at TEXT NOT NULL,
                stars INTEGER, forks INTEGER, open_issues INTEGER, stars_in_period INTEGER,
                period TEXT, trending_rank INTEGER, trending_language TEXT, source TEXT
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_snap_slug_time ON repo_snapshots(slug, observed_at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_snap_time ON repo_snapshots(observed_at)")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS developers (
                login TEXT PRIMARY KEY, name TEXT, bio TEXT, avatar_url TEXT, company TEXT,
                blog TEXT, followers INTEGER DEFAULT 0, following INTEGER DEFAULT 0,
                public_repos INTEGER DEFAULT 0, first_seen_at TEXT, last_seen_at TEXT,
                metadata_json TEXT
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS edges (
                id INTEGER PRIMARY KEY AUTOINCREMENT, src_type TEXT NOT NULL, src_id TEXT NOT NULL,
                dst_type TEXT NOT NULL, dst_id TEXT NOT NULL, relation TEXT NOT NULL,
                weight REAL DEFAULT 0, relevance INTEGER, reason TEXT, discovered_at TEXT,
                meta_json TEXT, UNIQUE (src_type, src_id, dst_type, dst_id, relation)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_edges_src ON edges(src_type, src_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_edges_dst ON edges(dst_type, dst_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_edges_relation ON edges(relation)")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS trending_runs (
                id INTEGER PRIMARY KEY AUTOINCREMENT, started_at TEXT, finished_at TEXT,
                language TEXT, period TEXT, repo_count INTEGER DEFAULT 0, status TEXT, detail TEXT
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS runs (
                id INTEGER PRIMARY KEY AUTOINCREMENT, started_at TEXT, finished_at TEXT,
                kind TEXT, status TEXT, api_calls INTEGER DEFAULT 0, web_calls INTEGER DEFAULT 0,
                repos_seen INTEGER DEFAULT 0, edges_added INTEGER DEFAULT 0, detail TEXT
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS api_calls (
                id INTEGER PRIMARY KEY AUTOINCREMENT, ts TEXT, kind TEXT, method TEXT, url TEXT,
                status INTEGER, resource TEXT, remaining INTEGER, limit_value INTEGER,
                cached INTEGER DEFAULT 0, duration_ms INTEGER, note TEXT
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_api_calls_ts ON api_calls(ts)")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS http_cache (
                url TEXT PRIMARY KEY, etag TEXT, body TEXT, fetched_at TEXT, status INTEGER
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // The index is a cache of public data; rebuilding it is cheaper and
        // safer than migrating it.
        listOf(
            "repo_snapshots", "edges", "developers", "repos",
            "trending_runs", "runs", "api_calls", "http_cache",
        ).forEach { db.execSQL("DROP TABLE IF EXISTS $it") }
        onCreate(db)
    }

    // ------------------------------------------------------------------
    // repos
    // ------------------------------------------------------------------
    /** Insert or update a repository. Returns true when the stars changed. */
    fun upsertRepo(repo: CrawlerRepo, source: String): Boolean {
        val db = writableDatabase
        val existing = db.rawQuery(
            "SELECT stars, first_seen_at FROM repos WHERE slug=?",
            arrayOf(repo.slug.lowercase()),
        ).use { if (it.moveToFirst()) it else null }
        val previousStars = existing?.let { it.getInt(0) }
        val firstSeen = existing?.getString(1) ?: nowIso()
        val changed = previousStars != null && repo.stars != null && previousStars != repo.stars

        val values = ContentValues().apply {
            put("slug", repo.slug.lowercase())
            put("owner", repo.owner)
            put("name", repo.name)
            put("description", repo.description)
            put("stars", repo.stars ?: 0)
            put("forks", repo.forks ?: 0)
            put("watchers", repo.watchers ?: 0)
            put("open_issues", repo.openIssues ?: 0)
            put("language", repo.language)
            put("topics", repo.topics.joinToString(","))
            put("homepage", repo.homepage)
            put("html_url", repo.htmlUrl)
            put("default_branch", repo.defaultBranch)
            put("license", repo.license)
            put("archived", if (repo.archived) 1 else 0)
            put("is_fork", if (repo.isFork) 1 else 0)
            put("owner_type", repo.ownerType)
            put("size_kb", repo.sizeKb)
            put("created_at", repo.createdAt)
            put("pushed_at", repo.pushedAt)
            put("updated_at", repo.updatedAt)
            put("first_seen_at", firstSeen)
            put("last_seen_at", nowIso())
            put("last_changed_at", if (changed) nowIso() else null)
            put("source", source)
        }
        db.insertWithOnConflict("repos", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        return changed
    }

    fun addSnapshot(
        slug: String,
        stars: Int? = null,
        forks: Int? = null,
        openIssues: Int? = null,
        starsInPeriod: Int? = null,
        period: String? = null,
        trendingRank: Int? = null,
        trendingLanguage: String? = null,
        source: String = "crawl",
    ) {
        val values = ContentValues().apply {
            put("slug", slug.lowercase())
            put("observed_at", nowIso())
            put("stars", stars)
            put("forks", forks)
            put("open_issues", openIssues)
            put("stars_in_period", starsInPeriod)
            put("period", period)
            put("trending_rank", trendingRank)
            put("trending_language", trendingLanguage)
            put("source", source)
        }
        writableDatabase.insert("repo_snapshots", null, values)
    }

    // ------------------------------------------------------------------
    // developers + edges
    // ------------------------------------------------------------------
    fun upsertDeveloper(dev: CrawlerDeveloper) {
        val db = writableDatabase
        val existing = db.rawQuery(
            "SELECT first_seen_at FROM developers WHERE login=?",
            arrayOf(dev.login.lowercase()),
        ).use { if (it.moveToFirst()) it.getString(0) else null }
        val values = ContentValues().apply {
            put("login", dev.login.lowercase())
            put("name", dev.name)
            put("bio", dev.bio)
            put("avatar_url", dev.avatarUrl)
            put("company", dev.company)
            put("blog", dev.blog)
            put("followers", dev.followers ?: 0)
            put("following", dev.following ?: 0)
            put("public_repos", dev.publicRepos ?: 0)
            put("first_seen_at", existing ?: nowIso())
            put("last_seen_at", nowIso())
        }
        db.insertWithOnConflict("developers", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** Insert an edge if it is new. Returns true when newly created. */
    fun addEdge(
        srcType: String,
        srcId: String,
        dstType: String,
        dstId: String,
        relation: String,
        relevance: Int? = null,
        reason: String? = null,
    ): Boolean {
        val values = ContentValues().apply {
            put("src_type", srcType)
            put("src_id", srcId.lowercase())
            put("dst_type", dstType)
            put("dst_id", dstId.lowercase())
            put("relation", relation)
            put("weight", 0.0)
            put("relevance", relevance)
            put("reason", reason)
            put("discovered_at", nowIso())
        }
        val rowId = writableDatabase.insertWithOnConflict(
            "edges", null, values, SQLiteDatabase.CONFLICT_IGNORE,
        )
        return rowId != -1L
    }

    /**
     * Developers we know about but have never traversed.
     *
     * A developer counts as expanded once it has at least one AUTHORED or
     * STARRED edge - the signal that its GraphQL bundle was pulled.
     */
    fun unexpandedDevelopers(limit: Int): List<String> {
        val sql = """
            SELECT d.login FROM developers d
            LEFT JOIN (
                SELECT src_id, COUNT(*) AS n FROM edges
                WHERE src_type='developer' AND relation IN ('AUTHORED','STARRED')
                GROUP BY src_id
            ) e ON e.src_id = d.login
            LEFT JOIN (
                SELECT src_id, COUNT(*) AS n FROM edges
                WHERE src_type='developer' AND relation='EXPANSION_ATTEMPTED'
                GROUP BY src_id
            ) a ON a.src_id = d.login
            WHERE COALESCE(e.n,0)=0 AND COALESCE(a.n,0)=0
            ORDER BY d.last_seen_at ASC, d.login ASC
            LIMIT ?
        """.trimIndent()
        return readableDatabase.rawQuery(sql, arrayOf(limit.toString())).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
    }

    fun markDeveloperExpanded(login: String) {
        addEdge("developer", login, "developer", login, "EXPANSION_ATTEMPTED", 0, "Traversal attempted")
    }

    // ------------------------------------------------------------------
    // accounting
    // ------------------------------------------------------------------
    fun startRun(kind: String): Long {
        val values = ContentValues().apply {
            put("started_at", nowIso())
            put("kind", kind)
            put("status", "running")
        }
        return writableDatabase.insert("runs", null, values)
    }

    fun finishRun(runId: Long, status: String, apiCalls: Int, webCalls: Int, reposSeen: Int, edgesAdded: Int, detail: String) {
        val values = ContentValues().apply {
            put("finished_at", nowIso())
            put("status", status)
            put("api_calls", apiCalls)
            put("web_calls", webCalls)
            put("repos_seen", reposSeen)
            put("edges_added", edgesAdded)
            put("detail", detail)
        }
        writableDatabase.update("runs", values, "id=?", arrayOf(runId.toString()))
    }

    fun recordTrendingRun(language: String, period: String, repoCount: Int, status: String, detail: String?) {
        val values = ContentValues().apply {
            put("started_at", nowIso())
            put("finished_at", nowIso())
            put("language", language)
            put("period", period)
            put("repo_count", repoCount)
            put("status", status)
            put("detail", detail)
        }
        writableDatabase.insert("trending_runs", null, values)
    }

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
        val values = ContentValues().apply {
            put("ts", nowIso())
            put("kind", kind)
            put("method", method)
            put("url", url)
            put("status", status)
            put("resource", resource)
            put("remaining", remaining)
            put("limit_value", limit)
            put("cached", if (cached) 1 else 0)
            put("duration_ms", durationMs.toInt())
            put("note", note)
        }
        try {
            writableDatabase.insert("api_calls", null, values)
        } catch (e: Exception) {
            Log.w(TAG, "api call audit failed", e)
        }
    }

    fun count(table: String): Int {
        return readableDatabase.rawQuery("SELECT COUNT(*) FROM $table", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    fun apiCallCount(sinceMinutes: Int): Int {
        val cutoff = isoSecondsAgo(sinceMinutes)
        return readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM api_calls WHERE ts >= ?", arrayOf(cutoff),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }
    }

    fun graphStats(): CrawlerStats {
        return CrawlerStats(
            repos = count("repos"),
            developers = count("developers"),
            edges = count("edges"),
            snapshots = count("repo_snapshots"),
            apiCallsLastHour = apiCallCount(60),
        )
    }

    /** Repositories considered for offline similarity edges. */
    fun reposForLinking(limit: Int): List<LinkableRepo> {
        return readableDatabase.rawQuery(
            "SELECT slug, owner, name, description, language, topics, stars FROM repos " +
                "WHERE archived=0 AND is_fork=0 ORDER BY stars DESC LIMIT ?",
            arrayOf(limit.toString()),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        LinkableRepo(
                            slug = cursor.getString(0),
                            owner = cursor.getString(1),
                            name = cursor.getString(2),
                            description = cursor.getString(3) ?: "",
                            language = cursor.getString(4) ?: "",
                            topics = cursor.getString(5).orEmpty().split(",").filter { it.isNotBlank() }.toSet(),
                            stars = cursor.getInt(6),
                        )
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // http cache (ETag)
    // ------------------------------------------------------------------
    override fun cacheGet(url: String): Pair<String?, String?> {
        return readableDatabase.rawQuery(
            "SELECT etag, body FROM http_cache WHERE url=?", arrayOf(url),
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) to cursor.getString(1) else null to null
        }
    }

    override fun cachePut(url: String, etag: String?, body: String?, status: Int) {
        val values = ContentValues().apply {
            put("url", url)
            put("etag", etag)
            put("body", body)
            put("fetched_at", nowIso())
            put("status", status)
        }
        writableDatabase.insertWithOnConflict("http_cache", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    companion object {
        private const val TAG = "CrawlerStore"
        const val DB_NAME = "crawler-index.db"
        const val DB_VERSION = 1

        fun nowIso(): String {
            val format = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
            format.timeZone = java.util.TimeZone.getTimeZone("UTC")
            return format.format(java.util.Date())
        }

        fun isoSecondsAgo(minutes: Int): String {
            val format = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
            format.timeZone = java.util.TimeZone.getTimeZone("UTC")
            return format.format(java.util.Date(System.currentTimeMillis() - minutes * 60_000L))
        }
    }
}

/** The canonical repository record shared by the crawler's components. */
data class CrawlerRepo(
    val slug: String,
    val owner: String,
    val name: String,
    val description: String? = null,
    val stars: Int? = null,
    val forks: Int? = null,
    val watchers: Int? = null,
    val openIssues: Int? = null,
    val language: String? = null,
    val topics: List<String> = emptyList(),
    val homepage: String? = null,
    val htmlUrl: String? = null,
    val defaultBranch: String? = null,
    val license: String? = null,
    val archived: Boolean = false,
    val isFork: Boolean = false,
    val ownerType: String? = null,
    val sizeKb: Int? = null,
    val createdAt: String? = null,
    val pushedAt: String? = null,
    val updatedAt: String? = null,
    val starsInPeriod: Int? = null,
    val trendingRank: Int? = null,
    val trendingPeriod: String? = null,
    val trendingLanguage: String? = null,
)

/** The canonical developer record. */
data class CrawlerDeveloper(
    val login: String,
    val name: String? = null,
    val bio: String? = null,
    val avatarUrl: String? = null,
    val company: String? = null,
    val blog: String? = null,
    val followers: Int? = null,
    val following: Int? = null,
    val publicRepos: Int? = null,
)

/** A developer plus their repositories, starred items and following list. */
data class DeveloperBundle(
    val developer: CrawlerDeveloper,
    val repos: List<CrawlerRepo>,
    val starred: List<CrawlerRepo>,
    val following: List<String>,
)

/** A repository as read back out of the index for similarity linking. */
data class LinkableRepo(
    val slug: String,
    val owner: String,
    val name: String,
    val description: String,
    val language: String,
    val topics: Set<String>,
    val stars: Int,
)

/** A snapshot of how big the index is. */
data class CrawlerStats(
    val repos: Int,
    val developers: Int,
    val edges: Int,
    val snapshots: Int,
    val apiCallsLastHour: Int,
)
