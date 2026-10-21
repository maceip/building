package dev.ide.workbench.runtime

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * Runtime environment doctor and self-healing toolchain repairer (ported from `wkbin/taixu`'s
 * `EnvironmentDoctor` & `ToolchainRepairer`).
 *
 * Diagnoses and automatically repairs common on-device runtime failure modes:
 * 1. Broken or stale symlinks in `<prefix>/bin` and `<prefix>/lib` (e.g., when Android updates the APK's
 *    randomized `/data/app/~~.../lib/arm64` directory).
 * 2. Missing executable permissions (`chmod +x`) on binaries and CLI scripts in `<prefix>/bin`,
 *    `<home>/npm-global/bin`, and installed extension `bin/` directories.
 * 3. Unrewritten `/data/data/com.termux/files/usr` shebangs in scripts.
 * 4. Missing runtime directories (`tmp`, `.l2s`, `home`, `npm-global/bin`, `py-shims`).
 * 5. Missing musl dynamic loader symlink (`ld-musl-aarch64.so.1 -> <nativeDir>/libldmusl.so`).
 */
object EnvironmentDoctor {

    enum class IssueKind {
        BROKEN_SYMLINK,
        STALE_NATIVE_LIB_SYMLINK,
        NON_EXECUTABLE_BINARY,
        UNREWRITTEN_TERMUX_SHEBANG,
        MISSING_DIRECTORY,
        MISSING_MUSL_LINK,
    }

    data class EnvironmentIssue(
        val kind: IssueKind,
        val path: Path,
        val description: String,
        val repaired: Boolean = false,
    )

    data class DiagnosisReport(
        val healthy: Boolean,
        val issues: List<EnvironmentIssue>,
        val checkedBinaries: Int,
        val checkedSymlinks: Int,
    ) {
        val repairedCount: Int get() = issues.count { it.repaired }
        val unresolvedCount: Int get() = issues.count { !it.repaired }

        fun summaryText(): String = buildString {
            appendLine("Runtime Environment Doctor: ${if (healthy) "HEALTHY" else "ISSUES_REMAIN"}")
            appendLine("Checked binaries: $checkedBinaries, Checked symlinks: $checkedSymlinks")
            appendLine("Total issues found: ${issues.size} (repaired: $repairedCount, unresolved: $unresolvedCount)")
            for (issue in issues) {
                val status = if (issue.repaired) "[REPAIRED]" else "[UNRESOLVED]"
                appendLine("- $status ${issue.kind}: ${issue.path} — ${issue.description}")
            }
        }.trimEnd()
    }

