package dev.ide.workbench.runtime

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * Manages the hybrid Bionic + Musl `nativeLibraryDir` runtime linker strategy (from `Past-da-king/agent`).
 *
 * Android 15+ enforces strict W^X (`SELinux app_data_file` noexec), so ELF binaries and shared libraries
 * must reside in the read+exec `nativeLibraryDir` (`lib*.so`).
 *
 * This helper:
 * 1. Maintains the canonical SONAME flattening map (`libz.so.1 -> libtxz.so`, `libcrypto.so.3 -> libcrypto3.so`,
 *    `ld-musl-aarch64.so.1 -> libldmusl.so`, etc.).
 * 2. Materializes the musl interpreter symlink (`<prefix>/bin/ld-musl-aarch64.so.1 -> <nativeDir>/libldmusl.so`)
 *    and SONAME alias symlinks in `<prefix>/lib/` when the corresponding `lib*.so` payloads exist in `nativeDir`.
 * 3. Inspects ELF `PT_INTERP` headers so musl-linked binaries (`claude`, `codex`, `rg`, Alpine tools) can be
 *    launched directly via `libldmusl.so --library-path <prefix>/lib:<nativeDir> <binary>` without PRoot overhead.
 */
object MuslAndBionicRuntimeLinker {

    /** Canonical mapping from upstream Linux/Termux SONAMEs to flattened Android `lib*.so` names. */
    val FLATTENED_SONAME_MAP: Map<String, String> = linkedMapOf(
        "libcrypto.so.3" to "libcrypto3.so",
        "libssl.so.3" to "libssl3.so",
        "libsqlite3.so.0" to "libsqlite3.so",
        "libz.so.1" to "libtxz.so",
        "libgcc_s.so.1" to "libmuslgcc.so",
        "libstdc++.so.6" to "libmuslstdcpp.so",
        "ld-musl-aarch64.so.1" to "libldmusl.so",
    )

    enum class ElfRuntimeFlavor {
        BIONIC,
        MUSL,
        STATIC_OR_UNKNOWN,
        NOT_ELF,
    }

    data class ElfInspection(
        val flavor: ElfRuntimeFlavor,
        val interpreter: String? = null,
        val maxPageAlignment: Long = 0L,
    ) {
        val is16KiBAligned: Boolean
            get() = maxPageAlignment >= 16384L
    }

    data class LinkerProvisionResult(
        val muslLoaderLink: Path?,
        val createdLibAliases: List<Path>,
    )

    /**
     * Ensures the musl dynamic loader symlink and SONAME compatibility links exist inside [prefix]
     * pointing to the verified `lib*.so` files inside [nativeDir].
     */
    fun ensureLinks(prefix: Path, nativeDir: Path): LinkerProvisionResult {
        val binDir = prefix.resolve("bin")
        val libDir = prefix.resolve("lib")
        Files.createDirectories(binDir)
        Files.createDirectories(libDir)

        var muslLoaderLink: Path? = null
        val muslTarget = nativeDir.resolve("libldmusl.so")
        if (Files.isRegularFile(muslTarget)) {
            val linkPath = binDir.resolve("ld-musl-aarch64.so.1")
            ensureSymlink(linkPath, muslTarget)
            val libLinkPath = libDir.resolve("ld-musl-aarch64.so.1")
            ensureSymlink(libLinkPath, muslTarget)
            muslLoaderLink = linkPath
        }

        val createdAliases = mutableListOf<Path>()
        for ((soname, flattenedName) in FLATTENED_SONAME_MAP) {
            val target = nativeDir.resolve(flattenedName)
            if (Files.isRegularFile(target)) {
                val aliasPath = libDir.resolve(soname)
                ensureSymlink(aliasPath, target)
                createdAliases.add(aliasPath)
            }
        }

        return LinkerProvisionResult(
            muslLoaderLink = muslLoaderLink,
            createdLibAliases = createdAliases,
        )
    }

    /**
     * Wraps [command] with the musl dynamic loader if the target executable is a musl-linked ELF binary
     * and `libldmusl.so` is available in [nativeDir] or [prefix].
     */
    fun wrapCommandIfNeeded(
        command: List<String>,
        prefix: Path,
        nativeDir: Path,
        extraLibDirs: List<Path> = emptyList(),
    ): List<String> {
        if (command.isEmpty()) return command
        val executable = runCatching { Path.of(command.first()) }.getOrNull() ?: return command
        if (!Files.isRegularFile(executable)) return command
        val inspection = inspectElf(executable)
        if (inspection.flavor != ElfRuntimeFlavor.MUSL) return command

        val muslLoader = nativeDir.resolve("libldmusl.so").takeIf { Files.isRegularFile(it) }
            ?: prefix.resolve("bin/ld-musl-aarch64.so.1").takeIf { Files.exists(it) }
            ?: return command

        val libraryPath = buildList {
            add(prefix.resolve("lib").toString())
            add(nativeDir.toString())
            extraLibDirs.forEach { add(it.toString()) }
        }.distinct().joinToString(":")

        return listOf(
            muslLoader.toString(),
            "--library-path",
            libraryPath,
        ) + command
    }

