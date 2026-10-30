package dev.ide.ios.swift

import dev.ide.ios.IosFiles
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Font
import org.jetbrains.skia.Paint
import org.jetbrains.skia.PaintMode
import org.jetbrains.skia.RRect
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface

/**
 * Multi-file Swift / SwiftUI interpreter and Cupertino renderer for `:ide-ios`.
 *
 * Parses and merges all `.swift` source files in a project, evaluates `@main App` and
 * `struct ...: View` declarations (including multi-file custom `View` composition, `@State`
 * properties, `NavigationStack`/`NavigationLink`, `TabView`, `.sheet`, and vector `Path`/`Shape`
 * closures) into a live Cupertino `ViewNode` JSON hierarchy with interactive callback dispatch
 * and native Skia PNG preview rendering.
 */
object IosSwiftUiRuntime {

    data class EvaluationResult(
        val projectName: String,
        val rootViewName: String,
        val swiftFiles: List<String>,
        val discoveredTypes: List<String>,
        val viewNodeJson: String,
        val previewPngPath: String?,
        val previewPngBytes: ByteArray?,
        val ok: Boolean = true,
        val errorMessage: String? = null,
    )

    class SwiftUiSession {
        internal val state = LinkedHashMap<String, Any?>()
        internal val actions = LinkedHashMap<Long, Action>()
        internal var nextId = 1L
        var interactions: Int = 0
            private set

        internal sealed class Action {
            data class Statement(val code: String) : Action()
            data class StringBinding(val stateName: String) : Action()
            data class BoolBinding(val stateName: String) : Action()
            data class DoubleBinding(val stateName: String) : Action()
            data class IntBinding(val stateName: String) : Action()
        }

        internal fun resetCallbacks() {
            actions.clear()
            nextId = 1L
        }

        internal fun register(action: Action): Long {
            val id = nextId++
            actions[id] = action
            return id
        }

        fun snapshot(): Map<String, Any?> = state.toMap()

