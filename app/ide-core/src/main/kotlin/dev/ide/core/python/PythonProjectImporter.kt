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
        // Also recognize cloned Python repos that have standard top-level Python entrypoints (`main.py`, `app.py`, `run.py`, `cli.py`, `server.py`, `manage.py`)
        // or any top-level `.py` script directly in the repository root.
        for (entry in listOf("main.py", "app.py", "run.py", "cli.py", "server.py", "manage.py", "start-abus.py", "one_click.py", "salary_lookup.py")) {
            val candidate = root.resolve(entry)
            if (Files.isRegularFile(candidate)) {
                return Detection(name = root.fileName?.toString() ?: "python-project", markers = listOf(candidate.toAbsolutePath()), confidence = 6)
            }
        }
        val excluded = setOf(".git", ".platform", "node_modules", "build", "dist", ".venv", "venv", "__pycache__")
        var firstPy: Path? = null
        var subPyManifest: Path? = null
        var pyCount = 0
        var tsJsCount = 0
        Files.walk(root, 6).use { stream ->
            stream.filter { p ->
                Files.isRegularFile(p) &&
                    root.relativize(p).none { seg -> seg.toString() in excluded }
            }.sorted().forEach { p ->
                val fileName = p.fileName.toString()
                when {
                    fileName.endsWith(".py") -> {
                        pyCount++
                        if (firstPy == null) firstPy = p
                    }
                    (fileName.endsWith(".ts") || fileName.endsWith(".tsx") || fileName.endsWith(".js") || fileName.endsWith(".mjs") || fileName.endsWith(".cjs")) &&
                        !fileName.endsWith(".d.ts") -> {
                        tsJsCount++
                    }
                    fileName in setOf("pyproject.toml", "setup.py", "requirements.txt") && subPyManifest == null -> {
                        subPyManifest = p
                    }
                }
            }
        }
        if (subPyManifest != null && pyCount > tsJsCount) {
            return Detection(
                name = root.fileName?.toString() ?: "python-project",
                markers = listOf(subPyManifest!!.toAbsolutePath()),
                confidence = 6,
            )
        }
        val anyPy = firstPy
        if (anyPy != null) {
            val conf = if (pyCount > tsJsCount) 4 else 3
            return Detection(name = root.fileName?.toString() ?: "python-project", markers = listOf(anyPy.toAbsolutePath()), confidence = conf)
        }
        null
    }.getOrNull()

    override fun syncFiles(): List<String> = listOf("pyproject.toml", "requirements.txt", "setup.py", "main.py", "app.py")

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
                "Python project ready: press Run to execute Python scripts.",
            )),
        )
    }

    private fun extractTomlName(toml: Path): String? = runCatching {
        val text = Files.readString(toml)
        val match = Regex("""name\s*=\s*"([^"]+)"""").find(text)
        match?.groupValues?.get(1)
    }.getOrNull()
}
