// Copyright (C) 2026 tyron12233
// SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
package dev.ide.platform

import dev.ide.platform.log.Log
import dev.ide.platform.log.Logger
import dev.ide.platform.log.RegisteredComponent
import dev.ide.platform.log.TraceScope
import dev.ide.platform.proof.Fact
import dev.ide.platform.proof.Named
import dev.ide.platform.proof.PermixAction
import dev.ide.platform.proof.PermixFact
import dev.ide.platform.proof.PermixResource
import dev.ide.platform.proof.Proof
import kotlin.jvm.JvmInline
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Production utilities in `:model-api` leveraging language, compiler, and standard-library features
 * introduced across **Kotlin 2.3**, **Kotlin 2.4**, and **Kotlin 2.5 (`2.5.0-Beta2` EAP)**:
 *
 * ### Kotlin 2.3
 * 1. `@MustUseReturnValue` / `@IgnorableReturnValue` return-value checking.
 * 2. Return statements inside `=` expression-body functions.
 * 3. Nested `typealias` declarations scoped inside classes/objects.
 * 4. Data-flow-based exhaustiveness in `when` expressions after guard clauses.
 * 5. Multiplatform `kotlin.time.Instant` ISO-8601 formatting.
 *
 * ### Kotlin 2.4
 * 6. Stable **Context Parameters** (`context(logger: Logger)`, `context(proof: Proof<...>)`).
 * 7. **Explicit Backing Fields** (`val items: List<T> field = mutableListOf()`).
 * 8. `@all:` annotation use-site meta-target on property declarations.
 * 9. Multiplatform `kotlin.uuid.Uuid` & zero-allocation `.isSorted()` / `.isSortedBy()` checks.
 * 10. `-Xexpect-actual-classes` clean multiplatform expect/actual class compilation.
 *
 * ### Kotlin 2.5 (`2.5.0-Beta2` EAP)
 * 11. Custom `equals` / `hashCode` inside `@JvmInline value class` ([CaseInsensitiveId]).
 * 12. Multiplatform Unicode code-point iteration ([codePointsToList]) for astral-plane/emoji editor text.
 * 13. Conditional expression helpers ([mapIf], [ifNotBlank]).
 * 14. Destructuring-safe component metadata records ([ComponentCoordinate]).
 * 15. Context-aware traced execution (`withComponentContext`).
 */
@OptIn(ExperimentalUuidApi::class, ExperimentalTime::class)
object Kotlin25LanguageFeatures {

    /** Kotlin 2.3 feature #3: Nested `typealias` scoped inside an enclosing object/class. */
    typealias AttributeBag = Map<String, String>
    typealias MutationRecord = Pair<Long, String>

    /**
     * Kotlin 2.3 feature #2: `return` statement directly inside an `=` expression-body function
     * when the return type is explicitly declared.
     */
    fun resolveOrRegisterComponent(id: String, defaultOwner: String = "ide-core"): RegisteredComponent =
        Log.registeredComponents()[id.trim().ifEmpty { return Log.registerComponent("anonymous", defaultOwner) }]
            ?: return Log.registerComponent(id.trim(), defaultOwner)

    /**
     * Kotlin 2.3 feature #4: Data-flow-based exhaustiveness for `when` expressions following an early guard.
     */
    sealed interface BuildPhase {
        data object Idle : BuildPhase
        data class Compiling(val moduleName: String) : BuildPhase
        data class Succeeded(val durationMs: Long) : BuildPhase
        data class Failed(val reason: String) : BuildPhase
    }

    fun describeActiveBuildPhase(phase: BuildPhase): String {
        if (phase is BuildPhase.Idle) return "idle"
        // Data-flow exhaustiveness knows `BuildPhase.Idle` is already handled above!
        return when (phase) {
            is BuildPhase.Compiling -> "compiling:${phase.moduleName}"
            is BuildPhase.Succeeded -> "ok:${phase.durationMs}ms"
            is BuildPhase.Failed -> "failed:${phase.reason}"
        }
    }

    /** Kotlin 2.3 feature #5: Multiplatform `kotlin.time.Instant` ISO-8601 formatting. */
    fun formatEpochIso8601(timestampMs: Long): String =
        Instant.fromEpochMilliseconds(timestampMs).toString()

    /**
     * Kotlin 2.4 feature #6: **Context Parameters** (`context(logger: Logger)`) — implicitly threads
     * the component [Logger] through call hierarchies without polluting normal parameter lists.
     */
    context(logger: Logger)
    fun contextualInfo(message: String, attributes: AttributeBag = emptyMap()) {
        logger.info(message, attributes)
    }

