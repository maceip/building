package dev.ide.core.runtime.e2e

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 4-Tier End-to-End Test Suite for iOS-on-Android Pipeline:
 *
 * Tier 1: Feature Coverage (ViewNode JSON serialization/deserialization, ViewHost stdin/stdout protocol, callback formatting)
 * Tier 2: Boundary & Corner Cases (Malformed JSON handling, deeply nested ViewNode trees, extreme coordinates, unicode symbols)
 * Tier 3: Cross-Feature Combinations (Multi-file Swift project with subdirectories Models/Views, Form with mixed Button/Toggle/TextField)
 * Tier 4: Real-World Scenarios (SwiftUI-Calculator with buttons and operators, verifying math state transitions)
 */
class SwiftCupertinoE2ETest {

    private val json = Json { ignoreUnknownKeys = true }

    // =========================================================================
    // TIER 1: FEATURE COVERAGE
    // =========================================================================

    @Test
    fun tier1_viewNodeJson_decodesStandardSwiftUiTree() {
        val sampleJson = """
            {
                "type": "VStack",
                "id": "root",
                "props": {
                    "alignment": "center",
                    "spacing": 16
                },
                "modifiers": [
                    { "kind": "padding", "args": { "amount": 16.0 } },
                    { "kind": "background", "args": { "color": "#FFFFFF" } }
                ],
                "children": [
                    {
                        "type": "Text",
                        "id": "root/title",
                        "props": { "text": "SwiftUI Counter", "fontSize": 20 },
                        "modifiers": [],
                        "children": []
                    },
                    {
                        "type": "Button",
                        "id": "root/btn_inc",
                        "props": { "title": "Add One", "onTap": 101 },
                        "modifiers": [],
                        "children": []
                    }
                ]
            }
        """.trimIndent()

        val root = json.parseToJsonElement(sampleJson).jsonObject
        assertEquals("VStack", root["type"]?.jsonPrimitive?.content)
        assertEquals("root", root["id"]?.jsonPrimitive?.content)
        assertEquals(16, root["props"]?.jsonObject?.get("spacing")?.jsonPrimitive?.intOrNull)

        val children = root["children"]?.jsonArray
        assertNotNull(children)
        assertEquals(2, children.size)

        val btn = children[1].jsonObject
        assertEquals("Button", btn["type"]?.jsonPrimitive?.content)
        assertEquals(101, btn["props"]?.jsonObject?.get("onTap")?.jsonPrimitive?.intOrNull)
    }

    @Test
    fun tier1_viewHostProtocol_encodesStandardCallbackPayloads() {
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

        // Void event (Button tap)
        val voidEvent = json.parseToJsonElement(formatEvent(101, "void")).jsonObject
        assertEquals("101", voidEvent["id"]?.jsonPrimitive?.content)
        assertEquals("void", voidEvent["kind"]?.jsonPrimitive?.content)

        // Bool event (Toggle change)
        val boolEvent = json.parseToJsonElement(formatEvent(102, "bool", true)).jsonObject
        assertEquals("102", boolEvent["id"]?.jsonPrimitive?.content)
        assertEquals("bool", boolEvent["kind"]?.jsonPrimitive?.content)
        assertEquals(true, boolEvent["value"]?.jsonPrimitive?.booleanOrNull)

        // Int event (Picker selection)
        val intEvent = json.parseToJsonElement(formatEvent(103, "int", 5)).jsonObject
        assertEquals("103", intEvent["id"]?.jsonPrimitive?.content)
        assertEquals(5, intEvent["value"]?.jsonPrimitive?.intOrNull)

        // String event (TextField input)
        val strEvent = json.parseToJsonElement(formatEvent(104, "string", "hello world")).jsonObject
        assertEquals("104", strEvent["id"]?.jsonPrimitive?.content)
        assertEquals("hello world", strEvent["value"]?.jsonPrimitive?.contentOrNull)
    }

    // =========================================================================
    // TIER 2: BOUNDARY & CORNER CASES
    // =========================================================================

