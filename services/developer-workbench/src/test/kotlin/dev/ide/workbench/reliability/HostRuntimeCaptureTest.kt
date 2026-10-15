package dev.ide.workbench.reliability

import kotlinx.serialization.json.JsonArray
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HostRuntimeCaptureTest {
    @TempDir lateinit var directory: Path
    private val failure = HostRuntimeFinish(HostRuntimeOutcome.INFRASTRUCTURE_FAILURE, HostRuntimeStage.PREPARE, HostRuntimeException.IO, durationMs = 4)

    private inner class Fixture(val maxQueued: Int = 64) {
        val registry = MemoryReliabilityStorage()
        val outbox = MemoryReliabilityStorage()
        val inbox = MemoryReliabilityStorage()
        val repository = DurableIncidentRepository(inbox, clock = testClock)
        var snapshots = 0
        var snapshot: (Path) -> String? = { "d".repeat(64) }
        var delivery: (List<HostRuntimeIncident>) -> DurableIncidentRepository.CommitResult = repository::acceptRuntime
        val capture = service()
        fun service() = HostRuntimeCapture(registry, outbox, { delivery(it) }, { snapshots++; snapshot(it) }, runtimeIdentity, testClock, maxQueued)
        fun begin(root: Path = directory, kind: HostRuntimeKind = HostRuntimeKind.NODE) =
            capture.begin(root, UUID.randomUUID().toString(), kind, HostRuntimeChannel.RUN)
    }

    @Test fun `default off and construction never snapshot deliver or create files`() {
        val f = Fixture()
        assertEquals(0, f.snapshots)
        assertEquals(RuntimeCaptureResult.DISABLED, f.capture.status(directory).result)
        assertNull(f.begin())
        assertNull(f.begin())
        assertEquals(0, f.snapshots)
        assertNull(f.registry.bytes)
        assertNull(f.outbox.bytes)
        assertNull(f.inbox.bytes)
        assertTrue(Files.list(directory).use { it.findAny().isEmpty })
    }

    @Test fun `consented begin completes pre run hash once and incident has no root name or source contents`() {
        val f = Fixture()
        val secretRoot = Files.createDirectory(directory.resolve("private-name-token-secret"))
        val source = secretRoot.resolve("sensitive-source.ts")
        Files.writeString(source, "const password = 'private-source-secret'")
        var beforeExecution = true
        f.snapshot = { root ->
            assertEquals(secretRoot.toRealPath(), root)
            assertTrue(beforeExecution)
            "a".repeat(64)
        }
        assertTrue(f.capture.setEnabled(secretRoot, true).enabled)
        val handle = assertNotNull(f.begin(secretRoot))
        assertEquals(1, f.snapshots)
        beforeExecution = false
        Files.writeString(source, "changed during execution")
        assertEquals(RuntimeCaptureResult.DELIVERED, handle.finish(failure).result)
        val record = f.repository.list().single()
        assertEquals("a".repeat(64), record.runtimeContext!!.sourceRevision)
        assertEquals(HostRuntimeSourceStatus.PRE_RUN_SNAPSHOT, record.runtimeContext!!.sourceStatus)
        assertEquals("runtime-source-v1", record.runtimeContext!!.sourcePolicy)
        assertEquals(1, f.snapshots)
        for (bytes in listOf(f.registry.bytes, f.outbox.bytes, f.inbox.bytes)) {
            val text = bytes!!.toString(Charsets.UTF_8)
            for (secret in listOf(secretRoot.toString(), "private-name", "private-source-secret", "sensitive-source", "changed during execution")) assertFalse(secret in text)
        }
        val registered = reliabilityObject(f.registry.bytes!!.toString(Charsets.UTF_8))["workspaces"] as JsonArray
        assertEquals(digest(secretRoot.toRealPath().toString()), registered.single().objectValue().text("rootHash"))
        assertFalse("rootHash" in f.inbox.bytes!!.toString(Charsets.UTF_8))
        assertFalse("generation" in f.inbox.bytes!!.toString(Charsets.UTF_8))
    }

    @Test fun `canonical root aliases share one consent workspace`() {
        val root = Files.createDirectory(directory.resolve("project"))
        val alias = directory.resolve("alias")
        Files.createSymbolicLink(alias, root)
        val f = Fixture()
        val grant = f.capture.setEnabled(root, true)
        assertEquals(grant.workspaceId, f.capture.status(alias).workspaceId)
        assertNotNull(f.begin(alias))
        assertEquals(RuntimeCaptureResult.DISABLED, f.capture.setEnabled(alias, false).result)
        assertNull(f.begin(root))
    }

    @Test fun `missing failing and malformed source snapshots remain explicitly unavailable without inventing hash`() {
        val f = Fixture()
        f.capture.setEnabled(directory, true)
        for (snapshot in listOf<(Path) -> String?>({ null }, { "approximate" }, { throw IOException("private-root-token") }, { throw NoClassDefFoundError("private-api") })) {
            f.snapshot = snapshot
            assertEquals(RuntimeCaptureResult.DELIVERED, assertNotNull(f.begin()).finish(failure).result)
        }
        assertEquals(4, f.repository.list().size)
        for (record in f.repository.list()) {
            assertNull(record.runtimeContext!!.sourceRevision)
            assertEquals(HostRuntimeSourceStatus.UNAVAILABLE, record.runtimeContext!!.sourceStatus)
            assertEquals("", record.sourceRevision)
        }
        assertFalse("private-root-token" in f.inbox.bytes!!.toString(Charsets.UTF_8))
    }

    @Test fun `success and cancellation close handle without outbox or inbox event`() {
        val f = Fixture()
        f.capture.setEnabled(directory, true)
        val handle = assertNotNull(f.begin())
        assertEquals(RuntimeCaptureResult.IGNORED, handle.finish(HostRuntimeFinish(null, HostRuntimeStage.SESSION)).result)
        assertEquals(RuntimeCaptureResult.IGNORED, handle.finish(failure).result)
        assertNull(f.outbox.bytes)
        assertTrue(f.repository.list().isEmpty())
    }

    @Test fun `ignored finish publishes its own workspace consent and rechecks revoked generation`() {
        val f = Fixture()
        val other = Files.createDirectory(directory.resolve("other"))
        val firstGrant = f.capture.setEnabled(directory, true)
        f.capture.setEnabled(other, true)
        val first = assertNotNull(f.begin())
        val second = assertNotNull(f.begin(other))
        f.capture.setEnabled(other, false)
        val ignored = first.finish(HostRuntimeFinish(null, HostRuntimeStage.SESSION))
        assertEquals(RuntimeCaptureResult.IGNORED, ignored.result)
        assertEquals(firstGrant.workspaceId, ignored.workspaceId)
        assertTrue(ignored.enabled)
        assertTrue(ignored.consentKnown)
        assertEquals(RuntimeCaptureResult.REVOKED, second.finish(HostRuntimeFinish(null, HostRuntimeStage.SESSION)).result)
        assertTrue(f.repository.list().isEmpty())
    }

    @Test fun `NPM nonzero timeout and configuration failures are typed and preparation metadata optional only before preparation`() {
        val f = Fixture()
        f.capture.setEnabled(directory, true)
        val npm = assertNotNull(f.begin(kind = HostRuntimeKind.NPM))
        npm.prepared(runtimePackId, "arm64-v8a")
        assertEquals(RuntimeCaptureResult.DELIVERED, npm.finish(HostRuntimeFinish(HostRuntimeOutcome.NONZERO_EXIT,
            HostRuntimeStage.PROCESS, exitCode = 19, durationMs = 5)).result)
        val timeout = assertNotNull(f.begin())
        timeout.prepared(runtimePackId, "arm64-v8a")
        assertEquals(RuntimeCaptureResult.DELIVERED, timeout.finish(HostRuntimeFinish(HostRuntimeOutcome.TIMEOUT,
            HostRuntimeStage.PROCESS, durationMs = 7)).result)
        val invalid = assertNotNull(f.begin(kind = HostRuntimeKind.PREPARATION))
        assertEquals(RuntimeCaptureResult.DELIVERED, invalid.finish(HostRuntimeFinish(HostRuntimeOutcome.CONFIGURATION_FAILURE,
            HostRuntimeStage.PREPARE, HostRuntimeException.INVALID_CONFIGURATION)).result)
        val records = f.repository.list().map { it.runtimeContext!! }
        assertEquals(setOf(HostRuntimeOutcome.NONZERO_EXIT, HostRuntimeOutcome.TIMEOUT, HostRuntimeOutcome.CONFIGURATION_FAILURE), records.map { it.outcome }.toSet())
        assertEquals(runtimePackId, records.single { it.runtimeKind == HostRuntimeKind.NPM }.packId)
        assertNull(records.single { it.runtimeKind == HostRuntimeKind.PREPARATION }.packId)
    }

    @Test fun `outbox is durably present before delivery and only accepted keys acknowledge it`() {
        val f = Fixture()
        f.capture.setEnabled(directory, true)
        var calls = 0
        f.delivery = { events ->
            calls++
            val queue = reliabilityObject(f.outbox.bytes!!.toString(Charsets.UTF_8))["events"] as JsonArray
            val persisted = parseRuntimeIncident(queue.single().objectValue()["event"]!!.objectValue())
            assertEquals(events.single(), persisted)
            val committed = f.repository.acceptRuntime(events)
            // newlyStored is not an ACK. No acceptedKeys means the durable queue must remain intact.
            DurableIncidentRepository.CommitResult(emptyList(), committed.newlyStored)
        }
        val handle = assertNotNull(f.begin())
        assertEquals(RuntimeCaptureResult.QUEUED, handle.finish(failure).result)
        assertEquals(1, f.capture.status(directory).queuedEvents)
        f.delivery = f.repository::acceptRuntime
        assertEquals(RuntimeCaptureResult.DELIVERED, f.capture.replay().result)
        assertEquals(0, f.capture.status(directory).queuedEvents)
        assertEquals(1, f.repository.list().size)
        assertEquals(1, calls)
    }

    @Test fun `inbox commit before queue ACK crash replays same event once without duplicate new incident`() {
        val f = Fixture()
        f.capture.setEnabled(directory, true)
        var newlyStored = 0
        val deliveredIds = mutableListOf<IncidentKey>()
        f.delivery = { events ->
            deliveredIds += events.map { it.key }
            f.repository.acceptRuntime(events).also {
                newlyStored += it.newlyStored.size
                f.outbox.failBeforeWrite = true // simulate death before removing the durable outbox entry.
            }
        }
        val result = assertNotNull(f.begin()).finish(failure)
        assertEquals(RuntimeCaptureResult.STORAGE_UNAVAILABLE, result.result)
        assertFalse(result.consentKnown)
        assertEquals(1, f.repository.list().size)
        f.outbox.failBeforeWrite = false
        val restartedRepository = DurableIncidentRepository(f.inbox, clock = testClock)
        val restart = HostRuntimeCapture(f.registry, f.outbox, { events ->
            deliveredIds += events.map { it.key }
            restartedRepository.acceptRuntime(events).also { newlyStored += it.newlyStored.size }
        }, { error("replay must never snapshot sources") }, runtimeIdentity, testClock)
        assertEquals(RuntimeCaptureResult.DELIVERED, restart.replay().result)
        assertEquals(1, newlyStored)
        assertEquals(2, deliveredIds.size)
        assertEquals(deliveredIds[0], deliveredIds[1])
        assertEquals(0, restart.status(directory).queuedEvents)
        restart.replay()
        assertEquals(2, deliveredIds.size)
    }

    @Test fun `queue write failure cannot deliver and later replay retains exact uncertain event`() {
        val f = Fixture()
        f.capture.setEnabled(directory, true)
        var delivered = 0
        f.delivery = { events -> delivered++; f.repository.acceptRuntime(events) }
        f.outbox.failBeforeWrite = true
        val handle = assertNotNull(f.begin())
        assertEquals(RuntimeCaptureResult.STORAGE_UNAVAILABLE, handle.finish(failure).result)
        assertEquals(0, delivered)
        f.outbox.failBeforeWrite = false
        f.outbox.failAfterWrite = true
        assertEquals(RuntimeCaptureResult.STORAGE_UNAVAILABLE, handle.finish(failure).result)
        assertEquals(0, delivered)
        val pending = reliabilityObject(f.outbox.bytes!!.toString(Charsets.UTF_8))["events"] as JsonArray
        val queued = parseRuntimeIncident(pending.single().objectValue()["event"]!!.objectValue())
        f.outbox.failAfterWrite = false
        assertEquals(RuntimeCaptureResult.DELIVERED, f.service().replay().result)
        assertEquals(1, delivered)
        assertNotNull(f.repository.read(queued.key))
    }

    @Test fun `bounded queue rejects only new event and never silently erases unrelated queued workspace`() {
        val f = Fixture(maxQueued = 2)
        val second = Files.createDirectory(directory.resolve("second"))
        val third = Files.createDirectory(directory.resolve("third"))
        f.delivery = { throw ReliabilityException(ReliabilityError.QUOTA) }
        for (root in listOf(directory, second, third)) f.capture.setEnabled(root, true)
        for (root in listOf(directory, second)) assertEquals(RuntimeCaptureResult.QUOTA, assertNotNull(f.begin(root)).finish(failure).result)
        val before = f.outbox.bytes!!.clone()
        assertEquals(RuntimeCaptureResult.QUOTA, assertNotNull(f.begin(third)).finish(failure).result)
        assertTrue(before.contentEquals(f.outbox.bytes))
        assertEquals(1, f.capture.status(directory).queuedEvents)
        assertEquals(1, f.capture.status(second).queuedEvents)
        assertEquals(0, f.capture.status(third).queuedEvents)
        f.delivery = f.repository::acceptRuntime
        assertEquals(RuntimeCaptureResult.DELIVERED, f.capture.replay().result)
        assertEquals(2, f.repository.list().size)
    }

    @Test fun `quota blocked workspace does not starve other queued workspace during replay`() {
        val f = Fixture()
        val other = Files.createDirectory(directory.resolve("other"))
        val firstWorkspace = f.capture.setEnabled(directory, true).workspaceId
        f.capture.setEnabled(other, true)
        f.delivery = { throw ReliabilityException(ReliabilityError.RETRY_LATER) }
        assertNotNull(f.begin()).finish(failure)
        assertNotNull(f.begin(other)).finish(failure)
        f.delivery = { events ->
            if (events.single().workspaceId == firstWorkspace) throw ReliabilityException(ReliabilityError.QUOTA)
            f.repository.acceptRuntime(events)
        }
        assertEquals(RuntimeCaptureResult.QUOTA, f.capture.replay().result)
        assertEquals(1, f.capture.status(directory).queuedEvents)
        assertEquals(0, f.capture.status(other).queuedEvents)
        assertEquals(1, f.repository.list().size)
    }

    @Test fun `revocation purges only workspace pending events retains saved history and never revives old handles`() {
        val f = Fixture()
        val other = Files.createDirectory(directory.resolve("other"))
        val first = f.capture.setEnabled(directory, true)
        f.capture.setEnabled(other, true)
        val oldHandle = assertNotNull(f.begin())
        assertEquals(RuntimeCaptureResult.DELIVERED, assertNotNull(f.begin()).finish(failure).result)
        f.delivery = { throw IOException("delivery-secret") }
        assertNotNull(f.begin()).finish(failure)
        assertNotNull(f.begin(other)).finish(failure)
        assertEquals(1, f.capture.status(directory).queuedEvents)
        val revoked = f.capture.setEnabled(directory, false)
        assertFalse(revoked.enabled)
        assertEquals(0, revoked.queuedEvents)
        assertEquals(1, f.capture.status(other).queuedEvents)
        val freshGrant = f.capture.setEnabled(directory, true)
        assertEquals(first.workspaceId, freshGrant.workspaceId)
        assertEquals(RuntimeCaptureResult.REVOKED, oldHandle.finish(failure).result)
        f.delivery = f.repository::acceptRuntime
        f.capture.replay()
        assertEquals(2, f.repository.list().size) // saved first workspace history plus the unrelated pending event.
        assertEquals(RuntimeCaptureResult.DELIVERED, assertNotNull(f.begin()).finish(failure).result)
        assertEquals(3, f.repository.list().size)
    }

    @Test fun `disabled generation protects old queue even if purge fails and source callback revokes in flight`() {
        val f = Fixture()
        f.capture.setEnabled(directory, true)
        f.delivery = { throw IOException("unavailable") }
        assertNotNull(f.begin()).finish(failure)
        val oldBytes = f.outbox.bytes!!.clone()
        f.outbox.failBeforeWrite = true
        val failed = f.capture.setEnabled(directory, false)
        assertEquals(RuntimeCaptureResult.STORAGE_UNAVAILABLE, failed.result)
        assertFalse(failed.consentKnown)
        assertTrue(oldBytes.contentEquals(f.outbox.bytes))
        f.outbox.failBeforeWrite = false
        assertFalse(f.service().status(directory).enabled)
        f.capture.setEnabled(directory, true)
        f.delivery = f.repository::acceptRuntime
        f.capture.replay()
        assertTrue(f.repository.list().isEmpty())
        f.snapshot = {
            f.service().setEnabled(directory, false)
            f.service().setEnabled(directory, true)
            "f".repeat(64)
        }
        assertNull(f.begin())
        assertEquals(RuntimeCaptureResult.REVOKED, f.capture.updates.value.result)
    }

    @Test fun `failed before and after consent writes remain unknown and never falsely claim disabled`() {
        val f = Fixture()
        f.capture.setEnabled(directory, true)
        f.registry.failBeforeWrite = true
        val before = f.capture.setEnabled(directory, false)
        assertEquals(RuntimeCaptureResult.STORAGE_UNAVAILABLE, before.result)
        assertTrue(before.enabled) // last known bit, explicitly not confirmation.
        assertFalse(before.consentKnown)
        f.registry.failBeforeWrite = false
        assertTrue(f.capture.status(directory).enabled)
        f.registry.failAfterWrite = true
        val after = f.capture.setEnabled(directory, false)
        assertFalse(after.consentKnown)
        f.registry.failAfterWrite = false
        val durable = f.service().status(directory)
        assertFalse(durable.enabled)
        assertTrue(durable.consentKnown)
        assertNull(f.begin())
    }

    @Test fun `multiple service instances retain private registry changes and stale handles see revocation`() {
        val f = Fixture()
        val another = f.service()
        f.capture.setEnabled(directory, true)
        val handle = assertNotNull(f.begin())
        another.setEnabled(directory, false)
        another.setEnabled(directory, true)
        assertEquals(RuntimeCaptureResult.REVOKED, handle.finish(failure).result)
        assertTrue(f.repository.list().isEmpty())
        val otherRoot = Files.createDirectory(directory.resolve("another"))
        another.setEnabled(otherRoot, true)
        assertTrue(f.capture.status(directory).enabled)
        assertTrue(f.capture.status(otherRoot).enabled)
    }

    @Test fun `same handle retries use one event and malformed pack or duration cannot enter private stores`() {
        val f = Fixture()
        f.capture.setEnabled(directory, true)
        val handle = assertNotNull(f.begin())
        assertEquals(RuntimeCaptureResult.INVALID, handle.prepared("/private-root-secret", "arm64-v8a").result)
        assertEquals(RuntimeCaptureResult.INVALID, handle.finish(failure.copy(durationMs = MAX_RUNTIME_DURATION_MS + 1)).result)
        assertNull(f.outbox.bytes)
        assertEquals(RuntimeCaptureResult.DELIVERED, handle.finish(failure).result)
        assertEquals(RuntimeCaptureResult.DELIVERED, handle.finish(failure.copy(durationMs = 99)).result)
        assertEquals(1, f.repository.list().size)
        assertFalse("private-root-secret" in f.inbox.bytes!!.toString(Charsets.UTF_8))
    }

    @Test fun `corrupt private queue is visible never discarded and disable still durably revokes`() {
        val f = Fixture()
        f.capture.setEnabled(directory, true)
        f.outbox.bytes = "{truncated-private-content".toByteArray()
        val damaged = f.outbox.bytes!!.clone()
        assertEquals(RuntimeCaptureResult.STORAGE_UNAVAILABLE, f.capture.replay().result)
        assertEquals(RuntimeCaptureResult.STORAGE_UNAVAILABLE, f.capture.setEnabled(directory, false).result)
        assertTrue(damaged.contentEquals(f.outbox.bytes))
        val workspaces = reliabilityObject(f.registry.bytes!!.toString(Charsets.UTF_8))["workspaces"] as JsonArray
        assertFalse(workspaces.single().objectValue().bool("enabled"))
        assertFalse("truncated-private-content" in f.capture.updates.value.toString())
    }

    @Test fun `file transactions serialize revocation against durable delivery across service instances`() {
        val registryPath = directory.resolve("registry.json")
        val outboxPath = directory.resolve("outbox.json")
        val inbox = DurableIncidentRepository(AtomicReliabilityFile(directory.resolve("inbox.json"), DurableIncidentRepository.MAX_SNAPSHOT_BYTES), clock = testClock)
        val delivering = CountDownLatch(1)
        val releaseDelivery = CountDownLatch(1)
        val revokeAttempt = CountDownLatch(1)
        val revokeDone = CountDownLatch(1)
        val delivered = AtomicInteger()
        fun service(deliver: (List<HostRuntimeIncident>) -> DurableIncidentRepository.CommitResult) = HostRuntimeCapture(
            AtomicReliabilityFile(registryPath, HostRuntimeCapture.MAX_REGISTRY_BYTES),
            AtomicReliabilityFile(outboxPath, HostRuntimeCapture.MAX_OUTBOX_BYTES), deliver, { "b".repeat(64) }, runtimeIdentity, testClock)
        val first = service { events ->
            delivered.incrementAndGet()
            delivering.countDown()
            check(releaseDelivery.await(5, TimeUnit.SECONDS))
            inbox.acceptRuntime(events)
        }
        val second = service(inbox::acceptRuntime)
        first.setEnabled(directory, true)
        val handle = assertNotNull(first.begin(directory, runtimeSessionId, HostRuntimeKind.NODE, HostRuntimeChannel.RUN))
        val executor = Executors.newFixedThreadPool(2)
        try {
            val finish = executor.submit<RuntimeCaptureStatus> { handle.finish(failure) }
            assertTrue(delivering.await(5, TimeUnit.SECONDS))
            val revoke = executor.submit<RuntimeCaptureStatus> {
                revokeAttempt.countDown()
                second.setEnabled(directory, false).also { revokeDone.countDown() }
            }
            assertTrue(revokeAttempt.await(5, TimeUnit.SECONDS))
            assertFalse(revokeDone.await(100, TimeUnit.MILLISECONDS))
            releaseDelivery.countDown()
            assertEquals(RuntimeCaptureResult.DELIVERED, finish.get(5, TimeUnit.SECONDS).result)
            assertEquals(RuntimeCaptureResult.DISABLED, revoke.get(5, TimeUnit.SECONDS).result)
            first.replay()
            assertNull(first.begin(directory, UUID.randomUUID().toString(), HostRuntimeKind.NODE, HostRuntimeChannel.RUN))
            assertEquals(1, delivered.get())
            assertEquals(1, inbox.list().size)
        } finally {
            releaseDelivery.countDown()
            executor.shutdownNow()
        }
    }
}
