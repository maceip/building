package dev.ide.ui.testing

import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import java.awt.BasicStroke
import java.awt.Color as AwtColor
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageTypeSpecifier
import javax.imageio.metadata.IIOMetadata
import javax.imageio.metadata.IIOMetadataNode
import javax.imageio.stream.FileImageOutputStream
import kotlin.math.abs

/**
 * Cash App Paparazzi-inspired Compose Multiplatform visual, accessibility, animation, and interactivity
 * verification harness.
 *
 * Capabilities:
 * 1. [DeviceConfig]-driven deterministic off-screen rendering via [ImageComposeScene].
 * 2. [AccessibilityRenderExtension]: renders a 2x-wide side-by-side image with color-coded semantic
 *    bounding boxes on the left and a numbered interactive/accessibility legend on the right.
 * 3. [SnapshotVerifier]: compares rendered bitmaps against golden files and produces a 3-pane
 *    `[Expected | Pixel Diff | Actual]` composite PNG on mismatch or request.
 * 4. [captureGifFrames]: steps the scene clock frame-by-frame to capture animations/shimmers as both
 *    an animated `.gif` and a horizontal filmstrip `.png`, and verifies pixel motion within target bounds.
 * 5. [assertNoDeadClickables]: walks every clickable/toggleable semantics node in the scene, clicks its
 *    center, and fails the test if any interactive element is a dead no-op (mutating neither caller
 *    backend state nor the semantic tree).
 * 6. [HtmlReportWriter]: emits a self-contained HTML inspection gallery (`index.html`) for all captured
 *    snapshots, accessibility overlays, diffs, and animation strips.
 */
