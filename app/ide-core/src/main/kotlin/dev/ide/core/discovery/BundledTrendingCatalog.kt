package dev.ide.core.discovery

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path

/**
 * Runtime loader and schema for the build-time bundled GitHub Trending catalog
 * (`discovery/bundled-trending-catalog.json`) and its bundled app screenshots
 * (`discovery/shots/<slug>.png`).
 *
 * Covers `daily`, `weekly`, and `monthly` trending lists across all popular languages
 * supported on the phone (`all`, `kotlin`, `swift`, `python`, `typescript`, `javascript`,
 * `java`, `rust`, `c++`).
 */
object BundledTrendingCatalog {

    const val CATALOG_RESOURCE_PATH = "discovery/bundled-trending-catalog.json"

    val SUPPORTED_LANGUAGE_KEYS = listOf(
        "all",
        "kotlin",
        "swift",
        "python",
        "typescript",
        "javascript",
        "java",
        "rust",
        "c++"
    )

    val SUPPORTED_PERIODS = listOf("daily", "weekly", "monthly")

    data class BundledPeerEntry(
        val slug: String,
        val name: String,
        val owner: String,
        val description: String,
        val stars: Int,
        val language: String,
        val connectionType: String,
        val connectedViaAuthor: String,
        val socialChain: List<String> = emptyList(),
        val relevanceScore: Int = 80,
        val relevanceReason: String = ""
    )

    data class BundledRepoEntry(
        val owner: String,
        val name: String,
        val description: String,
        val language: String,
        val stars: Int,
        val forks: Int,
        val starsInPeriod: Int = 0,
        val period: String = "daily",
        val languageKey: String = "all",
        val htmlUrl: String,
        val homepageUrl: String? = null,
        val defaultBranch: String = "main",
        val builtByAvatars: List<String> = emptyList(),
        val topics: List<String> = emptyList(),
        val bundledScreenshotResource: String? = null,
        val screenshotTier: String = "BUNDLED_BUILD_TIME",
        val screenshotOrigin: String = "Bundled at build time",
        val relatedPeers: List<BundledPeerEntry> = emptyList()
    )

