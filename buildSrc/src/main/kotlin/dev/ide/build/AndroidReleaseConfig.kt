package dev.ide.build

import org.gradle.api.Project
import java.net.URI
import java.util.Properties

/** One resolved set of public release values. Signing credentials never enter its artifact record. */
class AndroidReleaseConfig private constructor(
    val values: Map<String, String>,
    private val signing: Map<String, String?>,
) {
    val applicationId get() = values.getValue("ANDROID_APPLICATION_ID")
    val versionCode get() = values.getValue("ANDROID_VERSION_CODE").toInt()
    val versionName get() = values.getValue("ANDROID_VERSION_NAME")
    val adsEnabled get() = values.getValue("ANDROID_ADS_ENABLED").toBooleanStrict()
    fun signingValue(key: String): String? = signing[key]

    val buildConfigFields: Map<String, String> get() = mapOf(
        "SUPABASE_URL" to javaString(values.getValue("SUPABASE_URL")),
        "SUPABASE_KEY" to javaString(values.getValue("SUPABASE_KEY")),
        "JUDGE_URL" to javaString(values.getValue("JUDGE_URL")),
        "ADS_ENABLED" to adsEnabled.toString(),
        "AD_NATIVE_UNIT_ID" to javaString(values.getValue("ADMOB_NATIVE_UNIT_ID")),
        "AD_INTERSTITIAL_UNIT_ID" to javaString(values.getValue("ADMOB_INTERSTITIAL_UNIT_ID")),
        "AD_NATIVE_UNIT_IDS" to javaString(PLACEMENTS.mapNotNull { placement ->
            values.getValue("ADMOB_NATIVE_UNIT_$placement").takeIf { it.isNotBlank() }?.let { "$placement=$it" }
        }.joinToString(";")),
    )

    companion object {
        val PLACEMENTS = listOf("STORE", "STORE_ITEM", "PROJECTS", "LEARN", "CHALLENGE", "BUILD_CONSOLE",
            "RUN_RESULT", "SDK_MANAGER", "SETTINGS", "SIDEBAR")
        val PUBLIC_KEYS = setOf("ANDROID_APPLICATION_ID", "ANDROID_VERSION_CODE", "ANDROID_VERSION_NAME",
            "SUPABASE_URL", "SUPABASE_KEY", "JUDGE_URL", "ANDROID_ADS_ENABLED", "ADMOB_APP_ID",
            "ADMOB_NATIVE_UNIT_ID", "ADMOB_INTERSTITIAL_UNIT_ID") + PLACEMENTS.map { "ADMOB_NATIVE_UNIT_$it" }
        private val SIGNING_KEYS = mapOf("RELEASE_STORE_FILE" to "storeFile", "RELEASE_STORE_PASSWORD" to "storePassword",
            "RELEASE_KEY_ALIAS" to "keyAlias", "RELEASE_KEY_PASSWORD" to "keyPassword")

        fun from(project: Project): AndroidReleaseConfig {
            val text = project.providers.fileContents(project.rootProject.layout.projectDirectory.file("config/android-release.properties")).asText.get()
            val defaults = parseDefaults(text)
            val source = project.providers.gradleProperty("ANDROID_RELEASE_CONFIG_SOURCE").getOrElse("standard")
            require(source in setOf("standard", "environment")) { "ANDROID_RELEASE_CONFIG_SOURCE must be standard or environment" }
            val legacy = Properties()
            val legacyFile = project.rootProject.file("keystore.properties")
            if (source == "standard" && legacyFile.isFile) legacyFile.inputStream().use { legacy.load(it) }
            return resolve(defaults,
                { project.providers.gradleProperty(it).orNull },
                { project.providers.environmentVariable(it).orNull },
                source == "environment", legacy.stringPropertyNames().associateWith { legacy.getProperty(it) })
        }

        /** Same literal NAME=value format as the Python helper; no Java-properties escape interpretation. */
        fun parseDefaults(text: String): Map<String, String> {
            val values = linkedMapOf<String, String>()
            for (raw in text.lineSequence()) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                val key = line.substringBefore('=')
                val value = line.substringAfter('=', "")
                require('=' in line && key.matches(Regex("[A-Z][A-Z0-9_]*")) && key !in values && '\\' !in value) {
                    "Invalid public Android configuration; use unique literal NAME=value lines"
                }
                require(!key.startsWith("RELEASE_")) { "Signing values must not appear in public Android configuration" }
                values[key] = value
            }
            return values
        }

        /** The helper selects environment mode explicitly: user/project Gradle properties cannot shadow preflight. */
        fun resolve(defaults: Map<String, String>, property: (String) -> String?, environment: (String) -> String?,
                    environmentOnly: Boolean = false, legacySigning: Map<String, String> = emptyMap()): AndroidReleaseConfig {
            require(defaults.keys == PUBLIC_KEYS) { "The public Android release configuration has missing or unexpected keys" }
            val values = defaults.mapValues { (key, default) ->
                if (environmentOnly) requireNotNull(environment(key)) { "Release environment is missing $key" }
                else property(key) ?: environment(key) ?: default
            }.toMutableMap()
            require(values.getValue("ANDROID_APPLICATION_ID").matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+"))) {
                "ANDROID_APPLICATION_ID must be a valid dotted Android application ID"
            }
            val code = values.getValue("ANDROID_VERSION_CODE").toIntOrNull()
            require(code in 1..2100000000) { "ANDROID_VERSION_CODE must be between 1 and 2100000000" }
            values["ANDROID_VERSION_CODE"] = code.toString()
            require(values.getValue("ANDROID_VERSION_NAME").let { it.isNotBlank() && it.none(Char::isISOControl) }) { "ANDROID_VERSION_NAME must be a nonempty single line" }
            require(values.getValue("SUPABASE_URL").isEmpty() == values.getValue("SUPABASE_KEY").isEmpty()) { "Set both SUPABASE_URL and SUPABASE_KEY, or leave both empty" }
            for (key in listOf("SUPABASE_URL", "JUDGE_URL")) {
                val url = values.getValue(key)
                require(url.isEmpty() || runCatching { URI(url).let { it.scheme == "https" && !it.host.isNullOrBlank() } }.getOrDefault(false)) { "$key must be an HTTPS URL or empty" }
            }
            require(!values.getValue("SUPABASE_KEY").startsWith("sb_secret_")) { "SUPABASE_KEY must be a public client key" }
            val ads = values.getValue("ANDROID_ADS_ENABLED")
            require(ads == "true" || ads == "false") { "ANDROID_ADS_ENABLED must be true or false" }
            for (key in values.keys.filter { it.startsWith("ADMOB_") }) {
                if (ads == "false") values[key] = ""
                else {
                    val value = values.getValue(key)
                    val optional = key.startsWith("ADMOB_NATIVE_UNIT_") && key != "ADMOB_NATIVE_UNIT_ID"
                    val separator = if (key == "ADMOB_APP_ID") "~" else "/"
                    require((optional && value.isEmpty()) || (value.matches(Regex("ca-app-pub-[0-9]{16}$separator[0-9]{10}")) && !value.contains("3940256099942544"))) {
                        "$key must be a production AdMob ID${if (optional) " or empty" else ""}"
                    }
                }
            }
            val signing = SIGNING_KEYS.mapValues { (key, legacy) ->
                if (environmentOnly) environment(key) else legacySigning[legacy] ?: property(key) ?: environment(key)
            }
            return AndroidReleaseConfig(values.toMap(), signing)
        }

        /** Valid Java and JSON string literal; client values must never become generated source syntax. */
        fun javaString(value: String): String = buildString {
            append('"')
            for (c in value) when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c.code < 32) append("\\u%04x".format(c.code)) else append(c)
            }
            append('"')
        }
    }
}
