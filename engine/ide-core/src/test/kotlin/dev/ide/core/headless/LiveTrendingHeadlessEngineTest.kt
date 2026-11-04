package dev.ide.core.headless

import dev.ide.core.discovery.GitHubDiscoveryClient
import dev.ide.core.discovery.GitHubDiscoveryClient.RawRepoItem
import dev.ide.vcs.VcsProgress
import dev.ide.vcs.impl.GitProvider
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.test.assertTrue

class LiveTrendingHeadlessEngineTest {

    @OptIn(ExperimentalPathApi::class)
    @Test
    fun cloneAndBuildLiveTrendingSwiftKotlinAndPythonHeadlessly() = runBlocking {
        val workRoot = Files.createTempDirectory("headless-trending-e2e")
        try {
            val discovery = GitHubDiscoveryClient(cacheDir = workRoot.resolve("discovery-cache"))
            val swiftTrending = discovery.fetchLiveTrendingRepositories(language = "swift", period = "daily", limit = 10)
            val kotlinTrending = discovery.fetchLiveTrendingRepositories(language = "kotlin", period = "daily", limit = 10)
            val pythonTrending = discovery.fetchLiveTrendingRepositories(language = "python", period = "daily", limit = 10)

            println("=== TODAY'S LIVE GITHUB TRENDING (DAILY) ===")
            println("Swift  (${swiftTrending.size}): ${swiftTrending.take(5).joinToString { "${it.owner}/${it.name} (★${it.stars})" }}")
            println("Kotlin (${kotlinTrending.size}): ${kotlinTrending.take(5).joinToString { "${it.owner}/${it.name} (★${it.stars})" }}")
            println("Python (${pythonTrending.size}): ${pythonTrending.take(5).joinToString { "${it.owner}/${it.name} (★${it.stars})" }}")

            assertTrue(swiftTrending.isNotEmpty(), "Expected live Swift trending repositories from GitHub")
            assertTrue(kotlinTrending.isNotEmpty(), "Expected live Kotlin trending repositories from GitHub")
            assertTrue(pythonTrending.isNotEmpty(), "Expected live Python trending repositories from GitHub")

            val cachesRoot = workRoot.resolve("shared-caches")
            val gitProvider = GitProvider(workRoot.resolve("git-config"))
            Files.createDirectories(cachesRoot)

            // 1. PYTHON from today's live trending
            val pythonResult = cloneAndBuildFirstBuildable(
                language = "python",
                candidates = pythonTrending,
                gitProvider = gitProvider,
                workRoot = workRoot,
                cachesRoot = cachesRoot,
            )
            println("=== PYTHON TRENDING RESULT: $pythonResult ===")
            assertTrue(pythonResult.succeeded, "Expected live trending Python repo to clone and build: $pythonResult")

            // 2. SWIFT from today's live trending
            val swiftResult = cloneAndBuildFirstBuildable(
                language = "swift",
                candidates = swiftTrending,
                gitProvider = gitProvider,
                workRoot = workRoot,
                cachesRoot = cachesRoot,
            )
            println("=== SWIFT TRENDING RESULT: $swiftResult ===")
            assertTrue(swiftResult.succeeded, "Expected live trending Swift repo to clone and build: $swiftResult")

            // 3. KOTLIN from today's live trending
            val kotlinResult = cloneAndBuildFirstBuildable(
                language = "kotlin",
                candidates = kotlinTrending,
                gitProvider = gitProvider,
                workRoot = workRoot,
                cachesRoot = cachesRoot,
            )
            println("=== KOTLIN TRENDING RESULT: $kotlinResult ===")
            assertTrue(kotlinResult.succeeded, "Expected live trending Kotlin repo to clone and build: $kotlinResult")
        } finally {
            runCatching { workRoot.deleteRecursively() }
        }
    }

    data class TrendingBuildSummary(
        val language: String,
        val repo: String,
        val cloneMs: Long,
        val buildMs: Long,
        val taskId: String,
        val succeeded: Boolean,
        val tailLog: List<String>,
    )

    private suspend fun cloneAndBuildFirstBuildable(
        language: String,
        candidates: List<RawRepoItem>,
        gitProvider: GitProvider,
        workRoot: Path,
        cachesRoot: Path,
    ): TrendingBuildSummary {
        var lastAttempt: TrendingBuildSummary? = null
        for (candidate in candidates.take(6)) {
            val repoSlug = "${candidate.owner}/${candidate.name}"
            val cloneDir = workRoot.resolve("${language}-${candidate.owner}-${candidate.name}")
            val t0 = System.currentTimeMillis()
            val cloned = runCatching {
                gitProvider.clone(
                    url = "https://github.com/$repoSlug.git",
                    target = cloneDir,
                    branch = null,
                    depth = 1,
                    auth = null,
                ).close()
                true
            }.getOrDefault(false)
            val cloneMs = System.currentTimeMillis() - t0
            if (!cloned) continue

            val imported = HeadlessEngine.importProject(cloneDir)
            println("[$language] Cloned $repoSlug in ${cloneMs}ms (imported=$imported)")
            if (!imported) continue

            val tBuild0 = System.currentTimeMillis()
            val summary = HeadlessEngine.open(cloneDir, cachesRoot = cachesRoot).use { engine ->
                val tasks = engine.tasks()
                println("[$language] $repoSlug tasks: ${tasks.map { "${it.id} (${it.label})" }}")
                val preferredTask = when (language) {
                    "swift" -> tasks.firstOrNull { it.id == "swift:build" }
                        ?: tasks.firstOrNull { it.id == "swift:run" }
                        ?: tasks.firstOrNull()
                    "python" -> tasks.firstOrNull { it.id.startsWith("python:run:") }
                        ?: tasks.firstOrNull()
                    "kotlin" -> tasks.firstOrNull { it.id.startsWith("build:") }
                        ?: tasks.firstOrNull { it.id.startsWith("assemble:") && it.id.endsWith(":debug") }
                        ?: tasks.firstOrNull()
                    else -> tasks.firstOrNull()
                } ?: return@use null

                if (language == "kotlin") {
                    val unresolved = engine.resolveDependencies(timeoutMs = 180_000L, taskId = preferredTask.id)
                    println("[$language] $repoSlug unresolved deps for ${preferredTask.id}: $unresolved")
                }

                val result = engine.build(
                    taskId = preferredTask.id,
                    timeoutMs = 180_000L,
                    onLog = { line -> println("[$language:$repoSlug] ${line.message}") },
                )
                TrendingBuildSummary(
                    language = language,
                    repo = repoSlug,
                    cloneMs = cloneMs,
                    buildMs = System.currentTimeMillis() - tBuild0,
                    taskId = preferredTask.id,
                    succeeded = result.succeeded,
                    tailLog = result.log.takeLast(10).map { it.message },
                )
            }
            if (summary != null) {
                lastAttempt = summary
                if (summary.succeeded) return summary
            }
        }
        return lastAttempt ?: TrendingBuildSummary(
            language = language,
            repo = "none",
            cloneMs = 0L,
            buildMs = 0L,
            taskId = "none",
            succeeded = false,
            tailLog = listOf("No candidate succeeded"),
        )
    }
}
