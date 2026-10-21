package dev.ide.workbench.runtime

import java.nio.file.Files
import java.nio.file.Path

/**
 * Full PRoot command and mount layout builder (ported from `wkbin/taixu`'s `ProotCommandBuilder`
 * & `ProotMountLayout` + `Mangi-11/Eta`'s 16 KiB `libproot_exec.so` / `libproot_loader.so`).
 *
 * Configures:
 * - `--kill-on-exit`, `--link2symlink`, `-L`, `--sysvipc`, `--change-id=0:0`
 * - `PROOT_TMP_DIR` and `PROOT_L2S_DIR` (`.l2s` hardlink-to-symlink metadata store)
 * - `PROOT_LOADER` pointing to 16 KiB-aligned `libproot_loader.so` when present
 * - Bind mounts for `/dev`, `/proc`, `/sys`, `/apex`, `/linkerconfig/ld.config.txt`,
 *   `/data/dalvik-cache`, `/tmp`, `/workspace`, and `/root`
 */
object ProotCommandBuilder {

    data class ProotMountConfig(
        val prootBinary: Path,
        val rootfsDir: Path,
        val workDir: Path,
        val homeDir: Path,
        val tmpDir: Path,
        val nativeLibraryDir: Path? = null,
        val extraBinds: List<Pair<Path, String>> = emptyList(),
        val fakeRootId: Boolean = true,
        val sysvipc: Boolean = true,
        val link2symlink: Boolean = true,
    ) {
        val l2sDir: Path get() = rootfsDir.resolve(".l2s")
    }

    data class ProotLaunchPlan(
        val command: List<String>,
        val environment: Map<String, String>,
    )

    /**
     * Resolves the PRoot executable from either `nativeLibraryDir/libproot_exec.so` (16 KiB aligned)
     * or `prefix/bin/proot`.
     */
    fun findProotBinary(prefix: Path, nativeLibraryDir: Path?): Path? {
        if (nativeLibraryDir != null) {
            val nativeProot = nativeLibraryDir.resolve("libproot_exec.so")
            if (Files.isRegularFile(nativeProot)) return nativeProot
        }
        val prefixProot = prefix.resolve("bin/proot")
        if (Files.isRegularFile(prefixProot)) return prefixProot
        return null
    }

    fun build(
        config: ProotMountConfig,
        innerCommand: List<String>,
        baseEnvironment: Map<String, String> = emptyMap(),
    ): ProotLaunchPlan {
        require(innerCommand.isNotEmpty()) { "Inner command must not be empty" }
        Files.createDirectories(config.rootfsDir)
        Files.createDirectories(config.workDir)
        Files.createDirectories(config.homeDir)
        Files.createDirectories(config.tmpDir)
        Files.createDirectories(config.l2sDir)

        val args = mutableListOf<String>()
        args += config.prootBinary.toString()
        args += "--kill-on-exit"
        if (config.link2symlink) {
            args += "--link2symlink"
            args += "-L"
        }
        if (config.sysvipc) {
            args += "--sysvipc"
        }
        if (config.fakeRootId) {
            args += "--change-id=0:0"
        }

        // Core system bind mounts
        args += listOf("-b", "/dev")
        args += listOf("-b", "/proc")
        args += listOf("-b", "/sys")

        // Android runtime mounts if present on host device
        val optionalHostMounts = listOf(
            Path.of("/apex") to "/apex",
            Path.of("/linkerconfig/ld.config.txt") to "/linkerconfig/ld.config.txt",
            Path.of("/data/dalvik-cache") to "/data/dalvik-cache",
            Path.of("/system") to "/system",
        )
        for ((hostPath, guestPath) in optionalHostMounts) {
            if (Files.exists(hostPath)) {
                args += listOf("-b", "$hostPath:$guestPath")
            }
        }

        // Workspace, home, and tmp mounts
        args += listOf("-b", "${config.tmpDir}:/tmp")
        args += listOf("-b", "${config.homeDir}:/root")
        args += listOf("-b", "${config.workDir}:/workspace")

        for ((hostPath, guestPath) in config.extraBinds) {
            args += listOf("-b", "$hostPath:$guestPath")
        }

        args += listOf("-r", config.rootfsDir.toString())
        args += listOf("-w", "/workspace")
        args += innerCommand

        val env = LinkedHashMap<String, String>(baseEnvironment)
        env["PROOT_TMP_DIR"] = config.tmpDir.toString()
        env["PROOT_L2S_DIR"] = config.l2sDir.toString()
        env["PROOT_NO_SECCOMP"] = "1"
        env.putIfAbsent("HOME", "/root")
        env.putIfAbsent("TMPDIR", "/tmp")
        env.putIfAbsent("TERM", "xterm-256color")

        val loaderCandidate = config.nativeLibraryDir?.resolve("libproot_loader.so")
            ?.takeIf { Files.isRegularFile(it) }
            ?: config.rootfsDir.resolve("libexec/proot/loader").takeIf { Files.isRegularFile(it) }
        if (loaderCandidate != null) {
            env["PROOT_LOADER"] = loaderCandidate.toString()
        }

        val loader32Candidate = config.nativeLibraryDir?.resolve("libproot_loader32.so")
            ?.takeIf { Files.isRegularFile(it) }
            ?: config.rootfsDir.resolve("libexec/proot/loader32").takeIf { Files.isRegularFile(it) }
        if (loader32Candidate != null) {
            env["PROOT_LOADER_32"] = loader32Candidate.toString()
        }

        return ProotLaunchPlan(
            command = args,
            environment = env,
        )
    }
}
