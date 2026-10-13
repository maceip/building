package dev.ide.workbench.quality

import java.nio.file.Path
import java.time.Clock

/**
 * Production entry point for the offline App Quality & Reliability Lab.
 *
 * Methods are synchronous and bounded. UI hosts should invoke them on Dispatchers.IO (or their own
 * worker executor). Inputs are untrusted data: no code, links, scripts, attachment downloads, LLMs,
 * credentials, remote uploads, or build/test tasks are invoked. Export is the only write operation.
 * Each operation is isolated; a service may be called from multiple worker threads.
 *
 * @throws QualityException for invalid/unsupported inputs, configured safety limits, unsafe paths,
 * file access failures, or an existing export destination. Do not interpret failure/empty reports
 * as a quality/security verdict.
 */
class QualityService(
    private val config: QualityConfig = QualityConfig(),
    private val clock: Clock = Clock.systemUTC(),
) {
    fun importArchive(path: Path): QualityReport = ArchiveImporter(config.archive, clock).import(path)
    fun analyzeLogs(text: String): QualityReport = LogAnalyzer(config.logs, clock).analyze(text)
    fun scanProject(root: Path): QualityReport = ProjectQualityScanner(config.scanner, clock).scan(root)
    fun export(report: QualityReport, destination: Path, format: ExportFormat = ExportFormat.fromPath(destination)): ExportResult =
        QualityExporter(config.maxExportBytes).export(report, destination, format)
}
