@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)

package dev.ide.ios

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.requireOwner
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.scene.ComposeScene
import androidx.compose.ui.scene.LocalComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.window.ComposeUIViewController
import dev.ide.ui.CodeAssistApp
import platform.UIKit.UIPasteboard
import platform.UIKit.UIViewController
import kotlin.math.roundToInt

private var activeComposeScene: ComposeScene? = null
private var activeDensityScale: Float = 3f
private var activeSemanticsOwner: SemanticsOwner? = null
private var lastTappedWindowOffset: Offset? = null

private class SemanticsOwnerCaptureNode : Modifier.Node() {
    override fun onAttach() {
        activeSemanticsOwner = requireOwner().rootForTest.semanticsOwner
    }
}

private object SemanticsOwnerCaptureElement : ModifierNodeElement<SemanticsOwnerCaptureNode>() {
    override fun create(): SemanticsOwnerCaptureNode = SemanticsOwnerCaptureNode()
    override fun update(node: SemanticsOwnerCaptureNode) {
        activeSemanticsOwner = node.requireOwner().rootForTest.semanticsOwner
    }
    override fun hashCode(): Int = 17
    override fun equals(other: Any?): Boolean = other === this
}

@OptIn(ExperimentalComposeUiApi::class)
fun MainViewController(): UIViewController = ComposeUIViewController {
    val scene = LocalComposeScene.current
    val density = LocalDensity.current
    SideEffect {
        activeComposeScene = scene
        activeDensityScale = density.density
    }
    Box(modifier = Modifier.fillMaxSize().then(SemanticsOwnerCaptureElement)) {
        CodeAssistApp(
            backend = host.backend,
            composePreviewHost = host.composePreviewHost,
            fileActions = host.fileActions,
            openStoreItemId = host.pendingStoreItem,
            onStoreItemIdHandled = host::storeItemHandled,
        )
    }
}

val host: IosHost by lazy { IosHost() }

fun handleDeepLink(url: String): Boolean = host.handleUrl(url)

private fun escapeXml(s: String): String =
    s.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

/**
 * Dumps the live on-screen accessibility/semantics tree on the physical iPhone in XML format
 * with exact on-screen point coordinates `bounds="[left,top][right,bottom]"` and `x, y, width, height`.
 */
fun dumpLiveUiHierarchyXml(): String {
    val owner = activeSemanticsOwner ?: return "<hierarchy error=\"no_semantics_owner\"/>"
    val scale = activeDensityScale.coerceAtLeast(1f)
    val sb = StringBuilder()
    sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
    sb.append("<hierarchy scale=\"$scale\">\n")

    fun visit(node: SemanticsNode, depth: Int) {
        val indent = "  ".repeat(depth + 1)
        val rect = node.boundsInWindow
        val left = (rect.left / scale).roundToInt()
        val top = (rect.top / scale).roundToInt()
        val right = (rect.right / scale).roundToInt()
        val bottom = (rect.bottom / scale).roundToInt()
        val width = (right - left).coerceAtLeast(0)
        val height = (bottom - top).coerceAtLeast(0)

        val cfg = node.config
        val texts = cfg.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }.orEmpty()
        val editable = cfg.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()
        val desc = cfg.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ").orEmpty()
        val role = cfg.getOrNull(SemanticsProperties.Role)?.toString().orEmpty()
        val focused = cfg.getOrNull(SemanticsProperties.Focused) == true
        val clickable = cfg.contains(SemanticsActions.OnClick)
        val scrollable = cfg.contains(SemanticsActions.ScrollBy)
        val editableField = cfg.contains(SemanticsActions.SetText)
        val displayText = if (editable.isNotEmpty()) editable else texts

        val hasMeaning = displayText.isNotEmpty() || desc.isNotEmpty() || clickable || editableField || scrollable
        if (hasMeaning && width > 0 && height > 0) {
            sb.append(indent)
            sb.append("<Element")
            sb.append(" id=\"${node.id}\"")
            sb.append(" type=\"${escapeXml(if (editableField) "XCUIElementTypeTextField" else if (clickable) "XCUIElementTypeButton" else if (role.isNotEmpty()) role else "XCUIElementTypeStaticText")}\"")
            sb.append(" label=\"${escapeXml(displayText)}\"")
            sb.append(" content-desc=\"${escapeXml(desc)}\"")
            sb.append(" value=\"${escapeXml(editable)}\"")
            sb.append(" clickable=\"$clickable\"")
            sb.append(" focusable=\"$editableField\"")
            sb.append(" focused=\"$focused\"")
            sb.append(" scrollable=\"$scrollable\"")
            sb.append(" x=\"$left\" y=\"$top\" width=\"$width\" height=\"$height\"")
            sb.append(" bounds=\"[$left,$top][$right,$bottom]\"")
            if (node.children.isEmpty()) {
                sb.append("/>\n")
            } else {
                sb.append(">\n")
                node.children.forEach { visit(it, depth + 1) }
                sb.append(indent).append("</Element>\n")
            }
        } else {
            node.children.forEach { visit(it, depth) }
        }
    }

    visit(owner.unmergedRootSemanticsNode, 0)
    sb.append("</hierarchy>")
    return sb.toString()
}

