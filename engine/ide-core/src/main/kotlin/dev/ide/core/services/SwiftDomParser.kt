package dev.ide.core.services

import dev.ide.lang.dom.Diagnostic
import dev.ide.lang.dom.DomNode
import dev.ide.lang.dom.KotlinNodeKinds as K
import dev.ide.lang.dom.NodeKind
import dev.ide.lang.dom.ParsedFile
import dev.ide.lang.dom.TextRange
import dev.ide.lang.resolve.StructureItem
import dev.ide.lang.resolve.SymbolKind
import dev.ide.vfs.VirtualFile

/**
 * Tolerant Swift DOM parser and structure extractor that maps `.swift` source files (including SwiftUI
 * `App` / `View` declarations, computed `var body: some View/Scene` builders, trailing-closure view
 * hierarchies, `struct`/`class`/`enum`/`extension`/`protocol`/`actor` types, `func`/`init` declarations,
 * `@State var`/`let` properties, `switch`/`case`/`default`, `if`/`else`, `for`/`while`, and chained
 * view modifiers) onto the neutral Kotlin DOM ([K]) so [dev.ide.block.impl.KotlinBlockMapping],
 * [dev.ide.ui.editor.BlockEditor], breadcrumbs, and structure outline work natively on Swift projects.
 */
object SwiftDomParser {

    fun parse(file: VirtualFile, documentVersion: Long, text: String): ParsedFile {
        val rootRange = TextRange(0, text.length)
        val parsed = SwiftParsedFile(file, documentVersion, text, rootRange)
        val builder = Parser(text)
        val topChildren = builder.parseCompilationUnit(0, text.length, parsed)
        parsed.attachChildren(topChildren)
        return parsed
    }

    fun fileStructure(file: VirtualFile, text: String): List<StructureItem> {
        val parsed = parse(file, 1L, text)
        val out = ArrayList<StructureItem>()
        fun walk(node: DomNode, depth: Int) {
            when (node.kind) {
                K.CLASS_DECL -> {
                    val headerEnd = node.children.firstOrNull { it.kind == K.CLASS_BODY }?.range?.start ?: node.range.end
                    val header = text.substring(node.range.start, headerEnd)
                    val m = TYPE_DECL_REGEX.find(header)
                    if (m != null) {
                        val kw = m.groupValues[1]
                        val name = m.groupValues[2]
                        val nameOffset = node.range.start + m.groups[2]!!.range.first
                        val symKind = when (kw) {
                            "protocol" -> SymbolKind.INTERFACE
                            "enum" -> SymbolKind.ENUM
                            else -> SymbolKind.CLASS
                        }
                        val detail = header.substring(m.range.last + 1).trim().removePrefix(":").trim().ifEmpty { null }
                        out += StructureItem(name, detail, symKind, nameOffset, node.range.end, depth)
                    }
                    val body = node.children.firstOrNull { it.kind == K.CLASS_BODY }
                    body?.children?.forEach { walk(it, depth + 1) }
                }
                K.METHOD_DECL, K.INIT -> {
                    val bodyStart = node.children.firstOrNull { it.kind == K.BLOCK }?.range?.start ?: node.range.end
                    val header = text.substring(node.range.start, bodyStart)
                    val funcMatch = FUNC_DECL_REGEX.find(header)
                    val varMatch = if (funcMatch == null) COMPUTED_VAR_REGEX.find(header) else null
                    val initMatch = if (funcMatch == null && varMatch == null) INIT_DECL_REGEX.find(header) else null
                    when {
                        funcMatch != null -> {
                            val name = funcMatch.groupValues[1]
                            val nameOffset = node.range.start + funcMatch.groups[1]!!.range.first
                            val detail = header.substring(funcMatch.groups[1]!!.range.last + 1).trim().ifEmpty { "()" }
                            out += StructureItem(name, detail, SymbolKind.METHOD, nameOffset, node.range.end, depth)
                        }
                        varMatch != null -> {
                            val name = varMatch.groupValues[1]
                            val nameOffset = node.range.start + varMatch.groups[1]!!.range.first
                            val typeText = node.children.firstOrNull { it.kind == K.TYPE_REF }?.let {
                                text.substring(it.range.start, it.range.end).trim()
                            }
                            out += StructureItem(name, typeText, SymbolKind.METHOD, nameOffset, node.range.end, depth)
                        }
                        initMatch != null -> {
                            val nameOffset = node.range.start + initMatch.range.first
                            val detail = header.substring(initMatch.range.last + 1).trim().ifEmpty { "()" }
                            out += StructureItem("init", detail, SymbolKind.CONSTRUCTOR, nameOffset, node.range.end, depth)
                        }
                    }
                }
                K.PROPERTY -> {
                    val m = PROP_DECL_REGEX.find(text.substring(node.range.start, node.range.end))
                    if (m != null) {
                        val name = m.groupValues[2]
                        val nameOffset = node.range.start + m.groups[2]!!.range.first
                        val typeText = node.children.firstOrNull { it.kind == K.TYPE_REF }?.let {
                            text.substring(it.range.start, it.range.end).trim()
                        }
                        out += StructureItem(name, typeText, SymbolKind.FIELD, nameOffset, node.range.end, depth)
                    }
                }
                else -> node.children.forEach { walk(it, depth) }
            }
        }
        parsed.children.forEach { walk(it, 0) }
        return out
    }

    private val TYPE_DECL_REGEX = Regex("\\b(struct|class|enum|extension|protocol|actor)\\s+([A-Za-z_][\\w.]*)")
    private val FUNC_DECL_REGEX = Regex("\\bfunc\\s+`?([A-Za-z_][\\w]*)`?")
    private val COMPUTED_VAR_REGEX = Regex("\\bvar\\s+`?([A-Za-z_][\\w]*)`?")
    private val INIT_DECL_REGEX = Regex("\\binit\\b[?!]?")
    private val PROP_DECL_REGEX = Regex("\\b(var|let)\\s+`?([A-Za-z_][\\w]*)`?")

    internal class SwiftDomNode(
        override val kind: NodeKind,
        override val range: TextRange,
        override var parent: DomNode?,
        private val sourceText: String,
    ) : DomNode {
        private var _children: List<DomNode> = emptyList()
        override val children: List<DomNode> get() = _children

        fun attachChildren(kids: List<DomNode>) {
            val sorted = kids
                .filter { it.range.start >= range.start && it.range.end <= range.end && it.range.start <= it.range.end }
                .sortedBy { it.range.start }
            val nonOverlapping = ArrayList<DomNode>(sorted.size)
            var lastEnd = range.start
            for (k in sorted) {
                if (k.range.start >= lastEnd) {
                    if (k is SwiftDomNode) k.parent = this
                    nonOverlapping += k
                    lastEnd = k.range.end
                }
            }
            _children = nonOverlapping
        }

        override fun text(): CharSequence = sourceText.subSequence(range.start, range.end)
    }

