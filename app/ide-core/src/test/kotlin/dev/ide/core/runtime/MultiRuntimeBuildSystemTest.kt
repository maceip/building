package dev.ide.core.runtime

import dev.ide.core.IdeServicesBackend
import dev.ide.core.project.ImportableKind
import dev.ide.core.project.ProjectManager
import dev.ide.testkit.withTempDir
import dev.ide.ui.backend.RunStatus
import dev.ide.workbench.runtime.RuntimeChild
import dev.ide.workbench.runtime.RuntimeHost
import dev.ide.workbench.runtime.RuntimeInstallation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Verifies that Swift & iOS (SwiftUI + xtool `.ipa`), Python, and Node.js are first-class citizens:
 *  1. Cloning a GitHub SwiftUI repo (even an Xcode `.xcodeproj` repo with no `Package.swift` or `xtool.yml`),
 *     a Python repo, or a Node.js repo detects its build system automatically, scaffolds what is needed, and
 *     exposes first-class Build / Run tasks on the main Editor Build/Run button (`backend.build.runTasks()`).
 *  2. Pressing the main Build / Run button (`backend.build.runBuild()`) executes the project's `BuildSystem`
 *     and produces the expected asset (e.g. `xtool/<App>.ipa` for SwiftUI/iOS) or runs the Python / Node target
 *     and streams output to the IDE's `BuildConsole`.
 *  3. Built-in project templates (`swift-ios-app`, `python-app`, `node-app`) create valid first-class projects.
 */
class MultiRuntimeBuildSystemTest {

    private fun write(dir: Path, rel: String, text: String) {
        val file = dir.resolve(rel)
        Files.createDirectories(file.parent)
        file.writeText(text.trimIndent() + "\n")
    }

    @Test
    fun clonedSwiftUiRepoScaffoldsAndBuildsIosIpaViaMainBuildButton() = withTempDir("swiftui-clone-ipa") { root ->
        val fakePrefix = root.resolve("fake-prefix")
        val binDir = fakePrefix.resolve("bin")
        Files.createDirectories(binDir)
        val fakeXtool = binDir.resolve("xtool").apply { writeText("#!/bin/sh\n") }
        val fakeSwift = binDir.resolve("swift").apply { writeText("#!/bin/sh\n") }

        val recordedCommands = ArrayList<List<String>>()
        val previousHost = RuntimeBuildEnvironment.host
        RuntimeBuildEnvironment.host = object : RuntimeHost {
            override val workspaceCache: Path = root.resolve("ws-cache")
            override suspend fun prepare(): RuntimeInstallation = RuntimeInstallation(
                packId = "test-pack",
                abi = "arm64-v8a",
                prefix = fakePrefix,
                node = null,
                npmCli = null,
                python = null,
                shell = null,
                environment = mapOf("HOME" to root.resolve("home").toString()),
                xtool = fakeXtool,
            )

            override fun start(
                command: List<String>,
                workingDirectory: Path,
                environment: Map<String, String>,
            ): RuntimeChild {
                synchronized(recordedCommands) { recordedCommands += command }
                // Simulate xtool dev build --build-system native --ipa writing xtool/IceCubesApp.ipa
                if (command.any { it == "--ipa" }) {
                    val outIpa = workingDirectory.resolve("xtool/IceCubesApp.ipa")
                    Files.createDirectories(outIpa.parent)
                    Files.write(outIpa, ByteArray(1024) { 0x50 })
                }
                return FakeRuntimeChild(stdoutText = "Building IceCubesApp for iOS...\nPackaged xtool/IceCubesApp.ipa\n", exitCode = 0)
            }
        }

        try {
            val manager = ProjectManager.desktop(root.resolve("projects"))
            // Simulate a cloned GitHub SwiftUI repository that only has an .xcodeproj and SwiftUI sources
            val cloned = manager.projectsRoot.resolve("IceCubesApp")
            Files.createDirectories(cloned.resolve("IceCubesApp.xcodeproj"))
            write(
                cloned,
                "IceCubesApp/App.swift",
                """
                import SwiftUI

                @main
                struct IceCubesApp: App {
                    var body: some Scene {
                        WindowGroup {
                            Text("Hello iOS")
                        }
                    }
                }
                """,
            )

            val kind = manager.adoptFolderInPlace(cloned)
            assertEquals(ImportableKind.SWIFT, kind, "Cloned SwiftUI repo with .xcodeproj must be adopted as SWIFT")
            assertTrue(cloned.resolve("Package.swift").exists(), "Package.swift must be auto-scaffolded for SwiftUI clone")
            assertTrue(cloned.resolve("xtool.yml").exists(), "xtool.yml must be auto-scaffolded for iOS .ipa build")

            manager.open(cloned.toString()).use { ide ->
                val backend = IdeServicesBackend(ide, manager)
                assertTrue(backend.project.isSwift, "ProjectInfo.isSwift must be true")
                val tasks = backend.build.runTasks()
                assertTrue(tasks.isNotEmpty(), "Swift project must expose first-class Run/Build tasks")
                // Default Run is REAL Swift Cupertino execution (or bridge); Quick Peek is secondary.
                assertEquals(SwiftBuildSystem.TASK_RUN, tasks.first().id, "Default task for SwiftUI project must be Run iOS GUI (Cupertino Bridge)")
                assertTrue(tasks.any { it.id == SwiftBuildSystem.TASK_PREVIEW }, "Quick Peek must remain available")
                assertTrue(tasks.any { it.id == SwiftBuildSystem.TASK_IPA }, "Export IPA task must remain available")

                // Explicit Export IPA still goes through the toolchain path and produces the archive.
                val beforeIpa = backend.build.buildState.value
                backend.build.runTask(SwiftBuildSystem.TASK_IPA)
                val finalState = runBlocking {
                    withTimeout(30_000) {
                        backend.build.buildState.first {
                            it !== beforeIpa && it.status != RunStatus.Running && it.log.isNotEmpty()
                        }
                    }
                }
                assertEquals(RunStatus.Succeeded, finalState.status, "Log:\n${finalState.log.joinToString("\n") { it.message }}")
                assertTrue(cloned.resolve("xtool/IceCubesApp.ipa").exists(), "Build button must produce xtool/IceCubesApp.ipa")
                assertTrue(
                    finalState.log.any { "IceCubesApp.ipa" in it.message },
                    "Build console must report the produced .ipa artifact: ${finalState.log.map { it.message }}",
                )
            }
        } finally {
            RuntimeBuildEnvironment.host = previousHost
        }
    }

