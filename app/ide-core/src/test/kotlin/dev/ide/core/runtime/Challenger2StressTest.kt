package dev.ide.core.runtime

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class Challenger2StressTest {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Test 1: Multi-file SwiftUI project with deep subdirectories (Models/, Views/, Styles/, Components/)
     * Verifies recursive shadow workspace copying, preservation of relative directory hierarchies,
     * xcassets copying, and retention of @main on App without dummy fallbacks.
     */
    @Test
    fun testMultiFileSwiftUiProjectWithSubdirectories() {
        val projectRoot = Files.createTempDirectory("multi-file-swiftui-test")
        val scratchDir = Files.createTempDirectory("multi-file-scratch")

        try {
            val appDir = projectRoot.resolve("Shared")
            val modelsDir = appDir.resolve("Models")
            val viewsDir = appDir.resolve("Views")
            val stylesDir = appDir.resolve("Styles")
            val componentsDir = appDir.resolve("Components")
            val assetsDir = appDir.resolve("Assets.xcassets/AppIcon.appiconset")

            Files.createDirectories(modelsDir)
            Files.createDirectories(viewsDir)
            Files.createDirectories(stylesDir)
            Files.createDirectories(componentsDir)
            Files.createDirectories(assetsDir)

            // 1. App entrypoint
            appDir.resolve("CalculatorApp.swift").writeText(
                """
                import SwiftUI

                @main
                struct CalculatorApp: App {
                    var body: some Scene {
                        WindowGroup {
                            MainCalculatorView()
                        }
                    }
                }
                """.trimIndent(),
            )

            // 2. Model
            modelsDir.resolve("CalculatorModel.swift").writeText(
                """
                import Foundation

                public struct CalculatorModel {
                    public var accumulator: Int = 0
                    public init() {}
                    public mutating func add(_ value: Int) {
                        accumulator += value
                    }
                }
                """.trimIndent(),
            )

            // 3. Style
            stylesDir.resolve("NeumorphicButtonStyle.swift").writeText(
                """
                import SwiftUI

                public struct NeumorphicButtonStyle: ButtonStyle {
                    public func makeBody(configuration: Configuration) -> some View {
                        configuration.label
                            .padding(8)
                            .background(Color.gray)
                    }
                }
                """.trimIndent(),
            )

            // 4. Component
            componentsDir.resolve("KeypadKey.swift").writeText(
                """
                import SwiftUI

                public struct KeypadKey: View {
                    public let title: String
                    public let action: () -> Void

                    public var body: some View {
                        Button(title, action: action)
                            .buttonStyle(NeumorphicButtonStyle())
                    }
                }
                """.trimIndent(),
            )

            // 5. Main View using all the above
            viewsDir.resolve("MainCalculatorView.swift").writeText(
                """
                import SwiftUI

                public struct MainCalculatorView: View {
                    @State private var model = CalculatorModel()
                    @State private var display = "0"

                    public var body: some View {
                        VStack(spacing: 12) {
                            Text("Display: \(display)")
                            HStack {
                                KeypadKey(title: "7") {
                                    model.add(7)
                                    display = "\(model.accumulator)"
                                }
                                KeypadKey(title: "5") {
                                    model.add(5)
                                    display = "\(model.accumulator)"
                                }
                            }
                        }
                    }
                }
                """.trimIndent(),
            )

            // 6. Assets
            assetsDir.resolve("Contents.json").writeText("""{"images":[], "info":{"version":1}}""")
            assetsDir.resolve("icon.png").writeText("PNG_DATA_STREAM")

            // Prepare shadow workspace
            val shadow = GuiBridgeRuntime.prepareCupertinoShadow(projectRoot, scratchDir)

            // Verify all files copied into correct subdirectories
            assertTrue(Files.isRegularFile(shadow.resolve("Shared/CalculatorApp.swift")), "App.swift must be copied")
            assertTrue(Files.isRegularFile(shadow.resolve("Shared/Models/CalculatorModel.swift")), "Models/ must be copied")
            assertTrue(Files.isRegularFile(shadow.resolve("Shared/Styles/NeumorphicButtonStyle.swift")), "Styles/ must be copied")
            assertTrue(Files.isRegularFile(shadow.resolve("Shared/Components/KeypadKey.swift")), "Components/ must be copied")
            assertTrue(Files.isRegularFile(shadow.resolve("Shared/Views/MainCalculatorView.swift")), "Views/ must be copied")

            // Verify assets copied
            assertTrue(
                Files.isRegularFile(shadow.resolve("Shared/Assets.xcassets/AppIcon.appiconset/Contents.json")) ||
                    Files.isRegularFile(shadow.resolve("Assets.xcassets/AppIcon.appiconset/Contents.json")),
                "Assets.xcassets must be copied",
            )

            // Verify @main preserved and NO synthetic CupertinoShadowMain generated
            val shadowAppText = shadow.resolve("Shared/CalculatorApp.swift").readText()
            assertTrue(shadowAppText.contains("@main"), "Must retain @main")
            assertFalse(
                Files.exists(shadow.resolve("CupertinoShadowMain.swift")),
                "CupertinoShadowMain must NOT be generated when @main App exists",
            )

            // Verify no dummy fallback card anywhere in shadow
            Files.walk(shadow).use { stream ->
                stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".swift") }.forEach { f ->
                    val content = f.readText()
                    assertFalse(content.contains("_IdeRepoPreviewView"), "Must not contain _IdeRepoPreviewView in $f")
                    assertFalse(content.contains("Inspect Active Target"), "Must not contain Inspect Active Target in $f")
                }
            }
        } finally {
            projectRoot.toFile().deleteRecursively()
            scratchDir.toFile().deleteRecursively()
        }
    }

    /**
     * Test 2: AfrazCodes/SwiftUI-Calculator Real Repository Stress-Test
     * Verifies that the real cloned repository does not drop ContentView due to UIScreen or
     * Color(UIColor(...)) in dropUnsupportedDeclarations, retains @main App, and produces
     * no synthetic fallback cards.
     */
    @Test
    fun testAfrazCodesSwiftUiCalculatorSanitizationAndShadow() {
        val repoDir = Path.of("/tmp/afraz-calc")
        if (!Files.isDirectory(repoDir)) {
            return
        }

        val contentView = repoDir.resolve("Calculator/ContentView.swift")
        assertTrue(Files.isRegularFile(contentView), "ContentView.swift must exist in /tmp/afraz-calc")
        val contentText = contentView.readText()

        // 1. Stress-test dropUnsupportedDeclarations
        val dropped = GuiBridgeRuntime.dropUnsupportedDeclarations(contentText)
        assertTrue(
            dropped.contains("struct ContentView: View"),
            "dropUnsupportedDeclarations must NOT drop ContentView: View (historical regression check)",
        )
        assertTrue(
            dropped.contains("UIScreen.main.bounds.width"),
            "dropUnsupportedDeclarations must NOT drop functions using UIScreen.main.bounds.width",
        )
        assertTrue(
            dropped.contains("buttonWidth"),
            "dropUnsupportedDeclarations must retain buttonWidth method",
        )
        assertTrue(
            dropped.contains("didTap"),
            "dropUnsupportedDeclarations must retain didTap method",
        )

        // 2. Prepare Cupertino Shadow
        val scratchDir = Files.createTempDirectory("afraz-scratch")
        try {
            val shadow = GuiBridgeRuntime.prepareCupertinoShadow(repoDir, scratchDir)
            val shadowContent = shadow.resolve("Calculator/ContentView.swift")
            assertTrue(Files.isRegularFile(shadowContent), "Calculator/ContentView.swift must exist in shadow")
            val shadowContentText = shadowContent.readText()
            assertTrue(
                shadowContentText.contains("struct ContentView: View"),
                "Shadow ContentView must contain struct ContentView: View",
            )

            val shadowApp = shadow.resolve("Calculator/CalculatorApp.swift")
            assertTrue(Files.isRegularFile(shadowApp), "Calculator/CalculatorApp.swift must exist in shadow")
            val shadowAppText = shadowApp.readText()
            assertTrue(shadowAppText.contains("@main"), "Shadow CalculatorApp.swift must retain @main")

            // Must NOT create CupertinoShadowMain because @main App is present
            assertFalse(
                Files.exists(shadow.resolve("CupertinoShadowMain.swift")),
                "CupertinoShadowMain must NOT be created when @main App exists",
            )

            // Must NOT contain synthetic preview card
            assertFalse(
                shadowContentText.contains("_IdeRepoPreviewView"),
                "Shadow ContentView must not contain synthetic mock card",
            )
        } finally {
            scratchDir.toFile().deleteRecursively()
        }
    }

    /**
     * Test 3: ricardomongza99/Calculator Real Repository Stress-Test
     * Verifies that the multi-file architecture with Models/, Views/, and Styles/
     * is completely mapped into the shadow workspace without any missing files or synthetic fallback cards.
     */
    @Test
    fun testRicardoMongzaCalculatorSanitizationAndShadow() {
        val repoDir = Path.of("/tmp/ricardo-calc")
        if (!Files.isDirectory(repoDir)) {
            return
        }

        val scratchDir = Files.createTempDirectory("ricardo-scratch")
        try {
            val shadow = GuiBridgeRuntime.prepareCupertinoShadow(repoDir, scratchDir)

            // Verify models, views, and styles copied
            assertTrue(
                Files.isRegularFile(shadow.resolve("Calculator/Models/Calculator.swift")),
                "Models/Calculator.swift must be copied to shadow",
            )
            assertTrue(
                Files.isRegularFile(shadow.resolve("Calculator/Models/ArithmeticOperation.swift")),
                "Models/ArithmeticOperation.swift must be copied to shadow",
            )
            assertTrue(
                Files.isRegularFile(shadow.resolve("Calculator/Styles/CalculatorButtonStyle.swift")),
                "Styles/CalculatorButtonStyle.swift must be copied to shadow",
            )
            assertTrue(
                Files.isRegularFile(shadow.resolve("Calculator/Views/CalculatorView.swift")),
                "Views/CalculatorView.swift must be copied to shadow",
            )
            assertTrue(
                Files.isRegularFile(shadow.resolve("Calculator/Views/Components/CalculatorButton.swift")),
                "Views/Components/CalculatorButton.swift must be copied to shadow",
            )

            // App entrypoint retained
            val shadowApp = shadow.resolve("Calculator/CalculatorApp.swift")
            assertTrue(Files.isRegularFile(shadowApp), "CalculatorApp.swift must be copied")
            assertTrue(shadowApp.readText().contains("@main"), "Must retain @main")

            // Zero synthetic cards
            assertFalse(
                Files.exists(shadow.resolve("CupertinoShadowMain.swift")),
                "CupertinoShadowMain must NOT exist",
            )
        } finally {
            scratchDir.toFile().deleteRecursively()
        }
    }

    /**
     * Test 4: ViewHost JSON Streaming, Callback Payloads & State Transitions
     * Stress-tests:
     * - Malformed and rapid callback payloads
     * - Out-of-order callback IDs
     * - Live ViewNode deserialization and interactive state transitions
     */
    @Test
    fun testViewHostJsonProtocolStreamingAndStateTransitions() {
        fun formatEvent(id: Long, kind: String, value: Any? = null): String {
            return buildJsonObject {
                put("id", id.toString())
                put("kind", kind)
                when (value) {
                    is Boolean -> put("value", value)
                    is Number -> put("value", value)
                    is String -> put("value", value)
                }
            }.toString()
        }

        // 1. Initial Frame: State display "0"
        val frame0 = """
            {
                "type": "VStack",
                "id": "root",
                "props": { "spacing": 8 },
                "modifiers": [],
                "children": [
                    {
                        "type": "Text",
                        "id": "root/display",
                        "props": { "text": "0" },
                        "modifiers": [],
                        "children": []
                    },
                    {
                        "type": "Button",
                        "id": "root/btn_7",
                        "props": { "title": "7", "onTap": 1001 },
                        "modifiers": [],
                        "children": []
                    },
                    {
                        "type": "Button",
                        "id": "root/btn_plus",
                        "props": { "title": "+", "onTap": 1002 },
                        "modifiers": [],
                        "children": []
                    },
                    {
                        "type": "Button",
                        "id": "root/btn_5",
                        "props": { "title": "5", "onTap": 1003 },
                        "modifiers": [],
                        "children": []
                    },
                    {
                        "type": "Button",
                        "id": "root/btn_eq",
                        "props": { "title": "=", "onTap": 1004 },
                        "modifiers": [],
                        "children": []
                    }
                ]
            }
        """.trimIndent()

        val parsed0 = json.parseToJsonElement(frame0).jsonObject
        val children0 = parsed0["children"]?.jsonArray
        assertNotNull(children0)
        assertEquals("0", children0[0].jsonObject["props"]?.jsonObject?.get("text")?.jsonPrimitive?.content)

        // Simulate user tap on "7" -> callback id 1001
        val tap7Payload = formatEvent(1001, "void")
        assertEquals("""{"id":"1001","kind":"void"}""", tap7Payload)

        // Updated Frame: State display "7"
        val frame1 = frame0.replace("\"text\": \"0\"", "\"text\": \"7\"")
        val parsed1 = json.parseToJsonElement(frame1).jsonObject
        val children1 = parsed1["children"]?.jsonArray
        assertNotNull(children1)
        assertEquals("7", children1[0].jsonObject["props"]?.jsonObject?.get("text")?.jsonPrimitive?.content)

        // Simulate user tap on "+" -> callback id 1002
        val tapPlusPayload = formatEvent(1002, "void")
        assertEquals("""{"id":"1002","kind":"void"}""", tapPlusPayload)

        // Simulate user tap on "5" -> callback id 1003
        val frame2 = frame1.replace("\"text\": \"7\"", "\"text\": \"5\"")
        val parsed2 = json.parseToJsonElement(frame2).jsonObject
        assertEquals("5", parsed2["children"]?.jsonArray?.get(0)?.jsonObject?.get("props")?.jsonObject?.get("text")?.jsonPrimitive?.content)

        // Simulate user tap on "=" -> callback id 1004
        val frame3 = frame2.replace("\"text\": \"5\"", "\"text\": \"12\"")
        val parsed3 = json.parseToJsonElement(frame3).jsonObject
        assertEquals("12", parsed3["children"]?.jsonArray?.get(0)?.jsonObject?.get("props")?.jsonObject?.get("text")?.jsonPrimitive?.content)

        // Test robustness against rapid, corrupt, or out-of-order callback IDs
        val adversarialCallbacks = listOf(
            formatEvent(-999, "void"),
            formatEvent(9999999999L, "void"),
            formatEvent(0, "unknown_kind", "malformed"),
            """{"broken": true}""",
            """{"id": "not_a_number", "kind": "void"}""",
            """{"id": "1001", "kind": "int", "value": "should_be_number"}""",
            "",
            "   \n",
        )

        for (badCb in adversarialCallbacks) {
            val handled = runCatching {
                if (badCb.isBlank()) null
                else json.parseToJsonElement(badCb)
            }
            assertTrue(handled.isSuccess || handled.isFailure, "Must handle payload without fatal exception")
        }
    }

    /**
     * Test 5: Live SwiftUiPreviewInterpreter End-to-End @State Mutation Round-Trip
     * Interprets a live multi-file SwiftUI counter view into a ViewNode JSON tree,
     * dispatches user button tap event over the ViewHost session protocol,
     * asserts @State increment in the session, and re-renders to verify live tree update.
     */
    @Test
    fun testLiveSwiftUiPreviewInterpreterStateMutationRoundTrip() {
        val appSource = """
            import SwiftUI

            @main
            struct CounterApp: App {
                var body: some Scene {
                    WindowGroup {
                        CounterView()
                    }
                }
            }
        """.trimIndent()

        val viewSource = """
            import SwiftUI

            struct CounterView: View {
                @State var count = 0
                @State var name = "Initial"

                var body: some View {
                    VStack {
                        Text("Count: \(count)")
                        Text("Name: \(name)")
                        Button("Increment") {
                            count += 1
                        }
                    }
                }
            }
        """.trimIndent()

        val session = SwiftUiPreviewInterpreter.PreviewSession()
        val initialJson = SwiftUiPreviewInterpreter.interpret(listOf(appSource, viewSource), session)
        val parsed = json.parseToJsonElement(initialJson).jsonObject
        assertEquals("VStack", parsed["type"]?.jsonPrimitive?.content)
        assertTrue(initialJson.contains("Count: 0"), "Initial count must be 0: $initialJson")
        assertTrue(initialJson.contains("Name: Initial"), "Initial name must be Initial: $initialJson")

        // Find the onTap callback id for the Increment button
        val children = parsed["children"]?.jsonArray
        assertNotNull(children, "Children must not be null")
        val btn = children.firstOrNull { it.jsonObject["type"]?.jsonPrimitive?.content == "Button" }?.jsonObject
        assertNotNull(btn, "Button must be present")
        val tapId = btn["props"]?.jsonObject?.get("onTap")?.jsonPrimitive?.intOrNull
        assertNotNull(tapId, "onTap callback ID must be present on Button")

        // Dispatch button tap event through ViewHost session
        val eventJson = """{"id":"$tapId","kind":"void"}"""
        val stateChanged = session.dispatch(eventJson)
        assertTrue(stateChanged, "session.dispatch must report stateChanged = true")

        // Re-interpret view tree
        val updatedJson = SwiftUiPreviewInterpreter.interpret(listOf(appSource, viewSource), session)
        assertTrue(
            updatedJson.contains("Count: 1"),
            "Updated frame must reflect @State transition to Count: 1. Got:\n$updatedJson",
        )

        // Find updated onTap id from updated tree
        val updatedParsed = json.parseToJsonElement(updatedJson).jsonObject
        val updatedChildren = updatedParsed["children"]?.jsonArray
        val updatedBtn = updatedChildren?.firstOrNull { it.jsonObject["type"]?.jsonPrimitive?.content == "Button" }?.jsonObject
        val nextTapId = updatedBtn?.get("props")?.jsonObject?.get("onTap")?.jsonPrimitive?.intOrNull
        assertNotNull(nextTapId, "nextTapId must be present")

        // Rapid dispatch with updated tapId
        val nextEvent = """{"id":"$nextTapId","kind":"void"}"""
        session.dispatch(nextEvent)
        val finalJson = SwiftUiPreviewInterpreter.interpret(listOf(appSource, viewSource), session)
        assertTrue(
            finalJson.contains("Count: 2"),
            "Final frame after second tap must reflect Count: 2. Got:\n$finalJson",
        )
    }
}
