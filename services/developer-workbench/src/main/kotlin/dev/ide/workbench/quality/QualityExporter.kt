package dev.ide.workbench.quality

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption

internal class QualityExporter(private val maxBytes: Int) {
    fun export(report: QualityReport, destination: Path, format: ExportFormat): ExportResult {
        val path = destination.toAbsolutePath().normalize()
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) throw QualityException(QualityErrorCode.DESTINATION_EXISTS, "Export destination already exists; choose a new file to avoid overwriting data.")
        val parent = path.parent ?: throw QualityException(QualityErrorCode.UNSAFE_PATH, "The export needs an existing parent directory.")
        if (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) throw QualityException(QualityErrorCode.UNSAFE_PATH, "Choose an existing real export directory, not a symbolic link.")
        // Anchor to the resolved, explicitly selected directory; never use an archive member as a destination.
        val actual = try { parent.toRealPath().resolve(path.fileName) } catch (_: IOException) {
            throw QualityException(QualityErrorCode.IO_ERROR, "The export directory could not be accessed.")
        }
        val rendered = when (format) {
            ExportFormat.JSON -> qualityJson.encodeToString(JsonElement.serializer(), reportJson(report))
            ExportFormat.MARKDOWN -> markdown(report)
        }
        if (rendered.length > maxBytes) limitExceeded("Export size")
        val bytes = rendered.toByteArray(Charsets.UTF_8)
        if (bytes.size > maxBytes) limitExceeded("Export size")
        var created = false
        try {
            Files.newOutputStream(actual, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use {
                created = true
                it.write(bytes)
            }
        } catch (_: FileAlreadyExistsException) {
            throw QualityException(QualityErrorCode.DESTINATION_EXISTS, "Export destination already exists; nothing was overwritten.")
        } catch (_: IOException) {
            if (created) try { Files.deleteIfExists(actual) } catch (_: IOException) { /* Do not mask the original safe error. */ }
            throw QualityException(QualityErrorCode.IO_ERROR, "The report could not be exported; check directory permissions and available storage.")
        } catch (_: SecurityException) {
            throw QualityException(QualityErrorCode.IO_ERROR, "The export destination is not writable with current permissions.")
        }
        return ExportResult(actual, format, bytes.size.toLong())
    }