    @Test
    fun clonedPythonAndNodeReposExposeAndRunFirstClassBuildTasks() = withTempDir("python-node-clone") { root ->
        val fakePrefix = root.resolve("fake-prefix")
        val binDir = fakePrefix.resolve("bin")
        Files.createDirectories(binDir)
        val fakePython = binDir.resolve("python3").apply { writeText("#!/bin/sh\n") }
        val fakeNode = binDir.resolve("node").apply { writeText("#!/bin/sh\n") }
        val fakeNpm = binDir.resolve("npm").apply { writeText("#!/bin/sh\n") }

        val previousHost = RuntimeBuildEnvironment.host
        RuntimeBuildEnvironment.host = object : RuntimeHost {
            override val workspaceCache: Path = root.resolve("ws-cache")
            override suspend fun prepare(): RuntimeInstallation = RuntimeInstallation(
                packId = "test-pack",
                abi = "arm64-v8a",
                prefix = fakePrefix,
                node = fakeNode,
                npmCli = fakeNpm,
                python = fakePython,
                shell = null,
                environment = emptyMap(),
            )

            override fun start(
                command: List<String>,
                workingDirectory: Path,
                environment: Map<String, String>,
            ): RuntimeChild {
                val label = command.joinToString(" ")
                return FakeRuntimeChild(stdoutText = "Executed: $label\n", exitCode = 0)
            }
        }

        try {
            val manager = ProjectManager.desktop(root.resolve("projects"))

            // 1. Cloned Python repository with main.py
            val pyRepo = manager.projectsRoot.resolve("agent-runner")
            write(pyRepo, "main.py", "print('Hello from Python Agent')")
            assertEquals(ImportableKind.PYTHON, manager.adoptFolderInPlace(pyRepo))

            manager.open(pyRepo.toString()).use { ide ->
                val backend = IdeServicesBackend(ide, manager)
                assertTrue(backend.project.isPython)
                val tasks = backend.build.runTasks()
                assertEquals("python:run:main.py", tasks.first().id)

                val before = backend.build.buildState.value
                backend.build.runBuild()
                val finalState = runBlocking {
                    withTimeout(30_000) {
                        backend.build.buildState.first {
                            it !== before && it.status != RunStatus.Running && it.log.isNotEmpty()
                        }
                    }
                }
                assertEquals(RunStatus.Succeeded, finalState.status, "Python run log: ${finalState.log.map { it.message }}")
                assertTrue(finalState.log.any { "Executed:" in it.message && "main.py" in it.message })
            }

            // 2. Cloned Node.js repository with package.json & dependencies (auto npm install + npm start)
            val nodeRepo = manager.projectsRoot.resolve("hono-api")
            write(
                nodeRepo,
                "package.json",
                """
                {
                  "name": "hono-api",
                  "version": "1.0.0",
                  "scripts": {
                    "start": "node server.js",
                    "build": "node build.js"
                  },
                  "dependencies": {
                    "hono": "^4.0.0"
                  }
                }
                """,
            )
            write(nodeRepo, "server.js", "console.log('Server running')")
            assertEquals(ImportableKind.NODE, manager.adoptFolderInPlace(nodeRepo))

            manager.open(nodeRepo.toString()).use { ide ->
                val backend = IdeServicesBackend(ide, manager)
                assertTrue(backend.project.isNode)
                val tasks = backend.build.runTasks()
                assertEquals("node:npm:start", tasks.first().id)

                val before = backend.build.buildState.value
                backend.build.runBuild()
                val finalState = runBlocking {
                    withTimeout(30_000) {
                        backend.build.buildState.first {
                            it !== before && it.status != RunStatus.Running && it.log.isNotEmpty()
                        }
                    }
                }
                assertEquals(RunStatus.Succeeded, finalState.status, "Node run log: ${finalState.log.map { it.message }}")
                assertTrue(
                    finalState.log.any { "installing npm dependencies" in it.message },
                    "Freshly cloned Node repo without node_modules must auto-install dependencies on first Run",
                )
            }
        } finally {
            RuntimeBuildEnvironment.host = previousHost
        }
    }