@OptIn(ExperimentalComposeUiApi::class)
class PaparazziSession(
    val deviceConfig: DeviceConfig = DeviceConfig.PIXEL_9_PRO,
    val reportDir: File = File("build/reports/paparazzi"),
    content: @Composable () -> Unit,
) : AutoCloseable {

    val scene: ImageComposeScene = ImageComposeScene(
        width = deviceConfig.widthPx,
        height = deviceConfig.heightPx,
        density = Density(density = deviceConfig.density, fontScale = deviceConfig.fontScale),
        content = content,
    )

    private var nanoTime: Long = 0L
    private val recordedArtifacts = mutableListOf<ReportArtifact>()

    init {
        reportDir.mkdirs()
        advanceFrames(10)
    }

    /** Advances the Compose scene clock by [frameCount] 60fps frames (`16.67ms` each) and settles snapshots. */
    fun advanceFrames(frameCount: Int = 5, frameStepNanos: Long = 16_666_667L) {
        Snapshot.sendApplyNotifications()
        repeat(frameCount.coerceAtLeast(1)) {
            nanoTime += frameStepNanos
            scene.render(nanoTime)
        }
        Snapshot.sendApplyNotifications()
        scene.render(nanoTime)
    }

    /** Renders the current frame into a standard ARGB [BufferedImage]. */
    fun renderBitmap(): BufferedImage {
        Snapshot.sendApplyNotifications()
        val skiaImage = scene.render(nanoTime)
        val pngData = skiaImage.encodeToData()
            ?: error("Failed to encode Skia surface to PNG bytes")
        return ImageIO.read(ByteArrayInputStream(pngData.bytes))
    }

    /** Collects all unmerged [SemanticsNode]s in the current scene. */
    fun allSemanticsNodes(): List<SemanticsNode> {
        val result = mutableListOf<SemanticsNode>()
        fun walk(node: SemanticsNode) {
            result += node
            node.children.forEach(::walk)
        }
        scene.semanticsOwners.forEach { owner ->
            walk(owner.unmergedRootSemanticsNode)
        }
        return result
    }

    /** Collects all visible text strings exposed in semantics across the current scene. */
    fun allTexts(): List<String> =
        allSemanticsNodes().flatMap { node ->
            node.config.getOrNull(SemanticsProperties.Text)?.map { it.text }.orEmpty()
        }

    /** Finds the first semantics node containing [text] (exact or substring match). */
    fun findNodeByText(text: String, substring: Boolean = false): SemanticsNode? =
        allSemanticsNodes().firstOrNull { node ->
            val nodeTexts = node.config.getOrNull(SemanticsProperties.Text)?.map { it.text }.orEmpty()
            val contentDesc = node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
            val all = nodeTexts + contentDesc
            if (substring) all.any { it.contains(text) } else all.any { it == text }
        }

    /** Performs a pointer press + release at the center of the node matching [text] and advances frames. */
    fun clickText(text: String, substring: Boolean = false): SemanticsNode {
        val node = findNodeByText(text, substring)
            ?: throw AssertionError(
                "Expected semantics node with text '$text' (substring=$substring), " +
                    "but present texts were: ${allTexts()}"
            )
        clickBounds(node.boundsInRoot)
        return node
    }

    /** Performs a pointer press + release at the center of [bounds] and settles frames. */
    fun clickBounds(bounds: Rect) {
        val center = bounds.center
        scene.sendPointerEvent(PointerEventType.Press, center)
        scene.sendPointerEvent(PointerEventType.Release, center)
        advanceFrames(8)
    }

    /**
     * Captures a PNG snapshot of the current scene, optionally with [AccessibilityRenderExtension]
     * side-by-side semantic overlay and legend.
     */
    fun snapshot(
        name: String,
        includeAccessibilityOverlay: Boolean = true,
    ): File {
        val raw = renderBitmap()
        val finalImage = if (includeAccessibilityOverlay) {
            AccessibilityRenderExtension.renderWithLegend(raw, allSemanticsNodes())
        } else {
            raw
        }
        val out = File(reportDir, "$name.png")
        out.parentFile?.mkdirs()
        ImageIO.write(finalImage, "PNG", out)
        recordedArtifacts += ReportArtifact(
            name = name,
            kind = if (includeAccessibilityOverlay) "accessibility-snapshot" else "snapshot",
            file = out,
            summary = "${finalImage.width}x${finalImage.height}px (${deviceConfig.name})",
        )
        HtmlReportWriter.writeReport(reportDir, recordedArtifacts)
        return out
    }

    /**
     * Compares the current scene against [goldenFile] (or records [goldenFile] if `record = true` or missing
     * when `recordIfMissing = true`) and writes a 3-pane `[Expected | Pixel Diff | Actual]` image.
     */
    fun verifySnapshot(
        name: String,
        goldenFile: File = File(reportDir, "${name}_golden.png"),
        maxDiffPercent: Double = 0.1,
        recordIfMissing: Boolean = true,
    ): SnapshotComparisonResult {
        val actual = renderBitmap()
        if (!goldenFile.exists() && recordIfMissing) {
            goldenFile.parentFile?.mkdirs()
            ImageIO.write(actual, "PNG", goldenFile)
        }
        require(goldenFile.exists()) { "Golden snapshot does not exist: ${goldenFile.absolutePath}" }
        val expected = ImageIO.read(goldenFile)
        val diffFile = File(reportDir, "${name}_diff_3pane.png")
        val result = SnapshotVerifier.compareAndWriteThreePane(
            expected = expected,
            actual = actual,
            outputFile = diffFile,
            maxDiffPercent = maxDiffPercent,
        )
        recordedArtifacts += ReportArtifact(
            name = "${name}_3pane",
            kind = "diff-3pane",
            file = diffFile,
            summary = "Diff: ${"%.4f".format(result.diffPercent)}% (max ${"%.4f".format(maxDiffPercent)}%)",
        )
        HtmlReportWriter.writeReport(reportDir, recordedArtifacts)
        if (!result.passed) {
            throw AssertionError(
                "Snapshot '$name' exceeded maxDiffPercent ($maxDiffPercent%): actual=${result.diffPercent}% " +
                    "(${result.changedPixels}/${result.totalPixels} pixels). See 3-pane diff: ${diffFile.absolutePath}"
            )
        }
        return result
    }

    /**
     * Steps the Compose clock through [frameCount] frames separated by [frameStepMs] milliseconds,
     * writes both an animated `.gif` and a horizontal filmstrip `.png`, and returns the captured frames
     * so tests can assert that shimmer/animated regions move while non-animated regions stay still.
     */
    fun captureGifFrames(
        name: String,
        frameCount: Int = 8,
        frameStepMs: Long = 120L,
    ): GifCaptureResult {
        require(frameCount >= 2) { "frameCount must be >= 2" }
        val frames = mutableListOf<BufferedImage>()
        val stepNanos = frameStepMs * 1_000_000L
        repeat(frameCount) { idx ->
            if (idx > 0) {
                nanoTime += stepNanos
            }
            Snapshot.sendApplyNotifications()
            scene.render(nanoTime)
            frames += renderBitmap()
        }

        // Write horizontal filmstrip PNG
        val w = frames.first().width
        val h = frames.first().height
        val strip = BufferedImage(w * frames.size, h, BufferedImage.TYPE_INT_ARGB)
        val g = strip.createGraphics()
        frames.forEachIndexed { index, frame ->
            g.drawImage(frame, index * w, 0, null)
            g.color = AwtColor(212, 232, 74, 200)
            g.drawRect(index * w, 0, w - 1, h - 1)
        }
        g.dispose()
        val stripFile = File(reportDir, "${name}_strip.png")
        ImageIO.write(strip, "PNG", stripFile)

        // Write animated GIF
        val gifFile = File(reportDir, "$name.gif")
        writeAnimatedGif(frames, frameStepMs, gifFile)

        recordedArtifacts += ReportArtifact(
            name = "${name}_strip",
            kind = "gif-filmstrip",
            file = stripFile,
            summary = "$frameCount frames @ ${frameStepMs}ms/frame (GIF: ${gifFile.name})",
        )
        HtmlReportWriter.writeReport(reportDir, recordedArtifacts)
        return GifCaptureResult(frames = frames, gifFile = gifFile, stripFile = stripFile)
    }

    /**
     * Audits every interactive clickable/toggleable semantics node in the current scene.
     *
     * For each clickable node (not excluded by [excludeLabels]), this method:
     * 1. Captures the caller's [backendStateSnapshot] and the scene's semantic fingerprint before clicking.
     * 2. Invokes the node's `OnClick` action (or clicks its center coordinates).
     * 3. Captures the caller's [backendStateSnapshot] and the scene's semantic fingerprint after clicking.
     * 4. Fails the test immediately if clicking the node caused **zero** backend state change AND **zero**
     *    UI semantic tree change (catching dead `onClick = {}` buttons and unbacked mock controls).
     */
    fun assertNoDeadClickables(
        backendStateSnapshot: () -> Any?,
        excludeLabels: Set<String> = emptySet(),
    ): List<VerifiedClickable> {
        // Click unselected controls before already-selected ones so radio/segmented controls
        // transition away from default first and then transition back when the default item is clicked.
        val targetLabels = interactiveSemanticsNodes()
            .filter { node -> !node.config.contains(SemanticsProperties.EditableText) }
            .sortedBy { node -> node.config.getOrNull(SemanticsProperties.Selected) == true }
            .map { node -> nodeLabel(node) }
            .distinct()
            .filter { label -> label !in excludeLabels }

        val verified = mutableListOf<VerifiedClickable>()
        val deadControls = mutableListOf<String>()

        for (label in targetLabels) {
            val liveNode = interactiveSemanticsNodes()
                .firstOrNull { !it.config.contains(SemanticsProperties.EditableText) && nodeLabel(it) == label }
                ?: continue
            val bounds = liveNode.boundsInRoot
            if (bounds.width <= 1f || bounds.height <= 1f) continue

            val beforeBackend = backendStateSnapshot()
            val beforeSemantics = semanticTreeFingerprint()

            val clickAction = liveNode.config.getOrNull(SemanticsActions.OnClick)?.action
            if (clickAction != null) {
                clickAction.invoke()
                advanceFrames(8)
            } else {
                clickBounds(bounds)
            }

            val afterBackend = backendStateSnapshot()
            val afterSemantics = semanticTreeFingerprint()
            val backendChanged = beforeBackend != afterBackend
            val semanticsChanged = beforeSemantics != afterSemantics

            if (!backendChanged && !semanticsChanged) {
                deadControls += "Dead clickable '$label' at bounds=${bounds.left.toInt()},${bounds.top.toInt()}..${bounds.right.toInt()},${bounds.bottom.toInt()}"
            } else {
                verified += VerifiedClickable(
                    label = label,
                    bounds = bounds,
                    mutatedBackend = backendChanged,
                    mutatedSemantics = semanticsChanged,
                )
            }
        }

        if (deadControls.isNotEmpty()) {
            throw AssertionError(
                "Detected ${deadControls.size} dead/no-op clickable UI control(s):\n" +
                    deadControls.joinToString("\n") { "  - $it" }
            )
        }
        return verified
    }

    fun interactiveSemanticsNodes(): List<SemanticsNode> =
        allSemanticsNodes().filter { node ->
            node.config.contains(SemanticsActions.OnClick) &&
                node.boundsInRoot.width > 1f &&
                node.boundsInRoot.height > 1f
        }

    private fun nodeLabel(node: SemanticsNode): String {
        val texts = node.config.getOrNull(SemanticsProperties.Text)?.map { it.text }.orEmpty()
        val desc = node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
        val stateDesc = listOfNotNull(node.config.getOrNull(SemanticsProperties.StateDescription))
        val childrenTexts = mutableListOf<String>()
        fun collectChildText(n: SemanticsNode) {
            n.config.getOrNull(SemanticsProperties.Text)?.forEach { childrenTexts += it.text }
            n.config.getOrNull(SemanticsProperties.ContentDescription)?.forEach { childrenTexts += it }
            n.children.forEach(::collectChildText)
        }
        if (texts.isEmpty() && desc.isEmpty()) {
            node.children.forEach(::collectChildText)
        }
        val combined = (texts + desc + stateDesc + childrenTexts).filter { it.isNotBlank() }.distinct()
        return if (combined.isNotEmpty()) combined.joinToString(" | ") else "node#${node.id}"
    }

    private fun semanticTreeFingerprint(): String =
        allSemanticsNodes().joinToString("\n") { node ->
            val texts = node.config.getOrNull(SemanticsProperties.Text)?.joinToString(",") { it.text }.orEmpty()
            val selected = node.config.getOrNull(SemanticsProperties.Selected)
            val toggle = node.config.getOrNull(SemanticsProperties.ToggleableState)
            val state = node.config.getOrNull(SemanticsProperties.StateDescription)
            val editable = node.config.getOrNull(SemanticsProperties.EditableText)?.text
            "${node.id}:$texts:$selected:$toggle:$state:$editable:${node.boundsInRoot}"
        }

    override fun close() {
        scene.close()
    }
}

