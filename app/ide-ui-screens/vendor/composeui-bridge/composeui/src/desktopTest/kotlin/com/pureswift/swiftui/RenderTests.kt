package com.pureswift.swiftui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import kotlinx.serialization.json.Json
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

/// Interpreter tests on the desktop JVM — no emulator. The JSON fixtures
/// double as the wire-format contract with the Swift core's emitted schema.
@OptIn(ExperimentalTestApi::class)
class RenderTests {

    @get:Rule
    val compose = createComposeRule()

    private fun node(json: String): ViewNode = Json.decodeFromString(json)

    @Test
    fun textRendersItsContent() {
        compose.setContent {
            Render(node("""{"type":"Text","id":"root","props":{"text":"Hello"}}"""))
        }
        compose.onNodeWithText("Hello").assertIsDisplayed()
    }

    @Test
    fun monospacedFontInheritsAndCanBeResetByAChild() {
        compose.setContent {
            Render(node("""
                {"type":"VStack","id":"fonts","modifiers":[{"kind":"font","args":{"design":"monospaced"}}],"children":[
                  {"type":"Text","id":"mono","props":{"text":"04 ab"}},
                  {"type":"Text","id":"default","props":{"text":"SwiftKey"},"modifiers":[{"kind":"font","args":{"design":"default"}}]}
                ]}
                """.trimIndent()))
        }
        val mono = mutableListOf<TextLayoutResult>()
        val normal = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("04 ab").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(mono) }
        compose.onNodeWithText("SwiftKey").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(normal) }
        assertEquals(FontFamily.Monospace, mono.single().layoutInput.style.fontFamily)
        assertEquals(FontFamily.Default, normal.single().layoutInput.style.fontFamily)
    }

    @Test
    fun adaptiveTextChangesAppearanceWithoutReplacingTheTree() {
        val dark = mutableStateOf(false)
        val lightARGB = 0xFF1E1E1EL
        val darkARGB = 0xFFFCFCFCL
        val tree = node("""
            {"type":"Text","id":"adaptive","props":{"text":"Same identity"},
             "modifiers":[{"kind":"foregroundColor","args":{"color":[$lightARGB,$darkARGB]}}]}
            """.trimIndent())
        compose.setContent {
            CompositionLocalProvider(LocalAppearanceIsDark provides dark.value) { RenderChild(tree) }
        }
        fun textColor(): Color {
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText("Same identity").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            return layouts.single().layoutInput.style.color
        }
        assertEquals(Color(lightARGB.toInt()), textColor())
        compose.runOnIdle { dark.value = true }
        assertEquals(Color(darkARGB.toInt()), textColor())
        compose.runOnIdle { dark.value = false }
        assertEquals(Color(lightARGB.toInt()), textColor())
    }

    @Test
    fun bundledNamedTypographyInheritsExactWeightAndMetrics() {
        var expectedFamily: FontFamily? = null
        compose.setContent {
            expectedFamily = rememberNamedFontFamily("Host Grotesk")
            Render(node("""
                {"type":"VStack","id":"typography","modifiers":[
                  {"kind":"font","args":{"family":"Host Grotesk","size":24,"weightValue":560}},
                  {"kind":"lineHeight","args":{"value":36}},
                  {"kind":"tracking","args":{"value":-0.3}}
                ],"children":[{"type":"Text","id":"heading","props":{"text":"Exact proposal"}}]}
                """.trimIndent()))
        }
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("Exact proposal").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val style = layouts.single().layoutInput.style
        assertNotNull(expectedFamily, "The bundled Host Grotesk resources must load, not silently fall back")
        assertNotEquals(FontFamily.Default, expectedFamily)
        assertEquals(expectedFamily, style.fontFamily)
        assertEquals(FontWeight(560), style.fontWeight)
        assertEquals(24.sp, style.fontSize)
        assertEquals(36.sp, style.lineHeight)
        assertEquals((-0.3).sp, style.letterSpacing)
    }

    @Test
    fun explicitlyPlainActionBlocksDisabledTouchesAndDispatchesWhenEnabled() {
        val disabled = mutableStateOf(true)
        val invoked = mutableListOf<Long>()
        SwiftBridge.sink = object : CallbackSink {
            override fun invokeVoid(id: Long) { invoked += id }
            override fun invokeBool(id: Long, value: Boolean) {}
            override fun invokeDouble(id: Long, value: Double) {}
            override fun invokeInt(id: Long, value: Int) {}
            override fun invokeString(id: Long, value: String) {}
        }
        compose.setContent {
            Render(node("""
                {"type":"Button","id":"approval","props":{"onTap":77},"modifiers":[
                  {"kind":"buttonStyle","args":{"style":"plain"}},
                  {"kind":"disabled","args":{"value":${disabled.value}}}
                ],"children":[{"type":"Text","id":"approval-label","props":{"text":"Approve exact proposal"},
                  "modifiers":[{"kind":"padding","args":{"top":12,"leading":12,"bottom":12,"trailing":12}}]}]}
                """.trimIndent()))
        }
        compose.onNodeWithText("Approve exact proposal").assertIsNotEnabled().performTouchInput { click() }
        assertEquals(emptyList(), invoked)
        compose.runOnIdle { disabled.value = false }
        compose.onNodeWithText("Approve exact proposal").assertIsEnabled().performTouchInput { click() }
        assertEquals(listOf(77L), invoked)
    }

    @Test
    fun stackRendersChildrenInOrder() {
        compose.setContent {
            Render(
                node(
                    """
                    {"type":"VStack","id":"root","children":[
                      {"type":"Text","id":"root/0","props":{"text":"A"}},
                      {"type":"Text","id":"root/1","props":{"text":"B"}}
                    ]}
                    """.trimIndent()
                )
            )
        }
        compose.onNodeWithText("A").assertIsDisplayed()
        compose.onNodeWithText("B").assertIsDisplayed()
    }

    @Test
    fun buttonTapDispatchesItsCallbackId() {
        val invoked = mutableListOf<Long>()
        SwiftBridge.sink = object : CallbackSink {
            override fun invokeVoid(id: Long) { invoked += id }
            override fun invokeBool(id: Long, value: Boolean) {}
            override fun invokeDouble(id: Long, value: Double) {}
            override fun invokeInt(id: Long, value: Int) {}
            override fun invokeString(id: Long, value: String) {}
        }
        compose.setContent {
            Render(
                node(
                    """
                    {"type":"Button","id":"root","props":{"onTap":42},
                     "children":[{"type":"Text","id":"root/label","props":{"text":"Tap"}}]}
                    """.trimIndent()
                )
            )
        }
        compose.onNodeWithText("Tap").performClick()
        assertEquals(listOf(42L), invoked)
    }

    @Test
    fun toggleChangeDispatchesBoolCallback() {
        val invoked = mutableListOf<Pair<Long, Boolean>>()
        SwiftBridge.sink = object : CallbackSink {
            override fun invokeVoid(id: Long) {}
            override fun invokeBool(id: Long, value: Boolean) { invoked += id to value }
            override fun invokeDouble(id: Long, value: Double) {}
            override fun invokeInt(id: Long, value: Int) {}
            override fun invokeString(id: Long, value: String) {}
        }
        compose.setContent {
            Render(
                node(
                    """
                    {"type":"Toggle","id":"root","props":{"isOn":false,"onChange":7},
                     "children":[{"type":"Text","id":"root/label","props":{"text":"Enabled"}}]}
                    """.trimIndent()
                )
            )
        }
        compose.onNode(androidx.compose.ui.test.isToggleable()).performClick()
        assertEquals(listOf(7L to true), invoked)
    }

    @Test
    fun unknownNodeRendersDiagnosticInsteadOfCrashing() {
        compose.setContent {
            Render(node("""{"type":"Mystery","id":"root"}"""))
        }
        compose.onNodeWithText("This content is unavailable.").assertIsDisplayed()
    }

    @Test
    fun treeStoreDecodesFullSchema() {
        val store = TreeStore()
        store.updateJson(
            """
            {"type":"VStack","id":"root","props":{"spacing":8.0},
             "modifiers":[{"kind":"padding","args":{"top":1.0,"leading":2.0,"bottom":3.0,"trailing":4.0}}],
             "children":[{"type":"Text","id":"root/0","props":{"text":"x"}}],
             "count":null,"itemProviderId":null}
            """.trimIndent()
        )
        val root = store.root!!
        assertEquals("VStack", root.type)
        assertEquals(8.0, root.double("spacing"))
        assertEquals("padding", root.modifiers.single().kind)
        assertEquals(1, root.children.size)
    }
}
