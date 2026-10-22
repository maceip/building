// Copyright (C) 2026 tyron12233
// SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
package dev.ide.platform.proof

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GdpPermixTest {

    // A sensitive function that CANNOT be called without a Proof that the path is both
    // WithinWorkspace AND Permitted for WriteAction on that exact Named<P, String>.
    private fun <P, R> writeProtectedFile(
        path: Named<P, String>,
        content: String,
        workspaceProof: Proof<Fact.WithinWorkspace<P, R>>,
        permissionProof: Proof<PermixFact.Permitted<AgentResources.WorkspaceFile, AgentAction.Write, P>>,
    ): String {
        val combined: Proof<And<Fact.WithinWorkspace<P, R>, PermixFact.Permitted<AgentResources.WorkspaceFile, AgentAction.Write, P>>> =
            and(workspaceProof, permissionProof)
        return "wrote ${content.length} bytes to ${path.value} [${combined.witness}]"
    }

    @Test
    fun gdpProvesWorkspaceContainmentAndRejectsTraversal() {
        withNamedPair("/workspace/project/src/Main.kt", "/workspace/project") { file, root ->
            val validProof = DomainProvers.proveWithinWorkspace(file, root)
            assertNotNull(validProof, "Path inside workspace should produce a valid proof")
        }

        withNamedPair("/workspace/project/../secrets/id_rsa", "/workspace/project") { escaped, root ->
            val invalidProof = DomainProvers.proveWithinWorkspace(escaped, root)
            assertNull(invalidProof, "Path with '..' traversal must NOT produce a proof")
        }
    }

    @Test
    fun gdpPreventsFabricatingOnDeviceRuntimeClaims() {
        val installedModels = setOf("/data/user/0/dev.ide/files/models/qwen2.5-coder-1.5b.gguf")

        withNamed("/data/user/0/dev.ide/files/models/qwen2.5-coder-1.5b.gguf") { realModel ->
            val proof = DomainProvers.proveOnDeviceRuntime(realModel) { it in installedModels }
            assertNotNull(proof, "Installed model binary must yield Proof<OnDeviceRuntimeVerified>")
        }

        withNamed("anthropic:claude-sonnet-4-6") { cloudModel ->
            val proof = DomainProvers.proveOnDeviceRuntime(cloudModel) { it in installedModels }
            assertNull(proof, "Cloud API model must never yield Proof<OnDeviceRuntimeVerified>")
        }
    }

    @Test
    fun permixEnforcesPlanOnlyAskEachAndAutoAcceptAndMintsGdpProofs() {
        val workspaceRoot = "/workspace/app"
        val planOnly = AgentPermixTemplates.forTier(AgentPolicyTier.PLAN_ONLY, workspaceRoot)
        val askEach = AgentPermixTemplates.forTier(
            tier = AgentPolicyTier.ASK_EACH,
            workspaceRoot = workspaceRoot,
            approvalCallback = { _, action, target -> action == "install_package" && target == "room-runtime" },
        )
        val autoAccept = AgentPermixTemplates.forTier(AgentPolicyTier.AUTO_ACCEPT, workspaceRoot)

        withNamedPair("/workspace/app/src/App.kt", workspaceRoot) { targetFile, root ->
            val wsProof = assertNotNull(DomainProvers.proveWithinWorkspace(targetFile, root))

            // 1. PLAN_ONLY allows Read, denies Write
            assertTrue(planOnly.check(AgentResources.WorkspaceFile, AgentAction.Read, targetFile.value))
            assertNull(planOnly.prove(AgentResources.WorkspaceFile, AgentAction.Write, targetFile))
            assertFailsWith<PermixException> {
                planOnly.requireProof(AgentResources.WorkspaceFile, AgentAction.Write, targetFile)
            }

            // 2. ASK_EACH allows Write inside workspace and mints a Proof that satisfies writeProtectedFile
            val writeProof = assertNotNull(askEach.prove(AgentResources.WorkspaceFile, AgentAction.Write, targetFile))
            val result = writeProtectedFile(targetFile, "fun main() {}", wsProof, writeProof)
            assertTrue(result.contains("wrote 13 bytes"))

            // 3. ASK_EACH delegates package installation to approvalCallback
            withNamed("room-runtime") { approvedPkg ->
                assertNotNull(askEach.prove(AgentResources.PackageManager, AgentAction.InstallPackage, approvedPkg))
            }
            withNamed("unapproved-pkg") { rejectedPkg ->
                assertNull(askEach.prove(AgentResources.PackageManager, AgentAction.InstallPackage, rejectedPkg))
            }

            // 4. AUTO_ACCEPT allows all in-workspace actions but still blocks out-of-workspace writes
            assertNotNull(autoAccept.prove(AgentResources.WorkspaceFile, AgentAction.Delete, targetFile))
            withNamed("/etc/passwd") { outsideFile ->
                assertFalse(autoAccept.check(AgentResources.WorkspaceFile, AgentAction.Write, outsideFile.value))
                assertNull(autoAccept.prove(AgentResources.WorkspaceFile, AgentAction.Write, outsideFile))
            }
        }
    }

    @Test
    fun logicalCombinatorsAndModusPonensComposeProofs() {
        withNamed("sk-live-12345") { apiKey ->
            val pNonBlank = assertNotNull(DomainProvers.proveNonBlank(apiKey))
            val rule = axiomImplies<Fact.NonBlank<Any?>, Fact.ExplicitUserGesture>("KeyImpliesGestureInTest")
            val deduced = modusPonens(rule, pNonBlank)
            assertTrue(deduced.witness.contains("KeyImpliesGestureInTest"))
        }

        var gestureRecorded = ""
        DomainProvers.withExplicitUserGesture("tap:ProviderCard:openai") { proof ->
            gestureRecorded = proof.witness
        }
        assertEquals("ExplicitUserGesture(tap:ProviderCard:openai)", gestureRecorded)
    }
}
