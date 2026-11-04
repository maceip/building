package dev.ide.android.apk

import dev.ide.kotlin.classfile.ZipArchive

/**
 * An `.apk` as the guest sees it: a ZIP of `classes*.dex`, `AndroidManifest.xml`, resources and
 * `lib/<abi>/` native libraries. Reads only what the launcher needs; nothing is extracted until a run asks for it.
 */
class ApkPackage private constructor(
    val path: String?,
    val archive: ZipArchive,
) {
    val dexEntries: List<String>
        get() = archive.entries.map { it.name }.filter {
            it == "classes.dex" || (it.startsWith("classes") && it.endsWith(".dex"))
        }.sorted()

    fun readDex(name: String): ByteArray? = archive.entry(name)?.let { archive.read(it) }

    fun readManifestBytes(): ByteArray? = archive.entry("AndroidManifest.xml")?.let { archive.read(it) }

    fun hasNativeLibs(): Boolean = archive.entries.any { it.name.startsWith("lib/") && it.name.endsWith(".so") }

    fun nativeAbis(): List<String> = archive.entries
        .map { it.name }
        .filter { it.startsWith("lib/") && it.endsWith(".so") }
        .map { it.removePrefix("lib/").substringBefore('/') }
        .distinct()
        .sorted()

    companion object {
        fun open(bytes: ByteArray, path: String? = null): ApkPackage? {
            val zip = ZipArchive.open(bytes) ?: return null
            return ApkPackage(path, zip)
        }

        fun openFile(readBytes: () -> ByteArray?, path: String? = null): ApkPackage? =
            open(readBytes() ?: return null, path)
    }
}