    internal class SwiftParsedFile(
        override val file: VirtualFile,
        override val documentVersion: Long,
        private val sourceText: String,
        override val range: TextRange,
    ) : ParsedFile {
        override val kind: NodeKind = K.COMPILATION_UNIT
        override val parent: DomNode? = null
        private var _children: List<DomNode> = emptyList()
        override val children: List<DomNode> get() = _children
        override val diagnostics: List<Diagnostic> = emptyList()

        fun attachChildren(kids: List<DomNode>) {
            val sorted = kids
                .filter { it.range.start >= range.start && it.range.end <= range.end && it.range.start <= it.range.end }
                .sortedBy { it.range.start }
            val nonOverlapping = ArrayList<DomNode>(sorted.size)
            var lastEnd = range.start
            for (k in sorted) {
                if (k.range.start >= lastEnd) {
                    if (k is SwiftDomNode) k.parent = this
                    nonOverlapping += k
                    lastEnd = k.range.end
                }
            }
            _children = nonOverlapping
        }

        override fun text(): CharSequence = sourceText

        override fun nodeAt(offset: Int): DomNode {
            val clamped = offset.coerceIn(0, sourceText.length)
            fun dive(n: DomNode): DomNode {
                for (c in n.children) {
                    if (clamped in c.range.start..c.range.end) return dive(c)
                }
                return n
            }
            return dive(this)
        }

        override fun nodesIn(range: TextRange): Sequence<DomNode> {
            val out = ArrayList<DomNode>()
            fun walk(n: DomNode) {
                if (!n.range.intersects(range)) return
                out += n
                n.children.forEach(::walk)
            }
            walk(this)
            return out.asSequence()
        }
    }

    private class Parser(private val src: String) {

        private fun node(kind: NodeKind, start: Int, end: Int, parent: DomNode?, kids: List<DomNode> = emptyList()): SwiftDomNode {
            val s = start.coerceIn(0, src.length)
            val e = end.coerceIn(s, src.length)
            return SwiftDomNode(kind, TextRange(s, e), parent, src).also { it.attachChildren(kids) }
        }

        fun parseCompilationUnit(start: Int, end: Int, owner: DomNode): List<DomNode> {
            val statements = splitStatements(start, end)
            val imports = ArrayList<DomNode>()
            val others = ArrayList<DomNode>()

            for ((s, e) in statements) {
                val trimmed = src.substring(s, e).trimStart()
                if (trimmed.startsWith("import ") || trimmed.startsWith("import\t")) {
                    imports += node(K.IMPORT_DECL, s, e, null)
                } else {
                    parseMemberOrStatement(s, e, owner, isTopOrClassLevel = true)?.let { others += it }
                }
            }

            val out = ArrayList<DomNode>()
            if (imports.isNotEmpty()) {
                val listStart = imports.first().range.start
                val listEnd = imports.last().range.end
                val importList = node(K.IMPORT_LIST, listStart, listEnd, owner, imports)
                out += importList
            }
            out += others
            return out
        }

        private fun parseClassBodyMembers(bodyStart: Int, bodyEnd: Int, classBodyNode: DomNode): List<DomNode> {
            val innerStart = if (bodyStart < bodyEnd && src[bodyStart] == '{') bodyStart + 1 else bodyStart
            val innerEnd = if (bodyEnd > innerStart && src[bodyEnd - 1] == '}') bodyEnd - 1 else bodyEnd
            val statements = splitStatements(innerStart, innerEnd)
            val out = ArrayList<DomNode>()
            for ((s, e) in statements) {
                val sub = src.substring(s, e).trimStart()
                if (sub.startsWith("case ") && findTopLevelBrace(s, e) < 0) {
                    // Enum case declaration (`case one = "1"`, `case add, subtract`) — keep as chrome gap
                    continue
                }
                parseMemberOrStatement(s, e, classBodyNode, isTopOrClassLevel = true)?.let { out += it }
            }
            return out
        }

        private fun parseBlockStatements(innerStart: Int, innerEnd: Int, blockNode: DomNode): List<DomNode> {
            val statements = splitStatements(innerStart, innerEnd)
            val out = ArrayList<DomNode>()
            for ((s, e) in statements) {
                parseMemberOrStatement(s, e, blockNode, isTopOrClassLevel = false)?.let { out += it }
            }
            return out
        }

        private fun parseMemberOrStatement(start: Int, end: Int, parent: DomNode?, isTopOrClassLevel: Boolean): DomNode? {
            val (s, e) = trimSpan(start, end)
            if (s >= e) return null

            val headOffset = skipAttributesAndModifiers(s, e)
            val word = readIdentifier(headOffset, e)

            when (word) {
                "struct", "class", "enum", "extension", "protocol", "actor" -> {
                    return parseTypeDecl(s, e, parent)
                }
                "func" -> {
                    return parseFuncDecl(s, e, headOffset, parent)
                }
                "init" -> {
                    return parseInitDecl(s, e, headOffset, parent)
                }
                "var", "let" -> {
                    return parseVarOrLet(s, e, headOffset, word, parent, isTopOrClassLevel)
                }
            }

            if (isTopOrClassLevel && headOffset > s) {
                // Retry if an attribute had arguments on multiple lines
                val afterAttrs = skipLeadingAnnotationsAndModifiers(s, e)
                val retryWord = readIdentifier(afterAttrs, e)
                when (retryWord) {
                    "struct", "class", "enum", "extension", "protocol", "actor" -> return parseTypeDecl(s, e, parent)
                    "func" -> return parseFuncDecl(s, e, afterAttrs, parent)
                    "init" -> return parseInitDecl(s, e, afterAttrs, parent)
                    "var", "let" -> return parseVarOrLet(s, e, afterAttrs, retryWord, parent, true)
                }
            }

            return parseStatementOrExpr(s, e, parent)
        }

        private fun parseTypeDecl(s: Int, e: Int, parent: DomNode?): DomNode {
            val braceStart = findTopLevelBrace(s, e)
            if (braceStart < 0) {
                return node(K.CLASS_DECL, s, e, parent)
            }
            val braceEnd = findMatchingClose(braceStart, e, '{', '}')
            val clsNode = SwiftDomNode(K.CLASS_DECL, TextRange(s, e), parent, src)
            val bodyNode = SwiftDomNode(K.CLASS_BODY, TextRange(braceStart, braceEnd), clsNode, src)
            val members = parseClassBodyMembers(braceStart, braceEnd, bodyNode)
            bodyNode.attachChildren(members)
            clsNode.attachChildren(listOf(bodyNode))
            return clsNode
        }

        private fun parseFuncDecl(s: Int, e: Int, kwOffset: Int, parent: DomNode?): DomNode {
            val fnNode = SwiftDomNode(K.METHOD_DECL, TextRange(s, e), parent, src)
            val kids = ArrayList<DomNode>()
            val braceStart = findTopLevelBrace(kwOffset, e)
            val headerEnd = if (braceStart >= 0) braceStart else e

            val parenStart = findTopLevelChar(kwOffset, headerEnd, '(')
            if (parenStart >= 0) {
                val parenEnd = findMatchingClose(parenStart, headerEnd, '(', ')')
                kids += parseParameters(parenStart + 1, (parenEnd - 1).coerceAtLeast(parenStart + 1), fnNode)
                val arrowIdx = findTopLevelSubstring(parenEnd, headerEnd, "->")
                if (arrowIdx >= 0) {
                    val (ts, te) = trimSpan(arrowIdx + 2, headerEnd)
                    if (ts < te) {
                        kids += node(K.TYPE_REF, ts, te, fnNode)
                    }
                }
            }

            if (braceStart >= 0) {
                val braceEnd = findMatchingClose(braceStart, e, '{', '}')
                val blockNode = SwiftDomNode(K.BLOCK, TextRange(braceStart, braceEnd), fnNode, src)
                val stmts = parseBlockStatements(braceStart + 1, (braceEnd - 1).coerceAtLeast(braceStart + 1), blockNode)
                blockNode.attachChildren(stmts)
                kids += blockNode
            }

            fnNode.attachChildren(kids)
            return fnNode
        }

