package dev.ide.workbench.quality

import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class LogAnalyzerTest {
    private val clock = Clock.fixed(Instant.parse("2026-10-12T00:00:00Z"), ZoneOffset.UTC)
    private fun service(config: LogAnalysisConfig = LogAnalysisConfig()) = QualityService(QualityConfig(logs = config), clock)

    @Test fun `stack fingerprints ignore line numbers messages and timestamps`() {
        val result = service().analyzeLogs("""
            10-12 12:00:00.001 123 456 E AndroidRuntime: java.lang.IllegalStateException: request=123
                at sample.Store.save(Store.kt:24)
                at sample.Main.run(Main.kt:100)

            10-12 13:00:00.333 999 222 E AndroidRuntime: java.lang.IllegalStateException: request=456
                at sample.Store.save(Store.kt:999)
                at sample.Main.run(Main.kt:321)
        """.trimIndent())
        assertEquals(2, result.logSummary!!.events.size)
        assertEquals(1, result.groups.size)
        assertEquals(2, result.groups.single().occurrenceCount)
        assertEquals(Confidence.HIGH, result.groups.single().confidence)
        assertEquals(GroupKind.STACK_FINGERPRINT, result.groups.single().kind)
        assertEquals(listOf("sample.Store.save", "sample.Main.run"), result.logSummary!!.events.first().frames)
        assertEquals(0, result.groups.single().issueCount)
    }

    @Test fun `different methods and exception types produce distinct signatures`() {
        val result = service().analyzeLogs("""
            java.lang.IllegalStateException: bad
                at sample.Store.save(Store.kt:1)

            java.lang.IllegalStateException: bad
                at sample.Store.load(Store.kt:1)

            java.io.IOException: bad
                at sample.Store.save(Store.kt:1)
        """.trimIndent())
        assertEquals(3, result.groups.size)
        assertEquals(3, result.logSummary!!.events.map { it.fingerprint }.distinct().size)
    }

    @Test fun `deepest cause stack is normalized but not called verified cause`() {
        val result = service().analyzeLogs("""
            java.lang.RuntimeException: wrapper
                at sample.Ui.render(Ui.kt:1)
            Caused by: java.io.IOException: disk
                at sample.Store.write(Store.kt:2)
        """.trimIndent())
        val event = result.logSummary!!.events.single()
        assertEquals("java.io.IOException", event.exceptionClass)
        assertEquals(listOf("sample.Store.write"), event.frames)
        assertTrue(result.groups.single().explanation.contains("not confirmed"))
    }

    @Test fun `exception only fingerprints are explicitly weaker`() {
        val report = service().analyzeLogs("java.io.IOException: one\n\njava.io.IOException: two")
        assertEquals(2, report.logSummary!!.events.size)
        assertEquals(GroupKind.EXCEPTION_SIGNATURE, report.groups.single().kind)
        assertEquals(Confidence.LOW, report.groups.single().confidence)
    }

    @Test fun `secrets URLs emails paths addresses and private keys are redacted`() {
        val input = """
            java.lang.IllegalStateException: token=top-secret user@example.com /Users/private/person/data.db 192.168.0.22 https://api.example.com/account/123?token=leak
                at sample.Store.save(Store.kt:22)
            Authorization: Bearer dangerous-auth-value
            -----BEGIN PRIVATE KEY-----
            very-private-key
            -----END PRIVATE KEY-----
        """.trimIndent()
        val report = service().analyzeLogs(input)
        val excerpt = report.groups.single().evidence.single().snippet
        for (secret in listOf("top-secret", "user@example.com", "/Users/private", "192.168.0.22", "api.example", "dangerous-auth-value", "very-private-key")) assertFalse(excerpt.contains(secret))
        assertTrue(report.logSummary!!.redacted)
        assertTrue(report.groups.single().evidence.single().redacted)
        val redacted = QualityRedactor.redact("password='hello there' api_key=secret123 Cookie: secret-cookie\nC:\\Users\\private\\file.log\ncontent://private/account/4").text
        for (secret in listOf("hello there", "secret123", "secret-cookie", "Users", "private/account")) assertFalse(redacted.contains(secret))
    }

    @Test fun `redaction does not break canonical evidence citations or stack identifiers`() {
        val citation = "https://github.com/example/app/issues/123"
        assertEquals(citation, QualityRedactor.redact(citation).text)
        assertEquals("java.io.IOException", QualityRedactor.redact("java.io.IOException").text)
        assertNotEquals("https://github.com/example/app/issues/123?token=secret", QualityRedactor.redact("https://github.com/example/app/issues/123?token=secret").text)
    }

    @Test fun `blank normal and line-bounded logs are honest about unsupported evidence`() {
        val blank = service().analyzeLogs("")
        assertEquals(0, blank.logSummary!!.lineCount)
        assertTrue(blank.groups.isEmpty())
        assertTrue(blank.checklists.isEmpty())
        val normal = service().analyzeLogs("App started\nRequest finished successfully")
        assertTrue(normal.groups.isEmpty())
        assertTrue(normal.warnings.any { "does not mean" in it })
        val long = service(LogAnalysisConfig(maxLineChars = 64)).analyzeLogs("java.io.IOException: " + "a".repeat(500))
        assertEquals(1, long.logSummary!!.truncatedLines)
    }

    @Test fun `input lines and events are bounded with typed safe errors`() {
        assertEquals(QualityErrorCode.LIMIT_EXCEEDED, assertFailsWith<QualityException> { service(LogAnalysisConfig(maxInputChars = 5)).analyzeLogs("123456") }.code)
        assertEquals(QualityErrorCode.LIMIT_EXCEEDED, assertFailsWith<QualityException> { service(LogAnalysisConfig(maxLines = 1)).analyzeLogs("a\nb") }.code)
        assertEquals(QualityErrorCode.LIMIT_EXCEEDED, assertFailsWith<QualityException> { service(LogAnalysisConfig(maxEvents = 1)).analyzeLogs("java.io.IOException: a\n\njava.io.IOException: b") }.code)
    }
}
