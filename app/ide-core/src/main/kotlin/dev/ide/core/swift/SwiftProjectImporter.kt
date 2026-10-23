package dev.ide.core.swift

import dev.ide.model.BuildSystemId
import dev.ide.model.ContentRole
import dev.ide.model.DependencyScope
import dev.ide.model.FacetTemplate
import dev.ide.model.LanguageLevel
import dev.ide.model.ModuleType
import dev.ide.model.PlatformKind
import dev.ide.model.SourceSetTemplate
import dev.ide.model.impl.ModelPersistence
import dev.ide.model.sync.Detection
import dev.ide.model.sync.ExternalModule
import dev.ide.model.sync.ExternalProjectModel
import dev.ide.model.sync.ProjectImporter
import dev.ide.model.sync.SyncMessage
import dev.ide.model.sync.SyncOutcome
import dev.ide.model.sync.SyncRequest
import dev.ide.model.sync.SyncSeverity
import java.nio.file.Files
import java.nio.file.Path

/** A Swift / XTool project. */
object SwiftModuleType : ModuleType {
    val BUILD_SYSTEM = BuildSystemId("swift")
    override val id = "swift"
    override val displayName = "Swift & iOS"
    override val platform = PlatformKind("SWIFT")
    override fun supportedBuildSystems(): Set<BuildSystemId> = setOf(BUILD_SYSTEM)
    override fun defaultFacets(): List<FacetTemplate> = emptyList()
    override fun defaultSourceSets(): List<SourceSetTemplate> = listOf(
        SourceSetTemplate("main", DependencyScope.IMPLEMENTATION, mapOf("." to setOf(ContentRole("swift-source")))),
    )
}

/** Project importer for SwiftPM and standalone Swift / XTool projects. */
class SwiftProjectImporter : ProjectImporter {
    override val id = SwiftModuleType.BUILD_SYSTEM
    override val displayName = "Swift & iOS"

    override fun detect(root: Path): Detection? = runCatching {
        if (!Files.isDirectory(root)) return null
        if (ModelPersistence.exists(root.toString())) {
            val projects = ModelPersistence.load(root.toString()).projects
            val formerlyUnknown = dev.ide.core.sync.UnrecognizedProjectMarker.exists(root) && projects.all { it.modules.isEmpty() }
            if (projects.isNotEmpty() && projects.none { it.buildSystemId == id.value } && !formerlyUnknown) return null
        }
        val packageSwift = root.resolve("Package.swift")
        if (Files.isRegularFile(packageSwift)) {
            val name = extractPackageName(packageSwift) ?: root.fileName?.toString() ?: "swift-project"
            return Detection(
                name = name,
                markers = listOf(packageSwift.toAbsolutePath()),
                confidence = 6,
            )
        }
        val xtoolYml = root.resolve("xtool.yml")
        if (Files.isRegularFile(xtoolYml)) {
            return Detection(
                name = root.fileName?.toString() ?: "swift-project",
                markers = listOf(xtoolYml.toAbsolutePath()),
                confidence = 6,
            )
        }
        val xcodeproj = Files.walk(root, 3).use { stream ->
            stream.filter {
                Files.isDirectory(it) &&
                    (it.fileName.toString().endsWith(".xcodeproj") || it.fileName.toString().endsWith(".xcworkspace"))
            }.findFirst().orElse(null)
        }
        if (xcodeproj != null) {
            val name = xcodeproj.fileName.toString()
                .removeSuffix(".xcodeproj")
                .removeSuffix(".xcworkspace")
            return Detection(
                name = name,
                markers = listOf(xcodeproj.toAbsolutePath()),
                confidence = 6,
            )
        }
        val nestedPackageSwift = Files.walk(root, 3).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.fileName.toString() == "Package.swift" }
                .findFirst().orElse(null)
        }
        if (nestedPackageSwift != null) {
            val name = extractPackageName(nestedPackageSwift) ?: root.fileName?.toString() ?: "swift-project"
            return Detection(
                name = name,
                markers = listOf(nestedPackageSwift.toAbsolutePath()),
                confidence = 6,
            )
        }
        val swiftSource = dev.ide.core.runtime.SwiftBuildSystem.findSwiftSourceFiles(root).firstOrNull()
        if (swiftSource != null) {
            return Detection(
                name = root.fileName?.toString() ?: "swift-project",
                markers = listOf(swiftSource.toAbsolutePath()),
                confidence = 6,
            )
        }
        null
    }.getOrNull()

    override fun syncFiles(): List<String> = listOf("Package.swift", "xtool.yml")

    override suspend fun resolve(request: SyncRequest): SyncOutcome {
        request.progress.checkCanceled()
        val packageSwift = request.root.resolve("Package.swift")
        val fallbackName = request.root.fileName?.toString() ?: "swift-project"
        // If a cloned SwiftUI / Xcode repo does not yet have Package.swift or xtool.yml, scaffold them
        // on import so the project immediately compiles with `swift build` and `xtool dev build --ipa`.
        val isIos = dev.ide.core.runtime.SwiftBuildSystem.isIosOrSwiftUiProject(request.root)
        dev.ide.core.runtime.SwiftBuildSystem.ensureSwiftScaffolding(request.root, fallbackName, forIpa = isIos)
        val name = if (Files.isRegularFile(packageSwift)) {
            extractPackageName(packageSwift) ?: fallbackName
        } else {
            fallbackName
        }
        return SyncOutcome(
            ExternalProjectModel(
                name = name,
                buildSystemId = id,
                modules = listOf(ExternalModule(
                    name = "swift",
                    dirRelPath = ".",
                    typeId = SwiftModuleType.id,
                    languageLevel = LanguageLevel("SWIFT"),
                )),
            ),
            listOf(SyncMessage(
                SyncSeverity.INFO,
                "Swift & iOS project ready: press Build / Run to build an iOS .ipa or Swift package.",
            )),
        )
    }

    private fun extractPackageName(packageSwift: Path): String? = runCatching {
        val text = Files.readString(packageSwift)
        Regex("""Package\s*\(\s*name:\s*"([^"]+)"""").find(text)?.groupValues?.get(1)
            ?: Regex("""name:\s*"([^"]+)"""").find(text)?.groupValues?.get(1)
    }.getOrNull()
}
