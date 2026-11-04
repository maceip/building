package dev.ide.workbench.runtime

import java.nio.file.Files
import java.nio.file.Path

/** Explicit Java-8-compatible calls: Files.readString/writeString do not exist on supported ART. */
object RuntimeFiles {
    fun readUtf8(path: Path): String = String(Files.readAllBytes(path), Charsets.UTF_8)
    fun writeUtf8(path: Path, text: String): Path = Files.write(path, text.toByteArray(Charsets.UTF_8))
}