    @Test
    fun builtInSwiftPythonAndNodeTemplatesCreateFirstClassProjects() = withTempDir("multi-runtime-templates") { root ->
        val manager = ProjectManager.desktop(root.resolve("projects"))

        manager.create("swift-ios-app", mapOf("name" to "DemoSwiftApp", "packageName" to "dev.building.demo")).use { ide ->
            val backend = IdeServicesBackend(ide, manager)
            assertTrue(backend.project.isSwift)
            assertFalse(ide.isUnrecognizedProject())
            assertTrue(ide.workspaceRoot.resolve("Package.swift").exists())
            assertTrue(ide.workspaceRoot.resolve("xtool.yml").exists())
            assertEquals(SwiftBuildSystem.TASK_RUN, backend.build.runTasks().first().id)
        }

        manager.create("python-app", mapOf("name" to "DemoPythonApp", "packageName" to "dev.building.py")).use { ide ->
            val backend = IdeServicesBackend(ide, manager)
            assertTrue(backend.project.isPython)
            assertFalse(ide.isUnrecognizedProject())
            assertTrue(ide.workspaceRoot.resolve("main.py").exists())
            assertEquals("python:run:main.py", backend.build.runTasks().first().id)
        }

        manager.create("node-app", mapOf("name" to "DemoNodeApp", "packageName" to "dev.building.node")).use { ide ->
            val backend = IdeServicesBackend(ide, manager)
            assertTrue(backend.project.isNode)
            assertFalse(ide.isUnrecognizedProject())
            assertTrue(ide.workspaceRoot.resolve("package.json").exists())
            assertEquals("node:npm:start", backend.build.runTasks().first().id)
        }
    }

