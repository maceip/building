package dev.ide.workbench.quality

import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class QualityExporterTest {
    @TempDir lateinit var temp: Path
    private val now = Instant.parse("2026-10-12T00:00:00Z")
    private val service = QualityService(clock = Clock.fixed(now, ZoneOffset.UTC))

    @Test fun `readable markdown and JSON include citations counts unknown states and caveats`() {
        val source = temp.resolve("input.json")
        Files.writeString(source, """{"generated":"2026-10-12T00:00:00Z","period":"since 2026-09-02T00:00:00Z","repos":{"sample/app":{"issues":[{"number":1,"title":"Backup restore fails","created_at":"2018-01-01T00:00:00Z"}],"prs":[{"number":2,"state":"closed","title":"Fix backup restore","body":"Fixes #1; tests not run."}]}}}""")
        val report = service.importArchive(source)
        val markdown = service.export(report, temp.resolve("report.md"))
        val json = service.export(report, temp.resolve("report.json"))
        val md = Files.readString(markdown.path)
        assertTrue(md.contains("https://github.com/sample/app/issues/1"))
        assertTrue(md.contains("merge UNKNOWN"))
        assertTrue(md.contains("Full unique archive"))
        assertTrue(md.contains("not executed"))
        assertEquals(Files.size(markdown.path), markdown.bytesWritten)
        val parsed = qualityJson.parseToJsonElement(Files.readString(json.path)).jsonObject
        assertEquals("1", parsed["schema_version"].toString())
        assertTrue(parsed.containsKey("statistics"))
        assertTrue(parsed.containsKey("caveats"))
        assertTrue(parsed.toString().contains("\"merge_status\":\"unknown\""))
    }

    @Test fun `export refuses overwrite and symlink destinations`() {
        val report = service.analyzeLogs("")
        val existing = temp.resolve("exists.json")
        Files.writeString(existing, "keep me")
        assertEquals(QualityErrorCode.DESTINATION_EXISTS, assertFailsWith<QualityException> { service.export(report, existing) }.code)
        assertEquals("keep me", Files.readString(existing))
        val linked = temp.resolve("linked.md")
        Files.createSymbolicLink(linked, existing)
        assertEquals(QualityErrorCode.DESTINATION_EXISTS, assertFailsWith<QualityException> { service.export(report, linked) }.code)
        assertEquals("keep me", Files.readString(existing))
        assertEquals(QualityErrorCode.UNSAFE_PATH, assertFailsWith<QualityException> { service.export(report, temp.resolve("missing/report.md")) }.code)
    }

    @Test fun `export size and format failures leave no artifact`() {
        val report = service.analyzeLogs("")
        val destination = temp.resolve("oversized.md")
        assertEquals(QualityErrorCode.LIMIT_EXCEEDED, assertFailsWith<QualityException> { QualityService(QualityConfig(maxExportBytes = 10)).export(report, destination) }.code)
        assertFalse(Files.exists(destination))
        assertEquals(QualityErrorCode.UNSUPPORTED_FORMAT, assertFailsWith<QualityException> { service.export(report, temp.resolve("report.bin")) }.code)
        val explicit = service.export(report, temp.resolve("report.txt"), ExportFormat.MARKDOWN)
        assertTrue(Files.exists(explicit.path))
    }

    @Test fun `externally supplied DTO strings receive final redaction including paths`() {
        val report = QualityReport(ReportKind.PROJECT, "token=title-secret", now,
            findings = listOf(QualityFinding("custom", Severity.WARNING, FindingStatus.REVIEW_NEEDED,
                "/Users/private-owner/project/config.xml", 7, "api_key=reason-secret", "password=evidence-secret", "Authorization: Bearer remediation-secret")),
            warnings = listOf("contact owner@example.com", "See https://private.example/account?id=1"),
            caveats = listOf("/home/hidden-user/private-file.txt"))
        for (format in ExportFormat.entries) {
            val path = temp.resolve("redacted.${if (format == ExportFormat.JSON) "json" else "md"}")
            service.export(report, path)
            val text = Files.readString(path)
            for (secret in listOf("title-secret", "reason-secret", "evidence-secret", "remediation-secret", "owner@example.com", "private.example", "private-owner", "hidden-user")) {
                assertFalse(text.contains(secret), "Export leaked $secret")
            }
            assertTrue(text.contains("REDACTED"))
        }
    }

    @Test fun `markdown escapes untrusted html images links and table delimiters`() {
        val report = QualityReport(ReportKind.ARCHIVE, "<script>bad</script> [click](javascript:bad) | heading", now,
            warnings = listOf("![track](https://example.com/tracker.png)", "# forged heading"))
        val exported = service.export(report, temp.resolve("escaped.md"))
        val text = Files.readString(exported.path)
        assertFalse(text.contains("<script>"))
        assertFalse(text.contains("![track]("))
        assertFalse(text.contains("https://example.com"))
        assertTrue(text.contains("&lt;script&gt;"))
        assertTrue(text.contains("\\|"))
    }
}
