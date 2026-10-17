package dev.ide.core.runtime

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Instant Cupertino Preview: interpret a practical SwiftUI subset from source into a
 * `ViewNode` JSON tree — no Swift toolchain, Apple SDK, or compile step.
 *
 * This is the high-risk, high-leverage alternative to shipping 566 MB of Swift/xtool
 * and waiting on `swiftc`/`xtool` just to see a UI. Fidelity Run (compiled Cupertino
 * Bridge) and Export IPA (xtool) remain as fallbacks when a project exceeds this subset.
 *
 * Supported surface (intentionally small):
 *  - `@main` App + `WindowGroup`, or a root `View` struct with `body`
 *  - `@State` properties (Int/Double/Bool/String with literal initializers)
 *  - VStack / HStack / ZStack / Group / List / Section / ForEach(0..<n)
 *  - Text (with `\(...)` interpolation) / Button / Toggle / TextField / SecureField
 *  - Image(systemName:) / Spacer / Divider / NavigationView / NavigationStack
 *  - Modifiers: padding, frame, foregroundColor, background, font, bold, onTapGesture,
 *    navigationTitle, disabled, opacity
 *  - Button / onTapGesture action scripts: assignment, `+=`/`-=`, and simple expressions
 *
 * Emits the same newline-ready JSON the Cupertino process bridge already streams to
 * `ProgramIo.frame`, so `BridgeGuiWindow` + `cupertino()` render it unchanged.
 */
object SwiftUiPreviewInterpreter {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * When a repo holds several targets (e.g. SimpleCounterSwiftUI + SimpleCounterTCA),
     * keep only the files of one interpretible SwiftUI app target.
     */
    fun selectSources(sources: List<String>): List<String> {
        if (sources.size <= 1) return sources
        val blocked = listOf(
            "SwiftData", "CoreData", "Map(", "WKWebView", "AVFoundation",
            "@FetchRequest", "@EnvironmentObject", "@StateObject", "@ObservedObject",
            "UIViewRepresentable", "UIViewControllerRepresentable",
            "import ComposableArchitecture", "import TCA",
        )
        data class Candidate(val index: Int, val text: String, val bad: Int, val hasSwiftUi: Boolean, val hasMain: Boolean)
        val scored = sources.mapIndexed { i, text ->
            val bad = blocked.count { text.contains(it) }
            val hasSwiftUi = text.contains("import SwiftUI") || text.contains(": View") || text.contains(": App")
            val hasMain = text.contains("@main") || Regex("""struct\s+\w+\s*:\s*App\b""").containsMatchIn(text)
            Candidate(i, text, bad, hasSwiftUi, hasMain)
        }
        val best = scored
            .filter { it.hasSwiftUi }
            .sortedWith(
                compareBy<Candidate> { it.bad }
                    .thenByDescending { it.hasMain }
                    .thenBy { it.text.length },
            )
            .firstOrNull { it.bad == 0 } ?: return sources
        // Keep all clean files that share the chosen file's directory-ish prefix (heuristic: no blocked imports).
        return sources.filter { s ->
            val bad = blocked.count { s.contains(it) }
            bad == 0 && (
                s.contains("import SwiftUI") ||
                    s.contains(": View") ||
                    s.contains(": App") ||
                    s.contains("@State") ||
                    s.contains("struct ")
                )
        }.ifEmpty { listOf(best.text) }
    }

    /** True when [sources] look like a SwiftUI app we can interpret without a toolchain. */
    fun canInterpret(sources: List<String>): Boolean {
        val chosen = selectSources(sources)
        val joined = chosen.joinToString("\n")
        if (!joined.contains("import SwiftUI") && !joined.contains(": View") && !joined.contains(": App")) {
            return false
        }
        // Reject early on constructs we deliberately do not model.
        val blocked = listOf(
            "SwiftData", "CoreData", "Map(", "WKWebView", "AVFoundation",
            "@FetchRequest", "@EnvironmentObject", "@StateObject", "@ObservedObject",
            "UIViewRepresentable", "UIViewControllerRepresentable",
        )
        if (blocked.any { joined.contains(it) }) return false
        return parse(chosen) != null
    }

    /**
     * Interpret [sources] into a ViewNode JSON tree using [session] state.
     * Throws [SwiftUiPreviewException] when the subset cannot cover the project.
     */
    fun interpret(sources: List<String>, session: PreviewSession = PreviewSession()): String {
        val chosen = selectSources(sources)
        val root = parse(chosen) ?: throw SwiftUiPreviewException("No interpretible SwiftUI View/App body found")
        val builder = ViewNodeBuilder(session)
        val node = builder.buildRoot(root, session)
        return node.toString()
    }

    /**
     * Mutable `@State` store plus callback dispatch. Call [dispatch] with the same
     * JSON event shape `BridgeGuiWindow` sends (`{"id":"…","kind":"void|bool|string|…"}`),
     * then [interpret] again to get the updated tree.
     */
    class PreviewSession {
        internal val state = LinkedHashMap<String, Any?>()
        internal val callbacks = LinkedHashMap<Long, CallbackBinding>()
        internal var nextCallbackId = 1L

        fun snapshot(): Map<String, Any?> = state.toMap()

        /** Apply a callback event. Returns true when state changed and a re-interpret is needed. */
        fun dispatch(eventJson: String): Boolean {
            val obj = runCatching { json.parseToJsonElement(eventJson) as? JsonObject }.getOrNull() ?: return false
            val id = (obj["id"] as? JsonPrimitive)?.content?.toLongOrNull() ?: return false
            val kind = (obj["kind"] as? JsonPrimitive)?.content.orEmpty()
            val value = obj["value"]
            val binding = callbacks[id] ?: return false
            return when (binding) {
                is CallbackBinding.VoidAction -> {
                    runAction(binding.script)
                    true
                }
                is CallbackBinding.BoolBinding -> {
                    val b = when {
                        kind == "bool" -> (value as? JsonPrimitive)?.content?.toBooleanStrictOrNull()
                        value is JsonPrimitive -> value.content.toBooleanStrictOrNull()
                        else -> null
                    } ?: return false
                    if (state[binding.name] == b) return false
                    state[binding.name] = b
                    true
                }
                is CallbackBinding.StringBinding -> {
                    val s = (value as? JsonPrimitive)?.content ?: return false
                    if (state[binding.name] == s) return false
                    state[binding.name] = s
                    true
                }
            }
        }

