@file:OptIn(io.github.alexzhirkevich.cupertino.ExperimentalCupertinoApi::class)

package com.pureswift.swiftui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.alexzhirkevich.cupertino.AlertActionStyle
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Rule
import org.junit.Test
import kotlin.test.*

class CupertinoPresentationTests {
    @get:Rule val compose = createComposeRule()
    private val originalSink = SwiftBridge.sink
    private val calls = mutableListOf<Long>()
    private val states = mutableListOf<Pair<Long, String>>()

    @After fun restoreSink() { SwiftBridge.sink = originalSink }

    private fun awaitCondition(condition: () -> Boolean) = compose.waitUntilBounded(
        failureMessage = { "Timed out; callback states: $states" }, condition = condition)

    private fun sink() {
        SwiftBridge.sink = object : CallbackSink {
            override fun invokeVoid(id: Long) { calls += id }
            override fun invokeBool(id: Long, value: Boolean) {}
            override fun invokeDouble(id: Long, value: Double) {}
            override fun invokeInt(id: Long, value: Int) {}
            override fun invokeString(id: Long, value: String) { states += id to Json.parseToJsonElement(value).jsonObject.getValue("value").jsonPrimitive.content }
        }
    }
    private fun props(vararg values: Pair<String, JsonElement>) = JsonObject(values.toMap())
    private fun text(id: String, value: String) = ViewNode("Text", id, props("text" to JsonPrimitive(value)))
    private fun slot(name: String, vararg children: ViewNode) = ViewNode("CupertinoSlot", "slot-$name", props("name" to JsonPrimitive(name)), children = children.toList())
    private fun component(name: String, id: String = name, extra: JsonObject = props(), vararg children: ViewNode) =
        ViewNode("Composable", id, JsonObject(extra + ("name" to JsonPrimitive(name))), children = children.toList())
    private fun action(style: String, title: String, callback: Long, native: Boolean = false, enabled: Boolean = true): ViewNode {
        val prefix = if (native) "NativeAlertDialogActionsScope" else "AlertDialogActionsScope"
        return component("$prefix.$style", title, props("title" to JsonPrimitive(title), "onClick" to JsonPrimitive(callback), "enabled" to JsonPrimitive(enabled)),
            slot("title", text("$title-title", title)))
    }
    private fun withProps(node: ViewNode, vararg values: Pair<String, JsonElement>) = node.copy(props = JsonObject(node.props + values.toMap()))

    @Test fun orderedActionsAndExactStylesSurviveTheWire() {
        val tree = component("CupertinoAlertDialog", children = arrayOf(slot("buttons",
            action("default", "Keep", 1), action("destructive", "Remove", 2), action("cancel", "Cancel", 3))))
        val actions = cupertinoDialogActions(tree)
        assertEquals(listOf("Keep", "Remove", "Cancel"), actions.map { it.id })
        assertEquals(listOf(AlertActionStyle.Default, AlertActionStyle.Destructive, AlertActionStyle.Cancel), actions.map(::cupertinoActionStyle))
    }

    @Test fun nativeFallbackPreservesEveryButtonAndItsCallback() {
        sink()
        val tree = component("CupertinoAlertDialogNative", extra = props("title" to JsonPrimitive("Owner change")),
            children = arrayOf(slot("buttons", action("default", "Inspect", 101, true), action("destructive", "Replace", 102, true), action("cancel", "Cancel", 103, true))))
        compose.setContent { Render(tree) }
        compose.onNodeWithText("Inspect").assertIsDisplayed().performClick()
        compose.onNodeWithText("Replace").assertIsDisplayed().performClick()
        compose.onNodeWithText("Cancel").assertIsDisplayed().performClick()
        assertEquals(listOf(101L, 102L, 103L), calls)
    }

    @Test fun materialAdaptiveAlertDoesNotDropExtraOrderedActions() {
        sink()
        val tree = component("AdaptiveAlertDialog", children = arrayOf(slot("title", text("title", "Exact consent")),
            slot("buttons", action("default", "Inspect", 201), action("cancel", "Cancel", 202), action("destructive", "Replace", 203), action("default", "Details", 204, enabled = false))))
        compose.setContent { Render(component("AdaptiveTheme", extra = props("target" to JsonPrimitive("Material3")), children = arrayOf(slot("content", tree)))) }
        val inspect = compose.onNodeWithText("Inspect").fetchSemanticsNode().boundsInRoot.top
        val cancel = compose.onNodeWithText("Cancel").fetchSemanticsNode().boundsInRoot.top
        val replace = compose.onNodeWithText("Replace").fetchSemanticsNode().boundsInRoot.top
        assertTrue(inspect < cancel && cancel < replace, "Material actions keep builder order")
        compose.onNodeWithText("Inspect").performClick()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Replace").performClick()
        compose.onNodeWithText("Details").assertIsNotEnabled()
        assertEquals(listOf(201L, 202L, 203L), calls)
    }