        private fun parseInitDecl(s: Int, e: Int, kwOffset: Int, parent: DomNode?): DomNode {
            val fnNode = SwiftDomNode(K.METHOD_DECL, TextRange(s, e), parent, src)
            val kids = ArrayList<DomNode>()
            val braceStart = findTopLevelBrace(kwOffset, e)
            val headerEnd = if (braceStart >= 0) braceStart else e
            val parenStart = findTopLevelChar(kwOffset, headerEnd, '(')
            if (parenStart >= 0) {
                val parenEnd = findMatchingClose(parenStart, headerEnd, '(', ')')
                kids += parseParameters(parenStart + 1, (parenEnd - 1).coerceAtLeast(parenStart + 1), fnNode)
            }
            if (braceStart >= 0) {
                val braceEnd = findMatchingClose(braceStart, e, '{', '}')
                val blockNode = SwiftDomNode(K.BLOCK, TextRange(braceStart, braceEnd), fnNode, src)
                val stmts = parseBlockStatements(braceStart + 1, (braceEnd - 1).coerceAtLeast(braceStart + 1), blockNode)
                blockNode.attachChildren(stmts)
                kids += blockNode
            }
            fnNode.attachChildren(kids)
            return fnNode
        }

        private fun parseParameters(s: Int, e: Int, parent: DomNode): List<DomNode> {
            val parts = splitTopLevelByChar(s, e, ',')
            val out = ArrayList<DomNode>()
            for ((ps, pe) in parts) {
                val (ts, te) = trimSpan(ps, pe)
                if (ts >= te) continue
                val pNode = SwiftDomNode(K.PARAMETER, TextRange(ts, te), parent, src)
                val colon = findTopLevelChar(ts, te, ':')
                if (colon >= 0) {
                    val eq = findTopLevelAssignment(colon + 1, te)
                    val typeEnd = if (eq >= 0) eq else te
                    val (typeS, typeE) = trimSpan(colon + 1, typeEnd)
                    val pKids = ArrayList<DomNode>()
                    if (typeS < typeE) pKids += node(K.TYPE_REF, typeS, typeE, pNode)
                    if (eq >= 0) {
                        parseExpr(eq + 1, te, pNode)?.let { pKids += it }
                    }
                    pNode.attachChildren(pKids)
                }
                out += pNode
            }
            return out
        }

        private fun parseVarOrLet(
            s: Int,
            e: Int,
            kwOffset: Int,
            kw: String,
            parent: DomNode?,
            isTopOrClassLevel: Boolean,
        ): DomNode {
            val afterKw = skipWhitespace(kwOffset + kw.length, e)
            val nameEnd = skipIdentifier(afterKw, e)
            val eqIdx = findTopLevelAssignment(nameEnd, e)
            val braceIdx = findTopLevelBrace(nameEnd, e)

            // Computed property (`var body: some Scene { ... }` or `var buttonColor: Color { ... }`):
            // Has a `{ ... }` block and NO `=` before `{`. Project as METHOD_DECL so BlockEditor opens its body as visual blocks!
            if (kw == "var" && braceIdx >= 0 && (eqIdx < 0 || braceIdx < eqIdx)) {
                val fnNode = SwiftDomNode(K.METHOD_DECL, TextRange(s, e), parent, src)
                val kids = ArrayList<DomNode>()
                val colonIdx = findTopLevelChar(nameEnd, braceIdx, ':')
                if (colonIdx >= 0) {
                    val (ts, te) = trimSpan(colonIdx + 1, braceIdx)
                    if (ts < te) {
                        kids += node(K.TYPE_REF, ts, te, fnNode)
                    }
                }
                val braceEnd = findMatchingClose(braceIdx, e, '{', '}')
                val blockNode = SwiftDomNode(K.BLOCK, TextRange(braceIdx, braceEnd), fnNode, src)
                val bodyInnerStart = braceIdx + 1
                val bodyInnerEnd = (braceEnd - 1).coerceAtLeast(bodyInnerStart)
                // Check if computed property has explicit `get { ... }` accessor
                val trimmedBody = src.substring(bodyInnerStart, bodyInnerEnd).trimStart()
                val stmts = if (trimmedBody.startsWith("get ") || trimmedBody.startsWith("get{")) {
                    val getBrace = findTopLevelBrace(bodyInnerStart, bodyInnerEnd)
                    if (getBrace >= 0) {
                        val getEnd = findMatchingClose(getBrace, bodyInnerEnd, '{', '}')
                        parseBlockStatements(getBrace + 1, (getEnd - 1).coerceAtLeast(getBrace + 1), blockNode)
                    } else {
                        parseBlockStatements(bodyInnerStart, bodyInnerEnd, blockNode)
                    }
                } else {
                    parseBlockStatements(bodyInnerStart, bodyInnerEnd, blockNode)
                }
                blockNode.attachChildren(stmts)
                kids += blockNode
                fnNode.attachChildren(kids)
                return fnNode
            }

            // Stored property or local variable (`@State var value = "0"`, `let x: Int = 1`)
            val kind = if (isTopOrClassLevel) K.PROPERTY else K.LOCAL_VAR
            val propNode = SwiftDomNode(kind, TextRange(s, e), parent, src)
            val kids = ArrayList<DomNode>()
            val headerLimit = if (eqIdx >= 0) eqIdx else e
            val colonIdx = findTopLevelChar(nameEnd, headerLimit, ':')
            if (colonIdx >= 0) {
                val (ts, te) = trimSpan(colonIdx + 1, headerLimit)
                if (ts < te) {
                    kids += node(K.TYPE_REF, ts, te, propNode)
                }
            }
            if (eqIdx >= 0) {
                parseExpr(eqIdx + 1, e, propNode)?.let { kids += it }
            }
            propNode.attachChildren(kids)
            return propNode
        }

        private fun parseStatementOrExpr(s: Int, e: Int, parent: DomNode?): DomNode? {
            val (ts, te) = trimSpan(s, e)
            if (ts >= te) return null
            val word = readIdentifier(ts, te)
            return when (word) {
                "if" -> parseIf(ts, te, parent)
                "switch" -> parseSwitch(ts, te, parent)
                "for" -> parseFor(ts, te, parent)
                "while" -> parseWhile(ts, te, parent)
                "return" -> parseReturn(ts, te, parent)
                "break" -> node(K.BREAK, ts, te, parent)
                "continue" -> node(K.CONTINUE, ts, te, parent)
                else -> parseExpr(ts, te, parent)
            }
        }

