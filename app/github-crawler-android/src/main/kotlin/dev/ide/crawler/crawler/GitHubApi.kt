package dev.ide.crawler.crawler

import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * The GitHub HTTP client used by the phone-side crawler.
 *
 * The same rules as the macOS daemon:
 *
 *  * **Conditional requests.** Every GET carries the stored `ETag`; a `304`
 *    costs no rate-limit quota, so re-checking a known repository is free.
 *  * **Budget before spend.** The [CrawlerRateLimiter] decides whether the
 *    next call is allowed; this class only performs it and reports what
 *    GitHub said back.
 *  * **GraphQL batching.** A developer's repositories + starred repositories
 *    + following list come from ONE GraphQL request instead of three REST
 *    requests, which is what makes a 2-hop traversal affordable.
 */
class GitHubApi(
    private var token: String?,
    private val limiter: CrawlerRateLimiter,
    private val store: CrawlerPersistence,
    private val log: (String) -> Unit = { Log.d("GitHubApi", it) },
) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    @Volatile
    var cachedHits = 0
        private set

    // ------------------------------------------------------------------
    // low level
    // ------------------------------------------------------------------
    private data class Response(
        val code: Int,
        val body: String?,
        val remaining: Int?,
        val limit: Int?,
        val resetEpochSeconds: Long?,
        val resource: String?,
        val etag: String?,
        val retryAfterSeconds: Long?,
    )

    private fun request(
        url: String,
        method: String = "GET",
        body: String? = null,
        api: Boolean = true,
        resource: String = "core",
        useCache: Boolean = true,
        maxAttempts: Int = 3,
    ): Response {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", CrawlerConfig.USER_AGENT)
            // NOTE: deliberately no manual Accept-Encoding. OkHttp only
            // decompresses transparently when it adds the header itself; a
            // hand-set one makes the response body raw gzip bytes.

        if (api) {
            builder.header("Accept", "application/vnd.github+json")
            token?.takeIf { it.isNotBlank() }?.let { builder.header("Authorization", "Bearer $it") }
        } else {
            builder.header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        }
        body?.let { builder.header("Content-Type", "application/json") }

        var etag: String? = null
        if (useCache && method == "GET") {
            val (cachedEtag, _) = store.cacheGet(url)
            if (!cachedEtag.isNullOrBlank()) {
                etag = cachedEtag
                builder.header("If-None-Match", cachedEtag)
            }
        }
        val requestBody = body?.toRequestBody("application/json; charset=utf-8".toMediaType())

        var lastError: Exception? = null
        for (attempt in 1..maxAttempts) {
            limiter.waitForSlot(resource = resource, web = !api)
            val started = System.currentTimeMillis()
            val call = builder.build().let { request ->
                if (requestBody != null) client.newCall(request.newBuilder().method(method, requestBody).build())
                else client.newCall(request)
            }
            val response = try {
                call.execute()
            } catch (e: Exception) {
                lastError = e
                val wait = limiter.noteTransientFailure(attempt)
                log("network error on $url: ${e.message} (retry in ${wait / 1000}s)")
                store.recordApiCall(
                    if (api) "api" else "web", method, url, 0, resource, null, null, false,
                    System.currentTimeMillis() - started, "error:${e.message}",
                )
                if (attempt == maxAttempts) throw GitHubApiException("network error: ${e.message}", retryable = true)
                Thread.sleep(wait)
                continue
            }

            val code = response.code
            val remaining = response.header("x-ratelimit-remaining")?.toIntOrNull()
            val limit = response.header("x-ratelimit-limit")?.toIntOrNull()
            val reset = response.header("x-ratelimit-reset")?.toLongOrNull()
            val res = response.header("x-ratelimit-resource")
            val retryAfter = response.header("retry-after")?.toLongOrNull()
            val newEtag = response.header("ETag")

            limiter.observeHeaders(limit, remaining, reset, res)
            if (api) limiter.recordApiCall(resource) else limiter.recordWebCall()

            response.use {
                if (code == 403 || code == 429) {
                    val wait = limiter.noteRateLimited(retryAfter)
                    store.recordApiCall(
                        if (api) "api" else "web", method, url, code, resource, remaining, limit,
                        false, System.currentTimeMillis() - started, "rate_limited backoff=${wait / 1000}s",
                    )
                    if (code == 429 && attempt < maxAttempts) {
                        Thread.sleep(minOf(wait, 60_000L))
                        continue
                    }
                    throw GitHubApiException("rate limited ($code)", code)
                }
                if (code == 304) {
                    cachedHits++
                    store.recordApiCall(
                        if (api) "api" else "web", method, url, code, resource, remaining, limit,
                        true, System.currentTimeMillis() - started, "not-modified",
                    )
                    return Response(code, null, remaining, limit, reset, res, newEtag, retryAfter)
                }
                if (code == 404) {
                    store.recordApiCall(
                        if (api) "api" else "web", method, url, code, resource, remaining, limit,
                        false, System.currentTimeMillis() - started, "not-found",
                    )
                    return Response(code, null, remaining, limit, reset, res, newEtag, retryAfter)
                }
                if (code >= 500) {
                    val wait = limiter.noteTransientFailure(attempt)
                    store.recordApiCall(
                        if (api) "api" else "web", method, url, code, resource, remaining, limit,
                        false, System.currentTimeMillis() - started, "server-error retry in ${wait / 1000}s",
                    )
                    if (attempt < maxAttempts) {
                        Thread.sleep(wait)
                        continue
                    }
                    throw GitHubApiException("server error $code", code, retryable = true)
                }

                val text = response.body?.string()
                store.recordApiCall(
                    if (api) "api" else "web", method, url, code, resource, remaining, limit,
                    false, System.currentTimeMillis() - started, null,
                )
                if (useCache && method == "GET" && code == 200) {
                    store.cachePut(url, newEtag, text, code)
                }
                return Response(code, text, remaining, limit, reset, res, newEtag, retryAfter)
            }
        }
        throw GitHubApiException("request failed: ${lastError?.message}")
    }

    // ------------------------------------------------------------------
    // REST
    // ------------------------------------------------------------------
    private fun apiGetJson(path: String, resource: String = "core"): JsonObject? {
        val url = if (path.startsWith("http")) path else "${CrawlerConfig.API_BASE}/${path.trimStart('/')}"
        val response = request(url, resource = resource)
        if (response.code == 304) {
            val (_, body) = store.cacheGet(url)
            return body?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }
        }
        if (response.code != 200 || response.body.isNullOrBlank()) return null
        return runCatching { json.parseToJsonElement(response.body).jsonObject }.getOrNull()
    }

    fun getRepo(owner: String, name: String): CrawlerRepo? {
        val obj = apiGetJson("repos/$owner/$name") ?: return null
        return obj.toCrawlerRepo()
    }

    // ------------------------------------------------------------------
    // GraphQL
    // ------------------------------------------------------------------
    private fun graphql(query: String, variables: Map<String, Any?>): JsonObject? {
        val variablesJson = buildJsonObject {
            variables.forEach { (key, value) -> put(key, jsonPrimitiveOf(value)) }
        }
        val payload = buildJsonObject {
            put("query", JsonPrimitive(query))
            put("variables", variablesJson)
        }.toString()
        val response = request(
            CrawlerConfig.GRAPHQL_URL, method = "POST", body = payload,
            api = true, resource = "graphql", useCache = false,
        )
        if (response.code != 200 || response.body.isNullOrBlank()) return null
        val root = runCatching { json.parseToJsonElement(response.body).jsonObject }.getOrNull() ?: return null
        (root["errors"] as? JsonArray)?.firstOrNull()?.let {
            log("graphql error: ${it.jsonObject["message"]}")
            return null
        }
        return root["data"]?.jsonObject
    }

    /**
     * One GraphQL request for a developer's repos, stars and following.
     *
     * Uses `repositoryOwner` with an inline fragment for `User` so the same
     * query resolves both personal accounts (which have starred repositories
     * and a follow graph) and organizations (which do not).
     */
    fun developerBundle(
        login: String,
        repoCount: Int = CrawlerConfig.PEER_FETCH_COUNT,
        starCount: Int = CrawlerConfig.PEER_FETCH_COUNT,
        followCount: Int = CrawlerConfig.PEER_FETCH_COUNT,
    ): DeveloperBundle? {
        val query = """
            query DeveloperBundle(${'$'}login: String!, ${'$'}repoCount: Int!, ${'$'}starCount: Int!, ${'$'}followCount: Int!) {
              repositoryOwner(login: ${'$'}login) {
                login
                avatarUrl
                url
                repositories(first: ${'$'}repoCount, orderBy: {field: PUSHED_AT, direction: DESC}) {
                  nodes {
                    nameWithOwner owner { login } description stargazerCount forkCount
                    primaryLanguage { name } url homepageUrl isArchived isFork
                    diskUsage createdAt pushedAt updatedAt
                    licenseInfo { spdxId }
                    defaultBranchRef { name }
                    repositoryTopics(first: 10) { nodes { topic { name } } }
                  }
                }
                ... on User {
                  name bio company websiteUrl
                  followers { totalCount }
                  following(first: ${'$'}followCount) { totalCount nodes { login } }
                  starredRepositories(first: ${'$'}starCount, orderBy: {field: STARRED_AT, direction: DESC}) {
                    nodes {
                      nameWithOwner owner { login } description stargazerCount forkCount
                      primaryLanguage { name } url isArchived isFork
                    }
                  }
                }
                ... on Organization { name description websiteUrl }
              }
            }
        """.trimIndent()

        val data = graphql(
            query,
            mapOf(
                "login" to login,
                "repoCount" to repoCount,
                "starCount" to starCount,
                "followCount" to followCount,
            ),
        ) ?: return null
        val owner = data["repositoryOwner"]?.jsonObject ?: return null
        return owner.toDeveloperBundle()
    }

    // ------------------------------------------------------------------
    // web (trending)
    // ------------------------------------------------------------------
    fun fetchTrendingHtml(language: String, period: String): String? {
        val response = request(
            TrendingParser.trendingUrl(language, period),
            api = false, useCache = false,
        )
        return if (response.code == 200) response.body else null
    }
}

