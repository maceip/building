package dev.ide.model.impl.jdk

import dev.ide.model.PlatformKind
import dev.ide.model.impl.SdkData
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Derives a "core-Java" platform library from the bundled `android.jar` by stripping the Android
 * framework namespaces (`android.*`/`androidx.*`/`com.android.*`/`dalvik.*`) and keeping the standard
 * surface (`java.*`, `javax.*`, `org.w3c`/`org.xml`/`org.json`, …).
 *
 * This is the platform a Java/Kotlin CONSOLE app compiles, completes, and is analyzed against on-device.
 * The IDE never ships a JDK (running cheap on ART is the whole point), so there is no `rt.jar` to use; but
 * a console app runs on ART, whose `java.*` IS android.jar's `java.*`. So the correct core-Java surface is
 * exactly android.jar minus the `android.*` classes that must never appear in a non-Android module. The
 * result mirrors what Gradle gives a `java-library`: the JDK, not the Android SDK.
 *
 * The filtered jar is content-addressed under a cache dir and generated once; core-lambda-stubs
 * (`StringConcatFactory`/`LambdaMetafactory`, which android.jar omits) join the boot classpath separately.
 */
object CorePlatformProvider {

    /** The canonical name of the core-Java SDK in the workspace SDK table. */
    const val SDK_NAME = "core-java"

    /** Bumped when [EXCLUDED_PREFIXES] or core-Java stub synthesis changes, so a stale cached jar is regenerated. */
    private const val FILTER_VERSION = 2

    /** Fixed zip-entry time (1980-01-01) — reproducible output, and above the DOS-time floor. */
    private const val FIXED_TIME = 315_532_800_000L

    /** Class-path prefixes dropped from the core platform: the Android framework namespaces. */
    private val EXCLUDED_PREFIXES = listOf(
        "android/", "androidx/", "com/android/", "com/google/android/", "dalvik/",
    )

    /**
     * Build (or reuse) the core-Java [SdkData] derived from [androidJar]. [extraStubs] (e.g.
     * core-lambda-stubs) join the boot classpath as-is, after the filtered jar. The filtered jar is cached
     * under [cacheDir]. Returns null if [androidJar] can't be read.
     */
    fun coreJavaSdk(androidJar: Path, extraStubs: List<Path> = emptyList(), cacheDir: Path): SdkData? {
        val filtered = ensureFiltered(androidJar, cacheDir) ?: return null
        val boot = listOf(filtered.toAbsolutePath().normalize().toString()) +
            extraStubs.map { it.toAbsolutePath().normalize().toString() }
        return SdkData(SDK_NAME, boot, buildToolsPath = null, kind = PlatformKind.JVM)
    }