        private fun parseIf(s: Int, e: Int, parent: DomNode?): DomNode {
            val ifNode = SwiftDomNode(K.IF, TextRange(s, e), parent, src)
            val kids = ArrayList<DomNode>()
            val thenBrace = findTopLevelBrace(s + 2, e)
            if (thenBrace < 0) return ifNode

            val (condS, condE) = trimSpan(s + 2, thenBrace)
            if (condS < condE) {
                val condContainer = SwiftDomNode(K.CONTAINER, TextRange(condS, condE), ifNode, src)
                val condExpr = if (src[condS] == '(' && findMatchingClose(condS, condE, '(', ')') == condE) {
                    parseExpr(condS + 1, condE - 1, condContainer)
                } else {
                    parseExpr(condS, condE, condContainer)
                }
                if (condExpr != null) condContainer.attachChildren(listOf(condExpr))
                kids += condContainer
            }

            val thenEnd = findMatchingClose(thenBrace, e, '{', '}')
            val thenControl = SwiftDomNode(K.CONTROL_BODY, TextRange(thenBrace, thenEnd), ifNode, src)
            val thenBlock = SwiftDomNode(K.BLOCK, TextRange(thenBrace, thenEnd), thenControl, src)
            thenBlock.attachChildren(parseBlockStatements(thenBrace + 1, (thenEnd - 1).coerceAtLeast(thenBrace + 1), thenBlock))
            thenControl.attachChildren(listOf(thenBlock))
            kids += thenControl

            val afterThen = skipWhitespace(thenEnd, e)
            if (readIdentifier(afterThen, e) == "else") {
                val elseStart = skipWhitespace(afterThen + 4, e)
                if (elseStart < e) {
                    val elseControl = SwiftDomNode(K.CONTROL_BODY, TextRange(elseStart, e), ifNode, src)
                    if (readIdentifier(elseStart, e) == "if") {
                        val nestedIf = parseIf(elseStart, e, elseControl)
                        elseControl.attachChildren(listOf(nestedIf))
                    } else if (src[elseStart] == '{') {
                        val elseEnd = findMatchingClose(elseStart, e, '{', '}')
                        val elseBlock = SwiftDomNode(K.BLOCK, TextRange(elseStart, elseEnd), elseControl, src)
                        elseBlock.attachChildren(parseBlockStatements(elseStart + 1, (elseEnd - 1).coerceAtLeast(elseStart + 1), elseBlock))
                        elseControl.attachChildren(listOf(elseBlock))
                    }
                    kids += elseControl
                }
            }

            ifNode.attachChildren(kids)
            return ifNode
        }

        private fun parseSwitch(s: Int, e: Int, parent: DomNode?): DomNode {
            val whenNode = SwiftDomNode(K.WHEN, TextRange(s, e), parent, src)
            val kids = ArrayList<DomNode>()
            val braceStart = findTopLevelBrace(s + 6, e)
            if (braceStart < 0) return whenNode

            val (subjS, subjE) = trimSpan(s + 6, braceStart)
            if (subjS < subjE) {
                parseExpr(subjS, subjE, whenNode)?.let { kids += it }
            }

            val braceEnd = findMatchingClose(braceStart, e, '{', '}')
            val innerStart = braceStart + 1
            val innerEnd = (braceEnd - 1).coerceAtLeast(innerStart)
            val caseStarts = findSwitchCaseStarts(innerStart, innerEnd)

            for (i in caseStarts.indices) {
                val cs = caseStarts[i]
                val ce = if (i + 1 < caseStarts.size) caseStarts[i + 1] else innerEnd
                val (entryS, entryE) = trimSpan(cs, ce)
                if (entryS >= entryE) continue
                val colonIdx = findTopLevelChar(entryS, entryE, ':')
                if (colonIdx < 0) continue

                val entryNode = SwiftDomNode(K.WHEN_ENTRY, TextRange(entryS, entryE), whenNode, src)
                val entryKids = ArrayList<DomNode>()
                val headWord = readIdentifier(entryS, colonIdx)
                if (headWord == "case") {
                    val condParts = splitTopLevelByChar(entryS + 4, colonIdx, ',')
                    for ((cps, cpe) in condParts) {
                        val (tcs, tce) = trimSpan(cps, cpe)
                        if (tcs < tce) {
                            val condNode = SwiftDomNode(K.WHEN_CONDITION, TextRange(tcs, tce), entryNode, src)
                            parseExpr(tcs, tce, condNode)?.let { condNode.attachChildren(listOf(it)) }
                            entryKids += condNode
                        }
                    }
                }

                val (bodyS, bodyE) = trimSpan(colonIdx + 1, entryE)
                val bodyRangeStart = if (bodyS < bodyE) bodyS else colonIdx + 1
                val bodyRangeEnd = if (bodyS < bodyE) bodyE else entryE
                val bodyBlock = SwiftDomNode(K.BLOCK, TextRange(bodyRangeStart, bodyRangeEnd), entryNode, src)
                if (bodyS < bodyE) {
                    bodyBlock.attachChildren(parseBlockStatements(bodyS, bodyE, bodyBlock))
                }
                entryKids += bodyBlock
                entryNode.attachChildren(entryKids)
                kids += entryNode
            }

            whenNode.attachChildren(kids)
            return whenNode
        }

        private fun findSwitchCaseStarts(s: Int, e: Int): List<Int> {
            val out = ArrayList<Int>()
            var i = s
            var depthBrace = 0
            var depthParen = 0
            var depthBracket = 0
            while (i < e) {
                val next = skipTriviaOrLiteral(i, e)
                if (next > i) {
                    i = next
                    continue
                }
                when (src[i]) {
                    '{' -> depthBrace++
                    '}' -> if (depthBrace > 0) depthBrace--
                    '(' -> depthParen++
                    ')' -> if (depthParen > 0) depthParen--
                    '[' -> depthBracket++
                    ']' -> if (depthBracket > 0) depthBracket--
                }
                if (depthBrace == 0 && depthParen == 0 && depthBracket == 0 && src[i].isLetter()) {
                    val prevOk = i == s || !src[i - 1].isLetterOrDigit() && src[i - 1] != '_' && src[i - 1] != '.'
                    if (prevOk) {
                        val w = readIdentifier(i, e)
                        if (w == "case" || w == "default") {
                            out += i
                            i += w.length
                            continue
                        }
                    }
                }
                i++
            }
            return out
        }

        private fun parseFor(s: Int, e: Int, parent: DomNode?): DomNode {
            val forNode = SwiftDomNode(K.FOR, TextRange(s, e), parent, src)
            val kids = ArrayList<DomNode>()
            val braceStart = findTopLevelBrace(s + 3, e)
            if (braceStart < 0) return forNode

            val inIdx = findTopLevelKeyword(s + 3, braceStart, "in")
            if (inIdx >= 0) {
                val (ps, pe) = trimSpan(s + 3, inIdx)
                if (ps < pe) kids += node(K.PARAMETER, ps, pe, forNode)
                val (iterS, iterE) = trimSpan(inIdx + 2, braceStart)
                if (iterS < iterE) {
                    val container = SwiftDomNode(K.CONTAINER, TextRange(iterS, iterE), forNode, src)
                    parseExpr(iterS, iterE, container)?.let { container.attachChildren(listOf(it)) }
                    kids += container
                }
            }

            val braceEnd = findMatchingClose(braceStart, e, '{', '}')
            val ctrl = SwiftDomNode(K.CONTROL_BODY, TextRange(braceStart, braceEnd), forNode, src)
            val block = SwiftDomNode(K.BLOCK, TextRange(braceStart, braceEnd), ctrl, src)
            block.attachChildren(parseBlockStatements(braceStart + 1, (braceEnd - 1).coerceAtLeast(braceStart + 1), block))
            ctrl.attachChildren(listOf(block))
            kids += ctrl

            forNode.attachChildren(kids)
            return forNode
        }