        internal fun runAction(script: String) {
            // Assignment statements: `count += 1`, `name = "x"`, `enabled = true`, `count = count + 1`
            val stmts = script.split(';').map { it.trim() }.filter { it.isNotEmpty() }
            for (stmt in stmts) {
                val compound = Regex("""^([A-Za-z_]\w*)\s*(\+=|-=|\*=|/=)\s*(.+)$""").find(stmt)
                if (compound != null) {
                    val (name, op, rhs) = compound.destructured
                    val current = evalSimple(rhs)
                    val base = coerceNumber(state[name] ?: 0) ?: 0.0
                    val delta = coerceNumber(current) ?: 0.0
                    state[name] = when (op) {
                        "+=" -> normalizeNumber(base + delta)
                        "-=" -> normalizeNumber(base - delta)
                        "*=" -> normalizeNumber(base * delta)
                        "/=" -> if (delta == 0.0) state[name] else normalizeNumber(base / delta)
                        else -> state[name]
                    }
                    continue
                }
                val assign = Regex("""^([A-Za-z_]\w*)\s*=\s*(.+)$""").find(stmt)
                if (assign != null) {
                    val (name, rhs) = assign.destructured
                    state[name] = evalSimple(rhs)
                }
            }
        }

        /** Tiny expression evaluator for action scripts (literals, state names, + - * /, ternary). */
        private fun evalSimple(src: String): Any? {
            val t = src.trim()
            if (t.equals("true", true)) return true
            if (t.equals("false", true)) return false
            t.toDoubleOrNull()?.let { return normalizeNumber(it) }
            if (t.startsWith("\"") && t.endsWith("\"") && t.length >= 2) return t.substring(1, t.length - 1)
            // ternary: a ? b : c
            val tern = Regex("""^(.+?)\s*\?\s*(.+?)\s*:\s*(.+)$""").find(t)
            if (tern != null) {
                val (c, a, b) = tern.destructured
                val cond = coerceNumber(evalSimple(c))
                return if (cond != null && cond != 0.0) evalSimple(a) else evalSimple(b)
            }
            // binary + - * / (left-to-right, no precedence — action scripts are simple)
            val bin = Regex("""^(.+?)\s*([+\-*/])\s*(.+)$""").find(t)
            if (bin != null) {
                val (l, op, r) = bin.destructured
                val lv = coerceNumber(evalSimple(l)) ?: 0.0
                val rv = coerceNumber(evalSimple(r)) ?: 0.0
                return when (op) {
                    "+" -> normalizeNumber(lv + rv)
                    "-" -> normalizeNumber(lv - rv)
                    "*" -> normalizeNumber(lv * rv)
                    "/" -> if (rv == 0.0) normalizeNumber(lv) else normalizeNumber(lv / rv)
                    else -> normalizeNumber(lv)
                }
            }
            // bare identifier
            if (t.matches(Regex("""^[A-Za-z_]\w*$"""))) return state[t]
            return t
        }
    }

    sealed class CallbackBinding {
        data class VoidAction(val script: String) : CallbackBinding()
        data class BoolBinding(val name: String) : CallbackBinding()
        data class StringBinding(val name: String) : CallbackBinding()
    }

    class SwiftUiPreviewException(message: String) : RuntimeException(message)

    // ------------------------------------------------------------------
    // AST
    // ------------------------------------------------------------------

    internal sealed class Ast {
        data class Struct(
            val name: String,
            val superTypes: List<String>,
            val stateProps: List<StateProp>,
            val body: Expr?,
        ) : Ast()

        data class StateProp(val name: String, val initial: Any?)

        sealed class Expr : Ast()
        data class Literal(val value: Any?) : Expr()
        data class Ident(val name: String) : Expr()
        data class Member(val target: Expr?, val name: String) : Expr()
        data class Call(val callee: Expr, val args: List<Arg>, val trailing: List<Expr> = emptyList()) : Expr()
        data class Arg(val label: String?, val value: Expr)
        data class Binary(val op: String, val left: Expr, val right: Expr) : Expr()
        data class Unary(val op: String, val expr: Expr) : Expr()
        data class Ternary(val cond: Expr, val thenE: Expr, val elseE: Expr) : Expr()
        data class StringLit(val parts: List<StringPart>) : Expr()
        data class Closure(val params: List<String>, val body: List<Expr>) : Expr()
        data class InfixDots(val base: Expr, val calls: List<Call>) : Expr()

        sealed class StringPart
        data class StrText(val text: String) : StringPart()
        data class StrExpr(val expr: Expr) : StringPart()
    }

    // ------------------------------------------------------------------
    // Lexer
    // ------------------------------------------------------------------

    private sealed class Tok {
        data class Ident(val v: String) : Tok()
        data class Num(val v: Double) : Tok()
        data class Str(val raw: String) : Tok() // may contain \(...)
        data class Sym(val v: String) : Tok()
        object Eof : Tok()
    }

    private fun lex(src: String): List<Tok> {
        val out = ArrayList<Tok>()
        var i = 0
        val n = src.length
        while (i < n) {
            val c = src[i]
            when {
                c.isWhitespace() -> i++
                c == '/' && i + 1 < n && src[i + 1] == '/' -> {
                    while (i < n && src[i] != '\n') i++
                }
                c == '/' && i + 1 < n && src[i + 1] == '*' -> {
                    i += 2
                    while (i + 1 < n && !(src[i] == '*' && src[i + 1] == '/')) i++
                    i = (i + 2).coerceAtMost(n)
                }
                c == '"' -> {
                    val start = i
                    i++ // opening quote
                    while (i < n) {
                        if (src[i] == '\\' && i + 1 < n) {
                            i += 2
                            continue
                        }
                        if (src[i] == '"') {
                            i++
                            break
                        }
                        i++
                    }
                    out += Tok.Str(src.substring(start + 1, (i - 1).coerceAtLeast(start + 1)))
                }
                c.isDigit() -> {
                    val start = i
                    while (i < n && src[i].isDigit()) i++
                    // decimal only when '.' is followed by a digit (so `0..<3` stays a range)
                    if (i + 1 < n && src[i] == '.' && src[i + 1].isDigit()) {
                        i++
                        while (i < n && src[i].isDigit()) i++
                    }
                    out += Tok.Num(src.substring(start, i).toDoubleOrNull() ?: 0.0)
                }
                c.isLetter() || c == '_' -> {
                    val start = i
                    while (i < n && (src[i].isLetterOrDigit() || src[i] == '_')) i++
                    out += Tok.Ident(src.substring(start, i))
                }
                else -> {
                    // multi-char ops first
                    val two = if (i + 1 < n) src.substring(i, i + 2) else ""
                    val three = if (i + 2 < n) src.substring(i, i + 3) else ""
                    when {
                        three == "..." || three == "..<" -> {
                            out += Tok.Sym(three); i += 3
                        }
                        two in setOf("==", "!=", "<=", ">=", "&&", "||", "+=", "-=", "*=", "/=", "->", "??") -> {
                            out += Tok.Sym(two); i += 2
                        }
                        else -> {
                            out += Tok.Sym(c.toString()); i++
                        }
                    }
                }
            }
        }
        out += Tok.Eof
        return out
    }

