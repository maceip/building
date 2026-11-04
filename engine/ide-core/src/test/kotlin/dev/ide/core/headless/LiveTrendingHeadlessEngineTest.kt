package dev.ide.core.headless

import dev.ide.core.discovery.GitHubDiscoveryClient
import dev.ide.core.discovery.GitHubDiscoveryClient.RawRepoItem
import dev.ide.vcs.impl.GitProvider
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LiveTrendingHeadlessEngineTest {

    data class RepoBuildResult(
        val language: String,
        val repo: String,
        val cloneMs: Long,
        val buildMs: Long,
        val buildSystemId: String,
        val taskId: String,
        val succeeded: Boolean,
        val tailLog: List<String>,
    )

    @OptIn(ExperimentalPathApi::class)
    @Test
    fun cloneAndBuildAllDailyTrendingSwiftKotlinAndPythonHeadlessly() = runBlocking {
        val cacheRoot = Paths.get("/tmp/trending-daily-cache")
        Files.createDirectories(cacheRoot)
        val sharedCaches = cacheRoot.resolve("shared-engine-caches")
        Files.createDirectories(sharedCaches)
        val gitProvider = GitProvider(cacheRoot.resolve("git-config"))

        val discovery = GitHubDiscoveryClient(cacheDir = cacheRoot.resolve("discovery-cache"))
        val swiftTrending = discovery.fetchLiveTrendingRepositories(language = "swift", period = "daily", limit = 25)
        val kotlinTrending = discovery.fetchLiveTrendingRepositories(language = "kotlin", period = "daily", limit = 25)
        val pythonTrending = discovery.fetchLiveTrendingRepositories(language = "python", period = "daily", limit = 25)

        println("=== TODAY'S FULL DAILY GITHUB TRENDING LISTS ===")
        println("Swift  (${swiftTrending.size}): ${swiftTrending.joinToString { "${it.owner}/${it.name}" }}")
        println("Kotlin (${kotlinTrending.size}): ${kotlinTrending.joinToString { "${it.owner}/${it.name}" }}")
        println("Python (${pythonTrending.size}): ${pythonTrending.joinToString { "${it.owner}/${it.name}" }}")

        assertTrue(swiftTrending.isNotEmpty(), "Expected live Swift daily trending repositories")
        assertTrue(kotlinTrending.isNotEmpty(), "Expected live Kotlin daily trending repositories")
        assertTrue(pythonTrending.isNotEmpty(), "Expected live Python daily trending repositories")

        val allResults = ArrayList<RepoBuildResult>()

        for ((lang, repos) in listOf("python" to pythonTrending, "swift" to swiftTrending, "kotlin" to kotlinTrending)) {
            println("\n================ TESTING ALL ${repos.size} ${lang.uppercase()} REPOS ================")
            for ((idx, item) in repos.withIndex()) {
                val res = cloneImportAndBuildRepo(
                    language = lang,
                    index = idx + 1,
                    total = repos.size,
                    item = item,
                    cacheRoot = cacheRoot,
                    sharedCaches = sharedCaches,
                    gitProvider = gitProvider,
                )
                allResults += res
                println(
                    "[${lang.uppercase()} ${idx + 1}/${repos.size}] ${res.repo} -> " +
                        "succeeded=${res.succeeded} buildSystem=${res.buildSystemId} task=${res.taskId} " +
                        "clone=${res.cloneMs}ms build=${res.buildMs}ms",
                )
                if (!res.succeeded) {
                    println("  FAILURE TAIL LOG (${res.repo}):")
                    res.tailLog.forEach { println("    $it") }
                }
            }
        }

        val failed = allResults.filter { !it.succeeded }
        println("\n================ FINAL DAILY TRENDING SUMMARY ================")
        println("Total Tested: ${allResults.size} | Succeeded: ${allResults.count { it.succeeded }} | Failed: ${failed.size}")
        for (lang in listOf("swift", "kotlin", "python")) {
            val group = allResults.filter { it.language == lang }
            println("  ${lang.uppercase()}: ${group.count { it.succeeded }}/${group.size} succeeded")
        }
        if (failed.isNotEmpty()) {
            println("Failed repos:")
            failed.forEach { f ->
                println("  - [${f.language}] ${f.repo} (buildSystem=${f.buildSystemId}, task=${f.taskId}): ${f.tailLog.takeLast(5)}")
            }
        }
        assertEquals(
            emptyList(),
            failed.map { "${it.language}:${it.repo} (${it.taskId}) -> ${it.tailLog.takeLast(3)}" },
            "Every daily trending Swift, Kotlin, and Python repository must import and build headlessly",
        )
    }

    @OptIn(ExperimentalPathApi::class)
    private suspend fun cloneImportAndBuildRepo(
        language: String,
        index: Int,
        total: Int,
        item: RawRepoItem,
        cacheRoot: Path,
        sharedCaches: Path,
        gitProvider: GitProvider,
    ): RepoBuildResult {
        val repoSlug = "${item.owner}/${item.name}"
        val repoDir = cacheRoot.resolve("${language}__${item.owner}__${item.name}")
        val tClone0 = System.currentTimeMillis()
        if (!Files.isDirectory(repoDir.resolve(".git"))) {
            runCatching { repoDir.deleteRecursively() }
            gitProvider.clone(
                url = "https://github.com/$repoSlug.git",
                target = repoDir,
                branch = null,
                depth = 1,
                auth = null,
            ).close()
        } else {
            // Clean any prior .platform metadata, workspace model, or synthetic scaffolding before re-importing
            runCatching { repoDir.resolve(".platform").deleteRecursively() }
            runCatching { Files.deleteIfExists(repoDir.resolve("workspace.json")) }
            runCatching { Files.deleteIfExists(repoDir.resolve("repositories.txt")) }
            runCatching {
                Files.walk(repoDir, 4).use { stream ->
                    stream.filter { Files.isRegularFile(it) && it.fileName.toString() == "module.toml" }
                        .toList()
                        .forEach { Files.deleteIfExists(it) }
                }
            }
            val pkgSwift = repoDir.resolve("Package.swift")
            if (Files.isRegularFile(pkgSwift)) {
                val text = runCatching { Files.readString(pkgSwift) }.getOrDefault("").trimStart()
                if (text.startsWith("// swift-tools-version: 6.0") &&
                    (text.contains("exclude: [") || text.contains("path: \".\"") || text.contains("Python") || text.contains("Swift"))
                ) {
                    Files.deleteIfExists(pkgSwift)
                }
            }
            val xtoolYml = repoDir.resolve("xtool.yml")
            if (Files.isRegularFile(xtoolYml)) {
                val text = runCatching { Files.readString(xtoolYml) }.getOrDefault("")
                if (text.contains("bundleID: dev.building.")) {
                    Files.deleteIfExists(xtoolYml)
                }
            }
        }
        val cloneMs = System.currentTimeMillis() - tClone0

        val imported = HeadlessEngine.importProject(repoDir)
        if (!imported) {
            return RepoBuildResult(
                language = language,
                repo = repoSlug,
                cloneMs = cloneMs,
                buildMs = 0L,
                buildSystemId = "unrecognized",
                taskId = "none",
                succeeded = false,
                tailLog = listOf("HeadlessEngine.importProject returned false"),
            )
        }

        val tBuild0 = System.currentTimeMillis()
        return HeadlessEngine.open(repoDir, cachesRoot = sharedCaches).use { engine ->
            val bsId = engine.buildSystemId()
            val tasks = engine.tasks()
            val candidateTasks = selectCandidateTasks(language, engine)
            if (candidateTasks.isEmpty()) {
                return@use RepoBuildResult(
                    language = language,
                    repo = repoSlug,
                    cloneMs = cloneMs,
                    buildMs = System.currentTimeMillis() - tBuild0,
                    buildSystemId = bsId,
                    taskId = "none",
                    succeeded = false,
                    tailLog = listOf("No build tasks available (tasks=${tasks.map { it.id }})"),
                )
            }

            var lastSummary: RepoBuildResult? = null
            val maxCandidates = if (language == "kotlin") 2 else 1
            for (task in candidateTasks.take(maxCandidates)) {
                if (language == "kotlin") {
                    engine.resolveDependencies(timeoutMs = 180_000L, taskId = task.id)
                }
                val result = engine.build(
                    taskId = task.id,
                    timeoutMs = 180_000L,
                )
                val diagnosticLines = result.log.filter {
                    it.message.contains("ksp", ignoreCase = true) ||
                        it.message.contains("error", ignoreCase = true) ||
                        it.message.contains("FAILED")
                }.take(25)
                val summary = RepoBuildResult(
                    language = language,
                    repo = repoSlug,
                    cloneMs = cloneMs,
                    buildMs = System.currentTimeMillis() - tBuild0,
                    buildSystemId = bsId,
                    taskId = task.id,
                    succeeded = result.succeeded,
                    tailLog = (diagnosticLines + result.log.takeLast(12)).map { it.message }.distinct(),
                )
                lastSummary = summary
                if (summary.succeeded) return@use summary
            }
            lastSummary!!
        }
    }

    private fun selectCandidateTasks(
        language: String,
        engine: HeadlessEngine,
    ) = when (language) {
        "swift" -> {
            val tasks = engine.tasks()
            listOfNotNull(
                tasks.firstOrNull { it.id == "swift:build" },
                tasks.firstOrNull { it.id == "swift:run" },
                tasks.firstOrNull(),
            ).distinctBy { it.id }
        }
        "python" -> {
            val tasks = engine.tasks()
            listOfNotNull(
                tasks.firstOrNull { it.id == "python:build" },
                tasks.firstOrNull { it.id.startsWith("python:run:") },
                tasks.firstOrNull(),
            ).distinctBy { it.id }
        }
        "kotlin" -> {
            val tasks = engine.tasks()
            val modulesByName = engine.modules().associateBy { m -> m.name }
            fun depCount(modName: String): Int = modulesByName[modName]?.dependencies?.size ?: 999
            val javaTasks = tasks.filter { it.id.startsWith("build:") }
                .sortedBy { depCount(it.id.removePrefix("build:")) }
            val aarTasks = tasks.filter { it.id.startsWith("assembleAar:") && it.id.endsWith("Debug", ignoreCase = true) }
                .sortedBy { depCount(it.id.removePrefix("assembleAar:").substringBefore(':')) }
            val apkTasks = tasks.filter { it.id.startsWith("assemble:") && it.id.endsWith("Debug", ignoreCase = true) }
                .sortedBy { depCount(it.id.removePrefix("assemble:").substringBefore(':')) }
            (javaTasks + aarTasks + apkTasks + tasks).distinctBy { it.id }
        }
        else -> engine.tasks()
    }
}