        private fun parseWhile(s: Int, e: Int, parent: DomNode?): DomNode {
            val whileNode = SwiftDomNode(K.WHILE, TextRange(s, e), parent, src)
            val kids = ArrayList<DomNode>()
            val braceStart = findTopLevelBrace(s + 5, e)
            if (braceStart < 0) return whileNode

            val (condS, condE) = trimSpan(s + 5, braceStart)
            if (condS < condE) {
                val container = SwiftDomNode(K.CONTAINER, TextRange(condS, condE), whileNode, src)
                parseExpr(condS, condE, container)?.let { container.attachChildren(listOf(it)) }
                kids += container
            }

            val braceEnd = findMatchingClose(braceStart, e, '{', '}')
            val ctrl = SwiftDomNode(K.CONTROL_BODY, TextRange(braceStart, braceEnd), whileNode, src)
            val block = SwiftDomNode(K.BLOCK, TextRange(braceStart, braceEnd), ctrl, src)
            block.attachChildren(parseBlockStatements(braceStart + 1, (braceEnd - 1).coerceAtLeast(braceStart + 1), block))
            ctrl.attachChildren(listOf(block))
            kids += ctrl

            whileNode.attachChildren(kids)
            return whileNode
        }

        private fun parseReturn(s: Int, e: Int, parent: DomNode?): DomNode {
            val retNode = SwiftDomNode(K.RETURN, TextRange(s, e), parent, src)
            val (es, ee) = trimSpan(s + 6, e)
            if (es < ee) {
                parseExpr(es, ee, retNode)?.let { retNode.attachChildren(listOf(it)) }
            }
            return retNode
        }

        private fun parseExpr(s: Int, e: Int, parent: DomNode?): DomNode? {
            val (ts, te) = trimSpan(s, e)
            if (ts >= te) return null

            // 1. Check top-level binary / assignment operators (`=`, `+=`, `-=`, `||`, `&&`, `==`, `!=`, `<=`, `>=`, `<`, `>`, `??`, `+`, `-`, `*`, `/`)
            val binOp = findLowestPrecedenceBinaryOp(ts, te)
            if (binOp != null) {
                val (opStart, opEnd) = binOp
                val (ls, le) = trimSpan(ts, opStart)
                val (rs, re) = trimSpan(opEnd, te)
                if (ls < le && rs < re) {
                    val binNode = SwiftDomNode(K.BINARY, TextRange(ts, te), parent, src)
                    val left = parseExpr(ls, le, binNode)
                    val op = node(K.OPERATOR, opStart, opEnd, binNode)
                    val right = parseExpr(rs, re, binNode)
                    binNode.attachChildren(listOfNotNull(left, op, right))
                    return binNode
                }
            }

            // 2. Parenthesized expression `( ... )`
            if (src[ts] == '(' && findMatchingClose(ts, te, '(', ')') == te) {
                val pNode = SwiftDomNode(K.PARENTHESIZED, TextRange(ts, te), parent, src)
                parseExpr(ts + 1, te - 1, pNode)?.let { pNode.attachChildren(listOf(it)) }
                return pNode
            }

            // 3. String literal `"..."`
            if (src[ts] == '"' && skipStringLiteral(ts, te) == te) {
                return node(K.STRING_TEMPLATE, ts, te, parent)
            }

            // 4. Array/dictionary literal `[...]`
            if (src[ts] == '[' && findMatchingClose(ts, te, '[', ']') == te) {
                return node(K.COLLECTION_LITERAL, ts, te, parent)
            }

            // 5. Standalone closure literal `{ ... }` (e.g. `action: { self.didTap(button: item) }`)
            if (src[ts] == '{' && findMatchingClose(ts, te, '{', '}') == te) {
                return parseStandaloneClosure(ts, te, parent)
            }

            // 6. Number / boolean / nil literal
            val textSlice = src.substring(ts, te)
            if (textSlice == "true" || textSlice == "false" || textSlice == "nil" || NUMBER_REGEX.matches(textSlice)) {
                return node(K.LITERAL, ts, te, parent)
            }

            // 7. Leading-dot enum member or static member (`.orange`, `.all`, `.system(size: 32)`)
            if (src[ts] == '.' && ts + 1 < te && src[ts + 1].isLetter()) {
                val afterDot = ts + 1
                val identEnd = skipIdentifier(afterDot, te)
                if (identEnd == te) {
                    return node(K.NAME_REF, ts, te, parent)
                }
            }

            // 8. Member access / call chain (`Color.black.edgesIgnoringSafeArea(.all)`, `WindowGroup { ... }`, `Text(value).bold()`)
            val lastDot = findLastTopLevelMemberDot(ts, te)
            if (lastDot > ts) {
                val (recS, recE) = trimSpan(ts, lastDot)
                val (selS, selE) = trimSpan(lastDot + 1, te)
                if (recS < recE && selS < selE) {
                    val accessNode = SwiftDomNode(K.MEMBER_ACCESS, TextRange(ts, te), parent, src)
                    val recNode = parseExpr(recS, recE, accessNode)
                    val selNode = parseCallOrName(selS, selE, accessNode)
                    accessNode.attachChildren(listOfNotNull(recNode, selNode))
                    return accessNode
                }
            }

            // 9. Single call or identifier (`WindowGroup { ContentView() }`, `Spacer()`, `ContentView()`, `value`)
            return parseCallOrName(ts, te, parent)
        }