data class VerifiedClickable(
    val label: String,
    val bounds: Rect,
    val mutatedBackend: Boolean,
    val mutatedSemantics: Boolean,
)

data class SnapshotComparisonResult(
    val passed: Boolean,
    val diffPercent: Double,
    val changedPixels: Int,
    val totalPixels: Int,
    val compositeFile: File,
)

data class GifCaptureResult(
    val frames: List<BufferedImage>,
    val gifFile: File,
    val stripFile: File,
) {
    /**
     * Computes the number of pixels within [rect] that changed across consecutive frames.
     * Used to prove that shimmer/animated elements actually animate while static titles do not.
     */
    fun countAnimatedPixelsInRect(rect: Rect): Int {
        if (frames.size < 2) return 0
        val base = frames.first()
        val x0 = rect.left.toInt().coerceIn(0, base.width - 1)
        val y0 = rect.top.toInt().coerceIn(0, base.height - 1)
        val x1 = rect.right.toInt().coerceIn(x0 + 1, base.width)
        val y1 = rect.bottom.toInt().coerceIn(y0 + 1, base.height)
        var changed = 0
        for (y in y0 until y1) {
            for (x in x0 until x1) {
                val p0 = base.getRGB(x, y)
                val anyDifferent = frames.drop(1).any { f -> f.getRGB(x, y) != p0 }
                if (anyDifferent) changed++
            }
        }
        return changed
    }
}

