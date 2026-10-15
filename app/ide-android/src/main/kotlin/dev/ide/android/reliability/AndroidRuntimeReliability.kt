package dev.ide.android.reliability

import android.content.Context
import dev.ide.android.workbench.terminal.TerminalOutcome
import dev.ide.android.workbench.terminal.TerminalOutcomeClassification
import dev.ide.android.workbench.terminal.TerminalOutcomeObserver
import dev.ide.android.workbench.terminal.TerminalOutcomeObserverFactory
import dev.ide.android.workbench.terminal.TerminalProgramKind
import dev.ide.android.workbench.terminal.TerminalRuntimeIdentity
import dev.ide.workbench.reliability.HostRuntimeCapture
import dev.ide.workbench.reliability.HostRuntimeChannel
import dev.ide.workbench.reliability.HostRuntimeException
import dev.ide.workbench.reliability.HostRuntimeFinish
import dev.ide.workbench.reliability.HostRuntimeKind
import dev.ide.workbench.reliability.HostRuntimeOutcome
import dev.ide.workbench.reliability.HostRuntimeStage
import dev.ide.workbench.runtime.RuntimeObserver
import dev.ide.workbench.runtime.RuntimePreparedMetadata
import dev.ide.workbench.runtime.RuntimeOutcomeClassification
import dev.ide.workbench.runtime.RuntimeExceptionCategory
import dev.ide.workbench.runtime.RuntimeSessionObserver
import dev.ide.workbench.runtime.RuntimeSessionOutcome

/** Host-local provenance only. This does not impersonate an SDK app or enable AI/relay delivery. */
internal class AndroidRuntimeReliability(context: Context) {
    private val application = context.applicationContext
    val capture: HostRuntimeCapture get() = ReliabilityHostProvider.get(application).runtimeCapture

    val runtimeObserver = RuntimeObserver { sessionId, projectRoot, kind, _ ->
        val handle = capture.begin(projectRoot, sessionId.toString(), kind?.let { HostRuntimeKind.valueOf(it.name) } ?: HostRuntimeKind.UNKNOWN, HostRuntimeChannel.RUN)
        if (handle == null) RuntimeSessionObserver.NONE else object : RuntimeSessionObserver {
            override fun onRuntimePrepared(metadata: RuntimePreparedMetadata) {
                val packId = metadata.packId
                val abi = metadata.abi
                if (packId != null && abi != null) handle.prepared(packId, abi)
            }
            override fun onSessionFinished(outcome: RuntimeSessionOutcome) {
                val result = when (outcome.classification) {
                    RuntimeOutcomeClassification.UNEXPECTED_FAILURE -> if (outcome.exceptionCategory == RuntimeExceptionCategory.INVALID_CONFIGURATION) HostRuntimeOutcome.CONFIGURATION_FAILURE else HostRuntimeOutcome.INFRASTRUCTURE_FAILURE
                    RuntimeOutcomeClassification.NONZERO_EXIT -> HostRuntimeOutcome.NONZERO_EXIT
                    RuntimeOutcomeClassification.TIMEOUT -> HostRuntimeOutcome.TIMEOUT
                    RuntimeOutcomeClassification.CANCELLED, RuntimeOutcomeClassification.SUCCESS -> null
                }
                handle.finish(HostRuntimeFinish(
                    outcome = result,
                    stage = HostRuntimeStage.valueOf(outcome.stage.name),
                    exception = outcome.exceptionCategory?.let { HostRuntimeException.valueOf(it.name) },
                    exitCode = outcome.exitCode,
                    durationMs = outcome.durationMillis,
                ))
            }
        }
    }

    val terminalObserver = TerminalOutcomeObserverFactory { session ->
        val root = session.workingDirectory
        val kind = if (session.programKind == TerminalProgramKind.NODE) HostRuntimeKind.NODE else HostRuntimeKind.SHELL
        val handle = root?.let { capture.begin(it, session.sessionId, kind, HostRuntimeChannel.TERMINAL) }
        if (handle == null) TerminalOutcomeObserver.NONE else object : TerminalOutcomeObserver {
            override fun onRuntimePrepared(identity: TerminalRuntimeIdentity) {
                handle.prepared(identity.packId, identity.abi)
            }
            override fun onTerminal(outcome: TerminalOutcome) {
                val classification = outcome.classification
                val result = when (classification) {
                    TerminalOutcomeClassification.PREPARE_FAILURE,
                    TerminalOutcomeClassification.LAUNCH_FAILURE,
                    TerminalOutcomeClassification.SESSION_FAILURE -> HostRuntimeOutcome.INFRASTRUCTURE_FAILURE
                    TerminalOutcomeClassification.NONZERO_EXIT -> HostRuntimeOutcome.NONZERO_EXIT
                    TerminalOutcomeClassification.SUCCESS, TerminalOutcomeClassification.USER_CANCELLED -> null
                }
                val stage = when (classification) {
                    TerminalOutcomeClassification.PREPARE_FAILURE -> HostRuntimeStage.PREPARE
                    TerminalOutcomeClassification.LAUNCH_FAILURE -> HostRuntimeStage.LAUNCH
                    TerminalOutcomeClassification.SESSION_FAILURE -> HostRuntimeStage.SESSION
                    else -> HostRuntimeStage.PROCESS
                }
                handle.finish(HostRuntimeFinish(result, stage, exitCode = outcome.exitCode, durationMs = outcome.durationMs))
            }
        }
    }
}
