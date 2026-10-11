package dev.ide.gradle

import dev.ide.platform.JsonReader
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** The model the init script writes, plus `gradleVersion` from the tooling server. */
data class GradleProjectSnapshot(
    val name: String,
    val gradleVersion: String?,
    val modules: List<Module>,
    val issues: List<Issue>,
    val buildToolsVersions: List<String>,
    val platforms: List<String>,
    val ndkVersions: List<String>,
    val cmakeVersions: List<String>,
    val raw: String,
) {
    data class Module(
        val name: String,
        val path: String,
        val dir: String,
        val plugins: List<String>,
        val application: Boolean,
        val variants: List<Variant>,
        val sourceSets: List<SourceSet>,
        val dependencies: List<Dependency>,
        val namespace: String?,
        val compileSdk: String?,
        val minSdk: String?,
        val targetSdk: String?,
        val buildToolsVersion: String?,
        val ndkVersion: String?,
        val cmakeVersion: String?,
        val aidl: Boolean,
    )

    data class Variant(
        val name: String,
        val buildType: String,
        val flavors: List<String>,
        val assembleTask: String,
        val debuggable: Boolean,
    )

    data class SourceSet(val name: String, val java: List<String>, val kotlin: List<String>, val resources: List<String>)

    data class Dependency(val configuration: String, val coordinate: String?, val project: String?)

    data class Issue(val severity: String, val message: String)

    fun variants(): List<VariantChoice.Variant> = modules.flatMapIndexed { moduleIndex, module ->
        module.variants.mapIndexed { index, variant ->
            VariantChoice.Variant(
                modulePath = module.path,
                moduleDir = module.dir,
                application = module.application,
                name = variant.name,
                buildType = variant.buildType,
                flavors = variant.flavors,
                assembleTask = variant.assembleTask,
                debuggable = variant.debuggable,
                order = moduleIndex * 1000 + index,
                namespace = module.namespace,
            )
        }
    }

    fun module(path: String): Module? = modules.firstOrNull { it.path == path }

    companion object {
        const val FILE = ".platform/gradle-tooling.json"

        fun file(projectDir: Path): Path = projectDir.resolve(FILE)

        fun load(projectDir: Path): GradleProjectSnapshot? {
            val path = file(projectDir)
            if (!Files.isRegularFile(path)) return null
            return runCatching { parse(path.readText()) }.getOrNull()
        }

        fun store(projectDir: Path, raw: String) {
            val path = file(projectDir)
            Files.createDirectories(path.parent)
            path.writeText(raw)
        }

        fun parse(text: String): GradleProjectSnapshot {
            val root = JsonReader.obj(JsonReader.parse(text)) ?: error("gradle model is not an object")
            return GradleProjectSnapshot(
                name = JsonReader.str(root, "name") ?: "gradle-project",
                gradleVersion = JsonReader.str(root, "gradleVersion"),
                modules = JsonReader.arr(root["modules"]).mapNotNull { parseModule(it) },
                issues = JsonReader.arr(root["issues"]).mapNotNull { item ->
                    val obj = JsonReader.obj(item) ?: return@mapNotNull null
                    Issue(JsonReader.str(obj, "severity") ?: "warning", JsonReader.str(obj, "message") ?: return@mapNotNull null)
                },
                buildToolsVersions = JsonReader.strings(root, "buildToolsVersions"),
                platforms = JsonReader.strings(root, "platforms"),
                ndkVersions = JsonReader.strings(root, "ndkVersions"),
                cmakeVersions = JsonReader.strings(root, "cmakeVersions"),
                raw = text,
            )
        }

        private fun parseModule(item: Any?): Module? {
            val obj = JsonReader.obj(item) ?: return null
            return Module(
                name = JsonReader.str(obj, "name") ?: return null,
                path = JsonReader.str(obj, "path") ?: ":",
                dir = JsonReader.str(obj, "dir").orEmpty(),
                plugins = JsonReader.strings(obj, "plugins"),
                application = JsonReader.bool(obj, "application"),
                variants = JsonReader.arr(obj["variants"]).mapNotNull { parseVariant(it) },
                sourceSets = JsonReader.arr(obj["sourceSets"]).mapNotNull { set ->
                    val s = JsonReader.obj(set) ?: return@mapNotNull null
                    SourceSet(
                        JsonReader.str(s, "name") ?: "main",
                        JsonReader.strings(s, "java"),
                        JsonReader.strings(s, "kotlin"),
                        JsonReader.strings(s, "resources"),
                    )
                },
                dependencies = JsonReader.arr(obj["dependencies"]).mapNotNull { dep ->
                    val d = JsonReader.obj(dep) ?: return@mapNotNull null
                    Dependency(
                        JsonReader.str(d, "configuration") ?: "implementation",
                        JsonReader.str(d, "coordinate"),
                        JsonReader.str(d, "project"),
                    )
                },
                namespace = JsonReader.str(obj, "namespace"),
                compileSdk = scalar(obj["compileSdk"]),
                minSdk = scalar(obj["minSdk"]),
                targetSdk = scalar(obj["targetSdk"]),
                buildToolsVersion = scalar(obj["buildToolsVersion"]),
                ndkVersion = scalar(obj["ndkVersion"]),
                cmakeVersion = scalar(obj["cmakeVersion"]),
                aidl = JsonReader.bool(obj, "aidl"),
            )
        }

        private fun parseVariant(item: Any?): Variant? {
            val obj = JsonReader.obj(item) ?: return null
            val name = JsonReader.str(obj, "name") ?: return null
            return Variant(
                name = name,
                buildType = JsonReader.str(obj, "buildType") ?: name,
                flavors = JsonReader.strings(obj, "flavors"),
                assembleTask = JsonReader.str(obj, "assembleTask") ?: "assemble",
                debuggable = JsonReader.bool(obj, "debuggable", default = name == "debug" || name == "main"),
            )
        }

        private fun scalar(value: Any?): String? = when (value) {
            null -> null
            is String -> value
            is Long -> value.toString()
            is Double -> {
                if (value == Math.rint(value)) value.toLong().toString() else value.toString()
            }
            else -> value.toString()
        }
    }
}
