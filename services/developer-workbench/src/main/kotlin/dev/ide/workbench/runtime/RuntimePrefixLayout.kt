package dev.ide.workbench.runtime

import java.nio.file.Path

/** termux-exec 2.5's prefix buffer allows at most 89 UTF-8 bytes before the NUL. */
object RuntimePrefixLayout {
    fun prefix(filesDirectory: Path, packId: String): Path {
        require(packId.matches(Regex("termux-arm64-[0-9a-f]{16}"))) { "Unrecognized runtime pack identity" }
        val result = filesDirectory.resolve("dr/p").resolve(packId.substringAfterLast('-')).resolve("usr")
        require(result.isAbsolute && result.toString().toByteArray(Charsets.UTF_8).size < 90) {
            "App runtime prefix exceeds termux-exec's 89-byte path limit; this app ID/storage path is unsupported"
        }
        return result
    }
}
