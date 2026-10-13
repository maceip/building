package dev.ide.workbench.quality

import java.time.Clock

/** Normalizes exception type + frames, never variable messages, addresses, timestamps, or source line numbers. */
internal class LogAnalyzer(private val config: LogAnalysisConfig, private val clock: Clock) {
    fun analyze(text: String): QualityReport {
        val result = parse(text, config)
        val groups = result.events.groupBy { it.fingerprint }.toSortedMap().map { (fingerprint, events) ->
            val first = events.first()
            QualityGroup(
                id = "log:$fingerprint", title = first.exceptionClass, theme = PrimaryTheme.RUNTIME_LIFECYCLE,
                confidence = if (first.frames.isEmpty()) Confidence.LOW else Confidence.HIGH,
                kind = if (first.frames.isEmpty()) GroupKind.EXCEPTION_SIGNATURE else GroupKind.STACK_FINGERPRINT,
                issueCount = 0, pullRequestCount = 0, recordIds = emptyList(),
                evidence = events.take(5).map { event -> EvidenceCitation("log:line:${event.startLine}", null, event.excerpt,
                    EvidenceSource.LOG, event.startLine, event.redacted) },
                fingerprint = fingerprint, occurrenceCount = events.size,
                explanation = if (first.frames.isEmpty()) "Exception-type-only match; no stack frames were available, so recurrence confidence is low."
                else "Normalized exception class and up to eight stack frames match. Addresses, source line numbers, messages, and timestamps are excluded; a shared cause is not confirmed.",
            )
        }
        val warnings = buildList {
            if (text.isBlank()) add("No log text was supplied; no assessment was made.")
            else if (result.events.isEmpty()) add("No supported JVM/Android exception signature was observed; absence of a signature does not mean the application is healthy.")
            if (result.truncatedLines > 0) add("${result.truncatedLines} long log lines were truncated; grouping may be incomplete.")
            if (result.redacted) add("Potential credentials, private paths, URLs, or identifiers were redacted before storing excerpts.")
        }
        return QualityReport(ReportKind.LOGS, "Local log recurrence analysis", clock.instant(), groups = groups,
            logSummary = result, warnings = warnings,
            checklists = if (groups.isEmpty()) emptyList() else VerificationChecklists.forThemes(listOf(PrimaryTheme.RUNTIME_LIFECYCLE)),
            caveats = listOf(
                "Supported evidence: JVM/Android exception names and stack frames. Native tombstones, arbitrary logs, and obfuscated stacks may need specialist review.",
                "Fingerprints identify recurring reported signatures, not confirmed causes or independent tests. Exception-type-only groups are weaker than stack groups.",
                "Redaction is best effort; review excerpts before sharing. Raw input is not retained, uploaded, executed, or written by this analyzer.",
                "Suggested verification is not executed; no automated test pass is inferred from logs.",
            ))
    }

