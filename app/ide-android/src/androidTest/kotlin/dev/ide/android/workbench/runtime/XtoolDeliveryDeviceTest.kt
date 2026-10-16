package dev.ide.android.workbench.runtime

import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.play.core.splitcompat.SplitCompat
import com.google.android.play.core.splitinstall.*
import com.google.android.play.core.splitinstall.model.SplitInstallSessionStatus as Status
import dev.ide.workbench.runtime.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/** Real Swift compilation through signed local-testing feature APKs, with no Apple SDK. */
class XtoolDeliveryDeviceTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext

    @Test fun downloadSwiftWithoutNodeThenCompile() = runBlocking {
        assertFalse(RuntimePacks.xtoolInstalled(app))
        assertFalse(RuntimePacks.nodeInstalled(app))
        val host = AndroidRuntimeHost(app)
        assertTrue(host.disabledReason(RuntimeKind.XTOOL).orEmpty().contains("Download"))
        val before = host.prepare()
        assertNull(before.xtool)
        val manager = SplitInstallManagerFactory.create(app)
        val complete = CompletableDeferred<Unit>()
        val listener = SplitInstallStateUpdatedListener { state ->
            if (RuntimePacks.XTOOL_MODULE in state.moduleNames()) when (state.status()) {
                Status.INSTALLED -> complete.complete(Unit)
                Status.FAILED, Status.CANCELED, Status.REQUIRES_USER_CONFIRMATION ->
                    complete.completeExceptionally(IllegalStateException("Delivery status ${state.status()}, error ${state.errorCode()}"))
            }
        }
        manager.registerListener(listener)
        try {
            val request = SplitInstallRequest.newBuilder()
            RuntimePacks.requestModules(app, RuntimePacks.XTOOL_MODULE).forEach { request.addModule(it) }
            manager.startInstall(request.build())
                .addOnFailureListener { complete.completeExceptionally(it) }
            withTimeout(240_000) { complete.await() }
            SplitCompat.install(app)
            assertTrue(RuntimePacks.xtoolInstalled(app))
            assertFalse("Swift must not require the Node pack", RuntimePacks.nodeInstalled(app))
            val after = host.prepare()
            assertNotEquals(before.packId, after.packId)
            assertNull(host.disabledReason(RuntimeKind.XTOOL))
            assertFalse("Split payload remains read-only", Files.isExecutable(requireNotNull(after.xtool).toRealPath()))
            exercise(host, after)
        } finally { manager.unregisterListener(listener) }
    }

    @Test fun installedSwiftCompilesAfterColdStart() = runBlocking {
        assertTrue(RuntimePacks.xtoolInstalled(app))
        val host = AndroidRuntimeHost(app)
        exercise(host, host.prepare())
    }

    /** SDK is supplied to this disposable test app separately, never included in an APK/AAB. */
    @Test fun compileUnsignedIosWithSuppliedSdk() = runBlocking {
        val sdk = app.filesDir.toPath().resolve("xtool-test-sdk/darwin.xtoolsdk")
        assertTrue("Supply the developer-owned SDK before running this opt-in check", Files.isRegularFile(sdk.resolve("swift-sdk.json")))
        val host = AndroidRuntimeHost(app)
        val pack = host.prepare()
        val root = app.filesDir.toPath().resolve("xtool-ios-proof")
        Files.createDirectories(root.resolve("Sources/PhoneHello"))
        RuntimeFiles.writeUtf8(root.resolve("Package.swift"), """
            // swift-tools-version: 6.0
            import PackageDescription
            let package = Package(name: "PhoneHello", platforms: [.iOS(.v17)],
                products: [.library(name: "PhoneHello", targets: ["PhoneHello"])],
                targets: [.target(name: "PhoneHello", resources: [.copy("message.txt")])])
        """.trimIndent())
        RuntimeFiles.writeUtf8(root.resolve("xtool.yml"), "version: 1\nbundleID: ai.building.tests.PhoneHello\n")
        RuntimeFiles.writeUtf8(root.resolve("Sources/PhoneHello/message.txt"), "This resource was packaged by xtool on Android.\n")
        RuntimeFiles.writeUtf8(root.resolve("Sources/PhoneHello/App.swift"), """
            import SwiftUI
            @main struct PhoneHello: App {
                var body: some Scene { WindowGroup { Text("Built with the delivered runtime") } }
            }
        """.trimIndent())
        val xtool = requireNotNull(pack.xtool).toString()
        run(host, pack, root, xtool, "sdk", "install", sdk.toString())
        run(host, pack, root, xtool, "dev", "build", "--build-system", "native", "--ipa")
        val ipa = root.resolve("xtool/PhoneHello.ipa")
        assertTrue(Files.size(ipa) > 0)
        java.util.zip.ZipFile(ipa.toFile()).use { zip ->
            val executable = requireNotNull(zip.getEntry("Payload/PhoneHello.app/PhoneHello"))
            zip.getInputStream(executable).use { input ->
                assertArrayEquals(byteArrayOf(0xcf.toByte(), 0xfa.toByte(), 0xed.toByte(), 0xfe.toByte()), ByteArray(4).also { assertEquals(4, input.read(it)) })
            }
        }
        val source = root.resolve("Sources/PhoneHello/App.swift")
        RuntimeFiles.writeUtf8(source, RuntimeFiles.readUtf8(source).replace("Built with the delivered runtime", "Edited and rebuilt on Android"))
        run(host, pack, root, xtool, "dev", "build", "--ipa")
        run(host, pack, root, xtool, "dev", "generate-xcode-project")
        assertTrue(Files.isRegularFile(root.resolve("xtool/PhoneHello.xcworkspace/contents.xcworkspacedata")))
    }

    private suspend fun exercise(host: AndroidRuntimeHost, pack: RuntimeInstallation) {
        val root = Files.createTempDirectory(app.cacheDir.toPath(), "swift-pack-check-")
        try {
            assertTrue(run(host, pack, root, requireNotNull(pack.xtool).toString(), "--version").isNotBlank())
            val swift = pack.prefix.resolve("bin/swift").toString()
            assertTrue(run(host, pack, root, swift, "--version").contains("Swift version"))
            assertTrue(run(host, pack, root, pack.shell.toString(), "-c", "swift --version").contains("Swift version"))
            assertTrue(run(host, pack, root, pack.prefix.resolve("bin/sourcekit-lsp").toString(), "--help").contains("USAGE"))
            RuntimeFiles.writeUtf8(root.resolve("hello.swift"), "print(\"delivered-swift-compile-ok\")\n")
            run(host, pack, root, pack.prefix.resolve("bin/swiftc").toString(), "hello.swift", "-o", "hello")
            assertEquals("delivered-swift-compile-ok", run(host, pack, root, "/system/bin/linker64", root.resolve("hello").toString()).trim())
            RuntimeFiles.writeUtf8(root.resolve("Package.swift"), """
                // swift-tools-version: 6.0
                import PackageDescription
                let package = Package(name: "PackHello", targets: [.executableTarget(name: "PackHello")])
            """.trimIndent())
            Files.createDirectories(root.resolve("Sources/PackHello"))
            RuntimeFiles.writeUtf8(root.resolve("Sources/PackHello/main.swift"), "print(\"delivered-swiftpm-ok\")\n")
            run(host, pack, root, swift, "build", "--build-system", "native", "-j", "2")
            assertEquals("delivered-swiftpm-ok", run(host, pack, root, "/system/bin/linker64", root.resolve(".build/debug/PackHello").toString()).trim())
            assertEquals("python-with-swift-ok", run(host, pack, root, pack.python.toString(), "-c", "print('python-with-swift-ok')").trim())
        } finally { root.toFile().deleteRecursively() }
    }

    private suspend fun run(host: AndroidRuntimeHost, pack: RuntimeInstallation, root: Path, vararg command: String): String = withTimeout(300_000) {
        val child = host.start(command.toList(), root, pack.environment)
        try {
            coroutineScope {
                val out = async(Dispatchers.IO) { child.stdout.bufferedReader().use { it.readText() } }
                val err = async(Dispatchers.IO) { child.stderr.bufferedReader().use { it.readText() } }
                val exit = child.awaitExit()
                val stdout = out.await()
                assertEquals("${command.toList()}\n$stdout\n${err.await()}", 0, exit)
                stdout
            }
        } finally { child.terminate() }
    }
}
