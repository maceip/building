@file:OptIn(io.github.alexzhirkevich.cupertino.adaptive.ExperimentalAdaptiveApi::class)
package com.pureswift.swiftui

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import io.github.alexzhirkevich.cupertino.adaptive.AdaptiveTheme
import io.github.alexzhirkevich.cupertino.adaptive.Theme
import io.github.alexzhirkevich.cupertino.adaptive.MaterialThemeSpec
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Rule
import org.junit.Test
import kotlin.test.*

class CupertinoBasicTests {
    @get:Rule val compose = createComposeRule()
    private val originalSink = SwiftBridge.sink
    private val calls = mutableListOf<Pair<Long, Any>>()

    @After fun restoreSink() { SwiftBridge.sink = originalSink }

    private fun sink() {
        SwiftBridge.sink = object : CallbackSink {
            override fun invokeVoid(id: Long) { calls += id to Unit }
            override fun invokeBool(id: Long, value: Boolean) { calls += id to value }
            override fun invokeDouble(id: Long, value: Double) { calls += id to value }
            override fun invokeInt(id: Long, value: Int) { calls += id to value }
            override fun invokeString(id: Long, value: String) { calls += id to value }
        }
    }

    private fun node(json: String): ViewNode = Json.decodeFromString(json)

    @Test fun registryResolvesEveryVendoredIconAndAdaptiveExport() {
        assertEquals(833, cupertinoIconNames.count { it.startsWith("CupertinoIcons.") })
        assertEquals(46, cupertinoIconNames.count { it.startsWith("AdaptiveIcons.") })
        var missing: List<String> = listOf("not composed")
        compose.setContent {
            AdaptiveTheme(target = Theme.Cupertino, material = MaterialThemeSpec.Default()) {
                val values = mutableListOf<String>()
                for (name in cupertinoIconNames) if (cupertinoIcon(name) == null) values += name
                val alias = cupertinoIcon("CupertinoIcons.Default.Person")
                val unknown = cupertinoIcon("CupertinoIcons.Filled.NoSuchSymbol")
                SideEffect { missing = values; assertNotNull(alias); assertNull(unknown) }
            }
        }
        compose.runOnIdle { assertEquals(emptyList(), missing) }
    }

    @Test fun editingValuePreservesReverseSelectionAndCompositionAcrossJSON() {
        val value = TextFieldValue("composing", TextRange(7, 2), TextRange(1, 8))
        assertEquals(value, cupertinoEditingValue(Json.parseToJsonElement(cupertinoEditingJSON(value)).jsonObject))
        val withoutComposition = value.copy(composition = null)
        val objectValue = Json.parseToJsonElement(cupertinoEditingJSON(withoutComposition)).jsonObject
        assertFalse("compositionStart" in objectValue)
        assertFalse("compositionEnd" in objectValue)
        assertEquals(withoutComposition, cupertinoEditingValue(objectValue))
    }

    @Test fun malformedSelectionCannotCrashTheNativeEditor() {
        val value = cupertinoEditingValue(Json.parseToJsonElement("""{"text":"abc","selectionStart":-8,"selectionEnd":999,"compositionStart":-1,"compositionEnd":50}""").jsonObject)
        assertEquals(TextRange(0, 3), value.selection)
        assertEquals(TextRange(0, 3), value.composition)
    }

    @Test fun inheritedSwiftTextModifiersReachCupertinoText() {
        val tree = node("""{"type":"VStack","id":"outer","modifiers":[{"kind":"font","args":{"design":"monospaced","weight":"bold"}},{"kind":"foregroundColor","args":{"color":4294901760}}],"children":[{"type":"Composable","id":"text","props":{"name":"CupertinoText","text":"Inherited Cupertino"}}]}""")
        compose.setContent { Render(tree) }
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("Inherited Cupertino").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(FontFamily.Monospace, layouts.single().layoutInput.style.fontFamily)
        assertEquals(FontWeight.Bold, layouts.single().layoutInput.style.fontWeight)
        assertEquals(Color.Red, layouts.single().layoutInput.style.color)
    }

    @Test fun annotatedRangesAndMetadataSurviveRendering() {
        val tree = node("""{"type":"Composable","id":"styled","props":{"name":"CupertinoText","annotatedJson":{"text":"Strong link","spans":[{"start":0,"end":6,"fontWeight":700}],"annotations":[{"start":7,"end":11,"tag":"URL","value":"https://example.test"}]}}}""")
        compose.setContent { Render(tree) }
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("Strong link").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val text = layouts.single().layoutInput.text
        assertEquals(FontWeight.Bold, text.spanStyles.single().item.fontWeight)
        assertEquals("https://example.test", text.getStringAnnotations("URL", 7, 11).single().item)
    }