/**
 * Dispatches a physical screen coordinate tap at `(xPt, yPt)` (in iOS screen points)
 * into the live on-screen ComposeScene on the physical iPhone.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun dispatchScreenTap(xPt: Double, yPt: Double): Boolean {
    val scale = activeDensityScale.coerceAtLeast(1f)
    val pos = Offset((xPt * scale).toFloat(), (yPt * scale).toFloat())
    lastTappedWindowOffset = pos
    activeComposeScene?.let { scene ->
        scene.sendPointerEvent(PointerEventType.Press, pos)
        scene.sendPointerEvent(PointerEventType.Release, pos)
    }
    val owner = activeSemanticsOwner
    if (owner != null) {
        val clickables = mutableListOf<SemanticsNode>()
        fun findClickables(node: SemanticsNode) {
            if (node.boundsInWindow.contains(pos) && node.config.contains(SemanticsActions.OnClick)) {
                clickables.add(node)
            }
            node.children.forEach(::findClickables)
        }
        findClickables(owner.unmergedRootSemanticsNode)
        val innermost = clickables.lastOrNull()
        if (innermost != null) {
            val action = innermost.config.getOrNull(SemanticsActions.OnClick)?.action
            if (action != null) {
                action.invoke()
                return true
            }
        }
    }
    return true
}

fun triggerRunBuild() {
    host.backend.build.runBuild()
}

fun triggerRunPointerTap(x: Float, y: Float) {
    host.backend.sendRunPointer(0, x, y)
    host.backend.sendRunPointer(1, x, y)
}

/**
 * Dispatches a physical screen drag/scroll gesture from `(x1Pt, y1Pt)` to `(x2Pt, y2Pt)` (in iOS screen points).
 */
@OptIn(ExperimentalComposeUiApi::class)
fun dispatchScreenSwipe(x1Pt: Double, y1Pt: Double, x2Pt: Double, y2Pt: Double, steps: Int): Boolean {
    val scene = activeComposeScene ?: return false
    val scale = activeDensityScale.coerceAtLeast(1f)
    val start = Offset((x1Pt * scale).toFloat(), (y1Pt * scale).toFloat())
    val end = Offset((x2Pt * scale).toFloat(), (y2Pt * scale).toFloat())
    scene.sendPointerEvent(PointerEventType.Press, start)
    val totalSteps = steps.coerceIn(2, 50)
    for (i in 1..totalSteps) {
        val frac = i.toFloat() / totalSteps.toFloat()
        val cur = Offset(
            start.x + (end.x - start.x) * frac,
            start.y + (end.y - start.y) * frac,
        )
        scene.sendPointerEvent(PointerEventType.Move, cur)
    }
    scene.sendPointerEvent(PointerEventType.Release, end)
    return true
}

/**
 * Enters text into the currently focused text field on the physical iPhone screen
 * (after the text field has been focused via a screen tap at its (X, Y) coordinates).
 */
fun dispatchFocusedTextInput(text: String): Boolean {
    UIPasteboard.generalPasteboard.string = text
    val owner = activeSemanticsOwner ?: return false
    val allNodes = mutableListOf<SemanticsNode>()
    fun collect(n: SemanticsNode) {
        allNodes += n
        n.children.forEach(::collect)
    }
    collect(owner.unmergedRootSemanticsNode)
    val tappedPos = lastTappedWindowOffset
    val target = allNodes.firstOrNull {
        it.config.getOrNull(SemanticsProperties.Focused) == true &&
            it.config.contains(SemanticsActions.SetText)
    } ?: allNodes.firstOrNull {
        tappedPos != null &&
            it.config.contains(SemanticsActions.SetText) &&
            it.boundsInWindow.contains(tappedPos)
    } ?: allNodes.firstOrNull {
        it.config.contains(SemanticsActions.SetText)
    } ?: return false
    target.config.getOrNull(SemanticsActions.RequestFocus)?.action?.invoke()
    val action = target.config.getOrNull(SemanticsActions.SetText)?.action ?: return false
    return action(AnnotatedString(text))
}
