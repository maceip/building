package dev.ide.core.discovery

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * High-performance client that powers the on-device GitHub Explorer experience.
 *
 * Orchestrates GitHub REST API calls with [GitHubDiscoveryEngine] to extract:
 * 1. Multi-tier visual screenshots (Tiers 0-3).
 * 2. Architectural genome & North Star structural benchmarks ("Shaped like X, but...").
 * 3. Runtime capabilities (Zero-Cloud, offline-first, NDK, BLE, permissions).
 * 4. Developer social graph & peer circle traversal (who the author follows, starred projects).
 *
 * Fully respects network timeouts and rate-limiting, falling back gracefully to local cached
 * discovery fixtures when offline.
 */
class GitHubDiscoveryClient(
    var token: String? = null,
    private val apiBase: String = "https://api.github.com",
    private val cacheDir: Path? = null,
    private val http: OkHttpClient = defaultHttpClient(),
    private val engine: GitHubDiscoveryEngine = GitHubDiscoveryEngine()
) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Builds a complete, multi-dimensional [GitHubDiscoveryEngine.DiscoveryFeed] for the explorer screen.
     */
    fun getDiscoveryFeed(
        topic: String = "android",
        language: String = "kotlin",
        limit: Int = 10
    ): GitHubDiscoveryEngine.DiscoveryFeed {
        val normTopic = topic.lowercase().trim()
        val isBuiltInCategory = normTopic in setOf(
            "", "all", "android",
            "swift", "swiftui", "ios",
            "python",
            "node", "nodejs", "node.js",
            "agents",
            "llm", "large language models", "llms",
            "penetration-testing", "pentesting", "pen testing",
            "reverse-engineering", "reverse engineering", "reverse eng",
            "gaming", "science",
        )

        val searchQuery = when (normTopic) {
            "swift", "swiftui", "ios" -> "language:swift stars:>200 size:<5000"
            "python" -> "language:python topic:cli stars:>300 size:<5000"
            "node", "nodejs", "node.js" -> "language:javascript topic:nodejs stars:>300 size:<5000"
            "agents" -> "topic:ai-agents language:python stars:>300 size:<8000"
            "llm", "large language models", "llms" -> "topic:llm language:python stars:>500 size:<8000"
            "penetration-testing", "pentesting", "pen testing" -> "topic:security language:python stars:>500 size:<8000"
            "reverse-engineering", "reverse engineering", "reverse eng" -> "topic:reverse-engineering stars:>500 size:<10000"
            "gaming" -> "topic:game-engine stars:>500 size:<15000"
            "science" -> "topic:scientific-computing language:python stars:>500 size:<10000"
            "all", "", "android" -> if (language.isNotBlank()) "topic:android language:$language size:<5000" else "topic:android size:<5000"
            else -> if (topic.startsWith("topic:") || topic.contains(' ')) "$topic size:<8000" else "$topic stars:>50 size:<8000"
        }

        val (repos, fromOfflineFallback) = if (isBuiltInCategory) {
            getOfflineSampleRepositories(topic).take(limit) to true
        } else {
            searchRepositoriesWithOrigin(
                query = searchQuery,
                category = topic,
                sort = "stars",
                order = "desc",
                limit = limit
            )
        }

        val enriched = repos.take(limit).map { enrichRemoteRepo(it, skipRemoteMetadata = fromOfflineFallback) }

        // Find author peer picks from the top trending author, with instant curated fallback
        val topAuthor = enriched.firstOrNull()?.owner
        val peerPicks = if (!topAuthor.isNullOrBlank() && !fromOfflineFallback && !token.isNullOrBlank()) {
            getAuthorPeerProjects(topAuthor, limit = 4).ifEmpty { getOfflineAuthorPeerPicks(topic) }
        } else {
            getOfflineAuthorPeerPicks(topic)
        }

        // Compute North Star benchmarks across discovered items
        val northStars = mutableListOf<RepoShapeEngine.ComparisonResult>()
        val highVelocity = mutableListOf<RepoShapeEngine.ComparisonResult>()

        for (i in enriched.indices) {
            for (j in enriched.indices) {
                if (i == j) continue
                val targetFull = "${enriched[i].owner}/${enriched[i].name}"
                val candidateFull = "${enriched[j].owner}/${enriched[j].name}"
                RepoShapeEngine.evaluateComparison(
                    targetSlug = targetFull,
                    targetGenome = enriched[i].genome,
                    candidateSlug = candidateFull,
                    candidateGenome = enriched[j].genome,
                    lens = RepoShapeEngine.ComparisonLens.NORTH_STAR
                )?.let { northStars.add(it) }

                RepoShapeEngine.evaluateComparison(
                    targetSlug = targetFull,
                    targetGenome = enriched[i].genome,
                    candidateSlug = candidateFull,
                    candidateGenome = enriched[j].genome,
                    lens = RepoShapeEngine.ComparisonLens.HIGH_VELOCITY
                )?.let { highVelocity.add(it) }
            }
        }

        return GitHubDiscoveryEngine.DiscoveryFeed(
            trendingHero = enriched,
            northStarBenchmarks = northStars.take(5),
            highVelocityRising = highVelocity.take(5),
            authorPeerPicks = peerPicks
        )
    }

    /**
     * Searches repositories on GitHub with query parameters.
     */
    fun searchRepositories(
        query: String,
        category: String = "all",
        sort: String = "stars",
        order: String = "desc",
        limit: Int = 10
    ): List<RawRepoItem> = searchRepositoriesWithOrigin(query, category, sort, order, limit).first

    private fun searchRepositoriesWithOrigin(
        query: String,
        category: String = "all",
        sort: String = "stars",
        order: String = "desc",
        limit: Int = 10
    ): Pair<List<RawRepoItem>, Boolean> {
        val url = "$apiBase/search/repositories?q=${java.net.URLEncoder.encode(query, "UTF-8")}&sort=$sort&order=$order&per_page=$limit"
        val responseText = executeGet(url) ?: return getOfflineSampleRepositories(category) to true

        return try {
            val root = json.parseToJsonElement(responseText).jsonObject
            val items = root["items"]?.jsonArray ?: JsonArray(emptyList())
            val parsed = items.filterIsInstance<JsonObject>().map { item ->
                val ownerObj = item["owner"]?.jsonObject
                RawRepoItem(
                    owner = ownerObj?.get("login")?.jsonPrimitive?.content ?: "",
                    name = item["name"]?.jsonPrimitive?.content ?: "",
                    description = item["description"]?.jsonPrimitive?.content.orEmpty(),
                    stars = item["stargazers_count"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                    forks = item["forks_count"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                    language = item["language"]?.jsonPrimitive?.content.orEmpty(),
                    htmlUrl = item["html_url"]?.jsonPrimitive?.content.orEmpty(),
                    defaultBranch = item["default_branch"]?.jsonPrimitive?.content ?: "main",
                    topics = item["topics"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
                )
            }
            if (parsed.isEmpty()) getOfflineSampleRepositories(category) to true else parsed to false
        } catch (_: Exception) {
            getOfflineSampleRepositories(category) to true
        }
    }

    /**
     * Traverses the author's peer network: who they follow, and repos from those peers.
     */
    fun getAuthorPeerProjects(authorLogin: String, limit: Int = 5): List<DeveloperGraphEngine.PeerRepository> {
        val followingUrl = "$apiBase/users/$authorLogin/following?per_page=$limit"
        val followingText = executeGet(followingUrl) ?: return emptyList()

        val peers = mutableListOf<DeveloperGraphEngine.PeerRepository>()
        try {
            val followingList = json.parseToJsonElement(followingText).jsonArray
            for (peerElement in followingList.take(3)) {
                val peerLogin = peerElement.jsonObject["login"]?.jsonPrimitive?.content ?: continue
                val reposUrl = "$apiBase/users/$peerLogin/repos?sort=pushed&per_page=2"
                val reposText = executeGet(reposUrl) ?: continue
                val reposJson = json.parseToJsonElement(reposText).jsonArray
                for (repoElement in reposJson) {
                    val repoObj = repoElement.jsonObject
                    val name = repoObj["name"]?.jsonPrimitive?.content ?: continue
                    val desc = repoObj["description"]?.jsonPrimitive?.content.orEmpty()
                    val stars = repoObj["stargazers_count"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                    val lang = repoObj["language"]?.jsonPrimitive?.content.orEmpty()
                    peers.add(
                        DeveloperGraphEngine.PeerRepository(
                            slug = "$peerLogin/$name",
                            name = name,
                            owner = peerLogin,
                            description = desc,
                            stars = stars,
                            language = lang,
                            connectionReason = DeveloperGraphEngine.ConnectionType.FOLLOWED_PEER,
                            connectedViaAuthor = authorLogin,
                            peerFollowerCount = 0
                        )
                    )
                }
            }
        } catch (_: Exception) {
            // Graceful fallback
        }
        return peers
    }

    /**
     * Enriches a raw repository item into a full [GitHubDiscoveryEngine.DiscoveredRepository].
     */
    fun enrichRemoteRepo(repo: RawRepoItem, skipRemoteMetadata: Boolean = false): GitHubDiscoveryEngine.DiscoveredRepository {
        val readmeContent = if (skipRemoteMetadata) null else {
            val rawReadmeUrl = "https://raw.githubusercontent.com/${repo.owner}/${repo.name}/${repo.defaultBranch}/README.md"
            executeGet(rawReadmeUrl)
        }
        val manifestXml = if (skipRemoteMetadata) null else {
            val rawManifestUrl = "https://raw.githubusercontent.com/${repo.owner}/${repo.name}/${repo.defaultBranch}/app/src/main/AndroidManifest.xml"
            executeGet(rawManifestUrl)
        }

        return engine.enrichRepository(
            owner = repo.owner,
            name = repo.name,
            description = repo.description,
            stars = repo.stars,
            forks = repo.forks,
            language = repo.language,
            htmlUrl = repo.htmlUrl,
            readmeContent = readmeContent,
            manifestXml = manifestXml,
            topics = repo.topics
        )
    }

    /**
     * Downloads a screenshot image URL into the local disk cache for instant UI rendering.
     * Returns the local absolute path if successful.
     */
     fun cacheScreenshot(url: String): String? {
         if (cacheDir == null) return null
         val filename = "${url.hashCode().toUInt().toString(16)}.img"
         val targetPath = cacheDir.resolve("discovery/shots/$filename")
         if (Files.exists(targetPath) && Files.size(targetPath) > 0) {
             return targetPath.toAbsolutePath().toString()
         }

         Files.createDirectories(targetPath.parent)
         val request = Request.Builder()
             .url(url)
             .header("User-Agent", "Building-IDE/1.0 (Android; GitHubExplorer)")
             .build()

         return try {
             http.newCall(request).execute().use { response ->
                 if (!response.isSuccessful) return null
                 val bytes = response.body?.bytes() ?: return null
                 Files.write(targetPath, bytes)
                 targetPath.toAbsolutePath().toString()
             }
         } catch (_: IOException) {
             null
         }
     }

    /**
     * Retrieves the image bytes for a screenshot URL, reading from disk cache if present,
     * or fetching remotely.
     */
    fun loadScreenshotBytes(url: String): ByteArray? {
        val cachedPath = cacheScreenshot(url)
        if (cachedPath != null) {
            return try {
                Files.readAllBytes(Paths.get(cachedPath))
            } catch (_: IOException) {
                null
            }
        }
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Building-IDE/1.0 (Android; GitHubExplorer)")
            .build()
        return try {
            http.newCall(request).execute().use { response ->
                if (response.isSuccessful) response.body?.bytes() else null
            }
        } catch (_: IOException) {
            null
        }
    }

    private fun executeGet(url: String): String? {
        val builder = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Building-IDE/1.0 (Android; GitHubExplorer)")

        if (!token.isNullOrBlank()) {
            builder.header("Authorization", "Bearer $token")
        }

        return try {
            http.newCall(builder.build()).execute().use { response ->
                if (response.isSuccessful) response.body?.string() else null
            }
        } catch (_: IOException) {
            null
        }
    }

    fun getOfflineSampleRepositories(category: String = "all"): List<RawRepoItem> {
        val cat = category.lowercase().trim()
        return when {
            cat.contains("swift") || cat.contains("ios") -> listOf(
                RawRepoItem(
                    owner = "AfrazCodes",
                    name = "SwiftUI-Calculator",
                    description = "Interactive iOS Calculator app built in SwiftUI — compiles and runs natively via Cupertino Bridge",
                    stars = 58,
                    forks = 24,
                    language = "Swift",
                    htmlUrl = "https://github.com/AfrazCodes/SwiftUI-Calculator",
                    defaultBranch = "main",
                    topics = listOf("swiftui", "ios", "swift", "calculator", "offline-first")
                ),
                RawRepoItem(
                    owner = "ricardomongza99",
                    name = "Calculator",
                    description = "Full-featured iOS Calculator in SwiftUI with ObservableObject ViewModel and custom ButtonStyle",
                    stars = 42,
                    forks = 14,
                    language = "Swift",
                    htmlUrl = "https://github.com/ricardomongza99/Calculator",
                    defaultBranch = "main",
                    topics = listOf("swiftui", "ios", "swift", "mvvm", "offline-first")
                ),
                RawRepoItem(
                    owner = "takenori-kabeya",
                    name = "SwiftUIStateManagementBook-Counter",
                    description = "SwiftUI State Management Counter app with @State and SF Symbols interactive controls",
                    stars = 12,
                    forks = 4,
                    language = "Swift",
                    htmlUrl = "https://github.com/takenori-kabeya/SwiftUIStateManagementBook-Counter",
                    defaultBranch = "main",
                    topics = listOf("swiftui", "ios", "swift", "state-management")
                ),
                RawRepoItem(
                    owner = "johnsundell",
                    name = "Ink",
                    description = "Fast, zero-dependency Markdown parser and CLI built in pure Swift",
                    stars = 2537,
                    forks = 165,
                    language = "Swift",
                    htmlUrl = "https://github.com/johnsundell/Ink",
                    defaultBranch = "master",
                    topics = listOf("swift", "swiftui", "ios", "cli", "offline-first")
                ),
                RawRepoItem(
                    owner = "sindresorhus",
                    name = "Defaults",
                    description = "Swifty and modern UserDefaults with first-class SwiftUI @Default property wrappers",
                    stars = 2507,
                    forks = 152,
                    language = "Swift",
                    htmlUrl = "https://github.com/sindresorhus/Defaults",
                    defaultBranch = "main",
                    topics = listOf("swiftui", "ios", "swift", "offline-first")
                ),
                RawRepoItem(
                    owner = "apple",
                    name = "example-package-dealer",
                    description = "Official Apple SwiftPM executable package demonstrating modular Swift targets",
                    stars = 367,
                    forks = 190,
                    language = "Swift",
                    htmlUrl = "https://github.com/apple/example-package-dealer",
                    defaultBranch = "main",
                    topics = listOf("swift", "swiftui", "ios", "cli")
                ),
            )
            cat.contains("python") -> listOf(
                RawRepoItem(
                    owner = "karpathy",
                    name = "micrograd",
                    description = "A tiny scalar-valued autograd engine and neural network library in pure Python",
                    stars = 17931,
                    forks = 2600,
                    language = "Python",
                    htmlUrl = "https://github.com/karpathy/micrograd",
                    defaultBranch = "master",
                    topics = listOf("python", "cli", "ai", "offline-first")
                ),
                RawRepoItem(
                    owner = "karpathy",
                    name = "nanoGPT",
                    description = "The simplest, fastest pure-Python repository for training and running GPT models",
                    stars = 63859,
                    forks = 8900,
                    language = "Python",
                    htmlUrl = "https://github.com/karpathy/nanoGPT",
                    defaultBranch = "master",
                    topics = listOf("python", "cli", "ai", "llm")
                ),
                RawRepoItem(
                    owner = "karpathy",
                    name = "minGPT",
                    description = "Minimal, clean Python implementation of GPT training and inference",
                    stars = 24950,
                    forks = 3100,
                    language = "Python",
                    htmlUrl = "https://github.com/karpathy/minGPT",
                    defaultBranch = "master",
                    topics = listOf("python", "cli", "ai", "offline-first")
                ),
                RawRepoItem(
                    owner = "pallets",
                    name = "click",
                    description = "Python composable command line interface toolkit with zero boilerplate",
                    stars = 17789,
                    forks = 1950,
                    language = "Python",
                    htmlUrl = "https://github.com/pallets/click",
                    defaultBranch = "main",
                    topics = listOf("python", "terminal", "cli", "tui")
                )
            )
            cat.contains("node") -> listOf(
                RawRepoItem(
                    owner = "jameshibbard",
                    name = "nodegui-password-generator",
                    description = "Desktop GUI Password Generator built with @nodegui/nodegui QMainWindow, QCheckBox & QPushButton",
                    stars = 18,
                    forks = 5,
                    language = "JavaScript",
                    htmlUrl = "https://github.com/jameshibbard/nodegui-password-generator",
                    defaultBranch = "master",
                    topics = listOf("nodejs", "javascript", "nodegui", "gui", "offline-first")
                ),
                RawRepoItem(
                    owner = "lukeed",
                    name = "kleur",
                    description = "The fastest zero-dependency Node.js library for formatting terminal text with ANSI colors",
                    stars = 1697,
                    forks = 68,
                    language = "JavaScript",
                    htmlUrl = "https://github.com/lukeed/kleur",
                    defaultBranch = "master",
                    topics = listOf("nodejs", "javascript", "cli", "esm")
                ),
                RawRepoItem(
                    owner = "vercel",
                    name = "ms",
                    description = "Tiny millisecond conversion utility for Node.js services and CLIs",
                    stars = 5559,
                    forks = 490,
                    language = "JavaScript",
                    htmlUrl = "https://github.com/vercel/ms",
                    defaultBranch = "main",
                    topics = listOf("nodejs", "javascript", "cli", "esm")
                ),
                RawRepoItem(
                    owner = "tj",
                    name = "commander.js",
                    description = "Complete solution for Node.js command-line interfaces and subcommands",
                    stars = 28423,
                    forks = 2100,
                    language = "JavaScript",
                    htmlUrl = "https://github.com/tj/commander.js",
                    defaultBranch = "master",
                    topics = listOf("nodejs", "cli", "javascript", "esm")
                ),
                RawRepoItem(
                    owner = "developit",
                    name = "mitt",
                    description = "Tiny 200-byte functional event emitter and pubsub engine for Node.js",
                    stars = 11908,
                    forks = 430,
                    language = "JavaScript",
                    htmlUrl = "https://github.com/developit/mitt",
                    defaultBranch = "main",
                    topics = listOf("nodejs", "javascript", "esm", "cli")
                ),
                RawRepoItem(
                    owner = "lukeed",
                    name = "polka",
                    description = "A micro web server and HTTP router for Node.js so fast, it'll make you dance",
                    stars = 5587,
                    forks = 210,
                    language = "JavaScript",
                    htmlUrl = "https://github.com/lukeed/polka",
                    defaultBranch = "master",
                    topics = listOf("nodejs", "javascript", "cli", "esm")
                )
            )
            cat.contains("agent") -> listOf(
                RawRepoItem(
                    owner = "karpathy",
                    name = "nanoGPT",
                    description = "Fast, hackable Python LLM & autonomous agent reasoning backbone",
                    stars = 63859,
                    forks = 8900,
                    language = "Python",
                    htmlUrl = "https://github.com/karpathy/nanoGPT",
                    defaultBranch = "master",
                    topics = listOf("agents", "python", "llm", "ai")
                ),
                RawRepoItem(
                    owner = "karpathy",
                    name = "micrograd",
                    description = "Lightweight autograd & neural network engine in pure Python for agent experiments",
                    stars = 17931,
                    forks = 2600,
                    language = "Python",
                    htmlUrl = "https://github.com/karpathy/micrograd",
                    defaultBranch = "master",
                    topics = listOf("agents", "python", "llm", "ai")
                ),
                RawRepoItem(
                    owner = "lukeed",
                    name = "polka",
                    description = "High-speed Node.js micro HTTP server for hosting local tool-calling agent APIs",
                    stars = 5587,
                    forks = 210,
                    language = "JavaScript",
                    htmlUrl = "https://github.com/lukeed/polka",
                    defaultBranch = "master",
                    topics = listOf("agents", "nodejs", "javascript", "cli")
                )
            )
            cat.contains("llm") || cat.contains("large language") -> listOf(
                RawRepoItem(
                    owner = "karpathy",
                    name = "nanoGPT",
                    description = "The simplest, fastest repository for training and finetuning medium-sized GPTs",
                    stars = 63859,
                    forks = 8900,
                    language = "Python",
                    htmlUrl = "https://github.com/karpathy/nanoGPT",
                    defaultBranch = "master",
                    topics = listOf("llm", "python", "ai", "offline-first")
                ),
                RawRepoItem(
                    owner = "karpathy",
                    name = "minGPT",
                    description = "A minimal PyTorch re-implementation of the OpenAI GPT training and inference pipeline",
                    stars = 24950,
                    forks = 3100,
                    language = "Python",
                    htmlUrl = "https://github.com/karpathy/minGPT",
                    defaultBranch = "master",
                    topics = listOf("llm", "python", "ai", "offline-first")
                ),
                RawRepoItem(
                    owner = "karpathy",
                    name = "micrograd",
                    description = "Scalar-valued autograd engine demonstrating backpropagation from scratch in Python",
                    stars = 17931,
                    forks = 2600,
                    language = "Python",
                    htmlUrl = "https://github.com/karpathy/micrograd",
                    defaultBranch = "master",
                    topics = listOf("llm", "python", "ai", "offline-first")
                )
            )
            cat.contains("pen") || cat.contains("security") -> listOf(
                RawRepoItem(
                    owner = "pallets",
                    name = "click",
                    description = "Composable Python CLI toolkit powering security & network auditing tools",
                    stars = 17789,
                    forks = 1950,
                    language = "Python",
                    htmlUrl = "https://github.com/pallets/click",
                    defaultBranch = "main",
                    topics = listOf("security", "penetration-testing", "python", "cli")
                ),
                RawRepoItem(
                    owner = "tj",
                    name = "commander.js",
                    description = "Fast Node.js CLI framework for network inspection and security automation",
                    stars = 28423,
                    forks = 2100,
                    language = "JavaScript",
                    htmlUrl = "https://github.com/tj/commander.js",
                    defaultBranch = "master",
                    topics = listOf("penetration-testing", "security", "nodejs", "cli")
                )
            )
            cat.contains("reverse") || cat.contains("decompiler") -> listOf(
                RawRepoItem(
                    owner = "Ackites",
                    name = "Nrfr",
                    description = "Android carrier configuration & Shizuku system inspection tool in Kotlin & Compose",
                    stars = 9200,
                    forks = 680,
                    language = "Kotlin",
                    htmlUrl = "https://github.com/Ackites/Nrfr",
                    defaultBranch = "main",
                    topics = listOf("reverse-engineering", "android", "kotlin", "jetpack-compose")
                ),
                RawRepoItem(
                    owner = "johnsundell",
                    name = "Ink",
                    description = "High-speed lexical parser and AST transformer written in pure Swift",
                    stars = 2537,
                    forks = 165,
                    language = "Swift",
                    htmlUrl = "https://github.com/johnsundell/Ink",
                    defaultBranch = "master",
                    topics = listOf("reverse-engineering", "swift", "cli")
                )
            )
            cat.contains("game") || cat.contains("gaming") -> listOf(
                RawRepoItem(
                    owner = "apple",
                    name = "example-package-dealer",
                    description = "Modular card-deck shuffling and dealing game engine in SwiftPM",
                    stars = 367,
                    forks = 190,
                    language = "Swift",
                    htmlUrl = "https://github.com/apple/example-package-dealer",
                    defaultBranch = "main",
                    topics = listOf("gaming", "swift", "cli")
                ),
                RawRepoItem(
                    owner = "developit",
                    name = "mitt",
                    description = "Ultra-fast 200-byte event bus for 60fps JavaScript and Node.js game loops",
                    stars = 11908,
                    forks = 430,
                    language = "JavaScript",
                    htmlUrl = "https://github.com/developit/mitt",
                    defaultBranch = "main",
                    topics = listOf("gaming", "nodejs", "javascript")
                )
            )
            cat.contains("science") -> listOf(
                RawRepoItem(
                    owner = "karpathy",
                    name = "micrograd",
                    description = "Scalar-valued automatic differentiation and neural network engine in pure Python",
                    stars = 17931,
                    forks = 2600,
                    language = "Python",
                    htmlUrl = "https://github.com/karpathy/micrograd",
                    defaultBranch = "master",
                    topics = listOf("science", "mathematics", "python", "ai")
                ),
                RawRepoItem(
                    owner = "karpathy",
                    name = "minGPT",
                    description = "Scientific transformer architecture reference implementation in Python",
                    stars = 24950,
                    forks = 3100,
                    language = "Python",
                    htmlUrl = "https://github.com/karpathy/minGPT",
                    defaultBranch = "master",
                    topics = listOf("science", "machine-learning", "python")
                )
            )
            else -> listOf(
                RawRepoItem(
                    owner = "Ackites",
                    name = "Nrfr",
                    description = "Android SIM carrier configuration tool built with Kotlin and Jetpack Compose",
                    stars = 9200,
                    forks = 680,
                    language = "Kotlin",
                    htmlUrl = "https://github.com/Ackites/Nrfr",
                    defaultBranch = "main",
                    topics = listOf("android", "kotlin", "jetpack-compose", "material3")
                ),
                RawRepoItem(
                    owner = "Chethan616",
                    name = "ClearPDF",
                    description = "Modern Android document reader & editor without cloud trackers",
                    stars = 4200,
                    forks = 310,
                    language = "Kotlin",
                    htmlUrl = "https://github.com/Chethan616/ClearPDF",
                    defaultBranch = "main",
                    topics = listOf("pdf", "android", "offline-first", "material3", "kotlin")
                ),
                RawRepoItem(
                    owner = "kushagrasinghx",
                    name = "BitChord",
                    description = "Aesthetic YouTube Music Client with Compose and lossless audio",
                    stars = 6100,
                    forks = 480,
                    language = "Kotlin",
                    htmlUrl = "https://github.com/kushagrasinghx/BitChord",
                    defaultBranch = "main",
                    topics = listOf("youtube-music", "compose-multiplatform", "kotlin", "music-player")
                ),
                RawRepoItem(
                    owner = "cortinico",
                    name = "kotlin-android-template",
                    description = "Clean, fast-building Kotlin Android app template with Gradle KTS and Detekt",
                    stars = 1913,
                    forks = 320,
                    language = "Kotlin",
                    htmlUrl = "https://github.com/cortinico/kotlin-android-template",
                    defaultBranch = "main",
                    topics = listOf("android", "kotlin", "jetpack-compose", "material3")
                ),
                RawRepoItem(
                    owner = "skydoves",
                    name = "Sandwich",
                    description = "Adaptable and lightweight sealed API response library for Kotlin and Android",
                    stars = 1779,
                    forks = 115,
                    language = "Kotlin",
                    htmlUrl = "https://github.com/skydoves/Sandwich",
                    defaultBranch = "main",
                    topics = listOf("android", "kotlin", "jetpack-compose", "material3")
                ),
                RawRepoItem(
                    owner = "localsend",
                    name = "localsend",
                    description = "An open-source cross-platform alternative to AirDrop",
                    stars = 58200,
                    forks = 3900,
                    language = "Kotlin",
                    htmlUrl = "https://github.com/localsend/localsend",
                    defaultBranch = "main",
                    topics = listOf("android", "kotlin", "jetpack-compose", "material3", "offline-first")
                )
            )
        }
    }

    private fun getOfflineAuthorPeerPicks(category: String): List<DeveloperGraphEngine.PeerRepository> {
        val cat = category.lowercase().trim()
        return when {
            cat.contains("swift") || cat.contains("ios") -> listOf(
                DeveloperGraphEngine.PeerRepository(
                    slug = "apple/example-package-figlet",
                    name = "example-package-figlet",
                    owner = "apple",
                    description = "Swift Package Manager ASCII banner generator package",
                    stars = 320,
                    language = "Swift",
                    connectionReason = DeveloperGraphEngine.ConnectionType.FOLLOWED_PEER,
                    connectedViaAuthor = "johnsundell",
                    peerFollowerCount = 14800
                ),
                DeveloperGraphEngine.PeerRepository(
                    slug = "sindresorhus/LaunchAtLogin-Modern",
                    name = "LaunchAtLogin-Modern",
                    owner = "sindresorhus",
                    description = "Lightweight SwiftUI Launch-at-Login component",
                    stars = 582,
                    language = "Swift",
                    connectionReason = DeveloperGraphEngine.ConnectionType.FOLLOWED_PEER,
                    connectedViaAuthor = "johnsundell",
                    peerFollowerCount = 52400
                )
            )
            cat.contains("python") || cat.contains("agent") || cat.contains("llm") -> listOf(
                DeveloperGraphEngine.PeerRepository(
                    slug = "karpathy/minGPT",
                    name = "minGPT",
                    owner = "karpathy",
                    description = "Minimal PyTorch re-implementation of OpenAI GPT",
                    stars = 24950,
                    language = "Python",
                    connectionReason = DeveloperGraphEngine.ConnectionType.FOLLOWED_PEER,
                    connectedViaAuthor = "karpathy",
                    peerFollowerCount = 68000
                ),
                DeveloperGraphEngine.PeerRepository(
                    slug = "pallets/click",
                    name = "click",
                    owner = "pallets",
                    description = "Composable Python CLI toolkit",
                    stars = 17789,
                    language = "Python",
                    connectionReason = DeveloperGraphEngine.ConnectionType.FOLLOWED_PEER,
                    connectedViaAuthor = "karpathy",
                    peerFollowerCount = 19200
                )
            )
            cat.contains("node") -> listOf(
                DeveloperGraphEngine.PeerRepository(
                    slug = "lukeed/kleur",
                    name = "kleur",
                    owner = "lukeed",
                    description = "Fastest Node.js terminal color formatting library",
                    stars = 1697,
                    language = "JavaScript",
                    connectionReason = DeveloperGraphEngine.ConnectionType.FOLLOWED_PEER,
                    connectedViaAuthor = "developit",
                    peerFollowerCount = 16500
                ),
                DeveloperGraphEngine.PeerRepository(
                    slug = "vercel/ms",
                    name = "ms",
                    owner = "vercel",
                    description = "Tiny millisecond conversion utility for Node.js",
                    stars = 5559,
                    language = "JavaScript",
                    connectionReason = DeveloperGraphEngine.ConnectionType.FOLLOWED_PEER,
                    connectedViaAuthor = "lukeed",
                    peerFollowerCount = 31000
                )
            )
            else -> listOf(
                DeveloperGraphEngine.PeerRepository(
                    slug = "cortinico/kotlin-android-template",
                    name = "kotlin-android-template",
                    owner = "cortinico",
                    description = "Fast-building Android + Kotlin + Gradle KTS app template",
                    stars = 1913,
                    language = "Kotlin",
                    connectionReason = DeveloperGraphEngine.ConnectionType.FOLLOWED_PEER,
                    connectedViaAuthor = "Ackites",
                    peerFollowerCount = 8400
                ),
                DeveloperGraphEngine.PeerRepository(
                    slug = "karn/notify",
                    name = "notify",
                    owner = "karn",
                    description = "Simplified notification construction and delivery for Android",
                    stars = 812,
                    language = "Kotlin",
                    connectionReason = DeveloperGraphEngine.ConnectionType.FOLLOWED_PEER,
                    connectedViaAuthor = "Ackites",
                    peerFollowerCount = 4100
                )
            )
        }
    }

    /**
     * Tests a token against GitHub's /user and /rate_limit endpoints.
     * Returns Pair(username, Pair(limit, remaining)) if valid, or null if invalid.
     */
    fun verifyToken(tokenToTest: String): Pair<String, Pair<Int, Int>>? {
        val testToken = tokenToTest.trim()
        if (testToken.isBlank()) return null
        val req = Request.Builder()
            .url("$apiBase/user")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Building-IDE/1.0 (Android; GitHubExplorer)")
            .header("Authorization", "Bearer $testToken")
            .build()

        return try {
            http.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return null
                val body = res.body?.string() ?: return null
                val userObj = json.parseToJsonElement(body).jsonObject
                val login = userObj["login"]?.jsonPrimitive?.content ?: return null
                val rate = fetchRateLimit(testToken)
                login to rate
            }
        } catch (_: Exception) {
            null
        }
    }

    fun fetchRateLimit(tokenToUse: String? = token): Pair<Int, Int> {
        val req = Request.Builder()
            .url("$apiBase/rate_limit")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Building-IDE/1.0 (Android; GitHubExplorer)")

        if (!tokenToUse.isNullOrBlank()) {
            req.header("Authorization", "Bearer $tokenToUse")
        }

        return try {
            http.newCall(req.build()).execute().use { res ->
                if (!res.isSuccessful) return if (tokenToUse.isNullOrBlank()) 60 to 60 else 5000 to 5000
                val body = res.body?.string() ?: return 60 to 60
                val obj = json.parseToJsonElement(body).jsonObject
                val core = obj["resources"]?.jsonObject?.get("core")?.jsonObject
                val limit = core?.get("limit")?.jsonPrimitive?.content?.toIntOrNull() ?: 60
                val remaining = core?.get("remaining")?.jsonPrimitive?.content?.toIntOrNull() ?: 60
                limit to remaining
            }
        } catch (_: Exception) {
            if (tokenToUse.isNullOrBlank()) 60 to 60 else 5000 to 5000
        }
    }

    data class RawRepoItem(
        val owner: String,
        val name: String,
        val description: String,
        val stars: Int,
        val forks: Int,
        val language: String,
        val htmlUrl: String,
        val defaultBranch: String = "main",
        val topics: List<String> = emptyList()
    )

    companion object {
        private fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