    @Test fun materialAdaptiveNativeAlertDoesNotDropExtraActions() {
        sink()
        val tree = component("AdaptiveAlertDialogNative", extra = props("title" to JsonPrimitive("Review")), children = arrayOf(
            slot("buttons", action("default", "Inspect native", 211, true), action("cancel", "Cancel native", 212, true), action("destructive", "Replace native", 213, true))))
        compose.setContent { Render(component("AdaptiveTheme", extra = props("target" to JsonPrimitive("Material3")), children = arrayOf(slot("content", tree)))) }
        compose.onNodeWithText("Inspect native").performClick()
        compose.onNodeWithText("Cancel native").performClick()
        compose.onNodeWithText("Replace native").performClick()
        assertEquals(listOf(211L, 212L, 213L), calls)
    }

    @Test fun inheritedDisabledReachesDialogBuilderActions() {
        sink()
        val disabled = mutableStateOf(true)
        val tree = component("CupertinoAlertDialog", children = arrayOf(slot("title", text("title", "Approve")), slot("buttons", action("default", "Approve owner", 301))))
        compose.setContent {
            Render(tree.copy(modifiers = listOf(ModifierNode("disabled", props("value" to JsonPrimitive(disabled.value))))))
        }
        compose.onNodeWithText("Approve owner").assertIsNotEnabled()
        assertTrue(calls.isEmpty())
        compose.runOnIdle { disabled.value = false }
        compose.onNodeWithText("Approve owner").assertIsEnabled().performClick()
        assertEquals(listOf(301L), calls)
    }

    @Test fun dropdownScopeRendersDefaultChildrenAndHonorsDisabledActions() {
        sink()
        val tree = component("CupertinoDropdownMenu", extra = props("expanded" to JsonPrimitive(true)), children = arrayOf(
            component("CupertinoMenuScope.MenuSection", "section", children = arrayOf(slot("title", text("section-title", "Account")), slot("content",
                component("CupertinoMenuScope.MenuAction", "inspect", props("onClick" to JsonPrimitive(401)), slot("title", text("inspect-title", "Inspect owner"))),
                component("CupertinoMenuScope.MenuPickerAction", "selected", props("isSelected" to JsonPrimitive(true), "enabled" to JsonPrimitive(false), "onClick" to JsonPrimitive(402)), slot("title", text("selected-title", "Current owner"))))))))
        compose.setContent { Render(tree) }
        compose.onNodeWithText("Account").assertIsDisplayed()
        compose.onNodeWithText("Inspect owner").assertIsEnabled().performClick()
        compose.onNodeWithText("Current owner").assertIsNotEnabled()
        assertEquals(listOf(401L), calls)
    }

    @Test fun sheetDetentsAndWhitelistDecodeBothWireRepresentations() {
        val detents = """[{"kind":"height","value":120},{"kind":"fraction","value":0.5},"large"]"""
        val stringNode = component("CupertinoBottomSheetScaffold", extra = props("detents" to JsonPrimitive(detents), "allowedValues" to JsonPrimitive("[\"Hidden\",\"Expanded\"]")))
        val arrayNode = withProps(stringNode, "detents" to Json.parseToJsonElement(detents))
        assertEquals(cupertinoDetents(stringNode), cupertinoDetents(arrayNode))
        assertEquals(240f, cupertinoDetents(stringNode)[0].calculate(Density(2f), 1000f))
        assertEquals(500f, cupertinoDetents(stringNode)[1].calculate(Density(2f), 1000f))
        assertTrue(cupertinoAllowsValue(stringNode, "Expanded"))
        assertFalse(cupertinoAllowsValue(stringNode, "PartiallyExpanded"))
    }

