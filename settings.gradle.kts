rootProject.name = "building-v2"

pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        google()
        maven("https://repo.gradle.org/gradle/libs-releases") {
            content { includeModule("org.gradle", "gradle-tooling-api") }
        }
        maven("https://redirector.kotlinlang.org/maven/kotlin-ide-plugin-dependencies") {
            content { includeGroup("org.jetbrains.kotlin") }
        }
        maven("https://packages.jetbrains.team/maven/p/ij/intellij-dependencies") {
            content {
                includeGroupByRegex("org\\.jetbrains\\.intellij.*")
                includeGroup("org.jetbrains.kotlin")
                includeModule("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm")
                includeModuleByRegex("org\\.jetbrains", "syntax-api.*")
            }
        }
        maven("https://cache-redirector.jetbrains.com/intellij-repository/releases") {
            content {
                includeGroupByRegex("com\\.jetbrains\\.intellij.*")
                includeModule("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm")
            }
        }
    }
}

include(
    ":platform-core",
    ":platform-json",
    ":vfs-api",
    ":project-model-api",
    ":project-model-impl",
    ":build-api",
    ":build-engine",
    ":android-support",
    ":android-sdk-metadata",
    ":applog-runtime",
    ":reliability-test-support",
    ":language-api",
    ":index-api",
    ":index-impl",
    ":analysis-api",
    ":analysis-impl",
    ":lang-jdt",
    ":lang-java",
    ":lang-xml",
    ":lang-kotlin-index",
    ":kotlin-compiler-deps",
    ":intellij-psi-host",
    ":lang-kotlin",
    ":lang-ksp",
    ":decompiler",
    ":jvm-build",
    ":gradle-tooling",
    ":gradle-build",
    ":interp-core",
    ":interp-api",
    ":interp-impl",
    ":jvm-interp",
    ":jvm-vm",
    ":jvm-vm-fixtures",
    ":android-apk",
    ":deps-api",
    ":deps-impl",
    ":vcs-api",
    ":vcs-impl",
    ":analytics-api",
    ":analytics-impl",
    ":store-api",
    ":store-impl",
    ":store-bridge",
    ":block-api",
    ":block-impl",
    ":plugin-api",
    ":plugin-ui-api",
    ":plugin-impl",
    ":agent-api",
    ":agent-impl",
    ":agent-mcp",
    ":developer-workbench",
    ":layout-preview-api",
    ":layout-preview-impl",
    ":model-api",
    ":project-templates",
    ":kotlin-classfile",
    ":awt-toolkit",
    ":bench-support",
    ":test-support",
    ":kotlin-syntax",
    ":kotlin-symbols",
    ":ide-ui-api",
    ":model-bridge",
    ":ide-core",
    ":build-cli",
    ":android-app",
)

val layers = mapOf(
    "platform" to listOf(
        "model-api", "platform-core", "platform-json", "vfs-api", "project-model-api",
        "project-model-impl", "project-templates",
    ),
    "lang" to listOf(
        "kotlin-classfile",
        "language-api", "index-api", "index-impl", "analysis-api", "analysis-impl",
        "lang-jdt", "lang-java", "lang-kotlin", "lang-kotlin-index", "lang-ksp", "lang-xml",
        "kotlin-compiler-deps", "intellij-psi-host", "decompiler", "block-api", "block-impl",
    ),
    "build-system" to listOf("build-api", "build-engine", "jvm-build", "gradle-tooling", "gradle-build"),
    "run" to listOf("interp-api", "interp-core", "interp-impl", "jvm-interp", "jvm-vm", "jvm-vm-fixtures", "awt-toolkit", "android-apk"),
    "android" to listOf(
        "android-support", "android-sdk-metadata", "art-compat",
        "layout-preview-api", "layout-preview-impl", "applog-runtime", "reliability-test-support",
    ),
    "services" to listOf(
        "deps-api", "deps-impl", "vcs-api", "vcs-impl",
        "store-api", "store-impl", "store-bridge",
        "analytics-api", "analytics-impl", "agent-api", "agent-impl", "agent-mcp",
        "developer-workbench",
    ),
    "plugins" to listOf("plugin-api", "plugin-ui-api", "plugin-impl"),
    "engine" to listOf("ide-ui-api", "model-bridge", "ide-core", "build-cli"),
    "app" to listOf("android-app"),
    "tools" to listOf("test-support", "bench-support"),
    "experimental" to listOf("kotlin-syntax", "kotlin-symbols"),
)

val included = rootProject.children.map { it.name }.toSet()
layers.forEach { (layer, modules) ->
    modules.filter { it in included }.forEach { project(":$it").projectDir = file("$layer/$it") }
}
