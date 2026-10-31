@file:OptIn(io.github.alexzhirkevich.cupertino.ExperimentalCupertinoApi::class,
    io.github.alexzhirkevich.cupertino.InternalCupertinoApi::class)
package com.pureswift.swiftui

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.test.junit4.createComposeRule
import com.arkivanov.essenty.lifecycle.*
import io.github.alexzhirkevich.cupertino.*
import kotlinx.serialization.json.*
import org.junit.Test
import org.junit.Rule
import org.junit.After
import kotlin.test.*

class CupertinoStateBridgeTests {
    @get:Rule val compose = createComposeRule()
    private val originalSink = SwiftBridge.sink
    private val events = mutableListOf<Pair<Long, String>>()
    private val requested = mutableListOf<Int>()
    @After fun restore() { SwiftBridge.sink = originalSink }
    private fun sink() { SwiftBridge.sink = object : CallbackSink {
        override fun invokeVoid(id: Long) { events += id to "void" }
        override fun invokeBool(id: Long, value: Boolean) { events += id to value.toString() }
        override fun invokeDouble(id: Long, value: Double) { events += id to value.toString() }
        override fun invokeInt(id: Long, value: Int) { events += id to value.toString() }
        override fun invokeString(id: Long, value: String) { events += id to value }
        override fun itemNode(id: Long, index: Int): ViewNode { requested += index; return text("row-$index", "Lazy row $index") }
    } }
    private fun props(vararg pairs: Pair<String, JsonElement>) = JsonObject(pairs.toMap())
    private fun text(id: String, value: String) = ViewNode("Text", id, props("text" to JsonPrimitive(value)))
    private fun slot(name: String, vararg children: ViewNode) = ViewNode("CupertinoSlot", "slot-$name", props("name" to JsonPrimitive(name)), children = children.toList())
    private fun component(name: String, extra: JsonObject = props(), vararg children: ViewNode) = ViewNode("Composable", name, JsonObject(extra + ("name" to JsonPrimitive(name))), children = children.toList())
    private fun frame(n: ViewNode, height: Int = 400) = n.copy(modifiers = listOf(ModifierNode("frame", props("width" to JsonPrimitive(360), "height" to JsonPrimitive(height)))))

