package dev.ide.workbench.reliability

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DurableIncidentRepositoryTest {
    @TempDir lateinit var directory: Path

    @Test fun `durable restart lost receipt and resolved state retain stable event identity`() {
        val file = AtomicReliabilityFile(directory.resolve("inbox.json"), DurableIncidentRepository.MAX_SNAPSHOT_BYTES)
        val first = DurableIncidentRepository(file, clock = testClock)
        val event = incident()
        assertEquals(listOf(event.key), first.accept(listOf(event)).acceptedKeys)
        first.setState(event.key, IncidentState.RESOLVED)
        val restarted = DurableIncidentRepository(file, clock = testClock)
        val retry = restarted.accept(listOf(event))
        assertTrue(retry.newlyStored.isEmpty())
        assertEquals(listOf(event.key), retry.acceptedKeys)
        assertEquals(1, restarted.list().size)
        assertEquals(IncidentState.RESOLVED, restarted.read(event.key)?.summary?.state)
        assertEquals(restarted.list(), restarted.observe().value)
    }

    @Test fun `same stable id with a different payload rejects the whole batch even when redaction matches`() {
        val disk = MemoryReliabilityStorage()
        val repository = DurableIncidentRepository(disk, clock = testClock)
        repository.accept(listOf(incident(text = "password=first")))
        val before = disk.bytes!!.clone()
        val error = assertFailsWith<ReliabilityException> {
            repository.accept(listOf(incident(id = "new"), incident(text = "password=second")))
        }
        assertEquals(ReliabilityError.ID_CONFLICT, error.code)
        assertTrue(before.contentEquals(disk.bytes))
        assertEquals(1, repository.list().size)
    }

    @Test fun `no raw credentials PII controls or pairing nonce enter persisted inbox`() {
        val disk = MemoryReliabilityStorage()
        val repository = DurableIncidentRepository(disk, clock = testClock)
        val secret = "f".repeat(64)
        val event = incident(text = "token=supersecret user@example.com /data/user/0/private/file https://private.example/a\n" +
            "Authorization: Bearer never-save\npairingNonce=$secret\n$secret\n\u202Ejava.lang.Exception")
        repository.accept(listOf(event))
        val bytes = disk.bytes!!.toString(Charsets.UTF_8)
        for (value in listOf("supersecret", "user@example.com", "/data/user", "private.example", "never-save", secret, "\u202E")) assertFalse(value in bytes)
        assertTrue(repository.read(event.key)!!.summary.hostRedacted)
        assertTrue("java.lang.Exception" in repository.read(event.key)!!.redactedText)
    }

    @Test fun `expanded redaction markers stay within durable reload bounds`() {
        val disk = MemoryReliabilityStorage()
        val first = DurableIncidentRepository(disk, clock = testClock)
        val event = incident(text = "token=x ".repeat(3_000))
        first.accept(listOf(event))
        assertTrue(first.read(event.key)!!.redactedText.length <= IncidentWire.MAX_TEXT_CHARS)
        val restarted = DurableIncidentRepository(disk, clock = testClock)
        assertNotNull(restarted.read(event.key))
        assertTrue(restarted.accept(listOf(event)).newlyStored.isEmpty())
    }

    @Test fun `quotas are per app and retries do not consume more capacity`() {
        val repository = DurableIncidentRepository(MemoryReliabilityStorage(), IncidentLimits(maxEventsPerApp = 1, maxEventsTotal = 2), testClock)
        repository.accept(listOf(incident()))
        assertEquals(1, repository.accept(listOf(incident())).acceptedKeys.size)
        assertEquals(ReliabilityError.QUOTA, assertFailsWith<ReliabilityException> { repository.accept(listOf(incident("second"))) }.code)
        repository.accept(listOf(incident("other").copy(key = incident("other").key.copy(packageName = "other.app"))))
        assertEquals(2, repository.list().size)
    }

    @Test fun `failed commit never publishes state and poisons instance rather than accepting stale retry`() {
        val disk = MemoryReliabilityStorage()
        val repository = DurableIncidentRepository(disk, clock = testClock)
        disk.failAfterWrite = true
        assertFailsWith<ReliabilityException> { repository.accept(listOf(incident())) }
        assertTrue(repository.observe().value.isEmpty())
        assertFailsWith<ReliabilityException> { repository.accept(listOf(incident())) }
        disk.failAfterWrite = false
        val restarted = DurableIncidentRepository(disk, clock = testClock)
        assertTrue(restarted.accept(listOf(incident())).newlyStored.isEmpty())
        assertEquals(1, restarted.list().size)
    }

    @Test fun `truncated primary fails closed while abandoned temp is ignored`() {
        val primary = directory.resolve("inbox.json")
        Files.writeString(directory.resolve(".inbox-interrupted.pending"), "{partial")
        val file = AtomicReliabilityFile(primary, DurableIncidentRepository.MAX_SNAPSHOT_BYTES)
        val repository = DurableIncidentRepository(file, clock = testClock)
        repository.accept(listOf(incident()))
        assertNotNull(DurableIncidentRepository(file).read(incident().key))
        Files.writeString(primary, "{truncated")
        assertEquals(ReliabilityError.STORAGE_UNAVAILABLE, assertFailsWith<ReliabilityException> { DurableIncidentRepository(file) }.code)
        assertEquals("{truncated", Files.readString(primary))
    }

    @Test fun `atomic writer requires directory sync before returning and never follows a target symlink`() {
        var synced = false
        val primary = directory.resolve("inbox.json")
        val file = AtomicReliabilityFile(primary, 4_096) { synced = true }
        file.write("durable".toByteArray())
        assertTrue(synced)
        assertEquals("durable", file.read()!!.toString(Charsets.UTF_8))
        val link = directory.resolve("link.json")
        Files.createSymbolicLink(link, primary)
        assertFailsWith<ReliabilityException> { AtomicReliabilityFile(link, 4_096).write("overwrite".toByteArray()) }
        assertEquals("durable", Files.readString(primary))
    }

    @Test fun `same event id across session or build is not the same incident`() {
        val repository = DurableIncidentRepository(MemoryReliabilityStorage(), clock = testClock)
        repository.accept(listOf(incident(), incident(build = "build-2"), incident(session = "session-2")))
        assertEquals(3, repository.list().size)
    }

    @Test fun `changed revision type or time for same key is a conflict`() {
        val repository = DurableIncidentRepository(MemoryReliabilityStorage(), clock = testClock)
        repository.accept(listOf(incident()))
        for (changed in listOf(incident().copy(sourceRevision = "other"), incident().copy(type = "lifecycle"), incident().copy(timestamp = 123))) {
            assertEquals(ReliabilityError.ID_CONFLICT, assertFailsWith<ReliabilityException> { repository.accept(listOf(changed)) }.code)
        }
    }
}