    private fun markdown(report: QualityReport): String = buildString {
        appendLine("# ${md(report.title)}")
        appendLine()
        appendLine("Kind: ${report.kind.name} · Generated: ${report.generatedAt} · Schema: ${report.schemaVersion}")
        appendLine()
        appendLine("**Review needed. This report is evidence triage/preflight, not an automated test result or an aggregate quality/security pass.**")
        report.statistics?.let { stats ->
            appendLine(); appendLine("## Archive counts")
            appendLine("| Scope | Issues | PRs | Total |")
            appendLine("| --- | ---: | ---: | ---: |")
            appendLine(countRow("Full unique archive", stats.full))
            stats.recent?.let { appendLine(countRow("Recent window (created_at)", it)) }
            appendLine(countRow("Included after filters", stats.included))
            appendLine(countRow("Specialized device-security", stats.deviceSecuritySpecific))
            appendLine(countRow("Without specialized device-security", stats.withoutDeviceSecuritySpecific))
            appendLine()
            appendLine("Input records: ${stats.inputRecordCount}; invalid skipped: ${stats.invalidRecordCount}; duplicate IDs collapsed: ${stats.duplicateRecordCount}; truncated: ${stats.truncatedRecordCount}.")
            appendLine("Observed created_at range: ${stats.oldestCreatedAt ?: "unknown"} through ${stats.newestCreatedAt ?: "unknown"}; missing dates: ${stats.missingCreatedAtCount}.")
            appendLine("Recent window: ${stats.recentWindow?.let { "${it.start} through ${it.endInclusive} (inclusive)" } ?: "unavailable"}; outside window: ${stats.outsideWindowCount ?: "unknown"}.")
            appendLine(); appendLine("### Repository coverage")
            appendLine("| Repository | Full issues | Full PRs | Recent issues | Recent PRs | Included | Specialized |")
            appendLine("| --- | ---: | ---: | ---: | ---: | ---: | ---: |")
            stats.repositories.forEach { appendLine("| ${md(it.repository)} | ${it.full.issues} | ${it.full.pullRequests} | ${it.recent?.issues ?: "unknown"} | ${it.recent?.pullRequests ?: "unknown"} | ${it.included.total} | ${it.deviceSecuritySpecific.total} |") }
            appendLine(); appendLine("### Primary themes (counts, not prevalence)")
            appendLine("| Theme | Full issues | Full PRs | Included issues | Included PRs | Without specialized |")
            appendLine("| --- | ---: | ---: | ---: | ---: | ---: |")
            stats.fullByTheme.forEach { (theme, count) ->
                val included = stats.includedByTheme[theme] ?: RecordCounts()
                appendLine("| ${md(theme.label)} | ${count.issues} | ${count.pullRequests} | ${included.issues} | ${included.pullRequests} | ${stats.withoutDeviceSecurityByTheme[theme]?.total ?: 0} |")
            }
            appendLine(); appendLine("Issue states: ${md(stats.issuesByState.toString())}. PR states: ${md(stats.pullRequestsByState.toString())}. PR merge evidence: ${md(stats.pullRequestsByMergeStatus.toString())}.")
            stats.metadata.forEach { appendLine("- Source member: ${md(it.source)}; generated: ${it.generated ?: "unknown"}; declared period: ${md(it.declaredPeriod ?: "unknown")}") }
        }
        report.logSummary?.let {
            appendLine(); appendLine("## Log coverage")
            appendLine("Lines: ${it.lineCount}; observed exception events: ${it.events.size}; long lines truncated: ${it.truncatedLines}; best-effort redaction applied: ${it.redacted}.")
        }
        report.scanSummary?.let {
            appendLine(); appendLine("## Scan coverage")
            appendLine("Root (best-effort redacted for export): ${md(it.root)}")
            appendLine("Visited entries: ${it.visitedEntries}; inspected files: ${it.inspectedFiles}; inspected bytes: ${it.inspectedBytes}; skipped symbolic links: ${it.skippedSymlinks}; skipped files: ${it.skippedFiles}; partial: ${it.partial}.")
            appendLine("Observed test paths: ${it.testFilesObserved}; lockfiles: ${it.lockfilesObserved}; build configurations: ${it.buildConfigurationsObserved}.")
            appendLine("Excluded directory names: ${md(it.excludedDirectories.joinToString(", "))}.")
        }
        appendLine(); appendLine("## Evidence groups")
        if (report.groups.isEmpty()) appendLine("No supported groups were observed. This is not evidence that no defects exist.")
        for (group in report.groups) {
            appendLine(); appendLine("### ${md(group.title)}")
            appendLine("${group.kind} · ${group.theme.key} · confidence ${group.confidence} · ${group.issueCount} issues / ${group.pullRequestCount} PRs / ${group.occurrenceCount} occurrences")
            appendLine(md(group.explanation))
            group.fingerprint?.let { appendLine("Fingerprint: ${md(it)}") }
            group.evidence.forEach { appendLine("- ${citation(it)} — ${md(it.snippet)}${if (it.redacted) " (redacted)" else ""}") }
            if (group.linkedPullRequestIds.isNotEmpty()) appendLine("Explicitly referenced PRs (not proof of a fix): ${group.linkedPullRequestIds.joinToString(", ") { md(it) }}")
        }
        if (report.records.isNotEmpty()) {
            appendLine(); appendLine("## Per-record provenance")
            for (record in report.records) {
                appendLine(); appendLine("### ${citation(record.evidence)} — ${md(record.title)}")
                appendLine("${record.kind} · state ${record.state} · merge ${record.mergeStatus} · ${record.intent} · ${record.theme.key} · ${record.confidence}")
                appendLine("Cause evidence: ${record.causeStatus} (not independently confirmed). Transferability: ${record.transferability}. Created: ${record.createdAt ?: "unknown"}.")
                appendLine("Evidence: ${md(record.evidence.snippet)}")
                appendLine("Reason: ${md(record.classificationReason)}")
                if (record.references.isNotEmpty()) appendLine("Explicit references: ${record.references.joinToString(", ") { reference -> link(reference.id, reference.url) }}")
                if (record.textTruncated) appendLine("Text truncated; review the original citation.")
            }
        }
        if (report.findings.isNotEmpty()) {
            appendLine(); appendLine("## Source observations and review warnings")
            for (finding in report.findings) {
                appendLine(); appendLine("### ${md(finding.ruleId)} — ${finding.severity} / ${finding.status}")
                appendLine("Location: ${md(finding.file)}:${finding.line}")
                appendLine("Reason: ${md(finding.reason)}")
                appendLine("Evidence: ${md(finding.evidence)}")
                appendLine("Manual review: ${md(finding.remediation)}")
            }
        }
        if (report.checklists.isNotEmpty()) {
            appendLine(); appendLine("## Suggested verification (not executed)")
            report.checklists.forEach { checklist ->
                appendLine(); appendLine("### ${md(checklist.title)}")
                appendLine(md(checklist.status))
                checklist.steps.forEach { appendLine("- [ ] ${md(it)}") }
            }
        }
        appendLine(); appendLine("## Warnings and limitations")
        (report.warnings + report.caveats).forEach { appendLine("- ${md(it)}") }
    }

