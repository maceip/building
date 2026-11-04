package dev.ide.model.impl.jdk

import dev.ide.testkit.withTempDir
import dev.ide.model.PlatformKind
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CorePlatformProviderTest {

    /** A stand-in android.jar with a mix of Android-framework and standard-Java entries. */
    private fun fakeAndroidJar(dir: Path): Path {
        val jar = dir.resolve("android.jar")
        ZipOutputStream(Files.newOutputStream(jar)).use { zos ->
            for (name in listOf(
                "android/app/Activity.class",
                "android/util/Log.class",
                "androidx/core/Foo.class",
                "com/android/internal/Bar.class",
                "dalvik/system/DexFile.class",
                "com/google/android/Baz.class",
                "java/lang/Object.class",
                "java/util/List.class",
                "javax/net/SocketFactory.class",
                "org/json/JSONObject.class",
                "META-INF/MANIFEST.MF",
            )) {
                zos.putNextEntry(ZipEntry(name)); zos.write(name.toByteArray()); zos.closeEntry()
            }
        }
        return jar
    }

    private fun entryNames(jar: Path): Set<String> =
        ZipFile(jar.toFile()).use { z -> z.entries().asSequence().map { it.name }.toSet() }

    @Test
    fun stripsAndroidNamespacesKeepsStandardJava() {
        withTempDir("core-platform") { dir ->
            val androidJar = fakeAndroidJar(dir)
            val cache = dir.resolve("cache")
            val sdk = CorePlatformProvider.coreJavaSdk(androidJar, extraStubs = emptyList(), cacheDir = cache)
            assertNotNull(sdk)
            assertEquals(CorePlatformProvider.SDK_NAME, sdk.name)
            assertEquals(PlatformKind.JVM, sdk.kind)

            val filtered = Path.of(sdk.bootClasspath.single())
            val names = entryNames(filtered)

            // Android framework namespaces are gone.
            assertFalse(names.any { it.startsWith("android/") }, "android/* leaked: $names")
            assertFalse(names.any { it.startsWith("androidx/") })
            assertFalse(names.any { it.startsWith("com/android/") })
            assertFalse(names.any { it.startsWith("com/google/android/") })
            assertFalse(names.any { it.startsWith("dalvik/") })
            assertFalse(names.any { it.startsWith("META-INF/") })

            // Standard-Java surface survives.
            assertTrue("java/lang/Object.class" in names)
            assertTrue("java/util/List.class" in names)
            assertTrue("javax/net/SocketFactory.class" in names)
            assertTrue("org/json/JSONObject.class" in names)
        }
    }

    @Test
    fun cachesByContentSoSecondCallReusesTheJar() {
        withTempDir("core-platform-cache") { dir ->
            val androidJar = fakeAndroidJar(dir)
            val cache = dir.resolve("cache")
            val first = CorePlatformProvider.ensureFiltered(androidJar, cache)
            val second = CorePlatformProvider.ensureFiltered(androidJar, cache)
            assertNotNull(first)
            assertEquals(first, second, "second call should reuse the cached jar path")
        }
    }

    @Test
    fun extraStubsJoinBootClasspathAfterFilteredJar() {
        withTempDir("core-platform-stubs") { dir ->
            val androidJar = fakeAndroidJar(dir)
            val stub = Files.createFile(dir.resolve("core-lambda-stubs.jar"))
            val sdk = CorePlatformProvider.coreJavaSdk(androidJar, extraStubs = listOf(stub), cacheDir = dir.resolve("cache"))
            assertNotNull(sdk)
            assertEquals(2, sdk.bootClasspath.size)
            assertTrue(sdk.bootClasspath[0].endsWith(".jar"))
            assertEquals(stub.toAbsolutePath().normalize().toString(), sdk.bootClasspath[1])
        }
    }

    @Test
    fun patchesClassLoaderWithGetClassLoadingLock() {
        // Minimal valid classfile for DummyClass without getClassLoadingLock
        val baos = java.io.ByteArrayOutputStream()
        java.io.DataOutputStream(baos).use { dos ->
            dos.writeInt(0xCAFEBABE.toInt())
            dos.writeShort(0) // minor
            dos.writeShort(52) // major (Java 8)
            dos.writeShort(5) // cpCount = 5 (indices 1..4)
            dos.writeByte(1); dos.writeUTF("java/lang/ClassLoader") // #1
            dos.writeByte(7); dos.writeShort(1) // #2 Class(#1)
            dos.writeByte(1); dos.writeUTF("java/lang/Object") // #3
            dos.writeByte(7); dos.writeShort(3) // #4 Class(#3)
            dos.writeShort(0x0421) // ACC_PUBLIC | ACC_SUPER | ACC_ABSTRACT
            dos.writeShort(2) // this_class = #2
            dos.writeShort(4) // super_class = #4
            dos.writeShort(0) // interfaces_count = 0
            dos.writeShort(0) // fields_count = 0
            dos.writeShort(0) // methods_count = 0
            dos.writeShort(0) // attributes_count = 0
        }
        val raw = baos.toByteArray()
        val patched = CorePlatformProvider.patchClassLoaderBytecode(raw)
        assertTrue(patched.size > raw.size, "patched bytecode should be larger than original")
        assertTrue(String(patched, Charsets.ISO_8859_1).contains("getClassLoadingLock"))
        // Idempotent when called a second time
        val second = CorePlatformProvider.patchClassLoaderBytecode(patched)
        assertEquals(patched.size, second.size)

        withTempDir("jdk-compat-stubs") { dir ->
            val androidJar = dir.resolve("android.jar")
            ZipOutputStream(Files.newOutputStream(androidJar)).use { zos ->
                zos.putNextEntry(ZipEntry("java/lang/ClassLoader.class"))
                zos.write(raw)
                zos.closeEntry()
            }
            val stubJar = CorePlatformProvider.ensureJdkCompatStubs(androidJar, dir.resolve("cache"))
            assertNotNull(stubJar)
            assertEquals(setOf("java/lang/ClassLoader.class"), entryNames(stubJar))
            val stubBytes = ZipFile(stubJar.toFile()).use { z ->
                z.getInputStream(z.getEntry("java/lang/ClassLoader.class")).use { it.readBytes() }
            }
            assertTrue(String(stubBytes, Charsets.ISO_8859_1).contains("getClassLoadingLock"))
        }
    }
}
