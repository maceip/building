package dev.ide.build

import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Path
import javax.tools.ToolProvider

class AndroidReleaseConfigTest {
    @TempDir lateinit var temp: Path
    private fun defaults() = AndroidReleaseConfig.parseDefaults(File("../config/android-release.properties").readText())

    @Test fun `public defaults use literal values and reject ambiguous properties syntax`() {
        assertEquals(mapOf("ANDROID_VERSION_NAME" to "version # literal", "JUDGE_URL" to ""),
            AndroidReleaseConfig.parseDefaults("# comment\nANDROID_VERSION_NAME=version # literal  \nJUDGE_URL=\n"))
        for (text in listOf("JUDGE_URL=one\nJUDGE_URL=two", "ANDROID_VERSION_NAME=escaped\\nvalue", "RELEASE_STORE_PASSWORD=private")) {
            assertThrows(IllegalArgumentException::class.java) { AndroidReleaseConfig.parseDefaults(text) }
        }
    }

    @Test fun `ordinary builds preserve explicit property precedence including empty backends`() {
        val properties = mapOf("ANDROID_VERSION_CODE" to "009", "SUPABASE_URL" to "", "SUPABASE_KEY" to "")
        val env = mapOf("ANDROID_VERSION_CODE" to "8", "SUPABASE_URL" to "https://env.example", "SUPABASE_KEY" to "public")
        val result = AndroidReleaseConfig.resolve(defaults(), properties::get, env::get)
        assertEquals(9, result.versionCode)
        assertEquals("9", result.values["ANDROID_VERSION_CODE"])
        assertEquals("", result.values["SUPABASE_URL"])
        assertEquals("ai.building", result.applicationId)
    }

    @Test fun `helper environment owns every value including signing despite conflicting Gradle and legacy inputs`() {
        val env = defaults() + mapOf("ANDROID_APPLICATION_ID" to "test.verified", "ANDROID_VERSION_CODE" to "11",
            "SUPABASE_URL" to "https://checked.example", "SUPABASE_KEY" to "checked-public",
            "RELEASE_STORE_PASSWORD" to "checked-password", "RELEASE_STORE_FILE" to "/checked/key.p12")
        val result = AndroidReleaseConfig.resolve(defaults(), { "unrelated-property" }, env::get, true,
            mapOf("storePassword" to "unrelated-legacy", "storeFile" to "/unrelated/key.p12"))
        assertEquals(env.filterKeys { it in AndroidReleaseConfig.PUBLIC_KEYS }, result.values)
        assertEquals("checked-password", result.signingValue("RELEASE_STORE_PASSWORD"))
        assertEquals("/checked/key.p12", result.signingValue("RELEASE_STORE_FILE"))
        assertFalse(result.values.keys.any { it.startsWith("RELEASE_") })
    }

    @Test fun `helper mode fails instead of silently filling a missing checked value`() {
        val env = defaults() - "JUDGE_URL"
        val error = assertThrows(IllegalArgumentException::class.java) {
            AndroidReleaseConfig.resolve(defaults(), { "https://unchecked.example" }, env::get, true)
        }
        assertTrue(error.message!!.contains("JUDGE_URL"))
    }

    @Test fun `disabled ads discard every configured unit`() {
        val overrides = defaults().keys.filter { it.startsWith("ADMOB_") }.associateWith { "upstream-unit" }
        val result = AndroidReleaseConfig.resolve(defaults(), overrides::get, { null })
        assertTrue(result.values.filterKeys { it.startsWith("ADMOB_") }.values.all { it.isEmpty() })
        assertEquals("\"\"", result.buildConfigFields["AD_NATIVE_UNIT_IDS"])
    }

    @Test fun `enabled ads require production ids and keep only explicitly configured placements`() {
        val unit = "ca-app-pub-1234567890123456/1234567890"
        val overrides = mapOf("ANDROID_ADS_ENABLED" to "true", "ADMOB_APP_ID" to unit.replace('/', '~'),
            "ADMOB_NATIVE_UNIT_ID" to unit, "ADMOB_INTERSTITIAL_UNIT_ID" to unit, "ADMOB_NATIVE_UNIT_STORE" to unit)
        val result = AndroidReleaseConfig.resolve(defaults(), overrides::get, { null })
        assertEquals("\"STORE=$unit\"", result.buildConfigFields["AD_NATIVE_UNIT_IDS"])
        assertThrows(IllegalArgumentException::class.java) {
            AndroidReleaseConfig.resolve(defaults(), (overrides + ("ADMOB_NATIVE_UNIT_ID" to "ca-app-pub-3940256099942544/2247696110"))::get, { null })
        }
    }

    @Test fun `invalid versions backends and public defaults fail before compilation`() {
        for (overrides in listOf(mapOf("ANDROID_VERSION_CODE" to "0"), mapOf("ANDROID_VERSION_CODE" to "2147483648"),
            mapOf("ANDROID_VERSION_NAME" to "line\nbreak"), mapOf("SUPABASE_URL" to "https://missing-key.example"),
            mapOf("JUDGE_URL" to "https://"), mapOf("ANDROID_ADS_ENABLED" to "yes"))) {
            assertThrows(IllegalArgumentException::class.java) { AndroidReleaseConfig.resolve(defaults(), overrides::get, { null }) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            AndroidReleaseConfig.resolve(defaults() + ("RELEASE_STORE_PASSWORD" to "must-not-ship"), { null }, { null })
        }
    }

    @Test fun `generated client literals compile and round trip quotes slashes and Unicode`() {
        val value = "quote\" backslash\\ newline\n tab\t snowman \u2603"
        val source = temp.resolve("ClientValue.java").toFile()
        source.writeText("public class ClientValue { public static String value() { return ${AndroidReleaseConfig.javaString(value)}; } }")
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, source.path))
        URLClassLoader(arrayOf(temp.toUri().toURL())).use {
            assertEquals(value, it.loadClass("ClientValue").getMethod("value").invoke(null))
        }
    }

    @Test fun `record generation rejects a late variant override instead of certifying stale values`() {
        val config = AndroidReleaseConfig.resolve(defaults(), { null }, { null })
        val project = ProjectBuilder.builder().withProjectDir(temp.toFile()).build()
        val task = project.tasks.register("record", WriteAndroidReleaseConfig::class.java).get()
        task.values.set(config.values)
        task.applicationId.set(config.applicationId)
        task.versionCode.set(config.versionCode)
        task.versionName.set(config.versionName)
        task.admobAppId.set("")
        task.outputDirectory.set(temp.resolve("assets").toFile())
        task.buildConfigFields.set(config.buildConfigFields + ("JUDGE_URL" to "\"https://unchecked.example\""))
        val error = assertThrows(IllegalStateException::class.java) { task.write() }
        assertTrue(error.message!!.contains("JUDGE_URL"))
        assertFalse(temp.resolve("assets/building-release-config.json").toFile().exists())
        task.buildConfigFields.set(config.buildConfigFields)
        task.write()
        assertTrue(temp.resolve("assets/building-release-config.json").toFile().isFile)
    }
}