data class ReportArtifact(
    val name: String,
    val kind: String,
    val file: File,
    val summary: String,
)

/**
 * Cash App Paparazzi-style `AccessibilityRenderExtension`.
 *
 * Produces a 2x-wide image:
 * - Left pane: the rendered screen with color-coded semi-transparent overlays and numbered badges
 *   on every semantic node that carries accessibility text, content description, or click/toggle actions.
 * - Right pane: a dark structured legend listing each numbered badge, its role (`Button`, `RadioButton`,
 *   `Tab`, `Switch`, `Clickable`, `Text`), its bounding box, and its semantic text/actions.
 */
object AccessibilityRenderExtension {
    private val palette = listOf(
        AwtColor(212, 232, 74),   // Lime
        AwtColor(56, 189, 248),   // Sky
        AwtColor(251, 146, 60),   // Orange
        AwtColor(74, 222, 128),   // Green
        AwtColor(167, 139, 250),  // Purple
        AwtColor(244, 114, 182),  // Pink
        AwtColor(250, 204, 21),   // Gold
    )

    fun renderWithLegend(
        screenImage: BufferedImage,
        nodes: List<SemanticsNode>,
    ): BufferedImage {
        val relevant = nodes.filter { node ->
            val hasClick = node.config.contains(SemanticsActions.OnClick)
            val hasText = !node.config.getOrNull(SemanticsProperties.Text).isNullOrEmpty()
            val hasDesc = !node.config.getOrNull(SemanticsProperties.ContentDescription).isNullOrEmpty()
            (hasClick || hasText || hasDesc) &&
                node.boundsInRoot.width > 2f &&
                node.boundsInRoot.height > 2f
        }

        val w = screenImage.width
        val h = screenImage.height
        val out = BufferedImage(w * 2, h, BufferedImage.TYPE_INT_ARGB)
        val g = out.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

        // Left half: original screen + bounding boxes
        g.drawImage(screenImage, 0, 0, null)

        // Right half: dark legend background
        g.color = AwtColor(17, 18, 14)
        g.fillRect(w, 0, w, h)
        g.color = AwtColor(212, 232, 74)
        g.font = Font("SansSerif", Font.BOLD, 20)
        g.drawString("PAPARAZZI ACCESSIBILITY & SEMANTICS AUDIT (${relevant.size} nodes)", w + 24, 38)

        g.font = Font("Monospaced", Font.PLAIN, 14)
        var legendY = 70

        relevant.forEachIndexed { idx, node ->
            val color = palette[idx % palette.size]
            val b = node.boundsInRoot
            val rx = b.left.toInt().coerceIn(0, w - 1)
            val ry = b.top.toInt().coerceIn(0, h - 1)
            val rw = b.width.toInt().coerceAtLeast(2).coerceAtMost(w - rx)
            val rh = b.height.toInt().coerceAtLeast(2).coerceAtMost(h - ry)

            val isInteractive = node.config.contains(SemanticsActions.OnClick)
            g.color = AwtColor(color.red, color.green, color.blue, if (isInteractive) 52 else 22)
            g.fillRect(rx, ry, rw, rh)
            g.color = AwtColor(color.red, color.green, color.blue, if (isInteractive) 230 else 130)
            g.stroke = BasicStroke(if (isInteractive) 2.5f else 1.2f)
            g.drawRect(rx, ry, rw, rh)

            // Numbered badge on top-left corner
            val badgeText = "${idx + 1}"
            g.color = color
            g.fillRoundRect(rx, ry, 24, 18, 6, 6)
            g.color = AwtColor(17, 18, 14)
            g.font = Font("SansSerif", Font.BOLD, 11)
            g.drawString(badgeText, rx + 5, ry + 13)

            // Legend row on right half
            if (legendY < h - 24) {
                val role = node.config.getOrNull(SemanticsProperties.Role)?.toString()
                    ?: if (isInteractive) "Clickable" else "Text"
                val texts = node.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }.orEmpty()
                val desc = node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ").orEmpty()
                val label = listOf(texts, desc).filter { it.isNotBlank() }.joinToString(" / ").take(68)
                g.color = color
                g.fillRoundRect(w + 24, legendY - 13, 28, 18, 6, 6)
                g.color = AwtColor(17, 18, 14)
                g.font = Font("SansSerif", Font.BOLD, 11)
                g.drawString(badgeText, w + 30, legendY)

                g.color = if (isInteractive) AwtColor(242, 242, 232) else AwtColor(168, 170, 156)
                g.font = Font("Monospaced", if (isInteractive) Font.BOLD else Font.PLAIN, 13)
                g.drawString("[$role] $label (${b.width.toInt()}x${b.height.toInt()})", w + 60, legendY)
                legendY += 22
            }
        }