        private fun parseCallOrName(s: Int, e: Int, parent: DomNode?): DomNode {
            val (ts, te) = trimSpan(s, e)
            if (ts >= te) return node(K.NAME_REF, s, e, parent)

            val nameStart = if (src[ts] == '.') ts else ts
            val identStart = if (src[ts] == '.') ts + 1 else ts
            val nameEnd = skipIdentifier(identStart, te)

            if (nameEnd > ts && nameEnd == te) {
                return node(K.NAME_REF, ts, te, parent)
            }

            if (nameEnd > ts) {
                var pos = skipWhitespace(nameEnd, te)
                var argListRange: Pair<Int, Int>? = null
                if (pos < te && src[pos] == '(') {
                    val closeParen = findMatchingClose(pos, te, '(', ')')
                    argListRange = pos to closeParen
                    pos = skipWhitespace(closeParen, te)
                }
                var lambdaRange: Pair<Int, Int>? = null
                if (pos < te && src[pos] == '{') {
                    val closeBrace = findMatchingClose(pos, te, '{', '}')
                    if (skipWhitespace(closeBrace, te) == te) {
                        lambdaRange = pos to closeBrace
                        pos = closeBrace
                    }
                }

                if ((argListRange != null || lambdaRange != null) && skipWhitespace(pos, te) == te) {
                    val callNode = SwiftDomNode(K.METHOD_CALL, TextRange(ts, te), parent, src)
                    val kids = ArrayList<DomNode>()
                    kids += node(K.NAME_REF, nameStart, nameEnd, callNode)

                    if (argListRange != null) {
                        val (als, ale) = argListRange
                        val argListNode = SwiftDomNode(K.ARGUMENT_LIST, TextRange(als, ale), callNode, src)
                        val rawArgs = splitTopLevelByChar(als + 1, (ale - 1).coerceAtLeast(als + 1), ',')
                        val argNodes = ArrayList<DomNode>()
                        for ((as0, ae0) in rawArgs) {
                            val (as1, ae1) = trimSpan(as0, ae0)
                            if (as1 >= ae1) continue
                            val argNode = SwiftDomNode(K.ARGUMENT, TextRange(as1, ae1), argListNode, src)
                            val colon = findTopLevelChar(as1, ae1, ':')
                            val argKids = ArrayList<DomNode>()
                            if (colon > as1) {
                                val (ns, ne) = trimSpan(as1, colon)
                                if (ns < ne && isSimpleIdentifier(ns, ne)) {
                                    argKids += node(K.ARGUMENT_NAME, ns, ne, argNode)
                                    val (vs, ve) = trimSpan(colon + 1, ae1)
                                    if (vs < ve) {
                                        parseArgumentValue(vs, ve, argNode)?.let { argKids += it }
                                    }
                                } else {
                                    parseArgumentValue(as1, ae1, argNode)?.let { argKids += it }
                                }
                            } else {
                                parseArgumentValue(as1, ae1, argNode)?.let { argKids += it }
                            }
                            argNode.attachChildren(argKids)
                            argNodes += argNode
                        }
                        argListNode.attachChildren(argNodes)
                        kids += argListNode
                    }

                    if (lambdaRange != null) {
                        val (ls, le) = lambdaRange
                        kids += buildLambdaArgument(ls, le, callNode)
                    }

                    callNode.attachChildren(kids)
                    return callNode
                }
            }

            return node(K.NAME_REF, ts, te, parent)
        }

        private fun parseArgumentValue(s: Int, e: Int, parent: DomNode): DomNode? {
            val (ts, te) = trimSpan(s, e)
            if (ts >= te) return null
            // KeyPath `\.self`
            if (src.startsWith("\\.", ts)) {
                return node(K.NAME_REF, ts, te, parent)
            }
            return parseExpr(ts, te, parent)
        }

        private fun parseStandaloneClosure(ls: Int, le: Int, parent: DomNode?): DomNode {
            val lambdaNode = SwiftDomNode(K.LAMBDA, TextRange(ls, le), parent, src)
            val litNode = SwiftDomNode(K.FUNCTION_LITERAL, TextRange(ls, le), lambdaNode, src)
            val bodyStart = findClosureBodyStart(ls + 1, (le - 1).coerceAtLeast(ls + 1))
            val bodyEnd = (le - 1).coerceAtLeast(bodyStart)
            val blockNode = SwiftDomNode(K.BLOCK, TextRange(bodyStart, bodyEnd), litNode, src)
            blockNode.attachChildren(parseBlockStatements(bodyStart, bodyEnd, blockNode))
            litNode.attachChildren(listOf(blockNode))
            lambdaNode.attachChildren(listOf(litNode))
            return lambdaNode
        }

        private fun buildLambdaArgument(ls: Int, le: Int, parent: DomNode): DomNode {
            val lambdaArg = SwiftDomNode(K.LAMBDA_ARGUMENT, TextRange(ls, le), parent, src)
            val lambdaNode = SwiftDomNode(K.LAMBDA, TextRange(ls, le), lambdaArg, src)
            val litNode = SwiftDomNode(K.FUNCTION_LITERAL, TextRange(ls, le), lambdaNode, src)
            val innerStart = ls + 1
            val innerEnd = (le - 1).coerceAtLeast(innerStart)
            val bodyStart = findClosureBodyStart(innerStart, innerEnd)
            val blockNode = SwiftDomNode(K.BLOCK, TextRange(bodyStart, innerEnd), litNode, src)
            blockNode.attachChildren(parseBlockStatements(bodyStart, innerEnd, blockNode))
            litNode.attachChildren(listOf(blockNode))
            lambdaNode.attachChildren(listOf(litNode))
            lambdaArg.attachChildren(listOf(lambdaNode))
            return lambdaArg
        }

        /**
         * In Swift trailing closures like `ForEach(buttons, id: \.self) { row in ... }`, keep `row in` outside
         * the inner [K.BLOCK] range so [dev.ide.block.impl.KotlinBlockMapping] preserves `{ row in` as chrome on
         * the C-block header!
         */
        private fun findClosureBodyStart(innerStart: Int, innerEnd: Int): Int {
            val firstNewline = src.indexOf('\n', innerStart).let { if (it in innerStart..innerEnd) it else innerEnd }
            val inKeyword = findTopLevelKeyword(innerStart, firstNewline, "in")
            return if (inKeyword >= 0) inKeyword + 2 else innerStart
        }

        // ---- Lexical & structural helpers ----

        private fun splitStatements(start: Int, end: Int): List<Pair<Int, Int>> {
            val out = ArrayList<Pair<Int, Int>>()
            var stmtStart = skipWhitespaceAndComments(start, end)
            var i = stmtStart
            var depthBrace = 0
            var depthParen = 0
            var depthBracket = 0

            while (i < end) {
                val skipped = skipTriviaOrLiteral(i, end)
                if (skipped > i) {
                    i = skipped
                    continue
                }
                val c = src[i]
                when (c) {
                    '{' -> depthBrace++
                    '}' -> if (depthBrace > 0) depthBrace--
                    '(' -> depthParen++
                    ')' -> if (depthParen > 0) depthParen--
                    '[' -> depthBracket++
                    ']' -> if (depthBracket > 0) depthBracket--
                    ';' -> if (depthBrace == 0 && depthParen == 0 && depthBracket == 0) {
                        val (ts, te) = trimSpan(stmtStart, i)
                        if (ts < te) out += ts to te
                        stmtStart = skipWhitespaceAndComments(i + 1, end)
                        i = stmtStart
                        continue
                    }
                    '\n' -> if (depthBrace == 0 && depthParen == 0 && depthBracket == 0) {
                        val nextNonWs = skipWhitespaceAndComments(i + 1, end)
                        // Check if the next line is a continuation (e.g. `.padding()`, `.font(...)`, `else`, `{`)
                        val continues = nextNonWs < end && (
                            (src[nextNonWs] == '.' && nextNonWs + 1 < end && src[nextNonWs + 1].isLetter()) ||
                                readIdentifier(nextNonWs, end) == "else" ||
                                src[nextNonWs] == '{'
                            )
                        if (!continues) {
                            val (ts, te) = trimSpan(stmtStart, i)
                            if (ts < te) out += ts to te
                            stmtStart = nextNonWs
                            i = stmtStart
                            continue
                        }
                    }
                }
                i++
            }
            val (ts, te) = trimSpan(stmtStart, end)
            if (ts < te) out += ts to te
            return out
        }

