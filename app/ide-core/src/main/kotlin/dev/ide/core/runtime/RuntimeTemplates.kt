package dev.ide.core.runtime

import dev.ide.core.node.NodeModuleType
import dev.ide.core.python.PythonModuleType
import dev.ide.core.swift.SwiftModuleType
import dev.ide.model.ContentRole
import dev.ide.model.DependencyScope
import dev.ide.model.LanguageLevel
import dev.ide.model.SourceSetTemplate
import dev.ide.model.template.ProjectScaffold
import dev.ide.model.template.ProjectTemplate
import dev.ide.model.template.TemplateArgs
import dev.ide.model.template.TemplateCategory
import dev.ide.model.template.TemplateId
import dev.ide.model.template.TemplateParameter
import dev.ide.templates.TemplateSupport

/**
 * First-class starter templates for Swift & iOS (SwiftUI + xtool `.ipa`), Python, and Node.js.
 */
object SwiftIosAppTemplate : ProjectTemplate {
    override val id = TemplateId("swift-ios-app")
    override val displayName = "SwiftUI iOS App"
    override val description = "A SwiftUI iOS application built with Swift 6 & xtool into an installable .ipa."
    override val category = TemplateCategory.SWIFT
    override val iconId = "swift"
    override fun parameters(): List<TemplateParameter> = emptyList()

    override fun generate(scaffold: ProjectScaffold, args: TemplateArgs) {
        val targetName = TemplateSupport.typeName(args.name)
        val bundleId = args.packageName.ifBlank { "dev.building.${targetName.lowercase()}" }

        scaffold.workspace.beginModification().apply {
            addProject(args.name, SwiftModuleType.BUILD_SYSTEM, scaffold.rootDir)
            commit()
        }
        scaffold.workspace.projects.first { it.name == args.name }.beginModification().apply {
            addModule("swift", scaffold.moduleType(SwiftModuleType.id)).apply {
                languageLevel = LanguageLevel("SWIFT")
                addSourceSet(
                    SourceSetTemplate("main", DependencyScope.IMPLEMENTATION, mapOf("." to setOf(ContentRole("swift-source")))),
                )
            }
            commit()
        }

        scaffold.writeText(
            "xtool.yml",
            """
            version: 1
            bundleID: $bundleId
            """,
        )

        scaffold.writeText(
            "Package.swift",
            """
            // swift-tools-version: 6.0
            import PackageDescription

            let package = Package(
                name: "$targetName",
                platforms: [
                    .iOS(.v17),
                    .macOS(.v14)
                ],
                products: [
                    .library(name: "$targetName", targets: ["$targetName"]),
                ],
                targets: [
                    .target(
                        name: "$targetName"
                    ),
                ]
            )
            """,
        )

        scaffold.writeText(
            "Sources/$targetName/${targetName}App.swift",
            """
            #if canImport(SwiftUI)
            import SwiftUI

            @main
            struct ${targetName}App: App {
                var body: some Scene {
                    WindowGroup {
                        ContentView()
                    }
                }
            }

            struct ContentView: View {
                @State private var count = 0

                var body: some View {
                    VStack(spacing: 16) {
                        Text("${args.name}")
                            .font(.largeTitle.bold())
                        Text("Tapped \(count) times")
                            .foregroundStyle(.secondary)
                        Button("Increment") {
                            count += 1
                        }
                        .buttonStyle(.borderedProminent)
                    }
                    .padding()
                }
            }
            #else
            @main
            struct ${targetName}App {
                static func main() {
                    print("Hello from $targetName on Swift 6!")
                }
            }
            #endif
            """,
        )
    }
}

object PythonAppTemplate : ProjectTemplate {
    override val id = TemplateId("python-app")
    override val displayName = "Python Application"
    override val description = "A runnable Python 3 project with standard library, SQLite, and unit tests."
    override val category = TemplateCategory.PYTHON
    override val iconId = "python"
    override fun parameters(): List<TemplateParameter> = emptyList()

    override fun generate(scaffold: ProjectScaffold, args: TemplateArgs) {
        val slug = args.name.lowercase().replace(Regex("[^a-z0-9_]+"), "_").trim('_').ifEmpty { "python_app" }

        scaffold.workspace.beginModification().apply {
            addProject(args.name, PythonModuleType.BUILD_SYSTEM, scaffold.rootDir)
            commit()
        }
        scaffold.workspace.projects.first { it.name == args.name }.beginModification().apply {
            addModule("python", scaffold.moduleType(PythonModuleType.id)).apply {
                languageLevel = LanguageLevel("PYTHON")
                addSourceSet(
                    SourceSetTemplate("main", DependencyScope.IMPLEMENTATION, mapOf("." to setOf(ContentRole("python-source")))),
                )
            }
            commit()
        }

        scaffold.writeText(
            "pyproject.toml",
            """
            [project]
            name = "$slug"
            version = "0.1.0"
            description = "${args.name}"
            requires-python = ">=3.11"
            """,
        )

        scaffold.writeText(
            "main.py",
            """
            import sys
            import platform


            def greet(name: str = "${args.name}") -> str:
                return f"Hello from {name} running on Python {sys.version.split()[0]} ({platform.machine()})!"


            if __name__ == "__main__":
                print(greet())
            """,
        )
    }
}

object NodeAppTemplate : ProjectTemplate {
    override val id = TemplateId("node-app")
    override val displayName = "Node.js Application"
    override val description = "A runnable Node.js application with package.json scripts and HTTP server support."
    override val category = TemplateCategory.NODE
    override val iconId = "node"
    override fun parameters(): List<TemplateParameter> = emptyList()

    override fun generate(scaffold: ProjectScaffold, args: TemplateArgs) {
        val slug = args.name.lowercase().replace(Regex("[^a-z0-9-]+"), "-").trim('-').ifEmpty { "node-app" }

        scaffold.workspace.beginModification().apply {
            addProject(args.name, NodeModuleType.BUILD_SYSTEM, scaffold.rootDir)
            commit()
        }
        scaffold.workspace.projects.first { it.name == args.name }.beginModification().apply {
            addModule("node", scaffold.moduleType(NodeModuleType.id)).apply {
                languageLevel = LanguageLevel("ES2023")
                addSourceSet(
                    SourceSetTemplate("main", DependencyScope.IMPLEMENTATION, mapOf("." to setOf(ContentRole("node-source")))),
                )
            }
            commit()
        }

        scaffold.writeText(
            "package.json",
            """
            {
              "name": "$slug",
              "version": "1.0.0",
              "private": true,
              "scripts": {
                "start": "node index.js",
                "test": "node --test"
              }
            }
            """,
        )

        scaffold.writeText(
            "index.js",
            """
            const os = require('node:os');

            function main() {
              console.log(`Hello from ${args.name} on Node.js ${'$'}{process.version} (${'$'}{os.arch()})!`);
            }

            main();
            """,
        )
    }
}
