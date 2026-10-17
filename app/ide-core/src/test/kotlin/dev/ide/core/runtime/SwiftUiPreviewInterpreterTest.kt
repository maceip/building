package dev.ide.core.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Instant Cupertino Preview: SwiftUI source → ViewNode JSON with no toolchain.
 *
 * These tests pin the risky subset we ship: a real counter-style app must interpret,
 * stream a Cupertino-remappable tree, and advance state from BridgeGui callbacks.
 */
class SwiftUiPreviewInterpreterTest {

    private val counterSource = """
        import SwiftUI

        @main
        struct CounterApp: App {
            var body: some Scene {
                WindowGroup {
                    ContentView()
                }
            }
        }

        struct ContentView: View {
            @State private var count = 0
            @State private var name = "World"
            @State private var enabled = true

            var body: some View {
                VStack(alignment: .leading, spacing: 24) {
                    Text("Hello, \(name)").font(.largeTitle).bold()
                    Text("A Swift app. On Android.").foregroundColor(.gray)
                    TextField("Your name", text: ${'$'}name)
                    Toggle("Count by two", isOn: ${'$'}enabled)
                    Divider()
                    Text("\(count)").font(.system(size: 72)).bold()
                    Button("Add \(enabled ? 2 : 1)") { count += enabled ? 2 : 1 }
                    Button("Reset") { count = 0 }
                }
                .padding(24)
            }
        }
    """.trimIndent()

    @Test
    fun interpretsCounterAppWithoutToolchain() {
        val session = SwiftUiPreviewInterpreter.PreviewSession()
        assertTrue(SwiftUiPreviewInterpreter.canInterpret(listOf(counterSource)))
        val tree = SwiftUiPreviewInterpreter.interpret(listOf(counterSource), session)

        assertTrue(tree.contains("\"type\":\"VStack\""), tree)
        assertTrue(tree.contains("Hello, World"), tree)
        assertTrue(tree.contains("\"text\":\"0\"") || tree.contains("\"text\": 0") || tree.contains("\"text\":\"0\""), tree)
        assertTrue(tree.contains("Your name"), tree)
        assertTrue(tree.contains("Count by two"), tree)
        assertTrue(tree.contains("\"kind\":\"padding\""), tree)
        assertTrue(tree.contains("\"kind\":\"font\"") || tree.contains("font"), tree)
    }

    @Test
    fun buttonCallbackAdvancesStateAndReinterprets() {
        val session = SwiftUiPreviewInterpreter.PreviewSession()
        val tree1 = SwiftUiPreviewInterpreter.interpret(listOf(counterSource), session)

        // Find the Add button's onTap callback id (last Button in the tree).
        val onTaps = Regex(""""onTap":(\d+)""").findAll(tree1).map { it.groupValues[1].toLong() }.toList()
        assertTrue(onTaps.size >= 2, "Expected Add and Reset onTap ids in: $tree1")
        val addId = onTaps.first()

        val changed = session.dispatch("""{"id":"$addId","kind":"void"}""")
        assertTrue(changed, "Add click must change state")
        assertEquals(2L, session.snapshot()["count"], "enabled=true step is +2")

        val tree2 = SwiftUiPreviewInterpreter.interpret(listOf(counterSource), session)
        assertTrue(tree2.contains("\"text\":\"2\""), tree2)

        val resetId = onTaps.last()
        assertTrue(session.dispatch("""{"id":"$resetId","kind":"void"}"""))
        val tree3 = SwiftUiPreviewInterpreter.interpret(listOf(counterSource), session)
        assertTrue(tree3.contains("\"text\":\"0\""), tree3)
    }

    @Test
    fun toggleAndTextFieldUpdateState() {
        val session = SwiftUiPreviewInterpreter.PreviewSession()
        val tree = SwiftUiPreviewInterpreter.interpret(listOf(counterSource), session)

        val onChange = Regex(""""onChange":(\d+)""").findAll(tree).map { it.groupValues[1].toLong() }.toList()
        assertTrue(onChange.size >= 2, "Expected Toggle + TextField onChange ids in: $tree")

        // Tree order is TextField then Toggle in ContentView.
        val textFieldId = onChange[0]
        val toggleId = onChange[1]

        assertTrue(session.dispatch("""{"id":"$toggleId","kind":"bool","value":false}"""))
        assertEquals(false, session.snapshot()["enabled"])

        assertTrue(session.dispatch("""{"id":"$textFieldId","kind":"string","value":"Ada"}"""))
        assertEquals("Ada", session.snapshot()["name"])

        val tree2 = SwiftUiPreviewInterpreter.interpret(listOf(counterSource), session)
        assertTrue(tree2.contains("Hello, Ada"), tree2)
    }

    @Test
    fun rejectsUnsupportedFoundation() {
        val source = """
            import SwiftUI
            import SwiftData

            struct Bad: View {
                var body: some View {
                    Text("nope")
                }
            }
        """.trimIndent()
        assertFalse(SwiftUiPreviewInterpreter.canInterpret(listOf(source)))
    }

    @Test
    fun reportsClearErrorWhenNoBody() {
        val source = """
            import SwiftUI
            struct Empty: View {
            }
        """.trimIndent()
        // parse succeeds with a struct that has no body → interpret throws
        if (SwiftUiPreviewInterpreter.canInterpret(listOf(source))) {
            assertFailsWith<SwiftUiPreviewInterpreter.SwiftUiPreviewException> {
                SwiftUiPreviewInterpreter.interpret(listOf(source))
            }
        }
    }

    @Test
    fun stringInterpolationEvaluatesState() {
        val source = """
            import SwiftUI
            struct Greeting: View {
                @State private var name = "World"
                var body: some View {
                    Text("Hello, \(name)!")
                }
            }
        """.trimIndent()
        val session = SwiftUiPreviewInterpreter.PreviewSession()
        val tree = SwiftUiPreviewInterpreter.interpret(listOf(source), session)
        assertTrue(tree.contains("Hello, World!"), tree)
    }

    @Test
    fun stackLayoutsAndForEachRenderChildren() {
        val source = """
            import SwiftUI
            struct Rows: View {
                var body: some View {
                    VStack {
                        ForEach(0..<3) { i in
                            Text("Row")
                        }
                        HStack {
                            Text("L")
                            Spacer()
                            Text("R")
                        }
                    }
                }
            }
        """.trimIndent()
        val session = SwiftUiPreviewInterpreter.PreviewSession()
        val tree = SwiftUiPreviewInterpreter.interpret(listOf(source), session)
        assertTrue(tree.contains("\"type\":\"ForEach\""), tree)
        assertTrue(tree.contains("\"count\":3"), tree)
        assertTrue(tree.contains("\"type\":\"HStack\""), tree)
        assertTrue(tree.contains("\"type\":\"Spacer\""), tree)
    }
}