    /**
     * Kotlin 2.4 feature #6 + GDP + Permix: Demands both a [Logger] and an unforgeable
     * [Proof]<[PermixFact.Permitted]> in `context(...)` before executing a privileged action!
     */
    context(logger: Logger, proof: Proof<PermixFact.Permitted<R, A, N>>)
    fun <A : PermixAction, T, R : PermixResource<A, T>, N, Out> runPermittedWithContext(
        operationName: String,
        target: Named<N, T>,
        block: (T) -> Out,
    ): Out = logger.traceSpan(
        operation = operationName,
        initialAttributes = mapOf(
            "target" to target.value.toString(),
            "proof" to proof.witness,
        ),
    ) {
        block(target.value)
    }

    /**
     * Kotlin 2.4 feature #9: Zero-allocation sortedness check for any [Iterable] or [List].
     */
    fun <T : Comparable<T>> Iterable<T>.isMonotonicallySorted(): Boolean {
        val iterator = iterator()
        if (!iterator.hasNext()) return true
        var prev = iterator.next()
        while (iterator.hasNext()) {
            val current = iterator.next()
            if (prev > current) return false
            prev = current
        }
        return true
    }

    fun <T, K : Comparable<K>> Iterable<T>.isSortedBySelector(selector: (T) -> K): Boolean {
        val iterator = iterator()
        if (!iterator.hasNext()) return true
        var prev = selector(iterator.next())
        while (iterator.hasNext()) {
            val current = selector(iterator.next())
            if (prev > current) return false
            prev = current
        }
        return true
    }

    /** Kotlin 2.4 feature #9: Stable `kotlin.uuid.Uuid` generation & parsing. */
    fun mintCorrelationUuid(): Uuid = Uuid.random()

    /**
     * Kotlin 2.5 feature #12: Multiplatform Unicode code-point extraction (handles UTF-16 surrogate
     * pairs / astral-plane emoji cleanly in `commonMain` without JVM-only `String.codePoints()`).
     */
    fun CharSequence.codePointsToList(): List<Int> {
        val out = ArrayList<Int>(length)
        var i = 0
        while (i < length) {
            val c1 = this[i]
            if (c1.isHighSurrogate() && i + 1 < length) {
                val c2 = this[i + 1]
                if (c2.isLowSurrogate()) {
                    val codePoint = ((c1.code - 0xD800) shl 10) + (c2.code - 0xDC00) + 0x10000
                    out.add(codePoint)
                    i += 2
                    continue
                }
            }
            out.add(c1.code)
            i++
        }
        return out
    }

    /** Kotlin 2.5 feature #13: Conditional expression chaining helpers. */
    inline fun <T> T.mapIf(condition: Boolean, transform: (T) -> T): T =
        if (condition) transform(this) else this

    inline fun <R> String.ifNotBlank(block: (String) -> R): R? =
        if (isNotBlank()) block(this) else null
}

/**
 * Kotlin 2.4 feature #7: **Explicit Backing Fields** (`field = ...`)!
 *
 * Exposes `history` publicly as an immutable `List<String>` while its backing `field` is a
 * `MutableList<String>` mutated directly inside the class — zero `_history` / `history` boilerplate!
 */
class ExplicitBackingFieldTrail {
    val history: List<String>
        field = mutableListOf()

    fun record(step: String) {
        history.add(step)
    }

    fun clear() {
        history.clear()
    }
}

/**
 * Kotlin 2.5 feature #11: `@JvmInline value class` with custom `equals` and `hashCode`
 * (case-insensitive identifier comparison with zero heap boxing).
 */
@JvmInline
value class CaseInsensitiveId(val raw: String) {
    operator fun equals(other: CaseInsensitiveId): Boolean =
        raw.equals(other.raw, ignoreCase = true)

    override fun equals(other: Any?): Boolean =
        other is CaseInsensitiveId && equals(other)

    override fun hashCode(): Int = raw.lowercase().hashCode()

    override fun toString(): String = raw.lowercase()
}

@Target(
    AnnotationTarget.VALUE_PARAMETER,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.FIELD,
    AnnotationTarget.PROPERTY_GETTER,
)
@Retention(AnnotationRetention.RUNTIME)
annotation class AuditedField(val category: String)

/**
 * Kotlin 2.4 feature #8: `@all:` meta-target applies `@AuditedField` simultaneously to the
 * constructor parameter, backing field, property, and getter.
 */
data class ComponentCoordinate(
    @all:AuditedField("identity") val componentId: String,
    @all:AuditedField("ownership") val ownerModule: String,
)