    companion object {
        private val exception = Regex("(?<![A-Za-z0-9_$.])((?:[A-Za-z_$][A-Za-z0-9_$]*\\.)*[A-Z][A-Za-z0-9_$]*(?:Exception|Error)|(?:Exception|Error))(?=[:\\s]|$)")
        private val frame = Regex("(?:^|\\s)at\\s+((?:[A-Za-z0-9_.-]+/)?[A-Za-z_$][A-Za-z0-9_$.<>]*)\\(([^)\\r\\n]{0,300})\\)")
        private val causePrefix = Regex("(?i)(?:caused by:|suppressed:)")
        private val prefix = Regex("^(?:\\d{2}-\\d{2}\\s+)?\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?\\s+.*?\\s(?:[VDIWEF])\\s+[^:]{1,100}:\\s*")

        internal fun parse(text: String, config: LogAnalysisConfig): LogSummary {
            if (text.length > config.maxInputChars) limitExceeded("Log characters")
            val sanitized = QualityRedactor.redact(text)
            var lineCount = 0
            var truncated = 0
            var anyRedacted = sanitized.changed
            val events = ArrayList<LogEvent>()
            var start = 0
            var end = 0
            var exceptionClass: String? = null
            var frames = ArrayList<String>()
            var excerpt = StringBuilder()
            var changed = sanitized.changed
            fun finish() {
                val type = exceptionClass ?: return
                val normalizedFrames = frames.take(8)
                val fingerprint = stableHash("jvm-v1\n$type\n${normalizedFrames.joinToString("\n")}")
                if (events.size >= config.maxEvents) limitExceeded("Log event count")
                events += LogEvent(start, end, type, normalizedFrames, fingerprint, excerpt.toString().trim(), changed)
                exceptionClass = null; frames = ArrayList(); excerpt = StringBuilder(); changed = sanitized.changed
            }
            fun append(line: String) {
                if (excerpt.length >= config.maxExcerptChars) return
                val safe = QualityRedactor.redact(line)
                changed = changed || safe.changed
                val remaining = config.maxExcerptChars - excerpt.length
                excerpt.append((safe.text + "\n").take(remaining))
            }
            if (text.isNotEmpty()) for (original in sanitized.text.lineSequence()) {
                if (++lineCount > config.maxLines) limitExceeded("Log line count")
                if (original.length > config.maxLineChars) truncated++
                val line = original.take(config.maxLineChars)
                val safe = QualityRedactor.redact(line)
                anyRedacted = anyRedacted || safe.changed
                val content = prefix.replace(line, "")
                val frameMatch = frame.find(content)
                val type = if (frameMatch == null) exception.find(content)?.groupValues?.get(1) else null
                val caused = causePrefix.containsMatchIn(content)
                if (type != null) {
                    if (exceptionClass != null && !caused) finish()
                    if (exceptionClass == null) { start = lineCount; exceptionClass = type }
                    else if (content.contains("Caused by:", ignoreCase = true)) {
                        // The deepest reported cause and its frames define the signature, but are not verified causes.
                        exceptionClass = type; frames.clear()
                    }
                    end = lineCount
                    append(line)
                } else if (frameMatch != null && exceptionClass != null) {
                    end = lineCount
                    val method = frameMatch.groupValues[1].substringAfterLast('/')
                        .replace(Regex("\\${'$'}\\${'$'}Lambda\\${'$'}[0-9]+(?:/0x[0-9a-fA-F]+)?"), "\$\$Lambda")
                    if (frames.size < 8) frames += method
                    append(line)
                } else if (exceptionClass != null) {
                    if (content.isBlank() || (!content.trim().startsWith("... ") && !content.trim().startsWith("Suppressed:"))) finish()
                    else { end = lineCount; append(line) }
                }
            }
            finish()
            return LogSummary(lineCount, anyRedacted, truncated, events)
        }

        internal fun archiveGroups(records: List<QualityRecord>): List<QualityGroup> {
            val byFingerprint = sortedMapOf<String, MutableList<Pair<QualityRecord, LogEvent>>>()
            val config = LogAnalysisConfig(maxInputChars = 1_000_000, maxLines = 20_000, maxEvents = 2_000)
            for (record in records.filterNot { it.deviceSecuritySpecific }) {
                val events = parse(record.body, config).events
                for (event in events.distinctBy { it.fingerprint }) byFingerprint.getOrPut(event.fingerprint) { ArrayList() } += record to event
            }
            return byFingerprint.mapNotNull { (fingerprint, occurrences) ->
                if (occurrences.size < 2) return@mapNotNull null
                val first = occurrences.first().second
                val members = occurrences.map { it.first }
                val counts = countRecords(members)
                QualityGroup("stack:$fingerprint", first.exceptionClass, PrimaryTheme.RUNTIME_LIFECYCLE,
                    if (first.frames.isEmpty()) Confidence.LOW else Confidence.HIGH,
                    if (first.frames.isEmpty()) GroupKind.EXCEPTION_SIGNATURE else GroupKind.STACK_FINGERPRINT,
                    counts.issues, counts.pullRequests, members.map { it.id }.sorted(),
                    occurrences.take(5).map { (record, event) -> EvidenceCitation(record.id, record.url, event.excerpt,
                        EvidenceSource.LOG, event.startLine, event.redacted || record.evidence.redacted) },
                    fingerprint = fingerprint,
                    explanation = "Shared normalized ${if (first.frames.isEmpty()) "exception type only (weak evidence)" else "exception and stack frames"}. This overlaps primary-theme groups and is not proof of a shared cause.")
            }
        }
    }
}