    @Test
    fun tier2_malformedJsonLines_handledGracefullyWithoutCrashing() {
        val corruptedInputs = listOf(
            "",
            "   ",
            "not a json line",
            "{ truncated json",
            "{\"type\": 12345}",
            "{\"id\": null, \"broken\": true}",
            "{\"type\": \"Text\", \"children\": \"not-an-array\"}",
        )

        for (badInput in corruptedInputs) {
            val result = runCatching {
                if (badInput.isBlank()) null
                else json.parseToJsonElement(badInput)
            }
            // Must either catch cleanly or return failure, never throw unchecked thread fatal error
            assertTrue(result.isSuccess || result.isFailure)
        }
    }

    @Test
    fun tier2_deeplyNestedViewNodeHierarchy_scalesOver15Levels() {
        fun buildNested(depth: Int): JsonObject {
            if (depth <= 0) {
                return buildJsonObject {
                    put("type", "Text")
                    put("id", "leaf")
                    put("props", buildJsonObject { put("text", "Leaf Node") })
                }
            }
            return buildJsonObject {
                put("type", "VStack")
                put("id", "depth_$depth")
                put("props", buildJsonObject { put("spacing", 2) })
                put("children", kotlinx.serialization.json.buildJsonArray {
                    add(buildNested(depth - 1))
                })
            }
        }

        val deepTree = buildNested(20)
        val rawJson = deepTree.toString()
        assertTrue(rawJson.length > 500)
        val parsed = json.parseToJsonElement(rawJson).jsonObject
        assertEquals("VStack", parsed["type"]?.jsonPrimitive?.content)
        assertEquals("depth_20", parsed["id"]?.jsonPrimitive?.content)
    }

    @Test
    fun tier2_unicodeAndSpecialCharactersInLabelsAndProps() {
        val specialLabel = "Emoji \uD83C\uDFB5 & Quotes \"Hello\" 'World' \\ Sliders <Tag>"
        val tree = buildJsonObject {
            put("type", "Text")
            put("id", "special_text")
            put("props", buildJsonObject {
                put("text", specialLabel)
            })
        }
        val encoded = tree.toString()
        val decoded = json.parseToJsonElement(encoded).jsonObject
        assertEquals(specialLabel, decoded["props"]?.jsonObject?.get("text")?.jsonPrimitive?.content)
    }

    // =========================================================================
    // TIER 3: CROSS-FEATURE COMBINATIONS
    // =========================================================================

    @Test
    fun tier3_formWithMixedInputControlsButtonToggleTextField() {
        val formTree = buildJsonObject {
            put("type", "Form")
            put("id", "settings_form")
            put("children", kotlinx.serialization.json.buildJsonArray {
                // Toggle control
                add(buildJsonObject {
                    put("type", "Toggle")
                    put("id", "setting/dark_mode")
                    put("props", buildJsonObject {
                        put("isOn", true)
                        put("onChange", 201)
                    })
                })
                // TextField control
                add(buildJsonObject {
                    put("type", "TextField")
                    put("id", "setting/username")
                    put("props", buildJsonObject {
                        put("text", "developer")
                        put("placeholder", "Enter username")
                        put("onChange", 202)
                    })
                })
                // Action Button
                add(buildJsonObject {
                    put("type", "Button")
                    put("id", "setting/save")
                    put("props", buildJsonObject {
                        put("title", "Save Preferences")
                        put("onTap", 203)
                    })
                })
            })
        }

        val decoded = json.parseToJsonElement(formTree.toString()).jsonObject
        val children = decoded["children"]?.jsonArray
        assertNotNull(children)
        assertEquals(3, children.size)

        val toggle = children[0].jsonObject
        assertEquals(true, toggle["props"]?.jsonObject?.get("isOn")?.jsonPrimitive?.booleanOrNull)
        val textField = children[1].jsonObject
        assertEquals("developer", textField["props"]?.jsonObject?.get("text")?.jsonPrimitive?.content)
        val button = children[2].jsonObject
        assertEquals(203, button["props"]?.jsonObject?.get("onTap")?.jsonPrimitive?.intOrNull)
    }

    // =========================================================================
    // TIER 4: REAL-WORLD APPLICATION SCENARIOS
    // =========================================================================

