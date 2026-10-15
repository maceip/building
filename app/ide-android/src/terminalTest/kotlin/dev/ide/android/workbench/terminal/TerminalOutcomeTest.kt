package dev.ide.android.workbench.terminal

import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Modifier
import java.nio.file.Paths
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Plain JVM contract tests for delivery state, NOT a substitute for real PTY/SDK integration. */
class TerminalOutcomeTest {
    private class RecordingObserver : TerminalOutcomeObserver {
        val calls = mutableListOf<String>()
        val identities = mutableListOf<TerminalRuntimeIdentity>()
        val starts = mutableListOf<TerminalSessionStart>()
        val outcomes = mutableListOf<TerminalOutcome>()
        override fun onRuntimePrepared(identity: TerminalRuntimeIdentity) {
            calls += "prepared"
            identities += identity
        }
        override fun onStart(start: TerminalSessionStart) {
            calls += "start"
            starts += start
        }
        override fun onTerminal(outcome: TerminalOutcome) {
            calls += "terminal"
            outcomes += outcome
        }
    }

    private fun context(kind: TerminalProgramKind = TerminalProgramKind.SHELL) = TerminalSessionContext(
        sessionId = UUID.randomUUID().toString(),
        programKind = kind,
        workingDirectory = Paths.get("/host-private/user-selected-project"),
        startedAtEpochMs = 123456789L,
    )

    private fun delivery(
        observer: TerminalOutcomeObserver,
        context: TerminalSessionContext = context(),
        startedAtNanos: Long = System.nanoTime() - 25_000_000L,
    ) = TerminalOutcomeDelivery(context, TerminalOutcomeObserverFactory { observer }, startedAtNanos)

    @Test fun ownsOneProcessAndUsesOneIdentityStartAndTerminalCallback() {
        val observer = RecordingObserver()
        val context = context(TerminalProgramKind.NODE)
        val delivery = delivery(observer, context)
        val identity = TerminalRuntimeIdentity("runtime-pack-test", "arm64-v8a")
        delivery.runtimePrepared(identity)
        delivery.runtimePrepared(TerminalRuntimeIdentity("must-not-replace", "ignored"))
        delivery.launching()
        delivery.processStarted()
        delivery.processStarted()
        delivery.processExited(0)
        delivery.processExited(91)
        delivery.finish()
        delivery.finish()
        delivery.processStarted()
        delivery.runtimePrepared(identity)
        delivery.cancelled()
        delivery.failed()

        assertEquals(listOf("prepared", "start", "terminal"), observer.calls)
        assertEquals(listOf(identity), observer.identities)
        assertEquals(TerminalSessionStart(context.sessionId, TerminalProgramKind.NODE, context.startedAtEpochMs), observer.starts.single())
        val outcome = observer.outcomes.single()
        assertEquals(context.sessionId, outcome.sessionId)
        assertEquals(TerminalProgramKind.NODE, outcome.programKind)
        assertEquals(context.startedAtEpochMs, outcome.startedAtEpochMs)
        assertTrue(outcome.durationMs >= 25L)
        assertEquals(0, outcome.exitCode)
        assertEquals(TerminalOutcomeClassification.SUCCESS, outcome.classification)
    }

    @Test fun preparationFailureHasNoProcessStartOrInventedExit() {
        val observer = RecordingObserver()
        val delivery = delivery(observer)
        delivery.failed()
        delivery.finish()
        delivery.finish()
        assertEquals(listOf("terminal"), observer.calls)
        assertEquals(TerminalOutcomeClassification.PREPARE_FAILURE, observer.outcomes.single().classification)
        assertNull(observer.outcomes.single().exitCode)
    }

    @Test fun launchFailureIsDistinctFromPreparedRuntimeAndStartedProcess() {
        val observer = RecordingObserver()
        val delivery = delivery(observer)
        delivery.runtimePrepared(TerminalRuntimeIdentity("pack", "abi"))
        delivery.launching()
        delivery.failed()
        delivery.finish()
        assertEquals(listOf("prepared", "terminal"), observer.calls)
        assertEquals(TerminalOutcomeClassification.LAUNCH_FAILURE, observer.outcomes.single().classification)
        assertNull(observer.outcomes.single().exitCode)
    }

    @Test fun sessionInfrastructureFailureDoesNotInventAProcessExit() {
        val observer = RecordingObserver()
        val delivery = delivery(observer)
        delivery.processStarted()
        delivery.failed()
        delivery.finish()
        assertEquals(TerminalOutcomeClassification.SESSION_FAILURE, observer.outcomes.single().classification)
        assertNull(observer.outcomes.single().exitCode)
    }