        g.dispose()
        return out
    }
}

/**
 * Cash App Paparazzi-style 3-pane `[Expected | Pixel Diff | Actual]` image comparator.
 */
object SnapshotVerifier {
    fun compareAndWriteThreePane(
        expected: BufferedImage,
        actual: BufferedImage,
        outputFile: File,
        maxDiffPercent: Double = 0.1,
    ): SnapshotComparisonResult {
        val w = maxOf(expected.width, actual.width)
        val h = maxOf(expected.height, actual.height)
        val diffImg = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)

        var changedPixels = 0
        val totalPixels = w * h

        for (y in 0 until h) {
            for (x in 0 until w) {
                val eRgb = if (x < expected.width && y < expected.height) expected.getRGB(x, y) else 0
                val aRgb = if (x < actual.width && y < actual.height) actual.getRGB(x, y) else 0
                if (eRgb != aRgb) {
                    val dr = abs(((eRgb shr 16) and 0xFF) - ((aRgb shr 16) and 0xFF))
                    val dg = abs(((eRgb shr 8) and 0xFF) - ((aRgb shr 8) and 0xFF))
                    val db = abs((eRgb and 0xFF) - (aRgb and 0xFF))
                    if (dr + dg + db > 6) {
                        changedPixels++
                        // Highlight changed pixel in bright magenta/red
                        diffImg.setRGB(x, y, AwtColor(255, 59, 92, 255).rgb)
                    } else {
                        diffImg.setRGB(x, y, dimPixel(aRgb))
                    }
                } else {
                    diffImg.setRGB(x, y, dimPixel(aRgb))
                }
            }
        }

