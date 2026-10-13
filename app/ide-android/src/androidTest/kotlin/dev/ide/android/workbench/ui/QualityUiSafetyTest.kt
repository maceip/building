package dev.ide.android.workbench.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.time.Instant

/** Pure contracts: runs with AndroidJUnitRunner on device or ordinary JUnit on a host JVM. */
class QualityUiSafetyTest {
    @Test
    fun taskSelectionNeverFallsBackToInstallOrDefaultRun() {
        listOf(
            "androidRun:app:debug", "run:app", "gradle:assemble", "gradle:run", "gradle:run:app:debug",
            "gradle:install:app:debug", "gradle:build:app", "Gradle:build", "bundle", "build:",
            "gradle:build:app:debug --install", "gradle:build\n", ":app:installDebug", "gradle::build",
        ).forEach { assertNull("Unsafe/unsupported task ID: $it", qualityTaskKind(it)) }
        assertEquals(QualityTaskKind.BUILD, qualityTaskKind("build:app"))
        assertEquals(QualityTaskKind.BUILD, qualityTaskKind("assemble:app:debug"))
        assertEquals(QualityTaskKind.BUILD, qualityTaskKind("assembleAar:library:release"))
        assertEquals(QualityTaskKind.BUNDLE, qualityTaskKind("bundle:app:release"))
        assertEquals(QualityTaskKind.LINT, qualityTaskKind("lint:app"))
        assertEquals(QualityTaskKind.TEST, qualityTaskKind("test:app"))
        assertEquals(QualityTaskKind.BUILD, qualityTaskKind("gradle:build"))
        assertEquals(QualityTaskKind.BUILD, qualityTaskKind("gradle:build::main"))
        assertEquals(QualityTaskKind.TEST, qualityTaskKind("gradle:test::main"))
        assertEquals(QualityTaskKind.LINT, qualityTaskKind("gradle:lint:features:editor:debug"))
        assertEquals(QualityTaskKind.TEST, qualityTaskKind("gradle:test:app:debug"))
        assertEquals(QualityTaskKind.BUNDLE, qualityTaskKind("gradle:bundle:app:release"))
    }

    @Test
    fun onlyCanonicalGitHubEvidenceUrlsCanOpen() {
        assertEquals("https://github.com/org/repo/issues/42", qualityGitHubUrl("https://github.com/org/repo/issues/42?token=private#comment-1"))
        assertEquals("https://github.com/org/repo/pull/7", qualityGitHubUrl("https://github.com/org/repo/pull/7/"))
        listOf(
            "javascript:alert(1)", "file:///data/private", "http://github.com/org/repo/issues/1",
            "https://github.com.evil.invalid/org/repo/issues/1", "https://token@github.com/org/repo/issues/1",
            "https://github.com:8443/org/repo/issues/1", "https://github.com/org/repo/actions/1",
            "https://github.com/org/repo/issues/1/../../settings", "https://github.com/%2e%2e/repo/issues/1",
            "https://github.com/../repo/issues/1", "https://github.com/org/repo/issues/0",
            "https://github.com/ghp_PRIVATE_TOKEN/repo/issues/1",
        ).forEach { assertNull("Unexpected external link: $it", qualityGitHubUrl(it)) }
    }

    @Test
    fun credentialsAndControlCharactersAreRemovedBeforeDisplay() {
        val text = """
            Authorization: Bearer VERY_PRIVATE_BEARER
            {"Authorization":"VERY_PRIVATE_HEADER"}
            Cookie: sid=VERY_PRIVATE_COOKIE; auth=VERY_PRIVATE_COOKIE_AUTH
            session_id=VERY_PRIVATE_SESSION
            sk-VERY_PRIVATE_SECRET_KEY
            {"access_token":"VERY_PRIVATE_TOKEN","api_key":"VERY_PRIVATE_KEY"}
            password='VERY_PRIVATE_PASSWORD'
            https://username:VERY_PRIVATE_AUTH@github.com/org/repo/issues/1?token=VERY_PRIVATE_QUERY
            ghp_VERY_PRIVATE_GITHUB
            github_pat_VERY_PRIVATE_PAT
            eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJwcml2YXRlIn0.VERY_PRIVATE_SIGNATURE
            -----BEGIN PRIVATE KEY-----
            VERY_PRIVATE_PEM
            -----END PRIVATE KEY-----
            java.lang.IllegalStateException: ordinary error
        """.trimIndent() + "\u001b\u202e"
        val safe = qualityRedactUiText(text)
        assertFalse(safe.contains("VERY_PRIVATE"))
        assertFalse(safe.contains('\u001b'))
        assertFalse(safe.contains('\u202e'))
        assertTrue(safe.contains("[REDACTED]"))
        assertTrue(safe.contains("java.lang.IllegalStateException: ordinary error"))
        assertEquals(safe, qualityRedactUiText(safe))
    }

    @Test
    fun projectNavigationRejectsTraversalAndOutsideSymlink() {
        val root = Files.createTempDirectory("quality-navigation-")
        val outside = Files.createTempFile("quality-outside-", ".kt")
        val source = root.resolve("Main.kt")
        val link = root.resolve("escape.kt")
        try {
            Files.write(source, "class Main".toByteArray())
            assertEquals(source.toRealPath(), qualityResolveProjectFile(root, "Main.kt"))
            assertTrue(runCatching { qualityResolveProjectFile(root, outside.toString()) }.isFailure)
            assertTrue(runCatching { qualityResolveProjectFile(root, "../${outside.fileName}") }.isFailure)
            assertTrue(runCatching { qualityResolveProjectFile(root, "missing.kt") }.isFailure)
            Files.createSymbolicLink(link, outside)
            assertTrue(runCatching { qualityResolveProjectFile(root, "escape.kt") }.isFailure)
        } finally {
            Files.deleteIfExists(link)
            Files.deleteIfExists(source)
            Files.deleteIfExists(outside)
            Files.deleteIfExists(root)
        }
    }

    @Test
    fun archiveDateScopeIsExplicitUtcInclusiveAndValidated() {
        val config = qualityArchiveConfig("org/repo", "2026-01-01", "2026-01-31", true, false)
        assertEquals("org/repo", config.defaultRepository)
        assertEquals(Instant.parse("2026-01-01T00:00:00Z"), config.recentWindow?.start)
        assertEquals(Instant.parse("2026-01-31T23:59:59.999999999Z"), config.recentWindow?.endInclusive)
        assertTrue(config.recentOnly)
        assertFalse(config.includeDeviceSecuritySpecific)
        assertNull(qualityArchiveConfig("", "", "", false, false).recentWindow)
        assertTrue(runCatching { qualityArchiveConfig("", "2026-02-01", "2026-01-01", false, false) }.isFailure)
        assertTrue(runCatching { qualityArchiveConfig("", "2026-01-01", "", false, false) }.isFailure)
        assertTrue(runCatching { qualityArchiveConfig("", "2026-02-30", "2026-03-01", false, false) }.isFailure)
        assertTrue(runCatching { qualityArchiveConfig("https://github.com/org/repo?token=private", "", "", false, false) }.isFailure)
    }
}
