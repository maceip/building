package fragments.agentui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf

/** A host-owned renderer for the body of one generic tool kind. The kit keeps the tool chrome. */
fun interface ToolRenderer {
    @Composable fun Render(tool: ChatTool.Generic)
}

/** Register once for a feature lifetime; close the returned handle when that feature stops. */
class ToolRendererRegistry {
    private val renderers = mutableStateMapOf<String, ToolRenderer>()

    fun register(kind: String, renderer: ToolRenderer): AutoCloseable {
        require(kind.isNotBlank()) { "Tool kind must be nonblank" }
        require(kind !in renderers) { "Tool renderer already registered: $kind" }
        renderers[kind] = renderer
        return AutoCloseable { if (renderers[kind] === renderer) renderers.remove(kind) }
    }

    fun renderer(kind: String): ToolRenderer? = renderers[kind]
}
