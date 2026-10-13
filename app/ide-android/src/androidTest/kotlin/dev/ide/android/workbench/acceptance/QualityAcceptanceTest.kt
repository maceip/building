package dev.ide.android.workbench.acceptance

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ide.workbench.quality.ArchiveImportConfig
import dev.ide.workbench.quality.ExportFormat
import dev.ide.workbench.quality.FindingStatus
import dev.ide.workbench.quality.GroupKind
import dev.ide.workbench.quality.MergeStatus
import dev.ide.workbench.quality.QualityConfig
import dev.ide.workbench.quality.QualityErrorCode
import dev.ide.workbench.quality.QualityException
import dev.ide.workbench.quality.QualityReport
import dev.ide.workbench.quality.QualityService
import dev.ide.workbench.quality.RecordCounts
import dev.ide.workbench.quality.RecordKind
import dev.ide.workbench.quality.RecordState
import dev.ide.workbench.quality.ReportKind
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the production offline facade, ZIP/SAX/crypto/file APIs on Android ART, not mocked DTOs. */
@RunWith(AndroidJUnit4::class)
class QualityAcceptanceTest {
    private val clock = Clock.fixed(Instant.parse("2026-10-12T12:00:00Z"), ZoneOffset.UTC)
    private fun service(config: ArchiveImportConfig = ArchiveImportConfig()) = QualityService(QualityConfig(archive = config), clock)

    @Test(timeout = 45_000)
    fun archiveSeparatesFullRecentSpecializedAndPullRequestCountsWithoutExecutingData() {
        AcceptanceWorkspace("quality-archive").use { workspace ->
            val archive = syntheticArchive(workspace)
            val originalHash = hash(archive)
            val quality = service()
            val report = quality.importArchive(archive)
            val stats = requireNotNull(report.statistics)
            assertEquals(ReportKind.ARCHIVE, report.kind)
            assertEquals(9, stats.inputRecordCount)
            assertEquals(0, stats.invalidRecordCount)
            assertEquals(1, stats.duplicateRecordCount)
            assertEquals(RecordCounts(5, 3), stats.full)
            assertEquals(RecordCounts(3, 2), stats.recent)
            assertEquals(RecordCounts(4, 3), stats.included)
            assertEquals(RecordCounts(1, 0), stats.deviceSecuritySpecific)
            assertEquals(RecordCounts(4, 3), stats.withoutDeviceSecuritySpecific)
            assertEquals(2, stats.outsideWindowCount)
            assertEquals(1, stats.missingCreatedAtCount)
            assertEquals(stats.full, stats.repositories.single().full)
            assertFalse(report.records.any { it.deviceSecuritySpecific })
            assertEquals(7, report.records.size)
            val closedPr = report.records.single { it.number == 7L }
            assertEquals(RecordKind.PULL_REQUEST, closedPr.kind)
            assertEquals(RecordState.CLOSED, closedPr.state)
            assertEquals("Closed is not proof of merged/tested/fixed", MergeStatus.UNKNOWN, closedPr.mergeStatus)
            assertEquals(MergeStatus.NOT_MERGED, report.records.single { it.number == 8L }.mergeStatus)
            assertEquals(MergeStatus.MERGED, report.records.single { it.number == 9L }.mergeStatus)
            assertEquals(3, stats.pullRequestsByMergeStatus.values.sum())
            assertTrue(report.groups.any { it.kind == GroupKind.STACK_FINGERPRINT && it.recordIds.containsAll(listOf("example/app#2", "example/app#4")) })
            val themes = report.groups.filter { it.kind == GroupKind.THEME }
            assertEquals(stats.included.issues, themes.sumOf { it.issueCount })
            assertEquals(stats.included.pullRequests, themes.sumOf { it.pullRequestCount })
            assertTrue(themes.any { "example/app#1" in it.recordIds && "example/app#7" in it.linkedPullRequestIds })
            assertTrue(report.warnings.isNotEmpty())
            assertTrue(report.caveats.any { it.contains("heuristic", ignoreCase = true) })
            assertTrue(report.checklists.all { it.status.contains("not executed", ignoreCase = true) })
            assertSecretFree(report.toString())

            val repeated = quality.importArchive(archive)
            assertTrue("Grouping must be deterministic, not treated as ground-truth labels", report.groups == repeated.groups)
            assertTrue("Record identity and triage must be deterministic", report.records == repeated.records)
            val recent = service(ArchiveImportConfig(recentOnly = true)).importArchive(archive)
            assertEquals(stats.full, recent.statistics!!.full)
            assertEquals(RecordCounts(2, 2), recent.statistics!!.included)
            assertEquals(setOf(2L, 4L, 7L, 9L), recent.records.map { it.number }.toSet())
            val includingSpecialized = service(ArchiveImportConfig(includeDeviceSecuritySpecific = true)).importArchive(archive)
            assertEquals(stats.full, includingSpecialized.statistics!!.included)
            val specialized = includingSpecialized.records.single { it.deviceSecuritySpecific }
            assertEquals("[Withheld]", specialized.body)
            assertTrue(specialized.evidence.redacted)
            assertSecretFree(includingSpecialized.toString())

            val exported = quality.export(report, workspace.resolve("archive-report.json"))
            val reread = Files.readAllBytes(exported.path).toString(Charsets.UTF_8)
            assertEquals(Files.size(exported.path), exported.bytesWritten)
            assertEquals(ExportFormat.JSON, exported.format)
            assertEquals(8, JSONObject(reread).getJSONObject("statistics").getJSONObject("full").getInt("total"))
            assertSecretFree(reread)
            assertEquals(originalHash, hash(archive))
            assertFalse("Import must not extract ZIP members", Files.exists(workspace.resolve("nested")))
            assertFalse("Issue-body commands are data, not executable instructions", Files.exists(workspace.resolve("must-not-exist")))
        }
    }