    @Test fun sheetFrameConstrainsBothBackgroundAndSheetLayers() {
        fun marker(id: String, label: String) = text(id, label).copy(modifiers = listOf(
            ModifierNode("frame", props("fillWidth" to JsonPrimitive(true), "fillHeight" to JsonPrimitive(true))),
            ModifierNode("accessibilityIdentifier", props("id" to JsonPrimitive(id)))))
        val sheet = component("CupertinoBottomSheetScaffold", extra = props("value" to JsonPrimitive("Expanded")), children = arrayOf(
            slot("content", marker("background-bounds", "Scaffold body")), slot("sheetContent", marker("sheet-bounds", "Sheet body"))))
            .copy(modifiers = listOf(ModifierNode("frame", props("width" to JsonPrimitive(360), "height" to JsonPrimitive(420))),
                ModifierNode("accessibilityIdentifier", props("id" to JsonPrimitive("sheet-viewport")))))
        val tree = ViewNode("VStack", "bounded-host", props("spacing" to JsonPrimitive(8)),
            modifiers = listOf(ModifierNode("frame", props("height" to JsonPrimitive(600)))),
            children = listOf(sheet, text("following", "After the sheet")))
        compose.setContent { Render(tree) }
        val viewport = compose.onNodeWithTag("sheet-viewport").fetchSemanticsNode().boundsInRoot
        assertEquals(360f, viewport.width, .5f)
        assertEquals(420f, viewport.height, .5f)
        for (tag in listOf("background-bounds", "sheet-bounds")) {
            val bounds = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertTrue(bounds.width > 0 && bounds.height > 0, "$tag must actually be measured")
            assertTrue(bounds.left >= viewport.left - .5f && bounds.right <= viewport.right + .5f &&
                bounds.top >= viewport.top - .5f && bounds.bottom <= viewport.bottom + .5f,
                "$tag escapes the requested viewport: $bounds outside $viewport")
        }
        val following = compose.onNodeWithText("After the sheet").fetchSemanticsNode().boundsInRoot
        assertTrue(following.top < 500f, "A 420-point sheet must not consume its parent's entire 600-point height: $following")
    }

    @Test fun sheetCommandsExecuteOnceAndWhitelistVetoesForbiddenTransitions() {
        sink()
        val tree = mutableStateOf(component("CupertinoBottomSheetScaffold", extra = props("onStateChange" to JsonPrimitive(501), "value" to JsonPrimitive("Hidden")),
            children = arrayOf(slot("content", text("screen", "Screen")), slot("sheetContent", text("sheet", "Owner details")))))
        compose.setContent { Render(tree.value) }
        compose.waitForIdle()
        compose.runOnIdle { tree.value = withProps(tree.value, "command" to JsonPrimitive("show"), "commandID" to JsonPrimitive("1")) }
        awaitCondition { states.lastOrNull() == 501L to "Expanded" }
        compose.runOnIdle { tree.value = withProps(tree.value, "command" to JsonPrimitive("hide"), "commandID" to JsonPrimitive("2")) }
        awaitCondition { states.lastOrNull() == 501L to "Hidden" }
        val count = states.size
        compose.runOnIdle { tree.value = withProps(tree.value, "command" to JsonPrimitive("show")) }
        compose.waitForIdle()
        assertEquals(count, states.size, "Changing command without changing its ID must not replay it")
        compose.runOnIdle { tree.value = withProps(tree.value, "commandID" to JsonPrimitive("3"), "allowedValues" to JsonArray(listOf(JsonPrimitive("Hidden")))) }
        compose.waitForIdle()
        assertEquals(501L to "Hidden", states.last())
    }

