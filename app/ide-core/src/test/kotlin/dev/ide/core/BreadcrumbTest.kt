package dev.ide.core

import dev.ide.testkit.withTempDir
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class BreadcrumbTest {

    private fun crumbAt(ide: IdeServices, code: String): List<String> {
        val app = ide.modules().first { it.name == "app" }
        val probe = ide.sourceRoots(app).first().resolve("com/example/app/Probe.java")
        val offset = code.indexOf("|CARET|")
        return ide.breadcrumbAt(probe, code.replace("|CARET|", ""), offset)
    }

    @Test
    fun breadcrumbReflectsEnclosingTypeAndMethod() = withTempDir("ide-crumb") { dir ->
        IdeServices.bootstrapJavaDemo(dir).use { ide ->
            // caret inside a method → [type, method]
            assertEquals(
                listOf("Outer", "greet"),
                crumbAt(ide, "package com.example.app; class Outer { void greet() { int x = 0; |CARET| } }"),
            )
            // caret inside a nested type's method → [outer, inner, method]
            assertEquals(
                listOf("Outer", "Inner", "run"),
                crumbAt(ide, "package com.example.app; class Outer { class Inner { void run() { |CARET| } } }"),
            )
            // caret in the class body but outside any method → [type]
            assertEquals(
                listOf("Outer"),
                crumbAt(ide, "package com.example.app; class Outer { |CARET| void greet() {} }"),
            )
        }
        dir.toFile().deleteRecursively()
    }

    @Test
    fun swiftCalculatorAppAndContentViewProjectToBlocksAndBreadcrumbs() = withTempDir("ide-swift-crumb") { dir ->
        val calcAppCode = """
            import SwiftUI

            @main
            struct CalculatorApp: App {
                var body: some Scene {
                    WindowGroup {
                        ContentView()
                    }
                }
            }
        """.trimIndent()
        val contentViewCode = """
            import SwiftUI

            enum CalcButton: String {
                case one = "1"
                case add = "+"

                var buttonColor: Color {
                    switch self {
                    case .add:
                        return .orange
                    default:
                        return .gray
                    }
                }
            }

            struct ContentView: View {
                @State var value = "0"
                @State var runningNumber = 0

                var body: some View {
                    ZStack {
                        Color.black.edgesIgnoringSafeArea(.all)
                        VStack {
                            Spacer()
                            Text(value)
                                .bold()
                        }
                    }
                }

                func didTap(button: CalcButton) {
                    if button == .add {
                        self.runningNumber = 1
                    }
                }
            }
        """.trimIndent()

        val calcAppFile = dir.resolve("CalculatorApp.swift")
        val contentViewFile = dir.resolve("ContentView.swift")
        Files.writeString(calcAppFile, calcAppCode)
        Files.writeString(contentViewFile, contentViewCode)

        IdeServices.openUnrecognizedProject(dir).use { ide ->
            // 1. Breadcrumbs on CalculatorApp.swift: at initial offset 0 and inside body -> ["CalculatorApp", "body"]
            assertEquals(
                listOf("CalculatorApp", "body"),
                ide.breadcrumbAt(calcAppFile, calcAppCode, 0),
            )
            assertEquals(
                listOf("CalculatorApp", "body"),
                ide.breadcrumbAt(calcAppFile, calcAppCode, calcAppCode.indexOf("WindowGroup")),
            )

            // 2. Breadcrumbs on ContentView.swift: inside ContentView.body and ContentView.didTap
            assertEquals(
                listOf("ContentView", "body"),
                ide.breadcrumbAt(contentViewFile, contentViewCode, contentViewCode.indexOf("ZStack")),
            )
            assertEquals(
                listOf("ContentView", "didTap"),
                ide.breadcrumbAt(contentViewFile, contentViewCode, contentViewCode.indexOf("runningNumber = 1")),
            )

            // 3. Block projection on CalculatorApp.swift & ContentView.swift succeeds!
            val calcTree = ide.projectBlocks(calcAppFile, calcAppCode)
            kotlin.test.assertNotNull(calcTree, "CalculatorApp.swift must project to a BlockTree")
            kotlin.test.assertTrue(calcTree.allBlocks.any { it.label == "class" }, "Expected struct CalculatorApp block")
            kotlin.test.assertTrue(calcTree.allBlocks.any { it.label == "method" }, "Expected var body method block")
            kotlin.test.assertTrue(calcTree.allBlocks.any { it.label == "call" }, "Expected WindowGroup / ContentView call blocks")

            val contentViewTree = ide.projectBlocks(contentViewFile, contentViewCode)
            kotlin.test.assertNotNull(contentViewTree, "ContentView.swift must project to a BlockTree")
            kotlin.test.assertTrue(contentViewTree.allBlocks.any { it.label == "field" }, "Expected @State var field blocks")
            kotlin.test.assertTrue(contentViewTree.allBlocks.any { it.label == "when" }, "Expected switch block")
            kotlin.test.assertTrue(contentViewTree.allBlocks.any { it.label == "if" }, "Expected if block")
        }
        dir.toFile().deleteRecursively()
    }
}
