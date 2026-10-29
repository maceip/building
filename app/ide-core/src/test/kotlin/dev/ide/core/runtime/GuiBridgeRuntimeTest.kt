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
    fun preservesUiKitInteropAndCanvasDeclarationsAndProvidesShimsInPrelude() {
        val source = """
            import SwiftUI
            import UIKit

            @Observable
            class AppViewModel {
                var screenWidth: CGFloat = UIScreen.main.bounds.width
            }

            struct HostedController: UIViewControllerRepresentable {
                func makeUIViewController(context: Context) -> UIViewController {
                    UINavigationController(rootViewController: UIHostingController(rootView: Text("Hosted")))
                }
                func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
            }

            struct DrawingView: View {
                var body: some View {
                    Canvas { context, size in
                        var p = Path()
                        p.move(to: CGPoint(x: 0, y: 0))
                        p.addLine(to: CGPoint(x: size.width, y: size.height))
                        context.stroke(p, with: .color(.blue), lineWidth: 2)
                    }
                }
            }
        """.trimIndent()

        val dropped = GuiBridgeRuntime.dropUnsupportedDeclarations(source)
        assertEquals(source, dropped, "dropUnsupportedDeclarations must never delete user declarations")

        val prelude = GuiBridgeRuntime.CUPERTINO_BRIDGE_PRELUDE
        assertTrue(prelude.contains("public final class UIScreen"), "Prelude must shim UIScreen")
        assertTrue(prelude.contains("public struct UIColor"), "Prelude must shim UIColor")
        assertTrue(prelude.contains("public struct UIImage"), "Prelude must shim UIImage")
        assertTrue(prelude.contains("public open class UIViewController"), "Prelude must shim UIViewController")
        assertTrue(prelude.contains("public open class UIHostingController"), "Prelude must shim UIHostingController")
        assertTrue(prelude.contains("public open class UINavigationController"), "Prelude must shim UINavigationController")
        assertTrue(prelude.contains("public protocol UIViewControllerRepresentable"), "Prelude must shim UIViewControllerRepresentable")
        assertTrue(prelude.contains("public final class UIPasteboard"), "Prelude must shim UIPasteboard")
        assertTrue(prelude.contains("public final class UIImpactFeedbackGenerator"), "Prelude must shim UIImpactFeedbackGenerator")
        assertTrue(prelude.contains("public struct Canvas"), "Prelude must shim Canvas")
        assertTrue(prelude.contains("public var svgPath: String"), "Prelude Path must serialize SVG path commands")

        // Ensure repo-specific hacks were removed from generic prelude
        assertFalse(prelude.contains("enum ModuleTheme"), "Prelude must not contain repo-specific ModuleTheme")
        assertFalse(prelude.contains("enum L10n"), "Prelude must not contain repo-specific L10n")
        assertFalse(prelude.contains("enum FileSizeFormatter"), "Prelude must not contain repo-specific FileSizeFormatter")
        assertFalse(prelude.contains("struct SpeechLanguageSuggestion"), "Prelude must not contain repo-specific SpeechLanguageSuggestion")
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
}