    /**
     * Parses the 64-bit ELF header and program headers of [file] to identify its `PT_INTERP` dynamic linker
     * and maximum `PT_LOAD` `p_align` value (for Android 15/16 16 KiB page alignment checks).
     */
    fun inspectElf(file: Path): ElfInspection {
        if (!Files.isRegularFile(file)) return ElfInspection(ElfRuntimeFlavor.NOT_ELF)
        val bytes = runCatching {
            Files.newInputStream(file).use { input ->
                val buf = ByteArray(64 * 1024)
                val read = input.read(buf)
                if (read <= 0) return ElfInspection(ElfRuntimeFlavor.NOT_ELF)
                if (read < buf.size) buf.copyOf(read) else buf
            }
        }.getOrNull() ?: return ElfInspection(ElfRuntimeFlavor.NOT_ELF)

        if (bytes.size < 64) return ElfInspection(ElfRuntimeFlavor.NOT_ELF)
        if (bytes[0] != 0x7F.toByte() ||
            bytes[1] != 'E'.code.toByte() ||
            bytes[2] != 'L'.code.toByte() ||
            bytes[3] != 'F'.code.toByte()
        ) {
            return ElfInspection(ElfRuntimeFlavor.NOT_ELF)
        }

        val is64Bit = bytes[4].toInt() == 2
        if (!is64Bit) return ElfInspection(ElfRuntimeFlavor.STATIC_OR_UNKNOWN)

        val littleEndian = bytes[5].toInt() == 1
        val buf = ByteBuffer.wrap(bytes).order(if (littleEndian) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN)

        val phoff = buf.getLong(32)
        val phentsize = buf.getShort(54).toInt() and 0xFFFF
        val phnum = buf.getShort(56).toInt() and 0xFFFF

        if (phoff <= 0 || phentsize < 56 || phnum <= 0) {
            return ElfInspection(ElfRuntimeFlavor.STATIC_OR_UNKNOWN)
        }

        var interpreter: String? = null
        var maxLoadAlign = 0L

        for (i in 0 until phnum) {
            val offset = (phoff + i.toLong() * phentsize).toInt()
            if (offset + 56 > bytes.size) break
            val pType = buf.getInt(offset)
            val pOffset = buf.getLong(offset + 8)
            val pFilesz = buf.getLong(offset + 32)
            val pAlign = buf.getLong(offset + 48)

            // PT_LOAD == 1, PT_INTERP == 3
            if (pType == 1 && pAlign > maxLoadAlign) {
                maxLoadAlign = pAlign
            } else if (pType == 3 && pOffset >= 0 && pFilesz > 0 && pOffset + pFilesz <= bytes.size) {
                val start = pOffset.toInt()
                val len = pFilesz.toInt()
                val raw = String(bytes, start, len, Charsets.UTF_8).trimEnd('\u0000', ' ', '\n', '\r')
                interpreter = raw
            }
        }

        val flavor = when {
            interpreter == null -> ElfRuntimeFlavor.STATIC_OR_UNKNOWN
            interpreter.contains("musl", ignoreCase = true) -> ElfRuntimeFlavor.MUSL
            interpreter.contains("linker", ignoreCase = true) ||
                interpreter.contains("android", ignoreCase = true) -> ElfRuntimeFlavor.BIONIC
            else -> ElfRuntimeFlavor.STATIC_OR_UNKNOWN
        }
        return ElfInspection(
            flavor = flavor,
            interpreter = interpreter,
            maxPageAlignment = maxLoadAlign,
        )
    }

    private fun ensureSymlink(linkPath: Path, target: Path) {
        Files.createDirectories(linkPath.parent)
        if (Files.isSymbolicLink(linkPath)) {
            val current = runCatching { Files.readSymbolicLink(linkPath) }.getOrNull()
            if (current == target) return
        }
        if (Files.isDirectory(linkPath, LinkOption.NOFOLLOW_LINKS)) return
        Files.deleteIfExists(linkPath)
        Files.createSymbolicLink(linkPath, target)
    }
}