    @Test
    fun pureSwiftCliPackageBuildsAndExecutesBinaryDirectlyWithoutBogusXtoolYml() = withTempDir("swift-cli-pkg") { root ->
        val fakePrefix = root.resolve("fake-prefix")
        val binDir = fakePrefix.resolve("bin")
        Files.createDirectories(binDir)
        val fakeSwift = binDir.resolve("swift").apply { writeText("#!/bin/sh\n") }

        val recordedCommands = ArrayList<List<String>>()
        val previousHost = RuntimeBuildEnvironment.host
        RuntimeBuildEnvironment.host = object : RuntimeHost {
            override val workspaceCache: Path = root.resolve("ws-cache")
            override suspend fun prepare(): RuntimeInstallation = RuntimeInstallation(
                packId = "test-pack",
                abi = "arm64-v8a",
                prefix = fakePrefix,
                node = null,
                npmCli = null,
                python = null,
                shell = null,
                environment = mapOf("HOME" to root.resolve("home").toString()),
            )

            override fun start(
                command: List<String>,
                workingDirectory: Path,
                environment: Map<String, String>,
            ): RuntimeChild {
                synchronized(recordedCommands) { recordedCommands += command }
                if (command.contains("build")) {
                    val outBin = workingDirectory.resolve(".build/debug/ink-cli")
                    Files.createDirectories(outBin.parent)
                    outBin.writeText("#!/bin/sh\n")
                    outBin.toFile().setExecutable(true)
                    return FakeRuntimeChild(stdoutText = "[6/6] Linking ink-cli\nBuild complete!\n", exitCode = 0)
                }
                return FakeRuntimeChild(stdoutText = "<h1>Hello from Ink CLI</h1>\n", exitCode = 0)
            }
        }

        try {
            val manager = ProjectManager.desktop(root.resolve("projects"))
            val inkRepo = manager.projectsRoot.resolve("Ink")
            write(
                inkRepo,
                "Package.swift",
                """
                // swift-tools-version:5.5
                import PackageDescription

                let package = Package(
                    name: "Ink",
                    platforms: [.macOS(.v12), .iOS(.v15)],
                    products: [
                        .library(name: "Ink", targets: ["Ink"]),
                        .executable(name: "ink-cli", targets: ["InkCLI"])
                    ],
                    targets: [
                        .target(name: "Ink"),
                        .executableTarget(name: "InkCLI", dependencies: ["Ink"])
                    ]
                )
                """,
            )
            write(inkRepo, "Sources/Ink/MarkdownParser.swift", "public struct MarkdownParser { public init() {} }")
            write(inkRepo, "Sources/InkCLI/main.swift", "import Ink\nprint(\"<h1>Hello from Ink CLI</h1>\")")

            assertEquals(ImportableKind.SWIFT, manager.adoptFolderInPlace(inkRepo))
            assertFalse(inkRepo.resolve("xtool.yml").exists(), "Pure Swift CLI package must not get a synthetic xtool.yml")

            manager.open(inkRepo.toString()).use { ide ->
                val backend = IdeServicesBackend(ide, manager)
                val tasks = backend.build.runTasks()
                assertEquals(SwiftBuildSystem.TASK_RUN, tasks.first().id, "Default task for Swift CLI repo must be TASK_RUN")

                val before = backend.build.buildState.value
                backend.build.runBuild()
                val finalState = runBlocking {
                    withTimeout(30_000) {
                        backend.build.buildState.first {
                            it !== before && it.status != RunStatus.Running && it.log.isNotEmpty()
                        }
                    }
                }
                assertEquals(RunStatus.Succeeded, finalState.status, "Log:\n${finalState.log.joinToString("\n") { it.message }}")
                assertFalse(inkRepo.resolve("xtool.yml").exists(), "Running TASK_RUN must not write xtool.yml into CLI repo")
                assertTrue(
                    finalState.log.any { "<h1>Hello from Ink CLI</h1>" in it.message },
                    "Build console must show stdout from the executed Swift CLI binary: ${finalState.log.map { it.message }}",
                )
            }
        } finally {
            RuntimeBuildEnvironment.host = previousHost
        }
    }

