package dev.ide.ui.editor.core

import dev.ide.ui.editor.CodeLanguage
import dev.ide.ui.editor.languageFor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LanguageSyntaxHighlightingTest {

    @Test
    fun languageResolutionForNewLanguages() {
        assertEquals(CodeLanguage.Swift, languageFor("App.swift"))
        assertEquals(CodeLanguage.Swift, languageFor("Package.swift"))
        assertEquals(CodeLanguage.Python, languageFor("main.py"))
        assertEquals(CodeLanguage.Python, languageFor("worker.pyw"))
        assertEquals(CodeLanguage.TypeScript, languageFor("index.ts"))
        assertEquals(CodeLanguage.TypeScript, languageFor("Component.tsx"))
        assertEquals(CodeLanguage.TypeScript, languageFor("server.js"))
        assertEquals(CodeLanguage.TypeScript, languageFor("client.jsx"))
    }

    @Test
    fun swiftSyntaxStyling() {
        val lang = CodeLanguage.Swift

        // Comments
        val commentLine = styleLine("// This is a Swift comment", LexState.CODE, lang)
        assertTrue(commentLine.spans.any { it.type == TokenType.COMMENT })

        // Keywords and annotations
        val structLine = styleLine("@main struct ContentView: View {", LexState.CODE, lang)
        val structSpans = structLine.spans
        assertTrue(structSpans.any { it.type == TokenType.ANNOTATION }, "Expected annotation for @main")
        assertTrue(structSpans.any { it.type == TokenType.KEYWORD }, "Expected keyword for struct")

        // Functions and strings
        val funcLine = styleLine("    func greet(name: String) -> String {", LexState.CODE, lang)
        assertTrue(funcLine.spans.any { it.type == TokenType.KEYWORD }, "Expected keyword for func")

        val stringLine = styleLine("        let msg = \"Hello Swift\"", LexState.CODE, lang)
        assertTrue(stringLine.spans.any { it.type == TokenType.KEYWORD }, "Expected keyword for let")
        assertTrue(stringLine.spans.any { it.type == TokenType.STRING }, "Expected string for \"Hello Swift\"")
    }

    @Test
    fun pythonSyntaxStyling() {
        val lang = CodeLanguage.Python

        // Hash comments
        val commentLine = styleLine("# Python comment", LexState.CODE, lang)
        assertTrue(commentLine.spans.any { it.type == TokenType.COMMENT })

        // Keywords, decorator, strings
        val decorLine = styleLine("@property", LexState.CODE, lang)
        assertTrue(decorLine.spans.any { it.type == TokenType.ANNOTATION })

        val defLine = styleLine("def calculate(x):", LexState.CODE, lang)
        assertTrue(defLine.spans.any { it.type == TokenType.KEYWORD })

        val strLine = styleLine("    return 'result'", LexState.CODE, lang)
        assertTrue(strLine.spans.any { it.type == TokenType.KEYWORD_CONTROL })
        assertTrue(strLine.spans.any { it.type == TokenType.STRING })
    }

    @Test
    fun typescriptSyntaxStyling() {
        val lang = CodeLanguage.TypeScript

        val constLine = styleLine("const count: number = 42;", LexState.CODE, lang)
        assertTrue(constLine.spans.any { it.type == TokenType.KEYWORD })
        assertTrue(constLine.spans.any { it.type == TokenType.NUMBER })

        val commentLine = styleLine("// TypeScript comment", LexState.CODE, lang)
        assertTrue(commentLine.spans.any { it.type == TokenType.COMMENT })
    }
}