    @Test fun nonzeroIncludingInterruptedExitIsAnOutcomeNotCrashAttribution() {
        for (code in listOf(1, 7, 130, 137, 143, 255)) {
            val observer = RecordingObserver()
            val delivery = delivery(observer)
            delivery.processStarted()
            delivery.processExited(code)
            delivery.failed() // A later close failure cannot rewrite an observed process result.
            delivery.cancelled() // Nor can disposing an already finished terminal.
            delivery.finish()
            assertEquals(code, observer.outcomes.single().exitCode)
            assertEquals(TerminalOutcomeClassification.NONZERO_EXIT, observer.outcomes.single().classification)
        }
    }

    @Test fun successfulExitIsNotRewrittenByLateStopOrCleanupFailure() {
        val observer = RecordingObserver()
        val delivery = delivery(observer)
        delivery.processStarted()
        delivery.processExited(0)
        delivery.cancelled()
        delivery.failed()
        delivery.finish()
        assertEquals(TerminalOutcomeClassification.SUCCESS, observer.outcomes.single().classification)
    }

    @Test fun cancellationBeforeProcessStartProducesOnlyOneCancellationOutcome() {
        val observer = RecordingObserver()
        val delivery = delivery(observer)
        delivery.cancelled()
        delivery.cancelled()
        delivery.failed()
        delivery.finish()
        delivery.finish()
        assertEquals(listOf("terminal"), observer.calls)
        assertEquals(TerminalOutcomeClassification.USER_CANCELLED, observer.outcomes.single().classification)
        assertNull(observer.outcomes.single().exitCode)
    }

    @Test fun stopAndForcedExitStayCancelledEvenWhenCleanupFails() {
        val observer = RecordingObserver()
        val delivery = delivery(observer)
        delivery.processStarted()
        delivery.cancelled()
        delivery.processExited(137)
        delivery.failed()
        delivery.finish()
        assertEquals(137, observer.outcomes.single().exitCode)
        assertEquals(TerminalOutcomeClassification.USER_CANCELLED, observer.outcomes.single().classification)
    }

    @Test fun factoryFailureIsIgnoredWithoutChangingDeliveryState() {
        val calls = AtomicInteger()
        val delivery = TerminalOutcomeDelivery(context(), TerminalOutcomeObserverFactory {
            calls.incrementAndGet()
            throw IllegalStateException("private factory exception /secret token=value")
        }, System.nanoTime())
        delivery.runtimePrepared(TerminalRuntimeIdentity("pack", "abi"))
        delivery.launching()
        delivery.processStarted()
        delivery.processExited(0)
        delivery.finish()
        delivery.finish()
        assertEquals(1, calls.get())
    }

    @Test fun everyCallbackMayThrowIncludingCancellationWithoutChangingVerdictOrRetrying() {
        val identities = AtomicInteger()
        val starts = AtomicInteger()
        val terminals = mutableListOf<TerminalOutcome>()
        val observer = object : TerminalOutcomeObserver {
            override fun onRuntimePrepared(identity: TerminalRuntimeIdentity) {
                identities.incrementAndGet()
                throw IllegalStateException("private identity exception")
            }
            override fun onStart(start: TerminalSessionStart) {
                starts.incrementAndGet()
                throw java.util.concurrent.CancellationException("observer cancellation is not terminal cancellation")
            }
            override fun onTerminal(outcome: TerminalOutcome) {
                terminals += outcome
                throw AssertionError("private delivery failure")
            }
        }
        val delivery = delivery(observer)
        repeat(2) { delivery.runtimePrepared(TerminalRuntimeIdentity("pack", "abi")) }
        repeat(2) { delivery.processStarted() }
        delivery.processExited(0)
        repeat(2) { delivery.finish() }
        assertEquals(1, identities.get())
        assertEquals(1, starts.get())
        assertEquals(TerminalOutcomeClassification.SUCCESS, terminals.single().classification)
    }