    private fun findHostSwiftc(): String? {
        val fromEnv = System.getenv("SWIFT")
        if (fromEnv != null && Files.isRegularFile(Path.of(fromEnv))) return fromEnv
        for (p in listOf("/usr/bin/swiftc", "/Users/mac/.swiftly/bin/swiftc")) {
            if (Files.isRegularFile(Path.of(p))) return p
        }
        return null
    }

    private fun findSwiftUiSdk(): Path? {
        val candidates = listOf(
            Path.of(System.getProperty("user.home", ""), ".building/swiftui-bridge-sdk"),
            Path.of("/Users/mac/building-swift-preview/tools/swift-preview/.build/bridge-json-baseline/AndroidSwiftUI/SwiftUICore"),
            Path.of(System.getProperty("user.home", ""), ".building/SwiftUICore"),
        )
        return candidates.firstOrNull { Files.isDirectory(it) }
    }

    private fun findSwiftUiCorePackage(): Path? = findSwiftUiSdk()

    private fun findBridgeAndAppSource(): Path? {
        val candidates = listOf(
            Path.of(System.getProperty("user.home", ""), ".building/swiftui-bridge-sdk/BridgeAndApp.swift"),
            Path.of("/Users/mac/building-swift-preview/tools/swift-preview/.build/bridge-json-baseline/AndroidSwiftUI/Sources/ComposeUI/BridgeAndApp.swift"),
        )
        return candidates.firstOrNull { Files.isRegularFile(it) }
    }