    /** Filter [androidJar] to a core-Java jar under [cacheDir], reusing a prior result. Null on failure. */
    fun ensureFiltered(androidJar: Path, cacheDir: Path): Path? {
        if (!Files.isRegularFile(androidJar)) return null
        val size = Files.size(androidJar)
        val out = cacheDir.resolve("$SDK_NAME-$size-v$FILTER_VERSION.jar")
        if (Files.isRegularFile(out) && Files.size(out) > 0) return out

        Files.createDirectories(cacheDir)
        val tmp = Files.createTempFile(cacheDir, "$SDK_NAME-", ".jar.tmp")
        try {
            ZipFile(androidJar.toFile()).use { zip ->
                ZipOutputStream(Files.newOutputStream(tmp)).use { zos ->
                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        val e = entries.nextElement()
                        if (e.isDirectory) continue
                        val name = e.name
                        if (name.startsWith("META-INF/")) continue          // no manifest/signatures on a platform stub
                        if (EXCLUDED_PREFIXES.any { name.startsWith(it) }) continue
                        zos.putNextEntry(ZipEntry(name).apply { time = FIXED_TIME })
                        if (name == "java/lang/ClassLoader.class") {
                            val raw = zip.getInputStream(e).use { it.readBytes() }
                            zos.write(patchClassLoaderBytecode(raw))
                        } else {
                            zip.getInputStream(e).use { it.copyTo(zos) }
                        }
                        zos.closeEntry()
                    }
                }
            }
            Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING)
            return out
        } catch (t: Throwable) {
            runCatching { Files.deleteIfExists(tmp) }
            return null
        }
    }

    /**
     * Produces a tiny (~2 KB) stub jar under [cacheDir] containing only the patched `java/lang/ClassLoader.class`
     * with `protected Object getClassLoadingLock(String className)` synthesized. Placed before `android.jar` on
     * the compile boot classpath so JVM library modules compiled inside an Android build graph (`AndroidBuildSystem`)
     * also resolve `ClassLoader.getClassLoadingLock(String)`.
     */
    fun ensureJdkCompatStubs(androidJar: Path, cacheDir: Path): Path? {
        if (!Files.isRegularFile(androidJar)) return null
        val size = Files.size(androidJar)
        val out = cacheDir.resolve("jdk-compat-stubs-$size-v$FILTER_VERSION.jar")
        if (Files.isRegularFile(out) && Files.size(out) > 0) return out

        Files.createDirectories(cacheDir)
        val tmp = Files.createTempFile(cacheDir, "jdk-compat-stubs-", ".jar.tmp")
        try {
            ZipFile(androidJar.toFile()).use { zip ->
                val entry = zip.getEntry("java/lang/ClassLoader.class") ?: return null
                val raw = zip.getInputStream(entry).use { it.readBytes() }
                val patched = patchClassLoaderBytecode(raw)
                ZipOutputStream(Files.newOutputStream(tmp)).use { zos ->
                    zos.putNextEntry(ZipEntry("java/lang/ClassLoader.class").apply { time = FIXED_TIME })
                    zos.write(patched)
                    zos.closeEntry()
                }
            }
            Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING)
            return out
        } catch (t: Throwable) {
            runCatching { Files.deleteIfExists(tmp) }
            return null
        }
    }

    /**
     * Synthesizes `protected Object getClassLoadingLock(String className)` on `java/lang/ClassLoader.class`
     * when absent from `android.jar` so standard Java 7+ `ClassLoader`/`URLClassLoader` subclasses in JVM
     * modules compile cleanly on-device against `core-java`.
     */
    internal fun patchClassLoaderBytecode(bytes: ByteArray): ByteArray = runCatching {
        val dis = DataInputStream(ByteArrayInputStream(bytes))
        val magic = dis.readInt()
        if (magic != 0xCAFEBABE.toInt()) return bytes
        val minor = dis.readUnsignedShort()
        val major = dis.readUnsignedShort()
        val cpCount = dis.readUnsignedShort()
        val baos = ByteArrayOutputStream(bytes.size + 128)
        val dos = DataOutputStream(baos)
        dos.writeInt(magic)
        dos.writeShort(minor)
        dos.writeShort(major)
        dos.writeShort(cpCount + 3)

        var i = 1
        while (i < cpCount) {
            val tag = dis.readUnsignedByte()
            dos.writeByte(tag)
            when (tag) {
                1 -> {
                    val len = dis.readUnsignedShort()
                    val utf = ByteArray(len)
                    dis.readFully(utf)
                    if (String(utf, Charsets.UTF_8) == "getClassLoadingLock") return bytes
                    dos.writeShort(len)
                    dos.write(utf)
                }
                3, 4, 9, 10, 11, 12, 17, 18 -> {
                    val buf = ByteArray(4)
                    dis.readFully(buf)
                    dos.write(buf)
                }
                5, 6 -> {
                    val buf = ByteArray(8)
                    dis.readFully(buf)
                    dos.write(buf)
                    i++
                }
                7, 8, 16, 19, 20 -> {
                    val buf = ByteArray(2)
                    dis.readFully(buf)
                    dos.write(buf)
                }
                15 -> {
                    val buf = ByteArray(3)
                    dis.readFully(buf)
                    dos.write(buf)
                }
                else -> return bytes
            }
            i++
        }

        val nameIdx = cpCount
        val descIdx = cpCount + 1
        val codeIdx = cpCount + 2
        dos.writeByte(1); dos.writeUTF("getClassLoadingLock")
        dos.writeByte(1); dos.writeUTF("(Ljava/lang/String;)Ljava/lang/Object;")
        dos.writeByte(1); dos.writeUTF("Code")

        val header = ByteArray(6)
        dis.readFully(header)
        dos.write(header)

        val ifaceCount = dis.readUnsignedShort()
        dos.writeShort(ifaceCount)
        val ifaces = ByteArray(ifaceCount * 2)
        dis.readFully(ifaces)
        dos.write(ifaces)

        val fieldsCount = dis.readUnsignedShort()
        dos.writeShort(fieldsCount)
        repeat(fieldsCount) { copyMember(dis, dos) }

        val methodsCount = dis.readUnsignedShort()
        dos.writeShort(methodsCount + 1)
        repeat(methodsCount) { copyMember(dis, dos) }

        // protected Object getClassLoadingLock(String) { return this; }
        dos.writeShort(0x0004) // ACC_PROTECTED
        dos.writeShort(nameIdx)
        dos.writeShort(descIdx)
        dos.writeShort(1) // 1 attribute (Code)
        dos.writeShort(codeIdx)
        dos.writeInt(14) // attribute_length
        dos.writeShort(1) // max_stack
        dos.writeShort(2) // max_locals
        dos.writeInt(2) // code_length
        dos.writeByte(0x2a) // aload_0
        dos.writeByte(0xb0) // areturn
        dos.writeShort(0) // exception_table_length
        dos.writeShort(0) // attributes_count

        dis.copyTo(dos)
        dos.flush()
        baos.toByteArray()
    }.getOrDefault(bytes)

    private fun copyMember(dis: DataInputStream, dos: DataOutputStream) {
        val head = ByteArray(6)
        dis.readFully(head)
        dos.write(head)
        val attrCount = dis.readUnsignedShort()
        dos.writeShort(attrCount)
        repeat(attrCount) {
            val attrName = dis.readUnsignedShort()
            val attrLen = dis.readInt()
            dos.writeShort(attrName)
            dos.writeInt(attrLen)
            val info = ByteArray(attrLen)
            dis.readFully(info)
            dos.write(info)
        }
    }
}
