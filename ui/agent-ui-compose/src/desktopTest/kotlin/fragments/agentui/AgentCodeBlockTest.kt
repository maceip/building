package fragments.agentui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentCodeBlockTest {
    @Test
    fun `code highlighting preserves source and colors tokens by language`() {
        val javaScript = "const count = 2; // keep"
        val js = highlightCodeLine(javaScript, "js", AgentUiTheme.Light)
        assertEquals(javaScript, js.text)
        assertTrue(js.spanStyles.any { it.item.color == AgentUiTheme.Light.accent &&
            js.text.substring(it.start, it.end) == "const" })
        assertTrue(js.spanStyles.any { it.item.color == AgentUiTheme.Light.muted &&
            js.text.substring(it.start, it.end) == "// keep" })

        val json = "  \"ready\": true, \"name\": \"kit\""
        val parsed = highlightCodeLine(json, "json", AgentUiTheme.Light)
        assertEquals(json, parsed.text)
        assertTrue(parsed.spanStyles.any { it.item.color == AgentUiTheme.Light.accent &&
            parsed.text.substring(it.start, it.end) == "\"ready\"" })
        assertTrue(parsed.spanStyles.any { it.item.color == AgentUiTheme.Light.success &&
            parsed.text.substring(it.start, it.end) == "\"kit\"" })
    }
}