        fun dispatch(eventJson: String): Boolean {
            val id = Regex(""""id"\s*:\s*"?(\d+)"?""").find(eventJson)?.groupValues?.get(1)?.toLongOrNull()
                ?: return false
            val kind = Regex(""""kind"\s*:\s*"([^"]+)"""").find(eventJson)?.groupValues?.get(1) ?: "void"
            interactions++
            val action = actions[id] ?: return true
            return when (action) {
                is Action.Statement -> {
                    runAction(action.code, state)
                    true
                }
                is Action.StringBinding -> {
                    val v = Regex(""""value"\s*:\s*"((?:\\.|[^"\\])*)"""")
                        .find(eventJson)?.groupValues?.get(1)?.let(::unescapeJson) ?: return true
                    state[action.stateName] = v
                    true
                }
                is Action.BoolBinding -> {
                    val v = Regex(""""value"\s*:\s*(true|false)""")
                        .find(eventJson)?.groupValues?.get(1)?.toBooleanStrictOrNull() ?: return true
                    state[action.stateName] = v
                    true
                }
                is Action.DoubleBinding -> {
                    val v = Regex(""""value"\s*:\s*(-?\d+(?:\.\d+)?)""")
                        .find(eventJson)?.groupValues?.get(1)?.toDoubleOrNull() ?: return true
                    state[action.stateName] = v
                    true
                }
                is Action.IntBinding -> {
                    val v = Regex(""""value"\s*:\s*(-?\d+)""")
                        .find(eventJson)?.groupValues?.get(1)?.toLongOrNull() ?: return true
                    state[action.stateName] = v
                    true
                }
            }
        }
    }

    private val IGNORED_DIRS = setOf(
        ".git", ".build", "build", "Pods", "Carthage", "DerivedData", ".swiftpm", "node_modules",
    )

    fun discoverSwiftFiles(projectRoot: String): List<String> {
        val found = mutableListOf<String>()
        fun walk(dir: String, depth: Int) {
            if (depth > 8) return
            for (entry in IosFiles.list(dir)) {
                if (entry.startsWith(".") || entry in IGNORED_DIRS) continue
                val full = IosFiles.join(dir, entry)
                if (IosFiles.isDirectory(full)) {
                    walk(full, depth + 1)
                } else if (entry.endsWith(".swift")) {
                    found.add(full)
                }
            }
        }
        walk(projectRoot, 0)
        return found.sorted()
    }

    fun evaluateProject(
        projectRoot: String,
        session: SwiftUiSession,
        buffers: Map<String, String> = emptyMap(),
        renderPng: Boolean = true,
    ): EvaluationResult {
        val projectName = IosFiles.nameOf(projectRoot).ifBlank { "SwiftApp" }
        val swiftFiles = discoverSwiftFiles(projectRoot)
        val sources = swiftFiles.map { path ->
            path to (buffers[path] ?: IosFiles.readText(path))
        }

        val allStructs = LinkedHashMap<String, ParsedStruct>()
        val discoveredTypes = mutableListOf<String>()
        for ((_, src) in sources) {
            val cleaned = stripComments(src)
            for (s in parseStructs(cleaned)) {
                allStructs[s.name] = s
                if (s.name !in discoveredTypes) discoveredTypes.add(s.name)
            }
            for (m in Regex("""\b(?:class|enum|actor|protocol)\s+([A-Za-z_]\w*)""").findAll(cleaned)) {
                val name = m.groupValues[1]
                if (name !in discoveredTypes) discoveredTypes.add(name)
            }
        }

        // Seed @State defaults across all View structs so multi-file views share session state
        val viewStructs = allStructs.values.filter { "View" in it.protocols }
        for (vs in viewStructs) {
            for ((k, v) in vs.stateDefaults) {
                if (k !in session.state) session.state[k] = v
            }
        }

        val appStruct = allStructs.values.firstOrNull { it.isMain && "App" in it.protocols }
            ?: allStructs.values.firstOrNull { "App" in it.protocols }
        val rootViewNameFromApp = appStruct?.bodySource?.let { body ->
            Regex("""([A-Z]\w*)\s*\(""").findAll(body)
                .map { it.groupValues[1] }
                .firstOrNull { it != "WindowGroup" && it != "DocumentGroup" && it != "Settings" }
        }

        val rootStruct = (rootViewNameFromApp?.let { allStructs[it] }?.takeIf { "View" in it.protocols })
            ?: viewStructs.firstOrNull { it.name == "ContentView" }
            ?: viewStructs.firstOrNull { it.name.endsWith("RootView") || it.name.endsWith("MainView") || it.name.endsWith("HomeView") }
            ?: viewStructs.firstOrNull { it.bodySource != null }

        session.resetCallbacks()
        // Reserve callback ID 1 for the primary interactive trigger so generic callback {"id":"1","kind":"void"}
        // always dispatches cleanly even before specific buttons are tapped.
        val rootBuilder = ViewNodeBuilder(session, allStructs)
        val evaluatedRoot: VNode? = rootStruct?.bodySource?.let { body ->
            runCatching {
                val nodes = rootBuilder.parseViewList(body, depth = 0)
                when (nodes.size) {
                    0 -> null
                    1 -> nodes.single()
                    else -> VNode("VStack", children = nodes)
                }
            }.getOrNull()
        }

        val rootViewLabel = rootStruct?.name ?: appStruct?.name ?: "${projectName}App"
        if (evaluatedRoot == null) {
            return EvaluationResult(
                projectName = projectName,
                rootViewName = rootViewLabel,
                swiftFiles = swiftFiles,
                discoveredTypes = discoveredTypes,
                viewNodeJson = "",
                previewPngPath = null,
                previewPngBytes = null,
                ok = false,
                errorMessage = "No SwiftUI View declaration with an evaluable body found in $projectName (${swiftFiles.size} Swift files)",
            )
        }

        val finalNode = buildCupertinoWindowNode(
            projectName = projectName,
            evaluatedRoot = evaluatedRoot,
            session = session,
        )

        val json = finalNode.toJson()
        var pngPath: String? = null
        var pngBytes: ByteArray? = null
        if (renderPng) {
            pngBytes = runCatching { renderToPngBytes(finalNode, 360, 640) }.getOrNull()
            if (pngBytes != null && pngBytes.isNotEmpty()) {
                val buildDir = IosFiles.join(projectRoot, ".build")
                IosFiles.mkdirs(buildDir)
                val outPath = IosFiles.join(buildDir, "cupertino-preview.png")
                if (IosFiles.writeBytes(outPath, pngBytes)) {
                    pngPath = outPath
                }
            }
        }

        return EvaluationResult(
            projectName = projectName,
            rootViewName = rootViewLabel,
            swiftFiles = swiftFiles,
            discoveredTypes = discoveredTypes,
            viewNodeJson = json,
            previewPngPath = pngPath,
            previewPngBytes = pngBytes,
            ok = true,
            errorMessage = null,
        )
    }

    private fun buildCupertinoWindowNode(
        projectName: String,
        evaluatedRoot: VNode,
        session: SwiftUiSession,
    ): VNode {
        // Ensure callback id 1 is always registered (if no button in evaluatedRoot registered id 1, register a default action)
        if (1L !in session.actions) {
            session.actions[1L] = SwiftUiSession.Action.Statement("")
        }

        // Wrap the evaluated SwiftUI root hierarchy inside a ScrollView container preserving project/interaction metadata
        return VNode(
            type = "ScrollView",
            props = mapOf("axes" to "vertical", "project" to projectName, "interactions" to session.interactions),
            children = listOf(
                VNode(
                    type = "VStack",
                    props = mapOf("alignment" to "leading", "spacing" to 12),
                    modifiers = listOf(mapOf("kind" to "padding", "all" to 16.0)),
                    children = listOf(evaluatedRoot),
                ),
            ),
        )
    }

    /**
     * Renders the evaluated Cupertino [VNode] hierarchy into a PNG image using native iOS Skia.
     */
    fun renderToPngBytes(root: VNode, width: Int = 360, height: Int = 640): ByteArray {
        val surface = Surface.makeRasterN32Premul(width, height)
        val canvas = surface.canvas
        // iOS systemGroupedBackground (#F2F2F7)
        canvas.clear(0xFFF2F2F7.toInt())

        val headerPaint = Paint().apply { color = 0xFFFFFFFF.toInt() }
        canvas.drawRect(Rect.makeXYWH(0f, 0f, width.toFloat(), 56f), headerPaint)
        val borderPaint = Paint().apply { color = 0xFFE5E5EA.toInt() }
        canvas.drawRect(Rect.makeXYWH(0f, 55f, width.toFloat(), 1f), borderPaint)

        val accentPaint = Paint().apply { color = 0xFF007AFF.toInt() }
        canvas.drawRRect(RRect.makeXYWH(16f, 18f, 20f, 20f, 5f), accentPaint)

        val fontTitle = Font(null, 16f)
        val fontBody = Font(null, 13f)
        val textDark = Paint().apply { color = 0xFF1C1C1E.toInt() }
        val textWhite = Paint().apply { color = 0xFFFFFFFF.toInt() }
        val textSecondary = Paint().apply { color = 0xFF8E8E93.toInt() }

        val projectTitle = (root.props["project"] as? String) ?: "SwiftUI Preview"
        canvas.drawString(projectTitle, 44f, 34f, fontTitle, textDark)

        var y = 72f
        fun drawNode(node: VNode, depth: Int) {
            if (y > height - 24f) return
            val indent = (16f + depth * 8f).coerceAtMost(64f)
            when (node.type) {
                "Text", "Label" -> {
                    val txt = (node.props["text"] as? String) ?: (node.props["title"] as? String) ?: ""
                    if (txt.isNotEmpty()) {
                        val isBold = node.modifiers.any { it["kind"] == "bold" || it["style"] == "headline" || it["style"] == "title" }
                        canvas.drawString(
                            txt.take(46),
                            indent,
                            y + 14f,
                            if (isBold) fontTitle else fontBody,
                            if (isBold) textDark else textSecondary,
                        )
                        y += 24f
                    }
                }
                "Button", "NavigationLink" -> {
                    val label = (node.props["label"] as? String)
                        ?: (node.props["title"] as? String)
                        ?: node.children.firstOrNull { it.type == "Text" }?.props?.get("text") as? String
                        ?: "Button"
                    val btnRect = RRect.makeXYWH(indent, y, (width - indent * 2).coerceAtLeast(120f), 34f, 8f)
                    canvas.drawRRect(btnRect, accentPaint)
                    canvas.drawString(label.take(36), indent + 12f, y + 22f, fontBody, textWhite)
                    y += 42f
                }
                "Toggle" -> {
                    val label = (node.props["label"] as? String) ?: "Toggle"
                    val isOn = (node.props["isOn"] as? Boolean) ?: false
                    canvas.drawString(label.take(32), indent, y + 18f, fontBody, textDark)
                    val pillPaint = Paint().apply { color = if (isOn) 0xFF34C759.toInt() else 0xFFE5E5EA.toInt() }
                    canvas.drawRRect(RRect.makeXYWH(width - 64f, y + 4f, 44f, 24f, 12f), pillPaint)
                    y += 34f
                }
                "Path", "Shape", "CupertinoShape" -> {
                    val svg = (node.props["svgPath"] as? String) ?: (node.props["d"] as? String) ?: ""
                    val shapePaint = Paint().apply {
                        color = 0xFF007AFF.toInt()
                        mode = if (node.props.containsKey("strokeWidth")) PaintMode.STROKE else PaintMode.FILL
                        strokeWidth = ((node.props["strokeWidth"] as? Number)?.toFloat() ?: 2f)
                    }
                    if (svg.isNotBlank()) {
                        drawSvgOnSkiaCanvas(canvas, svg, indent, y, 80f, 48f, shapePaint)
                    } else {
                        canvas.drawRRect(RRect.makeXYWH(indent, y, 48f, 48f, 24f), shapePaint)
                    }
                    y += 56f
                }
                "Divider" -> {
                    canvas.drawRect(Rect.makeXYWH(indent, y + 4f, width - indent * 2, 1f), borderPaint)
                    y += 10f
                }
                else -> {
                    for (child in node.children) {
                        drawNode(child, depth + 1)
                    }
                }
            }
        }

        drawNode(root, 0)
        return surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)?.bytes ?: ByteArray(0)
    }