    @Test fun hiddenSheetIsClippedWithinItsFrameOnATallerHostAndStillOpensAndDrags() {
        sink()
        val sheetContent = component("CupertinoBottomSheetContent", extra = props(
            "containerColor" to JsonPrimitive(0xffff00ffL)), children = arrayOf(
                slot("content", text("hidden-marker", "Hidden sheet marker"))))
            .copy(modifiers = listOf(ModifierNode("frame", props(
                "fillWidth" to JsonPrimitive(true), "fillHeight" to JsonPrimitive(true)))))
        val tree = mutableStateOf(component("CupertinoBottomSheetScaffold", extra = props(
            "onStateChange" to JsonPrimitive(502), "value" to JsonPrimitive("Hidden")), children = arrayOf(
                slot("content", text("scaffold-marker", "Scaffold background")), slot("sheetContent", sheetContent)))
            .copy(modifiers = listOf(
                ModifierNode("frame", props("width" to JsonPrimitive(360), "height" to JsonPrimitive(420))),
                ModifierNode("accessibilityIdentifier", props("id" to JsonPrimitive("clipped-sheet"))))))
        compose.setContent {
            Box(Modifier.size(360.dp, 720.dp).background(Color.White).testTag("tall-sheet-host")) {
                Render(tree.value)
            }
        }
        fun assertHiddenAndNoPixelsBeyondFrame() {
            compose.onNodeWithText("Hidden sheet marker").assertIsNotDisplayed()
            val pixels = compose.onNodeWithTag("tall-sheet-host").captureToImage().toPixelMap()
            for (y in 440 until 720 step 40) for (x in 20 until 360 step 40) {
                assertEquals(Color.White.toArgb(), pixels[x, y].toArgb(),
                    "Hidden sheet paints outside its 420-point frame at ($x, $y)")
            }
        }
        awaitCondition { states.lastOrNull() == 502L to "Hidden" }
        assertHiddenAndNoPixelsBeyondFrame()
        compose.runOnIdle { tree.value = withProps(tree.value,
            "command" to JsonPrimitive("show"), "commandID" to JsonPrimitive("open")) }
        awaitCondition { states.lastOrNull() == 502L to "Expanded" }
        compose.onNodeWithText("Hidden sheet marker").assertIsDisplayed()
        compose.onNodeWithTag("clipped-sheet").performTouchInput {
            swipe(Offset(180f, 35f), Offset(180f, 410f), durationMillis = 250)
        }
        awaitCondition { states.lastOrNull() == 502L to "Hidden" }
        assertHiddenAndNoPixelsBeyondFrame()
    }

    private fun swipe(extra: JsonObject) = component("CupertinoSwipeBox", "swipe", extra, slot("items",
        component("CupertinoSwipeBoxItem", "delete", props("onClick" to JsonPrimitive(602)), slot("label", text("delete-label", "Delete")))),
        slot("content", text("row", "Swipe row"))).copy(modifiers = listOf(
            ModifierNode("frame", props("width" to JsonPrimitive(320), "height" to JsonPrimitive(64))),
            ModifierNode("accessibilityIdentifier", props("id" to JsonPrimitive("swipe-row")))))

    @Test fun swipeGestureReportsStateAndInheritedDisabledBlocksInteraction() {
        sink()
        val disabled = mutableStateOf(true)
        val tree = swipe(props("onStateChange" to JsonPrimitive(611),
            "startToEndBehavior" to JsonPrimitive("Disabled"), "endToStartBehavior" to JsonPrimitive("Expandable")))
        compose.setContent { Render(tree.copy(modifiers = tree.modifiers + ModifierNode("disabled", props("value" to JsonPrimitive(disabled.value))))) }
        compose.onNodeWithTag("swipe-row").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertEquals(611L to "Collapsed", states.last())
        compose.runOnIdle { disabled.value = false }
        compose.onNodeWithTag("swipe-row").performTouchInput { swipeLeft() }
        awaitCondition { states.lastOrNull() == 611L to "ExpandedToStart" }
    }

    @Test fun swipeCommandsAndSharedControllerObeyCurrentWhitelist() {
        sink()
        val child = mutableStateOf(swipe(props("onStateChange" to JsonPrimitive(601), "command" to JsonPrimitive("snapTo"),
            "commandID" to JsonPrimitive("1"), "commandValue" to JsonPrimitive("ExpandedToStart"),
            "allowedValues" to JsonArray(listOf(JsonPrimitive("ExpandedToStart"))))))
        val command = mutableStateOf("0")
        compose.setContent {
            Render(component("SharedSwipeBoxController", extra = props("command" to JsonPrimitive("collapse"), "commandID" to JsonPrimitive(command.value)), children = arrayOf(slot("content", child.value))))
        }
        awaitCondition { states.lastOrNull() == 601L to "ExpandedToStart" }
        compose.runOnIdle { command.value = "1" }
        compose.waitForIdle()
        assertEquals(601L to "ExpandedToStart", states.last(), "Controller collapse must consult the current whitelist")
        compose.runOnIdle { child.value = withProps(child.value, "allowedValues" to JsonArray(listOf(JsonPrimitive("ExpandedToStart"), JsonPrimitive("Collapsed")))) }
        compose.runOnIdle { command.value = "2" }
        awaitCondition { states.lastOrNull() == 601L to "Collapsed" }
    }
}
