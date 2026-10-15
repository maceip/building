package dev.ide.core.node

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
import dev.ide.workbench.runtime.NodePackageJson
import java.nio.file.Path

/** A package.json project, not a synthetic Java module or an Android build. */
object NodeModuleType : ModuleType {
    val BUILD_SYSTEM = BuildSystemId("node")
    override val id = "node"
    override val displayName = "Node.js"
    override val platform = PlatformKind("NODE")
    override fun supportedBuildSystems(): Set<BuildSystemId> = setOf(BUILD_SYSTEM)
    override fun defaultFacets(): List<FacetTemplate> = emptyList()
    // A non-JVM role keeps the JVM source/index/compiler sweeps out of node_modules and package sources.
    // The editor's Node tree presents real directories, rather than Java package compaction.
    override fun defaultSourceSets(): List<SourceSetTemplate> = listOf(
        SourceSetTemplate("main", DependencyScope.IMPLEMENTATION, mapOf("." to setOf(ContentRole("node-source")))),
    )
}

/** Detection and sync only parse bounded JSON. Executing scripts remains an explicit Runtime action. */
class NodeProjectImporter : ProjectImporter {
    override val id = NodeModuleType.BUILD_SYSTEM
    override val displayName = "Node.js"

    override fun detect(root: Path): Detection? = runCatching {
        // A package manifest added to a native/Gradle workspace must not silently change its owner.
        if (ModelPersistence.exists(root.toString())) {
            val projects = ModelPersistence.load(root.toString()).projects
            val formerlyUnknown = dev.ide.core.sync.UnrecognizedProjectMarker.exists(root) && projects.all { it.modules.isEmpty() }
            if (projects.isNotEmpty() && projects.none { it.buildSystemId == id.value } && !formerlyUnknown) return null
        }
        val manifest = NodePackageJson.read(root) ?: return null
        Detection(
            name = manifest.name ?: root.fileName?.toString() ?: "node-project",
            markers = listOf(root.toAbsolutePath().resolve("package.json")),
            // A Gradle/native project with a tooling package.json stays with its existing importer.
            confidence = 5,
        )
    }.getOrNull()

    override fun syncFiles(): List<String> = listOf("package.json")

    override suspend fun resolve(request: SyncRequest): SyncOutcome {
        request.progress.checkCanceled()
        request.progress.report(-1.0, "Reading package.json (no scripts are executed)")
        val manifest = NodePackageJson.read(request.root)
            ?: return SyncOutcome.failed("Cannot read a valid package.json object inside the project (maximum 1 MiB).")
        val name = manifest.name ?: request.root.fileName?.toString() ?: "node-project"
        return SyncOutcome(
            ExternalProjectModel(
                name = name,
                buildSystemId = id,
                modules = listOf(ExternalModule(
                    name = "node",
                    dirRelPath = ".",
                    typeId = NodeModuleType.id,
                    languageLevel = LanguageLevel("ECMASCRIPT"),
                )),
            ),
            listOf(SyncMessage(
                SyncSeverity.INFO,
                "Node.js project: ${manifest.scripts.size} package script(s). Use Run Node project to select an npm script " +
                    "or JavaScript file in the Runtime workbench. Dependencies are installed only on request.",
            )),
        )
    }
}
