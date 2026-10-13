package dev.ide.core.reliability

import dev.ide.agent.FileChange
import dev.ide.agent.IncidentApplyResult
import dev.ide.agent.IncidentIdentity
import dev.ide.agent.IncidentKnownBuild
import dev.ide.agent.IncidentPatchReview
import dev.ide.agent.IncidentSourceSnapshot
import dev.ide.agent.impl.IncidentDigests
import dev.ide.build.ReliabilitySourceSnapshot
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.IOException
import java.net.URI
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.CancellationException
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class IncidentPatchApplierTest {
    @TempDir lateinit var temp: Path

    @Test fun singleClosedFileUsesRealAtomicRenameAndNotifiesExactlyOnce() {
        val fixture = fixture(before = "class Logic { /* 日本語 */ }\r\n", after = "class Logic { /* 修正済み */ }\r\n")
        var currentChecks = 0
        var bufferChecks = 0
        val result = fixture.apply(checkCurrent = { currentChecks++ }, checkBuffers = { inputs, changed ->
            assertEquals(fixture.review.originalInputHashes, inputs)
            assertEquals(setOf(fixture.targetName), changed)
            assertEquals(if (++bufferChecks == 1) 0 else 1, staged(fixture).size)
            null
        })
        assertEquals(IncidentApplyResult.Applied, result)
        assertTrue(currentChecks >= 2)
        assertEquals(2, bufferChecks)
        assertEquals(listOf(listOf(fixture.target)), fixture.notifications)
        fixture.assertProject(fixture.originalBytes + (fixture.targetName to fixture.after.toByteArray(Charsets.UTF_8)))
        assertEquals(fixture.review.repairedSnapshot, canonical(fixture.project))
        assertContentEquals(fixture.after.toByteArray(Charsets.UTF_8), Files.readAllBytes(fixture.copy.resolve(fixture.targetName)))
    }

    @ParameterizedTest @ValueSource(strings = ["java", "kt", "c", "cc", "cpp", "h", "hpp"])
    fun eachSupportedProductionExtensionCanBeApplied(extension: String) {
        val fixture = fixture(targetName = "src/main/Logic.$extension")
        assertEquals(IncidentApplyResult.Applied, fixture.apply())
        assertEquals(fixture.after, Files.readString(fixture.target))
        assertEquals(listOf(listOf(fixture.target)), fixture.notifications)
    }

    @Test fun subscriberFailureCannotMakeACommittedRenamePendingOrRetryIt() {
        val fixture = fixture()
        var moves = 0
        var notifications = 0
        val applier = IncidentPatchApplier(fixture.project, fixture.work, atomicReplace = { staged, target ->
            moves++
            atomicMove(staged, target)
        })
        assertEquals(IncidentApplyResult.Applied, fixture.apply(applier = applier, committed = {
            notifications++
            assertEquals(fixture.after, Files.readString(fixture.target))
            error("event subscriber failed")
        }))
        assertEquals(1, moves)
        assertEquals(1, notifications)
        assertEquals(IncidentApplyResult.StaleSource, fixture.apply(applier = applier))
        assertEquals(1, moves)
        fixture.assertProject(fixture.originalBytes + (fixture.targetName to fixture.after.toByteArray(Charsets.UTF_8)))
    }

    @Test fun cancellationImmediatelyAfterRenamePreservesApplied() {
        val fixture = fixture()
        val job = Job()
        var notifications = 0
        val applier = IncidentPatchApplier(fixture.project, fixture.work, atomicReplace = { staged, target ->
            atomicMove(staged, target)
            job.cancel()
        })
        val result = fixture.apply(applier = applier, checkCurrent = { job.ensureActive() }, committed = {
            notifications++
            job.ensureActive() // A cancelled notification must not undo the commit outcome either.
        })
        assertTrue(job.isCancelled)
        assertEquals(IncidentApplyResult.Applied, result)
        assertEquals(1, notifications)
        fixture.assertProject(fixture.originalBytes + (fixture.targetName to fixture.after.toByteArray(Charsets.UTF_8)))
    }

    @ParameterizedTest @ValueSource(strings = ["unsupported-atomic", "io-failure", "unsupported-provider"])
    fun failedAtomicMoveLeavesEveryOriginalByteUntouchedAndRemovesOnlyItsTemp(failure: String) {
        val fixture = fixture()
        val unrelatedTemp = fixture.target.resolveSibling(".incident-patch-keep.tmp")
        Files.writeString(unrelatedTemp, "not owned by the applier")
        val expected = readTree(fixture.project)
        var moves = 0
        var stagedPath: Path? = null
        val applier = IncidentPatchApplier(fixture.project, fixture.work, atomicReplace = { staged, target ->
            moves++
            stagedPath = staged
            assertEquals(fixture.target, target)
            assertEquals(target.parent, staged.parent)
            assertContentEquals(fixture.after.toByteArray(Charsets.UTF_8), Files.readAllBytes(staged))
            assertEquals(BEFORE, Files.readString(target))
            when (failure) {
                "unsupported-atomic" -> throw AtomicMoveNotSupportedException(staged.toString(), target.toString(), "unsupported")
                "io-failure" -> throw IOException("injected filesystem failure")
                else -> throw UnsupportedOperationException("injected provider failure")
            }
        })
        assertIs<IncidentApplyResult.Pending>(fixture.apply(applier = applier))
        assertEquals(1, moves)
        assertFalse(Files.exists(requireNotNull(stagedPath), NOFOLLOW_LINKS))
        fixture.assertUnchanged(expected)
    }

    @Test fun filesystemWithoutPrivateStagingPermissionsRemainsPending() {
        val archive = temp.toRealPath().resolve("workspace.zip")
        FileSystems.newFileSystem(URI.create("jar:${archive.toUri()}"), mapOf("create" to "true")).use { fs ->
            val fixture = fixture(base = Files.createDirectory(fs.getPath("/workspace")))
            assertIs<IncidentApplyResult.Pending>(fixture.apply())
            fixture.assertUnchanged()
        }
    }

    @ParameterizedTest @ValueSource(strings = ["before-staging", "precommit"])
    fun cancellationPropagatesWithoutACommitAndCleansStaging(point: String) {
        val fixture = fixture()
        val cancelled = CancellationException("cancelled by caller")
        var checks = 0
        val thrown = assertFailsWith<CancellationException> {
            fixture.apply(checkCurrent = {
                checks++
                if (point == "before-staging" || staged(fixture).isNotEmpty()) throw cancelled
            })
        }
        assertSame(cancelled, thrown)
        assertTrue(checks >= if (point == "before-staging") 1 else 2)
        fixture.assertUnchanged()
    }

    @Test fun staleHostExceptionPropagatesAtTheLastCheckWithoutTouchingOriginals() {
        val fixture = fixture()
        val staleHost = IllegalStateException("host was replaced")
        val thrown = assertFailsWith<IllegalStateException> {
            fixture.apply(checkCurrent = { if (staged(fixture).isNotEmpty()) throw staleHost })
        }
        assertSame(staleHost, thrown)
        fixture.assertUnchanged()
    }

    @ParameterizedTest @ValueSource(strings = ["open-before", "open-after", "dirty-before", "dirty-after"])
    fun authoritativeBufferGateCanRefuseBeforeOrAfterStaging(mode: String) {
        val fixture = fixture()
        val refusal = if (mode.startsWith("open")) IncidentApplyResult.Pending("target has an open editor") else IncidentApplyResult.StaleSource
        var calls = 0
        val result = fixture.apply(checkBuffers = { inputs, changed ->
            calls++
            assertEquals(fixture.review.originalInputHashes, inputs)
            assertEquals(setOf(fixture.targetName), changed)
            // Includes non-source immutable inputs, even when it is not the target buffer that is dirty.
            assertTrue(EXTRA in inputs && SCRIPT in inputs && OTHER in inputs)
            if (mode.endsWith("before") || calls == 2) refusal else null
        })
        assertEquals(refusal, result)
        assertEquals(if (mode.endsWith("before")) 1 else 2, calls)
        fixture.assertUnchanged()
    }

    @Test fun bufferGateCannotClaimAppliedOrMutateTheFrozenInputContract() {
        val fixture = fixture()
        assertIs<IncidentApplyResult.Pending>(fixture.apply(checkBuffers = { inputs, changed ->
            assertFailsWith<UnsupportedOperationException> { (inputs as MutableMap<String, String>).clear() }
            assertFailsWith<UnsupportedOperationException> { (changed as MutableSet<String>).clear() }
            IncidentApplyResult.Applied
        }))
        fixture.assertUnchanged()
    }

    @ParameterizedTest @ValueSource(strings = ["target", "other", "test", "config", "extra", "script", "new-source", "delete-source", "rename-source", "delete-extra"])
    fun anyOriginalSourceOrApprovedInputMismatchIsStale(kind: String) {
        val fixture = fixture()
        mutate(fixture.project, fixture.targetName, kind)
        val userBytes = readTree(fixture.project)
        assertEquals(IncidentApplyResult.StaleSource, fixture.apply())
        fixture.assertUnchanged(userBytes)
    }

    @ParameterizedTest @ValueSource(strings = ["revision", "missing-source-input", "source-input-hash", "extra-input-hash", "missing-source-member", "renamed-source-member"])
    fun originalSnapshotAndInputMapMustMatchExactly(kind: String) {
        val fixture = fixture()
        val review = fixture.review
        val altered = when (kind) {
            "revision" -> review.copy(originalSnapshot = review.originalSnapshot.copy(revision = "a".repeat(64)))
            "missing-source-input" -> review.copy(originalInputHashes = review.originalInputHashes - OTHER)
            "source-input-hash" -> review.copy(originalInputHashes = review.originalInputHashes + (OTHER to "a".repeat(64)))
            "extra-input-hash" -> review.copy(originalInputHashes = review.originalInputHashes + (EXTRA to "a".repeat(64)))
            "missing-source-member" -> review.copy(
                originalSnapshot = review.originalSnapshot.copy(files = review.originalSnapshot.files - OTHER),
                repairedSnapshot = review.repairedSnapshot.copy(files = review.repairedSnapshot.files - OTHER),
                originalInputHashes = review.originalInputHashes - OTHER,
            )
            else -> review.copy(
                originalSnapshot = review.originalSnapshot.copy(files = renameKey(review.originalSnapshot.files, OTHER, "src/main/java/Renamed.java")),
                repairedSnapshot = review.repairedSnapshot.copy(files = renameKey(review.repairedSnapshot.files, OTHER, "src/main/java/Renamed.java")),
                originalInputHashes = renameKey(review.originalInputHashes, OTHER, "src/main/java/Renamed.java"),
            )
        }
        assertEquals(IncidentApplyResult.StaleSource, fixture.apply(review = altered))
        fixture.assertUnchanged()
    }

    @ParameterizedTest @ValueSource(strings = ["target", "other", "extra", "new-source"])
    fun allOriginalsAreRecheckedAfterTheSiblingWasStaged(kind: String) {
        val fixture = fixture()
        var modified = false
        var userBytes = fixture.originalBytes
        val applier = IncidentPatchApplier(fixture.project, fixture.work, snapshot = { root ->
            if (root == fixture.project && staged(fixture).isNotEmpty() && !modified) {
                modified = true
                mutate(fixture.project, fixture.targetName, kind)
                userBytes = readTree(fixture.project).filterKeys { !it.substringAfterLast('/').startsWith(".incident-patch-") }
            }
            canonical(root)
        })
        assertEquals(IncidentApplyResult.StaleSource, fixture.apply(applier = applier))
        assertTrue(modified)
        fixture.assertUnchanged(userBytes)
    }

    @ParameterizedTest @ValueSource(strings = ["target", "other", "test", "config", "extra", "script", "new-source", "delete-source", "rename-source", "delete-extra", "extra-file", "extra-test"])
    fun tamperedOrUnreviewedIsolatedInputsCannotBeApplied(kind: String) {
        val fixture = fixture()
        when (kind) {
            "extra-file" -> write(fixture.copy, "assets/unreviewed.json", "not reviewed")
            "extra-test" -> write(fixture.copy, "tests/unreviewed.sh", "exit 0")
            else -> mutate(fixture.copy, fixture.targetName, kind)
        }
        assertIs<IncidentApplyResult.Pending>(fixture.apply())
        fixture.assertUnchanged()
    }

    @ParameterizedTest @ValueSource(strings = ["other", "test", "config", "new-source", "delete-source", "rename-source"])
    fun evenResnapshottedCopyCannotChangeUnlistedFilesOrSourceMembership(kind: String) {
        val fixture = fixture()
        mutate(fixture.copy, fixture.targetName, kind)
        val review = fixture.review.copy(repairedSnapshot = canonical(fixture.copy))
        assertIs<IncidentApplyResult.Pending>(fixture.apply(review = review))
        fixture.assertUnchanged()
    }

    @ParameterizedTest @ValueSource(strings = ["target", "extra", "extra-file"])
    fun isolatedInputsAreAlsoRecheckedAfterStaging(kind: String) {
        val fixture = fixture()
        var mutated = false
        val applier = IncidentPatchApplier(fixture.project, fixture.work, snapshot = { root ->
            if (root == fixture.project && staged(fixture).isNotEmpty() && !mutated) {
                mutated = true
                if (kind == "extra-file") write(fixture.copy, "assets/late.json", "late") else mutate(fixture.copy, fixture.targetName, kind)
            }
            canonical(root)
        })
        assertIs<IncidentApplyResult.Pending>(fixture.apply(applier = applier))
        assertTrue(mutated)
        fixture.assertUnchanged()
    }

    @Test fun ordinaryBuildAndAndroidLeaseOutputsDoNotAuthorizeAdditionalChanges() {
        val fixture = fixture()
        for (dir in listOf("build", "out", "target", "dist", "node_modules", ".gradle", ".cxx", ".externalNativeBuild", ".kotlin",
            ".incident-gradle", ".incident-home", ".incident-tmp")) {
            write(fixture.copy, "$dir/generated/Output.java", "generated output, not an input")
        }
        assertEquals(IncidentApplyResult.Applied, fixture.apply())
        fixture.assertProject(fixture.originalBytes + (fixture.targetName to fixture.after.toByteArray(Charsets.UTF_8)))
    }

    @Test fun anApprovedInputInsideBuildOutputsStillMustMatchItsOriginalHash() {
        val fixture = fixture()
        val name = "build/approved-runner.bin"
        write(fixture.project, name, "frozen runner")
        write(fixture.copy, name, "changed runner")
        val review = fixture.review.copy(originalInputHashes = fixture.review.originalInputHashes + (name to IncidentDigests.file(fixture.project.resolve(name))))
        val expected = readTree(fixture.project)
        assertIs<IncidentApplyResult.Pending>(fixture.apply(review = review))
        fixture.assertUnchanged(expected)
    }

    @ParameterizedTest @ValueSource(strings = ["empty", "multi", "duplicate", "create", "delete", "before", "after", "no-op", "bad-utf8", "repaired-revision", "missing-extra-input"])
    fun malformedOrIncompleteReviewedDiffNeverWritesOriginals(kind: String) {
        val fixture = fixture()
        val change = fixture.review.changes.single()
        val review = when (kind) {
            "empty" -> fixture.review.copy(changes = emptyList())
            "multi" -> fixture.review.copy(changes = listOf(change, FileChange(OTHER, "user dirty source\n", "second patch\n")))
            "duplicate" -> fixture.review.copy(changes = listOf(change, change))
            "create" -> fixture.review.copy(changes = listOf(change.copy(before = null)))
            "delete" -> fixture.review.copy(changes = listOf(change.copy(after = null)))
            "before" -> fixture.review.copy(changes = listOf(change.copy(before = "forged original\n")))
            "after" -> fixture.review.copy(changes = listOf(change.copy(after = "unverified patch\n")))
            "no-op" -> fixture.review.copy(changes = listOf(change.copy(after = change.before)))
            "bad-utf8" -> fixture.review.copy(changes = listOf(change.copy(after = "\uD800")))
            "repaired-revision" -> fixture.review.copy(repairedSnapshot = fixture.review.repairedSnapshot.copy(revision = "a".repeat(64)))
            else -> fixture.review.copy(originalInputHashes = fixture.review.originalInputHashes - EXTRA)
        }
        assertTrue(fixture.apply(review = review) != IncidentApplyResult.Applied)
        fixture.assertUnchanged()
    }

    @ParameterizedTest @ValueSource(strings = ["src/test/Logic.java", "src/androidTest/Logic.java", "src/commonTest/Logic.kt", "tests/Logic.cpp",
        "testFixtures/Logic.kt", "reproducer/Logic.java", "invariants/Logic.java", "generated/Logic.java", "build/Logic.java", "out/Logic.cpp",
        "buildSrc/Logic.kt", "build-logic/Logic.kt", "src/main/LogicTest.java", "src/main/LogicSpec.kt", "src/main/BuildConfig.java",
        "src/main/AndroidManifest.xml", "src/main/not-source.txt", "src/secrets/Logic.java", "src/credentials/Logic.kt", ".hidden/Logic.java"])
    fun onlyProductionSourcesAreEditable(path: String) {
        val fixture = fixture(targetName = path)
        assertIs<IncidentApplyResult.Pending>(fixture.apply())
        fixture.assertUnchanged()
    }

    @ParameterizedTest @ValueSource(strings = ["../Logic.java", "src/../Logic.java", "src/./Logic.java", "/Logic.java", "src\\Logic.java",
        "src//Logic.java", "src/Logic.java/", "C:/Logic.java", "src/\u0000Logic.java"])
    fun everyReviewPathMustBeStrictlyRelativeAndCanonical(path: String) {
        val fixture = fixture()
        val review = fixture.review.copy(
            originalSnapshot = fixture.review.originalSnapshot.copy(files = renameKey(fixture.review.originalSnapshot.files, fixture.targetName, path)),
            repairedSnapshot = fixture.review.repairedSnapshot.copy(files = renameKey(fixture.review.repairedSnapshot.files, fixture.targetName, path)),
            originalInputHashes = renameKey(fixture.review.originalInputHashes, fixture.targetName, path),
            changes = listOf(fixture.review.changes.single().copy(path = path)),
        )
        assertIs<IncidentApplyResult.Pending>(fixture.apply(review = review))
        fixture.assertUnchanged()
    }

    @ParameterizedTest @ValueSource(strings = ["original-target", "original-parent", "original-extra", "copy-target", "copy-parent", "copy-extra", "copy-unreviewed"])
    fun symlinkFilesAndParentComponentsAreNeverFollowed(kind: String) {
        val fixture = fixture()
        val root = if (kind.startsWith("original")) fixture.project else fixture.copy
        val outside = Files.createTempDirectory(temp.toRealPath(), "outside-")
        when {
            kind.endsWith("parent") -> {
                val parent = root.resolve(fixture.targetName).parent
                val relocated = outside.resolve("sources")
                Files.move(parent, relocated)
                Files.createSymbolicLink(parent, relocated)
            }
            kind.endsWith("unreviewed") -> Files.createSymbolicLink(root.resolve("extra-link"), fixture.target)
            else -> {
                val path = root.resolve(if (kind.endsWith("extra")) EXTRA else fixture.targetName)
                val relocated = outside.resolve(path.fileName)
                Files.move(path, relocated)
                Files.createSymbolicLink(path, relocated)
            }
        }
        val expected = readTree(fixture.project)
        val external = readTree(outside)
        val result = fixture.apply()
        if (kind.startsWith("original")) assertEquals(IncidentApplyResult.StaleSource, result) else assertIs<IncidentApplyResult.Pending>(result)
        fixture.assertUnchanged(expected)
        assertTree(external, outside)
    }

    @ParameterizedTest @ValueSource(strings = ["copy-linked", "work-linked", "project-linked", "outside-work", "work-itself", "under-project", "noncanonical"])
    fun rootsMustBeCanonicalPrivateAndDisjoint(kind: String) {
        val fixture = fixture()
        var project = fixture.project
        var work = fixture.work
        var isolated = fixture.copy
        when (kind) {
            "copy-linked" -> isolated = Files.createSymbolicLink(work.resolve("linked-copy"), fixture.copy)
            "work-linked" -> work = Files.createSymbolicLink(work.resolveSibling("linked-work"), work)
            "project-linked" -> project = Files.createSymbolicLink(project.resolveSibling("linked-project"), project)
            "outside-work" -> isolated = Files.createDirectory(fixture.work.resolveSibling("outside"))
            "work-itself" -> isolated = fixture.work
            "under-project" -> {
                work = Files.createDirectory(project.resolve("private"))
                isolated = Files.createDirectory(work.resolve("copy"))
            }
            else -> isolated = isolated.resolve("..").resolve(isolated.fileName)
        }
        val expected = readTree(fixture.project)
        val result = fixture.apply(review = fixture.review.copy(isolatedRoot = isolated), applier = IncidentPatchApplier(project, work))
        if (kind == "project-linked") assertEquals(IncidentApplyResult.StaleSource, result) else assertIs<IncidentApplyResult.Pending>(result)
        fixture.assertUnchanged(expected)
    }

    @Test fun permissionsAreRetainedEvenForAReadOnlySource() {
        val fixture = fixture()
        assumeTrue(Files.getFileAttributeView(fixture.target, PosixFileAttributeView::class.java) != null)
        val permissions = PosixFilePermissions.fromString("r--r-----")
        Files.setPosixFilePermissions(fixture.target, permissions)
        val applier = IncidentPatchApplier(fixture.project, fixture.work, atomicReplace = { staged, target ->
            assertEquals(permissions, Files.getPosixFilePermissions(staged))
            atomicMove(staged, target)
        })
        assertEquals(IncidentApplyResult.Applied, fixture.apply(applier = applier, checkBuffers = { _, _ ->
            staged(fixture).forEach { assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(it)) }
            null
        }))
        assertEquals(permissions, Files.getPosixFilePermissions(fixture.target))
        assertEquals(fixture.after, Files.readString(fixture.target))
    }

    @Test fun alteredStagedBytesAreRefusedBeforeTheAtomicMove() {
        val fixture = fixture()
        assertIs<IncidentApplyResult.Pending>(fixture.apply(checkBuffers = { _, _ ->
            staged(fixture).forEach { Files.writeString(it, "not the reviewed patch") }
            null
        }))
        fixture.assertUnchanged()
    }

    @Test fun cancellationIsCheckedAgainImmediatelyBeforeCreatingASibling() {
        val fixture = fixture()
        var revoked = false
        val cancelled = CancellationException("revoked during initial validation")
        assertSame(cancelled, assertFailsWith<CancellationException> {
            fixture.apply(checkCurrent = { if (revoked) throw cancelled }, checkBuffers = { _, _ ->
                revoked = true
                null
            })
        })
        fixture.assertUnchanged()
    }

    @Test fun unreviewedCopyBytesCannotHideBehindAFileCountOrSizeOverflow() {
        val fixture = fixture()
        val excessive = fixture.review.originalInputHashes + (1..20_001).associate { "inputs/$it.txt" to "a".repeat(64) }
        assertIs<IncidentApplyResult.Pending>(fixture.apply(review = fixture.review.copy(originalInputHashes = excessive)))
        fixture.assertUnchanged()
        val oversized = ByteArray(8 * 1024 * 1024 + 1)
        Files.write(fixture.copy.resolve(EXTRA), oversized)
        assertIs<IncidentApplyResult.Pending>(fixture.apply())
        fixture.assertUnchanged()
    }

    @Test fun aggregateInputLimitIsEnforcedBeforeStaging() {
        val fixture = fixture()
        val bytes = ByteArray(8 * 1024 * 1024)
        val hash = IncidentDigests.bytes(bytes)
        val extras = (1..8).associate { index ->
            val name = "assets/large-$index.bin"
            Files.write(fixture.project.resolve(name), bytes)
            Files.write(fixture.copy.resolve(name), bytes)
            name to hash
        }
        // Eight 8 MiB inputs plus even the small source/test inputs exceed the 64 MiB bound.
        val result = fixture.apply(review = fixture.review.copy(originalInputHashes = fixture.review.originalInputHashes + extras))
        assertTrue(result != IncidentApplyResult.Applied)
        assertTrue(staged(fixture).isEmpty())
        assertTrue(fixture.notifications.isEmpty())
        assertEquals(BEFORE, Files.readString(fixture.target))
        extras.forEach { (name, digest) -> assertEquals(digest, IncidentDigests.file(fixture.project.resolve(name))) }
    }

    @Test fun targetLimitCountsUtf8BytesRatherThanCharacters() {
        val fixture = fixture(after = "é".repeat(300_000))
        assertIs<IncidentApplyResult.Pending>(fixture.apply())
        fixture.assertUnchanged()
    }

    private fun fixture(targetName: String = TARGET, before: String = BEFORE, after: String = AFTER, base: Path? = null): Fixture {
        val container = base ?: Files.createTempDirectory(temp.toRealPath(), "apply-")
        val project = Files.createDirectory(container.resolve("project"))
        val work = Files.createDirectory(container.resolve("private"))
        val copy = Files.createDirectory(work.resolve("incident-reviewed"))
        val inputs = linkedMapOf(targetName to before, OTHER to "user dirty source\n", TEST to "immutable test source\n",
            CONFIG to "immutable build configuration\n", EXTRA to "{\"immutable\":true}\n", SCRIPT to "#!/bin/sh\nexit 1\n")
        inputs.forEach { (name, content) -> write(project, name, content); write(copy, name, content) }
        write(project, "notes.txt", "unrelated original file not copied or edited\n")
        val original = canonical(project)
        val hashes = inputs.keys.associateWith { IncidentDigests.bytes(Files.readAllBytes(project.resolve(it))) }
        write(copy, targetName, after)
        val build = IncidentKnownBuild("project", "fixture.app", "build-1", "debug", original.revision, "source-uuid", "b".repeat(64), true)
        val review = IncidentPatchReview("run-1", IncidentIdentity("fixture.app", "build-1", "session", "event"), build, original,
            canonical(copy), copy, listOf(FileChange(targetName, before, after)), emptyList(), hashes)
        return Fixture(project, work, copy, targetName, after, review, readTree(project))
    }

    private inner class Fixture(
        val project: Path,
        val work: Path,
        val copy: Path,
        val targetName: String,
        val after: String,
        val review: IncidentPatchReview,
        val originalBytes: Map<String, ByteArray>,
    ) {
        val target: Path get() = project.resolve(targetName)
        val notifications = mutableListOf<List<Path>>()
        fun apply(
            review: IncidentPatchReview = this.review,
            applier: IncidentPatchApplier = IncidentPatchApplier(project, work),
            checkCurrent: () -> Unit = {},
            checkBuffers: (Map<String, String>, Set<String>) -> IncidentApplyResult? = { _, _ -> null },
            committed: (List<Path>) -> Unit = { notifications.add(it) },
        ): IncidentApplyResult = applier.apply(review, checkCurrent, checkBuffers, committed)
        fun assertUnchanged(expected: Map<String, ByteArray> = originalBytes) {
            assertProject(expected)
            assertTrue(notifications.isEmpty())
        }
        fun assertProject(expected: Map<String, ByteArray>) = assertTree(expected, project)
    }

    private fun mutate(root: Path, target: String, kind: String) {
        when (kind) {
            "target" -> write(root, target, "user or unverified target change\n")
            "other" -> write(root, OTHER, "unrelated source edit\n")
            "test" -> write(root, TEST, "disabled test\n")
            "config" -> write(root, CONFIG, "changed build configuration\n")
            "extra" -> write(root, EXTRA, "changed binary/resource input\n")
            "script" -> write(root, SCRIPT, "exit 0\n")
            "new-source" -> write(root, "src/main/java/Unexpected.kt", "new source\n")
            "delete-source" -> Files.delete(root.resolve(OTHER))
            "rename-source" -> Files.move(root.resolve(OTHER), root.resolve(OTHER).resolveSibling("Renamed.java"))
            "delete-extra" -> Files.delete(root.resolve(EXTRA))
            else -> error("Unknown test mutation")
        }
    }

    private fun canonical(root: Path): IncidentSourceSnapshot {
        val source = ReliabilitySourceSnapshot.capture(root)
        return IncidentSourceSnapshot(source.revision, source.privateRelativeFiles.toMap())
    }
    private fun write(root: Path, name: String, text: String) {
        val target = root.resolve(name)
        Files.createDirectories(target.parent)
        Files.write(target, text.toByteArray(Charsets.UTF_8))
    }
    private fun atomicMove(staged: Path, target: Path) {
        Files.move(staged, target, ATOMIC_MOVE, REPLACE_EXISTING)
    }
    private fun staged(fixture: Fixture): List<Path> = Files.list(fixture.target.parent).use { paths ->
        paths.filter { it.fileName.toString().startsWith(".incident-patch-") }.toList()
    }
    private fun readTree(root: Path): Map<String, ByteArray> = Files.walk(root).use { paths ->
        paths.filter { !Files.isDirectory(it, NOFOLLOW_LINKS) }.toList().associate { path ->
            root.relativize(path).joinToString("/") to if (Files.isSymbolicLink(path))
                "link:${Files.readSymbolicLink(path)}".toByteArray(Charsets.UTF_8) else Files.readAllBytes(path)
        }
    }
    private fun assertTree(expected: Map<String, ByteArray>, root: Path) {
        val actual = readTree(root)
        assertEquals(expected.keys, actual.keys)
        expected.forEach { (name, bytes) -> assertContentEquals(bytes, actual.getValue(name), name) }
    }
    private fun renameKey(map: Map<String, String>, old: String, new: String): Map<String, String> =
        (map - old) + (new to map.getValue(old))

    private companion object {
        const val TARGET = "src/main/java/Logic.java"
        const val OTHER = "src/main/java/User.java"
        const val TEST = "src/test/java/BehaviorTest.java"
        const val CONFIG = "build.gradle.kts"
        const val EXTRA = "assets/input.json"
        const val SCRIPT = "tests/repro.sh"
        const val BEFORE = "class Logic { int value() { return 0; } }\n"
        const val AFTER = "class Logic { int value() { return 1; } }\n"
    }
}
