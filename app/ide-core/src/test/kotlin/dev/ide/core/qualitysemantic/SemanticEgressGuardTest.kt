package dev.ide.core.qualitysemantic

import dev.ide.agent.LlmClient
import dev.ide.agent.LlmModelInfo
import dev.ide.agent.LlmProvider
import dev.ide.agent.LlmStreamEvent
import dev.ide.agent.ProviderConfig
import dev.ide.agent.SimpleLlmProviderRegistry
import dev.ide.ui.backend.UiAgentConfig
import dev.ide.ui.backend.UiAgentProvider
import dev.ide.workbench.quality.CauseStatus
import dev.ide.workbench.quality.Confidence
import dev.ide.workbench.quality.EvidenceCitation
import dev.ide.workbench.quality.EvidenceSource
import dev.ide.workbench.quality.MergeStatus
import dev.ide.workbench.quality.PrimaryTheme
import dev.ide.workbench.quality.QualityRecord
import dev.ide.workbench.quality.QualityReport
import dev.ide.workbench.quality.RecordIntent
import dev.ide.workbench.quality.RecordKind
import dev.ide.workbench.quality.RecordState
import dev.ide.workbench.quality.ReportKind
import dev.ide.workbench.quality.Transferability
import dev.ide.workbench.quality.semantic.SemanticException
import dev.ide.workbench.quality.semantic.SemanticFailure
import dev.ide.workbench.quality.semantic.SemanticGroupingService
import dev.ide.workbench.quality.semantic.SemanticLocality
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import okhttp3.Call
import okhttp3.Connection
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Test
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Fake-chain tests: no socket, DNS, key store, archive or actual provider is contacted. */
class SemanticEgressGuardTest {
    @Test fun `same-origin fallback is capped at two sends and malicious redirects cannot escape`() {
        val guard = SemanticEgressGuard("http://127.0.0.1:11434")
        val client = guard.client()
        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
        assertFalse(client.retryOnConnectionFailure)
        for (destination in listOf("https://example.test/v1/messages", "http://localhost:11434/v1/chat/completions",
            "http://127.0.0.1:11435/v1/chat/completions", "http://127.0.0.1.evil.test:11434/v1/messages")) {
            val chain = FakeChain(request(destination))
            assertFailsWith<IOException> { guard.intercept(chain) }
            assertEquals(0, chain.proceeded)
        }
        assertEquals(0, guard.sentCount)
        val redirect = FakeChain(request("http://127.0.0.1:11434/v1/chat/completions"), redirect = true)
        guard.intercept(redirect).close()
        assertEquals(1, redirect.proceeded)
        assertEquals(1, guard.sentCount)
        // A provider's same-origin protocol fallback consumes the second and final approved wire send.
        val fallback = FakeChain(request("http://127.0.0.1:11434/v1/responses"))
        guard.intercept(fallback).close()
        assertEquals(2, guard.sentCount)
        val third = FakeChain(request("http://127.0.0.1:11434/v1/messages"))
        assertFailsWith<IOException> { guard.intercept(third) }
        assertEquals(0, third.proceeded)
        assertEquals(2, guard.sentCount)
    }

    @Test fun `config preview never creates a client consent binds endpoint and DNS names are not local`() = runTest {
        var clientCreations = 0
        var labelRequests = 0
        val fake = object : LlmProvider {
            override val id = "openai"
            override val displayName = "Fake existing provider"
            override val models = listOf(LlmModelInfo("fixture", "Fixture"))
            override val defaultModel = "fixture"
            override fun client(config: ProviderConfig): LlmClient {
                clientCreations++
                return LlmClient { flow { labelRequests++; emit(LlmStreamEvent.Failed("synthetic rejection")) } }
            }
        }
        val registry = SimpleLlmProviderRegistry(listOf(fake))
        var config = UiAgentConfig(providers = listOf(UiAgentProvider("gateway", "Gateway", emptyList(), "", apiKey = "existing-test-only")),
            selectedProvider = "gateway", configured = true, model = "fixture", gatewayModel = "fixture", gatewayBaseUrl = "http://127.0.0.1:11434")
        var factoryCalls = 0
        fun labeler() = ConfiguredSemanticLabeler.from(registry, { config }) { factoryCalls++; registry }
        val first = assertNotNull(labeler())
        assertEquals(SemanticLocality.LOCAL, first.provider.locality)
        val service = SemanticGroupingService(first)
        val prepared = service.prepare(report())
        val consent = service.confirm(prepared)
        assertEquals(0, factoryCalls)
        assertEquals(0, clientCreations)
        assertEquals(0, labelRequests)
        assertFailsWith<SemanticException> { service.run(prepared) }
        config = config.copy(gatewayBaseUrl = "https://example.test/v1")
        assertEquals(SemanticFailure.PROVIDER_CHANGED, service.run(prepared, consent).failure)
        assertEquals(0, labelRequests)
        assertEquals(0, clientCreations)
        assertEquals(SemanticLocality.CLOUD, assertNotNull(labeler()).provider.locality)
        config = config.copy(gatewayBaseUrl = "https://localhost:11434")
        assertEquals(SemanticLocality.CLOUD, assertNotNull(labeler()).provider.locality)
        config = config.copy(gatewayBaseUrl = "http://localhost:11434")
        assertNull(labeler())
        config = config.copy(gatewayBaseUrl = "https://user:secret@example.test/v1")
        assertNull(labeler())
        config = config.copy(gatewayBaseUrl = "http://127.0.0.1:11434")
        val nextService = SemanticGroupingService(assertNotNull(labeler()))
        val next = nextService.prepare(report())
        assertEquals(SemanticFailure.UNAVAILABLE, nextService.run(next, nextService.confirm(next)).failure)
        assertEquals(1, factoryCalls)
        assertEquals(1, clientCreations)
        assertEquals(1, labelRequests)
    }

    private fun request(url: String): Request = Request.Builder().url(url).post("{}".toRequestBody()).build()
    private class FakeChain(private val input: Request, private val redirect: Boolean = false) : Interceptor.Chain {
        var proceeded = 0
        override fun request(): Request = input
        override fun proceed(request: Request): Response {
            proceeded++
            return Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(if (redirect) 307 else 200)
                .message("synthetic").header("Location", "https://unapproved.test/collect").body("{}".toResponseBody()).build()
        }
        override fun connection(): Connection? = null
        override fun call(): Call = error("No real call")
        override fun connectTimeoutMillis(): Int = 1000
        override fun withConnectTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
        override fun readTimeoutMillis(): Int = 1000
        override fun withReadTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
        override fun writeTimeoutMillis(): Int = 1000
        override fun withWriteTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
    }

    private fun report(): QualityReport {
        val record = QualityRecord("synthetic/app#1", "synthetic/app", 1, RecordKind.ISSUE, RecordState.OPEN,
            MergeStatus.NOT_APPLICABLE, "Button overlap", "Small screen buttons overlap", Instant.EPOCH,
            "https://github.com/synthetic/app/issues/1", RecordIntent.UNCLEAR, PrimaryTheme.UNCLASSIFIED, emptyList(), "unknown",
            Confidence.LOW, CauseStatus.SYMPTOM_ONLY, Transferability.UNCLEAR, false,
            EvidenceCitation("synthetic/app#1", null, "Button overlap", EvidenceSource.TITLE), "synthetic only", emptyList())
        return QualityReport(ReportKind.ARCHIVE, "Synthetic", Instant.EPOCH, listOf(record))
    }
}
