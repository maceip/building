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
        null
    }.getOrNull()

    override fun syncFiles(): List<String> = listOf("Package.swift")

    override suspend fun resolve(request: SyncRequest): SyncOutcome {
        request.progress.checkCanceled()
        val packageSwift = request.root.resolve("Package.swift")
        val name = if (Files.isRegularFile(packageSwift)) extractPackageName(packageSwift) ?: request.root.fileName?.toString() ?: "swift-project" else request.root.fileName?.toString() ?: "swift-project"
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
                "Swift & iOS project: use Run project in the Runtime workbench to build and run with xtool/Swift.",
            )),
        )
    }

    private fun extractPackageName(packageSwift: Path): String? = runCatching {
        val text = Files.readString(packageSwift)
        val match = Regex("""name:\s*"([^"]+)"""").find(text)
        match?.groupValues?.get(1)
    }.getOrNull()
}