        private fun findLowestPrecedenceBinaryOp(s: Int, e: Int): Pair<Int, Int>? {
            // Precedence from lowest (outermost) to highest:
            // 1: assignment `=`, `+=`, `-=`, `*=`, `/=`
            // 2: `??`
            // 3: `||`
            // 4: `&&`
            // 5: `==`, `!=`, `<=`, `>=`, `<`, `>`
            // 6: `+`, `-`
            // 7: `*`, `/`, `%`
            var bestRank = Int.MAX_VALUE
            var bestSpan: Pair<Int, Int>? = null
            var i = s
            var depthBrace = 0
            var depthParen = 0
            var depthBracket = 0

            while (i < e) {
                val skipped = skipTriviaOrLiteral(i, e)
                if (skipped > i) {
                    i = skipped
                    continue
                }
                val c = src[i]
                when (c) {
                    '{' -> depthBrace++
                    '}' -> if (depthBrace > 0) depthBrace--
                    '(' -> depthParen++
                    ')' -> if (depthParen > 0) depthParen--
                    '[' -> depthBracket++
                    ']' -> if (depthBracket > 0) depthBracket--
                }
                if (depthBrace == 0 && depthParen == 0 && depthBracket == 0 && i > s) {
                    val two = if (i + 1 < e) src.substring(i, i + 2) else ""
                    val (rank, len) = when {
                        two in setOf("+=", "-=", "*=", "/=") -> 1 to 2
                        two == "??" -> 2 to 2
                        two == "||" -> 3 to 2
                        two == "&&" -> 4 to 2
                        two in setOf("==", "!=", "<=", ">=") -> 5 to 2
                        two == "->" || two == ".." -> 0 to 2
                        c == '=' && (i == s || src[i - 1] !in "=!<>+-*/") && (i + 1 >= e || src[i + 1] != '=') -> 1 to 1
                        (c == '<' || c == '>') && !looksLikeGenericBracket(s, e, i) -> 5 to 1
                        (c == '+' || c == '-') && isBinaryPlusMinus(s, i) -> 6 to 1
                        (c == '*' || c == '/' || c == '%') && (i + 1 >= e || src[i + 1] !in "/*=") -> 7 to 1
                        else -> 0 to 1
                    }
                    if (rank > 0 && (rank < bestRank || (rank == bestRank && rank > 1))) {
                        bestRank = rank
                        bestSpan = i to (i + len)
                    }
                    i += len
                    continue
                }
                i++
            }
            return bestSpan
        }

        private fun isBinaryPlusMinus(start: Int, idx: Int): Boolean {
            var p = idx - 1
            while (p >= start && (src[p] == ' ' || src[p] == '\t')) p--
            if (p < start) return false
            val prev = src[p]
            return prev.isLetterOrDigit() || prev == '_' || prev == ')' || prev == ']' || prev == '"'
        }

        private fun looksLikeGenericBracket(s: Int, e: Int, idx: Int): Boolean {
            if (src[idx] == '<' && idx > s && (src[idx - 1].isLetterOrDigit() || src[idx - 1] == '_')) {
                val close = src.indexOf('>', idx + 1)
                if (close in (idx + 1) until e) {
                    val inside = src.substring(idx + 1, close)
                    if (inside.all { it.isLetterOrDigit() || it in " _,:?." }) return true
                }
            }
            return false
        }

        private fun findLastTopLevelMemberDot(s: Int, e: Int): Int {
            var last = -1
            var i = s
            var depthBrace = 0
            var depthParen = 0
            var depthBracket = 0
            while (i < e) {
                val skipped = skipTriviaOrLiteral(i, e)
                if (skipped > i) {
                    i = skipped
                    continue
                }
                val c = src[i]
                when (c) {
                    '{' -> depthBrace++
                    '}' -> if (depthBrace > 0) depthBrace--
                    '(' -> depthParen++
                    ')' -> if (depthParen > 0) depthParen--
                    '[' -> depthBracket++
                    ']' -> if (depthBracket > 0) depthBracket--
                    '.' -> if (depthBrace == 0 && depthParen == 0 && depthBracket == 0) {
                        val hasReceiverBefore = i > s && !src.substring(s, i).isBlank()
                        val hasIdentAfter = i + 1 < e && src[i + 1].isLetter()
                        val notNumberDecimal = !(i > s && src[i - 1].isDigit() && i + 1 < e && src[i + 1].isDigit())
                        val notRange = !(i + 1 < e && src[i + 1] == '.') && !(i > s && src[i - 1] == '.')
                        val notKeyPath = !(i > s && src[i - 1] == '\\')
                        if (hasReceiverBefore && hasIdentAfter && notNumberDecimal && notRange && notKeyPath) {
                            last = i
                        }
                    }
                }
                i++
            }
            return last
        }

        private fun findTopLevelBrace(s: Int, e: Int): Int {
            var i = s
            var depthParen = 0
            var depthBracket = 0
            while (i < e) {
                val skipped = skipTriviaOrLiteral(i, e)
                if (skipped > i) {
                    i = skipped
                    continue
                }
                when (src[i]) {
                    '(' -> depthParen++
                    ')' -> if (depthParen > 0) depthParen--
                    '[' -> depthBracket++
                    ']' -> if (depthBracket > 0) depthBracket--
                    '{' -> if (depthParen == 0 && depthBracket == 0) return i
                }
                i++
            }
            return -1
        }

        private fun findTopLevelChar(s: Int, e: Int, target: Char): Int {
            var i = s
            var depthBrace = 0
            var depthParen = 0
            var depthBracket = 0
            while (i < e) {
                val skipped = skipTriviaOrLiteral(i, e)
                if (skipped > i) {
                    i = skipped
                    continue
                }
                val c = src[i]
                if (c == target && depthBrace == 0 && depthParen == 0 && depthBracket == 0) return i
                when (c) {
                    '{' -> depthBrace++
                    '}' -> if (depthBrace > 0) depthBrace--
                    '(' -> depthParen++
                    ')' -> if (depthParen > 0) depthParen--
                    '[' -> depthBracket++
                    ']' -> if (depthBracket > 0) depthBracket--
                }
                i++
            }
            return -1
        }

        private fun findTopLevelAssignment(s: Int, e: Int): Int {
            var i = s
            var depthBrace = 0
            var depthParen = 0
            var depthBracket = 0
            while (i < e) {
                val skipped = skipTriviaOrLiteral(i, e)
                if (skipped > i) {
                    i = skipped
                    continue
                }
                val c = src[i]
                when (c) {
                    '{' -> depthBrace++
                    '}' -> if (depthBrace > 0) depthBrace--
                    '(' -> depthParen++
                    ')' -> if (depthParen > 0) depthParen--
                    '[' -> depthBracket++
                    ']' -> if (depthBracket > 0) depthBracket--
                    '=' -> if (depthBrace == 0 && depthParen == 0 && depthBracket == 0) {
                        val prev = if (i > s) src[i - 1] else ' '
                        val next = if (i + 1 < e) src[i + 1] else ' '
                        if (prev !in "=!<>+-*/" && next != '=') return i
                    }
                }
                i++
            }
            return -1
        }

