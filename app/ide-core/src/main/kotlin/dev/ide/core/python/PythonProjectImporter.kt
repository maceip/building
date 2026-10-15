package dev.ide.core.python

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

/** A Python project. */
object PythonModuleType : ModuleType {
    val BUILD_SYSTEM = BuildSystemId("python")
    override val id = "python"
    override val displayName = "Python"
    override val platform = PlatformKind("PYTHON")
    override fun supportedBuildSystems(): Set<BuildSystemId> = setOf(BUILD_SYSTEM)
    override fun defaultFacets(): List<FacetTemplate> = emptyList()
    override fun defaultSourceSets(): List<SourceSetTemplate> = listOf(
        SourceSetTemplate("main", DependencyScope.IMPLEMENTATION, mapOf("." to setOf(ContentRole("python-source")))),
    )
}

/** Project importer for Python projects (pyproject.toml, requirements.txt, setup.py, or standalone scripts). */
class PythonProjectImporter : ProjectImporter {
    override val id = PythonModuleType.BUILD_SYSTEM
    override val displayName = "Python"

    override fun detect(root: Path): Detection? = runCatching {
        if (!Files.isDirectory(root)) return null
        if (ModelPersistence.exists(root.toString())) {
            val projects = ModelPersistence.load(root.toString()).projects
            val formerlyUnknown = dev.ide.core.sync.UnrecognizedProjectMarker.exists(root) && projects.all { it.modules.isEmpty() }
            if (projects.isNotEmpty() && projects.none { it.buildSystemId == id.value } && !formerlyUnknown) return null
        }
        val pyproject = root.resolve("pyproject.toml")
        if (Files.isRegularFile(pyproject)) {
            val name = extractTomlName(pyproject) ?: root.fileName?.toString() ?: "python-project"
            return Detection(name = name, markers = listOf(pyproject.toAbsolutePath()), confidence = 6)
        }
        val setupPy = root.resolve("setup.py")
        if (Files.isRegularFile(setupPy)) {
            return Detection(name = root.fileName?.toString() ?: "python-project", markers = listOf(setupPy.toAbsolutePath()), confidence = 6)
        }
        val reqs = root.resolve("requirements.txt")
        if (Files.isRegularFile(reqs)) {
            return Detection(name = root.fileName?.toString() ?: "python-project", markers = listOf(reqs.toAbsolutePath()), confidence = 5)
        }
        val pipfile = root.resolve("Pipfile")
        if (Files.isRegularFile(pipfile)) {
            return Detection(name = root.fileName?.toString() ?: "python-project", markers = listOf(pipfile.toAbsolutePath()), confidence = 5)
        }
        val setupCfg = root.resolve("setup.cfg")
        if (Files.isRegularFile(setupCfg)) {
            return Detection(name = root.fileName?.toString() ?: "python-project", markers = listOf(setupCfg.toAbsolutePath()), confidence = 5)
        }
        null
    }.getOrNull()

    override fun syncFiles(): List<String> = listOf("pyproject.toml", "requirements.txt", "setup.py")

    override suspend fun resolve(request: SyncRequest): SyncOutcome {
        request.progress.checkCanceled()
        val name = request.root.fileName?.toString() ?: "python-project"
        return SyncOutcome(
            ExternalProjectModel(
                name = name,
                buildSystemId = id,
                modules = listOf(ExternalModule(
                    name = "python",
                    dirRelPath = ".",
                    typeId = PythonModuleType.id,
                    languageLevel = LanguageLevel("PYTHON"),
                )),
            ),
            listOf(SyncMessage(
                SyncSeverity.INFO,
                "Python project: use Run project in the Runtime workbench to execute Python scripts.",
            )),
        )
    }

    private fun extractTomlName(toml: Path): String? = runCatching {
        val text = Files.readString(toml)
        val match = Regex("""name\s*=\s*"([^"]+)"""").find(text)
        match?.groupValues?.get(1)
    }.getOrNull()
}
