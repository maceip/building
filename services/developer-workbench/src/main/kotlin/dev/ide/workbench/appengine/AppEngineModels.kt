// SPDX-License-Identifier: GPL-3.0-or-later
package dev.ide.workbench.appengine

import java.nio.file.Path

/** Native app engines, not a list of developer CLI commands or npm packages. */
sealed class AppEngine(val id: String, val extensions: Set<String>) {
    data object Node : AppEngine("node", setOf("js", "mjs", "cjs"))
    data object Python : AppEngine("python", setOf("py"))
}

data class AppEnginePackageRequest(
    val sourceRoot: Path,
    val entry: String,
    val destination: Path,
    val applicationId: String,
    val label: String,
    val engine: AppEngine,
    /** Prepared by the pinned host builder, never a Termux prefix or an arbitrary interpreter. */
    val payloadRoot: Path,
    /** Compiled Java-8 core library classes from :runtime-engine, not the IDE. */
    val runtimeClassesJar: Path,
    val allowUnqualifiedNode: Boolean = false,
    /** The repository has NOT granted a runtime linking exception. */
    val acknowledgeGplDistribution: Boolean = false,
    val internetPermission: Boolean = false,
    /** Script argv only; never interpreted as engine switches or passed through a shell. */
    val arguments: List<String> = emptyList(),
    /** Optional debug-only SDK bridge. Null exports no SDK, listener, provider or configuration. */
    val reliability: AppEngineReliabilityDependencies? = null,
)

/**
 * Caller-supplied production classes, not downloaded or taken from the IDE process.
 * Only debug depends on these jars; release remains SDK-free. enableCapture alone is
 * insufficient: the generated app must separately initialize its own enabled, consented
 * SDK Config through the existing build/provider or Application path. Generation never
 * fabricates build identity, pairing credentials, collection consent or SDK configuration.
 */
data class AppEngineReliabilityDependencies(
    val sdkClassesJar: Path,
    val listenerClassesJar: Path,
    val enableCapture: Boolean = false,
)

data class AppEnginePackageResult(
    val projectRoot: Path,
    val engine: AppEngine,
    val bundledBytes: Long,
    val sourceBundle: Path,
    val warnings: List<String>,
)

/** Explicitly narrow, source-backed capabilities. Never claims arbitrary desktop wheels/addons work. */
object AppEngineCapabilities {
    val commonLimitations = listOf(
        "Same Android UID and permissions; process crash containment is not a sandbox.",
        "Bound to the client lifecycle, no persistent background-service or automatic replay guarantee.",
        "Native code must already be in the signed app; no device-side native downloads or installers.",
        "The runtime library is GPL-3.0-or-later; generated-app distribution requires a licensing review.",
    )
    fun limitations(engine: AppEngine): List<String> = commonLimitations + when (engine) {
        AppEngine.Node -> listOf(
            "Node 24 is upstream LTS, but Android is explicitly unsupported upstream and requires qualification.",
            "Bundled JavaScript packages only; npm, native addons, child processes, workers and inspector are disabled.",
            "Non-owning embedded Node environment: process.abort, process.chdir and other process-global controls are unavailable.",
            "small-icu has limited locale data; packaging does not establish compatibility with every Node package.",
        )
        AppEngine.Python -> listOf(
            "CPython 3.14 Android core profile, bundled pure-Python packages only; no pip or native wheels.",
            "sqlite3, ctypes, bz2, lzma, zstd, curses, tkinter and multiprocessing are not included.",
            "TLS certificate roots are not bundled; HTTPS callers must supply a reviewed CA file/context.",
        )
    }
}
