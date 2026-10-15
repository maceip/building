// SPDX-License-Identifier: GPL-3.0-or-later
package dev.ide.workbench.appengine

import java.nio.file.Files
import java.nio.file.Path

/**
 * Packaging-time rejection of source shapes the embedded engines cannot run.
 * Detection is conservative: only capabilities the engine descriptor and
 * [AppEngineCapabilities] already declare disabled or not included are named.
 * Unrecognized source is packaged unchanged; the check never rewrites source.
 */
object AppEngineSourceCheck {
    private const val MAX_SCANNED_BYTES = 1_048_576L

    private val nodeRules: List<Pair<Regex, String>> = listOf(
        Regex("""(?:require\s*\(\s*['"]|from\s+['"]|import\s*\(\s*['"])(?:node:)?child_process(?:['"]|/)""") to
            "child_process: child processes are disabled in generated Node apps",
        Regex("""(?:require\s*\(\s*['"]|from\s+['"]|import\s*\(\s*['"])(?:node:)?worker_threads(?:['"]|/)""") to
            "worker_threads: workers are disabled in generated Node apps",
        Regex("""(?:require\s*\(\s*['"]|from\s+['"]|import\s*\(\s*['"])(?:node:)?inspector(?:['"]|/)""") to
            "inspector: the inspector is disabled in generated Node apps",
        Regex("""['"][^'"]*\.node['"]""") to
            "native addon (.node): native addons are disabled in generated Node apps",
    )

    /** Modules the bundled CPython profile does not include; see AppEngineCapabilities. */
    private val pythonExcluded = setOf(
        "sqlite3", "_sqlite3", "ctypes", "_ctypes", "multiprocessing", "tkinter",
        "curses", "_curses", "bz2", "_bz2", "lzma", "_lzma", "zstd", "pip",
    )
    private val pythonImport = Regex("""^\s*(?:import\s+([A-Za-z_][\w.]*)|from\s+([A-Za-z_][\w.]*)\s+import\b)""")

    fun check(engine: AppEngine, scripts: List<Pair<String, Path>>) {
        val findings = mutableListOf<String>()
        for ((name, file) in scripts) {
            val extension = name.substringAfterLast('.', "").lowercase()
            if (extension !in engine.extensions) continue
            if (Files.size(file) > MAX_SCANNED_BYTES) continue
            val text = Files.readString(file)
            when (engine) {
                AppEngine.Node -> nodeRules.forEach { (pattern, label) ->
                    if (pattern.containsMatchIn(text)) findings += "$name: $label"
                }
                AppEngine.Python -> text.lineSequence().forEach { line ->
                    val match = pythonImport.find(line) ?: return@forEach
                    val module = (match.groupValues[1].ifEmpty { match.groupValues[2] }).substringBefore('.')
                    if (module in pythonExcluded) {
                        findings += "$name: $module: module is not included in the bundled Python profile"
                    }
                }
            }
        }
        require(findings.isEmpty()) {
            "Unsupported generated-app capability:\n" + findings.distinct().joinToString("\n")
        }
    }
}