    @Test(timeout = 30_000)
    fun logFingerprintsNormalizeVariableDetailsAndExportsDoNotRetainOriginalSecrets() {
        AcceptanceWorkspace("quality-logs").use { workspace ->
            val logs = """
                java.lang.IllegalStateException: token=$TOKEN at first request 172.20.0.1
                    at dev.example.Store.restore(Store.kt:17)
                    at dev.example.Main.onCreate(Main.kt:23)

                java.lang.IllegalStateException: Authorization: Bearer $AUTH_SECRET
                    at dev.example.Store.restore(Store.kt:191)
                    at dev.example.Main.onCreate(Main.kt:299)
            """.trimIndent()
            val quality = service()
            val report = quality.analyzeLogs(logs)
            val summary = requireNotNull(report.logSummary)
            assertEquals(ReportKind.LOGS, report.kind)
            assertEquals(2, summary.events.size)
            assertTrue(summary.redacted)
            assertTrue(summary.events.all { it.redacted })
            assertEquals(summary.events.first().fingerprint, summary.events.last().fingerprint)
            val group = report.groups.single()
            assertEquals(GroupKind.STACK_FINGERPRINT, group.kind)
            assertEquals(2, group.occurrenceCount)
            assertEquals(0, group.issueCount)
            assertEquals(0, group.pullRequestCount)
            assertTrue(group.evidence.all { it.line != null && it.redacted })
            assertSecretFree(report.toString())
            assertTrue(report.checklists.all { it.status.contains("not executed", ignoreCase = true) })
            val changed = quality.analyzeLogs(logs.replace("Store.restore", "Store.delete"))
            assertFalse("Distinct stack methods must not share a fingerprint", group.fingerprint == changed.groups.single().fingerprint)

            for ((name, format) in listOf("log-report.json" to ExportFormat.JSON, "log-report.md" to ExportFormat.MARKDOWN)) {
                val result = quality.export(report, workspace.resolve(name), format)
                val bytes = Files.readAllBytes(result.path)
                val text = bytes.toString(Charsets.UTF_8)
                assertEquals(bytes.size.toLong(), result.bytesWritten)
                assertSecretFree(text)
                assertTrue("Redacted evidence should remain inspectable", text.contains("REDACTED"))
                if (format == ExportFormat.JSON) {
                    val json = JSONObject(text)
                    assertTrue(json.getJSONObject("log_summary").getBoolean("redacted"))
                    assertEquals(2, json.getJSONArray("groups").getJSONObject(0).getInt("occurrence_count"))
                }
                assertEquals(QualityErrorCode.DESTINATION_EXISTS, qualityFailure { quality.export(report, result.path, format) }.code)
                assertTrue("Existing report export must never be overwritten", bytes.contentEquals(Files.readAllBytes(result.path)))
            }
        }
    }

