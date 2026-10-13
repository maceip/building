package dev.ide.android.reliability.repair

import android.content.Context
import dev.ide.agent.IncidentKnownBuild
import dev.ide.agent.IncidentRepairPolicy
import dev.ide.agent.IncidentTask
import dev.ide.agent.IncidentTaskKind
import dev.ide.agent.IncidentVerificationPlan
import dev.ide.agent.impl.IncidentDigests
import dev.ide.build.ReliabilitySourceSnapshot
import dev.ide.core.reliability.IncidentGradleInvocation
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.Paths

/** Host-private, explicit local-unit verification authorization; never an instruction file in the project. */
class ApprovedRepairPlanStore(context: Context, val projectRoot: Path) {
    val projectId: String = IncidentDigests.bytes(projectRoot.toRealPath().toString().toByteArray())
    private val prefs = context.applicationContext.getSharedPreferences("incident-repair-$projectId", Context.MODE_PRIVATE)

    data class Input(
        val variant: String,
        val buildTask: String,
        val unitTask: String,
        val reproducerTest: String,
        val functionalTest: String,
        val editableFiles: Set<String>,
        val reproducerFiles: Set<String>,
        val functionalFiles: Set<String>,
        /** Separate consent to trusted project code execution, not a claim that Android provides a sandbox. */
        val trustedLocalExecution: Boolean,
    )
    data class Approved(val input: Input, val immutableFiles: Map<String, String>)

    fun policy(): IncidentRepairPolicy = IncidentRepairPolicy(projectId,
        enabled = prefs.getBoolean("enabled", false), cloudAiDataSharing = prefs.getBoolean("cloudSharing", false),
        approvedAiConfiguration = prefs.getString("approvedAi", null), autoApplyVerifiedPatch = prefs.getBoolean("autoApply", false),
        maxAttempts = prefs.getInt("attempts", 2).coerceIn(1, 2))
    fun savePolicy(policy: IncidentRepairPolicy) {
        require(policy.projectId == projectId)
        check(prefs.edit().putBoolean("enabled", policy.enabled).putBoolean("cloudSharing", policy.cloudAiDataSharing)
            .putString("approvedAi", policy.approvedAiConfiguration).putBoolean("autoApply", policy.autoApplyVerifiedPatch)
            .putInt("attempts", policy.maxAttempts).commit())
    }

    /** Freezes ALL copied noneditable source/build/test inputs. Changes require explicit reapproval. */
    fun approve(input: Input) {
        validate(input)
        require(input.trustedLocalExecution)
        val snapshot = ReliabilitySourceSnapshot.capture(projectRoot)
        require(snapshot.privateRelativeFiles.keys.containsAll(input.editableFiles + input.reproducerFiles + input.functionalFiles))
        val immutable = snapshot.privateRelativeFiles.filterKeys { it !in input.editableFiles }.toMutableMap()
        val wrapper = projectRoot.resolve("gradle/wrapper/gradle-wrapper.properties")
        require(Files.isRegularFile(wrapper, NOFOLLOW_LINKS) && Files.size(wrapper) <= 16_384) { "A frozen wrapper version is required for offline installed-distribution selection." }
        immutable["gradle/wrapper/gradle-wrapper.properties"] = IncidentDigests.file(wrapper)
        require(immutable.isNotEmpty())
        val json = buildJsonObject {
            put("variant", input.variant); put("buildTask", input.buildTask); put("unitTask", input.unitTask)
            put("reproducerTest", input.reproducerTest); put("functionalTest", input.functionalTest)
            put("trustedLocalExecution", input.trustedLocalExecution)
            put("editableFiles", JsonArray(input.editableFiles.sorted().map(::JsonPrimitive)))
            put("reproducerFiles", JsonArray(input.reproducerFiles.sorted().map(::JsonPrimitive)))
            put("functionalFiles", JsonArray(input.functionalFiles.sorted().map(::JsonPrimitive)))
            put("immutableFiles", JsonObject(immutable.mapValues { JsonPrimitive(it.value) }))
        }
        check(prefs.edit().putString("plan", json.toString()).commit())
    }
    fun revoke() { check(prefs.edit().remove("plan").commit()) }