    @Test fun privateWorkingDirectoryIsOnlyAvailableToFactoryAndPayloadSchemaIsFixed() {
        val observer = RecordingObserver()
        val context = context()
        var factoryCalls = 0
        val delivery = TerminalOutcomeDelivery(context, TerminalOutcomeObserverFactory { privateContext ->
            factoryCalls++
            assertSame(context, privateContext)
            assertEquals(Paths.get("/host-private/user-selected-project"), privateContext.workingDirectory)
            observer
        }, System.nanoTime())
        delivery.processStarted()
        delivery.failed()
        delivery.finish()

        fun fields(type: Class<*>): Set<String> = type.declaredFields
            .filterNot { it.isSynthetic || Modifier.isStatic(it.modifiers) }
            .map { it.name }.toSet()
        assertEquals(setOf("packId", "abi"), fields(TerminalRuntimeIdentity::class.java))
        assertEquals(setOf("sessionId", "programKind", "startedAtEpochMs"), fields(TerminalSessionStart::class.java))
        assertEquals(setOf("sessionId", "programKind", "startedAtEpochMs", "durationMs", "exitCode", "classification"), fields(TerminalOutcome::class.java))
        assertEquals(1, factoryCalls)
        assertFalse(context.toString().contains("user-selected-project"))
        assertFalse(observer.starts.single().toString().contains("host-private"))
        assertFalse(observer.outcomes.single().toString().contains("host-private"))
        assertTrue(TerminalOutcomeDelivery::class.java.declaredFields.none { it.type == TerminalSessionContext::class.java || Throwable::class.java.isAssignableFrom(it.type) })
    }

    @Test fun concurrentDuplicateCallbacksAreSerializedAndTerminalDeliveryIsOnce() {
        val events = Collections.synchronizedList(mutableListOf<String>())
        val outcomes = Collections.synchronizedList(mutableListOf<TerminalOutcome>())
        val delivery = delivery(object : TerminalOutcomeObserver {
            override fun onRuntimePrepared(identity: TerminalRuntimeIdentity) { events += "prepared" }
            override fun onStart(start: TerminalSessionStart) { events += "start" }
            override fun onTerminal(outcome: TerminalOutcome) { events += "terminal"; outcomes += outcome }
        })
        val threads = Executors.newFixedThreadPool(6)
        try {
            val gate = CountDownLatch(1)
            val tasks = (0 until 24).map {
                threads.submit {
                    check(gate.await(5, TimeUnit.SECONDS))
                    delivery.runtimePrepared(TerminalRuntimeIdentity("pack", "abi"))
                    delivery.processStarted()
                }
            }
            gate.countDown()
            tasks.forEach { it.get(5, TimeUnit.SECONDS) }
            delivery.processExited(0)
            val terminalTasks = (0 until 24).map { threads.submit { delivery.cancelled(); delivery.failed(); delivery.finish() } }
            terminalTasks.forEach { it.get(5, TimeUnit.SECONDS) }
            assertEquals(listOf("prepared", "start", "terminal"), events.toList())
            assertEquals(TerminalOutcomeClassification.SUCCESS, outcomes.single().classification)
        } finally {
            threads.shutdownNow()
        }
    }

    @Test fun reentrantTerminalDeliveryIsLatchedBeforeExternalCallback() {
        val outcomes = mutableListOf<TerminalOutcome>()
        lateinit var delivery: TerminalOutcomeDelivery
        delivery = delivery(object : TerminalOutcomeObserver {
            override fun onTerminal(outcome: TerminalOutcome) {
                outcomes += outcome
                delivery.finish()
                delivery.cancelled()
                delivery.failed()
            }
        })
        delivery.processStarted()
        delivery.processExited(0)
        delivery.finish()
        assertEquals(TerminalOutcomeClassification.SUCCESS, outcomes.single().classification)
    }

    @Test fun durationIsCappedAtSevenDaysForHostPayloadValidation() {
        val observer = RecordingObserver()
        val eightDaysNanos = 8L * 24 * 60 * 60 * 1_000_000_000L
        val delivery = delivery(observer, startedAtNanos = System.nanoTime() - eightDaysNanos)
        delivery.processStarted()
        delivery.processExited(0)
        delivery.finish()
        assertEquals(7L * 24 * 60 * 60 * 1000, observer.outcomes.single().durationMs)
    }

    @Test fun defaultFactoryIsInertAndNegativeDurationIsClamped() {
        val observer = RecordingObserver()
        val delivery = delivery(observer, startedAtNanos = System.nanoTime() + 10_000_000_000L)
        delivery.cancelled()
        delivery.finish()
        assertEquals(0L, observer.outcomes.single().durationMs)
        assertSame(TerminalOutcomeObserver.NONE, TerminalOutcomeObserverFactory.NONE.create(context()))
    }
}
