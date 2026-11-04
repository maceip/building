package dev.ide.workbench.quality

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class ProjectQualityScannerTest {
    @TempDir lateinit var temp: Path
    private fun seed(relative: String, text: String): Path = temp.resolve(relative).also {
        Files.createDirectories(it.parent); Files.writeString(it, text)
    }
    private fun manifest(application: String, children: String = "", permissions: String = ""): String = """
        <manifest xmlns:android="http://schemas.android.com/apk/res/android">
            $permissions
            <application $application>
                $children
            </application>
        </manifest>
    """.trimIndent()
    private fun scan(config: ProjectScanConfig = ProjectScanConfig()) = ProjectQualityScanner(config).scan(temp)

    @Test fun `manifest warnings are structural evidence with file line severity and manual remediation`() {
        val file = seed("app/src/main/AndroidManifest.xml", manifest("android:usesCleartextTraffic=\"true\" android:debuggable=\"true\"", """
            <service android:name=".SyncService" android:exported="true" />
            <provider android:name=".DataProvider" android:exported="true" />
        """.trimIndent(), "<uses-permission android:name=\"android.permission.READ_CONTACTS\" />"))
        val result = scan()
        assertTrue(result.findings.any { it.ruleId == "MANIFEST_CLEARTEXT" && it.status == FindingStatus.REVIEW_NEEDED })
        assertTrue(result.findings.any { it.ruleId == "MANIFEST_DEBUGGABLE" && it.severity == Severity.HIGH })
        assertTrue(result.findings.any { it.ruleId == "MANIFEST_BACKUP_POLICY" && it.status == FindingStatus.REVIEW_NEEDED })
        assertEquals(2, result.findings.count { it.ruleId == "EXPORTED_COMPONENT" })
        assertTrue(result.findings.any { it.ruleId == "SENSITIVE_PERMISSION_DECLARED" })
        assertTrue(result.findings.all { it.file == file.toRealPath().toString() && it.line >= 1 && it.reason.isNotBlank() && it.remediation.isNotBlank() })
        assertTrue(result.caveats.any { "No aggregate" in it })
    }

    @Test fun `safe declarations launcher activity and debug source are not release defects`() {
        seed("app/src/main/AndroidManifest.xml", manifest("android:usesCleartextTraffic=\"false\" android:debuggable=\"false\" android:allowBackup=\"false\"", """
            <activity android:name=".MainActivity" android:exported="true">
                <intent-filter>
                    <action android:name="android.intent.action.MAIN" />
                    <category android:name="android.intent.category.LAUNCHER" />
                </intent-filter>
            </activity>
            <service android:name=".PrivateService" android:exported="false" />
        """.trimIndent()))
        seed("app/src/debug/AndroidManifest.xml", manifest("android:debuggable=\"true\" android:allowBackup=\"false\" android:usesCleartextTraffic=\"true\""))
        val report = scan()
        assertTrue(report.findings.isNotEmpty())
        assertTrue(report.findings.all { it.status == FindingStatus.OBSERVED && it.severity == Severity.INFO })
        assertFalse(report.findings.any { it.ruleId == "EXPORTED_COMPONENT" })
    }

    @Test fun `manifest comments false namespaces and source keywords do not become findings`() {
        seed("app/src/main/AndroidManifest.xml", """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android" xmlns:fake="urn:not-android">
                <!-- <application android:debuggable="true" android:usesCleartextTraffic="true"/> -->
                <application android:debuggable="false" android:allowBackup="false" android:usesCleartextTraffic="false" fake:debuggable="true" />
            </manifest>
        """.trimIndent())
        seed("app/src/main/java/sample/Source.kt", "// password root crash allowBackup usesCleartextTraffic=true debuggable=true")
        val report = scan()
        assertFalse(report.findings.any { it.severity != Severity.INFO })
        assertEquals(1, report.scanSummary!!.inspectedFiles)
    }

    @Test fun `signature guards are observations and exported placeholders request review`() {
        seed("app/src/main/AndroidManifest.xml", manifest("android:allowBackup=\"false\" android:usesCleartextTraffic=\"false\"", """
            <service android:name=".ProtectedService" android:exported="true" android:permission="example.PRIVATE"/>
            <receiver android:name=".Placeholder" android:exported="${'$'}{exportFlag}"/>
            <receiver android:name=".Implicit"><intent-filter><action android:name="sample.ACTION"/></intent-filter></receiver>
        """.trimIndent(), "<permission android:name=\"example.PRIVATE\" android:protectionLevel=\"signature\"/>"))
        val report = scan()
        assertEquals(FindingStatus.OBSERVED, report.findings.single { it.ruleId == "EXPORTED_COMPONENT" }.status)
        assertEquals(2, report.findings.count { it.ruleId == "EXPORTED_COMPONENT_UNRESOLVED" })
    }

    @Test fun `network and backup policies inspected structurally with debug override distinction`() {
        seed("app/src/main/AndroidManifest.xml", manifest("android:networkSecurityConfig=\"@xml/network\" android:fullBackupContent=\"@xml/backup\""))
        seed("app/src/main/res/xml/network.xml", """
            <network-security-config>
                <base-config cleartextTrafficPermitted="true"><trust-anchors><certificates src="user"/></trust-anchors></base-config>
                <debug-overrides><trust-anchors><certificates src="user"/></trust-anchors></debug-overrides>
            </network-security-config>
        """.trimIndent())
        seed("app/src/main/res/xml/backup.xml", "<full-backup-content><include domain=\"root\" path=\".\"/></full-backup-content>")
        val report = scan()
        assertEquals(1, report.findings.count { it.ruleId == "NETWORK_USER_CERTIFICATES" })
        assertTrue(report.findings.any { it.ruleId == "NETWORK_CONFIG_CLEARTEXT" })
        assertTrue(report.findings.any { it.ruleId == "BACKUP_BROAD_INCLUDE" })
        assertFalse(report.findings.any { it.ruleId == "MANIFEST_POLICY_NOT_OBSERVED" })
    }

    @Test fun `XXE malformed and deep XML are not expanded or misreported as inspected`() {
        val secret = seed("private.txt", "never-include-this-secret")
        seed("app/src/main/AndroidManifest.xml", """<!DOCTYPE manifest [<!ENTITY xxe SYSTEM "${secret.toUri()}">]><manifest>&xxe;</manifest>""")
        seed("app/src/main/res/xml/bad.xml", "<network-security-config><unclosed></network-security-config>")
        seed("app/src/main/res/xml/deep.xml", "<node>".repeat(100) + "</node>".repeat(100))
        val report = scan()
        assertTrue(report.scanSummary!!.partial)
        assertTrue(report.findings.any { it.ruleId == "XML_UNSAFE_DECLARATION" })
        assertTrue(report.findings.any { it.ruleId == "XML_NOT_INSPECTED" })
        assertFalse(report.toString().contains("never-include-this-secret"))
    }

    @Test fun `project scanner never follows external or internal symlinks and excludes generated dirs`() {
        val outside = Files.createTempDirectory(temp.parent, "quality-outside-")
        try {
            Files.writeString(outside.resolve("AndroidManifest.xml"), manifest("android:debuggable=\"true\""))
            Files.createSymbolicLink(temp.resolve("external"), outside)
            Files.createSymbolicLink(temp.resolve("linked.xml"), outside.resolve("AndroidManifest.xml"))
            seed("build/AndroidManifest.xml", manifest("android:debuggable=\"true\""))
            seed("node_modules/dependency/package.json", "{\"dependencies\":{\"x\":\"*\"}}")
            seed(".git/AndroidManifest.xml", manifest("android:debuggable=\"true\""))
            seed("src/main/AndroidManifest.xml", manifest("android:allowBackup=\"false\" android:usesCleartextTraffic=\"false\""))
            val report = scan(ProjectScanConfig(excludedDirectories = emptySet()))
            assertEquals(2, report.scanSummary!!.skippedSymlinks)
            assertEquals(1, report.scanSummary!!.inspectedFiles)
            assertFalse(report.findings.any { it.ruleId == "MANIFEST_DEBUGGABLE" })
            assertTrue(report.findings.all { it.file.startsWith(temp.toRealPath().toString()) })
            assertEquals(QualityErrorCode.UNSAFE_PATH, assertFailsWith<QualityException> { ProjectQualityScanner().scan(temp.resolve("external")) }.code)
        } finally { Files.deleteIfExists(outside.resolve("AndroidManifest.xml")); Files.deleteIfExists(outside) }
    }

    @Test fun `walk depth file bytes entries and findings are bounded with partial coverage`() {
        seed("one/two/three/build.gradle.kts", "dependencies { implementation(\"a:b:+\") }")
        seed("build.gradle.kts", "dependencies { implementation(\"a:b:+\") }".repeat(30))
        assertTrue(scan(ProjectScanConfig(maxDepth = 2)).scanSummary!!.partial)
        assertTrue(scan(ProjectScanConfig(maxFileBytes = 10)).scanSummary!!.partial)
        assertTrue(scan(ProjectScanConfig(maxEntries = 1)).scanSummary!!.partial)
        val findings = scan(ProjectScanConfig(maxFindings = 1))
        assertEquals(1, findings.findings.size)
        assertTrue(findings.scanSummary!!.partial)
    }

    @Test fun `dynamic dependencies release debugging and missing test locking config are review warnings`() {
        seed("build.gradle.kts", """
            dependencies { implementation("example:library:1.+") }
            android { buildTypes { release { isDebuggable = true } } }
            // implementation("example:commented:+")
            // release { isDebuggable = true }
        """.trimIndent())
        val report = scan()
        assertEquals(1, report.findings.count { it.ruleId == "DYNAMIC_DEPENDENCY" })
        assertEquals(1, report.findings.count { it.ruleId == "GRADLE_RELEASE_DEBUGGABLE" })
        assertTrue(report.findings.any { it.ruleId == "TEST_FILES" && it.status == FindingStatus.REVIEW_NEEDED })
        assertTrue(report.findings.any { it.ruleId == "LOCKFILES" && it.status == FindingStatus.REVIEW_NEEDED })
        assertTrue(report.findings.any { it.ruleId == "GRADLE_WRAPPER_NOT_OBSERVED" })
    }

    @Test fun `pinned dependency tests lock and checksum presence are observations not passing tests`() {
        seed("build.gradle.kts", "dependencies { implementation(\"example:library:1.0.0\"); testImplementation(\"example:tests:1.0\") }")
        seed("gradle.lockfile", "example:library:1.0.0=runtimeClasspath")
        seed("gradle/wrapper/gradle-wrapper.properties", "distributionUrl=https\\://services.gradle.org/distributions/gradle-9.5.1-bin.zip\ndistributionSha256Sum=" + "a".repeat(64))
        seed("src/test/kotlin/AppTest.kt", "class AppTest")
        val report = scan()
        assertEquals(1, report.scanSummary!!.testFilesObserved)
        assertEquals(1, report.scanSummary!!.lockfilesObserved)
        assertFalse(report.findings.any { it.ruleId == "DYNAMIC_DEPENDENCY" })
        assertTrue(report.findings.all { it.status == FindingStatus.OBSERVED })
        assertTrue(report.findings.any { it.reason.contains("not validated") })
    }

    @Test fun `npm scripts are never executed ranges respect lock evidence and malformed config is partial`() {
        val marker = temp.resolve("not-created")
        seed("package.json", """{"scripts":{"test":"touch $marker"},"dependencies":{"range":"^1.0.0","exact":"1.2.3"}}""")
        val before = scan()
        assertFalse(Files.exists(marker))
        assertEquals(1, before.findings.count { it.ruleId == "PACKAGE_DEPENDENCY_RANGE" })
        assertEquals(FindingStatus.REVIEW_NEEDED, before.findings.single { it.ruleId == "PACKAGE_DEPENDENCY_RANGE" }.status)
        seed("package-lock.json", "{}")
        val after = scan()
        assertEquals(FindingStatus.OBSERVED, after.findings.single { it.ruleId == "PACKAGE_DEPENDENCY_RANGE" }.status)
        seed("bad/package.json", "{ invalid token=do-not-leak")
        val malformed = scan()
        assertTrue(malformed.scanSummary!!.partial)
        assertTrue(malformed.findings.any { it.ruleId == "PACKAGE_CONFIG_INVALID" })
        assertFalse(malformed.toString().contains("do-not-leak"))
    }

    @Test fun `scanner is read only and blank projects are not passes`() {
        val empty = scan()
        assertTrue(empty.findings.isEmpty())
        assertTrue(empty.warnings.any { "not a quality pass" in it })
        val text = manifest("android:debuggable=\"true\"")
        val file = seed("src/main/AndroidManifest.xml", text)
        scan()
        assertEquals(text, Files.readString(file))
        assertEquals(1L, Files.walk(temp).use { paths -> paths.filter { Files.isRegularFile(it) }.count() })
    }
}