    @Test(timeout = 30_000)
    fun manifestPreflightUsesAndroidSaxAndLeavesEveryProjectFileUntouched() {
        AcceptanceWorkspace("quality-scan").use { workspace ->
            val manifest = workspace.write("project/app/src/main/AndroidManifest.xml", """
                <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="dev.example.quality">
                    <uses-permission android:name="android.permission.READ_CONTACTS" />
                    <application android:label="token=$TOKEN" android:debuggable="true" android:usesCleartextTraffic="true" android:allowBackup="true">
                        <service android:name=".ExportedService" android:exported="true" />
                    </application>
                </manifest>
            """.trimIndent())
            workspace.write("project/app/build.gradle.kts", """
                plugins { id("com.android.application") }
                dependencies { implementation("example:dependency:1.+") }
                android { buildTypes { release { isDebuggable = true } } }
            """.trimIndent())
            workspace.write("project/package.json", JSONObject().put("name", "offline-quality-fixture")
                .put("scripts", JSONObject().put("test", "touch '${workspace.resolve("must-not-exist")}'"))
                .put("dependencies", JSONObject().put("fixture-only-not-installed", "^1.0.0")).toString())
            workspace.write("project/build/AndroidManifest.xml", "<this-file-must-not-be-parsed />")
            val outside = workspace.write("outside-AndroidManifest.xml", "<not-a-project-file token=\"$TOKEN\" />")
            Files.createSymbolicLink(workspace.resolve("project/linked-AndroidManifest.xml"), outside)
            val project = workspace.resolve("project")
            val before = snapshot(project)
            val quality = service()
            val report = quality.scanProject(project)
            val summary = requireNotNull(report.scanSummary)
            assertEquals(ReportKind.PROJECT, report.kind)
            assertEquals(1, summary.skippedSymlinks)
            assertEquals(3, summary.inspectedFiles)
            assertFalse("Valid manifest must be parsed on the Android SAX provider", summary.partial)
            assertTrue(summary.excludedDirectories.contains("build"))
            assertEquals(before, snapshot(project))
            assertFalse(Files.exists(workspace.resolve("must-not-exist")))
            val rules = report.findings.map { it.ruleId }.toSet()
            assertTrue("Manifest observations missing", rules.containsAll(setOf("MANIFEST_DEBUGGABLE", "MANIFEST_CLEARTEXT", "MANIFEST_BACKUP_POLICY", "EXPORTED_COMPONENT")))
            assertTrue("Build declarations must be scanned, not executed", rules.containsAll(setOf("DYNAMIC_DEPENDENCY", "GRADLE_RELEASE_DEBUGGABLE", "PACKAGE_TEST_SCRIPT")))
            assertTrue(report.findings.any { it.ruleId == "MANIFEST_DEBUGGABLE" && it.status == FindingStatus.REVIEW_NEEDED })
            assertTrue(report.findings.filter { it.ruleId.startsWith("MANIFEST_") }.all { it.file == manifest.toRealPath().toString() && it.line >= 1 })
            assertFalse(report.findings.any { it.file.contains("/build/") || it.file.contains("linked-AndroidManifest") })
            assertSecretFree(report.toString())
            val result = quality.export(report, workspace.resolve("scan-report.json"))
            val text = Files.readAllBytes(result.path).toString(Charsets.UTF_8)
            assertSecretFree(text)
            assertEquals(report.findings.size, JSONObject(text).getJSONArray("findings").length())
            assertEquals("Only export destination may change", before, snapshot(project))
        }
    }

