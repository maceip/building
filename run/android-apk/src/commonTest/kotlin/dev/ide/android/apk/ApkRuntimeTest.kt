package dev.ide.android.apk

import dev.ide.kotlin.classfile.ZipArchive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ApkRuntimeTest {

    @Test
    fun textManifestPackageAndActivity() {
        val xml = """
            <manifest package="com.example.demo">
              <application android:label="Demo">
                <activity android:name=".MainActivity">
                  <intent-filter>
                    <action android:name="android.intent.action.MAIN" />
                    <category android:name="android.intent.category.LAUNCHER" />
                  </intent-filter>
                </activity>
              </application>
            </manifest>
        """.trimIndent().encodeToByteArray()
        val m = ManifestParser.parse(xml)
        assertEquals("com.example.demo", m.packageName)
        assertEquals("com.example.demo.MainActivity", m.launchActivity)
    }

    @Test
    fun apkWithoutDexReportsMissingClasses() {
        // A zip that looks like an APK but has no classes.dex: the runtime must say so, not crash.
        val zip = zipOf(
            "AndroidManifest.xml" to """<manifest package="com.empty"><application/></manifest>""".encodeToByteArray(),
        )
        val result = ApkRuntime().launch(zip)
        assertEquals(false, result.ok)
        assertTrue(result.message.contains("no classes.dex"), result.message)
        assertEquals("com.empty", result.packageName)
    }

    @Test
    fun dexHeaderMustBeRecognised() {
        val notDex = ByteArray(0x70)
        assertTrue(DexFile.parse(notDex).isFailure)
    }

    @Test
    fun apkBuilderSynthesizesRunnableApkAndExecutesOnCreate() {
        val spec = ApkBuilder.parseProjectSpec(
            fallbackName = "BitChord",
            manifestXml = """
                <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                  <application android:name=".BitChordApplication" android:label="@string/app_name">
                    <activity android:name=".MainActivity" android:exported="true">
                      <intent-filter>
                        <action android:name="android.intent.action.MAIN" />
                        <category android:name="android.intent.category.APP_MUSIC" />
                      </intent-filter>
                    </activity>
                  </application>
                </manifest>
            """.trimIndent(),
            gradleTexts = listOf(
                """
                android {
                    namespace = "com.music.bitchord"
                    defaultConfig {
                        applicationId = "com.music.bitchord"
                    }
                }
                """.trimIndent(),
            ),
            stringsXml = """<resources><string name="app_name">BitChord</string></resources>""",
            kotlinFileNames = listOf("MainActivity.kt", "HomeScreen.kt", "PlayerScreen.kt"),
        )
        assertEquals("com.music.bitchord", spec.packageName)
        assertEquals("com.music.bitchord.MainActivity", spec.launchActivity)
        assertEquals("BitChord", spec.appLabel)

        val apkBytes = ApkBuilder.buildApkBytes(spec)
        val logs = mutableListOf<String>()
        val result = ApkRuntime { logs += it }.launch(apkBytes)
        assertTrue(result.ok, "Expected APK launch to succeed, got: ${result.message}")
        assertEquals("com.music.bitchord", result.packageName)
        assertEquals("com.music.bitchord.MainActivity", result.activity)
        assertTrue(result.dexClasses >= 1, "Expected at least 1 dex class, got ${result.dexClasses}")
        assertTrue(result.message.contains("onCreate returned"), "Expected onCreate to return: ${result.message}")
        assertTrue(logs.any { it.contains("BitChord") }, "Expected startup logs to mention BitChord: $logs")
    }

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        // Minimal stored-zip writer (local headers + central directory + EOCD) with real CRC-32s:
        // ZipArchive rejects an entry whose crc does not match, so a zero crc makes read() answer null.
        val out = ArrayList<Byte>()
        val centrals = ArrayList<Byte>()
        var offset = 0
        fun u16(v: Int) {
            out.add((v and 0xFF).toByte()); out.add(((v ushr 8) and 0xFF).toByte())
        }
        fun u32(v: Long) {
            u16((v and 0xFFFF).toInt()); u16(((v ushr 16) and 0xFFFF).toInt())
        }
        fun cu16(v: Int) {
            centrals.add((v and 0xFF).toByte()); centrals.add(((v ushr 8) and 0xFF).toByte())
        }
        fun cu32(v: Long) {
            cu16((v and 0xFFFF).toInt()); cu16(((v ushr 16) and 0xFFFF).toInt())
        }
        for ((name, data) in entries) {
            val nameBytes = name.encodeToByteArray()
            val crc = crc32(data)
            val localStart = offset
            u32(0x04034b50); u16(20); u16(0); u16(0) // stored
            u16(0); u16(0) // time/date
            u32(crc)
            u32(data.size.toLong()); u32(data.size.toLong())
            u16(nameBytes.size); u16(0)
            out.addAll(nameBytes.toList())
            out.addAll(data.toList())
            offset = out.size

            cu32(0x02014b50); cu16(20); cu16(20); cu16(0); cu16(0)
            cu16(0); cu16(0) // time/date
            cu32(crc)
            cu32(data.size.toLong()); cu32(data.size.toLong())
            cu16(nameBytes.size); cu16(0); cu16(0)
            cu16(0); cu16(0); cu32(0); cu32(localStart.toLong())
            centrals.addAll(nameBytes.toList())
        }
        val centralStart = out.size
        out.addAll(centrals)
        val centralSize = out.size - centralStart
        u32(0x06054b50); u16(0); u16(0)
        u16(entries.size); u16(entries.size)
        u32(centralSize.toLong()); u32(centralStart.toLong()); u16(0)
        val bytes = out.toByteArray()
        assertNotNull(ZipArchive.open(bytes), "synthetic zip should open")
        return bytes
    }

    private fun crc32(data: ByteArray): Long {
        var crc = -1
        for (b in data) {
            crc = crc xor (b.toInt() and 0xFF)
            repeat(8) {
                crc = if (crc and 1 != 0) (crc ushr 1) xor 0xEDB88320.toInt() else crc ushr 1
            }
        }
        return (crc.inv().toLong()) and 0xFFFFFFFFL
    }
}
