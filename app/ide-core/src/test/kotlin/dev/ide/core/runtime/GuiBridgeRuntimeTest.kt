package dev.ide.core.runtime

import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GuiBridgeRuntimeTest {

    @Test
    fun preserveMainOnAppInSanitizeSwiftUiSource() {
        val source = """
            import SwiftUI

            @main
            struct CalculatorApp: App {
                var body: some Scene {
                    WindowGroup {
                        ContentView()
                    }
                }
            }
        """.trimIndent()

        val sanitized = GuiBridgeRuntime.sanitizeSwiftUiSource(source)
        assertTrue(sanitized.contains("@main"), "Must retain @main")
        assertTrue(sanitized.contains("struct CalculatorApp: App"), "Must retain App struct")
        assertFalse(sanitized.contains("[sanitized @main on App]"), "Must not strip @main")
    }

    @Test
    fun dropUnsupportedDeclarationsHandlesBracesInStringsAndComments() {
        val source = """
            import SwiftUI

            struct ContentView: View {
                // { curly brace in line comment
                /* { nested block comment } */
                let format = "{ value: 123 }"
                let multiline = ${"\"\"\""}
                    { inside multiline string }
                ${"\"\"\""}

                var body: some View {
                    Text("Hello {World}")
                }
            }
        """.trimIndent()

        val dropped = GuiBridgeRuntime.dropUnsupportedDeclarations(source)
        assertTrue(dropped.contains("struct ContentView: View"), "ContentView must not be truncated or dropped")
        assertTrue(dropped.contains("Text(\"Hello {World}\")"), "View body must remain intact")
    }

    @Test
    fun allowsUIViewRepresentableAndToggleStyleDeclarations() {
        val source = """
            import SwiftUI

            struct CustomToggleStyle: ToggleStyle {
                func makeBody(configuration: Configuration) -> some View {
                    configuration.label
                }
            }

            struct WebHostView: UIViewRepresentable {
                func makeUIView(context: Context) -> Any { () }
                func updateUIView(_ uiView: Any, context: Context) {}
            }
        """.trimIndent()

        val dropped = GuiBridgeRuntime.dropUnsupportedDeclarations(source)
        assertTrue(dropped.contains("struct CustomToggleStyle: ToggleStyle"), "ToggleStyle must not be dropped")
        assertTrue(dropped.contains("struct WebHostView: UIViewRepresentable"), "UIViewRepresentable must not be dropped")
    }

    @Test
    fun countUnsupportedSwiftImportsAcceptsCommonFrameworks() {
        val source = """
            import SwiftUI
            import AudioToolbox
            import AVFoundation
            import PhotosUI
            import WebKit
            import Foundation

            struct TestView: View {
                var body: some View { Text("Test") }
            }
        """.trimIndent()

        val count = GuiBridgeRuntime.countUnsupportedSwiftImports(source)
        assertEquals(0, count, "Common iOS frameworks must not be counted as unsupported")

        val sanitized = GuiBridgeRuntime.sanitizeSwiftUiSource(source)
        assertTrue(sanitized.contains("// [sanitized import AudioToolbox]"))
        assertTrue(sanitized.contains("// [sanitized import AVFoundation]"))
        assertTrue(sanitized.contains("// [sanitized import PhotosUI]"))
        assertTrue(sanitized.contains("// [sanitized import WebKit]"))
    }

    @Test
    fun prepareCupertinoShadowRecursivelyCopiesSubdirectoriesAndAssets() {
        val tempDir = Files.createTempDirectory("shadow-test-project")
        val appDir = tempDir.resolve("Shared")
        val viewsDir = appDir.resolve("Views")
        val modelsDir = appDir.resolve("Models")
        val stylesDir = appDir.resolve("Styles")
        val assetsDir = appDir.resolve("Assets.xcassets/AppIcon.imageset")

        Files.createDirectories(viewsDir)
        Files.createDirectories(modelsDir)
        Files.createDirectories(stylesDir)
        Files.createDirectories(assetsDir)

        appDir.resolve("TestApp.swift").writeText(
            """
            import SwiftUI

            @main
            struct TestApp: App {
                var body: some Scene {
                    WindowGroup {
                        ContentView()
                    }
                }
            }
            """.trimIndent(),
        )
        viewsDir.resolve("ContentView.swift").writeText(
            """
            import SwiftUI

            struct ContentView: View {
                var body: some View {
                    Text("Hello")
                }
            }
            """.trimIndent(),
        )
        modelsDir.resolve("Item.swift").writeText(
            """
            import Foundation

            struct Item: Identifiable {
                let id = UUID()
            }
            """.trimIndent(),
        )
        stylesDir.resolve("AppStyle.swift").writeText(
            """
            import SwiftUI

            struct AppToggle: ToggleStyle {
                func makeBody(configuration: Configuration) -> some View {
                    EmptyView()
                }
            }
            """.trimIndent(),
        )
        assetsDir.resolve("Contents.json").writeText("{ \"images\": [] }")
        assetsDir.resolve("icon.png").writeText("PNG_MOCK")

        val scratchDir = Files.createTempDirectory("shadow-scratch")
        val shadow = GuiBridgeRuntime.prepareCupertinoShadow(tempDir, scratchDir)

        assertTrue(Files.isRegularFile(shadow.resolve("Shared/TestApp.swift")), "Main app file must be copied")
        assertTrue(Files.isRegularFile(shadow.resolve("Shared/Views/ContentView.swift")), "Subdirectory Views must be copied")
        assertTrue(Files.isRegularFile(shadow.resolve("Shared/Models/Item.swift")), "Subdirectory Models must be copied")
        assertTrue(Files.isRegularFile(shadow.resolve("Shared/Styles/AppStyle.swift")), "Subdirectory Styles must be copied")

        // Assets must be copied
        assertTrue(
            Files.isRegularFile(shadow.resolve("Shared/Assets.xcassets/AppIcon.imageset/Contents.json")) ||
                Files.isRegularFile(shadow.resolve("Assets.xcassets/AppIcon.imageset/Contents.json")),
            "Assets.xcassets must be copied into shadow",
        )

        // When @main is present on App, CupertinoShadowMain must NOT be generated!
        assertFalse(
            Files.exists(shadow.resolve("CupertinoShadowMain.swift")),
            "CupertinoShadowMain.swift must NOT be generated when @main App exists",
        )

        // Verify no _IdeRepoPreviewView exists anywhere in shadow
        Files.walk(shadow).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".swift") }.forEach { f ->
                val text = Files.readString(f)
                assertFalse(text.contains("_IdeRepoPreviewView"), "Must not contain _IdeRepoPreviewView in $f")
                assertFalse(text.contains("Inspect Active Target"), "Must not contain Inspect Active Target in $f")
            }
        }
    }

    @Test
    fun focusedCompanionSourcesIncludesSiblingModuleFiles() {
        // Reproduces the on-device regression: a focused preview that references a type declared in
        // another module file must compile together with that sibling file, otherwise the build fails
        // with "cannot find type 'X' in scope".
        val projectRoot = Files.createTempDirectory("focused-companion-project")
        val modelsDir = projectRoot.resolve("Calculator/Models")
        val stylesDir = projectRoot.resolve("Calculator/Styles")
        Files.createDirectories(modelsDir)
        Files.createDirectories(stylesDir)

        // Type declared in a sibling module file (the kind that used to be dropped).
        modelsDir.resolve("ButtonType.swift").writeText(
            """
            import Foundation
            import SwiftUI

            enum ButtonType: Hashable {
                case digit(Int)
            }
            """.trimIndent(),
        )

        // Preview candidate references ButtonType and lives in a different directory.
        stylesDir.resolve("CalculatorButtonStyle.swift").writeText(
            """
            import SwiftUI

            struct CalculatorButtonStyle: ButtonStyle {
                func makeBody(configuration: Configuration) -> some View { EmptyView() }
            }

            struct CalculatorButtonStyle_Previews: PreviewProvider {
                static let buttonType: ButtonType = .digit(0)
                static var previews: some View { EmptyView() }
            }
            """.trimIndent(),
        )

        // Real app entry point must be excluded so it does not collide with the shadow @main.
        projectRoot.resolve("CalculatorApp.swift").writeText(
            """
            import SwiftUI

            @main
            struct CalculatorApp: App {
                var body: some Scene { WindowGroup { EmptyView() } }
            }
            """.trimIndent(),
        )

        val sources = GuiBridgeRuntime.focusedCompanionSources(projectRoot, emptySet())
            .map { it.fileName.toString() }
            .toSet()

        assertTrue(sources.contains("ButtonType.swift"), "Sibling Models/ButtonType.swift must be a focused companion")
        assertTrue(sources.contains("CalculatorButtonStyle.swift"), "The preview candidate must be a focused companion")
        assertFalse(sources.contains("CalculatorApp.swift"), "The real @main App entry must be excluded from the focused set")
    }

    @Test
    fun focusedCompanionSourcesDropsUnsupportedImportFiles() {
        // A sibling that depends on an unsupported framework must not be pulled into the focused
        // module set, which would otherwise reintroduce the unsupported-import failure.
        val projectRoot = Files.createTempDirectory("focused-companion-unsupported")
        Files.createDirectories(projectRoot)
        projectRoot.resolve("Good.swift").writeText(
            """
            import SwiftUI
            struct GoodView: View { var body: some View { EmptyView() } }
            """.trimIndent(),
        )
        projectRoot.resolve("Unsupported.swift").writeText(
            """
            import SomeWeirdFramework
            struct Thing { let x: Int = 0 }
            """.trimIndent(),
        )

        val sources = GuiBridgeRuntime.focusedCompanionSources(projectRoot, emptySet())
            .map { it.fileName.toString() }
            .toSet()

        assertTrue(sources.contains("Good.swift"), "Safe file must be a focused companion")
        assertFalse(sources.contains("Unsupported.swift"), "Files with unsupported imports must be dropped")
    }
}