    private fun md(text: String): String = QualityRedactor.redact(text).text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace(Regex("([\\\\`*_{\\[\\]}()#!|])")) { "\\${it.value}" }.replace(Regex("[\\r\\n]+"), " ")
    private fun link(label: String, url: String): String = if (Regex("https://github\\.com/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+/(?:issues|pull)/[0-9]+").matches(url)) "[${md(label)}]($url)" else md(label)
    private fun citation(evidence: EvidenceCitation): String = evidence.url?.let { link(evidence.recordId, it) } ?: md(evidence.recordId)
    private fun countRow(label: String, counts: RecordCounts): String = "| $label | ${counts.issues} | ${counts.pullRequests} | ${counts.total} |"

    private fun reportJson(report: QualityReport): JsonObject = obj(
        "schema_version" to j(report.schemaVersion), "kind" to j(report.kind), "title" to j(report.title), "generated_at" to j(report.generatedAt.toString()),
        "records" to arr(report.records.map { record -> obj(
            "id" to j(record.id), "repository" to j(record.repository), "number" to j(record.number), "kind" to j(record.kind),
            "state" to j(record.state), "merge_status" to j(record.mergeStatus), "title" to j(record.title), "body" to j(record.body),
            "created_at" to j(record.createdAt?.toString()), "url" to j(record.url), "intent" to j(record.intent), "theme" to j(record.theme.key),
            "secondary_themes" to strings(record.secondaryThemes.map { it.key }), "problem_key" to j(record.problemKey), "confidence" to j(record.confidence),
            "cause_status" to j(record.causeStatus), "transferability" to j(record.transferability), "device_security_specific" to j(record.deviceSecuritySpecific),
            "evidence" to evidenceJson(record.evidence), "classification_reason" to j(record.classificationReason),
            "references" to arr(record.references.map { obj("id" to j(it.id), "url" to j(it.url), "kind" to j(it.kind)) }), "text_truncated" to j(record.textTruncated)) }),
        "groups" to arr(report.groups.map { group -> obj(
            "id" to j(group.id), "title" to j(group.title), "theme" to j(group.theme.key), "confidence" to j(group.confidence), "kind" to j(group.kind),
            "issue_count" to j(group.issueCount), "pull_request_count" to j(group.pullRequestCount), "occurrence_count" to j(group.occurrenceCount),
            "record_ids" to strings(group.recordIds), "evidence" to arr(group.evidence.map(::evidenceJson)), "linked_pull_request_ids" to strings(group.linkedPullRequestIds),
            "fingerprint" to j(group.fingerprint), "explanation" to j(group.explanation)) }),
        "statistics" to (report.statistics?.let(::statisticsJson) ?: JsonNull),
        "findings" to arr(report.findings.map { obj("rule_id" to j(it.ruleId), "severity" to j(it.severity), "status" to j(it.status),
            "file" to j(it.file), "line" to j(it.line), "reason" to j(it.reason), "evidence" to j(it.evidence), "remediation" to j(it.remediation)) }),
        "log_summary" to (report.logSummary?.let { summary -> obj("line_count" to j(summary.lineCount), "redacted" to j(summary.redacted), "truncated_lines" to j(summary.truncatedLines),
            "events" to arr(summary.events.map { obj("start_line" to j(it.startLine), "end_line" to j(it.endLine), "exception_class" to j(it.exceptionClass),
                "frames" to strings(it.frames), "fingerprint" to j(it.fingerprint), "excerpt" to j(it.excerpt), "redacted" to j(it.redacted)) })) } ?: JsonNull),
        "scan_summary" to (report.scanSummary?.let { obj("root" to j(it.root), "visited_entries" to j(it.visitedEntries), "inspected_files" to j(it.inspectedFiles),
            "inspected_bytes" to j(it.inspectedBytes), "skipped_symlinks" to j(it.skippedSymlinks), "skipped_files" to j(it.skippedFiles), "excluded_directories" to strings(it.excludedDirectories),
            "partial" to j(it.partial), "test_files_observed" to j(it.testFilesObserved), "lockfiles_observed" to j(it.lockfilesObserved), "build_configurations_observed" to j(it.buildConfigurationsObserved)) } ?: JsonNull),
        "checklists" to arr(report.checklists.map { obj("theme" to j(it.theme.key), "title" to j(it.title), "steps" to strings(it.steps), "status" to j(it.status)) }),
        "warnings" to strings(report.warnings), "caveats" to strings(report.caveats),
    )