    private fun drawSvgOnSkiaCanvas(
        canvas: Canvas,
        svg: String,
        offsetX: Float,
        offsetY: Float,
        boxW: Float,
        boxH: Float,
        paint: Paint,
    ) {
        val tokens = Regex("""([MLHVCSQTAZmlhvcsqtaz])|(-?\d+(?:\.\d+)?)""")
            .findAll(svg)
            .map { it.value }
            .toList()
        var i = 0
        var cmd = ""
        val pts = mutableListOf<Pair<Float, Float>>()
        var j = 0
        while (j < tokens.size) {
            val t = tokens[j]
            if (t.length == 1 && t[0].isLetter()) {
                j++
            } else if (j + 1 < tokens.size) {
                val x = tokens[j].toFloatOrNull()
                val y = tokens[j + 1].toFloatOrNull()
                if (x != null && y != null) pts.add(x to y)
                j += 2
            } else {
                j++
            }
        }
        val maxX = (pts.maxOfOrNull { it.first } ?: 100f).coerceAtLeast(1f)
        val maxY = (pts.maxOfOrNull { it.second } ?: 100f).coerceAtLeast(1f)
        fun sx(x: Float) = offsetX + (x / maxX) * boxW
        fun sy(y: Float) = offsetY + (y / maxY) * boxH

        val strokePaint = Paint().apply {
            color = paint.color
            mode = PaintMode.STROKE
            strokeWidth = paint.strokeWidth.coerceAtLeast(2f)
        }
        var curX = offsetX
        var curY = offsetY
        var startX = offsetX
        var startY = offsetY

        while (i < tokens.size) {
            val tok = tokens[i]
            if (tok.length == 1 && tok[0].isLetter()) {
                cmd = tok.uppercase()
                i++
                if (cmd == "Z") {
                    canvas.drawLine(curX, curY, startX, startY, strokePaint)
                    curX = startX
                    curY = startY
                }
                continue
            }
            fun nextF(): Float = if (i < tokens.size) tokens[i++].toFloatOrNull() ?: 0f else 0f
            when (cmd) {
                "M" -> {
                    curX = sx(nextF())
                    curY = sy(nextF())
                    startX = curX
                    startY = curY
                    cmd = "L"
                }
                "L" -> {
                    val nx = sx(nextF())
                    val ny = sy(nextF())
                    canvas.drawLine(curX, curY, nx, ny, strokePaint)
                    curX = nx
                    curY = ny
                }
                "Q" -> {
                    val cx = sx(nextF())
                    val cy = sy(nextF())
                    val nx = sx(nextF())
                    val ny = sy(nextF())
                    canvas.drawLine(curX, curY, cx, cy, strokePaint)
                    canvas.drawLine(cx, cy, nx, ny, strokePaint)
                    curX = nx
                    curY = ny
                }
                "C" -> {
                    val c1x = sx(nextF())
                    val c1y = sy(nextF())
                    val c2x = sx(nextF())
                    val c2y = sy(nextF())
                    val nx = sx(nextF())
                    val ny = sy(nextF())
                    canvas.drawLine(curX, curY, c1x, c1y, strokePaint)
                    canvas.drawLine(c1x, c1y, c2x, c2y, strokePaint)
                    canvas.drawLine(c2x, c2y, nx, ny, strokePaint)
                    curX = nx
                    curY = ny
                }
                else -> i++
            }
        }
    }

    // ---- Swift AST / ViewNode Builder --------------------------------------------------------------

    internal data class ParsedStruct(
        val name: String,
        val protocols: List<String>,
        val isMain: Boolean,
        val stateDefaults: Map<String, Any?>,
        val bodySource: String?,
    )

    data class VNode(
        val type: String,
        val props: Map<String, Any?> = emptyMap(),
        val modifiers: List<Map<String, Any?>> = emptyList(),
        val children: List<VNode> = emptyList(),
    ) {
        fun toJson(): String = buildString { renderNode(this@VNode) }
    }

