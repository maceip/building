package dev.ide.workbench.webapp

import java.nio.file.Path
import java.util.Collections

/** An already-built, offline static site. This API never executes a build script or a server. */
data class WebAppPackageRequest(
    val workspaceRoot: Path,
    /** Explicit workspace-relative output directory, e.g. dist, build, public, static, out, or ".". */
    val staticRoot: String,
    /** An existing directory OUTSIDE the source workspace; a new [projectName] child is reserved. */
    val outputParent: Path,
    val projectName: String,
    val applicationId: String,
    val appLabel: String,
    val versionCode: Int = 1,
    val versionName: String = "1.0",
    /** Only missing, extensionless top-level document navigations fall back to index.html. */
    val spaFallback: Boolean = false,
    /** Even when enabled, each external HTTPS link needs a real gesture and a native confirmation. */
    val allowExternalHttps: Boolean = false,
)

/** Conservative, streaming limits. Counting also bounds excluded entries and inspected secret files. */
data class WebAppPackageLimits(
    val maxFiles: Int = 10_000,
    val maxEntries: Int = 20_000,
    val maxFileBytes: Long = 32L * 1024 * 1024,
    val maxTotalBytes: Long = 128L * 1024 * 1024,
    val maxDepth: Int = 24,
    val maxPathBytes: Int = 512,
) {
    init {
        require(maxFiles in 1..10_000 && maxEntries in maxFiles..100_000)
        require(maxFileBytes in 1..(128L * 1024 * 1024))
        require(maxTotalBytes in 1..(512L * 1024 * 1024))
        require(maxDepth in 1..64 && maxPathBytes in 16..1024)
    }
}

data class WebAppAsset(val path: String, val bytes: Long, val sha256: String)
data class WebAppExcludedAsset(val path: String, val reason: String)

/** A read-only snapshot, not a permission to silently include files added after review. */
class WebAppPackageReview internal constructor(
    val request: WebAppPackageRequest,
    val staticDirectory: Path,
    val outputDirectory: Path,
    assets: List<WebAppAsset>,
    excluded: List<WebAppExcludedAsset>,
    val assetManifestSha256: String,
    internal val limits: WebAppPackageLimits,
) {
    val assets: List<WebAppAsset> = Collections.unmodifiableList(assets.toList())
    val excluded: List<WebAppExcludedAsset> = Collections.unmodifiableList(excluded.toList())
    val totalBytes: Long = assets.sumOf { it.bytes }
}

data class WebAppPackageResult(
    val projectRoot: Path,
    val sourceWorkspace: Path,
    val sourceStaticDirectory: Path,
    val applicationId: String,
    val assetCount: Int,
    val assetBytes: Long,
    val assetManifestSha256: String,
) {
    val assetManifest: Path get() = projectRoot.resolve("app/src/main/assets/webapp-manifest.json")
    val debugApk: Path get() = projectRoot.resolve("app/build/outputs/apk/debug/app-debug.apk")
    val releaseBundle: Path get() = projectRoot.resolve("app/build/outputs/bundle/release/app-release.aab")

    companion object {
        const val MODULE_NAME = "app"
        const val DEBUG_ASSEMBLE_TASK = "assemble:app:debug"
        const val DEBUG_RUN_TASK = "androidRun:app:debug"
        const val RELEASE_BUNDLE_TASK = "bundle:app:release"
    }
}