    @Test fun explicitDarkThemeSelectsDarkAppearanceColors() {
        val tree = node("""{"type":"Composable","id":"theme","props":{"name":"CupertinoTheme","isDark":true},"children":[{"type":"Composable","id":"text","props":{"name":"CupertinoText","text":"Dark override","color":[4294901760,4278190335]}}]}""")
        compose.setContent { CompositionLocalProvider(LocalAppearanceIsDark provides false) { RenderChild(tree) } }
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("Dark override").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(Color.Blue, layouts.single().layoutInput.style.color)
    }

    @Test fun inheritedDisabledSuppressesButtonCallback() {
        sink()
        val tree = node("""{"type":"VStack","id":"outer","modifiers":[{"kind":"disabled","args":{"value":true}}],"children":[{"type":"Composable","id":"button","props":{"name":"CupertinoButton","onClick":41},"children":[{"type":"Text","id":"title","props":{"text":"Disabled Cupertino"}}]}]}""")
        compose.setContent { Render(tree) }
        compose.onNodeWithText("Disabled Cupertino").assertIsNotEnabled().performClick()
        compose.runOnIdle { assertTrue(calls.isEmpty()) }
    }

    @Test fun unrelatedStyleDoesNotMakeFilledButtonTextMatchItsBackground() {
        val tree = node("""{"type":"Composable","id":"button","props":{"name":"CupertinoButton","style":"InsetGrouped"},"children":[{"type":"Composable","id":"label","props":{"name":"CupertinoText","text":"Readable button"}}]}""")
        compose.setContent { Render(tree) }
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("Readable button").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(Color.White, layouts.single().layoutInput.style.color)
    }

    @Test fun checkboxRoundTripsBooleanCallbacks() {
        sink()
        val tree = node("""{"type":"Composable","id":"checkbox","props":{"name":"CupertinoCheckBox","checked":false,"onCheckedChange":42}}""")
        compose.setContent { Render(tree) }
        compose.onNode(isToggleable()).performClick()
        compose.onNode(isToggleable()).performClick()
        compose.runOnIdle { assertEquals(listOf<Pair<Long, Any>>(42L to true, 42L to false), calls) }
    }

    @Test fun editingCallbackCarriesFullSelectionRatherThanJustText() {
        sink()
        val tree = node("""{"type":"Composable","id":"editor","props":{"name":"CupertinoBorderedTextField","valueKind":"editing","value":{"text":"Old","selectionStart":3,"selectionEnd":3},"onValueChange":43}}""")
        compose.setContent { Render(tree) }
        compose.onNode(hasSetTextAction()).performTextReplacement("Fresh")
        compose.runOnIdle {
            val value = Json.parseToJsonElement(calls.last { it.first == 43L }.second as String).jsonObject
            assertEquals("Fresh", value["text"]!!.jsonPrimitive.content)
            assertEquals(5, value["selectionStart"]!!.jsonPrimitive.int)
            assertEquals(5, value["selectionEnd"]!!.jsonPrimitive.int)
        }
    }

    @Test fun stringCallbackRemainsAString() {
        sink()
        val tree = node("""{"type":"Composable","id":"editor","props":{"name":"CupertinoTextField","value":"Old","onValueChange":44}}""")
        compose.setContent { Render(tree) }
        compose.onNode(hasSetTextAction()).performTextReplacement("Plain")
        compose.runOnIdle { assertEquals("Plain", calls.last { it.first == 44L }.second) }
    }

    @Test fun navigationItemsRetainTheirActualParentRowScopeAndCallback() {
        sink()
        val tree = node("""{"type":"Composable","id":"bar","props":{"name":"CupertinoNavigationBar","windowInsets":"none"},"children":[{"type":"Composable","id":"item","props":{"name":"RowScope.CupertinoNavigationBarItem","selected":true,"onClick":45},"children":[{"type":"CupertinoSlot","id":"icon-slot","props":{"name":"icon"},"children":[{"type":"Composable","id":"icon","props":{"name":"CupertinoIcon","imageVector":"CupertinoIcons.Filled.Person"}}]},{"type":"CupertinoSlot","id":"label-slot","props":{"name":"label"},"children":[{"type":"Text","id":"title","props":{"text":"Account"}}]}]}]}""")
        compose.setContent { Render(tree) }
        compose.onNodeWithText("Account").performClick()
        compose.runOnIdle { assertEquals(listOf<Pair<Long, Any>>(45L to Unit), calls) }
    }

    @Test fun searchCancelDefaultEmitsTheEmptyStringContract() {
        sink()
        val tree = node("""{"type":"Composable","id":"cancel","props":{"name":"CupertinoSearchTextFieldDefaults.cancelButton","onValueChange":46}}""")
        compose.setContent { Render(tree) }
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertEquals("", calls.last { it.first == 46L }.second) }
    }

    @Test fun unknownIconsProduceNamedDiagnostic() {
        val tree = node("""{"type":"Composable","id":"unknown","props":{"name":"CupertinoIcon","imageVector":"CupertinoIcons.Filled.Missing"}}""")
        compose.setContent { Render(tree) }
        compose.onNodeWithText("Icon unavailable.").assertIsDisplayed()
    }
}
