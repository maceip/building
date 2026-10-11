package dev.ide.core.gradle

import dev.ide.android.support.AndroidFacet
import dev.ide.android.support.AndroidFacetCodec
import dev.ide.android.support.BuildType
import dev.ide.android.support.ProductFlavor
import dev.ide.gradle.GradleEnvironment
import dev.ide.gradle.GradleProjectSnapshot
import dev.ide.gradle.GradleToolchain
import dev.ide.model.BuildSystemId
import dev.ide.model.ContentRole
import dev.ide.model.DependencyScope
import dev.ide.model.sync.Detection
import dev.ide.model.sync.ExternalDependency
import dev.ide.model.sync.ExternalFacet
import dev.ide.model.sync.ExternalLibrary
import dev.ide.model.sync.ExternalModule
import dev.ide.model.sync.ExternalModuleRef
import dev.ide.model.sync.ExternalProjectModel
import dev.ide.model.sync.ExternalSourceSet
import dev.ide.model.sync.ModelOwnership
import dev.ide.model.sync.ProjectImporter
import dev.ide.model.sync.SyncMessage
import dev.ide.model.sync.SyncOutcome
import dev.ide.model.sync.SyncRequest
import dev.ide.model.sync.SyncSeverity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path

/**
 * Imports a Gradle project by executing its build files through the tooling server.
 *
 * Confidence sits between the static script reader (10) and an exported model (100), and only when a
 * shell has opted in and a JDK is actually installed. A failure leaves the static snapshot in place
 * and says the build files were not executed. The static reader remains the project the editor has
 * before a toolchain exists.
 */
class GradleToolingImporter : ProjectImporter {

    override val id: BuildSystemId = BuildSystemId.GRADLE

    override val displayName: String = "Gradle"

    override val ownership: ModelOwnership = ModelOwnership.EXTERNAL

    override fun detect(root: Path): Detection? {
        if (!GradleEnvironment.enabled) return null
        val host = GradleEnvironment.host ?: return null
        if (!host.available()) return null
        if (!GradleImport.isGradleProject(root)) return null
        return Detection(
            name = root.fileName?.toString() ?: "gradle-project",
            markers = GradleImport.buildFiles(root),
            confidence = CONFIDENCE,
        )
    }

    override fun syncFiles(): List<String> = GradleProjectImporter().syncFiles()

    override suspend fun resolve(request: SyncRequest): SyncOutcome = withContext(Dispatchers.IO) {
        val fallback = GradleProjectImporter().resolve(request)
        val host = GradleEnvironment.host
        if (host == null || !host.available()) return@withContext fallback
        try {
            val snapshot = GradleToolchain.sync(request.root, host)
            if (snapshot.raw.isNotBlank()) GradleProjectSnapshot.store(request.root, snapshot.raw)
            val issues = snapshot.issues.map { issue ->
                val severity = if (issue.severity.equals("error", ignoreCase = true)) SyncSeverity.ERROR else SyncSeverity.WARNING
                SyncMessage(severity, issue.message)
            }
            SyncOutcome(model(snapshot), fallback.messages + issues)
        } catch (e: Exception) {
            val detail = e.message?.lineSequence()?.firstOrNull { it.isNotBlank() } ?: "the build files were not executed"
            val message = SyncMessage(
                SyncSeverity.WARNING,
                "The build files were not executed ($detail). The project is open from its scripts.",
            )
            fallback.copy(messages = fallback.messages + message)
        }
    }

    private fun model(snapshot: GradleProjectSnapshot): ExternalProjectModel = ExternalProjectModel(
        name = snapshot.name,
        buildSystemId = BuildSystemId.GRADLE,
        modules = snapshot.modules.map { module(it) },
    )