    fun read(): Approved? = runCatching {
        val text = prefs.getString("plan", null) ?: return null
        require(text.length <= 4 * 1024 * 1024)
        val obj = Json.parseToJsonElement(text) as JsonObject
        fun str(name: String) = obj.getValue(name).jsonPrimitive.content
        fun paths(name: String) = (obj.getValue(name) as JsonArray).map { it.jsonPrimitive.content }.toSet()
        val input = Input(str("variant"), str("buildTask"), str("unitTask"), str("reproducerTest"), str("functionalTest"),
            paths("editableFiles"), paths("reproducerFiles"), paths("functionalFiles"), obj["trustedLocalExecution"]?.jsonPrimitive?.booleanOrNull == true)
        validate(input)
        val hashes = (obj.getValue("immutableFiles") as JsonObject).mapValues { it.value.jsonPrimitive.content }
        require(hashes.values.all { Regex("[a-f0-9]{64}").matches(it) })
        Approved(input, hashes)
    }.getOrNull()

    fun plan(build: IncidentKnownBuild): IncidentVerificationPlan? {
        val approved = read() ?: return null
        val input = approved.input
        if (!input.trustedLocalExecution || !build.debuggable || build.projectId != projectId || build.variant != input.variant) return null
        return plan(approved)
    }

    fun approvedLocalPlan(): IncidentVerificationPlan? = read()?.takeIf { it.input.trustedLocalExecution }?.let(::plan)

    private fun plan(approved: Approved): IncidentVerificationPlan {
        val input = approved.input
        val buildTask = task("build", IncidentTaskKind.BUILD, input.buildTask, null)
        val repro = task("reproducer", IncidentTaskKind.REPRODUCER, input.unitTask, input.reproducerTest)
        val functional = task("functional", IncidentTaskKind.FUNCTIONAL, input.unitTask, input.functionalTest)
        return IncidentVerificationPlan(buildTask, repro, functional, input.editableFiles, approved.immutableFiles,
            input.reproducerFiles, input.functionalFiles)
    }

    fun invocations(plan: IncidentVerificationPlan): List<IncidentGradleInvocation> = listOf(plan.build, plan.reproducer, plan.functional).map { task ->
        IncidentGradleInvocation(task, listOf(task.command[1]), task.command.drop(2), usesDevice = false)
    }

    private fun task(id: String, kind: IncidentTaskKind, gradleTask: String, test: String?): IncidentTask {
        val args = if (test == null) emptyList() else listOf("--tests", test.replace('#', '.'))
        val reports = if (test == null) emptySet() else {
            val module = gradleTask.substringBeforeLast(':').trim(':').replace(':', '/')
            val prefix = if (module.isBlank()) "" else "$module/"
            setOf("${prefix}build/test-results/${gradleTask.substringAfterLast(':')}/TEST-${test.substringBefore('#')}.xml")
        }
        return IncidentTask(id, kind, listOf("gradle", gradleTask) + args, reports, test?.let(::setOf) ?: emptySet())
    }

    private fun validate(input: Input) {
        require(Regex("[A-Za-z][A-Za-z0-9]{0,80}").matches(input.variant))
        require(Regex(":(?:[A-Za-z0-9_-]+:)*(?:assemble[A-Za-z0-9]*|classes|compile[A-Za-z0-9]+)").matches(input.buildTask))
        require(Regex(":(?:[A-Za-z0-9_-]+:)*test[A-Za-z0-9]*").matches(input.unitTask))
        val testPattern = Regex("[A-Za-z_$][A-Za-z0-9_.$]*#[A-Za-z_$][A-Za-z0-9_$]*")
        require(testPattern.matches(input.reproducerTest) && testPattern.matches(input.functionalTest) && input.reproducerTest != input.functionalTest)
        require(input.editableFiles.size in 1..32 && input.reproducerFiles.isNotEmpty() && input.functionalFiles.isNotEmpty())
        require(input.reproducerFiles.intersect(input.functionalFiles).isEmpty())
        require(input.editableFiles.intersect(input.reproducerFiles + input.functionalFiles).isEmpty())
        (input.editableFiles + input.reproducerFiles + input.functionalFiles).forEach { relative ->
            require(!Paths.get(relative).isAbsolute && relative.split('/').none { it in setOf("", ".", "..") } && '\\' !in relative)
            val path = projectRoot.resolve(relative)
            require(Files.isRegularFile(path, NOFOLLOW_LINKS) && path.toRealPath().startsWith(projectRoot.toRealPath()))
        }
    }
}
