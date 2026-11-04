package dev.ide.core.runtime

import dev.ide.build.BuildContext
import dev.ide.build.BuildLogEntry
import dev.ide.build.BuildLogLevel
import dev.ide.build.ProgramIo
import dev.ide.build.TaskContext
import dev.ide.build.TaskResult
import dev.ide.build.engine.reportToolDiagnostics
import dev.ide.workbench.runtime.RuntimeHost
import dev.ide.workbench.runtime.RuntimeInstallation
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Interactive GUI Bridge runtime support for both:
 *  1. SwiftUI / Cupertino (`SwiftUI.swiftmodule` + `libSwiftUI.a` on-device `ViewHost` bridge)
 *  2. Node.js GUI (`@nodegui/nodegui`, `libui-node`, and `x11` window/widget bridge shim)
 *
 * Both bridges speak the newline-delimited JSON `ViewNode` protocol over stdout and receive
 * user interaction callbacks (`{"id":"...","kind":"void|bool|string|double|int","value":...}`)
 * over stdin, rendering live interactive native Compose windows inside `RunScreen`.
 */
object GuiBridgeRuntime {

    fun isBridgeJsonLine(line: String): Boolean {
        val trimmed = line.trim()
        return trimmed.startsWith("{") &&
            trimmed.endsWith("}") &&
            trimmed.contains("\"type\":") &&
            trimmed.contains("\"children\":")
    }

