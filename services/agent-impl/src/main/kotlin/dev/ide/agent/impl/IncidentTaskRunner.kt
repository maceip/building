package dev.ide.agent.impl

import dev.ide.agent.IncidentTask
import dev.ide.agent.IncidentTaskReceipt
import dev.ide.agent.IncidentTaskRunner
import dev.ide.agent.IncidentTestCase
import dev.ide.agent.IncidentTestOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The launcher is the host's real execution/sandbox boundary. It must use the exact approved command,
 * confine writes to copyRoot, expose no credentials, deny network, and kill the process group on stop.
 * Merely setting a subprocess's cwd or passing Gradle --offline is NOT a filesystem/network sandbox.
 * No permissive default launcher is supplied.
 */
interface IncidentProcessLauncher {
    fun start(copyRoot: Path, task: IncidentTask): Process
    fun stop(process: Process)
}

/** Real process exit + newly generated structured JUnit. Neither stdout nor model prose proves a pass. */
class IncidentProcessTaskRunner(private val launcher: IncidentProcessLauncher) : IncidentTaskRunner {
    override suspend fun run(copyRoot: Path, task: IncidentTask): IncidentTaskReceipt = withContext(Dispatchers.IO) {
        IncidentJUnitEvidence.clear(copyRoot, task)
        currentCoroutineContext().ensureActive()
        val process = launcher.start(copyRoot, task)
        // Drain, but never include arbitrary process output in cloud prompts or persist it as incident data.
        val pumps = listOf(process.inputStream, process.errorStream).map { input ->
            Thread({ runCatching { input.use { stream ->
                val buffer = ByteArray(4096)
                while (stream.read(buffer) >= 0) Unit
            } } }, "incident-task-output").apply { isDaemon = true; start() }
        }
        try {
            process.outputStream.close()
            val exit = withTimeout(task.timeoutMillis) {
                while (!process.waitFor(40, TimeUnit.MILLISECONDS)) {
                    currentCoroutineContext().ensureActive()
                    delay(10)
                }
                process.exitValue()
            }
            currentCoroutineContext().ensureActive()
            IncidentJUnitEvidence.receipt(copyRoot, task, exit)
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                launcher.stop(process)
                runCatching { process.inputStream.close() }
                runCatching { process.errorStream.close() }
                pumps.forEach { it.join(500) }
            }
        }
    }
}

/** Also used by the native Gradle/Android adapter after its actual structured tooling task exit. */
object IncidentJUnitEvidence {
    fun clear(root: Path, task: IncidentTask) {
        require(task.reportFiles.size <= 32)
        task.reportFiles.forEach { name ->
            val file = incidentPath(root, name, existing = false)
            require(name.endsWith(".xml") && name !in task.command) { "Expected a dedicated JUnit result path." }
            require(!Files.exists(file, NOFOLLOW_LINKS) || Files.isRegularFile(file, NOFOLLOW_LINKS))
            Files.deleteIfExists(file)
        }
    }

    fun receipt(root: Path, task: IncidentTask, exitCode: Int?, terminalTaskSuccess: Boolean? = null): IncidentTaskReceipt {
        val cases = ArrayList<IncidentTestCase>()
        val hashes = linkedMapOf<String, String>()
        task.reportFiles.sorted().forEach { name ->
            val path = incidentPath(root, name, existing = false)
            if (!Files.isRegularFile(path, NOFOLLOW_LINKS)) return@forEach
            require(Files.size(path) <= 1024 * 1024) { "JUnit report exceeds the verification limit." }
            val bytes = Files.readAllBytes(path)
            hashes[name] = IncidentDigests.bytes(bytes)
            cases += parse(bytes)
        }
        require(cases.map { it.id }.distinct().size == cases.size) { "Ambiguous duplicate JUnit test identities." }
        return IncidentTaskReceipt(UUID.randomUUID().toString(), task.id, IncidentDigests.task(task), exitCode,
            completed = true, tests = cases, reportHashes = hashes, terminalTaskSuccess = terminalTaskSuccess)
    }

    private fun parse(bytes: ByteArray): List<IncidentTestCase> {
        // Android and desktop XML providers differ; fail closed if entity protection cannot be installed.
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            isValidating = false
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            isExpandEntityReferences = false
        }
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        require('\u0000' !in text && !Regex("(?i)<!\\s*(?:DOCTYPE|ENTITY)").containsMatchIn(text)) { "Only entity-free UTF-8 JUnit evidence is accepted." }
        val builder = factory.newDocumentBuilder()
        builder.setEntityResolver { _, _ -> throw IllegalArgumentException("External entities are forbidden.") }
        val document = bytes.inputStream().use { builder.parse(it) }
        require(document.documentElement.tagName in setOf("testsuite", "testsuites")) { "Not a structured JUnit report." }
        val nodes = document.getElementsByTagName("testcase")
        require(nodes.length in 1..5000) { "JUnit report has no bounded test evidence." }
        return (0 until nodes.length).map { index ->
            val element = nodes.item(index) as org.w3c.dom.Element
            val className = element.getAttribute("classname")
            val name = element.getAttribute("name")
            require(className.isNotBlank() && name.isNotBlank() && className.length + name.length <= 1024)
            val outcome = when {
                element.getElementsByTagName("failure").length > 0 -> IncidentTestOutcome.FAILED
                element.getElementsByTagName("error").length > 0 -> IncidentTestOutcome.ERROR
                element.getElementsByTagName("skipped").length > 0 -> IncidentTestOutcome.SKIPPED
                else -> IncidentTestOutcome.PASSED
            }
            IncidentTestCase("$className#$name", outcome)
        }
    }
}