    @Test(timeout = 30_000)
    fun unsafeZipMembersAreRejectedWithoutExtractionOrSourceMutation() {
        AcceptanceWorkspace("quality-unsafe").use { workspace ->
            Files.createDirectories(workspace.resolve("input"))
            val archive = zip(workspace.resolve("input/unsafe.zip"), "../escape.json", "[]")
            val before = hash(archive)
            val failure = qualityFailure { service().importArchive(archive) }
            // Android 14+ may reject traversal in ZipInputStream.nextEntry before our importer sees
            // the member name; that safe platform refusal is mapped to INVALID_INPUT. Older ART
            // reaches the importer's explicit UNSAFE_PATH check. Neither path may extract anything.
            assertTrue("Unsafe ZIP must be refused by either the platform or the importer",
                failure.code == QualityErrorCode.UNSAFE_PATH || failure.code == QualityErrorCode.INVALID_INPUT)
            assertEquals(before, hash(archive))
            assertFalse(Files.exists(workspace.resolve("escape.json")))
            assertEquals(setOf("unsafe.zip"), Files.newDirectoryStream(archive.parent).use { entries ->
                entries.map { it.fileName.toString() }.toSet()
            })
        }
    }

    /** Opt-in only: main may stage the original user ZIP in app-private cache and supply its path. */
    @Test(timeout = 120_000)
    fun originalUserArchiveWhenExplicitlyProvidedRetains663RecordAccounting() {
        val supplied = InstrumentationRegistry.getArguments().getString("workbenchAcceptanceArchivePath")
        assumeTrue("Original user archive was not supplied; this optional integration is not an acceptance pass", !supplied.isNullOrBlank())
        val source = java.io.File(requireNotNull(supplied)).toPath()
        assertTrue("Archive argument must identify a readable regular file", Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS) && Files.isReadable(source))
        val before = hash(source)
        val quality = service()
        val report = quality.importArchive(source)
        val repeated = quality.importArchive(source)
        val stats = requireNotNull(report.statistics)
        assertEquals(663, stats.full.total)
        assertEquals(625, stats.full.issues)
        assertEquals(38, stats.full.pullRequests)
        assertEquals(stats.full.total, stats.repositories.sumOf { it.full.total })
        assertEquals(stats.full.total, stats.withoutDeviceSecuritySpecific.total + stats.deviceSecuritySpecific.total)
        assertNotNull("Recent-window accounting should remain separate", stats.recent)
        assertTrue(stats.recent!!.total <= stats.full.total)
        assertEquals(stats.included.total, report.records.size)
        assertTrue(report.groups.isNotEmpty())
        assertTrue(report.warnings.isNotEmpty())
        assertTrue(report.caveats.isNotEmpty())
        assertTrue("Original archive groups must be deterministic; labels are not ground truth", report.groups == repeated.groups)
        assertTrue("Original archive record accounting must be deterministic", stats == repeated.statistics)
        assertTrue(report.checklists.all { it.status.contains("not executed", ignoreCase = true) })
        AcceptanceWorkspace("quality-original-export").use { workspace ->
            val exported = quality.export(report, workspace.resolve("archive-report.json"))
            val json = JSONObject(Files.readAllBytes(exported.path).toString(Charsets.UTF_8))
            assertEquals(663, json.getJSONObject("statistics").getJSONObject("full").getInt("total"))
        }
        assertEquals("The supplied original archive is read-only and is never deleted", before, hash(source))
    }

    private fun syntheticArchive(workspace: AcceptanceWorkspace): Path {
        val stack1 = "java.lang.IllegalStateException: token=$TOKEN\n    at dev.example.Store.restore(Store.kt:17)\n    at dev.example.Main.onCreate(Main.kt:23)"
        val stack2 = "java.lang.IllegalStateException: Authorization: Bearer $AUTH_SECRET\n    at dev.example.Store.restore(Store.kt:191)\n    at dev.example.Main.onCreate(Main.kt:299)"
        val issues = JSONArray()
            .put(record(1, "Backup restore fails", "api_key=$API_SECRET\nAn old report; do not run `touch ${workspace.resolve("must-not-exist")}`.", "2018-01-01T00:00:00Z"))
            .put(record(2, "Crash while restoring", stack1))
            .put(record(3, "Device profile support", SPECIALIZED_TEXT, "2026-10-02T00:00:00Z"))
            .put(record(4, "Crash while restoring", stack2, "2026-10-03T00:00:00Z"))
            .put(record(5, "Question", "password=$PASSWORD", "not-a-date"))
            .put(record(7, "Fix backup restore", "", "2026-10-04T00:00:00Z").put("pull_request", JSONObject()).put("state", "closed"))
        val prs = JSONArray()
            .put(record(7, "Fix backup restore", "Fixes #1. Proposed change only; tests were not run.", "2026-10-04T00:00:00Z").put("state", "closed"))
            .put(record(8, "chore: update dependencies", "Not merged.", "2018-01-02T00:00:00Z").put("state", "closed").put("merged", false))
            .put(record(9, "Fix UI layout overlap", "Explicit merge metadata.", "2026-10-05T00:00:00Z").put("state", "closed").put("merged_at", "2026-10-06T00:00:00Z"))
        val root = JSONObject().put("generated", "2026-10-12T12:00:00Z").put("period", "since 2026-09-02T00:00:00Z")
            .put("repos", JSONObject().put("Example/App", JSONObject().put("issues", issues).put("prs", prs)))
        return zip(workspace.resolve("synthetic-archive.zip"), "nested/archive.json", root.toString())
    }

    private fun record(number: Int, title: String, body: String, created: String = "2026-10-01T00:00:00Z") =
        JSONObject().put("number", number).put("title", title).put("body", body).put("created_at", created).put("state", "open")

    private fun zip(path: Path, member: String, content: String): Path = path.also {
        ZipOutputStream(Files.newOutputStream(it)).use { output ->
            output.putNextEntry(ZipEntry(member))
            output.write(content.toByteArray(Charsets.UTF_8))
            output.closeEntry()
        }
    }

    private fun qualityFailure(action: () -> Unit): QualityException {
        try { action() } catch (error: QualityException) { return error }
        throw AssertionError("Expected bounded quality-service input rejection")
    }

    private fun assertSecretFree(text: String) {
        for (secret in listOf(TOKEN, AUTH_SECRET, API_SECRET, PASSWORD, SPECIALIZED_TEXT)) {
            assertFalse("Quality report retained original synthetic confidential input", text.contains(secret))
        }
    }

    private fun hash(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(8_192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private fun snapshot(root: Path): Map<String, String> {
        val files = sortedMapOf<String, String>()
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                files[root.relativize(file).toString()] = if (attrs.isSymbolicLink) "link:${Files.readSymbolicLink(file)}"
                else "${attrs.size()}:${attrs.lastModifiedTime().toMillis()}:${hash(file)}"
                return FileVisitResult.CONTINUE
            }
        })
        return files
    }

    private companion object {
        const val TOKEN = "quality-acceptance-token-secret"
        const val AUTH_SECRET = "quality-acceptance-bearer-secret"
        const val API_SECRET = "quality-acceptance-api-secret"
        const val PASSWORD = "quality-acceptance-password"
        const val SPECIALIZED_TEXT = "Synthetic specialized text must never appear in a report"
    }
}
