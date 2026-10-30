// Copyright (C) 2026 tyron12233
// SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
package dev.ide.platform

import dev.ide.platform.Kotlin25LanguageFeatures.codePointsToList
import dev.ide.platform.Kotlin25LanguageFeatures.ifNotBlank
import dev.ide.platform.Kotlin25LanguageFeatures.isMonotonicallySorted
import dev.ide.platform.Kotlin25LanguageFeatures.isSortedBySelector
import dev.ide.platform.Kotlin25LanguageFeatures.mapIf
import dev.ide.platform.log.Log
import dev.ide.platform.proof.AgentAction
import dev.ide.platform.proof.AgentPermixTemplates
import dev.ide.platform.proof.AgentPolicyTier
import dev.ide.platform.proof.AgentResources
import dev.ide.platform.proof.withNamed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi

class Kotlin25LanguageFeaturesTest {

    @Test
    fun explicitBackingFieldsEliminateUnderscoreTwinPropertyBoilerplate() {
        val trail = ExplicitBackingFieldTrail()
        assertTrue(trail.history.isEmpty())
        trail.record("init")
        trail.record("compile")
        trail.record("dex")
        assertEquals(listOf("init", "compile", "dex"), trail.history)
        trail.clear()
        assertTrue(trail.history.isEmpty())
    }

    @Test
    fun contextParametersPropagateLoggerAndGdpPermixProofsCleanly() {
        val comp = Kotlin25LanguageFeatures.resolveOrRegisterComponent("kotlin25.context.test", "model-api")
        val permix = AgentPermixTemplates.forTier(AgentPolicyTier.AUTO_ACCEPT, "/workspace")

        withNamed("/workspace/Main.kt") { namedFile ->
            val writeProof = assertNotNull(
                permix.prove(AgentResources.WorkspaceFile, AgentAction.Write, namedFile)
            )
            // Provide both context(Logger, Proof<Permitted<...>>) via `context(...)` block
            val output = with(comp.logger) {
                Kotlin25LanguageFeatures.contextualInfo("Starting context-parameter test")
                with(writeProof) {
                    Kotlin25LanguageFeatures.runPermittedWithContext(
                        operationName = "writeMainKt",
                        target = namedFile,
                    ) { path ->
                        "verified:$path"
                    }
                }
            }
            assertEquals("verified:/workspace/Main.kt", output)
        }
    }

    @Test
    fun valueClassCustomEqualsAndUnicodeCodePointsAndSortedChecksWork() {
        // 1. Custom equals/hashCode in @JvmInline value class
        val idA = CaseInsensitiveId("Antigravity")
        val idB = CaseInsensitiveId("antigravity")
        assertEquals(idA, idB)
        assertEquals(idA.hashCode(), idB.hashCode())

        // 2. Multiplatform Unicode code points (including astral-plane rocket emoji U+1F680)
        val text = "A🚀B"
        assertEquals(listOf(65, 0x1F680, 66), text.codePointsToList())

        // 3. Zero-allocation sortedness checks
        assertTrue(listOf(1, 2, 2, 5, 9).isMonotonicallySorted())
        assertFalse(listOf(1, 4, 3, 9).isMonotonicallySorted())
        val coords = listOf(
            ComponentCoordinate("a.one", "core"),
            ComponentCoordinate("b.two", "ui"),
        )
        assertTrue(coords.isSortedBySelector { it.componentId })

        // 4. Data-flow exhaustiveness & return-in-expression-body
        assertEquals("idle", Kotlin25LanguageFeatures.describeActiveBuildPhase(Kotlin25LanguageFeatures.BuildPhase.Idle))
        assertEquals(
            "compiling:app",
            Kotlin25LanguageFeatures.describeActiveBuildPhase(Kotlin25LanguageFeatures.BuildPhase.Compiling("app")),
        )

        // 5. Conditional expression helpers & ISO-8601 Instant
        assertEquals("HELLO!", "hello".mapIf(true) { it.uppercase() + "!" })
        assertNull("   ".ifNotBlank { it.trim() })
        assertEquals("ok", " ok ".ifNotBlank { it.trim() })
        assertEquals("1970-01-01T00:00:01Z", Kotlin25LanguageFeatures.formatEpochIso8601(1_000L))

        @OptIn(ExperimentalUuidApi::class)
        val uuid = Kotlin25LanguageFeatures.mintCorrelationUuid()
        @OptIn(ExperimentalUuidApi::class)
        assertEquals(32, uuid.toHexString().length)
    }
}