    @Test
    fun nodeGuiBridgeShimRendersCalculatorAndPasswordGenerator() = withTempDir { tmp ->
        val calcDir = tmp.resolve("calculator")
        write(
            calcDir,
            "package.json",
            """
            {
              "name": "calculator",
              "version": "1.0.0",
              "main": "dist/index.js",
              "scripts": { "start": "webpack && qode ./dist/index.js" },
              "dependencies": { "@nodegui/nodegui": "^0.57.1" }
            }
            """.trimIndent(),
        )
        write(
            calcDir,
            "src/index.ts",
            """
            import { QMainWindow, QPushButton, QPushButtonEvents, QWidget, FlexLayout, QLabel } from "@nodegui/nodegui";
            const win = new QMainWindow();
            win.setWindowTitle("Calculator");
            const rootView = new QWidget();
            rootView.setObjectName("rootView");
            const rootLayout = new FlexLayout();
            rootView.setLayout(rootLayout);
            const displayLabel = new QLabel();
            displayLabel.setObjectName("displayLabel");
            displayLabel.setText("0");
            rootLayout.addWidget(displayLabel);
            const btn7 = new QPushButton();
            btn7.setText("7");
            btn7.addEventListener(QPushButtonEvents.clicked, () => {
              displayLabel.setText("7");
            });
            rootLayout.addWidget(btn7);
            win.setCentralWidget(rootView);
            win.show();
            (global as any).win = win;
            """.trimIndent(),
        )

        assertTrue(GuiBridgeRuntime.isNodeGuiProject(calcDir))
        GuiBridgeRuntime.ensureNodeGuiBridgeShimInstalled(calcDir)
        assertTrue(calcDir.resolve("node_modules/@nodegui/nodegui/index.js").exists())
        val entry = GuiBridgeRuntime.findNodeGuiEntrypoint(calcDir)
        assertEquals(calcDir.resolve("src/index.ts"), entry)

        // Verify password-generator project detection and entrypoint resolution
        val pwDir = tmp.resolve("password-generator")
        write(
            pwDir,
            "package.json",
            """
            {
              "name": "nodegui-password-generator",
              "dependencies": { "@nodegui/nodegui": "^0.15.0", "lodash": "^4.17.15" }
            }
            """.trimIndent(),
        )
        write(pwDir, "src/index.js", "const { QMainWindow } = require('@nodegui/nodegui');")
        assertTrue(GuiBridgeRuntime.isNodeGuiProject(pwDir))
        GuiBridgeRuntime.ensureNodeGuiBridgeShimInstalled(pwDir)
        assertEquals(pwDir.resolve("src/index.js"), GuiBridgeRuntime.findNodeGuiEntrypoint(pwDir))
        Path.of("/private/tmp/nodegui-password-generator")
            .takeIf { Files.isDirectory(it) }
            ?.let { GuiBridgeRuntime.ensureNodeGuiBridgeShimInstalled(it) }

        // Verify multi-target SwiftUI repo selection (e.g., SwiftUIStateManagementBook-Counter with SimpleCounterSwiftUI + SimpleCounterTCA)
        val multiRepoDir = tmp.resolve("SwiftUIStateManagementBook-Counter")
        val swiftUiAppFile = write(
            multiRepoDir,
            "SimpleCounterSwiftUI/SimpleCounterSwiftUI/SimpleCounterSwiftUIApp.swift",
            """
            import SwiftUI
            @main
            struct SimpleCounterSwiftUIApp: App {
                var body: some Scene { WindowGroup { ContentView() } }
            }
            """.trimIndent(),
        )
        val swiftUiContentView = write(
            multiRepoDir,
            "SimpleCounterSwiftUI/SimpleCounterSwiftUI/ContentView.swift",
            """
            import SwiftUI
            struct ContentView: View { var body: some View { Text("0") } }
            """.trimIndent(),
        )
        write(
            multiRepoDir,
            "SimpleCounterTCA/SimpleCounterTCA/SimpleCounterTCAApp.swift",
            """
            import SwiftUI
            import ComposableArchitecture
            @main
            struct SimpleCounterTCAApp: App {
                var body: some Scene { WindowGroup { ContentView() } }
            }
            """.trimIndent(),
        )
        write(
            multiRepoDir,
            "SimpleCounterTCA/SimpleCounterTCA/ContentView.swift",
            """
            import SwiftUI
            import ComposableArchitecture
            struct ContentView: View { var body: some View { Text("TCA") } }
            """.trimIndent(),
        )
        val swiftUiAppPath = multiRepoDir.resolve("SimpleCounterSwiftUI/SimpleCounterSwiftUI/SimpleCounterSwiftUIApp.swift")
        val swiftUiContentViewPath = multiRepoDir.resolve("SimpleCounterSwiftUI/SimpleCounterSwiftUI/ContentView.swift")
        val allMultiFiles = SwiftBuildSystem.findSwiftSourceFiles(multiRepoDir)
        val selectedFiles = GuiBridgeRuntime.selectSwiftUiTargetFiles(multiRepoDir, allMultiFiles)
        assertEquals(
            setOf(swiftUiAppPath, swiftUiContentViewPath),
            selectedFiles.toSet(),
            "selectSwiftUiTargetFiles must scope to the standalone SwiftUI target without external ComposableArchitecture dependency",
        )

        // If node is installed on the host, verify live execution & callbacks of the NodeGUI project
        val nodeBin = listOf(
            "/Users/mac/.nvm/versions/node/v24.15.0/bin/node",
            "/opt/homebrew/bin/node",
            "/usr/local/bin/node",
            "/usr/bin/node",
        ).map { Path.of(it) }.firstOrNull { Files.isExecutable(it) }
        if (nodeBin != null) {
            val proc = ProcessBuilder(nodeBin.toString(), entry!!.toString())
                .directory(calcDir.toFile())
                .start()
            try {
                val reader = proc.inputStream.bufferedReader()
                val writer = proc.outputStream.bufferedWriter()
                val line1 = reader.readLine()
                assertTrue(line1 != null && line1.startsWith("{") && line1.contains("CupertinoButton"), "Expected ViewNode JSON tree with CupertinoButton, got: $line1")
                val cbMatch = Regex(""""onClick":(\d+)""").find(line1)
                assertTrue(cbMatch != null, "Expected onClick callback id for button '7' in Calculator ViewNode JSON: $line1")
                val cbId = cbMatch.groupValues[1]
                writer.write("{\"id\":\"$cbId\",\"kind\":\"void\"}\n")
                writer.flush()
                val line2 = reader.readLine()
                assertTrue(line2 != null && line2.startsWith("{") && line2.contains("\"text\":\"7\""), "Expected updated ViewNode JSON after clicking 7, got: $line2")
            } finally {
                proc.destroyForcibly()
            }
        }
    }