    // ------------------------------------------------------------------
    // Parser
    // ------------------------------------------------------------------

    private class Parser(private val toks: List<Tok>) {
        var pos = 0

        fun peek(): Tok = toks[pos]
        fun next(): Tok = toks[pos++]
        fun at(v: String): Boolean = peek() is Tok.Sym && (peek() as Tok.Sym).v == v
        fun atIdent(v: String): Boolean = peek() is Tok.Ident && (peek() as Tok.Ident).v == v

        fun expectSym(v: String) {
            if (!at(v)) throw SwiftUiPreviewException("Expected '$v' at token $pos, got ${peek()}")
            pos++
        }

        fun expectIdent(): String {
            val t = next()
            if (t !is Tok.Ident) throw SwiftUiPreviewException("Expected identifier, got $t")
            return t.v
        }

        fun skipAttributesAndModifiers() {
            while (true) {
                when {
                    at("@") -> {
                        pos++
                        if (peek() is Tok.Ident) pos++
                        // @State private var — leave the rest to property parsing
                        if (at("(")) skipParens()
                    }
                    atIdent("private") || atIdent("public") || atIdent("internal") ||
                        atIdent("fileprivate") || atIdent("static") || atIdent("final") ||
                        atIdent("lazy") || atIdent("weak") || atIdent("nonisolated") -> pos++
                    else -> return
                }
            }
        }

        fun skipParens() {
            expectSym("(")
            var depth = 1
            while (pos < toks.size && depth > 0) {
                val t = next()
                when {
                    t is Tok.Sym && t.v == "(" -> depth++
                    t is Tok.Sym && t.v == ")" -> depth--
                    t is Tok.Sym && t.v == "{" -> depth++ // loosely
                    t is Tok.Sym && t.v == "}" -> if (depth > 1) depth--
                }
            }
        }

        fun parseFile(): List<Ast.Struct> {
            val structs = ArrayList<Ast.Struct>()
            while (pos < toks.size && peek() != Tok.Eof) {
                skipAttributesAndModifiers()
                if (atIdent("struct") || atIdent("class") || atIdent("enum")) {
                    structs += parseStruct()
                } else {
                    // Skip top-level noise (imports already ignored by caller)
                    next()
                }
            }
            return structs
        }

        private fun parseStruct(): Ast.Struct {
            val kind = expectIdent() // struct/class/enum
            val name = expectIdent()
            val superTypes = ArrayList<String>()
            if (at(":")) {
                pos++
                superTypes += expectIdent()
                while (at(",")) {
                    pos++
                    skipAttributesAndModifiers()
                    superTypes += expectIdent()
                }
            }
            // generic where clauses etc.
            while (peek() is Tok.Ident && (peek() as Tok.Ident).v == "where") {
                // skip to body
                while (pos < toks.size && !at("{") && peek() != Tok.Eof) next()
            }
            expectSym("{")
            val stateProps = ArrayList<Ast.StateProp>()
            var body: Ast.Expr? = null
            var depth = 1
            while (pos < toks.size && depth > 0) {
                if (at("{")) {
                    depth++
                    next()
                    continue
                }
                if (at("}")) {
                    depth--
                    next()
                    if (depth == 0) break
                    continue
                }
                if (depth != 1) {
                    next()
                    continue
                }
                skipAttributesAndModifiers()
                when {
                    atIdent("var") || atIdent("let") -> {
                        pos++
                        val propName = expectIdent()
                        // type annotation
                        if (at(":")) {
                            pos++
                            skipType()
                        }
                        var initial: Any? = null
                        if (at("=")) {
                            pos++
                            initial = literalInitial()
                        }
                        if (propName == "body") {
                            body = parseExpr()
                        } else {
                            stateProps += Ast.StateProp(propName, initial)
                        }
                    }
                    atIdent("func") || atIdent("init") -> {
                        // skip function body
                        skipFuncLike()
                    }
                    atIdent("struct") || atIdent("class") || atIdent("enum") -> {
                        parseStruct() // nested; ignore
                    }
                    else -> next()
                }
            }
            // Also pick @State that were declared with attributes before var (handled above if we saw var).
            // Re-scan stateProps for defaults that were captured.
            return Ast.Struct(name, superTypes, stateProps, body)
        }

        private fun skipType() {
            // skip until = or { or newline-ish token that starts a new decl
            var depth = 0
            while (pos < toks.size) {
                val t = peek()
                if (depth == 0) {
                    if (at("=") || at("{") || atIdent("var") || atIdent("let") ||
                        atIdent("func") || atIdent("init") || atIdent("struct")
                    ) return
                    if (atIdent("some") || atIdent("any")) {
                        pos++
                        continue
                    }
                }
                when {
                    at("(") || at("[") || at("<") -> {
                        depth++; pos++
                    }
                    at(")") || at("]") || at(">") -> {
                        depth = (depth - 1).coerceAtLeast(0); pos++
                    }
                    else -> pos++
                }
            }
        }

        private fun literalInitial(): Any? {
            // Parse a constant expression without full View support
            return when (val t = peek()) {
                is Tok.Num -> {
                    pos++
                    normalizeNumber(t.v)
                }
                is Tok.Str -> {
                    pos++
                    parseStringParts(t.raw).joinToString("") {
                        when (it) {
                            is Ast.StrText -> it.text
                            is Ast.StrExpr -> "<expr>"
                        }
                    }
                }
                is Tok.Ident -> when (t.v) {
                    "true" -> {
                        pos++; true
                    }
                    "false" -> {
                        pos++; false
                    }
                    else -> {
                        pos++
                        t.v
                    }
                }
                is Tok.Sym -> when (t.v) {
                    "-" -> {
                        pos++
                        val n = next()
                        if (n is Tok.Num) -n.v else 0.0
                    }
                    else -> {
                        pos++
                        null
                    }
                }
                else -> {
                    pos++
                    null
                }
            }
        }

        private fun skipFuncLike() {
            // func name(...) ... { ... }
            while (pos < toks.size && !at("{") && !at("=") && peek() != Tok.Eof) next()
            if (at("=")) {
                // func body expression
                pos++
                parseExpr()
                return
            }
            if (at("{")) {
                var depth = 0
                do {
                    if (at("{")) depth++
                    if (at("}")) depth--
                    next()
                } while (depth > 0 && pos < toks.size)
            }
        }

