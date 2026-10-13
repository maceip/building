package dev.ide.android.workbench.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import dev.ide.workbench.quality.ArchiveStatistics
import dev.ide.workbench.quality.CauseStatus
import dev.ide.workbench.quality.EvidenceCitation
import dev.ide.workbench.quality.GroupKind
import dev.ide.workbench.quality.QualityFinding
import dev.ide.workbench.quality.QualityGroup
import dev.ide.workbench.quality.QualityRecord
import dev.ide.workbench.quality.QualityReport
import dev.ide.workbench.quality.RecordCounts
import dev.ide.workbench.quality.RecordKind
import dev.ide.workbench.quality.ReportKind
import dev.ide.workbench.quality.Severity
import androidx.compose.ui.unit.dp

internal typealias QualityOpenSource = (path: String, oneBasedLine: Int, root: String) -> Unit

@Composable
internal fun QualityReportSummary(report: QualityReport) {
    QualityCard(report.title) {
        QualityText("${qualityEnumLabel(report.kind.name)} · ${report.generatedAt} · local analysis")
        when (report.kind) {
            ReportKind.ARCHIVE -> {
                report.statistics?.let { stats ->
                    QualityMetric("Included in classification", qualityCounts(stats.included))
                    QualityMetric("Unique archive records", qualityCounts(stats.full))
                }
                QualityText(
                    "Categories and confidence are heuristic. Reporter theories are not confirmed causes. " +
                        "A closed issue or merged PR is not a verified fix; similar symptoms do not prove a relationship.",
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            ReportKind.LOGS -> report.logSummary?.let { summary ->
                QualityMetric("Inspected log lines", summary.lineCount.toString())
                QualityMetric("Detected event blocks / groups", "${summary.events.size} / ${report.groups.size}")
                QualityText(
                    "Stack similarity is a triage signal, not a confirmed cause, complete crash count or executed test.",
                    color = MaterialTheme.colorScheme.onSurface,
                )
                QualityText(
                    if (summary.redacted) "Known credential patterns were redacted. Review other personal data before sharing."
                    else "No known credential patterns detected. Redaction is best effort; review personal data before sharing.",
                )
                if (summary.truncatedLines > 0) QualityText("${summary.truncatedLines} lines truncated by analysis limits.")
            }
            ReportKind.PROJECT -> report.scanSummary?.let { summary ->
                QualityMetric("Heuristic findings", report.findings.size.toString())
                QualityMetric("Files inspected", summary.inspectedFiles.toString())
                QualityText(
                    if (summary.partial) "Partial scan — limits or skipped input reduce coverage. No tests were executed."
                    else "Static scan only — not a pass/fail result. No tests were executed.",
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (report.findings.isEmpty()) {
                    QualityText("No findings in inspected files does not prove the project is correct, secure or ready to publish.")
                }
            }
        }
    }
}

@Composable
internal fun QualityReportDetails(
    report: QualityReport,
    onOpenUrl: ((String) -> Unit)?,
    onOpenSource: QualityOpenSource?,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        report.statistics?.let { stats -> QualityArchiveStatistics(stats, report.generatedAt) }
        report.scanSummary?.let { summary ->
            QualityCard("Project findings") {
                QualityPagedList(report.findings, 6, report.generatedAt, "No heuristic findings in the inspected files. This is not a test pass.") { finding ->
                    key(finding.file, finding.ruleId, finding.line) {
                        QualityFindingRow(finding, summary.root, onOpenSource)
                    }
                }
                QualityFold("Scan coverage") {
                    QualityText(summary.root)
                    QualityMetric("Visited entries / inspected files", "${summary.visitedEntries} / ${summary.inspectedFiles}")
                    QualityMetric("Bytes inspected", summary.inspectedBytes.toString())
                    QualityMetric("Skipped files / symlinks", "${summary.skippedFiles} / ${summary.skippedSymlinks}")
                    QualityMetric("Test files observed (not run)", summary.testFilesObserved.toString())
                    QualityMetric("Lockfiles observed", summary.lockfilesObserved.toString())
                    QualityMetric("Build configurations observed", summary.buildConfigurationsObserved.toString())
                    QualityText("Excluded directories: ${summary.excludedDirectories.joinToString(", ")}")
                    QualityText("Reads files on disk. Unsaved editor changes and uninspected paths are outside this scan.")
                }
            }
        }
        if (report.groups.isNotEmpty()) {
            QualityCard(if (report.kind == ReportKind.LOGS) "Recurring log signatures" else "Problem groups & evidence") {
                QualityPagedList(report.groups, 5, report.generatedAt) { group ->
                    key(group.id) { QualityGroupRow(group, onOpenUrl) }
                }
            }
        }
        if (report.records.isNotEmpty()) {
            QualityCard("Archive records") {
                QualityFold("Browse ${report.records.size} included issues and PRs") {
                    QualityPagedList(report.records, 6, report.generatedAt) { record ->
                        key(record.id) { QualityRecordRow(record, onOpenUrl) }
                    }
                }
            }
        }
        report.logSummary?.takeIf { it.events.isNotEmpty() }?.let { summary ->
            QualityCard("Log evidence") {
                QualityFold("Browse ${summary.events.size} detected event blocks") {
                    QualityPagedList(summary.events, 5, report.generatedAt) { event ->
                        key(event.startLine, event.fingerprint) {
                            QualityText(event.exceptionClass, color = MaterialTheme.colorScheme.onSurface)
                            QualityText("Lines ${event.startLine}–${event.endLine} · ${event.frames.size} retained frames")
                            QualityFold("Evidence at line ${event.startLine}") {
                                QualityEvidenceText(event.excerpt)
                                QualityText("Fingerprint: ${event.fingerprint}")
                                if (event.redacted) QualityText("Credential patterns redacted in this event.")
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
        if (report.checklists.isNotEmpty()) {
            QualityCard("Suggested verification · not executed") {
                QualityText("These are review steps, not test results. Run actual tasks separately and record real outcomes.")
                QualityFold("${report.checklists.size} verification checklists") {
                    QualityPagedList(report.checklists, 4, report.generatedAt) { checklist ->
                        QualityText(checklist.title, style = MaterialTheme.typography.titleSmall)
                        QualityText(checklist.status)
                        checklist.steps.forEachIndexed { index, step -> QualityText("${index + 1}. $step") }
                        HorizontalDivider()
                    }
                }
            }
        }
        val limitations = remember(report) {
            report.warnings.map { "Warning: $it" } + report.caveats
        }
        if (limitations.isNotEmpty()) {
            QualityCard("Scope & limitations") {
                QualityPagedList(limitations, 4, report.generatedAt) { QualityText(it) }
            }
        }
    }
}

@Composable
private fun QualityArchiveStatistics(stats: ArchiveStatistics, identity: Any) {
    QualityCard("Primary categories · included records") {
        QualityText("One primary category per record. Issues and pull requests have separate denominators.")
        val themes = remember(stats) { stats.includedByTheme.entries.filter { it.value.total > 0 }.sortedByDescending { it.value.total } }
        if (themes.isEmpty()) QualityText("No records in the current inclusion scope.")
        for ((theme, counts) in themes) QualityMetric(theme.label, qualityCounts(counts))
        QualityFold("Repository, date & specialized-support sensitivity") {
            QualityMetric("Full archive (deduplicated)", qualityCounts(stats.full))
            QualityMetric("Included scope", qualityCounts(stats.included))
            stats.recent?.let { QualityMetric("Recent window, before specialty filter", qualityCounts(it)) }
            stats.recentWindow?.let { QualityText("Recent created-at window (inclusive, UTC): ${it.start} → ${it.endInclusive}") }
                ?: QualityText("No recent-window counts are available; no date window was assumed.")
            QualityText("Observed created-at range: ${stats.oldestCreatedAt ?: "unknown"} → ${stats.newestCreatedAt ?: "unknown"}")
            QualityMetric("Missing creation dates", stats.missingCreatedAtCount.toString())
            stats.outsideWindowCount?.let { QualityMetric("Outside recent window", it.toString()) }
            QualityMetric("Specialized device/security subset", qualityCounts(stats.deviceSecuritySpecific))
            QualityMetric("Full archive without that subset", qualityCounts(stats.withoutDeviceSecuritySpecific))
            QualityText(
                "Specialized device/security content is withheld, even when its counts are included. " +
                    "Repository or date mix can change the apparent ranking; counts do not establish causes or prevalence across all apps.",
            )
            QualityFold("Primary categories: full vs without specialized subset") {
                for ((theme, fullCounts) in stats.fullByTheme.entries.sortedByDescending { it.value.total }) {
                    if (fullCounts.total == 0) continue
                    QualityText(theme.label, color = MaterialTheme.colorScheme.onSurface)
                    QualityMetric("Full", qualityCounts(fullCounts))
                    QualityMetric("Without specialized subset", qualityCounts(stats.withoutDeviceSecurityByTheme[theme] ?: RecordCounts()))
                }
            }
            QualityFold("${stats.repositories.size} repositories") {
                QualityPagedList(stats.repositories, 5, identity) { repository ->
                    QualityText(repository.repository, color = MaterialTheme.colorScheme.onSurface)
                    QualityMetric("Full / included", "${qualityCounts(repository.full)} / ${qualityCounts(repository.included)}")
                    repository.recent?.let { QualityMetric("Recent", qualityCounts(it)) }
                    QualityMetric("Specialized subset", qualityCounts(repository.deviceSecuritySpecific))
                    HorizontalDivider()
                }
            }
            QualityMetric("Input records", stats.inputRecordCount.toString())
            QualityMetric("Invalid / duplicate records", "${stats.invalidRecordCount} / ${stats.duplicateRecordCount}")
            QualityMetric("Truncated record text", stats.truncatedRecordCount.toString())
            QualityFold("Archive metadata & recorded states") {
                if (stats.metadata.isNotEmpty()) {
                    QualityPagedList(stats.metadata, 4, identity) { metadata ->
                        QualityText("Source: ${metadata.source}")
                        QualityText("Export generated: ${metadata.generated ?: "unknown"}")
                        QualityText("Declared period: ${metadata.declaredPeriod ?: "not provided"}")
                        metadata.declaredWindow?.let { QualityText("Declared window: ${it.start} → ${it.endInclusive}") }
                    }
                }
                QualityText("Issue states: ${stats.issuesByState.entries.joinToString { "${qualityEnumLabel(it.key.name)} ${it.value}" }}")
                QualityText("PR states: ${stats.pullRequestsByState.entries.joinToString { "${qualityEnumLabel(it.key.name)} ${it.value}" }}")
                QualityText("PR merge states: ${stats.pullRequestsByMergeStatus.entries.joinToString { "${qualityEnumLabel(it.key.name)} ${it.value}" }}")
                QualityText("Unknown merge state stays unknown. Closure or merge is not a confirmed outcome.")
            }
        }
    }
}

@Composable
private fun QualityGroupRow(group: QualityGroup, onOpenUrl: ((String) -> Unit)?) {
    QualityText(group.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
    QualityText(
        if (group.kind == GroupKind.THEME) "${group.issueCount} issues · ${group.pullRequestCount} PRs · ${qualityEnumLabel(group.confidence.name)} confidence"
        else "${group.occurrenceCount} occurrences · ${qualityEnumLabel(group.confidence.name)} confidence",
    )
    QualityFold("Group details & ${group.evidence.size} evidence samples") {
        QualityText(group.explanation)
        group.fingerprint?.let { QualityText("Fingerprint: $it") }
        if (group.linkedPullRequestIds.isNotEmpty()) {
            QualityText("Explicitly referenced PRs (not confirmed fixes): ${group.linkedPullRequestIds.joinToString()}")
        }
        QualityPagedList(group.evidence, 3, group.id, "No evidence excerpt available for this group.") { evidence ->
            QualityCitation(evidence, onOpenUrl)
        }
        QualityText("Sample evidence is an author's report, not independently verified cause or outcome.")
    }
    HorizontalDivider()
}

@Composable
private fun QualityRecordRow(record: QualityRecord, onOpenUrl: ((String) -> Unit)?) {
    val kind = if (record.kind == RecordKind.ISSUE) "Issue" else "PR"
    QualityText("$kind #${record.number} · ${record.repository}")
    QualityText(record.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 4)
    QualityText("${record.theme.label} · ${qualityEnumLabel(record.confidence.name)} confidence")
    QualityFold("Details for $kind #${record.number}") {
        QualityText("Intent: ${qualityEnumLabel(record.intent.name)} · Transferability: ${qualityEnumLabel(record.transferability.name)}")
        QualityText("Created: ${record.createdAt ?: "unknown"} · State: ${qualityEnumLabel(record.state.name)}")
        if (record.kind == RecordKind.PULL_REQUEST) QualityText("Merge state: ${qualityEnumLabel(record.mergeStatus.name)}")
        val cause = when (record.causeStatus) {
            CauseStatus.SYMPTOM_ONLY -> "Symptom only — no cause established"
            CauseStatus.REPORTER_HYPOTHESIS -> "Reporter hypothesis — not a confirmed cause"
            CauseStatus.MECHANISM_DESCRIBED -> "Author-described mechanism — not independently confirmed"
            CauseStatus.NOT_APPLICABLE -> "Cause not applicable / not established"
        }
        QualityText(cause, color = MaterialTheme.colorScheme.onSurface)
        QualityText(record.classificationReason)
        if (record.secondaryThemes.isNotEmpty()) QualityText("Secondary themes: ${record.secondaryThemes.joinToString { it.label }}")
        QualityCitation(record.evidence, onOpenUrl)
        QualityGitHubLink(record.url, onOpenUrl)
        if (record.deviceSecuritySpecific) {
            QualityText("Specialized device/security content withheld. Only classification and counts are shown.")
        } else if (record.body.isNotBlank()) {
            QualityFold("Redacted source text") { QualityEvidenceText(record.body) }
        }
        if (record.textTruncated) QualityText("Source text was truncated by import limits.")
        if (record.references.isNotEmpty()) {
            QualityFold("${record.references.size} explicit cross-references") {
                QualityPagedList(record.references, 4, record.id) { reference ->
                    QualityText(reference.id)
                    QualityGitHubLink(reference.url, onOpenUrl)
                }
            }
        }
        QualityText("Links are explicit source references, not proof of a fix, reproduction or verified causal connection.")
    }
    HorizontalDivider()
}

@Composable
private fun QualityCitation(evidence: EvidenceCitation, onOpenUrl: ((String) -> Unit)?) {
    QualityText("${qualityEnumLabel(evidence.source.name)} evidence${evidence.line?.let { " · line $it" }.orEmpty()}")
    QualityEvidenceText(evidence.snippet, maxChars = 2_000)
    QualityGitHubLink(evidence.url, onOpenUrl)
    if (evidence.redacted) QualityText("Credential patterns redacted.")
}

@Composable
private fun QualityGitHubLink(raw: String?, onOpenUrl: ((String) -> Unit)?) {
    val url = remember(raw) { qualityGitHubUrl(raw) }
    if (url != null) {
        if (onOpenUrl != null) {
            TextButton(onClick = { onOpenUrl(url) }, modifier = Modifier.fillMaxWidth()) {
                Text("Open GitHub source · ${url.removePrefix("https://github.com/")}", modifier = Modifier.fillMaxWidth())
            }
        } else QualityText(url)
    } else if (!raw.isNullOrBlank()) QualityText("Source link unavailable: only HTTPS GitHub issue/PR links are opened.")
}

@Composable
private fun QualityFindingRow(finding: QualityFinding, root: String, onOpenSource: QualityOpenSource?) {
    QualityText(
        "${qualityEnumLabel(finding.severity.name)} · ${qualityEnumLabel(finding.status.name)} · ${finding.ruleId}",
        color = if (finding.severity == Severity.HIGH) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
    QualityText(finding.reason, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
    val path = remember(root, finding.file) { finding.file.removePrefix(root.trimEnd('/') + "/") }
    QualityText("$path:${finding.line}")
    QualityFold("Evidence & suggested remediation") {
        QualityEvidenceText(finding.evidence)
        QualityText(finding.remediation)
        QualityText("Review needed; this screen does not edit or auto-fix source files.")
        if (onOpenSource != null) {
            TextButton(onClick = { onOpenSource(finding.file, finding.line, root) }) { Text("Open source at line ${finding.line}") }
        } else QualityText("Source navigation is unavailable in this host.")
    }
    HorizontalDivider()
}

private fun qualityCounts(counts: RecordCounts): String = "${counts.issues} issues · ${counts.pullRequests} PRs"