/** A GitHub request failed. */
class GitHubApiException(message: String, val code: Int? = null, val retryable: Boolean = false) : Exception(message)

// ----------------------------------------------------------------------
// JSON -> model helpers
// ----------------------------------------------------------------------
private fun jsonPrimitiveOf(value: Any?): kotlinx.serialization.json.JsonPrimitive =
    when (value) {
        is Int -> kotlinx.serialization.json.JsonPrimitive(value)
        is Long -> kotlinx.serialization.json.JsonPrimitive(value)
        is Boolean -> kotlinx.serialization.json.JsonPrimitive(value)
        null -> kotlinx.serialization.json.JsonPrimitive(null as String?)
        else -> kotlinx.serialization.json.JsonPrimitive(value.toString())
    }

private fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString || it.content != "null" }?.content

private fun JsonObject.intOrNull(key: String): Int? =
    (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull()

private fun JsonObject.boolOrNull(key: String): Boolean? =
    (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toBooleanStrictOrNull()

private fun JsonObject.jsonArrayOrEmpty(key: String): JsonArray =
    this[key] as? JsonArray ?: JsonArray(emptyList())

private fun JsonObject.toCrawlerRepo(): CrawlerRepo {
    val ownerBlock = this["owner"]?.jsonObject
    val fullName = stringOrNull("full_name") ?: stringOrNull("nameWithOwner")
    var owner = ownerBlock?.stringOrNull("login") ?: ""
    var name = stringOrNull("name") ?: ""
    if (!fullName.isNullOrBlank() && fullName.contains("/")) {
        owner = fullName.substringBefore("/")
        name = fullName.substringAfter("/")
    }
    val ownerType = ownerBlock?.stringOrNull("type")

    val language = stringOrNull("language")
        ?: (this["primaryLanguage"] as? JsonObject)?.stringOrNull("name")

    val topics = jsonArrayOrEmpty("topics").mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
        .ifEmpty {
            ((this["repositoryTopics"] as? JsonObject)?.jsonArrayOrEmpty("nodes") ?: JsonArray(emptyList()))
                .mapNotNull { node ->
                    ((node as? JsonObject)?.get("topic") as? JsonObject)?.stringOrNull("name")
                }
        }

    val license = (this["license"] as? JsonObject)?.stringOrNull("spdx_id")
        ?: (this["licenseInfo"] as? JsonObject)?.stringOrNull("spdxId")

    val defaultBranch = stringOrNull("default_branch")
        ?: (this["defaultBranchRef"] as? JsonObject)?.stringOrNull("name")

    return CrawlerRepo(
        slug = "$owner/$name".lowercase(),
        owner = owner,
        name = name,
        description = stringOrNull("description"),
        stars = intOrNull("stargazers_count") ?: intOrNull("stargazerCount"),
        forks = intOrNull("forks_count") ?: intOrNull("forkCount"),
        watchers = intOrNull("watchers_count"),
        openIssues = intOrNull("open_issues_count"),
        language = language,
        topics = topics,
        homepage = stringOrNull("homepage"),
        htmlUrl = stringOrNull("html_url") ?: stringOrNull("url"),
        defaultBranch = defaultBranch,
        license = license,
        archived = boolOrNull("archived") ?: false,
        isFork = boolOrNull("fork") ?: false,
        ownerType = ownerType,
        sizeKb = intOrNull("diskUsage") ?: intOrNull("size"),
        createdAt = stringOrNull("created_at") ?: stringOrNull("createdAt"),
        pushedAt = stringOrNull("pushed_at") ?: stringOrNull("pushedAt"),
        updatedAt = stringOrNull("updated_at") ?: stringOrNull("updatedAt"),
    )
}

private fun JsonObject.toDeveloperBundle(): DeveloperBundle {
    val repos = (this["repositories"] as? JsonObject)?.jsonArrayOrEmpty("nodes")
        ?.mapNotNull { (it as? JsonObject)?.toCrawlerRepo() } ?: emptyList()
    val starred = (this["starredRepositories"] as? JsonObject)?.jsonArrayOrEmpty("nodes")
        ?.mapNotNull { (it as? JsonObject)?.toCrawlerRepo() } ?: emptyList()
    val following = (this["following"] as? JsonObject)?.jsonArrayOrEmpty("nodes")
        ?.mapNotNull { (it as? JsonObject)?.stringOrNull("login")?.lowercase() } ?: emptyList()

    val followersBlock = this["followers"] as? JsonObject
    val followingBlock = this["following"] as? JsonObject

    val developer = CrawlerDeveloper(
        login = stringOrNull("login")?.lowercase() ?: "",
        name = stringOrNull("name"),
        bio = stringOrNull("bio"),
        avatarUrl = stringOrNull("avatarUrl"),
        company = stringOrNull("company"),
        blog = stringOrNull("websiteUrl"),
        followers = followersBlock?.intOrNull("totalCount"),
        following = followingBlock?.intOrNull("totalCount"),
    )
    return DeveloperBundle(developer, repos, starred, following)
}