        private fun findTopLevelSubstring(s: Int, e: Int, sub: String): Int {
            var i = s
            var depthBrace = 0
            var depthParen = 0
            var depthBracket = 0
            while (i + sub.length <= e) {
                val skipped = skipTriviaOrLiteral(i, e)
                if (skipped > i) {
                    i = skipped
                    continue
                }
                if (depthBrace == 0 && depthParen == 0 && depthBracket == 0 && src.startsWith(sub, i)) return i
                when (src[i]) {
                    '{' -> depthBrace++
                    '}' -> if (depthBrace > 0) depthBrace--
                    '(' -> depthParen++
                    ')' -> if (depthParen > 0) depthParen--
                    '[' -> depthBracket++
                    ']' -> if (depthBracket > 0) depthBracket--
                }
                i++
            }
            return -1
        }

        private fun findTopLevelKeyword(s: Int, e: Int, kw: String): Int {
            var i = s
            var depthBrace = 0
            var depthParen = 0
            var depthBracket = 0
            while (i < e) {
                val skipped = skipTriviaOrLiteral(i, e)
                if (skipped > i) {
                    i = skipped
                    continue
                }
                val c = src[i]
                when (c) {
                    '{' -> depthBrace++
                    '}' -> if (depthBrace > 0) depthBrace--
                    '(' -> depthParen++
                    ')' -> if (depthParen > 0) depthParen--
                    '[' -> depthBracket++
                    ']' -> if (depthBracket > 0) depthBracket--
                }
                if (depthBrace == 0 && depthParen == 0 && depthBracket == 0 && c.isLetter()) {
                    val prevOk = i == s || (!src[i - 1].isLetterOrDigit() && src[i - 1] != '_')
                    if (prevOk && readIdentifier(i, e) == kw) return i
                }
                i++
            }
            return -1
        }

        private fun splitTopLevelByChar(s: Int, e: Int, delim: Char): List<Pair<Int, Int>> {
            val out = ArrayList<Pair<Int, Int>>()
            var partStart = s
            var i = s
            var depthBrace = 0
            var depthParen = 0
            var depthBracket = 0
            while (i < e) {
                val skipped = skipTriviaOrLiteral(i, e)
                if (skipped > i) {
                    i = skipped
                    continue
                }
                val c = src[i]
                if (c == delim && depthBrace == 0 && depthParen == 0 && depthBracket == 0) {
                    out += partStart to i
                    partStart = i + 1
                    i++
                    continue
                }
                when (c) {
                    '{' -> depthBrace++
                    '}' -> if (depthBrace > 0) depthBrace--
                    '(' -> depthParen++
                    ')' -> if (depthParen > 0) depthParen--
                    '[' -> depthBracket++
                    ']' -> if (depthBracket > 0) depthBracket--
                }
                i++
            }
            if (partStart <= e) out += partStart to e
            return out
        }

        private fun findMatchingClose(openIdx: Int, limit: Int, openChar: Char, closeChar: Char): Int {
            var i = openIdx
            var depth = 0
            while (i < limit) {
                val skipped = skipTriviaOrLiteral(i, limit)
                if (skipped > i) {
                    i = skipped
                    continue
                }
                val c = src[i]
                if (c == openChar) depth++
                else if (c == closeChar) {
                    depth--
                    if (depth == 0) return i + 1
                }
                i++
            }
            return limit
        }

        private fun skipTriviaOrLiteral(i: Int, limit: Int): Int {
            if (i >= limit) return i
            if (src.startsWith("//", i)) {
                val nl = src.indexOf('\n', i + 2)
                return if (nl < 0 || nl >= limit) limit else nl
            }
            if (src.startsWith("/*", i)) {
                val end = src.indexOf("*/", i + 2)
                return if (end < 0 || end + 2 >= limit) limit else end + 2
            }
            if (src[i] == '"') {
                return skipStringLiteral(i, limit)
            }
            return i
        }

        private fun skipStringLiteral(start: Int, limit: Int): Int {
            if (src.startsWith("\"\"\"", start)) {
                var i = start + 3
                while (i + 2 < limit) {
                    if (src[i] == '\\') {
                        i += 2
                        continue
                    }
                    if (src.startsWith("\"\"\"", i)) return i + 3
                    i++
                }
                return limit
            }
            var i = start + 1
            while (i < limit) {
                val c = src[i]
                if (c == '\\') {
                    i += 2
                    continue
                }
                if (c == '"') return i + 1
                i++
            }
            return limit
        }

        private fun skipAttributesAndModifiers(s: Int, e: Int): Int = skipLeadingAnnotationsAndModifiers(s, e)

        private fun skipLeadingAnnotationsAndModifiers(s: Int, e: Int): Int {
            var i = skipWhitespaceAndComments(s, e)
            while (i < e) {
                if (src[i] == '@') {
                    i = skipIdentifier(i + 1, e)
                    if (i < e && src[i] == '(') {
                        i = findMatchingClose(i, e, '(', ')')
                    }
                    i = skipWhitespaceAndComments(i, e)
                    continue
                }
                val w = readIdentifier(i, e)
                if (w in SWIFT_MODIFIERS) {
                    i += w.length
                    if (i < e && src[i] == '(') {
                        i = findMatchingClose(i, e, '(', ')')
                    }
                    i = skipWhitespaceAndComments(i, e)
                    continue
                }
                break
            }
            return i
        }

        private fun skipWhitespace(s: Int, e: Int): Int {
            var i = s
            while (i < e && src[i].isWhitespace()) i++
            return i
        }

        private fun skipWhitespaceAndComments(s: Int, e: Int): Int {
            var i = s
            while (i < e) {
                if (src[i].isWhitespace()) {
                    i++
                    continue
                }
                if (src.startsWith("//", i)) {
                    val nl = src.indexOf('\n', i + 2)
                    i = if (nl < 0 || nl >= e) e else nl + 1
                    continue
                }
                if (src.startsWith("/*", i)) {
                    val close = src.indexOf("*/", i + 2)
                    i = if (close < 0 || close + 2 >= e) e else close + 2
                    continue
                }
                break
            }
            return i
        }

        private fun trimSpan(s: Int, e: Int): Pair<Int, Int> {
            val ts = skipWhitespaceAndComments(s, e)
            var te = e
            while (te > ts && src[te - 1].isWhitespace()) te--
            return ts to te
        }

        private fun readIdentifier(s: Int, e: Int): String {
            val end = skipIdentifier(s, e)
            return if (end > s) src.substring(s, end).removeSurrounding("`") else ""
        }

        private fun skipIdentifier(s: Int, e: Int): Int {
            if (s >= e) return s
            if (src[s] == '`') {
                val close = src.indexOf('`', s + 1)
                return if (close in (s + 1) until e) close + 1 else s
            }
            if (!src[s].isLetter() && src[s] != '_') return s
            var i = s + 1
            while (i < e && (src[i].isLetterOrDigit() || src[i] == '_')) i++
            return i
        }

        private fun isSimpleIdentifier(s: Int, e: Int): Boolean =
            s < e && skipIdentifier(s, e) == e

        companion object {
            private val SWIFT_MODIFIERS = setOf(
                "public", "private", "internal", "fileprivate", "open",
                "static", "class", "final", "override", "mutating", "nonmutating",
                "lazy", "weak", "unowned", "convenience", "required", "optional",
                "indirect", "nonisolated", "isolated", "async",
            )
            private val NUMBER_REGEX = Regex("^-?\\d+(?:_\\d+)*(?:\\.\\d+(?:_\\d+)*)?$")
        }
    }
}