        val headerH = 44
        val composite = BufferedImage(w * 3, h + headerH, BufferedImage.TYPE_INT_ARGB)
        val g = composite.createGraphics()
        g.color = AwtColor(17, 18, 14)
        g.fillRect(0, 0, composite.width, composite.height)
        g.color = AwtColor(212, 232, 74)
        g.font = Font("SansSerif", Font.BOLD, 18)
        val diffPct = (changedPixels.toDouble() / totalPixels.toDouble()) * 100.0
        g.drawString("EXPECTED (GOLDEN)", 20, 28)
        g.drawString("PIXEL DIFF (${"%.4f".format(diffPct)}% / $changedPixels px)", w + 20, 28)
        g.drawString("ACTUAL RENDER", w * 2 + 20, 28)

        g.drawImage(expected, 0, headerH, null)
        g.drawImage(diffImg, w, headerH, null)
        g.drawImage(actual, w * 2, headerH, null)
        g.dispose()

        outputFile.parentFile?.mkdirs()
        ImageIO.write(composite, "PNG", outputFile)

        return SnapshotComparisonResult(
            passed = diffPct <= maxDiffPercent,
            diffPercent = diffPct,
            changedPixels = changedPixels,
            totalPixels = totalPixels,
            compositeFile = outputFile,
        )
    }

    private fun dimPixel(argb: Int): Int {
        val r = ((argb shr 16) and 0xFF) / 3
        val g = ((argb shr 8) and 0xFF) / 3
        val b = (argb and 0xFF) / 3
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
}

