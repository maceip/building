import groovy.json.JsonSlurper
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermission

plugins {
    alias(libs.plugins.android.application)
}

fun quoted(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
val hostFields = listOf("idePackage", "ideService", "pairingNonce", "ideSignerSha256")
require(hostFields.none { providers.gradleProperty("reliability.fixture.$it").isPresent }) {
    "Do not put fixture pairing credentials on the command line; use reliability.fixture.configFile"
}
val configFile = providers.gradleProperty("reliability.fixture.configFile").orNull
val pairedConfig: Map<*, *>? = configFile?.let { supplied ->
    val path = try { Paths.get(supplied) } catch (_: Exception) {
        throw GradleException("Invalid private fixture configuration path")
    }
    require(path.isAbsolute && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)) {
        "Fixture configuration must be an absolute regular non-symlink private file"
    }
    require(!path.toRealPath().startsWith(rootProject.projectDir.toPath().toRealPath()) && Files.size(path) <= 16_384) {
        "Fixture configuration must be outside the project and at most 16 KiB"
    }
    val permissions = try { Files.getPosixFilePermissions(path) } catch (_: UnsupportedOperationException) {
        throw GradleException("Private fixture config requires verifiable POSIX owner-only permissions")
    }
    require(permissions.none { it in setOf(PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_WRITE,
        PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_WRITE,
        PosixFilePermission.OTHERS_EXECUTE) }) { "Use owner-only permissions for the private fixture config" }
    val parsed = try {
        JsonSlurper().parseText(providers.fileContents(layout.file(providers.provider { path.toFile() })).asText.get()) as? Map<*, *>
            ?: throw IllegalArgumentException()
    } catch (_: Exception) {
        // Parser messages/causes can quote the credential. Never attach or print them.
        throw GradleException("Invalid private fixture configuration JSON")
    }
    val allowed = (hostFields + listOf("version", "enabled", "consentGranted", "packageName", "buildId", "sourceRevision",
        "captureCrashes", "captureLifecycle", "capturePreviousExitInfo", "captureNetwork", "teeStdout", "productionEnabled")).toSet()
    require(parsed.keys.all { it in allowed } && (parsed["version"] as? Number)?.toInt() == 1 &&
        parsed["enabled"] == true && parsed["consentGranted"] == true && parsed["productionEnabled"] == false &&
        parsed["packageName"] == "dev.ide.reliability.fixture" && parsed["buildId"] == "fixture-fixedV2-2" &&
        parsed["sourceRevision"] == "fixture-state-v1") { "Fixture grant identity/version/consent is invalid" }
    require(listOf("captureCrashes", "captureLifecycle", "capturePreviousExitInfo", "captureNetwork", "teeStdout")
        .all { parsed[it] is Boolean } && parsed["captureCrashes"] == true && parsed["captureLifecycle"] == true &&
        parsed["teeStdout"] == false) { "Fixture grant capture flags are invalid" }
    require(listOf("idePackage", "ideService").all {
        (parsed[it] as? String)?.matches(Regex("[A-Za-z_][A-Za-z0-9_.\\$]{1,239}")) == true
    } && listOf("pairingNonce", "ideSignerSha256").all {
        (parsed[it] as? String)?.matches(Regex("[0-9a-f]{64}")) == true
    }) { "Fixture grant destination, nonce or signer is invalid" }
    parsed
}
val pairing = hostFields.associateWith { pairedConfig?.get(it) as? String ?: "" }
if (pairedConfig != null) {
    // Pairing bytes belong in this deliberately private debug artifact, not shared build caches or
    // serialized configuration snapshots. The coordinator cleans the private build output after use.
    require(!gradle.startParameter.isConfigurationCacheRequested && !gradle.startParameter.isBuildCacheEnabled) {
        "Private paired fixture builds require --no-configuration-cache --no-build-cache"
    }
    tasks.configureEach { outputs.doNotCacheIf("Private debug pairing artifact") { true } }
}

android {
    namespace = "dev.ide.reliability.fixture"
    compileSdk = 37
    defaultConfig {
        applicationId = "dev.ide.reliability.fixture"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1"
        testApplicationId = "dev.ide.reliability.fixture.test"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        manifestPlaceholders["fixtureIdePackage"] = pairing.getValue("idePackage").ifEmpty { "com.tyron.code.dev" }
        buildConfigField("String", "IDE_PACKAGE", quoted(pairing.getValue("idePackage")))
        buildConfigField("String", "IDE_SERVICE", quoted(pairing.getValue("ideService")))
        buildConfigField("String", "PAIRING_NONCE", quoted(pairing.getValue("pairingNonce")))
        buildConfigField("String", "IDE_SIGNER_SHA256", quoted(pairing.getValue("ideSignerSha256")))
    }
    flavorDimensions += listOf("behavior", "schema")
    productFlavors {
        create("broken") { dimension = "behavior" }
        create("fixed") { dimension = "behavior" }
        create("v1") {
            dimension = "schema"
            versionCode = 1
            versionName = "1"
            buildConfigField("int", "DATA_SCHEMA", "1")
        }
        create("v2") {
            dimension = "schema"
            versionCode = 2
            versionName = "2"
            buildConfigField("int", "DATA_SCHEMA", "2")
        }
    }
    buildTypes {
        getByName("debug") {
            isDebuggable = true
            isMinifyEnabled = false
        }
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

// This is a test application, not a distribution target. There is no release APK or release hook path.
androidComponents {
    beforeVariants(selector().all()) {
        if (it.buildType != "debug" || (pairedConfig != null && it.name != "fixedV2Debug")) it.enable = false
    }
}

dependencies {
    debugImplementation(project(":applog-runtime"))
    debugImplementation(project(":reliability-test-support"))
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation("androidx.test:core:1.6.1")
}