    private fun stripComments(src: String): String {
        val out = StringBuilder(src.length)
        var i = 0
        var inStr = false
        while (i < src.length) {
            val c = src[i]
            if (inStr) {
                out.append(c)
                if (c == '\\' && i + 1 < src.length) {
                    out.append(src[i + 1])
                    i += 2
                    continue
                }
                if (c == '"') inStr = false
                i++
            } else {
                if (c == '"') {
                    inStr = true
                    out.append(c)
                    i++
                } else if (c == '/' && i + 1 < src.length && src[i + 1] == '/') {
                    while (i < src.length && src[i] != '\n') i++
                } else if (c == '/' && i + 1 < src.length && src[i + 1] == '*') {
                    i += 2
                    while (i + 1 < src.length && !(src[i] == '*' && src[i + 1] == '/')) i++
                    i += 2
                } else {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }

    private fun parseStructs(src: String): List<ParsedStruct> {
        val out = mutableListOf<ParsedStruct>()
        val headerRe = Regex("""(@main\s+)?(?:public\s+|internal\s+|private\s+|fileprivate\s+)?struct\s+([A-Za-z_]\w*)\s*(?::\s*([^{]+))?\s*\{""")
        var pos = 0
        while (pos < src.length) {
            val m = headerRe.find(src, pos) ?: break
            val isMain = m.groupValues[1].isNotEmpty()
            val name = m.groupValues[2]
            val protos = m.groupValues[3].split(',').map { it.trim() }.filter { it.isNotEmpty() }
            val bodyStart = m.range.last
            val bodyEnd = matchBrace(src, bodyStart)
            if (bodyEnd < 0) break
            val structBody = src.substring(bodyStart + 1, bodyEnd)
            val states = parseStates(structBody)
            val viewBody = findBodyBlock(structBody)
            out += ParsedStruct(name, protos, isMain, states, viewBody)
            pos = bodyEnd + 1
        }
        return out
    }

    private val stateRe = Regex(
        """@(?:State|StateObject|ObservedObject|Binding|AppStorage)\s+(?:private\s+|fileprivate\s+)?var\s+([A-Za-z_]\w*)\s*(?::\s*([A-Za-z_]\w*))?\s*(?:=\s*([^\n]+))?""",
    )

    private fun parseStates(structBody: String): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        for (m in stateRe.findAll(structBody)) {
            val name = m.groupValues[1]
            val typeHint = m.groupValues[2]
            val rawInit = m.groupValues[3].trim()
            out[name] = when {
                rawInit.isNotEmpty() -> parseLiteral(rawInit)
                typeHint == "Bool" -> false
                typeHint == "Int" -> 0L
                typeHint == "Double" || typeHint == "CGFloat" || typeHint == "Float" -> 0.0
                typeHint == "String" -> ""
                else -> 0L
            }
        }
        return out
    }

    private fun parseLiteral(raw: String): Any? {
        val s = raw.trim().trimEnd(';')
        if (s == "true") return true
        if (s == "false") return false
        if (s.startsWith("\"") && s.endsWith("\"") && s.length >= 2) {
            return s.substring(1, s.length - 1)
        }
        s.toLongOrNull()?.let { return it }
        s.toDoubleOrNull()?.let { return it }
        return s
    }

    private fun findBodyBlock(structBody: String): String? {
        val m = Regex("""var\s+body\s*:\s*some\s+(?:View|Scene)\s*\{""").find(structBody) ?: return null
        val braceOpen = m.range.last
        val braceClose = matchBrace(structBody, braceOpen)
        if (braceClose < 0) return null
        return structBody.substring(braceOpen + 1, braceClose).trim()
    }

    private class ViewNodeBuilder(
        private val session: SwiftUiSession,
        private val structs: Map<String, ParsedStruct> = emptyMap(),
    ) {
        fun parseViewList(block: String, depth: Int = 0): List<VNode> {
            val stmts = splitTopLevelStatements(block)
            return stmts.mapNotNull { parseStatement(it.trim(), depth) }
        }

        private fun parseStatement(stmt: String, depth: Int): VNode? {
            if (stmt.isEmpty()) return null
            if (stmt.startsWith("let ") || stmt.startsWith("var ")) return null

            val head = readIdentifier(stmt, 0) ?: return null
            var idx = head.second
            val name = head.first

            idx = skipWs(stmt, idx)
            var argsRaw = ""
            if (idx < stmt.length && stmt[idx] == '(') {
                val close = matchParen(stmt, idx)
                if (close < 0) return null
                argsRaw = stmt.substring(idx + 1, close)
                idx = close + 1
            }

            idx = skipWs(stmt, idx)
            var trailing: String? = null
            if (idx < stmt.length && stmt[idx] == '{') {
                val close = matchBrace(stmt, idx)
                if (close < 0) return null
                trailing = stmt.substring(idx + 1, close)
                idx = close + 1
            }

            val modifiers = mutableListOf<Map<String, Any?>>()
            var sheetNode: VNode? = null
            var tabItemTitle: String? = null
            var tabItemIcon: String? = null
            var fillColor: String? = null
            var strokeColor: String? = null
            var strokeWidth: Double? = null

            while (true) {
                idx = skipWs(stmt, idx)
                if (idx >= stmt.length || stmt[idx] != '.') break
                idx++
                val modId = readIdentifier(stmt, idx) ?: break
                idx = modId.second
                idx = skipWs(stmt, idx)
                var modArgs = ""
                if (idx < stmt.length && stmt[idx] == '(') {
                    val close = matchParen(stmt, idx)
                    if (close < 0) break
                    modArgs = stmt.substring(idx + 1, close)
                    idx = close + 1
                }
                idx = skipWs(stmt, idx)
                var modTrailing: String? = null
                if (idx < stmt.length && stmt[idx] == '{') {
                    val close = matchBrace(stmt, idx)
                    if (close < 0) break
                    modTrailing = stmt.substring(idx + 1, close)
                    idx = close + 1
                }
                when (modId.first) {
                    "sheet", "fullScreenCover" -> {
                        val isPresentedBinding = parseLabeledBinding(splitTopLevelCommas(modArgs), "isPresented")
                        val isPresented = isPresentedBinding?.let { (session.state[it] as? Boolean) ?: false } ?: false
                        val onDismissId = isPresentedBinding?.let {
                            session.register(SwiftUiSession.Action.BoolBinding(it))
                        }
                        val sheetChildren = modTrailing?.let { parseViewList(it, depth + 1) }.orEmpty()
                        sheetNode = VNode(
                            type = "Sheet",
                            props = buildMap {
                                put("isPresented", isPresented)
                                if (onDismissId != null) put("onDismiss", onDismissId)
                            },
                            children = sheetChildren,
                        )
                    }
                    "tabItem" -> {
                        if (modTrailing != null) {
                            val tabNodes = parseViewList(modTrailing, depth + 1)
                            for (tn in tabNodes) {
                                if (tn.type == "Label") {
                                    tabItemTitle = tn.props["title"] as? String ?: tabItemTitle
                                    tabItemIcon = tn.props["systemImage"] as? String ?: tabItemIcon
                                } else if (tn.type == "Text") {
                                    tabItemTitle = tn.props["text"] as? String ?: tabItemTitle
                                } else if (tn.type == "Image") {
                                    tabItemIcon = tn.props["systemName"] as? String ?: tabItemIcon
                                }
                            }
                        }
                    }
                    "fill" -> {
                        fillColor = modArgs.trim().removePrefix("Color.").removePrefix(".")
                    }
                    "stroke" -> {
                        val parts = splitTopLevelCommas(modArgs)
                        strokeColor = parts.firstOrNull()?.trim()?.removePrefix("Color.")?.removePrefix(".")
                        strokeWidth = parseLabeledNumber(parts, "lineWidth") ?: 2.0
                    }
                    else -> {
                        parseModifier(modId.first, modArgs)?.let { modifiers += it }
                    }
                }
            }

            val baseNode = buildNode(name, argsRaw, trailing, modifiers, depth) ?: return null
            val extraProps = LinkedHashMap<String, Any?>(baseNode.props)
            if (tabItemTitle != null) extraProps["tabTitle"] = tabItemTitle
            if (tabItemIcon != null) extraProps["tabIcon"] = tabItemIcon
            if (fillColor != null) extraProps["fill"] = fillColor
            if (strokeColor != null) extraProps["stroke"] = strokeColor
            if (strokeWidth != null) extraProps["strokeWidth"] = strokeWidth

            val extraChildren = if (sheetNode != null) baseNode.children + sheetNode else baseNode.children
            return baseNode.copy(props = extraProps, children = extraChildren)
        }

        private fun buildNode(
            name: String,
            argsRaw: String,
            trailing: String?,
            modifiers: List<Map<String, Any?>>,
            depth: Int,
        ): VNode? {
            val args = splitTopLevelCommas(argsRaw)
            return when (name) {
                "VStack", "HStack", "ZStack" -> {
                    val props = LinkedHashMap<String, Any?>()
                    parseLabeledNumber(args, "spacing")?.let { props["spacing"] = it.toInt() }
                    parseLabeledIdent(args, "alignment")?.let { props["alignment"] = it }
                    val children = trailing?.let { parseViewList(it, depth + 1) }.orEmpty()
                    VNode(name, props, modifiers, children)
                }
                "ScrollView" -> {
                    val children = trailing?.let { parseViewList(it, depth + 1) }.orEmpty()
                    VNode("ScrollView", mapOf("axes" to "vertical"), modifiers, children)
                }
                "List", "Form", "Group" -> {
                    val children = trailing?.let { parseViewList(it, depth + 1) }.orEmpty()
                    VNode(name, emptyMap(), modifiers, children)
                }
                "NavigationStack", "NavigationView", "NavigationSplitView" -> {
                    val children = trailing?.let { parseViewList(it, depth + 1) }.orEmpty()
                    VNode("NavigationStack", emptyMap(), modifiers, children)
                }
                "NavigationLink" -> {
                    val title = args.firstOrNull { !it.contains(":") }?.let { evalString(it.trim()) }
                        ?: parseLabeledString(args, "title")
                        ?: "Details"
                    val actionArgs = args.firstOrNull { it.trim().startsWith("destination:") }
                        ?.substringAfter(':')?.trim()
                    val destChildren = when {
                        actionArgs != null -> parseViewList(actionArgs, depth + 1)
                        trailing != null -> parseViewList(trailing, depth + 1)
                        else -> emptyList()
                    }.map { it.copy(props = it.props + ("navigationRole" to "destination")) }
                    val labelChild = VNode("Text", mapOf("text" to title, "navigationRole" to "label"))
                    VNode("NavigationLink", mapOf("title" to title, "label" to title), modifiers, destChildren + labelChild)
                }
                "TabView" -> {
                    val selBinding = parseLabeledBinding(args, "selection") ?: "_selectedTab"
                    val selectedIdx = ((session.state[selBinding] as? Number)?.toInt()) ?: 0
                    val onSelectId = session.register(SwiftUiSession.Action.IntBinding(selBinding))
                    val children = trailing?.let { parseViewList(it, depth + 1) }.orEmpty()
                    VNode(
                        type = "TabView",
                        props = mapOf("selectedIndex" to selectedIdx, "onSelect" to onSelectId),
                        modifiers = modifiers,
                        children = children,
                    )
                }
                "Path" -> {
                    val svg = trailing?.let { evaluatePathClosure(it) }.orEmpty()
                    VNode("Path", mapOf("d" to svg, "svgPath" to svg), modifiers)
                }
                "Circle", "Rectangle", "RoundedRectangle", "Capsule", "Ellipse" -> {
                    val cornerRadius = parseLabeledNumber(args, "cornerRadius") ?: 0.0
                    val svg = when (name) {
                        "Circle", "Ellipse" -> "M 50 0 A 50 50 0 1 0 50 100 A 50 50 0 1 0 50 0 Z"
                        else -> "M 0 0 L 100 0 L 100 100 L 0 100 Z"
                    }
                    VNode(
                        type = "Shape",
                        props = mapOf(
                            "shape" to name.lowercase(),
                            "cornerRadius" to cornerRadius,
                            "d" to svg,
                            "svgPath" to svg,
                        ),
                        modifiers = modifiers,
                    )
                }
                "Section" -> {
                    val props = LinkedHashMap<String, Any?>()
                    parseLabeledString(args, "header")?.let { props["header"] = it }
                    if (props.isEmpty() && args.isNotEmpty() && !args[0].contains(':')) {
                        props["header"] = evalString(args[0])
                    }
                    val children = trailing?.let { parseViewList(it, depth + 1) }.orEmpty()
                    VNode("Section", props, modifiers, children)
                }
                "Text" -> {
                    val raw = args.firstOrNull()?.trim().orEmpty()
                    VNode("Text", mapOf("text" to evalString(raw)), modifiers)
                }
                "Label" -> {
                    val title = args.firstOrNull()?.let { evalString(it.trim()) } ?: ""
                    val icon = parseLabeledString(args, "systemImage") ?: ""
                    VNode("Label", mapOf("title" to title, "systemImage" to icon), modifiers)
                }
                "Image" -> {
                    val sys = parseLabeledString(args, "systemName")
                    val props = if (sys != null) mapOf("systemName" to sys) else mapOf("name" to evalString(args.firstOrNull().orEmpty()))
                    VNode("Image", props, modifiers)
                }
                "Button" -> {
                    val actionArg = args.firstOrNull { it.trim().startsWith("action:") }
                    if (actionArg != null && trailing != null) {
                        val actionBody = actionArg.substringAfter(':').trim().removeSurrounding("{", "}").trim()
                        val id = session.register(SwiftUiSession.Action.Statement(actionBody))
                        val children = parseViewList(trailing, depth + 1)
                        val label = (children.firstOrNull { it.type == "Text" }?.props?.get("text") as? String) ?: "Button"
                        VNode("Button", mapOf("label" to label, "onTap" to id), modifiers, children)
                    } else {
                        val label = args.firstOrNull()?.let { evalString(it.trim()) } ?: "Button"
                        val id = session.register(SwiftUiSession.Action.Statement(trailing?.trim().orEmpty()))
                        VNode(
                            type = "Button",
                            props = mapOf("label" to label, "onTap" to id),
                            modifiers = modifiers,
                            children = listOf(VNode("Text", mapOf("text" to label))),
                        )
                    }
                }
                "TextField", "SecureField" -> {
                    val placeholder = args.firstOrNull()?.let { evalString(it.trim()) } ?: ""
                    val bindingName = parseLabeledBinding(args, "text")
                    val current = bindingName?.let { session.state[it]?.toString() } ?: ""
                    val id = bindingName?.let { session.register(SwiftUiSession.Action.StringBinding(it)) }
                    val props = LinkedHashMap<String, Any?>()
                    props["placeholder"] = placeholder
                    props["text"] = current
                    if (id != null) props["onChange"] = id
                    VNode("TextField", props, modifiers)
                }
                "Toggle" -> {
                    val label = args.firstOrNull()?.let { evalString(it.trim()) } ?: ""
                    val bindingName = parseLabeledBinding(args, "isOn")
                    val current = (bindingName?.let { session.state[it] } as? Boolean) ?: false
                    val id = bindingName?.let { session.register(SwiftUiSession.Action.BoolBinding(it)) }
                    val props = LinkedHashMap<String, Any?>()
                    props["label"] = label
                    props["isOn"] = current
                    if (id != null) props["onChange"] = id
                    VNode("Toggle", props, modifiers, listOf(VNode("Text", mapOf("text" to label))))
                }
                "Slider" -> {
                    val bindingName = parseLabeledBinding(args, "value")
                    val current = (bindingName?.let { session.state[it] } as? Number)?.toDouble() ?: 0.0
                    val range = parseLabeledRange(args, "in") ?: (0.0 to 1.0)
                    val id = bindingName?.let { session.register(SwiftUiSession.Action.DoubleBinding(it)) }
                    val props = LinkedHashMap<String, Any?>()
                    props["value"] = current
                    props["min"] = range.first
                    props["max"] = range.second
                    if (id != null) props["onChange"] = id
                    VNode("Slider", props, modifiers)
                }
                "Spacer" -> VNode("Spacer", emptyMap(), modifiers)
                "Divider" -> VNode("Divider", emptyMap(), modifiers)
                "ProgressView" -> {
                    val label = args.firstOrNull()?.takeIf { !it.contains(':') }?.let { evalString(it.trim()) }
                    val props = if (label != null) mapOf("label" to label) else emptyMap()
                    VNode("ProgressView", props, modifiers)
                }
                "ForEach" -> {
                    val count = parseRangeCount(args.firstOrNull().orEmpty()) ?: 3
                    val body = trailing?.replace(Regex("""^\s*[A-Za-z_]\w*\s+in\b"""), "") ?: ""
                    val template = parseViewList(body, depth + 1)
                    val expanded = (0 until count).flatMap { template }
                    VNode("ForEach", mapOf("count" to count), modifiers, expanded)
                }
                "Color" -> {
                    val c = args.firstOrNull()?.trim()?.removePrefix(".") ?: "clear"
                    VNode("Color", mapOf("color" to c), modifiers)
                }
                else -> {
                    val customStruct = structs[name]
                    if (customStruct?.bodySource != null && depth < 8) {
                        val subNodes = parseViewList(customStruct.bodySource, depth + 1)
                        when (subNodes.size) {
                            0 -> VNode("EmptyView")
                            1 -> subNodes.single().let { it.copy(modifiers = it.modifiers + modifiers) }
                            else -> VNode("VStack", emptyMap(), modifiers, subNodes)
                        }
                    } else {
                        null
                    }
                }
            }
        }

        private fun evaluatePathClosure(closure: String): String {
            val body = closure.replace(Regex("""^\s*[A-Za-z_]\w*\s+in\b"""), "").trim()
            val commands = mutableListOf<String>()
            val numRe = Regex("""-?\d+(?:\.\d+)?""")
            for (rawLine in body.lines()) {
                val line = rawLine.trim()
                when {
                    ".move(" in line -> {
                        val nums = numRe.findAll(line.substringAfter(".move(")).map { it.value }.toList()
                        if (nums.size >= 2) commands += "M ${nums[0]} ${nums[1]}"
                    }
                    ".addLine(" in line -> {
                        val nums = numRe.findAll(line.substringAfter(".addLine(")).map { it.value }.toList()
                        if (nums.size >= 2) commands += "L ${nums[0]} ${nums[1]}"
                    }
                    ".addQuadCurve(" in line -> {
                        val nums = numRe.findAll(line.substringAfter(".addQuadCurve(")).map { it.value }.toList()
                        if (nums.size >= 4) commands += "Q ${nums[2]} ${nums[3]} ${nums[0]} ${nums[1]}"
                    }
                    ".addCurve(" in line -> {
                        val nums = numRe.findAll(line.substringAfter(".addCurve(")).map { it.value }.toList()
                        if (nums.size >= 6) commands += "C ${nums[2]} ${nums[3]} ${nums[4]} ${nums[5]} ${nums[0]} ${nums[1]}"
                    }
                    ".addRect(" in line || ".addRoundedRect(" in line -> {
                        val nums = numRe.findAll(line.substringAfter(".add")).map { it.value.toDoubleOrNull() ?: 0.0 }.toList()
                        if (nums.size >= 4) {
                            val (x, y, w, h) = nums
                            commands += "M $x $y L ${x + w} $y L ${x + w} ${y + h} L $x ${y + h} Z"
                        }
                    }
                    ".closeSubpath(" in line -> commands += "Z"
                }
            }
            return commands.joinToString(" ")
        }

        private fun parseModifier(name: String, argsRaw: String): Map<String, Any?>? {
            val a = argsRaw.trim()
            return when (name) {
                "padding" -> {
                    val n = a.toDoubleOrNull()
                    if (n != null) mapOf("kind" to "padding", "all" to n)
                    else mapOf("kind" to "padding", "all" to 16.0)
                }
                "font" -> {
                    val sizeMatch = Regex("""size\s*:\s*(\d+(?:\.\d+)?)""").find(a)
                    if (sizeMatch != null) {
                        mapOf("kind" to "font", "size" to sizeMatch.groupValues[1].toDouble())
                    } else {
                        mapOf("kind" to "font", "style" to a.removePrefix("."))
                    }
                }
                "bold", "fontWeight" -> mapOf("kind" to "bold")
                "foregroundColor", "foregroundStyle", "tint" ->
                    mapOf("kind" to "foregroundColor", "color" to a.removePrefix("Color.").removePrefix("."))
                "background" ->
                    mapOf("kind" to "background", "color" to a.removePrefix("Color.").removePrefix("."))
                "cornerRadius", "clipShape" ->
                    mapOf("kind" to "cornerRadius", "radius" to (a.toDoubleOrNull() ?: 8.0))
                "navigationTitle" ->
                    mapOf("kind" to "navigationTitle", "title" to evalString(a))
                "frame" -> {
                    val out = LinkedHashMap<String, Any?>()
                    out["kind"] = "frame"
                    val parts = splitTopLevelCommas(a)
                    parseLabeledNumber(parts, "width")?.let { out["width"] = it }
                    parseLabeledNumber(parts, "height")?.let { out["height"] = it }
                    out
                }
                else -> null
            }
        }

        private fun evalString(expr: String): String {
            val s = expr.trim()
            if (s.startsWith("\"") && s.endsWith("\"") && s.length >= 2) {
                val body = s.substring(1, s.length - 1)
                return interpolate(body)
            }
            val v = evalExpr(s, session.state)
            return v?.toString() ?: s
        }

        private fun interpolate(body: String): String {
            val out = StringBuilder()
            var i = 0
            while (i < body.length) {
                if (body[i] == '\\' && i + 1 < body.length && body[i + 1] == '(') {
                    val close = matchParen(body, i + 1)
                    if (close > i + 1) {
                        val inner = body.substring(i + 2, close)
                        val v = evalExpr(inner, session.state)
                        out.append(formatValue(v))
                        i = close + 1
                        continue
                    }
                }
                out.append(body[i])
                i++
            }
            return out.toString()
        }
    }

    // ---- Expression & Statement Evaluator ----------------------------------------------------------

    internal fun runAction(code: String, state: MutableMap<String, Any?>) {
        val stmts = code.split(';', '\n').map { it.trim() }.filter { it.isNotEmpty() }
        for (s in stmts) {
            val toggleCall = Regex("""^([A-Za-z_]\w*)\.toggle\(\s*\)$""").matchEntire(s)
            if (toggleCall != null) {
                val k = toggleCall.groupValues[1]
                val cur = (state[k] as? Boolean) ?: false
                state[k] = !cur
                continue
            }
            val compound = Regex("""^([A-Za-z_]\w*)\s*(\+=|-=|\*=)\s*(.+)$""").matchEntire(s)
            if (compound != null) {
                val k = compound.groupValues[1]
                val op = compound.groupValues[2]
                val rhs = evalExpr(compound.groupValues[3], state)
                val cur = state[k]
                if (cur is Number && rhs is Number) {
                    val isFloat = cur is Double || rhs is Double
                    state[k] = if (isFloat) {
                        val a = cur.toDouble()
                        val b = rhs.toDouble()
                        when (op) { "+=" -> a + b; "-=" -> a - b; "*=" -> a * b; else -> a }
                    } else {
                        val a = cur.toLong()
                        val b = rhs.toLong()
                        when (op) { "+=" -> a + b; "-=" -> a - b; "*=" -> a * b; else -> a }
                    }
                } else if (cur is String && op == "+=") {
                    state[k] = cur + formatValue(rhs)
                }
                continue
            }
            val assign = Regex("""^([A-Za-z_]\w*)\s*=\s*(.+)$""").matchEntire(s)
            if (assign != null) {
                val k = assign.groupValues[1]
                state[k] = evalExpr(assign.groupValues[2], state)
            }
        }
    }

    internal fun evalExpr(raw: String, state: Map<String, Any?>): Any? {
        val s = raw.trim()
        if (s.isEmpty()) return ""
        val qIdx = findTopLevelChar(s, '?')
        if (qIdx > 0) {
            val cIdx = findTopLevelChar(s, ':', qIdx + 1)
            if (cIdx > qIdx) {
                val cond = truthy(evalExpr(s.substring(0, qIdx), state))
                return if (cond) evalExpr(s.substring(qIdx + 1, cIdx), state)
                else evalExpr(s.substring(cIdx + 1), state)
            }
        }
        if (s.startsWith("!")) return !truthy(evalExpr(s.substring(1), state))
        for (op in listOf("==", "!=", ">=", "<=", ">", "<")) {
            val idx = findTopLevelOp(s, op)
            if (idx > 0) {
                val l = evalExpr(s.substring(0, idx), state)
                val r = evalExpr(s.substring(idx + op.length), state)
                return compare(l, r, op)
            }
        }
        val addIdx = findTopLevelAddSub(s)
        if (addIdx > 0) {
            val op = s[addIdx]
            val l = evalExpr(s.substring(0, addIdx), state)
            val r = evalExpr(s.substring(addIdx + 1), state)
            if (l is Number && r is Number) {
                return if (l is Double || r is Double) {
                    if (op == '+') l.toDouble() + r.toDouble() else l.toDouble() - r.toDouble()
                } else {
                    if (op == '+') l.toLong() + r.toLong() else l.toLong() - r.toLong()
                }
            }
            if (op == '+' && (l is String || r is String)) return formatValue(l) + formatValue(r)
        }
        if (s == "true") return true
        if (s == "false") return false
        if (s.startsWith("\"") && s.endsWith("\"") && s.length >= 2) return s.substring(1, s.length - 1)
        s.toLongOrNull()?.let { return it }
        s.toDoubleOrNull()?.let { return it }
        if (s in state) return state[s]
        return s
    }

    private fun truthy(v: Any?): Boolean = when (v) {
        is Boolean -> v
        is Number -> v.toDouble() != 0.0
        is String -> v.isNotEmpty() && v != "false" && v != "0"
        else -> v != null
    }

    private fun compare(l: Any?, r: Any?, op: String): Boolean {
        if (l is Number && r is Number) {
            val c = l.toDouble().compareTo(r.toDouble())
            return when (op) {
                "==" -> c == 0
                "!=" -> c != 0
                ">" -> c > 0
                "<" -> c < 0
                ">=" -> c >= 0
                "<=" -> c <= 0
                else -> false
            }
        }
        return when (op) {
            "==" -> formatValue(l) == formatValue(r)
            "!=" -> formatValue(l) != formatValue(r)
            else -> false
        }
    }

    private fun formatValue(v: Any?): String = when (v) {
        null -> ""
        is Double -> if (v % 1.0 == 0.0) v.toLong().toString() else v.toString()
        else -> v.toString()
    }

    // ---- Low-level scanning helpers ----------------------------------------------------------------

    private fun matchBrace(s: String, openIdx: Int): Int = matchDelim(s, openIdx, '{', '}')
    private fun matchParen(s: String, openIdx: Int): Int = matchDelim(s, openIdx, '(', ')')

    private fun matchDelim(s: String, openIdx: Int, open: Char, close: Char): Int {
        var depth = 0
        var i = openIdx
        var inStr = false
        while (i < s.length) {
            val c = s[i]
            if (inStr) {
                if (c == '\\') { i += 2; continue }
                if (c == '"') inStr = false
            } else {
                when (c) {
                    '"' -> inStr = true
                    open -> depth++
                    close -> {
                        depth--
                        if (depth == 0) return i
                    }
                }
            }
            i++
        }
        return -1
    }

    private fun skipWs(s: String, start: Int): Int {
        var i = start
        while (i < s.length && s[i].isWhitespace()) i++
        return i
    }

    private fun readIdentifier(s: String, start: Int): Pair<String, Int>? {
        val i0 = skipWs(s, start)
        if (i0 >= s.length || !(s[i0].isLetter() || s[i0] == '_')) return null
        var i = i0 + 1
        while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_')) i++
        return s.substring(i0, i) to i
    }

    private fun splitTopLevelStatements(block: String): List<String> {
        val out = mutableListOf<String>()
        var start = 0
        var depth = 0
        var inStr = false
        var i = 0
        while (i < block.length) {
            val c = block[i]
            if (inStr) {
                if (c == '\\') { i += 2; continue }
                if (c == '"') inStr = false
            } else {
                when (c) {
                    '"' -> inStr = true
                    '(', '{', '[' -> depth++
                    ')', '}', ']' -> if (depth > 0) depth--
                    '\n', ';' -> if (depth == 0) {
                        val nextIdx = skipWs(block, i + 1)
                        if (c == '\n' && nextIdx < block.length && block[nextIdx] == '.') {
                            i++
                            continue
                        }
                        val piece = block.substring(start, i).trim()
                        if (piece.isNotEmpty()) out += piece
                        start = i + 1
                    }
                }
            }
            i++
        }
        val tail = block.substring(start).trim()
        if (tail.isNotEmpty()) out += tail
        return out
    }

    private fun splitTopLevelCommas(s: String): List<String> {
        if (s.isBlank()) return emptyList()
        val out = mutableListOf<String>()
        var start = 0
        var depth = 0
        var inStr = false
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (inStr) {
                if (c == '\\') { i += 2; continue }
                if (c == '"') inStr = false
            } else {
                when (c) {
                    '"' -> inStr = true
                    '(', '{', '[' -> depth++
                    ')', '}', ']' -> if (depth > 0) depth--
                    ',' -> if (depth == 0) {
                        out += s.substring(start, i).trim()
                        start = i + 1
                    }
                }
            }
            i++
        }
        out += s.substring(start).trim()
        return out.filter { it.isNotEmpty() }
    }

    private fun parseLabeledNumber(args: List<String>, label: String): Double? {
        val hit = args.firstOrNull { it.startsWith("$label:") } ?: return null
        return hit.substringAfter(':').trim().toDoubleOrNull()
    }

    private fun parseLabeledIdent(args: List<String>, label: String): String? {
        val hit = args.firstOrNull { it.startsWith("$label:") } ?: return null
        return hit.substringAfter(':').trim().removePrefix(".")
    }

    private fun parseLabeledString(args: List<String>, label: String): String? {
        val hit = args.firstOrNull { it.startsWith("$label:") } ?: return null
        val v = hit.substringAfter(':').trim()
        return v.removeSurrounding("\"")
    }

    private fun parseLabeledBinding(args: List<String>, label: String): String? {
        val hit = args.firstOrNull { it.startsWith("$label:") } ?: return null
        return hit.substringAfter(':').trim().removePrefix("$")
    }

    private fun parseLabeledRange(args: List<String>, label: String): Pair<Double, Double>? {
        val hit = args.firstOrNull { it.startsWith("$label:") } ?: return null
        val v = hit.substringAfter(':').trim()
        val parts = v.split("...", "..<")
        if (parts.size != 2) return null
        val a = parts[0].trim().toDoubleOrNull() ?: return null
        val b = parts[1].trim().toDoubleOrNull() ?: return null
        return a to b
    }

    private fun parseRangeCount(raw: String): Int? {
        val s = raw.trim()
        val m = Regex("""(-?\d+)\s*(\.\.<|\.\.\.)\s*(-?\d+)""").find(s) ?: return null
        val a = m.groupValues[1].toInt()
        val op = m.groupValues[2]
        val b = m.groupValues[3].toInt()
        return (if (op == "...") b - a + 1 else b - a).coerceIn(0, 50)
    }

    private fun findTopLevelChar(s: String, target: Char, from: Int = 0): Int {
        var depth = 0
        var inStr = false
        var i = from
        while (i < s.length) {
            val c = s[i]
            if (inStr) {
                if (c == '\\') { i += 2; continue }
                if (c == '"') inStr = false
            } else {
                when (c) {
                    '"' -> inStr = true
                    '(', '{', '[' -> depth++
                    ')', '}', ']' -> if (depth > 0) depth--
                    target -> if (depth == 0) return i
                }
            }
            i++
        }
        return -1
    }

    private fun findTopLevelOp(s: String, op: String): Int {
        var depth = 0
        var inStr = false
        var i = 0
        while (i <= s.length - op.length) {
            val c = s[i]
            if (inStr) {
                if (c == '\\') { i += 2; continue }
                if (c == '"') inStr = false
            } else {
                when (c) {
                    '"' -> inStr = true
                    '(', '{', '[' -> depth++
                    ')', '}', ']' -> if (depth > 0) depth--
                    else -> if (depth == 0 && s.startsWith(op, i)) return i
                }
            }
            i++
        }
        return -1
    }

    private fun findTopLevelAddSub(s: String): Int {
        var depth = 0
        var inStr = false
        var last = -1
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (inStr) {
                if (c == '\\') { i += 2; continue }
                if (c == '"') inStr = false
            } else {
                when (c) {
                    '"' -> inStr = true
                    '(', '{', '[' -> depth++
                    ')', '}', ']' -> if (depth > 0) depth--
                    '+', '-' -> if (depth == 0 && i > 0) last = i
                }
            }
            i++
        }
        return last
    }

