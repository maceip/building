package dev.ide.workbench.scenario

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class RegressionScenarioParserTest {
    @TempDir lateinit var root: Path

    private val command = """{"version":1,"id":"smoke","title":"Local smoke","targetId":"node:main.cjs","steps":[{"id":"test","type":"command","expect":{"exitCode":0}}]}"""
    private fun write(text: String = command): ReviewedRegressionScenario {
        Files.writeString(root.resolve("main.cjs"), "console.log('fixture');")
        Files.writeString(root.resolve("scenario.json"), text)
        return RegressionScenarioParser.load(root, "scenario.json")
    }

    @Test fun `review pins bytes and artifacts without rendering secret payloads`() {
        val review = write(command.replace("\"exitCode\":0", "\"exitCode\":0,\"stdout\":{\"contains\":[\"secret-token-123456789\"]}"))
        assertEquals(64, review.sha256.length)
        assertEquals(64, review.sourceArtifactHash.length)
        assertEquals(listOf("main.cjs"), review.sourceArtifacts.map { it.relativePath })
        assertFalse(review.reviewSummary.contains("secret-token"))
        assertEquals("node:main.cjs", review.scenario.targetId)
    }

    @Test fun `unknown commands URLs fields and ambiguous JSON fail closed`() {
        val bad = listOf(
            command.replace("\"version\":1", "\"version\":1,\"version\":1"),
            command.replace("\"title\"", "\"ti\\u0074le\":\"other\",\"title\""),
            command.replace("\"version\":1", "\"version\":\"1\""),
            command.replace("\"version\":1", "\"version\":2"),
            command.replace("\"version\":1", "\"version\":1,\"command\":\"rm -rf /\""),
            command.replace("node:main.cjs", "shell:main.cjs"),
            command.replace("node:main.cjs", "node:../main.cjs"),
            command.replace("node:main.cjs", "node:/main.cjs"),
            command.replace("\"exitCode\":0", "\"exitCode\":0,\"stdout\":{\"regex\":\".*\"}"),
            command.replace("\"steps\":[", "\"limits\":{\"runTimeoutMs\":180001},\"steps\":["),
            command.replace("\"steps\":[", "\"limits\":{\"maxOutputBytes\":9999999999},\"steps\":["),
            command.replace("\"type\":\"command\"", "\"type\":\"rotate-app\""),
            command.replace("\"steps\":[", "\"environment\":{\"TOKEN\":\"private\"},\"steps\":["),
        )
        bad.forEach { text -> assertFailsWith<ScenarioValidationException> { write(text) } }
    }

    @Test fun `HTTP paths cannot redirect target outside owned preview`() {
        listOf("http://example.com/", "//127.0.0.1/admin", "/../secret", "/a/./b", "/%2e%2e/secret", "/\\evil", "/path#fragment", "/?url=http://10.0.0.1", "/a\nHost:evil").forEach { path ->
            assertFailsWith<ScenarioValidationException> { RegressionScenarioParser.validateHttpPath(path) }
        }
        listOf("/health", "/state", "/items?page=1", "/a-b_test.json").forEach(RegressionScenarioParser::validateHttpPath)
    }

    @Test fun `rooted loading rejects traversal and every symlink component`() {
        write()
        assertFailsWith<ScenarioValidationException> { RegressionScenarioParser.load(root, "../scenario.json") }
        Files.createSymbolicLink(root.resolve("linked.json"), root.resolve("scenario.json"))
        assertFailsWith<ScenarioValidationException> { RegressionScenarioParser.load(root, "linked.json") }
        Files.createDirectory(root.resolve("folder"))
        Files.copy(root.resolve("scenario.json"), root.resolve("folder/scenario.json"))
        Files.createSymbolicLink(root.resolve("linked-folder"), root.resolve("folder"))
        assertFailsWith<ScenarioValidationException> { RegressionScenarioParser.load(root, "linked-folder/scenario.json") }
        Files.delete(root.resolve("main.cjs"))
        Files.createSymbolicLink(root.resolve("main.cjs"), root.resolve("scenario.json"))
        assertFailsWith<ScenarioValidationException> { RegressionScenarioParser.load(root, "scenario.json") }
    }

    @Test fun `file bytes nesting step count and lifecycle are bounded`() {
        write()
        Files.writeString(root.resolve("scenario.json"), " ".repeat(ScenarioLimits.MAX_FILE_BYTES + 1))
        assertFailsWith<ScenarioValidationException> { RegressionScenarioParser.load(root, "scenario.json") }
        assertFailsWith<ScenarioValidationException> { write(command.replace("\"exitCode\":0", "\"exitCode\":" + "[".repeat(20) + "0" + "]".repeat(20))) }
        val steps = (1..33).joinToString(",") { """{"id":"step$it","type":"command","expect":{"exitCode":0}}""" }
        assertFailsWith<ScenarioValidationException> { write(command.substringBefore("\"steps\"") + "\"steps\":[$steps]}") }
        assertFailsWith<ScenarioValidationException> { write(command.replace("{\"id\":\"test\",\"type\":\"command\",\"expect\":{\"exitCode\":0}}", "{\"id\":\"stop\",\"type\":\"stop\"}")) }
        val http = """{"id":"get","type":"http","method":"GET","path":"/state","expect":{"status":200}}"""
        assertFailsWith<ScenarioValidationException> { write(command.substringBefore("\"steps\"") + "\"steps\":[$http]}") }
    }

    @Test fun `samples create fresh workspaces and both scenarios are valid`() {
        Files.writeString(root.resolve("keep.txt"), "untouched")
        val first = RegressionScenarioSamples.seedOfflineNode(root)
        val second = RegressionScenarioSamples.seedOfflineNode(root)
        assertNotEquals(first, second)
        assertEquals("untouched", Files.readString(root.resolve("keep.txt")))
        val state = RegressionScenarioParser.load(first, RegressionScenarioSamples.STATE_SCENARIO)
        val smoke = RegressionScenarioParser.load(first, RegressionScenarioSamples.COMMAND_SCENARIO)
        assertEquals(10, state.scenario.steps.size)
        assertEquals(1, smoke.scenario.steps.size)
        assertEquals(2, state.sourceArtifacts.size)
        assertTrue(state.scenario.steps.any { it is ScenarioStep.Http && it.expect.status == 429 })
    }
}