    data class BundledCatalogData(
        val bundledAtIso: String,
        val generatorVersion: String = "1.0",
        val lists: Map<String, List<BundledRepoEntry>> = emptyMap()
    )

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        prettyPrint = true
    }

    @Volatile
    private var cachedCatalog: BundledCatalogData? = null

    fun listKey(languageKey: String, period: String): String {
        val normLang = normalizeLanguageKey(languageKey)
        val normPeriod = period.lowercase().trim().takeIf { it in SUPPORTED_PERIODS } ?: "daily"
        return "$normLang:$normPeriod"
    }

    fun normalizeLanguageKey(raw: String): String {
        return when (val clean = raw.lowercase().trim()) {
            "", "all", "android", "popular" -> "all"
            "kt", "kotlin" -> "kotlin"
            "swift", "swiftui", "ios" -> "swift"
            "py", "python", "python3" -> "python"
            "ts", "typescript" -> "typescript"
            "js", "javascript", "node", "nodejs", "node.js" -> "javascript"
            "java" -> "java"
            "rs", "rust" -> "rust"
            "cpp", "c++", "cxx", "c" -> "c++"
            else -> if (clean in SUPPORTED_LANGUAGE_KEYS) clean else "all"
        }
    }

    fun loadCatalog(): BundledCatalogData? {
        cachedCatalog?.let { return it }
        val stream = BundledTrendingCatalog::class.java.classLoader
            ?.getResourceAsStream(CATALOG_RESOURCE_PATH)
            ?: Thread.currentThread().contextClassLoader?.getResourceAsStream(CATALOG_RESOURCE_PATH)
            ?: return null
        return try {
            val text = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val parsed = decodeCatalogJson(text)
            cachedCatalog = parsed
            parsed
        } catch (_: Exception) {
            null
        }
    }

    fun decodeCatalogJson(text: String): BundledCatalogData {
        val root = json.parseToJsonElement(text).jsonObject
        val bundledAtIso = root["bundledAtIso"]?.jsonPrimitive?.content ?: ""
        val generatorVersion = root["generatorVersion"]?.jsonPrimitive?.content ?: "1.0"
        val listsObj = root["lists"]?.jsonObject ?: JsonObject(emptyMap())
        val listsMap = LinkedHashMap<String, List<BundledRepoEntry>>()

        for ((key, arrElem) in listsObj) {
            val arr = arrElem as? JsonArray ?: continue
            val entries = arr.filterIsInstance<JsonObject>().map { obj ->
                val peersArr = obj["relatedPeers"] as? JsonArray ?: JsonArray(emptyList())
                val peers = peersArr.filterIsInstance<JsonObject>().map { p ->
                    BundledPeerEntry(
                        slug = p["slug"]?.jsonPrimitive?.content.orEmpty(),
                        name = p["name"]?.jsonPrimitive?.content.orEmpty(),
                        owner = p["owner"]?.jsonPrimitive?.content.orEmpty(),
                        description = p["description"]?.jsonPrimitive?.content.orEmpty(),
                        stars = p["stars"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                        language = p["language"]?.jsonPrimitive?.content.orEmpty(),
                        connectionType = p["connectionType"]?.jsonPrimitive?.content ?: "FOLLOWED_PEER",
                        connectedViaAuthor = p["connectedViaAuthor"]?.jsonPrimitive?.content.orEmpty(),
                        socialChain = (p["socialChain"] as? JsonArray)?.map { it.jsonPrimitive.content } ?: emptyList(),
                        relevanceScore = p["relevanceScore"]?.jsonPrimitive?.content?.toIntOrNull() ?: 80,
                        relevanceReason = p["relevanceReason"]?.jsonPrimitive?.content.orEmpty()
                    )
                }
                BundledRepoEntry(
                    owner = obj["owner"]?.jsonPrimitive?.content.orEmpty(),
                    name = obj["name"]?.jsonPrimitive?.content.orEmpty(),
                    description = obj["description"]?.jsonPrimitive?.content.orEmpty(),
                    language = obj["language"]?.jsonPrimitive?.content.orEmpty(),
                    stars = obj["stars"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                    forks = obj["forks"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                    starsInPeriod = obj["starsInPeriod"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                    period = obj["period"]?.jsonPrimitive?.content ?: "daily",
                    languageKey = obj["languageKey"]?.jsonPrimitive?.content ?: "all",
                    htmlUrl = obj["htmlUrl"]?.jsonPrimitive?.content.orEmpty(),
                    homepageUrl = obj["homepageUrl"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() },
                    defaultBranch = obj["defaultBranch"]?.jsonPrimitive?.content ?: "main",
                    builtByAvatars = (obj["builtByAvatars"] as? JsonArray)?.map { it.jsonPrimitive.content } ?: emptyList(),
                    topics = (obj["topics"] as? JsonArray)?.map { it.jsonPrimitive.content } ?: emptyList(),
                    bundledScreenshotResource = obj["bundledScreenshotResource"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() },
                    screenshotTier = obj["screenshotTier"]?.jsonPrimitive?.content ?: "BUNDLED_BUILD_TIME",
                    screenshotOrigin = obj["screenshotOrigin"]?.jsonPrimitive?.content ?: "Bundled at build time",
                    relatedPeers = peers
                )
            }
            listsMap[key] = entries
        }
        return BundledCatalogData(
            bundledAtIso = bundledAtIso,
            generatorVersion = generatorVersion,
            lists = listsMap
        )
    }

    fun encodeCatalogJson(data: BundledCatalogData): String {
        val root = buildJsonObject {
            put("bundledAtIso", data.bundledAtIso)
            put("generatorVersion", data.generatorVersion)
            put(
                "lists",
                buildJsonObject {
                    for ((key, entries) in data.lists) {
                        put(
                            key,
                            buildJsonArray {
                                for (entry in entries) {
                                    add(
                                        buildJsonObject {
                                            put("owner", entry.owner)
                                            put("name", entry.name)
                                            put("description", entry.description)
                                            put("language", entry.language)
                                            put("stars", entry.stars)
                                            put("forks", entry.forks)
                                            put("starsInPeriod", entry.starsInPeriod)
                                            put("period", entry.period)
                                            put("languageKey", entry.languageKey)
                                            put("htmlUrl", entry.htmlUrl)
                                            if (entry.homepageUrl != null) put("homepageUrl", entry.homepageUrl)
                                            put("defaultBranch", entry.defaultBranch)
                                            put("builtByAvatars", buildJsonArray { entry.builtByAvatars.forEach { add(JsonPrimitive(it)) } })
                                            put("topics", buildJsonArray { entry.topics.forEach { add(JsonPrimitive(it)) } })
                                            if (entry.bundledScreenshotResource != null) {
                                                put("bundledScreenshotResource", entry.bundledScreenshotResource)
                                            }
                                            put("screenshotTier", entry.screenshotTier)
                                            put("screenshotOrigin", entry.screenshotOrigin)
                                            put(
                                                "relatedPeers",
                                                buildJsonArray {
                                                    for (p in entry.relatedPeers) {
                                                        add(
                                                            buildJsonObject {
                                                                put("slug", p.slug)
                                                                put("name", p.name)
                                                                put("owner", p.owner)
                                                                put("description", p.description)
                                                                put("stars", p.stars)
                                                                put("language", p.language)
                                                                put("connectionType", p.connectionType)
                                                                put("connectedViaAuthor", p.connectedViaAuthor)
                                                                put("socialChain", buildJsonArray { p.socialChain.forEach { add(JsonPrimitive(it)) } })
                                                                put("relevanceScore", p.relevanceScore)
                                                                put("relevanceReason", p.relevanceReason)
                                                            }
                                                        )
                                                    }
                                                }
                                            )
                                        }
                                    )
                                }
                            }
                        )
                    }
                }
            )
        }
        return json.encodeToString(JsonObject.serializer(), root)
    }

    fun getTrendingEntries(languageKey: String, period: String): List<BundledRepoEntry> {
        val catalog = loadCatalog() ?: return emptyList()
        val exactKey = listKey(languageKey, period)
        val exact = catalog.lists[exactKey]
        if (!exact.isNullOrEmpty()) return exact

        val normLang = normalizeLanguageKey(languageKey)
        val normPeriod = period.lowercase().trim().takeIf { it in SUPPORTED_PERIODS } ?: "daily"
        if (normLang != "all") {
            val fromAll = catalog.lists["all:$normPeriod"].orEmpty().filter {
                normalizeLanguageKey(it.language) == normLang
            }
            if (fromAll.isNotEmpty()) return fromAll
        }
        return catalog.lists["$normLang:daily"]
            ?: catalog.lists["all:$normPeriod"]
            ?: catalog.lists.values.firstOrNull().orEmpty()
    }

    fun loadBundledScreenshotBytes(resourcePath: String): ByteArray? {
        val clean = resourcePath.removePrefix("/")
        val stream = BundledTrendingCatalog::class.java.classLoader
            ?.getResourceAsStream(clean)
            ?: Thread.currentThread().contextClassLoader?.getResourceAsStream(clean)
            ?: return null
        return try {
            stream.use { it.readBytes() }
        } catch (_: Exception) {
            null
        }
    }

    fun materializeBundledScreenshot(resourcePath: String?, cacheDir: Path?): String? {
        if (resourcePath.isNullOrBlank()) return null
        val bytes = loadBundledScreenshotBytes(resourcePath) ?: return null
        val fileName = resourcePath.substringAfterLast('/')
        val targetDir = cacheDir?.resolve("discovery/bundled_shots")
            ?: Path.of(System.getProperty("java.io.tmpdir"), "building_discovery_bundled_shots")
        return try {
            Files.createDirectories(targetDir)
            val outFile = targetDir.resolve(fileName)
            if (!Files.exists(outFile) || Files.size(outFile) != bytes.size.toLong()) {
                Files.write(outFile, bytes)
            }
            outFile.toAbsolutePath().toString()
        } catch (_: Exception) {
            null
        }
    }
}
