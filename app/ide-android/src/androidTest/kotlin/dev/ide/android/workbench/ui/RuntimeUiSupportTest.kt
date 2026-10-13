package dev.ide.android.workbench.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure helper tests can also run on a JVM; no activity, native runtime pack, or device is required. */
class RuntimeUiSupportTest {
    @Test fun argumentsRoundTripWithoutShellEvaluation() {
        val arguments = listOf("plain", "", "two words", "single'quote", "double\"quote", "back\\slash", "line\nbreak", "\$HOME", "$(touch ignored)", ";", "&&")
        assertEquals(arguments, RuntimeArguments.parse(RuntimeArguments.format(arguments)))
        assertEquals(listOf("hello world", "two words", "plain"), RuntimeArguments.parse("'hello world' two\\ words plain"))
    }

    @Test fun invalidOrUnboundedArgumentsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { RuntimeArguments.parse("'unfinished") }
        assertThrows(IllegalArgumentException::class.java) { RuntimeArguments.parse("unfinished\\") }
        assertThrows(IllegalArgumentException::class.java) { RuntimeArguments.parse("bad\u0000arg") }
        assertThrows(IllegalArgumentException::class.java) { RuntimeArguments.parse("x".repeat(8193)) }
        assertThrows(IllegalArgumentException::class.java) { RuntimeArguments.parse(List(129) { "x" }.joinToString(" ")) }
    }

    @Test fun environmentPreservesLiteralValuesAndRejectsAmbiguousNames() {
        assertEquals(mapOf("PORT" to "8080", "TEXT" to "one=two ", "EMPTY" to ""), runtimeEnvironment("PORT=8080\nTEXT=one=two \n\nEMPTY="))
        assertThrows(IllegalArgumentException::class.java) { runtimeEnvironment("PORT=8080\nPORT=9000") }
        assertThrows(IllegalArgumentException::class.java) { runtimeEnvironment("invalid name=value") }
        assertThrows(IllegalArgumentException::class.java) { runtimeEnvironment("missing-value") }
    }

    @Test fun onlyExactLoopbackHttpOriginsAreEmbedded() {
        for (url in listOf("http://127.0.0.1:8123/", "http://localhost:3000/index.html", "http://[::1]:8080/", "https://localhost:8443/")) {
            assertNotNull(url, RuntimePreviewAddress.parse(url))
        }
        for (url in listOf(
            "file:///data/user/0/private", "content://private/secret", "javascript:alert(1)", "data:text/html,hello", "intent://app",
            "http://0.0.0.0:8000/", "http://10.0.2.2:8000/", "http://192.168.1.1/", "http://127.0.0.2/", "http://127.1/",
            "http://localhost.evil.example/", "http://localhost./", "http://localhost@evil.example/", "http://evil@localhost/",
            "http://localhost:0/", "http://localhost:65536/", "http://[::ffff:127.0.0.1]:8080/", " http://localhost/", "http://localhost/\n", "http://localhost\\evil/",
        )) assertNull(url, RuntimePreviewAddress.parse(url))
        val address = requireNotNull(RuntimePreviewAddress.parse("http://127.0.0.1:8123/"))
        assertTrue(address.permits("http://127.0.0.1:8123/other?q=1#fragment"))
        assertFalse(address.permits("http://127.0.0.1:8124/"))
        assertFalse(address.permits("http://localhost:8123/"))
        assertFalse(address.permits("https://127.0.0.1:8123/"))
        assertTrue(requireNotNull(RuntimePreviewAddress.parse("http://localhost/")).permits("http://LOCALHOST:80/page"))
    }

    @Test fun outputIsBoundedForBothManyLinesAndOneHugeLine() {
        val hugeLine = "z".repeat(100_000)
        val visible = runtimeVisibleOutput(hugeLine)
        assertTrue(visible.startsWith("[Earlier output omitted"))
        assertTrue(visible.length < 33_000)
        assertTrue(visible.endsWith("z".repeat(32_768)))
        val manyLines = (1..1000).joinToString("\n") { "line $it" }
        val bounded = runtimeVisibleOutput(manyLines)
        assertTrue(bounded.endsWith("line 1000"))
        assertTrue(bounded.lineSequence().count() <= 301)
        assertEquals("small\nlog", runtimeVisibleOutput("small\nlog"))
        assertEquals("", runtimeVisibleOutput(""))
    }
}
