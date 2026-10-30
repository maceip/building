package dev.ide.ui.screens

import dev.ide.ui.IdeUiState
import dev.ide.ui.OpenFile
import dev.ide.ui.backend.FileActions
import dev.ide.ui.backend.TreeNode
import dev.ide.ui.backend.UiProjectResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Breadcrumb fallback when the caret isn't inside an analyzer-resolved declaration:
 * omits the project root title (already shown in [EditorTopBar]) and active tab filename (already shown
 * in [TabsStrip]), and includes relative directory context plus the primary declaration/symbol in the file.
 */
internal fun breadcrumbFor(state: IdeUiState, file: OpenFile): List<String> {
    val projectName = state.backend.project.name.trim()
    val module = state.backend.files.moduleNameForFile(file.path)
        ?.takeIf { it.isNotBlank() && !it.equals(projectName, ignoreCase = true) }

    val rootDir = state.tree.dirPath?.replace('\\', '/')?.trimEnd('/').orEmpty()
    val fullPath = file.path.replace('\\', '/')
    val relPath = if (rootDir.isNotEmpty() && fullPath.startsWith("$rootDir/")) {
        fullPath.removePrefix("$rootDir/")
    } else {
        fullPath
    }
    val parentDirs = relPath.split('/')
        .dropLast(1)
        .filter { seg ->
            seg.isNotEmpty() &&
                !seg.equals(projectName, ignoreCase = true) &&
                !seg.equals(module, ignoreCase = true) &&
                seg !in setOf("src", "main", "kotlin", "java", "projects", "files", "0", "user", "data")
        }
        .takeLast(2)

    val symbolCrumbs = extractPrimarySymbolCrumbs(file.text)
    val combined = (listOfNotNull(module) + parentDirs + symbolCrumbs).distinct()
    if (combined.isNotEmpty()) return combined

    val fallbackParent = fullPath.split('/').dropLast(1).lastOrNull()
        ?.takeIf { it.isNotEmpty() && !it.equals(projectName, ignoreCase = true) }
    return listOfNotNull(
        fallbackParent
            ?: file.name.substringAfterLast('.', file.name).uppercase().takeIf { it.isNotBlank() }
            ?: file.name
    )
}

private val PRIMARY_TYPE_REGEX = Regex(
    "(?:^|\\n)\\s*(?:@[\\w.]+(?:\\([^)]*\\))?\\s*)*(?:(?:public|private|internal|protected|open|abstract|final|sealed|data|value|inline|actual|expect)\\s+)*(?:class|struct|interface|object|enum|protocol|extension|actor|record)\\s+`?([A-Za-z_][\\w]*)`?",
)
private val PRIMARY_FUNC_REGEX = Regex(
    "(?:^|\\n)\\s*(?:@[\\w.]+(?:\\([^)]*\\))?\\s*)*(?:(?:public|private|internal|protected|open|override|suspend|inline|static|mutating)\\s+)*(?:fun|func|def)\\s+`?([A-Za-z_][\\w]*)`?",
)
private val SWIFT_BODY_PROP_REGEX = Regex(
    "(?:^|\\n)\\s*var\\s+(body)\\s*:\\s*some\\s+\\w+",
)

internal fun extractPrimarySymbolCrumbs(text: String): List<String> {
    if (text.isBlank()) return emptyList()
    val typeName = PRIMARY_TYPE_REGEX.find(text)?.groupValues?.getOrNull(1)
    val swiftBody = SWIFT_BODY_PROP_REGEX.find(text)?.groupValues?.getOrNull(1)
    val funcName = PRIMARY_FUNC_REGEX.find(text)?.groupValues?.getOrNull(1)
    return when {
        typeName != null && swiftBody != null && funcName == null -> listOf(typeName, swiftBody)
        typeName != null -> listOf(typeName)
        funcName != null -> listOf(funcName)
        else -> emptyList()
    }
}

/** The first Java/Kotlin source root's directory in the tree (depth-first), or null if there is none. */
private fun firstSourceRootDir(root: TreeNode): String? {
    root.sourceRootPath?.let { return it }
    for (c in root.children) firstSourceRootDir(c)?.let { return it }
    return null
}

/**
 * Launch the host's file picker to import external file(s) into the first source root, then refresh the
 * tree and open the first imported file. No-op when there's no source root to target.
 */
internal fun doImport(state: IdeUiState, fileActions: FileActions) {
    val dir = firstSourceRootDir(state.tree) ?: state.tree.dirPath ?: return
    doImportInto(state, fileActions, dir)
}

/**
 * Launch the host's file picker to import external file(s) into [dir] (a specific project directory chosen
 * from a tree row's context menu), then refresh the tree and open the first imported file.
 */
internal fun doImportInto(state: IdeUiState, fileActions: FileActions, dir: String) {
    fileActions.importInto(dir) { paths ->
        state.refreshTree()
        paths.firstOrNull()?.let { p -> state.open(p, p.substringAfterLast('/').substringAfterLast('\\')) }
    }
}

/**
 * Pick a folder via the host's directory picker and import it as a Gradle project (best effort). [onBusy]
 * fires once a folder is chosen — before the slow copy + script parse + resolve — so the caller can show a
 * progress indicator; [onResult] receives the backend result, or null when the user cancelled the picker.
 * Extracted from [dev.ide.ui.CodeAssistApp] so the pick → import wiring is unit-testable without Compose.
 */
fun doImportGradle(
    fileActions: FileActions,
    scope: CoroutineScope,
    onBusy: () -> Unit,
    import: suspend (String) -> UiProjectResult,
    onResult: (UiProjectResult?) -> Unit,
) {
    fileActions.pickDirectory { path ->
        if (path.isNullOrBlank()) {
            onResult(null)
            return@pickDirectory
        }
        onBusy()
        scope.launch { onResult(import(path)) }
    }
}

/** All openable (file-backed) nodes in the tree, flattened — feeds the command palette's file list. */
internal fun openableFiles(root: TreeNode): List<TreeNode> {
    val out = ArrayList<TreeNode>()
    fun walk(n: TreeNode) {
        if (n.filePath != null) out.add(n)
        n.children.forEach(::walk)
    }
    walk(root)
    return out
}