        fun parseExpr(): Ast.Expr {
            return parseTernary()
        }

        private fun parseTernary(): Ast.Expr {
            val cond = parseOr()
            if (at("?")) {
                pos++
                val thenE = parseTernary()
                expectSym(":")
                val elseE = parseTernary()
                return Ast.Ternary(cond, thenE, elseE)
            }
            return cond
        }

        private fun parseOr(): Ast.Expr {
            var left = parseAnd()
            while (at("||")) {
                pos++
                left = Ast.Binary("||", left, parseAnd())
            }
            return left
        }

        private fun parseAnd(): Ast.Expr {
            var left = parseEquality()
            while (at("&&")) {
                pos++
                left = Ast.Binary("&&", left, parseEquality())
            }
            return left
        }

        private fun parseEquality(): Ast.Expr {
            var left = parseCompare()
            while (at("==") || at("!=") || at("??")) {
                val op = (next() as Tok.Sym).v
                left = Ast.Binary(op, left, parseCompare())
            }
            return left
        }

        private fun parseCompare(): Ast.Expr {
            var left = parseAdd()
            while (at("<") || at(">") || at("<=") || at(">=") || at("..<") || at("...")) {
                val op = (next() as Tok.Sym).v
                left = Ast.Binary(op, left, parseAdd())
            }
            return left
        }

        private fun parseAdd(): Ast.Expr {
            var left = parseMul()
            while (at("+") || at("-")) {
                val op = (next() as Tok.Sym).v
                left = Ast.Binary(op, left, parseMul())
            }
            return left
        }

        private fun parseMul(): Ast.Expr {
            var left = parseUnary()
            while (at("*") || at("/")) {
                val op = (next() as Tok.Sym).v
                left = Ast.Binary(op, left, parseUnary())
            }
            return left
        }

        private fun parseUnary(): Ast.Expr {
            if (at("!")) {
                pos++
                return Ast.Unary("!", parseUnary())
            }
            if (at("-")) {
                pos++
                return Ast.Unary("-", parseUnary())
            }
            if (at("$")) {
                pos++
                return Ast.Unary("$", parseUnary())
            }
            return parsePostfix()
        }

        /** Assignment / compound-assignment used in action closures. */
        private fun parseAssignment(): Ast.Expr {
            val left = parseExpr()
            val op = when {
                at("=") -> {
                    pos++; "="
                }
                at("+=") -> {
                    pos++; "+="
                }
                at("-=") -> {
                    pos++; "-="
                }
                at("*=") -> {
                    pos++; "*="
                }
                at("/=") -> {
                    pos++; "/="
                }
                else -> return left
            }
            return Ast.Binary(op, left, parseAssignment())
        }

        private fun parsePostfix(): Ast.Expr {
            var e = parsePrimary()
            while (true) {
                when {
                    at(".") -> {
                        pos++
                        val name = expectIdent()
                        if (at("(")) {
                            val args = parseArgs()
                            e = Ast.Call(Ast.Member(e, name), args)
                        } else {
                            e = Ast.Member(e, name)
                        }
                    }
                    at("(") -> {
                        val args = parseArgs()
                        e = Ast.Call(e, args)
                    }
                    at("{") -> {
                        // trailing closure
                        val cl = parseClosure()
                        e = when (e) {
                            is Ast.Call -> e.copy(trailing = e.trailing + cl)
                            else -> Ast.Call(e, emptyList(), listOf(cl))
                        }
                    }
                    else -> return e
                }
            }
        }

        private fun parseArgs(): List<Ast.Arg> {
            expectSym("(")
            val args = ArrayList<Ast.Arg>()
            if (at(")")) {
                pos++
                return args
            }
            while (true) {
                var label: String? = null
                if (peek() is Tok.Ident) {
                    val id = (peek() as Tok.Ident).v
                    val save = pos
                    pos++
                    if (at(":")) {
                        pos++
                        label = id
                    } else {
                        pos = save
                    }
                }
                val value = parseExpr()
                args += Ast.Arg(label, value)
                if (at(",")) {
                    pos++
                    continue
                }
                break
            }
            expectSym(")")
            return args
        }

        private fun parseClosure(): Ast.Closure {
            expectSym("{")
            val params = ArrayList<String>()
            // { count += 1 } or { v in ... } or { (v: Int) in ... }
            if (at("(")) {
                // param list
                skipParens()
                if (atIdent("in")) pos++
            } else if (peek() is Tok.Ident) {
                val save = pos
                val p1 = expectIdent()
                if (atIdent("in")) {
                    pos++
                    params += p1
                } else {
                    pos = save
                }
            }
            val body = ArrayList<Ast.Expr>()
            while (!at("}") && peek() != Tok.Eof) {
                // statements may be assignments; parse as expressions / skip keywords
                if (atIdent("let") || atIdent("var")) {
                    pos++
                    if (peek() is Tok.Ident) pos++
                    if (at("=")) {
                        pos++
                        parseExpr()
                    }
                    if (at(",")) pos++
                    continue
                }
                body += parseAssignment()
                while (at(";") || at(",")) pos++
                // also swallow `in` remnants
                if (atIdent("in")) pos++
            }
            expectSym("}")
            return Ast.Closure(params, body)
        }

        private fun parsePrimary(): Ast.Expr {
            return when (val t = peek()) {
                is Tok.Num -> {
                    pos++
                    Ast.Literal(t.v)
                }
                is Tok.Str -> {
                    pos++
                    val parts = parseStringParts(t.raw)
                    if (parts.size == 1 && parts[0] is Ast.StrText) {
                        Ast.Literal((parts[0] as Ast.StrText).text)
                    } else {
                        Ast.StringLit(parts)
                    }
                }
                is Tok.Ident -> {
                    pos++
                    when (t.v) {
                        "true" -> Ast.Literal(true)
                        "false" -> Ast.Literal(false)
                        "nil" -> Ast.Literal(null)
                        else -> {
                            // Color.gray style is handled as Member after this
                            Ast.Ident(t.v)
                        }
                    }
                }
                is Tok.Sym -> when (t.v) {
                    "(" -> {
                        pos++
                        val e = parseExpr()
                        expectSym(")")
                        e
                    }
                    "[" -> {
                        // array / dictionary literal — keep as opaque literal
                        val start = pos
                        skipBrackets()
                        Ast.Literal("array@$start")
                    }
                    "{" -> parseClosure()
                    "." -> {
                        // leading-dot member: .largeTitle, .gray, .leading
                        pos++
                        val name = expectIdent()
                        Ast.Member(null, name)
                    }
                    else -> {
                        pos++
                        Ast.Literal(null)
                    }
                }
                else -> {
                    pos++
                    Ast.Literal(null)
                }
            }
        }

