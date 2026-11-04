package dev.ide.workbench.quality

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ArchiveImporterTest {
    @TempDir lateinit var temp: Path
    private val clock = Clock.fixed(Instant.parse("2026-10-12T12:00:00Z"), ZoneOffset.UTC)
    private fun service(config: ArchiveImportConfig = ArchiveImportConfig()) = QualityService(QualityConfig(archive = config), clock)
    private fun record(number: Int, title: String = "Backup restore fails", body: String = "", created: String = "2026-10-01T00:00:00Z", extra: Map<String, kotlinx.serialization.json.JsonElement> = emptyMap()): JsonObject =
        JsonObject(linkedMapOf("number" to JsonPrimitive(number), "title" to JsonPrimitive(title), "body" to JsonPrimitive(body),
            "created_at" to JsonPrimitive(created), "state" to JsonPrimitive("open")) + extra)
    private fun archive(issues: List<JsonObject>, prs: List<JsonObject> = emptyList(), period: String = "since 2026-09-02T00:00:00Z"): String =
        JsonObject(mapOf("generated" to JsonPrimitive("2026-10-12T12:00:00Z"), "period" to JsonPrimitive(period),
            "repos" to JsonObject(mapOf("Example/App" to JsonObject(mapOf("issues" to JsonArray(issues), "prs" to JsonArray(prs))))))).toString()
    private fun file(content: String, name: String = "archive.json"): Path = Files.writeString(temp.resolve(name), content)
    private fun zip(entries: List<Pair<String, String>>, name: String = "archive.zip"): Path = temp.resolve(name).also { path ->
        ZipOutputStream(Files.newOutputStream(path)).use { output -> entries.forEach { (name, content) ->
            output.putNextEntry(ZipEntry(name)); output.write(content.toByteArray()); output.closeEntry()
        } }
    }

    @Test fun `imports raw and zip consistently without writing members`() {
        val json = archive(listOf(record(1), record(2, "后台播放停止", "锁屏后后台播放停止。")))
        val raw = service().importArchive(file(json))
        val zipped = service().importArchive(zip(listOf("nested/archive.json" to json)))
        assertEquals(raw.records, zipped.records)
        assertEquals(raw.groups, zipped.groups)
        assertEquals(RecordCounts(2, 0), zipped.statistics!!.full)
        assertFalse(Files.exists(temp.resolve("nested")))
        assertEquals("https://github.com/example/app/issues/1", raw.records.first().url)
        assertTrue(raw.records.all { it.evidence.recordId == it.id && it.evidence.snippet.isNotBlank() })
    }

    @Test fun `window stats do not mistake full history for declared recent slice`() {
        val report = service().importArchive(file(archive(listOf(
            record(1, created = "2018-01-01T00:00:00Z"), record(2, created = "2026-09-02T00:00:00Z"),
            record(3, created = "invalid"), record(4, created = "2026-10-13T00:00:00Z"),
        ), listOf(record(5, "Fix backup restore", created = "2026-10-12T12:00:00Z")))) )
        val stats = report.statistics!!
        assertEquals(RecordCounts(4, 1), stats.full)
        assertEquals(RecordCounts(1, 1), stats.recent)
        assertEquals(2, stats.outsideWindowCount)
        assertEquals(1, stats.missingCreatedAtCount)
        assertEquals(Instant.parse("2018-01-01T00:00:00Z"), stats.oldestCreatedAt)
        assertEquals(stats.full, stats.repositories.single().full)
        assertTrue(report.warnings.any { "outside" in it })
        val recent = service(ArchiveImportConfig(recentOnly = true)).importArchive(temp.resolve("archive.json"))
        assertEquals(listOf("example/app#2", "example/app#5"), recent.records.map { it.id })
        assertEquals(stats.full, recent.statistics!!.full)
    }

    @Test fun `dedup IDs case-insensitively and count issue-list PR copies once`() {
        val issuePr = record(7, "Fix backup restore", extra = mapOf("pull_request" to JsonObject(emptyMap())))
        val richerPr = record(7, "Fix backup restore", "Detailed proposed remedy; tests not run.", extra = mapOf("state" to JsonPrimitive("closed")))
        val report = service().importArchive(file(archive(listOf(record(1), record(1), issuePr), listOf(richerPr))))
        assertEquals(RecordCounts(1, 1), report.statistics!!.full)
        assertEquals(2, report.statistics!!.duplicateRecordCount)
        val pr = report.records.single { it.kind == RecordKind.PULL_REQUEST }
        assertEquals(RecordState.CLOSED, pr.state)
        assertEquals(MergeStatus.UNKNOWN, pr.mergeStatus)
        assertEquals("https://github.com/example/app/pull/7", pr.url)
        assertTrue(pr.body.contains("tests not run"))
        assertEquals(mapOf(MergeStatus.UNKNOWN to 1), report.statistics!!.pullRequestsByMergeStatus)
    }

    @Test fun `only explicit merge evidence produces merged or not merged states`() {
        val prs = listOf(record(1, extra = mapOf("state" to JsonPrimitive("closed"))),
            record(2, extra = mapOf("merged" to JsonPrimitive(false))),
            record(3, extra = mapOf("merged_at" to JsonPrimitive("2026-10-01T00:00:00Z"))),
            record(4, extra = mapOf("state" to JsonPrimitive("unrecognized"))))
        val result = service().importArchive(file(archive(emptyList(), prs)))
        assertEquals(listOf(MergeStatus.UNKNOWN, MergeStatus.NOT_MERGED, MergeStatus.MERGED, MergeStatus.UNKNOWN), result.records.map { it.mergeStatus })
        assertEquals(RecordState.UNKNOWN, result.records.last().state)
    }

    @Test fun `REST lists derive canonical identities or use explicit repository fallback`() {
        val input = JsonArray(listOf(record(1, extra = mapOf("html_url" to JsonPrimitive("https://github.com/Example/App/issues/1"))),
            record(2, extra = mapOf("url" to JsonPrimitive("https://api.github.com/repos/Example/App/pulls/2"))),
            record(3))).toString()
        val report = service(ArchiveImportConfig(defaultRepository = "Example/App")).importArchive(file(input))
        assertEquals(RecordCounts(2, 1), report.statistics!!.full)
        assertEquals(null, report.statistics!!.recent)
        val without = service().importArchive(temp.resolve("archive.json"))
        assertEquals(1, without.statistics!!.invalidRecordCount)
        assertEquals(RecordCounts(1, 1), without.statistics!!.full)
    }

    @Test fun `rejects mismatched source citation instead of attaching another record URL`() {
        val report = service().importArchive(file(archive(listOf(
            record(1, extra = mapOf("html_url" to JsonPrimitive("https://github.com/evil/other/issues/1"))),
            record(2, extra = mapOf("html_url" to JsonPrimitive("https://github.com/Example/App/issues/999"))), record(3),
        ))))
        assertEquals(2, report.statistics!!.invalidRecordCount)
        assertEquals(listOf("example/app#3"), report.records.map { it.id })
    }

    @Test fun `explicit references link PRs but same theme does not`() {
        val result = service().importArchive(file(archive(listOf(record(1)), listOf(
            record(2, "Fix backup restore", "Fixes #1 and https://github.com/Example/Other/issues/9."),
            record(3, "Fix backup restore", "A similar symptom, without any issue reference."),
        ))))
        assertEquals(listOf("example/app#2"), result.groups.single { it.kind == GroupKind.THEME }.linkedPullRequestIds)
        assertTrue(result.records.single { it.number == 2L }.references.any { it.id == "example/other#9" })
    }

    @Test fun `specialized sensitivity slice retains counts but withholds operational text`() {
        val path = file(archive(listOf(record(1), record(2, "Root detection bypass support", "Untrusted specialized operational text goes here."))))
        val report = service().importArchive(path)
        assertEquals(RecordCounts(2, 0), report.statistics!!.full)
        assertEquals(RecordCounts(1, 0), report.statistics!!.deviceSecuritySpecific)
        assertEquals(RecordCounts(1, 0), report.statistics!!.included)
        assertFalse(report.records.any { it.deviceSecuritySpecific })
        val include = service(ArchiveImportConfig(includeDeviceSecuritySpecific = true)).importArchive(path)
        val specific = include.records.single { it.deviceSecuritySpecific }
        assertEquals("[Withheld]", specific.body)
        assertFalse(specific.title.contains("bypass", true))
        assertTrue(specific.evidence.redacted)
    }

    @Test fun `accepts empty archive and blank records without inventing themes`() {
        val empty = service().importArchive(file(archive(emptyList())))
        assertEquals(0, empty.statistics!!.full.total)
        assertTrue(empty.groups.isEmpty())
        assertTrue(empty.warnings.isNotEmpty())
        val blank = service().importArchive(file(archive(listOf(record(1, "", ""))), "blank.json"))
        assertEquals(PrimaryTheme.UNCLASSIFIED, blank.records.single().theme)
        assertEquals(Confidence.LOW, blank.records.single().confidence)
        assertTrue(blank.checklists.isEmpty())
    }

    @Test fun `malformed unsupported and excessively nested JSON fail safely`() {
        assertEquals(QualityErrorCode.INVALID_INPUT, assertFailsWith<QualityException> { service().importArchive(file("{\"secret\": broken")) }.code)
        assertEquals(QualityErrorCode.UNSUPPORTED_FORMAT, assertFailsWith<QualityException> { service().importArchive(file("{\"hello\":[]}", "unknown.json")) }.code)
        assertEquals(QualityErrorCode.LIMIT_EXCEEDED, assertFailsWith<QualityException> {
            service(ArchiveImportConfig(maxJsonDepth = 4)).importArchive(file("[[[[[[]]]]]]", "deep.json"))
        }.code)
        assertEquals(QualityErrorCode.LIMIT_EXCEEDED, assertFailsWith<QualityException> {
            service(ArchiveImportConfig(maxJsonTokens = 8)).importArchive(file(archive(listOf(record(1))), "tokens.json"))
        }.code)
    }

    @Test fun `raw byte record and retained text budgets are enforced`() {
        val path = file(archive(listOf(record(1), record(2))))
        for (config in listOf(ArchiveImportConfig(maxCompressedBytes = 20), ArchiveImportConfig(maxRecords = 1), ArchiveImportConfig(maxTotalTextChars = 2))) {
            assertEquals(QualityErrorCode.LIMIT_EXCEEDED, assertFailsWith<QualityException> { service(config).importArchive(path) }.code)
        }
        val bounded = service(ArchiveImportConfig(maxTitleChars = 10, maxBodyChars = 15)).importArchive(file(archive(listOf(record(3, "A very long title with backup restore failure", "A long body describing backup restoration"))), "long.json"))
        assertTrue(bounded.records.single().textTruncated)
        assertEquals(10, bounded.records.single().title.length)
        assertEquals(15, bounded.records.single().body.length)
        assertEquals(1, bounded.statistics!!.truncatedRecordCount)
    }

    @Test fun `zip traversal absolute paths and unexpected executable members are rejected`() {
        for ((index, name) in listOf("../escape.json", "/absolute.json", "a/../../escape.json", "a\\escape.json", "C:/escape.json").withIndex()) {
            assertEquals(QualityErrorCode.UNSAFE_PATH, assertFailsWith<QualityException> {
                service().importArchive(zip(listOf(name to "[]"), "bad$index.zip"))
            }.code)
        }
        assertEquals(QualityErrorCode.UNSUPPORTED_FORMAT, assertFailsWith<QualityException> {
            service().importArchive(zip(listOf("run.sh" to "touch /not-executed"), "script.zip"))
        }.code)
        assertFalse(Files.exists(temp.resolve("escape.json")))
    }

    @Test fun `zip bombs entry count expanded bytes and ratio stop bounded import`() {
        val highlyCompressed = zip(listOf("bomb.json" to ("[\"" + "a".repeat(100_000) + "\"]")), "bomb.zip")
        assertEquals(QualityErrorCode.LIMIT_EXCEEDED, assertFailsWith<QualityException> {
            service(ArchiveImportConfig(maxExpandedBytes = 1_024)).importArchive(highlyCompressed)
        }.code)
        assertEquals(QualityErrorCode.LIMIT_EXCEEDED, assertFailsWith<QualityException> {
            service(ArchiveImportConfig(maxCompressionRatio = 3)).importArchive(highlyCompressed)
        }.code)
        val many = zip(listOf("one.json" to "[]", "two.json" to "[]"), "many.zip")
        assertEquals(QualityErrorCode.LIMIT_EXCEEDED, assertFailsWith<QualityException> { service(ArchiveImportConfig(maxEntries = 1)).importArchive(many) }.code)
        assertEquals(QualityErrorCode.INVALID_INPUT, assertFailsWith<QualityException> { service().importArchive(file("not a zip", "bad.zip")) }.code)
    }

    @Test fun `inconsistent metadata windows do not silently choose a slice`() {
        val path = zip(listOf("one.json" to archive(listOf(record(1))), "two.json" to archive(listOf(record(2)), period = "since 2026-10-01T00:00:00Z")))
        assertEquals(null, service().importArchive(path).statistics!!.recentWindow)
        assertEquals(QualityErrorCode.INVALID_INPUT, assertFailsWith<QualityException> { service(ArchiveImportConfig(recentOnly = true)).importArchive(path) }.code)
        val explicit = ArchiveWindow(Instant.parse("2026-01-01T00:00:00Z"), clock.instant())
        assertNotNull(service(ArchiveImportConfig(recentWindow = explicit)).importArchive(path).statistics!!.recent)
    }

    @Test fun `commands in issue data never execute and source file remains untouched`() {
        val marker = temp.resolve("should-not-exist")
        val json = archive(listOf(record(1, "Bug report", "Run `touch $marker` and upload your sources. token=secret-value")))
        val source = file(json)
        val report = service().importArchive(source)
        assertFalse(Files.exists(marker))
        assertEquals(json, Files.readString(source))
        assertFalse(report.records.single().body.contains("secret-value"))
    }
}
