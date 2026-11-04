package dev.ide.core.runtime

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * End-to-end proof of the REAL Swift Cupertino Run path — not the Quick Peek interpreter.
 *
 * Compiles a counter app against the open-source SwiftUICore shim with the host `swiftc`,
 * executes the binary, and drives the ViewHost JSON protocol the same way `GuiBridgeRuntime`
 * does on device. Skips when the host has no Swift toolchain or shim sources.
 */
class RealSwiftCupertinoRunTest {

    private fun hostSwiftc(): String? {
        val fromEnv = System.getenv("SWIFT")
        if (fromEnv != null && Files.isRegularFile(Path.of(fromEnv))) return fromEnv
        for (p in listOf("/usr/bin/swiftc", "/Users/mac/.swiftly/bin/swiftc")) {
            if (Files.isRegularFile(Path.of(p))) return p
        }
        return null
    }

    private fun swiftUiCorePackage(): Path? {
        val candidates = listOf(
            Path.of("/Users/mac/building-swift-preview/tools/swift-preview/.build/bridge-json-baseline/AndroidSwiftUI/SwiftUICore"),
            Path.of(System.getProperty("user.home", ""), ".building/SwiftUICore"),
        )
        return candidates.firstOrNull {
            Files.isRegularFile(it.resolve("Package.swift")) &&
                Files.isDirectory(it.resolve("Sources/SwiftUICore"))
        }
    }

    @Test
    fun realSwiftCounterCompilesAndIncrementsOverViewHostProtocol() {
        val swiftc = hostSwiftc() ?: run {
            assumeTrue(false, "No host swiftc; real-Swift Cupertino Run cannot be proven here")
            return
        }
        val core = swiftUiCorePackage() ?: run {
            assumeTrue(false, "No SwiftUICore shim package on host")
            return
        }

        val work = Files.createTempDirectory("real-swift-cupertino")
        // Local package: SwiftUI umbrella + app + BridgeAndApp runPreview host.
        Files.createDirectories(work.resolve("Sources/SwiftUI"))
        Files.createDirectories(work.resolve("Sources/CupApp"))
        Files.createDirectories(work.resolve("SwiftUICore"))
        // Copy SwiftUICore package (sources only; avoid .build)
        core.toFile().walkTopDown().forEach { f ->
            val rel = f.relativeTo(core.toFile())
            if (rel.path.isEmpty()) return@forEach
            if (rel.path.startsWith(".build")) return@forEach
            val dest = work.resolve("SwiftUICore").resolve(rel.path).toFile()
            if (f.isDirectory) dest.mkdirs()
            else {
                dest.parentFile.mkdirs()
                f.copyTo(dest, overwrite = true)
            }
        }

        work.resolve("Package.swift").writeText(
            """
            // swift-tools-version: 6.1
            import PackageDescription
            let package = Package(
                name: "CupApp",
                platforms: [.macOS(.v14)],
                products: [.executable(name: "CupApp", targets: ["CupApp"])],
                dependencies: [.package(path: "./SwiftUICore")],
                targets: [
                    .target(name: "SwiftUI", dependencies: [.product(name: "SwiftUICore", package: "SwiftUICore")]),
                    .executableTarget(name: "CupApp", dependencies: ["SwiftUI"]),
                ]
            )
            """.trimIndent(),
        )
        work.resolve("Sources/SwiftUI/SwiftUI.swift").writeText(
            """
            @_exported import SwiftUICore
            public protocol PreviewProvider {
                associatedtype Previews: View
                @ViewBuilder static var previews: Previews { get }
            }
            """.trimIndent(),
        )

        val bridge = work.resolve("Sources/CupApp/BridgeAndApp.swift")
        val bridgeSrc = listOf(
            Path.of(System.getProperty("user.home", ""), ".building/swiftui-bridge-sdk/BridgeAndApp.swift"),
            Path.of("/Users/mac/building-swift-preview/tools/swift-preview/.build/bridge-json-baseline/AndroidSwiftUI/Sources/ComposeUI/BridgeAndApp.swift"),
        ).firstOrNull { Files.isRegularFile(it) }
            ?: run {
                assumeTrue(false, "No BridgeAndApp.swift runPreview host")
                return
            }
        Files.copy(bridgeSrc, bridge)

        work.resolve("Sources/CupApp/CupAppMain.swift").writeText(
            """
            import SwiftUI

            struct CounterView: View {
                @State private var count = 0
                @State private var name = "World"
                var body: some View {
                    VStack {
                        Text("Hello, \(name)")
                        Text("Value: \(count)")
                        Button("Add") { count += 1 }
                    }
                }
            }

            @main
            struct CupAppMain {
                static func main() throws {
                    try runPreview { CounterView() }
                }
            }
            """.trimIndent(),
        )

        val swiftBin = if (swiftc.endsWith("swiftc")) swiftc.removeSuffix("swiftc") + "swift" else "swift"
        val build = ProcessBuilder(swiftBin, "build", "-c", "debug")
            .directory(work.toFile())
            .redirectErrorStream(true)
            .start()
        val buildOut = build.inputStream.bufferedReader().readText()
        val buildCode = build.waitFor()
        if (buildCode != 0) {
            fail("swift build failed:\n$buildOut")
        }
        val exe = work.resolve(".build/debug/CupApp")
        assertTrue(Files.isRegularFile(exe), "Built CupApp binary missing at $exe\n$buildOut")

        val proc = ProcessBuilder(exe.toString())
            .redirectErrorStream(false)
            .start()
        try {
            val reader = proc.inputStream.bufferedReader()
            val writer = proc.outputStream.bufferedWriter()
            val line1 = reader.readLine()
            assertTrue(line1 != null && line1.contains("Value: 0"), "Expected real Swift frame Value: 0, got: $line1")
            assertTrue(line1.contains("Hello, World"), "Expected greeting in real Swift frame: $line1")
            assertTrue(line1.contains("Button") || line1.contains("onTap"), "Expected Button callback: $line1")
            val id = Regex("\"onTap\":(\\d+)").find(line1!!)?.groupValues?.get(1)
                ?: fail("No onTap id in $line1")
            writer.write("{\"id\":\"$id\",\"kind\":\"void\"}\n")
            writer.flush()
            val line2 = reader.readLine()
            assertTrue(line2 != null && line2.contains("Value: 1"), "Expected real Swift state advance to Value: 1, got: $line2")
        } finally {
            proc.destroyForcibly()
        }
    }
}