        private fun skipBrackets() {
            expectSym("[")
            var depth = 1
            while (pos < toks.size && depth > 0) {
                val t = next()
                when {
                    t is Tok.Sym && t.v == "[" -> depth++
                    t is Tok.Sym && t.v == "]" -> depth--
                }
            }
        }
    }

    private fun parseStringParts(raw: String): List<Ast.StringPart> {
        val parts = ArrayList<Ast.StringPart>()
        val sb = StringBuilder()
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c == '\\' && i + 1 < raw.length && raw[i + 1] == '(') {
                if (sb.isNotEmpty()) {
                    parts += Ast.StrText(unescape(sb.toString()))
                    sb.setLength(0)
                }
                // find matching close paren
                var depth = 0
                val start = i + 1
                var j = start
                while (j < raw.length) {
                    if (raw[j] == '(') depth++
                    else if (raw[j] == ')') {
                        depth--
                        if (depth == 0) break
                    }
                    j++
                }
                val exprSrc = raw.substring(start + 1, j.coerceAtMost(raw.length))
                val sub = Parser(lex(exprSrc) + Tok.Eof)
                runCatching {
                    parts += Ast.StrExpr(sub.parseExpr())
                }.onFailure {
                    parts += Ast.StrText(exprSrc)
                }
                i = j + 1
                continue
            }
            sb.append(c)
            i++
        }
        if (sb.isNotEmpty()) parts += Ast.StrText(unescape(sb.toString()))
        return parts
    }

    private fun unescape(s: String): String =
        s.replace("\\n", "\n").replace("\\t", "\t").replace("\\\"", "\"").replace("\\\\", "\\")

    private fun parse(sources: List<String>): Ast.Struct? {
        val structs = ArrayList<Ast.Struct>()
        for (src in sources) {
            val cleaned = src.lineSequence()
                .filter { !it.trimStart().startsWith("import ") }
                .joinToString("\n")
            runCatching {
                structs += Parser(lex(cleaned) + Tok.Eof).parseFile()
            }.onFailure { return null }
        }
        if (structs.isEmpty()) return null

        // Prefer @main App's WindowGroup content's root view type; else a View with body.
        val app = structs.firstOrNull { it.superTypes.any { s -> s == "App" } }
        if (app?.body != null) {
            val rootExpr = extractWindowContent(app.body)
            val rootStruct = resolveRootStruct(rootExpr, structs)
            if (rootStruct != null) return rootStruct
            // WindowGroup { InlineView }
            if (rootExpr != null) {
                return Ast.Struct("Root", listOf("View"), emptyList(), rootExpr)
            }
        }
        return structs.firstOrNull { it.superTypes.any { s -> s == "View" } && it.body != null }
    }

    private fun extractWindowContent(body: Ast.Expr): Ast.Expr? {
        // body: some Scene { WindowGroup { ContentView() } } or just WindowGroup { ... }
        val unwrapped = when {
            body is Ast.Closure && body.body.size == 1 && body.params.isEmpty() -> body.body[0]
            else -> body
        }
        val calls = collectCalls(unwrapped)
        val wg = calls.firstOrNull {
            val calleeName = calleeName(it.callee)
            calleeName == "WindowGroup" || calleeName == "Window"
        }
        if (wg != null) {
            val content = wg.trailing.firstOrNull() ?: wg.args.firstOrNull()?.value
            return when {
                content is Ast.Closure && content.body.size == 1 && content.params.isEmpty() -> content.body[0]
                else -> content
            }
        }
        return unwrapped
    }

    private fun collectCalls(e: Ast.Expr): List<Ast.Call> {
        val out = ArrayList<Ast.Call>()
        fun walk(x: Ast.Expr?) {
            when (x) {
                is Ast.Call -> {
                    out += x
                    walk(x.callee)
                    x.args.forEach { walk(it.value) }
                    x.trailing.forEach { walk(it) }
                }
                is Ast.Member -> walk(x.target)
                is Ast.Binary -> {
                    walk(x.left); walk(x.right)
                }
                is Ast.Unary -> walk(x.expr)
                is Ast.Ternary -> {
                    walk(x.cond); walk(x.thenE); walk(x.elseE)
                }
                is Ast.InfixDots -> {
                    walk(x.base)
                    x.calls.forEach { walk(it) }
                }
                is Ast.Closure -> x.body.forEach { walk(it) }
                is Ast.StringLit -> x.parts.forEach {
                    if (it is Ast.StrExpr) walk(it.expr)
                }
                else -> {}
            }
        }
        walk(e)
        return out
    }

    private fun calleeName(e: Ast.Expr): String = when (e) {
        is Ast.Ident -> e.name
        is Ast.Member -> e.name
        else -> ""
    }

    private fun resolveRootStruct(expr: Ast.Expr?, structs: List<Ast.Struct>): Ast.Struct? {
        if (expr == null) return null
        val unwrapped = when {
            expr is Ast.Closure && expr.body.size == 1 && expr.params.isEmpty() -> expr.body[0]
            else -> expr
        }
        // ContentView()
        val name = when (unwrapped) {
            is Ast.Call -> calleeName(unwrapped.callee)
            is Ast.Ident -> unwrapped.name
            is Ast.Member -> unwrapped.name
            else -> null
        } ?: return null
        return structs.firstOrNull { it.name == name && it.body != null }
    }

    // ------------------------------------------------------------------
    // Evaluator / ViewNode builder
    // ------------------------------------------------------------------

    private class ViewNodeBuilder(private val session: PreviewSession) {
        private var idSeq = 0
        private val rootStruct: Ast.Struct? = null

        fun buildRoot(struct: Ast.Struct, session: PreviewSession): JsonElement {
            // Seed state from @State defaults
            for (p in struct.stateProps) {
                if (p.name !in session.state) {
                    session.state[p.name] = p.initial
                }
            }
            val body = struct.body ?: throw SwiftUiPreviewException("View ${struct.name} has no body")
            val core = unwrapViewBody(body)
            val node = buildView(core, "root", struct)
            return buildJsonObject {
                put("type", node.type)
                put("id", node.id)
                put("props", node.props)
                put("modifiers", node.modifiers)
                put("children", node.children)
            }
        }

        /** `var body: some View { VStack {...} }` parses as a one-expression Closure — unwrap it. */
        private fun unwrapViewBody(e: Ast.Expr): Ast.Expr {
            var cur = e
            while (cur is Ast.Closure && cur.body.size == 1 && cur.params.isEmpty()) {
                cur = cur.body[0]
            }
            return cur
        }

        private data class BuiltNode(
            val type: String,
            val id: String,
            val props: JsonObject,
            val modifiers: JsonArray,
            val children: JsonArray,
        )

        private fun nextId(path: String): String {
            idSeq++
            return if (path.isEmpty()) "root/$idSeq" else "$path/$idSeq"
        }

        private fun buildView(expr: Ast.Expr, path: String, struct: Ast.Struct?): BuiltNode {
            val (coreRaw, modifierCalls) = flattenModifiers(unwrapViewBody(expr))
            val core = unwrapViewBody(coreRaw)

            // Unwrap a root body that is just a call to another View type: ContentView()
            if (core is Ast.Call && core.trailing.isEmpty() && core.args.isEmpty()) {
                val name = calleeName(core.callee)
                val target = struct?.let { /* self */ null }
                // If it's a known container, fall through; if it's a View type, we can't recurse without
                // the other struct — treat unknown Ident calls as empty containers only when known.
                if (name in KNOWN_VIEWS) {
                    return buildKnownView(name, emptyList(), emptyList(), path, struct, modifierCalls)
                }
            }

            return when (core) {
                is Ast.Call -> {
                    val name = calleeName(core.callee)
                    if (name in KNOWN_VIEWS) {
                        buildKnownView(name, core.args, core.trailing, path, struct, emptyList())
                    } else if (core.callee is Ast.Ident && core.callee.name.firstOrNull()?.isUpperCase() == true) {
                        // Unknown custom view: emit empty group so the tree still renders
                        BuiltNode("Group", nextId(path), buildJsonObject {}, buildJsonArray {}, buildJsonArray {})
                    } else {
                        // Text("...") already handled; other calls
                        BuiltNode("Group", nextId(path), buildJsonObject {}, buildJsonArray {}, buildJsonArray {})
                    }
                }
                is Ast.StringLit, is Ast.Literal -> {
                    val text = evalToString(core)
                    BuiltNode(
                        "Text", nextId(path),
                        buildJsonObject { put("text", text) },
                        buildJsonArray {}, buildJsonArray {},
                    )
                }
                else -> BuiltNode("Group", nextId(path), buildJsonObject {}, buildJsonArray {}, buildJsonArray {})
            }.let { applyModifiers(it, modifierCalls, path, struct) }
        }

        private fun flattenModifiers(expr: Ast.Expr): Pair<Ast.Expr, List<Ast.Call>> {
            // SwiftUI source uses .padding(24) postfix calls. Our parser folds those as Call(Member(...)).
            // Collect trailing modifier calls into a list.
            val mods = ArrayList<Ast.Call>()
            var cur = expr
            while (true) {
                when {
                    cur is Ast.Call && cur.callee is Ast.Member && (cur.callee as Ast.Member).name in MODIFIER_NAMES -> {
                        mods += cur
                        cur = (cur.callee as Ast.Member).target ?: break
                    }
                    cur is Ast.Call && cur.callee is Ast.Ident && (cur.callee as Ast.Ident).name in MODIFIER_NAMES -> {
                        mods += cur
                        // no target
                        break
                    }
                    else -> break
                }
            }
            return cur to mods.asReversed()
        }

        private fun applyModifiers(
            node: BuiltNode,
            mods: List<Ast.Call>,
            path: String,
            struct: Ast.Struct?,
        ): BuiltNode {
            if (mods.isEmpty()) return node
            val arr = buildJsonArray {
                // preserve existing
                node.modifiers.forEach { add(it) }
                for (m in mods) {
                    val name = calleeName(m.callee)
                    val args = buildJsonObject {
                        for (a in m.args) {
                            val key = a.label ?: "value"
                            put(key, evalToJson(a.value))
                        }
                    }
                    add(buildJsonObject {
                        put("kind", name)
                        put("args", args)
                    })
                }
            }
            return node.copy(modifiers = arr)
        }

        private fun buildKnownView(
            name: String,
            args: List<Ast.Arg>,
            trailing: List<Ast.Expr>,
            path: String,
            struct: Ast.Struct?,
            modifierCalls: List<Ast.Call>,
        ): BuiltNode {
            val id = nextId(path)
            val childrenList = ArrayList<BuiltNode>()

            // trailing closures are child views or action scripts
            var actionScript: String? = null
            var labelExpr: Ast.Expr? = null
            // Long form: Button(action: {...}, label: {...})
            args.firstOrNull { it.label == "action" }?.let { arg ->
                actionScript = closureScript(arg.value)
            }
            args.firstOrNull { it.label == "label" }?.let { arg ->
                labelExpr = unwrapViewBody(arg.value)
            }
            for (t in trailing) {
                when {
                    name == "Button" && actionScript == null && looksLikeAction(t) -> {
                        actionScript = closureScript(t)
                    }
                    name == "Button" && labelExpr == null && !looksLikeAction(t) -> labelExpr = t
                    name == "Toggle" && actionScript == null && looksLikeAction(t) -> {
                        actionScript = closureScript(t)
                    }
                    t is Ast.Closure && (name != "Button" || labelExpr == null || !looksLikeAction(t)) -> {
                        // ViewBuilder content
                        if (name == "Button" && looksLikeAction(t)) {
                            if (actionScript == null) actionScript = closureScript(t)
                        } else {
                            for (childExpr in t.body) {
                                childrenList += buildView(childExpr, id, struct)
                            }
                            if (name == "Button" && labelExpr == null) {
                                // children already added
                            }
                        }
                    }
                    else -> {
                        childrenList += buildView(t, id, struct)
                    }
                }
            }

            val props = buildJsonObject {
                when (name) {
                    "Text" -> {
                        val textArg = args.firstOrNull { it.label == "text" } ?: args.firstOrNull()
                        val text = textArg?.let { evalToString(it.value) } ?: ""
                        put("text", text)
                    }
                    "Button" -> {
                        // Label as first arg string or trailing label
                        val title = args.firstOrNull()?.let { evalToString(it.value) }
                        if (title != null && childrenList.isEmpty()) {
                            // label is the string title
                        }
                        val cb = session.nextCallbackId++
                        session.callbacks[cb] = CallbackBinding.VoidAction(actionScript ?: "")
                        put("onTap", cb)
                        if (title != null) {
                            // emit label child
                        }
                    }
                    "Toggle" -> {
                        val title = args.firstOrNull { it.label == null || it.label == "_" }?.let { evalToString(it.value) }
                        if (title != null) {
                            childrenList.add(
                                0,
                                BuiltNode(
                                    "Text", "$id/label",
                                    buildJsonObject { put("text", title) },
                                    buildJsonArray {}, buildJsonArray {},
                                ),
                            )
                        }
                        val binding = args.firstOrNull { it.label == "isOn" }?.value
                        val stateName = bindingName(binding)
                        val current = (session.state[stateName] as? Boolean) ?: false
                        put("isOn", current)
                        val cb = session.nextCallbackId++
                        if (stateName != null) {
                            session.callbacks[cb] = CallbackBinding.BoolBinding(stateName)
                        }
                        put("onChange", cb)
                    }
                    "TextField", "SecureField" -> {
                        val placeholder = args.firstOrNull()?.let { evalToString(it.value) } ?: ""
                        val binding = args.firstOrNull { it.label == "text" }?.value
                        val stateName = bindingName(binding)
                        val current = session.state[stateName]?.toString() ?: ""
                        put("text", current)
                        put("placeholder", placeholder)
                        if (name == "SecureField") put("secure", true)
                        val cb = session.nextCallbackId++
                        if (stateName != null) {
                            session.callbacks[cb] = CallbackBinding.StringBinding(stateName)
                        }
                        put("onChange", cb)
                    }
                    "Image" -> {
                        val sys = args.firstOrNull { it.label == "systemName" }?.let { evalToString(it.value) }
                            ?: args.firstOrNull()?.let { evalToString(it.value) }
                        if (sys != null) put("systemName", sys)
                    }
                    "VStack", "HStack", "ZStack" -> {
                        val spacing = args.firstOrNull { it.label == "spacing" }?.let { evalToNumber(it.value) }
                        if (spacing != null) put("spacing", spacing)
                        val alignment = args.firstOrNull { it.label == "alignment" }?.let { evalToString(it.value) }
                        if (alignment != null) put("alignment", alignment)
                    }
                    "List", "Section", "Group", "NavigationView", "NavigationStack", "Form" -> {
                        val title = args.firstOrNull()?.let { evalToString(it.value) }
                        if (title != null) put("title", title)
                    }
                    "Color" -> {
                        val c = args.firstOrNull()?.let { evalToString(it.value) }
                        if (c != null) put("color", c)
                    }
                    "Slider" -> {
                        val v = args.firstOrNull { it.label == "value" }?.let { evalToNumber(it.value) } ?: 0.0
                        put("value", v)
                        val cb = session.nextCallbackId++
                        put("onChange", cb)
                    }
                }
            }

            // Button with string title → child Text
            if (name == "Button") {
                val title = args.firstOrNull { it.label == null }?.let { evalToString(it.value) }
                if (labelExpr != null && childrenList.isEmpty()) {
                    childrenList += buildView(labelExpr!!, "$id/label", struct)
                } else if (title != null && childrenList.isEmpty()) {
                    childrenList += BuiltNode(
                        "Text", "$id/label",
                        buildJsonObject { put("text", title) },
                        buildJsonArray {}, buildJsonArray {},
                    )
                }
            }

            // ForEach(0..<3) { i in ... }
            if (name == "ForEach") {
                return buildForEach(args, trailing, id, path, struct, modifierCalls)
            }

            val childrenJson = buildJsonArray {
                for (c in childrenList) {
                    add(buildJsonObject {
                        put("type", c.type)
                        put("id", c.id)
                        put("props", c.props)
                        put("modifiers", c.modifiers)
                        put("children", c.children)
                    })
                }
            }

            val base = BuiltNode(name, id, props, buildJsonArray {}, childrenJson)
            return applyModifiers(base, modifierCalls, path, struct)
        }

        private fun buildForEach(
            args: List<Ast.Arg>,
            trailing: List<Ast.Expr>,
            id: String,
            path: String,
            struct: Ast.Struct?,
            modifierCalls: List<Ast.Call>,
        ): BuiltNode {
            val rangeArg = args.firstOrNull()?.value
            var start = 0
            var end = 0
            if (rangeArg is Ast.Binary && (rangeArg.op == "..<" || rangeArg.op == "...")) {
                start = evalToNumber(rangeArg.left)?.toInt() ?: 0
                end = evalToNumber(rangeArg.right)?.toInt() ?: 0
                if (rangeArg.op == "...") end += 1
            }
            val itemParam = trailing.firstOrNull { it is Ast.Closure }?.let { (it as Ast.Closure).params.firstOrNull() }
            val body = (trailing.firstOrNull { it is Ast.Closure } as? Ast.Closure)?.body ?: emptyList()
            val kids = buildJsonArray {
                for (i in start until end) {
                    val saved = itemParam?.let { session.state[it] }
                    if (itemParam != null) session.state[itemParam] = i
                    for (childExpr in body) {
                        val n = buildView(childExpr, "$id/$i", struct)
                        add(buildJsonObject {
                            put("type", n.type)
                            put("id", n.id)
                            put("props", n.props)
                            put("modifiers", n.modifiers)
                            put("children", n.children)
                        })
                    }
                    if (itemParam != null) {
                        if (saved != null) session.state[itemParam] = saved else session.state.remove(itemParam)
                    }
                }
            }
            val base = BuiltNode("ForEach", id, buildJsonObject { put("count", end - start) }, buildJsonArray {}, kids)
            return applyModifiers(base, modifierCalls, path, struct)
        }

        private fun looksLikeAction(e: Ast.Expr): Boolean {
            if (e !is Ast.Closure) return false
            if (e.body.isEmpty()) return true
            // Action if body is assignment-like expressions rather than view builders
            return e.body.all {
                it is Ast.Binary && (it.op == "=" || it.op == "+=" || it.op == "-=" || it.op == "*=" || it.op == "/=") ||
                    it is Ast.Call && calleeName(it.callee) !in KNOWN_VIEWS
            }
        }

        private fun closureScript(e: Ast.Expr): String {
            if (e !is Ast.Closure) return ""
            return e.body.joinToString("; ") { exprToScript(it) }
        }

        private fun exprToScript(e: Ast.Expr): String = when (e) {
            is Ast.Binary -> "${exprToScript(e.left)} ${e.op} ${exprToScript(e.right)}"
            is Ast.Ident -> e.name
            is Ast.Literal -> when (val v = e.value) {
                is String -> "\"$v\""
                else -> v?.toString() ?: "nil"
            }
            is Ast.Member -> "${e.target?.let { exprToScript(it) } ?: ""}.${e.name}"
            is Ast.Unary -> if (e.op == "$") "$" + exprToScript(e.expr) else "${e.op}${exprToScript(e.expr)}"
            is Ast.Ternary -> "${exprToScript(e.cond)} ? ${exprToScript(e.thenE)} : ${exprToScript(e.elseE)}"
            is Ast.StringLit -> e.parts.joinToString("") {
                when (it) {
                    is Ast.StrText -> it.text
                    is Ast.StrExpr -> exprToScript(it.expr)
                }
            }
            else -> ""
        }

        private fun bindingName(e: Ast.Expr?): String? = when (e) {
            is Ast.Unary -> when {
                e.op == "$" -> when (val inner = e.expr) {
                    is Ast.Ident -> inner.name
                    is Ast.Member -> inner.name
                    else -> null
                }
                else -> null
            }
            is Ast.Member -> e.name
            is Ast.Ident -> e.name
            else -> null
        }

        private fun evalToString(e: Ast.Expr?): String {
            if (e == null) return ""
            return when (e) {
                is Ast.Literal -> e.value?.toString() ?: ""
                is Ast.StringLit -> e.parts.joinToString("") {
                    when (it) {
                        is Ast.StrText -> it.text
                        is Ast.StrExpr -> evalToString(it.expr)
                    }
                }
                is Ast.Ident -> session.state[e.name]?.toString() ?: e.name
                is Ast.Member -> e.name
                is Ast.Binary -> {
                    val l = evalToString(e.left)
                    val r = evalToString(e.right)
                    if (e.op == "+") l + r else evalToNumber(e)?.toString() ?: "$l${e.op}$r"
                }
                is Ast.Ternary -> {
                    val c = evalToBool(e.cond)
                    evalToString(if (c) e.thenE else e.elseE)
                }
                is Ast.Call -> calleeName(e.callee)
                else -> ""
            }
        }

        private fun evalToNumber(e: Ast.Expr?): Double? {
            if (e == null) return null
            return when (e) {
                is Ast.Literal -> when (val v = e.value) {
                    is Number -> v.toDouble()
                    is Boolean -> if (v) 1.0 else 0.0
                    is String -> v.toDoubleOrNull()
                    else -> null
                }
                is Ast.Ident -> coerceNumber(session.state[e.name])
                is Ast.Binary -> {
                    val l = evalToNumber(e.left) ?: return null
                    val r = evalToNumber(e.right) ?: return null
                    when (e.op) {
                        "+" -> l + r
                        "-" -> l - r
                        "*" -> l * r
                        "/" -> if (r == 0.0) null else l / r
                        else -> null
                    }
                }
                is Ast.Unary -> {
                    val v = evalToNumber(e.expr) ?: return null
                    if (e.op == "-") -v else v
                }
                is Ast.Ternary -> evalToNumber(if (evalToBool(e.cond)) e.thenE else e.elseE)
                is Ast.Member -> null
                else -> null
            }
        }

        private fun evalToBool(e: Ast.Expr?): Boolean {
            if (e == null) return false
            return when (e) {
                is Ast.Literal -> when (val v = e.value) {
                    is Boolean -> v
                    is Number -> v.toDouble() != 0.0
                    is String -> v.equals("true", true)
                    else -> false
                }
                is Ast.Ident -> when (val v = session.state[e.name]) {
                    is Boolean -> v
                    is Number -> v.toDouble() != 0.0
                    else -> false
                }
                is Ast.Unary -> if (e.op == "!") !evalToBool(e.expr) else evalToBool(e.expr)
                is Ast.Binary -> when (e.op) {
                    "==" -> evalToString(e.left) == evalToString(e.right)
                    "!=" -> evalToString(e.left) != evalToString(e.right)
                    "&&" -> evalToBool(e.left) && evalToBool(e.right)
                    "||" -> evalToBool(e.left) || evalToBool(e.right)
                    "<" -> (evalToNumber(e.left) ?: 0.0) < (evalToNumber(e.right) ?: 0.0)
                    ">" -> (evalToNumber(e.left) ?: 0.0) > (evalToNumber(e.right) ?: 0.0)
                    "<=" -> (evalToNumber(e.left) ?: 0.0) <= (evalToNumber(e.right) ?: 0.0)
                    ">=" -> (evalToNumber(e.left) ?: 0.0) >= (evalToNumber(e.right) ?: 0.0)
                    else -> false
                }
                is Ast.Ternary -> evalToBool(if (evalToBool(e.cond)) e.thenE else e.elseE)
                else -> false
            }
        }

        private fun evalToJson(e: Ast.Expr?): JsonElement {
            if (e == null) return JsonNull
            return when {
                e is Ast.Literal -> when (val v = e.value) {
                    null -> JsonNull
                    is Number -> JsonPrimitive(v)
                    is Boolean -> JsonPrimitive(v)
                    else -> JsonPrimitive(v.toString())
                }
                e is Ast.StringLit -> JsonPrimitive(evalToString(e))
                e is Ast.Unary && e.op == "$" -> JsonPrimitive(bindingName(e) ?: "")
                else -> {
                    val n = evalToNumber(e)
                    if (n != null && e !is Ast.StringLit && e !is Ast.Literal) {
                        JsonPrimitive(n)
                    } else {
                        JsonPrimitive(evalToString(e))
                    }
                }
            }
        }
    }

    private fun coerceNumber(v: Any?): Double? = when (v) {
        is Number -> v.toDouble()
        is Boolean -> if (v) 1.0 else 0.0
        is String -> v.toDoubleOrNull()
        else -> null
    }

    private fun normalizeNumber(v: Double): Any =
        if (v == v.toLong().toDouble()) v.toLong() else v

    private val KNOWN_VIEWS = setOf(
        "VStack", "HStack", "ZStack", "Group", "List", "Section", "Form",
        "Text", "Button", "Toggle", "TextField", "SecureField", "Image", "Label",
        "Spacer", "Divider", "Color", "Circle", "Rectangle", "Capsule", "RoundedRectangle",
        "NavigationView", "NavigationStack", "NavigationView", "ForEach", "Slider",
        "ProgressView", "Link", "Toggle", "EmptyView", "TupleView",
    )

    private val MODIFIER_NAMES = setOf(
        "padding", "frame", "foregroundColor", "foregroundStyle", "background", "font",
        "bold", "italic", "onTapGesture", "navigationTitle", "disabled", "opacity",
        "multilineTextAlignment", "cornerRadius", "border", "shadow", "offset", "scaleEffect",
        "listStyle", "buttonStyle", "textFieldStyle", "toggleStyle", "navigationBarTitle",
        "tint", "clipped", "overlay", "zIndex", "animation", "transition", "id", "tag",
        "help", "accessibilityLabel", "keyboardType", "autocapitalization", "disableAutocorrection",
    )
}
