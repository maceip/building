package dev.ide.workbench.quality

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale
import java.util.zip.ZipInputStream

internal class ArchiveImporter(private val config: ArchiveImportConfig, private val clock: Clock) {
    private data class Document(val source: String, val root: JsonElement)
    private val repositoryPattern = Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,99}/[A-Za-z0-9_.-]{1,100}")
    private val githubUrl = Regex("https://(?:github\\.com/([^/]+)/([^/]+)/(issues|pull)/([0-9]+)|api\\.github\\.com/repos/([^/]+)/([^/]+)/(issues|pulls)/([0-9]+))(?:[?#].*)?")
    private val fullReference = Regex("https://github\\.com/([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)/(issues|pull)/([0-9]+)")
    private val shorthandReference = Regex("(?<![A-Za-z0-9_/])(?:([A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+))?#([1-9][0-9]{0,17})(?![A-Za-z0-9])")

    fun import(path: Path): QualityReport = try {
        doImport(path)
    } catch (error: QualityException) {
        throw error
    } catch (_: IOException) {
        throw QualityException(QualityErrorCode.IO_ERROR, "The archive could not be read. Check file access and ZIP integrity.")
    } catch (_: SecurityException) {
        throw QualityException(QualityErrorCode.IO_ERROR, "The archive is not readable with the current file permissions.")
    }

    private fun doImport(path: Path): QualityReport {
        val bytes = readRegularFile(path, config.maxCompressedBytes)
        val documents = readDocuments(bytes, path.fileName.toString())
        val warnings = linkedSetOf<String>()
        val rawRecords = ArrayList<ImportedRecord>()
        val metadata = ArrayList<ArchiveMetadata>()
        var seen = 0
        var invalid = 0
        var totalText = 0
        val configuredRepository = config.defaultRepository?.let { canonicalRepository(it) }
        if (config.defaultRepository != null && configuredRepository == null) {
            throw QualityException(QualityErrorCode.INVALID_INPUT, "defaultRepository must be an owner/repository name.")
        }
        fun add(entries: JsonArray, repository: String?, kind: RecordKind?) {
            for (entry in entries) {
                if (++seen > config.maxRecords) limitExceeded("Archive record count")
                val record = (entry as? JsonObject)?.let { parseRecord(it, repository, kind) }
                if (record == null) { invalid++; continue }
                totalText += record.title.length + record.body.length
                if (totalText > config.maxTotalTextChars) limitExceeded("Archive text")
                rawRecords += record
            }
        }
        for (document in documents) {
            val root = document.root
            when (root) {
                is JsonArray -> {
                    metadata += ArchiveMetadata(document.source, null, null, null)
                    add(root, null, null)
                }
                is JsonObject -> {
                    metadata += readMetadata(root, document.source, warnings)
                    when {
                        root["repos"] is JsonObject -> {
                            val repos = root["repos"] as JsonObject
                            if (repos.size > config.maxRecords) limitExceeded("Repository count")
                            for ((name, value) in repos.toSortedMap()) {
                                val repo = canonicalRepository(name)
                                    ?: throw QualityException(QualityErrorCode.INVALID_INPUT, "An archive repository key is not an owner/repository name.")
                                val payload = value as? JsonObject
                                    ?: throw QualityException(QualityErrorCode.INVALID_INPUT, "Each repository must contain issue and/or PR arrays.")
                                var recognized = false
                                for ((key, kind) in listOf("issues" to RecordKind.ISSUE, "prs" to RecordKind.PULL_REQUEST, "pull_requests" to RecordKind.PULL_REQUEST)) {
                                    val values = payload[key] ?: continue
                                    val list = values as? JsonArray ?: throw QualityException(QualityErrorCode.INVALID_INPUT, "Repository issue and PR values must be arrays.")
                                    add(list, repo, kind)
                                    recognized = true
                                }
                                if (!recognized) warnings += "A repository had no supported issue or PR arrays; pagination totals are not imported as records."
                            }
                        }
                        root["items"] is JsonArray -> add(root["items"] as JsonArray, null, null)
                        root["issues"] is JsonArray || root["prs"] is JsonArray -> {
                            val repo = root.string("repo")?.let { canonicalRepository(it) }
                            listOf("issues" to RecordKind.ISSUE, "prs" to RecordKind.PULL_REQUEST).forEach { (key, kind) ->
                                root[key]?.let { values ->
                                    add(values as? JsonArray ?: throw QualityException(QualityErrorCode.INVALID_INPUT, "Issue and PR values must be arrays."), repo, kind)
                                }
                            }
                        }
                        else -> throw QualityException(QualityErrorCode.UNSUPPORTED_FORMAT, "Expected {repos:{owner/repo:{issues:[],prs:[]}}}, a GitHub REST list, or an {items:[]} response.")
                    }
                }
                else -> throw QualityException(QualityErrorCode.UNSUPPORTED_FORMAT, "The archive JSON root must be an object or a list.")
            }
        }
        if (invalid > 0) warnings += "$invalid records were skipped because canonical identity or field types were missing/inconsistent."
        val unique = rawRecords.groupBy { it.id }.toSortedMap().map { (_, copies) ->
            // Prefer the richer PR endpoint over an issue-list copy, then text completeness; tie-break without input ordering.
            val ordered = copies.sortedWith(compareByDescending<ImportedRecord> { it.kind == RecordKind.PULL_REQUEST }
                .thenByDescending { it.mergeStatus != MergeStatus.UNKNOWN }
                .thenByDescending { it.title.length + it.body.length }
                .thenBy { stableHash("${it.title}\n${it.body}\n${it.state}\n${it.createdAt}") })
            val selected = ordered.first()
            if (copies.map { it.state }.filter { it != RecordState.UNKNOWN }.distinct().size > 1 ||
                copies.map { it.mergeStatus }.filter { it == MergeStatus.MERGED || it == MergeStatus.NOT_MERGED }.distinct().size > 1) {
                warnings += "Duplicate records contain conflicting state metadata; the preferred richer copy is retained, not an inferred outcome."
            }
            selected.copy(references = copies.flatMap { it.references }.distinctBy { it.id to it.kind }.sortedBy { it.id })
        }
        val duplicates = rawRecords.size - unique.size
        if (duplicates > 0) warnings += "$duplicates duplicate IDs were collapsed; issue-list PR entries count as PRs, not issues."
        val securityRepositories = config.deviceSecurityRepositories.map { name -> canonicalRepository(name)
            ?: throw QualityException(QualityErrorCode.INVALID_INPUT, "deviceSecurityRepositories entries must be owner/repository names.") }.toSet()
        val all = unique.map { IssueClassifier.classify(it, it.repository in securityRepositories) }
        val distinctWindows = metadata.mapNotNull { it.declaredWindow }.distinct()
        val window = config.recentWindow ?: distinctWindows.singleOrNull()
        if (config.recentWindow == null && (distinctWindows.size > 1 || (distinctWindows.isNotEmpty() && metadata.any { it.declaredWindow == null }))) {
            warnings += "Archive members do not share one declared window; set an explicit recentWindow to compare a consistent slice."
        }
        val effectiveWindow = if (config.recentWindow == null && metadata.any { it.declaredWindow == null } && metadata.size > 1) null else window
        if (config.recentOnly && effectiveWindow == null) {
            throw QualityException(QualityErrorCode.INVALID_INPUT, "recentOnly requires an explicit or unambiguous declared archive window.")
        }
        val recent = effectiveWindow?.let { range -> all.filter { it.createdAt?.let(range::contains) == true } }
        val sensitive = all.filter { it.deviceSecuritySpecific }
        val ordinary = all.filterNot { it.deviceSecuritySpecific }
        val included = all.filter { (!config.recentOnly || it.createdAt?.let { date -> effectiveWindow!!.contains(date) } == true) &&
            (config.includeDeviceSecuritySpecific || !it.deviceSecuritySpecific) }
        warnings += "Specialized-device filtering is heuristic and may miss terse or domain-specific cases; review repository slices and configure deviceSecurityRepositories for user-reviewed scope."
        if (securityRepositories.isNotEmpty()) warnings += "${securityRepositories.size} explicitly configured repositories are treated as specialized device-security scope; no repository identities are hardcoded."
        if (sensitive.isNotEmpty()) warnings += "${sensitive.size} specialized device-security records are ${if (config.includeDeviceSecuritySpecific) "included with operational text withheld" else "excluded from the visible groups"}; full and sensitivity counts remain available."
        val outside = effectiveWindow?.let { range -> all.count { it.createdAt != null && !range.contains(it.createdAt) } }
        if (outside != null && outside > 0) warnings += "$outside records have created_at outside the stated recent window; full-history and recent counts differ."
        if (all.any { it.createdAt == null }) warnings += "Records without valid created_at cannot be assigned to a recent window."
        if (all.isEmpty()) warnings += "No valid records were imported; an empty report is not a quality assessment."
        if (all.any { it.textTruncated }) warnings += "Long titles/bodies were truncated before classification; labels, references, and snippets may be incomplete."
        val stats = ArchiveStatistics(
            inputRecordCount = seen, invalidRecordCount = invalid, duplicateRecordCount = duplicates,
            truncatedRecordCount = all.count { it.textTruncated }, full = countRecords(all), recent = recent?.let(::countRecords),
            included = countRecords(included), recentWindow = effectiveWindow,
            oldestCreatedAt = all.mapNotNull { it.createdAt }.minOrNull(), newestCreatedAt = all.mapNotNull { it.createdAt }.maxOrNull(),
            missingCreatedAtCount = all.count { it.createdAt == null }, outsideWindowCount = outside,
            repositories = all.groupBy { it.repository }.toSortedMap().map { (repo, members) ->
                RepositoryStatistics(repo, countRecords(members), recent?.filter { it.repository == repo }?.let(::countRecords),
                    countRecords(included.filter { it.repository == repo }), countRecords(members.filter { it.deviceSecuritySpecific }))
            },
            deviceSecuritySpecific = countRecords(sensitive), withoutDeviceSecuritySpecific = countRecords(ordinary),
            fullByTheme = themeCounts(all), includedByTheme = themeCounts(included), withoutDeviceSecurityByTheme = themeCounts(ordinary),
            metadata = metadata,
            issuesByState = all.filter { it.kind == RecordKind.ISSUE }.groupingBy { it.state }.eachCount(),
            pullRequestsByState = all.filter { it.kind == RecordKind.PULL_REQUEST }.groupingBy { it.state }.eachCount(),
            pullRequestsByMergeStatus = all.filter { it.kind == RecordKind.PULL_REQUEST }.groupingBy { it.mergeStatus }.eachCount(),
        )
        val stackGroups = LogAnalyzer.archiveGroups(included)
        return QualityReport(ReportKind.ARCHIVE, "Issue and PR evidence triage", clock.instant(),
            records = included, groups = buildThemeGroups(included) + stackGroups, statistics = stats,
            checklists = VerificationChecklists.forThemes(included.filterNot { it.deviceSecuritySpecific || it.intent in setOf(RecordIntent.MAINTENANCE, RecordIntent.POSITIVE_COMPATIBILITY_REPORT) }.map { it.theme }),
            warnings = warnings.toList(), caveats = archiveCaveats)
    }

    private fun readDocuments(bytes: ByteArray, source: String): List<Document> {
        val zip = bytes.size >= 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
        if (!zip) {
            if (source.lowercase(Locale.ROOT).endsWith(".zip")) throw QualityException(QualityErrorCode.INVALID_INPUT, "The selected ZIP does not have a ZIP signature.")
            if (bytes.size > config.maxExpandedBytes) limitExceeded("Expanded archive bytes")
            return listOf(Document(safeSnippet(source, 160), parseBoundedJson(decodeUtf8(bytes), config.maxJsonDepth, config.maxJsonTokens)))
        }
        val documents = ArrayList<Document>()
        var total = 0
        var entries = 0
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { input ->
                while (true) {
                    val entry = input.nextEntry ?: break
                    if (++entries > config.maxEntries) limitExceeded("ZIP entry count")
                    val name = entry.name
                    if (name.length > 512 || name.startsWith('/') || '\\' in name || ':' in name || '\u0000' in name ||
                        name.split('/').any { it == ".." || it == "." }) {
                        throw QualityException(QualityErrorCode.UNSAFE_PATH, "ZIP member names must be safe relative paths; nothing was extracted.")
                    }
                    if (entry.size > (config.maxExpandedBytes - total).toLong()) limitExceeded("Expanded archive bytes")
                    if (!entry.isDirectory && !name.lowercase(Locale.ROOT).endsWith(".json")) {
                        throw QualityException(QualityErrorCode.UNSUPPORTED_FORMAT, "ZIP archives may contain JSON files and directories only; no member is extracted or executed.")
                    }
                    val expanded = readBounded(input, config.maxExpandedBytes - total, "Expanded archive bytes")
                    total += expanded.size
                    if (total.toLong() > bytes.size.toLong() * config.maxCompressionRatio) limitExceeded("ZIP compression ratio")
                    if (entry.compressedSize > 0 && expanded.size.toLong() > entry.compressedSize * config.maxCompressionRatio) limitExceeded("ZIP member compression ratio")
                    if (entry.isDirectory && expanded.isNotEmpty()) throw QualityException(QualityErrorCode.INVALID_INPUT, "A ZIP directory unexpectedly contains data.")
                    if (!entry.isDirectory) documents += Document(safeSnippet(name, 160), parseBoundedJson(decodeUtf8(expanded), config.maxJsonDepth, config.maxJsonTokens))
                    input.closeEntry() // verifies CRC through the bounded read; does not extract to disk.
                }
            }
        } catch (error: QualityException) { throw error }
        catch (_: IOException) { throw QualityException(QualityErrorCode.INVALID_INPUT, "The ZIP is corrupt, truncated, encrypted, or uses an unsupported compression method.") }
        if (documents.isEmpty()) throw QualityException(QualityErrorCode.INVALID_INPUT, "The ZIP contains no JSON documents.")
        return documents
    }

    private fun canonicalRepository(input: String): String? = input.trim().let { value ->
        if (repositoryPattern.matches(value) && value.split('/').none { it == "." || it == ".." }) value.lowercase(Locale.ROOT) else null
    }

    private fun parseRecord(item: JsonObject, repositoryHint: String?, kindHint: RecordKind?): ImportedRecord? {
        val number = (item["number"] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 } ?: return null
        val url = item.string("html_url") ?: item.string("url")
        val urlMatch = url?.let { githubUrl.matchEntire(it) }
        val urlRepo = urlMatch?.let { if (it.groupValues[1].isNotBlank()) "${it.groupValues[1]}/${it.groupValues[2]}" else "${it.groupValues[5]}/${it.groupValues[6]}" }?.let(::canonicalRepository)
        val urlNumber = urlMatch?.let { (it.groupValues[4].ifBlank { it.groupValues[8] }).toLongOrNull() }
        if (urlNumber != null && urlNumber != number) return null
        val ownRepo = item.string("repo")?.let(::canonicalRepository)
            ?: (item["repository"] as? JsonObject)?.string("full_name")?.let(::canonicalRepository)
            ?: item.string("repository_url")?.removePrefix("https://api.github.com/repos/")?.let(::canonicalRepository)
        val repo = repositoryHint ?: ownRepo ?: urlRepo ?: config.defaultRepository?.let(::canonicalRepository) ?: return null
        if ((urlRepo != null && urlRepo != repo) || (ownRepo != null && ownRepo != repo)) return null
        val titleElement = item["title"]
        val bodyElement = item["body"]
        if (titleElement != null && titleElement != JsonNull && (titleElement !is JsonPrimitive || !titleElement.isString)) return null
        if (bodyElement != null && bodyElement != JsonNull && (bodyElement !is JsonPrimitive || !bodyElement.isString)) return null
        val title = item.string("title").orEmpty()
        val body = item.string("body").orEmpty()
        val isPr = kindHint == RecordKind.PULL_REQUEST || item["pull_request"] is JsonObject ||
            item.string("kind")?.lowercase(Locale.ROOT) in setOf("pr", "pull_request", "pull request") ||
            urlMatch?.let { it.groupValues[3] == "pull" || it.groupValues[7] == "pulls" } == true
        val kind = if (isPr) RecordKind.PULL_REQUEST else RecordKind.ISSUE
        val state = when (item.string("state")?.lowercase(Locale.ROOT)) { "open" -> RecordState.OPEN; "closed" -> RecordState.CLOSED; else -> RecordState.UNKNOWN }
        val prMetadata = item["pull_request"] as? JsonObject
        val mergedAt = (item.string("merged_at") ?: prMetadata?.string("merged_at"))?.let(::parseInstant)
        val merged = (item["merged"] as? JsonPrimitive)?.booleanOrNull ?: (prMetadata?.get("merged") as? JsonPrimitive)?.booleanOrNull
        val merge = when { !isPr -> MergeStatus.NOT_APPLICABLE; mergedAt != null || merged == true -> MergeStatus.MERGED; merged == false -> MergeStatus.NOT_MERGED; else -> MergeStatus.UNKNOWN }
        val boundedTitle = title.take(config.maxTitleChars)
        val boundedBody = body.take(config.maxBodyChars)
        val id = "$repo#$number"
        val referenceText = "$boundedTitle\n$boundedBody"
        val refs = ArrayList<ExplicitReference>()
        for (match in fullReference.findAll(referenceText).take(100)) {
            val refRepo = canonicalRepository("${match.groupValues[1]}/${match.groupValues[2]}") ?: continue
            val refNumber = match.groupValues[4].toLongOrNull()?.takeIf { it > 0 } ?: continue
            val refKind = if (match.groupValues[3] == "pull") RecordKind.PULL_REQUEST else RecordKind.ISSUE
            refs += ExplicitReference("$refRepo#$refNumber", "https://github.com/$refRepo/${if (refKind == RecordKind.PULL_REQUEST) "pull" else "issues"}/$refNumber", refKind)
        }
        // Plain #123 is a reference, not a claimed fix. Markdown/code-fence contents are not interpreted as linking directives.
        val referenceProse = referenceText.replace(Regex("(?s)```.*?(?:```|$)|~~~.*?(?:~~~|$)|`[^`]*`"), "")
        for (match in shorthandReference.findAll(referenceProse).take(100)) {
            val refRepo = match.groupValues[1].ifBlank { repo }.let(::canonicalRepository) ?: continue
            val refNumber = match.groupValues[2].toLongOrNull() ?: continue
            refs += ExplicitReference("$refRepo#$refNumber", "https://github.com/$refRepo/issues/$refNumber")
        }
        return ImportedRecord(id, repo, number, kind, state, merge, boundedTitle, boundedBody,
            item.string("created_at")?.let(::parseInstant), "https://github.com/$repo/${if (isPr) "pull" else "issues"}/$number",
            refs.filter { it.id != id }.distinctBy { it.id to it.kind }.sortedBy { it.id }, title.length > boundedTitle.length || body.length > boundedBody.length)
    }

    private fun readMetadata(root: JsonObject, source: String, warnings: MutableSet<String>): ArchiveMetadata {
        val generated = root.string("generated")?.let(::parseInstant)
        val period = root["period"]
        val periodText = when (period) { is JsonPrimitive -> period.contentOrNull; is JsonObject -> period.toString(); else -> null }?.let { safeSnippet(it, 240) }
        val startText = when (period) {
            is JsonObject -> period.string("start") ?: period.string("from") ?: period.string("since")
            is JsonPrimitive -> Regex("(?i)\\bsince\\s+(\\d{4}-\\d{2}-\\d{2}(?:T[^\\s,]+)?)").find(period.content)?.groupValues?.get(1)
            else -> null
        }
        val endText = (period as? JsonObject)?.let { it.string("end") ?: it.string("to") ?: it.string("until") }
        val start = startText?.let(::parseInstant)
        // An explicitly invalid upper bound is not silently replaced by generated/import time.
        // A date-only end means the entire UTC day, whereas a timestamp is an exact inclusive bound.
        val end = if (endText != null) parseWindowEnd(endText) else generated ?: clock.instant()
        val range = if (start != null && end != null && !end.isBefore(start)) ArchiveWindow(start, end) else null
        if (period != null && period != JsonNull && range == null) warnings += "A declared period could not be interpreted as a date window; its text is preserved without assuming recency."
        if (start != null && generated == null && endText == null) warnings += "No generated/end timestamp was supplied; the import time is the recent-window upper bound."
        return ArchiveMetadata(source, generated, periodText, range)
    }
}

internal fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
private fun parseWindowEnd(text: String): Instant? = if (Regex("\\d{4}-\\d{2}-\\d{2}").matches(text)) {
    try { LocalDate.parse(text).plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC).minusNanos(1) } catch (_: Exception) { null }
} else parseInstant(text)

internal fun parseInstant(text: String): Instant? = try { Instant.parse(text) } catch (_: Exception) {
    try { LocalDate.parse(text).atStartOfDay().toInstant(ZoneOffset.UTC) } catch (_: Exception) { null }
}
