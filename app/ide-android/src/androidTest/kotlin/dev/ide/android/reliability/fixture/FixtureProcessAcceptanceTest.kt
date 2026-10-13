package dev.ide.android.reliability.fixture

import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Ordinary IDE androidTest instrumentation controls ONLY the separately installed, test-only fixture.
 * The target app is not instrumented while it is killed/crashed. No same-UID fake crash-handler proof,
 * exported command service, production kill control, shell identity adoption, or arbitrary run-as.
 */
@RunWith(AndroidJUnit4::class)
class FixtureProcessAcceptanceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation

    @Test
    fun draftCheckpointSurvivesForceStopAndRelaunch() {
        requireAuthorizedFixture()
        try {
            val initial = launch()
            click("Seed disposable user data")
            editDraft(DRAFT)
            act({ it.contentDescription?.toString() == "Fixture draft" }) {
                it.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 8)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, 12)
                })
            }.also { assertTrue("Fixture editor must accept a real selection action", it) }
            click("Select second record")
            val previousCheckpoint = readCheckpoint()?.optLong("checkpointSequence") ?: -1
            click("Checkpoint disk state")
            val before = checkpoint(initial.getInt("pid"), previousCheckpoint)
            assertEquals(2, before.getInt("recordCount"))
            val persistedIds = events(before).map { it.getString("eventId") }.toSet()
            assertTrue("Real SDK lifecycle events must be durable before kill", persistedIds.isNotEmpty())

            shell(STOP)
            await("fixture process stopped") { shell(PID).isBlank() }
            val after = launch()
            assertNotEquals("Must observe a genuinely different process", before.getInt("pid"), after.getInt("pid"))
            assertEquals("Persisted checkpoint lost draft after process death", DRAFT, after.getString("body"))
            assertEquals(8, after.getInt("selectionStart"))
            assertEquals(12, after.getInt("selectionEnd"))
            assertEquals(2L, after.getLong("selectedId"))
            assertEquals("Saved Helsinki note", after.getString("savedRecord"))
            assertTrue("SQLite user records, attachments and settings must survive", after.getBoolean("seedUserData"))
            val retainedIds = events(after).map { it.getString("eventId") }.toSet()
            assertTrue("Kill/relaunch must retain original SDK event IDs", retainedIds.containsAll(persistedIds))
            assertTrue("Actual relaunched editor must render its recovered draft and selection",
                act({ it.contentDescription?.toString() == "Fixture draft" }) {
                    it.text?.toString() == DRAFT && it.textSelectionStart == 8 && it.textSelectionEnd == 12
                })
        } finally { shell(STOP) }
    }

    @Test
    fun uncaughtCrashInSeparateUidIsJournaledBeforeAndroidTerminatesProcess() {
        requireAuthorizedFixture()
        try {
            val before = launch()
            val oldFatalIds = events(before).filter { it.getString("type") == "fatal_java" }
                .map { it.getString("eventId") }.toSet()
            val oldSessions = events(before).map { it.getString("sessionId") }.toSet()
            click("Crash fixture process")
            // Observe natural Android termination BEFORE force-stop/cleanup. A caught exception or
            // recordException() call cannot satisfy this check.
            await("uncaught exception must terminate fixture PID", 20_000) { shell(PID).isBlank() }
            shell(STOP)
            val after = launch()
            assertNotEquals(before.getInt("pid"), after.getInt("pid"))
            val crash = events(after).singleOrNull {
                it.getString("type") == "fatal_java" && it.getString("eventId") !in oldFatalIds
            } ?: throw AssertionError("Real uncaught crash was not recovered from durable SDK journal")
            assertEquals(PACKAGE, crash.getString("packageName"))
            assertTrue("Crash must retain the dead process session, not be fabricated on relaunch",
                crash.getString("sessionId") in oldSessions)
            assertTrue(crash.getString("redactedText"), crash.getString("redactedText").contains("FixtureActivity"))
            assertTrue(crash.getString("redactedText"), crash.getString("redactedText").contains("IllegalStateException"))
            assertNotEquals(Process.myUid(), after.getInt("uid"))
        } finally { shell(STOP) }
    }

    private fun requireAuthorizedFixture() {
        assumeTrue("Explicit fixture authorization required; destructive actions never target a caller-supplied package",
            InstrumentationRegistry.getArguments().getString("authorizedFixture") == PACKAGE)
        val listing = shell(PACKAGES)
        val uid = Regex("package:${Regex.escape(PACKAGE)}\\s+uid:(\\d+)")
            .find(listing)?.groupValues?.get(1)?.toInt()
            ?: throw AssertionError("Install the authorized fixture APK first")
        assertNotEquals("Fixture must have a Linux UID distinct from IDE instrumentation", Process.myUid(), uid)
        val metadata = shell(METADATA)
        assertTrue("Only the debuggable fixture is authorized", metadata.contains("DEBUGGABLE"))
        assertTrue("Only a test-only APK is authorized", metadata.contains("TEST_ONLY"))
    }

    private fun launch(): JSONObject {
        val output = shell(START)
        assertFalse("Fixture launch failed: $output", output.contains("Error:"))
        var pid = 0
        await("fixture starts") { shell(PID).trim().toIntOrNull()?.also { pid = it } != null }
        await("fixture UI appears") {
            act({ it.packageName?.toString() == PACKAGE && it.contentDescription?.toString() == "Fixture draft" }) { true }
        }
        // onCreate checkpoint precedes onActivityResumed; take an explicit post-resume durable snapshot.
        val previous = readCheckpoint()
        val sequence = if (previous?.optInt("pid") == pid) previous.optLong("checkpointSequence") else -1
        click("Inspect durable SDK queue")
        return checkpoint(pid, sequence)
    }

    private fun readCheckpoint(): JSONObject? = runCatching { JSONObject(shell(CHECKPOINT)) }.getOrNull()

    private fun checkpoint(pid: Int, afterSequence: Long): JSONObject {
        var result: JSONObject? = null
        await("fresh fixture disk checkpoint") {
            result = readCheckpoint()
            result?.optInt("pid") == pid && (result?.optLong("checkpointSequence") ?: -1) > afterSequence
        }
        return requireNotNull(result)
    }

    private fun editDraft(text: String) {
        assertTrue("Fixture editor must accept actual accessibility text input",
            act({ it.contentDescription?.toString() == "Fixture draft" }) {
                it.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                })
            })
    }

    private fun click(label: String) {
        fun attempt(): Boolean = act({ it.text?.toString() == label && it.isVisibleToUser && it.isEnabled }) {
            it.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        // Inspection may leave the lower controls visible; a subsequent seed operation is above them.
        // Search both directions within the fixture's own ScrollView, including when the keyboard is up.
        for (index in 0 until 10) {
            if (attempt()) return
            if (!act({ it.isScrollable && it.isVisibleToUser }) {
                    it.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                }) break
            SystemClock.sleep(100)
        }
        repeat(16) {
            if (attempt()) return
            act({ it.isScrollable && it.isVisibleToUser }) { it.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) }
            SystemClock.sleep(100)
        }
        throw AssertionError("Cannot click fixture-only control '$label'")
    }

    private fun act(match: (AccessibilityNodeInfo) -> Boolean, action: (AccessibilityNodeInfo) -> Boolean): Boolean {
        val root = automation.rootInActiveWindow ?: return false
        fun visit(node: AccessibilityNodeInfo): Boolean {
            if (node.packageName?.toString() == PACKAGE && match(node) && action(node)) return true
            for (index in 0 until node.childCount) {
                val child = node.getChild(index) ?: continue
                try { if (visit(child)) return true } finally { child.recycle() }
            }
            return false
        }
        return try { visit(root) } finally { root.recycle() }
    }

    private fun events(checkpoint: JSONObject): List<JSONObject> {
        val array = checkpoint.getJSONArray("pending")
        return (0 until array.length()).map(array::getJSONObject)
    }

    private fun await(label: String, timeout: Long = 12_000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        do {
            if (condition()) return
            SystemClock.sleep(100)
        } while (SystemClock.elapsedRealtime() < deadline)
        throw AssertionError("Timed out: $label")
    }

    private fun shell(command: String): String {
        check(command in COMMANDS) { "Only hard-coded fixture commands are authorized" }
        return ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command))
            .bufferedReader().use { reader ->
                val result = StringBuilder()
                val buffer = CharArray(4096)
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    check(result.length + count <= 512 * 1024) { "Fixture shell response exceeds limit" }
                    result.append(buffer, 0, count)
                }
                result.toString()
            }
    }

    private companion object {
        const val PACKAGE = "dev.ide.reliability.fixture"
        const val DRAFT = "Unsaved Oslo draft - process recovery"
        const val START = "am start -W -n $PACKAGE/.FixtureActivity"
        const val STOP = "am force-stop $PACKAGE"
        const val PID = "pidof $PACKAGE"
        const val PACKAGES = "pm list packages -U $PACKAGE"
        const val METADATA = "dumpsys package $PACKAGE"
        const val CHECKPOINT = "run-as $PACKAGE cat files/fixture-checkpoint.json"
        val COMMANDS = setOf(START, STOP, PID, PACKAGES, METADATA, CHECKPOINT)
    }
}