    private fun statisticsJson(stats: ArchiveStatistics): JsonObject = obj(
        "input_record_count" to j(stats.inputRecordCount), "invalid_record_count" to j(stats.invalidRecordCount), "duplicate_record_count" to j(stats.duplicateRecordCount),
        "truncated_record_count" to j(stats.truncatedRecordCount), "full" to countsJson(stats.full), "recent" to (stats.recent?.let(::countsJson) ?: JsonNull), "included" to countsJson(stats.included),
        "recent_window" to windowJson(stats.recentWindow), "oldest_created_at" to j(stats.oldestCreatedAt?.toString()), "newest_created_at" to j(stats.newestCreatedAt?.toString()),
        "missing_created_at_count" to j(stats.missingCreatedAtCount), "outside_window_count" to (stats.outsideWindowCount?.let(::j) ?: JsonNull),
        "repositories" to arr(stats.repositories.map { obj("repository" to j(it.repository), "full" to countsJson(it.full), "recent" to (it.recent?.let(::countsJson) ?: JsonNull),
            "included" to countsJson(it.included), "device_security_specific" to countsJson(it.deviceSecuritySpecific)) }),
        "device_security_specific" to countsJson(stats.deviceSecuritySpecific), "without_device_security_specific" to countsJson(stats.withoutDeviceSecuritySpecific),
        "full_by_theme" to JsonObject(stats.fullByTheme.mapKeys { it.key.key }.mapValues { countsJson(it.value) }),
        "included_by_theme" to JsonObject(stats.includedByTheme.mapKeys { it.key.key }.mapValues { countsJson(it.value) }),
        "without_device_security_by_theme" to JsonObject(stats.withoutDeviceSecurityByTheme.mapKeys { it.key.key }.mapValues { countsJson(it.value) }),
        "metadata" to arr(stats.metadata.map { obj("source" to j(it.source), "generated" to j(it.generated?.toString()), "declared_period" to j(it.declaredPeriod), "declared_window" to windowJson(it.declaredWindow)) }),
        "issues_by_state" to enumCounts(stats.issuesByState), "pull_requests_by_state" to enumCounts(stats.pullRequestsByState), "pull_requests_by_merge_status" to enumCounts(stats.pullRequestsByMergeStatus),
    )
    private fun evidenceJson(evidence: EvidenceCitation) = obj("record_id" to j(evidence.recordId), "url" to j(evidence.url), "snippet" to j(evidence.snippet),
        "source" to j(evidence.source), "line" to (evidence.line?.let(::j) ?: JsonNull), "redacted" to j(evidence.redacted))
    private fun countsJson(counts: RecordCounts) = obj("issues" to j(counts.issues), "pull_requests" to j(counts.pullRequests), "total" to j(counts.total))
    private fun windowJson(window: ArchiveWindow?): JsonElement = window?.let { obj("start" to j(it.start.toString()), "end_inclusive" to j(it.endInclusive.toString()), "date_field" to j("created_at")) } ?: JsonNull
    private fun enumCounts(counts: Map<out Enum<*>, Int>) = JsonObject(counts.entries.sortedBy { it.key.name }.associate { it.key.name.lowercase(java.util.Locale.ROOT) to j(it.value) })
    private fun obj(vararg pairs: Pair<String, JsonElement>) = JsonObject(linkedMapOf(*pairs))
    private fun arr(values: List<JsonElement>) = JsonArray(values)
    private fun strings(values: List<String>) = arr(values.map { j(it) })
    private fun j(value: String?): JsonElement = value?.let { JsonPrimitive(QualityRedactor.redact(it).text) } ?: JsonNull
    private fun j(value: Number): JsonElement = JsonPrimitive(value)
    private fun j(value: Boolean): JsonElement = JsonPrimitive(value)
    private fun j(value: Enum<*>?): JsonElement = j(value?.name?.lowercase(java.util.Locale.ROOT))
}