    @Test fun hapticCommandDispatchesOnceAndHonorsDisabled() {
        sink()
        val feedback = mutableListOf<HapticFeedbackType>()
        val command = mutableStateOf<String?>(null)
        val enabled = mutableStateOf(true)
        val kind = mutableStateOf("LongPress")
        compose.setContent {
            CompositionLocalProvider(LocalHapticFeedback provides object : HapticFeedback {
                override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) { feedback += hapticFeedbackType }
            }) {
                val values = mutableMapOf<String, JsonElement>("type" to JsonPrimitive(kind.value), "enabled" to JsonPrimitive(enabled.value), "onPerformed" to JsonPrimitive(99))
                command.value?.let { values["commandID"] = JsonPrimitive(it) }
                Render(component("rememberCupertinoHapticFeedback", JsonObject(values)))
            }
        }
        assertTrue(feedback.isEmpty())
        compose.runOnIdle { command.value = "first" }
        compose.waitForIdle()
        assertEquals(listOf(HapticFeedbackType.LongPress), feedback)
        compose.runOnIdle { kind.value = "TextHandleMove" }
        compose.waitForIdle()
        assertEquals(1, feedback.size, "Prop changes cannot replay a command")
        compose.runOnIdle { command.value = "second" }
        compose.waitForIdle()
        assertEquals(HapticFeedbackType.TextHandleMove, feedback.last())
        compose.runOnIdle { enabled.value = false; command.value = "third" }
        compose.waitForIdle()
        assertEquals(2, feedback.size)
        assertEquals(listOf(99L to "void", 99L to "void"), events)
    }

    @Test fun decomposePreservesSurvivingChildAndDestroysRemovedChild() {
        val state = CupertinoNavigationState(listOf("root", "detail"))
        state.lifecycle.resume()
        val root = state.stack.value.items[0].instance
        val detail = state.stack.value.active.instance
        state.replace(listOf("root", "detail", "more"))
        assertSame(root, state.stack.value.items[0].instance)
        assertSame(detail, state.stack.value.items[1].instance)
        state.replace(listOf("root"))
        assertEquals(Lifecycle.State.DESTROYED, detail.context.lifecycle.state)
        assertSame(root, state.stack.value.active.instance)
        state.lifecycle.destroy()
        assertEquals(Lifecycle.State.DESTROYED, root.context.lifecycle.state)
    }

    @Test fun navigationRendersActiveSwiftSlotAndUpdatesExternalPath() {
        val entries = mutableStateOf("""[{"id":"root"}]""")
        compose.setContent { Render(frame(component("NativeChildren", props("entries" to JsonPrimitive(entries.value), "animation" to JsonPrimitive("none")),
            slot("entry_root", text("root", "Devices")), slot("entry_detail", text("detail", "Device detail"))))) }
        compose.onNodeWithText("Devices").assertIsDisplayed()
        compose.runOnIdle { entries.value = """[{"id":"root"},{"id":"detail"}]""" }
        compose.onNodeWithText("Device detail").assertIsDisplayed()
        compose.onNodeWithText("Devices").assertDoesNotExist()
        compose.runOnIdle { entries.value = """[{"id":"root"}]""" }
        compose.onNodeWithText("Devices").assertIsDisplayed()
    }

    @Test fun dateCallbackRetainsInt64AndExternalUpdateDoesNotEcho() {
        sink()
        val selected = mutableStateOf(1_800_000_000_000L / 86_400_000L * 86_400_000L)
        lateinit var state: CupertinoDatePickerState
        compose.setContent { state = cupertinoDateState(component("CupertinoDatePicker", props("selectedDateMillis" to JsonPrimitive(selected.value), "onSelectionChange" to JsonPrimitive(10)))) }
        val next = selected.value + 86_400_000L
        compose.runOnIdle { state.setSelection(next) }
        compose.waitUntilBounded { events.isNotEmpty() }
        assertEquals(next, Json.parseToJsonElement(events.single().second).jsonObject.getValue("selectedDateMillis").jsonPrimitive.long)
        compose.runOnIdle { selected.value = next + 86_400_000L }
        compose.waitForIdle()
        assertEquals(1, events.size, "A Swift state update must not echo as a user selection")
    }

    @Test fun timeStateKeepsPMAndDisabledStateBlocksCallbacks() {
        sink()
        val enabled = mutableStateOf(true)
        lateinit var state: CupertinoTimePickerState
        compose.setContent { state = cupertinoTimeState(component("CupertinoTimePicker", props("hour" to JsonPrimitive(23), "minute" to JsonPrimitive(58), "is24Hour" to JsonPrimitive(false), "enabled" to JsonPrimitive(enabled.value), "onSelectionChange" to JsonPrimitive(11)))) }
        compose.runOnIdle { assertEquals(23, state.hour); state.isManual = true; state.manualMinute = 57 }
        compose.waitUntilBounded { events.isNotEmpty() }
        val event = Json.parseToJsonElement(events.last().second).jsonObject
        assertEquals(23, event.getValue("hour").jsonPrimitive.int)
        assertEquals(57, event.getValue("minute").jsonPrimitive.int)
        val count = events.size
        compose.runOnIdle { enabled.value = false }
        compose.runOnIdle { state.manualMinute = 56 }
        compose.waitForIdle()
        assertEquals(count, events.size)
    }

    @Test fun sectionLinkRetainsNamedSlotsAndCallback() {
        sink()
        val section = component("CupertinoSection", props("style" to JsonPrimitive("InsetGrouped")), slot("title", text("heading", "Account")), slot("content",
            component("SectionScope.SectionLink", props("onClick" to JsonPrimitive(12)), slot("title", text("link", "Pair device")), slot("caption", text("caption", "Add your phone")))))
        compose.setContent { Render(frame(section)) }
        compose.onNodeWithText("Pair device").performClick()
        assertEquals(listOf(12L to "void"), events)
        compose.onNodeWithText("Add your phone").assertIsDisplayed()
    }

    @Test fun lazySectionRequestsOnlyVisibleSwiftItems() {
        sink()
        val lazy = component("CupertinoLazyColumn", children = arrayOf(slot("content", component("LazyListScope.section", children = arrayOf(slot("content",
            component("LazySectionScope.items", props("itemProvider" to JsonPrimitive(44), "count" to JsonPrimitive(1000)))))))))
        compose.setContent { Render(frame(lazy, 160)) }
        compose.onNodeWithText("Lazy row 0").assertIsDisplayed()
        assertTrue(requested.isNotEmpty())
        assertTrue(requested.size < 50, "Must request visible rows, not eagerly cross JNI for all 1000 rows: ${requested.size}")
    }
}
