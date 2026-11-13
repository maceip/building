package dev.ide.crawler.crawler

/**
 * GitHub Trending board parser.
 *
 * Ported from the in-app crawler
 * (`app/ide-core/src/main/kotlin/dev/ide/core/discovery/GitHubDiscoveryClient.kt`,
 * `parseTrendingHtml` / `fetchLiveTrendingRepositories`) and kept equivalent
 * to the macOS daemon's parser (tools/github-crawler/crawler/trending.py), so
 * all three implementations index the same data the same way.
 *
 * GitHub's trending pages are plain HTML and cost no API quota.
 */
object TrendingParser {

    private val ARTICLE_RE = Regex(
        """<article\s+class=["'][^"']*Box-row[^"']*["'][^>]*>([\s\S]*?)</article>""",
        RegexOption.IGNORE_CASE,
    )
    private val H2_HREF_RE = Regex(
        """<h2[^>]*>[\s\S]*?<a\s+[^>]*?href=["']/([^/"'\s]+)/([^/"'\s]+)["']""",
        RegexOption.IGNORE_CASE,
    )
    private val DESC_RE = Regex(
        """<p\s+class=["'][^"']*col-9[^"']*["'][^>]*>([\s\S]*?)</p>""",
        RegexOption.IGNORE_CASE,
    )
    private val LANG_RE = Regex(
        """itemprop=["']programmingLanguage["'][^>]*>([^<]+)</span>""",
        RegexOption.IGNORE_CASE,
    )
    private val STARGAZERS_RE = Regex(
        """href=["']/[^"']+/stargazers["'][^>]*>([\s\S]*?)</a>""",
        RegexOption.IGNORE_CASE,
    )
    private val FORKS_RE = Regex(
        """href=["']/[^"']+/forks["'][^>]*>([\s\S]*?)</a>""",
        RegexOption.IGNORE_CASE,
    )
    private val PERIOD_STARS_RE = Regex(
        """([\d,]+)\s+stars?\s+(?:today|this\s+week|this\s+month)""",
        RegexOption.IGNORE_CASE,
    )
    private val AVATAR_RE = Regex(
        """<img\s+[^>]*?class=["'][^"']*avatar-user[^"']*["'][^>]*?src=["']([^"']+)["']""",
        RegexOption.IGNORE_CASE,
    )

    private val TAG_RE = Regex("<[^>]+>")
    private val WS_RE = Regex("\\s+")
    private val COUNT_RE = Regex("""(\d+(?:\.\d+)?)\s*([km])?""")

    val SUPPORTED_PERIODS = listOf("daily", "weekly", "monthly")

    fun normaliseLanguageKey(language: String): String {
        val key = language.trim().lowercase()
        return when (key) {
            "", "all" -> "all"
            "cpp", "c++" -> "c++"
            "c#", "c-sharp" -> "csharp"
            "ts", "typescript" -> "typescript"
            "js", "javascript" -> "javascript"
            "py", "python" -> "python"
            "golang" -> "go"
            else -> key
        }
    }

    fun trendingUrl(language: String, period: String): String {
        val key = normaliseLanguageKey(language)
        val segment = if (key == "all") "" else "/$key"
        val since = period.trim().lowercase().takeIf { it in SUPPORTED_PERIODS } ?: "daily"
        return "${CrawlerConfig.WEB_BASE}/trending$segment?since=$since"
    }

    fun stripHtmlTags(raw: String): String =
        decodeHtmlEntities(WS_RE.replace(TAG_RE.replace(raw, " "), " "))

    fun decodeHtmlEntities(raw: String): String = raw
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&nbsp;", " ")

    /** Parses `1,234` / `12.3k` / `4.5m` into an integer. */
    fun parseCompactCount(raw: String): Int {
        val clean = raw.lowercase().replace(",", "").trim()
        if (clean.isEmpty()) return 0
        val match = COUNT_RE.find(clean) ?: return 0
        val base = match.groupValues[1].toDoubleOrNull() ?: return 0
        return when (match.groupValues[2]) {
            "k" -> (base * 1_000).toInt()
            "m" -> (base * 1_000_000).toInt()
            else -> base.toInt()
        }
    }

    /**
     * Parse GitHub's trending board into structured repository records.
     */
    fun parseTrendingHtml(
        html: String,
        period: String = "daily",
        defaultLanguage: String = "",
    ): List<CrawlerRepo> {
        if (html.isBlank()) return emptyList()
        val results = mutableListOf<CrawlerRepo>()

        for (article in ARTICLE_RE.findAll(html)) {
            val block = article.groupValues[1]
            val slugMatch = H2_HREF_RE.find(block) ?: continue
            val owner = slugMatch.groupValues[1].trim()
            val name = slugMatch.groupValues[2].trim()
            if (owner.isBlank() || name.isBlank() || owner == "sponsors" || owner == "login") continue

            val description = DESC_RE.find(block)?.groupValues?.getOrNull(1)?.let { stripHtmlTags(it).trim() } ?: ""
            val language = LANG_RE.find(block)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
                ?: defaultLanguage.ifBlank { "Kotlin" }
            val stars = STARGAZERS_RE.find(block)?.groupValues?.getOrNull(1)?.let { parseCompactCount(stripHtmlTags(it)) } ?: 0
            val forks = FORKS_RE.find(block)?.groupValues?.getOrNull(1)?.let { parseCompactCount(stripHtmlTags(it)) } ?: 0
            val starsInPeriod = PERIOD_STARS_RE.find(block)?.groupValues?.getOrNull(1)?.let { parseCompactCount(it) } ?: 0

            val avatars = AVATAR_RE.findAll(block)
                .map { it.groupValues[1].trim() }
                .filter { it.startsWith("http") }
                .distinct()
                .take(5)
                .toList()

            results.add(
                CrawlerRepo(
                    slug = "$owner/$name".lowercase(),
                    owner = owner,
                    name = name,
                    description = description,
                    stars = stars,
                    forks = forks,
                    language = decodeHtmlEntities(language),
                    htmlUrl = "https://github.com/$owner/$name",
                    defaultBranch = "main",
                    starsInPeriod = starsInPeriod,
                    trendingRank = results.size + 1,
                    trendingPeriod = period,
                )
            )
        }
        return results
    }
}