/** Writes an HTML gallery report (`index.html`) linking all snapshots, 3-pane diffs, and filmstrips. */
object HtmlReportWriter {
    fun writeReport(reportDir: File, artifacts: List<ReportArtifact>): File {
        reportDir.mkdirs()
        val indexFile = File(reportDir, "index.html")
        val cards = artifacts.joinToString("\n") { a ->
            """
            <div class="card">
              <div class="meta">
                <span class="badge">${a.kind}</span>
                <strong>${a.name}</strong>
                <span>${a.summary}</span>
              </div>
              <a href="${a.file.name}" target="_blank">
                <img src="${a.file.name}" alt="${a.name}" />
              </a>
            </div>
            """.trimIndent()
        }
        indexFile.writeText(
            """
            <!DOCTYPE html>
            <html lang="en">
            <head>
              <meta charset="utf-8" />
              <title>Paparazzi KMP UI Verification Report</title>
              <style>
                body { background: #141510; color: #F2F2E8; font-family: -apple-system, sans-serif; margin: 24px; }
                h1 { color: #D4E84A; font-size: 22px; }
                .grid { display: flex; flex-direction: column; gap: 24px; }
                .card { background: #1C1E17; border: 1px solid rgba(242,242,232,0.14); border-radius: 14px; padding: 16px; }
                .meta { display: flex; gap: 12px; align-items: center; margin-bottom: 12px; font-size: 14px; }
                .badge { background: rgba(212,232,74,0.16); color: #D4E84A; padding: 3px 8px; border-radius: 6px; font-size: 12px; }
                img { max-width: 100%; border-radius: 8px; border: 1px solid rgba(255,255,255,0.08); }
              </style>
            </head>
            <body>
              <h1>Paparazzi KMP Snapshot, Accessibility &amp; Interactivity Report</h1>
              <div class="grid">$cards</div>
            </body>
            </html>
            """.trimIndent()
        )
        return indexFile
    }
}

private fun writeAnimatedGif(frames: List<BufferedImage>, delayMs: Long, outputFile: File) {
    outputFile.parentFile?.mkdirs()
    val writers = ImageIO.getImageWritersBySuffix("gif")
    if (!writers.hasNext()) return
    val writer = writers.next()
    FileImageOutputStream(outputFile).use { ios ->
        writer.output = ios
        writer.prepareWriteSequence(null)
        val delayCs = (delayMs / 10L).coerceAtLeast(1L).toString()
        for (frame in frames) {
            val writeParam = writer.defaultWriteParam
            val typeSpecifier = ImageTypeSpecifier.createFromBufferedImageType(BufferedImage.TYPE_INT_ARGB)
            val metadata: IIOMetadata = writer.getDefaultImageMetadata(typeSpecifier, writeParam)
            val metaFormatName = metadata.nativeMetadataFormatName
            val root = metadata.getAsTree(metaFormatName) as IIOMetadataNode
            val gce = getNode(root, "GraphicControlExtension")
            gce.setAttribute("disposalMethod", "none")
            gce.setAttribute("userInputFlag", "FALSE")
            gce.setAttribute("transparentColorFlag", "FALSE")
            gce.setAttribute("delayTime", delayCs)
            gce.setAttribute("transparentColorIndex", "0")
            val appExtensions = getNode(root, "ApplicationExtensions")
            val child = IIOMetadataNode("ApplicationExtension")
            child.setAttribute("applicationID", "NETSCAPE")
            child.setAttribute("authenticationCode", "2.0")
            child.userObject = byteArrayOf(0x1, 0x0, 0x0)
            appExtensions.appendChild(child)
            metadata.setFromTree(metaFormatName, root)
            writer.writeToSequence(IIOImage(frame, null, metadata), writeParam)
        }
        writer.endWriteSequence()
    }
    writer.dispose()
}

private fun getNode(rootNode: IIOMetadataNode, nodeName: String): IIOMetadataNode {
    for (i in 0 until rootNode.length) {
        if (rootNode.item(i).nodeName.equals(nodeName, ignoreCase = true)) {
            return rootNode.item(i) as IIOMetadataNode
        }
    }
    val node = IIOMetadataNode(nodeName)
    rootNode.appendChild(node)
    return node
}