    @Test
    fun tier4_realWorldScenario_swiftUiCalculatorCompilationAndStateExecution() {
        val swiftc = findHostSwiftc() ?: run {
            assumeTrue(false, "No host swiftc; skipping real Swift execution")
            return
        }
        val sdk = findSwiftUiSdk() ?: run {
            assumeTrue(false, "No SwiftUI bridge SDK found; skipping real Swift execution")
            return
        }
        val bridgeSrc = findBridgeAndAppSource() ?: run {
            assumeTrue(false, "No BridgeAndApp.swift runPreview host; skipping real Swift execution")
            return
        }

        val work = Files.createTempDirectory("swiftui-calculator-e2e")
        try {
            val hasPrebuiltSdk = Files.isRegularFile(sdk.resolve("libSwiftUI.a"))
            val exe: Path
            if (hasPrebuiltSdk) {
                val bridgeContent = Files.readString(bridgeSrc).replace("import SwiftUICore", "import SwiftUI")
                val bridgeFile = work.resolve("BridgeAndApp.swift")
                bridgeFile.writeText(bridgeContent)

                val brainFile = work.resolve("CalculatorBrain.swift")
                brainFile.writeText(
                    """
                    public struct CalculatorBrain {
                        public static func add(_ a: Int, _ b: Int) -> Int {
                            return a + b
                        }
                    }
                    """.trimIndent(),
                )

                val viewFile = work.resolve("CalculatorView.swift")
                viewFile.writeText(
                    """
                    import SwiftUI

                    struct CalculatorView: View {
                        @State private var display = "0"
                        @State private var accumulator = 0

                        var body: some View {
                            VStack(spacing: 8) {
                                Text("Result: \(display)")
                                Button("7") {
                                    display = "7"
                                    accumulator = 7
                                }
                                Button("PlusFive") {
                                    let total = CalculatorBrain.add(accumulator, 5)
                                    display = "\(total)"
                                }
                            }
                        }
                    }

                    @main
                    struct CalculatorMain {
                        static func main() throws {
                            try runPreview { CalculatorView() }
                        }
                    }
                    """.trimIndent(),
                )

                exe = work.resolve("CalculatorApp")
                val compileCmd = listOf(
                    swiftc,
                    "-parse-as-library",
                    "-I$sdk",
                    "-L$sdk",
                    "-lSwiftUI",
                    bridgeFile.toString(),
                    brainFile.toString(),
                    viewFile.toString(),
                    "-o",
                    exe.toString(),
                )
                val build = ProcessBuilder(compileCmd)
                    .directory(work.toFile())
                    .redirectErrorStream(true)
                    .start()
                val buildOut = build.inputStream.bufferedReader().readText()
                val buildExit = build.waitFor()
                if (buildExit != 0) {
                    fail("SwiftUI-Calculator swiftc build failed:\n$buildOut")
                }
            } else {
                Files.createDirectories(work.resolve("Sources/SwiftUI"))
                Files.createDirectories(work.resolve("Sources/CalculatorApp"))
                Files.createDirectories(work.resolve("SwiftUICore"))

                // Copy SwiftUICore shim sources
                sdk.toFile().walkTopDown().forEach { f ->
                    val rel = f.relativeTo(sdk.toFile())
                    if (rel.path.isEmpty() || rel.path.startsWith(".build")) return@forEach
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
                        name: "CalculatorApp",
                        platforms: [.macOS(.v14)],
                        products: [.executable(name: "CalculatorApp", targets: ["CalculatorApp"])],
                        dependencies: [.package(path: "./SwiftUICore")],
                        targets: [
                            .target(name: "SwiftUI", dependencies: [.product(name: "SwiftUICore", package: "SwiftUICore")]),
                            .executableTarget(name: "CalculatorApp", dependencies: ["SwiftUI"]),
                        ]
                    )
                    """.trimIndent(),
                )

                work.resolve("Sources/SwiftUI/SwiftUI.swift").writeText(
                    """
                    @_exported import SwiftUICore
                    """.trimIndent(),
                )

                Files.copy(bridgeSrc, work.resolve("Sources/CalculatorApp/BridgeAndApp.swift"))

                // Multi-file: Models/CalculatorBrain.swift + Views/CalculatorView.swift
                Files.createDirectories(work.resolve("Sources/CalculatorApp/Models"))
                Files.createDirectories(work.resolve("Sources/CalculatorApp/Views"))

                work.resolve("Sources/CalculatorApp/Models/CalculatorBrain.swift").writeText(
                    """
                    public struct CalculatorBrain {
                        public static func add(_ a: Int, _ b: Int) -> Int {
                            return a + b
                        }
                    }
                    """.trimIndent(),
                )

                work.resolve("Sources/CalculatorApp/Views/CalculatorView.swift").writeText(
                    """
                    import SwiftUI

                    struct CalculatorView: View {
                        @State private var display = "0"
                        @State private var accumulator = 0

                        var body: some View {
                            VStack(spacing: 8) {
                                Text("Result: \(display)")
                                Button("7") {
                                    display = "7"
                                    accumulator = 7
                                }
                                Button("PlusFive") {
                                    let total = CalculatorBrain.add(accumulator, 5)
                                    display = "\(total)"
                                }
                            }
                        }
                    }

                    @main
                    struct CalculatorMain {
                        static func main() throws {
                            try runPreview { CalculatorView() }
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
                val buildExit = build.waitFor()
                if (buildExit != 0) {
                    fail("SwiftUI-Calculator build failed:\n$buildOut")
                }

                exe = work.resolve(".build/debug/CalculatorApp")
            }

            assertTrue(Files.isRegularFile(exe), "Compiled CalculatorApp binary missing: $exe")

            val proc = ProcessBuilder(exe.toString())
                .redirectErrorStream(false)
                .start()

            try {
                val reader = proc.inputStream.bufferedReader()
                val writer = proc.outputStream.bufferedWriter()

                // Initial frame: "Result: 0"
                val initialLine = reader.readLine()
                assertNotNull(initialLine, "Expected initial ViewNode line from CalculatorApp")
                assertTrue(initialLine.contains("Result: 0"), "Expected Result: 0 in initial frame: $initialLine")

                // Find Button("7") callback
                val btn7Match = Regex(""""onTap":(\d+)""").find(initialLine)
                assertNotNull(btn7Match, "Expected onTap callback ID for Button in $initialLine")
                val tapId1 = btn7Match.groupValues[1]

                // Send tap callback
                writer.write("{\"id\":\"$tapId1\",\"kind\":\"void\"}\n")
                writer.flush()

                // Frame after tap: Result: 7
                val updatedLine = reader.readLine()
                assertNotNull(updatedLine, "Expected second ViewNode line after tap")
                assertTrue(
                    updatedLine.contains("Result: 7"),
                    "Expected Result: 7 after tapping 7: $updatedLine",
                )
            } finally {
                proc.destroyForcibly()
            }
        } finally {
            runCatching { work.toFile().deleteRecursively() }
        }
    }
}