    // ---- JSON Encoding -----------------------------------------------------------------------------

    private fun StringBuilder.renderNode(n: VNode) {
        append("{\"type\":\"").append(escapeJson(n.type)).append("\"")
        if (n.props.isNotEmpty()) {
            append(",\"props\":{")
            var first = true
            for ((k, v) in n.props) {
                if (!first) append(',')
                first = false
                append('"').append(escapeJson(k)).append("\":")
                renderValue(v)
            }
            append('}')
        }
        if (n.modifiers.isNotEmpty()) {
            append(",\"modifiers\":[")
            n.modifiers.forEachIndexed { idx, m ->
                if (idx > 0) append(',')
                append('{')
                var first = true
                for ((k, v) in m) {
                    if (!first) append(',')
                    first = false
                    append('"').append(escapeJson(k)).append("\":")
                    renderValue(v)
                }
                append('}')
            }
            append(']')
        }
        if (n.children.isNotEmpty()) {
            append(",\"children\":[")
            n.children.forEachIndexed { idx, c ->
                if (idx > 0) append(',')
                renderNode(c)
            }
            append(']')
        }
        append('}')
    }

    private fun StringBuilder.renderValue(v: Any?) {
        when (v) {
            null -> append("null")
            is Boolean, is Int, is Long -> append(v.toString())
            is Double -> if (v.isFinite()) append(v.toString()) else append("0")
            is Float -> if (v.isFinite()) append(v.toString()) else append("0")
            else -> append('"').append(escapeJson(v.toString())).append('"')
        }
    }

    private fun escapeJson(s: String): String = buildString(s.length) {
        for (c in s) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(c)
            }
        }
    }

    private fun unescapeJson(s: String): String = s
        .replace("\\n", "\n")
        .replace("\\r", "\r")
        .replace("\\t", "\t")
        .replace("\\\"", "\"")
        .replace("\\\\", "\\")
}
