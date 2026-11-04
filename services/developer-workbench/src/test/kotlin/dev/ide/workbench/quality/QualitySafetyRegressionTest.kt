package dev.ide.workbench.quality

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QualitySafetyRegressionTest {
    @TempDir lateinit var temp: Path
    private fun raw(title: String, body: String = "") = ImportedRecord("sample/app#1", "sample/app", 1, RecordKind.ISSUE,
        RecordState.OPEN, MergeStatus.NOT_APPLICABLE, title, body, null, "https://github.com/sample/app/issues/1", emptyList(), false)

    @Test fun `redact full context before evidence cropping to avoid orphaned secret suffix`() {
        val secret = "SYNTHETIC_LONG_PASSWORD_0123456789abcdefghijklmnopqrstuvwxyz"
        val record = IssueClassifier.classify(raw("Bug report", "password=$secret\nBackup restore fails"))
        assertEquals(PrimaryTheme.DATA_STATE, record.theme)
        assertEquals(EvidenceSource.BODY, record.evidence.source)
        assertTrue(record.evidence.redacted)
        assertFalse(record.evidence.snippet.contains("23456789abcdefghijklmnopqrstuvwxyz"))
        assertFalse(record.body.contains(secret))
        assertTrue(record.evidence.snippet.contains("Backup restore fails"))
    }

    @Test fun `provider credential families scrub in full reports and both exports`() {
        val credentials = listOf("glpat-abcdefghijklmnopqrst", "ASIA0123456789ABCDEF", "AKIA0123456789ABCDEF", "AIzaSYNTHETIC1234567890abcdefghijklmnop",
            "ya29.synthetic-access-token", "xoxb-" + "synthetic".repeat(3), "ghp_shortsynthetic", "github_pat_synthetic", "sk-syntheticabcdefghijklmnop")
        val text = credentials.joinToString("\n")
        val report = QualityReport(ReportKind.LOGS, "Manual report", Instant.EPOCH, warnings = listOf(text))
        for (extension in listOf("md", "json")) {
            val result = QualityService().export(report, temp.resolve("redaction.$extension"))
            val content = Files.readString(result.path)
            credentials.forEach { assertFalse(content.contains(it), "Export retained a provider token") }
        }
    }

    @Test @Timeout(8) fun `redaction remains bounded for long non-email runs and many at signs`() {
        val plain = "a".repeat(100_000)
        assertEquals(plain, QualityRedactor.redact(plain).text)
        val many = "abc@def ".repeat(12_500)
        assertEquals(many, QualityRedactor.redact(many).text)
        val email = QualityRedactor.redact("prefix " + "a".repeat(100_000) + "@example.com suffix")
        assertEquals("prefix [REDACTED_EMAIL] suffix", email.text)
        assertTrue(email.changed)
        assertEquals("Contact [REDACTED_EMAIL].", QualityRedactor.redact("Contact hello@example.com.").text)
    }

    @Test fun `HTML boilerplate instructions never supply a false problem theme`() {
        val result = IssueClassifier.classify(raw("Bug report", """
            <!--
            Explain the crash, background service stops, data loss, network timeout, and kernel profile here.
            -->
            No response
        """.trimIndent()))
        // Sensitivity may conservatively withhold template text; semantic evidence must still abstain.
        val ordinary = IssueClassifier.classify(raw("Bug report", "<!--\nPlease describe the crash and background service stops.\n-->\nNo response"))
        assertEquals(PrimaryTheme.UNCLASSIFIED, ordinary.theme)
        assertEquals(Confidence.LOW, ordinary.confidence)
        assertFalse(result.body.contains("kernel profile"))
    }

    @Test fun `specialized tools and bilingual profile context filter but ordinary view roots do not`() {
        for (title in listOf("LSPosed fails on this phone", "Kernel profile not supported", "内核配置无法适配", "Magisk module request")) {
            val result = IssueClassifier.classify(raw(title))
            assertTrue(result.deviceSecuritySpecific, title)
            assertEquals("[Withheld]", result.body)
        }
        assertFalse(IssueClassifier.classify(raw("Root view layout overlap")).deviceSecuritySpecific)
        assertFalse(IssueClassifier.classify(raw("Profile picture layout is clipped")).deviceSecuritySpecific)
        val fenced = IssueClassifier.classify(raw("App problem", "```text\nLSPosed module configuration\n```"))
        assertTrue(fenced.deviceSecuritySpecific)
    }

    @Test fun `explicit repository sensitivity covers terse records without hardcoded identities`() {
        val path = temp.resolve("repo.json")
        Files.writeString(path, """{"repos":{"Chosen/Specialized":{"issues":[{"number":1,"title":"Not working"}]}}}""")
        val report = QualityService(QualityConfig(archive = ArchiveImportConfig(deviceSecurityRepositories = setOf("Chosen/Specialized")))).importArchive(path)
        assertEquals(1, report.statistics!!.deviceSecuritySpecific.total)
        assertTrue(report.records.isEmpty())
        assertTrue(report.warnings.any { "may miss" in it })
        assertTrue(report.warnings.any { "explicitly configured" in it })
    }

    @Test fun `explicit period invalid end never falls back and date-only end includes whole day`() {
        fun source(end: String): Path = temp.resolve("period-${end.replace(':', '-')}.json").also { path ->
            Files.writeString(path, JsonObject(mapOf("generated" to JsonPrimitive("2026-10-12T12:00:00Z"),
                "period" to JsonObject(mapOf("start" to JsonPrimitive("2026-10-01"), "end" to JsonPrimitive(end))),
                "repos" to JsonObject(mapOf("sample/app" to JsonObject(mapOf("issues" to JsonArray(listOf(JsonObject(mapOf(
                    "number" to JsonPrimitive(1), "title" to JsonPrimitive("Backup restore fails"), "created_at" to JsonPrimitive("2026-10-12T20:00:00Z"))))))))))).toString())
        }
        val invalid = QualityService().importArchive(source("invalid"))
        assertEquals(null, invalid.statistics!!.recentWindow)
        assertTrue(invalid.warnings.any { "could not be interpreted" in it })
        val day = QualityService().importArchive(source("2026-10-12"))
        assertEquals(1, day.statistics!!.recent!!.total)
        assertEquals(Instant.parse("2026-10-12T23:59:59.999999999Z"), day.statistics!!.recentWindow!!.endInclusive)
    }

    @Test fun `default repository is fallback and nested REST PR merge evidence is honored`() {
        val path = temp.resolve("rest.json")
        Files.writeString(path, """[{"number":1,"title":"Fix backup restore","html_url":"https://github.com/other/repo/pull/1","pull_request":{"merged_at":"2026-10-01T12:00:00Z"}},{"number":2,"title":"Backup restore fails"}]""")
        val report = QualityService(QualityConfig(archive = ArchiveImportConfig(defaultRepository = "fallback/repo"))).importArchive(path)
        assertEquals(0, report.statistics!!.invalidRecordCount)
        assertEquals(setOf("other/repo", "fallback/repo"), report.records.map { it.repository }.toSet())
        assertEquals(MergeStatus.MERGED, report.records.single { it.repository == "other/repo" }.mergeStatus)
    }
}