    @Test
    fun ideRunScreenBridgeStreamsLiveSwiftUiAndNodeGuiFramesAndCallbacks() = withTempDir { tmp ->
        val realSwiftCounter = Path.of("/Users/mac/building-swift-preview/tools/swift-preview/.build/github-counter")
            .takeIf { Files.isDirectory(it) }
            ?: Path.of("/tmp/gh-swiftui-candidates/SwiftUI-Calculator")
        val hostSdk = Path.of(System.getProperty("user.home", ""), ".building/swiftui-bridge-sdk")
        if (!Files.isDirectory(realSwiftCounter) || !Files.isDirectory(hostSdk)) {
            return@withTempDir
        }

        val previousHost = RuntimeBuildEnvironment.host
        RuntimeBuildEnvironment.host = null // Use real DesktopRuntimeHost
        try {
            val manager = ProjectManager.desktop(tmp.resolve("projects"))

            // Copy real multi-target github-counter repo and run it through IdeServicesBackend
            val swiftProjDir = manager.projectsRoot.resolve("SwiftUIStateManagementBook-Counter")
            realSwiftCounter.toFile().copyRecursively(swiftProjDir.toFile(), overwrite = true)
            manager.adoptFolderInPlace(swiftProjDir)
            manager.open(swiftProjDir.toString()).use { ide ->
                val backend = IdeServicesBackend(ide, manager)
                // This test owns the RunScreen frame/callback pipe. Drive Quick Peek (no host
                // swiftc required); RealSwiftCupertinoRunTest owns real Swift compile+execute.
                backend.build.runTask(SwiftBuildSystem.TASK_PREVIEW)
                val initialConsole = runBlocking {
                    withTimeout(30_000) {
                        backend.build.runConsole.first { rc ->
                            rc?.frame?.path?.contains("Value: 0") == true
                        }
                    }
                }!!
                val frameJson1 = initialConsole.frame!!.path
                val plusMatch = Regex("\"onTap\":(\\d+)").findAll(frameJson1).lastOrNull()
                assertTrue(plusMatch != null, "Expected plus button onTap callback in SwiftUI frame: $frameJson1")
                val plusId = plusMatch.groupValues[1]
                backend.build.sendRunInput("{\"id\":\"$plusId\",\"kind\":\"void\"}")
                val updatedConsole = runBlocking {
                    withTimeout(15_000) {
                        backend.build.runConsole.first { rc ->
                            rc?.frame?.path?.contains("Value: 1") == true
                        }
                    }
                }!!
                assertTrue(updatedConsole.frame!!.path.contains("Value: 1"))
                backend.build.closeRunInput()
                backend.build.stopBuild()
                Unit
            }
        } finally {
            RuntimeBuildEnvironment.host = previousHost
        }
    }

    private class FakeRuntimeChild(
        stdoutText: String,
        stderrText: String = "",
        private val exitCode: Int = 0,
    ) : RuntimeChild {
        override val stdin: OutputStream = ByteArrayOutputStream()
        override val stdout: InputStream = ByteArrayInputStream(stdoutText.toByteArray(Charsets.UTF_8))
        override val stderr: InputStream = ByteArrayInputStream(stderrText.toByteArray(Charsets.UTF_8))
        override suspend fun awaitExit(): Int = exitCode
        override suspend fun terminate() {}
    }
}