    private fun module(spec: GradleProjectSnapshot.Module): ExternalModule {
        val android = spec.application || spec.plugins.any { it.startsWith("com.android.") }
        val typeId = when {
            spec.application -> "android-app"
            spec.plugins.any { it == "com.android.library" || it == "com.android.dynamic-feature" } -> "android-lib"
            else -> "java-lib"
        }
        return ExternalModule(
            name = spec.name,
            dirRelPath = spec.dir.ifBlank { if (spec.path == ":") "." else spec.name },
            typeId = typeId,
            sourceSets = if (android) emptyList() else sourceSets(spec),
            dependencies = dependencies(spec),
            facets = if (android) listOf(facet(spec)) else emptyList(),
        )
    }

    private fun sourceSets(spec: GradleProjectSnapshot.Module): List<ExternalSourceSet> {
        val main = spec.sourceSets.firstOrNull { it.name == "main" }
        val roots = linkedMapOf<String, Set<ContentRole>>()
        val java = main?.java?.ifEmpty { listOf("src/main/java") } ?: listOf("src/main/java")
        val kotlin = main?.kotlin?.ifEmpty { listOf("src/main/kotlin") } ?: listOf("src/main/kotlin")
        val resources = main?.resources?.ifEmpty { listOf("src/main/resources") } ?: listOf("src/main/resources")
        java.forEach { roots[it] = setOf(ContentRole.SOURCE) }
        kotlin.forEach { roots[it] = setOf(ContentRole.SOURCE) }
        resources.forEach { roots[it] = setOf(ContentRole.RESOURCE) }
        return listOf(ExternalSourceSet("main", DependencyScope.IMPLEMENTATION, roots))
    }

    private fun dependencies(spec: GradleProjectSnapshot.Module): List<ExternalDependency> = spec.dependencies.mapNotNull { dep ->
        val scope = scope(dep.configuration)
        val projectPath = dep.project
        val coordinate = dep.coordinate
        when {
            projectPath != null -> ExternalModuleRef(projectPath.substringAfterLast(':').ifBlank { projectPath }, scope)
            coordinate != null -> ExternalLibrary(coordinate, scope)
            else -> null
        }
    }

    private fun scope(configuration: String): DependencyScope = when (configuration.substringAfterLast('.')) {
        "api" -> DependencyScope.API
        "compileOnly" -> DependencyScope.COMPILE_ONLY
        "runtimeOnly" -> DependencyScope.RUNTIME_ONLY
        "testImplementation" -> DependencyScope.TEST_IMPLEMENTATION
        else -> DependencyScope.IMPLEMENTATION
    }

    private fun facet(spec: GradleProjectSnapshot.Module): ExternalFacet {
        val buildTypes = spec.variants.map { it.buildType }.distinct().filter { it.isNotBlank() && it != "main" }
        val flavors = spec.variants.flatMap { it.flavors }.distinct()
        val android = AndroidFacet(
            namespace = spec.namespace ?: "com.example.${spec.name}",
            compileSdk = leadingInt(spec.compileSdk) ?: DEFAULT_COMPILE_SDK,
            minSdk = leadingInt(spec.minSdk) ?: DEFAULT_MIN_SDK,
            targetSdk = leadingInt(spec.targetSdk) ?: leadingInt(spec.minSdk) ?: DEFAULT_MIN_SDK,
            isApplication = spec.application,
            flavorDimensions = if (flavors.isEmpty()) emptyList() else listOf("default"),
            buildTypes = if (buildTypes.isEmpty()) AndroidFacet.DEFAULT_BUILD_TYPES
            else buildTypes.map { name ->
                BuildType(name, debuggable = spec.variants.filter { it.buildType == name }.any { it.debuggable })
            },
            productFlavors = flavors.map { ProductFlavor(it, dimension = "default") },
        )
        return ExternalFacet(AndroidFacetCodec.tomlTable, AndroidFacetCodec.encode(android))
    }

    private fun leadingInt(text: String?): Int? {
        if (text.isNullOrBlank()) return null
        val digits = text.trim().removePrefix("android-").takeWhile { it.isDigit() }
        return digits.toIntOrNull()
    }

    private companion object {
        const val CONFIDENCE = 50
        const val DEFAULT_COMPILE_SDK = 36
        const val DEFAULT_MIN_SDK = 21
    }
}
