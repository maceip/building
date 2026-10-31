@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
package com.pureswift.swiftui

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Rule
import org.junit.Test
import kotlin.test.*

class CupertinoTextEntryTests {
    @get:Rule val compose = createComposeRule()
    private val originalSink = SwiftBridge.sink
    @After fun restoreSink() { SwiftBridge.sink = originalSink }

    @Test fun unchangedInitialTreeDoesNotUndoNativeInput() {
        val state = CupertinoTextInputState(TextFieldValue("Old"), false)
        val local = TextFieldValue("Old-device", TextRange(7), TextRange(4, 10))
        state.edit(local, sentToSwift = true)
        state.receive(TextFieldValue("Old"))
        assertEquals(local, state.value)
    }

    @Test fun delayedStringEchoKeepsNewerTextCursorAndComposition() {
        val state = CupertinoTextInputState(TextFieldValue("Old"), false)
        state.edit(TextFieldValue("Old-d", TextRange(5)), sentToSwift = true)
        val local = TextFieldValue("Old-device", TextRange(7, 5), TextRange(4, 10))
        state.edit(local, sentToSwift = true)
        state.receive(TextFieldValue("Old-d"))
        assertEquals(local, state.value)
        state.receive(TextFieldValue("Old-device"))
        assertEquals(local, state.value)
    }

    @Test fun coalescedEchoAcknowledgesSkippedIntermediateEdits() {
        val state = CupertinoTextInputState(TextFieldValue(""), false)
        for (text in listOf("1", "12", "123")) state.edit(TextFieldValue(text, TextRange(text.length)), true)
        state.receive(TextFieldValue("123"))
        val local = TextFieldValue("1234", TextRange(2))
        state.edit(local, true)
        state.receive(TextFieldValue("123"))
        assertEquals(local, state.value)
        state.receive(TextFieldValue("1234"))
        assertEquals(local, state.value)
        state.receive(TextFieldValue("Reset"))
        assertEquals(TextFieldValue("Reset", TextRange(5)), state.value)
    }

    @Test fun distinctProgrammaticChangeWinsOverPendingInputAndClearsComposition() {
        val state = CupertinoTextInputState(TextFieldValue("Old"), false)
        state.edit(TextFieldValue("Old-typed", TextRange(6), TextRange(4, 9)), true)
        state.receive(TextFieldValue("Replacement"))
        assertEquals(TextFieldValue("Replacement", TextRange(11)), state.value)
    }

    @Test fun editingEchoCannotRewindNewerSelectionOrComposition() {
        val state = CupertinoTextInputState(TextFieldValue("text"), true)
        val first = TextFieldValue("text", TextRange(1), TextRange(0, 2))
        val next = TextFieldValue("text", TextRange(3), TextRange(2, 4))
        state.edit(first, true)
        state.edit(next, true)
        state.receive(first)
        assertEquals(next, state.value)
        state.receive(next)
        assertEquals(next, state.value)
        val explicitSelection = TextFieldValue("text", TextRange(4, 0))
        state.receive(explicitSelection)
        assertEquals(explicitSelection, state.value)
    }

    @Test fun selectionOnlyNativeChangeSurvivesStringAcknowledgement() {
        val state = CupertinoTextInputState(TextFieldValue("Old"), false)
        state.edit(TextFieldValue("Edited", TextRange(6)), true)
        state.edit(TextFieldValue("Edited", TextRange(2, 4)), false)
        state.receive(TextFieldValue("Edited"))
        assertEquals(TextRange(2, 4), state.value.selection)
    }

    @Test fun renderedPlainInputSurvivesDelayedSwiftEcho() = renderedDelayedEcho("CupertinoTextField")
    @Test fun renderedBorderedInputSurvivesDelayedSwiftEcho() = renderedDelayedEcho("CupertinoBorderedTextField")
    @Test fun renderedSearchInputSurvivesDelayedSwiftEcho() = renderedDelayedEcho("CupertinoSearchTextField")
    @Test fun renderedSectionInputSurvivesDelayedSwiftEchoAndClear() = renderedDelayedEcho("SectionScope.SectionTextField")
    @Test fun renderedLazySectionInputSurvivesDelayedSwiftEchoAndClear() = renderedDelayedEcho("LazySectionScope.textField")

    private fun renderedDelayedEcho(name: String) {
        val emitted = mutableListOf<String>()
        SwiftBridge.sink = object : CallbackSink {
            override fun invokeVoid(id: Long) = Unit
            override fun invokeBool(id: Long, value: Boolean) = Unit
            override fun invokeDouble(id: Long, value: Double) = Unit
            override fun invokeInt(id: Long, value: Int) = Unit
            override fun invokeString(id: Long, value: String) { emitted += value }
        }
        var external by mutableStateOf("Old")
        compose.setContent {
            val field = ViewNode(type = "Composable", id = "delayed-input", props = buildJsonObject {
                put("name", name); put("value", external); put("onValueChange", 901)
            })
            fun wrap(surface: String, child: ViewNode): ViewNode = ViewNode(type = "Composable", id = surface,
                props = buildJsonObject { put("name", surface) }, children = listOf(child))
            Render(when (name) {
                "SectionScope.SectionTextField" -> wrap("CupertinoSection", field)
                "LazySectionScope.textField" -> wrap("CupertinoLazyColumn", wrap("LazyListScope.section", field))
                else -> field
            })
        }
        val field = compose.onNode(hasSetTextAction())
        field.performTextReplacement("A")
        field.performTextReplacement("AB")
        field.performTextInputSelection(TextRange(1))
        compose.runOnIdle { external = "A" }
        field.assertTextEquals("AB")
        field.performTextInput("X")
        field.assertTextEquals("AXB")
        compose.runOnIdle { external = "AB" }
        field.assertTextEquals("AXB")
        compose.runOnIdle { external = "AXB" }
        field.assertTextEquals("AXB")
        compose.runOnIdle {
            assertEquals(listOf("A", "AB", "AXB"), emitted)
            external = "Programmatic reset"
        }
        field.assertTextEquals("Programmatic reset")
        if (name == "CupertinoSearchTextField" || name.contains("SectionScope.")) {
            field.performTextReplacement("Pending clear")
            if (name == "CupertinoSearchTextField") compose.onNodeWithText("Cancel").performClick()
            else compose.onNodeWithContentDescription("Clear", useUnmergedTree = true).performTouchInput { click() }
            field.assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
            // The render for typing may arrive only after the subsequent clear.
            compose.runOnIdle { external = "Pending clear" }
            field.assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
            compose.runOnIdle {
                assertEquals("", emitted.last())
                external = ""
            }
            field.assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        }
    }
}
