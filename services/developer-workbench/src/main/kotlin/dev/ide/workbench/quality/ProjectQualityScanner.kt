package dev.ide.workbench.quality

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.Locator
import org.xml.sax.SAXException
import org.xml.sax.SAXParseException
import org.xml.sax.helpers.DefaultHandler
import java.io.IOException
import java.io.StringReader
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.time.Clock
import java.util.Locale
import javax.xml.parsers.SAXParserFactory

/** Read-only source/configuration preflight, NOT Android lint or a merged-release-manifest verifier. */
class ProjectQualityScanner(
    private val config: ProjectScanConfig = ProjectScanConfig(),
    private val clock: Clock = Clock.systemUTC(),
) {
    fun scan(root: Path): QualityReport = try {
        Scan(root).run()
    } catch (error: QualityException) { throw error }
    catch (_: IOException) { throw QualityException(QualityErrorCode.IO_ERROR, "The project directory could not be read.") }
    catch (_: SecurityException) { throw QualityException(QualityErrorCode.IO_ERROR, "The project directory is not accessible with current permissions.") }

    private data class Source(val path: Path, val relative: String, val text: String) {
        fun lineAt(offset: Int): Int = text.take(offset.coerceIn(0, text.length)).count { it == '\n' } + 1
        fun evidence(line: Int): String = safeSnippet(text.lineSequence().drop((line - 1).coerceAtLeast(0)).firstOrNull().orEmpty())
    }
    private data class XmlNode(val name: String, val attrs: Map<String, String>, val line: Int, val parent: Int?) {
        fun android(name: String): String? = attrs["{http://schemas.android.com/apk/res/android}$name"]
    }

    private inner class Scan(requestedRoot: Path) {
        private val root: Path
        private val warnings = linkedSetOf<String>()
        private val findings = ArrayList<QualityFinding>()
        private val sources = ArrayList<Source>()
        private val regularFiles = ArrayList<Path>()
        private val mandatoryExclusions = setOf(".git", ".gradle", "build", "node_modules")
        private val exclusions = config.excludedDirectories + mandatoryExclusions
        private var visited = 0
        private var bytes = 0L
        private var symlinks = 0
        private var skipped = 0
        private var partial = false

        init {
            val normalized = requestedRoot.toAbsolutePath().normalize()
            if (!Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(normalized)) {
                throw QualityException(QualityErrorCode.UNSAFE_PATH, "Select a real project directory, not a symbolic link or special file.")
            }
            root = normalized.toRealPath()
        }

        private fun report(rule: String, severity: Severity, source: Source, line: Int, reason: String, remediation: String,
                           status: FindingStatus = FindingStatus.REVIEW_NEEDED, evidence: String? = null) {
            if (findings.size >= config.maxFindings) {
                partial = true; warnings += "Finding limit reached; remaining diagnostics are omitted."; return
            }
            findings += QualityFinding(rule, severity, status, safePathLabel(source.path), maxOf(1, line), reason,
                evidence?.let(::safeSnippet) ?: source.evidence(line), remediation)
        }

        private fun markPartial(message: String) { partial = true; warnings += message }
        private fun countEntry(): Boolean {
            if (visited >= config.maxEntries) { markPartial("Entry limit reached; project coverage is partial."); return false }
            visited++; return true
        }
        private fun relative(path: Path): String = root.relativize(path).toString().replace('\\', '/')
        private fun safeToRead(path: Path): Boolean {
            if (!path.normalize().startsWith(root)) return false
            var current = root
            for (part in root.relativize(path)) {
                current = current.resolve(part)
                if (Files.isSymbolicLink(current)) return false
            }
            return path.toRealPath().startsWith(root)
        }

        fun run(): QualityReport {
            Files.walkFileTree(root, emptySet(), config.maxDepth, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (!countEntry()) return FileVisitResult.TERMINATE
                    if (dir != root && dir.fileName.toString() in exclusions) return FileVisitResult.SKIP_SUBTREE
                    if (attrs.isSymbolicLink || !safeToRead(dir)) { symlinks++; return FileVisitResult.SKIP_SUBTREE }
                    return FileVisitResult.CONTINUE
                }
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (!countEntry()) return FileVisitResult.TERMINATE
                    if (attrs.isSymbolicLink) { symlinks++; return FileVisitResult.CONTINUE }
                    if (attrs.isDirectory) { markPartial("Depth limit reached; nested directories were not inspected."); return FileVisitResult.CONTINUE }
                    if (!attrs.isRegularFile) { skipped++; return FileVisitResult.CONTINUE }
                    regularFiles.add(file)
                    if (!isCandidate(relative(file))) return FileVisitResult.CONTINUE
                    if (attrs.size() > config.maxFileBytes || bytes + attrs.size() > config.maxTotalBytes) {
                        skipped++; markPartial("File or total-byte limit reached; some configuration files were not inspected."); return FileVisitResult.CONTINUE
                    }
                    try {
                        if (!safeToRead(file)) { symlinks++; return FileVisitResult.CONTINUE }
                        val content = readRegularFile(file, minOf(config.maxFileBytes, (config.maxTotalBytes - bytes).toInt()))
                        bytes += content.size
                        sources += Source(file, relative(file), decodeUtf8(content))
                    } catch (_: QualityException) { skipped++; markPartial("A configuration file was unreadable, changed, too large, or not UTF-8; it was skipped.") }
                    catch (_: IOException) { skipped++; markPartial("A configuration file could not be read; coverage is partial.") }
                    return FileVisitResult.CONTINUE
                }
                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                    if (!countEntry()) return FileVisitResult.TERMINATE
                    skipped++; markPartial("A project entry was inaccessible; coverage is partial.")
                    return FileVisitResult.CONTINUE
                }
            })
            sources.sortBy { it.relative }
            for (source in sources) when {
                source.path.fileName.toString() == "AndroidManifest.xml" -> inspectManifest(source)
                source.relative.matches(Regex(".*(?:^|/)res/xml(?:-[^/]+)?/[^/]+\\.xml")) -> inspectPolicyXml(source)
                source.path.fileName.toString() == "package.json" -> inspectPackage(source)
                source.path.fileName.toString() == "gradle-wrapper.properties" -> inspectWrapper(source)
                source.path.fileName.toString().let { it.endsWith(".gradle") || it.endsWith(".gradle.kts") || it.endsWith(".versions.toml") } -> inspectGradle(source)
            }
            val tests = regularFiles.count { isTest(relative(it)) }
            val locks = regularFiles.count { isLock(it.fileName.toString()) }
            val buildFiles = sources.filter { isBuildConfiguration(it.path.fileName.toString()) }
            val anchor = buildFiles.firstOrNull() ?: sources.firstOrNull() ?: Source(root, "", "")
            if (buildFiles.isNotEmpty()) {
                report("TEST_FILES", if (tests == 0) Severity.WARNING else Severity.INFO, anchor, 1,
                    if (tests == 0) "No conventional test source files were observed in the inspected scope${if (partial) " (partial scan)" else ""}. This does not prove the project has no tests."
                    else "$tests conventional test source files were observed; their content or execution result is not validated.",
                    "Review the project's test source sets and trusted unit/instrumentation task configuration; execute tests separately.",
                    if (tests == 0) FindingStatus.REVIEW_NEEDED else FindingStatus.OBSERVED,
                    evidence = "Test-file path conventions in the bounded project walk")
                report("LOCKFILES", if (locks == 0) Severity.WARNING else Severity.INFO, anchor, 1,
                    if (locks == 0) "No recognized dependency lockfile was observed in the inspected scope; resolution reproducibility needs review."
                    else "$locks dependency lockfile paths were observed; freshness and enforced use are not verified.",
                    "Review committed lockfiles, immutable/frozen installs, resolved Gradle dependency locking, and dependency verification.",
                    if (locks == 0) FindingStatus.REVIEW_NEEDED else FindingStatus.OBSERVED,
                    evidence = "Recognized dependency-lock paths in the bounded project walk")
                val gradle = buildFiles.firstOrNull { it.path.fileName.toString().endsWith(".gradle.kts") || it.path.fileName.toString().endsWith(".gradle") }
                if (gradle != null && regularFiles.none { it.fileName.toString() == "gradle-wrapper.properties" }) {
                    report("GRADLE_WRAPPER_NOT_OBSERVED", Severity.WARNING, gradle, 1,
                        "A Gradle build configuration was observed but no wrapper properties were found in this scope.",
                        "Review the project root and trusted Gradle wrapper/version/checksum configuration; no wrapper is generated by this scanner.")
                }
            } else warnings += "No supported build configuration was observed; this scan cannot characterize build/test setup."
            if (sources.isEmpty()) warnings += "No supported configuration files were inspected. An empty report is not a quality pass."
            if (symlinks > 0) warnings += "$symlinks symbolic-link entries or replaced paths were skipped without reading their targets."
            return QualityReport(ReportKind.PROJECT, "Project quality preflight — review needed", clock.instant(),
                findings = findings.distinctBy { listOf(it.ruleId, it.file, it.line, it.reason) }.sortedWith(compareBy<QualityFinding> { it.file }.thenBy { it.line }.thenBy { it.ruleId }),
                scanSummary = ScanSummary(safePathLabel(root), visited, sources.size, bytes, symlinks, skipped, exclusions.sorted(), partial,
                    tests, locks, buildFiles.size), warnings = warnings.toList(),
                checklists = VerificationChecklists.forThemes(buildList {
                    if (sources.any { it.path.fileName.toString() == "AndroidManifest.xml" }) add(PrimaryTheme.SECURITY_PRIVACY)
                    if (buildFiles.isNotEmpty()) add(PrimaryTheme.DELIVERY_BUILD)
                }), caveats = listOf(
                    "Available: bounded read-only source/configuration observations with file:line evidence and manual review suggestions. No patches, scripts, builds, network calls, or tests are executed.",
                    "Unsupported as a verdict: exhaustive Android lint, dependency vulnerability auditing, Gradle/manifest-placeholder evaluation, manifest merging, runtime behavior, exploitability, or test success. No aggregate quality/security pass is produced.",
                    "Source declarations may be changed by flavors, overlays, plugins, target SDK defaults, tools: directives, and manifest merging. Inspect the merged release manifest and real device behavior separately.",
                    "Excluded/generated directories and all symbolic links are skipped. Absence checks refer only to the inspected scope; use a stable project snapshot while scanning.",
                    "Source evidence is best-effort redacted. File paths remain exact for navigation and can reveal local usernames; review exports before sharing.",
                ))
        }

        private fun parseXml(source: Source): List<XmlNode>? {
            if (Regex("(?i)<!\\s*(?:DOCTYPE|ENTITY)").containsMatchIn(source.text)) {
                report("XML_UNSAFE_DECLARATION", Severity.WARNING, source, 1,
                    "DTD/entity declarations are unsupported and were not expanded; this XML was not inspected.",
                    "Review this configuration manually with external entity resolution disabled.")
                markPartial("XML with DTD/entity declarations was skipped; no external resources were loaded.")
                return null
            }
            return try {
                val factory = SAXParserFactory.newInstance().apply { isNamespaceAware = true; isValidating = false }
                // Some Android SAX providers do not implement every Xerces feature. The lexical DTD rejection,
                // disabled validation and rejecting entity resolver remain mandatory fallback protections.
                for ((name, value) in listOf(
                    "http://xml.org/sax/features/external-general-entities" to false,
                    "http://xml.org/sax/features/external-parameter-entities" to false,
                    "http://apache.org/xml/features/nonvalidating/load-external-dtd" to false,
                    "http://apache.org/xml/features/disallow-doctype-decl" to true,
                    "http://javax.xml.XMLConstants/feature/secure-processing" to true,
                )) try { factory.setFeature(name, value) } catch (_: Exception) { /* Rejecting resolver below is always installed. */ }
                val nodes = ArrayList<XmlNode>()
                val stack = ArrayList<Int>()
                val handler = object : DefaultHandler() {
                    private var locator: Locator? = null
                    override fun setDocumentLocator(value: Locator?) { locator = value }
                    override fun resolveEntity(publicId: String?, systemId: String?): InputSource = throw SAXException("External entity resolution disabled")
                    override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes) {
                        if (stack.size >= 64 || nodes.size >= 20_000) throw SAXException("XML structure limit")
                        val attrs = (0 until attributes.length).associate { i ->
                            val namespace = attributes.getURI(i).orEmpty()
                            val name = attributes.getLocalName(i).ifBlank { attributes.getQName(i) }
                            (if (namespace.isEmpty()) name else "{$namespace}$name") to attributes.getValue(i)
                        }
                        val name = localName.orEmpty().ifBlank { qName.orEmpty() }
                        nodes += XmlNode(if (uri.isNullOrEmpty()) name else "{$uri}$name", attrs, locator?.lineNumber ?: 1, stack.lastOrNull())
                        stack += nodes.lastIndex
                    }
                    override fun endElement(uri: String?, localName: String?, qName: String?) { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) }
                    override fun error(e: SAXParseException) { throw e }
                    override fun fatalError(e: SAXParseException) { throw e }
                }
                val reader = factory.newSAXParser().xmlReader
                reader.entityResolver = handler; reader.contentHandler = handler; reader.errorHandler = handler
                reader.parse(InputSource(StringReader(source.text)))
                nodes
            } catch (_: Exception) {
                report("XML_NOT_INSPECTED", Severity.WARNING, source, 1,
                    "XML is malformed, exceeds structural limits, or cannot be parsed safely; no manifest/policy conclusions were drawn.",
                    "Validate the XML locally and rerun the scanner. Parser messages are withheld to avoid disclosing source data.")
                markPartial("At least one XML file could not be inspected safely.")
                null
            }
        }

        private fun inspectManifest(source: Source) {
            val nodes = parseXml(source) ?: return
            if (nodes.firstOrNull()?.name != "manifest") return
            val application = nodes.firstOrNull { it.name == "application" && it.parent == 0 } ?: return
            val debugOnly = source.relative.split('/').any { it == "debug" || it.endsWith("Debug") }
            val cleartext = application.android("usesCleartextTraffic")
            if (cleartext == "true") report("MANIFEST_CLEARTEXT", if (debugOnly) Severity.INFO else Severity.WARNING, source, application.line,
                "usesCleartextTraffic=true is explicitly declared${if (debugOnly) " in a debug source set" else " outside a debug-only source set"}; actual transport policy depends on the merged manifest/network config.",
                "Review release transport requirements; prefer TLS and narrow any necessary cleartext exceptions.", if (debugOnly) FindingStatus.OBSERVED else FindingStatus.REVIEW_NEEDED)
            else if (cleartext == "false") report("MANIFEST_CLEARTEXT", Severity.INFO, source, application.line,
                "usesCleartextTraffic=false was observed; this is a declaration, not a verified runtime transport guarantee.",
                "Verify the merged release manifest and any network security configuration.", FindingStatus.OBSERVED)
            else if (cleartext != null || application.android("networkSecurityConfig") == null) report("MANIFEST_TRANSPORT_POLICY", Severity.WARNING, source, application.line,
                "An explicit literal cleartext policy was not observed; defaults, resources, target SDK, or overlays determine behavior.",
                "Review the release target SDK, resolved manifest, and network security configuration before deciding whether a change is needed.")
            when (application.android("debuggable")) {
                "true" -> report("MANIFEST_DEBUGGABLE", if (debugOnly) Severity.INFO else Severity.HIGH, source, application.line,
                    "debuggable=true is declared${if (debugOnly) " in a debug-only source set" else " in a main/release/unspecified source set"}; release output needs verification.",
                    "Inspect the merged release manifest and ensure intended production variants are not debuggable.", if (debugOnly) FindingStatus.OBSERVED else FindingStatus.REVIEW_NEEDED)
                "false" -> report("MANIFEST_DEBUGGABLE", Severity.INFO, source, application.line,
                    "debuggable=false is explicitly declared; merged release output has not been inspected.", "Verify production variants separately.", FindingStatus.OBSERVED)
                null -> Unit
                else -> report("MANIFEST_DEBUGGABLE_UNRESOLVED", Severity.WARNING, source, application.line,
                    "debuggable uses a nonliteral value that this scanner does not resolve.", "Resolve the merged release value using trusted build tooling.")
            }
            val backup = application.android("allowBackup")
            val backupPolicy = listOfNotNull(application.android("fullBackupContent"), application.android("dataExtractionRules"))
            if (backup == "false") report("MANIFEST_BACKUP_POLICY", Severity.INFO, source, application.line,
                "allowBackup=false was observed; OEM/device-transfer and Android-version behavior still require review.",
                "Verify backup and device-to-device transfer behavior on supported devices using non-sensitive fixtures.", FindingStatus.OBSERVED)
            else if (backupPolicy.isEmpty()) report("MANIFEST_BACKUP_POLICY", Severity.WARNING, source, application.line,
                "Backup is not explicitly disabled and no fullBackupContent/dataExtractionRules declaration was observed.",
                "Define the intended backup/transfer policy and review which sensitive files must be excluded; verify supported Android versions.")
            else report("MANIFEST_BACKUP_POLICY", Severity.INFO, source, application.line,
                "A backup policy reference was observed; rule resolution and sensitive-data exclusions are not proven.",
                "Review referenced backup rules, the merged manifest, and backup/restore behavior.", FindingStatus.OBSERVED)
            for (attribute in listOf("networkSecurityConfig", "fullBackupContent", "dataExtractionRules")) {
                val value = application.android(attribute) ?: continue
                val resource = Regex("@xml/([A-Za-z0-9_]+)").matchEntire(value)?.groupValues?.get(1) ?: continue
                val modulePrefix = source.relative.substringBefore("/src/", "")
                if (sources.none { it.relative.startsWith(if (modulePrefix.isEmpty()) "" else "$modulePrefix/") &&
                        it.relative.matches(Regex(".*(?:^|/)res/xml(?:-[^/]+)?/${Regex.escape(resource)}\\.xml")) }) {
                    report("MANIFEST_POLICY_NOT_OBSERVED", Severity.WARNING, source, application.line,
                        "$attribute refers to an XML resource not observed in this scan scope; it may come from another module, dependency, or overlay.",
                        "Resolve the resource in the merged release configuration; no missing-resource build failure is asserted.")
                }
            }
            val permissions = nodes.filter { it.name == "permission" && it.android("protectionLevel")?.split('|')?.any { part -> part.trim() == "signature" } == true }
                .mapNotNull { it.android("name") }.toSet()
            for ((index, node) in nodes.withIndex()) {
                if (node.name in setOf("activity", "activity-alias", "service", "receiver", "provider")) {
                    val exported = node.android("exported")
                    val filters = nodes.indices.filter { nodes[it].name == "intent-filter" && nodes[it].parent == index }
                    val launcher = node.name in setOf("activity", "activity-alias") && filters.any { filter ->
                        nodes.any { it.parent == filter && it.name == "action" && it.android("name") == "android.intent.action.MAIN" } &&
                            nodes.any { it.parent == filter && it.name == "category" && it.android("name") in setOf("android.intent.category.LAUNCHER", "android.intent.category.LEANBACK_LAUNCHER") }
                    }
                    val guard = node.android("permission") ?: application.android("permission")
                    if (exported == "true" && !launcher) report("EXPORTED_COMPONENT", if (guard in permissions) Severity.INFO else Severity.WARNING,
                        source, node.line, "An exported ${node.name} declaration was observed${if (guard in permissions) " with a locally declared signature permission" else if (guard != null) " with a permission whose protection/effective enforcement needs review" else " without an observed component/application permission"}. Export alone does not prove a vulnerability.",
                        "Review whether external callers are required; verify caller authorization, input validation, URI grants, and the merged component permissions.",
                        if (guard in permissions) FindingStatus.OBSERVED else FindingStatus.REVIEW_NEEDED)
                    else if (exported == null && filters.isNotEmpty()) report("EXPORTED_COMPONENT_UNRESOLVED", Severity.WARNING, source, node.line,
                        "A component has an intent filter without an explicit exported value in this source manifest.",
                        "Inspect the merged manifest and target-SDK requirements; Android 12+ requires an explicit value for components with intent filters.")
                    else if (exported != null && exported !in setOf("true", "false")) report("EXPORTED_COMPONENT_UNRESOLVED", Severity.WARNING, source, node.line,
                        "A component's exported value is not a literal boolean and was not resolved.", "Verify the merged release manifest and external-caller requirements.")
                }
                if (node.name == "uses-permission" && node.android("name") in sensitivePermissions) report("SENSITIVE_PERMISSION_DECLARED", Severity.INFO, source, node.line,
                    "A privacy-sensitive Android permission is explicitly requested. Its presence is not itself a defect.",
                    "Review necessity, least privilege, runtime consent, disclosures, and supported-OS behavior.")
            }
        }

        private fun inspectPolicyXml(source: Source) {
            val nodes = parseXml(source) ?: return
            if (nodes.firstOrNull()?.name == "network-security-config") {
                for ((index, node) in nodes.withIndex()) {
                    val debugOverride = generateSequence(index as Int?) { nodes[it].parent }.any { nodes[it].name == "debug-overrides" }
                    if (node.attrs["cleartextTrafficPermitted"] == "true") report("NETWORK_CONFIG_CLEARTEXT", if (debugOverride) Severity.INFO else Severity.WARNING, source, node.line,
                        "cleartextTrafficPermitted=true was observed in a network security configuration${if (debugOverride) " debug override" else ""}; domain scope and whether the file is active need review.",
                        "Review domain-specific exceptions and the manifest's resolved configuration; prefer TLS.", if (debugOverride) FindingStatus.OBSERVED else FindingStatus.REVIEW_NEEDED)
                    if (node.name == "certificates" && node.attrs["src"] == "user" && !debugOverride) report("NETWORK_USER_CERTIFICATES", Severity.WARNING, source, node.line,
                        "User-installed certificate trust is explicitly configured outside debug-overrides; intended release scope needs review.",
                        "Verify whether this trust is required, how narrowly it is scoped, and whether the configuration is used in production.")
                }
            } else if (nodes.firstOrNull()?.name in setOf("full-backup-content", "data-extraction-rules")) {
                for (node in nodes) if (node.name == "include" && node.attrs["domain"] in setOf("root", "external") && node.attrs["path"] in setOf(".", "/", "")) {
                    report("BACKUP_BROAD_INCLUDE", Severity.WARNING, source, node.line,
                        "A broad root/external backup include is declared; this does not establish that sensitive data is present.",
                        "Review included files, exclusions, encryption/transport requirements, and non-sensitive backup round-trip tests.")
                }
            }
        }

        private fun inspectGradle(source: Source) {
            val code = stripComments(source.text)
            val coordinates = Regex("[\"']([A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+:([^\"'\\r\\n]+))[\"']")
            for (match in coordinates.findAll(code)) if (dynamicVersion(match.groupValues[2])) report("DYNAMIC_DEPENDENCY", Severity.WARNING, source, source.lineAt(match.range.first),
                "A literal dependency coordinate uses a changing version/range; reproducibility depends on locking and resolution policy.",
                "Review pinning, committed dependency locks, and checksum/signature verification; do not automatically rewrite dependency versions.")
            if (source.path.fileName.toString().endsWith(".versions.toml")) {
                var versions = false
                var offset = 0
                for (line in code.lineSequence()) {
                    val trimmed = line.substringBefore('#').trim()
                    if (trimmed.startsWith('[')) versions = trimmed == "[versions]"
                    val match = Regex("(?:^[A-Za-z0-9_.-]+|\\bversion)\\s*=\\s*[\"']([^\"']+)[\"']").find(trimmed)
                    if (match != null && (versions || trimmed.contains("version")) && dynamicVersion(match.groupValues[1])) report("DYNAMIC_CATALOG_VERSION", Severity.WARNING, source, source.lineAt(offset),
                        "A version-catalog literal uses a changing version/range.", "Review exact version constraints and dependency locking for reproducible resolution.")
                    offset += line.length + 1
                }
            }
            for (match in Regex("[\"']http://[^\"'\\s]+[\"']").findAll(code)) report("GRADLE_HTTP_URL", Severity.WARNING, source, source.lineAt(match.range.first),
                "A plaintext HTTP URL literal appears in build configuration; this is not proof that it is an active repository.",
                "Review whether the URL is used for dependency/download transport; prefer authenticated HTTPS sources.", evidence = "HTTP URL literal (destination withheld)")
            val releaseStart = Regex("\\brelease\\s*\\{|(?:getByName|named)\\s*\\(\\s*[\"']release[\"']\\s*\\)\\s*\\{")
            for (match in releaseStart.findAll(code)) {
                val end = blockEnd(code, match.range.last)
                val body = code.substring(match.range.last + 1, end)
                val enabled = Regex("\\b(?:isDebuggable|debuggable)\\s*(?:=\\s*|\\s+)true\\b").find(body) ?: continue
                report("GRADLE_RELEASE_DEBUGGABLE", Severity.HIGH, source, source.lineAt(match.range.last + 1 + enabled.range.first),
                    "A literal debuggable=true assignment was observed inside an explicit release block; the Gradle DSL is not evaluated.",
                    "Review the production variant and inspect the merged release manifest; do not ship an unintentionally debuggable build.")
            }
            if (Regex("\\b(?:testImplementation|androidTestImplementation|testInstrumentationRunner|useJUnitPlatform)\\b").containsMatchIn(code)) report("TEST_CONFIGURATION_HINT", Severity.INFO, source, 1,
                "A conventional testing configuration token was observed in build configuration, not a verified test execution.",
                "Review source-set wiring and run trusted test tasks separately.", FindingStatus.OBSERVED, "Testing configuration declaration hint")
        }

        private fun inspectPackage(source: Source) {
            val json = try { parseBoundedJson(source.text, maxDepth = 48, maxTokens = 60_000) as? JsonObject } catch (_: QualityException) { null }
            if (json == null) {
                report("PACKAGE_CONFIG_INVALID", Severity.WARNING, source, 1, "package.json could not be parsed as a bounded JSON object.", "Validate package configuration locally; no scripts were executed.")
                markPartial("A package configuration could not be parsed."); return
            }
            val locked = regularFiles.any { it.parent == source.path.parent && it.fileName.toString() in setOf("package-lock.json", "npm-shrinkwrap.json", "pnpm-lock.yaml", "yarn.lock", "bun.lock", "bun.lockb") }
            for (section in listOf("dependencies", "devDependencies", "optionalDependencies", "peerDependencies")) {
                val dependencies = json[section] as? JsonObject ?: continue
                for ((name, element) in dependencies) {
                    val version = (element as? JsonPrimitive)?.takeIf { it.isString }?.content ?: continue
                    if (!dynamicVersion(version) && !Regex("^(?:[~^><*]|workspace:|git|https?:|github:)").containsMatchIn(version)) continue
                    val offset = source.text.indexOf("\"${name.replace("\"", "\\\"")}\"").coerceAtLeast(0)
                    report("PACKAGE_DEPENDENCY_RANGE", if (locked) Severity.INFO else Severity.WARNING, source, source.lineAt(offset),
                        "A dependency spec is not an exact registry version${if (locked) "; a package lockfile was observed but its enforced use/freshness is unknown" else " and no adjacent package lockfile was observed"}.",
                        "Review the package manager's lockfile and frozen/immutable install policy; ranges with a reviewed lockfile can be intentional.",
                        if (locked) FindingStatus.OBSERVED else FindingStatus.REVIEW_NEEDED,
                        evidence = "Dependency specification in $section: ${safeSnippet(name, 80)} = ${safeSnippet(version, 100)}")
                }
            }
            if ((json["scripts"] as? JsonObject)?.containsKey("test") == true) report("PACKAGE_TEST_SCRIPT", Severity.INFO, source, 1,
                "A test script key exists in package.json; its command and outcome are not verified or executed.",
                "Review scripts as code before manually running trusted tests.", FindingStatus.OBSERVED, "scripts.test key exists")
        }

        private fun inspectWrapper(source: Source) {
            val properties = source.text.lineSequence().map { it.trim() }.filter { it.isNotBlank() && !it.startsWith('#') && !it.startsWith('!') }.toList()
            val checksum = properties.firstOrNull { it.substringBefore('=').trim() == "distributionSha256Sum" }?.substringAfter('=')?.trim()
            report("GRADLE_WRAPPER_CHECKSUM", if (checksum?.matches(Regex("[A-Fa-f0-9]{64}")) == true) Severity.INFO else Severity.WARNING, source, 1,
                if (checksum?.matches(Regex("[A-Fa-f0-9]{64}")) == true) "A SHA-256 distribution checksum declaration was observed; its correctness is not verified."
                else "No valid distributionSha256Sum declaration was observed in wrapper properties.",
                "Review the trusted Gradle version/distribution source and independently verified checksum.",
                if (checksum?.matches(Regex("[A-Fa-f0-9]{64}")) == true) FindingStatus.OBSERVED else FindingStatus.REVIEW_NEEDED,
                "Gradle wrapper distribution checksum declaration")
            val insecureLine = source.text.lineSequence().indexOfFirst { it.trim().startsWith("distributionUrl=") && it.replace("\\:", ":").contains("http://") }
            if (insecureLine >= 0) report("GRADLE_WRAPPER_HTTP", Severity.WARNING, source, insecureLine + 1,
                "The wrapper distribution URL explicitly uses HTTP.", "Review a trusted HTTPS distribution and checksum.", evidence = "HTTP distribution URL (destination withheld)")
        }
    }

    companion object {
        private val sensitivePermissions = setOf("android.permission.READ_CONTACTS", "android.permission.READ_SMS", "android.permission.RECEIVE_SMS",
            "android.permission.ACCESS_BACKGROUND_LOCATION", "android.permission.MANAGE_EXTERNAL_STORAGE", "android.permission.READ_CALL_LOG")
        private fun isCandidate(relative: String): Boolean = relative.substringAfterLast('/').let {
            it == "AndroidManifest.xml" || it == "package.json" || it == "gradle-wrapper.properties" ||
                it.endsWith(".gradle") || it.endsWith(".gradle.kts") || it.endsWith(".versions.toml") ||
                relative.matches(Regex(".*(?:^|/)res/xml(?:-[^/]+)?/[^/]+\\.xml"))
        }
        private fun isBuildConfiguration(name: String): Boolean = name in setOf("build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts", "package.json")
        private fun isTest(relative: String): Boolean = Regex("(?:^|/)(?:test|tests|androidTest|commonTest|jvmTest|desktopTest|__tests__)/").containsMatchIn(relative) &&
            relative.substringAfterLast('.').lowercase(Locale.ROOT) in setOf("kt", "java", "js", "jsx", "ts", "tsx", "py", "groovy") ||
            Regex("(?:Test|Tests|Spec)\\.(?:kt|java)$|\\.(?:test|spec)\\.[jt]sx?$").containsMatchIn(relative)
        private fun isLock(name: String): Boolean = name in setOf("gradle.lockfile", "package-lock.json", "npm-shrinkwrap.json", "pnpm-lock.yaml", "yarn.lock", "bun.lock", "bun.lockb", "Cargo.lock", "poetry.lock", "composer.lock", "Gemfile.lock") || name.endsWith(".lockfile")
        private fun dynamicVersion(value: String): Boolean = value.contains('+') || value.contains('*') || value.contains("SNAPSHOT", true) ||
            value.startsWith("latest", true) || value.startsWith('[') || value.startsWith('(') || value.contains("||")

        /** Preserve strings and line positions while removing comments, so URLs and commented examples stay distinct. */
        private fun stripComments(text: String): String {
            val out = StringBuilder(text.length)
            var index = 0
            var quote: Char? = null
            var escaped = false
            var block = false
            var line = false
            while (index < text.length) {
                val ch = text[index]
                val next = text.getOrNull(index + 1)
                when {
                    line -> { out.append(if (ch == '\n') '\n' else ' '); if (ch == '\n') line = false }
                    block -> {
                        if (ch == '*' && next == '/') { out.append("  "); index++; block = false }
                        else out.append(if (ch == '\n') '\n' else ' ')
                    }
                    quote != null -> { out.append(ch); if (escaped) escaped = false else if (ch == '\\') escaped = true else if (ch == quote) quote = null }
                    ch == '/' && next == '/' -> { out.append("  "); index++; line = true }
                    ch == '/' && next == '*' -> { out.append("  "); index++; block = true }
                    ch == '\'' || ch == '"' -> { quote = ch; out.append(ch) }
                    else -> out.append(ch)
                }
                index++
            }
            return out.toString()
        }
        private fun blockEnd(text: String, openingBrace: Int): Int {
            var depth = 1
            var quote: Char? = null
            var escaped = false
            var index = openingBrace + 1
            while (index < text.length) {
                val ch = text[index]
                if (quote != null) { if (escaped) escaped = false else if (ch == '\\') escaped = true else if (ch == quote) quote = null }
                else if (ch == '\'' || ch == '"') quote = ch
                else if (ch == '{') depth++
                else if (ch == '}' && --depth == 0) return index
                index++
            }
            return text.length
        }
    }
}
