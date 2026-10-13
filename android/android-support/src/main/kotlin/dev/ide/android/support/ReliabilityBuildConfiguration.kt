package dev.ide.android.support

import dev.ide.android.support.gms.MiniJson
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

/** A build-scoped request, never a request to trust an installed app or to enable collection. */
data class ReliabilityBuildRequest(
    val projectPath: Path,
    val moduleId: String,
    val packageName: String,
    val variant: String,
    val debuggable: Boolean,
    val sourceRevision: String,
    val buildId: String,
)

/**
 * Returns the host's explicit recipient/pairing fields as JSON, or null for local-only collection.
 * Called only AFTER project consent, at build time. A nonce is a pending pairing capability, not proof
 * of the installed app's signer: the host must verify/approve that signer before accepting reports.
 * Release builds never call this port and never receive an IDE recipient or nonce.
 */
fun interface ReliabilityConfigurationProvider {
    fun configurationFor(request: ReliabilityBuildRequest): String?
}

/** The production Java-8 SDK only. Debug fault controls belong in a separate, debug/test-only artifact. */
data class ReliabilityBuildConfiguration(
    val runtimeJar: Path,
    val privateCacheRoot: Path,
    val configurationProvider: ReliabilityConfigurationProvider? = null,
)

/** Optional host wiring, read per graph. Installing a runtime/provider does NOT opt any project in. */
object ReliabilityBuildEnvironment {
    @Volatile var runtimeJar: Path? = null
    @Volatile var privateCacheRoot: Path? = null
    @Volatile var provider: ReliabilityConfigurationProvider? = null

    fun configuration(fallbackRuntimeJar: Path? = null): ReliabilityBuildConfiguration? {
        val jar = runtimeJar ?: fallbackRuntimeJar ?: return null
        val cache = privateCacheRoot ?: return null
        return ReliabilityBuildConfiguration(jar, cache, provider)
    }
}

/** Only these non-secret flags are read from a user's repository; nothing is written back to it. */
data class ReliabilityProjectOptions(
    val enabled: Boolean = false,
    val consentGranted: Boolean = false,
    val captureCrashes: Boolean = true,
    val captureLifecycle: Boolean = true,
    val capturePreviousExitInfo: Boolean = true,
    val captureNetwork: Boolean = false,
    val teeStdout: Boolean = false,
    val productionEnabled: Boolean = false,
) {
    fun appliesTo(debuggable: Boolean): Boolean = enabled && consentGranted && (debuggable || productionEnabled)

    internal fun publicFields(): Map<String, Any> = linkedMapOf(
        "version" to 1, "enabled" to enabled, "consentGranted" to consentGranted,
        "captureCrashes" to captureCrashes, "captureLifecycle" to captureLifecycle,
        "capturePreviousExitInfo" to capturePreviousExitInfo, "captureNetwork" to captureNetwork,
        "teeStdout" to teeStdout, "productionEnabled" to productionEnabled,
    )

    companion object {
        const val PROJECT_FILE = ".building/reliability.json"
        const val ASSET_NAME = "ide-reliability.json"
        const val SOURCE_ASSET_NAME = "ide-reliability-sources.json"
        const val CONFIG_METADATA = "dev.ide.reliability.CONFIG_ASSET"
        const val PROVIDER_CLASS = "dev.ide.applog.IdeLogBridgeProvider"
        const val AUTHORITY_SUFFIX = "ide-reliability"

        fun read(projectRoot: Path): ReliabilityProjectOptions {
            val path = projectRoot.resolve(PROJECT_FILE)
            if (!Files.exists(path, NOFOLLOW_LINKS)) return ReliabilityProjectOptions()
            require(Files.isRegularFile(path, NOFOLLOW_LINKS) && !Files.isSymbolicLink(path.parent)) {
                "Reliability: $PROJECT_FILE must be a regular, non-symlinked file."
            }
            require(Files.size(path) <= 16_384) { "Reliability: $PROJECT_FILE is too large." }
            val fields = try {
                MiniJson.parse(String(Files.readAllBytes(path), Charsets.UTF_8)) as? Map<*, *>
                    ?: error("expected an object")
            } catch (e: Exception) {
                throw IllegalArgumentException("Reliability: invalid $PROJECT_FILE; expected non-secret version-1 flags.", e)
            }
            val defaults = ReliabilityProjectOptions()
            require(fields.keys.all { it in defaults.publicFields().keys }) {
                "Reliability: $PROJECT_FILE accepts collection flags only; keep pairing credentials in host-private storage."
            }
            require((fields["version"] as? Number)?.toDouble() == 1.0) {
                "Reliability: unsupported $PROJECT_FILE version (expected 1)."
            }
            fun flag(key: String, default: Boolean): Boolean {
                if (key !in fields) return default
                return fields[key] as? Boolean
                    ?: throw IllegalArgumentException("Reliability: '$key' must be a boolean in $PROJECT_FILE.")
            }
            return ReliabilityProjectOptions(
                flag("enabled", false), flag("consentGranted", false), flag("captureCrashes", true),
                flag("captureLifecycle", true), flag("capturePreviousExitInfo", true), flag("captureNetwork", false),
                flag("teeStdout", false), flag("productionEnabled", false),
            )
        }
    }
}