    /**
     * Ensures the precompiled on-device `SwiftUI.swiftmodule` and `libSwiftUI.a` exist in
     * `<prefix>/lib/swift/android/` (restoring from `developer-runtime/swiftui-sdk` or
     * `/data/local/tmp/swiftui-sdk` if the runtime prefix was freshly extracted).
     */
    fun ensureSwiftUiBridgeSdk(installation: RuntimeInstallation): Boolean = runCatching {
        val swiftAndroidDir = installation.prefix.resolve("lib/swift/android")
        val targetLib = swiftAndroidDir.resolve("libSwiftUI.a")
        val targetModDir = swiftAndroidDir.resolve("SwiftUI.swiftmodule")
        val targetMod = targetModDir.resolve("aarch64-unknown-linux-android.swiftmodule")

        val filesRoot = installation.prefix.parent?.parent?.parent
        val detectedPkg = runCatching {
            var curr: Path? = installation.prefix
            var pkg: String? = null
            while (curr != null) {
                val name = curr.fileName?.toString()
                if (name == "ai.building.dev" || name == "dev.ide.android.dev") {
                    pkg = name
                    break
                }
                curr = curr.parent
            }
            pkg
        }.getOrNull()
        val pkgNames = listOfNotNull(detectedPkg, "ai.building.dev", "dev.ide.android.dev").distinct()
        val dynamicCandidates = pkgNames.flatMap { pkg ->
            listOf(
                java.nio.file.Paths.get("/sdcard/Android/data/$pkg/files/codeassist/swiftui-sdk/android-aarch64"),
                java.nio.file.Paths.get("/sdcard/Android/data/$pkg/files/codeassist/swiftui-sdk"),
                java.nio.file.Paths.get("/sdcard/Android/data/$pkg/files/swiftui-sdk/android-aarch64"),
                java.nio.file.Paths.get("/sdcard/Android/data/$pkg/files/swiftui-sdk"),
                java.nio.file.Paths.get("/data/user/0/$pkg/files/developer-runtime/swiftui-sdk/android-aarch64"),
                java.nio.file.Paths.get("/data/data/$pkg/files/developer-runtime/swiftui-sdk/android-aarch64"),
            )
        }
        val backupCandidates = listOfNotNull(
            filesRoot?.resolve("developer-runtime/swiftui-sdk/android-aarch64"),
            filesRoot?.resolve("developer-runtime/swiftui-sdk"),
        ) + dynamicCandidates + listOfNotNull(
            java.nio.file.Paths.get("/data/local/tmp/swiftui-sdk/android-aarch64"),
            java.nio.file.Paths.get("/data/local/tmp/swiftui-sdk"),
            java.nio.file.Paths.get(System.getProperty("user.home", ""), ".building/swiftui-bridge-sdk/android-aarch64"),
            java.nio.file.Paths.get(System.getProperty("user.home", ""), ".building/swiftui-bridge-sdk"),
        )
        for (candidate in backupCandidates) {
            val backup = if (Files.isDirectory(candidate.resolve("android-aarch64"))) {
                candidate.resolve("android-aarch64")
            } else {
                candidate
            }
            val srcLib = backup.resolve("libSwiftUI.a")
            val srcMod = backup.resolve("SwiftUI.swiftmodule/aarch64-unknown-linux-android.swiftmodule")
                .takeIf { Files.isRegularFile(it) }
                ?: backup.resolve("aarch64-unknown-linux-android.swiftmodule")
                    .takeIf { Files.isRegularFile(it) }
            if (Files.isRegularFile(srcLib) && srcMod != null && Files.isDirectory(swiftAndroidDir)) {
                val needsSync = !Files.isRegularFile(targetLib) ||
                    !Files.isRegularFile(targetMod) ||
                    Files.size(srcLib) != Files.size(targetLib)
                if (needsSync) {
                    Files.createDirectories(targetModDir)
                    Files.copy(srcLib, targetLib, StandardCopyOption.REPLACE_EXISTING)
                    val aarch64Dir = swiftAndroidDir.resolve("aarch64")
                    if (Files.isDirectory(aarch64Dir)) {
                        Files.copy(srcLib, aarch64Dir.resolve("libSwiftUI.a"), StandardCopyOption.REPLACE_EXISTING)
                    }
                    Files.copy(srcMod, targetMod, StandardCopyOption.REPLACE_EXISTING)
                    Files.copy(
                        srcMod,
                        targetModDir.resolve("aarch64-unknown-linux-android28.swiftmodule"),
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                }
                for (extraMod in listOf("Combine", "CoreGraphics", "UIKit")) {
                    val extraSrc = backup.resolve("$extraMod.swiftmodule/aarch64-unknown-linux-android.swiftmodule")
                        .takeIf { Files.isRegularFile(it) }
                        ?: backup.resolve("$extraMod.swiftmodule")
                            .takeIf { Files.isRegularFile(it) }
                    if (extraSrc != null && Files.isRegularFile(extraSrc)) {
                        val extraTargetDir = swiftAndroidDir.resolve("$extraMod.swiftmodule")
                        val extraTarget = extraTargetDir.resolve("aarch64-unknown-linux-android.swiftmodule")
                        if (!Files.isRegularFile(extraTarget)) {
                            Files.createDirectories(extraTargetDir)
                            Files.copy(extraSrc, extraTarget, StandardCopyOption.REPLACE_EXISTING)
                            Files.copy(
                                extraSrc,
                                extraTargetDir.resolve("aarch64-unknown-linux-android28.swiftmodule"),
                                StandardCopyOption.REPLACE_EXISTING,
                            )
                        }
                    }
                }
                return true
            }
        }
        if (Files.isRegularFile(targetLib) && Files.isRegularFile(targetMod)) {
            return true
        }
        if (installation.packId == "host-system") {
            val hostSdk = java.nio.file.Paths.get(System.getProperty("user.home", ""), ".building/swiftui-bridge-sdk")
            if (Files.isRegularFile(hostSdk.resolve("libSwiftUI.a")) && Files.isDirectory(hostSdk.resolve("SwiftUI.swiftmodule"))) {
                return true
            }
        }
        false
    }.getOrDefault(false)

    private val SUPPORTED_SWIFTUI_IMPORTS = setOf(
        "SwiftUI",
        "Foundation",
        "Combine",
        "CoreGraphics",
        "UIKit",
        "AppKit",
        "QuartzCore",
        "Observation",
        "Swift",
        "Darwin",
        "Glibc",
        "Android",
        "AudioToolbox",
        "AVFoundation",
        "AVKit",
        "PhotosUI",
        "WebKit",
        "SwiftData",
        "CoreData",
        "Charts",
        "MapKit",
        "CoreLocation",
        "StoreKit",
        "UserNotifications",
        "AuthenticationServices",
        "LocalAuthentication",
        "UniformTypeIdentifiers",
        "CryptoKit",
        "Security",
        "OSLog",
        "os",
        "Network",
        "SafariServices",
        "MessageUI",
        "TipKit",
        "WidgetKit",
        "CoreHaptics",
        "CoreImage",
        "ImageIO",
        "Vision",
        "Accelerate",
        "simd",
        "CoreText",
        "CoreServices",
        "MobileCoreServices",
        "Contacts",
        "EventKit",
        "QuickLook",
        "PDFKit",
        "MusicKit",
        "DeveloperToolsSupport",
    )

    internal val CUPERTINO_BRIDGE_PRELUDE: String = CUPERTINO_BRIDGE_PRELUDE_SOURCE

    internal fun stripPreviewMacros(text: String): String {
        if (!text.contains("#Preview")) return text
        val out = StringBuilder(text.length)
        var i = 0
        val n = text.length
        while (i < n) {
            val idx = text.indexOf("#Preview", i)
            if (idx == -1) {
                out.append(text, i, n)
                break
            }
            out.append(text, i, idx)
            var j = idx + "#Preview".length
            while (j < n && text[j].isWhitespace()) j++
            if (j < n && text[j] == '(') {
                var depth = 1
                j++
                while (j < n && depth > 0) {
                    when (text[j]) {
                        '(' -> depth++
                        ')' -> depth--
                    }
                    j++
                }
            }
            while (j < n && text[j].isWhitespace()) j++
            if (j < n && text[j] == '{') {
                var depth = 1
                j++
                while (j < n && depth > 0) {
                    when (text[j]) {
                        '{' -> depth++
                        '}' -> depth--
                    }
                    j++
                }
            }
            out.append("// [sanitized #Preview]")
            i = j
        }
        return out.toString()
    }

    /**
     * Preserves all `struct`/`class`/`enum`/`extension` declarations intact without deleting
     * user types that reference UIKit/AppKit/Canvas symbols (which are provided by
     * [CUPERTINO_BRIDGE_PRELUDE]).
     */
    internal fun dropUnsupportedDeclarations(text: String): String = text

    internal fun discoverInternalSwiftModules(projectRoot: Path): Set<String> {
        val mods = LinkedHashSet<String>()
        runCatching {
            Files.walk(projectRoot, 4).use { stream ->
                stream.filter { it.fileName?.toString() == "Package.swift" && Files.isRegularFile(it) }
                    .forEach { pkg ->
                        val text = runCatching { pkg.readText() }.getOrDefault("")
                        Regex("""name:\s*"([A-Za-z0-9_]+)"""").findAll(text).forEach { m ->
                            mods.add(m.groupValues[1])
                        }
                    }
            }
        }
        return mods
    }

    internal fun discoverXcassetsColors(projectRoot: Path): Map<String, String> {
        val colors = LinkedHashMap<String, String>()
        fun parseComponent(raw: String): Double {
            val s = raw.trim()
            if (s.startsWith("0x", ignoreCase = true)) {
                return (s.substring(2).toIntOrNull(16) ?: 0).coerceIn(0, 255) / 255.0
            }
            val d = s.toDoubleOrNull() ?: return 0.0
            return if (!s.contains('.') && d > 1.0) (d / 255.0).coerceIn(0.0, 1.0) else d.coerceIn(0.0, 1.0)
        }
        runCatching {
            Files.walk(projectRoot, 6).use { stream ->
                stream.filter { p ->
                    Files.isDirectory(p) && p.fileName?.toString()?.endsWith(".colorset") == true
                }.forEach { colorsetDir ->
                    val name = colorsetDir.fileName.toString().removeSuffix(".colorset")
                    val contentsFile = colorsetDir.resolve("Contents.json")
                    if (Files.isRegularFile(contentsFile)) {
                        val json = runCatching { contentsFile.readText() }.getOrDefault("")
                        val rMatch = Regex(""""red"\s*:\s*"([^"]+)"""").find(json)
                        val gMatch = Regex(""""green"\s*:\s*"([^"]+)"""").find(json)
                        val bMatch = Regex(""""blue"\s*:\s*"([^"]+)"""").find(json)
                        val aMatch = Regex(""""alpha"\s*:\s*"([^"]+)"""").find(json)
                        if (rMatch != null && gMatch != null && bMatch != null) {
                            val r = parseComponent(rMatch.groupValues[1])
                            val g = parseComponent(gMatch.groupValues[1])
                            val b = parseComponent(bMatch.groupValues[1])
                            val a = aMatch?.let { parseComponent(it.groupValues[1]) } ?: 1.0
                            colors[name] = String.format(
                                java.util.Locale.US,
                                "Color(red: %.4f, green: %.4f, blue: %.4f, opacity: %.4f)",
                                r,
                                g,
                                b,
                                a,
                            )
                        }
                    }
                }
            }
        }
        return colors
    }

    internal fun synthesizeXcassetsColorExtensions(xcassetsColors: Map<String, String>): String? {
        if (xcassetsColors.isEmpty()) return null
        val reserved = setOf(
            "red", "green", "blue", "orange", "yellow", "purple", "pink", "gray", "clear",
            "primary", "secondary", "black", "white", "teal", "cyan", "indigo", "mint",
            "brown", "accentColor", "link", "lightGray", "darkGray",
        )
        val entries = LinkedHashMap<String, String>()
        for ((rawName, colorExpr) in xcassetsColors) {
            val parts = rawName.split(Regex("""[^A-Za-z0-9]+""")).filter { it.isNotEmpty() }
            if (parts.isEmpty()) continue
            val camel = parts.mapIndexed { idx, part ->
                if (idx == 0) part.replaceFirstChar { it.lowercaseChar() }
                else part.replaceFirstChar { it.uppercaseChar() }
            }.joinToString("")
            if (camel.isNotEmpty() && camel.first().isLetter() && camel !in reserved) {
                entries[camel] = colorExpr
            }
        }
        if (entries.isEmpty()) return null
        val sb = StringBuilder()
        sb.appendLine("import SwiftUI")
        sb.appendLine("public extension Color {")
        for ((ident, expr) in entries) {
            sb.appendLine("    static let $ident = $expr")
        }
        sb.appendLine("}")
        sb.appendLine("public extension ShapeStyle where Self == Color {")
        for ((ident, _) in entries) {
            sb.appendLine("    static var $ident: Color { .$ident }")
        }
        sb.appendLine("}")
        return sb.toString()
    }

    internal fun discoverLocalizableStrings(projectRoot: Path): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        fun cleanValue(raw: String): String =
            raw.replace("\u2028", "\\n").replace("\u2029", "\\n").replace("\r\n", "\\n").replace("\n", "\\n")
        runCatching {
            Files.walk(projectRoot, 6).use { stream ->
                stream.filter { p ->
                    Files.isRegularFile(p) && (
                        p.fileName?.toString()?.endsWith(".xcstrings") == true ||
                            p.fileName?.toString()?.endsWith(".strings") == true
                        ) && !p.toString().replace('\\', '/').contains("/.building/")
                }.forEach { file ->
                    val text = runCatching { file.readText() }.getOrDefault("")
                    if (file.fileName.toString().endsWith(".xcstrings")) {
                        Regex(
                            """"([A-Za-z0-9_.-]+)"\s*:\s*\{\s*(?:"extractionState"\s*:\s*"[^"]*"\s*,\s*)?"localizations"\s*:\s*\{\s*"en"\s*:\s*\{\s*"stringUnit"\s*:\s*\{\s*"state"\s*:\s*"[^"]*"\s*,\s*"value"\s*:\s*"((?:\\.|[^"\\])*)""""
                        ).findAll(text).forEach { m ->
                            out[m.groupValues[1]] = cleanValue(m.groupValues[2])
                        }
                    } else {
                        val isPreferred = file.parent?.fileName?.toString()?.let {
                            it.equals("en.lproj", ignoreCase = true) || it.equals("Base.lproj", ignoreCase = true)
                        } ?: true
                        Regex("""(?m)^\s*"([A-Za-z0-9_.-]+)"\s*=\s*"((?:\\.|[^"\\])*)"\s*;""").findAll(text).forEach { m ->
                            val k = m.groupValues[1]
                            if (isPreferred || k !in out) {
                                out[k] = cleanValue(m.groupValues[2])
                            }
                        }
                    }
                }
            }
        }
        return out
    }

    internal fun sanitizeSwiftUiSource(
        text: String,
        internalModules: Set<String> = emptySet(),
        hasMacroServer: Boolean = false,
        xcassetsColors: Map<String, String> = emptyMap(),
        localizableStrings: Map<String, String> = emptyMap(),
    ): String {
        var t = stripPreviewMacros(text)
        t = dropUnsupportedDeclarations(t)
        t = t.replace(Regex("""#if\s+os\(macOS\)"""), "#if false")
        val foundationReexportMods = setOf(
            "AppKit", "UIKit", "AVFoundation", "SwiftData", "CoreData", "Combine",
            "UserNotifications", "CoreLocation", "MapKit", "StoreKit", "Network",
        )
        val sanitizedMods = internalModules + listOf(
            "AppKit", "UIKit", "QuartzCore",
            "AudioToolbox", "AVFoundation", "AVKit", "PhotosUI", "WebKit", "Observation", "Darwin",
            "SwiftData", "CoreData", "Charts", "MapKit", "CoreLocation", "StoreKit",
            "UserNotifications", "AuthenticationServices", "LocalAuthentication",
            "UniformTypeIdentifiers", "CryptoKit", "Security", "OSLog", "os", "Network",
            "SafariServices", "MessageUI", "TipKit", "WidgetKit", "CoreHaptics",
            "CoreImage", "ImageIO", "Vision", "Accelerate", "simd", "CoreText",
            "CoreServices", "MobileCoreServices", "Contacts", "EventKit", "QuickLook",
            "PDFKit", "MusicKit", "DeveloperToolsSupport",
        )
        for (mod in sanitizedMods) {
            val replacement = if (mod in foundationReexportMods) {
                "import Foundation // [sanitized import $mod]"
            } else {
                "// [sanitized import $mod]"
            }
            t = t.replace(
                Regex("""(?m)^\s*(?:@\w+(?:\([^)]*\))?\s+)*import\s+(?:(?:struct|class|enum|protocol|typealias|func|var|let)\s+)?${Regex.escape(mod)}\b.*$"""),
                replacement,
            )
        }
        if (t.contains("import SwiftUI") && !t.contains("import Foundation")) {
            t = t.replaceFirst("import SwiftUI", "import Foundation\nimport SwiftUI")
        }
        t = Regex("""@Model\s+((?:(?:public|internal|private|fileprivate|final)\s+)*class\s+\w+)([^{]*)\{""").replace(t) { m ->
            val decl = m.groupValues[1]
            val rest = m.groupValues[2]
            when {
                rest.contains("PersistentModel") -> "/* @Model */ $decl$rest{"
                rest.contains(":") -> "/* @Model */ $decl${rest.trimEnd()}, PersistentModel {"
                else -> "/* @Model */ $decl: PersistentModel $rest{"
            }
        }
        t = t.replace(Regex("""@Attribute(?:\([^)]*\))?\s*"""), "")
        t = t.replace(Regex("""@Relationship(?:\([^)]*\))?\s*"""), "")
        t = t.replace(Regex("""@Transient\s+"""), "")
        t = t.replace(Regex("""TextField<(?:Self\.)?_Label>"""), "TextField")
        if (!hasMacroServer) {
            t = t.replace(
                Regex("""@Observable\s+((?:(?:public|internal|private|fileprivate|final)\s+)*class\s+\w+)"""),
                "/* @Observable */ $1",
            )
            t = t.replace(Regex("""@ObservationTracked\s+"""), "")
            t = t.replace(Regex("""@ObservationIgnored\s+"""), "")
        }
        if (xcassetsColors.isNotEmpty()) {
            t = Regex("""Color\(\s*"([^"]+)"(?:\s*,\s*bundle\s*:\s*[^)]+)?\s*\)""").replace(t) { m ->
                xcassetsColors[m.groupValues[1]] ?: m.value
            }
        }
        if (localizableStrings.isNotEmpty()) {
            t = Regex(""""([A-Za-z0-9_.-]+)"""")
                .replace(t) { m ->
                    val key = m.groupValues[1]
                    val resolved = localizableStrings[key]
                    if (resolved != null && (key.contains('_') || !key.contains('.'))) {
                        "\"$resolved\""
                    } else {
                        m.value
                    }
                }
        }
        t = t.replace(Regex("""(?<![A-Za-z0-9_])private\s+(struct|enum|class)\s+"""), "$1 ")
        t = t.replace(
            Regex("""@FocusState\s+(private\s+)?var\s+(\w+)\s*:\s*Bool(?!\s*=)"""),
            "@State $1var $2: Bool = false",
        )
        return t
    }

    private val MEMBERWISE_PROP_REGEX = Regex(
        """^(?:@(ObservedObject|Binding|Bindable)\s+)?(?:(?:public|internal)\s+)?(?:let|var)\s+(\w+)\s*:\s*([^={]+)$"""
    )

    internal fun inferPreviewExpression(text: String): String {
        val prevMatch = Regex("""struct\s+(\w+)\s*:\s*PreviewProvider\b""").find(text)
        if (prevMatch != null) {
            return "${prevMatch.groupValues[1]}.previews"
        }
        val views = Regex("""struct\s+(\w+)\s*:\s*View\b""").findAll(text).map { it.groupValues[1] }.toList()
        if (views.isEmpty()) return "EmptyView()"
        val orderedViews = views.sortedByDescending {
            when {
                it.contains("Content") || it.contains("Main") || it.contains("Root") ||
                    it.contains("App") || it.contains("Panel") || it.contains("Home") -> 2
                it.contains("View") -> 1
                else -> 0
            }
        }
        for (v in orderedViews) {
            val idx = text.indexOf("struct $v")
            val bodyIdx = if (idx >= 0) text.indexOf("var body", idx) else -1
            val headerSlice = if (idx >= 0 && bodyIdx > idx) text.substring(idx, bodyIdx) else ""
            if (headerSlice.contains("init()")) return "$v()"
            val uninitProps = headerSlice.lineSequence().drop(1).map { it.trim() }.filter { s ->
                !s.contains("=") && !s.contains("{") && MEMBERWISE_PROP_REGEX.matches(s)
            }.toList()
            if (uninitProps.isEmpty()) return "$v()"
        }
        val v = orderedViews.first()
        val idx = text.indexOf("struct $v")
        val bodyIdx = if (idx >= 0) text.indexOf("var body", idx) else -1
        val headerSlice = if (idx >= 0 && bodyIdx > idx) text.substring(idx, bodyIdx) else ""
        data class PreviewInitArg(val name: String, val type: String, val isBinding: Boolean)
        val args = ArrayList<PreviewInitArg>()
        val initMatch = Regex("""init\s*\(([^\)]*)\)""").find(headerSlice)
        if (initMatch != null) {
            for (rawParam in initMatch.groupValues[1].split(',')) {
                val p = rawParam.trim()
                if (p.isEmpty() || p.contains("=")) continue
                val pm = Regex("""(\w+)(?:\s+\w+)?\s*:\s*(.+)""").find(p)
                if (pm != null) {
                    val ptype = pm.groupValues[2].trim()
                    args.add(PreviewInitArg(pm.groupValues[1], ptype, ptype.startsWith("Binding<")))
                }
            }
        } else {
            for (line in headerSlice.lineSequence().drop(1)) {
                val s = line.trim()
                if (!s.contains("=") && !s.contains("{")) {
                    val pm = MEMBERWISE_PROP_REGEX.find(s)
                    if (pm != null) {
                        val wrapper = pm.groupValues[1]
                        args.add(PreviewInitArg(pm.groupValues[2], pm.groupValues[3].trim(), wrapper == "Binding"))
                    }
                }
            }
        }
        val renderedArgs = args.map { arg ->
            val ptype = arg.type.removePrefix("Binding<").removeSuffix(">")
            val baseVal = when {
                "->" in ptype -> "{}"
                ptype.endsWith("?") -> "nil"
                ptype == "String" -> "\"${arg.name.replaceFirstChar { it.uppercaseChar() }}\""
                ptype in setOf("Double", "CGFloat", "Float") -> "0.72"
                ptype in setOf("Int", "UInt64", "Int64") -> "42"
                ptype == "Bool" -> "true"
                else -> ".init()"
            }
            val argVal = if (arg.isBinding) ".constant($baseVal)" else baseVal
            "${arg.name}: $argVal"
        }
        return "$v(${renderedArgs.joinToString(", ")})"
    }

    internal fun inferEnvironmentInjections(combinedSource: String): List<String> {
        val requestedTypes = LinkedHashSet<String>()
        Regex("""@EnvironmentObject\s+(?:(?:private|internal|public|fileprivate)\s+)?var\s+\w+\s*:\s*([A-Za-z_][A-Za-z0-9_]*)""")
            .findAll(combinedSource)
            .forEach { requestedTypes.add(it.groupValues[1]) }
        Regex("""@Environment\(\s*([A-Za-z_][A-Za-z0-9_]*)\.self\s*\)""")
            .findAll(combinedSource)
            .forEach { requestedTypes.add(it.groupValues[1]) }
        if (requestedTypes.isEmpty()) return emptyList()
        val modifiers = ArrayList<String>()
        for (typeName in requestedTypes) {
            val classMatch = Regex("""class\s+${Regex.escape(typeName)}\b[^{]*\{""").find(combinedSource) ?: continue
            val startIdx = classMatch.range.last
            var depth = 1
            var j = startIdx + 1
            while (j < combinedSource.length && depth > 0) {
                when (combinedSource[j]) {
                    '{' -> depth++
                    '}' -> depth--
                }
                j++
            }
            val classBody = combinedSource.substring(startIdx + 1, (j - 1).coerceAtLeast(startIdx + 1))
            val hasZeroArgInit = Regex("""\binit\s*\(\s*\)""").containsMatchIn(classBody)
            val hasRequiredArgInit = Regex("""\binit\s*\((?!\s*\))""").containsMatchIn(classBody)
            if (hasZeroArgInit || !hasRequiredArgInit) {
                modifiers.add(".environmentObject($typeName())")
            }
        }
        return modifiers
    }

    internal fun extractFailingSwiftFiles(
        compileLines: List<String>,
        candidates: List<Path>,
        protectedFiles: Set<Path>,
    ): Set<Path> {
        val protectedNames = protectedFiles.map { it.fileName.toString() }.toSet()
        val protectedPaths = protectedFiles.map { it.toAbsolutePath().normalize() }.toSet()
        val errorRegex = Regex("""^(.+?\.swift):\d+:\d+:\s*error:""")
        val matched = LinkedHashSet<Path>()
        for (line in compileLines) {
            val m = errorRegex.find(line.trim()) ?: continue
            val rawPath = m.groupValues[1].trim()
            val fileName = java.nio.file.Paths.get(rawPath).fileName?.toString().orEmpty()
            for (cand in candidates) {
                val candNorm = cand.toAbsolutePath().normalize()
                if (candNorm in protectedPaths || cand.fileName.toString() in protectedNames) continue
                if (candNorm.toString().endsWith(rawPath.replace('\\', '/')) || cand.fileName.toString() == fileName) {
                    matched.add(cand)
                }
            }
        }
        return matched
    }

    internal fun countUnsupportedSwiftImports(text: String, internalModules: Set<String> = emptySet()): Int {
        val sanitized = text.replace(Regex("""#if\s+os\(macOS\)"""), "#if false")
        var inFalse = 0
        var count = 0
        val attrPrefix = Regex("""^(?:@\w+(?:\([^)]*\))?\s+)+""")
        for (line in sanitized.lineSequence()) {
            val t = line.trim().replace(attrPrefix, "")
            if (t.startsWith("#if false")) {
                inFalse++
            } else if (t.startsWith("#if ") && inFalse > 0) {
                inFalse++
            } else if (t.startsWith("#else") && inFalse == 1) {
                inFalse = 0
            } else if (t.startsWith("#endif") && inFalse > 0) {
                inFalse--
            } else if (inFalse == 0 && t.startsWith("import ")) {
                val rest = t.removePrefix("import ").trim()
                val parts = rest.split(Regex("""\s+"""))
                val mod = parts.lastOrNull()?.substringBefore('.')?.takeWhile { it.isLetterOrDigit() || it == '_' }.orEmpty()
                if (mod.isNotEmpty() && mod !in SUPPORTED_SWIFTUI_IMPORTS && mod !in internalModules) {
                    count++
                }
            }
        }
        return count
    }

    internal fun isNonUiPlatformServiceFile(raw: String): Boolean {
        return raw.contains(": NSPanel") ||
            raw.contains(": NSWindow") ||
            raw.contains("NSPanel?") ||
            raw.contains("NSBitmapImageRep") ||
            raw.contains("CGEvent.tapCreate") ||
            raw.contains("AVAudioPCMBuffer") ||
            raw.contains("URLSessionConfiguration") ||
            raw.contains("dlopen(") ||
            raw.contains("proc_pid_rusage")
    }

    internal fun synthesizeFocusedCompanionShims(includedTexts: List<String>): String? {
        val combined = includedTexts.joinToString("\n")
        if (!combined.contains("CompanionManager") ||
            Regex("""(?:class|struct)\s+CompanionManager\b""").containsMatchIn(combined)
        ) {
            return null
        }
        return """
            import Foundation
            import SwiftUI

            extension Notification.Name {
                static let clickyDismissPanel = Notification.Name("clickyDismissPanel")
            }

            enum CompanionVoiceState: Sendable {
                case idle
                case listening
                case processing
                case responding
            }

            final class BuddyDictationManagerStub: ObservableObject, @unchecked Sendable {
                @Published var transcriptionProviderDisplayName: String = "Apple Speech"
                init() {}
            }

            final class CompanionManager: ObservableObject, @unchecked Sendable {
                nonisolated(unsafe) static weak var sharedInstance: CompanionManager?

                @Published var voiceState: CompanionVoiceState = .idle
                @Published var hasAccessibilityPermission: Bool = false
                @Published var hasScreenRecordingPermission: Bool = false
                @Published var hasMicrophonePermission: Bool = false
                @Published var hasScreenContentPermission: Bool = true
                @Published var isOverlayVisible: Bool = true
                @Published var selectedModel: String = "claude-sonnet-4-6"
                @Published var isClickyCursorEnabled: Bool = true
                @Published var hasCompletedOnboarding: Bool = false
                @Published var hasSubmittedEmail: Bool = false
                let buddyDictationManager = BuddyDictationManagerStub()

                var allPermissionsGranted: Bool {
                    hasAccessibilityPermission && hasScreenRecordingPermission && hasMicrophonePermission && hasScreenContentPermission
                }

                init() {
                    Self.sharedInstance = self
                }

                func setSelectedModel(_ model: String) {
                    selectedModel = model
                }

                func setClickyCursorEnabled(_ enabled: Bool) {
                    isClickyCursorEnabled = enabled
                    isOverlayVisible = enabled
                }

                func submitEmail(_ email: String) {
                    let trimmed = email.trimmingCharacters(in: .whitespacesAndNewlines)
                    guard !trimmed.isEmpty else { return }
                    hasSubmittedEmail = true
                }

                func triggerOnboarding() {
                    hasCompletedOnboarding = true
                    isOverlayVisible = true
                }

                func replayOnboarding() {
                    hasCompletedOnboarding = true
                    isOverlayVisible = true
                }

                func requestScreenContentPermission() {
                    hasScreenContentPermission = true
                }
            }

            enum WindowPositionManager {
                static func requestAccessibilityPermission() {
                    CompanionManager.sharedInstance?.hasAccessibilityPermission = true
                }
                static func revealAppInFinder() {}
                static func openAccessibilitySettings() {}
                static func requestScreenRecordingPermission() {
                    CompanionManager.sharedInstance?.hasScreenRecordingPermission = true
                }
            }
        """.trimIndent() + "\n"
    }

    /**
     * Source files that a focused Cupertino preview must compile together with its candidate.
     *
     * The previous behaviour compiled only the single candidate file, which dropped every other
     * declaration in the app target; a candidate that referenced a type declared elsewhere then
     * failed with `cannot find type 'X' in scope` (e.g. a preview referencing a `ButtonType` enum
     * declared in a sibling `Models/` file). Here we collect every repo source that has no
     * unsupported imports, excluding the real `@main` app entry (the shadow main supplies its own)
     * and build/test directories, so transitive module dependencies resolve.
     */
    internal fun focusedCompanionSources(
        projectRoot: Path,
        internalModules: Set<String>,
    ): List<Path> {
        return SwiftBuildSystem.findSwiftSourceFiles(projectRoot).filterNot { f ->
            val s = f.toString().replace('\\', '/')
            s.contains("/.build/") || s.contains("/.building/") || s.contains("/cupertino-shadow/") || s.contains("/Pods/") ||
                s.contains("/Tests/") || s.contains("/UITests/") || s.contains("/UnitTests/")
        }.filter { f ->
            val raw = runCatching { f.readText() }.getOrDefault("")
            countUnsupportedSwiftImports(raw, internalModules) == 0 &&
                !isNonUiPlatformServiceFile(raw) &&
                !Regex("""@main|struct\s+\w+\s*:\s*App\b""").containsMatchIn(raw)
        }
    }

    /**
     * When a repository contains multiple app targets or subprojects (for example,
     * `takenori-kabeya/SwiftUIStateManagementBook-Counter` which has both `SimpleCounterSwiftUI/`
     * and `SimpleCounterTCA/`), selects the single runnable SwiftUI target directory that has no
     * unresolvable external framework imports.
     */
    internal fun selectSwiftUiTargetFiles(projectRoot: Path, candidateFiles: List<Path>): List<Path> {
        if (candidateFiles.isEmpty()) return candidateFiles
        val internalModules = discoverInternalSwiftModules(projectRoot)
        // 1. Filter out non-iOS directories (Mac, tests, watch, etc.)
        val iosFiles = candidateFiles.filterNot { f ->
            val s = f.toString().replace('\\', '/')
            s.contains("/Mac/") || s.contains("/macOS/") ||
                s.contains("/.build/") || s.contains("/.building/") || s.contains("/cupertino-shadow/") ||
                s.contains("/Tests/") || s.contains("/UnitTests/") || s.contains("/UITests/") ||
                s.contains("/Watch/") || s.contains("/watchOS/") || s.contains("/Pods/")
        }.ifEmpty { candidateFiles }

        val fileTexts = iosFiles.associateWith { f ->
            runCatching { f.readText() }.getOrDefault("")
        }

        val pureSwiftUiFiles = iosFiles.filter { f ->
            val text = fileTexts[f].orEmpty()
            text.contains("import SwiftUI") && countUnsupportedSwiftImports(text, internalModules) == 0
        }

        val mainFiles = iosFiles.filter { f ->
            val text = fileTexts[f].orEmpty()
            text.contains("@main") || Regex("""struct\s+\w+\s*:\s*App\b""").containsMatchIn(text)
        }

        val commonTargetSubdirs = setOf(
            "views", "models", "styles", "viewmodels", "components", "shared", "model", "view", "services",
        )

        if (mainFiles.isNotEmpty()) {
            data class TargetCandidate(
                val mainFile: Path,
                val targetDir: Path,
                val files: List<Path>,
                val unsupportedImports: Int,
                val importsSwiftUi: Boolean,
            )

            val candidates = mainFiles.map { mainFile ->
                var targetDir = mainFile.parent ?: projectRoot
                var curr = mainFile.parent
                while (curr != null && curr != projectRoot) {
                    if (Files.exists(curr.resolve("Package.swift")) ||
                        runCatching { Files.list(curr).use { s -> s.anyMatch { it.fileName.toString().endsWith(".xcodeproj") } } }.getOrDefault(false)) {
                        targetDir = curr
                        break
                    }
                    val p = curr.parent
                    if (p != null) {
                        val pName = p.fileName?.toString()?.lowercase()
                        if (p != projectRoot && pName in setOf("experiments", "samples", "examples", "apps", "packages", "projects", "subprojects", "demos")) {
                            targetDir = curr
                            break
                        }
                        val currIsCommonSubdir = curr.fileName?.toString()?.lowercase() in commonTargetSubdirs
                        if (p != projectRoot || currIsCommonSubdir) {
                            val hasCommonSubdirs = runCatching {
                                Files.list(p).use { stream ->
                                    stream.anyMatch { dir ->
                                        Files.isDirectory(dir) && dir.fileName?.toString()?.lowercase() in commonTargetSubdirs
                                    }
                                }
                            }.getOrDefault(false)
                            if (hasCommonSubdirs) {
                                targetDir = p
                            }
                        }
                    }
                    curr = p
                }
                val scopedFiles = iosFiles.filter { it.startsWith(targetDir) }
                val unsupported = scopedFiles.sumOf { countUnsupportedSwiftImports(fileTexts[it].orEmpty(), internalModules) }
                val hasSwiftUi = scopedFiles.any { fileTexts[it].orEmpty().contains("import SwiftUI") }
                TargetCandidate(mainFile, targetDir, scopedFiles, unsupported, hasSwiftUi)
            }

            val best = candidates.sortedWith(
                compareBy<TargetCandidate> { !it.importsSwiftUi }
                    .thenBy { it.unsupportedImports }
                    .thenBy { it.mainFile.toString().contains("TCA", ignoreCase = true) }
                    .thenBy { it.targetDir.toString().length },
            ).firstOrNull()

            if (best != null && (best.unsupportedImports == 0 || pureSwiftUiFiles.isEmpty())) {
                val cleanFiles = best.files.filter {
                    val raw = fileTexts[it].orEmpty()
                    countUnsupportedSwiftImports(raw, internalModules) == 0 && !isNonUiPlatformServiceFile(raw)
                }
                return if (cleanFiles.isNotEmpty()) cleanFiles else best.files
            }
        }

        // When no clean @main candidate exists (or in multi-target projects like NetNewsWire where @main is a Widget),
        // pick the cluster of pure SwiftUI views and include all sibling/child directories
        if (pureSwiftUiFiles.isNotEmpty()) {
            val byTargetDir = pureSwiftUiFiles.groupBy { f ->
                var d = f.parent ?: projectRoot
                val p = d.parent
                if (p != null) {
                    val dIsCommonSubdir = d.fileName?.toString()?.lowercase() in commonTargetSubdirs
                    if (p != projectRoot || dIsCommonSubdir) {
                        val hasCommonSubdirs = runCatching {
                            Files.list(p).use { stream ->
                                stream.anyMatch { dir ->
                                    Files.isDirectory(dir) && dir.fileName?.toString()?.lowercase() in commonTargetSubdirs
                                }
                            }
                        }.getOrDefault(false)
                        if (hasCommonSubdirs) d = p
                    }
                }
                d
            }
            val bestTarget = byTargetDir.maxByOrNull { (_, files) ->
                files.count { f ->
                    val t = fileTexts[f].orEmpty()
                    t.contains(": View") && (t.contains("About") || t.contains("Content") || t.contains("Main") || t.contains("App") || t.contains("Panel"))
                } * 10 + files.size
            }?.key
            if (bestTarget != null) {
                val targetClean = iosFiles.filter {
                    val raw = fileTexts[it].orEmpty()
                    it.startsWith(bestTarget) && countUnsupportedSwiftImports(raw, internalModules) == 0 && !isNonUiPlatformServiceFile(raw)
                }
                return if (targetClean.isNotEmpty()) targetClean else iosFiles.filter { it.startsWith(bestTarget) }
            }
            return pureSwiftUiFiles
        }

        return iosFiles
    }

    /**
     * Build a disposable **shadow workspace** for Cupertino Run so the cloned GitHub/Xcode
     * project is never rewritten for the Android bridge. Copies the selected SwiftUI sources
     * and, when the tree has no bridge entry, a tiny `@main` that calls `runPreview { … }`.
     * xtool always runs on the original project root for the real IPA.
     */
    internal fun prepareCupertinoShadow(
        projectRoot: Path,
        scratchDir: Path?,
    ): Path {
        val shadow = (scratchDir ?: projectRoot.resolve(".building")).resolve("cupertino-shadow")
        if (Files.isDirectory(shadow)) {
            Files.walk(shadow).use { stream ->
                stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
        Files.createDirectories(shadow)
        val internalModules = discoverInternalSwiftModules(projectRoot)
        val xcassetsColors = discoverXcassetsColors(projectRoot)
        val localizableStrings = discoverLocalizableStrings(projectRoot)
        val allSwiftFiles = SwiftBuildSystem.findSwiftSourceFiles(projectRoot)
        val filteredFiles = allSwiftFiles.filter { f ->
            val name = f.fileName.toString()
            if (name.endsWith("Main.swift")) {
                val text = runCatching { f.readText() }.getOrDefault("")
                !text.contains("Swift TargetReady")
            } else true
        }.ifEmpty { allSwiftFiles }
        val swiftUiFiles = selectSwiftUiTargetFiles(projectRoot, filteredFiles)
        val copied = ArrayList<Path>()
        fun copyAndSanitize(src: Path, dst: Path) {
            Files.createDirectories(dst.parent)
            val raw = runCatching { src.readText() }.getOrDefault("")
            val content = sanitizeSwiftUiSource(
                raw,
                internalModules,
                hasMacroServer = false,
                xcassetsColors = xcassetsColors,
                localizableStrings = localizableStrings,
            )
            dst.writeText(content)
            copied.add(dst)
        }

        for (src in swiftUiFiles) {
            val rel = runCatching { projectRoot.relativize(src) }.getOrDefault(src.fileName)
            val dst = shadow.resolve(rel.toString())
            copyAndSanitize(src, dst)
        }

        // Recursively discover and copy all .swift files across subdirectories
        // (Models/, Views/, Styles/, ViewModels/, Components/) in the target directory tree
        val commonSubdirs = setOf(
            "views", "models", "styles", "viewmodels", "components", "shared", "model", "view", "services",
        )
        val targetRoots = swiftUiFiles.mapNotNull { f ->
            var d = f.parent
            while (d != null && d != projectRoot && d.parent != null && (d.parent != projectRoot || d.fileName?.toString()?.lowercase() in commonSubdirs)) {
                val pName = d.fileName?.toString()?.lowercase()
                if (pName in setOf("experiments", "samples", "examples", "apps", "packages", "projects", "subprojects", "demos")) {
                    break
                }
                if (Files.exists(d.resolve("Package.swift")) ||
                    runCatching { Files.list(d).use { s -> s.anyMatch { it.fileName.toString().endsWith(".xcodeproj") } } }.getOrDefault(false)) {
                    break
                }
                d = d.parent
            }
            d ?: f.parent
        }.distinct().ifEmpty { listOf(projectRoot) }

        for (root in targetRoots) {
            if (Files.isDirectory(root)) {
                Files.walk(root).use { stream ->
                    stream.filter { f ->
                        Files.isRegularFile(f) &&
                            f.fileName.toString().endsWith(".swift") &&
                            !f.fileName.toString().endsWith("Main.swift")
                    }.forEach { candidate ->
                        val relStr = candidate.toString().replace('\\', '/')
                        val isIgnored = candidate.startsWith(shadow) ||
                            relStr.contains("/.build/") || relStr.contains("/.building/") || relStr.contains("/cupertino-shadow/") || relStr.contains("/Pods/") ||
                            relStr.contains("/Tests/") || relStr.contains("/UnitTests/") || relStr.contains("/UITests/") ||
                            relStr.contains("/Mac/") || relStr.contains("/macOS/") || relStr.contains("/Watch/")
                        if (!isIgnored) {
                            val text = runCatching { candidate.readText() }.getOrDefault("")
                            if (!text.contains("import XCTest") &&
                                !text.contains("exit(") &&
                                countUnsupportedSwiftImports(text, internalModules) == 0 &&
                                !isNonUiPlatformServiceFile(text)
                            ) {
                                val rel = runCatching { projectRoot.relativize(candidate) }.getOrDefault(candidate.fileName)
                                val dst = shadow.resolve(rel.toString())
                                if (!Files.exists(dst)) {
                                    copyAndSanitize(candidate, dst)
                                }
                            }
                        }
                    }
                }
            }
        }

        val copiedTexts = copied.map { runCatching { it.readText() }.getOrDefault("") }
        synthesizeFocusedCompanionShims(copiedTexts)?.let { shims ->
            val shimFile = shadow.resolve("FocusedCompanionShims.swift")
            shimFile.writeText(shims)
            copied.add(shimFile)
        }
        synthesizeXcassetsColorExtensions(xcassetsColors)?.let { colorShims ->
            val colorShimFile = shadow.resolve("XcassetsColorShims.swift")
            colorShimFile.writeText(colorShims)
            copied.add(colorShimFile)
        }

        // Always write CupertinoBridgePrelude.swift into the shadow workspace so all shims
        // and extensions (UIViewRepresentable, ToggleStyle, frameworks, etc.) are available
        shadow.resolve("CupertinoBridgePrelude.swift").writeText(CUPERTINO_BRIDGE_PRELUDE + "\n")

        // Copy .xcassets asset catalogs and image resources into shadow workspace
        runCatching {
            Files.walk(projectRoot, 6).use { stream ->
                stream.filter { p ->
                    Files.isDirectory(p) && p.fileName.toString().endsWith(".xcassets")
                }.forEach { xcassetsDir ->
                    val rel = runCatching { projectRoot.relativize(xcassetsDir) }.getOrDefault(xcassetsDir.fileName)
                    val shadowXcassets = shadow.resolve(rel.toString())
                    Files.walk(xcassetsDir).use { assetStream ->
                        assetStream.forEach { src ->
                            val subRel = xcassetsDir.relativize(src)
                            val dst = shadowXcassets.resolve(subRel.toString())
                            if (Files.isDirectory(src)) {
                                Files.createDirectories(dst)
                            } else {
                                Files.createDirectories(dst.parent)
                                Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING)
                            }
                        }
                    }
                    val topAssets = shadow.resolve("Assets.xcassets")
                    if (!Files.exists(topAssets)) {
                        Files.walk(xcassetsDir).use { assetStream ->
                            assetStream.forEach { src ->
                                val subRel = xcassetsDir.relativize(src)
                                val dst = topAssets.resolve(subRel.toString())
                                if (Files.isDirectory(src)) {
                                    Files.createDirectories(dst)
                                } else {
                                    Files.createDirectories(dst.parent)
                                    Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING)
                                }
                            }
                        }
                    }
                }
            }
        }
        runCatching {
            for (root in targetRoots) {
                if (Files.isDirectory(root)) {
                    Files.walk(root, 4).use { stream ->
                        stream.filter { p ->
                            Files.isRegularFile(p) && p.fileName.toString().lowercase().let { n ->
                                n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".webp")
                            } && !p.toString().contains("/.build/")
                        }.forEach { imgFile ->
                            val rel = runCatching { projectRoot.relativize(imgFile) }.getOrDefault(imgFile.fileName)
                            val dst = shadow.resolve(rel.toString())
                            if (!Files.exists(dst)) {
                                Files.createDirectories(dst.parent)
                                Files.copy(imgFile, dst, StandardCopyOption.REPLACE_EXISTING)
                            }
                        }
                    }
                }
            }
        }

        val hasAppMain = copied.any { f ->
            val text = runCatching { f.readText() }.getOrDefault("")
            Regex("""@main\s+(?:public\s+|internal\s+)?struct\s+\w+\s*:\s*App\b""").containsMatchIn(text) ||
                (text.contains("@main") && text.contains(": App"))
        }
        val hasBridgeEntry = hasAppMain || copied.any { f ->
            val text = runCatching { f.readText() }.getOrDefault("")
            text.contains("runPreview") || text.contains("ViewHost(")
        }
        if (!hasBridgeEntry) {
            val text = copied.joinToString("\n") { runCatching { it.readText() }.getOrDefault("") }
            val expr = inferPreviewExpression(text)
            val envChain = inferEnvironmentInjections(text).joinToString("")
            shadow.resolve("CupertinoShadowMain.swift").writeText(
                """
                import SwiftUI

                @main
                struct CupertinoShadowMain {
                    static func main() throws {
                        try runPreview { $expr$envChain }
                    }
                }
                """.trimIndent() + "\n",
            )
        }
        return shadow
    }

    /**
     * Compiles a SwiftUI project's `.swift` sources against the on-device `SwiftUI` bridge module
     * and launches the interactive Cupertino GUI session in `RunScreen`.
     *
     * Compiles from [shadowRoot] when provided (see [prepareCupertinoShadow]); [projectRoot]
     * remains the original clone and is what xtool exports from.
     */
    suspend fun compileAndRunSwiftUiBridge(
        host: RuntimeHost,
        installation: RuntimeInstallation,
        swiftcBin: Path,
        projectRoot: Path,
        projectName: String,
        scratchDir: Path?,
        env: Map<String, String>,
        ctx: TaskContext,
        buildCtx: BuildContext?,
        shadowRoot: Path? = null,
    ): TaskResult {
        val compileRoot = shadowRoot ?: projectRoot
        val allSwiftFiles = SwiftBuildSystem.findSwiftSourceFiles(compileRoot)
        // Prefer real SwiftUI source files over any synthetic stub *Main.swift
        val filteredFiles = allSwiftFiles.filter { f ->
            val name = f.fileName.toString()
            if (name.endsWith("Main.swift")) {
                val text = runCatching { f.readText() }.getOrDefault("")
                !text.contains("Swift TargetReady")
            } else true
        }.ifEmpty { allSwiftFiles }
        val swiftUiFiles = if (shadowRoot != null) {
            filteredFiles
        } else {
            selectSwiftUiTargetFiles(compileRoot, filteredFiles)
        }

        if (swiftUiFiles.isEmpty()) {
            return TaskResult.Failed("No Swift source files found in $compileRoot")
        }

        val pkgName = SwiftBuildSystem.extractSwiftPackageName(projectRoot)
        val outDir = (scratchDir ?: projectRoot.resolve(".building")).resolve("cupertino-shadow/debug")
        Files.createDirectories(outDir)
        val outBin = outDir.resolve("$pkgName-cupertino")

        val newestSourceMtime = swiftUiFiles.maxOfOrNull {
            runCatching { Files.getLastModifiedTime(it).toMillis() }.getOrDefault(0L)
        } ?: 0L
        val binMtime = if (Files.isRegularFile(outBin) && Files.isExecutable(outBin)) {
            runCatching { Files.getLastModifiedTime(outBin).toMillis() }.getOrDefault(0L)
        } else 0L

        if (binMtime < newestSourceMtime || binMtime == 0L) {
            val sdkRoot = installation.prefix.parent ?: installation.prefix
            val swiftAndroidLib = installation.prefix.resolve("lib/swift/android")
            val archInclude = installation.prefix.resolve("include/aarch64-linux-android")
            val archLib = installation.prefix.resolve("lib/aarch64-linux-android")
            val hostBridgeSdk = java.nio.file.Paths.get(System.getProperty("user.home", ""), ".building/swiftui-bridge-sdk")
            val macroServer = installation.prefix.resolve("bin/OpenAppleMacrosServer")
            val hasMacroServer = Files.isRegularFile(macroServer) && Files.isExecutable(macroServer)
            fun buildSwiftcCommand(sources: List<Path>): List<String> = buildList {
                add(swiftcBin.toString())
                add("-parse-as-library")
                for (f in sources) add(f.toString())
                if (hasMacroServer) {
                    add("-load-plugin-executable")
                    add("$macroServer#ObservationMacros")
                }
                if (Files.isDirectory(swiftAndroidLib)) {
                    add("-sdk")
                    add(sdkRoot.toString())
                    add("-I$swiftAndroidLib")
                    add("-L$swiftAndroidLib")
                    if (Files.isDirectory(archInclude)) {
                        add("-Xcc")
                        add("-isystem")
                        add("-Xcc")
                        add(archInclude.toString())
                    }
                    if (Files.isDirectory(archLib)) {
                        add("-L$archLib")
                    }
                } else if (Files.isDirectory(hostBridgeSdk)) {
                    val cltSdk = java.nio.file.Paths.get("/Library/Developer/CommandLineTools/SDKs/MacOSX27.0.sdk")
                    if (swiftcBin.toString().contains(".swiftly") && Files.isDirectory(cltSdk)) {
                        add("-sdk")
                        add(cltSdk.toString())
                    }
                    add("-I$hostBridgeSdk")
                    add("-L$hostBridgeSdk")
                }
                add("-lSwiftUI")
                add("-o")
                add(outBin.toString())
            }
            ctx.reportStep(":swift:compileCupertinoBridge", "started")
            val relNames = swiftUiFiles.joinToString(" ") {
                runCatching { compileRoot.relativize(it).toString() }.getOrDefault(it.fileName.toString())
            }
            ctx.buildLog.log(BuildLogEntry("$ swiftc -parse-as-library $relNames -lSwiftUI -o ${outBin.fileName}", BuildLogLevel.INFO))
            val compileLines = ArrayList<String>()
            val activePrimaryFiles = ArrayList(swiftUiFiles)
            val protectedPrimary = swiftUiFiles.filter { f ->
                val name = f.fileName.toString()
                if (name in setOf("CupertinoBridgePrelude.swift", "CupertinoShadowMain.swift", "FocusedCompanionShims.swift", "XcassetsColorShims.swift")) {
                    true
                } else {
                    val text = runCatching { f.readText() }.getOrDefault("")
                    text.contains("@main") || Regex("""struct\s+\w+\s*:\s*App\b""").containsMatchIn(text)
                }
            }.toSet()
            var code = execSimple(host, buildSwiftcCommand(activePrimaryFiles), compileRoot, env, ctx, compileLines)
            var primaryPrunePass = 0
            while (code != 0 && primaryPrunePass < 5 && activePrimaryFiles.size > protectedPrimary.size + 1) {
                val failing = extractFailingSwiftFiles(compileLines, activePrimaryFiles, protectedPrimary)
                if (failing.isEmpty()) break
                activePrimaryFiles.removeAll(failing)
                primaryPrunePass++
                compileLines.clear()
                code = execSimple(host, buildSwiftcCommand(activePrimaryFiles), compileRoot, env, ctx, compileLines)
            }

            if (code != 0) {
                // Multi-file target had cross-module/platform symbols; try focused candidate SwiftUI view files in projectRoot
                val internalModules = discoverInternalSwiftModules(projectRoot)
                val xcassetsColors = discoverXcassetsColors(projectRoot)
                val localizableStrings = discoverLocalizableStrings(projectRoot)
                val repoSwiftFiles = SwiftBuildSystem.findSwiftSourceFiles(projectRoot).filterNot { f ->
                    val s = f.toString().replace('\\', '/')
                    s.contains("/.build/") || s.contains("/Pods/") ||
                        s.contains("/Tests/") || s.contains("/UITests/") || s.contains("/UnitTests/")
                }
                data class FocusedCandidate(val score: Int, val file: Path, val sanitized: String)
                val focusedCandidates = repoSwiftFiles.mapNotNull { p ->
                    val raw = runCatching { p.readText() }.getOrDefault("")
                    if (!raw.contains("import SwiftUI")) return@mapNotNull null
                    if (countUnsupportedSwiftImports(raw, internalModules) > 0) return@mapNotNull null
                    if (isNonUiPlatformServiceFile(raw)) return@mapNotNull null
                    val san = sanitizeSwiftUiSource(raw, internalModules, hasMacroServer, xcassetsColors, localizableStrings)
                    val hasView = Regex("""struct\s+\w+\s*:\s*View\b""").containsMatchIn(san)
                    val hasPreview = Regex("""struct\s+\w+\s*:\s*PreviewProvider\b""").containsMatchIn(san) ||
                        raw.contains("#Preview")
                    if (!hasView && !hasPreview) return@mapNotNull null
                    if (inferPreviewExpression(san) == "EmptyView()") return@mapNotNull null
                    val fname = p.fileName.toString()
                    val pathStr = p.toString().replace('\\', '/')
                    var score = 0
                    if (hasPreview) score += 50
                    if (listOf("ContentView", "MainView", "RootView", "AppView", "PanelView", "HomeView").any { fname.contains(it) }) {
                        score += 100
                    }
                    if (listOf("/Shared/", "/DesignSystem/", "/Views/", "Previews").any { pathStr.contains(it) }) {
                        score += 30
                    }
                    score -= san.length / 500
                    FocusedCandidate(score, p, san)
                }.sortedByDescending { it.score }

                val focusedDir = outDir.parent?.resolve("focused") ?: compileRoot.resolve("focused")
                val safeCompanions = focusedCompanionSources(projectRoot, internalModules)
                val modes = if (safeCompanions.size > 120) listOf(false, true) else listOf(true, false)
                for (includeCompanions in modes) {
                    if (code == 0) break
                    if (includeCompanions && safeCompanions.isEmpty()) continue
                    val limit = if (includeCompanions && safeCompanions.size > 40) 3 else 8
                    for (cand in focusedCandidates.take(limit)) {
                        if (Files.isDirectory(focusedDir)) {
                            Files.walk(focusedDir).use { stream ->
                                stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
                            }
                        }
                        Files.createDirectories(focusedDir)
                        val srcs = ArrayList<Path>()
                        val written = HashSet<String>()
                        val targetName = cand.file.fileName.toString()
                        written.add(targetName)
                        val targetFile = focusedDir.resolve(targetName)
                        targetFile.writeText(cand.sanitized)
                        srcs.add(targetFile)
                        if (includeCompanions) {
                            for (comp in safeCompanions) {
                                val name = comp.fileName.toString()
                                if (!written.add(name)) continue
                                val target = focusedDir.resolve(name)
                                target.writeText(
                                    sanitizeSwiftUiSource(
                                        runCatching { comp.readText() }.getOrDefault(""),
                                        internalModules,
                                        hasMacroServer,
                                        xcassetsColors,
                                        localizableStrings,
                                    )
                                )
                                srcs.add(target)
                            }
                        }
                        val focusedTexts = srcs.map { runCatching { it.readText() }.getOrDefault("") }
                        val protectedFocused = LinkedHashSet<Path>().apply { add(targetFile) }
                        synthesizeFocusedCompanionShims(focusedTexts)?.let { shims ->
                            val shimFile = focusedDir.resolve("FocusedCompanionShims.swift")
                            shimFile.writeText(shims)
                            srcs.add(shimFile)
                            protectedFocused.add(shimFile)
                        }
                        synthesizeXcassetsColorExtensions(xcassetsColors)?.let { colorShims ->
                            val colorShimFile = focusedDir.resolve("XcassetsColorShims.swift")
                            colorShimFile.writeText(colorShims)
                            srcs.add(colorShimFile)
                            protectedFocused.add(colorShimFile)
                        }
                        val preludeFile = focusedDir.resolve("CupertinoBridgePrelude.swift")
                        preludeFile.writeText(CUPERTINO_BRIDGE_PRELUDE + "\n")
                        srcs.add(preludeFile)
                        protectedFocused.add(preludeFile)
                        val expr = inferPreviewExpression(cand.sanitized)
                        val combinedFocused = focusedTexts.joinToString("\n")
                        val envChain = inferEnvironmentInjections(combinedFocused).joinToString("")
                        val mainFile = focusedDir.resolve("CupertinoShadowMain.swift")
                        fun writeShadowMain(withEnv: Boolean) {
                            val suffix = if (withEnv) envChain else ""
                            mainFile.writeText(
                                """
                                import SwiftUI

                                @main
                                struct CupertinoShadowMain {
                                    static func main() throws {
                                        try runPreview { $expr$suffix }
                                    }
                                }
                                """.trimIndent() + "\n",
                            )
                        }
                        writeShadowMain(withEnv = envChain.isNotEmpty())
                        srcs.add(mainFile)
                        protectedFocused.add(mainFile)
                        compileLines.clear()
                        var attemptCode = execSimple(host, buildSwiftcCommand(srcs), focusedDir, env, ctx, compileLines)
                        var prunePass = 0
                        while (attemptCode != 0 && includeCompanions && prunePass < 6 && srcs.size > protectedFocused.size) {
                            val failing = extractFailingSwiftFiles(compileLines, srcs, protectedFocused)
                            if (failing.isEmpty()) break
                            srcs.removeAll(failing)
                            prunePass++
                            compileLines.clear()
                            attemptCode = execSimple(host, buildSwiftcCommand(srcs), focusedDir, env, ctx, compileLines)
                        }
                        if (attemptCode != 0 && envChain.isNotEmpty()) {
                            writeShadowMain(withEnv = false)
                            compileLines.clear()
                            attemptCode = execSimple(host, buildSwiftcCommand(srcs), focusedDir, env, ctx, compileLines)
                        }
                        if (attemptCode == 0) {
                            code = 0
                            break
                        }
                    }
                }
            }

            ctx.reportToolDiagnostics("swiftc", compileLines)
            if (code != 0) {
                ctx.reportStep(":swift:compileCupertinoBridge", "failed")
                return TaskResult.Failed("swiftc exited with code $code")
            }
            outBin.toFile().setExecutable(true)
            ctx.reportStep(":swift:compileCupertinoBridge", "succeeded")
        } else {
            ctx.buildLog.log(
                BuildLogEntry(
                    "✓ Using up-to-date ARM64 SwiftUI binary: ${outBin.fileName} (${Files.size(outBin) / 1024} KB)",
                    BuildLogLevel.INFO,
                ),
            )
        }

        val runEnv = LinkedHashMap(env).apply {
            val swiftAndroidLib = installation.prefix.resolve("lib/swift/android")
            val curLd = get("LD_LIBRARY_PATH").orEmpty()
            if (Files.isDirectory(swiftAndroidLib)) {
                put("LD_LIBRARY_PATH", listOf(curLd, swiftAndroidLib.toString()).filter { it.isNotBlank() }.joinToString(":"))
            }
        }

        return execInteractiveBridge(
            host = host,
            command = listOf(outBin.toString()),
            workingDir = compileRoot,
            env = runEnv,
            projectName = projectName,
            displayCommand = "$pkgName",
            stepName = ":swift:runCupertinoBridge",
            ctx = ctx,
            buildCtx = buildCtx,
        )
    }

    /**
     * Returns true if the Node.js project at [root] uses a desktop/window GUI toolkit
     * (`@nodegui/nodegui`, `libui-node`, or `x11`) that can be hosted via our GUI bridge shim.
     */
    fun isNodeGuiProject(root: Path): Boolean = runCatching {
        val pkg = root.resolve("package.json")
        if (Files.isRegularFile(pkg)) {
            val text = pkg.readText()
            if (text.contains("@nodegui/nodegui") ||
                text.contains("libui-node") ||
                text.contains("\"x11\"") ||
                text.contains("qode ")
            ) {
                return true
            }
        }
        val candidates = listOf(
            "src/index.ts", "src/index.js", "index.ts", "index.js", "main.js", "app.js",
        )
        for (rel in candidates) {
            val p = root.resolve(rel)
            if (Files.isRegularFile(p)) {
                val content = p.readText()
                if (content.contains("@nodegui/nodegui") ||
                    content.contains("libui-node") ||
                    content.contains("require('x11')") ||
                    content.contains("from 'x11'")
                ) {
                    return true
                }
            }
        }
        false
    }.getOrDefault(false)

    fun findNodeGuiEntrypoint(root: Path): Path? {
        val candidates = listOf(
            "src/index.ts",
            "src/index.js",
            "index.ts",
            "index.js",
            "src/main.ts",
            "src/main.js",
            "main.js",
            "app.js",
        )
        for (rel in candidates) {
            val p = root.resolve(rel)
            if (Files.isRegularFile(p)) return p
        }
        return null
    }

    /**
     * Installs our pure-JS Node GUI Bridge shims (`@nodegui/nodegui`, `libui-node`, `x11`, and `lodash`)
     * into `<projectRoot>/node_modules/` so unmodified NodeGUI / libui / X11 repositories run directly on
     * the embedded Node.js runtime and stream interactive `ViewNode` trees to `RunScreen`.
     */
    fun ensureNodeGuiBridgeShimInstalled(projectRoot: Path) {
        runCatching {
            val nodeModules = projectRoot.resolve("node_modules")
            // 1. @nodegui/nodegui
            val nodeguiDir = nodeModules.resolve("@nodegui/nodegui")
            Files.createDirectories(nodeguiDir)
            nodeguiDir.resolve("package.json").writeText(
                """
                {
                  "name": "@nodegui/nodegui",
                  "version": "0.60.0-bridge",
                  "main": "index.js"
                }
                """.trimIndent() + "\n",
            )
            nodeguiDir.resolve("index.js").writeText(NODEGUI_BRIDGE_SHIM_JS)

            // 2. libui-node
            val libuiDir = nodeModules.resolve("libui-node")
            Files.createDirectories(libuiDir)
            libuiDir.resolve("package.json").writeText(
                """
                {
                  "name": "libui-node",
                  "version": "0.3.0-bridge",
                  "main": "index.js"
                }
                """.trimIndent() + "\n",
            )
            libuiDir.resolve("index.js").writeText(NODEGUI_BRIDGE_SHIM_JS)

            // 3. x11
            val x11Dir = nodeModules.resolve("x11")
            Files.createDirectories(x11Dir)
            x11Dir.resolve("package.json").writeText(
                """
                {
                  "name": "x11",
                  "version": "2.3.0-bridge",
                  "main": "index.js"
                }
                """.trimIndent() + "\n",
            )
            x11Dir.resolve("index.js").writeText(NODEGUI_BRIDGE_SHIM_JS)
        }
    }

    /**
     * Runs an interactive GUI process (SwiftUI or NodeGUI/X11) while streaming `ViewNode` JSON trees
     * from `stdout` to `ProgramIo.frame(...)` and forwarding callback JSON lines from `ProgramIo.stdin`
     * to `child.stdin`.
     */
    suspend fun execInteractiveBridge(
        host: RuntimeHost,
        command: List<String>,
        workingDir: Path,
        env: Map<String, String>,
        projectName: String,
        displayCommand: String,
        stepName: String,
        ctx: TaskContext,
        buildCtx: BuildContext?,
    ): TaskResult = coroutineScope {
        ctx.reportStep(stepName, "started")
        ctx.buildLog.log(BuildLogEntry("$ $displayCommand", BuildLogLevel.INFO))

        // Open the interactive RunConsoleUi session immediately if a BuildContext is wired
        val io: ProgramIo? = buildCtx?.openProgramIo?.invoke(projectName, displayCommand)
        io?.started()

        val child = try {
            host.start(command, workingDir, env)
        } catch (e: Exception) {
            val msg = "Failed to start $displayCommand: ${e.message ?: e.javaClass.simpleName}"
            ctx.buildLog.log(BuildLogEntry(msg, BuildLogLevel.ERROR))
            io?.exited(1)
            ctx.reportStep(stepName, "failed")
            return@coroutineScope TaskResult.Failed(msg, e)
        }

        val frameSeq = AtomicLong(0L)
        val firstFrameLogged = AtomicBoolean(false)

        // Pump interactive callback events from RunScreen (io.stdin) -> child.stdin
        val stdinJob = if (io != null) {
            launch(Dispatchers.IO) {
                try {
                    val buf = ByteArray(4096)
                    val input = io.stdin
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        if (n > 0) {
                            child.stdin.write(buf, 0, n)
                            child.stdin.flush()
                        }
                    }
                } catch (_: Exception) {
                } finally {
                    runCatching { child.stdin.close() }
                }
            }
        } else null

        val stdoutJob = launch(Dispatchers.IO) {
            try {
                child.stdout.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    lines.forEach { line ->
                        if (isBridgeJsonLine(line)) {
                            val seq = frameSeq.incrementAndGet()
                            val trimmed = line.trim()
                            io?.frame(trimmed, -1, -1, seq)
                            if (firstFrameLogged.compareAndSet(false, true)) {
                                val msg = "✓ GUI Bridge connected — live interactive window active in Run view"
                                ctx.buildLog.log(BuildLogEntry(msg, BuildLogLevel.INFO))
                                io?.stdout("$msg\n")
                                // In headless unit tests where no UI ProgramIo is attached, close stdin
                                // after the first frame so the process exits cleanly.
                                if (io == null) {
                                    runCatching { child.stdin.close() }
                                }
                            }
                        } else {
                            ctx.buildLog.log(BuildLogEntry(line, BuildLogLevel.INFO))
                            io?.stdout("$line\n")
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }

        val stderrJob = launch(Dispatchers.IO) {
            try {
                child.stderr.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    lines.forEach { line ->
                        val level = if (line.contains("error:", ignoreCase = true)) BuildLogLevel.ERROR else BuildLogLevel.INFO
                        ctx.buildLog.log(BuildLogEntry(line, level))
                        io?.stdout("$line\n")
                    }
                }
            } catch (_: Exception) {
            }
        }

        try {
            val exitCode = child.awaitExit()
            withContext(NonCancellable) { child.terminate() }
            runCatching { io?.stdin?.close() }
            stdinJob?.cancel()
            withTimeoutOrNull(2_000) {
                stdoutJob.join()
                stderrJob.join()
            }
            io?.exited(exitCode)
            if (exitCode == 0 || firstFrameLogged.get()) {
                ctx.reportStep(stepName, "succeeded")
                TaskResult.Success
            } else {
                ctx.reportStep(stepName, "failed")
                TaskResult.Failed("$displayCommand exited with code $exitCode")
            }
        } catch (e: CancellationException) {
            io?.exited(0)
            ctx.reportStep(stepName, "failed")
            throw e
        } finally {
            withContext(NonCancellable) { child.terminate() }
            runCatching { io?.stdin?.close() }
            runCatching { child.stdin.close() }
            runCatching { child.stdout.close() }
            runCatching { child.stderr.close() }
            stdinJob?.cancel()
            stdoutJob.cancel()
            stderrJob.cancel()
        }
    }

    private suspend fun execSimple(
        host: RuntimeHost,
        command: List<String>,
        workingDir: Path,
        env: Map<String, String>,
        ctx: TaskContext,
        capturedLines: MutableList<String>,
    ): Int = coroutineScope {
        val child = host.start(command, workingDir, env)
        val stdoutJob = launch(Dispatchers.IO) {
            streamSimpleLines(child.stdout) { line ->
                ctx.buildLog.log(BuildLogEntry(line, BuildLogLevel.INFO))
                synchronized(capturedLines) { capturedLines += line }
            }
        }
        val stderrJob = launch(Dispatchers.IO) {
            streamSimpleLines(child.stderr) { line ->
                val level = if (line.contains("error:", ignoreCase = true)) BuildLogLevel.ERROR else BuildLogLevel.WARN
                ctx.buildLog.log(BuildLogEntry(line, level))
                synchronized(capturedLines) { capturedLines += line }
            }
        }
        try {
            runCatching { child.stdin.close() }
            val code = child.awaitExit()
            withContext(NonCancellable) { child.terminate() }
            withTimeoutOrNull(2_000) {
                stdoutJob.join()
                stderrJob.join()
            }
            code
        } finally {
            withContext(NonCancellable) { child.terminate() }
            runCatching { child.stdout.close() }
            runCatching { child.stderr.close() }
            runCatching { child.stdin.close() }
            stdoutJob.cancel()
            stderrJob.cancel()
        }
    }

    private fun streamSimpleLines(input: InputStream, onLine: (String) -> Unit) {
        try {
            input.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEach { onLine(it) }
            }
        } catch (_: Exception) {
        }
    }

    private val NODEGUI_BRIDGE_SHIM_JS = """
        'use strict';
        const readline = require('readline');

        let nextCallbackId = 1;
        const callbacks = new Map();
        const activeWindows = [];
        let renderScheduled = false;
        let clipboardText = '';

        function registerCallback(fn) {
          const id = nextCallbackId++;
          callbacks.set(String(id), fn);
          return id;
        }

        function scheduleRender() {
          if (renderScheduled || activeWindows.length === 0) return;
          renderScheduled = true;
          queueMicrotask(() => {
            renderScheduled = false;
            emitActiveWindow();
          });
        }

        function hexToArgb(hex) {
          if (!hex || typeof hex !== 'string') return null;
          let clean = hex.trim().replace(/^#/, '');
          if (clean.toLowerCase() === 'white') clean = 'FFFFFF';
          if (clean.toLowerCase() === 'black') clean = '000000';
          if (clean.length === 3) {
            clean = clean.split('').map(c => c + c).join('');
          }
          if (clean.length === 6) {
            const rgb = parseInt(clean, 16);
            if (Number.isNaN(rgb)) return null;
            return ((0xFF000000 | rgb) >>> 0);
          }
          return null;
        }

        function parseCssRules(cssText) {
          const rules = [];
          if (!cssText || typeof cssText !== 'string') return rules;
          const cleaned = cssText.replace(/\/\*[\s\S]*?\*\//g, '');
          const regex = /([^{]+)\{([^}]*)\}/g;
          let match;
          while ((match = regex.exec(cleaned)) !== null) {
            const selectors = match[1].split(',').map(s => s.trim()).filter(Boolean);
            const props = {};
            match[2].split(';').forEach(decl => {
              const idx = decl.indexOf(':');
              if (idx > 0) {
                const k = decl.slice(0, idx).trim().toLowerCase();
                const v = decl.slice(idx + 1).trim().replace(/^['"]|['"]$/g, '');
                props[k] = v;
              }
            });
            rules.push({ selectors, props });
          }
          return rules;
        }

        function resolveStylesForWidget(widget, ancestorRules, parentObjectName) {
          const allRules = [...ancestorRules, ...parseCssRules(widget._styleSheet)];
          const matched = {};
          const objName = widget._objectName || '';
          const typeName = widget._widgetType || 'QWidget';
          for (const rule of allRules) {
            for (const sel of rule.selectors) {
              if (sel.includes(':pressed') || sel.includes(':hover')) continue;
              const matches =
                sel === '*' ||
                sel === typeName ||
                (objName && sel === '#' + objName) ||
                (parentObjectName && sel === '#' + parentObjectName + ' ' + typeName) ||
                (parentObjectName && sel === '#' + parentObjectName + ' *');
              if (matches) {
                Object.assign(matched, rule.props);
              }
            }
          }
          return { matched, allRules };
        }

        class NativeElement {}
        class FlexNode {}

        class QKeyEvent {
          constructor(nativeEvt) { this._text = (nativeEvt && nativeEvt.text) || ''; }
          text() { return this._text; }
        }

        class QWidget {
          constructor() {
            this._widgetType = 'QWidget';
            this._objectName = '';
            this._styleSheet = '';
            this._layout = null;
            this._listeners = new Map();
            this._flexNode = new FlexNode();
            this._windowTitle = '';
            this._centralWidget = null;
            this._children = [];
          }
          setObjectName(name) { this._objectName = String(name || ''); scheduleRender(); }
          objectName() { return this._objectName; }
          setStyleSheet(css) { this._styleSheet = String(css || ''); scheduleRender(); }
          styleSheet() { return this._styleSheet; }
          setLayout(layout) {
            this._layout = layout;
            if (layout) layout._owner = this;
            scheduleRender();
          }
          layout() { return this._layout; }
          getFlexNode() { return this._flexNode; }
          setFixedSize(w, h) { this._width = w; this._height = h; }
          resize(w, h) { this._width = w; this._height = h; }
          setWindowTitle(title) { this._windowTitle = String(title || ''); scheduleRender(); }
          windowTitle() { return this._windowTitle; }
          addEventListener(evt, fn) {
            if (!this._listeners.has(evt)) this._listeners.set(evt, []);
            this._listeners.get(evt).push(fn);
          }
          _emit(evt, arg) {
            const list = this._listeners.get(evt) || [];
            for (const fn of list) fn(arg);
            scheduleRender();
          }
          show() {
            if (!activeWindows.includes(this)) activeWindows.push(this);
            process.stderr.write('[NodeGUI Bridge] Window shown — streaming live ViewNode tree\n');
            emitActiveWindow();
          }
          close() {}
          toViewNode(ancestorRules = [], parentObjName = '', pathPrefix = 'root') {
            const { matched, allRules } = resolveStylesForWidget(this, ancestorRules, parentObjName);
            const id = pathPrefix + '/' + (this._objectName || this._widgetType);
            const childWidgets = this._layout ? this._layout._widgets : this._children;
            const isRow = matched['flex-direction'] === 'row' || (this._layout && this._layout._isRow);
            const children = childWidgets.map((c, idx) =>
              c.toViewNode(allRules, this._objectName || parentObjName, id + '/' + idx)
            );
            const equalWidth = isRow && childWidgets.length >= 2 && childWidgets.every(w => w._widgetType === 'QPushButton' || w._widgetType === 'QLabel');
            const props = {
              spacing: 6,
              alignment: isRow ? 'center' : 'leading',
              equalWidth,
            };
            const modifiers = [
              { kind: 'frame', args: { fillWidth: true } },
            ];
            if (matched['background'] && matched['background'].startsWith('#')) {
              const argb = hexToArgb(matched['background']);
              if (argb !== null) {
                modifiers.push({ kind: 'background', args: { color: argb } });
                modifiers.push({ kind: 'cornerRadius', args: { radius: 8 } });
              }
            }
            if (matched['padding']) {
              const p = Math.min(parseFloat(matched['padding']) || 6, 10);
              modifiers.push({ kind: 'padding', args: { top: p, bottom: p, leading: p, trailing: p } });
            }
            return {
              type: isRow ? 'HStack' : 'VStack',
              id,
              props,
              modifiers,
              children,
            };
          }
        }

        class QMainWindow extends QWidget {
          constructor() {
            super();
            this._widgetType = 'QMainWindow';
          }
          setCentralWidget(widget) {
            this._centralWidget = widget;
            scheduleRender();
          }
          centralWidget() { return this._centralWidget; }
          toViewNode() {
            const rootChild = this._centralWidget
              ? this._centralWidget.toViewNode([], '', 'root/central')
              : { type: 'VStack', id: 'root/empty', props: {}, modifiers: [], children: [] };
            const { matched } = resolveStylesForWidget(this._centralWidget || this, [], '');
            const cssBg = matched['background'] || matched['background-color'] || '';
            const windowBg = cssBg.startsWith('#') ? cssBg : '#F2F2F7';
            let pkgName = '';
            try {
              const fs = require('fs');
              const path = require('path');
              const pkgPath = path.join(process.cwd(), 'package.json');
              if (fs.existsSync(pkgPath)) {
                pkgName = JSON.parse(fs.readFileSync(pkgPath, 'utf8')).name || '';
              }
            } catch (_) {}
            const title = this._windowTitle || pkgName || 'NodeGUI Window';
            return {
              type: 'ScrollView',
              id: 'root',
              props: {
                windowTitle: title,
                windowBackground: windowBg,
              },
              modifiers: [
                { kind: 'frame', args: { fillWidth: true } },
              ],
              children: [rootChild],
            };
          }
        }

        class FlexLayout {
          constructor() {
            this._widgets = [];
            this._isRow = false;
          }
          setFlexNode() {}
          addWidget(widget) {
            if (widget && !this._widgets.includes(widget)) {
              this._widgets.push(widget);
              scheduleRender();
            }
          }
        }

        class QBoxLayout extends FlexLayout {
          constructor(dir) {
            super();
            this._isRow = dir === 0 || dir === 'LeftToRight' || dir === 'row';
          }
          setDirection(dir) {
            this._isRow = dir === 0 || dir === 'LeftToRight' || dir === 'row';
          }
        }

        class QLabel extends QWidget {
          constructor(parent) {
            super();
            this._widgetType = 'QLabel';
            this._text = '';
          }
          setText(t) {
            this._text = String(t ?? '');
            scheduleRender();
          }
          text() { return this._text; }
          toViewNode(ancestorRules = [], parentObjName = '', pathPrefix = 'root') {
            const { matched } = resolveStylesForWidget(this, ancestorRules, parentObjName);
            const isRight = (matched['qproperty-alignment'] || '').includes('AlignRight');
            const style = {};
            if (matched['font-size']) {
              const px = parseFloat(matched['font-size']);
              if (!Number.isNaN(px)) style.fontSize = Math.min(px, 34);
            }
            if (matched['color']) {
              const argb = hexToArgb(matched['color']);
              if (argb !== null) style.color = argb;
            }
            if (isRight) {
              style.textAlign = 'right';
              style.fontFamily = 'monospace';
              style.fontWeight = 'semibold';
            }
            return {
              type: 'Composable',
              id: pathPrefix + '/' + (this._objectName || 'label'),
              props: {
                name: 'CupertinoText',
                text: this._text,
                style,
              },
              modifiers: isRight ? [{ kind: 'frame', args: { fillWidth: true } }] : [],
              children: [],
            };
          }
        }

        class QPushButton extends QWidget {
          constructor() {
            super();
            this._widgetType = 'QPushButton';
            this._text = 'Button';
          }
          setText(t) {
            this._text = String(t ?? '');
            scheduleRender();
          }
          text() { return this._text; }
          setFlat() {}
          toViewNode(ancestorRules = [], parentObjName = '', pathPrefix = 'root') {
            const { matched } = resolveStylesForWidget(this, ancestorRules, parentObjName);
            const onTapId = registerCallback(() => {
              this._emit('clicked');
            });
            const id = pathPrefix + '/' + (this._objectName || this._text);
            const props = {
              name: 'CupertinoButton',
              onClick: onTapId,
            };
            if (matched['background'] && matched['background'].startsWith('#')) {
              const bgArgb = hexToArgb(matched['background']);
              if (bgArgb !== null) props.containerColor = bgArgb;
            }
            const fgArgb = matched['color'] ? hexToArgb(matched['color']) : 0xFFFFFFFF;
            if (fgArgb !== null) props.contentColor = fgArgb;
            const modifiers = [
              { kind: 'frame', args: { fillWidth: true } },
            ];
            if (matched['height']) {
              const h = parseFloat(matched['height']);
              if (!Number.isNaN(h)) {
                modifiers.push({ kind: 'frame', args: { height: Math.min(h, 46) } });
              }
            }
            return {
              type: 'Composable',
              id,
              props,
              modifiers,
              children: [
                {
                  type: 'CupertinoSlot',
                  id: id + '/content',
                  props: { name: 'content' },
                  modifiers: [],
                  children: [
                    {
                      type: 'Composable',
                      id: id + '/label',
                      props: {
                        name: 'CupertinoText',
                        text: this._text,
                        style: {
                          color: fgArgb || 0xFFFFFFFF,
                          fontSize: 16,
                          fontWeight: 'semibold',
                        },
                      },
                      modifiers: [],
                      children: [],
                    },
                  ],
                },
              ],
            };
          }
        }

        class QCheckBox extends QWidget {
          constructor() {
            super();
            this._widgetType = 'QCheckBox';
            this._text = '';
            this._checked = false;
          }
          setText(t) { this._text = String(t ?? ''); scheduleRender(); }
          text() { return this._text; }
          setChecked(c) { this._checked = Boolean(c); scheduleRender(); }
          isChecked() { return this._checked; }
          toViewNode(ancestorRules = [], parentObjName = '', pathPrefix = 'root') {
            const onChangeId = registerCallback((val) => {
              this._checked = Boolean(val);
              this._emit('toggled', this._checked);
              this._emit('stateChanged', this._checked ? 2 : 0);
            });
            const id = pathPrefix + '/' + (this._objectName || 'checkbox');
            return {
              type: 'HStack',
              id,
              props: { spacing: 10, alignment: 'center' },
              modifiers: [{ kind: 'frame', args: { fillWidth: true } }],
              children: [
                {
                  type: 'Composable',
                  id: id + '/box',
                  props: {
                    name: 'CupertinoCheckBox',
                    checked: this._checked,
                    onCheckedChange: onChangeId,
                  },
                  modifiers: [],
                  children: [],
                },
                {
                  type: 'Composable',
                  id: id + '/label',
                  props: {
                    name: 'CupertinoText',
                    text: this._text,
                  },
                  modifiers: [],
                  children: [],
                },
                {
                  type: 'Spacer',
                  id: id + '/spacer',
                  props: {},
                  modifiers: [],
                  children: [],
                },
              ],
            };
          }
        }

        class QLineEdit extends QWidget {
          constructor() {
            super();
            this._widgetType = 'QLineEdit';
            this._text = '';
            this._placeholder = '';
            this._readOnly = false;
          }
          setText(t) { this._text = String(t ?? ''); scheduleRender(); }
          text() { return this._text; }
          setPlaceholderText(t) { this._placeholder = String(t ?? ''); scheduleRender(); }
          setReadOnly(r) { this._readOnly = Boolean(r); scheduleRender(); }
          toViewNode(ancestorRules = [], parentObjName = '', pathPrefix = 'root') {
            const onChangeId = registerCallback((val) => {
              this._text = String(val ?? '');
              this._emit('textChanged', this._text);
            });
            const id = pathPrefix + '/' + (this._objectName || 'lineedit');
            return {
              type: 'Composable',
              id,
              props: {
                name: 'CupertinoBorderedTextField',
                value: this._text,
                readOnly: this._readOnly,
                singleLine: true,
                onValueChange: onChangeId,
              },
              modifiers: [{ kind: 'frame', args: { fillWidth: true } }],
              children: [
                {
                  type: 'CupertinoSlot',
                  id: id + '/placeholder',
                  props: { name: 'placeholder' },
                  modifiers: [],
                  children: [
                    {
                      type: 'Composable',
                      id: id + '/placeholder/text',
                      props: { name: 'CupertinoText', text: this._placeholder || '' },
                      modifiers: [],
                      children: [],
                    },
                  ],
                },
              ],
            };
          }
        }

        class QPlainTextEdit extends QWidget {
          constructor() {
            super();
            this._widgetType = 'QPlainTextEdit';
            this._text = '';
            this._placeholder = '';
            this._readOnly = false;
          }
          setPlainText(t) { this._text = String(t ?? ''); scheduleRender(); }
          toPlainText() { return this._text; }
          setPlaceholderText(t) { this._placeholder = String(t ?? ''); scheduleRender(); }
          setReadOnly(r) { this._readOnly = Boolean(r); scheduleRender(); }
          setWordWrapMode() {}
          toViewNode(ancestorRules = [], parentObjName = '', pathPrefix = 'root') {
            const onChangeId = registerCallback((val) => {
              this._text = String(val ?? '');
              this._emit('textChanged');
            });
            const id = pathPrefix + '/' + (this._objectName || 'textarea');
            return {
              type: 'Composable',
              id,
              props: {
                name: 'CupertinoBorderedTextField',
                value: this._text,
                readOnly: this._readOnly,
                singleLine: false,
                minLines: 2,
                maxLines: 3,
                onValueChange: onChangeId,
              },
              modifiers: [{ kind: 'frame', args: { fillWidth: true } }],
              children: [
                {
                  type: 'CupertinoSlot',
                  id: id + '/placeholder',
                  props: { name: 'placeholder' },
                  modifiers: [],
                  children: [
                    {
                      type: 'Composable',
                      id: id + '/placeholder/text',
                      props: {
                        name: 'CupertinoText',
                        text: this._placeholder || '',
                      },
                      modifiers: [],
                      children: [],
                    },
                  ],
                },
              ],
            };
          }
        }

        class QSlider extends QWidget {
          constructor() {
            super();
            this._widgetType = 'QSlider';
            this._value = 50;
            this._min = 0;
            this._max = 100;
          }
          setValue(v) { this._value = Number(v) || 0; scheduleRender(); }
          value() { return this._value; }
          setMinimum(v) { this._min = Number(v) || 0; }
          setMaximum(v) { this._max = Number(v) || 100; }
          setOrientation() {}
          toViewNode(ancestorRules = [], parentObjName = '', pathPrefix = 'root') {
            const onChangeId = registerCallback((val) => {
              this._value = Math.round(Number(val) || 0);
              this._emit('valueChanged', this._value);
            });
            return {
              type: 'Slider',
              id: pathPrefix + '/' + (this._objectName || 'slider'),
              props: {
                value: this._value,
                min: this._min,
                max: this._max,
                onChange: onChangeId,
              },
              modifiers: [{ kind: 'frame', args: { fillWidth: true } }],
              children: [],
            };
          }
        }

        class QProgressBar extends QWidget {
          constructor() {
            super();
            this._widgetType = 'QProgressBar';
            this._value = 0;
            this._max = 100;
          }
          setValue(v) { this._value = Number(v) || 0; scheduleRender(); }
          setMaximum(m) { this._max = Number(m) || 100; }
          toViewNode(ancestorRules = [], parentObjName = '', pathPrefix = 'root') {
            return {
              type: 'ProgressView',
              id: pathPrefix + '/' + (this._objectName || 'progress'),
              props: { value: this._value / Math.max(this._max, 1) },
              modifiers: [{ kind: 'frame', args: { fillWidth: true } }],
              children: [],
            };
          }
        }

        const QApplication = {
          instance: () => QApplication,
          clipboard: () => ({
            setText: (t) => {
              clipboardText = String(t ?? '');
              process.stderr.write('[NodeGUI Bridge] Copied to clipboard: ' + clipboardText + '\n');
            },
            text: () => clipboardText,
          }),
          exec: () => {},
        };

        const QPushButtonEvents = { clicked: 'clicked', pressed: 'pressed', released: 'released' };
        const QCheckBoxEvents = { toggled: 'toggled', stateChanged: 'stateChanged' };
        const QLineEditEvents = { textChanged: 'textChanged' };
        const BaseWidgetEvents = { KeyRelease: 'KeyRelease', KeyPress: 'KeyPress' };
        const Direction = { LeftToRight: 0, RightToLeft: 1, TopToBottom: 2, BottomToTop: 3 };
        const Orientation = { Horizontal: 1, Vertical: 2 };

        function emitActiveWindow() {
          const win = activeWindows[activeWindows.length - 1];
          if (!win) return;
          callbacks.clear();
          nextCallbackId = 1;
          const tree = win.toViewNode();
          process.stdout.write(JSON.stringify(tree) + '\n');
        }

        // Listen for callback events on stdin from RunScreen
        const rl = readline.createInterface({ input: process.stdin, terminal: false });
        rl.on('line', (line) => {
          const trimmed = line.trim();
          if (!trimmed.startsWith('{')) return;
          try {
            const evt = JSON.parse(trimmed);
            const fn = callbacks.get(String(evt.id));
            if (fn) {
              fn(evt.value);
              scheduleRender();
            }
          } catch (_) {}
        });
        rl.on('close', () => {
          process.exit(0);
        });

        module.exports = {
          QMainWindow,
          QWidget,
          FlexLayout,
          QBoxLayout,
          QLabel,
          QPushButton,
          QPushButtonEvents,
          QCheckBox,
          QCheckBoxEvents,
          QLineEdit,
          QLineEditEvents,
          QPlainTextEdit,
          QSlider,
          QProgressBar,
          QApplication,
          BaseWidgetEvents,
          Direction,
          Orientation,
          QKeyEvent,
          NativeElement,
        };
    """.trimIndent()
}