    fun diagnoseAndRepair(
        prefix: Path,
        home: Path,
        nativeDir: Path? = null,
        extensionsRoot: Path? = null,
        repair: Boolean = true,
    ): DiagnosisReport {
        val issues = mutableListOf<EnvironmentIssue>()
        var checkedBinaries = 0
        var checkedSymlinks = 0

        // 1. Ensure essential directories exist
        val requiredDirs = buildList {
            add(prefix)
            add(prefix.resolve("bin"))
            add(prefix.resolve("lib"))
            add(home)
            add(home.resolve("home"))
            add(home.resolve("tmp"))
            add(home.resolve("npm-global/bin"))
            add(home.resolve("py-shims"))
            add(prefix.resolve(".l2s"))
        }
        for (dir in requiredDirs) {
            if (!Files.isDirectory(dir)) {
                var fixed = false
                if (repair) {
                    fixed = runCatching {
                        Files.createDirectories(dir)
                        true
                    }.getOrDefault(false)
                }
                issues += EnvironmentIssue(
                    kind = IssueKind.MISSING_DIRECTORY,
                    path = dir,
                    description = "Required runtime directory was missing",
                    repaired = fixed,
                )
            }
        }

        // 2. Check Musl & Bionic SONAME links if nativeDir is provided
        if (nativeDir != null && Files.isDirectory(nativeDir)) {
            val muslTarget = nativeDir.resolve("libldmusl.so")
            val muslLink = prefix.resolve("bin/ld-musl-aarch64.so.1")
            if (Files.isRegularFile(muslTarget) && !Files.exists(muslLink)) {
                var fixed = false
                if (repair) {
                    fixed = runCatching {
                        MuslAndBionicRuntimeLinker.ensureLinks(prefix, nativeDir)
                        Files.exists(muslLink)
                    }.getOrDefault(false)
                }
                issues += EnvironmentIssue(
                    kind = IssueKind.MISSING_MUSL_LINK,
                    path = muslLink,
                    description = "Musl interpreter symlink ld-musl-aarch64.so.1 was missing",
                    repaired = fixed,
                )
            } else if (repair) {
                runCatching { MuslAndBionicRuntimeLinker.ensureLinks(prefix, nativeDir) }
            }
        }

        // 3. Collect all bin and lib directories (including installed extensions)
        val binDirs = mutableListOf(
            prefix.resolve("bin"),
            home.resolve("npm-global/bin"),
        )
        val libDirs = mutableListOf(
            prefix.resolve("lib"),
        )
        if (extensionsRoot != null && Files.isDirectory(extensionsRoot)) {
            runCatching {
                Files.list(extensionsRoot).use { stream ->
                    stream.filter { Files.isDirectory(it) && !it.fileName.toString().startsWith(".") }.forEach { extDir ->
                        binDirs.add(extDir.resolve("bin"))
                        libDirs.add(extDir.resolve("lib"))
                    }
                }
            }
        }

        // 4. Inspect symlinks across bin/ and lib/
        for (dir in (binDirs + libDirs)) {
            if (!Files.isDirectory(dir)) continue
            val entries = runCatching { Files.list(dir).use { it.toList() } }.getOrDefault(emptyList())
            for (entry in entries) {
                if (Files.isSymbolicLink(entry)) {
                    checkedSymlinks++
                    val target = runCatching { Files.readSymbolicLink(entry) }.getOrNull()
                    val resolved = if (target != null) entry.parent.resolve(target).normalize() else null
                    if (resolved == null || !Files.exists(resolved)) {
                        var fixed = false
                        val fileName = target?.fileName?.toString()
                        if (repair && nativeDir != null && fileName != null) {
                            val candidateInNative = nativeDir.resolve(fileName)
                            if (Files.isRegularFile(candidateInNative)) {
                                fixed = runCatching {
                                    Files.deleteIfExists(entry)
                                    Files.createSymbolicLink(entry, candidateInNative)
                                    true
                                }.getOrDefault(false)
                            }
                        }
                        if (!fixed && repair) {
                            // Remove dead symlink if it points nowhere recoverable
                            fixed = runCatching {
                                Files.deleteIfExists(entry)
                                true
                            }.getOrDefault(false)
                        }
                        issues += EnvironmentIssue(
                            kind = if (target?.toString()?.contains("/lib/") == true) {
                                IssueKind.STALE_NATIVE_LIB_SYMLINK
                            } else {
                                IssueKind.BROKEN_SYMLINK
                            },
                            path = entry,
                            description = "Dangling symlink pointing to '${target ?: "unknown"}'",
                            repaired = fixed,
                        )
                    }
                }
            }
        }

        // 5. Inspect executable bits and shebangs in all bin/ directories
        for (binDir in binDirs) {
            if (!Files.isDirectory(binDir)) continue
            val entries = runCatching { Files.list(binDir).use { it.toList() } }.getOrDefault(emptyList())
            for (entry in entries) {
                if (!Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) continue
                checkedBinaries++

                if (!Files.isExecutable(entry)) {
                    var fixed = false
                    if (repair) {
                        fixed = runCatching { entry.toFile().setExecutable(true, false) }.getOrDefault(false)
                    }
                    issues += EnvironmentIssue(
                        kind = IssueKind.NON_EXECUTABLE_BINARY,
                        path = entry,
                        description = "Binary/script in bin directory lacked execute permission",
                        repaired = fixed,
                    )
                }

                if (hasUnrewrittenTermuxShebang(entry)) {
                    var fixed = false
                    if (repair) {
                        fixed = runCatching {
                            TermuxExtensionManager.rewriteShebangIfNeeded(entry, prefix)
                        }.getOrDefault(false)
                    }
                    issues += EnvironmentIssue(
                        kind = IssueKind.UNREWRITTEN_TERMUX_SHEBANG,
                        path = entry,
                        description = "Script had hardcoded /data/data/com.termux/files/usr shebang",
                        repaired = fixed,
                    )
                }
            }
        }

        val unresolved = issues.count { !it.repaired }
        return DiagnosisReport(
            healthy = unresolved == 0,
            issues = issues,
            checkedBinaries = checkedBinaries,
            checkedSymlinks = checkedSymlinks,
        )
    }

    private fun hasUnrewrittenTermuxShebang(file: Path): Boolean {
        val size = runCatching { Files.size(file) }.getOrDefault(0L)
        if (size < 4L || size > 2L * 1024 * 1024) return false
        val head = ByteArray(minOf(size.toInt(), 256))
        val read = runCatching { Files.newInputStream(file).use { it.read(head) } }.getOrDefault(-1)
        if (read < 4 || head[0] != '#'.code.toByte() || head[1] != '!'.code.toByte()) return false
        val lineEnd = head.indexOf('\n'.code.toByte()).let { if (it < 0) read else it }
        val firstLine = String(head, 0, lineEnd, Charsets.UTF_8)
        return firstLine.contains("/data/data/com.termux/files/usr")
    }
}
